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
 * 真的 Codex / Gemini CLI（2026-10-20）：设 JXY_LIVE_AGENT=codex,gemini（或其中一个）才跑，走用户自己登录的账号。
 * 修 calc.py 里写错的 add：看改动有没有被比对出来、能不能撤销回原样。
 */
class LiveAgentTest {
    @Test
    fun realAgentsFixABug() = runBlocking {
        val which = System.getenv("JXY_LIVE_AGENT")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: run { println("没设 JXY_LIVE_AGENT，跳过"); return@runBlocking }
        for (w in which) {
            val agent = if (w == "codex") CodeSpec.CODEX else CodeSpec.GEMINI
            val dir = kotlin.io.path.createTempDirectory("jxy-live-agent").toFile()
            try {
                val project = File(dir, "proj").apply { mkdirs() }
                val bad = "def add(a, b):\n    return a - b\n"
                File(project, "calc.py").writeText(bad)
                val e = Engine(Storage(File(dir, "data")))
                e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
                e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell"))))
                val conv = e.call(Command.CodeCreate(project.absolutePath, agent, "", CodeMode.ACCEPT_EDITS)).data
                e.call(Command.CodeSend(conv, "calc.py 里的 add 函数结果不对，修好它。只改这个文件，改完用一两句话说改了什么。"))
                fun ai() = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
                val t0 = System.currentTimeMillis()
                withTimeout(400_000) { while (ai()?.status.let { it == null || it == MsgStatus.STREAMING }) delay(300) }
                val m = ai()!!
                println("—— $w（${(System.currentTimeMillis() - t0) / 1000} 秒）状态：${m.status} ${m.error}\n回答：${m.content}\n用量：${m.usage}\n步骤：\n" +
                    m.tools.joinToString("\n") { "${it.kind} | ${it.input} | ${it.note} | ${it.change?.let { c -> "改动 ${c.path} +${c.added} -${c.removed}" } ?: ""}" })
                assertEquals(MsgStatus.DONE, m.status, m.error)
                assertTrue("a + b" in File(project, "calc.py").readText(), File(project, "calc.py").readText())
                assertTrue(m.tools.any { it.change?.path == "calc.py" }, "改动比对出来了")
                assertTrue(e.call(Command.CodeRevertTask(conv, m.id)).ok)
                assertEquals(bad, File(project, "calc.py").readText(), "撤销回原样")
            } finally {
                dir.deleteRecursively()
            }
        }
        Unit
    }
}
