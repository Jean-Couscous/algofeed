package algofeed

import kotlinx.serialization.Serializable

/**
 * A portable snapshot for moving between devices: subscriptions, settings and learned ranking.
 * Stored entries (and so bookmarks and favorites) are left out; they are refetched after import.
 */
@Serializable
data class Backup(
    val version: Int = 2,
    val settings: Settings = Settings(),
    val folders: List<BackupFolder> = emptyList(),
    val feeds: List<BackupFeed> = emptyList(),
    val profile: Map<String, Double> = emptyMap(),
)

@Serializable
data class BackupFolder(val name: String)

@Serializable
data class BackupFeed(
    val type: String,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    val folder: String? = null,
    val bucket: Int = 2,
    val impressions: Int = 0,
    val opens: Int = 0,
    val favorites: Int = 0,
    val dismissals: Int = 0,
    val preferFeedVersion: Boolean = false,
    val authors: List<BackupAuthor> = emptyList(),
)

@Serializable
data class BackupAuthor(
    val author: String,
    val impressions: Int = 0,
    val opens: Int = 0,
    val favorites: Int = 0,
    val dismissals: Int = 0,
)
