package algofeed.desktop

import algofeed.SecretStore
import algofeed.data.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [SecretStore] backed by the freedesktop Secret Service through the `secret-tool` CLI (libsecret),
 * which both GNOME Keyring and KWallet provide. Each secret is a Secret Service item identified by
 * the attributes {service=algofeed, key=<key>}. [createOrNull] returns null when no usable Secret
 * Service is reachable, letting the app fall back to the database.
 */
class SecretServiceStore private constructor() : SecretStore {

    override suspend fun secret(key: String): String? = withContext(Dispatchers.IO) {
        val (code, out) = runTool("lookup", "service", SERVICE, "key", key)
        if (code == 0) out.ifEmpty { null } else null
    }

    override suspend fun setSecret(key: String, value: String) {
        val trimmed = value.trim()
        withContext(Dispatchers.IO) {
            if (trimmed.isEmpty()) {
                runTool("clear", "service", SERVICE, "key", key)
            } else {
                runTool("store", "--label=$SERVICE: $key", "service", SERVICE, "key", key, input = trimmed)
            }
        }
    }

    companion object {
        private const val SERVICE = "algofeed"
        private const val TOOL = "secret-tool"

        /** A usable store, or null when `secret-tool` is missing or no Secret Service answers. */
        fun createOrNull(): SecretServiceStore? = try {
            // A working service exits 0 (found) or 1 (not found) for a lookup; a missing tool throws,
            // and an unreachable service exits with some other error code.
            val (code, _) = exec(listOf(TOOL, "lookup", "service", SERVICE, "key", "__probe__"))
            if (code == 0 || code == 1) SecretServiceStore() else null
        } catch (_: Exception) {
            null
        }

        private fun runTool(vararg args: String, input: String? = null): Pair<Int, String> =
            exec(listOf(TOOL) + args, input)

        private fun exec(command: List<String>, input: String? = null): Pair<Int, String> {
            val process = ProcessBuilder(command).start()
            if (input != null) process.outputStream.use { it.write(input.toByteArray()) } else process.outputStream.close()
            // secret-tool's output is small; read it fully (to EOF) before waiting on the exit.
            val out = process.inputStream.readBytes().decodeToString().removeSuffix("\n")
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return -1 to ""
            }
            return process.exitValue() to out
        }
    }
}

/**
 * Moves any secrets still stored as plaintext rows in the settings table into [store], then deletes
 * those rows. Run once at startup when a keyring is available; a no-op after everything has moved.
 */
suspend fun migratePlaintextSecrets(db: AppDatabase, store: SecretStore) {
    val keys = listOf(
        SecretStore.HN_SESSION_KEY,
        SecretStore.TUMBLR_API_KEY,
        SecretStore.MANGADEX_CLIENT_ID,
        SecretStore.MANGADEX_CLIENT_SECRET,
        SecretStore.MANGADEX_REFRESH_TOKEN,
        SecretStore.MANGADEX_USER,
    )
    for (key in keys) {
        val plain = db.settings().get(key)?.ifBlank { null } ?: continue
        if (store.secret(key) == null) store.setSecret(key, plain)
        db.settings().delete(key)
    }
}
