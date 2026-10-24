package io.wife.mobile.link

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** The primitives both ends share (protocol.md 记号), the same as pc/mobile/crypto.py. */
object Crypto {
    private val SALT = "wife-mobile/1".toByteArray()
    private const val FRESH_MS = 5 * 60 * 1000L
    val random = SecureRandom()

    fun hk(ikm: ByteArray, info: ByteArray, n: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, SALT, info))
        return ByteArray(n).also { generator.generateBytes(it, 0, n) }
    }

    fun b64(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun aead(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(data.size))
        val n = cipher.processBytes(data, 0, data.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    fun seal(key: ByteArray, json: String, aad: ByteArray, nonce: ByteArray = bytes(12)): ByteArray =
        nonce + aead(key, nonce, json.toByteArray(), aad, true)

    fun seal(key: ByteArray, message: JSONObject, aad: ByteArray): ByteArray = seal(key, message.toString(), aad)

    /** null when the key or the data is wrong. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): JSONObject? = try {
        JSONObject(String(aead(key, sealed.copyOf(12), sealed.copyOfRange(12, sealed.size), aad, false)))
    } catch (e: InvalidCipherTextException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun stamp(message: JSONObject): JSONObject =
        message.put("t", System.currentTimeMillis()).put("id", b64(bytes(8)))

    /** Drops stale or replayed messages. */
    class Seen {
        private val ids = HashMap<String, Long>()

        @Synchronized
        fun fresh(message: JSONObject): Boolean {
            val now = System.currentTimeMillis()
            val id = message.optString("id")
            if (Math.abs(now - message.optLong("t")) > FRESH_MS || id.isEmpty() || id in ids) return false
            ids.entries.removeAll { now - it.value > 2 * FRESH_MS }
            ids[id] = message.optLong("t")
            return true
        }
    }

    fun x25519(): Pair<ByteArray, ByteArray> {
        val private = ByteArray(32).also { X25519.generatePrivateKey(random, it) }
        return private to ByteArray(32).also { X25519.generatePublicKey(private, 0, it, 0) }
    }

    fun x25519Public(private: ByteArray) = ByteArray(32).also { X25519.generatePublicKey(private, 0, it, 0) }

    fun agree(private: ByteArray, peer: ByteArray): ByteArray =
        ByteArray(32).also { require(X25519.calculateAgreement(private, 0, peer, 0, it, 0)) { "bad key" } }

    fun linkKey(phonePrivate: ByteArray, phonePublic: ByteArray, pcPublic: ByteArray, secret: ByteArray) =
        hk(agree(phonePrivate, pcPublic) + secret, "link".toByteArray() + pcPublic + phonePublic)

    /** (phone→computer, computer→phone) */
    fun sessionKeys(private: ByteArray, peer: ByteArray, link: ByteArray, sid: ByteArray, ePhone: ByteArray,
                 ePc: ByteArray): Pair<ByteArray, ByteArray> {
        val keys = hk(agree(private, peer) + link, "call".toByteArray() + sid + ePhone + ePc, 64)
        return keys.copyOf(32) to keys.copyOfRange(32, 64)
    }

    fun day(at: Long = System.currentTimeMillis()) = (at / 1000 / 86400).toInt()

    /** The day's Ed25519 key for the DHT: the seed is the private key. */
    fun dhtSeed(link: ByteArray, onDay: Int) =
        hk(link, "dht".toByteArray() + byteArrayOf((onDay ushr 24).toByte(), (onDay ushr 16).toByte(),
                                                 (onDay ushr 8).toByte(), onDay.toByte()))

    fun edPublic(seed: ByteArray) = ByteArray(32).also { Ed25519.generatePublicKey(seed, 0, it, 0) }
    fun edSign(seed: ByteArray, message: ByteArray) =
        ByteArray(64).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }
    fun edVerify(public: ByteArray, message: ByteArray, signature: ByteArray) =
        signature.size == 64 && Ed25519.verify(signature, 0, public, 0, message, 0, message.size)
}
