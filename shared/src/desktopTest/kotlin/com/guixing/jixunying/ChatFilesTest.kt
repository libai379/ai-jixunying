package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
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
import kotlin.test.assertTrue

/**
 * 对话页 AI 读电脑上的文件（2026-10-17，用户定「和 Code 一样放开」）：
 * 拖进来的文件夹直接能看；收录范围外的先问用户，「以后不再问」之后不再问；手机发起的这一轮不许读收录范围外的。
 */
class ChatFilesTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File
    private lateinit var outside: File

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-chat-files").toFile()
        outside = File(dir, "别处的资料").apply { mkdirs(); File(this, "说明.md").writeText("OUTSIDE_BODY_7\n") }
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val hasTools = req["tools"] != null
                    if (hasTools) requests += req
                    val task = msgs.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val results = msgs.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(t: String) = chunk(JsonObject(mapOf("content" to JsonPrimitive(t))).toString())
                        fun call(name: String, path: String) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(JsonObject(mapOf("path" to JsonPrimitive(path))).toString())))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("c${results.size}"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        val folder = Regex("- (.+)").find(task)?.groupValues?.get(1)?.trim()
                        fun callArgs(name: String, args: Map<String, String>) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString())))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("c${results.size}"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        val target = Regex("目标：(.+)").find(task)?.groupValues?.get(1)?.trim()
                        when {
                            !hasTools -> say("[]")
                            "写文件" in task && results.isEmpty() && target != null -> callArgs("write_file", mapOf("path" to target, "content" to "NEW_CONTENT\n"))
                            "跑命令" in task && results.isEmpty() && target != null -> callArgs("run_command", mapOf("command" to target))
                            "删掉" in task && results.isEmpty() && target != null -> callArgs("delete_file", mapOf("path" to target))
                            "找文件" in task && results.isEmpty() -> callArgs("find_files", mapOf("pattern" to "*.md", "path" to outside.absolutePath))
                            "看文件夹" in task && results.isEmpty() && folder != null -> call("list_folder", folder)
                            "读别处" in task && results.isEmpty() -> call("read_document", File(outside, "说明.md").absolutePath)
                            else -> say("好的。")
                        }
                    }
                }
            }
        }
        fake.start(wait = false)
    }

    @AfterTest
    fun cleanup() { fake.stop(500, 1_000); dir.deleteRecursively() }

    private suspend fun setup(): Pair<Engine, String> {
        val e = Engine(Storage(File(dir, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m")))))
        e.call(Command.SaveMember(Member("a1", "阿测", providerId = "p1", modelId = "m")))
        val conv = e.call(Command.CreateConversation(listOf("a1"))).data
        return e to conv
    }

    private fun ai(e: Engine, conv: String) = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }
    private suspend fun await(check: () -> Boolean) = withTimeout(30_000) { while (!check()) delay(30) }
    private fun lastTools() = requests.last()["messages"].toString()

    @Test
    fun draggedFolderReadableWithoutAsking() = runBlocking {
        val (e, conv) = setup()
        val folder = File(dir, "拖进来的").apply { mkdirs(); File(this, "a.txt").writeText("x") }
        e.call(Command.SendMessage(conv, "看文件夹", paths = listOf(folder.absolutePath)))
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("a.txt" in lastTools(), lastTools().takeLast(300))
        assertTrue(ai(e, conv)!!.tools.none { it.approval.isNotEmpty() }, "拖进来的不用问")
    }

    /** 2026-10-17「比 Claude 更开放」：电脑上发起的，别处的文件直接读，不问；按文件名也能找。 */
    @Test
    fun outsideReadsWithoutAsking() = runBlocking {
        val (e, conv) = setup()
        e.call(Command.SendMessage(conv, "读别处"))
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("OUTSIDE_BODY_7" in lastTools())
        assertTrue(ai(e, conv)!!.tools.none { it.approval.isNotEmpty() })
        val before = e.store.messages.value[conv].orEmpty().size
        e.call(Command.SendMessage(conv, "找文件"))
        await { e.store.messages.value[conv].orEmpty().size > before + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("说明.md" in lastTools())
    }

    /** 对话栏改文件：别处的先问（带差异），允许后写好、能撤销；「不再问」之后同一个文件夹不问；全部放开都不问；拖进来的文件夹里不问。 */
    @Test
    fun writeAsksThenRevertsAndOpens() = runBlocking {
        val (e, conv) = setup()
        val target = File(outside, "新文件.md")
        e.call(Command.SendMessage(conv, "写文件 目标：${target.absolutePath}"))
        await { ai(e, conv)?.tools?.any { it.approval == "waiting" } == true }
        val ask = ai(e, conv)!!.tools.first { it.approval == "waiting" }
        assertTrue("NEW_CONTENT" in ask.output, "卡片上要有差异")
        assertTrue(!target.exists(), "没批准不能先写")
        assertTrue(e.call(Command.ChatDecide(conv, ask.id, true, "dir")).ok)
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertEquals("NEW_CONTENT\n", target.readText())
        val ch = ai(e, conv)!!.tools.mapNotNull { it.change }.single()
        assertTrue(ch.created)
        assertTrue(e.call(Command.ChatRevert(conv, ch.id)).ok)
        assertTrue(!target.exists(), "撤销新建的 = 删掉")
        // 同一个文件夹已经「不再问」
        var before = e.store.messages.value[conv].orEmpty().size
        e.call(Command.SendMessage(conv, "写文件 目标：${target.absolutePath}"))
        await { e.store.messages.value[conv].orEmpty().size > before + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue(ai(e, conv)!!.tools.none { it.approval.isNotEmpty() })
        // 全部放开：别的文件夹也不问
        val other = File(dir, "另一处").apply { mkdirs() }
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(fileOpen = true)))
        before = e.store.messages.value[conv].orEmpty().size
        e.call(Command.SendMessage(conv, "写文件 目标：${File(other, "b.md").absolutePath}"))
        await { e.store.messages.value[conv].orEmpty().size > before + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue(ai(e, conv)!!.tools.none { it.approval.isNotEmpty() })
        assertEquals("NEW_CONTENT\n", File(other, "b.md").readText())
        // 手机改不了「全部放开」和不用问的文件夹（PhoneGate 保留电脑上的）
        val cur = e.state.conversation(conv)!!
        val viaPhone = com.guixing.jixunying.engine.code.PhoneGate.check(Command.UpdateConversation(cur.copy(fileOpen = false, grantedPaths = emptyList(), title = "改个名")), e.state).command as Command.UpdateConversation
        assertTrue(viaPhone.conversation.fileOpen && viaPhone.conversation.grantedPaths.isNotEmpty() && viaPhone.conversation.title == "改个名")
    }

    /** 2026-10-17 对话栏也能跑命令：默认先问；全部放开不问；手机发起的不能跑。 */
    @Test
    fun runCommandAsksThenOpen() = runBlocking {
        val (e, conv) = setup()
        e.call(Command.SendMessage(conv, "跑命令 目标：Write-Output RUN_OK_1"))
        await { ai(e, conv)?.tools?.any { it.approval == "waiting" } == true }
        val ask = ai(e, conv)!!.tools.first { it.approval == "waiting" }
        assertEquals("run", ask.kind)
        assertTrue(e.call(Command.ChatDecide(conv, ask.id, true)).ok)
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("RUN_OK_1" in lastTools())
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(fileOpen = true)))
        val before = e.store.messages.value[conv].orEmpty().size
        e.call(Command.SendMessage(conv, "跑命令 目标：Write-Output RUN_OK_2"))
        await { e.store.messages.value[conv].orEmpty().size > before + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue(ai(e, conv)!!.tools.none { it.approval.isNotEmpty() }, "全部放开不问")
        assertTrue("RUN_OK_2" in lastTools())
        // 手机发起的：电脑上没开「允许手机用 Code 栏」不能跑
        val viaPhone = com.guixing.jixunying.engine.code.PhoneGate.check(Command.SendMessage(conv, "跑命令 目标：Write-Output RUN_OK_3"), e.state).command
        val b2 = e.store.messages.value[conv].orEmpty().size
        e.call(viaPhone)
        await { e.store.messages.value[conv].orEmpty().size > b2 + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("手机上发起" in lastTools() && "RUN_OK_3\\n" !in lastTools().substringAfterLast("tool_call_id"))
        // 开了就和电脑上一样（2026-10-28，用户：「不要限制」）：这个对话全部放开，手机发起的也不问直接跑
        e.call(Command.SaveSettings(e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true))))
        val viaPhone2 = com.guixing.jixunying.engine.code.PhoneGate.check(Command.SendMessage(conv, "跑命令 目标：Write-Output RUN_OK_4"), e.state).command
        val b3 = e.store.messages.value[conv].orEmpty().size
        e.call(viaPhone2)
        await { e.store.messages.value[conv].orEmpty().size > b3 + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("RUN_OK_4" in lastTools() && "手机上发起" !in lastTools().substringAfterLast("RUN_OK_3"), lastTools().takeLast(400))
    }

    /** 2026-10-17 对话栏能删文件夹：拖进来的文件夹里不问；删之前整个备份，一键放回去。 */
    @Test
    fun deleteFolderAndRestore() = runBlocking {
        val (e, conv) = setup()
        val box = File(dir, "拖进来的盒子").apply { mkdirs() }
        val sub = File(box, "要删的").apply { mkdirs(); File(this, "a.txt").writeText("A"); File(this, "b/c.txt").apply { parentFile.mkdirs(); writeText("C") } }
        e.call(Command.SendMessage(conv, "删掉 目标：${sub.absolutePath}", paths = listOf(box.absolutePath)))
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue(!sub.exists(), "拖进来的文件夹里直接删")
        val ch = ai(e, conv)!!.tools.mapNotNull { it.change }.single()
        assertEquals(2, ch.removed)
        assertTrue(e.call(Command.ChatRevert(conv, ch.id)).ok)
        assertEquals("C", File(sub, "b/c.txt").readText(), "放回去了")
    }

    /** 手机发起的这一轮不许改电脑上的文件。 */
    @Test
    fun phoneTurnCannotWrite() = runBlocking {
        val (e, conv) = setup()
        val target = File(outside, "x.md")
        val viaPhone = com.guixing.jixunying.engine.code.PhoneGate.check(Command.SendMessage(conv, "写文件 目标：${target.absolutePath}"), e.state).command
        e.call(viaPhone)
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue(!target.exists())
        assertTrue("手机上发起" in lastTools())
    }

    @Test
    fun phoneTurnCannotReadOutside() = runBlocking {
        val (e, conv) = setup()
        val viaPhone = com.guixing.jixunying.engine.code.PhoneGate.check(Command.SendMessage(conv, "读别处", paths = listOf("C:\\x")), e.state).command as Command.SendMessage
        assertTrue(viaPhone.fromPhone && viaPhone.paths.isEmpty())
        e.call(viaPhone)
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("手机上发起" in lastTools(), lastTools().takeLast(300))
        assertTrue(ai(e, conv)!!.tools.none { it.approval == "waiting" }, "手机来的不弹批准，直接不读")
    }
}
