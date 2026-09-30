package Forinxy.jiexi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无障碍剪贴板监听：捕获系统任意界面上的复制动作后读取剪贴板并自动解析。
 *
 * Android 10+ 后台应用不能直接读取系统剪贴板（只有获得焦点的应用或默认输入法可以），
 * 但无障碍服务不受此限制，因此本服务作为「剪贴板监听服务」在应用处于后台时的补充通道，
 * 与「悬浮球即时读取」共同构成双通道方案。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val CHANNEL_ID = "clipboard_monitor"
        private const val NOTIFICATION_ID = 1003

        /** 复制事件出现后等待剪贴板内容真正更新再读取 */
        private const val READ_DELAY_MS = 300L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            return
        }
        // 复制动作常伴随文本变化事件；延迟一小段等剪贴板更新后再读取，避免读到旧内容
        scope.launch {
            delay(READ_DELAY_MS)
            try {
                ClipboardAutoParse.capture(this@ClipboardAccessibilityService)
                    ?.let { notifyText -> postNotification(notifyText) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun postNotification(text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ensureChannel()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("已捕获抖音分享")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun ensureChannel() {
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
}
