// JNI symbols depend on this package path and class name
// (see app/src/main/cpp/gn-download/rust/src/store_dl/gog/jni.rs).
package app.gamenative.service.download

import timber.log.Timber

/**
 * Callbacks from the native GOG download engine. Every method runs on a native worker /
 * process-pool thread — never touch Views directly.
 */
interface NativeGogDownloadListener {
    /**
     * One file reached its final state. [verified] = resume-skip (existing file passed size+MD5;
     * no bytes credited); otherwise a freshly assembled, size+MD5 verified and renamed file
     * ([fileBytes] = its decompressed size). [filesDone] counts both; [bytesDone] counts
     * assembled bytes only.
     */
    fun onProgress(
        bytesDone: Long,
        bytesTotal: Long,
        filesDone: Int,
        filesTotal: Int,
        file: String,
        fileBytes: Long,
        verified: Boolean,
    )

    /** Engine diagnostics. */
    fun onLog(line: String)

    /**
     * Cumulative compressed bytes fetched so far in this run (monotonic, throttled to ~5/sec
     * native-side). Credit the DELTA against the previous value to drive live size/ETA —
     * per-file [onProgress] alone is too coarse for games with a few large files.
     */
    fun onBytes(bytesFetched: Long) {}

    /**
     * Fired exactly once per [NativeGogDownload.start] that returned a non-zero handle.
     * [linkExpiry] = the run died on an HTTP 401/403/404/500 — the manager refreshes the
     * secure link and re-runs.
     */
    fun onComplete(
        success: Boolean,
        cancelled: Boolean,
        linkExpiry: Boolean,
        error: String,
        bytesWritten: Long,
        filesDone: Int,
    )
}

/**
 * JVM-side facade of the GOG gen2 chunk engine inside `libgndownload.so`
 * (`cpp/gn-download/rust/src/store_dl/gog/`). One [start] = one manager pool loop (base
 * install, DLC install, or a dependency-redist assembly); the Kotlin manager owns everything
 * before and after it (token/builds/manifest head, depot selection, secure-link resolution,
 * post-install).
 */
object NativeGogDownload {

    private const val TAG = "GN_GOG_DL"

    /** gen2: depot manifests → chunk fetch + inflate + MD5 (base install, DLC, dependencies). */
    const val KIND_GEN2_CHUNKS = 0

    /** gen1: build manifest → per-file HTTP Range GET streamed to disk. */
    const val KIND_GEN1_RANGES = 1

    @Volatile
    private var available: Boolean? = null

    /** True when `libgndownload.so` loads and the GOG JNI exports bind. Cached. */
    @JvmStatic
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            GameDownloadNative.ensureLoaded()
            nativeProbe() == 1
        } catch (t: Throwable) {
            Timber.tag(TAG).w("native GOG engine unavailable — ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        available = ok
        return ok
    }

    /**
     * Starts one download loop on a native thread and returns its handle (0 = not started; the
     * listener then receives NO callbacks).
     */
    @JvmStatic
    fun start(
        kind: Int,
        depotManifests: Array<String>,
        cdnBase: String,
        installDir: String,
        skipPaths: Array<String>,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        sortLargestFirst: Boolean,
        label: String,
        listener: NativeGogDownloadListener,
    ): Long {
        if (!isAvailable()) return 0L
        return try {
            nativeStart(
                kind, depotManifests, cdnBase, installDir, skipPaths, caBundlePath,
                maxWorkers, processWorkers, sortLargestFirst, label, listener,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).e("nativeStart threw — ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /** Requests cancellation; `onComplete(cancelled = true)` follows. Idempotent, 0 is a no-op. */
    @JvmStatic
    fun cancel(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeCancel(handle) }
    }

    /** Releases the handle. Call once after `onComplete` (the native run keeps itself alive). */
    @JvmStatic
    fun release(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeRelease(handle) }
    }

    @JvmStatic
    private external fun nativeProbe(): Int

    @JvmStatic
    private external fun nativeStart(
        kind: Int,
        depotManifests: Array<String>,
        cdnBase: String,
        installDir: String,
        skipPaths: Array<String>,
        caBundlePath: String,
        maxWorkers: Int,
        processWorkers: Int,
        sortLargestFirst: Boolean,
        label: String,
        listener: NativeGogDownloadListener,
    ): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
