package com.guixing.jixunying

import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.VideoGen
import com.guixing.jixunying.engine.VideoInput
import com.guixing.jixunying.engine.VideoMedia
import com.guixing.jixunying.model.VideoPick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 真做一段视频：用数据文件夹里第一个能做这个模型的服务商（千问AI平台优先），存到 JXY_VIDEO_OUT。
 * 只在设了 JXY_LIVE_VIDEO=数据文件夹（用副本，别直接指用户的）时跑；JXY_VIDEO_MODEL / JXY_VIDEO_RES / JXY_VIDEO_SECS / JXY_VIDEO_PROMPT 可改，
 * 默认 wan3.0-video、480P、2 秒（百炼原价每秒 0.3 元）。JXY_VIDEO_MEDIA=「用途=路径或网址;用途=…」带素材（用途见 VideoMediaUse），
 * JXY_VIDEO_RATIO 默认 16:9（带素材默认 adaptive）。会花钱，跑之前算好预算。不打印 Key。
 */
class LiveVideoTest {
    @Test
    fun makeVideoWithRealModel() = runBlocking {
        val dir = System.getenv("JXY_LIVE_VIDEO") ?: return@runBlocking
        val out = File(System.getenv("JXY_VIDEO_OUT") ?: return@runBlocking).apply { mkdirs() }
        val state = Storage(File(dir)).loadState() ?: return@runBlocking
        val model = System.getenv("JXY_VIDEO_MODEL") ?: VideoPick.DEFAULT_MODEL
        val vendor = VideoPick.model(model).vendor
        val p = VideoPick.candidates(state.providers).firstOrNull { VideoPick.vendorOf(it) == vendor } ?: run { println("没有能做 $model 的服务商"); return@runBlocking }
        val res = System.getenv("JXY_VIDEO_RES") ?: "480P"
        val secs = System.getenv("JXY_VIDEO_SECS")?.toIntOrNull() ?: 2
        val prompt = System.getenv("JXY_VIDEO_PROMPT") ?: "一只橘猫在窗台上伸懒腰，午后阳光，镜头缓慢推近，轻柔的环境声"
        val inputs = System.getenv("JXY_VIDEO_MEDIA").orEmpty().split(';').filter { '=' in it }.map { item ->
            val use = item.substringBefore('=').trim()
            val src = item.substringAfter('=').trim()
            if (src.startsWith("http")) VideoInput(use, src.substringAfterLast('/'), VideoMedia.mimeOf(src), url = src)
            else File(src).let { f -> val b = f.readBytes(); VideoInput(use, f.name, VideoMedia.mimeOf(f.name), b, seconds = VideoMedia.durationOf(f.name, VideoMedia.mimeOf(f.name), b)) }
        }
        val ratio = System.getenv("JXY_VIDEO_RATIO") ?: if (inputs.isEmpty()) "16:9" else "adaptive"
        VideoMedia.check(VideoPick.model(model), inputs, secs)?.let { println("素材不合要求：$it"); return@runBlocking }
        val t0 = System.currentTimeMillis()
        val seen = mutableListOf<String>()
        // JXY_VIDEO_CANCEL=1：提交后马上取消（还在排队就不收费），看真接口取消后任务变成什么状态
        if (System.getenv("JXY_VIDEO_CANCEL") == "1") {
            val gen = VideoGen({ null })
            var id: String? = null
            val job = launch { runCatching { gen.generate(p, model, prompt, res, secs, ratio, true, inputs, onTask = { id = it }) } }
            while (id == null && job.isActive) delay(50)
            job.cancel()
            val taskId = id ?: run { println("没拿到任务编号"); return@runBlocking }
            println("${(System.currentTimeMillis() - t0) / 1000.0} 秒拿到任务，马上取消")
            val status = gen.cancel(p, model, taskId)
            println("取消后任务状态：$status")
            if (status != "CANCELED") runCatching { gen.await(p, model, taskId, res, secs) }
                .onSuccess { println("取消不了，做完了：输出 ${it.usage.output} 秒、输入 ${it.usage.input} 秒") }.onFailure { println("取消不了，后来：${it.message?.take(300)}") }
            return@runBlocking
        }
        runCatching {
            VideoGen({ null }).generate(p, model, prompt, res, secs, ratio, true, inputs) { s ->
                if (seen.lastOrNull() != s) { seen += s; println("${(System.currentTimeMillis() - t0) / 1000} 秒：$s") }
            }
        }.onSuccess { r ->
            val f = File(out, "$model-$res-${secs}s-${System.currentTimeMillis() % 100000}.mp4").apply { writeBytes(r.bytes) }
            println("做好了：${p.name} $model ${r.resolution}，输出 ${r.usage.output} 秒、输入视频 ${r.usage.input} 秒、输入图片 ${r.usage.images} 张，" +
                "用时 ${(System.currentTimeMillis() - t0) / 1000.0} 秒 → ${f.absolutePath}（${r.bytes.size / 1024} KB）")
        }.onFailure { println("失败（${p.name} $model）：${it.message?.take(600)}") }
    }
}
