package io.wife.mobile.link

import java.io.ByteArrayOutputStream

/** Bencode as the BitTorrent DHT speaks it. Strings decode to ByteArray; dict keys to Latin-1 Strings (one char a
 *  byte, so they sort and compare like the bytes). Integers are Long. */
object Bencode {
    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is Int, is Long -> out.write("i${value}e".toByteArray())
            is ByteArray -> { out.write("${value.size}:".toByteArray()); out.write(value) }
            is String -> write(out, value.toByteArray(Charsets.ISO_8859_1))
            is List<*> -> { out.write('l'.code); value.forEach { write(out, it!!) }; out.write('e'.code) }
            is Map<*, *> -> {
                out.write('d'.code)
                for ((key, item) in value.entries.sortedBy { it.key as String }) {
                    write(out, key as String)
                    write(out, item!!)
                }
                out.write('e'.code)
            }
            else -> throw IllegalArgumentException("cannot bencode ${value::class}")
        }
    }

    fun decode(data: ByteArray): Any {
        val (value, end) = read(data, 0)
        require(end == data.size) { "trailing data" }
        return value
    }

    private fun read(data: ByteArray, at: Int): Pair<Any, Int> {
        var i = at
        return when (data[i].toInt().toChar()) {
            'i' -> {
                val end = data.indexOf('e'.code.toByte(), i)
                String(data, i + 1, end - i - 1).toLong() to end + 1
            }
            'l' -> {
                val items = mutableListOf<Any>()
                i++
                while (data[i] != 'e'.code.toByte()) read(data, i).let { items += it.first; i = it.second }
                items to i + 1
            }
            'd' -> {
                val items = LinkedHashMap<String, Any>()
                i++
                while (data[i] != 'e'.code.toByte()) {
                    val (key, afterKey) = read(data, i)
                    val (item, afterItem) = read(data, afterKey)
                    items[String(key as ByteArray, Charsets.ISO_8859_1)] = item
                    i = afterItem
                }
                items to i + 1
            }
            in '0'..'9' -> {
                val colon = data.indexOf(':'.code.toByte(), i)
                val start = colon + 1
                val end = start + String(data, i, colon - i).toInt()
                require(end <= data.size) { "string runs past the end" }
                data.copyOfRange(start, end) to end
            }
            else -> throw IllegalArgumentException("bad bencode at $i")
        }
    }

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
        for (j in from until size) if (this[j] == byte) return j
        throw IllegalArgumentException("unterminated")
    }
}
