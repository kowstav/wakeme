import CryptoKit
import Foundation
import WakeMeAPI

/// This installation's signing key. On a real iPhone the private key lives in the
/// Secure Enclave and never leaves it; the Simulator has no Secure Enclave, so there
/// a software key is kept in the Keychain (debug use only).
final class DeviceKey: DeviceSigner, @unchecked Sendable {
    static let shared = DeviceKey()
    private let lock = NSLock()
    private var cachedId: String?

    private enum K {
        static let seKey = "device.se.key"
        static let swKey = "device.sw.key"
        static let installId = "device.installId"
        static let deviceId = "device.id"
    }

    /// Stable per installation; survives sign-out so the server sees one device.
    var installId: String {
        if let id = Keychain.string(for: K.installId) { return id }
        let id = "i_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        Keychain.set(id, for: K.installId)
        return id
    }

    func deviceId() async -> String? {
        lock.withLock {
            if cachedId == nil { cachedId = Keychain.string(for: K.deviceId) }
            return cachedId
        }
    }

    func setDeviceId(_ id: String?) {
        lock.withLock {
            cachedId = id
            if let id { Keychain.set(id, for: K.deviceId) } else { Keychain.delete(K.deviceId) }
        }
    }

    /// base64 SPKI DER of the public key, as the server expects.
    func publicKeySPKIBase64() throws -> String {
        try withKey(se: { $0.publicKey.derRepresentation }, sw: { $0.publicKey.derRepresentation }).base64EncodedString()
    }

    func signBase64(_ data: Data) async throws -> String {
        try withKey(se: { try $0.signature(for: data).derRepresentation }, sw: { try $0.signature(for: data).derRepresentation }).base64EncodedString()
    }

    private func withKey<T>(se: (SecureEnclave.P256.Signing.PrivateKey) throws -> T, sw: (P256.Signing.PrivateKey) throws -> T) throws -> T {
        if SecureEnclave.isAvailable {
            if let blob = Keychain.data(for: K.seKey), let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob) {
                return try se(key)
            }
            let key = try SecureEnclave.P256.Signing.PrivateKey()
            Keychain.set(key.dataRepresentation, for: K.seKey)
            return try se(key)
        }
        if let raw = Keychain.data(for: K.swKey), let key = try? P256.Signing.PrivateKey(rawRepresentation: raw) {
            return try sw(key)
        }
        let key = P256.Signing.PrivateKey()
        Keychain.set(key.rawRepresentation, for: K.swKey)
        return try sw(key)
    }
}
