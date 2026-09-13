package app.gamenative.service.download

/**
 * Loads `libgndownload.so` — the single Rust library (`cpp/gn-download/rust`) behind
 * [GameDownloadService]. All store download pipelines (Steam CDN depots, GOG chunks,
 * Epic chunks, Amazon files) live in this one library.
 */
object GameDownloadNative {

    private const val LIB_NAME = "gndownload"

    @Volatile
    private var loaded = false

    @Synchronized
    @JvmStatic
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary(LIB_NAME)
        loaded = true
    }

    @JvmStatic
    fun isLoaded(): Boolean = loaded
}
