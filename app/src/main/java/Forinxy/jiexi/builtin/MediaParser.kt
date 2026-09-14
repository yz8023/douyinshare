package Forinxy.jiexi.builtin

/**
 * 统一解析入口：内置服务器 /data.php 调用的门面接口。
 * [parse] 返回 data.php 兼容 JSON 字符串，失败返回 {"success":false,...}。
 */
internal interface MediaParser {
    fun parse(input: String, useCookie: Boolean, original: Boolean, highest: Boolean): String
    fun diag(): String
}

/**
 * 单个平台的解析器。由 [MultiPlatformParser] 按 [platform] 分发。
 * 返回 data.php 兼容 JSON 字符串，失败返回 failResponse(...)。
 */
internal interface PlatformParser {
    val platform: Platform

    /** @param useCookie 请求是否合并内置 cookie（多平台里仅抖音等少数生效） */
    fun parse(input: String, useCookie: Boolean, original: Boolean, highest: Boolean): String
}

/** 按平台建立解析器索引（可单测） */
internal fun indexByPlatform(parsers: List<PlatformParser>): Map<Platform, PlatformParser> =
    parsers.associateBy { it.platform }