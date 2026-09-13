// JNI symbols depend on this package path and class name
// (see app/src/main/cpp/gn-download/rust/src/store_dl/amazon/jni.rs).
package app.gamenative.service.download

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Native Amazon download callbacks; every method runs on a native worker thread.
 */
interface NativeAmazonDownloadListener {

    /**
     * Fired after each committed file (and once up front with the resume-skipped credit).
     * [bytesDone] includes skipped files, like the manager's aggregate counter.
     */
    fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long)

    /** One diagnostic line. */
    fun onLog(line: String)

    /** Fired exactly once when the run succeeds, fails or is cancelled. */
    fun onComplete(success: Boolean, error: String, bytesWritten: Long)
}

/** Kotlin-friendly cancel probe. */
fun interface NativeAmazonCancelCheck {
    fun isCancelled(): Boolean
}

/**
 * JVM-side facade over the Rust Amazon download adapter in `libgndownload.so`.
 *
 * Replaces ONLY the file-fetch pool of `AmazonDownloadManager`; the manager keeps
 * manifest/auth/markers/post-install. [runBlocking] mirrors the old loop's blocking shape:
 * it returns when the run is over and polls the caller's cancel flag meanwhile.
 */
object NativeAmazonDownload {

    const val TAG = "GN_AMAZON_DL"

    /** Cancel-poll period while a run is in flight. */
    private const val CANCEL_POLL_MS = 100L

    class RunResult(
        @JvmField val success: Boolean,
        @JvmField val cancelled: Boolean,
        @JvmField val error: String,
        @JvmField val bytesWritten: Long,
    )

    @Volatile
    private var available: Boolean? = null

    /** True when `libgndownload.so` loads and binds. */
    @JvmStatic
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            GameDownloadNative.ensureLoaded()
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).w("native Amazon engine unavailable — ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        available = ok
        return ok
    }

    /**
     * Run one download to completion. [isCancelled] is polled every [CANCEL_POLL_MS]; the first
     * true flips the native cancel flag and the result reports `cancelled = true`.
     *
     * @param planJson `[{relPath, url, size, sha256hex}]` — every manifest file (the native
     *   side applies the size-based resume-skip itself).
     * @param maxWorkers window size; `<= 0` = native default (8).
     * @param processWorkers sync verify+write threads.
     */
    @JvmStatic
    fun runBlocking(
        planJson: String,
        installDir: String,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        isCancelled: NativeAmazonCancelCheck?,
        listener: NativeAmazonDownloadListener,
    ): RunResult {
        GameDownloadNative.ensureLoaded()
        val latch = CountDownLatch(1)
        var outcome: RunResult? = null
        val bridge = object : NativeAmazonDownloadListener {
            override fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long) {
                listener.onProgress(bytesDone, bytesTotal, filesDone, filesTotal)
            }

            override fun onLog(line: String) {
                listener.onLog(line)
            }

            override fun onComplete(success: Boolean, error: String, bytesWritten: Long) {
                outcome = RunResult(success, false, error, bytesWritten)
                listener.onComplete(success, error, bytesWritten)
                latch.countDown()
            }
        }
        val handle = nativeStart(planJson, installDir, caBundlePath, maxWorkers, processWorkers, bridge)
        if (handle == 0L) {
            // nativeStart already fired onComplete(false, …) for unusable inputs.
            return outcome ?: RunResult(false, false, "native start failed", 0L)
        }
        var cancelSent = false
        try {
            while (!latch.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!cancelSent && isCancelled?.isCancelled() == true) {
                    Timber.tag(TAG).i("cancel requested")
                    nativeCancel(handle)
                    cancelSent = true
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!cancelSent) {
                nativeCancel(handle)
                cancelSent = true
            }
            // Let the native run wind down before releasing the handle.
            try { latch.await(30, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        } finally {
            nativeRelease(handle)
        }
        val r = outcome ?: RunResult(false, cancelSent, "no completion", 0L)
        return RunResult(r.success && !cancelSent, cancelSent, r.error, r.bytesWritten)
    }

    @JvmStatic
    private external fun nativeStart(
        planJson: String,
        installDir: String,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        listener: NativeAmazonDownloadListener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
