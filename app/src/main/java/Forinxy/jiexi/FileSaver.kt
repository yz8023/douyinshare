package Forinxy.jiexi

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

private const val PROGRESS_UPDATE_INTERVAL_MS = 120L
private const val MAX_SAVE_ATTEMPTS = 3
private const val SAVE_RETRY_DELAY_MS = 450L
private const val MAX_MEDIASTORE_NAME_ATTEMPTS = 50
private const val STREAM_BUFFER_SIZE = 256 * 1024
private const val MIN_PARALLEL_VIDEO_BYTES = 16L * 1024L * 1024L
private const val RANGE_SEGMENT_TARGET_BYTES = 8L * 1024L * 1024L
private const val MAX_PARALLEL_VIDEO_RANGES = 32
private const val MAX_RANGE_SEGMENT_ATTEMPTS = 3

private data class RangeProbe(
    val totalLength: Long,
    val segmentCount: Int
)

private data class ByteRange(
    val start: Long,
    val end: Long
) {
    val length: Long
        get() = end - start + 1
}

private fun interface RangeChunkWriter {
    @Throws(IOException::class)
    fun write(position: Long, buffer: ByteArray, byteCount: Int)
}

private class UnsupportedRangeDownloadException(message: String) : IOException(message)

private class PartialRangeDownloadException(
    val bytesWritten: Long,
    cause: IOException
) : IOException(cause)

suspend fun saveFile(
    context: Context,
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
    mimeType: String,
    timestamp: Long,
    index: Int = 0,
    fileName: String? = null,
    probe: SaveSizeProbe? = null,
    subPathOverride: String? = null,
    extensionOverride: String? = null,
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
): Boolean = withContext(Dispatchers.IO) {
    val uniqueSuffix = url.hashCode().toString().replace("-", "")
    val isVideo = mimeType.startsWith("video")
    val defaultFileName = if (isVideo) {
        "dy_${timestamp}_${uniqueSuffix}.mp4"
    } else {
        "dy_${timestamp}_${index + 1}_${uniqueSuffix}.jpg"
    }

    val customFileName = fileName?.takeIf { it.isNotBlank() }
    val finalFileName = when {
        extensionOverride != null ->
            ensureExtension(customFileName ?: defaultFileName, extensionOverride)
        isVideo -> ensureExtension(customFileName ?: defaultFileName, "mp4")
        customFileName != null -> ensureExtension(customFileName, "jpg")
        else -> defaultFileName
    }

    val subPath = subPathOverride ?: if (isVideo) "dyparse/video" else "dyparse/image"
    var lastError: Exception? = null

    repeat(MAX_SAVE_ATTEMPTS) { attempt ->
        try {
            downloadAndSaveOnce(
                context = context,
                client = client,
                url = url,
                headers = headers,
                mimeType = mimeType,
                finalFileName = finalFileName,
                subPath = subPath,
                probe = probe,
                onProgress = onProgress
            )
            return@withContext true
        } catch (error: Exception) {
            lastError = error
            val retry = attempt < MAX_SAVE_ATTEMPTS - 1 && shouldRetrySave(error)
            Log.w(
                "SaveError",
                "save attempt ${attempt + 1}/$MAX_SAVE_ATTEMPTS failed for $url, retry=$retry",
                error
            )
            if (retry) {
                delay(SAVE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
    }

    Log.e("SaveError", "save file failed: $url", lastError)
    withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, "保存失败: ${lastError?.message}", Toast.LENGTH_SHORT).show()
    }
    false
}

/**
 * 保存实况动态视频：下载到缓存 → 按 [includeAudio] 决定是否剥离音频轨 → 落盘到 Downloads。
 * 剥离音频失败时自动回退保留原文件（带音频），保证保存不因剥离异常而失败。
 */
suspend fun saveLivePhotoVideo(
    context: Context,
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
    mimeType: String,
    fileName: String? = null,
    subPath: String = "dyparse/video",
    includeAudio: Boolean = true,
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
): Boolean = withContext(Dispatchers.IO) {
    val uniqueSuffix = url.hashCode().toString().replace("-", "")
    val finalFileName = ensureExtension(
        fileName?.takeIf { it.isNotBlank() } ?: "dy_live_${System.currentTimeMillis()}_$uniqueSuffix.mov",
        if (mimeType == "video/quicktime") "mov" else "mp4"
    )

    var cacheFile: File? = null
    var strippedFile: File? = null
    try {
        cacheFile = downloadToCacheFile(context, client, url, headers, onProgress)
        if (cacheFile == null) {
            return@withContext false
        }

        val fileToSave = if (includeAudio) {
            cacheFile
        } else {
            strippedFile = File(
                context.cacheDir,
                "stripped_${System.currentTimeMillis()}_$uniqueSuffix.mp4"
            )
            if (LivePhotoAudioStripper.stripAudioTrack(
                    sourcePath = cacheFile.absolutePath,
                    targetPath = strippedFile.absolutePath
                ) && strippedFile.length() > 0L
            ) {
                strippedFile
            } else {
                // 剥离失败：回退原文件（保留音频），仅告警不阻断保存
                Log.w("SaveError", "live photo audio strip failed, fallback to original: $url")
                cacheFile
            }
        }

        val contentLength = fileToSave.length()
        val inputStream = BufferedInputStream(fileToSave.inputStream(), STREAM_BUFFER_SIZE)
        inputStream.use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloadsMediaStore(
                    context = context,
                    inputStream = stream,
                    fileName = finalFileName,
                    mimeType = mimeType,
                    subPath = subPath,
                    totalLength = contentLength,
                    onProgress = onProgress
                )
            } else {
                saveToDownloadsDirectory(
                    inputStream = stream,
                    fileName = finalFileName,
                    subPath = subPath,
                    totalLength = contentLength,
                    onProgress = onProgress
                )
            }
        }
        true
    } catch (error: Exception) {
        Log.e("SaveError", "save live photo video failed: $url", error)
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, "保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
        }
        false
    } finally {
        runCatching { cacheFile?.delete() }
        runCatching { strippedFile?.delete() }
    }
}

/** 下载 URL 到缓存文件，返回缓存文件或 null。 */
private suspend fun downloadToCacheFile(
    context: Context,
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    onProgress: suspend (Long, Long) -> Unit
): File? {
    val uniqueSuffix = url.hashCode().toString().replace("-", "")
    val cacheFile = File(context.cacheDir, "live_photo_$uniqueSuffix.cache")
    return try {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                requestBuilder.header(name, value)
            }
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("download failed: ${response.code}")
            }
            val body = response.body ?: throw IOException("response body is empty")
            val contentLength = body.contentLength()
            if (contentLength > 0) {
                onProgress(0L, contentLength)
            }
            cacheFile.outputStream().use { rawOutput ->
                BufferedOutputStream(rawOutput, STREAM_BUFFER_SIZE).use { outputStream ->
                    copyStreamWithProgress(body.byteStream(), outputStream, contentLength, onProgress)
                }
            }
        }
        cacheFile
    } catch (error: Exception) {
        Log.e("SaveError", "download to cache failed: $url", error)
        runCatching { cacheFile.delete() }
        null
    }
}

/** 保存纯文本文件（歌词 .lrc/.txt 等）到 Downloads/dyparse/music。 */
suspend fun saveTextFile(
    context: Context,
    fileName: String,
    content: String,
    extension: String,
    mimeType: String = "text/plain",
    subPath: String = MUSIC_SAVE_SUBPATH
): Boolean = withContext(Dispatchers.IO) {
    val safeName = fileName.let { ensureExtension(it, extension) }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var lastError: Exception? = null
            val resolver = context.contentResolver
            for (attempt in 0 until MAX_MEDIASTORE_NAME_ATTEMPTS) {
                val candidateFileName = if (attempt == 0) safeName else buildUniqueFileName(safeName, attempt)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, candidateFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$subPath"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = try {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Cannot create MediaStore record")
                } catch (error: Exception) {
                    lastError = error
                    if (isDuplicateMediaStoreError(error) && attempt < MAX_MEDIASTORE_NAME_ATTEMPTS - 1) {
                        continue
                    }
                    throw error
                }
                try {
                    resolver.openOutputStream(uri)?.use { stream ->
                        BufferedOutputStream(stream, STREAM_BUFFER_SIZE).use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    } ?: throw IOException("Cannot open output stream")
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    return@withContext true
                } catch (error: Exception) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            }
            throw lastError ?: IOException("Cannot create MediaStore record")
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDir = File(downloadsDir, subPath)
            if (!appDir.exists() && !appDir.mkdirs()) {
                throw IOException("Cannot create save directory")
            }
            val targetFile = createUniqueFile(appDir, safeName)
            FileOutputStream(targetFile).use { stream ->
                BufferedOutputStream(stream, STREAM_BUFFER_SIZE).use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            true
        }
    } catch (error: Exception) {
        Log.e("SaveError", "save text file failed: $fileName", error)
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, "歌词保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
        }
        false
    }
}

private const val MUSIC_SAVE_SUBPATH = "dyparse/music"

private suspend fun downloadAndSaveOnce(
    context: Context,
    client: OkHttpClient,
    url: String,
    headers: Map<String, String>,
    mimeType: String,
    finalFileName: String,
    subPath: String,
    probe: SaveSizeProbe?,
    onProgress: suspend (Long, Long) -> Unit
) {
    val requestBuilder = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
            requestBuilder.header(name, value)
        }
    }
    val request = requestBuilder.build()
    val isVideo = mimeType.startsWith("video")

    if (isVideo && trySaveVideoWithParallelRange(
            context = context,
            client = client,
            request = request,
            finalFileName = finalFileName,
            mimeType = mimeType,
            subPath = subPath,
            probe = probe,
            onProgress = onProgress
        )
    ) {
        return
    }

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("download failed: ${response.code}")
        }

        val body = response.body ?: throw IOException("response body is empty")
        val contentLength = body.contentLength()
        if (contentLength > 0) {
            onProgress(0L, contentLength)
        }

        val peekedStream = PushbackInputStream(
            BufferedInputStream(body.byteStream(), STREAM_BUFFER_SIZE),
            16
        )
        val sniffedExt = sniffResponseExtension(
            peekedStream,
            contentType = response.header("Content-Type"),
            mimeType = mimeType
        )
        val actualFileName = if (sniffedExt != null) {
            val currentExt = finalFileName.substringAfterLast('.', "").lowercase()
            if (currentExt == sniffedExt.lowercase()) {
                finalFileName
            } else {
                "${finalFileName.substringBeforeLast('.', finalFileName)}.$sniffedExt"
            }
        } else {
            finalFileName
        }

        peekedStream.use { inputStream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloadsMediaStore(
                    context = context,
                    inputStream = inputStream,
                    fileName = actualFileName,
                    mimeType = mimeType,
                    subPath = subPath,
                    totalLength = contentLength,
                    onProgress = onProgress
                )
            } else {
                saveToDownloadsDirectory(
                    inputStream = inputStream,
                    fileName = actualFileName,
                    subPath = subPath,
                    totalLength = contentLength,
                    onProgress = onProgress
                )
            }
        }
    }
}

/**
 * 从响应流开头嗅探真实格式扩展名（文件头优先，其次 Content-Type），认不出返回 null。
 * 头条动图等地址的 URL 后缀是 `~tplv-tt-large.image` 而响应头是 `image/gif`，
 * 只能靠文件头/响应头定后缀，避免动图存成 .jpg、HEIF 存错扩展名。
 * 传入的 [stream] 必须是可回退的 PushbackInputStream（读取后会把文件头推回）。
 */
private fun sniffResponseExtension(
    stream: PushbackInputStream,
    contentType: String?,
    mimeType: String
): String? {
    return try {
        val kind = when {
            mimeType.startsWith("image") -> "image"
            mimeType.startsWith("audio") -> "audio"
            else -> "video"
        }
        val head = ByteArray(16)
        var headLen = 0
        while (headLen < head.size) {
            val n = stream.read(head, headLen, head.size - headLen)
            if (n == -1) break
            headLen += n
        }
        if (headLen == 0) return null
        stream.unread(head, 0, headLen)
        val fromBytes = extensionForBytes(head.copyOf(headLen))
        fromBytes ?: extensionForContentType(contentType, kind)
    } catch (e: Exception) {
        null
    }
}

/** 文件头 magic bytes → 扩展名（无点），认不出返回 null。 */
private fun extensionForBytes(head: ByteArray): String? {
    fun at(i: Int, magic: ByteArray): Boolean {
        if (head.size < i + magic.size) return false
        for (k in magic.indices) {
            if (head[i + k] != magic[k]) return false
        }
        return true
    }

    if (at(0, byteArrayOf(0x47, 0x49, 0x46, 0x38))) return "gif"
    if (at(0, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return "png"
    if (at(0, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return "jpg"
    if (at(0, byteArrayOf(0x42, 0x4D))) return "bmp"
    if (at(0, byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) return "webm"
    if (at(0, byteArrayOf(0x66, 0x4C, 0x61, 0x43))) return "flac"
    if (at(0, byteArrayOf(0x4F, 0x67, 0x67, 0x53))) return "ogg"
    if (at(0, byteArrayOf(0x52, 0x49, 0x46, 0x46)) && at(8, byteArrayOf(0x57, 0x41, 0x56, 0x45))) {
        return "wav"
    }
    if (at(0, byteArrayOf(0x49, 0x44, 0x33)) ||
        at(0, byteArrayOf(0xFF.toByte(), 0xFB.toByte())) ||
        at(0, byteArrayOf(0xFF.toByte(), 0xF3.toByte()))
    ) {
        return "mp3"
    }
    if (at(0, byteArrayOf(0x52, 0x49, 0x46, 0x46))) {
        if (at(8, byteArrayOf(0x57, 0x45, 0x42, 0x50))) return "webp"
        if (at(8, byteArrayOf(0x41, 0x56, 0x49, 0x20))) return "avi"
    }
    // HEIF/AVIF/MP4/MOV/M4A 共用 MP4 盒子：offset 4 是 'ftyp'，8 起是 brand。
    if (at(4, byteArrayOf(0x66, 0x74, 0x79, 0x70))) {
        val brand = String(head, 8, minOf(head.size - 8, 4), Charsets.US_ASCII)
        if (brand.startsWith("avif") || brand.startsWith("avis")) return "avif"
        if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1")) {
            return "heic"
        }
        if (brand.startsWith("qt")) return "mov"
        if (brand.startsWith("M4A")) return "m4a"
        // brand 认不出（CDN 常见）：长度够了按 MP4 记，比留 .part 强。
        return if (head.size >= 16) "mp4" else null
    }
    return null
}

/** 扩展名（无点）属于哪一类媒体，用于校验 Content-Type 猜测与下载类型是否一致。 */
private fun extensionKind(ext: String): String? = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif", "bmp", "tif", "tiff" -> "image"
    "mp4", "m4v", "mov", "webm", "mkv", "avi", "flv", "ts" -> "video"
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus" -> "audio"
    else -> null
}

/** Content-Type → 扩展名（无点），与下载类型不符时返回 null。 */
private fun extensionForContentType(contentType: String?, expectedKind: String): String? {
    val mime = (contentType ?: "").split(';').first().trim().lowercase()
    val ext = MIME_EXT_MAP[mime] ?: return null
    return if (extensionKind(ext) == expectedKind) ext else null
}

/** Content-Type → 扩展名（无点）映射，只认有把握的几个。 */
private val MIME_EXT_MAP: Map<String, String> = mapOf(
    "image/gif" to "gif",
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/pjpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "image/avif" to "avif",
    "image/heic" to "heic",
    "image/heif" to "heic",
    "image/bmp" to "bmp",
    "image/tiff" to "tiff",
    "video/mp4" to "mp4",
    "video/quicktime" to "mov",
    "video/webm" to "webm",
    "video/x-matroska" to "mkv",
    "audio/mpeg" to "mp3",
    "audio/mp4" to "m4a",
    "audio/x-m4a" to "m4a",
    "audio/aac" to "aac",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/flac" to "flac",
    "audio/x-flac" to "flac",
    "audio/ogg" to "ogg"
)

@SuppressLint("NewApi")
private suspend fun saveToDownloadsMediaStore(
    context: Context,
    inputStream: InputStream,
    fileName: String,
    mimeType: String,
    subPath: String,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    val resolver = context.contentResolver
    var lastError: Exception? = null

    for (attempt in 0 until MAX_MEDIASTORE_NAME_ATTEMPTS) {
        val candidateFileName = if (attempt == 0) fileName else buildUniqueFileName(fileName, attempt)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidateFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Cannot create MediaStore record")
        } catch (error: Exception) {
            lastError = error
            if (isDuplicateMediaStoreError(error) && attempt < MAX_MEDIASTORE_NAME_ATTEMPTS - 1) {
                continue
            }
            throw error
        }

        try {
            val rawOutputStream = resolver.openOutputStream(uri)
                ?: throw IOException("Cannot open output stream")
            rawOutputStream.use { stream ->
                BufferedOutputStream(stream, STREAM_BUFFER_SIZE).use { outputStream ->
                    copyStreamWithProgress(inputStream, outputStream, totalLength, onProgress)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    throw lastError ?: IOException("Cannot create MediaStore record")
}

private suspend fun saveToDownloadsDirectory(
    inputStream: InputStream,
    fileName: String,
    subPath: String,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val appDir = File(downloadsDir, subPath)
    if (!appDir.exists() && !appDir.mkdirs()) {
        throw IOException("Cannot create save directory")
    }

    val targetFile = createUniqueFile(appDir, fileName)
    FileOutputStream(targetFile).use { stream ->
        BufferedOutputStream(stream, STREAM_BUFFER_SIZE).use { outputStream ->
            copyStreamWithProgress(inputStream, outputStream, totalLength, onProgress)
        }
    }
}

private suspend fun trySaveVideoWithParallelRange(
    context: Context,
    client: OkHttpClient,
    request: Request,
    finalFileName: String,
    mimeType: String,
    subPath: String,
    probe: SaveSizeProbe?,
    onProgress: suspend (Long, Long) -> Unit
): Boolean {
    // 复用保存前探测结果；若探测明确不支持 Range 或文件过小，直接走单流
    val resolvedProbe = probe?.let { p ->
        if (!p.rangeSupported || p.totalBytes < MIN_PARALLEL_VIDEO_BYTES) {
            return false
        }
        RangeProbe(
            totalLength = p.totalBytes,
            segmentCount = chooseRangeSegmentCount(p.totalBytes)
        )
    } ?: run {
        try {
            probeVideoRangeSupport(client, request)
        } catch (error: IOException) {
            Log.d("SaveError", "range probe failed, fallback to single stream", error)
            null
        } ?: return false
    }

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveVideoRangesToDownloadsMediaStore(
                context = context,
                client = client,
                request = request,
                fileName = finalFileName,
                mimeType = mimeType,
                subPath = subPath,
                probe = resolvedProbe,
                onProgress = onProgress
            )
        } else {
            saveVideoRangesToDownloadsDirectory(
                client = client,
                request = request,
                fileName = finalFileName,
                subPath = subPath,
                probe = resolvedProbe,
                onProgress = onProgress
            )
        }
        true
    } catch (error: UnsupportedRangeDownloadException) {
        Log.d("SaveError", "range download unsupported, fallback to single stream", error)
        false
    }
}

@SuppressLint("NewApi")
private suspend fun saveVideoRangesToDownloadsMediaStore(
    context: Context,
    client: OkHttpClient,
    request: Request,
    fileName: String,
    mimeType: String,
    subPath: String,
    probe: RangeProbe,
    onProgress: suspend (Long, Long) -> Unit
) {
    val resolver = context.contentResolver
    var lastError: Exception? = null

    for (attempt in 0 until MAX_MEDIASTORE_NAME_ATTEMPTS) {
        val candidateFileName = if (attempt == 0) fileName else buildUniqueFileName(fileName, attempt)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidateFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Cannot create MediaStore record")
        } catch (error: Exception) {
            lastError = error
            if (isDuplicateMediaStoreError(error) && attempt < MAX_MEDIASTORE_NAME_ATTEMPTS - 1) {
                continue
            }
            throw error
        }

        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Cannot open output file descriptor")
            descriptor.use {
                FileOutputStream(it.fileDescriptor).channel.use { channel ->
                    onProgress(0L, probe.totalLength)
                    downloadFileInRanges(
                        client = client,
                        request = request,
                        totalLength = probe.totalLength,
                        segmentCount = probe.segmentCount,
                        writer = RangeChunkWriter { position, buffer, byteCount ->
                            writeFully(channel, buffer, byteCount, position)
                        },
                        onProgress = onProgress
                    )
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    throw lastError ?: IOException("Cannot create MediaStore record")
}

private suspend fun saveVideoRangesToDownloadsDirectory(
    client: OkHttpClient,
    request: Request,
    fileName: String,
    subPath: String,
    probe: RangeProbe,
    onProgress: suspend (Long, Long) -> Unit
) {
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val appDir = File(downloadsDir, subPath)
    if (!appDir.exists() && !appDir.mkdirs()) {
        throw IOException("Cannot create save directory")
    }

    val targetFile = createUniqueFile(appDir, fileName)
    try {
        RandomAccessFile(targetFile, "rw").use { outputFile ->
            outputFile.setLength(probe.totalLength)
            onProgress(0L, probe.totalLength)
            downloadFileInRanges(
                client = client,
                request = request,
                totalLength = probe.totalLength,
                segmentCount = probe.segmentCount,
                writer = RangeChunkWriter { position, buffer, byteCount ->
                    synchronized(outputFile) {
                        outputFile.seek(position)
                        outputFile.write(buffer, 0, byteCount)
                    }
                },
                onProgress = onProgress
            )
        }
    } catch (error: Exception) {
        if (targetFile.exists() && !targetFile.delete()) {
            Log.w("SaveError", "failed to delete partial download file: ${targetFile.absolutePath}")
        }
        throw error
    }
}

private fun probeVideoRangeSupport(
    client: OkHttpClient,
    request: Request
): RangeProbe? {
    val probeRequest = request.newBuilder()
        .header("Range", "bytes=0-0")
        .header("Accept-Encoding", "identity")
        .build()

    client.newCall(probeRequest).execute().use { response ->
        if (response.code != 206) {
            return null
        }

        val totalLength = parseContentRangeTotal(response.header("Content-Range"))
            ?: return null
        if (totalLength < MIN_PARALLEL_VIDEO_BYTES) {
            return null
        }

        return RangeProbe(
            totalLength = totalLength,
            segmentCount = chooseRangeSegmentCount(totalLength)
        )
    }
}

private fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) {
        return null
    }

    return Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)
        .find(contentRange)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}

private fun chooseRangeSegmentCount(totalLength: Long): Int {
    // 动态分段：小文件少段、大文件多段（上限 MAX_PARALLEL_VIDEO_RANGES）
    val targetCount = ((totalLength + RANGE_SEGMENT_TARGET_BYTES - 1) / RANGE_SEGMENT_TARGET_BYTES)
        .toInt()
        .coerceAtLeast(2)
    // 超大文件（>1GB）再多拉一段提升速度
    val boosted = if (totalLength > 1024L * 1024L * 1024L) targetCount + 2 else targetCount
    return boosted.coerceAtMost(MAX_PARALLEL_VIDEO_RANGES)
}

private fun buildByteRanges(totalLength: Long, segmentCount: Int): List<ByteRange> {
    val segmentSize = totalLength / segmentCount
    var start = 0L
    return (0 until segmentCount).map { index ->
        val end = if (index == segmentCount - 1) {
            totalLength - 1
        } else {
            start + segmentSize - 1
        }
        ByteRange(start, end).also {
            start = end + 1
        }
    }
}

private suspend fun downloadFileInRanges(
    client: OkHttpClient,
    request: Request,
    totalLength: Long,
    segmentCount: Int,
    writer: RangeChunkWriter,
    onProgress: suspend (Long, Long) -> Unit
) = coroutineScope {
    val progress = AtomicLong(0L)
    buildByteRanges(totalLength, segmentCount)
        .map { range ->
            async(Dispatchers.IO) {
                downloadRangeSegment(
                    client = client,
                    request = request,
                    range = range,
                    writer = writer,
                    progress = progress,
                    totalLength = totalLength,
                    onProgress = onProgress
                )
            }
        }
        .awaitAll()

    val downloaded = progress.get()
    if (downloaded != totalLength) {
        throw IOException("range download incomplete: $downloaded/$totalLength")
    }
    onProgress(totalLength, totalLength)
}

private suspend fun downloadRangeSegment(
    client: OkHttpClient,
    request: Request,
    range: ByteRange,
    writer: RangeChunkWriter,
    progress: AtomicLong,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    var nextStart = range.start
    var failedAttempts = 0

    while (nextStart <= range.end) {
        val slice = ByteRange(nextStart, range.end)
        try {
            val written = downloadRangeSlice(
                client = client,
                request = request,
                range = slice,
                writer = writer,
                progress = progress,
                totalLength = totalLength,
                onProgress = onProgress
            )
            if (written <= 0L) {
                throw IOException("range ${slice.start}-${slice.end} made no progress")
            }
            nextStart += written
            failedAttempts = 0
        } catch (error: UnsupportedRangeDownloadException) {
            throw error
        } catch (error: PartialRangeDownloadException) {
            nextStart += error.bytesWritten
            if (nextStart > range.end) {
                return
            }
            failedAttempts++
            if (failedAttempts >= MAX_RANGE_SEGMENT_ATTEMPTS) {
                throw error
            }
            delay(SAVE_RETRY_DELAY_MS * failedAttempts)
        } catch (error: IOException) {
            failedAttempts++
            if (failedAttempts >= MAX_RANGE_SEGMENT_ATTEMPTS) {
                throw error
            }
            delay(SAVE_RETRY_DELAY_MS * failedAttempts)
        }
    }
}

private suspend fun downloadRangeSlice(
    client: OkHttpClient,
    request: Request,
    range: ByteRange,
    writer: RangeChunkWriter,
    progress: AtomicLong,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
): Long {
    val rangeRequest = request.newBuilder()
        .header("Range", "bytes=${range.start}-${range.end}")
        .header("Accept-Encoding", "identity")
        .build()

    client.newCall(rangeRequest).execute().use { response ->
        if (response.code != 206) {
            throw UnsupportedRangeDownloadException("range response code ${response.code}")
        }

        val body = response.body ?: throw IOException("range response body empty")
        body.byteStream().use { inputStream ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var bytesRead: Int
            var written = 0L
            var lastUpdateMillis = 0L

            try {
                while (written < range.length) {
                    val maxRead = minOf(buffer.size.toLong(), range.length - written).toInt()
                    if (inputStream.read(buffer, 0, maxRead).also { bytesRead = it } == -1) {
                        break
                    }
                    writer.write(range.start + written, buffer, bytesRead)
                    written += bytesRead
                    val downloaded = progress.addAndGet(bytesRead.toLong())

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMillis >= PROGRESS_UPDATE_INTERVAL_MS) {
                        onProgress(downloaded.coerceAtMost(totalLength), totalLength)
                        lastUpdateMillis = now
                    }
                }
            } catch (error: UnsupportedRangeDownloadException) {
                throw error
            } catch (error: IOException) {
                if (written > 0L) {
                    throw PartialRangeDownloadException(written, error)
                }
                throw error
            }

            return written
        }
    }
}

private fun writeFully(
    channel: FileChannel,
    buffer: ByteArray,
    byteCount: Int,
    position: Long
) {
    var offset = 0
    var writePosition = position
    try {
        while (offset < byteCount) {
            val byteBuffer = ByteBuffer.wrap(buffer, offset, byteCount - offset)
            val written = channel.write(byteBuffer, writePosition)
            if (written <= 0) {
                throw IOException("channel write made no progress")
            }
            offset += written
            writePosition += written
        }
    } catch (error: IOException) {
        if (isPositionalWriteUnsupported(error)) {
            throw UnsupportedRangeDownloadException("positional write unsupported")
        }
        throw error
    } catch (error: RuntimeException) {
        throw IOException("channel write failed", error)
    }
}

private fun isPositionalWriteUnsupported(error: IOException): Boolean {
    val message = error.message.orEmpty().lowercase()
    return message.contains("illegal seek") ||
        message.contains("espipe") ||
        message.contains("not seekable")
}

private fun shouldRetrySave(error: Exception): Boolean {
    if (error is SocketTimeoutException) {
        return true
    }

    if (error is IOException) {
        val message = error.message.orEmpty().lowercase()
        return message.contains("stream was reset") ||
            message.contains("internal_error") ||
            message.contains("timeout") ||
            message.contains("unexpected end of stream") ||
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("failed to connect")
    }

    return false
}

private fun isDuplicateMediaStoreError(error: Exception): Boolean {
    val message = error.message.orEmpty()
    return message.contains("UNIQUE constraint failed", ignoreCase = true) ||
        message.contains("code 2067", ignoreCase = true)
}

private fun buildUniqueFileName(fileName: String, suffix: Int): String {
    val suffixNumber = suffix + 1
    val lastDot = fileName.lastIndexOf('.')
    return if (lastDot > 0) {
        val baseName = fileName.substring(0, lastDot)
        val extension = fileName.substring(lastDot)
        "${baseName}_$suffixNumber$extension"
    } else {
        "${fileName}_$suffixNumber"
    }
}

private fun createUniqueFile(directory: File, fileName: String): File {
    var candidate = File(directory, fileName)
    if (!candidate.exists()) {
        return candidate
    }

    val nameWithoutExtension = candidate.nameWithoutExtension
    val extension = candidate.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
    var counter = 2
    while (candidate.exists()) {
        candidate = File(directory, "${nameWithoutExtension}_$counter$extension")
        counter++
    }
    return candidate
}

private fun ensureExtension(name: String, extension: String): String {
    return if (name.lowercase().endsWith(".$extension")) {
        name
    } else {
        "$name.$extension"
    }
}

private suspend fun copyStreamWithProgress(
    inputStream: InputStream,
    outputStream: OutputStream,
    totalLength: Long,
    onProgress: suspend (Long, Long) -> Unit
) {
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var bytesRead: Int
    var totalBytesRead = 0L
    var lastUpdateMillis = 0L

    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        outputStream.write(buffer, 0, bytesRead)
        totalBytesRead += bytesRead

        val now = System.currentTimeMillis()
        if (totalLength > 0 && now - lastUpdateMillis >= PROGRESS_UPDATE_INTERVAL_MS) {
            onProgress(totalBytesRead, totalLength)
            lastUpdateMillis = now
        }
    }

    outputStream.flush()
    onProgress(totalBytesRead, totalLength)
}
