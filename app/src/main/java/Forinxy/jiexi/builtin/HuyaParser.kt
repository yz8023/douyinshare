package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * 虎牙短视频解析器：提取 URL 中的 videoId，调用 liveapi.huya.com
 * getMomentContent 接口取回 moment 视频信息。按 Python 参考实现移植。
 */
internal class HuyaParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.HUYA

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://www.huya.com/"
    )

    private val digitsPattern = Pattern.compile("(\\d+)")

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "huya parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val videoId = extractVideoId(input.trim())
            ?: return failResponse("无法识别虎牙视频链接")

        val resp = http.get(
            "https://liveapi.huya.com/moment/getMomentContent?videoId=$videoId",
            headers
        ) ?: return failResponse("视频不存在或已删除")
        if (resp.statusCode != 200) return failResponse("视频不存在或已删除")

        val payload = parseJsonObj(resp.body) ?: return failResponse("视频不存在或已删除")
        val videoInfo = payload.jObj("data")?.jObj("moment")?.jObj("videoInfo")
            ?: return failResponse("视频不存在或已删除")

        val play = firstDefinitionUrl(videoInfo)
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = videoId
        md.title = videoInfo.jStr("videoTitle") ?: "无标题"
        md.cover = videoInfo.jStr("videoCover")
        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    /** 从 URL 中提取纯数字 videoId（虎牙分享链多带 vtype 等参数） */
    private fun extractVideoId(input: String): String? {
        if (input.isBlank()) return null
        val m = digitsPattern.matcher(input)
        return if (m.find()) m.group(1) else null
    }

    private fun firstDefinitionUrl(videoInfo: JsonObject): String? {
        val definitions = videoInfo.jArr("definitions") ?: return null
        for (element in definitions) {
            if (element.isJsonObject) {
                element.asJsonObject.jStr("url")?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "HuyaParser"
    }
}
