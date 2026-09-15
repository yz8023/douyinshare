package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 海螺AI（MiniMax）视频分享解析器：从 Next.js Flight SSR 数据流 / JSON-LD
 * 中提取 videoAsset，优先取未带品牌大标的直链。
 * 按 Python 参考实现移植。
 */
internal class HailuoParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.HAILUO

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
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
            Log.w(TAG, "hailuo parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val html = resp.body
        val asset = extractVideoAsset(html)
            ?: extractLdJsonVideo(html)
            ?: return failResponse("无法提取作品数据")

        val md = MediaData()
        md.inputUrl = input
        md.type = "video"
        md.title = asset.firstStr("title", "name") ?: "海螺AI 作品"
        md.resolvedUrl = resp.finalUrl

        val videoURLs = asset.jObj("videoURLs")
        val videoUrl: String = videoURLs?.jStr("downloadURLWithAIWatermark")
            ?: asset.jStr("downloadURL")
            ?: asset.jStr("videoURL")
            ?: videoURLs?.jStr("downloadURLWithHailuoWatermark")
            ?: return failResponse("无法提取视频地址")

        md.playUrl = videoUrl
        md.rawPlayUrl = videoUrl
        md.cover = asset.firstStr("coverURL", "promptImgURL", "thumbnailUrl")
        md.addQuality(label = "默认", ratio = "default", url = videoUrl)

        val userId = asset.firstStr("userIDStr", "userID")
        if (userId != null) md.authorUid = userId

        asset.jObj("author")?.jStr("name")?.let {
            if (it.isNotBlank()) md.author = it
        }
        return md.toServerJson()
    }

    private fun extractVideoAsset(html: String): JsonObject? {
        val flightRgx = Regex("""self\.__next_f\.push\(\[1,\s*"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        val chunks = flightRgx.findAll(html).mapNotNull { m ->
            runCatching {
                com.google.gson.JsonParser.parseString("\"${m.groupValues[1]}\"").asString
            }.getOrNull()
        }
        for (decoded in chunks) {
            if (!decoded.contains("videoAsset")) continue
            val idx = decoded.indexOf(':')
            if (idx < 0) continue
            val tree = runCatching { com.google.gson.JsonParser.parseString(decoded.substring(idx + 1)) }
                .getOrNull() ?: continue
            val asset = findKeyInTree(tree, "videoAsset")
            if (asset?.isJsonObject == true) return asset.asJsonObject
        }

        val fallback = Regex(""""videoAsset":\s*(\{.+?\})(?:,\s*"[a-zA-Z0-9_-]+":|\]|\})""").find(html)
        if (fallback != null) {
            val raw = fallback.groupValues[1]
            parseJsonObj(raw)?.let { return it }
            parseJsonObj(raw.replace("\\/", "/").replace("\\u002F", "/"))?.let { return it }
        }
        return null
    }

    private fun extractLdJsonVideo(html: String): JsonObject? {
        val rgx = Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        rgx.findAll(html).forEach { m ->
            var raw = m.groupValues[1].trim()
            if (raw.contains("children")) {
                val c = Regex(""""children":\s*(".*?")\s*\}\]\)""").find(raw)
                if (c != null) {
                    raw = runCatching { com.google.gson.JsonParser.parseString(c.groupValues[1]).asString }
                        .getOrDefault(raw)
                }
            }
            val data = parseJsonObj(raw) ?: return@forEach
            if (data.jStr("@type") == "VideoObject") return data
            val graph = data.jArr("@graph")
            if (graph != null) {
                for (el in graph) {
                    val item = el.asObjOrNull() ?: continue
                    if (item.jStr("@type") == "VideoObject") return item
                }
            }
        }
        return null
    }

    private fun findKeyInTree(obj: JsonElement?, target: String): JsonElement? {
        if (obj == null || obj.isJsonNull) return null
        if (obj.isJsonObject) {
            val o = obj.asJsonObject
            for ((key, value) in o.entrySet()) {
                if (key == target && value.isJsonObject) return value
                val res = findKeyInTree(value, target)
                if (res != null) return res
            }
        } else if (obj.isJsonArray) {
            for (item in obj.asJsonArray) {
                val res = findKeyInTree(item, target)
                if (res != null) return res
            }
        }
        return null
    }

    private companion object {
        const val TAG = "HailuoParser"
    }
}