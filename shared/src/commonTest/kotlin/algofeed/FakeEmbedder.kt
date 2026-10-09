package algofeed

import algofeed.rank.Embedder
import kotlin.math.sqrt

/**
 * Deterministic stand-in for the real model: hashes word tokens into a fixed-width vector, so texts
 * that share words land close together. Enough to exercise content ranking and profile learning
 * without loading ONNX.
 */
class FakeEmbedder(override val dim: Int = 64) : Embedder {
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { vectorFor(it) }

    fun vectorFor(text: String): FloatArray {
        val v = FloatArray(dim)
        for (token in text.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (token.isNotEmpty()) v[(token.hashCode() and Int.MAX_VALUE) % dim] += 1f
        }
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        if (norm == 0.0) { v[0] = 1f; return v }
        val inv = (1.0 / sqrt(norm)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }
}
