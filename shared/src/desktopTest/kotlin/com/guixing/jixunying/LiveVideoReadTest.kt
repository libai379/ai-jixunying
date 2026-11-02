package com.guixing.jixunying

import com.guixing.jixunying.engine.EdgeBrowser
import com.guixing.jixunying.engine.SpeechText
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 真开 Edge 读本机视频（2026-11-01）：JXY_LIVE_VIDEOREAD=文件1,文件2…；JXY_VIDEOREAD_OUT=把取到的画面存到哪（看一眼对不对）。
 * 用临时资料夹，不碰用户的登录。只打印，不断言（解不解得开看这台电脑的解码器）。不花钱（不调听写）。
 */
class LiveVideoReadTest {
    @Test
    fun readLocalVideos() = runBlocking<Unit> {
        val files = System.getenv("JXY_LIVE_VIDEOREAD")?.split(',')?.map { File(it.trim()) } ?: return@runBlocking
        val out = File(System.getenv("JXY_VIDEOREAD_OUT") ?: return@runBlocking).apply { mkdirs() }
        val profile = kotlin.io.path.createTempDirectory("jxy-video-edge").toFile()
        val b = EdgeBrowser(EdgeBrowser.findBrowser() ?: error("找不到 Edge / Chrome"), profile)
        try {
            for (f in files) {
                println("==== ${f.name}（${f.length()} 字节）")
                val t0 = System.currentTimeMillis()
                runCatching { b.fileFrames(f, 6, 1024) }
                    .onFailure { println("画面解不开：${it.message}") }
                    .onSuccess { (frames, secs) ->
                        println("画面 ${frames.size} 张，整段 $secs 秒，大小 ${frames.map { it.size }}，用了 ${System.currentTimeMillis() - t0} ms")
                        frames.forEachIndexed { i, bytes -> File(out, "${f.nameWithoutExtension}-$i.jpg").writeBytes(bytes) }
                    }
                val t1 = System.currentTimeMillis()
                runCatching { b.fileAudioPcm(f, 600) }
                    .onFailure { println("声音解不开：${it.message}") }
                    .onSuccess { (pcm, secs) ->
                        val w = SpeechText.parseWav(SpeechText.wavOf(16000, 1, 16, pcm))
                        println("声音 ${pcm.size} 字节，整段 $secs 秒，静音=${w?.let { SpeechText.isSilent(it) }}，用了 ${System.currentTimeMillis() - t1} ms")
                    }
            }
        } finally {
            b.close()
            Thread.sleep(800)
            profile.deleteRecursively()
        }
    }
}
