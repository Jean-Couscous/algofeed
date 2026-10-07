package algofeed.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Firefox's default wheel smooth scrolling (ScrollAnimationBezierPhysics, used when
 * `general.smoothScroll.msdPhysics.enabled` is off, as in release builds). Each wheel event moves
 * the destination; the content follows a cubic Bezier ease-out whose length tracks how fast events
 * arrive, and a new event starts its curve at the current velocity so the motion stays continuous.
 *
 * Positions are in pixels along the scroll axis, times in milliseconds.
 */
class WheelScrollAnimation {
    private var startPos = 0.0
    private var destination = 0.0
    private var startTime = 0.0
    private var duration = 0.0
    private val spline = KeySpline()
    private val prevEventTime = DoubleArray(3)
    private var firstIteration = true

    /** True from the first wheel event until the content reaches the destination. */
    var running = false
        private set

    /** Where the destination is; the caller tracks where the content actually is. */
    val target get() = destination

    /**
     * A wheel event of [delta] px. [now] is on the animation clock; [eventTime] is the event's own
     * timestamp, used only for the gaps between events. [current] is the content position when
     * nothing is animating.
     */
    fun add(delta: Double, now: Double, eventTime: Double, current: Double) {
        if (!running) {
            firstIteration = true
            startPos = current
            destination = current
            running = true
        }
        update(now, eventTime, destination + delta)
    }

    private fun update(now: Double, eventTime: Double, newDestination: Double) {
        if (firstIteration) initializeHistory(eventTime)
        val newDuration = computeDuration(eventTime)
        var velocity = 0.0
        if (!firstIteration) {
            velocity = velocityAt(now)
            startPos = positionAt(now)
        }
        startTime = now
        duration = newDuration
        destination = newDestination
        initTimingFunction(velocity)
        firstIteration = false
    }

    private fun initializeHistory(eventTime: Double) {
        val maxDelta = MAX_MS / INTERVAL_RATIO
        prevEventTime[0] = eventTime - maxDelta
        prevEventTime[1] = prevEventTime[0] - maxDelta
        prevEventTime[2] = prevEventTime[1] - maxDelta
    }

    /** Twice the average gap between the last three events, 50 to 200 ms. */
    private fun computeDuration(eventTime: Double): Double {
        val eventsDelta = ((eventTime - prevEventTime[2]) / 3).toInt()
        prevEventTime[2] = prevEventTime[1]
        prevEventTime[1] = prevEventTime[0]
        prevEventTime[0] = eventTime
        return (eventsDelta * INTERVAL_RATIO).toInt().coerceIn(MIN_MS.toInt(), MAX_MS.toInt()).toDouble()
    }

    private fun initTimingFunction(velocity: Double) {
        if (destination == startPos) {
            spline.init(0.0, 0.0, 1 - STOP_DECELERATION_WEIGHTING, 1.0)
            return
        }
        val slope = velocity * (duration / 1000) / (destination - startPos)
        val normalization = sqrt(1.0 + slope * slope)
        val dt = 1.0 / normalization * CURRENT_VELOCITY_WEIGHTING
        val dxy = slope / normalization * CURRENT_VELOCITY_WEIGHTING
        spline.init(dt, dxy, 1 - STOP_DECELERATION_WEIGHTING, 1.0)
    }

    private fun progressAt(now: Double) = ((now - startTime) / duration).coerceIn(0.0, 1.0)

    fun isFinished(now: Double) = now > startTime + duration

    fun positionAt(now: Double): Double {
        if (isFinished(now)) return destination
        val p = spline.value(progressAt(now))
        return (1 - p) * startPos + p * destination
    }

    /** Pixels per second. */
    private fun velocityAt(now: Double): Double {
        if (isFinished(now)) return 0.0
        val (dt, dxy) = spline.derivatives(progressAt(now))
        if (dt == 0.0) return 0.0
        return dxy / dt * (destination - startPos) / (duration / 1000)
    }

    /** The content stopped at [position] (an edge, or another scroll took over). */
    fun stop(position: Double) {
        running = false
        startPos = position
        destination = position
    }

    companion object {
        // Firefox defaults: general.smoothScroll.mouseWheel.durationMinMS / durationMaxMS,
        // durationToIntervalRatio / 100, currentVelocityWeighting, stopDecelerationWeighting.
        const val MIN_MS = 50.0
        const val MAX_MS = 200.0
        const val INTERVAL_RATIO = 2.0
        const val CURRENT_VELOCITY_WEIGHTING = 0.25
        const val STOP_DECELERATION_WEIGHTING = 0.4

        /** Firefox on Linux scrolls three lines per notch; a line of its default 16px text is about 19px. */
        const val LINES_PER_NOTCH = 3
        val LINE_HEIGHT = 19.dp
    }
}

/** A port of Firefox's SMILKeySpline: a CSS-style cubic Bezier timing function from (0,0) to (1,1). */
class KeySpline {
    private var x1 = 0.0
    private var y1 = 0.0
    private var x2 = 0.0
    private var y2 = 0.0
    private val samples = DoubleArray(TABLE_SIZE)

    fun init(x1: Double, y1: Double, x2: Double, y2: Double) {
        this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2
        if (x1 != y1 || x2 != y2) for (i in 0 until TABLE_SIZE) samples[i] = bezier(i * STEP, x1, x2)
    }

    fun value(x: Double): Double = if (x1 == y1 && x2 == y2) x else bezier(tForX(x), y1, y2)

    /** d(x)/dt and d(y)/dt at the point whose x is [x]. */
    fun derivatives(x: Double): Pair<Double, Double> {
        val t = tForX(x)
        return slope(t, x1, x2) to slope(t, y1, y2)
    }

    private fun tForX(x: Double): Double {
        if (x == 1.0) return 1.0
        var intervalStart = 0.0
        var i = 1
        while (i != TABLE_SIZE - 1 && samples[i] <= x) {
            intervalStart += STEP
            i++
        }
        i--
        val dist = (x - samples[i]) / (samples[i + 1] - samples[i])
        val guess = intervalStart + dist * STEP
        val initialSlope = slope(guess, x1, x2)
        return when {
            initialSlope >= NEWTON_MIN_SLOPE -> newton(x, guess)
            initialSlope == 0.0 -> guess
            else -> subdivide(x, intervalStart, intervalStart + STEP)
        }
    }

    private fun newton(x: Double, guess: Double): Double {
        var t = guess
        repeat(NEWTON_ITERATIONS) {
            val currentSlope = slope(t, x1, x2)
            if (currentSlope == 0.0) return t
            t -= (bezier(t, x1, x2) - x) / currentSlope
        }
        return t
    }

    private fun subdivide(x: Double, from: Double, to: Double): Double {
        var a = from
        var b = to
        var t: Double
        var current: Double
        var i = 0
        do {
            t = a + (b - a) / 2
            current = bezier(t, x1, x2) - x
            if (current > 0) b = t else a = t
        } while (abs(current) > SUBDIVISION_PRECISION && ++i < SUBDIVISION_MAX_ITERATIONS)
        return t
    }

    private companion object {
        const val TABLE_SIZE = 11
        const val STEP = 1.0 / (TABLE_SIZE - 1)
        const val NEWTON_ITERATIONS = 4
        const val NEWTON_MIN_SLOPE = 0.02
        const val SUBDIVISION_PRECISION = 0.0000001
        const val SUBDIVISION_MAX_ITERATIONS = 10

        fun a(a1: Double, a2: Double) = 1.0 - 3.0 * a2 + 3.0 * a1
        fun b(a1: Double, a2: Double) = 3.0 * a2 - 6.0 * a1
        fun c(a1: Double) = 3.0 * a1
        fun bezier(t: Double, a1: Double, a2: Double) = ((a(a1, a2) * t + b(a1, a2)) * t + c(a1)) * t
        fun slope(t: Double, a1: Double, a2: Double) = 3.0 * a(a1, a2) * t * t + 2.0 * b(a1, a2) * t + c(a1)
    }
}

/** Firefox-style smooth mouse-wheel scrolling for [state]; off on touch hosts. Shift+wheel is left to Compose. */
@Composable
fun Modifier.smoothWheelScroll(state: ScrollableState): Modifier {
    if (LocalTouchUi.current) return this
    val animation = remember { WheelScrollAnimation() }
    // Wheel events waiting for the next frame: (pixels, event time in ms).
    val pending = remember { ArrayDeque<Pair<Double, Double>>() }
    val scope = rememberCoroutineScope()
    val job = remember { arrayOfNulls<Job>(1) }
    return pointerInput(state) {
        val notchPx = WheelScrollAnimation.LINES_PER_NOTCH * WheelScrollAnimation.LINE_HEIGHT.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll || event.keyboardModifiers.isShiftPressed) continue
                val change = event.changes.firstOrNull() ?: continue
                val notches = change.scrollDelta.y
                if (notches == 0f) continue
                change.consume()
                pending.addLast(notches * notchPx.toDouble() to change.uptimeMillis.toDouble())
                if (job[0]?.isActive == true) continue
                job[0] = scope.launch {
                    // One scroll session per animation: a keyboard jump or drag started meanwhile cancels it.
                    var applied = 0.0
                    try {
                        state.scroll {
                            while (true) {
                                val now = withFrameNanos { it } / 1_000_000.0
                                while (pending.isNotEmpty()) {
                                    val (delta, eventTime) = pending.removeFirst()
                                    animation.add(delta, now, eventTime, current = applied)
                                }
                                if (!animation.running) break
                                val step = (animation.positionAt(now) - applied).toFloat()
                                if (step != 0f) {
                                    val used = scrollBy(step)
                                    applied += used
                                    // An edge: stop where the content is instead of pushing on.
                                    if (abs(used) < abs(step) * 0.5f) animation.stop(applied)
                                }
                                if (animation.isFinished(now) && pending.isEmpty()) animation.stop(applied)
                            }
                        }
                    } finally {
                        pending.clear()
                        animation.stop(applied)
                    }
                }
            }
        }
    }
}

/** Middle-click autoscroll and smooth wheel scrolling, for every scrollable pane on desktop. */
@Composable
fun Modifier.mouseScrolling(state: ScrollableState): Modifier = middleClickAutoScroll(state).smoothWheelScroll(state)
