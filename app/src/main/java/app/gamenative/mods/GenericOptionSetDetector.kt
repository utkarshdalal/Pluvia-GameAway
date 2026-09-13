package app.gamenative.mods

import java.util.Locale

data class GenericOptionChoice(
    val sourceDirectory: String,
    val overlappingTargetCount: Int,
)

data class GenericOptionGroup(
    val stableId: String,
    val choices: List<GenericOptionChoice>,
    val commonSourceDirectories: List<String>,
    val reason: String,
)

object GenericOptionSetDetector {
    private val installBoundaryPrefixes = buildSet {
        add("data")
        ModPlacementRulePacks.builtIns
            .flatMap { it.directoryTargets.values }
            .mapTo(this) { normalizeArchiveDisplayPath(it).lowercase(Locale.ROOT) }
    }

    fun detect(index: ModArchiveIndex): List<GenericOptionGroup> {
        if (index.hasFomod) return emptyList()
        val directories = index.nodes.filter { it.descendantFileCount > 0 }
        val siblingsByParent = directories.groupBy { node -> node.normalizedKey.substringBeforeLast('/', "") }
        val detected = siblingsByParent.values.flatMap { siblings -> detectSiblingGroups(index, siblings) }
            .sortedWith(compareBy<GenericOptionGroup> { it.choices.first().sourceDirectory.count { char -> char == '/' } }.thenBy { it.stableId })
        val accepted = mutableListOf<GenericOptionGroup>()
        detected.forEach { group ->
            val nestedInsideAcceptedChoice = accepted.any { outer ->
                outer.choices.any { choice ->
                    group.choices.all { nested ->
                        val path = normalizeArchiveDisplayPath(nested.sourceDirectory)
                        val root = normalizeArchiveDisplayPath(choice.sourceDirectory)
                        path.equals(root, ignoreCase = true) || path.startsWith("$root/", ignoreCase = true)
                    }
                }
            }
            if (!nestedInsideAcceptedChoice) accepted += group
        }
        return accepted
    }

    private fun detectSiblingGroups(
        index: ModArchiveIndex,
        siblings: List<ArchiveTreeNode>,
    ): List<GenericOptionGroup> {
        if (siblings.size < 2) return emptyList()
        val signatures = siblings.associateWith { node ->
            index.filesUnder(node.displayPath)
                .filter { it.role.participatesInAutomaticPlacement() }
                .mapTo(mutableSetOf()) { file ->
                    file.normalizedKey.removePrefix("${node.normalizedKey}/")
                }
        }
        val versionChoices = siblings.filter {
            signatures.getValue(it).isNotEmpty() && it.displayPath.substringAfterLast('/').isVersionChoiceName()
        }
            .mapTo(mutableSetOf()) { it.normalizedKey }
        val versionFamilyHasEvidence = versionChoices.size >= 3 || siblings.any { root ->
            root.normalizedKey in versionChoices && siblings.any { other ->
                other != root && other.normalizedKey in versionChoices &&
                    signatures.getValue(root).intersect(signatures.getValue(other)).isNotEmpty()
            }
        }
        val parentLooksLikeOptionContainer = siblings.first().normalizedKey
            .substringBeforeLast('/', "")
            .substringAfterLast('/')
            .let { parent ->
                listOf("option", "variant", "choose", "choice", "pick one").any(parent::contains)
            }
        val installBoundaries = siblings.associateWith { root ->
            signatures.getValue(root).mapNotNullTo(mutableSetOf()) { relative ->
                installBoundaryPrefixes.firstOrNull { boundary ->
                    relative == boundary || relative.startsWith("$boundary/")
                }
            }
        }
        val related = siblings.associateWith { root ->
            siblings.filter { other ->
                if (root == other) return@filter false
                val versionAlternatives = versionFamilyHasEvidence &&
                    root.normalizedKey in versionChoices && other.normalizedKey in versionChoices
                val wrapperEvidence = root.optionStyleWrapper ||
                    other.optionStyleWrapper ||
                    parentLooksLikeOptionContainer ||
                    installBoundaries.getValue(root).intersect(installBoundaries.getValue(other)).isNotEmpty()
                if (versionAlternatives) return@filter true
                if (!wrapperEvidence) return@filter false
                val rootSignature = signatures.getValue(root)
                val otherSignature = signatures.getValue(other)
                val overlap = rootSignature.intersect(otherSignature).size
                val smaller = minOf(rootSignature.size, otherSignature.size).coerceAtLeast(1)
                overlap > 0 && overlap.toDouble() / smaller >= 0.6
            }
        }
        val visited = mutableSetOf<String>()
        val groups = mutableListOf<List<ArchiveTreeNode>>()
        siblings.forEach { root ->
            if (!visited.add(root.normalizedKey)) return@forEach
            val component = mutableListOf(root)
            val queue = ArrayDeque(related.getValue(root))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!visited.add(next.normalizedKey)) continue
                component += next
                queue.addAll(related.getValue(next))
            }
            if (component.size > 1) groups += component
        }
        val optionRootKeys = groups.flatten().mapTo(mutableSetOf()) { it.normalizedKey }
        val common = siblings.filter { it.normalizedKey !in optionRootKeys }.map { it.displayPath }.sorted()
        return groups.map { choices ->
            GenericOptionGroup(
                stableId = choices.map { it.normalizedKey }.sorted().joinToString("|").hashCode().toUInt().toString(16),
                choices = choices.sortedBy { it.normalizedKey }.map { root ->
                    val peers = choices.filter { it != root }
                    GenericOptionChoice(
                        sourceDirectory = root.displayPath,
                        overlappingTargetCount =
                        peers.maxOfOrNull { signatures.getValue(root).intersect(signatures.getValue(it)).size } ?: 0,
                    )
                },
                commonSourceDirectories = common,
                reason = "Sibling folders contain competing files for the same normalized targets",
            )
        }.sortedBy { it.choices.first().sourceDirectory.lowercase(Locale.ROOT) }
    }

    private fun String.isVersionChoiceName(): Boolean =
        matches(Regex("^v?\\d+(?:[._-]\\d+)+(?:[-_ ].*)?$", RegexOption.IGNORE_CASE))
}
