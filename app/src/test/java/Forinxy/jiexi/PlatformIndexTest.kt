package Forinxy.jiexi

import Forinxy.jiexi.builtin.Platform
import Forinxy.jiexi.builtin.PlatformParser
import Forinxy.jiexi.builtin.indexByPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/** 平台解析器索引：每个平台恰好映射到一个解析器 */
class PlatformIndexTest {

    private fun fake(platform: Platform): PlatformParser = object : PlatformParser {
        override val platform: Platform = platform
        override fun parse(
            input: String,
            useCookie: Boolean,
            original: Boolean,
            highest: Boolean
        ): String = platform.name
    }

    @Test
    fun each_supported_platform_has_mapping() {
        val parsers = Platform.entries.map { fake(it) }
        val index = indexByPlatform(parsers)
        assertEquals(Platform.entries.size, index.size)
        for (platform in Platform.entries) {
            assertEquals(platform.name, index[platform]?.parse("", false, false, false))
        }
    }

    @Test
    fun duplicate_platform_keeps_last() {
        val index = indexByPlatform(listOf(fake(Platform.DOUYIN), fake(Platform.DOUYIN)))
        assertEquals(1, index.size)
    }
}