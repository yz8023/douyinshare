package Forinxy.jiexi

import Forinxy.jiexi.builtin.TikTokSessionStore
import Forinxy.jiexi.builtin.TikTokPageData
import Forinxy.jiexi.builtin.asStrOrNull
import Forinxy.jiexi.builtin.firstObjOrNull
import Forinxy.jiexi.builtin.jArrOrEmpty
import Forinxy.jiexi.builtin.jLong
import Forinxy.jiexi.builtin.jObj
import Forinxy.jiexi.builtin.jStr
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok 页面数据提取与会话存储的纯函数单测：
 * UNIVERSAL 数据节点定位、itemStruct 提取、Set-Cookie 解析、CDN 域名判定。
 */
class TikTokPageDataTest {

    private val sampleHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>TikTok</title></head>
        <body>
        <script id="sigi-persisted-data">{"junk":1}</script>
        <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"7683526590705290516","desc":"Wuthering Waves Version 5.6","createTime":1788960447,"author":{"uniqueId":"meyro.kooo","nickname":"Meyro.kooo"},"video":{"duration":13,"width":1080,"height":2340,"cover":"https://p16-common-sign.tiktokcdn.com/cover.image","playAddr":"https://v16-webapp-prime.tiktok.com/video/tos/play?tk=abc","bitrateInfo":[{"Bitrate":33399409,"GearName":"original_1080_0","PlayAddr":{"UrlList":["https://v16-webapp-prime.tiktok.com/video/tos/hd?tk=abc"],"DataSize":55217573,"Width":1080,"Height":2340}}]}}}},"webapp.user-detail":{"userInfo":{}}}}
        </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun extractUniversalData_finds_node() {
        val root = TikTokPageData.extractUniversalData(sampleHtml)
        assertTrue(root != null)
        assertTrue(root!!.has("__DEFAULT_SCOPE__"))
    }

    @Test
    fun extractUniversalData_missing_node_returns_null() {
        assertNull(TikTokPageData.extractUniversalData("<html><body>login wall</body></html>"))
    }

    @Test
    fun extractItemStruct_returns_item() {
        val root = TikTokPageData.extractUniversalData(sampleHtml)!!
        val item = TikTokPageData.extractItemStruct(root)
        assertTrue(item != null)
        assertEquals("7683526590705290516", item!!.jStr("id"))
        assertEquals("Meyro.kooo", item.jObj("author")?.jStr("nickname"))
    }

    @Test
    fun extractFirstUrl_from_share_text() {
        assertEquals(
            "https://vt.tiktok.com/ZSbCqqPCq/",
            TikTokPageData.extractFirstUrl("Check this out https://vt.tiktok.com/ZSbCqqPCq/ enjoy")
        )
    }

    @Test
    fun extractFirstUrl_blank_returns_null() {
        assertNull(TikTokPageData.extractFirstUrl("   "))
    }

    @Test
    fun extractMediaId_from_video_page() {
        assertEquals(
            "7683526590705290516",
            TikTokPageData.extractMediaId("https://www.tiktok.com/@meyro.kooo/video/7683526590705290516")
        )
    }

    @Test
    fun extractMediaId_from_photo_page() {
        assertEquals(
            "123456",
            TikTokPageData.extractMediaId("https://www.tiktok.com/@u/photo/123456")
        )
    }

    @Test
    fun extractMediaId_short_link_returns_null() {
        assertNull(TikTokPageData.extractMediaId("https://vt.tiktok.com/ZSbCqqPCq/"))
    }

    @Test
    fun parseSetCookie_extracts_name_value() {
        val pair = TikTokPageData.parseSetCookie(
            "tt_chain_token=pT2TTbB60Rm/5dnn9QyipQ==; path=/; expires=Thu, 01 Jan 2026 00:00:00 GMT; HttpOnly; Secure; SameSite=Lax"
        )
        assertEquals("tt_chain_token", pair!!.first)
        assertEquals("pT2TTbB60Rm/5dnn9QyipQ==", pair.second)
    }

    @Test
    fun parseSetCookie_invalid_returns_null() {
        assertNull(TikTokPageData.parseSetCookie("Secure; HttpOnly"))
        assertNull(TikTokPageData.parseSetCookie(""))
        assertNull(TikTokPageData.parseSetCookie("=novalue; Path=/"))
    }

    @Test
    fun mergeCookieHeader_joins_pairs() {
        val merged = TikTokSessionStore.mergeCookieHeader(
            listOf(
                "tt_csrf_token=aaa; Path=/; Secure",
                "tt_chain_token=pT2TTbB60Rm/5dnn9QyipQ==; Path=/; HttpOnly"
            )
        )
        assertEquals(
            "tt_csrf_token=aaa; tt_chain_token=pT2TTbB60Rm/5dnn9QyipQ==",
            merged
        )
    }

    @Test
    fun mergeCookieHeader_same_name_latter_wins() {
        val merged = TikTokSessionStore.mergeCookieHeader(
            listOf("tt_chain_token=old; Path=/", "tt_chain_token=new; Path=/")
        )
        assertEquals("tt_chain_token=new", merged)
    }

    @Test
    fun qualityLabel_height_and_fallback() {
        assertEquals("1080p", TikTokPageData.qualityLabel(1080))
        assertEquals("高清", TikTokPageData.qualityLabel(null))
        assertEquals("高清", TikTokPageData.qualityLabel(0))
    }

    @Test
    fun bitrateInfo_entry_parses_from_sample() {
        val root = TikTokPageData.extractUniversalData(sampleHtml)!!
        val item = TikTokPageData.extractItemStruct(root)!!
        val video = item.jObj("video")!!
        val entry = video.jArrOrEmpty("bitrateInfo").firstObjOrNull()!!
        assertEquals(33399409L, entry.jLong("Bitrate"))
        val play = entry.jObj("PlayAddr")!!
        assertEquals(
            "https://v16-webapp-prime.tiktok.com/video/tos/hd?tk=abc",
            play.jArrOrEmpty("UrlList").firstOrNull()?.asStrOrNull()
        )
        assertEquals(55217573L, play.jLong("DataSize"))
        assertEquals(2340L, play.jLong("Height"))
    }

    @Test
    fun root_without_itemStruct_returns_null() {
        val root = JsonParser.parseString("{\"__DEFAULT_SCOPE__\":{}}").asJsonObject
        assertNull(TikTokPageData.extractItemStruct(root))
    }

    @Test
    fun extractItemStruct_reflow_scope_supported() {
        val root = JsonParser.parseString(
            "{\"__DEFAULT_SCOPE__\":{\"webapp.reflow.video.detail\":" +
                "{\"itemInfo\":{\"itemStruct\":{\"id\":\"111\",\"desc\":\"reflow\"}}}}}"
        ).asJsonObject
        val item = TikTokPageData.extractItemStruct(root)
        assertEquals("111", item?.jStr("id"))
    }

    @Test
    fun extractItemStructFromSigi_parses_item_module() {
        val html = """
            <html><body>
            <script id="SIGI_STATE">{"ItemModule":{"7301":{"id":"7301","desc":"sigi video"}}}</script>
            </body></html>
        """.trimIndent()
        val item = TikTokPageData.extractItemStructFromSigi(html)
        assertEquals("7301", item?.jStr("id"))
        assertEquals("sigi video", item?.jStr("desc"))
    }

    @Test
    fun extractItemStructFromSigi_missing_returns_null() {
        assertNull(TikTokPageData.extractItemStructFromSigi("<html><body>plain</body></html>"))
        assertNull(
            TikTokPageData.extractItemStructFromSigi(
                "<script id=\"SIGI_STATE\">{\"ItemModule\":{}}</script>"
            )
        )
    }

    @Test
    fun isWafChallengePage_detects_shell_markers() {
        assertTrue(
            TikTokPageData.isWafChallengePage(
                "<script id=\"wci\" class=\"_wafchallengeid\">Please wait...</script>"
            )
        )
        assertTrue(
            TikTokPageData.isWafChallengePage(
                "<script id=\"slardar-config\">{\"bid\":\"slardar_us_waf\"}</script>"
            )
        )
        assertTrue(TikTokPageData.isWafChallengePage("<script src=\"/aweme/v1/browser.web.pre.js\">"))
    }

    @Test
    fun isWafChallengePage_normal_page_returns_false() {
        assertTrue(!TikTokPageData.isWafChallengePage(sampleHtml))
    }

    @Test
    fun session_store_freshness_and_register() {
        TikTokSessionStore.clear()
        assertTrue(!TikTokSessionStore.hasFreshCookie())
        TikTokSessionStore.register("ua", "tt_sc=1")
        assertTrue(TikTokSessionStore.hasFreshCookie())
        assertEquals("tt_sc=1", TikTokSessionStore.currentCookieHeader())
        assertTrue(TikTokSessionStore.hasSession())
        // 过期判定
        assertTrue(!TikTokSessionStore.hasFreshCookie(maxAgeMs = -1L))
        TikTokSessionStore.clear()
        assertTrue(!TikTokSessionStore.hasSession())
    }
}
