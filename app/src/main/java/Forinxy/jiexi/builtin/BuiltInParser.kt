package Forinxy.jiexi.builtin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 内置解析引擎：在 App 进程内用纯 Kotlin 实现 server/data.php 的核心解析逻辑，
 * 供 BuiltInServer 的 /data.php 端点调用。输出结构与服务器版 1:1 兼容，
 * 客户端 ServerApiClient.parseServerResponse 无需任何改动。
 *
 * 与服务器版的主要差异：
 *  - 直接走分享页（_ROUTER_DATA / RENDER_DATA），跳过 detail API 的 a_bogus 签名，
 *    避免在客户端维护签名算法；
 *  - cookie 预热（ttwid/msToken）在进程内缓存 30 分钟；mode=cookie 时合并
 *    内置登录页捕获的抖音 cookie（DouyinAuthStore）。
 */
internal class BuiltInParser(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val mobileUserAgent: String = runCatching {
        Forinxy.jiexi.NativeLib.getParserUserAgent()
    }.getOrElse { DEFAULT_MOBILE_UA }

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ========== cookie 预热（进程内缓存 30 分钟） ==========

    @Volatile
    private var warmCookieHeader: String? = null

    @Volatile
    private var warmTimeMillis: Long = 0L

    private fun warmCookies(): String {
        val now = System.currentTimeMillis()
        val cached = warmCookieHeader
        if (cached != null && now - warmTimeMillis < WARM_CACHE_MS) {
            return cached
        }
        synchronized(this) {
            val recheck = warmCookieHeader
            if (recheck != null && now - warmTimeMillis < WARM_CACHE_MS) {
                return recheck
            }
            val fresh = fetchWarmCookies()
            warmCookieHeader = fresh
            warmTimeMillis = System.currentTimeMillis()
            return fresh
        }
    }

    /** HEAD douyin.com 拿 ttwid/msToken 等匿名会话 cookie（detail/分享页请求的前置） */
    private fun fetchWarmCookies(): String {
        return try {
            val request = Request.Builder()
                .url("https://www.douyin.com/")
                .method("HEAD", null)
                .header("User-Agent", mobileUserAgent)
                .header("Referer", "https://www.douyin.com/")
                .build()
            client.newCall(request).execute().use { response ->
                val setCookies = response.headers("Set-Cookie")
                val parts = setCookies
                    .mapNotNull { raw ->
                        val trimmed = raw.trim()
                        val nameValue = trimmed.substringBefore(';').trim()
                        if (nameValue.contains('=')) nameValue else null
                    }
                    .distinctBy { it.substringBefore('=').trim().lowercase() }
                parts.joinToString("; ")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Warm cookies failed", e)
            ""
        }
    }

    /** 组装 Cookie 头：warm cookie 为基础，mode=cookie 时用内置登录 cookie 覆盖同名键 */
    private fun buildCookieHeader(useManual: Boolean): String {
        val map = LinkedHashMap<String, String>()
        warmCookies().split(';').forEach { item ->
            val kv = item.trim()
            if (kv.contains('=')) {
                map[kv.substringBefore('=').trim()] = kv.substringAfter('=').trim()
            }
        }
        if (useManual) {
            val manual = Forinxy.jiexi.DouyinAuthStore.getCookie(appContext)
            if (!manual.isNullOrBlank()) {
                manual.split(';').forEach { item ->
                    val kv = item.trim()
                    if (kv.contains('=')) {
                        map[kv.substringBefore('=').trim()] = kv.substringAfter('=').trim()
                    }
                }
            }
        }
        return map.map { "${it.key}=${it.value}" }.joinToString("; ")
    }

    // ========== 主入口 ==========

    /**
     * 解析单个作品，返回与服务器 data.php 一致的 JSON 字符串。
     * 失败时返回 {"success":false,"error":"..."}。
     */
    fun parse(input: String, useCookie: Boolean, original: Boolean, highest: Boolean): String {
        val videoId = resolveVideoId(input)
            ?: return fail("无法识别作品 ID，请确认链接或分享文案有效")
        if (videoId.length < 15) {
            return fail("无法识别作品 ID（${videoId}）")
        }

        val cookieHeader = buildCookieHeader(useManual = useCookie)
        val shareUrl = "https://www.iesdouyin.com/share/video/$videoId/"
        val (html, resolvedUrl) = requestWithCookie(shareUrl, cookieHeader)
            ?: return fail("分享页请求失败")

        val item = extractAwemeItem(html)
            ?: return fail("解析失败：分享页未包含作品数据")

        return buildSuccessJson(item, videoId, input, resolvedUrl, useCookie, original, highest)
    }

    /** 诊断：data.php?diag=1 的同构响应（供测试连接用） */
    fun diag(): String {
        val cookieHeader = buildCookieHeader(useManual = true)
        val obj = JsonObject()
        obj.addProperty("version", "dyparse-builtin-4.2")
        obj.addProperty("platform", "android-inner")
        obj.addProperty("success", true)
        obj.addProperty("douyin_cookie_defined", Forinxy.jiexi.DouyinAuthStore.hasCookie(appContext))
        obj.addProperty(
            "douyin_cookie_has_sessionid",
            if (cookieHeader.contains("sessionid", ignoreCase = true)) "yes" else "no"
        )
        return gson.toJson(obj)
    }

    // ========== 作品 ID 解析 ==========

    private val videoIdPattern = Pattern.compile("/video/(\\d{15,21})")
    private val plainIdPattern = Pattern.compile("^(\\d{15,21})$")
    private val urlInTextPattern = Pattern.compile("https?://[^\\s\\u4e00-\\u9fa5]+")

    private fun resolveVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        videoIdPattern.matcher(trimmed).let { if (it.find()) return it.group(1) }
        if (plainIdPattern.matcher(trimmed).matches()) return trimmed

        // 从分享文案中抠出链接再试
        val link = urlInTextPattern.matcher(trimmed).takeIf { it.find() }?.group()
        if (!link.isNullOrBlank()) {
            videoIdPattern.matcher(link).let { if (it.find()) return it.group(1) }
            if (plainIdPattern.matcher(link).matches()) return link
            val host = runCatching { java.net.URI(link).host }.getOrNull()
            if (!host.isNullOrBlank() && host.contains("douyin.com", ignoreCase = true)) {
                return resolveViaRedirect(link)
            }
        }
        return null
    }

    /** 短链跟随重定向拿最终作品页，再提取 ID */
    private fun resolveViaRedirect(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .header("Referer", "https://www.douyin.com/")
                .build()
            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        } catch (e: Exception) {
            null
        }?.let { finalUrl ->
            videoIdPattern.matcher(finalUrl).let { if (it.find()) it.group(1) else null }
        }
    }

    // ========== HTTP 请求 ==========

    private fun requestWithCookie(url: String, cookieHeader: String): Pair<String, String>? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .header("Referer", "https://www.douyin.com/")
                .apply {
                    if (cookieHeader.isNotBlank()) {
                        header("Cookie", cookieHeader)
                    }
                }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isEmpty()) null
                else body to response.request.url.toString()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Share page request failed: $url", e)
            null
        }
    }

    /** 跟随重定向拿最终可播放地址（aweme/v1/play 会 302 到真实 CDN 地址） */
    private fun followRedirect(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", mobileUserAgent)
                .header("Referer", "https://www.douyin.com/")
                .build()
            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ========== 分享页提取 ==========

    private val routerDataPattern = Pattern.compile(
        "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?\\});?\\s*</script>",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )
    private val renderDataPattern = Pattern.compile(
        "<script[^>]*id=\"RENDER_DATA\"[^>]*>(.*?)</script>",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )

    private fun extractAwemeItem(html: String): JsonObject? {
        val router = routerDataPattern.matcher(html)
        if (router.find()) {
            val data = router.group(1)?.let { parseJson(it) }
            findItemInRouterData(data)?.let { return it }
        }
        val render = renderDataPattern.matcher(html)
        if (render.find()) {
            val decoded = render.group(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            val data = decoded?.let { parseJson(it) }
            findItemInRouterData(data)?.let { return it }
        }
        return null
    }

    private fun findItemInRouterData(data: JsonObject?): JsonObject? {
        if (data == null) return null
        val loader = data.asObject("loaderData")
        loader?.asObject("video_(id)/page")?.asObject("videoInfoRes")?.asArray("item_list")
            ?.firstOrNullJsonObject()?.let { return it }
        data.asObject("videoInfoRes")?.asArray("item_list")?.firstOrNullJsonObject()?.let { return it }
        data.asObject("aweme_detail")?.let { return it }
        return null
    }

    private fun parseJson(text: String): JsonObject? {
        return runCatching {
            val el = com.google.gson.JsonParser.parseString(text)
            if (el.isJsonObject) el.asJsonObject else null
        }.getOrNull()
    }

    // ========== 结果 JSON 组装（对齐 data.php 返回结构） ==========

    private fun buildSuccessJson(
        item: JsonObject,
        videoId: String,
        input: String,
        resolvedUrl: String,
        useCookie: Boolean,
        wantOriginal: Boolean,
        wantHighest: Boolean
    ): String {
        val obj = JsonObject()
        obj.addProperty("success", true)

        obj.addProperty("author", item.asObject("author")?.asString("nickname") ?: "未知作者")
        obj.addProperty("author_uid", item.asObject("author")?.asString("uid"))
        obj.addProperty("author_sec_uid", item.asObject("author")?.asString("sec_uid"))
        obj.addProperty("title", item.asString("desc") ?: "无标题")
        obj.addProperty("video_id", item.asString("aweme_id") ?: videoId)
        obj.addProperty("timestamp", item.asLong("create_time") ?: (System.currentTimeMillis() / 1000))

        // 图集
        val gallery = buildGallery(item)
        val type = if (gallery.isEmpty()) "video" else "image"
        obj.addProperty("type", type)
        if (type == "image") {
            val images = JsonArray()
            gallery.forEach { images.add(it["image_url"]?.asString) }
            obj.add("images", images)
        } else {
            obj.add("images", JsonNull.INSTANCE)
        }
        val galleryArray = JsonArray()
        gallery.forEach { galleryArray.add(it) }
        obj.add("gallery_media", galleryArray)

        // 封面
        val cover = item.asObject("video")?.asObject("cover")?.asStringUrlListFirst("url_list")
            ?: item.asObject("video")?.asObject("origin_cover")?.asStringUrlListFirst("url_list")
            ?: gallery.firstOrNull()?.asString("image_url")
        obj.addProperty("cover", cover)

        // 地址提取
        val video = item.asObject("video")
        val defaultUri = video?.asObject("play_addr")?.asString("uri")
            ?: video?.asArray("bit_rate")?.firstOrNullJsonObject()?.asObject("play_addr")?.asString("uri")

        var originalUri = video?.asObject("download_addr")?.asString("uri")
        if (originalUri == null) originalUri = video?.asObject("download_addr")?.asString("url_list_0")
        if (originalUri == null) originalUri = defaultUri

        // 最高画质候选
        var highestPlay: String? = null
        val candidates = collectVideoCandidates(item)
        val best = pickHighestCandidate(candidates)
        if (best != null) {
            highestPlay = best["url"]?.asString
        }
        if (highestPlay.isNullOrBlank()) highestPlay = originalUri
        if (highestPlay.isNullOrBlank()) highestPlay = defaultUri

        fun buildPlay(uri: String?, ratio: String): String? {
            if (uri.isNullOrBlank()) return null
            if (uri.contains("mp3")) return uri
            if (uri.startsWith("http")) return uri
            return "http://www.iesdouyin.com/aweme/v1/play/?video_id=$uri&ratio=$ratio&line=0"
        }

        val play = buildPlay(defaultUri, "1080p")
        val qualityPlay = buildPlay(originalUri, "default")

        // 按请求选择主地址：highest > original > 默认
        val selectedPlay = when {
            wantHighest -> highestPlay ?: qualityPlay ?: play
            wantOriginal -> qualityPlay ?: play
            else -> play
        }
        val saveQualityPlay = if (wantHighest) (highestPlay ?: qualityPlay) else qualityPlay

        val rawPlay = selectedPlay
        val finalPlay = if (selectedPlay != null && selectedPlay.startsWith("http")) {
            followRedirect(selectedPlay) ?: selectedPlay
        } else selectedPlay

        val qualityFinal = if (
            saveQualityPlay != null && saveQualityPlay.startsWith("http") && saveQualityPlay != selectedPlay
        ) {
            followRedirect(saveQualityPlay) ?: saveQualityPlay
        } else saveQualityPlay

        obj.addProperty("play_url", finalPlay)
        obj.addProperty("raw_play_url", rawPlay)
        obj.addProperty("original_play_url", qualityFinal)

        val durationMs = video?.asDouble("duration") ?: 0.0
        obj.addProperty("duration", durationMs / 1000.0)

        // 画质列表（仅视频）
        if (type == "video") {
            obj.add("quality_list", collectQualityList(item))
        } else {
            obj.add("quality_list", JsonArray())
        }

        obj.addProperty("resolved_url", resolvedUrl)
        obj.addProperty("input_url", input)
        obj.addProperty("auth_mode", if (useCookie) "cookie" else "anon")
        obj.addProperty("cookie_enabled", "true")

        return gson.toJson(obj)
    }

    // ========== 图集提取 ==========

    private fun buildGallery(item: JsonObject): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val imageSources = listOfNotNull(
            item.asObject("image_post_info")?.asArray("images"),
            item.asArray("image_list"),
            item.asArray("images"),
            item.asArray("image_infos"),
            item.asArray("original_images")
        ).firstOrNull { it.size() > 0 } ?: return result

        var idx = 0
        for (el in imageSources) {
            if (!el.isJsonObject) continue
            val img = el.asJsonObject
            val imageUrl = img.asArray("url_list")?.firstOrNull { !it.isJsonNull }?.asString
                ?: img.asArray("download_url_list")?.firstOrNull { !it.isJsonNull }?.asString

            // 实况图：从图集项的 video 字段提取实况视频地址
            var livePhoto: String? = null
            val liveVideo = img.asObject("video")
            if (liveVideo != null) {
                val playUri = liveVideo.asObject("play_addr")?.asString("uri")
                if (!playUri.isNullOrBlank()) {
                    livePhoto = when {
                        playUri.startsWith("http") -> playUri
                        !playUri.contains("mp3") ->
                            "http://www.iesdouyin.com/aweme/v1/play/?video_id=$playUri&ratio=1080p&line=0"
                        else -> playUri
                    }
                }
                if (livePhoto == null) {
                    for (key in listOf("play_addr_h264", "play_addr", "play_addr_lowbr", "download_addr")) {
                        val addr = liveVideo.asObject(key) ?: continue
                        val urlListFirst = addr.asArray("url_list")?.firstOrNull { !it.isJsonNull }?.asString
                        if (!urlListFirst.isNullOrBlank()) { livePhoto = urlListFirst; break }
                        val uri2 = addr.asString("uri")
                        if (!uri2.isNullOrBlank()) {
                            livePhoto = when {
                                uri2.startsWith("http") -> uri2
                                !uri2.contains("mp3") ->
                                    "http://www.iesdouyin.com/aweme/v1/play/?video_id=$uri2&ratio=1080p&line=0"
                                else -> uri2
                            }
                            break
                        }
                    }
                }
            }
            if (livePhoto == null) {
                val lv = img.asObject("video_play_addr")?.asString("uri")
                if (!lv.isNullOrBlank()) {
                    livePhoto = when {
                        lv.startsWith("http") -> lv
                        !lv.contains("mp3") ->
                            "http://www.iesdouyin.com/aweme/v1/play/?video_id=$lv&ratio=1080p&line=0"
                        else -> lv
                    }
                }
            }

            if (imageUrl != null || livePhoto != null) {
                val entry = JsonObject()
                entry.addProperty("index", idx)
                entry.addProperty("image_url", imageUrl)
                entry.addProperty("live_photo_raw_url", livePhoto)
                entry.addProperty("has_live_photo", livePhoto != null)
                result.add(entry)
                idx++
            }
        }
        return result
    }

    // ========== 画质列表（对齐 collect_quality_list） ==========

    private fun collectQualityList(item: JsonObject): JsonArray {
        val list = JsonArray()
        val video = item.asObject("video") ?: return list
        val durationMs = video.asDouble("duration") ?: 0.0
        val duration = durationMs / 1000.0

        fun buildRatioUrl(uri: String?, ratio: String?): String? {
            if (uri.isNullOrBlank()) return null
            if (uri.contains("mp3", ignoreCase = true)) return null
            if (uri.startsWith("http")) return uri
            return buildOriginalPlayEndpointUrl(uri, ratio ?: "default")
        }

        // 1) 原画质：download_addr（ratio=default）
        var originalUri = video.asObject("download_addr")?.asString("uri")
        if (originalUri == null) originalUri = video.asObject("download_addr")?.asString("url_list_0")
        var originalSize = video.asObject("download_addr")?.asLong("data_size")
            ?: video.asLong("data_size")
        if (originalUri != null) {
            val entry = JsonObject()
            entry.addProperty("label", "原画质")
            entry.addProperty("ratio", "default")
            entry.addProperty("url", buildRatioUrl(originalUri, "default"))
            if (originalSize != null && originalSize > 0) {
                entry.addProperty("size_bytes", originalSize)
            } else {
                entry.add("size_bytes", JsonNull.INSTANCE)
            }
            entry.addProperty("bit_rate", 0L)
            entry.addProperty("is_original", true)
            list.add(entry)
        } else if (video.asArray("bit_rate") != null) {
            // 无 download_addr 时兜底：把 bit_rate 最高档当作原画质候选
            var best: JsonObject? = null
            var bestH = 0
            for (el in video.asArray("bit_rate")!!) {
                if (!el.isJsonObject) continue
                val entry = el.asJsonObject
                var h = entry.asObject("play_addr")?.asLong("height")?.toInt() ?: 0
                if (h <= 0) h = entry.asLong("height")?.toInt() ?: 0
                if (best == null || h > bestH) {
                    best = entry
                    bestH = h
                }
            }
            if (best != null) {
                var uri = best.asObject("download_addr")?.asString("uri")
                if (uri == null) uri = best.asObject("play_addr")?.asString("uri")
                val size = best.asObject("download_addr")?.asLong("data_size")
                if (uri != null) {
                    val entry = JsonObject()
                    entry.addProperty("label", "原画质")
                    entry.addProperty("ratio", "default")
                    entry.addProperty("url", buildRatioUrl(uri, "default"))
                    if (size != null && size > 0) {
                        entry.addProperty("size_bytes", size)
                    } else {
                        entry.add("size_bytes", JsonNull.INSTANCE)
                    }
                    entry.addProperty("bit_rate", 0L)
                    entry.addProperty("is_original", true)
                    list.add(entry)
                }
            }
        }

        // 2) bit_rate 各档位：按比例去重（同一 ratio 只保留 data_size 最大的一档）
        val bitRate = video.asArray("bit_rate") ?: return list
        val byRatio = LinkedHashMap<String, JsonObject>()
        for (el in bitRate) {
            if (!el.isJsonObject) continue
            val entry = el.asJsonObject
            val playAddr = entry.asObject("play_addr")
            var uri = playAddr?.asString("uri")
            if (uri == null) uri = entry.asObject("download_addr")?.asString("uri")
            if (uri.isNullOrBlank()) continue

            var height = playAddr?.asLong("height")?.toInt() ?: 0
            if (height <= 0) height = entry.asLong("height")?.toInt() ?: 0
            if (height <= 0) {
                val parsedH = parseResolutionHeight(playAddr) ?: parseResolutionHeight(entry)
                if (parsedH != null) height = parsedH
            }

            val size = playAddr?.asLong("data_size")
                ?: entry.asLong("data_size")
                ?: 0L
            val bitRateValue = entry.asLong("bit_rate") ?: 0L
            val ratio = if (height > 0) "${height}p" else null
            val normRatio = ratio ?: "default"

            val existing = byRatio[normRatio]
            if (existing != null) {
                val existingHeight = existing.asLong("_height") ?: 0L
                if (existingHeight < height) {
                    byRatio[normRatio] = JsonObject().also { o ->
                        o.addProperty("uri", uri)
                        o.addProperty("_height", height.toLong())
                        o.addProperty("size", size)
                        o.addProperty("bit_rate", bitRateValue)
                        o.add("entry", entry)
                    }
                }
                continue
            }
            byRatio[normRatio] = JsonObject().also { o ->
                o.addProperty("uri", uri)
                o.addProperty("_height", height.toLong())
                o.addProperty("size", size)
                o.addProperty("bit_rate", bitRateValue)
                o.add("entry", entry)
            }
        }

        // 排序：分辨率高的在前（default 视为 -1）
        val sortedRatios = byRatio.keys.sortedByDescending { r ->
            if (r == "default") -1 else r.toIntOrNull() ?: -1
        }
        for (ratio in sortedRatios) {
            val info = byRatio[ratio] ?: continue
            val uri = info.asString("uri") ?: continue
            val url = buildRatioUrl(uri, ratio)
            if (url == null) continue
            var size = info.asLong("size") ?: 0L
            val bitRateValue = info.asLong("bit_rate") ?: 0L
            if (size <= 0 && duration > 0 && bitRateValue > 0) {
                size = bitRateValue * duration.toLong() / 8
            }
            val entry = info.asObject("entry")
            val label = qualityLabelForEntry(entry, info.asLong("_height")?.toInt() ?: 0)

            val quality = JsonObject()
            quality.addProperty("label", label)
            quality.addProperty("ratio", ratio)
            quality.addProperty("url", url)
            if (size > 0) {
                quality.addProperty("size_bytes", size)
            } else {
                quality.add("size_bytes", JsonNull.INSTANCE)
            }
            quality.addProperty("bit_rate", bitRateValue)
            quality.addProperty("is_original", false)
            list.add(quality)
        }

        return list
    }

    private fun qualityLabelForEntry(entry: JsonObject?, height: Int): String {
        if (entry != null) {
            for (key in listOf("gear_name", "quality_desc")) {
                val text = entry.asString(key)?.trim()
                if (!text.isNullOrEmpty() && QUALITY_RES_PATTERN.matcher(text).find()) {
                    return text
                }
            }
        }
        if (height > 0) return "${height}p"
        return "默认画质"
    }

    // ========== 最高画质候选收集（1:1 翻译自 data.php / dyparseX） ==========

    private fun collectVideoCandidates(item: JsonObject): List<JsonObject> {
        val video = item.asObject("video") ?: return emptyList()
        val candidates = mutableListOf<JsonObject>()

        collectOriginalPlayEndpointCandidates(video, candidates)

        video.asArray("bit_rate")?.forEachIndexed { index, el ->
            if (!el.isJsonObject) return@forEachIndexed
            val entry = el.asJsonObject
            collectAddressCandidates(
                entry,
                entry.asObject("play_addr"),
                3000 - index,
                entry.asLong("bit_rate")?.toInt() ?: 0,
                candidates
            )
            for ((key, value) in entry.entrySet()) {
                if (key.lowercase().startsWith("play_addr") ||
                    key.lowercase().startsWith("download_addr") ||
                    key.lowercase().startsWith("play_api")
                ) {
                    if (value.isJsonObject) {
                        collectAddressCandidates(
                            entry,
                            value.asJsonObject,
                            3200 - index,
                            entry.asLong("bit_rate")?.toInt() ?: 0,
                            candidates
                        )
                    }
                }
            }
        }

        collectAddressCandidates(video, video.asObject("play_addr_265"), 2500, 0, candidates)
        collectAddressCandidates(video, video.asObject("play_addr_h264"), 2400, 0, candidates)
        collectAddressCandidates(video, video.asObject("play_addr"), 2000, 0, candidates)
        collectAddressCandidates(video, video.asObject("download_addr"), 1000, 0, candidates)
        for ((key, value) in video.entrySet()) {
            if (key.lowercase().startsWith("play_addr") ||
                key.lowercase().startsWith("download_addr") ||
                key.lowercase().startsWith("play_api")
            ) {
                if (value.isJsonObject) {
                    collectAddressCandidates(video, value.asJsonObject, 1800, 0, candidates)
                }
            }
        }

        // distinctBy url
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<JsonObject>()
        for (c in candidates) {
            val url = c.asString("url") ?: continue
            if (url.isEmpty()) continue
            if (!seen.add(url)) continue
            result.add(c)
        }
        return result
    }

    private fun collectOriginalPlayEndpointCandidates(video: JsonObject, candidates: MutableList<JsonObject>) {
        val videoHeight = video.asLong("height")?.toInt()
            ?: (parseResolutionHeight(video) ?: 0)
        val videoWidth = video.asLong("width")?.toInt()
            ?: if (videoHeight > 0) (videoHeight * 16 / 9) else 0
        val videoDataSize = video.asLong("data_size")?.toInt() ?: 0
        val videoQualityType = video.asLong("quality_type")?.toInt() ?: 0

        addOriginalPlayEndpointCandidate(
            video.asObject("play_addr")?.asString("uri"), videoWidth, videoHeight, 0,
            videoDataSize, videoQualityType, null, 2600, candidates
        )
        addOriginalPlayEndpointCandidate(
            video.asString("vid"), videoWidth, videoHeight, 0,
            videoDataSize, videoQualityType, null, 2550, candidates
        )
        addOriginalPlayEndpointCandidate(
            video.asObject("download_addr")?.asString("uri"), videoWidth, videoHeight, 0,
            videoDataSize, videoQualityType, null, 2500, candidates
        )

        video.asArray("bit_rate")?.forEachIndexed { index, el ->
            if (!el.isJsonObject) return@forEachIndexed
            val entry = el.asJsonObject
            val playAddr = entry.asObject("play_addr")
            var entryHeight = playAddr?.asLong("height")?.toInt()
                ?: entry.asLong("height")?.toInt()
                ?: parseResolutionHeight(entry)
                ?: playAddr?.let { parseResolutionHeight(it) }
                ?: videoHeight
            val entryWidth = playAddr?.asLong("width")?.toInt()
                ?: entry.asLong("width")?.toInt()
                ?: if (entryHeight > 0) (entryHeight * 16 / 9) else videoWidth
            val entryBitRate = entry.asLong("bit_rate")?.toInt() ?: 0
            val entryDataSize = playAddr?.asLong("data_size")?.toInt()
                ?: entry.asLong("data_size")?.toInt()
                ?: 0
            val entryQualityType = entry.asLong("quality_type")?.toInt()
                ?: playAddr?.asLong("quality_type")?.toInt()
                ?: 0
            val ratio = if (entryHeight > 0) "${entryHeight}p" else null

            addOriginalPlayEndpointCandidate(
                entry.asObject("play_addr")?.asString("uri"), entryWidth, entryHeight,
                entryBitRate, entryDataSize, entryQualityType, ratio, 3600 - index, candidates
            )
            addOriginalPlayEndpointCandidate(
                entry.asObject("play_addr_h264")?.asString("uri"), entryWidth, entryHeight,
                entryBitRate, entryDataSize, entryQualityType, ratio, 3500 - index, candidates
            )
            addOriginalPlayEndpointCandidate(
                entry.asObject("play_addr_265")?.asString("uri"), entryWidth, entryHeight,
                entryBitRate, entryDataSize, entryQualityType, ratio, 3450 - index, candidates
            )
            addOriginalPlayEndpointCandidate(
                entry.asObject("download_addr")?.asString("uri"), entryWidth, entryHeight,
                entryBitRate, entryDataSize, entryQualityType, ratio, 3400 - index, candidates
            )
        }
    }

    private fun addOriginalPlayEndpointCandidate(
        uri: String?,
        width: Int,
        height: Int,
        bitRate: Int,
        dataSize: Int,
        qualityType: Int,
        ratio: String?,
        sourcePriority: Int,
        candidates: MutableList<JsonObject>
    ) {
        if (uri.isNullOrBlank()) return
        if (uri.startsWith("http")) return
        if (uri.contains("mp3")) return
        val normalizedHeight = maxOf(0, height)
        val normalizedWidth = if (width > 0) width else if (normalizedHeight > 0) (normalizedHeight * 16 / 9) else 0
        val entry = JsonObject()
        entry.addProperty("url", buildOriginalPlayEndpointUrl(uri, ratio))
        entry.addProperty("width", normalizedWidth)
        entry.addProperty("height", normalizedHeight)
        entry.addProperty("bitRate", bitRate)
        entry.addProperty("dataSize", dataSize)
        entry.addProperty("qualityType", qualityType)
        entry.addProperty("sourcePriority", sourcePriority)
        entry.addProperty("directUrl", false)
        candidates.add(entry)
    }

    private fun collectAddressCandidates(
        container: JsonObject,
        address: JsonObject?,
        sourcePriority: Int,
        fallbackBitRate: Int,
        candidates: MutableList<JsonObject>
    ) {
        if (address == null) return
        val urls = address.asArray("url_list")
            ?.mapNotNull { if (!it.isJsonNull) it.asString.takeIf(String::isNotBlank) else null }
            ?.toMutableList()
            ?: return
        if (urls.isEmpty()) return

        // sortedWith: directUrl 优先，watermark=0 优先
        urls.sortWith { a, b ->
            val aDirect = if (isDirectMediaUrl(a)) 0 else 1
            val bDirect = if (isDirectMediaUrl(b)) 0 else 1
            if (aDirect != bDirect) return@sortWith aDirect - bDirect
            val aNoWm = if (a.contains("watermark=0", ignoreCase = true)) 0 else 1
            val bNoWm = if (b.contains("watermark=0", ignoreCase = true)) 0 else 1
            aNoWm - bNoWm
        }

        val width = address.asLong("width")?.toInt()
            ?: container.asLong("width")?.toInt()
            ?: 0
        var fallbackHeight = address.asLong("height")?.toInt()
            ?: container.asLong("height")?.toInt()
            ?: parseResolutionHeight(container)
            ?: parseResolutionHeight(address)
            ?: 0
        val bitRate = address.asLong("bit_rate")?.toInt()
            ?: container.asLong("bit_rate")?.toInt()
            ?: fallbackBitRate
        val dataSize = address.asLong("data_size")?.toInt()
            ?: container.asLong("data_size")?.toInt()
            ?: 0
        val qualityType = container.asLong("quality_type")?.toInt()
            ?: address.asLong("quality_type")?.toInt()
            ?: 0

        urls.forEachIndexed { index, url ->
            var height = parseUrlRatioHeight(url)
            if (height == null) height = fallbackHeight
            val normalizedWidth = if (width > 0) width else if (height > 0) (height * 16 / 9) else 0
            val entry = JsonObject()
            entry.addProperty("url", url)
            entry.addProperty("width", normalizedWidth)
            entry.addProperty("height", height)
            entry.addProperty("bitRate", bitRate)
            entry.addProperty("dataSize", dataSize)
            entry.addProperty("qualityType", qualityType)
            entry.addProperty("sourcePriority", sourcePriority - index)
            entry.addProperty("directUrl", isDirectMediaUrl(url))
            candidates.add(entry)
        }
    }

    private fun buildOriginalPlayEndpointUrl(videoId: String, ratio: String?): String {
        val encodedId = java.net.URLEncoder.encode(videoId, "UTF-8")
        val encodedRatio = java.net.URLEncoder.encode(
            if (ratio != null && ratio.isNotEmpty()) ratio else "default", "UTF-8"
        )
        return "https://www.iesdouyin.com/aweme/v1/play/" +
            "?video_id=$encodedId&ratio=$encodedRatio&line=0" +
            "&is_play_url=1&watermark=0&source=PackSourceEnum_PUBLISH"
    }

    private fun parseResolutionHeight(source: JsonObject?): Int? {
        if (source == null) return null
        for (key in listOf("gear_name", "quality_desc", "ratio")) {
            val text = source.asString(key)
            if (!text.isNullOrBlank()) {
                val m = RES_HEIGHT_PATTERN.matcher(text)
                if (m.find()) return m.group(1)!!.toInt()
            }
        }
        return null
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return !url.contains("/aweme/v1/play/")
    }

    private fun parseUrlRatioHeight(url: String): Int? {
        val m = RATIO_PATTERN.matcher(url)
        if (m.find()) {
            val ratio = m.group(1) ?: return null
            if (ratio.equals("default", ignoreCase = true)) return null
            val m2 = RES_HEIGHT_PATTERN.matcher(ratio)
            if (m2.find()) return m2.group(1)!!.toInt()
        }
        return null
    }

    /** 取最高画质候选（compareBy: height→width→pixel→bitRate→dataSize→qualityType→directUrl→sourcePriority） */
    private fun pickHighestCandidate(candidates: List<JsonObject>): JsonObject? {
        var best: JsonObject? = null
        for (c in candidates) {
            if (best == null) { best = c; continue }
            val cH = c.asLong("height") ?: 0L
            val bH = best.asLong("height") ?: 0L
            if (cH > bH) { best = c; continue }
            if (cH < bH) continue
            val cW = c.asLong("width") ?: 0L
            val bW = best.asLong("width") ?: 0L
            if (cW > bW) { best = c; continue }
            if (cW < bW) continue
            val cP = cH * cW
            val bP = bH * bW
            if (cP > bP) { best = c; continue }
            if (cP < bP) continue
            val cB = c.asLong("bitRate") ?: 0L
            val bB = best.asLong("bitRate") ?: 0L
            if (cB > bB) { best = c; continue }
            if (cB < bB) continue
            val cD = c.asLong("dataSize") ?: 0L
            val bD = best.asLong("dataSize") ?: 0L
            if (cD > bD) { best = c; continue }
            if (cD < bD) continue
            val cQ = c.asLong("qualityType") ?: 0L
            val bQ = best.asLong("qualityType") ?: 0L
            if (cQ > bQ) { best = c; continue }
            if (cQ < bQ) continue
            val cDirect = if (c.asBoolean("directUrl")) 1 else 0
            val bDirect = if (best.asBoolean("directUrl")) 1 else 0
            if (cDirect > bDirect) { best = c; continue }
            if (cDirect < bDirect) continue
            val cS = c.asLong("sourcePriority") ?: 0L
            val bS = best.asLong("sourcePriority") ?: 0L
            if (cS > bS) { best = c; continue }
        }
        return best
    }

    // ========== 工具 ==========

    private fun fail(msg: String): String {
        val obj = JsonObject()
        obj.addProperty("success", false)
        obj.addProperty("error", msg)
        obj.addProperty("code", 400)
        return gson.toJson(obj)
    }

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.asLong(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.asDouble(key: String): Double? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.asBoolean(key: String): Boolean =
        if (has(key) && !get(key).isJsonNull) get(key).asBoolean else false

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private fun JsonObject.asStringOrList(key: String): String? {
        val v = get(key) ?: return null
        if (v.isJsonNull) return null
        if (v.isJsonPrimitive && v.asJsonPrimitive.isString) return v.asString
        if (v.isJsonArray && v.asJsonArray.size() > 0) {
            val first = v.asJsonArray[0]
            if (first.isJsonPrimitive && first.asJsonPrimitive.isString) return first.asString
        }
        return null
    }

    private fun JsonArray.firstOrNullJsonObject(): JsonObject? {
        for (el in this) {
            if (el.isJsonObject) return el.asJsonObject
        }
        return null
    }

    private fun JsonObject.asStringUrlListFirst(key: String): String? = asStringOrList(key)

    private companion object {
        const val TAG = "BuiltInParser"
        const val WARM_CACHE_MS = 30 * 60 * 1000L

        val DEFAULT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        val QUALITY_RES_PATTERN = Pattern.compile(
            "(?<![0-9])(2160|1440|1280|1080|960|720|540|480|360)\\s*p?(?![0-9])",
            Pattern.CASE_INSENSITIVE
        )
        val RES_HEIGHT_PATTERN = Pattern.compile(
            "(?<![0-9])(2160|1440|1280|1080|960|720|540|480|360)(?:p)?(?![0-9])",
            Pattern.CASE_INSENSITIVE
        )
        val RATIO_PATTERN = Pattern.compile("[?&]ratio=([^&]+)", Pattern.CASE_INSENSITIVE)
    }
}
