package algofeed.fetch

import algofeed.util.nowMillis
import io.ktor.http.Headers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-host rate-limit state, persisted so a restart doesn't blow a near-exhausted budget. */
interface RateLimitStore {
    suspend fun load(host: String): String?
    suspend fun save(host: String, json: String)

    companion object {
        val None = object : RateLimitStore {
            override suspend fun load(host: String): String? = null
            override suspend fun save(host: String, json: String) {}
        }
    }
}

@Serializable
data class RateWindow(val limit: Int, val remaining: Int, val resetAt: Long)

@Serializable
data class HostQuota(val hourly: RateWindow? = null, val daily: RateWindow? = null)

/**
 * Tracks each host's remaining request budget from rate-limit response headers, so a refresh can skip
 * a host about to hit the wall instead of waiting for the 429. Reads Tumblr's X-Ratelimit-Per{hour,day}-*,
 * Nexus's X-RL-{Hourly,Daily}-* and the draft RateLimit-* headers.
 */
class RateLimiter(
    private val store: RateLimitStore = RateLimitStore.None,
    private val clock: () -> Long = ::nowMillis,
    /** Hold a host back when a live window has this many requests left or fewer, until it resets. */
    private val safetyMargin: Int = 2,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val cache = HashMap<String, HostQuota>()

    /** Normalizes a host so the feed URL and the response URL key the same entry. */
    fun key(host: String?): String? = host?.removePrefix("www.")?.lowercase()?.ifEmpty { null }

    /** Whether a request to [host] is within budget; counts the request when it is. Call before fetching. */
    suspend fun allow(host: String?): Boolean = mutex.withLock {
        val h = key(host) ?: return@withLock true
        val q = loadLocked(h) ?: return@withLock true
        val now = clock()
        val windows = listOfNotNull(q.hourly, q.daily).filter { now < it.resetAt }
        if (windows.any { it.remaining <= safetyMargin }) return@withLock false
        val counted = HostQuota(
            hourly = q.hourly?.let { if (now < it.resetAt) it.copy(remaining = it.remaining - 1) else it },
            daily = q.daily?.let { if (now < it.resetAt) it.copy(remaining = it.remaining - 1) else it },
        )
        put(h, counted)
        true
    }

    /** Record the rate-limit headers from a response, overwriting with the server's authoritative counts. */
    suspend fun record(host: String?, headers: Headers) {
        val h = key(host) ?: return
        val now = clock()
        val hourly = window(headers, "x-ratelimit-perhour", now, HOUR_MS)
            ?: window(headers, "x-rl-hourly", now, HOUR_MS)
            ?: window(headers, "ratelimit", now, HOUR_MS)
        val daily = window(headers, "x-ratelimit-perday", now, DAY_MS)
            ?: window(headers, "x-rl-daily", now, DAY_MS)
        if (hourly == null && daily == null) return
        mutex.withLock {
            val prev = loadLocked(h)
            put(h, HostQuota(hourly ?: prev?.hourly, daily ?: prev?.daily))
        }
    }

    private suspend fun loadLocked(host: String): HostQuota? =
        cache[host] ?: store.load(host)?.let { raw ->
            runCatching { json.decodeFromString(HostQuota.serializer(), raw) }.getOrNull()?.also { cache[host] = it }
        }

    private suspend fun put(host: String, quota: HostQuota) {
        cache[host] = quota
        store.save(host, json.encodeToString(HostQuota.serializer(), quota))
    }

    private fun window(headers: Headers, prefix: String, now: Long, fallbackMs: Long): RateWindow? {
        val remaining = headers["$prefix-remaining"]?.trim()?.toIntOrNull() ?: return null
        val limit = headers["$prefix-limit"]?.trim()?.toIntOrNull() ?: remaining
        // Tumblr and the draft send seconds-until-reset; Nexus sends a wall-clock string we can't cheaply
        // parse, so fall back to a conservative full window.
        val resetAt = headers["$prefix-reset"]?.trim()?.toLongOrNull()?.let { now + it * 1000 } ?: (now + fallbackMs)
        return RateWindow(limit, remaining, resetAt)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
