package algofeed.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        Folder::class, Feed::class, Entry::class, EntryTerm::class, ProfileTerm::class, Setting::class,
        AuthorStat::class,
    ],
    version = 7,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = PinsToBookmarks::class),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = DropAi::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun feeds(): FeedDao
    abstract fun entries(): EntryDao
    abstract fun authorStats(): AuthorStatDao
    abstract fun profile(): ProfileDao
    abstract fun settings(): SettingDao
    abstract fun reset(): ResetDao
}

/** Version 3 replaced pins with bookmarks; existing pins become bookmarks. */
@RenameColumn(tableName = "entry", fromColumnName = "pinnedAt", toColumnName = "bookmarkedAt")
class PinsToBookmarks : AutoMigrationSpec

/** Version 5 removed AI ranking: its tables, learned topic labels and bookkeeping go. */
@DeleteTable(tableName = "entry_ai")
@DeleteTable(tableName = "profile_vector")
class DropAi : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM profile_term WHERE term LIKE 'topic:%'")
        connection.execSQL("DELETE FROM setting WHERE key LIKE 'ai.%' OR key = 'migrated.chatModel'")
    }
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Finishes a platform-specific builder (file path on desktop, Context on Android). */
fun RoomDatabase.Builder<AppDatabase>.buildAlgofeed(): AppDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
