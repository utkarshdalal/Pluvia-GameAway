package app.gamenative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

data class DownloadInfo(
    val jobCount: Int = 1,
    val gameId: Int,
    var downloadingAppIds: CopyOnWriteArrayList<Int>,
) {
    @Volatile
    private var downloadJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadProgressListeners = CopyOnWriteArrayList<(Float) -> Unit>()
    private val progresses: Array<Float> = Array(jobCount) { 0f }

    // Reservation-based persistence scheduler
    private val nextWriteTime = AtomicLong(0L)
    private var persistenceJob: Job? = null
    private val persistenceGeneration = AtomicInteger(0)

    private val weights    = FloatArray(jobCount) { 1f }     // ⇐ new
    private var weightSum  = jobCount.toFloat()

    // === Bytes / speed tracking for more stable ETA ===
    private var totalExpectedBytes: Long = 0L
    private var bytesDownloaded: Long = 0L
    private var persistencePath: String? = null

    private data class SpeedSample(val timeMs: Long, val bytes: Long)

    private val speedSamples = CopyOnWriteArrayList<SpeedSample>()
    private var emaSpeedBytesPerSec: Double = 0.0
    private var hasEmaSpeed: Boolean = false
    @Volatile
    private var isActive: Boolean = true
    @Volatile
    private var currentStatusMessage: String = ""
    private val postInstallSyncing = MutableStateFlow(false)

    private var wasAutoPaused: Boolean = false
    private var autoResumeCallback: (() -> Unit)? = null

    // For GameDownloadService integration
    private var queueGameSource: GameSource? = null
    private var queueGameId: String? = null

    fun cancel() {
        cancel("Cancelled by user")
    }

    fun pause(message: String = "Paused", autoPaused: Boolean = false) {
        persistProgressSnapshot()
        setPostInstallSyncing(false)
        resetSpeedTracking()
        wasAutoPaused = autoPaused
        if (autoPaused) {
            updateStatusMessage("Queued")
        }
        // Synchronized with setDownloadJob so deactivate+job-cancel is atomic
        // against a job assignment happening on another dispatcher.
        synchronized(this) {
            setActive(false)
            downloadJob?.cancel(CancellationException(message))
        }

        // If user manually paused (not auto-paused), unregister from queue to resume next download
        if (!autoPaused && queueGameSource != null && queueGameId != null) {
            app.gamenative.service.download.GameDownloadService.unregisterDownload(queueGameSource!!, queueGameId!!)
        }
    }

    fun failedToDownload() {
        cancel("Failed to download")
    }

    fun cancel(message: String) {
        // Mark as inactive and clear speed tracking so a future resume
        // does not use stale samples.
        setActive(false)
        setPostInstallSyncing(false)
        resetSpeedTracking()
        // A cancelled download is no longer queue-managed: clear the auto-paused
        // flag so nothing (UI keep-logic, queue scans) treats it as queued.
        wasAutoPaused = false

        // Unregister from queue to resume next download
        if (queueGameSource != null && queueGameId != null) {
            app.gamenative.service.download.GameDownloadService.unregisterDownload(queueGameSource!!, queueGameId!!)
        }

        // The snapshot write hits the (possibly saturated) install volume and the
        // job cancel cascades through many continuations; callers include UI click
        // handlers on the main thread, so both must run off it (ANR otherwise).
        ioScope.launch {
            // Signal cancellation before the possibly-slow snapshot write, so a
            // restarted download for the same path can't overlap the dying job.
            downloadJob?.cancel(CancellationException(message))
            // Persist the most recent progress so a resume can pick up where it left off.
            persistProgressSnapshot()
        }
    }

    fun setDownloadJob(job: Job) {
        // Race guard: the queue may have auto-paused (or the user paused/cancelled) this
        // download AFTER the coroutine launched but BEFORE the job was assigned here.
        // pause()/cancel() then found a null job and cancelled nothing — cancel it now
        // on assignment so the two-downloads-at-once window can't happen. Synchronized
        // with pause()'s deactivate-and-cancel so the two can never interleave: either
        // pause wins and this cancels on assignment, or assignment wins and pause
        // cancels the live job.
        synchronized(this) {
            downloadJob = job
            if (!isActive) {
                job.cancel(CancellationException(if (wasAutoPaused) "Paused for new download" else "Paused"))
            }
        }
    }

    suspend fun awaitCompletion(timeoutMs: Long = 5000L) {
        withTimeoutOrNull(timeoutMs) { downloadJob?.join() }
    }

    fun getProgress(): Float {
        // Always use bytes-based progress when available for accuracy
        if (totalExpectedBytes > 0L) {
            val bytesProgress = (bytesDownloaded.toFloat() / totalExpectedBytes.toFloat()).coerceIn(0f, 1f)
            return bytesProgress
        }

        // Fallback to depot-based progress only if we don't have byte tracking
        var total = 0f
        for (i in progresses.indices) {
            total += progresses[i] * weights[i]   // weight each depot
        }
        return if (weightSum == 0f) 0f else total / weightSum
    }


    fun setProgress(amount: Float, jobIndex: Int = 0) {
        progresses[jobIndex] = amount
        emitProgressChange()
    }

    fun setWeight(jobIndex: Int, weightBytes: Long) {        // tiny helper
        weights[jobIndex] = weightBytes.toFloat()
        weightSum = weights.sum()
    }

    // --- Bytes / speed / ETA helpers ---

    fun setTotalExpectedBytes(bytes: Long) {
        totalExpectedBytes = if (bytes < 0L) 0L else bytes
    }

    /**
     * Initialize bytesDownloaded with a persisted value (used on resume).
     */
    fun initializeBytesDownloaded(value: Long) {
        bytesDownloaded = if (value < 0L) 0L else value
    }

    /**
     * Record that [deltaBytes] have just been downloaded at [timestampMs].
     * This is used to derive recent download speed over a sliding window.
     */
    fun setPersistencePath(appDirPath: String?) {
        persistencePath = appDirPath
    }

    fun persistProgressSnapshot() {
        persistencePath?.let { persistBytesDownloaded(it) }
    }

    fun updateBytesDownloaded(
        deltaBytes: Long,
        timestampMs: Long = System.currentTimeMillis(),
        trackSpeed: Boolean = true,
    ) {
        if (!isActive) return
        if (deltaBytes <= 0L) {
            // Still record a sample to advance the time window, but do not change the count.
            if (trackSpeed) {
                addSpeedSample(timestampMs)
            }
            return
        }

        bytesDownloaded += deltaBytes
        if (bytesDownloaded < 0L) {
            bytesDownloaded = 0L
        }
        if (trackSpeed) {
            addSpeedSample(timestampMs)
        }
    }

    fun updateStatusMessage(message: String?) {
        currentStatusMessage = message ?: ""
        emitProgressChange()
    }

    fun getCurrentStatusMessage(): String = currentStatusMessage

    fun setPostInstallSyncing(syncing: Boolean) {
        postInstallSyncing.value = syncing
    }

    fun isPostInstallSyncing(): Boolean = postInstallSyncing.value

    fun getPostInstallSyncingFlow(): StateFlow<Boolean> = postInstallSyncing

    private fun addSpeedSample(timestampMs: Long) {
        speedSamples.add(SpeedSample(timestampMs, bytesDownloaded))
        trimOldSamples(timestampMs)
    }

    private fun trimOldSamples(nowMs: Long, windowMs: Long = 30_000L) {
        val cutoff = nowMs - windowMs
        while (speedSamples.isNotEmpty() && speedSamples.first().timeMs < cutoff) {
            speedSamples.removeAt(0)
        }
    }

    fun resetSpeedTracking() {
        speedSamples.clear()
        emaSpeedBytesPerSec = 0.0
        hasEmaSpeed = false
    }

    fun setActive(active: Boolean) {
        isActive = active
        if (!active) {
            resetSpeedTracking()
        }
    }

    fun wasAutoPaused(): Boolean = wasAutoPaused

    /**
     * Queue-managed retry marker: like an auto-pause, but WITHOUT touching the
     * download job. Called from the queue's failure-retry path, which runs inside
     * the failing job's own catch block — cancelling the job there would cancel
     * the very coroutine that is still handling the failure. The job is already
     * finishing; only the queued-state and the UI status need to change.
     */
    fun markQueuedForRetry(statusMessage: String) {
        setActive(false)
        setPostInstallSyncing(false)
        wasAutoPaused = true
        updateStatusMessage(statusMessage)
    }

    /**
     * Terminal success: clear the queue-managed paused state. A completed
     * download must never remain queued — every keep-entry path (store
     * finally blocks, Steam's removeDownloadJob) keys off wasAutoPaused,
     * and a stray auto-pause landing in a post-install race window would
     * otherwise leave a completed game stuck as a Paused row forever.
     */
    fun clearQueuedState() {
        wasAutoPaused = false
        autoResumeCallback = null
    }

    fun setAutoResumeCallback(callback: () -> Unit) {
        autoResumeCallback = callback
    }

    fun triggerAutoResume() {
        if (wasAutoPaused) {
            wasAutoPaused = false
            autoResumeCallback?.invoke()
            autoResumeCallback = null
        }
    }

    /**
     * Set the queue identifiers for this download.
     * This allows the DownloadInfo to unregister itself from the queue when paused/cancelled.
     */
    fun setQueueIdentifiers(gameSource: GameSource, gameId: String) {
        queueGameSource = gameSource
        queueGameId = gameId
    }

    fun isActive(): Boolean = isActive

    /**
     * Returns the total expected bytes for the download.
     */
    fun getTotalExpectedBytes(): Long = totalExpectedBytes

    /**
     * Returns the cumulative bytes downloaded so far.
     */
    fun getBytesDownloaded(): Long = bytesDownloaded

    /**
     * Returns a pair of (downloaded bytes, total expected bytes).
     * Returns (0, 0) if total expected bytes is 0 or not yet set.
     */
    fun getBytesProgress(): Pair<Long, Long> {
        return if (totalExpectedBytes > 0L) {
            bytesDownloaded.coerceAtMost(totalExpectedBytes) to totalExpectedBytes
        } else {
            0L to 0L
        }
    }

    /**
     * Returns an ETA in milliseconds based on recent download speed, or null if
     * there is not enough information yet (e.g. just started) or download is inactive.
     */
    fun getEstimatedTimeRemaining(windowSeconds: Int = 30): Long? {
        if (!isActive) return null
        if (totalExpectedBytes <= 0L) return null
        if (bytesDownloaded >= totalExpectedBytes) return null

        val now = System.currentTimeMillis()

        // Snapshot via toTypedArray().toList() so we never call toList() on the COWAL
        // when it's empty (which can crash), and we get a consistent view so another
        // thread can't shrink the list between our size check and first()/last().
        val cutoff = now - windowSeconds * 1000L
        val samples = speedSamples.toTypedArray().filter { it.timeMs >= cutoff }.toList()
        if (samples.size < 2) return null

        val first = samples.first()
        val last = samples.last()
        val elapsedMs = last.timeMs - first.timeMs
        if (elapsedMs <= 0L) return null

        val bytesDelta = last.bytes - first.bytes
        if (bytesDelta <= 0L) return null

        val currentSpeedBytesPerSec = bytesDelta.toDouble() / (elapsedMs.toDouble() / 1000.0)
        if (currentSpeedBytesPerSec <= 0.0) return null

        // Exponential moving average to smooth fluctuations.
        val alpha = 0.3
        val smoothedSpeed = if (!hasEmaSpeed) {
            hasEmaSpeed = true
            emaSpeedBytesPerSec = currentSpeedBytesPerSec
            currentSpeedBytesPerSec
        } else {
            emaSpeedBytesPerSec = alpha * currentSpeedBytesPerSec + (1 - alpha) * emaSpeedBytesPerSec
            emaSpeedBytesPerSec
        }

        if (smoothedSpeed <= 0.0) return null

        val remainingBytes = totalExpectedBytes - bytesDownloaded
        if (remainingBytes <= 0L) return null

        val etaSeconds = remainingBytes / smoothedSpeed
        if (etaSeconds.isNaN() || etaSeconds.isInfinite() || etaSeconds <= 0.0) return null

        return (etaSeconds * 1000.0).toLong()
    }

    fun addProgressListener(listener: (Float) -> Unit) {
        downloadProgressListeners.add(listener)
    }

    fun removeProgressListener(listener: (Float) -> Unit) {
        downloadProgressListeners.remove(listener)
    }

    fun emitProgressChange() {
        for (listener in downloadProgressListeners) {
            listener(getProgress())
        }
    }

    // --- Persistence helpers ---

    companion object {
        private const val PERSISTENCE_DIR = ".DownloadInfo"
        private const val PERSISTENCE_FILE = "bytes_downloaded.txt"
        private const val PERSIST_DEBOUNCE_MS = 10_000L // 10 seconds
    }

    /**
     * Persist bytesDownloaded to a file in the app directory.
     * Debounced to write at most once every 10 seconds to reduce I/O overhead.
     */
    fun persistBytesDownloaded(appDirPath: String) {
        val now = System.currentTimeMillis()
        val reserved = nextWriteTime.get()
        val delayMs = reserved - now

        // If we need to wait, schedule a delayed write
        if (delayMs > 0) {
            val currentGen = persistenceGeneration.get()
            persistenceJob?.cancel()
            persistenceJob = ioScope.launch {
                delay(delayMs)
                // Only write if generation hasn't been invalidated
                if (persistenceGeneration.get() == currentGen) {
                    writePersistedBytes(appDirPath)
                }
            }
            return
        }

        // Reserve the next write time and write immediately. The generation guard
        // keeps a late cancel-snapshot from recreating the resume file after
        // clearPersistedBytesDownloaded() has removed it on completion.
        nextWriteTime.set(now + PERSIST_DEBOUNCE_MS)
        val currentGen = persistenceGeneration.get()
        persistenceJob?.cancel()
        persistenceJob = ioScope.launch {
            if (persistenceGeneration.get() == currentGen) {
                writePersistedBytes(appDirPath)
            }
        }
    }

    /**
     * Internal method to actually write the bytes to disk.
     */
    private fun writePersistedBytes(appDirPath: String) {
        try {
            val dir = File(appDirPath, PERSISTENCE_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, PERSISTENCE_FILE)
            file.writeText(bytesDownloaded.toString())
            val now = System.currentTimeMillis()
            nextWriteTime.set(now + PERSIST_DEBOUNCE_MS)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist bytes downloaded to $appDirPath")
        }
    }

    /**
     * Load persisted bytesDownloaded from file, returns 0 if file doesn't exist or is unreadable.
     */
    fun loadPersistedBytesDownloaded(appDirPath: String): Long {
        return try {
            val file = File(File(appDirPath, PERSISTENCE_DIR), PERSISTENCE_FILE)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                content.toLongOrNull() ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persisted bytes downloaded from $appDirPath")
            0L
        }
    }

    /**
     * Delete the persisted bytes file (called on download completion).
     */
    fun clearPersistedBytesDownloaded(appDirPath: String) {
        // Invalidate any pending persistence to prevent recreating the file
        persistenceGeneration.incrementAndGet()
        persistenceJob?.cancel()
        try {
            val file = File(File(appDirPath, PERSISTENCE_DIR), PERSISTENCE_FILE)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear persisted bytes downloaded from $appDirPath")
        }
    }
}
