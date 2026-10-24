package io.wife.mobile.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import io.wife.mobile.link.Session
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** The phone as his microphone and her speaker during a call (protocol.md 声音的编码). The system's echo canceller keeps
 *  her voice from the speaker out of the microphone. */
class Voice(context: Context, private val session: Session) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val frames = ConcurrentHashMap<Int, ByteArray>()
    @Volatile private var running = false
    @Volatile private var next = -1
    @Volatile private var firstAt = 0L
    @Volatile private var lastSeq = 0
    @Volatile var speaker = true
        set(value) { field = value; if (running) route() }

    fun start() {
        running = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        thread(name = "microphone", isDaemon = true) { microphone() }
        thread(name = "her-voice", isDaemon = true) { play() }
    }

    fun stop() {
        running = false
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    /** A headset when there is one, else the loudspeaker or the earpiece as he chose. */
    private fun route() {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val headset = devices.firstOrNull {
                it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                                 AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
            }
            val wanted = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            (headset ?: devices.firstOrNull { it.type == wanted })?.let { audio.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker
        }
    }

    // ── his microphone: 16 kHz, 20 ms frames ──
    private fun microphone() {
        val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                         320 * 2 * 4)
        @Suppress("MissingPermission")                       // asked before the call starts
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT, size)
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.enabled = true
        val encoder = Opus.encoder(16000, 24000, true)
        val pcm = ShortArray(320)
        record.startRecording()
        while (running && !session.closed) {
            var got = 0
            while (got < pcm.size) {
                val n = record.read(pcm, got, pcm.size - got)
                if (n <= 0) break
                got += n
            }
            if (got == pcm.size) Opus.encode(encoder, pcm)?.let(session::audio)
        }
        record.stop()
        record.release()
        Opus.free(encoder, true)
    }

    // ── her voice: 48 kHz frames through a small jitter buffer ──
    fun heard(seq: Int, frame: ByteArray) {
        val playing = next
        if (playing >= 0 && ((seq - playing) and 0xFFFF) >= 0x8000) return          // too late
        if (frames.isEmpty()) firstAt = System.currentTimeMillis()
        lastSeq = seq
        frames[seq] = frame
    }

    private fun play() {
        val size = maxOf(AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                         960 * 2 * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val decoder = Opus.decoder(48000)
        track.play()
        var missing = 0
        while (running && !session.closed) {
            if (next < 0) {                                   // between sentences: wait for 80 ms of the next one
                if (frames.size >= 4 || (frames.isNotEmpty() && System.currentTimeMillis() - firstAt > 100)) {
                    val newest = lastSeq
                    next = frames.keys.maxBy { (newest - it) and 0xFFFF }        // the oldest one waiting
                } else {
                    Thread.sleep(5)
                    continue
                }
            }
            if (frames.size > 12) {                           // fell behind: skip ahead to 80 ms of buffer
                repeat(frames.size - 4) { frames.remove(next); next = (next + 1) and 0xFFFF }
            }
            val packet = frames.remove(next)
            val pcm = when {
                packet != null -> { missing = 0; Opus.decode(decoder, packet, 960) }
                ++missing <= 3 -> Opus.decode(decoder, null, 960)
                else -> { next = -1; missing = 0; continue }      // she finished the sentence
            }
            track.write(pcm, 0, pcm.size)                    // blocks: plays in real time
            next = (next + 1) and 0xFFFF
        }
        track.stop()
        track.release()
        Opus.free(decoder, false)
    }
}
