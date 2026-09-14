package Forinxy.jiexi

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import Forinxy.jiexi.data.GalleryMedia
import Forinxy.jiexi.data.ParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 不依赖服务器的本地解析：通过 App 内置 WebView 会话（含登录 cookie）请求
 * douyin aweme detail 接口，再把作品 JSON 映射为 ParseResult.Success。
 * 仅在用户未配置服务器（apiBase 为占位地址）时启用，作为「App 内置登录抓 cookie + 本地解析」的默认通道。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class LocalParseEngine(
    private val application: Application
) {
    private val bridge = DouyinAuthorWebApiBridge(application.applicationContext)
    private val gson = Gson()
    private val mobileUserAgent: String by lazy { NativeLib.getParserUserAgent() }
    // 同时支持视频（/video/）与图集图文（/note/）路径
    private val awemeIdPattern = Pattern.compile("/(?:video|note)/(\\d{15,21})")
    private val plainIdPattern = Pattern.compile("^(\\d{15,21})$")
    // 重定向 URL 兜底：直接取第一个 15-21 位纯数字串
    private val bareIdPattern = Pattern.compile("(\\d{15,21})")
    private val shortLinkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    suspend fun parse(input: String): ParseResult = withContext(Dispatchers.IO) {
        val awemeId = runCatching { resolveAwemeId(input) }.getOrNull()
            ?: return@withContext ParseResult.Error("本地解析失败：无法识别作品 ID")
        val body = runCatching { bridge.fetchAwemeDetail(awemeId) }
            .getOrElse { throwable ->
                Log.w("LocalParseEngine", "Aweme detail fetch failed: $awemeId", throwable)
                return@withContext ParseResult.Error("本地解析失败：${throwable.message ?: "获取作品详情失败"}")
            }
        val root = runCatching { gson.fromJson<Map<String, Any>>(body, mapType) }.getOrNull()
            ?: return@withContext ParseResult.Error("本地解析失败：响应格式异常")
        val item = root.dig<Map<String, Any>>("aweme_detail")
            ?: root.dig<Map<String, Any>>("item_detail")
            ?: return@withContext ParseResult.Error("本地解析失败：未找到作品数据")
        runCatching { buildParseResult(item, input, awemeId) }
            .getOrElse { throwable ->
                Log.w("LocalParseEngine", "Aweme mapping failed: $awemeId", throwable)
                ParseResult.Error(throwable.message ?: "本地解析失败")
            }
    }

    suspend fun destroy() {
        runCatching { bridge.destroy() }
            .onFailure { Log.w("LocalParseEngine", "Destroy failed", it) }
    }

    /** 从输入（分享链接 / 短链 / 纯 ID）解析出 awemeId */
    private suspend fun resolveAwemeId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return null
        }
        awemeIdPattern.matcher(trimmed).let { if (it.find()) return it.group(1) }
        if (plainIdPattern.matcher(trimmed).matches()) {
            return trimmed
        }
        // douyin 短链（v.douyin.com 等）先跟随重定向拿最终作品页再提取 ID
        val host = runCatching { java.net.URI(trimmed).host }.getOrNull()
        if (!host.isNullOrBlank() && host.contains("douyin.com", ignoreCase = true)) {
            return resolveViaHttpRedirect(trimmed)
        }
        // 兜底：从分享文案中抠出链接再尝试
        val linkFromText = inputRegexFallback(trimmed)
        if (linkFromText != null) {
            awemeIdPattern.matcher(linkFromText).let { if (it.find()) return it.group(1) }
            val linkHost = runCatching { java.net.URI(linkFromText).host }.getOrNull()
            if (!linkHost.isNullOrBlank() && linkHost.contains("douyin.com", ignoreCase = true)) {
                return resolveViaHttpRedirect(linkFromText)
            }
        }
        return null
    }

    /** 从分享文案中抠出第一个链接，再尝试按链接解析 */
    private fun inputRegexFallback(text: String): String? {
        return Regex("https?://[^\\s\\u4e00-\\u9fa5]+").find(text)?.value
    }

    private suspend fun resolveViaHttpRedirect(rawUrl: String): String? = withContext(Dispatchers.IO) {
        val finalUrl = runCatching {
            val request = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", mobileUserAgent)
                .header("Referer", "https://www.douyin.com/")
                .build()
            shortLinkClient.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }.getOrNull() ?: return@withContext null
        awemeIdPattern.matcher(finalUrl).let { if (it.find()) return@withContext it.group(1) }
        // 兜底：从最终 URL 中取第一个 15-21 位数字串（图文 /note/ 路径等）
        bareIdPattern.matcher(finalUrl).let { if (it.find()) return@withContext it.group(1) }
        null
    }

    /** 与 AuthorBatchManager.parseAwemeItem 对齐但面向本地单条解析的最小映射 */
    private fun buildParseResult(
        item: Map<String, Any>,
        inputUrl: String,
        awemeId: String
    ): ParseResult.Success {
        val author = item.dig<String>("author", "nickname")
            ?: item.dig<String>("flat_author_name")
            ?: "未知作者"
        val authorUid = item.dig<String>("author", "uid")
            ?: item.dig<String>("flat_author_uid")
        val title = item.dig<String>("desc") ?: "无标题"
        val timestamp = (item.dig<Number>("create_time"))?.toLong()
            ?: (System.currentTimeMillis() / 1000)
        val galleryMedia = DouyinContentMapper.extractGalleryMedia(item)
        val cover = DouyinContentMapper.extractCoverUrl(item, galleryMedia)

        if (galleryMedia.isNotEmpty()) {
            return ParseResult.Success(
                author = author,
                authorUid = authorUid,
                authorSecUid = item.dig<String>("author", "sec_uid"),
                title = title,
                type = "image",
                playUrl = null,
                rawPlayUrl = null,
                images = galleryMedia.mapNotNull { it.imageUrl },
                galleryMedia = galleryMedia,
                timestamp = timestamp,
                videoId = awemeId,
                cover = cover,
                inputUrl = inputUrl,
                source = "local"
            )
        }

        val rawPlayUrl = DouyinContentMapper.extractRawPlayUrl(item)
            ?: throw IOException("本地解析失败：未找到播放地址")
        // 本地模式没有服务器 quality_list：把原始播放地址同时作为 preview / 下载地址，
        // originalPlayUrl 复用同一地址，保存原画质/最高画质时无需再请求服务器。
        return ParseResult.Success(
            author = author,
            authorUid = authorUid,
            authorSecUid = item.dig<String>("author", "sec_uid"),
            title = title,
            type = "video",
            playUrl = rawPlayUrl,
            rawPlayUrl = rawPlayUrl,
            originalPlayUrl = rawPlayUrl,
            images = null,
            galleryMedia = null,
            timestamp = timestamp,
            videoId = awemeId,
            cover = cover,
            inputUrl = inputUrl,
            source = "local"
        )
    }
}