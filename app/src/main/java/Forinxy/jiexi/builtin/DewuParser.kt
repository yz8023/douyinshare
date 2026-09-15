package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject

/**
 * 得物 App 社区动态解析器：抓取分享页内嵌的 Next.js metaOGInfo 数据，
 * 提取无水印视频、图集、标题与作者。按 Python 参考实现移植。
 */
internal class DewuParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.DEWU

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://m.dewu.com/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
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
            Log.w(TAG, "dewu parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val scriptJson = findMetaOGInfoJson(resp.body)
            ?: return failResponse("未找到作品数据")
        val root = parseJsonObj(scriptJson) ?: return failResponse("作品数据解析失败")

        val pageProps = root.jObj("props")?.jObj("pageProps") ?: run {
            return failResponse("作品数据异常")
        }
        val list = pageProps.jObj("metaOGInfo")?.jArr("data")
            ?: return failResponse("作品数据为空")
        val target = list.firstOrNull()?.asObjOrNull()
            ?: return failResponse("作品数据为空")

        val content = target.jObj("content") ?: return failResponse("作品内容为空")
        val user = target.jObj("userInfo")

        val md = MediaData()
        md.inputUrl = input
        md.title = content.jStr("title") ?: "无标题"
        md.resolvedUrl = resp.finalUrl
        if (user != null) {
            md.author = user.jStr("userName")?.ifEmpty { null } ?: "未知作者"
        }

        content.jObj("cover")?.jStr("url")?.let { md.cover = it }

        val mediaList = content.jObj("media")?.jArr("list") ?: com.google.gson.JsonArray()
        val imageUrls = mutableListOf<String>()
        var videoUrl: String? = null
        val videoItems = mutableListOf<String>()
        mediaList.forEach { el ->
            val item = el.asObjOrNull() ?: return@forEach
            val type = item.jStr("mediaType")
            val url = item.jStr("url")
            if (url.isNullOrBlank()) return@forEach
            when (type) {
                "video" -> { if (videoUrl == null) videoUrl = url; videoItems.add(url) }
                "img" -> imageUrls.add(url)
            }
        }
        if (videoUrl == null) {
            videoUrl = content.jStr("videoShareUrl")
        }

        when {
            videoUrl != null -> {
                md.type = "video"
                md.playUrl = videoUrl
                md.rawPlayUrl = videoUrl
                videoItems.distinct().forEachIndexed { index, item ->
                    if (index == 0) return@forEachIndexed
                    md.addQuality(label = "画质${index + 1}", ratio = "default", url = item)
                }
                md.addQuality(
                    label = videoItems.distinct().let { if (it.size > 1) "画质1" else "默认" },
                    ratio = "default",
                    url = videoItems.distinct().first()
                )
            }
            imageUrls.isNotEmpty() -> {
                md.type = "image"
                imageUrls.distinct().forEach { md.addImage(it) }
            }
            md.cover != null -> {
                md.type = "image"
                md.addImage(md.cover)
            }
            else -> return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    /** 找到包含 metaOGInfo 的 script，提取其中最大的 JSON 对象 */
    private fun findMetaOGInfoJson(html: String): String? {
        val scriptPattern = Regex("<script[^>]*>[\\s\\S]*?metaOGInfo[\\s\\S]*?</script>")
        val script = scriptPattern.find(html)?.value ?: return null
        return extractBalancedObj(script)
    }

    private fun extractBalancedObj(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "DewuParser"
    }
}