package algofeed.rank

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs multilingual-e5-small with ONNX Runtime to produce L2-normalized sentence embeddings. The
 * same code serves desktop and Android: the two artifacts expose an identical `ai.onnxruntime` API,
 * and each platform resolves the model/vocab files before constructing this.
 */
class OnnxEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: SpmTokenizer,
    override val dim: Int,
) : Embedder, AutoCloseable {

    private val lock = Mutex()
    private val outputName = session.outputNames.first()
    // Some e5 ONNX exports keep a (zero) token_type_ids input even though XLM-R never uses it.
    private val needsTokenTypes = "token_type_ids" in session.inputNames

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        texts.map { embedOne(it) }
    }

    private suspend fun embedOne(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val mask = LongArray(ids.size) { 1L }
        val vector = lock.withLock {
            val tensors = ArrayList<OnnxTensor>(3)
            try {
                val inputs = HashMap<String, OnnxTensor>(3)
                inputs["input_ids"] = OnnxTensor.createTensor(env, arrayOf(ids)).also { tensors += it }
                inputs["attention_mask"] = OnnxTensor.createTensor(env, arrayOf(mask)).also { tensors += it }
                if (needsTokenTypes) {
                    inputs["token_type_ids"] = OnnxTensor.createTensor(env, arrayOf(LongArray(ids.size))).also { tensors += it }
                }
                session.run(inputs).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = (result[outputName].get().value as Array<Array<FloatArray>>)[0]
                    meanPool(hidden)
                }
            } finally {
                tensors.forEach { it.close() }
            }
        }
        return l2Normalize(vector)
    }

    private fun meanPool(tokens: Array<FloatArray>): FloatArray {
        val out = FloatArray(tokens[0].size)
        for (token in tokens) for (i in out.indices) out[i] += token[i]
        val n = tokens.size.toFloat()
        for (i in out.indices) out[i] /= n
        return out
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val inv = if (norm == 0.0) 0f else (1.0 / sqrt(norm)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** multilingual-e5-small hidden size. */
        const val DIM = 384

        fun create(modelPath: String, vocabLines: Sequence<String>, dim: Int = DIM): OnnxEmbedder {
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelPath, OrtSession.SessionOptions())
            return OnnxEmbedder(env, session, SpmTokenizer.fromLines(vocabLines), dim)
        }
    }
}
