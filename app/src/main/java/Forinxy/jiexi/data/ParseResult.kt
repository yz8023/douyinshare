package Forinxy.jiexi.data

data class GalleryMedia(
    val index: Int = 0,
    val imageUrl: String? = null,
    val livePhotoRawUrl: String? = null
) {
    val hasLivePhoto: Boolean
        get() = !livePhotoRawUrl.isNullOrBlank()
}

/**
 * 单个视频画质选项：服务器解析时一次性返回全部可供下载的画质（含大小），
 * 供保存前选择。「原画质」用 ratio=default 表示，其余用分辨率（如 1080p）。
 */
data class VideoQualityOption(
    val label: String,
    val ratio: String,
    val url: String,
    val sizeBytes: Long? = null,
    val bitRate: Long = 0,
    val isOriginal: Boolean = false
)

sealed class ParseResult {
    object Idle : ParseResult()
    object Loading : ParseResult()

    data class Success(
        val author: String,
        val authorUid: String? = null,
        val authorSecUid: String? = null,
        val title: String,
        val type: String, // "video" or "image"
        val playUrl: String?,
        val rawPlayUrl: String?,
        val images: List<String>?,
        val galleryMedia: List<GalleryMedia>? = null,
        val timestamp: Long,
        val videoId: String,
        val cover: String?,
        val inputUrl: String,
        val resolvedUrl: String? = null,
        val duration: Double = 0.0,
        val parseTimestamp: Long = System.currentTimeMillis() / 1000,
        val lastPlayUrlUpdateTime: Long? = null,
        val source: String = "single",
        val batchId: String? = null,
        // 服务器一次返回的原画质/最高画质地址（保存时直接用，避免二次请求）
        val originalPlayUrl: String? = null,
        // 服务器返回的全部可选画质（含预估大小），供保存前选择
        val qualityList: List<VideoQualityOption>? = null
    ) : ParseResult()

    data class Error(val msg: String) : ParseResult()
}

val ParseResult.Success.galleryItems: List<GalleryMedia>
    get() {
        val items = galleryMedia.orEmpty()
        if (items.isNotEmpty()) {
            if (items.withIndex().all { (index, media) -> media.index == index }) {
                return items
            }
            return items.mapIndexed { index, media -> media.copy(index = index) }
        }
        return images.orEmpty().mapIndexed { index, imageUrl ->
            GalleryMedia(index = index, imageUrl = imageUrl)
        }
    }

val ParseResult.Success.galleryImageCount: Int
    get() = galleryMedia
        ?.count { !it.imageUrl.isNullOrBlank() }
        ?: images.orEmpty().size

val ParseResult.Success.livePhotoCount: Int
    get() = galleryMedia.orEmpty().count { it.hasLivePhoto }

val ParseResult.Success.totalMediaAssetCount: Int
    get() = if (type == "video") {
        1
    } else {
        galleryMedia.orEmpty().ifEmpty {
            images.orEmpty().mapIndexed { index, imageUrl ->
                GalleryMedia(index = index, imageUrl = imageUrl)
            }
        }.sumOf { media ->
            (if (media.imageUrl.isNullOrBlank()) 0 else 1) +
                (if (media.hasLivePhoto) 1 else 0)
        }
    }
