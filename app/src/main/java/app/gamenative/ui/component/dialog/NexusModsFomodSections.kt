package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.ModPlacementMode
import app.gamenative.mods.FomodGroupType
import app.gamenative.mods.FomodEnvironmentSnapshot
import app.gamenative.mods.FomodInstaller
import app.gamenative.mods.ModInstallPlan
import app.gamenative.mods.PlannedFileStatus
import app.gamenative.mods.FomodPluginType
import app.gamenative.mods.FomodRecipeGenerator
import app.gamenative.mods.effectiveType
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@Composable
internal fun FomodSummarySection(
    installer: FomodInstaller,
    onConfigure: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nexus_fomod_installer), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = onConfigure,
                    enabled = (installer.steps.isNotEmpty() || installer.requiredFiles.isNotEmpty()) &&
                        installer.unsupportedWarnings.isEmpty(),
                ) {
                    Text(stringResource(R.string.nexus_configure))
                }
            }
            Text(
                text = installer.moduleName.ifBlank { "ModuleConfig.xml" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.nexus_fomod_step_option_count, installer.steps.size, installer.steps.sumOf { it.groups.sumOf { group -> group.plugins.size } }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            installer.unsupportedWarnings.firstOrNull()?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun FomodWizardDialog(
    installId: String,
    installer: FomodInstaller,
    environment: FomodEnvironmentSnapshot,
    extractedRoot: File,
    baseDraft: RecipeDraft,
    initialSelections: Map<String, Set<String>> = emptyMap(),
    onSelectionsChanged: (Map<String, Set<String>>) -> Unit = {},
    onApply: (List<RecipeDraft>, ModInstallPlan?, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var previewImage by remember { mutableStateOf<File?>(null) }
    var pendingResult by remember { mutableStateOf<PendingFomodResult?>(null) }
    var generatingPlan by remember(installId, installer) { mutableStateOf(false) }
    var generationError by remember(installId, installer) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selectedByGroup = remember(installer) {
        mutableStateMapOf<String, Set<String>>().apply {
            installer.steps.forEachIndexed { stepIndex, step ->
                step.groups.forEachIndexed { groupIndex, group ->
                    val defaults = when (group.type) {
                        FomodGroupType.SELECT_EXACTLY_ONE ->
                            listOfNotNull(
                                group.plugins.indexOfFirst { it.type == FomodPluginType.REQUIRED }.takeIf { it >= 0 },
                                group.plugins.indexOfFirst { it.type == FomodPluginType.RECOMMENDED }.takeIf { it >= 0 },
                                group.plugins.indexOfFirst { it.type != FomodPluginType.NOT_USABLE }.takeIf { it >= 0 },
                            ).firstOrNull()?.let { setOf(FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, it)) } ?: emptySet()
                        FomodGroupType.SELECT_AT_MOST_ONE ->
                            group.plugins.indexOfFirst { it.type == FomodPluginType.RECOMMENDED }
                                .takeIf { it >= 0 }
                                ?.let { setOf(FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, it)) }
                                ?: emptySet()
                        FomodGroupType.SELECT_AT_LEAST_ONE ->
                            group.plugins.mapIndexedNotNull { pluginIndex, plugin ->
                                if (plugin.type == FomodPluginType.REQUIRED || plugin.type == FomodPluginType.RECOMMENDED) {
                                    FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex)
                                } else {
                                    null
                                }
                            }
                                .toSet()
                                .ifEmpty {
                                    group.plugins.indexOfFirst { it.type != FomodPluginType.NOT_USABLE }
                                        .takeIf { it >= 0 }
                                        ?.let { setOf(FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, it)) }
                                        ?: emptySet()
                                }
                        FomodGroupType.SELECT_ANY ->
                            group.plugins.mapIndexedNotNull { pluginIndex, plugin ->
                                if (plugin.type == FomodPluginType.REQUIRED || plugin.type == FomodPluginType.RECOMMENDED) {
                                    FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex)
                                } else {
                                    null
                                }
                            }
                                .toSet()
                    }
                    put("$stepIndex:$groupIndex", defaults)
                }
            }
            initialSelections.forEach { (groupKey, selected) ->
                val parts = groupKey.split(':').mapNotNull(String::toIntOrNull)
                val group = parts.takeIf { it.size == 2 }
                    ?.let { installer.steps.getOrNull(it[0])?.groups?.getOrNull(it[1]) }
                    ?: return@forEach
                val allowed = group.plugins.indices.mapTo(mutableSetOf()) { pluginIndex ->
                    FomodRecipeGenerator.pluginKey(parts[0], parts[1], pluginIndex)
                }
                val restored = selected.intersect(allowed)
                if (selected.isEmpty() || restored.isNotEmpty()) {
                    put(groupKey, restored)
                }
            }
        }
    }

    LaunchedEffect(selectedByGroup) {
        snapshotFlow { selectedByGroup.toMap() }
            .distinctUntilChanged()
            .collect(onSelectionsChanged)
    }

    val selectedFlags = fomodSelectedFlags(installer, selectedByGroup)
    val fallbackStepNames = installer.steps.indices.map { index ->
        stringResource(R.string.nexus_fomod_step, index + 1)
    }
    val invalidGroups = fomodInvalidGroups(installer, selectedByGroup, selectedFlags, fallbackStepNames, environment)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.nexus_fomod_installer), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        installer.moduleName.ifBlank { "ModuleConfig.xml" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                generationError?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (installer.requiredFiles.isNotEmpty()) {
                        Text(
                            stringResource(R.string.nexus_fomod_required_mappings, installer.requiredFiles.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    installer.unsupportedWarnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (invalidGroups.isNotEmpty()) {
                        Text(
                            stringResource(R.string.nexus_fomod_complete_required_groups, invalidGroups.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    installer.steps.forEachIndexed { stepIndex, step ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(step.name.ifBlank { stringResource(R.string.nexus_fomod_step, stepIndex + 1) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            step.groups.forEachIndexed { groupIndex, group ->
                                val groupKey = "$stepIndex:$groupIndex"
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(group.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        group.plugins.forEachIndexed { pluginIndex, plugin ->
                                            val pluginKey = FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex)
                                            val selected = selectedByGroup[groupKey].orEmpty()
                                            val effectiveType = plugin.effectiveType(selectedFlags, environment)
                                            val checked = effectiveType == FomodPluginType.REQUIRED || (effectiveType != FomodPluginType.NOT_USABLE && pluginKey in selected)
                                            val enabled = effectiveType != FomodPluginType.REQUIRED && effectiveType != FomodPluginType.NOT_USABLE
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = enabled) {
                                                        updateFomodSelection(selectedByGroup, groupKey, group.type, group.plugins.indices.map { FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, it) }.toSet(), pluginKey, !checked)
                                                    }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    enabled = enabled,
                                                    onCheckedChange = {
                                                        updateFomodSelection(selectedByGroup, groupKey, group.type, group.plugins.indices.map { index -> FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, index) }.toSet(), pluginKey, it)
                                                    },
                                                )
                                                FomodPluginImage(
                                                    extractedRoot = extractedRoot,
                                                    imagePath = plugin.imagePath,
                                                    onPreview = { previewImage = it },
                                                )
                                                Column(Modifier.weight(1f)) {
                                                    Text(plugin.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    if (plugin.description.isNotBlank()) {
                                                        Text(
                                                            plugin.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                    Text(
                                                    fomodPluginTypeLabel(effectiveType),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (effectiveType == FomodPluginType.NOT_USABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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
                    Button(
                        onClick = {
                            val selectedKeys = selectedByGroup.values.flatten().toSet()
                            val selectedOptions = fomodSelectedOptionLabels(
                                installer,
                                selectedKeys,
                                fallbackStepNames,
                                environment,
                            )
                            generationError = null
                            generatingPlan = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        FomodRecipeGenerator.generateForPluginKeys(
                                            installId = installId,
                                            installer = installer,
                                            selectedPluginKeys = selectedKeys,
                                            targetRoot = baseDraft.targetRoot,
                                            targetRelativePath = baseDraft.targetRelativePath.ifBlank { "Data" },
                                            mode = ModPlacementMode.OVERWRITE_COPY.name,
                                            extractedRoot = extractedRoot,
                                            environment = environment,
                                        )
                                    }
                                    if (selectedByGroup.values.flatten().toSet() == selectedKeys) {
                                        pendingResult = PendingFomodResult(
                                            drafts = result.recipes.map { it.toDraft() },
                                            plan = result.plan,
                                            unsupportedCount = result.plan?.unresolvedCount ?: 0,
                                            unresolvedDetails = result.plan?.files.orEmpty()
                                                .filter { file ->
                                                    file.status == PlannedFileStatus.UNSUPPORTED ||
                                                        file.status == PlannedFileStatus.MISSING ||
                                                        file.status == PlannedFileStatus.CONFLICTED
                                                }
                                                .map { file -> "${file.sourceRelativePath}: ${file.reason}" },
                                            blockingIssues = result.plan?.blockingIssues
                                                ?: result.blockingIssues,
                                            selectedOptions = selectedOptions,
                                            conditionalRuleCount = installer.conditionalFileInstalls.size,
                                        )
                                    }
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    generationError = error.message ?: error.javaClass.simpleName
                                } finally {
                                    generatingPlan = false
                                }
                            }
                        },
                        enabled = invalidGroups.isEmpty() && !generatingPlan,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        if (generatingPlan) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_building_install_plan))
                        } else {
                            Text(stringResource(R.string.nexus_fomod_use_choices))
                        }
                    }
                }
            }
        }
    }

    previewImage?.let { image ->
        AlertDialog(
            onDismissRequest = { previewImage = null },
            title = {
                Text(image.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                CoilImage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    imageModel = { image },
                    imageOptions = ImageOptions(contentScale = ContentScale.Fit),
                )
            },
            confirmButton = {
                TextButton(onClick = { previewImage = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    pendingResult?.let { result ->
        AlertDialog(
            onDismissRequest = { pendingResult = null },
            title = { Text(stringResource(R.string.nexus_fomod_apply_choices_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nexus_fomod_choice_summary, result.selectedOptions.size, result.drafts.size))
                    if (result.conditionalRuleCount > 0) {
                        Text(
                            stringResource(R.string.nexus_fomod_conditional_rules, result.conditionalRuleCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (result.unsupportedCount > 0) {
                        Text(
                            stringResource(R.string.nexus_fomod_unsupported_mapping_count, result.unsupportedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        result.unresolvedDetails.take(4).forEach { detail ->
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (result.blockingIssues.isNotEmpty()) {
                        result.blockingIssues.take(4).forEach { issue ->
                            Text(
                                issue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    result.selectedOptions.take(12).forEach { option ->
                        Text(option, style = MaterialTheme.typography.bodySmall)
                    }
                    if (result.selectedOptions.size > 12) {
                        Text(
                            stringResource(R.string.nexus_fomod_more_options, result.selectedOptions.size - 12),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingResult = null
                        onApply(result.drafts, result.plan, result.unsupportedCount)
                    },
                    enabled = result.blockingIssues.isEmpty(),
                ) {
                    Text(
                        stringResource(
                            if (result.unsupportedCount > 0) {
                                R.string.nexus_review_placement
                            } else {
                                R.string.nexus_fomod_apply_choices
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingResult = null }) {
                    Text(stringResource(R.string.back))
                }
            },
        )
    }
}

@Composable
private fun FomodPluginImage(
    extractedRoot: File,
    imagePath: String,
    onPreview: (File) -> Unit,
) {
    val imageFile = remember(extractedRoot, imagePath) { fomodImageFile(extractedRoot, imagePath) }
    if (imageFile != null) {
        CoilImage(
            modifier = Modifier
                .size(width = 168.dp, height = 108.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { onPreview(imageFile) },
            imageModel = { imageFile },
            imageOptions = ImageOptions(contentScale = ContentScale.Fit),
        )
    }
}

private fun updateFomodSelection(
    selectedByGroup: MutableMap<String, Set<String>>,
    groupKey: String,
    groupType: FomodGroupType,
    groupPluginNames: Set<String>,
    pluginName: String,
    checked: Boolean,
) {
    val current = selectedByGroup[groupKey].orEmpty()
    val next = when (groupType) {
        FomodGroupType.SELECT_EXACTLY_ONE -> if (checked) setOf(pluginName) else current
        FomodGroupType.SELECT_AT_MOST_ONE -> if (checked) setOf(pluginName) else emptySet()
        FomodGroupType.SELECT_AT_LEAST_ONE -> {
            val changed = if (checked) current + pluginName else current - pluginName
            if (changed.isEmpty()) current else changed
        }
        FomodGroupType.SELECT_ANY -> if (checked) current + pluginName else current - pluginName
    }
    selectedByGroup[groupKey] = next.intersect(groupPluginNames)
}

private fun fomodInvalidGroups(
    installer: FomodInstaller,
    selectedByGroup: Map<String, Set<String>>,
    flags: Map<String, String>,
    fallbackStepNames: List<String>,
    environment: FomodEnvironmentSnapshot,
): List<String> =
    buildList {
        installer.steps.forEachIndexed { stepIndex, step ->
            step.groups.forEachIndexed { groupIndex, group ->
                val selected = selectedByGroup["$stepIndex:$groupIndex"].orEmpty()
                val selectable = group.plugins.mapIndexedNotNull { pluginIndex, plugin ->
                    if (plugin.effectiveType(flags, environment) == FomodPluginType.NOT_USABLE) {
                        null
                    } else {
                        FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex)
                    }
                }.toSet()
                val selectedUsable = selected.intersect(selectable)
                val invalid = when (group.type) {
                    FomodGroupType.SELECT_EXACTLY_ONE -> selectedUsable.size != 1
                    FomodGroupType.SELECT_AT_LEAST_ONE -> selectedUsable.isEmpty()
                    FomodGroupType.SELECT_AT_MOST_ONE,
                    FomodGroupType.SELECT_ANY,
                    -> false
                }
                if (invalid) {
                    add("${step.name.ifBlank { fallbackStepNames.getOrElse(stepIndex) { "" } }} / ${group.name}")
                }
            }
        }
    }

private fun fomodSelectedFlags(
    installer: FomodInstaller,
    selectedByGroup: Map<String, Set<String>>,
): Map<String, String> =
    buildMap {
        installer.steps.forEachIndexed { stepIndex, step ->
            step.groups.forEachIndexed { groupIndex, group ->
                val selected = selectedByGroup["$stepIndex:$groupIndex"].orEmpty()
                group.plugins.forEachIndexed { pluginIndex, plugin ->
                    val key = FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex)
                    if (plugin.type == FomodPluginType.REQUIRED || key in selected) {
                        plugin.conditionFlags.forEach { (name, value) -> put(name, value) }
                    }
                }
            }
        }
    }

private fun fomodSelectedOptionLabels(
    installer: FomodInstaller,
    selectedKeys: Set<String>,
    fallbackStepNames: List<String>,
    environment: FomodEnvironmentSnapshot,
): List<String> =
    buildList {
        val selectedPlugins = FomodRecipeGenerator.selectedPluginsForKeys(installer, selectedKeys, environment).toSet()
        installer.steps.forEachIndexed { stepIndex, step ->
            step.groups.forEachIndexed { groupIndex, group ->
                group.plugins.forEachIndexed { pluginIndex, plugin ->
                    val key = FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex)
                    if (plugin in selectedPlugins) {
                        add("${step.name.ifBlank { fallbackStepNames.getOrElse(stepIndex) { "" } }} / ${group.name}: ${plugin.name}")
                    }
                }
            }
        }
    }

@Composable
private fun fomodPluginTypeLabel(type: FomodPluginType): String = when (type) {
    FomodPluginType.REQUIRED -> stringResource(R.string.nexus_fomod_type_required)
    FomodPluginType.RECOMMENDED -> stringResource(R.string.nexus_fomod_type_recommended)
    FomodPluginType.OPTIONAL -> stringResource(R.string.nexus_fomod_type_optional)
    FomodPluginType.NOT_USABLE -> stringResource(R.string.nexus_fomod_type_not_usable)
    FomodPluginType.COULD_BE_USABLE -> stringResource(R.string.nexus_fomod_type_maybe)
}
