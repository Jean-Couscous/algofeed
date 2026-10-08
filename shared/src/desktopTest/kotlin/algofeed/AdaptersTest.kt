package algofeed

import algofeed.data.Feed
import algofeed.data.MediaKind
import algofeed.fetch.EntryDraft
import algofeed.fetch.FetchResult
import algofeed.fetch.defaultSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val (feed, entries) = fetchAll(mapOf("https://hn.algolia.com/api/v1/search" to Fixtures.hn), "hn")
        assertEquals("hn", feed.type)
        assertEquals("Hacker News", feed.title)
        val e = entries.single()
        assertEquals("https://thing.example/", e.url)
        assertEquals("https://news.ycombinator.com/item?id=1", e.commentsUrl)
        assertEquals("pg", e.author)
        assertNull(e.summaryHtml)
    }

    @Test fun hackerNewsTextPostLinksToTheItem() = runTest {
        val body = """{"hits":[{"objectID":"9","title":"Ask HN: anything?","author":"a","created_at_i":1791281000}]}"""
        val (_, entries) = fetchAll(mapOf("https://hn.algolia.com/api/v1/search" to body), "hn")
        assertEquals("https://news.ycombinator.com/item?id=9", entries.single().url)
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

    @Test fun redditGallery() = runTest {
        val (_, entries) = fetchAll(mapOf("https://www.reddit.com/r/x/.json" to Fixtures.redditGallery), "r/x")
        val e = entries.single()
        assertEquals(2, e.media.size)
        assertEquals("https://i.redd.it/m1.jpg", e.media[0].url)
        assertEquals(MediaKind.IMAGE, e.media[0].kind)
        assertEquals("first", e.media[0].caption)
        assertEquals("https://preview.redd.it/m1-small.jpg", e.media[0].thumbnailUrl)
        // With no top-level thumbnail, the card falls back to the first gallery image's preview.
        assertEquals("https://preview.redd.it/m1-small.jpg", e.thumbnailUrl)
    }

    @Test fun redditVideo() = runTest {
        val (_, entries) = fetchAll(mapOf("https://www.reddit.com/r/x/.json" to Fixtures.redditVideo), "r/x")
        val e = entries.single()
        val m = e.media.single()
        assertEquals(MediaKind.VIDEO, m.kind)
        assertEquals("https://v.redd.it/abc/DASH_720.mp4?source=fallback", m.url)
        // HLS carries audio and is preferred over DASH for inline playback.
        assertEquals("https://v.redd.it/abc/HLSPlaylist.m3u8", m.streamUrl)
        assertEquals("https://b.thumbs.redditmedia.com/t.jpg", m.thumbnailUrl)
    }

    @Test fun bluesky() = runTest {
        val (feed, entries) = fetchAll(
            mapOf("https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" to Fixtures.bluesky),
            "@alice.bsky.social",
        )
        assertEquals("bluesky", feed.type)
        val e = entries.single()
        assertEquals("https://bsky.app/profile/alice.bsky.social/post/xyz", e.url)
        assertTrue(e.contentHtml!!.contains("hello bsky"))
        assertEquals("https://cdn.bsky.app/f.jpg", e.media.single().url)
        assertEquals("a cat", e.media.single().caption)
    }

    @Test fun fourChan() = runTest {
        val (feed, entries) = fetchAll(mapOf("https://a.4cdn.org/g/catalog.json" to Fixtures.fourchan), "4chan:g")
        assertEquals("4chan", feed.type)
        val e = entries.single()
        assertEquals("A thread", e.title)
        assertEquals("https://boards.4chan.org/g/thread/12345", e.url)
        assertEquals("https://i.4cdn.org/g/1600000000000.jpg", e.media.single().url)
        assertEquals(MediaKind.IMAGE, e.media.single().kind)
    }

    @Test fun fourChanConditional() = runTest {
        val lastModified = "Wed, 08 Oct 2026 10:00:00 GMT"
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            if (request.headers[io.ktor.http.HttpHeaders.IfModifiedSince] != null) {
                respond("", io.ktor.http.HttpStatusCode.NotModified)
            } else {
                respond(
                    Fixtures.fourchan,
                    io.ktor.http.HttpStatusCode.OK,
                    io.ktor.http.headersOf(io.ktor.http.HttpHeaders.LastModified, lastModified),
                )
            }
        })
        val sources = defaultSources(client)
        val info = sources.resolve("4chan:g")
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, siteUrl = info.siteUrl, createdAt = 0)
        val first = sources.forFeed(feed).fetch(feed)
        assertIs<FetchResult.Fetched>(first)
        assertEquals(lastModified, first.lastModified)
        // With the stored Last-Modified echoed back, the catalog returns 304 and isn't re-parsed.
        val stale = feed.copy(lastModified = first.lastModified)
        assertEquals(FetchResult.NotModified, sources.forFeed(stale).fetch(stale))
    }

    @Test fun fourChanThread() = runTest {
        val threadJson = """{"posts":[
            {"no":100,"time":1791281000,"name":"Anonymous","sub":"OP","com":"the op"},
            {"no":101,"time":1791281100,"name":"Anonymous","com":"a <b>reply</b>"},
            {"no":102,"time":1791281200,"name":"Namefag","com":"another"},
            {"no":103,"time":1791281300,"name":"Anonymous","tim":1600000000001,"ext":".jpg","filename":"cat"},
            {"no":104,"time":1791281400,"name":"Anonymous","tim":1600000000002,"ext":".webm","filename":"clip"}
        ]}"""
        val adapter = algofeed.fetch.FourChanAdapter(Fixtures.client(mapOf("https://a.4cdn.org/g/thread/100.json" to threadJson)))
        val thread = adapter.thread("https://boards.4chan.org/g/thread/100")
        // The OP is dropped (the reader already shows it); replies stay in order.
        assertEquals(4, thread.comments.size)
        assertEquals(101L, thread.comments.first().id)
        assertEquals("a <b>reply</b>", thread.comments.first().html)
        assertEquals("Namefag", thread.comments[1].author)
        // An image reply carries the picture; a video reply carries the clip, both with a thumbnail.
        val image = thread.comments[2]
        assertEquals("https://i.4cdn.org/g/1600000000001.jpg", image.imageUrl)
        assertEquals("https://i.4cdn.org/g/1600000000001s.jpg", image.thumbnailUrl)
        assertNull(image.videoUrl)
        val video = thread.comments[3]
        assertEquals("https://i.4cdn.org/g/1600000000002.webm", video.videoUrl)
        assertNull(video.imageUrl)
    }

    @Test fun mangadex() = runTest {
        val routes = mapOf(
            "https://api.mangadex.org/manga/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/feed" to Fixtures.mangadex,
            "https://api.mangadex.org/manga/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" to Fixtures.mangadexManga,
        )
        val (feed, entries) = fetchAll(routes, "https://mangadex.org/title/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/some-manga")
        assertEquals("mangadex", feed.type)
        assertEquals("Some Manga", feed.title)
        val e = entries.single()
        assertEquals("Vol. 2 Ch. 15 — The Duel", e.title)
        assertEquals("https://mangadex.org/chapter/chap-1", e.url)
    }

    @Test fun mangadexFollowsNeedsLogin() = runTest {
        val sources = defaultSources(Fixtures.client(emptyMap()))
        val info = sources.resolve("mangadex:follows")
        assertEquals("mangadex-follows", info.type)
        assertEquals("https://api.mangadex.org/user/follows/manga/feed?limit=30&order[publishAt]=desc", info.url)
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, createdAt = 0)
        // Not logged in: fails cleanly rather than fetching.
        assertFailsWith<algofeed.fetch.FetchException> { sources.forFeed(feed).fetch(feed) }
    }

    @Test fun mangadexFollowsWithLogin() = runTest {
        val routes = mapOf(
            "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token" to
                """{"access_token":"tok","refresh_token":"r2","expires_in":900}""",
            "https://api.mangadex.org/user/follows/manga/feed" to Fixtures.mangadex,
        )
        val secrets = mapOf(
            SecretStore.MANGADEX_CLIENT_ID to "id",
            SecretStore.MANGADEX_CLIENT_SECRET to "secret",
            SecretStore.MANGADEX_REFRESH_TOKEN to "r1",
        )
        val sources = defaultSources(Fixtures.client(routes)) { secrets[it] }
        val info = sources.resolve("mangadex:follows")
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, createdAt = 0)
        val result = sources.forFeed(feed).fetch(feed)
        assertIs<FetchResult.Fetched>(result)
        assertEquals("Vol. 2 Ch. 15 — The Duel", result.entries.single().title)
    }

    @Test fun tumblrNeedsAKey() = runTest {
        val sources = defaultSources(Fixtures.client(mapOf("https://api.tumblr.com/v2/blog/staff.tumblr.com/posts" to Fixtures.tumblr)))
        val info = sources.resolve("staff.tumblr.com")
        assertEquals("tumblr", info.type)
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, createdAt = 0)
        // No key configured: it fails cleanly rather than fetching.
        assertFailsWith<algofeed.fetch.FetchException> { sources.forFeed(feed).fetch(feed) }
    }

    @Test fun tumblrWithKey() = runTest {
        val sources = defaultSources(
            Fixtures.client(mapOf("https://api.tumblr.com/v2/blog/staff.tumblr.com/posts" to Fixtures.tumblr)),
        ) { if (it == SecretStore.TUMBLR_API_KEY) "key123" else null }
        val info = sources.resolve("staff.tumblr.com")
        val feed = Feed(id = 1, type = info.type, url = info.url, title = info.title, createdAt = 0)
        val result = sources.forFeed(feed).fetch(feed)
        assertIs<FetchResult.Fetched>(result)
        val e = result.entries.single()
        assertEquals("https://staff.tumblr.com/post/42", e.url)
        assertEquals("https://64.media.tumblr.com/p.jpg", e.media.single().url)
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
        // The first fetch hits .json (403) then falls back to .rss; the second skips .json entirely.
        repeat(2) { assertIs<FetchResult.Fetched>(sources.forFeed(feed).fetch(feed)) }
        assertEquals(1, requests.count { ".json" in it }, requests.toString())
    }
}
