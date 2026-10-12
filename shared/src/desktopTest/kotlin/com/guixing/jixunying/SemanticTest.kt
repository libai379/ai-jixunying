package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Ledger
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.VecEntry
import com.guixing.jixunying.engine.VecIndex
import com.guixing.jixunying.engine.VecSource
import com.guixing.jixunying.engine.VectorStore
import com.guixing.jixunying.engine.VectorText
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.BgJob
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.DocHit
import com.guixing.jixunying.model.DocSettings
import com.guixing.jixunying.model.EmbedModels
import com.guixing.jixunying.model.EmbedPick
import com.guixing.jixunying.model.HistoryHit
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemorySettings
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SemanticEstimateResult
import com.guixing.jixunying.model.SemanticProgress
import com.guixing.jixunying.model.SemanticSettings
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.UsageKinds
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 按意思搜（向量，docs/向量和语音设计说明.md 第 2 节）：切段、算向量、向量库、补算、
 * 搜索时和字面结果合起来排（RRF）、估算、记账、旧版手机的设置不被冲掉。
 *
 * 假服务器严格解析请求（教训 69）：路径、Content-Type、Authorization、model、input、
 * 一次发几条（智谱 64 / 别的 10）、dimensions，有一处不对就记下来并回 400；
 * 每个用到向量的用例末尾都调 assertStrict()，有记录就失败（不能只回 400 了事）。
 * 假服务器返回的顺序故意和输入相反（真服务器的顺序也不保证），对号入座只能靠 data[].index。
 */
class SemanticTest {
    private val embedRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val chatRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val embedProblems = Collections.synchronizedList(mutableListOf<String>())

    /** 返回顺序和输入相反（测「按 index 对回顺序」）。 */
    @Volatile private var shuffle = true

    /** 有的家出错也回 HTTP 200，内容是 {"error":{…}}（智谱就是）。 */
    @Volatile private var errorIn200 = false

    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun base() = "http://127.0.0.1:$port"

    /**
     * 假的「意思」：每条文字在几条语义轴上取值——同一层意思的话这些维上是 1，别的维全是 0。
     * 这样测试能精确控制谁和谁像（真模型算出来的 1024 维在测试里没法控制）。
     * 哪条轴都不沾的文字（比如「我是小智。」）是零向量，余弦算不了 → 搜的时候自然被跳过。
     */
    private val axes = listOf(
        listOf("开会", "碰", "商量", "约", "聊"),
        listOf("合同", "付款", "违约金", "钱"),
        listOf("猫", "狗", "宠物"),
    )

    private fun vectorOf(text: String, dim: Int): List<Float> {
        val v = MutableList(dim) { 0f }
        axes.forEachIndexed { i, words -> if (i < dim && words.any { it in text }) v[i] = 1f }
        return v
    }

    private fun bad(what: String) {
        embedProblems += what
        println("假向量服务器：$what")
    }

    @BeforeTest
    fun setUp() {
        port = freePort()
        dir = kotlin.io.path.createTempDirectory("jxy-semantic").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/embeddings") {
                    val ctype = call.request.headers[HttpHeaders.ContentType].orEmpty()
                    if (!ctype.startsWith(ContentType.Application.Json.toString())) bad("Content-Type 不对：$ctype")
                    val auth = call.request.headers[HttpHeaders.Authorization].orEmpty()
                    if (!auth.startsWith("Bearer ") || auth.removePrefix("Bearer ").isBlank()) bad("Authorization 不对：$auth")
                    val raw = call.receiveText()
                    val req = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    if (req == null) {
                        bad("请求体不是 JSON：${raw.take(200)}")
                        call.respondText("""{"error":{"message":"bad json"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val model = (req["model"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (model.isBlank()) bad("model 是空的")
                    val input = (req["input"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                    if (input.isEmpty()) bad("input 不是数组或者空了：${raw.take(200)}")
                    input.filterNotNull().forEachIndexed { i, t -> if (t.isBlank()) bad("input 第 ${i + 1} 条是空的") }
                    if (input.any { it == null }) bad("input 里有不是字符串的东西：${raw.take(200)}")
                    val zhipu = model.startsWith(EmbedModels.ZHIPU)   // embedding-3 / embedding-3-x 都算智谱那条线
                    val limit = if (zhipu) 64 else 10
                    if (input.size > limit) bad("一次发了 ${input.size} 条，$model 最多 $limit 条")
                    val dims = (req["dimensions"] as? JsonPrimitive)?.intOrNull
                    if (zhipu || model == EmbedModels.QWEN) {
                        if (dims != EmbedModels.DIM) bad("$model 的 dimensions 应该是 ${EmbedModels.DIM}，实际是 $dims")
                    } else if (dims != null) bad("「自定义」服务商不该发 dimensions，实际发了 $dims")
                    if (embedProblems.isNotEmpty()) {
                        call.respondText("""{"error":{"message":"${embedProblems.last()}"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    embedRequests += req
                    val texts = input.map { it.orEmpty() }
                    val dim = dims ?: 8
                    val items = texts.mapIndexed { i, t -> i to vectorOf(t, dim) }
                    val ordered = if (shuffle) items.reversed() else items
                    val tokens = texts.sumOf { it.length }
                    val out = if (errorIn200) {
                        // HTTP 200 里带 error 对象：也算失败，而且不能记账
                        """{"error":{"code":"1210","message":"向量算不出来"}}"""
                    } else {
                        val data = JsonArray(ordered.map { (i, v) ->
                            JsonObject(mapOf("index" to JsonPrimitive(i), "embedding" to JsonArray(v.map { JsonPrimitive(it) })))
                        })
                        JsonObject(mapOf(
                            "data" to data,
                            "usage" to JsonObject(mapOf("prompt_tokens" to JsonPrimitive(tokens))),
                        )).toString()
                    }
                    call.respondText(out, ContentType.Application.Json)
                }
                // 聊天：只在测试要它调工具时说一句、调一次工具（真模型怎么调不在这个文件的范围里）
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    chatRequests += req
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val sawTool = msgs.any { it["role"]?.jsonPrimitive?.contentOrNull == "tool" }
                    val lastText = (msgs.last()["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(text: String) = text.chunked(4).forEach {
                            chunk(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("content" to JsonPrimitive(it)))))
                        }
                        fun callTool(name: String, args: String) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(args)))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t_$name"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            flush()
                        }
                        when {
                            sawTool -> say("我看过了。")
                            "翻翻" in lastText -> callTool("search_history", """{"query":"什么时候开会"}""")
                            "找找文档" in lastText -> callTool("search_documents", """{"query":"付款时间"}""")
                            else -> say("我是小智。")
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

    /** 假服务器记下的问题（教训 69：解析不严的假服务器会让错的请求照样过）。 */
    private fun assertStrict() = assertTrue(embedProblems.isEmpty(), "假服务器觉得请求有问题：${embedProblems.joinToString("；")}")

    /** 一个引擎：chat 用「假服务商」，向量用参数里指定的那些（presetId 照真平台写）。 */
    private suspend fun engine(vararg embedPresets: String, root: File = dir): Engine {
        val e = Engine(Storage(root))
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "${base()}/v1", "sk-test", listOf(ModelInfo("fake-chat")))))
        e.call(Command.SaveMember(Member("m0", "小智", providerId = "p1", modelId = "fake-chat", bio = "小智的定位")))
        for (id in embedPresets) {
            e.call(Command.SaveProvider(ProviderConfig(id = "p-$id", presetId = id, name = "$id 家", baseUrl = "${base()}/v1", apiKey = "sk-$id")))
        }
        // 测试里不让记录员在后台干活（压缩、挑记忆、点名、立场），免得掺进别的请求
        val s = e.state.settings
        e.call(Command.SaveSettings(s.copy(
            relay = s.relay.copy(enabled = false),
            memory = MemorySettings(enabled = false, autoExtract = false, stances = false, aiVerify = false, compressAt = 10_000_000),
        )))
        return e
    }

    private suspend fun chatConv(e: Engine, title: String = "聊天"): String {
        val id = e.call(Command.CreateConversation(listOf("m0"), title)).data
        e.call(Command.UpdateConversation(e.state.conversation(id)!!.copy(webSearch = false)))
        return id
    }

    private suspend fun waitIdle(e: Engine, convId: String, minAi: Int) {
        withTimeout(20_000) {
            while (true) {
                val ai = e.store.messages.value[convId].orEmpty().filter { it.role == Role.AI }
                if (ai.size >= minAi && ai.none { it.status == MsgStatus.STREAMING }) break
                delay(50)
            }
        }
        delay(200)
    }

    /** 等这一份算完（done 到了期望的段数、而且不在算）。 */
    private suspend fun waitEmbed(e: Engine, section: String, done: Int) = withTimeout(30_000) {
        while (true) {
            val p = e.state.semanticProgress.of(section)
            if (!p.running && p.done == done) break
            delay(50)
        }
    }

    private fun historyHits(e: Engine, r: CommandResult): List<HistoryHit> =
        AppJson.decodeFromString(ListSerializer(HistoryHit.serializer()), r.data)

    private fun docHits(e: Engine, r: CommandResult): List<DocHit> =
        AppJson.decodeFromString(ListSerializer(DocHit.serializer()), r.data)

    private suspend fun statusOf(e: Engine): SemanticProgress =
        AppJson.decodeFromString(SemanticProgress.serializer(), e.call(Command.SemanticStatus).data)

    /** 最后一条带工具结果的聊天请求里，工具那几条消息的正文（看 AI 到底收到了什么）。 */
    private fun toolText(): String {
        val req = chatRequests.lastOrNull { r ->
            r["messages"]!!.jsonArray.any { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "tool" }
        } ?: return ""
        return req["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.contentOrNull == "tool" }
            .joinToString("\n") { it["content"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    /** 假服务器收到的向量请求：一共几条、一次最多几条。 */
    private fun embedCounts(): List<Int> = embedRequests.map { it["input"]!!.jsonArray.size }

    private suspend fun send(e: Engine, conv: String, text: String, turn: Int) {
        e.call(Command.SendMessage(conv, text))
        waitIdle(e, conv, turn)
    }

    // ———————————————— 切段（不联网） ————————————————

    @Test
    fun chunksSplitLongText() {
        assertTrue(VectorText.chunks("").isEmpty())
        assertTrue(VectorText.chunks("   \n ").isEmpty())
        assertEquals(listOf("一句话。"), VectorText.chunks("  一句话。  "))
        // 去掉思考过程（成员回答里常有 <think>…</think>）
        assertEquals(listOf("结论是 1.5 大。"), VectorText.chunks("<think>1.12 看着更大，其实</think>结论是 1.5 大。"))
        assertEquals(listOf("前面一段。"), VectorText.chunks("前面一段。<think>想了很久</think>"))
        // 短的不切
        assertEquals(1, VectorText.chunks("很短的一句。").size)

        // 长的：一段最多 500 字，在句号处断，相邻两段重叠约 50 字
        val sentence = "这是第 N 句话，用来凑长度。"
        val text = (1..60).joinToString("") { sentence.replace("N", "$it") }
        val parts = VectorText.chunks(text)
        assertTrue(parts.size >= 2, "这么长应该切成几段：${parts.map { it.length }}")
        parts.forEach { assertTrue(it.length <= VectorText.PART, "一段最多 ${VectorText.PART} 字，实际 ${it.length}") }
        // 每一段都是原文里的一段（不丢字、不加字），合起来盖到结尾
        parts.forEachIndexed { i, p -> assertTrue(p in text, "第 ${i + 1} 段不是原文里的片段") }
        assertTrue(text.endsWith(parts.last().takeLast(10)), "最后一段要收到结尾：${parts.last().takeLast(20)}")
        // 相邻两段有重叠：后一段的开头能在前一段的结尾里找到
        for (i in 1 until parts.size) {
            assertTrue(parts[i].take(20) in parts[i - 1], "第 ${i + 1} 段没有和上一段重叠")
            assertTrue(parts[i - 1].takeLast(20) in parts[i], "第 ${i + 1} 段没有接上上一段的结尾")
        }
        // 完全没有句号的长文字照样切得开（硬切，正好两段满的加一段零头）
        val noStop = "啊".repeat(1200)
        assertEquals(listOf(500, 500, 300), VectorText.chunks(noStop).map { it.length })
    }

    // ———————————————— 挑哪家算向量（不联网） ————————————————

    @Test
    fun pickOrder() {
        fun st(vararg ids: String, settings: Settings = Settings(semantic = SemanticSettings(historyOn = true))) = AppState(
            providers = ids.map { ProviderConfig(id = "p-$it", presetId = it, name = it, baseUrl = "https://x", apiKey = "sk") },
            settings = settings,
        )
        assertEquals("p-zhipu", EmbedPick.pick(st("qianwen", "zhipu"))!!.providerId, "智谱排第一")
        assertEquals(EmbedModels.ZHIPU, EmbedPick.pick(st("qianwen", "zhipu"))!!.modelId)
        assertEquals("p-qianwen", EmbedPick.pick(st("qianwen", "custom"))!!.providerId, "没智谱就用千问")
        assertEquals("p-qianwen", EmbedPick.pick(st("qianwen"))!!.providerId)
        assertEquals(EmbedModels.QWEN, EmbedPick.pick(st("qianwen"))!!.modelId)
        // 没填 Key / 停用的不算
        assertNull(EmbedPick.pick(AppState(providers = listOf(ProviderConfig("p1", "zhipu", "智谱", "https://x")))))
        assertNull(EmbedPick.pick(AppState(providers = listOf(ProviderConfig("p1", "zhipu", "智谱", "https://x", "sk", enabled = false)))))
        // 这几家官方没有向量接口，挑不出来
        assertNull(EmbedPick.pick(st("deepseek", "minimax", "mimo")))
        // 「自定义」：模型名必须用户自己填，没填就不挑
        assertNull(EmbedPick.pick(st("custom")))
        val withModel = st("custom", settings = Settings(semantic = SemanticSettings(historyOn = true, model = "my-embed")))
        assertEquals("p-custom", EmbedPick.pick(withModel)!!.providerId)
        assertEquals("my-embed", EmbedPick.pick(withModel)!!.modelId)
        // 设置里指定了就用指定的；指定的那家停了 / 删了就回到自动
        val mine = st("zhipu", "qianwen", settings = Settings(semantic = SemanticSettings(historyOn = true, providerId = "p-qianwen", model = "text-embedding-v4")))
        assertEquals("p-qianwen", EmbedPick.pick(mine)!!.providerId)
        assertFalse(EmbedPick.isAuto(mine))
        assertTrue(EmbedPick.isAuto(st("zhipu", settings = Settings(semantic = SemanticSettings(providerId = "p-gone")))))
        assertTrue(EmbedPick.isAuto(st("zhipu", settings = Settings(semantic = SemanticSettings(providerId = "p-qianwen")))))
        // 一次发几条、发不发 dimensions
        assertEquals(64, EmbedModels.batchOf(ProviderConfig("a", "zhipu", "智谱", "https://x")))
        assertEquals(10, EmbedModels.batchOf(ProviderConfig("b", "qianwen", "千问", "https://x")))
        assertEquals(10, EmbedModels.batchOf(ProviderConfig("c", "custom", "自定义", "https://x")))
        assertTrue(EmbedModels.sendsDimensions(ProviderConfig("a", "zhipu", "智谱", "https://x")))
        assertTrue(EmbedModels.sendsDimensions(ProviderConfig("b", "qianwen", "千问", "https://x")))
        assertFalse(EmbedModels.sendsDimensions(ProviderConfig("c", "custom", "自定义", "https://x")))
        assertEquals(1024, EmbedModels.DIM)
        // 说明里写了哪家、多少钱
        assertTrue(EmbedPick.noteOf(ProviderConfig("a", "zhipu", "智谱", "https://x"), EmbedModels.ZHIPU).startsWith("智谱官方"))
        assertNull(Prices.of("qwen", "text-embedding-v4"), "千问的向量价格没查到，不给价")
    }

    // ———————————————— 算向量：分批、按 index 对回顺序、记账 ————————————————

    @Test
    fun zhipuBatchIs64() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        // 六条长消息，切出来 100 段左右（一次算不完 64 段，逼出分批）
        repeat(6) { i ->
            val long = (1..400).joinToString("") { "第$it 句话，写长一点，好看切段。 " }
            send(e, conv, long, i + 1)
        }
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        val total = withTimeout(30_000) {
            while (e.state.semanticProgress.history.total == 0) delay(50)
            e.state.semanticProgress.history.total
        }
        waitEmbed(e, "history", total)
        assertTrue(total > 64, "要够 64 段以上才试得出分批，实际 $total")
        val counts = embedCounts()
        assertEquals(total, counts.sum(), "每一段都算了一次（不重不漏）")
        assertEquals(64, counts.max(), "智谱一次最多 64 条")
        assertTrue(counts.dropLast(1).all { it == 64 }, "前面每一批都是满的：$counts")
        assertStrict()
        // 进度和 SemanticStatus 指令给界面的是同一份
        val p = e.state.semanticProgress.history
        assertEquals("zhipu", p.platform)
        assertEquals(EmbedModels.ZHIPU, p.model)
        assertEquals(EmbedModels.DIM, p.dim)
        assertEquals(p, statusOf(e).history)
        assertFalse(p.stale)
        assertTrue(p.text.startsWith("算好了"), p.text)
        // 再叫一次（没有新东西）不会重算、也不再发请求
        val n = embedRequests.size
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true, docsOn = true))))
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        delay(500)
        assertEquals(n, embedRequests.size, "已经算过的段不再算一遍")
        assertStrict()
    }

    @Test
    fun qwenBatchIs10() = runBlocking {
        val e = engine("qianwen")
        val conv = chatConv(e)
        // 三条两千字的消息：切出来 20 段左右
        repeat(3) { i ->
            val long = (1..120).joinToString("") { "第$it 句话，凑够十几段就行。 " }
            send(e, conv, long, i + 1)
        }
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        val total = withTimeout(30_000) {
            while (e.state.semanticProgress.history.total == 0) delay(50)
            e.state.semanticProgress.history.total
        }
        waitEmbed(e, "history", total)
        assertTrue(total > 10, "要够 10 段以上才试得出分批，实际 $total")
        val counts = embedCounts()
        assertEquals(total, counts.sum())
        assertEquals(10, counts.max(), "千问一次最多 10 条")
        assertTrue(counts.dropLast(1).all { it == 10 }, "前面每一批都是满的：$counts")
        assertStrict()
        assertEquals("text-embedding-v4", e.state.semanticProgress.history.model)
        assertEquals(EmbedModels.DIM, e.state.semanticProgress.history.dim)
    }

    /** 「自定义」不发 dimensions，维度就用它自己返回的（第一次算回来的长度）。 */
    @Test
    fun customProviderKeepsItsOwnDim() = runBlocking {
        val e = engine()
        // 只留一个「自定义」服务商（就是 chat 用的 p1），模型名要用户自己填
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "${base()}/v1", "sk-test", listOf(ModelInfo("fake-chat")))))
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true, model = "my-embed"))))
        val total = withTimeout(30_000) {
            while (e.state.semanticProgress.history.total == 0) delay(50)
            e.state.semanticProgress.history.total
        }
        waitEmbed(e, "history", total)
        assertStrict()
        assertEquals(1, embedRequests.size)
        assertEquals("my-embed", embedRequests.single()["model"]!!.jsonPrimitive.content)
        assertNull(embedRequests.single()["dimensions"], "「自定义」不发 dimensions")
        assertEquals(8, e.state.semanticProgress.history.dim, "维度用它自己返回的（假服务器回 8 维）")
        assertEquals("custom", e.state.semanticProgress.history.platform)
    }

    /** 返回顺序打乱了也按 data[].index 对回来（假服务器每次都反着回）。 */
    @Test
    fun orderRecoveredByIndex() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        val mine = "咱们下周三碰个头聊一下新方案"          // 轴 0
        val other = "预付款打过来了吗"                      // 轴 1
        send(e, conv, mine, 1)
        send(e, conv, other, 2)
        assertTrue(shuffle, "假服务器一直是打乱顺序回的")
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        val total = withTimeout(30_000) {
            while (e.state.semanticProgress.history.total == 0) delay(50)
            e.state.semanticProgress.history.total
        }
        waitEmbed(e, "history", total)
        assertStrict()
        // 问「商量开会的事」：轴 0 的那条要排第一；顺序对错了就会把轴 1 的那条排到前面
        val hits = historyHits(e, e.call(Command.SearchHistory("什么时候商量开会的事", 2)))
        assertTrue(hits.isNotEmpty())
        assertTrue(mine in hits.first().snippet, "对错号了，第一条是：${hits.first().snippet}")
    }

    @Test
    fun billsEmbeddingByTokens() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        val total = withTimeout(30_000) {
            while (e.state.semanticProgress.history.total == 0) delay(50)
            e.state.semanticProgress.history.total
        }
        waitEmbed(e, "history", total)
        assertStrict()
        assertEquals(2, total, "一条用户消息 + 一条回答，各一段")
        val recs = Ledger(File(dir, "usage")).since(0).filter { it.kind == UsageKinds.EMBED }
        assertEquals(1, recs.size, "一批一次账")
        val r = recs.single()
        assertEquals("p-zhipu", r.providerId)
        assertEquals("zhipu 家", r.provider)
        assertEquals("zhipu", r.platform)
        assertEquals(EmbedModels.ZHIPU, r.model)
        assertEquals(embedRequests.single()["input"]!!.jsonArray.sumOf { it.jsonPrimitive.content.length }, r.prompt, "token 用返回的 usage.prompt_tokens")
        assertEquals("按意思搜", UsageKinds.label(UsageKinds.EMBED))
        // 智谱 embedding-3：0.5 元 / 百万 token
        val price = assertNotNull(Prices.of("zhipu", EmbedModels.ZHIPU))
        assertTrue(abs(r.prompt * 0.5 / 1_000_000.0 - Prices.cost(r, price)!!) < 1e-12)
        assertTrue(Prices.describe(price).contains("0.5"))
    }

    // ———————————————— 出错、不记账 ————————————————

    @Test
    fun errorIn200FailsAndDoesNotBill() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)
        send(e, conv, "猫粮买哪个牌子好", 2)
        errorIn200 = true
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        withTimeout(30_000) { while (e.state.bgProblems.none { it.job == BgJob.EMBED }) delay(50) }
        // 服务商没认下这次请求（200 里是 error），不能记账
        assertEquals(0, Ledger(File(dir, "usage")).since(0).count { it.kind == UsageKinds.EMBED })
        val p = e.state.semanticProgress.history
        assertFalse(p.running)
        assertEquals(0, p.done)
        assertTrue("向量算不出来" in p.error, p.error)
        val problem = e.state.bgProblems.single { it.job == BgJob.EMBED }
        assertEquals(BgJob.EMBED, problem.job)
        assertTrue("按意思搜" in problem.noticeText(), problem.noticeText())
        assertTrue("照旧能按字面搜" in problem.noticeText(), "要告诉用户还能按字面搜")

        // 算出错了：搜索照旧只按字面
        val hits = historyHits(e, e.call(Command.SearchHistory("什么时候开会", 5)))
        assertEquals(1, hits.size, "只有字面命中的那条")
        assertTrue("什么时候开会" in hits.single().snippet)

        // 好了以后算出结果、出错提示自己消失，账也记上
        errorIn200 = false
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true, docsOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.history.total == 0) delay(50) }
        waitEmbed(e, "history", e.state.semanticProgress.history.total)
        assertEquals(0, e.state.bgProblems.count { it.job == BgJob.EMBED }, "成功一次出错提示自己消失")
        assertTrue(e.state.semanticProgress.history.error.isBlank())
        assertEquals(1, Ledger(File(dir, "usage")).since(0).count { it.kind == UsageKinds.EMBED })
    }

    // ———————————————— 补算：换了模型整份作废、删了消息清掉、改了文件重算 ————————————————

    @Test
    fun modelChangeInvalidatesWholeSection() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)
        send(e, conv, "咱们下周三碰个头聊一下新方案", 2)
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.history.total == 0) delay(50) }
        val total = e.state.semanticProgress.history.total
        waitEmbed(e, "history", total)
        assertStrict()
        assertEquals(total, embedCounts().sum(), "第一次整份算完")
        // 换个模型名（同一家）：不同模型算出来的向量不在一个空间里，不能混着用。
        // 但不自动作废重算（那样会悄悄再花一遍钱，主会话审查 10-12）：只标「要重新算」，搜索先只按字面
        // （指定了服务商和模型才会按这个模型算；只填模型名、服务商留空时是「自动挑」，用的还是这家的默认模型）
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true, providerId = "p-zhipu", model = "embedding-3-x"))))
        withTimeout(30_000) { while (!e.state.semanticProgress.history.stale) delay(20) }
        delay(300)
        assertEquals(total, embedCounts().sum(), "换了模型不自动重算（不花钱）")
        assertTrue("重新算一遍" in e.state.semanticProgress.history.text, e.state.semanticProgress.history.text)
        e.call(Command.SearchHistory("碰头", 8))
        assertEquals(total, embedCounts().sum(), "模型对不上时搜索只按字面，不去算问题的向量")
        // 用户点了「重新算一遍」才整份重算
        e.call(Command.SemanticRebuild("history"))
        withTimeout(30_000) { while (embedCounts().sum() < total * 2) delay(20) }
        waitEmbed(e, "history", total)
        assertStrict()
        assertEquals(total * 2, embedCounts().sum(), "换了模型要把每一段都重算，不能留着旧的")
        assertEquals("embedding-3-x", embedRequests.last()["model"]!!.jsonPrimitive.content)
        assertEquals("embedding-3-x", e.state.semanticProgress.history.model)
        // 旧模型的向量已经不在清单里了
        val idx = AppJson.decodeFromString(VecIndex.serializer(), File(File(dir, "vectors"), "history.json").readText(Charsets.UTF_8))
        assertEquals("embedding-3-x", idx.model)
        assertEquals(total, idx.entries.size)
    }

    @Test
    fun deletedMessageIsCleaned() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)
        send(e, conv, "咱们下周三碰个头聊一下新方案", 2)
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.history.total == 0) delay(50) }
        val total = e.state.semanticProgress.history.total
        waitEmbed(e, "history", total)
        assertStrict()
        val target = e.store.messages.value[conv]!!.first { "碰个头" in it.content }
        val requestsBefore = embedRequests.size

        // 删了这条消息：下次补算时它那一段要被清掉（不是留着不管）
        e.call(Command.DeleteMessage(conv, target.id))
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = false))))  // 先关（停下正在算的）
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))   // 再开：这次没有新东西要算，只清掉不在了的
        waitEmbed(e, "history", total - 1)
        assertStrict()
        assertEquals(requestsBefore, embedRequests.size, "没有新内容，不该重新算")
        val idx = AppJson.decodeFromString(VecIndex.serializer(), File(File(dir, "vectors"), "history.json").readText(Charsets.UTF_8))
        assertTrue(idx.entries.none { it.source.messageId == target.id }, "删掉的消息不该还留在清单里")
        assertEquals(total - 1, idx.entries.size)
        // 按意思也搜不到它了
        val hits = historyHits(e, e.call(Command.SearchHistory("什么时候商量开会的事", 5)))
        assertTrue(hits.none { it.messageId == target.id })
    }

    @Test
    fun changedDocumentIsRecomputed() = runBlocking {
        val e = engine("zhipu")
        val folder = File(dir, "资料").apply { mkdirs() }
        val f = File(folder, "开会.txt")
        f.writeText("下周三碰个头聊方案", Charsets.UTF_8)
        e.call(Command.SaveSettings(e.state.settings.copy(docs = DocSettings(folders = listOf(folder.path)))))
        withTimeout(20_000) { while (e.state.docs.count < 1 || e.state.docs.scanning) delay(50) }
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(docsOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.docs.total == 0) delay(50) }
        waitEmbed(e, "docs", 1)
        assertStrict()
        assertEquals(1, embedRequests.size)
        assertTrue("碰个头" in embedRequests.single()["input"]!!.jsonArray.single().jsonPrimitive.content)

        // 改了文件：修改时间要跟着变（同一个文件改了就重算它这一段）
        f.writeText("合同的付款时间和违约金", Charsets.UTF_8)
        f.setLastModified(System.currentTimeMillis() + 5_000)
        e.call(Command.DocRescan)
        withTimeout(30_000) { while (embedRequests.size < 2) delay(50) }
        withTimeout(30_000) { while (e.state.semanticProgress.docs.running) delay(50) }
        assertStrict()
        val sent = embedRequests.last()["input"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue("违约金" in sent, sent)
        val idx = AppJson.decodeFromString(VecIndex.serializer(), File(File(dir, "vectors"), "docs.json").readText(Charsets.UTF_8))
        assertEquals(1, idx.entries.size, "一个文件一段，替换掉旧的那段，不是并排留着")
        assertEquals(f.path, idx.entries.single().source.path)
        // 文档页（按字面）搜到的是新内容，旧内容找不到了
        val now = docHits(e, e.call(Command.DocSearch("违约金")))
        assertEquals(1, now.size)
        assertEquals(f.path, now.single().path)
        assertTrue("违约金" in now.single().snippet, now.single().snippet)
        assertEquals(0, docHits(e, e.call(Command.DocSearch("碰个头"))).size, "旧内容不该还找得到")
    }

    // ———————————————— 向量库：写到一半崩了也不坏 ————————————————

    @Test
    fun storeSurvivesTruncatedOrBrokenFiles() {
        val vdir = File(dir, "vectors").apply { mkdirs() }
        val store = VectorStore(vdir)
        val entries = (0 until 3).map { VecEntry(VecSource(convId = "c1", messageId = "m$it"), "h$it") }
        val vectors = List(3) { i -> FloatArray(4) { (it + i).toFloat() } }
        store.write(VectorStore.HISTORY, "zhipu", "embedding-3", 4, entries, vectors, 111)
        assertEquals(3, store.data(VectorStore.HISTORY).entries.size)
        assertEquals(4, store.data(VectorStore.HISTORY).dim)
        assertEquals(111, store.data(VectorStore.HISTORY).builtAt)
        assertEquals(entries, store.index(VectorStore.HISTORY).entries)

        // 崩在写新一版的两步之间：新 bin 写好了、json 还是旧的——旧 json 要指着旧 bin，向量和段一一对上
        // （以前只有一个 history.bin，这时旧清单会对上新 bin；新 bin 的顺序变了就悄悄对错号，主会话审查 10-12）
        val oldJson = File(vdir, "history.json").readText(Charsets.UTF_8)
        val oldBinName = AppJson.decodeFromString(VecIndex.serializer(), oldJson).bin
        val oldBinBytes = File(vdir, oldBinName).readBytes()
        store.write(VectorStore.HISTORY, "zhipu", "embedding-3", 4, entries.reversed(), vectors.reversed(), 112)
        File(vdir, oldBinName).writeBytes(oldBinBytes) // 崩在删旧 bin 之前
        File(vdir, "history.json").writeText(oldJson, Charsets.UTF_8) // 崩在 json 改名之前
        val crashed = VectorStore(vdir).data(VectorStore.HISTORY)
        assertEquals(entries, crashed.entries)
        for (i in 0 until 3) assertTrue(vectors[i].contentEquals(crashed.vectors[i]), "第 ${i + 1} 段的向量要对得上它自己")
        store.write(VectorStore.HISTORY, "zhipu", "embedding-3", 4, entries, vectors, 111)
        assertEquals(1, vdir.listFiles { f -> f.name.endsWith(".bin") }!!.size, "旧的 bin 写完就删掉")

        // bin 被截短（磁盘出过问题）：前面对得上的照用
        val bin = File(vdir, AppJson.decodeFromString(VecIndex.serializer(), File(vdir, "history.json").readText(Charsets.UTF_8)).bin)
        val all = bin.readBytes()
        bin.writeBytes(all.copyOf(4 * 4 * 2))   // 只有两条的量
        val half = VectorStore(vdir)
        assertEquals(2, half.data(VectorStore.HISTORY).entries.size, "对得上的两段照用")
        assertEquals(2, half.search(VectorStore.HISTORY, FloatArray(4) { 1f }, 10).size)

        // bin 干脆没了
        bin.delete()
        assertTrue(VectorStore(vdir).data(VectorStore.HISTORY).entries.isEmpty())

        // json 坏了：当空的，不抛异常
        bin.writeBytes(all)
        File(vdir, "history.json").writeText("{坏了", Charsets.UTF_8)
        assertTrue(VectorStore(vdir).data(VectorStore.HISTORY).entries.isEmpty())

        // 修好以后（重新算一份）照常
        val again = VectorStore(vdir)
        again.write(VectorStore.HISTORY, "zhipu", "embedding-3", 4, entries, vectors, 222)
        val read = VectorStore(vdir)
        assertEquals(3, read.data(VectorStore.HISTORY).entries.size)
        assertEquals(222, read.data(VectorStore.HISTORY).builtAt)
        assertTrue(read.data(VectorStore.HISTORY).matches("zhipu", "embedding-3", 4))
        assertFalse(read.data(VectorStore.HISTORY).matches("zhipu", "别的模型", 4))
        assertFalse(read.data(VectorStore.HISTORY).matches("qwen", "embedding-3", 4))
        // 维度对不上也不作数（换了 dimensions）
        assertFalse(read.data(VectorStore.HISTORY).matches("zhipu", "embedding-3", 1024))
        assertTrue(read.data(VectorStore.HISTORY).matches("zhipu", "embedding-3", 0), "不知道维度时不挑维度")
        // 条数和向量对不上、维度不一致：写的时候就要拦住
        var threw = false
        try { read.write(VectorStore.HISTORY, "zhipu", "embedding-3", 4, entries, vectors.take(2), 1) } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw, "条数对不上要拦")
        threw = false
        try { read.write(VectorStore.HISTORY, "zhipu", "embedding-3", 4, entries, listOf(FloatArray(3), FloatArray(4), FloatArray(4)), 1) } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw, "维度对不上要拦")
        assertEquals(3, VectorStore(vdir).data(VectorStore.HISTORY).entries.size, "拦下来的写入没有动盘上的数据")
    }

    // ———————————————— 搜：按意思 + 按字面合起来 ————————————————

    @Test
    fun semanticSearchFindsRewordedMessage() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        val reworded = "咱们下周三碰个头聊一下新方案"
        send(e, conv, reworded, 1)
        send(e, conv, "猫粮买哪个牌子好", 2)
        send(e, conv, "上次说的那个什么时候开会", 3)

        // 开关关着：和以前完全一样，只按字面，也不联网
        val off = historyHits(e, e.call(Command.SearchHistory("什么时候开会", 2)))
        assertEquals(1, off.size, "只按字面就只有那条真说了「什么时候开会」的")
        assertTrue("上次说的那个什么时候开会" in off.single().snippet)
        assertEquals(0, embedRequests.size, "开关关着不该去算向量")

        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.history.total == 0) delay(50) }
        val total = e.state.semanticProgress.history.total
        waitEmbed(e, "history", total)
        assertStrict()
        val on = historyHits(e, e.call(Command.SearchHistory("什么时候开会", 2)))
        assertEquals(2, on.size, "合起来搜：字面命中的那条 + 换了说法的「碰个头」")
        assertTrue("上次说的那个什么时候开会" in on.first().snippet, "字面命中的还是排前面")
        assertTrue(reworded in on.last().snippet, "换了说法的那条要按意思搜出来：${on.map { it.snippet }}")
        assertTrue(on.none { "猫粮" in it.snippet }, "不沾边的不该被带进来")
        // 问句本身也算了一次向量（一次一条）
        assertEquals(1, embedRequests.last()["input"]!!.jsonArray.size)

        // 关掉开关：又回到只按字面（算好的向量留着，不清）
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings())))
        delay(400)
        val back = historyHits(e, e.call(Command.SearchHistory("什么时候开会", 2)))
        assertEquals(1, back.size, "关掉以后跟以前一模一样")
        assertEquals(total, e.state.semanticProgress.history.done, "算好的向量留着，不因为关开关就删")
        assertFalse(e.state.semanticProgress.history.running)
    }

    /** 工具那条路：AI 调 search_history / search_documents 时拿到的就是合起来的结果，步骤上写明这次怎么搜的。 */
    @Test
    fun toolsUseMergedResults() = runBlocking {
        val e = engine("zhipu")
        val folder = File(dir, "资料").apply { mkdirs() }
        File(folder, "甲.txt").writeText("合同：付款时间 2026 年 11 月 30 日", Charsets.UTF_8)
        File(folder, "乙.txt").writeText("预付款打过来了吗，违约金几个点", Charsets.UTF_8)
        e.call(Command.SaveSettings(e.state.settings.copy(docs = DocSettings(folders = listOf(folder.path)))))
        withTimeout(20_000) { while (e.state.docs.count < 2 || e.state.docs.scanning) delay(50) }
        val conv = chatConv(e)
        send(e, conv, "咱们下周三碰个头聊一下新方案", 1)
        send(e, conv, "上次说的那个什么时候开会", 2)

        // 开关关着：工具拿到的是字面搜的老样子
        send(e, conv, "翻翻以前的聊天", 3)
        val offText = toolText()
        assertTrue("碰个头" !in offText, "开关关着不该按意思搜到它：$offText")
        assertEquals("这次只按字面搜", e.store.messages.value[conv]!!.last().tools.single { it.kind == "history" }.note)

        send(e, conv, "找找文档", 4)
        val offDoc = toolText()
        assertTrue("乙.txt" !in offDoc, "开关关着只按字面：$offDoc")
        assertTrue("甲.txt" in offDoc)

        // 两份都打开
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true, docsOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.history.total == 0 || e.state.semanticProgress.docs.total == 0) delay(50) }
        val ht = e.state.semanticProgress.history.total
        waitEmbed(e, "history", ht)
        val dt = e.state.semanticProgress.docs.total
        waitEmbed(e, "docs", dt)
        assertStrict()

        send(e, conv, "再翻翻以前的聊天", 5)
        val onText = toolText()
        assertTrue("碰个头" in onText, "按意思搜到了换了说法的那条：$onText")
        assertTrue("上次说的那个什么时候开会" in onText, "字面命中的还在")
        val step = e.store.messages.value[conv]!!.last().tools.single { it.kind == "history" }
        assertEquals("什么时候开会", step.input)
        assertTrue("合起来搜" in step.note, step.note)

        send(e, conv, "再找找文档", 6)
        val onDoc = toolText()
        assertTrue("甲.txt" in onDoc, "字面命中的文档还在：$onDoc")
        assertTrue("乙.txt" in onDoc, "按意思搜到只有「预付款」的那个文件：$onDoc")
        assertTrue("合起来搜" in e.store.messages.value[conv]!!.last().tools.single { it.kind == "doc" }.note)
    }

    // ———————————————— 估算（不联网、不花钱） ————————————————

    @Test
    fun estimateSeparatesBothAndStaysOffline() = runBlocking {
        val e = engine("zhipu")
        val folder = File(dir, "资料").apply { mkdirs() }
        val docText = "付款日期：2026年11月30日"
        File(folder, "合同.txt").writeText(docText, Charsets.UTF_8)
        e.call(Command.SaveSettings(e.state.settings.copy(docs = DocSettings(folders = listOf(folder.path)))))
        withTimeout(20_000) { while (e.state.docs.count < 1 || e.state.docs.scanning) delay(50) }
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)

        val r = e.call(Command.SemanticEstimate)
        assertTrue(r.ok, r.message)
        val est = AppJson.decodeFromString(SemanticEstimateResult.serializer(), r.data)
        assertEquals("p-zhipu", est.providerId)
        assertEquals("zhipu 家", est.providerName)
        assertEquals(EmbedModels.ZHIPU, est.model)
        assertTrue(est.label.endsWith("（zhipu 家）"), est.label)
        // 聊天记录：用户那句 6 字 + 回答 5 字，各一段
        assertEquals(11, est.history.chars)
        assertEquals(2, est.history.chunks)
        assertEquals((11 * EmbedModels.TOKENS_PER_CHAR).toLong(), est.history.tokens, "按智谱实测每字约 0.75 token 粗估")
        // 文档单独算：条数 / 字数和文档库里的一致
        val hit = docHits(e, e.call(Command.DocSearch(""))).single()
        assertEquals(hit.chars, est.docs.chars)
        assertEquals(1, est.docs.chunks)
        assertTrue(est.docs.chars > 0)
        // 钱按价格表估：智谱 0.5 元 / 百万 token
        val cost = assertNotNull(est.history.cost, "价格表里有 embedding-3，估得出钱")
        assertTrue(abs(est.history.tokens * 0.5 / 1_000_000.0 - cost) < 1e-12)
        assertTrue(est.priceText.contains("0.5"), est.priceText)
        assertTrue("粗估" in est.tokenNote, est.tokenNote)
        // 估算不联网、不算向量、不记账、不改进度
        assertEquals(0, embedRequests.size, "估算不发请求")
        assertEquals(0, Ledger(File(dir, "usage")).since(0).count { it.kind == UsageKinds.EMBED })
        assertEquals(SemanticProgress(), e.state.semanticProgress)

        // 千问：价格表里没有 text-embedding-v4，钱算不出来（token 照给）
        val q = engine("qianwen", root = File(dir, "第二台").apply { mkdirs() })
        val qEst = AppJson.decodeFromString(SemanticEstimateResult.serializer(), q.call(Command.SemanticEstimate).data)
        assertEquals(EmbedModels.QWEN, qEst.model)
        assertNull(qEst.history.cost)
        assertTrue("价格表里没有" in qEst.priceText, qEst.priceText)

        // 一家能算向量的都没有：不算错，说清挑不出来
        val none = engine(root = File(dir, "第三台").apply { mkdirs() })
        val nEst = AppJson.decodeFromString(SemanticEstimateResult.serializer(), none.call(Command.SemanticEstimate).data)
        assertTrue(nEst.model.isBlank(), nEst.label)
        assertTrue(nEst.providerId.isBlank())
        assertNull(nEst.history.cost)
    }

    // ———————————————— 开关 / 重新算 / 旧版手机的设置 ————————————————

    @Test
    fun rebuildClearsAndRecounts() = runBlocking {
        val e = engine("zhipu")
        val conv = chatConv(e)
        send(e, conv, "什么时候开会", 1)
        e.call(Command.SaveSettings(e.state.settings.copy(semantic = SemanticSettings(historyOn = true))))
        withTimeout(30_000) { while (e.state.semanticProgress.history.total == 0) delay(50) }
        val total = e.state.semanticProgress.history.total
        waitEmbed(e, "history", total)
        assertStrict()

        val r = e.call(Command.SemanticRebuild("history"))
        assertTrue("聊天记录" in r.message, r.message)
        withTimeout(30_000) { while (embedCounts().sum() < total * 2) delay(20) }
        waitEmbed(e, "history", total)
        assertStrict()
        assertEquals(total * 2, embedCounts().sum(), "重新算一遍：每一段都再算一次")
        // 空 scope = 两份都作废
        val both = e.call(Command.SemanticRebuild(""))
        assertTrue("聊天记录" in both.message && "文档" in both.message, both.message)
    }

    @Test
    fun oldPhoneSchemaKeepsSemantic() = runBlocking {
        val e = engine("zhipu")
        val s = e.state.settings
        assertTrue(e.call(Command.SaveSettings(s.copy(semantic = SemanticSettings(historyOn = true, docsOn = true, providerId = "p-zhipu", model = "embedding-3")))).ok)
        assertEquals(5, Settings.SCHEMA, "1.7.0 加了语音和按意思搜，schema 是 5")
        assertTrue(e.state.settings.semantic.historyOn)

        // 1.6.0 的手机（schema = 4）不认识这块，发来的是默认值：电脑上改过的要保留
        assertTrue(e.call(Command.SaveSettings(s.copy(schema = 4, semantic = SemanticSettings()))).ok)
        assertEquals(SemanticSettings(historyOn = true, docsOn = true, providerId = "p-zhipu", model = "embedding-3"), e.state.settings.semantic)

        // 这一版的界面（schema = 5）能关掉
        assertTrue(e.call(Command.SaveSettings(s.copy(schema = 5, semantic = SemanticSettings()))).ok)
        assertEquals(SemanticSettings(), e.state.settings.semantic)

        // 设置本身存得下、读得回来（旧版存下来的设置里没有这块：读出来是默认值）
        val text = AppJson.encodeToString(Settings.serializer(), s.copy(semantic = SemanticSettings(historyOn = true, model = "my-embed")))
        assertTrue("\"semantic\"" in text, text)
        assertEquals(SemanticSettings(historyOn = true, model = "my-embed"), AppJson.decodeFromString(Settings.serializer(), text).semantic)
        assertEquals(SemanticSettings(), AppJson.decodeFromString(Settings.serializer(), """{"proxy":"127.0.0.1:10809"}""").semantic)
        assertEquals(SemanticSettings(historyOn = true), AppJson.decodeFromString(SemanticSettings.serializer(), """{"historyOn":true}"""))
        // 旧版手机（不认识新字段）读出来的也要是默认值
        assertEquals(SemanticSettings(), AppJson.decodeFromString(SemanticSettings.serializer(), """{"historyOn":false,"future":1}"""))
    }
}
