package com.guixing.jixunying

import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.VideoGen
import com.guixing.jixunying.model.VideoPick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 真做一段视频：用数据文件夹里第一个能做视频的服务商（千问AI平台优先），存到 JXY_VIDEO_OUT。
 * 只在设了 JXY_LIVE_VIDEO=数据文件夹（用副本，别直接指用户的）时跑；JXY_VIDEO_MODEL / JXY_VIDEO_RES / JXY_VIDEO_SECS 可改，
 * 默认 wan3.0-video、480P、2 秒（百炼原价每秒 0.3 元）。会花钱，跑之前算好预算。不打印 Key。
 */
class LiveVideoTest {
    @Test
    fun makeVideoWithRealModel() = runBlocking {
        val dir = System.getenv("JXY_LIVE_VIDEO") ?: return@runBlocking
        val out = File(System.getenv("JXY_VIDEO_OUT") ?: return@runBlocking).apply { mkdirs() }
        val state = Storage(File(dir)).loadState() ?: return@runBlocking
        val p = VideoPick.candidates(state.providers).firstOrNull() ?: run { println("没有能做视频的服务商"); return@runBlocking }
        val model = System.getenv("JXY_VIDEO_MODEL") ?: VideoPick.DEFAULT_MODEL
        val res = System.getenv("JXY_VIDEO_RES") ?: "480P"
        val secs = System.getenv("JXY_VIDEO_SECS")?.toIntOrNull() ?: 2
        val t0 = System.currentTimeMillis()
        val seen = mutableListOf<String>()
        // JXY_VIDEO_CANCEL=1：提交后马上取消（还在排队就不收费），看真接口取消后任务变成什么状态
        if (System.getenv("JXY_VIDEO_CANCEL") == "1") {
            val gen = VideoGen({ null })
            var id: String? = null
            val job = launch { runCatching { gen.generate(p, model, "一只橘猫在窗台上伸懒腰", res, secs, "16:9", true, onTask = { id = it }) } }
            while (id == null && job.isActive) delay(50)
            job.cancel()
            val taskId = id ?: run { println("没拿到任务编号"); return@runBlocking }
            println("${(System.currentTimeMillis() - t0) / 1000.0} 秒拿到任务，马上取消")
            val status = gen.cancel(p, taskId)
            println("取消后任务状态：$status")
            if (status != "CANCELED") runCatching { gen.await(p, taskId, res, secs) }
                .onSuccess { println("取消不了，做完了：计费 ${it.seconds} 秒") }.onFailure { println("取消不了，后来：${it.message?.take(300)}") }
            return@runBlocking
        }
        runCatching {
            VideoGen({ null }).generate(p, model, "一只橘猫在窗台上伸懒腰，午后阳光，镜头缓慢推近，轻柔的环境声", res, secs, "16:9", true) { s ->
                if (seen.lastOrNull() != s) { seen += s; println("${(System.currentTimeMillis() - t0) / 1000} 秒：$s") }
            }
        }.onSuccess { r ->
            val f = File(out, "$model-$res-${secs}s.mp4").apply { writeBytes(r.bytes) }
            println("做好了：${p.name} $model ${r.resolution}，计费 ${r.seconds} 秒，用时 ${(System.currentTimeMillis() - t0) / 1000.0} 秒 → ${f.absolutePath}（${r.bytes.size / 1024} KB）")
        }.onFailure { println("失败（${p.name} $model）：${it.message?.take(400)}") }
    }
}
