package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement
import java.util.regex.Pattern

/**
 * 番茄小说 / 红果短剧 / 红果漫剧 推广页解析器：复用通用 HTML 元数据提取
 * （优先 window._ROUTER_DATA / _SSR_DATA JSON，其次 OpenGraph），取出
 * 推广视频（通常为试看/宣传片段）。按 Python 参考实现移植。
 */
internal class FanqieParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.FANQIE

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
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
            Log.w(TAG, "fanqie parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val html = resp.body
        val meta = parseHtml(html)

        val videoUrl = meta.videoUrl
            ?: return failResponse("无法提取作品数据")
        val md = MediaData()
        md.inputUrl = input
        md.type = "video"
        md.title = meta.title ?: "无标题"
        md.playUrl = videoUrl
        md.rawPlayUrl = videoUrl
        md.cover = meta.cover
        md.resolvedUrl = resp.finalUrl
        md.author = meta.author ?: "未知作者"
        md.addQuality(label = "默认", ratio = "default", url = videoUrl)
        return md.toServerJson()
    }

    data class HtmlMeta(
        val title: String?,
        val videoUrl: String?,
        val cover: String?,
        val author: String?
    )

    /** 通用 HTML 视频与元数据提取（对齐 HtmlVideoExtractor） */
    internal fun parseHtml(html: String): HtmlMeta {
        var title: String? = null
        var videoUrl: String? = null
        var cover: String? = null
        var author: String? = null

        parseRouterJson(html)?.let { data ->
            val titleRgx = listOf("title", "series_title", "video_title", "share_title", "name")
            val videoRgx = listOf("play_url", "video_url", "video_play_url", "url", "mp4_url")
            val coverRgx = listOf("cover_url", "poster_url", "cover", "image_url", "og:image")
            val authorRgx = listOf("author", "author_name", "user_name", "nick_name", "nickname")

            walk(data) { obj ->
                if (title == null) title = obj.firstStr(*titleRgx.toTypedArray())?.takeIf { it.isNotBlank() }
                if (videoUrl == null) {
                    val v = obj.firstStr(*videoRgx.toTypedArray())
                    if (v != null && (v.contains("http://") || v.contains("https://") ||
                            v.contains(".mp4") || v.contains("/tos-cn-"))) videoUrl = v
                }
                if (cover == null) {
                    val c = obj.firstStr(*coverRgx.toTypedArray())
                    if (c != null && (c.contains("http://") || c.contains("https://"))) cover = c
                }
                if (author == null) author = obj.firstStr(*authorRgx.toTypedArray())?.takeIf { it.isNotBlank() }
            }
        }

        val metaTitle = metaContent(html, "og:title")
        val metaCover = metaContent(html, "og:image")
        val metaVideo = metaVideoUrl(html)
        if (title == null) title = metaTitle
        if (cover == null) cover = metaCover
        if (videoUrl == null) videoUrl = metaVideo
        if (title == null) {
            val t = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            title = t?.groupValues?.get(1)?.trim()
        }
        if (videoUrl == null) {
            val v = Regex("<video[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)
            videoUrl = v?.groupValues?.get(1)
        }

        videoUrl = cleanUrl(videoUrl)
        cover = cleanUrl(cover)
        return HtmlMeta(title, videoUrl, cover, author)
    }

    private fun parseRouterJson(html: String): JsonElement? {
        val patterns = listOf(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\});\\s*</script>",
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\});",
            "window\\._SSR_DATA\\s*=\\s*(\\{.*?\\});\\s*</script>",
            "window\\._SSR_DATA\\s*=\\s*(\\{.*?\\});"
        )
        for (p in patterns) {
            val m = Pattern.compile(p, Pattern.DOTALL).matcher(html)
            if (m.find()) {
                parseJsonObj(m.group(1) ?: "")?.let { return it }
                parseJsonArr(m.group(1) ?: "")?.let { return it }
            }
        }
        // 备选：script 内包含 play_url/video_url/series_data 的 JSON 对象
        val scriptRgx = Pattern.compile(
            "<script[^>]*>\\s*(?:var\\s+)?(?:window\\.\\w+\\s*=\\s*)?(\\{.*?\\})\\s*;?\\s*</script>",
            Pattern.DOTALL
        )
        val m2 = scriptRgx.matcher(html)
        while (m2.find()) {
            val s = m2.group(1) ?: continue
            if (s.contains("play_url") || s.contains("video_url") || s.contains("series_data")) {
                parseJsonObj(s)?.let { return it }
            }
        }
        return null
    }

    private fun walk(node: JsonElement?, visit: (com.google.gson.JsonObject) -> Unit) {
        if (node == null || node.isJsonNull) return
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            visit(obj)
            for ((_, v) in obj.entrySet()) walk(v, visit)
        } else if (node.isJsonArray) {
            for (el in node.asJsonArray) walk(el, visit)
        }
    }

    private fun metaVideoUrl(html: String): String? {
        val keys = listOf("og:url", "twitter:player", "video:url")
        for (key in keys) {
            val v = metaContent(html, key) ?: continue
            if (v.contains("mime_type=video") || v.contains(".mp4") ||
                v.contains("/tos-cn-") || key == "og:url" && v.contains("play")
            ) return v
        }
        return null
    }

    private fun metaContent(html: String, property: String): String? {
        val m = Pattern.compile(
            "<meta[^>]*?property=[\"']" + Pattern.quote(property) + "[\"'][^>]*?content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (m.find()) return m.group(1)
        val m2 = Pattern.compile(
            "<meta[^>]*?content=[\"']([^\"']+)[\"'][^>]*?property=[\"']" + Pattern.quote(property) + "[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        return if (m2.find()) m2.group(1) else null
    }

    private fun cleanUrl(url: String?): String? {
        if (url == null) return null
        var cleaned = url.replace("\\u002F", "/").replace("\\/", "/")
            .replace("&amp;", "&")
        cleaned = cleaned.trim()
        return if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
    }

    private companion object {
        const val TAG = "FanqieParser"
    }
}