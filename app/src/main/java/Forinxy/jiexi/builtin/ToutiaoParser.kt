package Forinxy.jiexi.builtin

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * 今日头条视频解析器：移动端 SSR（RENDER_DATA 或页面内 SSR 脚本）解析作品详情，
 * 配合 ByteDance VOD（playAuthTokenV2）调度取回可直接播放的 m3u8 / mp4 地址，
 * 输出 data.php 兼容 JSON。按 Python 参考实现移植。
 */
internal class ToutiaoParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.TOUTIAO

    private val articleHeaders = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
        "Referer" to "https://m.toutiao.com/"
    )

    private val vodHeaders = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://m.toutiao.com/",
        "Accept" to "*/*"
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
            Log.w(TAG, "toutiao parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val itemId = extractItemId(input.trim())
            ?: return failResponse("无法识别头条视频链接")

        val ssr = fetchMobileSsr(itemId)
            ?: return failResponse("视频不存在或已删除")

        val play = bestPlayUrl(ssr.articleInfo, ssr.vodData)
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = itemId
        md.title = ssr.articleInfo.asString("title") ?: "无标题"

        val user = ssr.articleInfo.asObject("mediaUser")
        md.author = user?.asString("screenName")
            ?: ssr.articleInfo.asString("userName")
            ?: ssr.articleInfo.asString("source")
            ?: "未知作者"
        md.authorUid = user?.asString("userId")

        md.cover = ssr.articleInfo.asString("posterUrl") ?: ssr.vodData?.asString("CoverUrl")
        md.playUrl = play
        md.rawPlayUrl = play
        md.resolvedUrl = ssr.finalUrl
        md.addQuality(label = "默认", ratio = "default", url = play)

        return md.toServerJson()
    }

    // ========== 作品 ID 提取 ==========

    private val urlInTextPattern = Pattern.compile("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
    private val idCorePattern = Pattern.compile("^([A-Za-z0-9]+)")

    private val idQueryKeys = listOf(
        "item_id", "vid", "video_id", "id", "articleId", "group_id", "trendId", "shareId", "content_id"
    )

    private fun extractItemId(input: String): String? {
        if (input.isBlank()) return null
        val link = urlInTextPattern.matcher(input).let { if (it.find()) it.group() else null }
        val text = link ?: input
        val uri = runCatching { URI(text) }.getOrNull() ?: return null

        uri.host?.let { host ->
            if (!host.lowercase().endsWith("toutiao.com")) return null
            idFromQuery(uri)?.let { return it }
        }
        return idFromPath(uri.path.orEmpty())?.takeIf { it.isNotEmpty() }
    }

    /** 参考实现优先从查询参数中读取作品 ID（vid / item_id / shareId / articleId 等） */
    private fun idFromQuery(uri: URI): String? {
        val query = uri.query ?: return null
        if (query.isBlank()) return null
        val params = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) decodeUri(pair.substring(0, eq)) else decodeUri(pair)
            val value = if (eq >= 0) decodeUri(pair.substring(eq + 1)) else ""
            if (key in idQueryKeys && value.isNotBlank()) params[key] = value
        }
        for (key in idQueryKeys) {
            params[key]?.let { if (it.length >= 6) return cleanId(it) }
        }
        return null
    }

    /** 从路径提取：/video/{id}、/share/video/{id}、/group、/item、/article、a{id} / i{id} 等形式 */
    private fun idFromPath(path: String): String? {
        if (path.isBlank()) return null
        val segs = path.trim('/').split('/')
        for (i in segs.indices) {
            when {
                segs[i] == "video" -> segs.getOrNull(i + 1)?.let { return cleanId(it) }
                segs[i] == "share" && segs.getOrNull(i + 1) == "video" ->
                    segs.getOrNull(i + 2)?.let { return cleanId(it) }
                segs[i] == "group" || segs[i] == "item" || segs[i] == "article" ||
                    segs[i] == "detail" || segs[i] == "content" ->
                    segs.getOrNull(i + 1)?.let { return cleanId(it) }
            }
        }
        for (seg in segs) {
            if (seg.length > 8) {
                val first = seg[0]
                if (first == 'a' || first == 'A' || first == 'i' || first == 'I') {
                    val rest = seg.substring(1)
                    if (rest.isNotEmpty() && rest.all { it.isLetterOrDigit() }) return cleanId(rest)
                }
            }
        }
        segs.lastOrNull()?.let { return cleanId(it) }
        return null
    }

    /** 保留 ID 的字母数字前缀（兼容带 Z / 后缀或 .html 的形态），截掉后续路径杂质 */
    private fun cleanId(raw: String): String {
        var id = raw.trim()
        if (id.endsWith(".html")) id = id.dropLast(5)
        else if (id.endsWith(".shtml")) id = id.dropLast(6)
        if (id.isEmpty()) return id
        val m = idCorePattern.matcher(id)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    private fun decodeUri(text: String): String =
        runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8.name()) }.getOrDefault(text)

    // ========== 移动端 SSR 抓取 ==========

    private data class SsrData(
        val articleInfo: JsonObject,
        val vodData: JsonObject?,
        val finalUrl: String
    )

    private fun fetchMobileSsr(itemId: String): SsrData? {
        if (itemId.isBlank()) return null
        val candidates = listOf(
            "https://m.toutiao.com/video/$itemId/",
            "https://m.toutiao.com/i$itemId/",
            "https://m.toutiao.com/group/$itemId/",
            "https://m.toutiao.com/item/$itemId/"
        )
        for (url in candidates) {
            val resp = http.get(url, articleHeaders) ?: continue
            if (resp.statusCode != 200 || resp.body.isBlank()) continue
            val articleInfo = parseArticleInfo(resp.body) ?: continue
            val vodData = fetchVodData(articleInfo)
            return SsrData(articleInfo, vodData, resp.finalUrl)
        }
        return null
    }

    private fun parseArticleInfo(html: String): JsonObject? =
        parseRenderData(html) ?: parseWindowScriptData(html)

    private val renderDataPattern =
        Pattern.compile("<script\\s+id=\"RENDER_DATA\"[^>]*>([\\s\\S]*?)</script>")

    private fun parseRenderData(html: String): JsonObject? {
        val m = renderDataPattern.matcher(html)
        if (!m.find()) return null
        val raw = m.group(1)?.trim() ?: return null
        if (raw.isEmpty()) return null
        return runCatching {
            parseJsonObject(unquote(raw))?.asObject("articleInfo")
        }.getOrNull()
    }

    /** 等效于 Python urllib.parse.unquote：'＋' 保持字面量，仅解码 %XX */
    private fun unquote(text: String): String =
        URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private val ssrScriptStart =
        Pattern.compile("(?:_ROUTER_DATA|window\\._SSR_DATA|window\\.__INIT_PROPS__)\\s*=\\s*")

    /** 移动端 SSR 脚本兜底：_ROUTER_DATA / _SSR_DATA / __INIT_PROPS__ 中查找 articleInfo */
    private fun parseWindowScriptData(html: String): JsonObject? {
        val m = ssrScriptStart.matcher(html)
        while (m.find()) {
            if (html.getOrNull(m.end()) != '{') continue
            val end = matchingBraceEnd(html, m.end())
            if (end < 0) continue
            val root = runCatching { parseJsonObject(html.substring(m.end(), end + 1)) }.getOrNull()
                ?: continue
            findArticleInfo(root)?.let { return it }
        }
        return null
    }

    private fun matchingBraceEnd(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun findArticleInfo(root: JsonObject): JsonObject? {
        root.asObject("articleInfo")?.let { return it }
        for (entry in root.entrySet()) {
            val value = entry.value
            if (value is JsonObject) {
                findArticleInfo(value)?.let { return it }
            } else if (value is JsonArray) {
                for (element in value) {
                    if (element is JsonObject) {
                        findArticleInfo(element)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    // ========== ByteDance VOD 调度（playAuthTokenV2） ==========

    /** 对 articleInfo.playAuthTokenV2 做 base64 解码，取 GetPlayInfoToken 后请求 VOD 接口 */
    private fun fetchVodData(articleInfo: JsonObject): JsonObject? {
        val token = articleInfo.asString("playAuthTokenV2") ?: return null
        return runCatching {
            val tokenJson = parseJsonObject(
                String(Base64.decode(token, Base64.DEFAULT), StandardCharsets.UTF_8)
            ) ?: return null
            val queryStr = tokenJson.asString("GetPlayInfoToken") ?: return null
            val vodUrl = "https://vod.bytedanceapi.com/?$queryStr"
            val resp = http.get(vodUrl, vodHeaders) ?: return null
            parseJsonObject(resp.body)?.asObject("Result")?.asObject("Data")
        }.getOrNull()
    }

    // ========== 播放地址选择 ==========

    /** 优先 VOD PlayInfoList（按码率降序选最高画质），否则回退 articleInfo.playUrlList */
    private fun bestPlayUrl(articleInfo: JsonObject, vodData: JsonObject?): String? {
        if (vodData != null) {
            val playList = vodData.asArray("PlayInfoList")
            if (playList != null) {
                var bestUrl: String? = null
                var bestBitrate = 0L
                for (element in playList) {
                    if (!element.isJsonObject) continue
                    val item = element.asJsonObject
                    val url = item.asString("MainPlayUrl") ?: item.asString("BackupPlayUrl")
                        ?: continue
                    val bitrate = item.asLong("Bitrate") ?: 0L
                    if (bestUrl == null || bitrate > bestBitrate) {
                        bestUrl = url
                        bestBitrate = bitrate
                    }
                }
                if (bestUrl != null) return asMediaUrl(bestUrl)
            }
        }
        val playUrlList = articleInfo.asArray("playUrlList")
        if (playUrlList != null && playUrlList.size() > 0 && playUrlList[0].isJsonObject) {
            val first = playUrlList[0].asJsonObject
            val url = first.asString("mainUrl") ?: first.asString("backupUrl")
            if (url != null) return asMediaUrl(url)
        }
        return null
    }

    /** 协议相对地址补全为 https，非法地址返回 null */
    private fun asMediaUrl(url: String): String? {
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else null
    }

    // ========== JSON 扩展 ==========

    private fun parseJsonObject(text: String): JsonObject? {
        return runCatching {
            val element = JsonParser.parseString(text)
            if (element.isJsonObject) element.asJsonObject else null
        }.getOrNull()
    }

    private fun JsonElement.asObject(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive && get(key).asJsonPrimitive.isString) {
            get(key).asString
        } else {
            null
        }

    private fun JsonObject.asLong(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private companion object {
        const val TAG = "ToutiaoParser"
    }
}