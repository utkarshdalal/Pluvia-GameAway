package app.gamenative.mods

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModOverwriteManifest
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.ModDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

data class ModImportProgress(
    val status: String,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

class ModImportPausedException(message: String) : IOException(message)

class ModImportCanceledException(message: String) : IOException(message)

data class ModStoragePreflight(
    val estimatedRequiredBytes: Long,
    val availableBytes: Long,
) {
    val canImport: Boolean
        get() = availableBytes >= estimatedRequiredBytes
}

data class ModCleanupResult(
    val reclaimedBytes: Long,
)

data class ModStorageBreakdown(
    val cleanableBytes: Long,
    val failedArchiveBytes: Long,
    val extractedCacheBytes: Long,
    val backupBytes: Long,
    val redundantBackupBytes: Long = 0L,
    val redundantBackupCount: Int = 0,
)

enum class ModHealthSeverity {
    ERROR,
    WARNING,
}

enum class ModHealthAction {
    REAPPLY_MISSING,
    RECONFIGURE,
    REVIEW_PLACEMENT,
    REBUILD_PROFILE,
    ADOPT_OWNERSHIP,
    RESTORE_PREVIOUS,
}

data class ModHealthIssue(
    val severity: ModHealthSeverity,
    val title: String,
    val detail: String,
    val installName: String = "",
    val installId: String = "",
    val recommendedAction: ModHealthAction = ModHealthAction.REBUILD_PROFILE,
)

data class ModHealthReport(
    val issues: List<ModHealthIssue>,
    val facts: List<String> = emptyList(),
) {
    val errorCount: Int get() = issues.count { it.severity == ModHealthSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == ModHealthSeverity.WARNING }

    fun sanitizedManifest(): String = buildString {
        appendLine("health-version: 1")
        appendLine("errors: $errorCount")
        appendLine("warnings: $warningCount")
        facts.forEach { fact -> appendLine("fact: ${ModDiagnosticSanitizer.text(fact)}") }
        issues.forEach { issue ->
            append(issue.severity.name)
            append(' ')
            if (issue.installName.isNotBlank()) append("${ModDiagnosticSanitizer.text(issue.installName)}: ")
            append(ModDiagnosticSanitizer.text(issue.title))
            append(" action=")
            append(issue.recommendedAction.name)
            append(" [")
            append(ModDiagnosticSanitizer.text(issue.detail))
            appendLine(']')
        }
    }
}

object NexusModManager {
    private const val KEEP_IMPORTED_ARCHIVES = false
    private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
    private const val UNKNOWN_IMPORT_BYTES = 512L * 1024L * 1024L
    private const val ESTIMATED_EXTRACTED_MULTIPLIER = 2L

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ModDaoEntryPoint {
        fun modDao(): ModDao
        fun database(): PluviaDatabase
    }

    private val downloadClient = OkHttpClient()

    fun dao(context: Context): ModDao =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ModDaoEntryPoint::class.java,
        ).modDao()

    internal fun database(context: Context): PluviaDatabase =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ModDaoEntryPoint::class.java,
        ).database()

    fun cacheRoot(context: Context, appId: String): File =
        File(context.filesDir, "mods/$appId/nexus")

    fun backupRoot(context: Context, appId: String): File =
        File(context.filesDir, "mods/$appId/backups")

    fun installIdFor(appId: String, gameDomain: String, modId: Long, fileId: Long): String =
        installId(appId, gameDomain, modId, fileId)

    fun hasCompletePendingArchive(context: Context, install: ModInstall): Boolean {
        val tempArchiveFile = pendingArchiveFile(context, install)
        return tempArchiveFile.isFile &&
            (
                (install.sizeBytes > 0L && tempArchiveFile.length() == install.sizeBytes) ||
                    NexusImportState.hasCompletedDownload(install, tempArchiveFile.length())
            )
    }

    fun hasCompletePendingLocalContent(context: Context, install: ModInstall): Boolean {
        if (!ModInstallSource.isLocal(install.source)) return false
        val content = localContentPaths(context, install)
        val previousContentRequired = NexusImportState.restorablePreviousInstall(install) != null
        val previousContentAvailable = verifiedPreviousLocalInstall(context, install) != null
        if (install.source == ModInstallSource.LOCAL_ARCHIVE.name) {
            val tempArchiveFile = pendingArchiveFile(context, install)
            return tempArchiveFile.isFile &&
                NexusImportState.hasCompletedDownload(install, tempArchiveFile.length()) &&
                (!previousContentRequired || previousContentAvailable)
        }
        fun isComplete(content: File): Boolean =
            hasUsableExtractedContent(content) &&
                NexusImportState.hasCompletedLocalSnapshot(install, directorySize(content))

        if (isComplete(content.staged)) {
            return !previousContentRequired || previousContentAvailable
        }
        // When the completed snapshot is already promoted, only .previous can be the
        // old install; promotedContent itself must not satisfy both sides of the check.
        return (!previousContentRequired || hasUsableExtractedContent(content.previous)) &&
            isComplete(content.promoted)
    }

    internal fun verifiedPreviousLocalInstall(
        context: Context,
        install: ModInstall?,
    ): ModInstall? {
        if (install == null || !ModInstallSource.isLocal(install.source)) return null
        val previousInstall = NexusImportState.restorablePreviousInstall(install) ?: return null
        val content = localContentPaths(context, install)
        return previousInstall.takeIf {
            hasUsableExtractedContent(content.promoted) ||
                hasUsableExtractedContent(content.previous)
        }
    }

    internal fun restorePreviousLocalInstall(
        context: Context,
        install: ModInstall?,
        failureMessage: String,
    ): ModInstall? {
        if (install == null || !ModInstallSource.isLocal(install.source)) return null
        val previousInstall = NexusImportState.restorablePreviousInstall(install) ?: return null
        val content = localContentPaths(context, install)

        if (content.previous.exists()) {
            if (!hasUsableExtractedContent(content.previous)) throw IOException(failureMessage)
            if (content.promoted.exists() && !content.promoted.deleteRecursively()) {
                throw IOException(failureMessage)
            }
            if (!content.previous.renameTo(content.promoted)) throw IOException(failureMessage)
        }
        if (hasUsableExtractedContent(content.promoted)) return previousInstall
        if (content.promoted.exists() && !content.promoted.deleteRecursively()) {
            throw IOException(failureMessage)
        }
        return null
    }

    internal fun discardIncompletePendingLocalContent(
        context: Context,
        install: ModInstall,
        failureMessage: String,
    ): Boolean {
        if (!ModInstallSource.isLocal(install.source)) return false
        val content = localContentPaths(context, install)

        fun deleteOrThrow(file: File) {
            if (file.exists() && !file.deleteRecursively()) {
                throw IOException(failureMessage)
            }
        }

        deleteOrThrow(content.staged)
        if (install.source == ModInstallSource.LOCAL_ARCHIVE.name) {
            deleteOrThrow(pendingArchiveFile(context, install))
        }

        if (NexusImportState.restorablePreviousInstall(install) == null) {
            deleteOrThrow(content.promoted)
            deleteOrThrow(content.previous)
            return false
        }
        return restorePreviousLocalInstall(context, install, failureMessage) != null
    }

    private fun pendingArchiveFile(context: Context, install: ModInstall): File {
        val archiveFile = install.archivePath
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(
                cacheRoot(context, install.appId),
                "archives/${sanitizeFileName("${install.installId}_${install.fileName}")}",
            )
        return File(archiveFile.parentFile, "${archiveFile.name}.part")
    }

    private data class LocalContentPaths(val promoted: File) {
        val staged = File("${promoted.absolutePath}.tmp")
        val previous = File("${promoted.absolutePath}.previous")
    }

    private fun localContentPaths(context: Context, install: ModInstall) =
        LocalContentPaths(File(cacheRoot(context, install.appId), "extracted/${install.installId}"))

    fun estimateImportScratchBytes(files: List<NexusModFile>): Long =
        files.fold(0L) { total, file ->
            val estimate = estimateImportScratchBytes(file.sizeBytes)
            if (Long.MAX_VALUE - total < estimate) Long.MAX_VALUE else total + estimate
        }

    fun estimateSequentialImportScratchBytes(files: List<NexusModFile>): Long {
        if (files.isEmpty()) return 0L
        val archiveBytes = files.map { it.sizeBytes.takeIf { size -> size > 0L } ?: UNKNOWN_IMPORT_BYTES }
        val retainedExtractedBytes = archiveBytes.fold(0L) { total, size ->
            val extracted = estimatedExtractedBytes(size)
            if (Long.MAX_VALUE - total < extracted) Long.MAX_VALUE else total + extracted
        }
        val largestActiveImportBytes = archiveBytes.maxOrNull()?.let { size ->
            val extracted = estimatedExtractedBytes(size)
            if (Long.MAX_VALUE - size < extracted) Long.MAX_VALUE else size + extracted
        } ?: 0L
        return if (Long.MAX_VALUE - retainedExtractedBytes < largestActiveImportBytes) {
            Long.MAX_VALUE
        } else {
            retainedExtractedBytes + largestActiveImportBytes
        }
    }

    suspend fun checkImportStorage(
        context: Context,
        appId: String,
        files: List<NexusModFile>,
        sequential: Boolean = false,
    ): ModStoragePreflight = withContext(Dispatchers.IO) {
        val root = cacheRoot(context, appId).apply { mkdirs() }
        val estimatedBytes = if (sequential) {
            estimateSequentialImportScratchBytes(files)
        } else {
            estimateImportScratchBytes(files)
        }
        ModStoragePreflight(
            estimatedRequiredBytes = estimatedBytes,
            availableBytes = root.usableSpace,
        )
    }

    suspend fun checkLocalImportStorage(
        context: Context,
        appId: String,
        sourceBytes: Long,
        requiresExtraction: Boolean = true,
    ): ModStoragePreflight = withContext(Dispatchers.IO) {
        val root = cacheRoot(context, appId).apply { mkdirs() }
        ModStoragePreflight(
            estimatedRequiredBytes = if (requiresExtraction) {
                estimateImportScratchBytes(sourceBytes)
            } else {
                sourceBytes.takeIf { it > 0L } ?: UNKNOWN_IMPORT_BYTES
            },
            availableBytes = root.usableSpace,
        )
    }

    suspend fun importNexusFile(
        context: Context,
        appId: String,
        reference: NexusModReference,
        modInfo: NexusModInfo,
        file: NexusModFile,
        apiClient: NexusApiClient = NexusApiClient(),
        isPremiumAccount: Boolean? = null,
        onDetailedProgress: (ModImportProgress) -> Unit = {},
    ): ModInstall = withContext(Dispatchers.IO) {
        if (reference.fileId != null && reference.fileId != file.fileId) {
            throw IOException("The Nexus authorization does not match the selected file")
        }
        val dao = dao(context)
        val installId = installId(appId, reference.gameDomain, reference.modId, file.fileId)
        val root = cacheRoot(context, appId)
        val archiveDir = File(root, "archives")
        val extractDir = File(root, "extracted/$installId")
        val tempExtractDir = File(root, "extracted/$installId.tmp")
        archiveDir.mkdirs()

        val archiveFile = File(archiveDir, sanitizeFileName("${installId}_${file.fileName}"))
        val tempArchiveFile = File(archiveDir, "${archiveFile.name}.part")
        val previousInstall = dao.getInstall(installId)
        val previousDownloadCompleted = NexusImportState.hasCompletedDownload(
            previousInstall,
            tempArchiveFile.length(),
        )
        val restorablePreviousInstall = NexusImportState.restorablePreviousInstall(previousInstall)
        val importing = ModInstall(
            installId = installId,
            appId = appId,
            nexusGameDomain = reference.gameDomain,
            nexusModId = reference.modId,
            nexusFileId = file.fileId,
            modName = modInfo.name,
            fileName = file.fileName,
            version = file.version.ifBlank { modInfo.version },
            sizeBytes = file.sizeBytes,
            archivePath = archiveFile.absolutePath,
            extractedPath = extractDir.absolutePath,
            enabled = restorablePreviousInstall?.enabled ?: true,
            status = ModInstallStatus.IMPORTING.name,
            downloadedAt = 0L,
            metadataJson = NexusImportState.importMetadata(modInfo.summary, restorablePreviousInstall),
        )
        dao.upsertInstall(importing)
        suspend fun recordTerminalImport(status: ModInstallStatus, message: String) {
            dao.upsertInstall(
                NexusImportState.terminalInstall(
                    importing = importing,
                    summary = modInfo.summary,
                    status = status,
                    message = message,
                    previousInstall = restorablePreviousInstall,
                ),
            )
        }

        val startAllowed = ModDownloadRegistry.start(installId, appId, modInfo.name)
        var downloadCompleted = false
        var extractionCompleted = false
        try {
            if (!startAllowed || ModDownloadRegistry.isCancelRequested(installId)) {
                throw ModImportCanceledException("Import canceled")
            }
            val archiveAlreadyDownloaded = tempArchiveFile.isFile &&
                (
                    (file.sizeBytes > 0L && tempArchiveFile.length() == file.sizeBytes) ||
                        previousDownloadCompleted
                )
            if (archiveAlreadyDownloaded) {
                val completedBytes = tempArchiveFile.length()
                val expectedBytes = file.sizeBytes.takeIf { it > 0L } ?: completedBytes
                val complete = ModImportProgress(
                    status = "Downloading",
                    progress = 1f,
                    downloadedBytes = completedBytes,
                    totalBytes = expectedBytes,
                )
                ModDownloadRegistry.update(
                    installId = installId,
                    progress = complete.progress,
                    status = complete.status,
                    downloadedBytes = complete.downloadedBytes,
                    totalBytes = complete.totalBytes,
                )
                onDetailedProgress(complete)
            } else {
                if (reference.downloadAuthorization?.isExpired() == true) {
                    throw NexusApiException(
                        message = context.getString(R.string.nexus_authorization_expired),
                        statusCode = 410,
                        reason = NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_EXPIRED,
                    )
                }
                ensureDownloadNetworkAllowed()
                val links = apiClient.getDownloadLinks(
                    gameDomain = reference.gameDomain,
                    modId = reference.modId,
                    fileId = file.fileId,
                    downloadAuthorization = reference.downloadAuthorization,
                    isPremiumAccount = isPremiumAccount,
                )
                val downloadUrl = links.firstOrNull()?.uri ?: throw IOException("Nexus did not return a download link")
                download(installId, downloadUrl, tempArchiveFile, file.sizeBytes) {
                    ModDownloadRegistry.update(
                        installId = installId,
                        progress = it.progress,
                        status = it.status,
                        downloadedBytes = it.downloadedBytes,
                        totalBytes = it.totalBytes,
                    )
                    onDetailedProgress(it)
                }
            }
            downloadCompleted = true
            dao.upsertInstall(NexusImportState.markDownloadComplete(importing, tempArchiveFile.length()))
            val unpacking = ModImportProgress("Unpacking", progress = 0f)
            ModDownloadRegistry.update(installId, 0f, unpacking.status)
            onDetailedProgress(unpacking)
            val extraction = ModArchiveExtractor.extract(
                archiveFile = tempArchiveFile,
                destination = tempExtractDir,
                preservedSingleFileName = file.fileName,
            ) { extractProgress ->
                if (ModDownloadRegistry.isCancelRequested(installId)) {
                    throw ModImportCanceledException("Import canceled")
                }
                val unpackProgress = when {
                    extractProgress.totalBytes > 0L ->
                        (extractProgress.extractedBytes.toFloat() / extractProgress.totalBytes.toFloat()).coerceIn(0f, 1f)
                    extractProgress.totalEntries > 0 ->
                        (extractProgress.entriesProcessed.toFloat() / extractProgress.totalEntries.toFloat()).coerceIn(0f, 1f)
                    else -> 0f
                }
                val detail = ModImportProgress(
                    status = "Unpacking",
                    progress = unpackProgress,
                    downloadedBytes = extractProgress.extractedBytes,
                    totalBytes = extractProgress.totalBytes,
                )
                ModDownloadRegistry.update(
                    installId = installId,
                    progress = detail.progress,
                    status = detail.status,
                    downloadedBytes = detail.downloadedBytes,
                    totalBytes = detail.totalBytes,
                )
                onDetailedProgress(detail)
            }
            extractionCompleted = true
            if (extractDir.exists()) extractDir.deleteRecursively()
            moveFileOrDirectory(extraction.destination, extractDir)
            val storedArchivePath = if (KEEP_IMPORTED_ARCHIVES) {
                archiveFile.delete()
                moveFileOrDirectory(tempArchiveFile, archiveFile)
                archiveFile.absolutePath
            } else {
                archiveFile.delete()
                ""
            }
            val ready = importing.copy(
                archivePath = storedArchivePath,
                extractedPath = extractDir.absolutePath,
                status = ModInstallStatus.READY.name,
                downloadedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                metadataJson = NexusImportState.importMetadata(modInfo.summary),
            )
            dao.upsertInstall(ready)
            if (!KEEP_IMPORTED_ARCHIVES) {
                tempArchiveFile.delete()
            }
            val profile = ModProfileManager.ensureActiveProfile(dao, appId)
            ModProfileManager.ensureStateForInstall(dao, profile, ready.installId)
            ready
        } catch (e: OutOfMemoryError) {
            tempExtractDir.deleteRecursively()
            val failure = IOException(
                context.getString(R.string.nexus_archive_memory_error),
                e,
            )
            recordTerminalImport(ModInstallStatus.ERROR, NexusImportState.userMessage(failure))
            throw failure
        } catch (e: ModImportPausedException) {
            tempExtractDir.deleteRecursively()
            recordTerminalImport(ModInstallStatus.PAUSED, e.message ?: "Import paused")
            throw e
        } catch (e: ModImportCanceledException) {
            tempExtractDir.deleteRecursively()
            tempArchiveFile.delete()
            recordTerminalImport(ModInstallStatus.CANCELED, e.message ?: "Import canceled")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (downloadCompleted && !extractionCompleted && shouldRedownloadArchiveAfterExtractionFailure(e)) {
                tempArchiveFile.delete()
            }
            tempExtractDir.deleteRecursively()
            val message = NexusImportState.userMessage(
                error = e,
                expiredAuthorizationMessage = context.getString(R.string.nexus_authorization_expired),
                authenticationMessage = context.getString(R.string.nexus_integration_temporarily_unavailable),
            )
            recordTerminalImport(ModInstallStatus.ERROR, message)
            if (e is NexusApiException) throw e
            throw IOException(message, e)
        } finally {
            ModDownloadRegistry.finish(installId)
        }
    }

    suspend fun applyInstall(
        context: Context,
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        allowOverwrite: Boolean,
        saveLastPlacement: Boolean = true,
        preserveStatusOnError: Boolean = false,
        profileId: String = "",
        priority: Int = 0,
        reviewedPlan: ModInstallPlan? = null,
        checkpointHook: (ModDeploymentCheckpoint) -> Unit = {},
    ): ModPlacementResult = ModDeploymentCoordinator.withGameLock(install.appId) {
        applyInstallLocked(
            context = context,
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            allowOverwrite = allowOverwrite,
            saveLastPlacement = saveLastPlacement,
            preserveStatusOnError = preserveStatusOnError,
            profileId = profileId,
            priority = priority,
            reviewedPlan = reviewedPlan,
            checkpointHook = checkpointHook,
        )
    }

    private suspend fun applyInstallLocked(
        context: Context,
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        allowOverwrite: Boolean,
        saveLastPlacement: Boolean,
        preserveStatusOnError: Boolean,
        profileId: String,
        priority: Int,
        reviewedPlan: ModInstallPlan?,
        checkpointHook: (ModDeploymentCheckpoint) -> Unit,
    ): ModPlacementResult = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val existingManifests = dao.getOverwriteManifests(install.installId)
        val existingBackupPaths = existingManifests
            .mapNotNull { it.backupPath.takeIf(String::isNotBlank) }
            .toSet()
        val ownershipRoot = cacheRoot(context, install.appId)
        val previousOwnership = ModOwnershipStore.read(ownershipRoot, install.installId)
        val authoritativePlan = reviewedPlan ?: previousOwnership?.reviewedPlanOrNull()
        val plan = ModMaterializer.materializationPlan(
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            reviewedPlan = authoritativePlan,
        )
        var journal = ModDeploymentJournalStore.begin(ownershipRoot, install.installId, install.appId, plan)
        checkpointHook(ModDeploymentCheckpoint.PLANNED)
        fun advance(checkpoint: ModDeploymentCheckpoint, detail: String = "") {
            journal = ModDeploymentJournalStore.checkpoint(ownershipRoot, journal, checkpoint, detail)
            checkpointHook(checkpoint)
        }
        advance(ModDeploymentCheckpoint.PREPARING)
        advance(ModDeploymentCheckpoint.APPLYING)
        val applied = ModMaterializer.apply(
            install = install,
            plan = plan,
            backupRoot = backupRoot(context, install.appId),
            allowOverwrite = allowOverwrite,
        )
        if (applied.errors.isEmpty()) advance(ModDeploymentCheckpoint.VERIFYING)
        val verification = if (applied.errors.isEmpty()) ModDeploymentVerifier.verify(plan) else ModDeploymentVerification(emptyList())
        val result = if (verification.successful) {
            applied
        } else {
            applied.copy(
                errors = applied.errors + verification.issues.associate { issue ->
                    issue.targetPath to "${issue.type}: ${issue.detail}"
                },
            )
        }
        if (result.errors.isEmpty()) {
            if (result.manifests.isNotEmpty()) {
                dao.replaceOverwriteManifestsForTargets(install.installId, result.manifests)
            }
            val newTargetKeys = plan.files.mapTo(mutableSetOf()) { it.normalizedTargetKey }
            val staleKeys = previousOwnership?.files
                .orEmpty()
                .filter { it.active && it.normalizedTargetKey !in newTargetKeys }
                .mapTo(mutableSetOf()) { it.normalizedTargetKey }
            val staleCleanup = if (previousOwnership != null && staleKeys.isNotEmpty()) {
                ModOwnershipReconciler.removeOwnedFiles(
                    manifest = previousOwnership,
                    overwriteManifests = existingManifests,
                    targetKeys = staleKeys,
                )
            } else {
                ModOwnershipCleanupResult(0, 0, emptyList())
            }
            val safelyReconciledTargets = staleKeys - staleCleanup.preserved.mapTo(mutableSetOf()) { it.normalizedTargetKey }
            if (safelyReconciledTargets.isNotEmpty()) {
                val staleManifestTargets = existingManifests
                    .filter { WindowsPathIdentity.absoluteKey(File(it.targetPath)) in safelyReconciledTargets }
                    .map { it.targetPath }
                if (staleManifestTargets.isNotEmpty()) {
                    dao.deleteOverwriteManifestsForTargets(install.installId, staleManifestTargets)
                }
            }
            val ownership = ModOwnershipStore.create(
                appId = install.appId,
                plan = plan,
                overwriteManifests = existingManifests + result.manifests,
                profileId = profileId,
                priority = priority,
                preservedStale = staleCleanup.preserved,
            )
            ModOwnershipStore.write(ownershipRoot, ownership)
            if (install.status != ModInstallStatus.APPLIED.name) {
                dao.updateInstallStatus(install.installId, ModInstallStatus.APPLIED.name)
            }
            if (saveLastPlacement) saveLastPlacementForApp(install.appId, recipes)
            advance(ModDeploymentCheckpoint.COMMITTED)
            return@withContext result.copy(warnings = staleCleanup.skippedPaths)
        } else if (!preserveStatusOnError && install.status != ModInstallStatus.ERROR.name) {
            dao.updateInstallStatus(install.installId, ModInstallStatus.ERROR.name)
        }

        advance(ModDeploymentCheckpoint.ROLLING_BACK, "Apply or verification failed")
        val restoreSkipped = ModMaterializer.restoreBackups(result.manifests)
        val restoredTargets = result.manifests
            .filter { it.backupPath.isNotBlank() }
            .map { it.targetPath }
            .filterNot { it in restoreSkipped }
            .toSet()
        val removeSkipped = ModMaterializer.rollbackAppliedPlan(
            plan = plan,
            restoredOverwriteTargets = restoredTargets,
        )
        result.manifests
            .filter { it.targetPath in restoredTargets }
            .mapNotNull { it.backupPath.takeIf(String::isNotBlank) }
            .filterNot { it in existingBackupPaths }
            .distinct()
            .forEach { deleteFileBytes(File(it)) }
        val rollbackSkipped = (restoreSkipped + removeSkipped).distinct()
        if (rollbackSkipped.isEmpty()) {
            advance(ModDeploymentCheckpoint.ROLLED_BACK)
            result
        } else {
            advance(ModDeploymentCheckpoint.RECOVERY_REQUIRED, "${rollbackSkipped.size} changed file(s) were preserved")
            result.copy(
                errors = result.errors + ("Rollback" to "${rollbackSkipped.size} changed file(s) left in place after failed apply"),
            )
        }
    }

    suspend fun repairMissingAppliedTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        reviewedPlan: ModInstallPlan? = null,
    ): ModPlacementResult =
        ModDeploymentCoordinator.withGameLock(install.appId) {
            ModMaterializer.repairMissingTargets(
                install = install,
                recipes = recipes,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
                reviewedPlan = reviewedPlan,
            )
        }

    suspend fun restorePreviousDeployment(
        context: Context,
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        profileId: String = "",
        priority: Int = 0,
    ): ModPlacementResult {
        val previous = withContext(Dispatchers.IO) {
            ModOwnershipStore.readPrevious(cacheRoot(context, install.appId), install.installId)
        }
        val plan = previous?.reviewedPlanOrNull()
            ?: return ModPlacementResult(
                created = 0,
                skipped = 0,
                backedUp = 0,
                errors = mapOf(install.modName to "No previous reviewed deployment is available"),
                manifests = emptyList(),
            )
        return applyInstall(
            context = context,
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            allowOverwrite = true,
            saveLastPlacement = false,
            preserveStatusOnError = true,
            profileId = profileId,
            priority = priority,
            reviewedPlan = plan,
        )
    }

    suspend fun adoptHistoricalDeployment(
        context: Context,
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        profileId: String = "",
        priority: Int = 0,
    ): ModPlacementResult = ModDeploymentCoordinator.withGameLock(install.appId) {
        withContext(Dispatchers.IO) {
            val root = cacheRoot(context, install.appId)
            if (install.status != ModInstallStatus.APPLIED.name || ModOwnershipStore.read(root, install.installId) != null) {
                return@withContext ModPlacementResult(
                    created = 0,
                    skipped = 0,
                    backedUp = 0,
                    errors = mapOf(
                        install.modName to
                            "File tracking setup is only available for an older applied mod that is not tracked yet",
                    ),
                    manifests = emptyList(),
                )
            }
            val plan = ModMaterializer.materializationPlan(
                install = install,
                recipes = recipes,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
            )
            val verification = ModDeploymentVerifier.verify(plan)
            if (!plan.isComplete || !verification.successful) {
                return@withContext ModPlacementResult(
                    created = 0,
                    skipped = 0,
                    backedUp = 0,
                    errors = plan.errors + verification.issues.associate { issue ->
                        issue.targetPath to "${issue.type}: ${issue.detail}"
                    },
                    manifests = emptyList(),
                )
            }
            val manifests = dao(context).getOverwriteManifests(install.installId)
            val ownership = ModOwnershipStore.create(
                appId = install.appId,
                plan = plan,
                overwriteManifests = manifests,
                profileId = profileId,
                priority = priority,
            )
            ModOwnershipStore.write(root, ownership)
            ModPlacementResult(0, plan.files.size, 0, emptyMap(), emptyList())
        }
    }

    fun lastPlacementRecipesForApp(appId: String, installId: String): List<ModPlacementRecipe> {
        val root = runCatching { JSONObject(PrefManager.nexusLastPlacementJson) }.getOrElse { JSONObject() }
        val recipes = root.optJSONArray(appId) ?: return emptyList()
        return buildList {
            for (index in 0 until recipes.length()) {
                val recipe = recipes.optJSONObject(index) ?: continue
                add(
                    ModPlacementRecipe(
                        installId = installId,
                        sourceSubpath = recipe.optString("sourceSubpath"),
                        targetRoot = recipe.optString("targetRoot", ModTargetRoot.GAME_DIR.name),
                        targetRelativePath = recipe.optString("targetRelativePath"),
                        targetFileName = recipe.optString("targetFileName"),
                        mode = recipe.optString("mode", ModPlacementMode.SYMLINK.name),
                        stripPrefixSegments = recipe.optInt("stripPrefixSegments", 0),
                        includeSourceDirectory = recipe.optBoolean("includeSourceDirectory", false),
                        enabled = recipe.optBoolean("enabled", true),
                    ),
                )
            }
        }
    }

    fun saveLastPlacementForApp(appId: String, recipes: List<ModPlacementRecipe>) {
        val enabledRecipes = recipes.filter { it.enabled }
        val root = runCatching { JSONObject(PrefManager.nexusLastPlacementJson) }.getOrElse { JSONObject() }
        if (enabledRecipes.isEmpty()) {
            root.remove(appId)
            PrefManager.nexusLastPlacementJson = root.toString()
            return
        }
        val savedRecipes = JSONArray()
        enabledRecipes.forEach { recipe ->
            savedRecipes.put(
                JSONObject()
                    .put("sourceSubpath", recipe.sourceSubpath)
                    .put("targetRoot", recipe.targetRoot)
                    .put("targetRelativePath", recipe.targetRelativePath)
                    .put("targetFileName", recipe.targetFileName)
                    .put("mode", recipe.mode)
                    .put("stripPrefixSegments", recipe.stripPrefixSegments)
                    .put("includeSourceDirectory", recipe.includeSourceDirectory)
                    .put("enabled", recipe.enabled),
            )
        }
        root.put(appId, savedRecipes)
        PrefManager.nexusLastPlacementJson = root.toString()
    }

    suspend fun disableInstall(
        context: Context,
        install: ModInstall,
        restoreBackups: Boolean,
        gameRootDir: File? = null,
        winePrefix: String = ModContainerResolver.getWinePrefix(context, install.appId),
    ): List<String> = ModDeploymentCoordinator.withGameLock(install.appId) {
        disableInstallLocked(context, install, restoreBackups, gameRootDir, winePrefix)
    }

    private suspend fun disableInstallLocked(
        context: Context,
        install: ModInstall,
        restoreBackups: Boolean,
        gameRootDir: File?,
        winePrefix: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val dao = dao(context)
        if (install.status != ModInstallStatus.APPLIED.name) {
            dao.updateInstallEnabled(install.installId, false, ModInstallStatus.DISABLED.name)
            return@withContext emptyList()
        }
        val manifests = dao.getOverwriteManifests(install.installId)
        val ownershipRoot = cacheRoot(context, install.appId)
        val ownership = ModOwnershipStore.read(ownershipRoot, install.installId)
        if (ownership == null) {
            dao.updateInstallEnabled(install.installId, false, ModInstallStatus.DISABLED.name)
            return@withContext listOf("Verify and track this mod's installed files before disabling or removing it safely")
        }
        val cleanup = ModOwnershipReconciler.removeOwnedFiles(ownership, manifests, restoreBackups = restoreBackups)
        val preservedByTarget = cleanup.preserved.associateBy { it.normalizedTargetKey }
        ModOwnershipStore.write(
            ownershipRoot,
            ownership.copy(
                state = ModOwnershipState.DISABLED,
                files = ownership.files.map { file ->
                    preservedByTarget[file.normalizedTargetKey] ?: file.copy(active = false)
                },
            ),
        )
        dao.updateInstallEnabled(install.installId, false, ModInstallStatus.DISABLED.name)
        cleanup.skippedPaths
    }

    suspend fun deleteInstall(
        context: Context,
        install: ModInstall,
        restoreBackups: Boolean,
        gameRootDir: File? = null,
        winePrefix: String = ModContainerResolver.getWinePrefix(context, install.appId),
    ): List<String> = ModDeploymentCoordinator.withGameLock(install.appId) {
        val skipped = disableInstallLocked(context, install, restoreBackups, gameRootDir, winePrefix)
        withContext(Dispatchers.IO) {
            val dao = dao(context)
            dao.deleteOverwriteManifests(install.installId)
            dao.deleteInstall(install.installId)
            val installCacheRoot = cacheRoot(context, install.appId)
            ModOwnershipStore.delete(installCacheRoot, install.installId)
            ModDeploymentJournalStore.delete(installCacheRoot, install.installId)
            if (install.archivePath.isNotBlank()) {
                val archiveFile = File(install.archivePath)
                archiveFile.delete()
                archiveFile.parentFile?.let { File(it, "${archiveFile.name}.part").delete() }
            }
            File(install.extractedPath).deleteRecursively()
            File("${install.extractedPath}.tmp").deleteRecursively()
            File("${install.extractedPath}.previous").deleteRecursively()
            File(backupRoot(context, install.appId), install.installId).deleteRecursively()
            skipped
        }
    }

    suspend fun deleteInstallsForApp(
        context: Context,
        appId: String,
        restoreBackups: Boolean = true,
        gameRootDir: File? = null,
        winePrefix: String = ModContainerResolver.getWinePrefix(context, appId),
    ): List<String> = withContext(Dispatchers.IO) {
        val installs = dao(context).getInstallsForApp(appId)
        val skipped = installs.flatMap { install ->
            deleteInstall(
                context = context,
                install = install,
                restoreBackups = restoreBackups,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
            )
        }
        cacheRoot(context, appId).deleteRecursively()
        backupRoot(context, appId).deleteRecursively()
        skipped
    }

    suspend fun cleanupOrphanedFilesForApp(context: Context, appId: String): ModCleanupResult = withContext(Dispatchers.IO) {
        val root = cacheRoot(context, appId)
        val archiveFiles = File(root, "archives").listFiles().orEmpty()
        val extractedFiles = File(root, "extracted").listFiles().orEmpty()
        val backupFiles = backupRoot(context, appId).listFiles().orEmpty()
        val installs = dao(context).getInstallsForApp(appId)
        val installIds = installs.map { it.installId }.toSet()
        val protectedImportIds = protectedExtractedImportIds(context, installs)
        var reclaimedBytes = 0L

        val referencedArchives = installs
            .filter { it.status in resumableImportStatuses || (KEEP_IMPORTED_ARCHIVES && it.status in reusableStatuses) }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .toSet()
        val referencedPartials = installs
            .filter { it.status in resumableImportStatuses || it.status == ModInstallStatus.ERROR.name }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .map { File(it.parentFile, "${it.name}.part") }
            .toSet()
        fun ownedByActiveImport(file: File): Boolean =
            ModDownloadRegistry.observeDownloads().value.values.any {
                it.appId == appId &&
                    (file.name == it.installId || file.name.startsWith("${it.installId}_"))
            }
        archiveFiles.forEach { file ->
            when {
                ownedByActiveImport(file) -> Unit
                file.extension.equals("part", ignoreCase = true) && file !in referencedPartials ->
                    reclaimedBytes += deleteFileBytes(file)
                file.isFile && file !in referencedArchives ->
                    reclaimedBytes += deleteFileBytes(file)
            }
        }

        extractedFiles.forEach { file ->
            val transientImportId = transientExtractedImportId(file)
            when {
                transientImportId != null -> {
                    if (
                        transientImportId !in protectedImportIds &&
                        ModDownloadRegistry.get(transientImportId) == null
                    ) {
                        reclaimedBytes += deleteDirectoryBytes(file)
                    }
                }
                file.isDirectory &&
                    file.name !in installIds &&
                    ModDownloadRegistry.get(file.name) == null ->
                    reclaimedBytes += deleteDirectoryBytes(file)
            }
        }

        backupFiles.forEach { backup ->
            if (backup.isDirectory && backup.name !in installIds) {
                reclaimedBytes += deleteDirectoryBytes(backup)
            }
        }

        ModCleanupResult(reclaimedBytes)
    }

    suspend fun cleanupFailedArchivesForApp(context: Context, appId: String): ModCleanupResult = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val installs = dao.getInstallsForApp(appId)
        val reclaimedBytes = installs
            .filter { it.status == ModInstallStatus.ERROR.name }
            .sumOf { install ->
                val importActiveBeforeRefresh =
                    ModDownloadRegistry.get(install.installId) != null
                val stillFailed =
                    dao.getInstall(install.installId)?.status == ModInstallStatus.ERROR.name
                val importActiveAfterRefresh =
                    ModDownloadRegistry.get(install.installId) != null
                if (importActiveBeforeRefresh || !stillFailed || importActiveAfterRefresh) {
                    return@sumOf 0L
                }
                val archive = install.archivePath.takeIf(String::isNotBlank)?.let(::File)
                deleteFileBytes(archive) + deleteFileBytes(archive?.let(::partialFileFor))
            }
        ModCleanupResult(reclaimedBytes)
    }

    suspend fun cleanupRedundantBackupsForApp(context: Context, appId: String): ModCleanupResult = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val manifests = dao.getRedundantOverwriteManifestsForApp(appId)
        val backupRoot = backupRoot(context, appId)
        val safePaths = safeBackupPaths(backupRoot, manifests.map { it.backupPath }).toSet()
        val reclaimedBytes = safePaths.sumOf { deleteFileBytes(File(it)) }
        manifests
            .filter { manifest -> safeBackupPathOrNull(backupRoot, manifest.backupPath) in safePaths }
            .map { it.manifestId }
            .chunked(500)
            .forEach { dao.deleteOverwriteManifestsByIds(it) }
        deleteEmptyDirectories(backupRoot)
        ModCleanupResult(reclaimedBytes)
    }

    suspend fun scanStorageForApp(context: Context, appId: String): ModStorageBreakdown = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val installs = dao.getInstallsForApp(appId)
        val redundantBackupManifests = dao.getRedundantOverwriteManifestsForApp(appId)
        val safeRedundantBackupPaths = safeBackupPaths(backupRoot(context, appId), redundantBackupManifests.map { it.backupPath })
        val redundantBackups = safeRedundantBackupPaths.sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        val installIds = installs.map { it.installId }.toSet()
        val activeImportIds = protectedExtractedImportIds(context, installs)
            .toMutableSet()
            .apply {
                addAll(
                    ModDownloadRegistry.observeDownloads().value.values
                        .filter { it.appId == appId }
                        .map { it.installId },
                )
            }
        val failedArchives = installs
            .filter { it.status == ModInstallStatus.ERROR.name }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .flatMap { listOfNotNull(it, partialFileFor(it)) }
            .toSet()
        val referencedArchives = installs
            .filter { it.status in resumableImportStatuses || (KEEP_IMPORTED_ARCHIVES && it.status in reusableStatuses) }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .toSet()
        val referencedPartials = installs
            .filter { it.status in resumableImportStatuses || it.status == ModInstallStatus.ERROR.name }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File)?.let(::partialFileFor) }
            .toSet()
        var cleanable = 0L
        var failedArchiveBytes = 0L
        File(cacheRoot(context, appId), "archives").listFiles().orEmpty().forEach { file ->
            when {
                file in failedArchives -> failedArchiveBytes += file.length()
                file.extension.equals("part", ignoreCase = true) && file !in referencedPartials -> cleanable += file.length()
                file.isFile && file !in referencedArchives -> cleanable += file.length()
            }
        }
        var extractedCache = 0L
        File(cacheRoot(context, appId), "extracted").listFiles().orEmpty().forEach { file ->
            val transientImportId = transientExtractedImportId(file)
            when {
                transientImportId != null -> {
                    val size = directorySize(file)
                    if (transientImportId !in activeImportIds) {
                        cleanable += size
                    } else {
                        extractedCache += size
                    }
                }
                file.isDirectory &&
                    file.name !in installIds &&
                    file.name !in activeImportIds ->
                    cleanable += directorySize(file)
                file.isDirectory && !file.name.endsWith(".tmp") -> extractedCache += directorySize(file)
            }
        }
        var backups = 0L
        backupRoot(context, appId).listFiles().orEmpty().forEach { backup ->
            if (backup.isDirectory && backup.name in installIds) backups += directorySize(backup) else cleanable += directorySize(backup)
        }
        ModStorageBreakdown(cleanable, failedArchiveBytes, extractedCache, backups, redundantBackups, safeRedundantBackupPaths.size)
    }

    private fun transientExtractedImportId(file: File): String? =
        listOf(".tmp", ".previous")
            .firstOrNull { file.name.endsWith(it) }
            ?.let { file.name.removeSuffix(it) }

    private fun protectedExtractedImportIds(
        context: Context,
        installs: List<ModInstall>,
    ): Set<String> = installs
        .filter { install ->
            when {
                install.status in resumableImportStatuses -> true
                install.status != ModInstallStatus.ERROR.name ||
                    !ModInstallSource.isLocal(install.source) -> false
                hasCompletePendingLocalContent(context, install) -> true
                else -> NexusImportState.restorablePreviousInstall(install) != null &&
                    hasUsableExtractedContent(localContentPaths(context, install).previous)
            }
        }
        .mapTo(mutableSetOf()) { it.installId }

    suspend fun checkInstallHealthForApp(
        context: Context,
        appId: String,
        gameRootDir: File?,
        winePrefix: String,
    ): ModHealthReport = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val installs = dao.getInstallsForApp(appId)
        val installIds = installs.map { it.installId }.toSet()
        val backupRoot = backupRoot(context, appId)
        val issues = mutableListOf<ModHealthIssue>()
        var missingBackupCount = 0
        var redundantMissingBackupCount = 0
        var unsafeBackupCount = 0
        val missingBackupTargets = mutableListOf<String>()
        val unsafeBackupExamples = mutableListOf<String>()

        fun add(
            severity: ModHealthSeverity,
            title: String,
            detail: String,
            install: ModInstall? = null,
            action: ModHealthAction = ModHealthAction.REBUILD_PROFILE,
        ) {
            issues += ModHealthIssue(
                severity,
                title,
                detail,
                install?.modName.orEmpty(),
                install?.installId.orEmpty(),
                action,
            )
        }

        val ownershipRoot = cacheRoot(context, appId)
        val journals = ModDeploymentCoordinator.withGameLock(appId) {
            ModDeploymentJournalStore.reconcile(ownershipRoot)
        }.associateBy { it.installId }
        val ownershipByInstallId = installs.mapNotNull { install ->
            ModOwnershipStore.read(ownershipRoot, install.installId)?.let { install.installId to it }
        }.toMap()
        val activeProfile = dao.getActiveProfileForApp(appId)
        val enabledPriorities = activeProfile?.let { profile ->
            dao.getProfileInstallStates(appId, profile.profileId)
                .filter { it.enabled }
                .associate { it.installId to it.priority }
        }.orEmpty()
        val overlay = ModProfileOverlayPlanner.build(ownershipByInstallId.values.toList(), enabledPriorities)
        val overlayFindings = ModDeploymentVerifier.verify(
            overlay,
            ModVerificationDepth.CHANGED_CONTENT,
        ).issues
        val overlayInstallIds = overlay.targets.values.asSequence()
            .flatMap { it.contributors.asSequence() }
            .mapTo(mutableSetOf()) { it.installId }

        installs.forEach { install ->
            val status = runCatching { ModInstallStatus.valueOf(install.status) }.getOrNull()
            val extracted = File(install.extractedPath)
            val recipes = dao.getRecipesForInstall(install.installId)
            val manifests = dao.getOverwriteManifests(install.installId)
            val ownership = ownershipByInstallId[install.installId]
            val journal = journals[install.installId]

            if (journal?.checkpoint == ModDeploymentCheckpoint.RECOVERY_REQUIRED) {
                val canRestore = ModOwnershipStore.readPrevious(ownershipRoot, install.installId)?.reviewedPlanOrNull() != null
                add(
                    ModHealthSeverity.ERROR,
                    "Deployment recovery is required",
                    journal.detail,
                    install,
                    if (canRestore) ModHealthAction.RESTORE_PREVIOUS else ModHealthAction.RECONFIGURE,
                )
            }

            if (status == null) {
                add(ModHealthSeverity.ERROR, "Unknown install status", install.status, install)
            }
            if (install.status in reusableStatuses && !extracted.isDirectory) {
                add(
                    if (install.status == ModInstallStatus.APPLIED.name) ModHealthSeverity.ERROR else ModHealthSeverity.WARNING,
                    "Extracted cache is missing",
                    "Reapply, disable/delete, or placement changes may require redownloading this mod.",
                    install,
                )
            }
            if (install.status == ModInstallStatus.APPLIED.name) {
                if (recipes.none { it.enabled }) {
                    add(ModHealthSeverity.ERROR, "Applied mod has no placement recipe", "GameNative cannot verify or safely remove deployed files.", install)
                } else if (ownership == null) {
                    add(
                        ModHealthSeverity.WARNING,
                        "Finish tracking installed files",
                        "This mod was installed before file tracking was added. Verify the files already in place so GameNative can disable or remove the mod safely. No files will be moved or replaced.",
                        install,
                        ModHealthAction.ADOPT_OWNERSHIP,
                    )
                } else if (ownership.state != ModOwnershipState.ACTIVE) {
                    add(ModHealthSeverity.ERROR, "Ownership state does not match the applied mod", ownership.state.name, install)
                } else {
                    val findingsForInstall = overlayFindings.filter { it.installId == install.installId } +
                        ModDeploymentVerifier.verifyStale(ownership).issues
                    findingsForInstall.groupBy { it.type }.forEach { (type, findings) ->
                        val severity = if (type == ModVerificationIssueType.STALE) ModHealthSeverity.WARNING else ModHealthSeverity.ERROR
                        add(
                            severity,
                            when (type) {
                                ModVerificationIssueType.MISSING -> "Managed files are missing"
                                ModVerificationIssueType.MODIFIED -> "Managed files were modified"
                                ModVerificationIssueType.WRONG_CASE -> "Managed files have unexpected casing"
                                ModVerificationIssueType.AMBIGUOUS -> "Case-ambiguous target files exist"
                                ModVerificationIssueType.STALE -> "Stale managed files were preserved"
                                ModVerificationIssueType.OWNERSHIP -> "Ownership records need review"
                            },
                            findings.take(3).joinToString("\n") { it.targetPath },
                            install,
                            if (type == ModVerificationIssueType.MISSING) {
                                ModHealthAction.REAPPLY_MISSING
                            } else {
                                ModHealthAction.RECONFIGURE
                            },
                        )
                    }
                }
                if ((ownership == null || install.installId !in overlayInstallIds) && extracted.isDirectory) {
                    val missing = missingAppliedTargets(install, recipes, gameRootDir, winePrefix).take(3)
                    if (missing.isNotEmpty()) {
                        add(
                            ModHealthSeverity.ERROR,
                            "Some mod files are missing from the game folder",
                            "Apply order can restore them if the mod cache is still available.\n${missing.joinToString("\n")}",
                            install,
                            ModHealthAction.REAPPLY_MISSING,
                        )
                    }
                }
            } else if (ownership != null) {
                val preserved = ownership.copy(
                    files = ownership.files.filter { file ->
                        file.normalizedTargetKey !in overlay.targets
                    },
                )
                val findings = ModDeploymentVerifier.verifyStale(preserved).issues
                if (findings.isNotEmpty()) {
                    add(
                        ModHealthSeverity.WARNING,
                        "Disabled mod still has changed files in the game folder",
                        buildString {
                            append("${findings.size} changed file(s) were kept to avoid deleting user changes. ")
                            append("They can still affect the game while this mod is disabled. ")
                            append("Review the placement to decide what to keep or remove; Apply order will not remove them.")
                            findings.take(3).forEach { finding -> append("\n${finding.targetPath}") }
                        },
                        install,
                        ModHealthAction.REVIEW_PLACEMENT,
                    )
                }
            }
            if (install.status == ModInstallStatus.READY.name && manifests.isNotEmpty()) {
                add(ModHealthSeverity.WARNING, "Ready mod has overwrite records", "This mod is not applied but still has ${manifests.size} overwrite record(s).", install)
            }
            manifests.forEach { manifest ->
                if (manifest.backupPath.isBlank()) return@forEach
                val safePath = safeBackupPathOrNull(backupRoot, manifest.backupPath)
                when {
                    safePath == null -> {
                        unsafeBackupCount++
                        if (unsafeBackupExamples.size < 3) unsafeBackupExamples += manifest.backupPath
                    }
                    !File(safePath).isFile && manifest.originalHash.isNotBlank() && manifest.originalHash == manifest.installedHash -> {
                        redundantMissingBackupCount++
                    }
                    !File(safePath).isFile -> {
                        missingBackupCount++
                        missingBackupTargets += manifest.targetPath
                    }
                }
            }
        }

        if (unsafeBackupCount > 0) {
            add(
                ModHealthSeverity.ERROR,
                "Unsafe backup records",
                "$unsafeBackupCount backup record(s) point outside GameNative's backup folder.\n${unsafeBackupExamples.joinToString("\n")}",
            )
        }
        if (missingBackupCount > 0) {
            add(
                ModHealthSeverity.WARNING,
                "Backup files are missing",
                "$missingBackupCount non-redundant backup file(s) are missing. Disable/delete may not restore original files for those targets. If you need the originals back, repair/verify the game files before disabling these mods.\n\nMissing backup targets:\n${missingBackupTargets.joinToString("\n")}",
            )
        }
        if (redundantMissingBackupCount > 0) {
            add(
                ModHealthSeverity.WARNING,
                "Stale redundant backup records",
                "$redundantMissingBackupCount redundant backup record(s) point to already-cleaned files. Storage cleanup may show 0 B because only database records remain; use Clean redundant backups to remove them.",
            )
        }

        val extractedDir = File(cacheRoot(context, appId), "extracted")
        extractedDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".tmp") && it.name !in installIds }
            .take(3)
            .forEach { add(ModHealthSeverity.WARNING, "Orphaned extracted cache", it.name) }

        val ownershipProducers = ownershipByInstallId.values
            .groupingBy { "${it.planProducerId}@${it.planProducerVersion}" }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
            .ifBlank { "none" }
        val journalCheckpoints = journals.values
            .groupingBy { it.checkpoint.name }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
            .ifBlank { "none" }

        ModHealthReport(
            issues = issues,
            facts = listOf(
                "installs=${installs.size}",
                "ownership-manifests=${ownershipByInstallId.size}",
                "ownership-producers=$ownershipProducers",
                "deployment-journals=${journals.size}",
                "journal-checkpoints=$journalCheckpoints",
                "profile-enabled=${enabledPriorities.size}",
                "overlay-targets=${overlay.targets.size}",
            ),
        )
    }

    suspend fun reconcilePendingDeploymentsForApp(context: Context, appId: String): List<ModDeploymentJournal> =
        ModDeploymentCoordinator.withGameLock(appId) {
            withContext(Dispatchers.IO) { ModDeploymentJournalStore.reconcile(cacheRoot(context, appId)) }
        }

    fun hasMissingAppliedTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        reviewedPlan: ModInstallPlan? = null,
    ): Boolean =
        install.status == ModInstallStatus.APPLIED.name &&
            recipes.any { it.enabled } &&
            missingAppliedTargets(install, recipes, gameRootDir, winePrefix, reviewedPlan).isNotEmpty()

    suspend fun archiveEntries(install: ModInstall): List<ModArchiveEntry> =
        withContext(Dispatchers.IO) { ModArchiveExtractor.listExtractedEntries(File(install.extractedPath)) }

    private fun installId(appId: String, gameDomain: String, modId: Long, fileId: Long): String =
        "${sanitizeFileName(appId)}_${sanitizeFileName(gameDomain)}_${modId}_$fileId"

    private val reusableStatuses = NexusImportState.reusableStatuses

    val resumableImportStatuses = NexusImportState.resumableImportStatuses

    private fun moveFileOrDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        if (source.isDirectory) {
            if (!source.copyRecursively(target, overwrite = true)) {
                throw IOException("Failed to move extracted mod files")
            }
            source.deleteRecursively()
        } else {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun download(
        installId: String,
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (ModImportProgress) -> Unit,
    ) {
        ensureDownloadNetworkAllowed()
        destination.parentFile?.mkdirs()
        var existingBytes = if (destination.isFile) destination.length() else 0L
        if (expectedBytes > 0L && existingBytes == expectedBytes) {
            onProgress(
                ModImportProgress(
                    status = "Downloading",
                    progress = 1f,
                    downloadedBytes = existingBytes,
                    totalBytes = expectedBytes,
                ),
            )
            return
        }
        if (expectedBytes > 0L && existingBytes > expectedBytes) {
            destination.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val request = requestBuilder.build()
        downloadClient.newCall(request).execute().use { response ->
            ensureDownloadNetworkAllowed()
            if (ModDownloadRegistry.isCancelRequested(installId)) {
                throw ModImportCanceledException("Import canceled")
            }
            if (response.code == 416 && existingBytes > 0L) {
                val remoteBytes = response.header("Content-Range")
                    ?.substringAfter("*/", "")
                    ?.toLongOrNull()
                if (remoteBytes == existingBytes || (remoteBytes == null && expectedBytes > 0L && existingBytes >= expectedBytes)) {
                    onProgress(
                        ModImportProgress(
                            status = "Downloading",
                            progress = 1f,
                            downloadedBytes = existingBytes,
                            totalBytes = existingBytes,
                        ),
                    )
                    return
                }
            }
            if (response.code == 416 && expectedBytes > 0L && existingBytes >= expectedBytes) {
                onProgress(
                    ModImportProgress(
                        status = "Downloading",
                        progress = 1f,
                        downloadedBytes = existingBytes,
                        totalBytes = expectedBytes,
                    ),
                )
                return
            }
            if (!response.isSuccessful) throw NexusApiException("Download failed (${response.code})", response.code)
            val append = existingBytes > 0L && response.code == 206
            if (existingBytes > 0L && !append) {
                destination.delete()
                existingBytes = 0L
            }
            val responseLength = response.body.contentLength().takeIf { it > 0 }
            val total = when {
                append && responseLength != null -> existingBytes + responseLength
                responseLength != null -> responseLength
                expectedBytes > 0L -> expectedBytes
                else -> 0L
            }
            var downloaded = existingBytes
            onProgress(
                ModImportProgress(
                    status = "Downloading",
                    progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                ),
            )
            response.body.byteStream().use { input ->
                FileOutputStream(destination, append).buffered(DOWNLOAD_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        ensureDownloadNetworkAllowed()
                        if (ModDownloadRegistry.isCancelRequested(installId)) {
                            throw ModImportCanceledException("Import canceled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (total > 0L) {
                            (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        onProgress(
                            ModImportProgress(
                                status = "Downloading",
                                progress = progress,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            ),
                        )
                    }
                }
            }
            if (total > 0L && downloaded < total) {
                throw IOException("Download ended early ($downloaded of $total bytes)")
            }
        }
        onProgress(
            ModImportProgress(
                status = "Downloading",
                progress = 1f,
                downloadedBytes = destination.length(),
                totalBytes = destination.length(),
            ),
        )
    }

    private fun ensureDownloadNetworkAllowed() {
        if (PrefManager.downloadOnWifiOnly && !NetworkMonitor.hasWifiOrEthernet.value) {
            throw ModImportPausedException("Download paused because Wi-Fi/LAN-only downloads are enabled")
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { "mod" }
    }

    private fun estimateImportScratchBytes(sizeBytes: Long): Long {
        val archiveBytes = sizeBytes.takeIf { it > 0L } ?: UNKNOWN_IMPORT_BYTES
        val extractedBytes = estimatedExtractedBytes(archiveBytes)
        return if (Long.MAX_VALUE - archiveBytes < extractedBytes) Long.MAX_VALUE else archiveBytes + extractedBytes
    }

    private fun estimatedExtractedBytes(archiveBytes: Long): Long =
        safeMultiply(archiveBytes, ESTIMATED_EXTRACTED_MULTIPLIER)

    private fun safeMultiply(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

    private fun shouldRedownloadArchiveAfterExtractionFailure(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return listOf(
            "checksum",
            "corrupt",
            "truncated",
            "unexpected end",
            "invalid header",
            "download ended early",
        ).any(message::contains)
    }

    private fun missingAppliedTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        reviewedPlan: ModInstallPlan? = null,
    ): List<String> {
        val missing = mutableListOf<String>()
        val plan = ModMaterializer.materializationPlan(
            install,
            recipes,
            gameRootDir,
            winePrefix,
            captureTargetHashes = false,
            reviewedPlan = reviewedPlan,
        )
        missing += plan.errors.values
        plan.operations.filter { it.mode == ModPlacementMode.SYMLINK }.forEach { entry ->
            if (!Files.isSymbolicLink(entry.target.toPath())) {
                missing += entry.target.absolutePath
            }
            if (missing.size >= 3) return missing.take(3)
        }
        plan.files.filter { it.mode != ModPlacementMode.SYMLINK }.forEach { file ->
            if (!file.target.isFile) missing += file.target.absolutePath
            if (missing.size >= 3) return missing.take(3)
        }
        return missing
    }

    private fun partialFileFor(archiveFile: File): File? =
        archiveFile.parentFile?.let { File(it, "${archiveFile.name}.part") }

    private fun deleteFileBytes(file: File?): Long {
        if (file?.isFile != true) return 0L
        val size = file.length()
        return if (file.delete()) size else 0L
    }

    private fun deleteDirectoryBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val size = directorySize(dir)
        return if (dir.deleteRecursively()) size else 0L
    }

    private fun safeBackupPaths(root: File, paths: Iterable<String>): List<String> =
        paths.mapNotNull { safeBackupPathOrNull(root, it) }.distinct()

    private fun safeBackupPathOrNull(root: File, path: String): String? {
        return runCatching {
            val rootPath = root.canonicalFile.toPath()
            File(path).canonicalFile
                .takeIf { file -> file.toPath().startsWith(rootPath) }
                ?.absolutePath
        }.getOrNull()
    }

    private fun deleteEmptyDirectories(root: File) {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach(::deleteEmptyDirectories)
        if (root.listFiles()?.isEmpty() == true) root.delete()
    }

    internal fun directorySize(dir: File): Long =
        dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    internal fun hasUsableExtractedContent(dir: File): Boolean =
        dir.isDirectory && dir.walkTopDown().any(File::isFile)

}
