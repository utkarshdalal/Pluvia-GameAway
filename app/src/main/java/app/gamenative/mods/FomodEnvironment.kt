package app.gamenative.mods

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

enum class NativeBinaryArchitecture {
    X86,
    X64,
    ARM64,
    UNKNOWN,
}

data class ScriptExtenderFact(
    val id: String,
    val present: Boolean,
    val version: String? = null,
    val path: String = "",
)

data class FomodEnvironmentSnapshot(
    val gameName: String = "",
    val gameVersion: String? = null,
    val fileFacts: Map<String, Boolean> = emptyMap(),
    val pluginStateKnown: Boolean = false,
    val presentPlugins: Set<String> = emptySet(),
    val activePlugins: Set<String> = emptySet(),
    val pluginMasters: Map<String, List<String>> = emptyMap(),
    val scriptExtenders: Map<String, ScriptExtenderFact> = emptyMap(),
    val nativeDllArchitectures: Map<String, NativeBinaryArchitecture> = emptyMap(),
    val unsupportedRequirements: List<String> = emptyList(),
) {
    fun evaluate(dependency: FomodFileDependency): FomodFactState {
        val key = dependency.file.normalizedFactKey()
        val present = fileFacts[key] ?: return FomodFactState.UNKNOWN
        val pluginName = dependency.file.substringAfterLast('/').lowercase(Locale.ROOT)
        val isPlugin = pluginName.substringAfterLast('.', "") in setOf("esp", "esm", "esl")
        val active = pluginName in activePlugins
        return requiredState(dependency.state, present, active, isPlugin)
    }

    fun evaluate(dependency: FomodPluginDependency): FomodFactState {
        val key = dependency.plugin.substringAfterLast('/').lowercase(Locale.ROOT)
        val present = when {
            key in activePlugins -> true
            key in presentPlugins -> true
            pluginStateKnown -> false
            else -> return FomodFactState.UNKNOWN
        }
        return requiredState(dependency.state, present, key in activePlugins, isPlugin = true)
    }

    fun evaluate(dependency: FomodGameDependency): FomodFactState {
        val current = gameVersion ?: return FomodFactState.UNKNOWN
        return if (compareVersions(current, dependency.version) >= 0) FomodFactState.TRUE else FomodFactState.FALSE
    }

    private fun requiredState(
        state: FomodRequiredFileState,
        present: Boolean,
        active: Boolean,
        isPlugin: Boolean,
    ): FomodFactState =
        when (state) {
            FomodRequiredFileState.ACTIVE -> if (present && (!isPlugin || active)) FomodFactState.TRUE else FomodFactState.FALSE
            FomodRequiredFileState.INACTIVE -> if (present && isPlugin && !active) FomodFactState.TRUE else FomodFactState.FALSE
            FomodRequiredFileState.MISSING -> if (!present) FomodFactState.TRUE else FomodFactState.FALSE
        }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.versionParts()
        val rightParts = right.versionParts()
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun String.versionParts(): List<Int> =
        split(Regex("[^0-9]+")).filter(String::isNotBlank).mapNotNull(String::toIntOrNull)
}

object FomodEnvironmentSnapshotBuilder {
    fun build(
        installer: FomodInstaller,
        gameName: String,
        gameRootDir: File?,
        pluginsFile: File? = null,
        gameVersion: String? = null,
    ): FomodEnvironmentSnapshot {
        val requestedFiles = installer.dependencyExpressions()
            .flatMap { it.fileDependencies }
            .map { it.file }
            .distinctBy { it.normalizedFactKey() }
        val requestedPlugins = installer.dependencyExpressions()
            .flatMap { it.pluginDependencies }
            .map { it.plugin }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val fileFacts = if (gameRootDir == null) {
            emptyMap()
        } else {
            requestedFiles.associate { requested ->
                requested.normalizedFactKey() to resolveRequestedFile(gameRootDir, requested).isFile
            }
        }
        val presentPlugins = (requestedFiles + requestedPlugins).asSequence()
            .filter { it.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("esp", "esm", "esl") }
            .filter { requested ->
                fileFacts[requested.normalizedFactKey()] == true ||
                    (gameRootDir != null && resolveRequestedFile(gameRootDir, requested).isFile)
            }
            .mapTo(mutableSetOf()) { it.substringAfterLast('/').lowercase(Locale.ROOT) }
        val pluginLines = pluginsFile?.takeIf(File::isFile)?.readLines().orEmpty()
            .map { it.substringBefore('#').trim() }
            .filter(String::isNotBlank)
        val usesEnabledMarkers = pluginLines.any { it.startsWith('*') }
        val activePlugins = pluginLines.asSequence()
            .filter { !usesEnabledMarkers || it.startsWith('*') }
            .map { it.removePrefix("*").trim().lowercase(Locale.ROOT) }
            .filterTo(mutableSetOf(), String::isNotBlank)
        val pluginMasters = presentPlugins.associateWith { plugin ->
            val file = resolveRequestedFile(gameRootDir, plugin)
            if (file.isFile) BethesdaPluginManager.readPluginMasters(file) else emptyList()
        }
        val scriptExtenders = discoverScriptExtenders(gameRootDir)
        val nativeDllArchitectures = requestedFiles.asSequence()
            .filter { it.endsWith(".dll", ignoreCase = true) }
            .mapNotNull { requested ->
                resolveRequestedFile(gameRootDir, requested).takeIf(File::isFile)?.let { file ->
                    requested.normalizedFactKey() to readPeArchitecture(file)
                }
            }
            .toMap()
        return FomodEnvironmentSnapshot(
            gameName = gameName,
            gameVersion = gameVersion,
            fileFacts = fileFacts,
            pluginStateKnown = gameRootDir != null,
            presentPlugins = presentPlugins,
            activePlugins = activePlugins,
            pluginMasters = pluginMasters,
            scriptExtenders = scriptExtenders,
            nativeDllArchitectures = nativeDllArchitectures,
            unsupportedRequirements = installer.unsupportedWarnings,
        )
    }

    private fun resolveRequestedFile(gameRootDir: File?, requested: String): File {
        val root = gameRootDir ?: return File("")
        val relative = normalizeArchiveDisplayPath(requested)
        return listOf(relative, "Data/$relative")
            .mapNotNull { candidate -> ModTargetResolver.resolveWithin(root, candidate) }
            .firstOrNull { it.exists() }
            ?: File(root, relative)
    }

    private fun discoverScriptExtenders(gameRootDir: File?): Map<String, ScriptExtenderFact> {
        val root = gameRootDir ?: return emptyMap()
        val definitions = mapOf(
            "skse" to listOf("skse_loader.exe", "skse64_loader.exe", "Data/SKSE"),
            "f4se" to listOf("f4se_loader.exe", "Data/F4SE"),
            "sfse" to listOf("sfse_loader.exe", "Data/SFSE"),
            "nvse" to listOf("nvse_loader.exe", "Data/NVSE"),
            "obse" to listOf("obse_loader.exe", "Data/OBSE"),
        )
        return definitions.mapValues { (id, candidates) ->
            val present = candidates.mapNotNull { ModTargetResolver.resolveWithin(root, it) }.firstOrNull(File::exists)
            val version = root.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.name.startsWith(id, ignoreCase = true) && it.extension.equals("dll", true) }
                .mapNotNull { file ->
                    Regex("(?:^|_)(\\d+)[_.-](\\d+)[_.-](\\d+)(?:[_.-](\\d+))?", RegexOption.IGNORE_CASE)
                        .find(file.nameWithoutExtension)
                        ?.groupValues
                        ?.drop(1)
                        ?.filter(String::isNotBlank)
                        ?.map { part -> part.toIntOrNull()?.toString() ?: part }
                        ?.joinToString(".")
                }
                .firstOrNull()
            ScriptExtenderFact(id, present != null, version, present?.absolutePath.orEmpty())
        }
    }

    internal fun readPeArchitecture(file: File): NativeBinaryArchitecture = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 64L || input.readUnsignedShort() != 0x4d5a) return@use NativeBinaryArchitecture.UNKNOWN
            input.seek(0x3c)
            val peOffset = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            if (peOffset + 6 > input.length()) return@use NativeBinaryArchitecture.UNKNOWN
            input.seek(peOffset)
            if (Integer.reverseBytes(input.readInt()) != 0x00004550) return@use NativeBinaryArchitecture.UNKNOWN
            when (java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))) {
                0x014c -> NativeBinaryArchitecture.X86
                0x8664 -> NativeBinaryArchitecture.X64
                0xaa64 -> NativeBinaryArchitecture.ARM64
                else -> NativeBinaryArchitecture.UNKNOWN
            }
        }
    }.getOrDefault(NativeBinaryArchitecture.UNKNOWN)
}

private fun FomodInstaller.dependencyExpressions(): List<FomodDependencyExpression> =
    listOf(moduleDependencies) + conditionalFileInstalls.map { it.dependencies } +
        steps.flatMap { it.groups }.flatMap { it.plugins }.flatMap { it.typePatterns }.map { it.dependencies }

private fun String.normalizedFactKey(): String =
    normalizeArchiveDisplayPath(this).lowercase(Locale.ROOT)
