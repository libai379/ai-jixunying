package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test

/**
 * 用真模型看成员会不会做 Word / Excel / PPT（create_word / create_excel / create_ppt）：每位成员单聊三句，
 * 看有没有调工具、做出来的文件是什么样。做好的文件存到 JXY_LIVE_OFFICE_OUT（默认数据文件夹旁边的 live-office-out），
 * 再拿 OOXML 校验器和 LibreOffice 检查。
 * 只在设了 JXY_LIVE_OFFICE=数据文件夹 时跑：只复制那份数据的 state.json 到临时文件夹（不动原数据），
 * 关掉记忆、立场档案、AI 核实、联网（省钱、少变数）。不打印 Key。会花一点钱（四家都很便宜，一轮几分钱）。
 * JXY_LIVE_OFFICE_ONLY=阿德 只跑这一位；JXY_LIVE_OFFICE_ASKS=3 只问第 3 句（逗号分开可以选几句）。
 * 每一步的结果也写进输出文件夹的 结果.txt（Gradle 的测试报告可能被别的测试覆盖掉）。
 */
class LiveOfficeTest {
    private val asks = listOf(
        "帮我做一份 Word：《2026 年国庆假期值班安排通知》，正式一点，要有值班时间表（表格，10 月 1 日到 7 日，每天一人）和注意事项。",
        "把这些数据做成 Excel，最后加合计：华东 1 月 12000、2 月 13500、3 月 15200；华北 9800、10200、9900；华南 15000、16800、18100。再算一下每个区域的季度合计。",
        "做一个 5 页左右的 PPT，介绍 AI集训营 这个应用：多个 AI 一起群聊、立场档案、微信助理、能做 Word / Excel / PPT 文件。每页写上讲稿。",
    )

    @Test
    fun membersMakeOfficeFiles() = runBlocking {
        val src = System.getenv("JXY_LIVE_OFFICE") ?: return@runBlocking
        val out = File(System.getenv("JXY_LIVE_OFFICE_OUT") ?: File(File(src).parentFile, "live-office-out").path).apply { mkdirs() }
        val only = System.getenv("JXY_LIVE_OFFICE_ONLY").orEmpty()
        val pick = System.getenv("JXY_LIVE_OFFICE_ASKS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()
        val log = File(out, "结果.txt")
        fun say(line: String) { println(line); log.appendText(line + "\n", Charsets.UTF_8) }
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-office").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            val s = e.state.settings
            e.call(Command.SaveSettings(s.copy(
                memory = s.memory.copy(enabled = false, stances = false, aiVerify = false),
                office = s.office.copy(enabled = true, saveFolder = out.path),
            )))
            val members = e.state.members.filter { only.isEmpty() || it.name == only }
            say("成员：" + members.joinToString("、") { "${it.name}（${it.modelId}）" })
            for (m in members) {
                val conv = e.call(Command.CreateConversation(listOf(m.id), "做文件-${m.name}")).data
                e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
                for ((k, ask) in asks.withIndex()) {
                    if (pick.isNotEmpty() && (k + 1) !in pick) continue
                    val before = e.store.messages.value[conv].orEmpty().count { it.role == Role.AI }
                    val t0 = System.currentTimeMillis()
                    e.call(Command.SendMessage(conv, ask))
                    val done = withTimeoutOrNull(300_000) {
                        while (true) {
                            val ai = e.store.messages.value[conv].orEmpty().filter { it.role == Role.AI }
                            if (ai.size > before && ai.last().status != MsgStatus.STREAMING) return@withTimeoutOrNull ai.last()
                            delay(500)
                        }
                        @Suppress("UNREACHABLE_CODE") null
                    }
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    if (done == null) { say("【${m.name}】超时：${ask.take(20)}"); continue }
                    val files = done.attachments.filter { it.generated }
                    say("【${m.name}】${ask.take(18)}… → ${done.status}，$secs 秒，" +
                        (if (files.isEmpty()) "没做文件" else files.joinToString("；") { "${it.name}（${it.note}，${it.size / 1024} KB）" }) +
                        (done.usage?.let { "，输入 ${it.prompt} / 输出 ${it.completion}" } ?: "") +
                        (if (done.error.isNotBlank()) "，出错：${done.error.take(200)}" else ""))
                    done.tools.filter { !it.ok }.forEach { say("    ${it.kind} 没成：${it.input.take(40)} ${it.note.take(300)}") }
                    say("    工具：" + done.tools.joinToString("、") { "${it.kind}:${it.input.take(20)}" })
                    say("    回答：" + done.content.replace('\n', ' ').take(300))
                }
            }
            say("文件在：${out.path}")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
