package algofeed.rank

/**
 * Turns text into a dense sentence embedding. Implementations return L2-normalized vectors of
 * length [dim], so cosine similarity is a plain dot product. The content signal in [Ranker] is
 * built on these; the JVM/Android implementation runs a multilingual model on-device.
 */
interface Embedder {
    val dim: Int

    /** One L2-normalized vector per input, in order. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** Stands in when no model is available (first run before download, tests that don't rank content). */
object DisabledEmbedder : Embedder {
    override val dim = 0
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(0) }
}

/** Little-endian float32 packing so embeddings live in a BLOB column and in JSON backups. */
object Vectors {
    fun toBytes(vector: FloatArray): ByteArray {
        val out = ByteArray(vector.size * 4)
        for (i in vector.indices) {
            val bits = vector[i].toRawBits()
            val o = i * 4
            out[o] = bits.toByte()
            out[o + 1] = (bits ushr 8).toByte()
            out[o + 2] = (bits ushr 16).toByte()
            out[o + 3] = (bits ushr 24).toByte()
        }
        return out
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            val o = i * 4
            val bits = (bytes[o].toInt() and 0xFF) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }
}
