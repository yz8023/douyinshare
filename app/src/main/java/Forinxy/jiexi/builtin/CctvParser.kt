package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 央视（CCTV/央视网/央视新闻）解析器：
 * - 央视新闻客户端微视频（阿里云 API 网关 HMAC-SHA256 签名 getArticle 接口）
 * - tv.cctv.com 等节目详情页（CNTV getHttpVideoInfo 接口）
 * 按 Python 参考实现移植。
 */
internal class CctvParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.CCTV

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://tv.cctv.com/",
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
            Log.w(TAG, "cctv parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        val content = if (url.contains("cctvnews") || url.contains("/snow-book/")) {
            parseCctvNews(url)
        } else {
            parseCctvWeb(url)
        } ?: return failResponse("无法提取视频信息")
        return content
    }

    /** 央视新闻客户端微视频 / 新闻稿 */
    private fun parseCctvNews(url: String): String? {
        val itemId = extractParam(url, "item_id") ?: extractParam(url, "articleId")
            ?: run {
                Regex("(?:item_id|articleId)=([0-9a-zA-Z_\\-]+)").find(url)?.groupValues?.get(1)
            }
            ?: return failResponse("无法提取文章 ID")

        val host = "https://api.cctvnews.cctv.com"
        val path = "/1.0.0/feed/article/server/getArticle"
        val params = listOf("articleId" to itemId, "appcode" to "video_web")
        val tNow = System.currentTimeMillis()
        val appKey = "204133710"
        val appSecret = "etyEuNdA7GvQU7iPZHqnrBpSFfRyKQTD"

        val headersMap = LinkedHashMap<String, String>()
        headersMap["x-ca-timestamp"] = tNow.toString()
        headersMap["x-ca-key"] = appKey
        headersMap["x-ca-stage"] = "RELEASE"
        headersMap["accept"] = "application/json"
        headersMap["User-Agent"] = PlatformHttp.PC_UA
        val caHeaders = headersMap.keys.filter { it.startsWith("x-ca-") }.sorted()
        headersMap["x-ca-signature-headers"] = caHeaders.joinToString(",")
        val headerStr = caHeaders.joinToString("\n") { "${it}:${headersMap[it]}" }
        val sortedParams = params.sortedBy { it.first }
        val queryStr = sortedParams.joinToString("&") { "${it.first}=${it.second}" }
        val fullPath = "$path?$queryStr"
        val stringToSign = "GET\n${headersMap["accept"]}\n\n\n\n$headerStr\n$fullPath"
        val sig = hmacSha256Base64(appSecret, stringToSign)
        headersMap["x-ca-signature"] = sig

        val resp = http.get("$host$fullPath", headersMap)
            ?: return failResponse("无法获取文章数据")
        if (resp.statusCode != 200) return failResponse("接口请求失败（${resp.statusCode}）")

        val root = parseJsonObj(resp.body) ?: return failResponse("数据解析失败")
        val b64 = root.jStr("response") ?: return failResponse("数据格式异常")
        val payload = runCatching {
            parseJsonObj(String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8))
                ?.jObj("data")
        }.getOrNull() ?: return failResponse("解码失败")

        val md = MediaData()
        md.inputUrl = url
        md.title = payload.jStr("title")?.trim().orEmpty().ifBlank { "央视新闻视频" }
        md.author = payload.firstStr("source", "author") ?: "央视新闻"

        val videos = payload.jArr("videos")
        if (videos != null && videos.size() > 0) {
            val first = videos[0].asObjOrNull()
            val vUrl = first?.jStr("url")
            if (!vUrl.isNullOrBlank()) {
                md.type = "video"
                md.playUrl = vUrl
                md.rawPlayUrl = vUrl
                md.addQuality(label = "默认", ratio = "default", url = vUrl)
                md.cover = first.jObj("cover")?.jStr("url")
            }
        }
        if (md.playUrl == null) {
            val thumbs = payload.jArr("thumbnails")
            if (thumbs != null && thumbs.size() > 0) {
                md.cover = thumbs[0].asObjOrNull()?.jStr("url")
            }
        }
        if (md.playUrl == null) return failResponse("无法提取视频地址")
        if (md.cover == null) md.cover = null
        return md.toServerJson()
    }

    /** tv.cctv.com 等 CCTV 节目详情页：先从 HTML 提取 pid/guid，再查 CNTV 接口 */
    private fun parseCctvWeb(url: String): String? {
        var pid = extractParam(url, "pid") ?: extractParam(url, "guid")
        if (pid.isNullOrBlank()) {
            val resp = http.get(url, headers) ?: return failResponse("无法获取页面")
            val m = Regex("(?:guid|pid|videoCenterId)\\s*=\\s*[\"']([0-9a-fA-F]{32})[\"']")
                .find(resp.body)
            pid = m?.groupValues?.get(1)
        }
        if (pid.isNullOrBlank()) return failResponse("无法提取视频 pid")

        val resp = http.get("https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=$pid", headers)
            ?: return failResponse("无法获取视频信息")
        val data = parseJsonObj(resp.body) ?: return failResponse("数据解析失败")

        val md = MediaData()
        md.inputUrl = url
        md.type = "video"
        md.title = data.jStr("title")?.trim().orEmpty().ifBlank { "央视视频" }
        md.author = data.firstStr("column", "produce") ?: "央视网"
        md.cover = data.jStr("image")

        val vUrl = data.firstStr("hls_url", "f4v_url", "video_url")
        if (vUrl.isNullOrBlank()) return failResponse("无法提取视频地址")
        md.playUrl = vUrl
        md.rawPlayUrl = vUrl
        md.addQuality(label = "默认", ratio = "default", url = vUrl)
        return md.toServerJson()
    }

    private fun extractParam(url: String, name: String): String? = UrlKit.query(url)[name]

    private fun hmacSha256Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private fun String.toMd5Hex(): String =
        MessageDigest.getInstance("MD5").digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CctvParser"
    }
}