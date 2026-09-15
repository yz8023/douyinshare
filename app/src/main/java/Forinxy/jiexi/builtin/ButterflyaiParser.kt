package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject

/**
 * 星绘AI（ByteDance 豆包家族）分享解析器：通过官方 share/record/get 接口
 * 提取无水印原画图集与视频生成作品。按 Python 参考实现移植。
 */
internal class ButterflyaiParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.BUTTERFLYAI

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://www.butterflyai.cn/",
        "Accept" to "application/json, text/plain, */*"
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
            Log.w(TAG, "butterflyai parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        var shareCode = UrlKit.query(url)["share_code"]
            ?: UrlKit.query(url)["share_token"]
        var shareRecordId = UrlKit.query(url)["share_id"]
            ?: UrlKit.query(url)["share_record_id"]

        // 短链路径 /s/{code}/ 时跟随重定向取最终 URL 参数
        if (shareCode.isNullOrBlank()) {
            val shortRgx = Regex("/s/([a-zA-Z0-9_\\-]+)")
            shortRgx.find(runCatching { java.net.URI(url).path }.getOrNull().orEmpty())?.let {
                val resp = http.get(url, headers)
                if (resp != null) {
                    val q = UrlKit.query(resp.finalUrl)
                    shareCode = q["share_code"] ?: q["share_token"]
                    shareRecordId = q["share_id"] ?: q["share_record_id"]
                }
            }
        }

        if (shareCode.isNullOrBlank() && shareRecordId.isNullOrBlank()) {
            return failResponse("未能提取分享标识")
        }

        val payload = JsonObject()
        payload.addProperty("share_record_id", shareRecordId ?: "")
        payload.addProperty("from_h5", true)
        payload.addProperty("share_token", shareCode ?: "")
        val extra = JsonObject()
        extra.addProperty("aid", "564650")
        extra.addProperty("app_name", "星绘")
        payload.add("extra_params", extra)

        val resp = http.postJson(
            "https://www.butterflyai.cn/butterfly/community/v1/share/record/get?aid=564650",
            headers,
            payload.toString()
        ) ?: return failResponse("无法获取作品信息")

        val root = parseJsonObj(resp.body) ?: return failResponse("作品信息解析失败")
        val statusInfo = root.jObj("status_info")
        val statusCode = statusInfo?.jLong("status_code") ?: root.jLong("status_code") ?: 0L
        if (statusCode != 0L) {
            return failResponse(statusInfo?.jStr("status_msg") ?: root.jStr("message") ?: "接口返回错误")
        }

        val shareRecord = root.jObj("share_record") ?: return failResponse("作品数据为空")
        val artwork = shareRecord.jObj("artwork") ?: JsonObject()
        val creator = shareRecord.jObj("creator")

        val md = MediaData()
        md.inputUrl = input
        val firstEffect = shareRecord.jArr("effect_list")?.firstOrNull()?.asObjOrNull()
        val title = artwork.jStr("title")?.trim()
            ?: shareRecord.jObj("show_info")?.jStr("effect_title")?.trim()
            ?: firstEffect?.jStr("name")?.trim()
            ?: firstEffect?.jObj("show_info")?.jStr("effect_title")?.trim()
        md.title = if (title.isNullOrBlank()) "星绘AI 作品" else title
        md.resolvedUrl = resp.finalUrl

        creator?.jStr("screen_name")?.let {
            if (it.isNotBlank()) md.author = it
        }
        creator?.jStr("id")?.let { md.authorUid = it }

        // 视频：rendering_video 或 artwork.resource_list[*].video_resource.rendering_videos
        val renderingVideo = shareRecord.jObj("rendering_video")
        var videoUrl = renderingVideo?.firstStr("video_url", "download_url")
        artwork.jArr("resource_list")?.forEach { el ->
            val res = el.asObjOrNull() ?: return@forEach
            if (!videoUrl.isNullOrBlank()) return@forEach
            val vRes = res.jObj("video_resource")
            if (vRes != null) {
                vRes.jArr("rendering_videos")?.forEach { v ->
                    val item = v.asObjOrNull() ?: return@forEach
                    videoUrl = item.firstStr("video_url", "download_url")
                    if (!videoUrl.isNullOrBlank()) return@forEach
                }
            }
        }

        // 图片：resource_list 内 + share_record.rendering_images（优先无水印原画）
        val images = LinkedHashSet<String>()
        artwork.jArr("resource_list")?.forEach { el ->
            val res = el.asObjOrNull() ?: return@forEach
            val iRes = res.jObj("image_resource")
            if (iRes != null) {
                iRes.jArr("rendering_images")?.forEach { img ->
                    val item = img.asObjOrNull() ?: return@forEach
                    item.firstStr("no_wm_download_url", "download_url", "image_url")?.let {
                        if (it.isNotBlank()) images.add(it)
                    }
                }
            }
        }
        shareRecord.jArr("rendering_images")?.forEach { el ->
            val item = el.asObjOrNull() ?: return@forEach
            item.firstStr("no_wm_download_url", "download_url", "image_url")?.let {
                if (it.isNotBlank()) images.add(it)
            }
        }

        // 封面
        var cover = artwork.firstStr("cover_image_url", "cover_url")
        if (cover == null) cover = renderingVideo?.jStr("cover_image_url")

        when {
            !videoUrl.isNullOrBlank() -> {
                md.type = "video"
                md.playUrl = videoUrl
                md.rawPlayUrl = videoUrl
                md.addQuality(label = "默认", ratio = "default", url = videoUrl)
            }
            images.isNotEmpty() -> {
                md.type = "image"
                images.forEach { md.addImage(it) }
                if (cover != null) md.cover = cover
                if (md.cover == null) md.cover = images.first()
            }
            else -> return failResponse("无法提取媒体内容")
        }
        if (cover != null) md.cover = cover
        return md.toServerJson()
    }

    private companion object {
        const val TAG = "ButterflyaiParser"
    }
}