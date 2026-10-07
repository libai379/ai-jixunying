package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.DocExtract
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ImageGenSettings
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SearchEngine
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 用一个假的模型服务器把引擎跑一遍；联机用进程内的 MQTT 服务器（Moquette）代替公共中转。 */
class EngineTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val imageRequests = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    /** 1×1 的 PNG。 */
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    private val pngB64 = Base64.getEncoder().encodeToString(png)

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
                    val model = req["model"]!!.jsonPrimitive.content
                    val msgs = req["messages"]!!.jsonArray
                    val last = msgs.last().jsonObject
                    val lastText = (last["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val name = memberName(req)
                    val tools = req["tools"] as? JsonArray
                    val hasFnSearch = tools?.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "web_search" } == true
                    val hasDraw = tools?.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "generate_image" } == true
                    val sawTool = msgs.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
                    val drew = msgs.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" && "图片已生成" in it.jsonObject["content"].toString() }
                    // 只看用户最新的那一句（连续几句会合并成一条）
                    val latest = lastText.substringAfterLast("【")
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String, extra: String = "") { write("data: {$extra\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(text: String) = text.chunked(3).forEach {
                            chunk(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("content" to JsonPrimitive(it)))))
                        }
                        when {
                            // Kimi 官方搜索：先回一个 $web_search 调用，客户端原样交回参数后再回答
                            model == "kimi-fake" && !sawTool -> {
                                chunk("""{"tool_calls":[{"index":0,"id":"ws_1","type":"builtin_function","function":{"name":"${'$'}web_search","arguments":"{\"search_result\":{\"search_id\":\"abc\"}}"}}]}""")
                                write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            }
                            model == "kimi-fake" -> say("Kimi 搜到了。")
                            // 智谱官方搜索：出处放在 web_search 字段
                            model == "zhipu-fake" -> {
                                chunk("""{"content":""}""", """"web_search":[{"title":"智谱来源","link":"https://zhipu.example/a","content":"摘要"}],""")
                                say("智谱答[ref_1]。")
                            }
                            // 千问官方搜索：出处在 search_info.search_results
                            model == "qwen-fake" -> {
                                chunk("""{"content":""}""", """"search_info":{"search_results":[{"index":2,"title":"第二","url":"https://q.example/2"},{"index":1,"title":"第一","url":"https://q.example/1"}]},""")
                                say("千问答[1]。")
                            }
                            // 聊天里要图：先调 generate_image，拿到结果再说话
                            hasDraw && latest.contains("画") && !sawTool -> {
                                chunk("""{"tool_calls":[{"index":0,"id":"img_1","type":"function","function":{"name":"generate_image","arguments":"{\"prompt\":\"一只慢吞吞的猫\"}"}}]}""")
                                write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            }
                            drew -> say("画好了。")
                            hasFnSearch && lastText.contains("天气") && !sawTool -> {
                                chunk("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":""}}]}""")
                                chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"北京天气\"}"}}]}""")
                                write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            }
                            else -> {
                                chunk("""{"reasoning_content":"想一想"}""")
                                say(when {
                                    sawTool -> "搜索失败了，我没法确认天气。"
                                    name == "甲" && lastText.contains("请乙核实") -> "我是甲。@乙 你核实一下"
                                    else -> "我是$name。"
                                })
                                write("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n")
                            }
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
                // —— 画图 ——
                post("/v1/images/generations") {
                    val body = call.receiveText()
                    imageRequests += "openai:" + body
                    // 「慢」字的图故意画 2 秒，用来测画图时还能接着聊
                    if ("慢" in body) delay(2_000)
                    call.respondText("""{"data":[{"b64_json":"$pngB64"}]}""", ContentType.Application.Json)
                }
                get("/img/1.png") { call.respondBytes(png, ContentType.Image.PNG) }
                // 画不出来的服务商（比如 Key 没开通画图），用来测自动换下一个
                post("/bad/v1/images/generations") {
                    imageRequests += "bad:" + call.receiveText()
                    call.respondText("""{"error":{"message":"model not enabled for this key"}}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                post("/api/v1/services/aigc/multimodal-generation/generation") {
                    imageRequests += "dashscope:" + call.request.headers["X-DashScope-Async"] + ":" + call.receiveText()
                    call.respondText("""{"output":{"task_id":"t1","task_status":"PENDING"}}""", ContentType.Application.Json)
                }
                get("/api/v1/tasks/t1") {
                    call.respondText("""{"output":{"task_id":"t1","task_status":"SUCCEEDED","choices":[{"message":{"content":[{"image":"http://127.0.0.1:$port/img/1.png"}]}}]}}""", ContentType.Application.Json)
                }
                post("/mm/v1/image_generation") {
                    imageRequests += "minimax:" + call.receiveText()
                    call.respondText("""{"data":{"image_base64":["$pngB64"]},"base_resp":{"status_code":0,"status_msg":"success"}}""", ContentType.Application.Json)
                }
                post("/kling/v1/images/generations") {
                    imageRequests += "kling:" + call.request.headers["Authorization"] + ":" + call.receiveText()
                    call.respondText("""{"code":0,"data":{"task_id":"k1","task_status":"submitted"}}""", ContentType.Application.Json)
                }
                get("/kling/v1/images/generations/k1") {
                    call.respondText("""{"code":0,"data":{"task_id":"k1","task_status":"succeed","task_result":{"images":[{"url":"http://127.0.0.1:$port/img/1.png"}]}}}""", ContentType.Application.Json)
                }
                post("/ms/v1/images/generations") {
                    imageRequests += "modelscope:" + call.request.headers["X-ModelScope-Async-Mode"] + ":" + call.receiveText()
                    call.respondText("""{"task_id":"m1"}""", ContentType.Application.Json)
                }
                get("/ms/v1/tasks/m1") {
                    call.respondText("""{"task_status":"SUCCEED","output_images":["http://127.0.0.1:$port/img/1.png"]}""", ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
    }

    @AfterTest
    fun tearDown() {
        fake.stop(100, 200)
        dir.deleteRecursively()
    }

    private fun base() = "http://127.0.0.1:$port"

    private suspend fun engineWithMembers(vararg names: String, root: File = dir): Pair<Engine, List<Member>> {
        val e = Engine(Storage(root))
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "${base()}/v1", "sk-test", listOf(ModelInfo("fake-chat")))))
        val members = names.mapIndexed { i, n -> Member("m$i", n, providerId = "p1", modelId = "fake-chat", bio = "$n 的定位") }
        members.forEach { e.call(Command.SaveMember(it)) }
        // 测试里不联网：搜索引擎设成没 Key 的 Tavily，必然失败，验证失败路径；联机先关掉
        val s = e.state.settings
        e.call(Command.SaveSettings(s.copy(search = s.search.copy(engine = SearchEngine.TAVILY), relay = s.relay.copy(enabled = false))))
        return e to members
    }

    private suspend fun waitIdle(e: com.guixing.jixunying.client.Backend, convId: String, minAi: Int) {
        withTimeout(20_000) {
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
        assertTrue(requests.first().containsKey("temperature"))
        assertTrue(requests.drop(1).none { it.containsKey("temperature") })
        val toolMsg = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }.first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertTrue("搜索失败" in toolMsg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun platformNativeSearch() = runBlocking {
        val (e, _) = engineWithMembers("小智")
        // 三个「平台」都指向假服务器，靠预设编号识别是哪家
        e.call(Command.SaveProvider(ProviderConfig("pk", "moonshot", "Kimi", "${base()}/v1", "k", listOf(ModelInfo("kimi-fake")))))
        e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "${base()}/v1", "k", listOf(ModelInfo("zhipu-fake")))))
        e.call(Command.SaveProvider(ProviderConfig("pq", "qianwen", "千问", "${base()}/v1", "k", listOf(ModelInfo("qwen-fake")))))
        e.call(Command.SaveMember(Member("mk", "月", providerId = "pk", modelId = "kimi-fake")))
        e.call(Command.SaveMember(Member("mz", "谱", providerId = "pz", modelId = "zhipu-fake")))
        e.call(Command.SaveMember(Member("mq", "问", providerId = "pq", modelId = "qwen-fake")))

        suspend fun ask(memberId: String): com.guixing.jixunying.model.Message {
            val conv = e.call(Command.CreateConversation(listOf(memberId))).data
            e.call(Command.SendMessage(conv, "最新消息"))
            waitIdle(e, conv, 1)
            return e.store.messages.value[conv]!!.last()
        }

        val kimi = ask("mk")
        assertEquals("Kimi 搜到了。", kimi.content, kimi.error)
        val kimiReqs = requests.filter { it["model"]!!.jsonPrimitive.content == "kimi-fake" }
        assertTrue(kimiReqs.first()["tools"].toString().contains("builtin_function"))
        assertFalse(kimiReqs.first()["tools"].toString().contains("\"web_search\""), "有官方搜索就不再给自己的 web_search")
        val echoed = kimiReqs.last()["messages"]!!.jsonArray.map { it.jsonObject }.first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("\$web_search", echoed["name"]!!.jsonPrimitive.content)
        assertEquals("{\"search_result\":{\"search_id\":\"abc\"}}", echoed["content"]!!.jsonPrimitive.content)
        assertTrue(kimi.tools.any { it.input == "Kimi 官方联网搜索" })

        val zhipu = ask("mz")
        val zReq = requests.last { it["model"]!!.jsonPrimitive.content == "zhipu-fake" }
        assertTrue(zReq["tools"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" })
        assertEquals("https://zhipu.example/a", zhipu.tools.single().sources.single().url)
        assertEquals("智谱答[ref_1]。", zhipu.content)

        val qwen = ask("mq")
        val qReq = requests.last { it["model"]!!.jsonPrimitive.content == "qwen-fake" }
        assertEquals("true", qReq["enable_search"]!!.jsonPrimitive.content)
        assertEquals(listOf("https://q.example/1", "https://q.example/2"), qwen.tools.single().sources.map { it.url }, "按 index 排序")

        // 改成「全部用搜索引擎」后，Kimi 也走自己的 web_search
        e.call(Command.SaveSettings(e.state.settings.copy(search = e.state.settings.search.copy(mode = com.guixing.jixunying.model.SearchMode.ENGINE_ONLY))))
        requests.clear()
        ask("mk")
        assertTrue(requests.first()["tools"].toString().contains("\"web_search\""))
        assertFalse(requests.first()["tools"].toString().contains("builtin_function"))
    }

    @Test
    fun imageProtocols() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        suspend fun draw(presetId: String, url: String, model: String, key: String = "k"): com.guixing.jixunying.model.Message {
            e.call(Command.SaveProvider(ProviderConfig("img-$presetId", presetId, presetId, url, key, listOf(ModelInfo(model, imageGen = true, tools = false)))))
            e.call(Command.SaveSettings(e.state.settings.copy(imageGen = ImageGenSettings("img-$presetId", model, "1024x1024"))))
            val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
            e.call(Command.SendMessage(conv, "一只猫", drawImage = true))
            waitIdle(e, conv, 1)
            return e.store.messages.value[conv]!!.last()
        }
        for ((preset, url, model) in listOf(
            Triple("zhipu", "${base()}/v1", "cogview-3-flash"),
            Triple("qianwen", "${base()}/compatible-mode/v1", "qwen-image-3.0"),
            Triple("minimax", "${base()}/mm/v1", "image-01"),
            Triple("kling", "${base()}/kling", "kling-v3"),
            Triple("modelscope", "${base()}/ms/v1", "Qwen/Qwen-Image"),
        )) {
            val m = draw(preset, url, model, key = if (preset == "kling") "ak123:sk456" else "k")
            assertEquals(MsgStatus.DONE, m.status, "$preset：${m.error}")
            val att = m.attachments.single()
            assertContentEquals(png, e.fileBytes(att.id), preset)
        }
        assertTrue(imageRequests.any { it.startsWith("dashscope:enable:") && "1024*1024" in it })
        assertTrue(imageRequests.any { it.startsWith("minimax:") && "\"aspect_ratio\":\"1:1\"" in it })
        assertTrue(imageRequests.any { it.startsWith("kling:Bearer ey") }, "AK:SK 要签成 JWT")
        assertTrue(imageRequests.any { it.startsWith("modelscope:true:") })
    }

    /**
     * 没在 设置→画图 指定模型时自动挑：免费的智谱 cogview-3-flash 优先（服务商模型列表里没有也按预设推断），
     * 画不出来就换下一个（MiniMax 的 image-01）；刚失败过的排到后面，下次直接用能用的。
     */
    @Test
    fun imageAutoPickAndFallback() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "${base()}/bad/v1", "k", listOf(ModelInfo("glm-fake")))))
        e.call(Command.SaveProvider(ProviderConfig("pm", "minimax", "MiniMax", "${base()}/mm/v1", "k", listOf(ModelInfo("MiniMax-M3")))))
        val picks = com.guixing.jixunying.model.ImagePick.resolve(e.state)
        assertEquals(listOf("cogview-3-flash", "cogview-4-250304", "image-01"), picks.map { it.modelId })
        assertTrue(picks.first().free)

        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.SendMessage(conv, "一只猫", drawImage = true))
        waitIdle(e, conv, 1)
        val m = e.store.messages.value[conv]!!.last()
        assertEquals(MsgStatus.DONE, m.status, m.error)
        assertEquals("image-01（MiniMax）", m.modelLabel)
        assertEquals(listOf("bad", "bad", "minimax"), imageRequests.map { it.substringBefore(':') }, "cogview-3-flash、cogview-4 都失败后换 MiniMax")

        imageRequests.clear()
        val r = e.call(Command.TestImage())
        assertTrue(r.ok, r.message)
        assertEquals(listOf("minimax"), imageRequests.map { it.substringBefore(':') }, "刚失败过的排到后面")

        // 指定了模型就只用它，失败了直接报错，不偷偷换
        e.call(Command.SaveSettings(e.state.settings.copy(imageGen = ImageGenSettings("pz", "cogview-3-flash", "1024x1024"))))
        val r2 = e.call(Command.TestImage())
        assertFalse(r2.ok)
        assertTrue("HTTP 403" in r2.message, r2.message)
    }

    /** @ 了不在这个对话里的成员：把他拉进来，由他回答（以前会悄悄换成对话里的别人答）。 */
    @Test
    fun mentionOutsiderJoinsConversation() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙")
        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(conv, "@乙 你好"))
        waitIdle(e, conv, 1)
        assertEquals(listOf("m0", "m1"), e.state.conversation(conv)!!.memberIds)
        val ai = e.store.messages.value[conv]!!.filter { it.role == Role.AI }
        assertEquals(listOf("m1"), ai.map { it.senderId }, "只有被 @ 的乙回答")
        assertEquals("我是乙。", ai.single().content)
    }

    /** 边聊边画：聊天里让 AI 画图，图在 AI 的回复里；图还没画完时接着发下一句，也能马上回答。 */
    @Test
    fun chatAndDrawTogether() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        e.call(Command.SaveProvider(ProviderConfig("pimg", "zhipu", "画图", "${base()}/v1", "k", listOf(ModelInfo("cogview-3-flash", imageGen = true, tools = false)))))
        e.call(Command.SaveSettings(e.state.settings.copy(imageGen = ImageGenSettings("pimg", "cogview-3-flash", "1024x1024"))))
        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(conv, "帮我画一只猫"))
        delay(400)
        e.call(Command.SendMessage(conv, "顺便问一下你是谁"))
        // 第二句不用等画完：画图要 2 秒，第二句应该先答完
        withTimeout(1_500) {
            while (e.store.messages.value[conv].orEmpty().none { it.role == Role.AI && it.content == "我是小智。" && it.status == MsgStatus.DONE }) delay(30)
        }
        waitIdle(e, conv, 2)
        val ai = e.store.messages.value[conv]!!.filter { it.role == Role.AI }
        val drawn = ai.single { it.attachments.isNotEmpty() }
        assertEquals(MsgStatus.DONE, drawn.status, drawn.error)
        assertEquals(com.guixing.jixunying.model.AttachmentKind.GENERATED_IMAGE, drawn.attachments.single().kind)
        assertEquals("画好了。", drawn.content)
        assertContentEquals(png, e.fileBytes(drawn.attachments.single().id))
        assertTrue(requests.any { "你可以画图" in systemOf(it) })
        // 第二句的上下文里不该有第一句还没说完的半截回答
        val second = requests.first { r -> "顺便问一下你是谁" in r["messages"].toString() }
        assertFalse("画好了" in second["messages"].toString())
    }

    @Test
    fun documents() = runBlocking {
        val (e, _) = engineWithMembers("小智")
        val r = e.call(Command.FetchModels("p1"))
        assertTrue(r.ok, r.message)
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

    /** 手机隔着中转（这里用本地 MQTT 服务器模拟）配对、遥控电脑、传大文件、导入配置、被取消配对。 */
    @Test
    fun phoneLinksThroughRelay() = runBlocking {
        val mqttPort = freePort()
        val broker = io.moquette.broker.Server()
        val props = Properties().apply {
            setProperty("port", mqttPort.toString())
            setProperty("host", "127.0.0.1")
            setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true")
            setProperty("persistence_enabled", "false")
            setProperty("netty.mqtt.message_size", (1024 * 1024).toString())
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }
        broker.startServer(io.moquette.broker.config.MemoryConfig(props))
        val pcDir = File(dir, "pc").also { it.mkdirs() }
        val (pc, ms) = engineWithMembers("小智", root = pcDir)
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            val code = PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)
            // 配对码能编码成文字再解回来（复制粘贴用）
            assertEquals(code, PairingCode.decode(code.encode()))
            val wrong = pairWithHost(code.copy(p = Base64.getEncoder().encodeToString(ByteArray(16))), "坏手机")
            assertTrue(wrong.isFailure, "密钥不对不能配对")

            val phoneEngine = Engine(Storage(File(dir, "phone")), isPhone = true)
            val hub = Hub(phoneEngine, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            val paired = hub.pair(code)
            assertTrue(paired.isSuccess, paired.exceptionOrNull()?.message)
            assertEquals(1, pc.state.devices.size)
            assertTrue(pc.state.pairingSecret != code.p, "配对后换新的二维码")
            assertTrue(pairWithHost(code, "又一台").isFailure, "旧二维码不能再用")

            val remote = hub.remote.value!!
            withTimeout(20_000) { while (remote.conn.value !is ConnState.Connected) delay(100) }
            withTimeout(10_000) { while (remote.store.state.value.members.isEmpty()) delay(50) }
            val masked = remote.store.state.value.providers.first().apiKey
            assertTrue(masked.contains("••••") || masked.length <= 4, "手机看到的 Key 要打码")
            assertEquals("", remote.store.state.value.pairingSecret)

            // 遥控电脑聊天
            val conv = remote.call(Command.CreateConversation(listOf(ms[0].id))).data
            remote.call(Command.UpdateConversation(remote.store.state.value.conversation(conv)!!.copy(webSearch = false)))
            val att = remote.upload("笔记.txt", "text/plain", "手机上传的文字".toByteArray())
            assertNotNull(att)
            remote.call(Command.SendMessage(conv, "你好", listOf(att.id)))
            withTimeout(20_000) {
                while (remote.store.messages.value[conv].orEmpty().none { it.role == Role.AI && it.status == MsgStatus.DONE }) delay(50)
            }
            assertEquals("我是小智。", remote.store.messages.value[conv]!!.last().content)
            assertTrue("手机上传的文字" in requests.last()["messages"].toString())

            // 大文件：1.5MB 要切片
            val big = ByteArray(1_500_000) { (it * 31 % 251).toByte() }
            val bigAtt = remote.upload("大文件.bin", "application/octet-stream", big)
            assertNotNull(bigAtt)
            assertContentEquals(big, remote.fileBytes(bigAtt.id))

            // 打码的 Key 传回来不能覆盖真 Key
            remote.call(Command.SaveProvider(remote.store.state.value.providers.first { it.id == "p1" }.copy(name = "改名")))
            assertEquals("sk-test", pc.state.provider("p1")!!.apiKey)
            assertEquals("改名", pc.state.provider("p1")!!.name)

            // 把电脑的配置导入手机：手机单独用时就有模型和成员了，Key 是真的
            val exported = remote.call(Command.ExportConfig)
            assertTrue(exported.ok)
            phoneEngine.call(Command.ImportConfig(exported.data))
            assertEquals("sk-test", phoneEngine.state.provider("p1")!!.apiKey)
            assertEquals(listOf("小智"), phoneEngine.state.members.map { it.name })
            assertEquals("", phoneEngine.state.settings.proxy, "电脑的本地代理地址不导入手机")

            // 手机单独用（不经过电脑）
            val local = phoneEngine.call(Command.CreateConversation(listOf(ms[0].id))).data
            phoneEngine.call(Command.UpdateConversation(phoneEngine.state.conversation(local)!!.copy(webSearch = false)))
            phoneEngine.call(Command.SendMessage(local, "本机问一句"))
            waitIdle(phoneEngine, local, 1)
            assertEquals("我是小智。", phoneEngine.store.messages.value[local]!!.last().content)

            // 电脑取消配对 → 手机收到通知
            pc.call(Command.RemoveDevice(pc.state.devices.single().id))
            withTimeout(15_000) { while (!remote.revoked.value) delay(100) }
            hub.unpair()
        } finally {
            relay.stop()
            broker.stopServer()
        }
    }

    @Suppress("unused")
    private fun json(o: Any) = AppJson
}
