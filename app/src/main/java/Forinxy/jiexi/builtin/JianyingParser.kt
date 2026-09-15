package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement

/**
 * 剪映模板分享 (lv.ulikecam.com) 与 CapCut 协作审阅分享 (www.capcut.cn) 解析器。
 * 接口请求需附带 sign/device-time 头，含两阶段 CapCut API。按 Python 参考实现移植。
 */
internal class JianyingParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.JIANYING

    private val detailApi = "https://lv-api.ulikecam.com/lv/v1/web/replicate/multi_get_templates"
    private val capcutClusterApi = "https://www.capcut.cn/lv/v1/coordination/cluster_list"
    private val capcutDetailApi = "https://www.capcut.cn/lv/v1/coordination/share_detail_query"

    private val userAgent = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
            Log.w(TAG, "jianying parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        val extracted = extractParams(url)
        val (shareType, targetId, itemType) = extracted
        if (targetId.isBlank()) return failResponse("无法提取分享ID")

        val data = if (shareType == "capcut") fetchCapcutShare(targetId, url)
        else fetchJianyingTemplate(targetId, itemType, url)
        if (data == null) return failResponse("内容不存在或已删除")

        val videoUrl = data.videoUrl
        val images = data.images
        val title = data.title

        val md = MediaData()
        md.inputUrl = input
        md.resolvedUrl = url
        md.author = data.authorName ?: "未知作者"
        md.title = title

        when {
            videoUrl != null -> {
                md.type = "video"
                md.playUrl = videoUrl
                md.rawPlayUrl = videoUrl
                md.cover = data.coverUrl
                md.addQuality(label = "默认", ratio = "default", url = videoUrl)
                videoUrl
            }
            images.isNotEmpty() -> {
                md.type = "image"
                md.cover = images.first()
                images.forEach { md.addImage(it) }
            }
            else -> return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    private data class Params(val shareType: String, val targetId: String, val itemType: Int)

    private fun extractParams(url: String): Params {
        val host = hostOf(url)
        val path = pathOf(url)
        if (host.contains("capcut") || path.contains("/share/")) {
            val m = Regex("/share/(\\d+)").find(path)
            return Params("capcut", m?.groupValues?.get(1) ?: "", 0)
        }
        val query = UrlKit.query(url)
        val templateId = query["template_id"] ?: ""
        val itemType = runCatching { (query["item_type"] ?: "0").toInt() }.getOrDefault(0)
        return Params("jianying_template", templateId, itemType)
    }

    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", url).substringBefore('/')
        return afterScheme.substringBefore(':').lowercase()
    }

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').let { "/$it" }
    }

    private data class MediaDataExt(
        val title: String,
        val videoUrl: String?,
        val coverUrl: String?,
        val authorName: String?,
        val images: List<String>
    )

    private fun md5Hex(s: String): String {
        try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            return digest.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun fetchCapcutShare(shareId: String, shareUrl: String): MediaDataExt? {
        val timestamp = System.currentTimeMillis() / 1000L
        var innerShareId = shareId

        // 阶段 1: cluster_list
        val path1 = "/lv/v1/coordination/cluster_list"
        val sign1 = md5Hex("9e2c|${path1.takeLast(7)}|7||$timestamp||11ac")
        val headers1 = mapOf(
            "sign" to sign1,
            "device-time" to timestamp.toString(),
            "pf" to "7",
            "sign-ver" to "1",
            "User-Agent" to userAgent,
            "Content-Type" to "application/json",
            "Origin" to "https://www.capcut.cn",
            "Referer" to shareUrl
        )
        try {
            val body = """{"share_id":"$shareId","password":""}"""
            val resp = http.postJson(capcutClusterApi, headers1, body)
            if (resp != null && resp.statusCode == 200) {
                val root = parseJsonObj(resp.body)
                val list = root?.jObj("data")?.jArr("share_info_list") ?: emptyList<JsonElement>()
                list.firstOrNull()?.asObjOrNull()?.jStr("share_id")?.let { innerShareId = it }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "capcut cluster_list failed", e)
        }

        // 阶段 2: share_detail_query
        try {
            val path2 = "/lv/v1/coordination/share_detail_query".takeLast(7)
            val sign2 = md5Hex("9e2c|$path2|7||$timestamp||11ac")
            val headers2 = mapOf(
                "sign" to sign2,
                "device-time" to timestamp.toString(),
                "pf" to "7",
                "sign-ver" to "1",
                "User-Agent" to userAgent,
                "Content-Type" to "application/json",
                "Origin" to "https://www.capcut.cn",
                "Referer" to shareUrl
            )
            val resp = http.postJson(capcutDetailApi, headers2, """{"share_id":"$innerShareId"}""")
            if (resp == null || resp.statusCode != 200) return null
            val root = parseJsonObj(resp.body) ?: return null
            val data = root.jObj("data") ?: return null

            val normalV = data.jObj("normal_video")?.asObjOrNull()
            val vObj = data.jObj("video")?.asObjOrNull()
            val videoUrl = pickMainUrl(normalV) ?: pickMainUrl(vObj)

            val coverObj = data.jObj("cover_image")?.asObjOrNull()
            val coverUrl = coverObj?.firstStr("preview_1080p_url", "preview_720p_url", "preview_360p_url")
                ?: ""

            val title = data.jStr("file_name") ?: data.jStr("share_name") ?: "CapCut视频"
            val author = data.jStr("uploader_name") ?: ""
            return MediaDataExt(title, videoUrl, coverUrl, author, emptyList())
        } catch (e: Throwable) {
            Log.w(TAG, "capcut share_detail_query failed", e)
        }
        return null
    }

    private fun pickMainUrl(obj: com.google.gson.JsonObject?): String? {
        if (obj == null) return null
        for (k in listOf("player_720p", "player_480p", "player_360p")) {
            obj.jObj(k)?.jStr("main_url")?.let { return it }
        }
        return null
    }

    private fun fetchJianyingTemplate(targetId: String, itemType: Int, shareUrl: String): MediaDataExt? {
        val timestamp = System.currentTimeMillis() / 1000L
        val sign = md5Hex("9e2c|mplates|0||$timestamp||11ac")
        val headers = mapOf(
            "sign" to sign,
            "pf" to "0",
            "sign-ver" to "1",
            "device-time" to timestamp.toString(),
            "User-Agent" to userAgent,
            "Content-Type" to "application/json",
            "Origin" to "https://lv.ulikecam.com",
            "Referer" to "https://lv.ulikecam.com/"
        )
        try {
            val body = """{"sdk_version":"100.0.0","id":["$targetId"],"scene":"share","item_type":$itemType}"""
            val resp = http.postJson(detailApi, headers, body)
            if (resp == null || resp.statusCode != 200) return null
            val root = parseJsonObj(resp.body) ?: return null
            val templates = root.jObj("data")?.jArr("templates") ?: emptyList<JsonElement>()
            val tpl = templates.firstOrNull()?.asObjOrNull() ?: return null

            val videoUrl = tpl.jStr("video_url") ?: tpl.jStr("videoUrl")
            val coverUrl = tpl.jStr("cover_url") ?: tpl.jStr("cover")
            val title = tpl.jStr("title") ?: tpl.jStr("short_title") ?: "剪映模板"

            val author = tpl.jObj("author")?.asObjOrNull()
            val authorName = author?.jStr("name")
                ?: author?.jStr("nickname")
                ?: author?.jObj("aweme_info")?.asObjOrNull()?.jStr("name")

            val images = mutableListOf<String>()
            val rawImgs = tpl.jArr("images")
            rawImgs?.forEach {
                val u = when {
                    it.isJsonObject -> it.asObjOrNull()?.jStr("url")
                    it.isJsonPrimitive -> it.asStrOrNull()
                    else -> null
                }
                u?.takeIf { it.isNotBlank() }?.let { images.add(it) }
            }
            // 音视频模板：音频播放地址作为降级媒体
            val musicUrl = tpl.jObj("music_info")?.asObjOrNull()?.jStr("play_url")

            return if (videoUrl != null || images.isNotEmpty() || musicUrl != null) {
                MediaDataExt(title, videoUrl, coverUrl ?: "", authorName, images)
            } else null
        } catch (e: Throwable) {
            Log.w(TAG, "jianying template failed", e)
        }
        return null
    }

    private companion object {
        const val TAG = "JianyingParser"
    }
}