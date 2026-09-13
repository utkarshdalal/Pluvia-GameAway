package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModArchiveIndexPerformanceTest {
    @Test
    fun fiftyThousandEntries_indexWithinInteractiveRegressionBudget() {
        val entries = List(50_000) { index ->
            ModArchiveEntry(
                path = "Data/Textures/Set${index / 100}/texture$index.dds",
                directory = false,
                sizeBytes = 1,
            )
        }
        lateinit var archiveIndex: ModArchiveIndex

        val elapsed = measureTimeMillis { archiveIndex = ModArchiveIndex.build(entries) }

        assertEquals(50_000, archiveIndex.files.size)
        assertEquals(100, archiveIndex.filesUnder("Data/Textures/Set42").size)
        assertTrue("Indexing took ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun fiftyThousandEntries_completePlanningWithinInteractiveRegressionBudget() {
        val entries = List(50_000) { index ->
            ModArchiveEntry(
                path = "Data/Textures/Set${index / 100}/texture$index.dds",
                directory = false,
                sizeBytes = 1,
            )
        }
        lateinit var plan: ModInstallPlan

        val elapsed = measureTimeMillis {
            plan = AutomaticPlacementPlanner.plan("Skyrim Special Edition", entries).recommended!!.plan
        }

        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(50_000, plan.placedCount)
        assertTrue("Planning took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun twoThousandNestedFiles_materializeWithinGenerousRegressionBudget() {
        val root = createTempDirectory("materialization_performance").toFile()
        try {
            val extracted = File(root, "extracted").apply { mkdirs() }
            val game = File(root, "game").apply { mkdirs() }
            repeat(2_000) { index ->
                File(extracted, "Data/Textures/Set${index / 20}/texture$index.dds").apply {
                    parentFile?.mkdirs()
                    createNewFile()
                }
            }
            val install = ModInstall(
                installId = "performance",
                appId = "STEAM_1",
                nexusGameDomain = "game",
                nexusModId = 1,
                nexusFileId = 2,
                modName = "Large tree",
                fileName = "large.zip",
                archivePath = File(root, "large.zip").absolutePath,
                extractedPath = extracted.absolutePath,
            )
            val recipe = ModPlacementRecipe(
                installId = install.installId,
                sourceSubpath = "Data",
                targetRoot = ModTargetRoot.GAME_DIR.name,
                targetRelativePath = "Data",
                mode = ModPlacementMode.OVERWRITE_COPY.name,
            )
            lateinit var plan: ModMaterializationPlan

            val elapsed = measureTimeMillis {
                plan = ModMaterializer.materializationPlan(install, listOf(recipe), game, "", captureTargetHashes = false)
            }

            assertTrue(plan.errors.toString(), plan.isComplete)
            assertEquals(2_000, plan.files.size)
            assertTrue("Materialization planning took ${elapsed}ms", elapsed < 15_000)
        } finally {
            root.deleteRecursively()
        }
    }
}
