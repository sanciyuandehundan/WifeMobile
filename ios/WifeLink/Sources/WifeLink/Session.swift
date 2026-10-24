import CryptoKit
import Foundation

/// One session's packets (protocol.md 会话), the same as pc/mobile/session.py and the Android Session.kt.
public final class Session {
    public static let ping: UInt8 = 1, pong: UInt8 = 2, audio: UInt8 = 3, message: UInt8 = 4, ack: UInt8 = 5, bye: UInt8 = 6
    static let piece = 1000, window = 32

    public let sid: Data
    public private(set) var address: Endpoint
    public var onMessage: ([String: Any]) -> Void = { _ in }
    public var onAudio: (Int, Data) -> Void = { _, _ in }
    public var onClosed: () -> Void = {}
    /// Seconds between pings, the pace the computer was told (5 with the app in front).
    public var pingEvery: TimeInterval

    private let send: (Data, Endpoint) -> Void
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private let lock = NSLock()
    private var n: UInt64 = 0
    private var top: UInt64 = 0
    private var recent = Set<UInt64>()
    private var nextOut: UInt32 = 0
    private var unacked: [UInt32: (Data, Date)] = [:]
    private var waiting: [Data] = []                   // message pieces waiting for room among the 32 unconfirmed
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

    public init(send: @escaping (Data, Endpoint) -> Void, sid: Data, sendKey: Data, recvKey: Data, address: Endpoint,
                pingEvery: TimeInterval = 5) {
        self.send = send
        self.sid = sid
        self.sendKey = SymmetricKey(data: sendKey)
        self.recvKey = SymmetricKey(data: recvKey)
        self.address = address
        self.pingEvery = pingEvery
    }

    private static func nonce(_ counter: Data) -> ChaChaPoly.Nonce { try! ChaChaPoly.Nonce(data: Data(count: 4) + counter) }

    private func packet(_ kind: UInt8, _ body: Data = Data()) {
        lock.lock()
        guard !closed else { return lock.unlock() }
        n += 1
        sent += 1
        let counter = withUnsafeBytes(of: n.bigEndian) { Data($0) }
        let header = Data("WC".utf8) + sid + counter
        let box = try! ChaChaPoly.seal(Data([kind]) + body, using: sendKey, nonce: Session.nonce(counter), authenticating: header)
        let to = address
        lock.unlock()
        send(header + box.ciphertext + box.tag, to)
    }

    public func sendAudio(_ frame: Data) {
        lock.lock()
        audioSeq &+= 1
        let seq = audioSeq
        lock.unlock()
        packet(Session.audio, withUnsafeBytes(of: seq.bigEndian) { Data($0) } + frame)
    }

    public func message(_ message: [String: Any]) { self.message(json: JSON.data(message)) }

    /// Reliable and in order; long messages go in 1000-byte pieces, at most 32 of them out unconfirmed.
    public func message(json data: Data) {
        let pieces = data.isEmpty ? [Data()] : stride(from: 0, to: data.count, by: Session.piece).map {
            Data(data[$0..<min($0 + Session.piece, data.count)]) }
        lock.lock()
        for (i, piece) in pieces.enumerated() {
            nextOut += 1
            waiting.append(withUnsafeBytes(of: nextOut.bigEndian) { Data($0) } + Data([i < pieces.count - 1 ? 1 : 0]) + piece)
        }
        lock.unlock()
        flush()
    }

    private func flush() {
        var out: [Data] = []
        lock.lock()
        while !waiting.isEmpty, unacked.count < Session.window {
            let body = waiting.removeFirst()
            unacked[body.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }] = (body, Date())
            out.append(body)
        }
        lock.unlock()
        out.forEach { packet(Session.message, $0) }
    }

    public func hangUp() {
        packet(Session.bye)
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
              let box = try? ChaChaPoly.SealedBox(nonce: Session.nonce(counterBytes), ciphertext: bytes[14..<(bytes.count - 16)],
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
        case Session.ping:
            packet(Session.pong, body)
        case Session.audio where body.count >= 2:
            onAudio(Int(body[0]) << 8 | Int(body[1]), Data(body.dropFirst(2)))
        case Session.message where body.count >= 5:
            deliver(body.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }, Data(body.dropFirst(4)))
        case Session.ack where body.count >= 4:
            let upto = body.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            lock.lock()
            unacked = unacked.filter { $0.key > upto }
            lock.unlock()
            flush()
        case Session.bye:
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
        packet(Session.ack, withUnsafeBytes(of: upto.bigEndian) { Data($0) })
        whole.forEach(onMessage)
    }

    /// Call every 100 ms: resends what wasn't confirmed, pings at its pace, ends after 4 pings of silence (20 s at least).
    public func tick(_ now: Date = Date()) {
        if now.timeIntervalSince(heard) > max(20, 4 * pingEvery) { return close() }
        lock.lock()
        var again: [Data] = []
        for (seq, entry) in unacked where now.timeIntervalSince(entry.1) > 0.3 {
            unacked[seq] = (entry.0, now)
            again.append(entry.0)
        }
        let pingNow = now.timeIntervalSince(lastPing) > pingEvery
        if pingNow { lastPing = now }
        lock.unlock()
        again.forEach { packet(Session.message, $0) }
        if pingNow { packet(Session.ping, withUnsafeBytes(of: UInt64(Crypto.now()).bigEndian) { Data($0) }) }
    }
}
