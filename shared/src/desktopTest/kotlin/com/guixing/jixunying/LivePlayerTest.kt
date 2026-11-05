package com.guixing.jixunying

import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.windows.WindowsVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 软件里放视频（2026-11-04）：用电脑上的 Windows Media Foundation（ComposeMediaPlayer 库）真打开几段视频，看认不认、能不能放。
 * JXY_LIVE_PLAYER=文件1,文件2…（AI 做的 H.264、微信发来的 HEVC 都试）。不出窗口，只看播放器状态。
 */
class LivePlayerTest {
    @Test
    fun windowsPlaysFiles() = runBlocking {
        val files = System.getenv("JXY_LIVE_PLAYER")?.split(',')?.map { File(it.trim()) } ?: return@runBlocking
        suspend fun play(f: File): Boolean {
            val p = WindowsVideoPlayerState()
            p.openUri(f.absolutePath, InitialPlayerState.PLAY)
            var waited = 0
            while (waited < 15_000 && p.error == null && !(p.hasMedia && p.duration > 0 && p.currentTime > 0.5)) { delay(250); waited += 250 }
            println("【放视频】${f.name}：有媒体 ${p.hasMedia}，时长 ${p.duration} 秒，放到 ${p.currentTime} 秒，在放 ${p.isPlaying}，宽高比 ${p.aspectRatio}，出错 ${p.error}，等了 $waited 毫秒")
            val ok = p.error == null && p.currentTime > 0.5
            p.stop()
            p.dispose()
            return ok
        }
        for (f in files) {
            if (play(f)) continue
            // 放不了的（HEVC）转一份再放，和软件里一样
            val dst = File(f.parentFile, f.nameWithoutExtension + ".play.mp4").apply { delete() }
            val t0 = System.currentTimeMillis()
            val ok = com.guixing.jixunying.engine.transcodePlayable(f, dst)
            println("【转格式】${f.name}：${if (ok) "转好了" else "没转成"}，${System.currentTimeMillis() - t0} 毫秒，${dst.length()} 字节")
            if (ok) play(dst)
        }
    }
}
