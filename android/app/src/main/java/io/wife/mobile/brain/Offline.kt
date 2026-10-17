package io.wife.mobile.brain

import io.wife.mobile.link.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Something said while the phone couldn't reach the computer. */
data class Said(val his: Boolean, val text: String, val at: Long, val id: Int)

/** Her input the way the computer writes it (WifeBody mind/snapshot.py): the prompt and her card come from the
 *  computer (the call message "card"); the phone adds what was said since, the time, and his words. */
object Prompt {
    const val OPEN = "{\"reply\":\""
    private val PARTS = listOf(5 to "凌晨", 8 to "早上", 11 to "上午", 13 to "中午", 18 to "下午", 23 to "晚上", 24 to "深夜")
    private const val NUMBERS = "零一两三四五六七八九"

    private fun part(hour: Int) = PARTS.first { hour < it.first }.second

    private fun chinese(n: Int): String {
        if (n < 10) return NUMBERS[n].toString()
        val tens = n / 10
        val ones = n % 10
        return (if (tens == 1) "" else "二三四五六七八九"[tens - 2].toString()) + "十" +
            (if (ones > 0) NUMBERS[ones].toString().replace("两", "二") else "")
    }

    fun ago(at: Long, now: ZonedDateTime): String {
        val seconds = maxOf(0L, now.toEpochSecond() - at / 1000)
        val then = Instant.ofEpochMilli(at).atZone(now.zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate()).toInt()
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60}分钟前"
            seconds < 24 * 3600 -> "${seconds / 3600}小时前"
            days == 1 -> "昨天${part(then.hour)}"
            days == 2 -> "前天${part(then.hour)}"
            days < 14 -> "${chinese(days)}天前${part(then.hour)}"
            else -> "${chinese(minOf(days / 7, 99))}周前的一天${part(then.hour)}"
        }
    }

    fun now(now: ZonedDateTime): String {
        val iso = now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
        val hour = if (now.hour > 12) now.hour - 12 else now.hour
        val clock = "${part(now.hour)}${hour}点" + if (now.minute > 0) "${now.minute}分" else ""
        return "【现在】$iso 星期${"一二三四五六日"[now.dayOfWeek.value - 1]} $clock"
    }

    /** The whole prompt for answering ``text``, ending where her answer starts. */
    fun build(card: JSONObject, talk: List<Said>, text: String, id: Int, now: ZonedDateTime): String {
        val whole = card.getString("card")
        val mood = whole.indexOf("\n\n【她的心情】")
        val (before, after) = if (mood >= 0) whole.substring(0, mood) to whole.substring(mood) else whole to ""
        val lines = mutableListOf<String>()
        var shown: String? = null
        for (said in talk) {
            val at = ago(said.at, now)
            if (at != shown) lines += at.also { shown = it }
            lines += "  " + if (said.his) "他〔E${said.id}〕：${said.text}" else "她：${said.text}"
        }
        val user = before + (if (lines.isEmpty()) "" else "\n\n【最近发生的事】\n" + lines.joinToString("\n")) + after +
            "\n" + now(now) + "\n【他刚说】〔E$id〕$text"
        return head(card) + user + "<|im_end|>\n<|im_start|>assistant\n" + OPEN
    }

    /** The part that stays the same from answer to answer, read ahead once. */
    fun head(card: JSONObject) =
        "<|im_start|>system\n${card.getString("system")}<|im_end|>\n<|im_start|>user\n"

    fun stable(card: JSONObject): String {
        val whole = card.getString("card")
        val mood = whole.indexOf("\n\n【她的心情】")
        return head(card) + if (mood >= 0) whole.substring(0, mood) else whole
    }
}

/** Her reply as it streams: the JSON string after {"reply":" until its closing quote. */
class ReplyParser {
    private val out = StringBuilder()
    private var escape = false
    private var unicode: StringBuilder? = null
    var done = false
        private set
    val text: String get() = out.toString()

    fun feed(chunk: String) {
        for (c in chunk) {
            if (done) return
            val hex = unicode
            when {
                hex != null -> {
                    hex.append(c)
                    if (hex.length == 4) { out.append(hex.toString().toInt(16).toChar()); unicode = null }
                }
                escape -> {
                    escape = false
                    when (c) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> unicode = StringBuilder()
                        else -> out.append(c)
                    }
                }
                c == '\\' -> escape = true
                c == '"' -> done = true
                else -> out.append(c)
            }
        }
    }
}

/** Her brain on the phone: the computer's model and chat LoRA, copied into ``folder``. */
class Offline(val folder: File, private val keep: Keep) {
    @Volatile private var handle = 0L
    val awake: Boolean get() = handle != 0L

    fun files(): Pair<File?, File?> {
        val gguf = folder.listFiles { file -> file.name.endsWith(".gguf") }.orEmpty().toList()
        val lora = gguf.firstOrNull { it.name == "wife-chat.gguf" }
        val models = gguf.filter { it != lora && !it.name.startsWith("wife-") }
        return (models.firstOrNull { "00001-of-" in it.name } ?: models.maxByOrNull { it.length() }) to lora
    }

    fun card(): JSONObject? = keep.load().optJSONObject("card")

    /** Wakes her brain up; returns how it went, in his words. Takes a while: run off the main thread. */
    @Synchronized
    fun load(): String {
        if (handle != 0L) return "手机上的她醒着"
        val (base, lora) = files()
        base ?: return "手机里还没有她的大脑：把电脑上的模型文件放进 ${folder.path}"
        val started = System.currentTimeMillis()
        val loaded = Llama.load(base.path, 8192, maxOf(2, Runtime.getRuntime().availableProcessors() - 2))
        if (loaded == 0L) return "她的大脑没能加载（手机内存可能不够）"
        if (lora != null && !Llama.adapter(loaded, lora.path)) {
            Llama.free(loaded)
            return "她的说话 LoRA 没能加载"
        }
        handle = loaded
        val warm = card()?.let { "，先读了一遍她的资料（" + Llama.prefill(handle, Prompt.stable(it)) + "）" } ?: ""
        return "手机上的她醒了，用了 ${(System.currentTimeMillis() - started) / 1000} 秒$warm" +
            if (lora == null) "。没找到 wife-chat.gguf，用的是没训练过的底模" else ""
    }

    @Synchronized
    fun sleep() {
        if (handle != 0L) Llama.free(handle)
        handle = 0L
    }

    /** Her answer to ``text``; ``onText`` gets what she has said so far. Returns (reply, how long it took). */
    @Synchronized
    fun answer(text: String, onText: (String) -> Unit): Pair<String, String> {
        val card = card() ?: return "" to "还没有她的资料：先在家和她通一次话"
        if (handle == 0L) return "" to "手机上的她还没醒"
        val talk = talk()
        val id = (talk.maxOfOrNull { it.id } ?: 0) + 1
        val now = System.currentTimeMillis()
        val parser = ReplyParser()
        val stats = Llama.generate(handle, Prompt.build(card, talk, text, id, ZonedDateTime.now(ZoneId.systemDefault())), 200) {
            parser.feed(String(it))
            onText(parser.text)
            !parser.done
        }
        val reply = parser.text.trim()
        keep.update { saved ->
            val kept = (talk + Said(true, text, now, id) + Said(false, reply, System.currentTimeMillis(), 0)).takeLast(24)
            saved.put("talk", JSONArray(kept.map { JSONObject().put("his", it.his).put("text", it.text).put("at", it.at).put("id", it.id) }))
        }
        return reply to stats
    }

    fun talk(): List<Said> {
        val saved = keep.load().optJSONArray("talk") ?: return emptyList()
        return (0 until saved.length()).map { saved.getJSONObject(it) }
            .map { Said(it.getBoolean("his"), it.getString("text"), it.getLong("at"), it.optInt("id")) }
    }
}
