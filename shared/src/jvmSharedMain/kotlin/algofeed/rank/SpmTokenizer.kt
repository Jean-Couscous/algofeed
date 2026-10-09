package algofeed.rank

import java.text.Normalizer

/**
 * XLM-RoBERTa SentencePiece Unigram tokenizer, enough of it to feed the e5 model: NFKC normalize,
 * metaspace pre-tokenization (spaces become `▁`, one is prepended), Viterbi best-segmentation over
 * the vocabulary, then the `<s>…</s>` wrapping. The Precompiled normalizer of the reference is
 * approximated by NFKC, which is close enough for ranking cosine similarity.
 *
 * [vocab] is the model's piece list in id order (`piece\tlogScore`), parsed by [fromLines].
 */
class SpmTokenizer private constructor(
    private val pieceToId: HashMap<String, Int>,
    private val scores: FloatArray,
    private val maxPieceLen: Int,
    private val unkScore: Float,
) {
    fun encode(text: String, maxTokens: Int = 512): LongArray {
        val ids = viterbi(metaspace(normalize(text)))
        val budget = (maxTokens - 2).coerceAtLeast(0)
        val kept = if (ids.size > budget) ids.subList(0, budget) else ids
        val out = LongArray(kept.size + 2)
        out[0] = BOS
        for (i in kept.indices) out[i + 1] = kept[i].toLong()
        out[out.size - 1] = EOS
        return out
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replace(WHITESPACE, " ")

    private fun metaspace(text: String): String =
        if (text.isEmpty()) "" else META + text.replace(" ", META)

    /** Maximum-score segmentation; unmatched characters fall back to a single `<unk>`. */
    private fun viterbi(s: String): List<Int> {
        if (s.isEmpty()) return emptyList()
        val n = s.length
        val best = DoubleArray(n + 1) { if (it == 0) 0.0 else Double.NEGATIVE_INFINITY }
        val backStart = IntArray(n + 1) { -1 }
        val backId = IntArray(n + 1) { -1 }
        for (i in 1..n) {
            val from = maxOf(0, i - maxPieceLen)
            for (j in from until i) {
                if (best[j] == Double.NEGATIVE_INFINITY) continue
                val id = pieceToId[s.substring(j, i)]
                if (id != null) {
                    val cand = best[j] + scores[id]
                    if (cand > best[i]) { best[i] = cand; backStart[i] = j; backId[i] = id }
                }
            }
            // Single-character <unk> fallback, so there is always a path.
            val unkCand = best[i - 1] + unkScore
            if (unkCand > best[i]) { best[i] = unkCand; backStart[i] = i - 1; backId[i] = UNK.toInt() }
        }
        val rev = ArrayList<Int>()
        var i = n
        while (i > 0) {
            rev += backId[i]
            i = backStart[i]
        }
        rev.reverse()
        return rev
    }

    companion object {
        const val BOS = 0L
        const val PAD = 1L
        const val EOS = 2L
        const val UNK = 3L
        private const val META = "▁"
        private val WHITESPACE = Regex("\\s+")
        private val SPECIAL_IDS = setOf(0, 1, 2, 3)

        fun fromLines(lines: Sequence<String>): SpmTokenizer {
            val pieces = ArrayList<String>(250_000)
            val scoreList = ArrayList<Float>(250_000)
            for (line in lines) {
                val tab = line.lastIndexOf('\t')
                if (tab < 0) { pieces += line; scoreList += 0f; continue }
                pieces += line.substring(0, tab)
                scoreList += line.substring(tab + 1).toFloatOrNull() ?: 0f
            }
            val scores = FloatArray(scoreList.size) { scoreList[it] }
            val pieceToId = HashMap<String, Int>(pieces.size * 4 / 3)
            var maxLen = 1
            var minScore = 0f
            for (id in pieces.indices) {
                if (id in SPECIAL_IDS) continue
                val piece = pieces[id]
                pieceToId[piece] = id
                if (piece.length > maxLen) maxLen = piece.length
                if (scores[id] < minScore) minScore = scores[id]
            }
            return SpmTokenizer(pieceToId, scores, maxLen, minScore - 10f)
        }
    }
}
