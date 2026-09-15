package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * 好看视频解析器：抓取落地页 HTML，优先 window.jsonData，兜底
 * window.__PRELOADED_STATE__ 与 script 内联 JSON，提取播放地址/标题/封面/作者。
 */
internal class HaokanParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.HAOKAN

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://haokan.baidu.com/v"
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
            Log.w(TAG, "haokan parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val html = fetchHtml(input.trim())
            ?: return failResponse("无法获取页面内容")

        val data = extractJson(html)
            ?: return failResponse("视频不存在或已删除")

        val play = bestPlayUrl(data)
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = extractVid(data).orEmpty()
        md.title = extractTitle(data)
        md.author = extractAuthorNick(data)
        md.cover = extractCover(data)
        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    private fun fetchHtml(input: String): String? {
        if (input.isBlank()) return null
        val resp = http.get(input, headers) ?: return null
        if (resp.statusCode != 200 || resp.body.isBlank()) return null
        return resp.body
    }

    // ========== JSON 提取 ==========

    private val jsonDataPattern =
        Pattern.compile("window\\.jsonData\\s*=\\s*(\\{.*?\\});\\s*(?:window\\.|\\n|<)", Pattern.DOTALL)
    private val jsonDataFallback = Pattern.compile("window\\.jsonData\\s*=\\s*(\\{.*)", Pattern.DOTALL)
    private val preloadedStatePattern =
        Pattern.compile("window\\.__PRELOADED_STATE__\\s*=\\s*(\\{.*?\\};)", Pattern.DOTALL)
    private val scriptBlockPattern = Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.DOTALL)

    private fun extractJson(html: String): JsonObject? {
        extractByPattern(html, jsonDataPattern)?.let { return it }
        extractByPattern(html, jsonDataFallback)?.let { return it }
        extractByPattern(html, preloadedStatePattern)?.let { return it }

        val scriptMatcher = scriptBlockPattern.matcher(html)
        while (scriptMatcher.find()) {
            val script = scriptMatcher.group(1)?.trim() ?: continue
            if (!script.startsWith("{") || !script.endsWith("}")) continue
            if (!script.contains("\"url\"") && !script.contains("\"play_url\"") &&
                !script.contains("\"vid\"")
            ) continue
            val parsed = parseJsonObj(script) ?: continue
            val inner = parsed.jObj("data") ?: parsed
            if (inner.jStr("url") != null || inner.jStr("play_url") != null ||
                inner.jObj("videoInfo") != null
            ) return parsed
        }
        return null
    }

    private fun extractByPattern(html: String, pattern: Pattern): JsonObject? {
        val m = pattern.matcher(html)
        if (!m.find()) return null
        var raw = m.group(1)?.trim() ?: return null
        if (raw.endsWith(";")) raw = raw.dropLast(1).trim()
        val scriptEnd = raw.indexOf("</script>")
        if (scriptEnd >= 0) raw = raw.substring(0, scriptEnd).trim().trimEnd(';')
        return parseJsonObj(raw)
    }

    private fun innerData(root: JsonObject): JsonObject = root.jObj("data") ?: root

    // ========== 字段提取 ==========

    private fun bestPlayUrl(root: JsonObject): String? {
        val inner = innerData(root)
        val videoInfo = inner.jObj("videoInfo") ?: inner.jObj("response")

        if (videoInfo != null) {
            val clarityArr = videoInfo.jArr("clarityArr") ?: videoInfo.jArr("clarity_arr")
            if (clarityArr != null) {
                var best: JsonObject? = null
                var bestRank = Int.MIN_VALUE
                for (element in clarityArr) {
                    if (!element.isJsonObject) continue
                    val item = element.asJsonObject
                    if (item.jStr("url") == null) continue
                    val rank = item.jLong("rank")?.toInt() ?: 0
                    if (best == null || rank > bestRank) {
                        best = item
                        bestRank = rank
                    }
                }
                best?.jStr("url")?.let { return fixUrl(it) }
            }
            val playUrl = videoInfo.jStr("play_url") ?: videoInfo.jStr("playurl") ?: videoInfo.jStr("url")
            if (playUrl != null) return fixUrl(playUrl)
        }

        val curMeta = root.jObj("curVideoMeta") ?: inner.jObj("curVideoMeta")
        if (curMeta != null) {
            val clarityUrl = curMeta.jArr("clarityUrl")
            if (clarityUrl != null && clarityUrl.size() > 0 && clarityUrl[clarityUrl.size() - 1].isJsonObject) {
                clarityUrl[clarityUrl.size() - 1].asJsonObject.jStr("url")?.let { return fixUrl(it) }
            }
            curMeta.jStr("playurl")?.let { return fixUrl(it) }
        }

        val directUrl = inner.jStr("url")
        if (directUrl != null && (directUrl.contains(".mp4") || directUrl.contains("bdstatic"))) {
            return fixUrl(directUrl)
        }
        return null
    }

    private fun extractTitle(root: JsonObject): String {
        val inner = innerData(root)
        val videoInfo = inner.jObj("videoInfo") ?: inner.jObj("response")
        videoInfo?.jStr("title")?.let { return it }
        inner.jStr("title")?.let { return it }
        val curMeta = root.jObj("curVideoMeta") ?: inner.jObj("curVideoMeta")
        return curMeta?.jStr("title") ?: "无标题"
    }

    private fun extractCover(root: JsonObject): String? {
        val inner = innerData(root)
        val videoInfo = inner.jObj("videoInfo") ?: inner.jObj("response")
        if (videoInfo != null) {
            videoInfo.jStr("posterImage")?.let { return fixUrl(it) }
            videoInfo.jStr("poster")?.let { return fixUrl(it) }
            videoInfo.jStr("cover")?.let { return fixUrl(it) }
        }
        inner.jStr("poster")?.let { return fixUrl(it) }
        val curMeta = root.jObj("curVideoMeta") ?: inner.jObj("curVideoMeta")
        return curMeta?.jStr("poster")?.let { fixUrl(it) }
    }

    private fun extractVid(root: JsonObject): String? {
        val inner = innerData(root)
        val videoInfo = inner.jObj("videoInfo") ?: inner.jObj("response")
        videoInfo?.jStr("vid")?.let { return it }
        return inner.jStr("vid") ?: inner.jStr("vidid")
    }

    private fun extractAuthorNick(root: JsonObject): String {
        val inner = innerData(root)
        val author = inner.jObj("author")
        author?.jStr("name")?.let { return it }
        author?.jStr("author_name")?.let { return it }
        val curMeta = root.jObj("curVideoMeta") ?: inner.jObj("curVideoMeta")
        curMeta?.jObj("mth")?.jStr("author_name")?.let { return it }
        return "未知作者"
    }

    private fun fixUrl(raw: String): String {
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).replace("\\/", "/")
        return if (decoded.startsWith("//")) "https:$decoded" else decoded
    }

    private companion object {
        const val TAG = "HaokanParser"
    }
}
