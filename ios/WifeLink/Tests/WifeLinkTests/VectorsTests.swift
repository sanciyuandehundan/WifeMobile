import CryptoKit
import XCTest
@testable import WifeLink

/// The iPhone must produce the same bytes as the computer (pc/tests/vectors.json, made by pc/tests/vectors.py).
final class VectorsTests: XCTestCase {
    private let v: [String: [String: Any]] = {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try! Data(contentsOf: root.appendingPathComponent("pc/tests/vectors.json"))
        return try! JSONSerialization.jsonObject(with: data) as! [String: [String: Any]]
    }()

    private func hex(_ group: String, _ key: String) -> Data {
        let text = v[group]![key] as! String
        return Data(stride(from: 0, to: text.count, by: 2).map {
            UInt8(text[text.index(text.startIndex, offsetBy: $0)..<text.index(text.startIndex, offsetBy: $0 + 2)], radix: 16)! })
    }

    private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    private let there = Endpoint(host: "127.0.0.1", port: 1)

    func testHkdf() {
        XCTAssertEqual(hex(Crypto.hk(hex("hk", "ikm"), hex("hk", "info"))), v["hk"]!["out"] as? String)
    }

    func testSealedMessagesMatchAndOpen() {
        let json = Data((v["seal"]!["json"] as! String).utf8)
        let sealed = Crypto.seal(hex("seal", "key"), json: json, aad: hex("seal", "aad"), nonce: hex("seal", "nonce"))
        XCTAssertEqual(hex(sealed), v["seal"]!["out"] as? String)
        XCTAssertEqual(Crypto.open(hex("seal", "key"), sealed, aad: hex("seal", "aad"))?["type"] as? String, "where")
        XCTAssertNil(Crypto.open(hex("seal", "key"), sealed, aad: Data("WP1".utf8)))
    }

    func testLinkKeyFromThePairingCode() throws {
        let phone = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hex("link", "phone_private"))
        XCTAssertEqual(hex(phone.publicKey.rawRepresentation), v["link"]!["phone_public"] as? String)
        let link = try Crypto.linkKey(phone, phonePublic: hex("link", "phone_public"), pcPublic: hex("link", "pc_public"),
                                      secret: hex("link", "secret"))
        XCTAssertEqual(hex(link), v["link"]!["key"] as? String)
    }

    func testTheDaysDhtKeyAndSignature() throws {
        let seed = Crypto.dhtSeed(hex("link", "key"), day: v["dht"]!["day"] as! Int)
        XCTAssertEqual(hex(try Crypto.edPublic(seed)), v["dht"]!["public"] as? String)
        let signed = Dht.signed(Data("pc".utf8), 5, Data("v".utf8))
        XCTAssertEqual(hex(signed), v["dht"]!["signed"] as? String)
        XCTAssertTrue(Crypto.edVerify(hex("dht", "public"), signed, hex("dht", "signature")))
        XCTAssertFalse(Crypto.edVerify(hex("dht", "public"), signed + Data([0]), hex("dht", "signature")))
    }

    func testCallKeysAndTheFirstPacket() throws {
        let phone = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: hex("call", "phone_e_private"))
        let (toPc, toPhone) = try Crypto.callKeys(phone, peer: hex("call", "pc_e_public"), link: hex("link", "key"),
                                                  sid: hex("call", "sid"), ePhone: hex("call", "phone_e_public"),
                                                  ePc: hex("call", "pc_e_public"))
        XCTAssertEqual(hex(toPc), v["call"]!["to_pc"] as? String)
        XCTAssertEqual(hex(toPhone), v["call"]!["to_phone"] as? String)
        var sent: [Data] = []
        Call(send: { data, _ in sent.append(data) }, sid: hex("call", "sid"), sendKey: toPc, recvKey: toPhone, address: there)
            .message(json: Data(#"{"type":"text","text":"你好"}"#.utf8))
        XCTAssertEqual(hex(sent[0]), v["call"]!["first_message_packet"] as? String)
        var heard: [String] = []
        let computer = Call(send: { _, _ in }, sid: hex("call", "sid"), sendKey: toPhone, recvKey: toPc, address: there)
        computer.onMessage = { heard.append($0["text"] as! String) }
        computer.received(sent[0], from: there)
        computer.received(sent[0], from: there)            // replayed: ignored
        XCTAssertEqual(heard, ["你好"])
    }

    func testLongMessagesArriveWhole() {
        var sent: [Data] = []
        let one = Crypto.bytes(32), two = Crypto.bytes(32), sid = Data([1, 2, 3, 4])
        let card = String(repeating: "她记得的事。", count: 800)
        Call(send: { data, _ in sent.append(data) }, sid: sid, sendKey: one, recvKey: two, address: there)
            .message(["card": card])
        var heard: [String] = []
        let phone = Call(send: { _, _ in }, sid: sid, sendKey: two, recvKey: one, address: there)
        phone.onMessage = { heard.append($0["card"] as! String) }
        sent.reversed().forEach { phone.received($0, from: there) }     // out of order on the way
        XCTAssertGreaterThan(sent.count, 10)
        XCTAssertEqual(heard, [card])
    }

    func testBencodeLikeTheComputer() {
        let encoded = Ben.keyed(["t": .text("aa"), "y": .text("q"), "q": .text("ping"), "a": .keyed(["id": .text("abc")])]).encoded()
        XCTAssertEqual(hex(encoded), v["bencode"]!["encoded"] as? String)
        XCTAssertEqual(Ben.decode(encoded)?["a"]?["id"]?.data, Data("abc".utf8))
    }

    func testAddressesReadLikeProtocolMd() {
        XCTAssertEqual(Endpoint("[2001:db8::1]:18766"), Endpoint(host: "2001:db8::1", port: 18766))
        XCTAssertEqual(Endpoint("192.168.5.2:18766")?.description, "192.168.5.2:18766")
        XCTAssertNil(Endpoint("no-port"))
    }
}
