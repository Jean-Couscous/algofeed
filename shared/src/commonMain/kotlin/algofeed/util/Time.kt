package algofeed.util

import kotlin.time.Clock

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS

fun relativeTime(then: Long, now: Long = nowMillis()): String {
    val minutes = (now - then) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 60 * 24 -> "${minutes / 60}h"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d"
        else -> "${minutes / (60 * 24 * 30)}mo"
    }
}
