package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement

/**
 * 可灵AI 分享作品解析器：通过公开分享接口拉取作品详情。
 * 按 Python 参考实现移植。
 */
internal class KlingParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.KLING

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh",
        "Referer" to "https://klingai-share.kuaishou.com/"
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
            Log.w(TAG, "kling parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val (creativeId, creativeType) = extractCreative(input)
        if (creativeId.isBlank()) return failResponse("无法提取作品 ID")

        val resp = http.get(
            UrlKit.build(
                DETAIL_API,
                mapOf("creativeId" to creativeId, "creativeType" to creativeType)
            ),
            headers
        ) ?: return failResponse("无法获取作品信息")

        val root = parseJsonObj(resp.body) ?: return failResponse("作品信息解析失败")
        val success = root.jLong("status") == 200L && root.jLong("result") == 1L
        val data = root.jObj("data")
        if (!success || data == null) {
            return failResponse("作品不存在或已删除")
        }

        val playUrl = resourceUrl(data.jObj("resource"))
        if (playUrl.isNullOrBlank()) return failResponse("无法提取视频地址")

        val md = MediaData()
        md.inputUrl = input
        md.type = "video"
        md.videoId = creativeId
        md.title = data.jStr("introduction") ?: "可灵AI 作品"
        md.playUrl = playUrl
        md.rawPlayUrl = playUrl
        md.cover = resourceUrl(data.jObj("cover")) ?: resourceUrl(data.jObj("firstFrame"))
        md.resolvedUrl = resp.finalUrl
        md.addQuality(label = "默认", ratio = "default", url = playUrl)

        val profile = data.jObj("userProfile")
        if (profile != null) {
            md.author = profile.jStr("userName")?.ifEmpty { null } ?: "未知作者"
            profile.jStr("userId")?.let { md.authorUid = it }
        }
        return md.toServerJson()
    }

    /** 从 URL query（creative_id/work_id）或纯数字中提取作品 ID 与类型 */
    private fun extractCreative(text: String): Pair<String, String> {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            val query = UrlKit.query(trimmed)
            val id = query["creative_id"] ?: query["work_id"]
            if (!id.isNullOrBlank()) {
                return id to (query["creative_type"] ?: "WORK")
            }
        }
        if (Regex("^\\d+$").matches(trimmed)) return trimmed to "WORK"
        return "" to "WORK"
    }

    /** 取出 「{resource: 'url'}」 中的 url，并正序列化反斜杠 */
    private fun resourceUrl(obj: JsonElement?): String? {
        val o = obj?.asObjOrNull() ?: return null
        val url = o.jRawStr("resource") ?: return null
        return url.replace("\\/", "/")
    }

    private companion object {
        const val TAG = "KlingParser"
        const val DETAIL_API = "https://klingai-share.kuaishou.com/app/creatives/query"
    }
}