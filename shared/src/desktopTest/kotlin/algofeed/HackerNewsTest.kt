package algofeed

import algofeed.fetch.HackerNews
import algofeed.fetch.HackerNewsException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HackerNewsTest {
    private class Call(val method: String, val url: String, val cookie: String?, val body: String)

    private val calls = mutableListOf<Call>()

    private fun hn(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HackerNews(HttpClient(MockEngine { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            calls += Call(request.method.value, request.url.toString(), request.headers[HttpHeaders.Cookie], body)
            handler(request)
        }))

    // Logged-in markup as HN serves it: vote and favorite links carry auth tokens, the reply form an hmac.
    private val itemPage = """<html><body><table>
        <tr class="athing" id="100"><td><a id='up_100' href='vote?id=100&amp;how=up&amp;auth=s1&amp;goto=item%3Fid%3D100'></a></td></tr>
        <tr><td><a href="fave?id=100&amp;un=t&amp;auth=f1">un-favorite</a></td></tr>
        <tr><td><form action="comment" method="post"><input type="hidden" name="parent" value="100">
          <input type="hidden" name="goto" value="item?id=100"><input type="hidden" name="hmac" value="h100">
          <textarea name="text"></textarea></form></td></tr>
        <tr class="athing comtr" id="102"><td><a id='up_102' class='nosee' href='vote?id=102&amp;how=up&amp;auth=c2&amp;goto=item%3Fid%3D100'></a>
          <a id='un_102' href='vote?id=102&amp;how=un&amp;auth=u2&amp;goto=item%3Fid%3D100'>unvote</a></td></tr>
        <tr class="athing comtr" id="101"><td><a id='up_101' href='vote?id=101&amp;how=up&amp;auth=c1&amp;goto=item%3Fid%3D100'></a></td></tr>
        </table></body></html>"""

    private val algolia = """{"id":100,"author":"pg","children":[
        {"id":101,"author":"a","text":"<p>first</p>","created_at_i":10,"children":[]},
        {"id":102,"author":"b","text":"second","created_at_i":20,"children":[
          {"id":103,"author":null,"text":null,"created_at_i":30,"children":[]},
          {"id":104,"author":"c","text":"reply","created_at_i":40,"children":[]}]}]}"""

    @Test fun loginKeepsTheUserCookie() = runTest {
        val client = hn {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.SetCookie, listOf("user=someone&abc123; Path=/; Secure", "other=1")))
        }
        assertEquals("someone&abc123", client.login("someone", "pw"))
        val call = calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://news.ycombinator.com/login", call.url)
        assertEquals("acct=someone&pw=pw&goto=news", call.body)
        assertEquals("someone", HackerNews.userOf("someone&abc123"))
    }

    @Test fun badLoginAndCaptchaFail() = runTest {
        val bad = assertFailsWith<HackerNewsException> { hn { respond("Bad login.") }.login("x", "y") }
        assertEquals("Wrong username or password", bad.message)
        val captcha = assertFailsWith<HackerNewsException> { hn { respond("Validation required. <script src=recaptcha>") }.login("x", "y") }
        assertTrue("captcha" in captcha.message!!)
    }

    @Test fun itemPageLinks() {
        val page = HackerNews.parseItemPage(100, itemPage)
        assertEquals("vote?id=100&how=up&auth=s1&goto=item%3Fid%3D100", page.upvoteUrls[100])
        assertTrue(page.voted(102))
        assertFalse(page.voted(101))
        assertTrue(page.favorited)
        assertEquals("fave?id=100&un=t&auth=f1", page.faveUrl)
        assertEquals("h100", page.hmac)
        assertEquals(listOf(102L, 101L), page.order)
    }

    @Test fun loggedOutLinksAreIgnored() {
        val page = HackerNews.parseItemPage(1, "<a id='up_1' href='vote?id=1&amp;how=up&amp;goto=news'></a>")
        assertTrue(page.upvoteUrls.isEmpty())
    }

    @Test fun threadFollowsPageOrderAndDropsEmptyDeleted() = runTest {
        val client = hn { request ->
            if ("algolia" in request.url.host) respond(algolia, headers = headersOf("Content-Type", "application/json"))
            else respond(itemPage)
        }
        val thread = client.thread(100, "someone&abc")
        assertEquals(listOf(102L, 101L), thread.comments.map { it.id })
        assertEquals(listOf(104L), thread.comments[0].children.map { it.id })
        assertEquals(20_000L, thread.comments[0].time)
        assertEquals("user=someone&abc", calls.first { "ycombinator" in it.url }.cookie)
    }

    @Test fun replyPostsTheFormToken() = runTest {
        val client = hn { request ->
            if ("/reply" in request.url.encodedPath) respond(itemPage.replace("h100", "h102"))
            else respond("", HttpStatusCode.Found)
        }
        client.reply(parentId = 102, storyId = 100, text = "Nice & short", session = "someone&abc")
        assertEquals("https://news.ycombinator.com/reply?id=102&goto=item%3Fid%3D100", calls[0].url)
        assertEquals("parent=102&goto=item%3Fid%3D100&hmac=h102&text=Nice+%26+short", calls[1].body)
        assertEquals("user=someone&abc", calls[1].cookie)
    }

    @Test fun replyErrorShowsHnMessage() = runTest {
        val client = hn { respond("You're posting too fast. Please slow down. Thanks.") }
        val e = assertFailsWith<HackerNewsException> { client.reply(100, 100, "hi", "s", hmac = "h") }
        assertEquals("You're posting too fast. Please slow down. Thanks.", e.message)
    }

    @Test fun itemIdFromDiscussionUrl() {
        assertEquals(42L, HackerNews.itemId("https://news.ycombinator.com/item?id=42"))
        assertEquals(null, HackerNews.itemId("https://lobste.rs/s/abc"))
    }
}
