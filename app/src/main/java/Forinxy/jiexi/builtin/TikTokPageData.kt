package Forinxy.jiexi.builtin

import com.google.gson.JsonObject
import java.util.regex.Pattern

/**
 * TikTok 页面数据提取的纯函数工具（不依赖 Android 类，可在 JVM 单测覆盖）。
 *
 * TikTok 网页端把作品数据放在
 * `<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">{...}</script>`，
 * 路径为 `__DEFAULT_SCOPE__ -> webapp.video-detail -> itemInfo -> itemStruct`。
 */
internal object TikTokPageData {

    /** 分享文案里抠出的 URL */
    private val urlInTextPattern: Pattern =
        Pattern.compile("https?://[^\\s\\u4e00-\\u9fa5'\"<>]+")

    /** 作品页路径中的作品 ID：/video/123、/photo/123 */
    private val mediaIdPattern: Pattern = Pattern.compile("/(?:video|photo)/(\\d+)")

    /** __UNIVERSAL_DATA_FOR_REHYDRATION__ 数据节点 */
    private val universalDataPattern: Pattern =
        Pattern.compile("<script\\s+id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>([\\s\\S]*?)</script>")

    /** 从分享文案/输入中提取第一个 http(s) 链接 */
    fun extractFirstUrl(input: String): String? {
        if (input.isBlank()) return null
        val matcher = urlInTextPattern.matcher(input.trim())
        return if (matcher.find()) matcher.group() else null
    }

    /** 从作品页 URL 提取作品 ID（短链要先跟随重定向） */
    fun extractMediaId(url: String): String? {
        val matcher = mediaIdPattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    /** 从页面 HTML 提取 __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON 根对象 */
    fun extractUniversalData(html: String): JsonObject? {
        val matcher = universalDataPattern.matcher(html)
        if (!matcher.find()) return null
        val raw = matcher.group(1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return parseJsonObj(raw)
    }

    /** 从 UNIVERSAL 根对象定位 itemStruct（兼容桌面版与移动端 reflow 两种 scope 键） */
    fun extractItemStruct(root: JsonObject): JsonObject? {
        val scope = root.jObj("__DEFAULT_SCOPE__") ?: return null
        for (key in ITEM_SCOPE_KEYS) {
            val item = scope.jObj(key)?.jObj("itemInfo")?.jObj("itemStruct")
            if (item != null) return item
        }
        return null
    }

    /** SIGI_STATE 兜底：旧版页面结构，ItemModule 按作品 ID 索引 */
    fun extractItemStructFromSigi(html: String): JsonObject? {
        val matcher = Pattern.compile(
            "<script\\s+id=\"SIGI_STATE\"[^>]*>([\\s\\S]*?)</script>"
        ).matcher(html)
        if (!matcher.find()) return null
        val raw = matcher.group(1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val root = parseJsonObj(raw) ?: return null
        val module = root.jObj("ItemModule") ?: return null
        // 任取一个条目（页面只会渲染一个作品）
        return module.entrySet().firstOrNull()?.value?.asObjOrNull()
    }

    private val ITEM_SCOPE_KEYS = arrayOf(
        "webapp.video-detail",
        "webapp.reflow.video.detail"
    )

    /** 解析 Set-Cookie 头为 name=value 对（TikTok 会用 HttpOnly 等属性包装） */
    fun parseSetCookie(header: String): Pair<String, String>? {
        val first = header.trim().split(";").firstOrNull()?.trim().orEmpty()
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (name.isEmpty() || value.isEmpty()) return null
        return name to value
    }

    /** 是否为 Slardar WAF JS 挑战壳页（需真实浏览器执行 JS 才能通过，OkHttp 拿不到数据） */
    fun isWafChallengePage(html: String): Boolean {
        return html.contains("_wafchallengeid") ||
            html.contains("slardar_us_waf") ||
            html.contains("browser.web.pre.js")
    }

    /** 按分辨率高度生成画质标签（Unknown 用 "高清" 兜底，交给归一化排序） */
    fun qualityLabel(height: Int?): String {
        return if (height != null && height > 0) "${height}p" else "高清"
    }
}
