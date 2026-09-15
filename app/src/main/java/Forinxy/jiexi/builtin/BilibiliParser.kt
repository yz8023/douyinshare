package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.regex.Pattern

/**
 * 哔哩哔哩视频解析器：通过 B 站官方 view / playurl API 获取可直接播放的
 * 视频地址与元信息，输出与 data.php 兼容的 JSON。支持完整链接、b23.tv 短链、
 * 裸 BV 号与 av 号四种输入。
 */
internal class BilibiliParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.BILIBILI

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://www.bilibili.com/",
        "Accept-Language" to "zh-CN,zh;q=0.9"
    )

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return runCatching {
            try {
                doParse(input)
            } catch (e: ParseFailed) {
                failResponse(e.message ?: "解析失败，请稍后重试")
            }
        }.getOrElse { e ->
            Log.w(TAG, "bilibili parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resolved = resolveId(input.trim())
        val bvid: String?
        val aid: String?
        when {
            resolved.bvid != null -> {
                bvid = resolved.bvid
                aid = null
            }
            resolved.aid != null -> {
                bvid = null
                aid = resolved.aid
            }
            else -> return failResponse(resolved.error ?: "无法识别哔哩哔哩视频链接")
        }

        val viewUrl = if (bvid != null) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        } else {
            "https://api.bilibili.com/x/web-interface/view?aid=$aid"
        }
        val viewData = fetchView(viewUrl) ?: return failResponse("获取视频信息失败，请稍后重试")

        val realBvid = viewData.asString("bvid") ?: bvid
            ?: return failResponse("视频信息缺失，请稍后重试")
        val cid = viewData.asLong("cid")
            ?: viewData.asArray("pages")?.firstJsonObject()?.asLong("cid")
            ?: return failResponse("视频信息缺失，请稍后重试")

        val playUrl = "https://api.bilibili.com/x/player/playurl" +
            "?otype=json&fnver=0&fnval=3&player=3&qn=112&bvid=$realBvid&cid=$cid&platform=html5&high_quality=1"
        val playData = fetchPlay(playUrl) ?: return failResponse("获取播放地址失败，请稍后重试")
        val directUrl = extractPlayUrl(playData)
            ?: return failResponse("未获取到可播放的视频地址")

        val owner = viewData.asObject("owner")
        val media = MediaData()
        media.author = owner?.asString("name") ?: "未知作者"
        media.authorUid = owner?.asLong("mid")?.toString()
        media.title = viewData.asString("title") ?: "无标题"
        media.videoId = realBvid
        media.cover = viewData.asString("pic")?.let { if (it.startsWith("//")) "https:$it" else it }
        media.timestamp = viewData.asLong("pubdate") ?: (System.currentTimeMillis() / 1000)
        media.duration = viewData.asDouble("duration") ?: 0.0
        media.type = "video"
        media.playUrl = directUrl
        media.resolvedUrl = "https://www.bilibili.com/video/$realBvid"
        media.inputUrl = input

        val bestQn = pickQuality(playData)
        media.addQuality(
            label = qnLabel(bestQn),
            ratio = "default",
            url = directUrl
        )

        return media.toServerJson()
    }

    // ========== 作品 ID 解析 ==========

    private data class ResolvedId(
        val bvid: String? = null,
        val aid: String? = null,
        val error: String? = null
    )

    private val bvidPattern = Pattern.compile("BV1[a-zA-Z0-9]{9}")
    private val avPattern = Pattern.compile("(?:^|[/?&\\.]|\\b)av(\\d+)", Pattern.CASE_INSENSITIVE)
    private val urlInTextPattern = Pattern.compile("https?://[^\\s\\u4e00-\\u9fa5'\"]+")

    private fun resolveId(input: String): ResolvedId {
        bvidPattern.matcher(input).let { if (it.find()) return ResolvedId(bvid = it.group()) }
        avPattern.matcher(input).let {
            if (it.find()) {
                val digits = it.group(1)
                if (digits != null && digits.isNotEmpty()) return ResolvedId(aid = digits)
            }
        }
        if (input.isEmpty()) return ResolvedId(error = "请输入哔哩哔哩视频链接或 BV 号")

        val url = urlInTextPattern.matcher(input).takeIf { it.find() }?.group()
        if (url.isNullOrBlank()) return ResolvedId(error = "无法识别哔哩哔哩视频链接，请输入视频链接、BV 号或 av 号")
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: ""
        if (!host.endsWith("b23.tv") && !host.endsWith("bilibili.com")) {
            return ResolvedId(error = "请输入哔哩哔哩视频链接、BV 号或 av 号")
        }
        if (host.endsWith("b23.tv")) {
            val shortcut = http.get(url, headers)
                ?: return ResolvedId(error = "短链接跳转失败，请检查链接是否有效")
            val finalUrl = shortcut.finalUrl
            bvidPattern.matcher(finalUrl).let { if (it.find()) return ResolvedId(bvid = it.group()) }
            avPattern.matcher(finalUrl).let {
                if (it.find()) {
                    val digits = it.group(1)
                    if (digits != null && digits.isNotEmpty()) return ResolvedId(aid = digits)
                }
            }
            return ResolvedId(error = "无法从短链接中解析出视频 ID，请确认链接有效")
        }
        return ResolvedId(error = "未在链接中解析出视频 ID")
    }

    // ========== API 请求 ==========

    /** 请求 view API，code==0 时返回 result.data */
    private fun fetchView(url: String): JsonObject? {
        val result = http.get(url, headers) ?: return null
        val obj = parseJsonObject(result.body) ?: return null
        val code = obj.asLong("code")
        if (code != 0L) {
            throw ParseFailed(
                if (code == -404L) "视频不存在或已删除"
                else obj.asString("message") ?: "视频获取失败，请稍后重试"
            )
        }
        return obj.asObject("data")
    }

    /** 请求 playurl API，code==0 时返回 result.data */
    private fun fetchPlay(url: String): JsonObject? {
        val result = http.get(url, headers) ?: return null
        val obj = parseJsonObject(result.body) ?: return null
        if (obj.asLong("code") != 0L) {
            throw ParseFailed(obj.asString("message") ?: "播放地址获取失败，请稍后重试")
        }
        return obj.asObject("data")
    }

    private fun parseJsonObject(text: String): JsonObject? {
        return runCatching {
            val element = JsonParser.parseString(text)
            if (element.isJsonObject) element.asJsonObject else null
        }.getOrNull()
    }

    // ========== 播放地址提取 ==========

    /** 依次尝试 durl[0].url → dash.video[0] 直链 → 扫描任意直接媒体 URL */
    private fun extractPlayUrl(data: JsonObject): String? {
        data.asArray("durl")?.let { durls ->
            if (durls.size() > 0) {
                val first = durls[0].asObject()
                val url = first?.asString("url")
                if (!url.isNullOrBlank() && url.startsWith("http")) return url
            }
        }
        data.asObject("dash")?.asArray("video")?.let { videos ->
            if (videos.size() > 0) {
                val first = videos[0].asObject()
                val url = first?.asString("baseUrl") ?: first?.asString("base_url")
                if (!url.isNullOrBlank() && url.startsWith("http")) return url
            }
        }
        return firstMediaUrl(data)
    }

    private fun firstMediaUrl(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val value = element.asString
            return if (looksLikeMediaUrl(value)) value else null
        }
        if (element.isJsonObject) {
            for (entry in element.asJsonObject.entrySet()) {
                firstMediaUrl(entry.value)?.let { return it }
            }
        } else if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                firstMediaUrl(item)?.let { return it }
            }
        }
        return null
    }

    private fun looksLikeMediaUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".m4s") || lower.contains(".dts")
    }

    // ========== 画质 ==========

    private fun pickQuality(data: JsonObject): Int {
        val accepted = data.asArray("accept_quality")?.asIntList().orEmpty()
        if (accepted.size > 1) return accepted.max() ?: 112
        return (data.asLong("quality")?.toInt()) ?: 112
    }

    private fun qnLabel(qn: Int): String = when (qn) {
        127, 126, 125, 120 -> "4K 超清"
        116 -> "1080P 高帧率"
        112, 80 -> "高清 1080P"
        74, 64 -> "高清 720P"
        32 -> "清晰 480P"
        16 -> "流畅 360P"
        6 -> "流畅 240P"
        else -> "画质 $qn"
    }

    private class ParseFailed(message: String) : RuntimeException(message)

    // ========== JSON 扩展 ==========

    private fun JsonElement.asObject(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.asLong(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.asDouble(key: String): Double? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private fun JsonArray.firstJsonObject(): JsonObject? {
        for (element in this) if (element.isJsonObject) return element.asJsonObject
        return null
    }

    private fun JsonArray.asIntList(): List<Int> {
        val result = mutableListOf<Int>()
        for (element in this) {
            if (element.isJsonPrimitive) {
                runCatching { element.asInt }.getOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    private companion object {
        const val TAG = "BilibiliParser"
    }
}