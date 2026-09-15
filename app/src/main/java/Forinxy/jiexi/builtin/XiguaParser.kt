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
 * 西瓜视频解析器：移动端 SSR（RENDER_DATA / _SSR_DATA）+ ByteDance VOD 调度，
 * 按码率取最高画质播放地址。按 Python 参考实现移植（不含抖音兜底链路）。
 */
internal class XiguaParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.XIGUA

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
        "Referer" to "https://m.ixigua.com/"
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
            Log.w(TAG, "xigua parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val itemId = extractItemId(input.trim())
            ?: return failResponse("无法识别西瓜视频链接")

        val ssr = fetchMobileSsr(itemId)
            ?: return failResponse("视频不存在或已删除")

        val play = bestPlayUrl(ssr.articleInfo, ssr.vodData)
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = itemId
        md.title = ssr.articleInfo.jStr("title") ?: "无标题"

        val user = ssr.articleInfo.jObj("mediaUser") ?: ssr.articleInfo.jObj("user")
        md.author = user?.jStr("screenName")
            ?: user?.jStr("name")
            ?: ssr.articleInfo.jStr("userName")
            ?: ssr.articleInfo.jStr("source")
            ?: "未知作者"
        md.authorUid = user?.jStr("userId") ?: user?.jStr("id")

        md.cover = ssr.articleInfo.jStr("posterUrl") ?: ssr.vodData?.jStr("CoverUrl")
        md.playUrl = play
        md.rawPlayUrl = play
        md.resolvedUrl = ssr.finalUrl
        md.addQuality(label = "默认", ratio = "default", url = play)

        return md.toServerJson()
    }

    // ========== 作品 ID 提取 ==========

    private val urlInTextPattern = Pattern.compile("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
    private val idCorePattern = Pattern.compile("^([A-Za-z0-9]+)")

    private fun extractItemId(input: String): String? {
        if (input.isBlank()) return null
        val link = urlInTextPattern.matcher(input).let { if (it.find()) it.group() else null }
        val text = link ?: input
        val uri = runCatching { URI(text) }.getOrNull() ?: return null

        uri.host?.let { host ->
            if (!host.lowercase().endsWith("ixigua.com")) return null
        }
        return idFromPath(uri.path.orEmpty())?.takeIf { it.isNotEmpty() }
    }

    private fun idFromPath(path: String): String? {
        if (path.isBlank()) return null
        val segs = path.trim('/').split('/')
        for (i in segs.indices) {
            when {
                segs[i] == "video" || segs[i] == "group" || segs[i] == "item" ||
                    segs[i] == "detail" || segs[i] == "article" ->
                    segs.getOrNull(i + 1)?.let { return cleanId(it) }
            }
        }
        segs.lastOrNull()?.let { return cleanId(it) }
        return null
    }

    private fun cleanId(raw: String): String {
        var id = raw.trim()
        if (id.endsWith(".html")) id = id.dropLast(5)
        else if (id.endsWith(".shtml")) id = id.dropLast(6)
        if (id.isEmpty()) return id
        val m = idCorePattern.matcher(id)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    // ========== 移动端 SSR 抓取 ==========

    private data class SsrData(
        val articleInfo: JsonObject,
        val vodData: JsonObject?,
        val finalUrl: String
    )

    private fun fetchMobileSsr(itemId: String): SsrData? {
        if (itemId.isBlank()) return null
        val candidates = listOf(
            "https://m.ixigua.com/video/$itemId/",
            "https://m.ixigua.com/group/$itemId/",
            "https://www.ixigua.com/$itemId"
        )
        for (url in candidates) {
            val resp = http.get(url, headers) ?: continue
            if (resp.statusCode != 200 || resp.body.isBlank()) continue
            val articleInfo = parseArticleInfo(resp.body) ?: continue
            val vodData = fetchVodData(articleInfo)
            return SsrData(articleInfo, vodData, resp.finalUrl)
        }
        return null
    }

    private val renderDataPattern =
        Pattern.compile("<script\\s+id=\"(?:RENDER_DATA|_SSR_DATA)\"[^>]*>([\\s\\S]*?)</script>")

    private fun parseArticleInfo(html: String): JsonObject? {
        val m = renderDataPattern.matcher(html)
        if (!m.find()) return null
        val raw = m.group(1)?.trim() ?: return null
        if (raw.isEmpty()) return null
        return runCatching {
            val root = parseJsonObject(unquote(raw)) ?: return null
            root.jObj("articleInfo") ?: root.jObj("videoInfo")
        }.getOrNull()
    }

    private fun unquote(text: String): String =
        URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    // ========== ByteDance VOD 调度 ==========

    private fun fetchVodData(articleInfo: JsonObject): JsonObject? {
        val token = articleInfo.jStr("playAuthTokenV2") ?: return null
        return runCatching {
            val tokenJson = parseJsonObject(
                String(Base64.decode(token, Base64.DEFAULT), StandardCharsets.UTF_8)
            ) ?: return null
            val queryStr = tokenJson.jStr("GetPlayInfoToken") ?: return null
            val vodUrl = "https://vod.bytedanceapi.com/?$queryStr"
            val resp = http.get(vodUrl, headers) ?: return null
            parseJsonObject(resp.body)?.jObj("Result")?.jObj("Data")
        }.getOrNull()
    }

    private fun bestPlayUrl(articleInfo: JsonObject, vodData: JsonObject?): String? {
        if (vodData != null) {
            val playList = vodData.jArr("PlayInfoList")
            if (playList != null) {
                var bestUrl: String? = null
                var bestBitrate = 0L
                for (element in playList) {
                    if (!element.isJsonObject) continue
                    val item = element.asJsonObject
                    val url = item.jStr("MainPlayUrl") ?: item.jStr("BackupPlayUrl") ?: continue
                    val bitrate = item.jLong("Bitrate") ?: 0L
                    if (bestUrl == null || bitrate > bestBitrate) {
                        bestUrl = url
                        bestBitrate = bitrate
                    }
                }
                if (bestUrl != null) return asMediaUrl(bestUrl)
            }
        }
        val playUrlList = articleInfo.jArr("playUrlList")
        if (playUrlList != null && playUrlList.size() > 0 && playUrlList[0].isJsonObject) {
            val first = playUrlList[0].asJsonObject
            val url = first.jStr("mainUrl") ?: first.jStr("backupUrl")
            if (url != null) return asMediaUrl(url)
        }
        return null
    }

    private fun asMediaUrl(url: String): String? {
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else null
    }

    private fun parseJsonObject(text: String): JsonObject? =
        runCatching {
            val element = JsonParser.parseString(text)
            if (element.isJsonObject) element.asJsonObject else null
        }.getOrNull()

    private companion object {
        const val TAG = "XiguaParser"
    }
}
