package app.gamenative

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.StrictMode
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.events.EventDispatcher
import app.gamenative.mods.NexusAuthManager
import app.gamenative.powercontrol.PowerManager
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.sync.FrontendSyncManager
import app.gamenative.ui.screen.xserver.RadialMenuCoordinator
import app.gamenative.utils.ContainerMigrator
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.PlayIntegrity
import app.gamenative.utils.downloader.ContainerFilesDownloader
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import com.google.android.play.core.splitcompat.SplitCompatApplication
import com.posthog.PersonProfiles

// Add PostHog imports
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.winlator.container.Container
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.xenvironment.XEnvironment
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

typealias NavChangedListener = NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : SplitCompatApplication() {

    @Inject lateinit var gogGameDao: GOGGameDao
    @Inject lateinit var amazonGameDao: AmazonGameDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        preloadSystemLibraries()

        // Allows to find resource streams not closed within GameNative and JavaSteam
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )

            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        NetworkMonitor.init(this)

        // Lets the download queue hold a wake/Wi-Fi lock while downloads transfer.
        app.gamenative.service.download.GameDownloadService.init(this)

        // Init our custom crash handler.
        CrashHandler.initialize(this)

        // Init our datastore preferences.
        PrefManager.init(this)
        NexusAuthManager.initialize(this)
        FrontendSyncManager.init(this)

        // Initialize GOGConstants
        app.gamenative.service.gog.GOGConstants.init(this)

        DownloadService.populateDownloadService(this)

        migrateGogAmazonPaths()

        appScope.launch {
            ContainerMigrator.migrateLegacyContainersIfNeeded(
                context = applicationContext,
                onProgressUpdate = null,
                onComplete = null
            )
        }

        // Preload all container files in the background
        appScope.launch {
            ContainerFilesDownloader.preloadAllContainerFiles(applicationContext)
        }

        // Clear any stale temporary config overrides from previous app sessions
        try {
            IntentLaunchManager.clearAllTemporaryOverrides()
            Timber.d("[PluviaApp]: Cleared temporary config overrides from previous session")
        } catch (e: Exception) {
            Timber.e(e, "[PluviaApp]: Failed to clear temporary config overrides")
        }

        // Initialize PostHog Analytics
        val postHogConfig = PostHogAndroidConfig(
            apiKey = BuildConfig.POSTHOG_API_KEY,
            host = BuildConfig.POSTHOG_HOST,
        ).apply {
            /* turn every event into an identified one */
            personProfiles = PersonProfiles.ALWAYS
        }
        PostHogAndroid.setup(this, postHogConfig)
        com.posthog.PostHog.register("build_flavor", BuildConfig.FLAVOR)

        if (PrefManager.usageAnalyticsEnabled) {
            com.posthog.PostHog.capture(
                event = "\$set",
                properties = mapOf(
                    "\$set" to mapOf("recommendation_enabled" to PrefManager.showRecommendations),
                ),
            )
        }

        PlayIntegrity.warmUp(this)

        PowerManager.initialize(this)
    }

    /**
     * One-time migration: moves GOG/Amazon game directories from
     * {filesDir}/ to {dataDir}/ to match Steam/Epic, and updates DB paths.
     */
    private fun migrateGogAmazonPaths() {
        if (PrefManager.gogAmazonPathMigrated) return

        val dataDir = dataDir.path
        val filesDir = filesDir.absolutePath
        Timber.i("[Migration] Migrating GOG/Amazon install paths from $filesDir to $dataDir")

        val migrations = listOf(
            File(filesDir, "GOG") to File(dataDir, "GOG"),
            File(filesDir, "Amazon") to File(dataDir, "Amazon"),
        )

        for ((oldDir, newDir) in migrations) {
            if (!oldDir.exists()) continue
            if (newDir.exists()) {
                Timber.w("[Migration] Target already exists, skipping rename: ${newDir.path}")
                continue
            }
            val renamed = oldDir.renameTo(newDir)
            if (renamed) {
                Timber.i("[Migration] Renamed ${oldDir.path} -> ${newDir.path}")
            } else {
                Timber.w("[Migration] Failed to rename ${oldDir.path} -> ${newDir.path}")
            }
        }

        val oldPrefix = "$filesDir/"
        val newPrefix = "$dataDir/"

        runBlocking(Dispatchers.IO) {
            try {
                val gogGames = gogGameDao.getAllAsList()
                for (game in gogGames) {
                    if (game.installPath.isNotEmpty() && game.installPath.contains(oldPrefix)) {
                        val updated = game.copy(installPath = game.installPath.replace(oldPrefix, newPrefix))
                        gogGameDao.update(updated)
                    }
                }
                Timber.i("[Migration] Updated ${gogGames.count { it.installPath.contains(oldPrefix) }} GOG install paths")
            } catch (e: Exception) {
                Timber.e(e, "[Migration] Failed to update GOG DB paths")
            }

            try {
                val amazonGames = amazonGameDao.getAllAsList()
                for (game in amazonGames) {
                    if (game.installPath.isNotEmpty() && game.installPath.contains(oldPrefix)) {
                        val newPath = game.installPath.replace(oldPrefix, newPrefix)
                        amazonGameDao.markAsInstalled(game.productId, newPath, game.installSize, game.versionId)
                    }
                }
                Timber.i("[Migration] Updated ${amazonGames.count { it.installPath.contains(oldPrefix) }} Amazon install paths")
            } catch (e: Exception) {
                Timber.e(e, "[Migration] Failed to update Amazon DB paths")
            }
        }

        PrefManager.gogAmazonPathMigrated = true
        Timber.i("[Migration] GOG/Amazon path migration complete")
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null

        private lateinit var instance: PluviaApp
        private var cachedDefaultScreenSize: String? = null

        // TODO: find a way to make this saveable, this is terrible (leak that memory baby)
        internal var xEnvironment: XEnvironment? = null
        internal var xServerView: XServerRendererView? = null
        var inputControlsView: InputControlsView? = null
        var inputControlsManager: InputControlsManager? = null
        var touchpadView: TouchpadView? = null
        var radialMenuCoordinator: RadialMenuCoordinator? = null
        var achievementWatcher: app.gamenative.service.AchievementWatcher? = null

        var isOverlayPaused by mutableStateOf(false)
        @Volatile
        var isActivityInForeground: Boolean = true
        var isImmersiveActivityResumed: Boolean = false
        // True while the booting splash covers the game screen (and its Resume overlay).
        @Volatile
        var isBootingSplashShowing: Boolean = false

        // Active runtime suspend policy for the current in-game session.
        var activeSuspendPolicy: String = Container.SUSPEND_POLICY_MANUAL
            private set
        private var hasInitializedSuspendPolicyState: Boolean = false

        fun setActiveSuspendPolicy(policy: String) {
            activeSuspendPolicy = Container.normalizeSuspendPolicy(policy)
            hasInitializedSuspendPolicyState = true
        }

        /**
         * full environment teardown — shared by XServerScreen.exit() and
         * MainActivity.onDestroy fallback so both paths clean up identically
         */
        fun shutdownEnvironment() {
            val env = xEnvironment
            Timber.i("shutdownEnvironment: env=%s", env != null)

            // per-step catch so one failing teardown doesn't prevent the rest from running
            runCatching { achievementWatcher?.stop() }
                .onFailure { Timber.e(it, "shutdownEnvironment: achievementWatcher.stop") }
            runCatching { SteamService.clearCachedAchievements() }
                .onFailure { Timber.e(it, "shutdownEnvironment: clearCachedAchievements") }
            runCatching { touchpadView?.releasePointerCapture() }
                .onFailure { Timber.e(it, "shutdownEnvironment: releasePointerCapture") }
            runCatching { radialMenuCoordinator?.detach() }
                .onFailure { Timber.e(it, "shutdownEnvironment: radialMenuCoordinator.detach") }
            runCatching { env?.stopEnvironmentComponents() }
                .onFailure { Timber.e(it, "shutdownEnvironment: stopEnvironmentComponents") }

            // Stop performance driver
            PowerManager.stop()

            xEnvironment = null
            inputControlsView = null
            inputControlsManager = null
            touchpadView = null
            radialMenuCoordinator = null
            achievementWatcher = null
            ActiveGameRegistry.clear()
            SteamService.keepAlive = false
            SteamService.clearPlayingConflict()
            clearActiveSuspendState()
        }

        fun clearActiveSuspendState() {
            activeSuspendPolicy = Container.SUSPEND_POLICY_MANUAL
            isOverlayPaused = false
            hasInitializedSuspendPolicyState = false
        }

        fun hasValidSuspendPolicyState(): Boolean = hasInitializedSuspendPolicyState

        fun isNeverSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

        fun isManualSuspendMode(): Boolean = activeSuspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

        fun getDefaultScreenSize(): String {
            cachedDefaultScreenSize?.let { return it }

            return try {
                val displayManager = instance.getSystemService(DISPLAY_SERVICE) as? DisplayManager
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val width : Int
                    val height : Int

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val mode = display.mode
                        width = mode.physicalWidth
                        height = mode.physicalHeight
                    } else {
                        // API < 30 - Use deprecated Display API
                        val displayMetrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        display.getRealMetrics(displayMetrics)
                        width = displayMetrics.widthPixels
                        height = displayMetrics.heightPixels
                    }

                    // Calculate aspect ratio (always use landscape orientation for calculation)
                    val aspectRatio = maxOf(width, height).toFloat() / minOf(width, height).toFloat()

                    // Aspect ratio thresholds:
                    // 4:3 = 1.33
                    // 16:10 = 1.6
                    // 16:9 = 1.77

                    val result = when {
                        aspectRatio < 1.5f -> Container.DEFAULT_SCREEN_SIZE_4_3  // 4:3 aspect ratio devices
                        aspectRatio < 1.7f -> Container.DEFAULT_SCREEN_SIZE_16_10  // 16:10 aspect ratio devices
                        else -> Container.DEFAULT_SCREEN_SIZE_16_9  // 16:9 and wider aspect ratio devices
                    }
                    cachedDefaultScreenSize = result
                    result
                } else {
                    val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                    cachedDefaultScreenSize = fallback
                    fallback
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get device screen size")
                val fallback = Container.DEFAULT_SCREEN_SIZE_16_9  // Fallback to default
                cachedDefaultScreenSize = fallback
                fallback
            }
        }
    }

    /**
     * Some native libraries we dlopen at runtime (libsteamclient.so via SteamBootstrap,
     * the lsfg-vk layer, etc.) depend on `libjpeg.so`, which isn't on every device's
     * dynamic linker search path. Pre-load the system copy here with RTLD_GLOBAL
     * semantics (System.load is global) so all subsequent dlopens find its symbols.
     *
     * Single place for all: runs once in Application.onCreate before any other
     * native lib is loaded by this process. Failures are non-fatal — devices that
     * don't have the file (or have it elsewhere) just fall through.
     */
    private fun preloadSystemLibraries() {
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val candidates = if (is64) {
            listOf("/system/lib64/libjpeg.so", "/system/lib/libjpeg.so")
        } else {
            listOf("/system/lib/libjpeg.so", "/system/lib64/libjpeg.so")
        }
        for (path in candidates) {
            if (!File(path).exists()) continue
            try {
                System.load(path)
                Timber.i("[PluviaApp]: Preloaded $path")
                return
            } catch (e: Throwable) {
                Timber.w(e, "[PluviaApp]: System.load($path) failed")
            }
        }
        Timber.w("[PluviaApp]: Could not preload system libjpeg.so (none of the candidate paths worked)")
    }
}
