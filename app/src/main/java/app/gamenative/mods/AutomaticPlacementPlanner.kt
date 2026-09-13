package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModTargetRoot
import java.util.Locale

data class AutomaticPlacementCandidate(
    val id: String,
    val label: String,
    val description: String,
    val drafts: List<ModPlacementPresetDraft>,
    val plan: ModInstallPlan,
    val score: Int,
    val evidence: List<String>,
)

data class AutomaticPlacementResult(
    val candidates: List<AutomaticPlacementCandidate>,
    val recommended: AutomaticPlacementCandidate?,
    val optionGroups: List<GenericOptionGroup> = emptyList(),
)

data class AutomaticPlacementContext(
    val defaultTargetRoot: String = ModTargetRoot.GAME_DIR.name,
    val defaultTargetRelativePath: String = "",
    val defaultTargetIsProven: Boolean = false,
    val existingGameDirectories: Set<String> = emptySet(),
)

object AutomaticPlacementPlanner {
    private val bethesdaRule = ModPlacementRulePacks.bethesda

    fun plan(
        gameName: String,
        entries: List<ModArchiveEntry>,
        selectedOptions: Map<String, String> = emptyMap(),
        context: AutomaticPlacementContext = AutomaticPlacementContext(),
    ): AutomaticPlacementResult {
        val fullIndex = ModArchiveIndex.build(entries)
        val optionGroups = GenericOptionSetDetector.detect(fullIndex)
        val validSelections = optionGroups.mapNotNull { group ->
            val saved = selectedOptions[group.stableId] ?: selectedOptions.values.singleOrNull { saved ->
                group.choices.any { it.sourceDirectory.equals(saved, ignoreCase = true) }
            }
            group.choices.firstOrNull { it.sourceDirectory.equals(saved, ignoreCase = true) }
                ?.let { group.stableId to it.sourceDirectory }
        }.toMap()
        val excludedOptionRoots = optionGroups.flatMap { group ->
            val selected = validSelections[group.stableId]
            if (selected == null) emptyList() else group.choices.map { it.sourceDirectory }.filterNot { it == selected }
        }
        val effectiveEntries = if (excludedOptionRoots.isEmpty()) {
            entries
        } else {
            entries.filterNot { entry -> excludedOptionRoots.any { root -> entry.path.isUnderArchiveRoot(root) } }
        }
        val index = if (effectiveEntries === entries) fullIndex else ModArchiveIndex.build(effectiveEntries)
        val legacy = ModPlacementPresetDetector.detect(gameName, effectiveEntries).map { preset ->
            candidateFromDrafts(
                id = "legacy:${preset.id}",
                label = preset.label,
                description = preset.description,
                drafts = preset.drafts,
                index = index,
                origin = PlacementOrigin.LEGACY_PRESET,
                evidence = listOf("Existing placement preset ${preset.id}"),
            )
        }
        val generated = buildList {
            bethesdaCandidate(gameName, index)?.let(::add)
            frameworkCandidates(gameName, index).let(::addAll)
            existingGameLayoutCandidate(index, context)?.let(::add)
            provenPackageDirectoryCandidate(index, optionGroups, validSelections, context)?.let(::add)
        }
        val combined = generated.takeIf { candidates -> candidates.size > 1 }
            ?.flatMap { it.drafts }
            ?.distinct()
            ?.let { drafts ->
                candidateFromDrafts(
                    id = "rules:combined-v1",
                    label = "Complete mixed-layout plan",
                    description = "Combines compatible built-in game and framework rules.",
                    drafts = drafts,
                    index = index,
                    origin = PlacementOrigin.GAME_RULE,
                    evidence = generated.flatMap { it.evidence }.distinct(),
                )
            }
        val generatedCandidates = if (combined == null) generated else generated + combined
        val ranked = (generatedCandidates + legacy)
            .distinctBy { candidate -> candidate.plan.digest }
            .sortedWith(
                compareByDescending<AutomaticPlacementCandidate> { it.plan.isComplete }
                    .thenByDescending { it.score }
                    .thenBy { it.id },
            )
        val baseline = legacy.firstOrNull()
        val bestGenerated = generatedCandidates.maxWithOrNull(
            compareBy<AutomaticPlacementCandidate> { it.plan.isComplete }
                .thenBy { it.score }
                .thenByDescending { it.id },
        )
        val recommendedBase = when {
            bestGenerated == null -> baseline
            baseline == null -> bestGenerated
            PlacementPlanRegressionPolicy.canReplace(baseline.plan, bestGenerated.plan) &&
                preservesExistingDestinations(baseline.plan, bestGenerated.plan) -> bestGenerated
            else -> baseline
        }
        val optionMessage = optionGroups.firstOrNull { it.stableId !in validSelections }?.let { group ->
            "Choose one package variant: ${group.choices.joinToString { it.sourceDirectory }}"
        }
        val withExcluded = if (excludedOptionRoots.isEmpty()) {
            ranked
        } else {
            val excludedFiles = fullIndex.files.filter { file ->
                excludedOptionRoots.any { root -> file.displayPath.isUnderArchiveRoot(root) }
            }
            ranked.map { candidate ->
                candidate.copy(
                    plan = candidate.plan.copy(
                        files = candidate.plan.files + excludedFiles.map { file ->
                            PlannedModFile(
                                sourceRelativePath = file.displayPath,
                                status = PlannedFileStatus.INTENTIONALLY_IGNORED,
                                origin = PlacementOrigin.GAME_RULE,
                                sizeBytes = file.sizeBytes,
                                reason = "Unselected package variant",
                            )
                        },
                    ),
                )
            }
        }
        val reviewed = if (optionMessage == null) {
            withExcluded
        } else {
            withExcluded.map { candidate ->
                candidate.copy(
                    plan = candidate.plan.copy(blockingIssues = (candidate.plan.blockingIssues + optionMessage).distinct()),
                    evidence = candidate.evidence + optionMessage,
                )
            }
        }
        val recommendedIndex = recommendedBase?.let { base ->
            ranked.indexOfFirst { candidate ->
                candidate.id == base.id || candidate.plan.digest == base.plan.digest
            }
        } ?: -1
        val recommended = reviewed.getOrNull(recommendedIndex)
        return AutomaticPlacementResult(reviewed, recommended, optionGroups)
    }

    fun inferIncludeSourceDirectory(
        selectedPaths: Collection<String>,
        entries: List<ModArchiveEntry>,
        targetRelativePath: String,
    ): Boolean {
        val selectedDirectories = selectedPaths.filter { selectedPath ->
            val selectedKey = normalizedArchiveKey(selectedPath) ?: return@filter false
            val childPrefix = "$selectedKey/"
            entries.any { entry ->
                val entryKey = normalizedArchiveKey(entry.path)
                entryKey == selectedKey && entry.directory || entryKey?.startsWith(childPrefix) == true
            }
        }
        if (selectedDirectories.isEmpty()) return false
        val targetName = normalizeArchiveDisplayPath(targetRelativePath).substringAfterLast('/')
        return selectedDirectories.any { source ->
            !source.substringAfterLast('/').equals(targetName, ignoreCase = true)
        }
    }

    private fun bethesdaCandidate(gameName: String, index: ModArchiveIndex): AutomaticPlacementCandidate? {
        val game = BethesdaPluginManager.detectGame(gameName) ?: return null
        if (index.hasFomod) return null
        val dataNodes = index.nodes.filter { node -> node.displayPath.substringAfterLast('/').equals("Data", ignoreCase = true) }
        val bestData = dataNodes.maxWithOrNull(
            compareBy<ArchiveTreeNode> { it.descendantFileCount }
                .thenByDescending { it.displayPath.count { char -> char == '/' } }
                .thenBy { it.normalizedKey },
        )
        val drafts = if (bestData != null) {
            dataNodes.sortedBy { it.normalizedKey }.map { dataNode ->
                ModPlacementPresetDraft(
                    sourceSubpath = dataNode.displayPath,
                    targetRelativePath = game.dataDirName,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = false,
                )
            } + riskyGameRootDrafts(index)
        } else {
            val sources = index.files.mapNotNull(::bethesdaSourceForFile).distinctBy { it.lowercase(Locale.ROOT) }
            if (sources.isEmpty()) return null
            listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = ModPlacementSources.encode(sources),
                    targetRelativePath = game.dataDirName,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = true,
                ),
            )
        }
        val evidence = buildList {
            if (bestData != null) {
                add("Found ${dataNodes.size} compatible Data container(s): ${dataNodes.take(3).joinToString { it.displayPath }}")
            }
            val anchors = index.nodes.flatMapTo(mutableSetOf()) { it.semanticAnchors }.sorted()
            if (anchors.isNotEmpty()) add("Recognized Data content: ${anchors.joinToString()}")
            val loose = index.files.count {
                it.displayPath.substringAfterLast('.').lowercase(Locale.ROOT) in bethesdaRule.looseExtensions
            }
            if (loose > 0) add("Found $loose Bethesda plugin/archive file(s)")
        }
        return candidateFromDrafts(
            id = "rules:${bethesdaRule.stableId}-v${bethesdaRule.version}",
            label = "Complete Bethesda Data plan",
            description = "Maps recognized Data content and loose plugins while preserving content folders.",
            drafts = drafts,
            index = index,
            origin = PlacementOrigin.GAME_RULE,
            evidence = evidence,
        )
    }

    private fun riskyGameRootDrafts(index: ModArchiveIndex): List<ModPlacementPresetDraft> =
        index.files.filter { it.role == ArchiveContentRole.RISKY_ROOT && '/' !in it.displayPath }
            .map { file ->
                ModPlacementPresetDraft(
                    sourceSubpath = file.displayPath,
                    targetRelativePath = "",
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = false,
                )
            }

    private fun frameworkCandidates(gameName: String, index: ModArchiveIndex): List<AutomaticPlacementCandidate> {
        if (index.hasFomod) return emptyList()
        return listOf(
            Triple(ModPlacementRulePacks.bepInEx, "BepInEx framework plan", "Recognized BepInEx package layout"),
            Triple(ModPlacementRulePacks.melonLoader, "MelonLoader framework plan", "Recognized MelonLoader package layout"),
            Triple(ModPlacementRulePacks.unreal, "Unreal Engine package plan", "Recognized Unreal Paks package layout"),
            Triple(ModPlacementRulePacks.redmod, "REDmod package plan", "Recognized Cyberpunk/REDmod package layout"),
        ).mapNotNull { (rule, label, evidence) ->
            if (rule.gameNameTokens.isNotEmpty()) {
                val normalizedGame = gameName.lowercase(Locale.ROOT)
                if (rule.gameNameTokens.none(normalizedGame::contains)) return@mapNotNull null
            }
            val drafts = rule.directoryTargets.mapNotNull { (sourcePath, targetPath) ->
                index.nodes
                    .filter { node ->
                        val sourceKey = sourcePath.lowercase(Locale.ROOT)
                        val suffixMatch = node.normalizedKey.endsWith("/$sourceKey")
                        val parentName = node.normalizedKey.substringBeforeLast('/', "").substringAfterLast('/')
                        node.normalizedKey == sourceKey ||
                            (
                                suffixMatch &&
                                    parentName !in setOf("bepinex", "skse", "f4se", "sfse", "content", "bin", "x64")
                                )
                    }
                    .maxByOrNull { it.descendantFileCount }
                    ?.let { node ->
                        ModPlacementPresetDraft(
                            sourceSubpath = node.displayPath,
                            targetRelativePath = targetPath,
                            mode = ModPlacementMode.OVERWRITE_COPY.name,
                            includeSourceDirectory = false,
                        )
                    }
            }.toMutableList()
            if (rule == ModPlacementRulePacks.unreal) {
                val loose = index.files.filter { file ->
                    '/' !in file.displayPath &&
                        file.displayPath.substringAfterLast('.', "").lowercase(Locale.ROOT) in rule.looseExtensions
                }.map { it.displayPath }
                if (loose.isNotEmpty()) {
                    drafts += ModPlacementPresetDraft(
                        sourceSubpath = ModPlacementSources.encode(loose),
                        targetRelativePath = "Content/Paks",
                        mode = ModPlacementMode.OVERWRITE_COPY.name,
                    )
                }
            }
            if (drafts.isEmpty()) return@mapNotNull null
            candidateFromDrafts(
                id = "rules:${rule.stableId}-v${rule.version}",
                label = label,
                description = evidence,
                drafts = drafts,
                index = index,
                origin = PlacementOrigin.GAME_RULE,
                evidence = listOf(evidence, "Rule ${rule.stableId}@${rule.version}"),
            )
        }
    }

    private fun existingGameLayoutCandidate(
        index: ModArchiveIndex,
        context: AutomaticPlacementContext,
    ): AutomaticPlacementCandidate? {
        if (index.hasFomod || context.existingGameDirectories.isEmpty()) return null
        val existingByKey = context.existingGameDirectories
            .associateBy { it.lowercase(Locale.ROOT) }
        val matchingRoots = index.nodes
            .asSequence()
            .filter { node -> '/' !in node.displayPath && node.descendantFileCount > 0 }
            .filter { node -> node.normalizedKey in existingByKey }
            .filter { node -> index.filesUnder(node.displayPath).any { it.role == ArchiveContentRole.INSTALLABLE } }
            .sortedBy { it.normalizedKey }
            .toList()
        if (matchingRoots.isEmpty()) return null
        val drafts = matchingRoots.map { node ->
            ModPlacementPresetDraft(
                sourceSubpath = node.displayPath,
                targetRelativePath = "",
                targetRoot = ModTargetRoot.GAME_DIR.name,
                mode = ModPlacementMode.OVERWRITE_COPY.name,
                includeSourceDirectory = true,
            )
        }
        return candidateFromDrafts(
            id = "rules:existing-game-layout-v1",
            label = "Match existing game folders",
            description = "Keeps archive folders whose names match folders already used by the game.",
            drafts = drafts,
            index = index,
            origin = PlacementOrigin.GAME_RULE,
            evidence = listOf("Matched existing game folders: ${matchingRoots.joinToString { it.displayPath }}"),
        )
    }

    private fun provenPackageDirectoryCandidate(
        index: ModArchiveIndex,
        optionGroups: List<GenericOptionGroup>,
        selectedOptions: Map<String, String>,
        context: AutomaticPlacementContext,
    ): AutomaticPlacementCandidate? {
        if (
            index.hasFomod ||
            !context.defaultTargetIsProven ||
            context.defaultTargetRelativePath.isBlank()
        ) {
            return null
        }
        val installableFiles = index.files.filter { it.role == ArchiveContentRole.INSTALLABLE }
        val topLevelRoots = installableFiles.mapNotNull { file ->
            file.displayPath.substringBefore('/', "").takeIf(String::isNotBlank)
        }.distinctBy { it.lowercase(Locale.ROOT) }
        if (topLevelRoots.size != 1 || installableFiles.any { '/' !in it.displayPath }) return null
        val packageRoot = topLevelRoots.single()
        val packageName = packageRoot.substringAfterLast('/')
        val sourceIsTargetContainer = context.defaultTargetRelativePath.substringAfterLast('/')
            .equals(packageName, ignoreCase = true)
        val packageTarget = if (sourceIsTargetContainer) {
            context.defaultTargetRelativePath
        } else {
            listOf(context.defaultTargetRelativePath, packageName).filter(String::isNotBlank).joinToString("/")
        }
        val packageGroups = optionGroups.filter { group ->
            group.choices.all { choice ->
                choice.sourceDirectory.substringBeforeLast('/', "").equals(packageRoot, ignoreCase = true)
            }
        }
        val selectedPackageGroups = packageGroups.mapNotNull { group ->
            selectedOptions[group.stableId]?.let { selected -> group to selected }
        }
        val drafts = if (selectedPackageGroups.isEmpty()) {
            listOf(
                ModPlacementPresetDraft(
                    sourceSubpath = packageRoot,
                    targetRelativePath = context.defaultTargetRelativePath,
                    targetRoot = context.defaultTargetRoot,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    includeSourceDirectory = !sourceIsTargetContainer,
                ),
            )
        } else {
            buildList {
                selectedPackageGroups.forEach { (_, selected) ->
                    add(
                        ModPlacementPresetDraft(
                            sourceSubpath = selected,
                            targetRelativePath = packageTarget,
                            targetRoot = context.defaultTargetRoot,
                            mode = ModPlacementMode.OVERWRITE_COPY.name,
                        ),
                    )
                }
                packageGroups.flatMap { it.commonSourceDirectories }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .forEach { common ->
                        add(
                            ModPlacementPresetDraft(
                                sourceSubpath = common,
                                targetRelativePath = packageTarget,
                                targetRoot = context.defaultTargetRoot,
                                mode = ModPlacementMode.OVERWRITE_COPY.name,
                                includeSourceDirectory = true,
                            ),
                        )
                    }
                val directFiles = index.filesUnder(packageRoot).filter { file ->
                    file.displayPath.removePrefixCaseInsensitive("$packageRoot/").let { '/' !in it }
                }.map { it.displayPath }
                if (directFiles.isNotEmpty()) {
                    add(
                        ModPlacementPresetDraft(
                            sourceSubpath = ModPlacementSources.encode(directFiles),
                            targetRelativePath = packageTarget,
                            targetRoot = context.defaultTargetRoot,
                            mode = ModPlacementMode.OVERWRITE_COPY.name,
                        ),
                    )
                }
            }
        }
        return candidateFromDrafts(
            id = "rules:proven-package-directory-v1",
            label = "Install package into ${context.defaultTargetRelativePath}",
            description = "Preserves the package folder while applying only the selected package variant.",
            drafts = drafts,
            index = index,
            origin = PlacementOrigin.GAME_RULE,
            evidence = listOf(
                "High-confidence mod directory: ${context.defaultTargetRelativePath}",
                "Single package folder: $packageRoot",
            ),
        )
    }

    private fun bethesdaSourceForFile(file: IndexedArchiveFile): String? {
        if (file.role != ArchiveContentRole.INSTALLABLE) return null
        val segments = file.displayPath.split('/')
        val anchorIndex = segments.indexOfFirst { it.lowercase(Locale.ROOT) in bethesdaRule.directoryTargets }
        if (anchorIndex >= 0) return segments.take(anchorIndex + 1).joinToString("/")
        if (file.displayPath.substringAfterLast('.', "").lowercase(Locale.ROOT) in bethesdaRule.looseExtensions) {
            return file.displayPath
        }
        return null
    }

    private fun candidateFromDrafts(
        id: String,
        label: String,
        description: String,
        drafts: List<ModPlacementPresetDraft>,
        index: ModArchiveIndex,
        origin: PlacementOrigin,
        evidence: List<String>,
    ): AutomaticPlacementCandidate {
        val placedBySource = linkedMapOf<String, PlannedModFile>()
        val conflictingSourceKeys = mutableSetOf<String>()
        drafts.forEach { draft ->
            ModPlacementSources.decode(draft.sourceSubpath).ifEmpty { listOf("") }.forEach { source ->
                val sourceIsDirectory = source.isBlank() || index.isDirectory(source)
                index.filesUnder(source).forEach fileLoop@ { file ->
                    if (!file.role.participatesInAutomaticPlacement() && file.role != ArchiveContentRole.INVALID) {
                        return@fileLoop
                    }
                    val relative = when {
                        source.isBlank() -> file.displayPath
                        !sourceIsDirectory -> file.displayPath.substringAfterLast('/')
                        else -> file.displayPath.removePrefixCaseInsensitive("$source/")
                    }
                    val targetPath = listOfNotNull(
                        draft.targetRelativePath.takeIf(String::isNotBlank),
                        source.substringAfterLast('/').takeIf { sourceIsDirectory && source.isNotBlank() && draft.includeSourceDirectory },
                        relative.takeIf(String::isNotBlank),
                    ).joinToString("/")
                    val targetKey = ModTargetResolver.normalizedTargetKey(draft.targetRoot, targetPath)
                    val planned = PlannedModFile(
                        sourceRelativePath = file.displayPath,
                        targetRoot = draft.targetRoot,
                        targetRelativePath = targetPath,
                        normalizedTargetKey = targetKey,
                        status = if (targetKey == null) PlannedFileStatus.UNSUPPORTED else PlannedFileStatus.PLACED,
                        origin = origin,
                        mode = draft.mode,
                        sizeBytes = file.sizeBytes,
                        reason = evidence.firstOrNull() ?: description,
                        evidence = evidence,
                        risk = if (file.role == ArchiveContentRole.RISKY_ROOT) PlacementRisk.UNSAFE else PlacementRisk.SAFE,
                    )
                    val previous = placedBySource[file.normalizedKey]
                    when {
                        file.normalizedKey in conflictingSourceKeys -> Unit
                        previous != null && previous.normalizedTargetKey != planned.normalizedTargetKey -> {
                            conflictingSourceKeys += file.normalizedKey
                            placedBySource[file.normalizedKey] = previous.copy(
                                status = PlannedFileStatus.CONFLICTED,
                                reason = "One archive file maps to multiple destinations",
                                evidence = (previous.evidence + planned.evidence).distinct(),
                                risk = if (
                                    previous.risk == PlacementRisk.UNSAFE || planned.risk == PlacementRisk.UNSAFE
                                ) {
                                    PlacementRisk.UNSAFE
                                } else {
                                    PlacementRisk.REVIEW
                                },
                            )
                        }
                        else -> placedBySource[file.normalizedKey] = planned
                    }
                }
            }
        }
        val classified = index.files.map { file ->
            placedBySource[file.normalizedKey] ?: when (file.role) {
                ArchiveContentRole.DOCUMENTATION,
                ArchiveContentRole.METADATA,
                ArchiveContentRole.INSTALLER_SUPPORT,
                -> PlannedModFile(
                    sourceRelativePath = file.displayPath,
                    status = PlannedFileStatus.INTENTIONALLY_IGNORED,
                    origin = origin,
                    sizeBytes = file.sizeBytes,
                    reason = "Known non-installable ${file.role.name.lowercase(Locale.ROOT).replace('_', ' ')}",
                )
                else -> PlannedModFile(
                    sourceRelativePath = file.displayPath,
                    status = PlannedFileStatus.UNSUPPORTED,
                    origin = origin,
                    sizeBytes = file.sizeBytes,
                    reason = "No supported destination was proven",
                    risk = if (file.role == ArchiveContentRole.RISKY_ROOT) PlacementRisk.UNSAFE else PlacementRisk.REVIEW,
                )
            }
        }.toMutableList()
        val duplicateTargets = classified.filter { it.status == PlannedFileStatus.PLACED }
            .groupBy { it.normalizedTargetKey }
            .filterKeys { it != null }
            .filterValues { files -> files.map { it.sourceRelativePath.lowercase(Locale.ROOT) }.distinct().size > 1 }
        if (duplicateTargets.isNotEmpty()) {
            duplicateTargets.values.flatten().forEach { duplicate ->
                val indexOfFile = classified.indexOf(duplicate)
                classified[indexOfFile] = duplicate.copy(
                    status = PlannedFileStatus.CONFLICTED,
                    reason = "Multiple archive files target one Windows path",
                    risk = PlacementRisk.REVIEW,
                )
            }
        }
        val blockers = buildList {
            if (index.caseCollisions.isNotEmpty()) add("Archive contains case-colliding file paths")
            if (classified.any { it.status == PlannedFileStatus.UNSUPPORTED }) add("Some installable files have no proven destination")
            if (conflictingSourceKeys.isNotEmpty()) add("One or more archive files map to multiple destinations")
            if (duplicateTargets.isNotEmpty()) add("Multiple files target the same Windows path")
            if (classified.any { it.risk == PlacementRisk.UNSAFE }) add(ModInstallPlan.RISKY_ROOT_REVIEW_BLOCKER)
        }
        val plan = ModInstallPlan(
            files = classified,
            blockingIssues = blockers,
            producerId = id,
            producerVersion = id.substringAfterLast("-v", "1").toIntOrNull() ?: 1,
        )
        val score = (plan.coverage * 1_000).toInt() + evidence.size * 25 - blockers.size * 250
        return AutomaticPlacementCandidate(id, label, description, drafts, plan, score, evidence)
    }

    private fun preservesExistingDestinations(baseline: ModInstallPlan, candidate: ModInstallPlan): Boolean {
        val candidateTargets = candidate.files.associate { it.sourceRelativePath.lowercase(Locale.ROOT) to it.normalizedTargetKey }
        return baseline.files.filter { it.status == PlannedFileStatus.PLACED }.all { old ->
            candidateTargets[old.sourceRelativePath.lowercase(Locale.ROOT)] == old.normalizedTargetKey
        }
    }

    private fun String.removePrefixCaseInsensitive(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun String.isUnderArchiveRoot(root: String): Boolean {
        val path = normalizeArchiveDisplayPath(this)
        val normalizedRoot = normalizeArchiveDisplayPath(root)
        return path.equals(normalizedRoot, ignoreCase = true) || path.startsWith("$normalizedRoot/", ignoreCase = true)
    }
}
