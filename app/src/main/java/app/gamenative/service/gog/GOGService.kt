package app.gamenative.service.gog

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GOGCredentials
import app.gamenative.data.GOGGame
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.service.download.GameDownloadService
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.*
import timber.log.Timber


/**
 * GOG Service - thin abstraction layer that delegates to managers.
 *
 * Architecture:
 * - GOGApiClient: Api Layer for interacting with GOG's APIs
 * - GOGDownloadManager: Handles Download Logic for Games
 * - GOGConstants: Shared Constants for our GOG-related data
 * - GOGCloudSavesManager: Handler for Cloud Saves
 * - GOGAuthManager: Authentication and account management
 * - GOGManager: Game library, downloads, and installation
 * - GOGManifestParser: Parses and has utils for parsing/extracting/decompressing manifests.
 * - GOGDataMdoels: Data Models for GOG-related Data types such as API responses
 *
 */
@AndroidEntryPoint
class GOGService : Service() {

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.GOG_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.GOG_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: GOGService? = null

        // Sync tracking variables
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            // If already running, do nothing
            if (isRunning) {
                Timber.d("[GOGService] Service already running, skipping start")
                return
            }

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.i("[GOGService] First-time start - starting service with initial sync")
                val intent = Intent(context, GOGService::class.java)
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: always start service, but check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            val intent = Intent(context, GOGService::class.java)
            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[GOGService] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.d("[GOGService] Starting service without sync - throttled (${remainingMinutes}min remaining)")
                // Start service without sync action
            }
            context.startForegroundService(intent)
        }

        fun triggerLibrarySync(context: Context) {
            Timber.i("[GOGService] Triggering manual library sync (bypasses throttle)")
            val intent = Intent(context, GOGService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.let { service ->
                service.stopSelf()
            }
        }

        // ==========================================================================
        // AUTHENTICATION - Delegate to GOGAuthManager
        // ==========================================================================

        suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
            return GOGAuthManager.authenticateWithCode(context, authorizationCode)
        }

        fun hasStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.hasStoredCredentials(context)
        }

        suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
            return GOGAuthManager.getStoredCredentials(context)
        }

        suspend fun validateCredentials(context: Context): Result<Boolean> {
            return GOGAuthManager.validateCredentials(context)
        }

        fun clearStoredCredentials(context: Context): Boolean {
            return GOGAuthManager.clearStoredCredentials(context)
        }

        /**
         * Logout from GOG - clears credentials, database, and stops service
         */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.i("[GOGService] Logging out from GOG...")

                    // Get instance first before stopping the service
                    val instance = getInstance()
                    if (instance == null) {
                        Timber.w("[GOGService] Service instance not available during logout")
                        return@withContext Result.failure(Exception("Service not running"))
                    }

                    // Clear stored credentials
                    val credentialsCleared = clearStoredCredentials(context)
                    if (!credentialsCleared) {
                        Timber.w("[GOGService] Failed to clear credentials during logout")
                    }

                    // Clear all non-installed GOG games from database
                    instance.gogManager.deleteAllNonInstalledGames()
                    Timber.i("[GOGService] All non-installed GOG games removed from database")

                    // Hidden-game metadata belongs to the logged-out account.
                    instance.gogManager.clearHiddenFlags()

                    // Stop the service
                    stop()

                    Timber.i("[GOGService] Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService] Error during logout")
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
            if (inProgress) getInstance()?.notifierOrNull?.showSyncing(NotificationHelper.NOTIFICATION_ID_GOG)
            else getInstance()?.notifierOrNull?.showIdle(NotificationHelper.NOTIFICATION_ID_GOG)
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        fun getInstance(): GOGService? = instance

        // ==========================================================================
        // DOWNLOAD OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun hasActiveDownload(): Boolean {
            return getInstance()?.activeDownloads?.isNotEmpty() ?: false
        }

        fun getCurrentlyDownloadingGame(): String? {
            return getInstance()?.activeDownloads?.keys?.firstOrNull()
        }

        fun getDownloadInfo(gameId: String): DownloadInfo? {
            return getInstance()?.activeDownloads?.get(gameId)
        }

        fun getActiveDownloads(): Map<String, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        private fun hasPartialDownload(game: GOGGame): Boolean {
            if (game.isInstalled) return false
            val title = game.title.ifBlank { return false }
            val installPath = GOGConstants.getGameInstallPath(title)
            return app.gamenative.utils.MarkerUtils.hasPartialInstall(installPath)
        }

        fun hasPartialDownload(gameId: String, fallbackTitle: String? = null): Boolean {
            getGOGGameOf(gameId)?.let { return hasPartialDownload(it) }
            val title = fallbackTitle?.ifBlank { null } ?: return false
            val installPath = GOGConstants.getGameInstallPath(title)
            return app.gamenative.utils.MarkerUtils.hasPartialInstall(installPath)
        }

        private fun getPartialInstallPaths(): Set<String> {
            val roots = buildList {
                add(GOGConstants.internalGOGGamesPath)
                if (app.gamenative.PrefManager.externalStoragePath.isNotBlank()) {
                    add(GOGConstants.externalGOGGamesPath)
                }
            }.distinct()

            return roots.asSequence()
                .flatMap { root -> app.gamenative.utils.MarkerUtils.findResumablePartialInstalls(root).asSequence() }
                .toSet()
        }

        suspend fun getPartialDownloads(): List<String> {
            val instance = getInstance() ?: return emptyList()
            val partialInstallPaths = getPartialInstallPaths()
            if (partialInstallPaths.isEmpty()) return emptyList()

            return instance.gogManager.getNonInstalledGames()
                .asSequence()
                .filter { game -> !instance.activeDownloads.containsKey(game.id) }
                .filter { game ->
                    val title = game.title.ifBlank { return@filter false }
                    partialInstallPaths.contains(GOGConstants.getGameInstallPath(title))
                }
                .map { it.id }
                .toList()
        }

        fun cleanupDownload(gameId: String) {
            getInstance()?.activeDownloads?.remove(gameId)
        }

        fun cancelDownload(gameId: String): Boolean {
            val instance = getInstance()
            val downloadInfo = instance?.activeDownloads?.get(gameId)

            return if (downloadInfo != null) {
                Timber.i("Cancelling download for game: $gameId")
                downloadInfo.cancel()
                instance.activeDownloads.remove(gameId)
                Timber.d("Download cancelled for game: $gameId")
                true
            } else {
                Timber.w("No active download found for game: $gameId")
                false
            }
        }

        // ==========================================================================
        // GAME & LIBRARY OPERATIONS - Delegate to instance GOGManager
        // ==========================================================================

        fun getGOGGameOf(gameId: String): GOGGame? {
            return runBlocking(Dispatchers.IO) {
                getInstance()?.gogManager?.getGameFromDbById(gameId)
            }
        }

        suspend fun updateGOGGame(game: GOGGame) {
            getInstance()?.gogManager?.updateGame(game)
        }

        fun isGameInstalled(gameId: String): Boolean {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameFromDbById(gameId)
                if (game?.isInstalled != true) {
                    return@runBlocking false
                }

                // Verify the installation is actually valid
                val (isValid, errorMessage) = getInstance()?.gogManager?.verifyInstallation(gameId)
                    ?: Pair(false, "Service not available")
                if (!isValid) {
                    Timber.w("Game $gameId marked as installed but verification failed: $errorMessage")
                }
                isValid
            }
        }

        fun getInstallPath(gameId: String): String? {
            return runBlocking(Dispatchers.IO) {
                val game = getInstance()?.gogManager?.getGameFromDbById(gameId)
                if (game?.isInstalled == true) game.installPath else null
            }
        }

        fun updateInstallPath(gameId: String, path: String) {
            runBlocking(Dispatchers.IO) {
                val manager = getInstance()?.gogManager ?: return@runBlocking
                val game = manager.getGameFromDbById(gameId) ?: return@runBlocking
                if (game.installPath != path) manager.updateGame(game.copy(installPath = path))
            }
        }

        fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
            return getInstance()?.gogManager?.verifyInstallation(gameId)
                ?: Pair(false, "Service not available")
        }

        suspend fun getInstalledExe(libraryItem: LibraryItem): String {
            return getInstance()?.gogManager?.getInstalledExe(libraryItem)
                ?: ""
        }

        /**
         * Resolves the effective launch executable for a GOG game (container config or auto-detected).
         * Returns empty string if no executable can be found.
         */
        suspend fun getLaunchExecutable(appId: String, container: com.winlator.container.Container): String {
            return getInstance()?.gogManager?.getLaunchExecutable(appId, container) ?: ""
        }

        fun getGogWineStartCommand(
            libraryItem: LibraryItem,
            container: com.winlator.container.Container,
            bootToContainer: Boolean,
            appLaunchInfo: LaunchInfo?,
            envVars: com.winlator.core.envvars.EnvVars,
            guestProgramLauncherComponent: com.winlator.xenvironment.components.GuestProgramLauncherComponent,
            gameId: Int,
        ): String {
            return getInstance()?.gogManager?.getGogWineStartCommand(
                libraryItem, container, bootToContainer, appLaunchInfo, envVars, guestProgramLauncherComponent, gameId,
            ) ?: "\"explorer.exe\""
        }

        suspend fun refreshLibrary(context: Context): Result<Int> {
            return getInstance()?.gogManager?.refreshLibrary(context)
                ?: Result.failure(Exception("Service not available"))
        }

        fun downloadGame(context: Context, gameId: String, installPath: String, containerLanguage: String): Result<DownloadInfo?> {
            val instance = getInstance() ?: return Result.failure(Exception("Service not available"))

            // Create DownloadInfo for progress tracking
            val downloadInfo = DownloadInfo(jobCount = 1, gameId = 0, downloadingAppIds = CopyOnWriteArrayList<Int>())
            downloadInfo.setPersistencePath(installPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(installPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            // Atomically claim gameId: check, stale-entry replacement, and publication
            // under one lock so concurrent downloadGame calls cannot both start a job.
            synchronized(instance.activeDownloads) {
                val existing = instance.activeDownloads[gameId]
                if (existing != null) {
                    if (existing.isActive()) {
                        Timber.w("[Download] Already in progress for game $gameId")
                        return Result.success(existing)
                    }
                    // Stale inactive entry (e.g. a queued download being resumed).
                    instance.activeDownloads.remove(gameId, existing)
                }
                // Track in activeDownloads first
                instance.activeDownloads[gameId] = downloadInfo
            }
            instance.notifierOrNull?.trackDownload(downloadInfo, "", NotificationHelper.NOTIFICATION_ID_GOG)

            // Register with centralized queue and auto-pause other downloads
            GameDownloadService.registerDownload(
                gameSource = GameSource.GOG,
                gameId = gameId,
                downloadInfo = downloadInfo
            )

            // Launch download in service scope so it runs independently
            val job = instance.scope.launch {
                try {
                    Timber.d("[Download] Starting download for game $gameId")
                    val commonRedistDir = File(installPath, "_CommonRedist")
                    Timber.tag("GOG").d("Will install dependencies to _CommonRedist")

                    val result = instance.gogDownloadManager.downloadGame(
                        gameId, File(installPath),
                        downloadInfo, containerLanguage, true, commonRedistDir,
                    )

                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "[Download] Failed for game $gameId")
                        downloadInfo.setActive(false)

                        if (GameDownloadService.reportFailure(GameSource.GOG, gameId, error?.message)) {
                            // Transient failure: the queue holds the slot and
                            // auto-retries with backoff. The retry marker set
                            // wasAutoPaused, so the finally below keeps the
                            // active-map entry (UI shows Queued).
                        } else {
                            downloadInfo.setProgress(-1.0f)
                            SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")

                            // Unregister from queue so a paused download can resume
                            GameDownloadService.unregisterDownload(GameSource.GOG, gameId)
                        }
                    } else {
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
                        GameDownloadService.unregisterDownload(GameSource.GOG, gameId)

                        // Download cloud saves so they're ready before first launch.
                        // Status message keeps isDownloading() true so Play stays hidden during sync.
                        val appId = "GOG_$gameId"
                        val numericGameId = gameId.toIntOrNull()
                        try {
                            val gogGame = instance.gogManager.getGameFromDbById(gameId)
                            val locations = if (gogGame != null) instance.gogManager.getSaveDirectoryPath(context, appId, gogGame.title) else null
                            if (numericGameId != null && !locations.isNullOrEmpty() && !ContainerUtils.isLocalSavesOnly(context, appId)) {
                                try {
                                    downloadInfo.setPostInstallSyncing(true)
                                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(numericGameId, true))
                                    downloadInfo.updateStatusMessage("Syncing saves...")
                                    syncCloudSaves(context, appId, preferredAction = "download")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.e(e, "[PostInstallSync] Cloud save sync failed for game $gameId")
                                } finally {
                                    downloadInfo.setPostInstallSyncing(false)
                                    downloadInfo.updateStatusMessage(null)
                                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(numericGameId, false))
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.e(e, "[PostInstallSync] Cloud save sync failed for game $gameId")
                        }

                        SnackbarManager.show("Download completed successfully!")
                        downloadInfo.setProgress(1.0f)
                        downloadInfo.setActive(false)
                    }
                } catch (e: CancellationException) {
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId.toIntOrNull() ?: -1, false))
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[Download] Exception for game $gameId")
                    downloadInfo.setPostInstallSyncing(false)
                    downloadInfo.updateStatusMessage(null)
                    PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(gameId.toIntOrNull() ?: -1, false))
                    downloadInfo.setActive(false)

                    if (GameDownloadService.reportFailure(GameSource.GOG, gameId, e.message)) {
                        // Transient failure: the queue holds the slot and
                        // auto-retries with backoff. The retry marker set
                        // wasAutoPaused, so the finally below keeps the
                        // active-map entry (UI shows Queued).
                    } else {
                        downloadInfo.setProgress(-1.0f)
                        SnackbarManager.show("Download error: ${e.message ?: "Unknown error"}")

                        // Unregister from queue so a paused download can resume
                        GameDownloadService.unregisterDownload(GameSource.GOG, gameId)
                    }
                } finally {
                    // Remove from activeDownloads for both success and failure
                    // so UI knows download is complete and to prevent stale entries.
                    // Keep an auto-paused (queued) entry so the UI keeps showing it as
                    // Queued; remove(key, value) so a late finally never removes the
                    // fresh entry of an already-resumed download.
                    if (!downloadInfo.wasAutoPaused()) {
                        instance.activeDownloads.remove(gameId, downloadInfo)
                    }
                    Timber.d("[Download] Finished for game $gameId, progress: ${downloadInfo.getProgress()}, active: ${downloadInfo.isActive()}")
                }
            }
            downloadInfo.setDownloadJob(job)

            return Result.success(downloadInfo)
        }

        suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
            return getInstance()?.gogManager?.refreshSingleGame(gameId, context)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Delete/uninstall a GOG game
         * Delegates to GOGManager.deleteGame
         */
        suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
            return getInstance()?.gogManager?.deleteGame(context, libraryItem)
                ?: Result.failure(Exception("Service not available"))
        }

        /**
         * Sync GOG cloud saves for a game
         * @param context Android context
         * @param appId Game app ID (e.g., "gog_123456")
         * @param preferredAction Preferred sync action: "download", "upload", or "none"
         * @return true if sync succeeded, false otherwise
         */
        suspend fun syncCloudSaves(
            context: Context,
            appId: String,
            preferredAction: String = "none",
        ): Boolean = withContext(Dispatchers.IO) {
            try {
                Timber.tag("GOG").d("[Cloud Saves] syncCloudSaves called for $appId with action: $preferredAction")

                // Check if there's already a sync in progress for this appId
                val serviceInstance = getInstance()
                if (serviceInstance == null) {
                    Timber.tag("GOG").e("[Cloud Saves] Service instance not available for sync start")
                    return@withContext false
                }

                if (!serviceInstance.gogManager.startSync(appId)) {
                    Timber.tag("GOG").w("[Cloud Saves] Sync already in progress for $appId, skipping duplicate sync")
                    return@withContext false
                }

                try {
                    val instance = getInstance()
                    if (instance == null) {
                        Timber.tag("GOG").e("[Cloud Saves] Service instance not available")
                        return@withContext false
                    }

                    if (!GOGAuthManager.hasStoredCredentials(context)) {
                        Timber.tag("GOG").e("[Cloud Saves] Cannot sync saves: not authenticated")
                        return@withContext false
                    }

                    val authConfigPath = GOGAuthManager.getAuthConfigPath(context)
                    Timber.tag("GOG").d("[Cloud Saves] Using auth config path: $authConfigPath")

                    // Get game info
                    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                    Timber.tag("GOG").d("[Cloud Saves] Extracted game ID: $gameId from appId: $appId")
                    val game = instance.gogManager.getGameFromDbById(gameId.toString())

                    if (game == null) {
                        Timber.tag("GOG").e("[Cloud Saves] Game not found for appId: $appId")
                        return@withContext false
                    }
                    Timber.tag("GOG").d("[Cloud Saves] Found game: ${game.title}")

                    // Get save directory paths (Android runs games through Wine, so always Windows)
                    Timber.tag("GOG").d("[Cloud Saves] Resolving save directory paths for $appId")
                    val saveLocations = instance.gogManager.getSaveDirectoryPath(context, appId, game.title)

                    if (saveLocations == null || saveLocations.isEmpty()) {
                        Timber.tag("GOG").w("[Cloud Saves] No save locations found for game $appId (cloud saves may not be enabled)")
                        return@withContext false
                    }
                    Timber.tag("GOG").i("[Cloud Saves] Found ${saveLocations.size} save location(s) for $appId")

                    var allSucceeded = true

                    // Sync each save location
                    for ((index, location) in saveLocations.withIndex()) {
                        try {
                            Timber.tag("GOG").d("[Cloud Saves] Processing location ${index + 1}/${saveLocations.size}: '${location.name}'")

                            // Log directory state BEFORE sync
                            try {
                                val saveDir = java.io.File(location.location)
                                Timber.tag("GOG").d("[Cloud Saves] [BEFORE] Checking directory: ${location.location}")
                                Timber.tag("GOG").d("[Cloud Saves] [BEFORE] Directory exists: ${saveDir.exists()}, isDirectory: ${saveDir.isDirectory}")
                                if (saveDir.exists() && saveDir.isDirectory) {
                                    val filesBefore = saveDir.listFiles()
                                    if (filesBefore != null && filesBefore.isNotEmpty()) {
                                        Timber.tag("GOG").i(
                                            "[Cloud Saves] [BEFORE] ${filesBefore.size} files in '${location.name}': ${filesBefore.joinToString(", ") {
                                                it.name
                                            }}",
                                        )
                                    } else {
                                        Timber.tag("GOG").i("[Cloud Saves] [BEFORE] Directory '${location.name}' is empty")
                                    }
                                } else {
                                    Timber.tag("GOG").i("[Cloud Saves] [BEFORE] Directory '${location.name}' does not exist yet")
                                }
                            } catch (e: Exception) {
                                Timber.tag("GOG").e(e, "[Cloud Saves] [BEFORE] Failed to check directory")
                            }

                            // Get stored timestamp for this location
                            val timestampStr = instance.gogManager.getCloudSaveSyncTimestamp(appId, location.name)
                            val timestamp = timestampStr.toLongOrNull() ?: 0L

                            Timber.tag("GOG").i("[Cloud Saves] Syncing '${location.name}' for game $gameId (clientId: ${location.clientId}, path: ${location.location}, timestamp: $timestamp, action: $preferredAction)")

                            // Validate clientSecret is available
                            if (location.clientSecret.isEmpty()) {
                                Timber.tag("GOG").e("[Cloud Saves] Missing clientSecret for '${location.name}', skipping sync")
                                continue
                            }

                            val cloudSavesManager = GOGCloudSavesManager(context)
                            val newTimestamp = cloudSavesManager.syncSaves(
                                clientId = location.clientId,
                                clientSecret = location.clientSecret,
                                localPath = location.location,
                                dirname = location.name,
                                lastSyncTimestamp = timestamp,
                                preferredAction = preferredAction,
                            )

                            if (newTimestamp != timestamp) {
                                if (newTimestamp > 0) {
                                    // Success - store new timestamp
                                    instance.gogManager.setCloudSaveSyncTimestamp(appId, location.name, newTimestamp.toString())
                                    Timber.tag("GOG").d("[Cloud Saves] Updated timestamp for '${location.name}': $newTimestamp")

                                    // Log the save files in the directory after sync
                                    try {
                                        val saveDir = java.io.File(location.location)
                                        if (saveDir.exists() && saveDir.isDirectory) {
                                            val files = saveDir.listFiles()
                                            if (files != null && files.isNotEmpty()) {
                                                val fileList = files.joinToString(", ") { it.name }
                                                Timber.tag("GOG")
                                                    .i("[Cloud Saves] [$preferredAction] Files in '${location.name}': $fileList (${files.size} files)")

                                                // Log detailed file info
                                                files.forEach { file ->
                                                    val size = if (file.isFile) "${file.length()} bytes" else "directory"
                                                    Timber.tag("GOG").d("[Cloud Saves] [$preferredAction]   - ${file.name} ($size)")
                                                }
                                            } else {
                                                Timber.tag("GOG")
                                                    .w("[Cloud Saves] [$preferredAction] Directory '${location.name}' is empty at: ${location.location}")
                                            }
                                        } else {
                                            Timber.tag("GOG")
                                                .w("[Cloud Saves] [$preferredAction] Directory not found: ${location.location}")
                                        }
                                    } catch (e: Exception) {
                                        Timber.tag("GOG").e(e, "[Cloud Saves] Failed to list files in directory: ${location.location}")
                                    }

                                    Timber.tag("GOG")
                                        .i("[Cloud Saves] Successfully synced save location '${location.name}' for game $gameId")
                                } else {
                                    Timber.tag("GOG")
                                        .e("[Cloud Saves] Failed to sync save location '${location.name}' for game $gameId (timestamp: $newTimestamp)")
                                    allSucceeded = false
                                }
                            } else {
                                Timber.tag("GOG").i("[Cloud Saves] No save changes found for $appId")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag("GOG").e(e, "[Cloud Saves] Exception syncing save location '${location.name}' for game $gameId")
                            allSucceeded = false
                        }
                    }

                    if (allSucceeded) {
                        Timber.tag("GOG").i("[Cloud Saves] All save locations synced successfully for $appId")
                        return@withContext true
                    } else {
                        Timber.tag("GOG").w("[Cloud Saves] Some save locations failed to sync for $appId")
                        return@withContext false
                    }
                } finally {
                    // Always end the sync, even if an exception occurred
                    getInstance()?.gogManager?.endSync(appId)
                    Timber.tag("GOG").d("[Cloud Saves] Sync completed and lock released for $appId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Failed to sync cloud saves for App ID: $appId")
                return@withContext false
            }
        }

        data class GogConflict(
            val localTimestamp: Long,
            val remoteTimestamp: Long,
        )

        /**
         * Check every save location for a conflict (both local and cloud changed since the last
         * sync) WITHOUT uploading or downloading anything. Returns the first conflicting location's
         * timestamps (millis), or null if there is no conflict.
         *
         * Takes the per-app sync lock (startSync/endSync) for the duration of the read so it never
         * observes a half-synced snapshot: a concurrent syncSaves mutates local files, remote
         * metadata, and the stored timestamp, any of which would otherwise yield a wrong result.
         * If a sync is already running for this app, skips detection and returns null — that sync
         * reconciles state, and a real conflict surfaces on a later launch.
         */
        suspend fun detectCloudSaveConflict(
            context: Context,
            appId: String,
        ): GogConflict? = withContext(Dispatchers.IO) {
            val instance = getInstance() ?: return@withContext null
            if (!GOGAuthManager.hasStoredCredentials(context)) return@withContext null

            if (!instance.gogManager.startSync(appId)) {
                Timber.tag("GOG").d("[Cloud Saves] Sync already in progress for $appId, skipping conflict detection")
                return@withContext null
            }
            try {
                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                val game = instance.gogManager.getGameFromDbById(gameId.toString()) ?: return@withContext null
                val saveLocations = instance.gogManager.getSaveDirectoryPath(context, appId, game.title)
                    ?: return@withContext null
                val manager = GOGCloudSavesManager(context)

                for (location in saveLocations) {
                    if (location.clientSecret.isEmpty()) continue
                    val timestamp = instance.gogManager
                        .getCloudSaveSyncTimestamp(appId, location.name).toLongOrNull() ?: 0L
                    val conflict = manager.detectConflict(
                        clientId = location.clientId,
                        clientSecret = location.clientSecret,
                        localPath = location.location,
                        dirname = location.name,
                        lastSyncTimestamp = timestamp,
                    )
                    if (conflict != null) {
                        Timber.tag("GOG").i("[Cloud Saves] Conflict in '${location.name}' for $appId")
                        return@withContext GogConflict(conflict.localTimestamp, conflict.remoteTimestamp)
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("GOG").e(e, "[Cloud Saves] Conflict detection failed for $appId")
                null
            } finally {
                instance.gogManager.endSync(appId)
            }
        }
    }

    private lateinit var notificationHelper: NotificationHelper

    private val notifierOrNull: NotificationHelper? get() = if (::notificationHelper.isInitialized) notificationHelper else null

    @Inject
    lateinit var gogManager: GOGManager

    @Inject
    lateinit var gogDownloadManager: GOGDownloadManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active downloads by game ID
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    // GOGManager is injected by Hilt
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize notification helper for foreground service
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Register resume listener with GameDownloadService
        GameDownloadService.registerResumeListener(GameSource.GOG, object : GameDownloadService.ResumeListener {
            override fun onResumeRequested(gameSource: GameSource, gameId: String) {
                Timber.i("[GOGService] Resume requested for game $gameId")
                scope.launch {
                    val game = gogManager.getGameFromDbById(gameId)
                    if (game != null) {
                        val installPath = game.installPath.ifBlank {
                            GOGConstants.getGameInstallPath(game.title)
                        }
                        val container = ContainerUtils.getOrCreateContainer(applicationContext, "GOG_$gameId")
                        val language = ContainerUtils.toContainerData(container).language
                        downloadGame(applicationContext, gameId, installPath, language)
                    }
                }
            }
        })

        PluviaApp.events.emit(AndroidEvent.ServiceReady)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[GOGService] onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_GOG, "Connected")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_GOG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_GOG, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_GOG)

        // Determine if we should sync based on the action
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.i("[GOGService] Manual sync requested - bypassing throttle")
                true
            }

            ACTION_SYNC_LIBRARY -> {
                Timber.i("[GOGService] Automatic sync requested")
                true
            }

            null -> {
                // Service restarted by Android with null intent (START_STICKY behavior)
                // Only sync if we haven't done initial sync yet, or if it's been a while
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS

                if (shouldResync) {
                    Timber.i("[GOGService] Service restarted by Android - performing sync (hasPerformedInitialSync=$hasPerformedInitialSync, timeSinceLastSync=${timeSinceLastSync}ms)")
                    true
                } else {
                    Timber.d("[GOGService] Service restarted by Android - skipping sync (throttled)")
                    false
                }
            }

            else -> {
                // Service started without sync action (e.g., just to keep it alive)
                Timber.d("[GOGService] Service started without sync action")
                false
            }
        }

        // Start background library sync if requested
        if (shouldSync && (backgroundSyncJob == null || backgroundSyncJob?.isActive != true)) {
            Timber.i("[GOGService] Starting background library sync")
            backgroundSyncJob?.cancel() // Cancel any existing job
            backgroundSyncJob = scope.launch {
                try {
                    setSyncInProgress(true)
                    Timber.d("[GOGService]: Starting background library sync")

                    val syncResult = gogManager.startBackgroundSync(applicationContext)
                    if (syncResult.isFailure) {
                        Timber.w("[GOGService]: Failed to start background sync: ${syncResult.exceptionOrNull()?.message}")
                    } else {
                        Timber.i("[GOGService]: Background library sync completed successfully")
                        // Update last sync timestamp on successful sync
                        lastSyncTimestamp = System.currentTimeMillis()
                        // Mark that initial sync has been performed
                        hasPerformedInitialSync = true
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "[GOGService]: Exception starting background sync")
                } finally {
                    setSyncInProgress(false)
                }
            }
        } else if (shouldSync) {
            Timber.d("[GOGService] Background sync already in progress, skipping")
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("[GOGService] Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Cancel sync operations
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)

        scope.cancel() // Cancel any ongoing operations
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_GOG)

        // Drop this source's queue entries before removing the listener that resumes them
        GameDownloadService.unregisterAllForSource(GameSource.GOG)
        // Unregister resume listener from GameDownloadService
        GameDownloadService.unregisterResumeListener(GameSource.GOG)

        instance = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.tag("GOG").i("Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.tag("GOG").i("Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
