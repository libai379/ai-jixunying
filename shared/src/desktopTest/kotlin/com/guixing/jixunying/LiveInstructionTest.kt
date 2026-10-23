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
 * 用户 2026-10-23：「旧对话看不到我新更新的个性化」（AI 在旧对话里不照新写的自定义指令做）。
 * 先量是哪一环：每位成员各开一个单聊，没有自定义指令时先聊两句（这就是「旧对话」），再写上指令问第三句；
 * 另开一个新单聊问同一句。看两边的回答开头有没有照指令写。
 * 只在设了 JXY_LIVE_INSTR=数据文件夹 时跑：只复制 state.json（不动原数据），关掉记忆、立场档案、联网。
 * 结果写进 JXY_LIVE_INSTR_OUT（默认数据文件夹旁边的 live-instr.txt）。每位成员四次调用，几分钱。
 */
class LiveInstructionTest {
    @Test
    fun oldChatFollowsNewInstructions() = runBlocking {
        val src = System.getenv("JXY_LIVE_INSTR") ?: return@runBlocking
        val log = File(System.getenv("JXY_LIVE_INSTR_OUT") ?: File(File(src).parentFile, "live-instr.txt").path)
        log.writeText("", Charsets.UTF_8)
        fun say(line: String) { println(line); log.appendText(line + "\n", Charsets.UTF_8) }
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-instr").toFile()
        val mark = "报告老板："
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            suspend fun settings(instr: String) {
                val s = e.state.settings
                e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(enabled = false, stances = false, aiVerify = false),
                    personal = s.personal.copy(instructions = instr))))
            }
            suspend fun ask(conv: String, text: String): String {
                e.call(Command.SendMessage(conv, text))
                withTimeoutOrNull(300_000) {
                    while (true) {
                        delay(1_000)
                        val ai = e.store.messages.value[conv].orEmpty().lastOrNull()
                        if (ai != null && ai.role == Role.AI && ai.status != MsgStatus.STREAMING) break
                    }
                }
                val ai = e.store.messages.value[conv].orEmpty().last()
                if (ai.error.isNotBlank()) return "出错：" + ai.error.take(200)
                return ai.content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            }
            settings("")
            val members = e.state.members
            say("成员：" + members.joinToString("、") { "${it.name}（${it.modelId}）" })
            val q = "用一句话说说为什么天是蓝的。"
            // JXY_LIVE_INSTR_ONLY=阿智 只测这一位；JXY_LIVE_INSTR_RUNS=4 每位测几轮（一轮一个样本不够说明问题）
            val only = System.getenv("JXY_LIVE_INSTR_ONLY").orEmpty()
            val runs = System.getenv("JXY_LIVE_INSTR_RUNS")?.toIntOrNull() ?: 1
            for (m in members.filter { only.isEmpty() || it.name == only }) repeat(runs) {
                settings("")
                val old = e.call(Command.CreateConversation(listOf(m.id), "旧对话 ${m.name}")).data
                e.call(Command.UpdateConversation(e.state.conversation(old)!!.copy(webSearch = false)))
                ask(old, "一斤等于多少克？一句话回答。")
                ask(old, "水在多少度沸腾？一句话回答。")
                settings("每次回答都必须用「$mark」开头。")
                val a = ask(old, q)
                val fresh = e.call(Command.CreateConversation(listOf(m.id), "新对话 ${m.name}")).data
                e.call(Command.UpdateConversation(e.state.conversation(fresh)!!.copy(webSearch = false)))
                val b = ask(fresh, q)
                say("\n【${m.name}】旧对话：" + (if (a.startsWith(mark)) "照做" else "没照做") + "｜新对话：" + (if (b.startsWith(mark)) "照做" else "没照做"))
                say("  旧：" + a.take(160).replace("\n", " "))
                say("  新：" + b.take(160).replace("\n", " "))
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
