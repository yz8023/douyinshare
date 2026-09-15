package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement

/**
 * 通义千问（Qwen）分享解析器：优先调用官方 chat2-api 接口（SPA 分享页），
 * 未命中时回退解析 activity.qianwen.com 等 AI Studio 页面的
 * window.__INITIAL_PROPS__ JSON 或 OpenGraph Meta。按 Python 参考实现移植。
 */
internal open class QianwenParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.QIANWEN

    private val headers = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
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
            Log.w(TAG, "qianwen parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val url = input.trim()
        var shareId = UrlKit.query(url)["share_id"] ?: UrlKit.query(url)["shareId"]
        if (shareId.isNullOrBlank()) {
            val m = Regex("/share/chat/([a-zA-Z0-9_-]+)").find(url)
            shareId = m?.groupValues?.get(1)
        }
        val bizId = UrlKit.query(url)["biz_id"] ?: UrlKit.query(url)["bizId"] ?: "ai_qwen"

        var title: String? = null
        var imageList = listOf<String>()
        var videoList = listOf<String>()
        var authorName: String? = null

        // 1. 官方 chat2-api
        if (!shareId.isNullOrBlank()) {
            val apiUrl = "https://chat2-api.qianwen.com/api/v1/share/info?pr=qwen&fr=mac"
            val payload = """{"share_id":"$shareId","biz_id":"$bizId"}"""
            val resp = http.postJson(
                apiUrl,
                mapOf("User-Agent" to PlatformHttp.MOBILE_UA, "Content-Type" to "application/json"),
                payload
            )
            if (resp != null && resp.statusCode == 200) {
                val apiJson = parseJsonObj(resp.body)
                if (apiJson != null && apiJson.jLong("code") == 0L) {
                    val data = apiJson.jObj("data")
                    if (data != null && (data.jObj("session") != null || data.jStr("title") != null)) {
                        val media = extractData(data)
                        title = media.title
                        imageList = media.images
                        videoList = media.videos
                        authorName = media.author
                        if (imageList.isNotEmpty() || videoList.isNotEmpty()) {
                            return buildResponse(input, url, title, imageList, videoList, authorName)
                        }
                    }
                }
            }
        }

        // 2. 回退 HTML SSR
        val resp = http.get(url, headers) ?: return failResponse("无法获取页面内容")
        if (resp.statusCode != 200 || resp.body.isBlank()) return failResponse("内容不存在或已删除")
        val html = resp.body

        val marker = "window.__INITIAL_PROPS__"
        val pos = html.indexOf(marker)
        if (pos < 0) {
            title = metaContent(html, "og:title")
            val cover = metaContent(html, "og:image")
            if (!cover.isNullOrBlank()) imageList = listOf(cover)
            if (title == null && imageList.isEmpty()) return failResponse("无法提取作品信息")
            return buildResponse(input, url, title, imageList, videoList, authorName)
        }

        var sub = html.substring(pos + marker.length).trimStart(' ', '=')
        val endIdx = sub.indexOf("</script>")
        if (endIdx >= 0) sub = sub.substring(0, endIdx).trimEnd().trimEnd(';')
        val data = parseJsonObj(sub) ?: return failResponse("页面数据解析失败")

        val rawInitial = data.get("initialData")
        var initialData: JsonElement = rawInitial ?: data
        if (rawInitial?.isJsonPrimitive == true) {
            val rawStr = rawInitial.asString
            val decoded = if (rawStr.startsWith("%")) runCatching { java.net.URLDecoder.decode(rawStr, "UTF-8") }.getOrDefault(rawStr) else rawStr
            initialData = parseJsonObj(decoded) ?: parseJsonArr(decoded) ?: rawInitial
        }
        var initial = initialData
        if (initial.isJsonObject) {
            val d = initial.asJsonObject.jObj("data")
            if (d != null) initial = d
        }

        val media = extractData(initial)
        return buildResponse(input, url, media.title, media.images, media.videos, media.author)
    }

    protected data class Extracted(
        val title: String?,
        val images: List<String>,
        val videos: List<String>,
        val author: String?
    )

    protected fun extractData(data: JsonElement?): Extracted {
        if (data == null || data.isJsonNull) return Extracted(null, emptyList(), emptyList(), null)
        val obj = if (data.isJsonObject) data.asJsonObject else data.asJsonArray.firstOrNull()?.asObjOrNull()
        if (obj == null) return Extracted(null, emptyList(), emptyList(), null)

        var title: String? = obj.jStr("title")
            ?: obj.jStr("shareSubtitle")
            ?: obj.jStr("shareTitle")
        val session = obj.jObj("session")
        if (title == null && session != null) {
            title = session.jStr("title")
            if (title == null) {
                val rec0 = session.jArr("record_list")?.firstOrNull()?.asObjOrNull()
                if (rec0 != null) {
                    val query = rec0.get("query")
                    title = when {
                        query?.isJsonPrimitive == true -> query.asStrOrNull()
                        query?.isJsonObject == true -> query.asJsonObject.firstStr("content", "text")
                        else -> rec0.jArr("request_messages")?.firstOrNull()?.asObjOrNull()?.jStr("content")
                    }
                }
            }
        }

        var authorName: String? = null
        val creator = obj.jObj("creator") ?: obj.jObj("content")?.jObj("creator")
        if (creator != null && creator.entrySet().isNotEmpty()) {
            authorName = creator.firstStr("nick")
        }

        val images = mutableListOf<String>()
        val rawImages = obj.jArr("images")
        if (rawImages != null) {
            for (el in rawImages) {
                if (el.isJsonPrimitive) el.asStrOrNull()?.let { images.add(it) }
                else el.asObjOrNull()?.firstStr("url", "downloadUrl")?.let { images.add(it) }
            }
        } else if (obj.jStr("image") != null) {
            images.add(obj.jStr("image")!!)
        }

        val videos = mutableListOf<String>()
        obj.jObj("playInfo")?.firstStr("url", "downloadUrl")?.let { videos.add(it) }

        val deep = deepExtract(obj)
        if (videos.isEmpty()) videos.addAll(deep.first)
        if (images.isEmpty()) images.addAll(deep.second)

        return Extracted(
            title,
            images.distinct(),
            videos.distinct(),
            authorName
        )
    }

    private fun deepExtract(data: JsonElement?): Pair<List<String>, List<String>> {
        val videos = mutableListOf<String>()
        val images = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        val isVideo = { u: String ->
            val clean = u.replace("\\u0026", "&")
            val path = runCatching { java.net.URI(clean).path }.getOrNull().orEmpty()
                .replace("\\u0026", "&").lowercase()
            path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m4v") ||
                path.endsWith(".webm") || "/video/" in path || ".mp4" in path
        }
        val isImage = { u: String ->
            val clean = u.replace("\\u0026", "&")
            val path = runCatching { java.net.URI(clean).path }.getOrNull().orEmpty().lowercase()
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                path.endsWith(".webp") || path.endsWith(".gif") || ".jpg" in path || ".png" in path
        }

        fun walk(node: JsonElement?) {
            if (node == null || node.isJsonNull) return
            if (node.isJsonObject) {
                for ((k, v) in node.asJsonObject.entrySet()) {
                    if (k in listOf("url", "downloadUrl", "playUrl") && v.isJsonPrimitive) {
                        val s = v.asStrOrNull()
                        if (s != null && s.startsWith("http")) {
                            val clean = s.replace("\\u0026", "&")
                            if (clean !in seen) {
                                seen.add(clean)
                                when {
                                    isVideo(clean) -> videos.add(clean)
                                    isImage(clean) -> images.add(clean)
                                }
                            }
                        }
                    } else walk(v)
                }
            } else if (node.isJsonArray) {
                for (el in node.asJsonArray) walk(el)
            }
        }
        walk(data)
        return videos to images
    }

    private fun buildResponse(
        input: String,
        resolvedUrl: String,
        title: String?,
        imageList: List<String>,
        videoList: List<String>,
        author: String?
    ): String {
        val md = MediaData()
        md.inputUrl = input
        md.title = title?.ifBlank { null } ?: "通义千问 分享"
        md.resolvedUrl = resolvedUrl
        md.author = author?.ifBlank { null } ?: "未知作者"

        when {
            videoList.isNotEmpty() -> {
                val primary = videoList.first()
                md.type = "video"
                md.playUrl = primary
                md.rawPlayUrl = primary
                md.cover = imageList.firstOrNull()
                md.addQuality(label = "默认", ratio = "default", url = primary)
            }
            imageList.isNotEmpty() -> {
                md.type = "image"
                md.cover = imageList.first()
                imageList.forEach { md.addImage(it) }
            }
            else -> return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
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
        const val TAG = "QianwenParser"
    }
}