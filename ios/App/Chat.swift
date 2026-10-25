import Copus
import Foundation
import UIKit

/// One line in the conversation with her, kept on the phone (protocol.md 消息): his words, voice messages and photos,
/// and hers. His wait as ``pending`` until the computer says it has them.
struct Item: Identifiable, Codable, Equatable {
    enum Kind: String, Codable { case text, voice, photo }

    let id: String
    let his: Bool
    let t: Int64                    // ms
    var kind: Kind
    var text: String                // typed or said; under his voice what she heard, with hers what she said
    var ms = 0                      // a voice message's length
    var pending = false             // his, not yet confirmed by the computer
    var heard: Bool?                // his voice message: nil not heard yet, false not his voice (she did not take it)
    var link: String?               // hers: a video she wants him to see, ``text`` its title

    var date: Date { Date(timeIntervalSince1970: TimeInterval(t) / 1000) }
}

/// The conversation on this phone: chat.json beside one file per recording (.opus) or photo (.jpg), in the app's own
/// storage, which iOS keeps encrypted while the phone is locked.
final class Chat {
    private(set) var items: [Item] = []
    private let folder: URL
    private let pictures = NSCache<NSString, UIImage>()

    init() {
        folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: folder.appendingPathComponent("chat.json")),
           let saved = try? JSONDecoder().decode([Item].self, from: data) {
            items = saved
        }
    }

    func has(_ id: String) -> Bool { items.contains { $0.id == id } }

    func add(_ item: Item, voice: Data? = nil, photo: Data? = nil) {
        if let voice { try? voice.write(to: file(item.id, "opus")) }
        if let photo { try? photo.write(to: file(item.id, "jpg")) }
        items.append(item)
        items.sort { $0.t < $1.t }
        save()
    }

    func update(_ id: String, voice: Data? = nil, _ change: (inout Item) -> Void) {
        guard let at = items.firstIndex(where: { $0.id == id }) else { return }
        if let voice { try? voice.write(to: file(id, "opus")) }
        change(&items[at])
        save()
    }

    func voice(_ id: String) -> Data? { try? Data(contentsOf: file(id, "opus")) }
    func photo(_ id: String) -> Data? { try? Data(contentsOf: file(id, "jpg")) }

    func picture(_ id: String) -> UIImage? {
        if let kept = pictures.object(forKey: id as NSString) { return kept }
        guard let data = photo(id), let image = UIImage(data: data) else { return nil }
        pictures.setObject(image, forKey: id as NSString)
        return image
    }

    private func file(_ id: String, _ kind: String) -> URL { folder.appendingPathComponent("\(id).\(kind)") }

    private func save() {
        if let data = try? JSONEncoder().encode(items) {
            try? data.write(to: folder.appendingPathComponent("chat.json"), options: .atomic)
        }
    }
}

/// A recording in a message (protocol.md 声音的编码): each Opus frame after its 2-byte length.
enum Recording {
    static func pack(_ frames: [Data]) -> Data {
        frames.reduce(into: Data()) { out, frame in
            out.append(UInt8(frame.count >> 8))
            out.append(UInt8(frame.count & 0xFF))
            out.append(frame)
        }
    }

    static func unpack(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var frames: [Data] = [], at = 0
        while at + 2 <= bytes.count {
            let size = Int(bytes[at]) << 8 | Int(bytes[at + 1])
            frames.append(Data(bytes[(at + 2)..<min(at + 2 + size, bytes.count)]))
            at += 2 + size
        }
        return frames
    }

    /// 16-bit samples at ``rate`` (16 kHz for his voice, 48 kHz for hers).
    static func decode(_ data: Data, rate: Int32) -> [Int16] {
        var error: Int32 = 0
        let decoder = opus_decoder_create(rate, 1, &error)!
        defer { opus_decoder_destroy(decoder) }
        var samples: [Int16] = [], out = [Int16](repeating: 0, count: 5760)
        for frame in unpack(data) {
            let n = frame.withUnsafeBytes { bytes in
                out.withUnsafeMutableBufferPointer {
                    opus_decode(decoder, bytes.bindMemory(to: UInt8.self).baseAddress, Int32(frame.count), $0.baseAddress!, 5760, 0)
                }
            }
            if n > 0 { samples += out[0..<Int(n)] }
        }
        return samples
    }

    static func wav(_ samples: [Int16], rate: Int) -> Data {
        func le<T: FixedWidthInteger>(_ value: T) -> Data { withUnsafeBytes(of: value.littleEndian) { Data($0) } }
        let body = samples.withUnsafeBufferPointer { Data(buffer: $0) }
        var out = Data("RIFF".utf8) + le(UInt32(36 + body.count)) + Data("WAVEfmt ".utf8)
        out += le(UInt32(16)) + le(UInt16(1)) + le(UInt16(1)) + le(UInt32(rate)) + le(UInt32(rate * 2)) + le(UInt16(2)) + le(UInt16(16))
        return out + Data("data".utf8) + le(UInt32(body.count)) + body
    }
}
