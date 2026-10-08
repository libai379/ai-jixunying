package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Addressing
import com.guixing.jixunying.engine.DocExtract
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.BgJob
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Event
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
import com.guixing.jixunying.model.ThinkingMode
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用一个假的模型服务器把引擎跑一遍；联机用进程内的 MQTT 服务器（Moquette）代替公共中转。 */
class EngineTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val imageRequests = Collections.synchronizedList(mutableListOf<String>())
    // 假微信
    private val wxInbox = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val wxSent = Collections.synchronizedList(mutableListOf<String>())
    private val wxTyping = Collections.synchronizedList(mutableListOf<String>())
    private val wxHeaders = Collections.synchronizedList(mutableListOf<String>())
    private val qrPolls = java.util.concurrent.atomic.AtomicInteger()
    private val wxKey = ByteArray(16) { (it * 7 + 3).toByte() }
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
                    // 不认切换思考参数的服务（用 Key 区分）
                    if (call.request.headers["Authorization"] == "Bearer refuse-thinking" && req.containsKey("thinking")) {
                        call.respondText("""{"error":{"message":"Unrecognized request argument supplied: thinking"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    // Key 失效的服务商（测记录员后台出错）
                    if (call.request.headers["Authorization"] == "Bearer bad-key") {
                        call.respondText("""{"error":{"message":"Invalid API key"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@post
                    }
                    // 智谱的写法：中文、不带字段名（code 1210）
                    if (call.request.headers["Authorization"] == "Bearer refuse-thinking-cn" && req.containsKey("thinking")) {
                        call.respondText("""{"error":{"code":"1210","message":"该模型始终思考，不支持关闭思考；请使用 low、high 或 max。"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    if (req.containsKey("temperature")) {
                        call.respondText("""{"error":{"message":"invalid temperature: only 1 is allowed"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val model = req["model"]!!.jsonPrimitive.content
                    val msgs = req["messages"]!!.jsonArray
                    val last = msgs.last().jsonObject
                    val lastText = (last["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val firstText = (msgs.first().jsonObject["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    // 记录员的请求没有成员设定
                    val name = runCatching { memberName(req) }.getOrDefault("记录员")
                    val tools = req["tools"] as? JsonArray
                    fun hasTool(n: String) = tools?.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == n } == true
                    val hasFnSearch = hasTool("web_search")
                    val hasDraw = hasTool("generate_image")
                    val sawTool = msgs.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
                    val drew = msgs.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" && "图片已生成" in it.jsonObject["content"].toString() }
                    val remembered = msgs.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" && "记住" in it.jsonObject["content"].toString() }
                    val toolTexts = msgs.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                        .map { (it["content"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    // 只看用户最新的那一句（连续几句会合并成一条）
                    val latest = lastText.substringAfterLast("【")
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String, extra: String = "") { write("data: {$extra\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(text: String) = text.chunked(3).forEach {
                            chunk(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("content" to JsonPrimitive(it)))))
                        }
                        fun callTool(name: String, args: Map<String, String>) {
                            val argsJson = JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString()
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(argsJson)))
                            val call = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t_$name"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(call)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        when {
                            // —— 记录员 ——
                            "你是聊天记录员" in firstText -> say("## 话题\n测试话题\n## 已经定下的结论\n摘要里的结论")
                            "找出值得长期记住的" in firstText -> say("""好的：[{"text":"在北京做产品经理","kind":"关于我","replaces":null}]""")
                            "下面是关于一位用户的长期记忆" in firstText -> say("""[{"text":"喜欢简短的回答","kind":"偏好","from":[1,2]}]""")
                            // 点名：只有「让丙……」是在叫人，其他算对大家说
                            "判断这句话是想让哪几位成员来回答" in firstText -> say(if ("让丙" in firstText) """好的：{"to":["丙"]}""" else """{"to":[]}""")
                            // 立场档案：第二轮乙看到大家都说 A 就改了（跟风），甲没变；第一轮开新议题
                            "立场档案" in firstText && "之前已经记下的议题" in firstText -> say(
                                """{"updates":[{"topic":1,"member":"甲","stance":"A","why":"没变","by":"","reason":"还是说 1.5 大"},""" +
                                    """{"topic":1,"member":"乙","stance":"A","why":"跟风","by":"甲","reason":"看到大家都说 1.5 大就改了"}],"userDoubt":true,"newTopic":null}""")
                            "立场档案" in firstText && "哪个大" in firstText -> say(
                                """```json
                                |{"newTopic":{"question":"1.5 和 1.12 哪个大","options":[{"key":"A","text":"1.5 大"},{"key":"B","text":"1.12 大"}],
                                |"stances":[{"member":"甲","stance":"A"},{"member":"乙","stance":"B"},{"member":"丙","stance":"A"}]}}
                                |```""".trimMargin())
                            "立场档案" in firstText -> say("""{"newTopic":null}""")
                            // 用户说「记住」：调 remember
                            hasTool("remember") && latest.contains("记住") && !sawTool -> {
                                chunk("""{"tool_calls":[{"index":0,"id":"mem_1","type":"function","function":{"name":"remember","arguments":"{\"text\":\"喜欢简短的回答\",\"kind\":\"偏好\"}"}}]}""")
                                write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            }
                            remembered -> say("记住了。")
                            // 问自己的文档：先搜，再按搜到的路径读，读完再答
                            hasTool("search_documents") && latest.contains("合同") && !sawTool -> callTool("search_documents", mapOf("query" to "付款日期"))
                            toolTexts.any { "字，下面是" in it } -> say("合同里写的付款日期是 " + Regex("付款日期：(\\S+)").find(toolTexts.last())!!.groupValues[1])
                            hasTool("read_document") && toolTexts.any { "路径：" in it } ->
                                callTool("read_document", mapOf("path" to Regex("路径：(.+)").find(toolTexts.first { "路径：" in it })!!.groupValues[1].trim()))
                            // 想读文档库以外的文件
                            hasTool("read_document") && latest.contains("偷看") && !sawTool -> callTool("read_document", mapOf("path" to latest.substringAfter("偷看").trim()))
                            toolTexts.any { "文档库里没有" in it } -> say("读不了。")
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
                // —— 查余额（各家的格式照官方文档 / 官方 CLI）——
                get("/ds/user/balance") {
                    call.respondText("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""", ContentType.Application.Json)
                }
                get("/bad/user/balance") { call.respondText("""{"error":{"message":"Authentication Fails"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized) }
                get("/v1/users/me/balance") {
                    call.respondText("""{"code":0,"data":{"available_balance":49.58,"voucher_balance":46.58,"cash_balance":3.0},"scode":"0x0","status":true}""", ContentType.Application.Json)
                }
                get("/mm/account/query_balance") {
                    call.respondText("""{"available_amount":"12.30","cash_balance":"10.00","voucher_balance":"2.30","owed_amount":"0","base_resp":{"status_code":0,"status_msg":"success"}}""", ContentType.Application.Json)
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
                // —— 假的微信 iLink 接口和 CDN ——
                post("/ilink/bot/get_bot_qrcode") { call.respondText("""{"qrcode":"qr1","qrcode_img_content":"https://liteapp.weixin.qq.com/q/qr1"}""", ContentType.Application.Json) }
                get("/ilink/bot/get_qrcode_status") {
                    wxHeaders += call.request.headers["iLink-App-Id"].orEmpty()
                    val n = qrPolls.incrementAndGet()
                    call.respondText(if (n == 1) """{"status":"scaned"}"""
                        else """{"status":"confirmed","bot_token":"tok","ilink_bot_id":"bot1@im.bot","ilink_user_id":"me@im.wechat","baseurl":"http://127.0.0.1:$port"}""",
                        ContentType.Application.Json)
                }
                post("/ilink/bot/msg/notifystart") { call.respondText("{}", ContentType.Application.Json) }
                post("/ilink/bot/msg/notifystop") { call.respondText("{}", ContentType.Application.Json) }
                post("/ilink/bot/getupdates") {
                    wxHeaders += "auth=" + call.request.headers["Authorization"] + ";type=" + call.request.headers["AuthorizationType"] + ";uin=" + call.request.headers["X-WECHAT-UIN"]
                    call.receiveText()
                    val batch = wxInbox.poll()
                    if (batch == null) { delay(300); call.respondText("""{"ret":0,"msgs":[],"get_updates_buf":"b0"}""", ContentType.Application.Json) }
                    else call.respondText("""{"ret":0,"msgs":[$batch],"get_updates_buf":"b${wxInbox.size}"}""", ContentType.Application.Json)
                }
                post("/ilink/bot/getconfig") { call.receiveText(); call.respondText("""{"ret":0,"typing_ticket":"tt"}""", ContentType.Application.Json) }
                post("/ilink/bot/sendtyping") { wxTyping += call.receiveText(); call.respondText("""{"ret":0}""", ContentType.Application.Json) }
                post("/ilink/bot/sendmessage") { wxSent += call.receiveText(); call.respondText("""{"ret":0,"message_id":12345678901234567890}""", ContentType.Application.Json) }
                post("/ilink/bot/getuploadurl") { wxSent += "upload:" + call.receiveText(); call.respondText("""{"ret":0,"upload_param":"up1"}""", ContentType.Application.Json) }
                get("/c2c/download") {
                    // 微信 CDN 上的东西都是 AES-128-ECB 加密的
                    call.respondBytes(com.guixing.jixunying.engine.WeixinBridge.aesEcb(png, wxKey, encrypt = true), ContentType.Application.OctetStream)
                }
                post("/c2c/upload") {
                    val body = call.receiveText().length
                    wxSent += "cdn:" + call.request.queryParameters["encrypted_query_param"] + ":" + body
                    call.response.headers.append("x-encrypted-param", "dl1")
                    call.respondText("")
                }
                // 画不出来的服务商（比如 Key 没开通画图），用来测自动换下一个
                post("/bad/v1/images/generations") {
                    imageRequests += "bad:" + call.receiveText()
                    call.respondText("""{"error":{"message":"model not enabled for this key"}}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                post("/api/v1/services/aigc/multimodal-generation/generation") {
                    val async = call.request.headers["X-DashScope-Async"]
                    imageRequests += "dashscope:" + async + ":" + call.receiveText()
                    // 欠费的账号（百炼官方错误码 Arrearage）
                    if (call.request.headers["Authorization"] == "Bearer broke") {
                        call.respondText("""{"code":"Arrearage","message":"Access denied, please make sure your account is in good standing."}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    // 和真的千问AI平台一样（2026-10-08 实测）：千问图像不支持异步，回 403；同步直接给结果
                    if (async == "enable") call.respondText("""{"code":"AccessDenied","message":"current user api does not support asynchronous calls"}""",
                        ContentType.Application.Json, HttpStatusCode.Forbidden)
                    else call.respondText("""{"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"image":"http://127.0.0.1:$port/img/1.png"}]}}]}}""",
                        ContentType.Application.Json)
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
        // 只看成员的请求（答完以后记录员会看一遍立场，它当然看得到两人的回答）
        requests.filter { runCatching { memberName(it) }.isSuccess }.forEach { r ->
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

    /** 快速 / 深度：按这家的写法带参数，默认什么都不传；模型不认就去掉重试、标签恢复、提示一次，之后不再传。 */
    @Test
    fun thinkingFastAndDeep() = runBlocking {
        val (e, _) = engineWithMembers("小智")
        val notes = Collections.synchronizedList(mutableListOf<String>())
        e.addListener { if (it is Event.Notice) notes += it.text }
        e.call(Command.SaveProvider(ProviderConfig("pd", "deepseek", "DeepSeek", "${base()}/v1", "k", listOf(ModelInfo("deepseek-flash")))))
        e.call(Command.SaveProvider(ProviderConfig("pr", "deepseek", "不认参数的", "${base()}/v1", "refuse-thinking", listOf(ModelInfo("deepseek-flash")))))
        e.call(Command.SaveMember(Member("mf", "快", providerId = "pd", modelId = "deepseek-flash", thinking = ThinkingMode.FAST)))
        e.call(Command.SaveMember(Member("ms", "深", providerId = "pd", modelId = "deepseek-flash", thinking = ThinkingMode.DEEP)))
        e.call(Command.SaveMember(Member("ma", "默", providerId = "pd", modelId = "deepseek-flash")))
        e.call(Command.SaveMember(Member("mr", "倔", providerId = "pr", modelId = "deepseek-flash", thinking = ThinkingMode.FAST)))

        suspend fun ask(memberId: String): Pair<com.guixing.jixunying.model.Message, List<JsonObject>> {
            val conv = e.call(Command.CreateConversation(listOf(memberId))).data
            e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
            requests.clear()
            e.call(Command.SendMessage(conv, "你好"))
            waitIdle(e, conv, 1)
            return e.store.messages.value[conv]!!.last() to requests.toList()
        }

        val (fast, fastReqs) = ask("mf")
        assertEquals(MsgStatus.DONE, fast.status, fast.error)
        assertEquals("""{"type":"disabled"}""", fastReqs.single()["thinking"].toString())
        assertTrue(fast.modelLabel.endsWith("）· 快速"), fast.modelLabel)

        val (deep, deepReqs) = ask("ms")
        assertEquals("""{"type":"enabled"}""", deepReqs.single()["thinking"].toString())
        assertEquals("max", deepReqs.single()["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(deep.modelLabel.endsWith("）· 深度"), deep.modelLabel)

        val (auto, autoReqs) = ask("ma")
        assertFalse(autoReqs.single().containsKey("thinking") || autoReqs.single().containsKey("reasoning_effort"), "默认什么都不传")
        assertFalse("·" in auto.modelLabel, auto.modelLabel)

        // 不认参数：第一次 400，去掉重试答完；标签不带「快速」，提示一次
        val (refused, refusedReqs) = ask("mr")
        assertEquals(MsgStatus.DONE, refused.status, refused.error)
        assertEquals("我是倔。", refused.content)
        assertEquals(listOf(true, false), refusedReqs.map { it.containsKey("thinking") })
        assertFalse("快速" in refused.modelLabel, refused.modelLabel)
        assertEquals(1, notes.count { "不认「快速」" in it }, notes.toString())
        // 记住了：下次直接不传，也不再提示
        val (_, againReqs) = ask("mr")
        assertFalse(againReqs.single().containsKey("thinking"))
        assertEquals(1, notes.count { "不认「快速」" in it }, notes.toString())

        // 中文报错、不带字段名（智谱 1210）也认得出
        e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "${base()}/v1", "refuse-thinking-cn", listOf(ModelInfo("GLM-5.3-Flash")))))
        e.call(Command.SaveMember(Member("mz", "谱", providerId = "pz", modelId = "GLM-5.3-Flash", thinking = ThinkingMode.FAST)))
        val (zhipu, zhipuReqs) = ask("mz")
        assertEquals(MsgStatus.DONE, zhipu.status, zhipu.error)
        assertEquals("low", zhipuReqs.first()["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(zhipuReqs.last().containsKey("thinking") || zhipuReqs.last().containsKey("reasoning_effort"), "去掉思考参数重试")
    }

    /**
     * 记录员后台出错（Key 失效）：记进 bgProblems、同一样活只提示一次，聊天照常（点名判断不了就当对大家说）；
     * 换回好用的记录员，成功一次就清掉；「知道了」能收起。
     */
    @Test
    fun backgroundErrorsAreReported() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙", "丙")
        val notes = Collections.synchronizedList(mutableListOf<String>())
        e.addListener { if (it is Event.Notice && it.error) notes += it.text }
        e.call(Command.SaveProvider(ProviderConfig("pbad", "custom", "坏 Key", "${base()}/v1", "bad-key", listOf(ModelInfo("fake-chat")))))
        suspend fun recorder(pid: String) =
            e.call(Command.SaveSettings(e.state.settings.copy(memory = e.state.settings.memory.copy(recorderProviderId = pid, recorderModelId = if (pid.isEmpty()) "" else "fake-chat"))))
        recorder("pbad")
        val conv = e.call(Command.CreateConversation(ms.map { it.id })).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        fun problem(job: BgJob) = e.state.bgProblems.firstOrNull { it.job == job }
        suspend fun until(what: String, ok: () -> Boolean) = withTimeout(10_000) { while (!ok()) delay(50) }.also { assertTrue(ok(), what) }
        fun noticesAbout(label: String) = notes.count { "「$label」" in it }

        // 第一句：要问记录员是在叫谁 → Key 失效 → 当对大家说，三位都答；立场档案也记不上
        e.call(Command.SendMessage(conv, "让乙来算一下 1.5 和 1.12 哪个大"))
        waitIdle(e, conv, 3)
        assertEquals(3, e.store.messages.value[conv]!!.count { it.role == Role.AI }, "判断不了就当对大家说")
        until("点名判断出错要记下") { problem(BgJob.ADDRESSING) != null }
        until("立场档案出错要记下") { problem(BgJob.STANCES) != null }
        assertTrue("API Key 不对" in problem(BgJob.ADDRESSING)!!.reason, problem(BgJob.ADDRESSING)!!.reason)
        assertEquals(1, noticesAbout("点名判断"), notes.toString())
        assertEquals(1, noticesAbout("立场档案"), notes.toString())

        // 再错一次：次数加一，半小时内不再弹提示
        e.call(Command.SendMessage(conv, "让乙再算一遍"))
        waitIdle(e, conv, 6)
        until("连续两次") { problem(BgJob.ADDRESSING)?.times == 2 && problem(BgJob.STANCES)?.times == 2 }
        assertEquals(1, noticesAbout("点名判断"), notes.toString())
        assertEquals(1, noticesAbout("立场档案"), notes.toString())

        // 换回好用的记录员：点名判断成功一次就清掉，这次只有丙回答
        recorder("")
        e.call(Command.SendMessage(conv, "让丙来说说"))
        waitIdle(e, conv, 7)
        assertNull(problem(BgJob.ADDRESSING))
        assertEquals("丙", e.state.member(e.store.messages.value[conv]!!.last { it.role == Role.AI }.senderId)!!.name)
        // 大家都答的一题：立场档案记上了，错也清掉
        e.call(Command.SendMessage(conv, "1.5 和 1.12 哪个大"))
        waitIdle(e, conv, 10)
        until("立场档案恢复") { problem(BgJob.STANCES) == null }

        // 「知道了」：收起
        recorder("pbad")
        e.call(Command.SendMessage(conv, "让乙来算一下"))
        waitIdle(e, conv, 13)
        until("又出错") { problem(BgJob.ADDRESSING) != null }
        e.call(Command.DismissBgProblem(BgJob.ADDRESSING))
        assertNull(problem(BgJob.ADDRESSING))

        // 关掉立场档案：它的出错提示直接清掉（不会再跑，也就等不到「成功一次」）
        until("立场档案又出错") { problem(BgJob.STANCES) != null }
        e.call(Command.SaveSettings(e.state.settings.copy(memory = e.state.settings.memory.copy(stances = false))))
        assertNull(problem(BgJob.STANCES))
    }

    /** 记账：成员回答、记录员的后台活、画图都记一条；第一次启动时把以前聊天记录里的用量补记进来（只补一次）。 */
    @Test
    fun ledgerRecordsEveryCall() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙", "丙")
        e.call(Command.SaveProvider(ProviderConfig("pimg", "custom", "画图", "${base()}/v1", "k", listOf(ModelInfo("fake-image", tools = false, imageGen = true)))))
        val conv = e.call(Command.CreateConversation(ms.map { it.id })).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        fun ledger() = com.guixing.jixunying.engine.Ledger(File(dir, "usage")).since()
        suspend fun until(what: String, ok: () -> Boolean) = withTimeout(10_000) { while (!ok()) delay(50) }.also { assertTrue(ok(), what) }

        // 「让丙来说说」：点名判断记一条，只有丙回答，记在丙账上
        e.call(Command.SendMessage(conv, "让丙来说说"))
        waitIdle(e, conv, 1)
        until("点名和回答都记上") { ledger().map { it.kind }.containsAll(listOf("addressing", "chat")) }
        val chat = ledger().single { it.kind == "chat" }
        assertEquals("m2", chat.memberId, "点名点的是丙")
        assertEquals(conv, chat.convId)
        assertEquals(10, chat.prompt); assertEquals(5, chat.completion)
        assertEquals("p1", chat.providerId); assertEquals("fake-chat", chat.model)
        // 大家都答的一题：三条回答 + 立场档案记录员
        e.call(Command.SendMessage(conv, "1.5 和 1.12 哪个大"))
        waitIdle(e, conv, 4)
        until("立场档案记上") { ledger().any { it.kind == "stances" && it.convId == conv } }
        assertEquals(4, ledger().count { it.kind == "chat" })
        // 花费页的指令能用（假模型价格表里没有，都算「没价格」）
        val costs = AppJson.decodeFromString(com.guixing.jixunying.model.CostReport.serializer(), e.call(Command.GetCosts("all")).data)
        assertEquals(ledger().size, costs.total.calls)
        assertTrue(costs.byMember.any { it.label == "丙" } && costs.byMember.any { it.label == "记录员（后台）" }, costs.byMember.toString())

        // 直接画图：记一张
        e.call(Command.SendMessage(conv, "一只猫", drawImage = true))
        until("画图记一张") { ledger().any { it.kind == "image" && it.images == 1 && it.model == "fake-image" && it.convId == conv } }

        // 补记：把账本删掉，重新打开，以前那条带 usage 的回答和那张图会补进来（标着 backfill），再开一次不重复补
        File(dir, "usage").deleteRecursively()
        val e2 = Engine(Storage(dir))
        until("补记") { ledger().count { it.backfill && it.kind == "chat" && it.prompt == 10 } == 4 && ledger().any { it.backfill && it.kind == "image" } }
        val n = ledger().size
        Engine(Storage(dir))
        delay(500)
        assertEquals(n, ledger().size, "只补一次")
        assertTrue(e2.state.members.isNotEmpty())
    }

    /** 查余额：能查的平台用同一个 Key 查（格式各家不一样），Key 不对说清楚，查不了的给控制台链接。 */
    @Test
    fun balances() = runBlocking {
        val e = Engine(Storage(dir))
        e.call(Command.SaveProvider(ProviderConfig("pd", "deepseek", "DeepSeek", "${base()}/ds", "k", listOf(ModelInfo("deepseek-flash")))))
        e.call(Command.SaveProvider(ProviderConfig("pk", "moonshot", "Kimi", "${base()}/v1", "k", listOf(ModelInfo("kimi-k3")))))
        e.call(Command.SaveProvider(ProviderConfig("pm", "minimax", "MiniMax", "${base()}/mm/v1", "k", listOf(ModelInfo("MiniMax-M3")))))
        e.call(Command.SaveProvider(ProviderConfig("pi", "mimo", "小米", "${base()}/v1", "k", listOf(ModelInfo("mimo-v2.6-flash")))))
        e.call(Command.SaveProvider(ProviderConfig("pb", "deepseek", "坏 Key", "${base()}/bad", "bad", listOf(ModelInfo("deepseek-flash")))))
        val r = e.call(Command.GetBalances)
        assertTrue(r.ok, r.message)
        val list = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.BalanceInfo.serializer()), r.data).associateBy { it.providerId }
        assertEquals(110.0, list["pd"]!!.amount); assertTrue("赠送 ¥10.00" in list["pd"]!!.text, list["pd"]!!.text)
        assertEquals(49.58, list["pk"]!!.amount); assertTrue("代金券" in list["pk"]!!.text)
        assertEquals(12.3, list["pm"]!!.amount); assertTrue(list["pm"]!!.unofficial)
        assertFalse(list["pi"]!!.supported); assertTrue(list["pi"]!!.consoleUrl.startsWith("https://"), "查不了的给控制台链接")
        assertFalse(list["pb"]!!.ok); assertTrue("Key 不对" in list["pb"]!!.text, list["pb"]!!.text)
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
        assertTrue(imageRequests.any { it.startsWith("dashscope:null:") && "1024*1024" in it }, "千问图像按官方文档走同步")
        assertTrue(imageRequests.none { it.startsWith("dashscope:enable:") }, "不先试异步（千问AI平台会回 403）")
        assertTrue(imageRequests.any { it.startsWith("minimax:") && "\"aspect_ratio\":\"1:1\"" in it })
        assertTrue(imageRequests.any { it.startsWith("kling:Bearer ey") }, "AK:SK 要签成 JWT")
        assertTrue(imageRequests.any { it.startsWith("modelscope:true:") })
    }

    /**
     * 没在 设置→画图 指定模型时自动挑，画质优先：千问图像排第一（用户定的默认），免费的 cogview-3-flash 垫底
     * （服务商模型列表里没有的也按预设推断）。千问欠费：千问家别的模型不再试，直接换下一家；
     * 智谱画不出来就换 MiniMax 的 image-01；刚失败过的排到后面，下次直接用能用的。
     */
    @Test
    fun imageAutoPickAndFallback() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        e.call(Command.SaveProvider(ProviderConfig("pq", "qianwen", "千问", "${base()}/compatible-mode/v1", "broke",
            listOf(ModelInfo("qwen-image-3.0", imageGen = true, tools = false)))))
        e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "${base()}/bad/v1", "k", listOf(ModelInfo("glm-fake")))))
        e.call(Command.SaveProvider(ProviderConfig("pm", "minimax", "MiniMax", "${base()}/mm/v1", "k", listOf(ModelInfo("MiniMax-M3")))))
        val picks = com.guixing.jixunying.model.ImagePick.resolve(e.state)
        assertEquals(listOf("qwen-image-3.0", "wan2.7-image", "cogview-4-250304", "image-01", "cogview-3-flash"), picks.map { it.modelId })
        assertTrue(picks.last().free)

        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.SendMessage(conv, "一只猫", drawImage = true))
        waitIdle(e, conv, 1)
        val m = e.store.messages.value[conv]!!.last()
        assertEquals(MsgStatus.DONE, m.status, m.error)
        assertEquals("image-01（MiniMax）", m.modelLabel)
        assertEquals(listOf("dashscope", "bad", "minimax"), imageRequests.map { it.substringBefore(':') },
            "千问欠费就跳过万相（同一个 Key），智谱失败后换 MiniMax")

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

    /** AI 用 remember 记下的东西，之后每位成员的设定里都有；能在别的对话里被用上。 */
    @Test
    fun rememberToolFeedsLaterPrompts() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙")
        val c1 = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(c1)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(c1, "记住我喜欢简短的回答"))
        waitIdle(e, c1, 1)
        val ai = e.store.messages.value[c1]!!.last()
        assertEquals("记住了。", ai.content, ai.error)
        assertEquals("memory", ai.tools.single().kind)
        assertEquals(listOf("喜欢简短的回答"), e.state.memories.map { it.text })
        // 另一个对话、另一位成员也知道
        val c2 = e.call(Command.CreateConversation(listOf(ms[1].id))).data
        e.call(Command.SendMessage(c2, "你好"))
        waitIdle(e, c2, 1)
        val sys = systemOf(requests.last { memberName(it) == "乙" })
        assertTrue("[偏好] 喜欢简短的回答" in sys, sys)
        // 敏感信息不记
        val bad = e.call(Command.SaveMemory(com.guixing.jixunying.model.MemoryItem("x", "我的密码是 123456")))
        assertFalse(bad.ok)
    }

    /** 聊长了由记录员压成摘要（全群一份），之后发给模型的只有摘要 + 最近的原文；攒够 4 句用户的话会自动挑长期记忆。 */
    @Test
    fun recorderCompactsAndExtracts() = runBlocking {
        val (e, ms) = engineWithMembers("甲")
        e.call(Command.SaveSettings(e.state.settings.copy(memory = e.state.settings.memory.copy(compressAt = 400))))
        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        repeat(5) { i ->
            e.call(Command.SendMessage(conv, "第${i}句：" + "很长的内容".repeat(30)))
            waitIdle(e, conv, i + 1)
            delay(300) // 等后台的记录员干完
        }
        val c = e.state.conversation(conv)!!
        assertTrue(c.summarized > 0, "应该压缩过")
        assertTrue(requests.any { "你是聊天记录员" in it.toString() })
        // 最后一次回答的上下文：有摘要，没有第 0 句原文
        val lastChat = requests.last { runCatching { memberName(it) }.getOrNull() == "甲" }
        val ctx = lastChat["messages"].toString()
        assertTrue("【对话摘要】" in ctx && "摘要里的结论" in ctx, ctx.take(400))
        assertFalse("第0句" in ctx, "压缩掉的原文不再发")
        // 长期记忆：自动挑出来了
        assertTrue(e.state.memories.any { it.text == "在北京做产品经理" }, e.state.memories.toString())

        // 搜以前的聊天
        val hits = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.HistoryHit.serializer()),
            e.call(Command.SearchHistory("第3句")).data)
        assertEquals("第3句：", hits.first().snippet.take(4))

        // 整理：两条变一条
        e.call(Command.SaveMemory(com.guixing.jixunying.model.MemoryItem("m-a", "喜欢简洁", "偏好")))
        val tidy = e.call(Command.TidyMemories)
        assertTrue(tidy.ok, tidy.message)
        assertEquals(listOf("喜欢简短的回答"), e.state.memories.map { it.text })
    }

    /** 本机文档：收录指定文件夹（跳过 node_modules 这类），能按内容搜；AI 先搜再读再答；文档库以外的文件读不了。 */
    @Test
    fun localDocumentsSearchAndRead() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        val folder = File(dir, "我的资料").apply { mkdirs() }
        File(folder, "合同.txt").writeText("甲方：某某公司\n付款日期：2026年11月30日\n违约金：合同金额的百分之五", Charsets.UTF_8)
        File(folder, "周报.md").writeText("本周完成了登录页改版", Charsets.UTF_8)
        File(folder, "node_modules").mkdirs()
        File(folder, "node_modules/依赖说明.txt").writeText("不该收录", Charsets.UTF_8)
        val secret = File(dir, "secret.txt").apply { writeText("机密", Charsets.UTF_8) }
        e.call(Command.SaveSettings(e.state.settings.copy(docs = com.guixing.jixunying.model.DocSettings(folders = listOf(folder.path)))))
        withTimeout(10_000) { while (e.state.docs.count < 2 || e.state.docs.scanning) delay(50) }
        assertEquals(2, e.state.docs.count, "node_modules 里的不收")

        val ser = kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.DocHit.serializer())
        val hits = AppJson.decodeFromString(ser, e.call(Command.DocSearch("付款日期")).data)
        assertEquals("合同.txt", hits.first().name)
        assertTrue("2026年11月30日" in hits.first().snippet, hits.first().snippet)
        assertEquals(2, AppJson.decodeFromString(ser, e.call(Command.DocSearch("")).data).size, "不填关键词 = 最近的文档")

        val conv = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(conv, "我那份合同里写的付款日期是哪天"))
        waitIdle(e, conv, 1)
        val ai = e.store.messages.value[conv]!!.last()
        assertEquals("合同里写的付款日期是 2026年11月30日", ai.content, ai.error)
        assertEquals(listOf("doc", "doc"), ai.tools.map { it.kind })
        assertTrue("文档" in systemOf(requests.first { runCatching { memberName(it) }.getOrNull() == "小智" }))

        e.call(Command.SendMessage(conv, "偷看 ${secret.path}"))
        waitIdle(e, conv, 2)
        assertEquals("读不了。", e.store.messages.value[conv]!!.last().content)

        val att = AppJson.decodeFromString(com.guixing.jixunying.model.Attachment.serializer(), e.call(Command.DocAttach(hits.first().path)).data)
        assertTrue(att.textChars > 0)
    }

    /**
     * 微信助理：扫码绑定 → 收文字、收加密图片 → AI 回答发回微信（带 context_token、正在输入）→ 画的图加密上传后发回 → /新对话。
     * 假的 iLink 接口和 CDN 在 setUp 里。
     */
    @Test
    fun weixinBridgeEndToEnd() = runBlocking {
        val (e, ms) = engineWithMembers("小智")
        e.call(Command.SaveProvider(ProviderConfig("pimg", "zhipu", "画图", "${base()}/v1", "k", listOf(ModelInfo("cogview-3-flash", imageGen = true, tools = false)))))
        val bridge = com.guixing.jixunying.engine.WeixinBridge(e, File(dir, "weixin"), loginBase = base(), cdnBase = "${base()}/c2c")
        e.weixin = bridge
        bridge.start()
        assertTrue(e.state.weixinCapable)

        val login = e.call(Command.WeixinLogin)
        assertTrue(login.ok, login.message)
        assertEquals("https://liteapp.weixin.qq.com/q/qr1", login.data)
        withTimeout(10_000) { while (!e.state.weixin.status.startsWith("已连接")) delay(50) }
        assertTrue(e.state.weixin.bound)
        assertEquals("bot", wxHeaders.first())
        assertTrue(wxHeaders.any { it.startsWith("auth=Bearer tok;type=ilink_bot_token;uin=") })

        fun msg(id: Long, items: String, from: String = "me@im.wechat") = """{"message_id":$id,"from_user_id":"$from","to_user_id":"bot1@im.bot","message_type":1,"context_token":"ctx$id","item_list":[$items]}"""
        val keyB64 = Base64.getEncoder().encodeToString(wxKey)
        wxInbox += msg(98765432109876543, """{"type":1,"text_item":{"text":"你好"}}""")
        wxInbox += msg(2, """{"type":2,"image_item":{"media":{"encrypt_query_param":"img1","aes_key":"$keyB64","encrypt_type":1}}},{"type":1,"text_item":{"text":"看看这张"}}""")
        wxInbox += msg(3, """{"type":1,"text_item":{"text":"帮我画一只猫"}}""")
        // 陌生人发来的不理（只回答扫码绑定的本人）
        wxInbox += msg(5, """{"type":1,"text_item":{"text":"我是陌生人"}}""", from = "stranger@im.wechat")
        wxInbox += msg(4, """{"type":1,"text_item":{"text":"/新对话"}}""")
        withTimeout(20_000) { while (wxSent.none { "换个新话题" in it }) delay(50) }

        val conv = e.state.conversations.filter { it.channel == "weixin:me@im.wechat" }
        assertTrue(e.state.conversations.none { it.channel == "weixin:stranger@im.wechat" }, "陌生人的消息不处理")
        assertEquals(2, conv.size, "/新对话 新开了一个")
        val first = conv.last()
        e.call(Command.LoadMessages(first.id))
        val msgs = e.store.messages.value[first.id]!!
        assertEquals("你好", msgs.first().content)
        val img = msgs.first { it.content == "看看这张" }.attachments.single()
        assertContentEquals(png, e.fileBytes(img.id), "图片下载后解密")

        val texts = wxSent.filter { it.startsWith("{") }.map { Json.parseToJsonElement(it).jsonObject["msg"]!!.jsonObject }
        val firstReply = texts.first()
        assertEquals("me@im.wechat", firstReply["to_user_id"]!!.jsonPrimitive.content)
        assertTrue(texts.none { it["to_user_id"]!!.jsonPrimitive.content == "stranger@im.wechat" })
        assertEquals("ctx98765432109876543", firstReply["context_token"]!!.jsonPrimitive.content, "回复带上对方那条消息的 context_token（大整数不丢精度）")
        assertEquals("我是小智。", firstReply["item_list"]!!.jsonArray[0].jsonObject["text_item"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(texts.any { it["item_list"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content == "2" }, "画的图发回微信")
        assertTrue(wxSent.any { it.startsWith("cdn:up1:") }, "图片先加密传到 CDN")
        assertTrue(wxTyping.any { "\"status\":1" in it } && wxTyping.any { "\"status\":2" in it }, "正在输入 → 取消")

        e.call(Command.WeixinLogout)
        assertFalse(e.state.weixin.bound)
        bridge.stop()
    }

    @Test
    fun weixinPlainTextAndChunks() {
        val md = "## 结论\n**押金**要退\n- 第一条\n- 第二条\n[链接](https://a.example/x)\n| 列1 | 列2 |\n|---|---|\n| a | b |\n`代码`"
        assertEquals("结论\n押金要退\n• 第一条\n• 第二条\n链接（https://a.example/x）\n| 列1 | 列2 |\n| a | b |\n代码",
            com.guixing.jixunying.engine.WeixinBridge.plainText(md))
        val long = (1..50).joinToString("\n") { "第${it}段" + "字".repeat(60) }
        val parts = com.guixing.jixunying.engine.WeixinBridge.chunks(long, 1800)
        assertTrue(parts.size >= 2 && parts.all { it.length <= 1800 })
        assertEquals(long.replace("\n", ""), parts.joinToString("").replace("\n", ""))
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

    /**
     * 点名：不带 @、开头直接喊名字的，程序直接认，只有他回答（不问模型）；
     * 「让丙说两句」这种交给记录员的模型判断；只是提到名字（「乙说得对吗」）的，大家照常都答。
     */
    @Test
    fun nameCallAnswersOnlyThatMember() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙", "丙")
        val conv = e.call(Command.CreateConversation(ms.map { it.id })).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        fun aiOf(after: Int) = e.store.messages.value[conv]!!.filter { it.role == Role.AI }.drop(after).map { it.content }
        fun dispatcherAsked() = requests.count { "判断这句话是想让哪几位成员来回答" in it.toString() }

        e.call(Command.SendMessage(conv, "乙，你好"))
        waitIdle(e, conv, 1)
        assertEquals(listOf("我是乙。"), aiOf(0))
        assertEquals(0, dispatcherAsked(), "开头直接喊名字，不用问模型")

        e.call(Command.SendMessage(conv, "让丙说两句"))
        waitIdle(e, conv, 2)
        assertEquals(listOf("我是丙。"), aiOf(1))
        assertEquals(1, dispatcherAsked())

        e.call(Command.SendMessage(conv, "乙说得对吗？"))
        waitIdle(e, conv, 5)
        assertEquals(setOf("我是甲。", "我是乙。", "我是丙。"), aiOf(2).toSet(), "只是提到乙，大家都答")
        assertEquals(2, dispatcherAsked())

        // 程序直接认的几种说法
        val m = ms
        assertEquals(listOf(m[0], m[1]), Addressing.leading("甲和乙，你们比一下", m))
        assertEquals(listOf(m[1]), Addressing.leading("乙你怎么看", m))
        assertEquals(emptyList(), Addressing.leading("乙说得对吗", m), "名字后面直接跟话，可能只是在提他")
        assertEquals(emptyList(), Addressing.leading("甲，乙说得对吗", m), "后面又提到别人，交给模型")

        // 开头喊了不在这个对话里的成员：和 @ 一样，拉进来由他回答
        val solo = e.call(Command.CreateConversation(listOf(ms[0].id))).data
        e.call(Command.UpdateConversation(e.state.conversation(solo)!!.copy(webSearch = false)))
        e.call(Command.SendMessage(solo, "丙，你来说说"))
        waitIdle(e, solo, 1)
        assertEquals(listOf("m0", "m2"), e.state.conversation(solo)!!.memberIds)
        assertEquals(listOf("我是丙。"), e.store.messages.value[solo]!!.filter { it.role == Role.AI }.map { it.content })
    }

    /**
     * 立场档案：独立作答后记录员开议题、记首答；下一轮乙看到大家都说 A 就改了（跟风），甲没变（坚持）；
     * 用户标 A 对之后，能算出首答准确率、坚持、跟风、被纠正、说服别人；删消息、删对话时档案跟着清。
     */
    @Test
    fun stanceArchiveRecordsChanges() = runBlocking {
        val (e, ms) = engineWithMembers("甲", "乙", "丙")
        val conv = e.call(Command.CreateConversation(ms.map { it.id })).data
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        val ser = kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.StanceTopic.serializer())
        suspend fun topics() = AppJson.decodeFromString(ser, e.call(Command.StanceList(conv)).data)

        e.call(Command.SendMessage(conv, "1.5 和 1.12 哪个大？"))
        waitIdle(e, conv, 3)
        withTimeout(5_000) { while (e.state.conversation(conv)!!.stanceTopics < 1) delay(50) }
        val t1 = topics().single()
        assertEquals("1.5 和 1.12 哪个大", t1.question)
        assertEquals(listOf("A", "B", "A"), ms.map { m -> t1.entries.single { it.memberId == m.id }.option })
        assertTrue(t1.entries.all { it.first }, "独立作答的是首答")
        val judgeReq = requests.first { "立场档案" in it.toString() }.toString()
        assertTrue("独立作答" in judgeReq && "我是乙。" in judgeReq)

        e.call(Command.SendMessage(conv, "真的吗？再想想"))
        waitIdle(e, conv, 6)
        withTimeout(5_000) { while (topics().single().entries.size < 5) delay(50) }
        val t2 = topics().single()
        val yi = t2.entries.last { it.memberId == ms[1].id }
        assertEquals("A", yi.option)
        assertEquals(com.guixing.jixunying.model.Stances.FOLLOW, yi.why)
        assertEquals(ms[0].id, yi.by)
        assertEquals(com.guixing.jixunying.model.Stances.HOLD, t2.entries.last { it.memberId == ms[0].id }.why)

        assertFalse(e.call(Command.StanceMark(t2.id, "Z")).ok, "没有这个立场")
        assertTrue(e.call(Command.StanceMark(t2.id, "A")).ok)
        val cards = com.guixing.jixunying.model.Stances.cards(topics(), ms.map { it.id })
        val (jia, yiCard, bing) = cards
        assertEquals(1 to 1, jia.firstRight to jia.judged)
        assertEquals(1 to 1, jia.held to jia.challenged, "乙当时跟他不一样，他坚持了")
        assertEquals(1, jia.convinced)
        assertEquals(0, yiCard.firstRight)
        assertEquals(1, yiCard.follow)
        assertEquals(1, yiCard.corrected, "本来错、后来改对")
        assertEquals(0, yiCard.misled)
        assertEquals(1 to 0, bing.firstRight to bing.challenged)

        // 删掉乙第二轮的回答：那条表态也去掉
        e.call(Command.DeleteMessage(conv, yi.messageId))
        assertEquals(4, topics().single().entries.size)
        // 删对话：档案跟着删
        e.call(Command.DeleteConversation(conv))
        assertEquals(0, AppJson.decodeFromString(ser, e.call(Command.StanceList()).data).size)
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
