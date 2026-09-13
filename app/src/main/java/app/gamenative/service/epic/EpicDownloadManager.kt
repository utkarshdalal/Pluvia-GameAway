package app.gamenative.service.epic

import android.content.Context
import android.util.Log
import app.gamenative.PrefManager
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.utils.CdnRankingUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.data.EpicGame
import app.gamenative.service.StreamingAssembly
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeEpicDownload
import app.gamenative.service.download.NativeTreeDelete
import app.gamenative.service.epic.manifest.ChunkPart
import app.gamenative.service.epic.manifest.EpicManifest
import app.gamenative.service.epic.manifest.ManifestUtils
import app.gamenative.service.gog.HttpStatusException
import app.gamenative.utils.ContainerStorageManager
import app.gamenative.utils.DownloadSpeedConfig
import app.gamenative.utils.Net
import app.gamenative.utils.StorageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap.newKeySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * EpicDownloadManager handles downloading Epic games
 *
 * Manifest structure (from legendary.models.manifest):
 * - meta: App metadata (app_name, build_version, etc.)
 * - chunk_data_list: List of chunks to download
 * - file_manifest_list: List of files and their chunk composition
 */
@Singleton
class EpicDownloadManager @Inject constructor(
    private val epicManager: EpicManager,
    @ApplicationContext private val context: Context,
) {
    companion object {
        /**
         * Internal chunk-cache dir for a download target. Keyed by the full install
         * path (not just the directory name) so two containers installing the EOS
         * overlay — whose install dirs are all named "Overlay" — never share a cache.
         */
        fun chunkCacheDirFor(context: Context, installPath: String): File {
            val dir = File(installPath)
            val key = Integer.toHexString(dir.absolutePath.hashCode())
            return File(context.cacheDir, "epic_chunks/$key-${dir.name}")
        }

        /**
         * Sharded chunk-cache layout: `<cache>/<first2>/<guidStr>`. A flat directory with
         * ~100k chunk files (a 100 GB game of 1 MB chunks) makes every open/exists scan one
         * huge directory — expensive even on internal storage, worse on FUSE. Writes always
         * go to the sharded path; [resolveChunkFile] dual-reads (sharded first, legacy flat
         * second) so caches written by older builds resume without refetching. Mirrors
         * `store_dl/epic/plan.rs` (the native engine uses the same scheme for `.chunks`).
         */
        fun shardedChunkFile(chunkCacheDir: File, guidStr: String): File =
            File(File(chunkCacheDir, guidStr.take(2)), guidStr)

        /** Dual-read resolution: sharded first, legacy flat (pre-sharding builds) second. */
        fun resolveChunkFile(chunkCacheDir: File, guidStr: String): File {
            val sharded = shardedChunkFile(chunkCacheDir, guidStr)
            return if (sharded.exists()) sharded else File(chunkCacheDir, guidStr)
        }

        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer for decompression
        private const val MAX_CHUNK_RETRIES = 3 // Maximum retries per chunk
        private const val RETRY_DELAY_MS = 1000L // Initial retry delay in milliseconds
        private const val STREAM_PROGRESS_TIME_INTERVAL_MS = 200L
    }

    /**
     * Download and install an Epic game
     *
     * @param context Android context
     * @param game Epic game to download
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @param containerLanguage Container language (e.g. "english", "german"). Same as GOG/Steam; used to select install tags so the correct language files are downloaded.
     * @param dlcIds Optional DLC game IDs to include
     * @param commonRedistDir Optional directory for common redistributables
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        context: Context,
        game: EpicGame,
        installPath: String,
        downloadInfo: DownloadInfo,
        containerLanguage: String = EpicConstants.EPIC_FALLBACK_CONTAINER_LANGUAGE,
        dlcIds: List<Int>,
        commonRedistDir: File? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {

            Timber.tag("Epic").i("Starting download for ${game.title} to $installPath")

            File(installPath).mkdirs()
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            // Emit download started event so UI can attach progress listeners
            val gameId = game.id
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, true),
            )

            // Check for DLCs early to calculate total download size
            val dlcsToDownload = if (dlcIds.size > 0) {
                try {
                    Timber.tag("Epic").d("User has opted to download ${dlcIds.size} DLC titles for game: ${game.title}")
                    val dlcs = epicManager.getGamesById(dlcIds)
                    if (dlcs.isNotEmpty()) {
                        Timber.tag("Epic").d("Found ${dlcs.size} DLC(s) for ${game.title}")
                    }
                    dlcs.filter { dlcIds.contains(it.id) }
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error checking for DLCs, continuing without")
                    emptyList()
                }
            } else {
                emptyList()
            }

            Timber.tag("Epic").i("Filtered to ${dlcsToDownload.size} DLC(s) for ${game.title}")

            // Fetch manifest binary and CDN URLs from Epic
            val manifestResult = epicManager.fetchManifestFromEpic(
                context,
                game.namespace,
                game.catalogId,
                game.appName,
            )
            if (manifestResult.isFailure) {
                return@withContext Result.failure(
                    manifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                )
            }

            val manifestData = manifestResult.getOrNull()!!

            // ! Avoiding Cloudflare as it causes issues with some downloads and is inconsistent.
            val preferredCdnUrls = manifestData.cdnUrls
                .filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val cdnUrls = rankCdnUrlsByProbe(
                preferredCdnUrls.ifEmpty { manifestData.cdnUrls },
            )

            Timber.tag("Epic").d("Manifest fetched with ${cdnUrls.size} CDN URLs, parsing...")

            // Parse manifest binary to get chunks and files
            val manifest = EpicManifest.readAll(manifestData.manifestBytes)

            // Use container language (same as GOG) to select install tags: required + optional language files.
            val selectedTags = EpicConstants.containerLanguageToEpicInstallTags(containerLanguage)
            val files = ManifestUtils.getFilesForSelectedInstallTags(manifest, selectedTags)
            val chunks = ManifestUtils.getRequiredChunksForFileList(manifest, files)

            if (selectedTags.isNotEmpty()) {
                Timber.tag("Epic").i("Container language '$containerLanguage' -> install tags: ${selectedTags.joinToString()}")
            }

            val chunkDir = manifest.getChunkDir()

            // chunks can be empty when every file is zero-chunk (e.g. empty
            // config stubs); files.isEmpty() is the real error condition
            if (files.isEmpty()) {
                val msg = if (selectedTags.isNotEmpty()) {
                    "No files found for the selected language. This game may not support this language."
                } else {
                    "No file manifest in manifest"
                }
                return@withContext Result.failure(Exception(msg))
            }

            // Calculate total download size including DLCs
            var totalDownloadSize = chunks.sumOf { it.fileSize }
            var totalInstalledSize = chunks.sumOf { it.windowSize.toLong() }
            // Progress total = UNCOMPRESSED installed bytes (the native engine credits
            // decompressed chunk bytes; assembly tops up shared-chunk copies).
            var totalProgressSize = files.sumOf { it.fileSize }
            val baseGameSize = totalDownloadSize

            // Fetch DLC manifests to get their sizes for accurate progress tracking
            val dlcManifestData = mutableListOf<Pair<EpicGame, EpicManager.ManifestResult>>()
            if (dlcsToDownload.isNotEmpty()) {
                downloadInfo.updateStatusMessage("Calculating DLC sizes...")
                for (dlc in dlcsToDownload) {
                    try {
                        val dlcManifestResult = epicManager.fetchManifestFromEpic(
                            context,
                            dlc.namespace,
                            dlc.catalogId,
                            dlc.appName,
                        )
                        if (dlcManifestResult.isSuccess) {
                            val dlcManifest = dlcManifestResult.getOrNull()!!
                            val dlcParsed = EpicManifest.readAll(dlcManifest.manifestBytes)
                            val dlcDownloadSize = dlcParsed.chunkDataList?.elements?.sumOf { it.fileSize } ?: 0L
                            val dlcInstalledSize = dlcParsed.chunkDataList?.elements?.sumOf { it.windowSize.toLong() } ?: 0L
                            totalDownloadSize += dlcDownloadSize
                            totalInstalledSize += dlcInstalledSize
                            totalProgressSize += ManifestUtils.getFilesForSelectedInstallTags(dlcParsed, selectedTags).sumOf { it.fileSize }
                            dlcManifestData.add(dlc to dlcManifest)
                            Timber.tag("Epic").i("DLC ${dlc.title} size: ${dlcDownloadSize / 1_000_000} MB")
                        } else {
                            Timber.tag("Epic").w("Failed to fetch manifest for DLC ${dlc.title}, will skip")
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "Error fetching manifest for DLC ${dlc.title}")
                    }
                }
            }

            val chunkCount = chunks.size
            val fileCount = files.size

            Timber.tag("Epic").d(
                """
                |Download prepared:
                |  Base game download size: ${baseGameSize / 1_000_000_000.0} GB
                |  Base game installed size: ${totalInstalledSize / 1_000_000_000.0} GB
                |  DLCs: ${dlcManifestData.size}
                |  Total download size (including DLCs): ${totalDownloadSize / 1_000_000_000.0} GB
                |  Chunks: $chunkCount
                |  Files: $fileCount
                |  ChunkDir: $chunkDir
                """.trimMargin(),
            )

            downloadInfo.setTotalExpectedBytes(totalProgressSize)
            downloadInfo.updateStatusMessage("Downloading base game...")

            // Download chunks in parallel.
            // Cache lives on internal storage: on exFAT SD cards (dirsync mount) every
            // create/rename/delete in the cache dir is a synchronous directory flush,
            // which dominates download time for small chunks.
            val chunkCacheDir = chunkCacheDirFor(context, installPath)
            chunkCacheDir.mkdirs()

            Timber.tag("Epic").d(
                """
                |=== NATIVE KOTLIN MANIFEST DATA ===
                |CDN URLs (${cdnUrls.size}):
                |${cdnUrls.joinToString("\n") { "  - ${it.baseUrl}" }}
                |Chunks: ${chunks.size}
                |Files: ${files.size}
                |==================================
                """.trimMargin(),
            )

            // Build file-ordered chunk queue and run streaming download + assembly
            val installDir = File(installPath)
            installDir.mkdirs()

            // Incremental download: skip files already on disk with matching size and SHA-1.
            // On a resume this hashes every completed file, which can take minutes for a
            // large install — surface it in the UI and honor cancellation between files.
            downloadInfo.updateStatusMessage("Verifying existing files...")
            val pendingFiles = files.filter { file ->
                if (!downloadInfo.isActive()) {
                    // The enclosing catch never runs on this return path, so clean up here
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                !fileExistsWithCorrectHash(File(installDir, file.filename), file.fileSize, file.hash)
            }
            downloadInfo.updateStatusMessage(null)
            Timber.tag("Epic").d("Skipping ${files.size - pendingFiles.size} existing file(s), downloading ${pendingFiles.size}")

            val fileChunkIds = pendingFiles.map { f -> f.chunkParts.map { it.guidStr } }
            val chunkQueue = buildFileOrderedChunkQueue(manifest, fileChunkIds)

            // Native (Rust) chunk engine first; falls back to the Kotlin streaming pipeline.
            val nativeResult = downloadAndAssembleEpicChunksNative(
                manifestBytes = manifestData.manifestBytes,
                cdnUrls = cdnUrls,
                installDir = installDir,
                // The native engine re-parses the FULL manifest and applies the pending
                // indices to it, so allFiles must be the unfiltered list — passing the
                // install-tag-filtered `files` would shift indices onto the wrong files.
                allFiles = manifest.fileManifestList?.elements ?: files,
                pendingFiles = pendingFiles,
                downloadInfo = downloadInfo,
            )

            val downloadAndAssembleResult = nativeResult ?: downloadAndAssembleEpicChunks(
                manifest = manifest,
                cdnUrls = cdnUrls,
                chunkCacheDir = chunkCacheDir,
                installDir = installDir,
                files = pendingFiles,
                downloadInfo = downloadInfo,
                chunkQueue = chunkQueue,
                chunkDir = chunkDir,
            )

            if (downloadAndAssembleResult.isFailure) {
                return@withContext downloadAndAssembleResult
            }

            NativeTreeDelete.deleteTreeFast(chunkCacheDir)

            // Log final directory structure
            Timber.tag("Epic").i("Download completed successfully for ${game.title}")
            logDirectoryStructure(installDir)

            // Download DLCs using pre-fetched manifest data
            if (dlcManifestData.isNotEmpty()) {
                try {
                    Timber.tag("Epic").i("Downloading ${dlcManifestData.size} DLC(s) for ${game.title}")

                    dlcManifestData.forEachIndexed { index, (dlc, manifestData) ->
                        try {
                            Timber.tag("Epic").i("Downloading DLC ${index + 1}/${dlcManifestData.size}: ${dlc.title}")
                            downloadInfo.updateStatusMessage("Downloading DLC: ${dlc.title} (${index + 1}/${dlcManifestData.size})")

                            // Download the DLC using already-fetched manifest
                            val dlcResult = downloadGameWithManifest(
                                context = context,
                                game = dlc,
                                manifestData = manifestData,
                                installPath = installPath,
                                downloadInfo = downloadInfo,
                            )

                            if (dlcResult.isFailure) {
                                if (!downloadInfo.isActive()) {
                                    return@withContext dlcResult
                                }
                                Timber.tag("Epic").w("Failed to download DLC ${dlc.title}: ${dlcResult.exceptionOrNull()?.message}")
                            } else {
                                Timber.tag("Epic").i("Successfully downloaded DLC: ${dlc.title}")
                            }
                        } catch (e: Exception) {
                            Timber.tag("Epic").e(e, "Error downloading DLC ${dlc.title}")
                            // Continue with other DLCs
                        }
                    }

                    downloadInfo.updateStatusMessage("DLC downloads complete")
                    Timber.tag("Epic").i("Finished downloading DLCs for ${game.title}")
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "Error downloading DLCs")
                    // Don't fail the base game download if DLC fails
                }
            }
            // Update database with install info
            try {
                val updatedGame = game.copy(
                    isInstalled = true,
                    installPath = installPath,
                    installSize = totalInstalledSize,
                )
                epicManager.updateGame(updatedGame)
                Timber.tag("Epic").i("Updated database: game marked as installed")
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to update database for game ${game.id}")
                // Don't fail the entire download for DB issues
            }

            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)

            // Clean up and update UI
            downloadInfo.updateStatusMessage("Complete")
            // Ensure bytes-based progress shows 100% completion
            downloadInfo.updateBytesDownloaded(downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded())
            downloadInfo.clearPersistedBytesDownloaded(installPath)
            downloadInfo.setProgress(1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            // Notify UI that installation status changed
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId, app.gamenative.data.GameSource.EPIC),
            )

            Timber.tag("Epic").i("Download completed successfully for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Download failed: ${e.message}")
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1.0f)
            downloadInfo.setActive(false)
            Result.failure(e)
        } finally {
            // Always emit download stopped event
            val gameId = game.id ?: 0
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId, false),
            )
        }
    }

    /**
     * Download game using an already-fetched manifest (used for DLCs)
     */
    private suspend fun downloadGameWithManifest(
        context: Context,
        game: EpicGame,
        manifestData: EpicManager.ManifestResult,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("Starting download for ${game.title} using pre-fetched manifest")

            // Parse manifest
            val preferredCdnUrls = manifestData.cdnUrls
                .filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val cdnUrls = rankCdnUrlsByProbe(
                preferredCdnUrls.ifEmpty { manifestData.cdnUrls },
            )
            val manifest = EpicManifest.readAll(manifestData.manifestBytes)

            val chunkDataList = manifest.chunkDataList
                ?: return@withContext Result.failure(Exception("No chunk data in manifest"))
            val fileManifestList = manifest.fileManifestList
                ?: return@withContext Result.failure(Exception("No file manifest in manifest"))

            val files = fileManifestList.elements
            val chunkDir = manifest.getChunkDir()

            val chunkCacheDir = chunkCacheDirFor(context, installPath)
            chunkCacheDir.mkdirs()
            val installDir = File(installPath)
            installDir.mkdirs()

            // Incremental download: skip files already on disk with matching size and SHA-1.
            // On a resume this hashes every completed file, which can take minutes for a
            // large install — surface it in the UI and honor cancellation between files.
            downloadInfo.updateStatusMessage("Verifying existing files...")
            val pendingFiles = files.filter { file ->
                if (!downloadInfo.isActive()) {
                    // The enclosing catch never runs on this return path, so clean up here
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                !fileExistsWithCorrectHash(File(installDir, file.filename), file.fileSize, file.hash)
            }
            downloadInfo.updateStatusMessage(null)
            Timber.tag("Epic").d("Skipping ${files.size - pendingFiles.size} existing file(s), downloading ${pendingFiles.size}")

            val fileChunkIds = pendingFiles.map { f -> f.chunkParts.map { it.guidStr } }
            val chunkQueue = buildFileOrderedChunkQueue(manifest, fileChunkIds)

            // Native (Rust) chunk engine first; falls back to the Kotlin streaming pipeline.
            val dlcDownloadResult = downloadAndAssembleEpicChunksNative(
                manifestBytes = manifestData.manifestBytes,
                cdnUrls = cdnUrls,
                installDir = installDir,
                allFiles = files,
                pendingFiles = pendingFiles,
                downloadInfo = downloadInfo,
            ) ?: downloadAndAssembleEpicChunks(
                manifest = manifest,
                cdnUrls = cdnUrls,
                chunkCacheDir = chunkCacheDir,
                installDir = installDir,
                files = pendingFiles,
                downloadInfo = downloadInfo,
                chunkQueue = chunkQueue,
                chunkDir = chunkDir,
            )
            if (dlcDownloadResult.isFailure) return@withContext dlcDownloadResult

            NativeTreeDelete.deleteTreeFast(chunkCacheDir)

            // Update database
            try {
                epicManager.updateGame(game.copy(isInstalled = true, installPath = installPath))
                Timber.tag("Epic").i("Updated database: DLC ${game.title} marked as installed")
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "Failed to update database for DLC ${game.id}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "DLC download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Download the Epic Online Services overlay
     */
    suspend fun downloadOverlay(
        manifestResult: EpicManager.ManifestResult,
        installPath: String,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // ! Avoiding Cloudflare as it causes issues with some downloads and is inconsistent.
            // Matches the degradation behavior of downloadGame / downloadGameWithManifest:
            // if Cloudflare is the only CDN offered, fall back to it rather than hard-failing.
            val preferredCdnUrls = manifestResult.cdnUrls
                .filter { !it.baseUrl.startsWith("https://cloudflare.epicgamescdn.com") }
            val cdnUrls = rankCdnUrlsByProbe(
                preferredCdnUrls.ifEmpty { manifestResult.cdnUrls },
            )
            if (cdnUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No usable CDN URLs in manifest"))
            }

            val manifest = EpicManifest.readAll(manifestResult.manifestBytes)
            val chunks = manifest.chunkDataList?.elements
                ?: return@withContext Result.failure(Exception("Manifest contains no chunk data"))
            val files = manifest.fileManifestList?.elements
                ?: return@withContext Result.failure(Exception("Manifest contains no file list"))
            val chunkDir = manifest.getChunkDir()

            val installDir = File(installPath).also { it.mkdirs() }
            val chunkCacheDir = chunkCacheDirFor(context, installDir.absolutePath).also { it.mkdirs() }

            // Dummy DownloadInfo – overlay downloads are small and need no UI progress events
            val dummyDownloadInfo = DownloadInfo(
                jobCount = 1,
                gameId = -1,
                downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList(),
            )

            val parallelDownloads = PrefManager.downloadSpeed.coerceAtLeast(1)
            val downloadHttpClient = Net.httpForParallelDownloads(parallelDownloads)

            var downloadedChunks = 0
            val totalChunks = chunks.size

            chunks.chunked(parallelDownloads).forEach { batch ->
                val results = batch.map { chunk ->
                    async {
                        downloadChunkWithRetry(chunk, chunkCacheDir, chunkDir, cdnUrls, dummyDownloadInfo, downloadHttpClient)
                    }
                }.awaitAll()

                results.firstOrNull { it.isFailure }?.let { failure ->
                    NativeTreeDelete.deleteTreeFast(chunkCacheDir)
                    return@withContext Result.failure(
                        failure.exceptionOrNull() ?: Exception("Chunk download failed"),
                    )
                }

                downloadedChunks += batch.size
                onProgress?.invoke(downloadedChunks, totalChunks)
            }

            files.chunked(4).forEach { batch ->
                val results = batch.map { fileManifest ->
                    async { assembleFileSequential(fileManifest, chunkCacheDir, installDir) }
                }.awaitAll()

                results.firstOrNull { it.isFailure }?.let { failure ->
                    NativeTreeDelete.deleteTreeFast(chunkCacheDir)
                    return@withContext Result.failure(
                        failure.exceptionOrNull() ?: Exception("File assembly failed"),
                    )
                }
            }

            NativeTreeDelete.deleteTreeFast(chunkCacheDir)
            Timber.tag("Epic").i("downloadOverlay completed: $installPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "downloadOverlay failed for $installPath")
            Result.failure(e)
        }
    }

    /**
     * Download a single chunk with retry logic
     */
    private suspend fun downloadChunkWithRetry(
        chunk: app.gamenative.service.epic.manifest.ChunkInfo,
        chunkCacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        downloadInfo: DownloadInfo,
        downloadHttpClient: okhttp3.OkHttpClient,
    ): Result<File> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(MAX_CHUNK_RETRIES) { attempt ->
            val result = downloadChunk(chunk, chunkCacheDir, chunkDir, cdnUrls, downloadInfo, downloadHttpClient)

            if (result.isSuccess) {
                if (attempt > 0) {
                    Timber.tag("Epic").i("Chunk ${chunk.guidStr} downloaded successfully after ${attempt + 1} attempts")
                }
                return@withContext result
            }

            lastException = result.exceptionOrNull() as? Exception

            if (attempt < MAX_CHUNK_RETRIES - 1) {
                val delay = RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("Epic").w("Chunk ${chunk.guidStr} download failed (attempt ${attempt + 1}/$MAX_CHUNK_RETRIES): ${lastException?.message}. Retrying in ${delay}ms...")
                kotlinx.coroutines.delay(delay)
            }
        }

        Timber.tag("Epic").e(lastException, "Failed to download chunk ${chunk.guidStr} after $MAX_CHUNK_RETRIES attempts")
        Result.failure(lastException ?: Exception("Failed to download chunk ${chunk.guidStr}"))
    }

    private suspend fun rankCdnUrlsByProbe(cdnUrls: List<EpicManager.CdnUrl>): List<EpicManager.CdnUrl> {
        if (cdnUrls.size <= 1) return cdnUrls

        val rankedBaseUrls = CdnRankingUtils.rankBaseUrlsByHeadProbe(
            cdnUrls.map { it.baseUrl },
            Net.http,
            "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit",
        )
        val rankIndex = rankedBaseUrls.withIndex().associate { it.value to it.index }

        return cdnUrls.sortedBy { rankIndex[it.baseUrl] ?: Int.MAX_VALUE }
    }

    /**
     * Download a single chunk from Epic CDN with decompression
     */
    private suspend fun downloadChunk(
        chunk: app.gamenative.service.epic.manifest.ChunkInfo,
        chunkCacheDir: File,
        chunkDir: String,
        cdnUrls: List<EpicManager.CdnUrl>,
        downloadInfo: DownloadInfo,
        downloadHttpClient: okhttp3.OkHttpClient,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Dual-read: an existing file (either layout) is the skip/corrupt candidate;
            // on a miss the write target is always the sharded path.
            val decompressedFile = run {
                val resolved = resolveChunkFile(chunkCacheDir, chunk.guidStr)
                if (resolved.exists()) {
                    resolved
                } else {
                    shardedChunkFile(chunkCacheDir, chunk.guidStr).apply { parentFile?.mkdirs() }
                }
            }

            // Skip if already downloaded and decompressed
            if (decompressedFile.exists() && decompressedFile.length() == chunk.windowSize.toLong()) {
                // Quick verification - only verify if size matches
                if (verifyChunkHashBytes(decompressedFile.readBytes(), chunk.shaHash)) {
                    Timber.tag("Epic").d("Chunk ${chunk.guidStr} already exists and verified, skipping")
                    downloadInfo.updateBytesDownloaded(chunk.fileSize)
                    return@withContext Result.success(decompressedFile)
                } else {
                    Timber.tag("Epic").w("Chunk ${chunk.guidStr} exists but failed verification, re-downloading")
                    decompressedFile.delete()
                }
            }

            // Get chunk path for downloading
            val chunkPath = chunk.getPath(chunkDir)

            // Try each CDN base URL until one succeeds
            var lastException: Exception? = null
            for ((cdnIndex, cdnUrl) in cdnUrls.withIndex()) {
                try {
                    // Build full URL: baseUrl + cloudDir + chunkPath
                    val url = "${cdnUrl.baseUrl}${cdnUrl.cloudDir}/$chunkPath"

                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit")
                        .build()

                    // Use .use {} to ensure response is always closed, even on exception
                    downloadHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastException = Exception("HTTP ${response.code} downloading chunk from ${cdnUrl.baseUrl}")
                            return@use // Exit use block, response will be closed automatically
                        }

                        // Download and decompress Epic chunk file using streaming to avoid OOM exceptions
                        val responseBody = response.body!!
                        responseBody.byteStream().use { input ->
                            // Stream directly from network into chunk parser/decompressor.
                            // This removes an extra temp compressed-file write/read cycle.
                            decompressStreamingChunkToFile(input, decompressedFile, chunk.windowSize.toLong(), chunk.shaHash, downloadInfo)
                        }

                        return@withContext Result.success(decompressedFile)
                    }

                    // If we get here, response was unsuccessful, try next CDN
                    if (lastException != null) {
                        continue
                    }
                } catch (e: Exception) {
                    if (cdnIndex < cdnUrls.size - 1) {
                        Timber.tag("Epic").w(e, "Failed to download from ${cdnUrl.baseUrl}, trying next...")
                    }
                    lastException = e
                }
            }

            // All URLs failed
            return@withContext Result.failure(lastException ?: Exception("All CDN URLs failed for chunk ${chunk.guidStr}"))
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to download chunk ${chunk.guidStr}")
            Result.failure(e)
        }
    }

    /**
     * Decompress an Epic chunk file directly to output file with streaming hash verification
     * This avoids allocating huge ByteArrays (1.5GB) in memory
     */
    private fun decompressStreamingChunkToFile(
        inputStream: InputStream,
        outputFile: File,
        expectedSize: Long,
        expectedHash: ByteArray,
        downloadInfo: DownloadInfo,
    ) {
        val digest = MessageDigest.getInstance("SHA-1")
        var totalBytesWritten = 0L
        var lastProgressEmitAt = System.currentTimeMillis()

        inputStream.buffered().use { input ->
            // Read the entire header - determine size dynamically
            val headerStart = ByteArray(12)
            if (input.read(headerStart) != 12) {
                throw Exception("Failed to read chunk header start")
            }

            val startBuffer = ByteBuffer.wrap(headerStart).order(ByteOrder.LITTLE_ENDIAN)
            val magic = startBuffer.int
            if (magic != 0xB1FE3AA2.toInt()) {
                throw Exception("Invalid chunk magic: 0x${magic.toString(16)}")
            }

            val headerVersion = startBuffer.int
            val headerSize = startBuffer.int

            // Epic chunks can have different header sizes (62 or 66 bytes)
            // Minimum viable header is 62 bytes
            if (headerSize < 62 || headerSize > 66) {
                throw Exception("Invalid header size: $headerSize (expected 62-66 bytes)")
            }

            // Read the remaining header bytes
            val remainingSize = headerSize - 12
            val remainingBytes = ByteArray(remainingSize)
            if (input.read(remainingBytes) != remainingSize) {
                throw Exception("Failed to read remaining header: expected $remainingSize bytes")
            }

            // Parse the header fields from the remaining bytes sequentially
            // This matches the format in legendary/models/chunk.py
            val buffer = ByteBuffer.wrap(remainingBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Chunk header format (after magic/version/headerSize):
            // compressedSize: 4 bytes (file offset 12-15)
            // GUID: 16 bytes (file offset 16-31)
            // hash: 8 bytes (file offset 32-39)
            // storedAs: 1 byte (file offset 40)
            // SHA hash: 20 bytes (file offset 41-60)
            // For 66-byte headers:
            //   hash type: 1 byte (file offset 61)
            //   uncompressedSize: 4 bytes (file offset 62-65)
            // For 62-byte headers (older format):
            //   uncompressedSize: 4 bytes (file offset 58-61) - replaces hash type + padding

            // Ensure we have minimum required fields (up to SHA hash)
            if (buffer.remaining() < 50) {
                throw Exception("Buffer underflow: only ${buffer.remaining()} bytes available, need at least 50")
            }

            val compressedSize = buffer.int  // Read compressed size
            buffer.position(buffer.position() + 16)  // Skip GUID (16 bytes)
            buffer.position(buffer.position() + 8)   // Skip hash (8 bytes)
            val storedAs = buffer.get().toInt() and 0xFF  // Read storedAs flag
            val isCompressed = (storedAs and 0x1) == 0x1

            // Version Check to understand header spacing
            // Header version 2: includes sha_hash (20 bytes) + hash_type (1 byte) = 62 total bytes
            // Header version 3: adds uncompressed_size (4 bytes) = 66 total bytes
            if (headerVersion >= 2) {
                buffer.position(buffer.position() + 20)  // Skip SHA hash (20 bytes)
                if (buffer.remaining() >= 1) {
                    buffer.position(buffer.position() + 1)   // Skip hash type (1 byte)
                }
            }

            val uncompressedSize = if (headerVersion >= 3 && buffer.remaining() >= 4) {
                // Version 3+: uncompressedSize field is present (4 bytes)
                buffer.int
            } else {
                // Version 2 or no uncompressedSize field: use expectedSize parameter
                Timber.tag("Epic").d("Header version $headerVersion doesn't include uncompressedSize field, using expectedSize=$expectedSize")
                expectedSize.toInt()
            }

            outputFile.outputStream().buffered().use { output ->
                if (isCompressed) {
                    // Streaming decompression
                    val inflater = Inflater()
                    try {
                        val inputBuffer = ByteArray(65536) // 64KB compressed read buffer
                        val outputBuffer = ByteArray(65536) // 64KB decompressed write buffer
                        var endOfStream = false

                        while (totalBytesWritten < uncompressedSize && !endOfStream) {
                            // Feed more input if needed
                            if (inflater.needsInput() && !endOfStream) {
                                val bytesRead = input.read(inputBuffer)
                                if (bytesRead == -1) {
                                    endOfStream = true
                                    Timber.tag("Epic").d("Unexpected end of stream: read=$totalBytesWritten, expected=$uncompressedSize")
                                } else {
                                    downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressEmitAt >= STREAM_PROGRESS_TIME_INTERVAL_MS) {
                                        downloadInfo.emitProgressChange()
                                        lastProgressEmitAt = now
                                    }
                                    inflater.setInput(inputBuffer, 0, bytesRead)
                                }
                            }

                            // Try to decompress
                            try {
                                val decompressed = inflater.inflate(outputBuffer)
                                if (decompressed > 0) {
                                    output.write(outputBuffer, 0, decompressed)
                                    digest.update(outputBuffer, 0, decompressed)
                                    totalBytesWritten += decompressed
                                } else if (inflater.finished() || endOfStream) {
                                    // No more data available
                                    break
                                }
                            } catch (e: java.util.zip.DataFormatException) {
                                Timber.tag("Epic").d("DataFormatException during inflate: ${e.message}")
                                Timber.tag("Epic").d("  totalBytesWritten=$totalBytesWritten, expectedSize=$uncompressedSize")
                                Timber.tag("Epic").d("  inflater: finished=${inflater.finished()}, needsInput=${inflater.needsInput()}")
                                throw Exception("Failed to decompress chunk: ${e.message}", e)
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                } else {
                    // Already uncompressed - stream directly
                    val buffer = ByteArray(65536)
                    var remaining = compressedSize
                    while (remaining > 0) {
                        val toRead = minOf(remaining, buffer.size)
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmitAt >= STREAM_PROGRESS_TIME_INTERVAL_MS) {
                            downloadInfo.emitProgressChange()
                            lastProgressEmitAt = now
                        }
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead
                        remaining -= bytesRead
                    }
                }
            }
        }

        // Verify size
        if (totalBytesWritten != expectedSize) {
            Timber.tag("Epic").w("Warning - Size mismatch: expected=$expectedSize, actual=$totalBytesWritten, diff=${expectedSize - totalBytesWritten}")
            outputFile.delete()
            throw Exception("Decompressed size mismatch: expected $expectedSize, got $totalBytesWritten")
        }

        // Verify hash
        val actualHash = digest.digest()
        if (!actualHash.contentEquals(expectedHash)) {
            val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
            val actualHex = actualHash.joinToString("") { "%02x".format(it) }
            outputFile.delete()
            throw Exception("Chunk hash verification failed: expected $expectedHex, got $actualHex")
        }

        // Ensure UI receives a final progress update after this chunk's bytes.
        downloadInfo.emitProgressChange()
    }

    /**
     * Verify chunk SHA-1 hash from byte array
     */
    private fun verifyChunkHashBytes(data: ByteArray, expectedHash: ByteArray): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(data)
            val actualHash = digest.digest()
            val matches = actualHash.contentEquals(expectedHash)

            if (!matches) {
                val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
                val actualHex = actualHash.joinToString("") { "%02x".format(it) }
                Timber.tag("Epic").e("Hash mismatch: expected $expectedHex, got $actualHex")
            }

            matches
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Hash verification failed")
            false
        }
    }

    /**
     * True when the file on disk matches the manifest's size and full-file SHA-1,
     * so it can be skipped on resume. An all-zero manifest hash is treated as
     * unverifiable and the file is re-downloaded.
     */
    private fun fileExistsWithCorrectHash(outputFile: File, expectedSize: Long, expectedHash: ByteArray): Boolean {
        if (!outputFile.exists()) return false
        if (outputFile.length() != expectedSize) return false
        if (expectedHash.all { it == 0.toByte() }) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-1")
            outputFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().contentEquals(expectedHash)
        } catch (e: Exception) {
            Timber.tag("Epic").w(e, "Could not verify existing file ${outputFile.path}, re-downloading")
            false
        }
    }

    private fun buildFileOrderedChunkQueue(
        manifest: EpicManifest,
        fileChunkIds: List<List<String>>,
    ): List<app.gamenative.service.epic.manifest.ChunkInfo> {
        val orderedIds = StreamingAssembly.buildOrderedChunkQueue(fileChunkIds)
        return orderedIds.map { id ->
            manifest.chunkDataList?.getChunkByGuid(id)
                ?: throw IllegalStateException("Chunk $id referenced by file but not found in manifest")
        }
    }

    /**
     * Native (Rust) Epic chunk pipeline via GameDownloadService: fills `<installDir>/.chunks`
     * with verified, decompressed chunks for [pendingFiles] (a subset of [allFiles], the
     * manifest's full file list), then assembles the files with the same
     * [assembleFileSequential] step the overlay path uses.
     *
     * Returns null when the native engine is unavailable or declines the plan — the caller
     * then falls back to the Kotlin streaming pipeline ([downloadAndAssembleEpicChunks]).
     */
    @OptIn(InternalCoroutinesApi::class) // invokeOnCompletion(onCancelling = true)
    private suspend fun downloadAndAssembleEpicChunksNative(
        manifestBytes: ByteArray,
        cdnUrls: List<EpicManager.CdnUrl>,
        installDir: File,
        allFiles: List<app.gamenative.service.epic.manifest.FileManifest>,
        pendingFiles: List<app.gamenative.service.epic.manifest.FileManifest>,
        downloadInfo: DownloadInfo,
    ): Result<Unit>? = withContext(Dispatchers.IO) {
        val pendingFileIdx = pendingFiles.map { pending ->
            allFiles.indexOfFirst { it === pending }
        }
        if (pendingFileIdx.any { it < 0 }) return@withContext null

        val speedConfig = DownloadSpeedConfig()
        val cdnPrefixes = cdnUrls.map { it.baseUrl + it.cloudDir }.toTypedArray()
        val cancelFlag = AtomicBoolean(false)
        // Pause cancels the download JOB, but the native run below is a blocking call on an
        // IO thread — coroutine cancellation alone doesn't reach it (and kills the watcher
        // child before it can poll). Set the engine's cancel flag directly when the job
        // STARTS cancelling: onCancelling=true fires immediately on cancel(), whereas the
        // default (false) only fires once the job reaches its terminal state — i.e. after
        // the blocked native call returns, which is too late.
        val cancelHook = coroutineContext.job.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause is CancellationException) cancelFlag.set(true)
        }

        // Single credit budget for the WHOLE native run (fetch + assembly):
        // totalExpected − already-persisted bytes. The engine credits cached-skip
        // chunks at fetch time and reports assembled bytes afterwards; routing
        // both through this budget makes double-crediting on resume impossible
        // (the persisted snapshot is already counted) and caps the bar at 100%.
        val remainingCredit = java.util.concurrent.atomic.AtomicLong(
            (downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded()).coerceAtLeast(0L),
        )
        // Atomically take up to `want` from the budget; returns the amount taken.
        val takeCredit: (Long) -> Long = { want ->
            var taken = 0L
            remainingCredit.getAndUpdate { remaining ->
                taken = minOf(want, remaining)
                remaining - taken
            }
            taken
        }

        var creditedBytes = 0L
        var assemblyCredited = 0L
        var lastAssemblyEmitAt = 0L
        val listener = object : NativeEpicDownload.Listener {
            override fun onPlan(chunksTotal: Int, bytesTotal: Long, chunkDir: String) = Unit

            override fun onProgress(bytesDone: Long, bytesTotal: Long, chunksDone: Int, chunksTotal: Int) {
                // Native progress callbacks fire from multiple fetch-pool threads; the
                // read-check-set on creditedBytes must be atomic or the same cumulative
                // bytes get credited twice and progress runs ahead of reality.
                synchronized(this) {
                    val delta = bytesDone - creditedBytes
                    if (delta > 0L) {
                        creditedBytes = bytesDone
                        val taken = takeCredit(delta)
                        if (taken > 0L) {
                            downloadInfo.updateBytesDownloaded(taken)
                        }
                    }
                }
                if (chunksTotal > 0) {
                    downloadInfo.setProgress(chunksDone.toFloat() / chunksTotal.toFloat())
                }
                downloadInfo.updateStatusMessage("Downloading ($chunksDone/$chunksTotal chunks)")
            }

            override fun onAssemblyProgress(bytesWritten: Long) {
                // Assembly runs in-engine now (Rust); bytesWritten is cumulative.
                synchronized(this) {
                    val delta = bytesWritten - assemblyCredited
                    if (delta > 0L) {
                        assemblyCredited = bytesWritten
                        val taken = takeCredit(delta)
                        if (taken > 0L) {
                            downloadInfo.updateBytesDownloaded(taken)
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastAssemblyEmitAt >= 250L) {
                    lastAssemblyEmitAt = now
                    downloadInfo.updateStatusMessage("Assembling files...")
                    downloadInfo.emitProgressChange()
                }
            }

            override fun onLog(line: String) {
                Timber.tag("Epic").d(line)
            }

            override fun onComplete(success: Boolean, error: String, bytesCredited: Long) = Unit
        }

        val watcher = launch {
            while (isActive) {
                if (!downloadInfo.isActive()) {
                    cancelFlag.set(true)
                    return@launch
                }
                kotlinx.coroutines.delay(250)
            }
        }
        val result = try {
            GameDownloadService.downloadEpicChunks(
                manifest = manifestBytes,
                installDir = installDir.absolutePath,
                cdnPrefixes = cdnPrefixes,
                pendingFileIdx = pendingFileIdx.toIntArray(),
                expectedChunks = -1,
                expectedBytes = -1L,
                // Adaptive-window ceiling (ramps up only while the link delivers).
                maxWorkers = speedConfig.maxDownloads,
                processWorkers = speedConfig.maxDecompress,
                cancel = cancelFlag,
                listener = listener,
            )
        } finally {
            cancelHook.dispose()
            watcher.cancel()
        }

        if (!result.started) return@withContext null // engine unavailable → Kotlin pipeline
        if (result.cancelled) return@withContext Result.failure(Exception("Download cancelled"))
        if (!result.success) {
            return@withContext Result.failure(
                Exception(result.error.ifEmpty { "Chunk download failed" }),
            )
        }

        // The engine assembled every pending file in-process (Rust) and deleted
        // each cache chunk after its last consumer; this is only a safety sweep
        // for stray `.part` files from older interrupted runs.
        NativeTreeDelete.deleteTreeFast(File(installDir, ".chunks"))
        Result.success(Unit)
    }

    // assembles files as chunks arrive, deletes chunks once their last consumer is assembled
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadAndAssembleEpicChunks(
        manifest: EpicManifest,
        cdnUrls: List<EpicManager.CdnUrl>,
        chunkCacheDir: File,
        installDir: File,
        files: List<app.gamenative.service.epic.manifest.FileManifest>,
        downloadInfo: DownloadInfo,
        chunkQueue: List<app.gamenative.service.epic.manifest.ChunkInfo>,
        chunkDir: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val scope = CoroutineScope(Dispatchers.IO)
            val speedConfig = DownloadSpeedConfig()
            val parallelDownloads = speedConfig.maxDownloads
            val parallelAssemble = speedConfig.maxDecompress
            val downloadHttpClient = Net.httpForParallelDownloads(parallelDownloads)

            val totalChunks = chunkQueue.size
            val totalFiles = files.size
            val chunkUsageCounts = ConcurrentHashMap<String, AtomicInteger>()
            val downloadedChunkIds = newKeySet<String>()
            val pendingChunks = AtomicInteger(chunkQueue.size)

            // Calculate total expected installed size once (sum of all file sizes)
            val totalExpectedSize = files.sumOf { it.fileSize }

            val onExternalStorage = ContainerStorageManager.isOnExternalStorage(context, GameSource.EPIC, installDir.absolutePath)
            if (onExternalStorage) {
                val remainingBytes = files.sumOf { file ->
                    (file.fileSize - File(installDir, file.filename).length()).coerceAtLeast(0L)
                }
                StorageUtils.downloadSpaceShortfall(installDir, remainingBytes, chunkCacheDir)?.let {
                    return@withContext Result.failure(IOException(it))
                }
            }

            chunkQueue.forEach { chunkInfo ->
                chunkUsageCounts[chunkInfo.guidStr] = AtomicInteger(
                    files.sumOf { file -> file.chunkParts.count { chunk -> chunk.guidStr == chunkInfo.guidStr } }
                )
            }

            val networkChunkFlow = MutableSharedFlow<app.gamenative.service.epic.manifest.ChunkInfo>(extraBufferCapacity = Int.MAX_VALUE)
            val assembleFlow =
                MutableSharedFlow<Pair<app.gamenative.service.epic.manifest.ChunkInfo, Result<File>>>(extraBufferCapacity = Int.MAX_VALUE)

            var assemblyFailure: Throwable? = null

            // Assemble every file whose chunks are all present, as soon as it becomes ready.
            // Returns success(true) when the chunk was assembled, success(false) when it
            // was REQUEUED for re-download (still pending — caller must NOT count it as
            // done or decrement pendingChunks), failure on a real assembly error.
            suspend fun assembleReady(finishChunk: app.gamenative.service.epic.manifest.ChunkInfo): Result<Boolean> {
                val guidStr = finishChunk.guidStr
                if (!downloadInfo.isActive()) {
                    return Result.failure(Exception("Download cancelled"))
                }

                // 1. Find all files that contain this chunk
                val matchedFiles = files.filter { file ->
                    file.chunkParts.any { chunk -> chunk.guidStr == guidStr }
                }

                // 2. For each file found, try to assemble if all chunks are ready
                var assemblySuccessCount = 0

                // Cache vanished after the download phase (e.g. .chunks deleted between
                // resume and assembly): re-download the chunk instead of failing the
                // whole install with "Chunk file missing". The remove-guard ensures a
                // duplicate event for the same chunk cannot requeue it twice.
                if (!resolveChunkFile(chunkCacheDir, guidStr).exists()) {
                    Timber.tag("EPIC").w("Chunk $guidStr missing from cache at assembly; re-downloading")
                    if (downloadedChunkIds.remove(guidStr)) {
                        networkChunkFlow.tryEmit(finishChunk)
                    }
                    return Result.success(false)
                }

                matchedFiles.forEach { file ->
                    file.chunkParts.withIndex()
                        .filter { (_, chunk) -> chunk.guidStr == guidStr }
                        .forEach { (chunkIndex, chunk) ->
                            val result = assembleFileParallel(file, chunk, chunkCacheDir, installDir)
                            if (result.isSuccess) {
                                // 3. If assembly is successful and all chunks in downloadedChunkIds, increment file counter
                                assemblySuccessCount++
                            } else {
                                Timber.tag("EPIC").d(result.exceptionOrNull()?.message ?: "Failed to assemble ${file.filename}")
                            }
                        }
                }

                // 4. Decrement usage count only when assembly is successful
                if (assemblySuccessCount > 0) {
                    val usageCount = chunkUsageCounts[guidStr]?.addAndGet(-assemblySuccessCount)
                    if (usageCount != null && usageCount <= 0) {
                        val cacheFile = resolveChunkFile(chunkCacheDir, guidStr)
                        cacheFile.delete()
                    }
                }

                return Result.success(true)
            }

            val networkChunkJob: Job = scope.launch {
                networkChunkFlow
                    .flatMapMerge<app.gamenative.service.epic.manifest.ChunkInfo, Unit>(concurrency = parallelDownloads) { chunk ->
                        flow<Unit> {
                            if (!downloadInfo.isActive()) {
                                return@flow
                            }

                            val result = downloadChunkWithRetry(
                                chunk,
                                chunkCacheDir,
                                chunkDir,
                                cdnUrls,
                                downloadInfo,
                                downloadHttpClient,
                            )

                            // Always emit result to assembleFlow for processing (success or failure)
                            assembleFlow.tryEmit(chunk to result)
                            emit(Unit)
                        }
                    }
                    .flowOn(Dispatchers.IO)
                    .collect()
            }

            val assembleJob: Job = scope.launch {
                assembleFlow
                    .flatMapMerge<Pair<app.gamenative.service.epic.manifest.ChunkInfo, Result<File>>, Unit>(concurrency = parallelAssemble) { (chunk, result) ->
                        flow<Unit> {
                            if (!downloadInfo.isActive()) {
                                return@flow
                            }

                            if (result.isSuccess && assemblyFailure == null) {
                                // Successful download - add to completed set and try assembly
                                downloadedChunkIds.add(chunk.guidStr)

                                val assembleResult = assembleReady(chunk)
                                if (assembleResult.isFailure) {
                                    assemblyFailure = assembleResult.exceptionOrNull()
                                        ?: Exception("Failed to assemble ready files")
                                    Timber.tag("EPIC").d("Chunk ${chunk.guidStr} assembleReady Failed: ${assemblyFailure.message}")

                                    // Requeue the chunk for retry
                                    downloadedChunkIds.remove(chunk.guidStr)
                                    networkChunkFlow.tryEmit(chunk)
                                    return@flow
                                }
                                if (assembleResult.getOrThrow() == false) {
                                    // Requeued for re-download (cache vanished): still
                                    // pending — do not count progress or decrement.
                                    return@flow
                                }

                                val progress = downloadedChunkIds.size.toFloat() / totalChunks
                                downloadInfo.setProgress(progress)
                                downloadInfo.updateStatusMessage(
                                    "Downloading (${downloadedChunkIds.size}/$totalChunks chunks)",
                                )

                                // Decrement pending chunks counter
                                pendingChunks.decrementAndGet()
                            } else if (result.isFailure) {
                                // Failed download - handle retry logic
                                val exception = result.exceptionOrNull()
                                Timber.tag("EPIC").d("Chunk ${chunk.guidStr} download failed: ${exception?.message}")

                                if (exception is HttpStatusException) {
                                    Timber.tag("EPIC")
                                        .d("Chunk ${chunk.guidStr} download failed: HttpError ${exception.statusCode}, ${exception.message}")
                                    // TODO: Check error status
                                    if (exception.statusCode in listOf(401, 403, 404, 500)) {
                                        Timber.tag("EPIC").w("Chunk ${chunk.guidStr} urls expired, refreshing")
                                        networkChunkFlow.tryEmit(chunk)
                                        return@flow
                                    }
                                }

                                // For other failures, could add additional retry logic here
                                Timber.tag("EPIC").e("Chunk ${chunk.guidStr} failed permanently: ${exception?.message}")
                            }

                            emit(Unit)
                        }
                    }
                    .flowOn(Dispatchers.IO)
                    .collect()
            }

            // Start downloads by launching a separate coroutine to emit chunks
            val preAllocJob: Job = scope.launch {
                if (!downloadInfo.isActive()) {
                    Timber.tag("EPIC").w("Download cancelled by user")
                    return@launch
                }

                val chunksAdded = mutableListOf<String>()

                // Sort files by total chunk usage (lowest first) - files with less-shared chunks complete faster and free cache sooner
                val sortedFiles = files.sortedBy { file ->
                    file.chunkParts.sumOf { chunk ->
                        chunkUsageCounts[chunk.guidStr]?.get() ?: 0
                    }
                }
                Timber.tag("EPIC").d("Processing ${sortedFiles.size} files sorted by chunk usage (least shared first)")

                sortedFiles.forEach { file ->
                    if (!downloadInfo.isActive()) {
                        Timber.tag("EPIC").w("Download cancelled during file iteration")
                        return@launch
                    }
                    Timber.tag("EPIC").v("Pre-allocating ${file.filename}")

                    // Allocating file before download
                    val outputFile = File(installDir, file.filename)
                    outputFile.parentFile?.mkdirs()

                    val totalSize = file.fileSize

                    try {
                        // okio resize can OOM for large files on android.
                        // External volumes are exFAT: no sparse files, so setLength physically
                        // zero-fills the whole file there. Skip and let offset writes grow it.
                        RandomAccessFile(outputFile.path, "rw").use {
                            if (!onExternalStorage) {
                                it.setLength(totalSize)
                            } else if (it.length() > totalSize) {
                                it.setLength(totalSize)
                            }
                        }
                    } catch (e: IOException) {
                        throw IOException("Failed to allocate file ${outputFile.path}: ${e.message}")
                    }
                }

                chunkQueue.forEach { chunkInfo ->
                    chunksAdded.add(chunkInfo.guidStr)
                    networkChunkFlow.emit(chunkInfo)
                    Timber.tag("EPIC").v("Emitted chunk ${chunkInfo.guidStr} to download flow")
                }
            }

            Timber.tag("EPIC").d("Streaming download+assembly: $totalChunks chunks, $totalFiles files")

            downloadInfo.setProgress(0.0f)
            downloadInfo.setActive(true)

            preAllocJob.join()

            // Wait for all pending chunks to complete processing
            var lastPendingChunks = pendingChunks.get()
            var currentPendingChunks = lastPendingChunks
            var samePendingChunksAttempts = 0
            while (currentPendingChunks > 0) {
                if (!downloadInfo.isActive()) {
                    networkChunkJob.cancel()
                    assembleJob.cancel()
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                // Calculate storage usage stats (only scan cache dir, not entire install dir)
                val chunkCacheSize = calculateDirectorySize(chunkCacheDir)

                Timber.tag("EPIC").d(
                    """Waiting for $currentPendingChunks pending chunks
                    |  Cache: ${chunkCacheSize / 1_000_000}MB
                    |  Game files: ${totalExpectedSize / 1_000_000}MB
                    |  Total disk: ${(chunkCacheSize + totalExpectedSize) / 1_000_000}MB
                    """.trimMargin()
                )

                if (currentPendingChunks == lastPendingChunks) {
                    samePendingChunksAttempts++
                } else {
                    lastPendingChunks = currentPendingChunks
                    samePendingChunksAttempts = 0
                }

                if (samePendingChunksAttempts >= 10) {
                    val missingChunks = chunkQueue.filterNot { downloadedChunkIds.contains(it.guidStr) }
                    if (missingChunks.isNotEmpty()) {
                        Timber.tag("EPIC").w(
                            "Pending chunks stuck at $currentPendingChunks for $samePendingChunksAttempts checks; " +
                                    "re-emitting ${missingChunks.size} missing chunk(s) for retry",
                        )
                        missingChunks.forEach { networkChunkFlow.tryEmit(it) }
                    }

                    samePendingChunksAttempts = 0
                }

                // Wait for 5 seconds to recheck (not too quick due to added stats causing IO)
                delay(5000)

                currentPendingChunks = pendingChunks.get()
            }

            // Cancel the download flow jobs since no more chunks will be added
            networkChunkJob.cancel()

            // Cancel the assemble flow jobs since no more files will be added
            assembleJob.cancel()

            if (assemblyFailure != null) {
                return@withContext Result.failure(assemblyFailure!!)
            }

            Timber.tag("EPIC").i("Streaming complete: $totalChunks chunks, ${files.size} files assembled")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("EPIC").e(e, "Failed to download and assemble")
            Result.failure(e)
        }
    }

    /**
     * Assemble a file from its chunks
     */
    private suspend fun assembleFileSequential(
        fileManifest: app.gamenative.service.epic.manifest.FileManifest,
        chunkCacheDir: File,
        installDir: File,
        onPartWritten: ((Long) -> Unit)? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(installDir, fileManifest.filename)
            outputFile.parentFile?.mkdirs()

            outputFile.outputStream().use { output ->
                for (chunkPart in fileManifest.chunkParts) {
                    val chunkFile = resolveChunkFile(chunkCacheDir, chunkPart.guidStr)

                    if (!chunkFile.exists()) {
                        return@withContext Result.failure(Exception("Chunk file missing: ${chunkPart.guidStr}"))
                    }

                    // Read chunk data at specified offset
                    chunkFile.inputStream().use { input ->
                        input.skip(chunkPart.offset.toLong())

                        val buffer = ByteArray(65536) // Increased to 64KB for better I/O performance
                        var remaining = chunkPart.size.toLong()

                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val bytesRead = input.read(buffer, 0, toRead)

                            if (bytesRead == -1) break

                            output.write(buffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                    onPartWritten?.invoke(chunkPart.size.toLong())
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to assemble file ${fileManifest.filename}")
            Result.failure(e)
        }
    }

    /**
     * Assemble a file from its chunks
     */
    private suspend fun assembleFileParallel(
        fileManifest: app.gamenative.service.epic.manifest.FileManifest,
        chunk: ChunkPart,
        chunkCacheDir: File,
        installDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(installDir, fileManifest.filename)
            outputFile.parentFile?.mkdirs()

            // Get compressed chunk file (dual-read: sharded first, legacy flat second)
            val chunkFile = resolveChunkFile(chunkCacheDir, chunk.guidStr)

            if (!chunkFile.exists()) {
                return@withContext Result.failure(
                    Exception("Chunk file missing: ${chunk.guidStr}"),
                )
            }

            // Read chunk data at specified offset within the chunk file and write to output
            chunkFile.inputStream().use { input ->
                input.skip(chunk.offset.toLong())

                FileChannel.open(
                    outputFile.toPath(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE
                ).use { channel ->
                    channel.position(chunk.fileOffset)

                    val buffer = ByteArray(65536) // 64KB buffer for memory efficiency
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    var remaining = chunk.size.toLong()

                    while (remaining > 0) {
                        val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                        val bytesRead = input.read(buffer, 0, toRead)
                        if (bytesRead == -1) break

                        byteBuffer.clear()
                        byteBuffer.limit(bytesRead)
                        while (byteBuffer.hasRemaining()) {
                            channel.write(byteBuffer)
                        }
                        remaining -= bytesRead
                    }
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to assemble file ${fileManifest.filename}")
            Result.failure(e)
        }
    }

    /**
     * Log the directory structure of the installed game
     */
    private fun logDirectoryStructure(dir: File, prefix: String = "", isRoot: Boolean = true) {
        if (!dir.exists()) {
            Timber.tag("Epic").w("Directory does not exist: ${dir.absolutePath}")
            return
        }

        if (isRoot) {
            Timber.tag("Epic").i("=== Installation Directory Structure ===")
            Timber.tag("Epic").i("Root: ${dir.absolutePath}")
        }

        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()

        files.forEachIndexed { index, file ->
            val isLast = index == files.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val fileInfo = if (file.isDirectory) {
                "${file.name}/"
            } else {
                val size = formatFileSize(file.length())
                "${file.name} ($size)"
            }

            Timber.tag("Epic").i("$prefix$connector$fileInfo")

            // Recursively log subdirectories
            if (file.isDirectory) {
                val newPrefix = prefix + if (isLast) "    " else "│   "
                logDirectoryStructure(file, newPrefix, isRoot = false)
            }
        }

        if (isRoot) {
            val totalSize = calculateDirectorySize(dir)
            val fileCount = countFiles(dir)
            Timber.tag("Epic").i("=== Summary ===")
            Timber.tag("Epic").i("Total files: $fileCount")
            Timber.tag("Epic").i("Total size: ${formatFileSize(totalSize)}")
            Timber.tag("Epic").i("==================")
        }
    }

    /**
     * Format file size in human-readable format
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Count total number of files in a directory recursively
     */
    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile) return 1
        return dir.listFiles()?.sumOf { countFiles(it) } ?: 0
    }

    /**
     * Total size under [directory]. Symlinks are skipped to avoid double-counting.
     */
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            if (!directory.exists() || !directory.isDirectory) {
                return 0L
            }

            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                    continue
                }
                size += if (file.isDirectory) {
                    calculateDirectorySize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Timber.tag("Epic").w(e, "Error calculating directory size for ${directory.name}")
        }
        return size
    }
}
