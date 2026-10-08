package algofeed.ui

/** Things the shared UI asks the host platform to do. */
interface PlatformActions {
    /** Lets the user choose an OPML file; returns its contents or null if cancelled. */
    suspend fun pickOpml(): String?

    /** Lets the user choose where to save [content]; returns false if cancelled. */
    suspend fun saveOpml(content: String): Boolean

    /** Lets the user choose a backup file to import; returns its contents or null if cancelled. */
    suspend fun pickBackup(): String? = null

    /** Lets the user save a backup named [suggestedName]; returns false if cancelled. */
    suspend fun saveBackup(suggestedName: String, content: String): Boolean = false
}

/** Touch-first host (phones, tablets): 48dp targets, pull to refresh, and on narrow screens horizontal swipes open and close the drawer. */
val LocalTouchUi = androidx.compose.runtime.staticCompositionLocalOf { false }

/** A dialog window that covers the screen, drawing behind the system bars where the platform has them. */
expect fun fullScreenDialogProperties(): androidx.compose.ui.window.DialogProperties

/** Makes the status and navigation bar icons of the window this is composed in readable on the app's background. */
@androidx.compose.runtime.Composable
expect fun SystemBarIcons(darkTheme: Boolean)

/** Whether [dynamicColorScheme] can return colors on this device. */
expect val dynamicColorSupported: Boolean

/** The system's wallpaper-based scheme (Android 12+), or null where there is none. */
@androidx.compose.runtime.Composable
expect fun dynamicColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme?

/** Whether [InlineVideo] plays here; false falls back to opening the video in the browser. */
expect val inlineVideoSupported: Boolean

/**
 * Plays a video inline. [url] is the direct file; [streamUrl]-style muxed streams are passed as [url]
 * by the caller. [onClick] (when set) handles taps instead of the player's own controls.
 */
@androidx.compose.runtime.Composable
expect fun InlineVideo(
    url: String,
    thumbnailUrl: String?,
    autoPlay: Boolean,
    muted: Boolean,
    showControls: Boolean,
    modifier: androidx.compose.ui.Modifier,
    onClick: (() -> Unit)?,
)
