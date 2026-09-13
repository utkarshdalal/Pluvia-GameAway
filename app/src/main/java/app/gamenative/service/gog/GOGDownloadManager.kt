package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.service.gog.api.DepotDirectory
import app.gamenative.service.gog.api.DepotFile
import app.gamenative.service.gog.api.DepotLink
import app.gamenative.service.gog.api.FileChunk
import app.gamenative.service.gog.api.GOGApiClient
import app.gamenative.service.gog.api.GOGManifestMeta
import app.gamenative.service.gog.api.GOGManifestParser
import app.gamenative.service.gog.api.V1DepotFile
import app.gamenative.enums.Marker
import app.gamenative.service.download.GameDownloadService
import app.gamenative.service.download.NativeTreeDelete
import app.gamenative.service.download.NativeGogDownload
import app.gamenative.service.download.NativeGogDownloadListener
import app.gamenative.utils.CdnRankingUtils
import app.gamenative.utils.ContainerStorageManager
import app.gamenative.utils.DownloadSpeedConfig
import app.gamenative.utils.StorageUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.Net
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap.newKeySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom exception for HTTP status errors with typed status code
 */
class HttpStatusException(val statusCode: Int, message: String) : Exception(message)

/**
 * GOGDownloadManager handles downloading GOG games
 *
 * GOG's CDN structure (Gen 2):
 * 1. Fetch build manifest (contains depots and product metadata)
 * 2. Fetch depot manifests (contains file lists with chunks)
 * 3. Get secure CDN links (time-limited URLs for chunks) -> We have issues here
 * 4. Download chunks from CDN (zlib compressed data) -> We have issues here
 * 5. Decompress and verify chunks (MD5)
 * 6. Assemble files from chunks
 *
 * GOG Chunk Format (Gen 2):
 * - Chunks are identified by compressedMd5 hash
 * - Downloaded from secure CDN URLs (time-limited)
 * - Compressed using zlib
 * - Verified using MD5 hash after decompression
 * - Multiple chunks assemble into single files
 */
@Singleton
class GOGDownloadManager @Inject constructor(
    private val apiClient: GOGApiClient,
    private val parser: GOGManifestParser,
    private val gogManager: GOGManager,
    @ApplicationContext private val context: Context,
) {
    private val WINDOWS_OS_VERSION = "windows"

    /**
     * Context needed to refresh secure CDN links when they expire
     */
    private data class SecureLinkContext(
        val gameId: String,
        val generation: Int,
        val productIds: Set<String>,
        val chunkToProductMap: Map<String, String>,
    )

    companion object {
        private const val CHUNK_BUFFER_SIZE = 1024 * 1024 // 1MB buffer
        private const val MAX_CHUNK_RETRIES = 3 // Maximum retries per chunk
        private const val MAX_CHUNK_ASSEMBLY_ATTEMPTS = 3 // Re-fetch+reassemble attempts before skipping a chunk
        private const val RETRY_DELAY_MS = 1000L // Initial retry delay in milliseconds
        private const val DEPENDENCY_URL = "https://content-system.gog.com/dependencies/repository?generation=2"
        private const val STREAM_PROGRESS_TIME_INTERVAL_MS = 200L
    }

    /**
     * Download and install a GOG game
     *
     * @param gameId GOG game ID (numeric)
     * @param installPath Directory where game will be installed
     * @param downloadInfo Progress tracker
     * @param language Container language name (e.g. "english", "german"). Used to resolve GOG manifest language codes when filtering depots. See [GOGConstants.containerLanguageToGogCodes].
     * @param withDlcs Whether to include DLC content
     * @param supportDir Optional directory for support files (redistributables)
     * @return Result indicating success or failure
     */
    suspend fun downloadGame(
        gameId: String,
        installPath: File,
        downloadInfo: DownloadInfo,
        language: String = GOGConstants.GOG_FALLBACK_DOWNLOAD_LANGUAGE,
        withDlcs: Boolean = false,
        supportDir: File? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").i("Starting download for game $gameId to ${installPath.absolutePath}")

            if(supportDir != null) {
                Timber.tag("GOG").i("Will also put dependencies into ${supportDir.absolutePath}")
            }

            // Get the actual game from database to check what ID we have stored
            val dbGame = gogManager.getGameFromDbById(gameId)

            if (dbGame == null) {
                return@withContext Result.failure(
                    Exception("Failed to fetch game from DB"),
                )
            }

            Timber.tag("GOG").d("Database game ID: ${dbGame.id}, title: ${dbGame.title}")

            // Emit download started event so UI can attach progress listeners
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, true),
            )

            downloadInfo.updateStatusMessage("Fetching builds...")

            // Step 1: Get available builds — prefer Gen 2, fall back to Gen 1 (legacy)
            val selectedBuild = run {
                val gen2Result = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 2)
                if (gen2Result.isFailure) {
                    return@withContext Result.failure(
                        gen2Result.exceptionOrNull() ?: Exception("Failed to fetch Gen 2 builds"),
                    )
                }
                parser.selectBuild(gen2Result.getOrThrow().items, preferredGeneration = 2, platform = WINDOWS_OS_VERSION)
                    ?.let { return@run it }
                val gen1Result = apiClient.getBuildsForGame(gameId, WINDOWS_OS_VERSION, generation = 1)
                if (gen1Result.isFailure) {
                    return@withContext Result.failure(
                        gen1Result.exceptionOrNull() ?: Exception("Failed to fetch builds"),
                    )
                }
                val builds = gen1Result.getOrThrow()
                parser.selectBuild(builds.items, preferredGeneration = 1, platform = WINDOWS_OS_VERSION)
                    ?: run {
                        val hint = when {
                            builds.items.isEmpty() -> "No builds returned for Windows (game may be Linux/Mac only)."
                            else -> "No Windows build. Available: ${builds.items.joinToString { "Gen ${it.generation}/${it.platform}" }}."
                        }
                        return@withContext Result.failure(Exception("No suitable build found for Windows. $hint"))
                    }
            }

            Timber.tag("GOG").i("Selected build: ${selectedBuild.buildId} (Gen ${selectedBuild.generation}, Platform: ${selectedBuild.platform})")
            Timber.tag("GOG").d("Build productId: ${selectedBuild.productId}, input gameId: $gameId")
            Timber.tag("GOG").d("Full build details: buildId=${selectedBuild.buildId}, productId=${selectedBuild.productId}, platform=${selectedBuild.platform}, gen=${selectedBuild.generation}, version=${selectedBuild.versionName}, branch=${selectedBuild.branch}, legacyBuildId=${selectedBuild.legacyBuildId}")

            val realGameId = gameId

            downloadInfo.updateStatusMessage("Fetching manifest...")

            // Step 2: Fetch main manifest
            val gameManifestResult = apiClient.fetchManifest(selectedBuild.link)
            if (gameManifestResult.isFailure) {
                return@withContext Result.failure(
                    gameManifestResult.exceptionOrNull() ?: Exception("Failed to fetch manifest"),
                )
            }

            val gameManifest = gameManifestResult.getOrThrow()
            Timber.tag("GOG").d("Game Manifest: ${gameManifest.installDirectory}, ${gameManifest.depots.size} depot(s)")
            Timber.tag("GOG").d("Game Manifest baseProductId: ${gameManifest.baseProductId}")

            gameManifest.products?.let { products ->
                Timber.tag("GOG").d("Manifest products: ${products.joinToString { "name=${it.name}, id=${it.productId}" }}")
            }

            // Gen 1 (legacy): different manifest format and download flow (direct file URLs, no chunks)
            if (selectedBuild.generation == 1 && gameManifest.productTimestamp != null) {
                Timber.tag("GOG").i("Using Gen 1 (legacy) downloader for game $gameId")
                return@withContext downloadGameGen1(
                    gameId = gameId,
                    installPath = installPath,
                    downloadInfo = downloadInfo,
                    gameManifest = gameManifest,
                    selectedBuild = selectedBuild,
                    language = language,
                    withDlcs = withDlcs,
                    supportDir = supportDir,
                )
            }

            Timber.tag("GOG").i("Using Gen 2 downloader for game $gameId")

            // Grab Dependencies from the gameManifest for later.
            val dependencies = gameManifest.dependencies

            downloadInfo.updateStatusMessage("Filtering depots...")

            // Step 3: Filter depots by container language (parser resolves to GOG codes and tries English fallback)
            val (languageDepots, effectiveLang) = parser.filterDepotsByLanguage(gameManifest, language)
            if (languageDepots.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No depots found for any requested or fallback (English) languages"),
                )
            }

            // Filter by ownership to exclude unowned DLC depots
            val ownedGameIds = gogManager.getAllGameIds()
            val depots = parser.filterDepotsByOwnership(languageDepots, ownedGameIds)
            if (depots.isEmpty()) {
                return@withContext Result.failure(Exception("No owned depots found for language: $effectiveLang"))
            }

            Timber.tag("GOG").d("Found ${depots.size} owned depot(s) for $effectiveLang")
            depots.forEachIndexed { index, depot ->
                Timber.tag("GOG").d("  Depot $index: productId=${depot.productId}, manifest=${depot.manifest}, size=${depot.size}, compressedSize=${depot.compressedSize}")
            }

            downloadInfo.updateStatusMessage("Fetching depot manifests...")

            // Step 4: Fetch depot manifests to get file lists
            // Track which depot each file came from for proper productId mapping
            data class FileWithDepot(val file: DepotFile, val depotProductId: String)
            val allFilesWithDepots = mutableListOf<FileWithDepot>()
            // Symlinks and empty directories declared in the depot manifests. These carry no chunks,
            // so they bypass the chunk download/assemble path and must be created explicitly.
            val allLinks = mutableListOf<Pair<DepotLink, String>>()
            val allDirectories = mutableListOf<Pair<DepotDirectory, String>>()
            // Raw inflated depot-manifest JSON per depot productId — feeds the native GOG engine.
            val rawDepotJsonByProduct = mutableMapOf<String, MutableList<String>>()

            for ((index, depot) in depots.withIndex()) {
                downloadInfo.updateStatusMessage("Fetching depot ${index + 1}/${depots.size}...")

                val depotResult = apiClient.fetchDepotManifestWithRaw(depot.manifest)
                if (depotResult.isFailure) {
                    return@withContext Result.failure(
                        depotResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"),
                    )
                }

                val (depotManifest, depotRawJson) = depotResult.getOrThrow()
                rawDepotJsonByProduct.getOrPut(depot.productId) { mutableListOf() }.add(depotRawJson)
                depotManifest.files.forEach { file ->
                    allFilesWithDepots.add(FileWithDepot(file, depot.productId))
                }
                depotManifest.links.forEach { link -> allLinks.add(link to depot.productId) }
                depotManifest.directories.forEach { dir -> allDirectories.add(dir to depot.productId) }
            }

            val allFiles = allFilesWithDepots.map { it.file }
            Timber.tag("GOG").d("Total files from all depots: ${allFiles.size}")

            // Step 5: Separate base game, DLC, and support files
            val (baseFiles, dlcFiles) = parser.separateBaseDLC(allFiles, gameManifest.baseProductId)
            val filesToDownload = if (withDlcs) baseFiles + dlcFiles else baseFiles
            var (gameFiles, supportFiles) = parser.separateSupportFiles(filesToDownload)

            // Filter out files that already exist with correct size (incremental download).
            // On a resume this MD5-reads every completed file, which can take minutes for
            // a large install — surface it in the UI and honor cancellation between files.
            val gameInstallDir = installPath
            downloadInfo.updateStatusMessage("Verifying existing files...")
            val beforeCount = gameFiles.size
            gameFiles = gameFiles.filter { file ->
                if (!downloadInfo.isActive()) {
                    MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                val outputFile = File(gameInstallDir, file.path)
                val expectedSize = file.chunks.sumOf { it.size }
                !fileExistsWithCorrectSize(outputFile, expectedSize, file.md5)
            }
            Timber.tag("GOG").d("Skipping ${beforeCount - gameFiles.size} existing file(s), downloading ${gameFiles.size}")

            val beforeSupportCount = supportFiles.size
            supportFiles = supportFiles.filter { file ->
                if (!downloadInfo.isActive()) {
                    MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                val installRelativePath = getSupportInstallPath(file.path)
                val outputFile = File(gameInstallDir, installRelativePath)
                val expectedSize = file.chunks.sumOf { it.size }
                !fileExistsWithCorrectSize(outputFile, expectedSize, file.md5)
            }
            downloadInfo.updateStatusMessage(null)
            val supportFilesForGameDirAssemble = supportFiles.map { file ->
                val installRelativePath = getSupportInstallPath(file.path)
                if (installRelativePath != file.path) file.copy(path = installRelativePath) else file
            }
            Timber.tag("GOG").d(
                "Skipping ${beforeSupportCount - supportFiles.size} existing support file(s), downloading ${supportFiles.size}",
            )

            // Calculate sizes separately for transparency
            val (baseGameFiles, _) = parser.separateSupportFiles(baseFiles)
            val baseGameSize = parser.calculateTotalSize(baseGameFiles)
            val dlcSize = if (withDlcs && dlcFiles.isNotEmpty()) {
                val (dlcGameFiles, _) = parser.separateSupportFiles(dlcFiles)
                parser.calculateTotalSize(dlcGameFiles)
            } else {
                0L
            }

            Timber.tag("GOG").d(
                """
                |Download plan:
                |  Base game files: ${baseFiles.size}
                |  DLC files: ${dlcFiles.size}
                |  Game files to download: ${gameFiles.size}
                |  Support files: ${supportFiles.size}
                |  Base game size: ${baseGameSize / 1_000_000.0} MB
                |  DLC size: ${dlcSize / 1_000_000.0} MB
                |  Including DLCs: $withDlcs
                """.trimMargin(),
            )

            // Step 6: Calculate sizes and extract chunk hashes
            val allDownloadFiles = gameFiles + supportFiles
            val totalSize = parser.calculateTotalSize(allDownloadFiles)
            // Progress total = UNCOMPRESSED bytes: the native engine credits inflated chunk
            // bytes (piece streams topped up to the inflated size at chunk finish).
            val totalUncompressedSize = parser.calculateUncompressedSize(allDownloadFiles)
            val chunkHashes = parser.extractChunkHashes(allDownloadFiles)

            Timber.tag("GOG").d(
                """
                |Download stats:
                |  Total compressed size: ${totalSize / 1_000_000.0} MB (${if (withDlcs) "including DLC" else "base game only"})
                |  Total uncompressed size: ${totalUncompressedSize / 1_000_000.0} MB
                |  Unique chunks: ${chunkHashes.size}
                |  Files: ${allDownloadFiles.size}
                """.trimMargin(),
            )

            val resumedBytes = downloadInfo.getBytesDownloaded()
            downloadInfo.setTotalExpectedBytes(totalUncompressedSize + resumedBytes)

            // Step 7: Get secure CDN links for chunks
            downloadInfo.updateStatusMessage("Getting secure download links...")

            // Build mapping of product ID to secure URLs and chunk to product ID
            val productUrlMap = mutableMapOf<String, List<String>>()
            val chunkToProductMap = mutableMapOf<String, String>()

            Timber.tag("GOG").d("Mapping chunks to products. gameId parameter: $gameId, realGameId: $realGameId, manifest baseProductId: ${gameManifest.baseProductId}")

            val filesToDownloadPaths = allDownloadFiles.map { it.path }.toSet()
            // Map each chunk to its product ID using depot info
            allFilesWithDepots.forEach { (file, depotProductId) ->
                if (file.path !in filesToDownloadPaths) return@forEach
                // Use depot's productId as fallback when file has null/placeholder productId

                // TODO: Remove this logic and always use the depotProductId.
                val productId = when {
                    file.productId == null -> {
                        Timber.tag("GOG").d("File ${file.path} has null productId, using depotProductId: $depotProductId")
                        depotProductId
                    }
                    file.productId == "2147483047" -> {
                        Timber.tag("GOG").d("File ${file.path} has placeholder productId, using depotProductId: $depotProductId")
                        depotProductId
                    }
                    else -> {
                        Timber.tag("GOG").d("File ${file.path} has productId: ${file.productId}")
                        file.productId
                    }
                }

                // Only include files from products the user owns
                if (productId in ownedGameIds) {
                    file.chunks.forEach { chunk ->
                        chunkToProductMap[chunk.compressedMd5] = productId
                    }
                } else {
                    Timber.tag("GOG").d("Skipping file ${file.path} from unowned product $productId")
                }
            }

            // Get unique product IDs we need to fetch secure links for
            val productIds = chunkToProductMap.values.toSet()
            Timber.tag("GOG").d("Need secure links for ${productIds.size} owned product(s): ${productIds.joinToString()}")
            Timber.tag("GOG").d("Mapped ${chunkToProductMap.size} chunks to products")

            // Fetch secure links for each product
            for (productId in productIds) {
                val linksResult = apiClient.getSecureLink(
                    productId = productId,
                    path = "/",
                    generation = selectedBuild.generation,
                )
                if (linksResult.isSuccess) {
                    val urls = CdnRankingUtils.rankBaseUrlsByHeadProbe(
                        linksResult.getOrThrow().urls,
                        Net.http,
                        "GOG Galaxy",
                    )
                    productUrlMap[productId] = urls
                } else {
                    return@withContext Result.failure(
                        linksResult.exceptionOrNull() ?: Exception("Failed to get secure links for product $productId"),
                    )
                }
            }

            // Build chunk URL map using the correct product URL for each chunk
            val chunkUrlCandidates = parser.buildChunkUrlCandidatesWithProducts(chunkHashes, chunkToProductMap, productUrlMap)

            // Store context for refreshing secure links if they expire
            val secureLinkContext = SecureLinkContext(
                gameId = realGameId,
                generation = selectedBuild.generation,
                productIds = productIds,
                chunkToProductMap = chunkToProductMap,
            )

            // Step 8+9: Download chunks and assemble files. The byte-moving engine is Rust
            // (GameDownloadService → libgndownload store_dl/gog); the Kotlin streaming loop is
            // the fallback when the native library is unavailable.
            Timber.tag("GOG").i("Downloading and assembling game $gameId")

            // Mark download as in-progress so UI and install checks can detect partial installs
            installPath.mkdirs()
            MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            downloadInfo.updateStatusMessage("Downloading...")

            // Cache lives on internal storage: on exFAT SD cards (dirsync mount) every
            // create/rename/delete in the cache dir is a synchronous directory flush,
            // which dominates download time for small chunks.
            val chunkCacheDir = File(context.cacheDir, "gog_chunks/$gameId")
            chunkCacheDir.mkdirs()
            gameInstallDir.mkdirs()

            // Resolve each pending file's productId (same rules as the chunk→product map) for
            // the per-product native runs.
            val pendingPathsToProduct = mutableMapOf<String, String>()
            run {
                val pendingPaths = (gameFiles + supportFilesForGameDirAssemble).map { it.path }.toSet()
                allFilesWithDepots.forEach { (file, depotProductId) ->
                    if (file.path !in pendingPaths) return@forEach
                    val productId = when {
                        file.productId == null -> depotProductId
                        file.productId == "2147483047" -> depotProductId
                        else -> file.productId!!
                    }
                    if (productId in ownedGameIds) {
                        pendingPathsToProduct[file.path] = productId
                        // Support files are path-remapped (getSupportInstallPath) for assembly.
                        val remapped = getSupportInstallPath(file.path)
                        if (remapped != file.path) pendingPathsToProduct[remapped] = productId
                    }
                }
            }

            val nativeResult = downloadGameGen2Native(
                rawDepotJsonByProduct = rawDepotJsonByProduct,
                productUrlMap = productUrlMap,
                pendingPathsToProduct = pendingPathsToProduct,
                pendingPathsInOrder = (gameFiles + supportFilesForGameDirAssemble).map { it.path },
                // Original depot paths (BEFORE getSupportInstallPath strips "app/"):
                // the relocation pass matches its app/ roots against these.
                supportDepotPaths = supportFiles.map { it.path }.toSet(),
                installDir = gameInstallDir,
                downloadInfo = downloadInfo,
                baseProductId = gameManifest.baseProductId,
                generation = selectedBuild.generation,
            )

            val downloadAndAssembleResult = nativeResult ?: downloadAndAssembleChunks(
                chunkUrlCandidates = chunkUrlCandidates,
                chunkCacheDir = chunkCacheDir,
                installDir = gameInstallDir,
                files = gameFiles + supportFilesForGameDirAssemble,
                downloadInfo = downloadInfo,
                chunkHashes = chunkHashes,
                secureLinkContext = secureLinkContext,
                chunkToProductMap = chunkToProductMap,
            )

            if (downloadAndAssembleResult.isFailure) {
                // Keep the chunk cache so an interrupted download can resume from it
                MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                return@withContext downloadAndAssembleResult
            }

            NativeTreeDelete.deleteTreeFast(chunkCacheDir)

            // Create declared directories and symlinks (no chunks, so not handled by the assemble path).
            // Gate by base/DLC ownership the same way files are: only the base product unless DLCs are included.
            val includeProduct = { productId: String -> productId == gameManifest.baseProductId || withDlcs }
            createDirectoriesAndLinks(
                installDir = gameInstallDir,
                directories = allDirectories.filter { includeProduct(it.second) }.map { it.first },
                links = allLinks.filter { includeProduct(it.second) }.map { it.first },
            )

            // Download Dependencies (They will either go to root or supportDir depending on )
            if (supportDir != null && dependencies.isNotEmpty()) {
                downloadInfo.updateStatusMessage("Downloading dependencies...")
                supportDir.mkdirs()

                val dependencyResult = downloadDependencies(gameId, dependencies, installPath, supportDir, downloadInfo)
                if (dependencyResult.isFailure) {
                    if (!downloadInfo.isActive()) {
                        MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                        return@withContext dependencyResult
                    }
                    Timber.tag("GOG").w("Failed to install Dependencies: ${dependencyResult.exceptionOrNull()?.message}")
                }

            }

            // Step 11: Cleanup
            NativeTreeDelete.deleteTreeFast(chunkCacheDir)

            saveManifestToGameDir(installPath, gameManifest, selectedBuild.buildId, selectedBuild.versionName, effectiveLang)

            finalizeInstallSuccess(gameId, installPath, downloadInfo)
            Timber.tag("GOG").i("Download completed successfully for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Download failed: ${e.message}")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            // Emit download stopped event on failure
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
            )

            // Ensure in-progress marker is cleared on failure
            MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            Result.failure(e)
        }
    }

    /**
     * Saves manifest data needed for post-install setup (scriptinterpreter or temp_executable) to
     * installPath/_gog_manifest.json. Used on first launch to create registry keys etc.
     * @param language Language used for the download (from the selected language depots).
     */
    private fun saveManifestToGameDir(
        installPath: File,
        gameManifest: GOGManifestMeta,
        buildId: String,
        versionName: String,
        language: String,
    ) {
        try {
            val productsArray = JSONArray()
            gameManifest.products.forEach { p ->
                productsArray.put(
                    JSONObject().apply {
                        put("productId", p.productId)
                        put("name", p.name)
                        put("temp_executable", p.temp_executable ?: "")
                        put("temp_arguments", p.temp_arguments ?: "")
                    },
                )
            }
            val supportCommandsArray = JSONArray()
            gameManifest.supportCommands.forEach { c ->
                supportCommandsArray.put(
                    JSONObject().apply {
                        put("executable", c.executable)
                        put("gameID", c.gameId)
                        put("argument", c.argument)
                        put("languages", JSONArray(c.languages))
                    },
                )
            }
            val root = JSONObject().apply {
                put("version", 2)
                put("baseProductId", gameManifest.baseProductId)
                put("scriptInterpreter", gameManifest.scriptInterpreter)
                put("products", productsArray)
                put("supportCommands", supportCommandsArray)
                put("buildId", buildId)
                put("versionName", versionName)
                put("language", language)
            }
            val file = File(installPath, GOGManifestUtils.MANIFEST_FILE_NAME)
            file.writeText(root.toString())
            Timber.tag("GOG").d("Saved setup manifest to ${file.absolutePath} (scriptInterpreter=${gameManifest.scriptInterpreter})")
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Failed to save GOG setup manifest")
        }
    }

    /**
     * Shared finalization after a successful install: update DB, set download complete, emit events.
     * Used by both Gen 2 and Gen 1 success paths.
     */
    private suspend fun finalizeInstallSuccess(gameId: String, installPath: File, downloadInfo: DownloadInfo) {
        downloadInfo.updateStatusMessage("Updating database...")
        try {
            val game = gogManager.getGameFromDbById(gameId)
            if (game != null) {
                val installSize = calculateDirectorySize(installPath)
                gogManager.updateGame(game.copy(isInstalled = true, installPath = installPath.absolutePath, installSize = installSize))
                downloadInfo.clearPersistedBytesDownloaded(installPath.absolutePath)
                Timber.tag("GOG").i("Updated database: game marked as installed, size: ${installSize / 1_000_000} MB")
            } else {
                Timber.tag("GOG").w("Game $gameId not found in database, skipping DB update")
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to update database for game $gameId")
        }
        downloadInfo.updateStatusMessage("Complete")
        downloadInfo.setProgress(1.0f)
        downloadInfo.setActive(false)
        downloadInfo.emitProgressChange()
        MarkerUtils.removeMarker(installPath.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        MarkerUtils.addMarker(installPath.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)
        app.gamenative.PluviaApp.events.emitJava(
            app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
        )
        app.gamenative.PluviaApp.events.emitJava(
            app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(gameId.toIntOrNull() ?: 0, app.gamenative.data.GameSource.GOG),
        )
    }

    /**
     * Gen 1 (legacy) download: one main.bin per depot; files are read via Range requests (offset/size).
     * See heroic-gogdl: task_executor.py v1() uses endpoint["parameters"]["path"] += "/main.bin" and Range: bytes=offset-(offset+size-1).
     */
    private suspend fun downloadGameGen1(
        gameId: String,
        installPath: File,
        downloadInfo: DownloadInfo,
        gameManifest: app.gamenative.service.gog.api.GOGManifestMeta,
        selectedBuild: app.gamenative.service.gog.api.GOGBuild,
        language: String,
        withDlcs: Boolean,
        supportDir: File?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").i("Starting Gen 1 (legacy) download for game $gameId")
            val timestamp = gameManifest.productTimestamp ?: return@withContext Result.failure(Exception("Gen 1 manifest missing productTimestamp"))
            val platform = selectedBuild.platform
            val ownedGameIds = gogManager.getAllGameIds()

            // Filter depots by selected language (same logic as Gen 2), then by ownership
            downloadInfo.updateStatusMessage("Filtering depots...")
            val (languageDepots, effectiveLang) = parser.filterDepotsByLanguage(gameManifest, language)
            if (languageDepots.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No depots found for requested or fallback (English) languages"),
                )
            }
            val depots = parser.filterDepotsByOwnership(languageDepots, ownedGameIds)
            if (depots.isEmpty()) {
                return@withContext Result.failure(Exception("No owned depots found"))
            }

            val baseProductId = gameManifest.baseProductId
            val filesToDownload = if (withDlcs) depots else depots.filter { it.productId == baseProductId }
            if (filesToDownload.isEmpty()) {
                return@withContext Result.failure(Exception("No depots to download"))
            }

            val productIds = filesToDownload.map { it.productId }.toSet()
            val securePath = "/$platform/$timestamp/"
            val productUrlMap = mutableMapOf<String, List<String>>()
            for (productId in productIds) {
                val linksResult = apiClient.getSecureLink(productId = productId, path = securePath, generation = 1)
                if (linksResult.isFailure) {
                    return@withContext Result.failure(linksResult.exceptionOrNull() ?: Exception("Failed to get secure link for product $productId"))
                }
                productUrlMap[productId] = linksResult.getOrThrow().urls
            }

            data class FileWithProduct(val file: V1DepotFile, val productId: String)
            val allV1Files = mutableListOf<FileWithProduct>()

            downloadInfo.updateStatusMessage("Fetching depot manifests...")
            for ((idx, depot) in filesToDownload.withIndex()) {
                downloadInfo.updateStatusMessage("Fetching depot ${idx + 1}/${filesToDownload.size}...")
                val depotJsonResult = apiClient.fetchDepotManifestV1(depot.productId, platform, timestamp, depot.manifest)
                if (depotJsonResult.isFailure) {
                    return@withContext Result.failure(depotJsonResult.exceptionOrNull() ?: Exception("Failed to fetch depot manifest"))
                }
                val v1Files = parser.parseV1DepotManifest(depotJsonResult.getOrThrow())
                v1Files.forEach { allV1Files.add(FileWithProduct(it, depot.productId)) }
            }

            var gameFiles = allV1Files.filter { !it.file.isSupport }
            var supportFiles = allV1Files.filter { it.file.isSupport }
            gameFiles = gameFiles.filter { f ->
                val outFile = File(installPath, f.file.path)
                !fileExistsWithCorrectSize(outFile, f.file.size, f.file.hash.takeIf { it.isNotEmpty() })
            }
            if (supportDir != null) {
                supportFiles = supportFiles.filter { f ->
                    val outFile = File(supportDir, f.file.path)
                    !fileExistsWithCorrectSize(outFile, f.file.size, f.file.hash.takeIf { it.isNotEmpty() })
                }
            }
            val totalSize = gameFiles.sumOf { it.file.size } +
                if (supportDir != null) supportFiles.sumOf { it.file.size } else 0L
            val resumedBytes = downloadInfo.getBytesDownloaded()
            downloadInfo.setTotalExpectedBytes(totalSize + resumedBytes)
            downloadInfo.updateStatusMessage("Downloading files...")
            downloadInfo.setProgress(0f)
            downloadInfo.setActive(true)
            downloadInfo.emitProgressChange()

            val totalFiles = gameFiles.size + if (supportDir != null) supportFiles.size else 0
            var doneFiles = 0

            // Gen 1: one main.bin URL per product; each file is fetched with Range: bytes=offset-(offset+size-1)
            val mainBinUrlByProduct = productUrlMap.mapValues { (_, urls) ->
                buildGen1MainBinUrl(urls.firstOrNull())
            }

            fun downloadOneFile(f: FileWithProduct, baseDir: File): Result<Unit> {
                val file = f.file
                val outFile = File(baseDir, file.path)
                outFile.parentFile?.mkdirs()

                if (file.size == 0L) {
                    outFile.createNewFile()
                    return Result.success(Unit)
                }

                val mainBinUrl = mainBinUrlByProduct[f.productId]
                    ?: return Result.failure(Exception("No main.bin URL for product ${f.productId}"))

                val offset = file.offset
                if (offset == null) {
                    return Result.failure(Exception("Gen 1 file ${file.path} has no offset (main.bin range request required)"))
                }

                val rangeHeader = "bytes=$offset-${offset + file.size - 1}"
                val request = Request.Builder()
                    .url(mainBinUrl)
                    .header("User-Agent", "GOG Galaxy")
                    .header("Range", rangeHeader)
                    .build()

                return try {
                    Net.http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code} for ${file.path}"))
                        val body = response.body ?: return Result.failure(Exception("Empty response"))
                        val md = MessageDigest.getInstance("MD5")
                        val buffer = ByteArray(256 * 1024) // 256KB
                        val progressInterval = 512L * 1024 // emit progress every 512KB
                        var copiedInFile = 0L
                        DigestOutputStream(
                            BufferedOutputStream(FileOutputStream(outFile)),
                            md
                        ).use { out ->
                            body.byteStream().use { input ->
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    if (!downloadInfo.isActive()) {
                                        outFile.delete()
                                        return Result.failure(Exception("Download cancelled"))
                                    }
                                    out.write(buffer, 0, n)
                                    copiedInFile += n
                                    downloadInfo.updateBytesDownloaded(n.toLong())
                                    if (copiedInFile >= progressInterval || downloadInfo.getBytesDownloaded() >= totalSize) {
                                        copiedInFile = 0L
                                        downloadInfo.setProgress(
                                            (downloadInfo.getBytesDownloaded().toFloat() / totalSize).coerceIn(0f, 1f)
                                        )
                                        downloadInfo.emitProgressChange()
                                    }
                                }
                            }
                        }
                        val bytesWritten = outFile.length()
                        if (bytesWritten != file.size) return Result.failure(Exception("Size mismatch ${file.path}"))
                        val md5 = md.digest().joinToString("") { "%02x".format(it) }
                        if (file.hash.isNotEmpty() && md5 != file.hash) return Result.failure(Exception("MD5 mismatch ${file.path}"))
                        // bytes already reported during copy; ensure final progress is exact
                        downloadInfo.setProgress(
                            (downloadInfo.getBytesDownloaded().toFloat() / totalSize).coerceIn(0f, 1f)
                        )
                        downloadInfo.emitProgressChange()
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            for (f in gameFiles) {
                if (!downloadInfo.isActive()) return@withContext Result.failure(Exception("Download cancelled"))
                downloadInfo.updateStatusMessage("Downloading ${f.file.path}")
                val res = downloadOneFile(f, installPath)
                if (res.isFailure) return@withContext res
                doneFiles++
            }
            if (supportDir != null) {
                supportDir.mkdirs()
                for (f in supportFiles) {
                    if (!downloadInfo.isActive()) return@withContext Result.failure(Exception("Download cancelled"))
                    downloadInfo.updateStatusMessage("Downloading support ${f.file.path}")
                    val res = downloadOneFile(f, supportDir)
                    if (res.isFailure) return@withContext res
                    doneFiles++
                }
            }

            saveManifestToGameDir(installPath, gameManifest, selectedBuild.buildId, selectedBuild.versionName, effectiveLang)

            finalizeInstallSuccess(gameId, installPath, downloadInfo)
            Timber.tag("GOG").i("Gen 1 download completed for game $gameId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Gen 1 download failed: ${e.message}")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1.0f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()
            app.gamenative.PluviaApp.events.emitJava(
                app.gamenative.events.AndroidEvent.DownloadStatusChanged(gameId.toIntOrNull() ?: 0, false),
            )
            Result.failure(e)
        }
    }

    internal fun buildGen1MainBinUrl(baseUrl: String?): String {
        val safeBase = baseUrl?.trim().orEmpty()
        require(safeBase.isNotEmpty()) { "Missing Gen 1 secure link URL" }

        val queryIndex = safeBase.indexOf('?')
        val pathBase = if (queryIndex >= 0) safeBase.substring(0, queryIndex) else safeBase
        val querySuffix = if (queryIndex >= 0) safeBase.substring(queryIndex) else ""
        val normalizedPathBase = pathBase.trimEnd('/')

        return "$normalizedPathBase/main.bin$querySuffix"
    }

    /**
     * Native (Rust) GOG gen2 pipeline via GameDownloadService, one run-set per product: the
     * engine re-parses the raw depot manifests, fetches + inflates + MD5-verifies chunks and
     * assembles files with the same `.part` + rename protocol as the Kotlin loop, skipping
     * files that already pass size+MD5 on disk ("verified" events, no byte credit).
     *
     * Secure-link expiry is handled exactly like the Kotlin path: refresh the link for the
     * product and re-run with the already-completed files in `skipPaths`.
     *
     * Returns null when the native engine is unavailable (caller falls back to
     * [downloadAndAssembleChunks]); otherwise the terminal result.
     */
    private suspend fun downloadGameGen2Native(
        rawDepotJsonByProduct: Map<String, List<String>>,
        productUrlMap: MutableMap<String, List<String>>,
        pendingPathsToProduct: Map<String, String>,
        pendingPathsInOrder: List<String>,
        supportDepotPaths: Set<String>,
        installDir: File,
        downloadInfo: DownloadInfo,
        baseProductId: String,
        generation: Int,
    ): Result<Unit>? = withContext(Dispatchers.IO) {
        if (!NativeGogDownload.isAvailable()) return@withContext null

        // Group pending files per product; base product first (LPT order), DLCs after.
        val pathsByProduct = pendingPathsInOrder
            .groupBy { pendingPathsToProduct[it] }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        if (pathsByProduct.isEmpty()) return@withContext null
        val orderedProducts = pathsByProduct.keys.sortedBy { if (it == baseProductId) 0 else 1 }
        if (orderedProducts.any { rawDepotJsonByProduct[it].isNullOrEmpty() }) return@withContext null
        if (orderedProducts.any { productUrlMap[it].isNullOrEmpty() }) return@withContext null

        val speedConfig = DownloadSpeedConfig()
        val totalFiles = pendingPathsInOrder.size.coerceAtLeast(1)
        val donePaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        for (product in orderedProducts) {
            val manifests = rawDepotJsonByProduct.getValue(product).toTypedArray()
            var cdnBase = productUrlMap.getValue(product).first()
            var refreshesLeft = 3

            while (true) {
                if (!downloadInfo.isActive()) {
                    return@withContext Result.failure(Exception("Download cancelled"))
                }

                val latch = CountDownLatch(1)
                val completed = java.util.concurrent.atomic.AtomicReference<NativeGogRunCompletion?>()
                // Per-run high-water mark for the compressed-byte stream reported via onBytes.
                var fetchedBytes = 0L
                val listener = object : NativeGogDownloadListener {
                    override fun onProgress(
                        bytesDone: Long,
                        bytesTotal: Long,
                        filesDone: Int,
                        filesTotal: Int,
                        file: String,
                        fileBytes: Long,
                        verified: Boolean,
                    ) {
                        donePaths.add(file)
                        // Note: verified (resume-skipped) files credit NOTHING here. Their
                        // bytes were already counted in the persisted snapshot this run
                        // resumed from — re-crediting double-counts past the expected total
                        // (same fix as the Steam listener). Completion snaps the bar to
                        // 100% via the success path.
                        if (verified) {
                            Timber.tag("GOG").v("Verified existing file (no credit): $file")
                        }
                        // Aggregate across products: donePaths tracks every completed file.
                        val globalDone = donePaths.size
                        downloadInfo.setProgress((globalDone.toFloat() / totalFiles).coerceIn(0f, 1f))
                        downloadInfo.updateStatusMessage("Downloading ($globalDone/$totalFiles files)…")
                        downloadInfo.emitProgressChange()
                        downloadInfo.persistProgressSnapshot()
                    }

                    override fun onBytes(bytesFetched: Long) {
                        val delta = bytesFetched - fetchedBytes
                        if (delta > 0L) {
                            fetchedBytes = bytesFetched
                            downloadInfo.updateBytesDownloaded(delta)
                            // updateBytesDownloaded doesn't emit on its own (Steam relies on
                            // setProgress for that); without this the app screen's progress
                            // listener never fires and the UI only refreshes on re-entry.
                            // Native already throttles these callbacks to ~5/sec.
                            downloadInfo.emitProgressChange()
                        }
                    }

                    override fun onLog(line: String) {
                        Timber.tag("GOG").d(line)
                    }

                    override fun onComplete(
                        success: Boolean,
                        cancelled: Boolean,
                        linkExpiry: Boolean,
                        error: String,
                        bytesWritten: Long,
                        filesDone: Int,
                    ) {
                        completed.set(NativeGogRunCompletion(success, cancelled, linkExpiry, error))
                        latch.countDown()
                    }
                }

                val handle = GameDownloadService.downloadGogChunks(
                    kind = NativeGogDownload.KIND_GEN2_CHUNKS,
                    depotManifests = manifests,
                    cdnBase = cdnBase,
                    installDir = installDir.absolutePath,
                    skipPaths = donePaths.toTypedArray(),
                    // Adaptive-window ceiling (ramps up only while the link delivers).
                    maxWorkers = speedConfig.maxDownloads,
                    processWorkers = speedConfig.maxDecompress,
                    sortLargestFirst = product == baseProductId,
                    label = "gog/$product",
                    listener = listener,
                )
                if (handle == 0L) {
                    Timber.tag("GOG").w("native GOG engine failed to start for product $product")
                    return@withContext null
                }

                try {
                    while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                        if (!downloadInfo.isActive()) {
                            GameDownloadService.cancelGogDownload(handle)
                        }
                    }
                } finally {
                    GameDownloadService.releaseGogDownload(handle)
                }

                val c = completed.get()
                if (c == null) return@withContext null
                if (c.cancelled || !downloadInfo.isActive()) {
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                if (c.success) break

                if (c.linkExpiry && refreshesLeft > 0) {
                    refreshesLeft--
                    Timber.tag("GOG").w("secure link for product $product expired (${c.error}), refreshing")
                    val linksResult = apiClient.getSecureLink(productId = product, path = "/", generation = generation)
                    val urls = linksResult.getOrNull()?.urls
                    if (urls.isNullOrEmpty()) {
                        return@withContext Result.failure(
                            linksResult.exceptionOrNull() ?: Exception("Failed to refresh secure link"),
                        )
                    }
                    cdnBase = CdnRankingUtils.rankBaseUrlsByHeadProbe(urls, Net.http, "GOG Galaxy").first()
                    productUrlMap[product] = listOf(cdnBase)
                    continue
                }
                return@withContext Result.failure(
                    Exception(c.error.ifEmpty { "GOG download failed for product $product" }),
                )
            }
        }

        // The engine writes depot paths verbatim; GameNative strips the leading "app/" from
        // support-file paths (getSupportInstallPath), so move them up after the run.
        // Merge recursively: when the destination directory already exists, a skipped
        // renameTo followed by deleteRecursively would silently lose pending support files.
        // Only first-level entries that actually belong to support files are moved — a game
        // that legitimately ships its own top-level "app/" content keeps it untouched.
        val appDir = File(installDir, "app")
        if (appDir.isDirectory) {
            val supportRoots = supportDepotPaths
                .filter { it.startsWith("app/") }
                .map { it.removePrefix("app/").substringBefore('/') }
                .toSet()
            // Returns false when any mkdir/delete/rename fails — the source tree is
            // then kept so support files are never lost silently.
            fun moveIntoPlace(src: File, dstDir: File): Boolean {
                if (src.isDirectory) {
                    val targetDir = File(dstDir, src.name)
                    if (!targetDir.isDirectory && !targetDir.mkdirs()) return false
                    return src.listFiles()?.all { moveIntoPlace(it, targetDir) } ?: true
                }
                val target = File(dstDir, src.name)
                if (target.exists() && !target.delete()) return false
                return src.renameTo(target)
            }
            appDir.listFiles()?.forEach { child ->
                if (child.name in supportRoots) {
                    if (moveIntoPlace(child, installDir)) {
                        NativeTreeDelete.deleteTreeFast(child)
                    } else {
                        Timber.tag("GOG").e("Failed to relocate support path ${child.absolutePath}")
                        return@withContext Result.failure(
                            Exception("Failed to relocate support files from ${child.absolutePath}"),
                        )
                    }
                }
            }
            if (appDir.listFiles()?.isEmpty() == true) {
                appDir.delete()
            }
        }

        Result.success(Unit)
    }

    private class NativeGogRunCompletion(
        val success: Boolean,
        val cancelled: Boolean,
        val linkExpiry: Boolean,
        val error: String,
    )

    // assembles files as chunks arrive, deletes chunks once their last consumer is assembled
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadAndAssembleChunks(
        chunkUrlCandidates: Map<String, List<String>>,
        chunkCacheDir: File,
        installDir: File,
        files: List<DepotFile>,
        downloadInfo: DownloadInfo,
        chunkHashes: List<String>,
        secureLinkContext: SecureLinkContext?,
        chunkToProductMap: Map<String, String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val scope = CoroutineScope(Dispatchers.IO)
            val speedConfig = DownloadSpeedConfig()
            val parallelDownloads = speedConfig.maxDownloads
            val parallelAssemble = speedConfig.maxDecompress
            val downloadHttpClient = Net.httpForParallelDownloads(parallelDownloads)

            val currentChunkUrlCandidates = ConcurrentHashMap(chunkUrlCandidates)
            val totalChunks = chunkHashes.size
            val totalFiles = files.size
            val chunkUsageCounts = ConcurrentHashMap<String, AtomicInteger>()
            val downloadedChunkIds = newKeySet<String>()
            val chunkAttempts = ConcurrentHashMap<String, AtomicInteger>()
            val pendingChunks = AtomicInteger(chunkHashes.size)

            // Calculate total expected installed size once (sum of all file sizes)
            val totalExpectedSize = files.sumOf { file -> file.chunks.sumOf { it.size } }

            val onExternalStorage = ContainerStorageManager.isOnExternalStorage(context, GameSource.GOG, installDir.absolutePath)
            if (onExternalStorage) {
                val remainingBytes = files.sumOf { file ->
                    (file.chunks.sumOf { it.size } - File(installDir, file.path).length()).coerceAtLeast(0L)
                }
                StorageUtils.downloadSpaceShortfall(installDir, remainingBytes, chunkCacheDir)?.let {
                    return@withContext Result.failure(IOException(it))
                }
            }

            chunkHashes.forEach { chunkMd5 ->
                chunkUsageCounts[chunkMd5] = AtomicInteger(
                    files.sumOf { file -> file.chunks.count { chunk -> chunk.compressedMd5 == chunkMd5 } }
                )
            }

            val networkChunkFlow = MutableSharedFlow<String>(extraBufferCapacity = Int.MAX_VALUE)
            val assembleFlow = MutableSharedFlow<Pair<String, Result<File>>>(extraBufferCapacity = Int.MAX_VALUE)

            var assemblyFailure: Throwable? = null

            // Remaining chunk positions per file; the full-file MD5 runs once, when this hits zero.
            val filePendingPositions = ConcurrentHashMap<String, AtomicInteger>()
            files.forEach { file -> filePendingPositions[file.path] = AtomicInteger(file.chunks.size) }

            // assemble every file whose chunks have all arrived (or that has zero chunks)
            suspend fun assembleReady(chunkMd5: String): Result<Unit> {
                if (!downloadInfo.isActive()) {
                    return Result.failure(Exception("Download cancelled"))
                }

                // 1. Find all files that contain this chunk
                val matchedFiles = files.filter { file ->
                    file.chunks.any { chunk -> chunk.compressedMd5 == chunkMd5 }
                }

                // 2. For each file found, write this chunk into its position
                var expectedCount = 0
                var assemblySuccessCount = 0
                val assembledPerFile = mutableMapOf<DepotFile, Int>()

                matchedFiles.forEach { file ->
                    file.chunks.withIndex()
                        .filter { (_, chunk) -> chunk.compressedMd5 == chunkMd5 }
                        .forEach { (chunkIndex, chunk) ->
                            expectedCount++
                            val result = assembleFile(file, chunk, chunkIndex, chunkCacheDir, installDir)
                            if (result.isSuccess) {
                                assemblySuccessCount++
                                assembledPerFile[file] = (assembledPerFile[file] ?: 0) + 1
                            } else {
                                Timber.tag("GOG").d(result.exceptionOrNull()?.message ?: "Failed to assemble ${file.path}")
                            }
                        }
                }

                // 3. Only finalize once every position using this chunk has been written; a partial
                // result is reported as failure so the caller can re-fetch and retry the chunk.
                // Position counters are only decremented below, on the fully-successful attempt,
                // so a retried chunk cannot decrement the same position twice.
                if (assemblySuccessCount < expectedCount) {
                    return Result.failure(
                        Exception("Assembled $assemblySuccessCount/$expectedCount position(s) for chunk $chunkMd5"),
                    )
                }

                // Fully assembled: credit the chunk's INFLATED size once per position (shared
                // chunks are written — and counted — once per consumer, matching the
                // uncompressed progress total). Only the fully-successful attempt reaches
                // here, so a re-fetched chunk can never double-credit.
                val inflatedSize = matchedFiles
                    .firstNotNullOf { f -> f.chunks.firstOrNull { it.compressedMd5 == chunkMd5 } }
                    .size
                downloadInfo.updateBytesDownloaded(inflatedSize * expectedCount)
                downloadInfo.emitProgressChange()

                assembledPerFile.forEach { (file, count) ->
                    val remaining = filePendingPositions[file.path]?.addAndGet(-count)
                    if (remaining == 0) {
                        verifyAssembledFile(file, installDir)
                    }
                }

                // 4. Free the cached chunk once it has been placed into all of its positions
                val usageCount = chunkUsageCounts[chunkMd5]?.addAndGet(-assemblySuccessCount)
                if (usageCount != null && usageCount <= 0) {
                    val cacheFile = resolveChunkFile(chunkCacheDir, "${chunkMd5}.chunk")
                    cacheFile.delete()
                }

                return Result.success(Unit)
            }

            val networkChunkJob: Job = scope.launch {
                networkChunkFlow
                    .flatMapMerge<String, Unit>(concurrency = parallelDownloads) { chunkMd5 ->
                        flow<Unit> {
                            if (!downloadInfo.isActive()) {
                                return@flow
                            }

                            val result = run {
                                val urls = currentChunkUrlCandidates[chunkMd5] ?: return@run Result.failure<File>(
                                    Exception("No URL candidates found for chunk $chunkMd5"),
                                )
                                downloadChunkWithRetry(chunkMd5, urls, chunkCacheDir, downloadInfo, downloadHttpClient)
                            }

                            // Always emit result to assembleFlow for processing (success or failure)
                            assembleFlow.tryEmit(chunkMd5 to result)
                            emit(Unit)
                        }
                    }
                    .flowOn(Dispatchers.IO)
                    .collect()
            }

            val assembleJob: Job = scope.launch {
                assembleFlow
                    .flatMapMerge<Pair<String, Result<File>>, Unit>(concurrency = parallelAssemble) { (chunkMd5, result) ->
                        flow<Unit> {
                            if (!downloadInfo.isActive()) {
                                return@flow
                            }

                            if (result.isSuccess && assemblyFailure == null) {
                                // Successful download - add to completed set and try assembly
                                downloadedChunkIds.add(chunkMd5)

                                val assembleResult = assembleReady(chunkMd5)
                                if (assembleResult.isFailure && downloadInfo.isActive()) {
                                    // A position failed to assemble (e.g. corrupt chunk bytes). The per-chunk
                                    // md5 is reliable, so re-fetch a fresh copy and retry a few times. If it
                                    // still fails, skip this chunk and keep installing the rest instead of
                                    // aborting the whole download.
                                    val attempts = chunkAttempts.computeIfAbsent(chunkMd5) { AtomicInteger(0) }.incrementAndGet()
                                    if (attempts <= MAX_CHUNK_ASSEMBLY_ATTEMPTS) {
                                        Timber.tag("GOG").w(
                                            "Chunk $chunkMd5 assembly failed (attempt $attempts/$MAX_CHUNK_ASSEMBLY_ATTEMPTS), " +
                                                "re-fetching: ${assembleResult.exceptionOrNull()?.message}",
                                        )
                                        resolveChunkFile(chunkCacheDir, "${chunkMd5}.chunk").delete()
                                        downloadedChunkIds.remove(chunkMd5)
                                        networkChunkFlow.tryEmit(chunkMd5)
                                        return@flow
                                    }
                                    Timber.tag("GOG").e("Chunk $chunkMd5 could not be assembled after $MAX_CHUNK_ASSEMBLY_ATTEMPTS attempts, skipping")
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
                                Timber.tag("GOG").d("Chunk $chunkMd5 download failed: ${exception?.message}")

                                if (exception is HttpStatusException) {
                                    Timber.tag("GOG").d("Chunk $chunkMd5 download failed: HttpError ${exception.statusCode}, ${exception.message}")
                                    if (exception.statusCode in listOf(401, 403, 404, 500)) {
                                        if (secureLinkContext != null) {
                                            Timber.tag("GOG").w("Chunk $chunkMd5 urls expired, refreshing")

                                            val refreshResult = refreshSecureLinks(secureLinkContext, listOf(chunkMd5))
                                            if (refreshResult.isSuccess) {
                                                currentChunkUrlCandidates[chunkMd5] = refreshResult.getOrThrow().getValue(chunkMd5)
                                                networkChunkFlow.tryEmit(chunkMd5)
                                                return@flow
                                            }
                                        }
                                    }
                                }

                                // For other failures, could add additional retry logic here
                                Timber.tag("GOG").e("Chunk $chunkMd5 failed permanently: ${exception?.message}")
                            }

                            emit(Unit)
                        }
                    }
                    .flowOn(Dispatchers.IO)
                    .collect()
            }

            Timber.tag("GOG").d("Streaming download+assembly: $totalChunks chunks, $totalFiles files")

            downloadInfo.setProgress(0.0f)
            downloadInfo.setActive(true)

            // Start downloads by launching a separate coroutine to emit chunks
            val preallocJob: Job = scope.launch {
                if (!downloadInfo.isActive()) {
                    Timber.tag("GOG").w("Download cancelled by user")
                    return@launch
                }

                val chunksAdded = mutableListOf<String>()

                // Sort files by total chunk usage (lowest first) - files with less-shared chunks complete faster and free cache sooner
                val sortedFiles = files.sortedBy { file ->
                    file.chunks.sumOf { chunk ->
                        chunkUsageCounts[chunk.compressedMd5]?.get() ?: 0
                    }
                }
                Timber.tag("GOG").d("Processing ${sortedFiles.size} files sorted by chunk usage (least shared first)")

                sortedFiles.forEach { file ->
                    if (!downloadInfo.isActive()) {
                        Timber.tag("GOG").w("Download cancelled during file iteration")
                        return@launch
                    }
                    Timber.tag("GOG").v("Pre-allocating ${file.path}")

                    // Allocating file before download
                    val outputFile = File(installDir, file.path)
                    outputFile.parentFile?.mkdirs()

                    val totalSize = file.chunks.sumOf { it.size }

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

                        file.chunks.forEach { chunk ->
                            if (!chunksAdded.contains(chunk.compressedMd5)) {
                                chunksAdded.add(chunk.compressedMd5)
                                networkChunkFlow.emit(chunk.compressedMd5)
                                Timber.tag("GOG").v("Emitted chunk ${chunk.compressedMd5} to download flow")
                            }
                        }
                    } catch (e: IOException) {
                        throw IOException("Failed to allocate file ${outputFile.path}: ${e.message}")
                    }
                }
            }

            preallocJob.join()

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

                Timber.tag("GOG").d(
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
                    val missingChunks = chunkHashes.filterNot { downloadedChunkIds.contains(it) }
                    if (missingChunks.isNotEmpty()) {
                        Timber.tag("GOG").w(
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

            Timber.tag("GOG").i("Streaming complete: $totalChunks chunks, ${files.size} files assembled")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download and assemble")
            Result.failure(e)
        }
    }


    /**
     * Downloads the dependencies for a given game by using the dependency array from the game's depo-list
     * It will download the dependencies using the dependency URL
     *
     */
    private suspend fun downloadDependencies(
        gameId: String,
        dependencies: List<String>,
        gameDir: File,
        supportDir: File,
        downloadInfo: DownloadInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (dependencies.isEmpty()) {
                Timber.tag("GOG").d("No dependencies to download")
                return@withContext Result.success(Unit)
            }

            Timber.tag("GOG").i("Downloading ${dependencies.size} dependencies: ${dependencies.joinToString()}")

            // Get dependency repository
            val dependencyRepositoryResult = apiClient.fetchDependencyRepository(DEPENDENCY_URL)
            if (dependencyRepositoryResult.isFailure) {
                return@withContext Result.failure(
                    dependencyRepositoryResult.exceptionOrNull() ?: Exception("Failed to fetch Dependency repository"),
                )
            }

            val repositoryManifestUrl = dependencyRepositoryResult.getOrThrow().repositoryManifest
            if (repositoryManifestUrl.isBlank()) {
                return@withContext Result.failure(Exception("Empty repository manifest URL"))
            }

            // Get the decompressed manifest
            val dependencyManifestResult = apiClient.fetchDependencyManifest(repositoryManifestUrl)
            if (dependencyManifestResult.isFailure) {
                return@withContext Result.failure(
                    dependencyManifestResult.exceptionOrNull() ?: Exception("Failed to fetch Dependency manifest"),
                )
            }

            val dependencyManifest = dependencyManifestResult.getOrThrow()

            // Filter depots by the dependencyId so we only install what we need (e.g., MSVC2013)
            val filteredDepots = dependencyManifest.depots.filter { depot ->
                dependencies.contains(depot.dependencyId)
            }

            if (filteredDepots.isEmpty()) {
                Timber.tag("GOG").w("No matching dependency depots found for: ${dependencies.joinToString()}")
                return@withContext Result.success(Unit)
            }

            Timber.tag("GOG").d("Found ${filteredDepots.size} dependency depot(s) to download")

            // Get open link URLs for dependencies
            val openLinkResult = apiClient.getDependencyOpenLink()
            if (openLinkResult.isFailure) {
                return@withContext Result.failure(
                    openLinkResult.exceptionOrNull() ?: Exception("Failed to get dependency open link"),
                )
            }

            val dependencyBaseUrls = openLinkResult.getOrThrow()
            if (dependencyBaseUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No dependency URLs returned"))
            }

            Timber.tag("GOG").d("Got ${dependencyBaseUrls.size} dependency base URL(s)")

            // Download each dependency
            for ((index, depot) in filteredDepots.withIndex()) {
                downloadInfo.updateStatusMessage("Downloading dependency ${index + 1}/${filteredDepots.size}: ${depot.readableName}")

                Timber.tag("GOG").i("Downloading dependency: ${depot.readableName} (${depot.dependencyId})")

                // Determine install directory based on executable path
                // If path starts with __redist, install to supportDir, otherwise install to gameDir
                val installBaseDir = if (depot.executable?.path?.startsWith("__redist") == true) {
                    Timber.tag("GOG").d("Dependency ${depot.dependencyId} has __redist path, installing to supportDir")
                    supportDir
                } else {
                    Timber.tag("GOG").d("Dependency ${depot.dependencyId} has no __redist path, installing to gameDir")
                    gameDir
                }

                // Fetch depot manifest to get file list using open link URLs
                val depotManifestResult = apiClient.fetchDependencyDepotManifest(depot.manifest, dependencyBaseUrls)
                if (depotManifestResult.isFailure) {
                    Timber.tag("GOG").w("Failed to fetch depot manifest for ${depot.readableName}: ${depotManifestResult.exceptionOrNull()?.message}")
                    continue
                }

                val depotManifest = depotManifestResult.getOrThrow()
                val depotFiles = depotManifest.files

                if (depotFiles.isEmpty()) {
                    Timber.tag("GOG").w("No files in dependency depot: ${depot.readableName}")
                    continue
                }

                // Strip __redist/ prefix from file paths if they're being installed to supportDir
                val filesToAssemble = if (installBaseDir == supportDir) {
                    depotFiles.map { file ->
                        if (file.path.startsWith("__redist/")) {
                            file.copy(path = file.path.removePrefix("__redist/"))
                        } else {
                            file
                        }
                    }
                } else {
                    depotFiles
                }

                // Extract chunk hashes and build URLs
                val depotChunkHashes = parser.extractChunkHashes(filesToAssemble)
                val rankedDependencyUrls = CdnRankingUtils.rankBaseUrlsByHeadProbe(
                    dependencyBaseUrls,
                    Net.http,
                    "GOG Galaxy",
                )
                val chunkUrlCandidates = buildChunkUrlCandidates(depotChunkHashes, rankedDependencyUrls)

                // Dependency chunk cache also lives on internal storage (same exFAT
                // dirsync cost as the game chunk cache when installing to SD)
                val depotCacheDir = File(context.cacheDir, "gog_chunks/dep_${depot.dependencyId}")
                depotCacheDir.mkdirs()

                val depotInstallDir = installBaseDir
                depotInstallDir.mkdirs()

                // Streaming download + assemble (no secure link refresh for deps)
                val depotResult = downloadAndAssembleChunks(
                    chunkUrlCandidates = chunkUrlCandidates,
                    chunkCacheDir = depotCacheDir,
                    installDir = depotInstallDir,
                    files = filesToAssemble,
                    downloadInfo = downloadInfo,
                    chunkHashes = depotChunkHashes,
                    secureLinkContext = null,
                    chunkToProductMap = emptyMap(),
                )
                if (depotResult.isFailure) {
                    // propagate cancellations so finalizeInstallSuccess doesn't run
                    if (!downloadInfo.isActive()) {
                        return@withContext depotResult
                    }
                    Timber.tag("GOG").w("Failed to download/assemble ${depot.readableName}: ${depotResult.exceptionOrNull()?.message}")
                    continue
                }

                NativeTreeDelete.deleteTreeFast(depotCacheDir)

                Timber.tag("GOG").i("Successfully downloaded dependency: ${depot.readableName} to ${depotInstallDir.absolutePath}")
            }

            Timber.tag("GOG").i("Completed downloading ${filteredDepots.size} dependencies")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download dependencies")
            Result.failure(e)
        }
    }

    suspend fun downloadDependenciesWithProgress(
        gameId: String,
        dependencies: List<String>,
        gameDir: File,
        supportDir: File,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (dependencies.isEmpty()) return@withContext Result.success(Unit)
        val downloadInfo = DownloadInfo(
            jobCount = 1,
            gameId = 0,
            downloadingAppIds = CopyOnWriteArrayList(),
        )
        onProgress?.let { downloadInfo.addProgressListener(it) }
        val result = downloadDependencies(
            gameId = gameId,
            dependencies = dependencies,
            gameDir = gameDir,
            supportDir = supportDir,
            downloadInfo = downloadInfo,
        )
        if (result.isSuccess) {
            onProgress?.invoke(1f)
        }
        result
    }

    /**
     * Build chunk URL map using base URLs
     */
    private fun buildChunkUrlCandidates(chunkHashes: List<String>, baseUrls: List<String>): Map<String, List<String>> {
        return parser.buildChunkUrlCandidates(chunkHashes, baseUrls)
    }

    /**
     * Refresh secure CDN links when they expire
     *
     * @param context Context containing info needed to fetch new links
     * @param chunkHashes List of chunk hashes needed
     * @return New chunk URL candidates with fresh secure links
     */
    private suspend fun refreshSecureLinks(
        context: SecureLinkContext,
        chunkHashes: List<String>,
    ): Result<Map<String, List<String>>> = withContext(Dispatchers.IO) {
        try {
            val productUrlMap = mutableMapOf<String, List<String>>()

            // Get secure links for each product
            for (productId in context.productIds) {
                val linksResult = apiClient.getSecureLink(
                    productId = productId,
                    path = "/",
                    generation = context.generation,
                )
                if (linksResult.isSuccess) {
                    productUrlMap[productId] = CdnRankingUtils.rankBaseUrlsByHeadProbe(
                        linksResult.getOrThrow().urls,
                        Net.http,
                        "GOG Galaxy",
                    )
                } else {
                    return@withContext Result.failure(
                        linksResult.exceptionOrNull() ?: Exception("Failed to refresh secure links for product $productId"),
                    )
                }
            }

            Timber.tag("GOG").d("Refreshed secure links for ${productUrlMap.size} product(s)")

            // Rebuild chunk URL candidates with new secure links
            val newChunkUrlCandidates = parser.buildChunkUrlCandidatesWithProducts(chunkHashes, context.chunkToProductMap, productUrlMap)
            Result.success(newChunkUrlCandidates)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to refresh secure links")
            Result.failure(e)
        }
    }

    /**
     * Download a single chunk with retry logic
     *
     * @param chunkMd5 Compressed MD5 hash (chunk identifier)
     * @param urlCandidates Ordered secure CDN URL candidates (fastest first)
     * @param chunkCacheDir Cache directory
     * @param downloadInfo Progress tracker
     */
    private suspend fun downloadChunkWithRetry(
        chunkMd5: String,
        urlCandidates: List<String>,
        chunkCacheDir: File,
        downloadInfo: DownloadInfo,
        httpClient: OkHttpClient,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (urlCandidates.isEmpty()) {
            return@withContext Result.failure(Exception("No URL candidates for chunk $chunkMd5"))
        }

        var lastException: Exception? = null

        repeat(MAX_CHUNK_RETRIES) { attempt ->
            if (!downloadInfo.isActive()) {
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            val url = urlCandidates[attempt % urlCandidates.size]
            val result = downloadChunk(chunkMd5, url, chunkCacheDir, downloadInfo, httpClient)

            if (result.isSuccess) {
                if (attempt > 0) {
                    Timber.tag("GOG").i("Chunk $chunkMd5 downloaded successfully after ${attempt + 1} attempts")
                }
                return@withContext result
            }

            lastException = result.exceptionOrNull() as? Exception

            if (attempt < MAX_CHUNK_RETRIES - 1) {
                val delay = RETRY_DELAY_MS * (1 shl attempt) // Exponential backoff: 1s, 2s, 4s
                if (downloadInfo.isActive()) {
                    Timber.tag("GOG")
                        .w("Chunk $chunkMd5 download failed (attempt ${attempt + 1}/$MAX_CHUNK_RETRIES): ${lastException?.message}. Retrying in ${delay}ms...")
                    delay(delay)
                }
            }
        }

        Timber.tag("GOG").e(lastException, "Failed to download chunk $chunkMd5 after $MAX_CHUNK_RETRIES attempts")
        Result.failure(lastException ?: Exception("Failed to download chunk $chunkMd5"))
    }

    /**
     * Sharded chunk-cache layout: `<cache>/<first2>/<md5>.chunk`. A flat directory with tens
     * of thousands of chunk files costs a growing directory scan per lookup. Writes always go
     * to the sharded path; [resolveChunkFile] dual-reads (sharded first, legacy flat second)
     * so caches written by older builds resume without refetching. Same scheme as the Epic
     * cache (EpicDownloadManager.shardedChunkFile / store_dl/epic/plan.rs).
     */
    private fun shardedChunkFile(chunkCacheDir: File, name: String): File =
        File(File(chunkCacheDir, name.take(2)), name)

    /** Dual-read resolution: sharded first, legacy flat (pre-sharding builds) second. */
    private fun resolveChunkFile(chunkCacheDir: File, name: String): File {
        val sharded = shardedChunkFile(chunkCacheDir, name)
        return if (sharded.exists()) sharded else File(chunkCacheDir, name)
    }

    /**
     * Download a single chunk from GOG CDN
     *
     * @param chunkMd5 Compressed MD5 hash (chunk identifier)
     * @param url Secure CDN URL (time-limited)
     * @param chunkCacheDir Cache directory
     * @param downloadInfo Progress tracker
     */
    private suspend fun downloadChunk(
        chunkMd5: String,
        url: String,
        chunkCacheDir: File,
        downloadInfo: DownloadInfo,
        httpClient: OkHttpClient,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!downloadInfo.isActive()) {
                return@withContext Result.failure(Exception("Download cancelled"))
            }

            // Dual-read: an existing file (either layout) is the skip/corrupt candidate;
            // on a miss the write target is always the sharded path.
            val existing = resolveChunkFile(chunkCacheDir, "$chunkMd5.chunk")
            if (existing.exists()) {
                val existingMd5 = calculateMd5(existing.readBytes())
                if (existingMd5 == chunkMd5) {
                    Timber.tag("GOG").d("Chunk $chunkMd5 already exists and verified, skipping")
                    return@withContext Result.success(existing)
                } else {
                    Timber.tag("GOG").w("Chunk $chunkMd5 exists but failed verification, re-downloading")
                    existing.delete()
                }
            }
            val chunkFile = shardedChunkFile(chunkCacheDir, "$chunkMd5.chunk").apply { parentFile?.mkdirs() }
            val tempChunkFile = File(chunkFile.parentFile, "$chunkMd5.chunk.part")

            // Download compressed chunk (redact query params to avoid token leakage in logs)
            val safeUrl = url.substringBefore('?')
            Timber.tag("GOG").v("Downloading chunk $chunkMd5 from: $safeUrl")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GOG Galaxy")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GOG").e("HTTP ${response.code} for chunk $chunkMd5 from URL: $safeUrl")
                    return@withContext Result.failure(
                        HttpStatusException(response.code, "HTTP ${response.code} downloading chunk $chunkMd5")
                    )
                }

                val responseBody = response.body
                    ?: return@withContext Result.failure(Exception("Empty response for chunk $chunkMd5"))

                val md = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(64 * 1024) // 64KB - reduced from 256KB to minimize memory pressure during parallel downloads
                var lastProgressEmitAt = System.currentTimeMillis()

                if (tempChunkFile.exists()) tempChunkFile.delete()

                try {
                    BufferedOutputStream(FileOutputStream(tempChunkFile)).use { output ->
                        responseBody.byteStream().use { input ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (!downloadInfo.isActive()) {
                                    tempChunkFile.delete()
                                    return@withContext Result.failure(Exception("Download cancelled"))
                                }
                                md.update(buffer, 0, bytesRead)
                                output.write(buffer, 0, bytesRead)
                                // No byte credit here: progress is UNCOMPRESSED — each chunk
                                // credits its inflated size once fully assembled (assembleReady).
                                val now = System.currentTimeMillis()
                                if (now - lastProgressEmitAt >= STREAM_PROGRESS_TIME_INTERVAL_MS) {
                                    downloadInfo.emitProgressChange()
                                    lastProgressEmitAt = now
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    tempChunkFile.delete()
                    return@withContext Result.failure(
                        Exception("Failed while streaming chunk $chunkMd5: ${e.message}")
                    )
                }

                // Verify compressed MD5
                val actualMd5 = md.digest().joinToString("") { "%02x".format(it) }
                if (actualMd5 != chunkMd5) {
                    tempChunkFile.delete()
                    return@withContext Result.failure(
                        Exception("Compressed MD5 mismatch for chunk: expected $chunkMd5, got $actualMd5"),
                    )
                }

                // Ensure UI receives final progress update for this chunk.
                downloadInfo.emitProgressChange()

                // Atomically replace destination with verified temp chunk
                if (chunkFile.exists()) chunkFile.delete()
                if (!tempChunkFile.renameTo(chunkFile)) {
                    tempChunkFile.copyTo(chunkFile, overwrite = true)
                    tempChunkFile.delete()
                }

                Result.success(chunkFile)
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to download chunk $chunkMd5")
            Result.failure(e)
        }
    }

    /**
     * Assemble a single file from its chunks
     *
     * @param file File metadata with chunks
     * @param chunkCacheDir Directory containing downloaded chunks
     * @param installDir Target installation directory
     */
    private suspend fun assembleFile(
        file: DepotFile,
        chunk: FileChunk,
        chunkIndex: Int,
        chunkCacheDir: File,
        installDir: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(installDir, file.path)
            outputFile.parentFile?.mkdirs()

            // Get compressed chunk file (dual-read: sharded first, legacy flat second)
            val chunkFile = resolveChunkFile(chunkCacheDir, "${chunk.compressedMd5}.chunk")

            if (!chunkFile.exists()) {
                return@withContext Result.failure(
                    Exception("Chunk file missing: ${chunk.compressedMd5}"),
                )
            }

            val writeOffset = file.chunks.take(chunkIndex).sumOf { it.size }

            // Decompress directly to file while calculating MD5
            val decompressResult = decompressChunkToFile(chunkFile, chunk, outputFile, writeOffset)
            if (decompressResult.isFailure) {
                return@withContext Result.failure(
                    decompressResult.exceptionOrNull()
                        ?: Exception("Failed to decompress chunk ${chunk.compressedMd5}"),
                )
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to assemble file ${file.path}")
            Result.failure(e)
        }
    }

    /**
     * Full-file verification, run exactly once per file when its last chunk position lands.
     * Per-chunk MD5s are already verified in-stream during download.
     */
    private fun verifyAssembledFile(file: DepotFile, installDir: File) {
        val outputFile = File(installDir, file.path)
        if (file.md5 != null) {
            val fileMd5 = calculateMd5File(outputFile)
            if (!fileMd5.equals(file.md5, ignoreCase = true)) {
                // Don't fail - some games have incorrect MD5 in manifest
                Timber.tag("GOG").w("File MD5 mismatch: ${file.path}, expected ${file.md5}, got $fileMd5")
                return
            }
        }
        Timber.tag("GOG").v("Assembled: ${file.path} (${outputFile.length()} bytes)")
    }

    /**
     * Decompress a GOG chunk directly to file using zlib, streaming to avoid large memory allocations.
     * Calculates MD5 while decompressing and writes directly to the target file at the specified offset.
     *
     * @param chunkFile Compressed chunk file
     * @param chunk Chunk metadata
     * @param outputFile Target file to write decompressed data
     * @param writeOffset Offset in the output file to write at
     * @return Result indicating success or failure
     */
    internal fun decompressChunkToFile(
        chunkFile: File,
        chunk: FileChunk,
        outputFile: File,
        writeOffset: Long,
    ): Result<Unit> {
        return try {
            val md5Digest = MessageDigest.getInstance("MD5")
            var totalBytesWritten = 0L

            FileChannel.open(
                outputFile.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
            ).use { channel ->
                channel.position(writeOffset)

                // If no compressed size specified, data is already uncompressed
                if (chunk.compressedSize == null) {
                    chunkFile.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        val byteBuffer = ByteBuffer.wrap(buffer)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            md5Digest.update(buffer, 0, bytesRead)
                            byteBuffer.clear()
                            byteBuffer.limit(bytesRead)
                            while (byteBuffer.hasRemaining()) {
                                channel.write(byteBuffer)
                            }
                            totalBytesWritten += bytesRead
                        }
                    }
                } else {
                    // Decompress using zlib and stream directly to file
                    val inflater = Inflater()
                    try {
                        chunkFile.inputStream().buffered().use { input ->
                            val inputBuffer = ByteArray(8192)
                            val outputBuffer = ByteArray(8192)
                            val byteBuffer = ByteBuffer.wrap(outputBuffer)
                            var inputBytesRead: Int

                            while (input.read(inputBuffer).also { inputBytesRead = it } != -1) {
                                inflater.setInput(inputBuffer, 0, inputBytesRead)

                                while (!inflater.needsInput() && !inflater.finished()) {
                                    val count = inflater.inflate(outputBuffer)
                                    if (count > 0) {
                                        md5Digest.update(outputBuffer, 0, count)
                                        byteBuffer.clear()
                                        byteBuffer.limit(count)
                                        while (byteBuffer.hasRemaining()) {
                                            channel.write(byteBuffer)
                                        }
                                        totalBytesWritten += count
                                    } else {
                                        if (inflater.needsDictionary()) {
                                            throw java.io.IOException(
                                                "Zlib data requires a preset dictionary which is not supported"
                                            )
                                        }
                                        break
                                    }
                                }
                            }

                            // Finish any remaining data
                            while (!inflater.finished()) {
                                val count = inflater.inflate(outputBuffer)
                                if (count > 0) {
                                    md5Digest.update(outputBuffer, 0, count)
                                    byteBuffer.clear()
                                    byteBuffer.limit(count)
                                    while (byteBuffer.hasRemaining()) {
                                        channel.write(byteBuffer)
                                    }
                                    totalBytesWritten += count
                                } else {
                                    if (inflater.needsInput()) {
                                        throw java.io.IOException(
                                            "Incomplete zlib data: decompression requires more input but none available"
                                        )
                                    }
                                    break
                                }
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                }
            }

            // Verify size matches expected
            if (totalBytesWritten != chunk.size) {
                return Result.failure(
                    Exception("Decompressed size mismatch: expected ${chunk.size}, got $totalBytesWritten"),
                )
            }

            // Verify decompressed MD5
            val actualMd5 = md5Digest.digest().joinToString("") { "%02x".format(it) }
            if (actualMd5 != chunk.md5) {
                return Result.failure(
                    Exception("Decompressed MD5 mismatch for chunk: expected ${chunk.md5}, got $actualMd5"),
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to decompress chunk ${chunk.compressedMd5}")
            Result.failure(e)
        }
    }

    /**
     * Calculate MD5 hash of byte array
     */
    private fun calculateMd5(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getSupportInstallPath(path: String): String {
        return if (path.startsWith("app/")) path.removePrefix("app/") else path
    }

    /**
     * Create depot-declared empty directories and symlinks. These items carry no chunks, so the
     * chunk download/assemble path skips them; gogdl creates them separately (prepare_location /
     * CREATE_SYMLINK) and so must we, or affected games appear partially installed.
     */
    private fun createDirectoriesAndLinks(
        installDir: File,
        directories: List<DepotDirectory>,
        links: List<DepotLink>,
    ) {
        directories.forEach { dir ->
            val relPath = dir.path.removePrefix("/")
            if (relPath.isBlank()) return@forEach
            try {
                File(installDir, relPath).mkdirs()
            } catch (e: Exception) {
                Timber.tag("GOG").w(e, "Failed to create directory ${dir.path}")
            }
        }
        links.forEach { link ->
            val relPath = link.path.replace("\\", "/").removePrefix("/")
            if (relPath.isBlank() || link.target.isBlank()) return@forEach
            try {
                val linkFile = File(installDir, relPath)
                linkFile.parentFile?.mkdirs()
                if (linkFile.exists() || Files.isSymbolicLink(linkFile.toPath())) {
                    linkFile.delete()
                }
                Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(link.target))
                Timber.tag("GOG").d("Created symlink ${link.path} -> ${link.target}")
            } catch (e: Exception) {
                Timber.tag("GOG").w(e, "Failed to create symlink ${link.path} -> ${link.target}")
            }
        }
    }

    /**
     * Check if file exists and has the expected size. When [expectedMd5] is non-null/non-blank,
     * also verifies content MD5 to reject corrupted files; short-circuits on size mismatch before hashing.
     * When [expectedMd5] is null/blank, returns false to avoid treating pre-allocated files as complete.
     */
    private fun fileExistsWithCorrectSize(
        outputFile: File,
        expectedSize: Long,
        expectedMd5: String? = null,
    ): Boolean {
        if (!outputFile.exists()) return false
        if (outputFile.length() != expectedSize) return false
        if (expectedMd5.isNullOrBlank()) return false
        return calculateMd5File(outputFile).equals(expectedMd5, ignoreCase = true)
    }
    /**
     * Calculate MD5 hash of file
     */
    private fun calculateMd5File(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Total size under [directory]. Symlinks are skipped so GOG scriptinterpreter
     * `rootdir` (symlink to install root) does not re-count the whole tree.
     */
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        try {
            if (!directory.exists() || !directory.isDirectory) {
                return 0L
            }

            val files = directory.listFiles() ?: return 0L
            for (file in files) {
                if (Files.isSymbolicLink(file.toPath())) {
                    continue
                }
                size += if (file.isDirectory) {
                    calculateDirectorySize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Error calculating directory size for ${directory.name}")
        }
        return size
    }
}
