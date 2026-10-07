package algofeed.rank

/**
 * Turns text into stemmed terms for the content model. Deliberately simple: lowercase, split on
 * non-letters, drop stopwords and short tokens, strip common English/French suffixes.
 */
object Tokenizer {
    private val stopwords = """
        a about above after again against all also am an and any are aren as at be because been before being
        below between both but by can could did do does doing down during each few for from further had has
        have having he her here hers herself him himself his how however into is isn it its itself just let
        like make many may me more most much must my myself new no nor not now of off on once one only or
        other our ours ourselves out over own same she should so some such than that the their theirs them
        themselves then there these they this those through to too under until up very via was way we were
        what when where which while who whom why will with within without would you your yours yourself
        yourselves get got use used using says said year years time today first two three via http https www
        com html amp nbsp quot read more comments link submitted points
        alors au aucun aussi autre avant avec avoir bon car ce cela ces ceux chaque ci comme comment dans des
        du dedans dehors depuis devrait doit donc dos début elle elles en encore est et étaient état été être
        eu fait faites fois font hors ici il ils je juste la le les leur là ma maintenant mais mes mine moins
        mon mot même ni nommés notre nous ou où par parce pas peut peu plupart pour pourquoi quand que quel
        quelle quelles quels qui sa sans ses seulement si sien son sont sous soyez sur ta tandis tellement tels
        tes ton tous tout toute toutes très tu une un voient vont votre vous vu ça sont cette leurs entre
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(splitter)
            .asSequence()
            .filter { it.length >= 3 && it !in stopwords && !it.all(Char::isDigit) }
            .map(::stem)
            .filter { it.length >= 3 }
            .toList()

    /** Term frequency normalised by the most frequent term, so long texts don't dominate. */
    fun termFrequencies(text: String): Map<String, Float> {
        val counts = tokenize(text).groupingBy { it }.eachCount()
        val max = counts.values.maxOrNull() ?: return emptyMap()
        return counts.mapValues { (_, c) -> 0.5f + 0.5f * c / max }
    }

    fun stem(word: String): String {
        if (word.length <= 4) return word
        for ((suffix, replacement) in suffixes) {
            if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
                return word.dropLast(suffix.length) + replacement
            }
        }
        return word
    }

    private val suffixes = listOf(
        "ational" to "ate", "ization" to "ize", "ements" to "", "ement" to "", "ations" to "", "ation" to "",
        "ingly" to "", "ings" to "", "ing" to "", "ies" to "y", "ied" to "y", "ness" to "", "ment" to "",
        "edly" to "", "ed" to "", "es" to "", "ly" to "", "s" to "",
    )
}
