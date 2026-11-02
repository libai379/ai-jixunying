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

    override fun onCreate(arguments: Bundle?) {
        videos = arguments?.getString("video").orEmpty().split(',').filter { it.isNotBlank() }
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
