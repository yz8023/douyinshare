package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.net.URI

/**
 * 最右解析器：提取分享链接 pid，POST detail_h5 接口取回帖子
 * 视频与作者信息。按 Python 参考实现移植。
 */
internal class ZuiyouParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.ZUIYOU

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://share.xiaochuankeji.cn/"
    )

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "zuiyou parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val pid = extractPid(input.trim())
            ?: return failResponse("无法识别最右链接")

        val resp = http.postJson(
            "https://share.xiaochuankeji.cn/planck/share/post/detail_h5",
            headers,
            """{"h_av":"5.2.13.011","pid":$pid}"""
        ) ?: return failResponse("视频不存在或已删除")
        if (resp.statusCode != 200) return failResponse("视频不存在或已删除")

        val payload = parseJsonObj(resp.body) ?: return failResponse("视频不存在或已删除")
        val post = payload.jObj("data")?.jObj("post") ?: return failResponse("视频不存在或已删除")

        val play = extractPlayUrl(post) ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = pid
        md.title = post.jStr("content") ?: "无标题"

        val member = post.jObj("member")
        md.author = member?.jStr("name") ?: "未知作者"
        md.authorUid = member?.jLong("id")?.toString()
        md.authorSecUid = member?.jStr("avatar_urls")?.let { null }

        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    private fun extractPid(input: String): String? {
        if (input.isBlank()) return null
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val query = uri.query ?: return null
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key == "pid" && value.toLongOrNull() != null) return value
        }
        return null
    }

    private fun extractPlayUrl(post: JsonObject): String? {
        val videos = post.jObj("videos") ?: return null
        val imgs = post.jArr("imgs") ?: return null
        if (imgs.size() == 0 || !imgs[0].isJsonObject) return null
        val key = imgs[0].asJsonObject.jRawStr("id") ?: return null
        return videos.jObj(key)?.jStr("url")
    }

    private companion object {
        const val TAG = "ZuiyouParser"
    }
}
