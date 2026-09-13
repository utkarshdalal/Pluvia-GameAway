package app.gamenative.ui.component.dialog

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import app.gamenative.R
import app.gamenative.data.LibraryItem
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.BethesdaPlacementRecipeExpander
import app.gamenative.mods.BethesdaGame
import app.gamenative.mods.BethesdaPlugin
import app.gamenative.mods.BethesdaPluginAssetIssue
import app.gamenative.mods.BethesdaPluginDependencyIssue
import app.gamenative.mods.BethesdaPluginManager
import app.gamenative.mods.AuthorizedNexusWebsiteDownload
import app.gamenative.mods.AutomaticPlacementPlanner
import app.gamenative.mods.AutomaticPlacementCandidate
import app.gamenative.mods.AutomaticPlacementContext
import app.gamenative.mods.AutomaticPlacementResult
import app.gamenative.mods.BrowserFirstNexusWebsiteDownload
import app.gamenative.mods.FomodInstaller
import app.gamenative.mods.FomodEnvironmentSnapshot
import app.gamenative.mods.FomodEnvironmentSnapshotBuilder
import app.gamenative.mods.FomodAutoSelector
import app.gamenative.mods.FomodInstallerDetector
import app.gamenative.mods.FomodParser
import app.gamenative.mods.DuplicateLocalModContentException
import app.gamenative.mods.LocalModImporter
import app.gamenative.mods.LocalModSourceSelection
import app.gamenative.mods.LocalModSourceType
import app.gamenative.mods.truncateAtCodePointBoundary
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ModArchiveInstallAssessor
import app.gamenative.mods.ModConflictAnalyzer
import app.gamenative.mods.ModConfigurationDraft
import app.gamenative.mods.ModConfigurationDraftStore
import app.gamenative.mods.ModConfigurationRecipe
import app.gamenative.mods.ModDownloadInfo
import app.gamenative.mods.ModDownloadRegistry
import app.gamenative.mods.ModDeploymentCoordinator
import app.gamenative.mods.ModFileConflictReport
import app.gamenative.mods.ModHealthAction
import app.gamenative.mods.ModHealthReport
import app.gamenative.mods.ModHealthSeverity
import app.gamenative.mods.ModImportProgress
import app.gamenative.mods.ModInstallPlan
import app.gamenative.mods.ModMaterializer
import app.gamenative.mods.ModOwnershipManifest
import app.gamenative.mods.ModOwnershipStore
import app.gamenative.mods.PlannedFileStatus
import app.gamenative.mods.PlacementRiskPolicy
import app.gamenative.mods.ModPathDetector
import app.gamenative.mods.ModPlacementConflict
import app.gamenative.mods.ModPlacementPreset
import app.gamenative.mods.ModPlacementSources
import app.gamenative.mods.ModProfileManager
import app.gamenative.mods.ModStorageBreakdown
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.NexusApiClient
import app.gamenative.mods.NexusApiErrorReason
import app.gamenative.mods.NexusApiException
import app.gamenative.mods.NexusAuthManager
import app.gamenative.mods.NexusAuthError
import app.gamenative.mods.NexusAuthState
import app.gamenative.mods.NexusConnectionState
import app.gamenative.mods.NexusCollectionFile
import app.gamenative.mods.NexusCollectionInfo
import app.gamenative.mods.NexusCollectionPrioritySuggester
import app.gamenative.mods.NexusCollectionReusePolicy
import app.gamenative.mods.NexusCollectionUrlParser
import app.gamenative.mods.NexusDownloadLinkInbox
import app.gamenative.mods.NexusImportState
import app.gamenative.mods.NexusIntegrationStatus
import app.gamenative.mods.NexusModFile
import app.gamenative.mods.NexusModInfo
import app.gamenative.mods.NexusModManager
import app.gamenative.mods.NexusModReference
import app.gamenative.mods.NexusPendingDownloadStore
import app.gamenative.mods.NexusUserInfo
import app.gamenative.mods.PendingNexusWebsiteDownload
import app.gamenative.mods.NexusUrlParser
import app.gamenative.mods.isPastPendingTtl
import app.gamenative.mods.reviewedPlanOrNull
import app.gamenative.service.NexusModImportService
import app.gamenative.ui.screen.auth.NexusOAuthBrowserLauncher
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
internal data class RecipeDraft(
    val sourceSubpath: String = "",
    val targetRoot: String = ModTargetRoot.GAME_DIR.name,
    val targetRelativePath: String = "",
    val targetFileName: String = "",
    val mode: String = ModPlacementMode.SYMLINK.name,
    val stripPrefixSegments: Int = 0,
    val includeSourceDirectory: Boolean = false,
)

internal enum class PlacementChoice {
    AUTOMATIC,
    PRESET,
    LAST_USED,
    CUSTOM,
}

private data class PlacementPlanPreview(
    val installId: String,
    val drafts: List<RecipeDraft>,
    val plan: ModInstallPlan,
)

private enum class ManageModsTab {
    IMPORT,
    MODS,
    PLACEMENT,
    ISSUES,
}

private const val MIN_APPLY_FREE_BYTES = 2L * 1024L * 1024L * 1024L
private const val WEBSITE_AUTHORIZATION_TIMEOUT_MS = 15L * 60L * 1000L

private class NexusWebsiteAuthorizationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class PendingFileSelection(
    val reference: NexusModReference,
    val modInfo: NexusModInfo,
    val files: List<NexusModFile>,
)

internal sealed interface BrowserFirstNexusResolution {
    data class Resolved(
        val reference: NexusModReference,
        val modInfo: NexusModInfo,
        val file: NexusModFile,
    ) : BrowserFirstNexusResolution

    data object Expired : BrowserFirstNexusResolution
    data object WrongAccount : BrowserFirstNexusResolution
    data object MissingFile : BrowserFirstNexusResolution
    data object Invalid : BrowserFirstNexusResolution
}

/**
 * Derives the download identity directly from the current OAuth state.
 *
 * Keeping a separately remembered user can leak account A's ID or membership tier into account B
 * after an automatic invalid-grant disconnect and reconnect while this dialog remains composed.
 */
internal fun NexusAuthState.currentNexusUserInfo(): NexusUserInfo? {
    if (!isConnected) return null
    val currentAccount = account ?: return null
    val userId = currentAccount.id.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return NexusUserInfo(
        name = currentAccount.name,
        userId = userId,
        isPremium = currentAccount.isPremium,
    )
}

/** Reads the account at execution time so a retained callback cannot reuse an older session. */
internal suspend fun currentNexusUserForDownload(
    reference: NexusModReference,
    getCurrentUser: suspend () -> NexusUserInfo,
): NexusUserInfo? {
    val currentUser = getCurrentUser()
    val authorizationUserId = reference.downloadAuthorization?.userId
    return currentUser.takeIf { authorizationUserId == null || authorizationUserId == currentUser.userId }
}

internal suspend fun resolveBrowserFirstNexusDownload(
    reference: NexusModReference,
    getCurrentUser: suspend () -> NexusUserInfo,
    getModInfo: suspend (String, Long) -> NexusModInfo,
    getModFiles: suspend (String, Long) -> List<NexusModFile>,
    nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
): BrowserFirstNexusResolution {
    val fileId = reference.fileId?.takeIf { it > 0L } ?: return BrowserFirstNexusResolution.Invalid
    val authorization = reference.downloadAuthorization ?: return BrowserFirstNexusResolution.Invalid
    val authorizationUserId = authorization.userId?.takeIf { it > 0L }
        ?: return BrowserFirstNexusResolution.Invalid
    if (authorization.isExpired(nowEpochSeconds())) return BrowserFirstNexusResolution.Expired

    val user = getCurrentUser()
    if (user.userId <= 0L) return BrowserFirstNexusResolution.Invalid
    if (user.userId != authorizationUserId) return BrowserFirstNexusResolution.WrongAccount

    val modInfo = getModInfo(reference.gameDomain, reference.modId)
    val file = getModFiles(reference.gameDomain, reference.modId)
        .firstOrNull { it.fileId == fileId }
        ?: return BrowserFirstNexusResolution.MissingFile
    if (authorization.isExpired(nowEpochSeconds())) return BrowserFirstNexusResolution.Expired
    val currentUser = getCurrentUser()
    if (currentUser.userId != authorizationUserId) return BrowserFirstNexusResolution.WrongAccount
    return BrowserFirstNexusResolution.Resolved(reference, modInfo, file)
}

internal data class PendingLocalModImport(
    val source: LocalModSourceSelection,
    val installId: String? = null,
    val modName: String,
    val version: String = "",
    val estimatedRequiredBytes: Long,
    val availableBytes: Long,
)

internal data class PendingCollectionSelection(
    val collection: NexusCollectionInfo,
    val mods: List<PendingCollectionMod>,
)

internal data class PendingCollectionMod(
    val collectionFile: NexusCollectionFile,
    val modInfo: NexusModInfo?,
    val file: NexusModFile?,
    val error: String? = null,
)

internal enum class CollectionQueueStatus {
    QUEUED,
    IMPORTING,
    IMPORTED,
    FAILED,
    CANCELED,
}

internal data class CollectionQueueItem(
    val key: String,
    val name: String,
    val status: CollectionQueueStatus,
    val progress: Float = 0f,
    val message: String = "",
    val error: String = "",
    val startedAt: Long = 0L,
)

internal data class PendingFomodResult(
    val drafts: List<RecipeDraft>,
    val plan: ModInstallPlan?,
    val unsupportedCount: Int,
    val unresolvedDetails: List<String>,
    val blockingIssues: List<String>,
    val selectedOptions: List<String>,
    val conditionalRuleCount: Int,
)

internal data class PlacementApplyFailure(
    val installId: String,
    val installName: String,
    val errors: Map<String, String>,
)

internal data class PendingApply(
    val install: ModInstall,
    val recipes: List<ModPlacementRecipe>,
    val conflicts: List<ModPlacementConflict>,
    val reviewedPlan: ModInstallPlan? = null,
)

internal data class PendingProfileApply(
    val conflicts: List<ModPlacementConflict>,
)

internal data class PendingProfileNameEdit(
    val profile: ModProfile?,
    val initialName: String,
)

internal data class ArchiveBrowserItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long = 0L,
)

internal data class PlacementPresetOption(
    val preset: ModPlacementPreset,
    val drafts: List<RecipeDraft>,
)

private data class ModDiagnosticsSnapshot(
    val conflicts: List<ModFileConflictReport>,
    val placementNeededInstallIds: Set<String>,
    val bethesdaGame: BethesdaGame?,
    val plugins: List<BethesdaPlugin>,
    val pluginIssues: List<BethesdaPluginDependencyIssue>,
    val pluginAssetIssues: List<BethesdaPluginAssetIssue>,
)

private data class ProfileOrderPlan(
    val profileId: String,
    val stateByInstallId: Map<String, ModProfileInstallState>,
    val disabledInstalls: List<ModInstall>,
    val configuredInstalls: List<ModInstall>,
    val installsToApply: List<ModInstall>,
    val rebuildManagedOverlay: Boolean,
    val overlayTransitionBlockerCount: Int,
    val missingTargetRepairInstallIds: Set<String>,
    val recipesByInstallId: Map<String, List<ModPlacementRecipe>>,
    val ownershipByInstallId: Map<String, ModOwnershipManifest>,
    val reviewedPlansByInstallId: Map<String, ModInstallPlan>,
    val recipesToPersistByInstallId: Map<String, List<ModPlacementRecipe>>,
    val unconfiguredCount: Int,
    val unconfiguredNames: List<String>,
    val bethesdaGame: BethesdaGame?,
    val plugins: List<BethesdaPlugin>,
    val pluginIssues: List<BethesdaPluginDependencyIssue>,
    val pluginAssetIssues: List<BethesdaPluginAssetIssue>,
)

private data class ProfileOrderConflictCheck(
    val rawConflicts: List<ModPlacementConflict>,
    val conflicts: List<ModPlacementConflict>,
    val hasOverwriteRecipe: Boolean,
)

private data class ProfileOrderApplyResult(
    val errors: Int,
    val bethesdaGame: BethesdaGame?,
    val plugins: List<BethesdaPlugin>,
    val pluginIssues: List<BethesdaPluginDependencyIssue>,
    val pluginAssetIssues: List<BethesdaPluginAssetIssue>,
    val disabledSkipped: Int = 0,
)

@Composable
private fun NexusDialogSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = data.visuals.message,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ManageModsSummaryBar(
    installs: List<ModInstall>,
    enabledByInstallId: Map<String, Boolean>,
    activeProfile: ModProfile?,
    activeDownload: ModDownloadInfo?,
    issueCount: Int,
    diagnosticsLoading: Boolean,
    busyText: String?,
    modifier: Modifier = Modifier,
) {
    val placeable = installs.filter { it.canPlaceFiles() }
    val enabledCount = placeable.count {
        it.status == ModInstallStatus.APPLIED.name && isEnabledInProfile(it, enabledByInstallId)
    }
    val queueText = activeDownload?.status ?: when {
        busyText != null -> busyText
        diagnosticsLoading -> stringResource(R.string.nexus_scanning)
        issueCount > 0 -> stringResource(R.string.nexus_issue_count, issueCount)
        else -> stringResource(R.string.nexus_idle)
    }
    val summary = stringResource(
        R.string.nexus_summary_bar,
        placeable.size,
        enabledCount,
        activeProfile?.name ?: stringResource(R.string.nexus_default_profile),
        queueText,
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ManageModsTabs(
    selectedTab: ManageModsTab,
    onSelect: (ManageModsTab) -> Unit,
) {
    val tabs = ManageModsTab.entries
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        TabRow(
            selectedTabIndex = tabs.indexOf(selectedTab),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { onSelect(tab) },
                    text = {
                        Text(
                            text = when (tab) {
                                ManageModsTab.IMPORT -> stringResource(R.string.nexus_tab_import)
                                ManageModsTab.MODS -> stringResource(R.string.nexus_tab_mods)
                                ManageModsTab.PLACEMENT -> stringResource(if (compact) R.string.nexus_tab_placement_short else R.string.nexus_tab_placement)
                                ManageModsTab.ISSUES -> stringResource(R.string.nexus_tab_issues)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NexusSectionCard(verticalSpacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(verticalSpacing), content = content)
    }
}

@Composable
private fun NexusSectionHeader(title: String, loading: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        TextButton(onClick = onAction, enabled = !loading) { Text(actionLabel) }
    }
}

@Composable
private fun OverwriteConfirmDialog(
    title: String,
    message: String,
    conflicts: List<ModPlacementConflict>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(message)
                conflicts.take(12).forEach { conflict ->
                    Text(
                        text = conflict.targetPath.replace(File.separatorChar, '/'),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (conflicts.size > 12) Text(stringResource(R.string.nexus_more_prefixed, conflicts.size - 12))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun applyCollectionPluginOrder(
    plugins: List<BethesdaPlugin>,
    pluginLoadOrder: List<String>,
): List<BethesdaPlugin> {
    if (pluginLoadOrder.isEmpty() || plugins.isEmpty()) return plugins
    val orderByName = pluginLoadOrder
        .mapIndexed { index, name -> name.trim().removePrefix("*").lowercase() to index }
        .toMap()
    return plugins
        .sortedWith(
            compareBy<BethesdaPlugin> { orderByName[it.fileName.lowercase()] ?: Int.MAX_VALUE }
                .thenBy { it.orderIndex }
                .thenBy { it.priority }
                .thenBy { it.fileName.lowercase() },
        )
        .mapIndexed { index, plugin -> plugin.copy(orderIndex = index) }
}

@Composable
private fun EmptyWorkflowSection(
    title: String,
    subtitle: String,
) {
    NexusSectionCard(verticalSpacing = 6.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageCleanupSection(
    breakdown: ModStorageBreakdown?,
    loading: Boolean,
    onScan: () -> Unit,
    onCleanTemp: () -> Unit,
    onDeleteFailedArchives: () -> Unit,
    onCleanRedundantBackups: () -> Unit,
) {
    fun size(bytes: Long) = StorageUtils.formatBinarySize(bytes)
    @Composable
    fun Line(label: String, bytes: Long) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(size(bytes), fontWeight = FontWeight.SemiBold)
        }
    }
    val cleanable = breakdown?.cleanableBytes ?: 0L
    val failedArchives = breakdown?.failedArchiveBytes ?: 0L
    val redundantBackupCount = breakdown?.redundantBackupCount ?: 0
    val redundantBackupLabel = if (redundantBackupCount > 0) {
        stringResource(R.string.nexus_backups_safe_to_clean_records, redundantBackupCount)
    } else {
        stringResource(R.string.nexus_backups_safe_to_clean)
    }
    NexusSectionCard {
        NexusSectionHeader(stringResource(R.string.nexus_storage_cleanup_title), loading, stringResource(R.string.nexus_scan), onScan)
        Text(
            stringResource(R.string.nexus_storage_cleanup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.nexus_storage_cleanup_cache_backups_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.nexus_storage_cleanup_actions_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Line(stringResource(R.string.nexus_temp_orphaned_files), cleanable)
        Line(stringResource(R.string.nexus_failed_download_archives), failedArchives)
        breakdown?.let {
            Line(stringResource(R.string.nexus_extracted_mod_cache), it.extractedCacheBytes)
            Line(stringResource(R.string.nexus_rollback_backups), it.backupBytes)
            Line(redundantBackupLabel, it.redundantBackupBytes)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCleanTemp, enabled = !loading && cleanable > 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nexus_clean_temp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDeleteFailedArchives, enabled = !loading && failedArchives > 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nexus_delete_failed_archives), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCleanTemp, enabled = !loading && cleanable > 0L, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.nexus_clean_temp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onDeleteFailedArchives, enabled = !loading && failedArchives > 0L, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.nexus_delete_failed_archives), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        TextButton(
            onClick = onCleanRedundantBackups,
            enabled = !loading && redundantBackupCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.nexus_clean_redundant_backups), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InstallHealthSection(
    report: ModHealthReport?,
    loading: Boolean,
    onCheck: () -> Unit,
    onRebuild: () -> Unit,
    onReconfigure: (String) -> Unit,
    onAdoptOwnership: (String) -> Unit,
    onRestorePrevious: (String) -> Unit,
    onExport: (ModHealthReport) -> Unit,
) {
    NexusSectionCard {
        NexusSectionHeader(stringResource(R.string.nexus_install_health_title), loading, stringResource(R.string.nexus_check), onCheck)
        Text(
            stringResource(R.string.nexus_install_health_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report?.let { current ->
            if (current.issues.isEmpty()) {
                Text(stringResource(R.string.nexus_no_install_health_issues), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                val summaryColor = if (current.errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.nexus_install_health_summary, current.errorCount, current.warningCount), style = MaterialTheme.typography.bodySmall, color = summaryColor)
                if (
                    current.issues.any {
                        it.recommendedAction == ModHealthAction.REAPPLY_MISSING ||
                            it.recommendedAction == ModHealthAction.REBUILD_PROFILE
                    }
                ) {
                    OutlinedButton(onClick = onRebuild, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nexus_apply_order))
                    }
                }
                OutlinedButton(onClick = { onExport(current) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nexus_plan_export))
                }
                current.issues.take(8).forEach { issue ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val titleColor = if (issue.severity == ModHealthSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Text(listOf(issue.installName, issue.title).filter(String::isNotBlank).joinToString(": "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = titleColor)
                        Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (issue.installId.isNotBlank()) {
                            TextButton(
                                enabled = !loading,
                                onClick = {
                                    when (issue.recommendedAction) {
                                        ModHealthAction.REAPPLY_MISSING,
                                        ModHealthAction.REBUILD_PROFILE,
                                        -> onRebuild()
                                        ModHealthAction.ADOPT_OWNERSHIP -> onAdoptOwnership(issue.installId)
                                        ModHealthAction.RESTORE_PREVIOUS -> onRestorePrevious(issue.installId)
                                        ModHealthAction.RECONFIGURE,
                                        ModHealthAction.REVIEW_PLACEMENT,
                                        -> onReconfigure(issue.installId)
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        when (issue.recommendedAction) {
                                            ModHealthAction.REAPPLY_MISSING -> R.string.nexus_reapply_missing_files
                                            ModHealthAction.REBUILD_PROFILE -> R.string.nexus_apply_order
                                            ModHealthAction.ADOPT_OWNERSHIP -> R.string.nexus_adopt_ownership
                                            ModHealthAction.RESTORE_PREVIOUS -> R.string.nexus_restore_previous_deployment
                                            ModHealthAction.RECONFIGURE -> R.string.nexus_configure
                                            ModHealthAction.REVIEW_PLACEMENT -> R.string.nexus_review_placement
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
                if (current.issues.size > 8) {
                    Text(stringResource(R.string.nexus_more_prefixed, current.issues.size - 8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PlacementApplyFailureSection(
    failure: PlacementApplyFailure,
    onReconfigure: () -> Unit,
) {
    NexusSectionCard {
        Text(
            stringResource(R.string.nexus_apply_failure_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(failure.installName, style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.nexus_apply_failure_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlacementApplyFailureDetails(failure.errors)
        OutlinedButton(onClick = onReconfigure, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.nexus_review_placement))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NexusModsDialog(
    visible: Boolean,
    libraryItem: LibraryItem,
    gameRootDir: File?,
    winePrefix: String,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarController = LocalSnackbarHostController.current
    val snackbarOwner = remember { Any() }
    DisposableEffect(snackbarController, snackbarOwner) {
        snackbarController.register(snackbarOwner)
        onDispose { snackbarController.unregister(snackbarOwner) }
    }
    val dao = remember(context) { NexusModManager.dao(context) }
    val installs by dao.observeInstallsForApp(libraryItem.appId).collectAsState(initial = emptyList())
    val activeDownloads by ModDownloadRegistry.observeDownloads().collectAsState()
    val activeDownload = activeDownloads.values.firstOrNull { it.appId == libraryItem.appId }
    val activeImportProgress = activeDownload?.toImportProgress()
    val profiles by dao.observeProfilesForApp(libraryItem.appId).collectAsState(initial = emptyList())
    val activeProfile = profiles.firstOrNull { it.active }
    val profileStateFlow = remember(libraryItem.appId, activeProfile?.profileId) {
        activeProfile?.let { dao.observeProfileInstallStates(libraryItem.appId, it.profileId) }
            ?: flowOf(emptyList())
    }
    val profileStates by profileStateFlow.collectAsState(initial = emptyList())
    val priorityByInstallId = remember(profileStates) { profileStates.associate { it.installId to it.priority } }
    val profileEnabledByInstallId = remember(profileStates) { profileStates.associate { it.installId to it.enabled } }
    val nexusAuthState by NexusAuthManager.state.collectAsState()
    val apiClient = remember { NexusApiClient() }
    val roots = remember(gameRootDir, winePrefix, context) {
        ModTargetResolver.roots(gameRootDir, winePrefix).ifEmpty {
            listOfNotNull(gameRootDir?.takeIf { it.isDirectory }?.let {
                app.gamenative.mods.ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, context.getString(R.string.nexus_game_directory_root), it)
            })
        }
    }
    val fallbackDefaultDraft = remember(roots) {
        RecipeDraft(targetRoot = roots.firstOrNull()?.type?.name ?: ModTargetRoot.GAME_DIR.name)
    }

    val nexusUserInfo = nexusAuthState.currentNexusUserInfo()
    var nexusUrl by remember { mutableStateOf("") }
    var loadingMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var importProgress by remember { mutableStateOf<ModImportProgress?>(null) }
    var selectedInstall by remember { mutableStateOf<ModInstall?>(null) }
    var archiveEntries by remember { mutableStateOf<List<ModArchiveEntry>>(emptyList()) }
    var selectedFomodInstaller by remember { mutableStateOf<FomodInstaller?>(null) }
    var fomodEnvironment by remember { mutableStateOf(FomodEnvironmentSnapshot()) }
    var conflictReports by remember { mutableStateOf<List<ModFileConflictReport>>(emptyList()) }
    var placementNeededInstallIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bethesdaGame by remember(libraryItem.name) { mutableStateOf(BethesdaPluginManager.detectGame(libraryItem.name)) }
    var bethesdaPlugins by remember { mutableStateOf<List<BethesdaPlugin>>(emptyList()) }
    var bethesdaPluginIssues by remember { mutableStateOf<List<BethesdaPluginDependencyIssue>>(emptyList()) }
    var bethesdaPluginAssetIssues by remember { mutableStateOf<List<BethesdaPluginAssetIssue>>(emptyList()) }
    var diagnosticsLoading by remember { mutableStateOf(false) }
    var pendingFileSelection by remember { mutableStateOf<PendingFileSelection?>(null) }
    var pendingLocalImport by remember { mutableStateOf<PendingLocalModImport?>(null) }
    var localImportRetryInstallId by rememberSaveable(libraryItem.appId) {
        mutableStateOf<String?>(null)
    }
    var localInspectionGeneration by remember { mutableLongStateOf(0L) }
    var pendingCollectionSelection by remember { mutableStateOf<PendingCollectionSelection?>(null) }
    var selectedCollectionKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val collectionQueue = remember { mutableStateMapOf<String, CollectionQueueItem>() }
    val websiteAuthorizationWaiters = remember { mutableMapOf<String, CompletableDeferred<NexusModReference>>() }
    LaunchedEffect(nexusAuthState.connection) {
        if (
            nexusAuthState.connection == NexusConnectionState.DISCONNECTED &&
            websiteAuthorizationWaiters.isNotEmpty()
        ) {
            val error = NexusWebsiteAuthorizationException(
                context.getString(R.string.nexus_oauth_sign_in_required),
            )
            websiteAuthorizationWaiters.values.toList().forEach { waiter ->
                if (waiter.isActive) waiter.completeExceptionally(error)
            }
        }
        if (
            nexusAuthState.connection == NexusConnectionState.DISCONNECTED &&
            pendingFileSelection?.reference?.downloadAuthorization != null
        ) {
            pendingFileSelection = null
        }
    }
    var collectionPaused by remember { mutableStateOf(false) }
    var collectionCancelRequested by remember { mutableStateOf(false) }
    var collectionImportRunning by remember { mutableStateOf(false) }
    var activeCollectionInstallId by remember { mutableStateOf<String?>(null) }
    var pendingApply by remember { mutableStateOf<PendingApply?>(null) }
    var pendingProfileApply by remember { mutableStateOf<PendingProfileApply?>(null) }
    var modApplyInProgress by remember { mutableStateOf(false) }
    var profileApplyInProgress by remember { mutableStateOf(false) }
    var placementApplyStatusMessage by remember { mutableStateOf<String?>(null) }
    var placementApplyFailure by remember { mutableStateOf<PlacementApplyFailure?>(null) }
    var pendingProfileNameEdit by remember { mutableStateOf<PendingProfileNameEdit?>(null) }
    var pendingProfileDelete by remember { mutableStateOf<ModProfile?>(null) }
    var placementChoice by remember { mutableStateOf(PlacementChoice.AUTOMATIC) }
    var reviewedPlacementPlan by remember { mutableStateOf<ModInstallPlan?>(null) }
    var placementPlanPreview by remember { mutableStateOf<PlacementPlanPreview?>(null) }
    var automaticOptionSelections by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var riskyAutomaticPlanApproved by remember { mutableStateOf(false) }
    var fomodSelectionDraft by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var configurationDraftLoaded by remember { mutableStateOf(false) }
    var automaticPlacementResult by remember { mutableStateOf<AutomaticPlacementResult?>(null) }
    var automaticPlacementLoading by remember { mutableStateOf(false) }
    var selectedOwnership by remember { mutableStateOf<app.gamenative.mods.ModOwnershipManifest?>(null) }
    var selectedPreviousOwnership by remember { mutableStateOf<app.gamenative.mods.ModOwnershipManifest?>(null) }
    var placementOwnershipManifests by remember(libraryItem.appId) { mutableStateOf<List<ModOwnershipManifest>>(emptyList()) }
    var lastPlacementDrafts by remember(libraryItem.appId) { mutableStateOf<List<RecipeDraft>>(emptyList()) }
    var detectedDefaultDraft by remember(libraryItem.appId) { mutableStateOf<RecipeDraft?>(null) }
    var automaticPlacementContext by remember(libraryItem.appId) { mutableStateOf(AutomaticPlacementContext()) }
    val defaultDraft = detectedDefaultDraft ?: fallbackDefaultDraft
    var selectedTab by remember(libraryItem.appId) { mutableStateOf(ManageModsTab.MODS) }
    val recipeDrafts = remember { mutableStateListOf<RecipeDraft>() }
    var storageBreakdown by remember(libraryItem.appId) { mutableStateOf<ModStorageBreakdown?>(null) }
    var storageLoading by remember(libraryItem.appId) { mutableStateOf(false) }
    var healthReport by remember(libraryItem.appId) { mutableStateOf<ModHealthReport?>(null) }
    var healthLoading by remember(libraryItem.appId) { mutableStateOf(false) }
    var diagnosticsPaused by remember { mutableStateOf(false) }
    var nexusAuthActionInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(
        selectedInstall?.installId,
        libraryItem.name,
        archiveEntries,
        automaticOptionSelections,
        riskyAutomaticPlanApproved,
        automaticPlacementContext,
    ) {
        val install = selectedInstall
        if (install == null || archiveEntries.isEmpty()) {
            automaticPlacementResult = null
            automaticPlacementLoading = false
            return@LaunchedEffect
        }
        automaticPlacementLoading = true
        automaticPlacementResult = null
        val placement = withContext(Dispatchers.Default) {
            val planned = AutomaticPlacementPlanner.plan(
                libraryItem.name,
                archiveEntries,
                automaticOptionSelections,
                automaticPlacementContext,
            )
            planned.copy(
                candidates = planned.candidates.map { candidate ->
                    candidate.copy(plan = candidate.plan.withRiskyRootApproval(riskyAutomaticPlanApproved))
                },
                recommended = planned.recommended?.let { candidate ->
                    candidate.copy(plan = candidate.plan.withRiskyRootApproval(riskyAutomaticPlanApproved))
                },
            )
        }
        automaticPlacementResult = placement
        automaticPlacementLoading = false
        if (placementChoice == PlacementChoice.AUTOMATIC && install.canPlaceFiles()) {
            recipeDrafts.clear()
            recipeDrafts += automaticDraftsFor(placement, libraryItem.name, archiveEntries, defaultDraft)
        }
    }
    val nexusAuthenticationUnavailableMessage =
        context.getString(
            when {
                !NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE -> {
                    R.string.nexus_integration_temporarily_unavailable
                }
                !nexusAuthState.isConnected -> R.string.nexus_oauth_sign_in_required
                else -> R.string.nexus_oauth_session_unavailable
            },
        )
    val nexusAdultContentBlockedMessage = context.getString(R.string.nexus_adult_content_blocked)
    val nexusAuthErrorMessage = nexusAuthState.error?.let { error ->
        context.getString(
            when (error) {
                NexusAuthError.SIGN_IN_FAILED,
                NexusAuthError.CREDENTIAL_STORAGE_FAILED,
                -> R.string.nexus_oauth_sign_in_failed
                NexusAuthError.SESSION_EXPIRED -> R.string.nexus_oauth_sign_in_required
                NexusAuthError.REFRESH_RETRY_PENDING -> R.string.nexus_oauth_session_unavailable
            },
        )
    }

    fun nexusUserMessage(
        error: Throwable,
        fallback: String? = null,
        expiredAuthorizationMessage: String? = null,
    ): String {
        val authenticationMessage = nexusAuthenticationUnavailableMessage
        return if (fallback == null) {
            NexusImportState.userMessage(
                error = error,
                expiredAuthorizationMessage = expiredAuthorizationMessage,
                authenticationMessage = authenticationMessage,
                adultContentBlockedMessage = nexusAdultContentBlockedMessage,
            )
        } else {
            NexusImportState.userMessage(
                error = error,
                fallback = fallback,
                expiredAuthorizationMessage = expiredAuthorizationMessage,
                authenticationMessage = authenticationMessage,
                adultContentBlockedMessage = nexusAdultContentBlockedMessage,
            )
        }
    }

    fun blockUnavailableOnlineAccess(): Boolean {
        val message = when {
            !NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE -> {
                context.getString(R.string.nexus_integration_temporarily_unavailable)
            }
            !nexusAuthState.isConnected -> context.getString(R.string.nexus_oauth_sign_in_required)
            else -> return false
        }
        SnackbarManager.show(message)
        return true
    }

    fun connectNexusAccount() {
        if (!NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE || nexusAuthActionInProgress) {
            blockUnavailableOnlineAccess()
            return
        }
        scope.launch {
            nexusAuthActionInProgress = true
            try {
                val authorizationUri = try {
                    NexusAuthManager.beginAuthorization()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(
                        "[NexusOAuth]: Could not prepare browser sign-in (%s)",
                        error.javaClass.simpleName,
                    )
                    SnackbarManager.show(context.getString(R.string.nexus_oauth_sign_in_failed))
                    return@launch
                }
                val launchError = NexusOAuthBrowserLauncher.launch(context, authorizationUri)
                    .exceptionOrNull()
                if (launchError != null) {
                    Timber.w(
                        "[NexusOAuth]: Could not launch the sign-in browser (%s)",
                        launchError.javaClass.simpleName,
                    )
                    SnackbarManager.show(context.getString(R.string.nexus_oauth_browser_failed))
                    // Reset the pending transaction and CONNECTING state when no browser accepted it.
                    NexusAuthManager.cancelAuthorization()
                }
            } finally {
                nexusAuthActionInProgress = false
            }
        }
    }

    fun cancelNexusAuthorization() {
        if (nexusAuthActionInProgress) return
        scope.launch {
            nexusAuthActionInProgress = true
            try {
                NexusAuthManager.cancelAuthorization()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(
                    "[NexusOAuth]: Could not cancel browser sign-in (%s)",
                    error.javaClass.simpleName,
                )
            } finally {
                nexusAuthActionInProgress = false
            }
        }
    }

    fun disconnectNexusAccount() {
        if (nexusAuthActionInProgress) return
        scope.launch {
            nexusAuthActionInProgress = true
            try {
                NexusAuthManager.disconnect()
                    .onSuccess {
                        pendingFileSelection = null
                        pendingCollectionSelection = null
                        SnackbarManager.show(context.getString(R.string.nexus_oauth_disconnected))
                    }
                    .onFailure { error ->
                        Timber.w(
                            "[NexusOAuth]: Account disconnect did not complete (%s)",
                            error.javaClass.simpleName,
                        )
                        SnackbarManager.show(context.getString(R.string.nexus_oauth_disconnect_failed))
                    }
            } finally {
                nexusAuthActionInProgress = false
            }
        }
    }

    fun inspectLocalSource(
        retryInstallId: String?,
        inspect: suspend () -> LocalModSourceSelection,
    ) {
        val generation = localInspectionGeneration + 1L
        localInspectionGeneration = generation
        scope.launch {
            val inspectingMessage = context.getString(R.string.local_mod_inspecting)
            loadingMessage = inspectingMessage
            try {
                val source = inspect()
                val retryTarget = retryInstallId?.let { dao.getInstall(it) }
                if (
                    retryInstallId != null &&
                    (
                        retryTarget == null ||
                            retryTarget.appId != libraryItem.appId ||
                            retryTarget.source != source.type.installSource.name
                        )
                ) {
                    throw IOException(context.getString(R.string.nexus_invalid_source_metadata))
                }
                val storage = NexusModManager.checkLocalImportStorage(
                    context = context,
                    appId = libraryItem.appId,
                    sourceBytes = source.sizeBytes,
                    requiresExtraction = source.type == LocalModSourceType.ARCHIVE,
                )
                if (localInspectionGeneration != generation) return@launch
                pendingLocalImport = PendingLocalModImport(
                    source = source,
                    installId = retryTarget?.installId,
                    modName = (
                        retryTarget?.modName ?: if (
                            source.type == LocalModSourceType.FILES && source.fileCount > 1
                        ) {
                            context.getString(R.string.local_mod_default_name)
                        } else {
                            LocalModImporter.suggestedModName(
                                source,
                                context.getString(R.string.local_mod_default_name),
                            )
                        }
                    ).truncateAtCodePointBoundary(LocalModImporter.MAX_MOD_NAME_LENGTH),
                    version = retryTarget?.version.orEmpty(),
                    estimatedRequiredBytes = storage.estimatedRequiredBytes,
                    availableBytes = storage.availableBytes,
                )
                selectedTab = ManageModsTab.IMPORT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (localInspectionGeneration == generation) {
                    SnackbarManager.show(
                        NexusImportState.userMessage(
                            error = e,
                            fallback = context.getString(R.string.local_mod_unreadable),
                        ),
                    )
                }
            } finally {
                if (localInspectionGeneration == generation && loadingMessage == inspectingMessage) {
                    loadingMessage = null
                }
            }
        }
    }

    fun consumeLocalImportRetryId(): String? = localImportRetryInstallId.also {
        localImportRetryInstallId = null
    }

    val localArchiveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val retryInstallId = consumeLocalImportRetryId()
        uri ?: return@rememberLauncherForActivityResult
        inspectLocalSource(retryInstallId) { LocalModImporter.inspectArchive(context, uri) }
    }
    val localFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val retryInstallId = consumeLocalImportRetryId()
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        inspectLocalSource(retryInstallId) { LocalModImporter.inspectFiles(context, uris) }
    }
    val localFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val retryInstallId = consumeLocalImportRetryId()
        uri ?: return@rememberLauncherForActivityResult
        inspectLocalSource(retryInstallId) { LocalModImporter.inspectFolder(context, uri) }
    }

    fun launchLocalSourcePicker(sourceType: LocalModSourceType, retryInstallId: String? = null) {
        localImportRetryInstallId = retryInstallId
        when (sourceType) {
            LocalModSourceType.ARCHIVE -> localArchiveLauncher.launch(arrayOf("*/*"))
            LocalModSourceType.FILES -> localFilesLauncher.launch(arrayOf("*/*"))
            LocalModSourceType.FOLDER -> localFolderLauncher.launch(null)
        }
    }

    fun refreshLastPlacement() {
        lastPlacementDrafts = NexusModManager.lastPlacementRecipesForApp(libraryItem.appId, "")
            .map { it.toDraft() }
    }

    fun refreshStorageBreakdown() {
        scope.launch {
            storageLoading = true
            storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
            storageLoading = false
        }
    }

    fun runStorageCleanup(failedArchives: Boolean) {
        scope.launch {
            storageLoading = true
            try {
                val result = if (failedArchives) {
                    NexusModManager.cleanupFailedArchivesForApp(context, libraryItem.appId)
                } else {
                    NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                }
                storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
                SnackbarManager.show(context.getString(R.string.nexus_freed_size, StorageUtils.formatBinarySize(result.reclaimedBytes)))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_storage_cleanup_failed))
            } finally {
                storageLoading = false
            }
        }
    }

    fun cleanRedundantBackups() {
        scope.launch {
            storageLoading = true
            try {
                val result = NexusModManager.cleanupRedundantBackupsForApp(context, libraryItem.appId)
                storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
                SnackbarManager.show(context.getString(R.string.nexus_freed_size, StorageUtils.formatBinarySize(result.reclaimedBytes)))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_redundant_backup_cleanup_failed))
            } finally {
                storageLoading = false
            }
        }
    }

    fun runInstallHealthCheck() {
        scope.launch {
            healthLoading = true
            try {
                healthReport = NexusModManager.checkInstallHealthForApp(
                    context = context,
                    appId = libraryItem.appId,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                )
                val report = healthReport
                SnackbarManager.show(
                    if (report?.issues.isNullOrEmpty()) {
                        context.getString(R.string.nexus_no_install_health_issues)
                    } else {
                        context.getString(R.string.nexus_found_install_health_issues, report?.issues?.size ?: 0)
                    },
                )
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_install_health_check_failed))
            } finally {
                healthLoading = false
            }
        }
    }

    LaunchedEffect(libraryItem.appId) {
        refreshLastPlacement()
        launch(Dispatchers.IO) {
            ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
        }
        launch {
            delay(750)
            NexusModManager.reconcilePendingDeploymentsForApp(context, libraryItem.appId)
            NexusModImportService.resumeInterruptedImports(context)
            NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
            storageBreakdown = NexusModManager.scanStorageForApp(context, libraryItem.appId)
        }
    }

    LaunchedEffect(roots, gameRootDir, winePrefix, libraryItem.name) {
        detectedDefaultDraft = null
        automaticPlacementContext = AutomaticPlacementContext()
        val detected = withContext(Dispatchers.IO) {
            val existingGameDirectories = gameRootDir?.listFiles().orEmpty()
                .filter { it.isDirectory }
                .mapTo(linkedSetOf()) { it.name }
            BethesdaPluginManager.detectGame(libraryItem.name)?.let { game ->
                val draft = RecipeDraft(
                    targetRoot = ModTargetRoot.GAME_DIR.name,
                    targetRelativePath = game.dataDirName,
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                )
                return@withContext draft to AutomaticPlacementContext(
                    defaultTargetRoot = draft.targetRoot,
                    defaultTargetRelativePath = draft.targetRelativePath,
                    defaultTargetIsProven = true,
                    existingGameDirectories = existingGameDirectories,
                )
            }
            val pathDetection = ModPathDetector.detect(gameRootDir, winePrefix, libraryItem.name)
            val detectedDir = pathDetection
                ?.takeIf { it.confidence == "HIGH" }
                ?.targetDirs
                ?.firstOrNull()
                ?.canonicalFile
            val root = detectedDir?.let { dir ->
                roots.firstOrNull { root ->
                    val rootFile = root.dir.canonicalFile
                    dir == rootFile || dir.path.startsWith(rootFile.path + File.separator)
                }
            } ?: roots.firstOrNull()
            val relative = if (detectedDir != null && root != null) {
                detectedDir.relativeToOrNull(root.dir.canonicalFile)?.path ?: ""
            } else {
                ""
            }
            val draft = RecipeDraft(
                targetRoot = root?.type?.name ?: ModTargetRoot.GAME_DIR.name,
                targetRelativePath = relative,
            )
            draft to AutomaticPlacementContext(
                defaultTargetRoot = draft.targetRoot,
                defaultTargetRelativePath = draft.targetRelativePath,
                defaultTargetIsProven = detectedDir != null && relative.isNotBlank(),
                existingGameDirectories = existingGameDirectories,
            )
        }
        detectedDefaultDraft = detected.first
        automaticPlacementContext = detected.second
    }

    LaunchedEffect(pendingCollectionSelection) {
        val pending = pendingCollectionSelection
        selectedCollectionKeys = pending
            ?.mods
            ?.filter { it.canImport }
            ?.map { it.collectionKey() }
            ?.toSet()
            .orEmpty()
        collectionQueue.clear()
        collectionPaused = false
        collectionCancelRequested = false
        activeCollectionInstallId = null
    }

    LaunchedEffect(installs, profileStates, gameRootDir, winePrefix, libraryItem.appId, libraryItem.name, activeProfile?.profileId, diagnosticsPaused) {
        if (diagnosticsPaused) {
            diagnosticsLoading = false
            return@LaunchedEffect
        }
        diagnosticsLoading = true
        try {
            delay(300)
            val snapshot = withContext(Dispatchers.IO) {
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val states = ModProfileManager.ensureStatesForInstalls(
                    dao = dao,
                    profile = profile,
                    installs = installs.filter { it.canPlaceFiles() },
                )
                val enabledStateByInstallId = states.associate { it.installId to it.enabled }
                val usableInstalls = installs.filter { it.canPlaceFiles() && isEnabledInProfile(it, enabledStateByInstallId) }
                val priorities = states.associate { it.installId to it.priority }
                val recipesByInstallId = usableInstalls.associate { install ->
                    install.installId to dao.getRecipesForInstall(install.installId)
                }
                val ownershipRoot = NexusModManager.cacheRoot(context, libraryItem.appId)
                val ownershipByInstallId = usableInstalls.mapNotNull { install ->
                    ModOwnershipStore.read(ownershipRoot, install.installId)
                        ?.let { install.installId to it }
                }.toMap()
                val conflicts = ModConflictAnalyzer.analyze(
                    installs = usableInstalls,
                    recipesByInstallId = recipesByInstallId,
                    prioritiesByInstallId = priorities,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                    ownershipByInstallId = ownershipByInstallId,
                )
                val game = BethesdaPluginManager.detectGame(libraryItem.name)
                val detectedPlugins = game?.let {
                    BethesdaPluginManager.detectPlugins(
                        installs = usableInstalls,
                        recipesByInstallId = recipesByInstallId,
                        prioritiesByInstallId = priorities,
                        gameRootDir = gameRootDir,
                        winePrefix = winePrefix,
                        pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                        ownershipByInstallId = ownershipByInstallId,
                    )
                }.orEmpty()
                ModDiagnosticsSnapshot(
                    conflicts = conflicts,
                    placementNeededInstallIds = usableInstalls
                        .filter { install -> recipesByInstallId[install.installId].orEmpty().isEmpty() }
                        .mapTo(mutableSetOf()) { it.installId },
                    bethesdaGame = game,
                    plugins = detectedPlugins,
                    pluginIssues = game?.let {
                        BethesdaPluginManager.diagnosePluginMasters(
                            managedPlugins = detectedPlugins,
                            game = it,
                            gameRootDir = gameRootDir,
                            pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                        )
                    }.orEmpty(),
                    pluginAssetIssues = if (game != null) BethesdaPluginManager.diagnosePluginAssets(detectedPlugins) else emptyList(),
                )
            }
            conflictReports = snapshot.conflicts
            placementNeededInstallIds = snapshot.placementNeededInstallIds
            bethesdaGame = snapshot.bethesdaGame
            bethesdaPlugins = snapshot.plugins
            bethesdaPluginIssues = snapshot.pluginIssues
            bethesdaPluginAssetIssues = snapshot.pluginAssetIssues
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            conflictReports = emptyList()
            placementNeededInstallIds = emptySet()
            bethesdaPlugins = emptyList()
            bethesdaPluginIssues = emptyList()
            bethesdaPluginAssetIssues = emptyList()
            SnackbarManager.show(e.message ?: context.getString(R.string.nexus_diagnostics_scan_failed))
        } finally {
            diagnosticsLoading = false
        }
    }

    fun createProfile(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            SnackbarManager.show(context.getString(R.string.nexus_enter_profile_name))
            return
        }
        scope.launch {
            try {
                val active = ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val profile = ModProfile(
                    profileId = "${libraryItem.appId}:profile:${System.currentTimeMillis()}",
                    appId = libraryItem.appId,
                    name = trimmedName,
                    active = false,
                )
                dao.upsertProfile(profile)
                dao.getProfileInstallStates(libraryItem.appId, active.profileId).forEach { state ->
                    dao.upsertProfileInstallState(
                        state.copy(
                            profileId = profile.profileId,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                dao.activateProfile(libraryItem.appId, profile.profileId)
                pendingProfileNameEdit = null
                SnackbarManager.show(context.getString(R.string.nexus_profile_created, trimmedName))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_profile_create_failed))
            }
        }
    }

    fun renameProfile(profile: ModProfile, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            SnackbarManager.show(context.getString(R.string.nexus_enter_profile_name))
            return
        }
        scope.launch {
            try {
                dao.renameProfile(profile.profileId, trimmedName)
                pendingProfileNameEdit = null
                SnackbarManager.show(context.getString(R.string.nexus_profile_renamed))
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_profile_rename_failed))
            }
        }
    }

    fun deleteProfile(profile: ModProfile) {
        scope.launch {
            val currentProfiles = dao.getProfilesForApp(libraryItem.appId)
            if (currentProfiles.size <= 1) {
                SnackbarManager.show(context.getString(R.string.nexus_profile_required))
                pendingProfileDelete = null
                return@launch
            }
            val replacement = currentProfiles.firstOrNull { it.profileId != profile.profileId }
            dao.deleteProfile(profile.profileId)
            if (profile.active && replacement != null) {
                dao.activateProfile(libraryItem.appId, replacement.profileId)
            }
            pendingProfileDelete = null
            SnackbarManager.show(context.getString(R.string.nexus_profile_deleted, profile.name))
        }
    }

    fun activateProfile(profile: ModProfile) {
        scope.launch {
            dao.activateProfile(libraryItem.appId, profile.profileId)
            ModProfileManager.ensureStatesForInstalls(
                dao = dao,
                profile = profile.copy(active = true),
                installs = installs.filter { it.canPlaceFiles() },
            )
            SnackbarManager.show(context.getString(R.string.nexus_profile_switched_apply))
        }
    }

    fun setProfileInstallEnabled(install: ModInstall, enabled: Boolean) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId, enabled = enabled)
            dao.upsertProfileInstallState(state.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
            if (!enabled) {
                val skipped = NexusModManager.disableInstall(
                    context = context,
                    install = install,
                    restoreBackups = true,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                )
                SnackbarManager.show(
                    if (skipped.isEmpty()) {
                        context.getString(R.string.nexus_disabled_in_named_profile, profile.name)
                    } else {
                        context.getString(R.string.nexus_disabled_in_profile_with_skipped, profile.name, skipped.size)
                    },
                )
            } else {
                if (install.status == ModInstallStatus.DISABLED.name) {
                    dao.updateInstallEnabled(install.installId, true, ModInstallStatus.READY.name)
                }
                SnackbarManager.show(context.getString(R.string.nexus_enabled_in_named_profile, profile.name))
            }
        }
    }

    fun moveInstallPriority(installId: String, direction: Int) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val states = ModProfileManager.ensureStatesForInstalls(
                dao = dao,
                profile = profile,
                installs = installs.filter { it.canPlaceFiles() },
            )
                .sortedWith(compareByDescending<ModProfileInstallState> { it.priority }.thenBy { it.installId })
            val index = states.indexOfFirst { it.installId == installId }
            val otherIndex = index + direction
            if (index < 0 || otherIndex !in states.indices) return@launch
            val current = states[index]
            val other = states[otherIndex]
            dao.upsertProfileInstallState(current.copy(priority = other.priority, updatedAt = System.currentTimeMillis()))
            dao.upsertProfileInstallState(other.copy(priority = current.priority, updatedAt = System.currentTimeMillis()))
        }
    }

    fun makeInstallHighestPriority(installId: String) {
        scope.launch {
            val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
            val states = ModProfileManager.ensureStatesForInstalls(
                dao = dao,
                profile = profile,
                installs = installs.filter { it.canPlaceFiles() },
            )
            val current = states.firstOrNull { it.installId == installId } ?: return@launch
            val topPriority = states.maxOfOrNull { it.priority } ?: current.priority
            dao.upsertProfileInstallState(
                current.copy(
                    priority = if (current.priority >= topPriority) current.priority else topPriority + 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun writePluginState(updated: List<BethesdaPlugin>) {
        val game = bethesdaGame ?: return
        val pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, game) ?: return
        scope.launch {
            val issues = withContext(Dispatchers.IO) {
                BethesdaPluginManager.updateManagedPluginsTxt(
                    file = pluginsFile,
                    managedPlugins = updated,
                    game = game,
                    gameRootDir = gameRootDir,
                )
                BethesdaPluginManager.diagnosePluginMasters(
                    managedPlugins = updated,
                    game = game,
                    gameRootDir = gameRootDir,
                    pluginsFile = pluginsFile,
                )
            }
            bethesdaPlugins = updated
            bethesdaPluginIssues = issues
            bethesdaPluginAssetIssues = BethesdaPluginManager.diagnosePluginAssets(updated)
            SnackbarManager.show(
                if (issues.hasBlockingPluginIssues()) {
                    context.getString(R.string.nexus_plugin_list_saved_with_warnings)
                } else {
                    context.getString(R.string.nexus_plugin_list_saved)
                },
            )
        }
    }

    fun movePluginMastersBefore(plugin: BethesdaPlugin, masterNames: List<String>) {
        val masterKeys = masterNames.map { it.trim().removePrefix("*").lowercase() }.toSet()
        val moving = bethesdaPlugins.filter { it.fileName.lowercase() in masterKeys }
        if (moving.isEmpty()) {
            SnackbarManager.show(context.getString(R.string.nexus_required_plugin_not_managed))
            return
        }
        val reordered = bethesdaPlugins.toMutableList()
        reordered.removeAll(moving.toSet())
        val targetIndex = reordered.indexOfFirst { it.fileName == plugin.fileName }
        if (targetIndex < 0) return
        reordered.addAll(
            targetIndex,
            moving.sortedBy { masterNames.indexOfFirst { master -> master.equals(it.fileName, ignoreCase = true) }.takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
        )
        writePluginState(reordered)
    }

    fun applyProfileOrder(allowOverwrite: Boolean): kotlinx.coroutines.Job? {
        if (profileApplyInProgress || modApplyInProgress) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_order_already_applying))
            return null
        }
        return scope.launch {
            profileApplyInProgress = true
            diagnosticsPaused = true
            try {
                SnackbarManager.show(context.getString(R.string.nexus_applying_order_may_take_time))
                loadingMessage = context.getString(R.string.nexus_checking_mod_order)
                var effectiveAllowOverwrite = allowOverwrite
                val collectionPluginOrder = pendingCollectionSelection?.collection?.manifestInfo?.rules?.pluginLoadOrder.orEmpty()
                val conflictInstallIds = conflictReports
                    .flatMap { report -> report.participants.map { it.installId } }
                    .toSet()
                val plan = withContext(Dispatchers.IO) {
                    val profile = ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                    val currentInstalls = dao.getInstallsForApp(libraryItem.appId)
                        .filter { it.canPlaceFiles() }
                    val stateByInstallId = ModProfileManager.ensureStatesForInstalls(
                        dao = dao,
                        profile = profile,
                        installs = currentInstalls,
                    )
                        .associateBy { it.installId }
                    val disabledInstalls = currentInstalls
                        .filter { stateByInstallId[it.installId]?.enabled != true }
                    val orderedInstalls = currentInstalls
                        .filter { stateByInstallId[it.installId]?.enabled == true }
                        .sortedWith(compareBy<ModInstall> { stateByInstallId[it.installId]?.priority ?: 0 }.thenBy { it.installId })
                    val recipesToPersistByInstallId = mutableMapOf<String, List<ModPlacementRecipe>>()
                    val recipesByInstallId = orderedInstalls.associate { install ->
                        val savedRecipes = dao.getRecipesForInstall(install.installId)
                        val effectiveRecipes = BethesdaPlacementRecipeExpander.expand(libraryItem.name, install, savedRecipes)
                        if (effectiveRecipes != savedRecipes) {
                            recipesToPersistByInstallId[install.installId] = effectiveRecipes
                        }
                        install.installId to effectiveRecipes
                    }
                    val allOwnership = currentInstalls.mapNotNull { install ->
                        app.gamenative.mods.ModOwnershipStore.read(
                            NexusModManager.cacheRoot(context, libraryItem.appId),
                            install.installId,
                        )
                    }
                    val ownershipByInstallId = allOwnership.associateBy { it.installId }
                    val reviewedPlansByInstallId = allOwnership.mapNotNull { ownership ->
                        ownership.reviewedPlanOrNull()?.let { ownership.installId to it }
                    }.toMap()
                    val configuredInstalls = orderedInstalls.filter { install ->
                        recipesByInstallId[install.installId].orEmpty().isNotEmpty() ||
                            install.installId in reviewedPlansByInstallId
                    }
                    val unconfiguredInstalls = orderedInstalls - configuredInstalls.toSet()
                    val configuredOwnershipIds = allOwnership
                        .filter { it.state == app.gamenative.mods.ModOwnershipState.ACTIVE }
                        .mapTo(mutableSetOf()) { it.installId }
                    val desiredPriorities = stateByInstallId.values
                        .filter { it.enabled }
                        .associate { it.installId to it.priority }
                    val overlayTransition = app.gamenative.mods.ModProfileOverlayPlanner.transition(
                        allOwnership,
                        desiredPriorities,
                    )
                    val rebuildManagedOverlay = requiresManagedOverlayRebuild(
                        configuredInstallIds = configuredInstalls.mapTo(mutableSetOf()) { it.installId },
                        activeOwnershipInstallIds = configuredOwnershipIds,
                        transition = overlayTransition,
                    )
                    val game = BethesdaPluginManager.detectGame(libraryItem.name)
                    val plugins = game?.let {
                        BethesdaPluginManager.detectPlugins(
                            installs = configuredInstalls,
                            recipesByInstallId = recipesByInstallId,
                            prioritiesByInstallId = stateByInstallId.mapValues { state -> state.value.priority },
                            gameRootDir = gameRootDir,
                            winePrefix = winePrefix,
                            pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                            ownershipByInstallId = ownershipByInstallId,
                            defaultEnabled = true,
                        )
                    }.orEmpty()
                        .let { applyCollectionPluginOrder(it, collectionPluginOrder) }
                    val pluginIssues = game?.let {
                        BethesdaPluginManager.diagnosePluginMasters(
                            managedPlugins = plugins,
                            game = it,
                            gameRootDir = gameRootDir,
                            pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, it),
                        )
                    }.orEmpty()
                    val pluginAssetIssues = if (game != null) BethesdaPluginManager.diagnosePluginAssets(plugins) else emptyList()
                    val assetRepairInstallIds = pluginAssetIssues.mapNotNull { it.plugin.installId }.toSet()
                    val missingTargetRepairInstallIds = configuredInstalls
                        .filter { install ->
                            val ownership = ownershipByInstallId[install.installId]
                            if (ownership?.state == app.gamenative.mods.ModOwnershipState.ACTIVE) {
                                app.gamenative.mods.ModDeploymentVerifier.verifyPresence(ownership).issues.any {
                                    it.type == app.gamenative.mods.ModVerificationIssueType.MISSING
                                }
                            } else {
                                NexusModManager.hasMissingAppliedTargets(
                                    install = install,
                                    recipes = recipesByInstallId[install.installId].orEmpty(),
                                    gameRootDir = gameRootDir,
                                    winePrefix = winePrefix,
                                    reviewedPlan = reviewedPlansByInstallId[install.installId],
                                )
                            }
                        }
                        .mapTo(mutableSetOf()) { it.installId }
                    val installsToApply = if (rebuildManagedOverlay) {
                        configuredInstalls
                    } else {
                        configuredInstalls.filter { install ->
                            shouldApplyProfileInstall(
                                install = install,
                                hasActiveOwnership = install.installId in configuredOwnershipIds,
                                hasConflict = install.installId in conflictInstallIds,
                                needsAssetRepair = install.installId in assetRepairInstallIds,
                                hasMissingTarget = install.installId in missingTargetRepairInstallIds,
                            )
                        }
                    }
                    ProfileOrderPlan(
                        profileId = profile.profileId,
                        stateByInstallId = stateByInstallId,
                        disabledInstalls = disabledInstalls,
                        configuredInstalls = configuredInstalls,
                        installsToApply = installsToApply,
                        rebuildManagedOverlay = rebuildManagedOverlay,
                        overlayTransitionBlockerCount = if (overlayTransition.requiresRebuild && !overlayTransition.safeToRebuild) {
                            overlayTransition.currentVerification.issues.size
                        } else {
                            0
                        },
                        missingTargetRepairInstallIds = missingTargetRepairInstallIds,
                        recipesByInstallId = recipesByInstallId,
                        ownershipByInstallId = ownershipByInstallId,
                        reviewedPlansByInstallId = reviewedPlansByInstallId,
                        recipesToPersistByInstallId = recipesToPersistByInstallId,
                        unconfiguredCount = unconfiguredInstalls.size,
                        unconfiguredNames = unconfiguredInstalls.map { it.modName },
                        bethesdaGame = game,
                        plugins = plugins,
                        pluginIssues = pluginIssues,
                        pluginAssetIssues = pluginAssetIssues,
                    )
                }
                bethesdaGame = plan.bethesdaGame
                bethesdaPlugins = plan.plugins
                bethesdaPluginIssues = plan.pluginIssues
                bethesdaPluginAssetIssues = plan.pluginAssetIssues
                if (plan.overlayTransitionBlockerCount > 0) {
                    SnackbarManager.show(
                        context.getString(
                            R.string.nexus_mod_order_blocked_managed_files,
                            plan.overlayTransitionBlockerCount,
                        ),
                    )
                    return@launch
                }
                if (plan.pluginIssues.hasBlockingPluginIssues()) {
                    SnackbarManager.show(context.getString(R.string.nexus_fix_plugin_warnings_before_apply))
                    return@launch
                }
                var disabledSkipped = 0

                if (!allowOverwrite) {
                    loadingMessage = context.getString(R.string.nexus_checking_file_conflicts)
                    val check = withContext(Dispatchers.IO) {
                        val targetCheckInstalls = plan.installsToApply.filter { it.status != ModInstallStatus.APPLIED.name }
                        val overwriteManifests = targetCheckInstalls.flatMap { install ->
                            dao.getOverwriteManifests(install.installId)
                        }
                        val rawConflicts = targetCheckInstalls.flatMap { install ->
                            ModMaterializer.scanConflicts(
                                install = install,
                                recipes = plan.recipesByInstallId[install.installId].orEmpty(),
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                                reviewedPlan = plan.reviewedPlansByInstallId[install.installId],
                            )
                        }
                        ProfileOrderConflictCheck(
                            rawConflicts = rawConflicts,
                            conflicts = ModMaterializer.filterUnapprovedConflicts(rawConflicts, overwriteManifests),
                            hasOverwriteRecipe = plan.recipesByInstallId.values.flatten().any { it.mode == ModPlacementMode.OVERWRITE_COPY.name } ||
                                plan.reviewedPlansByInstallId.values.any { reviewed ->
                                    reviewed.files.any { it.status == PlannedFileStatus.PLACED && it.mode == ModPlacementMode.OVERWRITE_COPY.name }
                                },
                        )
                    }
                    if (check.conflicts.isNotEmpty() && check.hasOverwriteRecipe) {
                        pendingProfileApply = PendingProfileApply(check.conflicts)
                        return@launch
                    }
                    if (check.conflicts.isNotEmpty()) {
                        SnackbarManager.show(context.getString(R.string.nexus_profile_order_target_files_exist))
                        return@launch
                    }
                    effectiveAllowOverwrite = check.rawConflicts.isNotEmpty()
                }

                val availableBytes = NexusModManager.cacheRoot(context, libraryItem.appId).usableSpace
                if (availableBytes < MIN_APPLY_FREE_BYTES) {
                    SnackbarManager.show(
                        context.getString(R.string.nexus_storage_low_apply_order, StorageUtils.formatBinarySize(MIN_APPLY_FREE_BYTES - availableBytes)),
                    )
                    return@launch
                }

                if (plan.recipesToPersistByInstallId.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        plan.recipesToPersistByInstallId.forEach { (installId, recipes) ->
                            dao.replaceRecipes(installId, recipes)
                        }
                    }
                }

                loadingMessage = context.getString(R.string.nexus_applying_mod_order)
                val result = withContext(Dispatchers.IO) {
                    ModDeploymentCoordinator.withGameLock(libraryItem.appId) {
                        var transactionDisabledSkipped = plan.disabledInstalls.sumOf { install ->
                            NexusModManager.disableInstall(
                                context = context,
                                install = install,
                                restoreBackups = true,
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            ).size
                        }
                        if (plan.rebuildManagedOverlay) {
                            transactionDisabledSkipped += plan.configuredInstalls.asReversed().sumOf { install ->
                                NexusModManager.disableInstall(
                                    context = context,
                                    install = install,
                                    restoreBackups = true,
                                    gameRootDir = gameRootDir,
                                    winePrefix = winePrefix,
                                ).size
                            }
                            effectiveAllowOverwrite = true
                        }
                        var errors = 0
                        for (install in plan.installsToApply) {
                            val recipes = plan.recipesByInstallId[install.installId].orEmpty()
                            val applyResult = if (
                                !effectiveAllowOverwrite &&
                                install.status == ModInstallStatus.APPLIED.name &&
                                install.installId in plan.missingTargetRepairInstallIds
                            ) {
                                NexusModManager.repairMissingAppliedTargets(
                                    install = install,
                                    recipes = recipes,
                                    gameRootDir = gameRootDir,
                                    winePrefix = winePrefix,
                                    reviewedPlan = plan.reviewedPlansByInstallId[install.installId],
                                )
                            } else {
                                NexusModManager.applyInstall(
                                    context = context,
                                    install = if (plan.rebuildManagedOverlay) {
                                        install.copy(status = ModInstallStatus.DISABLED.name)
                                    } else {
                                        install
                                    },
                                    recipes = recipes,
                                    gameRootDir = gameRootDir,
                                    winePrefix = winePrefix,
                                    allowOverwrite = effectiveAllowOverwrite,
                                    saveLastPlacement = false,
                                    preserveStatusOnError = true,
                                    profileId = plan.profileId,
                                    priority = plan.stateByInstallId[install.installId]?.priority ?: 0,
                                    reviewedPlan = plan.reviewedPlansByInstallId[install.installId],
                                )
                            }
                            errors += applyResult.errors.size
                            // A rebuild disables every configured install first. Keep
                            // restoring later installs even if one plan fails so a
                            // single bad mod cannot leave the rest disabled.
                            if (applyResult.errors.isNotEmpty() && !plan.rebuildManagedOverlay) break
                        }
                        val game = BethesdaPluginManager.detectGame(libraryItem.name)
                        if (errors == 0 && game != null) {
                            val pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, game)
                            if (pluginsFile != null) {
                                val appliedInstalls = plan.configuredInstalls.map { it.copy(status = ModInstallStatus.APPLIED.name) }
                                val appliedOwnership = plan.ownershipByInstallId.toMutableMap().apply {
                                    plan.installsToApply.forEach { install ->
                                        ModOwnershipStore.read(
                                            NexusModManager.cacheRoot(context, libraryItem.appId),
                                            install.installId,
                                        )?.let { put(install.installId, it) }
                                    }
                                }
                                val detectedPlugins = applyCollectionPluginOrder(
                                    BethesdaPluginManager.detectPlugins(
                                        installs = appliedInstalls,
                                        recipesByInstallId = plan.recipesByInstallId,
                                        prioritiesByInstallId = plan.stateByInstallId.mapValues { it.value.priority },
                                        gameRootDir = gameRootDir,
                                        winePrefix = winePrefix,
                                        pluginsFile = pluginsFile,
                                        ownershipByInstallId = appliedOwnership,
                                        defaultEnabled = true,
                                    ),
                                    collectionPluginOrder,
                                )
                                BethesdaPluginManager.updateManagedPluginsTxt(
                                    file = pluginsFile,
                                    managedPlugins = detectedPlugins,
                                    game = game,
                                    gameRootDir = gameRootDir,
                                )
                                val issues = BethesdaPluginManager.diagnosePluginMasters(
                                    managedPlugins = detectedPlugins,
                                    game = game,
                                    gameRootDir = gameRootDir,
                                    pluginsFile = pluginsFile,
                                )
                                ProfileOrderApplyResult(
                                    errors = errors,
                                    bethesdaGame = game,
                                    plugins = detectedPlugins,
                                    pluginIssues = issues,
                                    pluginAssetIssues = BethesdaPluginManager.diagnosePluginAssets(detectedPlugins),
                                    disabledSkipped = transactionDisabledSkipped,
                                )
                            } else {
                                ProfileOrderApplyResult(errors, null, emptyList(), emptyList(), emptyList(), transactionDisabledSkipped)
                            }
                        } else {
                            ProfileOrderApplyResult(errors, null, emptyList(), emptyList(), emptyList(), transactionDisabledSkipped)
                        }
                    }
                }
                disabledSkipped = result.disabledSkipped
                if (plan.rebuildManagedOverlay && disabledSkipped > 0) {
                    SnackbarManager.show(context.getString(R.string.nexus_changed_disabled_files_left_in_place, disabledSkipped))
                    return@launch
                }
                result.bethesdaGame?.let {
                    bethesdaGame = it
                    bethesdaPlugins = result.plugins
                    bethesdaPluginIssues = result.pluginIssues
                    bethesdaPluginAssetIssues = result.pluginAssetIssues
                }
                val suffix = if (disabledSkipped > 0) {
                    context.getString(R.string.nexus_changed_disabled_files_left_in_place, disabledSkipped)
                } else {
                    ""
                }
                val skippedSuffix = if (plan.unconfiguredCount > 0) {
                    val visibleNames = plan.unconfiguredNames.take(3).joinToString(", ")
                    val remaining = if (plan.unconfiguredCount > 3) ", ..." else ""
                    "; ${context.getString(R.string.nexus_apply_needs_placement, plan.unconfiguredCount, visibleNames, remaining)}"
                } else {
                    ""
                }
                if (healthReport != null) {
                    loadingMessage = context.getString(R.string.nexus_refreshing_install_health)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            NexusModManager.checkInstallHealthForApp(
                                context = context,
                                appId = libraryItem.appId,
                                gameRootDir = gameRootDir,
                                winePrefix = winePrefix,
                            )
                        }
                    }.onSuccess { refreshedHealth ->
                        healthReport = refreshedHealth
                    }
                }
                SnackbarManager.show(if (result.errors == 0) context.getString(R.string.nexus_mod_order_applied, suffix, skippedSuffix) else context.getString(R.string.nexus_mod_order_applied_with_errors, result.errors, suffix, skippedSuffix))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SnackbarManager.show(e.message ?: context.getString(R.string.nexus_apply_mod_order_failed))
            } finally {
                profileApplyInProgress = false
                loadingMessage = null
                diagnosticsPaused = false
            }
        }
    }

    fun refreshEntries(install: ModInstall?) {
        if (install == null || !install.canPlaceFiles()) {
            archiveEntries = emptyList()
            selectedFomodInstaller = null
            fomodEnvironment = FomodEnvironmentSnapshot()
            return
        }
        archiveEntries = emptyList()
        selectedFomodInstaller = null
        fomodEnvironment = FomodEnvironmentSnapshot()
        scope.launch {
            val (entries, fomodInstaller, environment) = withContext(Dispatchers.IO) {
                val extractedRoot = File(install.extractedPath)
                val parsedFomod = FomodInstallerDetector.moduleConfigFile(extractedRoot)
                    ?.let { runCatching { FomodParser.parse(it, extractedRoot) }.getOrNull() }
                val game = BethesdaPluginManager.detectGame(libraryItem.name)
                Triple(
                    NexusModManager.archiveEntries(install),
                    parsedFomod,
                    parsedFomod?.let { installer ->
                        FomodEnvironmentSnapshotBuilder.build(
                            installer = installer,
                            gameName = libraryItem.name,
                            gameRootDir = gameRootDir,
                            pluginsFile = game?.let { BethesdaPluginManager.pluginsFile(winePrefix, it) },
                        )
                    } ?: FomodEnvironmentSnapshot(),
                )
            }
            archiveEntries = entries
            selectedFomodInstaller = fomodInstaller
            fomodEnvironment = environment
        }
    }

    fun adoptInstallOwnership(installId: String) {
        val install = installs.firstOrNull { it.installId == installId } ?: return
        scope.launch {
            healthLoading = true
            try {
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
                val result = NexusModManager.adoptHistoricalDeployment(
                    context = context,
                    install = install,
                    recipes = dao.getRecipesForInstall(install.installId),
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                    profileId = profile.profileId,
                    priority = state.priority,
                )
                SnackbarManager.show(
                    if (result.errors.isEmpty()) {
                        context.getString(R.string.nexus_ownership_adopted)
                    } else {
                        context.getString(R.string.nexus_file_tracking_setup_failed, result.errors.size)
                    },
                )
                healthReport = NexusModManager.checkInstallHealthForApp(context, libraryItem.appId, gameRootDir, winePrefix)
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                SnackbarManager.show(context.getString(R.string.nexus_file_tracking_setup_failed, 1))
            } finally {
                healthLoading = false
            }
        }
    }

    fun restorePreviousDeployment(installId: String) {
        val install = installs.firstOrNull { it.installId == installId } ?: return
        if (modApplyInProgress || profileApplyInProgress) return
        scope.launch {
            modApplyInProgress = true
            diagnosticsPaused = true
            try {
                loadingMessage = context.getString(R.string.nexus_restoring_previous_deployment)
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
                val result = NexusModManager.restorePreviousDeployment(
                    context = context,
                    install = install,
                    recipes = dao.getRecipesForInstall(install.installId),
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                    profileId = profile.profileId,
                    priority = state.priority,
                )
                val root = NexusModManager.cacheRoot(context, install.appId)
                if (result.errors.isEmpty() && selectedInstall?.installId == install.installId) {
                    selectedOwnership = app.gamenative.mods.ModOwnershipStore.read(root, install.installId)
                    selectedPreviousOwnership = app.gamenative.mods.ModOwnershipStore.readPrevious(root, install.installId)
                    reviewedPlacementPlan = selectedOwnership?.reviewedPlanOrNull()
                }
                SnackbarManager.show(
                    if (result.errors.isEmpty()) {
                        context.getString(R.string.nexus_previous_deployment_restored)
                    } else {
                        context.getString(R.string.nexus_previous_deployment_restore_failed, result.errors.size)
                    },
                )
                if (healthReport != null) {
                    healthReport = NexusModManager.checkInstallHealthForApp(context, libraryItem.appId, gameRootDir, winePrefix)
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                SnackbarManager.show(context.getString(R.string.nexus_previous_deployment_restore_failed, 1))
            } finally {
                modApplyInProgress = false
                loadingMessage = null
                diagnosticsPaused = false
            }
        }
    }

    fun loadRecipes(install: ModInstall?) {
        configurationDraftLoaded = false
        reviewedPlacementPlan = null
        automaticOptionSelections = emptyMap()
        riskyAutomaticPlanApproved = false
        fomodSelectionDraft = emptyMap()
        automaticPlacementResult = null
        recipeDrafts.clear()
        if (install == null || !install.canPlaceFiles()) {
            recipeDrafts += defaultDraft
            return
        }
        scope.launch {
            val (recipes, savedDraft) = withContext(Dispatchers.IO) {
                dao.getRecipesForInstall(install.installId) to
                    ModConfigurationDraftStore.read(NexusModManager.cacheRoot(context, install.appId), install)
            }
            val restoredRecipes = savedDraft?.recipes.orEmpty().map { it.toRecipe(install.installId) }
            automaticOptionSelections = savedDraft?.automaticOptions.orEmpty()
            riskyAutomaticPlanApproved = savedDraft?.riskyTargetsApproved == true
            fomodSelectionDraft = savedDraft?.fomodSelections.orEmpty().mapValues { it.value.toSet() }
            recipeDrafts.clear()
            val selectedRecipes = restoredRecipes.ifEmpty { recipes }
            val restoredChoice = savedDraft?.placementChoice
                ?.let { runCatching { PlacementChoice.valueOf(it) }.getOrNull() }
            if (selectedRecipes.isEmpty()) {
                placementChoice = restoredChoice ?: PlacementChoice.AUTOMATIC
                recipeDrafts += automaticPlacementResult
                    ?.let { automaticDraftsFor(it, libraryItem.name, archiveEntries, defaultDraft) }
                    .orEmpty()
                    .ifEmpty { listOf(defaultDraft) }
            } else {
                placementChoice = restoredChoice ?: PlacementChoice.CUSTOM
                recipeDrafts += selectedRecipes.map { it.toDraft() }
            }
            configurationDraftLoaded = true
        }
    }

    fun requestWebsiteDownloadAuthorization(
        reference: NexusModReference,
        modInfo: NexusModInfo,
        file: NexusModFile,
        requestId: String? = null,
        nexusUserId: Long? = null,
    ): Boolean {
        if (blockUnavailableOnlineAccess()) return false
        val currentNexusUserId = nexusUserId
            ?: NexusAuthManager.state.value.currentNexusUserInfo()?.userId
        val pendingReference = reference.copy(
            fileId = file.fileId,
            downloadAuthorization = null,
        )
        val pending = PendingNexusWebsiteDownload(
            appId = libraryItem.appId,
            reference = pendingReference,
            modInfo = modInfo,
            file = file,
            nexusUserId = currentNexusUserId,
            requestId = requestId,
        )
        val expected = NexusDownloadLinkInbox.expect(pending) {
            NexusPendingDownloadStore.remember(context, pending)
        }
        if (!expected) {
            val failure = NexusWebsiteAuthorizationException(
                context.getString(R.string.nexus_authorization_already_pending),
            )
            requestId?.let { websiteAuthorizationWaiters[it]?.completeExceptionally(failure) }
            SnackbarManager.show(failure.message.orEmpty())
            return false
        }
        val websiteUrl = NexusDownloadLinkInbox.websiteDownloadUrl(pendingReference, file.fileId)
        val launchError = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, websiteUrl.toUri()))
        }.exceptionOrNull()
        if (launchError == null) {
            SnackbarManager.show(context.getString(R.string.nexus_authorize_in_browser))
            return true
        }
        NexusDownloadLinkInbox.cancelExpected(libraryItem.appId, pendingReference, requestId)
        NexusPendingDownloadStore.removeMatching(context, libraryItem.appId, pendingReference, requestId)
        val failure = NexusWebsiteAuthorizationException(
            context.getString(R.string.nexus_open_browser_failed),
            launchError,
        )
        requestId?.let { websiteAuthorizationWaiters[it]?.completeExceptionally(failure) }
        SnackbarManager.show(failure.message.orEmpty())
        return false
    }

    suspend fun awaitWebsiteDownloadAuthorization(
        reference: NexusModReference,
        modInfo: NexusModInfo,
        file: NexusModFile,
        nexusUserId: Long?,
    ): NexusModReference? {
        val requestId = UUID.randomUUID().toString()
        val callback = CompletableDeferred<NexusModReference>()
        websiteAuthorizationWaiters[requestId] = callback
        requestWebsiteDownloadAuthorization(reference, modInfo, file, requestId, nexusUserId)
        var preserveExpectationForRecreation = false
        return try {
            when (
                val result = awaitCallbackOrCancellation(
                    callback = callback,
                    cancellationRequests = snapshotFlow { collectionCancelRequested },
                    timeoutMillis = WEBSITE_AUTHORIZATION_TIMEOUT_MS,
                )
            ) {
                null -> throw NexusWebsiteAuthorizationException(
                    context.getString(R.string.nexus_authorization_timed_out),
                )
                CallbackWaitResult.Cancelled -> null
                is CallbackWaitResult.Received -> result.value
            }
        } catch (e: CancellationException) {
            // The Activity/dialog can be recreated while the browser is open. Keep
            // the non-secret expected tuple so the returning NXM grant can be routed
            // to this app; the new dialog will recover it as a single authorized file.
            preserveExpectationForRecreation = true
            throw e
        } finally {
            websiteAuthorizationWaiters.remove(requestId)
            if (!preserveExpectationForRecreation) {
                val pendingReference = reference.copy(fileId = file.fileId, downloadAuthorization = null)
                NexusDownloadLinkInbox.cancelExpected(
                    appId = libraryItem.appId,
                    reference = pendingReference,
                    requestId = requestId,
                )
                NexusPendingDownloadStore.removeMatching(
                    context = context,
                    appId = libraryItem.appId,
                    reference = pendingReference,
                    requestId = requestId,
                )
            }
        }
    }

    fun importFile(reference: NexusModReference, modInfo: NexusModInfo, file: NexusModFile) {
        if (blockUnavailableOnlineAccess()) return
        if (reference.downloadAuthorization?.isExpired() == true) {
            requestWebsiteDownloadAuthorization(reference, modInfo, file)
            return
        }
        scope.launch {
            try {
                val user = currentNexusUserForDownload(reference, apiClient::getCurrentUser)
                if (user == null) {
                    SnackbarManager.show(context.getString(R.string.nexus_authorization_wrong_account))
                    return@launch
                }
                if (!user.isPremium && reference.downloadAuthorization == null) {
                    requestWebsiteDownloadAuthorization(reference, modInfo, file, nexusUserId = user.userId)
                    return@launch
                }
                val cleanup = NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                if (cleanup.reclaimedBytes > 0L) {
                    SnackbarManager.show(context.getString(R.string.nexus_cleaned_old_temp_files, StorageUtils.formatBinarySize(cleanup.reclaimedBytes)))
                }
                val storage = NexusModManager.checkImportStorage(context, libraryItem.appId, listOf(file))
                if (!storage.canImport) {
                    SnackbarManager.show(
                        context.getString(
                            R.string.nexus_not_enough_storage_import,
                            StorageUtils.formatBinarySize(storage.estimatedRequiredBytes),
                            StorageUtils.formatBinarySize(storage.availableBytes),
                        ),
                    )
                    return@launch
                }
                loadingMessage = context.getString(R.string.nexus_starting_named_file, file.name.ifBlank { file.fileName })
                progress = 0f
                importProgress = null
                val install = NexusModImportService.enqueueImport(
                    context = context,
                    appId = libraryItem.appId,
                    reference = reference,
                    modInfo = modInfo,
                    file = file,
                    displayName = modInfo.name,
                    isPremiumAccount = user.isPremium,
                ).await()
                selectedInstall = install
                pendingFileSelection = null
                selectedTab = ManageModsTab.PLACEMENT
                placementChoice = PlacementChoice.AUTOMATIC
                loadRecipes(install)
                refreshEntries(install)
                SnackbarManager.show(context.getString(R.string.nexus_nexus_mod_imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (NexusImportState.requiresWebsiteAuthorization(e)) {
                    requestWebsiteDownloadAuthorization(reference, modInfo, file)
                } else {
                    SnackbarManager.show(nexusUserMessage(e))
                }
            } finally {
                loadingMessage = null
                importProgress = null
            }
        }
    }

    fun openImportedLocalMod(install: ModInstall) {
        selectedInstall = install
        selectedTab = ManageModsTab.PLACEMENT
        placementChoice = PlacementChoice.AUTOMATIC
        loadRecipes(install)
        refreshEntries(install)
    }

    fun importLocalMod(pending: PendingLocalModImport) {
        val modName = pending.modName.trim()
        if (modName.isBlank()) {
            SnackbarManager.show(context.getString(R.string.local_mod_name_required))
            return
        }
        pendingLocalImport = null
        scope.launch {
            try {
                val cleanup = NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                if (cleanup.reclaimedBytes > 0L) {
                    SnackbarManager.show(
                        context.getString(
                            R.string.nexus_cleaned_old_temp_files,
                            StorageUtils.formatBinarySize(cleanup.reclaimedBytes),
                        ),
                    )
                }
                val storage = NexusModManager.checkLocalImportStorage(
                    context = context,
                    appId = libraryItem.appId,
                    sourceBytes = pending.source.sizeBytes,
                    requiresExtraction = pending.source.type == LocalModSourceType.ARCHIVE,
                )
                if (!storage.canImport) {
                    if (pendingLocalImport == null) {
                        pendingLocalImport = pending.copy(
                            estimatedRequiredBytes = storage.estimatedRequiredBytes,
                            availableBytes = storage.availableBytes,
                        )
                    }
                    SnackbarManager.show(
                        context.getString(
                            R.string.nexus_not_enough_storage_import,
                            StorageUtils.formatBinarySize(storage.estimatedRequiredBytes),
                            StorageUtils.formatBinarySize(storage.availableBytes),
                        ),
                    )
                    return@launch
                }
                loadingMessage = context.getString(R.string.local_mod_starting, pending.source.displayName)
                progress = 0f
                importProgress = null
                val install = NexusModImportService.enqueueLocalImport(
                    context = context,
                    appId = libraryItem.appId,
                    source = pending.source,
                    modName = modName,
                    version = pending.version,
                    installId = pending.installId ?: "local_${UUID.randomUUID()}",
                ).await()
                openImportedLocalMod(install)
                SnackbarManager.show(context.getString(R.string.local_mod_imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DuplicateLocalModContentException) {
                openImportedLocalMod(e.existingInstall)
                SnackbarManager.show(context.getString(R.string.local_mod_duplicate, e.existingInstall.modName))
            } catch (e: Exception) {
                SnackbarManager.show(
                    NexusImportState.userMessage(
                        error = e,
                        fallback = context.getString(R.string.local_mod_import_failed),
                    ),
                )
            } finally {
                loadingMessage = null
                importProgress = null
            }
        }
    }

    fun resumeStagedLocalMod(install: ModInstall) {
        scope.launch {
            try {
                loadingMessage = context.getString(R.string.local_mod_starting, install.fileName)
                progress = 0f
                importProgress = null
                val resumed = NexusModImportService.resumeLocalImport(context, install).await()
                openImportedLocalMod(resumed)
                SnackbarManager.show(context.getString(R.string.local_mod_imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SnackbarManager.show(
                    NexusImportState.userMessage(
                        error = e,
                        fallback = context.getString(R.string.local_mod_import_failed),
                    ),
                )
            } finally {
                loadingMessage = null
                importProgress = null
            }
        }
    }

    suspend fun receiveAuthorizedDownload(download: AuthorizedNexusWebsiteDownload) {
        if (blockUnavailableOnlineAccess()) return
        val reference = download.reference
        val matchingPending = download.pending
        if (matchingPending.appId != libraryItem.appId) return
        if (matchingPending.isPastPendingTtl()) {
            Timber.w("[NexusDownload]: Ignoring stale website authorization callback")
            return
        }
        val authorization = reference.downloadAuthorization ?: return
        if (authorization.isExpired()) {
            val error = NexusApiException(
                message = context.getString(R.string.nexus_authorization_expired),
                statusCode = 410,
                reason = NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_EXPIRED,
            )
            matchingPending.requestId?.let { websiteAuthorizationWaiters.remove(it)?.completeExceptionally(error) }
            SnackbarManager.show(error.message.orEmpty())
            return
        }

        if (
            matchingPending.nexusUserId != null &&
            authorization.userId != null &&
            matchingPending.nexusUserId != authorization.userId
        ) {
            val error = NexusApiException(
                message = context.getString(R.string.nexus_authorization_wrong_account),
                statusCode = 400,
                reason = NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_INVALID,
            )
            matchingPending.requestId?.let { websiteAuthorizationWaiters.remove(it)?.completeExceptionally(error) }
            SnackbarManager.show(error.message.orEmpty())
            return
        }
        matchingPending.requestId?.let { requestId ->
            val waiter = websiteAuthorizationWaiters.remove(requestId)
            if (waiter != null) {
                waiter.complete(reference)
                return
            }
            // The collection coroutine disappeared (for example after Activity
            // recreation). Recover the exact authorized file without silently
            // restarting the old collection queue.
            pendingCollectionSelection = null
            pendingFileSelection = PendingFileSelection(reference, matchingPending.modInfo, listOf(matchingPending.file))
            selectedTab = ManageModsTab.IMPORT
            SnackbarManager.show(context.getString(R.string.nexus_authorized_file_received))
            return
        }
        importFile(reference, matchingPending.modInfo, matchingPending.file)
    }

    suspend fun receiveBrowserFirstDownload(download: BrowserFirstNexusWebsiteDownload) {
        if (download.appId != libraryItem.appId || blockUnavailableOnlineAccess()) return
        loadingMessage = context.getString(R.string.nexus_resolving_nexus_mod)
        try {
            when (
                val resolution = resolveBrowserFirstNexusDownload(
                    reference = download.reference,
                    getCurrentUser = apiClient::getCurrentUser,
                    getModInfo = apiClient::getModInfo,
                    getModFiles = apiClient::getModFiles,
                )
            ) {
                is BrowserFirstNexusResolution.Resolved -> {
                    pendingCollectionSelection = null
                    pendingFileSelection = PendingFileSelection(
                        reference = resolution.reference,
                        modInfo = resolution.modInfo,
                        files = listOf(resolution.file),
                    )
                    selectedTab = ManageModsTab.IMPORT
                }
                BrowserFirstNexusResolution.Expired -> {
                    SnackbarManager.show(context.getString(R.string.nexus_authorization_expired))
                }
                BrowserFirstNexusResolution.WrongAccount -> {
                    SnackbarManager.show(context.getString(R.string.nexus_authorization_wrong_account))
                }
                BrowserFirstNexusResolution.MissingFile -> {
                    SnackbarManager.show(context.getString(R.string.nexus_nxm_file_not_found))
                }
                BrowserFirstNexusResolution.Invalid -> {
                    SnackbarManager.show(context.getString(R.string.nexus_invalid_nxm_callback))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SnackbarManager.show(
                nexusUserMessage(e, context.getString(R.string.nexus_resolve_url_failed)),
            )
        } finally {
            if (loadingMessage == context.getString(R.string.nexus_resolving_nexus_mod)) {
                loadingMessage = null
            }
        }
    }

    if (NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE) {
        val currentReceiveAuthorizedDownload = rememberUpdatedState(::receiveAuthorizedDownload)
        val currentReceiveBrowserFirstDownload = rememberUpdatedState(::receiveBrowserFirstDownload)
        DisposableEffect(libraryItem.appId, nexusAuthState.isConnected) {
            val registration = if (nexusAuthState.isConnected) {
                NexusDownloadLinkInbox.registerReceiver(libraryItem.appId)
            } else {
                null
            }
            onDispose { registration?.unregister() }
        }
        LaunchedEffect(apiClient, libraryItem.appId) {
            NexusDownloadLinkInbox.callbacksFor(libraryItem.appId)
                .collect { download -> currentReceiveAuthorizedDownload.value(download) }
        }
        LaunchedEffect(apiClient, libraryItem.appId, nexusAuthState.isConnected) {
            if (nexusAuthState.isConnected) {
                NexusDownloadLinkInbox.browserFirstCallbacksFor(libraryItem.appId)
                    .collect { download -> currentReceiveBrowserFirstDownload.value(download) }
            }
        }
    }

    fun retryInstall(install: ModInstall) {
        if (ModInstallSource.isLocal(install.source)) {
            val sourceType = LocalModSourceType.fromInstallSource(install.source)
            if (sourceType == null || install.appId != libraryItem.appId) {
                SnackbarManager.show(context.getString(R.string.nexus_invalid_source_metadata))
                return
            }
            scope.launch {
                val canResumeStagedContent = withContext(Dispatchers.IO) {
                    NexusModManager.hasCompletePendingLocalContent(context, install)
                }
                if (canResumeStagedContent) {
                    resumeStagedLocalMod(install)
                } else {
                    launchLocalSourcePicker(sourceType, install.installId)
                }
            }
            return
        }
        val gameDomain = install.nexusGameDomain
        val modId = install.nexusModId
        val fileId = install.nexusFileId
        if (gameDomain.isNullOrBlank() || modId == null || fileId == null) {
            SnackbarManager.show(context.getString(R.string.nexus_invalid_source_metadata))
            return
        }
        importFile(
            reference = NexusModReference(
                gameDomain = gameDomain,
                modId = modId,
                fileId = fileId,
            ),
            modInfo = NexusModInfo(
                modId = modId,
                name = install.modName,
                summary = install.metadataSummary(),
                version = install.version,
            ),
            file = NexusModFile(
                fileId = fileId,
                name = install.fileName,
                version = install.version,
                fileName = install.fileName,
                sizeBytes = install.sizeBytes,
                uploadedTimestamp = 0L,
            ),
        )
    }

    fun localizedImportStatus(status: String): String = when (status) {
        "Starting" -> context.getString(R.string.nexus_queue_starting)
        "Copying" -> context.getString(R.string.local_mod_import_status_copying)
        "Downloading" -> context.getString(R.string.nexus_import_status_downloading)
        "Unpacking" -> context.getString(R.string.nexus_import_status_unpacking)
        else -> status
    }

    suspend fun resolveCollectionMod(collectionFile: NexusCollectionFile): PendingCollectionMod {
        if (collectionFile.modId <= 0L || collectionFile.fileId <= 0L) {
            return PendingCollectionMod(
                collectionFile = collectionFile,
                modInfo = null,
                file = null,
                error = context.getString(R.string.nexus_collection_manual_external_entry),
            )
        }
        val embeddedFile = collectionFile.toFallbackNexusFile()
        val embeddedModInfo = collectionFile.toEmbeddedNexusModInfo()
        return try {
            val modInfo = embeddedModInfo
                ?: apiClient.getModInfo(collectionFile.gameDomain, collectionFile.modId)
            val files = apiClient.getModFiles(collectionFile.gameDomain, collectionFile.modId)
            val file = files.firstOrNull { it.fileId == collectionFile.fileId }
                ?: embeddedFile
            PendingCollectionMod(collectionFile, modInfo, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallbackFile = embeddedFile
            if (fallbackFile != null) {
                PendingCollectionMod(
                    collectionFile = collectionFile,
                    modInfo = NexusModInfo(
                        modId = collectionFile.modId,
                        name = collectionFile.modName.ifBlank { context.getString(R.string.nexus_collection_mod_fallback_name, collectionFile.modId) },
                        summary = "",
                        version = collectionFile.version,
                    ),
                    file = fallbackFile,
                    error = null,
                )
            } else {
                PendingCollectionMod(
                    collectionFile = collectionFile,
                    modInfo = null,
                    file = null,
                    error = e.message ?: context.getString(R.string.nexus_collection_mod_resolve_failed),
                )
            }
        }
    }

    fun resolveCollection(reference: app.gamenative.mods.NexusCollectionReference) {
        if (blockUnavailableOnlineAccess()) return
        scope.launch {
            try {
                loadingMessage = context.getString(R.string.nexus_resolving_nexus_collection)
                progress = 0f
                importProgress = null
                val collection = apiClient.getCollectionRevision(reference)
                if (collection.files.isEmpty()) {
                    SnackbarManager.show(context.getString(R.string.nexus_collection_no_downloadable_mods))
                    return@launch
                }
                var resolvedCount = 0
                val resolutionGate = Semaphore(4)
                val resolvedMods = coroutineScope {
                    collection.files.map { file ->
                        async {
                            val resolved = resolutionGate.withPermit { resolveCollectionMod(file) }
                            resolvedCount++
                            loadingMessage = context.getString(
                                R.string.nexus_resolving_collection_item,
                                resolvedCount,
                                collection.files.size,
                            )
                            resolved
                        }
                    }.awaitAll()
                }
                pendingFileSelection = null
                pendingCollectionSelection = PendingCollectionSelection(collection, resolvedMods)
                selectedTab = ManageModsTab.IMPORT
                SnackbarManager.show(context.getString(R.string.nexus_collection_resolve_ready, resolvedMods.count { it.canImport }))
            } catch (e: NexusApiException) {
                SnackbarManager.show(nexusUserMessage(e, context.getString(R.string.nexus_resolve_collection_failed)))
            } catch (e: Exception) {
                SnackbarManager.show(nexusUserMessage(e, context.getString(R.string.nexus_resolve_collection_failed)))
            } finally {
                if (loadingMessage?.startsWith(context.getString(R.string.nexus_resolving_prefix)) == true) loadingMessage = null
            }
        }
    }

    fun importCollection(pending: PendingCollectionSelection, selectedKeys: Set<String>) {
        if (blockUnavailableOnlineAccess()) return
        if (collectionImportRunning) return
        val collectionMods = pending.mods.filter { it.canImport && it.collectionKey() in selectedKeys }
        if (collectionMods.isEmpty()) {
            SnackbarManager.show(context.getString(R.string.nexus_no_selected_collection_mods_ready))
            return
        }
        val knownUser = nexusUserInfo
        collectionImportRunning = true
        scope.launch {
            var imported = 0
            var reused = 0
            var failed = 0
            fun updateQueue(
                pendingMod: PendingCollectionMod,
                status: CollectionQueueStatus,
                progress: Float? = null,
                message: String = "",
                error: String = "",
                startedAt: Long? = null,
            ) {
                val key = pendingMod.collectionKey()
                val current = collectionQueue[key]
                collectionQueue[key] = (current ?: pendingMod.toQueueItem(
                    status = status,
                    fallbackName = context.getString(R.string.nexus_collection_mod_fallback_name, pendingMod.collectionFile.modId),
                )).copy(
                    status = status,
                    progress = progress ?: current?.progress ?: 0f,
                    message = message,
                    error = error,
                    startedAt = startedAt ?: current?.startedAt ?: 0L,
                )
            }
            fun failQueuedItems(error: String) {
                collectionMods.forEach { pendingMod ->
                    if (collectionQueue[pendingMod.collectionKey()]?.status == CollectionQueueStatus.QUEUED) {
                        failed++
                        updateQueue(
                            pendingMod,
                            status = CollectionQueueStatus.FAILED,
                            message = context.getString(R.string.nexus_queue_failed),
                            error = error,
                        )
                    }
                }
            }
            suspend fun existingReusableInstall(pendingMod: PendingCollectionMod): ModInstall? {
                return withContext(Dispatchers.IO) {
                    val file = pendingMod.file ?: return@withContext null
                    val installId = NexusModManager.installIdFor(
                        appId = libraryItem.appId,
                        gameDomain = pendingMod.collectionFile.gameDomain,
                        modId = pendingMod.collectionFile.modId,
                        fileId = pendingMod.collectionFile.fileId.takeIf { it > 0L } ?: file.fileId,
                    )
                    dao.getInstall(installId)
                        ?.takeIf {
                            NexusCollectionReusePolicy.matchesExactFile(it, pendingMod.collectionFile, file) &&
                                it.canPlaceFiles() &&
                                File(it.extractedPath).isDirectory
                        }
                }
            }
            suspend fun configureCollectionInstall(
                pendingMod: PendingCollectionMod,
                modInfo: NexusModInfo,
                file: NexusModFile,
                reference: NexusModReference,
                install: ModInstall,
                profile: ModProfile,
                suggestedPriority: Int,
                reusedExisting: Boolean,
            ): Pair<String, String> {
                val entries = withContext(Dispatchers.IO) { NexusModManager.archiveEntries(install) }
                val fomodInstaller = withContext(Dispatchers.IO) {
                    val extractedRoot = File(install.extractedPath)
                    FomodInstallerDetector.moduleConfigFile(extractedRoot)
                        ?.let { runCatching { FomodParser.parse(it, extractedRoot) }.getOrNull() }
                }
                val bethesdaGameForMod = BethesdaPluginManager.detectGame(libraryItem.name)
                val fomodAutoSelection = fomodInstaller?.let { installer ->
                    bethesdaGameForMod?.let { game ->
                        val environment = withContext(Dispatchers.IO) {
                            FomodEnvironmentSnapshotBuilder.build(
                                installer = installer,
                                gameName = libraryItem.name,
                                gameRootDir = gameRootDir,
                                pluginsFile = BethesdaPluginManager.pluginsFile(winePrefix, game),
                            )
                        }
                        withContext(Dispatchers.Default) {
                            FomodAutoSelector.selectDeterministic(
                                installId = install.installId,
                                installer = installer,
                                targetRelativePath = game.dataDirName,
                                environment = environment,
                            )
                        }
                    }
                }
                val assessment = ModArchiveInstallAssessor.assess(
                    gameName = libraryItem.name,
                    modName = modInfo.name,
                    fileName = file.fileName.ifBlank { file.name },
                    entries = entries,
                    gameDomain = reference.gameDomain,
                    modId = reference.modId,
                    fileId = reference.fileId ?: file.fileId,
                )
                val hasFomodInstaller = archiveContainsFomodInstaller(entries)
                val drafts = if (hasFomodInstaller || !assessment.allowsAutomaticPlacement) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        automaticDraftsFor(libraryItem.name, entries, defaultDraft)
                    }
                }
                val existingRecipes = withContext(Dispatchers.IO) { dao.getRecipesForInstall(install.installId) }
                if (existingRecipes.isEmpty()) {
                    val recipes = fomodAutoSelection?.recipes ?: drafts.map { it.toRecipe(install.installId) }
                    withContext(Dispatchers.IO) {
                        dao.replaceRecipes(
                            install.installId,
                            BethesdaPlacementRecipeExpander.expand(
                                gameName = libraryItem.name,
                                install = install,
                                recipes = recipes,
                            ),
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    val targetProfile = dao.getActiveProfileForApp(libraryItem.appId) ?: profile
                    val state = ModProfileManager.ensureStateForInstall(
                        dao = dao,
                        profile = targetProfile,
                        installId = install.installId,
                        enabled = true,
                        priority = suggestedPriority,
                    )
                    dao.upsertProfileInstallState(
                        state.copy(
                            enabled = true,
                            priority = suggestedPriority,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                if (selectedInstall == null) {
                    selectedInstall = install
                    archiveEntries = entries
                    selectedFomodInstaller = fomodInstaller
                    placementChoice = PlacementChoice.AUTOMATIC
                    recipeDrafts.clear()
                    recipeDrafts += when {
                        existingRecipes.isNotEmpty() -> existingRecipes.map { it.toDraft() }
                        else -> (fomodAutoSelection?.recipes?.map { it.toDraft() } ?: drafts)
                            .ifEmpty { listOf(defaultDraft) }
                    }
                }
                val queueReasons = buildList {
                    if (reusedExisting && install.nexusFileId != (reference.fileId ?: file.fileId)) {
                        add(context.getString(R.string.nexus_collection_different_file_reused))
                    }
                    if (existingRecipes.isNotEmpty() && reusedExisting) add(context.getString(R.string.nexus_collection_existing_placement_kept))
                    addAll(assessment.reasons)
                    addAll(fomodAutoSelection?.reasons.orEmpty())
                }.distinct()
                val queueMessage = when {
                    reusedExisting && install.nexusFileId != (reference.fileId ?: file.fileId) -> context.getString(R.string.nexus_collection_already_imported_using_existing_file)
                    reusedExisting && existingRecipes.isNotEmpty() -> context.getString(R.string.nexus_collection_already_imported_kept_placement)
                    reusedExisting -> context.getString(R.string.nexus_collection_already_imported)
                    fomodAutoSelection != null -> context.getString(R.string.nexus_collection_imported_fomod_auto_selected)
                    else -> assessment.queueMessage
                }
                return queueMessage to queueReasons.joinToString("; ")
            }
            try {
                val cleanup = NexusModManager.cleanupOrphanedFilesForApp(context, libraryItem.appId)
                if (cleanup.reclaimedBytes > 0L) {
                    SnackbarManager.show(context.getString(R.string.nexus_cleaned_old_temp_files, StorageUtils.formatBinarySize(cleanup.reclaimedBytes)))
                }
                collectionPaused = false
                collectionCancelRequested = false
                collectionMods.forEach { pendingMod ->
                    updateQueue(pendingMod, CollectionQueueStatus.QUEUED)
                }
                val reusableInstalls = mutableMapOf<String, ModInstall>()
                val modsNeedingDownload = mutableListOf<PendingCollectionMod>()
                collectionMods.forEach { pendingMod ->
                    val existing = existingReusableInstall(pendingMod)
                    if (existing != null) {
                        reusableInstalls[pendingMod.collectionKey()] = existing
                    } else {
                        modsNeedingDownload += pendingMod
                    }
                }
                val nexusUserForDownloads = if (modsNeedingDownload.isNotEmpty()) {
                    try {
                        knownUser ?: apiClient.getCurrentUser()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val errorMessage = nexusUserMessage(e)
                        failQueuedItems(errorMessage)
                        SnackbarManager.show(errorMessage)
                        return@launch
                    }
                } else {
                    null
                }
                val storage = NexusModManager.checkImportStorage(
                    context = context,
                    appId = libraryItem.appId,
                    files = modsNeedingDownload.mapNotNull { it.file },
                    sequential = true,
                )
                if (!storage.canImport) {
                    val errorMessage = context.getString(
                        R.string.nexus_not_enough_storage_import,
                        StorageUtils.formatBinarySize(storage.estimatedRequiredBytes),
                        StorageUtils.formatBinarySize(storage.availableBytes),
                    )
                    failQueuedItems(errorMessage)
                    SnackbarManager.show(errorMessage)
                    return@launch
                }
                val suggestedPriorities = NexusCollectionPrioritySuggester.priorities(collectionMods.map { it.collectionFile })
                val profile = withContext(Dispatchers.IO) {
                    ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                }
                val collectionPriorityBase = withContext(Dispatchers.IO) {
                    (dao.getProfileInstallStates(libraryItem.appId, profile.profileId).maxOfOrNull { it.priority } ?: -1) + 1
                }
                for ((index, pendingMod) in collectionMods.withIndex()) {
                    while (collectionPaused && !collectionCancelRequested) {
                        updateQueue(pendingMod, CollectionQueueStatus.QUEUED, message = context.getString(R.string.nexus_queue_paused))
                        delay(300L)
                    }
                    if (collectionCancelRequested) {
                        updateQueue(pendingMod, CollectionQueueStatus.CANCELED, message = context.getString(R.string.nexus_queue_canceled))
                        break
                    }
                    val modInfo = pendingMod.modInfo ?: continue
                    val file = pendingMod.file ?: continue
                    val reference = NexusModReference(
                        gameDomain = pendingMod.collectionFile.gameDomain,
                        modId = pendingMod.collectionFile.modId,
                        fileId = pendingMod.collectionFile.fileId,
                    )
                    try {
                        val installId = NexusModManager.installIdFor(
                            appId = libraryItem.appId,
                            gameDomain = reference.gameDomain,
                            modId = reference.modId,
                            fileId = reference.fileId ?: file.fileId,
                        )
                        loadingMessage = context.getString(R.string.nexus_preparing_collection_item, index + 1, collectionMods.size, modInfo.name)
                        progress = 0f
                        importProgress = null
                        val suggestedPriority = collectionPriorityBase + (suggestedPriorities[pendingMod.collectionKey()] ?: index)
                        val reusableInstall = reusableInstalls[pendingMod.collectionKey()] ?: existingReusableInstall(pendingMod)
                        if (reusableInstall != null) {
                            if (reusableInstall.installId != installId) {
                                withContext(Dispatchers.IO) { dao.getInstall(installId) }
                                    ?.takeIf { it.status == ModInstallStatus.ERROR.name }
                                    ?.let { duplicate ->
                                        withContext(Dispatchers.IO) {
                                            dao.deleteOverwriteManifests(duplicate.installId)
                                            dao.deleteInstall(duplicate.installId)
                                            File(duplicate.archivePath).takeIf { it.path.isNotBlank() }?.delete()
                                            File(duplicate.extractedPath).deleteRecursively()
                                        }
                                    }
                            }
                            updateQueue(
                                pendingMod,
                                status = CollectionQueueStatus.IMPORTING,
                                progress = 1f,
                                message = context.getString(R.string.nexus_collection_already_imported),
                                startedAt = System.currentTimeMillis(),
                            )
                            val (queueMessage, queueError) = configureCollectionInstall(
                                pendingMod = pendingMod,
                                modInfo = modInfo,
                                file = file,
                                reference = reference,
                                install = reusableInstall,
                                profile = profile,
                                suggestedPriority = suggestedPriority,
                                reusedExisting = true,
                            )
                            reused++
                            updateQueue(
                                pendingMod,
                                CollectionQueueStatus.IMPORTED,
                                progress = 1f,
                                message = queueMessage,
                                error = queueError,
                            )
                            continue
                        }
                        val activeNexusUser = apiClient.getCurrentUser()
                        if (activeNexusUser.userId != nexusUserForDownloads?.userId) {
                            collectionCancelRequested = true
                            throw NexusWebsiteAuthorizationException(
                                context.getString(R.string.nexus_authorization_wrong_account),
                            )
                        }
                        val downloadReference = if (!activeNexusUser.isPremium) {
                            loadingMessage = context.getString(
                                R.string.nexus_waiting_for_website_authorization,
                                index + 1,
                                collectionMods.size,
                                modInfo.name,
                            )
                            updateQueue(
                                pendingMod,
                                status = CollectionQueueStatus.IMPORTING,
                                message = context.getString(R.string.nexus_waiting_for_nexus),
                                startedAt = System.currentTimeMillis(),
                            )
                            val authorizedReference = awaitWebsiteDownloadAuthorization(
                                reference,
                                modInfo,
                                file,
                                activeNexusUser.userId,
                            )
                            if (authorizedReference == null) {
                                updateQueue(
                                    pendingMod,
                                    status = CollectionQueueStatus.CANCELED,
                                    message = context.getString(R.string.nexus_queue_canceled),
                                )
                                break
                            }
                            authorizedReference
                        } else {
                            reference
                        }
                        if (collectionCancelRequested) {
                            updateQueue(
                                pendingMod,
                                status = CollectionQueueStatus.CANCELED,
                                message = context.getString(R.string.nexus_queue_canceled),
                            )
                            break
                        }
                        loadingMessage = context.getString(R.string.nexus_starting_collection_item, index + 1, collectionMods.size, modInfo.name)
                        updateQueue(
                            pendingMod,
                            status = CollectionQueueStatus.IMPORTING,
                            message = context.getString(R.string.nexus_queue_starting),
                            startedAt = System.currentTimeMillis(),
                        )
                        activeCollectionInstallId = installId
                        val install = NexusModImportService.enqueueImport(
                            context = context,
                            appId = libraryItem.appId,
                            reference = downloadReference,
                            modInfo = modInfo,
                            file = file,
                            displayName = context.getString(R.string.nexus_collection_display_name, index + 1, collectionMods.size, modInfo.name),
                            isPremiumAccount = activeNexusUser.isPremium,
                            onProgress = { detail ->
                                scope.launch(Dispatchers.Main) {
                                    updateQueue(
                                        pendingMod,
                                        CollectionQueueStatus.IMPORTING,
                                        progress = detail.progress,
                                        message = localizedImportStatus(detail.status),
                                    )
                                }
                            },
                        ).await()
                        imported++
                        val (queueMessage, queueError) = configureCollectionInstall(
                            pendingMod = pendingMod,
                            modInfo = modInfo,
                            file = file,
                            reference = reference,
                            install = install,
                            profile = profile,
                            suggestedPriority = suggestedPriority,
                            reusedExisting = false,
                        )
                        updateQueue(
                            pendingMod,
                            CollectionQueueStatus.IMPORTED,
                            progress = 1f,
                            message = queueMessage,
                            error = queueError,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is NexusWebsiteAuthorizationException || NexusImportState.requiresWebsiteAuthorization(e)) {
                            failed++
                            collectionCancelRequested = true
                            val errorMessage = nexusUserMessage(
                                error = e,
                                expiredAuthorizationMessage = context.getString(R.string.nexus_authorization_expired),
                            )
                            updateQueue(
                                pendingMod,
                                status = CollectionQueueStatus.FAILED,
                                message = context.getString(R.string.nexus_queue_failed),
                                error = errorMessage,
                            )
                            SnackbarManager.show(errorMessage)
                            break
                        }
                        failed++
                        val canceled = e.message?.contains("canceled", ignoreCase = true) == true || collectionCancelRequested
                        updateQueue(
                            pendingMod,
                            status = if (canceled) CollectionQueueStatus.CANCELED else CollectionQueueStatus.FAILED,
                            message = if (canceled) context.getString(R.string.nexus_queue_canceled) else context.getString(R.string.nexus_queue_failed),
                            error = nexusUserMessage(e),
                        )
                    } finally {
                        activeCollectionInstallId = null
                    }
                }
                if (collectionCancelRequested) {
                    collectionMods.forEach { pendingMod ->
                        if (collectionQueue[pendingMod.collectionKey()]?.status == CollectionQueueStatus.QUEUED) {
                            updateQueue(
                                pendingMod,
                                status = CollectionQueueStatus.CANCELED,
                                message = context.getString(R.string.nexus_queue_canceled),
                            )
                        }
                    }
                }
                val prepared = imported + reused
                val suffix = buildString {
                    if (reused > 0) append(context.getString(R.string.nexus_collection_summary_already_imported, reused))
                    if (failed > 0) append(context.getString(R.string.nexus_collection_summary_failed, failed))
                }
                if (collectionCancelRequested) {
                    SnackbarManager.show(context.getString(R.string.nexus_collection_import_canceled, prepared, suffix))
                } else {
                    SnackbarManager.show(context.getString(R.string.nexus_collection_prepared_applying, prepared, suffix))
                    if (prepared > 0) {
                        applyProfileOrder(allowOverwrite = false)?.join()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMessage = nexusUserMessage(e)
                failQueuedItems(errorMessage)
                SnackbarManager.show(errorMessage)
            } finally {
                collectionImportRunning = false
                activeCollectionInstallId = null
                loadingMessage = null
                importProgress = null
            }
        }
    }

    fun resolveUrlAndImport() {
        if (blockUnavailableOnlineAccess()) return
        val collectionReference = NexusCollectionUrlParser.parse(nexusUrl)
        if (collectionReference != null) {
            resolveCollection(collectionReference)
            return
        }
        val reference = NexusUrlParser.parse(nexusUrl)
        if (reference == null) {
            SnackbarManager.show(context.getString(R.string.nexus_enter_valid_nexus_url))
            return
        }
        scope.launch {
            try {
                loadingMessage = context.getString(R.string.nexus_resolving_nexus_mod)
                progress = 0f
                importProgress = null
                val modInfo = apiClient.getModInfo(reference.gameDomain, reference.modId)
                val files = apiClient.getModFiles(reference.gameDomain, reference.modId)
                if (files.isEmpty()) {
                    SnackbarManager.show(context.getString(R.string.nexus_no_downloadable_files_mod))
                    return@launch
                }
                val file = reference.fileId?.let { fileId -> files.firstOrNull { it.fileId == fileId } }
                if (file != null) {
                    importFile(reference, modInfo, file)
                } else {
                    pendingCollectionSelection = null
                    pendingFileSelection = PendingFileSelection(reference, modInfo, files)
                    selectedTab = ManageModsTab.IMPORT
                }
            } catch (e: NexusApiException) {
                SnackbarManager.show(nexusUserMessage(e, context.getString(R.string.nexus_resolve_url_failed)))
            } catch (e: Exception) {
                SnackbarManager.show(nexusUserMessage(e, context.getString(R.string.nexus_resolve_url_failed)))
            } finally {
                if (loadingMessage == context.getString(R.string.nexus_resolving_nexus_mod)) loadingMessage = null
            }
        }
    }

    fun buildRecipes(install: ModInstall, drafts: List<RecipeDraft>): List<ModPlacementRecipe> =
        BethesdaPlacementRecipeExpander.expand(
            gameName = libraryItem.name,
            install = install,
            recipes = drafts.map { draft -> draft.toRecipe(install.installId) },
        )

    LaunchedEffect(
        selectedInstall?.installId,
        placementChoice,
        recipeDrafts.toList(),
    ) {
        val install = selectedInstall
        val snapshot = recipeDrafts.toList()
        if (
            install == null ||
            !install.canPlaceFiles() ||
            placementChoice == PlacementChoice.AUTOMATIC ||
            snapshot.isEmpty() ||
            reviewedPlacementPlan != null
        ) {
            placementPlanPreview = null
            return@LaunchedEffect
        }
        delay(200)
        val recipes = BethesdaPlacementRecipeExpander.expand(
            gameName = libraryItem.name,
            install = install,
            recipes = snapshot.map { it.toRecipe(install.installId) },
        )
        val preview = withContext(Dispatchers.IO) {
            runCatching {
                ModMaterializer.materializationPlan(
                    install = install,
                    recipes = recipes,
                    gameRootDir = gameRootDir,
                    winePrefix = winePrefix,
                    captureTargetHashes = false,
                ).reviewedPlan
            }.getOrNull()
        }
        placementPlanPreview = preview?.let { PlacementPlanPreview(install.installId, snapshot, it) }
    }

    LaunchedEffect(
        selectedInstall?.installId,
        configurationDraftLoaded,
        placementChoice,
        automaticOptionSelections,
        riskyAutomaticPlanApproved,
        fomodSelectionDraft,
        recipeDrafts.toList(),
    ) {
        val install = selectedInstall ?: return@LaunchedEffect
        if (!configurationDraftLoaded || !install.canPlaceFiles()) return@LaunchedEffect
        delay(200)
        val draftSnapshot = recipeDrafts.toList()
        val placementChoiceSnapshot = placementChoice.name
        val automaticOptionsSnapshot = automaticOptionSelections
        val riskyTargetsApprovedSnapshot = riskyAutomaticPlanApproved
        val fomodSelectionsSnapshot = fomodSelectionDraft.mapValues { (_, values) -> values.sorted() }
        withContext(Dispatchers.IO) {
            val recipes = draftSnapshot.map { it.toRecipe(install.installId) }
            ModConfigurationDraftStore.write(
                NexusModManager.cacheRoot(context, install.appId),
                ModConfigurationDraft(
                    installId = install.installId,
                    archiveIdentity = ModConfigurationDraftStore.archiveIdentity(install),
                    placementChoice = placementChoiceSnapshot,
                    automaticOptions = automaticOptionsSnapshot,
                    riskyTargetsApproved = riskyTargetsApprovedSnapshot,
                    fomodSelections = fomodSelectionsSnapshot,
                    recipes = recipes.map(ModConfigurationRecipe::from),
                ),
            )
        }
    }

    suspend fun applyRecipesInternal(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        allowOverwrite: Boolean,
        reviewedPlan: ModInstallPlan? = null,
    ) {
        loadingMessage = context.getString(R.string.nexus_applying_mod_files)
        val result = withContext(Dispatchers.IO) {
            val applied = NexusModManager.applyInstall(
                context = context,
                install = install,
                recipes = recipes,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
                allowOverwrite = allowOverwrite,
                preserveStatusOnError = true,
                reviewedPlan = reviewedPlan,
            )
            if (applied.errors.isEmpty()) {
                dao.replaceRecipes(install.installId, recipes)
                val profile = activeProfile ?: ModProfileManager.ensureActiveProfile(dao, libraryItem.appId)
                val state = ModProfileManager.ensureStateForInstall(dao, profile, install.installId)
                dao.upsertProfileInstallState(state.copy(enabled = true, updatedAt = System.currentTimeMillis()))
            }
            applied
        }
        if (selectedInstall?.installId == install.installId) {
            val ownership = withContext(Dispatchers.IO) {
                val root = NexusModManager.cacheRoot(context, install.appId)
                ModOwnershipStore.read(root, install.installId) to ModOwnershipStore.readPrevious(root, install.installId)
            }
            selectedOwnership = ownership.first
            selectedPreviousOwnership = ownership.second
        }
        val message = if (result.errors.isEmpty()) {
            placementApplyFailure = null
            lastPlacementDrafts = recipes.map { it.toDraft() }
            placementNeededInstallIds = placementNeededInstallIds - install.installId
            val cleanupSuffix = if (result.warnings.isNotEmpty()) {
                context.getString(R.string.nexus_old_files_left_in_place_suffix, result.warnings.size)
            } else {
                ""
            }
            context.getString(
                R.string.nexus_placement_complete_summary,
                result.created + result.skipped,
                result.created,
                result.skipped,
                result.backedUp,
                cleanupSuffix,
            )
        } else {
            placementApplyFailure = PlacementApplyFailure(install.installId, install.modName, result.errors)
            context.getString(R.string.nexus_apply_failed_rolled_back, result.errors.size)
        }
        placementApplyStatusMessage = message
        SnackbarManager.show(message)
        if (selectedInstall?.installId == install.installId) {
            selectedInstall = install.copy(
                status = if (result.errors.isEmpty()) ModInstallStatus.APPLIED.name else install.status,
            )
        }
    }

    fun applyRecipes(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        allowOverwrite: Boolean,
        reviewedPlan: ModInstallPlan? = null,
    ) {
        if (modApplyInProgress || profileApplyInProgress) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_apply_already_running))
            return
        }
        scope.launch {
            modApplyInProgress = true
            diagnosticsPaused = true
            try {
                placementApplyStatusMessage = null
                applyRecipesInternal(install, recipes, allowOverwrite, reviewedPlan)
            } catch (e: Exception) {
                val message = e.message ?: context.getString(R.string.nexus_failed_to_apply_mod)
                placementApplyFailure = PlacementApplyFailure(
                    install.installId,
                    install.modName,
                    mapOf(install.modName to message),
                )
                placementApplyStatusMessage = message
                SnackbarManager.show(message)
            } finally {
                modApplyInProgress = false
                loadingMessage = null
                diagnosticsPaused = false
            }
        }
    }

    fun saveAndApply() {
        val install = selectedInstall ?: return
        if (!install.canPlaceFiles()) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_not_finished_importing))
            return
        }
        if (!recipeDrafts.all { draft -> roots.any { root -> root.type.name == draft.targetRoot } }) {
            SnackbarManager.show(context.getString(R.string.nexus_choose_destination_inside))
            return
        }
        val automaticPlan = if (placementChoice == PlacementChoice.AUTOMATIC) {
            automaticPlacementResult?.recommended?.plan
        } else {
            null
        }
        if (placementChoice == PlacementChoice.AUTOMATIC) {
            if (automaticPlan?.isComplete != true) {
                SnackbarManager.show(context.getString(R.string.nexus_plan_blocked))
                return
            }
        }
        val draftSnapshot = recipeDrafts.toList()
        val currentPreview = placementPlanPreview?.takeIf {
            it.installId == install.installId && it.drafts == draftSnapshot
        }?.plan
        val initialReviewedPlan = automaticPlan ?: reviewedPlacementPlan ?: currentPreview
        if (
            selectedFomodInstaller != null &&
            placementChoice != PlacementChoice.CUSTOM &&
            draftSnapshot.any { draft -> ModPlacementSources.decode(draft.sourceSubpath).isEmpty() }
        ) {
            SnackbarManager.show(context.getString(R.string.nexus_fomod_or_custom_required))
            return
        }
        if (modApplyInProgress) {
            SnackbarManager.show(context.getString(R.string.nexus_mod_apply_already_running))
            return
        }
        scope.launch {
            modApplyInProgress = true
            diagnosticsPaused = true
            try {
                placementApplyStatusMessage = null
                loadingMessage = context.getString(R.string.nexus_checking_target_files)
                val hasAmbiguousTargets = automaticPlan?.let { plan ->
                    withContext(Dispatchers.IO) { ModTargetResolver.inspectPlan(plan, roots) }
                        .ambiguousPaths.isNotEmpty()
                } == true
                if (hasAmbiguousTargets) {
                    SnackbarManager.show(context.getString(R.string.nexus_plan_blocked))
                    return@launch
                }
                val recipes = withContext(Dispatchers.Default) { buildRecipes(install, draftSnapshot) }
                val reviewedPlan = withContext(Dispatchers.IO) {
                    val base = initialReviewedPlan ?: ModMaterializer.materializationPlan(
                        install = install,
                        recipes = recipes,
                        gameRootDir = gameRootDir,
                        winePrefix = winePrefix,
                        captureTargetHashes = false,
                    ).reviewedPlan
                    PlacementRiskPolicy.enforce(base).withRiskApproval(riskyAutomaticPlanApproved)
                }
                if (!reviewedPlan.isComplete) {
                    reviewedPlacementPlan = reviewedPlan
                    val message = context.getString(R.string.nexus_plan_blocked)
                    placementApplyStatusMessage = message
                    SnackbarManager.show(message)
                    return@launch
                }
                val (rawConflicts, conflicts) = withContext(Dispatchers.IO) {
                    val raw = ModMaterializer.scanConflicts(
                        install = install,
                        recipes = recipes,
                        gameRootDir = gameRootDir,
                        winePrefix = winePrefix,
                        reviewedPlan = reviewedPlan,
                    )
                    raw to ModMaterializer.filterUnapprovedConflicts(
                        conflicts = raw,
                        manifests = dao.getOverwriteManifests(install.installId),
                    )
                }
                val hasOverwriteRecipe = recipes.any { it.mode == ModPlacementMode.OVERWRITE_COPY.name }
                if (conflicts.isNotEmpty() && hasOverwriteRecipe) {
                    pendingApply = PendingApply(install, recipes, conflicts, reviewedPlan)
                } else if (conflicts.isNotEmpty()) {
                    val message = context.getString(R.string.nexus_target_files_exist_overwrite)
                    placementApplyStatusMessage = message
                    SnackbarManager.show(message)
                } else {
                    applyRecipesInternal(
                        install,
                        recipes,
                        allowOverwrite = rawConflicts.isNotEmpty(),
                        reviewedPlan = reviewedPlan,
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: context.getString(R.string.nexus_scan_placement_conflicts_failed)
                placementApplyFailure = PlacementApplyFailure(
                    install.installId,
                    install.modName,
                    mapOf(install.modName to message),
                )
                placementApplyStatusMessage = message
                SnackbarManager.show(message)
            } finally {
                modApplyInProgress = false
                loadingMessage = null
                diagnosticsPaused = false
            }
        }
    }

    LaunchedEffect(selectedInstall?.installId) {
        val ownership = selectedInstall?.let { install ->
            withContext(Dispatchers.IO) {
                val root = NexusModManager.cacheRoot(context, install.appId)
                app.gamenative.mods.ModOwnershipStore.read(root, install.installId) to
                    app.gamenative.mods.ModOwnershipStore.readPrevious(root, install.installId)
            }
        }
        selectedOwnership = ownership?.first
        selectedPreviousOwnership = ownership?.second
    }

    LaunchedEffect(
        libraryItem.appId,
        installs.map { it.installId to it.status },
        selectedOwnership?.planDigest,
    ) {
        placementOwnershipManifests = withContext(Dispatchers.IO) {
            val root = NexusModManager.cacheRoot(context, libraryItem.appId)
            installs.mapNotNull { install -> ModOwnershipStore.read(root, install.installId) }
        }
    }

    fun shareDiagnostic(fileName: String, content: String) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val outputDir = File(context.cacheDir, "mod-diagnostics").apply { mkdirs() }
                File(outputDir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply {
                    writeText(content)
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.nexus_plan_export)))
        }
    }

    fun exportPlacementPlan(install: ModInstall, plan: ModInstallPlan) =
        shareDiagnostic("placement-${install.installId}.txt", plan.sanitizedManifest())

    fun exportHealthReport(report: ModHealthReport) =
        shareDiagnostic("mod-health-${libraryItem.appId}.txt", report.sanitizedManifest())

    val issueCount = conflictReports.size + bethesdaPluginIssues.size + bethesdaPluginAssetIssues.size +
        (healthReport?.issues?.size ?: 0) + if (placementApplyFailure == null) 0 else 1

    fun selectInstallForPlacement(install: ModInstall) {
        selectedInstall = install
        placementApplyStatusMessage = null
        loadRecipes(install)
        refreshEntries(install)
        selectedTab = ManageModsTab.PLACEMENT
    }

    fun cancelCollectionQueue() {
        collectionCancelRequested = true
        activeCollectionInstallId?.let(ModDownloadRegistry::requestCancel)
        collectionQueue.keys.forEach { key ->
            val current = collectionQueue[key] ?: return@forEach
            if (current.status == CollectionQueueStatus.QUEUED) {
                collectionQueue[key] = current.copy(
                    status = CollectionQueueStatus.CANCELED,
                    message = context.getString(R.string.nexus_queue_canceled),
                )
            }
        }
    }

    fun requestDialogDismiss() {
        if (collectionImportRunning) {
            SnackbarManager.show(context.getString(R.string.nexus_cancel_collection_before_closing))
        } else {
            onDismissRequest()
        }
    }

    @Composable
    fun CollectionSelectionContent(pending: PendingCollectionSelection) {
        val selectedFiles = pending.mods
            .filter { it.canImport && it.collectionKey() in selectedCollectionKeys }
            .mapNotNull { it.file }
        CollectionSelectionSection(
            pending = pending,
            selectedKeys = selectedCollectionKeys,
            queueItems = collectionQueue,
            availableBytes = NexusModManager.cacheRoot(context, libraryItem.appId).usableSpace,
            estimatedRequiredBytes = NexusModManager.estimateSequentialImportScratchBytes(selectedFiles),
            paused = collectionPaused,
            controlsEnabled = !collectionImportRunning,
            cancelEnabled = activeCollectionInstallId != null || collectionQueue.values.any {
                it.status == CollectionQueueStatus.QUEUED || it.status == CollectionQueueStatus.IMPORTING
            },
            onToggle = { key, selected ->
                selectedCollectionKeys = if (selected) selectedCollectionKeys + key else selectedCollectionKeys - key
            },
            onSelectAll = {
                selectedCollectionKeys = pending.mods.filter { it.canImport }.map { it.collectionKey() }.toSet()
            },
            onClearSelection = { selectedCollectionKeys = emptySet() },
            onImportSelected = { importCollection(pending, selectedCollectionKeys) },
            onRetryFailed = {
                val failedKeys = collectionQueue.values
                    .filter { it.status == CollectionQueueStatus.FAILED }
                    .map { it.key }
                    .toSet()
                selectedCollectionKeys = failedKeys
                importCollection(pending, failedKeys)
            },
            onPauseAll = { collectionPaused = true },
            onResumeAll = { collectionPaused = false },
            onCancelAll = ::cancelCollectionQueue,
        )
    }
    Dialog(
        onDismissRequest = ::requestDialogDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.option_manage_mods), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = libraryItem.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = ::requestDialogDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }

                HorizontalDivider()

                ManageModsSummaryBar(
                    installs = installs,
                    enabledByInstallId = profileEnabledByInstallId,
                    activeProfile = activeProfile,
                    activeDownload = activeDownload,
                    issueCount = issueCount,
                    diagnosticsLoading = diagnosticsLoading,
                    busyText = loadingMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )

                ManageModsTabs(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )

                val importScrollState = rememberScrollState()
                val modsScrollState = rememberScrollState()
                val placementScrollState = rememberScrollState()
                val issuesScrollState = rememberScrollState()
                val selectedScrollState = when (selectedTab) {
                    ManageModsTab.IMPORT -> importScrollState
                    ManageModsTab.MODS -> modsScrollState
                    ManageModsTab.PLACEMENT -> placementScrollState
                    ManageModsTab.ISSUES -> issuesScrollState
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(selectedScrollState)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (selectedTab) {
                        ManageModsTab.IMPORT -> {
                            LocalModImportSection(
                                onChooseArchive = { launchLocalSourcePicker(LocalModSourceType.ARCHIVE) },
                                onChooseFiles = { launchLocalSourcePicker(LocalModSourceType.FILES) },
                                onChooseFolder = { launchLocalSourcePicker(LocalModSourceType.FOLDER) },
                            )
                            if (!NexusIntegrationStatus.ONLINE_ACCESS_AVAILABLE) {
                                NexusIntegrationUnavailableSection()
                            } else {
                                NexusOAuthAccountSection(
                                    connected = nexusAuthState.isConnected,
                                    connecting = nexusAuthState.connection == NexusConnectionState.CONNECTING,
                                    accountName = nexusAuthState.account?.name,
                                    premium = nexusAuthState.account?.isPremium,
                                    errorMessage = nexusAuthErrorMessage,
                                    actionInProgress = nexusAuthActionInProgress,
                                    onConnect = ::connectNexusAccount,
                                    onCancelConnect = ::cancelNexusAuthorization,
                                    onDisconnect = ::disconnectNexusAccount,
                                )
                                if (nexusAuthState.isConnected) {
                                    ImportSection(
                                        nexusUrl = nexusUrl,
                                        onUrlChange = { nexusUrl = it },
                                        onImport = ::resolveUrlAndImport,
                                    )
                                    pendingFileSelection?.let { pending ->
                                        FileSelectionSection(
                                            pending = pending,
                                            onImport = { file ->
                                                val authorization = pending.reference.downloadAuthorization
                                                    ?.takeIf { pending.reference.fileId == file.fileId }
                                                importFile(
                                                    reference = pending.reference.copy(
                                                        fileId = file.fileId,
                                                        downloadAuthorization = authorization,
                                                    ),
                                                    modInfo = pending.modInfo,
                                                    file = file,
                                                )
                                            },
                                        )
                                    }
                                    pendingCollectionSelection?.let { pending -> CollectionSelectionContent(pending) }
                                }
                            }
                        }

                        ManageModsTab.MODS -> {
                            ProfilesSection(
                                profiles = profiles,
                                activeProfile = activeProfile,
                                onActivate = ::activateProfile,
                                onCreate = { pendingProfileNameEdit = PendingProfileNameEdit(null, nextProfileName(profiles, context.getString(R.string.nexus_profile_name_prefix))) },
                                onRename = { profile -> pendingProfileNameEdit = PendingProfileNameEdit(profile, profile.name) },
                                onDelete = { profile -> pendingProfileDelete = profile },
                            )
                            InstalledModsSection(
                                installs = installs,
                                priorityByInstallId = priorityByInstallId,
                                enabledByInstallId = profileEnabledByInstallId,
                                selectedInstall = selectedInstall,
                                placementNeededInstallIds = placementNeededInstallIds,
                                activeInstallIds = activeDownloads.keys,
                                onSelect = ::selectInstallForPlacement,
                                onSetEnabled = ::setProfileInstallEnabled,
                                onDelete = { install ->
                                    scope.launch {
                                        val skipped = NexusModManager.deleteInstall(
                                            context = context,
                                            install = install,
                                            restoreBackups = true,
                                            gameRootDir = gameRootDir,
                                            winePrefix = winePrefix,
                                        )
                                        if (selectedInstall?.installId == install.installId) selectedInstall = null
                                        SnackbarManager.show(
                                            if (skipped.isEmpty()) {
                                                context.getString(R.string.nexus_mod_deleted)
                                            } else {
                                                context.getString(R.string.nexus_mod_deleted_with_skipped, skipped.size)
                                            },
                                        )
                                    }
                                },
                                onRetry = ::retryInstall,
                                onMovePriority = ::moveInstallPriority,
                                onApplyOrder = { applyProfileOrder(allowOverwrite = false) },
                            )
                        }

                        ManageModsTab.PLACEMENT -> {
                            selectedInstall?.let { install ->
                                val presetOptions = placementPresetOptions(libraryItem.name, archiveEntries, defaultDraft)
                                val automaticPlacement = automaticPlacementResult
                                    ?: AutomaticPlacementResult(emptyList(), null, emptyList())
                                PlacementSection(
                                    install = install,
                                    entries = archiveEntries,
                                    fomodInstaller = selectedFomodInstaller,
                                    fomodEnvironment = fomodEnvironment,
                                    fomodBaseDraft = defaultDraft,
                                    roots = roots,
                                    drafts = recipeDrafts,
                                    presetOptions = presetOptions,
                                    automaticPlacement = automaticPlacement,
                                    automaticPlanLoading = automaticPlacementLoading,
                                    selectedAutomaticOptions = automaticOptionSelections,
                                    onAutomaticOptionSelected = { groupId, sourceDirectory ->
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        placementChoice = PlacementChoice.AUTOMATIC
                                        val updatedSelections = automaticOptionSelections + (groupId to sourceDirectory)
                                        automaticOptionSelections = updatedSelections
                                    },
                                    riskyAutomaticPlanApproved = riskyAutomaticPlanApproved,
                                    onRiskyAutomaticPlanApprovalChange = { approved ->
                                        placementApplyStatusMessage = null
                                        riskyAutomaticPlanApproved = approved
                                    },
                                    reviewedPlan = reviewedPlacementPlan ?: placementPlanPreview?.takeIf {
                                        it.installId == install.installId && it.drafts == recipeDrafts.toList()
                                    }?.plan,
                                    initialFomodSelections = fomodSelectionDraft,
                                    onFomodSelectionsChanged = { fomodSelectionDraft = it },
                                    previousOwnership = selectedOwnership,
                                    ownershipManifests = placementOwnershipManifests,
                                    installNamesById = installs.associate { it.installId to it.modName },
                                    canRestorePrevious = selectedPreviousOwnership?.reviewedPlanOrNull() != null,
                                    onRestorePrevious = { restorePreviousDeployment(install.installId) },
                                    placementChoice = placementChoice,
                                    canUseLastPlacement = lastPlacementDrafts.isNotEmpty(),
                                    onPlacementChoiceChange = { choice ->
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        val currentDrafts = recipeDrafts.toList()
                                        placementChoice = choice
                                        recipeDrafts.clear()
                                        recipeDrafts += when (choice) {
                                            PlacementChoice.AUTOMATIC -> automaticPlacementResult
                                                ?.let { automaticDraftsFor(it, libraryItem.name, archiveEntries, defaultDraft) }
                                                .orEmpty()
                                                .ifEmpty { listOf(defaultDraft) }
                                            PlacementChoice.PRESET -> presetOptions.firstOrNull()?.drafts
                                                ?: automaticDraftsFor(libraryItem.name, archiveEntries, defaultDraft)
                                            PlacementChoice.LAST_USED -> compatibleLastPlacementDrafts(lastPlacementDrafts, archiveEntries, defaultDraft)
                                            PlacementChoice.CUSTOM -> currentDrafts.ifEmpty { automaticDraftsFor(libraryItem.name, archiveEntries, defaultDraft) }
                                        }
                                    },
                                    onUseLastPlacement = {
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        placementChoice = PlacementChoice.LAST_USED
                                        recipeDrafts.clear()
                                        recipeDrafts += compatibleLastPlacementDrafts(lastPlacementDrafts, archiveEntries, defaultDraft)
                                    },
                                    onPresetSelected = { drafts ->
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        placementChoice = PlacementChoice.PRESET
                                        recipeDrafts.clear()
                                        recipeDrafts += drafts
                                    },
                                    onUpdateDraft = { index, draft ->
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        recipeDrafts[index] = draft
                                    },
                                    onAddDraft = {
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        recipeDrafts += defaultDraft
                                    },
                                    onRemoveDraft = { index ->
                                        if (recipeDrafts.size > 1) {
                                            placementApplyStatusMessage = null
                                            reviewedPlacementPlan = null
                                            recipeDrafts.removeAt(index)
                                        }
                                    },
                                    onFomodRecipes = { drafts, plan, unsupportedCount ->
                                        placementApplyStatusMessage = null
                                        placementChoice = PlacementChoice.CUSTOM
                                        reviewedPlacementPlan = plan
                                        recipeDrafts.clear()
                                        recipeDrafts += drafts
                                        if (unsupportedCount > 0) {
                                            SnackbarManager.show(context.getString(R.string.nexus_fomod_mappings_need_manual_placement, unsupportedCount))
                                        } else {
                                            SnackbarManager.show(context.getString(R.string.nexus_fomod_choices_added))
                                        }
                                    },
                                    onUseAutomaticCandidate = { candidate: AutomaticPlacementCandidate ->
                                        placementApplyStatusMessage = null
                                        placementChoice = PlacementChoice.CUSTOM
                                        reviewedPlacementPlan = candidate.plan
                                        recipeDrafts.clear()
                                        recipeDrafts += automaticDraftsFor(
                                            AutomaticPlacementResult(listOf(candidate), candidate),
                                            libraryItem.name,
                                            archiveEntries,
                                            defaultDraft,
                                        )
                                    },
                                    onResolveAutomaticPlan = { unresolvedSources ->
                                        placementApplyStatusMessage = null
                                        reviewedPlacementPlan = null
                                        placementChoice = PlacementChoice.CUSTOM
                                        val resolvedDrafts = draftsWithUnresolvedSources(recipeDrafts.toList(), unresolvedSources, defaultDraft)
                                        recipeDrafts.clear()
                                        recipeDrafts += resolvedDrafts
                                    },
                                    applyStatusMessage = placementApplyStatusMessage,
                                    applyErrors = placementApplyFailure
                                        ?.takeIf { it.installId == install.installId }
                                        ?.errors
                                        .orEmpty(),
                                    onExportPlan = { plan -> exportPlacementPlan(install, plan) },
                                    onSaveAndApply = ::saveAndApply,
                                )
                            } ?: EmptyWorkflowSection(stringResource(R.string.nexus_no_mod_selected), stringResource(R.string.nexus_select_mod_from_mods_tab))
                        }

                        ManageModsTab.ISSUES -> {
                            placementApplyFailure?.let { failure ->
                                PlacementApplyFailureSection(
                                    failure = failure,
                                    onReconfigure = {
                                        installs.firstOrNull { it.installId == failure.installId }
                                            ?.let(::selectInstallForPlacement)
                                    },
                                )
                            }
                            InstallHealthSection(
                                report = healthReport,
                                loading = healthLoading,
                                onCheck = ::runInstallHealthCheck,
                                onRebuild = { applyProfileOrder(allowOverwrite = false) },
                                onReconfigure = { installId ->
                                    installs.firstOrNull { it.installId == installId }?.let(::selectInstallForPlacement)
                                    selectedTab = ManageModsTab.PLACEMENT
                                },
                                onAdoptOwnership = ::adoptInstallOwnership,
                                onRestorePrevious = ::restorePreviousDeployment,
                                onExport = ::exportHealthReport,
                            )
                            StorageCleanupSection(
                                breakdown = storageBreakdown,
                                loading = storageLoading,
                                onScan = ::refreshStorageBreakdown,
                                onCleanTemp = { runStorageCleanup(failedArchives = false) },
                                onDeleteFailedArchives = { runStorageCleanup(failedArchives = true) },
                                onCleanRedundantBackups = ::cleanRedundantBackups,
                            )
                            if (issueCount == 0 && bethesdaGame == null && bethesdaPlugins.isEmpty()) {
                                EmptyWorkflowSection(stringResource(R.string.nexus_no_issues_found), stringResource(R.string.nexus_no_issues_description))
                            }
                            if (conflictReports.isNotEmpty()) {
                                ConflictSummarySection(
                                    conflicts = conflictReports,
                                    onSelectInstall = { installId ->
                                        installs.firstOrNull { it.installId == installId }?.let(::selectInstallForPlacement)
                                    },
                                    onMovePriority = ::moveInstallPriority,
                                    onMakeWinner = ::makeInstallHighestPriority,
                                )
                            }
                            if (bethesdaPluginIssues.isNotEmpty() || bethesdaPluginAssetIssues.isNotEmpty()) {
                                BethesdaPluginDiagnosticsSection(
                                    issues = bethesdaPluginIssues,
                                    assetIssues = bethesdaPluginAssetIssues,
                                )
                            }
                            if (bethesdaGame != null || bethesdaPlugins.isNotEmpty()) {
                                BethesdaPluginsSection(
                                    game = bethesdaGame,
                                    plugins = bethesdaPlugins,
                                    issues = bethesdaPluginIssues,
                                    assetIssues = bethesdaPluginAssetIssues,
                                    onToggle = { plugin ->
                                        writePluginState(
                                            bethesdaPlugins.map {
                                                if (it.fileName == plugin.fileName) it.copy(enabled = !it.enabled) else it
                                            },
                                        )
                                    },
                                    onMove = { plugin, direction ->
                                        val index = bethesdaPlugins.indexOfFirst { it.fileName == plugin.fileName }
                                        val otherIndex = index + direction
                                        if (index >= 0 && otherIndex in bethesdaPlugins.indices) {
                                            writePluginState(bethesdaPlugins.toMutableList().apply {
                                                val moved = removeAt(index)
                                                add(otherIndex, moved)
                                            })
                                        }
                                    },
                                    onFixOrder = ::movePluginMastersBefore,
                                )
                            }
                        }
                    }
                }

                val displayedImportProgress = activeImportProgress ?: importProgress
                val displayedLoadingMessage = activeDownload?.let { "${it.displayName}: ${localizedImportStatus(it.status)}" } ?: loadingMessage
                displayedLoadingMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(message, style = MaterialTheme.typography.bodyMedium)
                            }
                            displayedImportProgress?.let { detail ->
                                val reportsByteProgress =
                                    detail.status == "Downloading" || detail.status == "Copying"
                                if (reportsByteProgress && detail.downloadedBytes > 0L) {
                                    val totalText = if (detail.totalBytes > 0L) {
                                        StorageUtils.formatBinarySize(detail.totalBytes)
                                    } else {
                                        stringResource(R.string.nexus_progress_unknown)
                                    }
                                    val progressString = if (detail.status == "Copying") {
                                        R.string.local_mod_copied_progress
                                    } else {
                                        R.string.nexus_downloaded_progress
                                    }
                                    Text(
                                        text = stringResource(
                                            progressString,
                                            StorageUtils.formatBinarySize(detail.downloadedBytes),
                                            totalText,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (detail.status == "Unpacking") {
                                    Text(
                                        text = when {
                                            detail.totalBytes > 0L && detail.downloadedBytes > 0L ->
                                                stringResource(
                                                    R.string.nexus_unpacked_progress,
                                                    StorageUtils.formatBinarySize(detail.downloadedBytes),
                                                    StorageUtils.formatBinarySize(detail.totalBytes),
                                                )
                                            detail.downloadedBytes > 0L ->
                                                stringResource(R.string.nexus_unpacked_size, StorageUtils.formatBinarySize(detail.downloadedBytes))
                                            else -> stringResource(R.string.nexus_unpacking_archive)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            val displayedProgress = displayedImportProgress?.progress ?: progress
                            if (displayedProgress > 0f && displayedProgress < 1f) {
                                LinearProgressIndicator(progress = { displayedProgress }, modifier = Modifier.fillMaxWidth())
                            } else if (displayedImportProgress?.status == "Unpacking") {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                }
            }
            if (snackbarController.ownsHost(snackbarOwner)) {
                NexusDialogSnackbarHost(
                    hostState = snackbarController.hostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }

    pendingLocalImport?.let { pending ->
        LocalModReviewDialog(
            pending = pending,
            onPendingChange = { pendingLocalImport = it },
            onConfirm = { importLocalMod(pending) },
            onDismiss = { pendingLocalImport = null },
        )
    }

    pendingApply?.let { pending ->
        OverwriteConfirmDialog(
            title = stringResource(R.string.nexus_overwrite_existing_title),
            message = stringResource(R.string.nexus_overwrite_existing_message),
            conflicts = pending.conflicts,
            confirmLabel = stringResource(R.string.nexus_backup_overwrite),
            onConfirm = {
                pendingApply = null
                applyRecipes(
                    pending.install,
                    pending.recipes,
                    allowOverwrite = true,
                    reviewedPlan = pending.reviewedPlan,
                )
            },
            onDismiss = { pendingApply = null },
        )
    }

    pendingProfileApply?.let { pending ->
        OverwriteConfirmDialog(
            title = stringResource(R.string.nexus_apply_mod_order_title),
            message = stringResource(R.string.nexus_apply_mod_order_message),
            conflicts = pending.conflicts,
            confirmLabel = stringResource(R.string.nexus_backup_apply),
            onConfirm = {
                pendingProfileApply = null
                applyProfileOrder(allowOverwrite = true)
            },
            onDismiss = { pendingProfileApply = null },
        )
    }

    pendingProfileNameEdit?.let { edit ->
        ProfileNameDialog(
            title = if (edit.profile == null) stringResource(R.string.nexus_profile_new_title) else stringResource(R.string.nexus_profile_rename_title),
            initialName = edit.initialName,
            onConfirm = { name ->
                val existing = profiles.any { profile ->
                    profile.profileId != edit.profile?.profileId && profile.name.equals(name.trim(), ignoreCase = true)
                }
                if (existing) {
                            SnackbarManager.show(context.getString(R.string.nexus_profile_name_duplicate))
                } else if (edit.profile == null) {
                    createProfile(name)
                } else {
                    renameProfile(edit.profile, name)
                }
            },
            onDismiss = { pendingProfileNameEdit = null },
        )
    }

    pendingProfileDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingProfileDelete = null },
            title = { Text(stringResource(R.string.nexus_profile_delete_title)) },
            text = { Text(stringResource(R.string.nexus_profile_delete_message)) },
            confirmButton = {
                TextButton(onClick = { deleteProfile(profile) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProfileDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
