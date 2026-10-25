package io.wife.mobile

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import io.wife.mobile.brain.Offline
import io.wife.mobile.link.Crypto
import io.wife.mobile.link.Keep
import io.wife.mobile.link.Link
import io.wife.mobile.link.Session
import io.wife.mobile.link.Vault
import io.wife.mobile.voice.Player
import io.wife.mobile.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

data class Screen(
    val paired: Boolean = false,
    val computer: String = "",
    val her: String = "她",
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val awake: Boolean? = null,           // is she awake on the computer
    val away: Boolean = false,            // has he left the computer (WifeBody's presence)
    val items: List<Item> = emptyList(),
    val typing: String = "",              // her reply as she writes it
    val call: Boolean = false,
    val near: Boolean = true,
    val speaker: Boolean = true,
    val voiceReplies: Boolean = false,    // she answers what he types (and photos) with her voice too
    val voiceprints: Int = 0,
    val enrolling: String = "",           // what the computer said about the last voiceprint recording
    val note: String = "",
    val stats: String = "",
    val offline: String = "",             // her brain on the phone: what state it is in
    val thinking: Boolean = false,
)

/** The app's one state: the link to the computer and the session on it (kept by LinkService), the conversation,
 *  calls, and her brain on the phone for when the computer can't be reached. */
object Wife {
    lateinit var keep: Keep
    lateinit var link: Link
    lateinit var offline: Offline
    lateinit var chat: Chat
    private lateinit var app: Context
    val screen = MutableStateFlow(Screen())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var session: Session? = null
    @Volatile var front = false
    @Volatile private var voice: Voice? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var speech: TextToSpeech? = null
    @Volatile private var lastVoiceSent = 0L

    fun start(context: Context) {
        app = context.applicationContext
        keep = Vault(File(context.filesDir, "link.bin"))
        link = Link(keep).apply { onHere = { refresh() } }
        // inside the app's own storage: Android wipes an Android/data folder it didn't create (it took 4.7 GB with it)
        offline = Offline(File(context.filesDir, "brain").apply { mkdirs() }, keep)
        chat = Chat(File(context.filesDir, "chat"))
        screen.update { it.copy(voiceReplies = settings().getBoolean("voiceReplies", false)) }
        speech = TextToSpeech(context) { if (it == TextToSpeech.SUCCESS) speech?.language = Locale.CHINESE }
        refresh()
        shown()
    }

    fun refresh() {
        val saved = keep.load()
        val here = saved.optJSONObject("here") ?: JSONObject()
        val card = saved.optJSONObject("card")
        screen.update {
            it.copy(paired = saved.has("key"), computer = here.optString("name"),
                    her = card?.optString("name")?.takeIf(String::isNotEmpty) ?: it.her)
        }
    }

    fun note(text: String) = screen.update { it.copy(note = text) }

    private fun settings() = app.getSharedPreferences("wife", Context.MODE_PRIVATE)

    fun voiceReplies(on: Boolean) {
        settings().edit().putBoolean("voiceReplies", on).apply()
        screen.update { it.copy(voiceReplies = on) }
    }

    private fun shown() = screen.update { it.copy(items = chat.items) }

    // ── the session, kept by LinkService ──

    /** Keeps a session to the computer: pinging every 45 s from the background (5 s with the app in front or in a
     *  call), dialling again when it drops, sooner when woken. */
    suspend fun keepConnected() {
        var wait = 2000L
        while (true) {
            if (!screen.value.paired) {
                withTimeoutOrNull(10_000) { wake.receive() }
                continue
            }
            screen.update { it.copy(connecting = true) }
            val made = link.dial(if (front || screen.value.call) 5 else 45) { session ->
                session.onMessage = ::heard
                session.onAudio = { seq, frame -> voice?.heard(seq, frame) }
            }
            if (made == null) {
                screen.update { it.copy(connecting = false, connected = false, note = if (front) unreachable() else it.note) }
                withTimeoutOrNull(wait) { wake.receive() }
                wait = minOf(wait * 2, 60_000)
                continue
            }
            wait = 2000
            session = made
            screen.update { it.copy(connecting = false, connected = true, note = "") }
            made.message(JSONObject().put("type", "hello").put("app", if (front) "open" else "standby"))
            var ticks = 0
            while (!made.closed) {
                made.tick()
                delay(100)
                if (++ticks % 10 == 0) voice?.let { v ->
                    screen.update { it.copy(stats = "发出 ${made.sent} 个包 · 收到 ${made.got} 个") }
                }
            }
            session = null
            link.session = null
            if (screen.value.call) stopCall(tell = false)
            screen.update { it.copy(connected = false, typing = "") }
        }
    }

    /** The app came to the front or went behind it: the session's pace follows, and she knows he is on the phone. */
    fun foreground(on: Boolean) {
        front = on
        session?.let { s ->
            s.pingEvery = if (on || screen.value.call) 5000 else 45000
            s.message(JSONObject().put("type", "pace").put("ping", s.pingEvery / 1000))
            s.message(JSONObject().put("type", "hello").put("app", if (on) "open" else "standby"))
        }
        if (on) {
            LinkService.quiet(app)
            scope.launch { link.where() }                // at home: learn the computer's current addresses
            wake.trySend(Unit)
        }
    }

    fun connectNow() = wake.trySend(Unit)

    // ── what the computer says ──

    private fun heard(message: JSONObject) {
        when (message.optString("type")) {
            "state" -> {
                screen.update {
                    it.copy(awake = message.optBoolean("awake"), away = message.optBoolean("away"),
                            voiceprints = message.optInt("voiceprint", it.voiceprints),
                            her = message.optString("name").ifEmpty { it.her })
                }
                if (message.optBoolean("awake")) resend()
            }
            "her" -> herSaid(message)
            "heard" -> {
                chat.update(message.optString("id")) { it.copy(text = message.optString("text"), heard = message.optBoolean("ok", true)) }
                shown()
            }
            "got" -> {
                val ids = message.optJSONArray("ids") ?: JSONArray()
                for (i in 0 until ids.length()) chat.update(ids.getString(i)) { it.copy(pending = false) }
                shown()
            }
            "reply" -> screen.update { it.copy(typing = if (message.optBoolean("final")) "" else message.optString("text")) }
            "cancel" -> screen.update { it.copy(typing = "") }
            "voiceprint" -> screen.update {
                it.copy(voiceprints = message.optInt("count", it.voiceprints), enrolling = message.optString("error").ifEmpty { "电脑记下了" })
            }
            "here" -> link.remember(message)
            "card" -> { keep.update { it.put("card", message) }; refresh() }
        }
    }

    private fun herSaid(message: JSONObject) {
        val id = message.optString("id").ifEmpty { return }
        val text = message.optString("text")
        val voice = message.optString("opus").takeIf(String::isNotEmpty)?.let(Crypto::unb64)
        val new = !chat.has(id)
        if (new) {
            chat.add(Item(id, false, message.optLong("t", System.currentTimeMillis()), if (voice == null) "text" else "voice",
                          text, message.optInt("ms"), link = message.optString("link")), voice = voice)
        } else if (voice != null) {                                  // her voice for words already here
            chat.update(id, voice) { it.copy(kind = "voice", ms = message.optInt("ms")) }
        }
        session?.message(JSONObject().put("type", "got").put("ids", JSONArray().put(id)))
        screen.update { it.copy(typing = "") }
        shown()
        if (!front && new) LinkService.notify(app, screen.value.her, if (message.has("link")) "给你看这个：$text" else text)
        else if (front && voice != null && !screen.value.call &&
                 (screen.value.voiceReplies || System.currentTimeMillis() - lastVoiceSent < 120_000)) {
            Player.play(id, voice, 48000)                             // he spoke to her, or wants her voice: it plays
        }
    }

    // ── what he sends ──

    private fun newId() = Crypto.b64(Crypto.bytes(8))

    fun type(text: String) {
        val words = text.trim().ifEmpty { return }
        val s = session
        if ((s == null || screen.value.awake != true) && offline.awake) return answerHere(words)
        post(Item(newId(), true, System.currentTimeMillis(), "text", words, pending = true))
    }

    fun sendVoice(recording: ByteArray, ms: Int) {
        lastVoiceSent = System.currentTimeMillis()
        post(Item(newId(), true, System.currentTimeMillis(), "voice", "", ms, pending = true), voice = recording)
    }

    /** A photo, its longest side made at most 1600 pixels (protocol.md), turned the way the camera held it. */
    fun sendPhoto(data: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@launch note("这张照片打不开")
            val scale = minOf(1f, 1600f / maxOf(decoded.width, decoded.height))
            val turn = when (ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val matrix = Matrix().apply { postScale(scale, scale); postRotate(turn) }
            val photo = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val jpeg = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            post(Item(newId(), true, System.currentTimeMillis(), "photo", "", pending = true), photo = jpeg)
        }
    }

    private fun post(item: Item, voice: ByteArray? = null, photo: ByteArray? = null) {
        chat.add(item, voice, photo)
        shown()
        val s = session
        if (s != null && screen.value.awake == true) s.message(wire(item)) else connectNow()
    }

    private fun wire(item: Item): JSONObject {
        val message = JSONObject().put("id", item.id).put("t", item.t)
        if (screen.value.voiceReplies) message.put("voice", true)
        when (item.kind) {
            "voice" -> message.put("type", "voice").put("ms", item.ms).put("opus", Crypto.b64(chat.voice(item.id) ?: ByteArray(0)))
            "photo" -> message.put("type", "photo").put("jpeg", Crypto.b64(chat.photo(item.id) ?: ByteArray(0)))
            else -> message.put("type", "say").put("text", item.text)
        }
        return message
    }

    /** His items the computer has not confirmed: sent again whenever she is awake on a new session. */
    private fun resend() {
        val s = session ?: return
        for (item in chat.items) if (item.his && item.pending) s.message(wire(item))
    }

    fun play(item: Item) {
        if (Player.playing.value == item.id) return Player.stop()
        chat.voice(item.id)?.let { Player.play(item.id, it, if (item.his) 16000 else 48000) }
    }

    // ── a call (the microphone permission is granted) ──

    fun call(context: Context) {
        val s = session ?: run {
            note("还没连上电脑，连上了再打")
            connectNow()
            return
        }
        Player.stop()
        LinkService.calling(context, true)                             // Android lets the service use the microphone
        val v = Voice(context, s).also { it.speaker = screen.value.speaker }
        voice = v
        v.start()
        s.pingEvery = 5000
        s.message(JSONObject().put("type", "call").put("on", true).put("near", screen.value.near))
        screen.update { it.copy(call = true, note = "") }
    }

    fun hangUp() = stopCall(tell = true)

    private fun stopCall(tell: Boolean) {
        if (tell) session?.message(JSONObject().put("type", "call").put("on", false))
        voice?.stop()
        voice = null
        LinkService.calling(app, false)
        screen.update { it.copy(call = false, stats = "") }
    }

    fun near(near: Boolean) {
        screen.update { it.copy(near = near) }
        if (screen.value.call) session?.message(JSONObject().put("type", "call").put("on", true).put("near", near))
    }

    fun speaker(on: Boolean) {
        screen.update { it.copy(speaker = on) }
        voice?.speaker = on
    }

    // ── his voiceprint, recorded on this phone ──

    fun enroll(recording: ByteArray) {
        val s = session ?: return screen.update { it.copy(enrolling = "还没连上电脑") }
        screen.update { it.copy(enrolling = "电脑正在记…") }
        s.message(JSONObject().put("type", "enroll").put("opus", Crypto.b64(recording)))
    }

    fun clearVoiceprint() {
        session?.message(JSONObject().put("type", "enroll").put("clear", true))
    }

    // ── her brain on the phone, when the computer can't be reached ──

    private fun answerHere(words: String) {
        chat.add(Item(newId(), true, System.currentTimeMillis(), "text", words))
        shown()
        screen.update { it.copy(thinking = true) }
        scope.launch {
            val (reply, stats) = offline.answer(words) { text -> screen.update { it.copy(typing = text) } }
            screen.update { it.copy(thinking = false, offline = stats, typing = "") }
            if (reply.isNotEmpty()) {
                chat.add(Item(newId(), false, System.currentTimeMillis(), "text", reply, phone = true))
                shown()
                speech?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "her")
            }
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

    private fun unreachable(): String {
        val here = link.here()
        val away = here.optJSONArray("away")?.length() ?: 0
        return when {
            away == 0 -> "连不上电脑。电脑没有外出用的地址（没有 IPv6），只能在同一个 WiFi 里连。"
            here.optString("find") == "pair" -> "连不上电脑。家里的地址可能变了：回家连上 WiFi 后重新扫码配对。"
            here.optString("find") == "dht" -> "连不上电脑，在 BT 网络里也没查到它。电脑要开着，手机端程序要在运行。"
            else -> "连不上电脑。家里的地址可能变了，回家连上 WiFi 就会更新；也可能电脑没开、手机端程序没在运行。"
        } + if (offline.awake) "" else " 也可以在设置里叫醒手机上的她。"
    }
}
