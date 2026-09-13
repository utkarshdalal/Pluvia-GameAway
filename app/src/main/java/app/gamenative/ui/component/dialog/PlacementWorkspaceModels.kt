package app.gamenative.ui.component.dialog

import app.gamenative.data.ModPlacementMode
import app.gamenative.mods.ModInstallPlan
import app.gamenative.mods.ModOwnershipManifest
import app.gamenative.mods.ModOwnershipState
import app.gamenative.mods.ModPlacementSources
import app.gamenative.mods.ModPlanChangeType
import app.gamenative.mods.ModReconfigurationDiff
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.PlannedFileStatus
import app.gamenative.mods.PlannedModFile
import app.gamenative.mods.ResolvedModTargetRoot
import app.gamenative.mods.WindowsPathIdentity
import java.io.File
import java.util.Locale

internal data class PlacementLayoutModel(
    val visible: Boolean,
    val editable: Boolean,
    val multipleFolders: Boolean,
    val selectedNames: List<String>,
    val resultExample: String,
    val duplicateFolderWarning: Boolean,
)

internal data class PlacementDraftPage(
    val pageIndex: Int,
    val pageCount: Int,
    val startIndex: Int,
    val endIndexExclusive: Int,
)

internal fun placementDraftPage(
    totalRules: Int,
    requestedPage: Int,
    pageSize: Int = 20,
): PlacementDraftPage {
    require(pageSize > 0)
    val safeTotal = totalRules.coerceAtLeast(0)
    val pageCount = maxOf(1, (safeTotal + pageSize - 1) / pageSize)
    val pageIndex = requestedPage.coerceIn(0, pageCount - 1)
    val startIndex = (pageIndex * pageSize).coerceAtMost(safeTotal)
    return PlacementDraftPage(
        pageIndex = pageIndex,
        pageCount = pageCount,
        startIndex = startIndex,
        endIndexExclusive = (startIndex + pageSize).coerceAtMost(safeTotal),
    )
}

internal fun placementLayoutModel(
    draft: RecipeDraft,
    entries: List<app.gamenative.mods.ModArchiveEntry>,
): PlacementLayoutModel {
    if (draft.targetFileName.isNotBlank()) {
        return PlacementLayoutModel(false, false, false, emptyList(), "", false)
    }
    val sources = ModPlacementSources.decode(draft.sourceSubpath).filter(String::isNotBlank)
    val selectingEverything = sources.isEmpty()
    val folders = sources.filter { source ->
        entries.any { entry ->
            val path = normalizeArchivePath(entry.path)
            path.equals(source, ignoreCase = true) && entry.directory ||
                path.startsWith("${normalizeArchivePath(source)}/", ignoreCase = true)
        }
    }
    val selectedNames = if (selectingEverything) {
        entries.mapNotNull { entry ->
            normalizeArchivePath(entry.path).substringBefore('/', "").takeIf(String::isNotBlank)
        }.distinctBy { it.lowercase(Locale.ROOT) }
    } else {
        folders.map { it.substringAfterLast('/') }.distinct()
    }
    val destination = draft.targetRelativePath.trim('/').ifBlank { "<game folder>" }
    val result = when {
        selectingEverything && selectedNames.size == 1 -> "$destination/${selectedNames.single()}/<contents>"
        selectingEverything ->
            "$destination/{${selectedNames.take(3).joinToString(", ")}" +
                "${if (selectedNames.size > 3) ", ..." else ""}}/<contents>"
        selectedNames.isEmpty() || !draft.includeSourceDirectory -> "$destination/<selected contents>"
        selectedNames.size == 1 -> "$destination/${selectedNames.single()}/<contents>"
        else ->
            "$destination/{${selectedNames.take(3).joinToString(", ")}" +
                "${if (selectedNames.size > 3) ", ..." else ""}}/<contents>"
    }
    return PlacementLayoutModel(
        visible = selectedNames.isNotEmpty(),
        editable = !selectingEverything,
        multipleFolders = selectedNames.size > 1,
        selectedNames = selectedNames,
        resultExample = result,
        duplicateFolderWarning = draft.includeSourceDirectory && selectedNames.any { selected ->
            destination.substringAfterLast('/').equals(selected, ignoreCase = true)
        },
    )
}

internal enum class DestinationEntryImpact {
    WILL_ADD,
    WILL_REPLACE,
    WILL_BACK_UP,
    PLAN_CONFLICT,
    MANAGED_BY_THIS_MOD,
    MANAGED_BY_ANOTHER_MOD,
    MODIFIED,
    GAME_OR_UNMANAGED,
    WILL_RECEIVE_FILES,
}

internal data class DestinationBrowserEntry(
    val file: File,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val impacts: Set<DestinationEntryImpact>,
    val ownerInstallIds: Set<String>,
    val virtual: Boolean = false,
)

internal fun destinationBrowserEntries(
    directory: File,
    root: ResolvedModTargetRoot,
    plan: ModInstallPlan?,
    ownership: List<ModOwnershipManifest>,
    selectedInstallId: String,
    showHidden: Boolean,
    query: String,
    virtualFolderName: String? = null,
): List<DestinationBrowserEntry> {
    val planFiles = plan?.files.orEmpty()
    val activeOwnership = ownership.filter { it.state == ModOwnershipState.ACTIVE }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val children = directory.listFiles().orEmpty()
        .asSequence()
        .filter { it.isInsideOrEqual(root.dir) }
        .filter { showHidden || !it.name.startsWith('.') }
        .filter { normalizedQuery.isBlank() || normalizedQuery in it.name.lowercase(Locale.ROOT) }
        .map { child ->
            destinationBrowserEntry(child, root, planFiles, activeOwnership, selectedInstallId)
        }
        .toMutableList()
    virtualFolderName?.takeIf { name ->
        name.isNotBlank() && (normalizedQuery.isBlank() || normalizedQuery in name.lowercase(Locale.ROOT))
    }?.let { name ->
        children += DestinationBrowserEntry(
            file = File(directory, name),
            directory = true,
            sizeBytes = 0L,
            modifiedAt = 0L,
            impacts = setOf(DestinationEntryImpact.WILL_RECEIVE_FILES),
            ownerInstallIds = emptySet(),
            virtual = true,
        )
    }
    return children.sortedWith(compareByDescending<DestinationBrowserEntry> { it.directory }.thenBy { it.file.name.lowercase(Locale.ROOT) })
}

private fun destinationBrowserEntry(
    file: File,
    root: ResolvedModTargetRoot,
    planFiles: List<PlannedModFile>,
    ownership: List<ModOwnershipManifest>,
    selectedInstallId: String,
): DestinationBrowserEntry {
    val relative = runCatching {
        file.canonicalFile.relativeTo(root.dir.canonicalFile).path.replace(File.separatorChar, '/')
    }.getOrDefault("")
    val logicalKey = ModTargetResolver.normalizedTargetKey(root.type.name, relative)
    val absoluteKey = WindowsPathIdentity.absoluteKey(file)
    val exactPlan = logicalKey?.let { key ->
        planFiles.filter { it.normalizedTargetKey == key }
    }.orEmpty()
    val directoryPrefix = logicalKey?.let { "$it/" }
    val receivesFiles = file.isDirectory && directoryPrefix != null && planFiles.any {
        it.status == PlannedFileStatus.PLACED && it.normalizedTargetKey?.startsWith(directoryPrefix) == true
    }
    val ownedFiles = ownership.flatMap { manifest ->
        manifest.files.filter { it.active && it.normalizedTargetKey == absoluteKey }.map { manifest.installId to it }
    }
    val impacts = linkedSetOf<DestinationEntryImpact>()
    if (receivesFiles) impacts += DestinationEntryImpact.WILL_RECEIVE_FILES
    if (exactPlan.any { it.status == PlannedFileStatus.CONFLICTED }) impacts += DestinationEntryImpact.PLAN_CONFLICT
    exactPlan.filter { it.status == PlannedFileStatus.PLACED }.forEach { planned ->
        if (!file.exists()) {
            impacts += DestinationEntryImpact.WILL_ADD
        } else {
            impacts += DestinationEntryImpact.WILL_REPLACE
            if (planned.mode == ModPlacementMode.OVERWRITE_COPY.name) impacts += DestinationEntryImpact.WILL_BACK_UP
        }
    }
    if (ownedFiles.any { it.first == selectedInstallId }) impacts += DestinationEntryImpact.MANAGED_BY_THIS_MOD
    if (ownedFiles.any { it.first != selectedInstallId }) impacts += DestinationEntryImpact.MANAGED_BY_ANOTHER_MOD
    if (ownedFiles.any { (_, owned) -> file.isFile && (file.length() != owned.installedSize || file.lastModified() != owned.installedMtime) }) {
        impacts += DestinationEntryImpact.MODIFIED
    }
    if (file.isFile && ownedFiles.isEmpty() && exactPlan.isEmpty()) impacts += DestinationEntryImpact.GAME_OR_UNMANAGED
    return DestinationBrowserEntry(
        file = file,
        directory = file.isDirectory,
        sizeBytes = if (file.isFile) file.length() else 0L,
        modifiedAt = file.lastModified(),
        impacts = impacts,
        ownerInstallIds = ownedFiles.mapTo(linkedSetOf()) { it.first },
    )
}

internal fun validVirtualDestinationFolderName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() &&
        '/' !in name && '\\' !in name &&
        WindowsPathIdentity.normalizedRelativeKey(name) != null
}

internal fun validVirtualDestinationFolder(parent: File?, value: String): Boolean {
    if (parent == null || !validVirtualDestinationFolderName(value)) return false
    val candidate = File(parent, value.trim())
    return !candidate.exists() || candidate.isDirectory
}

internal enum class PlacementReviewCategory {
    ADDED,
    REPLACED,
    MOVED,
    REMOVED,
    IGNORED,
    BLOCKED,
    UNCHANGED,
}

internal data class PlacementReviewRow(
    val category: PlacementReviewCategory,
    val source: String,
    val previousTarget: String = "",
    val target: String = "",
    val targetRoot: String = "",
    val targetRelativePath: String = "",
    val reason: String = "",
    val sizeBytes: Long = 0L,
) {
    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase(Locale.ROOT)
        return needle.isBlank() || listOf(source, previousTarget, target, reason).any { needle in it.lowercase(Locale.ROOT) }
    }
}

internal fun placementReviewRows(
    plan: ModInstallPlan,
    diff: ModReconfigurationDiff?,
    roots: List<ResolvedModTargetRoot>,
    staleReason: String,
): List<PlacementReviewRow> {
    val changesBySource = diff?.changes.orEmpty().groupBy { it.sourceRelativePath }
    val rows = plan.files.map { file ->
        val change = changesBySource[file.sourceRelativePath].orEmpty().firstOrNull { it.type != ModPlanChangeType.UNCHANGED }
        val category = when (file.status) {
            PlannedFileStatus.INTENTIONALLY_IGNORED -> PlacementReviewCategory.IGNORED
            PlannedFileStatus.UNSUPPORTED, PlannedFileStatus.MISSING, PlannedFileStatus.CONFLICTED -> PlacementReviewCategory.BLOCKED
            PlannedFileStatus.PLACED -> when (change?.type) {
                ModPlanChangeType.MOVED -> PlacementReviewCategory.MOVED
                ModPlanChangeType.CHANGED -> PlacementReviewCategory.REPLACED
                ModPlanChangeType.STALE -> PlacementReviewCategory.REMOVED
                ModPlanChangeType.ADDED -> if (plannedTargetExists(file, roots)) PlacementReviewCategory.REPLACED else PlacementReviewCategory.ADDED
                ModPlanChangeType.UNCHANGED, null -> if (plannedTargetExists(file, roots) && diff == null) {
                    PlacementReviewCategory.REPLACED
                } else {
                    PlacementReviewCategory.UNCHANGED
                }
            }
        }
        PlacementReviewRow(
            category = category,
            source = file.sourceRelativePath,
            previousTarget = change?.previousTarget.orEmpty(),
            target = file.targetDisplay(),
            targetRoot = file.targetRoot.orEmpty(),
            targetRelativePath = file.targetRelativePath.orEmpty(),
            reason = file.reason,
            sizeBytes = file.sizeBytes,
        )
    }.toMutableList()
    diff?.changes.orEmpty().filter { it.type == ModPlanChangeType.STALE }.forEach { change ->
        rows += PlacementReviewRow(
            category = PlacementReviewCategory.REMOVED,
            source = change.sourceRelativePath,
            previousTarget = change.previousTarget,
            reason = staleReason,
        )
    }
    return rows.sortedWith(compareBy<PlacementReviewRow> { it.category.ordinal }.thenBy { it.source.lowercase(Locale.ROOT) })
}

private fun plannedTargetExists(file: PlannedModFile, roots: List<ResolvedModTargetRoot>): Boolean {
    val root = roots.firstOrNull { it.type.name == file.targetRoot } ?: return false
    val relative = file.targetRelativePath ?: return false
    return app.gamenative.mods.ModTargetResolver.resolveWithin(root.dir, relative)?.exists() == true
}

private fun PlannedModFile.targetDisplay(): String =
    listOfNotNull(targetRoot, targetRelativePath).filter(String::isNotBlank).joinToString("/")
