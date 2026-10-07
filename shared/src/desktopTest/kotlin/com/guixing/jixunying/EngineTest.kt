package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.engine.DocExtract
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.LanServer
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SearchEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 用一个假的 OpenAI 兼容服务器把引擎跑一遍。 */
class EngineTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private lateinit var fake: io.ktor.server.engine.EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun systemOf(req: JsonObject) = req["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
    private fun memberName(req: JsonObject) = Regex("你是「(.+?)」").find(systemOf(req))!!.groupValues[1]

    @BeforeTest
    fun setUp() {
        port = freePort()
        dir = kotlin.io.path.createTempDirectory("jxy-test").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                get("/v1/models") { call.respondText("""{"data":[{"id":"fake-chat"},{"id":"fake-vl"}]}""", ContentType.Application.Json) }
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    requests += req
                    if (req.containsKey("temperature")) {
                        call.respondText("""{"error":{"message":"invalid temperature: only 1 is allowed"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val msgs = req["messages"]!!.jsonArray
                    val last = msgs.last().jsonObject
                    val lastText = (last["content"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
                    val name = memberName(req)
                    val hasTools = req.containsKey("tools")
                    val sawTool = msgs.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        if (hasTools && lastText.contains("天气") && !sawTool) {
                            chunk("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}""")
                            chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"北京天气\"}"}}]}""")
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        } else {
                            chunk("""{"reasoning_content":"想一想"}""")
                            val text = when {
                                sawTool -> "搜索失败了，我没法确认天气。"
                                name == "甲" && lastText.contains("请乙核实") -> "我是甲。@乙 你核实一下"
                                else -> "我是$name。"
                            }
                            text.chunked(3).forEach { chunk(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                                JsonObject(mapOf("content" to kotlinx.serialization.json.JsonPrimitive(it))))) }
                            write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n")
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    @AfterTest
    fun tearDown() {
        fake.stop(100, 200)
        dir.deleteRecursively()
    }

    private suspend fun engineWithMembers(vararg names: String): Pair<Engine, List<Member>> {
        val e = Engine(Storage(dir))
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("fake-chat")))))
        val members = names.mapIndexed { i, n -> Member("m$i", n, providerId = "p1", modelId = "fake-chat", bio = "$n 的定位") }
        members.forEach { e.call(Command.SaveMember(it)) }
        // 测试里不联网：搜索引擎设成没 Key 的 Tavily，必然失败，验证失败路径
        val s = e.state.settings
        e.call(Command.SaveSettings(s.copy(search = s.search.copy(engine = SearchEngine.TAVILY), server = s.server.copy(enabled = false))))
        return e to members
    }

    private suspend fun waitIdle(e: Engine, convId: String, minAi: Int) {
        withTimeout(15_000) {
            while (true) {
                val list = e.store.messages.value[convId].orEmpty()
                val ai = list.filter { it.role == Role.AI }
                if (ai.size >= minAi && ai.none { it.status == MsgStatus.STREAMING }) break
                delay(50)
            }
        }
        delay(200)
    }

    @Test
    fun singleChatKnowsWhoItIs() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(conv, "你是谁"))
        waitIdle(e, conv, 1)
        val ai = e.store.messages.value[conv]!!.last()
        assertEquals(MsgStatus.DONE, ai.status, ai.error)
        assertEquals("我是小智。", ai.content)
        assertEquals("想一想", ai.reasoning)
        val sys = systemOf(requests.last())
        assertTrue("fake-chat（假服务商）" in sys, sys)
        assertTrue("一对一" in sys)
        assertTrue("不能联网" in sys)
        assertEquals("你是谁", e.state.conversation(conv)!!.title)
        // 存盘后重新加载
        val e2 = Engine(Storage(dir))
        e2.call(Command.LoadMessages(conv))
        assertEquals(2, e2.store.messages.value[conv]!!.size)
    }

    @Test
    fun groupIndependentAnswersDoNotSeeEachOther() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙")
        val conv = e.call(Command.CreateConversation(ms.map { it.id })).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false, replyMode = ReplyMode.INDEPENDENT)))
        e.call(Command.SendMessage(conv, "大家好"))
        waitIdle(e, conv, 2)
        val ai = e.store.messages.value[conv]!!.filter { it.role == Role.AI }
        assertEquals(setOf("我是甲。", "我是乙。"), ai.map { it.content }.toSet())
        requests.forEach { r ->
            val all = r["messages"].toString()
            assertFalse("我是甲。" in all || "我是乙。" in all, "独立作答不该看到别人的本轮回答")
            assertTrue("独立作答" in systemOf(r))
        }
        // 群聊系统提示里介绍了其他成员
        val sysOfJia = requests.map(::systemOf).first { "你是「甲」" in it }
        assertTrue("乙：fake-chat" in sysOfJia, sysOfJia)
    }

    @Test
    fun mentionChainAndUserMention() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙", "丙")
        val conv = e.call(Command.CreateConversation(ms.map { it.id })).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(conv, "@甲 请乙核实一下这个说法"))
        waitIdle(e, conv, 2)
        val ai = e.store.messages.value[conv]!!.filter { it.role == Role.AI }
        assertEquals(listOf("m0", "m1"), ai.map { it.senderId }, "只有甲回答，甲 @ 了乙，乙接着说；丙不说话")
        val lastReq = requests.last()
        assertTrue("「甲」在发言里 @ 了你" in systemOf(lastReq))
        assertTrue("【甲】我是甲。@乙 你核实一下" in lastReq["messages"].toString())
    }

    @Test
    fun toolCallLoopAndBadParamRetry() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        e.call(Command.SaveMember(ms[0].copy(temperature = 0.7)))
        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.SendMessage(conv, "今天北京天气怎么样"))
        waitIdle(e, conv, 1)
        val ai = e.store.messages.value[conv]!!.last()
        assertEquals(MsgStatus.DONE, ai.status, ai.error)
        assertEquals("搜索失败了，我没法确认天气。", ai.content)
        assertEquals(1, ai.tools.size)
        assertEquals("北京天气", ai.tools[0].input)
        assertFalse(ai.tools[0].ok)
        // 第一次带 temperature 被拒，之后不再带
        assertTrue(requests.first().containsKey("temperature"))
        assertTrue(requests.drop(1).none { it.containsKey("temperature") })
        val toolMsg = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }.first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertTrue("搜索失败" in toolMsg["content"]!!.jsonPrimitive.content)
        assertTrue(requests.last()["messages"]!!.jsonArray.any { "tool_calls" in it.jsonObject })
    }

    @Test
    fun fetchModelsAndDocuments() = runBlocking {
        val (e, _) = engineWithMembers("小智")
        val r = e.call(Command.FetchModels("p1"))
        assertTrue(r.ok, r.message)
        val models = e.state.provider("p1")!!.models.map { it.id }
        assertTrue("fake-vl" in models)
        assertTrue(e.state.provider("p1")!!.models.first { it.id == "fake-vl" }.vision)

        val docx = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("word/document.xml"))
                z.write("<w:document><w:body><w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段 &amp; 结尾</w:t></w:r></w:p></w:body></w:document>".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val (text, _) = DocExtract.extract("报告.docx", docx)
        assertEquals("第一段\n第二段 & 结尾", text)
        val att = e.upload("报告.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx)
        assertNotNull(att)
        assertEquals(text!!.length, att.textChars)
        val (gbk, _) = DocExtract.extract("a.txt", "中文内容".toByteArray(charset("GBK")))
        assertEquals("中文内容", gbk)
    }

    @Test
    fun phonePairsAndChatsOverLan() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        val lanPort = freePort()
        e.call(Command.SaveSettings(e.state.settings.copy(server = e.state.settings.server.copy(enabled = true, port = lanPort, deviceName = "测试电脑"))))
        val server = LanServer(e)
        server.restart()
        try {
            val phone = RemoteBackend()
            val bad = phone.pair("127.0.0.1:$lanPort", "000000", "手机")
            assertFalse(bad.ok)
            val ok = phone.pair("127.0.0.1:$lanPort", e.state.pairingCode, "测试手机")
            assertTrue(ok.ok, ok.message)
            phone.connect("127.0.0.1:$lanPort", ok.token)
            withTimeout(10_000) { while (phone.conn.value !is ConnState.Connected) delay(50) }
            withTimeout(5_000) { while (phone.store.state.value.members.isEmpty()) delay(50) }
            // Key 打码，配对码不外泄
            assertTrue(phone.store.state.value.providers.first().apiKey.contains("••••") || phone.store.state.value.providers.first().apiKey.length <= 4)
            assertEquals("", phone.store.state.value.pairingCode)

            val conv = phone.call(Command.CreateConversation(listOf(ms[0].id))).data
            phone.call(Command.UpdateConversation(phone.store.state.value.conversation(conv)!!.copy(webSearch = false)))
            val att = phone.upload("笔记.txt", "text/plain", "手机上传的文字".toByteArray())
            assertNotNull(att)
            phone.call(Command.SendMessage(conv, "你好", listOf(att.id)))
            withTimeout(15_000) {
                while (phone.store.messages.value[conv].orEmpty().none { it.role == Role.AI && it.status == MsgStatus.DONE }) delay(50)
            }
            assertEquals("我是小智。", phone.store.messages.value[conv]!!.last().content)
            assertTrue("手机上传的文字" in requests.last()["messages"].toString())
            assertEquals("手机上传的文字", String(phone.fileBytes(att.id)!!))
            // 手机改服务商时传回打码的 Key，电脑上的真 Key 不能被覆盖
            phone.call(Command.SaveProvider(phone.store.state.value.providers.first().copy(name = "改名")))
            assertEquals("sk-test", e.state.provider("p1")!!.apiKey)
            assertEquals("改名", e.state.provider("p1")!!.name)
            phone.disconnect()
        } finally {
            server.stop()
        }
    }

    @Suppress("unused")
    private fun JsonArray.texts() = map { it.toString() }
}
