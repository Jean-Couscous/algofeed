package algofeed.desktop

import algofeed.Repository
import algofeed.data.AppDatabase
import algofeed.data.buildAlgofeed
import algofeed.fetch.HackerNews
import algofeed.fetch.createHttpClient
import algofeed.fetch.defaultSources
import algofeed.rank.DisabledEmbedder
import algofeed.rank.Embedder
import algofeed.rank.OnnxEmbedder
import algofeed.reader.createReaderExtractor
import algofeed.ui.AlgofeedApp
import algofeed.ui.AlgofeedViewModel
import algofeed.ui.AppIcon
import algofeed.ui.PlatformActions
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.room.Room
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private fun dataDir(): File = File(dataHome(), "algofeed").apply { mkdirs() }

/** The model lives in the app's bundled resources; ALGOFEED_MODEL_DIR overrides it for development. */
private fun loadEmbedder(): Embedder {
    val dir = System.getenv("ALGOFEED_MODEL_DIR")
        ?: System.getProperty("compose.application.resources.dir")
        ?: return DisabledEmbedder
    val model = File(dir, "model_quantized.onnx")
    val vocab = File(dir, "tokenizer.vocab")
    if (!model.exists() || !vocab.exists()) return DisabledEmbedder
    return runCatching {
        vocab.useLines { lines -> OnnxEmbedder.create(model.absolutePath, lines) }
    }.getOrElse { DisabledEmbedder }
}

private class DesktopPlatform(private val frame: () -> Frame?) : PlatformActions {
    override suspend fun pickOpml() = load("Import OPML") { name -> name.endsWith(".opml", true) || name.endsWith(".xml", true) }

    override suspend fun saveOpml(content: String) = save("Export OPML", "algofeed-subscriptions.opml", content)

    override suspend fun pickBackup() = load("Import backup") { name -> name.endsWith(".json", true) }

    override suspend fun saveBackup(suggestedName: String, content: String) = save("Export backup", suggestedName, content)

    private suspend fun load(title: String, filter: (String) -> Boolean): String? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(frame(), title, FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> filter(name) }
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        withContext(Dispatchers.IO) { File(dialog.directory, name).readText() }
    }

    private suspend fun save(title: String, suggestedName: String, content: String): Boolean = withContext(Dispatchers.Main) {
        val dialog = FileDialog(frame(), title, FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val name = dialog.file ?: return@withContext false
        withContext(Dispatchers.IO) { File(dialog.directory, name).writeText(content) }
        true
    }
}

fun main() {
    migrateLegacyData(dataHome(), configHome())
    val dbFile = System.getenv("ALGOFEED_DB")?.let(::File) ?: File(dataDir(), "algofeed.db")
    val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath).buildAlgofeed()
    val client = createHttpClient()
    // Store secrets in the OS keyring via the Secret Service (GNOME Keyring / KWallet) when one is
    // reachable, moving any left in the settings table into it; otherwise keep the settings table.
    val secretStore = SecretServiceStore.createOrNull()
    if (secretStore != null) runBlocking { migratePlaintextSecrets(db, secretStore) }
    val secrets = algofeed.SecretReader { secretStore?.secret(it) ?: db.settings().get(it)?.ifBlank { null } }
    val repo = Repository(
        db, defaultSources(client, secrets), createReaderExtractor(client),
        secrets = secretStore,
        hackerNews = HackerNews(client),
        mangadexAuth = algofeed.fetch.MangadexAuth(client, secrets),
        embedder = loadEmbedder(),
    )

    application {
        val vm = androidx.compose.runtime.remember { AlgofeedViewModel(repo) }
        var frame: Frame? = null
        val platform = androidx.compose.runtime.remember { DesktopPlatform { frame } }
        Window(
            onCloseRequest = {
                runBlocking { vm.persistPending() }
                db.close()
                exitApplication()
            },
            title = "Algofeed",
            icon = rememberVectorPainter(AppIcon),
            state = rememberWindowState(width = 1320.dp, height = 860.dp),
        ) {
            frame = window
            AlgofeedApp(vm, platform)
        }
    }
}
