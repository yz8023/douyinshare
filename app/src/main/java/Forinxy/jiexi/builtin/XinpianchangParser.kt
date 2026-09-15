package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * 新片场解析器：先用移动端文章 API 取作品元数据，再调 mod-api 取
 * 多清晰度播放地址。按 Python 参考实现移植。
 */
internal class XinpianchangParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.XINPIANCHANG

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Accept" to "application/json, text/plain, */*"
    )

    private val articleIdPattern = Pattern.compile("(?:a|article/)?(\\d+)")

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "xinpianchang parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val articleId = extractArticleId(input.trim())
            ?: return failResponse("无法识别新片场链接")

        val articleResp = http.get("https://app.xinpianchang.com/article/$articleId", headers)
            ?: return failResponse("作品不存在或已删除")
        if (articleResp.statusCode != 200) return failResponse("作品不存在或已删除")
        val article = parseJsonObj(articleResp.body) ?: return failResponse("作品不存在或已删除")
        if (article.jLong("status") != 0L) return failResponse("作品不存在或已删除")
        val data = article.jObj("data") ?: return failResponse("作品不存在或已删除")

        val play = fetchPlayUrl(data) ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = data.jStr("vid") ?: data.jStr("media_id") ?: articleId
        md.title = data.jStr("title") ?: "无标题"
        md.cover = data.jStr("cover")

        val user = data.jObj("author")?.jObj("userinfo")
        md.author = user?.jStr("username") ?: "未知作者"
        md.authorUid = user?.jLong("id")?.toString()
        md.authorSecUid = user?.jStr("uid")

        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    private fun extractArticleId(input: String): String? {
        if (input.isBlank()) return null
        val path = runCatching { java.net.URI(input).path }.getOrNull().orEmpty()
        val m = articleIdPattern.matcher(path)
        if (m.find() && m.group(1)?.isNotEmpty() == true) return m.group(1)
        val digits = digitPattern.matcher(input)
        return if (digits.find()) digits.group() else null
    }

    private val digitPattern = Pattern.compile("\\d+")

    private fun fetchPlayUrl(data: JsonObject): String? {
        val vid = data.jStr("vid") ?: data.jStr("media_id") ?: return null
        val appKey = data.jObj("video")?.jStr("appKey") ?: DEFAULT_APP_KEY
        val resp = http.get(
            "https://mod-api.xinpianchang.com/mod/api/v2/media/$vid?appKey=$appKey",
            headers
        ) ?: return null
        if (resp.statusCode != 200) return null
        val mod = parseJsonObj(resp.body) ?: return null
        if (mod.jLong("status") != 0L) return null
        val progressive = mod.jObj("data")?.jObj("resource")?.jArr("progressive") ?: return null
        for (element in progressive) {
            if (element.isJsonObject) {
                val url = element.asJsonObject.jStr("url")
                if (url != null && url.startsWith("http")) return url
            }
        }
        return null
    }

    private companion object {
        const val TAG = "XinpianchangParser"
        const val DEFAULT_APP_KEY = "61a2f329348b3bf77"
    }
}
