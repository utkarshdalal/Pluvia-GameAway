package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SnippetFolder
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.AutomaticPlacementPlanner
import app.gamenative.mods.AutomaticPlacementCandidate
import app.gamenative.mods.AutomaticPlacementResult
import app.gamenative.mods.FomodInstaller
import app.gamenative.mods.FomodEnvironmentSnapshot
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ModInstallPlan
import app.gamenative.mods.ModOwnershipManifest
import app.gamenative.mods.ModOwnershipPlanDiffer
import app.gamenative.mods.ModReconfigurationDiff
import app.gamenative.mods.ModPlacementPreset
import app.gamenative.mods.ModPlacementSources
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.ModTargetPlanInspection
import app.gamenative.mods.PlannedFileStatus
import app.gamenative.mods.PlacementRisk
import app.gamenative.mods.PlacementRiskPolicy
import app.gamenative.mods.ResolvedModTargetRoot
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
@Composable
internal fun StatusChip(status: String) {
    val (label, color, contentColor) = when (status) {
        ModInstallStatus.READY.name -> Triple(stringResource(R.string.nexus_status_ready), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ModInstallStatus.APPLIED.name -> Triple(stringResource(R.string.nexus_status_applied), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ModInstallStatus.DISABLED.name -> Triple(stringResource(R.string.nexus_status_disabled), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
        ModInstallStatus.ERROR.name -> Triple(stringResource(R.string.nexus_status_failed), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ModInstallStatus.IMPORTING.name -> Triple(stringResource(R.string.nexus_status_importing), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ModInstallStatus.PAUSED.name -> Triple(stringResource(R.string.nexus_status_paused), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ModInstallStatus.CANCELED.name -> Triple(stringResource(R.string.nexus_status_canceled), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
        "PROFILE_DISABLED" -> Triple(stringResource(R.string.nexus_status_off_in_profile), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
        "NEEDS_PLACEMENT" -> Triple(stringResource(R.string.nexus_status_needs_placement), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        "ENABLED" -> Triple(stringResource(R.string.nexus_status_enabled), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        "WINS" -> Triple(stringResource(R.string.nexus_status_wins), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else -> Triple(status.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
@Composable
internal fun PlacementSection(
    install: ModInstall,
    entries: List<ModArchiveEntry>,
    fomodInstaller: FomodInstaller?,
    fomodEnvironment: FomodEnvironmentSnapshot,
    fomodBaseDraft: RecipeDraft,
    roots: List<ResolvedModTargetRoot>,
    drafts: List<RecipeDraft>,
    presetOptions: List<PlacementPresetOption>,
    automaticPlacement: AutomaticPlacementResult,
    automaticPlanLoading: Boolean,
    selectedAutomaticOptions: Map<String, String>,
    onAutomaticOptionSelected: (String, String) -> Unit,
    riskyAutomaticPlanApproved: Boolean,
    onRiskyAutomaticPlanApprovalChange: (Boolean) -> Unit,
    reviewedPlan: ModInstallPlan?,
    initialFomodSelections: Map<String, Set<String>>,
    onFomodSelectionsChanged: (Map<String, Set<String>>) -> Unit,
    previousOwnership: ModOwnershipManifest?,
    ownershipManifests: List<ModOwnershipManifest> = emptyList(),
    installNamesById: Map<String, String> = emptyMap(),
    canRestorePrevious: Boolean,
    onRestorePrevious: () -> Unit,
    placementChoice: PlacementChoice,
    canUseLastPlacement: Boolean,
    onPlacementChoiceChange: (PlacementChoice) -> Unit,
    onUseAutomaticCandidate: (AutomaticPlacementCandidate) -> Unit = {},
    onResolveAutomaticPlan: (List<String>) -> Unit = {},
    onUseLastPlacement: () -> Unit,
    onPresetSelected: (List<RecipeDraft>) -> Unit,
    onUpdateDraft: (Int, RecipeDraft) -> Unit,
    onAddDraft: () -> Unit,
    onRemoveDraft: (Int) -> Unit,
    onFomodRecipes: (List<RecipeDraft>, ModInstallPlan?, Int) -> Unit,
    applyStatusMessage: String?,
    applyErrors: Map<String, String> = emptyMap(),
    onExportPlan: (ModInstallPlan) -> Unit,
    onSaveAndApply: () -> Unit,
) {
    var showArchiveBrowser by remember(install.installId, entries) { mutableStateOf(false) }
    var showFomodWizard by remember(install.installId, fomodInstaller) { mutableStateOf(false) }
    var showAdvancedPlacement by remember(install.installId) { mutableStateOf(placementChoice != PlacementChoice.AUTOMATIC) }
    var customDraftPage by remember(install.installId) { mutableIntStateOf(0) }
    val visibleDraftPage = remember(drafts.size, customDraftPage) {
        placementDraftPage(drafts.size, customDraftPage)
    }
    LaunchedEffect(placementChoice) {
        if (placementChoice != PlacementChoice.AUTOMATIC) showAdvancedPlacement = true
    }
    LaunchedEffect(visibleDraftPage.pageIndex) {
        if (customDraftPage != visibleDraftPage.pageIndex) customDraftPage = visibleDraftPage.pageIndex
    }
    val destinationsValid = drafts.all { draft -> roots.any { it.type.name == draft.targetRoot } }
    val automaticPlan = remember(automaticPlacement.recommended?.plan, riskyAutomaticPlanApproved) {
        automaticPlacement.recommended?.plan
            ?.let(PlacementRiskPolicy::enforce)
            ?.withRiskApproval(riskyAutomaticPlanApproved)
    }
    val configuredPlan = remember(reviewedPlan, riskyAutomaticPlanApproved) {
        reviewedPlan
            ?.let(PlacementRiskPolicy::enforce)
            ?.withRiskApproval(riskyAutomaticPlanApproved)
    }
    val visiblePlan = if (placementChoice == PlacementChoice.AUTOMATIC) automaticPlan else configuredPlan
    val reconfigurationDiff = remember(previousOwnership, visiblePlan) {
        visiblePlan?.takeIf { previousOwnership != null }?.let { ModOwnershipPlanDiffer.compare(previousOwnership, it) }
    }
    var targetInspection by remember(automaticPlan, roots) { mutableStateOf<ModTargetPlanInspection?>(null) }
    var targetInspectionLoading by remember(automaticPlan, roots) { mutableStateOf(automaticPlan != null) }
    LaunchedEffect(automaticPlan, roots) {
        targetInspectionLoading = automaticPlan != null
        targetInspection = automaticPlan?.let { plan ->
            withContext(Dispatchers.IO) { ModTargetResolver.inspectPlan(plan, roots) }
        }
        targetInspectionLoading = false
    }
    val applyBlocked = when {
        placementChoice == PlacementChoice.AUTOMATIC -> {
            automaticPlanLoading ||
                targetInspectionLoading ||
                automaticPlan?.isComplete != true ||
                targetInspection?.ambiguousPaths?.isNotEmpty() == true
        }
        visiblePlan != null -> !visiblePlan.isComplete
        else -> false
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nexus_placement_where_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(install.modName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!install.canPlaceFiles()) {
                Text(
                    text = when (install.status) {
                        ModInstallStatus.IMPORTING.name -> stringResource(R.string.nexus_mod_still_importing)
                        ModInstallStatus.PAUSED.name -> install.errorMessage().ifBlank { stringResource(R.string.nexus_import_paused) }
                        ModInstallStatus.CANCELED.name -> install.errorMessage().ifBlank { stringResource(R.string.nexus_import_canceled) }
                        else -> install.errorMessage().ifBlank { stringResource(R.string.nexus_mod_not_finished_importing) }
                    },
                    color = if (install.status == ModInstallStatus.ERROR.name) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            } else {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.nexus_mod_not_finished_importing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val firstDraft = drafts.firstOrNull()
                ArchivePreview(
                    entries = entries,
                    importFinished = true,
                    onBrowse = { showArchiveBrowser = true },
                )

                fomodInstaller?.let {
                    FomodSummarySection(
                        installer = it,
                        onConfigure = { showFomodWizard = true },
                    )
                }

                PlacementChoiceSelector(
                    selected = placementChoice,
                    hasPresets = presetOptions.isNotEmpty(),
                    canUseLastPlacement = canUseLastPlacement,
                    showAdvanced = showAdvancedPlacement,
                    onSelect = {
                        if (it == PlacementChoice.LAST_USED) onUseLastPlacement() else onPlacementChoiceChange(it)
                    },
                )
                TextButton(onClick = { showAdvancedPlacement = !showAdvancedPlacement }) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showAdvancedPlacement) {
                            stringResource(R.string.nexus_hide_advanced_placement)
                        } else {
                            stringResource(R.string.nexus_advanced_placement)
                        },
                    )
                }
                if (!showAdvancedPlacement) {
                    Text(
                        stringResource(R.string.nexus_advanced_placement_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (placementChoice == PlacementChoice.AUTOMATIC && automaticPlanLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.nexus_building_install_plan), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (placementChoice == PlacementChoice.AUTOMATIC && automaticPlan != null) {
                    PlacementPlanReview(
                        automaticPlacement = automaticPlacement,
                        plan = automaticPlan,
                        roots = roots,
                        diff = reconfigurationDiff,
                        caseMerges = targetInspection?.caseMerges.orEmpty(),
                        ambiguousPaths = targetInspection?.ambiguousPaths.orEmpty(),
                        ownershipManifests = ownershipManifests,
                        selectedInstallId = install.installId,
                        installNamesById = installNamesById,
                        onResolve = {
                            val unresolvedSources = automaticPlan.files.filter {
                                it.status == PlannedFileStatus.UNSUPPORTED ||
                                    it.status == PlannedFileStatus.MISSING ||
                                    it.status == PlannedFileStatus.CONFLICTED ||
                                    it.targetRelativePath in targetInspection?.ambiguousPaths.orEmpty()
                            }.map { it.sourceRelativePath }.distinct()
                            onResolveAutomaticPlan(unresolvedSources)
                        },
                        onUseCandidate = onUseAutomaticCandidate,
                        onExport = { onExportPlan(automaticPlan) },
                    )
                } else if (placementChoice == PlacementChoice.AUTOMATIC) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.nexus_plan_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = { onResolveAutomaticPlan(emptyList()) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.nexus_placement_custom))
                        }
                    }
                } else if (configuredPlan != null) {
                    val unresolvedSources = configuredPlan.files.filter {
                        it.status == PlannedFileStatus.UNSUPPORTED ||
                            it.status == PlannedFileStatus.MISSING ||
                            it.status == PlannedFileStatus.CONFLICTED
                    }.map { it.sourceRelativePath }.distinct()
                    PlacementPlanReview(
                        automaticPlacement = null,
                        plan = configuredPlan,
                        roots = roots,
                        diff = reconfigurationDiff,
                        caseMerges = emptyList(),
                        ambiguousPaths = emptyList(),
                        ownershipManifests = ownershipManifests,
                        selectedInstallId = install.installId,
                        installNamesById = installNamesById,
                        onResolve = unresolvedSources.takeIf { it.isNotEmpty() }?.let { sources ->
                            { onResolveAutomaticPlan(sources) }
                        },
                        onUseCandidate = onUseAutomaticCandidate,
                        onExport = { onExportPlan(configuredPlan) },
                    )
                }

                if (placementChoice == PlacementChoice.AUTOMATIC && automaticPlacement.optionGroups.isNotEmpty()) {
                    automaticPlacement.optionGroups.forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.nexus_choose_package_variant), style = MaterialTheme.typography.labelLarge)
                            Text(group.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            group.choices.forEach { choice ->
                                val selected = selectedAutomaticOptions[group.stableId] == choice.sourceDirectory
                                if (selected) {
                                    Button(
                                        onClick = { onAutomaticOptionSelected(group.stableId, choice.sourceDirectory) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(choice.sourceDirectory) }
                                } else {
                                    OutlinedButton(
                                        onClick = { onAutomaticOptionSelected(group.stableId, choice.sourceDirectory) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(choice.sourceDirectory) }
                                }
                            }
                        }
                    }
                }

                if (
                    visiblePlan?.files.orEmpty().any { it.risk == PlacementRisk.UNSAFE }
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRiskyAutomaticPlanApprovalChange(!riskyAutomaticPlanApproved) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = riskyAutomaticPlanApproved,
                                onCheckedChange = onRiskyAutomaticPlanApprovalChange,
                            )
                            Column {
                                Text(
                                    stringResource(R.string.nexus_confirm_risky_game_root_files),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    stringResource(R.string.nexus_confirm_risky_game_root_files_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                if (placementChoice == PlacementChoice.PRESET && presetOptions.isNotEmpty()) {
                    PresetSelectionSection(
                        presets = presetOptions,
                        selectedDrafts = drafts,
                        onSelect = onPresetSelected,
                    )
                }

                if (firstDraft != null && placementChoice != PlacementChoice.CUSTOM) {
                    drafts.forEach { draft ->
                        PlacementSummaryCard(
                            choice = placementChoice,
                            draft = draft,
                            roots = roots,
                        )
                    }
                } else {
                    if (visibleDraftPage.pageCount > 1) {
                        PlacementDraftPager(
                            page = visibleDraftPage,
                            totalRules = drafts.size,
                            onPrevious = { customDraftPage-- },
                            onNext = { customDraftPage++ },
                        )
                    }
                    (visibleDraftPage.startIndex until visibleDraftPage.endIndexExclusive).forEach { index ->
                        val draft = drafts[index]
                        PlacementDraftEditor(
                            index = index,
                            draft = draft,
                            entries = entries,
                            roots = roots,
                            plan = visiblePlan,
                            ownershipManifests = ownershipManifests,
                            selectedInstallId = install.installId,
                            installNamesById = installNamesById,
                            showAdvanced = showAdvancedPlacement,
                            canRemove = drafts.size > 1,
                            onUpdate = { onUpdateDraft(index, it) },
                            onRemove = { onRemoveDraft(index) },
                        )
                    }
                }

                if (!destinationsValid) {
                    Text(
                        text = stringResource(R.string.nexus_choose_destination_inside),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                applyStatusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (applyErrors.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (applyErrors.isNotEmpty()) {
                    PlacementApplyFailureDetails(applyErrors)
                }

                if (canRestorePrevious) {
                    OutlinedButton(
                        onClick = onRestorePrevious,
                        enabled = applyStatusMessage == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.nexus_restore_previous_deployment))
                    }
                    Text(
                        stringResource(R.string.nexus_restore_previous_deployment_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compactActions = maxWidth < 420.dp
                    if (compactActions) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            if (placementChoice == PlacementChoice.CUSTOM) {
                                OutlinedButton(onClick = onAddDraft, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.nexus_add_location))
                                }
                            }
                            Button(
                                onClick = onSaveAndApply,
                                enabled = roots.isNotEmpty() && destinationsValid && !applyBlocked,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.nexus_apply_mod))
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (placementChoice == PlacementChoice.CUSTOM) {
                                OutlinedButton(onClick = onAddDraft) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.nexus_add_location))
                                }
                            }
                            Button(
                                onClick = onSaveAndApply,
                                enabled = roots.isNotEmpty() && destinationsValid && !applyBlocked,
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.nexus_apply_mod))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showArchiveBrowser) {
        ArchiveBrowserDialog(
            title = stringResource(R.string.nexus_files_in_mod),
            entries = entries,
            onSelect = null,
            onDismiss = { showArchiveBrowser = false },
        )
    }

    if (showFomodWizard && fomodInstaller != null) {
        FomodWizardDialog(
            installId = install.installId,
            installer = fomodInstaller,
            environment = fomodEnvironment,
            extractedRoot = File(install.extractedPath),
            // FOMOD destinations are relative to the game's content root. Reusing the
            // first generated mapping here recursively prefixes that mapping whenever
            // an installer is reconfigured.
            baseDraft = fomodBaseDraft,
            initialSelections = initialFomodSelections,
            onSelectionsChanged = onFomodSelectionsChanged,
            onApply = { generatedDrafts, plan, unsupportedCount ->
                showFomodWizard = false
                onFomodRecipes(generatedDrafts, plan, unsupportedCount)
            },
            onDismiss = { showFomodWizard = false },
        )
    }
}

@Composable
internal fun PlacementApplyFailureDetails(errors: Map<String, String>) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.nexus_apply_failure_details, errors.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            errors.entries.take(8).forEach { (path, reason) ->
                Text(
                    compactPlacementErrorPath(path),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (errors.size > 8) {
                Text(
                    stringResource(R.string.nexus_more_prefixed, errors.size - 8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

internal fun compactPlacementErrorPath(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    return normalized.split('/').filter(String::isNotBlank).takeLast(4).joinToString("/").ifBlank { path }
}

@Composable
private fun PlacementDraftPager(
    page: PlacementDraftPage,
    totalRules: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = page.pageIndex > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nexus_previous_rule_page))
            }
            Text(
                stringResource(
                    R.string.nexus_placement_rule_range,
                    page.startIndex + 1,
                    page.endIndexExclusive,
                    totalRules,
                ),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onNext, enabled = page.pageIndex < page.pageCount - 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.nexus_next_rule_page))
            }
        }
    }
}

@Composable
private fun PlacementPlanReview(
    automaticPlacement: AutomaticPlacementResult?,
    plan: ModInstallPlan,
    roots: List<ResolvedModTargetRoot>,
    diff: ModReconfigurationDiff?,
    caseMerges: List<String>,
    ambiguousPaths: List<String>,
    ownershipManifests: List<ModOwnershipManifest>,
    selectedInstallId: String,
    installNamesById: Map<String, String>,
    onResolve: (() -> Unit)?,
    onUseCandidate: (AutomaticPlacementCandidate) -> Unit,
    onExport: () -> Unit,
) {
    val stalePlacementReason = stringResource(R.string.nexus_stale_placement_reason)
    var showWhy by remember(plan.digest) { mutableStateOf(false) }
    var showAllFiles by remember(plan.digest) { mutableStateOf(false) }
    var browseTarget by remember(plan.digest) { mutableStateOf<RecipeDraft?>(null) }
    var rows by remember(plan.digest, diff, roots, stalePlacementReason) {
        mutableStateOf<List<PlacementReviewRow>>(emptyList())
    }
    var rowsLoading by remember(plan.digest, diff, roots, stalePlacementReason) { mutableStateOf(true) }
    LaunchedEffect(plan, diff, roots, stalePlacementReason) {
        rowsLoading = true
        rows = withContext(Dispatchers.IO) {
            placementReviewRows(plan, diff, roots, stalePlacementReason)
        }
        rowsLoading = false
    }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.nexus_plan_review_title), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(
                    R.string.nexus_plan_coverage,
                    plan.placedCount,
                    plan.selectedCount,
                    plan.ignoredCount,
                    plan.unresolvedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            val replacedDefaults = plan.files.filter { file ->
                file.status == PlannedFileStatus.INTENTIONALLY_IGNORED &&
                    file.reason.startsWith("Replaced by selected FOMOD file")
            }
            if (replacedDefaults.isNotEmpty()) {
                Text(
                    stringResource(R.string.nexus_fomod_defaults_replaced, replacedDefaults.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                replacedDefaults.take(4).forEach { replaced ->
                    Text(
                        stringResource(
                            R.string.nexus_fomod_selected_winner,
                            replaced.targetRelativePath.orEmpty(),
                            replaced.reason.removePrefix("Replaced by selected FOMOD file "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { showWhy = !showWhy }) {
                Text(if (showWhy) stringResource(R.string.nexus_hide_placement_reason) else stringResource(R.string.nexus_why_this_placement))
            }
            if (showWhy) {
                automaticPlacement?.recommended?.evidence.orEmpty().forEach { evidence ->
                    Text("\u2022 $evidence", style = MaterialTheme.typography.bodySmall)
                }
                if (automaticPlacement?.candidates.orEmpty().size > 1) {
                    Text(stringResource(R.string.nexus_plan_ranked), style = MaterialTheme.typography.labelMedium)
                    automaticPlacement?.candidates.orEmpty().take(3).forEachIndexed { index, candidate ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(
                                    R.string.nexus_plan_score,
                                    index + 1,
                                    candidate.label,
                                    (candidate.plan.coverage * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (candidate.plan.digest != plan.digest) {
                                TextButton(onClick = { onUseCandidate(candidate) }) {
                                    Text(stringResource(R.string.nexus_use_this_placement))
                                }
                            }
                        }
                    }
                }
            }
            caseMerges.take(5).forEach { merge ->
                Text(stringResource(R.string.nexus_plan_case_merge, merge), style = MaterialTheme.typography.bodySmall)
            }
            plan.files.filter {
                it.status == PlannedFileStatus.UNSUPPORTED ||
                    it.status == PlannedFileStatus.MISSING ||
                    it.status == PlannedFileStatus.CONFLICTED
            }.take(8).forEach { file ->
                Text(
                    "${file.sourceRelativePath}: ${file.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ambiguousPaths.take(5).forEach { path ->
                Text(
                    "$path: ${stringResource(R.string.nexus_plan_ambiguous_case)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!plan.isComplete || ambiguousPaths.isNotEmpty()) {
                Text(
                    stringResource(R.string.nexus_plan_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (onResolve != null) {
                    val resolveCount = plan.unresolvedCount + ambiguousPaths.size
                    Button(onClick = onResolve, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (resolveCount > 0) {
                                stringResource(R.string.nexus_resolve_files, resolveCount)
                            } else {
                                stringResource(R.string.nexus_custom_placement)
                            },
                        )
                    }
                    if (resolveCount > 0) {
                        Text(
                            stringResource(R.string.nexus_resolve_files_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (diff?.hasChanges == true) {
                Text(
                    stringResource(R.string.nexus_reconfiguration_summary, diff.added, diff.changed, diff.moved, diff.stale),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            plan.warnings.take(5).forEach { warning ->
                Text("\u2022 $warning", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showAllFiles = true }, enabled = !rowsLoading) {
                    if (rowsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.nexus_review_all_files, rows.size.takeUnless { rowsLoading } ?: plan.files.size))
                }
                TextButton(onClick = onExport) {
                    Text(stringResource(R.string.nexus_plan_export))
                }
            }
        }
    }

    if (showAllFiles) {
        PlacementPlanFilesDialog(
            rows = rows,
            onBrowseDestination = { row ->
                browseTarget = RecipeDraft(targetRoot = row.targetRoot, targetRelativePath = row.targetRelativePath)
            },
            onDismiss = { showAllFiles = false },
        )
    }

    browseTarget?.let { target ->
        ContainerDestinationPickerDialog(
            roots = roots,
            currentDraft = target,
            plan = plan,
            ownershipManifests = ownershipManifests,
            selectedInstallId = selectedInstallId,
            installNamesById = installNamesById,
            readOnly = true,
            onSelect = {},
            onDismiss = { browseTarget = null },
        )
    }
}

@Composable
private fun PlacementPlanFilesDialog(
    rows: List<PlacementReviewRow>,
    onBrowseDestination: (PlacementReviewRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(rows) { mutableStateOf("") }
    var category by remember(rows) { mutableStateOf<PlacementReviewCategory?>(null) }
    val categories = remember(rows) { rows.map { it.category }.distinct() }
    val filtered = remember(rows, query, category) {
        rows.filter { (category == null || it.category == category) && it.matches(query) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).height(640.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.nexus_all_planned_files), style = MaterialTheme.typography.headlineSmall)
                    NoExtractOutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.nexus_search_planned_files)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PlacementChoiceButton(
                            text = stringResource(R.string.nexus_all_files_count, rows.size),
                            selected = category == null,
                            onClick = { category = null },
                        )
                        categories.forEach { option ->
                            PlacementChoiceButton(
                                text = "${placementReviewCategoryLabel(option)} (${rows.count { it.category == option }})",
                                selected = category == option,
                                onClick = { category = option },
                            )
                        }
                    }
                }
                HorizontalDivider()
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.nexus_no_matching_planned_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        filtered.groupBy { it.category }.forEach { (group, groupRows) ->
                            item(key = "header:${group.name}") {
                                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(
                                        "${placementReviewCategoryLabel(group)} (${groupRows.size})",
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                            itemsIndexed(
                                groupRows,
                                key = { index, row -> "${group.name}:${row.source}:${row.target}:${row.previousTarget}:$index" },
                            ) { _, row ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(row.source, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                    if (row.previousTarget.isNotBlank()) {
                                        Text(
                                            stringResource(R.string.nexus_previous_destination_value, row.previousTarget),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (row.target.isNotBlank()) {
                                        Text(
                                            stringResource(R.string.nexus_destination_value, row.target),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (row.reason.isNotBlank()) {
                                        Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (row.sizeBytes > 0L) {
                                        Text(StorageUtils.formatBinarySize(row.sizeBytes), style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (row.targetRoot.isNotBlank()) {
                                        TextButton(onClick = { onBrowseDestination(row) }) {
                                            Text(stringResource(R.string.nexus_browse_destination))
                                        }
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp)
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun placementReviewCategoryLabel(category: PlacementReviewCategory): String = when (category) {
    PlacementReviewCategory.ADDED -> stringResource(R.string.nexus_plan_group_added)
    PlacementReviewCategory.REPLACED -> stringResource(R.string.nexus_plan_group_replaced)
    PlacementReviewCategory.MOVED -> stringResource(R.string.nexus_plan_group_moved)
    PlacementReviewCategory.REMOVED -> stringResource(R.string.nexus_plan_group_removed)
    PlacementReviewCategory.IGNORED -> stringResource(R.string.nexus_plan_group_ignored)
    PlacementReviewCategory.BLOCKED -> stringResource(R.string.nexus_plan_group_blocked)
    PlacementReviewCategory.UNCHANGED -> stringResource(R.string.nexus_plan_group_unchanged)
}

@Composable
private fun PresetSelectionSection(
    presets: List<PlacementPresetOption>,
    selectedDrafts: List<RecipeDraft>,
    onSelect: (List<RecipeDraft>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nexus_game_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        presets.forEach { option ->
            val selected = option.drafts == selectedDrafts
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(option.preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        option.preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Button(onClick = { onSelect(option.drafts) }, modifier = Modifier.fillMaxWidth(), content = { content() })
            } else {
                OutlinedButton(onClick = { onSelect(option.drafts) }, modifier = Modifier.fillMaxWidth(), content = { content() })
            }
        }
    }
}
@Composable
private fun PlacementChoiceSelector(
    selected: PlacementChoice,
    hasPresets: Boolean,
    canUseLastPlacement: Boolean,
    showAdvanced: Boolean,
    onSelect: (PlacementChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nexus_placement_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp
            val allChoices = listOf(
                Triple(PlacementChoice.AUTOMATIC, stringResource(R.string.nexus_placement_automatic), true),
                Triple(PlacementChoice.PRESET, stringResource(R.string.nexus_placement_preset), hasPresets),
                Triple(PlacementChoice.LAST_USED, stringResource(R.string.nexus_placement_last_used), canUseLastPlacement),
                Triple(PlacementChoice.CUSTOM, stringResource(R.string.nexus_placement_custom), true),
            )
            val choices = if (showAdvanced || selected != PlacementChoice.AUTOMATIC) allChoices else allChoices.take(1)
            val rows = if (compact) choices.chunked(2) else listOf(choices)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rows.forEach { rowChoices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowChoices.forEach { (choice, label, enabled) ->
                            PlacementChoiceButton(
                                text = label,
                                selected = selected == choice,
                                enabled = enabled,
                                onClick = { onSelect(choice) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun PlacementChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, content = { content() })
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, content = { content() })
    }
}

@Composable
private fun PlacementSummaryCard(
    choice: PlacementChoice,
    draft: RecipeDraft,
    roots: List<ResolvedModTargetRoot>,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = when (choice) {
                    PlacementChoice.AUTOMATIC -> stringResource(R.string.nexus_automatic_placement)
                    PlacementChoice.PRESET -> stringResource(R.string.nexus_preset_placement)
                    PlacementChoice.LAST_USED -> stringResource(R.string.nexus_last_used_placement)
                    PlacementChoice.CUSTOM -> stringResource(R.string.nexus_custom_placement)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                    text = stringResource(R.string.nexus_destination_folder_value, placementSummary(draft, roots, stringResource(R.string.nexus_base_folder))),
                style = MaterialTheme.typography.bodyMedium,
            )
            val selectedSources = ModPlacementSources.decode(draft.sourceSubpath).filter { it.isNotBlank() }
            val sourceSummary = sourceSelectionSummaryText(draft.sourceSubpath)
            if (selectedSources.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.nexus_source_folder_value, sourceSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DestinationScopeText(draft, roots)
        }
    }
}

@Composable
private fun ArchivePreview(
    entries: List<ModArchiveEntry>,
    importFinished: Boolean,
    onBrowse: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nexus_files_in_mod), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (entries.isNotEmpty()) {
                        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_browse))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nexus_files_in_mod), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        OutlinedButton(onClick = onBrowse) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_browse))
                        }
                    }
                }
            }
        }
        val shown = entries.take(30)
        if (shown.isEmpty()) {
            Text(
                if (importFinished) stringResource(R.string.nexus_no_extracted_entries) else stringResource(R.string.nexus_mod_not_finished_importing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            shown.forEach { entry ->
                Text(
                    text = if (entry.directory) "${entry.path}/" else entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entries.size > shown.size) {
                Text(
                    stringResource(R.string.nexus_more_files_available_browse, entries.size - shown.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlacementDraftEditor(
    index: Int,
    draft: RecipeDraft,
    entries: List<ModArchiveEntry>,
    roots: List<ResolvedModTargetRoot>,
    plan: ModInstallPlan?,
    ownershipManifests: List<ModOwnershipManifest>,
    selectedInstallId: String,
    installNamesById: Map<String, String>,
    showAdvanced: Boolean,
    canRemove: Boolean,
    onUpdate: (RecipeDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var showSourcePicker by remember(index) { mutableStateOf(false) }
    var showDestinationPicker by remember(index) { mutableStateOf(false) }
    var showManualPaths by remember(index) { mutableStateOf(false) }
    val layout = remember(draft.sourceSubpath, draft.targetRelativePath, draft.includeSourceDirectory, entries) {
        placementLayoutModel(draft, entries)
    }
    val recommendedKeepFolder = remember(draft.sourceSubpath, draft.targetRelativePath, entries, layout.visible) {
        layout.visible &&
            AutomaticPlacementPlanner.inferIncludeSourceDirectory(
                selectedPaths = ModPlacementSources.decode(draft.sourceSubpath),
                entries = entries,
                targetRelativePath = draft.targetRelativePath,
            )
    }

    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nexus_location_number, index + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.nexus_remove_placement_row))
                    }
                }
            }

            PickerButton(
                label = stringResource(R.string.nexus_source_folder_label),
                value = sourceSelectionSummaryText(draft.sourceSubpath),
                icon = Icons.Default.FolderOpen,
                onClick = { showSourcePicker = true },
            )

            PickerButton(
                label = stringResource(R.string.nexus_destination_folder),
                value = placementSummary(draft, roots, stringResource(R.string.nexus_base_folder)),
                icon = Icons.Default.Folder,
                onClick = { showDestinationPicker = true },
            )
            DestinationScopeText(draft, roots)

            if (layout.visible) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(if (layout.editable) R.string.nexus_folder_layout else R.string.nexus_everything_folder_layout),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (layout.editable) {
                        val names = layout.selectedNames.take(2).joinToString(", ") + if (layout.selectedNames.size > 2) ", ..." else ""
                        val contentsLabel = if (layout.multipleFolders) {
                            stringResource(R.string.nexus_merge_selected_folder_contents)
                        } else {
                            stringResource(R.string.nexus_install_contents_of, names)
                        }
                        val folderLabel = if (layout.multipleFolders) {
                            stringResource(R.string.nexus_keep_selected_folder_names)
                        } else {
                            stringResource(R.string.nexus_install_folder_and_contents, names)
                        }
                        PlacementChoiceButton(
                            text = contentsLabel + if (!recommendedKeepFolder) stringResource(R.string.nexus_recommended_suffix) else "",
                            selected = !draft.includeSourceDirectory,
                            onClick = { onUpdate(draft.copy(includeSourceDirectory = false)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PlacementChoiceButton(
                            text = folderLabel + if (recommendedKeepFolder) stringResource(R.string.nexus_recommended_suffix) else "",
                            selected = draft.includeSourceDirectory,
                            onClick = { onUpdate(draft.copy(includeSourceDirectory = true)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.nexus_folder_layout_result, layout.resultExample),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (layout.duplicateFolderWarning) {
                        Text(
                            stringResource(R.string.nexus_duplicate_folder_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (showAdvanced) {
                DropdownField(
                    label = stringResource(R.string.nexus_install_method),
                    value = placementModeLabelText(draft.mode),
                    options = ModPlacementMode.entries.map { placementModeLabelText(it.name) to it.name },
                    onSelect = { onUpdate(draft.copy(mode = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(onClick = { showManualPaths = !showManualPaths }) {
                    Text(if (showManualPaths) stringResource(R.string.nexus_hide_manual_paths) else stringResource(R.string.nexus_manual_paths))
                }
            }

            if (showAdvanced && showManualPaths) {
                NoExtractOutlinedTextField(
                    value = sourceManualText(draft.sourceSubpath),
                    onValueChange = { value ->
                        val sources = value.lines().filter(String::isNotBlank)
                        onUpdate(
                            draft.copy(
                                sourceSubpath = ModPlacementSources.encode(sources),
                                includeSourceDirectory = AutomaticPlacementPlanner.inferIncludeSourceDirectory(
                                    sources,
                                    entries,
                                    draft.targetRelativePath,
                                ),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.nexus_source_folders_files_label)) },
                    placeholder = { Text(stringResource(R.string.nexus_source_paths_placeholder)) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
                NoExtractOutlinedTextField(
                    value = draft.targetRelativePath,
                    onValueChange = {
                        val normalized = if (draft.targetRoot == ModTargetRoot.CUSTOM_ABSOLUTE.name) {
                            it.trim().replace('\\', '/')
                        } else {
                            ModTargetResolver.normalizeRelativePath(it)
                        }
                        onUpdate(draft.copy(targetRelativePath = normalized))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.nexus_destination_folder)) },
                    placeholder = { Text(stringResource(R.string.nexus_destination_placeholder)) },
                    singleLine = true,
                )
            }
        }
    }

    if (showSourcePicker) {
        ArchiveBrowserDialog(
            title = stringResource(R.string.nexus_choose_from_mod),
            entries = entries,
            onSelect = null,
            selectedPaths = ModPlacementSources.decode(draft.sourceSubpath)
                .filter { it.isNotBlank() }
                .toSet(),
            onSelectMultiple = { paths ->
                onUpdate(
                    draft.copy(
                        sourceSubpath = ModPlacementSources.encode(paths),
                        includeSourceDirectory = AutomaticPlacementPlanner.inferIncludeSourceDirectory(
                            paths,
                            entries,
                            draft.targetRelativePath,
                        ),
                    ),
                )
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    if (showDestinationPicker) {
        ContainerDestinationPickerDialog(
            roots = roots,
            currentDraft = draft,
            plan = plan,
            ownershipManifests = ownershipManifests,
            selectedInstallId = selectedInstallId,
            installNamesById = installNamesById,
            onSelect = {
                onUpdate(it)
                showDestinationPicker = false
            },
            onDismiss = { showDestinationPicker = false },
        )
    }
}

@Composable
private fun PickerButton(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun placementModeLabelText(mode: String): String = when (mode) {
    ModPlacementMode.SYMLINK.name -> stringResource(R.string.nexus_mode_link_files)
    ModPlacementMode.COPY.name -> stringResource(R.string.nexus_mode_copy_files)
    ModPlacementMode.OVERWRITE_COPY.name -> stringResource(R.string.nexus_mode_overwrite_backup)
    else -> mode
}

@Composable
private fun sourceSelectionSummaryText(sourceSubpath: String): String {
    val sources = ModPlacementSources.decode(sourceSubpath).filter { it.isNotBlank() }
    return when (sources.size) {
        0 -> stringResource(R.string.nexus_everything_in_mod)
        1 -> sources.single()
        else -> stringResource(
            R.string.nexus_source_selected_summary,
            sources.size,
            sources.take(2).joinToString(", "),
            if (sources.size > 2) ", ..." else "",
        )
    }
}

@Composable
private fun DestinationScopeText(
    draft: RecipeDraft,
    roots: List<ResolvedModTargetRoot>,
) {
    val root = roots.firstOrNull { it.type.name == draft.targetRoot }
    Text(
        text = if (root != null) {
            stringResource(R.string.nexus_inside_root, root.label)
        } else {
            stringResource(R.string.nexus_destination_not_inside)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (root != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ArchiveBrowserDialog(
    title: String,
    entries: List<ModArchiveEntry>,
    onSelect: ((String) -> Unit)?,
    selectedPaths: Set<String> = emptySet(),
    onSelectMultiple: ((Set<String>) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var currentPath by remember(entries) { mutableStateOf("") }
    var selected by remember(entries, selectedPaths) {
        mutableStateOf(selectedPaths.map(ModPlacementSources::normalize).filter { it.isNotBlank() }.toSet())
    }
    val children = remember(entries, currentPath) { archiveChildren(entries, currentPath) }
    val allFilesLabel = stringResource(R.string.nexus_all_files)
    val breadcrumb = currentPath.ifBlank { allFilesLabel }.replace("/", " / ")
    val multiSelect = onSelectMultiple != null

    fun toggleSelection(path: String) {
        val normalized = ModPlacementSources.normalize(path)
        if (normalized.isBlank()) return
        selected = if (normalized in selected) selected - normalized else selected + normalized
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(540.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = breadcrumb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (currentPath.isNotBlank()) {
                    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier
                                .clickable { currentPath = parentArchivePath(currentPath) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
                            Text(parentArchivePath(currentPath).ifBlank { allFilesLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                HorizontalDivider()

                if (entries.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.nexus_no_extracted_entries), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (children.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.nexus_no_files_in_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(children, key = { it.path }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (multiSelect && !item.directory) {
                                            toggleSelection(item.path)
                                        } else if (item.directory) {
                                            currentPath = item.path
                                        } else {
                                            onSelect?.invoke(item.path)
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (multiSelect) {
                                    Checkbox(
                                        checked = item.path in selected,
                                        onCheckedChange = { toggleSelection(item.path) },
                                    )
                                }
                                Icon(
                                    if (item.directory) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (item.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!item.directory && item.sizeBytes > 0L) {
                                        Text(
                                            StorageUtils.formatBinarySize(item.sizeBytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (item.directory) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    if (onSelectMultiple != null) {
                        OutlinedButton(
                            onClick = {
                                onSelectMultiple(
                                    if (currentPath.isBlank()) emptySet() else setOf(currentPath),
                                )
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(if (currentPath.isBlank()) stringResource(R.string.nexus_use_all_files) else stringResource(R.string.nexus_use_this_folder))
                        }
                        Button(
                            onClick = { onSelectMultiple(selected) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(R.string.nexus_use_selected))
                        }
                    } else if (onSelect != null) {
                        Button(
                            onClick = { onSelect(currentPath) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(if (currentPath.isBlank()) stringResource(R.string.nexus_use_all_files) else stringResource(R.string.nexus_use_this_folder))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerDestinationPickerDialog(
    roots: List<ResolvedModTargetRoot>,
    currentDraft: RecipeDraft,
    plan: ModInstallPlan?,
    ownershipManifests: List<ModOwnershipManifest>,
    selectedInstallId: String,
    installNamesById: Map<String, String>,
    readOnly: Boolean = false,
    onSelect: (RecipeDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentRootName by remember(currentDraft.targetRoot, roots) {
        mutableStateOf(roots.firstOrNull { it.type.name == currentDraft.targetRoot }?.type?.name.orEmpty())
    }
    var selectedDestination by remember(currentDraft.targetRoot, currentDraft.targetRelativePath, roots) {
        val root = roots.firstOrNull { it.type.name == currentDraft.targetRoot }
        mutableStateOf(root?.let { targetRoot ->
            val candidate = if (currentDraft.targetRelativePath.isBlank()) {
                targetRoot.dir
            } else {
                File(targetRoot.dir, currentDraft.targetRelativePath)
            }
            runCatching { candidate.canonicalFile }
                .getOrNull()
                ?.takeIf { it.isInsideOrEqual(targetRoot.dir) }
        })
    }
    var currentDir by remember(currentDraft.targetRoot, currentDraft.targetRelativePath, roots) {
        val root = roots.firstOrNull { it.type.name == currentDraft.targetRoot }
        val desired = root?.let { targetRoot ->
            if (currentDraft.targetRelativePath.isBlank()) targetRoot.dir else File(targetRoot.dir, currentDraft.targetRelativePath)
        }
        var existing = desired
        while (existing != null && !existing.isDirectory) existing = existing.parentFile
        mutableStateOf(existing?.takeIf { root != null && it.isInsideOrEqual(root.dir) })
    }
    var browserEntries by remember { mutableStateOf<List<DestinationBrowserEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val currentRoot = roots.firstOrNull { it.type.name == currentRootName }

    LaunchedEffect(currentDir, currentRoot, plan, ownershipManifests, query, showHidden, selectedDestination) {
        val dir = currentDir
        if (dir != null && dir.isDirectory) {
            loading = true
            try {
                delay(150)
                val root = currentRoot
                browserEntries = withContext(Dispatchers.IO) {
                    if (root == null) {
                        emptyList()
                    } else {
                        val virtualName = selectedDestination
                            ?.takeIf { !it.exists() && it.parentFile?.canonicalFile == dir.canonicalFile }
                            ?.name
                        destinationBrowserEntries(
                            directory = dir,
                            root = root,
                            plan = plan,
                            ownership = ownershipManifests,
                            selectedInstallId = selectedInstallId,
                            showHidden = showHidden,
                            query = query,
                            virtualFolderName = virtualName,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                browserEntries = emptyList()
            } finally {
                loading = false
            }
        } else {
            browserEntries = emptyList()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val compactHeight = maxHeight < 600.dp
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(if (compactHeight) 0.98f else 0.94f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(if (compactHeight) 12.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(if (readOnly) R.string.nexus_destination_contents else R.string.nexus_destination_folder),
                            style = if (compactHeight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        )
                        if (currentDir == null || currentRoot == null) {
                            Text(
                                text = stringResource(R.string.nexus_choose_game_container_location),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            DestinationBreadcrumb(currentRoot, currentDir!!) { destination ->
                                currentDir = destination
                                selectedDestination = destination
                                query = ""
                            }
                        }
                        if (roots.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                roots.forEach { root ->
                                    OutlinedButton(
                                        onClick = {
                                            currentRootName = root.type.name
                                            currentDir = root.dir
                                            selectedDestination = root.dir
                                            query = ""
                                        },
                                    ) {
                                        Text(root.label, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }

                    if (currentDir != null) {
                        if (!compactHeight) {
                            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            val root = currentRoot
                                            val parent = currentDir?.parentFile
                                            currentDir = if (root != null && parent != null && parent.isInsideOrEqual(root.dir)) {
                                                parent
                                            } else {
                                                null
                                            }
                                            selectedDestination = currentDir
                                            query = ""
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.back),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(currentDir?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        HorizontalDivider()
                        DestinationBrowserTools(
                            compact = compactHeight,
                            query = query,
                            onQueryChange = { query = it },
                            showHidden = showHidden,
                            onShowHiddenChange = { showHidden = it },
                            readOnly = readOnly,
                            onNewFolder = {
                                newFolderName = ""
                                showNewFolderDialog = true
                            },
                        )
                        HorizontalDivider()
                    }

                    if (currentDir == null) {
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(roots, key = { it.type.name }) { root ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentRootName = root.type.name
                                            currentDir = root.dir
                                            selectedDestination = root.dir
                                            query = ""
                                        }
                                        .padding(horizontal = 20.dp, vertical = if (compactHeight) 8.dp else 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                shape = RoundedCornerShape(8.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            targetRootIcon(root.type),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(root.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (loading) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    } else if (browserEntries.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Default.FolderOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.nexus_destination_folder_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(browserEntries, key = { "${it.file.absolutePath}:${it.virtual}" }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = entry.directory && !entry.virtual) {
                                            currentDir = entry.file
                                            selectedDestination = entry.file
                                            query = ""
                                        }
                                        .padding(horizontal = 20.dp, vertical = if (compactHeight) 8.dp else 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        if (entry.directory) Icons.Default.Folder else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = if (entry.directory) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(entry.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        DestinationEntryMetadata(entry, installNamesById, selectedInstallId)
                                    }
                                    if (entry.directory && !entry.virtual) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = if (compactHeight) 6.dp else 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!readOnly) {
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                        val selectedRoot = currentRoot
                        val selectedDir = selectedDestination ?: currentDir
                        if (readOnly) {
                            Button(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                                Text(stringResource(R.string.close))
                            }
                        } else if (selectedRoot != null && selectedDir != null) {
                            Button(
                                onClick = {
                                    val relative = runCatching {
                                        selectedDir.canonicalFile.relativeToOrNull(selectedRoot.dir.canonicalFile)
                                            ?.path
                                            ?.replace(File.separatorChar, '/')
                                            .orEmpty()
                                    }.getOrDefault("")
                                    onSelect(
                                        currentDraft.copy(
                                            targetRoot = selectedRoot.type.name,
                                            targetRelativePath = relative,
                                        ),
                                    )
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            ) {
                                Text(stringResource(R.string.nexus_select))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog && !readOnly) {
        val validDestination = validVirtualDestinationFolder(currentDir, newFolderName)
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.nexus_new_destination_folder)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nexus_new_destination_folder_description))
                    NoExtractOutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.nexus_folder_name)) },
                        isError = newFolderName.isNotBlank() && !validDestination,
                        singleLine = true,
                    )
                    Text(
                        stringResource(R.string.nexus_folder_created_when_applied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        currentDir?.let { parent ->
                            File(parent, newFolderName.trim()).takeIf { !it.exists() || it.isDirectory }?.let {
                                selectedDestination = it
                                showNewFolderDialog = false
                            }
                        }
                    },
                    enabled = validDestination,
                ) { Text(stringResource(R.string.nexus_use_folder)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DestinationBrowserTools(
    compact: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    showHidden: Boolean,
    onShowHiddenChange: (Boolean) -> Unit,
    readOnly: Boolean,
    onNewFolder: () -> Unit,
) {
    if (compact) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoExtractOutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.nexus_search_destination)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            IconButton(onClick = { onShowHiddenChange(!showHidden) }) {
                Icon(
                    if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = stringResource(R.string.nexus_show_hidden_files),
                    tint = if (showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!readOnly) {
                IconButton(onClick = onNewFolder) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = stringResource(R.string.nexus_new_destination_folder),
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NoExtractOutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.nexus_search_destination)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showHidden, onCheckedChange = onShowHiddenChange)
                Text(stringResource(R.string.nexus_show_hidden_files), modifier = Modifier.weight(1f))
                if (!readOnly) {
                    TextButton(onClick = onNewFolder) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.nexus_new_destination_folder))
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationBreadcrumb(
    root: ResolvedModTargetRoot,
    directory: File,
    onNavigate: (File) -> Unit,
) {
    val relativeSegments = remember(root, directory) {
        runCatching {
            directory.canonicalFile.relativeTo(root.dir.canonicalFile).path
                .replace(File.separatorChar, '/')
                .split('/')
                .filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onNavigate(root.dir) }) { Text(root.label, maxLines = 1) }
        var destination = root.dir
        relativeSegments.forEach { segment ->
            destination = File(destination, segment)
            val segmentDestination = destination
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(segmentDestination) }) { Text(segment, maxLines = 1) }
        }
    }
}

@Composable
private fun DestinationEntryMetadata(
    entry: DestinationBrowserEntry,
    installNamesById: Map<String, String>,
    selectedInstallId: String,
) {
    val details = listOfNotNull(
        StorageUtils.formatBinarySize(entry.sizeBytes).takeIf { !entry.directory && entry.sizeBytes > 0L },
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.modifiedAt)).takeIf { entry.modifiedAt > 0L },
        stringResource(R.string.nexus_will_be_created).takeIf { entry.virtual },
    )
    if (details.isNotEmpty()) {
        Text(
            details.joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    entry.impacts.forEach { impact ->
        val label = when (impact) {
            DestinationEntryImpact.WILL_ADD -> stringResource(R.string.nexus_impact_will_add)
            DestinationEntryImpact.WILL_REPLACE -> stringResource(R.string.nexus_impact_will_replace)
            DestinationEntryImpact.WILL_BACK_UP -> stringResource(R.string.nexus_impact_will_backup)
            DestinationEntryImpact.PLAN_CONFLICT -> stringResource(R.string.nexus_impact_plan_conflict)
            DestinationEntryImpact.MANAGED_BY_THIS_MOD -> stringResource(R.string.nexus_impact_managed_by_this_mod)
            DestinationEntryImpact.MANAGED_BY_ANOTHER_MOD -> {
                val names = entry.ownerInstallIds.filterNot { it == selectedInstallId }
                    .map { installNamesById[it] ?: it }
                    .take(2)
                    .joinToString(", ")
                stringResource(R.string.nexus_impact_managed_by_mod, names)
            }
            DestinationEntryImpact.MODIFIED -> stringResource(R.string.nexus_impact_modified)
            DestinationEntryImpact.GAME_OR_UNMANAGED -> stringResource(R.string.nexus_impact_game_or_unmanaged)
            DestinationEntryImpact.WILL_RECEIVE_FILES -> stringResource(R.string.nexus_impact_will_receive_files)
        }
        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, storedValue) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(storedValue)
                    },
                )
            }
        }
    }
}
