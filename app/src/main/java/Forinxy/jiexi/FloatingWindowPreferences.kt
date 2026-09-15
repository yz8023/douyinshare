package Forinxy.jiexi

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 悬浮窗（悬浮球）偏好：是否展示、整体透明度。
 *
 * 悬浮球本身不授予后台读取剪贴板的能力（Android 10+ 仅前台/默认输入法可读），
 * 它的作用是提供一个随时可点的入口：点一下把 App 切到前台，此时 App 即可读取
 * 剪贴板并解析，从而绕开"必须手动回到 App"的操作成本。
 */
object FloatingWindowPreferences {
    private const val PREF_NAME = "dyparse_floating_window"
    private const val KEY_ENABLED = "floating_enabled"
    private const val KEY_ALPHA = "floating_alpha"

    /** 透明度下限：保证按钮仍可见可点 */
    const val MIN_ALPHA = 0.3f
    const val MAX_ALPHA = 1.0f
    const val DEFAULT_ALPHA = 0.9f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getAlpha(context: Context): Float =
        prefs(context).getFloat(KEY_ALPHA, DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA)

    fun setAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_ALPHA, alpha.coerceIn(MIN_ALPHA, MAX_ALPHA))
            .apply()
    }

    /** 是否已获得「显示在其他应用上层」权限 */
    fun canDrawOverlays(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching { Settings.canDrawOverlays(context.applicationContext) }.getOrDefault(false)
    }
}
