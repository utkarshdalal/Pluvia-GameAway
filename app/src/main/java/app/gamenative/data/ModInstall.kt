package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ModInstallSource {
    NEXUS,
    LOCAL_ARCHIVE,
    LOCAL_FILES,
    LOCAL_FOLDER,
    ;

    val isLocal: Boolean
        get() = when (this) {
            LOCAL_ARCHIVE,
            LOCAL_FILES,
            LOCAL_FOLDER,
            -> true
            NEXUS -> false
        }

    companion object {
        fun isLocal(source: String): Boolean =
            entries.firstOrNull { it.name == source }?.isLocal == true
    }
}

enum class ModInstallStatus {
    IMPORTING,
    PAUSED,
    READY,
    APPLIED,
    DISABLED,
    CANCELED,
    ERROR,
}

enum class ModPlacementMode {
    SYMLINK,
    COPY,
    OVERWRITE_COPY,
}

enum class ModTargetRoot {
    GAME_DIR,
    WINE_C,
    DOCUMENTS,
    MY_GAMES,
    APPDATA_ROAMING,
    APPDATA_LOCAL,
    APPDATA_LOCALLOW,
    CUSTOM_ABSOLUTE,
}

@Entity(
    tableName = "mod_profile",
    indices = [
        Index("app_id"),
        Index(value = ["app_id", "name"], unique = true),
    ],
)
data class ModProfile(
    @PrimaryKey
    @ColumnInfo(name = "profile_id")
    val profileId: String,

    @ColumnInfo(name = "app_id")
    val appId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "active")
    val active: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "mod_install",
    indices = [
        Index("app_id"),
        Index(value = ["app_id", "source", "archive_sha256"]),
        Index(value = ["app_id", "source", "nexus_game_domain", "nexus_mod_id", "nexus_file_id"], unique = true),
    ],
)
data class ModInstall(
    @PrimaryKey
    @ColumnInfo(name = "install_id")
    val installId: String,

    @ColumnInfo(name = "app_id")
    val appId: String,

    @ColumnInfo(name = "source")
    val source: String = ModInstallSource.NEXUS.name,

    @ColumnInfo(name = "nexus_game_domain")
    val nexusGameDomain: String? = null,

    @ColumnInfo(name = "nexus_mod_id")
    val nexusModId: Long? = null,

    @ColumnInfo(name = "nexus_file_id")
    val nexusFileId: Long? = null,

    @ColumnInfo(name = "mod_name")
    val modName: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "version")
    val version: String = "",

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,

    @ColumnInfo(name = "archive_path")
    val archivePath: String,

    @ColumnInfo(name = "extracted_path")
    val extractedPath: String,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "status")
    val status: String = ModInstallStatus.READY.name,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long = 0L,

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String = "",

    @ColumnInfo(name = "archive_sha256")
    val archiveSha256: String = "",
)

@Entity(
    tableName = "mod_profile_install_state",
    primaryKeys = ["profile_id", "install_id"],
    foreignKeys = [
        ForeignKey(
            entity = ModProfile::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ModInstall::class,
            parentColumns = ["install_id"],
            childColumns = ["install_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("app_id"),
        Index("install_id"),
        Index(value = ["app_id", "profile_id", "priority"]),
    ],
)
data class ModProfileInstallState(
    @ColumnInfo(name = "profile_id")
    val profileId: String,

    @ColumnInfo(name = "install_id")
    val installId: String,

    @ColumnInfo(name = "app_id")
    val appId: String,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "mod_placement_recipe",
    foreignKeys = [
        ForeignKey(
            entity = ModInstall::class,
            parentColumns = ["install_id"],
            childColumns = ["install_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("install_id")],
)
data class ModPlacementRecipe(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "recipe_id")
    val recipeId: Long = 0L,

    @ColumnInfo(name = "install_id")
    val installId: String,

    @ColumnInfo(name = "source_subpath")
    val sourceSubpath: String = "",

    @ColumnInfo(name = "target_root")
    val targetRoot: String = ModTargetRoot.GAME_DIR.name,

    @ColumnInfo(name = "target_relative_path")
    val targetRelativePath: String = "",

    @ColumnInfo(name = "target_file_name")
    val targetFileName: String = "",

    @ColumnInfo(name = "mode")
    val mode: String = ModPlacementMode.SYMLINK.name,

    @ColumnInfo(name = "strip_prefix_segments")
    val stripPrefixSegments: Int = 0,

    @ColumnInfo(name = "include_source_directory")
    val includeSourceDirectory: Boolean = false,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
)

@Entity(
    tableName = "mod_overwrite_manifest",
    foreignKeys = [
        ForeignKey(
            entity = ModInstall::class,
            parentColumns = ["install_id"],
            childColumns = ["install_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("install_id"),
        Index("target_path"),
    ],
)
data class ModOverwriteManifest(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "manifest_id")
    val manifestId: Long = 0L,

    @ColumnInfo(name = "install_id")
    val installId: String,

    @ColumnInfo(name = "target_path")
    val targetPath: String,

    @ColumnInfo(name = "backup_path")
    val backupPath: String,

    @ColumnInfo(name = "original_hash")
    val originalHash: String = "",

    @ColumnInfo(name = "original_size")
    val originalSize: Long = 0L,

    @ColumnInfo(name = "original_mtime")
    val originalMtime: Long = 0L,

    @ColumnInfo(name = "installed_hash")
    val installedHash: String = "",

    @ColumnInfo(name = "installed_size")
    val installedSize: Long = 0L,

    @ColumnInfo(name = "installed_mtime")
    val installedMtime: Long = 0L,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
)
