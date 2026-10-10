package algofeed.desktop

import algofeed.data.Entry
import algofeed.rank.Breakdown
import algofeed.rank.Ranked
import algofeed.ui.EntryList
import algofeed.ui.middleClickAutoScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class AutoScrollTest {
    private val state = LazyListState()
    private val scene = ImageComposeScene(400, 600, Density(1f)) {
        LazyColumn(Modifier.fillMaxSize().middleClickAutoScroll(state), state = state) {
            items(500) { Text("Row $it", Modifier.height(40.dp)) }
        }
    }
    private var time = 0L

    private fun frames(n: Int) = repeat(n) {
        time += 16_000_000
        scene.render(time)
    }

    private fun middle(type: PointerEventType, at: Offset, pressed: Boolean) = scene.sendPointerEvent(
        type, at, buttons = PointerButtons(isTertiaryPressed = pressed), button = PointerButton.Tertiary,
    )

    @Test fun middleClickScrollsTowardThePointerAndClickStops() {
        frames(2)
        middle(PointerEventType.Press, Offset(200f, 200f), pressed = true)
        middle(PointerEventType.Release, Offset(200f, 200f), pressed = false)
        scene.sendPointerEvent(PointerEventType.Move, Offset(200f, 450f))
        frames(60)
        val scrolled = state.firstVisibleItemIndex
        assertTrue(scrolled > 5, "scrolled to row $scrolled")

        scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 450f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 450f), button = PointerButton.Primary)
        frames(2)
        val stoppedAt = state.firstVisibleItemIndex
        frames(30)
        assertEquals(stoppedAt, state.firstVisibleItemIndex, "a click stops autoscroll")
    }

    @Test fun pointerAboveScrollsUp() {
        state.requestScrollToItem(200)
        frames(2)
        middle(PointerEventType.Press, Offset(200f, 400f), pressed = true)
        middle(PointerEventType.Release, Offset(200f, 400f), pressed = false)
        scene.sendPointerEvent(PointerEventType.Move, Offset(200f, 100f))
        frames(60)
        assertTrue(state.firstVisibleItemIndex < 195, "at row ${state.firstVisibleItemIndex}")
    }

    // The wide stream centres its rows; the margins beside them must still scroll the list.
    @Test fun marginsBesideCentredRowsScroll() {
        val list = LazyListState()
        val entries = (1L..300L).map {
            Ranked(Entry(id = it, feedId = 1, remoteId = "$it", url = null, title = "Entry $it", sortDate = 0, fetchedAt = 0), 0.0, Breakdown())
        }
        val wide = ImageComposeScene(1200, 600, Density(1f)) {
            EntryList(
                items = entries, feeds = emptyMap(), listState = list, focused = -1, openId = null, showWhy = false,
                onOpen = {}, onExternal = {}, onLike = {}, onBookmark = {}, onDismiss = {},
                modifier = Modifier.fillMaxSize(), maxItemWidth = 400.dp,
            )
        }
        var t = 0L
        fun frames(n: Int) = repeat(n) { t += 16_000_000; wide.render(t) }
        frames(2)
        val margin = Offset(60f, 200f)
        wide.sendPointerEvent(PointerEventType.Scroll, margin, scrollDelta = Offset(0f, 3f))
        frames(20)
        val afterWheel = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        assertTrue(afterWheel != (0 to 0), "the wheel scrolls from the margin")

        wide.sendPointerEvent(PointerEventType.Press, margin, buttons = PointerButtons(isTertiaryPressed = true), button = PointerButton.Tertiary)
        wide.sendPointerEvent(PointerEventType.Release, margin, button = PointerButton.Tertiary)
        wide.sendPointerEvent(PointerEventType.Move, Offset(60f, 500f))
        frames(60)
        assertTrue(list.firstVisibleItemIndex > afterWheel.first + 5, "middle-click autoscroll works from the margin")
        wide.close()
    }
}
