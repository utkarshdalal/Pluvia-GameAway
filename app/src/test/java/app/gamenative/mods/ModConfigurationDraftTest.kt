package app.gamenative.mods

import app.gamenative.data.ModInstall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModConfigurationDraftTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun draftRoundTrip_isScopedToTheExactArchive_andCanBeCleared() {
        val root = temporaryFolder.newFolder("cache")
        val install = install("hash-one")
        val draft = ModConfigurationDraft(
            installId = install.installId,
            archiveIdentity = ModConfigurationDraftStore.archiveIdentity(install),
            placementChoice = "CUSTOM",
            automaticOptions = mapOf("runtime" to "x64"),
            fomodSelections = mapOf("0:0" to listOf("0:0:1")),
            recipes = listOf(ModConfigurationRecipe(sourceSubpath = "Data", targetRelativePath = "Data")),
        )

        ModConfigurationDraftStore.write(root, draft)

        assertEquals(draft.copy(updatedAt = ModConfigurationDraftStore.read(root, install)!!.updatedAt), ModConfigurationDraftStore.read(root, install))
        assertNull(ModConfigurationDraftStore.read(root, install("different-hash")))
        ModConfigurationDraftStore.delete(root, install.installId)
        assertNull(ModConfigurationDraftStore.read(root, install))
    }

    @Test
    fun writeFailure_isBestEffortAndLeavesNoTemporaryFile() {
        val invalidRoot = temporaryFolder.newFile("not-a-directory")
        val draft = ModConfigurationDraft(
            installId = "install",
            archiveIdentity = "archive",
            placementChoice = "AUTOMATIC",
        )

        assertFalse(ModConfigurationDraftStore.write(invalidRoot, draft))
        assertFalse(File(invalidRoot, "configuration/install.json.tmp").exists())
    }

    @Test
    fun preparedFileReplacementFailure_preservesExistingTarget() {
        val target = temporaryFolder.newFile("current.json").apply { writeText("valid") }

        assertTrue(runCatching { replaceWithPreparedFile(File(target.parentFile, "missing.tmp"), target) }.isFailure)
        assertEquals("valid", target.readText())
    }

    private fun install(hash: String) = ModInstall(
        installId = "install",
        appId = "game",
        modName = "Mod",
        fileName = "mod.zip",
        archivePath = File(temporaryFolder.root, "mod.zip").absolutePath,
        extractedPath = File(temporaryFolder.root, "extracted").absolutePath,
        archiveSha256 = hash,
    )
}
