package com.guixing.jixunying

import com.guixing.jixunying.engine.localVideoFrames
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 电脑上用程序带的 FFmpeg 取视频画面（2026-11-03 用户：「我从微信给他发视频文件他看不到，必须用 app 才行」：微信发来的是 HEVC，Edge 解不了）。
 * 测试里用 FFmpeg 自己做一段 3 秒的小视频（MPEG-4 编码，LGPL 版自带），前一半红、后一半蓝，看取出来的画面对不对。
 * 真 HEVC 的那段用 LiveVideoReadTest 在本机试（用户的视频不进仓库）。
 */
class VideoFramesTest {
    private val dir = kotlin.io.path.createTempDirectory("jxy-frames").toFile()

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun makeVideo(f: File) {
        val r = FFmpegFrameRecorder(f, 320, 240)
        r.format = "mp4"; r.videoCodec = avcodec.AV_CODEC_ID_MPEG4; r.frameRate = 10.0; r.videoBitrate = 400_000
        r.start()
        val conv = Java2DFrameConverter()
        repeat(30) { i ->
            val img = BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR)
            val g = img.createGraphics(); g.color = if (i < 15) Color(220, 30, 30) else Color(30, 30, 220); g.fillRect(0, 0, 320, 240); g.dispose()
            r.record(conv.convert(img))
        }
        r.stop(); r.release()
    }

    @Test
    fun grabsFramesSpreadOverTheVideo() {
        val f = File(dir, "a.mp4")
        makeVideo(f)
        val (frames, secs) = assertNotNull(localVideoFrames(f, 4, 160))
        assertEquals(4, frames.size)
        assertTrue(secs in 2.5..3.5, "整段 $secs 秒")
        val imgs = frames.map { ImageIO.read(it.inputStream()) }
        assertTrue(imgs.all { maxOf(it.width, it.height) <= 160 }, "长边不超过 160")
        fun reddish(i: BufferedImage) = Color(i.getRGB(i.width / 2, i.height / 2)).let { it.red > it.blue + 80 }
        assertTrue(reddish(imgs.first()) && !reddish(imgs.last()), "前面红、后面蓝：按时间均匀取的")
    }

    @Test
    fun notAVideoGivesNull() {
        val f = File(dir, "b.mp4").apply { writeText("不是视频") }
        assertNull(localVideoFrames(f, 4, 160), "解不开就返回 null，交给 Edge")
    }
}
