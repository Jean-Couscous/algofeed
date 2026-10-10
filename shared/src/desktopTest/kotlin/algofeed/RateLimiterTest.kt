package algofeed

import algofeed.fetch.RateLimitStore
import algofeed.fetch.RateLimiter
import io.ktor.http.Headers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : RateLimitStore {
        override suspend fun load(host: String) = map[host]
        override suspend fun save(host: String, json: String) { map[host] = json }
    }

    private fun headers(vararg pairs: Pair<String, String>) = Headers.build { pairs.forEach { append(it.first, it.second) } }

    @Test fun holdsHostNearHourlyLimit() = runTest {
        var now = 1_000_000L
        val limiter = RateLimiter(clock = { now })
        limiter.record("api.tumblr.com", headers(
            "X-Ratelimit-Perhour-Limit" to "1000",
            "X-Ratelimit-Perhour-Remaining" to "1",
            "X-Ratelimit-Perhour-Reset" to "3600",
        ))
        assertFalse(limiter.allow("api.tumblr.com"))
        // A different host is unaffected, and so is this one once its window has reset.
        assertTrue(limiter.allow("api.nexusmods.com"))
        now += 3_600_001L
        assertTrue(limiter.allow("api.tumblr.com"))
    }

    @Test fun allowsAndCountsDownWhenAboveMargin() = runTest {
        val now = 5L
        val limiter = RateLimiter(clock = { now }, safetyMargin = 2)
        limiter.record("api.nexusmods.com", headers(
            "X-RL-Hourly-Limit" to "100",
            "X-RL-Hourly-Remaining" to "4",
            "X-RL-Hourly-Reset" to "2026-01-01 00:00:00 +0000", // unparseable -> conservative full window
        ))
        assertTrue(limiter.allow("api.nexusmods.com"))  // 4 -> 3
        assertTrue(limiter.allow("api.nexusmods.com"))  // 3 -> 2
        assertFalse(limiter.allow("api.nexusmods.com")) // at margin
    }

    @Test fun persistsAcrossInstances() = runTest {
        val now = 10L
        val store = MapStore()
        RateLimiter(store, clock = { now }).record("api.tumblr.com", headers(
            "X-Ratelimit-Perday-Limit" to "5000",
            "X-Ratelimit-Perday-Remaining" to "0",
            "X-Ratelimit-Perday-Reset" to "86400",
        ))
        assertEquals(1, store.map.size)
        assertFalse(RateLimiter(store, clock = { now }).allow("api.tumblr.com"))
    }

    @Test fun ignoresResponsesWithoutRateLimitHeaders() = runTest {
        val limiter = RateLimiter(clock = { 0L })
        limiter.record("example.com", headers("Content-Type" to "text/html"))
        assertTrue(limiter.allow("example.com"))
    }
}
