package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.data.CancelConfirmation
import app.gamenative.ui.data.DownloadItemState
import app.gamenative.ui.data.DownloadItemStatus
import app.gamenative.ui.data.DownloadsState
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val steamAppDao: SteamAppDao,
    private val epicGameDao: EpicGameDao,
    private val gogGameDao: GOGGameDao,
    private val amazonGameDao: AmazonGameDao,
) : ViewModel() {

    private data class ActiveDownloadBinding(
        val appId: String,
        val gameSource: GameSource,
        val gameName: String,
        val iconUrl: String,
        val info: DownloadInfo,
    )

    private data class ObservedDownload(
        val info: DownloadInfo,
        val progressListener: (Float) -> Unit,
        val syncingJob: Job,
    ) {
        fun dispose() {
            info.removeProgressListener(progressListener)
            syncingJob.cancel()
        }
    }

    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private val gameNameCache = ConcurrentHashMap<String, String>()
    private val gameIconCache = ConcurrentHashMap<String, String>()
    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val observedDownloads = ConcurrentHashMap<String, ObservedDownload>()

    private val finishedDownloads = ConcurrentHashMap<String, DownloadItemState>()
    private val pausedDownloads = ConcurrentHashMap.newKeySet<String>()
    private val pendingCancelledDownloads = ConcurrentHashMap.newKeySet<String>()
    private val recentFailureMessages = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastTrackedDownloads: Map<String, DownloadItemState> = emptyMap()

    private val onDownloadStatusChanged: (AndroidEvent.DownloadStatusChanged) -> Unit = {
        scheduleRefreshDownloads()
    }

    private val onLibraryInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = {
        scheduleRefreshDownloads()
    }

    private val onPostInstallSyncStatusChanged: (AndroidEvent.PostInstallSyncStatusChanged) -> Unit = {
        scheduleRefreshDownloads()
    }

    init {
        PluviaApp.events.on<AndroidEvent.DownloadStatusChanged, Unit>(onDownloadStatusChanged)
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onLibraryInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.PostInstallSyncStatusChanged, Unit>(onPostInstallSyncStatusChanged)

        viewModelScope.launch(Dispatchers.IO) {
            refreshRequests.collect {
                refreshDownloadsSnapshot()
            }
        }

        scheduleRefreshDownloads()
    }

    override fun onCleared() {
        PluviaApp.events.off<AndroidEvent.DownloadStatusChanged, Unit>(onDownloadStatusChanged)
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onLibraryInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.PostInstallSyncStatusChanged, Unit>(onPostInstallSyncStatusChanged)
        clearObservedDownloads()
        super.onCleared()
    }

    private fun downloadKey(gameSource: GameSource, appId: String): String = "${gameSource.name}_$appId"

    private fun scheduleRefreshDownloads() {
        refreshRequests.tryEmit(Unit)
    }

    private fun clearObservedDownloads() {
        observedDownloads.values.forEach { it.dispose() }
        observedDownloads.clear()
    }

    private fun normalizeStatusMessage(message: String?): String? {
        return message
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !it.equals("null", ignoreCase = true) }
    }

    private fun failureMessage(message: String?): String {
        return normalizeStatusMessage(message) ?: appContext.getString(R.string.downloads_status_failed)
    }

    private suspend fun getSteamMetadata(appId: Int): Pair<String, String> {
        val key = downloadKey(GameSource.STEAM, appId.toString())
        val cachedName = gameNameCache[key]
        val cachedIcon = gameIconCache[key]
        if (cachedName != null && cachedIcon != null) return Pair(cachedName, cachedIcon)

        val app = steamAppDao.findApp(appId)
        val name = cachedName ?: (app?.name ?: "Steam App $appId").also { gameNameCache[key] = it }
        val icon = cachedIcon ?: (
            if (app != null && app.clientIconHash.isNotEmpty()) {
                "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/${app.id}/${app.clientIconHash}.ico"
            } else {
                ""
            }
            ).also { gameIconCache[key] = it }

        return Pair(name, icon)
    }

    private suspend fun getEpicMetadata(appId: Int): Pair<String, String> {
        val key = downloadKey(GameSource.EPIC, appId.toString())
        val cachedName = gameNameCache[key]
        val cachedIcon = gameIconCache[key]
        if (cachedName != null && cachedIcon != null) return Pair(cachedName, cachedIcon)

        val game = epicGameDao.getById(appId)
        val name = cachedName ?: (game?.title ?: "Epic Game $appId").also { gameNameCache[key] = it }
        val icon = cachedIcon ?: (game?.artCover ?: "").also { gameIconCache[key] = it }

        return Pair(name, icon)
    }

    private suspend fun getGOGMetadata(gameId: String): Pair<String, String> {
        val key = downloadKey(GameSource.GOG, gameId)
        val cachedName = gameNameCache[key]
        val cachedIcon = gameIconCache[key]
        if (cachedName != null && cachedIcon != null) return Pair(cachedName, cachedIcon)

        val game = gogGameDao.getById(gameId)
        val name = cachedName ?: (game?.title ?: "GOG Game $gameId").also { gameNameCache[key] = it }
        val icon = cachedIcon ?: (game?.imageUrl?.ifEmpty { game.iconUrl } ?: "").also { gameIconCache[key] = it }

        return Pair(name, icon)
    }

    private suspend fun getAmazonMetadata(productId: String): Pair<String, String> {
        val key = downloadKey(GameSource.AMAZON, productId)
        val cachedName = gameNameCache[key]
        val cachedIcon = gameIconCache[key]
        if (cachedName != null && cachedIcon != null) return Pair(cachedName, cachedIcon)

        val game = amazonGameDao.getByProductId(productId)
        val name = cachedName ?: (game?.title ?: "Amazon Game").also { gameNameCache[key] = it }
        val icon = cachedIcon ?: (game?.artUrl ?: "").also { gameIconCache[key] = it }

        return Pair(name, icon)
    }

    suspend fun resolveLibraryItem(gameSource: GameSource, appId: String): LibraryItem? {
        val libraryAppId = "${gameSource.name}_$appId"

        return when (gameSource) {
            GameSource.STEAM -> {
                val numericAppId = appId.toIntOrNull() ?: return null
                val app = steamAppDao.findApp(numericAppId) ?: SteamService.getAppInfoOf(numericAppId)
                app?.let {
                    LibraryItem(
                        appId = libraryAppId,
                        name = it.name,
                        iconHash = it.clientIconHash,
                        capsuleImageUrl = it.getCapsuleUrl(),
                        headerImageUrl = it.getHeaderImageUrl().orEmpty().ifEmpty { it.headerUrl },
                        heroImageUrl = it.getHeroUrl().ifEmpty { it.headerUrl },
                        gameSource = GameSource.STEAM,
                    )
                }
            }

            GameSource.GOG -> {
                gogGameDao.getById(appId)?.let { game ->
                    LibraryItem(
                        appId = libraryAppId,
                        name = game.title,
                        iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                        capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                        headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                        heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                        gameSource = GameSource.GOG,
                    )
                }
            }

            GameSource.EPIC -> {
                val numericAppId = appId.toIntOrNull() ?: return null
                epicGameDao.getById(numericAppId)?.let { game ->
                    LibraryItem(
                        appId = libraryAppId,
                        name = game.title,
                        iconHash = game.artSquare.ifEmpty { game.artCover },
                        capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                        headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                        heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                        gameSource = GameSource.EPIC,
                    )
                }
            }

            GameSource.AMAZON -> {
                val game = amazonGameDao.getByProductId(appId)
                    ?: appId.toIntOrNull()?.let { amazonGameDao.getByAppId(it) }
                game?.let {
                    LibraryItem(
                        appId = "${GameSource.AMAZON.name}_${it.appId}",
                        name = it.title,
                        iconHash = it.artUrl,
                        capsuleImageUrl = it.artUrl,
                        headerImageUrl = it.heroUrl.ifEmpty { it.artUrl },
                        heroImageUrl = it.heroUrl.ifEmpty { it.artUrl },
                        gameSource = GameSource.AMAZON,
                    )
                }
            }

            GameSource.CUSTOM_GAME -> {
                CustomGameScanner.scanAsLibraryItems(query = "")
                    .firstOrNull { it.appId == libraryAppId }
            }
        }
    }

    private fun buildActiveDownloadItem(
        appId: String,
        gameSource: GameSource,
        gameName: String,
        iconUrl: String,
        info: DownloadInfo,
    ): DownloadItemState {
        val key = downloadKey(gameSource, appId)
        val rawProgress = info.getProgress()
        val statusMessage = normalizeStatusMessage(info.getCurrentStatusMessage())
        val isRunning = info.isActive() || info.isPostInstallSyncing()
        val status = when {
            rawProgress < 0f || statusMessage?.startsWith("Failed", ignoreCase = true) == true -> DownloadItemStatus.FAILED
            isRunning -> DownloadItemStatus.DOWNLOADING
            info.wasAutoPaused() -> DownloadItemStatus.QUEUED
            else -> DownloadItemStatus.PAUSED
        }

        if (status == DownloadItemStatus.DOWNLOADING) {
            recentFailureMessages.remove(key)
        } else if (status == DownloadItemStatus.FAILED) {
            recentFailureMessages[key] = failureMessage(statusMessage)
        }

        val (downloaded, total) = info.getBytesProgress()
        return DownloadItemState(
            appId = appId,
            gameSource = gameSource,
            gameName = gameName,
            iconUrl = iconUrl,
            progress = rawProgress.takeIf { it >= 0f },
            bytesDownloaded = downloaded.takeIf { total > 0L },
            bytesTotal = total.takeIf { it > 0L },
            etaMs = info.getEstimatedTimeRemaining(),
            statusMessage = statusMessage,
            isActive = info.isActive(),
            isPartial = false,
            status = status,
        )
    }

    private fun buildPartialDownloadItem(
        appId: String,
        gameSource: GameSource,
        gameName: String,
        iconUrl: String,
    ): DownloadItemState {
        val key = downloadKey(gameSource, appId)
        val status = when {
            pausedDownloads.contains(key) -> DownloadItemStatus.PAUSED
            recentFailureMessages.containsKey(key) -> DownloadItemStatus.FAILED
            else -> DownloadItemStatus.RESUMABLE
        }
        val statusMessage = when (status) {
            DownloadItemStatus.PAUSED -> appContext.getString(R.string.downloads_status_paused)
            DownloadItemStatus.FAILED -> recentFailureMessages[key] ?: appContext.getString(R.string.downloads_status_failed)
            DownloadItemStatus.RESUMABLE -> appContext.getString(R.string.downloads_resume_available)
            else -> null
        }

        return DownloadItemState(
            appId = appId,
            gameSource = gameSource,
            gameName = gameName,
            iconUrl = iconUrl,
            progress = null,
            bytesDownloaded = null,
            bytesTotal = null,
            etaMs = null,
            statusMessage = statusMessage,
            isActive = false,
            isPartial = true,
            status = status,
        )
    }

    private suspend fun isInstalled(gameSource: GameSource, appId: String): Boolean {
        return when (gameSource) {
            GameSource.STEAM -> appId.toIntOrNull()?.let { SteamService.isAppInstalled(it) } ?: false
            GameSource.EPIC -> appId.toIntOrNull()?.let { epicGameDao.getById(it)?.isInstalled == true } ?: false
            GameSource.GOG -> gogGameDao.getById(appId)?.isInstalled == true
            GameSource.AMAZON -> amazonGameDao.getByProductId(appId)?.isInstalled == true
            GameSource.CUSTOM_GAME -> false
        }
    }

    /**
     * A live-map entry whose game is already installed, which is not running,
     * not syncing, and not queue-managed is a stale zombie (e.g. a pause raced
     * the completion job before clearQueuedState existed). Dropping it from the
     * live rows lets the disappeared-keys path file it under COMPLETED.
     */
    private suspend fun isStaleCompletedEntry(gameSource: GameSource, appId: String, info: DownloadInfo): Boolean {
        return !info.isActive() &&
            !info.isPostInstallSyncing() &&
            !info.wasAutoPaused() &&
            isInstalled(gameSource, appId)
    }

    private fun sortDownloads(items: Collection<DownloadItemState>): LinkedHashMap<String, DownloadItemState> {
        val sortedItems = items.sortedWith(
            compareBy<DownloadItemState> { item ->
                when {
                    item.status == DownloadItemStatus.DOWNLOADING -> 0
                    item.status == DownloadItemStatus.QUEUED -> 1
                    item.isPartial -> 2
                    item.status == DownloadItemStatus.COMPLETED -> 3
                    item.status == DownloadItemStatus.CANCELLED -> 4
                    else -> 5
                }
            }
                .thenByDescending { item -> if (item.isFinished) item.updatedAtMs else 0L }
                .thenBy { item ->
                    if (item.isFinished) {
                        ""
                    } else {
                        item.gameName.lowercase()
                    }
                },
        )

        return LinkedHashMap<String, DownloadItemState>().apply {
            sortedItems.forEach { item -> put(item.uniqueId, item) }
        }
    }

    private fun trimFinishedDownloads(maxEntries: Int = 20) {
        if (finishedDownloads.size <= maxEntries) return

        finishedDownloads.values
            .sortedByDescending { it.updatedAtMs }
            .drop(maxEntries)
            .forEach { item -> finishedDownloads.remove(item.uniqueId) }
    }

    private fun syncObservedDownloads(activeBindings: Map<String, ActiveDownloadBinding>) {
        observedDownloads.entries.toList().forEach { (key, observed) ->
            val binding = activeBindings[key]
            if (binding == null || binding.info !== observed.info) {
                observed.dispose()
                observedDownloads.remove(key)
            }
        }

        activeBindings.forEach { (key, binding) ->
            if (observedDownloads[key]?.info === binding.info) return@forEach

            val progressListener: (Float) -> Unit = {
                viewModelScope.launch(Dispatchers.Default) {
                    updateObservedDownloadItem(binding)
                }
            }
            binding.info.addProgressListener(progressListener)

            val syncingJob = viewModelScope.launch(Dispatchers.Default) {
                binding.info.getPostInstallSyncingFlow().collect {
                    updateObservedDownloadItem(binding)
                }
            }

            observedDownloads[key] = ObservedDownload(
                info = binding.info,
                progressListener = progressListener,
                syncingJob = syncingJob,
            )
        }
    }

    private fun updateObservedDownloadItem(binding: ActiveDownloadBinding) {
        val key = downloadKey(binding.gameSource, binding.appId)
        if (!lastTrackedDownloads.containsKey(key)) return

        val updatedItem = buildActiveDownloadItem(
            appId = binding.appId,
            gameSource = binding.gameSource,
            gameName = binding.gameName,
            iconUrl = binding.iconUrl,
            info = binding.info,
        )

        val updatedTrackedDownloads = LinkedHashMap(lastTrackedDownloads)
        updatedTrackedDownloads[key] = updatedItem
        lastTrackedDownloads = updatedTrackedDownloads

        _state.update { current ->
            if (!current.downloads.containsKey(key)) return@update current

            val mergedDownloads = LinkedHashMap(current.downloads)
            mergedDownloads[key] = updatedItem
            current.copy(downloads = sortDownloads(mergedDownloads.values))
        }
    }

    private suspend fun refreshDownloadsSnapshot() {
        try {
            val liveDownloads = LinkedHashMap<String, DownloadItemState>()
            val activeBindings = LinkedHashMap<String, ActiveDownloadBinding>()

            for ((appId, info) in SteamService.getActiveDownloads()) {
                val appIdString = appId.toString()
                if (isStaleCompletedEntry(GameSource.STEAM, appIdString, info)) continue
                val (name, icon) = getSteamMetadata(appId)
                val item = buildActiveDownloadItem(appIdString, GameSource.STEAM, name, icon, info)
                liveDownloads[item.uniqueId] = item
                activeBindings[item.uniqueId] = ActiveDownloadBinding(appIdString, GameSource.STEAM, name, icon, info)
            }

            for (appId in SteamService.getPartialDownloads()) {
                val appIdString = appId.toString()
                val key = downloadKey(GameSource.STEAM, appIdString)
                if (liveDownloads.containsKey(key)) continue
                val (name, icon) = getSteamMetadata(appId)
                liveDownloads[key] = buildPartialDownloadItem(appIdString, GameSource.STEAM, name, icon)
            }

            for ((appId, info) in EpicService.getActiveDownloads()) {
                val appIdString = appId.toString()
                if (isStaleCompletedEntry(GameSource.EPIC, appIdString, info)) continue
                val (name, icon) = getEpicMetadata(appId)
                val item = buildActiveDownloadItem(appIdString, GameSource.EPIC, name, icon, info)
                liveDownloads[item.uniqueId] = item
                activeBindings[item.uniqueId] = ActiveDownloadBinding(appIdString, GameSource.EPIC, name, icon, info)
            }

            for (appId in EpicService.getPartialDownloads()) {
                val appIdString = appId.toString()
                val key = downloadKey(GameSource.EPIC, appIdString)
                if (liveDownloads.containsKey(key)) continue
                val (name, icon) = getEpicMetadata(appId)
                liveDownloads[key] = buildPartialDownloadItem(appIdString, GameSource.EPIC, name, icon)
            }

            for ((gameId, info) in GOGService.getActiveDownloads()) {
                if (isStaleCompletedEntry(GameSource.GOG, gameId, info)) continue
                val (name, icon) = getGOGMetadata(gameId)
                val item = buildActiveDownloadItem(gameId, GameSource.GOG, name, icon, info)
                liveDownloads[item.uniqueId] = item
                activeBindings[item.uniqueId] = ActiveDownloadBinding(gameId, GameSource.GOG, name, icon, info)
            }

            for (gameId in GOGService.getPartialDownloads()) {
                val key = downloadKey(GameSource.GOG, gameId)
                if (liveDownloads.containsKey(key)) continue
                val (name, icon) = getGOGMetadata(gameId)
                liveDownloads[key] = buildPartialDownloadItem(gameId, GameSource.GOG, name, icon)
            }

            for ((productId, info) in AmazonService.getActiveDownloads()) {
                if (isStaleCompletedEntry(GameSource.AMAZON, productId, info)) continue
                val (name, icon) = getAmazonMetadata(productId)
                val item = buildActiveDownloadItem(productId, GameSource.AMAZON, name, icon, info)
                liveDownloads[item.uniqueId] = item
                activeBindings[item.uniqueId] = ActiveDownloadBinding(productId, GameSource.AMAZON, name, icon, info)
            }

            for (productId in AmazonService.getPartialDownloads(appContext)) {
                val key = downloadKey(GameSource.AMAZON, productId)
                if (liveDownloads.containsKey(key)) continue
                val (name, icon) = getAmazonMetadata(productId)
                liveDownloads[key] = buildPartialDownloadItem(productId, GameSource.AMAZON, name, icon)
            }

            val disappearedKeys = lastTrackedDownloads.keys - liveDownloads.keys
            for (key in disappearedKeys) {
                val previousItem = lastTrackedDownloads[key] ?: continue
                val finishedStatus = when {
                    pendingCancelledDownloads.remove(key) -> DownloadItemStatus.CANCELLED
                    isInstalled(previousItem.gameSource, previousItem.appId) -> DownloadItemStatus.COMPLETED
                    else -> DownloadItemStatus.FAILED
                }

                val finishedMessage = when (finishedStatus) {
                    DownloadItemStatus.COMPLETED -> appContext.getString(R.string.downloads_status_complete)
                    DownloadItemStatus.CANCELLED -> appContext.getString(R.string.downloads_status_cancelled)
                    DownloadItemStatus.FAILED -> recentFailureMessages[key] ?: appContext.getString(R.string.downloads_status_failed)
                    else -> previousItem.statusMessage
                }

                finishedDownloads[key] = previousItem.copy(
                    progress = if (finishedStatus == DownloadItemStatus.COMPLETED) 1f else previousItem.progress,
                    bytesDownloaded = if (finishedStatus == DownloadItemStatus.COMPLETED) {
                        previousItem.bytesTotal ?: previousItem.bytesDownloaded
                    } else {
                        previousItem.bytesDownloaded
                    },
                    etaMs = null,
                    statusMessage = finishedMessage,
                    isActive = false,
                    isPartial = false,
                    status = finishedStatus,
                    updatedAtMs = System.currentTimeMillis(),
                )

                pausedDownloads.remove(key)
                if (finishedStatus != DownloadItemStatus.FAILED) {
                    recentFailureMessages.remove(key)
                }
            }

            liveDownloads.keys.forEach { key -> finishedDownloads.remove(key) }
            trimFinishedDownloads()

            lastTrackedDownloads = liveDownloads
            syncObservedDownloads(activeBindings)

            _state.update {
                it.copy(downloads = sortDownloads(liveDownloads.values + finishedDownloads.values))
            }
        } catch (e: Exception) {
            Timber.tag("DownloadsViewModel").e(e, "Error refreshing downloads")
        }
    }

    fun onPauseDownload(item: DownloadItemState) {
        if (!item.canPause) return

        val key = item.uniqueId
        pausedDownloads.add(key)
        recentFailureMessages.remove(key)

        viewModelScope.launch(Dispatchers.IO) {
            pauseOnStore(item)
        }
    }

    /**
     * Store-level pause / dequeue. Non-destructive (no file deletion): a queued
     * (auto-paused) entry becomes plainly paused, an active one is cancelled.
     */
    private fun pauseOnStore(item: DownloadItemState) {
        when (item.gameSource) {
            GameSource.STEAM -> {
                val id = item.appId.toIntOrNull() ?: return
                SteamService.getAppDownloadInfo(id)?.cancel()
            }

            GameSource.EPIC -> {
                val id = item.appId.toIntOrNull() ?: return
                EpicService.cancelDownload(id)
            }

            GameSource.GOG -> GOGService.cancelDownload(item.appId)
            GameSource.AMAZON -> AmazonService.cancelDownload(item.appId)
            GameSource.CUSTOM_GAME -> Unit
        }
    }

    fun onResumeDownload(item: DownloadItemState) {
        if (!item.canResume) return

        val key = item.uniqueId
        pausedDownloads.remove(key)
        pendingCancelledDownloads.remove(key)
        recentFailureMessages.remove(key)
        finishedDownloads.remove(key)

        viewModelScope.launch(Dispatchers.IO) {
            resumeOnStore(item)
            scheduleRefreshDownloads()
        }
    }

    private suspend fun resumeOnStore(item: DownloadItemState) {
        val key = item.uniqueId
        when (item.gameSource) {
            GameSource.STEAM -> {
                val id = item.appId.toIntOrNull() ?: return
                SteamService.downloadApp(id)
            }

            GameSource.GOG -> {
                val game = gogGameDao.getById(item.appId) ?: return
                val installPath = game.installPath.ifBlank { GOGConstants.getGameInstallPath(game.title) }
                val container = ContainerUtils.getOrCreateContainer(appContext, "${GameSource.GOG.name}_${item.appId}")
                val language = ContainerUtils.toContainerData(container).language
                val result = GOGService.downloadGame(appContext, item.appId, installPath, language)
                result.exceptionOrNull()?.message?.let { recentFailureMessages[key] = it }
            }

            GameSource.EPIC -> {
                val id = item.appId.toIntOrNull() ?: return
                val game = epicGameDao.getById(id) ?: return
                val installPath = game.installPath.ifBlank {
                    EpicConstants.getGameInstallPath(appContext, game.appName)
                }
                val container = ContainerUtils.getOrCreateContainer(appContext, "${GameSource.EPIC.name}_${item.appId}")
                val language = ContainerUtils.toContainerData(container).language
                val result = EpicService.downloadGame(appContext, id, emptyList(), installPath, language)
                result.exceptionOrNull()?.message?.let { recentFailureMessages[key] = it }
            }

            GameSource.AMAZON -> {
                val game = amazonGameDao.getByProductId(item.appId) ?: return
                val installPath = game.installPath.ifBlank {
                    AmazonConstants.getGameInstallPath(appContext, game.title)
                }
                val result = AmazonService.downloadGame(appContext, item.appId, installPath)
                result.exceptionOrNull()?.message?.let { recentFailureMessages[key] = it }
            }

            GameSource.CUSTOM_GAME -> Unit
        }
    }

    fun onPauseAll() {
        val items = state.value.downloads.values
        val queued = items.filter { it.status == DownloadItemStatus.QUEUED }
        val active = items.filter { it.canPause }
        if (queued.isEmpty() && active.isEmpty()) return

        (queued + active).forEach {
            pausedDownloads.add(it.uniqueId)
            recentFailureMessages.remove(it.uniqueId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Dequeue queued entries FIRST: pausing the active download advances
            // the queue, so the queue must be empty before that happens — one
            // queue transition per entry would otherwise chain (ANR) and
            // pause-all would end up starting the next queued download.
            queued.forEach { pauseOnStore(it) }
            active.forEach { pauseOnStore(it) }
            scheduleRefreshDownloads()
        }
    }

    fun onResumeAll() {
        // Only the first resumable download is launched: every launch registers
        // with the queue, and registering N downloads at once would auto-pause
        // the previous one N-1 times (a queue-transition chain = ANR). The rest
        // stay truthfully paused — optimistically un-pausing them in the UI
        // would lie until the next refresh, and queueing paused downloads
        // without launching them needs store-side enqueue support.
        val first = state.value.downloads.values.firstOrNull { it.canResume } ?: return
        onResumeDownload(first)
    }

    fun onCancelAll() {
        val items = state.value.downloads.values.filter { it.canCancel }
        if (items.isEmpty()) return
        val active = items.filter { it.status == DownloadItemStatus.DOWNLOADING }
        val others = items - active.toSet()

        items.forEach {
            pendingCancelledDownloads.add(it.uniqueId)
            pausedDownloads.remove(it.uniqueId)
            recentFailureMessages.remove(it.uniqueId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Cancelling the active download advances the queue, so clear queued
            // and paused entries FIRST — otherwise each cancel restarts the next
            // queued download just to cancel it again (chain of queue
            // transitions + snapshot writes = ANR).
            others.forEach { cancelOnStore(it.appId, it.gameSource, it.gameName) }
            active.forEach { cancelOnStore(it.appId, it.gameSource, it.gameName) }
        }
    }

    fun onClearFinished() {
        finishedDownloads.clear()
        _state.update {
            it.copy(downloads = sortDownloads(lastTrackedDownloads.values))
        }
    }

    fun onCancelDownload(item: DownloadItemState) {
        _state.update {
            it.copy(
                cancelConfirmation = CancelConfirmation(
                    appId = item.appId,
                    gameSource = item.gameSource,
                    gameName = item.gameName,
                ),
            )
        }
    }

    fun onDismissCancel() {
        _state.update { it.copy(cancelConfirmation = null) }
    }

    fun onConfirmCancel() {
        val confirmation = _state.value.cancelConfirmation ?: return
        _state.update { it.copy(cancelConfirmation = null) }
        cancelDownloadNow(confirmation.appId, confirmation.gameSource, confirmation.gameName)
    }

    private fun cancelDownloadNow(appId: String, gameSource: GameSource, gameName: String) {
        val key = downloadKey(gameSource, appId)
        pendingCancelledDownloads.add(key)
        pausedDownloads.remove(key)
        recentFailureMessages.remove(key)

        viewModelScope.launch(Dispatchers.IO) {
            cancelOnStore(appId, gameSource, gameName)
        }
    }

    /** Store-level cancel + delete. Destructive: removes the partial download. */
    private suspend fun cancelOnStore(appId: String, gameSource: GameSource, gameName: String) {
        when (gameSource) {
            GameSource.STEAM -> {
                val id = appId.toIntOrNull() ?: return
                SteamService.getAppDownloadInfo(id)?.cancel()
                // A queued (auto-paused) entry has no live job left to remove it —
                // drop it explicitly (no-op when the job's completion already did).
                SteamService.removeDownloadJob(id)
                SteamService.deleteApp(id)
                PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(id, GameSource.STEAM))
                scheduleRefreshDownloads()
            }

            GameSource.EPIC -> {
                val id = appId.toIntOrNull() ?: return
                EpicService.cancelDownload(id)
                EpicService.deleteGame(appContext, id)
                scheduleRefreshDownloads()
            }

            GameSource.GOG -> {
                GOGService.cancelDownload(appId)
                val game = gogGameDao.getById(appId)
                if (game != null) {
                    GOGService.deleteGame(
                        appContext,
                        LibraryItem(
                            appId = appId,
                            name = game.title.ifBlank { gameName },
                            gameSource = GameSource.GOG,
                        ),
                    )
                }
                scheduleRefreshDownloads()
            }

            GameSource.AMAZON -> {
                AmazonService.cancelDownload(appId)
                AmazonService.deleteGame(appContext, appId)
                scheduleRefreshDownloads()
            }

            GameSource.CUSTOM_GAME -> Unit
        }
    }
}
