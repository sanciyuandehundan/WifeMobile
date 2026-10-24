import CryptoKit
import Foundation

/// The primitives both ends share (protocol.md 记号), the same as pc/mobile/crypto.py and the Android Crypto.kt.
public enum Crypto {
    static let salt = Data("wife-mobile/1".utf8)
    static let freshMs: Int64 = 5 * 60 * 1000

    public static func hk(_ ikm: Data, _ info: Data, _ n: Int = 32) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: n)
            .withUnsafeBytes { Data($0) }
    }

    public static func b64(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func unb64(_ text: String) -> Data? {
        var standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while standard.count % 4 != 0 { standard += "=" }
        return Data(base64Encoded: standard)
    }

    public static func bytes(_ n: Int) -> Data { Data((0..<n).map { _ in UInt8.random(in: 0...255) }) }

    public static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // ── sealed JSON ──

    public static func seal(_ key: Data, json: Data, aad: Data, nonce: Data? = nil) -> Data {
        let box = try! ChaChaPoly.seal(json, using: SymmetricKey(data: key),
                                       nonce: try! ChaChaPoly.Nonce(data: nonce ?? bytes(12)), authenticating: aad)
        return box.combined
    }

    public static func seal(_ key: Data, _ message: [String: Any], aad: Data) -> Data {
        seal(key, json: JSON.data(message), aad: aad)
    }

    /// nil when the key or the data is wrong.
    public static func open(_ key: Data, _ sealed: Data, aad: Data) -> [String: Any]? {
        guard sealed.count >= 28, let box = try? ChaChaPoly.SealedBox(combined: sealed),
              let plain = try? ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad) else { return nil }
        return JSON.object(plain)
    }

    public static func stamp(_ message: [String: Any]) -> [String: Any] {
        message.merging(["t": now(), "id": b64(bytes(8))]) { $1 }
    }

    /// Drops stale or replayed messages.
    public final class Seen {
        private var ids: [String: Int64] = [:]
        private let lock = NSLock()

        public init() {}

        public func fresh(_ message: [String: Any]) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            let now = Crypto.now()
            let t = (message["t"] as? NSNumber)?.int64Value ?? 0
            guard let id = message["id"] as? String, !id.isEmpty, abs(now - t) <= freshMs, ids[id] == nil else { return false }
            ids = ids.filter { now - $0.value < 2 * freshMs }
            ids[id] = t
            return true
        }
    }

    // ── keys ──

    public static func x25519() -> (Curve25519.KeyAgreement.PrivateKey, Data) {
        let key = Curve25519.KeyAgreement.PrivateKey()
        return (key, key.publicKey.rawRepresentation)
    }

    public static func agree(_ key: Curve25519.KeyAgreement.PrivateKey, _ peer: Data) throws -> Data {
        try key.sharedSecretFromKeyAgreement(with: .init(rawRepresentation: peer)).withUnsafeBytes { Data($0) }
    }

    public static func linkKey(_ phone: Curve25519.KeyAgreement.PrivateKey, phonePublic: Data, pcPublic: Data,
                               secret: Data) throws -> Data {
        hk(try agree(phone, pcPublic) + secret, Data("link".utf8) + pcPublic + phonePublic)
    }

    /// (phone→computer, computer→phone)
    public static func sessionKeys(_ key: Curve25519.KeyAgreement.PrivateKey, peer: Data, link: Data, sid: Data,
                                ePhone: Data, ePc: Data) throws -> (Data, Data) {
        let keys = hk(try agree(key, peer) + link, Data("call".utf8) + sid + ePhone + ePc, 64)
        return (Data(keys.prefix(32)), Data(keys.suffix(32)))
    }

    public static func day(_ at: Date = Date()) -> Int { Int(at.timeIntervalSince1970 / 86400) }

    /// The day's Ed25519 key for the DHT: the seed is the private key.
    public static func dhtSeed(_ link: Data, day: Int) -> Data {
        hk(link, Data("dht".utf8) + withUnsafeBytes(of: UInt32(day).bigEndian) { Data($0) })
    }

    public static func edPublic(_ seed: Data) throws -> Data {
        try Curve25519.Signing.PrivateKey(rawRepresentation: seed).publicKey.rawRepresentation
    }

    public static func edVerify(_ publicKey: Data, _ message: Data, _ signature: Data) -> Bool {
        (try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey))?.isValidSignature(signature, for: message) ?? false
    }
}

public enum JSON {
    public static func data(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes])) ?? Data("{}".utf8)
    }

    public static func object(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}
