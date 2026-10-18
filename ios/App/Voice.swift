import AVFoundation
import Copus
import WifeLink

/// The iPhone as his microphone and her speaker during a call (protocol.md 包). Voice processing (iOS's echo
/// canceller) keeps her voice from the speaker out of the microphone.
final class Voice {
    private let call: Call
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?
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
    private var missing = 0
    private var decoded = [Float](repeating: 0, count: 5760)   // her current frame, played from ``decodedAt`` on
    private var decodedCount = 0
    private var decodedAt = 0
    var speaker = true { didSet { route() } }
    private(set) var micFrames = 0                     // his frames sent, hers in and played (shown during a call)
    private(set) var herIn = 0
    private(set) var herOut = 0

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
        // iOS asks for her voice as fast as it plays: nothing to pace or wait for, and it carries on after a restart
        let source = AVAudioSourceNode(format: herFormat) { [weak self] silence, _, count, list in
            let out = UnsafeMutableAudioBufferListPointer(list)[0].mData!.assumingMemoryBound(to: Float.self)
            if (self?.fill(out, Int(count)) ?? 0) == 0 {
                out.update(repeating: 0, count: Int(count))
                silence.pointee = true
            }
            return noErr
        }
        self.source = source
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: herFormat)
        // iOS stops the engine whenever the audio setup changes (loudspeaker on or off, a headset plugged in):
        // start it again. On his first iPhone call it stopped right away and both ways went silent.
        changes = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine,
                                                         queue: nil) { [weak self] _ in self?.changed() }
        engine.prepare()
        try engine.start()
        running = true
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
    }

    func stop() {
        running = false
        if let changes { NotificationCenter.default.removeObserver(changes) }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
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
        herIn += 1
        if next >= 0, (seq - next) & 0xFFFF >= 0x8000 { return }          // too late
        if frames.isEmpty { firstAt = Date() }
        lastSeq = seq
        frames[seq] = frame
    }

    /// On the audio thread: as much of her voice as iOS asks for, or 0 while she is quiet.
    private func fill(_ out: UnsafeMutablePointer<Float>, _ count: Int) -> Int {
        var done = 0
        while done < count {
            if decodedAt == decodedCount, !decodeNext() { break }
            let take = min(count - done, decodedCount - decodedAt)
            decoded.withUnsafeBufferPointer { out.advanced(by: done).update(from: $0.baseAddress! + decodedAt, count: take) }
            done += take
            decodedAt += take
        }
        if done > 0 { out.advanced(by: done).update(repeating: 0, count: count - done) }
        return done
    }

    private func decodeNext() -> Bool {
        lock.lock()
        if next < 0 {                                   // between sentences: wait for 80 ms of the next one
            guard frames.count >= 4 || (!frames.isEmpty && Date().timeIntervalSince(firstAt) > 0.1) else {
                lock.unlock()
                return false
            }
            let newest = lastSeq
            next = frames.keys.max { (newest - $0) & 0xFFFF < (newest - $1) & 0xFFFF }!   // the oldest one waiting
        }
        if frames.count > 12 {                          // fell behind: skip ahead to 80 ms of buffer
            while frames.count > 4 {
                frames.removeValue(forKey: next)
                next = (next + 1) & 0xFFFF
            }
        }
        let packet = frames.removeValue(forKey: next)
        if packet == nil {
            missing += 1
            if missing > 3 {                            // she finished the sentence
                next = -1
                missing = 0
                lock.unlock()
                return false
            }
        } else {
            missing = 0
        }
        next = (next + 1) & 0xFFFF
        lock.unlock()
        let n = decoded.withUnsafeMutableBufferPointer { pcm -> Int32 in
            guard let packet else { return opus_decode_float(decoder, nil, 0, pcm.baseAddress!, 960, 0) }
            return packet.withUnsafeBytes {
                opus_decode_float(decoder, $0.bindMemory(to: UInt8.self).baseAddress, Int32(packet.count),
                                  pcm.baseAddress!, Int32(pcm.count), 0)
            }
        }
        decodedCount = max(0, Int(n))
        decodedAt = 0
        if packet != nil, n > 0 { herOut += 1 }
        return decodedCount > 0
    }
}
