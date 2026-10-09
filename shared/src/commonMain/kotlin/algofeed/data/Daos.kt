package algofeed.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Folder>>

    @Query("SELECT * FROM folder")
    suspend fun all(): List<Folder>

    @Query("SELECT * FROM folder WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Folder?

    @Insert
    suspend fun insert(folder: Folder): Long

    @Update
    suspend fun update(folder: Folder)

    @Delete
    suspend fun delete(folder: Folder)
}

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Feed>>

    @Query("SELECT * FROM feed")
    suspend fun all(): List<Feed>

    @Query("SELECT * FROM feed WHERE id = :id")
    suspend fun get(id: Long): Feed?

    @Query("SELECT * FROM feed WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): Feed?

    @Insert
    suspend fun insert(feed: Feed): Long

    @Update
    suspend fun update(feed: Feed)

    @Delete
    suspend fun delete(feed: Feed)

    @Query(
        """UPDATE feed SET impressions = impressions + :impressions, opens = opens + :opens,
           favorites = favorites + :favorites, dismissals = dismissals + :dismissals WHERE id = :id"""
    )
    suspend fun addStats(id: Long, impressions: Int = 0, opens: Int = 0, favorites: Int = 0, dismissals: Int = 0)
}

@Dao
interface AuthorStatDao {
    @Query(
        """INSERT INTO author_stat(feedId, author, impressions, opens, favorites, dismissals)
           VALUES(:feedId, :author, :impressions, :opens, :favorites, :dismissals)
           ON CONFLICT(feedId, author) DO UPDATE SET
             impressions = impressions + :impressions, opens = opens + :opens,
             favorites = favorites + :favorites, dismissals = dismissals + :dismissals"""
    )
    suspend fun addStats(feedId: Long, author: String, impressions: Int = 0, opens: Int = 0, favorites: Int = 0, dismissals: Int = 0)

    @Query("SELECT * FROM author_stat WHERE feedId IN (:feedIds)")
    suspend fun forFeeds(feedIds: List<Long>): List<AuthorStat>

    @Query("SELECT * FROM author_stat WHERE feedId = :feedId")
    suspend fun forFeed(feedId: Long): List<AuthorStat>
}

@Dao
interface EntryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entries: List<Entry>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerms(terms: List<EntryTerm>)

    @Query("SELECT * FROM entry WHERE id = :id")
    suspend fun get(id: Long): Entry?

    /** Home-stream candidates: unseen and not dismissed. */
    @Query(
        """SELECT entry.* FROM entry JOIN feed ON feed.id = entry.feedId
           WHERE feed.enabled AND viewedAt IS NULL AND dismissedAt IS NULL
           AND sortDate >= :since"""
    )
    suspend fun candidates(since: Long): List<Entry>

    @Query(
        """SELECT entry.* FROM entry JOIN feed ON feed.id = entry.feedId
           WHERE feed.enabled AND dismissedAt IS NULL AND sortDate >= :since"""
    )
    suspend fun candidatesIncludingSeen(since: Long): List<Entry>

    /** Most recently bookmarked first. */
    @Query("SELECT * FROM entry WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC")
    suspend fun bookmarks(): List<Entry>

    @Query("SELECT * FROM entry WHERE favoritedAt IS NOT NULL ORDER BY favoritedAt DESC")
    suspend fun favorites(): List<Entry>

    /** Entries carrying a gallery or a thumbnail image, newest first — for the Media grid. */
    @Query(
        """SELECT entry.* FROM entry JOIN feed ON feed.id = entry.feedId
           WHERE feed.enabled AND (entry.media <> '[]' OR entry.thumbnailUrl IS NOT NULL)
           ORDER BY sortDate DESC LIMIT :limit"""
    )
    suspend fun withMedia(limit: Int = 500): List<Entry>

    @Query("SELECT * FROM entry WHERE feedId = :feedId ORDER BY sortDate DESC LIMIT :limit")
    suspend fun byFeed(feedId: Long, limit: Int = 500): List<Entry>

    @Query(
        """SELECT entry.* FROM entry JOIN feed ON feed.id = entry.feedId
           WHERE feed.folderId = :folderId ORDER BY sortDate DESC LIMIT :limit"""
    )
    suspend fun byFolder(folderId: Long, limit: Int = 500): List<Entry>

    @Query(
        """SELECT * FROM entry WHERE title LIKE '%' || :q || '%' OR summaryHtml LIKE '%' || :q || '%'
           OR author LIKE '%' || :q || '%' ORDER BY sortDate DESC LIMIT 300"""
    )
    suspend fun search(q: String): List<Entry>

    @Query("SELECT * FROM entry_term WHERE entryId IN (:ids)")
    suspend fun termsFor(ids: List<Long>): List<EntryTerm>

    @Query("SELECT term, COUNT(*) AS df FROM entry_term GROUP BY term")
    suspend fun documentFrequencies(): List<TermDf>

    @Query("SELECT COUNT(DISTINCT entryId) FROM entry_term")
    suspend fun termDocumentCount(): Int

    @Query("SELECT COUNT(*) AS count, MIN(sortDate) AS oldest FROM entry WHERE feedId = :feedId")
    suspend fun volume(feedId: Long): FeedVolume

    @Query("SELECT COUNT(*) FROM entry WHERE fetchedAt >= :since AND viewedAt IS NULL")
    suspend fun countFetchedSince(since: Long): Int

    @Query("UPDATE entry SET viewedAt = :ts WHERE id IN (:ids) AND viewedAt IS NULL")
    suspend fun markViewed(ids: List<Long>, ts: Long): Int

    @Query("UPDATE entry SET openedAt = :ts WHERE id = :id")
    suspend fun setOpened(id: Long, ts: Long)

    @Query("UPDATE entry SET readSeconds = readSeconds + :seconds WHERE id = :id")
    suspend fun addReadSeconds(id: Long, seconds: Int)

    @Query("UPDATE entry SET bookmarkedAt = :ts WHERE id = :id")
    suspend fun setBookmarked(id: Long, ts: Long?)

    @Query("UPDATE entry SET favoritedAt = :ts WHERE id = :id")
    suspend fun setFavorited(id: Long, ts: Long?)

    @Query("UPDATE entry SET dismissedAt = :ts WHERE id = :id")
    suspend fun setDismissed(id: Long, ts: Long?)

    @Query("UPDATE entry SET extractedHtml = :html WHERE id = :id")
    suspend fun setExtracted(id: Long, html: String)

    @Query("DELETE FROM entry WHERE sortDate < :before AND bookmarkedAt IS NULL AND favoritedAt IS NULL")
    suspend fun prune(before: Long): Int
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile_term")
    suspend fun all(): List<ProfileTerm>

    @Upsert
    suspend fun upsert(terms: List<ProfileTerm>)

    @Query("DELETE FROM profile_term WHERE ABS(weight) < :threshold")
    suspend fun deleteBelow(threshold: Double)

    @Query("DELETE FROM profile_term")
    suspend fun clear()
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: Setting)
}

/** Clears subscriptions, entries and everything learned from them, in one transaction. Settings stay. */
@Dao
abstract class ResetDao {
    @Query("DELETE FROM entry_term")
    protected abstract suspend fun clearEntryTerms()

    @Query("DELETE FROM entry")
    protected abstract suspend fun clearEntries()

    @Query("DELETE FROM feed")
    protected abstract suspend fun clearFeeds()

    @Query("DELETE FROM folder")
    protected abstract suspend fun clearFolders()

    @Query("DELETE FROM profile_term")
    protected abstract suspend fun clearProfileTerms()

    /** Bookkeeping tied to the learned profile. */
    @Query("DELETE FROM setting WHERE key = 'profile.lastDecay'")
    protected abstract suspend fun clearLearningState()

    @Transaction
    open suspend fun clearSubscriptionsAndLearning() {
        clearEntryTerms()
        clearEntries()
        clearFeeds()
        clearFolders()
        clearProfileTerms()
        clearLearningState()
    }
}
