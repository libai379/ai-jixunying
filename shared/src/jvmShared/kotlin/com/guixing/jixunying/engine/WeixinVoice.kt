package com.guixing.jixunying.engine

import org.jitsi.impl.neomedia.codec.audio.silk.WeixinSilk

/**
 * 微信语音（2026-11-05）：SILK v3（腾讯写法 0x02 + #!SILK_V3）解成 24 kHz 单声道 16 位 wav。
 * 24 kHz 照腾讯官方插件 src/media/silk-transcode.ts 的 SILK_SAMPLE_RATE。不是 SILK、或者一点声音都没解出来，返回 null。
 * 解码用 silk 模块（libjitsi 的纯 Java 移植，见 silk/README.md），电脑和手机一样。
 */
object WeixinVoice {
    const val SAMPLE_RATE = 24_000

    fun isSilk(raw: ByteArray): Boolean = WeixinSilk.isSilk(raw)

    fun silkToWav(raw: ByteArray): ByteArray? {
        val pcm = WeixinSilk.decode(raw, SAMPLE_RATE) ?: return null
        if (pcm.isEmpty()) return null
        return SpeechText.wavOf(SAMPLE_RATE, 1, 16, pcm)
    }

    /** wav 有几秒（44 字节文件头 + 16 位单声道）。 */
    fun seconds(wav: ByteArray): Int = ((wav.size - 44) / (SAMPLE_RATE * 2)).coerceAtLeast(1)
}
