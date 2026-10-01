package Forinxy.jiexi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Opt-in root worker. It polls only the clipboard and feeds the normal parser queue. */
class RootClipboardService : Service() {
    companion object {
        private const val CHANNEL = "root_clipboard"
        private const val NOTIFICATION = 1002
        private const val POLL_MS = 1800L
        @Volatile var running = false
            private set

        fun start(context: Context) {
            val app = context.applicationContext
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(Intent(app, RootClipboardService::class.java))
                } else app.startService(Intent(app, RootClipboardService::class.java))
            }
        }
        fun stop(context: Context) = context.applicationContext.stopService(
            Intent(context.applicationContext, RootClipboardService::class.java)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitoring = false
    private var lastHash: String? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Root 自动解析", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("Root 自动解析已开启")
            .setContentText("仅监听剪贴板中的抖音分享内容")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(
                NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ) else startForeground(NOTIFICATION, notification)
        }.onFailure { stopSelf() }
        running = true
        if (!monitoring) {
            monitoring = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive && running) {
            val text = RootAccess.readClipboard()
            if (!text.isNullOrBlank()) {
                val hash = ClipboardShareContent.contentHash(text)
                if (hash != lastHash) {
                    lastHash = hash
                    try {
                        ClipboardAutoParse.capture(this@RootClipboardService, text)
                    } catch (_: Exception) {
                        // 下一轮继续监听；单次解析失败不能终止 Root 服务。
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        monitoring = false
        scope.cancel()
        super.onDestroy()
    }
}
