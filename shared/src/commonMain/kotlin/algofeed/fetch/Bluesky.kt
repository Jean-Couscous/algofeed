package algofeed.fetch

import algofeed.data.Feed
import algofeed.data.MediaItem
import algofeed.data.MediaKind
import algofeed.util.Dates
import algofeed.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Bluesky account's posts through the public AppView (no login). Accepts a profile URL
 * (`bsky.app/profile/handle`), a `@handle`, a bare `handle.bsky.social`, a DID, or `bsky:handle`.
 */
class BlueskyAdapter(private val client: HttpClient) : SourceAdapter {
    override val type = "bluesky"

    private val json = Json { ignoreUnknownKeys = true }
    private val profileUrl = Regex("""bsky\.app/profile/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val prefixed = Regex("""^bsky:(.+)$""", RegexOption.IGNORE_CASE)
    // A leading @ signals a handle; a bare one must end in .bsky.social so generic domains stay RSS.
    private val handle = Regex("""^@([a-z0-9][a-z0-9.\-]*\.[a-z]{2,})$""", RegexOption.IGNORE_CASE)
    private val defaultDomain = Regex("""^[a-z0-9][a-z0-9.\-]*\.bsky\.social$""", RegexOption.IGNORE_CASE)
    private val did = Regex("""^did:[a-z0-9:%\-]+$""", RegexOption.IGNORE_CASE)

    private fun actor(input: String): String? {
        val s = input.trim()
        profileUrl.find(s)?.let { return it.groupValues[1] }
        prefixed.matchEntire(s)?.let { return it.groupValues[1].removePrefix("@") }
        handle.matchEntire(s)?.let { return it.groupValues[1] }
        if (defaultDomain.matches(s) || did.matches(s)) return s
        return null
    }

    override fun accepts(input: String) = actor(input) != null

    private fun feedUrl(actor: String) =
        "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?limit=40&actor=${actor.encodeURLParameter()}"

    override suspend fun resolve(input: String): FeedInfo {
        val actor = actor(input) ?: throw FetchException("Not a Bluesky account")
        return FeedInfo(
            type = type,
            url = feedUrl(actor),
            title = if (actor.startsWith("did:")) actor else "@$actor",
            siteUrl = "https://bsky.app/profile/$actor",
            iconUrl = "https://bsky.app/static/favicon-32x32.png",
        )
    }

    override suspend fun fetch(feed: Feed): FetchResult =
        FetchResult.Fetched(parseFeed(client.getOk(feed.url).bodyAsText()))

    fun parseFeed(body: String): List<EntryDraft> {
        val now = nowMillis()
        val items = json.parseToJsonElement(body).jsonObject["feed"]?.jsonArray
            ?: throw FetchException("Unexpected Bluesky response")
        return items.mapNotNull { item ->
            val post = item.jsonObject["post"]?.jsonObject ?: return@mapNotNull null
            val uri = post.str("uri") ?: return@mapNotNull null
            val author = post["author"]?.jsonObject
            val handle = author?.str("handle") ?: return@mapNotNull null
            val rkey = uri.substringAfterLast('/')
            val record = post["record"]?.jsonObject
            val text = record?.str("text").orEmpty()
            val media = images(post)
            val postUrl = "https://bsky.app/profile/$handle/post/$rkey"
            EntryDraft(
                remoteId = uri,
                url = postUrl,
                // Posts have no title; the card shows the text, as for Mastodon.
                title = null,
                sortDate = Dates.parse(record?.str("createdAt") ?: post.str("indexedAt")) ?: now,
                commentsUrl = postUrl,
                author = author.str("displayName")?.ifBlank { null } ?: "@$handle",
                contentHtml = text.replace("\n", "<br>").ifBlank { null },
                thumbnailUrl = media.firstOrNull()?.let { it.thumbnailUrl ?: it.url },
                media = media,
            )
        }
    }

    private fun images(post: JsonObject): List<MediaItem> {
        val embed = post["embed"]?.jsonObject ?: return emptyList()
        // Plain image embeds, and the media half of a quote-with-media embed.
        val images = embed["images"]?.jsonArray ?: embed["media"]?.jsonObject?.get("images")?.jsonArray ?: return emptyList()
        return images.mapNotNull { img ->
            val o = img.jsonObject
            val url = o.str("fullsize") ?: o.str("thumb") ?: return@mapNotNull null
            MediaItem(url, MediaKind.IMAGE, thumbnailUrl = o.str("thumb"), caption = o.str("alt")?.ifBlank { null })
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}
