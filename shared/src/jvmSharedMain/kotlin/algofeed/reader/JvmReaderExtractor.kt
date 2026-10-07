package algofeed.reader

import algofeed.fetch.FetchException
import algofeed.fetch.getOk
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.extended.Readability4JExtended

class JvmReaderExtractor(private val client: HttpClient) : ReaderExtractor {
    override suspend fun extract(url: String): Article {
        val response = client.getOk(url)
        val finalUrl = response.call.request.url.toString()
        // Only parse web pages: an image or file read as HTML comes out as gibberish.
        val type = response.contentType()
        if (type != null && type.match(ContentType.Image.Any)) return Article(null, null, "<img src=\"$finalUrl\">")
        if (type != null && !type.match(ContentType.Text.Html) && !type.match(ContentType.Application.Xml) &&
            !type.match(ContentType("application", "xhtml+xml"))
        ) {
            throw FetchException("not a web page ($type)")
        }
        val html = response.bodyAsText()
        return withContext(Dispatchers.Default) {
            val article = Readability4JExtended(finalUrl, html).parse()
            val content = article.content ?: throw FetchException("Nothing readable at $url")
            Article(article.title, article.byline, content)
        }
    }
}

actual fun createReaderExtractor(client: HttpClient): ReaderExtractor = JvmReaderExtractor(client)
