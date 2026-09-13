package app.gamenative.ui.component.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SnippetFolder
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModProfile
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.BethesdaPluginManager
import app.gamenative.mods.BethesdaPluginDependencyIssue
import app.gamenative.mods.AutomaticPlacementPlanner
import app.gamenative.mods.AutomaticPlacementResult
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ModDownloadInfo
import app.gamenative.mods.ModImportProgress
import app.gamenative.mods.ModPlacementPreset
import app.gamenative.mods.ModPlacementPresetDetector
import app.gamenative.mods.ModPlacementSources
import app.gamenative.mods.ModProfileOverlayTransition
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.NexusCollectionFile
import app.gamenative.mods.NexusModFile
import app.gamenative.mods.NexusModInfo
import app.gamenative.mods.ResolvedModTargetRoot
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal sealed interface CallbackWaitResult<out T> {
    data class Received<T>(val value: T) : CallbackWaitResult<T>
    data object Cancelled : CallbackWaitResult<Nothing>
}

internal suspend fun <T> awaitCallbackOrCancellation(
    callback: Deferred<T>,
    cancellationRequests: Flow<Boolean>,
    timeoutMillis: Long,
): CallbackWaitResult<T>? = withTimeoutOrNull(timeoutMillis) {
    coroutineScope {
        val cancellation = async(start = CoroutineStart.UNDISPATCHED) {
            cancellationRequests.first { it }
        }
        try {
            select {
                callback.onAwait { CallbackWaitResult.Received(it) }
                cancellation.onAwait { CallbackWaitResult.Cancelled }
            }
        } finally {
            cancellation.cancel()
        }
    }
}

internal fun ModInstall.canPlaceFiles(): Boolean =
    status == ModInstallStatus.READY.name ||
        status == ModInstallStatus.APPLIED.name ||
        status == ModInstallStatus.DISABLED.name

internal fun ModInstall.canRetryImport(): Boolean =
    status == ModInstallStatus.ERROR.name ||
        status == ModInstallStatus.PAUSED.name ||
        status == ModInstallStatus.CANCELED.name

internal fun BethesdaPluginDependencyIssue.hasBlockingIssue(): Boolean =
    missingMasters.isNotEmpty() || disabledMasters.isNotEmpty() || lateMasters.isNotEmpty()

internal fun List<BethesdaPluginDependencyIssue>.hasBlockingPluginIssues(): Boolean =
    any { it.hasBlockingIssue() }

internal fun ModInstall.errorMessage(): String =
    runCatching { JSONObject(metadataJson).optString("error") }.getOrDefault("")

internal fun isEnabledInProfile(
    install: ModInstall,
    enabledByInstallId: Map<String, Boolean>,
): Boolean =
    enabledByInstallId[install.installId] ?: false

internal fun ModInstall.metadataSummary(): String =
    runCatching { JSONObject(metadataJson).optString("summary") }.getOrDefault("")

internal fun ModInstall.profileStatus(enabledInProfile: Boolean): String =
    when {
        canPlaceFiles() && status != ModInstallStatus.DISABLED.name && !enabledInProfile -> "PROFILE_DISABLED"
        else -> status
    }

internal fun requiresManagedOverlayRebuild(
    configuredInstallIds: Set<String>,
    activeOwnershipInstallIds: Set<String>,
    transition: ModProfileOverlayTransition,
): Boolean =
    configuredInstallIds.isNotEmpty() &&
        configuredInstallIds.all(activeOwnershipInstallIds::contains) &&
        transition.requiresRebuild &&
        transition.safeToRebuild

internal fun shouldApplyProfileInstall(
    install: ModInstall,
    hasActiveOwnership: Boolean,
    hasConflict: Boolean,
    needsAssetRepair: Boolean,
    hasMissingTarget: Boolean,
): Boolean =
    install.status != ModInstallStatus.APPLIED.name ||
        (hasConflict && !hasActiveOwnership) ||
        needsAssetRepair ||
        hasMissingTarget

internal fun ModDownloadInfo.toImportProgress(): ModImportProgress =
    ModImportProgress(
        status = status,
        progress = progress,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
    )

internal val PendingCollectionMod.canImport: Boolean
    get() = modInfo != null && file != null

internal fun PendingCollectionMod.collectionKey(): String =
    "${collectionFile.gameDomain}:${collectionFile.modId}:${collectionFile.fileId}:${collectionFile.position}"

internal fun PendingCollectionMod.toQueueItem(
    status: CollectionQueueStatus,
    fallbackName: String,
): CollectionQueueItem =
    CollectionQueueItem(
        key = collectionKey(),
        name = modInfo?.name ?: collectionFile.modName.ifBlank { fallbackName },
        status = status,
    )

internal fun collectionQueueLabel(
    item: CollectionQueueItem,
    queuedLabel: String,
    importingLabel: String,
    importedLabel: String,
    failedLabel: String,
    canceledLabel: String,
    etaLeft: (String) -> String,
): String {
    val base = when (item.status) {
        CollectionQueueStatus.QUEUED -> item.message.ifBlank { queuedLabel }
        CollectionQueueStatus.IMPORTING -> {
            val percent = (item.progress * 100f).toInt().coerceIn(0, 100)
            val eta = collectionEta(item)
            "${item.message.ifBlank { importingLabel }} $percent%${if (eta.isNotBlank()) " - ${etaLeft(eta)}" else ""}"
        }
        CollectionQueueStatus.IMPORTED -> item.message.ifBlank { importedLabel }
        CollectionQueueStatus.FAILED -> item.error.ifBlank { failedLabel }
        CollectionQueueStatus.CANCELED -> canceledLabel
    }
    return "${item.name}: $base"
}

internal fun collectionEta(item: CollectionQueueItem): String {
    if (item.startedAt <= 0L || item.progress <= 0.02f || item.progress >= 1f) return ""
    val elapsedMs = System.currentTimeMillis() - item.startedAt
    if (elapsedMs <= 0L) return ""
    val remainingMs = (elapsedMs * ((1f - item.progress) / item.progress)).toLong()
    return formatDurationShort(remainingMs)
}

internal fun formatDurationShort(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(1L)
    val minutes = seconds / 60L
    val hours = minutes / 60L
    return when {
        hours > 0L -> "${hours}h ${minutes % 60L}m"
        minutes > 0L -> "${minutes}m ${seconds % 60L}s"
        else -> "${seconds}s"
    }
}

internal fun NexusCollectionFile.toFallbackNexusFile(): NexusModFile? {
    val resolvedFileName = fileName.ifBlank { return null }
    return NexusModFile(
        fileId = fileId,
        name = resolvedFileName,
        version = version,
        fileName = resolvedFileName,
        sizeBytes = sizeBytes,
        uploadedTimestamp = 0L,
        isPrimary = true,
    )
}

internal fun ModPlacementRecipe.toDraft(): RecipeDraft =
    RecipeDraft(
        sourceSubpath = sourceSubpath,
        targetRoot = targetRoot,
        targetRelativePath = targetRelativePath,
        targetFileName = targetFileName,
        mode = mode,
        stripPrefixSegments = stripPrefixSegments,
        includeSourceDirectory = includeSourceDirectory,
    )

internal fun RecipeDraft.toRecipe(installId: String): ModPlacementRecipe =
    ModPlacementRecipe(
        installId = installId,
        sourceSubpath = ModPlacementSources.encode(ModPlacementSources.decode(sourceSubpath)),
        targetRoot = targetRoot,
        targetRelativePath = normalizedTargetPath(),
        targetFileName = targetFileName,
        mode = mode,
        stripPrefixSegments = stripPrefixSegments,
        includeSourceDirectory = includeSourceDirectory,
    )

internal fun RecipeDraft.normalizedTargetPath(): String =
    if (targetRoot == ModTargetRoot.CUSTOM_ABSOLUTE.name) {
        targetRelativePath.trim().replace('\\', '/')
    } else {
        ModTargetResolver.normalizeRelativePath(targetRelativePath)
    }

internal fun nextProfileName(profiles: List<ModProfile>, prefix: String): String {
    val existing = profiles.map { it.name }.toSet()
    var index = profiles.size + 1
    while ("$prefix $index" in existing) index++
    return "$prefix $index"
}

internal fun automaticDraftsFor(
    gameName: String,
    entries: List<ModArchiveEntry>,
    fallback: RecipeDraft,
    selectedOptions: Map<String, String> = emptyMap(),
): List<RecipeDraft> {
    val result = AutomaticPlacementPlanner.plan(gameName, entries, selectedOptions)
    return automaticDraftsFor(result, gameName, entries, fallback)
}

internal fun NexusCollectionFile.toEmbeddedNexusModInfo(): NexusModInfo? {
    val resolvedModName = modName.ifBlank { return null }
    return NexusModInfo(
        modId = modId,
        name = resolvedModName,
        summary = "",
        version = version,
    )
}

internal fun automaticDraftsFor(
    result: AutomaticPlacementResult,
    gameName: String,
    entries: List<ModArchiveEntry>,
    fallback: RecipeDraft,
): List<RecipeDraft> {
    val recommendation = result.recommended
    if (recommendation != null) {
        return recommendation.drafts.map { draft ->
            fallback.copy(
                sourceSubpath = draft.sourceSubpath,
                targetRoot = draft.targetRoot,
                targetRelativePath = draft.targetRelativePath,
                mode = draft.mode,
                includeSourceDirectory = draft.includeSourceDirectory,
            )
        }
    }

    val bethesdaGame = BethesdaPluginManager.detectGame(gameName)
    if (bethesdaGame != null) {
        return listOf(
            fallback.copy(
                targetRoot = ModTargetRoot.GAME_DIR.name,
                targetRelativePath = bethesdaGame.dataDirName,
                mode = ModPlacementMode.OVERWRITE_COPY.name,
                includeSourceDirectory = false,
            ),
        )
    }

    return listOf(automaticDraftFor(entries, fallback))
}

internal fun placementPresetOptions(
    gameName: String,
    entries: List<ModArchiveEntry>,
    fallback: RecipeDraft,
): List<PlacementPresetOption> =
    ModPlacementPresetDetector.detect(gameName, entries).map { preset ->
        PlacementPresetOption(
            preset = preset,
            drafts = preset.drafts.map { draft ->
                fallback.copy(
                    sourceSubpath = draft.sourceSubpath,
                    targetRelativePath = draft.targetRelativePath,
                    mode = draft.mode,
                    includeSourceDirectory = draft.includeSourceDirectory,
                )
            },
        )
    }

internal fun automaticDraftFor(entries: List<ModArchiveEntry>, fallback: RecipeDraft): RecipeDraft {
    val normalizedEntries = entries.map { it.copy(path = normalizeArchivePath(it.path)) }

    fun findDirectoryPath(vararg suffixes: String): String? {
        val normalizedSuffixes = suffixes.map { normalizeArchivePath(it) }
        return normalizedEntries
            .asSequence()
            .map { it.path }
            .flatMap { path ->
                normalizedSuffixes.asSequence().mapNotNull { suffix ->
                    when {
                        path.equals(suffix, ignoreCase = true) -> path
                        path.endsWith("/$suffix", ignoreCase = true) -> path
                        path.startsWith("$suffix/", ignoreCase = true) -> suffix
                        path.contains("/$suffix/", ignoreCase = true) -> {
                            path.substringBefore("/$suffix/") + "/$suffix"
                        }
                        else -> null
                    }
                }
            }
            .firstOrNull()
    }

    val preset = listOf(
        "BepInEx/plugins" to "BepInEx/plugins",
        "BepInEx/patchers" to "BepInEx/patchers",
        "MelonLoader/Mods" to "Mods",
        "Content/Paks" to "Content/Paks",
        "Data" to "Data",
        "Mods" to "Mods",
    ).firstNotNullOfOrNull { (sourceSuffix, target) ->
        findDirectoryPath(sourceSuffix)?.let { sourcePath -> sourcePath to target }
    }

    return if (preset != null) {
        fallback.copy(
            sourceSubpath = preset.first,
            targetRelativePath = preset.second,
            includeSourceDirectory = false,
        )
    } else {
        fallback
    }
}

internal fun compatibleLastPlacementDrafts(
    drafts: List<RecipeDraft>,
    entries: List<ModArchiveEntry>,
    fallback: RecipeDraft,
): List<RecipeDraft> {
    if (drafts.isEmpty()) return listOf(automaticDraftFor(entries, fallback))
    val automaticSource = automaticDraftFor(entries, fallback).sourceSubpath
    return drafts.map { draft ->
        val sources = ModPlacementSources.decode(draft.sourceSubpath)
        val compatibleSources = sources.filter { source ->
            source.isBlank() || archiveContainsSource(entries, source)
        }
        val sourceSubpath = when {
            sources.all { it.isBlank() } -> ""
            compatibleSources.isNotEmpty() -> ModPlacementSources.encode(compatibleSources)
            else -> automaticSource
        }
        draft.copy(sourceSubpath = sourceSubpath)
    }
}

internal fun draftsWithUnresolvedSources(
    current: List<RecipeDraft>,
    unresolvedSources: List<String>,
    fallback: RecipeDraft,
): List<RecipeDraft> {
    val existingSources = current.flatMap { ModPlacementSources.decode(it.sourceSubpath) }.toSet()
    val unresolvedDrafts = unresolvedSources.distinct().filterNot { it in existingSources }.map { source ->
        fallback.copy(
            sourceSubpath = ModPlacementSources.encode(listOf(source)),
            targetRoot = "",
            targetRelativePath = "",
            includeSourceDirectory = false,
        )
    }
    return current + unresolvedDrafts
}

internal fun archiveContainsSource(entries: List<ModArchiveEntry>, source: String): Boolean {
    val normalizedSource = normalizeArchivePath(source)
    if (normalizedSource.isBlank()) return true
    return entries.any { entry ->
        val path = normalizeArchivePath(entry.path)
        path.equals(normalizedSource, ignoreCase = true) ||
            path.startsWith("$normalizedSource/", ignoreCase = true)
    }
}

internal fun archiveContainsFomodInstaller(entries: List<ModArchiveEntry>): Boolean =
    entries.any { entry ->
        val path = normalizeArchivePath(entry.path)
        path.endsWith("fomod/ModuleConfig.xml", ignoreCase = true)
    }

internal fun fomodImageFile(extractedRoot: File, imagePath: String): File? {
    val normalized = normalizeArchivePath(imagePath)
    if (normalized.isBlank()) return null
    val candidates = listOf(
        File(extractedRoot, normalized),
        File(extractedRoot, "fomod/$normalized"),
    )
    val root = runCatching { extractedRoot.canonicalFile }.getOrNull() ?: return null
    return candidates.firstNotNullOfOrNull { candidate ->
        val file = runCatching { candidate.canonicalFile }.getOrNull() ?: return@firstNotNullOfOrNull null
        val extension = file.extension.lowercase()
        file.takeIf {
            it.isFile &&
                (it == root || it.path.startsWith(root.path + File.separator)) &&
                extension in setOf("png", "jpg", "jpeg", "webp", "bmp")
        }
    }
}

internal fun sourceManualText(sourceSubpath: String): String =
    ModPlacementSources.decode(sourceSubpath)
        .filter { it.isNotBlank() }
        .joinToString("\n")

internal fun placementSummary(
    draft: RecipeDraft,
    roots: List<ResolvedModTargetRoot>,
    baseFolderLabel: String,
): String {
    val rootLabel = roots.firstOrNull { it.type.name == draft.targetRoot }?.label ?: draft.targetRoot
    val relative = draft.targetRelativePath.ifBlank { baseFolderLabel }
    return if (draft.targetRelativePath.isBlank()) {
        rootLabel
    } else {
        "$rootLabel / $relative"
    }
}

internal fun archiveChildren(entries: List<ModArchiveEntry>, currentPath: String): List<ArchiveBrowserItem> {
    val prefix = normalizeArchivePath(currentPath).let { if (it.isBlank()) "" else "$it/" }
    val directories = linkedMapOf<String, ArchiveBrowserItem>()
    val files = mutableListOf<ArchiveBrowserItem>()
    entries.forEach { entry ->
        val path = normalizeArchivePath(entry.path)
        if (path.isBlank() || (prefix.isNotBlank() && !path.startsWith(prefix, ignoreCase = true))) return@forEach
        val remaining = if (prefix.isBlank()) path else path.substring(prefix.length)
        if (remaining.isBlank()) return@forEach
        val name = remaining.substringBefore('/')
        val childPath = if (prefix.isBlank()) name else prefix + name
        if ('/' in remaining || entry.directory) {
            directories[childPath] = ArchiveBrowserItem(
                name = name,
                path = childPath,
                directory = true,
            )
        } else {
            files += ArchiveBrowserItem(
                name = name,
                path = childPath,
                directory = false,
                sizeBytes = entry.sizeBytes,
            )
        }
    }
    return directories.values.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
}

internal fun parentArchivePath(path: String): String =
    normalizeArchivePath(path).substringBeforeLast('/', missingDelimiterValue = "")

internal fun normalizeArchivePath(path: String): String =
    path.replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." }
        .joinToString("/")

internal fun File.isInsideOrEqual(root: File): Boolean =
    runCatching {
        val rootCanonical = root.canonicalFile
        val fileCanonical = canonicalFile
        fileCanonical == rootCanonical || fileCanonical.path.startsWith(rootCanonical.path + File.separator)
    }.getOrDefault(false)

internal fun targetRootIcon(root: ModTargetRoot): ImageVector = when (root) {
    ModTargetRoot.GAME_DIR -> Icons.Default.SportsEsports
    ModTargetRoot.WINE_C -> Icons.Default.Computer
    ModTargetRoot.DOCUMENTS -> Icons.Default.Description
    ModTargetRoot.MY_GAMES -> Icons.Default.Gamepad
    ModTargetRoot.APPDATA_ROAMING -> Icons.Default.Settings
    ModTargetRoot.APPDATA_LOCAL -> Icons.Default.SnippetFolder
    ModTargetRoot.APPDATA_LOCALLOW -> Icons.Default.Inventory2
    ModTargetRoot.CUSTOM_ABSOLUTE -> Icons.Default.Folder
}
