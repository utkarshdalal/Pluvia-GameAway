package app.gamenative.mods

import java.util.Locale

enum class ArchiveContentRole {
    INSTALLABLE,
    DOCUMENTATION,
    METADATA,
    INSTALLER_SUPPORT,
    RISKY_ROOT,
    INVALID,
}

data class IndexedArchiveFile(
    val displayPath: String,
    val normalizedKey: String,
    val sizeBytes: Long,
    val role: ArchiveContentRole,
)

data class ArchiveTreeNode(
    val displayPath: String,
    val normalizedKey: String,
    val descendantFileCount: Int,
    val descendantBytes: Long,
    val semanticAnchors: Set<String>,
    val optionStyleWrapper: Boolean,
)

data class ModArchiveIndex(
    val files: List<IndexedArchiveFile>,
    val nodes: List<ArchiveTreeNode>,
    val caseCollisions: Map<String, List<String>>,
) {
    val hasFomod: Boolean
        get() = files.any { it.normalizedKey.endsWith("fomod/moduleconfig.xml") }

    fun filesUnder(sourcePath: String): List<IndexedArchiveFile> {
        val key = normalizedArchiveKey(sourcePath) ?: return emptyList()
        if (key.isBlank()) return files
        val prefix = "$key/"
        val exactStart = lowerBound(key)
        val prefixStart = lowerBound(prefix)
        val result = mutableListOf<IndexedArchiveFile>()
        var exactEnd = exactStart
        while (exactEnd < files.size && files[exactEnd].normalizedKey == key) {
            result += files[exactEnd]
            exactEnd++
        }
        var end = prefixStart
        while (end < files.size && files[end].normalizedKey.startsWith(prefix)) {
            result += files[end]
            end++
        }
        return result
    }

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = files.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (files[middle].normalizedKey < key) low = middle + 1 else high = middle
        }
        return low
    }

    fun isDirectory(sourcePath: String): Boolean {
        val key = normalizedArchiveKey(sourcePath) ?: return false
        return nodes.binarySearchBy(key) { it.normalizedKey } >= 0
    }

    companion object {
        private val semanticAnchors = ModPlacementRulePacks.archiveSemanticAnchors
        private val managerMetadataDirectories = setOf("bashtags", "omod conversion data")
        private val documentationDirectories = setOf(
            "doc",
            "docs",
            "documentation",
            "help",
            "manual",
            "manuals",
            "readmes",
            "screenshot",
            "screenshots",
        )
        private val documentationNamePrefixes = setOf(
            "readme",
            "changelog",
            "changes",
            "license",
            "copying",
            "credits",
            "authors",
            "install instructions",
            "installation instructions",
        )
        private val documentationExtensions = setOf("htm", "html", "md", "pdf", "rtf", "txt")
        private val documentationBoundaries = setOf(' ', '_', '-', '.')

        fun build(entries: List<ModArchiveEntry>): ModArchiveIndex {
            val indexedFiles = entries.asSequence()
                .filterNot { it.directory }
                .map { entry ->
                    val display = normalizeArchiveDisplayPath(entry.path)
                    val key = normalizedArchiveKey(entry.path)
                    IndexedArchiveFile(
                        displayPath = display.ifBlank { entry.path },
                        normalizedKey = key.orEmpty(),
                        sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                        role = if (key == null) ArchiveContentRole.INVALID else classify(display),
                    )
                }
                .sortedWith(compareBy<IndexedArchiveFile> { it.normalizedKey }.thenBy { it.displayPath })
                .toList()
            val displayPaths = mutableMapOf<String, String>()
            val counts = mutableMapOf<String, Int>()
            val bytes = mutableMapOf<String, Long>()
            val anchors = mutableMapOf<String, MutableSet<String>>()
            entries.asSequence().filter { it.directory }.forEach { entry ->
                val display = normalizeArchiveDisplayPath(entry.path)
                val key = normalizedArchiveKey(display)
                if (display.isNotBlank() && key != null) displayPaths.rememberDisplayPath(key, display)
            }
            indexedFiles.forEach { file ->
                val displaySegments = file.displayPath.split('/')
                val keySegments = file.normalizedKey.split('/')
                val fileAnchors = keySegments.filterTo(mutableSetOf()) { it in semanticAnchors }
                val keyBuilder = StringBuilder()
                val displayBuilder = StringBuilder()
                (0 until keySegments.lastIndex).forEach { index ->
                    if (index > 0) {
                        keyBuilder.append('/')
                        displayBuilder.append('/')
                    }
                    keyBuilder.append(keySegments[index])
                    displayBuilder.append(displaySegments[index])
                    val key = keyBuilder.toString()
                    val display = displayBuilder.toString()
                    displayPaths.rememberDisplayPath(key, display)
                    counts[key] = counts.getOrDefault(key, 0) + 1
                    bytes[key] = bytes.getOrDefault(key, 0L) + file.sizeBytes
                    anchors.getOrPut(key, ::mutableSetOf).addAll(fileAnchors)
                }
            }
            val nodes = displayPaths.entries
                .sortedBy { it.key }
                .map { (key, display) ->
                    ArchiveTreeNode(
                        displayPath = display,
                        normalizedKey = key,
                        descendantFileCount = counts.getOrDefault(key, 0),
                        descendantBytes = bytes.getOrDefault(key, 0L),
                        semanticAnchors = anchors[key].orEmpty(),
                        optionStyleWrapper = looksLikeOptionWrapper(display.substringAfterLast('/')),
                    )
                }
            return ModArchiveIndex(
                files = indexedFiles,
                nodes = nodes,
                caseCollisions = indexedFiles.filter { it.normalizedKey.isNotBlank() }
                    .groupBy { it.normalizedKey }
                    .filterValues { variants -> variants.map { it.displayPath }.distinct().size > 1 }
                    .mapValues { (_, variants) -> variants.map { it.displayPath }.distinct().sorted() },
            )
        }

        private fun classify(path: String): ArchiveContentRole {
            val normalized = path.lowercase(Locale.ROOT)
            val segments = normalized.split('/')
            val name = normalized.substringAfterLast('/')
            if (normalized.startsWith("__macosx/") || name in setOf(".ds_store", "thumbs.db", "desktop.ini")) {
                return ArchiveContentRole.METADATA
            }
            if (segments.dropLast(1).any { it in managerMetadataDirectories }) {
                return ArchiveContentRole.METADATA
            }
            if (normalized.contains("/fomod/") || normalized.startsWith("fomod/")) {
                return ArchiveContentRole.INSTALLER_SUPPORT
            }
            val rootDocumentation =
                segments.size == 1 && listOf(".htm", ".html", ".rtf").any(name::endsWith)
            if (
                segments.dropLast(1).any(::isDocumentationDirectory) ||
                looksLikeDocumentationFileName(name) ||
                name.endsWith(".md") ||
                name.endsWith(".pdf") ||
                rootDocumentation
            ) {
                return ArchiveContentRole.DOCUMENTATION
            }
            if (!normalized.contains('/') && listOf(".dll", ".asi", ".exe", ".ini", ".bat", ".cmd", ".ps1", ".msi").any(name::endsWith)) {
                return ArchiveContentRole.RISKY_ROOT
            }
            return ArchiveContentRole.INSTALLABLE
        }

        private fun isDocumentationDirectory(segment: String): Boolean {
            val name = segment.trim(' ', '_', '-', '.')
            return name in documentationDirectories ||
                listOf("readme", "documentation", "manual", "screenshot").any { prefix -> hasPrefixAtBoundary(name, prefix) }
        }

        private fun looksLikeDocumentationFileName(name: String): Boolean {
            val extension = name.substringAfterLast('.', "")
            return documentationNamePrefixes.any { prefix ->
                hasPrefixAtBoundary(name, prefix) ||
                    (name.startsWith(prefix) && extension in documentationExtensions)
            }
        }

        private fun hasPrefixAtBoundary(value: String, prefix: String): Boolean =
            value.startsWith(prefix) && (value.length == prefix.length || value[prefix.length] in documentationBoundaries)

        private fun looksLikeOptionWrapper(name: String): Boolean {
            val normalized = name.lowercase(Locale.ROOT)
            return Regex("^\\d{1,2}[ _.-]").containsMatchIn(normalized) ||
                Regex("^v?\\d+(?:[._-]\\d+)+(?:[-_ ].*)?$").matches(normalized) ||
                listOf("optional", "option", "variant", "choose", "pick one").any(normalized::contains)
        }

        private fun stableDisplayPath(left: String, right: String): String =
            minOf(left, right, compareBy<String> { it.lowercase(Locale.ROOT) }.thenBy { it })

        private fun MutableMap<String, String>.rememberDisplayPath(key: String, display: String) {
            val existing = this[key]
            when {
                existing == null -> this[key] = display
                existing != display -> this[key] = stableDisplayPath(existing, display)
            }
        }
    }
}

internal fun ArchiveContentRole.participatesInAutomaticPlacement(): Boolean =
    this == ArchiveContentRole.INSTALLABLE || this == ArchiveContentRole.RISKY_ROOT

internal fun normalizeArchiveDisplayPath(path: String): String =
    path.trim().replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")

internal fun normalizedArchiveKey(path: String): String? {
    val normalized = path.trim().replace('\\', '/')
    if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
    val segments = normalized.split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." }) return null
    return segments.joinToString("/") { it.lowercase(Locale.ROOT) }
}
