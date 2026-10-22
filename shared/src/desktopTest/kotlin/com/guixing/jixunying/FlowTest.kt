package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.CodeAgent
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
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
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 一条龙（2026-10-22，docs/Code栏设计说明.md 第 19 节）：程序按「开发 → 审查 / 测试 → 没过就改 → 提交」转。
 * 假服务器按开场话认角色：审查第 1 轮说没过、第 2 轮说过；测试一直说过（testSilent 时不写结论）。
 */
class FlowTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File
    private lateinit var project: File
    @Volatile private var testSilent = false

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-flow").toFile()
        project = File(dir, "proj").apply { mkdirs() }
        File(project, "a.txt").writeText("greeting world\n")
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    requests += req
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val task = msgs.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }
                        ?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val results = msgs.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(text: String) = chunk(JsonObject(mapOf("content" to JsonPrimitive(text))).toString())
                        fun callTool(name: String, args: Map<String, String>) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString())))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t_${name}_${results.size}"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        when {
                            "你是团队里的「开发」" in task -> when {
                                results.isNotEmpty() -> say("DEV_DONE")
                                "意见如下" in task -> callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "hello world", "new_text" to "hello world!"))
                                else -> callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "greeting world", "new_text" to "hello world"))
                            }
                            // 审查：看开发交回的是第几轮
                            "你是团队里的「审查」" in task ->
                                if ("开发：第 1 轮" in task) say("少了感叹号。\n结论：没过") else say("没问题了。\n结论：**通过**")
                            "你是团队里的「测试」" in task -> say(if (testSilent) "跑了，看着还行。" else "跑了 1 个，过了。\n结论：通过")
                            else -> say("主控不该被叫到。")
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

    private suspend fun engine(): Engine {
        val e = Engine(Storage(File(dir, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test",
            listOf(ModelInfo("code-model"), ModelInfo("dev-model"), ModelInfo("review-model"), ModelInfo("test-model")))))
        e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10))))
        return e
    }

    private suspend fun team(e: Engine, c: String) {
        assertTrue(e.call(Command.CodeSetTeam(c, "dev", "p1", "dev-model")).ok)
        assertTrue(e.call(Command.CodeSetTeam(c, "review", "p1", "review-model")).ok)
        assertTrue(e.call(Command.CodeSetTeam(c, "test", "p1", "test-model")).ok)
        val r = e.call(Command.CodeSetFlow(c, CodeSpec.FLOW_DEV))
        assertTrue(r.ok, r.message)
    }

    private fun ai(e: Engine, conv: String) = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
    private suspend fun awaitDone(e: Engine, conv: String) =
        withTimeout(60_000) { while (ai(e, conv)?.status != MsgStatus.DONE) delay(30) }
    private fun model(req: JsonObject) = req["model"]?.jsonPrimitive?.content

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    /** 第 1 轮审查没过 → 意见交回开发 → 第 2 轮都过 → 独立副本里本地提交；上面选的模型一次没被叫到。 */
    @Test
    fun loopsUntilPassedAndCommitsInWorktree() = runBlocking {
        if (runCatching { git(project, "--version") }.isFailure) { println("没有 git，跳过"); return@runBlocking }
        git(project, "init", "-b", "main")
        git(project, "config", "user.email", "t@example.com")
        git(project, "config", "user.name", "t")
        git(project, "add", "-A")
        git(project, "commit", "-m", "init")
        val e = engine()
        val r = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.ACCEPT_EDITS, worktree = true))
        assertTrue(r.ok, r.message)
        val c = r.data
        team(e, c)
        e.call(Command.CodeSend(c, "把问候改成 hello world!"))
        awaitDone(e, c)
        val m = ai(e, c)!!
        assertTrue("第 2 轮都过了" in m.content && "本地提交" in m.content, m.content)
        assertTrue("审查：没过" in m.content && "审查：通过" in m.content, m.content)
        assertTrue(requests.none { model(it) == "code-model" }, "一条龙开着时上面选的模型不参与")
        val folder = File(e.state.conversation(c)!!.code!!.folder)
        assertEquals("hello world!", File(folder, "a.txt").readText().trim())
        assertTrue("（一条龙第 2 轮通过）" in git(folder, "log", "-1", "--format=%s"), git(folder, "log", "-3", "--format=%s"))
        assertEquals("greeting world", File(project, "a.txt").readText().trim(), "主仓库没动")
        // 审查和测试同时跑，两者先后不定
        val agents = m.tools.filter { it.kind == "agent" }.map { it.input }
        assertEquals(6, agents.size, agents.toString())
        assertEquals("开发：第 1 轮", agents[0])
        assertEquals(setOf("审查：第 1 轮", "测试：第 1 轮"), agents.subList(1, 3).toSet())
        assertEquals("开发：第 2 轮", agents[3])
        assertEquals(setOf("审查：第 2 轮", "测试：第 2 轮"), agents.subList(4, 6).toSet())
        // 审查第 2 轮拿到的开发交回里带程序记的改动摘要
        val review2 = requests.last { model(it) == "review-model" }["messages"].toString()
        assertTrue("【程序记的改动】改了 1 个文件" in review2, review2)
    }

    /** 测试没写结论：停下写明「状态不确定」，不转第 2 轮；不是独立副本时也不会去提交。 */
    @Test
    fun missingVerdictStops() = runBlocking {
        testSilent = true
        val e = engine()
        val c = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.ACCEPT_EDITS)).data
        team(e, c)
        e.call(Command.CodeSend(c, "把问候改成 hello world!"))
        awaitDone(e, c)
        val m = ai(e, c)!!
        assertTrue("「测试」没给结论，状态不确定" in m.content, m.content)
        assertFalse("第 2 轮" in m.content, m.content)
        assertEquals(1, requests.count { model(it) == "test-model" })
    }

    /** 不是独立副本：都过了也不提交，写明原因；开关要先组好队；结论按最后一个认。 */
    @Test
    fun notWorktreeNoCommitAndRules() = runBlocking {
        val e = engine()
        val c = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.ACCEPT_EDITS)).data
        assertFalse(e.call(Command.CodeSetFlow(c, CodeSpec.FLOW_DEV)).ok, "没组队不能开")
        assertFalse(e.call(Command.CodeSetFlow(c, "whatever")).ok)
        team(e, c)
        e.call(Command.CodeSend(c, "把问候改成 hello world!"))
        awaitDone(e, c)
        val m = ai(e, c)!!
        assertTrue("第 2 轮都过了" in m.content && "不是独立副本" in m.content, m.content)
        assertEquals("hello world!", File(project, "a.txt").readText().trim())
        assertTrue(CodeAgent.flowVerdict("先说结论：没过\n改完再看。\n结论： 通过") == true)
        assertTrue(CodeAgent.flowVerdict("结论：「没过」") == false)
        assertNull(CodeAgent.flowVerdict("看着还行"))
        // 关掉以后回到原来的做法：上面选的模型来答
        assertTrue(e.call(Command.CodeSetFlow(c, "")).ok)
        e.call(Command.CodeSend(c, "随便聊一句"))
        withTimeout(60_000) { while (requests.none { model(it) == "code-model" }) delay(30) }
        Unit
    }
}
