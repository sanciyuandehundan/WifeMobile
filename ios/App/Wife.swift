import AVFoundation
import BackgroundTasks
import SwiftUI
import UIKit
import UserNotifications
import WifeLink

/// The app's one state: the link to the computer, the session on it, the conversation. Published values change on the
/// main thread only.
final class Wife: ObservableObject {
    static let shared = Wife()
    static let fetchTask = "io.wife.mobile.fetch"

    @Published var paired = false
    @Published var computer = ""
    @Published var her = "她"
    @Published var connected = false
    @Published var connecting = false
    @Published var awake: Bool?
    @Published var away = false
    @Published var items: [Item] = []
    @Published var typing = ""                  // her reply as she writes it
    @Published var inCall = false
    @Published var near = true
    @Published var speaker = true { didSet { voice?.speaker = speaker } }
    /// She answers what he types (and photos) with her voice too, played as it comes.
    @Published var voiceReplies = UserDefaults.standard.bool(forKey: "voiceReplies") {
        didSet { UserDefaults.standard.set(voiceReplies, forKey: "voiceReplies") }
    }
    @Published var voiceprints = 0
    @Published var enrolling = ""               // what the computer said about the last voiceprint recording
    @Published var note = ""
    @Published var stats = ""

    let keep = Vault()
    let chat = Chat()
    let player = Player()
    lazy var link: WifeLink.Link = {                 // SwiftUI has a Link of its own
        let link = WifeLink.Link(keep: keep)
        link.onHere = { [weak self] _ in DispatchQueue.main.async { self?.refresh() } }
        return link
    }()
    private var session: Session?
    private var voice: Voice?
    private var ticker: DispatchSourceTimer?
    private let work = DispatchQueue(label: "wife", qos: .userInitiated)
    private var front = false
    private var attempts = 0
    private var retry: DispatchWorkItem?
    private var fetching: BGAppRefreshTask?
    private var fetchQuiet: DispatchWorkItem?
    private var lastVoiceSent = Date.distantPast

    init() { items = chat.items }

    func refresh() {
        let saved = keep.load()
        paired = saved["key"] != nil
        computer = (saved["here"] as? [String: Any])?["name"] as? String ?? ""
        if let name = (saved["card"] as? [String: Any])?["name"] as? String, !name.isEmpty { her = name }
    }

    // ── in front, behind, woken by iOS ──

    func foreground() {
        front = true
        refresh()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        guard paired else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        if let session {
            session.message(["type": "hello", "app": "open"])
            return
        }
        work.async {
            if self.link.session == nil { self.link.reopen() }     // iOS may have taken the sockets back
            self.link.whereIs()                                      // at home: learn the computer's addresses
            DispatchQueue.main.async { self.connect() }
        }
    }

    func background() {
        front = false
        retry?.cancel()
        scheduleFetch()
        guard !inCall, let session else { return }                   // a call goes on (background audio)
        session.hangUp()
    }

    func scheduleFetch() {
        let request = BGAppRefreshTaskRequest(identifier: Wife.fetchTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// iOS woke the app for a moment: fetch what she said meanwhile (the computer keeps it), show it, hang up.
    func fetch(_ task: BGAppRefreshTask) {
        scheduleFetch()
        DispatchQueue.main.async {
            guard self.paired, !self.front, self.session == nil else {
                task.setTaskCompleted(success: true)
                return
            }
            self.fetching = task
            task.expirationHandler = { DispatchQueue.main.async { self.fetched() } }
            self.work.async {
                self.link.reopen()
                DispatchQueue.main.async { self.connect() }
            }
            self.quietThenFetched(after: 25)
        }
    }

    private func quietThenFetched(after seconds: Double) {
        fetchQuiet?.cancel()
        let done = DispatchWorkItem { [weak self] in self?.fetched() }
        fetchQuiet = done
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: done)
    }

    private func fetched() {
        guard let task = fetching else { return }
        fetching = nil
        fetchQuiet?.cancel()
        if !front { session?.hangUp() }
        task.setTaskCompleted(success: true)
    }

    // ── the session ──

    func connect() {
        guard paired, session == nil, !connecting else { return }
        connecting = true
        let app = front ? "open" : "fetch"
        work.async {
            let made = self.link.dial(ping: 5) { session in
                session.onMessage = { message in DispatchQueue.main.async { self.heard(message) } }
                session.onAudio = { [weak self] seq, frame in self?.voice?.heard(seq, frame) }
                session.onClosed = { DispatchQueue.main.async { self.closed(session) } }
            }
            DispatchQueue.main.async {
                self.connecting = false
                guard let made else {
                    self.note = self.unreachable()
                    if self.fetching != nil { self.fetched() } else { self.retryLater() }
                    return
                }
                self.opened(made, app: app)
            }
        }
    }

    private func opened(_ session: Session, app: String) {
        self.session = session
        connected = true
        note = ""
        attempts = 0
        session.message(["type": "hello", "app": app])
        let ticker = DispatchSource.makeTimerSource(queue: work)
        ticker.schedule(deadline: .now(), repeating: .milliseconds(100))
        var ticks = 0
        ticker.setEventHandler { [weak self] in
            session.tick()
            ticks += 1
            guard ticks % 10 == 0, let voice = self?.voice else { return }
            let line = "发出 \(session.sent) 个包 · 收到 \(session.got) 个 · 麦克风 \(voice.micFrames) 帧 · "
                + "她的声音收到 \(voice.herIn) 帧、放出 \(voice.herOut) 帧"
            DispatchQueue.main.async { self?.stats = line }
        }
        ticker.resume()
        self.ticker = ticker
    }

    private func closed(_ session: Session) {
        guard self.session === session else { return }
        ticker?.cancel()
        ticker = nil
        self.session = nil
        link.ended()
        connected = false
        typing = ""
        if inCall { stopCall(tell: false) }
        if fetching != nil { fetched() } else { retryLater() }
    }

    private func retryLater() {
        guard front, paired else { return }
        attempts += 1
        let wait = [2.0, 5, 10, 30][min(attempts - 1, 3)]
        let again = DispatchWorkItem { [weak self] in self?.connect() }
        retry = again
        DispatchQueue.main.asyncAfter(deadline: .now() + wait, execute: again)
    }

    // ── what the computer says ──

    private func heard(_ message: [String: Any]) {
        if fetching != nil { quietThenFetched(after: 3) }
        switch message["type"] as? String {
        case "state":
            awake = message["awake"] as? Bool
            away = message["away"] as? Bool ?? false
            voiceprints = (message["voiceprint"] as? NSNumber)?.intValue ?? voiceprints
            if let name = message["name"] as? String, !name.isEmpty { her = name }
            if awake == true { resend() }
        case "her":
            herSaid(message)
        case "heard":
            guard let id = message["id"] as? String else { return }
            chat.update(id) {
                $0.text = message["text"] as? String ?? ""
                $0.heard = message["ok"] as? Bool ?? true
            }
            items = chat.items
        case "got":
            for id in message["ids"] as? [String] ?? [] {
                chat.update(id) { $0.pending = false }
            }
            items = chat.items
        case "reply":
            typing = message["final"] as? Bool == true ? "" : message["text"] as? String ?? ""
        case "cancel":
            typing = ""
        case "voiceprint":
            voiceprints = (message["count"] as? NSNumber)?.intValue ?? voiceprints
            enrolling = message["error"] as? String ?? "电脑记下了"
        case "here":
            work.async { self.link.remember(message) }
        case "card":
            keep.update { $0["card"] = message }
            refresh()
        default:
            break
        }
    }

    private func herSaid(_ message: [String: Any]) {
        guard let id = message["id"] as? String else { return }
        let text = message["text"] as? String ?? ""
        let voice = (message["opus"] as? String).flatMap(Crypto.unb64)
        let ms = (message["ms"] as? NSNumber)?.intValue ?? 0
        let new = !chat.has(id)
        if new {
            chat.add(Item(id: id, his: false, t: (message["t"] as? NSNumber)?.int64Value ?? Crypto.now(),
                          kind: voice == nil ? .text : .voice, text: text, ms: ms), voice: voice)
        } else if let voice {                                        // her voice for words already here
            chat.update(id, voice: voice) {
                $0.kind = .voice
                $0.ms = ms
            }
        }
        session?.message(["type": "got", "ids": [id]])
        typing = ""
        items = chat.items
        if !front, new {
            notify(text)
        } else if front, let voice, !inCall, voiceReplies || Date().timeIntervalSince(lastVoiceSent) < 120 {
            player.play(id, voice, rate: 48000)                      // he spoke to her, or wants her voice: it plays
        }
    }

    private func notify(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = her
        content.body = text
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content,
                                                                     trigger: nil))
    }

    // ── what he sends ──

    func type(_ text: String) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        post(Item(id: Crypto.b64(Crypto.bytes(8)), his: true, t: Crypto.now(), kind: .text, text: words, pending: true))
    }

    func sendVoice(_ recording: Data, ms: Int) {
        lastVoiceSent = Date()
        post(Item(id: Crypto.b64(Crypto.bytes(8)), his: true, t: Crypto.now(), kind: .voice, text: "", ms: ms, pending: true),
             voice: recording)
    }

    /// A photo, its longest side made at most 1600 pixels (protocol.md).
    func sendPhoto(_ image: UIImage, caption: String = "") {
        let scale = min(1, 1600 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let smaller = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = smaller.jpegData(compressionQuality: 0.8) else { return }
        post(Item(id: Crypto.b64(Crypto.bytes(8)), his: true, t: Crypto.now(), kind: .photo, text: caption, pending: true),
             photo: jpeg)
    }

    private func post(_ item: Item, voice: Data? = nil, photo: Data? = nil) {
        chat.add(item, voice: voice, photo: photo)
        items = chat.items
        if let session, awake == true {
            session.message(wire(item))
        } else if session == nil {
            connect()
        }
    }

    private func wire(_ item: Item) -> [String: Any] {
        var message: [String: Any] = ["id": item.id, "t": item.t]
        if voiceReplies { message["voice"] = true }
        switch item.kind {
        case .text:
            message["type"] = "say"
            message["text"] = item.text
        case .voice:
            message["type"] = "voice"
            message["ms"] = item.ms
            message["opus"] = Crypto.b64(chat.voice(item.id) ?? Data())
        case .photo:
            message["type"] = "photo"
            message["jpeg"] = Crypto.b64(chat.photo(item.id) ?? Data())
            if !item.text.isEmpty { message["text"] = item.text }
        }
        return message
    }

    /// His items the computer has not confirmed: sent again whenever she is awake on a new session.
    private func resend() {
        guard let session else { return }
        for item in chat.items where item.his && item.pending {
            session.message(wire(item))
        }
    }

    func play(_ item: Item) {
        if player.playing == item.id {
            player.stop()
            return
        }
        if let recording = chat.voice(item.id) { player.play(item.id, recording, rate: item.his ? 16000 : 48000) }
    }

    // ── a call ──

    func call() {
        guard let session else {
            note = "还没连上电脑，连上了再打"
            connect()
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else {
                    self.note = "没有麦克风权限就没法通话"
                    return
                }
                guard self.session === session, !self.inCall else { return }
                self.player.stop()
                let voice = Voice(session: session)
                voice.speaker = self.speaker
                do {
                    try voice.start()
                } catch {
                    self.note = "声音没能打开：\(error.localizedDescription)"
                }
                self.voice = voice
                self.inCall = true
                session.message(["type": "call", "on": true, "near": self.near])
            }
        }
    }

    func hangUp() { stopCall(tell: true) }

    private func stopCall(tell: Bool) {
        if tell { session?.message(["type": "call", "on": false]) }
        voice?.stop()
        voice = nil
        inCall = false
        stats = ""
        if !front, let session { session.hangUp() }
    }

    func setNear(_ near: Bool) {
        self.near = near
        if inCall { session?.message(["type": "call", "on": true, "near": near]) }
    }

    // ── his voiceprint, recorded on this phone ──

    func enroll(_ recording: Data) {
        guard let session else {
            enrolling = "还没连上电脑"
            return
        }
        enrolling = "电脑正在记…"
        session.message(["type": "enroll", "opus": Crypto.b64(recording)])
    }

    func clearVoiceprint() { session?.message(["type": "enroll", "clear": true]) }

    // ── pairing ──

    func pair(_ qr: String) {
        note = "正在和电脑配对…"
        work.async {
            let problem = self.link.pair(qr, phoneName: UIDevice.current.name)
            DispatchQueue.main.async {
                self.refresh()
                self.note = problem ?? ""
                if problem == nil {
                    self.session?.hangUp()
                    self.connect()
                }
            }
        }
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
