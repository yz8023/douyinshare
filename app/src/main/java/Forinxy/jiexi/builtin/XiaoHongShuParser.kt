package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * 小红书笔记解析器：抓取笔记页面 HTML 中 window.__INITIAL_STATE__ 数据，
 * 提取作者、标题、视频直链或图集（含实况 Live 视频），输出 data.php 兼容 JSON。
 * 优先使用 PC UA 请求以获得无水印高品质流与原图，失败回退移动端 UA。
 */
internal class XiaoHongShuParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.XIAOHONGSHU

    private var terminalError: String? = null

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return runCatching {
            val resolved = resolveTarget(input.trim())
            if (resolved.error != null) return failResponse(resolved.error!!)
            terminalError = null
            val note = fetchNote(resolved)
                ?: return failResponse(terminalError ?: "无法获取小红书笔记内容，请稍后重试")
            buildResponse(input, resolved, note)
        }.getOrElse { e ->
            Log.w(TAG, "xiaohongshu parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    // ========== 链接与笔记 ID 解析 ==========

    private data class Resolved(
        val noteId: String = "",
        val xsecToken: String? = null,
        val fetchUrl: String = "",
        val error: String? = null
    )

    private val urlInTextPattern = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
    private val noteIdPattern = Regex("/(?:explore|discovery/item)/([0-9a-zA-Z_-]+)")
    private val profilePattern = Regex("/user/profile/")

    private fun resolveTarget(input: String): Resolved {
        val url = urlInTextPattern.find(input)?.value
            ?: return Resolved(error = "未能从输入中识别到小红书链接，请提供笔记分享链接")
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return Resolved(error = "链接格式无效，请检查后重试")

        val host = uri.host?.lowercase().orEmpty()
        if (host.endsWith("xhslink.com") || host.endsWith("xhslink.cn")) {
            // 短链：跟随重定向(finalUrl)还原真实笔记地址
            val result = http.get(url, redirectHeaders)
                ?: return Resolved(error = "短链接跳转失败，请检查链接是否有效")
            return parseNoteUrl(result.finalUrl)
        }
        if (host.endsWith("xiaohongshu.com")) {
            return parseNoteUrl(url)
        }
        return Resolved(error = "该链接不是小红书笔记链接")
    }

    private fun parseNoteUrl(url: String): Resolved {
        if (profilePattern.containsMatchIn(url)) {
            return Resolved(error = "暂不支持解析小红书用户主页，请提供笔记分享链接")
        }
        val matcher = noteIdPattern.find(url)
        val noteId = matcher?.groupValues?.get(1).orEmpty()
        if (noteId.isBlank()) {
            return Resolved(error = "未能从链接中解析出小红书笔记 ID，请确认链接有效")
        }
        val token = queryParam(url, "xsec_token")
        return Resolved(
            noteId = noteId,
            xsecToken = token,
            fetchUrl = buildNoteUrl(noteId, token)
        )
    }

    private fun buildNoteUrl(noteId: String, xsecToken: String?): String {
        val base = "https://www.xiaohongshu.com/explore/$noteId"
        return if (xsecToken.isNullOrBlank()) base else "$base?xsec_token=$xsecToken"
    }

    private fun queryParam(url: String, name: String): String? {
        if (url.isBlank()) return null
        val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        val prefix = "$name="
        for (pair in query.split("&")) {
            if (pair.startsWith(prefix)) {
                val value = pair.substring(prefix.length)
                return if (value.isEmpty()) null else value
            }
        }
        return null
    }

    // ========== 页面抓取 ==========

    private companion object {
        val redirectHeaders = mapOf(
            "User-Agent" to PlatformHttp.PC_UA,
            "Referer" to "https://www.xiaohongshu.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        const val TAG = "XiaoHongShuParser"
        const val ERR_DELETED = "笔记已被删除或不存在"
        const val ERR_RISK = "访问被小红书风控拦截，请配置小红书登录 Cookie 后重试"
    }

    private fun fetchNote(resolved: Resolved): JsonObject? {
        for ((index, ua) in listOf(PlatformHttp.PC_UA, PlatformHttp.MOBILE_UA).withIndex()) {
            val isLast = index == 1
            val headers = mapOf(
                "User-Agent" to ua,
                "Referer" to "https://www.xiaohongshu.com/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val result = http.get(resolved.fetchUrl, headers) ?: continue
            if (result.statusCode == 404) {
                if (isLast) terminalError = ERR_DELETED
                continue
            }
            if (result.statusCode != 200) continue
            val finalUrl = result.finalUrl
            if (finalUrl.contains("xiaohongshu.com/404") || finalUrl.contains("undertake_note_error")) {
                if (isLast) terminalError = ERR_DELETED
                continue
            }
            if (finalUrl.contains("xiaohongshu.com/login") || finalUrl.contains("xiaohongshu.com/visitor")) {
                if (isLast && terminalError == null) terminalError = ERR_RISK
                continue
            }
            val note = extractNoteFromHtml(result.body)
            if (note != null) return note
            if (terminalError != null) return null
        }
        return null
    }

    private val initStatePattern =
        Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*?\})</script>""", RegexOption.DOT_MATCHES_ALL)
    private val initStateFallbackPattern =
        Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*\})""", RegexOption.DOT_MATCHES_ALL)
    private val undefinedPattern = Regex(""":\s*undefined\b""")

    private fun extractNoteFromHtml(html: String): JsonObject? {
        if (html.isBlank()) return null
        var match = initStatePattern.find(html)
        if (match == null) match = initStateFallbackPattern.find(html)
        if (match == null) return null
        var jsonStr = match.groups[1]?.value.orEmpty()
        jsonStr = undefinedPattern.replace(jsonStr, ":null")
        val fullData = runCatching { JsonParser.parseString(jsonStr) }.getOrNull()?.asObject()
            ?: return null

        // 1. PC 端结构: note.noteDetailMap[firstNoteId].note
        val noteRoot = fullData.asObject("note")
        val firstNoteId = noteRoot?.asString("firstNoteId")
        if (firstNoteId != null) {
            val noteItem = noteRoot.asObject("noteDetailMap")?.asObject(firstNoteId)
            if (noteItem != null) {
                val note = noteItem.asObject("note")
                if (note != null) {
                    if (note.entrySet().isNotEmpty()) return note
                    terminalError = ERR_DELETED
                    return null
                }
            }
        }

        // 2. 移动端 H5 结构: noteData.data.noteData
        val h5Note = fullData.asObject("noteData")?.asObject("data")?.asObject("noteData")
        if (h5Note != null && h5Note.entrySet().isNotEmpty()) return h5Note

        // 3. 兜底回退结构
        val fb1 = fullData.asObject("noteData")?.asObject("note")
        if (fb1 != null && fb1.entrySet().isNotEmpty()) return fb1
        val fb2 = noteRoot?.asObject("note")
        if (fb2 != null && fb2.entrySet().isNotEmpty()) return fb2
        return null
    }

    // ========== 输出组装 ==========

    private fun buildResponse(input: String, resolved: Resolved, note: JsonObject): String {
        val media = MediaData()
        media.inputUrl = input
        media.videoId = resolved.noteId
        media.resolvedUrl = resolved.fetchUrl

        val user = note.asObject("user")
        media.author = user?.asString("nickname")
            ?: user?.asString("nickName")
            ?: "未知作者"
        media.authorUid = user?.asString("userId")
            ?: user?.asString("author_id")
            ?: user?.asString("id")

        media.title = note.asString("title") ?: note.asString("desc") ?: "无标题"

        note.asLong("time")?.let { t ->
            if (t > 0L) media.timestamp = if (t > 10_000_000_000L) t / 1000L else t
        }

        val images = extractImages(note)
        val cover = cleanImageUrl(note.get("cover"))
        media.cover = cover ?: images.firstOrNull()?.imageUrl

        val noteType = note.asString("type")
        val hasVideoNode = note.hasNonNull("video")
        val videoUrl = extractVideoUrl(note)
        val isVideo = noteType == "video" || hasVideoNode || videoUrl != null

        media.type = if (isVideo) "video" else "image"
        if (isVideo) {
            media.playUrl = videoUrl
            if (videoUrl != null) {
                media.addQuality(label = "原画", ratio = "default", url = videoUrl, isOriginal = true)
            }
        } else {
            images.forEach { media.addGalleryItem(it) }
        }
        return media.toServerJson()
    }

    // ========== 视频地址提取 ==========

    private fun extractVideoUrl(note: JsonObject): String? {
        val video = note.asObject("video") ?: return null

        // 1. 优先提取 consumer.originVideoKey（无水印原画视频 Key）
        val consumer = video.asObject("consumer")
        val originKey = consumer?.asString("originVideoKey")
            ?: consumer?.asString("origin_video_key")
            ?: video.asString("originVideoKey")
            ?: video.asString("origin_video_key")
        if (!originKey.isNullOrBlank()) return buildVideoCdnUrl(originKey)

        // 2. mediaV2 中的 screencast 原画/高清无水印流
        video.asString("mediaV2")?.let { raw ->
            val mv2 = runCatching { JsonParser.parseString(raw) }.getOrNull()?.asObject()
            if (mv2 != null) {
                val mv2Video = mv2.asObject("video")
                val opaque = mv2Video?.asObject("opaque1")
                opaque?.asString("hd_screencast_stream")?.let { return ensureHttps(it) }
                opaque?.asString("default_screencast_stream")?.let { return ensureHttps(it) }
                val mv2Consumer = (mv2Video ?: mv2).asObject("consumer")
                val mv2Key = mv2Consumer?.asString("originVideoKey")
                    ?: mv2Consumer?.asString("origin_video_key")
                if (!mv2Key.isNullOrBlank()) return buildVideoCdnUrl(mv2Key)
            }
        }

        // 3. codec 流：优先无水印(258/301 或 X264_MP4)，逐列表倒序取最高画质
        val stream = video.asObject("media")?.asObject("stream") ?: video.asObject("stream")
        val unwatermarked = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        for (codec in listOf("h264", "h265", "av1")) {
            stream?.asArray(codec)?.let { items ->
                for (index in items.size() - 1 downTo 0) {
                    val item = items[index].asObject() ?: continue
                    val master = item.asString("masterUrl")
                        ?: item.asArray("backupUrls")?.lastStringOrNull()
                    if (master.isNullOrBlank()) continue
                    val clean = ensureHttps(master) ?: continue
                    val streamType = item.asIntOrNull("streamType")
                    val streamDesc = item.asString("streamDesc").orEmpty()
                    if (streamType == 258 || streamType == 301 || "X264_MP4" in streamDesc) {
                        unwatermarked.add(clean)
                    } else if (streamType != 259 && streamType != 309) {
                        unwatermarked.add(clean)
                    } else {
                        fallback.add(clean)
                    }
                }
            }
        }
        return unwatermarked.firstOrNull() ?: fallback.firstOrNull()
    }

    private fun buildVideoCdnUrl(key: String): String {
        return if (key.startsWith("http://") || key.startsWith("https://")) {
            ensureHttps(key) ?: key
        } else {
            "https://sns-video-bd.xhscdn.com/${key.trimStart('/')}"
        }
    }

    private fun ensureHttps(url: String): String? {
        if (url.isBlank()) return null
        var u = url.replace("\\u002F", "/")
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        return u.ifBlank { null }
    }

    // ========== 图片与封面提取 ==========

    private val hexDirPattern = Regex("/[0-9a-f]{32}/(.+?)(?:!|$)")
    private val cleanSuffixPattern = Regex("![^?]*")

    private fun cleanImageUrl(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonObject) return cleanImageObject(element.asJsonObject)
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val url = element.asString
            if (url.isBlank()) return null
            return finalizeImageUrl(url)
        }
        return null
    }

    private fun cleanImageObject(img: JsonObject): String? {
        val fileId = img.asString("fileId")
        if (fileId != null) {
            return "https://sns-img-qc.xhscdn.com/$fileId?imageView2/2/w/1920/format/jpg"
        }
        var url = img.asString("urlDefault") ?: img.asString("url")
        if (url == null) {
            val infoList = img.asArray("infoList")
            if (infoList != null && infoList.size() > 0) {
                url = infoList[infoList.size() - 1].asObject()?.asString("url")
                    ?: infoList[0].asObject()?.asString("url")
            }
        }
        if (url == null) return null
        return finalizeImageUrl(url)
    }

    private fun finalizeImageUrl(url: String): String? {
        val u = url.replace("\\u002F", "/")
        val match = hexDirPattern.find(u)
        if (match != null) {
            val fileId = match.groupValues[1]
            return "https://sns-img-qc.xhscdn.com/$fileId?imageView2/2/w/1920/format/jpg"
        }
        val clean = u.replace(cleanSuffixPattern, "")
        return clean.ifBlank { null }
    }

    private fun extractImages(note: JsonObject): List<GalleryItem> {
        val imageList = note.asArray("imageList") ?: return emptyList()
        val result = mutableListOf<GalleryItem>()
        for (element in imageList) {
            val img = element.asObject() ?: continue
            val imageUrl = cleanImageObject(img) ?: continue
            var live: String? = null
            if (img.asBoolean("livePhoto")) {
                val h264 = img.asObject("stream")?.asArray("h264")
                val master = h264?.get(0)?.asObject()?.asString("masterUrl")
                if (!master.isNullOrBlank()) live = ensureHttps(master)
            }
            result.add(GalleryItem(imageUrl = imageUrl, livePhotoRawUrl = live))
        }
        return result
    }

    // ========== JSON 扩展 ==========

    private fun JsonElement.asObject(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.hasNonNull(key: String): Boolean =
        has(key) && !get(key).isJsonNull

    private fun JsonObject.asString(key: String): String? {
        if (!hasNonNull(key)) return null
        val value = get(key)
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.asLong(key: String): Long? =
        if (hasNonNull(key) && get(key).isJsonPrimitive) {
            runCatching { get(key).asLong }.getOrNull()
        } else {
            null
        }

    private fun JsonObject.asIntOrNull(key: String): Int? =
        if (hasNonNull(key) && get(key).isJsonPrimitive) {
            runCatching { get(key).asInt }.getOrNull()
        } else {
            null
        }

    private fun JsonObject.asBoolean(key: String): Boolean =
        if (hasNonNull(key) && get(key).isJsonPrimitive) {
            runCatching { get(key).asBoolean }.getOrNull() ?: false
        } else {
            false
        }

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (hasNonNull(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (hasNonNull(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private fun JsonArray.lastStringOrNull(): String? {
        if (size() == 0) return null
        val last = get(size() - 1)
        return if (last.isJsonPrimitive && last.asJsonPrimitive.isString) last.asString else null
    }
}