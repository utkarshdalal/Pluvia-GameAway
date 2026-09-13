package app.gamenative.service.download

import java.io.File
import timber.log.Timber

/**
 * JVM facade over the parallel tree deleter in `libgndownload.so` (`tree_delete.rs`).
 *
 * `File.deleteRecursively()` walks single-threaded with a `File` allocation and a full path
 * re-resolution per entry; on external storage (sdcardfs/FUSE) every syscall round-trips the
 * FUSE daemon, so deleting a large game takes many minutes. The native deleter drains a shared
 * work stack with several threads (overlapping FUSE latency) and uses d_type from getdents
 * instead of a stat per entry. Symlinks are unlinked, never followed; a directory is removed
 * only after all its children (refcounted), so it cannot fail with ENOTEMPTY.
 *
 * Contract: [deleteTreeFast] tries native first and falls back to [File.deleteRecursively]
 * when the lib is unavailable or the native run reports a hard failure — callers get the same
 * boolean semantics as `deleteRecursively()` either way.
 */
object NativeTreeDelete {

    private const val TAG = "TreeDelete"

    /** Returns deleted entries (files + dirs), or -1 on hard failure. */
    private external fun nativeDeleteTree(path: String, workers: Int): Long

    /** True when `libgndownload.so` loads and binds. */
    fun isAvailable(): Boolean = try {
        GameDownloadNative.ensureLoaded()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Deletes [dir] (file, symlink, or directory tree). Returns true when nothing at [dir]'s
     * path remains — via the native parallel deleter when available, else the Kotlin fallback.
     */
    fun deleteTreeFast(dir: File, workers: Int = 4): Boolean {
        if (!dir.exists()) return true
        val nativeDeleted: Long = try {
            GameDownloadNative.ensureLoaded()
            val t0 = System.currentTimeMillis()
            val deleted = nativeDeleteTree(dir.absolutePath, workers)
            Timber.i(
                "$TAG: native delete ${dir.absolutePath} → $deleted entries " +
                    "in ${System.currentTimeMillis() - t0} ms"
            )
            deleted
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: native deleter unavailable, using File.deleteRecursively")
            -1
        }
        if (nativeDeleted >= 0 && !dir.exists()) return true
        // Hard failure, or partial per-entry failures (native logs them): finish the job.
        if (nativeDeleted >= 0) {
            Timber.w("$TAG: residue left after native delete of ${dir.absolutePath}; falling back")
        }
        return dir.deleteRecursively() || !dir.exists()
    }
}
