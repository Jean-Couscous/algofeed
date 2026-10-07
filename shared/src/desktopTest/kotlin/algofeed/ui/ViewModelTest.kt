package algofeed.ui

import algofeed.BackgroundRefresh
import algofeed.Fixtures
import algofeed.Repository
import algofeed.Settings
import algofeed.data.AppDatabase
import algofeed.data.buildAlgofeed
import algofeed.fetch.defaultSources
import algofeed.reader.Article
import algofeed.reader.ReaderExtractor
import androidx.room.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildAlgofeed()
    private var extractions = 0
    private val extractor = object : ReaderExtractor {
        override suspend fun extract(url: String) = Article(null, null, "").also { extractions++ }
    }
    private val repo = Repository(db, defaultSources(Fixtures.client(mapOf("https://blog.example/feed" to Fixtures.rss))), extractor)
    private val noTimer = object : BackgroundRefresh {
        override val minIntervalMinutes = 15
        override fun schedule(settings: Settings) = Unit
    }

    @AfterTest fun close() {
        Dispatchers.resetMain()
        db.close()
    }

    // A reload cancels the one before it. On a fresh install the first refresh finishes at once and
    // reloads while the initial load is still querying; that cancellation used to show as
    // "Could not load entries: StandaloneCoroutine was cancelled".
    @Test fun supersededReloadIsNotAnError() = runBlocking {
        repo.addFeed("https://blog.example/feed")
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = AlgofeedViewModel(repo, noTimer)
        repeat(5) { vm.reload() }
        withTimeout(10_000) { vm.state.first { !it.loading && !it.refreshing && it.items.isNotEmpty() } }
        delay(300) // let the cancelled loads finish unwinding
        assertNull(vm.state.value.message)
    }

    @Test fun feedSetToFeedVersionSkipsExtraction() = runBlocking {
        val feed = repo.addFeed("https://blog.example/feed")
        repo.updateFeed(feed.copy(preferFeedVersion = true))
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = AlgofeedViewModel(repo, noTimer)
        val item = withTimeout(10_000) { vm.state.first { !it.loading && it.items.isNotEmpty() } }.items.first()
        withTimeout(10_000) { vm.feeds.first { it.singleOrNull()?.preferFeedVersion == true } }
        vm.open(item.entry)
        assertTrue(vm.state.value.reader!!.showingFeedVersion)
        delay(200)
        assertEquals(0, extractions)
    }
}
