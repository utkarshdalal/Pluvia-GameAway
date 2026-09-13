package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModInstallPlanContractTest {
    @Test
    fun plan_requiresEverySelectedFileToBePlacedOrExplained() {
        val complete = plan(placed = listOf("Sounds/theme.xwm"), ignored = listOf("readme.txt"))
        val partial = plan(placed = listOf("Sounds/theme.xwm"), unsupported = listOf("install.exe"))

        assertTrue(complete.isComplete)
        assertEquals(1.0, complete.coverage, 0.0)
        assertFalse(partial.isComplete)
        assertEquals(1, partial.unresolvedCount)
    }

    @Test
    fun differentialPolicy_neverTradesCoverageForAReplacement() {
        val baseline = plan(placed = listOf("Scripts/a.pex", "Plugin.esp"))
        val worse = plan(placed = listOf("Scripts/a.pex"), unsupported = listOf("Plugin.esp"))
        val better = plan(placed = listOf("Scripts/a.pex", "Plugin.esp", "Sounds/theme.xwm"))

        assertFalse(PlacementPlanRegressionPolicy.canReplace(baseline, worse))
        assertTrue(PlacementPlanRegressionPolicy.canReplace(baseline, better))
    }

    @Test
    fun digest_isStableAcrossArchiveEnumerationOrder() {
        val first = plan(placed = listOf("Scripts/a.pex", "Sounds/theme.xwm"))
        val second = plan(placed = listOf("Sounds/theme.xwm", "Scripts/a.pex"))

        assertEquals(first.digest, second.digest)
    }

    @Test
    fun diagnosticSanitizer_removesCredentialsAndAbsolutePaths() {
        val sanitized = ModDiagnosticSanitizer.text(
            "C:\\Games\\Skyrim\\Data https://example.invalid/file?X-Amz-Credential=secret&X-Amz-Signature=123 " +
                "/data/user/0/app/file https://user:password@example.invalid/file " +
                "Authorization: Bearer header-secret apikey=key-secret",
        )

        assertFalse("secret" in sanitized)
        assertFalse("password" in sanitized)
        assertFalse("C:\\Games" in sanitized)
        assertFalse("/data/user" in sanitized)
    }

    @Test
    fun healthManifest_includesSupportFactsWithoutLeakingPaths() {
        val report = ModHealthReport(
            issues = listOf(
                ModHealthIssue(
                    severity = ModHealthSeverity.WARNING,
                    title = "Managed files changed",
                    detail = "C:\\Games\\Example\\Data\\changed.esp",
                ),
            ),
            facts = listOf("ownership-producers=automatic@3:2", "cache=C:\\private\\mods"),
        )

        val manifest = report.sanitizedManifest()

        assertTrue("fact: ownership-producers=automatic@3:2" in manifest)
        assertFalse("C:\\Games" in manifest)
        assertFalse("C:\\private" in manifest)
    }

    @Test
    fun oneRiskPolicy_protectsExecutableRootsRegardlessOfPlanOrigin() {
        val rootDll = file("dxgi.dll", PlannedFileStatus.PLACED, "FOMOD mapping").copy(
            targetRelativePath = "dxgi.dll",
            normalizedTargetKey = "game_dir:dxgi.dll",
            origin = PlacementOrigin.FOMOD_OPTION,
        )
        val dataDll = rootDll.copy(
            sourceRelativePath = "MCMHelper.dll",
            targetRelativePath = "Data/SKSE/Plugins/MCMHelper.dll",
            normalizedTargetKey = "game_dir:data/skse/plugins/mcmhelper.dll",
        )

        val guarded = PlacementRiskPolicy.enforce(ModInstallPlan(listOf(rootDll, dataDll)))

        assertEquals(PlacementRisk.UNSAFE, guarded.files.first().risk)
        assertEquals(PlacementRisk.SAFE, guarded.files.last().risk)
        assertFalse(guarded.isComplete)
        assertTrue(guarded.withRiskApproval(true).isComplete)
    }

    private fun plan(
        placed: List<String>,
        ignored: List<String> = emptyList(),
        unsupported: List<String> = emptyList(),
    ): ModInstallPlan = ModInstallPlan(
        files = buildList {
            placed.forEach { source ->
                add(file(source, PlannedFileStatus.PLACED, "Selected by fixture rule"))
            }
            ignored.forEach { source ->
                add(file(source, PlannedFileStatus.INTENTIONALLY_IGNORED, "Known documentation"))
            }
            unsupported.forEach { source ->
                add(file(source, PlannedFileStatus.UNSUPPORTED, "Requires review", PlacementRisk.REVIEW))
            }
        },
        blockingIssues = unsupported.map { "Unresolved installable file: $it" },
    )

    private fun file(
        source: String,
        status: PlannedFileStatus,
        reason: String,
        risk: PlacementRisk = PlacementRisk.SAFE,
    ): PlannedModFile = PlannedModFile(
        sourceRelativePath = source,
        targetRoot = ModTargetRoot.GAME_DIR.name.takeIf { status == PlannedFileStatus.PLACED },
        targetRelativePath = "Data/$source".takeIf { status == PlannedFileStatus.PLACED },
        normalizedTargetKey = "game_dir:data/${source.lowercase()}".takeIf { status == PlannedFileStatus.PLACED },
        status = status,
        origin = PlacementOrigin.GAME_RULE,
        sizeBytes = 1L,
        reason = reason,
        risk = risk,
    )
}
