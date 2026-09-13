package app.gamenative.mods

import app.gamenative.data.ModOverwriteManifest
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ModOwnershipState {
    ACTIVE,
    DISABLED,
    RECOVERY_REQUIRED,
}

@Serializable
enum class ModOwnedFileDisposition {
    CREATED,
    MERGED,
    OVERWROTE,
    BACKED_UP,
    SHARED,
    STALE_PRESERVED,
}

@Serializable
data class ModOwnedFile(
    val sourceRelativePath: String,
    val targetRoot: String,
    val targetRelativePath: String,
    val targetPath: String,
    val normalizedTargetKey: String,
    val mode: String,
    val installedHash: String,
    val installedSize: Long,
    val installedMtime: Long,
    val disposition: ModOwnedFileDisposition,
    val priority: Int = 0,
    val active: Boolean = true,
)

@Serializable
data class ModOwnedOperation(
    val sourcePath: String,
    val targetPath: String,
    val normalizedTargetKey: String,
    val mode: String,
)

@Serializable
data class ModInstallDecision(
    val sourceRelativePath: String,
    val targetRoot: String = "",
    val targetRelativePath: String = "",
    val normalizedTargetKey: String = "",
    val status: String,
    val origin: String,
    val mode: String,
    val priority: Int,
    val reason: String,
    val outcome: String,
    val risk: String = PlacementRisk.SAFE.name,
    val riskApproved: Boolean = false,
    val sizeBytes: Long = 0L,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ModOwnershipManifest(
    val version: Int = 3,
    val installId: String,
    val appId: String,
    val profileId: String = "",
    val planDigest: String,
    val state: ModOwnershipState = ModOwnershipState.ACTIVE,
    val files: List<ModOwnedFile>,
    val operations: List<ModOwnedOperation> = emptyList(),
    val decisions: List<ModInstallDecision> = emptyList(),
    val planProducerId: String = "legacy",
    val planProducerVersion: Int = 1,
    val reviewedPlanDigest: String = "",
    val planWarnings: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

fun ModOwnershipManifest.reviewedPlanOrNull(): ModInstallPlan? {
    val ownedBySourceAndTarget = files.associateBy {
        Triple(it.sourceRelativePath, it.targetRoot, it.targetRelativePath)
    }
    val planned = if (decisions.isNotEmpty()) {
        decisions.mapNotNull { decision ->
            val status = runCatching { PlannedFileStatus.valueOf(decision.status) }.getOrNull() ?: return@mapNotNull null
            val origin = runCatching { PlacementOrigin.valueOf(decision.origin) }.getOrDefault(PlacementOrigin.MANUAL_RECIPE)
            val owned = ownedBySourceAndTarget[
                Triple(decision.sourceRelativePath, decision.targetRoot, decision.targetRelativePath),
            ]
                ?: files.firstOrNull { it.sourceRelativePath == decision.sourceRelativePath }
            PlannedModFile(
                sourceRelativePath = decision.sourceRelativePath,
                targetRoot = decision.targetRoot.takeIf(String::isNotBlank) ?: owned?.targetRoot,
                targetRelativePath = decision.targetRelativePath.takeIf(String::isNotBlank) ?: owned?.targetRelativePath,
                normalizedTargetKey = decision.normalizedTargetKey.takeIf(String::isNotBlank) ?: owned?.normalizedTargetKey,
                status = status,
                origin = origin,
                mode = decision.mode,
                priority = decision.priority,
                sizeBytes = decision.sizeBytes.takeIf { it > 0L } ?: owned?.installedSize ?: 0L,
                reason = decision.reason.ifBlank { "Restored from the applied ownership manifest" },
                evidence = decision.evidence,
                risk = runCatching { PlacementRisk.valueOf(decision.risk) }.getOrDefault(PlacementRisk.SAFE),
                riskApproved = decision.riskApproved,
            )
        }
    } else {
        files.filter { it.active }.map { owned ->
            PlannedModFile(
                sourceRelativePath = owned.sourceRelativePath,
                targetRoot = owned.targetRoot,
                targetRelativePath = owned.targetRelativePath,
                normalizedTargetKey = owned.normalizedTargetKey,
                status = PlannedFileStatus.PLACED,
                origin = PlacementOrigin.MANUAL_RECIPE,
                mode = owned.mode,
                sizeBytes = owned.installedSize,
                reason = "Conservatively adopted from a historical ownership manifest",
            )
        }
    }
    if (planned.none { it.status == PlannedFileStatus.PLACED }) return null
    return PlacementRiskPolicy.enforce(
        ModInstallPlan(
            files = planned,
            warnings = planWarnings,
            producerId = planProducerId,
            producerVersion = planProducerVersion,
        ),
    )
}

data class ModOverlayContribution(
    val installId: String,
    val priority: Int,
    val file: ModOwnedFile,
)

data class ModOverlayTarget(
    val normalizedTargetKey: String,
    val contributors: List<ModOverlayContribution>,
    val winner: ModOverlayContribution,
    val identicalContents: Boolean,
    val hasCaseCollision: Boolean,
)

data class ModProfileOverlay(
    val targets: Map<String, ModOverlayTarget>,
    val conflicts: List<ModOverlayTarget>,
)

data class ModProfileOverlayTransition(
    val current: ModProfileOverlay,
    val desired: ModProfileOverlay,
    val changedWinnerKeys: List<String>,
    val currentVerification: ModDeploymentVerification,
) {
    val requiresRebuild: Boolean get() = changedWinnerKeys.isNotEmpty()
    val safeToRebuild: Boolean get() = currentVerification.successful
}

enum class ModPlanChangeType {
    ADDED,
    CHANGED,
    MOVED,
    STALE,
    UNCHANGED,
}

data class ModPlanChange(
    val type: ModPlanChangeType,
    val sourceRelativePath: String,
    val previousTarget: String = "",
    val newTarget: String = "",
)

data class ModReconfigurationDiff(val changes: List<ModPlanChange>) {
    val added: Int get() = changes.count { it.type == ModPlanChangeType.ADDED }
    val changed: Int get() = changes.count { it.type == ModPlanChangeType.CHANGED }
    val moved: Int get() = changes.count { it.type == ModPlanChangeType.MOVED }
    val stale: Int get() = changes.count { it.type == ModPlanChangeType.STALE }
    val hasChanges: Boolean get() = changes.any { it.type != ModPlanChangeType.UNCHANGED }
}

object ModOwnershipPlanDiffer {
    fun compare(previous: ModOwnershipManifest?, next: ModInstallPlan): ModReconfigurationDiff {
        if (previous == null) {
            return ModReconfigurationDiff(
                next.files.filter { it.status == PlannedFileStatus.PLACED }.map { file ->
                    ModPlanChange(ModPlanChangeType.ADDED, file.sourceRelativePath, newTarget = file.normalizedTargetKey.orEmpty())
                },
            )
        }
        val oldFiles = previous.files.filter { it.active }
        val oldByTarget = oldFiles.associateBy { it.normalizedTargetKey }
        val oldBySource = oldFiles.groupBy { it.sourceRelativePath }
        val newFiles = next.files.filter { it.status == PlannedFileStatus.PLACED }
        val newAbsoluteKeys = newFiles.mapNotNull { planned ->
            oldFiles.firstOrNull {
                it.targetRoot == planned.targetRoot && it.targetRelativePath.equals(planned.targetRelativePath, ignoreCase = true)
            }?.normalizedTargetKey
        }.toSet()
        val changes = newFiles.map { planned ->
            val logicalTarget = planned.normalizedTargetKey.orEmpty()
            val sameSource = oldBySource[planned.sourceRelativePath].orEmpty().singleOrNull()
            val sameTarget = oldByTarget.values.firstOrNull {
                it.targetRoot == planned.targetRoot && it.targetRelativePath.equals(planned.targetRelativePath, ignoreCase = true)
            }
            when {
                sameSource != null && sameTarget == null -> ModPlanChange(
                    ModPlanChangeType.MOVED,
                    planned.sourceRelativePath,
                    sameSource.normalizedTargetKey,
                    logicalTarget,
                )
                sameTarget != null && sameTarget.sourceRelativePath != planned.sourceRelativePath -> ModPlanChange(
                    ModPlanChangeType.CHANGED,
                    planned.sourceRelativePath,
                    sameTarget.normalizedTargetKey,
                    logicalTarget,
                )
                sameTarget != null -> ModPlanChange(
                    ModPlanChangeType.UNCHANGED,
                    planned.sourceRelativePath,
                    sameTarget.normalizedTargetKey,
                    logicalTarget,
                )
                else -> ModPlanChange(ModPlanChangeType.ADDED, planned.sourceRelativePath, newTarget = logicalTarget)
            }
        }.toMutableList()
        oldFiles.filter { old ->
            old.normalizedTargetKey !in newAbsoluteKeys && newFiles.none {
                it.targetRoot == old.targetRoot && it.targetRelativePath.equals(old.targetRelativePath, ignoreCase = true)
            } && newFiles.none { it.sourceRelativePath == old.sourceRelativePath }
        }.forEach { old ->
            changes += ModPlanChange(ModPlanChangeType.STALE, old.sourceRelativePath, old.normalizedTargetKey)
        }
        return ModReconfigurationDiff(changes)
    }
}

object ModProfileOverlayPlanner {
    fun build(
        manifests: List<ModOwnershipManifest>,
        enabledPriorities: Map<String, Int>,
    ): ModProfileOverlay {
        val targets = manifests.asSequence()
            .filter { it.state == ModOwnershipState.ACTIVE && it.installId in enabledPriorities }
            .flatMap { manifest ->
                manifest.files.asSequence()
                    .filter { it.active }
                    .map { file -> ModOverlayContribution(manifest.installId, enabledPriorities.getValue(manifest.installId), file) }
            }
            .groupBy { it.file.normalizedTargetKey }
            .mapValues { (key, contributions) ->
                val ordered = contributions.sortedWith(compareBy<ModOverlayContribution> { it.priority }.thenBy { it.installId })
                val paths = ordered.map { it.file.targetPath.replace('\\', '/') }.distinct()
                ModOverlayTarget(
                    normalizedTargetKey = key,
                    contributors = ordered,
                    winner = ordered.last(),
                    identicalContents = ordered.map { it.file.installedHash }.filter(String::isNotBlank).distinct().size <= 1,
                    hasCaseCollision = paths.map { it.lowercase(Locale.ROOT) }.distinct().size < paths.size,
                )
            }
            .toSortedMap()
        return ModProfileOverlay(targets, targets.values.filter { it.contributors.size > 1 && !it.identicalContents })
    }

    fun transition(
        manifests: List<ModOwnershipManifest>,
        desiredPriorities: Map<String, Int>,
    ): ModProfileOverlayTransition {
        val currentPriorities = manifests
            .filter { it.state == ModOwnershipState.ACTIVE }
            .associate { manifest ->
                manifest.installId to (manifest.files.filter { it.active }.maxOfOrNull { it.priority } ?: 0)
            }
        val current = build(manifests, currentPriorities)
        val desired = build(manifests, desiredPriorities)
        val changedWinnerKeys = (current.targets.keys + desired.targets.keys)
            .filter { key ->
                val before = current.targets[key]?.winner
                val after = desired.targets[key]?.winner
                before?.installId != after?.installId ||
                    before?.file?.installedHash != after?.file?.installedHash ||
                    before?.file?.targetPath != after?.file?.targetPath
            }
            .sorted()
        return ModProfileOverlayTransition(
            current = current,
            desired = desired,
            changedWinnerKeys = changedWinnerKeys,
            currentVerification = if (changedWinnerKeys.isEmpty()) {
                ModDeploymentVerification(emptyList())
            } else {
                ModDeploymentVerifier.verify(
                    // Reordering rebuilds the entire managed overlay, not only the
                    // targets whose winner changes. Verify every current winner so
                    // unrelated user edits cannot be removed during that rebuild.
                    current,
                    ModVerificationDepth.CHANGED_CONTENT,
                )
            },
        )
    }
}

object ModOwnershipStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun read(root: File, installId: String): ModOwnershipManifest? =
        readFile(currentFile(root, installId)) ?: readFile(previousFile(root, installId))

    fun readPrevious(root: File, installId: String): ModOwnershipManifest? = readFile(previousFile(root, installId))

    fun reviewedPlan(root: File, installId: String): ModInstallPlan? = read(root, installId)?.reviewedPlanOrNull()

    fun readAll(root: File): List<ModOwnershipManifest> =
        ownershipDir(root).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json.zst") && !it.name.endsWith(".previous.json.zst") }
            .mapNotNull(::readFile)

    fun write(root: File, manifest: ModOwnershipManifest) {
        val current = currentFile(root, manifest.installId)
        val previous = previousFile(root, manifest.installId)
        val temp = File(current.parentFile, "${current.name}.tmp")
        current.parentFile?.mkdirs()
        val payload = Zstd.compress(json.encodeToString(manifest).toByteArray(Charsets.UTF_8), 3)
        FileOutputStream(temp).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        if (current.isFile) {
            replaceWithPreparedFile(current, previous)
        }
        replaceWithPreparedFile(temp, current)
    }

    fun delete(root: File, installId: String) {
        currentFile(root, installId).delete()
        previousFile(root, installId).delete()
        File(currentFile(root, installId).parentFile, "${currentFile(root, installId).name}.tmp").delete()
    }

    fun create(
        appId: String,
        plan: ModMaterializationPlan,
        overwriteManifests: List<ModOverwriteManifest>,
        profileId: String = "",
        priority: Int = 0,
        preservedStale: List<ModOwnedFile> = emptyList(),
    ): ModOwnershipManifest {
        val overwriteByTarget = overwriteManifests.associateBy { WindowsPathIdentity.absoluteKey(File(it.targetPath)) }
        val files = plan.files.map { planned ->
            val target = planned.target
            val overwrite = overwriteByTarget[planned.normalizedTargetKey]
            val installedHash = when {
                target.isFile -> sha256(target)
                planned.source.isFile -> sha256(planned.source)
                else -> ""
            }
            val disposition = when {
                overwrite?.backupPath?.isNotBlank() == true -> ModOwnedFileDisposition.BACKED_UP
                planned.targetExistedBefore && planned.targetHashBefore == installedHash -> ModOwnedFileDisposition.SHARED
                planned.mode.name == "OVERWRITE_COPY" && planned.targetExistedBefore -> ModOwnedFileDisposition.OVERWROTE
                planned.targetExistedBefore -> ModOwnedFileDisposition.MERGED
                else -> ModOwnedFileDisposition.CREATED
            }
            ModOwnedFile(
                sourceRelativePath = planned.sourceRelativePath,
                targetRoot = planned.targetRoot,
                targetRelativePath = planned.targetRelativePath,
                targetPath = target.absolutePath,
                normalizedTargetKey = planned.normalizedTargetKey,
                mode = planned.mode.name,
                installedHash = installedHash,
                installedSize = if (target.isFile) target.length() else planned.source.length(),
                installedMtime = if (target.exists()) target.lastModified() else planned.source.lastModified(),
                disposition = disposition,
                priority = priority,
            )
        }
        return ModOwnershipManifest(
            installId = plan.installId,
            appId = appId,
            profileId = profileId,
            planDigest = plan.digest,
            files = (files + preservedStale).distinctBy { it.normalizedTargetKey to it.active },
            operations = plan.operations.map { operation ->
                ModOwnedOperation(
                    sourcePath = operation.source.absolutePath,
                    targetPath = operation.target.absolutePath,
                    normalizedTargetKey = operation.normalizedTargetKey,
                    mode = operation.mode.name,
                )
            },
            decisions = plan.reviewedPlan.files.map { decision ->
                val owned = files.firstOrNull { file ->
                    file.sourceRelativePath == decision.sourceRelativePath &&
                        file.targetRoot == decision.targetRoot &&
                        file.targetRelativePath == decision.targetRelativePath
                }
                ModInstallDecision(
                    sourceRelativePath = decision.sourceRelativePath,
                    targetRoot = decision.targetRoot.orEmpty(),
                    targetRelativePath = decision.targetRelativePath.orEmpty(),
                    normalizedTargetKey = decision.normalizedTargetKey.orEmpty(),
                    status = decision.status.name,
                    origin = decision.origin.name,
                    mode = decision.mode,
                    priority = decision.priority,
                    reason = decision.reason,
                    outcome = owned?.disposition?.name ?: when (decision.status) {
                        PlannedFileStatus.INTENTIONALLY_IGNORED -> "INTENTIONALLY_SKIPPED"
                        PlannedFileStatus.UNSUPPORTED -> "BLOCKED_UNSUPPORTED"
                        PlannedFileStatus.MISSING -> "BLOCKED_MISSING"
                        PlannedFileStatus.CONFLICTED -> "BLOCKED_CONFLICT"
                        PlannedFileStatus.PLACED -> "PLANNED"
                    },
                    risk = decision.risk.name,
                    riskApproved = decision.riskApproved,
                    sizeBytes = decision.sizeBytes,
                    evidence = decision.evidence,
                )
            },
            planProducerId = plan.reviewedPlan.producerId,
            planProducerVersion = plan.reviewedPlan.producerVersion,
            reviewedPlanDigest = plan.reviewedPlan.digest,
            planWarnings = plan.reviewedPlan.warnings,
        )
    }

    private fun ownershipDir(root: File): File = File(root, "ownership")

    private fun currentFile(root: File, installId: String): File =
        File(ownershipDir(root), "${safeName(installId)}.json.zst")

    private fun previousFile(root: File, installId: String): File =
        File(ownershipDir(root), "${safeName(installId)}.previous.json.zst")

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readFile(file: File): ModOwnershipManifest? {
        if (!file.isFile) return null
        return runCatching {
            ZstdInputStream(FileInputStream(file)).bufferedReader().use { reader ->
                json.decodeFromString<ModOwnershipManifest>(reader.readText())
            }
        }.getOrNull()
    }

    internal fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class ModOwnershipCleanupResult(
    val removed: Int,
    val restored: Int,
    val preserved: List<ModOwnedFile>,
) {
    val skippedPaths: List<String> get() = preserved.map { it.targetPath }.distinct()
}

object ModOwnershipReconciler {
    suspend fun removeOwnedFiles(
        manifest: ModOwnershipManifest,
        overwriteManifests: List<ModOverwriteManifest>,
        targetKeys: Set<String> = manifest.files.filter { it.active }.mapTo(mutableSetOf()) { it.normalizedTargetKey },
        restoreBackups: Boolean = true,
    ): ModOwnershipCleanupResult {
        val selected = manifest.files.filter { it.active && it.normalizedTargetKey in targetKeys }
        val overwriteByKey = overwriteManifests.associateBy { WindowsPathIdentity.absoluteKey(File(it.targetPath)) }
        val restoreCandidates = if (restoreBackups) selected.mapNotNull { overwriteByKey[it.normalizedTargetKey] } else emptyList()
        val restoreSkipped = ModMaterializer.restoreBackups(restoreCandidates)
            .mapTo(mutableSetOf()) { WindowsPathIdentity.absoluteKey(File(it)) }
        val restoredKeys = restoreCandidates.asSequence()
            .filter { it.backupPath.isNotBlank() && it.normalizedKey() !in restoreSkipped }
            .mapTo(mutableSetOf()) { it.normalizedKey() }
        var removed = 0
        val preserved = mutableListOf<ModOwnedFile>()

        val symlinkOperations = manifest.operations.filter { operation ->
            val operationPath = operation.targetPath + File.separator
            val ownedUnderOperation = manifest.files.filter { owned ->
                owned.mode == "SYMLINK" &&
                    (owned.normalizedTargetKey == operation.normalizedTargetKey || owned.targetPath.startsWith(operationPath))
            }
            operation.mode == "SYMLINK" &&
                ownedUnderOperation.isNotEmpty() &&
                ownedUnderOperation.all { it.normalizedTargetKey in targetKeys }
        }
        symlinkOperations.forEach { operation ->
            val target = File(operation.targetPath)
            val source = File(operation.sourcePath)
            val link = target.toPath()
            val pointsToSource = runCatching {
                val raw = Files.readSymbolicLink(link)
                val resolved = if (raw.isAbsolute) raw else link.parent.resolve(raw)
                resolved.normalize().toFile().canonicalFile == source.canonicalFile
            }.getOrDefault(false)
            if (Files.isSymbolicLink(link) && pointsToSource) {
                Files.deleteIfExists(link)
                removed++
            } else {
                preserved += selected.filter { owned ->
                    owned.mode == "SYMLINK" &&
                        (
                            owned.normalizedTargetKey == operation.normalizedTargetKey ||
                                owned.targetPath.startsWith(operation.targetPath + File.separator)
                            )
                }
            }
        }

        selected.filter { it.mode != "SYMLINK" }.forEach { owned ->
            val overwrite = overwriteByKey[owned.normalizedTargetKey]
            when {
                owned.normalizedTargetKey in restoredKeys -> Unit
                overwrite != null && !restoreBackups -> preserved += owned.preserved()
                overwrite?.backupPath?.isBlank() == true -> Unit
                owned.disposition == ModOwnedFileDisposition.SHARED -> Unit
                owned.normalizedTargetKey in restoreSkipped -> preserved += owned.preserved()
                else -> {
                    val target = File(owned.targetPath)
                    val currentHash = ModOwnershipStore.sha256(target)
                    if (target.isFile && currentHash.isNotBlank() && currentHash == owned.installedHash) {
                        if (target.delete()) removed++ else preserved += owned.preserved()
                    } else if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                        preserved += owned.preserved()
                    }
                }
            }
        }
        return ModOwnershipCleanupResult(removed, restoredKeys.size, preserved.distinctBy { it.normalizedTargetKey })
    }

    private fun ModOwnedFile.preserved(): ModOwnedFile =
        copy(active = false, disposition = ModOwnedFileDisposition.STALE_PRESERVED)

    private fun ModOverwriteManifest.normalizedKey(): String = WindowsPathIdentity.absoluteKey(File(targetPath))
}
