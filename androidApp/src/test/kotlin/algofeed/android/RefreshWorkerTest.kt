package algofeed.android

import algofeed.Settings
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeRefreshApp : Application(), RefreshHost {
    var calls = 0
    var error: Exception? = null

    override suspend fun refreshInBackground() {
        calls++
        error?.let { throw it }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = FakeRefreshApp::class)
class RefreshWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<FakeRefreshApp>()

    @Test fun refreshes() = runTest {
        assertEquals(Result.success(), TestListenableWorkerBuilder<RefreshWorker>(app).build().doWork())
        assertEquals(1, app.calls)
    }

    @Test fun retriesThenGivesUp() = runTest {
        app.error = IOException("offline")
        assertEquals(Result.retry(), TestListenableWorkerBuilder<RefreshWorker>(app).build().doWork())
        assertEquals(Result.failure(), TestListenableWorkerBuilder<RefreshWorker>(app).setRunAttemptCount(3).build().doWork())
    }

    @Test fun requestFollowsSettings() {
        val refresh = WorkManagerRefresh(app)
        val plain = refresh.request(Settings(refreshMinutes = 60)).workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(60), plain.intervalDuration)
        assertEquals(NetworkType.CONNECTED, plain.constraints.requiredNetworkType)
        assertTrue(!plain.constraints.requiresCharging())

        val strict = refresh.request(Settings(refreshMinutes = 5, refreshUnmeteredOnly = true, refreshWhileChargingOnly = true)).workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(15), strict.intervalDuration)
        assertEquals(NetworkType.UNMETERED, strict.constraints.requiredNetworkType)
        assertTrue(strict.constraints.requiresCharging())
    }
}
