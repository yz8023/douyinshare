package Forinxy.jiexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookie 管理：导入校验 / 归一化 / 字段提取（纯函数部分） */
class DouyinAuthStoreTest {

    @Test
    fun normalize_removes_prefix_and_newlines_and_deduplicates() {
        val raw = "Cookie: sessionid=abc; passport_csrf_token=xyz;\r\nsessionid=dup"
        val normalized = DouyinAuthStore.normalizeCookieHeader(raw)
        assertEquals(2, normalized.split(";").size)
        assertFalse(normalized.contains("Cookie:"))
        assertFalse(normalized.contains("\n"))
        assertFalse(normalized.contains("\r"))
    }

    @Test
    fun normalize_skips_malformed_entries() {
        val normalized = DouyinAuthStore.normalizeCookieHeader("   ; invalidentry ; a=1 ;; b=2")
        assertEquals(listOf("a=1", "b=2"), normalized.split(";").map { it.trim() })
    }

    @Test
    fun looks_authenticated_true_for_sessionid() {
        assertTrue(DouyinAuthStore.looksAuthenticated("sessionid=abc123; passport_csrf_token=xyz"))
        assertTrue(DouyinAuthStore.looksAuthenticated("sessionid_ss=abc; uid_tt=1"))
    }

    @Test
    fun looks_authenticated_true_for_passport_auth_status() {
        assertTrue(
            DouyinAuthStore.looksAuthenticated(
                "uid_tt_ss=1; passport_auth_status=1; ttwid=xx"
            )
        )
    }

    @Test
    fun looks_authenticated_false_for_anonymous_cookies() {
        assertFalse(DouyinAuthStore.looksAuthenticated("ttwid=xx; msToken=yy"))
        assertFalse(DouyinAuthStore.looksAuthenticated(""))
        assertFalse(DouyinAuthStore.looksAuthenticated(null))
    }

    @Test
    fun extract_cookie_value_handles_names_with_prefix() {
        val header = "passport_csrf_token=xyz; ttwid=123; sessionid=abc"
        assertEquals("abc", DouyinAuthStore.extractCookieValue(header, "sessionid"))
        assertEquals("xyz", DouyinAuthStore.extractCookieValue(header, "passport_csrf_token"))
        assertNull(DouyinAuthStore.extractCookieValue(header, "missing"))
        assertNull(DouyinAuthStore.extractCookieValue(null, "sessionid"))
    }
}
