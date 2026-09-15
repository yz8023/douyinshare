package Forinxy.jiexi.builtin

import android.util.Log
import java.util.regex.Pattern

/**
 * 拼多多商品/评价秀分享解析器。参考实现的 feed_id 视频接口依赖 AntiSigner
 * (MiniRacer 执行 anti_content.js)，JVM 内无法移植，故本解析器仅实现：
 * 1. 链接中携带的实物图/分享素材 (_oak_share_url) 直接输出为高清图集；
 * 2. 商品页 SSR window.rawData → store.initDataObj.goods 提取标题/图集/视频。
 * 按 Python 参考实现移植（受限子集）。
 */
internal class PinduoduoParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.PINDUODUO

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://mobile.yangkeduo.com/",
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
            Log.w(TAG, "pinduoduo parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        val query = UrlKit.query(url)

        val goodsId = query["goods_id"]
        val reviewId = query["review_id"]
        val feedId = query["feed_id"]
        val rawOak = query["_oak_share_url"]

        val imageList = mutableListOf<String>()
        var coverUrl: String? = null
        var title: String? = null
        var videoUrl: String? = null

        // 1. 链接携带的分享实物图
        val oak = rawOak?.let { UrlKit.decode(it) }
        if (!oak.isNullOrBlank() && (oak.startsWith("http://") || oak.startsWith("https://"))) {
            imageList.add(oak)
            coverUrl = oak
            title = buildGoodsTitle(goodsId, reviewId)
        }

        // 2. 商品页面 SSR rawData 解析（外层已保证 videoUrl 为 null）
        if (videoUrl == null && imageList.isEmpty() && goodsId != null) {
            val resp = http.get(url, headers)
            if (resp != null && resp.statusCode == 200 && resp.body.isNotBlank()) {
                parseGoodsRawData(resp.body)?.let { goods ->
                    if (title == null) title = goods.title
                    goods.images.forEach { if (it !in imageList) imageList.add(it) }
                    videoUrl = goods.videoUrl
                    if (coverUrl == null) coverUrl = goods.coverUrl
                }
            }
        }

        // 3. 兜底标题
        if (title == null) {
            title = when {
                goodsId != null && reviewId != null ->
                    "拼多多评价分享 (商品ID: $goodsId, 评价ID: $reviewId)"
                goodsId != null -> "拼多多商品 (商品ID: $goodsId)"
                feedId != null -> "多多视频 (FeedID: $feedId)"
                else -> "拼多多分享"
            }
        }

        if (imageList.isEmpty() && videoUrl == null) return failResponse("无法提取作品信息")

        val md = MediaData()
        md.inputUrl = input
        md.resolvedUrl = url
        md.title = title
        md.author = "未知作者"
        when {
            videoUrl != null -> {
                md.type = "video"
                md.playUrl = videoUrl
                md.rawPlayUrl = videoUrl
                md.cover = coverUrl ?: imageList.firstOrNull()
                md.addQuality(label = "默认", ratio = "default", url = videoUrl)
            }
            else -> {
                md.type = "image"
                md.cover = coverUrl ?: imageList.first()
                imageList.forEach { md.addImage(it) }
            }
        }
        return md.toServerJson()
    }

    private data class Goods(
        val title: String?,
        val images: List<String>,
        val videoUrl: String?,
        val coverUrl: String?
    )

    private fun parseGoodsRawData(html: String): Goods? {
        val m = Pattern.compile(
            """window\.rawData\s*=\s*(\{.*?\});\s*</script>""",
            Pattern.DOTALL
        ).matcher(html)
        if (!m.find()) return null
        val rawData = parseJsonObj(m.group(1)) ?: return null

        val initData = rawData.jObj("store")?.jObj("initDataObj") ?: return null
        val goods = initData.jObj("goods") ?: return null

        var title: String? = null
        goods.jStr("goods_name")?.let { title = it }

        val images = mutableListOf<String>()
        val banner = goods.jArr("banner") ?: goods.jArr("gallery") ?: goods.jArr("top_gallery")
        banner?.forEach { item ->
            val u = when {
                item.isJsonPrimitive -> item.asStrOrNull()
                item.isJsonObject -> item.asJsonObject.jStr("url")
                else -> null
            }
            u?.takeIf { it.isNotBlank() }?.let {
                if (it !in images) images.add(it)
            }
        }

        var videoUrl: String? = null
        val video = goods.get("video")
        when {
            video?.isJsonObject == true -> virusUrl(video.asJsonObject.firstStr("url", "play_url"))?.let { videoUrl = it }
            video?.isJsonPrimitive == true -> video.asStrOrNull()?.let { videoUrl = it }
        }

        var coverUrl: String? = null
        goods.jStr("hd_thumb_url")?.let { coverUrl = it }
        if (coverUrl == null) goods.jStr("thumb_url")?.let { coverUrl = it }

        if (title == null && images.isEmpty() && videoUrl == null && coverUrl == null) return null
        return Goods(title, images, videoUrl, coverUrl)
    }

    private fun virusUrl(v: String?): String? {
        if (v.isNullOrBlank()) return null
        val cleaned = v.replace("\\u002F", "/").replace("\\/", "/")
        return if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
    }

    private fun buildGoodsTitle(goodsId: String?, reviewId: String?): String? {
        return when {
            goodsId != null && reviewId != null ->
                "拼多多评价分享 (商品ID: $goodsId, 评价ID: $reviewId)"
            goodsId != null -> "拼多多商品 (商品ID: $goodsId)"
            else -> null
        }
    }

    private companion object {
        const val TAG = "PinduoduoParser"
    }
}