package app.gamenative.service.download

import app.gamenative.data.DepotInfo
import app.gamenative.data.DownloadInfo
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import app.gamenative.data.GameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single entry point for ALL store game downloads in GameNative.
 *
 * Every real download pipeline runs in Rust inside `libgndownload.so`
 * (`app/src/main/cpp/gn-download/rust`); this service is the only Kotlin↔native boundary:
 *
 * - [downloadSteamApp] — Steam CDN depot downloads. JavaSteam stays the CM client: depot keys,
 *   manifest request codes and the CDN server list are resolved here through `SteamApps` /
 *   `SteamContent`, then the Rust engine (ported from Bannerlator's `bl-steam-client` depot
 *   pipeline) fetches manifests + chunks, decrypts/decompresses/verifies and writes files.
 *   The on-disk journal keeps the old JavaSteam DepotDownloader `.DepotDownloader/` format so
 *   in-progress downloads resume across the swap.
 * - [downloadGogChunks] / [downloadEpicChunks] / [downloadAmazonFiles] — the byte-fetching
 *   engines for the other stores; the store managers keep manifest/auth/post-install logic.
 */
object GameDownloadService {

    private const val TAG = "GameDownloadService"

    /** Thrown when a native download run finishes with `success = false` (not a cancel). */
    class DownloadFailedException(message: String) : Exception(message)

    // ─────────────────────────────────────────────────────────────────────────────
    // Steam
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Downloads (or, when [isUpdateOrVerify], re-verifies) [selectedDepots] of [appId] into
     * [installDir], reporting progress into [downloadInfo] exactly like the old
     * `DepotDownloader` listener did (per-depot delta bytes + per-depot fraction).
     *
     * Returns normally on success; throws [DownloadFailedException] on failure and
     * [kotlinx.coroutines.CancellationException] when the calling job is cancelled.
     */
    suspend fun downloadSteamApp(
        appId: Int,
        selectedDepots: Map<Int, DepotInfo>,
        branch: String,
        branchPassword: String?,
        installDir: String,
        isUpdateOrVerify: Boolean,
        depotIdToIndex: Map<Int, Int>,
        downloadInfo: DownloadInfo,
        maxWorkers: Int,
        processWorkers: Int,
        parentScope: CoroutineScope,
    ) {
        val steamClient = SteamService.instance?.steamClient
            ?: throw DownloadFailedException("Steam client not available")
        val steamApps = steamClient.getHandler(SteamApps::class.java)
            ?: throw DownloadFailedException("SteamApps handler not available")
        val steamContent = steamClient.getHandler(SteamContent::class.java)
            ?: throw DownloadFailedException("SteamContent handler not available")

        // ── 1. Resolve the CDN server list (JavaSteam CM call) ──────────────────
        val servers = resolveCdnServers(steamContent, parentScope)
        if (servers.isEmpty()) throw DownloadFailedException("No CDN servers available")

        // ── 2. Resolve per-depot (gid, depot key, manifest request code) ────────
        val depotsJson = JSONArray()
        val resolvedDepotIds = mutableListOf<Int>()
        for ((depotId, depot) in selectedDepots.toSortedMap()) {
            val gid = resolveManifestGid(steamApps, appId, depotId, depot, branch, branchPassword)
            if (gid == 0L) {
                Timber.tag(TAG).w("Skipping depot $depotId: no manifest gid for branch $branch")
                continue
            }

            val keyCallback = steamApps.getDepotDecryptionKey(depotId, appId).await()
            if (keyCallback.result != EResult.OK || keyCallback.depotKey.size != 32) {
                Timber.tag(TAG).w("Skipping depot $depotId: depot key denied (${keyCallback.result})")
                continue
            }

            val requestCode = fetchManifestRequestCode(
                steamContent, depotId, appId, gid, branch, parentScope,
            )

            depotsJson.put(
                JSONObject()
                    .put("depot_id", depotId)
                    // gid/code are uint64 in Steam's protocol but signed Longs here; send them
                    // as unsigned decimal strings so high-bit values survive JSON → Rust.
                    .put("manifest_id", java.lang.Long.toUnsignedString(gid))
                    .put("depot_key_hex", keyCallback.depotKey.toHex())
                    .put("manifest_request_code", java.lang.Long.toUnsignedString(requestCode)),
            )
            resolvedDepotIds.add(depotId)
        }
        if (depotsJson.length() == 0) {
            throw DownloadFailedException("No entitled depots to download")
        }

        val plan = JSONObject()
            .put("install_dir", installDir)
            .put("ca_bundle_path", "")
            // fresh = discard the journal entries for these depots and re-validate every
            // existing chunk on disk — the old engine's update/verify semantics.
            .put("fresh", isUpdateOrVerify)
            .put("max_workers", maxWorkers)
            .put("process_workers", processWorkers)
            .put("servers", serversToJson(servers))
            .put("depots", depotsJson)
            .toString()

        // ── 3. Run the native engine, mapping callbacks onto DownloadInfo ────────
        runNativeSteamDownload(
            plan = plan,
            appId = appId,
            branch = branch,
            steamContent = steamContent,
            depotIdToIndex = depotIdToIndex,
            downloadInfo = downloadInfo,
            parentScope = parentScope,
        )
    }

    private suspend fun runNativeSteamDownload(
        plan: String,
        appId: Int,
        branch: String,
        steamContent: SteamContent,
        depotIdToIndex: Map<Int, Int>,
        downloadInfo: DownloadInfo,
        parentScope: CoroutineScope,
    ) {
        // Track cumulative per-depot bytes to calculate deltas (same unit the engine reports:
        // decompressed chunk bytes written; totalExpectedBytes is the uncompressed depot size
        // set in SteamService — same unit, so the bar reaches 100% exactly at completion).
        val depotCumulativeBytes = ConcurrentHashMap<Int, Long>()
        // First progress callback ends the "Preparing depots" key-prep phase (owner-pinned, so a
        // late callback from an unwound run cannot wipe a newer attempt's message).
        val keyPrepCleared = AtomicBoolean(false)

        val listener = object : NativeSteamDownloadListener {
            override fun onProgress(
                depotId: Int,
                depotDone: Long,
                depotTotal: Long,
                depotsDone: Int,
                depotsTotal: Int,
                verifying: Boolean,
            ) {
                if (keyPrepCleared.compareAndSet(false, true)) {
                    SteamService.clearDepotKeyPrep(downloadInfo.gameId, owner = downloadInfo)
                }
                if (verifying) {
                    // Verified-existing bytes: these were already counted in the persisted
                    // snapshot this run resumed from. Crediting them again double-counts and
                    // the bar races to 100% while remaining chunks are still downloading.
                    // Only track the high-water so later real downloads delta from it.
                    depotCumulativeBytes.merge(depotId, depotDone, ::maxOf)
                } else {
                    // Parallel callbacks can arrive out of order; read-check-set must be
                    // atomic or two threads credit overlapping deltas. Clamp to the
                    // high-water so a late, smaller depotDone credits nothing.
                    val delta = synchronized(depotCumulativeBytes) {
                        val previous = depotCumulativeBytes[depotId] ?: 0L
                        val newHigh = maxOf(previous, depotDone)
                        depotCumulativeBytes[depotId] = newHigh
                        newHigh - previous
                    }
                    if (delta > 0L) {
                        downloadInfo.updateBytesDownloaded(delta, System.currentTimeMillis())
                    }
                }
                if (depotTotal > 0L) {
                    depotIdToIndex[depotId]?.let { index ->
                        downloadInfo.setProgress(
                            (depotDone.toFloat() / depotTotal.toFloat()).coerceIn(0f, 1f),
                            index,
                        )
                    }
                }
                downloadInfo.persistProgressSnapshot()
            }

            override fun refreshManifestRequestCode(depotId: Int, manifestId: Long): Long {
                return try {
                    kotlinx.coroutines.runBlocking {
                        fetchManifestRequestCode(
                            steamContent, depotId, appId, manifestId, branch, this,
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "refreshManifestRequestCode failed for depot $depotId")
                    0L
                }
            }

            override fun onComplete(
                success: Boolean,
                error: String,
                bytesWritten: Long,
                depotsCompleted: Int,
                depotsSkipped: Int,
            ) = Unit // handled by the suspension below
        }

        // The handle owns a registry slot in native; release it exactly once no matter
        // how the run ends (success, failure, or cancellation).
        var handle = 0L
        try {
            suspendCancellableCoroutine { cont ->
                val completionListener = object : NativeSteamDownloadListener by listener {
                    override fun onComplete(
                        success: Boolean,
                        error: String,
                        bytesWritten: Long,
                        depotsCompleted: Int,
                        depotsSkipped: Int,
                    ) {
                        if (success) {
                            Timber.tag(TAG).i(
                                "Steam download for app $appId complete: $bytesWritten bytes, " +
                                    "$depotsCompleted depots completed, $depotsSkipped skipped",
                            )
                            if (cont.isActive) cont.resume(Unit)
                        } else {
                            if (cont.isActive) {
                                cont.resumeWithException(DownloadFailedException(error.ifEmpty { "download failed" }))
                            }
                        }
                    }
                }

                handle = NativeSteamDownload.start(plan, completionListener)
                if (handle == 0L) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            DownloadFailedException("native Steam engine failed to start"),
                        )
                    }
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    NativeSteamDownload.cancel(handle)
                }
            }
        } finally {
            if (handle != 0L) {
                NativeSteamDownload.release(handle)
            }
        }
    }

    private suspend fun resolveCdnServers(
        steamContent: SteamContent,
        parentScope: CoroutineScope,
    ): List<Server> {
        return try {
            val cellId = runCatching { app.gamenative.PrefManager.cellId }.getOrDefault(0)
            steamContent.getServersForSteamPipe(
                cellId = cellId.takeIf { it > 0 },
                maxNumServers = 20,
                parentScope = parentScope,
            ).await()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GetServersForSteamPipe failed")
            emptyList()
        }
    }

    private fun serversToJson(servers: List<Server>): JSONArray {
        val out = JSONArray()
        for (server in servers) {
            val host = server.host ?: continue
            if (host.isEmpty() || server.steamChinaOnly) continue
            out.put(
                JSONObject()
                    .put("host", host)
                    .put("vhost", server.vHost ?: "")
                    .put("type", server.type ?: "")
                    .put("cell_id", server.cellId)
                    .put("steam_china_only", false)
                    .put(
                        "https_support",
                        if (server.protocol == Server.ConnectionProtocol.HTTPS) "mandatory" else "",
                    ),
            )
        }
        return out
    }

    private suspend fun fetchManifestRequestCode(
        steamContent: SteamContent,
        depotId: Int,
        appId: Int,
        manifestId: Long,
        branch: String,
        parentScope: CoroutineScope,
    ): Long {
        return try {
            steamContent.getManifestRequestCode(
                depotId = depotId,
                appId = appId,
                manifestId = manifestId,
                branch = branch,
                branchPasswordHash = null,
                parentScope = parentScope,
            ).await()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getManifestRequestCode failed for depot $depotId")
            0L
        }
    }

    /**
     * Resolves the manifest gid for [depot] on [branch], including password-protected beta
     * branches (via `checkAppBetaPassword` + `picsGetPrivateBeta`, mirroring the old
     * DepotDownloader's private-beta depot-section path).
     */
    private suspend fun resolveManifestGid(
        steamApps: SteamApps,
        appId: Int,
        depotId: Int,
        depot: DepotInfo,
        branch: String,
        branchPassword: String?,
    ): Long {
        depot.manifests[branch]?.gid?.takeIf { it != 0L }?.let { return it }

        if (branch.equals("public", ignoreCase = true)) {
            return 0L
        }

        // Non-passworded branch with no gid → nothing to do.
        if (branchPassword.isNullOrBlank()) {
            // Last-resort: public manifest (keeps the old weight-calculation fallback alive).
            return depot.manifests["public"]?.gid ?: 0L
        }

        // Passworded branch: decrypt via the private beta depot section.
        return try {
            val keys = SteamService.checkPrivateBranchPassword(appId, branchPassword)
            val branchKey = keys[branch] ?: keys.values.firstOrNull() ?: return 0L
            val accessToken = steamApps.picsGetAccessTokens(appId).await()
                .appTokens[appId] ?: 0L
            val privateBeta = steamApps.picsGetPrivateBeta(
                appId, accessToken, branch, branchKey,
            ).await()
            val gidNode = privateBeta.depotSection[depotId.toString()]["manifests"][branch]["gid"]
            gidNode.asUnsignedLong()?.toLong() ?: 0L
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "private beta gid resolution failed for depot $depotId")
            0L
        }
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(digits[(b.toInt() shr 4) and 0xf])
            out.append(digits[b.toInt() and 0xf])
        }
        return out.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // GOG
    // ─────────────────────────────────────────────────────────────────────────────

    /** True when the native GOG engine is loaded. */
    fun isGogNativeAvailable(): Boolean = NativeGogDownload.isAvailable()

    /** Starts a native GOG chunk-download loop (base install, DLC, or redist assembly). */
    fun downloadGogChunks(
        kind: Int,
        depotManifests: Array<String>,
        cdnBase: String,
        installDir: String,
        skipPaths: Array<String>,
        maxWorkers: Int,
        processWorkers: Int,
        sortLargestFirst: Boolean,
        label: String,
        listener: NativeGogDownloadListener,
    ): Long = NativeGogDownload.start(
        kind, depotManifests, cdnBase, installDir, skipPaths, "",
        maxWorkers, processWorkers, sortLargestFirst, label, listener,
    )

    fun cancelGogDownload(handle: Long) = NativeGogDownload.cancel(handle)

    fun releaseGogDownload(handle: Long) = NativeGogDownload.release(handle)

    // ─────────────────────────────────────────────────────────────────────────────
    // Epic
    // ─────────────────────────────────────────────────────────────────────────────

    /** Blocking Epic chunk fetch for `pendingFileIdx` (fills `<installDir>/.chunks`). */
    fun downloadEpicChunks(
        manifest: ByteArray,
        installDir: String,
        cdnPrefixes: Array<String>,
        pendingFileIdx: IntArray,
        expectedChunks: Int,
        expectedBytes: Long,
        maxWorkers: Int,
        processWorkers: Int,
        cancel: AtomicBoolean?,
        listener: NativeEpicDownload.Listener,
    ): NativeEpicDownload.Result = NativeEpicDownload.run(
        manifest, installDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes, "",
        maxWorkers, processWorkers, cancel, listener,
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Amazon
    // ─────────────────────────────────────────────────────────────────────────────

    /** Blocking Amazon file-download run. `planJson` = `[{relPath, url, size, sha256hex}]`. */
    fun downloadAmazonFiles(
        planJson: String,
        installDir: String,
        maxWorkers: Int,
        processWorkers: Int,
        isCancelled: NativeAmazonCancelCheck?,
        listener: NativeAmazonDownloadListener,
    ): NativeAmazonDownload.RunResult = NativeAmazonDownload.runBlocking(
        planJson, installDir, "", maxWorkers, processWorkers, isCancelled, listener,
    )

    // ═════════════════════════════════════════════════════════════════════════
    // Download queue (one active download across Steam/Epic/GOG/Amazon, queue-
    // managed auto-pause/resume, transient-failure auto-retry, wake-lock while
    // transferring). Merged from the former GameDownloadService object.
    // ═════════════════════════════════════════════════════════════════════════

    data class DownloadEntry(
        val gameSource: GameSource,
        val gameId: String,
        val downloadInfo: DownloadInfo
    )

    /**
     * Listener interface for services to handle resume requests.
     */
    interface ResumeListener {
        fun onResumeRequested(gameSource: GameSource, gameId: String)
    }

    private val activeDownloads = ConcurrentHashMap<String, DownloadEntry>()
    private val resumeListeners = ConcurrentHashMap<GameSource, ResumeListener>()

    // ── Automatic retry of transient failures ────────────────────────────────
    // A download that fails with a TRANSIENT error (timeout, reset, 5xx/429)
    // is restarted automatically up to MAX_AUTO_RETRIES times with backoff,
    // keeping its queue slot. Permanent errors (404/401/403, no depot key,
    // disk full, parse/decrypt, …) fail immediately, as before.
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val retryJobs = ConcurrentHashMap<String, Job>()

    private const val MAX_AUTO_RETRIES = 2
    private const val RETRY_BACKOFF_FIRST_MS = 30_000L
    private const val RETRY_BACKOFF_LATER_MS = 120_000L

    // ── Keep the device awake while transferring ─────────────────────────────
    // The store services run in the foreground, but that does NOT stop the CPU
    // from suspending when the device sleeps: without a wake lock a multi-hour
    // download stalls minutes after the screen turns off, and Doze can suspend
    // the network of a non-exempt app. While at least one download is
    // transferring (or a retry backoff is pending):
    //   - DownloadForegroundService anchors the process (started/stopped here,
    //     so survival does not depend on any store service), and
    //   - a partial WakeLock + low-latency WifiLock is held — but ONLY while
    //     the device is charging: keeping a phone awake for hours on battery
    //     drains it hot and fast, so on battery the download degrades to
    //     "runs while the device is awake" and resumes from its snapshot.
    private var appContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var downloadFgRunning = false

    /** Failsafe cap; normal operation releases as soon as the queue drains. */
    private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

    private val powerStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(queueLock) {
                updateWakeLockLocked()
            }
        }
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            appContext!!.registerReceiver(
                powerStateReceiver,
                android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                },
            )
        }
    }

    private fun isDeviceCharging(ctx: Context): Boolean {
        val battery: Intent? = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    /** Must be called under [queueLock] after every mutation that can change activity. */
    private fun updateWakeLockLocked() {
        val ctx = appContext ?: return
        val anyTransferring = activeDownloads.values.any { it.downloadInfo.isActive() } || retryJobs.isNotEmpty()

        // Process anchor: independent of charging state.
        if (anyTransferring && !downloadFgRunning) {
            DownloadForegroundService.start(ctx)
            downloadFgRunning = true
        } else if (!anyTransferring && downloadFgRunning) {
            DownloadForegroundService.stop(ctx)
            downloadFgRunning = false
        }

        // CPU/Wi-Fi keep-alive: only on external power.
        val wantWakeLock = anyTransferring && isDeviceCharging(ctx)
        if (wantWakeLock) {
            if (wakeLock == null) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameNative::DownloadWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GameNative:DownloadWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Timber.i("[GameDownloadService] Wake/Wi-Fi lock acquired (download transferring, charging)")
            }
        } else {
            if (wakeLock != null && anyTransferring) {
                Timber.i("[GameDownloadService] Wake/Wi-Fi lock released (device unplugged; download runs while awake)")
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    /**
     * Classify a failure message. Default is NOT transient: an unknown error
     * fails fast instead of looping. Permanent markers are checked first so a
     * message containing both (e.g. "non-200 HTTP status (404)") fails fast.
     */
    fun isTransientFailure(message: String?): Boolean {
        val msg = message?.lowercase() ?: return false
        val permanent = listOf(
            "(401", "(403", "(404", " 401", " 403", " 404",
            "no depot key", "no manifest gid", "unsafe path",
            "no space", "disk full", "enospc",
            "decrypt", "parse failed", "manifest parse",
            "cancelled", "canceled",
        )
        if (permanent.any { msg.contains(it) }) return false
        val transient = listOf(
            "timed out", "timeout", "connection reset", "connection refused",
            "connection aborted", "broken pipe", "unexpected eof", "eof while",
            "dns", "unreachable", "network", "temporarily", "stalled",
            "(429", "(500", "(502", "(503", "(504",
            " 429", " 500", " 502", " 503", " 504",
            "no process-pool verdict", "no cdn servers",
        )
        return transient.any { msg.contains(it) }
    }

    private fun retryBackoffMs(attempt: Int): Long {
        return if (attempt <= 1) RETRY_BACKOFF_FIRST_MS else RETRY_BACKOFF_LATER_MS
    }

    /**
     * Report a download failure. Returns true when an automatic retry was
     * scheduled — the caller must then KEEP the queue entry (and its active-map
     * entry, so the UI shows the download as Queued) and skip its own
     * failure/unregister handling. Returns false for permanent failures and
     * after [MAX_AUTO_RETRIES] attempts; the caller then fails the download
     * exactly as before (which unregisters and advances the queue).
     *
     * Must be called BEFORE the store removes its active-map entry: the retry
     * marker sets wasAutoPaused, which is what keeps that entry.
     */
    fun reportFailure(gameSource: GameSource, gameId: String, errorMessage: String?): Boolean {
        val key = makeKey(gameSource, gameId)
        synchronized(queueLock) {
            val entry = activeDownloads[key] ?: return false
            if (!isTransientFailure(errorMessage)) {
                retryAttempts.remove(key)
                return false
            }
            val attempt = (retryAttempts[key] ?: 0) + 1
            val listener = resumeListeners[gameSource]
            if (attempt > MAX_AUTO_RETRIES || listener == null) {
                Timber.w("[GameDownloadService] Not retrying $gameSource $gameId (attempt $attempt): $errorMessage")
                retryAttempts.remove(key)
                return false
            }
            retryAttempts[key] = attempt
            val backoffMs = retryBackoffMs(attempt)
            Timber.i("[GameDownloadService] Transient failure for $gameSource $gameId; auto-retry $attempt/$MAX_AUTO_RETRIES in ${backoffMs}ms: $errorMessage")
            entry.downloadInfo.markQueuedForRetry("Download interrupted — retrying in ${backoffMs / 1000}s ($attempt/$MAX_AUTO_RETRIES)")
            entry.downloadInfo.setAutoResumeCallback {
                listener.onResumeRequested(gameSource, gameId)
            }
            val job = retryScope.launch {
                delay(backoffMs)
                synchronized(queueLock) {
                    retryJobs.remove(key)
                    // Fire only if the entry is still registered (user may have
                    // cancelled during the backoff) and nothing else is active.
                    // Otherwise it stays queued and normal progression resumes it.
                    if (activeDownloads[key] == entry &&
                        activeDownloads.values.none { it.downloadInfo.isActive() }
                    ) {
                        Timber.i("[GameDownloadService] Auto-retrying $gameSource download for $gameId")
                        entry.downloadInfo.triggerAutoResume()
                    }
                    updateWakeLockLocked()
                }
            }
            retryJobs[key] = job
            updateWakeLockLocked()
            return true
        }
    }

    /**
     * Serializes every queue state transition (pause-all + register, remove + resume).
     * Without it, two concurrent registrations can each scan before either inserts and
     * BOTH stay active, breaking the one-at-a-time contract.
     */
    private val queueLock = Any()

    private fun makeKey(gameSource: GameSource, gameId: String): String {
        return "${gameSource.name}_$gameId"
    }

    /**
     * Register a resume listener for a specific game source.
     * Each service should register its own listener to handle resume requests.
     */
    fun registerResumeListener(gameSource: GameSource, listener: ResumeListener) {
        resumeListeners[gameSource] = listener
        Timber.i("[GameDownloadService] Registered resume listener for $gameSource")
        synchronized(queueLock) {
            // A queued entry of this source may have been waiting for this listener
            // (service was restarted while queued). Only resume when nothing else is
            // active, or the one-at-a-time contract breaks.
            if (activeDownloads.values.none { it.downloadInfo.isActive() }) {
                resumeNextLocked()
            }
            updateWakeLockLocked()
        }
    }

    /**
     * Unregister a resume listener for a specific game source.
     */
    fun unregisterResumeListener(gameSource: GameSource) {
        resumeListeners.remove(gameSource)
        Timber.i("[GameDownloadService] Unregistered resume listener for $gameSource")
    }

    /**
     * Register a new download. This will auto-pause all other active downloads.
     */
    fun registerDownload(
        gameSource: GameSource,
        gameId: String,
        downloadInfo: DownloadInfo
    ) {
        val key = makeKey(gameSource, gameId)

        synchronized(queueLock) {
            // Set queue identifiers on the DownloadInfo so it can unregister itself
            downloadInfo.setQueueIdentifiers(gameSource, gameId)

            // A fresh registration supersedes any pending retry of the previous
            // entry for this key. Attempt history is intentionally KEPT: the
            // auto-retry's own resume listener re-registers through this path,
            // and resetting here would retry forever.
            retryJobs.remove(key)?.cancel()

            // Auto-pause all other active downloads. Skip entries whose transfer is
            // already done and which are only syncing saves (post-install): pausing
            // one kills its finishing job while the entry stays queued, and the later
            // auto-resume re-runs the whole download (verify 1/N back to 100%) even
            // though the game was complete.
            activeDownloads.forEach { (existingKey, entry) ->
                if (existingKey != key && entry.downloadInfo.isActive() && !entry.downloadInfo.isPostInstallSyncing()) {
                    Timber.i("[GameDownloadService] Auto-pausing ${entry.gameSource} download for ${entry.gameId}")
                    entry.downloadInfo.pause(message = "Paused for new download", autoPaused = true)
                }
            }

            // Register the new download
            activeDownloads[key] = DownloadEntry(gameSource, gameId, downloadInfo)
            Timber.i("[GameDownloadService] Registered ${gameSource} download for $gameId")
            updateWakeLockLocked()
        }
    }

    /**
     * Unregister a download when it completes or is cancelled.
     * Automatically resumes the next paused download if available.
     */
    fun unregisterDownload(gameSource: GameSource, gameId: String) {
        val key = makeKey(gameSource, gameId)
        synchronized(queueLock) {
            // Idempotent: both the success path and the failure/cancel paths may call this for
            // the same download. Without the guard the second call would resume ANOTHER paused
            // download and break the one-at-a-time invariant.
            if (activeDownloads.remove(key) == null) {
                return
            }
            Timber.i("[GameDownloadService] Unregistered ${gameSource} download for $gameId")
            // Terminal state for this download (success, cancel, permanent
            // failure): clear retry bookkeeping and any pending backoff job.
            retryAttempts.remove(key)
            retryJobs.remove(key)?.cancel()

            // Auto-resume the first paused download (if any)
            resumeNextLocked()
            updateWakeLockLocked()
        }
    }

    /**
     * Resume the first auto-paused entry whose service has a resume listener installed.
     * Caller must hold [queueLock]. Entries without a listener stay queued — they are
     * retried when that service registers its listener (service restart path).
     */
    private fun resumeNextLocked() {
        // Only advance the queue when nothing is transferring: unregistering an
        // inactive (queued) entry while the active download is still running
        // must not start another download — the queue advances when the active
        // one finishes. Without this, dequeueing one of several queued entries
        // resumes the next queued entry and preempts the active download.
        if (activeDownloads.values.any { it.downloadInfo.isActive() }) return
        val nextDownload = activeDownloads.values.firstOrNull { entry ->
            entry.downloadInfo.wasAutoPaused() && resumeListeners.containsKey(entry.gameSource)
        } ?: return

        Timber.i("[GameDownloadService] Auto-resuming ${nextDownload.gameSource} download for ${nextDownload.gameId}")
        val listener = resumeListeners[nextDownload.gameSource] ?: return
        nextDownload.downloadInfo.setAutoResumeCallback {
            listener.onResumeRequested(nextDownload.gameSource, nextDownload.gameId)
        }
        nextDownload.downloadInfo.triggerAutoResume()
    }

    /**
     * Remove every entry belonging to a service being torn down. If the removed set
     * included the only ACTIVE download, advance the queue: removed entries can no
     * longer be selected, so the resume can only land on another (live) source —
     * without it, a queued download would wait forever for an unregister that
     * already happened.
     */
    fun unregisterAllForSource(gameSource: GameSource) {
        synchronized(queueLock) {
            val keys = activeDownloads.filterValues { it.gameSource == gameSource }.keys
            keys.forEach {
                activeDownloads.remove(it)
                retryAttempts.remove(it)
                retryJobs.remove(it)?.cancel()
            }
            if (keys.isNotEmpty()) {
                Timber.i("[GameDownloadService] Removed ${keys.size} $gameSource queue entr(ies) on service teardown")
            }
            if (activeDownloads.values.none { it.downloadInfo.isActive() }) {
                resumeNextLocked()
            }
            updateWakeLockLocked()
        }
    }

    /**
     * Get all currently active downloads across all services.
     */
    fun getActiveDownloads(): Map<String, DownloadEntry> {
        return HashMap(activeDownloads)
    }

    /**
     * Get the count of active downloads.
     */
    fun getActiveDownloadCount(): Int {
        return activeDownloads.count { it.value.downloadInfo.isActive() }
    }

    /**
     * Check if a specific download is registered.
     */
    fun isDownloadRegistered(gameSource: GameSource, gameId: String): Boolean {
        val key = makeKey(gameSource, gameId)
        return activeDownloads.containsKey(key)
    }

    /**
     * Get download info for a specific game.
     */
    fun getDownloadInfo(gameSource: GameSource, gameId: String): DownloadInfo? {
        val key = makeKey(gameSource, gameId)
        return activeDownloads[key]?.downloadInfo
    }
}
