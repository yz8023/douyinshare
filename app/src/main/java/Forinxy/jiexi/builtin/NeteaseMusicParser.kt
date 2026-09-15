package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder

/**
 * 网易云音乐解析器：歌曲 / MV / Mlog / 动态分享，输出 data.php 兼容 JSON。
 */
internal class NeteaseMusicParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.NETEASE_MUSIC

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input).toServerJson()
        } catch (e: ParseException) {
            Log.w(TAG, "parse netease music failed: ${e.message}")
            failResponse(e.message ?: "解析失败，请稍后重试")
        } catch (e: Throwable) {
            Log.w(TAG, "parse netease music failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): MediaData {
        resetState()
        val url = extractUrl(input)
            ?: throw ParseException("无法识别网易云音乐链接")
        val routing = detectMedia(url)
        val type = routing.type
            ?: throw ParseException("无法识别网易云音乐链接")
        val id = routing.id

        return when (type) {
            "mv" -> {
                require(parseMv(id)) { "MV不存在或已删除" }
                buildMediaData(url, input)
            }
            "mlog" -> {
                require(parseMlog(url, id)) { "音乐动态不存在或已删除" }
                buildMediaData(url, input)
            }
            "event" -> {
                require(parseEvent(url, id)) { "动态不存在或已删除" }
                buildMediaData(url, input)
            }
            else -> {
                require(id != null) { "歌曲不存在或已删除" }
                require(parseSong(id)) { "歌曲不存在或已删除" }
                buildSongData(url, input)
            }
        }
    }

    private fun buildSongData(shareUrl: String, input: String): MediaData {
        val playable = audioUrl ?: throw ParseException("该歌曲无可用试听/受版权保护")
        val md = MediaData()
        md.type = "music"
        md.playUrl = playable
        md.rawPlayUrl = playable
        md.originalPlayUrl = playable
        md.title = title.ifBlank { "无标题" }
        md.author = authorNickname.ifBlank { "未知作者" }
        md.authorUid = authorId?.takeIf { it.isNotBlank() }
        md.cover = coverUrl
        md.duration = durationSec
        md.videoId = mediaId.orEmpty()
        md.inputUrl = input
        md.resolvedUrl = shareUrl
        md.addQuality("标准", "default", playable, isOriginal = true)
        md.lyrics.addAll(lyrics)
        return md
    }

    private fun buildMediaData(shareUrl: String, input: String): MediaData {
        val playable = videoUrl ?: audioUrl
        if (playable.isNullOrBlank() && images.isEmpty() && galleryItems.isEmpty()) {
            throw ParseException("无法获取播放地址")
        }
        val md = MediaData()
        md.author = authorNickname.ifBlank { "未知作者" }
        md.authorUid = authorId?.takeIf { it.isNotBlank() }
        md.title = title.ifBlank {
            if (authorNickname.isNotBlank()) "${authorNickname}的音乐动态" else "网易云音乐动态"
        }
        md.videoId = mediaId.orEmpty()
        md.cover = coverUrl
        md.duration = durationSec
        md.inputUrl = input
        md.resolvedUrl = shareUrl
        images.forEach { md.addImage(it) }
        galleryItems.forEach { md.addGalleryItem(it) }
        if (!playable.isNullOrBlank()) {
            md.playUrl = playable
            md.rawPlayUrl = playable
            md.originalPlayUrl = playable
            md.type = if (videoUrl != null) "video" else "music"
            md.addQuality("原画", "default", playable, isOriginal = true)
        } else {
            md.type = "image"
        }
        md.lyrics.addAll(lyrics)
        return md
    }

    /** MV：/api/mv/detail 取标题/作者/封面与 brs 分级播放地址。 */
    private fun parseMv(mid: String?): Boolean {
        if (mid.isNullOrBlank()) return false
        mediaId = mid
        val payload = requestJson(
            "https://music.163.com/api/mv/detail",
            mapOf("id" to mid)
        ) ?: return false
        val data = payload.asObject("data") ?: return false
        val name = data.asString("name")
        if (!name.isNullOrBlank() && title.isBlank()) title = name
        if (coverUrl.isNullOrBlank()) coverUrl = data.asString("cover")
        if (authorNickname.isBlank()) {
            val artists = data.asArrayOrEmpty("artists")
            if (artists.size() > 0 && artists.get(0).isJsonObject) {
                val artist = artists.get(0).asJsonObject
                authorNickname = artist.asString("name") ?: data.asString("artistName") ?: ""
                authorId = (artist.asString("id") ?: data.asString("artistId")).orEmpty()
            } else {
                authorNickname = data.asString("artistName").orEmpty()
                authorId = data.asString("artistId").orEmpty()
            }
        }
        setDuration(data)
        if (videoUrl == null) {
            val ranked = mutableListOf<Pair<Long, String>>()
            data.asObject("brs")?.entrySet()?.forEach { (quality, el) ->
                if (el.isJsonPrimitive) {
                    val url = el.asJsonPrimitive.asString
                    val rank = quality.toLongOrNull() ?: 0L
                    if (validUrl(url)) ranked.add(rank to url)
                }
            }
            ranked.sortByDescending { it.first }
            videoUrl = ranked.firstOrNull()?.second
        }
        return true
    }

    /** Mlog：抓分享页 __INITIAL_PROPS__，取 video urlInfos / 图文 / 歌曲回退。 */
    private fun parseMlog(shareUrl: String, mlogId: String?): Boolean {
        if (mlogId.isNullOrBlank()) return false
        mediaId = mlogId
        val html = fetchHtml(shareUrl, SHARE_HEADERS) ?: return false
        val payload = extractInitialProps(html) ?: return false
        val info = payload.asObject("mlogInfo") ?: return false
        val resource = info.asObject("resource")
        val content = resource?.asObject("content")
        val video = content?.asObject("video")

        setAuthor(resource?.asObject("profile"))
        if (title.isBlank()) {
            title = content?.asString("title") ?: content?.asString("text").orEmpty()
        }
        if (coverUrl.isNullOrBlank()) {
            coverUrl = video?.asString("coverUrl") ?: video?.asString("frameUrl")
                ?: video?.asObject("frameImage")?.asString("imageUrl")
        }
        if (videoUrl == null) {
            videoUrl = rankedUrl(video?.asArrayOrEmpty("urlInfos") ?: JsonArray())
            if (videoUrl == null) {
                val fallback = video?.asObject("urlInfo")?.asString("url")
                if (validUrl(fallback)) videoUrl = fallback
            }
        }
        content?.asArray("image")?.forEach { el ->
            extractImage(el)?.let { images.add(it) }
        }

        val song = content?.asObject("song") ?: resource?.asObject("song")
        val songId = song?.asString("id")
        if (videoUrl == null && songId != null) {
            parseSong(songId, fallback = song)
        }
        return true
    }

    /** 动态：抓 /event 页面 event-data textarea，解析用户与图文/视频/歌曲。 */
    private fun parseEvent(shareUrl: String, eventId: String?): Boolean {
        if (eventId.isNullOrBlank()) return false
        mediaId = eventId
        val uid = extractParam(shareUrl, "uid") ?: extractParam(shareUrl, "userid")
        val query = buildString {
            append("id=").append(eventId)
            if (!uid.isNullOrBlank()) append("&uid=").append(uid)
        }
        val payload = fetchEventData("https://music.163.com/event?$query") ?: return false

        setAuthor(payload.asObject("user"))
        val content = parseJson(payload.asString("json"))
        val song = content?.asObject("song")
        if (title.isBlank()) {
            title = content?.asString("title") ?: content?.asString("msg")
                ?: song?.asString("name").orEmpty()
        }
        payload.asArrayOrEmpty("pics").forEach { el ->
            extractEventImage(el)?.let { galleryItems.add(it) }
        }
        if (videoUrl == null) {
            videoUrl = extractNestedVideoUrls(content?.asObject("videoData")).firstOrNull()
        }
        val songId = song?.asString("id")
        if (songId != null) parseSong(songId, fallback = song, preserveTitle = true)
        return true
    }

    private fun parseSong(
        songId: String?,
        fallback: JsonObject? = null,
        preserveTitle: Boolean = false
    ): Boolean {
        val numericId = songId?.toLongOrNull() ?: return false
        val detail = requestJson(
            "https://music.163.com/api/song/detail/",
            mapOf("id" to numericId.toString(), "ids" to "[$numericId]")
        )
        val songs = detail?.asArrayOrEmpty("songs") ?: JsonArray()
        val song = songs.firstOrNull { it.isJsonObject }?.asJsonObject ?: fallback ?: return false

        if (!preserveTitle || title.isBlank()) {
            val name = song.asString("name")
            if (!name.isNullOrBlank()) title = name
        }
        val album = song.asObject("album")
        if (coverUrl.isNullOrBlank()) {
            coverUrl = album?.asString("picUrl") ?: album?.asString("blurPicUrl")
        }
        val artists = song.asArrayOrEmpty("artists")
        if (authorNickname.isBlank() && artists.size() > 0 && artists.get(0).isJsonObject) {
            val artist = artists.get(0).asJsonObject
            authorNickname = artist.asString("name").orEmpty()
            authorId = artist.asString("id").orEmpty()
        }
        mediaId = numericId.toString()
        setDuration(song)

        val player = requestJson(
            "https://music.163.com/api/song/enhance/player/url",
            mapOf("id" to numericId.toString(), "ids" to "[$numericId]", "br" to "320000")
        )
        val entries = player?.asArrayOrEmpty("data") ?: JsonArray()
        if (audioUrl.isNullOrBlank() && entries.size() > 0 && entries.get(0).isJsonObject) {
            val url = entries.get(0).asJsonObject.asString("url")
            if (validUrl(url)) audioUrl = url
        }
        parseLyrics(numericId.toString())
        return true
    }

    /** /api/song/lyric 取 LRC 原文，转换为带时间轴的歌词行。 */
    private fun parseLyrics(songId: String) {
        if (lyrics.isNotEmpty()) return
        val payload = requestJson(
            "https://music.163.com/api/song/lyric",
            mapOf("id" to songId, "lv" to "1", "kv" to "1", "tv" to "-1")
        ) ?: return
        val lrc = payload.asObject("lrc")?.asString("lyric") ?: return
        lyrics = parseLrc(lrc)
    }

    /** 解析 LRC 文本：`[mm:ss.xx]text` / `[mm:ss]text`，仅保留有文本的行。 */
    private fun parseLrc(text: String): MutableList<LyricLine> {
        val result = mutableListOf<LyricLine>()
        text.lineSequence().forEach { rawLine ->
            val match = LRC_LINE.find(rawLine.trim()) ?: return@forEach
            val minute = match.groupValues[1].toIntOrNull() ?: return@forEach
            val second = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            val content = match.groupValues[3].trim()
            if (content.isEmpty()) return@forEach
            result.add(LyricLine(text = content, start = minute * 60 + second))
        }
        return result
    }

    private fun requestJson(baseUrl: String, params: Map<String, String>): JsonObject? {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val url = if (query.isEmpty()) baseUrl else "$baseUrl?$query"
        val resp = http.get(url, API_HEADERS) ?: return null
        return parseJson(resp.body)
    }

    private fun fetchHtml(url: String, headers: Map<String, String>): String? {
        val resp = http.get(url, headers) ?: return null
        if (resp.body.isNullOrBlank()) return null
        return resp.body
    }

    private fun extractInitialProps(html: String): JsonObject? {
        val m = INITIAL_PROPS.find(html) ?: return null
        return parseJson(m.groupValues[1])
    }

    private fun fetchEventData(url: String): JsonObject? {
        val resp = http.get(url, DESKTOP_HEADERS) ?: return null
        if (resp.body.isNullOrBlank()) return null
        val m = EVENT_DATA.find(resp.body) ?: return null
        return parseJson(htmlUnescape(m.groupValues[1].trim()))
    }

    private fun setAuthor(profile: JsonObject?) {
        if (profile == null) return
        if (authorNickname.isBlank()) {
            authorNickname = profile.asString("nickname") ?: profile.asString("name") ?: ""
        }
        if (authorId.isNullOrBlank()) {
            authorId = profile.asString("userId") ?: profile.asString("id").orEmpty()
        }
    }

    private fun setDuration(from: JsonObject?) {
        if (durationSec > 0.0) return
        val v = from?.asLong("duration") ?: return
        if (v > 0) durationSec = if (v > 1000) v / 1000.0 else v.toDouble()
    }

    /** mlog content.image：字符串或 {originUrl|url|imageUrl|picUrl}。 */
    private fun extractImage(el: JsonElement): String? {
        return when {
            el.isJsonPrimitive -> el.asJsonPrimitive.asString.takeIf { validUrl(it) }
            el.isJsonObject -> {
                val o = el.asJsonObject
                val url = o.asString("originUrl") ?: o.asString("url")
                    ?: o.asString("imageUrl") ?: o.asString("picUrl")
                url?.takeIf { validUrl(it) }
            }
            else -> null
        }
    }

    /** 动态 pics：取出图 URL 与可选视频实况地址。 */
    private fun extractEventImage(el: JsonElement): GalleryItem? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val url = o.asString("originUrl") ?: o.asString("pcRectangleUrl")
            ?: o.asString("rectangleUrl")
        if (!validUrl(url)) return null
        val live = o.asString("videoOriginalUrl") ?: o.asString("videoUrl")
        return GalleryItem(
            imageUrl = url,
            livePhotoRawUrl = live?.takeIf { validUrl(it) }
        )
    }

    /** urlInfos 按分辨率降序取最优视频地址。 */
    private fun rankedUrl(entries: JsonArray): String? {
        val ranked = mutableListOf<Pair<Long, String>>()
        for (el in entries) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val url = o.asString("url") ?: continue
            if (!validUrl(url)) continue
            val rank = o.qualityRank("resolution") ?: o.qualityRank("r") ?: 0L
            ranked.add(rank to url)
        }
        ranked.sortByDescending { it.first }
        return ranked.firstOrNull()?.second
    }

    /** videoData 里递归收集 mp4/mov/m3u8 地址。 */
    private fun extractNestedVideoUrls(value: JsonElement?): List<String> {
        val found = mutableListOf<String>()
        fun walk(el: JsonElement?) {
            when {
                el == null || el.isJsonNull -> {}
                el.isJsonObject -> {
                    for ((key, child) in el.asJsonObject.entrySet()) {
                        val isUrlKey = "url" in key.lowercase()
                        if (isUrlKey && child.isJsonPrimitive) {
                            val s = child.asJsonPrimitive.asString
                            if (validUrl(s) && VIDEO_EXTS.any { s.substringBefore('?').lowercase().endsWith(it) }) {
                                found.add(s)
                                continue
                            }
                        }
                        walk(child)
                    }
                }
                el.isJsonArray -> el.asJsonArray.forEach { walk(it) }
            }
        }
        walk(value)
        val seen = LinkedHashSet(found)
        return seen.toList()
    }

    /** 分享文案中的 URL 抠取。 */
    private fun extractUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value ?: text.takeIf { it.startsWith("http") }

    /** 依据 path（含 # 片段）路由到 song / mv / mlog / event，并解析 id。 */
    private fun detectMedia(url: String): MediaRouting {
        val parsed = runCatching { URI(url) }.getOrNull()
        val rawPath = parsed?.path?.lowercase() ?: ""
        val fragment = parsed?.rawFragment ?: ""
        val combined = (rawPath + fragment.substringBefore('?').lowercase()).lowercase()
        val fullQuery = (parsed?.rawQuery ?: "") +
            (fragment.substringAfter('?', "").takeIf { it.isNotEmpty() }?.let { "&$it" }.orEmpty())
        val id = extractId(fullQuery)
        val trimmed = combined.trimEnd('/')
        val lastSegment = trimmed.substringAfterLast('/', "")
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

        val type = when {
            "/mlog" in combined -> "mlog"
            "/landing/mv" in combined || MV_REGEX.containsMatchIn(combined) -> "mv"
            "/event" in combined -> "event"
            "/song" in combined -> "song"
            else -> null
        }
        val mediaId = when (type) {
            "mv" -> id ?: lastSegment
            "song" -> id ?: lastSegment
            "mlog", "event" -> id
            else -> null
        }
        return MediaRouting(type, mediaId)
    }

    private fun extractId(text: String): String? = ID_PARAM.find(text)?.groupValues?.get(1)

    private fun extractParam(text: String, name: String): String? =
        Regex("(?:[?&#]|^)$name=([^&\\s]+)").find(text)?.groupValues?.get(1)

    private fun urlEncode(value: String): String = runCatching {
        URLEncoder.encode(value, "UTF-8")
    }.getOrDefault(value)

    private fun parseJson(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val el = JsonParser.parseString(text)
            if (el.isJsonObject) el.asJsonObject else null
        }.getOrNull()
    }

    /** 事件 textarea 里的实体是 JSON 转义 + HTML 实体，做最小 unescape。 */
    private fun htmlUnescape(text: String): String {
        var out = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        out = HEX_REF.replace(out) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }
                .getOrDefault(m.value)
        }
        out = DEC_REF.replace(out) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }
                .getOrDefault(m.value)
        }
        return out
    }

    private fun validUrl(value: String?): Boolean =
        value != null && (value.startsWith("http://") || value.startsWith("https://"))

    private fun JsonObject.qualityRank(key: String): Long? {
        val el = get(key)
        return runCatching {
            when {
                el == null || el.isJsonNull || !el.isJsonPrimitive -> null
                else -> {
                    val p = el.asJsonPrimitive
                    if (p.isNumber) p.asLong else p.asString.toLongOrNull()
                }
            }
        }.getOrNull()
    }

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asString }.getOrNull() else null

    private fun JsonObject.asLong(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private fun JsonObject.asArrayOrEmpty(key: String): JsonArray =
        asArray(key) ?: JsonArray()

    private class MediaRouting(val type: String?, val id: String?)

    private class ParseException(message: String) : RuntimeException(message)

    /** 单次 parse 会话的中间状态（解析器按平台单例复用）。 */
    private var title: String = ""
    private var coverUrl: String? = null
    private var audioUrl: String? = null
    private var videoUrl: String? = null
    private var authorNickname: String = ""
    private var authorId: String? = null
    private var mediaId: String? = null
    private var durationSec: Double = 0.0
    private var images: MutableList<String> = mutableListOf()
    private var galleryItems: MutableList<GalleryItem> = mutableListOf()
    private var lyrics: MutableList<LyricLine> = mutableListOf()

    private fun resetState() {
        title = ""
        coverUrl = null
        audioUrl = null
        videoUrl = null
        authorNickname = ""
        authorId = null
        mediaId = null
        durationSec = 0.0
        images = mutableListOf()
        galleryItems = mutableListOf()
        lyrics = mutableListOf()
    }

    private companion object {
        const val TAG = "NeteaseMusicParser"

        val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

        val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

        val API_HEADERS = mapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to "https://music.163.com/",
            "Cookie" to "os=pc"
        )

        val SHARE_HEADERS = mapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to "https://music.163.com/"
        )

        val DESKTOP_HEADERS = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://music.163.com/"
        )

        val URL_IN_TEXT = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
        val MV_REGEX = Regex("/(?:mv|mvdetail)(?:/|$)")
        val ID_PARAM = Regex("(?:[?&#/]|^)id=(\\d+)")
        val INITIAL_PROPS = Regex(
            "window\\.__INITIAL_PROPS__\\s*=\\s*(\\{.*?\\})\\s*</script>",
            RegexOption.DOT_MATCHES_ALL
        )
        val EVENT_DATA = Regex(
            "<textarea[^>]+id=\"event-data\"[^>]*>\\s*(.*?)\\s*</textarea>",
            RegexOption.DOT_MATCHES_ALL
        )
        val HEX_REF = Regex("&#x([0-9a-fA-F]+);")
        val DEC_REF = Regex("&#(\\d+);")
        val VIDEO_EXTS = listOf(".mp4", ".mov", ".m3u8")
        val LRC_LINE = Regex("""\[(\d{1,3}):(\d{1,2}(?:\.\d{1,3})?)]\s*(.*)""")
    }
}