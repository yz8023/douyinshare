package Forinxy.jiexi.builtin

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * 美拍解析器：抓取 H5 页面 window.PHPDATA 中的 mediaInfo，按内置算法
 * 解码视频串并请求 CDN 重定向接口获取直链。按 Python 参考实现移植。
 */
internal class MeipaiParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.MEIPAI

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://www.meipai.com/"
    )

    private val mediaIdPattern = Pattern.compile("\\d{15,}")
    private val phpDataStart = "window.PHPDATA = "

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "meipai parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val mediaId = extractMediaId(input.trim())
            ?: return failResponse("无法识别美拍链接")

        val phpData = fetchPhpData(mediaId)
            ?: return failResponse("视频不存在或已删除")

        val mediaInfo = phpData.jObj("mediaInfo")
            ?: return failResponse("视频不存在或已删除")

        val videoUrl = decodeVideoString(mediaInfo.jStr("video"))
            ?: return failResponse("无法获取播放地址")

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = mediaId

        val rawTitle = mediaInfo.jStr("caption_origin") ?: mediaInfo.jStr("caption") ?: ""
        md.title = stripHtml(rawTitle).ifEmpty { "无标题" }

        val cover = mediaInfo.jStr("cover_pic")?.let { fixProtocol(it.substringBefore("!")) }
        md.cover = cover

        val user = mediaInfo.jObj("user")
        md.author = user?.jStr("screen_name") ?: "未知作者"
        md.authorUid = user?.jLong("id")?.toString()
        md.authorSecUid = user?.jStr("domain")

        md.playUrl = videoUrl
        md.rawPlayUrl = videoUrl
        md.addQuality(label = "默认", ratio = "default", url = videoUrl)
        return md.toServerJson()
    }

    private fun extractMediaId(input: String): String? {
        if (input.isBlank()) return null
        val m = mediaIdPattern.matcher(input)
        return if (m.find()) m.group() else null
    }

    private fun fetchPhpData(mediaId: String): JsonObject? {
        val resp = http.get("https://www.meipai.com/media/$mediaId", headers)
            ?: return null
        if (resp.statusCode != 200 || resp.body.isBlank()) return null
        val start = resp.body.indexOf(phpDataStart)
        if (start < 0) return null
        val jsonStart = start + phpDataStart.length
        return rawDecodeObject(resp.body, jsonStart)
    }

    /** 等效 Python json.JSONDecoder.raw_decode：从指定位置解析第一个完整 JSON 对象 */
    private fun rawDecodeObject(text: String, start: Int): JsonObject? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
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
                    if (depth == 0) {
                        return parseJsonObj(text.substring(start, i + 1))
                    }
                }
            }
        }
        return null
    }

    /**
     * 美拍视频串解码：前 4 位十六进制逆序转十进制得到两组位置信息，
     * 分别从头部/尾部摘除两段重复子串，剩余内容 base64 解码为原始地址。
     */
    private fun decodeVideoString(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return runCatching {
            val hexVal = encoded.take(4).reversed()
            val eDec = hexVal.toLong(16).toString()
            val pre = eDec.take(2).map { it.digitToInt() }
            val tail = eDec.drop(2).map { it.digitToInt() }
            val rest = encoded.drop(4)

            val idx1 = pre[0]
            val len1 = pre[1]
            val a1 = rest.substring(idx1, idx1 + len1)
            val str1 = rest.take(idx1) + rest.drop(idx1).replaceFirst(a1, "")

            val tailIdx = str1.length - tail[0] - tail[1]
            val tailLen = tail[1]
            val a2 = str1.substring(tailIdx, tailIdx + tailLen)
            val str2 = str1.take(tailIdx) + str1.drop(tailIdx).replaceFirst(a2, "")

            val padded = padBase64(str2)
            val rawUrl = String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)

            val normalized = when {
                rawUrl.startsWith("//") -> "https:$rawUrl"
                rawUrl.startsWith("http://") -> "https://" + rawUrl.substring(7)
                else -> rawUrl
            }
            resolveCdnUrl(normalized) ?: normalized
        }.getOrNull()
    }

    private fun padBase64(text: String): String {
        val mod = text.length % 4
        return if (mod == 0) text else text + "=".repeat(4 - mod)
    }

    private fun resolveCdnUrl(rawUrl: String): String? {
        val resp = http.get(
            "https://cracl.meitubase.com/resource/get_cdn_url?url=$rawUrl",
            headers
        ) ?: return null
        return if (resp.statusCode == 200) resp.finalUrl.takeIf { it.startsWith("http") } else null
    }

    private fun stripHtml(text: String): String =
        text.replace(Pattern.compile("<[^>]+>").toRegex(), "").trim()

    private fun fixProtocol(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    private companion object {
        const val TAG = "MeipaiParser"
    }
}
