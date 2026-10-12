package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Code 栏用真模型跑一遍（2026-10-12，主会话写）：只在设了 JXY_LIVE_CODE=数据文件夹 时跑。
 * 把那份数据的 state.json 复制到临时文件夹再用（不动原数据），不打印 Key。
 * JXY_LIVE_CODE_MODEL=服务商编号|模型名 指定用哪个模型；不设就挑第一个支持工具的 deepseek-flash。
 * 「接受编辑」档：改文件不问；跑命令由测试自动允许（打印出来看它跑了什么）。
 */
class LiveCodeTest {
    @Test
    fun codeLoopWithRealModel() = runBlocking {
        val src = System.getenv("JXY_LIVE_CODE") ?: return@runBlocking
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-code").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "data/state.json"))
            val project = File(tmp, "proj").apply { mkdirs() }
            File(project, "greet.txt").writeText("hello world\nsecond line\n")
            File(project, "notes.md").writeText("# 说明\n这个项目用来测试 Code 栏。\n")
            val e = Engine(Storage(File(tmp, "data")))
            e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
            val (pid, model) = System.getenv("JXY_LIVE_CODE_MODEL")?.split('|', limit = 2)?.let { it[0] to it[1] }
                ?: e.state.providers.filter { it.enabled }.firstNotNullOf { p -> p.models.firstOrNull { it.tools && it.id == "deepseek-flash" }?.let { p.id to it.id } }
            println("模型：$model（服务商 ${e.state.provider(pid)?.name}）")
            val r = e.call(Command.CodeCreate(project.absolutePath, pid, model, CodeMode.ACCEPT_EDITS))
            assertTrue(r.ok, r.message)
            val conv = r.data
            val t0 = System.currentTimeMillis()
            e.call(Command.CodeSend(conv, "把 greet.txt 第一行的 hello 改成 你好（别的不动），然后跑一条命令把 greet.txt 的内容打出来确认改对了。"))
            val approved = mutableSetOf<String>()
            withTimeout(600_000) {
                while (true) {
                    val ai = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
                    ai?.tools?.filter { it.approval == "waiting" && it.id !in approved }?.forEach { s ->
                        println("批准：${s.kind}「${s.input}」")
                        approved += s.id
                        e.call(Command.CodeDecide(conv, s.id, true))
                    }
                    if (ai != null && ai.status != MsgStatus.STREAMING && ai.status != MsgStatus.PENDING) break
                    delay(300)
                }
            }
            val ai = e.store.messages.value[conv].orEmpty().last { it.role == Role.AI && it.senderId == CODE_ID }
            println("用时 ${(System.currentTimeMillis() - t0) / 1000} 秒，状态 ${ai.status}，用量 ${ai.usage}")
            ai.tools.forEach { s ->
                println("步骤：${s.kind}「${s.input.take(80)}」 ok=${s.ok} 批准=${s.approval.ifEmpty { "不用" }} 改动=${s.change?.let { "+${it.added} −${it.removed}" } ?: "无"} ${s.note}")
                if (s.kind == "run") println("  输出：" + s.output.take(300).replace("\n", " ⏎ "))
            }
            println("回答：" + ai.content.take(500).replace("\n", " "))
            if (ai.error.isNotEmpty()) println("出错：" + ai.error.take(300))
            val now = File(project, "greet.txt").readText()
            println("greet.txt 现在：" + now.replace("\n", " ⏎ "))
            assertTrue(ai.status == MsgStatus.DONE, "做完：${ai.status} ${ai.error}")
            assertTrue(now.startsWith("你好 world"), "第一行改对了")
            assertTrue("second line" in now, "别的没动")
            assertTrue(ai.tools.any { it.kind == "run" && it.approval == "allowed" }, "跑了命令确认")
            assertTrue(ai.tools.any { it.change != null }, "改动记下来了，能审阅")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
