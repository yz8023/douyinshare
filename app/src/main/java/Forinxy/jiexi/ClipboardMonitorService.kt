package Forinxy.jiexi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 剪贴板监听前台服务：
 * 周期性读取系统剪贴板，识别抖音分享内容，去重后写入 clipboard_records 表，
 * 并自动调用解析接口回填标题/类型（状态 PENDING → DONE / FAILED）。
 *
 * 注意：Android 10+ 限制后台应用读取剪贴板，只有应用处于前台（或默认输入法）
 * 时才能读到内容，因此在应用打开/使用期间复制的内容会被自动捕获。
 */
class ClipboardMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "clipboard_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 3000L

        /** 去重窗口：同一段（哈希相同）内容在此窗口内不重复入账/解析 */
        const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L

        const val STATUS_PENDING = "PENDING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"

        /** 请求立即读取一次剪贴板（悬浮球点按后 App 回到前台时使用） */
        const val ACTION_POLL_NOW = "Forinxy.jiexi.action.POLL_NOW"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val app = context.applicationContext
            if (running) return
            try {
                val intent = Intent(app, ClipboardMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, ClipboardMonitorService::class.java))
        }

        /** 让已在运行的服务立刻读取一次剪贴板（App 处于前台时调用才有效） */
        fun requestPollNow(context: Context) {
            if (!running) return
            val app = context.applicationContext
            runCatching {
                app.startService(
                    Intent(app, ClipboardMonitorService::class.java).setAction(ACTION_POLL_NOW)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clipboardManager: ClipboardManager

    /** 前台时监听剪贴板变更，即时触发解析（相比 3s 轮询更灵敏） */
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private var lastNotifyText = "正在监听剪贴板..."
    private var monitoring = false

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
        registerClipboardListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 旧版本系统重启服务 / 重复 start 时避免创建多个轮询循环
        runCatching { startForegroundCompat(buildNotification(lastNotifyText)) }
            .onFailure { stopSelf() }
        running = true
        if (!monitoring) {
            monitoring = true
            scope.launch { monitorLoop() }
        }
        if (intent?.action == ACTION_POLL_NOW) {
            scope.launch { pollClipboardOnce() }
        }
        return START_STICKY
    }

    /** 注册剪贴板变更监听：应用在前台时复制内容可被即时捕获，不受 3s 轮询间隔限制 */
    private fun registerClipboardListener() {
        if (clipboardListener != null) return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (running) {
                scope.launch { pollClipboardOnce() }
            }
        }
        clipboardListener = listener
        runCatching { clipboardManager.addPrimaryClipChangedListener(listener) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clipboardListener?.let { runCatching { clipboardManager.removePrimaryClipChangedListener(it) } }
        clipboardListener = null
        running = false
        monitoring = false
        scope.cancel()
        ClipboardParseEngine.release()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "剪贴板监听",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台读取抖音分享内容并自动解析"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        builder
            .setContentTitle("剪贴板监听中")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
        return builder.build()
    }

    private suspend fun monitorLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                pollClipboardOnce()
            } catch (_: Throwable) {
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollClipboardOnce() {
        val notifyText = try {
            ClipboardAutoParse.capture(this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        lastNotifyText = notifyText
        if (running) {
            startForegroundCompat(buildNotification(notifyText))
        }
    }
}