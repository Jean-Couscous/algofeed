package algofeed

import algofeed.rank.OnnxEmbedder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Loads the real multilingual-e5-small model when it has been fetched (`:shared:prepareEmbeddingAssets`),
 * and checks the Kotlin tokenizer + ONNX path produce a sane, cross-lingual embedding space. Skipped
 * when the model asset is absent, so it does not require the download in every environment.
 */
class EmbedderSmokeTest {
    private val dir = File("build/embedding")

    @Test fun crossLingualSimilarityBeatsUnrelated() = runTest {
        val model = File(dir, "model_quantized.onnx")
        val vocab = File(dir, "tokenizer.vocab")
        if (!model.exists() || !vocab.exists()) return@runTest

        val embedder = vocab.useLines { OnnxEmbedder.create(model.absolutePath, it) }
        embedder.use {
            val v = it.embed(
                listOf(
                    "query: The new graphics card delivers excellent gaming performance",
                    "query: La nouvelle carte graphique offre d'excellentes performances de jeu",
                    "query: A recipe for chocolate chip cookies with walnuts",
                )
            )
            assertEquals(384, v[0].size)
            val related = dot(v[0], v[1]) // English vs French, same topic
            val unrelated = dot(v[0], v[2]) // English vs unrelated topic
            assertTrue(related > 0.6, "cross-lingual similarity too low: $related")
            assertTrue(related > unrelated + 0.1, "related=$related should clearly beat unrelated=$unrelated")
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var d = 0.0
        for (i in a.indices) d += a[i].toDouble() * b[i]
        return d
    }
}
