package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Ledger
import com.guixing.jixunying.engine.SpeechText
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.SpeechAudio
import com.guixing.jixunying.model.SpeechPick
import com.guixing.jixunying.model.SpeechPlan
import com.guixing.jixunying.model.SpeechSettings
import com.guixing.jixunying.model.SpeechUnits
import com.guixing.jixunying.model.UsageKinds
import com.guixing.jixunying.model.UsageRecord
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.test.assertNotNull
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语音（读出来 / 听写）：四家协议、长文字切段、录音分段、记账、自动挑、旧版手机的设置不被冲掉。
 * 假服务器严格检查请求（路径、方法、Content-Type、字段名、format=wav、multipart 里的 file 和 model），
 * 按各家官方的返回格式回；录音和音频都是自己拼的 wav（不借引擎里的拼接函数，免得测试和实现互相背书）。
 */
class SpeechTest {
    private val chatRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val miniMaxTtsRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val miniMaxAsrBodies = Collections.synchronizedList(mutableListOf<Form>())
    private val zhipuTtsRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val zhipuAsrBodies = Collections.synchronizedList(mutableListOf<Form>())

    /** 一份 multipart 表单，照真服务器那样解析出来的。 */
    private class Form(val fields: Map<String, String>, val fileName: String?, val fileType: String?, val file: ByteArray?, val disposition: String)

    /**
     * 按 multipart 规矩解析，不是只在原文里找 name="file"：10-12 之前假服务器就是那么查的，
     * 引擎把 Content-Disposition 拼出两个 form-data 也照样过，真服务器 MiniMax、智谱都拒了。
     */
    private suspend fun io.ktor.server.application.ApplicationCall.strictForm(): Form {
        val fields = mutableMapOf<String, String>()
        var name: String? = null
        var type: String? = null
        var bytes: ByteArray? = null
        var disposition = ""
        receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> fields[part.name.orEmpty()] = part.value
                is PartData.FileItem -> {
                    name = part.originalFileName
                    type = part.contentType?.toString()
                    bytes = part.provider().readRemaining().readByteArray()
                    disposition = part.headers.getAll(HttpHeaders.ContentDisposition).orEmpty().joinToString("; ")
                }
                else -> {}
            }
            part.dispose()
        }
        return Form(fields, name, type, bytes, disposition)
    }

    /** 文件那一段的头部要规矩：恰好一个 form-data、name 是 file、带文件名（真服务器靠它认文件）。 */
    private fun assertFileForm(f: Form, model: String) {
        assertEquals(1, Regex("form-data").findAll(f.disposition).count(), "Content-Disposition 里只能有一个 form-data：${f.disposition}")
        assertEquals("audio.wav", f.fileName, f.disposition)
        assertEquals("audio/wav", f.fileType)
        assertEquals(model, f.fields["model"])
        assertNotNull(f.file)
    }
    private val qwenRequests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val qwenDownloads = AtomicInteger()
    private val zhipuAsrCount = AtomicInteger()

    /** 假服务器上装出来的毛病（每个用例自己打开）。 */
    @Volatile private var mimoWithoutAudio = false
    @Volatile private var miniMaxFails = false
    @Volatile private var miniMaxAsrUnknownField = false

    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    /** 半秒的 wav：小米 / MiniMax / 智谱 / 千问回的音频都是它。 */
    private val shortWav = wavSeconds(0.5)

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun base() = "http://127.0.0.1:$port"

    /** 自己拼一个 wav（16000 Hz、单声道、16 位），用来当「服务商返回的音频」和「用户的录音」。 */
    private fun wavSeconds(seconds: Double, rate: Int = 16000): ByteArray {
        val data = ByteArray((rate * seconds).toInt() * 2)
        val b = ByteArrayOutputStream()
        fun ascii(s: String) = b.write(s.toByteArray(Charsets.US_ASCII))
        fun le(v: Int, n: Int) {
            for (i in 0 until n) b.write((v shr (8 * i)) and 0xFF)
        }
        ascii("RIFF"); le(36 + data.size, 4); ascii("WAVE")
        ascii("fmt "); le(16, 4); le(1, 2); le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
        ascii("data"); le(data.size, 4); b.write(data)
        return b.toByteArray()
    }

    @BeforeTest
    fun setUp() {
        port = freePort()
        dir = kotlin.io.path.createTempDirectory("jxy-speech").toFile()
        val wavJson = Base64.getEncoder().encodeToString(shortWav)
        val hex = shortWav.joinToString("") { "%02x".format(it) }
        fake = embeddedServer(CIO, port = port) {
            routing {
                // 小米：读出来和听写都走 chat/completions
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    chatRequests += req
                    val first = req["messages"]!!.jsonArray.first().jsonObject
                    if (first["content"] is JsonArray) {
                        // 听写：user 消息里是 input_audio
                        call.respondText("""{"choices":[{"message":{"content":"小米听写：你好世界"}}]}""", ContentType.Application.Json)
                        return@post
                    }
                    if (mimoWithoutAudio) {
                        call.respondText("""{"choices":[{"message":{"role":"assistant","content":""}}]}""", ContentType.Application.Json)
                        return@post
                    }
                    call.respondText("""{"choices":[{"message":{"audio":{"data":"$wavJson"}}}]}""", ContentType.Application.Json)
                }
                // MiniMax：读出来是 /v1/t2a_v2（接口地址去掉结尾的 /v1 再加回来），音频是十六进制
                post("/v1/t2a_v2") {
                    miniMaxTtsRequests += Json.parseToJsonElement(call.receiveText()).jsonObject
                    if (miniMaxFails) {
                        call.respondText("""{"base_resp":{"status_code":1002,"status_msg":"rate limit exceeded"}}""", ContentType.Application.Json)
                        return@post
                    }
                    call.respondText("""{"base_resp":{"status_code":0,"status_msg":"success"},"data":{"audio":"$hex"}}""", ContentType.Application.Json)
                }
                post("/v1/speech_to_text") {
                    val form = call.strictForm()
                    miniMaxAsrBodies += form
                    // 真服务器解析不出文件那一段时就是这么回的（10-12 真 Key 实测）
                    if (form.file == null || form.fileName == null) {
                        call.respondText("""{"type":"error","error":{"type":"bad_request_error","message":"invalid params, missing required form field: file (2013)"}}""", ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                        return@post
                    }
                    if (miniMaxAsrUnknownField) {
                        call.respondText("""{"base_resp":{"status_code":0},"result":"某些没见过的字段"}""", ContentType.Application.Json)
                        return@post
                    }
                    call.respondText("""{"base_resp":{"status_code":0},"text":"MiniMax 听写：你好世界"}""", ContentType.Application.Json)
                }
                // 智谱：读出来返回二进制，听写 multipart
                post("/v1/audio/speech") {
                    zhipuTtsRequests += Json.parseToJsonElement(call.receiveText()).jsonObject
                    call.respondBytes(shortWav, ContentType.Application.OctetStream)
                }
                post("/v1/audio/transcriptions") {
                    val form = call.strictForm()
                    zhipuAsrBodies += form
                    if (form.file == null || form.fileName == null) {
                        call.respondText("""{"error":{"message":"Failed to parse multipart servlet request","code":"500"}}""", ContentType.Application.Json)
                        return@post
                    }
                    call.respondText("""{"text":"第${zhipuAsrCount.incrementAndGet()}段"}""", ContentType.Application.Json)
                }
                // 千问：返回音频地址，再下载
                post("/api/v1/services/aigc/multimodal-generation/generation") {
                    qwenRequests += Json.parseToJsonElement(call.receiveText()).jsonObject
                    call.respondText("""{"output":{"audio":{"url":"${base()}/qwen-audio/1.wav"}},"request_id":"r1"}""", ContentType.Application.Json)
                }
                get("/qwen-audio/{name}") {
                    qwenDownloads.incrementAndGet()
                    call.respondBytes(shortWav, ContentType.Application.OctetStream)
                }
            }
        }.also { it.start(wait = false) }
    }

    @AfterTest
    fun tearDown() {
        fake.stop(100, 200)
        dir.deleteRecursively()
    }

    private suspend fun engine(vararg presetIds: String): Engine {
        val e = Engine(Storage(dir))
        for (id in presetIds) {
            e.call(Command.SaveProvider(ProviderConfig(id = "p-$id", presetId = id, name = "$id 家", baseUrl = "${base()}/v1", apiKey = "sk-$id")))
        }
        return e
    }

    private suspend fun speak(e: Engine, text: String, providerId: String = "", modelId: String = "", voice: String = "") =
        e.call(Command.Speak(text, providerId, modelId, voice))

    private suspend fun transcribe(e: Engine, wav: ByteArray, providerId: String = "", modelId: String = "") =
        e.call(Command.Transcribe(Base64.getEncoder().encodeToString(wav), "audio/wav", providerId, modelId))

    private fun audioOf(result: CommandResult): SpeechAudio {
        assertTrue(result.ok, result.message)
        return AppJson.decodeFromString(SpeechAudio.serializer(), result.data)
    }

    private fun decoded(a: SpeechAudio) = a.segments.map { Base64.getDecoder().decode(it) }

    // ———————————————— 读出来 ————————————————

    @Test
    fun mimoReadsOutLoud() = runBlocking {
        val e = engine("mimo")
        val a = audioOf(speak(e, "你好，世界。"))
        assertEquals(1, a.segments.size)
        assertTrue(a.segments[0].startsWith("UklGR"), "返回的应该是 wav（RIFF 开头）")
        assertTrue(shortWav.contentEquals(decoded(a)[0]), "拿到的音频和假服务器回的一样")
        assertEquals(6, a.chars)
        assertTrue("mimo-v2.5-tts（mimo 家）" in a.label, a.label)

        val req = chatRequests.last()
        assertEquals("mimo-v2.5-tts", req["model"]!!.jsonPrimitive.content)
        val msgs = req["messages"]!!.jsonArray
        assertEquals("", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你好，世界。", msgs[1].jsonObject["content"]!!.jsonPrimitive.content)
        val audio = req["audio"]!!.jsonObject
        assertEquals("mimo_default", audio["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", audio["format"]!!.jsonPrimitive.content)
        // 小米官方文档里没有语速字段：不传（主会话审查 10-12）
        assertNull(req["speed"], req.toString())
        assertNull(audio["speed"], audio.toString())
    }

    @Test
    fun mimoWithoutAudioReportsError() = runBlocking {
        mimoWithoutAudio = true
        val e = engine("mimo")
        val r = speak(e, "念一下")
        assertFalse(r.ok)
        assertTrue("没返回音频" in r.message, r.message)
        assertTrue("choices[0].message.audio.data" in r.message, r.message)
    }

    @Test
    fun miniMaxReadsOutLoud() = runBlocking {
        val e = engine("minimax")
        val a = audioOf(speak(e, "汉字 ab"))
        assertTrue(shortWav.contentEquals(decoded(a)[0]), "十六进制解出来要和原音频一样")
        val req = miniMaxTtsRequests.last()
        assertEquals("speech-2.8-turbo", req["model"]!!.jsonPrimitive.content)
        assertEquals("汉字 ab", req["text"]!!.jsonPrimitive.content)
        val vs = req["voice_setting"]!!.jsonObject
        assertEquals("male-qn-qingse", vs["voice_id"]!!.jsonPrimitive.content)
        assertTrue(abs(vs["speed"]!!.jsonPrimitive.content.toDouble() - 1.0) < 1e-9, vs.toString())
        assertEquals("wav", req["audio_setting"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun miniMaxReportsStatusMessage() = runBlocking {
        // HTTP 200 里用 base_resp.status_code 报错：要把 status_msg 带出来
        miniMaxFails = true
        val e = engine("minimax")
        val r = speak(e, "念一下")
        assertFalse(r.ok)
        assertTrue("rate limit exceeded" in r.message, r.message)
        assertTrue("1002" in r.message, r.message)
    }

    @Test
    fun zhipuReadsOutLoud() = runBlocking {
        val e = engine("zhipu")
        val a = audioOf(speak(e, "念一下"))
        assertTrue(shortWav.contentEquals(decoded(a)[0]))
        val req = zhipuTtsRequests.last()
        assertEquals("glm-tts", req["model"]!!.jsonPrimitive.content)
        assertEquals("念一下", req["input"]!!.jsonPrimitive.content)
        assertEquals("tongtong", req["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", req["response_format"]!!.jsonPrimitive.content, "不显式给 wav 的话它默认回 pcm")
    }

    @Test
    fun qwenReadsOutLoud() = runBlocking {
        val e = engine("qianwen")
        val a = audioOf(speak(e, "念一下"))
        assertTrue(shortWav.contentEquals(decoded(a)[0]), "返回的是地址，要再下载一次")
        assertEquals(1, qwenDownloads.get())
        val req = qwenRequests.last()
        assertEquals("qwen3-tts-flash", req["model"]!!.jsonPrimitive.content)
        val input = req["input"]!!.jsonObject
        assertEquals("念一下", input["text"]!!.jsonPrimitive.content)
        assertEquals("Cherry", input["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun otherProviderSaysNotConnected() = runBlocking {
        // 别家（比如 OpenAI 兼容的）不去硬试 /audio/xxx，直接说没接
        val e = engine("openai")
        val r = speak(e, "念一下")
        assertFalse(r.ok)
        assertTrue("还没有接读出来" in r.message, r.message)
        assertEquals(0, chatRequests.size + zhipuTtsRequests.size + miniMaxTtsRequests.size)
    }

    // ———————————————— 听写 ————————————————

    @Test
    fun mimoWritesDown() = runBlocking {
        val e = engine("mimo")
        val r = transcribe(e, shortWav)
        assertTrue(r.ok, r.message)
        assertEquals("小米听写：你好世界", r.data)
        val content = chatRequests.last()["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray
        val part = content.first().jsonObject
        assertEquals("input_audio", part["type"]!!.jsonPrimitive.content)
        val data = part["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        assertTrue(data.startsWith("data:audio/wav;base64,"), data.take(40))
        assertTrue(shortWav.contentEquals(Base64.getDecoder().decode(data.substringAfter(","))), "发过去的音频要原样")
    }

    @Test
    fun miniMaxWritesDown() = runBlocking {
        val e = engine("minimax")
        val r = transcribe(e, shortWav)
        assertTrue(r.ok, r.message)
        assertEquals("MiniMax 听写：你好世界", r.data)
        val form = miniMaxAsrBodies.last()
        assertFileForm(form, "asr-1.0")
        assertTrue(shortWav.contentEquals(form.file), "发过去的音频要原样")
    }

    @Test
    fun miniMaxUnknownAsrFieldSaysSo() = runBlocking {
        // 官方文档没写返回里文字叫什么：取不到就把整段返回报出来（不静默返回空）
        miniMaxAsrUnknownField = true
        val e = engine("minimax")
        val r = transcribe(e, shortWav)
        assertFalse(r.ok)
        assertTrue("没找到文字" in r.message, r.message)
        assertTrue("某些没见过的字段" in r.message, r.message)
    }

    @Test
    fun zhipuWritesDown() = runBlocking {
        val e = engine("zhipu")
        val r = transcribe(e, shortWav)
        assertTrue(r.ok, r.message)
        assertEquals("第1段", r.data)
        val form = zhipuAsrBodies.last()
        assertFileForm(form, "glm-asr-2512")
        assertTrue(shortWav.contentEquals(form.file), "发过去的音频要原样")
    }

    @Test
    fun zhipuSplitsLongRecording() = runBlocking {
        val long = wavSeconds(40.0)
        // 先看切段本身：40 秒切成两份（25 + 15），每份不超过 25 秒，拼起来还是原来的音频
        val parts = SpeechText.splitWav(long, 25.0)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.seconds <= 25.0 }, parts.map { it.seconds }.toString())
        assertEquals(40.0, parts.sumOf { it.seconds }, 0.01)
        assertEquals(long.size - 44, parts.sumOf { it.bytes.size } - 44 * parts.size, "音频数据一点不多一点不少（每段各带回自己 44 字节的头）")

        val e = engine("zhipu")
        val r = transcribe(e, long)
        assertTrue(r.ok, r.message)
        assertEquals("第1段第2段", r.data, "按顺序分开识别再拼起来")
        assertEquals(2, zhipuAsrBodies.size)
        zhipuAsrBodies.forEach { assertFileForm(it, "glm-asr-2512") }
        assertTrue(zhipuAsrBodies.all { (SpeechText.secondsOf(it.file!!) ?: 99.0) <= 25.0 }, "每段发过去的都是不超过 25 秒的 wav")
    }

    @Test
    fun qwenWritesDownNotConnected() = runBlocking {
        val e = engine("qianwen")
        val r = transcribe(e, shortWav)
        assertFalse(r.ok)
        assertTrue("还没有接听写" in r.message, r.message)
    }

    @Test
    fun emptyRecordingIsRejected() = runBlocking {
        val e = engine("mimo", "zhipu")
        val r = e.call(Command.Transcribe("", "audio/wav"))
        assertFalse(r.ok)
        assertTrue("空" in r.message || "base64" in r.message, r.message)
    }

    // ———————————————— 长文字 ————————————————

    @Test
    fun cleanRemovesMarkdownAndCode() {
        val text = """
            # 标题

            这是**正文**，带一个[链接](https://example.com/a)和 `行内代码`。

            - 第一点
            - 第二点

            ```
            val x = 1
            println(x)
            ```

            > 引用一句

            <think>先想一下</think>想好了。
        """.trimIndent()
        val clean = SpeechText.clean(text)
        assertFalse("#" in clean, clean)
        assertFalse("**" in clean, clean)
        assertFalse("`" in clean, clean)
        assertFalse("https://" in clean, clean)
        assertFalse("先想一下" in clean, clean)
        assertFalse("- 第一点" in clean, clean)
        assertTrue("这是正文" in clean, clean)
        assertTrue("链接" in clean, clean)
        assertTrue("行内代码" in clean, clean)
        assertTrue("这里有一段代码" in clean, clean)
        assertFalse("println" in clean, clean)
        assertTrue("引用一句" in clean, clean)
        assertTrue("想好了" in clean, clean)
    }

    @Test
    fun splitRespectsEachLimit() {
        // 各家上限（官方文档里的数）
        assertEquals(1024, SpeechText.limitOf("zhipu"))
        assertEquals(600, SpeechText.limitOf("qwen"))
        assertEquals(3000, SpeechText.limitOf("minimax"))
        assertEquals(1000, SpeechText.limitOf("mimo"))
        // 长文字按句号切，每段不超过上限，还不丢字
        val text = (1..120).joinToString("") { "这是第 $it 句话，写在这里。" }
        val out = SpeechText.split(text, 300)
        assertTrue(out.size > 1, "应该切成了几段：${out.size}")
        assertTrue(out.all { it.length <= 300 }, out.maxOf { it.length }.toString())
        assertEquals(text.count { it != ' ' }, out.joinToString("") { it }.count { it != ' ' }, "一个字都不能少")
        // 单句特别长的时候硬切
        val hard = SpeechText.split("啊".repeat(1000), 300)
        assertTrue(hard.all { it.length <= 300 }, hard.map { it.length }.toString())
        assertEquals(1000, hard.sumOf { it.length })
    }

    @Test
    fun longTextIsSplitIntoFewRequests() = runBlocking {
        val e = engine("zhipu")
        val text = (1..80).joinToString("") { "这是第 $it 句话。" } // 一共六百多字
        val a = audioOf(speak(e, text))
        assertEquals(1, a.segments.size, "六百多字没超过智谱的 1024，一次就行")
        assertEquals(a.segments.size, zhipuTtsRequests.size, "一段一次请求")
        assertEquals(text.take(10), zhipuTtsRequests.first()["input"]!!.jsonPrimitive.content.take(10))
        // 超过上限的会切
        val n = zhipuTtsRequests.size
        val b = audioOf(speak(e, (1..300).joinToString("") { "这是第 $it 句话。" })) // 两千多字
        assertTrue(b.segments.size >= 3, "两千多字要切成 3 段以上：${b.segments.size}")
        val sent = zhipuTtsRequests.drop(n)
        assertEquals(b.segments.size, sent.size)
        assertTrue(sent.all { it["input"]!!.jsonPrimitive.content.length <= 1024 },
            sent.maxOf { it["input"]!!.jsonPrimitive.content.length }.toString())
    }

    @Test
    fun codeOnlyIsReadAsOneLine() = runBlocking {
        val e = engine("zhipu")
        val a = audioOf(speak(e, "```\nval x = 1\nprintln(x)\n```"))
        assertEquals(1, a.segments.size)
        assertEquals(1, zhipuTtsRequests.size)
        val sent = zhipuTtsRequests.last()["input"]!!.jsonPrimitive.content
        assertTrue("这里有一段代码" in sent, sent)
        assertFalse("val x" in sent, sent)
    }

    /** 先切段（不联网），再一段一段 Speak(prepared)：点停止就不再往下合成（主会话审查 10-12 加的）。 */
    @Test
    fun planThenSpeakOneChunkAtATime() = runBlocking {
        val e = engine("zhipu")
        val long = "## 标题\n" + (1..60).joinToString("") { "这是第${it}句话，用来凑够长度，看它会不会按上限切开。" } + "\n3.5 元一万字。"
        val r = e.call(Command.SpeakPlan(long))
        assertTrue(r.ok, r.message)
        val plan = AppJson.decodeFromString(SpeechPlan.serializer(), r.data)
        assertEquals("p-zhipu", plan.providerId)
        assertEquals("glm-tts", plan.modelId)
        assertTrue(plan.chunks.size >= 2, "这么长应该切成几段：${plan.chunks.map { it.length }}")
        assertTrue(plan.chunks.all { it.length <= 1024 }, "智谱一段最多 1024 字")
        assertEquals(0, zhipuTtsRequests.size, "切段不联网")
        assertFalse(plan.chunks.first().startsWith("#"), "标题记号去掉了")
        // 「3.5 元」开头的那一行不能被当成有序列表削掉
        assertTrue(plan.chunks.last().endsWith("3.5 元一万字。"), plan.chunks.last())

        // 只合成第一段就停：只发了一次请求
        val first = e.call(Command.Speak(plan.chunks.first(), plan.providerId, plan.modelId, prepared = true))
        assertEquals(1, audioOf(first).segments.size)
        assertEquals(1, zhipuTtsRequests.size)
        assertEquals(plan.chunks.first(), zhipuTtsRequests.last()["input"]!!.jsonPrimitive.content, "prepared 的原样发出去，不再清理一遍")

        // 没有能读的内容、没有能用的服务商都要说清楚
        val empty = e.call(Command.SpeakPlan("#\n---"))
        assertFalse(empty.ok)
        assertTrue("没有能读的内容" in empty.message, empty.message)
        val none = Engine(Storage(File(dir, "none").apply { mkdirs() }))
        val nr = none.call(Command.SpeakPlan("你好"))
        assertFalse(nr.ok)
        assertTrue("没有接读出来" in nr.message, nr.message)
    }

    /** 设置里的音色只给设置里选的那家用；那家用不了、自动换到别家时用别家的默认音色。 */
    @Test
    fun voiceFromSettingsOnlyForThatProvider() = runBlocking {
        val e = engine("mimo", "zhipu")
        e.call(Command.SaveSettings(e.state.settings.copy(speech = SpeechSettings(ttsProviderId = "p-mimo", ttsVoice = "冰糖"))))
        audioOf(speak(e, "你好。"))
        assertEquals("冰糖", chatRequests.last()["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        // 小米停用了：自动挑到智谱，音色用智谱默认的 tongtong，不把「冰糖」发过去
        val mimo = e.state.provider("p-mimo")!!
        e.call(Command.SaveProvider(mimo.copy(enabled = false)))
        audioOf(speak(e, "你好。"))
        assertEquals("tongtong", zhipuTtsRequests.last()["voice"]!!.jsonPrimitive.content)
    }

    // ———————————————— 自动挑（SpeechPick） ————————————————

    @Test
    fun pickOrder() {
        fun st(vararg ids: String, settings: Settings = Settings()) = AppState(
            providers = ids.map { ProviderConfig(id = "p-$it", presetId = it, name = it, baseUrl = "https://x", apiKey = "sk") },
            settings = settings,
        )
        assertEquals("p-mimo", SpeechPick.forTts(st("zhipu", "mimo"))!!.providerId, "读出来小米排第一")
        assertEquals("mimo-v2.5-tts", SpeechPick.forTts(st("zhipu", "mimo"))!!.modelId)
        assertEquals("p-minimax", SpeechPick.forTts(st("zhipu", "minimax"))!!.providerId)
        assertEquals("speech-2.8-turbo", SpeechPick.forTts(st("zhipu", "minimax"))!!.modelId, "MiniMax 默认用 turbo（便宜）")
        assertEquals("p-zhipu", SpeechPick.forTts(st("qianwen", "zhipu"))!!.providerId)
        assertEquals("p-qianwen", SpeechPick.forTts(st("qianwen"))!!.providerId, "只有千问时也用它")
        // 听写没有千问
        assertEquals("p-mimo", SpeechPick.forAsr(st("zhipu", "mimo"))!!.providerId)
        assertEquals("p-minimax", SpeechPick.forAsr(st("zhipu", "minimax"))!!.providerId)
        assertEquals("p-zhipu", SpeechPick.forAsr(st("zhipu"))!!.providerId)
        assertNull(SpeechPick.forTts(st("openai", "deepseek")))
        assertNull(SpeechPick.forAsr(st("qianwen")))
        // 没填 Key / 停用的不算
        assertNull(SpeechPick.forTts(AppState(providers = listOf(ProviderConfig("p1", "mimo", "小米", "https://x")))))
        assertNull(SpeechPick.forTts(AppState(providers = listOf(ProviderConfig("p1", "mimo", "小米", "https://x", "sk", enabled = false)))))
        // 设置里指定了就用指定的
        val picked = SpeechPick.forTts(st("mimo", "zhipu", settings = Settings(speech = SpeechSettings(ttsProviderId = "p-zhipu", ttsModel = "glm-tts"))))
        assertEquals("p-zhipu", picked!!.providerId)
        // 指定的那家没了 / 停用了 → 回到自动
        assertTrue(SpeechPick.isAuto(st("mimo", settings = Settings(speech = SpeechSettings(ttsProviderId = "p-gone"))), tts = true))
        val off = AppState(
            providers = listOf(
                ProviderConfig("p-zhipu", "zhipu", "zhipu", "https://x", "sk", enabled = false),
                ProviderConfig("p-mimo", "mimo", "mimo", "https://x", "sk"),
            ),
            settings = Settings(speech = SpeechSettings(ttsProviderId = "p-zhipu")),
        )
        assertEquals("p-mimo", SpeechPick.forTts(off)!!.providerId, "指定的那家停用了就自动挑")
        // 模型列表里有 voiceclone / voicedesign 也不挑它们（要额外传音频样本或音色描述），用官方默认的 mimo-v2.5-tts
        val listed = AppState(providers = listOf(ProviderConfig("p-mimo", "mimo", "mimo", "https://x", "sk",
            models = listOf(ModelInfo("mimo-v2.5-tts-voiceclone"), ModelInfo("mimo-v2.5-tts-voicedesign"), ModelInfo("mimo-v2.5-tts")))))
        assertEquals("mimo-v2.5-tts", SpeechPick.forTts(listed)!!.modelId)
    }

    // ———————————————— 记账 ————————————————

    @Test
    fun billingCountsCharsAndSeconds() = runBlocking {
        val e = engine("mimo", "minimax", "zhipu")
        // 读出来：小米限时免费，但账照记（字数、单位）
        e.call(Command.SaveSettings(e.state.settings.copy(speech = SpeechSettings(ttsProviderId = "p-mimo"))))
        audioOf(speak(e, "汉字 ab"))
        // 听写：智谱按秒
        assertTrue(transcribe(e, wavSeconds(10.0), providerId = "p-zhipu").ok)

        val recs = Ledger(File(dir, "usage")).since(0)
        val tts = recs.single { it.kind == UsageKinds.TTS }
        assertEquals(5, tts.units.toInt())
        assertEquals(SpeechUnits.CHAR, tts.unit)
        assertEquals("mimo-v2.5-tts", tts.model)
        assertEquals("mimo", tts.platform)
        assertTrue(abs(Prices.cost(tts, Prices.of("mimo", "mimo-v2.5-tts"))!!) < 1e-9, "小米读出来限时免费")

        val asr = recs.single { it.kind == UsageKinds.ASR }
        assertEquals(10.0, asr.units, 0.01)
        assertEquals(SpeechUnits.SECOND, asr.unit)
        assertEquals("glm-asr-2512", asr.model)
        // 智谱按约每秒 0.0002 元估：10 秒 0.002 元
        assertTrue(abs(0.002 - Prices.cost(asr, Prices.of("zhipu", "glm-asr-2512"))!!) < 1e-9)

        // MiniMax：1 个汉字算 2 个字符 → 2 元 / 万字符
        assertEquals(6.0, SpeechUnits.ttsChars("minimax", "汉字ab"), "汉字各算 2 个、字母各算 1 个")
        assertEquals(4.0, SpeechUnits.ttsChars("mimo", "汉字ab"), "别家按字数")
        val mm = UsageRecord(0, UsageKinds.TTS, "p-minimax", "MiniMax", "minimax", "speech-2.8-turbo",
            units = SpeechUnits.ttsChars("minimax", "汉字ab"), unit = SpeechUnits.CHAR)
        assertTrue(abs(2.0 * 6.0 / 10_000.0 - Prices.cost(mm, Prices.of("minimax", "speech-2.8-turbo"))!!) < 1e-9)
        // 高音质贵一点：3.5 元 / 万字符
        assertTrue(abs(3.5 * 6.0 / 10_000.0 - Prices.cost(mm.copy(model = "speech-2.8-hd"), Prices.of("minimax", "speech-2.8-hd"))!!) < 1e-9)
        // 小米听写 0.5 元 / 小时
        val mAsr = mm.copy(platform = "mimo", model = "mimo-v2.5-asr", units = 10.0, unit = SpeechUnits.SECOND)
        assertTrue(abs(0.5 * 10.0 / 3600.0 - Prices.cost(mAsr, Prices.of("mimo", "mimo-v2.5-asr"))!!) < 1e-9)
        // MiniMax 听写 2.5 元 / 小时
        val xAsr = mAsr.copy(platform = "minimax", model = "asr-1.0")
        assertTrue(abs(2.5 * 10.0 / 3600.0 - Prices.cost(xAsr, Prices.of("minimax", "asr-1.0"))!!) < 1e-9)
        // 千问读出来价格没查到（未确认），花费页会说没算进去
        assertNull(Prices.of("qwen", "qwen3-tts-flash"))
    }

    @Test
    fun failedButBilledIsStillRecorded() = runBlocking {
        // 教训 53：服务商收下了请求（回了 2xx）就已经扣钱，后面解析失败也要记。
        // 小米回了个空的 audio.data → 报错，但账上要有这一条。
        val p2 = freePort()
        val broken = embeddedServer(CIO, port = p2) {
            routing {
                post("/v1/chat/completions") {
                    call.receiveText()
                    call.respondText("""{"choices":[{"message":{"audio":{"data":""}}}]}""", ContentType.Application.Json)
                }
            }
        }
        broken.start(wait = false)
        try {
            val e = engine("mimo")
            e.call(Command.SaveProvider(ProviderConfig(id = "p-mimo", presetId = "mimo", name = "小米", baseUrl = "http://127.0.0.1:$p2/v1", apiKey = "sk")))
            e.call(Command.SaveSettings(e.state.settings.copy(speech = SpeechSettings(ttsProviderId = "p-mimo"))))
            val r = speak(e, "念一下")
            assertFalse(r.ok, "返回空音频要报错")
            assertEquals(1, Ledger(File(dir, "usage")).since(0).count { it.kind == UsageKinds.TTS }, "已经扣钱了，账要记上")
        } finally {
            broken.stop(100, 200)
        }
    }

    /** 智谱听写出错时也回 HTTP 200，内容是 {"error":…}（10-12 真 Key 实测）：报错、不记账。 */
    @Test
    fun zhipuErrorIn200IsNotBilled() = runBlocking {
        val p2 = freePort()
        val broken = embeddedServer(CIO, port = p2) {
            routing {
                post("/v1/audio/transcriptions") {
                    call.receiveText()
                    call.respondText("""{"error":{"message":"Failed to parse multipart servlet request","code":"500"}}""", ContentType.Application.Json)
                }
            }
        }
        broken.start(wait = false)
        try {
            val e = engine()
            e.call(Command.SaveProvider(ProviderConfig(id = "p-zhipu", presetId = "zhipu", name = "智谱", baseUrl = "http://127.0.0.1:$p2/v1", apiKey = "sk")))
            val r = transcribe(e, shortWav)
            assertFalse(r.ok)
            assertTrue("Failed to parse multipart" in r.message, r.message)
            assertEquals(0, Ledger(File(dir, "usage")).since(0).count { it.kind == UsageKinds.ASR }, "没识别就不记账")
        } finally {
            broken.stop(100, 200)
        }
    }

    // ———————————————— 旧版手机 ————————————————

    @Test
    fun oldPhoneKeepsSpeechSettings() = runBlocking {
        val e = engine("mimo")
        val s = e.state.settings
        val mine = SpeechSettings(ttsProviderId = "p-mimo", ttsModel = "mimo-v2.5-tts", ttsVoice = "冰糖", ttsSpeed = 1.2, autoRead = true, asrProviderId = "p-mimo")
        assertTrue(e.call(Command.SaveSettings(s.copy(speech = mine))).ok)
        assertEquals(mine, e.state.settings.speech)

        // 1.6.0 的手机（schema = 3）不认识这块，发来的是默认值：电脑上设好的要保留
        assertTrue(e.call(Command.SaveSettings(s.copy(schema = 3, speech = SpeechSettings()))).ok)
        assertEquals(mine, e.state.settings.speech)

        // 这一版的界面能改回来
        assertTrue(e.call(Command.SaveSettings(s.copy(speech = SpeechSettings(autoRead = false)))).ok)
        assertEquals(SpeechSettings(autoRead = false), e.state.settings.speech)
        assertEquals(4, Settings.SCHEMA, "1.7.0 加了语音，schema 是 4")
    }

    @Test
    fun settingsRoundTrip() {
        // 旧版存下来的设置里没有 speech 这一块，读进来不会崩
        val old = AppJson.decodeFromString(Settings.serializer(), """{"schema":3}""")
        assertEquals(SpeechSettings(), old.speech)
        val text = AppJson.encodeToString(Settings.serializer(), old.copy(speech = SpeechSettings(autoRead = true, ttsSpeed = 1.5)))
        assertTrue("\"autoRead\":true" in text, text)
        assertEquals(1.5, AppJson.decodeFromString(Settings.serializer(), text).speech.ttsSpeed, 1e-9)
    }

    @Test
    fun speechAudioRoundTrip() {
        val a = SpeechAudio(mime = "audio/wav", segments = listOf("AAA=", "BBB="), label = "mimo-v2.5-tts · 2 段", chars = 30)
        assertEquals(a, AppJson.decodeFromString(SpeechAudio.serializer(), AppJson.encodeToString(SpeechAudio.serializer(), a)))
    }
}
