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
                        when {
                            !hasTools -> say("[]")
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

    @Test
    fun outsideAsksThenRemembers() = runBlocking {
        val (e, conv) = setup()
        e.call(Command.SendMessage(conv, "读别处"))
        await { ai(e, conv)?.tools?.any { it.approval == "waiting" } == true }
        val step = ai(e, conv)!!.tools.first { it.approval == "waiting" }
        assertEquals(outside.absolutePath.lowercase(), step.scopeDir.lowercase())
        assertTrue(e.call(Command.ChatDecide(conv, step.id, true, "dir")).ok)
        await { ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue("OUTSIDE_BODY_7" in lastTools())
        assertTrue(e.state.conversation(conv)!!.grantedPaths.any { it.equals(outside.absolutePath, ignoreCase = true) })
        // 第二次：不再问
        val before = e.store.messages.value[conv].orEmpty().size
        e.call(Command.SendMessage(conv, "再读别处"))
        await { e.store.messages.value[conv].orEmpty().size > before + 1 && ai(e, conv)?.status == MsgStatus.DONE }
        assertTrue(ai(e, conv)!!.tools.none { it.approval == "waiting" })
        assertTrue("OUTSIDE_BODY_7" in lastTools())
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
