package app.wakeme.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secureDataStore by preferencesDataStore(name = "secure")

/**
 * Small encrypted key/value store for session secrets. Values are sealed with
 * an AES-256-GCM key that never leaves the Android Keystore; the datastore
 * file is excluded from backups (see data_extraction_rules.xml).
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val alias = "wakeme_secure_store_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    suspend fun put(name: String, value: String?) {
        context.secureDataStore.edit { prefs ->
            val k = stringPreferencesKey(name)
            if (value == null) prefs.remove(k) else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                prefs[k] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            }
        }
    }

    suspend fun get(name: String): String? {
        val raw = context.secureDataStore.data.first()[stringPreferencesKey(name)] ?: return null
        return runCatching {
            val (iv, ct) = raw.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    suspend fun clear() {
        context.secureDataStore.edit { it.clear() }
    }
}
