package Forinxy.jiexi

import android.content.Context

/**
 * 下载画质的交互方式设置：
 * - ASK_EVERY: 每次下载（单条/批量）前都弹窗询问画质与大小（默认）
 * - USE_SAVED: 不询问，直接用「保存设置」里的原画质/最高画质开关
 */
object QualitySelectionPreferences {
    private const val PREF_NAME = "dyparse_quality"
    private const val KEY_ASK_EVERY_DOWNLOAD = "ask_every_download"

    /** 每次下载前都询问画质 */
    const val MODE_ASK_EVERY = true
    /** 仅按设置里保存过的画质开关保存，不再询问 */
    const val MODE_USE_SAVED = false

    fun isAskEveryDownload(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASK_EVERY_DOWNLOAD, MODE_ASK_EVERY)
    }

    fun setAskEveryDownload(context: Context, askEvery: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASK_EVERY_DOWNLOAD, askEvery)
            .apply()
    }
}