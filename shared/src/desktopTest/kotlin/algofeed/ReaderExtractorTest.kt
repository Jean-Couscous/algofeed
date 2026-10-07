package algofeed

import algofeed.fetch.FetchException
import algofeed.reader.JvmReaderExtractor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ReaderExtractorTest {
    private fun extractor(type: String, body: ByteArray) = JvmReaderExtractor(HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", type))
    }))

    // JPEG bytes run through Readability used to come out as a page of gibberish.
    @Test fun imageBecomesAnImage() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte())
        val article = extractor("image/jpeg", jpeg).extract("https://i.redd.it/p1.jpeg")
        assertEquals("<img src=\"https://i.redd.it/p1.jpeg\">", article.html)
    }

    @Test fun otherFilesAreNotParsed() = runTest {
        assertFailsWith<FetchException> { extractor("application/pdf", "%PDF-1.7".encodeToByteArray()).extract("https://example.com/a.pdf") }
    }
}
