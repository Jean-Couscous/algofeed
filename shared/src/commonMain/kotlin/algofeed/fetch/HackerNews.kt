package algofeed.fetch

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class HackerNewsException(message: String) : Exception(message)


/** What the item page offers the logged-in user: vote links per item, the favorite link and the reply form. */
data class HnItemPage(
    val upvoteUrls: Map<Long, String> = emptyMap(),
    val unvoteUrls: Map<Long, String> = emptyMap(),
    val faveUrl: String? = null,
    val favorited: Boolean = false,
    /** Token of the top-level comment form. */
    val hmac: String? = null,
    /** Comment ids in the order HN shows them. */
    val order: List<Long> = emptyList(),
) {
    fun voted(id: Long) = id in unvoteUrls
}


/**
 * Hacker News account actions. HN has no write API, so this does what the website does: the login
 * form sets a `user` cookie, and vote, favorite and reply links carry tokens scraped from item pages.
 * Threads come from the Algolia API, which returns the whole tree in one request.
 */
class HackerNews(private val client: HttpClient) {
    private val direct by lazy { client.config { followRedirects = false } }
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the session cookie value (`name&hash`). */
    suspend fun login(user: String, password: String): String {
        val response = direct.submitForm(
            "$BASE/login",
            parameters {
                append("acct", user)
                append("pw", password)
                append("goto", "news")
            },
        )
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .firstNotNullOfOrNull { cookie -> cookie.substringBefore(';').takeIf { it.startsWith("user=") }?.removePrefix("user=") }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val body = response.bodyAsText()
        throw HackerNewsException(
            if ("Validation required" in body || "recaptcha" in body) "Hacker News wants a captcha. Log in once in a browser, then try again."
            else "Wrong username or password",
        )
    }

    suspend fun itemPage(id: Long, session: String?): HnItemPage =
        parseItemPage(id, client.get("$BASE/item?id=$id") { session?.let { cookie(it) } }.bodyAsText())

    suspend fun thread(id: Long, session: String?): CommentThread = coroutineScope {
        // The page gives HN's ordering and, when logged in, the vote links; the thread still loads without it.
        val page = async { runCatching { itemPage(id, session) }.getOrNull() }
        val tree = parseAlgolia(client.getOk("https://hn.algolia.com/api/v1/items/$id").bodyAsText())
        val p = page.await()
        CommentThread(p?.order?.takeIf { it.isNotEmpty() }?.let { sortByPage(tree, it) } ?: tree, p)
    }

    /** Follows a vote or favorite link from [HnItemPage]. */
    suspend fun follow(path: String, session: String) {
        val response = direct.get("$BASE/$path") { cookie(session) }
        if (response.status.value >= 400) throw HackerNewsException("Hacker News answered ${response.status.value}")
        if (response.status.value == 200) failureText(response)?.let { throw HackerNewsException(it) }
    }

    /** Posts [text] as a reply to [parentId], a story or a comment in story [storyId]. */
    suspend fun reply(parentId: Long, storyId: Long, text: String, session: String, hmac: String? = null) {
        val goto = "item?id=$storyId"
        val token = hmac ?: replyHmac(client.get("$BASE/reply?id=$parentId&goto=item%3Fid%3D$storyId") { cookie(session) }.bodyAsText())
            ?: throw HackerNewsException("Hacker News didn't offer a reply form. Are you still logged in?")
        val response = direct.submitForm(
            "$BASE/comment",
            parameters {
                append("parent", parentId.toString())
                append("goto", goto)
                append("hmac", token)
                append("text", text)
            },
        ) { cookie(session) }
        // Success redirects back to the thread; errors ("You're posting too fast") come as a page.
        if (response.status.value != 302) {
            throw HackerNewsException(failureText(response) ?: "Hacker News answered ${response.status.value}")
        }
    }

    private suspend fun failureText(response: HttpResponse): String? {
        val text = Ksoup.parse(response.bodyAsText()).body().text().trim()
        return text.takeIf { it.isNotEmpty() && it.length < 300 }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.cookie(session: String) = header(HttpHeaders.Cookie, "user=$session")

    private fun parseAlgolia(body: String): List<Comment> {
        fun node(o: JsonObject): Comment = Comment(
            id = o["id"]!!.jsonPrimitive.longOrNull ?: 0,
            author = o.text("author"),
            html = o.text("text"),
            time = (o["created_at_i"]?.jsonPrimitive?.longOrNull ?: 0) * 1000,
            children = o["children"]?.jsonArray.orEmpty().map { node(it.jsonObject) }.filterNot { it.isEmptyDeleted() },
        )
        return node(json.parseToJsonElement(body).jsonObject).children
    }

    private fun Comment.isEmptyDeleted() = author == null && html == null && children.isEmpty()

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    companion object {
        const val BASE = "https://news.ycombinator.com"
        private val itemUrl = Regex("""news\.ycombinator\.com/item\?id=(\d+)""")

        /** The HN item id of a story or comment URL. */
        fun itemId(url: String?): Long? = url?.let { itemUrl.find(it)?.groupValues?.get(1)?.toLongOrNull() }

        fun userOf(session: String): String = session.substringBefore('&')

        fun parseItemPage(id: Long, html: String): HnItemPage {
            val doc = Ksoup.parse(html)
            fun links(prefix: String) = doc.select("a[id^=$prefix]")
                .mapNotNull { a ->
                    val href = a.attr("href")
                    val itemId = a.id().removePrefix(prefix).toLongOrNull()
                    // Logged out, the links have no auth token and only lead to the login page.
                    if (itemId == null || "auth=" !in href) null else itemId to href
                }
                .toMap()
            val fave = doc.select("a[href^=fave?id=$id&]").firstOrNull { "auth=" in it.attr("href") }
            return HnItemPage(
                upvoteUrls = links("up_"),
                unvoteUrls = links("un_"),
                faveUrl = fave?.attr("href"),
                favorited = fave?.attr("href")?.contains("un=t") == true,
                hmac = replyHmac(html),
                order = doc.select("tr.athing.comtr").mapNotNull { it.id().toLongOrNull() },
            )
        }

        fun replyHmac(html: String): String? =
            Ksoup.parse(html).select("form[action=comment] input[name=hmac]").firstOrNull()?.attr("value")?.ifBlank { null }

        /** Orders each level like the page; comments the page doesn't show (later pages) keep Algolia's order after them. */
        fun sortByPage(comments: List<Comment>, order: List<Long>): List<Comment> {
            val position = order.withIndex().associate { it.value to it.index }
            fun sort(list: List<Comment>): List<Comment> =
                list.sortedBy { position[it.id] ?: Int.MAX_VALUE }.map { it.copy(children = sort(it.children)) }
            return sort(comments)
        }
    }
}
