package algofeed.desktop

import algofeed.Repository
import algofeed.Settings
import algofeed.StreamView
import algofeed.data.AppDatabase
import algofeed.data.buildAlgofeed
import algofeed.fetch.RateLimiter
import algofeed.fetch.createHttpClient
import algofeed.fetch.defaultSources
import algofeed.rateLimitStore
import algofeed.reader.createReaderExtractor
import algofeed.ui.AlgofeedApp
import algofeed.ui.AlgofeedViewModel
import algofeed.ui.PlatformActions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat

/**
 * Development tool: subscribes a scratch database to the given sources (if it has none yet),
 * prints the ranked home stream, and renders the UI offscreen to PNG files.
 *
 * Usage: screenshot <db file> <output dir> [source...]
 */
fun main(args: Array<String>): Unit = runBlocking {
    val dbFile = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    val db = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath).buildAlgofeed()
    val rateLimiter = RateLimiter(rateLimitStore(db))
    val client = createHttpClient(rateLimiter)
    val repo = Repository(
        db, defaultSources(client), createReaderExtractor(client),
        rateLimiter = rateLimiter,
    )

    // ALGOFEED_TRACE_REFRESH=1: run one full refresh, print each progress step with timings, and exit.
    if (System.getenv("ALGOFEED_TRACE_REFRESH") != null) {
        val t0 = System.currentTimeMillis()
        val result = runCatching {
            repo.refreshAll(minIntervalMs = 0) { p -> println("+${System.currentTimeMillis() - t0}ms ${p.done}/${p.total}") }
        }
        println("+${System.currentTimeMillis() - t0}ms returned: $result")
        Runtime.getRuntime().halt(0)
    }

    if (db.feeds().all().isEmpty()) {
        for (source in args.drop(2)) {
            val result = runCatching { repo.addFeed(source) }
            println("add $source -> ${result.fold({ "${it.title} [${it.type}] bucket=${it.bucket}" }, { "FAILED ${it.message}" })}")
        }
    }
    val feeds = db.feeds().all().associateBy { it.id }
    fun printStream(label: String, stream: List<algofeed.rank.Ranked>) {
        println("== $label: ${stream.size} entries")
        stream.take(15).forEach {
            val b = it.breakdown
            println("%6.2f  r=%.2f f=%.2f s=%.2f c=%.2f  %-14s %-55s %s".format(
                it.score, b.recency, b.rarity, b.source, b.content,
                feeds[it.entry.feedId]?.title?.take(14), (it.entry.title ?: "(untitled)").take(55), b.topTerms.joinToString("|")))
        }
    }
    printStream("home", repo.stream(StreamView.Home, repo.settings()))

    val platform = object : PlatformActions {
        override suspend fun pickOpml(): String? = null
        override suspend fun saveOpml(content: String) = false
    }
    val vm = withContext(Dispatchers.Main) { AlgofeedViewModel(repo) }

    suspend fun shoot(name: String, width: Int, height: Int, settle: Long = 4000, before: () -> Unit = {}) {
        withContext(Dispatchers.Main) {
            ImageComposeScene(width, height, Density(1f)) { AlgofeedApp(vm, platform) }.use { scene ->
                before()
                var t = 0L
                val until = System.currentTimeMillis() + settle
                while (System.currentTimeMillis() < until) {
                    scene.render(t)
                    t += 16_000_000
                    delay(50)
                }
                val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "$name.png").writeBytes(png)
                println("wrote $name.png")
            }
        }
    }

    withContext(Dispatchers.Main) {
        ImageComposeScene(256, 256, Density(1f)) {
            androidx.compose.foundation.Image(algofeed.ui.AppIcon, null, androidx.compose.ui.Modifier.fillMaxSize())
        }.use { File(out, "icon.png").writeBytes(it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        println("wrote icon.png")
    }

    // ALGOFEED_WATCH_STATE=<seconds>: print refresh/progress state changes for that long, then exit.
    System.getenv("ALGOFEED_WATCH_STATE")?.toLongOrNull()?.let { seconds ->
        val t0 = System.currentTimeMillis()
        val job = launch(Dispatchers.Main) {
            vm.state.collect { st -> println("+${System.currentTimeMillis() - t0}ms refreshing=${st.refreshing} progress=${st.progress} loading=${st.loading} items=${st.items.size}") }
        }
        delay(seconds * 1000)
        job.cancel()
        Runtime.getRuntime().halt(0)
    }
    // ALGOFEED_PROGRESS_SHOT=1: capture the header while the initial refresh is still running.
    if (System.getenv("ALGOFEED_PROGRESS_SHOT") != null) shoot("progress", 1320, 400, settle = 1200)
    delay(3000) // let the initial refresh finish
    shoot("wide", 1320, 860)
    shoot("narrow", 400, 820)
    val first = vm.state.value.items.firstOrNull { it.entry.title != null && it.entry.url != null }?.entry
    if (first != null) {
        shoot("reader", 1320, 860, settle = 8000) { vm.open(first) }
        shoot("reader-narrow", 400, 820, settle = 3000)
    }
    vm.closeReader()

    withContext(Dispatchers.Main) { vm.show(StreamView.Media) }
    delay(1500)
    shoot("media", 1320, 860, settle = 3000)

    db.close()
    Runtime.getRuntime().halt(0)
}
