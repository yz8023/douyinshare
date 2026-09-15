package Forinxy.jiexi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 悬浮球自绘 View：玻璃质感圆球 + 链接图标。
 *
 * 动效参考 motion-web 的思路：
 * - 按下即时回弹缩放（弹簧感）
 * - 拖动时随位移轻微倾斜，松手后回正
 * - 空闲时持续「呼吸」缩放，保持存在感
 */
class FloatingBallView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        color = Color.argb(165, 255, 255, 255)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.4f)
        color = Color.WHITE
    }

    /** 按下缩放（1 → 0.86） */
    private var pressScale = 1f

    /** 呼吸缩放（1 → 1.05） */
    private var breathScale = 1f

    /** 拖动倾斜角度 */
    private var rotationDeg = 0f

    private var pressAnimator: ValueAnimator? = null
    private var breathAnimator: ValueAnimator? = null
    private var tiltAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f
        bodyPaint.shader = RadialGradient(
            cx - radius * 0.35f,
            cy - radius * 0.42f,
            radius * 1.4f,
            intArrayOf(
                Color.parseColor("#9A8CFF"),
                Color.parseColor("#5B6CFF"),
                Color.parseColor("#3A45C9")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            Color.argb(95, 110, 122, 255),
            Color.argb(0, 110, 122, 255),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.9f
        val scale = pressScale * breathScale

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.rotate(rotationDeg, cx, cy)

        // 外发光
        canvas.drawCircle(cx, cy, radius * 1.3f, glowPaint)
        // 球体
        canvas.drawCircle(cx, cy, radius, bodyPaint)
        // 顶部高光弧
        canvas.drawArc(
            cx - radius * 0.64f,
            cy - radius * 0.64f,
            cx + radius * 0.64f,
            cy + radius * 0.64f,
            198f,
            112f,
            false,
            rimPaint
        )
        // 外描边
        rimPaint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius, rimPaint)

        drawLinkIcon(canvas, cx, cy, radius)

        canvas.restore()
    }

    /** 「链接」图标：两段圆角矩形交错组成链条 */
    private fun drawLinkIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val half = radius * 0.36f
        val thickness = radius * 0.26f
        canvas.save()
        canvas.rotate(-45f, cx, cy)
        val left = RectF(
            cx - half,
            cy - thickness / 2f,
            cx + thickness * 0.22f,
            cy + thickness / 2f
        )
        val right = RectF(
            cx - thickness * 0.22f,
            cy - thickness / 2f,
            cx + half,
            cy + thickness / 2f
        )
        val r = thickness / 2f
        canvas.drawRoundRect(left, r, r, iconPaint)
        canvas.drawRoundRect(right, r, r, iconPaint)
        canvas.restore()
    }

    /** 按下 / 抬起：即时回弹缩放 */
    fun setPressedFeedback(pressed: Boolean) {
        animatePressScale(if (pressed) 0.86f else 1f)
    }

    private fun animatePressScale(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = if (target < 1f) 120L else 240L
            interpolator =
                if (target < 1f) DecelerateInterpolator() else OvershootInterpolator(3.6f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 拖动时按水平位移倾斜 */
    fun applyDragTilt(degrees: Float) {
        rotationDeg = degrees.coerceIn(-16f, 16f)
        invalidate()
    }

    /** 松手：弹性回正 */
    fun settleTilt() {
        if (rotationDeg == 0f) return
        tiltAnimator?.cancel()
        tiltAnimator = ValueAnimator.ofFloat(rotationDeg, 0f).apply {
            duration = 460L
            interpolator = OvershootInterpolator(2.4f)
            addUpdateListener {
                rotationDeg = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 空闲呼吸动画 */
    fun startBreathing() {
        if (breathAnimator != null) return
        breathAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
            duration = 1600L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                breathScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopBreathing() {
        breathAnimator?.cancel()
        breathAnimator = null
        breathScale = 1f
        invalidate()
    }

    /** 播放一次「被点击」的脉冲，用于反馈解析已触发 */
    fun playTapPulse(onEnd: (() -> Unit)? = null) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(1f, 0.78f, 1f).apply {
            duration = 320L
            interpolator = OvershootInterpolator(3.2f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
