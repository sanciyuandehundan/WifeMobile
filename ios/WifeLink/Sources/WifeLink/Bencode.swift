import Foundation

/// Bencode as the BitTorrent DHT speaks it. Dict keys are bytes and sort as bytes.
public indirect enum Ben: Equatable {
    case int(Int64)
    case bytes(Data)
    case list([Ben])
    case dict([Data: Ben])

    public static func text(_ s: String) -> Ben { .bytes(Data(s.utf8)) }

    /// A dict written with string keys.
    public static func keyed(_ items: [String: Ben]) -> Ben {
        .dict(Dictionary(uniqueKeysWithValues: items.map { (Data($0.key.utf8), $0.value) }))
    }

    public subscript(key: String) -> Ben? {
        if case .dict(let items) = self { return items[Data(key.utf8)] }
        return nil
    }

    public var data: Data? { if case .bytes(let d) = self { return d }; return nil }
    public var int: Int64? { if case .int(let v) = self { return v }; return nil }

    public func encoded() -> Data {
        switch self {
        case .int(let v):
            return Data("i\(v)e".utf8)
        case .bytes(let d):
            return Data("\(d.count):".utf8) + d
        case .list(let items):
            return items.reduce(Data("l".utf8)) { $0 + $1.encoded() } + Data("e".utf8)
        case .dict(let items):
            return items.keys.sorted { $0.lexicographicallyPrecedes($1) }
                .reduce(Data("d".utf8)) { $0 + Ben.bytes($1).encoded() + items[$1]!.encoded() } + Data("e".utf8)
        }
    }

    public static func decode(_ data: Data) -> Ben? {
        let bytes = [UInt8](data)
        var i = 0
        guard let value = read(bytes, &i), i == bytes.count else { return nil }
        return value
    }

    private static func read(_ b: [UInt8], _ i: inout Int) -> Ben? {
        guard i < b.count else { return nil }
        switch b[i] {
        case UInt8(ascii: "i"):
            guard let end = b[i...].firstIndex(of: UInt8(ascii: "e")),
                  let v = Int64(String(decoding: b[(i + 1)..<end], as: UTF8.self)) else { return nil }
            i = end + 1
            return .int(v)
        case UInt8(ascii: "l"):
            i += 1
            var items: [Ben] = []
            while i < b.count, b[i] != UInt8(ascii: "e") {
                guard let item = read(b, &i) else { return nil }
                items.append(item)
            }
            i += 1
            return .list(items)
        case UInt8(ascii: "d"):
            i += 1
            var items: [Data: Ben] = [:]
            while i < b.count, b[i] != UInt8(ascii: "e") {
                guard case .bytes(let key)? = read(b, &i), let item = read(b, &i) else { return nil }
                items[key] = item
            }
            i += 1
            return .dict(items)
        case UInt8(ascii: "0")...UInt8(ascii: "9"):
            guard let colon = b[i...].firstIndex(of: UInt8(ascii: ":")),
                  let n = Int(String(decoding: b[i..<colon], as: UTF8.self)), colon + 1 + n <= b.count else { return nil }
            i = colon + 1 + n
            return .bytes(Data(b[(colon + 1)..<i]))
        default:
            return nil
        }
    }
}
