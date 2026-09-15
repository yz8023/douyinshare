package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject

/**
 * 松果时刻（Pinecone Moment）分享解析器：通过公开接口提取故事视频、
 * 图集与配音音频。按 Python 参考实现移植。
 */
internal class PineconeMomentParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.PINECONE_MOMENT

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://m.pineconemoment.com/"
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
            Log.w(TAG, "pinecone_moment parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val params = paramsFromUrl(input)
        val storyId = params["item_id"].orEmpty()
        if (storyId.isBlank()) return failResponse("无法提取作品 ID")

        val resp = http.get(UrlKit.build(API_URL, params), headers)
            ?: return failResponse("无法获取作品信息")
        val root = parseJsonObj(resp.body) ?: return failResponse("作品信息解析失败")
        val code = root.jLong("code") ?: -1L
        if (code != 0L && code != 200L) return failResponse("作品不存在或已删除")

        val data = root.jObj("data") ?: return failResponse("作品信息为空")
        val story = data.jObj("story") ?: data.jObj("interactive_comic")
            ?: return failResponse("作品信息为空")

        val md = MediaData()
        md.inputUrl = input
        md.title = story.jStr("title") ?: "无标题"
        md.resolvedUrl = resp.finalUrl

        val creator = story.jObj("creator") ?: story.jObj("sharer")
        md.author = creator?.jStr("nickname")?.ifEmpty { null } ?: "未知作者"
        creator?.jStr("user_id")?.let { md.authorUid = it }

        val images = story.jArr("images") ?: com.google.gson.JsonArray()
        val videoUrls = LinkedHashSet<String>()
        images.forEach { el ->
            val item = el.asObjOrNull() ?: return@forEach
            item.jStr("video_url")?.let { v -> if (validUrl(v)) videoUrls.add(v) }
        }
        val imageUrls = mutableListOf<String>()
        images.forEach { el ->
            val item = el.asObjOrNull() ?: return@forEach
            item.jStr("url")?.let { u -> if (validUrl(u)) imageUrls.add(u) }
        }

        val cover = firstMediaField(images, "video_cover_url")
            ?: firstMediaField(images, "url")
            ?: story.jStr("cover_uri")

        when {
            videoUrls.isNotEmpty() -> {
                val primary = videoUrls.first()
                md.type = "video"
                md.playUrl = primary
                md.rawPlayUrl = primary
                md.addQuality(label = "默认", ratio = "default", url = primary)
            }
            imageUrls.isNotEmpty() -> {
                md.type = "image"
                imageUrls.forEach { md.addImage(it) }
            }
            else -> {
                val audio = story.jObj("dubbing")?.jObj("h5_audio")?.jStr("audio_url")
                if (audio.isNullOrBlank()) return failResponse("无法提取媒体内容")
                md.type = "music"
                md.playUrl = audio
                md.rawPlayUrl = audio
                md.addQuality(label = "默认", ratio = "default", url = audio)
            }
        }
        if (cover != null && md.cover == null) md.cover = cover
        return md.toServerJson()
    }

    /** item_id 优先取 query，其次取 /story/<id> 路径 */
    private fun paramsFromUrl(url: String?): Map<String, String> {
        val query = UrlKit.query(url)
        val path = pathWithoutQuery(url)
        var storyId = query["item_id"] ?: query["story_id"]
        if (storyId.isNullOrBlank()) {
            val parts = path.split("/").filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts[parts.size - 2] == "story") {
                storyId = parts.last()
            }
        }
        val params = LinkedHashMap<String, String>()
        params["item_type"] = query["item_type"] ?: query["story_type"] ?: "1"
        if (!storyId.isNullOrBlank()) params["item_id"] = storyId
        for (key in listOf("sharer_id", "author_id", "channel", "version", "style_id", "share_id")) {
            query[key]?.let { params[key] = it }
        }
        return params
    }

    private fun pathWithoutQuery(url: String?): String {
        val u = url.orEmpty()
        val q = u.indexOf('?')
        return if (q >= 0) u.substring(0, q) else u
    }

    private fun firstMediaField(images: com.google.gson.JsonArray, field: String): String? {
        for (el in images) {
            val item = el.asObjOrNull() ?: continue
            val v = item.jStr(field)
            if (!v.isNullOrBlank() && validUrl(v)) return v
        }
        return null
    }

    private fun validUrl(url: String?): Boolean =
        url?.startsWith("http://") == true || url?.startsWith("https://") == true

    private companion object {
        const val TAG = "PineconeMomentParser"
        const val API_URL = "https://m.pineconemoment.com/share/item/detail"
    }
}