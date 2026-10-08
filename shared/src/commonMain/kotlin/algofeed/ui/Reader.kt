package algofeed.ui

import algofeed.data.Feed
import algofeed.data.MediaCodec
import algofeed.data.MediaKind
import algofeed.util.Urls
import algofeed.util.relativeTime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ReaderPane(
    reader: ReaderState,
    feed: Feed?,
    narrow: Boolean,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onBookmark: () -> Unit,
    onExternal: () -> Unit,
    onToggleSource: () -> Unit,
    /** Set for HN stories. */
    comments: CommentActions? = null,
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    val entry = reader.entry
    val link = entry?.url ?: reader.url
    val mediaPage = Urls.isMediaPage(link)
    // Sources that resolve their attachments (Reddit galleries, Bluesky, 4chan, …) carry them here.
    val media = remember(entry?.id, entry?.media) { MediaCodec.decode(entry?.media) }
    // Image posts show the image; galleries and videos show their preview and link out for the rest.
    val picture = if (media.isNotEmpty()) null else if (Urls.isImage(link)) link else if (mediaPage) entry?.thumbnailUrl else null
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var commentsTop by remember { mutableStateOf(0) }
    LaunchedEffect(entry?.id, reader.url) { scroll.scrollTo(0) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Action(if (narrow) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Close, if (narrow) "Back" else "Close reader", onClick = onClose)
            // Takes the free space; on narrow phones the label shortens before any icon is pushed out.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (entry != null && (entry.contentHtml != null || entry.summaryHtml != null) && entry.url != null && picture == null && !mediaPage) {
                    TextButton(onClick = onToggleSource) {
                        Text(if (reader.showingFeedVersion) "Load full article" else "Show feed version", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (reader.comments != null && comments != null) {
                Action(Icons.Outlined.ChatBubbleOutline, "Go to comments") { scope.launch { scroll.animateScrollTo(commentsTop) } }
            } else {
                entry?.commentsUrl?.let { Action(Icons.Outlined.ChatBubbleOutline, "Open discussion") { onExternal(); uri.openUri(it) } }
            }
            link?.let { Action(Icons.Outlined.OpenInNew, "Open in browser") { onExternal(); uri.openUri(it) } }
            val storyId = reader.comments?.takeIf { it.source == CommentSource.HackerNews }?.storyId
            if (storyId != null && comments?.loggedIn == true) {
                val voted = comments.voted(storyId)
                Action(
                    if (voted) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    if (voted) "Take back upvote" else "Upvote on Hacker News",
                    tint = if (voted) MaterialTheme.colorScheme.primary else null,
                ) { comments.onVote(storyId) }
            }
            if (entry != null) {
                Action(
                    if (entry.favoritedAt != null) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    if (entry.favoritedAt != null) "Remove from favorites" else "Favorite",
                    tint = if (entry.favoritedAt != null) LocalExtraColors.current.favorite else null,
                    onClick = onFavorite,
                )
                BookmarkAction(entry, onBookmark)
            }
        }
        if (reader.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider(color = LocalExtraColors.current.divider)
        Box(Modifier.fillMaxSize().mouseScrolling(scroll).verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 700.dp).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val title = reader.article?.title?.takeIf { entry?.title == null || it.isNotBlank() } ?: entry?.title
                title?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = LocalReadingFont.current, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
                    )
                }
                val byline = listOfNotNull(
                    feed?.title,
                    (entry?.author ?: reader.article?.byline)?.takeIf { it != feed?.title },
                    entry?.let { relativeTime(it.sortDate) + " ago" },
                ).joinToString("  /  ")
                if (byline.isNotEmpty()) {
                    Text(byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                reader.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.padding(top = 4.dp))
                picture?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = entry?.title,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    )
                }
                media.forEach { item ->
                    AsyncImage(
                        model = if (item.kind == MediaKind.VIDEO) item.thumbnailUrl ?: item.url else item.url,
                        contentDescription = item.caption ?: entry?.title,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    )
                    item.caption?.let { c ->
                        Text(c, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.kind == MediaKind.VIDEO) {
                        TextButton(onClick = { onExternal(); uri.openUri(item.url) }) { Text("Play the video in the browser") }
                    }
                }
                if (mediaPage && link != null && media.isEmpty()) {
                    TextButton(onClick = { onExternal(); uri.openUri(link) }) {
                        Text(if (Urls.host(link) == "v.redd.it") "Play the video in the browser" else "See the whole gallery in the browser")
                    }
                }
                reader.article?.let { HtmlContent(it.html, link ?: "") }
                if (!reader.loading && reader.article == null && link != null) {
                    TextButton(onClick = { onExternal(); uri.openUri(link) }) { Text("Open in browser instead") }
                }
                if (reader.comments != null && comments != null) {
                    // The column's top padding is above this position, which suits the scroll target.
                    Box(Modifier.onGloballyPositioned { commentsTop = it.positionInParent().y.toInt() }) {
                        CommentsSection(reader.comments, comments, onOpenThread = { onExternal(); uri.openUri(reader.comments.threadUrl) })
                    }
                }
            }
        }
    }
}
