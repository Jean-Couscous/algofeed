package algofeed

import algofeed.ui.CardImageShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardImageShapeTest {
    @Test fun portraitsSitInA4by3BoxWithFilledSides() {
        assertEquals(4f / 3f, CardImageShape.ratio(0.7f))
        assertTrue(CardImageShape.letterboxed(0.7f))
        assertTrue(CardImageShape.letterboxed(1f), "squares too")
    }

    @Test fun landscapesFillTheWidthAtTheirOwnShape() {
        assertEquals(16f / 9f, CardImageShape.ratio(16f / 9f))
        assertEquals(3f, CardImageShape.ratio(3f))
        assertFalse(CardImageShape.letterboxed(16f / 9f))
    }

    @Test fun loadingUsesTheBoxWithoutBands() {
        assertEquals(4f / 3f, CardImageShape.ratio(null))
        assertFalse(CardImageShape.letterboxed(null))
    }
}
