package app.gamenative.mods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

data class ModArchiveEntry(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
)

data class ModArchiveExtractionResult(
    val destination: File,
    val entries: List<ModArchiveEntry>,
)

data class ModArchiveExtractionProgress(
    val format: String,
    val entriesProcessed: Int,
    val totalEntries: Int,
    val extractedBytes: Long,
    val totalBytes: Long,
    val currentPath: String = "",
)

class UnsupportedModArchiveException(message: String) : IOException(message)

object ModArchiveExtractor {
    // Hard ceilings prevent archive bombs and bound memory, disk, and path handling costs.
    // Keep these aligned with LocalModImporter so switching source types cannot bypass them.
    private const val MAX_ENTRIES = ModImportSafetyLimits.MAX_ENTRIES
    private const val MAX_EXPANDED_BYTES = ModImportSafetyLimits.MAX_CONTENT_BYTES
    private const val MAX_RELATIVE_PATH_LENGTH = ModImportSafetyLimits.MAX_RELATIVE_PATH_LENGTH
    private const val MAX_PATH_SEGMENTS =
        ModImportSafetyLimits.MAX_DIRECTORY_DEPTH + 1 // Directory levels plus a file name.
    private const val ARCHIVE_READ_BLOCK_SIZE = 1024 * 1024
    private val supportedArchiveExtensions = setOf("zip", "7z", "rar", "exe")

    suspend fun extract(
        archiveFile: File,
        destination: File,
        preservedSingleFileName: String? = null,
        onProgress: (ModArchiveExtractionProgress) -> Unit = {},
    ): ModArchiveExtractionResult =
        withContext(Dispatchers.IO) {
            if (!archiveFile.isFile) throw IOException("Archive does not exist: ${archiveFile.absolutePath}")
            if (destination.exists() && !destination.deleteRecursively()) {
                throw IOException("Could not clear extraction directory: ${destination.absolutePath}")
            }
            if (!destination.mkdirs() && !destination.isDirectory) {
                throw IOException("Could not create extraction directory: ${destination.absolutePath}")
            }

            val archiveExtension = archiveExtension(archiveFile)
            val startedAt = System.nanoTime()
            val entries = try {
                when (archiveExtension) {
                    "zip" -> extractZip(archiveFile, destination, onProgress)
                    "7z" -> extractSevenZip(archiveFile, destination, onProgress)
                    "rar" -> extractRar(archiveFile, destination, onProgress)
                    "exe" -> preserveExecutableFile(archiveFile, destination, preservedSingleFileName, onProgress)
                    else -> throw UnsupportedModArchiveException("Unsupported archive type: .$archiveExtension")
                }
            } catch (e: Exception) {
                destination.deleteRecursively()
                throw e
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            Timber.i(
                "Extracted mod archive format=%s archiveBytes=%d extractedBytes=%d entries=%d elapsedMs=%d",
                archiveExtension,
                archiveFile.length(),
                entries.sumOf { it.sizeBytes },
                entries.size,
                elapsedMs,
            )
            ModArchiveExtractionResult(
                destination = destination,
                entries = entries.sortedBy { it.path.lowercase() },
            )
        }

    private fun archiveExtension(archiveFile: File): String {
        // Keep .exe installers as single placeable files, but allow nonstandard Nexus
        // extensions to fall back to safe archive header detection.
        val extension = archiveFile.extension.lowercase()
        val inferred = if (extension == "part") {
            File(archiveFile.nameWithoutExtension).extension.lowercase()
        } else {
            extension
        }
        if (inferred == "exe") return inferred
        if (inferred in supportedArchiveExtensions) return inferred
        return archiveExtensionFromHeader(archiveFile).ifBlank { inferred }
    }

    private fun archiveExtensionFromHeader(archiveFile: File): String {
        val header = ByteArray(8)
        val read = archiveFile.inputStream().use { it.read(header) }
        if (read < 4) return ""
        return when {
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> "zip"
            read >= 6 &&
                header[0] == 0x37.toByte() &&
                header[1] == 0x7A.toByte() &&
                header[2] == 0xBC.toByte() &&
                header[3] == 0xAF.toByte() &&
                header[4] == 0x27.toByte() &&
                header[5] == 0x1C.toByte() -> "7z"
            read >= 7 &&
                header[0] == 0x52.toByte() &&
                header[1] == 0x61.toByte() &&
                header[2] == 0x72.toByte() &&
                header[3] == 0x21.toByte() &&
                header[4] == 0x1A.toByte() &&
                header[5] == 0x07.toByte() -> "rar"
            else -> ""
        }
    }

    fun listExtractedEntries(root: File): List<ModArchiveEntry> {
        if (!root.isDirectory) return emptyList()
        val base = root.canonicalFile
        return root.walkTopDown()
            .filter { it != root }
            .take(MAX_ENTRIES)
            .mapNotNull { file ->
                val rel = file.canonicalFile.relativeToOrNull(base)?.invariantSeparatorsPath ?: return@mapNotNull null
                ModArchiveEntry(rel, file.isDirectory, if (file.isFile) file.length() else 0L)
            }
            .toList()
            .sortedBy { it.path.lowercase() }
    }

    private fun extractZip(
        archiveFile: File,
        destination: File,
        onProgress: (ModArchiveExtractionProgress) -> Unit,
    ): List<ModArchiveEntry> {
        val entries = mutableListOf<ModArchiveEntry>()
        var expandedBytes = 0L
        ZipFile(archiveFile).use { zip ->
            val totalEntries = zip.size()
            if (totalEntries > MAX_ENTRIES) throw IOException("Archive has too many entries")
            var totalBytes = 0L
            zip.entries().asSequence().forEach { entry ->
                if (!entry.isDirectory && entry.size > 0L) {
                    totalBytes += entry.size
                    if (totalBytes > MAX_EXPANDED_BYTES) {
                        throw IOException("Archive expands beyond the safety limit")
                    }
                }
            }
            zip.entries().asSequence().forEach { entry ->
                if (entries.size >= MAX_ENTRIES) throw IOException("Archive has too many entries")
                val outFile = safeDestination(destination, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    entries += ModArchiveEntry(normalizeArchivePath(entry.name), true, 0L)
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).buffered(ARCHIVE_READ_BLOCK_SIZE).use { input ->
                        FileOutputStream(outFile).buffered(ARCHIVE_READ_BLOCK_SIZE).use { output ->
                            val buffer = ByteArray(ARCHIVE_READ_BLOCK_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                expandedBytes += read
                                if (expandedBytes > MAX_EXPANDED_BYTES) {
                                    throw IOException("Archive expands beyond the safety limit")
                                }
                                output.write(buffer, 0, read)
                                emitProgress(onProgress, "zip", entries.size, totalEntries, expandedBytes, totalBytes, entry.name)
                            }
                        }
                    }
                    entries += ModArchiveEntry(normalizeArchivePath(entry.name), false, outFile.length())
                }
                emitProgress(onProgress, "zip", entries.size, totalEntries, expandedBytes, totalBytes, entry.name)
            }
        }
        return entries
    }

    private fun extractSevenZip(
        archiveFile: File,
        destination: File,
        onProgress: (ModArchiveExtractionProgress) -> Unit,
    ): List<ModArchiveEntry> =
        extractLibarchive(archiveFile, destination, "7z", onProgress) { Archive.readSupportFormat7zip(it) }

    private fun extractRar(
        archiveFile: File,
        destination: File,
        onProgress: (ModArchiveExtractionProgress) -> Unit,
    ): List<ModArchiveEntry> =
        extractLibarchive(archiveFile, destination, "rar", onProgress) {
            Archive.readSupportFormatRar(it)
            Archive.readSupportFormatRar5(it)
        }

    private fun preserveExecutableFile(
        archiveFile: File,
        destination: File,
        preservedFileName: String?,
        onProgress: (ModArchiveExtractionProgress) -> Unit,
    ): List<ModArchiveEntry> {
        val fileName = sanitizePreservedFileName(preservedFileName) ?: archiveFile.name.removeSuffix(".part")
        val outFile = safeDestination(destination, fileName)
        outFile.parentFile?.mkdirs()
        var copiedBytes = 0L
        archiveFile.inputStream().buffered(ARCHIVE_READ_BLOCK_SIZE).use { input ->
            FileOutputStream(outFile).buffered(ARCHIVE_READ_BLOCK_SIZE).use { output ->
                val buffer = ByteArray(ARCHIVE_READ_BLOCK_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copiedBytes += read
                    if (copiedBytes > MAX_EXPANDED_BYTES) {
                        throw IOException("Archive expands beyond the safety limit")
                    }
                    output.write(buffer, 0, read)
                    emitProgress(onProgress, "exe", 0, 1, copiedBytes, archiveFile.length(), fileName)
                }
            }
        }
        emitProgress(onProgress, "exe", 1, 1, copiedBytes, archiveFile.length(), fileName)
        return listOf(ModArchiveEntry(normalizeArchivePath(fileName), directory = false, sizeBytes = outFile.length()))
    }

    private fun extractLibarchive(
        archiveFile: File,
        destination: File,
        format: String,
        onProgress: (ModArchiveExtractionProgress) -> Unit,
        supportFormat: (Long) -> Unit,
    ): List<ModArchiveEntry> {
        val entries = mutableListOf<ModArchiveEntry>()
        var expandedBytes = 0L
        val archive = try {
            Archive.readNew()
        } catch (e: LinkageError) {
            throw UnsupportedModArchiveException("${format.uppercase()} extraction is not available on this device")
        }

        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray(Charsets.UTF_8))
            Archive.readSupportFilterAll(archive)
            supportFormat(archive)
            Archive.readOpenFileName(
                archive,
                archiveFile.absolutePath.toByteArray(Charsets.UTF_8),
                ARCHIVE_READ_BLOCK_SIZE.toLong(),
            )

            while (true) {
                val entry = try {
                    Archive.readNextHeader(archive)
                } catch (e: ArchiveException) {
                    if (e.code == Archive.ERRNO_EOF) break else throw e
                }
                if (entry == 0L) break
                if (entries.size >= MAX_ENTRIES) throw IOException("Archive has too many entries")
                if (ArchiveEntry.isEncrypted(entry)) {
                    throw UnsupportedModArchiveException("Encrypted ${format.uppercase()} archives are not supported")
                }

                val entryName = archiveEntryName(entry)
                val outFile = safeDestination(destination, entryName)
                val normalized = normalizeArchivePath(entryName)
                when (ArchiveEntry.filetype(entry)) {
                    ArchiveEntry.AE_IFDIR -> {
                        outFile.mkdirs()
                        entries += ModArchiveEntry(normalized, true, 0L)
                    }
                    ArchiveEntry.AE_IFREG, 0 -> {
                        outFile.parentFile?.mkdirs()
                        val written = FileOutputStream(outFile).use { output ->
                            copyLibarchiveEntryData(archive, output, expandedBytes) { currentExpandedBytes ->
                                emitProgress(onProgress, format, entries.size, 0, currentExpandedBytes, 0, normalized)
                            }
                        }
                        expandedBytes += written
                        entries += ModArchiveEntry(normalized, false, outFile.length())
                    }
                    ArchiveEntry.AE_IFLNK -> throw UnsupportedModArchiveException("${format.uppercase()} archives with symlinks are not supported")
                    else -> throw UnsupportedModArchiveException("${format.uppercase()} archive contains an unsupported entry type: $entryName")
                }
                emitProgress(onProgress, format, entries.size, 0, expandedBytes, 0, normalized)
            }
        } catch (e: ArchiveException) {
            throw friendlyLibarchiveException(format, e)
        } finally {
            runCatching { Archive.readClose(archive) }
            runCatching { Archive.readFree(archive) }
        }
        return entries
    }

    private fun emitProgress(
        onProgress: (ModArchiveExtractionProgress) -> Unit,
        format: String,
        entriesProcessed: Int,
        totalEntries: Int,
        extractedBytes: Long,
        totalBytes: Long,
        currentPath: String,
    ) {
        onProgress(
            ModArchiveExtractionProgress(
                format = format,
                entriesProcessed = entriesProcessed,
                totalEntries = totalEntries,
                extractedBytes = extractedBytes,
                totalBytes = totalBytes,
                currentPath = normalizeArchivePath(currentPath),
            ),
        )
    }

    private fun copyLibarchiveEntryData(
        archive: Long,
        output: FileOutputStream,
        alreadyExpandedBytes: Long,
        onBytesCopied: (Long) -> Unit,
    ): Long {
        val buffer = ByteBuffer.allocateDirect(ARCHIVE_READ_BLOCK_SIZE)
        var written = 0L
        while (true) {
            buffer.clear()
            try {
                Archive.readData(archive, buffer)
            } catch (e: ArchiveException) {
                if (e.code == Archive.ERRNO_EOF) break else throw e
            }
            val read = buffer.position()
            if (read <= 0) break
            written += read.toLong()
            if (alreadyExpandedBytes + written > MAX_EXPANDED_BYTES) {
                throw IOException("Archive expands beyond the safety limit")
            }
            onBytesCopied(alreadyExpandedBytes + written)
            buffer.flip()
            while (buffer.hasRemaining()) {
                output.channel.write(buffer)
            }
        }
        return written
    }

    private fun archiveEntryName(entry: Long): String {
        val utf8Name = ArchiveEntry.pathnameUtf8(entry)
        if (!utf8Name.isNullOrBlank()) return utf8Name
        val rawName = ArchiveEntry.pathname(entry)
        if (rawName != null && rawName.isNotEmpty()) return rawName.toString(Charsets.UTF_8)
        throw IOException("Archive contains an entry without a path")
    }

    private fun friendlyLibarchiveException(format: String, error: ArchiveException): IOException {
        val message = error.message.orEmpty()
        val label = format.uppercase()
        return when {
            message.contains("encrypted", ignoreCase = true) ->
                UnsupportedModArchiveException("Encrypted $label archives are not supported")
            message.contains("checksum", ignoreCase = true) ->
                IOException("$label archive is damaged or incomplete (checksum validation failed). Retry the download.", error)
            message.contains("multi-volume", ignoreCase = true) ||
                message.contains("multi volume", ignoreCase = true) ||
                message.contains("volume", ignoreCase = true) ->
                UnsupportedModArchiveException("Multipart $label archives are not supported yet")
            else -> IOException("$label extraction failed: ${message.ifBlank { "archive could not be read" }}", error)
        }
    }

    private fun safeDestination(destination: File, rawName: String): File {
        if (
            rawName.length > MAX_RELATIVE_PATH_LENGTH ||
            isUnsafeArchivePath(rawName)
        ) {
            throw IOException("Unsafe archive path: $rawName")
        }
        val normalized = normalizeArchivePath(rawName)
        val segments = normalized.split('/')
        if (
            normalized.isBlank() ||
            normalized.length > MAX_RELATIVE_PATH_LENGTH ||
            segments.size > MAX_PATH_SEGMENTS ||
            segments.any { it == ".." }
        ) {
            throw IOException("Unsafe archive path: $rawName")
        }
        val outFile = File(destination, normalized).canonicalFile
        val destCanonical = destination.canonicalFile
        if (!outFile.path.startsWith(destCanonical.path + File.separator) && outFile != destCanonical) {
            throw IOException("Archive entry escapes extraction directory: $rawName")
        }
        return outFile
    }

    internal fun isUnsafeArchivePath(path: String): Boolean {
        val trimmed = path.trim()
        return trimmed.startsWith("/") ||
            trimmed.startsWith("\\") ||
            Regex("^[A-Za-z]:.*").containsMatchIn(trimmed)
    }

    private fun normalizeArchivePath(path: String): String =
        path.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")

    private fun sanitizePreservedFileName(name: String?): String? =
        name
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !isUnsafeArchivePath(it) }

}
