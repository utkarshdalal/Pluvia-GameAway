package app.gamenative.ui.component.dialog

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.AutomaticPlacementPlanner
import app.gamenative.mods.FomodEnvironmentSnapshot
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ResolvedModTargetRoot
import java.io.File
import org.junit.Rule
import org.junit.Test

class PlacementReviewAndroidTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun completeAutomaticPlan_exposesReviewAndExportAction() {
        val entries = listOf(
            ModArchiveEntry("Sounds/voice.wav", directory = false, sizeBytes = 1),
            ModArchiveEntry("Scripts/menu.pex", directory = false, sizeBytes = 1),
            ModArchiveEntry("SKSE/Plugins/Example.dll", directory = false, sizeBytes = 1),
            ModArchiveEntry("Example.esp", directory = false, sizeBytes = 1),
            ModArchiveEntry("Example.bsa", directory = false, sizeBytes = 1),
        )
        val automatic = AutomaticPlacementPlanner.plan("Skyrim Special Edition", entries)
        val recommended = checkNotNull(automatic.recommended)
        val drafts = recommended.drafts.map { draft ->
            RecipeDraft(
                sourceSubpath = draft.sourceSubpath,
                targetRelativePath = draft.targetRelativePath,
                mode = draft.mode,
                includeSourceDirectory = draft.includeSourceDirectory,
            )
        }
        val gameRoot = File(compose.activity.cacheDir, "placement-review-game").apply { mkdirs() }

        compose.setContent {
            MaterialTheme {
                PlacementSection(
                    install = ModInstall(
                        installId = "install-1",
                        appId = "steam:489830",
                        modName = "Example mod",
                        fileName = "example.zip",
                        archivePath = "/archive.zip",
                        extractedPath = "/extracted",
                    ),
                    entries = entries,
                    fomodInstaller = null,
                    fomodEnvironment = FomodEnvironmentSnapshot(),
                    fomodBaseDraft = RecipeDraft(targetRelativePath = "Data"),
                    roots = listOf(ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game folder", gameRoot)),
                    drafts = drafts,
                    presetOptions = emptyList(),
                    automaticPlacement = automatic,
                    automaticPlanLoading = false,
                    selectedAutomaticOptions = emptyMap(),
                    onAutomaticOptionSelected = { _, _ -> },
                    riskyAutomaticPlanApproved = false,
                    onRiskyAutomaticPlanApprovalChange = {},
                    reviewedPlan = null,
                    initialFomodSelections = emptyMap(),
                    onFomodSelectionsChanged = {},
                    previousOwnership = null,
                    canRestorePrevious = true,
                    onRestorePrevious = {},
                    placementChoice = PlacementChoice.AUTOMATIC,
                    canUseLastPlacement = false,
                    onPlacementChoiceChange = {},
                    onUseLastPlacement = {},
                    onPresetSelected = {},
                    onUpdateDraft = { _, _ -> },
                    onAddDraft = {},
                    onRemoveDraft = {},
                    onFomodRecipes = { _, _, _ -> },
                    applyStatusMessage = null,
                    onExportPlan = {},
                    onSaveAndApply = {},
                )
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.nexus_plan_review_title)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.nexus_plan_export))
            .assertExists()
            .assertHasClickAction()
        compose.onNodeWithText(compose.activity.getString(R.string.nexus_review_all_files, recommended.plan.files.size))
            .assertExists()
            .assertHasClickAction()
        compose.onNodeWithText(compose.activity.getString(R.string.nexus_restore_previous_deployment))
            .assertExists()
            .assertHasClickAction()
    }
}
