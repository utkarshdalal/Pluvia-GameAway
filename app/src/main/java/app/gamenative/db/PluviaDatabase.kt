package app.gamenative.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.gamenative.data.ChangeNumbers
import app.gamenative.data.AppInfo
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.FileChangeLists
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamFileHashCache
import app.gamenative.data.SteamLicense
import app.gamenative.data.CachedLicense
import app.gamenative.data.DownloadingAppInfo
import app.gamenative.data.EncryptedAppTicket
import app.gamenative.data.SteamUnlockedBranch
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.data.ModInstall
import app.gamenative.data.ModOverwriteManifest
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import app.gamenative.db.converters.AppConverter
import app.gamenative.db.converters.ByteArrayConverter
import app.gamenative.db.converters.FriendConverter
import app.gamenative.db.converters.LicenseConverter
import app.gamenative.db.converters.UserFileInfoListConverter
import app.gamenative.db.converters.GOGConverter
import app.gamenative.db.dao.ModDao
import app.gamenative.db.dao.ChangeNumbersDao
import app.gamenative.db.dao.FileChangeListsDao
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.SteamFileHashCacheDao
import app.gamenative.db.dao.SteamLicenseDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.CachedLicenseDao
import app.gamenative.db.dao.DownloadingAppInfoDao
import app.gamenative.db.dao.EncryptedAppTicketDao
import app.gamenative.db.dao.SteamUnlockedBranchDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao

const val DATABASE_NAME = "pluvia.db"

@Database(
    entities = [
        AppInfo::class,
        CachedLicense::class,
        ChangeNumbers::class,
        EncryptedAppTicket::class,
        FileChangeLists::class,
        LibraryPlayHistory::class,
        SteamApp::class,
        SteamFileHashCache::class,
        SteamLicense::class,
        GOGGame::class,
        EpicGame::class,
        AmazonGame::class,
        DownloadingAppInfo::class,
        SteamUnlockedBranch::class,
        ModInstall::class,
        ModProfile::class,
        ModProfileInstallState::class,
        ModPlacementRecipe::class,
        ModOverwriteManifest::class,
    ],
    version = 27,
    // For db migration, visit https://developer.android.com/training/data-storage/room/migrating-db-versions for more information
    exportSchema = true, // It is better to handle db changes carefully, as GN is getting much more users.
    autoMigrations = [
        // For every version change, if it is automatic, please add a new migration here.
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13), // Added amazon_games table
        AutoMigration(from = 13, to = 14), // Added GOG background image column
        AutoMigration(from = 14, to = 15), // Added branch columns and steam_unlocked_branch table
        AutoMigration(from = 15, to = 16), // Added ufs_parse_version to steam_app
        // AutoMigration(from = 16, to = 17),
        // Disabled auto-migration due to duplicated column in previous version (upstream PR #1048)
        // duplicate column name: ufs_parse_version (code 1 SQLITE_ERROR)
        // v16 users will fallback to destructive migration (only cached Steam data, re-fetched on login)
        AutoMigration(from = 17, to = 18), // Added workshop_mods, enabled_workshop_item_ids, workshop_download_pending to steam_app
        AutoMigration(from = 18, to = 19), // Added recovered_install_size_bytes to app_info
        AutoMigration(from = 19, to = 20), // Added custom_install_path to app_info
        AutoMigration(from = 20, to = 21), // Added steam_file_hash_cache table
        AutoMigration(from = 21, to = 22), // Added GOG vertical_cover_url column
        AutoMigration(from = 22, to = 23), // Added local library play history table
        AutoMigration(from = 25, to = 26), // Added GOG hidden column
    ]
)
@TypeConverters(
    AppConverter::class,
    ByteArrayConverter::class,
    FriendConverter::class,
    LicenseConverter::class,
    UserFileInfoListConverter::class,
    GOGConverter::class,
)
abstract class PluviaDatabase : RoomDatabase() {

    abstract fun steamLicenseDao(): SteamLicenseDao

    abstract fun steamAppDao(): SteamAppDao

    abstract fun steamFileHashCacheDao(): SteamFileHashCacheDao

    abstract fun appChangeNumbersDao(): ChangeNumbersDao

    abstract fun appFileChangeListsDao(): FileChangeListsDao

    abstract fun libraryPlayHistoryDao(): LibraryPlayHistoryDao

    abstract fun appInfoDao(): AppInfoDao

    abstract fun cachedLicenseDao(): CachedLicenseDao

    abstract fun encryptedAppTicketDao(): EncryptedAppTicketDao

    abstract fun gogGameDao(): GOGGameDao

    abstract fun epicGameDao(): EpicGameDao

    abstract fun amazonGameDao(): AmazonGameDao

    abstract fun downloadingAppInfoDao(): DownloadingAppInfoDao

    abstract fun steamUnlockedBranchDao(): SteamUnlockedBranchDao

    abstract fun modDao(): ModDao
}
