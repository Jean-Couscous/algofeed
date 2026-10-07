package algofeed

import algofeed.ui.KeySpline
import algofeed.ui.WheelScrollAnimation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmoothWheelTest {
    private val notch = 57.0

    @Test fun easeOutSpline() {
        val s = KeySpline().apply { init(0.0, 0.0, 0.6, 1.0) }
        assertEquals(0.0, s.value(0.0), 1e-6)
        assertEquals(1.0, s.value(1.0), 1e-6)
        assertTrue(s.value(0.5) > 0.6, "ease-out is past halfway at half time: ${s.value(0.5)}")
        val linear = KeySpline().apply { init(0.3, 0.3, 0.7, 0.7) }
        assertEquals(0.42, linear.value(0.42), 1e-9)
    }

    @Test fun oneNotchTakesFirefoxsMaximum200ms() {
        val a = WheelScrollAnimation()
        a.add(notch, now = 0.0, eventTime = 1000.0, current = 0.0)
        assertTrue(a.positionAt(100.0) in notch / 2..notch)
        assertFalse(a.isFinished(199.0))
        assertTrue(a.isFinished(201.0))
        assertEquals(notch, a.positionAt(201.0))
        val path = (0..200 step 8).map { a.positionAt(it.toDouble()) }
        assertTrue(path.zipWithNext().all { (x, y) -> y >= x }, "never moves back")
    }

    @Test fun fastNotchesShortenTheAnimationDownTo50ms() {
        val a = WheelScrollAnimation()
        for (i in 0..3) a.add(notch, now = i * 16.0, eventTime = 1000.0 + i * 16, current = 0.0)
        assertEquals(4 * notch, a.target)
        // Gaps of 16 ms average out to 16 ms after three events: 2 x 16 = 32, clamped to 50.
        assertTrue(a.isFinished(48.0 + 51))
        assertEquals(4 * notch, a.positionAt(100.0))
    }

    @Test fun aNewNotchContinuesFromTheCurrentPosition() {
        val a = WheelScrollAnimation()
        a.add(notch, now = 0.0, eventTime = 1000.0, current = 0.0)
        val before = a.positionAt(60.0)
        a.add(notch, now = 60.0, eventTime = 1060.0, current = 0.0)
        assertEquals(before, a.positionAt(60.0), 1e-6)
        assertTrue(a.positionAt(76.0) > before, "keeps moving forward right away")
    }

    @Test fun reversingRetargets() {
        val a = WheelScrollAnimation()
        a.add(notch, now = 0.0, eventTime = 1000.0, current = 0.0)
        a.add(-notch, now = 30.0, eventTime = 1030.0, current = 0.0)
        assertEquals(0.0, a.target)
    }
}
