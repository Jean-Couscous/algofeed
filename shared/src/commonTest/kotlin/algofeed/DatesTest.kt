package algofeed

import algofeed.util.Dates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatesTest {
    private val expected = 1_791_288_000_000L // 2026-10-06T12:00:00Z

    @Test fun rfc822() {
        assertEquals(expected, Dates.parse("Tue, 06 Oct 2026 12:00:00 GMT"))
        assertEquals(expected, Dates.parse("Tue, 06 Oct 2026 14:00:00 +0200"))
        assertEquals(expected, Dates.parse("6 Oct 2026 08:00:00 EDT"))
        assertEquals(expected, Dates.parse("Tue, 06 Oct 26 12:00 Z"))
    }

    @Test fun iso8601() {
        assertEquals(expected, Dates.parse("2026-10-06T12:00:00Z"))
        assertEquals(expected, Dates.parse("2026-10-06T12:00:00.123Z"))
        assertEquals(expected, Dates.parse("2026-10-06T14:00:00+02:00"))
        assertEquals(expected - 12 * 3_600_000L, Dates.parse("2026-10-06"))
    }

    @Test fun garbage() {
        assertNull(Dates.parse("yesterday"))
        assertNull(Dates.parse(null))
    }
}
