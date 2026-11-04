package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.PhotoEdit
import com.guixing.jixunying.engine.Pixels
import com.guixing.jixunying.engine.SpeechText
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.decodePixels
import com.guixing.jixunying.engine.encodePixels
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.ChannelPart
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ImageGenSettings
import com.guixing.jixunying.model.MediaPick
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.USER_ID
import com.guixing.jixunying.model.VideoGenSettings
import com.guixing.jixunying.model.VideoMediaUse
import io.ktor.http.ContentType
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
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 自己挑模型、换着试（2026-11-03 用户：「让 AI 也能调用模型，包括画图修图视频语音等等，只有成员他自己是换不了的……
 * 如果一张图修不好，让他多试几个」）：P 图里的补画（框里交给改图模型、程序只贴回框里）、三种改图接口的请求、
 * 再试的花钱上限、画图写 model / replaces、配音。全用假服务，不花钱。
 */
class RepaintTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    /** 假模型依次回的话：工具调用（名字 + 参数）或者一句话。参数里的 {{last}} 换成上一个工具结果里的文件名。 */
    private val script = ConcurrentLinkedQueue<Pair<String, String>>()
    /** 假千问收到的改图请求（原文）。 */
    private val edits = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
    private val painted = Collections.synchronizedList(mutableListOf<String>())
    private val spoken = Collections.synchronizedList(mutableListOf<String>())
    private val videoAsks = Collections.synchronizedList(mutableListOf<String>())
    private val served = ConcurrentHashMap<String, ByteArray>()

    /** 一段 3 秒的真视频（FFmpeg 现做，MPEG-4，前一半红、后一半蓝，没有声音）：假万相做出来的就是它。 */
    private val clip: ByteArray by lazy {
        val f = File(dir, "clip-src.mp4")
        val r = FFmpegFrameRecorder(f, 320, 240)
        r.format = "mp4"; r.videoCodec = avcodec.AV_CODEC_ID_MPEG4; r.frameRate = 10.0; r.videoBitrate = 400_000
        r.start()
        val conv = Java2DFrameConverter()
        repeat(30) { i ->
            val img = BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR)
            val g = img.createGraphics(); g.color = if (i < 15) Color(220, 30, 30) else Color(30, 30, 220); g.fillRect(0, 0, 320, 240); g.dispose()
            r.record(conv.convert(img))
        }
        r.stop(); r.release()
        f.readBytes().also { f.delete() }
    }
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    private val GREEN = 0x22AA44

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-repaint").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    requests += req
                    var (name, payload) = script.poll() ?: ("" to "好的。")
                    if ("{{last}}" in payload) {
                        val lastTool = req["messages"]!!.jsonArray.map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "tool" }["content"]!!.jsonPrimitive.content
                        payload = payload.replace("{{last}}", Regex("《([^》]+)》").find(lastTool)!!.groupValues[1])
                    }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun delta(d: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$d}]}\n\n"); flush() }
                        if (name.isNotEmpty()) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(payload)))
                            val tc = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("t${requests.size}"), "type" to JsonPrimitive("function"), "function" to fn))
                            delta(JsonObject(mapOf("tool_calls" to JsonArray(listOf(tc)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        } else {
                            delta(JsonObject(mapOf("content" to JsonPrimitive(payload))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                        }
                        write("data: [DONE]\n\n"); flush()
                    }
                }
                // 假千问：千问图像、万相 2.7 改图（同步）。框里涂绿，整张再偏一点颜色（像真模型那样重画了一遍）
                post("/api/v1/services/aigc/multimodal-generation/generation") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    edits += "sync" to body
                    val content = body["input"]!!.jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
                    val image = content.firstNotNullOfOrNull { it["image"]?.jsonPrimitive?.contentOrNull }
                    val out = if (image == null) flat(64, 64, 0x3366CC) else {
                        val src = decodePixels(Base64.getDecoder().decode(image.substringAfter("base64,")))!!
                        val bbox = (body["parameters"]!!.jsonObject["bbox_list"] as? JsonArray)?.get(0)?.jsonArray?.get(0)?.jsonArray?.map { it.jsonPrimitive.content.toInt() }
                        // 千问不收框：截的那块框在正中（四周各留一样宽的参照），照这个涂
                        val b = bbox ?: listOf(src.w / 4, src.h / 3, src.w * 3 / 4, src.h * 2 / 3)
                        regenerate(src, b)
                    }
                    served["e${edits.size}"] = encodePixels(out)
                    call.respondText("""{"output":{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"image":"http://127.0.0.1:$port/img/e${edits.size}"}]}}]}}""", ContentType.Application.Json)
                }
                // 假万相 2.1 通用图像编辑：只能异步，照遮罩涂
                post("/api/v1/services/aigc/image2image/image-synthesis") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    edits += ("async:" + call.request.headers["X-DashScope-Async"]) to body
                    val input = body["input"]!!.jsonObject
                    val src = decodePixels(Base64.getDecoder().decode(input["base_image_url"]!!.jsonPrimitive.content.substringAfter("base64,")))!!
                    val mask = decodePixels(Base64.getDecoder().decode(input["mask_image_url"]!!.jsonPrimitive.content.substringAfter("base64,")))!!
                    val out = src.copy()
                    for (i in out.argb.indices) if (mask.argb[i] and 0xFFFFFF == 0xFFFFFF) out.argb[i] = (0xFF shl 24) or GREEN
                    served["w1"] = encodePixels(out)
                    call.respondText("""{"output":{"task_id":"w1","task_status":"PENDING"}}""", ContentType.Application.Json)
                }
                get("/api/v1/tasks/w1") {
                    call.respondText("""{"output":{"task_id":"w1","task_status":"SUCCEEDED","results":[{"url":"http://127.0.0.1:$port/img/w1"}]}}""", ContentType.Application.Json)
                }
                // 假千问AI平台的异步画图（2026-11-04）：可灵、Vidu 走 image-generation（要 X-DashScope-Async），老万相走 text2image；
                // 提示词写「未开通」的照真平台那样：任务照建，然后失败「The product is not activated」
                post("/api/v1/services/aigc/image-generation/generation") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    edits += ("ig:" + call.request.headers["X-DashScope-Async"]) to body
                    val id = "g${edits.size}"
                    served[id] = encodePixels(flat(64, 64, 0x3366CC))
                    if ("未开通" in body.toString()) served["$id-fail"] = ByteArray(0)
                    call.respondText("""{"output":{"task_id":"$id","task_status":"PENDING"}}""", ContentType.Application.Json)
                }
                post("/api/v1/services/aigc/text2image/image-synthesis") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    edits += ("t2i:" + call.request.headers["X-DashScope-Async"]) to body
                    val id = "g${edits.size}"
                    served[id] = encodePixels(flat(64, 64, 0x3366CC))
                    call.respondText("""{"output":{"task_id":"$id","task_status":"PENDING"}}""", ContentType.Application.Json)
                }
                get("/api/v1/tasks/{id}") {
                    val id = call.parameters["id"]!!
                    if (served.containsKey("$id-fail")) call.respondText("""{"output":{"task_id":"$id","task_status":"FAILED","code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation."}}""", ContentType.Application.Json)
                    else call.respondText("""{"output":{"task_id":"$id","task_status":"SUCCEEDED","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"image":"http://127.0.0.1:$port/img/$id","type":"image"}]}}]}}""", ContentType.Application.Json)
                }
                get("/img/{id}") { call.respondBytes(served[call.parameters["id"]]!!, ContentType.Image.PNG) }
                // 假画图（OpenAI 风格）：记下用的哪个模型
                post("/v1/images/generations") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    painted += body["model"]!!.jsonPrimitive.content
                    val b = encodePixels(flat(64, 64, if (painted.size == 1) 0x3366CC else 0xCC6633))
                    call.respondText("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(b)}"}]}""", ContentType.Application.Json)
                }
                // 假万相做视频（只有异步）：做出来的是一段真的小视频（clip），程序要从里面取得出画面
                post("/api/v1/services/aigc/video-generation/video-synthesis") {
                    videoAsks += call.receiveText()
                    call.respondText("""{"request_id":"r","output":{"task_id":"rv","task_status":"PENDING"}}""", ContentType.Application.Json)
                }
                get("/api/v1/tasks/rv") {
                    call.respondText("""{"output":{"task_id":"rv","task_status":"SUCCEEDED","video_url":"http://127.0.0.1:$port/vid/clip.mp4"},"usage":{"duration":3.0,"output_video_duration":3.0,"video_count":1}}""",
                        ContentType.Application.Json)
                }
                get("/vid/clip.mp4") { call.respondBytes(clip, ContentType.parse("video/mp4")) }
                // 假智谱读出来：回一段 wav
                post("/zhipu/v4/audio/speech") {
                    val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                    spoken += body["model"]!!.jsonPrimitive.content + ":" + body["input"]!!.jsonPrimitive.content
                    call.respondBytes(SpeechText.wavOf(16000, 1, 16, ByteArray(32000) { (it % 50).toByte() }), ContentType.parse("audio/wav"))
                }
            }
        }.also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        fake.stop(0, 0)
        dir.deleteRecursively()
    }

    // —— 假图 ——

    private fun flat(w: Int, h: Int, rgb: Int) = Pixels(w, h, IntArray(w * h) { (0xFF shl 24) or rgb })

    /** 有花纹的图（横竖渐变加几块色块），补画贴回去时框外每个像素都要原样。 */
    private fun photo(): Pixels {
        val img = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 600) for (x in 0 until 800) img.setRGB(x, y, Color(80 + x / 8, 90 + y / 6, 120 + (x + y) / 20).rgb)
        val g = img.createGraphics()
        g.color = Color(200, 60, 60); g.fillOval(360, 270, 80, 60) // 要去掉的那个红色的东西
        g.color = Color(30, 30, 30); g.fillRect(40, 40, 120, 80)
        g.dispose()
        return Pixels(800, 600, img.getRGB(0, 0, 800, 600, null, 0, 800))
    }

    /** 假模型「重画」：整张每个通道 +12（模型常见的整体偏色），框里涂绿。 */
    private fun regenerate(src: Pixels, b: List<Int>): Pixels {
        val out = Pixels(src.w, src.h, IntArray(src.argb.size) { i ->
            val c = src.argb[i]
            fun ch(sh: Int) = ((c shr sh and 255) + 12).coerceAtMost(255) shl sh
            (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        })
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) out.argb[y * out.w + x] = (0xFF shl 24) or ((GREEN shr 16 and 255) + 12 shl 16) or ((GREEN shr 8 and 255) + 12 shl 8) or ((GREEN and 255) + 12)
        return out
    }

    private fun near(c: Int, rgb: Int, tol: Int) = (0..2).all { kotlin.math.abs((c shr (16 - 8 * it) and 255) - (rgb shr (16 - 8 * it) and 255)) <= tol }

    // —— 纯算的 ——

    @Test
    fun blendBackKeepsOutsideAndTakesOffTheTint() = runBlocking {
        val src = photo()
        val box = intArrayOf(340, 250, 460, 350)
        val r = PhotoEdit.runAll(src, listOf(Json.parseToJsonElement("""{"op":"redraw","box":[340,250,460,350],"prompt":"去掉红的，补成背景"}""").jsonObject), { null }) { req ->
            // 框在截的那块里的位置对得上：加回截取的左上角就是原来的框
            val c = PhotoEdit.redrawCrop(800, 600, box)
            assertEquals(box.toList(), listOf(req.box[0] + c[0], req.box[1] + c[1], req.box[2] + c[0], req.box[3] + c[1]))
            assertEquals(c[2] - c[0] to c[3] - c[1], req.crop.w to req.crop.h)
            assertEquals("去掉红的，补成背景", req.prompt)
            PhotoEdit.RedrawResult(regenerate(req.crop, listOf(req.box[0], req.box[1], req.box[2], req.box[3])), "假模型", "约 0.2 元")
        }
        val d = PhotoEdit.diff(src, r.out)!!
        assertTrue(d.box!![0] >= box[0] && d.box!![1] >= box[1] && d.box!![2] <= box[2] && d.box!![3] <= box[3], "框外一个像素都不能动：${d.box!!.toList()}")
        // 框正中是绿的，整体偏的 +12 扣掉了
        assertTrue(near(r.out.argb[300 * 800 + 400], GREEN, 2), Integer.toHexString(r.out.argb[300 * 800 + 400]))
        assertTrue(r.notes.any { "偏了颜色" in it }, r.notes.toString())
        assertTrue("补画" in r.label && "假模型" in r.summary, r.summary)
    }

    /** 11-03 训练：让换背景，好几位把整张图框进去交给模型。框超过全图一半就不做（真模型会把要留的猫也重画掉）。 */
    @Test
    fun hugeBoxIsRefused() = runBlocking {
        var asked = 0
        val e = runCatching {
            PhotoEdit.runAll(photo(), listOf(Json.parseToJsonElement("""{"op":"redraw","box":[0,0,800,600],"prompt":"背景换成海边"}""").jsonObject), { null }) { req ->
                asked++; PhotoEdit.RedrawResult(req.crop, "假模型")
            }
        }.exceptionOrNull()
        assertTrue(e is PhotoEdit.Problem && "一半" in e.message!! && "100%" in e.message!!, e.toString())
        assertEquals(0, asked, "不该交给模型")
        // 一半以内照做
        PhotoEdit.runAll(photo(), listOf(Json.parseToJsonElement("""{"op":"redraw","box":[0,0,400,600],"prompt":"左边换成海"}""").jsonObject), { null }) { req ->
            asked++; PhotoEdit.RedrawResult(req.crop, "假模型")
        }
        assertEquals(1, asked)
    }

    @Test
    fun cropAndSizesFollowTheRules() {
        // 细长的框：截的那块长宽比不超过 3:1
        val c = PhotoEdit.redrawCrop(2000, 1500, intArrayOf(500, 700, 1500, 740))
        val w = c[2] - c[0]; val h = c[3] - c[1]
        assertTrue(w <= h * 3 + 2, "$w×$h")
        assertTrue(c[0] <= 500 && c[1] <= 700 && c[2] >= 1500 && c[3] >= 740)
        // 千问：总像素 512²～2048²，宽高 16 的倍数，比例不变
        val (qw, qh) = PhotoEdit.fitArea(400, 300, 512 * 512, 2048 * 2048)
        assertTrue(qw % 16 == 0 && qh % 16 == 0 && qw.toLong() * qh in 512L * 512..2048L * 2048, "$qw×$qh")
        assertTrue(kotlin.math.abs(qw.toDouble() / qh - 4.0 / 3) < 0.03)
        // 大图缩到 2048² 以内
        val (bw, bh) = PhotoEdit.fitArea(3000, 2500, 768 * 768, 2048 * 2048)
        assertTrue(bw.toLong() * bh <= 2048L * 2048)
        // 万相 2.1：每边 512～4096
        val (sw, sh) = PhotoEdit.fitSides(300, 200, 512, 4096)
        assertEquals(512, minOf(sw, sh))
        // 遮罩：框里白、框外黑
        val m = PhotoEdit.maskOf(10, 10, intArrayOf(2, 2, 5, 5))
        assertEquals(0xFFFFFF, m.argb[3 * 10 + 3] and 0xFFFFFF)
        assertEquals(0, m.argb[0] and 0xFFFFFF)
    }

    @Test
    fun pickingModelsByName() {
        val qw = ProviderConfig("pq", "qianwen", "假千问", "http://127.0.0.1:1/compatible-mode/v1", "k", listOf(ModelInfo("qwen-image-3.0", tools = false, imageGen = true)))
        val st = AppState(providers = listOf(qw), settings = Settings(videoGen = VideoGenSettings(enabled = true)))
        // 改图：默认 qwen-image-3.0，万相 2.7、2.1 用同一个 Key 也能挑
        assertEquals(listOf("qwen-image-3.0", "wan2.7-image", "wanx2.1-imageedit"), MediaPick.editCandidates(st).map { it.modelId })
        assertEquals("wan2.7-image", MediaPick.edit(st, "wan2.7-image（假千问）")?.modelId)
        assertEquals("qwen-image-3.0-pro", MediaPick.edit(st, "qwen-image-3.0-pro")?.modelId)
        assertNull(MediaPick.edit(st, "cogview-4"))
        // 画图
        assertEquals("qwen-image-3.0", MediaPick.image(st, "QWEN-IMAGE-3.0")?.modelId)
        assertNull(MediaPick.image(st, "gpt-image-1"))
        // 做视频：没有 MiniMax 的 Key、文生视频的模型收不了首帧，都说清楚
        assertTrue(MediaPick.video(st, "MiniMax-H3", emptySet()).second!!.contains("MiniMax"))
        assertTrue(MediaPick.video(st, "wan2.7-t2v", setOf(VideoMediaUse.FIRST)).second!!.contains("首帧"))
        assertEquals("wan3.0-video", MediaPick.video(st, "wan3.0-video", setOf(VideoMediaUse.FIRST)).first?.model?.id)
        // 估价：千问图像 0.18 元一张，查不到的按 0.5
        assertEquals(0.18, MediaPick.imageCost(st, "pq", "qwen-image-3.0"), 1e-9)
        assertEquals(0.14, MediaPick.imageCost(st, "pq", "wanx2.1-imageedit"), 1e-9)
        assertEquals(MediaPick.UNKNOWN_IMAGE, MediaPick.imageCost(st, "pq", "没听说过的模型"), 1e-9)
    }

    // —— 接进对话 ——

    private suspend fun world(budget: Double = 2.0, speech: Boolean = false): Engine {
        val e = Engine(Storage(dir), isPhone = true)
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "k", listOf(ModelInfo("fake-chat", vision = true)))))
        e.call(Command.SaveProvider(ProviderConfig("pq", "qianwen", "假千问", "http://127.0.0.1:$port/compatible-mode/v1", "k",
            listOf(ModelInfo("qwen-image-3.0", tools = false, imageGen = true), ModelInfo("wan2.7-image", tools = false, imageGen = true)))))
        e.call(Command.SaveProvider(ProviderConfig("pimg", "custom", "假画图", "http://127.0.0.1:$port/v1", "k",
            listOf(ModelInfo("fake-a", tools = false, imageGen = true), ModelInfo("fake-b", tools = false, imageGen = true)))))
        if (speech) e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "假智谱", "http://127.0.0.1:$port/zhipu/v4", "k", listOf(ModelInfo("glm-4-flash")))))
        e.call(Command.SaveMember(Member("m1", "阿测", providerId = "p1", modelId = "fake-chat")))
        val s = e.state.settings
        e.call(Command.SaveSettings(s.copy(imageGen = ImageGenSettings("pimg", "fake-a", "1024x1024", retryBudget = budget),
            memory = s.memory.copy(enabled = false, autoExtract = false, stances = false, aiVerify = false), relay = s.relay.copy(enabled = false))))
        return e
    }

    private suspend fun Engine.newChat(): String {
        call(Command.CreateConversation(listOf("m1"), "测试"))
        return state.conversations.first().id
    }

    private fun upload(name: String, bytes: ByteArray): String {
        val a = Attachment("up${name.hashCode().toString(16)}", name, "image/png", bytes.size.toLong(), AttachmentKind.IMAGE)
        Storage(dir).putFile(a, bytes, null)
        return a.id
    }

    private suspend fun Engine.answer(convId: String): Message = withTimeout(30_000) {
        while (true) {
            delay(50)
            val m = store.messages.value[convId].orEmpty().lastOrNull()
            if (m != null && m.role == Role.AI && m.status != MsgStatus.STREAMING && m.status != MsgStatus.PENDING) return@withTimeout m
        }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    private fun toolResults(r: JsonObject) = r["messages"]!!.jsonArray.map { it.jsonObject }
        .filter { it["role"]?.jsonPrimitive?.content == "tool" }.map { it["content"]!!.jsonPrimitive.content }

    @Test
    fun redrawSendsTheCutAndPastesBackOnlyTheBox() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val src = photo()
        val id = upload("a.png", encodePixels(src))
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"redraw","box":[300,250,500,350],"prompt":"去掉红的东西，补成周围的背景"}]}"""
        script += "" to "补好了，框里是模型画的。"
        e.call(Command.SendMessage(conv, "把中间那个红的去掉", listOf(id)))
        val a = e.answer(conv)
        assertEquals(MsgStatus.DONE, a.status)
        assertEquals(listOf(true), a.tools.map { it.ok }, a.tools.toString())
        // 发给假千问的：qwen-image-3.0，原图截的一块（JPEG），一句话，尺寸在千问的范围里
        val (_, body) = edits.single()
        assertEquals("qwen-image-3.0", body["model"]!!.jsonPrimitive.content)
        val content = body["input"]!!.jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(content[0]["image"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals("去掉红的东西，补成周围的背景", content[1]["text"]!!.jsonPrimitive.content)
        val size = body["parameters"]!!.jsonObject["size"]!!.jsonPrimitive.content.split('*').map { it.toInt() }
        assertTrue(size[0].toLong() * size[1] in 512L * 512..2048L * 2048, size.toString())
        // 成品：框外和原图一个像素不差，框里是绿的
        val out = decodePixels(Storage(dir).fileBytes(a.attachments.single().id)!!)!!
        val d = PhotoEdit.diff(src, out)!!
        assertTrue(d.box!![0] >= 300 && d.box!![1] >= 250 && d.box!![2] <= 500 && d.box!![3] <= 350, d.box!!.toList().toString())
        assertTrue(near(out.argb[300 * 800 + 400], GREEN, 6), Integer.toHexString(out.argb[300 * 800 + 400]))
        // 回给模型的：用的哪个模型、多少钱、要跟用户说是模型画的
        val tool = toolResults(requests[1]).last()
        assertTrue("qwen-image-3.0" in tool && "0.18" in tool && "模型画的" in tool && "范围外一个像素都没变" in tool, tool)
        // 工具说明里有 redraw 和能用的模型
        assertTrue("redraw" in requests[0]["tools"].toString() && "wanx2.1-imageedit" in requests[0]["tools"].toString())
    }

    @Test
    fun wan27GetsTheBoxAndWanx21GetsAMask() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val src = photo()
        val id = upload("a.png", encodePixels(src))
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"redraw","box":[300,250,500,350],"prompt":"补成背景","model":"wan2.7-image"}]}"""
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"redraw","box":[300,250,500,350],"prompt":"补成背景","model":"wanx2.1-imageedit"}]}"""
        script += "" to "试了两个。"
        e.call(Command.SendMessage(conv, "去掉红的", listOf(id)))
        val a = e.answer(conv)
        assertEquals(2, a.tools.count { it.kind == "photo" && it.ok }, a.tools.toString())
        // 万相 2.7：同步，parameters.bbox_list 里是框在发过去那张图上的位置
        val (_, wan) = edits[0]
        assertEquals("wan2.7-image", wan["model"]!!.jsonPrimitive.content)
        val p = wan["parameters"]!!.jsonObject
        val bb = p["bbox_list"]!!.jsonArray[0].jsonArray[0].jsonArray.map { it.jsonPrimitive.content.toInt() }
        val sz = p["size"]!!.jsonPrimitive.content.split('*').map { it.toInt() }
        assertTrue(sz[0].toLong() * sz[1] >= 768L * 768, sz.toString())
        assertTrue(bb[0] > 0 && bb[2] < sz[0] && bb[1] > 0 && bb[3] < sz[1] && bb[2] > bb[0] && bb[3] > bb[1], "$bb / $sz")
        // 万相 2.1：异步，带遮罩，遮罩和原图一样大
        val (how, wx) = edits[1]
        assertEquals("async:enable", how)
        val input = wx["input"]!!.jsonObject
        assertEquals("description_edit_with_mask", input["function"]!!.jsonPrimitive.content)
        val base = decodePixels(Base64.getDecoder().decode(input["base_image_url"]!!.jsonPrimitive.content.substringAfter("base64,")))!!
        val mask = decodePixels(Base64.getDecoder().decode(input["mask_image_url"]!!.jsonPrimitive.content.substringAfter("base64,")))!!
        assertEquals(base.w to base.h, mask.w to mask.h)
        assertTrue(minOf(base.w, base.h) >= 512)
        // 重做只留最后一张；框外照样一个像素不差
        val out = decodePixels(Storage(dir).fileBytes(a.attachments.single().id)!!)!!
        val d = PhotoEdit.diff(src, out)!!
        assertTrue(d.box!![0] >= 300 && d.box!![2] <= 500 && d.box!![1] >= 250 && d.box!![3] <= 350, d.box!!.toList().toString())
    }

    /** 再试的上限：第一次不拦，之后累计超过上限就不做，照实说。 */
    @Test
    fun retryBudgetStopsExtraTries() = runBlocking {
        val e = world(budget = 0.3)
        val conv = e.newChat()
        val id = upload("a.png", encodePixels(photo()))
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"redraw","box":[300,250,500,350],"prompt":"补成背景"}]}"""
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"redraw","box":[300,250,500,350],"prompt":"补成背景","model":"wan2.7-image"}]}"""
        script += "" to "只试了一次。"
        e.call(Command.SendMessage(conv, "去掉红的", listOf(id)))
        val a = e.answer(conv)
        assertEquals(1, edits.size, "第二次超过 0.3 元，不该发出去")
        assertEquals(listOf(true, false), a.tools.map { it.ok })
        val tool = toolResults(requests[2]).last()
        assertTrue("不能再试了" in tool && "0.3" in tool, tool)
        assertEquals(1, a.attachments.size, "第一次的结果还在")
        // 不再试（0）：只做第一次
        val e0 = world(budget = 0.0)
        assertEquals(0.0, e0.state.settings.imageGen.retryBudget)
    }

    /** 画图写 model 就用它；不满意换一个重画，replaces 换掉上一张，用户只收到一张。 */
    @Test
    fun drawWithModelAndReplace() = runBlocking {
        val e = world()
        val conv = e.newChat()
        script += "generate_image" to """{"prompt":"一只猫","model":"fake-b"}"""
        script += "generate_image" to """{"prompt":"一只猫，字写清楚","model":"fake-a","replaces":"{{last}}"}"""
        script += "generate_image" to """{"prompt":"一只猫","model":"没有这个模型"}"""
        script += "" to "画好了。"
        e.call(Command.SendMessage(conv, "画一只猫"))
        val a = e.answer(conv)
        assertEquals(listOf("fake-b", "fake-a"), painted.toList())
        assertEquals(1, a.attachments.size, "换掉的不发给用户")
        val results = toolResults(requests[3])
        assertTrue("换掉了" in results[1], results[1])
        assertTrue("没有「没有这个模型」" in results[2] && "fake-a" in results[2], results[2])
        assertTrue(a.tools.first().note.contains("fake-b"), a.tools.first().note)
    }

    /** 做视频的提示词写法：按有 Key 的那家给官方写法，用户自己的写法排最前。 */
    @Test
    fun videoToolCarriesPromptGuides() = runBlocking {
        val e = world()
        e.call(Command.SaveSettings(e.state.settings.copy(videoGen = e.state.settings.videoGen.copy(enabled = true, promptGuide = "我的写法：一律写实拍，手持镜头"))))
        val conv = e.newChat()
        script += "" to "好。"
        e.call(Command.SendMessage(conv, "你好"))
        e.answer(conv)
        val video = requests[0]["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "generate_video" }["description"]!!.jsonPrimitive.content
        assertTrue("我的写法：一律写实拍" in video && video.indexOf("我的写法") < video.indexOf("写给万相"), video)
        assertTrue("固定机位" in video && "integrated_multimodal_description" !in video, "只有千问的 Key，不给 MiniMax 的写法")
    }

    /** 配音：读成一个 wav 文件挂在回答上。 */
    @Test
    fun speechMakesAnAudioFile() = runBlocking {
        val e = world(speech = true)
        val conv = e.newChat()
        script += "generate_speech" to """{"text":"生日快乐"}"""
        script += "" to "配好了。"
        e.call(Command.SendMessage(conv, "用语音说一句生日快乐"))
        val a = e.answer(conv)
        assertEquals(listOf("glm-tts:生日快乐"), spoken.toList())
        val att = a.attachments.single()
        assertTrue(att.name.startsWith("语音_") && att.name.endsWith(".wav") && att.mime == "audio/wav", att.toString())
        assertNotNull(SpeechText.parseWav(Storage(dir).fileBytes(att.id)!!))
        assertEquals("speech", a.tools.single().kind)
        assertTrue("generate_speech" in requests[0]["tools"].toString())
    }

    // —— 做好的视频自己看一遍（2026-11-03 用户：「我要每次生成视频他也要看一遍」）——

    private fun msgs(r: JsonObject) = r["messages"]!!.jsonArray.map { it.jsonObject }

    private fun imagesIn(m: JsonObject) = (m["content"] as? JsonArray)?.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image_url" } ?: 0

    private fun contentText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        else -> ""
    }

    private suspend fun videoWorld(): Engine {
        System.setProperty("jxy.videoPollMs", "30")
        val e = world()
        e.call(Command.SaveSettings(e.state.settings.copy(videoGen = e.state.settings.videoGen.copy(enabled = true))))
        return e
    }

    /** 做好就看：取 6 张画面记在附件上，下一条消息里给它看，工具结果让它对照着说；下一轮聊天记录里还带 3 张，写在程序记录里。 */
    @Test
    fun watchesTheVideoItMade() = runBlocking {
        val e = videoWorld()
        val conv = e.newChat()
        script += "generate_video" to """{"prompt":"红布慢慢变成蓝布","duration":"3"}"""
        script += "" to "做好了，看了一遍：前面红、后面蓝。"
        e.call(Command.SendMessage(conv, "做一段红布变蓝布的视频"))
        val a = e.answer(conv)
        assertEquals(MsgStatus.DONE, a.status, a.error)
        assertEquals(1, videoAsks.size)
        val v = a.attachments.single()
        assertTrue(v.isVideo && v.generated, v.toString())
        assertEquals(6, v.frames.size, "画面记在附件上：$v")
        assertTrue(v.seconds in 2..4, v.toString())
        assertTrue("没有声音" in v.note, v.note)
        // 做好后那次请求：工具结果说看了几张、要照实说；紧跟一条带 6 张画面的消息（工具结果里放不了图）
        val after = requests[1]
        val tool = toolResults(after).last()
        assertTrue("视频做好了" in tool && "取了 6 张画面" in tool && "照实说" in tool && "《${v.name}》" in tool, tool)
        val shown = msgs(after).last()
        assertEquals(6, imagesIn(shown))
        assertTrue("你刚做好的《${v.name}》的 6 张画面" in contentText(shown["content"]), contentText(shown["content"]))
        // 取的画面是按时间先后的：头一张红，最后一张蓝
        val urls = (shown["content"] as JsonArray).mapNotNull { (it as? JsonObject)?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.content }
        fun rgb(u: String) = javax.imageio.ImageIO.read(Base64.getDecoder().decode(u.substringAfter(",")).inputStream()).let { Color(it.getRGB(it.width / 2, it.height / 2)) }
        assertTrue(rgb(urls.first()).red > 150 && rgb(urls.last()).blue > 150, "${rgb(urls.first())} / ${rgb(urls.last())}")

        // 下一轮：这段视频带 3 张画面，写在程序记录（user）里，不写进它自己的话
        script += "" to "前面红后面蓝。"
        e.call(Command.SendMessage(conv, "刚才那段怎么样"))
        e.answer(conv)
        val all = msgs(requests.last())
        val texts = all.joinToString("\n") { contentText(it["content"]) }
        assertTrue("你上面这条回答发给用户的视频：${v.name}" in texts && "附了其中 3 张画面" in texts, texts.takeLast(800))
        assertEquals(3, all.sumOf { imagesIn(it) })
        all.filter { it["role"]?.jsonPrimitive?.content == "assistant" }.forEach {
            assertFalse(v.name in contentText(it["content"]), it.toString())
            assertEquals(0, imagesIn(it))
        }
    }

    /** 以前做的、没看过的视频（这个功能以前做的那三段）：用户再问时先看一遍，最近三段各带 3 张画面；每段只读一次。 */
    @Test
    fun oldVideosAreWatchedBeforeAnswering() = runBlocking {
        val st = Storage(dir)
        val e = videoWorld()
        val conv = e.newChat()
        val t = System.currentTimeMillis()
        val list = mutableListOf<Message>()
        // 照用户 11-03 那段微信对话排：三段视频中间夹着问话，第一段到再问「哪个好」隔了 14 条
        fun say(text: String) { list += Message("u${list.size}", conv, Role.USER, USER_ID, text, createdAt = t - 600_000 + list.size * 10_000) }
        fun reply(text: String, video: Int? = null) {
            val atts = video?.let { i ->
                val model = listOf("wan3.0-video", "MiniMax-H3-Max", "happyhorse-1.1-t2v")[i]
                listOf(Attachment("old$i", "视频_10000$i.mp4", "video/mp4", clip.size.toLong(), AttachmentKind.DOCUMENT, note = "15 秒 · 1080P · $model", generated = true)
                    .also { st.putFile(it, clip, null) })
            }.orEmpty()
            list += Message("a${list.size}", conv, Role.AI, "m1", text, createdAt = t - 600_000 + list.size * 10_000, attachments = atts,
                channelParts = atts.map { ChannelPart("w${list.size}", attachmentId = it.id) })
        }
        say("你生成一个美女跳舞的视频"); reply("好了，用的万相。", 0)
        say("还有其他的什么模型"); reply("还有海螺、HappyHorse。")
        say("在用海螺 H3 Max 做一个试试"); reply("好了，海螺 H3 Max。", 1)
        say("还有一个模型 用那个模型，1.1"); reply("好了，HappyHorse 1.1。", 2)
        say("这是个什么模型？哪家的？"); reply("阿里的。")
        say("你觉得这三个视频哪个好"); reply("这三条的画面我这边没读到。")
        say("你为什么读不到"); reply("生成工具没把画面回传给我。")
        st.saveMessages(conv, list)
        // 新开一个引擎读盘（上面那个已经把这个对话的空记录放在内存里了）
        st.saveState(e.state)
        val e2 = Engine(st, isPhone = true)
        e2.call(Command.LoadMessages(conv))
        script += "" to "第一段最好。"
        e2.call(Command.SendMessage(conv, "这三个视频哪个好"))
        e2.answer(conv)
        val all = msgs(requests.last())
        val texts = all.joinToString("\n") { contentText(it["content"]) }
        for (i in 0..2) assertTrue("发给用户的视频：视频_10000$i.mp4" in texts, texts.takeLast(1500))
        assertTrue("已经发到微信" in texts && "附了其中 3 张画面" in texts, texts.takeLast(1500))
        assertEquals(9, all.filter { it["role"]?.jsonPrimitive?.content == "user" }.sumOf { imagesIn(it) }, "三段各 3 张")
        all.filter { it["role"]?.jsonPrimitive?.content == "assistant" }.forEach { assertEquals(0, imagesIn(it)) }
        // 读过的记在附件上；再问一句不再读
        val made = e2.store.messages.value[conv]!!.filter { it.role == Role.AI && it.attachments.isNotEmpty() }.map { it.attachments.single() }
        assertEquals(listOf(6, 6, 6), made.map { it.frames.size })
        val frameFiles = File(dir, "files").listFiles()!!.count { it.name.endsWith(".json") }
        script += "" to "还是第一段。"
        e2.call(Command.SendMessage(conv, "确定吗"))
        e2.answer(conv)
        assertEquals(frameFiles, File(dir, "files").listFiles()!!.count { it.name.endsWith(".json") }, "没有再取一遍画面")
    }

    /** 一次回答里重做视频：replaces 换掉的那段，取过的画面一起删掉。 */
    @Test
    fun replacedVideoTakesItsFramesAlong() = runBlocking {
        val e = videoWorld()
        val conv = e.newChat()
        script += "generate_video" to """{"prompt":"第一版","duration":"3"}"""
        script += "generate_video" to """{"prompt":"第二版","duration":"3","replaces":"{{last}}"}"""
        script += "" to "换成第二版了。"
        e.call(Command.SendMessage(conv, "做一段视频"))
        val a = e.answer(conv)
        assertEquals(2, videoAsks.size)
        val kept = a.attachments.single()
        assertEquals(6, kept.frames.size)
        assertTrue("换掉了" in toolResults(requests[2]).last(), toolResults(requests[2]).last())
        // 文件区里只剩留下的那段和它的 6 张画面
        val ids = File(dir, "files").listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.toSet()
        assertEquals(setOf(kept.id) + kept.frames, ids)
    }

    /**
     * 2026-11-04 用户：「你得让 AI 看到所有的模型 调用所有的模型 不要漏掉」。以前工具说明只列前 8 个，用户的千问AI平台有 12 个画图模型排在前面，
     * MiniMax 的 image-01、智谱的 cogview 全被挤掉，AI 答「MiniMax 没有画图接口」。现在全列（按服务商分组、带价钱），
     * 各家官方的画图模型（千问AI平台全部文生图、智谱 glm-image / cogview-4、MiniMax image-01-live）都算进来。
     */
    @Test
    fun aiSeesEveryImageModel() = runBlocking {
        val qwenListed = listOf("qwen-image-2.0", "qwen-image-2.0-2026-03-03", "qwen-image-2.0-pro", "qwen-image-2.0-pro-2026-03-03", "qwen-image-2.0-pro-2026-04-22",
            "qwen-image-2.0-pro-2026-06-22", "qwen-image-2.1-pro", "qwen-image-3.0", "qwen-image-3.0-pro", "wan2.7-image", "wan2.7-image-pro", "z-image-turbo")
        val qw = ProviderConfig("pq", "qianwen", "假千问", "http://127.0.0.1:$port/compatible-mode/v1", "k", qwenListed.map { ModelInfo(it, tools = false, imageGen = true) })
        val mm = ProviderConfig("pm", "minimax", "假MiniMax", "http://127.0.0.1:$port/v1", "k", listOf(ModelInfo("image-01", tools = false, imageGen = true)))
        val zp = ProviderConfig("pz", "zhipu", "假智谱", "http://127.0.0.1:$port/v4", "k",
            listOf(ModelInfo("cogview-4-250304", tools = false, imageGen = true), ModelInfo("cogview-3-flash", tools = false, imageGen = true)))
        val all = com.guixing.jixunying.model.ImagePick.candidates(listOf(qw, mm, zp)).map { it.modelId }
        for (m in listOf("image-01", "image-01-live", "glm-image", "cogview-4", "cogview-4-250304", "cogview-3-flash", "qwen-image-2.1-turbo", "wan2.6-t2i",
                "wan2.5-t2i-preview", "wan2.2-t2i-flash", "wanx2.1-t2i-turbo", "wanx2.0-t2i-turbo", "wanx-v1", "kling/kling-v3-image-generation", "vidu/viduq2-fast_reference2image"))
            assertTrue(m in all, "少了 $m：$all")
        assertTrue(all.none { it == "wan2.6-image" || it == "wanx2.1-imageedit" }, "文生图要流式的、只能改图的不算画图模型")
        // 平台上转卖的视频模型不能当画图模型
        assertFalse(com.guixing.jixunying.model.Presets.guessImageGen("kling/kling-v3-video-generation"))
        assertTrue(com.guixing.jixunying.model.Presets.guessImageGen("vidu/viduq3-fast_reference2image"))

        // 真跑一轮：工具说明里全列出来
        val e = world()
        e.call(Command.SaveProvider(qw)); e.call(Command.SaveProvider(mm)); e.call(Command.SaveProvider(zp))
        val conv = e.newChat()
        e.call(Command.SendMessage(conv, "生图的还有哪个"))
        e.answer(conv)
        val draw = requests.last()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "generate_image" }
        val desc = draw["parameters"]!!.jsonObject["properties"]!!.jsonObject["model"]!!.jsonObject["description"]!!.jsonPrimitive.content
        for (m in listOf("image-01（约 0.0", "image-01-live", "glm-image", "cogview-3-flash（免费）", "qwen-image-3.0（约 0.18 元一张）", "wanx2.0-t2i-turbo（约 0.04 元一张）",
                "kling/kling-v3-image-generation", "vidu/viduq2-pro_reference2image", "fake-a（价钱没查到，设置里选的）", "假MiniMax：", "假智谱："))
            assertTrue(m in desc, "说明里没有「$m」：$desc")
        assertTrue("要先在千问AI平台控制台开通" in desc)
    }

    /**
     * 千问AI平台每种画图模型走对接口（2026-11-04 照 API 参考核对，又用用户的 Key 故意传错尺寸逐个探过）：
     * 万相 2.7 / 2.6、Z-Image 同步 multimodal-generation；可灵、Vidu 异步 image-generation；老万相异步 text2image；尺寸按各家的限制缩放。
     */
    @Test
    fun eachQwenPlatformImageModelUsesItsOwnApi() = runBlocking {
        val p = ProviderConfig("pq", "qianwen", "假千问", "http://127.0.0.1:$port/compatible-mode/v1", "k")
        val gen = com.guixing.jixunying.engine.ImageGen { null }
        fun last() = edits.last()
        fun params() = last().second["parameters"]!!.jsonObject

        gen.generate(p, "wan2.7-image", "一只猫", "1024x1792")
        assertEquals("sync", last().first)
        assertEquals("wan2.7-image", last().second["model"]!!.jsonPrimitive.content)
        assertTrue(params()["size"]!!.jsonPrimitive.content.let { s -> s.split('*').let { it[0].toInt() * it[1].toInt() in 589_824..4_194_304 } })

        gen.generate(p, "kling/kling-v3-image-generation", "一只猫", "1024x1792")
        assertEquals("ig:enable", last().first, "可灵只能异步")
        assertEquals("9:16", params()["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("2k", params()["resolution"]!!.jsonPrimitive.content)
        assertTrue(last().second["input"]!!.jsonObject["messages"] != null)

        gen.generate(p, "vidu/viduq2-fast_reference2image", "一只猫", "1024x1792")
        assertEquals("ig:enable", last().first)
        assertEquals("768*1376", params()["size"]!!.jsonPrimitive.content, "Vidu 只能从固定尺寸里挑")
        gen.generate(p, "vidu/vidu-image_reference2image", "一只猫", "1792x1024")
        assertEquals("1920*1088", params()["size"]!!.jsonPrimitive.content)

        gen.generate(p, "wanx2.1-t2i-turbo", "一只猫", "1024x1792")
        assertEquals("t2i:enable", last().first)
        assertEquals("一只猫", last().second["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        val (w, h) = params()["size"]!!.jsonPrimitive.content.split('*').map { it.toInt() }
        assertTrue(w in 512..1440 && h in 512..1440 && w % 16 == 0 && h % 16 == 0 && h > w, "每边 512～1440：${w}x$h")

        // 平台上的第三方没开通：说清楚要去开通
        val err = runCatching { gen.generate(p, "vidu/viduq2-fast_reference2image", "未开通", "1024x1024") }.exceptionOrNull()
        assertTrue(err?.message?.contains("还没开通") == true, err?.message)

        // 尺寸缩放
        assertEquals(1024 to 1024, com.guixing.jixunying.engine.ImageGen.fitSize(1024, 1024, 512L * 512, 1440L * 1440, 512, 1440))
        val (fw, fh) = com.guixing.jixunying.engine.ImageGen.fitSize(1080, 1920, 512L * 512, 1L shl 22, 512, 2048, 32)
        assertTrue(fw % 32 == 0 && fh % 32 == 0 && fw.toLong() * fh <= (1L shl 22), "${fw}x$fh")
        assertEquals("1:1", com.guixing.jixunying.engine.ImageGen.klingAspect(1000, 1100))
    }

    /**
     * 2026-11-04 用户：「之前修的那个微信助手看不了视频文件，还是没有修好」：11-03 电脑只有 Edge 时读过一回（25 秒、画面解不开）的 HEVC 视频，
     * 1.15.0 说下次用到时重读，可挑「要读的视频」时只挑没读过的（seconds == 0），重读从来没发生。现在引用它就重读一次，画面给 AI 看。
     */
    @Test
    fun undecodableVideoIsReadAgain() = runBlocking {
        val e = world()
        // JXY_REAL_VIDEO=真视频文件：拿用户那段真的 HEVC 微信视频走一遍（不花钱：模型是假的、没配听写）
        val bytes = System.getenv("JXY_REAL_VIDEO")?.let { File(it).readBytes() } ?: clip
        val old = Attachment("oldvid", "微信视频_18621.mp4", "video/mp4", bytes.size.toLong(), AttachmentKind.DOCUMENT, seconds = 25, note = "画面解不开")
        Storage(dir).putFile(old, bytes, null)
        val conv = e.channelConversation("weixin:me@im.wechat", "微信对话", listOf("m1"), webSearch = false)
        e.channelTurn(conv, listOf(Engine.ChannelInput("> 小白 · 11月3日 13:18：[视频：微信视频_18621.mp4]\n\n你还是继续来分析这个视频", emptyList(), quoted = listOf(old))))
        val meta = Storage(dir).fileMeta("oldvid")!!
        println("重读：${bytes.size} 字节，${meta.seconds} 秒，${meta.frames.size} 张画面，${meta.note}")
        assertEquals(6, meta.frames.size, "重读取到了画面：${meta.note}")
        assertTrue("重读过" in meta.note && "画面解不开" !in meta.note, meta.note)
        val asked = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" }
        val imgs = asked.sumOf { m -> (m["content"] as? JsonArray)?.count { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" } ?: 0 }
        assertTrue(imgs >= 6, "画面带给模型了（$imgs 张）")
        // 只重读一次：再引用不再读
        val files = File(dir, "files").listFiles()!!.size
        e.channelTurn(conv, listOf(Engine.ChannelInput("再看看", emptyList(), quoted = listOf(Storage(dir).fileMeta("oldvid")!!))))
        assertEquals(files, File(dir, "files").listFiles()!!.size)
    }
}
