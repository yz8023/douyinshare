package Forinxy.jiexi.builtin

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 平台解析器共用的 URL 工具：query / fragment 参数解析与拼接。
 * 采用纯字符串处理，避免依赖 android.net.Uri（保证 JVM 单测可用）。
 */
internal object UrlKit {

    /** 解析 query 参数（自动剔除 #fragment；值做 URL 解码） */
    fun query(url: String?): Map<String, String> {
        val u = url.orEmpty()
        val qIndex = u.indexOf('?')
        if (qIndex < 0) return emptyMap()
        var query = u.substring(qIndex + 1)
        val hash = query.indexOf('#')
        if (hash >= 0) query = query.substring(0, hash)
        return decodeForm(query)
    }

    /** 解析 fragment 内「?XXX」形式的参数（Soul 分享链接用） */
    fun fragmentQuery(url: String?): Map<String, String> {
        val u = url.orEmpty()
        val h = u.indexOf('#')
        if (h < 0) return emptyMap()
        val fragment = u.substring(h + 1)
        val qIndex = fragment.indexOf('?')
        if (qIndex < 0) return emptyMap()
        return decodeForm(fragment.substring(qIndex + 1))
    }

    /** 拼接 base + query 参数 */
    fun build(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val separator = if (base.contains('?')) "&" else "?"
        return "$base$separator$query"
    }

    private fun decodeForm(form: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        form.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            if (key.isNotEmpty()) map.putIfAbsent(decode(key), decode(value))
        }
        return map
    }

    fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun encode(s: String): String =
        runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}