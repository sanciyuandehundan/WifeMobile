package io.wife.mobile.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** What the phone keeps between runs (Vault on the phone). */
interface Keep {
    fun load(): JSONObject
    fun update(change: (JSONObject) -> Unit)
}

/** The phone's two UDP ports (protocol.md): pairing, finding the computer, dialling, and the DHT when needed. */
class Link(private val keep: Keep) {
    private val v4 = DatagramSocket(null).apply { broadcast = true; bind(InetSocketAddress(0)) }
    private val v6: DatagramSocket? = try {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("::"), 0))
    } catch (e: IOException) {
        null
    }
    val dht = Dht(::send)
    @Volatile var call: Call? = null
    var onHere: (JSONObject) -> Unit = {}
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Pair<JSONObject, InetSocketAddress>>>()
    private val pairing = ConcurrentHashMap<String, CompletableDeferred<Pair<ByteArray, InetSocketAddress>>>()
    private val seen = Crypto.Seen()

    init {
        for (socket in listOfNotNull(v4, v6)) thread(name = "link-receive", isDaemon = true) { receive(socket) }
        keep.load().optJSONObject("nodes")?.let { saved ->
            for (address in saved.keys()) dht.nodes[address] = Crypto.unb64(saved.getString(address))
        }
    }

    val paired: Boolean get() = keep.load().has("key")
    fun here(): JSONObject = keep.load().optJSONObject("here") ?: JSONObject()

    fun send(data: ByteArray, to: InetSocketAddress) {
        val socket = if (to.address is Inet6Address) v6 else v4
        try {
            socket?.send(DatagramPacket(data, data.size, to))
        } catch (e: IOException) {                       // no route on this network: the other addresses may work
        }
    }

    private fun receive(socket: DatagramSocket) {
        val buffer = ByteArray(65536)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                return
            }
            val data = buffer.copyOf(packet.length)
            val from = packet.socketAddress as InetSocketAddress
            when {
                data.isEmpty() -> Unit
                data[0] == 'd'.code.toByte() -> dht.received(data, from)
                data.startsWith("WP1") && data.size > 35 -> pairing[Crypto.b64(data.copyOfRange(3, 35))]?.complete(data to from)
                data.startsWith("WM1") -> handshake(data, from)
                data.startsWith("WC") -> call?.takeIf { it.sid.contentEquals(data.copyOfRange(2, 6)) }?.received(data, from)
            }
        }
    }

    private fun ByteArray.startsWith(magic: String) =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    private fun head(dev: ByteArray) = "WM1".toByteArray() + dev

    private fun handshake(data: ByteArray, from: InetSocketAddress) {
        val saved = keep.load()
        if (!saved.has("key") || data.size < 11 + 28) return
        val dev = Crypto.unb64(saved.getString("dev"))
        if (!data.copyOfRange(3, 11).contentEquals(dev)) return
        val message = Crypto.open(Crypto.unb64(saved.getString("key")), data.copyOfRange(11, data.size), head(dev)) ?: return
        if (!seen.fresh(message)) return
        when (message.optString("type")) {
            "here" -> remember(message)
            "answer" -> waiting[message.optString("re")]?.complete(message to from)
        }
    }

    /** The computer said where it is. Chosen "回家重新配对": its outside addresses stay those from pairing. */
    fun remember(here: JSONObject) {
        keep.update { saved ->
            val old = saved.optJSONObject("here")
            if (old != null && old.optString("find") == "pair" && here.optString("type") != "paired") {
                here.put("away", old.optJSONArray("away") ?: JSONArray())
            }
            saved.put("here", here)
            here.optJSONArray("nodes")?.let { nodes ->
                for (i in 0 until nodes.length()) dht.nodes.putIfAbsent(nodes.getString(i), Crypto.bytes(20))
            }
            saved.put("nodes", JSONObject(dht.nodes.mapValues { Crypto.b64(it.value) }))
        }
        onHere(here)
    }

    // ── pairing ──

    /** Scans gave ``qr``; returns null when paired, else what went wrong in his words. */
    suspend fun pair(qr: String, phoneName: String): String? = withContext(Dispatchers.IO) {
        val query = try {
            URI(qr).rawQuery.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        } catch (e: Exception) {
            return@withContext "这不是 Wife 的配对码"
        }
        val pcPublic = Crypto.unb64(query["pc"] ?: return@withContext "这不是 Wife 的配对码")
        val secret = Crypto.unb64(query["s"] ?: return@withContext "这不是 Wife 的配对码")
        val (private, public) = Crypto.x25519()
        val head = "WP1".toByteArray() + public
        val request = head + Crypto.seal(Crypto.hk(secret, "pair".toByteArray()),
                                         Crypto.stamp(JSONObject().put("name", phoneName)), head)
        val reply = CompletableDeferred<Pair<ByteArray, InetSocketAddress>>()
        pairing[Crypto.b64(public)] = reply
        val targets = addresses(query["l"].orEmpty().split(",")) + BROADCAST
        val answer = repeatUntil(8, 700) { targets.forEach { send(request, it) }; reply }
        pairing.remove(Crypto.b64(public))
        answer ?: return@withContext "电脑没有回话：手机和电脑要连着同一个 WiFi，电脑上的配对码要还开着"
        val key = Crypto.linkKey(private, public, pcPublic, secret)
        val paired = Crypto.open(key, answer.first.copyOfRange(35, answer.first.size), head)
            ?: return@withContext "配对码不对，或者已经作废了"
        keep.update { saved ->
            for (name in saved.keys().asSequence().toList()) saved.remove(name)          // a new computer, a new start
            saved.put("key", Crypto.b64(key)).put("dev", paired.getString("dev"))
        }
        remember(paired)
        null
    }

    // ── finding the computer and dialling ──

    /** At home: ask where the computer is (its reply updates the addresses). */
    fun where() {
        val saved = keep.load()
        if (!saved.has("key")) return
        val dev = Crypto.unb64(saved.getString("dev"))
        val packet = head(dev) + Crypto.seal(Crypto.unb64(saved.getString("key")), Crypto.stamp(JSONObject().put("type", "where")), head(dev))
        (addresses(here().optJSONArray("lan")) + BROADCAST).forEach { send(packet, it) }
    }

    /** Dials the computer: the home network and its outside addresses at once; when that fails and it was chosen,
     *  asks BT's DHT once where the computer is now. Null when nothing answers. ``ready`` sets up the call before
     *  its first packet can arrive. */
    suspend fun dial(near: Boolean, ready: (Call) -> Unit = {}): Call? = withContext(Dispatchers.IO) {
        val saved = keep.load()
        if (!saved.has("key")) return@withContext null
        val key = Crypto.unb64(saved.getString("key"))
        val dev = Crypto.unb64(saved.getString("dev"))
        val sid = Crypto.bytes(4)
        val (private, ePhone) = Crypto.x25519()
        val offer = Crypto.stamp(JSONObject().put("type", "offer").put("sid", Crypto.b64(sid)).put("e", Crypto.b64(ePhone))
                                     .put("near", near))
        val packet = head(dev) + Crypto.seal(key, offer, head(dev))
        val reply = CompletableDeferred<Pair<JSONObject, InetSocketAddress>>()
        waiting[offer.getString("id")] = reply
        fun targets(here: JSONObject) = addresses(here.optJSONArray("lan")) + BROADCAST + addresses(here.optJSONArray("away"))
        var answer = repeatUntil(10, 500) { targets(here()).forEach { send(packet, it) }; reply }
        if (answer == null && here().optString("find") == "dht") {
            val found = lookUp(key)
            if (found != null) {
                remember(found)
                answer = repeatUntil(10, 500) { targets(found).forEach { send(packet, it) }; reply }
            }
        }
        waiting.remove(offer.getString("id"))
        val (message, from) = answer ?: return@withContext null
        val ePc = Crypto.unb64(message.getString("e"))
        val (toPc, toPhone) = Crypto.callKeys(private, ePc, key, sid, ePhone, ePc)
        Call(::send, sid, toPc, toPhone, from).also { ready(it); call = it }     // handlers in place before packets
    }

    /** The computer's latest here on the DHT (today's key, then yesterday's). */
    private suspend fun lookUp(key: ByteArray): JSONObject? {
        for (onDay in listOf(Crypto.day(), Crypto.day() - 1)) {
            val value = dht.get(Crypto.edPublic(Crypto.dhtSeed(key, onDay)), "pc".toByteArray()) ?: continue
            Crypto.open(key, value, "pc".toByteArray())?.let { return it }
        }
        return null
    }

    private suspend fun <T> repeatUntil(times: Int, everyMs: Long, attempt: () -> CompletableDeferred<T>): T? {
        repeat(times) {
            val result = withTimeoutOrNull(everyMs) { attempt().await() }
            if (result != null) return result
        }
        return null
    }

    private fun addresses(list: JSONArray?) = addresses((0 until (list?.length() ?: 0)).map { list!!.getString(it) })

    private fun addresses(list: List<String>) = list.filter { ':' in it }.mapNotNull {
        try {
            InetSocketAddress(InetAddress.getByName(it.substringBeforeLast(':').trim('[', ']')), it.substringAfterLast(':').toInt())
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val PORT = 18766
        private val BROADCAST = listOf(InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT))
    }
}
