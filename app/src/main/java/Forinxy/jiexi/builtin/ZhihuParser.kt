package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * 知乎解析器：通过公开 API（answers/videos/pins/articles）获取内容，
 * 优先取码率最高的播放地址，支持图集。按 Python 参考实现移植。
 */
internal class ZhihuParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.ZHIHU

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://www.zhihu.com/"
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
            Log.w(TAG, "zhihu parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val content = extractContentId(input.trim())
            ?: return failResponse("无法识别知乎链接")
        val apiPath = apiPathFor(content.first) ?: return failResponse("无法识别知乎链接")

        val resp = http.get("https://api.zhihu.com/$apiPath/${content.second}", headers)
            ?: return failResponse("内容不存在或已删除")
        if (resp.statusCode != 200) return failResponse("内容不存在或已删除")
        val data = parseJsonObj(resp.body) ?: return failResponse("内容不存在或已删除")

        val md = MediaData()
        md.inputUrl = input
        md.videoId = content.second

        val question = data.jObj("question")
        md.title = question?.jStr("title") ?: data.jStr("title")
            ?: data.jStr("excerpt_title") ?: "无标题"

        val author = data.jObj("author")
        md.author = author?.jStr("name") ?: "未知作者"
        md.authorUid = author?.jLong("id")?.toString()
        md.authorSecUid = author?.jStr("url_token")

        md.cover = pinVideo(data).jStr("thumbnail")?.let { firstUrl(it) }
            ?: data.jStr("thumbnail")?.let { firstUrl(it) }
            ?: data.jStr("image_url")?.let { firstUrl(it) }
            ?: imagesFromContent(data).firstOrNull()

        val play = playlistUrl(data.jObj("playlist")) ?: playlistUrl(pinVideo(data).jObj("playlist"))

        if (play != null) {
            md.type = "video"
            md.playUrl = play
            md.rawPlayUrl = play
            md.addQuality(label = "默认", ratio = "default", url = play)
        } else {
            val images = imagesFromContent(data)
            if (images.isNotEmpty()) {
                md.type = "image"
                images.forEach { md.addImage(it) }
            } else {
                return failResponse("无法提取媒体内容")
            }
        }
        return md.toServerJson()
    }

    private fun extractContentId(input: String): Pair<String, String>? {
        if (input.isBlank()) return null
        val path = runCatching { java.net.URI(input).path }.getOrNull().orEmpty()
        val patterns = listOf(
            "answer" to Pattern.compile("question/\\d+/answer/(\\d+)"),
            "answer" to Pattern.compile("/answer/(\\d+)"),
            "zvideo" to Pattern.compile("/zvideo/(\\d+)"),
            "pin" to Pattern.compile("/pin/(\\d+)"),
            "article" to Pattern.compile("(?:zhuanlan\\.zhihu\\.com/p/|/article/)(\\d+)")
        )
        for ((type, pattern) in patterns) {
            val source = if (type == "article") input else path
            val m = pattern.matcher(source)
            if (m.find()) {
                val id = m.group(1) ?: continue
                return type to id
            }
        }
        return null
    }

    private fun apiPathFor(type: String): String? = when (type) {
        "answer" -> "answers"
        "zvideo" -> "videos"
        "pin" -> "pins"
        "article" -> "articles"
        else -> null
    }

    private fun pinVideo(data: JsonObject): JsonObject {
        val content = data.jArr("content") ?: return JsonObject()
        for (element in content) {
            if (element.isJsonObject) {
                val item = element.asJsonObject
                if (item.jStr("type") == "video") return item
            }
        }
        return JsonObject()
    }

    /** 播放列表：字典（各画质）或数组，按 bitrate/width*height 降序取第一个 */
    private fun playlistUrl(playlist: JsonObject?): String? {
        if (playlist == null) return null
        val candidates = mutableListOf<JsonObject>()
        if (playlist.isJsonObject) {
            for (element in playlist.entrySet()) {
                if (element.value.isJsonObject) candidates.add(element.value.asJsonObject)
            }
        }
        candidates.sortWith(compareByDescending<JsonObject> { it.jLong("bitrate") ?: 0L }
            .thenByDescending { (it.jLong("width") ?: 0L) * (it.jLong("height") ?: 0L) })
        for (item in candidates) {
            item.jStr("play_url")?.let { return it }
            item.jStr("url")?.let { return it }
        }
        return null
    }

    private fun firstUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.startsWith("http")) return value
        return null
    }

    private fun imagesFromContent(data: JsonObject): List<String> {
        val images = mutableListOf<String>()
        val content = data.jArr("content")
        if (content != null) {
            for (element in content) {
                if (element.isJsonObject) {
                    val item = element.asJsonObject
                    if (item.jStr("type") == "image") {
                        val url = firstUrl(item.jStr("url")) ?: firstUrl(item.jStr("image"))
                        if (url != null && !images.contains(url)) images.add(url)
                    }
                }
            }
        }
        val html = data.jStr("content") ?: data.jStr("content_html").orEmpty()
        if (html.isNotEmpty()) {
            val m = imgTagPattern.matcher(html)
            while (m.find()) {
                val url = m.group(1) ?: m.group(2) ?: m.group(3) ?: continue
                if (url.startsWith("http") && !images.contains(url)) images.add(url)
            }
        }
        return images
    }

    private val imgTagPattern =
        Pattern.compile("<img[^>]*?(?:data-original=[\"']([^\"']+)[\"']|data-actualsrc=[\"']([^\"']+)[\"']|src=[\"']([^\"']+)[\"'])")

    private companion object {
        const val TAG = "ZhihuParser"
    }
}
