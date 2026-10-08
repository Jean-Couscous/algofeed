package algofeed.ui

import androidx.compose.ui.window.DialogProperties

actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

@androidx.compose.runtime.Composable
actual fun SystemBarIcons(darkTheme: Boolean) = Unit

actual val dynamicColorSupported = false

@androidx.compose.runtime.Composable
actual fun dynamicColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme? = null

actual val inlineVideoSupported = false

@androidx.compose.runtime.Composable
actual fun InlineVideo(
    url: String,
    thumbnailUrl: String?,
    autoPlay: Boolean,
    muted: Boolean,
    showControls: Boolean,
    modifier: androidx.compose.ui.Modifier,
    onClick: (() -> Unit)?,
) = Unit
