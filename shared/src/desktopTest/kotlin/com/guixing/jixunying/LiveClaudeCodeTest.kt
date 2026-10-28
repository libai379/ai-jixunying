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

    /**
     * 2026-10-28 用户：「为什么 Claude Code 用终端的时候……我可以随时打断你呢？」——真的 Claude Code 走一遍插话：
     * 它在写长文的时候插一句，程序先喊停、再在同一个进程里交给它；看它停了、接着答、记得前面在写什么。
     * 设 JXY_LIVE_CLAUDE=1 才跑（haiku，用户的订阅，几十个词元）。
     */
    @Test
    fun realClaudeHearsInterjection() = runBlocking {
        if (System.getenv("JXY_LIVE_CLAUDE") != "1") { println("没设 JXY_LIVE_CLAUDE=1，跳过"); return@runBlocking }
        val dir = kotlin.io.path.createTempDirectory("jxy-live-claude-int").toFile()
        try {
            val project = File(dir, "proj").apply { mkdirs() }
            val e = Engine(Storage(File(dir, "data")))
            e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
            e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell"))))
            val conv = e.call(Command.CodeCreate(project.absolutePath, CodeSpec.CLAUDE_CODE, "haiku", CodeMode.ACCEPT_EDITS)).data
            fun msgs() = e.store.messages.value[conv].orEmpty()
            fun ais() = msgs().filter { it.role == Role.AI && it.senderId == CODE_ID }
            e.call(Command.CodeSend(conv, "不用任何工具，直接在回答里用中文写一篇两千字的文章，题目是《茶的历史》，从唐朝写到今天，分十段。"))
            // 等它开始写（有字或者有思考）再插话
            withTimeout(120_000) { while (ais().firstOrNull()?.let { it.content.isNotBlank() || it.reasoning.isNotBlank() } != true) delay(200) }
            val t0 = System.currentTimeMillis()
            e.call(Command.CodeSend(conv, "先别写了。用一句话告诉我：你刚才在写什么题目？"))
            withTimeout(180_000) { while (ais().size < 2 || ais().last().status == MsgStatus.STREAMING) delay(200) }
            val all = msgs()
            println("插话后 ${System.currentTimeMillis() - t0} 毫秒答完\n" + all.joinToString("\n") { "${it.role} ${it.status} ${it.error} | ${it.content.take(160).replace("\n", "⏎")}" })
            assertEquals(listOf(Role.USER, Role.AI, Role.USER, Role.AI), all.map { it.role })
            assertEquals(MsgStatus.DONE, all[1].status, "被喊停的那段不算出错：" + all[1].error)
            assertEquals(MsgStatus.DONE, all[3].status, all[3].error)
            assertTrue("茶" in all[3].content, "记得前面在写什么：" + all[3].content)
        } finally {
            dir.deleteRecursively()
        }
        Unit
    }
}
