package algofeed.opml

import algofeed.data.Feed
import algofeed.data.Folder
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

data class OpmlFeed(val url: String, val title: String?, val folder: String?)

object Opml {
    /** Reads feeds from an OPML document; nested outlines without an xmlUrl become folders. */
    fun parse(xml: String): List<OpmlFeed> {
        val doc = Ksoup.parse(xml, Parser.xmlParser(), "")
        val body = doc.selectFirst("body") ?: return emptyList()
        val out = ArrayList<OpmlFeed>()
        fun walk(element: Element, folder: String?) {
            for (child in element.children()) {
                if (child.tagName().lowercase() != "outline") continue
                val url = child.attr("xmlUrl").ifBlank { child.attr("xmlurl") }
                val title = child.attr("title").ifBlank { child.attr("text") }.ifBlank { null }
                if (url.isNotBlank()) {
                    out += OpmlFeed(url.trim(), title, folder)
                } else {
                    walk(child, title ?: folder)
                }
            }
        }
        walk(body, null)
        return out
    }

    fun export(folders: List<Folder>, feeds: List<Feed>): String {
        val byFolder = feeds.groupBy { it.folderId }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<opml version="2.0">""").append('\n')
        sb.append("  <head><title>Algofeed subscriptions</title></head>\n")
        sb.append("  <body>\n")
        fun outline(feed: Feed, indent: String) {
            sb.append(indent)
                .append("""<outline type="rss" text="${esc(feed.title)}" title="${esc(feed.title)}" xmlUrl="${esc(feed.url)}"""")
            feed.siteUrl?.let { sb.append(""" htmlUrl="${esc(it)}"""") }
            sb.append("/>\n")
        }
        for (folder in folders.sortedBy { it.name.lowercase() }) {
            val members = byFolder[folder.id].orEmpty()
            if (members.isEmpty()) continue
            sb.append("""    <outline text="${esc(folder.name)}" title="${esc(folder.name)}">""").append('\n')
            members.forEach { outline(it, "      ") }
            sb.append("    </outline>\n")
        }
        byFolder[null].orEmpty().forEach { outline(it, "    ") }
        sb.append("  </body>\n</opml>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
