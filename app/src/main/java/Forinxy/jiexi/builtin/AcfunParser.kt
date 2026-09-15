package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * AcFun 视频解析器：抓取页面 window.pageInfo（含 currentVideoInfo.ksPlayJson），
 * 提取 HLS 播放地址。按 Python 参考实现移植。
 */
internal class AcfunParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.ACFUN

    private val headers = mapOf("User-Agent" to PlatformHttp.PC_UA)

    private val videoIdPattern = Pattern.compile("acfun\\.cn/v/(ac\\d+)")
    private val pageInfoPattern =
        Pattern.compile("window\\.pageInfo\\s*=\\s*(?:window\\.videoInfo\\s*=\\s*)?(\\{.*?\\});", Pattern.DOTALL)

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "acfun parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val link = input.trim()
        val m = videoIdPattern.matcher(link)
        val videoId = if (m.find()) m.group(1) else null
            ?: return failResponse("无法识别 AcFun 视频链接")

        val pageInfo = fetchPageInfo(link)
            ?: return failResponse("视频不存在或已删除")

        val play = extractPlayUrl(pageInfo)
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = videoId
        md.title = pageInfo.jStr("title") ?: "无标题"
        md.author = pageInfo.jObj("user")?.jStr("name") ?: "未知作者"
        md.authorUid = pageInfo.jObj("user")?.jLong("id")?.toString()
        md.cover = pageInfo.jStr("coverUrl")
        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    private fun fetchPageInfo(url: String): JsonObject? {
        val resp = http.get(url, headers) ?: return null
        if (resp.statusCode != 200 || resp.body.isBlank()) return null
        val m = pageInfoPattern.matcher(resp.body)
        if (!m.find()) return null
        return parseJsonObj(m.group(1))
    }

    private fun extractPlayUrl(pageInfo: JsonObject): String? {
        val ksPlayJson = pageInfo.jObj("currentVideoInfo")?.jStr("ksPlayJson") ?: return null
        val playData = parseJsonObj(ksPlayJson) ?: return null
        val adaptationSet = playData.jArr("adaptationSet") ?: return null
        if (adaptationSet.size() == 0 || !adaptationSet[0].isJsonObject) return null
        val representation = adaptationSet[0].asJsonObject.jArr("representation") ?: return null
        if (representation.size() == 0 || !representation[0].isJsonObject) return null
        return representation[0].asJsonObject.jStr("url")
    }

    private companion object {
        const val TAG = "AcfunParser"
    }
}
