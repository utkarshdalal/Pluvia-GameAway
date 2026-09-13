// JNI symbols depend on this package path and class name
// (see app/src/main/cpp/gn-download/rust/src/jni_steam.rs).
package app.gamenative.service.download

import timber.log.Timber

/**
 * Callbacks from the native Steam depot download engine. Every method runs on a native
 * worker thread — never touch Views directly.
 */
interface NativeSteamDownloadListener {
    /**
     * Per-depot chunk progress. [depotDone]/[depotTotal] are bytes for the current depot;
     * [depotsDone]/[depotsTotal] count depots; [verifying] marks the final verification pass.
     */
    fun onProgress(
        depotId: Int,
        depotDone: Long,
        depotTotal: Long,
        depotsDone: Int,
        depotsTotal: Int,
        verifying: Boolean,
    )

    /**
     * Called from native worker threads when a manifest fetch needs a fresh manifest request
     * code (Steam rotates them ~every 5 min). Implementations must call
     * `SteamContent.getManifestRequestCode` and return the code (0 = unavailable, the engine
     * falls back to the pre-resolved one). The code is a Steam uint64: return the raw signed
     * bits, negative values are valid and the native side bit-casts them back.
     */
    fun refreshManifestRequestCode(depotId: Int, manifestId: Long): Long

    /** Fired exactly once per [NativeSteamDownload.start] that returned a non-zero handle. */
    fun onComplete(
        success: Boolean,
        error: String,
        bytesWritten: Long,
        depotsCompleted: Int,
        depotsSkipped: Int,
    )
}

/**
 * JVM-side facade of the Steam CDN depot download engine inside `libgndownload.so`
 * (`cpp/gn-download/rust/src/depot_*`, `cdn_client.rs`, `fetch_core.rs`).
 *
 * JavaSteam stays the CM client: [start] is fed depot keys, manifest request codes and the CDN
 * server list resolved via `SteamApps` / `SteamContent` on the Kotlin side; the Rust engine
 * fetches manifests + chunks from the CDN, decrypts/decompresses/verifies and writes files,
 * keeping the same `.DepotDownloader/` journal format as the old JavaSteam DepotDownloader so
 * downloads resume across the engine swap.
 */
object NativeSteamDownload {

    private const val TAG = "GN_STEAM_DL"

    @Volatile
    private var available: Boolean? = null

    /** True when `libgndownload.so` loads and the Steam JNI exports bind. Cached. */
    @JvmStatic
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = try {
            GameDownloadNative.ensureLoaded()
            nativeProbe() == 1
        } catch (t: Throwable) {
            Timber.tag(TAG).w("native Steam engine unavailable — ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        available = ok
        return ok
    }

    /**
     * Starts one depot-download run on a native thread and returns its handle
     * (0 = not started; the listener then receives NO callbacks).
     *
     * @param planJson see `jni_steam.rs` for the schema (install_dir, ca_bundle_path, fresh,
     *   max_workers, process_workers, servers[], depots[]).
     */
    @JvmStatic
    fun start(planJson: String, listener: NativeSteamDownloadListener): Long {
        if (!isAvailable()) return 0L
        return try {
            nativeStart(planJson, listener)
        } catch (t: Throwable) {
            Timber.tag(TAG).e("nativeStart threw — ${t.javaClass.simpleName}: ${t.message}")
            0L
        }
    }

    /** Requests cancellation; `onComplete(success = false, "cancelled")` follows. 0 is a no-op. */
    @JvmStatic
    fun cancel(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeCancel(handle) }
    }

    /** Releases the handle. Call once after `onComplete`. */
    @JvmStatic
    fun release(handle: Long) {
        if (handle == 0L) return
        runCatching { nativeRelease(handle) }
    }

    @JvmStatic
    private external fun nativeProbe(): Int

    @JvmStatic
    private external fun nativeStart(planJson: String, listener: NativeSteamDownloadListener): Long

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeRelease(handle: Long)
}
