package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject

/**
 * Soul 分享帖解析器：通过 Soul H5 公开接口提取帖子媒体与作者信息。
 * 按 Python 参考实现移植。
 */
internal class SoulParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.SOUL

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://w13.soulsmile.cn/",
        "Origin" to "https://w13.soulsmile.cn"
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
            Log.w(TAG, "soul parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val params = parsePageParams(input)
        val post = fetchPostDetail(params)

        val md = MediaData()
        md.inputUrl = input
        md.title = post.jStr("title") ?: post.jStr("content") ?: "Soul 帖子"

        val attachments = post.jArr("attachments") ?: com.google.gson.JsonArray()
        val imageUrls = mutableListOf<String>()
        var primaryVideo: JsonObject? = null
        attachments.forEach { el ->
            val item = el.asObjOrNull() ?: return@forEach
            when (item.jStr("type")) {
                "VIDEO" -> if (primaryVideo == null) primaryVideo = item
                else -> {
                    val url = item.jStr("fileUrl")
                        ?: item.jStr("imageUrl")
                        ?: item.jStr("imageOriginUrl")
                        ?: item.jStr("pictureUrl")
                    url?.let { normalizeUrl(it) }?.let { if (it.isNotBlank()) imageUrls.add(it) }
                }
            }
        }

        val user = fetchUserInfo(post.jStr("authorIdEcpt"))
        md.author = user.jStr("nickName")?.ifEmpty { null } ?: "未知作者"
        post.jStr("authorIdEcpt")?.let { md.authorUid = it }

        val videoUrl = primaryVideo?.jStr("fileUrl")?.let { normalizeUrl(it) }
        if (!videoUrl.isNullOrBlank()) {
            md.type = "video"
            md.playUrl = videoUrl
            md.rawPlayUrl = videoUrl
            md.cover = primaryVideo?.jStr("videoCoverUrl") ?: videoExtCover(primaryVideo)
            md.addQuality(label = "默认", ratio = "default", url = videoUrl)
        } else if (imageUrls.isNotEmpty()) {
            md.type = "image"
            imageUrls.distinct().forEach { md.addImage(it) }
        } else {
            return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    private fun parsePageParams(url: String): Map<String, String> {
        val query = UrlKit.query(url)
        val fragment = UrlKit.fragmentQuery(url)
        val params = LinkedHashMap<String, String>()
        for (key in listOf("postIdEcpt", "sign", "signVersion")) {
            val value = query[key] ?: fragment[key]
            if (!value.isNullOrBlank()) params[key] = value
        }
        if (params.size < 3) return params
        return params
    }

    private fun fetchPostDetail(params: Map<String, String>): JsonObject {
        if (params["postIdEcpt"].isNullOrBlank()) return JsonObject()
        val resp = http.get(UrlKit.build(POST_DETAIL_API, params), headers)
            ?: return JsonObject()
        val root = parseJsonObj(resp.body) ?: return JsonObject()
        if (!root.jBool("success")) return JsonObject()
        return root.jObj("data")?.jObj("post") ?: JsonObject()
    }

    private fun fetchUserInfo(authorIdEcpt: String?): JsonObject {
        if (authorIdEcpt.isNullOrBlank()) return JsonObject()
        val resp = http.get(
            UrlKit.build(USER_INFO_API, mapOf("userIdEcpt" to authorIdEcpt)),
            headers
        ) ?: return JsonObject()
        val root = parseJsonObj(resp.body) ?: return JsonObject()
        if (!root.jBool("success")) return JsonObject()
        return root.jObj("data") ?: JsonObject()
    }

    private fun videoExtCover(video: JsonObject?): String? {
        val raw = video?.jRawStr("ext") ?: return null
        val ext = parseJsonObj(raw) ?: return null
        return ext.jStr("videoCoverUrl")
    }

    /** 处理 \/ 反斜杠转义 */
    private fun normalizeUrl(url: String): String =
        url.replace("\\/", "/")

    private companion object {
        const val TAG = "SoulParser"
        const val POST_DETAIL_API = "https://api-h5.soulapp.cn/html/v3/post/detail"
        const val USER_INFO_API = "https://api-h5.soulapp.cn/html/v2/user/info"
    }
}