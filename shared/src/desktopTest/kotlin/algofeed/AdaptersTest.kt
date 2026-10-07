package algofeed

import algofeed.data.Feed
import algofeed.fetch.EntryDraft
import algofeed.fetch.FetchResult
import algofeed.fetch.defaultSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest

class AdaptersTest {
    private suspend fun fetchAll(routes: Map<String, String>, input: String): Pair<Feed, List<EntryDraft>> {
        val sources = defaultSources(Fixtures.client(routes))
        val info = sources.resolve(input)
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, siteUrl = info.siteUrl, createdAt = 0)
        val result = sources.forFeed(feed).fetch(feed)
        assertIs<FetchResult.Fetched>(result)
        return feed to result.entries
    }

    @Test fun rss() = runTest {
        val (feed, entries) = fetchAll(mapOf("https://blog.example/feed" to Fixtures.rss), "blog.example/feed")
        assertEquals("rss", feed.type)
        assertEquals("Example Blog", feed.title)
        assertEquals(2, entries.size)
        val first = entries.first()
        assertEquals("rust-1", first.remoteId)
        assertEquals("https://blog.example/img/a.png", first.thumbnailUrl)
        assertEquals(1_791_280_800_000L, first.sortDate)
    }

    @Test fun atom() = runTest {
        val (feed, entries) = fetchAll(mapOf("https://atom.example/atom" to Fixtures.atom), "https://atom.example/atom")
        assertEquals("Atom Site", feed.title)
        assertEquals("Hello Atom", entries.single().title)
        assertEquals("https://atom.example/hello", entries.single().url)
    }

    @Test fun discoversFeedFromHtml() = runTest {
        val routes = mapOf("https://site.example/feed.xml" to Fixtures.rss, "https://site.example" to Fixtures.html)
        val (feed, _) = fetchAll(routes, "https://site.example")
        assertEquals("https://site.example/feed.xml", feed.url)
    }

    @Test fun hackerNews() = runTest {
        val (feed, entries) = fetchAll(mapOf("https://hnrss.org/frontpage" to Fixtures.hn), "hn")
        assertEquals("hn", feed.type)
        assertEquals("Hacker News", feed.title)
        val e = entries.single()
        assertEquals("https://thing.example/", e.url)
        assertEquals("https://news.ycombinator.com/item?id=1", e.commentsUrl)
        assertNull(e.summaryHtml)
    }

    @Test fun mastodonHandle() = runTest {
        val (feed, entries) = fetchAll(mapOf("https://social.example/@someone.rss" to Fixtures.mastodon), "@someone@social.example")
        assertEquals("mastodon", feed.type)
        assertNull(entries.single().title)
        assertTrue(entries.single().summaryHtml!!.contains("kotlin"))
    }

    @Test fun youtubeHandle() = runTest {
        val routes = mapOf(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCaaaaaaaaaaaaaaaaaaaaaa" to Fixtures.youtube,
            "https://www.youtube.com/@someone" to Fixtures.youtubePage,
        )
        val (feed, entries) = fetchAll(routes, "https://www.youtube.com/@someone")
        assertEquals("youtube", feed.type)
        assertEquals("A Channel", feed.title)
        assertEquals("https://i.ytimg.com/vi/vid1/hqdefault.jpg", entries.single().thumbnailUrl)
    }

    @Test fun redditRssFallback() = runTest {
        val rss = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>r/x</title>
            <entry><id>t3_a</id><title>Cool link</title><link href="https://www.reddit.com/r/x/comments/a/cool/"/>
            <updated>2026-10-06T10:00:00Z</updated><content type="html">&lt;table&gt;&lt;tr&gt;&lt;td&gt; &amp;#32; submitted by &amp;#32; &lt;a href="https://www.reddit.com/user/bob"&gt; /u/bob &lt;/a&gt; &lt;br/&gt; &lt;span&gt;&lt;a href="https://cool.example/post"&gt;[link]&lt;/a&gt;&lt;/span&gt; &amp;#32; &lt;span&gt;&lt;a href="https://www.reddit.com/r/x/comments/a/cool/"&gt;[comments]&lt;/a&gt;&lt;/span&gt;&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;</content></entry></feed>"""
        // No JSON route: the adapter falls back to RSS.
        val (_, entries) = fetchAll(mapOf("https://www.reddit.com/r/x/.rss" to rss), "r/x")
        val e = entries.single()
        assertEquals("https://cool.example/post", e.url)
        assertEquals("https://www.reddit.com/r/x/comments/a/cool/", e.commentsUrl)
        assertNull(e.contentHtml)
        assertNull(e.summaryHtml)
    }

    @Test fun reddit() = runTest {
        val (feed, entries) = fetchAll(mapOf("https://www.reddit.com/r/x/.json" to Fixtures.reddit), "r/x")
        assertEquals("reddit", feed.type)
        assertEquals("https://www.reddit.com/r/x", feed.url)
        assertEquals(listOf("t3_abc", "t3_def"), entries.map { it.remoteId })
        val link = entries[0]
        assertEquals("https://cool.example/post", link.url)
        assertEquals("https://www.reddit.com/r/x/comments/abc/cool/", link.commentsUrl)
        assertEquals("u/bob", link.author)
        assertEquals("https://www.reddit.com/r/x/comments/def/q/", entries[1].url)
        assertNull(entries[1].thumbnailUrl)
    }

    @Test fun redditCustomFeed() = runTest {
        val rss = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>feed</title>
            <entry><id>t3_a</id><title>Post</title><link href="https://www.reddit.com/r/x/comments/a/post/"/>
            <updated>2026-10-06T10:00:00Z</updated></entry></feed>"""
        val routes = mapOf("https://www.reddit.com/user/bob/m/news/.rss" to rss)
        for (input in listOf("u/bob/m/news", "https://old.reddit.com/u/bob/m/news/", "reddit.com/user/bob/m/news/.json")) {
            val (feed, entries) = fetchAll(routes, input)
            assertEquals("https://www.reddit.com/user/bob/m/news", feed.url)
            assertEquals("news (u/bob)", feed.title)
            assertEquals("Post", entries.single().title)
        }
    }

    @Test fun kagiNews() = runTest {
        val rss = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Kagi News - Technology</title>
            <link>https://kite.kagi.com/tech.xml</link>
            <item><title>Chip news</title><link>https://kite.kagi.com/tech/1/chip-news</link>
            <guid isPermaLink="true">https://kite.kagi.com/tech/1/chip-news</guid>
            <description>&lt;p&gt;Summary.&lt;/p&gt;&lt;h3&gt;Highlights:&lt;/h3&gt;&lt;ul&gt;&lt;li&gt;One&lt;/li&gt;&lt;/ul&gt;</description>
            <pubDate>Wed, 07 Oct 2026 04:01:53 +0000</pubDate></item></channel></rss>"""
        val categories = """{"batchId":"b","categories":[{"categoryId":"tech","categoryName":"Technology"},
            {"categoryId":"usa_|_georgia","categoryName":"USA | Georgia"}]}"""
        val routes = mapOf(
            "https://kite.kagi.com/api/batches/latest/categories" to categories,
            "https://kite.kagi.com/tech.xml" to rss,
            "https://kite.kagi.com/usa_%7C_georgia.xml" to rss,
        )
        for (input in listOf("kagi:tech", "Kagi Technology", "https://news.kagi.com/tech", "kite.kagi.com/tech.xml")) {
            val (feed, entries) = fetchAll(routes, input)
            assertEquals("kagi", feed.type)
            assertEquals("https://kite.kagi.com/tech.xml", feed.url)
            assertEquals("Kagi News - Technology", feed.title)
            val e = entries.single()
            assertEquals("https://kite.kagi.com/tech/1/chip-news", e.url)
            assertTrue(e.contentHtml!!.contains("Highlights"))
        }
        assertEquals("https://kite.kagi.com/usa_%7C_georgia.xml", fetchAll(routes, "kagi: usa | georgia").first.url)
    }

    @Test fun redditThreadFromJson() = runTest {
        val json = """[{"kind":"Listing","data":{"children":[]}},{"kind":"Listing","data":{"children":[
            {"kind":"t1","data":{"id":"a1","author":"bob","body":"Top","body_html":"<div><p>Top</p></div>","created_utc":1791280800.0,
              "replies":{"kind":"Listing","data":{"children":[
                {"kind":"t1","data":{"id":"a2","author":"[deleted]","body":"[removed]","body_html":"<p>[removed]</p>","created_utc":1791280900.0,"replies":""}},
                {"kind":"more","data":{"count":3}}]}}}},
            {"kind":"t1","data":{"id":"a3","author":"eve","body":"Second","body_html":"<p>Second</p>","created_utc":1791281000.0,"replies":""}}]}}]"""
        val reddit = defaultSources(Fixtures.client(mapOf("https://www.reddit.com/r/x/comments/abc/post/.json" to json)))
            .adapters.filterIsInstance<algofeed.fetch.RedditAdapter>().single()
        val thread = reddit.thread("https://www.reddit.com/r/x/comments/abc/post/")
        assertEquals(listOf("bob", "eve"), thread.comments.map { it.author })
        val reply = thread.comments[0].children.single()
        assertNull(reply.author)
        assertNull(reply.html)
        assertEquals("a1".toLong(36), thread.comments[0].id)
        assertEquals(1_791_280_800_000L, thread.comments[0].time)
        assertEquals(false, thread.flat)
    }

    @Test fun redditThreadFallsBackToFlatRss() = runTest {
        val rss = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>post</title>
            <entry><author><name>/u/op</name></author><id>t3_abc</id><title>Post</title><link href="https://www.reddit.com/r/x/comments/abc/post/"/>
            <updated>2026-10-06T10:00:00Z</updated><content type="html">&lt;p&gt;post&lt;/p&gt;</content></entry>
            <entry><author><name>/u/bob</name></author><id>t1_a1</id><title>/u/bob on Post</title><link href="https://www.reddit.com/r/x/comments/abc/post/a1/"/>
            <updated>2026-10-06T11:00:00Z</updated><content type="html">&lt;div class="md"&gt;&lt;p&gt;Sauce&lt;/p&gt;&lt;/div&gt;</content></entry></feed>"""
        // No JSON route: Reddit refused it, as it does without an account.
        val reddit = defaultSources(Fixtures.client(mapOf("https://www.reddit.com/r/x/comments/abc/post/.rss" to rss)))
            .adapters.filterIsInstance<algofeed.fetch.RedditAdapter>().single()
        val thread = reddit.thread("https://old.reddit.com/r/x/comments/abc/post/")
        assertTrue(thread.flat)
        val c = thread.comments.single()
        assertEquals("bob", c.author)
        assertTrue(c.html!!.contains("Sauce"))
        assertEquals("a1".toLong(36), c.id)
    }

    @Test fun redditSkipsJsonForAWhileAfterA403() = runTest {
        val rss = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>r/x</title>
            <entry><id>t3_a</id><title>Post</title><link href="https://www.reddit.com/r/x/comments/a/post/"/>
            <updated>2026-10-06T10:00:00Z</updated></entry></feed>"""
        val requests = mutableListOf<String>()
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            val url = request.url.toString()
            requests += url
            if (".json" in url) respond("blocked", io.ktor.http.HttpStatusCode.Forbidden)
            else respond(rss, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "text/xml"))
        })
        val sources = defaultSources(client)
        val info = sources.resolve("r/x")
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, createdAt = 0)
        repeat(2) { assertIs<FetchResult.Fetched>(sources.forFeed(feed).fetch(feed)) }
        sources.adapters.filterIsInstance<algofeed.fetch.RedditAdapter>().single().thread("https://www.reddit.com/r/x/comments/a/post/")
        assertEquals(1, requests.count { ".json" in it }, requests.toString())
    }
}
