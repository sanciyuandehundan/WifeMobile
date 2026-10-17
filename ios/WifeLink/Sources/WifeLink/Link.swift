import CryptoKit
import Foundation

/// What the phone keeps between runs (the Keychain on the phone).
public protocol Keep: AnyObject {
    func load() -> [String: Any]
    func update(_ change: (inout [String: Any]) -> Void)
}

/// The phone's side of protocol.md: pairing, finding the computer, dialling, and the DHT when needed. Its calls
/// block: run them off the main thread. iOS lets an app broadcast only with a multicast entitlement from Apple, so
/// the phone sends to the addresses it knows (from the code it scanned, and the computer's "here" since).
public final class Link {
    public var onHere: ([String: Any]) -> Void = { _ in }
    public private(set) var call: Call?
    private let keep: Keep
    private var sockets: Sockets!
    private var dht: Dht!
    private let seen = Crypto.Seen()
    private let lock = NSLock()
    private var waiting: [String: (DispatchSemaphore, Any?)] = [:]

    public init(keep: Keep) {
        self.keep = keep
        sockets = Sockets { [weak self] data, from in self?.received(data, from: from) }
        dht = Dht { [weak self] data, to in self?.sockets.send(data, to: to) }
        for (address, id) in keep.load()["nodes"] as? [String: String] ?? [:] {
            dht.add(address, id: Crypto.unb64(id))
        }
    }

    public func here() -> [String: Any] { keep.load()["here"] as? [String: Any] ?? [:] }

    public func ended() { call = nil }

    private func received(_ data: Data, from: Endpoint) {
        guard let first = data.first else { return }
        if first == UInt8(ascii: "d") {
            dht.received(data, from: from)
        } else if data.starts(with: Data("WP1".utf8)), data.count > 35 {
            complete("pair:" + Crypto.b64(Data(data[data.startIndex + 3 ..< data.startIndex + 35])), data)
        } else if data.starts(with: Data("WM1".utf8)) {
            handshake(Data(data), from: from)
        } else if data.starts(with: Data("WC".utf8)), let call, data.count > 6,
                  Data(data[data.startIndex + 2 ..< data.startIndex + 6]) == call.sid {
            call.received(data, from: from)
        }
    }

    private func wait(_ key: String) {
        lock.lock()
        waiting[key] = (DispatchSemaphore(value: 0), nil)
        lock.unlock()
    }

    private func complete(_ key: String, _ value: Any) {
        lock.lock()
        if let entry = waiting[key], entry.1 == nil {
            waiting[key] = (entry.0, value)
            entry.0.signal()
        }
        lock.unlock()
    }

    /// Sends ``attempt`` again every ``everyMs`` until ``key`` is answered, at most ``times`` times.
    private func answer(_ key: String, times: Int, everyMs: Int, attempt: () -> Void) -> Any? {
        lock.lock()
        let semaphore = waiting[key]?.0
        lock.unlock()
        for _ in 0..<times {
            attempt()
            if semaphore?.wait(timeout: .now() + .milliseconds(everyMs)) == .success { break }
        }
        lock.lock()
        defer { lock.unlock() }
        return waiting.removeValue(forKey: key)?.1
    }

    private func head(_ dev: Data) -> Data { Data("WM1".utf8) + dev }

    private func handshake(_ data: Data, from: Endpoint) {
        let saved = keep.load()
        guard let key = (saved["key"] as? String).flatMap(Crypto.unb64), let dev = (saved["dev"] as? String).flatMap(Crypto.unb64),
              data.count > 11 + 28, Data(data[3..<11]) == dev,
              let message = Crypto.open(key, Data(data[11...]), aad: head(dev)), seen.fresh(message) else { return }
        switch message["type"] as? String {
        case "here":
            remember(message)
        case "answer":
            complete("answer:" + (message["re"] as? String ?? ""), (message, from))
        default:
            break
        }
    }

    /// The computer said where it is. Chosen "回家重新配对": its outside addresses stay those from pairing.
    public func remember(_ here: [String: Any]) {
        var here = here
        keep.update { saved in
            if let old = saved["here"] as? [String: Any], old["find"] as? String == "pair", here["type"] as? String != "paired" {
                here["away"] = old["away"] ?? []
            }
            saved["here"] = here
            for address in here["nodes"] as? [String] ?? [] { dht.add(address) }
            saved["nodes"] = dht.nodes.mapValues { Crypto.b64($0) }
        }
        onHere(here)
    }

    // ── pairing ──

    /// The scanned code; nil when paired, else what went wrong in his words.
    public func pair(_ qr: String, phoneName: String) -> String? {
        guard let parts = URLComponents(string: qr), let items = parts.queryItems,
              let pc = items.first(where: { $0.name == "pc" })?.value.flatMap(Crypto.unb64),
              let secret = items.first(where: { $0.name == "s" })?.value.flatMap(Crypto.unb64) else {
            return "这不是 Wife 的配对码"
        }
        let lan = (items.first(where: { $0.name == "l" })?.value ?? "").split(separator: ",").compactMap { Endpoint(String($0)) }
        let (key, phone) = Crypto.x25519()
        let head = Data("WP1".utf8) + phone
        let request = head + Crypto.seal(Crypto.hk(secret, Data("pair".utf8)), Crypto.stamp(["name": phoneName]), aad: head)
        let waitingFor = "pair:" + Crypto.b64(phone)
        wait(waitingFor)
        guard let reply = answer(waitingFor, times: 8, everyMs: 700, attempt: { lan.forEach { sockets.send(request, to: $0) } })
                as? Data else {
            return "电脑没有回话：手机和电脑要连着同一个 WiFi，电脑上的配对码要还开着"
        }
        guard let link = try? Crypto.linkKey(key, phonePublic: phone, pcPublic: pc, secret: secret),
              let paired = Crypto.open(link, Data(reply[35...]), aad: head), let dev = paired["dev"] as? String else {
            return "配对码不对，或者已经作废了"
        }
        keep.update { $0 = ["key": Crypto.b64(link), "dev": dev] }          // a new computer, a new start
        remember(paired)
        return nil
    }

    // ── finding the computer and dialling ──

    private func addresses(_ list: Any?) -> [Endpoint] { (list as? [String] ?? []).compactMap(Endpoint.init) }

    /// At home: asks where the computer is (its reply updates the addresses).
    public func whereIs() {
        let saved = keep.load()
        guard let key = (saved["key"] as? String).flatMap(Crypto.unb64), let dev = (saved["dev"] as? String).flatMap(Crypto.unb64)
        else { return }
        let packet = head(dev) + Crypto.seal(key, Crypto.stamp(["type": "where"]), aad: head(dev))
        addresses(here()["lan"]).forEach { sockets.send(packet, to: $0) }
    }

    /// Dials the computer at its home and outside addresses at once; when that fails and it was chosen, asks BT's DHT
    /// once where the computer is now. ``ready`` sets up the call before its first packet can arrive.
    public func dial(near: Bool, ready: (Call) -> Void) -> Call? {
        let saved = keep.load()
        guard let key = (saved["key"] as? String).flatMap(Crypto.unb64), let dev = (saved["dev"] as? String).flatMap(Crypto.unb64)
        else { return nil }
        let sid = Crypto.bytes(4)
        let (secret, ePhone) = Crypto.x25519()
        let offer = Crypto.stamp(["type": "offer", "sid": Crypto.b64(sid), "e": Crypto.b64(ePhone), "near": near])
        let packet = head(dev) + Crypto.seal(key, offer, aad: head(dev))
        let waitingFor = "answer:" + (offer["id"] as! String)
        func targets(_ here: [String: Any]) -> [Endpoint] { addresses(here["lan"]) + addresses(here["away"]) }
        wait(waitingFor)
        var reply = answer(waitingFor, times: 10, everyMs: 500) { targets(here()).forEach { sockets.send(packet, to: $0) } }
        if reply == nil, here()["find"] as? String == "dht", let found = lookUp(key) {
            remember(found)
            wait(waitingFor)
            reply = answer(waitingFor, times: 10, everyMs: 500) { targets(found).forEach { sockets.send(packet, to: $0) } }
        }
        guard let answered = reply as? ([String: Any], Endpoint),
              let ePc = (answered.0["e"] as? String).flatMap(Crypto.unb64),
              let keys = try? Crypto.callKeys(secret, peer: ePc, link: key, sid: sid, ePhone: ePhone, ePc: ePc)
        else { return nil }
        let call = Call(send: { [weak self] data, to in self?.sockets.send(data, to: to) }, sid: sid, sendKey: keys.0,
                        recvKey: keys.1, address: answered.1)
        ready(call)
        self.call = call
        return call
    }

    /// The computer's latest "here" on the DHT (today's key, then yesterday's).
    private func lookUp(_ key: Data) -> [String: Any]? {
        for day in [Crypto.day(), Crypto.day() - 1] {
            guard let publicKey = try? Crypto.edPublic(Crypto.dhtSeed(key, day: day)),
                  let value = dht.get(publicKey, salt: Data("pc".utf8)),
                  let here = Crypto.open(key, value, aad: Data("pc".utf8)) else { continue }
            return here
        }
        return nil
    }
}
