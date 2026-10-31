import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public struct SignedEnvelope: Codable, Equatable, Sendable {
    public var revisionId: String
    public var payload: String
    public var signature: String
    public var keyId: String
    public var digest: String
    public var alg: String
}

public struct SignedRevisionPayload: Codable, Equatable, Sendable {
    public var revisionId: String
    public var definitionId: String
    public var revision: Int
    public var definition: ChallengeDefinition
}

public enum SignatureError: Error, Equatable {
    case unsupportedAlgorithm, unknownKey, badEncoding, digestMismatch, badSignature, revisionMismatch
}

/// Verifies a published challenge revision before installing it for offline
/// use: digest, then ECDSA P-256 / SHA-256 (DER) over the exact payload bytes,
/// then parse. A valid signature detects tampering; content safety was checked
/// by the server's validator before signing.
public enum ChallengeEnvelopes {
    /// trustedKeys: keyId → base64 SPKI DER, from the server's public config (pinned at install).
    public static func verify(_ envelope: SignedEnvelope, trustedKeys: [String: String]) throws -> SignedRevisionPayload {
        guard envelope.alg == "ES256" else { throw SignatureError.unsupportedAlgorithm }
        guard let spkiB64 = trustedKeys[envelope.keyId] else { throw SignatureError.unknownKey }
        guard let bytes = Data(base64Encoded: envelope.payload), let spki = Data(base64Encoded: spkiB64), let sig = Data(base64Encoded: envelope.signature) else {
            throw SignatureError.badEncoding
        }
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard digest == envelope.digest else { throw SignatureError.digestMismatch }
        let key: P256.Signing.PublicKey
        let signature: P256.Signing.ECDSASignature
        do {
            key = try P256.Signing.PublicKey(derRepresentation: spki)
            signature = try P256.Signing.ECDSASignature(derRepresentation: sig)
        } catch {
            throw SignatureError.badEncoding
        }
        guard key.isValidSignature(signature, for: bytes) else { throw SignatureError.badSignature }
        let payload = try JSONDecoder().decode(SignedRevisionPayload.self, from: bytes)
        guard payload.revisionId == envelope.revisionId else { throw SignatureError.revisionMismatch }
        return payload
    }
}
