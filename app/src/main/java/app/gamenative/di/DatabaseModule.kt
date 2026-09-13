package app.gamenative.di

import android.content.Context
import androidx.room.Room
import app.gamenative.db.DATABASE_NAME
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.ModDao
import app.gamenative.db.dao.SteamUnlockedBranchDao
import app.gamenative.db.migration.ROOM_MIGRATION_V23_to_V24
import app.gamenative.db.migration.ROOM_MIGRATION_V24_to_V25
import app.gamenative.db.migration.ROOM_MIGRATION_V26_to_V27
import app.gamenative.db.migration.ROOM_MIGRATION_V7_to_V8
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PluviaDatabase {
        // The db will be considered unstable during development.
        // Once stable we should add a (room) db migration
        return Room.databaseBuilder(context, PluviaDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                ROOM_MIGRATION_V7_to_V8,
                ROOM_MIGRATION_V23_to_V24,
                ROOM_MIGRATION_V24_to_V25,
                ROOM_MIGRATION_V26_to_V27,
            )
            // Versions 1-6 predate the first retained migration. Version 16 has no
            // 16 -> 17 migration because that historical schema could contain a
            // duplicated column. Every newer supported schema migrates in place.
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 16)
            .build()
    }

    @Provides
    @Singleton
    fun provideSteamLicenseDao(db: PluviaDatabase) = db.steamLicenseDao()

    @Provides
    @Singleton
    fun provideSteamAppDao(db: PluviaDatabase) = db.steamAppDao()

    @Provides
    @Singleton
    fun provideSteamFileHashCacheDao(db: PluviaDatabase) = db.steamFileHashCacheDao()

    @Provides
    @Singleton
    fun provideAppChangeNumbersDao(db: PluviaDatabase) = db.appChangeNumbersDao()

    @Provides
    @Singleton
    fun provideAppFileChangeListsDao(db: PluviaDatabase) = db.appFileChangeListsDao()

    @Provides
    @Singleton
    fun provideLibraryPlayHistoryDao(db: PluviaDatabase): LibraryPlayHistoryDao = db.libraryPlayHistoryDao()

    @Provides
    @Singleton
    fun provideAppInfoDao(db: PluviaDatabase): AppInfoDao = db.appInfoDao()

    @Provides
    @Singleton
    fun provideCachedLicenseDao(db: PluviaDatabase): CachedLicenseDao = db.cachedLicenseDao()

    @Provides
    @Singleton
    fun provideEncryptedAppTicketDao(db: PluviaDatabase): EncryptedAppTicketDao = db.encryptedAppTicketDao()

    @Provides
    @Singleton
    fun provideGOGGameDao(db: PluviaDatabase) = db.gogGameDao()

    @Provides
    @Singleton
    fun provideEpicGameDao(db: PluviaDatabase) = db.epicGameDao()

    @Provides
    @Singleton
    fun provideAmazonGameDao(db: PluviaDatabase) = db.amazonGameDao()

    @Provides
    @Singleton
    fun provideDownloadingAppInfoDao(db: PluviaDatabase): DownloadingAppInfoDao = db.downloadingAppInfoDao()

    @Provides
    @Singleton
    fun provideSteamUnlockedBranchDao(db: PluviaDatabase): SteamUnlockedBranchDao = db.steamUnlockedBranchDao()

    @Provides
    @Singleton
    fun provideModDao(db: PluviaDatabase): ModDao = db.modDao()
}
