package Forinxy.jiexi.builtin

import android.util.Log
import java.util.regex.Pattern

/**
 * 梨视频解析器：调用 videoStatus.jsp 获取视频信息，替换 srcUrl 中的
 * 分段编号为 cont-{id} 得到真实直链；标题/作者从落地页 HTML 正则提取。
 */
internal class LishipinParser(private val http: PlatformHttp) : PlatformParser {

    override val platform: Platform get() = Platform.LISHIPIN

    private val digitPattern = Pattern.compile("\\d+")
    private val srcUrlPattern = Pattern.compile("(\\d+)-(\\d+-hd\\.mp4)")

    override fun parse(
        input: String,
        useCookie: Boolean,
        original: Boolean,
        highest: Boolean
    ): String {
        return try {
            doParse(input)
        } catch (e: Throwable) {
            Log.w(TAG, "lishipin parse failed", e)
            failResponse("解析失败，请稍后重试")
        }
    }

    private fun doParse(input: String): String {
        val link = input.trim()
        val videoId = extractVideoId(link)
            ?: return failResponse("无法识别梨视频链接")

        val pageResp = http.get(link, headersFor(link))
        val html = pageResp?.body.orEmpty()

        val apiResp = http.get(
            "https://www.pearvideo.com/videoStatus.jsp?contId=$videoId&mrd=${randomMrd()}",
            headersFor(link)
        ) ?: return failResponse("视频不存在或已删除")
        if (apiResp.statusCode != 200) return failResponse("视频不存在或已删除")

        val data = parseJsonObj(apiResp.body) ?: return failResponse("视频不存在或已删除")
        val videoInfo = data.jObj("videoInfo") ?: return failResponse("视频不存在或已删除")

        val srcUrl = videoInfo.jStr("srcUrl")
            ?: return failResponse("无法获取播放地址")
        val play = rewriteSrcUrl(srcUrl, videoId)

        val md = MediaData()
        md.type = "video"
        md.inputUrl = input
        md.videoId = videoId
        md.title = extractTitle(html) ?: "无标题"
        md.cover = videoInfo.jStr("video_image")

        val author = extractAuthor(html)
        md.author = author.first.ifEmpty { "未知作者" }
        md.authorUid = author.second

        md.playUrl = play
        md.rawPlayUrl = play
        md.addQuality(label = "默认", ratio = "default", url = play)
        return md.toServerJson()
    }

    private fun headersFor(url: String): Map<String, String> = mapOf(
        "User-Agent" to PlatformHttp.PC_UA,
        "Referer" to url
    )

    private fun extractVideoId(input: String): String? {
        if (input.isBlank()) return null
        val m = digitPattern.matcher(input)
        return if (m.find()) m.group() else null
    }

    private fun randomMrd(): String = String.format("%.4f", Math.random())

    /** 参考实现把 srcUrl 中的 {n}-{n}-hd.mp4 替换为 cont-{id}-{n}-hd.mp4 */
    private fun rewriteSrcUrl(srcUrl: String, videoId: String): String {
        val m = srcUrlPattern.matcher(srcUrl)
        return m.replaceAll("cont-$videoId-$2")
    }

    private val summaryPattern = Pattern.compile("<div[^>]*class=[\"']summary[\"'][^>]*>([\\s\\S]*?)</div>")

    private fun extractTitle(html: String): String? {
        if (html.isBlank()) return null
        val m = summaryPattern.matcher(html)
        if (!m.find()) return null
        return stripTags(m.group(1) ?: "").trim().ifEmpty { null }
    }

    private val thiscatPattern = Pattern.compile("<div[^>]*class=[\"']thiscat[\"'][^>]*>([\\s\\S]*?)</div>\\s*</div>")
    private val colNamePattern = Pattern.compile("class=[\"']col-name[\"'][^>]*>([\\s\\S]*?)</div>")
    private val imgSrcPattern = Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*>")
    private val authorIdPattern = Pattern.compile("data-userid=[\"']?(\\d+)[\"'\\s]")
    private val authorHrefPattern = Pattern.compile("author_(\\d+)")

    private fun extractAuthor(html: String): Pair<String, String> {
        if (html.isBlank()) return "" to ""
        val blockMatcher = thiscatPattern.matcher(html)
        val block = if (blockMatcher.find()) blockMatcher.group(1) ?: "" else html
        val name = colNamePattern.matcher(block).let { if (it.find()) stripTags(it.group(1) ?: "").trim() else "" }
        val avatar = imgSrcPattern.matcher(block).let { if (it.find()) it.group(1) ?: "" else "" }
        var authorId = authorIdPattern.matcher(block).let { if (it.find()) it.group(1) ?: "" else "" }
        if (authorId.isEmpty()) {
            authorId = authorHrefPattern.matcher(block).let { if (it.find()) it.group(1) ?: "" else "" }
        }
        return name to authorId
    }

    private fun stripTags(text: String): String =
        text.replace(Pattern.compile("<[^>]+>").toRegex(), " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TAG = "LishipinParser"
    }
}
