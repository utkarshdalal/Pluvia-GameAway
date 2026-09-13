package app.gamenative.ui.component.dialog

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.mods.NexusCollectionFile
import app.gamenative.mods.ModDeploymentVerification
import app.gamenative.mods.ModProfileOverlay
import app.gamenative.mods.ModProfileOverlayTransition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusModsDialogHelpersTest {
    @Test
    fun callbackWait_callbackWinsWhenCancellationIsAlsoReady() = runTest {
        val callback = CompletableDeferred("authorized")

        assertEquals(
            CallbackWaitResult.Received("authorized"),
            awaitCallbackOrCancellation(callback, MutableStateFlow(true), timeoutMillis = 1_000L),
        )
    }

    @Test
    fun callbackWait_returnsCancellation() = runTest {
        assertEquals(
            CallbackWaitResult.Cancelled,
            awaitCallbackOrCancellation(CompletableDeferred<String>(), MutableStateFlow(true), 1_000L),
        )
    }

    @Test
    fun callbackWait_returnsNullOnTimeout() = runTest {
        assertNull(
            awaitCallbackOrCancellation(CompletableDeferred<String>(), MutableStateFlow(false), 1_000L),
        )
    }

    @Test
    fun callbackWait_propagatesParentCancellation() = runTest {
        val outcome = CompletableDeferred<Throwable?>()
        val waiter = launch {
            outcome.complete(
                runCatching {
                    awaitCallbackOrCancellation(CompletableDeferred<String>(), MutableStateFlow(false), 60_000L)
                }.exceptionOrNull(),
            )
        }

        yield()
        waiter.cancel()

        assertTrue(outcome.await() is CancellationException)
    }

    @Test
    fun profileStatus_preservesDisabledInstallStatus() {
        val install = install(status = ModInstallStatus.DISABLED)

        assertEquals(ModInstallStatus.DISABLED.name, install.profileStatus(enabledInProfile = false))
    }

    @Test
    fun profileStatus_marksReadyInstallDisabledInProfile() {
        val install = install(status = ModInstallStatus.READY)

        assertEquals("PROFILE_DISABLED", install.profileStatus(enabledInProfile = false))
    }

    @Test
    fun recipeRoundTrip_preservesFomodDestinationFileName() {
        val recipe = ModPlacementRecipe(
            installId = "mcm-helper",
            sourceSubpath = "Data/MCM/Config/SkyUI_SE/config.json",
            targetRelativePath = "Data/MCM/Config/SkyUI_SE",
            targetFileName = "config.json",
        )

        assertEquals(recipe.targetFileName, recipe.toDraft().targetFileName)
        assertEquals(recipe.targetFileName, recipe.toDraft().toRecipe(recipe.installId).targetFileName)
    }

    @Test
    fun unresolvedSources_requireAnExplicitDestinationWithoutDroppingExistingMappings() {
        val current = listOf(RecipeDraft(sourceSubpath = "Data", targetRelativePath = "Data"))

        val drafts = draftsWithUnresolvedSources(current, listOf("Docs/readme.txt", "Docs/readme.txt"), RecipeDraft())

        assertEquals(2, drafts.size)
        assertEquals(current.single(), drafts.first())
        assertEquals("Docs/readme.txt", drafts.last().sourceSubpath)
        assertEquals("", drafts.last().targetRoot)
    }

    @Test
    fun placementErrors_showTheUsefulPathTail() {
        assertEquals(
            "Mods/CharacterEditor/v1.6/CharacterEditor.dll",
            compactPlacementErrorPath("/data/user/0/app/game/Mods/CharacterEditor/v1.6/CharacterEditor.dll"),
        )
    }

    @Test
    fun collectionEmbeddedMetadata_avoidsSeparateModInfoLookup() {
        val collectionFile = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 123L,
            fileId = 456L,
            modName = "Cloaks",
            fileName = "Cloaks-456.7z",
            version = "1.2.1",
            sizeBytes = 42L,
        )

        assertEquals("Cloaks", collectionFile.toEmbeddedNexusModInfo()?.name)
        assertEquals("Cloaks-456.7z", collectionFile.toFallbackNexusFile()?.fileName)
    }

    @Test
    fun applyOrder_doesNotRebuildOrReapplyManagedConflictsWhenOrderIsAlreadyCurrent() {
        val overlay = ModProfileOverlay(emptyMap(), emptyList())
        val unchanged = ModProfileOverlayTransition(
            current = overlay,
            desired = overlay,
            changedWinnerKeys = emptyList(),
            currentVerification = ModDeploymentVerification(emptyList()),
        )
        val applied = install(status = ModInstallStatus.APPLIED)

        assertFalse(requiresManagedOverlayRebuild(setOf(applied.installId), setOf(applied.installId), unchanged))
        assertFalse(
            shouldApplyProfileInstall(
                install = applied,
                hasActiveOwnership = true,
                hasConflict = true,
                needsAssetRepair = false,
                hasMissingTarget = false,
            ),
        )
    }

    @Test
    fun applyOrder_rebuildsManagedOverlayOnlyWhenAValidatedWinnerChanges() {
        val overlay = ModProfileOverlay(emptyMap(), emptyList())
        val changed = ModProfileOverlayTransition(
            current = overlay,
            desired = overlay,
            changedWinnerKeys = listOf("data/shared.txt"),
            currentVerification = ModDeploymentVerification(emptyList()),
        )

        assertTrue(requiresManagedOverlayRebuild(setOf("low", "high"), setOf("low", "high"), changed))
    }

    private fun install(status: ModInstallStatus): ModInstall =
        ModInstall(
            installId = "install",
            appId = "app",
            nexusGameDomain = "skyrimspecialedition",
            nexusModId = 1L,
            nexusFileId = 2L,
            modName = "Mod",
            fileName = "file.zip",
            archivePath = "",
            extractedPath = "",
            status = status.name,
        )
}
