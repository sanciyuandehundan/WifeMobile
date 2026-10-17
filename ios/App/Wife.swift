import AVFoundation
import SwiftUI
import UIKit
import WifeLink

/// One line in the conversation on screen. ``turn``: her reply's turn on the computer, so its pieces replace it.
struct Line: Identifiable {
    let id = UUID()
    let his: Bool
    var text: String
    var turn: Int = -1
}

/// The app's one state: the link to the computer and the call. Published values change on the main thread only.
final class Wife: ObservableObject {
    static let shared = Wife()

    @Published var paired = false
    @Published var computer = ""
    @Published var her = "她"
    @Published var dialing = false
    @Published var inCall = false
    @Published var awake: Bool?
    @Published var near = true
    @Published var speaker = true { didSet { voice?.speaker = speaker } }
    @Published var note = ""
    @Published var lines: [Line] = []

    let keep = Vault()
    lazy var link: WifeLink.Link = {                 // SwiftUI has a Link of its own
        let link = WifeLink.Link(keep: keep)
        link.onHere = { [weak self] _ in DispatchQueue.main.async { self?.refresh() } }
        return link
    }()
    private var voice: Voice?
    private var ticker: DispatchSourceTimer?
    private let work = DispatchQueue(label: "wife", qos: .userInitiated)

    func refresh() {
        let saved = keep.load()
        paired = saved["key"] != nil
        computer = (saved["here"] as? [String: Any])?["name"] as? String ?? ""
        if let name = (saved["card"] as? [String: Any])?["name"] as? String, !name.isEmpty { her = name }
    }

    func appeared() {
        refresh()
        work.async { self.link.whereIs() }                // at home: learn the computer's current addresses
    }

    func pair(_ qr: String) {
        note = "正在和电脑配对…"
        work.async {
            let problem = self.link.pair(qr, phoneName: UIDevice.current.name)
            DispatchQueue.main.async {
                self.refresh()
                self.note = problem ?? "配对好了。以后点“打给她”就能和她说话。"
            }
        }
    }

    // ── the call ──

    func callButton() {
        if inCall || dialing {
            link.call?.hangUp()
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async {
                if granted { self.dial() } else { self.note = "没有麦克风权限就没法通话" }
            }
        }
    }

    private func dial() {
        dialing = true
        note = "正在找电脑…"
        let near = self.near
        work.async {
            var voice: Voice?
            let call = self.link.dial(near: near) { call in
                call.onMessage = { message in DispatchQueue.main.async { self.heard(message) } }
                let made = Voice(call: call)
                voice = made
                call.onAudio = { [weak made] seq, frame in made?.heard(seq, frame) }
                call.onClosed = { DispatchQueue.main.async { self.ended() } }
            }
            DispatchQueue.main.async {
                guard let call, let voice else {
                    self.dialing = false
                    self.note = self.unreachable()
                    return
                }
                self.started(call, voice)
            }
        }
    }

    private func started(_ call: Call, _ voice: Voice) {
        self.voice = voice
        voice.speaker = speaker
        do {
            try voice.start()
        } catch {
            note = "声音没能打开：\(error.localizedDescription)"
        }
        call.message(["type": "hello", "near": near])
        dialing = false
        inCall = true
        note = ""
        let ticker = DispatchSource.makeTimerSource(queue: work)
        ticker.schedule(deadline: .now(), repeating: .milliseconds(100))
        ticker.setEventHandler { call.tick() }
        ticker.resume()
        self.ticker = ticker
    }

    private func ended() {
        ticker?.cancel()
        ticker = nil
        voice?.stop()
        voice = nil
        link.ended()
        inCall = false
        dialing = false
        awake = nil
        note = "通话结束了"
    }

    private func heard(_ message: [String: Any]) {
        switch message["type"] as? String {
        case "state":
            awake = message["awake"] as? Bool
            if let name = message["name"] as? String, !name.isEmpty { her = name }
        case "reply":
            let turn = (message["turn"] as? NSNumber)?.intValue ?? -1
            let text = message["text"] as? String ?? ""
            if turn >= 0, let at = lines.lastIndex(where: { !$0.his && $0.turn == turn }) {
                lines[at].text = text
            } else {
                lines.append(Line(his: false, text: text, turn: turn))
            }
        case "here":
            work.async { self.link.remember(message) }
        case "card":
            keep.update { $0["card"] = message }
            refresh()
        default:
            break
        }
    }

    func setNear(_ near: Bool) {
        self.near = near
        link.call?.message(["type": "hello", "near": near])
    }

    func type(_ text: String) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        guard let call = link.call, !call.closed else {
            note = "现在没连着电脑：先点“打给她”。"
            return
        }
        lines.append(Line(his: true, text: words))
        call.message(["type": "text", "text": words])
    }

    private func unreachable() -> String {
        let here = link.here()
        let away = (here["away"] as? [Any])?.count ?? 0
        switch (away, here["find"] as? String) {
        case (0, _): return "连不上电脑。电脑没有外出用的地址（没有 IPv6），只能在同一个 WiFi 里连。"
        case (_, "pair"): return "连不上电脑。家里的地址可能变了：回家连上 WiFi 后重新扫码配对。"
        case (_, "dht"): return "连不上电脑，在 BT 网络里也没查到它。电脑要开着，手机端程序要在运行。"
        default: return "连不上电脑。家里的地址可能变了，回家连上 WiFi 就会更新；也可能电脑没开、手机端程序没在运行。"
        }
    }
}
