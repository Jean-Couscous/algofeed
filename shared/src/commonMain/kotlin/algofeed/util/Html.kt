package algofeed.util

import com.fleeksoft.ksoup.Ksoup

object Html {
    fun toText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return Ksoup.parse(html).text().trim()
    }

    fun firstImage(html: String?, baseUrl: String = ""): String? {
        if (html.isNullOrBlank()) return null
        return Ksoup.parse(html, baseUrl).selectFirst("img[src]")?.absUrl("src")?.takeIf { it.startsWith("http") }
    }

    fun snippet(html: String?, max: Int = 280): String {
        val text = toText(html)
        return if (text.length <= max) text else text.take(max).substringBeforeLast(' ') + "…"
    }
}

object Urls {
    fun host(url: String?): String? =
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore(':')?.removePrefix("www.")?.ifEmpty { null }

    fun origin(url: String): String? {
        val scheme = url.substringBefore("://", "")
        if (scheme.isEmpty()) return null
        val authority = url.substringAfter("://").substringBefore('/')
        return "$scheme://$authority"
    }

    fun resolve(base: String, href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "${base.substringBefore(':')}:$href"
        href.startsWith("/") -> (origin(base) ?: "") + href
        else -> base.substringBeforeLast('/') + "/" + href
    }

    fun normalize(url: String?): String? = url
        ?.substringBefore('#')
        ?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")
        ?.trimEnd('/')
        ?.lowercase()

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif")

    /** A direct link to an image file, such as a Reddit or Imgur image post. */
    fun isImage(url: String?): Boolean {
        val path = url?.substringBefore('#')?.substringBefore('?') ?: return false
        return path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in imageExtensions
    }

    /** A page that is a gallery or video rather than an article: Reddit galleries and hosted videos. */
    fun isMediaPage(url: String?): Boolean {
        val host = host(url) ?: return false
        return host == "v.redd.it" || (host.endsWith("reddit.com") && "/gallery/" in url!!)
    }

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("http://") || t.startsWith("https://")) && ' ' !in t
    }

    /** The first http(s) URL in free text, such as a shared "Title https://…" string. */
    fun firstUrl(text: String): String? {
        var url = Regex("""https?://[^\s<>"]+""").find(text)?.value ?: return null
        // Drop sentence punctuation, and a closing parenthesis unless the URL opened one.
        while (url.isNotEmpty()) {
            val last = url.last()
            url = when {
                last in ".,;:!?'" -> url.dropLast(1)
                last == ')' && url.count { it == ')' } > url.count { it == '(' } -> url.dropLast(1)
                else -> break
            }
        }
        return url
    }
}
