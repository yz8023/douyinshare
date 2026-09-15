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

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://isee.weishi.qq.com"
    )

    private val initStatePattern =
        Pattern.compile("window\\.Vise\\.initState\\s*=\\s*(\\{.*?\\};)", Pattern.DOTALL)

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
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("视频不存在或已删除")

        val m = initStatePattern.matcher(resp.body)
        if (!m.find()) return failResponse("无法解析微视页面数据")

        val state = parseJsonObj(m.group(1)) ?: return failResponse("无法解析微视页面数据")
        val feed = firstFeed(state) ?: return failResponse("视频不存在或已删除")

        val videoUrl = feed.jStr("videoUrl")?.replace("\\u002F", "/")
            ?: return failResponse("无法获取播放地址")

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
