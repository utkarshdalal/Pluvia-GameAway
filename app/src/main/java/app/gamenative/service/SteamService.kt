package app.gamenative.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Base64
import app.gamenative.ui.data.Achievement
import app.gamenative.ui.util.GameInviteNotificationManager
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.callback.GameInviteCallback
import app.gamenative.service.handler.GameInviteHandler
import androidx.room.withTransaction
import app.gamenative.BuildConfig
import app.gamenative.NetworkMonitor
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.AppInfo
import app.gamenative.data.CachedLicense
import app.gamenative.data.DepotInfo
import app.gamenative.data.DownloadInfo
import app.gamenative.data.EncryptedAppTicket
import app.gamenative.data.GameProcessInfo
import app.gamenative.data.GameSource
import app.gamenative.data.LaunchInfo
import app.gamenative.data.OwnedGames
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.PreferredCopyOption
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamControllerConfigDetail
import app.gamenative.data.SteamFriend
import app.gamenative.data.SteamLicense
import app.gamenative.data.UserFileInfo
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.ChangeNumbersDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.FileChangeListsDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamFileHashCacheDao
import app.gamenative.db.dao.SteamLicenseDao
import app.gamenative.enums.LoginResult
import app.gamenative.enums.Marker
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.FileUtils
import app.gamenative.utils.LicenseSerializer
import app.gamenative.utils.LocaleHelper
import app.gamenative.utils.LsfgVkManager
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.Net
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.CURRENT_UFS_PARSE_VERSION
import app.gamenative.utils.generateSteamApp
import app.gamenative.workshop.WorkshopManager
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import dagger.hilt.android.AndroidEntryPoint
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeTreeDelete
import `in`.dragonbra.javasteam.enums.EAccountType
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EUniverse
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientObjects.ECloudPendingRemoteOperation
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudconfigstoreSteamclient
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesFamilygroupsSteamclient
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.steam.CloudConfigStoreService
import app.gamenative.steam.SteamCollectionParser
import `in`.dragonbra.javasteam.rpc.service.FamilyGroups
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerQuality
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamgameserver.SteamGameServer
import `in`.dragonbra.javasteam.steam.handlers.steammasterserver.SteamMasterServer
import `in`.dragonbra.javasteam.steam.handlers.steamscreenshots.SteamScreenshots
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.PlayingSessionStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.SteamWorkshop
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.NullPointerException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import okio.Path.Companion.toPath
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import app.gamenative.data.DownloadingAppInfo
import app.gamenative.data.SteamUnlockedBranch
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.SteamUnlockedBranchDao
import app.gamenative.enums.SteamRealm
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import com.winlator.container.ContainerManager
import app.gamenative.statsgen.StatType
import app.gamenative.statsgen.StatsAchievementsGenerator
import app.gamenative.statsgen.VdfParser
import app.gamenative.utils.DownloadSpeedConfig
import app.gamenative.utils.CustomGameScanner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@AndroidEntryPoint
class SteamService : Service(), IChallengeUrlChanged {

    override fun attachBaseContext(newBase: Context) {
        PrefManager.init(newBase)
        val languageCode = PrefManager.appLanguage
        val context = LocaleHelper.applyLanguage(newBase, languageCode)
        super.attachBaseContext(context)
    }

    // To view log messages in android logcat properly
    private val logger = object : LogListener {
        override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
            val logMessage = message ?: "No message given"
            Timber.i(throwable, "[${clazz.simpleName}] -> $logMessage")
        }

        override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
            val logMessage = message ?: "No message given"
            Timber.e(throwable, "[${clazz.simpleName}] -> $logMessage")
        }
    }

    @Inject
    lateinit var db: PluviaDatabase

    @Inject
    lateinit var licenseDao: SteamLicenseDao

    @Inject
    lateinit var appDao: SteamAppDao

    @Inject
    lateinit var changeNumbersDao: ChangeNumbersDao

    @Inject
    lateinit var appInfoDao: AppInfoDao

    @Inject
    lateinit var fileChangeListsDao: FileChangeListsDao

    @Inject
    lateinit var steamFileHashCacheDao: SteamFileHashCacheDao

    @Inject
    lateinit var cachedLicenseDao: CachedLicenseDao

    @Inject
    lateinit var encryptedAppTicketDao: EncryptedAppTicketDao

    @Inject
    lateinit var downloadingAppInfoDao: DownloadingAppInfoDao

    @Inject
    lateinit var steamUnlockedBranchDao: SteamUnlockedBranchDao

    private lateinit var notificationHelper: NotificationHelper

    private val notifierOrNull: NotificationHelper? get() = if (::notificationHelper.isInitialized) notificationHelper else null

    internal var callbackManager: CallbackManager? = null
    internal var steamClient: SteamClient? = null
    internal val callbackSubscriptions: ArrayList<Closeable> = ArrayList()

    private var _unifiedFriends: SteamUnifiedFriends? = null
    private var _steamUser: SteamUser? = null
    private var _steamApps: SteamApps? = null
    private var _steamFriends: SteamFriends? = null
    private var _steamCloud: SteamCloud? = null
    private var _steamUserStats: SteamUserStats? = null
    private var _steamFamilyGroups: FamilyGroups? = null

    private var _loginResult: LoginResult = LoginResult.Failed

    private var licenses: List<License> = emptyList()

    private var retryAttempt = 0

    private val appPicsChannel = Channel<List<PICSRequest>>(
        capacity = 1_000,
        onBufferOverflow = BufferOverflow.SUSPEND,
        onUndeliveredElement = { droppedApps ->
            Timber.w("App PICS Channel dropped: ${droppedApps.size} apps")
        },
    )

    private val packagePicsChannel = Channel<List<PICSRequest>>(
        capacity = 1_000,
        onBufferOverflow = BufferOverflow.SUSPEND,
        onUndeliveredElement = { droppedPackages ->
            Timber.w("Package PICS Channel dropped: ${droppedPackages.size} packages")
        },
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var offlineAchievementSyncJob: Job? = null
    private val pendingSyncAppIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val pendingSyncFileLock = Any()
    private val pendingSyncFile by lazy { File(applicationContext.filesDir, "pending_achievement_sync.txt") }

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        Companion.stop()
    }

    // The current shared family group the logged in user is joined to.
    private var familyGroupMembers: ArrayList<Int> = arrayListOf()
    private var familyGroupId: Long = 0L

    private fun setFamilyGroupId(id: Long) {
        familyGroupId = id
        _familyGroupIdFlow.value = id
    }

    private fun bumpFamilyPreferredCopyDataVersion() {
        _familyPreferredCopyDataVersion.update { it + 1 }
    }

    /** appId → distinct owner steamId64s from GetSharedLibraryApps */
    private val familyAppOwnerSteamIds: ConcurrentHashMap<Int, List<Long>> = ConcurrentHashMap()
    /**
     * True only after a successful GetSharedLibraryApps with includeNonGames=true
     * and includeExcluded=true. Games-only / excluded-filtered caches must not be
     * used for preferred-copy DLC counts (Steam omits DLC unless both flags are set).
     */
    @Volatile
    private var familySharedLibraryReadyForDlcCounts: Boolean = false
    /** appId → preferred lender steamId64 from GetPreferredLenders / user choice */
    private val preferredLenderByAppId: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()
    /** steamId64 → display name for family members when known */
    private val familyMemberNames: ConcurrentHashMap<Long, String> = ConcurrentHashMap()

    private val appTokens: ConcurrentHashMap<Int, Long> = ConcurrentHashMap()

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Add these as class properties
    private var picsGetProductInfoJob: Job? = null
    private var picsChangesCheckerJob: Job? = null
    private var friendCheckerJob: Job? = null
    private var steamCollectionsJob: Job? = null

    private val _isPlayingBlocked = MutableStateFlow(false)
    val isPlayingBlocked = _isPlayingBlocked.asStateFlow()
    private val _isHandlingConflict = AtomicBoolean(false)

    // Cache in-memory the local persona state.
    private val _localPersona = MutableStateFlow(
        SteamFriend(name = PrefManager.steamUserName, avatarHash = PrefManager.steamUserAvatarHash),
    )
    val localPersona = _localPersona.asStateFlow()

    companion object {
        const val MAX_PICS_BUFFER = 256

        const val MAX_RETRY_ATTEMPTS = 20

        const val INVALID_APP_ID: Int = Int.MAX_VALUE
        const val INVALID_PKG_ID: Int = Int.MAX_VALUE
        private const val STEAM_CONTROLLER_CONFIG_FILENAME = "steam_controller_config.vdf"

        /**
         * Default timeout to use when making requests
         */
        var requestTimeout = 30.seconds

        /**
         * Default timeout to use when reading the response body
         */
        var responseTimeout = 120.seconds

        private val PROTOCOL_TYPES = EnumSet.of(ProtocolTypes.WEB_SOCKET)

        internal var instance: SteamService? = null

        /** Serializes GetSharedLibraryApps clear+refill so login and modal cannot interleave. */
        private val familySharedLibraryRefreshMutex = Mutex()

        var cachedAchievements: List<app.gamenative.statsgen.Achievement>? = null
            private set
        var cachedAchievementsAppId: Int? = null
            private set

        fun clearCachedAchievements() {
            cachedAchievements = null
            cachedAchievementsAppId = null
        }

        val hasWifiOrEthernet: Boolean get() = NetworkMonitor.hasWifiOrEthernet.value

        /** @return true if download may proceed; false if blocked (notifies user) */
        private fun checkWifiOrNotify(): Boolean {
            if (PrefManager.downloadOnWifiOnly && !hasWifiOrEthernet) {
                val svc = instance
                if (svc != null) {
                    svc.notificationHelper.notify(svc.getString(R.string.download_no_wifi))
                } else {
                    Timber.w("checkWifiOrNotify: no SteamService instance to notify")
                }
                return false
            }
            return true
        }

        private val downloadJobs = ConcurrentHashMap<Int, DownloadInfo>()

        /** Apps with a workshop download that was paused (cancelled) by the user. */
        val workshopPausedApps: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        // Depot-key acquisition progress, keyed by appId. Useful for games with many depots like Borderlands 2
        private val depotKeyPrep = ConcurrentHashMap<Int, DepotKeyPrep>()

        // owner pins the phase to one specific download attempt. A cancelled DepotDownloader can
        // still deliver a chunk while it unwinds, and depot keys arrive on the callback thread, so
        // callbacks belonging to a previous download of the same app must not touch a newer
        // attempt's state — nor may its messages go to a DownloadInfo that has since been replaced.
        private class DepotKeyPrep(val owner: DownloadInfo, val total: Int) {
            val resolved = AtomicInteger(0)
        }

        private val depotKeyOwner = ConcurrentHashMap<Int, Int>()

        // Guards the prep bookkeeping together with the status messages it writes, so a key
        // callback can never re-post "Preparing depots" after the phase has already been ended.
        private val depotKeyPrepLock = Any()

        // Begins the depot key prep phase, seeding a status message so the UI never shows a bare 0%
        private fun beginDepotKeyPrep(appId: Int, depotIds: Set<Int>, downloadInfo: DownloadInfo) {
            synchronized(depotKeyPrepLock) {
                depotKeyPrep[appId] = DepotKeyPrep(downloadInfo, depotIds.size)
                depotIds.forEach { depotId -> depotKeyOwner[depotId] = appId }
                instance?.let { downloadInfo.updateStatusMessage(it.getString(R.string.download_preparing)) }
            }
        }

        /** Called once per depot key Steam returns, from the DepotKeyCallback subscription. */
        private fun noteDepotKeyResolved(depotId: Int) {
            val appId = depotKeyOwner[depotId] ?: return
            val svc = instance ?: return
            synchronized(depotKeyPrepLock) {
                val prep = depotKeyPrep[appId] ?: return
                val done = prep.resolved.incrementAndGet().coerceAtMost(prep.total)
                prep.owner.updateStatusMessage(
                    svc.getString(R.string.download_preparing_depots, done, prep.total),
                )
            }
        }

        // Ends the depot key prep phase and clears its status message, once the actual download
        // begins or the job goes away. Callers that can outlive their own download (the progress
        // callbacks) must pass [owner]: the phase is then only ended if it still belongs to that
        // download, so a late callback cannot wipe the message a newer attempt just posted. Omit
        // [owner] to end the phase whoever owns it, e.g. when tearing the job down.
        internal fun clearDepotKeyPrep(appId: Int, owner: DownloadInfo? = null) {
            if (!depotKeyPrep.containsKey(appId)) return
            synchronized(depotKeyPrepLock) {
                val prep = depotKeyPrep[appId] ?: return
                if (owner != null && prep.owner !== owner) return
                depotKeyOwner.entries.removeIf { it.value == appId }
                depotKeyPrep.remove(appId)
                prep.owner.updateStatusMessage(null)
            }
        }

        internal fun notifyDownloadStarted(appId: Int) {
            PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, true))
        }

        private fun notifyDownloadStopped(appId: Int) {
            PluviaApp.events.emit(AndroidEvent.DownloadStatusChanged(appId, false))
        }

        fun removeDownloadJob(appId: Int) {
            clearDepotKeyPrep(appId)
            // Keep an auto-paused (queued) entry so the downloads UI keeps showing it
            // as Queued (same as Epic/GOG/Amazon) instead of falling back to a partial
            // "Ready to Resume" row with a manual resume button. downloadApp replaces
            // the stale entry when the queue resumes it.
            downloadJobs[appId]?.let { if (it.wasAutoPaused()) return }
            val removed = downloadJobs.remove(appId)
            if (removed != null) {
                notifyDownloadStopped(appId)
            }
        }

        /** Returns true if there is an incomplete download on disk (no complete marker). */
        fun hasPartialDownload(appId: Int): Boolean {
            if (workshopPausedApps.contains(appId)) return true

            val downloadingApp = getDownloadingAppInfoOf(appId)
            if (downloadingApp != null) {
                return true
            }

            val dirPath = getAppDirPath(appId)
            return MarkerUtils.hasPartialInstall(dirPath)
        }

        private val syncInProgressApps = ConcurrentHashMap<Int, AtomicBoolean>()

        private fun getSyncFlag(appId: Int): AtomicBoolean {
            val existing = syncInProgressApps[appId]
            if (existing != null) {
                return existing
            }
            val created = AtomicBoolean(false)
            val prior = syncInProgressApps.putIfAbsent(appId, created)
            return prior ?: created
        }

        private fun tryAcquireSync(appId: Int): Boolean {
            val flag = getSyncFlag(appId)
            val acquired = flag.compareAndSet(false, true)
            if (acquired) instance?.notifierOrNull?.showSyncing(NotificationHelper.NOTIFICATION_ID_STEAM)
            return acquired
        }

        private fun releaseSync(appId: Int) {
            val flag = syncInProgressApps[appId]
            flag?.set(false)
            if (flag != null && !flag.get()) {
                syncInProgressApps.remove(appId, flag)
            }
            instance?.notifierOrNull?.showIdle(NotificationHelper.NOTIFICATION_ID_STEAM)
        }

        // Track whether a game is currently running to prevent premature service stop
        @JvmStatic
        @Volatile
        var keepAlive: Boolean = false

        @Volatile
        var isImporting: Boolean = false

        var isStopping: Boolean = false
            private set
        var isConnected: Boolean = false
            private set
        var isRunning: Boolean = false
            private set
        var isLoggingOut: Boolean = false
            private set
        val isLoggedIn: Boolean
            get() = instance?.steamClient?.steamID?.isValid == true
        var isWaitingForQRAuth: Boolean = false
            private set

        fun clearPlayingConflict() {
            instance?._isPlayingBlocked?.value = false
            instance?._isHandlingConflict?.set(false)
        }

        private val serverListPath: String
            get() = Paths.get(DownloadService.baseCacheDirPath, "server_list.bin").pathString

        val internalAppInstallPath: String
            get() = Paths.get(DownloadService.baseDataDirPath, "Steam", "steamapps", "common").pathString

        private val externalAppInstallRoot: String
            get() = PrefManager.externalStoragePath

        val externalAppInstallPath: String
            get() = Paths.get(externalAppInstallRoot, "Steam", "steamapps", "common").pathString

        // all install paths: internal + configured external + all mounted volumes
        val allInstallPaths: List<String>
            get() {
                val paths = mutableListOf(internalAppInstallPath)
                // only include configured external path if it's a real absolute path
                if (PrefManager.externalStoragePath.isNotBlank()) {
                    paths += externalAppInstallPath
                }
                for (volPath in DownloadService.externalVolumePaths) {
                    if (volPath.isNotBlank()) {
                        paths += Paths.get(volPath, "Steam", "steamapps", "common").pathString
                    }
                }
                return paths.distinct()
            }

        private val internalAppStagingPath: String
            get() {
                return Paths.get(DownloadService.baseDataDirPath, "Steam", "steamapps", "staging").pathString
            }
        private val externalAppStagingPath: String
            get() {
                return Paths.get(externalAppInstallRoot, "Steam", "steamapps", "staging").pathString
            }

        private val externalStorageReady: Boolean
            get() = PrefManager.useExternalStorage && File(externalAppInstallRoot).let {
                it.path.isNotBlank() && it.exists()
            }

        val defaultStoragePath: String
            get() {
                return if (externalStorageReady) {
                    Timber.i("External storage path is $externalAppInstallRoot")
                    externalAppInstallRoot
                } else {
                    if (instance != null) {
                        return DownloadService.baseDataDirPath
                    }
                    return ""
                }
            }

        val defaultAppInstallPath: String
            get() {
                return if (externalStorageReady) {
                    Timber.i("Using external storage")
                    Timber.i("install path for external storage is " + externalAppInstallPath)
                    externalAppInstallPath
                } else {
                    Timber.i("Using internal storage")
                    internalAppInstallPath
                }
            }

        val defaultAppStagingPath: String
            get() {
                return if (PrefManager.useExternalStorage) {
                    externalAppStagingPath
                } else {
                    internalAppStagingPath
                }
            }

        val userSteamId: SteamID?
            get() = instance?.steamClient?.steamID

        val familyMembers: List<Int>
            get() = instance?.familyGroupMembers ?: emptyList()

        val familyGroupId: Long
            get() = instance?.familyGroupId ?: 0L

        /** Observable family group id; updates when LoggedOn hydrates (or clears) family sharing. */
        private val _familyGroupIdFlow = MutableStateFlow(0L)
        val familyGroupIdFlow: StateFlow<Long> = _familyGroupIdFlow.asStateFlow()

        /**
         * Bumps when family preferred-copy caches finish refreshing (owners, preferred lenders).
         * [familyGroupIdFlow] alone is not enough: the id is set before those RPCs complete,
         * and StateFlow will not re-emit an unchanged id when a later refresh fills the caches.
         */
        private val _familyPreferredCopyDataVersion = MutableStateFlow(0)
        val familyPreferredCopyDataVersion: StateFlow<Int> = _familyPreferredCopyDataVersion.asStateFlow()

        suspend fun hasMultiplePreferredCopyOptions(appId: Int): Boolean =
            getPreferredCopyOptions(appId).size >= 2

        suspend fun getPreferredCopyOptions(appId: Int): List<PreferredCopyOption> = withContext(Dispatchers.IO) {
            val svc = instance ?: return@withContext emptyList()
            // Preferred-copy UI only applies in a Steam Family; skip the expensive license scan otherwise.
            if (svc.familyGroupId == 0L) return@withContext emptyList()
            val selfId = userSteamId ?: return@withContext emptyList()
            val selfSteamId = selfId.convertToUInt64()
            val selfAccountId = selfId.accountID.toInt()

            val ownerSteamIds = linkedSetOf<Long>()
            val cachedOwners = svc.familyAppOwnerSteamIds[appId]
            val ownerSource = when {
                !cachedOwners.isNullOrEmpty() -> {
                    ownerSteamIds.addAll(cachedOwners)
                    "sharedLibrary"
                }
                else -> null
            }

            // Load licenses once; reuse for owner discovery (fallback) and package lookup.
            val allLicenses = svc.licenseDao.getAllLicenses()
            val licensesForApp = allLicenses.filter { appId in it.appIds }
            val resolvedOwnerSource = if (ownerSource == null && licensesForApp.isNotEmpty()) {
                for (license in licensesForApp) {
                    for (accountId in license.ownerAccountId) {
                        ownerSteamIds.add(SteamID(accountId.toLong(), EUniverse.Public, EAccountType.Individual).convertToUInt64())
                    }
                }
                "licenses"
            } else {
                ownerSource
            }

            val finalOwnerSource = if (ownerSteamIds.isEmpty()) {
                // Fallback: active package owners (already on IO; avoid nested runBlocking via getAppInfoOf)
                svc.appDao.findApp(appId)?.ownerAccountId?.forEach { accountId ->
                    ownerSteamIds.add(SteamID(accountId.toLong(), EUniverse.Public, EAccountType.Individual).convertToUInt64())
                }
                if (ownerSteamIds.isNotEmpty()) "appOwnerAccountId" else "none"
            } else {
                resolvedOwnerSource ?: "unknown"
            }

            if (ownerSteamIds.isEmpty()) {
                Timber.d(
                    "getPreferredCopyOptions appId=$appId owners=0 source=$finalOwnerSource " +
                        "sharedCached=${cachedOwners?.size ?: 0} licensesForApp=${licensesForApp.size}",
                )
                return@withContext emptyList()
            }

            // DLC counts stay null here; the preferred-copy modal always runs
            // ensurePreferredCopyDlcCounts before treating counts as final.
            val selfDisplayName = PrefManager.steamUserName

            val options = ownerSteamIds.map { steamId64 ->
                val steamId = SteamID(steamId64)
                val accountId = steamId.accountID.toInt()
                val isSelf = steamId64 == selfSteamId || accountId == selfAccountId
                val packageId = findLicenseForLender(licensesForApp, accountId)?.packageId
                PreferredCopyOption(
                    lenderSteamId = steamId64,
                    accountId = accountId,
                    displayName = if (isSelf) {
                        selfDisplayName
                    } else {
                        svc.familyMemberNames[steamId64].orEmpty()
                    },
                    isSelf = isSelf,
                    packageId = packageId,
                    ownedDlcCount = null,
                )
            }.sortedWith(compareByDescending<PreferredCopyOption> { it.isSelf }.thenBy { it.displayName })

            Timber.d(
                "getPreferredCopyOptions appId=$appId source=$finalOwnerSource " +
                    "owners=${options.map { "${it.accountId}(self=${it.isSelf},pkg=${it.packageId},name=${it.displayName})" }} " +
                    "sharedCachedOwners=${cachedOwners.orEmpty()} licensesForApp=${licensesForApp.size}",
            )
            options
        }

        /**
         * Resolves the parent game's DLC ID catalog (local + PICS), refreshes Family
         * shared-library owners including non-games and excluded apps (DLC), ensures
         * lender package license appIds are filled (viaLicense readiness), then fills
         * per-lender counts from shared-library owners union local licenses.
         * Always refreshes; callers should show a loading state until this returns.
         * Leaves [PreferredCopyOption.ownedDlcCount] null only when the catalog is empty
         * or neither ownership source is usable.
         */
        suspend fun ensurePreferredCopyDlcCounts(
            appId: Int,
            options: List<PreferredCopyOption>,
        ): List<PreferredCopyOption> = withContext(Dispatchers.IO) {
            if (options.isEmpty()) return@withContext options
            val svc = instance ?: return@withContext options.map { it.copy(ownedDlcCount = null) }
            val dlcIds = resolveDlcIdsForApp(appId, allowNetwork = true)
            if (dlcIds.isEmpty()) {
                Timber.i("ensurePreferredCopyDlcCounts appId=$appId dlcIds=0 (catalog empty)")
                return@withContext options.map { it.copy(ownedDlcCount = null) }
            }

            // Steam omits DLC from GetSharedLibraryApps unless includeExcluded=true
            // (e.g. AppExcluded_NonrefundableDLC). includeNonGames alone is not enough.
            val refresh = refreshFamilySharedLibraryOwners(
                includeNonGames = true,
                includeExcluded = true,
            )
            val familyOwners = refresh.owners
            val sharedReady = refresh.freshSuccess || svc.familySharedLibraryReadyForDlcCounts

            // viaLicense depends on package PICS having filled SteamLicense.appIds.
            // That queue races the preferred-copy modal; fill empty lender packages
            // synchronously before treating counts as final (keeps the UI spinner up).
            val lenderAccountIds = options.mapTo(HashSet()) { it.accountId }
            val packagesFilled = ensureLenderPackageAppIdsReady(lenderAccountIds)

            val allLicenses = svc.licenseDao.getAllLicenses()
            // License fallback only counts when appIds were filled (package PICS). Empty appIds
            // would otherwise yield a fake "0 DLC" after shared-library refresh failure.
            val anyLenderHasPopulatedLicenses = options.any { option ->
                allLicenses.any {
                    option.accountId in it.ownerAccountId && it.appIds.isNotEmpty()
                }
            }
            if (!sharedReady && !anyLenderHasPopulatedLicenses) {
                Timber.i(
                    "ensurePreferredCopyDlcCounts appId=$appId dlcIds=${dlcIds.size} " +
                        "freshSuccess=${refresh.freshSuccess} sharedReady=false noPopulatedLicenses " +
                        "packagesFilled=$packagesFilled",
                )
                return@withContext options.map { it.copy(ownedDlcCount = null) }
            }

            val withCounts = options.map { option ->
                val lenderLicenses = allLicenses.filter { option.accountId in it.ownerAccountId }
                val viaShared = if (sharedReady) {
                    dlcIds.filter { familyOwners[it]?.contains(option.lenderSteamId) == true }
                } else {
                    emptyList()
                }
                val viaLicense = dlcIds.filter { dlcId -> lenderLicenses.any { dlcId in it.appIds } }
                val count = (viaShared.toSet() + viaLicense.toSet()).size
                Timber.d(
                    "ensurePreferredCopyDlcCounts LENDER appId=$appId " +
                        "accountId=${option.accountId} name=${option.displayName} self=${option.isSelf} " +
                        "viaShared=$viaShared viaLicense=$viaLicense count=$count " +
                        "lenderLicenseCount=${lenderLicenses.size}",
                )
                option.copy(ownedDlcCount = count)
            }
            Timber.i(
                "ensurePreferredCopyDlcCounts appId=$appId dlcIds=${dlcIds.size} " +
                    "packagesFilled=$packagesFilled " +
                    "freshSuccess=${refresh.freshSuccess} sharedReady=$sharedReady " +
                    "counts=${withCounts.map { "${it.accountId}:${it.ownedDlcCount}" }}",
            )
            withCounts
        }

        /**
         * Ensures [SteamLicense.appIds] are populated for packages owned by [lenderAccountIds].
         * Preferred-copy DLC counts use license appIds; those are normally filled by the
         * async package PICS queue, which can still be empty when the modal opens.
         * Returns how many packages were updated in this call.
         */
        private suspend fun ensureLenderPackageAppIdsReady(lenderAccountIds: Set<Int>): Int {
            if (lenderAccountIds.isEmpty()) return 0
            val svc = instance ?: return 0
            val steamApps = svc._steamApps ?: return 0
            val pending = svc.licenseDao.getAllLicenses().filter { license ->
                license.appIds.isEmpty() &&
                    license.ownerAccountId.any { it in lenderAccountIds }
            }
            if (pending.isEmpty()) {
                Timber.d(
                    "ensureLenderPackageAppIdsReady: no empty appIds for " +
                        "${lenderAccountIds.size} lenders",
                )
                return 0
            }
            Timber.i(
                "ensureLenderPackageAppIdsReady: filling appIds for ${pending.size} packages " +
                    "(lenders=${lenderAccountIds.size})",
            )
            var filled = 0
            pending.chunked(MAX_PICS_BUFFER).forEach { chunk ->
                val requests = chunk.map { PICSRequest(it.packageId, it.accessToken) }
                try {
                    val callback = steamApps.picsGetProductInfo(
                        apps = emptyList(),
                        packages = requests,
                    ).await()
                    callback.results.forEach { picsCallback ->
                        picsCallback.packages.values.forEach { pkg ->
                            val appIds = pkg.keyValues["appids"].children.map { it.asInteger() }
                            val depotIds = pkg.keyValues["depotids"].children.map { it.asInteger() }
                            svc.licenseDao.updateApps(pkg.id, appIds)
                            svc.licenseDao.updateDepots(pkg.id, depotIds)
                            filled++
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "ensureLenderPackageAppIdsReady: PICS failed for chunk size=${chunk.size}",
                    )
                }
            }
            Timber.i("ensureLenderPackageAppIdsReady: updated $filled packages")
            return filled
        }

        /**
         * Collects DLC app IDs for [appId] from local parent metadata and DLC rows.
         * When [allowNetwork] is true, PICS-fetches the parent (with access token when
         * available) and merges remote listofdlc / depot DLC ids.
         */
        private suspend fun resolveDlcIdsForApp(appId: Int, allowNetwork: Boolean): Set<Int> {
            val svc = instance ?: return emptySet()
            val ids = linkedSetOf<Int>()
            val fromListOfDlc = linkedSetOf<Int>()
            val fromDepots = linkedSetOf<Int>()
            val fromParentRows = linkedSetOf<Int>()
            val fromLicensedRows = linkedSetOf<Int>()
            val fromPics = linkedSetOf<Int>()

            fun collectFromApp(app: SteamApp?, intoListOfDlc: MutableSet<Int>, intoDepots: MutableSet<Int>) {
                if (app == null) return
                app.dlcAppIds.filter { it > 0 && it != INVALID_APP_ID }.forEach {
                    intoListOfDlc.add(it)
                    ids.add(it)
                }
                app.depots.values.forEach { depot ->
                    if (depot.dlcAppId != INVALID_APP_ID && depot.dlcAppId > 0) {
                        intoDepots.add(depot.dlcAppId)
                        ids.add(depot.dlcAppId)
                    }
                }
            }

            collectFromApp(svc.appDao.findApp(appId), fromListOfDlc, fromDepots)
            svc.appDao.findDlcAppIdsForParent(appId).forEach {
                fromParentRows.add(it)
                ids.add(it)
            }
            svc.appDao.findDownloadableDLCApps(appId).orEmpty().forEach {
                fromLicensedRows.add(it.id)
                ids.add(it.id)
            }
            svc.appDao.findHiddenDLCApps(appId).orEmpty().forEach {
                fromLicensedRows.add(it.id)
                ids.add(it.id)
            }

            if (!allowNetwork) {
                Timber.d(
                    "resolveDlcIdsForApp appId=$appId allowNetwork=false " +
                        "listofdlc=$fromListOfDlc depots=$fromDepots parentRows=$fromParentRows " +
                        "licensedRows=$fromLicensedRows total=$ids",
                )
                return ids
            }

            val steamApps = svc._steamApps ?: return ids
            try {
                val accessToken = try {
                    steamApps.picsGetAccessTokens(
                        appIds = listOf(appId),
                        packageIds = emptyList(),
                    ).await().appTokens[appId] ?: 0L
                } catch (e: Exception) {
                    Timber.w(e, "resolveDlcIdsForApp: access token failed for appId=$appId")
                    0L
                }
                Timber.d("resolveDlcIdsForApp appId=$appId picsAccessToken=${accessToken != 0L}")
                val pics = steamApps.picsGetProductInfo(
                    apps = listOf(PICSRequest(id = appId, accessToken = accessToken)),
                    packages = emptyList(),
                ).await()
                val remote = pics.results
                    .firstOrNull()
                    ?.apps
                    ?.values
                    ?.firstOrNull()
                    ?: run {
                        Timber.d("resolveDlcIdsForApp appId=$appId PICS returned no app; total=$ids")
                        return ids
                    }
                val generated = remote.keyValues.generateSteamApp()
                collectFromApp(generated, fromPics, fromDepots)

                // Persist so subsequent opens start with a fuller local catalog.
                val existing = svc.appDao.findApp(appId)
                if (existing != null) {
                    val mergedDlcAppIds = (existing.dlcAppIds + generated.dlcAppIds)
                        .filter { it > 0 && it != INVALID_APP_ID }
                        .distinct()
                    svc.appDao.insert(
                        existing.copy(
                            dlcAppIds = mergedDlcAppIds.ifEmpty { existing.dlcAppIds },
                            depots = if (generated.depots.isNotEmpty()) generated.depots else existing.depots,
                            receivedPICS = true,
                            lastChangeNumber = remote.changeNumber,
                        ),
                    )
                } else {
                    svc.appDao.insert(
                        generated.copy(
                            receivedPICS = true,
                            lastChangeNumber = remote.changeNumber,
                        ),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "resolveDlcIdsForApp: PICS failed for appId=$appId")
            }
            Timber.d(
                "resolveDlcIdsForApp appId=$appId " +
                    "listofdlcLocal=$fromListOfDlc picsListofdlc=$fromPics depots=$fromDepots " +
                    "parentRows=$fromParentRows licensedRows=$fromLicensedRows total=${ids.size} ids=$ids",
            )
            return ids
        }

        private data class SharedLibraryRefreshResult(
            val owners: Map<Int, List<Long>>,
            /**
             * True only when this call received EResult.OK for the requested
             * includeNonGames / includeExcluded flags.
             */
            val freshSuccess: Boolean,
        )

        /**
         * Fetches Steam Family shared-library ownership and replaces [familyAppOwnerSteamIds].
         * Pass [includeNonGames] = true and [includeExcluded] = true for preferred-copy DLC
         * counts; Steam omits DLC rows unless both are set.
         * On failure returns the current cache with [SharedLibraryRefreshResult.freshSuccess] false.
         */
        private suspend fun refreshFamilySharedLibraryOwners(
            includeNonGames: Boolean,
            includeExcluded: Boolean = false,
        ): SharedLibraryRefreshResult = familySharedLibraryRefreshMutex.withLock {
            val svc = instance
                ?: return SharedLibraryRefreshResult(emptyMap(), freshSuccess = false)
            val familyGroups = svc._steamFamilyGroups
                ?: return SharedLibraryRefreshResult(
                    svc.familyAppOwnerSteamIds.toMap(),
                    freshSuccess = false,
                )
            if (svc.familyGroupId == 0L) {
                return SharedLibraryRefreshResult(emptyMap(), freshSuccess = false)
            }

            try {
                val sharedRequest = SteammessagesFamilygroupsSteamclient.CFamilyGroups_GetSharedLibraryApps_Request.newBuilder().apply {
                    familyGroupid = svc.familyGroupId
                    includeOwn = true
                    this.includeExcluded = includeExcluded
                    this.includeNonGames = includeNonGames
                    // Omit maxApps so Steam uses its default (large Int.MAX_VALUE was speculative).
                }.build()

                val sharedResult = familyGroups.getSharedLibraryApps(sharedRequest).await()
                if (sharedResult.result != EResult.OK) {
                    Timber.w(
                        "GetSharedLibraryApps(includeNonGames=$includeNonGames " +
                            "includeExcluded=$includeExcluded) failed: ${sharedResult.result}",
                    )
                    return SharedLibraryRefreshResult(
                        svc.familyAppOwnerSteamIds.toMap(),
                        freshSuccess = false,
                    )
                }

                // Full replace under the mutex so readers never see a half-cleared map from
                // concurrent login + modal refreshes.
                val next = ConcurrentHashMap<Int, List<Long>>()
                sharedResult.body.appsList.forEach { sharedApp ->
                    if (sharedApp.ownerSteamidsCount >= 1) {
                        next[sharedApp.appid] = sharedApp.ownerSteamidsList.toList()
                    }
                }
                svc.familyAppOwnerSteamIds.clear()
                svc.familyAppOwnerSteamIds.putAll(next)
                // Only mark DLC-ready when this successful response requested both flags.
                svc.familySharedLibraryReadyForDlcCounts = includeNonGames && includeExcluded
                Timber.i(
                    "Cached shared library owners for ${svc.familyAppOwnerSteamIds.size} apps " +
                        "(includeNonGames=$includeNonGames includeExcluded=$includeExcluded)",
                )
                SharedLibraryRefreshResult(
                    svc.familyAppOwnerSteamIds.toMap(),
                    freshSuccess = true,
                )
            } catch (e: Exception) {
                Timber.e(
                    e,
                    "GetSharedLibraryApps(includeNonGames=$includeNonGames " +
                        "includeExcluded=$includeExcluded) failed",
                )
                SharedLibraryRefreshResult(
                    svc.familyAppOwnerSteamIds.toMap(),
                    freshSuccess = false,
                )
            }
        }

        suspend fun getActivePreferredCopy(appId: Int): PreferredCopyOption? =
            selectActivePreferredCopy(appId, getPreferredCopyOptions(appId))

        /**
         * Resolves the active preferred copy. Prefers the in-memory lender map; only
         * falls back to [PrefManager.preferredFamilyLenders] (sync DataStore read) when
         * that map has not been hydrated yet. Call from a background dispatcher.
         */
        fun selectActivePreferredCopy(
            appId: Int,
            options: List<PreferredCopyOption>,
        ): PreferredCopyOption? {
            if (options.isEmpty()) return null
            val preferredMap = instance?.preferredLenderByAppId
            val preferredSteamId = when {
                preferredMap == null -> PrefManager.preferredFamilyLenders[appId]
                // Empty map means not yet hydrated from network/prefs; allow PrefManager fallback.
                preferredMap.isEmpty() -> PrefManager.preferredFamilyLenders[appId]
                else -> preferredMap[appId]
            }
            if (preferredSteamId != null) {
                options.firstOrNull { it.lenderSteamId == preferredSteamId }?.let { return it }
            }
            val self = options.firstOrNull { it.isSelf }
            if (self != null) return self
            return options.first()
        }

        suspend fun setPreferredCopy(appId: Int, lenderSteamId: Long): Boolean = withContext(Dispatchers.IO) {
            val svc = instance ?: return@withContext false
            val groupId = svc.familyGroupId
            if (groupId == 0L) {
                Timber.w("setPreferredCopy: no family group")
                return@withContext false
            }
            val familyGroups = svc._steamFamilyGroups ?: return@withContext false

            val request = SteammessagesFamilygroupsSteamclient.CFamilyGroups_SetPreferredLender_Request.newBuilder().apply {
                familyGroupid = groupId
                this.appid = appId
                this.lenderSteamid = lenderSteamId
            }.build()

            val result = try {
                familyGroups.setPreferredLender(request).await()
            } catch (e: Exception) {
                Timber.e(e, "setPreferredLender failed for appId=$appId")
                return@withContext false
            }

            if (result.result != EResult.OK) {
                Timber.w("setPreferredLender returned ${result.result} for appId=$appId")
                return@withContext false
            }

            svc.preferredLenderByAppId[appId] = lenderSteamId
            PrefManager.setPreferredFamilyLender(appId, lenderSteamId)
            applyPreferredLenderLocally(appId, lenderSteamId, svc.licenseDao.getAllLicenses())
            // Emit on Main so Compose listeners can safely update UI state.
            withContext(Dispatchers.Main.immediate) {
                PluviaApp.events.emit(AndroidEvent.PreferredCopyChanged(appId))
            }
            true
        }

        private fun findLicenseForLender(
            licensesForApp: List<SteamLicense>,
            lenderAccountId: Int,
        ): SteamLicense? {
            val licenses = licensesForApp.filter { lenderAccountId in it.ownerAccountId }
            if (licenses.isEmpty()) return null
            return licenses.maxByOrNull { license ->
                when {
                    ELicenseFlags.Expired in license.licenseFlags -> 0
                    else -> 1
                }
            }
        }

        private suspend fun applyPreferredLenderLocally(
            appId: Int,
            lenderSteamId: Long,
            allLicenses: List<SteamLicense>,
        ) {
            val svc = instance ?: return
            val lenderAccountId = SteamID(lenderSteamId).accountID.toInt()
            val licensesForApp = allLicenses.filter { appId in it.appIds }
            val license = findLicenseForLender(licensesForApp, lenderAccountId)
            val app = svc.appDao.findApp(appId) ?: return
            if (license != null) {
                svc.appDao.update(
                    app.copy(
                        packageId = license.packageId,
                        ownerAccountId = listOf(lenderAccountId),
                        licenseFlags = license.licenseFlags,
                    ),
                )
                Timber.i(
                    "Applied preferred lender $lenderAccountId for app $appId → package ${license.packageId}",
                )
            } else {
                // Still flip owner for badge / play session even if package row is missing.
                svc.appDao.update(app.copy(ownerAccountId = listOf(lenderAccountId)))
                Timber.w(
                    "Preferred lender $lenderAccountId for app $appId has no local license; ownerAccountId updated only",
                )
            }
        }

        private suspend fun applyAllCachedPreferredLenders() {
            val svc = instance ?: return
            val preferred = PrefManager.preferredFamilyLenders.toMutableMap()
            preferred.putAll(svc.preferredLenderByAppId)
            if (preferred.isEmpty()) return
            val allLicenses = svc.licenseDao.getAllLicenses()
            for ((appId, lenderSteamId) in preferred) {
                applyPreferredLenderLocally(appId, lenderSteamId, allLicenses)
            }
        }

        val isLoginInProgress: Boolean
            get() = instance?._loginResult == LoginResult.InProgress

        suspend fun setPersonaState(state: EPersonaState) = withContext(Dispatchers.IO) {
            PrefManager.personaState = state
            instance?._steamFriends?.setPersonaState(state)
        }

        suspend fun requestUserPersona() = withContext(Dispatchers.IO) {
            // in order to get user avatar url and other info
            userSteamId?.let { instance?._steamFriends?.requestFriendInfo(it) }
        }

        suspend fun getSelfCurrentlyPlayingAppId(): Int? = withContext(Dispatchers.IO) {
            val self = instance?.localPersona?.value ?: return@withContext null
            if (self.isPlayingGame) self.gameAppID else null
        }

        suspend fun kickPlayingSession(onlyGame: Boolean = true): Boolean = withContext(Dispatchers.IO) {
            val user = instance?._steamUser ?: return@withContext false
            try {
                instance?._isPlayingBlocked?.value = true
                user.kickPlayingSession(onlyStopGame = onlyGame)

                // Wait for PlayingSessionStateCallback to indicate unblocked
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    if (instance?._isPlayingBlocked?.value == false) return@withContext true
                    delay(100)
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Get licenses from database for use with DepotDownloader
         */
        suspend fun getLicensesFromDb(): List<License> = withContext(Dispatchers.IO) {
            val cached = instance?.cachedLicenseDao?.getAll() ?: return@withContext emptyList()
            cached.mapNotNull { cachedLicense ->
                LicenseSerializer.deserializeLicense(cachedLicense.licenseJson)
            }
        }

        fun isAppLicensed(packageId: Int): Boolean {
            return runBlocking(Dispatchers.IO) {
                instance?.licenseDao?.findLicense(packageId) != null
            }
        }

        fun getPkgInfoOf(appId: Int): SteamLicense? {
            return runBlocking(Dispatchers.IO) {
                instance?.licenseDao?.findLicense(
                    instance?.appDao?.findApp(appId)?.packageId ?: INVALID_PKG_ID,
                )
            }
        }

        fun getSharedPkg(): SteamLicense? {
            return runBlocking(Dispatchers.IO) {
                instance?.licenseDao?.findLicense(0)
            }
        }

        /**
         * Depot IDs the user's license actually grants for [appId].
         * Returns null when unknown (license not cached yet) so callers
         * can fall back to the old behaviour instead of blocking everything.
         */
        fun getLicensedDepotIds(appId: Int): Set<Int>? {
            val ids = getPkgInfoOf(appId)?.depotIds ?: return null
            val directDepotIds = ids.takeIf { it.isNotEmpty() }?.toSet() ?: emptySet()
            val sharedDepotIds = getSharedPkg()?.depotIds?.takeIf { it.isNotEmpty() }?.toSet() ?: emptySet()
            return (directDepotIds + sharedDepotIds).takeIf { it.isNotEmpty() }
        }

        /**
         * Batch-load licensed depot IDs for many apps in a single DB query.
         * Returns appId → depotIds; missing entries mean license unknown (fall back to unfiltered).
         */
        fun buildLicensedDepotMap(apps: List<SteamApp>): Map<Int, Set<Int>> {
            val pkgIds = apps.map { it.packageId }.filter { it != INVALID_PKG_ID }.distinct()
            val licenses = runBlocking(Dispatchers.IO) {
                instance?.licenseDao?.findLicenses(pkgIds) ?: emptyList()
            }
            val pkgToDepots = licenses.associate { it.packageId to it.depotIds.toSet() }
            return apps.mapNotNull { app ->
                val depots = pkgToDepots[app.packageId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                app.id to depots
            }.toMap()
        }

        fun getAppInfoOf(appId: Int): SteamApp? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findApp(appId) }
        }

        fun getDownloadingAppInfoOf(appId: Int): DownloadingAppInfo? {
            return runBlocking(Dispatchers.IO) { instance?.downloadingAppInfoDao?.getDownloadingApp(appId) }
        }

        fun getDownloadableDlcAppsOf(appId: Int): List<SteamApp>? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findDownloadableDLCApps(appId) }
        }

        /**
         * Java-friendly accessor for the AppIDs of every DLC the current
         * user owns for [appId]. Combines the visible (depot-bearing) and
         * hidden DLC sets returned by [SteamAppDao]; both are licence-gated
         * so the result is "DLCs this account can legally use", regardless
         * of whether they're installed on disk.
         *
         * Returns an empty array (never null) if no DLCs are owned or the
         * service isn't ready -- safe to call from any launch path without
         * a null-check on the Java side.
         */
        @JvmStatic
        fun getOwnedDlcAppIdsOf(appId: Int): IntArray {
            val visible = getDownloadableDlcAppsOf(appId).orEmpty()
            val hidden  = getHiddenDlcAppsOf(appId).orEmpty()
            if (visible.isEmpty() && hidden.isEmpty()) return IntArray(0)
            val ids = LinkedHashSet<Int>(visible.size + hidden.size)
            visible.forEach { ids.add(it.id) }
            hidden.forEach { ids.add(it.id) }
            ids.remove(appId)
            return ids.toIntArray()
        }

        fun getHiddenDlcAppsOf(appId: Int): List<SteamApp>? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findHiddenDLCApps(appId) }
        }

        fun getInstalledApp(appId: Int): AppInfo? {
            return runBlocking(Dispatchers.IO) { instance?.appInfoDao?.getInstalledApp(appId) }
        }

        fun getAllInstalledApps(): List<AppInfo>? {
            return runBlocking(Dispatchers.IO) { instance?.appInfoDao?.getAll() }
        }

        fun findSteamAppWithAppIds(appIds: List<Int>): List<SteamApp>? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findSteamAppWithAppIds(appIds) }
        }

        fun getImportedAppDirs(): List<String> {
            val dirs = mutableSetOf<String>()
            val installedApps = getAllInstalledApps()
            val importedAppIds = installedApps?.filter { it.isImported }?.map { it.id }
            if (importedAppIds != null) {
                val steamApps = importedAppIds
                    .chunked(900)
                    .flatMap { ids -> findSteamAppWithAppIds(ids).orEmpty() }
                steamApps?.forEach { steamApp ->
                    dirs += getAppDirName(steamApp)
                }
            }
            return dirs.toList()
        }

        fun findSteamAppWithInstallDir(dirName: String): List<SteamApp>? {
            return runBlocking(Dispatchers.IO) { instance?.appDao?.findSteamAppWithInstallDir(dirName) }
        }

        fun getInstalledDepotsOf(appId: Int): List<Int>? {
            return getInstalledApp(appId)?.downloadedDepots
        }

        fun getInstalledDlcDepotsOf(appId: Int): List<Int>? {
            return getInstalledApp(appId)?.dlcDepots
        }

        fun getAppDownloadInfo(appId: Int): DownloadInfo? {
            return downloadJobs[appId]
        }

        fun setAppDownloadInfo(appId: Int, info: DownloadInfo) {
            downloadJobs[appId] = info
        }

        fun getActiveDownloads(): Map<Int, DownloadInfo> = HashMap(downloadJobs)

        suspend fun getPartialDownloads(): List<Int> {
            return instance?.downloadingAppInfoDao?.getAll()
                ?.map { it.appId }
                ?.filter { appId -> !downloadJobs.containsKey(appId) }
                ?: emptyList()
        }

        fun isAppInstalled(appId: Int): Boolean {
            return MarkerUtils.hasMarker(getAppDirPath(appId), Marker.DOWNLOAD_COMPLETE_MARKER)
        }

        fun getAppDlc(appId: Int): Map<Int, DepotInfo> {
            return getAppInfoOf(appId)?.let {
                it.depots.filter { it.value.dlcAppId != INVALID_APP_ID }
            }.orEmpty()
        }

        suspend fun isAppInLibrary(appId: Int): Boolean =
            instance?.licenseDao?.getAllLicenses()?.any { appId in it.appIds } == true

        suspend fun requestFreeLicense(appId: Int): Boolean = withContext(Dispatchers.IO) {
            val steamApps = instance?._steamApps ?: return@withContext false
            try {
                val callback = steamApps.requestFreeLicense(appId).toFuture().await()
                Timber.i(
                    "requestFreeLicense($appId) -> ${callback.result}, " +
                        "apps=${callback.grantedApps}, packages=${callback.grantedPackages}",
                )
                callback.result == EResult.OK && appId in callback.grantedApps
            } catch (e: Exception) {
                Timber.e(e, "requestFreeLicense($appId) failed")
                false
            }
        }

        suspend fun getOwnedAppDlc(appId: Int): Map<Int, DepotInfo> {
            val client = instance?.steamClient ?: return emptyMap()
            val accountId = client.steamID?.accountID?.toInt() ?: return emptyMap()
            val ownedGameIds = getOwnedGames(userSteamId!!.convertToUInt64()).map { it.appId }.toHashSet()

            return getAppDlc(appId).filter { (_, depot) ->
                when {
                    /* Base-game depots always download */
                    depot.dlcAppId == INVALID_APP_ID -> true

                    /* ① licence cache */
                    instance?.licenseDao?.findLicense(depot.dlcAppId) != null -> true

                    /* ② PICS row */
                    instance?.appDao?.findApp(depot.dlcAppId) != null -> true

                    /* ③ owned-games list */
                    depot.dlcAppId in ownedGameIds -> true

                    /* ④ final online / cached call */
                    else -> false
                }
            }.toMap()
        }

        fun getMainAppDlcIdsWithoutProperDepotDlcIds(appId: Int): MutableList<Int> {
            val mainAppDlcIds = mutableListOf<Int>()
            val hiddenDlcAppIds = getHiddenDlcAppsOf(appId).orEmpty().map { it.id }

            val appInfo = getAppInfoOf(appId)
            if (appInfo != null) {
                // for each of the dlcAppId found in main depots, filter the count = 1, add that dlcAppId to dlcAppIds
                val checkingAppDlcIds = appInfo.depots.filter { it.value.dlcAppId != INVALID_APP_ID }.map { it.value.dlcAppId }.distinct()
                checkingAppDlcIds.forEach { checkingDlcId ->
                    val checkMap = appInfo.depots.filter { it.value.dlcAppId == checkingDlcId }
                    if (checkMap.size == 1) {
                        val depotInfo = checkMap[checkMap.keys.first()]!!
                        if (depotInfo.osList.contains(OS.none) &&
                            depotInfo.manifests.isEmpty() &&
                            hiddenDlcAppIds.isNotEmpty() && hiddenDlcAppIds.contains(checkingDlcId)) {
                            mainAppDlcIds.add(checkingDlcId)
                        }
                    }
                }
            }

            return mainAppDlcIds
        }

        /**
         * Refresh the owned games list by querying Steam, diffing with the local DB, and
         * queueing PICS requests for anything new so metadata gets populated.
         *
         * @return number of newly discovered appIds that were scheduled for PICS.
         */
        suspend fun refreshOwnedGamesFromServer(): Int = withContext(Dispatchers.IO) {
            val service = instance ?: return@withContext 0
            val unifiedFriends = service._unifiedFriends ?: return@withContext 0
            val steamId = userSteamId ?: return@withContext 0

            runCatching {
                val ownedGames = unifiedFriends.getOwnedGames(steamId.convertToUInt64())
                val remoteAppIds = ownedGames.map { it.appId }.filter { it > 0 }.toSet()
                if (remoteAppIds.isEmpty()) {
                    return@runCatching 0
                }

                val localAppIds = service.appDao.getAllAppIds().toSet()
                val missingAppIds = remoteAppIds - localAppIds
                if (missingAppIds.isEmpty()) {
                    return@runCatching 0
                }

                missingAppIds
                    .chunked(MAX_PICS_BUFFER)
                    .forEach { chunk ->
                        val requests = chunk.map { PICSRequest(id = it) }
                        service.appPicsChannel.send(requests)
                    }

                missingAppIds.size
            }.onFailure { error ->
                Timber.tag("SteamService").e(error, "Failed to refresh owned games from server")
            }.getOrDefault(0)
        }

        /**
         * Common filter for downloadable depots.
         *
         * [prefer64Bit] and [preferNonDeckWindows] are preference flags:
         * `true` filters OUT the lesser variant (32-bit / Deck-only), while
         * `false` is permissive and lets all architectures or Deck states through.
         * [eligibleDepots] passes both as `false` to skip preference checks
         * when computing the flags themselves.
         */
        fun filterForDownloadableDepots(
            depot: DepotInfo,
            prefer64Bit: Boolean,
            preferNonDeckWindows: Boolean,
            preferredLanguage: String,
            ownedDlc: Map<Int, DepotInfo>?,
            licensedDepotIds: Set<Int>? = null,
            hasSteamUnlockedBranch: Boolean = false,
            dlcAppIdsWithSingleDepots: Set<Int>? = null,
        ): Boolean {
            if (depot.manifests.isEmpty() && depot.encryptedManifests.isNotEmpty() && !hasSteamUnlockedBranch)
                return false
            // 1. Has something to download (0-byte manifests = stale PICS data from interrupted fetch)
            val hasContent = depot.manifests.isNotEmpty() ||
                (hasSteamUnlockedBranch && depot.encryptedManifests.isNotEmpty()) ||
                depot.sharedInstall
            if (!hasContent)
                return false
            // 2. Supported OS
            if (!depot.isWindowsCompatible)
                return false
            // 3. 64-bit or indeterminate
            // Arch selection: allow 64-bit and Unknown always.
            // Allow 32-bit only when no 64-bit depot exists.
            val archOk = when (depot.osArch) {
                OSArch.Arch64, OSArch.Unknown -> true
                OSArch.Arch32 -> !prefer64Bit
                else -> false
            }
            if (!archOk) return false
            // 4. DLC you actually own
            if (depot.dlcAppId != INVALID_APP_ID && ownedDlc != null && !ownedDlc.containsKey(depot.depotId))
                return false
            // 5. Language filter - if depot has language, it must match preferred language
            if (depot.language.isNotEmpty() && depot.language != preferredLanguage) {
                // Note here, this logic is added to resolve A Date with Death - Expansion DLC (depotID: 2696090)
                // the depot is in english language but there is only 1 depot in the dlcApp, we should always include it
                if (depot.dlcAppId != INVALID_APP_ID) {
                    if (dlcAppIdsWithSingleDepots != null && !dlcAppIdsWithSingleDepots.contains(depot.dlcAppId)) {
                        return false
                    }
                } else {
                    return false
                }
            }
            // 6. Package grants this depot — prevents grabbing region depots the user has no license for.
            //    Skip for DLC and systemDefined depots: DLC licensed via own package (check 4), systemDefined always granted.
            if (depot.dlcAppId == INVALID_APP_ID && !depot.systemDefined && licensedDepotIds != null && depot.depotId !in licensedDepotIds)
                return false
            // 7. Prefer non-Steam-Deck depot when both exist (we're on Android, not Deck)
            if (depot.steamDeck && preferNonDeckWindows)
                return false
            // 8. Skip depot if the realm is SteamChina
            if (depot.realm == SteamRealm.SteamChina)
                return false

            return true
        }

        /**
         * Returns all DLC App IDs that have exactly one depot.
         * Used to identify DLCs with a single depot configuration.
         */
        fun getDlcAppIdsWithSingleDepot(depots: Map<Int, DepotInfo>): Set<Int> {
            return depots.values
                .filter { it.dlcAppId != INVALID_APP_ID }
                .groupBy { it.dlcAppId }
                .filterValues { it.size == 1 }
                .keys
        }

        /**
         * Depots eligible for preference-flag computation: delegates to
         * [filterForDownloadableDepots] with both preference flags false
         * so arch and Steam Deck checks become no-ops. This gives us the pool from
         * which to derive those flags without circular dependency.
         */
        fun eligibleDepots(
            depots: Map<Int, DepotInfo>,
            preferredLanguage: String,
            ownedDlc: Map<Int, DepotInfo>?,
            licensedDepotIds: Set<Int>?,
        ): Collection<DepotInfo> {
            val dlcAppIdsWithSingleDepots = getDlcAppIdsWithSingleDepot(depots)
            return depots.values.filter { depot ->
                filterForDownloadableDepots(depot, prefer64Bit = false, preferNonDeckWindows = false, preferredLanguage,
                    ownedDlc, licensedDepotIds,
                    dlcAppIdsWithSingleDepots = dlcAppIdsWithSingleDepots
                )
            }
        }

        /**
         * Two-pass depot resolution: derives preference flags from [eligibleDepots],
         * then applies full filtering including arch and Steam Deck preference.
         */
        fun resolveDownloadableDepots(
            depots: Map<Int, DepotInfo>,
            preferredLanguage: String,
            ownedDlc: Map<Int, DepotInfo>?,
            licensedDepotIds: Set<Int>?,
            hasSteamUnlockedBranch: Boolean = false,
        ): Map<Int, DepotInfo> {
            val dlcAppIdsWithSingleDepots = getDlcAppIdsWithSingleDepot(depots)
            val effectiveLanguage = SteamUtils.effectiveDepotLanguage(
                depots, preferredLanguage, ownedDlc, licensedDepotIds, hasSteamUnlockedBranch,
            )
            val eligible = eligibleDepots(depots, effectiveLanguage, ownedDlc, licensedDepotIds)
            val has64Bit = eligible.any { it.osArch == OSArch.Arch64 }
            val hasNonDeckWin = eligible.any { !it.steamDeck && it.isWindowsCompatible }
            return depots.filter { (_, depot) ->
                filterForDownloadableDepots(depot, has64Bit, hasNonDeckWin, effectiveLanguage,
                    ownedDlc, licensedDepotIds,
                    dlcAppIdsWithSingleDepots = dlcAppIdsWithSingleDepots
                )
            }
        }

        fun getMainAppDepots(appId: Int, containerLanguage: String): Map<Int, DepotInfo> {
            val appInfo = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc = runBlocking { getOwnedAppDlc(appId) }
            val hasSteamUnlockedBranch = runBlocking { getSteamUnlockedBranches(appId).isNotEmpty() }
            val licensedDepots = getLicensedDepotIds(appId).orEmpty().toMutableSet()

            // Use the dlcAppID of the ownedDlc, to find the licensed depotIds from steam_license
            val mainPackageDepotIds = getPkgInfoOf(appId)?.depotIds.orEmpty().toSet()
            val mapDlcDepotIds = mutableMapOf<Int, List<Int>>()
            ownedDlc.forEach { (dlcAppId, info) ->
                val dlcDepotIds = getPkgInfoOf(dlcAppId)?.depotIds.orEmpty()

                // Make sure licensedDepots contains the dlc depots
                licensedDepots.addAll(dlcDepotIds)

                if (mainPackageDepotIds.isEmpty()) return@forEach

                val dlcOnlyDepotIds = dlcDepotIds.filter { it !in mainPackageDepotIds }
                if (dlcOnlyDepotIds.isNotEmpty()) {
                    mapDlcDepotIds[dlcAppId] = dlcOnlyDepotIds
                }
            }

            val baseDepots = resolveDownloadableDepots(appInfo.depots, containerLanguage, ownedDlc, licensedDepots, hasSteamUnlockedBranch)

            // Find in the depots of mainApp, that if any of the depotID is actually belongs to another steam_app entry
            // override the dlcAppId to the corresponding app id
            // It should fix Don't Starve DLC list, and keeping existing DLC logic correct
            // For existing DLC logic, two games checked Halo MCC, Cyberpunk 2077 to have correct data
            val map = mutableMapOf<Int, DepotInfo>()
            baseDepots.forEach { (depotId, info) ->
                val foundDlcAppId = mapDlcDepotIds
                    .filter { it.value.contains(info.depotId) }
                    .keys.firstOrNull()
                map[depotId] = info.copy(dlcAppId = foundDlcAppId ?: info.dlcAppId)
            }

            return map
        }

        /**
         * Get downloadable depots for a given app (default language), including all DLCs
         * @return Map of app ID to depot ID to depot info
         */
        fun getDownloadableDepots(appId: Int): Map<Int, DepotInfo> {
            val preferredLanguage = PrefManager.containerLanguage
            return getDownloadableDepots(appId, preferredLanguage)
        }

        /**
         * Get downloadable depots for a given app (container language), including all DLCs
         * @return Map of app ID to depot ID to depot info
         */
        fun getDownloadableDepots(appId: Int, preferredLanguage: String): Map<Int, DepotInfo> {
            val appInfo = getAppInfoOf(appId) ?: return emptyMap()
            val ownedDlc = runBlocking { getOwnedAppDlc(appId) }
            val hasSteamUnlockedBranch = runBlocking { getSteamUnlockedBranches(appId).isNotEmpty() }
            val licensedDepots = getLicensedDepotIds(appId).orEmpty().toMutableSet()

            val map = getMainAppDepots(appId, preferredLanguage).toMutableMap()

            // parent app's arch applies to DLC arch selection
            val mainLanguage = SteamUtils.effectiveDepotLanguage(
                appInfo.depots, preferredLanguage, ownedDlc, licensedDepots, hasSteamUnlockedBranch,
            )
            val has64Bit = eligibleDepots(appInfo.depots, mainLanguage, ownedDlc, licensedDepots)
                .any { it.osArch == OSArch.Arch64 }

            val indirectDlcApps = getDownloadableDlcAppsOf(appId).orEmpty()
            indirectDlcApps.forEach { dlcApp ->
                val dlcAppIdsWithSingleDepots = getDlcAppIdsWithSingleDepot(dlcApp.depots)
                val dlcLicensedDepots = getLicensedDepotIds(dlcApp.id)
                // Resolve the DLC's own language too, so DLC that omits the container language installs.
                val dlcLanguage = SteamUtils.effectiveDepotLanguage(
                    dlcApp.depots, preferredLanguage, null, dlcLicensedDepots, hasSteamUnlockedBranch,
                )
                val dlcEligible = eligibleDepots(dlcApp.depots, dlcLanguage, null, dlcLicensedDepots)
                val dlcHasNonDeckWin = dlcEligible.any { !it.steamDeck && it.isWindowsCompatible }
                dlcApp.depots
                    .filter { (_, depot) ->
                        filterForDownloadableDepots(depot, has64Bit, dlcHasNonDeckWin, dlcLanguage,
                            null, dlcLicensedDepots, hasSteamUnlockedBranch,
                            dlcAppIdsWithSingleDepots = dlcAppIdsWithSingleDepots
                        )
                    }
                    .forEach { (depotId, depot) ->
                        // Add DLC Depots with custom object
                        map[depotId] = DepotInfo(
                            depotId = depot.depotId,
                            dlcAppId = dlcApp.id, // Set to DLC App ID
                            optionalDlcId = depot.optionalDlcId,
                            depotFromApp = depot.depotFromApp,
                            sharedInstall = depot.sharedInstall,
                            osList = depot.osList,
                            osArch = depot.osArch,
                            language = depot.language,
                            manifests = depot.manifests,
                            encryptedManifests = depot.encryptedManifests,
                            systemDefined = depot.systemDefined,
                            steamDeck = depot.steamDeck,
                        )
                    }
            }

            return map
        }

        fun getAppDirName(app: SteamApp?): String {
            // The folder name, if it got made
            var appName = app?.config?.installDir.orEmpty()
            if (appName.isEmpty()) {
                appName = app?.name.orEmpty()
            }
            return appName
        }

        /**
         * Resolve best matching directory: completed install > partial > null.
         * Extracted for testability — called by [getAppDirPath].
         */
        fun resolveExistingAppDir(installPaths: List<String>, names: List<String>): String? {
            var firstExisting: String? = null
            for (basePath in installPaths) {
                for (name in names) {
                    if (name.isEmpty()) continue
                    val path = Paths.get(basePath, name)
                    if (Files.isDirectory(path)) {
                        if (MarkerUtils.hasMarker(path.pathString, Marker.DOWNLOAD_COMPLETE_MARKER)) {
                            return path.pathString
                        }
                        if (firstExisting == null) firstExisting = path.pathString
                    }
                }
            }
            return firstExisting
        }

        fun getAppDirPath(gameId: Int): String {
            val info = getAppInfoOf(gameId)

            // For installed game, check whether it has customInstallPath and return it
            val appInfo = getInstalledApp(gameId)
            if (appInfo != null && appInfo.isImported) {
                return appInfo.customInstallPath
            }

            val appName = getAppDirName(info)
            val oldName = info?.name.orEmpty()
            val names = if (oldName.isNotEmpty() && oldName != appName) listOf(appName, oldName) else listOf(appName)

            // prefer completed installs over partial/stale directories
            val resolved = resolveExistingAppDir(allInstallPaths, names)
            if (resolved != null) return resolved

            // nothing on disk yet — default to preferred install location
            if (PrefManager.useExternalStorage) {
                return Paths.get(externalAppInstallPath, appName).pathString
            }
            return Paths.get(internalAppInstallPath, appName).pathString
        }

        private fun isExecutable(flags: Any): Boolean = when (flags) {
            // SteamKit-JVM (most forks) – flags is EnumSet<EDepotFileFlag>
            is EnumSet<*> -> {
                flags.contains(EDepotFileFlag.Executable) ||
                    flags.contains(EDepotFileFlag.CustomExecutable)
            }

            // SteamKit-C# protobuf port – flags is UInt / Int / Long
            is Int -> (flags and 0x20) != 0 || (flags and 0x80) != 0
            is Long -> ((flags and 0x20L) != 0L) || ((flags and 0x80L) != 0L)

            else -> false
        }

        /* -------------------------------------------------------------------------- */
        /* 1. Extra patterns & word lists                                             */
        /* -------------------------------------------------------------------------- */

        // Unreal Engine "Shipping" binaries (e.g. Stray-Win64-Shipping.exe)
        private val UE_SHIPPING = Regex(
            """.*-win(32|64)(-shipping)?\.exe$""",
            RegexOption.IGNORE_CASE,
        )

        // UE folder hint …/Binaries/Win32|64/…
        private val UE_BINARIES = Regex(
            """.*/binaries/win(32|64)/.*\.exe$""",
            RegexOption.IGNORE_CASE,
        )

        // Tools / crash-dumpers to push down
        private val NEGATIVE_KEYWORDS = listOf(
            "crash", "handler", "viewer", "compiler", "tool",
            "setup", "unins", "eac", "launcher", "steam",
        )

        /* add near-name helper */
        private fun fuzzyMatch(a: String, b: String): Boolean {
            /* strip digits & punctuation, compare first 5 letters */
            val cleanA = a.replace(Regex("[^a-z]"), "")
            val cleanB = b.replace(Regex("[^a-z]"), "")
            return cleanA.take(5) == cleanB.take(5)
        }

        /* add generic short-name detector: one letter + digits, ≤4 chars  */
        private val GENERIC_NAME = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)

        /* -------------------------------------------------------------------------- */
        /* 2. Heuristic score (same signature!)                                       */
        /* -------------------------------------------------------------------------- */

        private fun scoreExe(
            file: FileData,
            gameName: String,
            hasExeFlag: Boolean,
        ): Int {
            var s = 0
            val path = file.fileName.lowercase()

            // 1️⃣ UE shipping or binaries folder bonus
            if (UE_SHIPPING.matches(path)) s += 300
            if (UE_BINARIES.containsMatchIn(path)) s += 250

            // 2️⃣ root-folder exe bonus
            if (!path.contains('/')) s += 200

            // 3️⃣ filename contains the game / installDir
            if (path.contains(gameName) || fuzzyMatch(path, gameName)) s += 100

            // 4️⃣ obvious tool / crash-dumper penalty
            if (NEGATIVE_KEYWORDS.any { it in path }) s -= 150
            if (GENERIC_NAME.matches(file.fileName)) s -= 200   // ← new

            // 5️⃣ Executable | CustomExecutable flag
            if (hasExeFlag) s += 50

            Timber.i("Score for $path: $s")

            return s
        }

        fun FileData.isStub(): Boolean {
            /* stub detector (same short rules) */
            val generic = Regex("^[a-z]\\d{1,3}\\.exe$", RegexOption.IGNORE_CASE)
            val bad = listOf("launcher", "steam", "crash", "handler", "setup", "unins", "eac")
            val n = fileName.lowercase()
            val stub = generic.matches(n) || bad.any { it in n } || totalSize < 1_000_000
            if (stub) Timber.d("Stub filtered: $fileName  size=$totalSize")
            return stub
        }

        /** select the primary binary */
        fun choosePrimaryExe(
            files: List<FileData>?,
            gameName: String,
        ): FileData? = files?.maxWithOrNull { a, b ->
            val sa = scoreExe(a, gameName, isExecutable(a.flags)) // <- fixed
            val sb = scoreExe(b, gameName, isExecutable(b.flags))

            when {
                sa != sb -> sa - sb                                 // higher score wins
                else -> (a.totalSize - b.totalSize).toInt()     // tie-break on size
            }
        }

        /**
         * Picks the real shipped EXE for a Steam app.
         *
         * ❶ try the dev-supplied launch entry (skip obvious stubs)
         * ❷ else score all manifest-flagged EXEs and keep the best
         * ❸ else fall back to the largest flagged EXE in the biggest depot
         * If everything fails, return the game's install directory.
         */
        fun getInstalledExe(appId: Int): String {
            val appInfo = getAppInfoOf(appId) ?: return ""

            val installDir = appInfo.config.installDir.ifEmpty { appInfo.name }

            val depots = appInfo.depots.values.filter { d ->
                !d.sharedInstall && d.isWindowsCompatible
            }
            Timber.i("Depots considered: $depots")

            /* launch targets (lower-case) */
            val launchTargets = appInfo.config.launch
                .mapNotNull { it.executable.lowercase() }.toSet() ?: emptySet()

            Timber.i("Launch targets from appinfo: $launchTargets")

            /* ---------------------------------------------------------- */
            val flagged = mutableListOf<Pair<FileData, Long>>() // (file, depotSize)
            var largestDepotSize = 0L

            // Use DepotDownloader to fetch manifests
            val steamClient = instance?.steamClient
            val licenses = runBlocking { getLicensesFromDb() }
            if (steamClient == null || licenses.isEmpty()) {
                Timber.w("Cannot fetch manifests: steamClient or licenses not available")
                // Fallback to last resort
                return (
                    getAppInfoOf(appId)?.let { appInfo ->
                        getWindowsLaunchInfos(appId).firstOrNull()
                    }
                    )?.executable ?: ""
            }

            val installedBranch = getInstalledApp(appId)?.branch ?: "public"
            for (depot in depots) {
                val mi = depot.manifests[installedBranch]
                    ?: depot.encryptedManifests[installedBranch]
                    ?: depot.manifests["public"]
                    ?: continue
                if (mi.size > largestDepotSize) largestDepotSize = mi.size

                // Check cache first
                val man = DepotManifest.loadFromFile("${getAppDirPath(appId)}/.DepotDownloader/${depot.depotId}_${mi.gid}.manifest")

                Timber.d("Using manifest for depot ${depot.depotId}  size=${mi.size}")

                /* 1️⃣ exact launch entry that isn't a stub */
                man?.files?.firstOrNull { f ->
                    f.fileName.lowercase() in launchTargets && !f.isStub()
                }?.let {
                    Timber.i("Picked via launch entry: ${it.fileName}")
                    return it.fileName.replace('\\', '/').toString()
                }

                /* collect for later */
                man?.files?.filter { isExecutable(it.flags) || it.fileName.endsWith(".exe", true) }
                    ?.forEach { flagged += it to mi.size }
            }

            Timber.i("Flagged executable candidates: ${flagged.map { it.first.fileName }}")

            /* 2️⃣ scorer (unchanged) */
            choosePrimaryExe(
                flagged
                    .map { it.first }
                    .let { pool ->
                        val noStubs = pool.filterNot { it.isStub() }
                        if (noStubs.isNotEmpty()) noStubs else pool
                    },
                installDir.lowercase(),
            )?.let {
                Timber.i("Picked via scorer: ${it.fileName}")
                return it.fileName.replace('\\', '/')
            }

            /* 3️⃣ fallback: biggest exe from the biggest depot */
            flagged
                .filter { it.second == largestDepotSize }
                .maxByOrNull { it.first.totalSize }
                ?.let {
                    Timber.i("Picked via largest-depot fallback: ${it.first.fileName}")
                    return it.first.fileName.replace('\\', '/').toString()
                }

            /* 4️⃣ last resort */
            Timber.w("No executable found; falling back to install dir")
            return (
                getAppInfoOf(appId)?.let { appInfo ->
                    getWindowsLaunchInfos(appId).firstOrNull()
                }
                )?.executable ?: ""
        }

        /**
         * Resolves the effective launch executable for a Steam game (container config or auto-detected).
         * Returns a non-empty sentinel when [Container.isLaunchRealSteam] or
         * [Container.isLaunchBionicSteam] is true so the launch is not blocked.
         */
        fun getLaunchExecutable(appId: String, container: Container): String {
            if (container.isLaunchRealSteam || container.isLaunchBionicSteam) return "steam"
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            return container.executablePath.ifEmpty { getInstalledExe(gameId) }
        }

        suspend fun deleteApp(appId: Int): Boolean = withContext(Dispatchers.IO) {
            // snapshot path before marker removal (removing the marker changes resolution)
            val appInfo = getInstalledApp(appId)
            val result = if (appInfo?.isImported == true) {
                // For imported game, do cleanup
                // Remove from manual folders list and invalidate cache
                val folderPath = appInfo.customInstallPath
                val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
                manualFolders.remove(folderPath)
                PrefManager.customGameManualFolders = manualFolders
                CustomGameScanner.invalidateCache()

                MarkerUtils.removeMarker(folderPath, Marker.DOWNLOAD_COMPLETE_MARKER)

                true
            } else {
                val appDirPath = getAppDirPath(appId)
                val appDir = File(appDirPath)

                if (appDir.exists()) {
                    MarkerUtils.removeMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                }

                NativeTreeDelete.deleteTreeFast(File(appDirPath))
            }

            // Remove from DB
            workshopPausedApps.remove(appId)
            with(instance!!) {
                db.withTransaction {
                    appInfoDao.deleteApp(appId)
                    changeNumbersDao.deleteByAppId(appId)
                    fileChangeListsDao.deleteByAppId(appId)
                    steamFileHashCacheDao.deleteByAppId(appId)
                    downloadingAppInfoDao.deleteApp(appId)
                    appDao.clearWorkshopState(appId)

                    val indirectDlcAppIds = getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }
                    indirectDlcAppIds.forEach { dlcAppId ->
                        appInfoDao.deleteApp(dlcAppId)
                        changeNumbersDao.deleteByAppId(dlcAppId)
                        fileChangeListsDao.deleteByAppId(dlcAppId)
                        steamFileHashCacheDao.deleteByAppId(dlcAppId)
                    }
                }
            }

            return@withContext result
        }

        fun downloadApp(appId: Int): DownloadInfo? {
            val currentDownloadInfo = downloadJobs[appId]
            if (currentDownloadInfo != null) {
                val branch = getDownloadingAppInfoOf(appId)?.branch
                    ?: getInstalledApp(appId)?.branch
                    ?: "public"
                return downloadApp(appId, currentDownloadInfo.downloadingAppIds, branch = branch, isUpdateOrVerify = false)
            } else {
                val downloadingAppInfo = getDownloadingAppInfoOf(appId)
                if (downloadingAppInfo != null) {
                    return downloadApp(appId, downloadingAppInfo.dlcAppIds.orEmpty(), branch = downloadingAppInfo.branch, isUpdateOrVerify = false)
                } else {
                    val installedApp = getInstalledApp(appId)
                    val branch = installedApp?.branch ?: "public"
                    val dlcAppIds = getInstalledDlcDepotsOf(appId).orEmpty().toMutableList()

                    getDownloadableDlcAppsOf(appId)?.forEach { dlcApp ->
                        val installedDlcApp = getInstalledApp(dlcApp.id)
                        if (installedDlcApp != null) {
                            dlcAppIds.add(installedDlcApp.id)
                        }
                    }

                    return downloadApp(appId, dlcAppIds, branch = branch, isUpdateOrVerify = true)
                }
            }
        }

        fun downloadApp(appId: Int, dlcAppIds: List<Int>, branch: String = "public", isUpdateOrVerify: Boolean): DownloadInfo? {
            if (!checkWifiOrNotify()) return null
            return getAppInfoOf(appId)?.let { appInfo ->
                val container = ContainerManager(instance!!.applicationContext).getContainerById("STEAM_${appId}")
                val containerLanguage = if (container != null) {
                    container.language
                } else {
                    PrefManager.containerLanguage
                }

                Timber.tag("SteamService").d("downloadApp: downloading app $appId with language $containerLanguage, branch $branch")

                val depots = getDownloadableDepots(appId = appId, preferredLanguage = containerLanguage)
                downloadApp(
                    appId = appId,
                    downloadableDepots = depots,
                    userSelectedDlcAppIds = dlcAppIds,
                    branch = branch,
                    containerLanguage = containerLanguage,
                    isUpdateOrVerify = isUpdateOrVerify)
            }
        }

        fun isImageFsInstalled(context: Context): Boolean {
            return ImageFs.find(context).rootDir.exists()
        }

        fun isImageFsInstallable(context: Context, variant: String): Boolean {
            val imageFs = ImageFs.find(context)
            if (variant.equals(Container.BIONIC)) {
                return File(imageFs.filesDir, "imagefs_bionic.txz").exists() || context.assets.list("")
                    ?.contains("imagefs_bionic.txz") == true
            } else {
                return File(imageFs.filesDir, "imagefs_gamenative.txz").exists() || context.assets.list("")
                    ?.contains("imagefs_gamenative.txz") == true
            }
        }

        fun isSteamInstallable(context: Context): Boolean {
            val imageFs = ImageFs.find(context)
            return File(imageFs.filesDir, "steam.tzst").exists()
        }

        fun isFileInstallable(context: Context, filename: String): Boolean {
            val imageFs = ImageFs.find(context)
            return File(imageFs.filesDir, filename).exists()
        }

        suspend fun fetchFile(
            url: String,
            dest: File,
            onProgress: (Float) -> Unit,
        ) = withContext(Dispatchers.IO) {
            val tmp = File(dest.absolutePath + ".part")
            try {
                val http = SteamUtils.http

                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { rsp ->
                    check(rsp.isSuccessful) { "HTTP ${rsp.code}" }
                    val body = rsp.body ?: error("empty body")
                    val total = body.contentLength()
                    tmp.outputStream().use { out ->
                        body.byteStream().copyTo(out, 8 * 1024) { read ->
                            onProgress(read.toFloat() / total)
                        }
                    }
                    if (total > 0 && tmp.length() != total) {
                        tmp.delete()
                        error("incomplete download")
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

        suspend fun fetchFileWithFallback(
            fileName: String,
            dest: File,
            context: Context,
            onProgress: (Float) -> Unit,
        ) = withContext(Dispatchers.IO) {
            val primaryUrl = "https://downloads.gamenative.app/$fileName"
            val fallbackUrl = "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/$fileName"
            try {
                fetchFile(primaryUrl, dest, onProgress)
            } catch (e: Exception) {
                Timber.w(e, "Primary download failed; retrying with fallback URL")
                try {
                    fetchFile(fallbackUrl, dest, onProgress)
                } catch (e2: Exception) {
                    dest.delete()
                    throw IOException(
                        "Failed to download $fileName. Please check your network connection or try a VPN.",
                        e2,
                    )
                }
            }
        }

        /** copyTo with progress callback */
        private inline fun InputStream.copyTo(
            out: OutputStream,
            bufferSize: Int = DEFAULT_BUFFER_SIZE,
            progress: (Long) -> Unit,
        ) {
            val buf = ByteArray(bufferSize)
            var bytesRead: Int
            var total = 0L
            while (read(buf).also { bytesRead = it } >= 0) {
                if (bytesRead == 0) continue
                out.write(buf, 0, bytesRead)
                total += bytesRead
                progress(total)
            }
        }

        fun downloadImageFs(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            variant: String,
            context: Context,
        ) = parentScope.async {
            Timber.i("imagefs will be downloaded")
            if (variant == Container.BIONIC) {
                val dest = File(instance!!.filesDir, "imagefs_bionic.txz")
                Timber.d("Downloading imagefs_bionic to " + dest.toString())
                fetchFileWithFallback("imagefs_bionic.txz", dest, context, onDownloadProgress)
            } else {
                Timber.d("Downloading imagefs_gamenative to " + File(instance!!.filesDir, "imagefs_gamenative.txz"));
                fetchFileWithFallback(
                    "imagefs_gamenative.txz",
                    File(instance!!.filesDir, "imagefs_gamenative.txz"),
                    context,
                    onDownloadProgress,
                )
            }
        }

        fun downloadImageFsPatches(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
        ) = parentScope.async {
            Timber.i("imagefs will be downloaded")
            val dest = File(instance!!.filesDir, "imagefs_patches_gamenative.tzst")
            Timber.d("Downloading imagefs_patches_gamenative.tzst to " + dest.toString())
            fetchFileWithFallback("imagefs_patches_gamenative.tzst", dest, context, onDownloadProgress)
        }

        fun downloadFile(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
            fileName: String,
        ) = parentScope.async {
            Timber.i("$fileName will be downloaded")
            val dest = File(instance!!.filesDir, fileName)
            Timber.d("Downloading $fileName to " + dest.toString())
            fetchFileWithFallback(fileName, dest, context, onDownloadProgress)
        }

        fun downloadSteam(
            onDownloadProgress: (Float) -> Unit,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            context: Context,
        ) = parentScope.async {
            Timber.i("imagefs will be downloaded")
            val dest = File(instance!!.filesDir, "steam.tzst")
            Timber.d("Downloading steam.tzst to " + dest.toString())
            fetchFileWithFallback("steam.tzst", dest, context, onDownloadProgress)
        }

        private fun selectSteamControllerConfig(
            details: List<SteamControllerConfigDetail>,
        ): SteamControllerConfigDetail? {
            if (details.isEmpty()) return null

            val branchPriority = listOf("default", "public")
            val controllerPriority = listOf(
                "controller_xbox360",
                "controller_xboxone",
                "controller_steamcontroller_gordon",
            )

            for (branch in branchPriority) {
                for (controllerType in controllerPriority) {
                    val match = details.firstOrNull { detail ->
                        detail.controllerType.equals(controllerType, ignoreCase = true) &&
                            detail.enabledBranches.any { it.equals(branch, ignoreCase = true) }
                    }
                    if (match != null) return match
                }
            }

            return null
        }

        private fun resolveSteamInputManifestFile(
            appId: Int,
            appDirPath: String,
        ): File? {
            val manifestPath = getAppInfoOf(appId)
                ?.config
                ?.steamInputManifestPath
                ?.trim()
                .orEmpty()
            if (manifestPath.isEmpty()) return null

            return FileUtils.findFileCaseInsensitive(File(appDirPath), manifestPath)
        }

        private fun loadConfigFromManifest(
            manifestFile: File,
        ): String? {
            if (!manifestFile.exists()) return null
            val manifestDirPath = manifestFile.parentFile?.path ?: return null

            val manifestText = manifestFile.readText(Charsets.UTF_8)
            val configText = try {
                parseManifestForConfig(manifestDirPath, manifestText)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse Steam Input manifest config at ${manifestFile.path}")
                return null
            }
            return configText ?: manifestText
        }

        private fun parseManifestForConfig(
            manifestDirPath: String,
            manifestText: String,
        ): String? {
            return try {
                val kv = KeyValue.loadFromString(manifestText) ?: return null
                val actionManifest = if (kv.name?.equals("Action Manifest", ignoreCase = true) == true) {
                    kv
                } else {
                    kv["Action Manifest"]
                }
                if (actionManifest === KeyValue.INVALID) {
                    return findSiblingControllerConfig(manifestDirPath)
                }

                val configs = actionManifest["configurations"]
                if (configs === KeyValue.INVALID || configs.children.isEmpty()) {
                    return findSiblingControllerConfig(manifestDirPath)
                        ?: throw IllegalStateException("No configurations found in Action Manifest")
                }

                for (controllerType in PREFERRED_CONTROLLER_TYPES) {
                    val controllerBlock = configs[controllerType]
                    if (controllerBlock === KeyValue.INVALID) continue

                    for (entry in controllerBlock.children) {
                        val pathNode = entry["path"]
                        val configPath = pathNode.asString().orEmpty()
                        if (pathNode === KeyValue.INVALID || configPath.isEmpty()) continue

                        val configFile = FileUtils.findFileCaseInsensitive(File(manifestDirPath), configPath)
                            ?: continue
                        return configFile.readText(Charsets.UTF_8)
                    }
                }

                findSiblingControllerConfig(manifestDirPath)
                    ?: throw IllegalStateException("No valid controller configuration found in Action Manifest")
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse Steam Input manifest config")
                null
            }
        }

        private val PREFERRED_CONTROLLER_TYPES = listOf(
            "controller_xboxone",
            "controller_steamcontroller_gordon",
            "controller_generic",
            "controller_xbox360",
        )

        private fun findSiblingControllerConfig(manifestDirPath: String): String? {
            for (controllerType in PREFERRED_CONTROLLER_TYPES) {
                val configFile = FileUtils.findFileCaseInsensitive(File(manifestDirPath), "$controllerType.vdf")
                    ?: continue
                return configFile.readText(Charsets.UTF_8)
            }
            return null
        }

        private fun readBuiltInSteamInputTemplate(fileName: String): String? {
            val assets = instance?.assets ?: return null
            return runCatching {
                assets.open("steaminput/$fileName").use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }

        private fun readDownloadedSteamInputTemplate(appId: Int): String? {
            val configFile = File(getAppDirPath(appId), STEAM_CONTROLLER_CONFIG_FILENAME)
            if (!configFile.exists()) return null
            return configFile.readText(Charsets.UTF_8)
        }

        fun resolveSteamControllerVdfText(appId: Int): String? {
            val config = getAppInfoOf(appId)?.config ?: return null
            return when (config.steamControllerTemplateIndex) {
                1 -> readDownloadedSteamInputTemplate(appId)
                13 -> {
                    val manifestFile = resolveSteamInputManifestFile(appId, getAppDirPath(appId))
                        ?: return null
                    loadConfigFromManifest(manifestFile)
                }
                2, 12 -> readBuiltInSteamInputTemplate("controller_xboxone_gamepad_fps.vdf")
                6 -> readBuiltInSteamInputTemplate("controller_xboxone_wasd.vdf")
                4, 5 -> readBuiltInSteamInputTemplate("gamepad_joystick.vdf")
                else -> readBuiltInSteamInputTemplate("gamepad+mouse.vdf")
            }
        }

        fun downloadApp(
            appId: Int,
            downloadableDepots: Map<Int, DepotInfo>,
            userSelectedDlcAppIds: List<Int>,
            branch: String,
            containerLanguage: String,
            isUpdateOrVerify: Boolean,
        ): DownloadInfo? {
            val appDirPath = getAppDirPath(appId)

            if (!checkWifiOrNotify()) return null
            // An ACTIVE download keeps its existing DownloadInfo; a stale inactive entry
            // (e.g. an auto-paused/queued download kept for UI visibility) is replaced.
            val existingInfo = downloadJobs[appId]
            if (existingInfo != null) {
                if (existingInfo.isActive()) return existingInfo
                downloadJobs.remove(appId, existingInfo)
            }
            Timber.d("depots is empty? " + downloadableDepots.isEmpty())
            if (downloadableDepots.isEmpty()) return null

            val indirectDlcAppIds = getDownloadableDlcAppsOf(appId).orEmpty().map { it.id }

            val hasDepotContent = { depot: DepotInfo ->
                depot.manifests.isNotEmpty() || depot.encryptedManifests.isNotEmpty()
            }

            // Depots from Main game
            val mainDepots = getMainAppDepots(appId, containerLanguage)
            var mainAppDepots = mainDepots.filter { (_, depot) ->
                depot.dlcAppId == INVALID_APP_ID
            } + mainDepots.filter { (_, depot) ->
                userSelectedDlcAppIds.contains(depot.dlcAppId) && hasDepotContent(depot)
            }

            // Depots from DLC App
            val dlcAppDepots = downloadableDepots.filter { (_, depot) ->
                !mainAppDepots.map { it.key }.contains(depot.depotId) &&
                userSelectedDlcAppIds.contains(depot.dlcAppId) && indirectDlcAppIds.contains(depot.dlcAppId) && hasDepotContent(depot)
            }

            // Remove depots that are already downloaded (not for update/verify)
            val appInfo = getInstalledApp(appId)
            if (appInfo != null && !isUpdateOrVerify) {
                mainAppDepots = mainAppDepots.filter { it.key !in appInfo.downloadedDepots }
            }

            // Combine main app and DLC depots
            val selectedDepots = mainAppDepots + dlcAppDepots

            val downloadingAppIds = CopyOnWriteArrayList<Int>()
            val calculatedDlcAppIds = CopyOnWriteArrayList<Int>()

            userSelectedDlcAppIds.forEach { dlcAppId ->
                if (dlcAppDepots.filter { (_, depot) -> depot.dlcAppId == dlcAppId }.isNotEmpty()) {
                    downloadingAppIds.add(dlcAppId)
                    calculatedDlcAppIds.add(dlcAppId)
                }
            }

            // Add main app ID if there are main app depots
            if (mainAppDepots.isNotEmpty()) {
                downloadingAppIds.add(appId)
            }

            // There are some apps, the dlc depots does not have dlcAppId in the data, need to set it back
            val mainAppDlcIds = getMainAppDlcIdsWithoutProperDepotDlcIds(appId)

            // If there are no DLC depots, download the main app only
            if (dlcAppDepots.isEmpty()) {
                // Because all dlcIDs are coming from main depots, need to add the dlcID to main app in order to save it to db after finish download
                mainAppDlcIds.addAll(mainAppDepots.filter { it.value.dlcAppId != INVALID_APP_ID }.map { it.value.dlcAppId }.distinct())

                // Refresh id List, so only main app is downloaded
                calculatedDlcAppIds.clear()
                downloadingAppIds.clear()
                downloadingAppIds.add(appId)
            }

            Timber.i("selectedDepots is empty? " + selectedDepots.isEmpty())

            if (selectedDepots.isEmpty()) return null

            Timber.i("Starting download for $appId")
            Timber.i("App contains ${mainAppDepots.size} depot(s): ${mainAppDepots.keys}")
            Timber.i("DLC contains ${dlcAppDepots.size} depot(s): ${dlcAppDepots.keys}")
            Timber.i("downloadingAppIds: $downloadingAppIds")

            // Save downloading app info
            runBlocking {
                instance?.downloadingAppInfoDao?.insert(
                    DownloadingAppInfo(
                        appId,
                        dlcAppIds = userSelectedDlcAppIds,
                        branch = branch,
                    ),
                )
            }

            val info = DownloadInfo(selectedDepots.size, appId, downloadingAppIds).also { di ->
                di.setPersistencePath(appDirPath)
                // Weights + total = UNCOMPRESSED depot size (manifest.size): the native engine
                // credits decompressed chunk bytes written, so the progress bar and ETA must be
                // in the same unit (previously getDownloadBytes = compressed → the bar could
                // clamp at 100% before the depot was actually done).
                val sizes = selectedDepots.map { (_, depot) ->
                    val mInfo = depot.manifests[branch]
                        ?: depot.encryptedManifests[branch]
                        ?: return@map 1L
                    mInfo.size.coerceAtLeast(1L)
                }
                sizes.forEachIndexed { i, bytes -> di.setWeight(i, bytes) }

                // Total expected size (used for ETA based on recent download speed)
                val totalBytes = sizes.sum()
                di.setTotalExpectedBytes(totalBytes)

                // Load persisted bytes downloaded value on resume
                val persistedBytes = di.loadPersistedBytesDownloaded(appDirPath)
                if (persistedBytes > 0L) {
                    di.initializeBytesDownloaded(persistedBytes)
                    Timber.i("Resumed download: initialized with $persistedBytes bytes")
                }

                // register BEFORE launching: a fast verify/update on up-to-date data can
                // complete the launch body synchronously (DepotDownloader has no chunks to
                // fetch). removeDownloadJob inside that body must find the entry to emit
                // DownloadStatusChanged(false); otherwise the UI hangs at 0% and the next
                // downloadApp call returns the stale DownloadInfo from the still-populated
                // map (line ~1666 short-circuit).
                downloadJobs[appId] = di

                // Depot keys are fetched one per depot before the first chunk arrives, and
                // nothing in IDownloadListener reports that phase. Seed a status message now
                // so the UI never shows a bare 0% with no explanation; noteDepotKeyResolved
                // refines it into a running count as keys resolve. Register before launching,
                // since keys start arriving almost immediately.
                beginDepotKeyPrep(appId, selectedDepots.keys, di)

                notifyDownloadStarted(appId)
                instance?.notifierOrNull?.trackDownload(di, getAppInfoOf(appId)?.name.orEmpty(), NotificationHelper.NOTIFICATION_ID_STEAM)

                val chunkStagingRedirectDir = File(DownloadService.baseCacheDirPath, "depot_chunks/$appId")
                    .takeIf { !appDirPath.startsWith(DownloadService.baseDataDirPath) }

                // Register with centralized queue and auto-pause other downloads
                GameDownloadService.registerDownload(
                    gameSource = GameSource.STEAM,
                    gameId = appId.toString(),
                    downloadInfo = di
                )

                val downloadJob = instance!!.scope.launch {
                    try {
                        if (isUpdateOrVerify) {
                            SteamUtils.clearStaleDrmBackups(appDirPath)
                        }

                        // Get licenses from database
                        val licenses = getLicensesFromDb()
                        if (licenses.isEmpty()) {
                            Timber.w("No licenses available for download")
                            // Free the queue slot so a queued download isn't stranded
                            GameDownloadService.unregisterDownload(GameSource.STEAM, appId.toString())
                            return@launch
                        }

                        // All Steam bytes are moved by the Rust engine in libgndownload.so via
                        // GameDownloadService; JavaSteam stays the CM client (keys/codes/servers).
                        val speedConfig = DownloadSpeedConfig()
                        Timber.i("CPU Cores: ${speedConfig.cpuCores}")
                        Timber.i("maxDownloads: ${speedConfig.maxDownloads}")
                        Timber.i("maxDecompress: ${speedConfig.maxDecompress}")

                        chunkStagingRedirectDir?.apply {
                            NativeTreeDelete.deleteTreeFast(this)
                            mkdirs()
                        }

                        val branchPassword = instance?.steamUnlockedBranchDao
                            ?.getSteamUnlockedBranches(appId)
                            ?.firstOrNull { it.branchName == branch }
                            ?.password

                        val depotIdToIndex = selectedDepots.keys
                            .mapIndexed { index, depotId -> depotId to index }
                            .toMap()

                        Timber.i("Downloading game to " + defaultAppInstallPath)

                        try {
                            GameDownloadService.downloadSteamApp(
                                appId = appId,
                                selectedDepots = selectedDepots,
                                branch = branch,
                                branchPassword = branchPassword,
                                installDir = getAppDirPath(appId),
                                isUpdateOrVerify = isUpdateOrVerify,
                                depotIdToIndex = depotIdToIndex,
                                downloadInfo = di,
                                // Adaptive-window ceiling (ramps up only while the link delivers);
                                // process pool stays core-scaled.
                                maxWorkers = speedConfig.maxDownloads,
                                processWorkers = speedConfig.maxDecompress,
                                parentScope = this,
                            )
                        } catch (e: GameDownloadService.DownloadFailedException) {
                            Timber.e(e, "App $appId failed to download")
                            di.failedToDownload()
                            // Remove the downloading app info
                            runBlocking {
                                instance?.downloadingAppInfoDao?.deleteApp(di.gameId)
                            }
                            removeDownloadJob(di.gameId)
                            instance?.let { service ->
                                SnackbarManager.show(service.getString(R.string.download_failed_try_again))
                            }
                            return@launch
                        }

                        // Transfer is complete — free the queue slot BEFORE post-install
                        // work (controller config, markers, save sync) so the next queued
                        // download can start. Holding the slot through post-install also
                        // lets a newly registered download auto-pause this finished one;
                        // its later auto-resume re-verifies every file (progress shows
                        // the game restarting after reaching 100%).
                        GameDownloadService.unregisterDownload(GameSource.STEAM, appId.toString())

                        val appConfig = getAppInfoOf(appId)?.config
                        if (appConfig?.steamControllerTemplateIndex == 1) {
                            val controllerConfig = appConfig.steamControllerConfigDetails
                                .let { selectSteamControllerConfig(it) }

                            if (controllerConfig != null) {
                                val appDirPath = getAppDirPath(appId)
                                val publishedFileId = controllerConfig.publishedFileId

                                runCatching {
                                    // Build POST request to Steam GetPublishedFileDetails API
                                    val requestBody = FormBody.Builder()
                                        .add("itemcount", "1")
                                        .add("publishedfileids[0]", publishedFileId.toString())
                                        .build()

                                    val request = Request.Builder()
                                        .url(
                                            "https://api.steampowered.com/" +
                                                "ISteamRemoteStorage/GetPublishedFileDetails/v1"
                                        )
                                        .post(requestBody)
                                        .build()

                                    Net.http.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            Timber.w(
                                                "Failed to get steam controller config details " +
                                                    "for ${publishedFileId}: ${response.code}",
                                            )
                                            return@use
                                        }

                                        val responseBody = response.body?.string()
                                        if (responseBody.isNullOrEmpty()) {
                                            Timber.w(
                                                "Empty response body for steam controller config " +
                                                    publishedFileId,
                                            )
                                            return@use
                                        }

                                        // Parse JSON object response
                                        val responseJson = JSONObject(responseBody)
                                        val responseData = responseJson.optJSONObject("response")
                                        if (responseData == null) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "missing response data",
                                            )
                                            return@use
                                        }

                                        val result = responseData.optInt("result", 0)
                                        val resultCount = responseData.optInt("resultcount", 0)
                                        if (result != 1 || resultCount < 1) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "returned result=$result resultcount=$resultCount",
                                            )
                                            return@use
                                        }

                                        val fileDetails = responseData
                                            .optJSONArray("publishedfiledetails")
                                            ?.optJSONObject(0)
                                        if (fileDetails == null) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "missing publishedfiledetails",
                                            )
                                            return@use
                                        }

                                        val fileUrl = fileDetails.optString("file_url", "").trim()

                                        if (fileUrl.isEmpty()) {
                                            Timber.w(
                                                "Steam controller config ${publishedFileId} " +
                                                    "missing fileUrl",
                                            )
                                            return@use
                                        }

                                        val configFile = File(appDirPath, STEAM_CONTROLLER_CONFIG_FILENAME)

                                        // Download the file
                                        val downloadRequest = Request.Builder()
                                            .url(fileUrl)
                                            .get()
                                            .build()

                                        Net.http.newCall(downloadRequest).execute().use { downloadResponse ->
                                            if (!downloadResponse.isSuccessful) {
                                                Timber.w(
                                                    "Failed to download steam controller config " +
                                                        "${publishedFileId}: ${downloadResponse.code}",
                                                )
                                                return@use
                                            }

                                            val downloadBody = downloadResponse.body
                                            if (downloadBody == null) {
                                                Timber.w(
                                                    "Empty body for steam controller config " +
                                                        publishedFileId,
                                                )
                                                return@use
                                            }

                                            configFile.outputStream().use { output ->
                                                downloadBody.byteStream().use { input ->
                                                    input.copyTo(output)
                                                }
                                            }

                                            Timber.i(
                                                "Downloaded steam controller config " +
                                                    "${publishedFileId} to ${configFile.path}",
                                            )
                                        }
                                    }
                                }.onFailure { error ->
                                    Timber.w(
                                        error,
                                        "Steam controller config download failed for " +
                                            publishedFileId,
                                    )
                                }
                            }
                        }

                        // Complete app download
                        if (mainAppDepots.isNotEmpty()) {
                            val mainAppDepotIds = mainAppDepots.keys.sorted()
                            completeAppDownload(
                                downloadInfo = di,
                                downloadingAppId = appId,
                                entitledDepotIds = mainAppDepotIds,
                                selectedDlcAppIds = mainAppDlcIds,
                                appDirPath = appDirPath,
                                branch = branch,
                                parentScope = this,
                            )
                        }

                        // Complete dlc app download
                        calculatedDlcAppIds.forEach { dlcAppId ->
                            val dlcAppDepotIds = getAppInfoOf(dlcAppId)?.depots?.keys.orEmpty()
                            val dlcDepots = selectedDepots.filter { (depotId, depot) ->
                                depot.dlcAppId == dlcAppId &&
                                    (depotId !in mainAppDepots || depotId in dlcAppDepotIds)
                            }
                            val dlcDepotIds = dlcDepots.keys.sorted()
                            completeAppDownload(
                                downloadInfo = di,
                                downloadingAppId = dlcAppId,
                                entitledDepotIds = dlcDepotIds,
                                selectedDlcAppIds = emptyList(),
                                appDirPath = appDirPath,
                                branch = branch,
                                parentScope = this,
                            )
                        }

                        // Remove the job here — Play button becomes visible after this.
                        // clearQueuedState: a completed download must not survive as a
                        // queued/paused entry if a stray auto-pause landed in a
                        // post-install race window (removeDownloadJob keeps
                        // wasAutoPaused entries).
                        di.clearQueuedState()
                        removeDownloadJob(appId)
                        PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(appId, GameSource.STEAM))

                        // Remove the downloading app info
                        instance?.downloadingAppInfoDao?.deleteApp(appId)
                    } catch (e: CancellationException) {
                        Timber.d(e, "Download canceled for app $appId")
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Download failed for app $appId")
                        di.persistProgressSnapshot()
                        if (GameDownloadService.reportFailure(GameSource.STEAM, appId.toString(), e.message)) {
                            // Transient failure: the queue holds the slot and
                            // auto-retries with backoff. The retry marker set
                            // wasAutoPaused, so removeDownloadJob keeps the entry
                            // and the UI shows the download as Queued.
                            removeDownloadJob(appId)
                        } else {
                            // Mark all depots as failed
                            selectedDepots.keys.sorted().forEachIndexed { idx, _ ->
                                di.setWeight(idx, 0)
                                di.setProgress(1f, idx)
                            }
                            removeDownloadJob(appId)
                            // Unregister from queue so a paused download can resume
                            GameDownloadService.unregisterDownload(GameSource.STEAM, appId.toString())
                        }
                    }
                }
                downloadJob.invokeOnCompletion { throwable ->
                    // safety net for paths the inline removeDownloadJob doesn't cover:
                    // early `return@launch` on empty licenses, exceptions before the catch
                    // handlers, and cancellations thrown out of suspension points.
                    // second call is a no-op if the inline path already removed the entry.
                    removeDownloadJob(appId)
                    chunkStagingRedirectDir?.let { NativeTreeDelete.deleteTreeFast(it) }
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        Timber.d(throwable, "Download canceled for app $appId")
                    }
                }
                di.setDownloadJob(downloadJob)
            }

            return info
        }

        // parentScope is intentionally the download job's own CoroutineScope: cancelling the
        // download (e.g. user taps Cancel) also cancels the post-install cloud save sync.
        private suspend fun completeAppDownload(
            downloadInfo: DownloadInfo,
            downloadingAppId: Int,
            entitledDepotIds: List<Int>,
            selectedDlcAppIds: List<Int>,
            appDirPath: String,
            branch: String = "public",
            parentScope: CoroutineScope,
        ) {
            Timber.i("Item $downloadingAppId download completed, saving database")

            // Update database
            val appInfo = instance?.appInfoDao?.getInstalledApp(downloadingAppId)

            // Update Saved AppInfo
            if (appInfo != null) {
                val updatedDownloadedDepots = (appInfo.downloadedDepots + entitledDepotIds).distinct()
                val updatedDlcDepots = (appInfo.dlcDepots + selectedDlcAppIds).distinct()

                instance?.appInfoDao?.update(
                    appInfo.copy(
                        isDownloaded = true,
                        downloadedDepots = updatedDownloadedDepots.sorted(),
                        dlcDepots = updatedDlcDepots.sorted(),
                        branch = branch,
                    ),
                )
            } else {
                instance?.appInfoDao?.insert(
                    AppInfo(
                        downloadingAppId,
                        isDownloaded = true,
                        downloadedDepots = entitledDepotIds.sorted(),
                        dlcDepots = selectedDlcAppIds.sorted(),
                        branch = branch,
                    ),
                )
            }

            // Remove completed appId from downloadInfo.dlcAppIds
            downloadInfo.downloadingAppIds.removeIf { it == downloadingAppId }

            // All downloading appIds are removed
            if (downloadInfo.downloadingAppIds.isEmpty()) {
                // Handle completion: add markers
                withContext(Dispatchers.IO) {
                    MarkerUtils.addMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(appDirPath, Marker.STEAM_DLL_REPLACED)
                    MarkerUtils.removeMarker(appDirPath, Marker.STEAM_COLDCLIENT_USED)
                }

                // clean up DB record BEFORE notifying UI to avoid stale "Resume" button
                instance?.downloadingAppInfoDao?.deleteApp(downloadInfo.gameId)

                // Clear persisted bytes now — depot install is committed. Post-install sync is
                // best-effort and may be cancelled, so this must not be deferred past the sync block.
                downloadInfo.clearPersistedBytesDownloaded(appDirPath)

                // Download cloud saves so they're ready before first launch.
                // Uses the container's own path directly — no activation of the shared xuser
                // symlink needed, so this is safe to run concurrently with any other game session.
                instance?.let { svc ->
                    val appId = downloadInfo.gameId
                    val steamId = userSteamId
                    val containerId = "${GameSource.STEAM.name}_$appId"
                    // Skip post-install sync for utility apps (e.g., Lossless Scaling)
                    val isUtilityApp = appId == LsfgVkManager.LOSSLESS_SCALING_APP_ID
                    if (!isUtilityApp) {
                        if (steamId != null && !ContainerUtils.isLocalSavesOnly(svc.applicationContext, containerId)) {
                            downloadInfo.setPostInstallSyncing(true)
                            downloadInfo.updateStatusMessage("Syncing saves...")
                            PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(appId, true))
                            try {
                                val container = ContainerUtils.getOrCreateContainer(svc.applicationContext, containerId)
                                val prefixToPath: (String) -> String = { prefix ->
                                    PathType.from(prefix).toAbsPath(container, appId, steamId.accountID)
                                }
                                val postSyncInfo = forceSyncUserFiles(
                                    appId = appId,
                                    prefixToPath = prefixToPath,
                                    preferredSave = SaveLocation.Remote,
                                    parentScope = parentScope,
                                ).await()
                                if (postSyncInfo.syncResult !in setOf(SyncResult.Success, SyncResult.UpToDate)) {
                                    Timber.w("[PostInstallSync] Cloud save sync finished with ${postSyncInfo.syncResult} for app $appId")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "[PostInstallSync] Cloud save sync failed for app $appId")
                            } finally {
                                downloadInfo.setPostInstallSyncing(false)
                                downloadInfo.updateStatusMessage(null)
                                PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(appId, false))
                            }
                        }
                    } else {
                        Timber.d("Skipped container creation Lossless Scaling")
                        PluviaApp.events.emit(AndroidEvent.PostInstallSyncStatusChanged(appId, false))
                    }
                }
            }
        }


        fun getWindowsLaunchInfos(appId: Int): List<LaunchInfo> {
            return getAppInfoOf(appId)?.let { appInfo ->
                appInfo.config.launch.filter { launchInfo ->
                    // since configOS was unreliable and configArch was even more unreliable
                    launchInfo.executable.endsWith(".exe", ignoreCase = true)
                }
            }.orEmpty()
        }

        suspend fun notifyRunningProcesses(vararg gameProcesses: GameProcessInfo) = withContext(Dispatchers.IO) {
            instance?.let { steamInstance ->
                if (isConnected) {
                    val gamesPlayed = gameProcesses.mapNotNull { gameProcess ->
                        getAppInfoOf(gameProcess.appId)?.let { appInfo ->
                            getPkgInfoOf(gameProcess.appId)?.let { pkgInfo ->
                                appInfo.branches[gameProcess.branch]?.let { branch ->
                                    val processId = gameProcess.processes
                                        .firstOrNull { it.parentIsSteam }
                                        ?.processId
                                        ?: gameProcess.processes.firstOrNull()?.processId
                                        ?: 0

                                    val userAccountId = userSteamId!!.accountID.toInt()
                                    val preferredLender = instance?.preferredLenderByAppId?.get(gameProcess.appId)
                                        ?: PrefManager.preferredFamilyLenders[gameProcess.appId]
                                    val preferredAccountId = preferredLender?.let { SteamID(it).accountID.toInt() }
                                    val ownerId = when {
                                        preferredAccountId != null &&
                                            pkgInfo.ownerAccountId.contains(preferredAccountId) -> preferredAccountId
                                        pkgInfo.ownerAccountId.contains(userAccountId) -> userAccountId
                                        pkgInfo.ownerAccountId.isNotEmpty() -> pkgInfo.ownerAccountId.first()
                                        else -> userAccountId
                                    }
                                    GamePlayedInfo(
                                        gameId = gameProcess.appId.toLong(),
                                        processId = processId,
                                        ownerId = ownerId,
                                        // TODO: figure out what this is and un-hardcode
                                        launchSource = 100,
                                        gameBuildId = branch.buildId.toInt(),
                                        processIdList = gameProcess.processes,
                                    )
                                }
                            }
                        }
                    }

                    Timber.i(
                        "GameProcessInfo:%s",
                        gamesPlayed.joinToString("\n") { game ->
                            """
                        |   processId: ${game.processId}
                        |   gameId: ${game.gameId}
                        |   processes: ${
                                game.processIdList.joinToString("\n") { process ->
                                    """
                                |   processId: ${process.processId}
                                |   processIdParent: ${process.processIdParent}
                                |   parentIsSteam: ${process.parentIsSteam}
                                    """.trimMargin()
                                }
                            }
                            """.trimMargin()
                        },
                    )

                    steamInstance._steamApps?.notifyGamesPlayed(
                        gamesPlayed = gamesPlayed,
                        clientOsType = EOSType.WinUnknown,
                    )
                }
            }
        }

        fun beginLaunchApp(
            appId: Int,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            ignorePendingOperations: Boolean = false,
            preferredSave: SaveLocation = SaveLocation.None,
            prefixToPath: (String) -> String,
            isOffline: Boolean = false,
            onProgress: ((message: String, progress: Float) -> Unit)? = null,
        ): Deferred<PostSyncInfo> = parentScope.async {
            if (isOffline || !isConnected) {
                return@async PostSyncInfo(SyncResult.UpToDate)
            }
            if (!tryAcquireSync(appId)) {
                Timber.w("Cannot launch app when sync already in progress for appId=$appId")
                return@async PostSyncInfo(SyncResult.InProgress)
            }

            try {
                val context = instance?.applicationContext ?: return@async PostSyncInfo(SyncResult.UnknownFail)
                // Migrate GSE Saves to Steam userdata
                SteamUtils.migrateGSESavesToSteamUserdata(context, appId)

                var syncResult = PostSyncInfo(SyncResult.UnknownFail)

                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    try {
                        PrefManager.clientId?.let { clientId ->
                            instance?.let { steamInstance ->
                                getAppInfoOf(appId)?.let { appInfo ->
                                    steamInstance._steamCloud?.let { steamCloud ->
                                        val postSyncInfo = SteamAutoCloud.syncUserFiles(
                                            appInfo = appInfo,
                                            clientId = clientId,
                                            steamInstance = steamInstance,
                                            steamCloud = steamCloud,
                                            preferredSave = preferredSave,
                                            parentScope = parentScope,
                                            prefixToPath = prefixToPath,
                                            onProgress = onProgress,
                                        ).await()

                                        postSyncInfo?.let { info ->
                                            syncResult = info

                                            if (info.syncResult == SyncResult.Success || info.syncResult == SyncResult.UpToDate) {
                                                Timber.i(
                                                    "Signaling app launch:\n\tappId: %d\n\tclientId: %s\n\tosType: %s",
                                                    appId,
                                                    PrefManager.clientId,
                                                    EOSType.WinUnknown,
                                                )

                                                val pendingRemoteOperations = steamCloud.signalAppLaunchIntent(
                                                    appId = appId,
                                                    clientId = clientId,
                                                    machineName = SteamUtils.getMachineName(steamInstance),
                                                    ignorePendingOperations = ignorePendingOperations,
                                                    osType = EOSType.WinUnknown,
                                                ).await()

                                                if (pendingRemoteOperations.isNotEmpty() && !ignorePendingOperations) {
                                                    syncResult = PostSyncInfo(
                                                        syncResult = SyncResult.PendingOperations,
                                                        pendingRemoteOperations = pendingRemoteOperations,
                                                    )
                                                } else if (ignorePendingOperations &&
                                                    pendingRemoteOperations.any {
                                                        it.operation == ECloudPendingRemoteOperation.k_ECloudPendingRemoteOperationAppSessionActive
                                                    }
                                                ) {
                                                    steamInstance._steamUser!!.kickPlayingSession()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        break
                    } catch (e: AsyncJobFailedException) {
                        if (attempt == maxAttempts) {
                            Timber.e(e, "Cloud sync failed after $maxAttempts attempts")
                            syncResult = PostSyncInfo(SyncResult.UnknownFail)
                        } else {
                            Timber.w("Cloud sync attempt $attempt failed (AsyncJobFailedException), retrying...")
                            delay(1000L * attempt)
                        }
                    }
                }

                return@async syncResult
            } finally {
                releaseSync(appId)
            }
        }

        suspend fun forceSyncUserFiles(
            appId: Int,
            prefixToPath: (String) -> String,
            preferredSave: SaveLocation = SaveLocation.None,
            parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
            overrideLocalChangeNumber: Long? = null,
        ): Deferred<PostSyncInfo> = parentScope.async {
            if (!tryAcquireSync(appId)) {
                Timber.w("Cannot force sync when sync already in progress for appId=$appId")
                return@async PostSyncInfo(SyncResult.InProgress)
            }

            try {
                val context = instance?.applicationContext ?: return@async PostSyncInfo(SyncResult.UnknownFail)
                // Migrate GSE Saves to Steam userdata
                SteamUtils.migrateGSESavesToSteamUserdata(context, appId)

                var syncResult = PostSyncInfo(SyncResult.UnknownFail)

                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    try {
                        PrefManager.clientId?.let { clientId ->
                            instance?.let { steamInstance ->
                                getAppInfoOf(appId)?.let { appInfo ->
                                    steamInstance._steamCloud?.let { steamCloud ->
                                        val postSyncInfo = SteamAutoCloud.syncUserFiles(
                                            appInfo = appInfo,
                                            clientId = clientId,
                                            steamInstance = steamInstance,
                                            steamCloud = steamCloud,
                                            preferredSave = preferredSave,
                                            parentScope = parentScope,
                                            prefixToPath = prefixToPath,
                                            overrideLocalChangeNumber = overrideLocalChangeNumber,
                                        ).await()

                                        postSyncInfo?.let { info ->
                                            syncResult = info
                                            Timber.i("Force cloud sync completed for app $appId with result: ${info.syncResult}")
                                        }
                                    }
                                }
                            }
                        }
                        break
                    } catch (e: AsyncJobFailedException) {
                        if (attempt == maxAttempts) {
                            Timber.e(e, "Force cloud sync failed after $maxAttempts attempts")
                        } else {
                            Timber.w("Force cloud sync attempt $attempt failed (AsyncJobFailedException), retrying...")
                            delay(1000L * attempt)
                        }
                    }
                }

                return@async syncResult
            } finally {
                releaseSync(appId)
            }
        }

        suspend fun closeApp(context: Context, appId: Int, isOffline: Boolean, prefixToPath: (String) -> String) = withContext(Dispatchers.IO) {
            async {
                if (isOffline || !isConnected) {
                    instance?.addPendingSyncApp(appId)
                    return@async
                }

                if (!tryAcquireSync(appId)) {
                    Timber.w("Cannot close app when sync already in progress for appId=$appId")
                    return@async
                }

                try {
                    try {
                        syncAchievementsFromGoldberg(context, appId)
                    } catch (e: Exception) {
                        Timber.e(e, "Achievement sync failed for appId=$appId, continuing with cloud save sync")
                    }

                    val maxAttempts = 3
                    for (attempt in 1..maxAttempts) {
                        try {
                            PrefManager.clientId?.let { clientId ->
                                instance?.let { steamInstance ->
                                    getAppInfoOf(appId)?.let { appInfo ->
                                        steamInstance._steamCloud?.let { steamCloud ->
                                            val postSyncInfo = SteamAutoCloud.syncUserFiles(
                                                appInfo = appInfo,
                                                clientId = clientId,
                                                steamInstance = steamInstance,
                                                steamCloud = steamCloud,
                                                parentScope = this,
                                                prefixToPath = prefixToPath,
                                            ).await()

                                            steamCloud.signalAppExitSyncDone(
                                                appId = appId,
                                                clientId = clientId,
                                                uploadsCompleted = postSyncInfo?.uploadsCompleted == true,
                                                uploadsRequired = postSyncInfo?.uploadsRequired == false,
                                            )
                                        }
                                    }
                                }
                            }
                            break
                        } catch (e: AsyncJobFailedException) {
                            if (attempt == maxAttempts) {
                                Timber.e(e, "Close app sync failed after $maxAttempts attempts")
                            } else {
                                Timber.w("Close app sync attempt $attempt failed (AsyncJobFailedException), retrying...")
                                delay(1000L * attempt)
                            }
                        }
                    }
                } finally {
                    releaseSync(appId)
                    instance?.removePendingSyncApp(appId)
                }
            }
        }

        data class FileChanges(
            val filesDeleted: List<UserFileInfo>,
            val filesModified: List<UserFileInfo>,
            val filesCreated: List<UserFileInfo>,
        )

        /**
         * loginusers.vdf writer for the OAuth-style refresh-token flow introduced in 2024.
         *
         * @param steamId64    64-bit SteamID of the logged-in user
         * @param account      AccountName (same as you passed to logOn / poll result)
         * @param refreshToken Long-lived token you get from AuthSession / QR / credentials
         * @param accessToken  Optional – short-lived access token, Steam ignores it if absent
         * @param personaName  What the client shows in the drop-down; defaults to AccountName
         */
        internal fun getLoginUsersVdfOauth(
            steamId64: String,
            account: String,
            refreshToken: String,
            accessToken: String? = null,
            personaName: String = account,
        ): String {
            val epoch = System.currentTimeMillis() / 1_000

            val vdf = buildString {
                appendLine("\"users\"")
                appendLine("{")
                appendLine("    \"$steamId64\"")
                appendLine("    {")
                appendLine("        \"AccountName\"          \"$account\"")
                appendLine("        \"PersonaName\"          \"$personaName\"")
                appendLine("        \"RememberPassword\"     \"1\"")
                appendLine("        \"WantsOfflineMode\"     \"0\"")
                appendLine("        \"SkipOfflineModeWarning\"     \"0\"")
                appendLine("        \"AllowAutoLogin\"       \"1\"")
                appendLine("        \"MostRecent\"           \"1\"")
                appendLine("        \"Timestamp\"            \"$epoch\"")
                appendLine("    }")
                appendLine("}")
            }

            return vdf
        }

        private fun login(
            username: String,
            accessToken: String? = null,
            refreshToken: String? = null,
            password: String? = null,
            rememberSession: Boolean = true,
            twoFactorAuth: String? = null,
            emailAuth: String? = null,
            clientId: Long? = null,
        ) {
            val steamUser = instance!!._steamUser!!

            // Sensitive info, only print in DEBUG build.
//            if (BuildConfig.DEBUG) {
//                Timber.d(
//                    """
//                    Login Information:
//                     Username: $username
//                     AccessToken: $accessToken
//                     RefreshToken: $refreshToken
//                     Password: $password
//                     Remember Session: $rememberSession
//                     TwoFactorAuth: $twoFactorAuth
//                     EmailAuth: $emailAuth
//                    """.trimIndent(),
//                )
//            }

            PrefManager.username = username

            if ((password != null && rememberSession) || refreshToken != null) {
                if (accessToken != null) {
                    PrefManager.accessToken = accessToken
                }

                if (refreshToken != null) {
                    PrefManager.refreshToken = refreshToken
                }

                if (clientId != null) {
                    PrefManager.clientId = clientId
                }
            }

            val event = SteamEvent.LogonStarted(username)
            PluviaApp.events.emit(event)

            steamUser.logOn(
                LogOnDetails(
                    username = SteamUtils.removeSpecialChars(username).trim(),
                    password = password?.let { SteamUtils.removeSpecialChars(it).trim() },
                    shouldRememberPassword = rememberSession,
                    twoFactorCode = twoFactorAuth,
                    authCode = emailAuth,
                    accessToken = refreshToken,
                    loginID = SteamUtils.getUniqueDeviceId(instance!!),
                    machineName = SteamUtils.getMachineName(instance!!),
                    chatMode = ChatMode.NEW_STEAM_CHAT,
                ),
            )
        }

        suspend fun startLoginWithCredentials(
            username: String,
            password: String,
            rememberSession: Boolean,
            authenticator: IAuthenticator,
        ) = withContext(Dispatchers.IO) {
            try {
                Timber.i("Logging in via credentials.")
                instance!!._loginResult = LoginResult.InProgress
                Timber.i("Set login result to InProgress.")
                instance!!.steamClient?.let { steamClient ->
                    val authDetails = AuthSessionDetails().apply {
                        this.username = username.trim()
                        this.password = password // Not trimming as some passwords have leading spaces.
                        this.persistentSession = rememberSession
                        this.authenticator = authenticator
                        this.deviceFriendlyName = SteamUtils.getMachineName(instance!!)
                        this.clientOSType = EOSType.WinUnknown
                    }

                    val event = SteamEvent.LogonStarted(username)
                    PluviaApp.events.emit(event)

                    val authSession = steamClient.authentication.beginAuthSessionViaCredentials(authDetails).await()

                    val pollResult = authSession.pollingWaitForResult().await()

                    if (pollResult.accountName.isEmpty() && pollResult.refreshToken.isEmpty()) {
                        throw Exception("No account name or refresh token received.")
                    }

                    login(
                        clientId = authSession.clientID,
                        username = pollResult.accountName,
                        accessToken = pollResult.accessToken,
                        refreshToken = pollResult.refreshToken,
                        rememberSession = rememberSession,
                    )
                } ?: run {
                    Timber.e("Could not logon: Failed to connect to Steam")

                    val event = SteamEvent.LogonEnded(username, LoginResult.Failed, "No connection to Steam")
                    PluviaApp.events.emit(event)
                }
            } catch (e: Exception) {
                Timber.e(if (e is CancellationException) "Login cancelled or timed out" else "Login failed")

                val message = when (e) {
                    is CancellationException -> null
                    is AuthenticationException -> e.result?.name ?: e.message
                    else -> e.message ?: e.javaClass.name
                }

                val event = SteamEvent.LogonEnded(username, LoginResult.Failed, message)
                PluviaApp.events.emit(event)
            }
        }

        suspend fun startLoginWithQr() = withContext(Dispatchers.IO) {
            try {
                Timber.i("Logging in via QR.")

                val service = instance
                if (service == null) {
                    Timber.e("Could not start QR logon: Service not initialized")
                    val event = SteamEvent.QrAuthEnded(success = false, message = "Service not initialized")
                    PluviaApp.events.emit(event)
                    return@withContext
                }

                service.steamClient?.let { steamClient ->
                    isWaitingForQRAuth = true

                    val authDetails = AuthSessionDetails().apply {
                        this.deviceFriendlyName = SteamUtils.getMachineName(instance!!)
                        this.clientOSType = EOSType.WinUnknown
                        this.persistentSession = true
                    }

                    val authSession = steamClient.authentication.beginAuthSessionViaQR(authDetails).await()

                    // Steam will periodically refresh the challenge url, this callback allows you to draw a new qr code.
                    authSession.challengeUrlChanged = service

                    val qrEvent = SteamEvent.QrChallengeReceived(authSession.challengeUrl)
                    PluviaApp.events.emit(qrEvent)

                    Timber.d("PollingInterval: ${authSession.pollingInterval.toLong()}")

                    var authPollResult: AuthPollResult? = null

                    while (isWaitingForQRAuth && authPollResult == null) {
                        try {
                            authPollResult = authSession.pollAuthSessionStatus().await()
                        } catch (e: Exception) {
                            Timber.e(e, "Poll auth session status error")
                            throw e
                        }

                        // Sensitive info, only print in DEBUG build.
//                        if (BuildConfig.DEBUG && authPollResult != null) {
//                            Timber.d(
//                                "AccessToken: %s\nAccountName: %s\nRefreshToken: %s\nNewGuardData: %s",
//                                authPollResult.accessToken,
//                                authPollResult.accountName,
//                                authPollResult.refreshToken,
//                                authPollResult.newGuardData ?: "No new guard data",
//                            )
//                        }

                        delay(authSession.pollingInterval.toLong())
                    }

                    isWaitingForQRAuth = false

                    val event = SteamEvent.QrAuthEnded(authPollResult != null)
                    PluviaApp.events.emit(event)

                    // there is a chance qr got cancelled and there is no authPollResult
                    if (authPollResult == null) {
                        Timber.e("Got no auth poll result")
                        throw Exception("Got no auth poll result")
                    }

                    login(
                        clientId = authSession.clientID,
                        username = authPollResult.accountName,
                        accessToken = authPollResult.accessToken,
                        refreshToken = authPollResult.refreshToken,
                    )
                } ?: run {
                    Timber.e("Could not start QR logon: Failed to connect to Steam")

                    val event = SteamEvent.QrAuthEnded(success = false, message = "No connection to Steam")
                    PluviaApp.events.emit(event)
                }
            } catch (e: Exception) {
                Timber.e(e, "QR failed")

                val message = when (e) {
                    is CancellationException -> "QR Session timed out"
                    is AuthenticationException -> e.result?.name ?: e.message
                    else -> e.message ?: e.javaClass.name
                }

                val event = SteamEvent.QrAuthEnded(success = false, message = message)
                PluviaApp.events.emit(event)
            }
        }

        fun stopLoginWithQr() {
            Timber.i("Stopping QR polling")

            isWaitingForQRAuth = false
        }

        fun stop() {
            instance?.let { steamInstance ->
                steamInstance.scope.launch {
                    steamInstance.stop()
                }
            }
        }

        fun logOut() {
            CoroutineScope(Dispatchers.Default).launch {
                // isConnected = false

                isLoggingOut = true

                performLogOffDuties(clearCloudSyncState = true)

                val steamUser = instance!!._steamUser!!
                steamUser.logOff()
            }
        }

        private fun clearUserData(clearCloudSyncState: Boolean = false) {
            PrefManager.clearSteamSessionPreferences()
            instance?.clearPendingSync()
            clearDatabase(clearCloudSyncState = clearCloudSyncState)
            SteamCollectionRepository.clear()
        }

        private fun shouldClearUserDataForLoggedOnFailure(result: EResult): Boolean = when (result) {
            EResult.InvalidPassword,
            EResult.IllegalPassword,
            EResult.PasswordUnset,
            EResult.AccountLogonDenied,
            EResult.AccountLogonDeniedNoMail,
            EResult.AccountLogonDeniedVerifiedEmailRequired,
            EResult.AccountLoginDeniedNeedTwoFactor,
            EResult.InvalidLoginAuthCode,
            EResult.ExpiredLoginAuthCode,
            EResult.RequirePasswordReEntry,
            EResult.ParentalControlRestricted,
            EResult.CachedCredentialInvalid,
            EResult.AccessDenied,
            EResult.Expired,
            EResult.Revoked -> true
            else -> false
        }

        fun clearDatabase(clearCloudSyncState: Boolean = false) {
            with(instance!!) {
                scope.launch {
                    db.withTransaction {
                        appDao.deleteAll()
                        if (clearCloudSyncState) {
                            changeNumbersDao.deleteAll()
                            fileChangeListsDao.deleteAll()
                        }
                        licenseDao.deleteAll()
                        encryptedAppTicketDao.deleteAll()
                        downloadingAppInfoDao.deleteAll()
                        steamUnlockedBranchDao.deleteAll()
                    }
                }
            }
        }

        private fun cancelLongLivedSteamJobs() {
            // Cancel previous continuous jobs or else they will continue to run even after logout
            instance?.picsGetProductInfoJob?.cancel()
            instance?.picsChangesCheckerJob?.cancel()
            instance?.friendCheckerJob?.cancel()
            // Stop an in-flight collections fetch so a slow RPC can't repopulate the repo after logout.
            instance?.steamCollectionsJob?.cancel()
        }

        private fun performLogOffDuties(clearCloudSyncState: Boolean = false) {
            val username = PrefManager.username

            clearUserData(clearCloudSyncState = clearCloudSyncState)
            instance?._localPersona?.value = SteamFriend()

            val event = SteamEvent.LoggedOut(username)
            PluviaApp.events.emit(event)

            cancelLongLivedSteamJobs()
        }

        suspend fun getOwnedGames(friendID: Long): List<OwnedGames> = withContext(Dispatchers.IO) {
            instance?._unifiedFriends!!.getOwnedGames(friendID)
        }

        // Add helper to detect if any downloads or cloud sync are in progress
        fun hasActiveOperations(): Boolean {
            val anySyncInProgress = syncInProgressApps.values.any { it.get() }
            return anySyncInProgress || downloadJobs.values.any { it.getProgress() < 1f }
        }

        // Should service auto-stop when idle (backgrounded)?
        var autoStopWhenIdle: Boolean = false

        suspend fun isUpdatePending(
            appId: Int,
            branch: String = "public",
        ): Boolean = withContext(Dispatchers.IO) {
            // Don't try if there's no internet
            if (!isConnected) return@withContext false

            val steamApps = instance?._steamApps ?: return@withContext false

            // ── 1. Fetch the latest app header from Steam (PICS).
            val pics = steamApps.picsGetProductInfo(
                apps = listOf(PICSRequest(id = appId)),
                packages = emptyList(),
            ).await()

            val remoteAppInfo = pics.results
                .firstOrNull()
                ?.apps
                ?.values
                ?.firstOrNull()
                ?: return@withContext false // nothing returned ⇒ treat as up-to-date

            val remoteSteamApp = remoteAppInfo.keyValues.generateSteamApp()
            val localSteamApp = getAppInfoOf(appId) ?: return@withContext true // not cached yet

            // ── 2. Compare manifest IDs of the depots we actually install.
            getDownloadableDepots(appId).keys.any { depotId ->
                val remoteManifest = remoteSteamApp.depots[depotId]?.manifests?.get(branch)
                val localManifest = localSteamApp.depots[depotId]?.manifests?.get(branch)
                // If remote manifest is null, skip this depot (hack for Castle Crashers)
                if (remoteManifest == null) return@any false
                remoteManifest?.gid != localManifest?.gid
            }
        }

        suspend fun checkPrivateBranchPassword(appId: Int, password: String): Map<String, ByteArray> =
            withContext(Dispatchers.IO) {
                val steamApps = instance?._steamApps ?: return@withContext emptyMap()
                try {
                    val callback = steamApps.checkAppBetaPassword(appId, password).await()
                    if (callback.result == EResult.OK) {
                        val dao = instance?.steamUnlockedBranchDao ?: return@withContext callback.betaPasswords
                    for ((branchName, _) in callback.betaPasswords) {
                            dao.insert(SteamUnlockedBranch(appId, branchName, password))
                        }
                        callback.betaPasswords
                    } else {
                        emptyMap()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "checkPrivateBranchPassword failed for app $appId")
                    emptyMap()
                }
            }

        suspend fun getSteamUnlockedBranches(appId: Int): List<SteamUnlockedBranch> =
            withContext(Dispatchers.IO) {
                instance?.steamUnlockedBranchDao?.getSteamUnlockedBranches(appId) ?: emptyList()
            }

        suspend fun checkDlcOwnershipViaPICSBatch(dlcAppIds: Set<Int>): Set<Int> {
            if (dlcAppIds.isEmpty()) return emptySet()

            val steamApps = instance?._steamApps ?: return emptySet()

            try {
                // Step 1: Get access tokens for all DLC appIds at once
                val tokens = steamApps.picsGetAccessTokens(
                    appIds = dlcAppIds.toList(),
                    packageIds = emptyList(),
                ).await()

                Timber.d("Access tokens response:")
                Timber.d("  - Granted tokens: ${tokens.appTokens.keys}")
                Timber.d("  - Denied tokens: ${tokens.appTokensDenied}")

                // Step 2: Filter to only appIds that have tokens (we own them)
                val ownedAppIds = tokens.appTokens.keys.filter { it in dlcAppIds }.toSet()

                Timber.d("Owned appIds (from tokens): $ownedAppIds")

                if (ownedAppIds.isEmpty()) {
                    Timber.w("No owned DLCs found via access tokens")
                    return emptySet()
                }

                // Step 3: Create PICSRequests for all owned appIds
                val picsRequests = ownedAppIds.map { appId ->
                    val token = tokens.appTokens[appId] ?: return@map null
                    PICSRequest(id = appId, accessToken = token)
                }.filterNotNull()

                Timber.d("Created ${picsRequests.size} PICS requests")

                if (picsRequests.isEmpty()) return emptySet()

                // Step 4: Query PICS for all apps at once (batch them)
                // Note: Steam has limits, so you might need to chunk if > 100 apps
                val chunkSize = 100
                val allOwnedAppIds = mutableSetOf<Int>()

                picsRequests.chunked(chunkSize).forEach { chunk ->
                    Timber.d("Querying PICS chunk with ${chunk.size} apps")
                    val callback = steamApps.picsGetProductInfo(
                        apps = chunk,
                        packages = emptyList(),
                    ).await()

                    // Collect all appIds that returned results
                    callback.results.forEach { picsCallback ->
                        val returnedAppIds = picsCallback.apps.keys
                        Timber.d("  PICS result: ${returnedAppIds.size} apps returned")
                        allOwnedAppIds.addAll(picsCallback.apps.keys)
                    }
                }

                Timber.i("Final owned DLC appIds: $allOwnedAppIds")
                Timber.i("Total owned: ${allOwnedAppIds.size} out of ${dlcAppIds.size} checked")

                return allOwnedAppIds
            } catch (e: Exception) {
                Timber.e(e, "Failed to check DLC ownership via PICS batch for ${dlcAppIds.size} appIds")
                return emptySet()
            }
        }

        suspend fun fetchAchievementsForDisplay(appId: Int): List<Achievement>? {
            if (!isConnected) return null
            return try {
                withTimeout(15_000) {
                val steamUser = instance?._steamUser ?: return@withTimeout null
                val userStats = instance?._steamUserStats?.getUserStats(appId, steamUser.steamID!!)?.await() ?: return@withTimeout null
                // Failed fetch (e.g. transient CM error): return null so the caller can retry.
                if (userStats.result != EResult.OK) return@withTimeout null
                val baseIconUrl = SteamUtils.getBaseAchievementIconUrl(appId)
                val appLanguage = SteamUtils.steamLanguageForAppLocale()
                val localized = userStats.getExpandedAchievements(appLanguage)
                // Parse the English schema lazily: only achievements missing a localized name or
                // description need it, so fully-localized games never pay for the extra parse.
                val englishByName by lazy {
                    if (appLanguage == "english") {
                        emptyMap()
                    } else {
                        // A nameless block can't be matched, and its null key would swallow
                        // every lookup.
                        userStats.getExpandedAchievements("english")
                            .associateBy { it.name }
                            .filterKeys { it != null }
                    }
                }
                localized.map { block ->
                    fun english() = englishByName[block.name]
                    Achievement(
                        displayName = block.displayName?.takeIf { it.isNotBlank() }
                            ?: english()?.displayName?.takeIf { it.isNotBlank() }
                            ?: block.name ?: "",
                        name = block.name,
                        isUnlocked = block.isUnlocked,
                        description = block.description?.takeIf { it.isNotBlank() }
                            ?: english()?.description?.takeIf { it.isNotBlank() }
                            ?: "",
                        unlockTimestamp = block.unlockTimestamp,
                        hidden = block.hidden,
                        icon = if (!block.icon.isNullOrEmpty()) "$baseIconUrl${block.icon}" else "",
                        iconGray = if (!block.iconGray.isNullOrEmpty()) "$baseIconUrl${block.iconGray}" else null,
                        progressCurrent = block.progressCurrent,
                        progressMax = block.progressMax,
                    )
                }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w("fetchAchievementsForDisplay timed out for appId=$appId")
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "fetchAchievementsForDisplay failed for appId=$appId")
                null
            }
        }

        suspend fun generateAchievements(appId: Int, configDirectory: String) {
            val steamUser = instance!!._steamUser!!
            val userStats = instance?._steamUserStats!!.getUserStats(appId, steamUser.steamID!!).await()
            val schemaArray = userStats.schema.toByteArray()
            val generator = StatsAchievementsGenerator()
            val result = generator.generateStatsAchievements(schemaArray, userStats, configDirectory)
            cachedAchievements = result.achievements
            cachedAchievementsAppId = appId

            val nameToBlockBit = result.nameToBlockBit
            Timber.d("nameToBlockBit size=${nameToBlockBit.size} for appId=$appId")
            if (nameToBlockBit.isNotEmpty()) {
                val configDir = File(configDirectory)
                if (!configDir.exists()) configDir.mkdirs()
                val mappingJson = JSONObject()
                nameToBlockBit.forEach { (name, pair) ->
                    mappingJson.put(name, JSONArray(listOf(pair.first, pair.second)))
                }
                File(configDir, "achievement_name_to_block.json").writeText(mappingJson.toString(), Charsets.UTF_8)
            }

            // Seed the GSE Saves file with the real earned state from Steam to avoid re-trigger notifications
            val context = instance!!.applicationContext
            val gseDirs = getGseSaveDirs(context, appId)
            seedGseSaveAchievements(gseDirs, result.achievements)
        }

        // Seed the GSE achievements file to ensure that we don't get early unlock triggers (Games such as Brotato do re-triggers on launch).
        // merges results with ones from Steam Servers so we don't overwrite offline achievements.
        private fun seedGseSaveAchievements(dirs: List<File>, achievements: List<app.gamenative.statsgen.Achievement>) {
            if (achievements.isEmpty()) return
            for (dir in dirs) {
                try {
                    dir.mkdirs()
                    val file = File(dir, "achievements.json")
                    // grab existing file or create new if nothing exists.
                    val merged = if (file.exists()) {
                        try {
                            JSONObject(file.readText(Charsets.UTF_8))
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to parse existing GSE achievements.json in ${dir.absolutePath}, starting fresh")
                            JSONObject()
                        }
                    } else {
                        JSONObject()
                    }

                    // Apply achievements earned & timestamp to file where matched & persists local if local is earned & timestamped.
                    for (ach in achievements) {
                        val existing = if (merged.has(ach.name)) merged.getJSONObject(ach.name) else JSONObject()
                        val localEarned = existing.optBoolean("earned", false)
                        val steamEarned = ach.unlocked ?: false
                        val earned = localEarned || steamEarned
                        val localTime = existing.optLong("earned_time", 0L)
                        val steamTime = (ach.unlockTimestamp ?: 0).toLong()
                        val earnedTime = maxOf(localTime, steamTime)
                        existing.put("earned", earned)
                        existing.put("earned_time", earnedTime)
                        merged.put(ach.name, existing)
                    }

                    file.writeText(merged.toString(2), Charsets.UTF_8)
                    Timber.d("Seeded GSE Saves achievements.json in ${dir.absolutePath}")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to seed GSE Saves achievements.json in ${dir.absolutePath}")
                }
            }
        }

        fun getGseSaveDirs(context: Context, appId: Int): List<File> {
            val imageFs = ImageFs.find(context)
            val dirs = mutableListOf<File>()
            dirs.add(File(
                imageFs.rootDir,
                "${ImageFs.WINEPREFIX}/drive_c/users/xuser/AppData/Roaming/GSE Saves/$appId"
            ))
            val accountId = userSteamId?.accountID?.toInt()
                ?: PrefManager.steamUserAccountId.takeIf { it != 0 }
            if (accountId != null) {
                dirs.add(File(
                    imageFs.rootDir,
                    "${ImageFs.WINEPREFIX}/drive_c/Program Files (x86)/Steam/userdata/$accountId/$appId"
                ))
            }
            return dirs
        }

        /**
         * Scans GSE save directories for unlocked achievements and a stats directory.
         * Shared by [syncAchievementsFromGoldberg] and [AchievementWatcher].
         *
         * @return pair of (unlocked achievement names, first stats directory found or null)
         */
        fun collectGseUnlocksAndStats(gseDirs: List<File>): Pair<Set<String>, File?> {
            val unlocked = mutableSetOf<String>()
            var statsDir: File? = null
            for (dir in gseDirs) {
                val achFile = File(dir, "achievements.json")
                if (achFile.exists()) {
                    try {
                        val json = JSONObject(achFile.readText(Charsets.UTF_8))
                        for (name in json.keys()) {
                            val entry = json.optJSONObject(name) ?: continue
                            if (entry.optBoolean("earned", false)) {
                                unlocked.add(name)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to parse achievements.json in ${dir.absolutePath}")
                    }
                }
                val sd = File(dir, "stats")
                if (statsDir == null && sd.isDirectory && (sd.listFiles()?.isNotEmpty() == true)) {
                    statsDir = sd
                }
            }
            return unlocked to statsDir
        }

        suspend fun syncAchievementsFromGoldberg(context: Context, appId: Int) {
            val gseSaveDirs = getGseSaveDirs(context, appId).filter { it.isDirectory }
            if (gseSaveDirs.isEmpty()) {
                Timber.d("No GSE save directory found for appId=$appId")
                return
            }

            val (unlockedNames, gseStatsDir) = collectGseUnlocksAndStats(gseSaveDirs)

            if (unlockedNames.isEmpty() && gseStatsDir == null) {
                Timber.d("No earned achievements or stats found in Goldberg output for appId=$appId")
                return
            }

            val configDirectory = findSteamSettingsDir(context, appId)
            if (configDirectory == null) {
                Timber.w("Could not find steam_settings directory for appId=$appId")
                return
            }

            val hasStats = gseStatsDir != null
            Timber.i("Found ${unlockedNames.size} earned achievements and ${if (hasStats) "stats" else "no stats"} for appId=$appId, syncing to Steam")
            val result = storeAchievementUnlocks(appId, configDirectory, unlockedNames, gseStatsDir ?: gseSaveDirs.first().resolve("stats"))
            result.onSuccess {
                Timber.i("Successfully synced achievements and stats to Steam for appId=$appId")
            }.onFailure { e ->
                Timber.e(e, "Failed to sync achievements and stats to Steam for appId=$appId")
            }
        }

        fun findSteamSettingsDir(context: Context, appId: Int): String? {
            val appDirPath = getAppDirPath(appId)
            val appDirSettings = File(appDirPath, "steam_settings")
            if (File(appDirSettings, "achievement_name_to_block.json").exists()) {
                return appDirSettings.absolutePath
            }

            val container = ContainerUtils.getContainer(context, "STEAM_$appId")
            val coldclientSettings = File(
                container.rootDir,
                ".wine/drive_c/Program Files (x86)/Steam/steam_settings"
            )
            if (File(coldclientSettings, "achievement_name_to_block.json").exists()) {
                return coldclientSettings.absolutePath
            }

            return null
        }

        suspend fun storeAchievementUnlocks(
            appId: Int,
            configDirectory: String,
            unlockedNames: Set<String>,
            gseStatsDir: File
        ): Result<Unit> = runCatching {
            val steamUser = instance!!._steamUser!!
            val userStats = instance?._steamUserStats!!.getUserStats(appId, steamUser.steamID!!).await()
            if (userStats.result != EResult.OK) {
                throw IllegalStateException("getUserStats failed: ${userStats.result}")
            }

            val allStats = mutableMapOf<Int, Int>()

            // Build achievement name-to-block mapping from on-disk file
            val mappingFile = File(configDirectory, "achievement_name_to_block.json")
            if (mappingFile.exists() && unlockedNames.isNotEmpty()) {
                val mappingJson = JSONObject(mappingFile.readText(Charsets.UTF_8))
                val nameToBlockBit = mutableMapOf<String, Pair<Int, Int>>()
                for (key in mappingJson.keys()) {
                    val arr = mappingJson.optJSONArray(key) ?: continue
                    if (arr.length() >= 2) {
                        nameToBlockBit[key] = Pair(arr.getInt(0), arr.getInt(1))
                    }
                }

                // Seed with current achievement bitmasks from server
                for (block in userStats.achievementBlocks ?: emptyList()) {
                    val blockId = (block.achievementId as? Number)?.toInt() ?: continue
                    var bitmask = 0
                    val unlockTimes = block.unlockTime ?: emptyList()
                    for (i in unlockTimes.indices) {
                        val t = unlockTimes[i]
                        if ((t as? Number)?.toLong() != 0L) bitmask = bitmask or (1 shl i)
                    }
                    allStats[blockId] = bitmask
                }

                // Merge in newly unlocked achievements
                for (name in unlockedNames) {
                    val (blockId, bitIndex) = nameToBlockBit[name] ?: continue
                    val current = allStats.getOrDefault(blockId, 0)
                    allStats[blockId] = current or (1 shl bitIndex)
                }
            }

            // Merge GSE stat files using schema from getUserStats for name->id mapping
            if (gseStatsDir.isDirectory) {
                val statNameToId = mutableMapOf<String, Int>()
                try {
                    val parsedSchema = VdfParser().binaryLoads(userStats.schema.toByteArray())
                    for ((_, appData) in parsedSchema) {
                        if (appData !is Map<*, *>) continue
                        val statInfo = (appData as Map<String, Any>)["stats"] as? Map<String, Any> ?: continue
                        for ((statKey, statData) in statInfo) {
                            if (statData !is Map<*, *>) continue
                            val stat = statData as Map<String, Any>
                            val statType = stat["type"]?.toString() ?: continue
                            if (statType == StatType.STAT_TYPE_BITS || statType == StatType.ACHIEVEMENTS) continue
                            val name = stat["name"]?.toString()?.lowercase() ?: continue
                            val id = statKey.toIntOrNull() ?: continue
                            statNameToId[name] = id
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse schema for stat name mapping, appId=$appId")
                }

                if (statNameToId.isNotEmpty()) {
                    for (statFile in gseStatsDir.listFiles() ?: emptyArray()) {
                        if (!statFile.isFile) continue
                        val statId = statNameToId[statFile.name.lowercase()] ?: continue
                        val bytes = statFile.readBytes()
                        if (bytes.size >= 4) {
                            val value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                            allStats[statId] = value
                            Timber.d("Read GSE stat: ${statFile.name} -> statId=$statId, value=$value")
                        }
                    }
                }
            }

            if (allStats.isEmpty()) {
                Timber.d("No stats or achievements to store for appId=$appId")
                return@runCatching
            }

            val statsToStore = allStats.map { (id, value) -> Stats(statId = id, statValue = value) }
            Timber.d("storeUserStats: appId=$appId, crcStats=${userStats.crcStats}, stats=$statsToStore")
            val mySteamId = steamUser.steamID!!
            val callback = instance?._steamUserStats!!.storeUserStats(
                appId, statsToStore, mySteamId, mySteamId, userStats.crcStats
            ).await()
            if (callback.result != EResult.OK) {
                throw IllegalStateException("storeUserStats failed: ${callback.result}")
            }
            if (callback.statsOutOfDate) {
                Timber.w("Stats were out of date on server for appId=$appId")
            }
            if (callback.statsFailedValidation.isNotEmpty()) {
                Timber.w("${callback.statsFailedValidation.size} stats failed validation for appId=$appId")
                callback.statsFailedValidation.forEach { f ->
                    Timber.w("  statId=${f.statId} reverted to ${f.revertedStatValue}")
                }
            }
        }

    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Restore any app IDs that were pending achievement sync before the service was killed
        pendingSyncAppIds.addAll(
            runCatching {
                pendingSyncFile.readLines().mapNotNull { it.trim().toIntOrNull() }
            }.getOrDefault(emptyList())
        )

        // JavaSteam logger CME hot-fix
        runCatching {
            val clazz = Class.forName("in.dragonbra.javasteam.util.log.LogManager")
            val field = clazz.getDeclaredField("LOGGERS").apply { isAccessible = true }
            field.set(
                /* obj = */ null,
                java.util.concurrent.ConcurrentHashMap<Any, Any>(),   // replaces the HashMap
            )
        }

        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        // Register resume listener with GameDownloadService
        GameDownloadService.registerResumeListener(GameSource.STEAM, object : GameDownloadService.ResumeListener {
            override fun onResumeRequested(gameSource: GameSource, gameId: String) {
                val appId = gameId.toIntOrNull() ?: return
                Timber.i("[SteamService] Resume requested for app $appId")
                scope.launch {
                    // The auto-paused job may still be unwinding (snapshot write, finally
                    // blocks); downloadApp() early-returns while downloadJobs still holds
                    // it, so wait for it to finish and clear itself first.
                    val old = downloadJobs[appId]
                    if (old != null && !old.isActive()) {
                        old.awaitCompletion(10_000)
                    }
                    downloadApp(appId)
                }
            }
        })

        // clear stale download records (completed games) but keep interrupted ones (preserves DLC selection)
        scope.launch {
            for (record in downloadingAppInfoDao.getAll()) {
                if (isAppInstalled(record.appId)) {
                    downloadingAppInfoDao.deleteApp(record.appId)
                }
            }
        }

        notificationHelper = NotificationHelper(applicationContext)

        // pause downloads when WiFi/Ethernet connectivity changes
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = checkAndPauseDownloads()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = checkAndPauseDownloads()

            // query ConnectivityManager directly (not NetworkMonitor) to avoid
            // callback ordering race between our two separate registrations.
            // no VPN exclusion needed here — activeNetwork is always fresh
            // (stale-VPN guard is only needed in NetworkMonitor's multi-network tracking)
            private fun hasActiveWifiOrEthernet(): Boolean {
                val activeNet = connectivityManager.activeNetwork ?: return false
                val caps = connectivityManager.getNetworkCapabilities(activeNet) ?: return false
                return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }

            // no transition guard needed — if WiFi already down, downloadJobs is empty (no-op)
            private fun checkAndPauseDownloads() {
                if (PrefManager.downloadOnWifiOnly && !hasActiveWifiOrEthernet()) {
                    for ((appId, info) in downloadJobs.entries.toList()) {
                        Timber.d("Pausing download for $appId — WiFi/Ethernet lost")
                        info.cancel()
                        PluviaApp.events.emit(AndroidEvent.DownloadPausedDueToConnectivity(appId))
                        removeDownloadJob(appId)
                    }
                    notificationHelper.notify(getString(R.string.download_paused_wifi))
                }
            }
        }
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // To view log messages in android logcat properly
        LogManager.addListener(logger)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Start up the notification early to to avoid ForegroundServiceDidNotStartInTimeException
        val notification = notificationHelper.createServiceNotification(NotificationHelper.NOTIFICATION_ID_STEAM, "Running...")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_STEAM, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_STEAM, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_STEAM)
        notificationHelper.showIdle(NotificationHelper.NOTIFICATION_ID_STEAM)

        when (intent?.action) {
            NotificationHelper.ACTION_EXIT -> {
                Timber.d("Exiting app via notification intent")

                val event = AndroidEvent.EndProcess
                PluviaApp.events.emit(event)

                return START_NOT_STICKY
            }
        }

        if (!isRunning) {
            Timber.i("Using server list path: $serverListPath")

            val configuration = SteamConfiguration.create {
                it.withProtocolTypes(PROTOCOL_TYPES)
                it.withCellID(PrefManager.cellId)
                it.withServerListProvider(FileServerListProvider(File(serverListPath)))
                it.withConnectionTimeout(60000L)
                it.withHttpClient(
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .pingInterval(15, TimeUnit.SECONDS) // keep WebSocket alive during idle
                        .build(),
                )
            }

            // create our steam client instance
            steamClient = SteamClient(configuration).apply {
                // remove callbacks we're not using.
                removeHandler(SteamGameServer::class.java)
                removeHandler(SteamMasterServer::class.java)
                removeHandler(SteamWorkshop::class.java)
                removeHandler(SteamScreenshots::class.java)
                // JavaSteam has the protobuf for game invites but no handler for them.
                addHandler(GameInviteHandler())
            }

            // create the callback manager which will route callbacks to function calls
            callbackManager = CallbackManager(steamClient!!)

            // get the different handlers to be used throughout the service
            _steamUser = steamClient!!.getHandler(SteamUser::class.java)
            _steamApps = steamClient!!.getHandler(SteamApps::class.java)
            _steamFriends = steamClient!!.getHandler(SteamFriends::class.java)
            _steamCloud = steamClient!!.getHandler(SteamCloud::class.java)
            _steamUserStats = steamClient!!.getHandler(SteamUserStats::class.java)

            _unifiedFriends = SteamUnifiedFriends(this)
            _steamFamilyGroups = steamClient!!.getHandler<SteamUnifiedMessages>()!!.createService<FamilyGroups>()

            // subscribe to the callbacks we are interested in
            with(callbackSubscriptions) {
                with(callbackManager!!) {
                    add(subscribe(ConnectedCallback::class.java, ::onConnected))
                    add(subscribe(DisconnectedCallback::class.java, ::onDisconnected))
                    add(subscribe(LoggedOnCallback::class.java, ::onLoggedOn))
                    add(subscribe(LoggedOffCallback::class.java, ::onLoggedOff))
                    add(subscribe(PersonaStateCallback::class.java, ::onPersonaStateReceived))
                    add(subscribe(LicenseListCallback::class.java, ::onLicenseList))
                    add(subscribe(PlayingSessionStateCallback::class.java, ::onPlayingSessionState))
                    add(subscribe(DepotKeyCallback::class.java) { noteDepotKeyResolved(it.depotID) })
                    add(subscribe(GameInviteCallback::class.java, ::onGameInvite))
                }
            }

            isRunning = true

            // we should use Dispatchers.IO here since we are running a sleeping/blocking function
            // "The idea is that the IO dispatcher spends a lot of time waiting (IO blocked),
            // while the Default dispatcher is intended for CPU intensive tasks, where there
            // is little or no sleep."
            // source: https://stackoverflow.com/a/59040920
            scope.launch {
                while (isRunning) {
                    // logD("runWaitCallbacks")

                    try {
                        callbackManager!!.runWaitCallbacks(1000L)
                    } catch (e: Exception) {
                        Timber.e("runWaitCallbacks failed: $e")
                    }
                }
            }

            connectToSteam()
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("Foreground service timeout reached, restarting...")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Persist download progress for all active downloads
        // This is a safety net for OS kills (unlikely but possible)
        downloadJobs.values.forEach { downloadInfo ->
            downloadInfo.persistProgressSnapshot()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel()

        connectivityManager.unregisterNetworkCallback(networkCallback)

        // Drop this source's queue entries before removing the listener that resumes them
        GameDownloadService.unregisterAllForSource(GameSource.STEAM)
        // Unregister resume listener from GameDownloadService
        GameDownloadService.unregisterResumeListener(GameSource.STEAM)

        scope.launch { stop() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations() && !(BuildConfig.XR_BUILD && keepAlive)) {
            Timber.i("Task removed and no active work — stopping service")
            stopSelf()
        } else {
            Timber.i("Task removed but active work or keepAlive exists — keeping service alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectToSteam() {
        CoroutineScope(Dispatchers.Default).launch {
            // this call errors out if run on the main thread
            steamClient!!.connect()

            delay(5000)

            if (!isConnected) {
                Timber.w("Failed to connect to Steam, marking endpoint bad and force disconnecting")

                try {
                    steamClient!!.servers.tryMark(steamClient!!.currentEndpoint, PROTOCOL_TYPES, ServerQuality.BAD)
                } catch (e: NullPointerException) {
                    // I don't care
                } catch (e: Exception) {
                    Timber.e(e, "Failed to mark endpoint as bad:")
                }

                try {
                    steamClient!!.disconnect()
                } catch (e: NullPointerException) {
                    // I don't care
                } catch (e: Exception) {
                    Timber.e(e, "There was an issue when disconnecting:")
                }
            }
        }
    }

    private suspend fun stop() {
        Timber.i("Stopping Steam service")
        if (steamClient != null && steamClient!!.isConnected) {
            isStopping = true

            steamClient!!.disconnect()

            while (isStopping) {
                delay(200L)
            }

            // the reason we don't clearValues() here is because the onDisconnect
            // callback does it for us
        } else {
            clearValues()
        }
    }

    private fun clearValues() {
        _loginResult = LoginResult.Failed
        isRunning = false
        isConnected = false
        isLoggingOut = false
        isWaitingForQRAuth = false
        setFamilyGroupId(0L)
        familyGroupMembers.clear()
        familyAppOwnerSteamIds.clear()
        familySharedLibraryReadyForDlcCounts = false
        preferredLenderByAppId.clear()
        familyMemberNames.clear()
        bumpFamilyPreferredCopyDataVersion()

        steamClient = null
        _steamUser = null
        _steamApps = null
        _steamFriends = null
        _steamCloud = null

        callbackSubscriptions.forEach { it.close() }
        callbackSubscriptions.clear()
        callbackManager = null

        _unifiedFriends?.close()
        _unifiedFriends = null

        reconnectJob?.cancel()
        offlineAchievementSyncJob?.cancel()
        offlineAchievementSyncJob = null
        pendingSyncAppIds.clear()
        isStopping = false
        retryAttempt = 0

        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.clearAllListenersOf<SteamEvent<Any>>()

        LogManager.removeListener(logger)
    }

    private fun reconnect() {
        notificationHelper.notify("Retrying...")

        isConnected = false

        if (!_isHandlingConflict.get()) {
            val event = SteamEvent.Disconnected(isTerminal = false)
            PluviaApp.events.emit(event)
        }

        steamClient!!.disconnect()
    }

    // region [REGION] callbacks
    @Suppress("UNUSED_PARAMETER", "unused")
    private fun onConnected(callback: ConnectedCallback) {
        Timber.i("Connected to Steam")

        reconnectJob?.cancel()
        retryAttempt = 0
        isConnected = true

        var isAutoLoggingIn = false

        if (SteamUtils.hasStoredCredentials()) {
            isAutoLoggingIn = true

            login(
                username = PrefManager.username,
                refreshToken = PrefManager.refreshToken,
                rememberSession = true,
            )
        }

        val event = SteamEvent.Connected(isAutoLoggingIn)
        PluviaApp.events.emit(event)
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        Timber.i("Disconnected from Steam. User initiated: ${callback.isUserInitiated}")

        isConnected = false
        offlineAchievementSyncJob?.cancel()
        offlineAchievementSyncJob = null

        if (!isStopping && retryAttempt < MAX_RETRY_ATTEMPTS) {
            retryAttempt++
            val backoffMs = (1000L * minOf(1 shl (retryAttempt - 1), 60)).coerceAtMost(60_000L)

            Timber.w("Attempting to reconnect (retry $retryAttempt) after ${backoffMs}ms")

            if (!_isHandlingConflict.get()) {
                val event = SteamEvent.RemotelyDisconnected
                PluviaApp.events.emit(event)
            }

            reconnectJob = scope.launch {
                delay(backoffMs)
                if (isRunning && !isStopping) connectToSteam()
            }
        } else {
            // only terminal when retries exhausted, not when user/system stopped the service
            val event = SteamEvent.Disconnected(isTerminal = !isStopping)
            PluviaApp.events.emit(event)

            clearValues()

            stopSelf()
        }
    }

    private suspend fun refreshFamilyPreferredCopyData() {
        val familyGroups = _steamFamilyGroups ?: return
        if (familyGroupId == 0L) return

        try {
            // Login refresh keeps includeExcluded=false (playable shared games).
            // Preferred-copy DLC counts re-fetch with includeExcluded=true on demand.
            refreshFamilySharedLibraryOwners(includeNonGames = true, includeExcluded = false)
        } catch (e: Exception) {
            Timber.e(e, "GetSharedLibraryApps failed")
        }

        try {
            val preferredRequest = SteammessagesFamilygroupsSteamclient.CFamilyGroups_GetPreferredLenders_Request.newBuilder().apply {
                familyGroupid = familyGroupId
            }.build()

            val preferredResult = familyGroups.getPreferredLenders(preferredRequest).await()
            if (preferredResult.result == EResult.OK) {
                preferredLenderByAppId.clear()
                preferredResult.body.membersList.forEach { member ->
                    val lenderSteamId = member.steamid
                    member.preferredAppidsList.forEach { appId ->
                        preferredLenderByAppId[appId] = lenderSteamId
                    }
                }
                // Persist server state locally so offline reconnect can fall back to it.
                PrefManager.preferredFamilyLenders = preferredLenderByAppId.toMap()
                Timber.i("Cached ${preferredLenderByAppId.size} preferred family lenders")
            } else {
                Timber.w("GetPreferredLenders failed: ${preferredResult.result}")
                PrefManager.preferredFamilyLenders.forEach { (appId, lender) ->
                    preferredLenderByAppId[appId] = lender
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "GetPreferredLenders failed")
            PrefManager.preferredFamilyLenders.forEach { (appId, lender) ->
                preferredLenderByAppId[appId] = lender
            }
        }

        // Best-effort persona names for family members who are also friends.
        try {
            val friendIds = familyGroupMembers.map { accountId ->
                SteamID(accountId.toLong(), EUniverse.Public, EAccountType.Individual)
            }
            if (friendIds.isNotEmpty()) {
                _steamFriends?.requestFriendInfo(friendIds)
            }
            friendIds.forEach { steamId ->
                val persona = _steamFriends?.getFriendPersonaName(steamId)
                if (!persona.isNullOrBlank() && persona != "[unknown]") {
                    familyMemberNames[steamId.convertToUInt64()] = persona
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "Could not resolve family member persona names")
        }

        applyAllCachedPreferredLenders()
        // Notify UI after caches are filled; familyGroupId was already set before this RPC.
        bumpFamilyPreferredCopyDataVersion()
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun onLoggedOn(callback: LoggedOnCallback) {
        Timber.i("Logged onto Steam: ${callback.result}")

        if (userSteamId?.isValid == true) {
            if (PrefManager.steamUserAccountId != userSteamId!!.accountID.toInt()) {
                PrefManager.steamUserAccountId = userSteamId!!.accountID.toInt()
                Timber.d("Saving logged in Steam accountID ${userSteamId!!.accountID.toInt()}")
            }
            val steamId64 = userSteamId!!.convertToUInt64()
            if (PrefManager.steamUserSteamId64 != steamId64) {
                PrefManager.steamUserSteamId64 = steamId64
                Timber.d("Saving logged in Steam ID64 $steamId64")
            }
        }

        when (callback.result) {
            EResult.TryAnotherCM -> {
                _loginResult = LoginResult.Failed
                reconnect()
            }

            EResult.OK -> {
                // save the current cellid somewhere. if we lose our saved server list, we can use this when retrieving
                // servers from the Steam Directory.
                if (!PrefManager.cellIdManuallySet) {
                    PrefManager.cellId = callback.cellID
                }

                // retrieve persona data of logged in user
                scope.launch { requestUserPersona() }

                // fetch the user's Steam collections for the library filter
                steamCollectionsJob = scope.launch { fetchSteamCollections() }

                // Request family share info if we have a familyGroupId.
                // Set id synchronously so UI can observe hydration before the RPC finishes.
                if (callback.familyGroupId != 0L) {
                    setFamilyGroupId(callback.familyGroupId)
                    scope.launch {
                        val request = SteammessagesFamilygroupsSteamclient.CFamilyGroups_GetFamilyGroup_Request.newBuilder().apply {
                            familyGroupid = callback.familyGroupId
                        }.build()

                        _steamFamilyGroups!!.getFamilyGroup(request).await().let {
                            if (it.result != EResult.OK) {
                                Timber.w("An error occurred loading family group info.")
                                return@launch
                            }

                            val response = it.body

                            Timber.i("Found family share: ${response.name}, with ${response.membersCount} members.")

                            familyGroupMembers.clear()
                            response.membersList.forEach { member ->
                                val steamId = SteamID(member.steamid)
                                val accountID = steamId.accountID.toInt()
                                familyGroupMembers.add(accountID)
                            }
                        }

                        refreshFamilyPreferredCopyData()
                    }
                } else {
                    setFamilyGroupId(0L)
                    familyGroupMembers.clear()
                    familyAppOwnerSteamIds.clear()
                    familySharedLibraryReadyForDlcCounts = false
                    preferredLenderByAppId.clear()
                    familyMemberNames.clear()
                    bumpFamilyPreferredCopyDataVersion()
                }

                picsChangesCheckerJob = continuousPICSChangesChecker()
                picsGetProductInfoJob = continuousPICSGetProductInfo()

                // Tell steam we're online, this allows friends to update.
                _steamFriends?.setPersonaState(PrefManager.personaState)

                val activeGame = ActiveGameRegistry.get()
                if (activeGame != null) {
                    Timber.i("Re-sending active game session for appId=%d after Steam reconnect", activeGame.appId)
                    scope.launch {
                        notifyRunningProcesses(activeGame)
                    }
                } else {
                    Timber.d("No active game session to re-send after Steam reconnect")
                }

                notificationHelper.notify("Connected")

                _loginResult = LoginResult.Success

                // Resume any workshop downloads that were interrupted
                scope.launch {
                    resumePendingWorkshopDownloads()
                }

                syncPendingOfflineAchievements()
            }

            else -> {
                if (shouldClearUserDataForLoggedOnFailure(callback.result)) {
                    PrefManager.clearSteamSessionPreferences()
                }

                _loginResult = LoginResult.Failed

                reconnect()
            }
        }

        val event = SteamEvent.LogonEnded(PrefManager.username, _loginResult)
        PluviaApp.events.emit(event)
    }

    private suspend fun resumePendingWorkshopDownloads() {
        if (PrefManager.downloadOnWifiOnly && !hasWifiOrEthernet) {
            Timber.i("Skipping pending workshop downloads — WiFi-only mode and no WiFi")
            return
        }

        val dao = appDao ?: return
        val pendingAppIds = dao.getAppsWithPendingWorkshopDownloads()
        if (pendingAppIds.isEmpty()) return

        Timber.i("Resuming ${pendingAppIds.size} pending workshop download(s)")
        val context = this@SteamService
        for (appId in pendingAppIds) {
            // If the game is no longer installed, the pending flag is stale — clear it.
            if (!isAppInstalled(appId)) {
                Timber.i("App $appId no longer installed, clearing stale workshop state")
                dao.clearWorkshopState(appId)
                continue
            }

            // Skip if a download is already running for this app
            if (getAppDownloadInfo(appId) != null) continue

            val enabledIds = WorkshopManager.parseEnabledIds(
                dao.getEnabledWorkshopItemIds(appId),
            )
            if (enabledIds.isEmpty()) {
                dao.setWorkshopDownloadPending(appId, false)
                continue
            }

            WorkshopManager.startWorkshopDownload(appId, enabledIds, context)
        }
    }

    internal fun addPendingSyncApp(appId: Int) {
        synchronized(pendingSyncFileLock) {
            pendingSyncAppIds.add(appId)
            runCatching { pendingSyncFile.writeText(pendingSyncAppIds.joinToString("\n")) }
        }
        Timber.tag("achievements").d("Recording appId=$appId for offline achievement sync on reconnect")
    }

    internal fun removePendingSyncApp(appId: Int) {
        synchronized(pendingSyncFileLock) {
            pendingSyncAppIds.remove(appId)
            runCatching {
                if (pendingSyncAppIds.isEmpty()) pendingSyncFile.delete()
                else pendingSyncFile.writeText(pendingSyncAppIds.joinToString("\n"))
            }
        }
    }

    internal fun clearPendingSync() {
        synchronized(pendingSyncFileLock) {
            pendingSyncAppIds.clear()
            runCatching { pendingSyncFile.delete() }
        }
    }

    private fun syncPendingOfflineAchievements() {
        offlineAchievementSyncJob?.cancel()
        offlineAchievementSyncJob = scope.launch {
            try {
                delay(2_000)

                if (!isConnected || !isLoggedIn) {
                    Timber.tag("achievements").d("Skipping reconnect achievement sync sweep — Steam no longer connected")
                    return@launch
                }

                val appsToSync = pendingSyncAppIds.toSet()
                if (appsToSync.isEmpty()) {
                    Timber.tag("achievements").d("Skipping reconnect achievement sync sweep — no apps were closed while offline")
                    return@launch
                }

                Timber.tag("achievements").i("Syncing offline achievements for ${appsToSync.size} app(s) closed while disconnected")
                for (appId in appsToSync) {
                    ensureActive()

                    if (!isConnected || !isLoggedIn) {
                        Timber.tag("achievements").d("Stopping reconnect achievement sync sweep — Steam no longer connected")
                        return@launch
                    }

                    val gseSaveDirs = getGseSaveDirs(applicationContext, appId).filter { it.isDirectory }
                    if (gseSaveDirs.isEmpty()) {
                        removePendingSyncApp(appId)
                        continue
                    }

                    val hasOfflineAchievementData = gseSaveDirs.any { dir ->
                        File(dir, "achievements.json").exists() ||
                            (File(dir, "stats").isDirectory && (File(dir, "stats").listFiles()?.isNotEmpty() == true))
                    }
                    if (!hasOfflineAchievementData) {
                        removePendingSyncApp(appId)
                        continue
                    }

                    if (!tryAcquireSync(appId)) {
                        Timber.tag("achievements").d("Skipping reconnect achievement sync for appId=$appId — sync already in progress")
                        continue
                    }

                    try {
                        Timber.tag("achievements").i("Attempting reconnect achievement sync for appId=$appId")
                        syncAchievementsFromGoldberg(applicationContext, appId)
                        removePendingSyncApp(appId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag("achievements").e(e, "Reconnect achievement sync failed for appId=$appId")
                    } finally {
                        releaseSync(appId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("achievements").e(e, "Reconnect achievement sync sweep failed")
            } finally {
                if (offlineAchievementSyncJob?.isActive != true) {
                    offlineAchievementSyncJob = null
                }
            }
        }
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        Timber.i("Logged off of Steam: ${callback.result}")

        notificationHelper.notify("Disconnected...")

        if (isLoggingOut) {
            performLogOffDuties(clearCloudSyncState = true)

            scope.launch { stop() }
        } else if (callback.result == EResult.LogonSessionReplaced) {
            // Unexpected session replacement should not wipe persisted Steam state.
            cancelLongLivedSteamJobs()
            scope.launch { stop() }
        } else if (callback.result == EResult.LoggedInElsewhere) {
            // received when a client runs an app and wants to forcibly close another
            // client running an app. The callback doesn't carry the remote app id, so the
            // dialog falls back to a generic "another game" label.
            if (PluviaApp.xEnvironment != null) {
                if (!_isHandlingConflict.getAndSet(true)) {
                    _isPlayingBlocked.value = true
                    PluviaApp.events.emit(SteamEvent.PlayingBlocked(remoteAppName = null))
                }
                reconnect()
            } else {
                PluviaApp.events.emit(SteamEvent.ForceCloseApp)
                reconnect()
            }
        } else {
            reconnect()
        }
    }

    private fun onPlayingSessionState(callback: PlayingSessionStateCallback) {
        Timber.d("onPlayingSessionState: blocked=${callback.isPlayingBlocked} remoteAppId=${callback.playingAppID}")
        _isPlayingBlocked.value = callback.isPlayingBlocked

        if (!callback.isPlayingBlocked) return

        // Only show the dialog if the remote app is in our local DB. Non-Steam shortcuts get
        // synthetic IDs we can't kick and have no name for, so let the local launch proceed.
        val knownApp = callback.playingAppID
            .takeIf { it != 0 }
            ?.let { getAppInfoOf(it) }
            ?: return

        if (_isHandlingConflict.compareAndSet(false, true)) {
            PluviaApp.events.emit(SteamEvent.PlayingBlocked(remoteAppName = knownApp.name))
        }
    }

    /**
     * Steam fans a game invite out to every session on the account, so this arrives here even
     * though the running game is served by the separate bionic Steam client. Acting on it is the
     * overlay's job -- this only surfaces the prompt.
     */
    private fun onGameInvite(callback: GameInviteCallback) {
        Timber.i("onGameInvite: from=${callback.inviterSteamId} connect=${callback.connectString}")
        if (callback.connectString.isEmpty()) return

        GameInviteNotificationManager.show(callback.inviterSteamId, callback.connectString)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun onPersonaStateReceived(callback: PersonaStateCallback) {
        // Ignore accounts that arent individuals
        if (!callback.friendId.isIndividualAccount) {
            return
        }

        // Ignore states where the name is blank.
        if (callback.playerName.isEmpty()) {
            return
        }

        val friendSteamId64 = callback.friendId.convertToUInt64()
        if (familyGroupMembers.contains(callback.friendId.accountID.toInt())) {
            familyMemberNames[friendSteamId64] = callback.playerName
        }

        // Timber.d("Persona state received: ${callback.name}")

        scope.launch {
            db.withTransaction {
                // Send off an event if we change states.
                val userSteamId = steamClient?.steamID ?: return@withTransaction
                if(callback.friendId != userSteamId) return@withTransaction

                val avatarHash = callback.avatarHash.toHexString()
                val playerName = callback.playerName

                // When connected, callback may return Offline due to missing Status flag in request.
                // Trust PrefManager.personaState (user's chosen state) in that case.
                val state = if (callback.personaState == EPersonaState.Offline && isConnected) {
                    PrefManager.personaState
                } else {
                    callback.personaState
                }

                Timber.d(
                    "Local persona state received: ${callback.playerName}, state=$state, gameAppId=${callback.gamePlayedAppId}, gameName=${callback.gameName}",
                )

                // Update local state flow
                _localPersona.update {
                    it.copy(
                        avatarHash = avatarHash,
                        name = playerName,
                        state = state,
                        gameAppID = callback.gamePlayedAppId,
                        gameName = appDao.findApp(callback.gamePlayedAppId)?.name ?: callback.gameName,
                    )
                }

                // Cache local persona
                PrefManager.steamUserAvatarHash = avatarHash
                PrefManager.steamUserName = playerName

                val event = SteamEvent.PersonaStateReceived(localPersona.value)
                PluviaApp.events.emit(event)
            }
        }
    }

    /**
     * Downloads the user's Steam collections from CloudConfigStore and publishes the
     * parsed static collections to [SteamCollectionRepository] for the library filter.
     */
    internal suspend fun fetchSteamCollections() {
        val client = steamClient
        val fetchSteamId = client?.steamID?.convertToUInt64()
        // Same login + account as when we started, so a slow RPC can't cross accounts.
        fun sameSession() = isLoggedIn && steamClient?.steamID?.convertToUInt64() == fetchSteamId
        val um = client?.getHandler<SteamUnifiedMessages>()
        if (um == null) {
            Timber.tag("SteamCollections").w("UnifiedMessages handler unavailable; cannot fetch collections")
            return
        }
        // A registered service is required: JavaSteam routes ServiceMethodResponse packets by
        // service name, so the generic sendMessage alone never receives the reply.
        val service = try {
            um.createService(CloudConfigStoreService::class.java)
        } catch (t: Throwable) {
            Timber.tag("SteamCollections").e(t, "Cannot create CloudConfigStore service; keeping cached snapshot")
            return
        }

        val request = SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_Download_Request.newBuilder()
            .addVersions(
                SteammessagesCloudconfigstoreSteamclient.CCloudConfigStore_NamespaceVersion.newBuilder()
                    .setEnamespace(1) // user collections namespace
                    .setVersion(0L), // 0 = full download
            )
            .build()

        // Reply can be starved or dropped during the post-login PICS burst; retry with backoff.
        val backoffsMs = longArrayOf(3_000L, 8_000L, 20_000L)
        val maxAttempts = backoffsMs.size + 1
        repeat(maxAttempts) { attempt ->
            if (!sameSession()) return
            try {
                val job = service.download(request)
                job.timeout = 30_000L
                val response = job.toFuture().await()

                val body = response.body.build()
                val rawEntries = body.dataList.flatMap { ns ->
                    ns.entriesList.map { entry ->
                        SteamCollectionParser.RawEntry(
                            key = entry.key,
                            value = entry.value,
                            isDeleted = entry.isDeleted,
                        )
                    }
                }

                val parsed = SteamCollectionParser.parse(rawEntries)
                Timber.tag("SteamCollections").i(
                    "Fetched ${parsed.collections.size} Steam collections " +
                        "(${parsed.skippedDynamicCount} dynamic skipped) on attempt ${attempt + 1}",
                )
                if (sameSession()) SteamCollectionRepository.update(parsed)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val lastAttempt = attempt == maxAttempts - 1
                Timber.tag("SteamCollections").w(
                    t,
                    "Steam collections fetch attempt ${attempt + 1}/$maxAttempts failed" +
                        if (lastAttempt) "; keeping cached snapshot" else "; retrying",
                )
                if (!lastAttempt) delay(backoffsMs[attempt])
            }
        }
    }

    private fun onLicenseList(callback: LicenseListCallback) {
        if (callback.result != EResult.OK) {
            Timber.w("Failed to get License list")
            return
        }

        Timber.i("Received License List ${callback.result}, size: ${callback.licenseList.size}")

        scope.launch {
            db.withTransaction {
                // Note: I assume with every launch we do, in fact, update the licenses for app the apps if we join or get removed
                //      from family sharing... We really can't test this as there is a 1-year cooldown.
                //      Then 'findStaleLicences' will find these now invalid items to remove.

                // Chunk the input to reduce memory pressures for very large items.
                licenses = callback.licenseList
                cachedLicenseDao.deleteAll()
                callback.licenseList.chunked(500).forEach { chunk ->
                    cachedLicenseDao.insertAll(
                        chunk.map { license ->
                            CachedLicense(licenseJson = LicenseSerializer.serializeLicense(license))
                        },
                    )
                }
                val licensesToAdd = callback.licenseList
                    .groupBy { it.packageID }
                    .map { licensesEntry ->
                        val preferred = licensesEntry.value.firstOrNull {
                            it.ownerAccountID == userSteamId?.accountID?.toInt()
                        } ?: licensesEntry.value.first()
                        SteamLicense(
                            packageId = licensesEntry.key,
                            lastChangeNumber = preferred.lastChangeNumber,
                            timeCreated = preferred.timeCreated,
                            timeNextProcess = preferred.timeNextProcess,
                            minuteLimit = preferred.minuteLimit,
                            minutesUsed = preferred.minutesUsed,
                            paymentMethod = preferred.paymentMethod,
                            licenseFlags = licensesEntry.value
                                .map { it.licenseFlags }
                                .reduceOrNull { first, second ->
                                    val combined = EnumSet.copyOf(first)
                                    combined.addAll(second)
                                    combined
                                } ?: EnumSet.noneOf(ELicenseFlags::class.java),
                            purchaseCode = preferred.purchaseCode,
                            licenseType = preferred.licenseType,
                            territoryCode = preferred.territoryCode,
                            accessToken = preferred.accessToken,
                            ownerAccountId = licensesEntry.value.map { it.ownerAccountID }, // Read note above
                            masterPackageID = preferred.masterPackageID,
                        )
                    }

                if (licensesToAdd.isNotEmpty()) {
                    Timber.i("Adding ${licensesToAdd.size} licenses")
                    licensesToAdd.chunked(500).forEach { chunk ->
                        licenseDao.insertAll(chunk)
                    }
                }

                val licensesToRemove = licenseDao.findStaleLicences(
                    packageIds = callback.licenseList.map { it.packageID },
                )
                if (licensesToRemove.isNotEmpty()) {
                    Timber.i("Removing ${licensesToRemove.size} (stale) licenses")
                    val packageIds = licensesToRemove.map { it.packageId }
                    licenseDao.deleteStaleLicenses(packageIds)
                }

                // Get PICS information with the current license database.
                licenseDao.getAllLicenses()
                    .map { PICSRequest(it.packageId, it.accessToken) }
                    .chunked(MAX_PICS_BUFFER)
                    .forEach { chunk ->
                        Timber.d("onLicenseList: Queueing ${chunk.size} package(s) for PICS")
                        packagePicsChannel.send(chunk)
                    }
            }

            // After licenses land, re-apply any preferred family lenders.
            if (familyGroupId != 0L && preferredLenderByAppId.isNotEmpty()) {
                applyAllCachedPreferredLenders()
            } else if (familyGroupId != 0L && PrefManager.preferredFamilyLenders.isNotEmpty()) {
                PrefManager.preferredFamilyLenders.forEach { (appId, lender) ->
                    preferredLenderByAppId[appId] = lender
                }
                applyAllCachedPreferredLenders()
            }
        }
    }

    override fun onChanged(qrAuthSession: QrAuthSession?) {
        qrAuthSession?.let { qr ->
            if (!BuildConfig.DEBUG) {
                Timber.d("QR code changed -> ${qr.challengeUrl}")
            }

            val event = SteamEvent.QrChallengeReceived(qr.challengeUrl)
            PluviaApp.events.emit(event)
        } ?: run { Timber.w("QR challenge url was null") }
    }
    // endregion

    /**
     * Request changes for apps and packages since a given change number.
     * Checks every [PICS_CHANGE_CHECK_DELAY] seconds.
     * Results are returned in a [PICSChangesCallback]
     */
    private fun continuousPICSChangesChecker(): Job = scope.launch {
        while (isActive && isLoggedIn) {
            // Initial delay before each check
            delay(60.seconds)

            PICSChangesCheck()
        }
    }

    private fun PICSChangesCheck() {
        scope.launch {
            ensureActive()

            try {
                val changesSince = _steamApps!!.picsGetChangesSince(
                    lastChangeNumber = PrefManager.lastPICSChangeNumber,
                    sendAppChangeList = true,
                    sendPackageChangelist = true,
                ).await()

                if (PrefManager.lastPICSChangeNumber == changesSince.currentChangeNumber) {
                    Timber.w("Change number was the same as last change number, skipping")
                    return@launch
                }

                // Set our last change number
                PrefManager.lastPICSChangeNumber = changesSince.currentChangeNumber

                Timber.d(
                    "picsGetChangesSince:" +
                        "\n\tlastChangeNumber: ${changesSince.lastChangeNumber}" +
                        "\n\tcurrentChangeNumber: ${changesSince.currentChangeNumber}" +
                        "\n\tisRequiresFullUpdate: ${changesSince.isRequiresFullUpdate}" +
                        "\n\tisRequiresFullAppUpdate: ${changesSince.isRequiresFullAppUpdate}" +
                        "\n\tisRequiresFullPackageUpdate: ${changesSince.isRequiresFullPackageUpdate}" +
                        "\n\tappChangesCount: ${changesSince.appChanges.size}" +
                        "\n\tpkgChangesCount: ${changesSince.packageChanges.size}",

                )

                // Process any app changes
                launch {
                    changesSince.appChanges.values
                        .filter { changeData ->
                            // only queue PICS requests for apps existing in the db that have changed
                            val app = appDao.findApp(changeData.id) ?: return@filter false
                            changeData.changeNumber != app.lastChangeNumber
                        }
                        .map { PICSRequest(id = it.id) }
                        .chunked(MAX_PICS_BUFFER)
                        .forEach { chunk ->
                            ensureActive()
                            Timber.d("onPicsChanges: Queueing ${chunk.size} app(s) for PICS")
                            appPicsChannel.send(chunk)
                        }
                }

                // Process any package changes
                launch {
                    val pkgsWithChanges = changesSince.packageChanges.values
                        .filter { changeData ->
                            // only queue PICS requests for pkgs existing in the db that have changed
                            val pkg = licenseDao.findLicense(changeData.id) ?: return@filter false
                            changeData.changeNumber != pkg.lastChangeNumber
                        }

                    if (pkgsWithChanges.isNotEmpty()) {
                        val pkgsForAccessTokens = pkgsWithChanges.filter { it.isNeedsToken }.map { it.id }

                        val accessTokens = _steamApps?.picsGetAccessTokens(emptyList(), pkgsForAccessTokens)
                            ?.await()?.packageTokens ?: emptyMap()

                        ensureActive()

                        pkgsWithChanges
                            .map { PICSRequest(it.id, accessTokens[it.id] ?: 0) }
                            .chunked(MAX_PICS_BUFFER)
                            .forEach { chunk ->
                                Timber.d("onPicsChanges: Queueing ${chunk.size} package(s) for PICS")
                                packagePicsChannel.send(chunk)
                            }
                    }
                }
            } catch (e: NullPointerException) {
                Timber.w("No lastPICSChangeNumber, skipping")
            } catch (e: AsyncJobFailedException) {
                Timber.w("AsyncJobFailedException, skipping")
            }
        }
    }

    /**
     * A buffered flow to parse so many PICS requests in a given moment.
     */
    private fun continuousPICSGetProductInfo(): Job = scope.launch {
        // Launch both coroutines within this parent job
        launch {
            appPicsChannel.receiveAsFlow()
                .filter { it.isNotEmpty() }
                .buffer(capacity = MAX_PICS_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
                .collect { appRequests ->
                    Timber.d("Processing ${appRequests.size} app PICS requests")

                    ensureActive()
                    if (!isLoggedIn) return@collect
                    val steamApps = instance?._steamApps ?: return@collect

                    try {
                        val callback = steamApps.picsGetProductInfo(
                            apps = appRequests,
                            packages = emptyList(),
                        ).await()

                        callback.results.forEachIndexed { index, picsCallback ->
                            Timber.d(
                                "onPicsProduct: ${index + 1} of ${callback.results.size}" +
                                    "\n\tReceived PICS result of ${picsCallback.apps.size} app(s)." +
                                    "\n\tReceived PICS result of ${picsCallback.packages.size} package(s).",
                            )

                            ensureActive()
                            val steamAppsMap = picsCallback.apps.values.mapNotNull { app ->
                                val appFromDb = appDao.findApp(app.id)
                                val packageId = appFromDb?.packageId ?: INVALID_PKG_ID
                                val packageFromDb = if (packageId != INVALID_PKG_ID) licenseDao.findLicense(packageId) else null
                                val ownerAccountId = packageFromDb?.ownerAccountId ?: emptyList()

                                // Apps with -1 for the ownerAccountId should be added.
                                //  This can help with friend game names.

                                // TODO maybe apps with -1 for the ownerAccountId can be stripped with necessities and name.

                                val ufsParseVersionOutdated = appFromDb != null && appFromDb.ufsParseVersion < CURRENT_UFS_PARSE_VERSION

                                if (app.changeNumber != appFromDb?.lastChangeNumber || ufsParseVersionOutdated) {
                                    val newApp = app.keyValues.generateSteamApp().copy(
                                        packageId = packageId,
                                        ownerAccountId = ownerAccountId,
                                        receivedPICS = true,
                                        lastChangeNumber = app.changeNumber,
                                        licenseFlags = packageFromDb?.licenseFlags ?: EnumSet.noneOf(ELicenseFlags::class.java),
                                    )
                                    if (ufsParseVersionOutdated && newApp.ufs.saveFilePatterns.any { it.uploadRoot != it.root || it.uploadPath != it.path }) {
                                        // UFS path logic changed and this app has rootoverrides: store 0 to force one
                                        // full cloud query while preserving the local sync snapshot.
                                        changeNumbersDao.insert(app.id, 0L)
                                    }
                                    newApp
                                } else {
                                    null
                                }
                            }

                            if (steamAppsMap.isNotEmpty()) {
                                Timber.i("Inserting ${steamAppsMap.size} PICS apps to database")
                                db.withTransaction {
                                    appDao.insertAll(steamAppsMap)
                                }
                            }
                        }
                    } catch (e: AsyncJobFailedException) {
                        Timber.w("Could not get PICS product info $e")
                    }
                }
        }

        launch {
            packagePicsChannel.receiveAsFlow()
                .filter { it.isNotEmpty() }
                .buffer(capacity = MAX_PICS_BUFFER, onBufferOverflow = BufferOverflow.SUSPEND)
                .collect { packageRequests ->
                    Timber.d("Processing ${packageRequests.size} package PICS requests")

                    ensureActive()
                    if (!isLoggedIn) return@collect
                    val steamApps = instance?._steamApps ?: return@collect

                    val callback = steamApps.picsGetProductInfo(
                        apps = emptyList(),
                        packages = packageRequests,
                    ).await()

                    callback.results.forEach { picsCallback ->
                        // Don't race the queue.
                        if (!isLoggedIn) return@collect
                        val queue = Collections.synchronizedList(mutableListOf<Int>())

                        db.withTransaction {
                            // When the same app appears in multiple packages (e.g. user owns the game and
                            // also has a free-weekend / demo / family-shared sub for it), the previous
                            // implementation overwrote SteamApp.packageId with whichever pkg was iterated
                            // last — non-deterministic and prone to landing on a non-user-owned package,
                            // which then makes the user's own game appear as family-shared in the library.
                            // To fix that we (a) process user-owned packages last so they win the
                            // last-write-wins assignment within this batch and (b) refuse to downgrade an
                            // existing user-owned packageId across batches.
                            val accountId = userSteamId?.accountID?.toInt()
                            val packageLicenses: Map<Int, SteamLicense> = if (accountId != null) {
                                val packageIds = picsCallback.packages.values.map { it.id }
                                licenseDao.findLicenses(packageIds).associateBy { it.packageId }
                            } else {
                                emptyMap()
                            }
                            val userOwnedPackageIds: Set<Int> = if (accountId != null) {
                                packageLicenses.values
                                    .filter { it.ownerAccountId.contains(accountId) }
                                    .mapTo(HashSet()) { it.packageId }
                            } else {
                                emptySet()
                            }

                            // Prefer non-expired user-owned packages so a live sub wins over an expired remnant.
                            // When a preferred family lender is set for an app, prefer that lender's packages higher.
                            val preferredLenders = if (familyGroupId != 0L) {
                                preferredLenderByAppId.toMap().ifEmpty { PrefManager.preferredFamilyLenders }
                            } else {
                                emptyMap()
                            }
                            fun preferredLenderAccountForApp(appId: Int): Int? =
                                preferredLenders[appId]?.let { SteamID(it).accountID.toInt() }

                            fun pkgRank(pkgId: Int, forAppId: Int? = null): Int {
                                val preferredAccount = forAppId?.let { preferredLenderAccountForApp(it) }
                                val license = packageLicenses[pkgId]
                                if (preferredAccount != null && license?.ownerAccountId?.contains(preferredAccount) == true) {
                                    return if (ELicenseFlags.Expired in license.licenseFlags) 3 else 4
                                }
                                if (pkgId !in userOwnedPackageIds) return 0
                                val expired = license?.licenseFlags?.contains(ELicenseFlags.Expired) == true
                                return if (expired) 1 else 2
                            }

                            val orderedPackages = picsCallback.packages.values.sortedBy { pkg ->
                                val appIds = pkg.keyValues["appids"].children.map { it.asInteger() }
                                appIds.maxOfOrNull { pkgRank(pkg.id, it) } ?: pkgRank(pkg.id)
                            }

                            orderedPackages.forEach { pkg ->
                                val appIds = pkg.keyValues["appids"].children.map { it.asInteger() }
                                licenseDao.updateApps(pkg.id, appIds)

                                val depotIds = pkg.keyValues["depotids"].children.map { it.asInteger() }
                                licenseDao.updateDepots(pkg.id, depotIds)

                                // Insert a stub row (or update) of SteamApps to the database.
                                appIds.forEach { appid ->
                                    val existing = appDao.findApp(appid)
                                    if (existing == null) {
                                        appDao.insert(SteamApp(id = appid, packageId = pkg.id))
                                        return@forEach
                                    }
                                    if (existing.packageId == pkg.id) {
                                        return@forEach
                                    }
                                    if (accountId != null && existing.packageId != INVALID_PKG_ID) {
                                        val existingLicense = packageLicenses[existing.packageId]
                                            ?: licenseDao.findLicense(existing.packageId)
                                        val preferredAccount = preferredLenderAccountForApp(appid)
                                        val existingRank = when {
                                            preferredAccount != null &&
                                                existingLicense?.ownerAccountId?.contains(preferredAccount) == true -> {
                                                if (ELicenseFlags.Expired in existingLicense.licenseFlags) 3 else 4
                                            }
                                            existingLicense == null -> 0
                                            !existingLicense.ownerAccountId.contains(accountId) -> 0
                                            ELicenseFlags.Expired in existingLicense.licenseFlags -> 1
                                            else -> 2
                                        }
                                        if (existingRank > pkgRank(pkg.id, appid)) {
                                            return@forEach
                                        }
                                    }
                                    appDao.update(existing.copy(packageId = pkg.id))
                                }

                                queue.addAll(appIds)
                            }
                        }

                        try {
                            // TODO: This could be an issue. (Stalling)
                            steamApps.picsGetAccessTokens(
                                appIds = queue,
                                packageIds = emptyList(),
                            ).await()
                                .appTokens
                                .forEach { (key, value) ->
                                    appTokens[key] = value
                                }

                            // Get PICS information with the app ids.
                            queue
                                .map { PICSRequest(id = it, accessToken = appTokens[it] ?: 0L) }
                                .chunked(MAX_PICS_BUFFER)
                                .forEach { chunk ->
                                    Timber.d("bufferedPICSGetProductInfo: Queueing ${chunk.size} for PICS")
                                    appPicsChannel.send(chunk)
                                }
                        } catch (e: AsyncJobFailedException) {
                            Timber.w("Could not get PICS product info $e")
                        }
                    }
                }
        }
    }

    /**
     * Get encrypted app ticket for an app, with 30-minute caching.
     * Returns the serialized protobuf bytes, or null if unavailable.
     */
    suspend fun getEncryptedAppTicket(appId: Int): ByteArray? {
        return try {
            // Check database for existing ticket less than 30 minutes old
            val cachedTicket = encryptedAppTicketDao.getByAppId(appId)
            val now = System.currentTimeMillis()
            val thirtyMinutes = 30 * 60 * 1000L

            if (cachedTicket != null && (now - cachedTicket.timestamp) < thirtyMinutes) {
                Timber.d("Using cached encrypted app ticket protobuf for app $appId")
                return cachedTicket.encryptedTicket
            }

            // Request new ticket from Steam
            val steamApps = instance?._steamApps ?: null
            val response = try {
                withTimeout(5_000) {
                    steamApps?.requestEncryptedAppTicket(appId)?.await()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to request encrypted app ticket for app $appId")
                return null
            }

            if (response?.result != EResult.OK || response.encryptedAppTicket == null) {
                Timber.w("Failed to get encrypted app ticket for app $appId: ${response?.result}")
                return null
            }

            // Extract all fields from the protobuf message
            val ticketProto = response.encryptedAppTicket
            val ticket = EncryptedAppTicket(
                appId = appId,
                result = response.result.code(),
                ticketVersionNo = ticketProto!!.ticketVersionNo.toInt(),
                crcEncryptedTicket = ticketProto.crcEncryptedticket.toInt(),
                cbEncryptedUserData = ticketProto.cbEncrypteduserdata.toInt(),
                cbEncryptedAppOwnershipTicket = ticketProto.cbEncryptedAppownershipticket.toInt(),
                encryptedTicket = ticketProto.toByteArray(),
                timestamp = now,
            )

            // Store in database
            encryptedAppTicketDao.insert(ticket)
            Timber.d("Stored new encrypted app ticket protobuf for app $appId")

            ticket.encryptedTicket
        } catch (e: Exception) {
            Timber.e(e, "Error getting encrypted app ticket for app $appId")
            null
        }
    }

    /**
     * Get encrypted app ticket as base64 encoded string, with 30-minute caching.
     * Returns the base64 encoded ticket, or null if unavailable.
     */
    suspend fun getEncryptedAppTicketBase64(appId: Int): String? {
        val ticket = getEncryptedAppTicket(appId) ?: return null
        return Base64.encodeToString(ticket, Base64.NO_WRAP)
    }
}
