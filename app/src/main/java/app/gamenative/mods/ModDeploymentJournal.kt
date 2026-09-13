package app.gamenative.mods

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ModDeploymentCheckpoint {
    PLANNED,
    PREPARING,
    APPLYING,
    VERIFYING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
}

@Serializable
data class ModDeploymentJournal(
    val version: Int = 1,
    val operationId: String = UUID.randomUUID().toString(),
    val installId: String,
    val appId: String,
    val planDigest: String,
    val targetCount: Int,
    val checkpoint: ModDeploymentCheckpoint = ModDeploymentCheckpoint.PLANNED,
    val detail: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = startedAt,
)

object ModDeploymentJournalStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun begin(root: File, installId: String, appId: String, plan: ModMaterializationPlan): ModDeploymentJournal {
        val journal = ModDeploymentJournal(
            installId = installId,
            appId = appId,
            planDigest = plan.digest,
            targetCount = plan.files.size,
        )
        write(root, journal)
        return journal
    }

    fun checkpoint(
        root: File,
        journal: ModDeploymentJournal,
        checkpoint: ModDeploymentCheckpoint,
        detail: String = "",
    ): ModDeploymentJournal = journal.copy(
        checkpoint = checkpoint,
        detail = detail.take(512),
        updatedAt = System.currentTimeMillis(),
    ).also { write(root, it) }

    fun read(root: File, installId: String): ModDeploymentJournal? = readFile(journalFile(root, installId))

    fun readAll(root: File): List<ModDeploymentJournal> =
        journalDir(root).listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.mapNotNull(::readFile)

    fun delete(root: File, installId: String) {
        val current = journalFile(root, installId)
        current.delete()
        File(current.parentFile, "${current.name}.tmp").delete()
    }

    fun reconcile(root: File): List<ModDeploymentJournal> = readAll(root).map { journal ->
        when (journal.checkpoint) {
            ModDeploymentCheckpoint.PLANNED,
            ModDeploymentCheckpoint.PREPARING,
            -> checkpoint(
                root,
                journal,
                ModDeploymentCheckpoint.ROLLED_BACK,
                "Recovered before filesystem mutation",
            )
            ModDeploymentCheckpoint.VERIFYING -> {
                val ownership = ModOwnershipStore.read(root, journal.installId)
                if (
                    ownership?.planDigest == journal.planDigest &&
                    ModDeploymentVerifier.verify(ownership).issues.isEmpty()
                ) {
                    checkpoint(root, journal, ModDeploymentCheckpoint.COMMITTED, "Recovered verified deployment")
                } else {
                    checkpoint(root, journal, ModDeploymentCheckpoint.RECOVERY_REQUIRED, "Deployment state needs review")
                }
            }
            ModDeploymentCheckpoint.APPLYING,
            ModDeploymentCheckpoint.ROLLING_BACK,
            -> checkpoint(
                root,
                journal,
                ModDeploymentCheckpoint.RECOVERY_REQUIRED,
                "Filesystem mutation may have been interrupted",
            )
            else -> journal
        }
    }

    private fun write(root: File, journal: ModDeploymentJournal) {
        val current = journalFile(root, journal.installId)
        val temp = File(current.parentFile, "${current.name}.tmp")
        current.parentFile?.mkdirs()
        FileOutputStream(temp).use { output ->
            output.write(json.encodeToString(journal).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        replaceWithPreparedFile(temp, current)
    }

    private fun readFile(file: File): ModDeploymentJournal? =
        if (!file.isFile) null else runCatching { json.decodeFromString<ModDeploymentJournal>(file.readText()) }.getOrNull()

    private fun journalDir(root: File): File = File(root, "journals")

    private fun journalFile(root: File, installId: String): File =
        File(journalDir(root), "${installId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
}
