import AVFoundation

/// Silence played while Wife is in the background, so iOS keeps the app running and the session to the computer open:
/// her words pop up at once, as on Android, without Apple's push service (the user, 2026-10-25; it costs battery).
/// Mixed with other apps' sound, so his music goes on. Swiped away or after a restart, he opens Wife once again.
final class Stay {
    private let engine = AVAudioEngine()
    private var wanted = false

    init() {
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let silence = AVAudioSourceNode(format: format) { isSilence, _, _, buffers in
            for buffer in UnsafeMutableAudioBufferListPointer(buffers) {
                if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) }
            }
            isSilence.pointee = true
            return noErr
        }
        engine.attach(silence)
        engine.connect(silence, to: engine.mainMixerNode, format: format)
        let center = NotificationCenter.default
        // a phone call or a route change stops the engine: once it is over, go on
        center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let ended = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.ended.rawValue
            if ended { self?.play() }
        }
        center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            self?.play()
        }
    }

    func start() {
        wanted = true
        play()
    }

    func stop() {
        wanted = false
        engine.stop()
    }

    private func play() {
        guard wanted, !engine.isRunning else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, options: [.mixWithOthers])
        try? session.setActive(true)
        try? engine.start()
    }
}
