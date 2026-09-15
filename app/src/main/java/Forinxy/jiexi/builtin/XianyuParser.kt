package Forinxy.jiexi.builtin

import android.util.Log
import java.util.regex.Pattern

/**
 * 闲鱼 / 淘宝短链解析器：提取商品 ID、标价与真实目标链接。
 *
 * 分享链接本身多为商品详情页，通常没有可下载媒体。若目标链接是图片
 * CDN 地址（如 alicdn），按图集输出；否则返回明确提示。
 */
internal class XianyuParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.XIANYU

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://e.tb.cn/"
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
            Log.w(TAG, "xianyu parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val resp = http.get(input.trim(), headers)
            ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")

        val html = resp.body
        var targetUrl = extractVarUrl(html)
        var query = UrlKit.query(targetUrl)

        var itemId = query["id"]
        val price = query["price"]

        if (itemId.isNullOrBlank()) {
            query = UrlKit.query(input)
            itemId = query["id"]
        }
        if (itemId.isNullOrBlank()) return failResponse("无法提取商品 ID")

        val title = if (!price.isNullOrBlank()) {
            "闲鱼商品 (商品ID: $itemId, 标价: ¥$price)"
        } else {
            "闲鱼商品 (商品ID: $itemId)"
        }

        // 目标链接为图片 CDN 时按图集输出，否则为纯商品页
        if (targetUrl != null && isImageUrl(targetUrl)) {
            val md = MediaData()
            md.inputUrl = input
            md.type = "image"
            md.title = title
            md.videoId = itemId
            md.cover = targetUrl
            md.resolvedUrl = targetUrl
            md.addImage(targetUrl)
            return md.toServerJson()
        }

        if (targetUrl != null) {
            val md = MediaData()
            md.inputUrl = input
            md.type = "image"
            md.title = title
            md.videoId = itemId
            md.resolvedUrl = targetUrl
            return md.toServerJson()
        }

        return failResponse("该链接为商品详情页，无可下载的媒体文件")
    }

    private fun extractVarUrl(html: String): String? {
        val m = Pattern.compile("var\\s+url\\s*=\\s*'([^']+)'").matcher(html)
        return if (m.find()) m.group(1) else null
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("alicdn.com") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif")
    }

    private companion object {
        const val TAG = "XianyuParser"
    }
}