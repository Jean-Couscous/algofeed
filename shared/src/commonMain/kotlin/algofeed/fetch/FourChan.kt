package algofeed.fetch

import algofeed.data.Feed
import algofeed.data.MediaItem
import algofeed.data.MediaKind
import algofeed.util.Html
import algofeed.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
    private val threadPath = Regex("""4chan(?:nel)?\.org/([a-z0-9]+)/thread/(\d+)""", RegexOption.IGNORE_CASE)

    /** (board, thread number) from a thread's web URL. */
    private fun parseThreadUrl(url: String): Pair<String, Long>? =
        threadPath.find(url)?.let { it.groupValues[1].lowercase() to it.groupValues[2].toLong() }

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
        // a.4cdn.org honours If-Modified-Since, so an unchanged catalog comes back 304 with no body.
        val response = client.getOk(feed.url) {
            feed.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            feed.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }
        if (response.status.value == 304) return FetchResult.NotModified
        return FetchResult.Fetched(
            entries = parseCatalog(response.bodyAsText(), board),
            etag = response.headers[HttpHeaders.ETag],
            lastModified = response.headers[HttpHeaders.LastModified],
        )
    }

    /** The replies to one thread, read-only, from the keyless JSON API. The OP is shown by the reader. */
    suspend fun thread(threadUrl: String): CommentThread {
        val (board, no) = parseThreadUrl(threadUrl) ?: throw FetchException("Not a 4chan thread")
        // A deleted or pruned thread 404s here; point at a third-party archive instead of failing.
        val body = try {
            client.getOk("https://a.4cdn.org/$board/thread/$no.json").bodyAsText()
        } catch (e: FetchException) {
            if (e.status == 404) return archivedNotice(board, no) else throw e
        }
        val posts = json.parseToJsonElement(body)
            .jsonObject["posts"]?.jsonArray.orEmpty()
        // The first post is the OP; the rest are replies, flat (4chan has no reply tree).
        return CommentThread(posts.drop(1).map { post ->
            val o = post.jsonObject
            val tim = o["tim"]?.jsonPrimitive?.longOrNull
            val ext = o.str("ext")
            val file = if (tim != null && ext != null) "https://i.4cdn.org/$board/$tim$ext" else null
            val isVideo = ext == ".webm" || ext == ".mp4"
            Comment(
                id = o["no"]?.jsonPrimitive?.longOrNull ?: 0,
                author = o.str("name"),
                html = o.str("com"),
                time = (o["time"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
                imageUrl = file?.takeUnless { isVideo },
                videoUrl = file?.takeIf { isVideo },
                thumbnailUrl = if (tim != null && ext != null) "https://i.4cdn.org/$board/${tim}s.jpg" else null,
            )
        })
    }

    fun parseCatalog(body: String, board: String): List<EntryDraft> {
        val pages = json.parseToJsonElement(body).jsonArray
        return pages.flatMap { page ->
            page.jsonObject["threads"]?.jsonArray.orEmpty().mapNotNull { thread ->
                val o = thread.jsonObject
                val no = o["no"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val threadUrl = "https://boards.4chan.org/$board/thread/$no"
                val com = o.str("com")
                // Many threads have no subject; the comment is the body, so reusing it as the title
                // only duplicates the text. Label the title by board and number instead.
                val subject = o.str("sub")?.let(Html::toText) ?: "/$board/ thread $no"
                val media = image(o, board)
                EntryDraft(
                    remoteId = no.toString(),
                    url = threadUrl,
                    title = subject,
                    sortDate = (o["time"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
                    commentsUrl = threadUrl,
                    author = o.str("name")?.takeUnless { it == "Anonymous" },
                    contentHtml = com,
                    // 4chan serves only a 250px thumbnail or the full file; for a still image the card
                    // shows the full file (Coil downsamples it to the card and caches it for the reader).
                    thumbnailUrl = media.firstOrNull()?.let { if (it.kind == MediaKind.IMAGE) it.url else it.thumbnailUrl },
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

    /**
     * A one-comment stand-in for a thread 4chan no longer serves, linking to the FoolFuuka archive
     * that covers the board when one is known. Boards without a known archive get a plain notice.
     */
    private fun archivedNotice(board: String, no: Long): CommentThread {
        val archive = ARCHIVES.entries.firstOrNull { board in it.value }?.key
        val html = if (archive != null) {
            val link = "$archive/$board/thread/$no"
            "This thread is no longer on 4chan (deleted or pruned). It may still be readable on an " +
                "archive: <a href=\"$link\">$link</a>"
        } else {
            "This thread is no longer on 4chan (deleted or pruned), and no known archive covers /$board/."
        }
        return CommentThread(listOf(Comment(id = no, author = null, html = html, time = nowMillis())))
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }

    companion object {
        // Live FoolFuuka archives and the boards each keeps. A board in several archives takes the
        // first listed. Coverage shifts over time; update as archives add or drop boards.
        private val ARCHIVES = linkedMapOf(
            "https://desuarchive.org" to setOf(
                "a", "aco", "an", "c", "cgl", "co", "d", "fit", "g", "his", "int", "jp", "k", "m",
                "mlp", "mu", "q", "qa", "r9k", "tg", "trash", "vr", "vrpg", "wsg",
            ),
            "https://arch.b4k.dev" to setOf("v", "vg", "vm", "vmg", "vp", "vst", "qb"),
            "https://boards.4plebs.org" to setOf(
                "adv", "f", "hr", "o", "pol", "s4s", "sp", "trv", "tv", "x",
            ),
            "https://archiveofsins.com" to setOf("h", "hc", "hm", "i", "lgbt", "r", "s", "soc", "t", "u", "y"),
        )
    }
}
