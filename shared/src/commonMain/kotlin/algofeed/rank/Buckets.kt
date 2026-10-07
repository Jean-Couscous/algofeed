package algofeed.rank

import algofeed.util.DAY_MS

/** feedi's feed frequency buckets (feedi/models.py, Feed.frequency_rank). */
object Buckets {
    fun forPostsPerDay(postsPerDay: Double): Int = when {
        postsPerDay <= 1.0 / 30 -> 0
        postsPerDay <= 1.0 / 7 -> 1
        postsPerDay <= 1.0 -> 2
        postsPerDay <= 5.0 -> 3
        postsPerDay <= 20.0 -> 4
        else -> 5
    }

    /** Rate over the retained window: entries / days since the oldest retained entry (at least one day). */
    fun compute(entryCount: Int, oldest: Long?, now: Long): Int {
        if (entryCount == 0 || oldest == null) return 2
        val days = ((now - oldest).toDouble() / DAY_MS).coerceAtLeast(1.0)
        return forPostsPerDay(entryCount / days)
    }
}
