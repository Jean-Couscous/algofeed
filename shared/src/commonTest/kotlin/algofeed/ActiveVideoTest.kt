package algofeed

import algofeed.ui.VisibleVideo
import algofeed.ui.activeVideoKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveVideoTest {
    @Test fun straddlingCenterWins() {
        val videos = listOf(VisibleVideo("a", 0, 100), VisibleVideo("b", 200, 300))
        assertEquals("a", activeVideoKey(videos, viewportCenter = 50))
        assertEquals("b", activeVideoKey(videos, viewportCenter = 250))
    }

    @Test fun nearestWhenNoneStraddle() {
        val videos = listOf(VisibleVideo("a", 0, 100), VisibleVideo("b", 200, 300))
        // Center 140: nearer to a's center (50) than b's (250).
        assertEquals("a", activeVideoKey(videos, viewportCenter = 140))
        assertEquals("b", activeVideoKey(videos, viewportCenter = 170))
    }

    @Test fun emptyIsNull() {
        assertNull(activeVideoKey(emptyList<VisibleVideo<String>>(), viewportCenter = 100))
    }
}
