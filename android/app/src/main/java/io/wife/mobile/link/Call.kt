package io.wife.mobile.link

import org.bouncycastle.crypto.InvalidCipherTextException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/** One call's packets (protocol.md 通话), the same as pc/mobile/call.py. Thread-safe: packets arrive on the
 *  socket threads, audio leaves from the microphone thread. */
class Call(
    private val send: (ByteArray, InetSocketAddress) -> Unit,
    val sid: ByteArray,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    @Volatile var address: InetSocketAddress,
) {
    var onMessage: (JSONObject) -> Unit = {}
    var onAudio: (Int, ByteArray) -> Unit = { _, _ -> }
    var onClosed: () -> Unit = {}

    private var n = 0L
    private var top = 0L
    private val recent = HashSet<Long>()
    private var nextOut = 0
    private val unacked = LinkedHashMap<Int, Pair<ByteArray, Long>>()
    private var nextIn = 1
    private val early = HashMap<Int, ByteArray>()
    private val joined = ByteArrayOutputStream()
    private var audioSeq = 0
    @Volatile var heard = System.currentTimeMillis()
    @Volatile var closed = false

    private fun nonce(counter: ByteArray) = ByteArray(4) + counter

    @Synchronized
    private fun packet(kind: Int, body: ByteArray = ByteArray(0)) {
        if (closed) return
        n++
        val header = "WC".toByteArray() + sid + ByteBuffer.allocate(8).putLong(n).array()
        send(header + Crypto.aead(sendKey, nonce(header.copyOfRange(6, 14)), byteArrayOf(kind.toByte()) + body,
                                  header, true), address)
    }

    fun ping() = packet(PING, ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array())

    fun audio(frame: ByteArray) {
        audioSeq = (audioSeq + 1) and 0xFFFF
        packet(AUDIO, byteArrayOf((audioSeq shr 8).toByte(), audioSeq.toByte()) + frame)
    }

    /** Reliable and in order; long messages go in 1000-byte pieces. */
    fun message(message: JSONObject) = message(message.toString())

    @Synchronized
    fun message(json: String) {
        val data = json.toByteArray()
        val pieces = if (data.isEmpty()) listOf(data) else (data.indices step PIECE).map {
            data.copyOfRange(it, minOf(it + PIECE, data.size)) }
        pieces.forEachIndexed { i, piece ->
            nextOut++
            val body = ByteBuffer.allocate(4).putInt(nextOut).array() + byteArrayOf(if (i < pieces.size - 1) 1 else 0) + piece
            unacked[nextOut] = body to System.currentTimeMillis()
            packet(MESSAGE, body)
        }
    }

    fun hangUp() {
        packet(BYE)
        close()
    }

    fun close() {
        if (!closed) {
            closed = true
            onClosed()
        }
    }

    fun received(data: ByteArray, from: InetSocketAddress) {
        if (data.size < 31 || closed) return
        val header = data.copyOf(14)
        val counter = ByteBuffer.wrap(data, 6, 8).long
        val plain: ByteArray
        synchronized(this) {
            if (counter <= top - 64 || counter in recent) return
            plain = try {
                Crypto.aead(recvKey, nonce(header.copyOfRange(6, 14)), data.copyOfRange(14, data.size), header, false)
            } catch (e: InvalidCipherTextException) {
                return
            }
            top = maxOf(top, counter)
            recent.add(counter)
            recent.removeAll { it <= top - 64 }
        }
        address = from
        heard = System.currentTimeMillis()
        val body = plain.copyOfRange(1, plain.size)
        when (plain[0].toInt()) {
            PING -> packet(PONG, body)
            AUDIO -> onAudio(((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF), body.copyOfRange(2, body.size))
            MESSAGE -> deliver(ByteBuffer.wrap(body, 0, 4).int, body.copyOfRange(4, body.size))
            ACK -> synchronized(this) { val upto = ByteBuffer.wrap(body, 0, 4).int; unacked.keys.removeAll { it <= upto } }
            BYE -> close()
        }
    }

    private fun deliver(seq: Int, piece: ByteArray) {
        val whole = mutableListOf<JSONObject>()
        synchronized(this) {
            if (seq >= nextIn) early[seq] = piece
            while (true) {
                val next = early.remove(nextIn) ?: break
                nextIn++
                joined.write(next, 1, next.size - 1)
                if (next[0].toInt() == 0) {
                    whole += JSONObject(joined.toString(Charsets.UTF_8.name()))
                    joined.reset()
                }
            }
        }
        packet(ACK, ByteBuffer.allocate(4).putInt(nextIn - 1).array())
        whole.forEach(onMessage)
    }

    /** Call every 100 ms: resends what wasn't confirmed, pings every 5 s, hangs up after 20 s of silence. */
    fun tick(now: Long = System.currentTimeMillis()) {
        if (now - heard > DEAD_MS) return close()
        val again = synchronized(this) {
            unacked.entries.filter { now - it.value.second > RESEND_MS }.map { entry ->
                entry.setValue(entry.value.first to now)
                entry.value.first
            }
        }
        again.forEach { packet(MESSAGE, it) }
        if (now - lastPing > KEEPALIVE_MS) {
            lastPing = now
            ping()
        }
    }

    private var lastPing = 0L

    companion object {
        const val PING = 1; const val PONG = 2; const val AUDIO = 3; const val MESSAGE = 4; const val ACK = 5; const val BYE = 6
        const val PIECE = 1000
        const val KEEPALIVE_MS = 5000L; const val DEAD_MS = 20000L; const val RESEND_MS = 300L
    }
}
