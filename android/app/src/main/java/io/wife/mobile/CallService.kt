package io.wife.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.wife.mobile.voice.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A call keeps going with the screen off: Android lets a foreground service with a notification use the
 *  microphone. */
class CallService : Service() {
    companion object {
        @Volatile var voice: Voice? = null
        private const val HANG_UP = "hang_up"

        fun dial(context: Context) = context.startForegroundService(Intent(context, CallService::class.java))
        fun hangUp(context: Context) = context.startService(Intent(context, CallService::class.java).setAction(HANG_UP))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HANG_UP) {
            Wife.link.call?.hangUp()
            return START_NOT_STICKY
        }
        startForeground(1, notice("正在找电脑…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (Wife.screen.value.dialing || Wife.screen.value.call) return START_NOT_STICKY
        Wife.screen.update { it.copy(dialing = true, note = "正在找电脑…") }
        Wife.scope.launch {
            val near = Wife.screen.value.near
            val call = Wife.link.dial(near) { call ->
                Wife.inCall(call)
                val voice = Voice(this@CallService, call).also { it.speaker = Wife.screen.value.speaker }
                call.onAudio = voice::heard
                CallService.voice = voice
            }
            if (call == null) {
                Wife.screen.update { it.copy(dialing = false) }
                Wife.note(unreachable())
                stopSelf()
                return@launch
            }
            voice?.start()
            getSystemService(NotificationManager::class.java).notify(1, notice("正在和${Wife.screen.value.her}说话"))
            while (!call.closed) {
                call.tick()
                delay(100)
            }
            voice?.stop()
            voice = null
            Wife.link.call = null
            Wife.ended()
            Wife.note("通话结束了")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun unreachable(): String {
        val here = Wife.link.here()
        val away = here.optJSONArray("away")?.length() ?: 0
        return when {
            away == 0 -> "连不上电脑。电脑没有外出用的地址（没有 IPv6），只能在同一个 WiFi 里连。"
            here.optString("find") == "pair" -> "连不上电脑。家里的地址可能变了：回家连上 WiFi 后重新扫码配对。"
            here.optString("find") == "dht" -> "连不上电脑，在 BT 网络里也没查到它。电脑要开着，手机端程序要在运行。"
            else -> "连不上电脑。家里的地址可能变了，回家连上 WiFi 就会更新；也可能电脑没开、手机端程序没在运行。"
        } + " 也可以叫醒手机上的她。"
    }

    private fun notice(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("call", "通话", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hang = PendingIntent.getService(this, 1, Intent(this, CallService::class.java).setAction(HANG_UP),
                                            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "call")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Wife")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "挂断", hang).build())
            .setOngoing(true)
            .build()
    }
}
