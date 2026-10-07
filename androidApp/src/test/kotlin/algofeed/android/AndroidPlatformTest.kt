package algofeed.android

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Answers every launch at once with [answer], as the system picker would after the user chose. */
private class FakeRegistry(private val answer: () -> Uri?) : ActivityResultRegistry() {
    val launched = mutableListOf<Any?>()

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launched += input
        @Suppress("UNCHECKED_CAST")
        dispatchResult(requestCode, answer() as O)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidPlatformTest {
    private val dir = Files.createTempDirectory("opml").toFile()
    private val platform = AndroidPlatform(ApplicationProvider.getApplicationContext())

    @Before fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun cleanup() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun attach(registry: ActivityResultRegistry) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        platform.attach(controller.get(), registry)
        controller.start().resume()
    }

    @Test fun importReadsTheChosenFile() = runTest {
        val file = File(dir, "subs.opml").apply { writeText("<opml version=\"2.0\"/>") }
        val registry = FakeRegistry { Uri.fromFile(file) }
        attach(registry)
        assertEquals("<opml version=\"2.0\"/>", platform.pickOpml())
        assertEquals(listOf("*/*"), (registry.launched.single() as Array<*>).toList())
    }

    @Test fun exportWritesTheChosenFile() = runTest {
        val file = File(dir, "out.opml").apply { writeText("old content that is longer") }
        val registry = FakeRegistry { Uri.fromFile(file) }
        attach(registry)
        assertTrue(platform.saveOpml("<opml/>"))
        assertEquals("<opml/>", file.readText())
        assertEquals("algofeed-subscriptions.opml", registry.launched.single())
    }

    @Test fun cancelledPickers() = runTest {
        attach(FakeRegistry { null })
        assertNull(platform.pickOpml())
        assertFalse(platform.saveOpml("<opml/>"))
    }

    @Test fun nothingAttached() = runTest {
        assertNull(platform.pickOpml())
        assertFalse(platform.saveOpml("<opml/>"))
    }
}
