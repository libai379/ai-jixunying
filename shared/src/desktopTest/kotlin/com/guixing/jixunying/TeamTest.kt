package com.guixing.jixunying

import com.guixing.jixunying.engine.CODE_ID
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
import com.guixing.jixunying.model.TeamSeat
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
import kotlin.test.assertTrue

/**
 * 多角色派活（2026-10-19，docs/Code栏设计说明.md 第 16 节）：主控用 assign_task 把任务单派给开发 / 审查，
 * 角色用自己的模型（假服务器按 model 字段认）或者 Claude Code（ClaudeCodeTest 的假 claude）。
 */
class TeamTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File
    private lateinit var project: File
    private lateinit var fakeCmd: File

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-team").toFile()
        project = File(dir, "proj").apply { mkdirs() }
        File(project, "a.txt").writeText("greeting world\n")
        val fk = File(dir, "fake").apply { mkdirs() }
        File(fk, "fake_claude.py").writeText(ClaudeCodeTest.FAKE, Charsets.UTF_8)
        fakeCmd = File(fk, "claude.cmd").apply { writeText("@python -u -X utf8 \"%~dp0fake_claude.py\" %*\r\n") }

        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    requests += req
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val task = msgs.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }
                        ?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val results = msgs.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                        .map { (it["content"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
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
                            // 角色：开发改 a.txt；审查硬要写文件（该被拒），然后交回
                            "你是团队里的「开发」" in task -> if (results.isEmpty())
                                callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "greeting world", "new_text" to "hello world"))
                            else say("DEV_DONE 改了 a.txt")
                            "你是团队里的「审查」" in task -> if (results.isEmpty())
                                callTool("write_file", mapOf("path" to "d.txt", "content" to "审查不该写文件"))
                            else say("REVIEW_OK 没发现问题")
                            // 主控：先派开发，再派审查，收尾
                            "组队" in task -> when (results.size) {
                                0 -> callTool("assign_task", mapOf("role" to "dev", "task" to "把 a.txt 里的问候改一下", "name" to "改问候"))
                                1 -> callTool("assign_task", mapOf("role" to "review", "task" to "审一下刚才的改动"))
                                else -> say("团队做完了。")
                            }
                            else -> say("收到。")
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
            listOf(ModelInfo("code-model"), ModelInfo("dev-model"), ModelInfo("review-model"), ModelInfo("chat-only", tools = false)))))
        e.call(Command.SaveSettings(e.state.settings.copy(
            code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10, claudePath = fakeCmd.absolutePath),
        )))
        return e
    }

    private suspend fun conv(e: Engine): String {
        val r = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.ACCEPT_EDITS))
        assertTrue(r.ok, r.message)
        return r.data
    }

    private fun ai(e: Engine, conv: String) = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
    private suspend fun awaitDone(e: Engine, conv: String) =
        withTimeout(60_000) { while (ai(e, conv)?.status != MsgStatus.DONE) delay(30) }
    private fun toolNames(req: JsonObject) = req["tools"]?.jsonArray.orEmpty()
        .mapNotNull { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
    private fun model(req: JsonObject) = req["model"]?.jsonPrimitive?.content

    /** 角色用自己的模型；审查拿不到写文件的工具、硬写也被拒；步子记在主控这条回答上；结果交回主控。 */
    @Test
    fun rolesUseTheirOwnModels() = runBlocking {
        val e = engine()
        val c = conv(e)
        assertTrue(e.call(Command.CodeSetTeam(c, "dev", "p1", "dev-model")).ok)
        assertTrue(e.call(Command.CodeSetTeam(c, "review", "p1", "review-model")).ok)
        assertEquals(listOf("dev", "review"), e.state.conversation(c)!!.code!!.team.map { it.role })
        e.call(Command.CodeSend(c, "组队改问候"))
        awaitDone(e, c)
        val m = ai(e, c)!!
        assertEquals("团队做完了。", m.content)

        val main = requests.first { model(it) == "code-model" }
        assertTrue("assign_task" in toolNames(main), toolNames(main).toString())
        assertTrue("【你的团队】" in main["messages"].toString(), "主控的系统说明里有团队")
        val dev = requests.first { model(it) == "dev-model" }
        assertTrue("你是团队里的「开发」" in dev["messages"].toString())
        assertTrue(toolNames(dev).none { it == "assign_task" || it == "spawn_agent" }, "角色不能再派活")
        val review = requests.first { model(it) == "review-model" }
        assertTrue(toolNames(review).none { it == "write_file" || it == "edit_file" }, "审查只看不改：" + toolNames(review))
        assertFalse("write_file" in review["messages"]!!.jsonArray.first().toString(), "审查的系统说明里也不提写文件的工具")

        assertEquals("hello world\n", File(project, "a.txt").readText(), "开发改了文件")
        assertFalse(File(project, "d.txt").exists(), "审查硬写文件被拒")
        val agents = m.tools.filter { it.kind == "agent" }
        assertEquals(listOf("开发：改问候", "审查：审一下刚才的改动"), agents.map { it.input })
        assertTrue(m.tools.any { it.change != null && it.change!!.path == "a.txt" }, "开发的改动挂在这条回答上，能撤销")
        val last = requests.last { model(it) == "code-model" }["messages"].toString()
        assertTrue("DEV_DONE" in last && "REVIEW_OK" in last, "两个角色的结果都交回主控")
    }

    /** 角色交给 Claude Code：每张单子一个新会话（不 --resume）、审查用 plan 档；它说的话交回主控，不进回答正文。 */
    @Test
    fun claudeCodeAsRole() = runBlocking {
        if (!runCatching { ProcessBuilder("cmd.exe", "/c", "python", "--version").start().waitFor() == 0 }.getOrDefault(false)) {
            println("没有 python，跳过"); return@runBlocking
        }
        val e = engine()
        val c = conv(e)
        assertTrue(e.call(Command.CodeSetTeam(c, "dev", CodeSpec.CLAUDE_CODE, "haiku")).ok)
        assertTrue(e.call(Command.CodeSetTeam(c, "review", CodeSpec.CLAUDE_CODE, "")).ok)
        e.call(Command.CodeSend(c, "组队看看"))
        awaitDone(e, c)
        val m = ai(e, c)!!
        assertEquals("团队做完了。", m.content, "Claude Code 说的话不进主控的正文")
        val runs = File(dir, "fake/args.log").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, runs.size, runs.toString())
        assertTrue("--model haiku" in runs[0] && "--permission-mode manual" in runs[0], runs[0])
        assertTrue("--permission-mode plan" in runs[1], "审查用 plan：" + runs[1])
        assertTrue(runs.none { "--resume" in it }, "每张单子新开会话")
        assertEquals("", e.state.conversation(c)!!.code!!.externalSession, "不记主会话的编号")
        val last = requests.last()["messages"].toString()
        assertTrue("看完了。" in last, "它说的话交回主控")
        assertTrue(m.tools.count { it.kind == "agent" } == 2, m.tools.map { it.kind to it.input }.toString())
        assertTrue(m.tools.any { it.kind == "read" }, "它读文件的步子也记在这条回答上")
    }

    /** 选角色的规矩：不认识的角色、不能调工具的模型拒；选「不用」删掉；没组队没有 assign_task；手机改不了。 */
    @Test
    fun teamRulesAndPhoneGate() = runBlocking {
        val e = engine()
        val c = conv(e)
        assertFalse(e.call(Command.CodeSetTeam(c, "boss", "p1", "dev-model")).ok)
        assertFalse(e.call(Command.CodeSetTeam(c, "dev", "p1", "chat-only")).ok)
        assertFalse(e.call(Command.CodeSetTeam(c, "dev", "p1", "nope")).ok)
        assertTrue(e.call(Command.CodeSetTeam(c, "test", "p1", "dev-model")).ok)
        assertTrue(e.call(Command.CodeSetTeam(c, "dev", "p1", "dev-model")).ok)
        assertEquals(listOf("dev", "test"), e.state.conversation(c)!!.code!!.team.map { it.role }, "按角色顺序排")
        assertTrue(e.call(Command.CodeSetTeam(c, "test")).ok)
        assertEquals(listOf(TeamSeat("dev", "p1", "dev-model")), e.state.conversation(c)!!.code!!.team)
        val v = com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeSetTeam(c, "review", "p1", "review-model"), e.state)
        assertFalse(v.refusal?.ok ?: true, "手机改不了团队")

        val solo = conv(e)
        e.call(Command.CodeSend(solo, "你好"))
        awaitDone(e, solo)
        assertTrue(toolNames(requests.last()).none { it == "assign_task" }, "没组队就没有 assign_task")
        assertFalse("【你的团队】" in requests.last()["messages"].toString())
    }
}
