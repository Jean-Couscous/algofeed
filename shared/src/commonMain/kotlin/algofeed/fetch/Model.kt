package algofeed.fetch

import algofeed.data.Feed
import algofeed.data.MediaItem

data class FeedInfo(
    val type: String,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
)

data class EntryDraft(
    val remoteId: String,
    val url: String?,
    val title: String?,
    val sortDate: Long,
    val commentsUrl: String? = null,
    val author: String? = null,
    val summaryHtml: String? = null,
    val contentHtml: String? = null,
    val thumbnailUrl: String? = null,
    val media: List<MediaItem> = emptyList(),
)

sealed interface FetchResult {
    data class Fetched(
        val entries: List<EntryDraft>,
        val etag: String? = null,
        val lastModified: String? = null,
    ) : FetchResult

    data object NotModified : FetchResult
}

class FetchException(message: String, cause: Throwable? = null, val status: Int? = null) : Exception(message, cause)

/**
 * A kind of source. [accepts] decides from what the user typed (URL, handle, shorthand);
 * [resolve] turns that into a subscribable feed; [fetch] pulls its current entries.
 */
interface SourceAdapter {
    val type: String
    fun accepts(input: String): Boolean
    suspend fun resolve(input: String): FeedInfo
    suspend fun fetch(feed: Feed): FetchResult
}

/** Ordered list of adapters; the generic RSS adapter is the fallback. */
class Sources(val adapters: List<SourceAdapter>, private val fallback: SourceAdapter) {
    fun forInput(input: String): SourceAdapter = adapters.firstOrNull { it.accepts(input.trim()) } ?: fallback
    fun forFeed(feed: Feed): SourceAdapter = adapters.firstOrNull { it.type == feed.type } ?: fallback
    suspend fun resolve(input: String): FeedInfo = forInput(input).resolve(input.trim())
}
