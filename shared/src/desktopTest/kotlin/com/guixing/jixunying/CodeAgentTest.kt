package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.CODE_ID
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
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
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Code 栏的编码循环（docs/Code栏设计说明.md 第 3、4、9 节，2026-10-12）：
 * 用一个假的模型服务器按脚本调工具，把读文件 / 改文件 / 等批准 / 允许 / 拒绝、越界、计划档、
 * 改错地方、停下、审阅和撤销、差异和记账都跑一遍；末尾再用进程内 MQTT 中转验一遍手机的拦截。
 * 循环、工具、审批本身在 engine/CodeAgent.kt。
 */
class CodeAgentTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    /** 用户选的那个项目文件夹。 */
    private lateinit var project: File

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun base() = "http://127.0.0.1:$port"

    @BeforeTest
    fun setUp() {
        port = freePort()
        dir = kotlin.io.path.createTempDirectory("jxy-code-agent").toFile()
        project = File(dir, "proj").apply { mkdirs() }
        // 两个样例文件：a.txt 用来改；dup.txt 里「dup」出现两次（测 old_text 不唯一）
        File(project, "a.txt").writeText("greeting world\n")
        File(project, "dup.txt").writeText("dup line\ndup line\n")

        fake = embeddedServer(CIO, port = port) {
            routing {
                // 2026-10-17：fetch_url 读的网页
                get("/page") { call.respondText("<html><body><p>PAGE_BODY_42</p></body></html>", ContentType.Text.Html) }
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    requests += req
                    val msgs = req["messages"]!!.jsonArray
                    val system = (msgs.first().jsonObject["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    // 任务文字：最后一条 user（后面几轮最后一条是工具结果；一个会话连发两次时前面还有旧任务）
                    val task = msgs.map { it.jsonObject }.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }
                        ?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val toolResults = msgs.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                        .map { (it["content"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(text: String) = chunk(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("content" to JsonPrimitive(text)))))
                        fun callTool(name: String, args: Map<String, String>) {
                            val argsJson = JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString()
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(argsJson)))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t_${name}_${toolResults.size}"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        when {
                            // 2026-10-17「自动」档的检查员：动作里有 ALLOW_ME 就放行，否则拦下
                            "你是安全检查员" in system -> say(if ("ALLOW_ME" in system) "{\"allow\": true, \"reason\": \"在请求范围内\"}" else "{\"allow\": false, \"reason\": \"超出请求范围\"}")
                            !system.startsWith("你在帮用户改一个项目里的代码") -> say("（这不是 Code 会话，不该走到这里）")
                            // 读 → 改 → 看结果说话（允许就说改完了，拒绝就说不改了）
                            "所有 greeting" in task -> when {
                                toolResults.isEmpty() -> callTool("read_file", mapOf("path" to "a.txt"))
                                toolResults.none { "改好了" in it || "用户拒绝了" in it } ->
                                    callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "greeting world", "new_text" to "hello world"))
                                toolResults.any { "用户拒绝了" in it } -> say("好的，不改了。")
                                else -> say("改完了。")
                            }
                            // old_text 在文件里找不到
                            "不存在的文字" in task -> if (toolResults.isEmpty())
                                callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "根本不存在的文字", "new_text" to "x"))
                            else say("没找到，那就算了。")
                            // old_text 出现两次
                            "重复的文字" in task -> if (toolResults.isEmpty())
                                callTool("edit_file", mapOf("path" to "dup.txt", "old_text" to "dup", "new_text" to "dup2"))
                            else say("分不清改哪一处，先不动。")
                            // 计划档：模型硬要写文件
                            "计划档" in task -> if (toolResults.isEmpty())
                                callTool("write_file", mapOf("path" to "d.txt", "content" to "不该被建出来"))
                            else say("知道了。")
                            // 越界：先往项目文件夹外面写，再读一个绝对路径
                            "越界" in task -> when {
                                toolResults.isEmpty() -> callTool("write_file", mapOf("path" to "../evil.txt", "content" to "不该被写出来"))
                                toolResults.size == 1 -> callTool("read_file", mapOf("path" to "C:\\Windows\\win.ini"))
                                else -> say("知道了。")
                            }
                            // 跑一个很久的命令（等会要测「停下」）
                            "长命令" in task -> if (toolResults.isEmpty())
                                callTool("run_command", mapOf("command" to "Start-Sleep 30"))
                            else say("跑完了。")
                            // 改两处：改 a.txt，再新建 b.txt
                            "改两处" in task -> when {
                                toolResults.isEmpty() -> callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "greeting world", "new_text" to "hello world"))
                                toolResults.none { "写好了" in it } -> callTool("write_file", mapOf("path" to "b.txt", "content" to "新建的内容\n"))
                                else -> say("两处都改了。")
                            }
                            // 主会话审查时补的（10-12）：项目里有个目录联接指到外面，外面的文件里有 SECRET_OUTSIDE
                            "找联接" in task -> if (toolResults.isEmpty())
                                callTool("grep", mapOf("pattern" to "SECRET_OUTSIDE"))
                            else say("找完了。")
                            // 改一个 GBK 编码的老文件
                            "改GBK" in task -> if (toolResults.isEmpty())
                                callTool("edit_file", mapOf("path" to "gbk.txt", "old_text" to "中文", "new_text" to "汉字"))
                            else say("知道了。")
                            // 同一个文件连改两次
                            "连改两次" in task -> when (toolResults.size) {
                                0 -> callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "greeting world", "new_text" to "hello world"))
                                1 -> callTool("edit_file", mapOf("path" to "a.txt", "old_text" to "hello world", "new_text" to "hey world"))
                                else -> say("改了两次。")
                            }
                            // 2026-10-17：写工作区外面的一个文件（路径跟在任务后面）
                            task.startsWith("写外面的文件 ") -> if (toolResults.isEmpty())
                                callTool("write_file", mapOf("path" to task.removePrefix("写外面的文件 ").trim(), "content" to "WRITTEN\n"))
                            else say("写完了。")
                            // 2026-10-17：读工作区外面的一个文件（路径跟在任务后面）
                            task.startsWith("读外面的文件 ") -> if (toolResults.isEmpty())
                                callTool("read_file", mapOf("path" to task.removePrefix("读外面的文件 ").trim()))
                            else say("读完了。")
                            // 2026-10-17：读项目里的一张图片
                            "看看图" in task -> if (toolResults.isEmpty())
                                callTool("read_file", mapOf("path" to "pic.png"))
                            else say("看完了。")
                            // 2026-10-17：按文件名找 / 读网页（参数跟在任务后面）
                            task.startsWith("找文件 ") -> if (toolResults.isEmpty())
                                callTool("find_files", mapOf("pattern" to task.removePrefix("找文件 ").trim()))
                            else say("找完了。")
                            task.startsWith("读网页 ") -> if (toolResults.isEmpty())
                                callTool("fetch_url", mapOf("url" to task.removePrefix("读网页 ").trim()))
                            else say("读完了。")
                            // 2026-10-18：后台跑一条命令，再看一次输出
                            task.startsWith("后台跑 ") -> when (toolResults.size) {
                                0 -> callTool("run_command", mapOf("command" to task.removePrefix("后台跑 ").trim(), "background" to "true"))
                                1 -> {
                                    Thread.sleep(2500)
                                    callTool("read_output", mapOf("id" to Regex("编号 (\\S+?)。").find(toolResults[0])!!.groupValues[1]))
                                }
                                else -> say("开好了。")
                            }
                            // 2026-10-17：跑任务后面跟的那条命令
                            task.startsWith("跑命令 ") -> if (toolResults.isEmpty())
                                callTool("run_command", mapOf("command" to task.removePrefix("跑命令 ").trim()))
                            else say("跑完了。")
                            // 一直在读，不停下来（测走满最多步数）
                            "一直读" in task -> callTool("read_file", mapOf("path" to "a.txt"))
                            // 慢一点再回答（测同一个会话里第二条排队）
                            "排队测试" in task -> {
                                Thread.sleep(700)
                                say(if ("第二条" in task) "第二条做完了。" else "第一条做完了。")
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

    /** 装好一个能跑 Code 的电脑引擎：执行器、服务商（支持工具）、Code 设置都在。 */
    private suspend fun engine(): Engine {
        val e = Engine(Storage(dir))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "${base()}/v1", "sk-test", listOf(ModelInfo("code-model")))))
        e.call(Command.SaveSettings(e.state.settings.copy(
            code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10, allowPhone = false),
        )))
        return e
    }

    private suspend fun codeConv(e: Engine, mode: CodeMode = CodeMode.MANUAL): String {
        val r = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model", mode))
        assertTrue(r.ok, r.message)
        return r.data
    }

    private fun listOf(e: Engine, conv: String) = e.store.messages.value[conv].orEmpty()
    private fun aiOf(e: Engine, conv: String) = listOf(e, conv).lastOrNull { it.role == Role.AI && it.senderId == CODE_ID }
    private fun waitingOf(e: Engine, conv: String): ToolStep? = aiOf(e, conv)?.tools?.lastOrNull { it.approval == "waiting" }

    private suspend fun await(timeoutMs: Long = 30_000, check: () -> Boolean) {
        withTimeout(timeoutMs) { while (!check()) delay(30) }
    }

    /** 等某一步推出来等批准，返回那一步。 */
    private suspend fun awaitWaiting(e: Engine, conv: String, kind: String? = null): ToolStep {
        await { waitingOf(e, conv)?.let { kind == null || it.kind == kind } == true }
        return waitingOf(e, conv)!!
    }

    private fun aiCount(e: Engine, conv: String) = listOf(e, conv).count { it.role == Role.AI && it.senderId == CODE_ID }

    /** 发一条任务，返回发完之后至少该有几条回答消息（同一个会话连发两次时用来等新的那一条）。 */
    private suspend fun sendTask(e: Engine, conv: String, text: String): Int {
        val before = aiCount(e, conv)
        assertTrue(e.call(Command.CodeSend(conv, text)).ok)
        await { aiCount(e, conv) > before }
        return before + 1
    }

    /** 等第 minAi 条（含）之后最后一条回答到某个状态。 */
    private suspend fun awaitStatus(e: Engine, conv: String, st: MsgStatus, minAi: Int = 1, timeoutMs: Long = 30_000) {
        await(timeoutMs) {
            val ai = listOf(e, conv).filter { it.role == Role.AI && it.senderId == CODE_ID }
            ai.size >= minAi && ai.last().status == st
        }
    }

    /** 模型这几轮收到的所有工具结果（用来确认它「看到了什么」）。 */
    private fun lastToolTexts() = requests.last()["messages"].toString()

    // ———————————————— 主会话审查时补的（10-12） ————————————————

    @Test
    fun grepDoesNotFollowJunctionOutside() = runBlocking {
        val outside = File(dir, "outside").apply { mkdirs(); File(this, "secret.txt").writeText("SECRET_OUTSIDE\n") }
        val link = File(project, "link")
        val p = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, outside.absolutePath).redirectErrorStream(true).start()
        p.waitFor()
        if (!link.exists()) { println("建不了目录联接，跳过"); return@runBlocking }
        try {
            val e = engine()
            val conv = codeConv(e)
            e.call(Command.CodeSend(conv, "找联接里的东西"))
            awaitStatus(e, conv, MsgStatus.DONE)
            assertTrue("没找到" in lastToolTexts(), lastToolTexts())
            assertFalse("secret.txt" in lastToolTexts(), "不能顺着联接读到项目外的文件：" + lastToolTexts())
        } finally {
            java.nio.file.Files.delete(link.toPath())
        }
    }

    @Test
    fun editRefusesNonUtf8File() = runBlocking {
        val gbk = File(project, "gbk.txt").apply { writeBytes("老文件里的中文\n".toByteArray(charset("GBK"))) }
        val before = gbk.readBytes()
        val e = engine()
        val conv = codeConv(e, CodeMode.ACCEPT_EDITS)
        e.call(Command.CodeSend(conv, "改GBK文件"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertTrue(before.contentEquals(gbk.readBytes()), "GBK 文件一个字节都不能动")
        assertTrue("不是 UTF-8" in lastToolTexts(), lastToolTexts())
    }

    @Test
    fun rejectEarlierChangeAlsoMarksLaterOnSameFile() = runBlocking {
        val e = engine()
        val conv = codeConv(e, CodeMode.ACCEPT_EDITS)
        e.call(Command.CodeSend(conv, "连改两次"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertEquals("hey world\n", File(project, "a.txt").readText())
        val changes = aiOf(e, conv)!!.tools.mapNotNull { it.change }
        assertEquals(2, changes.size)
        val r = e.call(Command.CodeReview(conv, changes[0].id, false))
        assertTrue(r.ok, r.message)
        assertEquals("greeting world\n", File(project, "a.txt").readText(), "回到最早的样子")
        assertTrue("后面的 1 处" in r.message, r.message)
        await { aiOf(e, conv)!!.tools.mapNotNull { it.change }.all { it.review == "rejected" } }
        // 再点一次拒绝不做事；已经撤销的不能再「接受」
        assertTrue(e.call(Command.CodeReview(conv, changes[0].id, false)).ok)
        assertFalse(e.call(Command.CodeReview(conv, changes[1].id, true)).ok)
        assertEquals("greeting world\n", File(project, "a.txt").readText())
    }

    // ———————————————— 单子里的验收项 ————————————————

    @Test
    fun readEditWaitApprove() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "把所有 greeting 改成 hello"))
        // 先读了 a.txt（读不用批准），然后要改 a.txt 时停下来等批准
        val step = awaitWaiting(e, conv, "edit")
        assertEquals("edit", step.kind)
        assertEquals("a.txt", step.input)
        assertTrue("greeting world" in step.output && "hello world" in step.output, step.output)
        assertEquals("greeting world\n", File(project, "a.txt").readText(), "还没批准，文件不能动")

        assertTrue(e.call(Command.CodeDecide(conv, step.id, true)).ok)
        awaitStatus(e, conv, MsgStatus.DONE)
        assertEquals("hello world\n", File(project, "a.txt").readText())

        val ai = assertNotNull(aiOf(e, conv))
        assertEquals("改完了。", ai.content.trim())
        val change = assertNotNull(ai.tools.first { it.id == step.id }.change)
        assertEquals("a.txt", change.path)
        assertEquals(1, change.added)
        assertEquals(1, change.removed)
        assertFalse(change.created, "a.txt 本来就有")
        assertEquals("", change.review)
        assertEquals("allowed", ai.tools.first { it.id == step.id }.approval)
    }

    @Test
    fun denyLeavesFileUntouched() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        val before = File(project, "a.txt").readBytes()
        e.call(Command.CodeSend(conv, "把所有 greeting 改成 hello"))
        val step = awaitWaiting(e, conv, "edit")
        assertTrue(e.call(Command.CodeDecide(conv, step.id, false)).ok)
        awaitStatus(e, conv, MsgStatus.DONE)

        assertContentEqualsString(before, File(project, "a.txt").readBytes(), "拒绝了就不能动文件")
        val ai = assertNotNull(aiOf(e, conv))
        assertEquals("好的，不改了。", ai.content.trim())
        assertEquals("denied", ai.tools.first { it.id == step.id }.approval)
        assertNull(ai.tools.first { it.id == step.id }.change, "拒绝了不算改动")
        assertTrue("用户拒绝了" in lastToolTexts(), "模型要收到「用户拒绝了」")
    }

    /**
     * 2026-10-17 用户改了规矩：工作区外面的文件也能读写，但每次都先问（照 Claude Code）。
     * 「接受编辑」档也要问（它只放行工作区里的改动）；拒绝了文件不动；允许了照常读。
     */
    @Test
    fun pathOutsideProjectAsksFirst() = runBlocking {
        val e = engine()
        val conv = codeConv(e, CodeMode.ACCEPT_EDITS)
        e.call(Command.CodeSend(conv, "越界测试"))

        val write = awaitWaiting(e, conv, "write")
        assertTrue(write.scopeDir.isNotBlank(), "工作区外的写要给出「加进工作区」的文件夹")
        assertTrue(e.call(Command.CodeDecide(conv, write.id, false)).ok)
        // 2026-10-17「比 Claude 更开放」：电脑上发起的，工作区外面的读不问
        awaitStatus(e, conv, MsgStatus.DONE)
        assertTrue(aiOf(e, conv)!!.tools.single { it.kind == "read" }.approval.isEmpty(), "读外面的不该问")

        assertFalse(File(dir, "evil.txt").exists(), "拒绝了就不能建出文件")
        assertTrue("用户拒绝了" in lastToolTexts(), "拒绝要告诉模型")
        assertTrue("fonts" in lastToolTexts().lowercase(), "允许了就真读到 win.ini：" + lastToolTexts().take(500))
        // 只允许这一次：工作区没变
        assertTrue(e.state.conversation(conv)!!.code!!.extraPaths.isEmpty())
    }

    /** 批准时选「允许，并把这个文件夹加进工作区」：之后同一个文件夹里的读不再问；手机发来的同样选项只当这一次。 */
    @Test
    fun allowAndAddFolderWidensWorkspace() = runBlocking {
        val outsideDir = File(dir, "other").apply { mkdirs(); File(this, "x.txt").writeText("OTHER_CONTENT\n") }
        val e = engine()
        val conv = codeConv(e)
        val path = File(outsideDir, "x.txt").absolutePath
        // 手机来的「加进工作区」被 PhoneGate 改成只这一次
        val gate = com.guixing.jixunying.engine.code.PhoneGate
        assertNotNull(gate.check(Command.CodeDecide(conv, "s1", true, "dir"), e.state).refusal, "开关没开：手机一律拒")
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        val viaPhone = gate.check(Command.CodeDecide(conv, "s1", true, "dir"), on)
        assertNull(viaPhone.refusal)
        assertEquals("", (viaPhone.command as Command.CodeDecide).remember, "开关开着：批准放行，但「加进工作区」只当这一次")
        assertNotNull(gate.check(Command.CodeSetAccess(conv, listOf(path), emptyList()), on).refusal, "手机不能改工作区")

        // 读外面的不问（2026-10-17）
        e.call(Command.CodeSend(conv, "读外面的文件 $path"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertTrue("OTHER_CONTENT" in lastToolTexts())
        // 写外面的要问；选「加进工作区」
        val n1 = sendTask(e, conv, "写外面的文件 $path")
        val ask = awaitWaiting(e, conv, "write")
        assertEquals(outsideDir.absolutePath.lowercase(), ask.scopeDir.lowercase())
        assertTrue(e.call(Command.CodeDecide(conv, ask.id, true, "dir")).ok)
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n1)
        assertEquals("WRITTEN\n", File(path).readText())
        assertEquals(listOf(outsideDir.absolutePath.lowercase()), e.state.conversation(conv)!!.code!!.extraPaths.map { it.lowercase() })
        // 第二次写同一个文件夹：手动档下工作区里的写照样问（手动档的规矩），切到接受编辑就不问
        e.call(Command.CodeSetMode(conv, CodeMode.ACCEPT_EDITS))
        val n2 = sendTask(e, conv, "写外面的文件 $path")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n2)
        assertTrue(aiOf(e, conv)!!.tools.none { it.approval.isNotEmpty() }, "加进工作区、接受编辑以后不该再问")
    }

    /** 2026-10-17：设置 → 个性化 的自定义指令、项目里的 CLAUDE.md 都进 Code 的系统说明；有 CLAUDE.md 时不读 AGENTS.md。 */
    @Test
    fun instructionsAndProjectNotesReachTheModel() = runBlocking {
        File(project, "CLAUDE.md").writeText("NOTE_FROM_CLAUDE_MD\n")
        File(project, "AGENTS.md").writeText("NOTE_FROM_AGENTS_MD\n")
        val e = engine()
        e.call(Command.SaveSettings(e.state.settings.copy(personal = com.guixing.jixunying.model.PersonalSettings("INSTR_ALL", "INSTR_CODE"))))
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "你好"))
        awaitStatus(e, conv, MsgStatus.DONE)
        val system = requests.last()["messages"]!!.jsonArray.first().jsonObject["content"].toString()
        assertTrue("INSTR_ALL" in system && "INSTR_CODE" in system, "自定义指令")
        assertTrue("NOTE_FROM_CLAUDE_MD" in system, "项目说明")
        assertFalse("NOTE_FROM_AGENTS_MD" in system, "有 CLAUDE.md 时不读 AGENTS.md")
        assertTrue(aiOf(e, conv)!!.tools.any { it.note.contains("项目说明") && "CLAUDE.md" in it.input }, "读了哪些说明要记一步给用户看")
        // 旧版手机（schema 5）不认识个性化，发来的保存设置不能把电脑上的自定义指令冲掉
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(schema = 5, personal = com.guixing.jixunying.model.PersonalSettings()))).ok)
        assertEquals("INSTR_ALL", e.state.settings.personal.instructions)
    }

    /** 2026-10-17：find_files 按文件名找（*.kt 不写目录也能找到深层的）；指到外面的联接不跟。 */
    @Test
    fun findFilesByName() = runBlocking {
        File(project, "src/deep/x").mkdirs()
        File(project, "src/deep/x/Hello.kt").writeText("x")
        File(project, "README.md").writeText("x")
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "找文件 *.kt"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertTrue("src/deep/x/Hello.kt" in lastToolTexts(), lastToolTexts().takeLast(300))
        assertFalse("README.md" in lastToolTexts().substringAfter("找到"))
        val n = sendTask(e, conv, "找文件 src/**/*.kt")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n)
        assertTrue("src/deep/x/Hello.kt" in lastToolTexts())
    }

    /** 2026-10-17：新建不用先选——文件夹、模型用上一个会话的；开起来以后能换文件夹，以前的改动照样能撤销。 */
    @Test
    fun createWithoutPickingAndSwitchFolder() = runBlocking {
        val e = engine()
        val first = codeConv(e)
        val r = e.call(Command.CodeCreate("", "", ""))
        assertTrue(r.ok, r.message)
        val spec = e.state.conversation(r.data)!!.code!!
        assertEquals(e.state.conversation(first)!!.code!!.folder, spec.folder, "用上一个会话的文件夹")
        assertEquals("code-model", spec.modelId)
        // 在旧文件夹里改一处，再换文件夹：撤销照样恢复旧文件夹里的那个文件
        e.call(Command.CodeSetMode(first, CodeMode.ACCEPT_EDITS))
        e.call(Command.CodeSend(first, "所有 greeting"))
        awaitStatus(e, first, MsgStatus.DONE)
        val ch = aiOf(e, first)!!.tools.mapNotNull { it.change }.single()
        val other = File(dir, "另一个项目").apply { mkdirs() }
        assertTrue(e.call(Command.CodeSetProject(first, folder = other.absolutePath)).ok)
        assertEquals(other.absolutePath, e.state.conversation(first)!!.code!!.folder)
        assertTrue(e.call(Command.CodeReview(first, ch.id, false)).ok)
        assertEquals("greeting world\n", File(project, "a.txt").readText(), "换了文件夹也能撤销旧文件夹里的改动")
        // 手机换不了
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        assertNotNull(com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeSetProject(first, folder = "C:\\"), on).refusal)
        Unit
    }

    /** 2026-10-17「自动」档：工作区里的改动直接做；外面的、跑命令先给检查员看，放行就做，拦下的带着理由问用户。 */
    @Test
    fun autoModeUsesChecker() = runBlocking {
        val e = engine()
        e.call(Command.SaveSettings(e.state.settings.copy(memory = e.state.settings.memory.copy(judgeProviderId = "p1", judgeModelId = "code-model"))))
        val conv = codeConv(e)
        assertTrue(e.call(Command.CodeSetMode(conv, CodeMode.ACCEPT_EDITS, auto = true)).ok)
        assertTrue(e.state.conversation(conv)!!.code!!.autoMode)
        // 外面的写：检查员放行（路径里有 ALLOW_ME）→ 不问
        val ok = File(dir, "ALLOW_ME/z.txt").apply { parentFile.mkdirs() }
        e.call(Command.CodeSend(conv, "写外面的文件 ${ok.absolutePath}"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertEquals("WRITTEN\n", ok.readText())
        assertTrue(aiOf(e, conv)!!.tools.none { it.approval.isNotEmpty() })
        // 跑命令：检查员拦下 → 问用户，说明里带理由
        sendTask(e, conv, "跑命令 Write-Output hi")
        val ask = awaitWaiting(e, conv, "run")
        assertTrue("自动检查拦下" in ask.note && "超出请求范围" in ask.note, ask.note)
        e.call(Command.CodeStop(conv))
        // 手机打不开自动
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        assertNotNull(com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeSetMode(conv, CodeMode.ACCEPT_EDITS, auto = true), on).refusal)
        // 检查员的回答解析
        assertEquals(true to "好", com.guixing.jixunying.engine.AutoJudge.parse("<think>x</think>{\"allow\": true, \"reason\": \"好\"}"))
        assertNull(com.guixing.jixunying.engine.AutoJudge.parse("我觉得可以"))
    }

    /** 2026-10-18 终端：在项目文件夹里跑；cd 只换目录、记得住；末尾 & 放后台；手机上用不了。 */
    @Test
    fun terminalRunsAndRemembersCd() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        File(project, "sub").mkdirs()
        fun data(r: com.guixing.jixunying.model.CommandResult) = Json.parseToJsonElement(r.data).jsonObject.mapValues { it.value.jsonPrimitive.content }
        val r1 = data(e.call(Command.TermRun(conv, "Write-Output TERM_OK")))
        assertTrue("TERM_OK" in r1["output"]!!)
        assertEquals("0", r1["code"])
        val r2 = data(e.call(Command.TermRun(conv, "cd sub")))
        assertEquals(File(project, "sub").canonicalPath, r2["cwd"])
        val r3 = data(e.call(Command.TermRun(conv, "(Get-Location).Path", r2["cwd"]!!)))
        assertTrue(r3["output"]!!.trim().endsWith("sub"), r3["output"])
        val r4 = data(e.call(Command.TermRun(conv, "Start-Sleep 30 &")))
        assertTrue("后台" in r4["output"]!!)
        e.state.conversation(conv)!!.background.forEach { e.call(Command.BgStop(conv, it.id)) }
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        assertNotNull(com.guixing.jixunying.engine.code.PhoneGate.check(Command.TermRun(conv, "dir"), on).refusal)
        Unit
    }

    /** 2026-10-18 后台命令：开起来马上返回编号；认出输出里的本机网址；read_output 看得到；停得掉。 */
    @Test
    fun backgroundCommandRunsAndStops() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSetMode(conv, CodeMode.ACCEPT_EDITS, openAll = true))
        e.call(Command.CodeSend(conv, "后台跑 Write-Output 'Local: http://localhost:5173/'; Start-Sleep 60"))
        awaitStatus(e, conv, MsgStatus.DONE, timeoutMs = 60_000)
        assertTrue("localhost:5173" in lastToolTexts(), lastToolTexts().takeLast(400))
        val p = e.state.conversation(conv)!!.background.single()
        assertTrue(p.running)
        await(15_000) { e.state.conversation(conv)!!.background.single().url.isNotBlank() }
        assertEquals("http://localhost:5173", e.state.conversation(conv)!!.background.single().url)
        assertTrue(e.call(Command.BgStop(conv, p.id)).ok)
        assertTrue(!e.state.conversation(conv)!!.background.single().running)
    }

    /** 2026-10-17：fetch_url 不问（和对话栏统一，读网页不改本机任何东西）。 */
    @Test
    fun fetchUrlReadsWithoutAsking() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "读网页 ${base()}/page"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertTrue("PAGE_BODY_42" in lastToolTexts(), lastToolTexts().takeLast(300))
        assertTrue(aiOf(e, conv)!!.tools.single { it.kind == "fetch" }.approval.isEmpty())
    }

    /** 2026-10-17「全部放开」：写工作区外面、跑命令都不问；危险命令照样问；手机打不开。 */
    @Test
    fun openAllSkipsApprovalsExceptDangerous() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        assertNotNull(com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeSetMode(conv, CodeMode.ACCEPT_EDITS, openAll = true), on).refusal, "手机打不开全部放开")
        assertTrue(e.call(Command.CodeSetMode(conv, CodeMode.ACCEPT_EDITS, openAll = true)).ok)
        val out = File(dir, "elsewhere/y.txt").apply { parentFile.mkdirs() }
        e.call(Command.CodeSend(conv, "写外面的文件 ${out.absolutePath}"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertEquals("WRITTEN\n", out.readText())
        val n = sendTask(e, conv, "跑命令 Write-Output 放开")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n)
        assertTrue(aiOf(e, conv)!!.tools.single { it.kind == "run" }.approval.isEmpty(), "全部放开不问命令")
        // 改之前照样备份：能撤销
        val ch = listOf(e, conv).flatMap { m -> m.tools.mapNotNull { it.change } }.first { it.path.endsWith("y.txt") }
        assertTrue(e.call(Command.CodeReview(conv, ch.id, false)).ok)
        assertFalse(out.exists(), "新建的撤销后删掉")
        // 危险命令照样问
        sendTask(e, conv, "跑命令 Remove-Item x -Recurse")
        assertEquals("run", awaitWaiting(e, conv, "run").kind)
        e.call(Command.CodeStop(conv))
        Unit
    }

    private fun writePng(f: File) {
        val img = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(img, "png", f)
    }

    /** 2026-10-17：附件——图片发给能看图的模型；拖进来的文件原路径写进任务、加进工作区。 */
    @Test
    fun attachmentsReachTheModel() = runBlocking {
        val e = engine()
        e.call(Command.SaveProvider(e.state.provider("p1")!!.copy(models = listOf(ModelInfo("code-model", vision = true)))))
        val conv = codeConv(e)
        val png = File(dir, "shot.png").also { writePng(it) }
        val att = e.upload(png.name, "image/png", png.readBytes())!!
        val doc = File(dir, "notes.md").apply { writeText("DOC_BODY_123\n") }
        val docAtt = e.upload(doc.name, "text/markdown", doc.readBytes())!!
        assertTrue(e.call(Command.CodeSend(conv, "看这张截图", listOf(att.id, docAtt.id), listOf(doc.absolutePath))).ok)
        awaitStatus(e, conv, MsgStatus.DONE)
        val sent = requests.last()["messages"].toString()
        assertTrue("image_url" in sent && "data:image" in sent, "图片要发给能看图的模型")
        assertTrue("DOC_BODY_123" in sent, "文档的文字要一起发")
        assertTrue(doc.absolutePath.replace("\\", "\\\\") in sent, "原路径写进任务里")
        assertTrue(e.state.conversation(conv)!!.code!!.extraPaths.any { it.equals(doc.absolutePath, ignoreCase = true) }, "拖进来的文件加进工作区")
        // 手机发来的路径被清掉
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        val viaPhone = com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeSend(conv, "x", paths = listOf("C:\\x")), on).command as Command.CodeSend
        assertTrue(viaPhone.paths.isEmpty())
    }

    /** 2026-10-17：read_file 读到图片——能看图的模型在下一条消息里收到图片；看不了图的告诉它看不了。 */
    @Test
    fun readFileShowsImagesToVisionModels() = runBlocking {
        writePng(File(project, "pic.png"))
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "看看图"))
        awaitStatus(e, conv, MsgStatus.DONE)
        assertTrue("看不了图片" in lastToolTexts(), lastToolTexts().take(400))

        e.call(Command.SaveProvider(e.state.provider("p1")!!.copy(models = listOf(ModelInfo("code-model", vision = true)))))
        val n = sendTask(e, conv, "再看看图")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n)
        val sent = requests.last()["messages"].toString()
        assertTrue("read_file 读到的图片" in sent && "data:image" in sent, sent.takeLast(400))
    }

    /** 批准命令时选「同样开头不再问」：同样开头的直接跑；拼起来的命令照样问。 */
    @Test
    fun rememberedCommandPrefixRunsWithoutAsking() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "跑命令 Write-Output 第一次"))
        val ask = awaitWaiting(e, conv, "run")
        assertEquals("Write-Output", ask.prefix)
        assertTrue(e.call(Command.CodeDecide(conv, ask.id, true, "command")).ok)
        awaitStatus(e, conv, MsgStatus.DONE)
        assertEquals(listOf("Write-Output"), e.state.conversation(conv)!!.code!!.allowedCommands)

        val n = sendTask(e, conv, "跑命令 Write-Output 第二次")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n)
        val step = aiOf(e, conv)!!.tools.single { it.kind == "run" }
        assertEquals("", step.approval, "同样开头的不再问")
        assertTrue("第二次" in step.output, step.output)

        // 拼起来的：照样要问
        sendTask(e, conv, "跑命令 Write-Output a; Remove-Item x")
        val again = awaitWaiting(e, conv, "run")
        assertEquals("", again.prefix, "拼起来的命令不给「不再问」")
        e.call(Command.CodeStop(conv))
        Unit
    }

    @Test
    fun planModeHasNoWriteTools() = runBlocking {
        val e = engine()
        val conv = codeConv(e, CodeMode.PLAN)
        e.call(Command.CodeSend(conv, "计划档也想写文件"))
        awaitStatus(e, conv, MsgStatus.DONE)

        // 计划档压根不提供写文件 / 改文件的工具
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.none { req -> toolNames(req).any { it == "write_file" || it == "edit_file" } },
            "计划档不该把写文件的工具发给模型")
        // 模型硬要调，程序也不执行
        assertFalse(File(project, "d.txt").exists(), "计划档不能建出文件")
        val ai = assertNotNull(aiOf(e, conv))
        val step = ai.tools.single { it.kind == "write" }
        assertEquals("denied", step.approval)
        assertTrue("计划档" in lastToolTexts(), "模型要收到「计划档没执行」")
    }

    @Test
    fun editFileReportsBadOldText() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        val a = File(project, "a.txt").readText()
        val dup = File(project, "dup.txt").readText()

        val n1 = sendTask(e, conv, "改不存在的文字")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n1)
        assertEquals(a, File(project, "a.txt").readText(), "没找到就不能动")
        assertTrue("没有这段文字" in lastToolTexts(), lastToolTexts())

        val n2 = sendTask(e, conv, "改重复的文字")
        awaitStatus(e, conv, MsgStatus.DONE, minAi = n2)
        assertEquals(dup, File(project, "dup.txt").readText(), "不唯一就不能动")
        assertTrue("出现了 2 次" in lastToolTexts(), lastToolTexts())
    }

    @Test
    fun stopWhileCommandRuns() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "跑个长命令"))
        // 跑命令永远要批准
        val step = awaitWaiting(e, conv, "run")
        assertEquals("run", step.kind)
        assertTrue("Start-Sleep" in step.input, step.input)
        assertTrue(e.call(Command.CodeDecide(conv, step.id, true)).ok)
        delay(600) // 让它真的跑起来
        val t0 = System.currentTimeMillis()
        e.call(Command.CodeStop(conv))
        awaitStatus(e, conv, MsgStatus.STOPPED, timeoutMs = 15_000)
        val took = System.currentTimeMillis() - t0
        assertTrue(took < 15_000, "停下要快，不能等命令自己跑满 30 秒（用了 ${took}ms）")
    }

    @Test
    fun reviewRejectAndRevertTask() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        val aBefore = File(project, "a.txt").readText()
        e.call(Command.CodeSend(conv, "改两处"))
        assertTrue(e.call(Command.CodeDecide(conv, awaitWaiting(e, conv, "edit").id, true)).ok)
        assertTrue(e.call(Command.CodeDecide(conv, awaitWaiting(e, conv, "write").id, true)).ok)
        awaitStatus(e, conv, MsgStatus.DONE)

        val ai = assertNotNull(aiOf(e, conv))
        assertTrue(File(project, "b.txt").exists(), "新建的文件要在")
        val editChange = assertNotNull(ai.tools.first { it.kind == "edit" }.change)
        assertTrue(ai.tools.first { it.kind == "write" }.change!!.created, "b.txt 是新建的")

        // 拒绝第一处：a.txt 恢复成改之前
        assertTrue(e.call(Command.CodeReview(conv, editChange.id, false)).ok)
        assertEquals(aBefore, File(project, "a.txt").readText())
        assertEquals("rejected", aiOf(e, conv)!!.tools.first { it.id == editChange.id }.change!!.review)

        // 撤销整条回答：b.txt（新建的）删掉
        assertTrue(e.call(Command.CodeRevertTask(conv, ai.id)).ok)
        assertFalse(File(project, "b.txt").exists(), "撤销要把新建的文件删掉")
        assertTrue(aiOf(e, conv)!!.tools.all { it.change == null || it.change.review == "rejected" })
    }

    @Test
    fun diffHasHunksAndCounts() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "把所有 greeting 改成 hello"))
        assertTrue(e.call(Command.CodeDecide(conv, awaitWaiting(e, conv, "edit").id, true)).ok)
        awaitStatus(e, conv, MsgStatus.DONE)

        val change = assertNotNull(aiOf(e, conv)).tools.first { it.change != null }.change!!
        val r = e.call(Command.CodeDiff(conv, change.id))
        assertTrue(r.ok, r.message)
        assertTrue("@@ " in r.data, r.data)
        assertTrue("-greeting world" in r.data, r.data)
        assertTrue("+hello world" in r.data, r.data)
        assertTrue("--- a/a.txt" in r.data && "+++ b/a.txt" in r.data, r.data)
    }

    @Test
    fun stopsAtMaxSteps() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "一直读文件，别停"))
        awaitStatus(e, conv, MsgStatus.DONE, timeoutMs = 60_000)
        val ai = assertNotNull(aiOf(e, conv))
        // 到上限要停下来告诉用户，不是悄悄结束（docs/Code栏设计说明.md 第 3 节）
        assertTrue("（到了最多 10 步，停下了）" in ai.content, ai.content)
        assertEquals(10, ai.tools.count { it.kind == "read" }, "一步不多一步不少，正好走满上限")
    }

    @Test
    fun secondTaskQueuesBehindRunningOne() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "排队测试：第一条"))
        await { aiCount(e, conv) == 1 }
        // 第一条还在跑的时候再发一条：用户的话先进去，回答排队等
        assertTrue(e.call(Command.CodeSend(conv, "排队测试：第二条")).ok)
        delay(250)
        assertEquals(1, aiCount(e, conv), "同一个会话同时只跑一个循环")
        assertEquals("排队测试：第二条", listOf(e, conv).last().content, "第二条用户消息要先显示出来")
        // 第一条做完，第二条才接着开始
        await { aiCount(e, conv) == 2 }
        awaitStatus(e, conv, MsgStatus.DONE, minAi = 2)
        val ai = listOf(e, conv).filter { it.role == Role.AI && it.senderId == CODE_ID }
        assertEquals("第一条做完了。", ai[0].content.trim())
        assertEquals("第二条做完了。", ai[1].content.trim())
        assertTrue(ai[0].createdAt <= ai[1].createdAt)
    }

    @Test
    fun ledgerTaggedCodeAndNoRecorder() = runBlocking {
        val e = engine()
        val conv = codeConv(e)
        e.call(Command.CodeSend(conv, "把所有 greeting 改成 hello"))
        assertTrue(e.call(Command.CodeDecide(conv, awaitWaiting(e, conv, "edit").id, true)).ok)
        awaitStatus(e, conv, MsgStatus.DONE)
        delay(200) // 记账是异步写的

        // 账本里这次调用记成 kind = "code"
        val usageDir = File(dir, "usage")
        val text = usageDir.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.joinToString("\n") { it.readText() }
        assertTrue("\"kind\":\"code\"" in text, text)
        // 记录员的后台活没被叫到：每一轮请求都是 Code 的说明，没有记录员那一套
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all {
            (it["messages"]!!.jsonArray.first().jsonObject["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                .startsWith("你在帮用户改一个项目里的代码")
        }, "Code 会话不能触发压缩 / 挑记忆 / 点名 / 立场这些后台活")
    }

    // ———————————————— 手机经中转发来的 Code 指令 ————————————————

    @Test
    fun phoneCodeCommandsAreBlockedByDefault() = runBlocking {
        val mqttPort = freePort()
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(Properties().apply {
            setProperty("port", mqttPort.toString())
            setProperty("host", "127.0.0.1")
            setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true")
            setProperty("persistence_enabled", "false")
            setProperty("netty.mqtt.message_size", (1024 * 1024).toString())
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val pcDir = File(dir, "pc").also { it.mkdirs() }
        val pc = Engine(Storage(pcDir))
        pc.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        pc.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "${base()}/v1", "sk-test", listOf(ModelInfo("code-model")))))
        pc.call(Command.SaveSettings(pc.state.settings.copy(
            relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"),
            code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10, allowPhone = false),
        )))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            val code = PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)
            val phoneEngine = Engine(Storage(File(dir, "phone")), isPhone = true)
            val hub = Hub(phoneEngine, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            assertTrue(hub.pair(code).isSuccess)
            val remote = assertNotNull(hub.remote.value)
            withTimeout(20_000) { while (remote.conn.value !is ConnState.Connected) delay(100) }

            // 电脑上没打开开关：写操作的指令一律被拦下
            val blocked = remote.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
            assertFalse(blocked.ok, blocked.message)
            assertTrue("没打开「允许手机用 Code 栏」" in blocked.message, blocked.message)
            val send = remote.call(Command.CodeSend("随便一个会话", "改点东西"))
            assertFalse(send.ok)
            assertTrue("没打开「允许手机用 Code 栏」" in send.message, send.message)
            // 看差异是只读的，不拦（走到底就是「找不到这处改动」）
            val diff = remote.call(Command.CodeDiff("随便一个会话", "没有这个改动"))
            assertFalse(diff.ok)
            assertTrue("找不到这处改动" in diff.message, diff.message)

            // 手机自己发保存设置想打开开关、换外壳：Code 栏那几项保留电脑上的（10-13 审出的漏洞，PhoneGate）
            val forged = remote.call(Command.SaveSettings(pc.state.settings.copy(code = CodeSettings(shell = "C:\\evil.exe", allowPhone = true))))
            assertTrue(forged.ok, forged.message)
            assertFalse(pc.state.settings.code.allowPhone, "手机不能自己打开「允许手机用 Code 栏」")
            assertEquals("powershell", pc.state.settings.code.shell)
            assertFalse(remote.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).ok)

            // 电脑上打开开关：电脑上建过会话的文件夹，手机就能建 Code 会话了（标成手机发起）
            pc.call(Command.SaveSettings(pc.state.settings.copy(code = pc.state.settings.code.copy(allowPhone = true))))
            assertFalse(remote.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).ok, "电脑上还没建过这个文件夹的会话")
            assertTrue(pc.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).ok)
            val ok = remote.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
            assertTrue(ok.ok, ok.message)
            assertEquals(project.absolutePath, pc.state.conversation(ok.data)?.code?.folder)
            assertEquals(true, pc.state.conversation(ok.data)?.code?.fromPhone)
        } finally {
            relay.stop()
            broker.stopServer()
        }
    }

    private fun toolNames(req: JsonObject): List<String> =
        (req["tools"] as? JsonArray)?.mapNotNull {
            it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
        }.orEmpty()

    private fun assertContentEqualsString(expected: ByteArray, actual: ByteArray, message: String) {
        assertTrue(expected.contentEquals(actual), message)
    }
}
