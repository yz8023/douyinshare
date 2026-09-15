package Forinxy.jiexi.builtin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 新增平台解析器共用的 JSON 读取工具（前缀 j*，避免与既有解析器内的私有扩展重名）。
 * 全部为可空安全读取：字段缺失/JSON null/类型不符一律返回 null。
 */

internal fun parseJsonObj(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return runCatching { JsonParser.parseString(text) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject
}

internal fun parseJsonArr(text: String?): JsonArray? {
    if (text.isNullOrBlank()) return null
    return runCatching { JsonParser.parseString(text) }.getOrNull()
        ?.takeIf { it.isJsonArray }?.asJsonArray
}

internal fun JsonObject.jStr(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return runCatching { el.asString }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.jRawStr(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return runCatching { el.asString }.getOrNull()
}

internal fun JsonObject.jObj(key: String): JsonObject? {
    val el = get(key) ?: return null
    return if (el.isJsonObject) el.asJsonObject else null
}

internal fun JsonObject.jArr(key: String): JsonArray? {
    val el = get(key) ?: return null
    return if (el.isJsonArray) el.asJsonArray else null
}

internal fun JsonObject.jArrOrEmpty(key: String): JsonArray = jArr(key) ?: JsonArray()

internal fun JsonObject.jLong(key: String): Long? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return runCatching {
        val p = el.asJsonPrimitive
        if (p.isNumber) p.asLong else p.asString.toLongOrNull()
    }.getOrNull()
}

internal fun JsonObject.jDouble(key: String): Double? {
    val el = get(key) ?: return null
    if (el.isJsonNull) return null
    return runCatching {
        val p = el.asJsonPrimitive
        if (p.isNumber) p.asDouble else p.asString.toDoubleOrNull()
    }.getOrNull()
}

internal fun JsonObject.jBool(key: String): Boolean {
    val el = get(key) ?: return false
    if (el.isJsonNull || !el.isJsonPrimitive) return false
    return runCatching {
        val p = el.asJsonPrimitive
        if (p.isBoolean) p.asBoolean else p.asString == "true" || p.asString == "1"
    }.getOrDefault(false)
}

internal fun JsonElement.asObjOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

internal fun JsonArray.firstObjOrNull(): JsonObject? {
    for (el in this) {
        if (el.isJsonObject) return el.asJsonObject
    }
    return null
}

internal fun JsonElement.asStrOrNull(): String? =
    if (isJsonPrimitive) runCatching { asString }.getOrNull() else null
