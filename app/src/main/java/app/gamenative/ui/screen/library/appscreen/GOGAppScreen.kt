package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.gamenative.ui.component.dialog.LoadingDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.GOGGame
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.service.DownloadService
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.MarkerUtils
import java.io.File
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils.getContainer
import com.winlator.container.ContainerData
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber

/**
 * GOG-specific implementation of BaseAppScreen
 * Handles GOG games with integration to the Python gogdl backend
 */
class GOGAppScreen : BaseAppScreen() {
    companion object {
        private const val TAG = "GOGAppScreen"

        // Shared state for uninstall dialog - list of appIds that should show the dialog
        private val uninstallDialogAppIds = mutableStateListOf<String>()

        fun showUninstallDialog(appId: String) {
            Timber.tag(TAG).d("showUninstallDialog: appId=$appId")
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
                Timber.tag(TAG).d("Added to uninstall dialog list: $appId")
            }
        }

        fun hideUninstallDialog(appId: String) {
            Timber.tag(TAG).d("hideUninstallDialog: appId=$appId")
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean {
            val result = uninstallDialogAppIds.contains(appId)
            Timber.tag(TAG).d("shouldShowUninstallDialog: appId=$appId, result=$result")
            return result
        }

        // Shared state for deletion progress dialog
        var showDeletingDialog by mutableStateOf(false)

        /**
         * Formats bytes into a human-readable string (KB, MB, GB).
         * Uses binary units (1024 base).
         */
        private fun formatBytes(bytes: Long): String {
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            return when {
                bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
                bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
        }

        internal suspend fun forceCloudSync(
            context: Context,
            appId: String,
            syncCloudSaves: suspend (Context, String, String) -> Boolean = { syncContext, syncAppId, preferredAction ->
                GOGService.syncCloudSaves(
                    context = syncContext,
                    appId = syncAppId,
                    preferredAction = preferredAction,
                )
            },
            showSnackbar: (String) -> Unit = SnackbarManager::show,
            logError: (Throwable) -> Unit = { error ->
                Timber.tag(TAG).e(error, "[Cloud Saves] Sync failed")
            },
        ) {
            try {
                showSnackbar(context.getString(R.string.library_cloud_sync_starting))

                val result = withContext(Dispatchers.IO) {
                    syncCloudSaves(context, appId, "auto")
                }

                if (result) {
                    showSnackbar(context.getString(R.string.library_cloud_sync_success))
                } else {
                    showSnackbar(context.getString(R.string.library_cloud_sync_failed))
                }
            } catch (e: Exception) {
                logError(e)
                showSnackbar(
                    context.getString(
                        R.string.library_cloud_sync_error,
                        e.message ?: "",
                    ),
                )
            }
        }
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        // Extract numeric gameId for GOGService calls
        val gameId = libraryItem.gameId.toString()

        // Add a refresh trigger to re-fetch game data when install status changes
        var refreshTrigger by remember { mutableStateOf(0) }

        // Listen for install status changes to refresh game data
        LaunchedEffect(gameId) {
            val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.appId == libraryItem.gameId) {
                    Timber.tag(TAG).d("Install status changed, refreshing game data for $gameId")
                    refreshTrigger++
                }
            }
            app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        }

        var gogGame by remember(gameId, refreshTrigger) { mutableStateOf<GOGGame?>(null) }

        LaunchedEffect(gameId, refreshTrigger) {
            gogGame = GOGService.getGOGGameOf(gameId)
        }

        val game = gogGame

        // Format sizes for display
        val sizeOnDisk = if (game != null && game.isInstalled && game.installSize > 0) {
            formatBytes(game.installSize)
        } else {
            null
        }

        val sizeFromStore = if (game != null && game.downloadSize > 0) {
            formatBytes(game.downloadSize)
        } else {
            null
        }

        // Parse GOG's ISO 8601 release date string to Unix timestamp
        // GOG returns dates like "2022-08-18T17:50:00+0300" (without colon in timezone)
        // GameDisplayInfo expects Unix timestamp in SECONDS, not milliseconds
        val rawReleaseDate = game?.releaseDate
        val releaseDateTimestamp = if (!rawReleaseDate.isNullOrEmpty() && rawReleaseDate != "null") {
            try {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
                val timestampMillis = java.time.ZonedDateTime.parse(rawReleaseDate, formatter).toInstant().toEpochMilli()
                val timestampSeconds = timestampMillis / 1000
                timestampSeconds
            } catch (e: Exception) {
                Timber.tag(TAG).d("Release date not parseable (ignored): $rawReleaseDate")
                0L
            }
        } else {
            0L
        }

        val gameNameForCompatibility = game?.title ?: libraryItem.name
        val (compatibilityMessage, compatibilityColor) = rememberCompatibilityInfo(
            context = context,
            gameName = gameNameForCompatibility,
        )

        val displayInfo = GameDisplayInfo(
            name = game?.title ?: libraryItem.name,
            iconUrl = game?.iconUrl ?: libraryItem.iconHash,
            heroImageUrl = game?.backgroundUrl?.ifEmpty { null }
                ?: game?.imageUrl?.ifEmpty { null }
                ?: game?.iconUrl
                ?: libraryItem.iconHash,
            gameId = libraryItem.gameId, // Use gameId property which handles conversion
            appId = libraryItem.appId,
            releaseDate = releaseDateTimestamp,
            developer = game?.developer?.takeIf { it.isNotEmpty() } ?: "", // GOG API doesn't provide this
            installLocation = game?.installPath?.takeIf { it.isNotEmpty() },
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = sizeFromStore,
            compatibilityMessage = compatibilityMessage,
            compatibilityColor = compatibilityColor,
        )
        return displayInfo
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        return try {
            // GOGService expects numeric gameId
            val installed = GOGService.isGameInstalled(libraryItem.gameId.toString())
            installed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to check install status for ${libraryItem.appId}")
            false
        }
    }

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isValidToDownload: checking appId=${libraryItem.appId}")
        // GOG games can be downloaded if not already installed or downloading
        val installed = isInstalled(context, libraryItem)
        val downloading = isDownloading(context, libraryItem)
        val valid = !installed && !downloading
        Timber.tag(TAG).d("isValidToDownload: appId=${libraryItem.appId}, installed=$installed, downloading=$downloading, valid=$valid")
        return valid
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isDownloading: checking appId=${libraryItem.appId}")
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString()) ?: return false
        val downloading = downloadInfo.isPostInstallSyncing() || downloadInfo.isActive()
        Timber.tag(TAG).d(
            "isDownloading: appId=${libraryItem.appId}, postInstallSyncing=${downloadInfo.isPostInstallSyncing()}, active=${downloadInfo.isActive()}, result=$downloading",
        )
        return downloading
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        // GOGService expects numeric gameId
        val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString())
        val progress = downloadInfo?.getProgress() ?: 0f
        Timber.tag(TAG).d("getDownloadProgress: appId=${libraryItem.appId}, progress=$progress")
        return progress
    }

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        return GOGService.hasPartialDownload(libraryItem.gameId.toString(), libraryItem.name)
    }

    override fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        Timber.tag(TAG).i("onDownloadInstallClick: appId=${libraryItem.appId}, name=${libraryItem.name}")
        // GOGService expects numeric gameId
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = GOGService.getDownloadInfo(gameId)
        val isDownloading = isDownloading(context, libraryItem)
        val installed = isInstalled(context, libraryItem)
        val hasPartial = hasPartialDownload(context, libraryItem)

        Timber.tag(TAG).d(
            "onDownloadInstallClick: appId=${libraryItem.appId}, " +
                "isDownloading=$isDownloading, installed=$installed, hasPartial=$hasPartial",
        )

        if (isDownloading) {
            // Cancel ongoing download
            Timber.tag(TAG).i("Cancelling GOG download for: ${libraryItem.appId}")
            downloadInfo?.cancel()
            GOGService.cleanupDownload(gameId)
        } else if (installed) {
            // Already installed: launch game
            Timber.tag(TAG).i("GOG game already installed, launching: ${libraryItem.appId}")
            onClickPlay(false)
        } else if (hasPartial) {
            // Match Steam behavior: resume immediately for partial downloads.
            Timber.tag(TAG).i("Resuming partial GOG download for: ${libraryItem.appId}")
            performDownload(context, libraryItem, onClickPlay)
        } else {
            // Show install confirmation dialog
            showGOGInstallConfirmationDialog(context, libraryItem)
        }
    }

    private fun showGOGInstallConfirmationDialog(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId.toString()
        Timber.tag(TAG).i("Showing install confirmation dialog for: ${libraryItem.appId}")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val game = GOGService.getGOGGameOf(gameId)

                // Calculate sizes
                val downloadSize = app.gamenative.utils.StorageUtils.formatBinarySize(game?.downloadSize ?: 0L)
                val availableSpace = app.gamenative.utils.StorageUtils.formatBinarySize(
                    app.gamenative.utils.StorageUtils.getAvailableSpace(app.gamenative.service.gog.GOGConstants.defaultGOGGamesPath)
                )

                val message = context.getString(
                    R.string.gog_install_confirmation_message,
                    downloadSize,
                    availableSpace
                )
                val state = app.gamenative.ui.component.dialog.state.MessageDialogState(
                    visible = true,
                    type = app.gamenative.ui.enums.DialogType.INSTALL_APP,
                    title = context.getString(R.string.gog_install_game_title),
                    message = message,
                    confirmBtnText = context.getString(R.string.download),
                    dismissBtnText = context.getString(R.string.cancel)
                )
                BaseAppScreen.showInstallDialog(libraryItem.appId, state)
            } catch (e: Exception) {
                Timber.e(e, "Failed to show install dialog for: ${libraryItem.appId}")
            }
        }
    }

    private fun performDownload(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit) {
        val gameId = libraryItem.gameId.toString()
        Timber.i("Starting GOG game download: ${libraryItem.appId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get install path
                val installPath = GOGConstants.getGameInstallPath(libraryItem.name)
                val containerData = loadContainerData(context, libraryItem)

                Timber.d("Downloading GOG game to: $installPath")

                SnackbarManager.show("Starting download for ${libraryItem.name}...")

                // Start download - GOGService will handle monitoring, database updates, verification, and events
                val result = GOGService.downloadGame(context, gameId, installPath, containerData.language)

                if (result.isSuccess) {
                    Timber.i("GOG download started successfully for: $gameId")
                    // Success toast will be shown when download completes (monitored by GOGService)
                } else {
                    val error = result.exceptionOrNull()
                    Timber.e(error, "Failed to start GOG download")
                    SnackbarManager.show("Failed to start download: ${error?.message}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during GOG download")
                SnackbarManager.show("Download error: ${e.message}")
            }
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onPauseResumeClick: appId=${libraryItem.appId}")
        val gameId = libraryItem.gameId.toString()
        val downloadInfo = GOGService.getDownloadInfo(gameId)
        val isDownloading = isDownloading(context, libraryItem)
        val hasPartial = hasPartialDownload(context, libraryItem)

        if (isDownloading) {
            Timber.tag(TAG).i("Cancelling GOG download: ${libraryItem.appId}")
            downloadInfo?.cancel()
            GOGService.cleanupDownload(gameId)
        } else if (hasPartial) {
            Timber.tag(TAG).i("Resuming partial GOG download via pause/resume: ${libraryItem.appId}")
            performDownload(context, libraryItem) {}
        } else {
            // Fresh start: show install confirmation dialog.
            showGOGInstallConfirmationDialog(context, libraryItem)
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onDeleteDownloadClick: appId=${libraryItem.appId}")
        val isDownloadingFlag = isDownloading(context, libraryItem)
        val isInstalledFlag = isInstalled(context, libraryItem)
        val hasPartial = hasPartialDownload(context, libraryItem)
        Timber.tag(TAG).d("onDeleteDownloadClick: appId=${libraryItem.appId}, isDownloading=$isDownloadingFlag, isInstalled=$isInstalledFlag, hasPartial=$hasPartial")
        if (isDownloadingFlag || hasPartial) {
            showInstallDialog(
                libraryItem.appId,
                app.gamenative.ui.component.dialog.state.MessageDialogState(
                    visible = true,
                    type = app.gamenative.ui.enums.DialogType.CANCEL_APP_DOWNLOAD,
                    title = context.getString(R.string.cancel_download_prompt_title),
                    message = context.getString(R.string.library_delete_download_message),
                    confirmBtnText = context.getString(R.string.yes),
                    dismissBtnText = context.getString(R.string.no),
                )
            )
        } else if (isInstalledFlag) {
            Timber.tag(TAG).i("Showing uninstall dialog for: ${libraryItem.appId}")
            showUninstallDialog(libraryItem.appId)
        }
    }

    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        Timber.i("Uninstalling GOG game: ${libraryItem.appId}")
        showDeletingDialog = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val gameRootDir = getInstallPath(context, libraryItem)?.let(::File)
                // Delegate to GOGService which calls GOGManager.deleteGame
                val result = GOGService.deleteGame(context, libraryItem)
                DownloadService.invalidateCache()
                if (result.isSuccess) {
                    cleanupNexusModsForApp(context, libraryItem, gameRootDir)
                }

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Timber.i("Successfully uninstalled GOG game: ${libraryItem.appId}")
                        SnackbarManager.show("Game uninstalled successfully")
                    } else {
                        val error = result.exceptionOrNull()
                        Timber.e(error, "Failed to uninstall GOG game: ${libraryItem.appId}")
                        SnackbarManager.show("Failed to uninstall game: ${error?.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error uninstalling GOG game")
                withContext(Dispatchers.Main) {
                    SnackbarManager.show("Failed to uninstall game: ${e.message}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    showDeletingDialog = false
                }
            }
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("onUpdateClick: appId=${libraryItem.appId}")
        // TODO: Implement update for GOG games
        // Check GOG for newer version and download if available
        Timber.tag(TAG).d("Update clicked for GOG game: ${libraryItem.appId}")
    }

    override fun getExportFileExtension(): String {
        return ".gog"
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? {
        Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}")
        return try {
            // GOGService expects numeric gameId
            val path = GOGService.getInstallPath(libraryItem.gameId.toString())
            Timber.tag(TAG).d("getInstallPath: appId=${libraryItem.appId}, path=$path")
            path
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get install path for ${libraryItem.appId}")
            null
        }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        Timber.tag(TAG).d("loadContainerData: appId=${libraryItem.appId}")
        // Load GOG-specific container data using ContainerUtils
        val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        val containerData = app.gamenative.utils.ContainerUtils.toContainerData(container)
        Timber.tag(TAG).d("loadContainerData: loaded container for ${libraryItem.appId}")
        return containerData
    }

    override fun saveContainerConfig(context: Context, libraryItem: LibraryItem, config: ContainerData) {
        Timber.tag(TAG).i("saveContainerConfig: appId=${libraryItem.appId}")
        val container = getContainer(context, libraryItem.appId)
        val previousLanguage = container.language
        app.gamenative.utils.ContainerUtils.applyToContainer(context, libraryItem.appId, config)
        Timber.tag(TAG).d("saveContainerConfig: saved container config for ${libraryItem.appId}")

        if (previousLanguage != config.language) {
            triggerGOGVerifyDownload(context, libraryItem, config.language)
        }
    }

    private fun triggerGOGVerifyDownload(context: Context, libraryItem: LibraryItem, language: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val gameId = libraryItem.gameId.toString()
            if (!GOGService.isGameInstalled(gameId)) return@launch
            if (GOGService.getDownloadInfo(gameId)?.isActive() == true) return@launch

            val installPath = GOGService.getInstallPath(gameId)
                ?: GOGConstants.getGameInstallPath(libraryItem.name)

            GOGService.downloadGame(context, gameId, installPath, language)
        }
    }

    override fun supportsContainerConfig(): Boolean {
        Timber.tag(TAG).d("supportsContainerConfig: returning true")
        // GOG games support container configuration like other Wine games
        return true
    }

    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean,
    ): List<AppMenuOption> {
        if (!isInstalled || isDownloading(context, libraryItem)) {
            return emptyList()
        }

        val options = mutableListOf<AppMenuOption>()

        options.add(
            AppMenuOption(
                optionType = AppOptionMenuType.VerifyFiles,
                onClick = {
                    showInstallDialog(
                        libraryItem.appId,
                        app.gamenative.ui.component.dialog.state.MessageDialogState(
                            visible = true,
                            type = app.gamenative.ui.enums.DialogType.UPDATE_VERIFY_CONFIRM,
                            title = context.getString(R.string.library_verify_files_title),
                            message = context.getString(R.string.library_verify_files_message),
                            confirmBtnText = context.getString(R.string.proceed),
                            dismissBtnText = context.getString(R.string.cancel),
                        ),
                    )
                },
            ),
        )

        options.add(
            AppMenuOption(
                optionType = AppOptionMenuType.ForceCloudSync,
                onClick = {
                    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                    scope.launch {
                        forceCloudSync(
                            context = context,
                            appId = libraryItem.appId,
                        )
                    }
                },
            ),
        )

        return options
    }

    /**
     * GOG games support standard container reset
     */
    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = {
                resetContainerToDefaults(context, libraryItem)
            },
        )
    }

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit)? {
        Timber.tag(TAG).d("[OBSERVE] Setting up observeGameState for appId=${libraryItem.appId}, gameId=${libraryItem.gameId}")
        val disposables = mutableListOf<() -> Unit>()
        var currentProgressListener: ((Float) -> Unit)? = null

        fun attachProgressListener() {
            // GOGService expects numeric gameId
            val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString()) ?: return
            currentProgressListener?.let { listener ->
                downloadInfo.removeProgressListener(listener)
            }
            val progressListener: (Float) -> Unit = { progress ->
                onProgressChanged(progress)
            }
            downloadInfo.addProgressListener(progressListener)
            currentProgressListener = progressListener
        }

        // Attach immediately if a download is already running (the DownloadStatusChanged
        // event fired before this screen was composed and won't fire again).
        if (isDownloading(context, libraryItem)) {
            attachProgressListener()
        }
        disposables += {
            currentProgressListener?.let { listener ->
                GOGService.getDownloadInfo(libraryItem.gameId.toString())?.removeProgressListener(listener)
                currentProgressListener = null
            }
        }

        // Listen for download status changes
        val downloadStatusListener: (app.gamenative.events.AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] DownloadStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Download status changed for ${libraryItem.appId}, isDownloading=${event.isDownloading}")
                if (event.isDownloading) {
                    // Download started - attach progress listener
                    attachProgressListener()
                } else {
                    // Download stopped/completed - clean up listener
                    currentProgressListener?.let { listener ->
                        val downloadInfo = GOGService.getDownloadInfo(libraryItem.gameId.toString())
                        downloadInfo?.removeProgressListener(listener)
                        currentProgressListener = null
                    }
                    onHasPartialDownloadChanged?.invoke(false)
                }
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener)
        disposables +=
            { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.DownloadStatusChanged, Unit>(downloadStatusListener) }

        // Listen for install status changes
        val installListener: (app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            Timber.tag(TAG).d("[OBSERVE] LibraryInstallStatusChanged event received: event.appId=${event.appId}, libraryItem.gameId=${libraryItem.gameId}, match=${event.appId == libraryItem.gameId}")
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] Install status changed for ${libraryItem.appId}, calling onStateChanged()")
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        disposables +=
            { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener) }

        val postInstallSyncListener: (app.gamenative.events.AndroidEvent.PostInstallSyncStatusChanged) -> Unit = { event ->
            if (event.appId == libraryItem.gameId) {
                Timber.tag(TAG).d("[OBSERVE] PostInstallSyncStatusChanged for ${libraryItem.appId}, isSyncing=${event.isSyncing}")
                onStateChanged()
            }
        }
        app.gamenative.PluviaApp.events.on<app.gamenative.events.AndroidEvent.PostInstallSyncStatusChanged, Unit>(postInstallSyncListener)
        disposables +=
            { app.gamenative.PluviaApp.events.off<app.gamenative.events.AndroidEvent.PostInstallSyncStatusChanged, Unit>(postInstallSyncListener) }

        // Return cleanup function
        return {
            disposables.forEach { it() }
        }
    }

    /**
     * GOG-specific dialogs (install confirmation, uninstall confirmation)
     */
    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        Timber.tag(TAG).d("AdditionalDialogs: composing for appId=${libraryItem.appId}")
        val context = LocalContext.current


        // Monitor uninstall dialog state
        var showUninstallDialog by remember { mutableStateOf(shouldShowUninstallDialog(libraryItem.appId)) }
        LaunchedEffect(libraryItem.appId) {
            snapshotFlow { shouldShowUninstallDialog(libraryItem.appId) }
                .collect { shouldShow ->
                    showUninstallDialog = shouldShow
                }
        }

        // Shared install dialog state (from BaseAppScreen)
        val appId = libraryItem.appId
        var installDialogState by remember(appId) {
            mutableStateOf(BaseAppScreen.getInstallDialogState(appId) ?: app.gamenative.ui.component.dialog.state.MessageDialogState(false))
        }
        LaunchedEffect(appId) {
            snapshotFlow { BaseAppScreen.getInstallDialogState(appId) }
                .collect { state ->
                    installDialogState = state ?: app.gamenative.ui.component.dialog.state.MessageDialogState(false)
                }
        }

        // Show install dialog if visible
        if (installDialogState.visible) {
            val onDismissRequest: (() -> Unit)? = {
                BaseAppScreen.hideInstallDialog(appId)
            }
            val onDismissClick: (() -> Unit)? = {
                BaseAppScreen.hideInstallDialog(appId)
            }
            val onConfirmClick: (() -> Unit)? = when (installDialogState.type) {
                app.gamenative.ui.enums.DialogType.INSTALL_APP -> {
                    {
                        BaseAppScreen.hideInstallDialog(appId)
                        performDownload(context, libraryItem) {}
                    }
                }
                app.gamenative.ui.enums.DialogType.CANCEL_APP_DOWNLOAD -> {
                    {
                        BaseAppScreen.hideInstallDialog(appId)
                        showDeletingDialog = true
                        val gameId = libraryItem.gameId.toString()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val downloadInfo = GOGService.getDownloadInfo(gameId)
                                val wasDownloading = downloadInfo != null &&
                                    downloadInfo.isActive() &&
                                    (downloadInfo.getProgress() ?: 0f) < 1f
                                downloadInfo?.cancel()
                                downloadInfo?.awaitCompletion()
                                GOGService.cleanupDownload(gameId)

                                val isInstalledAfterCancel = GOGService.isGameInstalled(gameId)
                                if (isInstalledAfterCancel) {
                                    // Download completed and game ended up installed; don't show "Download cancelled"
                                    return@launch
                                }

                                val result = GOGService.deleteGame(context, libraryItem)
                                DownloadService.invalidateCache()
                                withContext(Dispatchers.Main) {
                                    if (wasDownloading && !isInstalledAfterCancel) {
                                        SnackbarManager.show("Download cancelled")
                                    }
                                    if (result.isFailure) {
                                        SnackbarManager.show("Failed to delete download: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main) {
                                    showDeletingDialog = false
                                }
                            }
                        }
                    }
                }
                app.gamenative.ui.enums.DialogType.UPDATE_VERIFY_CONFIRM -> {
                    {
                        BaseAppScreen.hideInstallDialog(appId)
                        val gameId = libraryItem.gameId.toString()
                        val installPath = GOGService.getInstallPath(gameId)
                            ?: GOGConstants.getGameInstallPath(libraryItem.name)
                        MarkerUtils.clearInstalledPrerequisiteMarkers(installPath)
                        val language = loadContainerData(context, libraryItem).language
                        triggerGOGVerifyDownload(context, libraryItem, language)
                    }
                }
                else -> null
            }
            app.gamenative.ui.component.dialog.MessageDialog(
                visible = installDialogState.visible,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                onDismissClick = onDismissClick,
                confirmBtnText = installDialogState.confirmBtnText,
                dismissBtnText = installDialogState.dismissBtnText,
                title = installDialogState.title,
                message = installDialogState.message,
            )
        }

        // Show deletion progress dialog
        if (showDeletingDialog) {
            LoadingDialog(
                visible = true,
                progress = -1f,
                message = stringResource(R.string.deleting),
            )
        }

        // Show uninstall confirmation dialog
        if (showUninstallDialog) {
            AlertDialog(
                onDismissRequest = {
                    hideUninstallDialog(libraryItem.appId)
                },
                title = { Text(stringResource(R.string.gog_uninstall_game_title)) },
                text = {
                    Text(
                        text = stringResource(
                            R.string.gog_uninstall_confirmation_message,
                            libraryItem.name,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                            performUninstall(context, libraryItem)
                        },
                    ) {
                        Text(stringResource(R.string.uninstall))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(libraryItem.appId)
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
