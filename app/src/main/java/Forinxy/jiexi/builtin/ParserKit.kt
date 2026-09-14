package Forinxy.jiexi.builtin

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 单个画质选项（与 douyin quality_list 结构一致） */
class QualityOption(
    val label: String,
    val ratio: String,
    val url: String,
    val sizeBytes: Long? = null,
    val bitRate: Long = 0,
    val isOriginal: Boolean = false
)

/** 图集条目（image_url / live_photo_raw_url，index 由 toServerJson 自动补齐） */
class GalleryItem(
    val imageUrl: String? = null,
    val livePhotoRawUrl: String? = null
)

/**
 * 平台无关的解析结果载体。各平台解析器把提取到的数据装进 MediaData，
 * 再由 [toServerJson] 输出与 server/data.php 1:1 兼容的 JSON 字符串，
 * 客户端 ServerApiClient.parseServerResponse 无需改动。
 */
class MediaData {
    var author: String = "未知作者"
    var authorUid: String? = null
    var authorSecUid: String? = null
    var title: String = "无标题"
    var videoId: String = ""
    var timestamp: Long = System.currentTimeMillis() / 1000
    // video / image / music
    var type: String = "video"
    var playUrl: String? = null
    var rawPlayUrl: String? = null
    var originalPlayUrl: String? = null
    var cover: String? = null
    var resolvedUrl: String? = null
    var inputUrl: String = ""
    var duration: Double = 0.0

    val gallery = mutableListOf<GalleryItem>()
    private val qualities = mutableListOf<QualityOption>()

    val isImage: Boolean get() = type == "image"
    val isMusic: Boolean get() = type == "music"
    val hasGallery: Boolean get() = gallery.isNotEmpty()

    fun addImage(url: String?) {
        if (!url.isNullOrBlank()) gallery.add(GalleryItem(imageUrl = url))
    }

    fun addGalleryItem(item: GalleryItem) {
        if (item.imageUrl != null || item.livePhotoRawUrl != null) gallery.add(item)
    }

    fun addQuality(
        label: String,
        ratio: String,
        url: String,
        sizeBytes: Long? = null,
        bitRate: Long = 0,
        isOriginal: Boolean = false
    ) {
        qualities.add(QualityOption(label, ratio, url, sizeBytes, bitRate, isOriginal))
    }

    fun qualityListJson(): JsonArray {
        val list = JsonArray()
        qualities.forEachIndexed { index, q ->
            if (q.url.isBlank()) return@forEachIndexed
            val entry = JsonObject()
            entry.addProperty("label", q.label.ifEmpty { "画质${index + 1}" })
            entry.addProperty("ratio", q.ratio.ifEmpty { "default" })
            entry.addProperty("url", q.url)
            if (q.sizeBytes != null && q.sizeBytes > 0) {
                entry.addProperty("size_bytes", q.sizeBytes)
            } else {
                entry.add("size_bytes", com.google.gson.JsonNull.INSTANCE)
            }
            entry.addProperty("bit_rate", q.bitRate)
            entry.addProperty("is_original", q.isOriginal)
            list.add(entry)
        }
        return list
    }

    /**
     * 输出 data.php 兼容 JSON。type 为 video 时带 quality_list；
     * type 为 image 时带 images + gallery_media（含实况地址）。
     */
    fun toServerJson(gson: Gson = JSON_GSON): String {
        val obj = JsonObject()
        obj.addProperty("success", true)
        obj.addProperty("author", author)
        if (authorUid != null) obj.addProperty("author_uid", authorUid)
        else obj.add("author_uid", com.google.gson.JsonNull.INSTANCE)
        if (authorSecUid != null) obj.addProperty("author_sec_uid", authorSecUid)
        else obj.add("author_sec_uid", com.google.gson.JsonNull.INSTANCE)
        obj.addProperty("title", title)
        obj.addProperty("video_id", videoId)
        obj.addProperty("timestamp", timestamp)
        obj.addProperty("type", type)

        val images = JsonArray()
        gallery.forEach { item ->
            images.add(item.imageUrl?.let { com.google.gson.JsonPrimitive(it) } ?: com.google.gson.JsonNull.INSTANCE)
        }
        obj.add("images", images)

        val galleryArray = JsonArray()
        gallery.forEachIndexed { index, item ->
            val entry = JsonObject()
            entry.addProperty("index", index)
            if (item.imageUrl != null) entry.addProperty("image_url", item.imageUrl)
            else entry.add("image_url", com.google.gson.JsonNull.INSTANCE)
            if (item.livePhotoRawUrl != null) entry.addProperty("live_photo_raw_url", item.livePhotoRawUrl)
            else entry.add("live_photo_raw_url", com.google.gson.JsonNull.INSTANCE)
            entry.addProperty("has_live_photo", item.livePhotoRawUrl != null)
            galleryArray.add(entry)
        }
        obj.add("gallery_media", galleryArray)

        if (cover != null) obj.addProperty("cover", cover)
        else obj.add("cover", com.google.gson.JsonNull.INSTANCE)

        if (playUrl != null) obj.addProperty("play_url", playUrl)
        else obj.add("play_url", com.google.gson.JsonNull.INSTANCE)
        if (rawPlayUrl != null) obj.addProperty("raw_play_url", rawPlayUrl)
        else obj.add("raw_play_url", com.google.gson.JsonNull.INSTANCE)
        if (originalPlayUrl != null) obj.addProperty("original_play_url", originalPlayUrl)
        else obj.add("original_play_url", com.google.gson.JsonNull.INSTANCE)

        obj.addProperty("duration", duration)

        val qualityList = qualityListJson()
        if (type == "video" || type == "music") {
            obj.add("quality_list", qualityList)
        } else {
            obj.add("quality_list", JsonArray())
        }

        if (resolvedUrl != null) obj.addProperty("resolved_url", resolvedUrl)
        else obj.add("resolved_url", com.google.gson.JsonNull.INSTANCE)
        obj.addProperty("input_url", inputUrl)
        obj.addProperty("auth_mode", "anon")
        obj.addProperty("cookie_enabled", "true")
        return gson.toJson(obj)
    }
}

/** 序列化时保留 JsonNull 值的键（data.php 兼容：author_uid/cover 等保持 null） */
val JSON_GSON: Gson = GsonBuilder().serializeNulls().create()

/** 数据载体输出失败响应。 */
fun failResponse(msg: String, code: Int = 400): String {
    val obj = JsonObject()
    obj.addProperty("success", false)
    obj.addProperty("error", msg)
    obj.addProperty("code", code)
    return JSON_GSON.toJson(obj)
}