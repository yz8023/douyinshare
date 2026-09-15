package Forinxy.jiexi

import Forinxy.jiexi.builtin.GalleryItem
import Forinxy.jiexi.builtin.LyricLine
import Forinxy.jiexi.builtin.MediaData
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 共享解析结果载体输出的 data.php 兼容 JSON 结构校验 */
class MediaDataJsonTest {

    private fun parseRoot(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun video_output_matches_server_schema() {
        val md = MediaData()
        md.author = "UP主"
        md.authorUid = "10086"
        md.title = "测试视频"
        md.videoId = "bv123"
        md.type = "video"
        md.playUrl = "https://example.com/v.mp4"
        md.cover = "https://example.com/c.jpg"
        md.duration = 12.5
        md.addQuality("高清", "1080p", "https://example.com/v.mp4", sizeBytes = 1000)

        val root = parseRoot(md.toServerJson())
        assertTrue(root.get("success").asBoolean)
        assertEquals("UP主", root.get("author").asString)
        assertEquals("10086", root.get("author_uid").asString)
        assertEquals("测试视频", root.get("title").asString)
        assertEquals("video", root.get("type").asString)
        assertEquals("bv123", root.get("video_id").asString)
        assertEquals(12.5, root.get("duration").asDouble, 0.001)
        assertTrue(root.has("gallery_media"))
        assertTrue(root.has("images"))
        assertEquals(1, root.getAsJsonArray("quality_list").size())
    }

    @Test
    fun image_output_with_gallery_and_live_photo() {
        val md = MediaData()
        md.type = "image"
        md.addGalleryItem(GalleryItem(imageUrl = "https://example.com/1.jpg"))
        md.addGalleryItem(GalleryItem(livePhotoRawUrl = "https://example.com/1.mov"))

        val root = parseRoot(md.toServerJson())
        assertEquals("image", root.get("type").asString)
        val images = root.getAsJsonArray("images")
        assertEquals(2, images.size())
        assertEquals("https://example.com/1.jpg", images[0].asString)
        assertTrue(images[1].isJsonNull)

        val gallery = root.getAsJsonArray("gallery_media")
        assertEquals(0, gallery[0].asJsonObject.get("index").asInt)
        assertEquals("https://example.com/1.mov", gallery[1].asJsonObject.get("live_photo_raw_url").asString)
        assertTrue(gallery[1].asJsonObject.get("has_live_photo").asBoolean)
        // 图集不输出 quality_list
        assertEquals(0, root.getAsJsonArray("quality_list").size())
    }

    @Test
    fun music_output_sets_audio_play() {
        val md = MediaData()
        md.type = "music"
        md.playUrl = "https://example.com/song.mp3"
        md.videoId = "track123"

        val root = parseRoot(md.toServerJson())
        assertEquals("music", root.get("type").asString)
        assertEquals("https://example.com/song.mp3", root.get("play_url").asString)
        md.hasGallery.let { assertFalse(it) }
    }

    @Test
    fun null_optional_fields_serialize_as_json_null() {
        val md = MediaData()
        val root = parseRoot(md.toServerJson())
        assertTrue(root.get("author_uid").isJsonNull)
        assertTrue(root.get("cover").isJsonNull)
        assertTrue(root.get("resolved_url").isJsonNull)
    }

    @Test
    fun music_lyrics_serialize_with_lrc_fields() {
        val md = MediaData()
        md.type = "music"
        md.playUrl = "https://example.com/song.mp3"
        md.lyrics.add(LyricLine(text = "第一句", start = 1.5, end = 4.5))
        md.lyrics.add(LyricLine(text = "第二句（无时间）"))

        val root = parseRoot(md.toServerJson())
        val lyrics = root.getAsJsonArray("lyrics")
        assertEquals(2, lyrics.size())
        assertEquals("第一句", lyrics[0].asJsonObject.get("text").asString)
        assertEquals(1.5, lyrics[0].asJsonObject.get("start").asDouble, 0.001)
        assertEquals(4.5, lyrics[0].asJsonObject.get("end").asDouble, 0.001)
        assertTrue(lyrics[1].asJsonObject.get("start").isJsonNull)
    }

    @Test
    fun non_music_output_has_empty_lyrics() {
        val md = MediaData()
        val root = parseRoot(md.toServerJson())
        assertTrue(root.has("lyrics"))
        assertEquals(0, root.getAsJsonArray("lyrics").size())
    }
}