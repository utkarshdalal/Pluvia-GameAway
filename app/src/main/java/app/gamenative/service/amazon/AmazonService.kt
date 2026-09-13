package app.gamenative.service.amazon

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.AmazonCredentials
import app.gamenative.data.AmazonGame
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeTreeDelete
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.ExecutableSelectionUtils
import app.gamenative.utils.MarkerUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber

/** Amazon Games foreground service. */
@AndroidEntryPoint
class AmazonService : Service() {

    /** Entry point to access [AmazonGameDao] when service instance is unavailable. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AmazonDaoEntryPoint {
        fun amazonGameDao(): AmazonGameDao
    }

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var amazonManager: AmazonManager

    @Inject
    lateinit var amazonDownloadManager: AmazonDownloadManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active downloads keyed by Amazon product ID (e.g. "amzn1.adg.product.XXXX")
    private val activeDownloads = ConcurrentHashMap<String, DownloadInfo>()

    // Active install paths keyed by Amazon product ID (used for robust partial-download detection)
    private val activeDownloadPaths = ConcurrentHashMap<String, String>()

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.AMAZON_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.AMAZON_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes
        private var instance: AmazonService? = null

        // Sync tracking variables
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null

        private fun setSyncInProgress(inProgress: Boolean) {
            syncInProgress = inProgress
        }

        fun isSyncInProgress(): Boolean = syncInProgress

        /** Returns true when sync or download work is still active. */
        fun hasActiveOperations(): Boolean {
            return syncInProgress || backgroundSyncJob?.isActive == true || hasActiveDownload()
        }

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            if (isRunning) {
                Timber.d("[Amazon] Service already running")
                return
            }

            val intent = Intent(context, AmazonService::class.java)

            // First-time start: always sync without throttle
            if (!hasPerformedInitialSync) {
                Timber.i("[Amazon] First-time start — starting service with initial sync")
                intent.action = ACTION_SYNC_LIBRARY
                context.startForegroundService(intent)
                return
            }

            // Subsequent starts: check throttle for sync
            val now = System.currentTimeMillis()
            val timeSinceLastSync = now - lastSyncTimestamp

            if (timeSinceLastSync >= SYNC_THROTTLE_MILLIS) {
                Timber.i("[Amazon] Starting service with automatic sync (throttle passed)")
                intent.action = ACTION_SYNC_LIBRARY
            } else {
                val remainingMinutes = (SYNC_THROTTLE_MILLIS - timeSinceLastSync) / 1000 / 60
                Timber.i("[Amazon] Starting service without sync — throttled (${remainingMinutes}min remaining)")
            }
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.stopSelf()
        }

        fun getInstance(): AmazonService? = instance

        fun hasStoredCredentials(context: Context): Boolean =
            AmazonAuthManager.hasStoredCredentials(context)

        /** Authenticate with Amazon using a PKCE authorization code. */
        suspend fun authenticateWithCode(
            context: Context,
            authCode: String,
        ): Result<AmazonCredentials> = AmazonAuthManager.authenticateWithCode(context, authCode)

        /** Logout, clear credentials, delete non-installed entries, and stop the service. */
        suspend fun logout(context: Context): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    Timber.tag("Amazon").i("Starting logout...")

                    // Deregister device and clear credentials
                    AmazonAuthManager.logout(context)
                    Timber.tag("Amazon").i("Credentials cleared")

                    // Delete non-installed games from database
                    val svc = instance
                    if (svc != null) {
                        svc.amazonManager.deleteAllNonInstalledGames()
                        Timber.tag("Amazon").i("All non-installed Amazon games removed from database")
                    } else {
                        Timber.tag("Amazon").w("Service not running during logout — cleaning up DB via entry point")
                        val dao = EntryPointAccessors
                            .fromApplication(context.applicationContext, AmazonDaoEntryPoint::class.java)
                            .amazonGameDao()
                        withContext(Dispatchers.IO) { dao.deleteAllNonInstalledGames() }
                        Timber.tag("Amazon").i("Non-installed Amazon games removed from database (service was stopped)")
                    }

                    // Stop the service
                    stop()

                    Timber.tag("Amazon").i("Logout completed successfully")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Amazon").e(e, "Error during logout")
                    Result.failure(e)
                }
            }
        }

        /** Trigger a manual library sync, bypassing throttle. */
        fun triggerLibrarySync(context: Context) {
            Timber.i("[Amazon] Manual sync requested — bypassing throttle")
            val intent = Intent(context, AmazonService::class.java)
            intent.action = ACTION_MANUAL_SYNC
            context.startForegroundService(intent)
        }

        // ── Install queries ───────────────────────────────────────────────────

        /** Fetch and cache total download size for a game. */
        suspend fun fetchDownloadSize(productId: String): Long? {
            val svc = instance ?: return null
            val game = svc.amazonManager.getGameById(productId) ?: return null
            if (game.entitlementId.isBlank()) return null

            val token = svc.amazonManager.getBearerToken() ?: return null
            val size = AmazonApiClient.fetchDownloadSize(game.entitlementId, token) ?: return null

            // Cache in DB so we don't have to re-fetch next time
            svc.amazonManager.updateDownloadSize(productId, size)
            return size
        }

        /** Return whether a game is installed, using marker-based detection with DB reconciliation. */
        fun isGameInstalled(context: Context, productId: String): Boolean {
            val game = getAmazonGameOf(productId) ?: return false

            if (game.isInstalled && game.installPath.isNotEmpty()) {
                return MarkerUtils.hasMarker(game.installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            }

            val installPath = game.installPath.takeIf { it.isNotEmpty() }
                ?: game.title.takeIf { it.isNotEmpty() }?.let {
                    AmazonConstants.getGameInstallPath(context, it)
                }
                ?: return false

            val isDownloadComplete = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            if (isDownloadComplete && !isDownloadInProgress) {
                runBlocking(Dispatchers.IO) {
                    instance?.amazonManager?.markInstalled(productId, installPath, 0L)
                }
                return true
            }

            return false
        }

        /** Return whether a game is installed, looked up by appId. */
        fun isGameInstalledByAppId(context: Context, appId: Int): Boolean {
            val game = getAmazonGameByAppId(appId) ?: return false
            return isGameInstalled(context, game.productId)
        }

        /** Return expected install path for [appId], even when partially downloaded. */
        fun getExpectedInstallPathByAppId(context: Context, appId: Int): String? {
            val game = getAmazonGameByAppId(appId) ?: return null

            instance?.activeDownloadPaths?.get(game.productId)?.let { return it }

            val title = game.title.ifBlank { return null }
            return AmazonConstants.getGameInstallPath(context, title)
        }

        /** Steam-style partial detection: directory exists and completion marker is absent. */
        fun hasPartialDownloadByAppId(context: Context, appId: Int): Boolean {
            if (getDownloadInfoByAppId(appId) != null) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=true reason=active_download")
                return true
            }
            if (isGameInstalledByAppId(context, appId)) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=installed")
                return false
            }

            val expectedPath = getExpectedInstallPathByAppId(context, appId) ?: return false
            val installDir = File(expectedPath)
            if (!installDir.exists()) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=path_missing path=$expectedPath")
                return false
            }

            if (MarkerUtils.hasMarker(expectedPath, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=complete_marker path=$expectedPath")
                return false
            }
            if (MarkerUtils.hasMarker(expectedPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=true reason=in_progress_marker path=$expectedPath")
                return true
            }

            val children = installDir.listFiles() ?: return false
            if (children.isEmpty()) {
                Timber.tag("Amazon").d("[PARTIAL] appId=$appId partial=false reason=empty_dir path=$expectedPath")
                return false
            }

            val hasPartialPayload = children.any { child ->
                when (child.name) {
                    Marker.DOWNLOAD_COMPLETE_MARKER.fileName,
                    Marker.DOWNLOAD_IN_PROGRESS_MARKER.fileName,
                    ".DownloadInfo" -> {
                        child.isDirectory && (child.listFiles()?.any { it.isFile && it.length() > 0L } == true)
                    }
                    else -> true
                }
            }

            val childNames = children.joinToString(limit = 8) { it.name }
            Timber.tag("Amazon").d(
                "[PARTIAL] appId=$appId partial=$hasPartialPayload reason=dir_scan path=$expectedPath children=$childNames"
            )
            return hasPartialPayload
        }

        /** Return [AmazonGame] for a product ID, or null if unavailable. */
        fun getAmazonGameOf(productId: String): AmazonGame? {
            return runBlocking(Dispatchers.IO) {
                instance?.amazonManager?.getGameById(productId)
            }
        }

        /** Return [AmazonGame] for an appId, or null if unavailable. */
        fun getAmazonGameByAppId(appId: Int): AmazonGame? {
            return runBlocking(Dispatchers.IO) {
                instance?.amazonManager?.getGameByAppId(appId)
            }
        }

        /** Return install path for [productId], or null if not installed. */
        fun getInstallPath(productId: String): String? {
            val game = getAmazonGameOf(productId) ?: return null
            return if (game.isInstalled && game.installPath.isNotEmpty()) game.installPath else null
        }

        /** Return install path for [appId], or null if not installed. */
        fun getInstallPathByAppId(appId: Int): String? {
            val game = getAmazonGameByAppId(appId) ?: return null
            return if (game.isInstalled && game.installPath.isNotEmpty()) game.installPath else null
        }

        /** Persist a new install path for [appId] after an on-disk migration. */
        fun updateInstallPath(appId: Int, path: String) {
            runBlocking(Dispatchers.IO) {
                val game = instance?.amazonManager?.getGameByAppId(appId) ?: return@runBlocking
                if (game.isInstalled && game.installPath != path) {
                    instance?.amazonManager?.markInstalled(game.productId, path, game.installSize, game.versionId)
                }
            }
        }

        /** Convert appId to productId via DB lookup. */
        fun getProductIdByAppId(appId: Int): String? {
            return getAmazonGameByAppId(appId)?.productId
        }

        /**
         * Resolves the effective launch executable for an Amazon game.
         * Returns empty string if no executable can be found.
         */
        fun getLaunchExecutable(containerId: String): String {
            val appId = runCatching { ContainerUtils.extractGameIdFromContainerId(containerId) }.getOrElse { return "" }
            if (appId <= 0) return ""

            val installPath = getInstallPathByAppId(appId) ?: return ""
            val installDir = File(installPath)
            if (!installDir.isDirectory) return ""

            val exeFile = ExecutableSelectionUtils.choosePrimaryExeFromDisk(
                installDir = installDir,
                gameName = installDir.name,
            ) ?: return ""

            return exeFile.path
        }

        /** Deprecated name kept for call-site compatibility — delegates to [getInstallPath]. */
        fun getInstalledGamePath(gameId: String): String? = getInstallPath(gameId)

        /** Check whether an installed game has a newer live version. */
        suspend fun isUpdatePending(productId: String): Boolean {
            val svc = instance ?: return false
            val game = svc.amazonManager.getGameById(productId) ?: return false
            if (!game.isInstalled || game.versionId.isEmpty()) return false
            val token = svc.amazonManager.getBearerToken() ?: return false
            return AmazonApiClient.isUpdateAvailable(productId, game.versionId, token) ?: false
        }

        // ── Download management ───────────────────────────────────────────────

        /** Returns the active [DownloadInfo] for [productId], or null if not downloading. */
        fun getDownloadInfo(productId: String): DownloadInfo? =
            getInstance()?.activeDownloads?.get(productId)

        fun getActiveDownloads(): Map<String, DownloadInfo> =
            getInstance()?.activeDownloads?.let { HashMap(it) } ?: emptyMap()

        private fun getPartialInstallPaths(context: Context): Set<String> {
            val roots = buildList {
                add(AmazonConstants.internalAmazonGamesPath(context))
                if (app.gamenative.PrefManager.externalStoragePath.isNotBlank()) {
                    add(AmazonConstants.externalAmazonGamesPath())
                }
            }.distinct()

            return roots.asSequence()
                .flatMap { root -> MarkerUtils.findResumablePartialInstalls(root).asSequence() }
                .toSet()
        }

        suspend fun getPartialDownloads(context: Context): List<String> {
            val instance = getInstance() ?: return emptyList()
            val partialInstallPaths = getPartialInstallPaths(context)
            if (partialInstallPaths.isEmpty()) return emptyList()

            return instance.amazonManager.getNonInstalledGames()
                .asSequence()
                .filter { game -> !instance.activeDownloads.containsKey(game.productId) }
                .filter { game ->
                    val expectedPaths = buildList {
                        game.installPath.takeIf { it.isNotBlank() }?.let(::add)
                        add(AmazonConstants.getGameInstallPath(context, game.title))
                    }
                    expectedPaths.any(partialInstallPaths::contains)
                }
                .map { it.productId }
                .toList()
        }

        /** Returns the active [DownloadInfo] for [appId], or null if not downloading. */
        fun getDownloadInfoByAppId(appId: Int): DownloadInfo? {
            val productId = getProductIdByAppId(appId) ?: return null
            return getDownloadInfo(productId)
        }

        /** Cancel an in-progress download by [appId]. */
        fun cancelDownloadByAppId(appId: Int): Boolean {
            val productId = getProductIdByAppId(appId) ?: return false
            return cancelDownload(productId)
        }

        /** Check whether an installed game (by appId) has an update available. */
        suspend fun isUpdatePendingByAppId(appId: Int): Boolean {
            val productId = getProductIdByAppId(appId) ?: return false
            return isUpdatePending(productId)
        }

        /** Returns true if there is at least one active download. */
        fun hasActiveDownload(): Boolean =
            getInstance()?.activeDownloads?.isNotEmpty() == true

        /** Begin downloading [productId] to [installPath]. */
        suspend fun downloadGame(
            context: Context,
            productId: String,
            installPath: String,
        ): Result<DownloadInfo> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Amazon service is not running"))

            val game = withContext(Dispatchers.IO) {
                instance.amazonManager.getGameById(productId)
            } ?: return Result.failure(Exception("Game not found: $productId"))

            val downloadInfo = DownloadInfo(
                jobCount = 1,
                gameId = game.appId,
                downloadingAppIds = CopyOnWriteArrayList(),
            )
            downloadInfo.setPersistencePath(installPath)

            val persistedBytes = downloadInfo.loadPersistedBytesDownloaded(installPath)
            if (persistedBytes > 0L) {
                downloadInfo.initializeBytesDownloaded(persistedBytes)
            }

            // Atomically claim productId: check, stale-entry replacement, and
            // publication under one lock so concurrent downloadGame calls cannot
            // both start a job.
            synchronized(instance.activeDownloads) {
                instance.activeDownloads[productId]?.let { existing ->
                    if (existing.isActive()) {
                        Timber.tag("Amazon").w("Download already in progress for $productId")
                        return Result.success(existing)
                    }
                    // Stale inactive entry (e.g. a queued download being resumed).
                    instance.activeDownloads.remove(productId, existing)
                }
                instance.activeDownloads[productId] = downloadInfo
            }
            downloadInfo.setActive(true)
            instance.activeDownloadPaths[productId] = installPath

            // Fresh install/update run should clear stale completion marker before starting
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)

            PluviaApp.events.emitJava(
                AndroidEvent.DownloadStatusChanged(game.appId, true)
            )

            // Register with centralized queue and auto-pause other downloads
            GameDownloadService.registerDownload(
                gameSource = GameSource.AMAZON,
                gameId = productId,
                downloadInfo = downloadInfo
            )

            val job = instance.serviceScope.launch {
                try {
                    val result = instance.amazonDownloadManager.downloadGame(
                        context = context,
                        game = game,
                        installPath = installPath,
                        downloadInfo = downloadInfo,
                    )

                    if (result.isSuccess) {
                        Timber.tag("Amazon").i("Download succeeded for $productId")
                        // A completed download must never remain queued: clear the
                        // paused state so the finally below drops the active-map
                        // entry even if a stray auto-pause landed in a race window.
                        downloadInfo.clearQueuedState()
                        downloadInfo.setActive(false)
                        downloadInfo.clearPersistedBytesDownloaded(installPath)
                        SnackbarManager.show("Download completed: ${game.title}")
                        PluviaApp.events.emitJava(
                            AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.AMAZON)
                        )

                        // Unregister from queue (will auto-resume next paused download)
                        GameDownloadService.unregisterDownload(GameSource.AMAZON, productId)
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.tag("Amazon").e(error, "Download failed for $productId")
                        downloadInfo.setActive(false)

                        if (GameDownloadService.reportFailure(GameSource.AMAZON, productId, error?.message)) {
                            // Transient failure: the queue holds the slot and
                            // auto-retries with backoff. Keep the partial install
                            // (no cleanup) so the retry resumes from it; the retry
                            // marker set wasAutoPaused, so the finally below keeps
                            // the active-map entry (UI shows Queued).
                        } else {
                            instance.cleanupFailedInstall(context, game, installPath)
                            SnackbarManager.show("Download failed: ${error?.message ?: "Unknown error"}")

                            // Unregister from queue so a paused download can resume
                            GameDownloadService.unregisterDownload(GameSource.AMAZON, productId)
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) {
                        Timber.tag("Amazon").d("Download cancelled for $productId")
                    } else {
                        Timber.tag("Amazon").e(e, "Download exception for $productId")
                        if (GameDownloadService.reportFailure(GameSource.AMAZON, productId, e.message)) {
                            // Transient failure: queue-managed auto-retry; keep
                            // the partial install and the active-map entry.
                        } else {
                            instance.cleanupFailedInstall(context, game, installPath)

                            // Unregister from queue so a paused download can resume
                            GameDownloadService.unregisterDownload(GameSource.AMAZON, productId)
                        }
                    }
                    downloadInfo.setActive(false)
                } finally {
                    // Keep an auto-paused (queued) entry so the UI keeps showing it as
                    // Queued; remove(key, value) so a late finally never removes the
                    // fresh entry of an already-resumed download.
                    if (!downloadInfo.wasAutoPaused()) {
                        instance.activeDownloads.remove(productId, downloadInfo)
                        instance.activeDownloadPaths.remove(productId)
                    }
                    PluviaApp.events.emitJava(
                        AndroidEvent.DownloadStatusChanged(game.appId, false)
                    )
                }
            }

            downloadInfo.setDownloadJob(job)
            return Result.success(downloadInfo)
        }

        /** Cancel an in-progress download for [productId]. */
        fun cancelDownload(productId: String): Boolean {
            val instance = getInstance() ?: return false
            val downloadInfo = instance.activeDownloads[productId] ?: run {
                Timber.tag("Amazon").w("No active download for $productId")
                return false
            }
            Timber.tag("Amazon").i("Cancelling download for $productId")
            downloadInfo.cancel()
            // Remove the map entry like GOG/Epic do: a queued (auto-paused)
            // entry has no live job left whose finally could remove it.
            instance.activeDownloads.remove(productId, downloadInfo)
            instance.activeDownloadPaths.remove(productId)
            return true
        }

        suspend fun deleteGame(context: Context, productId: String): Result<Unit> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Amazon service is not running"))

            return withContext(Dispatchers.IO) {
                try {
                    val game = instance.amazonManager.getGameById(productId)
                        ?: return@withContext Result.failure(Exception("Game not found: $productId"))

                    val path = game.installPath.ifEmpty {
                        AmazonConstants.getGameInstallPath(context, game.title)
                    }
                    if (File(path).exists()) {
                        val installDir = File(path)
                        val manifestFile = File(context.filesDir, "manifests/amazon/$productId.proto")

                        if (manifestFile.exists()) {
                            // ── Manifest-based uninstall ─────────────────────────
                            Timber.tag("Amazon").i("Manifest-based uninstall for $productId")
                            try {
                                val manifest = AmazonManifest.parse(manifestFile.readBytes())
                                var deletedFiles = 0
                                var failedFiles = 0

                                for (mf in manifest.allFiles) {
                                    val file = File(installDir, mf.unixPath)
                                    if (file.exists()) {
                                        if (file.delete()) {
                                            deletedFiles++
                                        } else {
                                            failedFiles++
                                            Timber.tag("Amazon").w("Failed to delete: ${file.absolutePath}")
                                        }
                                    }
                                }

                                // Walk directories bottom-up and remove empty ones
                                val dirs = mutableSetOf<File>()
                                for (mf in manifest.allFiles) {
                                    var parent = File(installDir, mf.unixPath).parentFile
                                    while (parent != null && parent != installDir && parent.toPath().startsWith(installDir.toPath())) {
                                        dirs.add(parent)
                                        parent = parent.parentFile
                                    }
                                }
                                // Sort deepest-first so child dirs are removed before parents
                                for (dir in dirs.sortedByDescending { it.absolutePath.length }) {
                                    if (dir.exists() && dir.isDirectory && (dir.listFiles()?.isEmpty() == true)) {
                                        dir.delete()
                                    }
                                }

                                // Remove the install dir itself if it's now empty
                                if (installDir.exists() && installDir.isDirectory &&
                                    (installDir.listFiles()?.isEmpty() == true)
                                ) {
                                    installDir.delete()
                                }

                                Timber.tag("Amazon").i(
                                    "Manifest-based uninstall complete: $deletedFiles deleted, $failedFiles failed"
                                )
                            } catch (e: Exception) {
                                Timber.tag("Amazon").w(e, "Manifest parse failed — falling back to recursive delete")
                                NativeTreeDelete.deleteTreeFast(installDir)
                            }
                        } else {
                            // ── Fallback: recursive delete ───────────────────────
                            Timber.tag("Amazon").i("No cached manifest — recursive delete: $path")
                            NativeTreeDelete.deleteTreeFast(installDir)
                        }

                        MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                        MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                        // Remove metadata residue and ensure uninstall leaves no resumable state behind.
                        val downloadInfoDir = File(installDir, ".DownloadInfo")
                        if (downloadInfoDir.exists()) {
                            NativeTreeDelete.deleteTreeFast(downloadInfoDir)
                        }

                        if (installDir.exists()) {
                            val amazonRoot = File(AmazonConstants.defaultAmazonGamesPath(context)).canonicalFile
                            val installCanonical = installDir.canonicalFile
                            val isUnderAmazonRoot = installCanonical.path == amazonRoot.path ||
                                installCanonical.path.startsWith("${amazonRoot.path}${File.separator}")

                            if (isUnderAmazonRoot) {
                                NativeTreeDelete.deleteTreeFast(installCanonical)
                            } else {
                                Timber.tag("Amazon").w(
                                    "Skipping final recursive uninstall cleanup outside Amazon root: ${installCanonical.path}"
                                )
                            }

                            Timber.tag("Amazon").i(
                                "[UNINSTALL] cleanup productId=$productId installDirExists=${installCanonical.exists()} path=${installCanonical.path}"
                            )
                        }
                    }

                    instance.amazonManager.markUninstalled(productId)

                    // Delete cached manifest
                    try {
                        val manifestFile = File(context.filesDir, "manifests/amazon/$productId.proto")
                        if (manifestFile.exists()) {
                            manifestFile.delete()
                            Timber.tag("Amazon").d("Deleted cached manifest for $productId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("Amazon").w(e, "Failed to delete cached manifest (non-fatal)")
                    }

                    withContext(Dispatchers.Main) {
                        ContainerUtils.deleteContainer(context, "AMAZON_${game.appId}")
                    }

                    val postUninstallPath = AmazonConstants.getGameInstallPath(context, game.title)
                    val postInstallDirExists = File(postUninstallPath).exists()
                    val completeMarkerExists = MarkerUtils.hasMarker(postUninstallPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    val inProgressMarkerExists = MarkerUtils.hasMarker(postUninstallPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

                    Timber.tag("Amazon").i(
                        "[UNINSTALL] final_state productId=$productId appId=${game.appId} installDirExists=$postInstallDirExists completeMarker=$completeMarkerExists inProgressMarker=$inProgressMarkerExists"
                    )

                    PluviaApp.events.emitJava(
                        AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.AMAZON)
                    )

                    Timber.tag("Amazon").i("Game uninstalled: $productId")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Timber.tag("Amazon").e(e, "Failed to uninstall $productId")
                    Result.failure(e)
                }
            }
        }

        // ── Game verification ─────────────────────────────────────────────────

        /** Result of verifying installed files against cached manifest. */
        data class VerificationResult(
            val totalFiles: Int,
            val verifiedOk: Int,
            val missingFiles: Int,
            val sizeMismatch: Int,
            val hashMismatch: Int,
            val failedFiles: List<String>,
        ) {
            val isValid: Boolean get() = failedFiles.isEmpty()
        }

        /** Verify installed files for [productId] against cached manifest. */
        suspend fun verifyGame(context: Context, productId: String): Result<VerificationResult> {
            val instance = getInstance()
                ?: return Result.failure(Exception("Amazon service is not running"))

            return withContext(Dispatchers.IO) {
                try {
                    val game = instance.amazonManager.getGameById(productId)
                        ?: return@withContext Result.failure(Exception("Game not found: $productId"))

                    if (!game.isInstalled || game.installPath.isEmpty()) {
                        return@withContext Result.failure(Exception("Game is not installed"))
                    }

                    val installDir = File(game.installPath)
                    if (!installDir.exists()) {
                        return@withContext Result.failure(Exception("Install directory not found: ${game.installPath}"))
                    }

                    val manifestFile = File(context.filesDir, "manifests/amazon/$productId.proto")
                    if (!manifestFile.exists()) {
                        return@withContext Result.failure(Exception("No cached manifest — reinstall to enable verification"))
                    }

                    val manifest = AmazonManifest.parse(manifestFile.readBytes())
                    val files = manifest.allFiles

                    Timber.tag("Amazon").i("Verifying ${files.size} files for $productId at ${game.installPath}")

                    var verifiedOk = 0
                    var missingFiles = 0
                    var sizeMismatch = 0
                    var hashMismatch = 0
                    val failedFiles = mutableListOf<String>()

                    for (mf in files) {
                        val file = File(installDir, mf.unixPath)

                        if (!file.exists()) {
                            missingFiles++
                            failedFiles.add(mf.unixPath)
                            Timber.tag("Amazon").d("Verify MISSING: ${mf.unixPath}")
                            continue
                        }

                        if (file.length() != mf.size) {
                            sizeMismatch++
                            failedFiles.add(mf.unixPath)
                            Timber.tag("Amazon").d(
                                "Verify SIZE MISMATCH: ${mf.unixPath} (expected=${mf.size}, actual=${file.length()})"
                            )
                            continue
                        }

                        // SHA-256 check (algorithm 0) — skip if hash not available
                        if (mf.hashAlgorithm == 0 && mf.hashBytes.isNotEmpty()) {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            file.inputStream().buffered().use { input ->
                                val buf = ByteArray(8192)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    digest.update(buf, 0, read)
                                }
                            }
                            val computed = digest.digest()
                            if (!computed.contentEquals(mf.hashBytes)) {
                                hashMismatch++
                                failedFiles.add(mf.unixPath)
                                Timber.tag("Amazon").d("Verify HASH MISMATCH: ${mf.unixPath}")
                                continue
                            }
                        }

                        verifiedOk++
                    }

                    val result = VerificationResult(
                        totalFiles = files.size,
                        verifiedOk = verifiedOk,
                        missingFiles = missingFiles,
                        sizeMismatch = sizeMismatch,
                        hashMismatch = hashMismatch,
                        failedFiles = failedFiles,
                    )

                    if (result.isValid) {
                        Timber.tag("Amazon").i("Verification PASSED: ${result.verifiedOk}/${result.totalFiles} files OK")
                    } else {
                        Timber.tag("Amazon").w(
                            "Verification FAILED: ${result.verifiedOk}/${result.totalFiles} OK, " +
                                "${result.missingFiles} missing, ${result.sizeMismatch} size mismatch, " +
                                "${result.hashMismatch} hash mismatch"
                        )
                    }

                    Result.success(result)
                } catch (e: Exception) {
                    Timber.tag("Amazon").e(e, "Verification failed for $productId")
                    Result.failure(e)
                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        stop()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Register resume listener with GameDownloadService
        GameDownloadService.registerResumeListener(GameSource.AMAZON, object : GameDownloadService.ResumeListener {
            override fun onResumeRequested(gameSource: GameSource, gameId: String) {
                Timber.tag("Amazon").i("[AmazonService] Resume requested for product $gameId")
                serviceScope.launch {
                    val game = amazonManager.getGameById(gameId)
                    if (game != null) {
                        val installPath = game.installPath.ifBlank {
                            AmazonConstants.getGameInstallPath(applicationContext, game.title)
                        }
                        downloadGame(applicationContext, gameId, installPath)
                    }
                }
            }
        })

        PluviaApp.events.emit(AndroidEvent.ServiceReady)
        Timber.i("[Amazon] Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_AMAZON, "Connected")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_AMAZON, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_AMAZON, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_AMAZON)

        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> {
                Timber.i("[Amazon] Manual sync requested — bypassing throttle")
                true
            }
            ACTION_SYNC_LIBRARY -> {
                Timber.i("[Amazon] Automatic sync requested")
                true
            }
            null -> {
                // Service restarted by Android (START_STICKY)
                val timeSinceLastSync = System.currentTimeMillis() - lastSyncTimestamp
                val shouldResync = !hasPerformedInitialSync || timeSinceLastSync >= SYNC_THROTTLE_MILLIS
                if (shouldResync) {
                    Timber.i("[Amazon] Service restarted by Android — performing sync (initial=$hasPerformedInitialSync, elapsed=${timeSinceLastSync}ms)")
                } else {
                    Timber.d("[Amazon] Service restarted by Android — skipping sync (throttled)")
                }
                shouldResync
            }
            else -> {
                Timber.d("[Amazon] Service started without sync action")
                false
            }
        }

        if (shouldSync) {
            if (syncInProgress) {
                Timber.i("[Amazon] Sync already in progress — ignoring duplicate request")
            } else {
                backgroundSyncJob = serviceScope.launch { syncLibrary() }
            }
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("[Amazon] Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        backgroundSyncJob?.cancel()
        setSyncInProgress(false)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_AMAZON)

        // Drop this source's queue entries before removing the listener that resumes them
        GameDownloadService.unregisterAllForSource(GameSource.AMAZON)
        // Unregister resume listener from GameDownloadService
        GameDownloadService.unregisterResumeListener(GameSource.AMAZON)

        instance = null
        super.onDestroy()
        Timber.i("[Amazon] Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.i("[Amazon] Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.i("[Amazon] Task removed but active work exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun cleanupFailedInstall(context: Context, game: AmazonGame, installPath: String) {
        withContext(Dispatchers.IO) {
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            runCatching {
                val dir = File(installPath)
                if (dir.exists()) {
                    NativeTreeDelete.deleteTreeFast(dir)
                }
            }.onFailure {
                Timber.tag("Amazon").w(it, "Failed to clean partial install dir for ${game.productId}")
            }

            runCatching {
                amazonManager.markUninstalled(game.productId)
            }.onFailure {
                Timber.tag("Amazon").w(it, "Failed to mark game uninstalled after failed install: ${game.productId}")
            }
        }

        withContext(Dispatchers.Main) {
            ContainerUtils.deleteContainer(context, "AMAZON_${game.appId}")
        }

        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.AMAZON))
    }

    // ── Instance helpers (for callers that hold a direct reference) ───────────

    /** Instance-method accessor for callers using [getInstance]?. */
    fun getInstalledGamePath(gameId: String): String? = getInstallPath(gameId)

    private suspend fun syncLibrary() {
        setSyncInProgress(true)
        try {
            amazonManager.refreshLibrary()
            lastSyncTimestamp = System.currentTimeMillis()
            hasPerformedInitialSync = true
            Timber.i("[Amazon] Sync complete — next auto-sync in 15 minutes")
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Library sync failed")
        } finally {
            setSyncInProgress(false)
        }
    }

}
