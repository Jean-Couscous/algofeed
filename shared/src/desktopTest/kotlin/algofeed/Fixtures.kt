package algofeed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

object Fixtures {
    val rss = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
          <title>Example Blog</title><link>https://blog.example</link>
          <item><title>Rust borrow checker deep dive</title><link>https://blog.example/rust</link>
            <guid>rust-1</guid><pubDate>Tue, 06 Oct 2026 10:00:00 GMT</pubDate>
            <description>&lt;p&gt;How the &lt;b&gt;borrow checker&lt;/b&gt; works.&lt;/p&gt;&lt;img src="/img/a.png"&gt;</description></item>
          <item><title>Gardening in autumn</title><link>https://blog.example/garden</link>
            <guid>garden-1</guid><pubDate>Mon, 05 Oct 2026 10:00:00 GMT</pubDate>
            <description>Tomatoes and pumpkins.</description></item>
        </channel></rss>"""

    val atom = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"><title>Atom Site</title><link href="https://atom.example/"/>
          <entry><title>Hello Atom</title><link href="https://atom.example/hello"/><id>urn:1</id>
            <updated>2026-10-06T09:00:00Z</updated><author><name>Ann</name></author>
            <content type="html">&lt;p&gt;Full content here&lt;/p&gt;</content></entry>
        </feed>"""

    val html = """<html><head><title>Site</title>
        <link rel="alternate" type="application/rss+xml" href="/feed.xml"></head><body>hi</body></html>"""

    val hn = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>
        <title>Hacker News: Front Page</title><link>https://news.ycombinator.com/</link>
        <item><title>Show HN: A thing</title><link>https://thing.example/</link>
          <description>Article URL: https://thing.example/ Points: 120</description>
          <pubDate>Tue, 06 Oct 2026 11:00:00 +0000</pubDate>
          <comments>https://news.ycombinator.com/item?id=1</comments><guid>https://news.ycombinator.com/item?id=1</guid></item>
        </channel></rss>"""

    val mastodon = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>
        <title>Someone</title><link>https://social.example/@someone</link>
        <item><guid>https://social.example/@someone/1</guid><link>https://social.example/@someone/1</link>
          <pubDate>Tue, 06 Oct 2026 08:00:00 +0000</pubDate><description>&lt;p&gt;A toot about kotlin&lt;/p&gt;</description></item>
        </channel></rss>"""

    val youtube = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
          <yt:channelId>UCaaaaaaaaaaaaaaaaaaaaaa</yt:channelId><title>A Channel</title>
          <link rel="alternate" href="https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"/>
          <entry><id>yt:video:vid1</id><yt:videoId>vid1</yt:videoId><title>Video one</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=vid1"/>
            <published>2026-10-05T12:00:00+00:00</published>
            <media:group><media:title>Video one</media:title>
              <media:thumbnail url="https://i.ytimg.com/vi/vid1/hqdefault.jpg" width="480" height="360"/>
              <media:description>About the video</media:description></media:group></entry>
        </feed>"""

    val youtubePage = """<html><head></head><body><script>var x = {"externalId":"UCaaaaaaaaaaaaaaaaaaaaaa"};</script></body></html>"""

    val reddit = """{"kind":"Listing","data":{"children":[
        {"kind":"t3","data":{"name":"t3_sticky","stickied":true,"title":"Rules","permalink":"/r/x/comments/s/","url":"https://www.reddit.com/r/x/comments/s/","is_self":true,"created_utc":1791280000}},
        {"kind":"t3","data":{"name":"t3_abc","title":"Cool link","permalink":"/r/x/comments/abc/cool/","url":"https://cool.example/post","is_self":false,"author":"bob","created_utc":1791280000.0,"thumbnail":"https://b.thumbs.redditmedia.com/t.jpg"}},
        {"kind":"t3","data":{"name":"t3_def","title":"Question","permalink":"/r/x/comments/def/q/","url":"https://www.reddit.com/r/x/comments/def/q/","is_self":true,"selftext_html":"<p>Help?</p>","author":"amy","created_utc":1791281000,"thumbnail":"self"}}
    ]}}"""

    /** A client that serves [routes] (URL prefix → body) and 404s everything else. */
    fun client(routes: Map<String, String>, requests: MutableList<String> = mutableListOf()) = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        requests += url
        val body = routes.entries.firstOrNull { url.startsWith(it.key) }?.value
        if (body == null) respond("not found", HttpStatusCode.NotFound)
        else respond(body, HttpStatusCode.OK, headersOf("Content-Type", if (body.trimStart().startsWith("{")) "application/json" else "text/xml"))
    })
}
