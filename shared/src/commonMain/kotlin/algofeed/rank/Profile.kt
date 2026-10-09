package algofeed.rank

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
 * The interest vector: a dense embedding-space point. Each signal adds the entry's (unit-length)
 * embedding scaled by the signal weight, so the profile drifts toward what you engage with and away
 * from what you skip. Weights decay daily so it follows changing interests.
 */
object ProfileLearner {
    const val DAILY_DECAY = 0.98

    /** Adds [entryVector] (assumed L2-normalized) into [profile] in place, scaled by the signal. */
    fun apply(profile: FloatArray, entryVector: FloatArray, signal: Signal, scale: Double = 1.0) {
        if (entryVector.size != profile.size) return
        val w = (signal.weight * scale).toFloat()
        for (i in profile.indices) profile[i] += w * entryVector[i]
    }

    fun decay(profile: FloatArray, days: Double): FloatArray {
        val factor = DAILY_DECAY.pow(days).toFloat()
        return FloatArray(profile.size) { profile[it] * factor }
    }
}
