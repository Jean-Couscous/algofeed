package algofeed.ui

import algofeed.ThemeMode
import algofeed.resources.Res
import algofeed.resources.figtree_bold
import algofeed.resources.figtree_medium
import algofeed.resources.figtree_regular
import algofeed.resources.literata_bold
import algofeed.resources.literata_italic
import algofeed.resources.literata_regular
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font

/** Colors the Material scheme has no slot for. */
@Immutable
data class ExtraColors(
    val like: Color,
    val selection: Color,
    val divider: Color,
)

val LocalExtraColors = staticCompositionLocalOf { lightExtra }
val LocalReadingFont = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }

private val lightScheme = lightColorScheme(
    primary = Color(0xFF2B5C8A),
    onPrimary = Color(0xFFF5F8FB),
    primaryContainer = Color(0xFFDCE7F2),
    onPrimaryContainer = Color(0xFF15324D),
    secondary = Color(0xFF4F5B66),
    onSecondary = Color(0xFFF5F7F9),
    secondaryContainer = Color(0xFFE3E7EB),
    onSecondaryContainer = Color(0xFF1D2329),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF1D2329),
    surface = Color(0xFFF4F5F7),
    onSurface = Color(0xFF1D2329),
    surfaceVariant = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFF55606A),
    surfaceContainerLowest = Color(0xFFFBFCFD),
    surfaceContainerLow = Color(0xFFEFF1F3),
    surfaceContainer = Color(0xFFEAEDF0),
    surfaceContainerHigh = Color(0xFFE4E7EA),
    surfaceContainerHighest = Color(0xFFDEE2E6),
    outline = Color(0xFF8A949D),
    outlineVariant = Color(0xFFD5DAE0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFF8F7),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF9CC2E6),
    onPrimary = Color(0xFF0F2B44),
    primaryContainer = Color(0xFF24435F),
    onPrimaryContainer = Color(0xFFD6E5F3),
    secondary = Color(0xFFB4BEC8),
    onSecondary = Color(0xFF1F262C),
    secondaryContainer = Color(0xFF2E363E),
    onSecondaryContainer = Color(0xFFDDE3E9),
    background = Color(0xFF15191D),
    onBackground = Color(0xFFE2E6EA),
    surface = Color(0xFF15191D),
    onSurface = Color(0xFFE2E6EA),
    surfaceVariant = Color(0xFF262C32),
    onSurfaceVariant = Color(0xFFA2ACB6),
    surfaceContainerLowest = Color(0xFF111417),
    surfaceContainerLow = Color(0xFF1A1F23),
    surfaceContainer = Color(0xFF1E2328),
    surfaceContainerHigh = Color(0xFF242A30),
    surfaceContainerHighest = Color(0xFF2B3238),
    outline = Color(0xFF6E7881),
    outlineVariant = Color(0xFF323A41),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF5C1311),
)

private val lightExtra = ExtraColors(
    like = Color(0xFFC2405A),
    selection = Color(0xFFE2EAF3),
    divider = Color(0xFFDCE0E5),
)

private val darkExtra = ExtraColors(
    like = Color(0xFFEC9AAE),
    selection = Color(0xFF1F2E3C),
    divider = Color(0xFF2A3137),
)

/** Whether [AlgofeedTheme] chose dark colors, which can differ from the system setting. */
val LocalDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun AlgofeedTheme(mode: ThemeMode, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val ui = FontFamily(
        Font(Res.font.figtree_regular, FontWeight.Normal),
        Font(Res.font.figtree_medium, FontWeight.Medium),
        Font(Res.font.figtree_bold, FontWeight.Bold),
    )
    val reading = FontFamily(
        Font(Res.font.literata_regular, FontWeight.Normal),
        Font(Res.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.literata_bold, FontWeight.Bold),
    )
    val base = Typography()
    fun TextStyle.ui() = copy(fontFamily = ui)
    val typography = Typography(
        displaySmall = base.displaySmall.ui(),
        headlineSmall = base.headlineSmall.ui().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
        titleLarge = base.titleLarge.ui().copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = base.titleMedium.ui().copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = base.titleSmall.ui().copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = base.bodyLarge.ui().copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.ui().copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.ui().copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = base.labelLarge.ui().copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.ui(),
        labelSmall = base.labelSmall.ui().copy(fontSize = 12.sp),
    )
    val dynamic = if (dynamicColor) dynamicColorScheme(dark) else null
    val extra = if (dark) darkExtra else lightExtra
    SystemBarIcons(dark)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalExtraColors provides if (dynamic == null) extra else extra.copy(selection = dynamic.secondaryContainer),
        LocalReadingFont provides reading,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(colorScheme = dynamic ?: if (dark) darkScheme else lightScheme, typography = typography, content = content)
    }
}
