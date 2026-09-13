package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticPlacementPlannerTest {
    @Test
    fun bethesdaPlan_placesMixedLooseContentWithoutFlatteningDirectories() {
        val entries = archive("Sounds/theme.xwm", "Scripts/menu.pex", "Example.esp", "readme.txt")

        val candidate = AutomaticPlacementPlanner.plan("Skyrim Special Edition", entries).recommended!!

        assertTrue(candidate.plan.isComplete)
        assertEquals(
            setOf("Data/Sounds/theme.xwm", "Data/Scripts/menu.pex", "Data/Example.esp"),
            candidate.plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertEquals(PlannedFileStatus.INTENTIONALLY_IGNORED, candidate.plan.files.single { it.sourceRelativePath == "readme.txt" }.status)
    }

    @Test
    fun bethesdaPlan_ignoresDocumentationAndManagerMetadataButKeepsRuntimeText() {
        val plan = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive(
                "Data/Interface/Translations/example_english.txt",
                "BashTags/Example.txt",
                "Docs/Example Readme + Credits.html",
                "credits.html",
            ),
        ).recommended!!.plan

        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(
            PlannedFileStatus.PLACED,
            plan.files.single { it.sourceRelativePath.contains("Translations") }.status,
        )
        assertTrue(
            plan.files.filterNot { it.sourceRelativePath.contains("Translations") }
                .all { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED },
        )
    }

    @Test
    fun bethesdaPlan_stripsOneWrapperAndDataContainer() {
        val candidate = AutomaticPlacementPlanner.plan(
            "Fallout 4",
            archive("Cool Mod/Data/meshes/rifle.nif", "Cool Mod/Data/textures/rifle.dds"),
        ).recommended!!

        assertEquals(
            setOf("Data/meshes/rifle.nif", "Data/textures/rifle.dds"),
            candidate.plan.files.mapNotNull { it.targetRelativePath }.toSet(),
        )
        assertTrue(candidate.plan.isComplete)
    }

    @Test
    fun automaticPlan_blocksUnexplainedInstallableFiles() {
        val candidate = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Sounds/theme.xwm", "Mystery/config.bin"),
        ).recommended!!

        assertFalse(candidate.plan.isComplete)
        assertEquals(PlannedFileStatus.UNSUPPORTED, candidate.plan.files.single { it.sourceRelativePath == "Mystery/config.bin" }.status)
        assertTrue(candidate.plan.blockingIssues.isNotEmpty())
    }

    @Test
    fun planningIsStableAcrossArchiveOrderAndManualFolderInferencePreservesContentFolder() {
        val first = archive("Sounds/theme.xwm", "Scripts/menu.pex", "Example.esp")
        val second = first.reversed()

        assertEquals(
            AutomaticPlacementPlanner.plan("Skyrim Special Edition", first).recommended!!.plan.digest,
            AutomaticPlacementPlanner.plan("Skyrim Special Edition", second).recommended!!.plan.digest,
        )
        assertTrue(AutomaticPlacementPlanner.inferIncludeSourceDirectory(listOf("Sounds"), first, "Data"))
        assertFalse(AutomaticPlacementPlanner.inferIncludeSourceDirectory(listOf("Data"), archive("Data/file.txt"), "Data"))
    }

    @Test
    fun ambiguousStructuralVariants_blockAutomaticChoiceAndKeepCommonFolderVisible() {
        val result = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Option A/Data/textures/x.dds", "Option B/Data/textures/x.dds", "Common/Data/scripts/y.pex"),
        )

        assertEquals(setOf("Option A", "Option B"), result.optionGroups.single().choices.map { it.sourceDirectory }.toSet())
        assertEquals(listOf("Common"), result.optionGroups.single().commonSourceDirectories)
        assertFalse(result.recommended!!.plan.isComplete)
        assertTrue(result.recommended!!.plan.blockingIssues.any { "variant" in it.lowercase() })

        val group = result.optionGroups.single()
        val selected = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Option A/Data/textures/x.dds", "Option B/Data/textures/x.dds", "Common/Data/scripts/y.pex"),
            selectedOptions = mapOf(group.stableId to "Option B"),
        ).recommended!!.plan
        assertTrue(selected.blockingIssues.toString(), selected.isComplete)
        assertEquals(
            setOf("Data/textures/x.dds", "Data/scripts/y.pex"),
            selected.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertTrue(selected.files.any { it.sourceRelativePath.startsWith("Option A/") && it.status == PlannedFileStatus.INTENTIONALLY_IGNORED })
    }

    @Test
    fun nestedStructuralVariants_areDetectedWithoutDependingOnFolderNames() {
        val entries = archive(
            "Package/Choices/Blue/Data/textures/x.dds",
            "Package/Choices/Red/Data/textures/x.dds",
            "Package/Choices/Common/Data/scripts/y.pex",
        )
        val result = AutomaticPlacementPlanner.plan("Skyrim Special Edition", entries)
        val group = result.optionGroups.single()

        assertEquals(
            setOf("Package/Choices/Blue", "Package/Choices/Red"),
            group.choices.mapTo(mutableSetOf()) { it.sourceDirectory },
        )
        assertEquals(listOf("Package/Choices/Common"), group.commonSourceDirectories)
        assertFalse(result.recommended!!.plan.isComplete)
    }

    @Test
    fun mixedDataAndRootBinary_areSeparatedAndRootBinaryRequiresReview() {
        val plan = AutomaticPlacementPlanner.plan(
            "Skyrim Special Edition",
            archive("Data/Scripts/x.pex", "dinput8.dll"),
        ).recommended!!.plan

        assertEquals(
            setOf("Data/Scripts/x.pex", "dinput8.dll"),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.mapNotNull { it.targetRelativePath }.toSet(),
        )
        assertFalse(plan.isComplete)
        assertEquals(PlacementRisk.UNSAFE, plan.files.single { it.sourceRelativePath == "dinput8.dll" }.risk)
        val approved = plan.withRiskyRootApproval(true)
        assertTrue(approved.isComplete)
        assertFalse(approved.withRiskyRootApproval(false).isComplete)
    }

    @Test
    fun frameworkRules_produceCompletePlansWithoutRegressingLegacyTargets() {
        val cases = listOf(
            Triple("Any Unity game", archive("BepInEx/plugins/Test.dll", "BepInEx/config/Test.cfg"), setOf("BepInEx/plugins/Test.dll", "BepInEx/config/Test.cfg")),
            Triple("Another Unity game", archive("Mods/Test.dll", "UserData/settings.cfg"), setOf("Mods/Test.dll", "UserData/settings.cfg")),
            Triple("Any Unreal game", archive("Content/Paks/Test.pak", "Content/Paks/Test.utoc"), setOf("Content/Paks/Test.pak", "Content/Paks/Test.utoc")),
            Triple("Cyberpunk 2077", archive("archive/pc/mod/Test.archive", "r6/scripts/Test.reds"), setOf("archive/pc/mod/Test.archive", "r6/scripts/Test.reds")),
        )

        cases.forEach { (game, entries, targets) ->
            val plan = AutomaticPlacementPlanner.plan(game, entries).recommended!!.plan
            assertTrue("$game: ${plan.blockingIssues}", plan.isComplete)
            assertEquals(targets, plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet())
        }
    }

    @Test
    fun existingGameFolders_outweighAnUnprovenSingleFolderGuess() {
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Classic arena game",
            entries = archive(
                "Help/Map Readme.txt",
                "Maps/CTF-Arena.ut2",
                "Music/Arena.ogg",
                "Screenshots/Arena.jpg",
            ),
            context = AutomaticPlacementContext(
                defaultTargetRelativePath = "Sounds",
                defaultTargetIsProven = false,
                existingGameDirectories = setOf("Maps", "Music", "Sounds"),
            ),
        )
        val plan = result.recommended!!.plan

        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(
            setOf("Maps/CTF-Arena.ut2", "Music/Arena.ogg"),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertTrue(
            plan.files.filter { it.sourceRelativePath.startsWith("Help/") || it.sourceRelativePath.startsWith("Screenshots/") }
                .all { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED },
        )
    }

    @Test
    fun combinedRules_blockOneSourceMappedToDifferentDestinations() {
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Classic game",
            entries = archive("Mods/Plugin.dll"),
            context = AutomaticPlacementContext(
                defaultTargetRelativePath = "Packages",
                defaultTargetIsProven = true,
                existingGameDirectories = setOf("Mods"),
            ),
        )

        val combined = result.candidates.single { it.id == "rules:combined-v1" }

        assertEquals(PlannedFileStatus.CONFLICTED, combined.plan.files.single().status)
        assertTrue(combined.plan.blockingIssues.any { "multiple destinations" in it.lowercase() })
    }

    @Test
    fun provenModDirectory_preservesPackageWrapperAndStripsOnlySelectedVariant() {
        val entries = archive(
            "CharacterEditor/v1/About/About.xml",
            "CharacterEditor/v1/Assemblies/Editor.dll",
            "CharacterEditor/v1.6/About/About.xml",
            "CharacterEditor/v1.6/Assemblies/Editor.dll",
            "CharacterEditor/Textures/Icon.png",
        )
        val context = AutomaticPlacementContext(
            defaultTargetRelativePath = "Mods",
            defaultTargetIsProven = true,
        )
        val initial = AutomaticPlacementPlanner.plan("Colony game", entries, context = context)
        val optionGroup = initial.optionGroups.single()
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Colony game",
            entries = entries,
            selectedOptions = mapOf(optionGroup.stableId to "CharacterEditor/v1.6"),
            context = context,
        )
        val plan = result.recommended!!.plan

        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(
            setOf(
                "Mods/CharacterEditor/About/About.xml",
                "Mods/CharacterEditor/Assemblies/Editor.dll",
                "Mods/CharacterEditor/Textures/Icon.png",
            ),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertTrue(
            plan.files.filter { it.sourceRelativePath.startsWith("CharacterEditor/v1/") }
                .all { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED },
        )
    }

    @Test
    fun provenModDirectory_treatsDifferentlyShapedVersionFoldersAsOneChoice() {
        val entries = archive(
            "CharacterEditor/About/About.xml",
            "CharacterEditor/Defs/CharEditor.xml",
            "CharacterEditor/Textures/Icon.png",
            "CharacterEditor/v1.0/0Harmony.dll",
            "CharacterEditor/v1.0/CharacterEditor.dll",
            "CharacterEditor/v1.1/Assemblies/CharacterEditor.dll",
            "CharacterEditor/v1.3/Assemblies/CharacterEditor.dll",
            "CharacterEditor/v1.3/Defs/GradientHairMasks.xml",
            "CharacterEditor/v1.6/Assemblies/CharacterEditor.dll",
            "CharacterEditor/v1.6/Defs/LifeStageGiant.xml",
            "CharacterEditor/v1.6/Gradients/GradientHairMasks.xml",
        )
        val context = AutomaticPlacementContext(
            defaultTargetRelativePath = "Mods",
            defaultTargetIsProven = true,
        )
        val initial = AutomaticPlacementPlanner.plan("RimWorld", entries, context = context)
        val optionGroup = initial.optionGroups.single()

        assertEquals(
            setOf(
                "CharacterEditor/v1.0",
                "CharacterEditor/v1.1",
                "CharacterEditor/v1.3",
                "CharacterEditor/v1.6",
            ),
            optionGroup.choices.mapTo(mutableSetOf()) { it.sourceDirectory },
        )
        assertEquals(
            setOf("CharacterEditor/About", "CharacterEditor/Defs", "CharacterEditor/Textures"),
            optionGroup.commonSourceDirectories.toSet(),
        )

        val plan = AutomaticPlacementPlanner.plan(
            gameName = "RimWorld",
            entries = entries,
            selectedOptions = mapOf("previous-detector-id" to "CharacterEditor/v1.6"),
            context = context,
        ).recommended!!.plan

        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(
            setOf(
                "Mods/CharacterEditor/About/About.xml",
                "Mods/CharacterEditor/Defs/CharEditor.xml",
                "Mods/CharacterEditor/Textures/Icon.png",
                "Mods/CharacterEditor/Assemblies/CharacterEditor.dll",
                "Mods/CharacterEditor/Defs/LifeStageGiant.xml",
                "Mods/CharacterEditor/Gradients/GradientHairMasks.xml",
            ),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
        assertTrue(
            plan.files.filter { "/v1." in it.sourceRelativePath && !it.sourceRelativePath.startsWith("CharacterEditor/v1.6/") }
                .all { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED },
        )
    }

    @Test
    fun unrelatedPairOfNumberedFolders_isNotAssumedToBeAChoice() {
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Numbered content",
            entries = archive(
                "Package/v1.0/Textures/First.dds",
                "Package/v2.0/Sounds/Second.wav",
            ),
        )

        assertTrue(result.optionGroups.isEmpty())
    }

    @Test
    fun ordinaryContentNamespacesWithMatchingLayouts_areNotPackageVariants() {
        val cases = listOf(
            archive(
                "Package/dungeons/cove/shared/room.darkest",
                "Package/dungeons/crypts/shared/room.darkest",
                "Package/dungeons/town/shared/room.darkest",
            ),
            archive(
                "CreativeMode/interface/cheatingtable/window.config",
                "CreativeMode/interface/furnituretable/window.config",
                "CreativeMode/interface/spawningtable/window.config",
            ),
        )

        cases.forEach { entries ->
            assertTrue(GenericOptionSetDetector.detect(ModArchiveIndex.build(entries)).isEmpty())
        }
    }

    @Test
    fun structuralWrappersAroundAnInstallRoot_areStillPackageVariants() {
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Modded game",
            entries = archive(
                "Blue/Data/textures/shared.dds",
                "Red/Data/textures/shared.dds",
            ),
        )

        assertEquals(
            setOf("Blue", "Red"),
            result.optionGroups.single().choices.mapTo(mutableSetOf()) { it.sourceDirectory },
        )
    }

    @Test
    fun readmeTrees_areIgnoredAndNeverBecomePackageVariants() {
        val entries = archive(
            "Campaign Module/Module.ini",
            "Campaign Module/troops.txt",
            "Campaign Module/_README_PACKAGE/OPTIONAL_FONT_ENG/font.dds",
            "Campaign Module/_README_PACKAGE/OPTIONAL_FONT_RUS_UKR_ENG/font.dds",
        )
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Module game",
            entries = entries,
            context = AutomaticPlacementContext(
                defaultTargetRelativePath = "Modules",
                defaultTargetIsProven = true,
            ),
        )

        assertTrue(result.optionGroups.isEmpty())
        val plan = result.recommended!!.plan
        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(
            setOf("Campaign Module/Module.ini", "Campaign Module/troops.txt"),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.mapTo(mutableSetOf()) { it.sourceRelativePath },
        )
        assertTrue(
            plan.files.filter { "/_README_PACKAGE/" in it.sourceRelativePath }
                .all { it.status == PlannedFileStatus.INTENTIONALLY_IGNORED },
        )
    }

    @Test
    fun documentationPrefixes_doNotHideInstallableFoldersOrBinariesWithSimilarNames() {
        val index = ModArchiveIndex.build(
            archive(
                "ManualTransmission/ManualTransmission.dll",
                "ScreenshotsEnhanced/ScreenshotsEnhanced.esp",
                "_README_PACKAGE/guide.txt",
                "ReadMeFirst.txt",
            ),
        )

        assertEquals(
            ArchiveContentRole.INSTALLABLE,
            index.files.single { it.displayPath.endsWith("ManualTransmission.dll") }.role,
        )
        assertEquals(
            ArchiveContentRole.INSTALLABLE,
            index.files.single { it.displayPath.endsWith("ScreenshotsEnhanced.esp") }.role,
        )
        assertTrue(
            index.files.filter { it.displayPath.contains("README", ignoreCase = true) }
                .all { it.role == ArchiveContentRole.DOCUMENTATION },
        )
    }

    @Test
    fun provenTargetContainer_isMergedWithoutDuplicatingItsFolderName() {
        val result = AutomaticPlacementPlanner.plan(
            gameName = "Plugin game",
            entries = archive("Mods/Example/About.xml", "Mods/Example/Runtime.dll"),
            context = AutomaticPlacementContext(
                defaultTargetRelativePath = "Mods",
                defaultTargetIsProven = true,
            ),
        )
        val plan = result.recommended!!.plan

        assertTrue(plan.blockingIssues.toString(), plan.isComplete)
        assertEquals(
            setOf("Mods/Example/About.xml", "Mods/Example/Runtime.dll"),
            plan.files.filter { it.status == PlannedFileStatus.PLACED }.map { it.targetRelativePath }.toSet(),
        )
    }

    private fun archive(vararg paths: String): List<ModArchiveEntry> =
        paths.map { ModArchiveEntry(it, directory = false, sizeBytes = 1L) }
}
