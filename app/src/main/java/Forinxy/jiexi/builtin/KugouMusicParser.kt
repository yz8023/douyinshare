package Forinxy.jiexi.builtin

import android.util.Log
import com.google.gson.JsonObject
import java.net.URI
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * 酷狗音乐解析器：MV 走签名接口 m3ws.kugou.com，歌曲走分享页 phpParam。
 * 按 Python 参考实现移植。
 */
internal class KugouMusicParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.KUGOU_MUSIC

    private val mvApiUrl = "https://m3ws.kugou.com/api/v1/mv/infov2"
    private val signatureSalt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private val mobileHeaders = mapOf(
        "User-Agent" to PlatformHttp.MOBILE_UA,
        "Referer" to "https://m.kugou.com/"
    )

    private val desktopMvPattern = Pattern.compile("/mvweb/html/mv_([0-9a-f]{32})\\.html")
    private val phpParamPattern = Pattern.compile("var\\s+phpParam\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "kugou parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val link = input.trim()
        val media = detectMedia(link) ?: return failResponse("无法识别酷狗音乐链接")
        val md = if (media.first == "mv") {
            fetchMv(media.second) ?: return failResponse("MV不存在或已删除")
        } else {
            fetchSong(link) ?: return failResponse("歌曲不存在或已删除")
        }
        md.inputUrl = input
        return md.toServerJson()
    }

    private fun detectMedia(input: String): Pair<String, String>? {
        if (input.isBlank()) return null
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase()
        val m = desktopMvPattern.matcher(path)
        if (m.find()) return "mv" to (m.group(1) ?: return null)
        val params = parseQuery(uri.query.orEmpty())
        if (path.contains("/mv")) {
            params["hash"]?.let { return "mv" to it }
        }
        if (path.contains("/share/song") || path.contains("/song")) {
            params["chain"]?.let { return "song" to it }
        }
        return null
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (value.isNotBlank()) map[key] = value
        }
        return map
    }

    // ========== MV ==========

    private fun fetchMv(hash: String): MediaData? {
        val timestamp = System.currentTimeMillis().toString()
        val params = sortedMapOf(
            "cmd" to "100",
            "hash" to hash,
            "ext" to "mp4",
            "ismp3" to "1",
            "ssl" to "1",
            "srcappid" to "2919",
            "clientver" to "20000",
            "clienttime" to timestamp,
            "mid" to timestamp,
            "uuid" to timestamp,
            "dfid" to "-"
        )
        val source = signatureSalt + params.entries.joinToString("") { "${it.key}=${it.value}" } + signatureSalt
        params["signature"] = md5Hex(source)

        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val resp = http.get("$mvApiUrl?$query", mobileHeaders) ?: return null
        if (resp.statusCode != 200) return null
        val payload = parseJsonObj(resp.body) ?: return null
        val errcode = payload.jLong("errcode")
        if (errcode != null && errcode != 0L) return null

        val streams = extractMvStreams(payload.jObj("mvdata"))
        val play = streams.firstOrNull() ?: return null

        val md = MediaData()
        md.type = "video"
        md.videoId = hash
        md.title = payload.jStr("songname") ?: "无标题"
        md.author = payload.jStr("singer") ?: "未知作者"
        md.authorUid = payload.jLong("id")?.toString()
        md.cover = payload.jStr("mvicon")?.replace("{size}", "400")

        md.playUrl = play
        md.rawPlayUrl = play
        streams.forEachIndexed { index, url ->
            md.addQuality(label = "画质${index + 1}", ratio = "default", url = url)
        }
        return md
    }

    private fun extractMvStreams(mvdata: JsonObject?): List<String> {
        if (mvdata == null) return emptyList()
        val urls = mutableListOf<String>()
        for (key in listOf("sq", "rq", "le", "sd")) {
            val item = mvdata.jObj(key) ?: continue
            val candidates = mutableListOf<String>()
            item.jStr("downurl")?.let { candidates.add(it) }
            val backups = item.jArr("backupdownurl")
            if (backups != null) {
                for (element in backups) {
                    element.asStrOrNull()?.let { candidates.add(it) }
                }
            }
            for (url in candidates) {
                if (url.startsWith("http") && !urls.contains(url)) urls.add(url)
            }
        }
        return urls
    }

    // ========== 歌曲 ==========

    private fun fetchSong(url: String): MediaData? {
        val resp = http.get(url, mobileHeaders) ?: return null
        if (resp.statusCode != 200 || resp.body.isBlank()) return null
        val m = phpParamPattern.matcher(resp.body)
        if (!m.find()) return null
        val payload = parseJsonObj(m.group(1)) ?: return null
        val data = payload.jObj("song_info")?.jObj("data") ?: return null

        val payType = data.jRawStr("pay_type")
        if (data.jRawStr("error") != null || payType != null &&
            payType != "0" && payType != "null" && payType != "None"
        ) {
            return null
        }
        val audioUrl = data.jStr("url") ?: return null
        if (!audioUrl.startsWith("http")) return null

        val md = MediaData()
        md.type = "music"
        md.title = data.jStr("songName") ?: data.jStr("fileName") ?: "无标题"
        md.cover = data.jStr("album_img")?.replace("{size}", "400")
            ?: data.jStr("imgUrl")?.replace("{size}", "400")

        val author = data.jArr("authors")?.firstObjOrNull()
        md.author = author?.jStr("author_name") ?: author?.jStr("name")
            ?: data.jStr("singerName") ?: "未知作者"
        md.authorUid = author?.jLong("author_id")?.toString() ?: author?.jLong("id")?.toString()

        md.playUrl = audioUrl
        md.rawPlayUrl = audioUrl
        md.addQuality(label = "默认", ratio = "default", url = audioUrl)
        return md
    }

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "KugouMusicParser"
    }
}
