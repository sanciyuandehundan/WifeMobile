package io.wife.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Wife.start(this)
    }
}

/** Cream and rose by day, cocoa and candlelight at night: the same colours as Wife's windows on the computer. */
private data class Palette(val window: Color, val surface: Color, val text: Color, val muted: Color, val accent: Color,
                           val soft: Color, val border: Color)

private val Day = Palette(Color(0xFFFFF9F5), Color(0xFFFFFFFF), Color(0xFF3E302C), Color(0xFF9A857C), Color(0xFFD9677C),
                          Color(0xFFFDE7EA), Color(0xFFF0DED5))
private val Night = Palette(Color(0xFF221C1B), Color(0xFF2C2422), Color(0xFFF4EAE5), Color(0xFFB5A098), Color(0xFFF29BA8),
                            Color(0xFF4A2D33), Color(0xFF3E322F))

class MainActivity : ComponentActivity() {
    private val scan = registerForActivityResult(ScanContract()) { result ->
        val qr = result.contents ?: return@registerForActivityResult
        Wife.note("正在和电脑配对…")
        Wife.scope.launch {
            val problem = Wife.link.pair(qr, Build.MODEL)
            Wife.refresh()
            Wife.note(problem ?: "配对好了。以后点“打给她”就能和她说话。")
        }
    }

    private val brain = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        Wife.importBrain(contentResolver, picked)
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CallService.dial(this) else Wife.note("没有麦克风权限就没法通话")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val palette = if (isSystemInDarkTheme()) Night else Day
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = palette.accent, background = palette.window)
                         else lightColorScheme(primary = palette.accent, background = palette.window)
            MaterialTheme(colorScheme = scheme) {
                val screen by Wife.screen.collectAsState()
                Home(screen, palette, pick = { brain.launch(arrayOf("*/*")) },
                     scan = { scan.launch(ScanOptions().setPrompt("扫电脑上的配对码").setBeepEnabled(false).setOrientationLocked(false)) },
                     call = {
                         if (screen.call || screen.dialing) CallService.hangUp(this)
                         else microphone.launch(Manifest.permission.RECORD_AUDIO)
                     })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wife.link.where()                      // at home: learn the computer's current addresses
    }
}

@Composable
private fun Home(screen: Screen, p: Palette, pick: () -> Unit, scan: () -> Unit, call: () -> Unit) {
    Column(Modifier.fillMaxSize().background(p.window).systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(p.soft), contentAlignment = Alignment.Center) {
                Text(screen.her.take(1), color = p.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(screen.her, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(status(screen), color = p.muted, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        if (!screen.paired) {
            Card(p) {
                Text("先和电脑上的她认识一下", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("在电脑上打开 Wife 的“手机”附属功能，点“显示配对码”，再用这里扫一下。手机和电脑要连着同一个 WiFi。" +
                     "配对码只能用一次，扫完就作废。", color = p.muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = scan, colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("扫码配对") }
            }
            Note(screen.note, p)
            return@Column
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val live = screen.call || screen.dialing
            Box(Modifier.size(132.dp).clip(CircleShape).background(if (live) p.soft else p.accent).clickable(onClick = call),
                contentAlignment = Alignment.Center) {
                Text(if (screen.dialing) "取消" else if (screen.call) "挂断" else "打给她",
                     color = if (live) p.accent else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(p) {
            Toggle("我在电脑旁边", "她的声音从手机出来，手机当你的耳机和麦克风", screen.near, p) { Wife.near(it) }
            Toggle("外放", "关掉就用听筒；戴着耳机时总是从耳机出来", screen.speaker, p) { Wife.speaker(it) }
        }
        Note(screen.note, p)
        val list = rememberLazyListState()
        LaunchedEffect(screen.lines.size, screen.lines.lastOrNull()?.text) {
            if (screen.lines.isNotEmpty()) list.animateScrollToItem(screen.lines.size - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(screen.lines) { line -> Bubble(line, p) }
        }
        Typing(p, screen.thinking)
        Offline(screen, p, pick)
        TextButton(onClick = scan, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("重新配对", color = p.muted, fontSize = 13.sp)
        }
    }
}

private fun status(screen: Screen): String = when {
    !screen.paired -> "还没有和电脑配对"
    screen.dialing -> "正在找电脑…"
    screen.call && screen.awake == false -> "接通了，但电脑上的她在休息"
    screen.call -> "正在说话 · " + if (screen.near) "在电脑旁" else "在外面"
    else -> "和 ${screen.computer.ifEmpty { "电脑" }} 配对了" + if (Wife.offline.awake) " · 手机上的她醒着" else ""
}

@Composable
private fun Card(p: Palette, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(18.dp)) { content() }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, p: Palette, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 15.sp)
            Text(detail, color = p.muted, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = change,
               colors = SwitchDefaults.colors(checkedTrackColor = p.accent, uncheckedTrackColor = p.border))
    }
}

@Composable
private fun Note(text: String, p: Palette) {
    if (text.isNotEmpty()) Text(text, color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun Bubble(line: Line, p: Palette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.his) Arrangement.End else Arrangement.Start) {
        Text(line.text + if (line.phone) "  · 手机上的她" else "",
             color = if (line.his) Color.White else p.text, fontSize = 15.sp,
             modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp))
                 .background(if (line.his) p.accent else p.surface).padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@Composable
private fun Typing(p: Palette, thinking: Boolean) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                          placeholder = { Text(if (thinking) "她在想…" else "打字给她") }, singleLine = true,
                          shape = RoundedCornerShape(20.dp),
                          colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.border,
                                                                    focusedTextColor = p.text, unfocusedTextColor = p.text))
        Spacer(Modifier.width(8.dp))
        Button(onClick = { Wife.type(text); text = "" }, enabled = text.isNotBlank() && !thinking,
               colors = ButtonDefaults.buttonColors(containerColor = p.accent)) { Text("说") }
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
                Wife.offline.card() == null -> "大脑放好了。先在家和她通一次话，手机会记下她是谁。"
                else -> "大脑放好了" + (if (lora == null) "（没有她的说话 LoRA）" else "") + "。她和电脑上是同一个大脑，只是慢一些，也不能用她的声音。"
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
