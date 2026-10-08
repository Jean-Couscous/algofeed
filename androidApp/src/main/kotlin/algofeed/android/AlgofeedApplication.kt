package algofeed.android

import algofeed.Repository
import algofeed.Settings
import algofeed.data.AppDatabase
import algofeed.data.buildAlgofeed
import algofeed.fetch.HackerNews
import algofeed.fetch.createHttpClient
import algofeed.fetch.defaultSources
import algofeed.reader.createReaderExtractor
import android.app.Application
import androidx.room.Room
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
        val client = createHttpClient()
        val secretStore = KeystoreSecretStore(secretsDataStore)
        val secrets = algofeed.SecretReader { secretStore.secret(it) }
        Repository(
            db, defaultSources(client, secrets), createReaderExtractor(client),
            secrets = secretStore,
            // Hourly by default to spare the battery; the user can lower it to WorkManager's 15 minutes.
            defaults = Settings(refreshMinutes = 60),
            hackerNews = HackerNews(client),
            mangadexAuth = algofeed.fetch.MangadexAuth(client, secrets),
        )
    }

    override suspend fun refreshInBackground() {
        repository.refreshAll()
        TopEntriesWidget.update(this)
    }
}
