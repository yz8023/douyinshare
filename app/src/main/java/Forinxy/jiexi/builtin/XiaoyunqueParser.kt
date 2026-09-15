package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject

/**
 * 小云雀AI 分享解析器：通过小云雀官方 landing_page 接口解析分享链接。
 * 按 Python 参考实现移植。
 */
internal class XiaoyunqueParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.XIAOYUNQUE

    private val headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "User-Agent" to USER_AGENT
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
            Log.w(TAG, "xiaoyunque parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        var qdict = UrlKit.query(input)
        var fetchUrl = input.trim()

        // 无 query 但路径带 /s/ 的短链：先跟随重定向拿到真实 query
        if (qdict.isEmpty() && fetchUrl.contains("/s/")) {
            val target = if (fetchUrl.endsWith("/")) fetchUrl else "$fetchUrl/"
            val resp = http.get(target, headers)
            if (resp != null) {
                qdict = UrlKit.query(resp.finalUrl)
            }
        }
        if (qdict.isEmpty()) return failResponse("无法提取分享参数")

        val body = com.google.gson.JsonObject().apply {
            val queryParams = com.google.gson.JsonObject()
            qdict.forEach { (k, v) -> queryParams.addProperty(k, v) }
            add("query_params", queryParams)
        }.toString()

        val resp = http.postJson(API_URL, headers, body)
            ?: return failResponse("无法获取作品信息")
        val root = parseJsonObj(resp.body) ?: return failResponse("作品信息解析失败")
        if (root.jLong("err_no") != 0L) {
            return failResponse(root.jStr("err_tips") ?: "解析失败")
        }

        val data = root.jObj("data") ?: return failResponse("作品数据为空")
        val item = extractItemInfo(data) ?: return failResponse("作品数据为空")

        val md = MediaData()
        md.inputUrl = input
        md.title = item.firstStr("title", "desc") ?: "小云雀AI 作品"
        md.resolvedUrl = resp.finalUrl

        val user = item.jObj("author_info") ?: item.jObj("user_info")
        if (user != null) {
            md.author = user.firstStr("nick_name", "nickname")?.ifEmpty { null } ?: "未知作者"
            user.firstStr("user_id", "sec_uid")?.let { md.authorUid = it }
        }

        val imageUrls = LinkedHashSet<String>()
        val imageInfo = item.jObj("image_info") ?: item.jObj("images")
        extractImageUrls(imageInfo, imageUrls)
        if (item.jArr("image_info") != null) extractImageUrls(item.jArr("image_info"), imageUrls)
        if (item.jArr("images") != null) extractImageUrls(item.jArr("images"), imageUrls)

        val videoUrls = LinkedHashSet<String>()
        item.firstStr("video_url", "video_play_url")?.let { videoUrls.add(it) }
        val videoInfo = item.jObj("video_info") ?: item.jObj("video")
        extractVideoUrls(videoInfo, videoUrls)
        if (item.jArr("video_info") != null) extractVideoUrls(item.jArr("video_info"), videoUrls)
        if (item.jArr("video") != null) extractVideoUrls(item.jArr("video"), videoUrls)

        var cover = item.firstStr("cover_url", "poster", "cover")
        if (cover == null) cover = extractCoverFromVideoInfo(videoInfo)

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
            else -> return failResponse("无法提取媒体内容")
        }
        if (cover != null) md.cover = cover
        return md.toServerJson()
    }

    /** 定位命中的页面节点并返回其 item_info */
    private fun extractItemInfo(data: JsonObject): JsonObject? {
        val pageInfo = data.jObj("page_info") ?: return null
        for (key in listOf("generate_page", "inspiration_page", "template_page", "share_page", "gugu_page")) {
            val page = pageInfo.jObj(key)
            if (page != null) {
                val item = resolveItem(page)
                if (item != null) return item
            }
        }
        for ((_, value) in pageInfo.entrySet()) {
            val page = value.asObjOrNull() ?: continue
            if (page.jObj("item_info") != null || page.jObj("user_info") != null ||
                page.jObj("item_list") != null || page.jArr("item_list") != null
            ) {
                val item = resolveItem(page)
                if (item != null) return item
            }
        }
        return null
    }

    private fun resolveItem(page: JsonObject): JsonObject? {
        val list = page.jArr("item_list")
        if (list != null && list.size() > 0) {
            var idx = page.jLong("current_index")?.toInt() ?: 0
            if (idx < 0 || idx >= list.size()) idx = 0
            val item = list[idx].asObjOrNull()
            if (item != null) return item
        }
        return page.jObj("item_info")
    }

    private fun extractImageUrls(info: com.google.gson.JsonElement?, out: MutableSet<String>) {
        if (info == null) return
        if (info.isJsonObject) {
            info.asObjOrNull()?.firstStr("image_url", "url")?.let { if (it.isNotBlank()) out.add(it) }
        } else if (info.isJsonArray) {
            for (el in info.asJsonArray) {
                if (el.isJsonObject) {
                    el.asObjOrNull()?.firstStr("image_url", "url")?.let { if (it.isNotBlank()) out.add(it) }
                } else if (el.isJsonPrimitive) {
                    val s = el.asStrOrNull()
                    if (!s.isNullOrBlank()) out.add(s)
                }
            }
        }
    }

    private fun extractVideoUrls(info: com.google.gson.JsonElement?, out: MutableSet<String>) {
        if (info == null) return
        if (info.isJsonObject) {
            info.asObjOrNull()?.firstStr("video_url", "main_url", "url")?.let { if (it.isNotBlank()) out.add(it) }
        } else if (info.isJsonArray) {
            for (el in info.asJsonArray) {
                if (el.isJsonObject) {
                    el.asObjOrNull()?.firstStr("video_url", "main_url", "url")?.let { if (it.isNotBlank()) out.add(it) }
                } else if (el.isJsonPrimitive) {
                    val s = el.asStrOrNull()
                    if (!s.isNullOrBlank()) out.add(s)
                }
            }
        }
    }

    private fun extractCoverFromVideoInfo(info: com.google.gson.JsonElement?): String? {
        return when {
            info == null -> null
            info.isJsonObject -> info.asObjOrNull()?.firstStr("cover_url", "poster", "cover")
            info.isJsonArray -> {
                for (el in info.asJsonArray) {
                    val o = el.asObjOrNull() ?: continue
                    o.firstStr("cover_url", "poster", "cover")?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private companion object {
        const val TAG = "XiaoyunqueParser"
        const val API_URL = "https://xiaoyunque.jianying.com/luckycat/cn/jianying/campaign/v1/pippit/share/landing_page"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}