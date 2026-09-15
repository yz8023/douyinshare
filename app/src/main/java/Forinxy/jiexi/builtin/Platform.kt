package Forinxy.jiexi.builtin

import java.net.URI

/**
 * 支持解析的平台。识别规则以域名匹配为主，作品 ID 规则为辅。
 *
 * 识别顺序很重要：汽水音乐的 qishui.douyin.com / music.douyin.com 属于 douyin
 * 子域，必须先于抖音判定；绿洲 oasis.weibo.cn 属于 weibo.cn 子域，必须先于微博；
 * 可灵AI klingai-share.kuaishou.com 属于快手子域，必须先于快手。短视频短链
 * （b23.tv / t.cn / xhslink.com 等）按域名直接归类，具体作品 ID 由各平台解析器
 * 跟随重定向后提取。
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
    QISHUI_MUSIC("汽水音乐"),
    // ---- media-parser 对齐补充的平台（本轮已实现）----
    XIGUA("西瓜视频"),
    HAOKAN("好看视频"),
    ZHIHU("知乎"),
    HUYA("虎牙"),
    LVZHOU("绿洲"),
    MEIPAI("美拍"),
    QUANMIN_KGE("全民K歌"),
    XINPIANCHANG("新片场"),
    ZUIYOU("最右"),
    QQ_MUSIC("QQ音乐"),
    KUGOU_MUSIC("酷狗音乐"),
    ACFUN("AcFun"),
    WEISHI("微视"),
    LISHIPIN("梨视频"),
    // ---- media-parser 对齐补充的平台（第二批）----
    XIANYU("闲鱼"),
    DEWU("得物"),
    PINECONE_MOMENT("松果时刻"),
    KWAIYING("快影"),
    PEIYINXIU("配音秀"),
    KLING("可灵AI"),
    SOUL("Soul"),
    // ---- media-parser 对齐补充的平台（第三批）----
    LOFTER("网易LOFTER"),
    HAILUO("海螺AI"),
    XIAOYUNQUE("小云雀AI"),
    // ---- media-parser 对齐补充的平台（第四批）----
    WECHAT_CHANNELS("视频号"),
    WECHAT_MP("微信公众号"),
    // ---- media-parser 对齐补充的平台（第五批）----
    FANQIE("番茄小说"),
    JIANYING("剪映"),
    TENCENT_CHANNEL("腾讯频道"),
    YUANBAO("腾讯元宝"),
    DOUBAO("豆包"),
    JIMENG("即梦AI"),
    QIANWEN("通义千问"),
    QUARK_AI("夸克AI"),
    PINDUODUO("拼多多"),
    BUTTERFLYAI("星绘AI"),
    CCTV("央视"),
    YANG_SHIPIN("央视频");

    val isMusic: Boolean
        get() = this == NETEASE_MUSIC || this == QISHUI_MUSIC ||
            this == QQ_MUSIC || this == KUGOU_MUSIC

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
                // ---- 已有平台（含子域特例优先）----
                h == "qishui.douyin.com" || h == "music.douyin.com" -> QISHUI_MUSIC
                hostsMatch(h, "klingai-share.kuaishou.com") -> KLING
                hostsMatch(h, "douyin.com", "iesdouyin.com") -> DOUYIN
                hostsMatch(h, "bilibili.com", "b23.tv") -> BILIBILI
                hostsMatch(h, "kuaishou.com", "chenzhongtech.com") -> KUAISHOU
                hostsMatch(h, "xiaohongshu.com", "xhslink.com", "xhslink.cn") -> XIAOHONGSHU
                hostsMatch(h, "oasis.weibo.cn") -> LVZHOU
                hostsMatch(h, "weibo.com", "weibo.cn", "t.cn") -> WEIBO
                hostsMatch(h, "toutiao.com", "snssdk.com") -> TOUTIAO
                hostsMatch(h, "pipix.com") -> PIPIXIA
                hostsMatch(h, "pipigx.com", "ippzone.com") -> PIPIGAOXIAO
                hostsMatch(h, "music.163.com", "163cn.tv") -> NETEASE_MUSIC

                // ---- 视频/社区平台 ----
                hostsMatch(h, "ixigua.com") -> XIGUA
                hostsMatch(
                    h, "haokan.baidu.com", "haokan.hao123.com", "mr.baidu.com",
                    "sv.baidu.com", "baijiahao.baidu.com"
                ) -> HAOKAN
                hostsMatch(h, "zhihu.com") -> ZHIHU
                hostsMatch(h, "huya.com", "hy.fan") -> HUYA
                hostsMatch(h, "meipai.com") -> MEIPAI
                hostsMatch(h, "xinpianchang.com") -> XINPIANCHANG
                hostsMatch(h, "izuiyou.com", "xiaochuankeji.cn") -> ZUIYOU
                hostsMatch(h, "acfun.cn") -> ACFUN
                hostsMatch(h, "pearvideo.com") -> LISHIPIN
                hostsMatch(h, "weishi.qq.com") -> WEISHI

                // ---- 第二批补充平台 ----
                hostsMatch(h, "pineconemoment.com") -> PINECONE_MOMENT
                hostsMatch(h, "peiyinxiu.com") -> PEIYINXIU
                hostsMatch(
                    h, "e.tb.cn", "m.tb.cn", "tb.cn", "goofish.com",
                    "market.m.taobao.com", "h5.m.goofish.com", "2.taobao.com"
                ) -> XIANYU
                hostsMatch(h, "dewu.com", "poizon.com", "dw4.co") -> DEWU
                hostsMatch(h, "kwaiying.com") -> KWAIYING
                hostsMatch(h, "soulsmile.cn") -> SOUL

                // ---- 第三批补充平台 ----
                hostsMatch(h, "lofter.com") -> LOFTER
                hostsMatch(h, "hailuoai.com", "hailuoai.video") -> HAILUO
                hostsMatch(h, "xiaoyunque.jianying.com", "xyq.jianying.com") -> XIAOYUNQUE

                // ---- 第四批补充平台 ----
                hostsMatch(h, "mp.weixin.qq.com") -> WECHAT_MP
                hostsMatch(
                    h, "channels.weixin.qq.com", "weixin.qq.com", "finder.video.qq.com"
                ) -> WECHAT_CHANNELS

                // ---- 第五批补充平台 ----
                hostsMatch(
                    h, "fqnovel.com", "novelquickapp.com", "zlink.fqnovel.com",
                    "qznovel.com", "changdunovel.com", "kylin.hainanyuyue.com",
                    "hainanyuyue.com"
                ) -> FANQIE
                hostsMatch(
                    h, "lv.ulikecam.com", "www.capcut.cn", "capcut.cn", "www.capcut.com", "capcut.com"
                ) -> JIANYING
                hostsMatch(h, "pd.qq.com") -> TENCENT_CHANNEL
                hostsMatch(h, "yb.tencent.com", "yuanbao.tencent.com") -> YUANBAO
                hostsMatch(h, "doubao.com") -> DOUBAO
                hostsMatch(
                    h, "jimeng.jianying.com", "jimeng.ai", "aiseet.atry.com"
                ) -> JIMENG
                hostsMatch(
                    h, "qianwen.com", "qianwen.aliyun.com", "tongyi.aliyun.com",
                    "qianwen.my.cn", "pages.tongyi.com", "tongyi.com"
                ) -> QIANWEN
                hostsMatch(h, "quark.cn", "act.quark.cn") -> QUARK_AI
                hostsMatch(
                    h, "yangkeduo.com", "pinduoduo.com"
                ) -> PINDUODUO
                hostsMatch(h, "butterflyai.cn") -> BUTTERFLYAI
                hostsMatch(h, "cctv.com", "cctv.cn", "cctvnews.cctv.com", "content-static.cctvnews.cctv.com") -> CCTV
                hostsMatch(h, "yspapp.cn", "yangshipin.cn") -> YANG_SHIPIN

                // ---- 音乐/音频平台 ----
                hostsMatch(h, "y.qq.com") -> QQ_MUSIC
                hostsMatch(h, "kg.qq.com", "kg2.qq.com", "kg3.qq.com", "static-play.kg.qq.com") -> QUANMIN_KGE
                hostsMatch(h, "kugou.com") -> KUGOU_MUSIC

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
