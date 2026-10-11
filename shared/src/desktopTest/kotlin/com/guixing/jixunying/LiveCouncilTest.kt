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
 * 用真模型跑一次会审：四位成员各自回答 → 匿名互评（要能写出「名次：甲 > 乙 …」）→ 主席汇总。
 * 只在设了 JXY_LIVE_COUNCIL=数据文件夹 时跑：只复制 state.json（不动原数据），关掉记忆、立场档案、联网。
 * 结果写进 JXY_LIVE_COUNCIL_OUT（默认数据文件夹旁边的 live-council.txt）。会花一点钱（九次调用，几分钱）。
 */
class LiveCouncilTest {
    @Test
    fun councilWithRealModels() = runBlocking {
        val src = System.getenv("JXY_LIVE_COUNCIL") ?: return@runBlocking
        val log = File(System.getenv("JXY_LIVE_COUNCIL_OUT") ?: File(File(src).parentFile, "live-council.txt").path)
        log.writeText("", Charsets.UTF_8)
        fun say(line: String) { println(line); log.appendText(line + "\n", Charsets.UTF_8) }
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-council").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            val s = e.state.settings
            e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(enabled = false, stances = false, aiVerify = false))))
            val members = e.state.members
            say("成员：" + members.joinToString("、") { "${it.name}（${it.modelId}）" })
            val conv = e.call(Command.CreateConversation(members.map { it.id }, "会审测试")).data
            e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(council = true, webSearch = false)))
            val t0 = System.currentTimeMillis()
            e.call(Command.SendMessage(conv, "一个班 40 人，至少有几个人生日在同一个月？说清理由。"))
            withTimeoutOrNull(900_000) {
                while (true) {
                    val ai = e.store.messages.value[conv].orEmpty().filter { it.role == Role.AI }
                    if (ai.any { it.stage == "chair" && it.status != MsgStatus.STREAMING }) break
                    delay(1_000)
                }
            } ?: say("15 分钟还没汇总完")
            say("一共 ${(System.currentTimeMillis() - t0) / 1000} 秒")
            for (m in e.store.messages.value[conv].orEmpty().filter { it.role == Role.AI }) {
                val who = e.memberName(m.senderId) ?: if (m.senderId == com.guixing.jixunying.engine.CHAIR_ID) "会审主席" else m.senderId
                val tag = when (m.stage) { "review" -> "互评"; "chair" -> "汇总"; else -> "回答" }
                say("\n【$who · $tag · ${m.status}】" + (if (m.error.isNotBlank()) "出错：${m.error.take(200)}" else "") +
                    (m.usage?.let { "（${it.millis / 1000} 秒，输入 ${it.prompt} / 输出 ${it.completion}）" } ?: ""))
                val body = m.content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                say(if (m.stage == "chair") body else body.take(400) + (if (body.length > 400) " ……" else "") +
                    (if (m.stage == "review") "\n  名次行：" + (body.lines().lastOrNull { "名次" in it } ?: "（没写名次）") else ""))
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
