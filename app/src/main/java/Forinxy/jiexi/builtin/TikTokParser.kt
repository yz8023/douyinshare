package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * TikTok（国际版抖音）解析器：网页端 __UNIVERSAL_DATA_FOR_REHYDRATION__ 数据节点方案。
 *
 * 流程：跟随短链/分享链重定向（手动跟跳以收集各跳 Set-Cookie）→ 请求作品页
 * → 提取 itemStruct → bitrateInfo 组装画质列表 / imagePost 组装图集。
 *
 * 关键点：TikTok CDN 对 playAddr 的签名与 Cookie 里的 tt_chain_token 绑定，
 * 下载/预览必须复用解析时的 UA+Cookie 会话（见 [TikTokSessionStore]），
 * 所以这里解析成功后会把会话登记进去。匿名网页端能拿到最高码率一路流，
 * original/highest 参数无更多档位可选（忽略）。
 */
internal class TikTokParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.TIKTOK

    private val pageHeaders = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Mode" to "navigate"
    )

    /** 不跟随重定向的客户端：手动跟跳以收集每一跳的 Set-Cookie */
    private val noRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "tiktok parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val startUrl = TikTokPageData.extractFirstUrl(input)
            ?: return failResponse("无法识别 TikTok 链接")

        val page = fetchPage(startUrl)
            ?: return failResponse("视频不存在或已删除")

        val root = TikTokPageData.extractUniversalData(page.html)
        val item = root?.let { TikTokPageData.extractItemStruct(it) }
            ?: TikTokPageData.extractItemStructFromSigi(page.html)
            ?: return failResponse(
                if (TikTokPageData.isWafChallengePage(page.html)) {
                    // WAF JS 挑战壳页：OkHttp 过不去，需要 WebView 预热会话后重试
                    "TikTok 风控拦截，请稍后重试"
                } else {
                    "视频不存在或已删除"
                }
            )

        // 页面请求种下了新 Cookie 则刷新会话；否则保留已有（WebView 预热）会话
        if (page.cookieHeader.isNotBlank()) {
            TikTokSessionStore.register(PlatformHttp.PC_UA, page.cookieHeader)
        }

        val md = MediaData()
        md.inputUrl = input
        md.videoId = item.jStr("id")
            ?: TikTokPageData.extractMediaId(page.finalUrl).orEmpty()
        md.author = item.jObj("author")?.jStr("nickname") ?: "未知作者"
        md.authorUid = item.jObj("author")?.jStr("uniqueId")
        md.title = item.jStr("desc") ?: "无标题"
        item.jLong("createTime")?.let { md.timestamp = it }
        md.resolvedUrl = page.finalUrl

        val imagePost = item.jObj("imagePost")
        val images = imagePost?.jArrOrEmpty("images")
        if (images != null && images.size() > 0) {
            md.type = "image"
            for (element in images) {
                val image = element.asObjOrNull() ?: continue
                val url = image.jObj("imageURL")?.jArrOrEmpty("urlList")
                    ?.firstOrNull()?.asStrOrNull() ?: continue
                if (url.isNotBlank()) md.addImage(url)
            }
            if (!md.hasGallery) return failResponse("无法获取图片地址")
            md.cover = md.gallery.firstOrNull()?.imageUrl
            return md.toServerJson()
        }

        val video = item.jObj("video")
            ?: return failResponse("无法获取视频数据")
        md.type = "video"
        video.jLong("duration")?.let { md.duration = it.toDouble() }
        md.cover = coverUrlOf(video)

        val playUrl = fillVideoQualities(md, video)
            ?: return failResponse("无法获取播放地址")
        md.playUrl = playUrl
        md.rawPlayUrl = playUrl

        return md.toServerJson()
    }

    /** 从 bitrateInfo 组装画质列表，返回最高码率地址 */
    private fun fillVideoQualities(md: MediaData, video: JsonObject): String? {
        var bestUrl: String? = null
        var bestBitrate = -1L
        for (element in video.jArrOrEmpty("bitrateInfo")) {
            val entry = element.asObjOrNull() ?: continue
            val play = entry.jObj("PlayAddr") ?: continue
            val url = play.jArrOrEmpty("UrlList").firstOrNull()?.asStrOrNull()
                ?: play.jStr("Url")
            if (url.isNullOrBlank()) continue
            val bitrate = entry.jLong("Bitrate") ?: 0L
            val height = play.jLong("Height")?.toInt()
            md.addQuality(
                label = TikTokPageData.qualityLabel(height),
                ratio = "default",
                url = url,
                sizeBytes = play.jLong("DataSize"),
                bitRate = bitrate
            )
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        if (bestUrl != null) return bestUrl
        // 兜底：无 bitrateInfo 时直接取 playAddr / PlayAddrStruct
        val playAddr = video.jStr("playAddr")
        if (!playAddr.isNullOrBlank()) {
            md.addQuality(label = "高清", ratio = "default", url = playAddr)
            return playAddr
        }
        val structUrl = video.jObj("PlayAddrStruct")?.jArrOrEmpty("UrlList")
            ?.firstOrNull()?.asStrOrNull()
        if (!structUrl.isNullOrBlank()) {
            md.addQuality(label = "高清", ratio = "default", url = structUrl)
            return structUrl
        }
        return null
    }

    /** cover 字段兼容两种结构：字符串 URL 或 {urlList:[...]} */
    private fun coverUrlOf(video: JsonObject): String? {
        for (key in listOf("cover", "originCover", "dynamicCover")) {
            val el = video.get(key) ?: continue
            if (el.isJsonPrimitive) {
                val text = el.asString
                if (text.isNotBlank()) return text
            } else if (el.isJsonObject) {
                for (u in el.asJsonObject.jArrOrEmpty("urlList")) {
                    val text = u.asStrOrNull()
                    if (!text.isNullOrBlank()) return text
                }
            }
        }
        return null
    }

    // ========== 手动重定向抓取 ==========

    private data class PageData(
        val html: String,
        val finalUrl: String,
        val cookieHeader: String
    )

    private fun fetchPage(startUrl: String): PageData? {
        var current = startUrl
        val jar = LinkedHashMap<String, String>()
        // 预热会话（WebView 收割的 WAF 放行 Cookie）作为初始 Cookie
        TikTokSessionStore.currentCookieHeader().takeIf { it.isNotBlank() }?.let { stored ->
            for (pair in stored.split(";")) {
                TikTokPageData.parseSetCookie(pair)?.let { (name, value) -> jar[name] = value }
            }
        }
        var hops = 0
        while (hops < MAX_REDIRECT_HOPS) {
            hops++
            val headers = LinkedHashMap(pageHeaders)
            if (jar.isNotEmpty()) {
                headers["Cookie"] = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            val resp = fetch(current, headers) ?: return null
            resp.headers["set-cookie"]?.forEach { raw ->
                TikTokPageData.parseSetCookie(raw)?.let { (name, value) -> jar[name] = value }
            }
            when (resp.statusCode) {
                301, 302, 303, 307, 308 -> {
                    val location = resp.header("location") ?: return null
                    current = resolveRedirect(resp.finalUrl, location) ?: return null
                }
                else -> {
                    if (resp.statusCode != 200 || resp.body.isBlank()) return null
                    val cookieHeader = if (jar.isEmpty()) {
                        ""
                    } else {
                        jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    }
                    return PageData(resp.body, resp.finalUrl, cookieHeader)
                }
            }
        }
        return null
    }

    private fun fetch(url: String, headers: Map<String, String>): HttpResult? {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }
            noRedirectClient.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val headerMap = LinkedHashMap<String, List<String>>()
                for ((name, values) in response.headers) {
                    if (name.isBlank()) continue
                    val key = name.lowercase()
                    headerMap[key] = (headerMap[key] ?: emptyList()) + values
                }
                HttpResult(body, response.request.url.toString(), response.code, headerMap)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "GET $url failed", e)
            null
        }
    }

    private fun resolveRedirect(base: String, location: String): String? =
        runCatching { URI(base).resolve(location).toString() }.getOrNull()

    private companion object {
        const val TAG = "TikTokParser"
        const val MAX_REDIRECT_HOPS = 6
    }
}
