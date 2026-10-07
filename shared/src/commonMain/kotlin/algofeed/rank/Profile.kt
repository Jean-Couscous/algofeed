package algofeed.rank

import kotlin.math.abs
import kotlin.math.pow

/** User actions that teach the content model. */
enum class Signal(val weight: Double) {
    Open(1.0),
    LongRead(1.0),
    Favorite(3.0),
    Bookmark(2.0),
    Dismiss(-1.0),
    SkippedPast(-0.1),
}

/**
 * The interest vector: term → weight. Each signal adds the entry's TF-IDF vector scaled by the signal
 * weight; weights decay daily so the profile follows changing interests.
 */
object ProfileLearner {
    const val DAILY_DECAY = 0.98
    const val MAX_TERMS = 3000
    private const val MIN_WEIGHT = 0.01

    fun apply(
        profile: MutableMap<String, Double>,
        entryTerms: Map<String, Float>,
        idf: Idf,
        signal: Signal,
    ): Map<String, Double> {
        val changed = HashMap<String, Double>()
        if (entryTerms.isEmpty()) return changed
        // Normalise so one long article doesn't outweigh many short ones.
        val vector = entryTerms.mapValues { (term, tf) -> tf * idf[term] }
        val norm = kotlin.math.sqrt(vector.values.sumOf { it * it })
        if (norm == 0.0) return changed
        for ((term, w) in vector) {
            val updated = (profile[term] ?: 0.0) + signal.weight * w / norm
            profile[term] = updated
            changed[term] = updated
        }
        return changed
    }

    fun decay(profile: Map<String, Double>, days: Double): Map<String, Double> {
        val factor = DAILY_DECAY.pow(days)
        return profile.mapValues { it.value * factor }
    }

    /** Drops near-zero terms and caps the vector size, keeping the strongest weights. */
    fun trim(profile: Map<String, Double>): Map<String, Double> =
        profile.filterValues { abs(it) >= MIN_WEIGHT }
            .entries.sortedByDescending { abs(it.value) }
            .take(MAX_TERMS)
            .associate { it.key to it.value }
}
