import CryptoKit
import Foundation

/// One call's packets (protocol.md 通话), the same as pc/mobile/call.py and the Android Call.kt.
public final class Call {
    public static let ping: UInt8 = 1, pong: UInt8 = 2, audio: UInt8 = 3, message: UInt8 = 4, ack: UInt8 = 5, bye: UInt8 = 6
    static let piece = 1000

    public let sid: Data
    public private(set) var address: Endpoint
    public var onMessage: ([String: Any]) -> Void = { _ in }
    public var onAudio: (Int, Data) -> Void = { _, _ in }
    public var onClosed: () -> Void = {}

    private let send: (Data, Endpoint) -> Void
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private let lock = NSLock()
    private var n: UInt64 = 0
    private var top: UInt64 = 0
    private var recent = Set<UInt64>()
    private var nextOut: UInt32 = 0
    private var unacked: [UInt32: (Data, Date)] = [:]
    private var nextIn: UInt32 = 1
    private var early: [UInt32: Data] = [:]
    private var joined = Data()
    private var audioSeq: UInt16 = 0
    private var lastPing = Date.distantPast
    /// How many packets went out and came in (shown during a call, to tell which way a problem is).
    public private(set) var sent = 0
    public private(set) var got = 0
    public private(set) var heard = Date()
    public private(set) var closed = false

    public init(send: @escaping (Data, Endpoint) -> Void, sid: Data, sendKey: Data, recvKey: Data, address: Endpoint) {
        self.send = send
        self.sid = sid
        self.sendKey = SymmetricKey(data: sendKey)
        self.recvKey = SymmetricKey(data: recvKey)
        self.address = address
    }

    private static func nonce(_ counter: Data) -> ChaChaPoly.Nonce { try! ChaChaPoly.Nonce(data: Data(count: 4) + counter) }

    private func packet(_ kind: UInt8, _ body: Data = Data()) {
        lock.lock()
        guard !closed else { return lock.unlock() }
        n += 1
        sent += 1
        let counter = withUnsafeBytes(of: n.bigEndian) { Data($0) }
        let header = Data("WC".utf8) + sid + counter
        let box = try! ChaChaPoly.seal(Data([kind]) + body, using: sendKey, nonce: Call.nonce(counter), authenticating: header)
        let to = address
        lock.unlock()
        send(header + box.ciphertext + box.tag, to)
    }

    public func sendAudio(_ frame: Data) {
        lock.lock()
        audioSeq &+= 1
        let seq = audioSeq
        lock.unlock()
        packet(Call.audio, withUnsafeBytes(of: seq.bigEndian) { Data($0) } + frame)
    }

    public func message(_ message: [String: Any]) { self.message(json: JSON.data(message)) }

    /// Reliable and in order; long messages go in 1000-byte pieces.
    public func message(json data: Data) {
        let pieces = data.isEmpty ? [Data()] : stride(from: 0, to: data.count, by: Call.piece).map {
            Data(data[$0..<min($0 + Call.piece, data.count)]) }
        for (i, piece) in pieces.enumerated() {
            lock.lock()
            nextOut += 1
            let body = withUnsafeBytes(of: nextOut.bigEndian) { Data($0) } + Data([i < pieces.count - 1 ? 1 : 0]) + piece
            unacked[nextOut] = (body, Date())
            lock.unlock()
            packet(Call.message, body)
        }
    }

    public func hangUp() {
        packet(Call.bye)
        close()
    }

    public func close() {
        lock.lock()
        let was = closed
        closed = true
        lock.unlock()
        if !was { onClosed() }
    }

    public func received(_ data: Data, from: Endpoint) {
        let bytes = Data(data)
        guard bytes.count >= 31, !closed else { return }
        let header = Data(bytes.prefix(14))
        let counterBytes = Data(header.suffix(8))
        let counter = counterBytes.reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        lock.lock()
        guard counter > top || (counter + 64 > top && !recent.contains(counter)),
              let box = try? ChaChaPoly.SealedBox(nonce: Call.nonce(counterBytes), ciphertext: bytes[14..<(bytes.count - 16)],
                                                  tag: bytes.suffix(16)),
              let plain = try? ChaChaPoly.open(box, using: recvKey, authenticating: header) else { return lock.unlock() }
        got += 1
        top = max(top, counter)
        recent.insert(counter)
        recent = recent.filter { $0 + 64 > top }
        address = from                                  // follow his phone when it changes networks
        heard = Date()
        lock.unlock()
        let kind = plain[plain.startIndex]
        let body = Data(plain.dropFirst())
        switch kind {
        case Call.ping:
            packet(Call.pong, body)
        case Call.audio where body.count >= 2:
            onAudio(Int(body[0]) << 8 | Int(body[1]), Data(body.dropFirst(2)))
        case Call.message where body.count >= 5:
            deliver(body.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }, Data(body.dropFirst(4)))
        case Call.ack where body.count >= 4:
            let upto = body.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            lock.lock()
            unacked = unacked.filter { $0.key > upto }
            lock.unlock()
        case Call.bye:
            close()
        default:
            break
        }
    }

    private func deliver(_ seq: UInt32, _ piece: Data) {
        var whole: [[String: Any]] = []
        lock.lock()
        if seq >= nextIn { early[seq] = piece }
        while let next = early.removeValue(forKey: nextIn) {
            nextIn += 1
            joined += next.dropFirst()
            if next[next.startIndex] == 0 {
                if let message = JSON.object(joined) { whole.append(message) }
                joined = Data()
            }
        }
        let upto = nextIn - 1
        lock.unlock()
        packet(Call.ack, withUnsafeBytes(of: upto.bigEndian) { Data($0) })
        whole.forEach(onMessage)
    }

    /// Call every 100 ms: resends what wasn't confirmed, pings every 5 s, hangs up after 20 s of silence.
    public func tick(_ now: Date = Date()) {
        if now.timeIntervalSince(heard) > 20 { return close() }
        lock.lock()
        var again: [Data] = []
        for (seq, entry) in unacked where now.timeIntervalSince(entry.1) > 0.3 {
            unacked[seq] = (entry.0, now)
            again.append(entry.0)
        }
        let pingNow = now.timeIntervalSince(lastPing) > 5
        if pingNow { lastPing = now }
        lock.unlock()
        again.forEach { packet(Call.message, $0) }
        if pingNow { packet(Call.ping, withUnsafeBytes(of: UInt64(Crypto.now()).bigEndian) { Data($0) }) }
    }
}
