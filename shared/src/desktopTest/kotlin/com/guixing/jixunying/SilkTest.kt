package com.guixing.jixunying

import org.jitsi.impl.neomedia.codec.audio.silk.WeixinSilk
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 微信语音（SILK）解码（2026-11-05）：样本 weixin-voice.silk 是用下面 synth() 合成的 3 秒「元音」编出来的（来历见 silk/README.md），
 * 解回来要是 3 秒、24 kHz，和原始信号对得上；两种文件头（腾讯的 0x02 开头、标准的 #!SILK_V3 开头）都认；不是 SILK 的给 null。
 * 真实微信语音的对照（别人解好的参照 PCM）在 LiveSilkTest。
 */
class SilkTest {
    private val fixture: ByteArray = javaClass.getResource("/weixin-voice.silk")!!.readBytes()

    /** 和造样本时同一个公式（silk/README.md「测试样本」）。 */
    private fun synth(): ShortArray {
        val rate = 24000
        val n = rate * 3
        val s = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f0 = 120 + 100 * t / 3.0
            phase += 2 * PI * f0 / rate
            var v = 0.0
            for (h in 1..12) {
                val fh = f0 * h
                val w = exp(-((fh - 700) / 300).pow(2)) + 0.6 * exp(-((fh - 1200) / 400).pow(2)) + 0.15
                v += w * sin(h * phase) / h
            }
            var env = 0.5 - 0.5 * cos(2 * PI * 4 * t)
            if (t > 1.3 && t < 1.7) env = 0.0
            s[i] = (9000 * env * v).coerceIn(-32000.0, 32000.0).toInt().toShort()
        }
        return s
    }

    private fun samples(pcm: ByteArray) = ShortArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xff) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }

    private fun rms(s: ShortArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += s[i].toDouble() * s[i]
        return sqrt(e / (to - from))
    }

    @Test
    fun decodesWeixinVoice() {
        assertTrue(WeixinSilk.isSilk(fixture))
        assertEquals(10, WeixinSilk.headerLength(fixture), "腾讯写法：0x02 + #!SILK_V3")
        val pcm = assertNotNull(WeixinSilk.decode(fixture, 24000))
        assertEquals(3 * 24000 * 2, pcm.size, "150 帧 × 20 毫秒 = 3 秒")
        val got = samples(pcm)
        val want = synth()
        // 编解码有一点延迟：在 0～25 毫秒里找最像的对齐
        var best = 0.0
        var bestLag = 0
        for (lag in 0..600) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in 0 until want.size - lag) {
                val x = want[i].toDouble(); val y = got[i + lag].toDouble()
                xy += x * y; xx += x * x; yy += y * y
            }
            val c = xy / sqrt(xx * yy)
            if (c > best) { best = c; bestLag = lag }
        }
        println("【SILK 样本】和原始信号最像 ${"%.3f".format(best)}（延迟 $bestLag 个采样）；有声段 ${"%.0f".format(rms(got, 6000, 12000))}、静音段 ${"%.0f".format(rms(got, 34000, 39000))}")
        assertTrue(best > 0.8, "解出来的和原始信号对得上：$best")
        assertTrue(rms(got, 34000, 39000) < rms(got, 6000, 12000) / 20, "1.4～1.6 秒那段静音解出来也安静")
    }

    @Test
    fun standardHeaderAndRubbish() {
        val standard = fixture.copyOfRange(1, fixture.size)
        assertEquals(9, WeixinSilk.headerLength(standard), "标准写法：#!SILK_V3 开头")
        assertContentEquals(WeixinSilk.decode(fixture, 24000), WeixinSilk.decode(standard, 24000))
        assertFalse(WeixinSilk.isSilk("RIFF....WAVE".toByteArray()))
        assertNull(WeixinSilk.decode(ByteArray(100) { it.toByte() }, 24000), "不是 SILK 给 null")
        // 截断在一帧中间：解出前面完整的帧，不抛错
        val cut = WeixinSilk.decode(fixture.copyOf(fixture.size / 2), 24000)!!
        assertTrue(cut.isNotEmpty() && cut.size < 3 * 24000 * 2)
        // 16 kHz 输出也行（微信默认 24 kHz）
        assertEquals(3 * 16000 * 2, WeixinSilk.decode(fixture, 16000)!!.size)
    }
}
