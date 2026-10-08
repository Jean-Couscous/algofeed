package algofeed.android

import algofeed.SecretStore
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stored at `files/datastore/secrets.preferences_pb`, which the backup rules leave out. */
val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Keeps secrets AES-GCM encrypted in DataStore, with the AES key held by the Android Keystore.
 * Settings are read often, so decrypted values are cached in memory.
 */
class KeystoreSecretStore(
    private val store: DataStore<Preferences>,
    private val secretKey: () -> SecretKey = ::keystoreKey,
) : SecretStore {
    private val mutex = Mutex()
    private val cache = mutableMapOf<Preferences.Key<String>, String?>()

    override suspend fun secret(key: String) = get(prefKey(key))

    override suspend fun setSecret(key: String, value: String) = set(prefKey(key), value)

    // The HN session kept its original "hn_session" pref name so existing logins survive the upgrade.
    private fun prefKey(key: String): Preferences.Key<String> =
        if (key == SecretStore.HN_SESSION_KEY) HN_SESSION else stringPreferencesKey(key)

    private suspend fun get(key: Preferences.Key<String>): String? = mutex.withLock {
        forgetLegacyApiKey()
        if (key !in cache) cache[key] = store.data.first()[key]?.let(::decryptOrNull)
        cache[key]
    }

    private suspend fun set(key: Preferences.Key<String>, raw: String) = mutex.withLock {
        val value = raw.trim().ifEmpty { null }
        if (key in cache && value == cache[key]) return@withLock
        store.edit { prefs -> if (value == null) prefs.remove(key) else prefs[key] = encrypt(value) }
        cache[key] = value
    }

    private var legacyChecked = false

    /** The OpenRouter key from the removed AI ranking; deleted once so it doesn't linger on the device. */
    private suspend fun forgetLegacyApiKey() {
        if (legacyChecked) return
        if (LEGACY_API_KEY in store.data.first()) store.edit { it.remove(LEGACY_API_KEY) }
        legacyChecked = true
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    /** Null when the Keystore key changed or is gone (for example after a restore to another device). */
    private fun decryptOrNull(encoded: String): String? = try {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        val HN_SESSION = stringPreferencesKey("hn_session")
        val LEGACY_API_KEY = stringPreferencesKey("openrouter_api_key")
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

private const val KEY_ALIAS = "algofeed_secrets"

private fun keystoreKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
    val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
}
