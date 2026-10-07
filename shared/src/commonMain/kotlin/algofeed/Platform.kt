package algofeed

/** Keeps the Hacker News session outside the database. */
interface SecretStore {
    suspend fun hnSession(): String?

    /** A blank [session] removes it. */
    suspend fun setHnSession(session: String)
}

/** Refreshes feeds while the app isn't running. When a host provides one, the view model doesn't run its own timer. */
interface BackgroundRefresh {
    /** Shortest interval the scheduler supports. */
    val minIntervalMinutes: Int

    /** Uses [Settings.refreshMinutes] and the refresh constraints. */
    fun schedule(settings: Settings)
}
