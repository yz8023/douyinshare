package Forinxy.jiexi.builtin

import android.util.Log
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 快影（Kwaiying）模板/作品分享解析器：通过快影 OpenAPI 获取模板媒体。
 * 签名算法按 Python 参考实现移植。
 */
internal class KwaiyingParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.KWAIYING

    private val headers = mapOf(
        "User-Agent" to MOBILE_UA,
        "Origin" to "https://share.kwaiying.com",
        "Referer" to "https://share.kwaiying.com/"
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
            Log.w(TAG, "kwaiying parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val templateId = extractTemplateId(input)
        if (templateId.isBlank()) return failResponse("无法提取模板 ID")

        val sign = generateSign()
        val params = LinkedHashMap<String, String>().apply {
            putAll(sign)
            put("templateId", templateId)
        }
        val resp = http.get(UrlKit.build(DETAIL_API, params), headers)
            ?: return failResponse("无法获取模板信息")
        val root = parseJsonObj(resp.body) ?: return failResponse("模板信息解析失败")
        val resource = root.jObj("resource")
        if (root.jLong("result") != 1L || resource == null) {
            return failResponse("模板不存在或已删除")
        }

        val videoUrl = resource.jStr("videoUrl")
        if (videoUrl.isNullOrBlank()) return failResponse("无法提取视频地址")

        val md = MediaData()
        md.inputUrl = input
        md.type = "video"
        md.videoId = templateId
        md.title = resource.jStr("name") ?: "快影模板"
        md.playUrl = videoUrl
        md.rawPlayUrl = videoUrl
        md.resolvedUrl = resp.finalUrl

        val bean = resource.jObj("templateBean")
        md.cover = bean?.jStr("coverUrl") ?: bean?.jStr("coverWebPUrl")

        val user = resource.jObj("user")
        md.author = user?.jStr("nickName")?.ifEmpty { null } ?: "未知作者"
        user?.jStr("userId")?.let { md.authorUid = it }

        md.addQuality(label = "默认", ratio = "default", url = videoUrl)
        return md.toServerJson()
    }

    private fun extractTemplateId(url: String?): String {
        val query = UrlKit.query(url)
        val id = query["id"] ?: query["templateId"]
        if (!id.isNullOrBlank()) return id
        val m = Regex("(?:id|templateId|template_id)=(\\d+)").find(url.orEmpty())
        return m?.groupValues?.get(1).orEmpty()
    }

    /** 生成快影 API 签名参数 */
    private fun generateSign(): Map<String, String> {
        val nowMs = System.currentTimeMillis()
        val nonce = Random.nextLong(100_000_000_000L, 1_000_000_000_000L)
        val hexStr = SIGN_KEY.map { Integer.toHexString(it.code) }.joinToString("")
        val digitsOnly = hexStr.filter { it.isDigit() }.take(16)
        val a = digitsOnly.toLongOrNull() ?: 0L
        val s = (a xor nowMs) or a
        val sign = md5Hex((nonce xor s).toString())
        return mapOf("timestamp" to nowMs.toString(), "nonce" to nonce.toString(), "sign" to sign)
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "KwaiyingParser"
        const val DETAIL_API = "https://api.kmovie.gifshow.com/rest/n/kmovie/app/resource/getTemplateById"
        const val SIGN_KEY = "yiuhjkbvhbjisjchgdnx38uejd"
        const val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    }
}