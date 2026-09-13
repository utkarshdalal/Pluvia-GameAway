// JNI symbols depend on this package path and class name
// (see app/src/main/cpp/gn-download/rust/src/store_dl/epic/jni.rs).
package app.gamenative.service.download

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import timber.log.Timber

/**
 * JVM facade over the native Epic chunk fetcher in `libgndownload.so` (`store_dl/epic`).
 *
 * Replaces exactly two things in `EpicDownloadManager`: the fixed thread pool that fills
 * `<installDir>/.chunks/<GUID>` with verified, decompressed chunks, and the assembly stage
 * that writes each pending file out of the cache (deleting chunks after their last
 * consumer). Everything around it (manifest fetch/parse, install-tag selection,
 * delta/verify, post-install) stays in Kotlin.
 *
 * [run] is BLOCKING (like the pool it replaces) and polls [AtomicBoolean] `cancel` every
 * 250 ms: on cancel it flips the native flag, waits up to 5 s for the run to wind down, then
 * returns `cancelled = true`.
 */
object NativeEpicDownload {

    private const val TAG = "GN_EPIC_DL"

    /** Listener for the native run; every method is called on a native thread. */
    interface Listener {
        /** Once, before any fetch: the plan the engine derived (Kotlin cross-checks it). */
        fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String)

        /** Per accounted chunk (cached-skip or fetched). */
        fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int)

        /** Per assembled file part (cumulative), after a successful fetch. */
        fun onAssemblyProgress(bytesWritten: Long)

        /** Engine log line. */
        fun onLog(line: String)

        /** Terminal. */
        fun onComplete(success: Boolean, error: String, bytesCredited: Long)
    }

    /**
     * Outcome of [run]. `started == false` means the engine never fetched anything (library
     * missing, plan cross-check failed, …) and the caller must run its Kotlin pool instead.
     */
    class Result(
        @JvmField val started: Boolean,
        @JvmField val success: Boolean,
        @JvmField val cancelled: Boolean,
        @JvmField val error: String,
        @JvmField val bytesCredited: Long,
        @JvmField val chunksDone: Int,
        @JvmField val chunksTotal: Int,
    )

    private class Completion(val success: Boolean, val error: String, val bytes: Long)

    /**
     * Run the chunk fetch for `pendingFileIdx` (indices into the manifest's file list) and
     * block until it completes, fails or is cancelled.
     *
     * @param expectedChunks Kotlin's needed-chunk count (-1 = no cross-check)
     * @param expectedBytes  Kotlin's Σ max(fileSize, 1) (-1 = no cross-check)
     * @param cancel         the manager's cancel flag; null = never cancel
     */
    @JvmStatic
    fun run(
        manifest: ByteArray,
        installDir: String,
        cdnPrefixes: Array<String>,
        pendingFileIdx: IntArray,
        expectedChunks: Int,
        expectedBytes: Long,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        cancel: AtomicBoolean?,
        listener: Listener,
    ): Result {
        try {
            GameDownloadNative.ensureLoaded()
        } catch (t: Throwable) {
            val msg = "lib: ${t.javaClass.simpleName}: ${t.message}"
            Timber.tag(TAG).w("engine unavailable — $msg")
            return Result(false, false, false, msg, 0L, 0, 0)
        }

        val latch = CountDownLatch(1)
        val completion = AtomicReference<Completion?>(null)
        val doneRef = AtomicReference(0)
        val totalRef = AtomicReference(0)
        val inner = object : Listener {
            override fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String) {
                totalRef.set(chunksTotal)
                listener.onPlan(chunksTotal, bytesTotal, chunkDir)
            }

            override fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int) {
                doneRef.set(chunksDone)
                listener.onProgress(bytesDone, bytesTotal, chunksDone, chunksTotal)
            }

            override fun onAssemblyProgress(bytesWritten: Long) = listener.onAssemblyProgress(bytesWritten)

            override fun onLog(line: String) = listener.onLog(line)

            override fun onComplete(success: Boolean, error: String, bytesCredited: Long) {
                completion.set(Completion(success, error, bytesCredited))
                latch.countDown()
                try {
                    listener.onComplete(success, error, bytesCredited)
                } catch (_: Throwable) {
                }
            }
        }

        val handle: Long = try {
            nativeStart(
                manifest, installDir, cdnPrefixes, pendingFileIdx, expectedChunks, expectedBytes,
                caBundlePath, maxWorkers, processWorkers, inner,
            )
        } catch (t: Throwable) {
            val msg = "nativeStart: ${t.javaClass.simpleName}: ${t.message}"
            Timber.tag(TAG).w("engine unavailable — $msg")
            return Result(false, false, false, msg, 0L, 0, 0)
        }
        if (handle == 0L) {
            val c = completion.get()
            return Result(false, false, false, c?.error ?: "not started", 0L, 0, totalRef.get())
        }

        var cancelSent = false
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.get()) {
                    nativeCancel(handle)
                    cancelSent = true
                    latch.await(5, TimeUnit.SECONDS)
                    break
                }
            }
        } finally {
            nativeRelease(handle)
        }

        val c = completion.get()
        val success = !cancelSent && c != null && c.success
        val error = when {
            cancelSent -> "cancelled"
            c == null -> "no completion"
            else -> c.error
        }
        return Result(
            true, success, cancelSent, error,
            c?.bytes ?: 0L, doneRef.get(), totalRef.get(),
        )
    }

    @JvmStatic
    private external fun nativeStart(
        manifest: ByteArray,
        installDir: String,
        cdnPrefixes: Array<String>,
        pendingFileIdx: IntArray,
        expectedChunks: Int,
        expectedBytes: Long,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        listener: Listener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
