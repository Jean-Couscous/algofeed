package algofeed.fetch

import algofeed.SecretReader
import algofeed.SecretStore
import algofeed.data.Feed
import algofeed.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * New, updated or trending mods for a Nexus Mods game via the official API, which needs
 * the user's personal API key (from nexusmods.com/users/myaccount?tab=api), read from [secrets]. The
 * RSS feeds sit behind an interactive Cloudflare challenge; the API does not, and a free account's
 * key is enough to read mod listings (only generating download links needs Premium).
 *
 * Accepts `nexus:skyrimspecialedition` (optionally `/updated` or `/trending`) and any
 * `nexusmods.com/<game>/…` URL, including the old `/rss/…` feed URLs.
 */
class NexusmodsAdapter(private val client: HttpClient, private val secrets: SecretReader?) : SourceAdapter {
    override val type = "nexusmods"

    private val json = Json { ignoreUnknownKeys = true }
    private val shorthand = Regex("""^(?:nexus|nexusmods):([a-z0-9]+)(?:/(\w+))?$""", RegexOption.IGNORE_CASE)
    private val reserved = setOf(
        "www", "users", "games", "mods", "about", "news", "forums", "help", "contact",
        "terms", "privacy", "search", "community", "go", "image", "images",
    )

    private enum class Variant(val path: String, val label: String, val updated: Boolean) {
        ADDED("latest_added", "new mods", false),
        UPDATED("latest_updated", "updated mods", true),
        TRENDING("trending", "trending mods", false),
    }

    private data class Target(val game: String, val variant: Variant)

    private fun variantOf(keyword: String?): Variant = when (keyword?.lowercase()) {
        "updated" -> Variant.UPDATED
        "trending" -> Variant.TRENDING
        else -> Variant.ADDED
    }

    private fun target(input: String): Target? {
        val s = input.trim()
        shorthand.matchEntire(s)?.let { return Target(it.groupValues[1].lowercase(), variantOf(it.groupValues[2])) }
        val host = s.lowercase().substringAfter("nexusmods.com/", "")
        if (host.isEmpty()) return null
        val segments = host.substringBefore('?').substringBefore('#').split('/').filter { it.isNotBlank() }
        val game = segments.firstOrNull()?.takeUnless { it in reserved } ?: return null
        // Old RSS URLs look like /<game>/rss/<kind>; map the "updated" kind, otherwise treat as newest.
        val rssKind = segments.indexOf("rss").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
        val variant = if (rssKind != null) variantOf(if (rssKind.startsWith("updated")) "updated" else null) else Variant.ADDED
        return Target(game, variant)
    }

    override fun accepts(input: String) = target(input) != null

    private fun apiUrl(target: Target) =
        "https://api.nexusmods.com/v1/games/${target.game}/mods/${target.variant.path}.json"

    override suspend fun resolve(input: String): FeedInfo {
        val t = target(input) ?: throw FetchException("Not a Nexus Mods game")
        return FeedInfo(
            type = type,
            // The key is added at fetch time so it is never stored in the feed row.
            url = apiUrl(t),
            title = "${t.game} — ${t.variant.label}",
            siteUrl = "https://www.nexusmods.com/${t.game}",
            iconUrl = "https://www.nexusmods.com/favicon.ico",
        )
    }

    override suspend fun fetch(feed: Feed): FetchResult {
        val key = secrets?.get(SecretStore.NEXUSMODS_API_KEY)?.ifBlank { null }
            ?: throw FetchException("Add your Nexus Mods API key in Settings to follow Nexus games")
        val body = client.getOk(feed.url) { header("apikey", key) }.bodyAsText()
        val useUpdated = "latest_updated" in feed.url
        return FetchResult.Fetched(parseMods(body, useUpdated))
    }

    fun parseMods(body: String, useUpdated: Boolean): List<EntryDraft> {
        val now = nowMillis()
        val mods = json.parseToJsonElement(body).jsonArray
        return mods.mapNotNull { element ->
            val o = element.jsonObject
            val id = o["mod_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val domain = o.str("domain_name") ?: return@mapNotNull null
            val timestamp = o.long(if (useUpdated) "updated_timestamp" else "created_timestamp")
            EntryDraft(
                remoteId = id.toString(),
                url = "https://www.nexusmods.com/$domain/mods/$id",
                title = o.str("name"),
                sortDate = if (timestamp > 0) timestamp * 1000 else now,
                author = o.str("uploaded_by") ?: o.str("author"),
                summaryHtml = o.str("summary"),
                thumbnailUrl = o.str("picture_url"),
            )
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }

    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L
}
