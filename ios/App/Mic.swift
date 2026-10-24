import AVFoundation
import Copus

/// His voice the way protocol.md has it, from whatever the microphone gives: 16 kHz mono Opus, 20 ms frames.
final class MicEncoder {
    private let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: false)!
    private let encoder: OpaquePointer
    private var converter: AVAudioConverter?
    private var pending: [Int16] = []
    private(set) var level: Float = 0                  // how loud the last buffer was, 0…1 (for the screen)

    struct Problem: LocalizedError {
        let errorDescription: String?
    }

    init() {
        var error: Int32 = 0
        encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error)!
    }

    deinit { opus_encoder_destroy(encoder) }

    /// The microphone's format now (a Bluetooth headset records at a different rate).
    func reset(_ input: AVAudioFormat) throws {
        guard input.sampleRate > 0, let converter = AVAudioConverter(from: input, to: format) else {
            throw Problem(errorDescription: "麦克风的格式转不过来（\(input)）")
        }
        self.converter = converter
        pending = []
    }

    /// One buffer from the microphone → the whole frames it completes.
    func encode(_ buffer: AVAudioPCMBuffer) -> [Data] {
        guard let converter else { return [] }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16000 / buffer.format.sampleRate) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return [] }
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
        let samples = UnsafeBufferPointer(start: out.int16ChannelData![0], count: Int(out.frameLength))
        if !samples.isEmpty {
            level = min(1, sqrt(samples.reduce(Float(0)) { $0 + Float($1) * Float($1) } / Float(samples.count)) / 6000)
        }
        pending += samples
        var frames: [Data] = []
        var packet = [UInt8](repeating: 0, count: 1500)
        while pending.count >= 320 {
            let n = pending.withUnsafeBufferPointer { opus_encode(encoder, $0.baseAddress!, 320, &packet, 1500) }
            if n > 0 { frames.append(Data(packet[0..<Int(n)])) }
            pending.removeFirst(320)
        }
        return frames
    }
}

/// Records a voice message, or a sentence for his voiceprint, the way a call hears him (iOS's voice processing on),
/// so the computer's voiceprint check hears the same voice in all three.
final class Recorder: ObservableObject {
    @Published private(set) var recording = false
    @Published private(set) var level: Float = 0
    @Published private(set) var started = Date()
    private var engine: AVAudioEngine?
    private let mic = MicEncoder()
    private var frames: [Data] = []
    private let lock = NSLock()

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try session.setActive(true)
        let engine = AVAudioEngine()
        try engine.inputNode.setVoiceProcessingEnabled(true)
        _ = engine.mainMixerNode                          // the voice processor runs with its output side on
        let input = engine.inputNode.outputFormat(forBus: 0)
        try mic.reset(input)
        lock.lock()
        frames = []
        lock.unlock()
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: input) { [weak self] buffer, _ in
            guard let self else { return }
            let made = self.mic.encode(buffer)
            self.lock.lock()
            self.frames += made
            self.lock.unlock()
            let level = self.mic.level
            DispatchQueue.main.async { self.level = level }
        }
        engine.prepare()
        try engine.start()
        self.engine = engine
        started = Date()
        recording = true
    }

    /// What was recorded: (frames packed as in a message, ms).
    func stop() -> (Data, Int) {
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
        recording = false
        level = 0
        lock.lock()
        defer { lock.unlock() }
        let made = frames
        frames = []
        return (Recording.pack(made), made.count * 20)
    }
}

/// Plays one voice message at a time: his recorded at 16 kHz, hers at 48 kHz.
final class Player: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var playing: String?
    private var player: AVAudioPlayer?

    func play(_ id: String, _ recording: Data, rate: Int32) {
        stop()
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .defaultToSpeaker])
        try? session.setActive(true)
        guard let player = try? AVAudioPlayer(data: Recording.wav(Recording.decode(recording, rate: rate), rate: Int(rate)))
        else { return }
        player.delegate = self
        player.play()
        self.player = player
        playing = id
    }

    func stop() {
        player?.stop()
        player = nil
        playing = nil
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        DispatchQueue.main.async {
            if self.player === player { self.stop() }
        }
    }
}
