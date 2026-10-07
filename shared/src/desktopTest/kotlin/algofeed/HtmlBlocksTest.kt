package algofeed

import algofeed.ui.Block
import algofeed.ui.HtmlBlocks
import algofeed.ui.LinkColors
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlBlocksTest {
    private fun images(html: String) =
        HtmlBlocks.parse(html, "https://site.example/post/", LinkColors(Color.Blue, Color.Gray)).filterIsInstance<Block.Image>()

    @Test fun imagesBecomeLinksLabelledByAltText() {
        val blocks = images(
            """<p>Intro</p><img src="/a/chart.png?w=600" alt="Sales by month">
               <figure><a href="/full/photo.jpg"><img src="thumb.jpg" alt=""></a><figcaption>The harbour at night</figcaption></figure>
               <img src="https://cdn.example/x/diagram.svg">"""
        )
        assertEquals(
            listOf(
                Block.Image("https://site.example/a/chart.png?w=600", "Sales by month", null),
                Block.Image("https://site.example/full/photo.jpg", null, "The harbour at night"),
                Block.Image("https://cdn.example/x/diagram.svg", null, null),
            ),
            blocks,
        )
        assertEquals(listOf("Sales by month", "The harbour at night", "diagram.svg"), blocks.map { it.label })
        assertEquals(listOf(null, null, null), blocks.map { it.description })
    }

    @Test fun longAltTextIsShortenedForTheLink() {
        val alt = "Screenshot of a retro pixel-art music player. A large scene shows a harbor at night under a purple sky."
        val image = images("""<img src="https://site.example/s.png" alt="$alt">""").single()
        assertEquals("Screenshot of a retro pixel-art music player.", image.label)
        assertEquals("A large scene shows a harbor at night under a purple sky.", image.description)
        val noSentence = Block.Image("https://x/y.png", "word ".repeat(40).trim(), null)
        assertTrue(noSentence.label.length <= Block.Image.LABEL_MAX + 1 && noSentence.label.endsWith("…"))
    }
}
