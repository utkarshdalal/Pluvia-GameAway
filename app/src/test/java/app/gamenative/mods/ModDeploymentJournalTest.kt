package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModDeploymentJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun interruptedCheckpoints_reconcileToSafeExplicitStates() {
        val expectations = mapOf(
            ModDeploymentCheckpoint.PLANNED to ModDeploymentCheckpoint.ROLLED_BACK,
            ModDeploymentCheckpoint.PREPARING to ModDeploymentCheckpoint.ROLLED_BACK,
            ModDeploymentCheckpoint.APPLYING to ModDeploymentCheckpoint.RECOVERY_REQUIRED,
            ModDeploymentCheckpoint.VERIFYING to ModDeploymentCheckpoint.RECOVERY_REQUIRED,
            ModDeploymentCheckpoint.ROLLING_BACK to ModDeploymentCheckpoint.RECOVERY_REQUIRED,
            ModDeploymentCheckpoint.ROLLED_BACK to ModDeploymentCheckpoint.ROLLED_BACK,
            ModDeploymentCheckpoint.RECOVERY_REQUIRED to ModDeploymentCheckpoint.RECOVERY_REQUIRED,
        )
        expectations.forEach { (interruptedAt, expected) ->
            val root = temporaryFolder.newFolder(interruptedAt.name)
            val initial = ModDeploymentJournalStore.begin(root, "install", "game", emptyPlan())
            if (interruptedAt != ModDeploymentCheckpoint.PLANNED) {
                ModDeploymentJournalStore.checkpoint(root, initial, interruptedAt)
            }

            assertEquals(expected, ModDeploymentJournalStore.reconcile(root).single().checkpoint)
        }
    }

    @Test
    fun verifyingCheckpoint_commitsOnlyWhenOwnershipAndFilesMatch() {
        val root = temporaryFolder.newFolder("verified")
        val source = temporaryFolder.newFile("source").apply { writeText("expected") }
        val target = temporaryFolder.newFile("target").apply { writeText("expected") }
        val plan = ModMaterializationPlan(
            installId = "install",
            operations = emptyList(),
            files = listOf(
                ModPlannedFile(
                    installId = "install",
                    source = source,
                    target = target,
                    mode = ModPlacementMode.COPY,
                    targetRoot = "GAME_DIR",
                    sourceRelativePath = source.name,
                    targetRelativePath = target.name,
                ),
            ),
        )
        val journal = ModDeploymentJournalStore.begin(root, "install", "game", plan)
        ModDeploymentJournalStore.checkpoint(root, journal, ModDeploymentCheckpoint.VERIFYING)
        ModOwnershipStore.write(
            root,
            ModOwnershipManifest(
                installId = "install",
                appId = "game",
                planDigest = plan.digest,
                files = listOf(
                    ModOwnedFile(
                        sourceRelativePath = source.name,
                        targetRoot = "GAME_DIR",
                        targetRelativePath = target.name,
                        targetPath = target.absolutePath,
                        normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                        mode = ModPlacementMode.COPY.name,
                        installedHash = ModOwnershipStore.sha256(source),
                        installedSize = target.length(),
                        installedMtime = target.lastModified(),
                        disposition = ModOwnedFileDisposition.CREATED,
                    ),
                ),
            ),
        )

        assertEquals(ModDeploymentCheckpoint.COMMITTED, ModDeploymentJournalStore.reconcile(root).single().checkpoint)
        target.writeText("external change")
        assertTrue(
            ModDeploymentVerifier.verify(ModOwnershipStore.read(root, "install")!!).issues.any {
                it.type ==
                    ModVerificationIssueType.MODIFIED
            },
        )
    }

    @Test
    fun delete_removesCurrentAndTemporaryJournalFiles() {
        val root = temporaryFolder.newFolder("delete")
        ModDeploymentJournalStore.begin(root, "install", "game", emptyPlan())
        val temporary = File(root, "journals/install.json.tmp").apply { writeText("partial") }

        ModDeploymentJournalStore.delete(root, "install")

        assertNull(ModDeploymentJournalStore.read(root, "install"))
        assertFalse(temporary.exists())
    }

    private fun emptyPlan() = ModMaterializationPlan("install", emptyList(), emptyList())
}
