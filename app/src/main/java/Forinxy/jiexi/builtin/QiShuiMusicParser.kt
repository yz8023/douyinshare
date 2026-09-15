package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder

/**
 * 汽水音乐解析器：解析歌曲 / UGC 视频 / 歌单分享页。
 * 先抓分享页，从 `window._ROUTER_DATA` 提取轨道与视频数据；
 * 失败时回退到 beta-luna seo_track 接口。输出 data.php 兼容 JSON。
 */
internal class QiShuiMusicParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.QISHUI_MUSIC

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input).toServerJson()
        } catch (e: ParseException) {
            Log.w(TAG, "parse qishui music failed: ${e.message}")
            failResponse(e.message ?: "无法解析汽水音乐分享")
        } catch (e: Throwable) {
            Log.w(TAG, "parse qishui music failed", e)
            failResponse("无法解析汽水音乐分享")
        }
    }

    private fun doParse(input: String): MediaData {
        resetState()
        val url = extractUrl(input)
            ?: throw ParseException("无法解析汽水音乐分享")

        trackId = extractTrackId(url)

        val resp = http.get(url, PAGE_HEADERS)
            ?: throw ParseException("无法解析汽水音乐分享")
        val html = resp.body
        resolvedUrl = resp.finalUrl
        if (trackId.isNullOrBlank()) trackId = extractTrackId(resp.finalUrl)

        parseRouterData(html)

        if (title.isBlank() || (audioUrl.isNullOrBlank() && videoUrl.isNullOrBlank())) {
            parseSeoPayload()
        }

        val playable = playableUrl()
        if (playable.isNullOrBlank()) {
            if (collectionTracks.isNotEmpty()) return buildCollectionData(input)
            throw ParseException("音乐不存在或已删除")
        }

        val md = MediaData()
        md.type = "music"
        md.playUrl = playable
        md.originalPlayUrl = playable
        md.title = title.ifBlank { "无标题" }
        md.author = authorNickname.ifBlank { "未知作者" }
        md.authorUid = authorId?.takeIf { it.isNotBlank() }
        md.cover = coverUrl
        md.duration = durationSec
        md.videoId = trackId ?: videoIdFromData.orEmpty()
        md.inputUrl = input
        md.resolvedUrl = resolvedUrl
        md.lyrics.addAll(lyrics)
        md.addQuality("标准", "default", playable, isOriginal = true)
        return md
    }

    /** 歌单分享：把轨道名拼进标题，取第一首可播音频。 */
    private fun buildCollectionData(input: String): MediaData {
        val playable = collectionTracks.firstOrNull { !it.audioUrl.isNullOrBlank() }
            ?: collectionTracks.firstOrNull { !it.videoUrl.isNullOrBlank() }
            ?: throw ParseException("音乐不存在或已删除")

        val names = collectionTracks.map { it.name.ifBlank { "未知歌曲" } }
        val md = MediaData()
        md.type = "music"
        md.playUrl = playable.audioUrl ?: playable.videoUrl
        md.originalPlayUrl = md.playUrl
        md.title = collectionName.takeIf { it.isNotBlank() }
            ?.let { "$it（${collectionTracks.size}首）" }
            ?: names.joinToString("、")
        md.author = playable.artist?.ifBlank { "未知作者" } ?: "未知作者"
        md.cover = collectionTracks.firstNotNullOfOrNull { it.coverUrl } ?: coverUrl
        md.duration = collectionTracks.firstOrNull { it.duration > 0.0 }?.duration ?: 0.0
        md.videoId = playable.id ?: trackId.orEmpty()
        md.inputUrl = input
        md.resolvedUrl = resolvedUrl
        md.addQuality("标准", "default", md.playUrl ?: "", isOriginal = true)
        return md
    }

    private fun playableUrl(): String? =
        audioUrl ?: videoUrl

    /** 解析 `window._ROUTER_DATA = {...};` 中的 loaderData。 */
    private fun parseRouterData(html: String) {
        val m = ROUTER_DATA.find(html) ?: return
        val loaderData = runCatching {
            JsonParser.parseString(m.groupValues[1]).asJsonObject
        }.getOrNull()?.asObject("loaderData") ?: return

        for (key in loaderData.keySet()) {
            val el = loaderData.get(key) ?: continue
            if (!el.isJsonObject) continue
            val pageData = el.asJsonObject

            val options = pageData.asObject("videoOptions")
            if (options != null) {
                if (title.isBlank()) {
                    title = options.asString("videoName") ?: options.asString("title").orEmpty()
                }
                if (authorNickname.isBlank()) {
                    authorNickname = options.asString("artistName").orEmpty()
                }
                if (coverUrl.isNullOrBlank()) {
                    coverUrl = options.asString("coverURL") ?: options.asString("firstFrameURL")
                }
                val stream = options.asString("url")
                if (!stream.isNullOrBlank()) {
                    if (stream.contains("video_mp4") || stream.contains("douyinvod.com")) {
                        if (videoUrl.isNullOrBlank()) videoUrl = stream
                    } else if (audioUrl.isNullOrBlank()) {
                        audioUrl = stream
                    }
                }
                if (durationSec <= 0.0) durationSec = bestEffortDuration(options)
            }

            var track = pageData.asObject("trackOptions")
                ?: pageData.asObject("track")
                ?: pageData.asObject("seo_track")
            val inner = track?.asObject("track")
            if (inner != null) track = inner
            if (track != null && !track.isEmpty) {
                if (title.isBlank()) {
                    title = track.asString("name") ?: track.asString("title").orEmpty()
                }
                val artist = track.asObject("artist")
                    ?: track.asArray("artists")?.firstOrNull()
                        ?.takeIf { it.isJsonObject }?.asJsonObject?.asObject("user_info")
                if (artist != null) {
                    if (authorNickname.isBlank()) {
                        authorNickname = artist.asString("nickname") ?: artist.asString("name").orEmpty()
                    }
                    if (authorId.isNullOrBlank()) {
                        authorId = (artist.asString("id") ?: artist.asString("user_id")).orEmpty()
                    }
                }
                val album = track.asObject("album")
                if (coverUrl.isNullOrBlank()) {
                    val albumCover = album?.let { firstUrl(it.get("cover_url")) }
                    coverUrl = albumCover ?: firstUrl(track.get("cover_url"))
                }
                if (audioUrl.isNullOrBlank()) {
                    audioUrl = track.asString("audio_url")
                        ?: track.asString("play_url")
                        ?: track.asString("main_url")
                }
                if (videoIdFromData.isNullOrBlank()) {
                    videoIdFromData = track.asString("id")
                }
                if (durationSec <= 0.0) durationSec = bestEffortDuration(track)
            }

            val lyricsOpt = pageData.asObject("audioWithLyricsOption") ?: pageData.asObject("track")
            if (lyricsOpt != null) {
                if (title.isBlank()) {
                    title = lyricsOpt.asString("trackName")
                        ?: lyricsOpt.asString("videoName")
                        ?: lyricsOpt.asString("songName").orEmpty()
                }
                if (authorNickname.isBlank()) {
                    authorNickname = lyricsOpt.asString("artistName").orEmpty()
                }
                if (coverUrl.isNullOrBlank()) {
                    coverUrl = lyricsOpt.asString("coverURL")
                        ?: lyricsOpt.asString("coverUrl")
                        ?: lyricsOpt.asString("cover_url")
                }
                extractLyricsFrom(lyricsOpt)
            }

            val found = extractCollection(pageData)
            if (found.isNotEmpty() && collectionTracks.isEmpty()) {
                collectionTracks = found
                if (collectionName.isBlank()) {
                    collectionName = pageData.asString("name")
                        ?: pageData.asString("title").orEmpty()
                }
            }
        }
    }

    /**
     * 参照参考实现的 _extract_subtitles_from_dict：
     * 1) songMakerTeamSentences / sentences / lyrics / subtitles 数组（字符串或 {text|start_time|end_time}）
     * 2) lrc / lyric / lyrics_text / lyric_string 文本
     */
    private fun extractLyricsFrom(o: JsonObject) {
        if (lyrics.isNotEmpty()) return
        val sentences = o.asArray("songMakerTeamSentences")
            ?: o.asArray("sentences")
            ?: o.asArray("lyrics")
            ?: o.asArray("subtitles")
        if (sentences != null && sentences.size() > 0) {
            for (el in sentences) {
                if (el.isJsonPrimitive) {
                    el.asString.takeIf { it.isNotBlank() }?.let { lyrics.add(LyricLine(it)) }
                } else if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    val text = obj.asString("text") ?: obj.asString("content")
                        ?: obj.asString("sentence") ?: obj.asString("lyric")
                    if (!text.isNullOrBlank()) {
                        val start = obj.asDouble("start_time") ?: obj.asDouble("startTime")
                        val end = obj.asDouble("end_time") ?: obj.asDouble("endTime")
                        lyrics.add(LyricLine(text.trim(), normalizeSec(start), normalizeSec(end)))
                    }
                }
            }
            if (lyrics.isNotEmpty()) return
        }
        val lrcText = o.asString("lrc") ?: o.asString("lyric")
            ?: o.asString("lyrics_text") ?: o.asString("lyric_string")
        if (!lrcText.isNullOrBlank()) {
            parseLrc(lrcText)
        }
    }

    /** [mm:ss] 或 [mm:ss.xx] 的 LRC 文本解析 */
    private fun parseLrc(lrcText: String) {
        LRC_PATTERN.findAll(lrcText).forEach { m ->
            val minutes = m.groupValues[1].toIntOrNull() ?: 0
            val seconds = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val text = m.groupValues[3].trim()
            if (text.isNotEmpty()) {
                lyrics.add(LyricLine(text, start = minutes * 60 + seconds))
            }
        }
        if (lyrics.isEmpty()) {
            lrcText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                if (!line.startsWith("[")) lyrics.add(LyricLine(line))
            }
        }
    }

    private fun normalizeSec(v: Double?): Double? {
        if (v == null) return null
        return if (v > 1000.0) v / 1000.0 else v
    }

    /** 分享页 HTML 没有可用数据时，回退 beta-luna seo_track 接口。 */
    private fun parseSeoPayload() {
        val id = trackId
        if (id.isNullOrBlank()) return
        val resp = http.get("$SEO_TRACK_API?track_id=$id&device_platform=web", SEO_HEADERS)
            ?: return
        if (resp.statusCode !in 200..299) return
        val payload = parseJson(resp.body) ?: return
        val track = payload.asObject("seo_track")?.asObject("track") ?: return

        extractLyricsFrom(track)

        if (title.isBlank()) title = track.asString("name").orEmpty()
        val artist = track.asArray("artists")?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.asObject("user_info")
        if (artist != null) {
            if (authorNickname.isBlank()) {
                authorNickname = artist.asString("nickname").orEmpty()
            }
            if (authorId.isNullOrBlank()) {
                authorId = artist.asString("id").orEmpty()
            }
        }
        val album = track.asObject("album")
        if (coverUrl.isNullOrBlank()) {
            album?.let { firstUrl(it.get("cover_url")) }?.let { coverUrl = it }
        }
        if (audioUrl.isNullOrBlank()) {
            val videoModel = payload.asObject("track_player")?.asString("video_model")
            if (!videoModel.isNullOrBlank()) {
                val video = parseJson(videoModel)?.asArray("video_list")
                    ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                video?.asString("main_url")?.let { audioUrl = it }
                    ?: video?.asString("backup_url")?.let { audioUrl = it }
            }
        }
        if (durationSec <= 0.0) durationSec = bestEffortDuration(track)
    }

    /**
     * 从 loaderData 的页面数据里找歌单轨道列表。结构未固定，按常见字段
     * （playList / playlist / trackList / songs / songList 等）尽力提取。
     */
    private fun extractCollection(pageData: JsonObject): List<TrackItem> {
        val result = mutableListOf<TrackItem>()
        for (key in COLLECTION_KEYS) {
            val el = pageData.get(key) ?: continue
            val items: JsonArray? = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> el.asJsonObject.asArray("track_list")
                    ?: el.asJsonObject.asArray("tracks")
                    ?: el.asJsonObject.asArray("songs")
                else -> null
            }
            if (items == null) continue
            for (itemEl in items) {
                if (!itemEl.isJsonObject) continue
                result.add(trackItem(itemEl.asJsonObject))
            }
            if (result.isNotEmpty()) break
        }
        return result
    }

    private fun trackItem(o: JsonObject): TrackItem {
        val albumCover = o.asObject("album")?.let { firstUrl(it.get("cover_url")) }
        return TrackItem(
            name = o.asString("name")
                ?: o.asString("title")
                ?: o.asString("track_name")
                ?: o.asString("music_name").orEmpty(),
            artist = o.asString("artist_name")
                ?: o.asString("singer")
                ?: o.asString("author")
                ?: o.asObject("artist")?.asString("nickname"),
            audioUrl = o.asString("audio_url") ?: o.asString("play_url")
                ?: o.asString("main_url") ?: o.asString("playUrl"),
            videoUrl = o.asString("video_url") ?: o.asString("videoUrl"),
            coverUrl = o.asString("cover_url") ?: albumCover,
            id = o.asString("track_id") ?: o.asString("id"),
            duration = bestEffortDuration(o)
        )
    }

    /** 参照参考实现的 _first_url：字符串 / 字符串列表 / {url|origin_url|large_url} */
    private fun firstUrl(value: JsonElement?): String? {
        value ?: return null
        return when {
            value.isJsonPrimitive -> value.asString.takeIf { it.isNotBlank() }
            value.isJsonArray -> value.asJsonArray.firstNotNullOfOrNull { el ->
                if (el.isJsonPrimitive) el.asString.takeIf { it.isNotBlank() } else null
            }
            value.isJsonObject -> {
                val obj = value.asJsonObject
                listOf("url", "origin_url", "large_url").firstNotNullOfOrNull { key ->
                    obj.asString(key)?.takeIf { it.isNotBlank() }
                }
            }
            else -> null
        }
    }

    /** 尽力取时长（毫秒按秒折算）；参考实现不取，这里补全。 */
    private fun bestEffortDuration(o: JsonObject): Double {
        for (key in DURATION_KEYS) {
            val v = o.asDouble(key) ?: continue
            if (v > 0.0) return if (v > 1000.0) v / 1000.0 else v
        }
        return 0.0
    }

    private fun extractUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value ?: text.takeIf { it.startsWith("http") }

    /** track_id / ugc_video_id 查询参数，或 /track/、/video/ 路径中纯数字段 */
    private fun extractTrackId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val decoded = try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }
        val qIndex = decoded.indexOf('?')
        val query = if (qIndex >= 0) decoded.substring(qIndex + 1) else ""
        TRACK_ID_PARAM.find(query)?.let { return it.groupValues[1] }
        val path = runCatching { URI(decoded).path }.getOrNull().orEmpty()
        for (prefix in PATH_PREFIXES) {
            if (path.contains(prefix)) {
                val id = path.substringAfter(prefix).filter { it.isDigit() }
                if (id.isNotEmpty()) return id
            }
        }
        return null
    }

    private fun parseJson(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val el = JsonParser.parseString(text)
            if (el.isJsonObject) el.asJsonObject else null
        }.getOrNull()
    }

    private fun JsonObject.asString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asString }.getOrNull() else null

    private fun JsonObject.asDouble(key: String): Double? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.asObject(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.asArray(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private data class TrackItem(
        val name: String,
        val artist: String?,
        val audioUrl: String?,
        val videoUrl: String?,
        val coverUrl: String?,
        val id: String?,
        val duration: Double
    )

    private class ParseException(message: String) : RuntimeException(message)

    /** 单曲共享状态，一次 parse 会话内的中间结果 */
    private var title: String = ""
    private var coverUrl: String? = null
    private var audioUrl: String? = null
    private var videoUrl: String? = null
    private var authorNickname: String = ""
    private var authorId: String? = null
    private var trackId: String? = null
    private var videoIdFromData: String? = null
    private var durationSec: Double = 0.0
    private var resolvedUrl: String? = null
    private var collectionName: String = ""
    private var collectionTracks: List<TrackItem> = emptyList()
    private val lyrics = mutableListOf<LyricLine>()

    /** 解析器实例会被复用（MultiPlatformParser 按平台单例），每次解析前清空状态 */
    private fun resetState() {
        title = ""
        coverUrl = null
        audioUrl = null
        videoUrl = null
        authorNickname = ""
        authorId = null
        trackId = null
        videoIdFromData = null
        durationSec = 0.0
        resolvedUrl = null
        collectionName = ""
        collectionTracks = emptyList()
        lyrics.clear()
    }

    private companion object {
        const val TAG = "QiShuiMusicParser"
        const val SEO_TRACK_API = "https://beta-luna.douyin.com/luna/h5/seo_track"

        val MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

        val PAGE_HEADERS = mapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to "https://music.douyin.com/"
        )

        val SEO_HEADERS = mapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to "https://music.douyin.com/",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val URL_IN_TEXT = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")
        val ROUTER_DATA = Regex("_ROUTER_DATA\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
        val TRACK_ID_PARAM = Regex("(?:track_id|ugc_video_id)=([^&#\\s]+)")
        val PATH_PREFIXES = listOf("/track/", "/video/")
        val LRC_PATTERN =
            Regex("\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?]\\s*(.*)")
        val COLLECTION_KEYS = listOf(
            "playList", "playlist", "playListOptions", "playlistOptions",
            "trackList", "songList", "songs", "musicList"
        )
        val DURATION_KEYS = listOf("duration", "duration_ms", "music_duration", "format_duration_ms", "video_time")
    }
}