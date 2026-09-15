package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * 微博解析器：从 PC / 移动端 / 短视频链接提取微博作品 ID，经访客会话引导后依次
 * 请求视频组件、statuses/show 与容器 getIndex 接口补全信息，输出 data.php 兼容 JSON。
 */
internal class WeiboParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.WEIBO

    private val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private var cookieHeader: String = ""

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "weibo parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val realUrl = resolveRealUrl(input)
            ?: return failResponse("无法获取微博视频地址")
        val videoOid = extractVideoOid(realUrl)
        val mid = extractMid(realUrl)
        if (videoOid == null && mid == null) return failResponse("无法获取微博视频地址")

        val postData = fetchPostData(realUrl, videoOid, mid)
        if (!isFulfilled(postData)) return failResponse("该微博不存在或已删除")

        val play = extractPlayUrl(postData)
            ?: return failResponse("无法获取微博视频地址")

        val md = MediaData()
        md.inputUrl = input
        md.resolvedUrl = realUrl
        md.type = "video"
        md.videoId = mid ?: videoOid ?: ""
        md.author = extractAuthor(postData)
        md.authorUid = extractAuthorUid(postData)
        md.title = extractTitle(postData, realUrl, mid)
        md.cover = extractCover(postData)
        md.playUrl = play
        md.rawPlayUrl = play
        md.originalPlayUrl = play
        md.duration = extractDuration(postData)
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    // ========== 链接解析 ==========

    private val urlInTextPattern = Pattern.compile("https?://[^\\s\\u4e00-\\u9fa5'\"]+")

    private fun resolveRealUrl(input: String): String? {
        if (input.isBlank()) return null
        val link = urlInTextPattern.matcher(input).let { if (it.find()) it.group() else null }
        val raw = (link ?: input.trim()).trim()
        if (raw.isBlank()) return null
        // 微博博文可直接由 URL 中的 ID 解析；网页端常重定向到访客系统
        // (passport.weibo.cn/visitor)，因此能从原始链接取到 ID 时不再发请求。
        if (extractMid(raw) != null || extractVideoOid(raw) != null) return raw
        val resp = http.get(raw, pcHeaders())
        val finalUrl = resp?.finalUrl?.takeIf { it.isNotBlank() } ?: return raw
        if (isVisitorUrl(finalUrl)) {
            decodeEmbeddedUrl(finalUrl)?.let { if (extractMid(it) != null) return it }
            return raw
        }
        return finalUrl
    }

    private fun isVisitorUrl(url: String): Boolean =
        "passport.weibo" in url || "/visitor" in url || "visitor.weibo" in url

    /** 从访客系统的 url= 参数还原真实目标地址（percent-encoded）。 */
    private fun decodeEmbeddedUrl(url: String): String? {
        val raw = queryParam(url, "url") ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun pcHeaders(referer: String = "https://weibo.com/"): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = PlatformHttp.PC_UA
        headers["Referer"] = referer
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        return headers
    }

    private fun mobileHeaders(referer: String = "https://m.weibo.cn/"): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = PlatformHttp.MOBILE_UA
        headers["Referer"] = referer
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        return headers
    }

    // ========== 微博 ID / 视频 OID 提取 ==========

    private val fidOidPattern = Pattern.compile("""\d+:\d+""")
    private val pathOidPattern = Pattern.compile("""/(?:tv/|l/wblive/p/)?show/(\d+:\d+)""")
    private val showOidPattern = Pattern.compile("""/show/(\d+:\d+)""")
    private val uidSegPattern = Pattern.compile("""weibo\.(?:com|cn)/\d+/([a-zA-Z0-9]+)(?:/|\?|$)""")
    private val statusDetailPattern =
        Pattern.compile("""weibo\.(?:cn|com)/(?:\d+/)?(?:status/|detail/|statuses/show\?id=)([a-zA-Z0-9]+)""")
    private val idNumPattern = Pattern.compile("""id=(\d+)""")
    private val idAnyPattern = Pattern.compile("""id=([a-zA-Z0-9]+)""")
    private val loginSegmentPattern = Pattern.compile("""weibo\.com/([a-zA-Z0-9_\-]{2,})/([a-zA-Z0-9]{9,})(?:/|\?|$)""")
    private val nineCharPattern = Pattern.compile("""/([a-zA-Z0-9]{9})\b""")
    private val uidInUrlPattern = Pattern.compile("""weibo\.(?:com|cn)/(\d+)/""")
    private val fidNumPattern = Pattern.compile("""\d+:(\d+)""")

    private fun extractVideoOid(url: String): String? {
        if (url.isBlank()) return null
        queryParam(url, "fid")?.let { fid ->
            if (fidOidPattern.matcher(fid).matches()) return fid
        }
        val path = runCatching { URL(url).path }.getOrNull() ?: return null
        matchGroup(pathOidPattern, path)?.let { return it }
        matchGroup(showOidPattern, url)?.let { return it }
        return null
    }

    private fun extractMid(url: String): String? {
        if (url.isBlank()) return null
        queryParam(url, "fid")?.let { fid ->
            fullMatchGroup(fidNumPattern, fid)?.let { return it }
        }
        val path = runCatching { URL(url).path }.getOrNull()
        if (path != null && path.isNotBlank()) {
            matchGroup(pathOidPattern, path)?.let { return it }
        }
        matchGroup(showOidPattern, url)?.let { return it }
        matchGroup(uidSegPattern, url)?.let { seg -> return if (seg.all { it.isDigit() }) seg else mid2id(seg) }
        matchGroup(statusDetailPattern, url)?.let { seg -> return if (seg.all { it.isDigit() }) seg else mid2id(seg) }
        matchGroup(idNumPattern, url)?.let { return it }
        matchGroup(idAnyPattern, url)?.let { seg -> return if (seg.all { it.isDigit() }) seg else mid2id(seg) }
        matchGroup(loginSegmentPattern, url)?.let { seg -> return if (seg.all { it.isDigit() }) seg else mid2id(seg) }
        matchGroup(nineCharPattern, url)?.let { seg -> return if (seg.all { it.isDigit() }) seg else mid2id(seg) }
        return null
    }

    private fun queryParam(url: String, key: String): String? {
        val q = url.indexOf('?')
        if (q < 0 || q + 1 >= url.length) return null
        for (pair in url.substring(q + 1).split("&")) {
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            if (k != key) continue
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            return runCatching { URLDecoder.decode(v, StandardCharsets.UTF_8.name()) }.getOrDefault(v)
        }
        return null
    }

    // ========== mid 转 status id（base62） ==========

    private fun base62Decode(text: String): BigInteger {
        var result = BigInteger.ZERO
        val base = BigInteger.valueOf(62L)
        for (c in text) {
            val idx = ALPHABET.indexOf(c)
            if (idx < 0) continue
            result = result.multiply(base).add(BigInteger.valueOf(idx.toLong()))
        }
        return result
    }

    private fun mid2id(mid: String): String {
        val reversed = mid.reversed()
        val size = if (reversed.length % 4 == 0) reversed.length / 4 else reversed.length / 4 + 1
        val parts = mutableListOf<String>()
        for (i in 0 until size) {
            val start = i * 4
            val end = (start + 4).coerceAtMost(reversed.length)
            val chunk = reversed.substring(start, end).reversed()
            var part = base62Decode(chunk).toString()
            if (i != size - 1) part = part.padStart(7, '0')
            parts.add(part)
        }
        parts.reverse()
        val joined = parts.joinToString("")
        return if (joined.isEmpty()) "" else BigInteger(joined).toString()
    }

    // ========== 数据抓取 ==========

    private fun fetchPostData(realUrl: String, videoOid: String?, mid: String?): JsonObject {
        if (videoOid != null) {
            fetchVideoPageData(realUrl, videoOid, mid)?.let { if (isFulfilled(it)) return it }
        }
        if (mid == null) return JsonObject()
        val shown = fetchStatusesShow(mid) ?: return fallbackFetchAjax(mid)
        return shown
    }

    private fun fetchVideoPageData(realUrl: String, videoOid: String, mid: String?): JsonObject? {
        if (!initVisitorSession()) return null

        val inner = JsonObject()
        inner.addProperty("oid", videoOid)
        val payload = JsonObject()
        payload.add("Component_Play_Playinfo", inner)
        val body = "data=" + URLEncoder.encode(payload.toString(), StandardCharsets.UTF_8.name())
        val resp = http.post("https://weibo.com/tv/api/component", pcHeaders(realUrl), body)
        resp?.let {
            val root = parseJsonObject(it.body)
            if (root != null && root.codeIs100000()) {
                root.asObject("data")?.asObject("Component_Play_Playinfo")?.let { playInfo ->
                    if (isFulfilled(playInfo)) return playInfo
                }
            }
        }

        val dataMid = mid ?: videoOid.substringAfterLast(":")
        val compUrl = "https://video.weibo.com/api/component?format=json&data_mid=$dataMid" +
            "&componentid=Component_Play_Playinfo"
        val compResp = http.get(compUrl, pcHeaders(realUrl))
        compResp?.let {
            val innerData = parseJsonArray(it.body)
                ?.firstOrNull { element -> element.isJsonObject }
                ?.asJsonObject?.asObject("data")
                ?.asArray("components")
                ?.firstOrNull { element -> element.isJsonObject }
                ?.asJsonObject?.asObject("data")
            if (innerData != null) return innerData
        }

        if (videoOid.startsWith("1022:")) {
            val roomUrl = "https://weibo.com/l/!/2/wblive/room/show_pc_live.json?live_id=$videoOid"
            val roomResp = http.get(roomUrl, pcHeaders(realUrl))
            roomResp?.let {
                val root = parseJsonObject(it.body)
                if (root != null && root.codeIs100000()) {
                    root.asObject("data")?.let { return it }
                }
            }
        }
        return null
    }

    private fun initVisitorSession(): Boolean {
        return try {
            val fingerprint = JsonObject()
            fingerprint.addProperty("os", "1")
            fingerprint.addProperty("browser", "Chrome")
            fingerprint.addProperty("fonts", "undefined")
            fingerprint.addProperty("screenInfo", "1440*900*24")
            fingerprint.addProperty("plugins", "")
            fingerprint.addProperty("ls", "undefined")
            fingerprint.addProperty("wh", "")
            fingerprint.addProperty("version", "1.0.0")
            fingerprint.addProperty("vendor", "Google Inc.")
            fingerprint.addProperty("ua", "Mozilla/5.0")
            val fp = URLEncoder.encode(fingerprint.toString(), StandardCharsets.UTF_8.name())

            val genUrl = "https://passport.weibo.com/visitor/genvisitor?cb=parser_callback&fp=$fp"
            val genResp = http.get(genUrl, pcHeaders()) ?: return false
            val tid = parseJsonp(genResp.body)?.asObject("data")?.asString("tid") ?: return false

            val rand = Math.random()
            val visitUrl = "https://passport.weibo.com/visitor/visitor?a=incarnate" +
                "&t=$tid&w=2&c=095&gc=&cb=parser_callback&from=weibo&_rand=$rand"
            val visitResp = http.get(visitUrl, pcHeaders()) ?: return false
            digestSub(visitResp.body)
            val retcode = parseJsonp(visitResp.body)
                ?.get("retcode")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            retcode != null && runCatching { retcode.asLong == 20000000L }.getOrDefault(false)
        } catch (e: Throwable) {
            false
        }
    }

    private fun digestSub(body: String) {
        val obj = parseJsonp(body) ?: return
        val sub = obj.asString("sub") ?: obj.asObject("data")?.asString("sub") ?: return
        setCookie("SUB=$sub")
    }

    private fun setCookie(pair: String) {
        cookieHeader = if (cookieHeader.isBlank()) pair else "$cookieHeader; $pair"
    }

    private fun fetchStatusesShow(mid: String): JsonObject? {
        val headers = mobileHeaders("https://m.weibo.cn/detail/$mid").toMutableMap().apply {
            put("Accept", "application/json, text/plain, */*")
            put("MWeibo-Pwa", "1")
            put("X-Requested-With", "XMLHttpRequest")
        }
        val resp = http.get("https://m.weibo.cn/statuses/show?id=$mid", headers) ?: return null
        val root = parseJsonObject(resp.body) ?: return null
        if (root.okFlag()) return root.asObject("data")
        return null
    }

    private val renderDataPattern =
        Pattern.compile("${'$'}render_data\\s*=\\s*\\[(.*?)\\]\\[0\\]\\s*\\|\\|", Pattern.DOTALL)

    private fun fallbackFetchAjax(mid: String): JsonObject {
        val headers = mobileHeaders().toMutableMap().apply {
            put("Accept", "text/html,application/xhtml+xml,application/xml;")
        }
        val resp = http.get("https://m.weibo.cn/detail/$mid", headers)
        if (resp != null) {
            val m = renderDataPattern.matcher(resp.body)
            if (m.find()) {
                parseJsonObject(m.group(1) ?: "")?.asObject("status")?.let { return it }
            }
        }
        val pcAjax = http.get("https://weibo.com/ajax/statuses/show?id=$mid", pcHeaders())
        pcAjax?.let { parseJsonObject(it.body)?.let { return it } }
        return JsonObject()
    }

    private fun fetchContainerLongText(uid: String?, mid: String): String? {
        if (uid.isNullOrBlank() || mid.isBlank()) return null
        val url = "https://m.weibo.cn/api/container/getIndex?type=item&uid=$uid&mid=$mid" +
            "&containerid=230413${uid}_-_$mid"
        val resp = http.get(url, mobileHeaders()) ?: return null
        val root = parseJsonObject(resp.body) ?: return null
        if (!root.okFlag()) return null
        val cards = root.asObject("data")?.asArray("cards") ?: return null
        if (cards.size() == 0) return null
        val card = cards.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
        val groupText = card.asArray("card_group")
            ?.firstOrNull { it.isJsonObject }?.asJsonObject?.asString("text")
        return card.asObject("longText")?.asString("text")
            ?: card.asString("text")
            ?: groupText
    }

    // ========== 字段提取 ==========

    private fun extractTitle(postData: JsonObject, realUrl: String, mid: String?): String {
        postData.asString("title")?.let { return it }
        val pageInfo = postData.asObject("page_info")
        pageInfo?.asString("title")?.let { return it }
        pageInfo?.asString("content2")?.let { return it }
        pageInfo?.asString("content1")?.let { return it }
        pageInfo?.asString("page_title")?.let { return stripHtml(it).trim() }
        postData.asString("longText")?.let { return stripHtml(it).trim() }
        val text = postData.asString("text_raw")
            ?: postData.asString("text")
            ?: postData.asString("content")
        if (text != null) return stripHtml(text).trim()
        if (mid != null) {
            fetchContainerLongText(extractUid(realUrl, postData), mid)?.let {
                val clean = stripHtml(it).trim()
                if (clean.isNotEmpty()) return clean
            }
        }
        return "无标题"
    }

    private fun extractUid(realUrl: String, postData: JsonObject): String? {
        postData.asObject("user")?.asString("id")?.let { return it }
        postData.asString("uid")?.let { return it }
        matchGroup(uidInUrlPattern, realUrl)?.let { return it }
        return null
    }

    private fun extractAuthor(postData: JsonObject): String {
        val user = postData.asObject("user")
        return postData.asString("screen_name")
            ?: postData.asString("screenName")
            ?: user?.asString("screen_name")
            ?: user?.asString("screenName")
            ?: user?.asString("nickname")
            ?: postData.asString("author")
            ?: "未知作者"
    }

    private fun extractAuthorUid(postData: JsonObject): String? {
        val user = postData.asObject("user")
        return user?.asString("id")
            ?: user?.asString("uid")
            ?: postData.asString("author_id")
    }

    private fun extractCover(postData: JsonObject): String? {
        postData.asString("cover")?.let { return withProto(it) }
        postData.asString("cover_image")?.let { return withProto(it) }
        postData.asString("thumbnail_pic")?.let { return withProto(it) }
        postData.asString("bmiddle_pic")?.let { return withProto(it) }
        postData.asObject("page_info")?.asObject("page_pic")?.asString("url")?.let { return withProto(it) }
        return null
    }

    private val htmlTagPattern = Pattern.compile("""<[^>]+>""")

    private fun stripHtml(text: String): String = htmlTagPattern.matcher(text).replaceAll("")

    private fun extractPlayUrl(postData: JsonObject): String? {
        for (key in listOf("replay_origin_url", "live_origin_hls_url", "live_origin_flv_url")) {
            postData.asString(key)?.let { return withProto(it) }
        }
        postData.asObject("urls")?.let { urls -> firstPlayUrlFromUrls(urls)?.let { return it } }
        postData.asObject("page_info")?.asObject("urls")?.let { urls ->
            firstPlayUrlFromUrls(urls)?.let { return it }
        }
        postData.asString("ff_mp4_hd_url")?.let { return withProto(it) }

        val mediaInfo = postData.asObject("page_info")?.asObject("media_info")
        if (mediaInfo != null) {
            for (key in listOf("mp4_hd_url", "mp4_sd_url", "stream_url_hd", "stream_url")) {
                mediaInfo.asString(key)?.let { return withProto(it) }
            }
            playbackListUrl(mediaInfo.asArray("playback_list"))?.let { return withProto(it) }
        }
        for (key in listOf("mp4_hd_url", "mp4_sd_url", "stream_url_hd", "stream_url")) {
            postData.asString(key)?.let { return withProto(it) }
        }
        postData.asString("h5_url")?.let { return withProto(it) }
        postData.asString("hd_url")?.let { return withProto(it) }
        postData.asString("url")?.let { return withProto(it) }
        postData.asObject("page_info")?.let { pi ->
            pi.asString("page_url")?.let { return withProto(it) }
            pi.asString("card_url")?.let { return withProto(it) }
        }
        playbackListUrl(postData.asArray("playback_list"))?.let { return withProto(it) }
        return null
    }

    private fun playbackListUrl(list: JsonArray?): String? {
        if (list == null) return null
        for (element in list) {
            if (!element.isJsonObject) continue
            element.asJsonObject.asObject("play_info")?.asString("url")?.let { return it }
        }
        return null
    }

    /** 从 urls 映射里取播放地址，优先 720p 清晰度。 */
    private fun firstPlayUrlFromUrls(urls: JsonObject): String? {
        var first: String? = null
        for (entry in urls.entrySet()) {
            val value = entry.value
            val candidate = when {
                value.isJsonPrimitive && !value.asString.isBlank() -> value.asString
                value.isJsonObject -> value.asJsonObject.asString("url")
                    ?: value.asJsonObject.asString("back_url")
                else -> null
            } ?: continue
            val url = withProto(candidate)
            if (first == null) first = url
            if ("720" in entry.key) return url
        }
        return first
    }

    private fun extractDuration(postData: JsonObject): Double {
        postData.asObject("page_info")?.asObject("media_info")?.get("duration")?.let { return durationOf(it) }
        postData.get("duration")?.let { return durationOf(it) }
        return 0.0
    }

    private fun durationOf(el: JsonElement): Double {
        if (!el.isJsonPrimitive) return 0.0
        val p = el.asJsonPrimitive
        if (p.isNumber) return runCatching { p.asDouble }.getOrDefault(0.0)
        if (p.isString) {
            val segs = p.asString.trim().split(":")
            return when (segs.size) {
                3 -> (segs[0].toDoubleOrNull() ?: 0.0) * 3600 +
                    (segs[1].toDoubleOrNull() ?: 0.0) * 60 + (segs[2].toDoubleOrNull() ?: 0.0)
                2 -> (segs[0].toDoubleOrNull() ?: 0.0) * 60 + (segs[1].toDoubleOrNull() ?: 0.0)
                else -> p.asString.trim().toDoubleOrNull() ?: 0.0
            }
        }
        return 0.0
    }

    private fun withProto(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    // ========== 工具 ==========

    private fun matchGroup(pattern: Pattern, text: String): String? {
        val m = pattern.matcher(text)
        return if (m.find() && m.groupCount() >= 1) m.group(1) else null
    }

    private fun fullMatchGroup(pattern: Pattern, text: String): String? {
        val m = pattern.matcher(text)
        return if (m.matches() && m.groupCount() >= 1) m.group(1) else null
    }

    private fun parseJsonObject(text: String): JsonObject?
        = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun parseJsonArray(text: String): JsonArray?
        = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray

    private fun parseJsonp(text: String): JsonObject? {
        val first = text.indexOf('(')
        val last = text.lastIndexOf(')')
        if (first < 0 || last <= first) return null
        return parseJsonObject(text.substring(first + 1, last))
    }

    private fun isFulfilled(obj: JsonObject): Boolean = obj.entrySet().isNotEmpty()

    private fun JsonObject.okFlag(): Boolean {
        val v = get("ok") ?: return false
        if (!v.isJsonPrimitive) return false
        val p = v.asJsonPrimitive
        return if (p.isString) p.asString == "1" else runCatching { p.asLong == 1L }.getOrDefault(false)
    }

    private fun JsonObject.codeIs100000(): Boolean {
        val v = get("code") ?: return false
        if (!v.isJsonPrimitive) return false
        val p = v.asJsonPrimitive
        return if (p.isString) p.asString == "100000"
        else runCatching { p.asLong == 100000L }.getOrDefault(false)
    }

    private fun JsonElement.asObject(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) {
            get(key).asString.takeIf { it.isNotBlank() }
        } else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private companion object {
        const val TAG = "WeiboParser"
    }
}