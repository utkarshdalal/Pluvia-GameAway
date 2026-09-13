package app.gamenative.ui.component.dialog

import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ModInstallPlan
import app.gamenative.mods.ModOwnedFile
import app.gamenative.mods.ModOwnedFileDisposition
import app.gamenative.mods.ModOwnershipManifest
import app.gamenative.mods.ModPlanChange
import app.gamenative.mods.ModPlanChangeType
import app.gamenative.mods.ModReconfigurationDiff
import app.gamenative.mods.PlacementOrigin
import app.gamenative.mods.PlannedFileStatus
import app.gamenative.mods.PlannedModFile
import app.gamenative.mods.ResolvedModTargetRoot
import app.gamenative.mods.WindowsPathIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlacementWorkspaceModelsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun layoutChoice_isHiddenWhenItCannotChangeTheResult() {
        val entries = listOf(ModArchiveEntry("readme.txt", directory = false, sizeBytes = 1))

        assertFalse(placementLayoutModel(RecipeDraft(), entries).visible)
        assertFalse(placementLayoutModel(RecipeDraft(sourceSubpath = "readme.txt"), entries).visible)
        assertFalse(placementLayoutModel(RecipeDraft(sourceSubpath = "Data", targetFileName = "file.txt"), entries).visible)
    }

    @Test
    fun placementDraftPage_boundsLargeRuleSets() {
        assertEquals(0 until 20, placementDraftPage(6_654, 0).let { it.startIndex until it.endIndexExclusive })
        val last = placementDraftPage(6_654, Int.MAX_VALUE)
        assertEquals(332, last.pageIndex)
        assertEquals(6_640 until 6_654, last.startIndex until last.endIndexExclusive)
    }

    @Test
    fun layoutChoice_explainsFolderAndContentsResults() {
        val entries = listOf(ModArchiveEntry("Data/file.txt", directory = false, sizeBytes = 1))
        val contents = placementLayoutModel(
            RecipeDraft(sourceSubpath = "Data", targetRelativePath = "Data"),
            entries,
        )
        val folder = placementLayoutModel(
            RecipeDraft(sourceSubpath = "Data", targetRelativePath = "Data", includeSourceDirectory = true),
            entries,
        )

        assertEquals("Data/<selected contents>", contents.resultExample)
        assertEquals("Data/Data/<contents>", folder.resultExample)
        assertTrue(folder.duplicateFolderWarning)
    }

    @Test
    fun everythingSelection_explainsThatThePackageFolderIsPreserved() {
        val layout = placementLayoutModel(
            RecipeDraft(targetRelativePath = "Mods"),
            listOf(ModArchiveEntry("CharacterEditor/About/About.xml", directory = false, sizeBytes = 1)),
        )

        assertTrue(layout.visible)
        assertFalse(layout.editable)
        assertEquals("Mods/CharacterEditor/<contents>", layout.resultExample)
    }

    @Test
    fun virtualFolderName_rejectsTraversalReservedAndSeparators() {
        assertTrue(validVirtualDestinationFolderName("New Mods"))
        assertFalse(validVirtualDestinationFolderName("../escape"))
        assertFalse(validVirtualDestinationFolderName("Data/Plugins"))
        assertFalse(validVirtualDestinationFolderName("CON"))
    }

    @Test
    fun virtualDestinationFolder_rejectsAnExistingFile() {
        val parent = temporaryFolder.newFolder("destination-parent")
        File(parent, "existing").writeText("file")
        File(parent, "directory").mkdir()

        assertFalse(validVirtualDestinationFolder(parent, "existing"))
        assertTrue(validVirtualDestinationFolder(parent, "new folder"))
        assertTrue(validVirtualDestinationFolder(parent, "directory"))
    }

    @Test
    fun destinationBrowser_showsFilesAndClassifiesPlanAndOwnership() {
        val rootDir = temporaryFolder.newFolder("game")
        val dataDir = File(rootDir, "Data").apply { mkdirs() }
        val replace = File(dataDir, "replace.txt").apply { writeText("old") }
        val managed = File(dataDir, "managed.txt").apply { writeText("managed") }
        File(dataDir, ".hidden.txt").writeText("hidden")
        val root = ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game", rootDir)
        val plan = ModInstallPlan(
            files = listOf(
                planned("Data/replace.txt", ModPlacementMode.OVERWRITE_COPY),
                planned("Data/new.txt", ModPlacementMode.COPY),
            ),
        )
        val ownership = ModOwnershipManifest(
            installId = "selected",
            appId = "app",
            planDigest = "digest",
            files = listOf(
                ModOwnedFile(
                    sourceRelativePath = "managed.txt",
                    targetRoot = ModTargetRoot.GAME_DIR.name,
                    targetRelativePath = "Data/managed.txt",
                    targetPath = managed.absolutePath,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(managed),
                    mode = ModPlacementMode.COPY.name,
                    installedHash = "hash",
                    installedSize = managed.length(),
                    installedMtime = managed.lastModified(),
                    disposition = ModOwnedFileDisposition.CREATED,
                ),
            ),
        )

        val entries = destinationBrowserEntries(dataDir, root, plan, listOf(ownership), "selected", false, "")
        val replaceEntry = entries.single { it.file == replace }
        val managedEntry = entries.single { it.file == managed }

        assertTrue(DestinationEntryImpact.WILL_REPLACE in replaceEntry.impacts)
        assertTrue(DestinationEntryImpact.WILL_BACK_UP in replaceEntry.impacts)
        assertTrue(DestinationEntryImpact.MANAGED_BY_THIS_MOD in managedEntry.impacts)
        assertTrue(entries.none { it.file.name.startsWith('.') })
    }

    @Test
    fun destinationBrowser_searchesAndIncludesVirtualFolder() {
        val rootDir = temporaryFolder.newFolder("search-game")
        File(rootDir, "Data").mkdirs()
        File(rootDir, "BepInEx").mkdirs()
        val root = ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game", rootDir)

        val entries = destinationBrowserEntries(
            rootDir,
            root,
            null,
            emptyList(),
            "selected",
            false,
            "mod",
            virtualFolderName = "Mods",
        )

        assertEquals(listOf("Mods"), entries.map { it.file.name })
        assertTrue(entries.single().virtual)
    }

    @Test
    fun destinationBrowser_doesNotMatchPlansWithoutTargetKeys() {
        val rootDir = temporaryFolder.newFolder("unresolved-game")
        val unmanaged = File(rootDir, "unmanaged.bin").apply { writeText("game") }
        val root = ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game", rootDir)
        val unresolvedPlan = ModInstallPlan(
            files = listOf(
                PlannedModFile(
                    sourceRelativePath = "unknown.bin",
                    status = PlannedFileStatus.CONFLICTED,
                    origin = PlacementOrigin.GAME_RULE,
                    reason = "No target",
                ),
            ),
        )

        val entry = destinationBrowserEntries(
            rootDir,
            root,
            unresolvedPlan,
            emptyList(),
            "selected",
            false,
            "",
        ).single { it.file == unmanaged }

        assertFalse(DestinationEntryImpact.PLAN_CONFLICT in entry.impacts)
        assertTrue(DestinationEntryImpact.GAME_OR_UNMANAGED in entry.impacts)
    }

    @Test
    fun reviewRows_groupPlanAndReconfigurationChanges() {
        val rootDir = temporaryFolder.newFolder("review-game")
        File(rootDir, "Data/existing.txt").apply { parentFile?.mkdirs(); writeText("old") }
        val root = ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game", rootDir)
        val plan = ModInstallPlan(
            files = listOf(
                planned("Data/new.txt"),
                planned("Data/existing.txt"),
                planned("Data/moved.txt"),
                PlannedModFile(
                    sourceRelativePath = "docs/readme.txt",
                    status = PlannedFileStatus.INTENTIONALLY_IGNORED,
                    origin = PlacementOrigin.GAME_RULE,
                    reason = "Documentation",
                ),
                PlannedModFile(
                    sourceRelativePath = "unknown/file.bin",
                    status = PlannedFileStatus.UNSUPPORTED,
                    origin = PlacementOrigin.GAME_RULE,
                    reason = "No safe destination",
                ),
            ),
        )
        val diff = ModReconfigurationDiff(
            listOf(
                ModPlanChange(ModPlanChangeType.ADDED, "Data/new.txt", newTarget = "GAME_DIR:data/new.txt"),
                ModPlanChange(ModPlanChangeType.ADDED, "Data/existing.txt", newTarget = "GAME_DIR:data/existing.txt"),
                ModPlanChange(ModPlanChangeType.MOVED, "Data/moved.txt", "GAME_DIR:old.txt", "GAME_DIR:data/moved.txt"),
                ModPlanChange(ModPlanChangeType.STALE, "Data/removed.txt", "GAME_DIR:data/removed.txt"),
            ),
        )

        val categories = placementReviewRows(
            plan,
            diff,
            listOf(root),
            staleReason = "No longer produced by this placement",
        ).groupingBy { it.category }.eachCount()

        assertEquals(1, categories[PlacementReviewCategory.ADDED])
        assertEquals(1, categories[PlacementReviewCategory.REPLACED])
        assertEquals(1, categories[PlacementReviewCategory.MOVED])
        assertEquals(1, categories[PlacementReviewCategory.REMOVED])
        assertEquals(1, categories[PlacementReviewCategory.IGNORED])
        assertEquals(1, categories[PlacementReviewCategory.BLOCKED])
    }

    private fun planned(
        relative: String,
        mode: ModPlacementMode = ModPlacementMode.COPY,
    ) = PlannedModFile(
        sourceRelativePath = relative,
        targetRoot = ModTargetRoot.GAME_DIR.name,
        targetRelativePath = relative,
        normalizedTargetKey = WindowsPathIdentity.targetKey(ModTargetRoot.GAME_DIR.name, relative),
        status = PlannedFileStatus.PLACED,
        origin = PlacementOrigin.GAME_RULE,
        mode = mode.name,
        sizeBytes = 1,
        reason = "Test",
    )
}
