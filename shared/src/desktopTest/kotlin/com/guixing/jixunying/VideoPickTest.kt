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
