package algofeed.fetch

import algofeed.data.Feed
import algofeed.util.Dates
import algofeed.util.Html
import algofeed.util.Urls
import algofeed.util.nowMillis
import com.fleeksoft.ksoup.Ksoup
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/** Platform-specific parser construction (the JVM build takes an OkHttp client). */
expect fun createRssParser(): RssParser

/**
 * Generic RSS/Atom/RDF source. Subclasses rewrite user input into a feed URL ([feedUrlFor]) and
 * adjust entries ([mapItem]).
 */
open class RssAdapter(
    protected val client: HttpClient,
    protected val parser: RssParser = createRssParser(),
) : SourceAdapter {
    override val type = "rss"

    override fun accepts(input: String) = true

    open fun feedUrlFor(input: String): String = input.withScheme()

    open fun defaultTitle(channel: RssChannel, url: String): String =
        channel.title?.let(Html::toText)?.ifBlank { null } ?: Urls.host(url) ?: url

    override suspend fun resolve(input: String): FeedInfo = resolveUrl(feedUrlFor(input), discover = true)

    protected suspend fun resolveUrl(url: String, discover: Boolean): FeedInfo {
        val response = client.getOk(url)
        val finalUrl = response.call.request.url.toString()
        val body = response.bodyAsText()
        if (!looksLikeFeed(body)) {
            if (!discover) throw FetchException("No feed found at $url")
            val discovered = discoverFeedUrl(body, finalUrl) ?: throw FetchException("No feed found at $url")
            return resolveUrl(discovered, discover = false)
        }
        val channel = parser.parse(body)
        val site = channel.link?.takeIf { it.startsWith("http") } ?: Urls.origin(finalUrl)
        return FeedInfo(
            type = type,
            url = finalUrl,
            title = defaultTitle(channel, finalUrl),
            siteUrl = site,
            iconUrl = channel.image?.url?.takeIf { it.startsWith("http") } ?: site?.let { Urls.origin(it) + "/favicon.ico" },
        )
    }

    override suspend fun fetch(feed: Feed): FetchResult {
        val response = client.getOk(feed.url) {
            feed.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            feed.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }
        if (response.status.value == 304) return FetchResult.NotModified
        val channel = parser.parse(response.bodyAsText())
        val now = nowMillis()
        return FetchResult.Fetched(
            entries = channel.items.mapNotNull { item -> toDraft(item, feed, now)?.let { mapItem(item, it) } },
            etag = response.headers[HttpHeaders.ETag],
            lastModified = response.headers[HttpHeaders.LastModified],
        )
    }

    protected open fun mapItem(item: RssItem, draft: EntryDraft): EntryDraft = draft

    private fun toDraft(item: RssItem, feed: Feed, now: Long): EntryDraft? {
        val link = item.link?.trim()?.ifEmpty { null }
        val remoteId = item.guid?.trim()?.ifEmpty { null } ?: link ?: item.title ?: return null
        val content = item.content?.ifBlank { null }
        val description = item.description?.ifBlank { null }
        val base = link ?: feed.siteUrl ?: feed.url
        val thumbnail = item.youtubeItemData?.thumbnailUrl
            ?: item.image?.takeIf { it.startsWith("http") }
            ?: item.rawMediaContent?.takeIf { it.medium == "image" || it.type?.startsWith("image") == true }?.url
            ?: item.rawEnclosure?.takeIf { it.type?.startsWith("image") == true }?.url
            ?: Html.firstImage(content ?: description, base)
        return EntryDraft(
            remoteId = remoteId,
            url = link?.let { Urls.resolve(feed.siteUrl ?: feed.url, it) },
            title = item.title?.let(Html::toText)?.ifBlank { null },
            sortDate = Dates.parse(item.pubDate)?.coerceAtMost(now) ?: now,
            commentsUrl = item.commentsUrl?.ifBlank { null },
            author = item.author?.let(Html::toText)?.ifBlank { null },
            summaryHtml = description ?: item.youtubeItemData?.description,
            contentHtml = content,
            thumbnailUrl = thumbnail,
        )
    }

    companion object {
        fun looksLikeFeed(body: String): Boolean {
            val head = body.take(1000).lowercase()
            return "<rss" in head || "<feed" in head || "<rdf:rdf" in head
        }

        /** Finds `<link rel="alternate" type="application/rss+xml|atom+xml">` in an HTML page. */
        fun discoverFeedUrl(html: String, baseUrl: String): String? {
            val doc = Ksoup.parse(html, baseUrl)
            return doc.select("link[rel=alternate]")
                .firstOrNull { val t = it.attr("type").lowercase(); "rss" in t || "atom" in t }
                ?.let { Urls.resolve(baseUrl, it.attr("href")) }
        }
    }
}
