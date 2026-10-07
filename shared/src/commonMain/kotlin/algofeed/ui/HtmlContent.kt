package algofeed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/** A readable document reduced to the blocks the reader knows how to draw. */
sealed interface Block {
    data class Heading(val level: Int, val text: AnnotatedString) : Block
    data class Paragraph(val text: AnnotatedString, val quote: Boolean = false) : Block
    data class ListItem(val marker: String, val depth: Int, val text: AnnotatedString) : Block
    data class Code(val text: String) : Block
    /** Shown as a link, not loaded: [alt] from the source, [caption] from a figcaption. */
    data class Image(val url: String, val alt: String?, val caption: String?) : Block {
        private val text get() = alt ?: caption

        /** Link text: the first sentence of the description, or the file name. */
        val label: String
            get() {
                val t = text ?: return url.substringBefore('?').substringAfterLast('/').ifBlank { "Image" }
                val sentence = Regex("""^.+?[.!?](?=\s|$)""").find(t)?.value ?: t
                return if (sentence.length <= LABEL_MAX) sentence else sentence.take(LABEL_MAX).substringBeforeLast(' ') + "…"
            }

        /** The rest of the description after the sentence used as the link, if any. */
        val description: String?
            get() {
                val t = text ?: return null
                val rest = if (label.endsWith("…")) t else t.removePrefix(label).trim()
                return rest.ifEmpty { null }
            }

        companion object {
            const val LABEL_MAX = 100
        }
    }
    data class Embed(val url: String) : Block
    data object Rule : Block
}

data class LinkColors(val link: Color, val code: Color)

object HtmlBlocks {
    private val blockTags = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "nav", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "pre", "blockquote", "hr", "img", "table", "tr",
        "dl", "dt", "dd", "iframe", "video", "picture", "details", "summary",
    )

    fun parse(html: String, baseUrl: String, colors: LinkColors): List<Block> {
        val doc = Ksoup.parse(html, baseUrl)
        val out = ArrayList<Block>()
        Walker(out, colors).walkChildren(doc.body(), quote = false, listDepth = 0)
        return out.filterNot { it is Block.Paragraph && it.text.isBlank() }
    }

    private val whitespace = Regex("\\s+")
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".svg")

    /** Inline text being built, tracking the last character to collapse whitespace across nodes. */
    private class Buf {
        val b = AnnotatedString.Builder()
        var last = '\n'
        fun newline() {
            b.append("\n")
            last = '\n'
        }
    }

    private class Walker(val out: MutableList<Block>, val colors: LinkColors) {
        private var inline = Buf()

        private fun flush(quote: Boolean) {
            val text = inline.b.toAnnotatedString().trimmed()
            if (text.isNotEmpty()) out += Block.Paragraph(text, quote)
            inline = Buf()
        }

        fun walkChildren(element: Element, quote: Boolean, listDepth: Int) {
            for (child in element.childNodes()) walk(child, quote, listDepth)
            flush(quote)
        }

        private fun walk(node: Node, quote: Boolean, listDepth: Int) {
            if (node is TextNode) {
                appendText(inline, node.text())
                return
            }
            if (node !is Element) return
            val tag = node.tagName().lowercase()
            if (tag in setOf("script", "style", "noscript", "svg", "button", "form", "input")) return
            if (tag !in blockTags) {
                appendInline(inline, node)
                return
            }
            flush(quote)
            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" ->
                    out += Block.Heading(tag[1].digitToInt(), inlineOf(node))
                "pre" -> out += Block.Code(node.wholeText().trimEnd())
                "hr" -> out += Block.Rule
                "img" -> imageBlock(node, null)?.let { out += it }
                "picture" -> node.selectFirst("img")?.let { imageBlock(it, null) }?.let { out += it }
                "figure" -> {
                    val caption = node.selectFirst("figcaption")?.text()?.ifBlank { null }
                    val img = node.selectFirst("img")?.let { imageBlock(it, caption) }
                    if (img != null) out += img
                    else walkChildren(node, quote, listDepth)
                }
                "iframe", "video" -> {
                    val src = node.absUrl("src").ifBlank { node.selectFirst("source")?.absUrl("src").orEmpty() }
                    if (src.startsWith("http")) out += Block.Embed(src)
                }
                "blockquote" -> walkChildren(node, quote = true, listDepth = listDepth)
                "ul", "ol" -> {
                    var n = 1
                    for (li in node.children()) {
                        if (li.tagName().lowercase() != "li") continue
                        val marker = if (tag == "ol") "${n++}." else "•"
                        val nested = li.children().filter { it.tagName().lowercase() in setOf("ul", "ol") }
                        nested.forEach { it.remove() }
                        out += Block.ListItem(marker, listDepth, inlineOf(li))
                        nested.forEach { walk(it, quote, listDepth + 1) }
                    }
                }
                else -> walkChildren(node, quote, listDepth)
            }
        }

        private fun imageBlock(img: Element, caption: String?): Block.Image? {
            val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }.takeIf { it.startsWith("http") } ?: return null
            // Prefer the full-size file when the image is wrapped in a link to it.
            val linked = img.parents().firstOrNull { it.tagName().lowercase() == "a" }?.absUrl("href")
                ?.takeIf { href -> imageExtensions.any { href.substringBefore('?').lowercase().endsWith(it) } }
            val alt = img.attr("alt").trim().ifBlank { null } ?: img.attr("title").trim().ifBlank { null }
            return Block.Image(linked ?: src, alt, caption?.takeIf { it != alt })
        }

        private fun inlineOf(element: Element): AnnotatedString {
            val b = Buf()
            for (child in element.childNodes()) {
                when (child) {
                    is TextNode -> appendText(b, child.text())
                    is Element -> if (child.tagName().lowercase() in setOf("p", "div")) {
                        if (b.b.length > 0) b.newline()
                        appendInline(b, child)
                    } else appendInline(b, child)
                }
            }
            return b.b.toAnnotatedString().trimmed()
        }

        private fun appendInline(buf: Buf, el: Element) {
            val b = buf.b
            val tag = el.tagName().lowercase()
            if (tag in setOf("script", "style", "svg", "button")) return
            fun children() = el.childNodes().forEach {
                when (it) {
                    is TextNode -> appendText(buf, it.text())
                    is Element -> appendInline(buf, it)
                }
            }
            when (tag) {
                "br" -> buf.newline()
                "a" -> {
                    val href = el.absUrl("href")
                    if (href.startsWith("http")) {
                        b.withLink(LinkAnnotation.Url(href, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) { children() }
                    } else children()
                }
                "b", "strong" -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
                "i", "em", "cite" -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
                "code", "kbd", "samp" -> b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.code, fontSize = 0.9.em())) { children() }
                "s", "del", "strike" -> b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { children() }
                "sup" -> b.withStyle(SpanStyle(fontSize = 0.75.em())) { children() }
                "img" -> {} // inline images (emoji, icons) are dropped
                else -> children()
            }
        }

        private fun appendText(buf: Buf, text: String) {
            if (text.isEmpty()) return
            val collapsed = text.replace(whitespace, " ")
            val piece = if (buf.last == ' ' || buf.last == '\n') collapsed.trimStart() else collapsed
            if (piece.isEmpty()) return
            buf.b.append(piece)
            buf.last = piece.last()
        }
    }

    private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

    private fun AnnotatedString.trimmed(): AnnotatedString {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return AnnotatedString("")
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return subSequence(start, end)
    }
}

@Composable
fun HtmlContent(html: String, baseUrl: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LinkColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHigh)
    val blocks = remember(html, baseUrl, colors) { HtmlBlocks.parse(html, baseUrl, colors) }
    val reading = LocalReadingFont.current
    val body = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = reading,
        fontSize = if (compact) 16.sp else 18.sp,
        lineHeight = if (compact) 25.sp else 29.sp,
    )
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)) {
            for (block in blocks) {
                when (block) {
                    is Block.Heading -> Text(
                        block.text,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = when (block.level) { 1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; else -> 17.sp },
                            lineHeight = when (block.level) { 1 -> 32.sp; 2 -> 28.sp; else -> 24.sp },
                        ),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    is Block.Paragraph -> if (block.quote) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            Text(block.text, style = body.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 16.dp))
                        }
                    } else Text(block.text, style = body)
                    is Block.ListItem -> Row(Modifier.padding(start = (block.depth * 20).dp)) {
                        Text(block.marker, style = body, modifier = Modifier.width(28.dp))
                        Text(block.text, style = body, modifier = Modifier.weight(1f))
                    }
                    is Block.Code -> Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(block.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, softWrap = false)
                    }
                    is Block.Image -> Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Outlined.Image, "Image",
                                Modifier.padding(top = 3.dp, end = 8.dp).size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                buildAnnotatedString {
                                    withLink(LinkAnnotation.Url(block.url, TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)))) {
                                        append(block.label)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        block.description?.let { ImageDescription(it) }
                        block.caption?.takeIf { block.alt != null }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp, top = 4.dp))
                        }
                    }
                    is Block.Embed -> Text(
                        buildAnnotatedString {
                            withLink(LinkAnnotation.Url(block.url, TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)))) {
                                append("Embedded media: ${block.url.substringAfter("://").substringBefore('/')}")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Block.Rule -> HorizontalDivider(color = LocalExtraColors.current.divider)
                }
            }
        }
    }
}

@Composable
private fun ImageDescription(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 26.dp, top = 4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                if (expanded) "Show less" else "Show full description",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).clickable { expanded = !expanded },
            )
        }
    }
}
