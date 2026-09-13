package app.gamenative.ui.screen.downloads

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.data.DownloadItemState
import app.gamenative.ui.data.DownloadItemStatus
import app.gamenative.ui.data.DownloadsState
import app.gamenative.ui.model.DownloadsViewModel
import app.gamenative.ui.screen.library.components.LibraryDetailPane
import app.gamenative.ui.screen.settings.ContainerStorageManagerContent
import app.gamenative.ui.screen.settings.ContainerStorageManagerTransientUi
import app.gamenative.ui.screen.settings.rememberContainerStorageManagerUiState
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DownloadsSection(
    val titleResId: Int,
    val icon: ImageVector,
) {
    Storage(
        titleResId = R.string.settings_storage_manage_title,
        icon = Icons.Default.Storage,
    ),
    Downloads(
        titleResId = R.string.downloads_section_title,
        icon = Icons.Default.Download,
    ),
}

@Composable
fun HomeDownloadsScreen(
    onBack: () -> Unit = {},
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onAiDebugRun: (String) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val storageManagerState = rememberContainerStorageManagerUiState()
    val scope = rememberCoroutineScope()
    var selectedSectionIndex by rememberSaveable { mutableIntStateOf(DownloadsSection.Storage.ordinal) }
    val sections = remember { DownloadsSection.values().toList() }
    val selectedSection = sections.getOrElse(selectedSectionIndex) { DownloadsSection.Downloads }
    var selectedLibraryItem by remember { mutableStateOf<LibraryItem?>(null) }
    var openGameRequestId by rememberSaveable { mutableIntStateOf(0) }

    fun fallbackLibraryItem(gameSource: GameSource, appId: String, name: String, iconUrl: String): LibraryItem {
        return LibraryItem(
            appId = "${gameSource.name}_$appId",
            name = name,
            iconHash = if (gameSource == GameSource.STEAM) "" else iconUrl,
            capsuleImageUrl = iconUrl,
            headerImageUrl = iconUrl,
            heroImageUrl = iconUrl,
            gameSource = gameSource,
        )
    }

    fun clearSelectedLibraryItem() {
        openGameRequestId += 1
        selectedLibraryItem = null
    }

    fun openGame(gameSource: GameSource, appId: String, name: String, iconUrl: String) {
        val requestId = openGameRequestId + 1
        openGameRequestId = requestId
        scope.launch {
            val resolvedItem = viewModel.resolveLibraryItem(gameSource, appId)
                ?: fallbackLibraryItem(gameSource, appId, name, iconUrl)
            if (requestId == openGameRequestId) {
                selectedLibraryItem = resolvedItem
            }
        }
    }

    BackHandler(enabled = selectedLibraryItem != null) {
        clearSelectedLibraryItem()
    }

    if (selectedLibraryItem != null) {
        LibraryDetailPane(
            libraryItem = selectedLibraryItem,
            onBack = ::clearSelectedLibraryItem,
            onClickPlay = { useBoxArt ->
                selectedLibraryItem?.let { libraryItem ->
                    onClickPlay(libraryItem.appId, useBoxArt)
                }
            },
            onTestGraphics = {
                selectedLibraryItem?.let { libraryItem ->
                    onTestGraphics(libraryItem.appId)
                }
            },
            onPlayWithDiagnostics = {
                selectedLibraryItem?.let { libraryItem ->
                    onPlayWithDiagnostics(libraryItem.appId)
                }
            },
            onAiDebugRun = {
                selectedLibraryItem?.let { libraryItem ->
                    onAiDebugRun(libraryItem.appId)
                }
            },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .displayCutoutPadding(),
        ) {
            DownloadsHeader(
                title = stringResource(selectedSection.titleResId),
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

            if (isPortrait) {
                // Portrait: horizontal tab row above the content
                DownloadsTabRow(
                    sections = sections,
                    selectedSection = selectedSection,
                    onSectionSelected = { selectedSectionIndex = it.ordinal },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = if (isPortrait) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!isPortrait) {
                    DownloadsSidebar(
                        sections = sections,
                        selectedSection = selectedSection,
                        onSectionSelected = { selectedSectionIndex = it.ordinal },
                        modifier = Modifier
                            .width(96.dp)
                            .fillMaxHeight(),
                    )
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    color = PluviaTheme.colors.surfacePanel.copy(alpha = 0.94f),
                    tonalElevation = 2.dp,
                    shadowElevation = 12.dp,
                ) {
                    when (selectedSection) {
                        DownloadsSection.Downloads -> DownloadsContent(
                            state = state,
                            onResumeDownload = viewModel::onResumeDownload,
                            onPauseDownload = viewModel::onPauseDownload,
                            onCancelDownload = viewModel::onCancelDownload,
                            onPauseAll = viewModel::onPauseAll,
                            onResumeAll = viewModel::onResumeAll,
                            onCancelAll = viewModel::onCancelAll,
                            onClearFinished = viewModel::onClearFinished,
                            onOpenGame = { item ->
                                openGame(item.gameSource, item.appId, item.gameName, item.iconUrl)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                        )

                        DownloadsSection.Storage -> ContainerStorageManagerContent(
                            state = storageManagerState,
                            onOpenGame = { gameSource, appId, name, iconUrl ->
                                openGame(gameSource, appId.removePrefix("${gameSource.name}_"), name, iconUrl)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                        )
                    }
                }
            }
        }
    }

    val confirmation = state.cancelConfirmation
    MessageDialog(
        visible = confirmation != null,
        title = stringResource(R.string.cancel_download_prompt_title),
        message = confirmation?.gameName?.let {
            stringResource(R.string.downloads_cancel_confirm, it)
        },
        confirmBtnText = stringResource(R.string.yes),
        dismissBtnText = stringResource(R.string.no),
        onConfirmClick = { viewModel.onConfirmCancel() },
        onDismissClick = { viewModel.onDismissCancel() },
        onDismissRequest = { viewModel.onDismissCancel() },
    )

    ContainerStorageManagerTransientUi(storageManagerState)
}

@Composable
private fun DownloadsHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackButton(onClick = onBack)

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DownloadsSidebar(
    sections: List<DownloadsSection>,
    selectedSection: DownloadsSection,
    onSectionSelected: (DownloadsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember {
        sections.associateWith { FocusRequester() }
    }
    var requestedInitialFocus by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = PluviaTheme.colors.surfacePanel.copy(alpha = 0.88f),
        tonalElevation = 1.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { section ->
                DownloadsSidebarItem(
                    section = section,
                    selected = selectedSection == section,
                    onClick = { onSectionSelected(section) },
                    focusRequester = focusRequesters.getValue(section),
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    LaunchedEffect(selectedSection, requestedInitialFocus) {
        if (requestedInitialFocus) return@LaunchedEffect
        val focusRequester = focusRequesters.getValue(selectedSection)
        repeat(3) {
            try {
                focusRequester.requestFocus()
                requestedInitialFocus = true
                return@LaunchedEffect
            } catch (_: Exception) {
                delay(80)
            }
        }
    }
}

@Composable
private fun DownloadsTabRow(
    sections: List<DownloadsSection>,
    selectedSection: DownloadsSection,
    onSectionSelected: (DownloadsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sections.forEach { section ->
            val isSelected = selectedSection == section
            val accentColor = PluviaTheme.colors.accentPurple

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(
                        selected = isSelected,
                        onClick = { onSectionSelected(section) },
                    ),
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) {
                    accentColor.copy(alpha = 0.16f)
                } else {
                    PluviaTheme.colors.surfacePanel.copy(alpha = 0.88f)
                },
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) {
                        accentColor.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = stringResource(section.titleResId),
                        tint = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(section.titleResId),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsSidebarItem(
    section: DownloadsSection,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple
    val isHighlighted = selected || isFocused

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (isHighlighted) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = if (isFocused) 0.18f else 0.12f),
                                accentColor.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                } else {
                    Modifier.background(Color.Transparent)
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> accentColor.copy(alpha = 0.65f)
                    selected -> accentColor.copy(alpha = 0.32f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                },
                shape = RoundedCornerShape(18.dp),
            )
            .focusRequester(focusRequester)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isFocused -> accentColor.copy(alpha = 0.22f)
                        selected -> accentColor.copy(alpha = 0.14f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = stringResource(section.titleResId),
                tint = when {
                    isHighlighted -> accentColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadsContent(
    state: DownloadsState,
    onResumeDownload: (DownloadItemState) -> Unit,
    onPauseDownload: (DownloadItemState) -> Unit,
    onCancelDownload: (DownloadItemState) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
    onOpenGame: (DownloadItemState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(state.downloads) { state.downloads.values.toList() }
    val activeCount = remember(items) { items.count { it.canPause } }
    val resumableCount = remember(items) { items.count { it.canResume } }
    val finishedCount = remember(items) { items.count { it.isFinished } }
    val hasCancelableItems = remember(items) { items.any { it.canCancel } }
    val primaryActionLabel = if (activeCount > 0) {
        stringResource(R.string.downloads_pause_all)
    } else {
        stringResource(R.string.downloads_resume_all)
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = stringResource(
                R.string.downloads_summary,
                activeCount,
                resumableCount,
                finishedCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))

        if (items.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DownloadsToolbarButton(
                    text = primaryActionLabel,
                    icon = if (activeCount > 0) Icons.Default.Pause else Icons.Default.PlayArrow,
                    onClick = if (activeCount > 0) onPauseAll else onResumeAll,
                    enabled = activeCount > 0 || resumableCount > 0,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                DownloadsToolbarButton(
                    text = stringResource(R.string.downloads_cancel_all),
                    icon = Icons.Default.Delete,
                    onClick = onCancelAll,
                    enabled = hasCancelableItems,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                DownloadsToolbarButton(
                    text = stringResource(R.string.downloads_clear_finished),
                    icon = null,
                    onClick = onClearFinished,
                    enabled = finishedCount > 0,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (items.isEmpty()) {
            EmptyDownloadsContent(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(
                    items = items,
                    key = { it.uniqueId },
                ) { item ->
                    DownloadItemCard(
                        item = item,
                        onOpenGame = { onOpenGame(item) },
                        onResume = { onResumeDownload(item) },
                        onPause = { onPauseDownload(item) },
                        onCancel = { onCancelDownload(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsToolbarButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple

    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        interactionSource = interactionSource,
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) {
                accentColor.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            },
        ),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isFocused) accentColor.copy(alpha = 0.18f) else containerColor,
            contentColor = if (isFocused) accentColor else contentColor,
        ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text)
    }
}

@Composable
private fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "backButtonScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    PluviaTheme.colors.accentPurple.copy(alpha = 0.2f)
                } else {
                    PluviaTheme.colors.surfaceElevated
                }
            )
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, PluviaTheme.colors.accentPurple.copy(alpha = 0.6f), CircleShape)
                } else {
                    Modifier.border(1.dp, PluviaTheme.colors.borderDefault.copy(alpha = 0.3f), CircleShape)
                }
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.back),
            tint = if (isFocused) PluviaTheme.colors.accentPurple else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EmptyDownloadsContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                text = stringResource(R.string.downloads_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadItemCard(
    item: DownloadItemState,
    onOpenGame: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val statusText = statusLabel(item.status)
    val detailText = item.statusMessage?.takeIf { !it.equals(statusText, ignoreCase = true) }
    val etaText = item.etaMs?.let(::formatEta)
    val progressColor = when (item.status) {
        DownloadItemStatus.COMPLETED -> PluviaTheme.colors.accentSuccess
        DownloadItemStatus.CANCELLED,
        DownloadItemStatus.FAILED,
        -> PluviaTheme.colors.accentDanger
        DownloadItemStatus.PAUSED,
        DownloadItemStatus.QUEUED,
        DownloadItemStatus.RESUMABLE,
        -> PluviaTheme.colors.accentWarning
        DownloadItemStatus.DOWNLOADING -> PluviaTheme.colors.statusDownloading
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

        val actionButtons: @Composable (() -> Unit) = {
            if (item.canPause || item.canResume || item.canCancel) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (item.canResume) {
                        DownloadActionButton(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.resume_download),
                            onClick = onResume,
                        )
                    } else if (item.canPause) {
                        DownloadActionButton(
                            imageVector = Icons.Default.Pause,
                            contentDescription = stringResource(R.string.pause_download),
                            onClick = onPause,
                        )
                    }

                    if (item.canCancel) {
                        DownloadActionButton(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            onClick = onCancel,
                        )
                    }
                }
            }
        }

        val infoContent: @Composable (Modifier) -> Unit = { infoModifier ->
            Column(modifier = infoModifier) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.gameName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    item.progress?.let { progress ->
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = progressColor,
                        )
                    }
                }

                item.progress?.let { progress ->
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = progressColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetadataChip(
                        text = sourceLabel(item.gameSource),
                        containerColor = sourceContainerColor(item.gameSource),
                        contentColor = sourceContentColor(item.gameSource),
                    )
                    MetadataChip(
                        text = statusText,
                        containerColor = statusContainerColor(item.status),
                        contentColor = statusContentColor(item.status),
                    )
                }

                if (item.bytesDownloaded != null || etaText != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bytesText = when {
                            item.bytesDownloaded != null && item.bytesTotal != null && item.bytesTotal > 0L -> {
                                "${Formatter.formatFileSize(context, item.bytesDownloaded)} / ${Formatter.formatFileSize(context, item.bytesTotal)}"
                            }
                            item.bytesDownloaded != null -> Formatter.formatFileSize(context, item.bytesDownloaded)
                            else -> null
                        }
                        if (bytesText != null) {
                            Text(
                                text = bytesText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (!etaText.isNullOrBlank()) {
                            Text(
                                text = etaText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (!detailText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (isPortrait) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GameArtworkButton(
                        imageUrl = item.iconUrl,
                        contentDescription = item.gameName,
                        placeholderIcon = Icons.Default.Download,
                        onClick = onOpenGame,
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    infoContent(Modifier.weight(1f))
                }

                if (item.canPause || item.canResume || item.canCancel) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        actionButtons()
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameArtworkButton(
                    imageUrl = item.iconUrl,
                    contentDescription = item.gameName,
                    placeholderIcon = Icons.Default.Download,
                    onClick = onOpenGame,
                )

                Spacer(modifier = Modifier.width(12.dp))

                infoContent(Modifier.weight(1f))

                if (item.canPause || item.canResume || item.canCancel) {
                    Spacer(modifier = Modifier.width(12.dp))
                    actionButtons()
                }
            }
        }
    }
}

@Composable
private fun GameArtworkButton(
    imageUrl: String,
    contentDescription: String,
    placeholderIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = PluviaTheme.colors.accentPurple

    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) {
                    accentColor.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNotBlank()) {
            CoilImage(
                imageModel = { imageUrl },
                imageOptions = ImageOptions(
                    contentScale = ContentScale.Crop,
                    contentDescription = contentDescription,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Icon(
                imageVector = placeholderIcon,
                contentDescription = contentDescription,
                tint = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun MetadataChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun sourceLabel(gameSource: GameSource): String = when (gameSource) {
    GameSource.STEAM -> stringResource(R.string.library_source_steam)
    GameSource.GOG -> stringResource(R.string.tab_gog)
    GameSource.EPIC -> stringResource(R.string.tab_epic)
    GameSource.AMAZON -> stringResource(R.string.tab_amazon)
    GameSource.CUSTOM_GAME -> stringResource(R.string.library_source_custom)
}

@Composable
private fun statusLabel(status: DownloadItemStatus): String = when (status) {
    DownloadItemStatus.DOWNLOADING -> stringResource(R.string.downloading)
    DownloadItemStatus.QUEUED -> stringResource(R.string.downloads_status_queued)
    DownloadItemStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
    DownloadItemStatus.RESUMABLE -> stringResource(R.string.downloads_resume_available)
    DownloadItemStatus.COMPLETED -> stringResource(R.string.downloads_status_complete)
    DownloadItemStatus.CANCELLED -> stringResource(R.string.downloads_status_cancelled)
    DownloadItemStatus.FAILED -> stringResource(R.string.downloads_status_failed)
}

@Composable
private fun sourceContainerColor(gameSource: GameSource): Color = when (gameSource) {
    GameSource.STEAM -> MaterialTheme.colorScheme.primaryContainer
    GameSource.GOG -> MaterialTheme.colorScheme.tertiaryContainer
    GameSource.EPIC -> MaterialTheme.colorScheme.secondaryContainer
    GameSource.AMAZON -> MaterialTheme.colorScheme.surfaceContainerHighest
    GameSource.CUSTOM_GAME -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun sourceContentColor(gameSource: GameSource): Color = when (gameSource) {
    GameSource.STEAM -> MaterialTheme.colorScheme.onPrimaryContainer
    GameSource.GOG -> MaterialTheme.colorScheme.onTertiaryContainer
    GameSource.EPIC -> MaterialTheme.colorScheme.onSecondaryContainer
    GameSource.AMAZON -> MaterialTheme.colorScheme.onSurface
    GameSource.CUSTOM_GAME -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusContainerColor(status: DownloadItemStatus): Color = when (status) {
    DownloadItemStatus.DOWNLOADING -> MaterialTheme.colorScheme.primaryContainer
    DownloadItemStatus.PAUSED,
    DownloadItemStatus.QUEUED,
    DownloadItemStatus.RESUMABLE,
    -> MaterialTheme.colorScheme.secondaryContainer
    DownloadItemStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
    DownloadItemStatus.CANCELLED,
    DownloadItemStatus.FAILED,
    -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun statusContentColor(status: DownloadItemStatus): Color = when (status) {
    DownloadItemStatus.DOWNLOADING -> MaterialTheme.colorScheme.onPrimaryContainer
    DownloadItemStatus.PAUSED,
    DownloadItemStatus.QUEUED,
    DownloadItemStatus.RESUMABLE,
    -> MaterialTheme.colorScheme.onSecondaryContainer
    DownloadItemStatus.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
    DownloadItemStatus.CANCELLED,
    DownloadItemStatus.FAILED,
    -> MaterialTheme.colorScheme.onErrorContainer
}

@Composable
private fun DownloadActionButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "downloadActionButtonScale",
    )
    val accentColor = PluviaTheme.colors.accentPurple

    Box(
        modifier = modifier
            .scale(scale)
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) {
                    accentColor.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                },
                shape = CircleShape,
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun formatEta(etaMs: Long): String {
    val totalSeconds = etaMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
