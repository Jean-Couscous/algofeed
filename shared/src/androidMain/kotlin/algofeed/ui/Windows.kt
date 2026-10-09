package algofeed.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat

actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

// Right-click context menus are a desktop concept; Android uses long-press menus elsewhere.
@Composable
actual fun StyledContextMenus(content: @Composable () -> Unit) = content()

@Composable
actual fun MediaContextMenu(url: String, canSave: Boolean, content: @Composable () -> Unit) = content()

@Composable
actual fun SystemBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

actual val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? {
    if (!dynamicColorSupported) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
