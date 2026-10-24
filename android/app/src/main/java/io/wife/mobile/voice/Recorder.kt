package io.wife.mobile.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** A recording in a message (protocol.md 声音的编码): each Opus frame after its 2-byte length. */
object Recording {
    fun pack(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (frame in frames) {
            out.write(frame.size shr 8)
            out.write(frame.size and 0xFF)
            out.write(frame)
        }
        return out.toByteArray()
    }

    fun unpack(data: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var at = 0
        while (at + 2 <= data.size) {
            val size = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
            frames += data.copyOfRange(at + 2, minOf(at + 2 + size, data.size))
            at += 2 + size
        }
        return frames
    }
}

/** Records a voice message, or a sentence for his voiceprint, the way a call hears him (the call's microphone with
 *  the system's echo canceller), so the computer's voiceprint check hears the same voice in all three. */
class Recorder {
    val level = MutableStateFlow(0f)
    @Volatile var recording = false
        private set
    var started = 0L
        private set
    private val frames = mutableListOf<ByteArray>()
    private var worker: Thread? = null

    @Suppress("MissingPermission")                           // asked before he can hold the button
    fun start() {
        if (recording) return
        recording = true
        started = System.currentTimeMillis()
        synchronized(frames) { frames.clear() }
        worker = thread(name = "recorder", isDaemon = true) {
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                             320 * 2 * 4)
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                                     AudioFormat.ENCODING_PCM_16BIT, size)
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.enabled = true
            val encoder = Opus.encoder(16000, 24000, true)
            val pcm = ShortArray(320)
            record.startRecording()
            while (recording) {
                var got = 0
                while (got < pcm.size) {
                    val n = record.read(pcm, got, pcm.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < pcm.size) continue
                level.value = minOf(1f, sqrt(pcm.fold(0f) { sum, s -> sum + s.toFloat() * s } / pcm.size) / 6000f)
                Opus.encode(encoder, pcm)?.let { synchronized(frames) { frames += it } }
            }
            record.stop()
            record.release()
            Opus.free(encoder, true)
        }
    }

    /** What was recorded: (frames packed as in a message, ms). */
    fun stop(): Pair<ByteArray, Int> {
        recording = false
        worker?.join(500)
        level.value = 0f
        val made = synchronized(frames) { frames.toList().also { frames.clear() } }
        return Recording.pack(made) to made.size * 20
    }
}

/** Plays one voice message at a time: his recorded at 16 kHz, hers at 48 kHz. */
object Player {
    val playing = MutableStateFlow<String?>(null)
    @Volatile private var track: AudioTrack? = null

    fun play(id: String, recording: ByteArray, rate: Int) {
        stop()
        playing.value = id
        thread(name = "player", isDaemon = true) {
            val decoder = Opus.decoder(rate)
            val samples = Recording.unpack(recording).map { Opus.decode(decoder, it, rate / 50) }
            Opus.free(decoder, false)
            val pcm = ShortArray(samples.sumOf { it.size }).also { out ->
                var at = 0
                for (part in samples) { part.copyInto(out, at); at += part.size }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(pcm.size * 2, 4096))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            this.track = track
            if (playing.value != id) return@thread track.release()
            track.play()
            Thread.sleep(pcm.size * 1000L / rate + 200)
            if (this.track === track) {
                this.track = null
                playing.value = null
            }
            track.release()
        }
    }

    fun stop() {
        track?.let {
            track = null
            try { it.stop() } catch (e: IllegalStateException) {}
        }
        playing.value = null
    }
}
