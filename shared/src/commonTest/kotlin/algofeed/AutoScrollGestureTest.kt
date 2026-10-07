package algofeed

import algofeed.ui.AutoScrollGesture
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoScrollGestureTest {
    @Test fun clickAnchorsUntilNextClick() {
        val g = AutoScrollGesture()
        assertTrue(g.press(Offset(100f, 100f), middle = true))
        g.release(middle = true)
        assertTrue(g.active, "a plain middle click keeps scrolling")
        g.move(Offset(100f, 300f))
        assertTrue(g.velocity() > 0, "pointer below the anchor scrolls down")
        g.move(Offset(100f, 20f))
        assertTrue(g.velocity() < 0, "pointer above the anchor scrolls up")
        assertTrue(g.press(Offset(100f, 20f), middle = false), "any click stops it and is swallowed")
        assertFalse(g.active)
    }

    @Test fun dragScrollsWhileHeld() {
        val g = AutoScrollGesture()
        g.press(Offset(0f, 0f), middle = true)
        g.move(Offset(0f, 200f))
        g.release(middle = true)
        assertFalse(g.active)
    }

    @Test fun otherButtonsAreLeftAlone() {
        val g = AutoScrollGesture()
        assertFalse(g.press(Offset.Zero, middle = false))
        assertFalse(g.active)
    }

    @Test fun deadZoneAndSpeedGrowWithDistance() {
        assertEquals(0f, AutoScrollGesture.speedFor(AutoScrollGesture.DEAD_ZONE))
        val near = AutoScrollGesture.speedFor(40f)
        val far = AutoScrollGesture.speedFor(200f)
        assertTrue(far > near * 2)
        assertEquals(-far, AutoScrollGesture.speedFor(-200f))
    }
}
