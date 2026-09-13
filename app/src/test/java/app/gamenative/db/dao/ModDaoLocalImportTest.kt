package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import app.gamenative.db.PluviaDatabase
import app.gamenative.mods.NexusImportState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModDaoLocalImportTest {
    private lateinit var database: PluviaDatabase
    private lateinit var dao: ModDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.modDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun contentHashLookup_boundsReusableRowsAndSkipsFailedRow() = runBlocking {
        dao.upsertInstall(localInstall("local_failed", ModInstallStatus.ERROR))
        repeat(34) { index ->
            dao.upsertInstall(
                localInstall("local_ready_$index", ModInstallStatus.READY)
                    .copy(updatedAt = index.toLong()),
            )
        }

        val duplicates = dao.getLocalInstallsByContentHash(
            appId = APP_ID,
            archiveSha256 = CONTENT_HASH,
            excludingInstallId = "local_new",
            reusableStatuses = NexusImportState.reusableStatuses,
        )

        assertEquals(32, duplicates.size)
        assertEquals("local_ready_33", duplicates.first().installId)
    }

    @Test
    fun installUpsert_preservesDependentProfileState() = runBlocking {
        val install = localInstall("local_retry", ModInstallStatus.ERROR)
        val profile = ModProfile(
            profileId = "profile",
            appId = APP_ID,
            name = "Default",
        )
        dao.upsertProfile(profile)
        dao.upsertInstall(install)
        dao.upsertProfileInstallState(
            ModProfileInstallState(
                profileId = profile.profileId,
                installId = install.installId,
                appId = APP_ID,
            ),
        )

        dao.upsertInstall(install.copy(status = ModInstallStatus.IMPORTING.name))

        assertEquals(
            ModInstallStatus.IMPORTING.name,
            dao.getInstall(install.installId)?.status,
        )
        assertNotNull(dao.getProfileInstallState(profile.profileId, install.installId))
    }

    @Test
    fun profileUpsert_preservesEveryDependentInstallState() = runBlocking {
        val profile = ModProfile(
            profileId = "profile",
            appId = APP_ID,
            name = "Default",
        )
        val first = localInstall("first", ModInstallStatus.APPLIED)
        val second = localInstall("second", ModInstallStatus.APPLIED)
        dao.upsertProfile(profile)
        dao.upsertInstall(first)
        dao.upsertInstall(second)
        listOf(first, second).forEachIndexed { priority, install ->
            dao.upsertProfileInstallState(
                ModProfileInstallState(
                    profileId = profile.profileId,
                    installId = install.installId,
                    appId = APP_ID,
                    enabled = true,
                    priority = priority,
                ),
            )
        }

        dao.upsertProfile(profile.copy(active = true, updatedAt = profile.updatedAt + 1))

        val states = dao.getProfileInstallStates(APP_ID, profile.profileId)
        assertEquals(setOf("first", "second"), states.map { it.installId }.toSet())
        assertTrue(states.all { it.enabled })
    }

    @Test
    fun installUpdate_doesNotRecreateDeletedRow() = runBlocking {
        val deleted = localInstall("local_deleted", ModInstallStatus.ERROR)

        assertEquals(0, dao.updateInstall(deleted))
        assertNull(dao.getInstall(deleted.installId))
    }

    private fun localInstall(installId: String, status: ModInstallStatus) = ModInstall(
        installId = installId,
        appId = APP_ID,
        source = ModInstallSource.LOCAL_FILES.name,
        nexusGameDomain = null,
        nexusModId = null,
        nexusFileId = null,
        modName = installId,
        fileName = "settings.ini",
        archivePath = "",
        extractedPath = "/mods/$installId",
        status = status.name,
        archiveSha256 = CONTENT_HASH,
    )

    private companion object {
        const val APP_ID = "test-app"
        const val CONTENT_HASH = "same-content"
    }
}
