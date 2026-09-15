package Forinxy.jiexi

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import kotlin.math.hypot

/**
 * 悬浮球前台服务。
 *
 * 悬浮球是一个始终置顶的可拖动小按钮，点按后把 App 切到前台；由于
 * Android 10+ 只有在应用处于前台（获得焦点）时才能读取剪贴板，因此
 * 「点按悬浮球 → App 回到前台 → 立刻读取并解析剪贴板」构成了后台自动
 * 解析的实际可行方案。
 *
 * 窗口不使用 FLAG_NOT_FOCUSABLE 之外的可聚焦标志，避免抢占其他应用的输入焦点。
 */
class FloatingBallService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_ball"
        private const val NOTIFICATION_ID = 1002
        private const val BALL_SIZE_DP = 56
        private const val TAP_MAX_DURATION_MS = 300L

        @Volatile
        var running = false
            private set

        @Volatile
        private var instance: FloatingBallService? = null

        fun start(context: Context) {
            val app = context.applicationContext
            if (running) return
            if (!FloatingWindowPreferences.canDrawOverlays(app)) return
            try {
                val intent = Intent(app, FloatingBallService::class.java)
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
            app.stopService(Intent(app, FloatingBallService::class.java))
        }

        /** 运行中实时更新透明度（设置页滑杆） */
        fun updateAlpha(alpha: Float) {
            instance?.applyAlpha(alpha)
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: FloatingBallView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var ballSizePx = 0
    private var touchSlop = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var downTime = 0L
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        ballSizePx = (BALL_SIZE_DP * metrics.density).toInt()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FloatingWindowPreferences.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching { startForegroundCompat(buildNotification()) }
            .isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (ballView == null) {
            val added = runCatching { addBall() }.isSuccess
            if (!added) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        instance = null
        ballView?.let { view ->
            view.stopBreathing()
            runCatching { windowManager.removeView(view) }
        }
        ballView = null
        layoutParams = null
        super.onDestroy()
    }

    private fun addBall() {
        val view = FloatingBallView(this).apply {
            alpha = FloatingWindowPreferences.getAlpha(this@FloatingBallService)
        }
        val params = WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - ballSizePx
            y = (screenHeight * 0.42f).toInt()
        }
        view.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(view, params)
        ballView = view
        layoutParams = params
        view.startBreathing()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = ballView ?: return false
        val params = layoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params.x
                downParamY = params.y
                downTime = System.currentTimeMillis()
                dragging = false
                view.setPressedFeedback(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    params.x = (downParamX + dx).toInt().coerceIn(0, screenWidth - ballSizePx)
                    params.y = (downParamY + dy).toInt().coerceIn(0, screenHeight - ballSizePx)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    view.applyDragTilt(dx / ballSizePx * 26f)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                view.setPressedFeedback(false)
                val tapped = !dragging && System.currentTimeMillis() - downTime < TAP_MAX_DURATION_MS
                if (tapped) {
                    view.playTapPulse()
                    openAppAndReadClipboard()
                } else {
                    view.settleTilt()
                    snapToEdge()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                view.setPressedFeedback(false)
                view.settleTilt()
                snapToEdge()
                return true
            }
        }
        return false
    }

    /** 松手后弹性吸附到左右最近边缘 */
    private fun snapToEdge() {
        val view = ballView ?: return
        val params = layoutParams ?: return
        val maxX = (screenWidth - ballSizePx).coerceAtLeast(0)
        val center = params.x + ballSizePx / 2f
        val targetX = if (center < screenWidth / 2f) 0 else maxX
        if (params.x == targetX) return
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 320L
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                params.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private fun openAppAndReadClipboard() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun applyAlpha(alpha: Float) {
        val scaled = alpha.coerceIn(FloatingWindowPreferences.MIN_ALPHA, FloatingWindowPreferences.MAX_ALPHA)
        ballView?.alpha = scaled
        layoutParams?.let { params ->
            params.alpha = scaled
            ballView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮球",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "常驻悬浮球，点按即可快速解析剪贴板链接"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
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
            .setContentTitle("悬浮球已开启")
            .setContentText("点按悬浮球即可快速解析剪贴板链接")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
        return builder.build()
    }
}
