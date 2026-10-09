package algofeed.fetch

import algofeed.data.Feed
import algofeed.data.MediaItem
import algofeed.data.MediaKind
import algofeed.util.Html
import algofeed.util.nowMillis
import kotlinx.coroutines.CancellationException
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Hacker News front page via the official Algolia API. The old hnrss.org mirror was unreliable
 * (frequent 5xx and empty responses), which surfaced as a persistent feed error; Algolia does not.
 * Links point to the article, comments to the HN item page.
 */
class HackerNewsAdapter(private val client: HttpClient) : SourceAdapter {
    override val type = "hn"

    private val json = Json { ignoreUnknownKeys = true }

    override fun accepts(input: String): Boolean {
        val s = input.lowercase()
        return s == "hn" || s == "hackernews" || "news.ycombinator.com" in s || "hnrss.org" in s
    }

    override suspend fun resolve(input: String) = FeedInfo(
        type = type,
        url = FRONT_PAGE,
        title = "Hacker News",
        siteUrl = HN_SITE,
        iconUrl = "$HN_SITE/favicon.ico",
    )

    // Feeds subscribed under the old hnrss URL still resolve here by type; always read the Algolia front page.
    override suspend fun fetch(feed: Feed): FetchResult =
        FetchResult.Fetched(parseFrontPage(client.getOk(FRONT_PAGE).bodyAsText()))

    fun parseFrontPage(body: String): List<EntryDraft> {
        val hits = json.parseToJsonElement(body).jsonObject["hits"]?.jsonArray
            ?: throw FetchException("Unexpected Hacker News response")
        return hits.mapNotNull { hit ->
            val o = hit.jsonObject
            val id = o.str("objectID") ?: return@mapNotNull null
            val comments = "$HN_SITE/item?id=$id"
            EntryDraft(
                remoteId = id,
                url = o.str("url") ?: comments,
                title = o.str("title"),
                sortDate = (o["created_at_i"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
                commentsUrl = comments,
                author = o.str("author"),
            )
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }

    private companion object {
        const val HN_SITE = "https://news.ycombinator.com"
        const val FRONT_PAGE = "https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=50"
    }
}

/** Lobsters, front page or a tag (`lobste.rs/t/rust`). */
class LobstersAdapter(client: HttpClient, parser: RssParser = createRssParser()) : RssAdapter(client, parser) {
    override val type = "lobsters"

    override fun accepts(input: String) = "lobste.rs" in input.lowercase() || input.lowercase() == "lobsters"

    override fun feedUrlFor(input: String): String {
        val url = if (input.lowercase() == "lobsters") "https://lobste.rs" else input.withScheme().trimEnd('/')
        return when {
            url.endsWith(".rss") -> url
            "/t/" in url -> "$url.rss"
            else -> "https://lobste.rs/rss"
        }
    }

    override fun mapItem(item: RssItem, draft: EntryDraft): EntryDraft {
        // Text posts link to themselves; keep the discussion link only when it differs.
        val comments = draft.commentsUrl?.takeIf { it != draft.url }
        // Link posts carry only a "Comments" anchor as their description.
        val summary = draft.summaryHtml?.takeUnless { Html.toText(it) == "Comments" }
        return draft.copy(commentsUrl = comments, summaryHtml = summary)
    }
}

/**
 * Mastodon (and other ActivityPub servers exposing the same RSS paths):
 * `@user@server`, `https://server/@user`, `#tag@server` or `https://server/tags/tag`.
 */
class MastodonAdapter(client: HttpClient, parser: RssParser = createRssParser()) : RssAdapter(client, parser) {
    override val type = "mastodon"

    private val handle = Regex("""^@?([\w.]+)@([\w.-]+\.[a-z]{2,})$""", RegexOption.IGNORE_CASE)
    private val tag = Regex("""^#(\w+)@([\w.-]+\.[a-z]{2,})$""", RegexOption.IGNORE_CASE)
    private val profileUrl = Regex("""^https?://[\w.-]+/@[\w.]+/?$""")
    private val tagUrl = Regex("""^https?://[\w.-]+/tags/\w+/?$""")

    override fun accepts(input: String): Boolean =
        handle.matches(input) || tag.matches(input) || profileUrl.matches(input) || tagUrl.matches(input) ||
            (input.endsWith(".rss") && ("/@" in input || "/tags/" in input))

    override fun feedUrlFor(input: String): String {
        handle.matchEntire(input)?.let { return "https://${it.groupValues[2]}/@${it.groupValues[1]}.rss" }
        tag.matchEntire(input)?.let { return "https://${it.groupValues[2]}/tags/${it.groupValues[1]}.rss" }
        return if (input.endsWith(".rss")) input else input.trimEnd('/') + ".rss"
    }

    // Statuses have no title; the card shows the status text instead.
    override fun mapItem(item: RssItem, draft: EntryDraft) = draft.copy(title = null)
}

/** YouTube channel (`/channel/UC…`, `/@handle`, or a feeds/videos.xml URL). */
class YouTubeAdapter(client: HttpClient, parser: RssParser = createRssParser()) : RssAdapter(client, parser) {
    override val type = "youtube"

    private val channelId = Regex("""(UC[\w-]{22})""")

    override fun accepts(input: String) = "youtube.com" in input.lowercase() || "youtu.be" in input.lowercase()

    override suspend fun resolve(input: String): FeedInfo {
        val url = input.withScheme()
        val feedUrl = when {
            "feeds/videos.xml" in url -> url
            "/channel/" in url -> channelId.find(url)?.let { feedFor(it.value) }
            else -> null
        } ?: run {
            // Handle or custom URL: the channel page carries the id. The consent cookie avoids the EU interstitial.
            val page = client.getOk(url) { header("Cookie", "SOCS=CAI; CONSENT=YES+1") }.bodyAsText()
            RssAdapter.discoverFeedUrl(page, url)
                ?: Regex(""""(?:channelId|externalId)":"(UC[\w-]{22})"""").find(page)?.groupValues?.get(1)?.let(::feedFor)
                ?: throw FetchException("Could not find a channel id at $url")
        }
        return resolveUrl(feedUrl, discover = false)
    }

    private fun feedFor(id: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$id"
}

/**
 * Kagi News category through its RSS feed: `kagi` (World), `kagi:tech`, `kagi technology` or a
 * news.kagi.com / kite.kagi.com URL. Items carry the whole story (summary, highlights, sources).
 */
class KagiNewsAdapter(client: HttpClient, parser: RssParser = createRssParser()) : RssAdapter(client, parser) {
    override val type = "kagi"

    private val json = Json { ignoreUnknownKeys = true }
    private val shorthand = Regex("""^kagi(?:\s*[:\s]\s*(.+))?$""", RegexOption.IGNORE_CASE)

    override fun accepts(input: String): Boolean {
        val s = input.lowercase()
        return shorthand.matches(input.trim()) || "news.kagi.com" in s || "kite.kagi.com" in s
    }

    override suspend fun resolve(input: String): FeedInfo {
        val trimmed = input.trim()
        val category = shorthand.matchEntire(trimmed)?.let { it.groupValues[1].ifBlank { "world" } }
            ?.let { categoryId(it) }
            ?: trimmed.substringAfter(".kagi.com", "").trimStart('/').substringBefore('/').substringBefore('?')
                .decodeURLPart().removeSuffix(".xml").ifBlank { "world" }
        val info = resolveUrl("https://kite.kagi.com/${category.encodeURLPathPart()}.xml", discover = false)
        return info.copy(siteUrl = "https://news.kagi.com/$category", iconUrl = "https://news.kagi.com/apple-touch-icon.png")
    }

    /** Matches [name] against the ids and names of the categories in the latest batch. */
    private suspend fun categoryId(name: String): String {
        val wanted = name.trim().lowercase()
        val categories = runCatching {
            json.parseToJsonElement(client.getOk("https://kite.kagi.com/api/batches/latest/categories").bodyAsText())
                .jsonObject["categories"]?.jsonArray.orEmpty().map { it.jsonObject }
        }.getOrDefault(emptyList())
        val match = categories.firstOrNull { it.text("categoryId")?.lowercase() == wanted }
            ?: categories.firstOrNull { it.text("categoryName")?.lowercase() == wanted }
        return match?.text("categoryId") ?: wanted.replace(Regex("""\s+"""), "_")
    }

    // The description is the whole story, so the reader shows it as is.
    override fun mapItem(item: RssItem, draft: EntryDraft) = draft.copy(contentHtml = draft.summaryHtml)

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}

/**
 * Subreddits, multireddits and public custom feeds via the public JSON listing (`r/rust`,
 * `reddit.com/r/rust/top`, `u/someone/m/feed`). The entry links to the submitted URL and keeps the Reddit thread as the discussion link.
 */
class RedditAdapter(
    private val client: HttpClient,
    private val rss: RssAdapter = RssAdapter(client),
) : SourceAdapter {
    override val type = "reddit"

    private val json = Json { ignoreUnknownKeys = true }
    private val shorthand = Regex("""^/?r/([\w+]+)/?$""")
    private val customFeedShorthand = Regex("""^/?(?:u|user)/([\w-]+)/m/(\w+)/?$""", RegexOption.IGNORE_CASE)
    private val customFeedPath = Regex("""^/user/([\w-]+)/m/(\w+)$""", RegexOption.IGNORE_CASE)

    /** Reddit refuses the JSON API to some networks; after a 403, go straight to RSS for a while. */
    private var jsonRefusedUntil = 0L

    private suspend fun jsonOrNull(url: String): String? {
        if (nowMillis() < jsonRefusedUntil) return null
        return try {
            client.getOk(url).bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A timeout or IO error must still fall through to the RSS listing, not become a hard failure.
            // When the network is refusing or throttling us (403/429/503), stop hammering JSON for a while.
            val msg = e.message ?: ""
            if (REFUSAL_CODES.any { it in msg }) jsonRefusedUntil = nowMillis() + JSON_RETRY_MS
            null
        }
    }

    override fun accepts(input: String) =
        "reddit.com/" in input.lowercase() || shorthand.matches(input) || customFeedShorthand.matches(input)

    private fun canonical(input: String): String {
        shorthand.matchEntire(input)?.let { return "https://www.reddit.com/r/${it.groupValues[1]}" }
        customFeedShorthand.matchEntire(input)?.let { return "https://www.reddit.com/user/${it.groupValues[1]}/m/${it.groupValues[2]}" }
        return input.withScheme()
            .replace(Regex("""https?://(old\.|new\.|np\.)?reddit\.com"""), "https://www.reddit.com")
            .substringBefore('?')
            .removeSuffix(".json").removeSuffix(".rss")
            .trimEnd('/')
            .replace(Regex("""reddit\.com/u/"""), "reddit.com/user/")
    }

    override suspend fun resolve(input: String): FeedInfo {
        val url = canonical(input)
        val path = url.substringAfter("reddit.com")
        val custom = customFeedPath.matchEntire(path)
        return FeedInfo(
            type = type,
            url = url,
            title = custom?.let { "${it.groupValues[2]} (u/${it.groupValues[1]})" } ?: path.trim('/'),
            siteUrl = url,
            iconUrl = "https://www.reddit.com/favicon.ico",
        )
    }

    override suspend fun fetch(feed: Feed): FetchResult {
        // Reddit sometimes refuses unauthenticated JSON; the RSS listing usually still works.
        val body = jsonOrNull("${feed.url}/.json?limit=50&raw_json=1")
            ?: return rss.fetch(feed.copy(url = "${feed.url}/.rss")).let { result ->
                if (result is FetchResult.Fetched) result.copy(entries = result.entries.map(::fromRss)) else result
            }
        return FetchResult.Fetched(parseListing(body))
    }

    fun parseListing(body: String): List<EntryDraft> {
        val now = nowMillis()
        val children = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("children")?.jsonArray
            ?: throw FetchException("Unexpected Reddit response")
        return children.mapNotNull { child ->
            val d = child.jsonObject["data"]?.jsonObject ?: return@mapNotNull null
            if (d.bool("stickied")) return@mapNotNull null
            val permalink = "https://www.reddit.com" + d.str("permalink")
            val isSelf = d.bool("is_self")
            val media = redditMedia(d)
            val thumbnail = d.str("thumbnail")?.takeIf { it.startsWith("http") }
                ?: d["preview"]?.jsonObject?.get("images")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("source")?.jsonObject?.str("url")
                ?: media.firstOrNull()?.let { it.thumbnailUrl ?: it.url }
            EntryDraft(
                remoteId = d.str("name") ?: permalink,
                url = if (isSelf) permalink else d.str("url") ?: permalink,
                title = d.str("title"),
                sortDate = d["created_utc"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() } ?: now,
                commentsUrl = permalink,
                author = d.str("author")?.let { "u/$it" },
                summaryHtml = d.str("selftext_html"),
                contentHtml = if (isSelf) d.str("selftext_html") else null,
                thumbnailUrl = thumbnail,
                media = media,
            )
        }
    }

    /** Gallery pages and Reddit-hosted videos, resolved to their attachments. Needs `raw_json=1` so URLs aren't HTML-escaped. */
    private fun redditMedia(d: JsonObject): List<MediaItem> {
        if (d.bool("is_gallery")) {
            val meta = d["media_metadata"]?.jsonObject ?: return emptyList()
            val items = d["gallery_data"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
            return items.mapNotNull { item ->
                val o = item.jsonObject
                val m = meta[o.str("media_id")]?.jsonObject ?: return@mapNotNull null
                val source = m["s"]?.jsonObject
                val gif = source?.str("gif")
                val url = gif ?: source?.str("u") ?: return@mapNotNull null
                val thumb = m["p"]?.jsonArray?.lastOrNull()?.jsonObject?.str("u")
                MediaItem(url, if (gif != null) MediaKind.GIF else MediaKind.IMAGE, thumbnailUrl = thumb, caption = o.str("caption"))
            }
        }
        d["media"]?.jsonObject?.get("reddit_video")?.jsonObject?.let { rv ->
            val video = rv.str("fallback_url") ?: return@let
            val thumb = d.str("thumbnail")?.takeIf { it.startsWith("http") }
                ?: d["preview"]?.jsonObject?.get("images")?.jsonArray?.firstOrNull()?.jsonObject?.get("source")?.jsonObject?.str("url")
            // fallback_url is video-only; the HLS/DASH manifest carries audio for inline playback.
            val stream = rv.str("hls_url") ?: rv.str("dash_url")
            return listOf(MediaItem(video, MediaKind.VIDEO, thumbnailUrl = thumb, streamUrl = stream))
        }
        return emptyList()
    }

    private fun fromRss(draft: EntryDraft): EntryDraft {
        // Reddit RSS bodies end with "<a href=…>[link]</a> <a href=…>[comments]</a>".
        val link = draft.contentHtml?.let { Regex("""<a href="([^"]+)">\[link]</a>""").find(it)?.groupValues?.get(1) }
        return draft.copy(
            url = link ?: draft.url,
            commentsUrl = draft.url,
            summaryHtml = stripTrailer(draft.summaryHtml),
            contentHtml = stripTrailer(draft.contentHtml),
        )
    }

    /** Drops the "submitted by /u/x [link] [comments]" footer; null if nothing else remains. */
    private fun stripTrailer(html: String?): String? {
        val body = html?.replace(Regex("""(?s)(&#32;|\s)*submitted by.*"""), "")
        return body?.takeIf { Html.toText(it).isNotBlank() }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }

    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true

    private companion object {
        const val JSON_RETRY_MS = 15 * 60_000L
        val REFUSAL_CODES = listOf("HTTP 403", "HTTP 429", "HTTP 503")
    }
}

fun defaultSources(client: HttpClient, secrets: algofeed.SecretReader? = null): Sources {
    val parser = createRssParser()
    val rss = RssAdapter(client, parser)
    return Sources(
        adapters = listOf(
            RedditAdapter(client, rss),
            YouTubeAdapter(client, parser),
            HackerNewsAdapter(client),
            KagiNewsAdapter(client, parser),
            LobstersAdapter(client, parser),
            MastodonAdapter(client, parser),
            BlueskyAdapter(client),
            FourChanAdapter(client),
            TumblrAdapter(client, secrets),
            MangadexFollowsAdapter(client, MangadexAuth(client, secrets)),
            MangadexAdapter(client),
            rss,
        ),
        fallback = rss,
    )
}

