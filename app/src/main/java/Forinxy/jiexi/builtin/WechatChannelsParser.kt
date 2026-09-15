package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.util.Random

/**
 * 微信视频号 解析器：提取分享链接中的 shortUri，匿名调用官方
 * finder-preview feed 接口获取视频/图集/背景音乐。受登录态限制的作品
 * 返回接口错误提示。按 Python 参考实现移植（匿名路径）。
 */
internal class WechatChannelsParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.WECHAT_CHANNELS

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
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
            Log.w(TAG, "wechat channels parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val shortUri = extractShortUri(input)
            ?: return failResponse("链接不是可识别的视频号分享链接")

        val requestId = String.format("%08x", random.nextInt(0x10000000))
        val pageUrl = UrlKit.encode(SPH_PAGE)
        val resp = http.post(
            "https://channels.weixin.qq.com$FEED_INFO_API?_rid=$requestId&_pageUrl=$pageUrl",
            mapOf(
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                "Content-Type" to "application/json",
                "Origin" to "https://channels.weixin.qq.com",
                "Referer" to "$SPH_PAGE?id=${UrlKit.encode(shortUri)}",
                "User-Agent" to USER_AGENT
            ),
            """{"baseReq":{"generalToken":""},"shortUri":"$shortUri"}"""
        ) ?: return failResponse("无法获取作品信息")

        val root = parseJsonObj(resp.body)
            ?: return failResponse("作品信息解析失败")
        val errCode = root.jLong("errCode")
        if (errCode != null && errCode != 0L) {
            return failResponse(root.jStr("errMsg")?.ifBlank { null } ?: "视频号接口返回错误")
        }

        val data = root.jObj("data") ?: return failResponse("作品数据为空")
        val feed = data.jObj("feedInfo") ?: return failResponse("作品数据为空")
        val author = data.jObj("authorInfo")

        val videoUrl = feed.jStr("videoUrl")
            ?: feed.jObj("h264VideoInfo")?.jStr("videoUrl")
            ?: feed.jObj("h265VideoInfo")?.jStr("videoUrl")

        val imageList = mutableListOf<String>()
        feed.jArr("picInfo")?.forEach { el ->
            val item = el.asObjOrNull() ?: return@forEach
            item.jStr("url")?.let { if (it.isNotBlank()) imageList.add(it) }
        }
        val audioUrl = feed.jObj("bgmInfo")?.jStr("bgmUrl")

        val md = MediaData()
        md.inputUrl = input
        md.title = feed.jStr("title")?.ifBlank { null }
            ?: feed.jStr("description")?.ifBlank { null }
            ?: "视频号作品"
        md.resolvedUrl = resp.finalUrl
        md.author = author?.jStr("nickname")?.ifBlank { null }
            ?: ""
        md.authorUid = author?.jStr("id")
        md.cover = feed.jStr("coverUrl")

        when {
            !videoUrl.isNullOrBlank() -> {
                md.type = "video"
                md.playUrl = videoUrl
                md.rawPlayUrl = videoUrl
                md.addQuality(label = "默认", ratio = "default", url = videoUrl)
            }
            audioUrl != null && audioUrl.isNotBlank() && imageList.isEmpty() -> {
                md.type = "music"
                md.playUrl = audioUrl
                md.addQuality(label = "默认", ratio = "default", url = audioUrl)
            }
            imageList.isNotEmpty() -> {
                md.type = "image"
                imageList.forEach { md.addImage(it) }
            }
            else -> return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    /** 提取视频号 shortUri：优先 /sph/ 路径，其次 query 的 id，最后跟随重定向取二十位短码 */
    private fun extractShortUri(input: String): String? {
        val url = input.trim()
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        val sphRgx = Regex("(?:^|/)sph/([A-Za-z0-9]+)")
        sphRgx.find(path)?.let { return it.groupValues[1] }

        val idFromQuery = UrlKit.query(url)["id"]
        if (!idFromQuery.isNullOrBlank()) return idFromQuery

        val resp = http.get(url, headers)
            ?: return null
        val finalId = UrlKit.query(resp.finalUrl)["id"]
        if (!finalId.isNullOrBlank()) return finalId
        return null
    }

    private companion object {
        const val TAG = "WechatChannelsParser"
        const val FEED_INFO_API = "/finder-preview/api/feed/get_feed_info"
        const val SPH_PAGE = "https://channels.weixin.qq.com/finder-preview/pages/sph"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val random = Random()
    }
}