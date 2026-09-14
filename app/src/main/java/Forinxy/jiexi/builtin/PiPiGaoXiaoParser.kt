package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import kotlin.random.Random

/**
 * 皮皮搞笑解析器：从分享链接提取 pid/mid，请求 h5.pipigx.com 的
 * fetch_content 接口取回作品数据（视频 / 图集），输出 data.php 兼容 JSON。
 */
internal class PiPiGaoXiaoParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.PIPIGAOXIAO

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input).toServerJson()
        } catch (e: Throwable) {
            Log.w(TAG, "parse pipigaoxiao failed", e)
            failResponse("链接无效或作品已删除")
        }
    }

    private fun doParse(input: String): MediaData {
        val url = extractUrl(input) ?: throw ParseException("no url")
        val pid = extractPid(url) ?: throw ParseException("no pid in $url")

        val headers = mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "User-Agent" to if (Random.nextBoolean()) PlatformHttp.PC_UA else PlatformHttp.MOBILE_UA,
            "Referer" to url
        )
        val resp = http.postJson(FETCH_API, headers, buildBody(pid, extractMid(url)).toString())
            ?: throw ParseException("fetch_content http failed")
        val root = parseJson(resp.body) ?: throw ParseException("fetch_content bad json")
        val data = root.asObject("data") ?: throw ParseException("no data node")
        val post = data.asObject("post") ?: throw ParseException("no post node")

        val md = MediaData()
        md.videoId = pid
        md.inputUrl = input
        md.resolvedUrl = url
        md.title = post.asString("content").orEmpty().ifBlank { "无标题" }

        val user = data.asObject("user")
        user?.asString("name")?.takeIf { it.isNotBlank() }?.let { md.author = it }
        (user?.asString("mid") ?: post.asString("mid"))?.takeIf { it.isNotBlank() }?.let { md.authorUid = it }

        val imgs = collectImgs(post)
        val video = post.asObject("video") ?: resolvePostVideo(post, imgs)
        val playUrl = pickVideoUrl(video)

        return if (playUrl != null) {
            md.type = "video"
            md.playUrl = playUrl
            md.addQuality("原画", "default", playUrl, isOriginal = true)
            md.duration = video?.asDouble("duration") ?: 0.0
            md.cover = imgs.firstNotNullOfOrNull { imgUrl(it) }
            md
        } else {
            val images = collectImages(imgs)
            if (images.isEmpty()) throw ParseException("no media")
            md.type = "image"
            md.cover = images.first()
            images.forEach(md::addImage)
            md
        }
    }

    /** 从分享文本里抠出 http(s) 链接（与 Platform 内规则一致） */
    private fun extractUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value ?: text.takeIf { it.startsWith("http") }

    /** 优先 pid=(\d+) 查询参数；其次 URL 路径尾段纯数字；兜底最长数字串 */
    private fun extractPid(url: String): String? {
        PID_PARAM.find(url)?.let { return it.groupValues[1] }
        val tail = runCatching { URI(url).path?.trimEnd('/')?.substringAfterLast('/') }.getOrNull()
        if (tail != null && tail.isNotEmpty() && tail.all { it.isDigit() }) return tail
        return PURE_NUMBERS.findAll(url).maxByOrNull { it.value.length }?.value
    }

    private fun extractMid(url: String): Long? =
        MID_PARAM.find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun buildBody(pid: String, mid: Long?): JsonObject {
        val body = JsonObject()
        body.addProperty("pid", pid.toLongOrNull() ?: 0L)
        if (mid != null) body.addProperty("mid", mid) else body.addProperty("mid", "null")
        body.addProperty("type", "post")
        return body
    }

    /** 参照 Python 实现，从 post.videos[img_id].url 取真实视频地址 */
    private fun resolvePostVideo(post: JsonObject, imgs: List<JsonObject>): JsonObject? {
        val videos = post.asObject("videos") ?: return null
        imgs.firstOrNull()?.asString("id")?.let { imgId ->
            val el = videos.get(imgId)
            if (el != null && el.isJsonObject) return el.asJsonObject
        }
        for (key in videos.keySet()) {
            val el = videos.get(key)
            if (el != null && el.isJsonObject) return el.asJsonObject
        }
        return null
    }

    /** 优先 url，其次 video_url / high_url / qhd_url */
    private fun pickVideoUrl(video: JsonObject?): String? {
        if (video == null) return null
        video.asString("url")?.let { return it }
        video.asString("video_url")?.let { return it }
        video.asString("high_url")?.let { return it }
        video.asString("qhd_url")?.let { return it }
        return null
    }

    private fun collectImgs(post: JsonObject): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        post.asArray("imgs")?.forEach { el -> if (el.isJsonObject) list.add(el.asJsonObject) }
        return list
    }

    private fun imgUrl(img: JsonObject): String? =
        img.asString("url")
            ?: img.asString("id")?.takeIf { it.isNotBlank() }?.let { "$IMG_HOST$it" }

    private fun collectImages(imgs: List<JsonObject>): List<String> {
        val urls = LinkedHashSet<String>()
        imgs.forEach { imgUrl(it)?.let(urls::add) }
        return urls.toList()
    }

    private fun parseJson(text: String): JsonObject? {
        return try {
            val el = JsonParser.parseString(text)
            if (el.isJsonObject) el.asJsonObject else null
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.asDouble(key: String): Double? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private class ParseException(message: String) : RuntimeException(message)

    private companion object {
        const val TAG = "PiPiGaoXiaoParser"
        const val FETCH_API = "https://h5.pipigx.com/ppapi/share/fetch_content"
        const val IMG_HOST = "https://file.ippzone.com/img/view/id/"
        val URL_IN_TEXT = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
        val PID_PARAM = Regex("pid=(\\d+)")
        val MID_PARAM = Regex("mid=(\\d+)")
        val PURE_NUMBERS = Regex("\\d+")
    }
}