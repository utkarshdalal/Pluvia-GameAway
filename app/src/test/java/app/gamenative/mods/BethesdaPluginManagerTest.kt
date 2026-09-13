package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class BethesdaPluginManagerTest {
    private lateinit var tempDir: File
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("bethesda_plugins").toFile()
        gameDir = File(tempDir, "game").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun detectGame_recognizesCommonBethesdaNames() {
        assertEquals(BethesdaGame.SKYRIM_SPECIAL_EDITION, BethesdaPluginManager.detectGame("The Elder Scrolls V: Skyrim Special Edition"))
        assertEquals(BethesdaGame.FALLOUT_NEW_VEGAS, BethesdaPluginManager.detectGame("Fallout: New Vegas"))
        assertEquals(BethesdaGame.STARFIELD, BethesdaPluginManager.detectGame("Starfield"))
    }

    @Test
    fun detectPlugins_readsPlannedPluginFilesAndEnabledState() = runBlocking {
        val install = install()
        File(install.extractedPath, "Data/Example.esm").apply {
            parentFile?.mkdirs()
            writeText("esm")
        }
        File(install.extractedPath, "Data/Example.esp").writeText("esp")
        val pluginsTxt = File(tempDir, "plugins.txt").apply { writeText("*Example.esm\n*example.esp\n") }

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(recipe(install.installId))),
            prioritiesByInstallId = mapOf(install.installId to 5),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = pluginsTxt,
        )

        assertEquals(listOf("Example.esm", "Example.esp"), plugins.map { it.fileName })
        assertEquals(true, plugins.first { it.fileName == "Example.esm" }.enabled)
        assertEquals(true, plugins.first { it.fileName == "Example.esp" }.enabled)
        assertTrue(plugins.all { it.priority == 5 })
    }

    @Test
    fun detectPlugins_treatsMissingPluginsTxtAsEmpty() = runBlocking {
        val install = install()
        File(install.extractedPath, "Data/Example.esp").apply {
            parentFile?.mkdirs()
            writeText("esp")
        }

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(recipe(install.installId))),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = File(tempDir, "missing/plugins.txt"),
        )

        assertEquals(listOf("Example.esp"), plugins.map { it.fileName })
        assertEquals(false, plugins.single().enabled)
    }

    @Test
    fun detectPlugins_usesRenamedTargetExtension() = runBlocking {
        val install = install()
        File(install.extractedPath, "Choice/Plugin.bin").apply {
            parentFile?.mkdirs()
            writeText("plugin")
        }
        val renamedRecipe = ModPlacementRecipe(
            installId = install.installId,
            sourceSubpath = "Choice/Plugin.bin",
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data",
            targetFileName = "Renamed.esp",
            mode = ModPlacementMode.OVERWRITE_COPY.name,
        )

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(renamedRecipe)),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = null,
        )

        assertEquals(listOf("Renamed.esp"), plugins.map { it.fileName })
    }

    @Test
    fun detectPlugins_canDefaultNewPluginsToEnabled() = runBlocking {
        val install = install()
        File(install.extractedPath, "Data/Example.esp").apply {
            parentFile?.mkdirs()
            writeText("esp")
        }

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(recipe(install.installId))),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = File(tempDir, "missing/plugins.txt"),
            defaultEnabled = true,
        )

        assertEquals(true, plugins.single().enabled)
    }

    @Test
    fun detectPlugins_reusesAppliedOwnershipWithoutRebuildingArchivePlan() = runBlocking {
        val install = install()
        val source = File(install.extractedPath, "Choices/Cloaks.esp").apply {
            parentFile?.mkdirs()
            writeText("esp")
        }
        val target = File(gameDir, "Data/Cloaks.esp")
        val ownership = ModOwnershipManifest(
            installId = install.installId,
            appId = install.appId,
            planDigest = "owned",
            files = listOf(
                ModOwnedFile(
                    sourceRelativePath = source.relativeTo(File(install.extractedPath)).path,
                    targetRoot = ModTargetRoot.GAME_DIR.name,
                    targetRelativePath = "Data/Cloaks.esp",
                    targetPath = target.absolutePath,
                    normalizedTargetKey = WindowsPathIdentity.absoluteKey(target),
                    mode = ModPlacementMode.OVERWRITE_COPY.name,
                    installedHash = "hash",
                    installedSize = source.length(),
                    installedMtime = source.lastModified(),
                    disposition = ModOwnedFileDisposition.CREATED,
                ),
            ),
        )

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = emptyMap(),
            prioritiesByInstallId = mapOf(install.installId to 7),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = File(tempDir, "missing/plugins.txt"),
            ownershipByInstallId = mapOf(install.installId to ownership),
            defaultEnabled = true,
        )

        assertEquals("Cloaks.esp", plugins.single().fileName)
        assertEquals(source.absolutePath, plugins.single().sourcePath)
    }

    @Test
    fun detectPlugins_ignoresReadyModsThatHaveNotBeenApplied() = runBlocking {
        val install = install().copy(status = ModInstallStatus.READY.name)
        File(install.extractedPath, "Data/Unplaced.esp").apply {
            parentFile?.mkdirs()
            writeText("esp")
        }

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(recipe(install.installId))),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = File(tempDir, "missing/plugins.txt"),
            defaultEnabled = true,
        )

        assertTrue(plugins.isEmpty())
    }

    @Test
    fun updateManagedPluginsTxt_preservesUnmanagedEntriesAndWritesManagedOrder() {
        val file = File(tempDir, "plugins.txt").apply {
            writeText("# comment\n*Skyrim.esm\n*OldManaged.esp\n*External.esp\n")
        }

        BethesdaPluginManager.updateManagedPluginsTxt(
            file,
            listOf(
                BethesdaPlugin("OldManaged.esp", "old", "Old", "", enabled = false, priority = 0),
                BethesdaPlugin("NewManaged.esp", "new", "New", "", enabled = true, priority = 1),
            ),
        )

        assertEquals("# comment\n*Skyrim.esm\n*External.esp\nOldManaged.esp\n*NewManaged.esp\n", file.readText())
        assertEquals("Skyrim.esm\nExternal.esp\nOldManaged.esp\nNewManaged.esp\n", File(tempDir, "loadorder.txt").readText())
    }

    @Test
    fun detectPlugins_preservesDisabledManagedOrderFromPluginsTxt() = runBlocking {
        val install = install()
        File(install.extractedPath, "Data/OldManaged.esp").apply {
            parentFile?.mkdirs()
            writeText("esp")
        }
        File(install.extractedPath, "Data/NewManaged.esp").writeText("esp")
        val pluginsTxt = File(tempDir, "plugins.txt").apply {
            writeText("OldManaged.esp\n*NewManaged.esp\n")
        }

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(recipe(install.installId))),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = pluginsTxt,
        )

        assertEquals(listOf("OldManaged.esp", "NewManaged.esp"), plugins.map { it.fileName })
        assertEquals(false, plugins.first().enabled)
        assertEquals(true, plugins.last().enabled)
    }

    @Test
    fun detectPlugins_treatsPlainOblivionPluginEntriesAsEnabled() = runBlocking {
        val install = install()
        File(install.extractedPath, "Data/Example.esp").apply {
            parentFile?.mkdirs()
            writeText("esp")
        }
        val pluginsTxt = File(tempDir, "Oblivion/plugins.txt").apply {
            parentFile?.mkdirs()
            writeText("Example.esp\n")
        }

        val plugins = BethesdaPluginManager.detectPlugins(
            installs = listOf(install),
            recipesByInstallId = mapOf(install.installId to listOf(recipe(install.installId))),
            prioritiesByInstallId = emptyMap(),
            gameRootDir = gameDir,
            winePrefix = "",
            pluginsFile = pluginsTxt,
        )

        assertEquals(true, plugins.single().enabled)
    }

    @Test
    fun updateManagedPluginsTxt_addsSkyrimBaseAndCreationClubPlugins() {
        File(gameDir, "Data").mkdirs()
        listOf("Skyrim.esm", "Update.esm", "Dawnguard.esm", "_ResourcePack.esl", "ccBGSSSE001-Fish.esm").forEach { name ->
            File(gameDir, "Data/$name").writeText("plugin")
        }
        val file = File(tempDir, "plugins.txt")

        BethesdaPluginManager.updateManagedPluginsTxt(
            file = file,
            managedPlugins = listOf(BethesdaPlugin("Mod.esp", "mod", "Mod", "", enabled = true, priority = 0)),
            game = BethesdaGame.SKYRIM_SPECIAL_EDITION,
            gameRootDir = gameDir,
        )

        assertEquals(
            "*Skyrim.esm\n*Update.esm\n*Dawnguard.esm\n*_ResourcePack.esl\n*ccBGSSSE001-Fish.esm\n*Mod.esp\n",
            file.readText(),
        )
        assertEquals(
            "Skyrim.esm\nUpdate.esm\nDawnguard.esm\n_ResourcePack.esl\nccBGSSSE001-Fish.esm\nMod.esp\n",
            File(tempDir, "loadorder.txt").readText(),
        )
    }

    @Test
    fun updateManagedPluginsTxt_addsBethesdaMastersFromGameDataWithoutDuplicatingManagedPlugins() {
        File(gameDir, "Data").mkdirs()
        listOf(
            "DeadMoney.esm",
            "FalloutNV.esm",
            "UnlistedBase.esl",
            "ManagedMaster.esm",
            "UnmanagedPatch.esp",
        ).forEach { name ->
            File(gameDir, "Data/$name").writeText("plugin")
        }
        val file = File(tempDir, "plugins.txt")

        BethesdaPluginManager.updateManagedPluginsTxt(
            file = file,
            managedPlugins = listOf(
                BethesdaPlugin("ManagedMaster.esm", "managed-master", "Managed", "", enabled = true, priority = 0),
                BethesdaPlugin("Mod.esp", "mod", "Mod", "", enabled = true, priority = 1),
            ),
            game = BethesdaGame.FALLOUT_NEW_VEGAS,
            gameRootDir = gameDir,
        )

        assertEquals(
            "*FalloutNV.esm\n*DeadMoney.esm\n*UnlistedBase.esl\n*ManagedMaster.esm\n*Mod.esp\n",
            file.readText(),
        )
        assertEquals(
            "FalloutNV.esm\nDeadMoney.esm\nUnlistedBase.esl\nManagedMaster.esm\nMod.esp\n",
            File(tempDir, "loadorder.txt").readText(),
        )
    }

    @Test
    fun updateManagedPluginsTxt_writesOblivionPluginsWithoutAsteriskMarkers() {
        File(gameDir, "Data").mkdirs()
        listOf("Oblivion.esm", "DLCShiveringIsles.esp", "DLCHorseArmor.esp", "Managed.esp", "Disabled.esp").forEach { name ->
            File(gameDir, "Data/$name").writeText("plugin")
        }
        val file = File(tempDir, "Oblivion/plugins.txt").apply {
            parentFile?.mkdirs()
            writeText("*DLCHorseArmor.esp\nExternal.esp\n")
        }

        BethesdaPluginManager.updateManagedPluginsTxt(
            file = file,
            managedPlugins = listOf(
                BethesdaPlugin("Managed.esp", "managed", "Managed", "", enabled = true, priority = 0),
                BethesdaPlugin("Disabled.esp", "disabled", "Disabled", "", enabled = false, priority = 1),
            ),
            game = BethesdaGame.OBLIVION,
            gameRootDir = gameDir,
        )

        assertEquals(
            "Oblivion.esm\nDLCShiveringIsles.esp\nDLCHorseArmor.esp\nExternal.esp\nManaged.esp\n",
            file.readText(),
        )
        assertEquals(
            "Oblivion.esm\nDLCShiveringIsles.esp\nDLCHorseArmor.esp\nExternal.esp\nManaged.esp\n",
            File(file.parentFile, "loadorder.txt").readText(),
        )
    }

    @Test
    fun readPluginMasters_readsTes4MastFields() {
        val plugin = File(tempDir, "Dependent.esp").apply {
            writeBytes(pluginBytes("Skyrim.esm", "Update.esm", "Master.esp"))
        }

        assertEquals(
            listOf("Skyrim.esm", "Update.esm", "Master.esp"),
            BethesdaPluginManager.readPluginMasters(plugin),
        )
    }

    @Test
    fun diagnosePluginMasters_reportsMissingAndDisabledMasters() = runBlocking {
        val dataDir = File(gameDir, "Data").apply { mkdirs() }
        File(dataDir, "Skyrim.esm").writeText("base")
        File(dataDir, "Master.esp").writeBytes(pluginBytes("Skyrim.esm"))
        val dependent = File(dataDir, "Dependent.esp").apply {
            writeBytes(pluginBytes("Skyrim.esm", "Master.esp", "Missing.esm"))
        }
        val pluginsTxt = File(tempDir, "plugins.txt").apply {
            writeText("*Skyrim.esm\nMaster.esp\n*Dependent.esp\n")
        }

        val issues = BethesdaPluginManager.diagnosePluginMasters(
            managedPlugins = listOf(
                BethesdaPlugin(
                    fileName = "Dependent.esp",
                    installId = "dependent",
                    modName = "Dependent",
                    deployedPath = dependent.absolutePath,
                    enabled = true,
                    priority = 0,
                ),
            ),
            game = BethesdaGame.SKYRIM_SPECIAL_EDITION,
            gameRootDir = gameDir,
            pluginsFile = pluginsTxt,
        )

        assertEquals(1, issues.size)
        assertEquals(listOf("Missing.esm"), issues.single().missingMasters)
        assertEquals(listOf("Master.esp"), issues.single().disabledMasters)
    }

    @Test
    fun diagnosePluginMasters_treatsPresentBethesdaBaseMastersAsEnabled() = runBlocking {
        val dataDir = File(gameDir, "Data").apply { mkdirs() }
        File(dataDir, "FalloutNV.esm").writeText("base")
        val dependent = File(dataDir, "Dependent.esp").apply {
            writeBytes(pluginBytes("FalloutNV.esm"))
        }
        val pluginsTxt = File(tempDir, "plugins.txt").apply {
            writeText("*Dependent.esp\n")
        }

        val issues = BethesdaPluginManager.diagnosePluginMasters(
            managedPlugins = listOf(
                BethesdaPlugin(
                    fileName = "Dependent.esp",
                    installId = "dependent",
                    modName = "Dependent",
                    deployedPath = dependent.absolutePath,
                    enabled = true,
                    priority = 0,
                ),
            ),
            game = BethesdaGame.FALLOUT_NEW_VEGAS,
            gameRootDir = gameDir,
            pluginsFile = pluginsTxt,
        )

        assertTrue(issues.isEmpty())
    }

    @Test
    fun diagnosePluginMasters_ignoresDisabledDependentPlugins() = runBlocking {
        val dataDir = File(gameDir, "Data").apply { mkdirs() }
        val dependent = File(dataDir, "Dependent.esp").apply {
            writeBytes(pluginBytes("Missing.esm"))
        }
        val pluginsTxt = File(tempDir, "plugins.txt").apply {
            writeText("Dependent.esp\n")
        }

        val issues = BethesdaPluginManager.diagnosePluginMasters(
            managedPlugins = listOf(
                BethesdaPlugin(
                    fileName = "Dependent.esp",
                    installId = "dependent",
                    modName = "Dependent",
                    deployedPath = dependent.absolutePath,
                    enabled = false,
                    priority = 0,
                ),
            ),
            game = BethesdaGame.SKYRIM_SPECIAL_EDITION,
            gameRootDir = gameDir,
            pluginsFile = pluginsTxt,
        )

        assertTrue(issues.isEmpty())
    }

    @Test
    fun diagnosePluginMasters_reportsLateMasters() = runBlocking {
        val dataDir = File(gameDir, "Data").apply { mkdirs() }
        File(dataDir, "Skyrim.esm").writeText("base")
        val master = File(dataDir, "Master.esp").apply {
            writeBytes(pluginBytes("Skyrim.esm"))
        }
        val dependent = File(dataDir, "Dependent.esp").apply {
            writeBytes(pluginBytes("Master.esp"))
        }
        val pluginsTxt = File(tempDir, "plugins.txt").apply {
            writeText("*Dependent.esp\n*Master.esp\n")
        }

        val issues = BethesdaPluginManager.diagnosePluginMasters(
            managedPlugins = listOf(
                BethesdaPlugin(
                    fileName = "Dependent.esp",
                    installId = "dependent",
                    modName = "Dependent",
                    deployedPath = dependent.absolutePath,
                    enabled = true,
                    priority = 0,
                ),
                BethesdaPlugin(
                    fileName = "Master.esp",
                    installId = "master",
                    modName = "Master",
                    deployedPath = master.absolutePath,
                    enabled = true,
                    priority = 1,
                ),
            ),
            game = BethesdaGame.SKYRIM_SPECIAL_EDITION,
            gameRootDir = gameDir,
            pluginsFile = pluginsTxt,
        )

        assertEquals(listOf("Master.esp"), issues.single().lateMasters)
    }

    @Test
    fun diagnosePluginAssets_reportsMissingPluginSidecars() = runBlocking {
        val extracted = File(tempDir, "extracted").apply { mkdirs() }
        val sourcePlugin = File(extracted, "Alternate Start.esp").apply { writeText("plugin") }
        File(extracted, "Alternate Start.bsa").writeText("assets")
        File(extracted, "Alternate Start - Textures.bsa").writeText("textures")
        File(extracted, "Other.bsa").writeText("other")
        val dataDir = File(gameDir, "Data").apply { mkdirs() }
        val targetPlugin = File(dataDir, "Alternate Start.esp").apply { writeText("plugin") }
        File(dataDir, "Alternate Start.bsa").writeText("assets")

        val issues = BethesdaPluginManager.diagnosePluginAssets(
            listOf(
                BethesdaPlugin(
                    fileName = "Alternate Start.esp",
                    installId = "alternate",
                    modName = "Alternate Start",
                    deployedPath = targetPlugin.absolutePath,
                    enabled = true,
                    priority = 0,
                    sourcePath = sourcePlugin.absolutePath,
                ),
            ),
        )

        assertEquals(1, issues.size)
        assertEquals(listOf("Alternate Start - Textures.bsa"), issues.single().missingFiles)
    }

    @Test
    fun diagnosePluginAssets_ignoresDisabledPlugins() = runBlocking {
        val extracted = File(tempDir, "extracted").apply { mkdirs() }
        val sourcePlugin = File(extracted, "Disabled.esp").apply { writeText("plugin") }
        File(extracted, "Disabled.bsa").writeText("assets")

        val issues = BethesdaPluginManager.diagnosePluginAssets(
            listOf(
                BethesdaPlugin(
                    fileName = "Disabled.esp",
                    installId = "disabled",
                    modName = "Disabled",
                    deployedPath = File(gameDir, "Data/Disabled.esp").absolutePath,
                    enabled = false,
                    priority = 0,
                    sourcePath = sourcePlugin.absolutePath,
                ),
            ),
        )

        assertTrue(issues.isEmpty())
    }

    private fun install(): ModInstall {
        val extracted = File(tempDir, "extracted").apply { mkdirs() }
        return ModInstall(
            installId = "install",
            appId = "APP",
            nexusGameDomain = "game",
            nexusModId = 1,
            nexusFileId = 2,
            modName = "Plugin Mod",
            fileName = "plugin.zip",
            archivePath = File(tempDir, "plugin.zip").absolutePath,
            extractedPath = extracted.absolutePath,
            status = ModInstallStatus.APPLIED.name,
        )
    }

    private fun recipe(installId: String): ModPlacementRecipe =
        ModPlacementRecipe(
            installId = installId,
            sourceSubpath = "Data",
            targetRoot = ModTargetRoot.GAME_DIR.name,
            targetRelativePath = "Data",
            mode = ModPlacementMode.OVERWRITE_COPY.name,
        )

    private fun pluginBytes(vararg masters: String): ByteArray {
        val fields = ByteArrayOutputStream()
        fields.write(field("HEDR", ByteArray(12)))
        masters.forEach { master ->
            fields.write(field("MAST", (master + "\u0000").toByteArray(Charsets.UTF_8)))
            fields.write(field("DATA", ByteArray(8)))
        }
        val data = fields.toByteArray()
        return ByteArrayOutputStream().apply {
            write("TES4".toByteArray(Charsets.US_ASCII))
            writeUInt32(data.size)
            writeUInt32(0)
            writeUInt32(0)
            writeUInt32(0)
            writeUInt16(44)
            writeUInt16(0)
            write(data)
        }.toByteArray()
    }

    private fun field(type: String, data: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(type.toByteArray(Charsets.US_ASCII))
            writeUInt16(data.size)
            write(data)
        }.toByteArray()

    private fun ByteArrayOutputStream.writeUInt16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
