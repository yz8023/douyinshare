package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement

/**
 * 即梦AI 视频/图片分享解析器：优先调用官方 mweb get_item_info 接口，
 * 其次尝试 campaign landing_page 接口，最后尝试 competition preview_video。
 * 按 Python 参考实现移植。
 */
internal class JimengParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.JIMENG

    private val apiUrl = "https://jimeng.jianying.com/mweb/v1/get_item_info"
    private val campaignApiUrl =
        "https://jimeng.jianying.com/luckycat/cn/jianying/campaign/v1/dreamina/share/landing_page"
    private val competitionApiUrl = "https://jimeng.jianying.com/competition/v1/preview_video"

    private val userAgent = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
        )

    private val headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "User-Agent" to userAgent
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
            Log.w(TAG, "jimeng parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private data class Data(
        var title: String?,
        var videoUrl: String?,
        var videoList: MutableList<String>,
        var coverUrl: String?,
        var authorName: String?,
        var imageList: MutableList<String>
    )

    private fun doParse(input: String): String {
        val url = input.trim()
        var targetUrl = url
        var itemId = extractItemId(targetUrl)

        // 短链 / 未提取到 ID：跟随重定向
        if (itemId == null || "/s/" in targetUrl) {
            val resp = http.get(targetUrl, mapOf("User-Agent" to userAgent))
            if (resp != null && resp.statusCode == 200) {
                targetUrl = resp.finalUrl.ifBlank { targetUrl }
                if (itemId == null) itemId = extractItemId(targetUrl)
                if (itemId == null) itemId = extractItemIdFromHtml(resp.body)
            }
        }

        val data = Data(null, null, mutableListOf(), null, null, mutableListOf())

        // 1. get_item_info
        if (itemId != null) {
            try {
                val resp = http.postJson(
                    apiUrl,
                    headers,
                    """{"published_item_id":"$itemId"}"""
                )
                if (resp != null && resp.statusCode == 200) {
                    val root = parseJsonObj(resp.body)
                    if (root != null && root.jStr("ret") == "0") {
                        root.jObj("data")?.let {
                            applyFormatted(data, formatData(it))
                        }
                        return buildResponse(input, targetUrl, data)
                    }
                    val errmsg = root?.jStr("errmsg").orEmpty()
                    if ("itemId not exist" in errmsg || root?.jStr("ret") in listOf("2032", "1000")) {
                        if (tryParseCampaignApi(targetUrl, itemId, data)) {
                            return buildResponse(input, targetUrl, data)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "jimeng get_item_info failed", e)
            }
        } else {
            // 3. competition preview_video 兜底：从 URL 提取 video_id
            val videoId = extractVideoId(targetUrl) ?: itemId
            // 若无 item_id 且无 video_id，尝试 campaign
            val secUid = secUidOf(targetUrl)
            if (secUid != null) {
                if (tryParseCampaignApi(targetUrl, null, data)) {
                    return buildResponse(input, targetUrl, data)
                }
            }
            if (videoId != null) {
                val preview = fetchCompetitionPreview(videoId)
                if (preview != null) {
                    val formatted = formatCompetitionPreviewData(preview)
                    applyFormatted(data, formatted)
                    return buildResponse(input, targetUrl, data)
                }
            }
        }

        // 2. campaign 接口（回流/草稿链接）
        if (itemId != null && (targetUrl.contains("reflux") || targetUrl.contains("mproject") ||
                targetUrl.contains("share_token"))
        ) {
            if (tryParseCampaignApi(targetUrl, itemId, data)) {
                return buildResponse(input, targetUrl, data)
            }
        }

        if (data.videoUrl == null && data.imageList.isEmpty()) {
            return failResponse("无法提取作品信息")
        }
        return buildResponse(input, targetUrl, data)
    }

    private data class Formatted(
        val title: String?,
        val videoUrl: String?,
        val videoList: List<String>,
        val coverUrl: String?,
        val authorName: String?,
        val imageList: List<String>
    )

    private fun applyFormatted(data: Data, fmt: Formatted) {
        if (data.title == null) data.title = fmt.title
        if (data.videoUrl == null) data.videoUrl = fmt.videoUrl
        data.videoList.addAll(fmt.videoList)
        if (data.coverUrl == null) data.coverUrl = fmt.coverUrl
        if (data.authorName == null) data.authorName = fmt.authorName
        data.imageList.addAll(fmt.imageList)
    }

    private fun buildResponse(input: String, resolvedUrl: String, data: Data): String {
        val md = MediaData()
        md.inputUrl = input
        md.title = data.title ?: "即梦AI 分享"
        md.resolvedUrl = resolvedUrl
        md.author = data.authorName ?: "未知作者"

        val uniqueVideos = LinkedHashSet(data.videoList).toList()
        val uniqueImages = LinkedHashSet(data.imageList).toList()

        when {
            data.videoUrl != null || uniqueVideos.isNotEmpty() -> {
                val primary = data.videoUrl ?: uniqueVideos.firstOrNull()
                md.type = "video"
                if (primary != null) {
                    md.playUrl = primary
                    md.rawPlayUrl = primary
                    md.cover = data.coverUrl
                    md.addQuality(label = "默认", ratio = "default", url = primary)
                    uniqueVideos.filter { it != primary }.forEach { md.addQuality(label = "清晰", ratio = "default", url = it) }
                }
            }
            uniqueImages.isNotEmpty() -> {
                md.type = "image"
                md.cover = uniqueImages.first()
                uniqueImages.forEach { md.addImage(it) }
            }
            else -> return failResponse("无法提取媒体内容")
        }
        return md.toServerJson()
    }

    private fun formatData(detail: JsonElement): Formatted {
        val d = detail.asObjOrNull()
        val common = d?.jObj("common_attr")
        val author = d?.jObj("author")
        val video = d?.jObj("video")
        val originVideo = video?.jObj("origin_video")?.asObjOrNull()
        val transcoded = video?.jObj("transcoded_video")?.asObjOrNull()

        var primary: String? = null
        transcoded?.jObj("origin")?.asObjOrNull()?.jStr("video_url")?.let { primary = it }
        if (primary == null) primary = originVideo?.jStr("video_url")
        if (primary == null) primary = bestTranscodedUrl(transcoded)

        var coverUrl: String? = null
        common?.jObj("cover_url_map")?.let { map ->
            for (q in listOf("4096", "2400", "1080", "720", "480", "360", "original")) {
                map.jStr(q)?.let { coverUrl = it }
                if (coverUrl != null) break
            }
            if (coverUrl == null) {
                for ((_, v) in map.entrySet()) {
                    v.asStrOrNull()?.let { coverUrl = it }
                    if (coverUrl != null) break
                }
            }
        }
        if (coverUrl == null) coverUrl = common?.jStr("cover_url") ?: video?.jStr("cover_url")

        // 图片
        val imageList = mutableListOf<String>()
        val rawImages = d?.jArr("image_infos") ?: d?.jArr("image_list") ?: d?.jArr("images")
        if (rawImages != null) {
            for (img in rawImages) {
                when {
                    img.isJsonPrimitive -> img.asStrOrNull()?.takeIf { it.startsWith("http") }?.let { imageList.add(it) }
                    img.isJsonObject -> {
                        val obj = img.asJsonObject
                        val urlMap = obj.jObj("image_url_map") ?: obj.jObj("cover_url_map")
                        var imgUrl: String? = null
                        if (urlMap != null) {
                            for (q in listOf("original", "4096", "2400", "1080", "720")) {
                                urlMap.jStr(q)?.let { imgUrl = it }
                                if (imgUrl != null) break
                            }
                            if (imgUrl == null) {
                                for ((_, v) in urlMap.entrySet()) {
                                    v.asStrOrNull()?.let { imgUrl = it }
                                    if (imgUrl != null) break
                                }
                            }
                        }
                        if (imgUrl == null) imgUrl = obj.firstStr("image_url", "url", "origin_url", "main_url")
                        imgUrl?.let { imageList.add(it) }
                    }
                }
            }
        }
        val dedupImages = LinkedHashSet(imageList).toList()
        if (primary == null && dedupImages.isEmpty() && coverUrl != null) {
            dedupImages + listOf(coverUrl)
        }

        primary = sanitizeVideoUrl(primary)
        val videoList = mutableListOf<String>()
        if (primary != null) videoList.add(primary)
        if (transcoded != null) {
            for ((_, item) in transcoded.entrySet()) {
                val v = item.asObjOrNull()?.jStr("video_url") ?: continue
                val u = sanitizeVideoUrl(v) ?: continue
                if (u !in videoList) videoList.add(u)
            }
        }

        val authorId = author?.jStr("uid") ?: author?.jStr("sec_uid") ?: ""
        return Formatted(
            common?.jStr("title"),
            primary,
            videoList,
            coverUrl,
            author?.jStr("name"),
            dedupImages
        )
    }

    private fun tryParseCampaignApi(url: String, itemId: String?, data: Data): Boolean {
        return try {
            val queryParams = UrlKit.query(url)
            val secUid = queryParams["share_sec_uid"] ?: queryParams["author_id"]

            val headers2 = headers + mapOf("appid" to "581595")
            var payload = mutableMapOf<String, String>()
            queryParams.forEach { (k, v) -> if (v.isNotBlank()) payload[k] = v }
            if (itemId != null) payload["item_id"] = itemId
            if (secUid != null) payload["sec_uid"] = secUid

            var res = http.postJson(
                campaignApiUrl,
                headers2,
                payloadJson(payload)
            )
            if (res == null) return false
            var resJson = parseJsonObj(res.body) ?: return false

            if (resJson.jLong("err_no") != 0L || resJson.jObj("data") == null) {
                if (payload.containsKey("item_id") && queryParams.isNotEmpty()) {
                    payload.remove("item_id")
                    res = http.postJson(campaignApiUrl, headers2, payloadJson(payload)) ?: return false
                    resJson = parseJsonObj(res.body) ?: return false
                }
            }

            if (resJson.jLong("err_no") == 0L && resJson.jObj("data") != null) {
                val formatted = formatCampaignData(resJson.jObj("data")!!)
                if (formatted.videoUrl != null || formatted.imageList.isNotEmpty()) {
                    applyFormatted(data, formatted)
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            Log.w(TAG, "jimeng campaign failed", e)
            false
        }
    }

    private fun payloadJson(map: Map<String, String>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":\"").append(v.replace("\"", "\\\"")).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun formatCampaignData(data: JsonElement): Formatted {
        val d = data.asObjOrNull()
        val pageInfo = d?.jObj("page_info")
        val creation = pageInfo?.jObj("creation")
        val meta = creation?.jObj("metadata")
        val userInfo = pageInfo?.jObj("user_info") ?: creation?.jObj("author")

        val rawVideo = meta?.jStr("video_url") ?: creation?.jStr("video_url")
        val downloadInfo = meta?.jObj("download_info")?.asObjOrNull()
        val watermarkEndingUrl = downloadInfo?.jStr("watermark_ending_url")
        val downloadUrl = downloadInfo?.jStr("url")

        val candidates = mutableListOf<String>()
        for (candidate in listOf(watermarkEndingUrl, rawVideo, downloadUrl)) {
            if (candidate != null && candidate.startsWith("http")) {
                val cleaned = sanitizeVideoUrl(candidate) ?: continue
                if (cleaned !in candidates) candidates.add(cleaned)
            }
        }
        val primary = candidates.firstOrNull()

        val coverUrl = meta?.jStr("cover_url") ?: creation?.jStr("cover_url") ?: pageInfo?.jStr("cover_url")

        val imageList = mutableListOf<String>()
        val rawImages = creation?.jArr("image_list") ?: meta?.jArr("image_list") ?: creation?.jArr("images")
        if (rawImages != null) {
            for (img in rawImages) {
                when {
                    img.isJsonPrimitive -> img.asStrOrNull()?.takeIf { it.startsWith("http") }?.let { imageList.add(it) }
                    img.isJsonObject -> img.asJsonObject.firstStr("image_url", "url", "origin_url")
                        ?.let { imageList.add(it) }
                }
            }
        }
        val dedupImages = LinkedHashSet(imageList).toList()
        if (primary == null && dedupImages.isEmpty() && coverUrl != null) {
            dedupImages + listOf(coverUrl)
        }

        val title = meta?.jStr("title") ?: creation?.jStr("prompt") ?: pageInfo?.jStr("title")
        val authorName = userInfo?.firstStr("name", "nickname")
        return Formatted(title, primary, candidates, coverUrl, authorName, dedupImages)
    }

    private fun fetchCompetitionPreview(videoId: String): JsonElement? {
        return try {
            val res = http.postJson(competitionApiUrl, headers, """{"video_id":"$videoId"}""")
            if (res != null && res.statusCode == 200) {
                val root = parseJsonObj(res.body)
                if (root != null && root.jStr("ret") == "0") {
                    root.jObj("data")?.jObj("video_preview")
                } else null
            } else null
        } catch (e: Throwable) {
            Log.d(TAG, "jimeng competition preview failed", e)
            null
        }
    }

    private fun formatCompetitionPreviewData(preview: JsonElement): Formatted {
        val p = preview.asObjOrNull()
        val originVideo = p?.jObj("origin_video")?.asObjOrNull()
        val transcoded = p?.jObj("transcoded_video")?.asObjOrNull()

        var primary: String? = null
        transcoded?.jObj("origin")?.asObjOrNull()?.jStr("video_url")?.let { primary = it }
        if (primary == null) primary = originVideo?.jStr("video_url")
        if (primary == null) primary = bestTranscodedUrl(transcoded)

        primary = sanitizeVideoUrl(primary)
        val videoList = mutableListOf<String>()
        if (primary != null) videoList.add(primary)
        if (transcoded != null) {
            for ((_, item) in transcoded.entrySet()) {
                val v = item.asObjOrNull()?.jStr("video_url") ?: continue
                val u = sanitizeVideoUrl(v) ?: continue
                if (u !in videoList) videoList.add(u)
            }
        }
        return Formatted(null, primary, videoList, null, null, emptyList())
    }

    private fun sanitizeVideoUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace(Regex("&lr=[^&]+"), "")
            .replace(Regex("\\?lr=[^&]+&"), "?")
            .replace(Regex("\\?lr=[^&]+$"), "")
            .replace(Regex("cd=0(%7C|\\|)0(%7C|\\|)1(%7C|\\|)(\\d+)"), "cd=0\$1\$10\$2\$20\$3\$30\$4")
    }

    private fun bestTranscodedUrl(transcoded: com.google.gson.JsonObject?): String? {
        if (transcoded == null) return null
        var best: String? = null
        var bestScore = -1L
        for ((_, item) in transcoded.entrySet()) {
            val it = item.asObjOrNull() ?: continue
            val videoUrl = it.jStr("video_url") ?: continue
            val width = it.jLong("width") ?: 0
            val height = it.jLong("height") ?: 0
            val br = it.jLong("br") ?: it.jLong("bitrate") ?: 0
            val score = width * height
            if (best == null || score > bestScore || (score == bestScore && br > 0)) {
                best = videoUrl
                bestScore = score
            }
        }
        return best
    }

    private fun extractVideoId(url: String): String? {
        val q = UrlKit.query(url)
        q["video_id"]?.takeIf { it.isNotBlank() }?.let { return it }
        q["vid"]?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun secUidOf(url: String): String? {
        val q = UrlKit.query(url)
        val v = q["share_sec_uid"] ?: q["author_id"]
        return v?.takeIf { it.isNotBlank() }
    }

    private fun extractItemId(url: String): String? {
        val query = UrlKit.query(url)
        for (key in listOf("published_item_id", "item_id", "id", "work_id", "feed_id", "projectId")) {
            val value = query[key]
            if (value != null && value.isNotEmpty() && value.all { it.isDigit() }) return value
        }
        val path = pathOf(url)
        val parts = path.split("/").filter { it.isNotBlank() }
        for (i in parts.indices.reversed()) {
            if (parts[i].length >= 10 && parts[i].all { it.isDigit() }) return parts[i]
        }
        return null
    }

    private fun extractItemIdFromHtml(html: String): String? {
        val patterns = listOf(
            Regex("""["']published_item_id["']\s*:\s*["']?(\d{10,25})"""),
            Regex("""["']item_id["']\s*:\s*["']?(\d{10,25})"""),
            Regex("""["']itemId["']\s*:\s*["']?(\d{10,25})"""),
            Regex("""["']work_id["']\s*:\s*["']?(\d{10,25})"""),
            Regex("""["']feed_id["']\s*:\s*["']?(\d{10,25})"""),
            Regex("""["']id["']\s*:\s*["']?(\d{15,25})""")
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').let { "/$it" }
    }

    private companion object {
        const val TAG = "JimengParser"
    }
}