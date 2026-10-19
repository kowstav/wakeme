package app.wakeme.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable
data class SignedEnvelope(val revisionId: String, val payload: String, val signature: String, val keyId: String, val digest: String, val alg: String)

@Serializable
data class SignedRevisionPayload(val revisionId: String, val definitionId: String, val revision: Int, val definition: ChallengeDefinition)

class SignatureException(message: String) : Exception(message)

/**
 * Verifies a published challenge revision before installing it for offline
 * use: digest, then ECDSA P-256 / SHA-256 (DER) over the exact payload bytes,
 * then parse. A valid signature detects tampering; content safety was checked
 * by the server's validator before signing.
 */
object ChallengeEnvelopes {
    fun verify(envelope: SignedEnvelope, trustedKeys: Map<String, String>): SignedRevisionPayload {
        if (envelope.alg != "ES256") throw SignatureException("unsupported algorithm")
        val spki = trustedKeys[envelope.keyId] ?: throw SignatureException("unknown signing key")
        val bytes = Base64.getDecoder().decode(envelope.payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (digest != envelope.digest) throw SignatureException("digest mismatch")
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(spki)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(bytes)
            verify(Base64.getDecoder().decode(envelope.signature))
        }
        if (!ok) throw SignatureException("bad signature")
        val payload = WakeMeJson.decodeFromString(SignedRevisionPayload.serializer(), bytes.toString(Charsets.UTF_8))
        if (payload.revisionId != envelope.revisionId) throw SignatureException("revision mismatch")
        return payload
    }

    fun rawJson(envelope: SignedEnvelope): JsonObject =
        WakeMeJson.parseToJsonElement(Base64.getDecoder().decode(envelope.payload).toString(Charsets.UTF_8)).jsonObject
}
