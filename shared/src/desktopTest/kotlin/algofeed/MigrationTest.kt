package algofeed

import algofeed.data.AppDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTest {
    private val dir = Files.createTempDirectory("migration")
    private val helper = MigrationTestHelper(
        schemaDirectoryPath = Path("schemas"),
        databasePath = dir.resolve("test.db"),
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @AfterTest fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test fun pinsBecomeBookmarks() {
        helper.createDatabase(2).apply {
            execSQL(
                """INSERT INTO feed (id, type, url, title, bucket, enabled, createdAt, impressions, opens, favorites, dismissals)
                   VALUES (1, 'rss', 'https://blog.example/feed', 'Blog', 0, 1, 0, 0, 0, 0, 0)"""
            )
            execSQL("INSERT INTO entry (id, feedId, remoteId, sortDate, fetchedAt, readSeconds, pinnedAt) VALUES (1, 1, 'a', 0, 0, 0, 1234)")
            execSQL("INSERT INTO entry (id, feedId, remoteId, sortDate, fetchedAt, readSeconds) VALUES (2, 1, 'b', 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(3)
        val bookmarked = mutableMapOf<Long, Long?>()
        db.prepare("SELECT id, bookmarkedAt FROM entry ORDER BY id").use { st ->
            while (st.step()) bookmarked[st.getLong(0)] = if (st.isNull(1)) null else st.getLong(1)
        }
        db.close()
        assertEquals(mapOf(1L to 1234L, 2L to null), bookmarked)
    }

    @Test fun feedsDefaultToExtraction() {
        helper.createDatabase(3).apply {
            execSQL(
                """INSERT INTO feed (id, type, url, title, bucket, enabled, createdAt, impressions, opens, favorites, dismissals)
                   VALUES (1, 'rss', 'https://blog.example/feed', 'Blog', 0, 1, 0, 0, 0, 0, 0)"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(4)
        val prefer = db.prepare("SELECT preferFeedVersion FROM feed").use { st -> st.step(); st.getLong(0) }
        db.close()
        assertEquals(0L, prefer)
    }

    @Test fun aiDataIsDropped() {
        helper.createDatabase(4).apply {
            execSQL(
                """INSERT INTO feed (id, type, url, title, bucket, enabled, createdAt, impressions, opens, favorites, dismissals, preferFeedVersion)
                   VALUES (1, 'rss', 'https://blog.example/feed', 'Blog', 0, 1, 0, 0, 0, 0, 0, 0)"""
            )
            execSQL("INSERT INTO entry (id, feedId, remoteId, sortDate, fetchedAt, readSeconds) VALUES (1, 1, 'a', 0, 0, 0)")
            execSQL("INSERT INTO entry_ai (entryId, embedAttempts, tagAttempts) VALUES (1, 0, 0)")
            execSQL("INSERT INTO profile_term (term, weight) VALUES ('rust', 1.0), ('topic:rust', 1.0)")
            execSQL("INSERT INTO setting (key, value) VALUES ('ai.lastError', 'x'), ('migrated.chatModel', '1'), ('settings', '{}')")
            close()
        }
        val db = helper.runMigrationsAndValidate(5)
        fun strings(sql: String) = db.prepare(sql).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
        val tables = strings("SELECT name FROM sqlite_master WHERE type = 'table'")
        val terms = strings("SELECT term FROM profile_term")
        val keys = strings("SELECT key FROM setting")
        db.close()
        assertFalse("entry_ai" in tables || "profile_vector" in tables)
        assertEquals(listOf("rust"), terms)
        assertEquals(listOf("settings"), keys)
    }

    @Test fun entriesGainAMediaColumn() {
        helper.createDatabase(5).apply {
            execSQL(
                """INSERT INTO feed (id, type, url, title, bucket, enabled, createdAt, impressions, opens, favorites, dismissals, preferFeedVersion)
                   VALUES (1, 'rss', 'https://blog.example/feed', 'Blog', 0, 1, 0, 0, 0, 0, 0, 0)"""
            )
            execSQL("INSERT INTO entry (id, feedId, remoteId, sortDate, fetchedAt, readSeconds) VALUES (1, 1, 'a', 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(6)
        val media = db.prepare("SELECT media FROM entry WHERE id = 1").use { st -> st.step(); st.getText(0) }
        db.close()
        assertEquals("[]", media)
    }

    @Test fun authorStatTableIsAddedAndDataSurvives() {
        helper.createDatabase(6).apply {
            execSQL(
                """INSERT INTO feed (id, type, url, title, bucket, enabled, createdAt, impressions, opens, favorites, dismissals, preferFeedVersion)
                   VALUES (1, 'rss', 'https://blog.example/feed', 'Blog', 0, 1, 0, 3, 2, 1, 0, 0)"""
            )
            execSQL("INSERT INTO entry (id, feedId, remoteId, sortDate, fetchedAt, readSeconds, media) VALUES (1, 1, 'a', 0, 0, 0, '[]')")
            close()
        }
        val db = helper.runMigrationsAndValidate(7)
        // The additive table exists and is empty; the pre-existing feed row is untouched.
        val authorRows = db.prepare("SELECT COUNT(*) FROM author_stat").use { st -> st.step(); st.getLong(0) }
        val opens = db.prepare("SELECT opens FROM feed WHERE id = 1").use { st -> st.step(); st.getLong(0) }
        db.close()
        assertEquals(0L, authorRows)
        assertEquals(2L, opens)
    }

    @Test fun tfIdfTablesGiveWayToEmbeddings() {
        helper.createDatabase(7).apply {
            execSQL(
                """INSERT INTO feed (id, type, url, title, bucket, enabled, createdAt, impressions, opens, favorites, dismissals, preferFeedVersion)
                   VALUES (1, 'rss', 'https://blog.example/feed', 'Blog', 0, 1, 0, 0, 0, 0, 0, 0)"""
            )
            execSQL("INSERT INTO entry (id, feedId, remoteId, sortDate, fetchedAt, readSeconds, media) VALUES (1, 1, 'a', 0, 0, 0, '[]')")
            execSQL("INSERT INTO entry_term (entryId, term, tf) VALUES (1, 'rust', 1.0)")
            execSQL("INSERT INTO profile_term (term, weight) VALUES ('rust', 2.0)")
            execSQL("INSERT INTO setting (key, value) VALUES ('profile.lastDecay', '123'), ('settings', '{}')")
            close()
        }
        val db = helper.runMigrationsAndValidate(8)
        fun strings(sql: String) = db.prepare(sql).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
        val tables = strings("SELECT name FROM sqlite_master WHERE type = 'table'")
        val keys = strings("SELECT key FROM setting")
        val entries = db.prepare("SELECT COUNT(*) FROM entry").use { st -> st.step(); st.getLong(0) }
        db.close()
        assertFalse("entry_term" in tables || "profile_term" in tables)
        assertTrue("entry_embedding" in tables && "profile_vector" in tables)
        // The learned profile resets (its decay bookkeeping goes); subscriptions and entries survive.
        assertEquals(listOf("settings"), keys)
        assertEquals(1L, entries)
    }
}
