package app.gamenative.mods

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class FomodEnvironmentTest {
    @Test
    fun dependencies_preserveUnknownAndEvaluateSupportedFacts() {
        val expression = FomodDependencyExpression(
            fileDependencies = listOf(FomodFileDependency("Data/Required.dll", FomodRequiredFileState.ACTIVE)),
            pluginDependencies = listOf(FomodPluginDependency("Example.esp", FomodRequiredFileState.ACTIVE)),
            gameDependencies = listOf(FomodGameDependency("1.6.0")),
        )

        assertEquals(FomodFactState.UNKNOWN, expression.evaluate(emptyMap(), FomodEnvironmentSnapshot()))
        assertEquals(
            FomodFactState.TRUE,
            expression.evaluate(
                emptyMap(),
                FomodEnvironmentSnapshot(
                    gameVersion = "1.6.1170",
                    fileFacts = mapOf("data/required.dll" to true),
                    presentPlugins = setOf("example.esp"),
                    activePlugins = setOf("example.esp"),
                ),
            ),
        )
    }

    @Test
    fun unknownConditional_blocksInsteadOfGuessing() {
        val installer = FomodInstaller(
            moduleName = "Dependencies",
            requiredFiles = emptyList(),
            steps = emptyList(),
            conditionalFileInstalls = listOf(
                FomodConditionalFileInstall(
                    FomodDependencyExpression(
                        fileDependencies = listOf(FomodFileDependency("Data/Maybe.dll", FomodRequiredFileState.ACTIVE)),
                    ),
                    listOf(FomodFileMapping("Maybe.dll", "Maybe.dll", 0, directory = false)),
                ),
            ),
        )

        val result = FomodSelectionEvaluator.evaluate(installer, emptySet())

        assertTrue(result.mappings.isEmpty())
        assertTrue(result.blockingIssues.any { "unknown" in it.lowercase() })
    }

    @Test
    fun unknownModuleGameVersion_warnsWithoutBlockingExplicitChoices() {
        val installer = FomodInstaller(
            moduleName = "Versioned installer",
            requiredFiles = emptyList(),
            steps = emptyList(),
            moduleDependencies = FomodDependencyExpression(
                gameDependencies = listOf(FomodGameDependency("1.6.629")),
            ),
        )

        val result = FomodSelectionEvaluator.evaluate(installer, emptySet())

        assertTrue(result.blockingIssues.isEmpty())
        assertTrue(result.warnings.any { "could not be verified" in it })
    }

    @Test
    fun unknownOptionAvailability_warnsWithoutBlockingSelectedVersion() {
        val installer = FomodInstaller(
            moduleName = "Version-gated installer",
            requiredFiles = emptyList(),
            steps = listOf(
                FomodStep(
                    name = "Main",
                    groups = listOf(
                        FomodGroup(
                            name = "DLL",
                            type = FomodGroupType.SELECT_EXACTLY_ONE,
                            plugins = listOf(
                                versionedPlugin("New game version", "New/Example.dll", "1.6.629"),
                                versionedPlugin("Old game version", "Old/Example.dll", "1.6.353"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val selected = setOf(FomodRecipeGenerator.pluginKey(0, 0, 0))
        val result = FomodSelectionEvaluator.evaluate(installer, selected)

        assertEquals(listOf("New/Example.dll"), result.mappings.map { it.mapping.source })
        assertTrue(result.blockingIssues.isEmpty())
        assertTrue(result.warnings.any { "explicit choices" in it })
    }

    @Test
    fun environment_discoversScriptExtenderVersionAndDllArchitecture() {
        val root = createTempDirectory("fomod-environment").toFile()
        try {
            File(root, "skse64_loader.exe").writeText("loader")
            File(root, "skse64_2_02_06.dll").writeBytes(peHeader(0x8664))
            File(root, "Data/SKSE/Plugins/Test.dll").apply {
                parentFile?.mkdirs()
                writeBytes(peHeader(0x8664))
            }
            val installer = FomodInstaller(
                moduleName = "Environment",
                requiredFiles = emptyList(),
                steps = emptyList(),
                moduleDependencies = FomodDependencyExpression(
                    fileDependencies = listOf(FomodFileDependency("SKSE/Plugins/Test.dll", FomodRequiredFileState.ACTIVE)),
                ),
            )

            val snapshot = FomodEnvironmentSnapshotBuilder.build(installer, "Skyrim Special Edition", root)

            assertTrue(snapshot.scriptExtenders.getValue("skse").present)
            assertEquals("2.2.6", snapshot.scriptExtenders.getValue("skse").version)
            assertEquals(NativeBinaryArchitecture.X64, snapshot.nativeDllArchitectures["skse/plugins/test.dll"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun environment_honorsModernPluginMarkersAndLegacyUnmarkedLists() {
        val root = createTempDirectory("fomod-plugin-state").toFile()
        try {
            File(root, "Data/Enabled.esp").apply { parentFile?.mkdirs(); writeText("enabled") }
            File(root, "Data/Disabled.esp").writeText("disabled")
            val installer = FomodInstaller(
                moduleName = "Plugin state",
                requiredFiles = emptyList(),
                steps = emptyList(),
                moduleDependencies = FomodDependencyExpression(
                    pluginDependencies = listOf(
                        FomodPluginDependency("Enabled.esp", FomodRequiredFileState.ACTIVE),
                        FomodPluginDependency("Disabled.esp", FomodRequiredFileState.INACTIVE),
                    ),
                ),
            )
            val pluginsFile = File(root, "plugins.txt")
            pluginsFile.writeText("*Enabled.esp\nDisabled.esp\n")

            val modern = FomodEnvironmentSnapshotBuilder.build(installer, "Skyrim Special Edition", root, pluginsFile)

            assertEquals(setOf("enabled.esp"), modern.activePlugins)
            assertEquals(setOf("enabled.esp", "disabled.esp"), modern.presentPlugins)

            pluginsFile.writeText("Enabled.esp\nDisabled.esp\n")
            val legacy = FomodEnvironmentSnapshotBuilder.build(installer, "Skyrim Special Edition", root, pluginsFile)

            assertEquals(setOf("enabled.esp", "disabled.esp"), legacy.activePlugins)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun inspectedEmptyPluginState_provesMissingDependency() {
        val root = createTempDirectory("fomod-empty-plugin-state").toFile()
        try {
            val dependency = FomodPluginDependency("Absent.esp", FomodRequiredFileState.MISSING)
            val installer = FomodInstaller(
                moduleName = "Missing plugin dependency",
                requiredFiles = emptyList(),
                steps = emptyList(),
                moduleDependencies = FomodDependencyExpression(pluginDependencies = listOf(dependency)),
            )

            val snapshot = FomodEnvironmentSnapshotBuilder.build(installer, "Skyrim Special Edition", root)

            assertTrue(snapshot.pluginStateKnown)
            assertEquals(FomodFactState.TRUE, snapshot.evaluate(dependency))
            assertEquals(FomodFactState.UNKNOWN, FomodEnvironmentSnapshot().evaluate(dependency))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun peHeader(machine: Int): ByteArray = ByteArray(512).also { bytes ->
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x3c, 0x80)
            putInt(0x80, 0x00004550)
            putShort(0x84, machine.toShort())
        }
    }

    private fun versionedPlugin(name: String, source: String, version: String) = FomodPlugin(
        name = name,
        description = "",
        imagePath = "",
        type = FomodPluginType.OPTIONAL,
        files = listOf(FomodFileMapping(source, "SKSE/Plugins/Example.dll", 0, false)),
        typePatterns = listOf(
            FomodTypePattern(
                dependencies = FomodDependencyExpression(
                    gameDependencies = listOf(FomodGameDependency(version)),
                ),
                type = FomodPluginType.RECOMMENDED,
            ),
        ),
    )

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
