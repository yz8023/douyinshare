package Forinxy.jiexi

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * 实况动态视频「不带音频」处理：用系统原生 MediaExtractor + MediaMuxer 重封装，
 * 仅保留视频轨、剥离音频轨。任何异常返回 false，由调用方回退保留原文件。
 */
object LivePhotoAudioStripper {
    private const val TAG = "LivePhotoAudioStripper"
    private const val BUFFER_SIZE = 1 shl 20

    fun stripAudioTrack(sourcePath: String, targetPath: String): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourcePath)

            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }
            if (videoTrackIndex < 0) {
                Log.w(TAG, "no video track found, skip strip")
                return false
            }

            muxer = MediaMuxer(targetPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoFormat = extractor.getTrackFormat(videoTrackIndex)
            val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION, 0)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }
            val outTrackIndex = muxer.addTrack(videoFormat)
            muxer.start()
            extractor.selectTrack(videoTrackIndex)

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val info = android.media.MediaCodec.BufferInfo()
            var sawEos = false
            while (!sawEos) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    sawEos = true
                    info.size = 0
                    info.flags = android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    muxer.writeSampleData(outTrackIndex, buffer, info)
                } else {
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outTrackIndex, buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "strip audio failed", t)
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }
}
