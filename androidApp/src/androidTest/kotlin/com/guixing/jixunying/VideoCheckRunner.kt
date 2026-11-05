package com.guixing.jixunying

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.guixing.jixunying.engine.localVideoAudio
import com.guixing.jixunying.engine.localVideoFrames
import java.io.File

/**
 * 在模拟器上真跑一遍手机解视频（2026-11-01，engine/PlatformBits.android.kt）：自动测试跑在电脑的 JVM 上，碰不到安卓的解码器。
 * 不依赖 androidx.test，就一个 Instrumentation。用法（只对模拟器，别对用户手机）：
 * ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest，两个包都 adb -s emulator-5554 install -r，
 * 视频放进应用文件夹（run-as），adb -s emulator-5554 shell am instrument -w -e video files/x.mp4 com.guixing.jixunying.test/com.guixing.jixunying.VideoCheckRunner。
 * 结果打印出来，取到的画面存在 cache/frames（run-as 取回来看）。
 */
class VideoCheckRunner : Instrumentation() {
    private var videos: List<String> = emptyList()
    private var silk: String? = null

    override fun onCreate(arguments: Bundle?) {
        videos = arguments?.getString("video").orEmpty().split(',').filter { it.isNotBlank() }
        silk = arguments?.getString("silk")?.takeIf { it.isNotBlank() }
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val results = Bundle()
        val out = StringBuilder()
        val dataDir = targetContext.filesDir.parentFile!!
        // 历法库在安卓上也跑一下（2026-11-02，engine/Today.kt）
        runCatching {
            val d = java.time.LocalDate.of(2026, 11, 2)
            out.append(com.guixing.jixunying.engine.Today.describe(d)).append('\n')
            out.append(com.guixing.jixunying.engine.Today.ahead(d).second).append('\n')
            out.append(com.guixing.jixunying.engine.DateMentions.notes("我生日是农历九月二十四，下周三交报告", d)).append('\n')
        }.onFailure { out.append("历法出错：").append(it).append('\n') }
        // P 图在安卓上也跑一下（2026-11-02，engine/PhotoEdit.kt）：解码、存图、写字、画框都是安卓自己的实现，电脑上的测试碰不到
        runCatching {
            val pe = com.guixing.jixunying.engine.PhotoEdit
            val gray = (0xFF shl 24) or 0xEDEDED
            val p = com.guixing.jixunying.engine.Pixels(800, 600, IntArray(800 * 600) { gray })
            com.guixing.jixunying.engine.drawTextOn(p, "昨天 晚上7:37", intArrayOf(280, 270, 520, 310), 0xAAAAAA, 32, false, "")
            val ink = p.argb.count { it != gray }
            val png = com.guixing.jixunying.engine.encodePixels(p)
            val back = com.guixing.jixunying.engine.decodePixels(png)!!
            out.append("写字 ").append(ink).append(" 个像素；PNG 存了再读 ").append(pe.diff(p, back)?.changed).append(" 个不一样；文件头尺寸 ").append(pe.sizeOf(png)).append('\n')
            val steps = pe.steps(kotlinx.serialization.json.JsonPrimitive("""[{"op":"erase","box":[270,262,530,318]},{"op":"text","text":"今天 上午9:00"},{"op":"mark","box":[100,100,300,200],"shape":"ellipse","color":"红"},{"op":"mosaic","box":[400,400,700,500]},{"op":"crop","box":[0,50,800,550]},{"op":"rotate","angle":90}]"""))
            val r = pe.run(back, steps) { null }
            out.append("跑了：").append(r.summary).append("；结果 ").append(r.out.w).append('×').append(r.out.h).append('\n')
            val erased = pe.run(back, pe.steps(kotlinx.serialization.json.JsonPrimitive("""[{"op":"erase","box":[270,262,530,318]}]"""))) { null }
            out.append("只擦字：改了 ").append(pe.diff(back, erased.out)?.changed).append(" 个像素，范围 ").append(pe.diff(back, erased.out)?.box?.joinToString(",")).append("；还剩 ").append(erased.out.argb.count { it != gray }).append(" 个不是背景的像素\n")
            val jpg = com.guixing.jixunying.engine.encodePixels(r.out, 90)
            out.append("JPEG ").append(jpg.size).append(" 字节，文件头尺寸 ").append(pe.sizeOf(jpg)).append("，方向 ").append(pe.orientationOf(jpg)).append('\n')
            val dir = File(targetContext.cacheDir, "frames").apply { mkdirs() }
            File(dir, "photo-steps.png").writeBytes(com.guixing.jixunying.engine.encodePixels(r.out))
            File(dir, "photo-erased.png").writeBytes(com.guixing.jixunying.engine.encodePixels(erased.out))
        }.onFailure { out.append("P 图出错：").append(it).append('\n') }
        // 微信语音（SILK）在安卓上也解一下（2026-11-05，silk 模块）：-e silk files/x.silk；解出来的 wav 存在 cache/frames/silk.wav
        silk?.let { name ->
            runCatching {
                val raw = File(dataDir, name).readBytes()
                val t0 = System.nanoTime()
                val wav = com.guixing.jixunying.engine.WeixinVoice.silkToWav(raw)
                val ms = (System.nanoTime() - t0) / 1_000_000
                out.append("SILK ").append(raw.size).append(" 字节，是 SILK：").append(com.guixing.jixunying.engine.WeixinVoice.isSilk(raw))
                    .append("，解成 wav ").append(wav?.size).append(" 字节（").append(wav?.let { com.guixing.jixunying.engine.WeixinVoice.seconds(it) }).append(" 秒），用了 ").append(ms).append(" 毫秒\n")
                wav?.let { File(File(targetContext.cacheDir, "frames").apply { mkdirs() }, "silk.wav").writeBytes(it) }
            }.onFailure { out.append("SILK 出错：").append(it).append('\n') }
        }
        for (name in videos) {
            val f = File(dataDir, name)
            out.append("==== ").append(f.name).append("（").append(f.length()).append(" 字节）\n")
            val t0 = System.currentTimeMillis()
            runCatching { localVideoFrames(f, 6, 1024) }
                .onFailure { out.append("画面出错：").append(it).append('\n') }
                .onSuccess { r ->
                    val (frames, secs) = r!!
                    out.append("画面 ").append(frames.size).append(" 张，整段 ").append(secs).append(" 秒，大小 ").append(frames.map { it.size })
                        .append("，用了 ").append(System.currentTimeMillis() - t0).append(" ms\n")
                    val dir = File(targetContext.cacheDir, "frames").apply { mkdirs() }
                    frames.forEachIndexed { i, b -> File(dir, "${f.nameWithoutExtension}-$i.jpg").writeBytes(b) }
                }
            val t1 = System.currentTimeMillis()
            runCatching { localVideoAudio(f, 600) }
                .onFailure { out.append("声音出错：").append(it).append('\n') }
                .onSuccess { r ->
                    val (pcm, secs) = r!!
                    var peak = 0
                    for (i in 0 until pcm.size / 2) peak = maxOf(peak, kotlin.math.abs((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)))
                    out.append("声音 ").append(pcm.size).append(" 字节（").append(pcm.size / 32000.0).append(" 秒，16k 单声道），声轨 ").append(secs)
                        .append(" 秒，最大振幅 ").append(peak).append("，用了 ").append(System.currentTimeMillis() - t1).append(" ms\n")
                    val dir = File(targetContext.cacheDir, "frames").apply { mkdirs() }
                    if (pcm.isNotEmpty()) File(dir, "${f.nameWithoutExtension}.pcm").writeBytes(pcm)
                }
        }
        results.putString(REPORT_KEY_STREAMRESULT, out.toString())
        finish(Activity.RESULT_OK, results)
    }
}
