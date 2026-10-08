package algofeed.ui

import algofeed.fetch.USER_AGENT
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import okhttp3.OkHttpClient

actual val inlineVideoSupported = true

@OptIn(UnstableApi::class)
@Composable
actual fun InlineVideo(
    url: String,
    thumbnailUrl: String?,
    autoPlay: Boolean,
    muted: Boolean,
    showControls: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val player = remember {
        val sources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(OkHttpClient()).setUserAgent(USER_AGENT))
        ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            // Short clips loop when they autoplay muted in the feed; the reader plays once.
            repeatMode = if (muted) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    var firstFrame by remember(url) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(url) { player.setMediaItem(MediaItem.fromUri(url)); player.prepare() }
    LaunchedEffect(autoPlay) { player.playWhenReady = autoPlay }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                Lifecycle.Event.ON_START -> player.playWhenReady = autoPlay
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = showControls
                }
            },
            update = { it.useController = showControls },
            modifier = Modifier.fillMaxSize(),
        )
        // A poster covers the black surface until the first frame is drawn.
        if (!firstFrame && thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Without its own controls (muted feed cards), a tap opens the reader.
        if (onClick != null && !showControls) {
            Box(Modifier.fillMaxSize().clickable(onClick = onClick))
        }
    }
}
