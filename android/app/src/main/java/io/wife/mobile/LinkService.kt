package io.wife.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Always connected to the computer, so what she says reaches him even with Wife closed (no push service: protocol.md
 *  送到). Android keeps it running as a foreground service with a quiet notification; during a call it may also use
 *  the microphone. */
class LinkService : Service() {
    companion object {
        private const val HANG_UP = "hang_up"
        private var shown = 100
        @Volatile private var running: LinkService? = null

        fun start(context: Context) = context.startForegroundService(Intent(context, LinkService::class.java))

        /** A call starts (from the screen, so Android allows the microphone) or ends. */
        fun calling(context: Context, on: Boolean) {
            val service = running
            if (service != null) Handler(Looper.getMainLooper()).post { service.foreground(on) }
            else if (on) start(context)
        }

        /** Her words while Wife is not in front. */
        fun notify(context: Context, her: String, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("her", "她的消息", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            manager.notify(++shown, Notification.Builder(context, "her")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(her)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }

        /** He opened Wife: her messages are on the screen now. */
        fun quiet(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.id > 100 }.forEach { manager.cancel(it.id) }
        }
    }

    private var keeping: Job? = null
    private var showing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HANG_UP) {
            Wife.hangUp()
            return START_STICKY
        }
        running = this
        foreground(Wife.screen.value.call)
        if (keeping == null) keeping = Wife.scope.launch { Wife.keepConnected() }
        if (showing == null) showing = Wife.scope.launch {
            Wife.screen.map { text(it.call) to it.call }.distinctUntilChanged().collect { (text, inCall) ->
                getSystemService(NotificationManager::class.java).notify(1, notice(text, inCall))
            }
        }
        return START_STICKY
    }

    private fun foreground(inCall: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notice(text(inCall), inCall), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                                (if (inCall) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
        } else {
            startForeground(1, notice(text(inCall), inCall))
        }
    }

    override fun onDestroy() {
        running = null
        keeping?.cancel()
        showing?.cancel()
        super.onDestroy()
    }

    private fun text(inCall: Boolean): String {
        val screen = Wife.screen.value
        return when {
            inCall -> "正在和${screen.her}通话"
            screen.connected -> "和${screen.her}连着"
            else -> "现在连不上电脑，过一会儿再试"
        }
    }

    private fun notice(text: String, inCall: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("link", "和她连着", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "link")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Wife")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (inCall) {
            val hang = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(HANG_UP),
                                                PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "挂断", hang).build())
        }
        return builder.build()
    }
}

/** After the phone starts (or Wife is updated): connected again without opening the app. */
class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Wife.screen.value.paired) LinkService.start(context)
    }
}
