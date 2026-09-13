package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModOverwriteManifest
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

data class ModPlacementConflict(
    val sourcePath: String,
    val targetPath: String,
    val directory: Boolean,
)

data class ModPlacementResult(
    val created: Int,
    val skipped: Int,
    val backedUp: Int,
    val errors: Map<String, String>,
    val manifests: List<ModOverwriteManifest>,
    val warnings: List<String> = emptyList(),
)

data class ModPlannedEntry(
    val installId: String,
    val source: File,
    val target: File,
    val mode: ModPlacementMode = ModPlacementMode.SYMLINK,
    val targetRoot: String = "",
    val sourceRelativePath: String = "",
    val targetRelativePath: String = "",
    val normalizedTargetKey: String = WindowsPathIdentity.absoluteKey(target),
    val targetExistedBefore: Boolean = target.exists() || Files.isSymbolicLink(target.toPath()),
)

data class ModPlannedFile(
    val installId: String,
    val source: File,
    val target: File,
    val mode: ModPlacementMode,
    val targetRoot: String,
    val sourceRelativePath: String,
    val targetRelativePath: String,
    val normalizedTargetKey: String = WindowsPathIdentity.absoluteKey(target),
    val targetExistedBefore: Boolean = target.exists() || Files.isSymbolicLink(target.toPath()),
    val targetHashBefore: String = "",
)

data class ModMaterializationPlan(
    val installId: String,
    val operations: List<ModPlannedEntry>,
    val files: List<ModPlannedFile>,
    val errors: Map<String, String> = emptyMap(),
    val reviewedPlan: ModInstallPlan = ModInstallPlan(
        files = emptyList(),
        blockingIssues = errors.values.toList(),
        producerId = "legacy-runtime",
    ),
) {
    val isComplete: Boolean get() = errors.isEmpty() && reviewedPlan.isComplete
    val digest: String
        get() {
            val canonical = reviewedPlan.digest + "\n" + files.joinToString("\n") { file ->
                "${file.sourceRelativePath}|${file.targetRoot}|${file.targetRelativePath}|${file.normalizedTargetKey}|${file.mode}"
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
}

object ModMaterializer {
    private const val COPY_SENTINEL = ".gamenative_mod_install"
    private const val APPLY_FREE_SPACE_RESERVE_BYTES = 512L * 1024L * 1024L

    fun filterUnapprovedConflicts(
        conflicts: List<ModPlacementConflict>,
        manifests: List<ModOverwriteManifest>,
    ): List<ModPlacementConflict> {
        if (conflicts.isEmpty() || manifests.isEmpty()) return conflicts
        val manifestsByTarget = manifests.groupBy { WindowsPathIdentity.absoluteKey(File(it.targetPath)) }
        return conflicts.filter { conflict ->
            val target = File(conflict.targetPath)
            val matchingManifests = manifestsByTarget[WindowsPathIdentity.absoluteKey(target)].orEmpty()
            matchingManifests.none { manifest -> targetMatchesApprovedState(target, manifest) }
        }
    }

    suspend fun scanConflicts(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        reviewedPlan: ModInstallPlan? = null,
    ): List<ModPlacementConflict> = withContext(Dispatchers.IO) {
        val plan = materializationPlan(
            install,
            recipes,
            gameRootDir,
            winePrefix,
            captureTargetHashes = false,
            reviewedPlan = reviewedPlan,
        )
        buildList {
            plan.operations.forEach { entry ->
                when (entry.mode) {
                    ModPlacementMode.OVERWRITE_COPY -> addAll(overwriteConflicts(entry))
                    else -> {
                        if (entry.target.exists() || Files.isSymbolicLink(entry.target.toPath())) {
                            val alreadyCorrectSymlink = Files.isSymbolicLink(entry.target.toPath()) &&
                                resolveSymlinkTarget(entry.target)?.canonicalFile == entry.source.canonicalFile
                            if (!alreadyCorrectSymlink) {
                                add(
                                    ModPlacementConflict(
                                        sourcePath = entry.source.absolutePath,
                                        targetPath = entry.target.absolutePath,
                                        directory = entry.source.isDirectory,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun overwriteConflicts(entry: ModPlannedEntry): List<ModPlacementConflict> {
        if (entry.source.isFile) {
            return if (targetNeedsOverwrite(entry.target, entry.source)) {
                listOf(
                    ModPlacementConflict(
                        sourcePath = entry.source.absolutePath,
                        targetPath = entry.target.absolutePath,
                        directory = false,
                    ),
                )
            } else {
                emptyList()
            }
        }
        if (!entry.source.isDirectory) return emptyList()
        if (Files.isSymbolicLink(entry.target.toPath())) {
            return listOf(
                ModPlacementConflict(
                    sourcePath = entry.source.absolutePath,
                    targetPath = entry.target.absolutePath,
                    directory = true,
                ),
            )
        }
        val sourceRoot = entry.source.canonicalFile
        return entry.source.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { sourceFile ->
                val relative = sourceFile.canonicalFile.relativeToOrNull(sourceRoot)?.path ?: return@mapNotNull null
                val targetFile = safeChildTarget(entry.target, relative)
                if (targetNeedsOverwrite(targetFile, sourceFile)) {
                    ModPlacementConflict(
                        sourcePath = sourceFile.absolutePath,
                        targetPath = targetFile.absolutePath,
                        directory = false,
                    )
                } else {
                    null
                }
            }
            .toList()
    }

    suspend fun apply(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        backupRoot: File,
        allowOverwrite: Boolean,
    ): ModPlacementResult {
        val plan = withContext(Dispatchers.IO) {
            materializationPlan(install, recipes, gameRootDir, winePrefix)
        }
        return apply(install, plan, backupRoot, allowOverwrite)
    }

    suspend fun apply(
        install: ModInstall,
        plan: ModMaterializationPlan,
        backupRoot: File,
        allowOverwrite: Boolean,
    ): ModPlacementResult = withContext(Dispatchers.IO) {
        if (!plan.isComplete) return@withContext blockedResult(plan)
        var created = 0
        var skipped = 0
        var backedUp = 0
        val errors = linkedMapOf<String, String>().apply { putAll(plan.errors) }
        val manifests = mutableListOf<ModOverwriteManifest>()
        val targetsWrittenThisApply = mutableSetOf<String>()

        backupRoot.mkdirs()
        plan.operations.forEach { entry ->
            try {
                when (entry.mode) {
                    ModPlacementMode.SYMLINK -> {
                        val result = ensureSymlink(entry.target, entry.source)
                        if (result) created++ else skipped++
                    }
                    ModPlacementMode.COPY -> {
                        val result = copyWithoutOverwrite(entry.target, entry.source, install.installId)
                        if (result) created++ else skipped++
                    }
                    ModPlacementMode.OVERWRITE_COPY -> {
                        val result = copyWithBackups(
                            install = install,
                            target = entry.target,
                            source = entry.source,
                            backupRoot = backupRoot,
                            allowOverwrite = allowOverwrite,
                            targetsWrittenThisApply = targetsWrittenThisApply,
                        )
                        created += result.created
                        backedUp += result.backedUp
                        manifests += result.manifests
                    }
                }
            } catch (e: Exception) {
                errors[entry.target.absolutePath] = "${e::class.simpleName}: ${e.message}"
            }
        }

        ModPlacementResult(created, skipped, backedUp, errors, manifests)
    }

    suspend fun repairMissingTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        reviewedPlan: ModInstallPlan? = null,
    ): ModPlacementResult = withContext(Dispatchers.IO) {
        var created = 0
        var skipped = 0
        val plan = materializationPlan(
            install,
            recipes,
            gameRootDir,
            winePrefix,
            captureTargetHashes = false,
            reviewedPlan = reviewedPlan,
        )
        if (!plan.isComplete) return@withContext blockedResult(plan)
        val errors = linkedMapOf<String, String>().apply { putAll(plan.errors) }

        plan.operations.forEach { entry ->
            try {
                when (entry.mode) {
                    ModPlacementMode.SYMLINK -> {
                        if (entry.target.exists() || Files.isSymbolicLink(entry.target.toPath())) {
                            skipped++
                        } else if (ensureSymlink(entry.target, entry.source)) {
                            created++
                        } else {
                            skipped++
                        }
                    }
                    ModPlacementMode.COPY,
                    ModPlacementMode.OVERWRITE_COPY,
                    -> {
                        val result = copyMissingFiles(entry.target, entry.source)
                        created += result.created
                        skipped += result.skipped
                    }
                }
            } catch (e: Exception) {
                errors[entry.target.absolutePath] = "${e::class.simpleName}: ${e.message}"
            }
        }

        ModPlacementResult(created, skipped, backedUp = 0, errors = errors, manifests = emptyList())
    }

    suspend fun restoreBackups(manifests: List<ModOverwriteManifest>): List<String> = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val handledTargets = mutableSetOf<String>()
        manifests.sortedByDescending { it.targetPath.length }.forEach { manifest ->
            if (!handledTargets.add(WindowsPathIdentity.absoluteKey(File(manifest.targetPath)))) return@forEach
            val target = File(manifest.targetPath)
            if (manifest.backupPath.isBlank()) return@forEach
            val backup = File(manifest.backupPath)
            if (!backup.isFile) {
                skipped += manifest.targetPath
                return@forEach
            }
            val currentHash = if (target.isFile) sha256(target) else ""
            if (currentHash.isNotBlank() && currentHash != manifest.installedHash) {
                skipped += manifest.targetPath
                return@forEach
            }
            deleteTargetSymlinkIfPresent(target)
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
        }
        skipped
    }

    suspend fun removeAppliedFiles(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        restoredOverwriteTargets: Set<String> = emptySet(),
    ): List<String> = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val restoredOverwriteTargetKeys = restoredOverwriteTargets.mapTo(mutableSetOf()) {
            WindowsPathIdentity.absoluteKey(File(it))
        }
        val plan = materializationPlan(install, recipes, gameRootDir, winePrefix, captureTargetHashes = false)
        plan.operations.forEach { entry ->
            runCatching {
                when (entry.mode) {
                    ModPlacementMode.SYMLINK -> removeSymlink(entry.target, entry.source, skipped)
                    ModPlacementMode.COPY -> removeCopiedEntry(
                        target = entry.target,
                        source = entry.source,
                        installId = install.installId,
                        skipped = skipped,
                        allowOwnedDirectoryDelete = true,
                        reportChangedFiles = true,
                    )
                    ModPlacementMode.OVERWRITE_COPY -> removeCopiedEntry(
                        target = entry.target,
                        source = entry.source,
                        installId = install.installId,
                        skipped = skipped,
                        allowOwnedDirectoryDelete = false,
                        reportChangedFiles = true,
                        ignoredChangedTargetKeys = restoredOverwriteTargetKeys,
                        removeLegacySentinel = true,
                    )
                }
            }.onFailure { skipped += "${entry.targetRoot}:${entry.targetRelativePath}" }
        }
        skipped += plan.errors.keys
        skipped.distinct()
    }

    /** Roll back only targets proven to have been absent before this exact plan. */
    suspend fun rollbackAppliedPlan(
        plan: ModMaterializationPlan,
        restoredOverwriteTargets: Set<String> = emptySet(),
    ): List<String> = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val restoredKeys = restoredOverwriteTargets.mapTo(mutableSetOf()) {
            WindowsPathIdentity.absoluteKey(File(it))
        }

        plan.operations
            .filter { it.mode == ModPlacementMode.SYMLINK && !it.targetExistedBefore }
            .sortedByDescending { it.target.absolutePath.length }
            .forEach { operation ->
                runCatching { removeSymlink(operation.target, operation.source, skipped) }
                    .onFailure { skipped += operation.target.absolutePath }
            }

        plan.files.asReversed()
            .filter { file ->
                file.mode != ModPlacementMode.SYMLINK &&
                    !file.targetExistedBefore &&
                    file.normalizedTargetKey !in restoredKeys
            }
            .forEach { file ->
                runCatching {
                    val target = file.target
                    if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return@runCatching
                    val currentHash = if (target.isFile) sha256(target) else ""
                    val sourceHash = if (file.source.isFile) sha256(file.source) else ""
                    if (target.isFile && sourceHash.isNotBlank() && currentHash == sourceHash) {
                        target.delete()
                        target.parentFile?.let { deleteEmptyDirs(it, stopAt = target.parentFile?.parentFile) }
                    } else {
                        skipped += target.absolutePath
                    }
                }.onFailure { skipped += file.target.absolutePath }
            }

        plan.operations.asReversed()
            .filter { operation ->
                operation.mode == ModPlacementMode.COPY &&
                    operation.source.isDirectory &&
                    !operation.targetExistedBefore
            }
            .forEach { operation ->
                runCatching {
                    val operationTargetPath = operation.target.absoluteFile.toPath().normalize()
                    val preservedTargetExists = plan.files.any { file ->
                        file.mode == ModPlacementMode.COPY &&
                            file.target.absoluteFile.toPath().normalize().startsWith(operationTargetPath) &&
                            (file.target.exists() || Files.isSymbolicLink(file.target.toPath()))
                    }
                    if (!preservedTargetExists) {
                        val sentinel = File(operation.target, COPY_SENTINEL)
                        if (sentinel.isFile && sentinel.readText() == plan.installId) {
                            check(sentinel.delete()) { "Could not remove rollback marker" }
                        }
                        deleteEmptyDirs(operation.target, stopAt = operation.target.parentFile)
                    }
                }.onFailure { skipped += operation.target.absolutePath }
            }
        skipped.distinct()
    }

    fun materializationPlan(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        captureTargetHashes: Boolean = true,
        reviewedPlan: ModInstallPlan? = null,
    ): ModMaterializationPlan {
        if (reviewedPlan != null) {
            return resolveReviewedPlan(
                install,
                PlacementRiskPolicy.enforce(reviewedPlan),
                gameRootDir,
                winePrefix,
                captureTargetHashes,
            )
        }
        val operations = mutableListOf<ModPlannedEntry>()
        val errors = linkedMapOf<String, String>()
        val targetSession = ModTargetResolver.session(gameRootDir, winePrefix)
        recipes.filter { it.enabled }.forEach { recipe ->
            runCatching { plannedEntries(install, recipe, targetSession) }
                .onSuccess(operations::addAll)
                .onFailure { error ->
                    val key = recipe.sourceSubpath.ifBlank { recipe.targetRelativePath.ifBlank { install.modName } }
                    errors[key] = error.message ?: error::class.simpleName.orEmpty()
                }
        }
        val targetHashes = mutableMapOf<String, String>()
        val targetNamespaces = mutableMapOf<String, WindowsTargetNamespace>()
        val expandedFiles = buildList {
            operations.forEach { entry ->
                val targetNamespace = targetNamespaces.getOrPut(entry.normalizedTargetKey) {
                    WindowsTargetNamespace(entry.target)
                }
                runCatching {
                    expandPlannedFiles(entry, targetNamespace, targetHashes, captureTargetHashes)
                }
                    .onSuccess(::addAll)
                    .onFailure { error ->
                        errors[entry.sourceRelativePath.ifBlank { entry.targetRelativePath }] =
                            error.message ?: error::class.simpleName.orEmpty()
                    }
            }
        }
        val files = expandedFiles.groupBy { it.normalizedTargetKey }.flatMap { (targetKey, values) ->
            val distinctSources = values.map { it.source.canonicalPath }.distinct()
            when {
                distinctSources.size <= 1 -> listOf(values.last())
                values.all { it.mode == ModPlacementMode.OVERWRITE_COPY } -> listOf(values.last())
                else -> {
                    errors[targetKey] = "Multiple selected files target the same Windows path: " +
                        values.joinToString { it.sourceRelativePath }
                    values
                }
            }
        }.sortedWith(compareBy<ModPlannedFile> { it.normalizedTargetKey }.thenBy { it.sourceRelativePath.lowercase(Locale.ROOT) })
        if (files.isEmpty()) {
            errors[install.modName] = "The saved placement does not contain any files to install"
        }
        val manualPlan = PlacementRiskPolicy.enforce(ModInstallPlan(
            files = files.map { file ->
                PlannedModFile(
                    sourceRelativePath = file.sourceRelativePath,
                    targetRoot = file.targetRoot,
                    targetRelativePath = file.targetRelativePath,
                    normalizedTargetKey = ModTargetResolver.normalizedTargetKey(file.targetRoot, file.targetRelativePath),
                    status = PlannedFileStatus.PLACED,
                    origin = PlacementOrigin.MANUAL_RECIPE,
                    mode = file.mode.name,
                    sizeBytes = file.source.length(),
                    reason = "Expanded from a saved placement recipe",
                )
            },
            blockingIssues = errors.values.distinct(),
            producerId = "manual-recipes",
            producerVersion = 1,
        ))
        return ModMaterializationPlan(install.installId, operations, files, errors, manualPlan)
    }

    private fun resolveReviewedPlan(
        install: ModInstall,
        reviewedPlan: ModInstallPlan,
        gameRootDir: File?,
        winePrefix: String,
        captureTargetHashes: Boolean,
    ): ModMaterializationPlan {
        val errors = linkedMapOf<String, String>()
        reviewedPlan.blockingIssues.forEachIndexed { index, issue -> errors["plan:$index"] = issue }
        val extractedRoot = File(install.extractedPath).canonicalFile
        val targetSession = ModTargetResolver.session(gameRootDir, winePrefix)
        val targetHashes = mutableMapOf<String, String>()
        val operations = mutableListOf<ModPlannedEntry>()
        val files = mutableListOf<ModPlannedFile>()

        reviewedPlan.files.filter { it.status == PlannedFileStatus.PLACED }
            .sortedWith(compareBy<PlannedModFile> { it.normalizedTargetKey.orEmpty() }.thenBy { it.sourceRelativePath.lowercase(Locale.ROOT) })
            .forEach { planned ->
                val source = resolveReviewedSource(extractedRoot, planned.sourceRelativePath)
                val targetRoot = planned.targetRoot
                val targetRelativePath = planned.targetRelativePath
                when {
                    source == null || !source.isFile -> errors[planned.sourceRelativePath] = "Reviewed source file is missing or case-ambiguous"
                    targetRoot == null || targetRelativePath == null -> errors[planned.sourceRelativePath] = "Reviewed target is missing"
                    else -> {
                        val logicalKey = ModTargetResolver.normalizedTargetKey(targetRoot, targetRelativePath)
                        if (logicalKey == null || logicalKey != planned.normalizedTargetKey) {
                            errors[planned.sourceRelativePath] = "Reviewed target identity changed before apply"
                            return@forEach
                        }
                        val target = targetSession.resolve(targetRoot, targetRelativePath)
                        if (target == null) {
                            errors[planned.sourceRelativePath] = "Reviewed target is unavailable or case-ambiguous"
                            return@forEach
                        }
                        val mode = runCatching { ModPlacementMode.valueOf(planned.mode) }
                            .getOrDefault(ModPlacementMode.OVERWRITE_COPY)
                        val operation = ModPlannedEntry(
                            installId = install.installId,
                            source = source,
                            target = target,
                            mode = mode,
                            targetRoot = targetRoot,
                            sourceRelativePath = planned.sourceRelativePath,
                            targetRelativePath = targetRelativePath,
                        )
                        operations += operation
                        files += expandPlannedFiles(
                            operation,
                            WindowsTargetNamespace(target.parentFile ?: target),
                            targetHashes,
                            captureTargetHashes,
                        )
                    }
                }
            }
        files.groupBy { it.normalizedTargetKey }.filterValues { it.size > 1 }.forEach { (key, contenders) ->
            if (contenders.map { it.source.canonicalPath }.distinct().size > 1) {
                errors[key] = "Reviewed plan contains multiple files for one Windows target"
            }
        }
        if (files.size != reviewedPlan.placedCount) {
            errors[install.modName] = "Resolved ${files.size} of ${reviewedPlan.placedCount} reviewed files"
        }
        return ModMaterializationPlan(
            installId = install.installId,
            operations = operations,
            files = files.sortedWith(compareBy<ModPlannedFile> { it.normalizedTargetKey }.thenBy { it.sourceRelativePath.lowercase(Locale.ROOT) }),
            errors = errors,
            reviewedPlan = reviewedPlan,
        )
    }

    private fun resolveReviewedSource(extractedRoot: File, sourceRelativePath: String): File? {
        val segments = normalizedArchiveKey(sourceRelativePath)?.split('/').orEmpty()
        val displaySegments = normalizeArchiveDisplayPath(sourceRelativePath).split('/').filter(String::isNotBlank)
        if (segments.size != displaySegments.size) return null
        var current = extractedRoot
        displaySegments.forEach { segment ->
            val matches = current.listFiles().orEmpty().filter { it.name.equals(segment, ignoreCase = true) }
            if (matches.size != 1) return null
            current = matches.single()
        }
        val source = runCatching { current.canonicalFile }.getOrNull() ?: return null
        return source.takeIf { it.path.startsWith(extractedRoot.path + File.separator) }
    }

    private fun blockedResult(plan: ModMaterializationPlan): ModPlacementResult {
        val errors = linkedMapOf<String, String>().apply {
            putAll(plan.errors)
            plan.reviewedPlan.blockingIssues.forEachIndexed { index, issue -> putIfAbsent("plan:$index", issue) }
            plan.reviewedPlan.files
                .filter { it.status != PlannedFileStatus.PLACED && it.status != PlannedFileStatus.INTENTIONALLY_IGNORED }
                .forEach { file -> putIfAbsent(file.sourceRelativePath, file.reason) }
            if (isEmpty()) put("plan", "The reviewed placement plan is incomplete")
        }
        return ModPlacementResult(0, 0, 0, errors, emptyList())
    }

    private fun plannedEntries(
        install: ModInstall,
        recipe: ModPlacementRecipe,
        targetSession: ModTargetResolutionSession,
    ): List<ModPlannedEntry> =
        sourceSubpathsForPlacement(recipe.sourceSubpath).flatMap { sourceSubpath ->
            plannedEntriesForSource(install, recipe, sourceSubpath, targetSession)
        }

    private fun sourceSubpathsForPlacement(sourceSubpath: String): List<String> =
        ModPlacementSources.decode(sourceSubpath).ifEmpty { listOf("") }

    private fun plannedEntriesForSource(
        install: ModInstall,
        recipe: ModPlacementRecipe,
        sourceSubpath: String,
        targetSession: ModTargetResolutionSession,
    ): List<ModPlannedEntry> {
        val extractedRoot = File(install.extractedPath).canonicalFile
        val normalizedSource = ModPlacementSources.normalize(sourceSubpath)
        val source = resolveSource(extractedRoot, normalizedSource)
        if (source != extractedRoot && !source.path.startsWith(extractedRoot.path + File.separator)) {
            throw IOException("Source path escapes extracted mod directory")
        }
        if (!source.exists()) {
            throw IOException("Source path does not exist: $sourceSubpath")
        }
        val targetDir = targetSession.resolve(recipe.targetRoot, recipe.targetRelativePath)
            ?: throw IOException("Target root is unavailable: ${recipe.targetRoot}")
        val mode = runCatching { ModPlacementMode.valueOf(recipe.mode) }.getOrDefault(ModPlacementMode.SYMLINK)

        val effectiveSource = stripPrefix(source, recipe.stripPrefixSegments)
        return when {
            effectiveSource.isFile -> listOf(
                plannedEntry(
                    install.installId,
                    effectiveSource,
                    targetDir,
                    recipe.targetFileName.ifBlank { effectiveSource.name },
                    mode,
                    recipe.targetRoot,
                    extractedRoot,
                    recipe.targetRelativePath,
                ),
            )
            recipe.includeSourceDirectory && effectiveSource != extractedRoot ->
                listOf(
                    plannedEntry(
                        install.installId,
                        effectiveSource,
                        targetDir,
                        effectiveSource.name,
                        mode,
                        recipe.targetRoot,
                        extractedRoot,
                        recipe.targetRelativePath,
                    ),
                )
            else -> effectiveSource.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.map {
                    plannedEntry(
                        install.installId,
                        it,
                        targetDir,
                        it.name,
                        mode,
                        recipe.targetRoot,
                        extractedRoot,
                        recipe.targetRelativePath,
                    )
                }
                ?: emptyList()
        }
    }

    private fun plannedEntry(
        installId: String,
        source: File,
        targetRoot: File,
        relative: String,
        mode: ModPlacementMode,
        targetRootName: String,
        extractedRoot: File,
        targetBaseRelative: String,
    ): ModPlannedEntry {
        val target = ModTargetResolver.resolveWithin(targetRoot, relative)
            ?: throw IOException("Target path is invalid or case-ambiguous: $relative")
        return ModPlannedEntry(
            installId = installId,
            source = source,
            target = target,
            mode = mode,
            targetRoot = targetRootName,
            sourceRelativePath = source.relativeTo(extractedRoot).path.replace(File.separatorChar, '/'),
            targetRelativePath = listOf(targetBaseRelative, relative)
                .filter(String::isNotBlank)
                .joinToString("/")
                .replace(File.separatorChar, '/'),
        )
    }

    private fun expandPlannedFiles(
        entry: ModPlannedEntry,
        targetNamespace: WindowsTargetNamespace,
        targetHashes: MutableMap<String, String>,
        captureTargetHashes: Boolean,
    ): List<ModPlannedFile> {
        val files = if (entry.source.isFile) sequenceOf(entry.source) else entry.source.walkTopDown().filter { it.isFile }
        return files.map { sourceFile ->
            val nested = if (entry.source.isFile) "" else sourceFile.relativeTo(entry.source).path
            val targetFile = if (nested.isBlank()) {
                entry.target
            } else {
                targetNamespace.resolve(nested).takeIf { it.isValid }?.file
                    ?: throw IOException("Target path escapes or is ambiguous in destination directory: $nested")
            }
            val targetKey = WindowsPathIdentity.absoluteKey(targetFile)
            ModPlannedFile(
                installId = entry.installId,
                source = sourceFile,
                target = targetFile,
                mode = entry.mode,
                targetRoot = entry.targetRoot,
                sourceRelativePath = sourceFile.path.removePrefix(entry.source.path)
                    .trimStart(File.separatorChar)
                    .let { nestedSource ->
                        listOf(entry.sourceRelativePath, nestedSource)
                            .filter(String::isNotBlank)
                            .joinToString("/")
                            .replace(File.separatorChar, '/')
                    },
                targetRelativePath = listOf(entry.targetRelativePath, nested.replace(File.separatorChar, '/'))
                    .filter(String::isNotBlank)
                    .joinToString("/"),
                targetHashBefore = if (
                    captureTargetHashes && targetFile.isFile && !Files.isSymbolicLink(targetFile.toPath())
                ) {
                    targetHashes.getOrPut(targetKey) { sha256(targetFile) }
                } else {
                    ""
                },
            )
        }.toList()
    }

    private fun resolveSource(extractedRoot: File, normalizedSource: String): File {
        if (normalizedSource.isBlank()) return extractedRoot
        if (normalizedSource.split('/').any { it == ".." }) {
            throw IOException("Source path escapes extracted mod directory")
        }
        val direct = File(extractedRoot, normalizedSource).canonicalFile
        if (direct.exists()) return direct
        resolveCaseInsensitive(extractedRoot, normalizedSource)?.let { return it.canonicalFile }
        extractedRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.forEach { wrapper ->
                resolveCaseInsensitive(wrapper, normalizedSource)?.let { return it.canonicalFile }
            }
        return extractedRoot.walkTopDown()
            .firstOrNull { file ->
                val relative = file.relativeToOrNull(extractedRoot)
                    ?.path
                    ?.replace(File.separatorChar, '/')
                    .orEmpty()
                relative.equals(normalizedSource, ignoreCase = true) ||
                    relative.endsWith("/$normalizedSource", ignoreCase = true)
            }
            ?.canonicalFile
            ?: resolveSingleDirectoryByName(extractedRoot, normalizedSource.substringAfterLast('/'))?.canonicalFile
            ?: direct
    }

    private fun resolveSingleDirectoryByName(root: File, name: String): File? {
        if (!name.equals("Data", ignoreCase = true)) return null
        val matches = root.walkTopDown()
            .filter { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            .take(2)
            .toList()
        return matches.singleOrNull()
    }

    private fun resolveCaseInsensitive(root: File, path: String): File? {
        var current = root
        path.split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = current.listFiles()
                ?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current
    }

    private fun stripPrefix(source: File, segments: Int): File {
        var current = source
        repeat(segments.coerceAtLeast(0)) {
            val children = current.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            current = children.singleOrNull { it.isDirectory } ?: current
        }
        return current
    }

    private fun ensureSymlink(target: File, source: File): Boolean {
        target.parentFile?.mkdirs()
        val targetPath = target.toPath()
        if (Files.isSymbolicLink(targetPath)) {
            val current = resolveSymlinkTarget(target)
            if (current?.canonicalFile == source.canonicalFile) return false
            Files.delete(targetPath)
        } else if (target.exists()) {
            return false
        }
        Files.createSymbolicLink(targetPath, source.toPath().toAbsolutePath().normalize())
        return true
    }

    private fun resolveSymlinkTarget(file: File): File? {
        val path = file.toPath()
        val raw = runCatching { Files.readSymbolicLink(path) }.getOrNull() ?: return null
        val resolved = if (raw.isAbsolute) raw else path.parent.resolve(raw)
        return resolved.normalize().toFile()
    }

    private fun removeSymlink(target: File, source: File, skipped: MutableList<String>) {
        val targetPath = target.toPath()
        if (!Files.isSymbolicLink(targetPath)) return
        val current = resolveSymlinkTarget(target)
        if (current?.canonicalFile == source.canonicalFile) {
            Files.deleteIfExists(targetPath)
        } else {
            skipped += target.absolutePath
        }
    }

    private fun removeCopiedEntry(
        target: File,
        source: File,
        installId: String,
        skipped: MutableList<String>,
        allowOwnedDirectoryDelete: Boolean,
        reportChangedFiles: Boolean,
        ignoredChangedTargetKeys: Set<String> = emptySet(),
        removeLegacySentinel: Boolean = false,
    ) {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
        if (source.isDirectory) {
            val sentinel = File(target, COPY_SENTINEL)
            if (allowOwnedDirectoryDelete && target.isDirectory && sentinel.isFile && sentinel.readText() == installId) {
                target.deleteRecursively()
                return
            }
            if (removeLegacySentinel && sentinel.isFile && sentinel.readText() == installId) {
                sentinel.delete()
            }
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { sourceFile ->
                    val targetFile = safeChildTarget(target, sourceFile.relativeTo(source).path)
                    removeCopiedFileIfUnchanged(targetFile, sourceFile, skipped, reportChangedFiles, ignoredChangedTargetKeys)
                }
            if (allowOwnedDirectoryDelete || removeLegacySentinel) {
                deleteEmptyDirs(target, stopAt = target.parentFile)
            }
        } else {
            removeCopiedFileIfUnchanged(target, source, skipped, reportChangedFiles, ignoredChangedTargetKeys)
        }
    }

    private fun removeCopiedFileIfUnchanged(
        target: File,
        source: File,
        skipped: MutableList<String>,
        reportChangedFiles: Boolean,
        ignoredChangedTargetKeys: Set<String> = emptySet(),
    ) {
        if (!target.exists() || !target.isFile || !source.isFile) return
        val targetKey = WindowsPathIdentity.absoluteKey(target)
        if (targetKey in ignoredChangedTargetKeys) return
        if (sha256(target) == sha256(source)) {
            target.delete()
        } else if (reportChangedFiles) {
            skipped += target.absolutePath
        }
    }

    private fun deleteEmptyDirs(dir: File, stopAt: File?) {
        if (!dir.isDirectory || dir == stopAt) return
        dir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { deleteEmptyDirs(it, stopAt = dir) }
        if (dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
    }

    private fun copyWithoutOverwrite(target: File, source: File, installId: String): Boolean {
        if (target.exists() || Files.isSymbolicLink(target.toPath())) return false
        target.parentFile?.mkdirs()
        if (source.isDirectory) {
            target.mkdirs()
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { sourceFile ->
                    val targetFile = safeChildTarget(target, sourceFile.relativeTo(source).path)
                    ensureRealParentDirectories(targetFile, stopAt = target)
                    sourceFile.copyTo(targetFile, overwrite = false)
                }
            File(target, COPY_SENTINEL).writeText(installId)
        } else {
            source.copyTo(target, overwrite = false)
        }
        return true
    }

    private data class CopyBackupResult(
        val created: Int,
        val backedUp: Int,
        val manifests: List<ModOverwriteManifest>,
    )

    private data class CopyMissingResult(
        val created: Int,
        val skipped: Int,
    )

    private fun copyMissingFiles(target: File, source: File): CopyMissingResult {
        var created = 0
        var skipped = 0

        if (source.isDirectory) {
            if (Files.isSymbolicLink(target.toPath())) return CopyMissingResult(created, skipped + 1)
            if (!target.exists()) target.mkdirs()
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(source).path
                    val targetFile = safeChildTarget(target, relative)
                    if (targetFile.exists() || Files.isSymbolicLink(targetFile.toPath())) {
                        skipped++
                        return@forEach
                    }
                    ensureSpaceForCopy(existingSpaceRoot(targetFile), targetFile, file)
                    ensureRealParentDirectories(targetFile, stopAt = target)
                    file.copyTo(targetFile, overwrite = false)
                    created++
                }
            return CopyMissingResult(created, skipped)
        }

        if (!source.isFile) return CopyMissingResult(created, skipped + 1)
        if (target.exists() || Files.isSymbolicLink(target.toPath())) return CopyMissingResult(created, skipped + 1)
        ensureSpaceForCopy(existingSpaceRoot(target), target, source)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
        return CopyMissingResult(created + 1, skipped)
    }

    private fun copyWithBackups(
        install: ModInstall,
        target: File,
        source: File,
        backupRoot: File,
        allowOverwrite: Boolean,
        targetsWrittenThisApply: MutableSet<String>,
    ): CopyBackupResult {
        val targetKey = WindowsPathIdentity.absoluteKey(target)
        if (!allowOverwrite && source.isFile && targetNeedsOverwrite(target, source) && targetKey !in targetsWrittenThisApply) {
            throw IOException("Overwrite was not confirmed for ${target.absolutePath}")
        }
        if (!allowOverwrite && source.isDirectory && Files.isSymbolicLink(target.toPath())) {
            throw IOException("Overwrite was not confirmed for ${target.absolutePath}")
        }

        var created = 0
        var backedUp = 0
        val manifests = mutableListOf<ModOverwriteManifest>()

        if (source.isDirectory) {
            deleteTargetSymlinkIfPresent(target)
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(source).path
                    val targetFile = safeChildTarget(target, relative)
                    val currentTargetKey = WindowsPathIdentity.absoluteKey(targetFile)
                    val sameApplyTarget = currentTargetKey in targetsWrittenThisApply
                    if (!allowOverwrite && !sameApplyTarget && targetNeedsOverwrite(targetFile, file)) {
                        throw IOException("Overwrite was not confirmed for ${targetFile.absolutePath}")
                    }
                    val backup = if (sameApplyTarget) BackupIfNeededResult() else backupIfNeeded(install, targetFile, file, backupRoot)
                    if (backup.manifest != null) {
                        if (backup.backedUp) backedUp++
                        manifests += backup.manifest
                    }
                    if (backup.skipCopy) {
                        return@forEach
                    }
                    ensureSpaceForCopy(existingSpaceRoot(targetFile), targetFile, file)
                    deleteTargetSymlinkIfPresent(targetFile)
                    ensureRealParentDirectories(targetFile, stopAt = target)
                    file.copyTo(targetFile, overwrite = true)
                    targetsWrittenThisApply += currentTargetKey
                    created++
                    manifests.replaceLastForTarget(targetFile, install)
                }
        } else {
            val sameApplyTarget = targetKey in targetsWrittenThisApply
            val backup = if (sameApplyTarget) BackupIfNeededResult() else backupIfNeeded(install, target, source, backupRoot)
            if (backup.manifest != null) {
                if (backup.backedUp) {
                    backedUp++
                }
                manifests += backup.manifest
            }
            if (backup.skipCopy) {
                return CopyBackupResult(created, backedUp, manifests)
            }
            ensureSpaceForCopy(existingSpaceRoot(target), target, source)
            deleteTargetSymlinkIfPresent(target)
            ensureRealParentDirectories(target, stopAt = target.parentFile)
            source.copyTo(target, overwrite = true)
            targetsWrittenThisApply += targetKey
            created++
            manifests.replaceLastForTarget(target, install)
        }
        return CopyBackupResult(created, backedUp, manifests)
    }

    private fun MutableList<ModOverwriteManifest>.replaceLastForTarget(target: File, install: ModInstall) {
        val targetKey = WindowsPathIdentity.absoluteKey(target)
        val index = indexOfLast { WindowsPathIdentity.absoluteKey(File(it.targetPath)) == targetKey }
        if (index < 0 || !target.isFile) return
        val current = this[index]
        this[index] = current.copy(
            installedHash = sha256(target),
            installedSize = target.length(),
            installedMtime = target.lastModified(),
        )
    }

    private data class BackupIfNeededResult(
        val manifest: ModOverwriteManifest? = null,
        val backedUp: Boolean = false,
        val skipCopy: Boolean = false,
    )

    private fun backupIfNeeded(
        install: ModInstall,
        target: File,
        source: File,
        backupRoot: File,
    ): BackupIfNeededResult {
        if (Files.isSymbolicLink(target.toPath())) return BackupIfNeededResult()
        if (!target.exists() || !target.isFile) return BackupIfNeededResult()
        val targetHash = sha256(target)
        val sourceHash = sha256(source)
        val relative = target.absolutePath
            .replace(':', '_')
            .replace('\\', '/')
            .trimStart('/')
        val backup = File(File(backupRoot, install.installId), relative)
        if (sourceHash.isNotBlank() && targetHash == sourceHash) {
            return BackupIfNeededResult(
                manifest = if (backup.isFile) {
                    overwriteManifest(install, target, backup, sourceHash, source)
                } else if (install.status != ModInstallStatus.APPLIED.name) {
                    protectiveManifest(install, target, sourceHash, source)
                } else {
                    null
                },
                skipCopy = true,
            )
        }
        val backedUp = if (backup.isFile) {
            false
        } else {
            ensureSpace(backupRoot, target.length())
            backup.parentFile?.mkdirs()
            target.copyTo(backup, overwrite = false)
            true
        }
        return BackupIfNeededResult(
            manifest = overwriteManifest(install, target, backup, sourceHash, source),
            backedUp = backedUp,
            skipCopy = false,
        )
    }

    private fun overwriteManifest(
        install: ModInstall,
        target: File,
        backup: File,
        sourceHash: String,
        source: File,
    ): ModOverwriteManifest =
        ModOverwriteManifest(
            installId = install.installId,
            targetPath = target.absolutePath,
            backupPath = backup.absolutePath,
            originalHash = sha256(backup),
            originalSize = backup.length(),
            originalMtime = backup.lastModified(),
            installedHash = sourceHash,
            installedSize = source.length(),
            installedMtime = source.lastModified(),
        )

    private fun protectiveManifest(
        install: ModInstall,
        target: File,
        sourceHash: String,
        source: File,
    ): ModOverwriteManifest =
        ModOverwriteManifest(
            installId = install.installId,
            targetPath = target.absolutePath,
            backupPath = "",
            originalHash = sourceHash,
            originalSize = target.length(),
            originalMtime = target.lastModified(),
            installedHash = sourceHash,
            installedSize = source.length(),
            installedMtime = source.lastModified(),
        )

    private fun ensureSpaceForCopy(spaceRoot: File, target: File, source: File) {
        if (!source.isFile) return
        val extraBytes = when {
            target.isFile -> 0L
            else -> source.length()
        }
        if (extraBytes <= 0L) return
        ensureSpace(spaceRoot, extraBytes)
    }

    private fun ensureSpace(spaceRoot: File, extraBytes: Long) {
        if (spaceRoot.usableSpace < extraBytes + APPLY_FREE_SPACE_RESERVE_BYTES) {
            throw IOException("Storage is too low to safely apply mods. Free more space and retry.")
        }
    }

    private fun existingSpaceRoot(target: File): File =
        generateSequence(target.parentFile ?: target) { it.parentFile }
            .firstOrNull { it.exists() }
            ?: target

    private fun deleteTargetSymlinkIfPresent(target: File) {
        val path = target.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
        }
    }

    private fun ensureRealParentDirectories(target: File, stopAt: File?) {
        val parents = generateSequence(target.parentFile) { parent ->
            if (parent == stopAt) null else parent.parentFile
        }.toList().asReversed()
        parents.forEach { dir ->
            val path = dir.toPath()
            if (Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path)
            }
            if (!dir.exists()) {
                dir.mkdir()
            }
        }
    }

    private fun targetNeedsOverwrite(target: File, source: File): Boolean {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return false
        if (Files.isSymbolicLink(target.toPath())) {
            return resolveSymlinkTarget(target)?.canonicalFile != source.canonicalFile
        }
        if (!target.isFile || !source.isFile) return true
        if (target.length() != source.length()) return true
        return sha256(target) != sha256(source)
    }

    private fun safeChildTarget(root: File, relative: String): File {
        return ModTargetResolver.resolveWithin(root, relative)
            ?: throw IOException("Target path escapes or is ambiguous in destination directory: $relative")
    }

    private fun sha256(file: File): String {
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

    private fun targetMatchesApprovedState(target: File, manifest: ModOverwriteManifest): Boolean {
        if (!target.isFile || Files.isSymbolicLink(target.toPath())) return false
        val currentHash = sha256(target)
        return currentHash.isNotBlank() &&
            (currentHash == manifest.installedHash || currentHash == manifest.originalHash)
    }
}
