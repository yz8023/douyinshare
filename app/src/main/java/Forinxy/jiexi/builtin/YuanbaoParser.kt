package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 腾讯元宝公开对话 / AI 生图 / AI 视频分享解析器：
 * 优先解析页面 #__NEXT_DATA__ 的 pageProps（fullChatShareData / shareDetailData），
 * 独立分享页回退调用 general_share_detail 接口。按 Python 参考实现移植。
 */
internal class YuanbaoParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.YUANBAO

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
            Log.w(TAG, "yuanbao parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        val resp = http.get(url, headers) ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")
        val resolvedUrl = resp.finalUrl.ifBlank { url }
        val html = resp.body

        var title: String? = null
        var coverUrl: String? = null
        var videoList = mutableListOf<String>()
        var imageList = mutableListOf<String>()
        var authorName: String? = null

        val next = loadNextData(html)
        val pageProps = next?.jObj("props")?.jObj("pageProps")
        if (pageProps != null) {
            val fullShare = pageProps.jObj("fullChatShareData")
            if (fullShare != null) {
                val (t, c, v, i, a) = parseChatShare(fullShare)
                if (title == null) title = t
                if (coverUrl == null) coverUrl = c
                videoList.addAll(v)
                imageList.addAll(i)
                if (authorName == null) authorName = a
            }
            val detail = pageProps.jObj("shareDetailData")
            if (detail != null && videoList.isEmpty() && imageList.isEmpty()) {
                val (t, c, v, i, a) = parseShareDetail(detail)
                if (title == null) title = t
                if (coverUrl == null) coverUrl = c
                videoList.addAll(v)
                imageList.addAll(i)
                if (authorName == null) authorName = a
            } else if (fullShare == null && detail == null) {
                val tmpVideos = mutableListOf<String>()
                val tmpImages = mutableListOf<String>()
                val tmpCovers = mutableListOf<String>()
                collectDirectMedia(pageProps, tmpVideos, tmpImages, tmpCovers)
                videoList.addAll(tmpVideos)
                imageList.addAll(tmpImages)
                if (coverUrl == null) coverUrl = tmpCovers.firstOrNull()
            }
        }

        if (title == null) title = pageTitle(html)
        videoList = unique(videoList)
        imageList = unique(imageList)

        if (videoList.isEmpty() && imageList.isEmpty()) {
            val detail = fetchStandaloneShareDetail(resolvedUrl, pageProps)
            if (detail != null) {
                val (t, c, v, i, a) = parseShareDetail(detail)
                if (title == null) title = t
                if (coverUrl == null) coverUrl = c
                videoList.addAll(v)
                imageList.addAll(i)
                if (authorName == null) authorName = a
            }
        }

        videoList = unique(videoList)
        imageList = unique(imageList)
        if (videoList.isEmpty() && imageList.isEmpty()) return failResponse("无法提取作品信息")

        val md = MediaData()
        md.inputUrl = input
        md.resolvedUrl = resolvedUrl
        md.author = authorName ?: "未知作者"
        md.title = title ?: "腾讯元宝 分享"
        if (videoList.isNotEmpty()) {
            val primary = videoList.first()
            md.type = "video"
            md.playUrl = primary
            md.rawPlayUrl = primary
            md.cover = coverUrl
            md.addQuality(label = "默认", ratio = "default", url = primary)
        } else {
            md.type = "image"
            md.cover = imageList.first()
            imageList.forEach { md.addImage(it) }
        }
        return md.toServerJson()
    }

    private data class Parsed(
        val title: String?,
        val coverUrl: String?,
        val videos: List<String>,
        val images: List<String>,
        val author: String?
    )

    private fun loadNextData(html: String): JsonObject? {
        val m = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html) ?: return null
        val raw = m.groupValues[1]
        return runCatching { parseJsonObj(raw.trim())!! }.getOrNull()
    }

    private fun parseChatShare(payload: JsonObject): Parsed {
        val chat = payload.jObj("chat")
        val card = chat?.jObj("shareCardInfo")
        val conversations = chat?.jArr("convs") ?: com.google.gson.JsonArray()

        var title = card?.jStr("title")?.let { cleanTitle(it) }
        var author = extractAuthor(chat)
        var coverUrl: String? = null
        val videos = mutableListOf<String>()
        val images = mutableListOf<String>()

        for (conv in conversations) {
            val convObj = conv.asObjOrNull() ?: continue
            if (convObj.jStr("speaker") != "ai") continue
            for (speech in convObj.jArr("speechesV2") ?: com.google.gson.JsonArray()) {
                val s = speech.asObjOrNull() ?: continue
                val extra = s.jObj("extra") ?: continue
                for (replace in extra.jArr("replaces") ?: com.google.gson.JsonArray()) {
                    val r = replace.asObjOrNull() ?: continue
                    for (media in r.jArr("multimedias") ?: com.google.gson.JsonArray()) {
                        val m = media.asObjOrNull() ?: continue
                        collectMultimediaFull(m, videos, images)
                    }
                }
            }
        }

        if (title == null) title = findPrompt(conversations)

        val mediaType = (card?.jStr("imageFrom") ?: "").lowercase()
        val cardImage = card?.firstStr("coverUrl", "imageUrl")?.let { validUrl(it) }
        if (cardImage != null) {
            if ("video" in mediaType) {
                if (coverUrl == null) coverUrl = cardImage
            } else if (images.isEmpty()) {
                images.add(cardImage)
                if (coverUrl == null) coverUrl = cardImage
            }
        }
        return Parsed(title, coverUrl, videos, images, author)
    }

    /** coverUrl 传播辅助：media 内嵌封面在视频类型下直接使用 */
    private fun collectMultimediaFull(
        media: JsonObject,
        videos: MutableList<String>,
        images: MutableList<String>
    ) {
        val mediaType = listOf("type", "mediaType", "mimeType", "display")
            .mapNotNull { media.jStr(it)?.lowercase() }.joinToString(" ")
        val isVideo = "video" in mediaType
        val isImage = "image" in mediaType || !isVideo

        val primary = firstUrl(
            media.jRawStr("downloadUrl"),
            media.jStr("url"),
            media.jStr("resourceUrl"),
            media.jStr("playUrl"),
            media.jStr("videoUrl")
        )
        if (isVideo && primary != null) videos.add(primary)
        else if (isImage && primary != null) images.add(primary)
    }

    private fun parseShareDetail(detail: JsonObject): Parsed {
        var title = detail.jStr("title") ?: detail.jStr("prompt")
        title = title?.let { cleanTitle(it) }

        val videos = mutableListOf<String>()
        val images = mutableListOf<String>()
        val covers = mutableListOf<String>()

        val extra = detail.jRawStr("extra")
        if (extra != null && extra.isNotBlank()) {
            parseJsonObj(extra)?.let { collectDirectMedia(it, videos, images, covers) }
        }
        collectDirectMedia(detail, videos, images, covers)

        var authorName: String? = null
        val userInfo = detail.jObj("userInfo") ?: detail.jObj("author")
        if (userInfo != null) {
            authorName = userInfo.firstStr("name", "nickname", "nick")
        }
        return Parsed(title, covers.firstOrNull(), videos, images, authorName)
    }

    private fun collectDirectMedia(
        data: JsonElement,
        videos: MutableList<String>,
        images: MutableList<String>,
        covers: MutableList<String>
    ) {
        if (data.isJsonObject) {
            for ((key, value) in data.asJsonObject.entrySet()) {
                val keyLower = key.lowercase()
                if (value.isJsonPrimitive) {
                    val s = value.asStrOrNull() ?: continue
                    val url = validUrl(s) ?: continue
                    when {
                        keyLower.contains("avatar") || keyLower.contains("icon") ||
                            keyLower.contains("logo") || keyLower.contains("target") -> Unit
                        "video" in keyLower || "playurl" in keyLower -> videos.add(url)
                        "cover" in keyLower || "poster" in keyLower || "thumbnail" in keyLower ->
                            if (covers.isEmpty()) covers.add(url)
                        keyLower in setOf("downloadurl", "imageurl", "image_url") -> images.add(url)
                    }
                } else if (value.isJsonObject || value.isJsonArray) {
                    collectDirectMedia(value, videos, images, covers)
                }
            }
        } else if (data.isJsonArray) {
            for (el in data.asJsonArray) collectDirectMedia(el, videos, images, covers)
        }
    }

    private fun fetchStandaloneShareDetail(resolvedUrl: String, pageProps: JsonObject?): JsonObject? {
        val path = pathOf(resolvedUrl)
        if ("/bot/app/share/" !in path) return null

        val shareId = pageProps?.jStr("shareId") ?: path.trimEnd('/').substringAfterLast('/')
        val userId = UrlKit.query(resolvedUrl)["userId"] ?: ""
        if (shareId.isBlank()) return null

        val apiHeaders = mapOf(
            "User-Agent" to headers["User-Agent"].orEmpty(),
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to "https://yb.tencent.com",
            "Referer" to resolvedUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "x-source" to "web",
            "x-verify-flag" to "1"
        )
        val payload =
            """{"shareType":"videoWeb","shareId":"$shareId","userId":"$userId"}"""
        val resp = http.postJson(
            "https://yb.tencent.com/api/share/general_share_detail",
            apiHeaders,
            payload
        ) ?: return null
        if (resp.statusCode != 200) return null
        val root = parseJsonObj(resp.body) ?: return null
        return root.takeIf { it.jStr("error") == null }
    }

    private fun extractAuthor(chat: JsonObject?): String? {
        if (chat == null) return null
        val author = authorFromDict(chat.jObj("userInfo"))
        val conversations = chat.jArr("convs") ?: return author?.nickname
        for (conv in conversations) {
            val convObj = conv.asObjOrNull() ?: continue
            if (convObj.jStr("speaker") != "human") continue
            val role = convObj.jObj("role")
            val candidate = role?.let { authorFromDict(it) }
            if (candidate != null && candidate.nickname?.isNotBlank() == true) return candidate.nickname
        }
        return author?.nickname
    }

    private data class Author(val nickname: String?, val authorId: String?, val avatar: String?)

    private fun authorFromDict(data: JsonObject?): Author? {
        if (data == null || data.entrySet().isEmpty()) return null
        val nickname = data.firstStr("name", "nickname", "nick")
        val authorId = data.firstStr("userId", "id", "uid")
        val avatar = data.firstStr("imageUrl", "avatar", "avatarUrl")
        if (nickname.isNullOrBlank() && authorId.isNullOrBlank() && avatar.isNullOrBlank()) return null
        return Author(nickname, authorId, avatar)
    }

    private fun findPrompt(conversations: JsonElement?): String? {
        if (conversations == null || !conversations.isJsonArray) return "腾讯元宝分享"
        for (conv in conversations.asJsonArray) {
            val convObj = conv.asObjOrNull() ?: continue
            if (convObj.jStr("speaker") != "human") continue
            val prompt = convObj.jStr("displayPrompt") ?: convObj.jStr("speech")
            if (!prompt.isNullOrBlank()) return cleanTitle(prompt)
        }
        return "腾讯元宝分享"
    }

    private fun cleanTitle(value: String): String {
        return value.trim().replace(Regex("^\\[(?:图片|视频)]\\s*"), "")
    }

    private fun validUrl(value: String?): String? {
        if (value == null) return null
        val v = value.trim()
        if (!v.startsWith("http://") && !v.startsWith("https://")) return null
        return htmlUnescape(v).replace("\\u0026", "&")
    }

    private fun firstUrl(vararg values: String?): String? {
        for (value in values) {
            val url = validUrl(value) ?: continue
            return url
        }
        return null
    }

    private fun unique(values: MutableList<String>): MutableList<String> {
        return LinkedHashSet(values.filter { it.isNotBlank() }).toMutableList()
    }

    private fun pageTitle(html: String): String? {
        val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun htmlUnescape(value: String): String {
        return value.replace("&quot;", "\"").replace("&#34;", "\"")
            .replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&amp;", "&")
    }

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').let { "/$it" }
    }

    private companion object {
        const val TAG = "YuanbaoParser"
    }
}