package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.DesktopMcpServer
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.code.Commander
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Claude Code 当总指挥（2026-10-22，docs/Code栏设计说明.md 第 18 节）：
 * 1. 本机 MCP 服务的规矩（口令、Origin、GET、通知）和三个工具；
 * 2. 假 claude（python）照 --mcp-config 调我们的服务：先照真 Claude Code 的样子来问能不能用 mcp__jxy__assign_task，再派「开发」（假模型改文件）、等结果、汇报。
 * 电脑上没有 python 第 2 条跳过。
 */
class CommanderTest {
    private lateinit var dir: File
    private lateinit var project: File
    private var port = 0
    private lateinit var fake: EmbeddedServer<*, *>
    private val http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("jxy-commander").toFile()
        project = File(dir, "proj").apply { mkdirs() }
        File(project, "a.txt").writeText("greeting world\n")
        port = ServerSocket(0).use { it.localPort }
        // 假模型：给「开发」用（自己加的模型走 subAgent）。读 a.txt → 改 → 说做完了
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val toolResults = msgs.filter { it["role"]?.jsonPrimitive?.content == "tool" }.map { (it["content"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(text: String) = chunk(JsonObject(mapOf("content" to JsonPrimitive(text))).toString())
                        fun callTool(name: String, args: Map<String, String>) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString())))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t_${name}_${toolResults.size}"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        when {
                            toolResults.isEmpty() -> callTool("read_file", mapOf("path" to "a.txt"))
                            toolResults.none { "改好了" in it } -> callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "greeting world", "new_text" to "hello world"))
                            else -> say("DEV_DONE 把 a.txt 的 greeting 换成了 hello。")
                        }
                    }
                }
            }
        }
        fake.start(wait = false)
    }

    @AfterTest
    fun cleanup() {
        fake.stop(500, 1_000)
        dir.deleteRecursively()
    }

    private fun post(port: Int, body: String, token: String?, origin: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp")).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        if (token != null) b.header("Authorization", "Bearer $token")
        if (origin != null) b.header("Origin", origin)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun textOf(r: HttpResponse<String>): String =
        Json.parseToJsonElement(r.body()).jsonObject["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    /** 本机服务的规矩和三个工具（不起 claude）。 */
    @Test
    fun serverRulesAndTools() = runBlocking<Unit> {
        val commander = Commander()
        val server = DesktopMcpServer(commander)
        // 角色在别的线程上跑（真用时在总指挥那一轮的 IO 协程里），这里假装干 0.3 秒
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val s = commander.open(scope, listOf("dev" to "开发（假）")) { role, task, _ -> delay(300); "做完了：$role $task" }
            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
            assertEquals(401, post(server.port, init, null).statusCode(), "没口令")
            assertEquals(401, post(server.port, init, "wrong").statusCode(), "口令不对")
            assertEquals(403, post(server.port, init, s.token, origin = "http://evil.example").statusCode(), "网页来源")
            val ok = post(server.port, init, s.token, origin = "http://localhost:1234")
            assertEquals(200, ok.statusCode())
            assertTrue("\"protocolVersion\":\"2025-06-18\"" in ok.body(), ok.body())
            assertEquals(202, post(server.port, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", s.token).statusCode(), "通知回 202")
            val get = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp")).GET().header("Authorization", "Bearer ${s.token}").build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, get.statusCode(), "不开推送流")

            val list = post(server.port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", s.token).body()
            assertTrue("assign_task" in list && "wait_task" in list && "list_tasks" in list && "\"dev\"" in list, list)
            val bad = post(server.port, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"assign_task","arguments":{"role":"review","task":"x"}}}""", s.token)
            assertTrue("团队里没有" in textOf(bad) && "\"isError\":true" in bad.body(), bad.body())
            val a = textOf(post(server.port, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"assign_task","arguments":{"role":"dev","task":"改 a.txt","name":"改字"}}}""", s.token))
            assertTrue("T1" in a, a)
            val w = textOf(post(server.port, """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"wait_task","arguments":{"ids":["T1"],"seconds":30}}}""", s.token))
            assertTrue("做完了：dev 改 a.txt" in w, w)
            assertTrue("做完了" in textOf(post(server.port, """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"list_tasks","arguments":{}}}""", s.token)))
            // 没做完就收尾：停掉并交回
            val slow = commander.open(scope, listOf("dev" to "开发")) { _, _, _ -> delay(60_000); "不会到这里" }
            post(server.port, """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"assign_task","arguments":{"role":"dev","task":"慢活"}}}""", slow.token)
            assertEquals(listOf("T1"), commander.close(slow.token).map { it.id })
            commander.close(s.token)
            assertEquals(401, post(server.port, init, s.token).statusCode(), "这一轮结束口令作废")
        } finally {
            scope.cancel()
            server.close()
        }
    }

    private fun hasPython() = runCatching { ProcessBuilder("cmd.exe", "/c", "python", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** 假 claude 当总指挥：照 --mcp-config 调我们的服务派「开发」，开发（自己加的假模型）真改了文件，结果交回总指挥写进回答。 */
    @Test
    fun claudeCommandsTheTeam() = runBlocking<Unit> {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val fakeDir = File(dir, "fake").apply { mkdirs() }
        File(fakeDir, "fake_claude.py").writeText(FAKE, Charsets.UTF_8)
        val cmd = File(fakeDir, "claude.cmd").apply { writeText("@python -u -X utf8 \"%~dp0fake_claude.py\" %*\r\n") }
        val e = Engine(Storage(File(dir, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        val server = DesktopMcpServer(e.commander)
        e.mcpPort = server.port
        try {
            e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("code-model", tools = true)))))
            e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10, claudePath = cmd.absolutePath))))
            val conv = e.call(Command.CodeCreate(project.absolutePath, CodeSpec.CLAUDE_CODE, "", CodeMode.ACCEPT_EDITS)).data
            assertTrue(e.call(Command.CodeSetTeam(conv, "dev", "p1", "code-model")).ok)
            e.call(Command.CodeSend(conv, "总指挥：把 a.txt 的 greeting 换成 hello"))
            fun ai() = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
            withTimeout(90_000) { while (ai()?.status.let { it == null || it == MsgStatus.STREAMING }) delay(100) }
            val m = ai()!!
            println("回答：${m.content}\n步骤：" + m.tools.joinToString("\n") { "${it.kind} | ${it.input.take(60)} | ${it.note}" })
            assertEquals(MsgStatus.DONE, m.status, m.error)
            assertEquals("hello world\n", File(project, "a.txt").readText().replace("\r\n", "\n"), "开发真改了")
            assertTrue("总指挥汇报" in m.content && "DEV_DONE" in m.content, m.content)
            assertTrue(m.tools.any { it.kind == "agent" && "做完了" in it.note }, "开发那一步记在总指挥的回答上")
            assertTrue(m.tools.none { "mcp__jxy" in it.input }, "派活工具本身不重复记一步")
            assertTrue(m.tools.any { it.change != null }, "开发改的文件能看差异、撤销")
            val args = File(fakeDir, "args.log").readText()
            assertTrue("--mcp-config" in args && "--append-system-prompt-file" in args, args)
            assertTrue("PERMISSION allow" in File(fakeDir, "events.log").readText(), "派活工具不用批准")
            assertFalse(e.commander.knows(File(fakeDir, "token.log").readText().trim()), "这一轮结束口令作废")
        } finally {
            server.close()
        }
    }

    companion object {
        /** 假 claude：stream-json + can_use_tool 照真 claude；「总指挥」任务照 --mcp-config 调本机 MCP 服务。 */
        private val FAKE = """
import sys, json, os, urllib.request
here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
with open(os.path.join(here, 'args.log'), 'a', encoding='utf-8') as f:
    f.write(' '.join(args) + '\n')
ev = open(os.path.join(here, 'events.log'), 'a', encoding='utf-8')
def out(o):
    sys.stdout.write(json.dumps(o, ensure_ascii=False) + '\n'); sys.stdout.flush()
def read():
    line = sys.stdin.readline()
    if not line:
        sys.exit(0)
    return json.loads(line)
SID = 'fake-commander-1'
n = [0]
def ask(name, inp):
    n[0] += 1
    tid = 'toolu_%d' % n[0]
    out({'type': 'assistant', 'message': {'role': 'assistant', 'content': [{'type': 'tool_use', 'id': tid, 'name': name, 'input': inp}]}, 'parent_tool_use_id': None, 'session_id': SID})
    out({'type': 'control_request', 'request_id': 'req_%d' % n[0], 'request': {'subtype': 'can_use_tool', 'tool_name': name, 'input': inp, 'tool_use_id': tid}})
    while True:
        m = read()
        if m.get('type') == 'control_response':
            r = m['response']['response']
            ev.write('PERMISSION %s %s\n' % (r.get('behavior'), name)); ev.flush()
            return tid, r
def result(tid, text, err=False):
    out({'type': 'user', 'message': {'role': 'user', 'content': [{'tool_use_id': tid, 'type': 'tool_result', 'content': text, 'is_error': err}]}, 'parent_tool_use_id': None})
def say(t):
    out({'type': 'assistant', 'message': {'role': 'assistant', 'content': [{'type': 'text', 'text': t}]}, 'parent_tool_use_id': None})
def done(t):
    out({'type': 'result', 'subtype': 'success', 'is_error': False, 'result': t, 'session_id': SID, 'duration_ms': 5,
         'usage': {'input_tokens': 10, 'cache_creation_input_tokens': 0, 'cache_read_input_tokens': 5, 'output_tokens': 3}})
cfg = json.load(open(args[args.index('--mcp-config') + 1], encoding='utf-8')) if '--mcp-config' in args else None
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
rid = [0]
def rpc(method, params=None, notify=False):
    srv = cfg['mcpServers']['jxy']
    body = {'jsonrpc': '2.0', 'method': method}
    if params is not None:
        body['params'] = params
    if not notify:
        rid[0] += 1
        body['id'] = rid[0]
    h = dict(srv['headers']); h['Content-Type'] = 'application/json'; h['Accept'] = 'application/json, text/event-stream'
    req = urllib.request.Request(srv['url'], data=json.dumps(body).encode('utf-8'), headers=h)
    with opener.open(req, timeout=120) as r:
        data = r.read().decode('utf-8')
        return json.loads(data) if data else None
def call(name, arguments):
    tid, r = ask('mcp__jxy__' + name, arguments)
    out_ = rpc('tools/call', {'name': name, 'arguments': arguments})
    text = out_['result']['content'][0]['text']
    result(tid, text)
    return text
while True:
    m = read()
    if m.get('type') == 'control_request':
        out({'type': 'control_response', 'response': {'subtype': 'success', 'request_id': m['request_id'], 'response': {}}})
        continue
    if m.get('type') != 'user':
        continue
    task = ''.join(b.get('text', '') for b in m['message']['content'] if b.get('type') == 'text')
    out({'type': 'system', 'subtype': 'init', 'session_id': SID, 'model': 'claude-fake', 'cwd': os.getcwd()})
    if '总指挥' in task and cfg:
        open(os.path.join(here, 'token.log'), 'w').write(cfg['mcpServers']['jxy']['headers']['Authorization'].split(' ')[1])
        rpc('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'fake', 'version': '1'}})
        rpc('notifications/initialized', notify=True)
        tools = rpc('tools/list')
        ev.write('TOOLS %s\n' % ','.join(t['name'] for t in tools['result']['tools'])); ev.flush()
        call('assign_task', {'role': 'dev', 'task': '把 a.txt 里所有 greeting 换成 hello', 'name': '改字'})
        got = call('wait_task', {'seconds': 60})
        say('总指挥汇报：' + got)
        done('ok')
    else:
        say('不是总指挥任务')
        done('ok')
""".trimStart()
    }
}
