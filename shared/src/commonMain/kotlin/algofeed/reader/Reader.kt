package algofeed.reader

import io.ktor.client.HttpClient

data class Article(val title: String?, val byline: String?, val html: String)

/** Readable-content extraction (Mozilla Readability port on JVM platforms). */
interface ReaderExtractor {
    suspend fun extract(url: String): Article
}

expect fun createReaderExtractor(client: HttpClient): ReaderExtractor
