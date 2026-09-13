package app.gamenative.mods

import app.gamenative.data.ModTargetRoot
import java.io.File

data class ResolvedModTargetRoot(
    val type: ModTargetRoot,
    val label: String,
    val dir: File,
)

data class ModTargetPlanInspection(
    val caseMerges: List<String>,
    val ambiguousPaths: List<String>,
)

object ModTargetResolver {
    fun normalizeRelativePath(path: String): String =
        path.trim().replace('\\', '/').trim('/')

    fun normalizedTargetKey(targetRoot: String, targetRelativePath: String): String? =
        if (targetRoot == ModTargetRoot.CUSTOM_ABSOLUTE.name) {
            WindowsPathIdentity.absoluteKey(File(targetRelativePath.trim().replace('\\', '/')))
        } else {
            WindowsPathIdentity.targetKey(targetRoot, targetRelativePath)
        }

    fun roots(gameRootDir: File?, winePrefix: String): List<ResolvedModTargetRoot> {
        val result = mutableListOf<ResolvedModTargetRoot>()
        if (gameRootDir?.isDirectory == true) {
            result += ResolvedModTargetRoot(ModTargetRoot.GAME_DIR, "Game Directory", gameRootDir)
        }
        if (winePrefix.isNotBlank()) {
            val driveC = File(winePrefix, "drive_c")
            if (driveC.isDirectory) {
                result += ResolvedModTargetRoot(ModTargetRoot.WINE_C, "C: Drive", driveC)
                val userHome = ModContainerResolver.getWineUserHome(winePrefix)
                result += ResolvedModTargetRoot(ModTargetRoot.DOCUMENTS, "My Documents", File(userHome, "Documents"))
                result += ResolvedModTargetRoot(ModTargetRoot.MY_GAMES, "My Games", File(userHome, "Documents/My Games"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_ROAMING, "AppData / Roaming", File(userHome, "AppData/Roaming"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCAL, "AppData / Local", File(userHome, "AppData/Local"))
                result += ResolvedModTargetRoot(ModTargetRoot.APPDATA_LOCALLOW, "AppData / LocalLow", File(userHome, "AppData/LocalLow"))
            }
        }
        return result
    }

    fun resolve(
        targetRoot: String,
        targetRelativePath: String,
        gameRootDir: File?,
        winePrefix: String,
    ): File? = session(gameRootDir, winePrefix).resolve(targetRoot, targetRelativePath)

    fun session(gameRootDir: File?, winePrefix: String): ModTargetResolutionSession =
        ModTargetResolutionSession(roots(gameRootDir, winePrefix))

    fun resolveWithin(root: File, relativePath: String): File? {
        val rootCanonical = root.safeCanonicalFile() ?: return null
        val resolution = WindowsTargetNamespace(rootCanonical).resolve(relativePath)
        val target = resolution.takeIf { it.isValid }?.file ?: return null
        return target.takeIf { it.isInsideOrEqual(rootCanonical) }
    }

    fun inspectPlan(
        plan: ModInstallPlan,
        resolvedRoots: List<ResolvedModTargetRoot>,
    ): ModTargetPlanInspection {
        val caseMerges = mutableSetOf<String>()
        val ambiguities = mutableSetOf<String>()
        val namespaces = resolvedRoots.associate { it.type.name to WindowsTargetNamespace(it.dir) }
        plan.files.filter { it.status == PlannedFileStatus.PLACED }.forEach { file ->
            val relative = file.targetRelativePath ?: return@forEach
            val namespace = namespaces[file.targetRoot] ?: return@forEach
            val resolution = namespace.resolve(relative)
            caseMerges += resolution.caseMerges
            if (resolution.ambiguousSegments.isNotEmpty()) ambiguities += relative
        }
        return ModTargetPlanInspection(caseMerges.sorted(), ambiguities.sorted())
    }

    private fun File.safeCanonicalFile(): File? =
        runCatching { canonicalFile }.getOrNull()

    private fun File.isInsideOrEqual(root: File): Boolean {
        if (this == root) return true
        val rootPath = root.path
        if (rootPath == File.separator) return path.startsWith(rootPath)
        return path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }
}

/** Reuses one Windows-style namespace for every target in a reviewed plan. */
class ModTargetResolutionSession internal constructor(
    resolvedRoots: List<ResolvedModTargetRoot>,
) {
    private val rootsByType = resolvedRoots.mapNotNull { root ->
        root.dir.safeCanonicalFile()?.let { canonical -> root.type to canonical }
    }.toMap()
    private val namespaces = rootsByType.mapValues { (_, root) -> WindowsTargetNamespace(root) }

    fun resolve(targetRoot: String, targetRelativePath: String): File? {
        val rootType = runCatching { ModTargetRoot.valueOf(targetRoot) }.getOrNull() ?: return null
        if (rootType == ModTargetRoot.CUSTOM_ABSOLUTE) return resolveCustom(targetRelativePath)
        val root = rootsByType[rootType] ?: return null
        val relative = ModTargetResolver.normalizeRelativePath(targetRelativePath)
        if (WindowsPathIdentity.relativeSegments(relative) == null) return null
        return namespaces.getValue(rootType).resolve(relative).takeIf { it.isValid }?.file
            ?.takeIf { it.isInsideOrEqual(root) }
    }

    private fun resolveCustom(path: String): File? {
        val raw = File(path.trim().replace('\\', '/'))
        if (!raw.isAbsolute) return null
        val candidate = raw.safeCanonicalFile() ?: return null
        val matchingRoot = rootsByType.entries
            .filter { (_, root) -> candidate.isInsideOrEqual(root) }
            .maxByOrNull { (_, root) -> root.path.length }
            ?: return null
        val relative = candidate.relativeToOrNull(matchingRoot.value)?.path.orEmpty()
        return namespaces.getValue(matchingRoot.key).resolve(relative).takeIf { it.isValid }?.file
            ?.takeIf { it.isInsideOrEqual(matchingRoot.value) }
    }

    private fun File.safeCanonicalFile(): File? = runCatching { canonicalFile }.getOrNull()

    private fun File.isInsideOrEqual(root: File): Boolean {
        if (this == root) return true
        val rootPath = root.path
        if (rootPath == File.separator) return path.startsWith(rootPath)
        return path.startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
    }
}
