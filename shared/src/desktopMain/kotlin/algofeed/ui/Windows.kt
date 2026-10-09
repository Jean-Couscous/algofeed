package algofeed.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DarkDefaultContextMenuRepresentation
import androidx.compose.foundation.LightDefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogProperties
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

@Composable
actual fun StyledContextMenus(content: @Composable () -> Unit) {
    // The built-in default is light-only; pick the one matching the app's theme so it isn't jarring in dark mode.
    val representation = if (LocalDarkTheme.current) DarkDefaultContextMenuRepresentation else LightDefaultContextMenuRepresentation
    CompositionLocalProvider(LocalContextMenuRepresentation provides representation, content = content)
}

@Composable
actual fun MediaContextMenu(url: String, canSave: Boolean, content: @Composable () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    ContextMenuArea(
        items = {
            buildList {
                add(ContextMenuItem("Open in browser") { uri.openUri(url) })
                add(ContextMenuItem("Copy link") { clipboard.setText(AnnotatedString(url)) })
                if (canSave) add(ContextMenuItem("Save image…") { saveToFile(url) })
            }
        },
        content = content,
    )
}

/** Picks a destination, then downloads [url] off the UI thread. */
private fun saveToFile(url: String) {
    val suggested = url.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
    val dialog = FileDialog(null as Frame?, "Save image", FileDialog.SAVE).apply {
        file = suggested
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return
    Thread {
        runCatching {
            val conn = java.net.URI(url).toURL().openConnection().apply {
                setRequestProperty("User-Agent", USER_AGENT_DESKTOP)
            }
            conn.getInputStream().use { input -> File(dir, name).outputStream().use(input::copyTo) }
        }
    }.start()
}

private const val USER_AGENT_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) Algofeed"

@Composable
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
