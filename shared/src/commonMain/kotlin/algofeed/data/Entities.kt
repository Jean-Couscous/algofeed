package algofeed.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folder")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "feed",
    foreignKeys = [
        ForeignKey(Folder::class, ["id"], ["folderId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("url", unique = true), Index("folderId")],
)
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val type: String,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    /** feedi's frequency bucket: 0 = monthly or rarer … 5 = more than 20 posts a day. */
    val bucket: Int = 2,
    val lastFetch: Long? = null,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
    // Engagement counters for source affinity.
    val impressions: Int = 0,
    val opens: Int = 0,
    val favorites: Int = 0,
    val dismissals: Int = 0,
    /** The reader shows the feed's own content instead of extracting the article page. */
    @ColumnInfo(defaultValue = "0") val preferFeedVersion: Boolean = false,
)

@Entity(
    tableName = "entry",
    foreignKeys = [
        ForeignKey(Feed::class, ["id"], ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["feedId", "remoteId"], unique = true), Index("sortDate")],
)
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val remoteId: String,
    val url: String?,
    val commentsUrl: String? = null,
    val title: String?,
    val author: String? = null,
    val summaryHtml: String? = null,
    val contentHtml: String? = null,
    val thumbnailUrl: String? = null,
    val sortDate: Long,
    val fetchedAt: Long,
    val viewedAt: Long? = null,
    val openedAt: Long? = null,
    val readSeconds: Int = 0,
    val bookmarkedAt: Long? = null,
    val favoritedAt: Long? = null,
    val dismissedAt: Long? = null,
    /** Reader-mode extraction cache. */
    val extractedHtml: String? = null,
)

@Entity(
    tableName = "entry_term",
    primaryKeys = ["entryId", "term"],
    foreignKeys = [
        ForeignKey(Entry::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("term")],
)
data class EntryTerm(
    val entryId: Long,
    val term: String,
    val tf: Float,
)

/** One dimension of the learned interest vector. */
@Entity(tableName = "profile_term")
data class ProfileTerm(
    @PrimaryKey val term: String,
    val weight: Double,
)

@Entity(tableName = "setting")
data class Setting(
    @PrimaryKey val key: String,
    val value: String,
)

data class TermDf(val term: String, val df: Int)

data class FeedVolume(val count: Int, val oldest: Long?)
