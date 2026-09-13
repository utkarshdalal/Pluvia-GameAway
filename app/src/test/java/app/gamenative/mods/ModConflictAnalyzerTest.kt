package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ModConflictAnalyzerTest {
    private lateinit var tempDir: File
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("mod_conflicts").toFile()
        gameDir = File(tempDir, "game").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun analyze_reportsWinningModByPriority() = runBlocking {
        val low = install("low", "Low Priority", "low")
        val high = install("high", "High Priority", "high")
        File(low.extractedPath, "Data/config.ini").apply {
            parentFile?.mkdirs()
            writeText("low")
        }
        File(high.extractedPath, "Data/config.ini").apply {
            parentFile?.mkdirs()
            writeText("high")
        }

        val reports = ModConflictAnalyzer.analyze(
            installs = listOf(low, high),
            recipesByInstallId = mapOf(
                low.installId to listOf(recipe(low.installId)),
                high.installId to listOf(recipe(high.installId)),
            ),
            prioritiesByInstallId = mapOf(low.installId to 10, high.installId to 20),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertEquals(1, reports.size)
        val report = reports.single()
        assertTrue(report.targetPath.endsWith("Data${File.separator}config.ini"))
        assertEquals("high", report.winnerInstallId)
        assertEquals(listOf("high", "low"), report.participants.map { it.installId })
        assertEquals(true, report.participants.first().wins)
    }

    @Test
    fun analyze_treatsCaseVariantWindowsTargetsAsOneConflict() = runBlocking {
        val first = install("first", "First", "first")
        val second = install("second", "Second", "second")
        File(first.extractedPath, "Data/Scripts/A.pex").apply {
            parentFile?.mkdirs()
            writeText("first")
        }
        File(second.extractedPath, "Data/scripts/a.pex").apply {
            parentFile?.mkdirs()
            writeText("second")
        }

        val reports = ModConflictAnalyzer.analyze(
            installs = listOf(first, second),
            recipesByInstallId = mapOf(first.installId to listOf(recipe(first.installId)), second.installId to listOf(recipe(second.installId))),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
        )

        assertEquals(1, reports.size)
        assertEquals(setOf("first", "second"), reports.single().participants.map { it.installId }.toSet())
    }

    @Test
    fun analyze_reusesAppliedOwnershipWithoutRebuildingArchivePlans() = runBlocking {
        val low = install("low", "Low", "low").copy(status = ModInstallStatus.APPLIED.name)
        val high = install("high", "High", "high").copy(status = ModInstallStatus.APPLIED.name)
        val target = File(gameDir, "Data/shared.txt")

        val reports = ModConflictAnalyzer.analyze(
            installs = listOf(low, high),
            recipesByInstallId = emptyMap(),
            prioritiesByInstallId = mapOf("low" to 1, "high" to 2),
            gameRootDir = gameDir,
            winePrefix = "",
            ownershipByInstallId = mapOf(
                "low" to ownership(low, target),
                "high" to ownership(high, target),
            ),
        )

        assertEquals("high", reports.single().winnerInstallId)
    }

    private fun install(id: String, name: String, folder: String): ModInstall {
        val extracted = File(tempDir, folder).apply { mkdirs() }
        return ModInstall(
            installId = id,
            appId = "APP",
            nexusGameDomain = "game",
            nexusModId = id.hashCode().toLong(),
            nexusFileId = id.hashCode().toLong(),
            modName = name,
            fileName = "$id.zip",
            archivePath = File(tempDir, "$id.zip").absolutePath,
            extractedPath = extracted.absolutePath,
        )
    }

    private fun recipe(installId: String): ModPlacementRecipe =
        ModPlacementRecipe(
            installId = installId,
            sourceSubpath = "Data",
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data",
            mode = ModPlacementMode.OVERWRITE_COPY.name,
        )

    private fun ownership(install: ModInstall, target: File): ModOwnershipManifest =
        ModOwnershipManifest(
            installId = install.installId,
            appId = install.appId,
            planDigest = install.installId,
            files = listOf(
                ModOwnedFile(
                    sourceRelativePath = "Data/shared.txt",
                    targetRoot = ModTargetRoot.GAME_DIR.name,
                    targetRelativePath = "Data/shared.txt",
                    targetPath = target.absolutePath,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    installedHash = install.installId,
                    installedSize = 1L,
                    installedMtime = 1L,
                    disposition = ModOwnedFileDisposition.CREATED,
                ),
            ),
        )
}
