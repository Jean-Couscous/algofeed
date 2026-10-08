package algofeed.fetch

import algofeed.SecretReader
import algofeed.SecretStore
import algofeed.data.Feed
import algofeed.util.Dates
import algofeed.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val mangadexJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.ifBlank { null }

/** Chapters from any MangaDex feed endpoint (per-title or followed), which share the `data[]` shape. */
fun parseMangadexChapters(body: String): List<EntryDraft> {
    val now = nowMillis()
    val data = mangadexJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray
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

/**
 * New-chapter releases for a MangaDex title via the public API (no key). Accepts a title URL
 * (`mangadex.org/title/<uuid>`) or `mangadex:<uuid>`. The followed-manga feed is
 * [MangadexFollowsAdapter].
 */
class MangadexAdapter(private val client: HttpClient, private val language: String = "en") : SourceAdapter {
    override val type = "mangadex"

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
        val attrs = mangadexJson.parseToJsonElement(client.getOk("https://api.mangadex.org/manga/$id").bodyAsText())
            .jsonObject["data"]?.jsonObject?.get("attributes")?.jsonObject?.get("title")?.jsonObject
        return attrs?.get(language)?.jsonPrimitive?.content
            ?: attrs?.values?.firstOrNull()?.jsonPrimitive?.content
    }

    override suspend fun fetch(feed: Feed): FetchResult =
        FetchResult.Fetched(parseMangadexChapters(client.getOk(feed.url).bodyAsText()))
}

/**
 * The logged-in user's followed-manga chapter feed. Needs a MangaDex Personal API Client; the user
 * logs in from Settings, which stores a refresh token that [auth] exchanges for bearer tokens at
 * fetch time. Accepts `mangadex:follows` or `mangadex.org/titles/follows`.
 */
class MangadexFollowsAdapter(private val client: HttpClient, private val auth: MangadexAuth) : SourceAdapter {
    override val type = "mangadex-follows"

    override fun accepts(input: String): Boolean {
        val s = input.trim().lowercase()
        return s == "mangadex:follows" || "mangadex.org/titles/follows" in s
    }

    override suspend fun resolve(input: String) = FeedInfo(
        type = type,
        url = "https://api.mangadex.org/user/follows/manga/feed?limit=30&order[publishAt]=desc",
        title = "MangaDex — Followed updates",
        siteUrl = "https://mangadex.org/titles/follows",
        iconUrl = "https://mangadex.org/favicon.ico",
    )

    override suspend fun fetch(feed: Feed): FetchResult {
        val token = auth.accessToken() ?: throw FetchException("Log in to MangaDex in Settings to follow your titles")
        val body = client.getOk(feed.url) { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        return FetchResult.Fetched(parseMangadexChapters(body))
    }
}

/**
 * MangaDex OAuth for a Personal API Client (Keycloak password grant). [login] trades the user's
 * credentials for a refresh token; [accessToken] refreshes a short-lived bearer token on demand,
 * reading the stored client id/secret and refresh token through [secrets]. Tokens live in memory
 * only; the refresh token is persisted by the caller.
 */
class MangadexAuth(private val client: HttpClient, private val secrets: SecretReader?) {
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var expiresAt = 0L

    /** Returns the refresh token to persist. Throws [FetchException] with a readable message on failure. */
    suspend fun login(clientId: String, clientSecret: String, username: String, password: String): String {
        val token = grant(
            Parameters.build {
                append("grant_type", "password")
                append("username", username)
                append("password", password)
                append("client_id", clientId)
                append("client_secret", clientSecret)
            },
        )
        val refresh = token.str("refresh_token") ?: throw FetchException("MangaDex did not return a refresh token")
        cache(token)
        return refresh
    }

    /** A valid bearer token, refreshing as needed; null when not logged in. */
    suspend fun accessToken(): String? = mutex.withLock {
        accessToken?.takeIf { nowMillis() < expiresAt }?.let { return it }
        val clientId = secrets?.get(SecretStore.MANGADEX_CLIENT_ID) ?: return null
        val clientSecret = secrets.get(SecretStore.MANGADEX_CLIENT_SECRET) ?: return null
        val refresh = secrets.get(SecretStore.MANGADEX_REFRESH_TOKEN) ?: return null
        val token = grant(
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refresh)
                append("client_id", clientId)
                append("client_secret", clientSecret)
            },
        )
        cache(token)
        accessToken
    }

    fun forget() {
        accessToken = null
        expiresAt = 0L
    }

    private suspend fun grant(form: Parameters): JsonObject {
        val response = client.submitForm(TOKEN_ENDPOINT, form)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = runCatching { mangadexJson.parseToJsonElement(body).jsonObject.str("error_description") }.getOrNull()
            throw FetchException(detail ?: "MangaDex login failed (HTTP ${response.status.value})")
        }
        return mangadexJson.parseToJsonElement(body).jsonObject
    }

    private fun cache(token: JsonObject) {
        accessToken = token.str("access_token")
        // Refresh a little early so a token never expires mid-request.
        val ttl = token["expires_in"]?.jsonPrimitive?.longOrNull ?: 900L
        expiresAt = nowMillis() + (ttl - 30).coerceAtLeast(0) * 1000
    }

    private companion object {
        const val TOKEN_ENDPOINT = "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token"
    }
}
