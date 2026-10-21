package com.guixing.jixunying

import com.guixing.jixunying.engine.VideoGen
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SpeechUnits
import com.guixing.jixunying.model.UsageRecord
import com.guixing.jixunying.model.VideoPick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 做视频的参数：各模型的请求体写法、档位 / 时长不合规时怎么改、价格（出处见 docs/参考资料.md「视频生成」）。 */
class VideoPickTest {

    @Test
    fun requestBodies() {
        // wan3.0：resolution + ratio，能关声音
        val w30 = VideoGen.body(VideoPick.model("wan3.0-video"), "猫", "480P", 5, "9:16", false).toString()
        assertEquals("""{"model":"wan3.0-video","input":{"prompt":"猫"},"parameters":{"resolution":"480P","ratio":"9:16","duration":5,"prompt_extend":true,"watermark":false,"audio":false}}""", w30)
        // wan2.6 及更早：用 size（像素），没有 audio 开关
        val w26 = VideoGen.body(VideoPick.model("wan2.6-t2v"), "猫", "720P", 5, "16:9", true).toString()
        assertTrue("\"size\":\"1280*720\"" in w26 && "resolution" !in w26 && "audio" !in w26, w26)
        // happyhorse 不传 watermark 默认加水印：一律传 false
        val hh = VideoGen.body(VideoPick.model("happyhorse-1.1-t2v"), "猫", "720P", 5, "16:9", true).toString()
        assertTrue("\"watermark\":false" in hh, hh)
    }

    @Test
    fun clampsToWhatTheModelTakes() {
        val w27 = VideoPick.model("wan2.7-t2v")
        assertEquals("720P", VideoPick.resolutionFor(w27, "480P"), "wan2.7 没有 480P，换最接近的 720P")
        assertEquals(15, VideoPick.durationFor(w27, 30))
        assertEquals(5, VideoPick.durationFor(VideoPick.model("wan2.2-t2v-plus"), 10), "wan2.2 固定 5 秒")
        assertEquals("16:9", VideoPick.ratioFor(w27, "21:9"), "wan2.7 没有 21:9")
        assertEquals("21:9", VideoPick.ratioFor(VideoPick.model("wan3.0-video"), "21:9"))
    }

    @Test
    fun whoCanMakeVideos() {
        val qw = ProviderConfig("a", "qianwen", "千问", "https://maas.qianwenaiapi.com/compatible-mode/v1", "k")
        val bl = ProviderConfig("b", "dashscope", "百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "k")
        val ds = ProviderConfig("c", "deepseek", "DeepSeek", "https://api.deepseek.com", "k")
        val noKey = qw.copy(id = "d", apiKey = "")
        assertEquals(listOf("a", "b"), VideoPick.candidates(listOf(bl, ds, noKey, qw)).map { it.id }, "千问AI平台排前面；没 Key 的、别家的不算")
        assertFalse(VideoPick.supports(ds))
    }

    @Test
    fun mediaBodies() {
        // 万相 3.0 带素材：input.media 按顺序，智能时长 -1 照传
        val w = VideoGen.body(VideoPick.model("wan3.0-video"), "编辑视频：改成黏土", "480P", -1, "adaptive", true,
            listOf(VideoGen.Media("reference_video", "oss://a/v.mp4"), VideoGen.Media("reference_image", "oss://a/i.png"))).toString()
        assertTrue(""""media":[{"type":"reference_video","url":"oss://a/v.mp4"},{"type":"reference_image","url":"oss://a/i.png"}]""" in w, w)
        assertTrue(""""duration":-1""" in w && """"ratio":"adaptive"""" in w, w)
        // MiniMax：text 在最前；图生视频 ratio 恒为 adaptive；文生视频 ratio 不能是 adaptive
        val h3 = VideoPick.model("MiniMax-H3-Max")
        val i2v = VideoGen.minimaxBody(h3, "动起来", "480P", 5, "16:9", listOf(VideoGen.Media("first_frame", "data:image/png;base64,AA"))).toString()
        assertEquals("""{"model":"MiniMax-H3-Max","content":[{"type":"text","text":"动起来"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA"},"role":"first_frame"}],""" +
            """"resolution":"480P","duration":5,"ratio":"adaptive","aigc_watermark":false}""", i2v)
        assertTrue(""""ratio":"16:9"""" in VideoGen.minimaxBody(h3, "猫", "480P", 5, "adaptive").toString())
        val r2v = VideoGen.minimaxBody(h3, "照着视频1", "480P", 5, "adaptive", listOf(VideoGen.Media("reference_video", "https://x/v.mp4"), VideoGen.Media("reference_audio", "https://x/a.mp3"))).toString()
        assertTrue(""""type":"video_url","video_url":{"url":"https://x/v.mp4"},"role":"reference_video"""" in r2v, r2v)
        assertTrue(""""type":"audio_url","audio_url":{"url":"https://x/a.mp3"},"role":"reference_audio"""" in r2v && """"ratio":"adaptive"""" in r2v, r2v)
    }

    @Test
    fun switchesModelForMaterials() {
        val qw = ProviderConfig("a", "qianwen", "千问", "https://maas.qianwenaiapi.com/compatible-mode/v1", "k")
        val mm = ProviderConfig("m", "minimax", "MiniMax", "https://api.minimaxi.com/v1", "k")
        val mmGlobal = ProviderConfig("g", "minimax-global", "MiniMax 国际", "https://api.minimax.io/v1", "k")
        assertEquals(VideoPick.MINIMAX, VideoPick.vendorOf(mm))
        assertNull(VideoPick.vendorOf(mmGlobal), "国际版的视频接口没核对过，不认")
        fun st(providers: List<ProviderConfig>, model: String, providerId: String = "") = com.guixing.jixunying.model.AppState(providers = providers,
            settings = com.guixing.jixunying.model.Settings(videoGen = com.guixing.jixunying.model.VideoGenSettings(modelId = model, providerId = providerId)))
        // 选了只能文生视频的：带首帧换 wan3.0（同一家）
        val c1 = VideoPick.resolve(st(listOf(qw, mm), "wan2.7-t2v"), setOf("first_frame"))!!
        assertEquals("wan3.0-video", c1.model.id); assertEquals("a", c1.providerId); assertTrue("改用 wan3.0-video" in c1.switched, c1.switched)
        // 不带素材就用设置里的
        assertEquals("wan2.7-t2v", VideoPick.resolve(st(listOf(qw, mm), "wan2.7-t2v"))!!.model.id)
        // 选了 MiniMax：文档它收不了，换千问的 wan3.0
        val c2 = VideoPick.resolve(st(listOf(qw, mm), "MiniMax-H3-Max", "m"), setOf("file"))!!
        assertEquals("wan3.0-video", c2.model.id); assertEquals("a", c2.providerId); assertTrue("千问" in c2.switched, c2.switched)
        // 只有 MiniMax：默认用 H3-Max；文档谁都收不了，返回原来的（由检查报错）
        assertEquals("MiniMax-H3-Max", VideoPick.resolve(st(listOf(mm), "wan3.0-video"))!!.model.id)
        val c3 = VideoPick.resolve(st(listOf(mm), "wan3.0-video"), setOf("file"))!!
        assertEquals("MiniMax-H3-Max", c3.model.id); assertEquals("", c3.switched)
        // 设置页只列有 Key 的那几家的模型
        assertTrue(VideoPick.modelsFor(listOf(mm)).all { it.vendor == VideoPick.MINIMAX })
        assertEquals(2, VideoPick.modelsFor(listOf(mm)).size)
        // MiniMax 档位：没有 480P 的 H3 换 768P
        assertEquals("768P", VideoPick.resolutionFor(VideoPick.model("MiniMax-H3"), "480P"))
        assertEquals(5, VideoPick.durationFor(VideoPick.model("MiniMax-H3-Max"), 2), "H3-Max 最短 5 秒")
    }

    @Test
    fun minimaxPrices() {
        fun cost(model: String, secs: Double = 0.0, images: Int = 0): Double? {
            val r = if (images > 0) UsageRecord(0, "video", "p", "MiniMax", "minimax", model, images = images)
                else UsageRecord(0, "video", "p", "MiniMax", "minimax", model, units = secs, unit = SpeechUnits.SECOND)
            return Prices.cost(r, Prices.of("minimax", model))
        }
        assertEquals(1.65, cost("MiniMax-H3-Max 480P", 5.0)!!, 1e-9)
        assertEquals(2.5, cost("MiniMax-H3-Max 768P", 5.0)!!, 1e-9)
        assertEquals(0.74, cost("MiniMax-H3-Max 480P 输入视频", 2.0)!!, 1e-9)
        assertEquals(1.94, cost("MiniMax-H3-Max 768P 输入视频", 2.0)!!, 1e-9)
        assertEquals(2.5, cost("MiniMax-H3 768P", 5.0)!!, 1e-9)
        assertEquals(4.0, cost("MiniMax-H3 2K", 5.0)!!, 1e-9)
        assertEquals(1.0, cost("MiniMax-H3 2K 输入视频", 1.25)!!, 1e-9)
        assertEquals(1.0, cost("MiniMax-H3-Max 图片", images = 2)!!, 1e-9)
        assertEquals(0.4, cost("MiniMax-H3 图片", images = 2)!!, 1e-9)
        assertNull(Prices.of("minimax", "MiniMax-H3 768P", baseUrl = "https://api.minimax.io/v1"), "国际站价格没查")
    }

    @Test
    fun pricesPerSecondByResolution() {
        fun cost(model: String, secs: Double): Double? {
            val r = UsageRecord(0, "video", "p", "千问", "qwen", model, units = secs, unit = SpeechUnits.SECOND)
            return Prices.cost(r, Prices.of("qwen", model))
        }
        assertEquals(1.5, cost("wan3.0-video 480P", 5.0)!!, 1e-9)
        assertEquals(6.0, cost("wan3.0-video 1080P", 5.0)!!, 1e-9)
        assertEquals(4.5, cost("wan3.0-video-prime 720P", 5.0)!!, 1e-9)
        assertEquals(3.0, cost("wan2.7-t2v 720P", 5.0)!!, 1e-9)
        assertEquals(0.7, cost("wan2.2-t2v-plus 480P", 5.0)!!, 1e-9)
        assertNull(cost("no-such-video 480P", 5.0))
    }
}
