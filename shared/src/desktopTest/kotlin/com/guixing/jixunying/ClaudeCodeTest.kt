package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.code.ClaudeCli
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 外部 Claude Code（2026-10-18）：用一个 Python 写的假 claude（照本机 2.1.302 实测的 stream-json 格式，见 docs/Code栏设计说明.md 第 15 节）
 * 把整条路走一遍：启动参数、权限请求按权限档答、等用户批准、改前备份和撤销、--resume 接着上次的会话、会话接不上重开、没登录的提示。
 * 电脑上没有 python 就跳过。
 */
class ClaudeCodeTest {
    private lateinit var dir: File
    private lateinit var project: File
    private lateinit var fakeCmd: File
    private val argsLog get() = File(dir, "fake/args.log")
    private val inLog get() = File(dir, "fake/stdin.log")

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("jxy-claude").toFile()
        project = File(dir, "proj").apply { mkdirs() }
        File(project, "a.txt").writeText("old\n")
        val fake = File(dir, "fake").apply { mkdirs() }
        File(fake, "fake_claude.py").writeText(FAKE, Charsets.UTF_8)
        fakeCmd = File(fake, "claude.cmd").apply { writeText("@python -u -X utf8 \"%~dp0fake_claude.py\" %*\r\n") }
    }

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun hasPython() = runCatching { ProcessBuilder("cmd.exe", "/c", "python", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private suspend fun engine(): Engine {
        val e = Engine(Storage(File(dir, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveSettings(e.state.settings.copy(
            code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10, claudePath = fakeCmd.absolutePath),
        )))
        return e
    }

    private suspend fun claudeConv(e: Engine, mode: CodeMode = CodeMode.MANUAL, model: String = ""): String {
        val r = e.call(Command.CodeCreate(project.absolutePath, CodeSpec.CLAUDE_CODE, model, mode))
        assertTrue(r.ok, r.message)
        return r.data
    }

    private fun msgs(e: Engine, conv: String) = e.store.messages.value[conv].orEmpty()
    private fun ai(e: Engine, conv: String) = msgs(e, conv).lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
    private fun aiCount(e: Engine, conv: String) = msgs(e, conv).count { it.role == Role.AI && it.senderId == CODE_ID }
    private fun waiting(e: Engine, conv: String): ToolStep? = ai(e, conv)?.tools?.lastOrNull { it.approval == "waiting" }

    private suspend fun await(timeoutMs: Long = 60_000, check: () -> Boolean) = withTimeout(timeoutMs) { while (!check()) delay(30) }
    private suspend fun awaitWaiting(e: Engine, conv: String, kind: String): ToolStep {
        await { waiting(e, conv)?.kind == kind }
        return waiting(e, conv)!!
    }
    private suspend fun awaitDone(e: Engine, conv: String, count: Int = 1, st: MsgStatus = MsgStatus.DONE) =
        await { aiCount(e, conv) >= count && ai(e, conv)?.status == st }

    private fun runs() = argsLog.readLines(Charsets.UTF_8).filter { it.isNotBlank() }

    /** 2026-10-22：它读了一张图（工作区外面的截图，像试用记账本时读 %TEMP% 里的模拟器截图）——步骤上挂一份副本给用户看。 */
    @Test
    fun imageItReadIsKeptOnTheStep() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", File(dir, "shot.png"))
        val e = engine()
        val conv = claudeConv(e, CodeMode.ACCEPT_EDITS)
        e.call(Command.CodeSend(conv, "看截图"))
        awaitDone(e, conv)
        val step = ai(e, conv)!!.tools.single { it.kind == "read" }
        assertTrue(step.image.isNotBlank(), "挂了图：$step")
        assertTrue(File(dir, "data/files/${step.image}").isFile, "副本存进了附件文件夹")
    }

    @Test
    fun manualAsksForWriteAndCommandThenUndo() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e, CodeMode.MANUAL, "haiku")
        assertTrue(e.call(Command.CodeSend(conv, "写文件")).ok)
        val w = awaitWaiting(e, conv, "write")
        assertEquals("a.txt", w.input)
        assertTrue("+new" in w.output && "-old" in w.output, "等批准时就能看差异：" + w.output)
        assertEquals("old\n", File(project, "a.txt").readText(), "没批准之前不许动")
        e.call(Command.CodeDecide(conv, w.id, true))
        val r = awaitWaiting(e, conv, "run")
        assertEquals("echo hi", r.input)
        e.call(Command.CodeDecide(conv, r.id, true))
        awaitDone(e, conv)
        val m = ai(e, conv)!!
        assertEquals("new\n", File(project, "a.txt").readText())
        assertTrue("做完了" in m.content, m.content)
        assertTrue(m.modelLabel.startsWith("Claude Code"), m.modelLabel)
        val change = m.tools.firstNotNullOfOrNull { it.change }
        assertNotNull(change, "改了文件要有改动记录")
        assertEquals(1, change.added)
        assertEquals("hi", m.tools.first { it.kind == "run" }.output.trim())
        assertTrue(m.tools.any { it.kind == "todo" && "[x] 改 a.txt" in it.output }, "它的待办清单画成卡片")
        assertTrue(m.tools.any { it.kind == "read" && it.input == "a.txt" }, "没来问就做了的读也记一步")
        assertEquals("fake-session-1", e.state.conversation(conv)!!.code!!.externalSession, "记下它的会话编号")
        val a = runs().single()
        assertTrue("--permission-mode manual" in a && "--permission-prompt-tool stdio" in a && "--model haiku" in a, a)
        assertFalse("--resume" in a, a)
        assertTrue("写文件" !in a, "任务走标准输入，不放在命令行里")
        // 撤销：回到改之前
        assertTrue(e.call(Command.CodeRevertTask(conv, m.id)).ok)
        assertEquals("old\n", File(project, "a.txt").readText())
        Unit
    }

    @Test
    fun acceptEditsWritesWithoutAskingAndResumes() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e, CodeMode.ACCEPT_EDITS)
        e.call(Command.CodeSend(conv, "写文件"))
        val r = awaitWaiting(e, conv, "run")
        assertEquals("new\n", File(project, "a.txt").readText(), "接受编辑：改文件不问")
        e.call(Command.CodeDecide(conv, r.id, true))
        awaitDone(e, conv)
        e.call(Command.CodeSend(conv, "只读"))
        awaitDone(e, conv, count = 2)
        val second = runs()[1]
        assertTrue("--resume fake-session-1" in second, "第二条任务接着上次的会话：$second")
        assertFalse("--model" in second, "没选模型就不带 --model")
        Unit
    }

    @Test
    fun deniedWriteIsToldToClaude() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e)
        e.call(Command.CodeSend(conv, "写文件"))
        val w = awaitWaiting(e, conv, "write")
        e.call(Command.CodeDecide(conv, w.id, false))
        val r = awaitWaiting(e, conv, "run")
        e.call(Command.CodeDecide(conv, r.id, false))
        awaitDone(e, conv)
        assertEquals("old\n", File(project, "a.txt").readText())
        val m = ai(e, conv)!!
        assertEquals("denied", m.tools.first { it.id == w.id }.approval, "拒绝的那一步不被它回来的结果盖掉")
        assertTrue(inLog.readText(Charsets.UTF_8).contains("\"behavior\": \"deny\"") || inLog.readText(Charsets.UTF_8).contains("\"behavior\":\"deny\""),
            "拒绝要回给它")
        Unit
    }

    @Test
    fun planModeShowsPlanWithoutActing() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e, CodeMode.PLAN)
        e.call(Command.CodeSend(conv, "计划"))
        awaitDone(e, conv)
        assertTrue("--permission-mode plan" in runs().single())
        val plan = ai(e, conv)!!.tools.first { it.kind == "plan" }
        assertTrue("第一步" in plan.output, plan.output)
        assertTrue("PLAN_DENIED" in ai(e, conv)!!.content, "计划档：它要退出计划档被拒了")
        Unit
    }

    @Test
    fun staleSessionStartsFreshWithHistory() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e)
        e.call(Command.CodeSend(conv, "只读"))
        awaitDone(e, conv)
        e.call(Command.CodeSend(conv, "坏会话"))
        awaitDone(e, conv, count = 2)
        val all = runs()
        assertEquals(3, all.size, all.joinToString("\n"))
        assertTrue("--resume" in all[1] && "--resume" !in all[2], "接不上就开新的")
        val sent = inLog.readText(Charsets.UTF_8)
        assertTrue("这个会话前面的对话" in sent, "新开的会话把前面的对话带过去")
        Unit
    }

    @Test
    fun notLoggedInExplainsHowToLogIn() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e)
        e.call(Command.CodeSend(conv, "没登录"))
        awaitDone(e, conv, st = MsgStatus.ERROR)
        assertTrue("打开 Claude Code 登录" in ai(e, conv)!!.error, ai(e, conv)!!.error)
        Unit
    }

    @Test
    fun stopKillsClaude() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val e = engine()
        val conv = claudeConv(e)
        e.call(Command.CodeSend(conv, "写文件"))
        awaitWaiting(e, conv, "write")
        e.call(Command.CodeStop(conv))
        awaitDone(e, conv, st = MsgStatus.STOPPED)
        assertEquals("old\n", File(project, "a.txt").readText())
        Unit
    }

    /** 本机 2.1.302 真输出里摘的几行（签名、长字段删短了）。 */
    @Test
    fun parsesRealOutput() {
        val init = ClaudeCli.parse("""{"type":"system","subtype":"init","cwd":"C:\\x","session_id":"6fa6876f-714e-4e1c-b004-d74470341c45","tools":["Read"],"model":"claude-haiku-5-5","permissionMode":"acceptEdits"}""")
        assertEquals(ClaudeCli.Init("6fa6876f-714e-4e1c-b004-d74470341c45", "claude-haiku-5-5"), init.single())
        val use = ClaudeCli.parse("""{"type":"assistant","message":{"model":"claude-haiku-5-5","id":"msg_1","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_011FBMG7RfWaDqdgtmcUDxyC","name":"Read","input":{"file_path":"C:\\x\\a.txt"},"caller":{"type":"direct"}}]},"parent_tool_use_id":null,"session_id":"s"}""").single() as ClaudeCli.ToolUse
        assertEquals("Read", use.name)
        assertFalse(use.sub)
        val res = ClaudeCli.parse("""{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_011FBMG7RfWaDqdgtmcUDxyC","type":"tool_result","content":"1\thello probe 42\n2\t"}]},"parent_tool_use_id":null}""").single() as ClaudeCli.ToolResult
        assertTrue("hello probe 42" in res.text && !res.isError)
        val perm = ClaudeCli.parse("""{"type":"control_request","request_id":"e1d19804","request":{"subtype":"can_use_tool","tool_name":"Write","display_name":"Write","input":{"file_path":"C:\\x\\c.txt","content":"hello probe 84\n"},"description":"c.txt","permission_suggestions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}],"tool_use_id":"toolu_015srB4Bm37m2PjsTynmRx4P"}}""").single() as ClaudeCli.Permission
        assertEquals("e1d19804", perm.requestId)
        assertEquals("toolu_015srB4Bm37m2PjsTynmRx4P", perm.toolUseId)
        val done = ClaudeCli.parse("""{"type":"result","subtype":"success","is_error":false,"duration_ms":9094,"result":"42","session_id":"s1","usage":{"input_tokens":6,"cache_creation_input_tokens":14473,"cache_read_input_tokens":70810,"output_tokens":712}}""").single() as ClaudeCli.Done
        assertEquals(6 + 14473 + 70810, done.usage.prompt)
        assertEquals(70810, done.usage.cached)
        assertFalse(done.isError)
        val limit = ClaudeCli.parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1792320000,"rateLimitType":"five_hour"}}""").single()
        assertEquals(ClaudeCli.Limit("allowed", 1792320000), limit)
        assertTrue(ClaudeCli.parse("""{"type":"system","subtype":"thinking_tokens","estimated_tokens":50}""").isEmpty())
        assertTrue(ClaudeCli.parse("not json").isEmpty())
    }

    @Test
    fun envDropsParentSessionAndAddsProxy() {
        val out = ClaudeCli.env(mapOf("CLAUDECODE" to "1", "CLAUDE_CODE_SESSION_ID" to "x", "CLAUDE_CODE_GIT_BASH_PATH" to "F:\\Git\\bin\\bash.exe", "PATH" to "p"), "127.0.0.1:10809")
        assertFalse("CLAUDECODE" in out || "CLAUDE_CODE_SESSION_ID" in out)
        assertEquals("F:\\Git\\bin\\bash.exe", out["CLAUDE_CODE_GIT_BASH_PATH"])
        assertEquals("http://127.0.0.1:10809", out["HTTPS_PROXY"])
        assertEquals("http://1.2.3.4:5", ClaudeCli.env(mapOf("HTTPS_PROXY" to "http://1.2.3.4:5"), "127.0.0.1:10809")["HTTPS_PROXY"], "用户自己设的代理不覆盖")
        assertEquals("[x] 一\n[>] 二\n[ ] 三", ClaudeCli.todos(JsonObject(mapOf("todos" to kotlinx.serialization.json.JsonArray(listOf(
            JsonObject(mapOf("content" to JsonPrimitive("一"), "status" to JsonPrimitive("completed"))),
            JsonObject(mapOf("content" to JsonPrimitive("二"), "status" to JsonPrimitive("in_progress"))),
            JsonObject(mapOf("content" to JsonPrimitive("三"), "status" to JsonPrimitive("pending"))),
        ))))))
    }

    companion object {
        /** 假 claude：照真 claude 2.1.302 的 stream-json 和 can_use_tool 协议说话；参数记进 args.log，收到的每一行记进 stdin.log。 */
        internal val FAKE = """
import sys, json, os
here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
with open(os.path.join(here, 'args.log'), 'a', encoding='utf-8') as f:
    f.write(' '.join(args) + '\n')
inlog = open(os.path.join(here, 'stdin.log'), 'a', encoding='utf-8')
def out(o):
    sys.stdout.write(json.dumps(o, ensure_ascii=False) + '\n'); sys.stdout.flush()
def read():
    line = sys.stdin.readline()
    if not line:
        sys.exit(0)
    inlog.write(line); inlog.flush()
    return json.loads(line)
SID = 'fake-session-1'
n = [0]
def ask(name, inp):
    n[0] += 1
    tid = 'toolu_%d' % n[0]
    out({'type': 'assistant', 'message': {'role': 'assistant', 'content': [{'type': 'tool_use', 'id': tid, 'name': name, 'input': inp}]}, 'parent_tool_use_id': None, 'session_id': SID})
    out({'type': 'control_request', 'request_id': 'req_%d' % n[0], 'request': {'subtype': 'can_use_tool', 'tool_name': name, 'input': inp, 'tool_use_id': tid}})
    while True:
        m = read()
        if m.get('type') == 'control_response':
            return tid, m['response']['response']
def result(tid, text, err=False):
    out({'type': 'user', 'message': {'role': 'user', 'content': [{'tool_use_id': tid, 'type': 'tool_result', 'content': text, 'is_error': err}]}, 'parent_tool_use_id': None})
def free(name, inp, text):
    n[0] += 1
    tid = 'toolu_%d' % n[0]
    out({'type': 'assistant', 'message': {'role': 'assistant', 'content': [{'type': 'tool_use', 'id': tid, 'name': name, 'input': inp}]}, 'parent_tool_use_id': None})
    result(tid, text)
def say(t):
    out({'type': 'assistant', 'message': {'role': 'assistant', 'content': [{'type': 'text', 'text': t}]}, 'parent_tool_use_id': None})
def done(t, err=False):
    out({'type': 'result', 'subtype': 'success' if not err else 'error_during_execution', 'is_error': err, 'result': t, 'session_id': SID, 'duration_ms': 5,
         'usage': {'input_tokens': 10, 'cache_creation_input_tokens': 0, 'cache_read_input_tokens': 5, 'output_tokens': 3}})
while True:
    m = read()
    if m.get('type') == 'control_request':
        out({'type': 'control_response', 'response': {'subtype': 'success', 'request_id': m['request_id'], 'response': {}}})
        continue
    if m.get('type') != 'user':
        continue
    task = ''.join(b.get('text', '') for b in m['message']['content'] if b.get('type') == 'text')
    if '没登录' in task:
        out({'type': 'result', 'subtype': 'success', 'is_error': True, 'result': 'Not logged in · Please run /login', 'session_id': 'x'})
        continue
    if '坏会话' in task and '--resume' in args:
        out({'type': 'result', 'subtype': 'error_during_execution', 'is_error': True, 'result': '', 'errors': ['No conversation found with session ID: ' + SID]})
        continue
    out({'type': 'system', 'subtype': 'init', 'session_id': SID, 'model': 'claude-fake', 'cwd': os.getcwd()})
    path = os.path.join(os.getcwd(), 'a.txt')
    if '写文件' in task:
        free('TodoWrite', {'todos': [{'content': '改 a.txt', 'status': 'completed', 'activeForm': 'x'}]}, 'ok')
        free('Read', {'file_path': path}, '1\told')
        tid, r = ask('Write', {'file_path': path, 'content': 'new\n'})
        if r.get('behavior') == 'allow':
            open(path, 'w', encoding='utf-8', newline='').write(r['updatedInput']['content'])
            result(tid, 'File created successfully at: ' + path)
        else:
            result(tid, r.get('message', 'denied'), True)
        tid, r = ask('Bash', {'command': 'echo hi'})
        result(tid, 'hi' if r.get('behavior') == 'allow' else r.get('message', ''), r.get('behavior') != 'allow')
        say('做完了。')
        done('做完了。')
    elif '看截图' in task:
        shot = os.path.join(os.path.dirname(os.getcwd()), 'shot.png')
        free('Read', {'file_path': shot}, '')
        say('看了截图。')
        done('看了截图。')
    elif '计划' in task:
        tid, r = ask('ExitPlanMode', {'plan': '第一步：看代码\n第二步：改'})
        result(tid, r.get('message', ''), True)
        say('PLAN_DENIED' if r.get('behavior') == 'deny' else 'PLAN_ALLOWED')
        done('ok')
    else:
        free('Read', {'file_path': path}, '1\told')
        say('看完了。')
        done('看完了。')
""".trimStart()
    }
}
