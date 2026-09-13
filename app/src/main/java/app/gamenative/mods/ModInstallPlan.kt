package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import java.security.MessageDigest
import java.util.Locale

enum class PlannedFileStatus {
    PLACED,
    INTENTIONALLY_IGNORED,
    UNSUPPORTED,
    MISSING,
    CONFLICTED,
}

enum class PlacementOrigin {
    MANUAL_RECIPE,
    GAME_RULE,
    LEGACY_PRESET,
    FOMOD_REQUIRED,
    FOMOD_OPTION,
    FOMOD_CONDITIONAL,
}

enum class PlacementRisk {
    SAFE,
    REVIEW,
    UNSAFE,
}

data class PlannedModFile(
    val sourceRelativePath: String,
    val targetRoot: String? = null,
    val targetRelativePath: String? = null,
    val normalizedTargetKey: String? = null,
    val status: PlannedFileStatus,
    val origin: PlacementOrigin,
    val priority: Int = 0,
    val mode: String = ModPlacementMode.OVERWRITE_COPY.name,
    val sizeBytes: Long = 0L,
    val reason: String,
    val evidence: List<String> = emptyList(),
    val risk: PlacementRisk = PlacementRisk.SAFE,
    val riskApproved: Boolean = false,
)

data class ModInstallPlan(
    val files: List<PlannedModFile>,
    val warnings: List<String> = emptyList(),
    val blockingIssues: List<String> = emptyList(),
    val producerId: String = "unknown",
    val producerVersion: Int = 1,
) {
    val selectedCount: Int
        get() = files.count { it.status != PlannedFileStatus.INTENTIONALLY_IGNORED }

    val placedCount: Int
        get() = files.count { it.status == PlannedFileStatus.PLACED }

    val ignoredCount: Int
        get() = files.count { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED }

    val unresolvedCount: Int
        get() = files.count {
            it.status == PlannedFileStatus.UNSUPPORTED ||
                it.status == PlannedFileStatus.MISSING ||
                it.status == PlannedFileStatus.CONFLICTED
        }

    val installableBytes: Long
        get() = files.filter { it.status != PlannedFileStatus.INTENTIONALLY_IGNORED }.sumOf { it.sizeBytes }

    val placedBytes: Long
        get() = files.filter { it.status == PlannedFileStatus.PLACED }.sumOf { it.sizeBytes }

    val coverage: Double
        get() = if (installableBytes > 0L) {
            placedBytes.toDouble() / installableBytes.toDouble()
        } else if (selectedCount == 0) {
            1.0
        } else {
            placedCount.toDouble() / selectedCount.toDouble()
        }

    val isComplete: Boolean
        get() = blockingIssues.isEmpty() &&
            unresolvedCount == 0 &&
            files.none { it.risk == PlacementRisk.UNSAFE && !it.riskApproved }

    val digest: String
        get() {
            val canonical = files
                .sortedWith(
                    compareBy<PlannedModFile> { it.normalizedTargetKey.orEmpty() }
                        .thenBy { it.sourceRelativePath.lowercase(Locale.ROOT) }
                        .thenByDescending { it.priority },
                )
                .joinToString("\n") { file ->
                    listOf(
                        file.sourceRelativePath,
                        file.targetRoot.orEmpty(),
                        file.targetRelativePath.orEmpty(),
                        file.normalizedTargetKey.orEmpty(),
                        file.status.name,
                        file.origin.name,
                        file.priority.toString(),
                        file.mode,
                        file.sizeBytes.toString(),
                        file.risk.name,
                        file.riskApproved.toString(),
                    ).joinToString("|")
                }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

    fun sanitizedManifest(): String = buildString {
        appendLine("plan-version: 1")
        appendLine("producer: ${ModDiagnosticSanitizer.text(producerId)}@$producerVersion")
        appendLine("digest: $digest")
        appendLine("complete: $isComplete")
        appendLine("placed: $placedCount/$selectedCount")
        files.sortedBy { it.sourceRelativePath.lowercase(Locale.ROOT) }.forEach { file ->
            append(file.status.name)
            append(' ')
            append(ModDiagnosticSanitizer.relativePath(file.sourceRelativePath))
            if (file.targetRoot != null && file.targetRelativePath != null) {
                append(" -> ")
                append(file.targetRoot)
                append('/')
                append(
                    if (file.targetRoot == "CUSTOM_ABSOLUTE") {
                        "<custom-path>"
                    } else {
                        ModDiagnosticSanitizer.relativePath(file.targetRelativePath)
                    },
                )
            }
            append(" [")
            append(ModDiagnosticSanitizer.text(file.reason))
            if (file.riskApproved) append("; high-risk target explicitly approved")
            appendLine(']')
        }
        warnings.sorted().forEach { appendLine("warning: ${ModDiagnosticSanitizer.text(it)}") }
        blockingIssues.sorted().forEach { appendLine("blocker: ${ModDiagnosticSanitizer.text(it)}") }
    }

    fun withRiskApproval(approved: Boolean): ModInstallPlan = PlacementRiskPolicy.enforce(this).let { plan ->
        plan.copy(
        files = plan.files.map { file ->
            if (file.risk == PlacementRisk.UNSAFE) file.copy(riskApproved = approved) else file
        },
        blockingIssues = when {
            approved -> plan.blockingIssues.filterNot { it == RISKY_ROOT_REVIEW_BLOCKER }
            plan.files.none { it.risk == PlacementRisk.UNSAFE } -> plan.blockingIssues
            RISKY_ROOT_REVIEW_BLOCKER in plan.blockingIssues -> plan.blockingIssues
            else -> plan.blockingIssues + RISKY_ROOT_REVIEW_BLOCKER
        },
        )
    }

    fun withRiskyRootApproval(approved: Boolean): ModInstallPlan = withRiskApproval(approved)

    companion object {
        const val RISKY_ROOT_REVIEW_BLOCKER = "Risky game-root installer content requires review"
    }
}

data class PlacementPlanQuality(
    val placedFiles: Int,
    val placedBytes: Long,
    val unresolvedFiles: Int,
    val blockers: Int,
    val highestRisk: PlacementRisk,
)

object PlacementPlanRegressionPolicy {
    fun quality(plan: ModInstallPlan): PlacementPlanQuality = PlacementPlanQuality(
        placedFiles = plan.placedCount,
        placedBytes = plan.placedBytes,
        unresolvedFiles = plan.unresolvedCount,
        blockers = plan.blockingIssues.size,
        highestRisk = plan.files.maxOfOrNull { it.risk } ?: PlacementRisk.SAFE,
    )

    fun canReplace(baseline: ModInstallPlan, candidate: ModInstallPlan): Boolean {
        val old = quality(baseline)
        val new = quality(candidate)
        return new.placedFiles >= old.placedFiles &&
            new.placedBytes >= old.placedBytes &&
            new.unresolvedFiles <= old.unresolvedFiles &&
            new.blockers <= old.blockers &&
            new.highestRisk <= old.highestRisk
    }
}
