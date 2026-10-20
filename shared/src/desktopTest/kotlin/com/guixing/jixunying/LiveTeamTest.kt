package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真模型试多角色派活（2026-10-19）：设 JXY_LIVE_TEAM=数据文件夹 才跑（只读那份数据里的服务商，不打印 Key）。
 * 主控和审查用 deepseek-flash，开发交给本机真的 Claude Code（haiku，走用户的订阅）。要批准的测试替用户点「允许」。
 * 看主控会不会真的用 assign_task 派活、开发真的把文件改对、审查交回意见。
 */
class LiveTeamTest {
    @Test
    fun realTeamFixesABug() = runBlocking {
        val data = System.getenv("JXY_LIVE_TEAM") ?: run { println("没设 JXY_LIVE_TEAM，跳过"); return@runBlocking }
        val real = Storage(File(data)).loadState() ?: error("读不了 $data")
        val p = real.providers.firstOrNull { pr -> pr.enabled && pr.models.any { it.id == "deepseek-flash" } } ?: error("没有 deepseek-flash")
        val dir = kotlin.io.path.createTempDirectory("jxy-live-team").toFile()
        try {
            val project = File(dir, "proj").apply { mkdirs() }
            File(project, "calc.py").writeText("def add(a, b):\n    return a - b\n")
            val e = Engine(Storage(File(dir, "data")))
            e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
            e.call(Command.SaveProvider(p))
            e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell", maxSteps = 30), proxy = real.settings.proxy)))
            val conv = e.call(Command.CodeCreate(project.absolutePath, p.id, "deepseek-flash", CodeMode.ACCEPT_EDITS)).data
            assertTrue(e.call(Command.CodeSetTeam(conv, "dev", CodeSpec.CLAUDE_CODE, "haiku")).ok)
            assertTrue(e.call(Command.CodeSetTeam(conv, "review", p.id, "deepseek-flash")).ok)
            e.call(Command.CodeSend(conv, "calc.py 里的 add 函数结果不对。请把修复派给开发（修好后用 python 跑一下验证），修完再派给审查看一眼改动，最后向我汇报。"))
            fun ai() = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
            withTimeout(480_000) {
                while (true) {
                    val m = ai()
                    if (m != null && m.status != MsgStatus.STREAMING) break
                    m?.tools?.firstOrNull { it.approval == "waiting" }?.let { e.call(Command.CodeDecide(conv, it.id, true)) }
                    delay(300)
                }
            }
            val m = ai()!!
            println("状态：${m.status} ${m.error}\n回答：${m.content}\n步骤：\n" + m.tools.joinToString("\n") {
                "${it.kind} | ${it.input} | ${it.approval} | ${it.note} | ${it.output.take(200).replace("\n", "⏎")}"
            })
            assertEquals(MsgStatus.DONE, m.status, m.error)
            val agents = m.tools.filter { it.kind == "agent" }.map { it.input }
            assertTrue(agents.any { it.startsWith("开发") } && agents.any { it.startsWith("审查") }, "派了开发和审查：$agents")
            assertTrue("a + b" in File(project, "calc.py").readText(), File(project, "calc.py").readText())
        } finally {
            dir.deleteRecursively()
        }
        Unit
    }
}
