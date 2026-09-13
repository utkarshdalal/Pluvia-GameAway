package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.gamenative.data.ModInstall
import app.gamenative.data.ModOverwriteManifest
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import kotlinx.coroutines.flow.Flow

@Dao
interface ModDao {

    @Query("SELECT * FROM mod_install WHERE app_id = :appId ORDER BY LOWER(mod_name), LOWER(file_name)")
    fun observeInstallsForApp(appId: String): Flow<List<ModInstall>>

    @Query("SELECT * FROM mod_install WHERE app_id = :appId ORDER BY LOWER(mod_name), LOWER(file_name)")
    suspend fun getInstallsForApp(appId: String): List<ModInstall>

    @Query("SELECT * FROM mod_install WHERE install_id = :installId")
    suspend fun getInstall(installId: String): ModInstall?

    @Query(
        """
        SELECT * FROM mod_install
        WHERE app_id = :appId
          AND source IN ('LOCAL_ARCHIVE', 'LOCAL_FILES', 'LOCAL_FOLDER')
          AND archive_sha256 != ''
          AND archive_sha256 = :archiveSha256
          AND install_id != :excludingInstallId
          AND status IN (:reusableStatuses)
        ORDER BY updated_at DESC, install_id DESC
        LIMIT 32
        """,
    )
    suspend fun getLocalInstallsByContentHash(
        appId: String,
        archiveSha256: String,
        excludingInstallId: String,
        reusableStatuses: Set<String>,
    ): List<ModInstall>

    @Query("SELECT * FROM mod_install WHERE status = :status ORDER BY updated_at")
    suspend fun getInstallsByStatus(status: String): List<ModInstall>

    @Upsert
    suspend fun upsertInstall(install: ModInstall)

    @Update
    suspend fun updateInstall(install: ModInstall): Int

    @Query("UPDATE mod_install SET enabled = :enabled, status = :status, updated_at = :updatedAt WHERE install_id = :installId")
    suspend fun updateInstallEnabled(
        installId: String,
        enabled: Boolean,
        status: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE mod_install SET status = :status, updated_at = :updatedAt WHERE install_id = :installId")
    suspend fun updateInstallStatus(
        installId: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM mod_install WHERE install_id = :installId")
    suspend fun deleteInstall(installId: String)

    @Query("SELECT * FROM mod_profile WHERE app_id = :appId ORDER BY active DESC, LOWER(name)")
    fun observeProfilesForApp(appId: String): Flow<List<ModProfile>>

    @Query("SELECT * FROM mod_profile WHERE app_id = :appId ORDER BY active DESC, LOWER(name)")
    suspend fun getProfilesForApp(appId: String): List<ModProfile>

    @Query("SELECT * FROM mod_profile WHERE app_id = :appId AND active = 1 LIMIT 1")
    suspend fun getActiveProfileForApp(appId: String): ModProfile?

    @Upsert
    suspend fun upsertProfile(profile: ModProfile)

    @Query("UPDATE mod_profile SET name = :name, updated_at = :updatedAt WHERE profile_id = :profileId")
    suspend fun renameProfile(
        profileId: String,
        name: String,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE mod_profile SET active = 0, updated_at = :updatedAt WHERE app_id = :appId")
    suspend fun clearActiveProfile(appId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE mod_profile SET active = 1, updated_at = :updatedAt WHERE app_id = :appId AND profile_id = :profileId")
    suspend fun setProfileActive(appId: String, profileId: String, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun activateProfile(appId: String, profileId: String) {
        clearActiveProfile(appId)
        setProfileActive(appId, profileId)
    }

    @Query("DELETE FROM mod_profile WHERE profile_id = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Query("SELECT * FROM mod_profile_install_state WHERE app_id = :appId AND profile_id = :profileId ORDER BY priority, install_id")
    fun observeProfileInstallStates(appId: String, profileId: String): Flow<List<ModProfileInstallState>>

    @Query("SELECT * FROM mod_profile_install_state WHERE app_id = :appId AND profile_id = :profileId ORDER BY priority, install_id")
    suspend fun getProfileInstallStates(appId: String, profileId: String): List<ModProfileInstallState>

    @Query("SELECT * FROM mod_profile_install_state WHERE profile_id = :profileId AND install_id = :installId LIMIT 1")
    suspend fun getProfileInstallState(profileId: String, installId: String): ModProfileInstallState?

    @Query("SELECT COALESCE(MAX(priority) + 1, 0) FROM mod_profile_install_state WHERE app_id = :appId AND profile_id = :profileId")
    suspend fun nextProfileInstallPriority(appId: String, profileId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfileInstallState(state: ModProfileInstallState)

    @Transaction
    suspend fun ensureProfileInstallState(
        profile: ModProfile,
        installId: String,
        enabled: Boolean = true,
        priority: Int? = null,
    ): ModProfileInstallState {
        getProfileInstallState(profile.profileId, installId)?.let { return it }
        val state = ModProfileInstallState(
            profileId = profile.profileId,
            installId = installId,
            appId = profile.appId,
            enabled = enabled,
            priority = priority ?: nextProfileInstallPriority(profile.appId, profile.profileId),
        )
        upsertProfileInstallState(state)
        return state
    }

    @Query("DELETE FROM mod_profile_install_state WHERE install_id = :installId")
    suspend fun deleteProfileInstallStatesForInstall(installId: String)

    @Query("SELECT * FROM mod_placement_recipe WHERE install_id = :installId ORDER BY recipe_id")
    fun observeRecipesForInstall(installId: String): Flow<List<ModPlacementRecipe>>

    @Query("SELECT * FROM mod_placement_recipe WHERE install_id = :installId ORDER BY recipe_id")
    suspend fun getRecipesForInstall(installId: String): List<ModPlacementRecipe>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipes(recipes: List<ModPlacementRecipe>)

    @Query("DELETE FROM mod_placement_recipe WHERE install_id = :installId")
    suspend fun deleteRecipesForInstall(installId: String)

    @Transaction
    suspend fun replaceRecipes(installId: String, recipes: List<ModPlacementRecipe>) {
        deleteRecipesForInstall(installId)
        if (recipes.isNotEmpty()) {
            insertRecipes(recipes)
        }
    }

    @Query("SELECT * FROM mod_overwrite_manifest WHERE install_id = :installId ORDER BY target_path")
    suspend fun getOverwriteManifests(installId: String): List<ModOverwriteManifest>

    @Query(
        """
        SELECT mod_overwrite_manifest.* FROM mod_overwrite_manifest
        INNER JOIN mod_install ON mod_install.install_id = mod_overwrite_manifest.install_id
        WHERE mod_install.app_id = :appId
            AND original_hash != ''
            AND original_hash = installed_hash
            AND backup_path != ''
        ORDER BY backup_path
        """,
    )
    suspend fun getRedundantOverwriteManifestsForApp(appId: String): List<ModOverwriteManifest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverwriteManifests(manifests: List<ModOverwriteManifest>)

    @Query("DELETE FROM mod_overwrite_manifest WHERE install_id = :installId AND target_path IN (:targetPaths)")
    suspend fun deleteOverwriteManifestsForTargets(installId: String, targetPaths: List<String>)

    @Query("DELETE FROM mod_overwrite_manifest WHERE manifest_id IN (:manifestIds)")
    suspend fun deleteOverwriteManifestsByIds(manifestIds: List<Long>)

    @Transaction
    suspend fun replaceOverwriteManifestsForTargets(installId: String, manifests: List<ModOverwriteManifest>) {
        if (manifests.isEmpty()) {
            deleteOverwriteManifests(installId)
            return
        }
        deleteOverwriteManifestsForTargets(installId, manifests.map { it.targetPath })
        insertOverwriteManifests(manifests)
    }

    @Query("DELETE FROM mod_overwrite_manifest WHERE install_id = :installId")
    suspend fun deleteOverwriteManifests(installId: String)
}
