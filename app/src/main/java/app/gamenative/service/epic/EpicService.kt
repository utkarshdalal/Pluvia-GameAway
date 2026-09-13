package app.gamenative.service.epic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.EpicGameToken
import app.gamenative.utils.MarkerUtils
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeTreeDelete
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.NotificationHelper
import com.winlator.container.Container
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.*
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber

/**
 * Epic Games Service - thin coordinator that delegates to other Epic managers.
 */
@AndroidEntryPoint
class EpicService : Service() {

    companion object {
        private var instance: EpicService? = null

        private const val ACTION_SYNC_LIBRARY = "app.gamenative.EPIC_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.EPIC_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {

            Timber.tag("EPIC").d("Starting service...")
            // If already running, do nothing
            if (isRunning) {
                Timber.tag("EPIC").d("[EpicService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.tag("EPIC").i("[EpicService] First-time start - starting service with initial sync")
                val intent = Intent(context, EpicService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: always start service, but check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, EpicService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.tag("EPIC").i("[EpicService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.tag("EPIC").i("Starting service without sync - throttled (${remainingMinutes}min remaining)")
                // Start service without sync action
            }
            context.startForegroundService(intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.tag("EPIC").i("Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, EpicService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to EpicAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
            return EpicAuthManager.authenticateWithCode(context, authorizationCode)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return EpicAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
            return EpicAuthManager.getStoredCredentials(context)
        }

        /**
         * Logout from Epic - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.tag("EPIC").i("Logging out from Epic...")

                    // Clear stored credentials first, regardless of service state
                    val credentialsCleared = EpicAuthManager.clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.tag("Epic").e("Failed to clear credentials during logout")
                        return@withContext Result.failure(Exception("Failed to clear stored credentials"))
                    }

                    // Get instance to clean up service-specific data
                    val instance = getInstance()
                    if (instance != null) {
                        // Clear all nonInstalled Epic games from database
                        instance.epicManager.deleteAllNonInstalledGames()
                        Timber.tag("Epic").i("All Non-installed Epic games removed from database")

                        // Stop the service
                        stop()
                    } else {
                        Timber.tag("Epic").w("Service not running during logout, but credentials were cleared")
                    }

                    Timber.tag("Epic").i("Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error during logout")
                    Result.failure(e)
                }
            }
        }

        // ==========================================================================
        // SYNC & OPERATIONS
        // ==========================================================================

        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()
        }

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
            if (inProgress) getInstance()?.notifierOrNull?.showSyncing(NotificationHelper.NOTIFICATION_ID_EPIC)
            else getInstance()?.notifierOrNull?.showIdle(NotificationHelper.NOTIFICATION_ID_EPIC)
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): EpicService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance EpicManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        fun getCurrentlyDownloadingGame(): Int? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        fun getDownloadInfo(appId: Int): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(appId)
        }

        fun getActiveDownloads(): Map<Int, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        fun hasPartialDownload(context: Context, appId: Int): Boolean {
            val game = getEpicGameOf(appId) ?: return false
            if (game.isInstalled) return false
            val appName = game.appName.ifBlank { return false }
            val installPath = EpicConstants.getGameInstallPath(context, appName)
            return MarkerUtils.hasPartialInstall(installPath)
        }

        private fun getPartialInstallPaths(context: Context): Set<String> {
            val roots = buildList {
                add(EpicConstants.internalEpicGamesPath(context))
                if (app.gamenative.PrefManager.externalStoragePath.isNotBlank()) {
                    add(EpicConstants.externalEpicGamesPath())
                }
            }.distinct()

            return roots.asSequence()
                .flatMap { root -> MarkerUtils.findResumablePartialInstalls(root).asSequence() }
                .toSet()
        }

        suspend fun getPartialDownloads(): List<Int> {
            val instance = getInstance() ?: return emptyList()
            val context = instance.applicationContext
            val partialInstallPaths = getPartialInstallPaths(context)
            if (partialInstallPaths.isEmpty()) return emptyList()

            return instance.epicManager.getNonInstalledGames()
                .asSequence()
                .filter { game -> !instance.activeDownloads.containsKey(game.id) }
                .filter { game ->
                    val appName = game.appName.ifBlank { return@filter false }
                    partialInstallPaths.contains(EpicConstants.getGameInstallPath(context, appName))
                }
                .map { it.id }
                .toList()
        }

        suspend fun deleteGame(context: Context, appId: Int): Result<Unit> {
            val instance = getInstance()
            if (instance == null) {
                return Result.failure(Exception("Service not available"))
            }

            return try {
                // Get the game to find its install path
                val game = instance.epicManager.getGameById(appId)
                if (game == null) {
                    return Result.failure(Exception("Game not found: $appId"))
                }

                val path = if (game.installPath.isNotEmpty()) game.installPath else EpicConstants.getGameInstallPath(context, game.appName)
                if (File(path).exists()) {
                    Timber.tag("Epic").i("Deleting installation folder: $path")
                    val deleted = NativeTreeDelete.deleteTreeFast(File(path))
                    if (deleted) {
                        Timber.tag("Epic").i("Successfully deleted installation folder")
                    } else {
                        Timber.tag("Epic").w("Failed to delete some files in installation folder")
                    }
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }

                // Drop any leftover chunk cache (kept on failed downloads for resume)
                NativeTreeDelete.deleteTreeFast(EpicDownloadManager.chunkCacheDirFor(context, path))

                // Uninstall from database (keeps the entry but marks as not installed)
                instance.epicManager.uninstall(appId)

                // Delete container
                // Use game.id (the auto-generated numeric Room DB primary key) to match the container
                // ID format used at creation time: "EPIC_${libraryItem.gameId}" = "EPIC_${game.id}".
                // Previously used game.appName (the Legendary identifier, e.g. a UUID) which never
                // matched the stored container ID, causing orphaned containers.
                withContext(Dispatchers.Main) {
                    ContainerUtils.deleteContainer(context, "EPIC_${game.id}")
                }

                // Trigger library refresh event
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(appId, app.gamenative.data.GameSource.EPIC)
                )

                Timber.tag("Epic").i("Game uninstalled: $appId")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to uninstall game: $appId")
                Result.failure(e)
            }
        }

        suspend fun cleanupDownload(context: Context, appId: Int) {
            withContext(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameById(appId)?.let { game ->
                    val path = EpicConstants.getGameInstallPath(context, game.appName)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }
            }
            getInstance()?.activeDownloads?.remove(appId)
        }

        fun cancelDownload(appId: Int): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(appId)

            return if (downloadInfo != null) {
                Timber.tag("EPIC").i("Cancelling download for Epic game: $appId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(appId)
                Timber.tag("EPIC").d("Download cancelled for Epic game: $appId")
                true
            } else {
                Timber.w("No active download found for Epic game: $appId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS
        // ==========================================================================

        fun getEpicGameOf(appId: Int): EpicGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameById(appId)
            }
        }

        fun getEpicGameByAppName(appName: String): EpicGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getGameByAppName(appName)
            }
        }

        fun getDLCForGame(appId: Int): List<EpicGame> {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.epicManager?.getDLCForTitle(appId) ?: emptyList()
            }
        }

        suspend fun updateEpicGame(game: EpicGame) {
            getInstance()?.epicManager?.updateGame(game)
        }


        fun isGameInstalled(context: Context, appId: Int): Boolean {
            val game = getEpicGameOf(appId) ?: return false

            if (game.isInstalled && game.installPath.isNotEmpty()) {
                return MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            }

            val installPath = game.installPath.takeIf { it.isNotEmpty() }
                ?: game.appName.takeIf { it.isNotEmpty() }?.let {
                    EpicConstants.getGameInstallPath(context, it)
                }
                ?: return false

            val isDownloadComplete = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            if (isDownloadComplete && !isDownloadInProgress) {
                val updatedGame = game.copy(
                    isInstalled = true,
                    installPath = installPath,
                )
                runBlocking(Dispatchers.IO) {
                    getInstance()?.epicManager?.updateGame(updatedGame)
                }
                return true
            }

            return false
        }

        fun getInstallPath(appId: Int): String? {
            val game = getEpicGameOf(appId)
            return if (game?.isInstalled == true && game.installPath.isNotEmpty()) {
                game.installPath
            } else {
                null
            }
        }

        fun updateInstallPath(appId: Int, path: String) {
            runBlocking(Dispatchers.IO) {
                val game = getInstance()?.epicManager?.getGameById(appId) ?: return@runBlocking
                if (game.installPath != path) {
                    getInstance()?.epicManager?.updateGame(game.copy(installPath = path))
                }
            }
        }

        suspend fun getInstalledExe(appId: Int): String {
            return getInstance()?.epicManager?.getInstalledExe(appId) ?: ""
        }

        /**
         * Resolves the effective launch executable for an Epic game.
         * Container id is expected to be "EPIC_&lt;numericId&gt;" (from library). Returns empty if
         * game is not installed, no executable can be found, or containerId cannot be parsed.
         */
        suspend fun getLaunchExecutable(containerId: String): String {
            val gameId = try {
                ContainerUtils.extractGameIdFromContainerId(containerId)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to parse Epic containerId: $containerId")
                return ""
            }
            return getInstance()?.epicManager?.getLaunchExecutable(gameId) ?: ""
        }

        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.epicManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        suspend fun fetchManifestSizes(context: Context, appId: Int): EpicManager.ManifestSizes {
            return getInstance()?.epicManager?.fetchManifestSizes(context, appId)
                ?: EpicManager.ManifestSizes(installSize = 0L, downloadSize = 0L)
        }

        fun downloadGame(context: Context, appId: Int, dlcGameIds: List<Int>, installPath: String, containerLanguage: String): Result<DownloadInfo> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            val game = runBlocking { instance.epicManager.getGameById(appId) }
                ?: return Result.failure(Exception("Game not found for appId: $appId"))
            val gameId = game.id ?: return Result.failure(Exception("Game ID not found for appId: $appId"))

            // Create DownloadInfo before claiming (avoids holding the lock during I/O)
            val downloadInfo = DownloadInfo(
                jobCount = 1,
                gameId = appId,
                downloadingAppIds = CopyOnWriteArrayList<Int>(),
            )
            downloadInfo.setPersistencePath(installPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(installPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            // Atomically claim appId: check, stale-entry replacement, and publication
            // under one lock so concurrent downloadGame calls cannot both start a job.
            synchronized(instance.activeDownloads) {
                val existing = instance.activeDownloads[appId]
                if (existing != null) {
                    if (existing.isActive()) {
                        Timber.tag("Epic").w("Download already in progress for $appId")
                        return Result.success(existing)
                    }
                    // Stale inactive entry (e.g. a queued download being resumed).
                    instance.activeDownloads.remove(appId, existing)
                }
                instance.activeDownloads[appId] = downloadInfo
            }
            instance.activeDlcSelections[appId] = dlcGameIds
            downloadInfo.setActive(true)
            instance.notifierOrNull?.trackDownload(downloadInfo, game.title ?: "", NotificationHelper.NOTIFICATION_ID_EPIC)

            // Register with centralized queue and auto-pause other downloads
            GameDownloadService.registerDownload(
                gameSource = GameSource.EPIC,
                gameId = appId.toString(),
                downloadInfo = downloadInfo
            )

            // Start download in background
            val job = instance.scope.launch {
                try {
                    val commonRedistDir = File(installPath, "_CommonRedist")
                    Timber.tag("Epic").i("Starting download for game: ${game.title}, gameId: ${game.id}")

                    val result = instance.epicDownloadManager.downloadGame(
                        context,
                        game,
                        installPath,
                        downloadInfo,
                        containerLanguage,
                        dlcGameIds,
                        commonRedistDir,
                    )

                    Timber.tag("Epic").d("Download result: ${if (result.isSuccess) "SUCCESS" else "FAILURE: ${result.exceptionOrNull()?.message}"}")

                    if (result.isSuccess) {
                        Timber.i("[Download] Completed successfully for game $gameId")

                        // A completed download must never remain queued: clear the
                        // paused state so the finally below drops the active-map
                        // entry even if a stray auto-pause landed in a race window.
                        downloadInfo.clearQueuedState()

                        // Transfer is complete — free the queue slot BEFORE post-install
                        // sync so the next queued download can start. Holding the slot
                        // through sync also lets a newly registered download auto-pause
                        // this finished one; its later auto-resume re-verifies every
                        // file ("1/N again" after reaching 100%).
                        GameDownloadService.unregisterDownload(GameSource.EPIC, appId.toString())

                        // Download cloud saves so they're ready before first launch.
                        // Status message keeps isDownloading() true so Play stays hidden during sync.
                        val epicAppId = "EPIC_$gameId"
                        if (game.cloudSaveEnabled && !ContainerUtils.isLocalSavesOnly(context, epicAppId)) {
                            downloadInfo.setPostInstallSyncing(true)
                            PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, true))
                            downloadInfo.updateStatusMessage("Syncing saves...")
                            try {
                                EpicCloudSavesManager.syncCloudSaves(
                                    context = context,
                                    appId = gameId,
                                    preferredAction = "download",
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "[PostInstallSync] Cloud save sync failed for game $gameId")
                            } finally {
                                downloadInfo.setPostInstallSyncing(false)
                                downloadInfo.updateStatusMessage(null)
                                PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, false))
                            }
                        }

                        SnackbarManager.show("Download completed successfully!")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[Download] Failed for game $gameId")
                        downloadInfo.setActive(false)

                        if (GameDownloadService.reportFailure(GameSource.EPIC, appId.toString(), error?.message)) {
                            // Transient failure: the queue holds the slot and
                            // auto-retries with backoff. The retry marker set
                            // wasAutoPaused, so the finally below keeps the
                            // active-map entry (UI shows Queued) and the DLC
                            // selection for the resumed run.
                        } else {
                            downloadInfo.setProgress(-1.0f)
                            SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")

                            // Unregister from queue so a paused download can resume
                            GameDownloadService.unregisterDownload(GameSource.EPIC, appId.toString())
                        }
                    }
                } catch (e: CancellationException) {
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, false))
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId, false))
                    downloadInfo.setActive(false)

                    if (GameDownloadService.reportFailure(GameSource.EPIC, appId.toString(), e.message)) {
                        // Transient failure: the queue holds the slot and
                        // auto-retries with backoff. The retry marker set
                        // wasAutoPaused, so the finally below keeps the
                        // active-map entry (UI shows Queued) and the DLC
                        // selection for the resumed run.
                    } else {
                        downloadInfo.setProgress(-1.0f)
                        SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")

                        // Unregister from queue so a paused download can resume
                        GameDownloadService.unregisterDownload(GameSource.EPIC, appId.toString())
                    }
                } finally {
                    // Keep an auto-paused (queued) entry in the map so the downloads UI
                    // keeps showing it as Queued and the resume listener can recover its
                    // DLC selection. remove(key, value) so a late finally never removes
                    // the fresh entry of an already-resumed download.
                    if (!downloadInfo.wasAutoPaused()) {
                        instance.activeDownloads.remove(appId, downloadInfo)
                        // remove(key, value): a stale finally must not wipe the DLC
                        // selection a resumed download already stored for this app.
                        instance.activeDlcSelections.remove(appId, dlcGameIds)
                    }
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }
            downloadInfo.setDownloadJob(job)

            // Return the DownloadInfo immediately so caller can track progress
            return Result.success(downloadInfo)
        }

        suspend fun refreshSingleGame(appId: Int, context: Context): Result<EpicGame?> {
            // For now, just get from database
            val game = getInstance()?.epicManager?.getGameById(appId)
            // TODO: Fix this up.
            return if (game != null) {
                Result.success(game)
            } else {
                Result.failure(Exception("Game not found: $appId"))
            }
        }

        // ==========================================================================
        // Game Launcher Helpers
        // ==========================================================================

        suspend fun getGameLaunchToken(
            context: Context,
            namespace: String? = null,
            catalogItemId: String? = null,
            requiresOwnershipToken: Boolean = false
        ): Result<EpicGameToken> {
            return EpicAuthManager.getGameLaunchToken(context, namespace, catalogItemId, requiresOwnershipToken)
        }

        suspend fun buildLaunchParameters(
            context: Context,
            container: Container,
            game: EpicGame,
            offline: Boolean = false,
            languageCode: String = "en-US"
        ): Result<List<String>> {
            return EpicGameLauncher.buildLaunchParameters(context, container, game, offline, languageCode)
        }

        fun cleanupLaunchTokens(context: Context, container: Container? = null) {
            EpicGameLauncher.cleanupOwnershipTokens(context, container)
        }

        // ==========================================================================
        // EOS OVERLAY
        // ==========================================================================

        /**
         * Install (or re-install) the EOS overlay into [container].
         *
         * Downloads the latest overlay from Epic's CDN, replaces incompatible DLLs
         * with Wine-compatible stubs, and writes the overlay path to the Wine registry.
         *
         * @param context         Android context.
         * @param container       Target Wine container.
         * @param forceReinstall  Re-download even if the overlay appears installed.
         * @param onProgress      Optional callback: (downloadedChunks, totalChunks).
         */
        suspend fun installOverlay(
            context: Context,
            container: Container,
            forceReinstall: Boolean = false,
            onProgress: ((Int, Int) -> Unit)? = null,
        ): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("EpicService not running"))
            return instance.epicOverlayManager.installOverlay(
                context, container, forceReinstall, onProgress,
            )
        }

        /**
         * Remove the EOS overlay from [container] and clear its registry entry.
         */
        suspend fun removeOverlay(context: Context, container: Container): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("EpicService not running"))
            return instance.epicOverlayManager.removeOverlay(context, container)
        }

        // ==========================================================================
        // CLOUD SAVES HELPERS
        // ==========================================================================

        /**
         * Get the Epic account ID from stored credentials
         */
        fun getAccountId(): String? {
            return try {
                val context = getInstance()?.applicationContext ?: return null
                val credentialsResult = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    EpicAuthManager.getStoredCredentials(context)
                }
                credentialsResult.getOrNull()?.accountId
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to get account ID")
                null
            }
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    private val notifierOrNull: NotificationHelper? get() = if (::notificationHelper.isInitialized) notificationHelper else null

    @Inject
    lateinit var epicManager: EpicManager

    @Inject
    lateinit var epicDownloadManager: EpicDownloadManager

    @Inject
    lateinit var epicOverlayManager: EpicOverlayManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by GameNative Int ID
    private val activeDownloads = ConcurrentHashMap<Int, DownloadInfo>()
    /** DLC selection per active download, so a queue-resumed download keeps its DLC. */
    private val activeDlcSelections = ConcurrentHashMap<Int, List<Int>>()

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.tag("Epic").i("[EpicService] Service created")

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Register resume listener with GameDownloadService
        GameDownloadService.registerResumeListener(GameSource.EPIC, object : GameDownloadService.ResumeListener {
            override fun onResumeRequested(gameSource: GameSource, gameId: String) {
                val appId = gameId.toIntOrNull() ?: return
                Timber.tag("Epic").i("[EpicService] Resume requested for app $appId")
                // Capture synchronously, before launch: the queue clears the queued
                // state before invoking this, and the cancelled download's late
                // finally could otherwise run before the coroutine reads the map.
                val dlcGameIds = instance?.activeDlcSelections?.get(appId).orEmpty()
                scope.launch {
                    val game = epicManager.getGameById(appId)
                    if (game != null) {
                        val installPath = game.installPath.ifBlank {
                            EpicConstants.getGameInstallPath(applicationContext, game.appName)
                        }
                        val container = ContainerUtils.getOrCreateContainer(applicationContext, "EPIC_$appId")
                        val language = ContainerUtils.toContainerData(container).language
                        downloadGame(applicationContext, appId, dlcGameIds, installPath, language)
                    }
                }
            }
        })

        PluviaApp.events.emit(AndroidEvent.ServiceReady)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag("EPIC").d("onStartCommand() - action: ${intent?.action}")

        val instance = getInstance()
        // Start as foreground service
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_EPIC, "Connected")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_EPIC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_EPIC, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_EPIC)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.tag("EPIC").i("Manual sync requested - bypassing throttle")
                true
            }

            ACTION_SYNC_LIBRARY -> {
                Timber.tag("EPIC").i("Automatic sync requested")
                true
            }

            null -> {
                // Service restarted by Android with null intent (START_STICKY behavior)
                // Only sync if we haven't done initial sync yet, or if it's been a while
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                if (shouldResync) {
                    Timber.tag("EPIC").i("Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)")
                    true
                } else {
                    Timber.tag("EPIC").d("Service restarted by Android - skipping sync (throttled)")
                    false
                }
            }

            else -> {
                // Service started without sync action (e.g., just to keep it alive)
                Timber.tag("EPIC").d(" Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.tag("EPIC").i("Starting background library sync")

            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.tag("EPIC").d("Starting background library sync")
                    val syncResult = epicManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.tag("EPIC").i("Background library sync completed successfully")
                        // Update last sync timestamp on successful sync
                        lastSyncTimestamp = System.currentTimeMillis()
                        // Mark that initial sync has been performed
                        hasPerformedInitialSync = true
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.tag("EPIC").d("Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.tag("EPIC").w("Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag("Epic").i("[EpicService] Service destroyed")
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_EPIC)

        // Drop this source's queue entries before removing the listener that resumes them
        GameDownloadService.unregisterAllForSource(GameSource.EPIC)
        // Unregister resume listener from GameDownloadService
        GameDownloadService.unregisterResumeListener(GameSource.EPIC)

        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.tag("Epic").i("Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.tag("Epic").i("Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
