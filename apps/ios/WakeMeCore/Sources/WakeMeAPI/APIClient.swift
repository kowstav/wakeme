import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// A structured API error. `message` is safe to show to the user.
public struct APIError: Error, Equatable, Sendable {
    public let status: Int
    public let code: String
    public let message: String
    public let retryable: Bool
    public let retryAfterSeconds: Int?
    public let requestId: String
}

/// Network unavailable: callers show an offline state; local alarms and escape never depend on this.
public struct OfflineError: Error, Sendable {
    public let message = "You're offline. Personal alarms on this phone still work."
}

/// Supplies the bearer token; `refresh()` returns true when a new token is available.
public protocol TokenProvider: Sendable {
    func accessToken() async -> String?
    func refresh() async -> Bool
}

/// This installation's hardware-backed key (Secure Enclave on iOS).
public protocol DeviceSigner: Sendable {
    func deviceId() async -> String?
    /// Base64 DER ECDSA P-256 / SHA-256 signature over `data`.
    func signBase64(_ data: Data) async throws -> String
}

/// Sends one request. Abstracted so tests run without a network.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: HTTPTransport {
    let session: URLSession
    public init(session: URLSession = .shared) { self.session = session }
    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }
}

private struct ErrorBody: Decodable {
    struct Detail: Decodable {
        let code: String
        let message: String
        let retryable: Bool?
        let requestId: String?
        let retryAfterSeconds: Int?
    }
    let error: Detail
}

public enum HTTPMethod: String, Sendable { case get = "GET", post = "POST", put = "PUT", patch = "PATCH", delete = "DELETE" }

/// Typed client for the Wake Me API. Mirrors the Android ApiClient:
///  - bearer token, refreshed once on 401;
///  - phone-affecting writes signed by this device's key (WAKEME-V1 canonical string);
///  - only idempotent requests retried, with jittered backoff and Retry-After;
///  - platform TLS trust only.
public struct APIClient: Sendable {
    public let baseURL: URL
    let transport: HTTPTransport
    let tokens: TokenProvider
    let signer: DeviceSigner?
    let sleep: @Sendable (UInt64) async -> Void

    public init(baseURL: URL, transport: HTTPTransport = URLSessionTransport(), tokens: TokenProvider, signer: DeviceSigner?, sleep: @escaping @Sendable (UInt64) async -> Void = { try? await Task.sleep(nanoseconds: $0) }) {
        self.baseURL = baseURL
        self.transport = transport
        self.tokens = tokens
        self.signer = signer
        self.sleep = sleep
    }

    public static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()

    public static let decoder = JSONDecoder()

    public static func idempotencyKey() -> String { "k_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() }

    public static func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    /// The exact string a device signs; the server rebuilds and verifies it.
    public static func canonical(method: String, path: String, timestamp: String, body: Data) -> String {
        ["WAKEME-V1", method, path, timestamp, sha256Hex(body)].joined(separator: "\n")
    }

    public func get<T: Decodable>(_ path: String, as: T.Type = T.self) async throws -> T {
        try await call(.get, path, body: Optional<Data>.none, signed: false, idempotent: true)
    }

    public func post<T: Decodable, B: Encodable>(_ path: String, body: B?, signed: Bool = false, idempotent: Bool = false, as: T.Type = T.self) async throws -> T {
        try await call(.post, path, body: try body.map { try Self.encoder.encode($0) }, signed: signed, idempotent: idempotent)
    }

    public func patch<T: Decodable, B: Encodable>(_ path: String, body: B, signed: Bool = false, as: T.Type = T.self) async throws -> T {
        try await call(.patch, path, body: try Self.encoder.encode(body), signed: signed, idempotent: true)
    }

    public func put<T: Decodable, B: Encodable>(_ path: String, body: B, as: T.Type = T.self) async throws -> T {
        try await call(.put, path, body: try Self.encoder.encode(body), signed: false, idempotent: true)
    }

    public func delete<T: Decodable>(_ path: String, as: T.Type = T.self) async throws -> T {
        try await call(.delete, path, body: Optional<Data>.none, signed: false, idempotent: true)
    }

    /// The response body as sent (for example a data export saved to a file).
    public func getData(_ path: String) async throws -> Data {
        try await callData(.get, path, body: nil, signed: false, idempotent: true)
    }

    func call<T: Decodable>(_ method: HTTPMethod, _ path: String, body: Data?, signed: Bool, idempotent: Bool) async throws -> T {
        let data = try await callData(method, path, body: body, signed: signed, idempotent: idempotent)
        return try Self.decoder.decode(T.self, from: data.isEmpty ? Data("{}".utf8) : data)
    }

    func callData(_ method: HTTPMethod, _ path: String, body: Data?, signed: Bool, idempotent: Bool) async throws -> Data {
        var attempt = 0
        var refreshed = false
        while true {
            attempt += 1
            let request = try await buildRequest(method, path, body: body, signed: signed)
            let data: Data
            let response: HTTPURLResponse
            do {
                (data, response) = try await transport.send(request)
            } catch {
                if idempotent && attempt < 3 {
                    await sleep(backoffNanos(attempt, retryAfter: nil))
                    continue
                }
                throw OfflineError()
            }
            if (200..<300).contains(response.statusCode) { return data }
            let err = try? Self.decoder.decode(ErrorBody.self, from: data).error
            let retryAfter = response.value(forHTTPHeaderField: "retry-after").flatMap(Int.init) ?? err?.retryAfterSeconds
            if response.statusCode == 401, !refreshed, let code = err?.code, code == "invalid_token" || code == "missing_token" {
                refreshed = true
                if await tokens.refresh() { continue }
            }
            if idempotent, attempt < 3, response.statusCode == 429 || response.statusCode >= 500, (retryAfter ?? 0) <= 30 {
                await sleep(backoffNanos(attempt, retryAfter: retryAfter))
                continue
            }
            throw APIError(
                status: response.statusCode,
                code: err?.code ?? "http_\(response.statusCode)",
                message: err?.message ?? "Something went wrong. Try again.",
                retryable: err?.retryable ?? (response.statusCode >= 500),
                retryAfterSeconds: retryAfter,
                requestId: err?.requestId ?? ""
            )
        }
    }

    func buildRequest(_ method: HTTPMethod, _ path: String, body: Data?, signed: Bool) async throws -> URLRequest {
        guard let url = URL(string: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else { throw URLError(.badURL) }
        var r = URLRequest(url: url)
        r.httpMethod = method.rawValue
        r.setValue("application/json", forHTTPHeaderField: "accept")
        if let token = await tokens.accessToken() { r.setValue("Bearer \(token)", forHTTPHeaderField: "authorization") }
        let bytes = body ?? Data()
        if signed {
            guard let signer, let id = await signer.deviceId() else { throw APIError(status: 0, code: "device_not_registered", message: "This phone isn't registered yet.", retryable: false, retryAfterSeconds: nil, requestId: "") }
            let ts = String(Int(Date().timeIntervalSince1970))
            r.setValue(id, forHTTPHeaderField: "x-wakeme-device")
            r.setValue(ts, forHTTPHeaderField: "x-wakeme-timestamp")
            r.setValue(try await signer.signBase64(Data(Self.canonical(method: method.rawValue, path: path, timestamp: ts, body: bytes).utf8)), forHTTPHeaderField: "x-wakeme-signature")
        }
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "content-type")
        }
        return r
    }

    func backoffNanos(_ attempt: Int, retryAfter: Int?) -> UInt64 {
        if let retryAfter { return UInt64(retryAfter) * 1_000_000_000 }
        let base = UInt64(500) << UInt64(attempt - 1)
        return base * UInt64(Int.random(in: 80..<120)) / 100 * 1_000_000
    }
}
