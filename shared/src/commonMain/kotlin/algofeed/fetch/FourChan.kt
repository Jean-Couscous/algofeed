package algofeed.fetch

import algofeed.data.Feed
import algofeed.data.MediaItem
import algofeed.data.MediaKind
import algofeed.util.Html
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A 4chan board's catalog (thread OPs) via the read-only JSON API. No key. Accepts a board URL
 * (`boards.4chan.org/g/`, `4channel.org/g`) or the shorthand `4chan:g`.
 */
class FourChanAdapter(private val client: HttpClient) : SourceAdapter {
    override val type = "4chan"

    private val json = Json { ignoreUnknownKeys = true }
    private val hostBoard = Regex("""(?:boards\.)?4chan(?:nel)?\.org/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val shorthand = Regex("""^4chan:([a-z0-9]+)$""", RegexOption.IGNORE_CASE)

    private fun board(input: String): String? =
        shorthand.matchEntire(input.trim())?.groupValues?.get(1)?.lowercase()
            ?: hostBoard.find(input)?.groupValues?.get(1)?.lowercase()

    override fun accepts(input: String) = board(input) != null

    override suspend fun resolve(input: String): FeedInfo {
        val board = board(input) ?: throw FetchException("Not a 4chan board")
        return FeedInfo(
            type = type,
            url = "https://a.4cdn.org/$board/catalog.json",
            title = "/$board/ - 4chan",
            siteUrl = "https://boards.4chan.org/$board/",
            iconUrl = "https://s.4cdn.org/image/favicon.ico",
        )
    }

    override suspend fun fetch(feed: Feed): FetchResult {
        val board = board(feed.siteUrl ?: feed.url) ?: Regex("""a\.4cdn\.org/([a-z0-9]+)/""").find(feed.url)?.groupValues?.get(1)
            ?: throw FetchException("Not a 4chan board")
        return FetchResult.Fetched(parseCatalog(client.getOk(feed.url).bodyAsText(), board))
    }

    fun parseCatalog(body: String, board: String): List<EntryDraft> {
        val pages = json.parseToJsonElement(body).jsonArray
        return pages.flatMap { page ->
            page.jsonObject["threads"]?.jsonArray.orEmpty().mapNotNull { thread ->
                val o = thread.jsonObject
                val no = o["no"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val threadUrl = "https://boards.4chan.org/$board/thread/$no"
                val com = o.str("com")
                val subject = o.str("sub")?.let(Html::toText)
                    ?: com?.let { Html.toText(it).take(80).ifBlank { null } }
                    ?: "/$board/ thread $no"
                val media = image(o, board)
                EntryDraft(
                    remoteId = no.toString(),
                    url = threadUrl,
                    title = subject,
                    sortDate = (o["time"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
                    commentsUrl = threadUrl,
                    author = o.str("name")?.takeUnless { it == "Anonymous" },
                    contentHtml = com,
                    thumbnailUrl = media.firstOrNull()?.thumbnailUrl,
                    media = media,
                )
            }
        }
    }

    private fun image(o: JsonObject, board: String): List<MediaItem> {
        val tim = o["tim"]?.jsonPrimitive?.longOrNull ?: return emptyList()
        val ext = o.str("ext") ?: return emptyList()
        val kind = when (ext.lowercase()) {
            ".webm", ".mp4" -> MediaKind.VIDEO
            ".gif" -> MediaKind.GIF
            else -> MediaKind.IMAGE
        }
        return listOf(
            MediaItem(
                url = "https://i.4cdn.org/$board/$tim$ext",
                kind = kind,
                thumbnailUrl = "https://i.4cdn.org/$board/${tim}s.jpg",
                caption = o.str("filename")?.let { it + (o.str("ext") ?: "") },
            ),
        )
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }
}
