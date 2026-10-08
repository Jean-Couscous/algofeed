package algofeed.ui

import algofeed.data.Entry
import algofeed.data.Feed
import algofeed.data.MediaCodec
import algofeed.rank.Breakdown
import algofeed.rank.Ranked
import algofeed.util.Html
import algofeed.util.relativeTime
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun EntryList(
    items: List<Ranked>,
    feeds: Map<Long, Feed>,
    listState: LazyListState,
    focused: Int,
    openId: Long?,
    showWhy: Boolean,
    onOpen: (Entry) -> Unit,
    onExternal: (Entry) -> Unit,
    onFavorite: (Entry) -> Unit,
    onBookmark: (Entry) -> Unit,
    onDismiss: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    /** Whether the entry is upvoted on Hacker News; null hides the button. */
    hnVoted: (Entry) -> Boolean? = { null },
    onUpvote: (Entry) -> Unit = {},
    /** Rows are centred at this width when the list is wider. */
    maxItemWidth: Dp = Dp.Unspecified,
) {
    val videoIds = remember(items) {
        if (!inlineVideoSupported) emptySet()
        else items.mapNotNull { r -> r.entry.id.takeIf { MediaCodec.firstVideo(r.entry.media) != null } }.toSet()
    }
    val activeVideoId by remember(videoIds) {
        derivedStateOf {
            if (videoIds.isEmpty()) null
            else {
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val videos = info.visibleItemsInfo
                    .filter { it.key in videoIds }
                    .map { VisibleVideo(it.key as Long, it.offset, it.offset + it.size) }
                activeVideoKey(videos, center)
            }
        }
    }
    LazyColumn(modifier.mouseScrolling(listState), state = listState, contentPadding = contentPadding) {
        itemsIndexed(items, key = { _, r -> r.entry.id }) { index, ranked ->
            Column(Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = maxItemWidth).fillMaxWidth()) {
                EntryRow(
                    ranked = ranked,
                    feed = feeds[ranked.entry.feedId],
                    highlighted = index == focused || ranked.entry.id == openId,
                    showWhy = showWhy,
                    autoPlayVideo = ranked.entry.id == activeVideoId,
                    onOpen = { onOpen(ranked.entry) },
                    onExternal = { onExternal(ranked.entry) },
                    onFavorite = { onFavorite(ranked.entry) },
                    onBookmark = { onBookmark(ranked.entry) },
                    onDismiss = { onDismiss(ranked.entry) },
                    upvoted = hnVoted(ranked.entry),
                    onUpvote = { onUpvote(ranked.entry) },
                )
                HorizontalDivider(color = LocalExtraColors.current.divider)
            }
        }
    }
}

@Composable
private fun EntryRow(
    ranked: Ranked,
    feed: Feed?,
    highlighted: Boolean,
    showWhy: Boolean,
    autoPlayVideo: Boolean,
    onOpen: () -> Unit,
    onExternal: () -> Unit,
    onFavorite: () -> Unit,
    onBookmark: () -> Unit,
    onDismiss: () -> Unit,
    upvoted: Boolean?,
    onUpvote: () -> Unit,
) {
    val entry = ranked.entry
    val uri = LocalUriHandler.current
    val snippet = remember(entry.id) { Html.snippet(entry.summaryHtml ?: entry.contentHtml, if (entry.title == null) 500 else 240) }
    val seen = entry.viewedAt != null || entry.openedAt != null
    var whyOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque, so swipe actions don't show through.
            .background(MaterialTheme.colorScheme.background)
            .background(if (highlighted) LocalExtraColors.current.selection else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeedIcon(feed, 20)
            Spacer(Modifier.width(10.dp))
            Text(
                feed?.title ?: "",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            entry.author?.takeIf { it != feed?.title }?.let {
                Text(
                    "  $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(0.01f))
            Text(
                relativeTime(entry.sortDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            )
        }
        Column(Modifier.padding(top = 8.dp)) {
            entry.title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (seen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snippet.isNotEmpty()) {
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.title == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (entry.title == null) 8 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (entry.title != null) 4.dp else 0.dp),
                )
            }
            val video = remember(entry.id, entry.media) { if (inlineVideoSupported) MediaCodec.firstVideo(entry.media) else null }
            if (video != null) {
                InlineVideo(
                    url = video.streamUrl ?: video.url,
                    thumbnailUrl = video.thumbnailUrl ?: entry.thumbnailUrl,
                    autoPlay = autoPlayVideo,
                    muted = true,
                    showControls = false,
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp),
                    onClick = onOpen,
                )
            } else {
                entry.thumbnailUrl?.let { CardImage(it, Modifier.padding(top = 10.dp, end = 8.dp)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            entry.commentsUrl?.let { comments ->
                Action(Icons.Outlined.ChatBubbleOutline, "Open discussion") { onExternal(); uri.openUri(comments) }
            }
            entry.url?.let { url ->
                Action(Icons.Outlined.OpenInNew, "Open in browser") { onExternal(); uri.openUri(url) }
            }
            Action(
                if (entry.favoritedAt != null) Icons.Filled.Star else Icons.Outlined.StarOutline,
                if (entry.favoritedAt != null) "Remove from favorites" else "Favorite",
                tint = if (entry.favoritedAt != null) LocalExtraColors.current.favorite else null,
                onClick = onFavorite,
            )
            BookmarkAction(entry, onBookmark)
            upvoted?.let { on ->
                Action(
                    if (on) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    if (on) "Take back upvote" else "Upvote on Hacker News",
                    tint = if (on) MaterialTheme.colorScheme.primary else null,
                    onClick = onUpvote,
                )
            }
            Action(Icons.Outlined.ThumbDown, "Show less like this", onClick = onDismiss)
            if (showWhy && ranked.score != 0.0) {
                Action(Icons.Outlined.Info, "Why this entry is here") { whyOpen = !whyOpen }
            }
        }
        if (whyOpen) WhyPanel(ranked.breakdown, ranked.score)
    }
}

@Composable
private fun WhyPanel(b: Breakdown, score: Double) {
    Column(
        Modifier.padding(bottom = 12.dp, end = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Score ${fmt(score)}", style = MaterialTheme.typography.labelLarge)
        Factor("Freshness", b.recency)
        Factor("Rare source", b.rarity)
        Factor("Your history with this feed", b.source)
        Factor("Topic match", b.content, b.topTerms.takeIf { it.isNotEmpty() }?.joinToString(", "))
        if (b.fatigue != 0.0) Factor("More from this feed above", b.fatigue)
        if (b.duplicate != 0.0) Factor("Similar to a higher entry", b.duplicate)
    }
}

@Composable
private fun Factor(label: String, value: Double, detail: String? = null) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(200.dp))
        Text(
            fmt(value),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (value < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(56.dp),
        )
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun fmt(v: Double): String {
    val r = kotlin.math.round(v * 100) / 100
    val s = r.toString().let { if ('.' in it) it.padEnd(it.indexOf('.') + 3, '0') else "$it.00" }
    return if (v > 0) "+$s" else s
}

@Composable
fun Action(icon: ImageVector, label: String, tint: Color? = null, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(if (LocalTouchUi.current) 48.dp else 40.dp)) {
            Icon(icon, label, Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Twitter-style bookmark: a private save for later, listed under Bookmarks. */
@Composable
fun BookmarkAction(entry: Entry, onClick: () -> Unit) {
    val on = entry.bookmarkedAt != null
    Action(
        if (on) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        if (on) "Remove from Bookmarks" else "Bookmark",
        tint = if (on) MaterialTheme.colorScheme.primary else null,
        onClick = onClick,
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Placeholder cards shown on a cold start when the database has nothing cached yet. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Column(modifier.fillMaxSize()) {
        repeat(6) { i ->
            SkeletonCard(alpha, withThumbnail = i % 2 == 0)
            HorizontalDivider(color = LocalExtraColors.current.divider)
        }
    }
}

@Composable
private fun SkeletonCard(alpha: Float, withThumbnail: Boolean) {
    val tone = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    fun Modifier.block() = clip(RoundedCornerShape(4.dp)).background(tone)
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(120.dp).height(11.dp).block())
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(0.92f).height(15.dp).block())
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.55f).height(15.dp).block())
        if (withThumbnail) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(tone))
        }
    }
}

/**
 * The card's image, as in Reddit's card view: always shown whole. Landscape images fill the width at
 * their own shape; portrait and square ones sit centred in a 4:3 box whose sides show a blurred,
 * darkened copy of the same picture.
 */
@Composable
private fun CardImage(url: String, modifier: Modifier = Modifier) {
    // Remembered across recycling so a card keeps its shape when it scrolls back into view.
    var natural by remember(url) { mutableStateOf(ImageRatios[url]) }
    val box = CardImageShape.ratio(natural)
    Box(modifier.widthIn(max = 640.dp).fillMaxWidth().aspectRatio(box).clip(RoundedCornerShape(12.dp))) {
        if (CardImageShape.letterboxed(natural)) {
            // Blur needs Android 12+; on older versions the backdrop is just the darkened picture.
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(28.dp),
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0 && size.height > 0) {
                    val r = size.width / size.height
                    ImageRatios[url] = r
                    natural = r
                }
            },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** A visible video card: [key] identifies it, [top]/[bottom] are its bounds in the viewport's coordinates. */
class VisibleVideo<K>(val key: K, val top: Int, val bottom: Int)

/**
 * The one video that should autoplay: the card straddling [viewportCenter], else the nearest. Pure,
 * so it can be unit-tested apart from the list.
 */
fun <K> activeVideoKey(videos: List<VisibleVideo<K>>, viewportCenter: Int): K? =
    videos.firstOrNull { viewportCenter in it.top until it.bottom }?.key
        ?: videos.minByOrNull { kotlin.math.abs((it.top + it.bottom) / 2 - viewportCenter) }?.key

object CardImageShape {
    /** Portrait and square images get this box; anything wider keeps its own shape. */
    const val BOX = 4f / 3f

    /** Width / height of the box for an image of [natural] ratio (null while loading). */
    fun ratio(natural: Float?): Float = maxOf(natural ?: BOX, BOX)

    /** Narrower than the box, so the sides need filling. */
    fun letterboxed(natural: Float?) = natural != null && natural < BOX - 0.01f
}

/** Image shapes seen this session, by URL; bounded so long sessions don't grow it forever. */
private object ImageRatios {
    private val ratios = object : LinkedHashMap<String, Float>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > 1000
    }

    operator fun get(url: String): Float? = ratios[url]
    operator fun set(url: String, ratio: Float) {
        ratios[url] = ratio
    }
}
