import Darwin
import Foundation

/// An address as protocol.md writes it: "1.2.3.4:5" or "[2001:db8::1]:5".
public struct Endpoint: Hashable, CustomStringConvertible {
    public let host: String
    public let port: UInt16

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }

    public init?(_ text: String) {
        guard let colon = text.lastIndex(of: ":"), let port = UInt16(text[text.index(after: colon)...]) else { return nil }
        self.init(host: String(text[..<colon]).trimmingCharacters(in: CharacterSet(charactersIn: "[]")), port: port)
    }

    var v6: Bool { host.contains(":") }
    public var description: String { v6 ? "[\(host)]:\(port)" : "\(host):\(port)" }
}

/// The phone's two UDP ports: one IPv4 (handshakes, sessions, DHT) and one IPv6 (sessions).
final class Sockets {
    private let v4: Int32
    private let v6: Int32
    private let sending = DispatchQueue(label: "link-send")

    init(received: @escaping (Data, Endpoint) -> Void) {
        let four = socket(AF_INET, SOCK_DGRAM, 0)
        var any4 = sockaddr_in()
        any4.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        any4.sin_family = sa_family_t(AF_INET)
        _ = withUnsafePointer(to: &any4) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(four, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        let six = socket(AF_INET6, SOCK_DGRAM, 0)
        var only: Int32 = 1
        setsockopt(six, IPPROTO_IPV6, IPV6_V6ONLY, &only, socklen_t(MemoryLayout<Int32>.size))
        var any6 = sockaddr_in6()
        any6.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        any6.sin6_family = sa_family_t(AF_INET6)
        _ = withUnsafePointer(to: &any6) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(six, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) } }
        v4 = four
        v6 = six
        for fd in [four, six] where fd >= 0 {
            Thread { Sockets.receive(fd, received) }.start()
        }
    }

    /// iOS takes a suspended app's sockets back: closed, its receiving threads end.
    func close() {
        Darwin.close(v4)
        Darwin.close(v6)
    }

    /// From any thread; packets leave in order.
    func send(_ data: Data, to e: Endpoint) {
        sending.async { [v4, v6] in
            if e.v6 {
                var a = sockaddr_in6()
                a.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
                a.sin6_family = sa_family_t(AF_INET6)
                a.sin6_port = e.port.bigEndian
                guard inet_pton(AF_INET6, e.host, &a.sin6_addr) == 1 else { return }
                data.withUnsafeBytes { buffer in
                    _ = withUnsafePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(v6, buffer.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) } }
                }
            } else {
                var a = sockaddr_in()
                a.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                a.sin_family = sa_family_t(AF_INET)
                a.sin_port = e.port.bigEndian
                guard inet_pton(AF_INET, e.host, &a.sin_addr) == 1 else { return }
                data.withUnsafeBytes { buffer in
                    _ = withUnsafePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(v4, buffer.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
                }
            }
        }
    }

    private static func receive(_ fd: Int32, _ received: (Data, Endpoint) -> Void) {
        var buffer = [UInt8](repeating: 0, count: 65536)
        while true {
            var storage = sockaddr_storage()
            var length = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let n = withUnsafeMutablePointer(to: &storage) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                recvfrom(fd, &buffer, buffer.count, 0, $0, &length) } }
            if n < 0 { return }
            if let from = endpoint(&storage) { received(Data(buffer[0..<n]), from) }
        }
    }

    private static func endpoint(_ storage: inout sockaddr_storage) -> Endpoint? {
        var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        switch Int32(storage.ss_family) {
        case AF_INET:
            return withUnsafePointer(to: &storage) { $0.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { a in
                var address = a.pointee.sin_addr
                inet_ntop(AF_INET, &address, &text, socklen_t(text.count))
                return Endpoint(host: String(cString: text), port: UInt16(bigEndian: a.pointee.sin_port))
            } }
        case AF_INET6:
            return withUnsafePointer(to: &storage) { $0.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { a in
                var address = a.pointee.sin6_addr
                inet_ntop(AF_INET6, &address, &text, socklen_t(text.count))
                return Endpoint(host: String(cString: text), port: UInt16(bigEndian: a.pointee.sin6_port))
            } }
        default:
            return nil
        }
    }
}
