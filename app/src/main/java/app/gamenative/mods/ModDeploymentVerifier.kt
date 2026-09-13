package app.gamenative.mods

import java.io.File
import java.nio.file.Files
import java.util.Locale

enum class ModVerificationIssueType {
    MISSING,
    MODIFIED,
    WRONG_CASE,
    AMBIGUOUS,
    STALE,
    OWNERSHIP,
}

data class ModVerificationIssue(
    val type: ModVerificationIssueType,
    val targetPath: String,
    val detail: String,
    val installId: String = "",
)

data class ModDeploymentVerification(val issues: List<ModVerificationIssue>) {
    val successful: Boolean get() = issues.isEmpty()
}

enum class ModVerificationDepth {
    FULL,
    CHANGED_CONTENT,
}

object ModDeploymentVerifier {
    fun verify(plan: ModMaterializationPlan): ModDeploymentVerification {
        val session = VerificationSession(ModVerificationDepth.FULL)
        return ModDeploymentVerification(
            plan.files.flatMap { file ->
                session.verifyTarget(
                    target = file.target,
                    expectedHash = ModOwnershipStore.sha256(file.source),
                    expectedSize = file.source.length(),
                    expectedMtime = 0L,
                    stale = false,
                    installId = file.installId,
                )
            },
        )
    }

    fun verify(
        manifest: ModOwnershipManifest,
        depth: ModVerificationDepth = ModVerificationDepth.FULL,
    ): ModDeploymentVerification {
        val session = VerificationSession(depth)
        return ModDeploymentVerification(
            manifest.files.flatMap { file ->
                session.verifyTarget(
                    target = File(file.targetPath),
                    expectedHash = file.installedHash,
                    expectedSize = file.installedSize,
                    expectedMtime = file.installedMtime,
                    stale = !file.active,
                    installId = manifest.installId,
                )
            },
        )
    }

    fun verify(
        overlay: ModProfileOverlay,
        depth: ModVerificationDepth = ModVerificationDepth.FULL,
    ): ModDeploymentVerification {
        val session = VerificationSession(depth)
        return ModDeploymentVerification(
            overlay.targets.values.flatMap { target ->
                session.verifyTarget(
                    target = File(target.winner.file.targetPath),
                    expectedHash = target.winner.file.installedHash,
                    expectedSize = target.winner.file.installedSize,
                    expectedMtime = target.winner.file.installedMtime,
                    stale = false,
                    installId = target.winner.installId,
                ) + if (target.hasCaseCollision) {
                    listOf(
                        ModVerificationIssue(
                            ModVerificationIssueType.AMBIGUOUS,
                            target.winner.file.targetPath,
                            "Enabled mods use case-variant target paths",
                            target.winner.installId,
                        ),
                    )
                } else {
                    emptyList()
                }
            },
        )
    }

    fun verifyPresence(manifest: ModOwnershipManifest): ModDeploymentVerification {
        val session = VerificationSession(ModVerificationDepth.CHANGED_CONTENT)
        return ModDeploymentVerification(
            manifest.files
                .asSequence()
                .filter { it.active }
                .flatMap { file -> session.verifyPresence(File(file.targetPath), manifest.installId).asSequence() }
                .toList(),
        )
    }

    fun verifyStale(manifest: ModOwnershipManifest): ModDeploymentVerification {
        val session = VerificationSession(ModVerificationDepth.CHANGED_CONTENT)
        return ModDeploymentVerification(
            manifest.files
                .asSequence()
                .filter { !it.active && it.disposition == ModOwnedFileDisposition.STALE_PRESERVED }
                .flatMap { file -> session.verifyStale(File(file.targetPath), manifest.installId).asSequence() }
                .toList(),
        )
    }

    private class VerificationSession(private val depth: ModVerificationDepth) {
        private val childrenByParent = mutableMapOf<String, Map<String, List<File>>>()

        fun verifyTarget(
            target: File,
            expectedHash: String,
            expectedSize: Long,
            expectedMtime: Long,
            stale: Boolean,
            installId: String,
        ): List<ModVerificationIssue> {
            val resolution = resolveTarget(target, installId)
            resolution.issue?.let { return listOf(it) }
            val actual = resolution.actual
            if (actual == null) {
                return if (stale) {
                    emptyList()
                } else {
                    listOf(issue(ModVerificationIssueType.MISSING, target, "Required planned file is missing", installId))
                }
            }
            if (stale) {
                return listOf(issue(ModVerificationIssueType.STALE, actual, "A preserved stale managed file is still present", installId))
            }
            val issues = mutableListOf<ModVerificationIssue>()
            if (actual.name != target.name) {
                issues += issue(ModVerificationIssueType.WRONG_CASE, actual, "Target exists with unexpected casing", installId)
            }
            if (!contentMatches(actual, expectedHash, expectedSize, expectedMtime)) {
                issues += issue(ModVerificationIssueType.MODIFIED, actual, "Target content differs from the reviewed plan", installId)
            }
            return issues
        }

        fun verifyPresence(target: File, installId: String): List<ModVerificationIssue> {
            val resolution = resolveTarget(target, installId)
            resolution.issue?.let { return listOf(it) }
            return if (resolution.actual == null) {
                listOf(issue(ModVerificationIssueType.MISSING, target, "Required planned file is missing", installId))
            } else {
                emptyList()
            }
        }

        fun verifyStale(target: File, installId: String): List<ModVerificationIssue> {
            val resolution = resolveTarget(target, installId)
            resolution.issue?.let { return listOf(it) }
            return resolution.actual?.let {
                listOf(issue(ModVerificationIssueType.STALE, it, "A preserved stale managed file is still present", installId))
            }.orEmpty()
        }

        private fun contentMatches(actual: File, expectedHash: String, expectedSize: Long, expectedMtime: Long): Boolean {
            if (!actual.isFile || expectedHash.isBlank()) return false
            if (depth == ModVerificationDepth.CHANGED_CONTENT) {
                if (expectedSize >= 0L && actual.length() != expectedSize) return false
                if (expectedMtime > 0L && actual.lastModified() == expectedMtime) return true
            }
            return ModOwnershipStore.sha256(actual) == expectedHash
        }

        private fun resolveTarget(target: File, installId: String): TargetResolution {
            val caseMatches = caseMatches(target)
            if (caseMatches.size > 1) {
                return TargetResolution(
                    issue = issue(ModVerificationIssueType.AMBIGUOUS, target, "Multiple case variants exist", installId),
                )
            }
            if (target.exists() || Files.isSymbolicLink(target.toPath())) return TargetResolution(target)
            return TargetResolution(caseMatches.singleOrNull())
        }

        private fun caseMatches(target: File): List<File> {
            val parent = target.parentFile ?: return emptyList()
            val children = childrenByParent.getOrPut(parent.absolutePath) {
                parent.listFiles()
                    .orEmpty()
                    .groupBy { it.name.lowercase(Locale.ROOT) }
            }
            return children[target.name.lowercase(Locale.ROOT)].orEmpty()
        }
    }

    private data class TargetResolution(
        val actual: File? = null,
        val issue: ModVerificationIssue? = null,
    )

    private fun issue(type: ModVerificationIssueType, target: File, detail: String, installId: String) =
        ModVerificationIssue(type, target.absolutePath, detail, installId)
}
