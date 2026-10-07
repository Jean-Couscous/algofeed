package algofeed.util

/**
 * Parses the date formats found in feeds: RFC 822/1123 (RSS) and ISO 8601 / RFC 3339 (Atom, JSON APIs).
 * Returns epoch milliseconds, or null when the string is not recognised.
 */
object Dates {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val zones = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "EST" to -300, "EDT" to -240, "CST" to -360, "CDT" to -300,
        "MST" to -420, "MDT" to -360, "PST" to -480, "PDT" to -420,
        "CET" to 60, "CEST" to 120, "BST" to 60, "IST" to 330, "JST" to 540,
    )

    // [Day, ] DD Mon YYYY HH:MM[:SS] [zone]
    private val rfc822 = Regex(
        """^(?:[A-Za-z]+,?\s+)?(\d{1,2})\s+([A-Za-z]{3})[A-Za-z]*\.?\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([A-Za-z]+|[+-]\d{2}:?\d{2})?"""
    )

    // YYYY-MM-DD[THH:MM[:SS[.fff]]][zone]
    private val iso = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(Z|[+-]\d{2}:?\d{2})?"""
    )

    fun parse(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        iso.find(s)?.let { m ->
            val g = m.groupValues
            return epochMillis(
                g[1].toInt(), g[2].toInt(), g[3].toInt(),
                g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0,
                offsetMinutes(g[7]),
            )
        }
        rfc822.find(s)?.let { m ->
            val g = m.groupValues
            val month = months.indexOf(g[2].lowercase()) + 1
            if (month == 0) return null
            var year = g[3].toInt()
            if (year < 100) year += if (year < 70) 2000 else 1900
            return epochMillis(
                year, month, g[1].toInt(),
                g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0,
                offsetMinutes(g[7]),
            )
        }
        return null
    }

    private fun offsetMinutes(zone: String): Int {
        if (zone.isEmpty()) return 0
        zones[zone.uppercase()]?.let { return it }
        if (zone[0] != '+' && zone[0] != '-') return 0
        val digits = zone.substring(1).replace(":", "")
        if (digits.length != 4) return 0
        val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2).toInt()
        return if (zone[0] == '-') -minutes else minutes
    }

    fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, offsetMinutes: Int = 0): Long {
        val days = daysFromCivil(year, month, day)
        val seconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1000
    }

    // Howard Hinnant's days_from_civil.
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
