package Forinxy.jiexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 剪贴板分享识别的多平台扩展 */
class ClipboardShareContentTest {

    @Test
    fun douyin_share_still_recognized() {
        assertTrue(ClipboardShareContent.isDouyinShare("https://v.douyin.com/abcdef/"))
        assertTrue(ClipboardShareContent.isDouyinShare("7234567890123456789"))
    }

    @Test
    fun multi_platform_share_recognized() {
        assertTrue(ClipboardShareContent.isDouyinShare("https://www.bilibili.com/video/BV1xx411c7mD/"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://v.kuaishou.com/abc123"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://xhslink.com/a/AbCdEf"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://music.163.com/song?id=185711"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://t.cn/A6xYz12"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://qishui.douyin.com/s/iX21ep91/"))
    }

    @Test
    fun reported_failing_platforms_recognized() {
        // 用户反馈解析失败的平台链接，剪贴板识别须覆盖
        assertTrue(ClipboardShareContent.isDouyinShare("https://www.pearvideo.com/video_1805408"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://video.weishi.qq.com/5D41bben"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://c6.y.qq.com/base/fcgi-bin/u?__=K3G2Po2Y91ZH"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://www.zhihu.com/pin/2066168388699807826"))
        assertTrue(ClipboardShareContent.isDouyinShare("https://163cn.tv/bgriucOS"))
        // 分享文案 + 短链兜底
        assertTrue(
            ClipboardShareContent.isDouyinShare(
                "分享我的梨视频作品：https://www.pearvideo.com/video_1807314 觉得不错就点个赞吧"
            )
        )
        assertTrue(
            ClipboardShareContent.isDouyinShare(
                "【QQ音乐】https://c6.y.qq.com/base/fcgi-bin/u?__=K3G2Po2Y91ZH 一起来听~"
            )
        )
    }

    @Test
    fun unsupported_share_not_recognized() {
        assertFalse(ClipboardShareContent.isDouyinShare("https://www.youtube.com/watch?v=xxx"))
        assertFalse(ClipboardShareContent.isDouyinShare("hello world"))
    }

    @Test
    fun extract_parse_input_returns_any_supported_url() {
        assertEquals(
            "https://music.163.com/song?id=185711",
            ClipboardShareContent.extractParseInput(
                "分享陶喆的单曲《就是爱你》: https://music.163.com/song?id=185711"
            )
        )
        assertEquals(
            "https://www.xiaohongshu.com/explore/641d1a000000000013012345",
            ClipboardShareContent.extractParseInput("看看这个笔记 https://www.xiaohongshu.com/explore/641d1a000000000013012345")
        )
        assertEquals("7234567890123456789", ClipboardShareContent.extractParseInput("7234567890123456789"))
    }

    @Test
    fun extract_parse_input_from_reported_platforms() {
        assertEquals(
            "https://c6.y.qq.com/base/fcgi-bin/u?__=K3G2Po2Y91ZH",
            ClipboardShareContent.extractParseInput("【QQ音乐】https://c6.y.qq.com/base/fcgi-bin/u?__=K3G2Po2Y91ZH 一起来听~")
        )
        assertEquals(
            "https://163cn.tv/bgriucOS",
            ClipboardShareContent.extractParseInput("分享澪恩Seiwen的单曲: https://163cn.tv/bgriucOS (来自@网易云音乐)")
        )
    }

    @Test
    fun extract_parse_input_returns_null_for_unknown() {
        assertNull(ClipboardShareContent.extractParseInput("没有任何链接"))
        assertNull(ClipboardShareContent.extractParseInput(""))
    }
}