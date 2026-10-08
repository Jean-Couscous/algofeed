package algofeed

/** Keeps secrets (session cookies, API keys) outside the database. */
interface SecretStore {
    suspend fun secret(key: String): String?

    /** A blank [value] removes the secret. */
    suspend fun setSecret(key: String, value: String)

    suspend fun hnSession(): String? = secret(HN_SESSION_KEY)

    /** A blank [session] removes it. */
    suspend fun setHnSession(session: String) = setSecret(HN_SESSION_KEY, session)

    companion object {
        const val HN_SESSION_KEY = "hn.session"
        const val TUMBLR_API_KEY = "tumblr.apiKey"
    }
}

/** Reads a stored secret by key; given to source adapters that need a user-provided key. */
fun interface SecretReader {
    suspend fun get(key: String): String?
}

/** Refreshes feeds while the app isn't running. When a host provides one, the view model doesn't run its own timer. */
interface BackgroundRefresh {
    /** Shortest interval the scheduler supports. */
    val minIntervalMinutes: Int

    /** Uses [Settings.refreshMinutes] and the refresh constraints. */
    fun schedule(settings: Settings)
}
