package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

enum class BethesdaGame(
    val displayName: String,
    val localAppDataDir: String,
    val dataDirName: String = "Data",
) {
    MORROWIND("Morrowind", "Morrowind"),
    OBLIVION("Oblivion", "Oblivion"),
    FALLOUT3("Fallout 3", "Fallout3"),
    FALLOUT_NEW_VEGAS("Fallout New Vegas", "FalloutNV"),
    SKYRIM("Skyrim", "Skyrim"),
    SKYRIM_SPECIAL_EDITION("Skyrim Special Edition", "Skyrim Special Edition"),
    SKYRIM_VR("Skyrim VR", "Skyrim VR"),
    FALLOUT4("Fallout 4", "Fallout4"),
    FALLOUT4_VR("Fallout 4 VR", "Fallout4VR"),
    STARFIELD("Starfield", "Starfield"),
}

data class BethesdaPlugin(
    val fileName: String,
    val installId: String?,
    val modName: String?,
    val deployedPath: String,
    val enabled: Boolean,
    val priority: Int,
    val orderIndex: Int = Int.MAX_VALUE,
    val sourcePath: String = "",
)

data class BethesdaPluginDependencyIssue(
    val plugin: BethesdaPlugin,
    val masters: List<String>,
    val missingMasters: List<String>,
    val disabledMasters: List<String>,
    val lateMasters: List<String> = emptyList(),
)

data class BethesdaPluginAssetIssue(
    val plugin: BethesdaPlugin,
    val missingFiles: List<String>,
)

object BethesdaPluginManager {
    private val pluginExtensions = setOf("esm", "esp", "esl")
    private val sidecarExtensions = setOf("bsa", "bsl", "ba2", "ini")
    private const val MAX_TES4_HEADER_BYTES = 4 * 1024 * 1024

    fun detectGame(gameName: String): BethesdaGame? {
        val normalized = gameName.lowercase()
        return when {
            "skyrim vr" in normalized -> BethesdaGame.SKYRIM_VR
            "skyrim special edition" in normalized || "skyrim se" in normalized -> BethesdaGame.SKYRIM_SPECIAL_EDITION
            "skyrim" in normalized -> BethesdaGame.SKYRIM
            "fallout 4 vr" in normalized -> BethesdaGame.FALLOUT4_VR
            "fallout 4" in normalized -> BethesdaGame.FALLOUT4
            "new vegas" in normalized || "fallout nv" in normalized -> BethesdaGame.FALLOUT_NEW_VEGAS
            "fallout 3" in normalized -> BethesdaGame.FALLOUT3
            "oblivion" in normalized -> BethesdaGame.OBLIVION
            "morrowind" in normalized -> BethesdaGame.MORROWIND
            "starfield" in normalized -> BethesdaGame.STARFIELD
            else -> null
        }
    }

    suspend fun detectPlugins(
        installs: List<ModInstall>,
        recipesByInstallId: Map<String, List<ModPlacementRecipe>>,
        prioritiesByInstallId: Map<String, Int>,
        gameRootDir: File?,
        winePrefix: String,
        pluginsFile: File?,
        ownershipByInstallId: Map<String, ModOwnershipManifest> = emptyMap(),
        defaultEnabled: Boolean = false,
    ): List<BethesdaPlugin> = withContext(Dispatchers.IO) {
        val game = gameFromPluginsFile(pluginsFile)
        val existingOrder = pluginsFile?.let { readPluginEntries(it, game) }.orEmpty()
        val existingByPlugin = existingOrder.associateBy { it.fileName.lowercase() }
        val orderByPlugin = existingOrder
            .mapIndexed { index, entry -> entry.fileName.lowercase() to index }
            .toMap()
        installs.filter { it.status == ModInstallStatus.APPLIED.name }.flatMap { install ->
            val recipes = recipesByInstallId[install.installId].orEmpty()
            val ownership = ownershipByInstallId[install.installId]
                ?.takeIf { it.state == ModOwnershipState.ACTIVE }
            runCatching {
                val pluginFiles = if (ownership != null) {
                    ownership.files
                        .asSequence()
                        .filter { it.active && File(it.targetPath).extension.lowercase() in pluginExtensions }
                        .map { file -> File(install.extractedPath, file.sourceRelativePath) to File(file.targetPath) }
                        .toList()
                } else {
                    val plan = ModMaterializer.materializationPlan(
                        install,
                        recipes,
                        gameRootDir,
                        winePrefix,
                        captureTargetHashes = false,
                    )
                    check(plan.isComplete) { plan.errors.values.joinToString() }
                    plan.files
                        .filter { file -> file.target.extension.lowercase() in pluginExtensions }
                        .map { file -> file.source to file.target }
                }
                pluginFiles.map { (source, target) ->
                    BethesdaPlugin(
                        fileName = target.name,
                        installId = install.installId,
                        modName = install.modName,
                        deployedPath = target.absolutePath,
                        enabled = existingByPlugin[target.name.lowercase()]?.enabled ?: defaultEnabled,
                        priority = prioritiesByInstallId[install.installId] ?: 0,
                        orderIndex = orderByPlugin[target.name.lowercase()] ?: Int.MAX_VALUE,
                        sourcePath = source.absolutePath,
                    )
                }
            }.getOrElse { error ->
                Timber.w(error, "Skipping Bethesda plugin detection for Nexus install %s", install.installId)
                emptyList()
            }
        }
            .distinctBy { it.fileName.lowercase() to it.installId }
            .sortedWith(
                compareBy<BethesdaPlugin> { it.orderIndex }
                    .thenBy { pluginTypeRank(it.fileName) }
                    .thenBy { it.priority }
                    .thenBy { it.fileName.lowercase() },
            )
    }

    suspend fun diagnosePluginMasters(
        managedPlugins: List<BethesdaPlugin>,
        game: BethesdaGame?,
        gameRootDir: File?,
        pluginsFile: File?,
    ): List<BethesdaPluginDependencyIssue> = withContext(Dispatchers.IO) {
        val pluginEntries = pluginsFile?.let { readPluginEntries(it, game) }.orEmpty()
        val managedNames = managedPlugins.map { it.fileName.lowercase() }.toSet()
        val basePlugins = gameRootDir?.let { baseGamePlugins(game, it, managedNames) }.orEmpty()
        val gamePlugins = gameRootDir?.let { pluginFilesInGameData(game, it) }.orEmpty()
        val enabledNames = (
            pluginEntries.filter { it.enabled }.map { it.fileName } +
                basePlugins +
                managedPlugins.filter { it.enabled }.map { it.fileName }
            ).map { it.lowercase() }.toSet()
        val availableNames = (
            pluginEntries.map { it.fileName } +
                basePlugins +
                gamePlugins.map { it.name } +
                managedPlugins.map { it.fileName }
            ).map { it.lowercase() }.toSet()
        val orderByName = pluginEntries
            .mapIndexed { index, entry -> entry.fileName.lowercase() to index }
            .toMap()
        val fallbackOrderByName = managedPlugins
            .mapIndexed { index, plugin -> plugin.fileName.lowercase() to index }
            .toMap()

        managedPlugins
            .filter { it.enabled }
            .mapNotNull { plugin ->
                val masters = readPluginMasters(plugin.pluginSourceFile())
                if (masters.isEmpty()) return@mapNotNull null
                val missing = masters.filter { it.lowercase() !in availableNames }
                val disabled = masters.filter { master ->
                    val key = master.lowercase()
                    key in availableNames && key !in enabledNames
                }
                val pluginOrder = orderByName[plugin.fileName.lowercase()] ?: fallbackOrderByName[plugin.fileName.lowercase()] ?: Int.MAX_VALUE
                val late = masters.filter { master ->
                    val masterOrder = orderByName[master.lowercase()] ?: fallbackOrderByName[master.lowercase()] ?: return@filter false
                    masterOrder > pluginOrder
                }
                if (missing.isEmpty() && disabled.isEmpty() && late.isEmpty()) {
                    null
                } else {
                    BethesdaPluginDependencyIssue(
                        plugin = plugin,
                        masters = masters,
                        missingMasters = missing,
                        disabledMasters = disabled,
                        lateMasters = late,
                    )
                }
            }
    }

    suspend fun diagnosePluginAssets(
        managedPlugins: List<BethesdaPlugin>,
    ): List<BethesdaPluginAssetIssue> = withContext(Dispatchers.IO) {
        managedPlugins
            .filter { it.enabled }
            .mapNotNull { plugin ->
                val sourcePlugin = plugin.pluginSourceFile()
                val targetPlugin = File(plugin.deployedPath)
                val missingFiles = buildList {
                    if (!targetPlugin.isFile) add(plugin.fileName)
                    sourcePluginSidecars(sourcePlugin).forEach { sourceSidecar ->
                        val expectedTarget = ModTargetResolver.resolveWithin(
                            targetPlugin.parentFile ?: return@forEach,
                            sourceSidecar.name,
                        ) ?: return@forEach
                        if (!expectedTarget.isFile) add(sourceSidecar.name)
                    }
                }
                if (missingFiles.isEmpty()) {
                    null
                } else {
                    BethesdaPluginAssetIssue(plugin, missingFiles.distinctBy { it.lowercase() })
                }
            }
    }

    fun readPluginMasters(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.inputStream().use { input ->
                val recordHeader = ByteArray(24)
                if (!input.readFully(recordHeader)) return emptyList()
                if (recordHeader.asAscii(0, 4) != "TES4") return emptyList()
                val recordSize = recordHeader.uint32Le(4)
                    .coerceAtMost(MAX_TES4_HEADER_BYTES.toLong())
                    .toInt()
                if (recordSize <= 0) return emptyList()
                val recordData = ByteArray(recordSize)
                if (!input.readFully(recordData)) return emptyList()
                readTes4MasterFields(recordData)
            }
        }.getOrDefault(emptyList())
    }

    fun pluginsFile(winePrefix: String, game: BethesdaGame): File? {
        if (winePrefix.isBlank()) return null
        val userHome = ModContainerResolver.getWineUserHome(winePrefix)
        return File(userHome, "AppData/Local/${game.localAppDataDir}/plugins.txt")
    }

    fun updateManagedPluginsTxt(
        file: File,
        managedPlugins: List<BethesdaPlugin>,
        game: BethesdaGame? = null,
        gameRootDir: File? = null,
    ) {
        file.parentFile?.mkdirs()
        val managedByName = managedPlugins.associateBy { it.fileName.lowercase() }
        val usesMarkers = usesAsteriskEnabledMarkers(game)
        val basePlugins = gameRootDir?.let { root -> baseGamePlugins(game, root, managedByName.keys) }.orEmpty()
        val baseByName = basePlugins.map { it.lowercase() }.toSet()
        val retainedLines = pluginFileVariants(file)
            .flatMap { existingFile -> if (existingFile.isFile) existingFile.readLines() else emptyList() }
            .filter { line ->
                val entry = parsePluginLine(line, game) ?: return@filter true
                entry.fileName.lowercase() !in managedByName && entry.fileName.lowercase() !in baseByName
            }
            .map { if (usesMarkers) it else normalizePluginLineForNoMarkerGame(it) }
            .distinctPluginLines(game)
        val baseLines = basePlugins.map { enabledPluginLine(it, usesMarkers) }
        val managedLines = managedPlugins
            .distinctBy { it.fileName.lowercase() }
            .mapNotNull { plugin ->
                when {
                    plugin.enabled -> enabledPluginLine(plugin.fileName, usesMarkers)
                    usesMarkers -> plugin.fileName
                    else -> null
                }
            }
        val finalLines = baseLines + retainedLines + managedLines
        val pluginsText = finalLines.joinToString(separator = "\n", postfix = "\n")
        pluginFileVariants(file, includePrimary = true).forEach { it.writeText(pluginsText) }

        val loadOrderParent = file.parentFile ?: file.absoluteFile.parentFile ?: File(".")
        val loadOrderFile = File(loadOrderParent, "loadorder.txt")
        val loadOrderEntries = finalLines
            .mapNotNull { parsePluginLine(it, game) }
            .map { it.fileName }
            .distinctBy { it.lowercase() }
        val loadOrderText = loadOrderEntries.joinToString(separator = "\n", postfix = "\n")
        pluginFileVariants(loadOrderFile, includePrimary = true).forEach { it.writeText(loadOrderText) }
    }

    private data class PluginEntry(
        val fileName: String,
        val enabled: Boolean,
    )

    private fun readPluginEntries(file: File, game: BethesdaGame?): List<PluginEntry> =
        pluginFileVariants(file)
            .flatMap { existingFile -> if (existingFile.isFile) existingFile.readLines() else emptyList() }
            .mapNotNull { parsePluginLine(it, game) }
            .distinctBy { it.fileName.lowercase() }

    private fun parsePluginLine(line: String, game: BethesdaGame?): PluginEntry? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return null
        val enabled = !usesAsteriskEnabledMarkers(game) || trimmed.startsWith("*")
        val fileName = trimmed.removePrefix("*").trim()
        if (fileName.isBlank()) return null
        return PluginEntry(fileName = fileName, enabled = enabled)
    }

    private fun pluginTypeRank(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
        "esm" -> 0
        "esl" -> 1
        "esp" -> 2
        else -> 3
    }

    private fun baseGamePlugins(
        game: BethesdaGame?,
        gameRootDir: File,
        excludedPluginNames: Set<String> = emptySet(),
    ): List<String> {
        val dataDir = File(gameRootDir, game?.dataDirName ?: "Data")
        if (!dataDir.isDirectory) return emptyList()
        val excluded = excludedPluginNames.map { it.lowercase() }.toSet()
        val order = knownBasePluginOrder(game)
            .mapIndexed { index, name -> name.lowercase() to index }
            .toMap()
        val baseExtensions = setOf("esm", "esl")
        val known = knownBasePluginOrder(game)
            .filter { it.lowercase() !in excluded && File(dataDir, it).isFile }
        val scannedMasters = dataDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.lowercase() !in excluded &&
                    (file.extension.lowercase() in baseExtensions || file.name.lowercase() in order)
            }
            ?.sortedWith(
                compareBy<File> { order[it.name.lowercase()] ?: Int.MAX_VALUE }
                    .thenBy { pluginTypeRank(it.name) }
                    .thenBy { it.name.lowercase() },
            )
            ?.map { it.name }
            .orEmpty()

        return (known + scannedMasters).distinctBy { it.lowercase() }
    }

    private fun knownBasePluginOrder(game: BethesdaGame?): List<String> = when (game) {
        BethesdaGame.MORROWIND -> listOf("Morrowind.esm", "Tribunal.esm", "Bloodmoon.esm")
        BethesdaGame.OBLIVION -> listOf(
            "Oblivion.esm",
            "DLCShiveringIsles.esp",
            "Knights.esp",
            "DLCBattlehornCastle.esp",
            "DLCFrostcrag.esp",
            "DLCMehrunesRazor.esp",
            "DLCOrrery.esp",
            "DLCSpellTomes.esp",
            "DLCThievesDen.esp",
            "DLCVileLair.esp",
            "DLCHorseArmor.esp",
        )
        BethesdaGame.FALLOUT3 -> listOf(
            "Fallout3.esm",
            "Anchorage.esm",
            "ThePitt.esm",
            "BrokenSteel.esm",
            "PointLookout.esm",
            "Zeta.esm",
        )
        BethesdaGame.FALLOUT_NEW_VEGAS -> listOf(
            "FalloutNV.esm",
            "DeadMoney.esm",
            "HonestHearts.esm",
            "OldWorldBlues.esm",
            "LonesomeRoad.esm",
            "GunRunnersArsenal.esm",
            "CaravanPack.esm",
            "ClassicPack.esm",
            "MercenaryPack.esm",
            "TribalPack.esm",
        )
        BethesdaGame.SKYRIM, BethesdaGame.SKYRIM_SPECIAL_EDITION, BethesdaGame.SKYRIM_VR -> listOf(
            "Skyrim.esm",
            "Update.esm",
            "Dawnguard.esm",
            "HearthFires.esm",
            "Dragonborn.esm",
            "_ResourcePack.esl",
        )
        BethesdaGame.FALLOUT4, BethesdaGame.FALLOUT4_VR -> listOf(
            "Fallout4.esm",
            "DLCRobot.esm",
            "DLCworkshop01.esm",
            "DLCCoast.esm",
            "DLCworkshop02.esm",
            "DLCworkshop03.esm",
            "DLCNukaWorld.esm",
            "DLCUltraHighResolution.esm",
        )
        BethesdaGame.STARFIELD -> listOf(
            "Starfield.esm",
            "BlueprintShips-Starfield.esm",
            "Constellation.esm",
            "OldMars.esm",
            "ShatteredSpace.esm",
        )
        null -> emptyList()
    }

    private fun pluginFilesInGameData(game: BethesdaGame?, gameRootDir: File): List<File> {
        val dataDir = File(gameRootDir, game?.dataDirName ?: "Data")
        if (!dataDir.isDirectory) return emptyList()
        return dataDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in pluginExtensions }
            .orEmpty()
    }

    private fun BethesdaPlugin.pluginSourceFile(): File {
        val source = sourcePath.takeIf { it.isNotBlank() }?.let(::File)
        if (source?.isFile == true) return source
        return File(deployedPath)
    }

    private fun sourcePluginSidecars(plugin: File): List<File> {
        if (!plugin.isFile || plugin.extension.lowercase() !in pluginExtensions) return emptyList()
        val base = plugin.nameWithoutExtension.lowercase()
        return plugin.parentFile
            ?.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file != plugin &&
                    file.extension.lowercase() in sidecarExtensions &&
                    file.nameWithoutExtension.lowercase().let { it == base || it.startsWith("$base - ") }
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    private fun readTes4MasterFields(recordData: ByteArray): List<String> {
        val masters = mutableListOf<String>()
        var offset = 0
        var extendedFieldSize: Int? = null
        while (offset + 6 <= recordData.size) {
            val type = recordData.asAscii(offset, 4)
            val declaredSize = recordData.uint16Le(offset + 4)
            offset += 6
            val fieldSize = extendedFieldSize ?: declaredSize
            extendedFieldSize = null
            if (fieldSize < 0 || offset + fieldSize > recordData.size) break
            when (type) {
                "XXXX" -> {
                    if (fieldSize >= 4) {
                        extendedFieldSize = recordData.uint32Le(offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    }
                }
                "MAST" -> {
                    val raw = recordData.copyOfRange(offset, offset + fieldSize)
                    val nulIndex = raw.indexOf(0)
                    val nameBytes = if (nulIndex >= 0) raw.copyOf(nulIndex) else raw
                    val master = nameBytes.toString(Charsets.UTF_8).trim()
                    if (master.isNotBlank()) masters += master
                }
            }
            offset += fieldSize
        }
        return masters.distinctBy { it.lowercase() }
    }

    private fun java.io.InputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.uint16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.uint32Le(offset: Int): Long =
        ((this[offset].toLong() and 0xffL)) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun pluginFileVariants(file: File, includePrimary: Boolean = false): List<File> {
        val parent = file.parentFile ?: return listOf(file)
        val variants = parent.listFiles()
            ?.filter { it.name.equals(file.name, ignoreCase = true) }
            .orEmpty()
        return ((if (includePrimary) listOf(file) else emptyList()) + variants)
            .distinctBy { it.absolutePath }
    }

    private fun usesAsteriskEnabledMarkers(game: BethesdaGame?): Boolean =
        game != BethesdaGame.OBLIVION && game != BethesdaGame.MORROWIND

    private fun gameFromPluginsFile(file: File?): BethesdaGame? {
        val localAppDataDir = file?.parentFile?.name ?: return null
        return BethesdaGame.entries.firstOrNull { it.localAppDataDir.equals(localAppDataDir, ignoreCase = true) }
    }

    private fun enabledPluginLine(fileName: String, usesMarkers: Boolean): String =
        if (usesMarkers) "*$fileName" else fileName

    private fun normalizePluginLineForNoMarkerGame(line: String): String =
        parsePluginLine(line, BethesdaGame.OBLIVION)?.fileName ?: line

    private fun List<String>.distinctPluginLines(game: BethesdaGame?): List<String> {
        val seenPlugins = mutableSetOf<String>()
        val seenRaw = mutableSetOf<String>()
        return filter { line ->
            val entry = parsePluginLine(line, game)
            if (entry == null) {
                seenRaw.add(line)
            } else {
                seenPlugins.add(entry.fileName.lowercase())
            }
        }
    }
}
