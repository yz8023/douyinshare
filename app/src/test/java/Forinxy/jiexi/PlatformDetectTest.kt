package Forinxy.jiexi

import Forinxy.jiexi.builtin.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 平台识别正反用例：域名匹配为主 + 作品 ID 规则为辅。
 * 覆盖全部 10 个支持平台 + 未知域名返回 null。
 */
class PlatformDetectTest {

    @Test
    fun douyin_video_url() {
        assertEquals(Platform.DOUYIN, Platform.detect("https://www.douyin.com/video/7234567890123456789"))
    }

    @Test
    fun douyin_share_text() {
        assertEquals(
            Platform.DOUYIN,
            Platform.detect("1.23 复制打开抖音，看看【我的作品】 https://v.douyin.com/abcdefg/")
        )
    }

    @Test
    fun douyin_plain_19_digit_id() {
        assertEquals(Platform.DOUYIN, Platform.detect("7234567890123456789"))
    }

    @Test
    fun douyin_short_v_douyin() {
        assertEquals(Platform.DOUYIN, Platform.detect("https://v.douyin.com/iK2mnWe/"))
    }

    @Test
    fun qishui_music_precedes_douyin() {
        assertEquals(
            Platform.QISHUI_MUSIC,
            Platform.detect("https://qishui.douyin.com/s/iX21ep91/")
        )
    }

    @Test
    fun qishui_music_music_douyin_domain() {
        assertEquals(
            Platform.QISHUI_MUSIC,
            Platform.detect("https://music.douyin.com/track/12345")
        )
    }

    @Test
    fun bilibili_full_video_url() {
        assertEquals(
            Platform.BILIBILI,
            Platform.detect("https://www.bilibili.com/video/BV1xx411c7mD/")
        )
    }

    @Test
    fun bilibili_short_b23() {
        assertEquals(Platform.BILIBILI, Platform.detect("https://b23.tv/abc123"))
    }

    @Test
    fun bilibili_bare_bvid() {
        assertEquals(Platform.BILIBILI, Platform.detect("BV1xx411c7mD"))
    }

    @Test
    fun kuaishou_short_link() {
        assertEquals(Platform.KUAISHOU, Platform.detect("https://v.kuaishou.com/abc123"))
    }

    @Test
    fun kuaishou_m_chenzhongtech() {
        assertEquals(
            Platform.KUAISHOU,
            Platform.detect("https://v.m.chenzhongtech.com/fw/photo/3x5abcxyz")
        )
    }

    @Test
    fun xiaohongshu_explore() {
        assertEquals(
            Platform.XIAOHONGSHU,
            Platform.detect("https://www.xiaohongshu.com/explore/641d1a000000000013012345")
        )
    }

    @Test
    fun xiaohongshu_short_link() {
        assertEquals(Platform.XIAOHONGSHU, Platform.detect("https://xhslink.com/a/AbCdEf"))
    }

    @Test
    fun weibo_status_url() {
        assertEquals(
            Platform.WEIBO,
            Platform.detect("https://weibo.com/1234567890/MnAbCdEfG")
        )
    }

    @Test
    fun weibo_tcn_short() {
        assertEquals(Platform.WEIBO, Platform.detect("https://t.cn/A6xYz12"))
    }

    @Test
    fun toutiao_video() {
        assertEquals(
            Platform.TOUTIAO,
            Platform.detect("https://www.toutiao.com/video/7334567890123456789/")
        )
    }

    @Test
    fun toutiao_a_link() {
        assertEquals(
            Platform.TOUTIAO,
            Platform.detect("https://www.toutiao.com/a7334567890123456789/")
        )
    }

    @Test
    fun pipixia_short() {
        assertEquals(Platform.PIPIXIA, Platform.detect("https://h5.pipix.com/s/abc123/"))
    }

    @Test
    fun pipixia_api_domain() {
        assertEquals(Platform.PIPIXIA, Platform.detect("https://api.pipix.com/x"))
    }

    @Test
    fun pipigaoxiao_short() {
        assertEquals(Platform.PIPIGAOXIAO, Platform.detect("https://h5.pipigx.com/pp/x/?pid=12345"))
    }

    @Test
    fun ippzone_domain() {
        assertEquals(
            Platform.PIPIGAOXIAO,
            Platform.detect("https://file.ippzone.com/img/view/id/abc")
        )
    }

    @Test
    fun netease_song_url() {
        assertEquals(
            Platform.NETEASE_MUSIC,
            Platform.detect("https://music.163.com/#/song?id=33894312")
        )
    }

    @Test
    fun netease_share_text() {
        assertEquals(
            Platform.NETEASE_MUSIC,
            Platform.detect("分享陶喆的单曲《就是爱你》: https://music.163.com/song?id=185711")
        )
    }

    @Test
    fun netease_mv_url() {
        assertEquals(
            Platform.NETEASE_MUSIC,
            Platform.detect("https://music.163.com/mv?id=12345")
        )
    }

    @Test
    fun unknown_youtube_url_returns_null() {
        assertNull(Platform.detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun unknown_tiktok_url_returns_null() {
        assertNull(Platform.detect("https://www.tiktok.com/@user/video/12345"))
    }

    @Test
    fun blank_input_returns_null() {
        assertNull(Platform.detect(""))
        assertNull(Platform.detect("   "))
    }

    @Test
    fun random_text_returns_null() {
        assertNull(Platform.detect("hello world 随便一段文字"))
    }

    @Test
    fun douyin_override_host_does_not_false_match_other() {
        // 含 douyin 但属其他平台的域名仍需精确识别
        assertEquals(Platform.QISHUI_MUSIC, Platform.detect("https://qishui.douyin.com/s/x/"))
    }

    @Test
    fun xigua_video() {
        assertEquals(
            Platform.XIGUA,
            Platform.detect("https://www.ixigua.com/7234567890123456789")
        )
    }

    @Test
    fun xigua_mobile_video() {
        assertEquals(
            Platform.XIGUA,
            Platform.detect("https://m.ixigua.com/video/7234567890123456789/")
        )
    }

    @Test
    fun haokan_video() {
        assertEquals(
            Platform.HAOKAN,
            Platform.detect("https://haokan.baidu.com/v?vid=1234567890")
        )
    }

    @Test
    fun haokan_hao123() {
        assertEquals(
            Platform.HAOKAN,
            Platform.detect("https://haokan.hao123.com/abc")
        )
    }

    @Test
    fun zhihu_answer() {
        assertEquals(
            Platform.ZHIHU,
            Platform.detect("https://www.zhihu.com/question/123456789/answer/987654321")
        )
    }

    @Test
    fun zhihu_zvideo() {
        assertEquals(
            Platform.ZHIHU,
            Platform.detect("https://www.zhihu.com/zvideo/1234567890123456789")
        )
    }

    @Test
    fun zhihu_article() {
        assertEquals(
            Platform.ZHIHU,
            Platform.detect("https://zhuanlan.zhihu.com/p/123456789")
        )
    }

    @Test
    fun huya_moment() {
        assertEquals(
            Platform.HUYA,
            Platform.detect("https://www.huya.com/moment/1234567890")
        )
    }

    @Test
    fun lvzhou_precedes_weibo() {
        // 绿洲域名是 weibo.cn 子域，必须优先识别为绿洲
        assertEquals(
            Platform.LVZHOU,
            Platform.detect("https://oasis.weibo.cn/v1/h5/share?sid=123456")
        )
    }

    @Test
    fun meipai_media() {
        assertEquals(
            Platform.MEIPAI,
            Platform.detect("https://www.meipai.com/media/123456789012345")
        )
    }

    @Test
    fun quanminkge_share() {
        assertEquals(
            Platform.QUANMIN_KGE,
            Platform.detect("https://kg.qq.com/node/play?s=abc123")
        )
    }

    @Test
    fun xinpianchang_article() {
        assertEquals(
            Platform.XINPIANCHANG,
            Platform.detect("https://www.xinpianchang.com/a12345678")
        )
    }

    @Test
    fun zuiyou_share() {
        assertEquals(
            Platform.ZUIYOU,
            Platform.detect("https://share.xiaochuankeji.cn/postDetail?pid=123456")
        )
    }

    @Test
    fun qqmusic_song() {
        assertEquals(
            Platform.QQ_MUSIC,
            Platform.detect("https://y.qq.com/n/ryqq/songDetail/004Z8Ihr0JIu5s")
        )
    }

    @Test
    fun qqmusic_mv() {
        assertEquals(
            Platform.QQ_MUSIC,
            Platform.detect("https://y.qq.com/n/ryqq/mvDetail/0020GSPf3gZGq0")
        )
    }

    @Test
    fun kugou_mv() {
        assertEquals(
            Platform.KUGOU_MUSIC,
            Platform.detect("https://www.kugou.com/mvweb/html/mv_1234567890abcdef.html")
        )
    }

    @Test
    fun kugou_song() {
        assertEquals(
            Platform.KUGOU_MUSIC,
            Platform.detect("https://m.kugou.com/share/song/abc123.html")
        )
    }

    @Test
    fun acfun_video() {
        assertEquals(
            Platform.ACFUN,
            Platform.detect("https://www.acfun.cn/v/ac12345678")
        )
    }

    @Test
    fun weishi_video() {
        assertEquals(
            Platform.WEISHI,
            Platform.detect("https://weishi.qq.com/abc123")
        )
    }

    @Test
    fun lishipin_video() {
        assertEquals(
            Platform.LISHIPIN,
            Platform.detect("https://www.pearvideo.com/video_1234567890")
        )
    }

    @Test
    fun kling_precedes_kuaishou() {
        // 可灵分享域名是快手子域，必须优先识别为可灵AI
        assertEquals(
            Platform.KLING,
            Platform.detect("https://klingai-share.kuaishou.com/creatives/abc?creative_id=123456")
        )
    }

    @Test
    fun kwaiying_share() {
        assertEquals(
            Platform.KWAIYING,
            Platform.detect("https://share.kwaiying.com/pc/index.html?id=1234567890")
        )
    }

    @Test
    fun peiyinxiu_share() {
        assertEquals(
            Platform.PEIYINXIU,
            Platform.detect("https://www.peiyinxiu.com/dub/123456")
        )
    }

    @Test
    fun pinecone_moment_share() {
        assertEquals(
            Platform.PINECONE_MOMENT,
            Platform.detect("https://m.pineconemoment.com/share/story/123456")
        )
    }

    @Test
    fun dewu_share() {
        assertEquals(
            Platform.DEWU,
            Platform.detect("https://m.dewu.com/pcapp/sns/detail/1234567890")
        )
    }

    @Test
    fun dewu_dw4_short() {
        assertEquals(
            Platform.DEWU,
            Platform.detect("https://dw4.co/AbCdEfG")
        )
    }

    @Test
    fun xianyu_short() {
        assertEquals(
            Platform.XIANYU,
            Platform.detect("https://e.tb.cn/x1y2z3")
        )
    }

    @Test
    fun xianyu_goofish() {
        assertEquals(
            Platform.XIANYU,
            Platform.detect("https://h5.m.goofish.com/item?id=1234567890")
        )
    }

    @Test
    fun soul_share() {
        assertEquals(
            Platform.SOUL,
            Platform.detect("https://w13.soulsmile.cn/post/detail.html?postIdEcpt=abc123")
        )
    }

    @Test
    fun lofter_post() {
        assertEquals(
            Platform.LOFTER,
            Platform.detect("https://www.lofter.com/share/post?id=123456789")
        )
    }

    @Test
    fun hailuo_share() {
        assertEquals(
            Platform.HAILUO,
            Platform.detect("https://hailuoai.com/share/ai-video/1234567890")
        )
    }

    @Test
    fun xiaoyunque_share() {
        assertEquals(
            Platform.XIAOYUNQUE,
            Platform.detect("https://xiaoyunque.jianying.com/s/abc123")
        )
    }

    @Test
    fun wechat_channels_url() {
        assertEquals(
            Platform.WECHAT_CHANNELS,
            Platform.detect("https://channels.weixin.qq.com/wechatfeed/feed/1234567890")
        )
    }

    @Test
    fun wechat_channels_share_text() {
        assertEquals(
            Platform.WECHAT_CHANNELS,
            Platform.detect(
                "一起来看视频号 #视频号 https://weixin.qq.com/sph/AabcDef12abcdefGh11a"
            )
        )
    }

    @Test
    fun wechat_channels_mp_precedes() {
        assertEquals(
            Platform.WECHAT_MP,
            Platform.detect("https://mp.weixin.qq.com/s/AbC123xyzQr4")
        )
    }

    @Test
    fun butterflyai_share() {
        assertEquals(
            Platform.BUTTERFLYAI,
            Platform.detect("https://www.butterflyai.cn/share/record/1234567890")
        )
    }

    @Test
    fun butterflyai_short_link() {
        assertEquals(
            Platform.BUTTERFLYAI,
            Platform.detect("https://s.butterflyai.cn/abc123")
        )
    }

    @Test
    fun cctv_news_article() {
        assertEquals(
            Platform.CCTV,
            Platform.detect("https://news.cctv.com/2026/09/15/ARTIabcdef1234567890.shtml")
        )
    }

    @Test
    fun cctv_yangshipin_covered_domain() {
        assertEquals(
            Platform.YANG_SHIPIN,
            Platform.detect("https://v.yangshipin.cn/share?vid=abcdef1234567890")
        )
    }

    @Test
    fun yangshipin_app_share() {
        assertEquals(
            Platform.YANG_SHIPIN,
            Platform.detect("https://yspapp.cn/video/1234567890")
        )
    }

    @Test
    fun fanqie_novel_share() {
        assertEquals(
            Platform.FANQIE,
            Platform.detect("https://novelquickapp.com/page/1234567890")
        )
    }

    @Test
    fun fanqie_short_drama() {
        assertEquals(
            Platform.FANQIE,
            Platform.detect("https://zlink.fqnovel.com/short/abc123")
        )
    }

    @Test
    fun fanqie_fqnovel_app_share() {
        assertEquals(
            Platform.FANQIE,
            Platform.detect("https://fqnovel.com/s/abc123")
        )
    }

    @Test
    fun fanqie_hongguoduanju() {
        assertEquals(
            Platform.FANQIE,
            Platform.detect("https://changdunovel.com/index.html?id=12345")
        )
    }

    @Test
    fun qianwen_share() {
        assertEquals(
            Platform.QIANWEN,
            Platform.detect("https://activity.qianwen.com/chats/share/abc123")
        )
    }

    @Test
    fun tongyi_chat2() {
        assertEquals(
            Platform.QIANWEN,
            Platform.detect("https://chat2-api.qianwen.com/share/chat/xyz789")
        )
    }

    @Test
    fun qianwen_alibaba_studio() {
        assertEquals(
            Platform.QIANWEN,
            Platform.detect("https://pages.tongyi.com/x/share/abc123")
        )
    }

    @Test
    fun quark_ai_share() {
        assertEquals(
            Platform.QUARK_AI,
            Platform.detect("https://act.quark.cn/share/abc123")
        )
    }

    @Test
    fun jianying_lv_share() {
        assertEquals(
            Platform.JIANYING,
            Platform.detect("https://lv.ulikecam.com/detail/24c5a6d7e8f9a0b1c2d3e4f5a6b7c8d9?template_id=1234567890")
        )
    }

    @Test
    fun capcut_share() {
        assertEquals(
            Platform.JIANYING,
            Platform.detect("https://www.capcut.cn/share/1234567890")
        )
    }

    @Test
    fun tencent_channel_post() {
        assertEquals(
            Platform.TENCENT_CHANNEL,
            Platform.detect("https://pd.qq.com/s/abc123/xyz")
        )
    }

    @Test
    fun yuanbao_share() {
        assertEquals(
            Platform.YUANBAO,
            Platform.detect("https://yuanbao.tencent.com/chat/share/abc123")
        )
    }

    @Test
    fun jimeng_share() {
        assertEquals(
            Platform.JIMENG,
            Platform.detect("https://jimeng.jianying.com/ai-video/1234567890")
        )
    }

    @Test
    fun jimeng_short_s() {
        assertEquals(
            Platform.JIMENG,
            Platform.detect("https://jimeng.ai/s/abc123")
        )
    }

    @Test
    fun doubao_thread() {
        assertEquals(
            Platform.DOUBAO,
            Platform.detect("https://www.doubao.com/thread/abc123")
        )
    }

    @Test
    fun doubao_video_share() {
        assertEquals(
            Platform.DOUBAO,
            Platform.detect("https://www.doubao.com/video-sharing?share_id=abc&video_id=xyz")
        )
    }

    @Test
    fun pinduoduo_goods() {
        assertEquals(
            Platform.PINDUODUO,
            Platform.detect("https://mobile.yangkeduo.com/goods.html?goods_id=1234567890")
        )
    }

    @Test
    fun pinduoduo_duoduo_video() {
        assertEquals(
            Platform.PINDUODUO,
            Platform.detect("https://pinduoduo.com/mall_page?feed_id=abc123")
        )
    }
}