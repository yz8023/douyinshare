package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import kotlin.random.Random

/**
 * 皮皮虾解析器：跟随分享短链到详情页并提取 cell_id，再请求
 * cell_comment 接口取回作品数据（视频 / 图集），输出 data.php 兼容 JSON。
 */
internal class PiPiXiaParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.PIPIXIA

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            val md = doParse(input)
            md.toServerJson()
        } catch (e: Throwable) {
            Log.w(TAG, "parse pipixia failed", e)
            failResponse("链接无效或作品已删除")
        }
    }

    private fun doParse(input: String): MediaData {
        val startUrl = extractUrl(input)
            ?: throw ParseException("no url")
        val headers = headers()

        // 1. 跟随分享短链，OkHttp 自动跟随重定向，finalUrl 即详情页
        val follow = http.get(startUrl, headers)
            ?: throw ParseException("follow http failed")
        val finalUrl = follow.finalUrl

        val cellId = extractCellId(finalUrl)
            ?: throw ParseException("no cell_id in $finalUrl")

        // 2. 调用 cell_comment 接口取作品数据
        val apiUrl = "$WIDGET_API?${buildParams(cellId)}"
        val resp = http.get(apiUrl, headers)
            ?: throw ParseException("widget http failed")
        val root = parseJson(resp.body)
            ?: throw ParseException("widget bad json")
        val item = root.asObject("data")
            ?.asArray("cell_comments")?.firstJsonObject()
            ?.asObject("comment_info")?.asObject("item")
            ?: throw ParseException("no item")

        val pageTitle = extractPageTitle(follow.body)
        val content = item.asString("content")

        val md = MediaData()
        item.asObject("author")?.asString("name")?.takeIf { it.isNotBlank() }?.let { md.author = it }
        item.asObject("author")?.asString("id")?.let { md.authorUid = it }
        md.title = pageTitle.ifBlank { content.orEmpty() }.ifBlank { "无标题" }
        md.videoId = cellId
        md.cover = item.asObject("cover")?.asArray("url_list")?.firstJsonObject()?.asString("url")
        md.duration = item.asDouble("duration") ?: item.asObject("video")?.asDouble("duration") ?: 0.0
        md.resolvedUrl = finalUrl
        md.inputUrl = input

        // 3. 图集优先，否则视频
        val images = collectImageUrls(item)
        if (images.isNotEmpty()) {
            md.type = "image"
            images.forEach { md.addImage(it) }
        } else {
            val playUrl = item.asObject("video")?.let { pickVideoUrl(it) }
                ?: throw ParseException("no video url")
            md.type = "video"
            md.playUrl = playUrl
            md.addQuality("原画", "default", playUrl, isOriginal = true)
        }
        return md
    }

    /** 从分享文本里抠出 http(s) 链接（与 Platform 内规则一致） */
    private fun extractUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value ?: text.takeIf { it.startsWith("http") }

    /** 详情页路径尾段为数字 cell_id；否则取 finalUrl 中最长的纯数字串 */
    private fun extractCellId(finalUrl: String): String? {
        val tail = runCatching { URI(finalUrl).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull()
        if (tail != null && tail.isNotEmpty() && tail.all { it.isDigit() }) return tail
        return PURE_NUMBERS.findAll(finalUrl).maxByOrNull { it.value.length }?.value
    }

    private fun buildParams(cellId: String): String =
        "offset=0&cell_type=1&api_version=1&cell_id=$cellId&ac=wifi" +
            "&channel=huawei_1319_64&aid=1319&app_name=super"

    /** 优先 video_high，其次原始 url / download_url */
    private fun pickVideoUrl(video: JsonObject): String? {
        video.asObject("video_high")?.asArray("url_list")?.firstJsonObject()?.asString("url")?.let { return it }
        video.asArray("url_list")?.firstJsonObject()?.asString("url")?.let { return it }
        video.asString("url")?.let { return it }
        video.asString("download_url")?.let { return it }
        return null
    }

    /** 兼容 images / image_list / note.multi_image 三种图集形态 */
    private fun collectImageUrls(item: JsonObject): List<String> {
        val urls = LinkedHashSet<String>()
        item.asArray("images")?.forEach { el ->
            when {
                el.isJsonObject -> el.asJsonObject.asString("url")?.let(urls::add)
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> urls.add(el.asString)
                else -> {}
            }
        }
        item.asArray("image_list")?.forEach { el ->
            when {
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    (o.asString("url") ?: o.asArray("url_list")?.firstJsonObject()?.asString("url"))
                        ?.let(urls::add)
                }
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> urls.add(el.asString)
                else -> {}
            }
        }
        item.asObject("note")?.asArray("multi_image")?.forEach { el ->
            if (el.isJsonObject) {
                el.asJsonObject.asArray("url_list")?.firstJsonObject()?.asString("url")?.let(urls::add)
            }
        }
        return urls.toList()
    }

    private fun extractPageTitle(html: String): String {
        val m = OG_TITLE.find(html) ?: return ""
        return m.groupValues[1].replace(Regex("\\s*-\\s*皮皮虾\\s*$"), "").trim()
    }

    private fun parseJson(text: String): JsonObject? {
        return try {
            val el = JsonParser.parseString(text)
            if (el.isJsonObject) el.asJsonObject else null
        } catch (e: Exception) {
            null
        }
    }

    /** 随机 PC/移动端 UA + pipix Referer */
    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to if (Random.nextBoolean()) PlatformHttp.PC_UA else PlatformHttp.MOBILE_UA,
        "Referer" to "https://h5.pipix.com/"
    )

    private class ParseException(message: String) : RuntimeException(message)

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.asDouble(key: String): Double? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private fun JsonArray.firstJsonObject(): JsonObject? {
        for (el in this) {
            if (el.isJsonObject) return el.asJsonObject
        }
        return null
    }

    private companion object {
        const val TAG = "PiPiXiaParser"
        const val WIDGET_API = "https://api.pipix.com/bds/cell/cell_comment/"
        val URL_IN_TEXT = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
        val PURE_NUMBERS = Regex("\\d+")
        val OG_TITLE = Regex(
            """<meta\s+property=["']og:title["']\s+content=["'](.*?)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}