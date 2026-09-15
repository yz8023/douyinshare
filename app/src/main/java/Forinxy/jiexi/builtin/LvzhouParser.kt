package Forinxy.jiexi.builtin

import android.util.Log
import java.util.regex.Pattern

/**
 * 绿洲解析器：抓取 H5 页面 HTML，从 <video> 提取视频地址、
 * .media img 提取图集、background-image 提取封面。按 Python 参考实现移植。
 */
internal class LvzhouParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.LVZHOU

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://oasis.weibo.cn/"
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
            Log.w(TAG, "lvzhou parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val html = resp.body
        val images = extractImages(html)
        val play = extractVideo(html)
        val cover = extractCover(html) ?: images.firstOrNull()

        val md = MediaData()
        md.inputUrl = input
        md.title = extractTitle(html) ?: "无标题"
        md.cover = cover
        md.resolvedUrl = resp.finalUrl

        val author = extractAuthor(html)
        md.author = author.first.ifEmpty { "未知作者" }
        md.authorUid = author.second

        if (play != null) {
            md.type = "video"
            md.videoId = ""
            md.playUrl = play
            md.rawPlayUrl = play
            md.addQuality(label = "默认", ratio = "default", url = play)
        } else if (images.isNotEmpty()) {
            md.type = "image"
            images.forEach { md.addImage(it) }
        } else {
            return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    private val videoPattern = Pattern.compile("<video[^>]*>(?:[\\s\\S]*?<source[^>]*src=[\"']([^\"']+)[\"'])?[\\s\\S]*?</video>")
    private val videoSrcPattern = Pattern.compile("<video[^>]*src=[\"']([^\"']+)[\"']")
    private val mediaImgPattern = Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*>")
    private val bgImagePattern = Pattern.compile("background-image:url\\((.*?)\\)")
    private val statusTextPattern = Pattern.compile("<div[^>]*class=[\"'][^\"']*(?:status-text|status-title)[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>")
    private val nicknamePattern = Pattern.compile("class=[\"'][^\"']*user[^\"']*[\"'][^>]*>[\\s\\S]*?class=[\"'][^\"']*nickname[^\"']*[\"'][^>]*>([\\s\\S]*?)</[^>]+>")
    private val userAvatarPattern = Pattern.compile("class=[\"'][^\"']*user[^\"']*[\"'][^>]*>[\\s\\S]*?<img[^>]*src=[\"']([^\"']+)[\"']")

    private fun extractVideo(html: String): String? {
        videoSrcPattern.matcher(html).let { if (it.find()) return fixProtocol(it.group(1) ?: "") }
        val m = videoPattern.matcher(html)
        if (m.find()) {
            val group = m.group(1)
            if (!group.isNullOrBlank()) return fixProtocol(group)
        }
        return null
    }

    private fun extractImages(html: String): List<String> {
        val images = mutableListOf<String>()
        val m = mediaImgPattern.matcher(html)
        while (m.find()) {
            m.group(1)?.let { url ->
                if (url.isNotBlank() && !images.contains(url)) images.add(url)
            }
        }
        return images
    }

    private fun extractCover(html: String): String? {
        val m = bgImagePattern.matcher(html)
        if (m.find()) return m.group(1)?.trim()
        return null
    }

    private fun extractTitle(html: String): String? {
        val m = statusTextPattern.matcher(html)
        if (m.find()) {
            val text = stripTags(m.group(1) ?: "").trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun extractAuthor(html: String): Pair<String, String> {
        val nickMatcher = nicknamePattern.matcher(html)
        val nick = if (nickMatcher.find()) stripTags(nickMatcher.group(1) ?: "").trim() else ""
        val avatarMatcher = userAvatarPattern.matcher(html)
        if (avatarMatcher.find()) avatarMatcher.group(1)
        return nick to ""
    }

    private fun fixProtocol(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    private fun stripTags(text: String): String =
        text.replace(Pattern.compile("<[^>]+>").toRegex(), " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TAG = "LvzhouParser"
    }
}
