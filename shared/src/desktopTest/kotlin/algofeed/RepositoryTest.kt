package algofeed

import algofeed.data.AppDatabase
import algofeed.data.buildAlgofeed
import algofeed.fetch.defaultSources
import algofeed.reader.Article
import algofeed.reader.ReaderExtractor
import algofeed.util.HOUR_MS
import androidx.room.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest

class RepositoryTest {
    private val now = 1_791_288_000_000L
    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildAlgofeed()
    private val requests = mutableListOf<String>()
    private val client = Fixtures.client(
        mapOf(
            "https://blog.example/feed" to Fixtures.rss,
            "https://hn.algolia.com/api/v1/search" to Fixtures.hn,
            "https://social.example/@someone.rss" to Fixtures.mastodon,
        ),
        requests,
    )
    private val extractor = object : ReaderExtractor {
        override suspend fun extract(url: String) = Article("t", null, "<p>extracted</p>")
    }
    private val repo = Repository(db, defaultSources(client), extractor, clock = { now }, embedder = FakeEmbedder())

    @AfterTest fun close() = db.close()

    /** Cosine of a stored entry embedding against the learned profile. */
    private suspend fun affinity(entryId: Long): Double {
        val profile = repo.profile() ?: return 0.0
        val vec = algofeed.rank.Vectors.fromBytes(db.embeddings().forEntries(listOf(entryId)).first().vector)
        val norm = kotlin.math.sqrt(profile.sumOf { it.toDouble() * it })
        return algofeed.rank.Ranker.contentAffinity(vec, profile, norm)
    }

    @Test fun subscribeRefreshAndRank() = runTest {
        repo.addFeed("https://blog.example/feed")
        repo.addFeed("hn")
        repo.addFeed("@someone@social.example")
        assertEquals(0, repo.refreshAll().newEntries) // dedupe on (feed, remoteId)

        val stream = repo.stream(StreamView.Home, Settings())
        assertEquals(4, stream.size)

        // Engagement with Rust content lifts the matching entry above fresher ones.
        val rust = stream.first { it.entry.title?.contains("Rust") == true }.entry
        repo.markOpened(rust)
        repo.setLike(rust, true)
        assertTrue(affinity(rust.id) > 0)

        // Opened entries leave the home stream, and so do entries scrolled past (feedi behaviour).
        assertTrue(repo.stream(StreamView.Home, Settings()).none { it.entry.id == rust.id })
        val skipped = repo.stream(StreamView.Home, Settings()).first().entry
        repo.markViewed(listOf(skipped))
        assertTrue(repo.stream(StreamView.Home, Settings()).none { it.entry.id == skipped.id })
        assertEquals(1, db.feeds().get(rust.feedId)!!.impressions)
        assertTrue(db.entries().get(rust.id)!!.favoritedAt != null) // the like is recorded
    }

    @Test fun coldStartLeadsWithNewest() = runTest {
        // With an empty profile the ranked feed should still order a single feed newest-first:
        // recency dominates and the other factors are flat.
        val feedId = db.feeds().insert(
            algofeed.data.Feed(type = "rss", url = "https://blog.example/feed", title = "Blog", bucket = 2, createdAt = now)
        )
        db.entries().insertIgnore(
            (0 until 5).map { i ->
                algofeed.data.Entry(
                    feedId = feedId, remoteId = "e$i", url = "https://blog.example/e$i", title = "Post number $i",
                    sortDate = now - i * 6 * HOUR_MS, fetchedAt = now,
                )
            }
        )
        val order = repo.stream(StreamView.Home, Settings()).map { it.entry.sortDate }
        assertEquals(order.sortedDescending(), order)
    }

    @Test fun refreshReportsProgressPerFeed() = runTest {
        db.feeds().insert(algofeed.data.Feed(type = "rss", url = "https://blog.example/feed", title = "Blog", createdAt = 0))
        db.feeds().insert(algofeed.data.Feed(type = "hn", url = "https://hnrss.org/frontpage", title = "HN", createdAt = 0))
        val steps = mutableListOf<Pair<Int, Int>>()
        repo.refreshAll { p -> steps += p.done to p.total }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), steps)
        // Nothing due: no feed steps at all, so no bar.
        steps.clear()
        repo.refreshAll { p -> steps += p.done to p.total }
        assertTrue(steps.isEmpty())
    }

    @Test fun dismissLowersFeedAndTopic() = runTest {
        val feed = repo.addFeed("https://blog.example/feed")
        val garden = repo.stream(StreamView.Home, Settings()).first { it.entry.title?.contains("Garden") == true }.entry
        repo.setDismissed(garden, true)
        assertTrue(affinity(garden.id) < 0)
        assertTrue(repo.stream(StreamView.Home, Settings()).none { it.entry.id == garden.id })
        assertEquals(1, db.feeds().get(feed.id)!!.dismissals)
    }

    @Test fun authorAffinityLiftsEngagedAuthorsWithinAFeed() = runTest {
        val ranked = Settings()
        val feedId = db.feeds().insert(
            algofeed.data.Feed(type = "rss", url = "https://multi.example/feed", title = "Multi", bucket = 2, createdAt = now)
        )
        fun entry(remote: String, author: String) = algofeed.data.Entry(
            feedId = feedId, remoteId = remote, url = "https://multi.example/$remote", title = "Post $remote",
            author = author, sortDate = now - HOUR_MS, fetchedAt = now,
        )
        db.entries().insertIgnore(
            listOf("a1", "a2", "a3").map { entry(it, "alice") } + listOf("b1", "b2", "b3").map { entry(it, "bob") }
        )
        val byRemote = db.entries().byFeed(feedId).associateBy { it.remoteId }

        // Engage with alice, reject bob.
        for (id in listOf("a1", "a2")) {
            repo.markOpened(byRemote.getValue(id))
            repo.setLike(byRemote.getValue(id), true)
        }
        for (id in listOf("b1", "b2")) repo.setDismissed(byRemote.getValue(id), true)

        // The untouched entries a3 / b3 are the remaining candidates; only the author differs.
        val home = repo.stream(StreamView.Home, ranked)
        val a3 = home.first { it.entry.remoteId == "a3" }
        val b3 = home.first { it.entry.remoteId == "b3" }
        assertTrue(a3.breakdown.author > 0, "alice author factor ${a3.breakdown.author}")
        assertTrue(b3.breakdown.author < 0, "bob author factor ${b3.breakdown.author}")
        assertTrue(a3.score > b3.score, "a3 ${a3.score} should outrank b3 ${b3.score}")
        assertTrue(home.indexOfFirst { it.entry.remoteId == "a3" } < home.indexOfFirst { it.entry.remoteId == "b3" })
    }

    @Test fun oldSettingsWithModeStillLoad() = runTest {
        db.settings().put(algofeed.data.Setting("settings", """{"mode":"Classic","refreshMinutes":45}"""))
        assertEquals(45, repo.settings().refreshMinutes)
    }

    @Test fun bookmarksListNewestFirstAndStayInHome() = runTest {
        var clock = now
        val repo = Repository(db, defaultSources(client), extractor, clock = { clock })
        repo.addFeed("https://blog.example/feed")
        repo.addFeed("hn")
        val home = repo.stream(StreamView.Home, Settings())
        val (first, second) = home.take(2).map { it.entry }
        repo.setBookmarked(second, true)
        clock += 1000
        repo.setBookmarked(first, true)

        assertEquals(listOf(first.id, second.id), repo.stream(StreamView.Bookmarks, Settings()).map { it.entry.id })
        // Unlike the old pins, bookmarks don't jump to the top of Home.
        assertEquals(home.map { it.entry.id }, repo.stream(StreamView.Home, Settings()).map { it.entry.id })

        repo.setBookmarked(first, false)
        assertEquals(listOf(second.id), repo.stream(StreamView.Bookmarks, Settings()).map { it.entry.id })
    }

    @Test fun platformDefaultsApplyUntilSaved() = runTest {
        val android = Repository(db, defaultSources(client), extractor, clock = { now }, defaults = Settings(refreshMinutes = 60))
        assertEquals(60, android.settings().refreshMinutes)
        android.saveSettings(Settings(refreshMinutes = 20))
        assertEquals(20, android.settings().refreshMinutes)
    }

    @Test fun aiSettingsAndKeyAreDropped() = runTest {
        db.settings().put(algofeed.data.Setting("settings", """{"refreshMinutes":45,"ai":{"enabled":true,"apiKey":"sk-old"}}"""))
        assertEquals(45, repo.settings().refreshMinutes)
        val raw = db.settings().get("settings")!!
        assertTrue("sk-old" !in raw && "\"ai\"" !in raw, raw)
    }

    @Test fun readerCachesExtraction() = runTest {
        repo.addFeed("https://blog.example/feed")
        val entry = repo.stream(StreamView.Home, Settings()).first().entry
        repo.readable(entry)
        assertEquals("<p>extracted</p>", repo.entry(entry.id)!!.extractedHtml)
    }

    @Test fun opmlImportExport() = runTest {
        val opml = """<opml version="2.0"><body><outline text="Tech">
            <outline text="HN" xmlUrl="https://hnrss.org/frontpage"/></outline>
            <outline text="Blog" xmlUrl="https://blog.example/feed"/></body></opml>"""
        val (added, failed) = repo.importOpml(opml)
        assertEquals(2, added)
        assertTrue(failed.isEmpty())
        val exported = repo.exportOpml()
        assertTrue("""<outline text="Tech" title="Tech">""" in exported)
        assertTrue("https://blog.example/feed" in exported)
        assertEquals("hn", db.feeds().byUrl("https://hnrss.org/frontpage")!!.type)
    }

    @Test fun hnSessionWithoutSecretStore() = runTest {
        val hnClient = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
            respond("", io.ktor.http.HttpStatusCode.Found, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.SetCookie, "user=someone&abc; Path=/"))
        })
        val withHn = Repository(db, defaultSources(client), extractor, clock = { now }, hackerNews = algofeed.fetch.HackerNews(hnClient))
        assertEquals(null, withHn.hnUser())
        assertEquals("someone", withHn.hnLogin(" someone ", "pw"))
        assertEquals("someone", withHn.hnUser())
        // Kept apart from the settings JSON, which the Settings dialog writes back as a whole.
        withHn.saveSettings(withHn.settings())
        assertEquals("someone", withHn.hnUser())
        withHn.hnLogout()
        assertEquals(null, withHn.hnUser())
    }
}
