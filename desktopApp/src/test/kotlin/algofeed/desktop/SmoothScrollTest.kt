package algofeed.desktop

import algofeed.ui.smoothWheelScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class SmoothScrollTest {
    private val state = LazyListState()
    private val scene = ImageComposeScene(400, 900, Density(1f)) {
        LazyColumn(Modifier.fillMaxSize().smoothWheelScroll(state), state = state) {
            items(500) { Text("Row $it", Modifier.height(10.dp)) }
        }
    }
    private var time = 0L

    private fun position() = state.firstVisibleItemIndex * 10 + state.firstVisibleItemScrollOffset

    private fun track(frames: Int) = List(frames) { time += 16_000_000; scene.render(time); position() }

    private fun wheel(notches: Float) = scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 200f), scrollDelta = Offset(0f, notches))

    @AfterTest fun close() = scene.close()

    @Test fun oneNotchScrolls57pxOver200msLikeFirefox() {
        track(2)
        wheel(1f)
        val path = track(30)
        // 3 lines of 19 px: Compose's own handler, if it also ran, would add its 30 px step.
        assertEquals(57, path.last())
        val done = path.indexOfFirst { it == 57 }
        assertTrue(done in 10..15, "done after ${done + 1} frames of 16 ms: $path")
        // From rest Firefox's curve is cubic-bezier(0.25, 0, 0.6, 1): a short ease-in, then a long ease-out.
        assertTrue(path[6] > 57 / 2, "past halfway by mid-animation: $path")
        assertTrue(path.zipWithNext().all { (a, b) -> b >= a }, "moves one way: $path")
    }

    @Test fun stopsAtTheTop() {
        track(2)
        wheel(-5f)
        assertEquals(List(15) { 0 }, track(15))
        wheel(1f)
        assertEquals(57, track(30).last(), "a scroll into the top edge leaves nothing behind")
    }
}
