package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * 快手作品解析器：抓取分享页 SSR 状态（APOLLO_STATE 优先、INIT_STATE 兜底），
 * 提取无水印视频 / 图集图片与元信息，输出 data.php 兼容 JSON。
 */
internal class KuaishouParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.KUAISHOU

    private val desktopHeaders = mapOf(
        "content-type" to "application/json; charset=UTF-8",
        "User-Agent" to PlatformHttp.PC_UA,
        "referer" to "https://www.kuaishou.com/",
        "cookie" to DEFAULT_COOKIE
    )

    private val mobileHeaders = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "referer" to "https://v.m.chenzhongtech.com/",
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
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
            Log.w(TAG, "kuaishou parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val shareUrl = extractUrl(input) ?: return failResponse("未找到有效的分享链接")
        val realUrl = resolveRealUrl(shareUrl)
        val videoId = extractPhotoId(realUrl)

        val loaded = loadPage(buildCandidates(realUrl, videoId))
            ?: return failResponse("作品不存在或已删除")

        val state = loaded.state
        val client = state.asObject("defaultClient") ?: state
        val photo = if (loaded.pageType == "VIDEO") {
            findPhoto(client, videoId)
        } else {
            atlasPayload(state)?.asObject("photo")
        }

        val atlas = atlasPayload(state)
        val images = collectImages(state)
        val videoUrl = if (loaded.pageType == "VIDEO") {
            resolveApolloVideoUrl(client, photo)
        } else {
            resolveAtlasVideoUrl(photo)
        }
        val caption = photo?.asString("caption")?.takeIf { it.isNotBlank() }
        val author = resolveAuthor(client, photo, state, videoId)
        val cover = if (loaded.pageType == "VIDEO") {
            resolveApolloCover(photo) ?: resolveAtlasCover(atlas, images)
        } else {
            resolveAtlasCover(atlas, images)
        }

        val md = MediaData()
        md.inputUrl = input
        md.resolvedUrl = loaded.finalUrl.takeIf { it.isNotBlank() } ?: realUrl
        md.videoId = photo?.asString("id")
            ?: photo?.asLong("photoId")?.toString()
            ?: videoId
            ?: ""
        md.author = author.nickname ?: "未知作者"
        author.uniqueId?.takeIf { it.isNotBlank() }?.let { md.authorUid = it }
        md.title = caption ?: "无标题"
        md.timestamp = resolveTimestamp(photo)
        md.duration = photo?.asDouble("duration") ?: 0.0

        when {
            videoUrl != null -> {
                md.type = "video"
                md.playUrl = videoUrl
                md.cover = cover
                md.addQuality("原画", "default", videoUrl, isOriginal = true)
            }
            images.isNotEmpty() -> {
                md.type = "image"
                md.cover = cover ?: images.firstOrNull()
                images.forEach(md::addImage)
            }
            else -> return failResponse("作品不存在或已删除")
        }

        return md.toServerJson()
    }

    // ========== 链接与作品 ID ==========

    private val urlInText = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
    private val shortVideoPattern = Regex("/short-video/([^/?#]+)")
    private val fwPhotoPattern = Regex("/fw/photo/([^/?#]+)")

    private fun extractUrl(text: String): String? {
        val url = urlInText.find(text.trim())?.value ?: return null
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: ""
        val supported = host == "kuaishou.com" ||
            host.endsWith(".kuaishou.com") ||
            host == "chenzhongtech.com" ||
            host.endsWith(".chenzhongtech.com")
        return if (supported) url else null
    }

    /** 短链跟随重定向后再取真实地址；长链直接使用 */
    private fun resolveRealUrl(url: String): String {
        if (extractPhotoId(url) != null || isFwPhotoUrl(url)) return url
        val result = http.get(url, desktopHeaders)
            ?: return url
        val finalUrl = result.finalUrl.trim()
        return if (finalUrl.isNotBlank() && extractPhotoId(finalUrl) != null) finalUrl else url
    }

    private fun extractPhotoId(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        shortVideoPattern.find(path)?.let { return it.groupValues[1] }
        fwPhotoPattern.find(path)?.let { return it.groupValues[1] }
        return null
    }

    private fun isFwPhotoUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull()?.trimEnd('/') ?: return false
        return path.startsWith("/fw/photo/")
    }

    private fun buildCandidates(realUrl: String, videoId: String?): List<String> {
        val candidates = mutableListOf(realUrl)
        if (!videoId.isNullOrBlank() && !isFwPhotoUrl(realUrl)) {
            candidates.add("https://v.m.chenzhongtech.com/fw/photo/$videoId")
        }
        return candidates.distinct()
    }

    // ========== 页面拉取与状态解析 ==========

    private data class LoadedPage(val pageType: String, val state: JsonObject, val finalUrl: String)

    private fun loadPage(candidates: List<String>): LoadedPage? {
        for (candidate in candidates) {
            tryCandidate(candidate, mobileHeaders)?.let { return it }
            tryCandidate(candidate, desktopHeaders)?.let { return it }
        }
        return null
    }

    private fun tryCandidate(candidate: String, headers: Map<String, String>): LoadedPage? {
        val result = http.get(candidate, headers) ?: return null
        if (isBlockedPayload(result.body)) return null
        val (pageType, state) = identifyAndParse(result.body)
        if (pageType == "UNKNOWN" || state == null) return null
        if (!isValidVideoState(pageType, state)) return null
        return LoadedPage(pageType, state, result.finalUrl)
    }

    private fun isBlockedPayload(html: String): Boolean {
        val obj = parseJsonObject(html) ?: return false
        return obj.asLong("result") == 2L
    }

    private fun identifyAndParse(html: String): Pair<String, JsonObject?> {
        stateJson(html, "window.__APOLLO_STATE__")?.let { json ->
            parseJsonObject(json)?.let { return "VIDEO" to it }
        }
        stateJson(html, "window.INIT_STATE")?.let { json ->
            parseJsonObject(json)?.let { return "ATLAS" to it }
        }
        return "UNKNOWN" to null
    }

    private fun stateJson(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf("{", markerIndex + marker.length)
        if (start < 0) return null
        return extractJsonObject(html, start)
    }

    /** 括号平衡法稳健提取 JSON 对象，兼容 SSR 里多余的脚本尾料 */
    private fun extractJsonObject(text: String, startIndex: Int): String? {
        if (startIndex < 0 || startIndex >= text.length) return null
        var bracketCount = 0
        var inString = false
        var escapeNext = false
        var quoteChar = ' '
        var i = startIndex
        while (i < text.length) {
            val char = text[i]
            if (inString) {
                when {
                    escapeNext -> escapeNext = false
                    char == '\\' -> escapeNext = true
                    char == quoteChar -> inString = false
                }
                i++
                continue
            }
            when (char) {
                '\'', '"' -> {
                    inString = true
                    quoteChar = char
                }
                '{' -> bracketCount++
                '}' -> {
                    bracketCount--
                    if (bracketCount == 0) return text.substring(startIndex, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun parseJsonObject(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    private fun isValidVideoState(pageType: String, state: JsonObject): Boolean {
        if (pageType == "ATLAS") {
            val payload = findNestedDict(state, listOf("photo")) ?: return false
            val photo = payload.asObject("photo")
            if (photo == null) return false
            return photo.hasValue("caption") ||
                photo.hasValue("coverUrls") ||
                photo.hasValue("webpCoverUrls") ||
                photo.hasValue("mainMvUrls") ||
                photo.hasValue("manifest") ||
                payload.hasValue("atlas")
        }
        if (pageType != "VIDEO") return true
        val defaultClient = state.asObject("defaultClient") ?: return false
        if (defaultClient.hasValue("VisionVideoSetRepresentation:1")) return true
        for (key in defaultClient.keySet()) {
            if (key.contains("visionVideoDetail") || key.contains("VisionVideoDetailPhoto:")) return true
        }
        return false
    }

    /** 深度优先遍历扁平状态树，返回同时具备 requiredKeys 的节点 */
    private fun findNestedDict(root: JsonObject, requiredKeys: List<String>): JsonObject? {
        val stack = ArrayDeque<JsonElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isJsonObject) {
                val obj = current.asJsonObject
                if (requiredKeys.all { obj.has(it) }) return obj
                for (entry in obj.entrySet()) {
                    val value = entry.value
                    if (value.isJsonObject || value.isJsonArray) stack.addLast(value)
                }
            } else if (current.isJsonArray) {
                for (item in current.asJsonArray) {
                    if (item.isJsonObject || item.isJsonArray) stack.addLast(item)
                }
            }
        }
        return null
    }

    /** APOLLO 状态下定位作品对象：精确键 → 模糊 photoId 键 */
    private fun findPhoto(client: JsonObject, videoId: String?): JsonObject? {
        if (videoId != null) {
            client.asObject("VisionVideoDetailPhoto:$videoId")?.let { return it }
        }
        for (key in client.keySet()) {
            if (key.contains("VisionVideoDetailPhoto:")) {
                client.asObject(key)?.let { return it }
            }
            if (videoId != null && key.contains("photoId\":\"$videoId\"")) {
                client.asObject(key)?.let { return it }
            }
        }
        return null
    }

    private fun atlasPayload(state: JsonObject): JsonObject? {
        findNestedDict(state, listOf("atlas", "photo"))?.let { return it }
        return findNestedDict(state, listOf("photo"))
    }

    // ========== 内容提取 ==========

    private fun resolveApolloVideoUrl(client: JsonObject, photo: JsonObject?): String? {
        client.asObject("VisionVideoSetRepresentation:1")?.asString("url")?.let { return normalizeUrl(it) }
        photo?.asString("photoUrl")?.let { return normalizeUrl(it) }
        photo?.asArray("videoResource")?.forEach { res ->
            if (res.isJsonObject) {
                res.asJsonObject.asString("url")?.let { return normalizeUrl(it) }
            }
        }
        return null
    }

    private fun resolveAtlasVideoUrl(photo: JsonObject?): String? {
        if (photo == null) return null
        firstUrl(photo.get("mainMvUrls"))?.let { return it }
        firstUrl(photo.get("photoUrls"))?.let { return it }
        val manifest = photo.asObject("manifest") ?: return null
        manifest.asArray("adaptationSet")?.forEach { adapterEl ->
            if (!adapterEl.isJsonObject) return@forEach
            adapterEl.asJsonObject.asArray("representation")?.forEach { repEl ->
                if (!repEl.isJsonObject) return@forEach
                val rep = repEl.asJsonObject
                rep.get("backupUrl")?.let { firstUrl(it)?.let { return it } }
                val m3u8 = rep.asString("m3u8Slice")
                if (m3u8 != null && m3u8.contains("http")) {
                    m3u8.lineSequence().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
                    }
                }
            }
        }
        return null
    }

    private fun resolveApolloCover(photo: JsonObject?): String? {
        if (photo == null) return null
        photo.asString("coverUrl")?.let { return normalizeUrl(it) }
        firstUrl(photo.get("coverUrls"))?.let { return it }
        photo.asString("defaultCoverUrl")?.let { return normalizeUrl(it) }
        firstUrl(photo.get("webpCoverUrls"))?.let { return it }
        return null
    }

    private fun resolveAtlasCover(atlas: JsonObject?, images: List<String>): String? {
        val photo = atlas?.asObject("photo")
        if (photo != null) {
            firstUrl(photo.get("coverUrls"))?.let { return it }
            firstUrl(photo.get("webpCoverUrls"))?.let { return it }
        }
        return images.firstOrNull()
    }

    private data class AuthorInfo(
        val nickname: String?,
        val uniqueId: String?,
        val avatar: String?
    )

    private fun resolveAuthor(
        client: JsonObject,
        photo: JsonObject?,
        state: JsonObject,
        videoId: String?
    ): AuthorInfo {
        photo?.asObject("user")?.let { user ->
            val name = user.asString("name") ?: user.asString("user_name")
            val id = user.asString("id") ?: user.asLong("id")?.toString()
            if (name != null || id != null) return AuthorInfo(name, id, user.asString("headerUrl"))
        }

        photo?.let { p ->
            val authorRef = p.get("author")
            val refKey = referenceKey(authorRef)
            if (refKey != null) {
                client.asObject(refKey)?.let { detail ->
                    val id = detail.asString("id") ?: detail.asLong("id")?.toString()
                    return AuthorInfo(
                        detail.asString("name") ?: detail.asString("user_name"),
                        id,
                        detail.asString("headerUrl")
                    )
                }
            }
            if (videoId != null && refKey == null) {
                for (key in client.keySet()) {
                    if (key.contains("photoId\":\"$videoId\"")) {
                        val inner = client.asObject(key) ?: continue
                        val innerKey = referenceKey(inner.get("author")) ?: continue
                        client.asObject(innerKey)?.let { detail ->
                            return AuthorInfo(
                                detail.asString("name"),
                                detail.asString("id") ?: detail.asLong("id")?.toString(),
                                detail.asString("headerUrl")
                            )
                        }
                    }
                }
            }
        }

        photo?.let { p ->
            val id = p.asString("kwaiId") ?: p.asString("userEid") ?: p.asString("userId") ?: p.asString("eid")
            val name = p.asString("userName") ?: p.asString("user_name")
            val avatar = firstUrl(p.get("headUrls")) ?: p.asString("headUrl") ?: p.asString("headurl")
            if (name != null || id != null) return AuthorInfo(name, id?.takeIf { it.isNotBlank() }, avatar)
        }

        for (entry in state.entrySet()) {
            val value = entry.value
            if (value.isJsonObject) {
                val profile = value.asJsonObject.asObject("userProfile")?.asObject("profile")
                if (profile != null) {
                    val name = profile.asString("user_name")
                    val id = profile.asString("user_id")
                    if (name != null || id != null) {
                        return AuthorInfo(name, id, profile.asString("headurl"))
                    }
                }
            }
        }
        return AuthorInfo(null, null, null)
    }

    private fun referenceKey(ref: JsonElement?): String? {
        ref ?: return null
        if (ref.isJsonNull) return null
        if (ref.isJsonPrimitive) return ref.asString
        if (ref.isJsonObject) {
            return ref.asJsonObject.asString("__ref") ?: ref.asJsonObject.asString("id")
        }
        return null
    }

    private fun collectImages(state: JsonObject): List<String> {
        val payload = atlasPayload(state) ?: return emptyList()
        val photo = payload.asObject("photo") ?: JsonObject()

        val variants = mutableListOf<JsonObject>()
        photo.asObject("ext_params")?.asObject("atlas")?.let { variants.add(it) }
        payload.asObject("atlas")?.let { variants.add(it) }

        val preferred = variants.firstOrNull { atlasIsWebp(it) } ?: variants.firstOrNull()
        val urls = mutableListOf<String>()
        if (preferred != null) {
            val cdn = firstCdn(preferred)
            preferred.asArray("list")?.forEach { path ->
                if (path.isJsonPrimitive && path.asJsonPrimitive.isString) {
                    buildResourceUrl(cdn, path.asString)?.let { urls.add(it) }
                }
            }
        }
        if (urls.isNotEmpty()) return urls

        return listOfNotNull(
            firstUrl(photo.get("coverUrls")),
            firstUrl(photo.get("webpCoverUrls"))
        )
    }

    private fun atlasIsWebp(atlas: JsonObject): Boolean {
        val paths = atlas.asArray("list") ?: return false
        for (path in paths) {
            if (path.isJsonPrimitive && path.asJsonPrimitive.isString &&
                path.asString.lowercase().endsWith(".webp")
            ) {
                return true
            }
        }
        return false
    }

    private fun firstCdn(atlas: JsonObject): String? {
        val cdn = atlas.get("cdn")
        val cdnList = mutableListOf<String>()
        if (cdn != null && !cdn.isJsonNull) {
            if (cdn.isJsonPrimitive) cdnList.add(cdn.asString)
            else if (cdn.isJsonArray) {
                for (item in cdn.asJsonArray) if (item.isJsonPrimitive) cdnList.add(item.asString)
            }
        }
        if (cdnList.isEmpty()) {
            atlas.asArray("cdnList")?.forEach { item ->
                if (item.isJsonObject) {
                    item.asJsonObject.asString("cdn")?.takeIf { it.isNotBlank() }?.let { cdnList.add(it) }
                }
            }
        }
        return cdnList.firstOrNull()
    }

    private fun buildResourceUrl(cdn: String?, path: String): String? {
        val normalizedPath = normalizeUrl(path) ?: return null
        if (normalizedPath.startsWith("http://") || normalizedPath.startsWith("https://")) return normalizedPath
        if (normalizedPath.startsWith("//")) return "https:$normalizedPath"
        val normalizedCdn = normalizeUrl(cdn)?.trimEnd('/')
        if (normalizedCdn == null) return normalizedPath
        return if (normalizedCdn.startsWith("http://") || normalizedCdn.startsWith("https://")) {
            "${normalizedCdn}/${normalizedPath.trimStart('/')}"
        } else {
            "https://$normalizedCdn/${normalizedPath.trimStart('/')}"
        }
    }

    private fun normalizeUrl(url: String?): String? {
        if (url == null || url.isBlank()) return null
        val normalized = url.replace("\\u002F", "/")
        return if (normalized.startsWith("//")) "https:$normalized" else normalized
    }

    private fun firstUrl(candidates: JsonElement?): String? {
        if (candidates == null || candidates.isJsonNull) return null
        if (candidates.isJsonPrimitive && candidates.asJsonPrimitive.isString) {
            return normalizeUrl(candidates.asString)
        }
        if (candidates.isJsonArray) {
            for (item in candidates.asJsonArray) {
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    item.asString.takeIf { it.isNotBlank() }?.let { return normalizeUrl(it) }
                } else if (item.isJsonObject) {
                    item.asJsonObject.asString("url")?.let { return normalizeUrl(it) }
                }
            }
        }
        return null
    }

    private fun resolveTimestamp(photo: JsonObject?): Long {
        val raw = photo?.asLong("timestamp") ?: photo?.asLong("createTime")
        if (raw != null && raw > 0) {
            return if (raw > 10_000_000_000L) raw / 1000 else raw
        }
        return System.currentTimeMillis() / 1000
    }

    // ========== JSON 扩展 ==========

    private fun JsonObject.hasValue(key: String): Boolean {
        val value = get(key) ?: return false
        return !value.isJsonNull
    }

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.asLong(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.asDouble(key: String): Double? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private companion object {
        const val TAG = "KuaishouParser"
        const val DEFAULT_COOKIE =
            "kpf=PC_WEB; clientid=3; did=web_bfbcdb2f5b3dc663a745deabafcf61e6; kwpsecproductname=kuaishou-vision; " +
                "didv=1773330035000; kwpsecproductname=kuaishou-vision; userId=446442483; kuaishou.server.webday7_st=ChprdWFpc2hvdS5zZXJ2ZXIud2ViZGF5Ny5zdBKwAeuBbGjVcz39sj4G7d7P54r9C1etC_QftYb2I1XMg01WSbw9NefL7E6EmwkYxHf70B9BM3Oyk20kFv1Y0xnRcfHtGNHYUHkmKguP6cvFeACofr2zPAZYRchRkndIBk5qExOlkr4FSoGpY-WqXeibapHNEbfZTLZl_QkQA4aGWotSZpBMv6wR3RxZWiMv60xc-CIndGICJbbRAaRGZNxz7QBj2Mr-SeU2o0bVi7esnD1AGhKquV16S9dezebl5ZuYo_R_JKgiIAidQF8n526Yos_GTgm3KrGknnEbkK-NMiNvTw3YBehZKAUwAQ; " +
                "kuaishou.server.webday7_ph=f3720606882f1d7a76ab1ab52a489c4d44a1; bUserId=1000583835422; " +
                "ktrace-context=1|MS44Nzg0NzI0NTc4Nzk2ODY5Ljg3MTE4OTQ4LjE3NzM1NzExNTEyMjQuNDQ0OTc1MTI=|MS44Nzg0NzI0NTc4Nzk2ODY5LjUxNTU3MjM4LjE3NzM1NzExNTEyMjQuNDQ0OTc1MTM=|0|webservice-user-growth-node|webservice|true|src-Js; " +
                "kwssectoken=BIjmefxxiTpXOdz9/RQ6Gl7cR5/0J7xaPzJ18udJgBSLTrJy4O7LhrYtbeeHGW+AOJrI6P8LQnioDWSuuQxV8Q==; " +
                "kwscode=75d440673de879734b8700f363119968b4fabb4eb0369b1607e206d8e8c1ac9d; kpn=KUAISHOU_VISION; " +
                "kwfv1=PnGU+9+Y8008S+nH0U+0mjPf8fP08f+98f+nLlwnrIP9P9G98YPf8jPBQSweS0+nr9G0mD8B+fP/L98/qlPe4f8eDI8f8jwBGh8BPAPfLEGALhGf+f+AYj+e4jPfLl+AY0G/cI+/Q0G0DEPfc98/mjw/pSPBbjGArh8erl+ezfG/HlP0zf+0b0+n+DGnpj+0HI+9Qj+0p0PeDF+ADIPeL7+W==; " +
                "kwssectoken=IMLS/eg005i6IUbIoIB/7WByh8ciKMPUXULQ3a5/m3dK5D9ez8He/oMP2QLhil52v7Bk3O0CO2g6t5R/5XjSCw==; " +
                "kwscode=75d440673de879734b8700f363119968b4fabb4eb0369b1607e206d8e8c1ac9d"
    }
}