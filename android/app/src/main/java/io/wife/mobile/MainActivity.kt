package io.wife.mobile

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.wife.mobile.voice.Player
import io.wife.mobile.voice.Recorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Wife.start(this)
    }
}

/** Cream and rose by day, cocoa and candlelight at night: the same colours as Wife's windows on the computer. */
private data class Palette(val window: Color, val surface: Color, val text: Color, val muted: Color, val accent: Color,
                           val soft: Color, val border: Color, val danger: Color)

private val Day = Palette(Color(0xFFFFF9F5), Color(0xFFFFFFFF), Color(0xFF3E302C), Color(0xFF9A857C), Color(0xFFD9677C),
                          Color(0xFFFDE7EA), Color(0xFFF0DED5), Color(0xFFC9473F))
private val Night = Palette(Color(0xFF221C1B), Color(0xFF2C2422), Color(0xFFF4EAE5), Color(0xFFB5A098), Color(0xFFF29BA8),
                            Color(0xFF4A2D33), Color(0xFF3E322F), Color(0xFFF08A80))

private val SENTENCES = listOf("我回来了，今天过得怎么样？", "等我一下，我先去倒杯水。", "今天天气不错，我们出去走走吧。",
                               "晚上想吃点什么？我来做饭。", "这首歌真好听，再放一遍吧。")

/** What the screens ask the activity for: permissions, the camera, pickers, system settings. */
private class Actions(val scan: () -> Unit, val call: () -> Unit, val photo: () -> Unit, val gallery: () -> Unit,
                      val brain: () -> Unit, val keepRunning: () -> Unit, val running: () -> Boolean,
                      val microphone: () -> Boolean)

class MainActivity : ComponentActivity() {
    private var afterMicrophone: (() -> Unit)? = null
    private var shot: File? = null

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val qr = result.contents ?: return@registerForActivityResult
        Wife.note("正在和电脑配对…")
        Wife.scope.launch {
            val problem = Wife.link.pair(qr, Build.MODEL)
            Wife.refresh()
            Wife.note(problem ?: "")
            if (problem == null) {
                Wife.session?.hangUp()                      // a new computer: the next session goes to it
                LinkService.start(this@MainActivity)
                Wife.connectNow()
            }
        }
    }

    private val brain = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        Wife.importBrain(contentResolver, picked)
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicrophone?.invoke() else Wife.note("没有麦克风权限：在手机设置里给 Wife 打开麦克风")
        afterMicrophone = null
    }

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = shot
        if (taken && file != null) Wife.sendPhoto(file.readBytes())
        file?.delete()
    }

    private val cameraAllowed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) takePhoto() }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openInputStream(uri)?.use { Wife.sendPhoto(it.readBytes()) }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun withMicrophone(then: () -> Unit) {
        if (granted(Manifest.permission.RECORD_AUDIO)) return then()
        afterMicrophone = then
        microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun takePhoto() {
        if (!granted(Manifest.permission.CAMERA)) return cameraAllowed.launch(Manifest.permission.CAMERA)
        val file = File(File(cacheDir, "photos").apply { mkdirs() }, "shot.jpg")
        shot = file
        camera.launch(FileProvider.getUriForFile(this, "io.wife.mobile.photos", file))
    }

    private fun running() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Wife.screen.value.paired) LinkService.start(this)
        val actions = Actions(
            scan = { scan.launch(ScanOptions().setPrompt("扫电脑上的配对码").setBeepEnabled(false).setOrientationLocked(false)) },
            call = { withMicrophone { Wife.call(this) } },
            photo = ::takePhoto,
            gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            brain = { brain.launch(arrayOf("*/*")) },
            keepRunning = {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
            running = ::running,
            microphone = { granted(Manifest.permission.RECORD_AUDIO).also { if (!it) withMicrophone {} } },
        )
        setContent {
            val palette = if (isSystemInDarkTheme()) Night else Day
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = palette.accent, background = palette.window)
                         else lightColorScheme(primary = palette.accent, background = palette.window)
            MaterialTheme(colorScheme = scheme) {
                val screen by Wife.screen.collectAsState()
                Root(screen, palette, actions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Wife.foreground(true)
    }

    override fun onStop() {
        Wife.foreground(false)
        super.onStop()
    }
}

@Composable
private fun Root(screen: Screen, p: Palette, a: Actions) {
    var page by remember { mutableStateOf("chat") }
    var looking by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(p.window)) {
        when {
            !screen.paired -> Pairing(screen, p, a)
            screen.call -> CallPage(screen, p)
            page == "settings" -> {
                BackHandler { page = "chat" }
                SettingsPage(screen, p, a, open = { page = it }, back = { page = "chat" })
            }
            page == "voiceprint" -> {
                BackHandler { page = "settings" }
                VoiceprintPage(screen, p, a, back = { page = "settings" })
            }
            else -> ChatPage(screen, p, a, settings = { page = "settings" }, look = { looking = it })
        }
        looking?.let { id -> PhotoViewer(id) { looking = null } }
    }
}

private fun status(screen: Screen): String = when {
    !screen.connected && screen.connecting -> "正在连电脑…"
    !screen.connected -> "连不上电脑" + if (Wife.offline.awake) " · 手机上的她醒着" else ""
    screen.awake == false -> "电脑上的她在休息"
    screen.away -> "在线 · 你不在电脑前"
    else -> "在线"
}

// ── talking with her ──

@Composable
private fun ChatPage(screen: Screen, p: Palette, a: Actions, settings: () -> Unit, look: (String) -> Unit) {
    val recorder = remember { Recorder() }
    var recording by remember { mutableStateOf(false) }
    val playing by Player.playing.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(screen.her, p, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(screen.her, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(status(screen), color = p.muted, fontSize = 12.sp)
                }
                Glyph("📞", p.soft, onClick = a.call)
                Glyph("⚙", null, p.muted, onClick = settings)
            }
            Rule(p)
            val list = rememberLazyListState()
            val count = screen.items.size + (if (screen.typing.isNotEmpty()) 1 else 0) + (if (screen.note.isNotEmpty()) 1 else 0)
            LaunchedEffect(count, screen.typing) { if (count > 0) list.animateScrollToItem(count - 1) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
                       verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(screen.items, key = { _, item -> item.id }) { i, item ->
                    Column {
                        if (i == 0 || item.t - screen.items[i - 1].t > 5 * 60_000) TimeLine(item.t, p)
                        Bubble(item, p, playing == item.id, play = { Wife.play(item) }, look = { look(item.id) })
                    }
                }
                if (screen.typing.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth()) {
                        Text(screen.typing + " …", color = p.muted, fontSize = 16.sp,
                             modifier = Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).background(p.surface)
                                 .padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
                if (screen.note.isNotEmpty()) item {
                    Text(screen.note, color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                         modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                }
            }
            Rule(p)
            Bar(screen, p, a, recorder) { recording = it }
        }
        if (recording) RecordingBadge(recorder, p, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Bar(screen: Screen, p: Palette, a: Actions, recorder: Recorder, holding: (Boolean) -> Unit) {
    var speaking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val send = { Wife.type(typed); typed = "" }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(if (speaking) "⌨" else "🎙", null) { speaking = !speaking }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (speaking) {
                HoldToTalk("按住 说话", p, recorder, permitted = a.microphone, sent = { recording, ms -> Wife.sendVoice(recording, ms) },
                           holding = holding)
            } else {
                OutlinedTextField(value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                  placeholder = { Text(if (screen.thinking) "她在想…" else "发消息") },
                                  shape = RoundedCornerShape(12.dp),
                                  keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                  keyboardActions = KeyboardActions(onSend = { send() }),
                                  colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.border,
                                                                            focusedTextColor = p.text, unfocusedTextColor = p.text))
            }
        }
        Spacer(Modifier.width(6.dp))
        if (!speaking && typed.isNotBlank()) {
            Glyph("↑", p.accent, Color.White) { send() }
        } else {
            Glyph("📷", null, onClick = a.photo)
            Glyph("🖼", null, onClick = a.gallery)
        }
    }
}

/** Hold to record, let go to send, slide up to cancel, the way messaging apps do it. */
@Composable
private fun HoldToTalk(title: String, p: Palette, recorder: Recorder, shortest: Int = 600, permitted: () -> Boolean,
                       sent: (ByteArray, Int) -> Unit, holding: (Boolean) -> Unit = {}) {
    var down by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    val allowed by rememberUpdatedState(permitted)
    val send by rememberUpdatedState(sent)
    val hold by rememberUpdatedState(holding)
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (down) (if (cancelling) p.danger else p.accent) else p.surface)
            .border(1.dp, p.border, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    if (!allowed()) return@awaitEachGesture
                    recorder.start()
                    down = true
                    cancelling = false
                    hold(true)
                    val limit = 60.dp.toPx()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                        cancelling = first.position.y - change.position.y > limit
                        if (!change.pressed) break
                    }
                    val (recording, ms) = recorder.stop()
                    down = false
                    hold(false)
                    if (!cancelling && ms >= shortest) send(recording, ms)
                    cancelling = false
                }
            },
        contentAlignment = Alignment.Center) {
        Text(if (down) (if (cancelling) "松开手指，取消" else "松开 发送") else title,
             color = if (down) Color.White else p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecordingBadge(recorder: Recorder, p: Palette, modifier: Modifier) {
    val level by recorder.level.collectAsState()
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            seconds = ((System.currentTimeMillis() - recorder.started) / 1000).toInt()
            delay(250)
        }
    }
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(p.surface).padding(22.dp),
           horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎙", fontSize = 34.sp)
        Box(Modifier.padding(vertical = 8.dp).width((30 + level * 110).dp).height(6.dp).clip(CircleShape).background(p.accent))
        Text("%d:%02d".format(seconds / 60, seconds % 60), color = p.text, fontSize = 15.sp)
        Text("松开发送 · 上滑取消", color = p.muted, fontSize = 12.sp)
    }
}

/** One line of the conversation: his on the right in rose, hers on the left. */
@Composable
private fun Bubble(item: Item, p: Palette, playing: Boolean, play: () -> Unit, look: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (item.his) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom) {
        if (item.his && item.pending) Text("发送中", color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp))
        Column(Modifier.widthIn(max = 290.dp), horizontalAlignment = if (item.his) Alignment.End else Alignment.Start) {
            when (item.kind) {
                "photo" -> Photo(item.id, look)
                "voice" -> if (item.his) {
                    Voice(item, playing, Color.White,
                          Modifier.clip(RoundedCornerShape(18.dp)).background(p.accent).clickable(onClick = play)
                              .padding(horizontal = 14.dp, vertical = 10.dp).width(minOf(220, 76 + item.ms / 1000 * 6).dp))
                    if (item.heard == false) Text("没认出是你的声音，她没当成你说的", color = p.danger, fontSize = 12.sp)
                    else if (item.text.isNotEmpty()) Text(item.text, color = p.muted, fontSize = 12.sp)
                } else {
                    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(p.surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Voice(item, playing, p.accent, Modifier.clickable(onClick = play))
                        Spacer(Modifier.height(6.dp))
                        Text(item.text, color = p.text, fontSize = 16.sp)
                    }
                }
                else -> Text(item.text + if (item.phone) "  · 手机上的她" else "", color = if (item.his) Color.White else p.text,
                             fontSize = 16.sp,
                             modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (item.his) p.accent else p.surface)
                                 .padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun Voice(item: Item, playing: Boolean, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(if (playing) "■" else "▶", color = color, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text("${maxOf(1, item.ms / 1000)}″", color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Photo(id: String, look: () -> Unit) {
    val image = remember(id) { Wife.chat.photo(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription = "照片", contentScale = ContentScale.Fit,
              modifier = Modifier.widthIn(max = 200.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = look))
    }
}

@Composable
private fun PhotoViewer(id: String, close: () -> Unit) {
    BackHandler(onBack = close)
    val image = remember(id) { Wife.chat.photo(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = close), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, contentDescription = "照片", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TimeLine(t: Long, p: Palette) {
    val today = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_YEAR) ==
        Calendar.getInstance().get(Calendar.DAY_OF_YEAR) && System.currentTimeMillis() - t < 86_400_000
    val text = SimpleDateFormat(if (today) "HH:mm" else "M月d日 HH:mm", Locale.CHINA).format(Date(t))
    Text(text, color = p.muted, fontSize = 11.sp, textAlign = TextAlign.Center,
         modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp))
}

// ── a call ──

@Composable
private fun CallPage(screen: Screen, p: Palette) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val since = System.currentTimeMillis()
        while (true) {
            seconds = ((System.currentTimeMillis() - since) / 1000).toInt()
            delay(500)
        }
    }
    val said = screen.typing.ifEmpty { screen.items.lastOrNull { !it.his }?.text ?: "" }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Avatar(screen.her, p, 112.dp)
        Spacer(Modifier.height(14.dp))
        Text(screen.her, color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(if (screen.awake == false) "接通了，但电脑上的她在休息" else "通话中 %d:%02d".format(seconds / 60, seconds % 60),
             color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Text(said, color = p.text, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 5,
             modifier = Modifier.heightIn(min = 100.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Choice("💻", "在电脑旁", screen.near, p) { Wife.near(!screen.near) }
            Choice("🔊", "外放", screen.speaker, p) { Wife.speaker(!screen.speaker) }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(76.dp).clip(CircleShape).background(p.danger).clickable { Wife.hangUp() }, contentAlignment = Alignment.Center) {
            Text("挂断", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (screen.note.isNotEmpty()) Text(screen.note, color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text(screen.stats, color = p.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
    }
}

@Composable
private fun Choice(glyph: String, title: String, on: Boolean, p: Palette, flip: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = flip)) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(if (on) p.accent else p.surface).border(1.dp, p.border, CircleShape),
            contentAlignment = Alignment.Center) { Text(glyph, fontSize = 22.sp) }
        Spacer(Modifier.height(6.dp))
        Text(title, color = p.muted, fontSize = 12.sp)
    }
}

// ── settings ──

@Composable
private fun SettingsPage(screen: Screen, p: Palette, a: Actions, open: (String) -> Unit, back: () -> Unit) {
    var clearing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(a.running()) }
    LaunchedEffect(Unit) {                                   // he may come back from the system's battery settings
        while (true) {
            running = a.running()
            delay(1500)
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Top("设置", p, back)
        Card(p) {
            Text("电脑", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Pair("配对的电脑", screen.computer.ifEmpty { "电脑" }, p)
            Pair("现在", if (screen.connected) "连着" else "没连上", p)
            TextButton(onClick = a.scan) { Text("重新配对", color = p.accent) }
        }
        Spacer(Modifier.height(12.dp))
        Card(p) {
            Text("声纹", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("录过以后，手机传来的声音（通话、语音消息）要对得上你的声纹，她才当成是你说的。还没录时，配对过的手机就算你。",
                 color = p.muted, fontSize = 12.sp)
            Pair("已经录了", "${screen.voiceprints} 段", p)
            Button(onClick = { open("voiceprint") }, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("录手机声纹") }
            if (screen.voiceprints > 0) TextButton(onClick = { clearing = true }) { Text("清掉手机声纹", color = p.danger) }
        }
        Spacer(Modifier.height(12.dp))
        Card(p) {
            Text("在后台一直连着", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Wife 关着时也和电脑连着（通知栏有一条“和她连着”），她的话会马上弹出来，不经过任何推送服务。要让手机别把它关掉：",
                 color = p.muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (running) Text("已经允许在后台一直运行", color = p.muted, fontSize = 13.sp)
            else Button(onClick = a.keepRunning, colors = ButtonDefaults.buttonColors(containerColor = p.soft, contentColor = p.accent)) {
                Text("允许在后台一直运行")
            }
            Spacer(Modifier.height(8.dp))
            Text("小米手机还要在 设置 → 应用设置 → 应用管理 → Wife 里打开“自启动”，省电策略选“无限制”。", color = p.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Offline(screen, p, a.brain)
        Spacer(Modifier.height(24.dp))
    }
    if (clearing) {
        AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清掉手机上录的声纹？") },
                    confirmButton = { TextButton(onClick = { Wife.clearVoiceprint(); clearing = false }) { Text("清掉", color = p.danger) } },
                    dismissButton = { TextButton(onClick = { clearing = false }) { Text("不了") } })
    }
}

@Composable
private fun VoiceprintPage(screen: Screen, p: Palette, a: Actions, back: () -> Unit) {
    val recorder = remember { Recorder() }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Wife.screen.update { it.copy(enrolling = if (it.connected) "" else "要先连上电脑才能录") } }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Top("录手机声纹", p, back)
        Text("用平常说话的声音读下面这句：按住按钮读，读完松开。一共 ${SENTENCES.size} 句，电脑会记下手机里你的声音。",
             color = p.muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        if (index < SENTENCES.size) {
            Text("第 ${index + 1} 句，共 ${SENTENCES.size} 句", color = p.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Card(p) { Text(SENTENCES[index], color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
            HoldToTalk("按住 读这句", p, recorder, shortest = 1500, permitted = { screen.connected && a.microphone() },
                       sent = { recording, _ -> Wife.enroll(recording); index++ })
        } else {
            Text("录好了。以后手机传来的声音，电脑都会对一下是不是你。", color = p.text, fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (screen.enrolling.isNotEmpty()) Text(screen.enrolling, color = p.muted, fontSize = 13.sp)
        Text("电脑上已经有 ${screen.voiceprints} 段手机声纹", color = p.muted, fontSize = 12.sp)
    }
}

@Composable
private fun Offline(screen: Screen, p: Palette, pick: () -> Unit) {
    val (model, lora) = remember(screen.offline) { Wife.offline.files() }
    Card(p) {
        Text("连不上电脑时：手机上的她", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        val detail = screen.offline.ifEmpty {
            when {
                model == null -> "用数据线把电脑上她的大脑拷进手机的“下载”：两个 qwen2.5-7b-instruct 分片和 " +
                    "wife-chat.gguf（共 4.8 GB），再点下面选中它们。"
                Wife.offline.card() == null -> "大脑放好了。先在家和她连一次，手机会记下她是谁。"
                else -> "大脑放好了" + (if (lora == null) "（没有她的说话 LoRA）" else "") + "。连不上电脑时，你打的字由手机上的她回，" +
                    "和电脑上是同一个大脑，只是慢一些，也不能用她的声音。"
            }
        }
        Text(detail, color = p.muted, fontSize = 12.sp)
        if (model == null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = pick, enabled = !screen.thinking,
                   colors = ButtonDefaults.buttonColors(containerColor = p.soft, contentColor = p.accent)) { Text("选择大脑文件") }
        }
        if (model != null && !Wife.offline.awake) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { Wife.wakeOffline() }, enabled = !screen.thinking,
                   colors = ButtonDefaults.buttonColors(containerColor = p.soft, contentColor = p.accent)) { Text("叫醒手机上的她") }
        }
    }
}

// ── pairing ──

@Composable
private fun Pairing(screen: Screen, p: Palette, a: Actions) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(screen.her, p, 56.dp)
            Spacer(Modifier.width(14.dp))
            Text(screen.her, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Card(p) {
            Text("先和电脑上的她认识一下", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("在电脑上打开 Wife 的“手机”附属功能，点“显示配对码”，再用这里扫一下。手机和电脑要连着同一个 WiFi。" +
                 "配对码只能用一次，扫完就作废。", color = p.muted, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Button(onClick = a.scan, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("扫码配对") }
        }
        if (screen.note.isNotEmpty()) Text(screen.note, color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
    }
}

// ── small pieces ──

@Composable
private fun Avatar(name: String, p: Palette, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(p.soft), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = p.accent, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Glyph(text: String, background: Color?, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).then(if (background != null) Modifier.background(background) else Modifier)
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 18.sp)
    }
}

@Composable
private fun Rule(p: Palette) = Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.border))

@Composable
private fun Top(title: String, p: Palette, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹ 返回", color = p.accent, fontSize = 15.sp, modifier = Modifier.clickable(onClick = back))
        Spacer(Modifier.weight(1f))
        Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun Card(p: Palette, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(18.dp)) { content() }
}

@Composable
private fun Pair(label: String, value: String, p: Palette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = p.muted, fontSize = 14.sp)
    }
}
