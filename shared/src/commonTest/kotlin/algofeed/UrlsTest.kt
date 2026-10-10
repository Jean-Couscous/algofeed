package algofeed

import algofeed.util.Urls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlsTest {
    @Test fun firstUrlInSharedText() {
        assertEquals("https://danluu.com/atom.xml", Urls.firstUrl("https://danluu.com/atom.xml"))
        assertEquals("https://example.com/post?id=1", Urls.firstUrl("Great read: https://example.com/post?id=1 via @someone"))
        assertEquals("http://a.example/x", Urls.firstUrl("see http://a.example/x. Also https://b.example"))
    }

    @Test fun trailingPunctuation() {
        assertEquals("https://example.com/a", Urls.firstUrl("(https://example.com/a)"))
        assertEquals("https://en.wikipedia.org/wiki/Mercury_(planet)", Urls.firstUrl("https://en.wikipedia.org/wiki/Mercury_(planet)."))
        assertEquals("https://example.com/a", Urls.firstUrl("\"https://example.com/a\""))
    }

    @Test fun noUrl() {
        assertNull(Urls.firstUrl("just some text"))
        assertNull(Urls.firstUrl("ftp://example.com"))
    }

    @Test fun imagesAndMediaPages() {
        assertTrue(Urls.isImage("https://i.redd.it/p1j1x3yc23uh1.jpeg"))
        assertTrue(Urls.isImage("https://i.imgur.com/abc.PNG?1"))
        assertFalse(Urls.isImage("https://example.com/post.html"))
        assertFalse(Urls.isImage("https://example.com/jpeg/"))
        // Reddit preview links keep their extension before the signed query, but the host counts regardless.
        assertTrue(Urls.isImage("https://preview.redd.it/abc.png?width=640&format=png&s=sig"))
        assertTrue(Urls.isImage("https://preview.redd.it/abc?width=640&s=sig"))
        assertTrue(Urls.isRedditPreview("https://preview.reddit.com/abc.jpg"))
        assertFalse(Urls.isRedditPreview("https://i.redd.it/abc.jpg"))
        assertTrue(Urls.isMediaPage("https://www.reddit.com/gallery/1x06c5i"))
        assertTrue(Urls.isMediaPage("https://v.redd.it/abc123"))
        assertFalse(Urls.isMediaPage("https://www.reddit.com/r/x/comments/a/b/"))
    }
}
