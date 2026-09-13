package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ModTargetResolverTest {
    private lateinit var tempDir: File
    private lateinit var gameDir: File
    private lateinit var winePrefix: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("mod_target_resolver").toFile()
        gameDir = File(tempDir, "game").apply { mkdirs() }
        winePrefix = File(tempDir, "prefix").apply {
            File(this, "drive_c/users/xuser/Documents/My Games").mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun resolve_blocksRelativePathEscapingSelectedRoot() {
        val resolved = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "../outside",
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertNull(resolved)
    }

    @Test
    fun resolve_allowsCustomAbsoluteOnlyInsideKnownRoots() {
        val inside = File(gameDir, "Data").apply { mkdirs() }
        val outside = File(tempDir, "outside").apply { mkdirs() }

        val resolvedInside = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.CUSTOM_ABSOLUTE.name,
            targetRelativePath = inside.absolutePath,
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )
        val resolvedOutside = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.CUSTOM_ABSOLUTE.name,
            targetRelativePath = outside.absolutePath,
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertEquals(inside.canonicalFile, resolvedInside)
        assertNull(resolvedOutside)
    }

    @Test
    fun resolve_rejectsRelativeCustomAbsolutePath() {
        val resolved = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.CUSTOM_ABSOLUTE.name,
            targetRelativePath = "Data",
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertNull(resolved)
    }

    @Test
    fun resolve_normalizesWindowsSeparatorsInRelativePath() {
        val resolved = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data\\Textures",
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertEquals(File(gameDir, "Data/Textures").canonicalFile, resolved)
    }

    @Test
    fun resolve_reusesExistingDirectoryCasing() {
        val scripts = File(gameDir, "Data/Scripts").apply { mkdirs() }

        val resolved = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "data/scripts",
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertEquals(scripts.canonicalFile, resolved)
    }

    @Test
    fun inspectPlan_reportsCaseMergeWithoutChangingThePlan() {
        File(gameDir, "Data/Scripts").mkdirs()
        val plan = ModInstallPlan(
            files = listOf(
                PlannedModFile(
                    sourceRelativePath = "scripts/example.pex",
                    targetRoot = ModTargetRoot.GAME_DIR.name,
                    targetRelativePath = "Data/scripts/example.pex",
                    normalizedTargetKey = "GAME_DIR:data/scripts/example.pex",
                    status = PlannedFileStatus.PLACED,
                    origin = PlacementOrigin.GAME_RULE,
                    reason = "fixture",
                ),
            ),
        )

        val inspection = ModTargetResolver.inspectPlan(plan, ModTargetResolver.roots(gameDir, winePrefix.absolutePath))

        assertTrue(inspection.caseMerges.any { it.contains("scripts -> Scripts") })
        assertTrue(inspection.ambiguousPaths.isEmpty())
    }

    @Test
    fun inspectPlan_reusesPlannedDirectoryCasing() {
        val plan = ModInstallPlan(
            files = listOf(
                plannedFile("Data/Scripts/First.pex"),
                plannedFile("data/scripts/Second.pex"),
            ),
        )

        val inspection = ModTargetResolver.inspectPlan(plan, ModTargetResolver.roots(gameDir, winePrefix.absolutePath))

        assertTrue(inspection.caseMerges.any { it.contains("scripts -> Scripts") })
        assertTrue(inspection.ambiguousPaths.isEmpty())
    }

    @Test
    fun resolve_blocksAmbiguousExistingCaseVariants() {
        File(gameDir, "Data/Scripts").mkdirs()
        File(gameDir, "Data/scripts").mkdirs()
        assumeTrue(
            File(gameDir, "Data").listFiles().orEmpty().count { it.name.equals("scripts", ignoreCase = true) } == 2,
        )

        val resolved = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data/SCRIPTS",
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertNull(resolved)
    }

    @Test
    fun resolve_normalizesWindowsSeparatorsInCustomAbsolutePath() {
        val inside = File(gameDir, "Data/Textures").apply { mkdirs() }
        val windowsStyleInside = inside.absolutePath.replace('/', '\\')

        val resolved = ModTargetResolver.resolve(
            targetRoot = ModTargetRoot.CUSTOM_ABSOLUTE.name,
            targetRelativePath = windowsStyleInside,
            gameRootDir = gameDir,
            winePrefix = winePrefix.absolutePath,
        )

        assertEquals(inside.canonicalFile, resolved)
    }

    @Test
    fun roots_includeWineBookmarkLocations() {
        val roots = ModTargetResolver.roots(gameDir, winePrefix.absolutePath)

        assertTrue(roots.any { it.type == ModTargetRoot.GAME_DIR })
        assertTrue(roots.any { it.type == ModTargetRoot.WINE_C })
        assertTrue(roots.any { it.type == ModTargetRoot.MY_GAMES })
    }

    @Test
    fun getWineUserHome_createsSteamUserWhenNoUserExists() {
        val emptyPrefix = File(tempDir, "empty-prefix")

        val userHome = ModContainerResolver.getWineUserHome(emptyPrefix.absolutePath)

        assertEquals(File(emptyPrefix, "drive_c/users/steamuser").canonicalFile, userHome.canonicalFile)
        assertTrue(userHome.isDirectory)
    }

    private fun plannedFile(path: String) = PlannedModFile(
        sourceRelativePath = path,
        targetRoot = ModTargetRoot.GAME_DIR.name,
        targetRelativePath = path,
        normalizedTargetKey = ModTargetResolver.normalizedTargetKey(ModTargetRoot.GAME_DIR.name, path),
        status = PlannedFileStatus.PLACED,
        origin = PlacementOrigin.GAME_RULE,
        reason = "fixture",
    )
}
