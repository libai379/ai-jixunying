package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.PageBrowser
import com.guixing.jixunying.engine.Quotes
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.WeixinBridge
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 微信里引用历史消息（2026-11-01 用户：「引用话语或者图片、视频等等，就是引用历史记录，它好像读不到，而且还报错了」）。
 * 新版微信的 ref_msg 可能只带编号（官方插件 docs/quote-cache_zh_CN.md），按编号到聊天记录里找；
 * 还有服务商过载（server_is_overloaded）时自己等一等再试、不把 JSON 甩给用户。假的模型、iLink、CDN 都在这里。
 */
class WeixinQuoteTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val wxInbox = ConcurrentLinkedQueue<String>()
    /** 发到微信的每一条：消息内容、给它的编号。 */
    private val wxSent = Collections.synchronizedList(mutableListOf<Pair<JsonObject, String>>())
    /** 编号都超过 2^63，试大整数不丢精度。 */
    private val wxIds = AtomicInteger(0)
    private val cdnGets = AtomicInteger(0)
    /** 接下来几次模型请求报错（报什么、什么状态码）。 */
    private val busyLeft = AtomicInteger(0)
    @Volatile private var busyBody = ""
    @Volatile private var busyStatus = HttpStatusCode.InternalServerError
    private val modelCalls = AtomicInteger(0)
    @Volatile private var asrCalls = 0
    private val wxKey = ByteArray(16) { (it * 7 + 3).toByte() }
    private val keyB64 = Base64.getEncoder().encodeToString(wxKey)
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    private fun base() = "http://127.0.0.1:$port"

    /** 假浏览器：只管解视频；画面、声音都算解得开。 */
    private inner class FakeVideoBrowser : PageBrowser {
        val framesAsked = AtomicInteger(0)
        override suspend fun fileFrames(file: File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double> {
            framesAsked.incrementAndGet()
            return List(count) { png } to 12.3
        }
        override suspend fun fileAudioPcm(file: File, maxSeconds: Int): Pair<ByteArray, Double> {
            val n = 16000 * 3
            val b = ByteArray(n * 2)
            for (i in 0 until n) { val v = (Math.sin(i / 8.0) * 8000).toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
            return b to 12.3
        }
        override suspend fun snapshot(url: String, script: String, settleMs: Long) = JsonObject(emptyMap())
        override suspend fun cookieNames(domain: String) = emptySet<String>()
        override suspend fun showForLogin(url: String) {}
        override suspend fun hide() {}
        override suspend fun audioPcm(mediaUrl: String, maxSeconds: Int) = ByteArray(0) to 0.0
        override fun running() = true
        override fun close() {}
    }

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-wxquote").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    modelCalls.incrementAndGet()
                    if (busyLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                        call.respondText(busyBody, ContentType.Application.Json, busyStatus)
                        return@post
                    }
                    requests += req
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val who = Regex("你是「(.+?)」").find(contentText(msgs.first()["content"]))?.groupValues?.get(1) ?: "记录员"
                    val lastUser = msgs.last { it["role"]?.jsonPrimitive?.content == "user" }
                    val wantsCat = "画一只猫" in contentText(lastUser["content"])
                    val drew = msgs.any { it["role"]?.jsonPrimitive?.content == "tool" }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun delta(d: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$d}]}\n\n"); flush() }
                        if (wantsCat && !drew && req["tools"]?.toString()?.contains("generate_image") == true) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive("generate_image"), "arguments" to JsonPrimitive("""{"prompt":"一只猫"}""")))
                            val tc = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t1"), "type" to JsonPrimitive("function"), "function" to fn))
                            delta(JsonObject(mapOf("tool_calls" to JsonArray(listOf(tc)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        } else {
                            delta(JsonObject(mapOf("content" to JsonPrimitive("${who}答：好的。"))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                        }
                        write("data: [DONE]\n\n"); flush()
                    }
                }
                post("/v1/images/generations") {
                    call.receiveText()
                    call.respondText("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}""", ContentType.Application.Json)
                }
                post("/v1/audio/transcriptions") {
                    call.receiveText()
                    asrCalls++
                    call.respondText("""{"text":"视频里说大家好。"}""", ContentType.Application.Json)
                }
                post("/ilink/bot/get_bot_qrcode") { call.respondText("""{"qrcode":"qr1","qrcode_img_content":"https://liteapp.weixin.qq.com/q/qr1"}""", ContentType.Application.Json) }
                get("/ilink/bot/get_qrcode_status") {
                    call.respondText("""{"status":"confirmed","bot_token":"tok","ilink_bot_id":"bot1@im.bot","ilink_user_id":"me@im.wechat","baseurl":"http://127.0.0.1:$port"}""", ContentType.Application.Json)
                }
                post("/ilink/bot/msg/notifystart") { call.respondText("{}", ContentType.Application.Json) }
                post("/ilink/bot/msg/notifystop") { call.respondText("{}", ContentType.Application.Json) }
                post("/ilink/bot/getupdates") {
                    call.receiveText()
                    val m = wxInbox.poll()
                    if (m == null) { delay(100); call.respondText("""{"ret":0,"msgs":[],"get_updates_buf":"b0"}""", ContentType.Application.Json) }
                    else call.respondText("""{"ret":0,"msgs":[$m],"get_updates_buf":"b1"}""", ContentType.Application.Json)
                }
                post("/ilink/bot/getconfig") { call.receiveText(); call.respondText("""{"ret":0,"typing_ticket":"tt"}""", ContentType.Application.Json) }
                post("/ilink/bot/sendtyping") { call.receiveText(); call.respondText("""{"ret":0}""", ContentType.Application.Json) }
                post("/ilink/bot/sendmessage") {
                    val id = (BigInteger("18446744073709551000") + BigInteger.valueOf(wxIds.incrementAndGet().toLong())).toString()
                    wxSent += Json.parseToJsonElement(call.receiveText()).jsonObject["msg"]!!.jsonObject to id
                    call.respondText("""{"ret":0,"message_id":$id}""", ContentType.Application.Json)
                }
                post("/ilink/bot/getuploadurl") { call.receiveText(); call.respondText("""{"ret":0,"upload_param":"up1"}""", ContentType.Application.Json) }
                get("/c2c/download") {
                    cdnGets.incrementAndGet()
                    call.respondBytes(WeixinBridge.aesEcb(png, wxKey, encrypt = true), ContentType.Application.OctetStream)
                }
                post("/c2c/upload") {
                    call.receiveText()
                    call.response.headers.append("x-encrypted-param", "dl1")
                    call.respondText("")
                }
            }
        }.also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        fake.stop(0, 0)
        dir.deleteRecursively()
    }

    private fun contentText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        else -> ""
    }

    private fun lastAsk(): JsonObject = requests.last().let { r -> r["messages"]!!.jsonArray.map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "user" } }
    private fun lastAskText() = contentText(lastAsk()["content"])
    private fun lastAskImages() = (lastAsk()["content"] as? JsonArray)?.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image_url" } ?: 0

    private fun sentText(m: JsonObject) = m["item_list"]!!.jsonArray[0].jsonObject["text_item"]?.jsonObject?.get("text")?.jsonPrimitive?.content

    private fun inMsg(id: String, vararg items: String) =
        """{"message_id":$id,"from_user_id":"me@im.wechat","to_user_id":"bot1@im.bot","message_type":1,"context_token":"ctx$id","item_list":[${items.joinToString(",")}]}"""

    private fun text(s: String) = """{"type":1,"text_item":{"text":"$s"}}"""
    private fun quoting(s: String, ref: String) = """{"type":1,"text_item":{"text":"$s"},"ref_msg":$ref}"""
    private val imageItem get() = """{"type":2,"image_item":{"media":{"encrypt_query_param":"img1","aes_key":"$keyB64","encrypt_type":1}}}"""
    private val videoItem get() = """{"type":5,"video_item":{"media":{"encrypt_query_param":"v1","aes_key":"$keyB64","encrypt_type":1},"video_size":32}}"""
    private val fileItem get() = """{"type":4,"file_item":{"media":{"encrypt_query_param":"f1","aes_key":"$keyB64","encrypt_type":1},"file_name":"报告.txt","len":"68"}}"""

    private class World(val e: Engine, val bridge: WeixinBridge, val browser: PageBrowser?)

    /** 一台绑好微信的电脑。memberNames 里全部回答微信。 */
    private suspend fun world(vararg memberNames: String, vision: Boolean = true, browser: PageBrowser? = null): World {
        val e = Engine(Storage(dir), browserFactory = { browser })
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "${base()}/v1", "sk-test", listOf(ModelInfo("fake-chat", vision = vision)))))
        val members = memberNames.mapIndexed { i, n -> Member("m$i", n, providerId = "p1", modelId = "fake-chat", bio = "$n 的定位") }
        members.forEach { e.call(Command.SaveMember(it)) }
        val s = e.state.settings
        e.call(Command.SaveSettings(s.copy(
            memory = s.memory.copy(enabled = false),
            relay = s.relay.copy(enabled = false),
            weixin = s.weixin.copy(enabled = true, memberIds = members.map { it.id }, webSearch = false),
        )))
        val bridge = WeixinBridge(e, File(dir, "weixin"), loginBase = base(), cdnBase = "${base()}/c2c")
        bridge.batchWaitMs = 30
        e.weixin = bridge
        bridge.start()
        assertTrue(e.call(Command.WeixinLogin).ok)
        withTimeout(10_000) { while (!e.state.weixin.status.startsWith("已连接")) delay(30) }
        return World(e, bridge, browser)
    }

    private suspend fun waitSent(n: Int) = withTimeout(20_000) { while (wxSent.size < n) delay(20) }

    private suspend fun history(e: Engine): List<Message> {
        val conv = e.state.conversations.filter { it.channel == "weixin:me@im.wechat" }.maxByOrNull { it.updatedAt }!!
        e.call(Command.LoadMessages(conv.id))
        return e.store.messages.value[conv.id]!!
    }

    /** 用户那张截图的情形：引用助理刚说的一句，ref_msg 只带编号。AI 要看得到被引用的话，界面上也看得到。 */
    @Test
    fun quoteAiReplyById() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("101", text("ai 味还有"))
        waitSent(1)
        val (reply, replyId) = wxSent[0]
        assertEquals("阿苗答：好的。", sentText(reply))
        assertTrue(replyId.length >= 20, "微信的编号是 64 位无符号整数：$replyId")
        val ai = withTimeout(5_000) { var a: Message; do { a = history(w.e).last(); delay(20) } while (a.channelParts.isEmpty()); a }
        assertEquals(replyId, ai.channelParts.single().id, "发出去的回答记下了微信给的编号，大整数不丢精度")
        assertEquals("", ai.channelParts.single().text, "文字和正文一样就不另存一份")

        wxSent.clear()
        wxInbox += inMsg("102", quoting("这句 ai 味最重", """{"svr_id":$replyId}"""))
        waitSent(1)
        val asked = lastAskText()
        assertTrue("> 阿苗 · " in asked, asked)
        assertTrue("阿苗答：好的。" in asked, "模型看得到被引用的话：$asked")
        assertTrue("这句 ai 味最重" in asked, asked)
        val mine = history(w.e).last { it.role == Role.USER }
        assertTrue(mine.content.startsWith("> 阿苗 · ") && mine.content.endsWith("\n\n这句 ai 味最重"), mine.content)
        assertEquals(listOf("102"), mine.channelParts.map { it.id })
        assertEquals(1, history(w.e).last().let { if (it.role == Role.AI) 1 else 0 })
        w.bridge.stop()
    }

    /** 引用自己以前发的文字；svr_id 写成字符串也认；引用块里的旧引用不再套一层。 */
    @Test
    fun quoteOwnTextAndNestedQuote() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("201", text("第一句话"))
        waitSent(1)
        wxSent.clear()
        wxInbox += inMsg("202", quoting("第二句", """{"svr_id":"201"}"""))
        waitSent(1)
        assertTrue("第一句话" in lastAskText())
        wxSent.clear()
        wxInbox += inMsg("203", quoting("第三句", """{"svr_id":"202"}"""))
        waitSent(1)
        val mine = history(w.e).last { it.role == Role.USER }
        assertTrue("第二句" in mine.content, mine.content)
        assertFalse("第一句话" in mine.content, "只引用那一句自己说的，不把它引用的再套进来：${mine.content}")
        w.bridge.stop()
    }

    /** 引用以前发的图：模型这一句就看得到那张图，文件不另存一份；另外连 CDN 都不用再下。 */
    @Test
    fun quoteImageById() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("301", imageItem, text("看看这张"))
        waitSent(1)
        val original = history(w.e).first { it.role == Role.USER }.attachments.single()
        assertEquals(AttachmentKind.IMAGE, original.kind)
        val gets = cdnGets.get()
        wxSent.clear()
        wxInbox += inMsg("302", quoting("这张图里是什么", """{"svr_id":301}"""))
        waitSent(1)
        val mine = history(w.e).last { it.role == Role.USER }
        assertEquals(listOf(original.id), mine.quoted.map { it.id }, "引用的还是原来那个文件")
        assertTrue(mine.attachments.isEmpty())
        assertEquals(gets, cdnGets.get(), "聊天记录里有，不用再从微信下载")
        assertTrue(lastAskImages() >= 1, "模型看得到图")
        assertTrue("看看这张" in lastAskText(), lastAskText())
        // 同一张图不重复带（原来那条和引用它的那条）
        assertEquals(1, requests.last()["messages"].toString().split("image_url").size / 2)
        w.bridge.stop()
    }

    /** 引用 AI 画的图：发图时记下了编号，引用时图带给模型（AI 自己说过的图平时不带）。 */
    @Test
    fun quoteGeneratedImageById() = runBlocking {
        val w = world("阿苗")
        w.e.call(Command.SaveProvider(ProviderConfig("pimg", "zhipu", "画图", "${base()}/v1", "k", listOf(ModelInfo("cogview-3-flash", imageGen = true, tools = false)))))
        wxInbox += inMsg("401", text("帮我画一只猫"))
        withTimeout(20_000) { while (wxSent.none { it.first["item_list"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content == "2" }) delay(20) }
        val imageId = wxSent.first { it.first["item_list"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content == "2" }.second
        // 先发图、再发文字（2026-11-02：文字先到，对方就问「图呢」，AI 又花钱重画）
        waitSent(2)
        assertEquals("2", wxSent[0].first["item_list"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content, "图先发")
        assertEquals("阿苗答：好的。", sentText(wxSent[1].first), "文字后发")
        val ai = withTimeout(5_000) { var a: Message; do { a = history(w.e).last { it.role == Role.AI }; delay(20) } while (a.channelParts.none { it.id == imageId }); a }
        val made = ai.attachments.single { it.kind == AttachmentKind.GENERATED_IMAGE }
        assertEquals(made.id, ai.channelParts.single { it.id == imageId }.attachmentId)
        wxSent.clear()
        wxInbox += inMsg("402", quoting("把这张改成蓝色的", """{"svr_id":$imageId}"""))
        waitSent(1)
        val mine = history(w.e).last { it.role == Role.USER }
        assertEquals(listOf(made.id), mine.quoted.map { it.id })
        assertTrue("[图片：${made.name}]" in mine.content, mine.content)
        assertTrue(lastAskImages() >= 1)
        w.bridge.stop()
    }

    /** 引用文件：文字进上下文。 */
    @Test
    fun quoteFileById() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("501", fileItem)
        waitSent(1)
        wxSent.clear()
        wxInbox += inMsg("502", quoting("这份说了什么", """{"svr_id":501}"""))
        waitSent(1)
        val mine = history(w.e).last { it.role == Role.USER }
        assertEquals("报告.txt", mine.quoted.single().name)
        assertTrue("[文件：报告.txt]" in mine.content, mine.content)
        assertTrue("[附件：报告.txt]" in lastAskText(), lastAskText())
        w.bridge.stop()
    }

    /** 没存下来的（早于这个版本的）：不装作没引用，照实告诉 AI 看不到。空的 ref_msg 不算引用。 */
    @Test
    fun quoteNotInHistory() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("601", quoting("这句不对", """{"svr_id":999999}"""))
        waitSent(1)
        assertTrue("内容没存下来" in lastAskText(), lastAskText())
        assertTrue("这句不对" in lastAskText())
        wxSent.clear()
        wxInbox += inMsg("602", quoting("普通的一句", "{}"))
        waitSent(1)
        assertEquals("普通的一句", history(w.e).last { it.role == Role.USER }.content)
        w.bridge.stop()
    }

    /** 老版微信把内容带在引用里（官方插件 inbound.test.ts 的几种写法），媒体从 CDN 下载。 */
    @Test
    fun quoteInlineFromOldClient() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("701", quoting("这样吗", """{"title":"原标题","message_item":{"type":1,"text_item":{"text":"原内容"}}}"""))
        waitSent(1)
        assertTrue("> 原标题 | 原内容" in lastAskText(), lastAskText())
        wxSent.clear()
        wxInbox += inMsg("702", quoting("图里是什么", """{"message_item":$imageItem}"""))
        waitSent(1)
        val mine = history(w.e).last { it.role == Role.USER }
        assertEquals(1, mine.attachments.size, "引用里带的图下载下来存成附件")
        assertTrue("[图片]" in mine.content, mine.content)
        wxSent.clear()
        wxInbox += inMsg("703", quoting("你说的这个", """{"message_item":{"type":3,"voice_item":{"text":"一段语音的字"}}}"""))
        waitSent(1)
        assertTrue("[语音] 一段语音的字" in lastAskText(), lastAskText())
        w.bridge.stop()
    }

    /** 只选一段引用（partial_text）：引用块里只有选中的那段；校验对不上就引整条。 */
    @Test
    fun quotePartialText() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("801", text("苹果和香蕉和橘子都很好吃"))
        waitSent(1)
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        wxSent.clear()
        wxInbox += inMsg("802", quoting("为什么", """{"svr_id":801,"partial_text":{"start":"香","end":"蕉","startindex":0,"endindex":0,"quotemd5":"${md5("香蕉")}"}}"""))
        waitSent(1)
        var mine = history(w.e).last { it.role == Role.USER }
        assertTrue(mine.content.substringBefore("\n\n").endsWith("：香蕉"), mine.content)
        wxSent.clear()
        wxInbox += inMsg("803", quoting("再说说", """{"svr_id":801,"partial_text":{"start":"香","end":"蕉","startindex":0,"endindex":0,"quotemd5":"对不上的校验值"}}"""))
        waitSent(1)
        mine = history(w.e).last { it.role == Role.USER }
        assertTrue("苹果和香蕉和橘子都很好吃" in mine.content, mine.content)
        w.bridge.stop()
    }

    /** 长回答拆成几条发：引用其中一条只引那一段；「/新对话」以后、程序重开以后还找得到。 */
    @Test
    fun quoteOneChunkAcrossConversationsAndRestart() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("901", text("第一句话"))
        waitSent(1)
        wxSent.clear()
        wxInbox += inMsg("902", text("/新对话"))
        waitSent(1)
        wxSent.clear()
        wxInbox += inMsg("903", quoting("还记得这句吗", """{"svr_id":901}"""))
        waitSent(1)
        assertTrue("第一句话" in lastAskText(), "换了新对话也找得到：" + lastAskText())
        withTimeout(5_000) { while (Engine(Storage(dir)).findChannelPart("weixin:me@im.wechat", "901") == null) delay(100) }
        assertNull(Engine(Storage(dir)).findChannelPart("weixin:别人@im.wechat", "901"), "别的联系人看不到")
        w.bridge.stop()
    }

    /** 群里引用某一位的话：只有它来接；引用里出现的别人的名字不算叫人。微信里带的【名字】不进引用块。 */
    @Test
    fun quoteInGroupGoesToThatMember() = runBlocking {
        val w = world("小智", "小美")
        wxInbox += inMsg("1001", text("大家好"))
        waitSent(2)
        val zhi = wxSent.first { sentText(it.first)!!.startsWith("【小智】") }
        assertEquals("【小智】\n小智答：好的。", sentText(zhi.first))
        val ai = withTimeout(5_000) {
            var a: Message
            do { a = history(w.e).last { it.role == Role.AI && it.senderId == "m0" }; delay(20) } while (a.channelParts.isEmpty())
            a
        }
        assertEquals("【小智】\n小智答：好的。", ai.channelParts.single().text, "微信里显示的文字和正文不一样：另存一份，部分引用才对得上")
        requests.clear()
        wxSent.clear()
        wxInbox += inMsg("1002", quoting("你这句和小美说的不一样", """{"svr_id":${zhi.second}}"""))
        withTimeout(20_000) { while (wxSent.isEmpty()) delay(20) }
        delay(500)
        val mine = history(w.e).last { it.role == Role.USER }
        assertTrue(mine.content.startsWith("@小智 \n> 小智 · "), mine.content)
        assertFalse("【小智】" in mine.content, mine.content)
        assertEquals(1, wxSent.size, "只有小智接：" + wxSent.map { sentText(it.first) })
        w.bridge.stop()
    }

    /** 视频：发来就存下来、取画面、听写；AI 看得到画面和听到的话；引用它时不用再读一遍。 */
    @Test
    fun videoIsReadAndQuotable() = runBlocking {
        val browser = FakeVideoBrowser()
        val w = world("阿苗", browser = browser)
        w.e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "${base()}/v1", "sk-z")))
        wxInbox += inMsg("1101", videoItem)
        waitSent(1)
        val video = history(w.e).first { it.role == Role.USER }.attachments.single()
        assertTrue(video.isVideo, video.toString())
        assertEquals(6, video.frames.size)
        assertEquals(13, video.seconds, "12.3 秒进一位")
        assertEquals(1, asrCalls)
        assertEquals(6, lastAskImages(), "模型看得到六张画面")
        val asked = lastAskText()
        assertTrue("[视频：${video.name}，13 秒]" in asked, asked)
        assertTrue("视频里说大家好。" in asked, asked)

        wxSent.clear()
        wxInbox += inMsg("1102", quoting("这段视频讲了什么", """{"svr_id":1101}"""))
        waitSent(1)
        val mine = history(w.e).last { it.role == Role.USER }
        assertEquals(listOf(video.id), mine.quoted.map { it.id })
        assertEquals(1, browser.framesAsked.get(), "读过的不再读一遍")
        assertEquals(1, asrCalls, "听写按秒收费，也不再听一遍")
        assertEquals(6, lastAskImages(), "还是六张，不重复带")
        assertTrue("视频里说大家好。" in lastAskText())
        w.bridge.stop()
    }

    /** 这台设备解不了视频（测试里没有浏览器，相当于没装 Edge）：照实说，不装作看过。没开视觉的模型只拿听到的话。 */
    @Test
    fun videoWithoutDecoderSaysSo() = runBlocking {
        val w = world("阿苗")
        wxInbox += inMsg("1201", videoItem, text("看看这个"))
        waitSent(1)
        val asked = lastAskText()
        assertTrue("[视频：" in asked, asked)
        assertTrue("这台设备解不了视频" in asked, asked)
        assertTrue("看看这个" in asked, asked)
        w.bridge.stop()
    }

    private val overloaded get() = """{"error":{"message":"Our servers are currently overloaded. Please try again later.","type":"service_unavailable_error","param":null,"code":"server_is_overloaded","headers":{"retry-after":"326","retry-after-ms":"326000"}}}"""

    /** 短时间过载：自己等一下再试，用户感觉不到。 */
    @Test
    fun busyRetriesQuietly() = runBlocking {
        val w = world("阿苗")
        w.e.busyBackoff = listOf(10L, 10L, 10L)
        busyBody = """{"error":{"message":"Our servers are currently overloaded."}}"""
        busyLeft.set(2)
        wxInbox += inMsg("1301", text("你好"))
        waitSent(1)
        assertEquals("阿苗答：好的。", sentText(wxSent.single().first), "用户只收到回答")
        assertEquals(3, modelCalls.get(), "试了三次")
        assertEquals(MsgStatus.DONE, history(w.e).last().status)
        w.bridge.stop()
    }

    /** 用户截图那个错：服务商说要等几分钟。单聊就等，微信里说一声，到时候自动答。 */
    @Test
    fun busyLongWaitTellsAndAnswers() = runBlocking {
        val w = world("阿苗")
        w.e.busyShortMs = 50
        busyBody = overloaded.replace("326000", "300")
        busyLeft.set(1)
        wxInbox += inMsg("1401", text("你好"))
        waitSent(2)
        val first = sentText(wxSent[0].first)!!
        assertTrue("太忙" in first && "恢复" in first, first)
        assertFalse("{" in first, first)
        assertEquals("阿苗答：好的。", sentText(wxSent[1].first))
        val ai = history(w.e).last()
        assertEquals(MsgStatus.DONE, ai.status)
        assertEquals("wait", ai.tools.single().kind)
        w.bridge.stop()
    }

    /** 等的时候又发了一句：不等了，两句一起答一次，不说「没答上来」。 */
    @Test
    fun busyWaitGivesWayToNewMessage() = runBlocking {
        val w = world("阿苗")
        w.e.busyShortMs = 50
        busyBody = overloaded.replace("326000", "120000")
        busyLeft.set(1)
        wxInbox += inMsg("1501", text("第一句"))
        waitSent(1)
        assertTrue("太忙" in sentText(wxSent[0].first)!!)
        wxInbox += inMsg("1502", text("第二句"))
        waitSent(2)
        delay(300)
        assertEquals(2, wxSent.size, wxSent.map { sentText(it.first) }.toString())
        assertEquals("阿苗答：好的。", sentText(wxSent[1].first))
        val msgs = history(w.e)
        assertEquals(listOf("第一句", "第二句"), msgs.filter { it.role == Role.USER }.map { it.content })
        assertEquals(1, msgs.count { it.role == Role.AI }, "等的那条撤掉了")
        assertTrue("第一句" in lastAskText() && "第二句" in lastAskText(), lastAskText())
        w.bridge.stop()
    }

    /** 服务商说要等太久（超过 15 分钟）或者一直不行：微信里说清楚，不是 JSON，也不是「去电脑上看」。 */
    @Test
    fun busyGivesUpWithPlainWords() = runBlocking {
        val w = world("阿苗")
        busyBody = overloaded.replace("326000", "1000000")
        busyLeft.set(50)
        wxInbox += inMsg("1601", text("你好"))
        waitSent(1)
        val said = sentText(wxSent.single().first)!!
        assertTrue(said.startsWith("这次没答上来：假服务商 这会儿太忙了"), said)
        assertTrue("17 分钟" in said, said)
        assertFalse("{" in said || "电脑上" in said, said)
        val ai = history(w.e).last()
        assertEquals(MsgStatus.ERROR, ai.status)
        assertFalse("{" in ai.error, ai.error)
        assertEquals(1, modelCalls.get(), "等不了的就不再试")
        w.bridge.stop()
    }

    /** 群里不等久的（别让一位拖住整桌）。没钱、Key 不对这种等也没用，一次就报；别的 5xx 也不显示 JSON。 */
    @Test
    fun busyRulesForGroupsAndHopelessErrors() = runBlocking {
        val w = world("小智", "小美")
        w.e.busyShortMs = 50
        busyBody = overloaded
        busyLeft.set(2)
        wxInbox += inMsg("1701", text("你好"))
        waitSent(2)
        assertEquals(2, modelCalls.get())
        assertTrue(wxSent.all { "太忙" in sentText(it.first)!! }, wxSent.map { sentText(it.first) }.toString())
        wxSent.clear()
        modelCalls.set(0)

        // 余额不足：一次就报
        busyStatus = HttpStatusCode.TooManyRequests
        busyBody = """{"error":{"code":"1113","message":"余额不足或无可用资源包"}}"""
        busyLeft.set(50)
        wxInbox += inMsg("1702", text("@小智 你好"))
        waitSent(1)
        assertEquals(1, modelCalls.get(), "余额不足等也没用")
        assertTrue("余额或额度不足" in sentText(wxSent.single().first)!! && "余额不足或无可用资源包" in sentText(wxSent.single().first)!!)
        assertFalse("{" in sentText(wxSent.single().first)!!)
        wxSent.clear()
        modelCalls.set(0)

        // 别的 5xx：按退避试完，报错只留一句话
        w.e.busyBackoff = listOf(10L, 10L)
        busyStatus = HttpStatusCode.BadGateway
        busyBody = """{"error":{"message":"boom"}}"""
        busyLeft.set(50)
        wxInbox += inMsg("1703", text("@小智 你好"))
        waitSent(1)
        assertEquals(3, modelCalls.get())
        assertEquals("这次没答上来：服务商那边出错了，稍后再试（HTTP 502）：boom", sentText(wxSent.single().first))
        busyLeft.set(0)
        w.bridge.stop()
    }

    /** 聊天里图多的时候，带给模型的是最新的几张（以前从旧往新数，最新发的反而轮不上）；早的写明这次看不到。 */
    @Test
    fun newestImagesGoToModel() = runBlocking {
        val w = world("阿苗")
        val conv = w.e.call(Command.CreateConversation(listOf("m0"))).data
        w.e.call(Command.UpdateConversation(w.e.state.conversation(conv)!!.copy(webSearch = false)))
        for (i in 1..8) {
            val att = w.e.upload("pic$i.png", "image/png", png)!!
            val before = requests.size
            w.e.call(Command.SendMessage(conv, "第 $i 张", attachmentIds = listOf(att.id)))
            withTimeout(10_000) { while (requests.size == before) delay(20) }
            delay(100)
        }
        val body = requests.last()["messages"].toString()
        assertEquals(6, body.split("\"image_url\",\"image_url\"").size - 1, "最多带六张")
        assertTrue("pic1.png，太早了" in body && "pic2.png，太早了" in body, body.take(600))
        assertFalse("pic8.png，太早了" in body || "pic3.png，太早了" in body)
        w.bridge.stop()
    }

    @Test
    fun quoteHelpers() {
        assertEquals("> 一行\n> 二行\n\n我说", Quotes.compose("一行\n二行", "我说"))
        assertEquals("> 空行\n>\n> 下一段", Quotes.compose("空行\n\n下一段", ""))
        assertEquals("@小智 \n\n这句", Quotes.spoken("@小智 \n> 小美，你看\n\n这句").replace("\n\n\n", "\n\n"))
        // 官方插件 partial-quote.test.ts 的三个例子
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("abc", Quotes.resolvePartial("abcedfabcgh", Quotes.Partial("a", "c", 1, 1, md5("abc"))))
        val full = "x-end start first-end second-end"
        val selected = "start first-end second-end"
        assertEquals(selected, Quotes.resolvePartial(full, Quotes.Partial("start", "end", 0, 1, md5(selected))))
        assertNull(Quotes.resolvePartial("hello", Quotes.Partial("h", "o", -1, 0, "")))
        assertNull(Quotes.resolvePartial("hello", Quotes.Partial("h", "o", 0, 0, "not-the-md5")))
        assertNotNull(Quotes.resolvePartial("hello", Quotes.Partial("h", "o", 0, 0, "")))
    }
}
