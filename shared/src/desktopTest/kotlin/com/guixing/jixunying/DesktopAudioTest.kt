package com.guixing.jixunying

import com.guixing.jixunying.engine.SpeechText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 电脑上真的用 javax.sound 放一段**静音**的 wav（全是 0，不会出声）：
 * 点「停止」（取消协程）要马上停，不能等缓冲里剩下的放完；不取消就放完才返回。
 * 这台机器没有放音设备时（比如没有声卡的服务器）直接跳过。
 */
class DesktopAudioTest {
    private fun silence(seconds: Double, rate: Int = 24000) = SpeechText.wavOf(rate, 1, 16, ByteArray((rate * seconds).toInt() * 2))

    private fun hasOutput(): Boolean = runCatching {
        val f = javax.sound.sampled.AudioFormat(24000f, 16, 1, true, false)
        javax.sound.sampled.AudioSystem.isLineSupported(javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, f))
    }.getOrDefault(false)

    @Test
    fun stopIsImmediateAndNormalPlayFinishes() = runBlocking {
        if (!hasOutput()) { println("这台机器没有放音设备，跳过"); return@runBlocking }
        val p = DesktopPlatform { null }

        // 3 秒的静音，0.3 秒后点停止：要在 1 秒内结束（以前 drain 不理会取消，要等缓冲放完）
        val t0 = System.currentTimeMillis()
        val job = launch { p.playWav(silence(3.0)) }
        delay(300)
        job.cancel()
        job.join()
        val stopMs = System.currentTimeMillis() - t0
        println("3 秒静音，0.3 秒时停止，实际 $stopMs 毫秒结束")
        assertTrue(stopMs < 1300, "点停止要马上停，用了 $stopMs 毫秒")

        // 比放音缓冲还短的一段：一下子全写进缓冲，剩下全靠 drain 等。以前 drain 不理会取消，点停止要等它放完
        val bufSec = runCatching {
            val f = javax.sound.sampled.AudioFormat(24000f, 16, 1, true, false)
            val l = javax.sound.sampled.AudioSystem.getSourceDataLine(f)
            l.open(f)
            val s = l.bufferSize / (24000.0 * 2)
            l.close()
            s
        }.getOrDefault(0.5)
        val clip = (bufSec * 0.8).coerceAtLeast(0.3)
        val t2 = System.currentTimeMillis()
        val job2 = launch { p.playWav(silence(clip)) }
        delay(80)
        job2.cancel()
        job2.join()
        val drainMs = System.currentTimeMillis() - t2
        println("放音缓冲约 ${"%.2f".format(bufSec)} 秒；${"%.2f".format(clip)} 秒静音，0.08 秒时停止，实际 $drainMs 毫秒结束")
        assertTrue(drainMs < 80 + (clip * 1000 * 0.5).toLong(), "缓冲里剩下的那截也要马上停，用了 $drainMs 毫秒")

        // 0.4 秒的静音不取消：放完才返回（至少接近 0.4 秒，也不会卡住不回来）
        val t1 = System.currentTimeMillis()
        p.playWav(silence(0.4))
        val playMs = System.currentTimeMillis() - t1
        println("0.4 秒静音放完用了 $playMs 毫秒")
        assertTrue(playMs in 300..4000, "放完才返回，用了 $playMs 毫秒")
    }
}
