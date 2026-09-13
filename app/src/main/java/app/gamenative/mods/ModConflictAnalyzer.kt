package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

data class ModConflictParticipant(
    val installId: String,
    val modName: String,
    val sourcePath: String,
    val priority: Int,
    val wins: Boolean,
)

data class ModFileConflictReport(
    val targetPath: String,
    val targetRelativePath: String,
    val winnerInstallId: String,
    val participants: List<ModConflictParticipant>,
)

object ModConflictAnalyzer {
    suspend fun analyze(
        installs: List<ModInstall>,
        recipesByInstallId: Map<String, List<ModPlacementRecipe>>,
        prioritiesByInstallId: Map<String, Int>,
        gameRootDir: File?,
        winePrefix: String,
        ownershipByInstallId: Map<String, ModOwnershipManifest> = emptyMap(),
    ): List<ModFileConflictReport> = withContext(Dispatchers.IO) {
        val installById = installs.associateBy { it.installId }
        val plannedFiles = installs.flatMap { install ->
            val recipes = recipesByInstallId[install.installId].orEmpty()
            val ownership = ownershipByInstallId[install.installId]
                ?.takeIf { install.status == ModInstallStatus.APPLIED.name && it.state == ModOwnershipState.ACTIVE }
            if (ownership != null) {
                return@flatMap ownership.files
                    .filter { it.active }
                    .map { file ->
                        PlannedFile(
                            installId = install.installId,
                            source = File(install.extractedPath, file.sourceRelativePath),
                            target = File(file.targetPath),
                        )
                    }
            }
            runCatching {
                val plan = ModMaterializer.materializationPlan(
                    install,
                    recipes,
                    gameRootDir,
                    winePrefix,
                    captureTargetHashes = false,
                )
                check(plan.isComplete) { plan.errors.values.joinToString() }
                plan.files.map { file ->
                    PlannedFile(file.installId, file.source, file.target)
                }
            }.getOrElse { error ->
                Timber.w(error, "Skipping Nexus conflict analysis for install %s", install.installId)
                emptyList()
            }
        }

        plannedFiles
            .groupBy { WindowsPathIdentity.absoluteKey(it.target) }
            .filterValues { it.map { file -> file.installId }.distinct().size > 1 }
            .map { (_, files) ->
                val sorted = files.sortedWith(
                    compareByDescending<PlannedFile> { prioritiesByInstallId[it.installId] ?: 0 }
                        .thenByDescending { installById[it.installId]?.updatedAt ?: 0L }
                        .thenByDescending { installById[it.installId]?.createdAt ?: 0L },
                )
                val winner = sorted.first()
                ModFileConflictReport(
                    targetPath = winner.target.absolutePath,
                    targetRelativePath = relativeTargetPath(winner.target.absolutePath, gameRootDir, winePrefix),
                    winnerInstallId = winner.installId,
                    participants = sorted.map { file ->
                        val install = installById[file.installId]
                        ModConflictParticipant(
                            installId = file.installId,
                            modName = install?.modName ?: file.installId,
                            sourcePath = file.source.absolutePath,
                            priority = prioritiesByInstallId[file.installId] ?: 0,
                            wins = file.installId == winner.installId,
                        )
                    },
                )
            }
            .sortedWith(compareBy<ModFileConflictReport> { it.targetRelativePath.lowercase() }.thenBy { it.targetPath })
    }

    private data class PlannedFile(
        val installId: String,
        val source: File,
        val target: File,
    )

    private fun relativeTargetPath(path: String, gameRootDir: File?, winePrefix: String): String {
        val target = File(path)
        val roots = ModTargetResolver.roots(gameRootDir, winePrefix)
            .sortedByDescending { it.dir.absolutePath.length }
        val root = roots.firstOrNull { target.isInsideOrEqual(it.dir) } ?: return path
        val relative = runCatching {
            target.canonicalFile.relativeToOrNull(root.dir.canonicalFile)?.path.orEmpty()
        }.getOrDefault("")
        return if (relative.isBlank()) root.label else "${root.label}/${relative.replace(File.separatorChar, '/')}"
    }

    private fun File.isInsideOrEqual(root: File): Boolean =
        runCatching {
            val rootCanonical = root.canonicalFile
            val fileCanonical = canonicalFile
            fileCanonical == rootCanonical || fileCanonical.path.startsWith(rootCanonical.path + File.separator)
        }.getOrDefault(false)
}
