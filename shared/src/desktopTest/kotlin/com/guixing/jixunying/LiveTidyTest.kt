package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Recorder
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * 整理记忆拿真数据真跑一次（2026-11-05）：用户截图「这个整理不出来，手机也是连不上，电脑也是连不上」——168 条要记录员整份重写，
 * 次次撞 10 分钟超时。改成分批、只写改动、关思考、不限时以后，看每批多久、记录员按不按新格式回话、最后变成多少条。
 * 只在设了 JXY_LIVE_TIDY=数据文件夹 时跑：只复制 state.json 到临时文件夹（不动原数据；不开中转、不接微信），用里面配好的记录员。
 * 拿掉的、新写的都写进 JXY_LIVE_TIDY_OUT（默认数据文件夹旁边的 live-tidy.txt），人来看合并得对不对。
 */
class LiveTidyTest {
    @Test
    fun tidyRealMemories() = runBlocking {
        val src = System.getenv("JXY_LIVE_TIDY") ?: return@runBlocking
        val out = File(System.getenv("JXY_LIVE_TIDY_OUT") ?: File(File(src).parentFile, "live-tidy.txt").path)
        out.writeText("", Charsets.UTF_8)
        fun say(line: String) { println(line); out.appendText(line + "\n", Charsets.UTF_8) }
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-tidy").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            val s = e.state.settings
            // 只跑整理：别的记录员活（挑记忆、立场档案、AI 核对）关掉，省调用
            e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(autoExtract = false, stances = false, aiVerify = false))))
            val before = e.state.memories.filterNot { it.pinned }
            val pick = Recorder.pick(e.state)
            say("记录员：${pick?.second}（${pick?.first?.name}）；没置顶的 ${before.size} 条，正文 ${before.sumOf { it.text.length }} 字")
            val batches = Recorder.tidyBatches(before)
            say("分 ${batches.size} 批：" + batches.joinToString(" / ") { b -> "${b.size} 条（" + b.map { it.topic.ifBlank { "#" + it.kind } }.distinct().joinToString("、") + "）" })
            val t0 = System.currentTimeMillis()
            say("开始：" + e.call(Command.TidyMemories).message)
            var last = ""
            var batch = 0
            var batchAt = t0
            while (true) {
                val t = e.state.tidy
                val now = System.currentTimeMillis()
                if (t.batch != batch) {
                    if (batch > 0) say("  第 $batch 批用了 ${(now - batchAt) / 1000} 秒")
                    batch = t.batch; batchAt = now
                }
                val line = "第 ${t.batch}/${t.batches} 批，看完 ${t.done}/${t.total}，合并 ${t.merged} 删 ${t.dropped}，这批写了 ${t.written} 字${if (t.thinking && t.written > 0) "（在想）" else ""}"
                if (line != last) { println("${(now - t0) / 1000}s $line"); last = line }
                if (!t.running) { say("  第 $batch 批用了 ${(now - batchAt) / 1000} 秒"); break }
                delay(300)
            }
            val t = e.state.tidy
            say("结果：${t.note}（总共 ${(t.finishedAt - t.startedAt) / 1000} 秒）")
            val after = e.state.memories
            val afterIds = after.map { it.id }.toSet()
            val beforeIds = before.map { it.id }.toSet()
            val gone = before.filter { it.id !in afterIds }
            val added = after.filter { it.id !in beforeIds }
            // 拿掉的每条算进字面最像的那条新的（字的二元组覆盖一半以上）；哪条都不像的就是删掉的。粗算，给人看用
            fun grams(x: String) = x.filter { it.isLetterOrDigit() }.lowercase().windowed(2).toSet()
            val into = gone.associateWith { g ->
                val gg = grams(g.text)
                added.maxByOrNull { n -> gg.count { it in grams(n.text) } }?.takeIf { n -> gg.isNotEmpty() && gg.count { it in grams(n.text) } * 2 >= gg.size }
            }
            say("")
            say("—— 新写的 ${added.size} 条（← 是并进来的原文）——")
            added.forEach { n ->
                say("+ [${n.topic.ifBlank { n.kind }}]（${n.text.length} 字）${n.text}")
                gone.filter { into[it] == n }.forEach { say("    ← ${it.text}") }
            }
            say("")
            val dropped = gone.filter { into[it] == null }
            say("—— 字面上哪条新的都不像的 ${dropped.size} 条（改写得多的合并，或者删掉的；记录员这次删了 ${t.dropped} 条）——")
            dropped.forEach { say("- [${it.topic.ifBlank { it.kind }}] ${it.text}") }
            val leftDates = added.count { Regex("""\d{1,2}月\d{1,2}日记""").containsMatchIn(it.text) }
            say("")
            say("新写的里还带「几月几日记」的：$leftDates 条；最长 ${added.maxOfOrNull { it.text.length } ?: 0} 字")
            assertFalse(t.error, t.note)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
