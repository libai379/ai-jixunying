package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.code.AgentCli
import com.guixing.jixunying.engine.code.Snapshot
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 接 Codex / Gemini CLI（2026-10-20，docs/Code栏设计说明.md 第 17 节）：解析两家的输出、快照比对、
 * 用假的 codex / gemini（python 脚本，照 10-19、10-20 本机实测的输出格式）跑完整的会话。
 */
class AgentCliTest {
    private lateinit var dir: File
    private lateinit var project: File
    private lateinit var fakeDir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("jxy-agentcli").toFile()
        project = File(dir, "proj").apply { mkdirs() }
        fakeDir = File(dir, "fake").apply { mkdirs() }
        File(fakeDir, "fake_agent.py").writeText(FAKE, Charsets.UTF_8)
        File(fakeDir, "codex.cmd").writeText("@python -u -X utf8 \"%~dp0fake_agent.py\" codex %*\r\n")
        File(fakeDir, "gemini.cmd").writeText("@python -u -X utf8 \"%~dp0fake_agent.py\" gemini %*\r\n")
    }

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun hasPython() = runCatching { ProcessBuilder("cmd.exe", "/c", "python", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private suspend fun engine(): Engine {
        val e = Engine(Storage(File(dir, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10,
            codexPath = File(fakeDir, "codex.cmd").absolutePath, geminiPath = File(fakeDir, "gemini.cmd").absolutePath))))
        return e
    }

    private suspend fun conv(e: Engine, agent: String, mode: CodeMode): String {
        val r = e.call(Command.CodeCreate(project.absolutePath, agent, "", mode))
        assertTrue(r.ok, r.message)
        return r.data
    }

    private fun msgs(e: Engine, conv: String) = e.store.messages.value[conv].orEmpty()
    private fun ai(e: Engine, conv: String) = msgs(e, conv).lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
    private fun aiCount(e: Engine, conv: String) = msgs(e, conv).count { it.role == Role.AI && it.senderId == CODE_ID }
    private suspend fun await(check: () -> Boolean) = withTimeout(60_000) { while (!check()) delay(30) }
    private suspend fun awaitEnd(e: Engine, conv: String, count: Int = 1) =
        await { aiCount(e, conv) >= count && ai(e, conv)?.status.let { it != null && it != MsgStatus.STREAMING } }
    private fun waiting(e: Engine, conv: String): ToolStep? = ai(e, conv)?.tools?.lastOrNull { it.approval == "waiting" }
    private fun runs() = File(fakeDir, "args.log").takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.filter { it.isNotBlank() }.orEmpty()
    private fun stdin() = File(fakeDir, "stdin.log").takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()

    /** 两家的输出拆成事件（行是 10-19、10-20 本机实测抄下来的，路径和数字照原样）。 */
    @Test
    fun parsesBothFormats() {
        val codex = listOf(
            """{"type":"thread.started","thread_id":"01a157b2-86a1-72e1-abb5-31735894ebbe"}""",
            """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"我会只修改 a.txt。\n"}}""",
            """{"type":"item.started","item":{"id":"item_1","type":"command_execution","command":"\"C:\\\\WINDOWS\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe\" -Command 'Get-Content -LiteralPath a.txt -Raw'","aggregated_output":"","exit_code":null,"status":"in_progress"}}""",
            """{"type":"item.completed","item":{"id":"item_1","type":"command_execution","command":"x","aggregated_output":"red\n\r\n","exit_code":0,"status":"completed"}}""",
            """{"type":"turn.completed","usage":{"input_tokens":73703,"cached_input_tokens":48640,"cache_write_input_tokens":0,"output_tokens":154,"reasoning_output_tokens":0}}""",
            """{"type":"turn.failed","error":{"message":"{\"type\":\"error\",\"status\":400,\"error\":{\"type\":\"invalid_request_error\",\"message\":\"The 'no-such-model' model is not supported when using Codex with a ChatGPT account.\"}}"}}""",
        ).flatMap { AgentCli.parse(CodeSpec.CODEX, it) }
        assertEquals(AgentCli.Session("01a157b2-86a1-72e1-abb5-31735894ebbe"), codex[0])
        assertEquals(AgentCli.Text("我会只修改 a.txt。\n", false), codex[1])
        val start = codex[2] as AgentCli.Tool
        assertEquals("Get-Content -LiteralPath a.txt -Raw", start.summary, "去掉 powershell 的外壳")
        assertFalse(start.done)
        val end = codex[3] as AgentCli.Tool
        assertTrue(end.done && end.ok && end.output == "red")
        val done = codex[4] as AgentCli.Done
        assertNull(done.error)
        assertEquals(73703, done.usage.prompt)
        assertEquals(48640, done.usage.cached)
        assertEquals("The 'no-such-model' model is not supported when using Codex with a ChatGPT account.", (codex[5] as AgentCli.Done).error, "取出里面那层 message")

        val gemini = listOf(
            """{"type":"init","timestamp":"2026-10-20T05:05:14.452Z","session_id":"c264e67d-7e57-414a-8edd-c21671078cc4","model":"auto"}""",
            """{"type":"message","timestamp":"2026-10-20T05:05:14.453Z","role":"user","content":"把 b.txt 里的 red 改成 green。"}""",
            """{"type":"tool_use","timestamp":"2026-10-20T05:05:43.795Z","tool_name":"replace","tool_id":"replace__call_324017","parameters":{"new_string":"green","old_string":"red","file_path":"b.txt","instruction":"Replace red with green"}}""",
            """{"type":"tool_result","timestamp":"2026-10-20T05:05:43.811Z","tool_id":"replace__call_324017","status":"success"}""",
            """{"type":"message","timestamp":"2026-10-20T05:05:45.535Z","role":"assistant","content":"好了","delta":true}""",
            """{"type":"result","timestamp":"2026-10-20T05:05:45.547Z","status":"success","stats":{"total_tokens":29386,"input_tokens":28452,"output_tokens":103,"cached":14563,"input":13889,"duration_ms":31095,"tool_calls":2}}""",
            """{"type":"result","timestamp":"2026-10-20T05:07:01.989Z","status":"error","error":{"type":"unknown","message":"[API Error: An unknown error occurred.]"},"stats":{"total_tokens":0}}""",
        ).flatMap { AgentCli.parse(CodeSpec.GEMINI, it) }
        assertEquals(AgentCli.Session("c264e67d-7e57-414a-8edd-c21671078cc4"), gemini[0])
        assertEquals(AgentCli.Tool("replace__call_324017", "edit", "b.txt", false), gemini[1], "用户那条不算它说的")
        assertTrue((gemini[2] as AgentCli.Tool).let { it.done && it.ok })
        assertEquals(AgentCli.Text("好了", true), gemini[3])
        assertEquals(28452, (gemini[4] as AgentCli.Done).usage.prompt)
        assertEquals(31095, (gemini[4] as AgentCli.Done).usage.millis)
        assertEquals("[API Error: An unknown error occurred.]", (gemini[5] as AgentCli.Done).error)

        // 10-20 真跑见到的另一种写法：-Command "…"，里面的双引号是 \"
        assertEquals("python -B -c \"print('ok')\"",
            AgentCli.shortCommand("\"C:\\\\WINDOWS\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe\" -Command \"python -B -c \\\"print('ok')\\\"\""))
        assertTrue(AgentCli.parse(CodeSpec.GEMINI, """{"type":"tool_use","tool_name":"update_topic","tool_id":"u1","parameters":{"title":"x"}}""").isEmpty(),
            "Gemini 自己记思路的工具不显示")

        // 启动参数：只读 / 平常 / 全部放开；任务从标准输入给（codex 的「-」）
        assertEquals(listOf("exec", "--json", "--skip-git-repo-check", "-s", "read-only", "-"), AgentCli.args(CodeSpec.CODEX, "", true, false, listOf("D:\\x")))
        assertTrue("workspace-write" in AgentCli.args(CodeSpec.CODEX, "m1", false, false, emptyList()))
        assertTrue("danger-full-access" in AgentCli.args(CodeSpec.CODEX, "", false, true, emptyList()))
        assertTrue(AgentCli.args(CodeSpec.GEMINI, "", true, false, emptyList()).containsAll(listOf("--approval-mode", "plan", "--skip-trust")))
        assertTrue("auto_edit" in AgentCli.args(CodeSpec.GEMINI, "", false, false, emptyList()))
        assertTrue("yolo" in AgentCli.args(CodeSpec.GEMINI, "", false, true, emptyList()))
    }

    /** 快照：改了的、新建的、删掉的都找得到；build 这类目录不管；太大的只知道改了、没有原样；没扫完时不报新建。 */
    @Test
    fun snapshotFindsChanges() {
        File(project, "a.txt").writeText("red\n")
        File(project, "old.txt").writeText("old\n")
        File(project, "same.txt").writeText("same\n")
        File(project, "build").mkdirs(); File(project, "build/out.txt").writeText("1")
        val big = File(project, "big.bin").apply { writeBytes(ByteArray((Snapshot.MAX_FILE + 10).toInt())) }
        val snap = Snapshot.take(listOf(project))
        assertTrue(snap.complete)
        File(project, "a.txt").writeText("blue\n")
        File(project, "old.txt").delete()
        File(project, "sub").mkdirs(); File(project, "sub/new.txt").writeText("new\n")
        File(project, "build/out.txt").writeText("2")
        File(project, "same.txt").setLastModified(System.currentTimeMillis() + 60_000) // 只动了时间，内容一样
        big.writeBytes(ByteArray((Snapshot.MAX_FILE + 20).toInt()))
        val ch = snap.changes(listOf(project)).associateBy { it.file.relativeTo(project).invariantSeparatorsPath }
        assertEquals(setOf("a.txt", "old.txt", "sub/new.txt", "big.bin"), ch.keys)
        assertEquals("red\n", String(ch.getValue("a.txt").before!!))
        assertTrue(ch.getValue("old.txt").deleted)
        assertFalse(ch.getValue("sub/new.txt").existed)
        assertTrue(ch.getValue("big.bin").tooBig)
    }

    /** 接受编辑档：不问就交出去（workspace-write）；它说的话进回答；改、建、删都列出来；撤销整条回答回到原样。 */
    @Test
    fun codexChangesAreListedAndUndoable() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        File(project, "a.txt").writeText("red\n")
        File(project, "old.txt").writeText("old\n")
        val e = engine()
        val c = conv(e, CodeSpec.CODEX, CodeMode.ACCEPT_EDITS)
        e.call(Command.CodeSend(c, "把 a.txt 里的 red 改成 blue，带引号 \"x\" 和中文"))
        awaitEnd(e, c)
        val m = ai(e, c)!!
        assertEquals(MsgStatus.DONE, m.status, m.error)
        assertEquals("我来改。\n\n好了", m.content)
        assertEquals("Codex · 默认", m.modelLabel)
        assertTrue(runs().single().let { "exec --json" in it && "-s workspace-write" in it && it.endsWith(" -") }, runs().toString())
        assertTrue("带引号 \"x\" 和中文" in stdin(), "任务原样从标准输入给：" + stdin())
        assertTrue(m.tools.any { it.kind == "run" && it.input == "Set-Content a.txt blue" && it.ok }, m.tools.map { it.kind to it.input }.toString())
        val changes = m.tools.mapNotNull { it.change }.associateBy { it.path }
        assertEquals(setOf("a.txt", "new.txt", "old.txt"), changes.keys)
        assertTrue(changes.getValue("new.txt").created)
        assertEquals("blue\n", File(project, "a.txt").readText())
        assertTrue(e.call(Command.CodeRevertTask(c, m.id)).ok)
        assertEquals("red\n", File(project, "a.txt").readText())
        assertEquals("old\n", File(project, "old.txt").readText(), "删掉的恢复了")
        assertFalse(File(project, "new.txt").exists(), "新建的删掉了")
    }

    /** 手动档：交出去之前问一次；不同意就不启动它；同意了才跑。 */
    @Test
    fun manualAsksOnceBeforeHanding() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        File(project, "a.txt").writeText("red\n")
        val e = engine()
        val c = conv(e, CodeSpec.GEMINI, CodeMode.MANUAL)
        e.call(Command.CodeSend(c, "把 a.txt 里的 red 改成 blue"))
        await { waiting(e, c) != null }
        val w = waiting(e, c)!!
        assertTrue(w.input.startsWith("交给 Gemini CLI：把 a.txt"), w.input)
        e.call(Command.CodeDecide(c, w.id, false))
        awaitEnd(e, c)
        assertEquals("你没同意，这次没交给 Gemini CLI。", ai(e, c)!!.content)
        assertTrue(runs().isEmpty(), "没同意就没启动")
        assertEquals("red\n", File(project, "a.txt").readText())

        e.call(Command.CodeSend(c, "把 a.txt 里的 red 改成 blue"))
        await { aiCount(e, c) == 2 && waiting(e, c) != null }
        e.call(Command.CodeDecide(c, waiting(e, c)!!.id, true))
        awaitEnd(e, c, 2)
        val m = ai(e, c)!!
        assertEquals(MsgStatus.DONE, m.status, m.error)
        assertEquals("好了", m.content)
        assertTrue("--approval-mode auto_edit" in runs().single() && "--skip-trust" in runs().single(), runs().toString())
        assertTrue(m.tools.any { it.change?.path == "a.txt" })
        assertTrue("你没同意" in stdin() || "把 a.txt" in stdin(), "前面的对话带进去：" + stdin())
    }

    /** 计划档只读（read-only、不问、不拍快照）；报错照实显示；手机发来的不交。 */
    @Test
    fun planErrorsAndPhone() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        File(project, "a.txt").writeText("red\n")
        val e = engine()
        val c = conv(e, CodeSpec.CODEX, CodeMode.PLAN)
        e.call(Command.CodeSend(c, "看看 a.txt"))
        awaitEnd(e, c)
        assertEquals(MsgStatus.DONE, ai(e, c)!!.status, ai(e, c)!!.error)
        assertTrue("-s read-only" in runs().last(), runs().toString())
        assertTrue("只读" in stdin())

        e.call(Command.CodeSend(c, "报错"))
        awaitEnd(e, c, 2)
        assertEquals(MsgStatus.ERROR, ai(e, c)!!.status)
        assertTrue("not supported" in ai(e, c)!!.error.orEmpty(), ai(e, c)!!.error)

        // 2026-10-28 起手机发来的任务和电脑上一样交给它（以前不交：它自己读文件，管不住）
        val before = runs().size
        val v = com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeSend(c, "把 a.txt 改了"), e.state.copy(
            settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true))))
        e.call(v.command)
        awaitEnd(e, c, 3)
        assertFalse("手机发来的任务不交给" in ai(e, c)!!.error.orEmpty(), ai(e, c)!!.error)
        assertEquals(before + 1, runs().size, "手机发来的也启动了它")
    }

    companion object {
        /** 假的 codex / gemini：第一个参数说是哪个；任务从标准输入读，按里面的字决定做什么；输出照两家实测的格式。 */
        val FAKE = """
import sys, json, os
here = os.path.dirname(os.path.abspath(__file__))
who = sys.argv[1]
args = sys.argv[2:]
with open(os.path.join(here, 'args.log'), 'a', encoding='utf-8') as f:
    f.write(' '.join(args) + '\n')
task = sys.stdin.read()
with open(os.path.join(here, 'stdin.log'), 'a', encoding='utf-8') as f:
    f.write(task + '\n----\n')
def out(o):
    sys.stdout.write(json.dumps(o, ensure_ascii=False) + '\n'); sys.stdout.flush()
edit = '这次的任务：）\n把 a.txt' in task or task.startswith('把 a.txt')
if who == 'codex':
    out({'type': 'thread.started', 'thread_id': 't1'})
    out({'type': 'turn.started'})
    if '报错' in task.split('这次的任务：）')[-1]:
        out({'type': 'turn.failed', 'error': {'message': json.dumps({'type': 'error', 'status': 400, 'error': {'message': "The 'x' model is not supported"}})}})
        sys.exit(1)
    out({'type': 'item.completed', 'item': {'id': 'item_0', 'type': 'agent_message', 'text': '我来改。'}})
    cmd = '"C:\\\\WINDOWS\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe" -Command ' + "'Set-Content a.txt blue'"
    out({'type': 'item.started', 'item': {'id': 'item_1', 'type': 'command_execution', 'command': cmd, 'aggregated_output': '', 'exit_code': None, 'status': 'in_progress'}})
    if edit:
        open('a.txt', 'w', encoding='utf-8', newline='').write('blue\n')
        open('new.txt', 'w', encoding='utf-8').write('new\n')
        if os.path.exists('old.txt'): os.remove('old.txt')
    out({'type': 'item.completed', 'item': {'id': 'item_1', 'type': 'command_execution', 'command': cmd, 'aggregated_output': '', 'exit_code': 0, 'status': 'completed'}})
    out({'type': 'item.completed', 'item': {'id': 'item_2', 'type': 'agent_message', 'text': '好了'}})
    out({'type': 'turn.completed', 'usage': {'input_tokens': 100, 'cached_input_tokens': 40, 'output_tokens': 5, 'reasoning_output_tokens': 0}})
else:
    out({'type': 'init', 'session_id': 's1', 'model': 'auto'})
    out({'type': 'message', 'role': 'user', 'content': task + '\n\n\n.'})
    out({'type': 'tool_use', 'tool_name': 'replace', 'tool_id': 'replace__1', 'parameters': {'file_path': 'a.txt', 'old_string': 'red', 'new_string': 'blue'}})
    if edit:
        open('a.txt', 'w', encoding='utf-8', newline='').write('blue\n')
    out({'type': 'tool_result', 'tool_id': 'replace__1', 'status': 'success'})
    out({'type': 'message', 'role': 'assistant', 'content': '好', 'delta': True})
    out({'type': 'message', 'role': 'assistant', 'content': '了', 'delta': True})
    out({'type': 'result', 'status': 'success', 'stats': {'input_tokens': 50, 'output_tokens': 3, 'cached': 10, 'duration_ms': 7}})
""".trimStart()
    }
}
