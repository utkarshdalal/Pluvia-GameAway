package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import java.util.Locale

data class PlacementRiskAssessment(
    val risk: PlacementRisk,
    val evidence: String = "",
)

/**
 * One origin-independent policy for destinations that deserve extra review.
 * Individual mods and archive names must never participate in this decision.
 */
object PlacementRiskPolicy {
    private val executableExtensions = setOf(
        "asi", "bat", "cmd", "com", "dll", "exe", "jar", "js", "msi", "ps1", "scr", "vbs",
    )
    private val rootConfigurationExtensions = setOf("cfg", "conf", "ini", "json", "toml", "xml", "yaml", "yml")

    fun assess(targetRoot: String?, targetRelativePath: String?): PlacementRiskAssessment {
        val root = runCatching { ModTargetRoot.valueOf(targetRoot.orEmpty()) }.getOrNull()
        val path = normalizeArchiveDisplayPath(targetRelativePath.orEmpty())
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val atRoot = '/' !in path.trim('/')

        return when {
            root == ModTargetRoot.CUSTOM_ABSOLUTE -> PlacementRiskAssessment(
                PlacementRisk.UNSAFE,
                "Custom absolute destinations require explicit confirmation",
            )
            root == ModTargetRoot.WINE_C -> PlacementRiskAssessment(
                PlacementRisk.UNSAFE,
                "Direct writes to the Wine C: drive require explicit confirmation",
            )
            root == ModTargetRoot.GAME_DIR && atRoot && extension in executableExtensions -> PlacementRiskAssessment(
                PlacementRisk.UNSAFE,
                "Executable or loader content in the game directory requires explicit confirmation",
            )
            root == ModTargetRoot.GAME_DIR && atRoot && extension in rootConfigurationExtensions -> PlacementRiskAssessment(
                PlacementRisk.REVIEW,
                "Game-directory configuration file should be reviewed",
            )
            else -> PlacementRiskAssessment(PlacementRisk.SAFE)
        }
    }

    fun enforce(plan: ModInstallPlan): ModInstallPlan {
        val files = plan.files.map { file ->
            if (file.status != PlannedFileStatus.PLACED) return@map file
            val assessment = assess(file.targetRoot, file.targetRelativePath)
            if (assessment.risk <= file.risk) return@map file
            file.copy(
                risk = assessment.risk,
                evidence = (file.evidence + assessment.evidence).filter(String::isNotBlank).distinct(),
            )
        }
        val needsApproval = files.any { it.risk == PlacementRisk.UNSAFE && !it.riskApproved }
        return plan.copy(
            files = files,
            blockingIssues = when {
                needsApproval && ModInstallPlan.RISKY_ROOT_REVIEW_BLOCKER !in plan.blockingIssues ->
                    plan.blockingIssues + ModInstallPlan.RISKY_ROOT_REVIEW_BLOCKER
                !needsApproval -> plan.blockingIssues.filterNot { it == ModInstallPlan.RISKY_ROOT_REVIEW_BLOCKER }
                else -> plan.blockingIssues
            },
        )
    }
}
