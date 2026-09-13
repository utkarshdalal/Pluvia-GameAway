package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ModConfigurationDraft(
    val version: Int = 1,
    val installId: String,
    val archiveIdentity: String,
    val placementChoice: String,
    val automaticOptions: Map<String, String> = emptyMap(),
    val riskyTargetsApproved: Boolean = false,
    val fomodSelections: Map<String, List<String>> = emptyMap(),
    val recipes: List<ModConfigurationRecipe> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ModConfigurationRecipe(
    val sourceSubpath: String = "",
    val targetRoot: String = ModTargetRoot.GAME_DIR.name,
    val targetRelativePath: String = "",
    val targetFileName: String = "",
    val mode: String = ModPlacementMode.SYMLINK.name,
    val stripPrefixSegments: Int = 0,
    val includeSourceDirectory: Boolean = false,
    val enabled: Boolean = true,
) {
    fun toRecipe(installId: String) = ModPlacementRecipe(
        installId = installId,
        sourceSubpath = sourceSubpath,
        targetRoot = targetRoot,
        targetRelativePath = targetRelativePath,
        targetFileName = targetFileName,
        mode = mode,
        stripPrefixSegments = stripPrefixSegments,
        includeSourceDirectory = includeSourceDirectory,
        enabled = enabled,
    )

    companion object {
        fun from(recipe: ModPlacementRecipe) = ModConfigurationRecipe(
            sourceSubpath = recipe.sourceSubpath,
            targetRoot = recipe.targetRoot,
            targetRelativePath = recipe.targetRelativePath,
            targetFileName = recipe.targetFileName,
            mode = recipe.mode,
            stripPrefixSegments = recipe.stripPrefixSegments,
            includeSourceDirectory = recipe.includeSourceDirectory,
            enabled = recipe.enabled,
        )
    }
}

object ModConfigurationDraftStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun archiveIdentity(install: ModInstall): String = install.archiveSha256.ifBlank {
        listOf(install.fileName.lowercase(Locale.ROOT), install.sizeBytes.toString(), install.nexusFileId?.toString().orEmpty())
            .joinToString("|")
    }

    fun read(root: File, install: ModInstall): ModConfigurationDraft? {
        val file = file(root, install.installId)
        if (!file.isFile) return null
        val draft = runCatching { json.decodeFromString<ModConfigurationDraft>(file.readText()) }.getOrNull()
            ?: return null
        return draft.takeIf {
            it.installId == install.installId && it.archiveIdentity == archiveIdentity(install)
        }
    }

    fun write(root: File, draft: ModConfigurationDraft): Boolean {
        val target = file(root, draft.installId)
        val temp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            target.parentFile?.mkdirs()
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(draft.copy(updatedAt = System.currentTimeMillis())).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            replaceWithPreparedFile(temp, target)
        }.fold(
            onSuccess = { true },
            onFailure = {
                temp.delete()
                false
            },
        )
    }

    fun delete(root: File, installId: String) {
        file(root, installId).delete()
        File(file(root, installId).parentFile, "${file(root, installId).name}.tmp").delete()
    }

    private fun file(root: File, installId: String): File =
        File(File(root, "configuration"), "${installId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
}
