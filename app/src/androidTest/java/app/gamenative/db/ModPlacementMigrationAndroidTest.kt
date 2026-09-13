package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V26_to_V27
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModPlacementMigrationAndroidTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
    )

    @Test
    fun migrate26To27_preservesMasterInstallAndRecipe() {
        helper.createDatabase(MASTER_SCHEMA_DATABASE_NAME, 26).apply {
            execSQL(
                """
                INSERT INTO mod_install (
                    install_id, app_id, source, nexus_game_domain, nexus_mod_id, nexus_file_id,
                    mod_name, file_name, version, size_bytes, archive_path, extracted_path,
                    enabled, status, created_at, updated_at, downloaded_at, metadata_json, archive_sha256
                ) VALUES (
                    'install-1', 'steam:489830', 'LOCAL_ARCHIVE', NULL, NULL, NULL,
                    'Historical mod', 'historical.zip', '1.0', 42, '/archive.zip', '/extracted',
                    1, 'APPLIED', 1, 2, 3, '{}', 'archive-hash'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO mod_placement_recipe (
                    install_id, source_subpath, target_root, target_relative_path, mode,
                    strip_prefix_segments, include_source_directory, enabled
                ) VALUES (
                    'install-1', 'Scripts', 'GAME_DIR', 'Data', 'OVERWRITE_COPY', 0, 1, 1
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            MASTER_SCHEMA_DATABASE_NAME,
            27,
            true,
            ROOM_MIGRATION_V26_to_V27,
        ).use { database ->
            database.query(
                """
                SELECT i.mod_name, r.source_subpath, r.target_relative_path, r.target_file_name
                FROM mod_install i
                JOIN mod_placement_recipe r ON r.install_id = i.install_id
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Historical mod", cursor.getString(0))
                assertEquals("Scripts", cursor.getString(1))
                assertEquals("Data", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(false, cursor.moveToNext())
            }
        }
    }

    @Test
    fun migrate26To27_reconcilesPlacementSchema() {
        helper.createDatabase(PLACEMENT_SCHEMA_DATABASE_NAME, 25).apply {
            execSQL(
                "ALTER TABLE mod_placement_recipe ADD COLUMN target_file_name TEXT NOT NULL DEFAULT ''",
            )
            execSQL("PRAGMA user_version = 26")
            execSQL("UPDATE room_master_table SET identity_hash = '7e163d4af9b2107253274fe6d3f84665' WHERE id = 42")
            close()
        }

        helper.runMigrationsAndValidate(
            PLACEMENT_SCHEMA_DATABASE_NAME,
            27,
            true,
            ROOM_MIGRATION_V26_to_V27,
        ).close()
    }

    private companion object {
        const val MASTER_SCHEMA_DATABASE_NAME = "mod-placement-v26-master"
        const val PLACEMENT_SCHEMA_DATABASE_NAME = "mod-placement-v26-placement"
    }
}
