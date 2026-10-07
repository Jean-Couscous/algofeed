package algofeed.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import kotlin.math.abs
import kotlin.math.sign

/**
 * Browser-style middle-click autoscroll. A middle click drops an anchor and the view scrolls
 * toward the pointer, faster the further it is; any click or wheel turn stops it. Holding the
 * middle button and dragging scrolls until the button is released.
 */
class AutoScrollGesture {
    /** Snapshot state, so only starting and stopping recompose or redraw; pointer moves don't. */
    var anchor: Offset? by mutableStateOf(null)
        private set
    var pointer: Offset = Offset.Zero
        private set
    private var dragged = false

    val active get() = anchor != null

    /** Returns true when the event belongs to the gesture and should be consumed. */
    fun press(position: Offset, middle: Boolean): Boolean {
        if (active) {
            anchor = null
            return true
        }
        if (!middle) return false
        anchor = position
        pointer = position
        dragged = false
        return true
    }

    fun move(position: Offset) {
        val a = anchor ?: return
        pointer = position
        if ((position - a).getDistance() > DEAD_ZONE) dragged = true
    }

    /** Releasing the middle button after a drag ends the scroll; after a plain click the anchor stays. */
    fun release(middle: Boolean) {
        if (middle && dragged) anchor = null
    }

    fun stop() {
        anchor = null
    }

    /** Pixels per second for the current pointer position; positive scrolls down. */
    fun velocity(): Float {
        val a = anchor ?: return 0f
        return speedFor(pointer.y - a.y)
    }

    companion object {
        const val DEAD_ZONE = 10f
        private const val MAX_SPEED = 8000f

        fun speedFor(distance: Float): Float {
            val beyond = abs(distance) - DEAD_ZONE
            if (beyond <= 0f) return 0f
            // Gentle near the anchor, quick further out, like Firefox.
            return sign(distance) * (beyond * 6f + beyond * beyond * 0.04f).coerceAtMost(MAX_SPEED)
        }
    }
}

/** Middle-click autoscroll for [state]; off on touch hosts, where there is no middle button. */
@Composable
fun Modifier.middleClickAutoScroll(state: ScrollableState): Modifier {
    if (LocalTouchUi.current) return this
    val gesture = remember { AutoScrollGesture() }
    val active = gesture.active
    val color = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (gesture.active) {
            val now = withFrameNanos { it }
            val delta = gesture.velocity() * (now - last) / 1_000_000_000f
            last = now
            if (delta != 0f) state.scrollBy(delta)
        }
    }

    return this
        .pointerInput(state) {
            awaitPointerEventScope {
                var middleDown = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val position = event.changes.firstOrNull()?.position ?: continue
                    val middle = event.buttons.isTertiaryPressed
                    val handled = when (event.type) {
                        PointerEventType.Press -> gesture.press(position, middle = middle && !middleDown)
                        PointerEventType.Move -> { gesture.move(position); false }
                        PointerEventType.Release -> {
                            val middleReleased = middleDown && !middle
                            val wasActive = gesture.active
                            gesture.release(middleReleased)
                            middleReleased && wasActive
                        }
                        PointerEventType.Scroll -> { gesture.stop(); false }
                        else -> false
                    }
                    middleDown = middle
                    if (handled) event.changes.forEach { it.consume() }
                }
            }
        }
        .drawWithContent {
            drawContent()
            gesture.anchor?.let { drawAnchor(it, color, background) }
        }
}

/** The anchor marker: a ring with arrows up and down, as browsers draw it. */
private fun DrawScope.drawAnchor(at: Offset, color: Color, background: Color) {
    val r = 14f * density
    drawCircle(background.copy(alpha = 0.9f), r, at)
    drawCircle(color.copy(alpha = 0.6f), r, at, style = Stroke(1.5f * density))
    val w = 4f * density
    val h = 4f * density
    val gap = 4f * density
    for (dir in listOf(-1f, 1f)) {
        val tipY = at.y + dir * (gap + h)
        val baseY = at.y + dir * gap
        drawPath(
            Path().apply {
                moveTo(at.x, tipY)
                lineTo(at.x - w, baseY)
                lineTo(at.x + w, baseY)
                close()
            },
            color.copy(alpha = 0.8f),
        )
    }
    drawCircle(color.copy(alpha = 0.8f), 1.5f * density, at)
}
