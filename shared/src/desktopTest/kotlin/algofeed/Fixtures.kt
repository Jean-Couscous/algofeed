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

    val hn = """{"hits":[
        {"objectID":"1","title":"Show HN: A thing","url":"https://thing.example/","author":"pg","points":120,"num_comments":5,"created_at_i":1791280800}
    ]}"""

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

    val redditGallery = """{"kind":"Listing","data":{"children":[
        {"kind":"t3","data":{"name":"t3_gal","title":"Gallery","permalink":"/r/x/comments/gal/g/","url":"https://www.reddit.com/gallery/gal","is_gallery":true,"is_self":false,"author":"amy","created_utc":1791281000.0,
          "gallery_data":{"items":[{"media_id":"m1","caption":"first"},{"media_id":"m2"}]},
          "media_metadata":{
            "m1":{"status":"valid","e":"Image","s":{"u":"https://i.redd.it/m1.jpg"},"p":[{"u":"https://preview.redd.it/m1-small.jpg"}]},
            "m2":{"status":"valid","e":"Image","s":{"u":"https://i.redd.it/m2.jpg"},"p":[]}}}}
    ]}}"""

    val redditVideo = """{"kind":"Listing","data":{"children":[
        {"kind":"t3","data":{"name":"t3_vid","title":"A clip","permalink":"/r/x/comments/vid/clip/","url":"https://v.redd.it/abc","is_self":false,"author":"amy","created_utc":1791281000.0,
          "thumbnail":"https://b.thumbs.redditmedia.com/t.jpg",
          "media":{"reddit_video":{"fallback_url":"https://v.redd.it/abc/DASH_720.mp4?source=fallback","hls_url":"https://v.redd.it/abc/HLSPlaylist.m3u8","dash_url":"https://v.redd.it/abc/DASHPlaylist.mpd"}}}}
    ]}}"""

    val bluesky = """{"feed":[
        {"post":{"uri":"at://did:plc:abc/app.bsky.feed.post/xyz","author":{"handle":"alice.bsky.social","displayName":"Alice"},
          "record":{"text":"hello bsky","createdAt":"2026-10-06T10:00:00Z"},
          "embed":{"${'$'}type":"app.bsky.embed.images#view","images":[{"thumb":"https://cdn.bsky.app/t.jpg","fullsize":"https://cdn.bsky.app/f.jpg","alt":"a cat"}]},
          "indexedAt":"2026-10-06T10:00:01Z"}}
    ]}"""

    val fourchan = """[{"page":1,"threads":[
        {"no":12345,"sub":"A thread","com":"first <b>post</b>","tim":1600000000000,"ext":".jpg","filename":"pic","time":1791281000,"name":"Anonymous"}
    ]}]"""

    val tumblr = """{"meta":{"status":200},"response":{"posts":[
        {"type":"photo","id":42,"id_string":"42","blog_name":"staff","post_url":"https://staff.tumblr.com/post/42","timestamp":1791281000,
          "summary":"a photo","caption":"<p>look</p>","photos":[{"caption":"","original_size":{"url":"https://64.media.tumblr.com/p.jpg","width":500,"height":500}}]}
    ]}}"""

    val mangadex = """{"result":"ok","data":[
        {"id":"chap-1","type":"chapter","attributes":{"volume":"2","chapter":"15","title":"The Duel","translatedLanguage":"en","publishAt":"2026-10-06T10:00:00+00:00"}}
    ]}"""

    val mangadexManga = """{"result":"ok","data":{"id":"manga-1","type":"manga","attributes":{"title":{"en":"Some Manga"}}}}"""

    val nexusmods = """[
        {"name":"Unofficial Patch","summary":"Bug fixes","picture_url":"https://staticdelivery.nexusmods.com/mods/1/p.jpg",
          "mod_id":266,"domain_name":"skyrimspecialedition","version":"4.3","created_timestamp":1600000000,
          "updated_timestamp":1791281000,"author":"Arthmoor","uploaded_by":"Arthmoor","status":"published","available":true},
        {"name":"SkyUI","summary":"Better menus","mod_id":12604,"domain_name":"skyrimspecialedition",
          "created_timestamp":1500000000,"updated_timestamp":1500000500,"uploaded_by":"SkyUI Team","available":true},
        {"name":"You must be logged in to view this content.","summary":"","mod_id":9999,"domain_name":"skyrimspecialedition",
          "created_timestamp":1600000050,"status":"under_moderation","available":false}
    ]"""

    /** A client that serves [routes] (URL prefix → body) and 404s everything else. */
    fun client(routes: Map<String, String>, requests: MutableList<String> = mutableListOf()) = HttpClient(MockEngine { request ->
        val url = request.url.toString()
        requests += url
        val body = routes.entries.firstOrNull { url.startsWith(it.key) }?.value
        if (body == null) respond("not found", HttpStatusCode.NotFound)
        else respond(body, HttpStatusCode.OK, headersOf("Content-Type", if (body.trimStart().startsWith("{")) "application/json" else "text/xml"))
    })
}
