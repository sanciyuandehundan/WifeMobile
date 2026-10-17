package io.wife.mobile.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** BitTorrent's DHT as a read-only node (BEP 43): the phone only looks up where the computer is, and only when it
 *  can't reach it. It never asks BT's entry nodes; it starts from the nodes the computer gave it. */
class Dht(private val send: (ByteArray, InetSocketAddress) -> Unit) {
    private val id = Crypto.bytes(20)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any>>>()
    /** "ip:port" → node id, nodes that answered lately (saved by the caller). */
    val nodes = ConcurrentHashMap<String, ByteArray>()

    fun received(data: ByteArray, from: InetSocketAddress) {
        val message = try { Bencode.decode(data) as Map<*, *> } catch (e: Exception) { return }
        val tid = (message["t"] as? ByteArray)?.let { String(it, Charsets.ISO_8859_1) } ?: return
        val kind = (message["y"] as? ByteArray)?.let { String(it) }
        if (kind != "r" && kind != "e") return
        @Suppress("UNCHECKED_CAST")
        pending.remove(tid)?.complete(message as Map<String, Any>)
    }

    private suspend fun query(to: InetSocketAddress, method: String, args: Map<String, Any>): Map<*, *>? {
        val tid = String(Crypto.bytes(2), Charsets.ISO_8859_1)
        val reply = CompletableDeferred<Map<String, Any>>()
        pending[tid] = reply
        send(Bencode.encode(mapOf("t" to tid, "y" to "q", "q" to method, "ro" to 1L, "a" to mapOf("id" to id) + args)),
             to)
        val message = withTimeoutOrNull(2000) { reply.await() }
        pending.remove(tid)
        val body = message?.get("r") as? Map<*, *> ?: return null
        val nodeId = body["id"] as? ByteArray ?: return null
        if (nodeId.size == 20) nodes["${to.address.hostAddress}:${to.port}"] = nodeId
        return body
    }

    private fun distance(a: ByteArray, b: ByteArray): ByteArray = ByteArray(20) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private val closer = Comparator<ByteArray> { a, b ->
        for (i in 0 until 20) {
            val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (c != 0) return@Comparator c
        }
        0
    }

    private fun parseNodes(data: ByteArray): List<Pair<ByteArray, InetSocketAddress>> =
        (0..data.size - 26 step 26).map { i ->
            val port = ((data[i + 24].toInt() and 0xFF) shl 8) or (data[i + 25].toInt() and 0xFF)
            data.copyOfRange(i, i + 20) to InetSocketAddress(InetAddress.getByAddress(data.copyOfRange(i + 20, i + 24)), port)
        }

    private fun usable(address: InetSocketAddress): Boolean {
        val ip = address.address as? Inet4Address ?: return false
        return address.port != 0 && !(ip.isSiteLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
            ip.isAnyLocalAddress || ip.isMulticastAddress)
    }

    /** Walk towards target with eight queries in flight; the replies of the nodes that answered. */
    private suspend fun lookup(target: ByteArray, method: String, args: Map<String, Any>): List<Map<*, *>> = coroutineScope {
        val known = HashMap<InetSocketAddress, ByteArray>()
        for ((address, nodeId) in nodes) {
            val (host, port) = address.substringBeforeLast(':') to address.substringAfterLast(':').toInt()
            known[InetSocketAddress(host, port)] = nodeId
        }
        val asked = HashSet<InetSocketAddress>()
        val failed = HashSet<InetSocketAddress>()
        val answers = HashMap<InetSocketAddress, Map<*, *>>()
        val results = Channel<Pair<InetSocketAddress, Map<*, *>?>>(Channel.UNLIMITED)
        var flying = 0
        while (true) {
            val ranked = known.keys.filter { it !in failed }.sortedWith { a, b ->
                closer.compare(distance(known[a]!!, target), distance(known[b]!!, target)) }.take(8)
            if (ranked.isNotEmpty() && ranked.all { it in answers }) break
            for (address in ranked.filter { it !in asked }.take(8 - flying)) {
                asked += address
                flying++
                launch { results.send(address to query(address, method, args)) }
            }
            if (flying == 0) break
            val (address, reply) = results.receive()
            flying--
            if (reply == null) {
                failed += address
                nodes.remove("${address.address.hostAddress}:${address.port}")
                continue
            }
            known[address] = reply["id"] as ByteArray
            answers[address] = reply
            (reply["nodes"] as? ByteArray)?.let { data ->
                for ((nodeId, found) in parseNodes(data)) if (found !in known && usable(found)) known[found] = nodeId
            }
        }
        coroutineContext.cancelChildren()
        answers.values.toList()
    }

    /** The newest correctly signed value of a BEP 44 mutable item. */
    suspend fun get(public: ByteArray, salt: ByteArray): ByteArray? {
        val target = MessageDigest.getInstance("SHA-1").digest(public + salt)
        var best: Pair<Long, ByteArray>? = null
        for (reply in lookup(target, "get", mapOf("target" to target))) {
            val key = reply["k"] as? ByteArray ?: continue
            val value = reply["v"] as? ByteArray ?: continue
            val seq = reply["seq"] as? Long ?: continue
            val signature = reply["sig"] as? ByteArray ?: continue
            if (!key.contentEquals(public) || !Crypto.edVerify(public, signed(salt, seq, value), signature)) continue
            if (best == null || seq > best.first) best = seq to value
        }
        return best?.second
    }

    companion object {
        fun signed(salt: ByteArray, seq: Long, value: ByteArray): ByteArray =
            (if (salt.isNotEmpty()) "4:salt".toByteArray() + Bencode.encode(salt) else ByteArray(0)) +
                "3:seq".toByteArray() + Bencode.encode(seq) + "1:v".toByteArray() + Bencode.encode(value)
    }
}
