/*
 * The web feed icon, redrawn from assets/feed-icon.svg (originally made for Mozilla Firefox).
 * This file is available under the Mozilla Public License 1.1: https://www.mozilla.org/MPL/1.1/
 * See assets/README.md.
 */
package algofeed.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Algofeed's logo: the standard feed icon, on a 256-unit canvas like the source SVG. */
val AppIcon: ImageVector by lazy {
    // The SVG gradient runs from 8.5% to 91.5% of the inner square (x = y = 10, size 236).
    val gradient = Brush.linearGradient(
        0.0f to Color(0xFFE3702D),
        0.1071f to Color(0xFFEA7D31),
        0.3503f to Color(0xFFF69537),
        0.5f to Color(0xFFFB9E3A),
        0.7016f to Color(0xFFEA7C31),
        0.8866f to Color(0xFFDE642B),
        1.0f to Color(0xFFD95B29),
        start = Offset(10 + 0.085f * 236, 10 + 0.085f * 236),
        end = Offset(10 + 0.915f * 236, 10 + 0.915f * 236),
    )
    val white = SolidColor(Color.White)
    ImageVector.Builder("Algofeed", 48.dp, 48.dp, 256f, 256f).apply {
        addPath(addPathNodes(roundedSquare(0f, 256f, 55f)), fill = SolidColor(Color(0xFFCC5D15)))
        addPath(addPathNodes(roundedSquare(5f, 246f, 50f)), fill = SolidColor(Color(0xFFF49C52)))
        addPath(addPathNodes(roundedSquare(10f, 236f, 47f)), fill = gradient)
        addPath(addPathNodes("M44 189a24 24 0 1 0 48 0a24 24 0 1 0 -48 0z"), fill = white)
        addPath(addPathNodes("M160 213h-34a82 82 0 0 0 -82 -82v-34a116 116 0 0 1 116 116z"), fill = white)
        addPath(addPathNodes("M184 213A140 140 0 0 0 44 73V38a175 175 0 0 1 175 175z"), fill = white)
    }.build()
}

/** SVG path for a square at ([at], [at]) with side [size] and corner radius [r]. */
private fun roundedSquare(at: Float, size: Float, r: Float): String {
    val s = size - 2 * r
    return "M${at + r} ${at}h${s}a$r $r 0 0 1 $r ${r}v${s}a$r $r 0 0 1 -$r ${r}h-${s}a$r $r 0 0 1 -$r -${r}v-${s}a$r $r 0 0 1 $r -${r}z"
}
