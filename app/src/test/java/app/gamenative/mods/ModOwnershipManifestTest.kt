package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModOwnershipManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sidecarRoundTrip_andOverlayWinner_areDeterministic() {
        val root = temporaryFolder.newFolder("cache")
        val low = manifest("low", "Data/Scripts/X.pex", "one", priority = 10)
        val high = manifest("high", "data/scripts/x.pex", "two", priority = 20)
        ModOwnershipStore.write(root, low)

        assertEquals(low, ModOwnershipStore.read(root, low.installId))
        val overlay = ModProfileOverlayPlanner.build(listOf(high, low), mapOf("high" to 20, "low" to 10))
        assertEquals("high", overlay.targets.values.single().winner.installId)
        assertEquals(1, overlay.conflicts.size)
        assertTrue(overlay.targets.values.single().hasCaseCollision)
    }

    @Test
    fun overlayTransition_comparesDesiredWinnerAgainstTheCurrentDeployedWinner() {
        val target = temporaryFolder.newFile("shared.txt").apply { writeText("low") }
        val low = manifest("low", target, ModOwnershipStore.sha256(target), priority = 20)
        val high = manifest("high", target, "different", priority = 10)

        val transition = ModProfileOverlayPlanner.transition(
            listOf(low, high),
            desiredPriorities = mapOf("low" to 10, "high" to 20),
        )

        assertEquals("low", transition.current.targets.values.single().winner.installId)
        assertEquals("high", transition.desired.targets.values.single().winner.installId)
        assertEquals(1, transition.changedWinnerKeys.size)
        assertTrue(transition.requiresRebuild)
        assertTrue(transition.safeToRebuild)
    }

    @Test
    fun overlayTransition_skipsContentVerificationWhenNoWinnerCanChange() {
        val target = temporaryFolder.newFile("unchanged-order.txt").apply { writeText("current") }
        val manifest = manifest("managed", target, "not-the-current-hash", priority = 10)

        val transition = ModProfileOverlayPlanner.transition(
            listOf(manifest),
            desiredPriorities = mapOf("managed" to 10),
        )

        assertFalse(transition.requiresRebuild)
        assertTrue(transition.currentVerification.successful)
    }

    @Test
    fun overlayTransition_verifiesUnchangedTargetsBeforeRebuildingTheWholeOverlay() {
        val shared = temporaryFolder.newFile("shared-rebuild.txt").apply { writeText("low") }
        val unrelated = temporaryFolder.newFile("unrelated.txt").apply { writeText("owned") }
        val low = manifest("low", shared, ModOwnershipStore.sha256(shared), priority = 20)
        val high = manifest("high", shared, "different", priority = 10)
        val other = manifest("other", unrelated, ModOwnershipStore.sha256(unrelated), priority = 5)
        unrelated.appendText("-user edit")

        val transition = ModProfileOverlayPlanner.transition(
            listOf(low, high, other),
            desiredPriorities = mapOf("low" to 10, "high" to 20, "other" to 5),
        )

        assertTrue(transition.requiresRebuild)
        assertFalse(transition.safeToRebuild)
        assertEquals(listOf(unrelated.absolutePath), transition.currentVerification.issues.map { it.targetPath })
    }

    @Test
    fun changedContentVerification_hashesOnlyWhenRecordedMetadataChanged() {
        val target = temporaryFolder.newFile("metadata.txt").apply { writeText("owned") }
        val manifest = manifest("managed", target, "not-the-current-hash", priority = 10).let { ownership ->
            ownership.copy(
                files = ownership.files.map {
                    it.copy(installedSize = target.length(), installedMtime = target.lastModified())
                },
            )
        }

        assertFalse(ModDeploymentVerifier.verify(manifest).successful)
        assertTrue(ModDeploymentVerifier.verify(manifest, ModVerificationDepth.CHANGED_CONTENT).successful)

        target.appendText("-changed")

        assertFalse(ModDeploymentVerifier.verify(manifest, ModVerificationDepth.CHANGED_CONTENT).successful)
    }

    @Test
    fun presenceVerification_reportsMissingManagedTargetsWithoutMaterializingTheArchive() {
        val missing = File(temporaryFolder.root, "missing.txt")
        val manifest = manifest("managed", missing, "hash", priority = 10)

        assertEquals(
            listOf(ModVerificationIssueType.MISSING),
            ModDeploymentVerifier.verifyPresence(manifest).issues.map { it.type },
        )
    }

    @Test
    fun staleVerification_checksOnlyFilesExplicitlyPreservedDuringDisable() {
        val restoredTarget = temporaryFolder.newFile("restored.txt").apply { writeText("original") }
        val preservedTarget = temporaryFolder.newFile("preserved.txt").apply { writeText("changed") }
        val disabled = manifest("disabled", restoredTarget, "owned", priority = 1).copy(
            state = ModOwnershipState.DISABLED,
            files = listOf(
                manifest("disabled", restoredTarget, "owned", priority = 1).files.single().copy(active = false),
                manifest("disabled", preservedTarget, "owned", priority = 1).files.single().copy(
                    active = false,
                    disposition = ModOwnedFileDisposition.STALE_PRESERVED,
                ),
            ),
        )

        val findings = ModDeploymentVerifier.verifyStale(disabled).issues

        assertEquals(listOf(preservedTarget.absolutePath), findings.map { it.targetPath })
    }

    @Test
    fun staleCleanup_removesOnlyUnchangedOwnedFiles() = runBlocking {
        val targetRoot = temporaryFolder.newFolder("game")
        val unchanged = File(targetRoot, "unchanged.txt").apply { writeText("owned") }
        val modified = File(targetRoot, "modified.txt").apply { writeText("changed") }
        val ownedHash = ModOwnershipStore.sha256(temporaryFolder.newFile("source.txt").apply { writeText("owned") })
        val manifest = ModOwnershipManifest(
            installId = "install",
            appId = "game",
            planDigest = "digest",
            files = listOf(unchanged, modified).map { target ->
                ModOwnedFile(
                    sourceRelativePath = target.name,
                    targetRoot = "GAME_DIR",
                    targetRelativePath = target.name,
                    targetPath = target.absolutePath,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                    mode = ModPlacementMode.COPY.name,
                    installedHash = ownedHash,
                    installedSize = 5,
                    installedMtime = 1,
                    disposition = ModOwnedFileDisposition.CREATED,
                )
            },
        )

        val result = ModOwnershipReconciler.removeOwnedFiles(manifest, emptyList())

        assertFalse(unchanged.exists())
        assertTrue(modified.exists())
        assertEquals(listOf(modified.absolutePath), result.skippedPaths)
    }

    @Test
    fun reconfigurationDiff_reportsAddedMovedAndStaleOutputs() {
        val previous = ModOwnershipManifest(
            installId = "install",
            appId = "game",
            planDigest = "old",
            files = listOf(
                owned("A.txt", "Data/A.txt"),
                owned("B.txt", "Data/B.txt"),
            ),
        )
        val next = ModInstallPlan(
            files = listOf(
                planned("A.txt", "Data/Moved/A.txt"),
                planned("C.txt", "Data/C.txt"),
            ),
            producerId = "fixture",
        )

        val diff = ModOwnershipPlanDiffer.compare(previous, next)

        assertEquals(1, diff.added)
        assertEquals(1, diff.moved)
        assertEquals(1, diff.stale)
    }

    @Test
    fun ownershipDecisions_restoreTheReviewedPlan_andKeepOneUndoGeneration() {
        val root = temporaryFolder.newFolder("history")
        val first = manifest("install", "Data/First.txt", "one", priority = 1).copy(
            decisions = listOf(
                ModInstallDecision(
                    sourceRelativePath = "First.txt",
                    targetRoot = "GAME_DIR",
                    targetRelativePath = "Data/First.txt",
                    normalizedTargetKey = "game_dir:data/first.txt",
                    status = PlannedFileStatus.PLACED.name,
                    origin = PlacementOrigin.FOMOD_REQUIRED.name,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    priority = 4,
                    reason = "Required installer file",
                    outcome = "CREATED",
                    sizeBytes = 3,
                    evidence = listOf("requiredFiles"),
                ),
            ),
            planProducerId = "fomod",
            planProducerVersion = 2,
        )
        val second = first.copy(planDigest = "second", files = first.files.map { it.copy(installedHash = "two") })
        ModOwnershipStore.write(root, first)
        ModOwnershipStore.write(root, second)

        val restored = ModOwnershipStore.reviewedPlan(root, "install")

        assertEquals("fomod", restored?.producerId)
        assertEquals("Data/First.txt", restored?.files?.single()?.targetRelativePath)
        assertEquals(listOf("requiredFiles"), restored?.files?.single()?.evidence)
        assertEquals(first, ModOwnershipStore.readPrevious(root, "install"))
    }

    @Test
    fun reviewedPlan_matchesRepeatedSourceByLogicalDestination() {
        val manifest = ModOwnershipManifest(
            installId = "install",
            appId = "game",
            planDigest = "digest",
            files = listOf(
                owned("Shared.bin", "Data/First.bin").copy(installedSize = 11),
                owned("Shared.bin", "Data/Second.bin").copy(installedSize = 22),
            ),
            decisions = listOf("Data/Second.bin", "Data/First.bin").map { target ->
                ModInstallDecision(
                    sourceRelativePath = "Shared.bin",
                    targetRoot = "GAME_DIR",
                    targetRelativePath = target,
                    normalizedTargetKey = WindowsPathIdentity.targetKey("GAME_DIR", target).orEmpty(),
                    status = PlannedFileStatus.PLACED.name,
                    origin = PlacementOrigin.FOMOD_REQUIRED.name,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    priority = 0,
                    reason = "fixture",
                    outcome = "CREATED",
                )
            },
        )

        val restored = manifest.reviewedPlanOrNull()!!

        assertEquals(listOf(22L, 11L), restored.files.map { it.sizeBytes })
    }

    private fun owned(source: String, target: String): ModOwnedFile = ModOwnedFile(
        sourceRelativePath = source,
        targetRoot = "GAME_DIR",
        targetRelativePath = target,
        targetPath = "C:/Game/$target",
        normalizedTargetKey = WindowsPathIdentity.absoluteKey(File("C:/Game/$target")),
        mode = ModPlacementMode.OVERWRITE_COPY.name,
        installedHash = source,
        installedSize = 1,
        installedMtime = 1,
        disposition = ModOwnedFileDisposition.CREATED,
    )

    private fun planned(source: String, target: String): PlannedModFile = PlannedModFile(
        sourceRelativePath = source,
        targetRoot = "GAME_DIR",
        targetRelativePath = target,
        normalizedTargetKey = WindowsPathIdentity.targetKey("GAME_DIR", target),
        status = PlannedFileStatus.PLACED,
        origin = PlacementOrigin.FOMOD_REQUIRED,
        reason = "fixture",
    )

    private fun manifest(installId: String, targetPath: String, hash: String, priority: Int): ModOwnershipManifest {
        val target = File("C:/Game/$targetPath")
        return manifest(installId, target, hash, priority)
    }

    private fun manifest(installId: String, target: File, hash: String, priority: Int): ModOwnershipManifest {
        return ModOwnershipManifest(
            installId = installId,
            appId = "game",
            planDigest = "digest-$installId",
            files = listOf(
                ModOwnedFile(
                    sourceRelativePath = "x.pex",
                    targetRoot = "GAME_DIR",
                    targetRelativePath = target.name,
                    targetPath = target.path,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    installedHash = hash,
                    installedSize = target.takeIf(File::isFile)?.length() ?: 1,
                    installedMtime = target.takeIf(File::isFile)?.lastModified() ?: 1,
                    disposition = ModOwnedFileDisposition.OVERWROTE,
                    priority = priority,
                ),
            ),
        )
    }
}
