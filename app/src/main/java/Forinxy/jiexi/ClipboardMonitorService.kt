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
import Forinxy.jiexi.data.local.ClipboardRecordEntity
import Forinxy.jiexi.data.local.HistoryDatabase
import Forinxy.jiexi.data.ParseResult
import com.google.gson.Gson
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
        private const val DEDUPE_WINDOW_MS = 5 * 60 * 1000L

        const val STATUS_PENDING = "PENDING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var clipboardManager: ClipboardManager

    /** 前台时监听剪贴板变更，即时触发解析（相比 3s 轮询更灵敏） */
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** 完整解析结果序列化（卡片详情复用） */
    private val gson = Gson()

    /** 内容哈希 → 首次处理时间，用于窗口去重 */
    private val seenTimes = HashMap<String, Long>()

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
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = runCatching { clip.getItemAt(0).coerceToText(this).toString() }.getOrNull() ?: return
        if (!ClipboardShareContent.isDouyinShare(text)) return
        val parseInput = ClipboardShareContent.extractParseInput(text) ?: return
        val hash = ClipboardShareContent.contentHash(text)
        if (recentlyHandled(hash)) return

        val db = HistoryDatabase.get(this)
        val since = System.currentTimeMillis() - DEDUPE_WINDOW_MS
        if (db.historyDao().countClipboardRecordsSince(hash, since) > 0) return

        val id = db.historyDao().insertClipboardRecord(
            ClipboardRecordEntity(
                content = text,
                contentHash = hash,
                copiedAt = System.currentTimeMillis(),
                status = STATUS_PENDING
            )
        )

        val result = try {
            ClipboardParseEngine.parse(this, parseInput)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ParseResult.Error("解析失败(${e.message})")
        }

        val success = result is ParseResult.Success
        val successItem = result as? ParseResult.Success
        db.historyDao().updateClipboardRecord(
            id = id,
            status = if (success) STATUS_DONE else STATUS_FAILED,
            videoId = successItem?.videoId,
            title = successItem?.title,
            author = successItem?.author,
            type = successItem?.type,
            cover = successItem?.cover,
            payloadJson = successItem?.let { runCatching { gson.toJson(it) }.getOrNull() },
            parseError = (result as? ParseResult.Error)?.msg,
            parsedAt = if (success) System.currentTimeMillis() else null
        )

        val notifyText = if (success) {
            val title = (result as ParseResult.Success).title ?: "未知"
            "已解析：$title"
        } else {
            "解析失败：${(result as? ParseResult.Error)?.msg ?: "未知错误"}"
        }
        lastNotifyText = notifyText
        if (running) {
            startForegroundCompat(buildNotification(notifyText))
        }
    }

    /** 窗口内是否已处理过同一内容 */
    private fun recentlyHandled(hash: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - DEDUPE_WINDOW_MS
        val iterator = seenTimes.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        return seenTimes.put(hash, now) != null
    }
}