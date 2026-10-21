package com.guixing.jixunying

import com.guixing.jixunying.engine.VideoInput
import com.guixing.jixunying.engine.VideoMedia
import com.guixing.jixunying.model.VideoMediaUse
import com.guixing.jixunying.model.VideoPick
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 做视频的素材：发之前的本地检查（官方限制见 docs/参考资料.md「视频生成」）、读视频 / 音频时长。 */
class VideoMediaTest {

    companion object {
        /** 最小的 MP4：ftyp + moov（只有 mvhd，版本 0，timescale 1000）。 */
        fun tinyMp4(seconds: Double): ByteArray {
            val out = ByteArrayOutputStream()
            fun u32(v: Long) { out.write(((v shr 24) and 0xff).toInt()); out.write(((v shr 16) and 0xff).toInt()); out.write(((v shr 8) and 0xff).toInt()); out.write((v and 0xff).toInt()) }
            u32(16); out.write("ftypisom".toByteArray()); u32(0x200)
            val mvhd = ByteArray(100).also { b ->
                fun put(i: Int, v: Long) { b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte(); b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte() }
                put(12, 1000); put(16, (seconds * 1000).toLong())
            }
            u32(8L + 8 + mvhd.size); out.write("moov".toByteArray())
            u32(8L + mvhd.size); out.write("mvhd".toByteArray()); out.write(mvhd)
            return out.toByteArray()
        }

        /** 最小的 WAV：16kHz 单声道 16 位，seconds 秒的静音。 */
        fun tinyWav(seconds: Double): ByteArray {
            val data = (16000 * 2 * seconds).toInt()
            val out = ByteArrayOutputStream()
            fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xff) }
            out.write("RIFF".toByteArray()); le(36 + data, 4); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2); le(16000, 4); le(32000, 4); le(2, 2); le(16, 2)
            out.write("data".toByteArray()); le(data, 4); out.write(ByteArray(data))
            return out.toByteArray()
        }
    }

    private fun img(use: String, name: String = "a.png", size: Int = 10) = VideoInput(use, name, "image/png", ByteArray(size))
    private fun vid(secs: Double, name: String = "v.mp4") = VideoInput(VideoMediaUse.VIDEO, name, "video/mp4", ByteArray(10), seconds = secs)
    private fun aud(secs: Double) = VideoInput(VideoMediaUse.AUDIO, "a.wav", "audio/wav", ByteArray(10), seconds = secs)

    @Test
    fun readsDurations() {
        assertEquals(2.5, VideoMedia.durationOf("x.mp4", "video/mp4", tinyMp4(2.5))!!, 1e-9)
        assertEquals(3.0, VideoMedia.durationOf("x.wav", "audio/wav", tinyWav(3.0))!!, 1e-9)
        assertNull(VideoMedia.durationOf("x.mp4", "video/mp4", ByteArray(20)), "不是 MP4 读不出来就给 null，交给服务端查")
        // MP3：128kbps 的一帧头 FF FB 90，按固定码率估
        val mp3 = ByteArray(16000 * 5).also { it[0] = 0xff.toByte(); it[1] = 0xfb.toByte(); it[2] = 0x90.toByte() }
        assertEquals(5.0, VideoMedia.durationOf("x.mp3", "audio/mpeg", mp3)!!, 0.01)
    }

    @Test
    fun wanRules() {
        val w = VideoPick.model("wan3.0-video")
        assertNull(VideoMedia.check(w, listOf(img(VideoMediaUse.FIRST)), 5))
        assertNull(VideoMedia.check(w, listOf(img(VideoMediaUse.FIRST), img(VideoMediaUse.LAST)), 5))
        assertNotNull(VideoMedia.check(w, listOf(img(VideoMediaUse.LAST)), 5), "万相不能只给尾帧")
        assertTrue("互斥" in VideoMedia.check(w, listOf(img(VideoMediaUse.FIRST), img(VideoMediaUse.IMAGE)), 5)!!)
        assertTrue("10 张" in VideoMedia.check(w, List(11) { img(VideoMediaUse.IMAGE) }, 5)!!)
        assertTrue("15 秒" in VideoMedia.check(w, listOf(vid(10.0), vid(6.0)), -1)!!)
        assertTrue("15 秒" in VideoMedia.check(w, listOf(aud(8.0), aud(8.0)), 5)!!)
        assertTrue("30 秒" in VideoMedia.check(w, listOf(vid(10.0)), 25)!!, "输入 + 输出 ≤30 秒")
        assertNull(VideoMedia.check(w, listOf(vid(10.0)), -1), "智能时长不查")
        val doc = VideoInput(VideoMediaUse.FILE, "报告.pdf", "application/pdf", ByteArray(10))
        val link = VideoInput(VideoMediaUse.LINK, "a", "text/html", url = "https://example.com/a")
        assertNull(VideoMedia.check(w, listOf(doc, img(VideoMediaUse.IMAGE)), 5))
        assertTrue("二选一" in VideoMedia.check(w, listOf(doc, link), 5)!!)
        assertNotNull(VideoMedia.check(w, listOf(VideoInput(VideoMediaUse.FILE, "a.zip", "application/zip", ByteArray(1))), 5), "压缩包不能当文档")
        assertNotNull(VideoMedia.check(w, listOf(VideoInput(VideoMediaUse.FIRST, "a.mp4", "video/mp4", ByteArray(1))), 5), "视频不能当首帧")
        assertTrue("20 MB" in VideoMedia.check(w, listOf(img(VideoMediaUse.IMAGE, size = 21 * 1024 * 1024)), 5)!!)
        // 网址交给服务端查
        assertNull(VideoMedia.check(w, listOf(VideoInput(VideoMediaUse.VIDEO, "x", "video/mp4", url = "https://example.com/x.mp4")), -1))
    }

    @Test
    fun minimaxRules() {
        val m = VideoPick.model("MiniMax-H3-Max")
        assertNull(VideoMedia.check(m, listOf(img(VideoMediaUse.LAST)), 5), "MiniMax 能只给尾帧")
        assertTrue("收不了文档" in VideoMedia.check(m, listOf(VideoInput(VideoMediaUse.FILE, "a.pdf", "application/pdf", ByteArray(1))), 5)!!)
        assertTrue("9 张" in VideoMedia.check(m, List(10) { img(VideoMediaUse.IMAGE) }, 5)!!)
        assertTrue("3 段" in VideoMedia.check(m, List(4) { vid(2.0) }, 5)!!)
        assertTrue("2～15 秒" in VideoMedia.check(m, listOf(vid(1.0)), 5)!!, "每段至少 2 秒")
        assertTrue("格式不对" in VideoMedia.check(m, listOf(vid(3.0, "a.webm").let { VideoInput(it.use, it.name, it.mime, it.bytes, seconds = 3.0) }), 5)!!)
        assertTrue("64 MB" in VideoMedia.check(m, listOf(VideoInput(VideoMediaUse.VIDEO, "a.mp4", "video/mp4", ByteArray(48 * 1024 * 1024), seconds = 5.0)), 5)!!,
            "Base64 放大以后超过请求体上限")
        assertTrue("12 个" in VideoMedia.check(m, List(9) { img(VideoMediaUse.IMAGE) } + List(2) { vid(2.0) } + List(2) { aud(2.0) }, 5)!!)
    }
}
