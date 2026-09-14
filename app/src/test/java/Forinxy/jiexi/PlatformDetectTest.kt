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
}