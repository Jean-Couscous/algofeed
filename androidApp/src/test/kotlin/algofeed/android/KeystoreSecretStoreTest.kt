package algofeed.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

// The Android Keystore isn't available on the JVM, so these tests use a software AES key.
class KeystoreSecretStoreTest {
    private val dir = Files.createTempDirectory("secrets").toFile()
    private val file = File(dir, "secrets.preferences_pb")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun dataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()).also { scopes += it },
    ) { file }

    @AfterTest fun cleanup() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    @Test fun roundTripAndClear() = runTest {
        val store = KeystoreSecretStore(dataStore()) { key }
        assertNull(store.hnSession())
        store.setHnSession(" someone&abc123 ")
        assertEquals("someone&abc123", store.hnSession())
        assertFalse("abc123" in file.readBytes().decodeToString(), "session stored in plain text")
        store.setHnSession("")
        assertNull(store.hnSession())
    }

    @Test fun readsBackAfterRestart() = runTest {
        val ds = dataStore()
        KeystoreSecretStore(ds) { key }.setHnSession("someone&1")
        assertEquals("someone&1", KeystoreSecretStore(ds) { key }.hnSession())
    }

    @Test fun otherKeyReadsAsMissing() = runTest {
        val ds = dataStore()
        KeystoreSecretStore(ds) { key }.setHnSession("someone&1")
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(KeystoreSecretStore(ds) { other }.hnSession())
    }

    @Test fun legacyApiKeyIsDeleted() = runTest {
        val ds = dataStore()
        val legacy = stringPreferencesKey("openrouter_api_key")
        ds.edit { it[legacy] = "ciphertext" }
        KeystoreSecretStore(ds) { key }.hnSession()
        assertFalse(legacy in ds.data.first())
    }
}
