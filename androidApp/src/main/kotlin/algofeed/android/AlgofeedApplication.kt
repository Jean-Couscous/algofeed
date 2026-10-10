package algofeed.android

import algofeed.Repository
import algofeed.Settings
import algofeed.data.AppDatabase
import algofeed.data.buildAlgofeed
import algofeed.fetch.HackerNews
import algofeed.fetch.RateLimiter
import algofeed.fetch.createHttpClient
import algofeed.fetch.defaultSources
import algofeed.rateLimitStore
import algofeed.rank.DisabledEmbedder
import algofeed.rank.Embedder
import algofeed.rank.OnnxEmbedder
import algofeed.reader.createReaderExtractor
import android.app.Application
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.MainScope

class AlgofeedApplication : Application(), RefreshHost {
    /** For work that must finish after an Activity is gone, such as saving viewed entries. */
    val scope = MainScope()

    val platform by lazy { AndroidPlatform(this) }

    val backgroundRefresh by lazy { WorkManagerRefresh(this) }

    /** Set when the app goes to the background, so the next start can catch up. Survives rotation. */
    var inBackground = false

    val repository: Repository by lazy {
        val db = Room.databaseBuilder<AppDatabase>(this, getDatabasePath("algofeed.db").absolutePath).buildAlgofeed()
        val rateLimiter = RateLimiter(rateLimitStore(db))
        val client = createHttpClient(rateLimiter)
        val secretStore = KeystoreSecretStore(secretsDataStore)
        val secrets = algofeed.SecretReader { secretStore.secret(it) }
        Repository(
            db, defaultSources(client, secrets), createReaderExtractor(client),
            secrets = secretStore,
            // Hourly by default to spare the battery; the user can lower it to WorkManager's 15 minutes.
            defaults = Settings(refreshMinutes = 60),
            hackerNews = HackerNews(client),
            mangadexAuth = algofeed.fetch.MangadexAuth(client, secrets),
            embedder = loadEmbedder(),
            rateLimiter = rateLimiter,
        )
    }

    /** Copies the bundled model out of the APK once, then loads it for memory-mapped inference. */
    private fun loadEmbedder(): Embedder = runCatching {
        val model = File(filesDir, "model_quantized.onnx")
        if (!model.exists()) assets.open("model_quantized.onnx").use { i -> model.outputStream().use { i.copyTo(it) } }
        assets.open("tokenizer.vocab").bufferedReader().useLines { lines ->
            OnnxEmbedder.create(model.absolutePath, lines)
        }
    }.getOrElse { DisabledEmbedder }

    override suspend fun refreshInBackground() {
        repository.refreshAll()
        TopEntriesWidget.update(this)
    }
}
