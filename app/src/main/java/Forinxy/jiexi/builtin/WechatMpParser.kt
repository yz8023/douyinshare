package Forinxy.jiexi.builtin

import android.util.Log
import java.util.regex.Pattern

/**
 * 微信公众号 解析器：通过 SSR 页面结构解析 mp.weixin.qq.com 文章标题、
 * 公众号信息、封面、正文原画图集、音频与内嵌视频。按 Python 参考实现移植。
 */
internal class WechatMpParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.WECHAT_MP

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
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
            Log.w(TAG, "wechat mp parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取文章内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("文章不存在或已删除")
        val html = resp.body

        // 1. 标题
        var title = extractQuoted(html, listOf("var msg_title =", "msg_title :", "msg_title:"))
        if (title.isNullOrBlank()) title = metaContent(html, "og:title")
        if (title.isNullOrBlank()) title = htmlTagContent(html, "h1", "id", "activity-name")
        title = decodeHtmlEntities(title ?: "").trim()

        // 2. 公众号作者 / ID
        var authorName = extractQuoted(html, listOf("var nickname =", "nick_name :", "nick_name:"))
        if (authorName.isNullOrBlank()) authorName = metaNameContent(html, "author")
        if (authorName.isNullOrBlank()) authorName = htmlTagContent(html, "a", "id", "js_name")
        val authorId = extractQuoted(html, listOf("var user_name =", "user_name :", "user_name:"))

        // 3. 封面
        var cover = extractQuoted(html, listOf("var msg_cdn_url =", "cdn_url :", "cdn_url:"))
        if (cover.isNullOrBlank()) cover = metaContent(html, "og:image")

        // 4. 正文图集（把 /640? 或 /300? 升级为原始画质 /0?）
        val imageList = mutableListOf<String>()
        val contentBox = extractDiv(html, "id", "js_content")
        val desc = stripTags(contentBox ?: html).ifBlank { null }
        collectImages(contentBox ?: html, imageList)
        if (imageList.isEmpty() && !cover.isNullOrBlank()) imageList.add(cover)

        // 5. 音频
        val audioUrl = extractMpVoice(html)

        // 6. 视频分组
        val videoGroups = LinkedHashMap<String, MutableList<VideoItem>>()
        videoBlockRegex.findAll(html).forEach { m ->
            val block = m.value
            val vurl = decodeHtmlEntities(m.groupValues.getOrElse(1) { "" })
            val width = findInt(block, "width") ?: 0
            val height = findInt(block, "height") ?: 0
            val filesize = findInt(block, "filesize") ?: 0
            val quality = findInt(block, "video_quality_level") ?: 0
            if (vurl.isBlank()) return@forEach
            val key = videoGroupKey(vurl)
            videoGroups.getOrPut(key) { mutableListOf() }.add(VideoItem(vurl, width, height, filesize, quality))
        }

        val videoList = mutableListOf<String>()
        if (videoGroups.isNotEmpty()) {
            val isSingle = "video_page_info" in html &&
                "video_page_infos" !in html && "videoPageInfos" !in html
            for ((_, items) in videoGroups) {
                val best = items.maxWithOrNull(
                    compareBy<VideoItem> { it.quality }
                        .thenBy { it.width.toLong() * it.height }
                        .thenBy { it.filesize }
                ) ?: continue
                if (best.url !in videoList) videoList.add(best.url)
            }
            if (isSingle && videoList.size > 1) videoList.subList(1, videoList.size).clear()
        } else {
            directVideoRegex.findAll(html).forEach { m ->
                val clean = decodeHtmlEntities(m.value.trimEnd(',', '"', '\''))
                val key = videoGroupKey(clean)
                if (videoList.none { videoGroupKey(it) == key }) videoList.add(clean)
            }
        }

        val md = MediaData()
        md.inputUrl = input
        md.title = title.ifBlank { "微信公众号文章" }
        md.resolvedUrl = resp.finalUrl
        md.author = decodeHtmlEntities(authorName ?: "").trim().ifBlank { "未知作者" }
        if (!authorId.isNullOrBlank()) md.authorUid = authorId
        md.cover = cover?.let { decodeHtmlEntities(it) }

        when {
            videoList.isNotEmpty() -> {
                val primary = videoList.first()
                md.type = "video"
                md.playUrl = primary
                md.rawPlayUrl = primary
                md.addQuality(label = "默认", ratio = "default", url = primary)
            }
            audioUrl != null && audioUrl.isNotBlank() && imageList.isEmpty() -> {
                md.type = "music"
                md.playUrl = audioUrl
                md.addQuality(label = "默认", ratio = "default", url = audioUrl)
            }
            imageList.isNotEmpty() -> {
                md.type = "image"
                md.cover = md.cover ?: imageList.first()
                imageList.forEach { md.addImage(it) }
            }
            else -> return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    /** 匹配 `key : "value"` / `var key = "value"` 形式并返回 value */
    private fun extractQuoted(html: String, keys: List<String>): String? {
        for (key in keys) {
            val idx = html.indexOf(key)
            if (idx < 0) continue
            val after = html.substring(idx + key.length)
            val quoteIdx = after.indexOf('"')
            if (quoteIdx < 0) continue
            val start = quoteIdx + 1
            val end = after.indexOf('"', start)
            if (end < 0) continue
            return after.substring(start, end)
        }
        return null
    }

    private fun findInt(block: String, key: String): Int? {
        val m = Pattern.compile("""$key\s*:\s*['"]?(\d+)""").matcher(block)
        return if (m.find()) m.group(1)?.toIntOrNull() else null
    }

    private fun videoGroupKey(url: String): String {
        val m1 = Pattern.compile("mpvideo\\.qpic\\.cn/([a-zA-Z0-9_-]+)\\.f\\d+").matcher(url)
        if (m1.find()) return m1.group(1) ?: ""
        val m2 = Pattern.compile("mpvideo\\.qpic\\.cn/([a-zA-Z0-9_-]+)").matcher(url)
        if (m2.find()) return m2.group(1) ?: ""
        return url.substringBefore("?")
    }

    private fun collectImages(html: String, out: MutableList<String>) {
        imgRegex.findAll(html).forEach { m ->
            val src = m.groupValues.getOrElse(1) { "" }.ifEmpty { m.groupValues.getOrElse(2) { "" } }
            if (src.isBlank()) return@forEach
            if (src.startsWith("http") && src.contains("qpic.cn")) {
                val full = src.replace(Regex("/(?:640|300|0)\\?"), "/0?")
                if (full !in out) out.add(full)
            }
        }
    }

    private fun extractMpVoice(html: String): String? {
        val m = Pattern.compile("<mpvoice[^>]*voice_encode_fileid=\"([^\"]+)\"").matcher(html)
        if (m.find()) return "https://res.wx.qq.com/voice/getvoice?mediaid=${m.group(1)}"
        return null
    }

    private fun extractDiv(html: String, attr: String, value: String): String? {
        val m = Pattern.compile("<div[^>]*$attr=\"$value\"[^>]*>(.*?)</div>", Pattern.DOTALL).matcher(html)
        return if (m.find()) m.group(1) else null
    }

    private fun htmlTagContent(html: String, tag: String, attr: String, value: String): String? {
        val m = Pattern.compile("<$tag[^>]*$attr=\"$value\"[^>]*>(.*?)</$tag>", Pattern.DOTALL).matcher(html)
        return if (m.find()) stripTags(m.group(1) ?: "") else null
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
        return if (m2.find()) m2.group(1) else null
    }

    private fun metaNameContent(html: String, name: String): String? {
        val p = Pattern.compile(
            "<meta[^>]*name=[\"']$name[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (p.find()) return p.group(1)
        val p2 = Pattern.compile(
            "<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*name=[\"']$name[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        return if (p2.find()) p2.group(1) else null
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), " ").trim()

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("\\x26amp;", "&")
            .replace("\\x3c", "<")
            .replace("\\x3e", ">")

    private data class VideoItem(
        val url: String,
        val width: Int,
        val height: Int,
        val filesize: Int,
        val quality: Int
    )

    private companion object {
        const val TAG = "WechatMpParser"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val videoBlockRegex = Regex(
            "\\{[^{}]*?url\\s*:\\s*['\"`](https?://mpvideo\\.qpic\\.cn/[^'\"`]+)['\"`][^{}]*?\\}",
            RegexOption.DOT_MATCHES_ALL
        )
        val directVideoRegex = Regex("https?://mpvideo\\.qpic\\.cn/[^\\s'\"`]+")
        val imgRegex = Regex(
            "<img[^>]*(?:data-src|src)=\"([^\"]+)\"|" +
                "<img[^>]*(?:data-src|src)='([^']+)'"
        )
    }
}