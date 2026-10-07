package algofeed

import algofeed.data.Feed
import algofeed.data.Folder
import algofeed.opml.Opml
import algofeed.opml.OpmlFeed
import kotlin.test.Test
import kotlin.test.assertEquals

class OpmlTest {
    @Test fun parsesNestedOutlines() {
        val xml = """<?xml version="1.0"?>
            <opml version="1.0"><head><title>x</title></head><body>
              <outline text="Tech" title="Tech">
                <outline type="rss" text="Lobsters" xmlUrl="https://lobste.rs/rss" htmlUrl="https://lobste.rs"/>
                <outline text="HN" xmlUrl="https://hnrss.org/frontpage"/>
              </outline>
              <outline type="rss" title="Blog &amp; Co" xmlUrl="https://blog.example/feed.xml"/>
            </body></opml>"""
        assertEquals(
            listOf(
                OpmlFeed("https://lobste.rs/rss", "Lobsters", "Tech"),
                OpmlFeed("https://hnrss.org/frontpage", "HN", "Tech"),
                OpmlFeed("https://blog.example/feed.xml", "Blog & Co", null),
            ),
            Opml.parse(xml),
        )
    }

    @Test fun roundTrip() {
        val folders = listOf(Folder(1, "News & Stuff"))
        val feeds = listOf(
            Feed(1, folderId = 1, type = "rss", url = "https://a.example/rss?x=1&y=2", title = "A <b>", createdAt = 0),
            Feed(2, type = "rss", url = "https://b.example/atom", title = "B", createdAt = 0),
        )
        assertEquals(
            listOf(
                OpmlFeed("https://a.example/rss?x=1&y=2", "A <b>", "News & Stuff"),
                OpmlFeed("https://b.example/atom", "B", null),
            ),
            Opml.parse(Opml.export(folders, feeds)),
        )
    }
}
