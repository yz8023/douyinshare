package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * 网易 LOFTER 社区解析器：提取图集与短视频。
 * 优先解析页面内嵌的 window.__initialize_data__ JSON，未命中时回退 HTML 抓取。
 * 按 Python 参考实现移植。
 */
internal class LofterParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.LOFTER

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://www.lofter.com/",
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
            Log.w(TAG, "lofter parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val html = resp.body
        val initData = extractInitializeData(html)

        val md = MediaData()
        md.inputUrl = input
        md.resolvedUrl = resp.finalUrl
        md.author = "未知作者"

        if (initData != null) {
            parseFromInitData(md, initData)
        } else {
            parseFromHtml(md, html)
        }
        if (md.title.isBlank()) md.title = "无标题"

        return when {
            md.playUrl != null -> md.toServerJson()
            md.gallery.isNotEmpty() -> md.toServerJson()
            else -> failResponse("无法提取媒体内容")
        }
    }

    private fun extractInitializeData(html: String): JsonObject? {
        val idx = html.indexOf("window.__initialize_data__")
        if (idx >= 0) {
            val start = html.indexOf('{', idx)
            val clean = html.substringAfter("window.__initialize_data__")
            parseJsonObj(balancedObj(clean))?.let { return it }
            if (start >= 0) {
                html.indexOf("</script>", start).let { end ->
                    if (end > start) {
                        val candidate = html.substring(start, end).trimEnd(';').trim()
                        parseJsonObj(candidate)?.let { return it }
                    }
                }
            }
        }
        val m = Pattern.compile("window\\.__initialize_data__\\s*=\\s*(\\{.*?\\})\\s*(?:;|</script>)", Pattern.DOTALL)
            .matcher(html)
        if (m.find()) {
            parseJsonObj(m.group(1) ?: "")?.let { return it }
        }
        return null
    }

    private fun balancedObj(text: String): String? {
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

    private fun parseFromInitData(md: MediaData, data: JsonObject) {
        val answer = data.jObj("answerDetailData")
        if (answer != null) {
            val blog = answer.jObj("blogInfo")
            md.author = blog?.jStr("blogNickName") ?: blog?.jStr("blogName") ?: "未知作者"
            md.title = answer.firstStr("barrage", "answer") ?: ""
            answer.jArr("images")?.forEach { el ->
                val img = el.asObjOrNull() ?: return@forEach
                img.firstStr("orign", "raw")?.let { md.addImage(it) }
            }
            return
        }

        val bundle = data.jObj("postData")?.jObj("data") ?: return
        val blog = bundle.jObj("blogInfo")
        md.author = blog?.jStr("blogNickName") ?: blog?.jStr("blogName") ?: "未知作者"
        val postView = bundle.jObj("postData")?.jObj("postView") ?: return

        md.title = postView.firstStr("title", "digest") ?: ""

        val videoView = postView.jObj("videoPostView")
        if (videoView != null) {
            val videoInfo = videoView.jObj("videoInfo")
            val videoUrl = videoInfo?.firstStr("originUrl", "flashurl")
            if (!videoUrl.isNullOrBlank()) {
                md.type = "video"
                md.playUrl = videoUrl
                md.rawPlayUrl = videoUrl
                md.cover = videoInfo?.firstStr("video_img_url", "video_first_img")
                md.addQuality(label = "默认", ratio = "default", url = videoUrl)
            }
        }

        val photoView = postView.jObj("photoPostView")
        if (photoView != null) {
            photoView.jArr("photoLinks")?.forEach { el ->
                val item = el.asObjOrNull() ?: return@forEach
                item.firstStr("raw", "orign")?.let { md.addImage(it) }
            }
            if (md.cover == null) {
                val first = photoView.jObj("firstImage") ?: postView.jObj("firstImage")
                md.cover = first?.firstStr("raw", "orign")
            }
        }

        val caption = videoView?.jStr("caption") ?: photoView?.jStr("caption")
        if (caption != null) md.title = stripTags(caption).ifEmpty { md.title }
    }

    private fun parseFromHtml(md: MediaData, html: String) {
        md.title = metaContent(html, "og:title") ?: ""
        md.cover = metaContent(html, "og:image")
        val video = Regex("<video[^>]*src=[\"']([^\"']+)[\"']").find(html)
        if (video != null) {
            val url = video.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
            md.type = "video"
            md.playUrl = url
            md.rawPlayUrl = url
            md.addQuality(label = "默认", ratio = "default", url = url)
        }
        if (md.gallery.isEmpty()) {
            val imgRgx = Regex("<img[^>]*(?:src|data-src)=[\"']([^\"']+)[\"']")
            imgRgx.findAll(html).forEach { m ->
                val src = m.groupValues[1]
                if (src.contains("lf127.net") && !src.contains("ava")) md.addImage(src)
            }
        }
    }

    private fun metaContent(html: String, property: String): String? {
        val m = Pattern.compile(
            "<meta[^>]*property=[\"']" + Pattern.quote(property) + "[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m.find()) return m.group(1)
        val m2 = Pattern.compile(
            "<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*property=[\"']" + Pattern.quote(property) + "[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m2.find()) return m2.group(1)
        return null
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").trim()

    private companion object {
        const val TAG = "LofterParser"
    }
}