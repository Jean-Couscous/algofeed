package algofeed.android

import algofeed.BackgroundRefresh
import algofeed.Settings
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Implemented by the Application, so tests can give the worker a fake. */
interface RefreshHost {
    suspend fun refreshInBackground()
}

/** Fetches all feeds. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            (applicationContext as RefreshHost).refreshInBackground()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

class WorkManagerRefresh(private val context: Context) : BackgroundRefresh {
    /** WorkManager's floor for periodic work. */
    override val minIntervalMinutes = 15

    override fun schedule(settings: Settings) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request(settings))
    }

    fun request(settings: Settings): PeriodicWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.refreshUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.refreshWhileChargingOnly)
            .build()
        return PeriodicWorkRequestBuilder<RefreshWorker>(
            settings.refreshMinutes.coerceAtLeast(minIntervalMinutes).toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }

    companion object {
        const val WORK_NAME = "refresh"
    }
}
