package app.gamenative.service.gog

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.GOGCloudSavesLocation
import app.gamenative.data.GOGCloudSavesLocationTemplate
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.service.download.NativeTreeDelete
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.Marker
import app.gamenative.enums.PathType
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.FileUtils
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.core.FileUtils as WinlatorFileUtils
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Data class to hold size information from gogdl info command
 */
data class GameSizeInfo(
    val downloadSize: Long,
    val diskSize: Long,
)

/**
 * Unified manager for GOG game and library operations.
 *
 * Responsibilities:
 * - Database CRUD for GOG games
 * - Library syncing from GOG API
 * - Game downloads and installation
 * - Installation verification
 * - Executable discovery
 * - Wine launch commands
 * - File system operations
 *
 * Uses GOGPythonBridge for all GOGDL command execution.
 * Uses GOGAuthManager for authentication checks.
 */
@Singleton
class GOGManager @Inject constructor(
    private val gogGameDao: GOGGameDao,
    @ApplicationContext private val context: Context,
) {

    // Thread-safe cache for download sizes
    private val downloadSizeCache = ConcurrentHashMap<String, String>()
    private val REFRESH_BATCH_SIZE = 10

    // Cache for remote config API responses (clientId -> save locations)
    // This avoids fetching the same config multiple times
    private val remoteConfigCache = ConcurrentHashMap<String, List<GOGCloudSavesLocationTemplate>>()

    // Timestamp storage for sync state (gameId_locationName -> timestamp)
    // Persisted to disk to survive app restarts
    private val syncTimestamps = ConcurrentHashMap<String, String>()
    private val timestampFile = File(context.filesDir, "gog_sync_timestamps.json")

    // Track active sync operations to prevent concurrent syncs
    private val activeSyncs = ConcurrentHashMap.newKeySet<String>()

    init {
        // Load persisted cloudsave timestamps on initialization
        loadCloudSaveTimestampsFromDisk()
    }

    suspend fun getGameFromDbById(gameId: String): GOGGame? {
        return withContext(Dispatchers.IO) {
            try {
                gogGameDao.getById(gameId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get GOG game by ID: $gameId")
                null
            }
        }
    }

    suspend fun getNonInstalledGames(): List<GOGGame> {
        return withContext(Dispatchers.IO) { gogGameDao.getNonInstalledGames() }
    }

    suspend fun insertGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            // Preserve install state and the hidden flag when the row already exists, so a
            // single-game refresh cannot reset them.
            gogGameDao.upsertPreservingInstallStatus(listOf(game))
        }
    }

    suspend fun updateGame(game: GOGGame) {
        withContext(Dispatchers.IO) {
            gogGameDao.update(game)
        }
    }

    suspend fun deleteAllNonInstalledGames() {
        withContext(Dispatchers.IO) {
            gogGameDao.deleteAllNonInstalledGames()
        }
    }

    suspend fun getAllGameIds(): Set<String> {
        return withContext(Dispatchers.IO) {
            try {
                gogGameDao.getAllGameIdsIncludingExcluded().toSet()
            } catch (e: Exception) {
                Timber.e(e, "Failed to get all game IDs")
                emptySet()
            }
        }
    }

    suspend fun startBackgroundSync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot start background sync: no stored credentials")
                return@withContext Result.failure(Exception("No stored credentials found"))
            }

            Timber.tag("GOG").i("Starting GOG library background sync...")

            val result = refreshLibrary(context)

            if (result.isSuccess) {
                val count = result.getOrNull() ?: 0
                Timber.tag("GOG").i("Background sync completed: $count games synced")
                backfillVerticalCovers()
                return@withContext Result.success(Unit)
            } else {
                val error = result.exceptionOrNull()
                Timber.e(error, "Background sync failed: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Background sync failed"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync GOG library in background")
            Result.failure(e)
        }
    }

    /**
     * Fetches hidden-product IDs once per sync and stores them on the matching `gog_games` rows.
     *
     * Failures leave the existing hidden flags untouched and are logged; this never throws and
     * never fails the caller. Staleness is corrected on the next sync.
     *
     * @return the fetched hidden product IDs, or null when the fetch failed or the user is not
     * authenticated (callers can use it to stamp newly inserted rows).
     */
    suspend fun refreshHiddenIds(): Set<String>? {
        if (!GOGAuthManager.hasStoredCredentials(context)) return null
        val hiddenIdsResult = GOGApiClient.getHiddenGameIds(context)
        if (hiddenIdsResult.isFailure) {
            Timber.tag("GOG").w(
                hiddenIdsResult.exceptionOrNull(),
                "Failed to fetch hidden GOG game IDs; keeping existing hidden flags",
            )
            return null
        }
        val hiddenIds = hiddenIdsResult.getOrNull() ?: emptySet()
        return try {
            gogGameDao.applyHiddenFlags(hiddenIds)
            hiddenIds
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to persist hidden GOG game IDs; keeping existing hidden flags")
            null
        }
    }

    /** Clears the hidden flag on every GOG row (used when the logged-out account's metadata is removed). */
    suspend fun clearHiddenFlags() {
        gogGameDao.clearHiddenFlags()
    }

    /**
     * Refresh the entire library (called manually by user)
     * Fetches all games from GOG API and updates the database
     * ! Note: If someone wants to improve this logic, I'd recommend seeing
     * ! if coroutine parallel downloading would work without being rate-limited
     */
    suspend fun refreshLibrary(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!GOGAuthManager.hasStoredCredentials(context)) {
                Timber.w("Cannot refresh library: not authenticated with GOG")
                return@withContext Result.failure(Exception("Not authenticated with GOG"))
            }

            Timber.tag("GOG").i("Refreshing GOG library from GOG API...")

            // Fetch games from GOG via GOGDL Python backend

            var gameIdList = GOGApiClient.getGameIds(context)

            if (!gameIdList.isSuccess) {
                val error = gameIdList.exceptionOrNull()
                Timber.e(error, "Failed to fetch GOG game IDs: ${error?.message}")
                return@withContext Result.failure(error ?: Exception("Failed to fetch GOG game IDs"))
            }

            val gameIds = gameIdList.getOrNull() ?: emptyList()
            Timber.tag("GOG").i("Successfully fetched ${gameIds.size} game IDs from GOG")

            // Refresh hidden-game metadata even when the owned library itself is unchanged.
            // A failure keeps the existing flags and must not fail the library refresh.
            val hiddenIds = refreshHiddenIds()

            if (gameIds.isEmpty()) {
                Timber.w("No games found in GOG library")
                return@withContext Result.success(0)
            }

            val ignoredGameId = "1801418160" // Hidden ID for GOG Galaxy that we should ignore.

            // Get existing game IDs from database to avoid re-fetching
            val existingGameIds = gogGameDao.getAllGameIdsIncludingExcluded().toMutableSet()
            existingGameIds.add(ignoredGameId)

            Timber.tag("GOG").d("Found ${existingGameIds.size} games already in database")

            // Filter to only new games that need details fetched
            val newGameIds = gameIds.filter { it !in existingGameIds }
            Timber.tag("GOG").d("${newGameIds.size} new games need details fetched")

            if (newGameIds.isEmpty()) {
                Timber.tag("GOG").d("No new games to fetch, library is up to date")
                return@withContext Result.success(0)
            }

            var totalProcessed = 0

            Timber.tag("GOG").d("Getting Game Details for ${newGameIds.size} new GOG Games...")

            val games = mutableListOf<GOGGame>()

            // Use direct HTTP calls via GOGApiClient
            for ((index, id) in newGameIds.withIndex()) {
                try {
                    // Fetch game details using direct HTTP call
                    val result = GOGApiClient.getGameById(context, id)

                    if (result.isSuccess) {
                        val gameDetails = result.getOrNull()
                        if (gameDetails != null) {
                            Timber.tag("GOG").d("Got Game Details for ID: $id")
                            val parsedGame = parseGameObject(gameDetails)
                            if (parsedGame != null) {
                                val isHidden = hiddenIds?.contains(id) == true
                                // Only real (non-excluded) games are shown, so only fetch
                                // their portrait cover to avoid wasting GamesDB requests.
                                val game = if (parsedGame.exclude) {
                                    parsedGame.copy(hidden = isHidden)
                                } else {
                                    parsedGame.copy(
                                        hidden = isHidden,
                                        verticalCoverUrl = GOGApiClient.getVerticalCoverUrl(id),
                                    )
                                }
                                games.add(game)
                                Timber.tag("GOG").d("Refreshed Game: ${game.title}")
                                totalProcessed++
                            }
                        }
                    } else {
                        Timber.w("GOG game ID $id not found in library after refresh")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse game details for ID: $id")
                }

                if ((index + 1) % REFRESH_BATCH_SIZE == 0 || index == newGameIds.size - 1) {
                    if (games.isNotEmpty()) {
                        gogGameDao.upsertPreservingInstallStatus(games)
                        Timber.tag("GOG").d("Batch inserted ${games.size} games (processed ${index + 1}/${newGameIds.size})")
                        games.clear()
                    }
                }
            }
            val detectedCount = detectAndUpdateExistingInstallations()
            if (detectedCount > 0) {
                Timber.d("Detected and updated $detectedCount existing installations")
            }
            Timber.tag("GOG").i("Successfully refreshed GOG library with $totalProcessed games")
            return@withContext Result.success(totalProcessed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh GOG library")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Backfill portrait covers for games already in the database that predate the
     * vertical_cover_url column (or whose fetch previously failed). New games already
     * get their cover during [refreshLibrary], so this only touches existing rows.
     */
    private suspend fun backfillVerticalCovers() = withContext(Dispatchers.IO) {
        try {
            val gameIds = gogGameDao.getGameIdsMissingVerticalCover()
            if (gameIds.isEmpty()) return@withContext

            Timber.tag("GOG").d("Backfilling vertical covers for ${gameIds.size} games")
            var filled = 0
            for (id in gameIds) {
                val coverUrl = GOGApiClient.getVerticalCoverUrl(id)
                if (coverUrl.isNotEmpty()) {
                    gogGameDao.updateVerticalCoverUrl(id, coverUrl)
                    filled++
                }
            }
            Timber.tag("GOG").i("Backfilled $filled vertical covers")
        } catch (e: Exception) {
            Timber.tag("GOG").w(e, "Failed to backfill vertical covers")
        }
    }

    private fun parseGameObject(parsedGame: ParsedGogGame): GOGGame? {
        val title = parsedGame.title
        val id = parsedGame.id
        val downloadSize = parsedGame.downloadSize
        val isSecret = parsedGame.isSecret
        val isDlc = parsedGame.isDlc
        // Added Exclude so that we still store a record in the DB but we don't expose it.
        // This reduces the amount of fetching we do from the APIs and we also reduce chances of Amazon Prime duplicates etc.
        // Had to put in an extra case for some games not using isSecret but still are amazon prime duplicates...
        val exclude =
            title == "Unknown Game" || title.startsWith("product_title_") || title == "Unknown" || downloadSize == 0L || isSecret ||
                title.endsWith("Amazon Prime") || isDlc

        return GOGGame(
            id = id,
            title = title,
            exclude = exclude,
            slug = parsedGame.slug,
            imageUrl = parsedGame.imageUrl,
            iconUrl = parsedGame.iconUrl,
            backgroundUrl = parsedGame.backgroundUrl,
            description = parsedGame.description,
            releaseDate = parsedGame.releaseDate,
            developer = parsedGame.developer,
            publisher = parsedGame.publisher,
            genres = parsedGame.genres,
            languages = parsedGame.languages,
            downloadSize = parsedGame.downloadSize,
            installSize = 0L,
            isInstalled = false,
            installPath = "",
            lastPlayed = 0L,
            playTime = 0L,
        )
    }

    /**
     * Scan the GOG games directories for existing installations
     * and update the database with installation info
     *
     * @return Number of installations detected and updated
     */
    private suspend fun detectAndUpdateExistingInstallations(): Int = withContext(Dispatchers.IO) {
        var detectedCount = 0

        try {
            // Check both internal and external storage paths
            val pathsToCheck = listOf(
                GOGConstants.internalGOGGamesPath,
                GOGConstants.externalGOGGamesPath,
            )

            for (basePath in pathsToCheck) {
                val baseDir = File(basePath)
                if (!baseDir.exists() || !baseDir.isDirectory) {
                    Timber.d("Skipping non-existent path: $basePath")
                    continue
                }

                Timber.d("Scanning for installations in: $basePath")
                val installDirs = baseDir.listFiles { file -> file.isDirectory } ?: emptyArray()

                for (installDir in installDirs) {
                    try {
                        val detectedGame = detectGameFromDirectory(installDir)
                        if (detectedGame != null) {
                            // Update database with installation info
                            val existingGame = getGameFromDbById(detectedGame.id)
                            if (existingGame != null && !existingGame.isInstalled) {
                                val updatedGame = existingGame.copy(
                                    isInstalled = true,
                                    installPath = detectedGame.installPath,
                                    installSize = detectedGame.installSize,
                                )
                                updateGame(updatedGame)
                                detectedCount++
                                Timber.i("Detected existing installation: ${existingGame.title} at ${installDir.absolutePath}")
                            } else if (existingGame != null) {
                                Timber.d("Game ${existingGame.title} already marked as installed")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Error detecting game in ${installDir.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during installation detection")
        }

        detectedCount
    }

    /**
     * Try to detect which game is installed in the given directory
     *
     * @param installDir The directory to check
     * @return GOGGame with installation info, or null if no game detected
     */
    private suspend fun detectGameFromDirectory(installDir: File): GOGGame? {
        if (!installDir.exists() || !installDir.isDirectory) {
            return null
        }

        val dirName = installDir.name
        Timber.d("Checking directory: $dirName")

        // Look for .info files which contain game metadata
        val infoFiles = installDir.listFiles { file ->
            file.isFile && file.extension == "info"
        } ?: emptyArray()

        if (infoFiles.isNotEmpty()) {
            // Try to parse game ID from .info file
            val infoFile = infoFiles.first()
            try {
                val infoContent = infoFile.readText()
                val infoJson = JSONObject(infoContent)
                val gameId = infoJson.optString("gameId", "")
                if (gameId.isNotEmpty()) {
                    val game = getGameFromDbById(gameId)
                    if (game != null) {
                        val installSize = FileUtils.calculateDirectorySize(installDir)
                        return game.copy(
                            isInstalled = true,
                            installPath = installDir.absolutePath,
                            installSize = installSize,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error parsing .info file: ${infoFile.name}")
            }
        }

        // Fallback: Try to match by directory name with game titles in database
        val allGames = gogGameDao.getAllAsList()
        for (game in allGames) {
            // Sanitize game title to match directory naming convention
            val sanitizedTitle = game.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()

            if (dirName.equals(sanitizedTitle, ignoreCase = true)) {
                // Verify it's actually a game directory (has executables or subdirectories)
                val hasContent = installDir.listFiles()?.any {
                    it.isDirectory || it.extension in listOf("exe", "dll", "bat")
                } == true

                if (hasContent) {
                    val installSize = FileUtils.calculateDirectorySize(installDir)
                    Timber.d("Matched directory '$dirName' to game '${game.title}'")
                    return game.copy(
                        isInstalled = true,
                        installPath = installDir.absolutePath,
                        installSize = installSize,
                    )
                }
            }
        }

        return null
    }

    suspend fun refreshSingleGame(gameId: String, context: Context): Result<GOGGame?> {
        return try {
            Timber.d("Fetching single game data for gameId: $gameId via direct HTTP...")

            if (!GOGAuthManager.hasStoredCredentials(context)) {
                return Result.failure(Exception("Not authenticated"))
            }

            val result = GOGApiClient.getGameById(context, gameId)

            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Failed to fetch game data"))
            }

            val gameDetails = result.getOrNull()
            if (gameDetails == null) {
                Timber.w("Game $gameId not found in GOG library")
                return Result.success(null)
            }

            val game = parseGameObject(gameDetails)
            if (game == null) {
                Timber.tag("GOG").w("Skipping Invalid GOG App with id: $gameId")
                return Result.success(null)
            }
            // insertGame preserves install state, hidden, and cover; a hidden game that has not
            // been synced yet fails open (visible) until the next sync.
            insertGame(game)
            return Result.success(gogGameDao.getById(gameId) ?: game)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error fetching single game data for $gameId")
            Result.failure(e)
        }
    }

    suspend fun deleteGame(context: Context, libraryItem: LibraryItem): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val gameId = libraryItem.gameId.toString()

                val game = getGameFromDbById(gameId)
                val storedPath = game?.installPath?.takeIf { it.isNotBlank() }
                val computedPath = getGameInstallPath(gameId, libraryItem.name)
                val pathsToClean = listOfNotNull(storedPath, computedPath).distinct()

                val manifestPath = File(context.filesDir, "manifests/$gameId")
                if (manifestPath.exists()) {
                    manifestPath.delete()
                    Timber.i("Deleted manifest file for game $gameId")
                }

                val failedPaths = mutableListOf<String>()
                for (path in pathsToClean) {
                    val dir = File(path)
                    if (dir.exists()) {
                        if (NativeTreeDelete.deleteTreeFast(dir)) {
                            Timber.i("Successfully deleted game directory: $path")
                        } else {
                            Timber.w("Failed to delete some game files at $path")
                            failedPaths.add(path)
                        }
                    } else {
                        Timber.w("GOG game directory doesn't exist: $path")
                    }
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                    MarkerUtils.removeMarker(path, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                }

                // Drop any leftover chunk cache (kept on failed downloads for resume)
                NativeTreeDelete.deleteTreeFast(File(context.cacheDir, "gog_chunks/$gameId"))

                if (game != null) {
                    val updatedGame = game.copy(isInstalled = false, installPath = "")
                    gogGameDao.update(updatedGame)
                    Timber.d("Updated database: game marked as not installed")
                }

                withContext(Dispatchers.Main) {
                    ContainerUtils.deleteContainer(context, libraryItem.appId)
                }

                // Trigger library refresh event
                app.gamenative.PluviaApp.events.emitJava(
                    app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(libraryItem.gameId, app.gamenative.data.GameSource.GOG),
                )

                if (failedPaths.isNotEmpty()) {
                    return@withContext Result.failure(Exception("Failed to fully delete at: ${failedPaths.joinToString()}"))
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete GOG game ${libraryItem.gameId}")
                Result.failure(e)
            }
        }
    }

    fun isGameInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        try {
            val appDirPath = getAppDirPath(libraryItem.appId)

            // Use marker-based approach
            val isDownloadComplete = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            val isDownloadInProgress = MarkerUtils.hasMarker(appDirPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            val isInstalled = isDownloadComplete && !isDownloadInProgress

            // Update database if status changed
            val gameId = libraryItem.gameId.toString()
            val game = runBlocking { getGameFromDbById(gameId) }
            if (game != null && isInstalled != game.isInstalled) {
                val installPath = if (isInstalled) getGameInstallPath(gameId, libraryItem.name) else ""
                val updatedGame = game.copy(isInstalled = isInstalled, installPath = installPath)
                runBlocking { gogGameDao.update(updatedGame) }
            }

            return isInstalled
        } catch (e: Exception) {
            Timber.e(e, "Error checking if GOG game is installed")
            return false
        }
    }

    fun verifyInstallation(gameId: String): Pair<Boolean, String?> {
        val game = runBlocking { getGameFromDbById(gameId) }
        val installPath = game?.installPath

        if (game == null || installPath == null || !game.isInstalled) {
            return Pair(false, "Game not marked as installed in database")
        }

        val installDir = File(installPath)
        if (!installDir.exists()) {
            return Pair(false, "Install directory not found: $installPath")
        }

        if (!installDir.isDirectory) {
            return Pair(false, "Install path is not a directory")
        }

        val contents = installDir.listFiles()
        if (contents == null || contents.isEmpty()) {
            return Pair(false, "Install directory is empty")
        }

        Timber.i("Installation verified for game $gameId at $installPath")
        return Pair(true, null)
    }

    // Get the exe. There is a v1 and v2 depending on the age of the game.
    suspend fun getInstalledExe(libraryItem: LibraryItem): String = withContext(Dispatchers.IO) {
        val gameId = libraryItem.gameId.toString()
        try {
            val game = getGameFromDbById(gameId) ?: return@withContext ""
            val installPath = game.installPath.ifEmpty { getGameInstallPath(game.id, game.title) }

            // Try V2 structure first (game_$gameId subdirectory)
            val v2GameDir = File(installPath, "game_$gameId")
            if (v2GameDir.exists()) {
                return@withContext getGameExecutable(installPath, v2GameDir)
            }

            // Try V1 structure: goggame-*.info and exe can be in install root or in a subdir
            val installDirFile = File(installPath)
            val exe = getGameExecutable(installPath, installDirFile)
            if (exe.isNotEmpty()) return@withContext exe
            val subdirs = installDirFile.listFiles()?.filter {
                it.isDirectory && it.name != "saves" && it.name != "_CommonRedist"
            } ?: emptyList()

            for (subdir in subdirs) {
                val subdirExe = getGameExecutable(installPath, subdir)
                if (subdirExe.isNotEmpty()) return@withContext subdirExe
            }

            ""
        } catch (e: Exception) {
            Timber.e(e, "Failed to get executable for GOG game $gameId")
            ""
        }
    }

    /**
     * Resolves the effective launch executable for a GOG game (container config or auto-detected).
     * Returns empty string if no executable can be found.
     */
    suspend fun getLaunchExecutable(appId: String, container: Container): String = withContext(Dispatchers.IO) {
        container.executablePath.ifEmpty {
            getInstalledExe(LibraryItem(appId = appId, name = "", gameSource = GameSource.GOG))
        }
    }

    private fun getGameExecutable(installPath: String, gameDir: File): String {
        val result = getMainExecutableFromGOGInfo(gameDir, installPath)
        if (result.isSuccess) {
            val exe = result.getOrNull() ?: ""
            Timber.d("Found GOG game executable from info file: $exe")
            return exe
        }
        Timber.e(result.exceptionOrNull(), "Failed to find executable from GOG info file in: ${gameDir.absolutePath}")
        return ""
    }

    private fun findGOGInfoFile(directory: File, gameId: String? = null, maxDepth: Int = 3, currentDepth: Int = 0): File? {
        if (!directory.exists() || !directory.isDirectory) {
            return null
        }

        // Check current directory first
        val infoFile = directory.listFiles()?.find {
            it.isFile && if (gameId != null) {
                it.name == "goggame-$gameId.info"
            } else {
                it.name.startsWith("goggame-") && it.name.endsWith(".info")
            }
        }

        if (infoFile != null) {
            return infoFile
        }

        // If max depth reached, stop searching
        if (currentDepth >= maxDepth) {
            return null
        }

        // Search subdirectories recursively
        val subdirs = directory.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (subdir in subdirs) {
            val found = findGOGInfoFile(subdir, gameId, maxDepth, currentDepth + 1)
            if (found != null) {
                return found
            }
        }

        return null
    }

    private fun getMainExecutableFromGOGInfo(gameDir: File, installPath: String): Result<String> {
        return try {
            val infoFile = findGOGInfoFile(gameDir)
                ?: return Result.failure(Exception("GOG info file not found in ${gameDir.absolutePath}"))

            val content = infoFile.readText()
            val jsonObject = JSONObject(content)

            if (!jsonObject.has("playTasks")) {
                return Result.failure(Exception("playTasks array not found in ${infoFile.name}"))
            }

            val playTasks = jsonObject.getJSONArray("playTasks")
            val installDir = File(installPath)

            for (i in 0 until playTasks.length()) {
                val task = playTasks.getJSONObject(i)
                if (task.has("isPrimary") && task.getBoolean("isPrimary")) {
                    val executablePath = task.getString("path")
                    Timber.e("executable_path: $executablePath, gameDir: ${gameDir.absolutePath}")
                    val exeFile = FileUtils.findFileCaseInsensitive(gameDir, executablePath)
                    if (exeFile != null) {
                        val relativePath = exeFile.relativeTo(installDir).path
                        return Result.success(relativePath)
                    }
                    return Result.failure(Exception("Primary executable '$executablePath' not found in ${gameDir.absolutePath}"))
                }
            }

            Result.failure(Exception("No primary executable found in playTasks"))
        } catch (e: Exception) {
            Result.failure(Exception("Error parsing GOG info file in ${gameDir.absolutePath}: ${e.message}", e))
        }
    }

    fun getGogWineStartCommand(
        libraryItem: LibraryItem,
        container: Container,
        bootToContainer: Boolean,
        appLaunchInfo: LaunchInfo?,
        envVars: EnvVars,
        guestProgramLauncherComponent: GuestProgramLauncherComponent,
        gameId: Int,
    ): String {
        // Verify installation
        val (isValid, errorMessage) = verifyInstallation(gameId.toString())
        if (!isValid) {
            Timber.e("Installation verification failed: $errorMessage")
            return "\"explorer.exe\""
        }

        val game = runBlocking { getGameFromDbById(gameId.toString()) }
        if (game == null) {
            Timber.e("Game not found for ID: $gameId")
            return "\"explorer.exe\""
        }

        val gameInstallPath = game.installPath.ifEmpty { getGameInstallPath(gameId.toString(), game.title) }
        val gameDir = File(gameInstallPath)

        if (!gameDir.exists()) {
            Timber.e("Game directory does not exist: $gameInstallPath")
            return "\"explorer.exe\""
        }

        // Use container's configured executable path if available, otherwise auto-detect
        val executablePath = if (container.executablePath.isNotEmpty()) {
            Timber.d("Using configured executable path from container: ${container.executablePath}")
            container.executablePath
        } else {
            val detectedPath = runBlocking { getInstalledExe(libraryItem) }
            Timber.d("Auto-detected executable path: $detectedPath")
            if (detectedPath.isNotEmpty()) {
                container.executablePath = detectedPath
                container.saveData()
            }
            detectedPath
        }

        if (executablePath.isEmpty()) {
            Timber.w("No executable found, opening file manager")
            return "\"explorer.exe\""
        }

        if (ContainerUtils.isAbsoluteWindowsPath(executablePath)) {
            return "\"$executablePath\""
        }

        // Find the drive letter that's mapped to this game's install path
        var gogDriveLetter: String? = null
        for (drive in com.winlator.container.Container.drivesIterator(container.drives)) {
            if (drive[1] == gameInstallPath) {
                gogDriveLetter = drive[0]
                Timber.d("Found GOG game mapped to ${drive[0]}: drive")
                break
            }
        }

        if (gogDriveLetter == null) {
            Timber.e("GOG game directory not mapped to any drive: $gameInstallPath")
            return "\"explorer.exe\""
        }

        val gameInstallDir = File(gameInstallPath)
        val execFile = File(gameInstallPath, executablePath)
        // Handle potential IllegalArgumentException if paths don't share a common ancestor
        val relativePath = try {
            execFile.relativeTo(gameInstallDir).path.replace('/', '\\')
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Failed to compute relative path from $gameInstallDir to $execFile")
            return "\"explorer.exe\""
        }

        val windowsPath = "$gogDriveLetter:\\$relativePath"

        // Set working directory
        val execWorkingDir = execFile.parentFile
        if (execWorkingDir != null) {
            guestProgramLauncherComponent.workingDir = execWorkingDir
            envVars.put("WINEPATH", "$gogDriveLetter:\\")
        } else {
            guestProgramLauncherComponent.workingDir = gameDir
        }

        Timber.d("GOG Wine command: \"$windowsPath\"")
        return "\"$windowsPath\""
    }

    /**
     * Creates the GOG scriptinterpreter rootdir symlink when present. /DIR and /supportDir use
     * A:\_CommonRedist\ISI\rootdir; rootdir must be a symlink to the actual game install root so
     * it resolves correctly when the drive is mounted.
     */
    private fun ensureScriptInterpreterRootDirSymlink(gameInstallDir: File) {
        val commonRedistDir = File(gameInstallDir, "_CommonRedist")
        val isiDir = File(commonRedistDir, "ISI")
        if (isiDir.isDirectory) {
            ensureRootDirSymlink(gameInstallDir, isiDir)
        }
    }

    private fun ensureRootDirSymlink(gameInstallDir: File, parentDir: File) {
        val rootDirLink = File(parentDir, "rootdir")
        if (!rootDirLink.exists() || !WinlatorFileUtils.isSymlink(rootDirLink)) {
            try {
                WinlatorFileUtils.symlink(gameInstallDir, rootDirLink)
                Timber.tag("GOG").d(
                    "Created rootdir symlink: ${rootDirLink.absolutePath} -> ${gameInstallDir.absolutePath}",
                )
            } catch (e: Exception) {
                Timber.tag("GOG").e(
                    e,
                    "Failed to create rootdir symlink: ${rootDirLink.absolutePath} -> ${gameInstallDir.absolutePath}",
                )
            }
        }
    }

    /**
     * Returns command parts to run GOG scriptinterpreter.exe for each product (when required by
     * _gog_manifest.json). Used by LaunchSteps to prepend to the game launch command so it runs
     * in the same Wine session. Returns empty list if not needed or not available.
     */
    fun getScriptInterpreterPartsForLaunch(appId: String): List<String> {
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId) ?: return emptyList()
        val game = runBlocking { getGameFromDbById(gameId.toString()) } ?: return emptyList()
        val computedPath = getGameInstallPath(gameId.toString(), game.title)
        val gameInstallPath = when {
            game.installPath.isNotEmpty() && File(game.installPath).exists() -> game.installPath
            else -> computedPath
        }
        val gameInstallDir = File(gameInstallPath)
        if (!GOGManifestUtils.needsScriptInterpreter(gameInstallDir)) return emptyList()
        val root = GOGManifestUtils.readLocalManifest(gameInstallDir) ?: return emptyList()
        val isiRelativePath = "_CommonRedist/ISI/scriptinterpreter.exe"
        if (!File(gameInstallPath, isiRelativePath).exists()) return emptyList()

        ensureScriptInterpreterRootDirSymlink(gameInstallDir)

        val isiRelativePathWin = isiRelativePath.replace('/', '\\')
        val gameDriveLetter = "A"
        val buildId = root.optString("buildId", "")
        val versionName = root.optString("versionName", "")
        val langCode = root.optString("language", "en").let { if (it.length <= 2) "$it-US" else it }
        val language = "English"
        val productsArray = root.optJSONArray("products") ?: return emptyList()

        val parts = mutableListOf<String>()
        for (i in 0 until productsArray.length()) {
            val product = productsArray.getJSONObject(i)
            val productId = product.optString("productId", "")
            if (productId.isEmpty()) continue

            val exePathWin = "$gameDriveLetter:\\$isiRelativePathWin"
            // HACK: /DIR and /supportDir point to a \"rootdir\" folder inside ISI, which is a symlink
            // to the actual game install root (created during redist download). This gives
            // scriptinterpreter a full path with drive + folder name while still resolving
            // to the game directory that the drive letter is mapped to.
            val dirAndSupport = "$gameDriveLetter:\\_CommonRedist\\ISI\\rootdir"
            val args = listOf(
                "/VERYSILENT",
                "/DIR=$dirAndSupport",
                "/Language=$language",
                "/LANG=$language",
                "/ProductId=$productId",
                "/galaxyclient",
                "/buildId=$buildId",
                "/versionName=$versionName",
                "/lang-code=$langCode",
                "/supportDir=$dirAndSupport",
                "/nodesktopshorctut",
                "/nodesktopshortcut",
            ).joinToString(" ")

            parts.add("$exePathWin $args")
        }

        return parts
    }

    /**
     * Returns command parts to run Gen 1 (legacy) support_commands setup executables for launch.
     * These are per-game installers GOG ships in the support depot (downloaded to
     * _CommonRedist/<gameID>/) that create registry keys, shortcuts, etc. — the same step
     * Galaxy and Heroic perform after installing a Gen 1 game. Idempotent, so run each launch
     * like the scriptinterpreter path; the registry also self-heals if the prefix is recreated.
     */
    fun getSupportCommandPartsForLaunch(appId: String, knownInstallDir: File? = null): List<String> {
        val gameInstallDir = if (knownInstallDir != null && knownInstallDir.isDirectory) {
            knownInstallDir
        } else {
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId) ?: return emptyList()
            val game = runBlocking { getGameFromDbById(gameId.toString()) } ?: return emptyList()
            val computedPath = getGameInstallPath(gameId.toString(), game.title)
            val gameInstallPath = when {
                game.installPath.isNotEmpty() && File(game.installPath).exists() -> game.installPath
                else -> computedPath
            }
            File(gameInstallPath)
        }
        val root = GOGManifestUtils.readLocalManifest(gameInstallDir) ?: return emptyList()
        val commandsArray = root.optJSONArray("supportCommands") ?: return emptyList()
        if (commandsArray.length() == 0) return emptyList()

        val language = root.optString("language", "english")
        val buildId = root.optString("buildId", "")
        val versionName = root.optString("versionName", "")
        val gameDriveLetter = "A"

        val parts = mutableListOf<String>()
        for (i in 0 until commandsArray.length()) {
            val cmd = commandsArray.getJSONObject(i)
            val executable = cmd.optString("executable", "").trimStart('/')
            if (executable.isEmpty()) continue
            val cmdGameId = cmd.optString("gameID", "")
            if (cmdGameId.isEmpty()) continue

            val langsArr = cmd.optJSONArray("languages")
            var languageMatches = langsArr == null || langsArr.length() == 0
            if (langsArr != null) {
                val aliases = languageAliases(language)
                for (j in 0 until langsArr.length()) {
                    val l = langsArr.getString(j)
                    if (l.equals("Neutral", ignoreCase = true) || l.lowercase() in aliases) {
                        languageMatches = true
                        break
                    }
                }
            }
            if (!languageMatches) continue

            val supportSubDir = File(gameInstallDir, "_CommonRedist/$cmdGameId")
            val exeFile = File(supportSubDir, executable)
            if (!exeFile.exists()) {
                Timber.tag("GOG").w("Support command executable missing, skipping: ${exeFile.absolutePath}")
                continue
            }

            ensureRootDirSymlink(gameInstallDir, supportSubDir)

            val exePathWin = "$gameDriveLetter:\\_CommonRedist\\$cmdGameId\\${executable.replace('/', '\\')}"
            val dirArg = "$gameDriveLetter:\\_CommonRedist\\$cmdGameId\\rootdir"
            val args = listOf(
                "/VERYSILENT",
                "/DIR=$dirArg",
                "/Language=$language",
                "/LANG=$language",
                "/ProductId=$cmdGameId",
                "/galaxyclient",
                "/buildId=$buildId",
                "/versionName=$versionName",
                "/nodesktopshorctut",
                "/nodesktopshortcut",
            ).joinToString(" ")

            val extraArg = cmd.optString("argument", "")
            parts.add(if (extraArg.isNotEmpty()) "$exePathWin $extraArg $args" else "$exePathWin $args")
        }

        return parts
    }

    /**
     * All spellings that refer to the same language as [language] (container name plus GOG
     * codes, lowercased), so support_commands language names ("English") match whichever
     * form the download saved ("english", "en-US", "en", ...).
     */
    private fun languageAliases(language: String): Set<String> {
        val lower = language.lowercase()
        val entry = GOGConstants.CONTAINER_LANGUAGE_TO_GOG_CODES.entries.firstOrNull { (name, codes) ->
            name == lower || codes.any { it.equals(lower, ignoreCase = true) }
        } ?: return setOf(lower)
        return (entry.value.map { it.lowercase() } + entry.key).toSet()
    }

    // ==========================================================================
    // CLOUD SAVES
    // ==========================================================================

    /**
     * Read GOG game info file and extract clientId
     * @param appId Game ID
     * @param installPath Optional install path, if null will try to get from game database
     * @return JSONObject with game info, or null if not found
     */
    suspend fun readInfoFile(appId: String, installPath: String?): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            var path = installPath

            // If no install path provided, try to get from database
            if (path == null) {
                val game = getGameFromDbById(gameId.toString())
                path = game?.installPath
            }

            if (path == null || path.isEmpty()) {
                Timber.w("No install path found for game $gameId")
                return@withContext null
            }

            val installDir = File(path)
            if (!installDir.exists()) {
                Timber.w("Install directory does not exist: $path")
                return@withContext null
            }

            // Look for goggame-{gameId}.info file - check root first, then common subdirectories
            val infoFile = findGOGInfoFile(installDir, gameId.toString())

            if (infoFile == null || !infoFile.exists()) {
                Timber.w("Info file not found for game $gameId in ${installDir.absolutePath}")
                return@withContext null
            }

            val infoContent = infoFile.readText()
            val infoJson = JSONObject(infoContent)
            Timber.d("Successfully read info file for game $gameId")
            return@withContext infoJson
        } catch (e: Exception) {
            Timber.e(e, "Failed to read info file for appId $appId")
            return@withContext null
        }
    }

    /**
     * Fetch save locations from GOG Remote Config API
     * @param context Android context
     * @param appId Game app ID
     * @param installPath Game install path
     * @return Triple of (clientId, clientSecret, List of save location templates), or null if cloud saves not enabled or API call fails
     */
    suspend fun getSaveSyncLocation(
        context: Context,
        appId: String,
        installPath: String,
    ): Triple<String, String, List<GOGCloudSavesLocationTemplate>>? = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("[Cloud Saves] Getting save sync location for $appId")
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            val infoJson = readInfoFile(appId, installPath)

            if (infoJson == null) {
                Timber.tag("GOG").w("[Cloud Saves] Cannot get save sync location: info file not found")
                return@withContext null
            }

            // Get clientId + clientSecret from the build metadata, like gogdl/Heroic. The goggame-*.info
            // clientId is optional and missing for many games (e.g. Dead Cells), so prefer the .info value
            // only as a hint and fall back to the build metadata, which always carries both.
            val buildCredentials = GOGApiClient.getClientCredentials(context, gameId.toString(), installPath)
            val clientId = infoJson.optString("clientId", "").ifEmpty { buildCredentials?.first ?: "" }
            if (clientId.isEmpty()) {
                Timber.tag("GOG").w("[Cloud Saves] No clientId in info file or build metadata for game $gameId")
                return@withContext null
            }
            Timber.tag("GOG").d("[Cloud Saves] Client ID: $clientId")

            val clientSecret = buildCredentials?.second ?: ""
            if (clientSecret.isEmpty()) {
                Timber.tag("GOG").w("[Cloud Saves] No clientSecret available for game $gameId")
            } else {
                Timber.tag("GOG").d("[Cloud Saves] Got client secret for game")
            }

            // Check cache first
            remoteConfigCache[clientId]?.let { cachedLocations ->
                Timber.tag("GOG").d("[Cloud Saves] Using cached save locations for clientId $clientId (${cachedLocations.size} locations)")
                // Cache only contains locations, we still need to fetch clientSecret fresh
                return@withContext Triple(clientId, clientSecret, cachedLocations)
            }

            // Android runs games through Wine, so always use Windows platform
            val syncPlatform = "Windows"

            // Fetch remote config
            val url = "https://remote-config.gog.com/components/galaxy_client/clients/$clientId?component_version=2.0.45"
            Timber.tag("GOG").d("[Cloud Saves] Fetching remote config from: $url")

            val request = Request.Builder()
                .url(url)
                .build()

            val response = Net.http.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    Timber.tag("GOG").w("[Cloud Saves] Failed to fetch remote config: HTTP ${response.code}")
                    return@withContext null
                }
                Timber.tag("GOG").d("[Cloud Saves] Successfully fetched remote config")

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Timber.tag("GOG").w("[Cloud Saves] Empty response body from remote config")
                    return@withContext null
                }
                val configJson = JSONObject(responseBody)

                // Parse response: content.Windows.cloudStorage.locations
                val content = configJson.optJSONObject("content")
                if (content == null) {
                    Timber.tag("GOG").w("[Cloud Saves] No 'content' field in remote config response")
                    return@withContext null
                }

                val platformContent = content.optJSONObject(syncPlatform)
                if (platformContent == null) {
                    Timber.tag("GOG").d("[Cloud Saves] No cloud storage config for platform $syncPlatform")
                    return@withContext null
                }

                val cloudStorage = platformContent.optJSONObject("cloudStorage")
                if (cloudStorage == null) {
                    Timber.tag("GOG").d("[Cloud Saves] No cloudStorage field for platform $syncPlatform")
                    return@withContext null
                }

                val enabled = cloudStorage.optBoolean("enabled", false)
                if (!enabled) {
                    Timber.tag("GOG").d("[Cloud Saves] Cloud saves not enabled for game $gameId")
                    return@withContext null
                }
                Timber.tag("GOG").d("[Cloud Saves] Cloud saves are enabled for game $gameId")

                val locationsArray = cloudStorage.optJSONArray("locations")
                if (locationsArray == null || locationsArray.length() == 0) {
                    // Cloud is enabled but GOG declares no filesystem locations: the game syncs saves
                    // through the Galaxy SDK IStorage API, which mirrors them to a fixed local path.
                    // gogdl/Heroic skip these games; fall back to that path and let the existing sync
                    // (conflict prompt included) run. Empty name => list the whole bucket unfiltered.
                    Timber.tag("GOG").i("[Cloud Saves] Enabled with no locations for game $gameId — using Galaxy SDK storage fallback")
                    val fallback = GOGCloudSavesLocationTemplate(
                        "",
                        "%LOCALAPPDATA%/GOG.com/Galaxy/Applications/$clientId/Storage/Shared/Files",
                    )
                    return@withContext Triple(clientId, clientSecret, listOf(fallback))
                }
                Timber.tag("GOG").d("[Cloud Saves] Found ${locationsArray.length()} location(s) in config")

                val locations = mutableListOf<GOGCloudSavesLocationTemplate>()
                for (i in 0 until locationsArray.length()) {
                    val locationObj = locationsArray.getJSONObject(i)
                    val name = locationObj.optString("name", "__default")
                    val location = locationObj.optString("location", "")
                    if (location.isNotEmpty()) {
                        Timber.tag("GOG").d("[Cloud Saves] Location ${i + 1}: '$name' = '$location'")
                        locations.add(GOGCloudSavesLocationTemplate(name, location))
                    } else {
                        Timber.tag("GOG").w("[Cloud Saves] Skipping location ${i + 1} with empty path")
                    }
                }

                // Cache the result
                if (locations.isNotEmpty()) {
                    remoteConfigCache[clientId] = locations
                    Timber.tag("GOG").d("[Cloud Saves] Cached ${locations.size} save locations for clientId $clientId")
                }

                Timber.tag("GOG").i("[Cloud Saves] Found ${locations.size} save location(s) for game $gameId")
                return@withContext Triple(clientId, clientSecret, locations)
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "[Cloud Saves] Failed to get save sync location for appId $appId")
            return@withContext null
        }
    }

    /**
     * Get resolved save directory paths for a game
     * @param context Android context
     * @param appId Game app ID
     * @param gameTitle Game title (for fallback)
     * @return List of resolved save locations, or null if cloud saves not available
     */
    suspend fun getSaveDirectoryPath(
        context: Context,
        appId: String,
        gameTitle: String,
    ): List<GOGCloudSavesLocation>? = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG").d("[Cloud Saves] Getting save directory path for $appId ($gameTitle)")
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            val game = getGameFromDbById(gameId.toString())

            if (game == null) {
                Timber.tag("GOG").w("[Cloud Saves] Game not found for appId $appId")
                return@withContext null
            }

            val installPath = game.installPath
            if (installPath.isEmpty()) {
                Timber.tag("GOG").w("[Cloud Saves] Game not installed: $appId")
                return@withContext null
            }
            Timber.tag("GOG").d("[Cloud Saves] Game install path: $installPath")

            // Fetch clientId + clientSecret + save locations from API (Android runs games through Wine,
            // so always Windows). clientId comes from build metadata when the goggame-*.info omits it.
            Timber.tag("GOG").d("[Cloud Saves] Fetching save locations from API")
            val result = getSaveSyncLocation(context, appId, installPath)

            if (result == null || result.third.isEmpty()) {
                // The remote config API returned no locations, meaning this game either has cloud
                // saves disabled or no save paths configured. We don't fall back to the default
                // GOG Galaxy path (%LOCALAPPDATA%/GOG.com/Galaxy/Applications/<clientId>/Storage/…)
                // because clientSecret also comes from the API — without it, cloud auth cannot
                // succeed and the fallback path can never be used for a real sync.
                Timber.tag("GOG").d("[Cloud Saves] No save locations from API for game $gameId, cloud saves not supported")
                return@withContext null
            }

            val clientId = result.first
            val clientSecret = result.second
            val locations = result.third
            Timber.tag("GOG").d("[Cloud Saves] Client ID: $clientId")
            Timber.tag("GOG").i("[Cloud Saves] Retrieved ${locations.size} save location(s) from API")

            // Resolve each location
            val resolvedLocations = mutableListOf<GOGCloudSavesLocation>()
            for ((index, locationTemplate) in locations.withIndex()) {
                Timber.tag("GOG").d("[Cloud Saves] Resolving location ${index + 1}/${locations.size}: '${locationTemplate.name}' = '${locationTemplate.location}'")
                // Resolve GOG variables (<?INSTALL?>, etc.) to Windows env vars
                var resolvedPath = PathType.resolveGOGPathVariables(locationTemplate.location, installPath)
                Timber.tag("GOG").d("[Cloud Saves] After GOG variable resolution: $resolvedPath")

                // Install-relative locations (<?INSTALL?>) resolve to the real on-disk install dir, which
                // is already an absolute host path — use it directly, like Heroic does. Only Windows-style
                // locations (%LOCALAPPDATA% etc.) need mapping into the Wine prefix; running toAbsPathForGOG
                // on an absolute host path wrongly re-roots it under .wine/drive_c.
                resolvedPath = resolvedPath.replace("\\", "/")
                if (!resolvedPath.startsWith(installPath.replace("\\", "/"))) {
                    // Pass appId to ensure we use the correct container-specific wine prefix
                    resolvedPath = PathType.toAbsPathForGOG(context, resolvedPath, appId)
                }
                Timber.tag("GOG").d("[Cloud Saves] After path mapping to Wine prefix: $resolvedPath")

                // Normalize path to resolve any '..' or '.' components
                try {
                    val normalizedPath = File(resolvedPath).canonicalPath
                    // Ensure trailing slash for directories
                    resolvedPath = if (!normalizedPath.endsWith("/")) "$normalizedPath/" else normalizedPath
                    Timber.tag("GOG").d("[Cloud Saves] After normalization: $resolvedPath")
                } catch (e: Exception) {
                    Timber.tag("GOG").w(e, "[Cloud Saves] Failed to normalize path, using as-is: $resolvedPath")
                }

                resolvedLocations.add(
                    GOGCloudSavesLocation(
                        name = locationTemplate.name,
                        location = resolvedPath,
                        clientId = clientId,
                        clientSecret = clientSecret,
                    ),
                )
            }

            Timber.tag("GOG").i("[Cloud Saves] Resolved ${resolvedLocations.size} save location(s) for game $gameId")
            for (loc in resolvedLocations) {
                Timber.tag("GOG").d("[Cloud Saves]   - '${loc.name}': ${loc.location}")
            }
            return@withContext resolvedLocations
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "[Cloud Saves] Failed to get save directory path for appId $appId")
            return@withContext null
        }
    }

    /**
     * Get stored sync timestamp for a game+location
     * @param appId Game app ID
     * @param locationName Location name
     * @return Timestamp string, or "0" if not found
     */
    fun getCloudSaveSyncTimestamp(appId: String, locationName: String): String {
        val key = "${appId}_$locationName"
        return syncTimestamps.getOrDefault(key, "0")
    }

    /**
     * Store sync timestamp for a game+location
     * @param appId Game app ID
     * @param locationName Location name
     * @param timestamp Timestamp string
     */
    fun setCloudSaveSyncTimestamp(appId: String, locationName: String, timestamp: String) {
        val key = "${appId}_$locationName"
        syncTimestamps[key] = timestamp
        Timber.d("Stored sync timestamp for $key: $timestamp")
        // Persist to disk
        saveCloudSaveTimestampsToDisk()
    }

    /**
     * Start a sync operation for a game (prevents concurrent syncs)
     * @param appId Game app ID
     * @return true if sync can proceed, false if one is already in progress
     */
    fun startSync(appId: String): Boolean {
        return activeSyncs.add(appId)
    }

    /**
     * End a sync operation for a game
     * @param appId Game app ID
     */
    fun endSync(appId: String) {
        activeSyncs.remove(appId)
    }

    /**
     * Load timestamps from disk
     */
    private fun loadCloudSaveTimestampsFromDisk() {
        try {
            if (timestampFile.exists()) {
                val json = timestampFile.readText()
                val map = org.json.JSONObject(json)
                map.keys().forEach { key ->
                    syncTimestamps[key] = map.getString(key)
                }
                Timber.tag("GOG").i("[Cloud Saves] Loaded ${syncTimestamps.size} sync timestamps from disk")
            } else {
                Timber.tag("GOG").d("[Cloud Saves] No persisted timestamps found (first run)")
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "[Cloud Saves] Failed to load timestamps from disk")
        }
    }

    /**
     * Save timestamps to disk
     */
    private fun saveCloudSaveTimestampsToDisk() {
        try {
            val json = org.json.JSONObject()
            syncTimestamps.forEach { (key, value) ->
                json.put(key, value)
            }
            timestampFile.writeText(json.toString())
            Timber.tag("GOG").d("[Cloud Saves] Saved ${syncTimestamps.size} timestamps to disk")
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "[Cloud Saves] Failed to save timestamps to disk")
        }
    }

    // ==========================================================================
    // FILE SYSTEM & PATHS
    // ==========================================================================

    fun getAppDirPath(appId: String): String {
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
        val game = runBlocking { getGameFromDbById(gameId.toString()) }

        if (game != null) {
            if (game.installPath.isNotBlank()) return game.installPath
            return GOGConstants.getGameInstallPath(game.title)
        }

        Timber.w("Could not find game for appId $appId")
        return GOGConstants.defaultGOGGamesPath
    }

    fun getGameInstallPath(gameId: String, gameTitle: String): String {
        return GOGConstants.getGameInstallPath(gameTitle)
    }
}
