package algofeed.fetch

import algofeed.SecretReader
import algofeed.SecretStore
import algofeed.data.Feed
import algofeed.data.MediaItem
import algofeed.data.MediaKind
import algofeed.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A Tumblr blog's posts via the public API v2, which needs the user's own API key (a consumer key
 * registered at tumblr.com/oauth/apps), read from [secrets]. Accepts `blog.tumblr.com`,
 * `tumblr.com/blog`, or `tumblr:blog`.
 */
class TumblrAdapter(private val client: HttpClient, private val secrets: SecretReader?) : SourceAdapter {
    override val type = "tumblr"

    private val json = Json { ignoreUnknownKeys = true }
    private val prefixed = Regex("""^tumblr:([a-z0-9-]+)$""", RegexOption.IGNORE_CASE)
    private val subdomain = Regex("""^(?:https?://)?([a-z0-9-]+)\.tumblr\.com""", RegexOption.IGNORE_CASE)
    private val path = Regex("""tumblr\.com/(?:blog/view/)?([a-z0-9-]+)""", RegexOption.IGNORE_CASE)
    private val reserved = setOf("www", "api", "assets", "dashboard", "explore", "settings", "blog")

    private fun blog(input: String): String? {
        val s = input.trim()
        prefixed.matchEntire(s)?.let { return it.groupValues[1].lowercase() }
        subdomain.find(s)?.groupValues?.get(1)?.lowercase()?.takeUnless { it in reserved }?.let { return it }
        path.find(s)?.groupValues?.get(1)?.lowercase()?.takeUnless { it in reserved }?.let { return it }
        return null
    }

    override fun accepts(input: String) = blog(input) != null

    override suspend fun resolve(input: String): FeedInfo {
        val blog = blog(input) ?: throw FetchException("Not a Tumblr blog")
        return FeedInfo(
            type = type,
            // The key is added at fetch time so it is never stored in the feed row.
            url = "https://api.tumblr.com/v2/blog/$blog.tumblr.com/posts?limit=20",
            title = blog,
            siteUrl = "https://$blog.tumblr.com",
            iconUrl = "https://api.tumblr.com/v2/blog/$blog.tumblr.com/avatar/96",
        )
    }

    override suspend fun fetch(feed: Feed): FetchResult {
        val key = secrets?.get(SecretStore.TUMBLR_API_KEY)?.ifBlank { null }
            ?: throw FetchException("Add your Tumblr API key in Settings to follow Tumblr blogs")
        val url = feed.url + "&api_key=" + key.encodeURLParameter()
        val posts = json.parseToJsonElement(client.getOk(url).bodyAsText())
            .jsonObject["response"]?.jsonObject?.get("posts")?.jsonArray
            ?: throw FetchException("Unexpected Tumblr response")
        val now = nowMillis()
        return FetchResult.Fetched(posts.mapNotNull { toDraft(it.jsonObject, now) })
    }

    private fun toDraft(o: JsonObject, now: Long): EntryDraft? {
        val postUrl = o.str("post_url") ?: return null
        val media = photos(o)
        val body = o.str("body") ?: o.str("caption") ?: o.str("text") ?: o.str("description")
        return EntryDraft(
            remoteId = o.str("id_string") ?: o["id"]?.jsonPrimitive?.longOrNull?.toString() ?: postUrl,
            url = postUrl,
            title = o.str("title"),
            sortDate = (o["timestamp"]?.jsonPrimitive?.longOrNull ?: 0).let { if (it > 0) it * 1000 else now },
            commentsUrl = postUrl,
            author = o.str("blog_name"),
            summaryHtml = o.str("summary")?.ifBlank { null },
            contentHtml = body?.ifBlank { null },
            thumbnailUrl = media.firstOrNull()?.url,
            media = media,
        )
    }

    private fun photos(o: JsonObject): List<MediaItem> =
        o["photos"]?.jsonArray.orEmpty().mapNotNull { photo ->
            val p = photo.jsonObject
            val url = p["original_size"]?.jsonObject?.str("url") ?: return@mapNotNull null
            MediaItem(url, MediaKind.IMAGE, caption = p.str("caption")?.ifBlank { null })
        }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}
