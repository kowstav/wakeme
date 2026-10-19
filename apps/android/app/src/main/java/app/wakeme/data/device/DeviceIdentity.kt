package app.wakeme.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceStore by preferencesDataStore(name = "device")

/**
 * This installation's identity: a random install id and a non-exportable
 * EC P-256 signing key in the Android Keystore (StrongBox when available).
 * The server binds receipts and commands to this key; a stolen access token
 * alone cannot submit receipts for this phone.
 */
@Singleton
class DeviceIdentity @Inject constructor(@ApplicationContext private val context: Context) {
    private val alias = "wakeme_device_signing_v1"
    private val installKey = stringPreferencesKey("install_id")
    private val deviceKey = stringPreferencesKey("device_id")

    suspend fun installId(): String {
        context.deviceStore.data.first()[installKey]?.let { return it }
        val id = "and_" + UUID.randomUUID().toString().replace("-", "")
        context.deviceStore.edit { it[installKey] = id }
        return id
    }

    /** Server-assigned device id after registration; null before. */
    suspend fun deviceId(): String? = context.deviceStore.data.first()[deviceKey]

    suspend fun setDeviceId(id: String?) {
        context.deviceStore.edit { if (id == null) it.remove(deviceKey) else it[deviceKey] = id }
    }

    private fun privateKey(): PrivateKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? PrivateKey)?.let { return it }
        generate(strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        return ks.getKey(alias, null) as PrivateKey
    }

    private fun generate(strongBox: Boolean) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        try {
            gen.initialize(spec)
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    /** base64 SPKI DER of the public key, as the server expects. */
    fun publicKeyBase64(): String {
        privateKey()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return Base64.encodeToString(ks.getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)
    }

    /** DER-encoded ECDSA signature, base64. */
    fun signBase64(message: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey())
            update(message)
        }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    /** Rotate after sign-out or account recovery: old receipts can no longer be forged with a leaked key. */
    suspend fun reset() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        context.deviceStore.edit { it.remove(deviceKey) }
    }
}
