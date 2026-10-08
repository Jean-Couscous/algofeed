package algofeed.fetch

import algofeed.data.Feed
import algofeed.util.Dates
import algofeed.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * New-chapter releases for a MangaDex title via the public API (no key). Accepts a title URL
 * (`mangadex.org/title/<uuid>`) or `mangadex:<uuid>`. The followed-manga feed (which needs OAuth)
 * is out of scope.
 */
class MangadexAdapter(private val client: HttpClient, private val language: String = "en") : SourceAdapter {
    override val type = "mangadex"

    private val json = Json { ignoreUnknownKeys = true }
    private val uuid = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)

    private fun id(input: String): String? {
        val s = input.trim()
        if (s.startsWith("mangadex:", ignoreCase = true)) return uuid.find(s)?.value?.lowercase()
        if ("mangadex.org/title/" in s.lowercase()) return uuid.find(s)?.value?.lowercase()
        return null
    }

    override fun accepts(input: String) = id(input) != null

    private fun feedUrl(id: String) =
        "https://api.mangadex.org/manga/$id/feed?limit=30&order[publishAt]=desc&translatedLanguage[]=$language"

    override suspend fun resolve(input: String): FeedInfo {
        val id = id(input) ?: throw FetchException("Not a MangaDex title")
        val title = runCatching { titleOf(id) }.getOrNull() ?: id
        return FeedInfo(
            type = type,
            url = feedUrl(id),
            title = title,
            siteUrl = "https://mangadex.org/title/$id",
            iconUrl = "https://mangadex.org/favicon.ico",
        )
    }

    private suspend fun titleOf(id: String): String? {
        val attrs = json.parseToJsonElement(client.getOk("https://api.mangadex.org/manga/$id").bodyAsText())
            .jsonObject["data"]?.jsonObject?.get("attributes")?.jsonObject?.get("title")?.jsonObject
        return attrs?.get(language)?.jsonPrimitive?.content
            ?: attrs?.values?.firstOrNull()?.jsonPrimitive?.content
    }

    override suspend fun fetch(feed: Feed): FetchResult =
        FetchResult.Fetched(parseFeed(client.getOk(feed.url).bodyAsText()))

    fun parseFeed(body: String): List<EntryDraft> {
        val now = nowMillis()
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: throw FetchException("Unexpected MangaDex response")
        return data.mapNotNull { item ->
            val o = item.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val a = o["attributes"]?.jsonObject ?: return@mapNotNull null
            val volume = a.str("volume")?.let { "Vol. $it " }.orEmpty()
            val chapter = a.str("chapter")?.let { "Ch. $it" } ?: "Oneshot"
            val name = a.str("title")?.let { " — $it" }.orEmpty()
            val external = a.str("externalUrl")
            EntryDraft(
                remoteId = id,
                url = external ?: "https://mangadex.org/chapter/$id",
                title = "$volume$chapter$name",
                sortDate = Dates.parse(a.str("publishAt") ?: a.str("readableAt")) ?: now,
                commentsUrl = "https://mangadex.org/chapter/$id",
            )
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }
}
