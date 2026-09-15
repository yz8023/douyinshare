package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonElement
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 豆包对话分享 / 独立 AI 视频与音乐分享解析器。
 * 支持 thread/chat 页 script data-fn-args 载荷，以及 video/music-sharing
 * 独立分享页（get_video_model → fallback_api FPLAY 解密；get_play_info；
 * get_video_share_info）。按 Python 参考实现移植。
 */
internal class DoubaoParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.DOUBAO

    private val videoApi = "https://www.doubao.com/creativity/share/get_video_share_info"
    private val playInfoApi = "https://www.doubao.com/samantha/media/get_play_info"
    private val getVideoModelApi = "https://www.doubao.com/alice/resource/get_video_model"

    private val fplayKdfSalt =
        "TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA=="

    private val userAgent = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        )

    private val headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
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
            Log.w(TAG, "doubao parse failed", e)
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
        val path = pathOf(url)
        val data = Data(null, null, mutableListOf(), null, null, mutableListOf())
        when {
            path.startsWith("/thread/") || path.startsWith("/chat/") ->
                parseThread(url, data)
            path == "/video-sharing" ->
                parseVideoSharing(url, data)
            path == "/music-sharing" ->
                parseMusicSharing(url, data)
            else -> return failResponse("不支持该分享链接类型")
        }

        if (data.videoUrl == null && data.imageList.isEmpty() && data.videoList.isEmpty()) {
            return failResponse("无法提取媒体内容")
        }
        return buildResponse(input, url, data)
    }

    private fun parseThread(url: String, data: Data) {
        val resp = http.get(url, headers) ?: return
        if (resp.statusCode != 200) return
        val roots = loadScriptPayloads(resp.body)

        val creations = mutableListOf<JsonElement>()
        val imageUrls = mutableListOf<String>()
        val videoUrls = mutableListOf<String>()
        val coverUrls = mutableListOf<String>()

        for (root in roots) {
            collectCreations(root, creations)
            collectAllImages(root, imageUrls)
        }

        for (creation in creations) {
            val c = creation.asObjOrNull() ?: continue
            val image = c.jObj("image")?.asObjOrNull()
            extractImageUrl(image)?.let {
                imageUrls.add(it)
                coverUrls.add(it)
            }

            val video = c.jObj("video")?.asObjOrNull()
            if (video != null && video.entrySet().isNotEmpty()) {
                val vid = video.jStr("vid") ?: video.jStr("video_id")
                val (cleanUrls, cleanPoster) = if (vid != null) {
                    fetchUnwatermarkedVideoByVid(vid, url)
                } else Pair(emptyList(), null)
                if (cleanUrls.isNotEmpty()) videoUrls.addAll(cleanUrls)
                if (cleanPoster != null) coverUrls.add(cleanPoster)

                if (vid != null && cleanUrls.isEmpty()) {
                    val (playUrl, playPoster) = fetchPlayInfo(vid, url)
                    if (playUrl != null) videoUrls.add(playUrl)
                    if (playPoster != null) coverUrls.add(playPoster)
                }

                if (cleanUrls.isEmpty()) {
                    videoUrls.addAll(extractThreadVideoUrls(video))
                }

                val poster = video.jStr("poster_url")
                    ?: video.jObj("cover")?.asObjOrNull()?.jStr("url")
                    ?: video.jObj("poster")?.asObjOrNull()?.jStr("url")
                if (poster != null) coverUrls.add(poster)
            }
        }

        if (imageUrls.isNotEmpty()) coverUrls.addAll(imageUrls)

        var uniqueImages = unique(imageUrls)
        var uniqueVideos = unique(videoUrls)
        val uniqueCovers = unique(coverUrls)

        val cleanVideos = uniqueVideos.filter { !isWatermarkedVideoUrl(it) }
        if (cleanVideos.isNotEmpty()) uniqueVideos = cleanVideos.toMutableList()

        data.title = findTitle(roots) ?: "豆包对话分享"
        data.videoUrl = uniqueVideos.firstOrNull()
        data.videoList.addAll(uniqueVideos)
        data.coverUrl = uniqueCovers.firstOrNull()
        data.authorName = findAuthor(roots)
        data.imageList.addAll(uniqueImages)
    }

    private fun parseVideoSharing(url: String, data: Data) {
        val query = UrlKit.query(url)
        val shareId = query["share_id"] ?: return
        val videoId = query["video_id"] ?: return

        val apiHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Origin" to "https://www.doubao.com",
            "Referer" to url
        )
        val params = commonParams()

        // 1. get_video_model + FPLAY 解密
        var originalVideoUrl: String? = null
        var playPosterUrl: String? = null
        val (unwatermarked, poster) = fetchUnwatermarkedVideoByVid(videoId, url)
        if (unwatermarked.isNotEmpty()) originalVideoUrl = unwatermarked.first()
        playPosterUrl = poster

        // 2. get_play_info
        if (originalVideoUrl == null) {
            val resp = http.postJson(apiUrl(playInfoApi, params), apiHeaders, """{"key":"$videoId"}""")
            if (resp != null && resp.statusCode == 200) {
                val root = parseJsonObj(resp.body)
                if (root != null && root.jLong("code") == 0L) {
                    val pdata = root.jObj("data")
                    val rawOrig = pdata?.jObj("original_media_info")?.asObjOrNull()?.jStr("main_url")
                    if (rawOrig != null) originalVideoUrl = sanitizeVideoUrl(rawOrig)
                    if (pdata?.jStr("poster_url") != null) playPosterUrl = pdata.jStr("poster_url")
                }
            }
        }

        // 3. get_video_share_info
        val resp = http.postJson(apiUrl(videoApi, params), apiHeaders,
            """{"share_id":"$shareId","vid":"$videoId","creation_id":""}""")
        if (resp == null || resp.statusCode != 200) {
            if (originalVideoUrl == null) return
        }
        var shareUrl: String? = null
        var prompt: String? = null
        var posterFromDetail: String? = null
        var nickname: String? = null
        var authorId: String? = null
        var avatar: String? = null
        if (resp != null) {
            val root = parseJsonObj(resp.body)
            val detail = root?.jObj("data")
            val playInfo = detail?.jObj("play_info")?.asObjOrNull()
            val rawMain = playInfo?.jStr("main") ?: playInfo?.jStr("backup")
            if (rawMain != null) shareUrl = sanitizeVideoUrl(rawMain)
            prompt = detail?.jStr("prompt")
            posterFromDetail = playInfo?.jStr("poster_url")
            val userInfo = detail?.jObj("user_info")?.asObjOrNull()
            nickname = userInfo?.jStr("nickname") ?: userInfo?.jStr("user_name")
            authorId = userInfo?.jStr("user_id")
            avatar = userInfo?.jStr("avatar") ?: userInfo?.jStr("avatar_url")
        }

        val finalVideoUrl = originalVideoUrl ?: shareUrl
        val finalVideoList: MutableList<String> =
            if (unwatermarked.isNotEmpty()) unwatermarked.toMutableList()
            else if (finalVideoUrl != null) mutableListOf(finalVideoUrl) else mutableListOf()
        val finalCover = playPosterUrl ?: posterFromDetail

        data.title = prompt ?: "豆包 AI 视频"
        data.videoUrl = finalVideoUrl
        data.videoList.addAll(finalVideoList)
        data.coverUrl = finalCover
        data.authorName = nickname
        if (authorId != null || avatar != null) {
            data.authorName = nickname
        }
    }

    private fun parseMusicSharing(url: String, data: Data) {
        val query = UrlKit.query(url)
        val vid = query["vid"] ?: query["video_id"]

        val apiHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Origin" to "https://www.doubao.com",
            "Referer" to url
        )
        val params = commonParams()

        var coverUrl: String? = null
        if (vid != null) {
            val (unwatermarked, poster) = fetchUnwatermarkedVideoByVid(vid, url)
            if (unwatermarked.isNotEmpty()) data.videoList.addAll(unwatermarked)
            if (poster != null) coverUrl = poster

            if (data.videoList.isEmpty()) {
                val resp = http.postJson(apiUrl(playInfoApi, params), apiHeaders, """{"key":"$vid"}""")
                if (resp != null && resp.statusCode == 200) {
                    val root = parseJsonObj(resp.body)
                    if (root != null && root.jLong("code") == 0L) {
                        val pdata = root.jObj("data")
                        val rawOrig = pdata?.jObj("original_media_info")?.asObjOrNull()?.jStr("main_url")
                        if (rawOrig != null) {
                            val clean = sanitizeVideoUrl(rawOrig)
                            if (pdata?.jStr("media_type") == "audio") {
                                data.title = "豆包AI音乐分享"
                                if (clean != null && !data.videoList.contains(clean)) data.videoList.add(clean)
                            } else if (clean != null) {
                                if (!data.videoList.contains(clean)) data.videoList.add(clean)
                            }
                        }
                        if (coverUrl == null && pdata?.jStr("poster_url") != null) {
                            coverUrl = pdata.jStr("poster_url")
                        }
                    }
                }
            }
        }

        data.title = data.title ?: "豆包AI音乐分享"
        data.videoUrl = data.videoList.firstOrNull()
        data.coverUrl = coverUrl
    }

    private fun buildResponse(input: String, resolvedUrl: String, data: Data): String {
        val md = MediaData()
        md.inputUrl = input
        md.title = data.title ?: "豆包 分享"
        md.resolvedUrl = resolvedUrl
        md.author = data.authorName ?: "未知作者"

        val uniqueVideos = LinkedHashSet(data.videoList).toList()
        val uniqueImages = LinkedHashSet(data.imageList).toList()

        when {
            uniqueVideos.isNotEmpty() || data.videoUrl != null -> {
                val primary = data.videoUrl ?: uniqueVideos.firstOrNull()
                md.type = "video"
                if (primary != null) {
                    md.playUrl = primary
                    md.rawPlayUrl = primary
                    md.cover = data.coverUrl
                    md.addQuality(label = "默认", ratio = "default", url = primary)
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

    private fun commonParams(): Map<String, String> = mapOf(
        "version_code" to "20800",
        "language" to "zh-CN",
        "device_platform" to "web",
        "aid" to "497858",
        "real_aid" to "497858",
        "pkg_type" to "release_version",
        "samantha_web" to "1",
        "use-olympus-account" to "1"
    )

    private fun apiUrl(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) -> "${UrlKit.encode(k)}=${UrlKit.encode(v)}" }
        return "$base?$query"
    }

    // ---- FPLAY 解密 ----

    private fun sha512(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-512").digest(data)

    private fun decipherFplayUrl(urlRaw: String?, keySeed: String?): String {
        if (urlRaw.isNullOrEmpty() || keySeed.isNullOrEmpty()) return ""
        return try {
            val encrypted = base64DecodeUrl(urlRaw.replace("-", "+").replace("_", "/")) ?: return ""
            val seed = base64DecodeUrl(keySeed.replace("-", "+").replace("_", "/")) ?: return ""

            val ciphertext = encrypted.copyOfRange(4, encrypted.size)
            val firstHash = sha512(seed)
            val salt = Base64.getDecoder().decode(fplayKdfSalt)
            val keyMaterial = firstHash + salt
            val derived = sha512(keyMaterial)

            val key = derived.copyOfRange(0, 16)
            val iv = derived.copyOfRange(16, 32)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            unpadPkcs7(decrypted)?.toString(Charsets.UTF_8)?.trim().orEmpty()
        } catch (e: Throwable) {
            Log.d(TAG, "fplay decipher failed", e)
            ""
        }
    }

    private fun base64DecodeUrl(s: String): ByteArray? {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
    }

    private fun unpadPkcs7(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val padLen = data[data.size - 1].toInt()
        if (padLen == 0 || padLen > 16 || padLen > data.size) return data
        return data.copyOfRange(0, data.size - padLen)
    }

    /** 尝试 fallback_api（带 key_seed）获取无水印直链 */
    private fun fetchFromFallbackApi(fallbackApi: String?): List<String> {
        if (fallbackApi.isNullOrBlank()) return emptyList()
        if (!fallbackApi.contains("key_seed=") && !fallbackApi.contains("key_seed%3D") &&
            !fallbackApi.contains("key_seed%3d")
        ) return emptyList()

        val candidates = listOf(
            mapOf("force_fids" to base64Encode("original"), "codec_type" to "5"),
            mapOf("codec_type" to "1")
        )
        val headers2 = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://www.doubao.com/"
        )

        for (candidate in candidates) {
            val cleanUrl = rebuildFallbackUrl(fallbackApi, candidate)
            val resp = http.get(cleanUrl, headers2) ?: continue
            if (resp.statusCode != 200) continue
            val infoData = parseJsonObj(resp.body)?.jObj("video_info")?.jObj("data") ?: continue
            val keySeed = infoData.jStr("key_seed") ?: continue

            val urls = mutableListOf<String>()
            val videoList = infoData.jObj("video_list")?.asObjOrNull() ?: continue
            for (vInfo in videoList.entrySet()) {
                val obj = vInfo.value.asObjOrNull() ?: continue
                for (key in listOf("main_url", "backup_url_1")) {
                    val raw = obj.jRawStr(key) ?: continue
                    val dec = decipherFplayUrl(raw, keySeed)
                    if (dec.isNotBlank() && dec !in urls) urls.add(dec)
                }
            }
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    private fun base64Encode(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun rebuildFallbackUrl(orig: String, cand: Map<String, String>): String {
        val base = orig.substringBefore('?')
        val existing = mutableMapOf<String, String>()
        UrlKit.query(orig).forEach { (k, v) -> if (v.isNotBlank()) existing[k] = v }
        existing.remove("logo_type")
        existing.remove("force_fids")
        existing.putAll(cand)
        return UrlKit.build(base, existing)
    }

    private fun fetchUnwatermarkedVideoByVid(vid: String, referer: String): Pair<List<String>, String?> {
        if (vid.isBlank()) return Pair(emptyList(), null)
        val apiHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Origin" to "https://www.doubao.com",
            "Referer" to referer
        )
        return try {
            val resp = http.postJson(getVideoModelApi, apiHeaders, """{"params":[{"uri":"$vid"}]}""")
                ?: return Pair(emptyList(), null)
            if (resp.statusCode != 200) return Pair(emptyList(), null)
            val root = parseJsonObj(resp.body) ?: return Pair(emptyList(), null)
            if (root.jLong("code") != 0L) return Pair(emptyList(), null)
            val results = root.jObj("data")?.jArr("results") ?: return Pair(emptyList(), null)
            val res0 = results.firstOrNull()?.asObjOrNull() ?: return Pair(emptyList(), null)
            val vRes = res0.jObj("video_model_result")?.asObjOrNull() ?: return Pair(emptyList(), null)
            val vModelRaw = vRes.jRawStr("video_model") ?: return Pair(emptyList(), null)
            var poster = res0.jObj("video_url_result")?.asObjOrNull()?.jStr("poster_url")

            val vModel = parseJsonObj(vModelRaw) ?: return Pair(emptyList(), null)
            val fbApi = vModel.jStr("fallback_api")
            if (poster == null) poster = vModel.jStr("poster_url")
            if (fbApi != null) {
                val urls = fetchFromFallbackApi(fbApi)
                if (urls.isNotEmpty()) return Pair(urls, poster)
            }
            Pair(emptyList(), null)
        } catch (e: Throwable) {
            Log.d(TAG, "get_video_model failed", e)
            Pair(emptyList(), null)
        }
    }

    private fun fetchPlayInfo(vid: String, referer: String): Pair<String?, String?> {
        val apiHeaders = headers + mapOf(
            "Content-Type" to "application/json",
            "Origin" to "https://www.doubao.com",
            "Referer" to referer
        )
        return try {
            val resp = http.postJson(apiUrl(playInfoApi, commonParams()), apiHeaders, """{"key":"$vid"}""")
                ?: return Pair(null, null)
            if (resp.statusCode != 200) return Pair(null, null)
            val root = parseJsonObj(resp.body) ?: return Pair(null, null)
            if (root.jLong("code") != 0L) return Pair(null, null)
            val pdata = root.jObj("data") ?: return Pair(null, null)
            val rawOrig = pdata.jObj("original_media_info")?.asObjOrNull()?.jStr("main_url")
            val sanitized = if (rawOrig != null) sanitizeVideoUrl(rawOrig) else null
            Pair(sanitized, pdata.jStr("poster_url"))
        } catch (e: Throwable) {
            Pair(null, null)
        }
    }

    // ---- <script data-fn-args> 载荷 ----

    private fun loadScriptPayloads(html: String): List<JsonElement> {
        val roots = mutableListOf<JsonElement>()
        val scriptRgx = Regex(
            """<script\b[^>]*\bdata-fn-args="(.*?)"\s*/?>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in scriptRgx.findAll(html)) {
            val raw = m.groupValues[1]
            val decoded = htmlUnescape(raw)
            parseJsonObj(decoded)?.let { roots.add(expandJsonStrings(it)) }
                ?: parseJsonArr(decoded)?.let { roots.add(expandJsonStrings(it)) }
        }

        // data-fn-name="r" 的 router payload
        val routerRgx = java.util.regex.Pattern.compile(
            """<script\b[^>]*\bdata-fn-name="r"[^>]*\bdata-fn-args="(.*?)"\s+nonce=""",
            java.util.regex.Pattern.DOTALL
        ).matcher(html)
        while (routerRgx.find()) {
            val raw = htmlUnescape(routerRgx.group(1) ?: "")
            parseJsonObj(raw)?.let { roots.add(expandJsonStrings(it)) }
                ?: parseJsonArr(raw)?.let { roots.add(expandJsonStrings(it)) }
        }
        return roots
    }

    private fun htmlUnescape(value: String): String {
        return value.replace("&quot;", "\"").replace("&#34;", "\"")
            .replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&amp;", "&")
    }

    private fun expandJsonStrings(element: JsonElement): JsonElement {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val copy = com.google.gson.JsonObject()
            for ((k, v) in obj.entrySet()) copy.add(k, expandJsonStrings(v))
            return copy
        }
        if (element.isJsonArray) {
            val arr = com.google.gson.JsonArray()
            for (el in element.asJsonArray) arr.add(expandJsonStrings(el))
            return arr
        }
        if (element.isJsonPrimitive) {
            runCatching { element.asString }.getOrNull()?.let { s ->
                val stripped = htmlUnescape(s).trim()
                if ((stripped.startsWith("{") || stripped.startsWith("[")) ) {
                    parseJsonObj(stripped)?.let { return expandJsonStrings(it) }
                    parseJsonArr(stripped)?.let { return expandJsonStrings(it) }
                }
            }
        }
        return element
    }

    private fun collectCreations(node: JsonElement?, output: MutableList<JsonElement>) {
        if (node == null || node.isJsonNull) return
        if (node.isJsonObject) {
            val obj = node.asJsonObject
            obj.jObj("creation_block")?.asObjOrNull()?.jArr("creations")?.forEach {
                if (it.isJsonObject) output.add(it)
            }
            obj.jArr("creations")?.forEach {
                if (it.isJsonObject) output.add(it)
            }
            for ((_, v) in obj.entrySet()) collectCreations(v, output)
        } else if (node.isJsonArray) {
            for (el in node.asJsonArray) collectCreations(el, output)
        }
    }

    private fun collectAllImages(node: JsonElement?, output: MutableList<String>) {
        if (node == null || node.isJsonNull) return
        if (node.isJsonObject) {
            val obj = node.asJsonObject

            obj.jArr("ref_images")?.let { list ->
                for (el in list) {
                    extractImageUrl(el.asObjOrNull())?.let { output.add(it) }
                }
            }
            obj.jArr("ref_resources")?.let { list ->
                for (el in list) {
                    extractImageUrl(el.asObjOrNull()?.jObj("image")?.asObjOrNull())?.let { output.add(it) }
                }
            }
            obj.jObj("image")?.asObjOrNull()?.let {
                extractImageUrl(it)?.let { u -> output.add(u) }
            }
            for ((_, v) in obj.entrySet()) collectAllImages(v, output)
        } else if (node.isJsonArray) {
            for (el in node.asJsonArray) collectAllImages(el, output)
        }
    }

    private fun extractImageUrl(img: JsonElement?): String? {
        val m = img?.asObjOrNull() ?: return null
        return m.jObj("image_ori_raw")?.asObjOrNull()?.jStr("url")
            ?: m.jObj("image_raw")?.asObjOrNull()?.jStr("url")
            ?: m.jObj("image_ori")?.asObjOrNull()?.jStr("url")
            ?: m.jObj("image_origin")?.asObjOrNull()?.jStr("url")
            ?: m.jStr("raw_url")
            ?: m.jStr("origin_url")
            ?: m.jStr("url")
    }

    private fun extractThreadVideoUrls(video: com.google.gson.JsonObject): List<String> {
        val urls = mutableListOf<String>()
        val fallbackUrls = mutableListOf<String>()

        video.jStr("download_url")?.let { direct ->
            val sanitized = sanitizeVideoUrl(direct)
            if (sanitized != null) {
                if (!isWatermarkedVideoUrl(sanitized)) urls.add(sanitized)
                else fallbackUrls.add(sanitized)
            }
        }

        var model: JsonElement? = null
        video.jRawStr("video_model")?.let { raw ->
            parseJsonObj(raw)?.let { model = it }
        }
        for (item in walkDicts(model)) {
            for (key in listOf("main_url", "backup_url_1")) {
                val encoded = item.jRawStr(key) ?: continue
                val decoded = runCatching { Base64.getDecoder().decode(encoded)?.toString(Charsets.UTF_8) }
                    .getOrNull() ?: continue
                val sanitized = sanitizeVideoUrl(decoded)
                if (sanitized != null) {
                    if (!isWatermarkedVideoUrl(sanitized)) {
                        if (sanitized !in urls) urls.add(sanitized)
                    } else {
                        if (sanitized !in fallbackUrls) fallbackUrls.add(sanitized)
                    }
                }
            }
        }

        val finalUrls = unique(urls)
        return if (finalUrls.isNotEmpty()) finalUrls else unique(fallbackUrls)
    }

    private fun sanitizeVideoUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        val base = url.substringBefore('?')
        var query = url.substringAfter('?', "")
        if (query.isNotBlank()) {
            val parts = query.split("&").filter {
                val key = it.substringBefore('=')
                key != "lr" && key != "logo_type" && key != "download"
            }
            query = parts.joinToString("&")
        }
        return if (query.isBlank()) url else "$base?$query"
    }

    private fun isWatermarkedVideoUrl(url: String?): Boolean {
        val lower = (url ?: "").lowercase()
        return "video_gen_watermark" in lower || "watermark_dyn" in lower ||
            "logo_type=video_gen_watermark" in lower
    }

    private fun findTitle(roots: List<JsonElement>): String? {
        for (key in listOf("title", "prompt", "description")) {
            for (item in walkDictsAll(roots)) {
                val value = item.jStr(key)
                if (value != null && value.isNotBlank() && !value.trimStart().startsWith("{")) {
                    return value.trim()
                }
            }
        }
        return null
    }

    private fun findAuthor(roots: List<JsonElement>): String? {
        for (item in walkDictsAll(roots)) {
            val nickname = item.jStr("nickname") ?: item.jStr("user_name")
            if (nickname != null && nickname.isNotBlank()) return nickname.trim()
        }
        return null
    }

    private fun walkDicts(node: JsonElement?): Sequence<com.google.gson.JsonObject> = sequence {
        if (node == null || node.isJsonNull) return@sequence
        if (node.isJsonObject) {
            yield(node.asJsonObject)
            for ((_, v) in node.asJsonObject.entrySet()) {
                yieldAll(walkDicts(v))
            }
        } else if (node.isJsonArray) {
            for (el in node.asJsonArray) yieldAll(walkDicts(el))
        }
    }

    private fun walkDictsAll(roots: List<JsonElement>): Sequence<com.google.gson.JsonObject> = sequence {
        for (root in roots) yieldAll(walkDicts(root))
    }

    private fun unique(values: List<String>): MutableList<String> {
        return LinkedHashSet(values.filter { it.isNotBlank() }).toMutableList()
    }

    private fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').let { "/$it" }
    }

    private companion object {
        const val TAG = "DoubaoParser"
    }
}