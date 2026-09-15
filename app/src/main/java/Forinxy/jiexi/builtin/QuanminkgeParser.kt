package Forinxy.jiexi.builtin

import android.util.Log
import java.net.URI
import java.util.regex.Pattern

/**
 * 全民K歌解析器：从分享链接提取 s 参数，抓取 kg.qq.com/node/play
 * 页面 window.__DATA__，提取演唱视频。按 Python 参考实现移植。
 */
internal class QuanminkgeParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.QUANMIN_KGE

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://kg.qq.com/"
    )

    private val dataPattern =
        Pattern.compile("window\\.__DATA__\\s*=\\s*(\\{.*?\\})\\s*;\\s*</script>", Pattern.DOTALL)

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "quanminkge parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val shareId = extractShareId(input.trim())
            ?: return failResponse("无法识别全民K歌链接")

        val resp = http.get("https://kg.qq.com/node/play?s=$shareId", headers)
            ?: return failResponse("视频不存在或已删除")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("视频不存在或已删除")

        val m = dataPattern.matcher(resp.body)
        if (!m.find()) return failResponse("无法解析全民K歌页面数据")
        val data = parseJsonObj(m.group(1)) ?: return failResponse("无法解析全民K歌页面数据")
        val detail = data.jObj("detail") ?: return failResponse("视频不存在或已删除")

        val play = detail.jStr("playurl_video")
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = shareId
        md.title = detail.jStr("content") ?: "无标题"
        md.cover = detail.jStr("cover")
        md.author = detail.jObj("singer")?.jStr("nick") ?: "未知作者"
        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    private fun extractShareId(input: String): String? {
        if (input.isBlank()) return null
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val query = uri.query ?: return null
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key == "s" && value.isNotBlank()) return value
        }
        return null
    }

    private companion object {
        const val TAG = "QuanminkgeParser"
    }
}
