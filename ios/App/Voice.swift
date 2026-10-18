import AVFoundation
import Copus
import WifeLink

/// The iPhone as his microphone and her speaker during a call (protocol.md 包). Voice processing (iOS's echo
/// canceller) keeps her voice from the speaker out of the microphone.
final class Voice {
    private let call: Call
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let herFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false)!
    private let micFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: false)!
    private let encoder: OpaquePointer
    private let decoder: OpaquePointer
    private var converter: AVAudioConverter?
    private var pending: [Int16] = []
    private let lock = NSLock()
    private var frames: [Int: Data] = [:]
    private var next = -1
    private var lastSeq = 0
    private var firstAt = Date()
    private var running = false
    private let ahead = DispatchSemaphore(value: 3)       // 60 ms of her voice queued in the player at most
    var speaker = true { didSet { route() } }
    private(set) var micFrames = 0                     // his frames sent (shown during a call)

    init(call: Call) {
        self.call = call
        var error: Int32 = 0
        encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error)!
        decoder = opus_decoder_create(48000, 1, &error)!
    }

    deinit {
        opus_encoder_destroy(encoder)
        opus_decoder_destroy(decoder)
    }

    struct Problem: LocalizedError {
        let errorDescription: String?
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try session.setActive(true)
        route()                        // before the engine starts: a route change stops a running engine
        try engine.inputNode.setVoiceProcessingEnabled(true)
        try listen()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: herFormat)
        // iOS stops the engine whenever the audio setup changes (loudspeaker on or off, a headset plugged in):
        // start it again. On his first iPhone call it stopped right away and both ways went silent.
        changes = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine,
                                                         queue: nil) { [weak self] _ in self?.changed() }
        engine.prepare()
        try engine.start()
        player.play()
        running = true
        Thread { [weak self] in self?.play() }.start()
    }

    private var changes: NSObjectProtocol?
    private let setup = DispatchQueue(label: "wife.voice")

    /// Taps the microphone in whatever format it has now (a Bluetooth headset records at a different rate).
    private func listen() throws {
        engine.inputNode.removeTap(onBus: 0)
        let input = engine.inputNode.outputFormat(forBus: 0)
        guard input.sampleRate > 0, let converter = AVAudioConverter(from: input, to: micFormat) else {
            throw Problem(errorDescription: "麦克风的格式转不过来（\(input)）")
        }
        self.converter = converter
        pending = []
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: input) { [weak self] buffer, _ in self?.heardHim(buffer) }
    }

    private func changed() {
        setup.async { [weak self] in                     // not inside the notification: tearing down there can deadlock
            guard let self, self.running else { return }
            try? self.listen()
            self.resume()
        }
    }

    private func resume() {
        guard running, !engine.isRunning else { return }
        try? engine.start()
        player.play()
    }

    func stop() {
        running = false
        if let changes { NotificationCenter.default.removeObserver(changes) }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        ahead.signal()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// A headset when there is one, else the loudspeaker or the earpiece as he chose.
    private func route() {
        let session = AVAudioSession.sharedInstance()
        let headset = session.currentRoute.outputs.contains {
            [.headphones, .bluetoothHFP, .bluetoothA2DP, .bluetoothLE, .usbAudio].contains($0.portType) }
        try? session.overrideOutputAudioPort(speaker && !headset ? .speaker : .none)
        resume()
    }

    // ── his microphone: 16 kHz, 20 ms frames ──

    private func heardHim(_ buffer: AVAudioPCMBuffer) {
        guard let converter, !call.closed else { return }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16000 / buffer.format.sampleRate) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: micFormat, frameCapacity: capacity) else { return }
        var given = false
        converter.convert(to: out, error: nil) { _, status in
            if given {
                status.pointee = .noDataNow
                return nil
            }
            given = true
            status.pointee = .haveData
            return buffer
        }
        pending += UnsafeBufferPointer(start: out.int16ChannelData![0], count: Int(out.frameLength))
        var packet = [UInt8](repeating: 0, count: 1500)
        while pending.count >= 320 {
            let n = pending.withUnsafeBufferPointer { opus_encode(encoder, $0.baseAddress!, 320, &packet, 1500) }
            if n > 0 {
                call.sendAudio(Data(packet[0..<Int(n)]))
                micFrames += 1
            }
            pending.removeFirst(320)
        }
    }

    // ── her voice: 48 kHz frames through a small jitter buffer ──

    func heard(_ seq: Int, _ frame: Data) {
        lock.lock()
        defer { lock.unlock() }
        if next >= 0, (seq - next) & 0xFFFF >= 0x8000 { return }          // too late
        if frames.isEmpty { firstAt = Date() }
        lastSeq = seq
        frames[seq] = frame
    }

    private func play() {
        var missing = 0
        while running, !call.closed {
            lock.lock()
            if next < 0 {                                   // between sentences: wait for 80 ms of the next one
                guard frames.count >= 4 || (!frames.isEmpty && Date().timeIntervalSince(firstAt) > 0.1) else {
                    lock.unlock()
                    usleep(5000)
                    continue
                }
                let newest = lastSeq
                next = frames.keys.max { (newest - $0) & 0xFFFF < (newest - $1) & 0xFFFF }!   // the oldest one waiting
            }
            while frames.count > 12 {                       // fell behind: skip ahead to 80 ms of buffer
                frames.removeValue(forKey: next)
                next = (next + 1) & 0xFFFF
                if frames.count <= 4 { break }
            }
            let packet = frames.removeValue(forKey: next)
            lock.unlock()
            guard let buffer = AVAudioPCMBuffer(pcmFormat: herFormat, frameCapacity: 960) else { continue }
            var n: Int32 = 0
            if let packet {
                missing = 0
                n = packet.withUnsafeBytes {
                    opus_decode_float(decoder, $0.bindMemory(to: UInt8.self).baseAddress, Int32(packet.count),
                                      buffer.floatChannelData![0], 960, 0)
                }
            } else {
                missing += 1
                if missing > 3 {                           // she finished the sentence
                    lock.lock()
                    next = -1
                    lock.unlock()
                    missing = 0
                    continue
                }
                n = opus_decode_float(decoder, nil, 0, buffer.floatChannelData![0], 960, 0)
            }
            buffer.frameLength = AVAudioFrameCount(max(0, n))
            ahead.wait()                                     // plays in real time: at most three frames ahead
            player.scheduleBuffer(buffer) { [weak self] in self?.ahead.signal() }
            lock.lock()
            next = (next + 1) & 0xFFFF
            lock.unlock()
        }
    }
}
