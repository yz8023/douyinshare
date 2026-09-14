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
    fun extract_parse_input_returns_null_for_unknown() {
        assertNull(ClipboardShareContent.extractParseInput("没有任何链接"))
        assertNull(ClipboardShareContent.extractParseInput(""))
    }
}