package app.gamenative.service.amazon

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.data.DownloadInfo
import app.gamenative.enums.Marker
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeAmazonCancelCheck
import app.gamenative.service.download.NativeAmazonDownload
import app.gamenative.service.download.NativeAmazonDownloadListener
import app.gamenative.utils.DownloadSpeedConfig
import app.gamenative.utils.MarkerUtils
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Handles native downloading of Amazon games. */
@Singleton
class AmazonDownloadManager @Inject constructor(
    private val amazonManager: AmazonManager,
) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 6
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val PROGRESS_EMIT_INTERVAL = 512 * 1024L // Emit UI progress every 512 KB
        private const val TAG = "Amazon"
    }

    /** Download and install an Amazon game. */
    suspend fun downloadGame(
        context: Context,
        game: AmazonGame,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val productId = game.productId
        try {
            Timber.tag(TAG).i("Starting download for ${game.title} → $installPath")

            File(installPath).mkdirs()
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            // Helper to cleanup marker on early failure
            fun cleanupOnFailure() {
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            }

            // ── 1. Credentials ───────────────────────────────────────────────
            if (game.entitlementId.isBlank()) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Game '${game.title}' has no entitlement ID — re-sync library first"))
            }
            val bearerToken = amazonManager.getBearerToken()
            if (bearerToken == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("No Amazon credentials stored"))
            }

            // ── 2. Download spec ─────────────────────────────────────────────
            downloadInfo.updateStatusMessage("Fetching download information…")
            val spec = AmazonApiClient.fetchGameDownload(game.entitlementId, bearerToken)
            if (spec == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to fetch download spec from Amazon"))
            }

            Timber.tag(TAG).d("Download spec: url=${spec.downloadUrl}, version=${spec.versionId}")

            // ── 3. Manifest ──────────────────────────────────────────────────
            downloadInfo.updateStatusMessage("Fetching manifest…")
            val manifestUrl = appendPath(spec.downloadUrl, "manifest.proto")
            Timber.tag(TAG).d("Manifest URL: $manifestUrl")
            val manifestBytes = fetchBytes(manifestUrl)
            if (manifestBytes == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to download manifest.proto"))
            }

            val manifest = try {
                AmazonManifest.parse(manifestBytes)
            } catch (e: Exception) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to parse manifest: ${e.message}", e))
            }

            val files = manifest.allFiles
            if (files.isEmpty()) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Manifest contains no files"))
            }

            Timber.tag(TAG).i(
                "Manifest: ${files.size} file(s), ${manifest.totalInstallSize / 1_000_000} MB total"
            )

            // ── 4. Progress setup ────────────────────────────────────────────
            downloadInfo.setTotalExpectedBytes(manifest.totalInstallSize)
            downloadInfo.setProgress(0f)
            downloadInfo.emitProgressChange()

            // ── 5. Download files — the byte-moving pool runs in Rust
            //    (GameDownloadService → libgndownload store_dl/amazon). The Kotlin batch
            //    loop below is kept only as a fallback when the native library is missing.
            val installDir = File(installPath)
            val baseUrl = spec.downloadUrl

            if (NativeAmazonDownload.isAvailable()) {
                val planJson = org.json.JSONArray().apply {
                    for (file in files) {
                        // Path-traversal guard (defense in depth; the native engine also
                        // rejects these): a manifest path must never escape installDir.
                        // FAIL rather than skip — silently dropping the file would let a
                        // partial install be reported as complete.
                        val rel = file.unixPath
                        if (rel.isEmpty() || rel.startsWith("/") || rel.contains('\\') ||
                            rel.split('/').any { it == ".." }
                        ) {
                            Timber.tag(TAG).e("Unsafe manifest path: $rel")
                            return@withContext Result.failure(
                                Exception("Manifest contains unsafe path: $rel"),
                            )
                        }
                        val hashHex = file.hashBytes.joinToString("") { "%02x".format(it) }
                        put(
                            org.json.JSONObject()
                                .put("relPath", rel)
                                .put("url", appendPath(baseUrl, "files/$hashHex"))
                                .put("size", file.size)
                                .put("sha256hex", if (file.hashAlgorithm == 0) hashHex else ""),
                        )
                    }
                }.toString()

                var creditedBytes = 0L
                val listener = object : NativeAmazonDownloadListener {
                    override fun onProgress(bytesDone: Long, bytesTotal: Long, filesDone: Long, filesTotal: Long) {
                        // Native callbacks fire from multiple fetch-pool threads; keep the
                        // delta read-check-set atomic (same fix as Epic's listener).
                        synchronized(this) {
                            val delta = bytesDone - creditedBytes
                            if (delta > 0L) {
                                creditedBytes = bytesDone
                                downloadInfo.updateBytesDownloaded(delta)
                            }
                        }
                        downloadInfo.updateStatusMessage("Downloading ($filesDone/$filesTotal files)…")
                        downloadInfo.emitProgressChange()
                        downloadInfo.persistProgressSnapshot()
                    }

                    override fun onLog(line: String) {
                        Timber.tag(TAG).d(line)
                    }

                    override fun onComplete(success: Boolean, error: String, bytesWritten: Long) = Unit
                }

                // Adaptive-window ceiling from the user's speed tier (was a hardcoded 6);
                // process pool stays core-scaled.
                val speedConfig = DownloadSpeedConfig()
                val result = GameDownloadService.downloadAmazonFiles(
                    planJson = planJson,
                    installDir = installPath,
                    maxWorkers = speedConfig.maxDownloads,
                    processWorkers = speedConfig.maxDecompress,
                    isCancelled = NativeAmazonCancelCheck { !downloadInfo.isActive() },
                    listener = listener,
                )
                if (result.cancelled) {
                    Timber.tag(TAG).w("Download cancelled by user")
                    throw CancellationException("Download cancelled")
                }
                if (!result.success) {
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    return@withContext Result.failure(
                        Exception(result.error.ifEmpty { "File download failed" })
                    )
                }
            } else {
                var completedFiles = 0
                val totalFiles = files.size

                for (batch in files.chunked(MAX_PARALLEL_DOWNLOADS)) {
                    if (!downloadInfo.isActive()) {
                        Timber.tag(TAG).w("Download cancelled by user")
                        throw CancellationException("Download cancelled")
                    }

                    val results = batch.map { file ->
                        async {
                            downloadFileWithRetry(
                                baseUrl = baseUrl,
                                file = file,
                                installDir = installDir,
                                downloadInfo = downloadInfo,
                            )
                        }
                    }.awaitAll()

                    val failure = results.firstOrNull { it.isFailure }
                    if (failure != null) {
                        MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        return@withContext Result.failure(
                            failure.exceptionOrNull() ?: Exception("File download failed")
                        )
                    }

                    completedFiles += batch.size
                    downloadInfo.updateStatusMessage("Downloading ($completedFiles/$totalFiles files)…")
                    downloadInfo.emitProgressChange()
                    downloadInfo.persistProgressSnapshot()
                }
            }

            // ── 6. Cache manifest ────────────────────────────────────────
            try {
                val manifestDir = File(context.filesDir, "manifests/amazon")
                manifestDir.mkdirs()
                val manifestFile = File(manifestDir, "$productId.proto")
                manifestFile.writeBytes(manifestBytes)
                Timber.tag(TAG).i("Cached manifest: ${manifestFile.absolutePath} (${manifestBytes.size} bytes)")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to cache manifest (non-fatal)")
            }

            // ── 7. Persist installed state ───────────────────────────────────
            Timber.tag(TAG).i("Persisting install: productId=$productId, version=${spec.versionId}")
            amazonManager.markInstalled(productId, installPath, manifest.totalInstallSize, spec.versionId)

            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)

            downloadInfo.updateStatusMessage("Complete")
            // Clamp bytes so progress reads exactly 1.0
            val remaining = downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded()
            if (remaining > 0L) downloadInfo.updateBytesDownloaded(remaining)
            downloadInfo.setProgress(1f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            Timber.tag(TAG).i("Download complete for ${game.title}")
            Result.success(Unit)

        } catch (e: Exception) {
            if (e is CancellationException) {
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                downloadInfo.persistProgressSnapshot()
                downloadInfo.setActive(false)
                throw e
            }
            Timber.tag(TAG).e(e, "Download failed for ${game.title}: ${e.message}")
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1f)
            downloadInfo.setActive(false)
            Result.failure(e)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private suspend fun downloadFileWithRetry(
        baseUrl: String,
        file: AmazonManifest.ManifestFile,
        installDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            if (!downloadInfo.isActive()) {
                throw CancellationException("Download cancelled")
            }

            val result = downloadFile(baseUrl, file, installDir, downloadInfo)
            if (result.isSuccess) return result

            lastException = result.exceptionOrNull() as? Exception
            if (attempt < MAX_RETRIES - 1) {
                val backoffMs = RETRY_DELAY_MS * (1 shl attempt) // 1s, 2s, 4s
                Timber.tag(TAG).w(
                    "File ${file.unixPath} failed (attempt ${attempt + 1}/$MAX_RETRIES): " +
                        "${lastException?.message}. Retrying in ${backoffMs}ms…"
                )
                delay(backoffMs)
            }
        }
        return Result.failure(
            lastException ?: Exception("Failed to download ${file.unixPath} after $MAX_RETRIES attempts")
        )
    }

    private suspend fun downloadFile(
        baseUrl: String,
        file: AmazonManifest.ManifestFile,
        installDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val destFile = File(installDir, file.unixPath).canonicalFile
        val tmpFile = File(installDir, "${file.unixPath}.tmp").canonicalFile
        val installDirCanonical = installDir.canonicalPath

        // Security check: prevent path traversal attacks
        if (!destFile.path.startsWith(installDirCanonical) || !tmpFile.path.startsWith(installDirCanonical)) {
            Timber.tag(TAG).e("Path traversal attempt blocked: ${file.unixPath}")
            return@withContext Result.failure(SecurityException("Invalid file path"))
        }

        try {
            // Skip already-complete files (resume-friendly)
            if (destFile.exists() && destFile.length() == file.size) {
                // Count recovered bytes for progress without skewing transfer speed ETA.
                downloadInfo.updateBytesDownloaded(file.size, trackSpeed = false)
                return@withContext Result.success(Unit)
            }

            destFile.parentFile?.mkdirs()
            tmpFile.parentFile?.mkdirs()

            // nile uses /files/{hash_hex} per downloading/manager.py, NOT the unix path
            val hashHex = file.hashBytes.joinToString("") { "%02x".format(it) }
            val url = appendPath(baseUrl, "files/$hashHex")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "nile/0.1 Amazon")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code} for ${file.unixPath}")
                    )
                }

                response.body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var bytesSinceLastEmit = 0L
                        while (input.read(buf).also { read = it } != -1) {
                                    if (!downloadInfo.isActive()) {
                                        throw CancellationException("Download cancelled")
                                    }
                            output.write(buf, 0, read)
                            downloadInfo.updateBytesDownloaded(read.toLong())
                            bytesSinceLastEmit += read
                            // Emit progress every ~512 KB so UI updates smoothly during large files
                            if (bytesSinceLastEmit >= PROGRESS_EMIT_INTERVAL) {
                                bytesSinceLastEmit = 0L
                                downloadInfo.emitProgressChange()
                                downloadInfo.persistProgressSnapshot()
                            }
                        }
                    }
                }
            }

            // Verify SHA-256 hash (algorithm 0) when present
            if (file.hashAlgorithm == 0 && file.hashBytes.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                tmpFile.inputStream().buffered().use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        digest.update(buf, 0, read)
                    }
                }
                val computed = digest.digest()
                if (!computed.contentEquals(file.hashBytes)) {
                    tmpFile.delete()
                    return@withContext Result.failure(
                        Exception("SHA-256 mismatch for ${file.unixPath}")
                    )
                }
            }

            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)

            Result.success(Unit)
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            tmpFile.delete()
            Timber.tag(TAG).w(e, "Error downloading ${file.unixPath}")
            Result.failure(e)
        }
    }

    /** Append [segment] to [baseUrl] path before any query string. */
    private fun appendPath(baseUrl: String, segment: String): String {
        val qIdx = baseUrl.indexOf('?')
        return if (qIdx == -1) {
            "$baseUrl/$segment"
        } else {
            val path = baseUrl.substring(0, qIdx)
            val query = baseUrl.substring(qIdx)
            "$path/$segment$query"
        }
    }

    /** Download raw bytes from [url], returning null on error. */
    private fun fetchBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "fetchBytes failed for $url")
        null
    }
}
