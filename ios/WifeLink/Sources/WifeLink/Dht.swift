import CryptoKit
import Foundation

/// BitTorrent's DHT as a read-only node (BEP 43): the phone only looks up where the computer is, when it can't reach
/// it. It never asks BT's entry nodes; it starts from the nodes the computer gave it.
final class Dht {
    private let id = Crypto.bytes(20)
    private let send: (Data, Endpoint) -> Void
    private let lock = NSLock()
    private var pending: [Data: (DispatchSemaphore, Ben?)] = [:]
    /// "ip:port" → node id: nodes that answered lately (the caller saves them).
    private(set) var nodes: [String: Data] = [:]

    init(send: @escaping (Data, Endpoint) -> Void) { self.send = send }

    func add(_ address: String, id: Data? = nil) {
        lock.lock()
        if nodes[address] == nil || id != nil { nodes[address] = id ?? Crypto.bytes(20) }
        lock.unlock()
    }

    func received(_ data: Data, from: Endpoint) {
        guard let message = Ben.decode(data), let tid = message["t"]?.data,
              let kind = message["y"]?.data, kind == Data("r".utf8) || kind == Data("e".utf8) else { return }
        lock.lock()
        if let waiting = pending[tid] {
            pending[tid] = (waiting.0, message)
            waiting.0.signal()
        }
        lock.unlock()
    }

    private func query(_ to: Endpoint, _ method: String, _ args: [String: Ben]) -> Ben? {
        let tid = Crypto.bytes(2)
        let done = DispatchSemaphore(value: 0)
        lock.lock()
        pending[tid] = (done, nil)
        lock.unlock()
        var a = args
        a["id"] = .bytes(id)
        send(Ben.keyed(["t": .bytes(tid), "y": .text("q"), "q": .text(method), "ro": .int(1), "a": .keyed(a)]).encoded(), to)
        _ = done.wait(timeout: .now() + 2)
        lock.lock()
        let reply = pending.removeValue(forKey: tid)?.1
        lock.unlock()
        guard let body = reply?["r"], let nodeId = body["id"]?.data, nodeId.count == 20 else { return nil }
        add(to.description, id: nodeId)
        return body
    }

    private static func closer(_ a: Data, _ b: Data, to target: Data) -> Bool {
        for i in 0..<20 {
            let x = a[a.startIndex + i] ^ target[target.startIndex + i], y = b[b.startIndex + i] ^ target[target.startIndex + i]
            if x != y { return x < y }
        }
        return false
    }

    private static func usable(_ bytes: [UInt8], port: UInt16) -> Bool {
        let private10 = bytes[0] == 10, private172 = bytes[0] == 172 && (16...31).contains(bytes[1])
        let private192 = bytes[0] == 192 && bytes[1] == 168, local = bytes[0] == 127 || bytes[0] == 0
        let link = bytes[0] == 169 && bytes[1] == 254, multicast = bytes[0] >= 224
        return port != 0 && !(private10 || private172 || private192 || local || link || multicast)
    }

    /// Walks towards target, eight queries at a time; the replies of the nodes that answered.
    private func lookup(_ target: Data, _ method: String, _ args: [String: Ben]) -> [Ben] {
        lock.lock()
        var known: [Endpoint: Data] = [:]
        for (address, nodeId) in nodes { if let e = Endpoint(address) { known[e] = nodeId } }
        lock.unlock()
        var asked = Set<Endpoint>(), answers: [Endpoint: Ben] = [:]
        while true {
            let ranked = known.keys.sorted { Dht.closer(known[$0]!, known[$1]!, to: target) }.prefix(8)
            let todo = ranked.filter { !asked.contains($0) }
            if todo.isEmpty { break }
            asked.formUnion(todo)
            var replies = [Ben?](repeating: nil, count: todo.count)
            let results = NSLock()
            DispatchQueue.concurrentPerform(iterations: todo.count) { i in
                let reply = query(todo[i], method, args)
                results.lock()
                replies[i] = reply
                results.unlock()
            }
            for (e, reply) in zip(todo, replies) {
                guard let reply else {
                    known.removeValue(forKey: e)
                    lock.lock()
                    nodes.removeValue(forKey: e.description)
                    lock.unlock()
                    continue
                }
                answers[e] = reply
                known[e] = reply["id"]?.data
                guard let compact = reply["nodes"]?.data.map({ [UInt8]($0) }) else { continue }
                for i in stride(from: 0, to: compact.count - 25, by: 26) {
                    let ip = Array(compact[(i + 20)..<(i + 24)]), port = UInt16(compact[i + 24]) << 8 | UInt16(compact[i + 25])
                    let found = Endpoint(host: ip.map { String($0) }.joined(separator: "."), port: port)
                    if known[found] == nil, Dht.usable(ip, port: port) { known[found] = Data(compact[i..<(i + 20)]) }
                }
            }
        }
        return Array(answers.values)
    }

    /// The newest correctly signed value of a BEP 44 mutable item.
    func get(_ publicKey: Data, salt: Data) -> Data? {
        let target = Data(Insecure.SHA1.hash(data: publicKey + salt))
        var best: (Int64, Data)?
        for reply in lookup(target, "get", ["target": .bytes(target)]) {
            guard reply["k"]?.data == publicKey, let value = reply["v"]?.data, let seq = reply["seq"]?.int,
                  let signature = reply["sig"]?.data,
                  Crypto.edVerify(publicKey, Dht.signed(salt, seq, value), signature) else { continue }
            if best == nil || seq > best!.0 { best = (seq, value) }
        }
        return best?.1
    }

    static func signed(_ salt: Data, _ seq: Int64, _ value: Data) -> Data {
        (salt.isEmpty ? Data() : Data("4:salt".utf8) + Ben.bytes(salt).encoded()) + Data("3:seq".utf8) +
            Ben.int(seq).encoded() + Data("1:v".utf8) + Ben.bytes(value).encoded()
    }
}
