package Forinxy.jiexi.builtin

import java.net.URI

/**
 * 支持解析的平台。识别规则以域名匹配为主，作品 ID 规则为辅。
 *
 * 识别顺序很重要：汽水音乐的 qishui.douyin.com / music.douyin.com 属于 douyin
 * 子域，必须先于抖音判定；短视频短链（b23.tv / t.cn / xhslink.com 等）按域名直接归类，
 * 具体作品 ID 由各平台解析器跟随重定向后提取。
 */
enum class Platform(val label: String) {
    DOUYIN("抖音"),
    BILIBILI("哔哩哔哩"),
    KUAISHOU("快手"),
    XIAOHONGSHU("小红书"),
    WEIBO("微博"),
    TOUTIAO("今日头条"),
    PIPIXIA("皮皮虾"),
    PIPIGAOXIAO("皮皮搞笑"),
    NETEASE_MUSIC("网易云音乐"),
    QISHUI_MUSIC("汽水音乐");

    val isMusic: Boolean
        get() = this == NETEASE_MUSIC || this == QISHUI_MUSIC

    companion object {
        /** 分享文案里抠出的 URL */
        private val urlInTextPattern = Regex("https?://[^\\s\\u4e00-\\u9fa5'\"]+")

        /** 纯抖音作品 ID（19 位纯数字） */
        private val douyinPlainIdPattern = Regex("^\\d{19}$")

        /** 哔哩哔哩 BV 号（裸 BV 号也直接识别） */
        private val bvidPattern = Regex("BV1[a-zA-Z0-9]{9}")

        /**
         * 识别输入所属平台。识别顺序：域名匹配（含无协议文本中的链接）→ 纯数字抖音
         * ID → 裸 bilibili BV 号。无法识别返回 null。
         */
        fun detect(input: String): Platform? {
            val trimmed = input?.trim().orEmpty()
            if (trimmed.isEmpty()) return null

            val host = extractHost(trimmed)
            if (host != null) {
                val platformByHost = matchHost(host)
                if (platformByHost != null) return platformByHost
            }

            // 无 URL 的纯输入兜底：抖音 19 位 ID / bilibili BV 号
            val link = urlInTextPattern.find(trimmed)?.value
            if (link != null) {
                extractHost(link)?.let { h ->
                    matchHost(h)?.let { return it }
                }
            }
            if (douyinPlainIdPattern.matches(trimmed)) return DOUYIN
            if (bvidPattern.containsMatchIn(trimmed)) return BILIBILI
            return null
        }

        /** 按 顺序 匹配域名（较特异平台在前） */
        private fun matchHost(host: String): Platform? {
            val h = host.lowercase()
            return when {
                h == "qishui.douyin.com" || h == "music.douyin.com" -> QISHUI_MUSIC
                hostsMatch(h, "douyin.com", "iesdouyin.com") -> DOUYIN
                hostsMatch(h, "bilibili.com", "b23.tv") -> BILIBILI
                hostsMatch(h, "kuaishou.com", "chenzhongtech.com") -> KUAISHOU
                hostsMatch(h, "xiaohongshu.com", "xhslink.com", "xhslink.cn") -> XIAOHONGSHU
                hostsMatch(h, "weibo.com", "weibo.cn", "t.cn") -> WEIBO
                h.endsWith("toutiao.com") -> TOUTIAO
                hostsMatch(h, "pipix.com") -> PIPIXIA
                hostsMatch(h, "pipigx.com", "ippzone.com") -> PIPIGAOXIAO
                hostsMatch(h, "music.163.com") -> NETEASE_MUSIC
                else -> null
            }
        }

        /** host 等于或属于任一权威域名 */
        private fun hostsMatch(host: String, vararg domains: String): Boolean {
            for (domain in domains) {
                if (host == domain || host.endsWith(".$domain")) return true
            }
            return false
        }

        /** 从输入（可能是分享文案）中提取第一个 http(s) 链接的 host */
        private fun extractHost(text: String): String? {
            val link = urlInTextPattern.find(text)?.value ?: return null
            return runCatching { URI(link).host }?.getOrNull()
        }
    }
}