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
 * 真的 Claude Code（2026-10-18）：设 JXY_LIVE_CLAUDE=1 才跑，用本机装好、登录好的 claude（走用户的订阅，haiku，一个很小的任务）。
 * 手动档：每一步等批准，测试替用户点「允许」；看文件真改了、改动记录挂上了、能撤销、会话编号记下了。
 */
class LiveClaudeCodeTest {
    @Test
    fun realClaudeEditsWithApproval() = runBlocking {
        if (System.getenv("JXY_LIVE_CLAUDE") != "1") { println("没设 JXY_LIVE_CLAUDE=1，跳过"); return@runBlocking }
        val dir = kotlin.io.path.createTempDirectory("jxy-live-claude").toFile()
        try {
            val project = File(dir, "proj").apply { mkdirs() }
            File(project, "a.txt").writeText("color = red\n")
            val e = Engine(Storage(File(dir, "data")))
            e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
            e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell"))))
            val conv = e.call(Command.CodeCreate(project.absolutePath, CodeSpec.CLAUDE_CODE, "haiku", CodeMode.MANUAL)).data
            e.call(Command.CodeSend(conv, "用 Edit 工具把 a.txt 里的 red 改成 blue，然后用 PowerShell 运行 Get-Content a.txt 确认。只做这两步，别的不做。"))
            fun ai() = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
            withTimeout(240_000) {
                while (true) {
                    val m = ai()
                    if (m != null && m.status != MsgStatus.STREAMING) break
                    m?.tools?.firstOrNull { it.approval == "waiting" }?.let { e.call(Command.CodeDecide(conv, it.id, true)) }
                    delay(200)
                }
            }
            val m = ai()!!
            println("状态：${m.status} ${m.error}\n回答：${m.content}\n步骤：\n" + m.tools.joinToString("\n") { "${it.kind} | ${it.input} | ${it.approval} | ${it.note} | ${it.output.take(120).replace("\n", "⏎")}" })
            assertEquals(MsgStatus.DONE, m.status, m.error)
            assertEquals("color = blue\n", File(project, "a.txt").readText().replace("\r\n", "\n"))
            assertTrue(m.tools.any { it.change != null }, "改动记录挂上了")
            assertTrue(e.state.conversation(conv)!!.code!!.externalSession.isNotBlank(), "会话编号记下了")
            assertTrue(e.call(Command.CodeRevertTask(conv, m.id)).ok)
            assertEquals("color = red\n", File(project, "a.txt").readText().replace("\r\n", "\n"))
        } finally {
            dir.deleteRecursively()
        }
        Unit
    }
}
