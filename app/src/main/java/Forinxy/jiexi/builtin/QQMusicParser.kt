package Forinxy.jiexi.builtin

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.net.URI
import java.util.regex.Pattern

/**
 * QQ音乐解析器：通过 musicu.fcg 协议接口获取歌曲详情/播放地址/歌词，
 * 或 MV 信息与多清晰度视频。按 Python 参考实现移植。
 */
internal class QQMusicParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.QQ_MUSIC

    private val apiUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    private val songHeaders = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to "https://y.qq.com/",
        "Content-Type" to "application/json"
    )

    private val mvHeaders = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://y.qq.com/",
        "Content-Type" to "application/json"
    )

    private val songmidPattern = Pattern.compile("/(?:songDetail|song)/([A-Za-z0-9]+)(?:/|$)")
    private val mvPattern = Pattern.compile("/(?:mv|mvDetail)/([A-Za-z0-9]+)(?:/|$)")

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "qqmusic parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val link = input.trim()
        var media = detectMedia(link)
        if (media == null) {
            // 短链（c6.y.qq.com/base/fcgi-bin/u）需跟随重定向后再识别
            val pageResp = http.get(link, songHeaders) ?: return failResponse("无法识别QQ音乐链接")
            if (pageResp.statusCode != 200) return failResponse("无法识别QQ音乐链接")
            media = detectMedia(pageResp.finalUrl)
        }
        media ?: return failResponse("无法识别QQ音乐链接")

        val md = if (media.isMv) {
            fetchMv(media.id) ?: return failResponse("MV不存在或已删除")
        } else {
            fetchSong(media.songMid, media.songId) ?: return failResponse("歌曲不存在或已删除")
        }
        md.inputUrl = input
        return md.toServerJson()
    }

    // ========== 媒体识别 ==========

    private data class MediaRef(
        val isMv: Boolean,
        val id: String = "",
        val songMid: String? = null,
        val songId: String? = null
    )

    private fun detectMedia(input: String): MediaRef? {
        if (input.isBlank()) return null
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val query = uri.query.orEmpty()
        val path = uri.path.orEmpty()

        val params = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (value.isNotBlank()) params[key] = value
        }

        val vid = params["vid"]
        if (vid != null) return MediaRef(isMv = true, id = vid)
        val m1 = mvPattern.matcher(path)
        if (m1.find()) return MediaRef(isMv = true, id = m1.group(1) ?: return null)

        val songMid = params["songmid"] ?: params["songMid"]
        val songId = params["songid"] ?: params["songId"] ?: params["id"]
        if (songMid != null || songId != null) {
            return MediaRef(isMv = false, songMid = songMid, songId = songId)
        }
        val m2 = songmidPattern.matcher(path)
        if (m2.find()) return MediaRef(isMv = false, songMid = m2.group(1))
        if (path.contains("playsong")) {
            return MediaRef(isMv = false, songMid = songMid, songId = songId)
        }
        return null
    }

    // ========== 歌曲 ==========

    private fun fetchSong(songMid: String?, songId: String?): MediaData? {
        val numericId = songId?.toLongOrNull() ?: 0L
        val payload = JsonObject()
        payload.add("comm", jsonComm())
        val songInfo = JsonObject()
        songInfo.addProperty("module", "music.pf_song_detail_svr")
        songInfo.addProperty("method", "get_song_detail_yqq")
        val songInfoParam = JsonObject()
        songInfoParam.addProperty("song_mid", songMid.orEmpty())
        songInfoParam.addProperty("song_id", numericId)
        songInfo.add("param", songInfoParam)
        payload.add("songinfo", songInfo)

        val playUrl = JsonObject()
        playUrl.addProperty("module", "vkey.GetVkeyServer")
        playUrl.addProperty("method", "CgiGetVkey")
        val playParam = JsonObject()
        val mids = JsonArray()
        songMid?.let { mids.add(it) }
        playParam.add("songmid", mids)
        val types = JsonArray()
        types.add(0)
        playParam.add("songtype", types)
        playParam.addProperty("guid", "2333333333")
        playParam.addProperty("uin", "0")
        playParam.addProperty("loginflag", 1)
        playParam.addProperty("platform", "20")
        playUrl.add("param", playParam)
        payload.add("playUrl", playUrl)

        val lyric = JsonObject()
        lyric.addProperty("module", "music.musichallSong.PlayLyricInfo")
        lyric.addProperty("method", "GetPlayLyricInfo")
        val lyricParam = JsonObject()
        lyricParam.addProperty("songMID", songMid.orEmpty())
        lyricParam.addProperty("songID", numericId)
        lyric.add("param", lyricParam)
        payload.add("lyric", lyric)

        val resp = http.postJson(apiUrl, songHeaders, payload.toString()) ?: return null
        if (resp.statusCode != 200) return null
        val result = parseJsonObj(resp.body) ?: return null

        val trackInfo = result.jObj("songinfo")?.jObj("data")?.jObj("track_info")
        val playData = result.jObj("playUrl")?.jObj("data")
        val lyricB64 = result.jObj("lyric")?.jObj("data")?.jStr("lyric")

        val md = MediaData()
        md.type = "music"
        md.videoId = songId ?: songMid.orEmpty()

        if (trackInfo != null) {
            md.title = trackInfo.jStr("name") ?: trackInfo.jStr("title") ?: "无标题"
            val album = trackInfo.jObj("album")
            val albumMid = album?.jStr("mid")
                ?: album?.jRawStr("pmid")?.trimEnd('_', '1')?.takeIf { it.isNotBlank() }
            if (albumMid != null) {
                md.cover = "https://y.qq.com/music/photo_new/T002R800x800M000$albumMid.jpg"
            }
            val singer = trackInfo.jArr("singer")?.firstObjOrNull()
                ?: trackInfo.jArr("singers")?.firstObjOrNull()
            if (singer != null) {
                md.author = singer.jStr("name") ?: singer.jStr("title") ?: "未知作者"
                md.authorUid = singer.jRawStr("mid") ?: singer.jLong("id")?.toString()
            }
        } else {
            md.title = "QQ音乐"
        }

        var audioUrl: String? = null
        val sips = playData?.jArr("sip")
        val midurl = playData?.jArr("midurlinfo")
        if (midurl != null && midurl.size() > 0 && midurl[0].isJsonObject) {
            val purl = midurl[0].asJsonObject.jStr("purl")
            if (purl != null && sips != null && sips.size() > 0) {
                val sip = sips.asSequence()
                    .mapNotNull { it.asStrOrNull() }
                    .firstOrNull { it.startsWith("http") } ?: sips.asSequence().mapNotNull { it.asStrOrNull() }.firstOrNull()
                if (sip != null) audioUrl = sip + purl
            }
        }
        md.playUrl = audioUrl
        md.rawPlayUrl = audioUrl
        if (audioUrl != null) md.addQuality(label = "默认", ratio = "default", url = audioUrl)

        lyricB64?.let { encoded ->
            runCatching {
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                parseLrc(decoded).forEach { md.lyrics.add(it) }
            }
        }
        return md
    }

    private fun jsonComm(): JsonObject {
        val comm = JsonObject()
        comm.addProperty("cv", 4747474)
        comm.addProperty("ct", 24)
        comm.addProperty("format", "json")
        comm.addProperty("inCharset", "utf-8")
        comm.addProperty("outCharset", "utf-8")
        comm.addProperty("notice", 0)
        comm.addProperty("platform", "yqq.json")
        comm.addProperty("needNewCode", 1)
        comm.addProperty("uin", 0)
        return comm
    }

    private val lrcLinePattern = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)")

    private fun parseLrc(lrcText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in lrcText.split("\n")) {
            val m = lrcLinePattern.matcher(raw.trim())
            if (m.find()) {
                val text = m.group(3)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val minutes = m.group(1)?.toDoubleOrNull() ?: 0.0
                    val seconds = m.group(2)?.toDoubleOrNull() ?: 0.0
                    val start = (minutes * 60 + seconds)
                    lines.add(LyricLine(text = text, start = kotlin.math.round(start * 1000) / 1000.0))
                }
            }
        }
        return lines
    }

    // ========== MV ==========

    private fun fetchMv(vid: String): MediaData? {
        val payload = JsonObject()
        val comm = JsonObject()
        comm.addProperty("ct", 24)
        comm.addProperty("cv", 0)
        payload.add("comm", comm)

        val mvInfo = JsonObject()
        mvInfo.addProperty("module", "video.VideoDataServer")
        mvInfo.addProperty("method", "get_video_info_batch")
        val mvInfoParam = JsonObject()
        val vids = JsonArray()
        vids.add(vid)
        mvInfoParam.add("vidlist", vids)
        val required = JsonArray()
        listOf(
            "vid", "type", "sid", "cover_pic", "duration", "singers", "video_pay",
            "hint", "code", "msg", "name", "desc", "playcnt", "pubdate", "isfav", "gmid"
        ).forEach { required.add(it) }
        mvInfoParam.add("required", required)
        mvInfo.add("param", mvInfoParam)
        payload.add("mvInfo", mvInfo)

        val mvUrl = JsonObject()
        mvUrl.addProperty("module", "gosrf.Stream.MvUrlProxy")
        mvUrl.addProperty("method", "GetMvUrls")
        val mvUrlParam = JsonObject()
        val mvVids = JsonArray()
        mvVids.add(vid)
        mvUrlParam.add("vids", mvVids)
        mvUrlParam.addProperty("request_typet", 10001)
        mvUrl.add("param", mvUrlParam)
        payload.add("mvUrl", mvUrl)

        val resp = http.postJson(apiUrl, mvHeaders, payload.toString()) ?: return null
        if (resp.statusCode != 200) return null
        val result = parseJsonObj(resp.body) ?: return null

        val metadata = result.jObj("mvInfo")?.jObj("data")?.jObj(vid)
        val streams = result.jObj("mvUrl")?.jObj("data")?.jObj(vid)?.let { extractStreams(it) }
            ?: emptyList()
        val play = streams.firstOrNull() ?: return null

        val md = MediaData()
        md.type = "video"
        md.videoId = vid
        md.title = metadata?.jStr("name") ?: metadata?.jStr("raw_name") ?: "无标题"
        md.cover = metadata?.jStr("cover_pic") ?: metadata?.jStr("first_frame_pic")
        val singer = metadata?.jArr("singers")?.firstObjOrNull()
            ?: metadata?.jArr("related_singers")?.firstObjOrNull()
        md.author = singer?.jStr("name") ?: singer?.jStr("title")
            ?: metadata?.jStr("uploader_nick") ?: "未知作者"
        md.authorUid = singer?.jLong("id")?.toString()
            ?: singer?.jRawStr("mid") ?: metadata?.jRawStr("uploader_encuin")

        md.playUrl = play
        md.rawPlayUrl = play
        streams.forEachIndexed { index, url ->
            md.addQuality(label = "画质${index + 1}", ratio = "default", url = url)
        }
        return md
    }

    /** 依次取第一个非空 JsonArray（参考实现 `url or freeflow_url or comm_url or []`） */
    private fun firstNonEmptyArray(item: JsonObject, vararg keys: String): JsonArray? {
        for (key in keys) {
            val arr = item.jArr(key)
            if (arr != null && arr.size() > 0) return arr
        }
        return null
    }

    private fun extractStreams(streamData: JsonObject): List<String> {
        val ranked = mutableListOf<Pair<Long, String>>()
        for (kind in listOf("mp4", "hls")) {
            val items = streamData.jArr(kind) ?: continue
            for (element in items) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject
                val code = item.jLong("code")
                if (code != null && code != 0L) continue
                // 参考实现用 or 语义：url/freeflow_url/comm_url 依次取第一个非空数组
                val candidates = firstNonEmptyArray(item, "url", "freeflow_url", "comm_url")
                var url: String? = null
                if (candidates != null) {
                    for (c in candidates) {
                        val value = c.asStrOrNull()
                        if (value != null && value.startsWith("http")) {
                            url = value
                            break
                        }
                    }
                }
                if (url == null) {
                    val m3u8 = item.jStr("m3u8")
                    if (m3u8 != null && m3u8.startsWith("http")) url = m3u8
                }
                if (url != null) {
                    val quality = item.jLong("filetype") ?: item.jLong("newFileType") ?: 0L
                    ranked.add(quality to url)
                }
            }
        }
        ranked.sortByDescending { it.first }
        val urls = mutableListOf<String>()
        for ((_, url) in ranked) {
            if (!urls.contains(url)) urls.add(url)
        }
        return urls
    }

    private companion object {
        const val TAG = "QQMusicParser"
    }
}
