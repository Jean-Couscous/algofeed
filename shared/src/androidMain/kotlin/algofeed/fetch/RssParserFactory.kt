package algofeed.fetch

import com.prof18.rssparser.RssParser
import com.prof18.rssparser.RssParserBuilder

actual fun createRssParser(): RssParser = RssParserBuilder().build()
