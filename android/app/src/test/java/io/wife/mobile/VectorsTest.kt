package io.wife.mobile

import io.wife.mobile.brain.Prompt
import io.wife.mobile.brain.ReplyParser
import io.wife.mobile.brain.Said
import io.wife.mobile.link.Bencode
import io.wife.mobile.link.Call
import io.wife.mobile.link.Crypto
import io.wife.mobile.link.Dht
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.time.ZoneId
import java.time.ZonedDateTime

/** The phone must produce the same bytes as the computer (pc/tests/vectors.json, made by pc/tests/vectors.py). */
class VectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("vectors.json")!!.readText())
    private fun JSONObject.hex(key: String) = getString(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun hkdf() {
        val h = v.getJSONObject("hk")
        assertEquals(h.getString("out"), Crypto.hk(h.hex("ikm"), h.hex("info")).hex())
    }

    @Test
    fun sealedMessagesMatchAndOpen() {
        val s = v.getJSONObject("seal")
        val sealed = Crypto.seal(s.hex("key"), s.getString("json"), s.hex("aad"), s.hex("nonce"))
        assertEquals(s.getString("out"), sealed.hex())
        assertEquals("where", Crypto.open(s.hex("key"), sealed, s.hex("aad"))!!.getString("type"))
        assertEquals(null, Crypto.open(s.hex("key"), sealed, "WP1".toByteArray()))
    }

    @Test
    fun linkKeyFromThePairingCode() {
        val l = v.getJSONObject("link")
        assertEquals(l.getString("phone_public"), Crypto.x25519Public(l.hex("phone_private")).hex())
        assertEquals(l.getString("key"),
                     Crypto.linkKey(l.hex("phone_private"), l.hex("phone_public"), l.hex("pc_public"), l.hex("secret")).hex())
    }

    @Test
    fun theDaysDhtKeyAndSignature() {
        val d = v.getJSONObject("dht")
        val seed = Crypto.dhtSeed(v.getJSONObject("link").hex("key"), d.getInt("day"))
        assertEquals(d.getString("public"), Crypto.edPublic(seed).hex())
        val signed = Dht.signed("pc".toByteArray(), 5, "v".toByteArray())
        assertEquals(d.getString("signed"), signed.hex())
        assertEquals(d.getString("signature"), Crypto.edSign(seed, signed).hex())
        assertTrue(Crypto.edVerify(d.hex("public"), signed, d.hex("signature")))
    }

    @Test
    fun callKeysAndTheFirstPacket() {
        val c = v.getJSONObject("call")
        val (toPc, toPhone) = Crypto.callKeys(c.hex("phone_e_private"), c.hex("pc_e_public"), v.getJSONObject("link").hex("key"),
                                              c.hex("sid"), c.hex("phone_e_public"), c.hex("pc_e_public"))
        assertEquals(c.getString("to_pc"), toPc.hex())
        assertEquals(c.getString("to_phone"), toPhone.hex())
        val sent = mutableListOf<ByteArray>()
        val there = InetSocketAddress("127.0.0.1", 1)
        Call({ data, _ -> sent += data }, c.hex("sid"), toPc, toPhone, there).message("""{"type":"text","text":"你好"}""")
        assertEquals(c.getString("first_message_packet"), sent[0].hex())
        val heard = mutableListOf<JSONObject>()
        val computer = Call({ _, _ -> }, c.hex("sid"), toPhone, toPc, there).apply { onMessage = { heard += it } }
        computer.received(sent[0], there)
        computer.received(sent[0], there)                     // replayed: ignored
        assertEquals(listOf("你好"), heard.map { it.getString("text") })
    }

    @Test
    fun longMessagesArriveWhole() {
        val sent = mutableListOf<ByteArray>()
        val there = InetSocketAddress("127.0.0.1", 1)
        val key1 = Crypto.bytes(32)
        val key2 = Crypto.bytes(32)
        val card = "她记得的事。".repeat(800)
        Call({ data, _ -> sent += data }, byteArrayOf(1, 2, 3, 4), key1, key2, there).message(JSONObject().put("card", card))
        val heard = mutableListOf<JSONObject>()
        val phone = Call({ _, _ -> }, byteArrayOf(1, 2, 3, 4), key2, key1, there).apply { onMessage = { heard += it } }
        sent.reversed().forEach { phone.received(it, there) }  // out of order on the way
        assertTrue(sent.size > 10)
        assertEquals(card, heard.single().getString("card"))
    }

    @Test
    fun bencodeLikeTheComputer() {
        val b = v.getJSONObject("bencode")
        val encoded = Bencode.encode(mapOf("t" to "aa".toByteArray(), "y" to "q", "q" to "ping", "a" to mapOf("id" to "abc".toByteArray())))
        assertEquals(b.getString("encoded"), encoded.hex())
        val back = Bencode.decode(encoded) as Map<*, *>
        assertArrayEquals("abc".toByteArray(), (back["a"] as Map<*, *>)["id"] as ByteArray)
    }

    @Test
    fun herOfflineInputReadsLikeTheComputers() {
        val card = JSONObject().put("system", "你就是【她】这一栏里的角色。")
            .put("card", "【她】\n名字：爱丽丝\n\n【她记得】\nM1（3天前）他喜欢蓝色\n\n【她的心情】有点想他")
        val now = ZonedDateTime.of(2026, 10, 17, 21, 37, 5, 0, ZoneId.of("Asia/Shanghai"))
        val talk = listOf(Said(true, "在吗", now.toInstant().toEpochMilli() - 5 * 60_000, 1),
                          Said(false, "在呀", now.toInstant().toEpochMilli() - 5 * 60_000, 0))
        val prompt = Prompt.build(card, talk, "想你了", 2, now)
        assertEquals("<|im_start|>system\n你就是【她】这一栏里的角色。<|im_end|>\n<|im_start|>user\n" +
                     "【她】\n名字：爱丽丝\n\n【她记得】\nM1（3天前）他喜欢蓝色\n\n【最近发生的事】\n5分钟前\n  他〔E1〕：在吗\n  她：在呀" +
                     "\n\n【她的心情】有点想他\n【现在】2026-10-17T21:37:05+08:00 星期六 晚上9点37分\n【他刚说】〔E2〕想你了" +
                     "<|im_end|>\n<|im_start|>assistant\n{\"reply\":\"", prompt)
        assertTrue(prompt.startsWith(Prompt.stable(card)))
        assertEquals("前天深夜", Prompt.ago(now.minusDays(2).withHour(23).toInstant().toEpochMilli(), now))
    }

    @Test
    fun herReplyEndsAtTheClosingQuote() {
        val parser = ReplyParser()
        parser.feed("想你")
        parser.feed("啦\\n\\u5462\"}多余的")
        assertEquals("想你啦\n呢", parser.text)
        assertTrue(parser.done)
    }
}
