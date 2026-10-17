package io.wife.mobile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import io.wife.mobile.brain.Offline
import io.wife.mobile.link.Call
import io.wife.mobile.link.Keep
import io.wife.mobile.link.Link
import io.wife.mobile.link.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One line in the conversation on screen. ``turn``: her reply's turn on the computer, so its pieces replace it. */
data class Line(val his: Boolean, val text: String, val turn: Long = -1, val phone: Boolean = false)

data class Screen(
    val paired: Boolean = false,
    val computer: String = "",
    val her: String = "她",
    val dialing: Boolean = false,
    val call: Boolean = false,
    val awake: Boolean? = null,           // is she awake on the computer (known during a call)
    val near: Boolean = true,
    val speaker: Boolean = true,
    val note: String = "",
    val lines: List<Line> = emptyList(),
    val find: String = "",
    val offline: String = "",             // her brain on the phone: what state it is in
    val thinking: Boolean = false,
)

/** The app's one state: the link to the computer, the call, her brain on the phone. */
object Wife {
    lateinit var keep: Keep
    lateinit var link: Link
    lateinit var offline: Offline
    val screen = MutableStateFlow(Screen())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var speech: TextToSpeech? = null

    fun start(context: Context) {
        keep = Vault(File(context.filesDir, "link.bin"))
        link = Link(keep).apply { onHere = { refresh() } }
        // inside the app's own storage: Android wipes an Android/data folder it didn't create (it took 4.7 GB with it)
        offline = Offline(File(context.filesDir, "brain").apply { mkdirs() }, keep)
        speech = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) speech?.language = Locale.CHINESE }
        refresh()
    }

    fun refresh() {
        val saved = keep.load()
        val here = saved.optJSONObject("here") ?: JSONObject()
        val card = saved.optJSONObject("card")
        screen.update {
            it.copy(paired = saved.has("key"), computer = here.optString("name"), find = here.optString("find"),
                    her = card?.optString("name")?.takeIf(String::isNotEmpty) ?: it.her)
        }
    }

    fun note(text: String) = screen.update { it.copy(note = text) }

    // ── during a call (CallService runs it) ──

    fun inCall(call: Call) {
        call.onMessage = { message ->
            when (message.optString("type")) {
                "state" -> screen.update { it.copy(awake = message.optBoolean("awake"),
                                                   her = message.optString("name").ifEmpty { it.her }) }
                "reply" -> said(message.optLong("turn", -1), message.optString("text"))
                "here" -> link.remember(message)
                "card" -> { keep.update { it.put("card", message) }; refresh() }
            }
        }
        call.message(JSONObject().put("type", "hello").put("near", screen.value.near))
        screen.update { it.copy(call = true, dialing = false, note = "") }
    }

    fun ended() = screen.update { it.copy(call = false, dialing = false, awake = null) }

    private fun said(turn: Long, text: String) = screen.update { s ->
        val at = s.lines.indexOfLast { !it.his && it.turn == turn && turn >= 0 }
        val line = Line(false, text, turn)
        s.copy(lines = if (at >= 0) s.lines.toMutableList().also { it[at] = line } else (s.lines + line).takeLast(60))
    }

    fun near(near: Boolean) {
        screen.update { it.copy(near = near) }
        link.call?.message(JSONObject().put("type", "hello").put("near", near))
    }

    fun speaker(on: Boolean) {
        screen.update { it.copy(speaker = on) }
        CallService.voice?.speaker = on
    }

    // ── typing to her ──

    fun type(text: String) {
        val words = text.trim().ifEmpty { return }
        screen.update { it.copy(lines = (it.lines + Line(true, words)).takeLast(60)) }
        val call = link.call
        if (call != null && !call.closed) {
            call.message(JSONObject().put("type", "text").put("text", words))
            return
        }
        if (!offline.awake) return note("现在没连着电脑。可以先叫醒手机上的她，或者打给电脑上的她。")
        screen.update { it.copy(thinking = true) }
        scope.launch {
            var shown = false
            val (reply, stats) = offline.answer(words) { text ->
                screen.update { s ->
                    val line = Line(false, text, phone = true)
                    s.copy(lines = if (shown) s.lines.dropLast(1) + line else (s.lines + line).also { shown = true })
                }
            }
            screen.update { it.copy(thinking = false, offline = stats) }
            if (reply.isNotEmpty()) speech?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "her")
        }
    }

    /** Copies the brain files he picked (from Download, say) into the app. */
    fun importBrain(resolver: ContentResolver, picked: List<Uri>) {
        if (picked.isEmpty()) return
        screen.update { it.copy(thinking = true) }
        scope.launch(Dispatchers.IO) {
            try {
                for ((i, uri) in picked.withIndex()) {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null } ?: "brain-$i.gguf"
                    screen.update { it.copy(offline = "正在放进第 ${i + 1}/${picked.size} 个：$name…") }
                    resolver.openInputStream(uri)!!.use { input -> File(offline.folder, name).outputStream().use(input::copyTo) }
                }
                screen.update { it.copy(thinking = false, offline = "大脑放好了。") }
            } catch (e: Exception) {
                screen.update { it.copy(thinking = false, offline = "没放进去：${e.message}（手机空间可能不够）") }
            }
        }
    }

    fun wakeOffline() {
        screen.update { it.copy(thinking = true, offline = "正在叫醒手机上的她…（大脑有 4.7 GB，要等一会儿）") }
        scope.launch(Dispatchers.IO) {
            val result = offline.load()
            screen.update { it.copy(thinking = false, offline = result) }
        }
    }
}
