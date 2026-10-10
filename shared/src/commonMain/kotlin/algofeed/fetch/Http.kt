package algofeed.fetch

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0 Algofeed/0.1"

fun createHttpClient(): HttpClient = HttpClient {
    followRedirects = true
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
    }
    // Transient throttling (429) and server errors (5xx) are common on Reddit and others; back off and
    // retry a couple of times instead of turning them straight into a failed update.
    install(HttpRequestRetry) {
        maxRetries = 2
        retryOnServerErrors(maxRetries = 2)
        retryIf { _, response -> response.status == HttpStatusCode.TooManyRequests }
        exponentialDelay(respectRetryAfterHeader = true)
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, USER_AGENT)
    }
}

suspend fun HttpClient.getOk(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    val response = get(url, block)
    if (!response.status.isSuccess() && response.status.value != 304) {
        throw FetchException("HTTP ${response.status.value} for $url", status = response.status.value)
    }
    return response
}

fun String.withScheme(): String = if ("://" in this) this else "https://$this"
