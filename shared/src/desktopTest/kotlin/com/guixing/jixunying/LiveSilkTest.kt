package com.guixing.jixunying

import org.jitsi.impl.neomedia.codec.audio.silk.WeixinSilk
import java.io.File
import kotlin.test.Test

/**
 * 微信语音（SILK）解码对照（2026-11-05）：JXY_SILK_SAMPLE=文件夹，里面放 sample.silk 和别人解好的 ref.pcm（24 kHz、16 位单声道），
 * 看我们解出来的和参照差多少。样本不进仓库（来源的许可证不允许带进来）。
 */
class LiveSilkTest {
    @Test
    fun matchesReference() {
        val dir = File(System.getenv("JXY_SILK_SAMPLE") ?: return)
        val silk = File(dir, "sample.silk").readBytes()
        val t0 = System.nanoTime()
        val pcm = WeixinSilk.decode(silk, 24000)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        val ref = File(dir, "ref.pcm").readBytes()
        val n = minOf(pcm.size, ref.size) / 2
        var same = 0; var maxDiff = 0; var sig = 0.0; var err = 0.0
        for (i in 0 until n) {
            val a = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
            val b = (ref[2 * i].toInt() and 0xff) or (ref[2 * i + 1].toInt() shl 8)
            if (a == b) same++
            maxDiff = maxOf(maxDiff, kotlin.math.abs(a - b))
            sig += b.toDouble() * b; err += (a - b).toDouble() * (a - b)
        }
        val snr = if (err == 0.0) Double.POSITIVE_INFINITY else 10 * kotlin.math.log10(sig / err)
        println("【SILK】解码 ${ms} 毫秒；我们 ${pcm.size} 字节、参照 ${ref.size} 字节；逐个采样相同 ${same}/${n}（${"%.2f".format(same * 100.0 / n)}%），最大差 $maxDiff，信噪比 ${"%.1f".format(snr)} dB")
        File(dir, "ours.pcm").writeBytes(pcm)
    }
}
