package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import app.gamenative.data.ModPlacementMode
import java.io.File
import java.util.Locale

data class FomodExpectedMapping(
    val mapping: FomodFileMapping,
    val origin: PlacementOrigin,
    val ordinal: Int,
)

data class FomodSelectionEvaluation(
    val mappings: List<FomodExpectedMapping>,
    val flags: Map<String, String>,
    val warnings: List<String>,
    val blockingIssues: List<String>,
)

object FomodSelectionEvaluator {
    fun evaluate(
        installer: FomodInstaller,
        selectedPluginKeys: Set<String>,
        environment: FomodEnvironmentSnapshot = FomodEnvironmentSnapshot(),
    ): FomodSelectionEvaluation {
        val selectedPlugins = FomodRecipeGenerator.selectedPluginsForKeys(installer, selectedPluginKeys, environment)
        val flags = linkedMapOf<String, String>()
        selectedPlugins.forEach { plugin -> plugin.conditionFlags.forEach { (name, value) -> flags[name] = value } }
        val conditionalStates = installer.conditionalFileInstalls.map { conditional ->
            conditional to conditional.dependencies.evaluate(flags, environment)
        }
        var ordinal = 0
        val expected = buildList {
            installer.requiredFiles.forEach { mapping ->
                add(FomodExpectedMapping(mapping, PlacementOrigin.FOMOD_REQUIRED, ordinal++))
            }
            selectedPlugins.forEach { plugin ->
                plugin.files.forEach { mapping ->
                    add(FomodExpectedMapping(mapping, PlacementOrigin.FOMOD_OPTION, ordinal++))
                }
            }
            conditionalStates.forEach { (conditional, state) ->
                if (state == FomodFactState.TRUE) {
                    conditional.files.forEach { mapping ->
                        add(FomodExpectedMapping(mapping, PlacementOrigin.FOMOD_CONDITIONAL, ordinal++))
                    }
                }
            }
        }
        val moduleDependencyState = installer.moduleDependencies.evaluate(flags, environment)
        val warnings = buildList {
            if (moduleDependencyState == FomodFactState.UNKNOWN && installer.moduleDependencies.hasFacts()) {
                add("FOMOD game requirements could not be verified; the selected package version will be used")
            }
            if (
                installer.steps.flatMap { it.groups }.flatMap { it.plugins }.flatMap { it.typePatterns }
                    .any { it.dependencies.evaluate(flags, environment) == FomodFactState.UNKNOWN }
            ) {
                add("FOMOD option availability could not be verified; your explicit choices will be used")
            }
        }
        val blockers = buildList {
            addAll(installer.unsupportedWarnings)
            when (moduleDependencyState) {
                FomodFactState.FALSE -> add("The installed game does not satisfy this FOMOD's requirements")
                FomodFactState.UNKNOWN -> Unit
                FomodFactState.TRUE -> Unit
            }
            if (conditionalStates.any { (_, state) -> state == FomodFactState.UNKNOWN }) {
                add("A selected FOMOD conditional depends on unknown game facts")
            }
            if (installer.conditionalFileInstalls.any { it.dependencies.unsupportedCount() > 0 }) {
                add("A selected FOMOD conditional uses unsupported dependencies")
            }
            if (
                installer.steps.flatMap { it.groups }.flatMap { it.plugins }.flatMap { it.typePatterns }
                    .any { it.dependencies.unsupportedCount() > 0 }
            ) {
                add("FOMOD option availability depends on unsupported game facts")
            }
        }
        return FomodSelectionEvaluation(expected, flags, warnings.distinct(), blockers.distinct())
    }

    private fun FomodDependencyExpression.hasFacts(): Boolean =
        flagDependencies.isNotEmpty() ||
            fileDependencies.isNotEmpty() ||
            pluginDependencies.isNotEmpty() ||
            gameDependencies.isNotEmpty() ||
            childGroups.isNotEmpty() ||
            unsupportedDependencyCount > 0
}

object FomodPlanExpander {
    fun expand(
        installer: FomodInstaller,
        evaluation: FomodSelectionEvaluation,
        extractedRoot: File,
        targetRoot: String = ModTargetRoot.GAME_DIR.name,
        targetRelativePath: String = "Data",
        mode: String = ModPlacementMode.OVERWRITE_COPY.name,
    ): ModInstallPlan {
        val root = extractedRoot.canonicalFile
        val sourceResolver = CaseInsensitiveSourceResolver(root)
        val expanded = mutableListOf<ExpandedFomodFile>()
        val missing = mutableListOf<PlannedModFile>()

        evaluation.mappings.forEach { expected ->
            val sourcePath = joinPath(installer.basePath, expected.mapping.source)
            val source = sourceResolver.resolve(sourcePath)
            when {
                source == null || !source.exists() -> missing += expected.missingFile(sourcePath)
                expected.mapping.directory && !source.isDirectory -> missing += expected.missingFile(sourcePath)
                !expected.mapping.directory && !source.isFile -> missing += expected.missingFile(sourcePath)
                expected.mapping.directory -> {
                    val sourceRoot = source.canonicalFile
                    source.walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { file -> file.relativeTo(sourceRoot).path.lowercase(Locale.ROOT) }
                        .forEach { file ->
                            val relative = file.canonicalFile.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
                            val destination = joinPath(targetRelativePath, expected.mapping.destination, relative)
                            expanded += expected.expanded(file, root, targetRoot, destination, mode)
                        }
                }
                else -> {
                    val destination = if (expected.mapping.destination.isBlank()) {
                        joinPath(targetRelativePath, source.name)
                    } else {
                        joinPath(targetRelativePath, expected.mapping.destination)
                    }
                    expanded += expected.expanded(source, root, targetRoot, destination, mode)
                }
            }
        }

        val planned = expanded.map { it.file }.toMutableList()
        expanded.groupBy { it.file.normalizedTargetKey }.filterKeys { it != null }.values.forEach { contenders ->
            if (contenders.size < 2) return@forEach
            val winner = contenders.maxWithOrNull(
                compareBy<ExpandedFomodFile> { it.file.priority }.thenBy { it.ordinal },
            ) ?: return@forEach
            contenders.filter { it !== winner }.forEach { loser ->
                val index = planned.indexOf(loser.file)
                planned[index] = loser.file.copy(
                    status = PlannedFileStatus.INTENTIONALLY_IGNORED,
                    reason = "Replaced by selected FOMOD file ${winner.file.sourceRelativePath}",
                )
            }
        }
        planned += missing
        return PlacementRiskPolicy.enforce(ModInstallPlan(
            files = planned.sortedWith(
                compareBy<PlannedModFile> { it.normalizedTargetKey.orEmpty() }
                    .thenBy { it.sourceRelativePath.lowercase(Locale.ROOT) }
                    .thenByDescending { it.priority },
            ),
            warnings = evaluation.warnings,
            blockingIssues = evaluation.blockingIssues,
            producerId = "fomod",
            producerVersion = 1,
        ))
    }

    private data class ExpandedFomodFile(
        val file: PlannedModFile,
        val ordinal: Int,
    )

    private fun FomodExpectedMapping.expanded(
        source: File,
        extractedRoot: File,
        targetRoot: String,
        destination: String,
        mode: String,
    ): ExpandedFomodFile {
        val targetKey = ModTargetResolver.normalizedTargetKey(targetRoot, destination)
        return ExpandedFomodFile(
            file = PlannedModFile(
                sourceRelativePath = source.canonicalFile.relativeTo(extractedRoot).path.replace(File.separatorChar, '/'),
                targetRoot = targetRoot,
                targetRelativePath = destination,
                normalizedTargetKey = targetKey,
                status = if (targetKey == null) PlannedFileStatus.UNSUPPORTED else PlannedFileStatus.PLACED,
                origin = origin,
                priority = mapping.priority,
                mode = mode,
                sizeBytes = source.length(),
                reason = "Selected FOMOD ${origin.name.lowercase(Locale.ROOT).replace('_', ' ')} mapping",
                risk = if (targetKey == null) PlacementRisk.REVIEW else PlacementRisk.SAFE,
            ),
            ordinal = ordinal,
        )
    }

    private fun FomodExpectedMapping.missingFile(sourcePath: String): PlannedModFile = PlannedModFile(
        sourceRelativePath = sourcePath,
        status = PlannedFileStatus.MISSING,
        origin = origin,
        priority = mapping.priority,
        reason = "Selected FOMOD source is missing or has the wrong file type",
        risk = PlacementRisk.REVIEW,
    )

    private class CaseInsensitiveSourceResolver(
        private val root: File,
    ) {
        private val directoryListings = mutableMapOf<String, Map<String, List<File>>>()
        private val resolvedPaths = mutableMapOf<String, File?>()

        fun resolve(relativePath: String): File? {
            val key = normalizedArchiveKey(relativePath) ?: return null
            if (resolvedPaths.containsKey(key)) return resolvedPaths[key]
            val displaySegments = normalizeArchiveDisplayPath(relativePath).split('/').filter(String::isNotBlank)
            if (key.split('/').size != displaySegments.size) return null
            var current = root
            displaySegments.forEach { segment ->
                val children = directoryListings.getOrPut(current.path) {
                    current.listFiles().orEmpty().groupBy { it.name.lowercase(Locale.ROOT) }
                }
                val matches = children[segment.lowercase(Locale.ROOT)].orEmpty()
                if (matches.size != 1) {
                    resolvedPaths[key] = null
                    return null
                }
                current = matches.single()
            }
            val candidate = runCatching { current.canonicalFile }.getOrNull()
                ?.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
            resolvedPaths[key] = candidate
            return candidate
        }
    }

    private fun joinPath(vararg paths: String): String =
        paths.map(::normalizeArchiveDisplayPath).filter(String::isNotBlank).joinToString("/")
}
