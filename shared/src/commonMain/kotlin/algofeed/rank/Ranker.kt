package algofeed.rank

import algofeed.data.AuthorStat
import algofeed.data.Entry
import algofeed.data.Feed
import algofeed.util.HOUR_MS
import algofeed.util.Urls
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
data class Weights(
    // Recency dominates so a cold start (empty profile, all affinities ~0) still leads with the
    // freshest posts; source/author/content lift entries as the profile learns.
    val recency: Double = 2.0,
    val rarity: Double = 0.3,
    val source: Double = 0.8,
    val author: Double = 0.6,
    val content: Double = 1.5,
    /** Hours for the recency decay constant. */
    val halfLifeHours: Double = 36.0,
    /** Penalty per entry already placed above from the same feed, so busy feeds spread out. */
    val fatigue: Double = 0.06,
    /** Maximum consecutive entries from one feed. */
    val maxRun: Int = 2,
)

data class Breakdown(
    val recency: Double = 0.0,
    val rarity: Double = 0.0,
    val source: Double = 0.0,
    val author: Double = 0.0,
    val content: Double = 0.0,
    val duplicate: Double = 0.0,
    val fatigue: Double = 0.0,
    val topTerms: List<String> = emptyList(),
) {
    val total get() = recency + rarity + source + author + content + duplicate + fatigue
}

data class Ranked(val entry: Entry, val score: Double, val breakdown: Breakdown)

/** Everything the ranker needs, gathered by the repository. */
class RankInput(
    val candidates: List<Entry>,
    val feeds: Map<Long, Feed>,
    /** entryId → term → tf */
    val terms: Map<Long, Map<String, Float>>,
    val documentFrequency: Map<String, Int>,
    val documentCount: Int,
    /** TF-IDF interest vector: stemmed term → weight. */
    val profile: Map<String, Double>,
    val now: Long,
    /** (feedId, author) → engagement counters. */
    val authorStats: Map<Pair<Long, String>, AuthorStat> = emptyMap(),
)

class Ranker(private val weights: Weights = Weights()) {

    /**
     * The single Home ordering: freshness (newest first), feedi's feed rarity (quiet feeds first),
     * engagement with the feed and topic match, mixed by [Weights]; then duplicate and same-feed
     * penalties.
     */
    fun rank(input: RankInput): List<Ranked> {
        val idf = Idf(input.documentFrequency, input.documentCount)
        val profileNorm = sqrt(input.profile.values.sumOf { it * it })
        val scored = input.candidates.map { entry ->
            val feed = input.feeds[entry.feedId]
            val ageHours = ((input.now - entry.sortDate).coerceAtLeast(0L)).toDouble() / HOUR_MS
            val recency = exp(-ageHours / weights.halfLifeHours) + if (ageHours <= 72) 0.25 else 0.0
            val rarity = (5 - (feed?.bucket ?: 2)) / 5.0
            val source = feed?.let { sourceAffinity(it) } ?: 0.0
            val author = entry.author?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { input.authorStats[entry.feedId to it] }?.let { authorAffinity(it) } ?: 0.0
            val (affinity, stems) = contentAffinity(input.terms[entry.id].orEmpty(), idf, input.profile, profileNorm)
            val b = Breakdown(
                recency = weights.recency * recency,
                rarity = weights.rarity * rarity,
                source = weights.source * source,
                author = weights.author * author,
                content = weights.content * affinity,
                topTerms = stems,
            )
            Ranked(entry, b.total, b)
        }.sortedByDescending { it.score }
        return diversify(penalizeDuplicates(scored, input.terms))
    }

    /** Duplicates (same URL, or near-identical titles) of a higher-ranked entry get pushed down. */
    private fun penalizeDuplicates(sorted: List<Ranked>, terms: Map<Long, Map<String, Float>>): List<Ranked> {
        val seenUrls = HashSet<String>()
        val kept = ArrayList<Set<String>>()
        val out = sorted.mapIndexed { index, r ->
            val url = Urls.normalize(r.entry.url)
            var dup = url != null && !seenUrls.add(url)
            if (!dup && index < DUPLICATE_WINDOW) {
                val words = titleWords(r.entry, terms)
                if (words.size >= 3) {
                    dup = kept.any { jaccard(it, words) >= 0.6 }
                    kept += words
                }
            }
            if (dup) r.copy(score = r.score - 1.0, breakdown = r.breakdown.copy(duplicate = -1.0)) else r
        }
        return out.sortedByDescending { it.score }
    }

    /**
     * Greedy re-rank. Each feed's next entry competes with its score minus a fatigue penalty for
     * every entry of that feed already placed, and no feed gets more than [Weights.maxRun] in a row.
     */
    private fun diversify(sorted: List<Ranked>): List<Ranked> {
        val queues = sorted.groupBy { it.entry.feedId }.mapValues { ArrayDeque(it.value) }
        val placed = HashMap<Long, Int>()
        val out = ArrayList<Ranked>(sorted.size)
        var runFeed: Long? = null
        var runLength = 0
        while (out.size < sorted.size) {
            var best: Long? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for ((feedId, queue) in queues) {
                val head = queue.firstOrNull() ?: continue
                if (feedId == runFeed && runLength >= weights.maxRun) continue
                val adjusted = head.score - weights.fatigue * (placed[feedId] ?: 0)
                if (adjusted > bestScore) {
                    bestScore = adjusted
                    best = feedId
                }
            }
            // Only one feed left: the run cap can't be honoured.
            val feedId = best ?: queues.entries.first { it.value.isNotEmpty() }.key
            val next = queues.getValue(feedId).removeFirst()
            val penalty = -weights.fatigue * (placed[feedId] ?: 0)
            out += if (penalty == 0.0) next else next.copy(score = next.score + penalty, breakdown = next.breakdown.copy(fatigue = penalty))
            placed[feedId] = (placed[feedId] ?: 0) + 1
            runLength = if (feedId == runFeed) runLength + 1 else 1
            runFeed = feedId
        }
        return out
    }

    companion object {
        private const val DUPLICATE_WINDOW = 300
        const val PRIOR_ALPHA = 1.0
        const val PRIOR_BETA = 3.0
        val PRIOR = PRIOR_ALPHA / (PRIOR_ALPHA + PRIOR_BETA)

        /**
         * Beta-smoothed engagement rate minus the prior, so a fresh source scores 0, one whose entries
         * are always opened tends to +0.75 and one that only gets dismissed tends to −0.25 − dismiss rate.
         */
        fun betaAffinity(impressions: Int, opens: Int, favorites: Int, dismissals: Int): Double {
            val denominator = impressions + PRIOR_ALPHA + PRIOR_BETA
            val engaged = (opens + 2.0 * favorites + PRIOR_ALPHA) / denominator
            val dismissed = dismissals / denominator
            return (engaged - dismissed - PRIOR).coerceIn(-1.0, 1.0)
        }

        fun sourceAffinity(feed: Feed): Double =
            betaAffinity(feed.impressions, feed.opens, feed.favorites, feed.dismissals)

        fun authorAffinity(stat: AuthorStat): Double =
            betaAffinity(stat.impressions, stat.opens, stat.favorites, stat.dismissals)

        /** Cosine similarity between the entry's TF-IDF vector and the profile vector. */
        fun contentAffinity(
            terms: Map<String, Float>,
            idf: Idf,
            profile: Map<String, Double>,
            profileNorm: Double,
        ): Pair<Double, List<String>> {
            if (terms.isEmpty() || profileNorm == 0.0) return 0.0 to emptyList()
            var dot = 0.0
            var norm = 0.0
            val contributions = ArrayList<Pair<String, Double>>()
            for ((term, tf) in terms) {
                val w = tf * idf[term]
                norm += w * w
                val p = profile[term] ?: continue
                dot += w * p
                contributions += term to w * p
            }
            if (norm == 0.0) return 0.0 to emptyList()
            val top = contributions.filter { abs(it.second) > 0 }.sortedByDescending { abs(it.second) }.take(3).map { it.first }
            return dot / (sqrt(norm) * profileNorm) to top
        }

        private fun titleWords(entry: Entry, terms: Map<Long, Map<String, Float>>): Set<String> =
            entry.title?.let { Tokenizer.tokenize(it).toSet() } ?: terms[entry.id]?.keys.orEmpty()

        private fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val inter = a.count { it in b }
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }
}

class Idf(private val df: Map<String, Int>, private val documentCount: Int) {
    operator fun get(term: String): Double = ln((documentCount + 1.0) / ((df[term] ?: 0) + 1.0)) + 1.0
}
