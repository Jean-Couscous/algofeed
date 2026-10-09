package algofeed

import algofeed.data.Entry
import algofeed.data.Feed
import algofeed.rank.Buckets
import algofeed.rank.Idf
import algofeed.rank.ProfileLearner
import algofeed.rank.RankInput
import algofeed.rank.Ranker
import algofeed.rank.Signal
import algofeed.rank.Tokenizer
import algofeed.util.DAY_MS
import algofeed.util.HOUR_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RankerTest {
    private val now = 1_791_288_000_000L

    private fun feed(id: Long, bucket: Int, impressions: Int = 0, opens: Int = 0, favorites: Int = 0, dismissals: Int = 0) =
        Feed(id = id, type = "rss", url = "https://f$id.example", title = "Feed $id", bucket = bucket, createdAt = 0,
            impressions = impressions, opens = opens, favorites = favorites, dismissals = dismissals)

    private fun entry(id: Long, feedId: Long, hoursAgo: Double, title: String = "Entry $id", url: String = "https://e.example/$id") =
        Entry(id = id, feedId = feedId, remoteId = "$id", url = url, title = title,
            sortDate = now - (hoursAgo * HOUR_MS).toLong(), fetchedAt = now)

    private fun input(
        entries: List<Entry>,
        feeds: List<Feed>,
        texts: Map<Long, String> = emptyMap(),
        profile: Map<String, Double> = emptyMap(),
    ): RankInput {
        val terms = texts.mapValues { Tokenizer.termFrequencies(it.value) }
        val df = terms.values.flatMap { it.keys }.groupingBy { it }.eachCount()
        return RankInput(entries, feeds.associateBy { it.id }, terms, df, terms.size, profile, now)
    }

    @Test fun bucketsMatchFeediThresholds() {
        assertEquals(0, Buckets.forPostsPerDay(1.0 / 30))
        assertEquals(1, Buckets.forPostsPerDay(1.0 / 7))
        assertEquals(2, Buckets.forPostsPerDay(1.0))
        assertEquals(3, Buckets.forPostsPerDay(5.0))
        assertEquals(4, Buckets.forPostsPerDay(20.0))
        assertEquals(5, Buckets.forPostsPerDay(20.1))
        assertEquals(3, Buckets.compute(60, now - 30 * DAY_MS, now))
        assertEquals(2, Buckets.compute(1, now - HOUR_MS, now)) // a single new post is at most daily
    }

    @Test fun weightsMixNewestFirstAndQuietFeedsFirst() {
        val feeds = listOf(feed(1, bucket = 5), feed(2, bucket = 0))
        val entries = listOf(entry(1, 1, hoursAgo = 1.0), entry(2, 2, hoursAgo = 30.0), entry(3, 1, hoursAgo = 2.0))
        val noMix = algofeed.rank.Weights(source = 0.0, content = 0.0, fatigue = 0.0, maxRun = 10)
        val newest = Ranker(noMix.copy(recency = 1.0, rarity = 0.0)).rank(input(entries, feeds)).map { it.entry.id }
        assertEquals(listOf(1L, 3L, 2L), newest)
        val quiet = Ranker(noMix.copy(recency = 0.1, rarity = 3.0)).rank(input(entries, feeds)).map { it.entry.id }
        assertEquals(listOf(2L, 1L, 3L), quiet)
    }

    @Test fun smartModeWithoutHistoryPrefersFreshAndRare() {
        val feeds = listOf(feed(1, bucket = 5), feed(2, bucket = 0))
        val entries = listOf(entry(1, 1, hoursAgo = 5.0), entry(2, 2, hoursAgo = 5.0), entry(3, 1, hoursAgo = 200.0))
        val order = Ranker().rank(input(entries, feeds)).map { it.entry.id }
        assertEquals(listOf(2L, 1L, 3L), order)
    }

    @Test fun sourceAffinityRewardsEngagement() {
        val neutral = Ranker.sourceAffinity(feed(1, 2))
        val loved = Ranker.sourceAffinity(feed(2, 2, impressions = 20, opens = 15, favorites = 3))
        val ignored = Ranker.sourceAffinity(feed(3, 2, impressions = 50))
        val disliked = Ranker.sourceAffinity(feed(4, 2, impressions = 20, dismissals = 10))
        assertEquals(0.0, neutral, 1e-9)
        assertTrue(loved > neutral && ignored < neutral && disliked < ignored, "$loved $ignored $disliked")
    }

    @Test fun authorAffinityRewardsEngagement() {
        fun stat(impressions: Int = 0, opens: Int = 0, favorites: Int = 0, dismissals: Int = 0) =
            algofeed.data.AuthorStat(1, "a", impressions, opens, favorites, dismissals)
        val neutral = Ranker.authorAffinity(stat())
        val loved = Ranker.authorAffinity(stat(impressions = 20, opens = 15, favorites = 3))
        val ignored = Ranker.authorAffinity(stat(impressions = 50))
        val disliked = Ranker.authorAffinity(stat(impressions = 20, dismissals = 10))
        assertEquals(0.0, neutral, 1e-9)
        assertTrue(loved > neutral && ignored < neutral && disliked < ignored, "$loved $ignored $disliked")
    }

    @Test fun profileBoostsMatchingContent() {
        val feeds = listOf(feed(1, 2))
        val texts = mapOf(
            1L to "Rust compiler borrow checker improvements",
            2L to "Celebrity gossip red carpet fashion",
            3L to "Gardening tips tomato season",
        )
        val entries = texts.keys.map { entry(it, 1, hoursAgo = 3.0, title = texts.getValue(it)) }
        val inp = input(entries, feeds, texts)
        val idf = Idf(inp.documentFrequency, inp.documentCount)
        val profile = HashMap<String, Double>()
        ProfileLearner.apply(profile, Tokenizer.termFrequencies("Rust borrow checker lifetimes"), idf, Signal.Favorite)
        ProfileLearner.apply(profile, Tokenizer.termFrequencies("celebrity gossip"), idf, Signal.Dismiss)

        val ranked = Ranker(algofeed.rank.Weights(maxRun = 10)).rank(input(entries, feeds, texts, profile))
        assertEquals(listOf(1L, 3L, 2L), ranked.map { it.entry.id })
        assertTrue(ranked.first().breakdown.topTerms.contains("rust"))
        assertTrue(ranked.last().breakdown.content < 0)
    }

    @Test fun diversityCapsRunsFromOneFeed() {
        val feeds = listOf(feed(1, 0), feed(2, 5))
        val entries = (1L..6L).map { entry(it, 1, hoursAgo = it.toDouble()) } + entry(10, 2, hoursAgo = 30.0) + entry(11, 2, hoursAgo = 31.0)
        val feedOrder = Ranker().rank(input(entries, feeds)).map { it.entry.feedId }
        var run = 0
        var prev = -1L
        for (f in feedOrder.take(6)) {
            run = if (f == prev) run + 1 else 1
            prev = f
            assertTrue(run <= 2, "run too long: $feedOrder")
        }
    }

    @Test fun quietFeedsAreLiftedAmongComparablyFreshEntries() {
        // Recency leads, but a quiet feed still gets a rarity lift, so a reasonably fresh post from a
        // rarely-posting feed surfaces among a flood from busy feeds instead of being buried.
        val busy = (1L..2L).map { feed(it, bucket = 5) }
        val quiet = feed(9, bucket = 1)
        val entries = (1L..40L).map { entry(it, feedId = 1 + it % 2, hoursAgo = it * 0.25) } + entry(100, 9, hoursAgo = 2.0)
        val order = Ranker().rank(input(entries, busy + quiet)).map { it.entry.id }
        assertTrue(order.indexOf(100L) < 8, "quiet entry at ${order.indexOf(100L)}")
    }

    @Test fun duplicatesArePushedDown() {
        val feeds = listOf(feed(1, 2), feed(2, 2), feed(3, 2))
        val entries = listOf(
            entry(1, 1, 1.0, title = "Kotlin 3.0 released with new compiler", url = "https://kotlinlang.org/blog/k3"),
            entry(2, 2, 1.1, title = "Kotlin 3.0 released with new compiler", url = "https://news.example/kotlin"),
            entry(3, 3, 1.2, title = "Something else entirely here", url = "https://x.example/y"),
        )
        val order = Ranker().rank(input(entries, feeds)).map { it.entry.id }
        assertEquals(listOf(1L, 3L, 2L), order)
    }

    @Test fun decayShrinksWeights() {
        val decayed = ProfileLearner.decay(mapOf("a" to 1.0), days = 7.0)
        assertEquals(0.868, decayed.getValue("a"), 1e-3)
    }

    @Test fun tokenizerDropsStopwordsAndStems() {
        assertEquals(listOf("rust", "compiler", "releas"), Tokenizer.tokenize("The Rust compilers are releasing!"))
        assertEquals(Tokenizer.stem("releases"), Tokenizer.stem("releasing"))
    }
}
