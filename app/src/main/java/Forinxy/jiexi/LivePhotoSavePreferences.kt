package Forinxy.jiexi

import android.content.Context

/**
 * 实况图保存偏好：选择保存模式，以及实况动态视频是否保留音频轨。
 *
 * PAIR（合并为实况，默认）：静态图 + 配套动态视频按相同基名配对保存，
 * 支持 Live Photo 的相册（如 Google Photos）可自动识别为一张实况图。
 * IMAGE_ONLY：仅保存静态图片。
 * VIDEO_ONLY：仅保存实况动态视频。
 *
 * includeAudio（默认 true）：实况动态视频保留其自带音频轨；设为 false 时
 * 下载后用系统原生 MediaMuxer 重封装剥离音频轨（剥离失败自动保留原文件）。
 */
object LivePhotoSavePreferences {
    private const val PREF_NAME = "dyparse_live_photo"
    private const val KEY_MODE = "save_mode"
    private const val KEY_INCLUDE_AUDIO = "include_audio"

    enum class Mode(val label: String, val description: String) {
        PAIR("合并为实况", "图片与动态视频配对保存，支持实况的相册可识别"),
        IMAGE_ONLY("图片格式", "仅保存静态图片"),
        VIDEO_ONLY("视频格式", "仅保存实况动态视频");

        companion object {
            fun fromName(name: String?): Mode {
                return entries.firstOrNull { it.name == name } ?: PAIR
            }
        }
    }

    fun getMode(context: Context): Mode {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
            .let { Mode.fromName(it) }
    }

    fun setMode(context: Context, mode: Mode) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun includeAudio(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INCLUDE_AUDIO, true)
    }

    fun setIncludeAudio(context: Context, includeAudio: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INCLUDE_AUDIO, includeAudio)
            .apply()
    }
}
