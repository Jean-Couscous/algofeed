package algofeed.ui

import algofeed.data.Entry
import algofeed.data.MediaCodec
import algofeed.data.MediaItem
import algofeed.data.MediaKind
import algofeed.rank.Ranked
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage

/** A grid of media-bearing entries; tapping a cell opens that entry's gallery in the viewer. */
@Composable
fun MediaGrid(
    items: List<Ranked>,
    onOpen: (Entry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.entry.id }) { ranked ->
            val entry = ranked.entry
            val media = remember(entry.id, entry.media) { MediaCodec.decode(entry.media) }
            val thumb = media.firstOrNull()?.let { it.thumbnailUrl ?: it.url } ?: entry.thumbnailUrl
            val hasVideo = media.any { it.kind == MediaKind.VIDEO }
            Box(Modifier.aspectRatio(1f).clickable { onOpen(entry) }) {
                AsyncImage(
                    model = thumb,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                if (hasVideo) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.4f), CircleBadge).padding(6.dp),
                    )
                } else if (media.size > 1) {
                    Icon(
                        Icons.Outlined.Collections,
                        contentDescription = "Gallery of ${media.size}",
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
        }
    }
}

private val CircleBadge = androidx.compose.foundation.shape.CircleShape

/** Full-screen gallery viewer: swipe between a post's images, pinch/scroll/double-tap to zoom, videos play with controls. */
@Composable
fun MediaViewer(
    media: List<MediaItem>,
    startIndex: Int,
    /** Whether [onOpenSource] opens an in-app discussion (vs. the source page in a browser). */
    sourceIsThread: Boolean,
    onOpenSource: (() -> Unit)?,
    onClose: () -> Unit,
) {
    if (media.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = fullScreenDialogProperties()) {
        SystemBarIcons(darkTheme = true)
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, media.lastIndex)) { media.size }
        // Lock paging while the current image is zoomed in, so a drag pans instead of flipping pages.
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pager.currentPage) { zoomed = false }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                val item = media[page]
                val onZoom: (Boolean) -> Unit = { if (page == pager.currentPage) zoomed = it }
                when {
                    item.kind == MediaKind.VIDEO && inlineVideoSupported -> {
                        MediaContextMenu(item.streamUrl ?: item.url, canSave = false) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                InlineVideo(
                                    url = item.streamUrl ?: item.url,
                                    thumbnailUrl = item.thumbnailUrl,
                                    autoPlay = true,
                                    muted = false,
                                    showControls = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = null,
                                )
                            }
                        }
                    }
                    item.kind == MediaKind.VIDEO -> {
                        // No inline player here (desktop): show the poster and offer the browser.
                        val uri = LocalUriHandler.current
                        MediaContextMenu(item.url, canSave = false) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ZoomableImage(item.thumbnailUrl ?: item.url, onZoomChange = onZoom, onDismiss = onClose)
                                FilledTonalButton(onClick = { uri.openUri(item.url) }) {
                                    Icon(Icons.Filled.PlayArrow, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play in browser")
                                }
                            }
                        }
                    }
                    else -> {
                        MediaContextMenu(item.url, canSave = true) {
                            ZoomableImage(item.url, onZoomChange = onZoom, onDismiss = onClose)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Action(Icons.Outlined.Close, "Close", tint = Color.White, onClick = onClose)
                Spacer(Modifier.weight(1f))
                if (media.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${media.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                if (onOpenSource != null) {
                    Action(
                        if (sourceIsThread) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.OpenInNew,
                        if (sourceIsThread) "Open discussion" else "Open source",
                        tint = Color.White,
                        onClick = onOpenSource,
                    )
                }
            }
            media[pager.currentPage].caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String, onZoomChange: (Boolean) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Downward drag distance while swiping to dismiss (only meaningful at 1x).
    var dismissDy by remember(url) { mutableStateOf(0f) }
    val zoomedIn = scale > 1f

    fun clamp(s: Float, o: Offset): Offset {
        val maxX = (size.width * (s - 1) / 2f).coerceAtLeast(0f)
        val maxY = (size.height * (s - 1) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun setScale(s: Float, pan: Offset) {
        val clamped = s.coerceIn(1f, 5f)
        scale = clamped
        offset = if (clamped == 1f) Offset.Zero else clamp(clamped, offset + pan)
        onZoomChange(clamped > 1f)
    }

    Box(
        modifier.fillMaxSize()
            .onSizeChanged { size = it }
            // Pinch to zoom, drag to pan once zoomed. At 1x a single-finger drag is left to the pager.
            .pointerInput(url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (scale > 1f || multiTouch) {
                            setScale(scale * zoom, pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // A single tap closes instantly (when not zoomed, so a tap while zoomed doesn't dismiss).
            // No onDoubleTap here: pairing it would delay onTap by the double-tap timeout. Zoom is pinch/wheel.
            .pointerInput(url) {
                detectTapGestures(onTap = { if (scale <= 1f) onDismiss() })
            }
            // Swipe down to dismiss, at 1x. Re-keyed on zoom so it never fights the pan gesture.
            .pointerInput(url, zoomedIn) {
                if (!zoomedIn) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, delta -> dismissDy = (dismissDy + delta).coerceAtLeast(0f); change.consume() },
                        onDragEnd = { if (dismissDy > size.height * 0.2f) onDismiss() else dismissDy = 0f },
                        onDragCancel = { dismissDy = 0f },
                    )
                }
            }
            // Desktop: mouse wheel zooms.
            .pointerInput(url) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) {
                                setScale(scale * if (dy < 0) 1.15f else 1f / 1.15f, Offset.Zero)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val dismissProgress = if (size.height > 0) (dismissDy / size.height).coerceIn(0f, 1f) else 0f
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y + dismissDy
                alpha = 1f - dismissProgress * 0.6f
            },
        )
    }
}
