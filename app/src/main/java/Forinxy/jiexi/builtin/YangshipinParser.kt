package Forinxy.jiexi.builtin

import android.util.Log

/**
 * 央视频（Yangshipin）解析器：从页面内嵌 STATE JSON（横竖屏视频）提取
 * 元数据/作者/封面，支持 meta refresh 短链跳转。按 Python 参考实现移植。
 */
internal class YangshipinParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.YANG_SHIPIN

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://m.yangshipin.cn/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
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
            Log.w(TAG, "yangshipin parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        var url = input
        var page = fetch(url) ?: return failResponse("无法获取页面内容")
        var html = page

        // 兼容短链未重定向时页面内的 meta refresh 跳转
        val refresh = Regex("""<meta\s+http-equiv=["']refresh["']\s+content=["'][^;]+;\s*URL=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)
        if (refresh != null) {
            var redirect = refresh.groupValues[1].trim()
            if (redirect.startsWith("/")) redirect = "https://m.yangshipin.cn$redirect"
            url = redirect
            html = fetch(url) ?: return failResponse("无法获取页面内容")
        }

        var title: String? = null
        var cover: String? = null
        var authorName: String? = null

        // 1. 横屏视频 STATE
        extractStateJson(html, "window.__STATE_video__")?.let { sv ->
            val share = sv.jObj("payloads")?.jObj("sharevideo")
            if (share != null) {
                if (title == null) title = share.jStr("title")?.trim()
                if (cover == null) cover = share.jStr("cover_pic")
                if (authorName == null) authorName = share.jObj("om_info")?.jStr("title")
            }
        }

        // 2. 竖屏视频 STATE
        extractStateJson(html, "window.__STATE_portrait_video__")?.let { dp ->
            val items = dp.jObj("payloads")?.jObj("videoDataList")?.jArr("items")
            val vd = items?.firstOrNull()?.asObjOrNull()?.jObj("videoData")
            if (vd != null) {
                if (title == null) title = vd.jStr("title")?.trim()
                if (cover == null) {
                    cover = vd.jObj("shareItem")?.jStr("shareImgUrl")
                        ?: vd.jObj("poster")?.jObj("poster")?.jStr("imageUrl")
                }
                if (authorName == null) {
                    val actor = vd.jObj("detailFollowItem")?.jObj("actorItem")
                    if (actor != null) {
                        val nick = actor.jObj("nickName")?.jStr("text")
                        if (nick != null) authorName = nick
                    }
                }
            }
        }

        // 3. 兜底：OpenGraph
        if (title.isNullOrBlank()) title = metaContent(html, "og:title")
        if (title.isNullOrBlank()) {
            val t = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)
            title = t?.groupValues?.get(1)?.trim()
        }
        if (cover.isNullOrBlank()) cover = metaContent(html, "og:image")

        title = title?.replace(Regex("<[^>]+>"), "")?.trim()
        if (title.isNullOrBlank()) return failResponse("无法提取作品信息")

        val md = MediaData()
        md.inputUrl = input
        md.title = title
        md.type = "image"
        md.author = authorName?.let { if (it.isBlank()) "未知作者" else it } ?: "未知作者"
        md.cover = cover
        if (!cover.isNullOrBlank()) {
            md.addImage(cover)
        }
        // 仅封面图集兜底；央视频通常为带锁流视频地址，此处输出封面供展示
        return md.toServerJson()
    }

    private fun fetch(url: String): String? {
        val resp = http.get(url, headers) ?: return null
        if (resp.statusCode != 200 || resp.body.isBlank()) return null
        return resp.body
    }

    /** 从页面提取指定 STATE 前缀后的平衡 JSON 对象 */
    private fun extractStateJson(html: String, key: String): com.google.gson.JsonObject? {
        val idx = html.indexOf(key)
        if (idx < 0) return null
        val start = html.indexOf("{", idx)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return parseJsonObj(html.substring(start, i + 1))
                    }
                }
            }
        }
        return null
    }

    private fun metaContent(html: String, property: String): String? {
        val rgx = Regex(
            """<meta[^>]*property=["']$property["'][^>]*content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)
        if (rgx != null) return rgx.groupValues[1]
        val rgx2 = Regex(
            """<meta[^>]*content=["']([^"']+)["'][^>]*property=["']$property["']""",
            RegexOption.IGNORE_CASE
        ).find(html)
        return rgx2?.groupValues?.get(1)
    }

    private companion object {
        const val TAG = "YangshipinParser"
    }
}