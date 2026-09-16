package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * 微视解析器：抓取页面 window.Vise.initState，从 feedsList[0] 提取
 * 视频地址/标题/封面/作者。按 Python 参考实现移植。
 */
internal class WeishiParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.WEISHI

    private val initStatePattern =
        Pattern.compile("window\\.Vise\\.initState\\s*=\\s*(\\{.*\\};)", Pattern.DOTALL)

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "weishi parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        // 依次尝试 PC / 移动 UA：真机上个别网络下某一 UA 请求失败时自动回退
        var lastError: String? = null
        for (ua in listOf(PlatformHttp.PC_UA, PlatformHttp.MOBILE_UA)) {
            val headers = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://isee.weishi.qq.com"
            )
            val resp = http.get(input.trim(), headers)
            if (resp == null) {
                lastError = lastError ?: "无法获取页面内容"
                continue
            }
            if (resp.statusCode != 200 || resp.body.isBlank()) {
                lastError = "视频不存在或已删除"
                continue
            }
            val m = initStatePattern.matcher(resp.body)
            if (!m.find()) {
                lastError = "无法解析微视页面数据"
                continue
            }
            val state = parseJsonObj(m.group(1)?.trimEnd(';')) ?: run {
                lastError = "无法解析微视页面数据"
                continue
            }
            val feed = firstFeed(state) ?: run {
                lastError = "视频不存在或已删除"
                continue
            }
            val videoUrl = feed.jStr("videoUrl")?.replace("\\u002F", "/")
            if (videoUrl.isNullOrBlank()) {
                lastError = "无法获取播放地址"
                continue
            }

            val md = MediaData()
            md.type = "video"
            md.inputUrl = input
            md.videoId = extractFeedId(feed).orEmpty()
            md.title = feed.jStr("feedDesc") ?: "无标题"
            md.cover = feed.jStr("videoCover")?.replace("\\u002F", "/")

            val poster = feed.jObj("poster")
            md.author = poster?.jStr("nick") ?: "未知作者"
            md.authorUid = poster?.jRawStr("id")
            md.authorSecUid = null

            md.playUrl = videoUrl
            md.rawPlayUrl = videoUrl
            md.addQuality(label = "默认", ratio = "default", url = videoUrl)
            return md.toServerJson()
        }
        return failResponse(lastError ?: "无法获取页面内容")
    }

    private fun firstFeed(state: JsonObject): JsonObject? {
        val feeds = state.jArr("feedsList") ?: return null
        if (feeds.size() == 0) return null
        for (element in feeds) {
            if (element.isJsonObject) return element.asJsonObject
        }
        return null
    }

    private fun extractFeedId(feed: JsonObject): String? =
        feed.jStr("feedId") ?: feed.jStr("feed_id") ?: feed.jStr("id")

    private companion object {
        const val TAG = "WeishiParser"
    }
}
