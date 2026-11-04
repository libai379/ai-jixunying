package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.PhotoEdit
import com.guixing.jixunying.engine.Pixels
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.decodePixels
import com.guixing.jixunying.engine.encodePixels
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.ChannelPart
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.ImageGenSettings
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.USER_ID
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P 图（2026-11-02 用户：「现在的接口都是画图，我需要就是 P 图的……它不可能画准的」）：engine/PhotoEdit.kt 改像素，
 * 还有接进对话以后的来回（假模型）：结果给 AI 看、重做只留最后一张、自己发过的图记录里写清楚。
 */
class PhotoEditTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    /** 假模型依次回的话：工具调用（名字 + 参数）或者一句话。 */
    private val script = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    private val painted = java.util.concurrent.atomic.AtomicInteger(0)
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-photoedit").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    requests += req
                    val (name, payload) = script.poll() ?: ("" to "好的。")
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
                post("/v1/images/generations") {
                    call.receiveText()
                    painted.incrementAndGet()
                    val b = encodePixels(flat(64, 64, 0x3366CC))
                    call.respondText("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(b)}"}]}""", ContentType.Application.Json)
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

    private fun draw(p: Pixels, d: java.awt.Graphics2D.() -> Unit): Pixels {
        val img = BufferedImage(p.w, p.h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, p.w, p.h, p.argb, 0, p.w)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.d()
        g.dispose()
        return Pixels(p.w, p.h, img.getRGB(0, 0, p.w, p.h, null, 0, p.w))
    }

    /** 和真截图一样的结构：灰底，上面一个绿气泡，中间一行灰字，下面一个白气泡。 */
    private fun chat(): Pixels = draw(flat(800, 600, 0xEDEDED)) {
        color = Color(149, 236, 105); fillRoundRect(420, 40, 300, 120, 16, 16)
        color = Color.BLACK; font = Font("Microsoft YaHei", Font.PLAIN, 44); drawString("好的", 500, 118)
        color = Color(255, 196, 40); fillOval(640, 70, 60, 60) // 👌 那样的橙黄色小东西
        color = Color(170, 170, 170); font = Font("Microsoft YaHei", Font.PLAIN, 32); drawString("昨天 晚上7:37", 280, 300)
        color = Color.WHITE; fillRoundRect(60, 400, 500, 120, 16, 16)
        color = Color.BLACK; font = Font("Microsoft YaHei", Font.PLAIN, 40); drawString("通话时长 00:38", 100, 476)
    }

    private fun steps(json: String) = PhotoEdit.steps(Json.parseToJsonElement(json))
    private fun run(p: Pixels, json: String) = PhotoEdit.run(p, steps(json)) { null }

    /** 灰字所在的范围里还有没有不是背景色的像素。 */
    private fun leftIn(p: Pixels, x1: Int, y1: Int, x2: Int, y2: Int, bg: Int): Int {
        var n = 0
        for (y in y1 until y2) for (x in x1 until x2) if (p.argb[y * p.w + x] and 0xFFFFFF != bg) n++
        return n
    }

    private fun outsideChanged(a: Pixels, b: Pixels, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        var n = 0
        for (y in 0 until a.h) for (x in 0 until a.w) if (!(x in x1 until x2 && y in y1 until y2) && a.argb[y * a.w + x] != b.argb[y * a.w + x]) n++
        return n
    }

    // —— 改像素 ——

    @Test
    fun eraseOnFlatTouchesOnlyTheText() {
        val src = chat()
        val r = run(src, """[{"op":"erase","box":[270,265,520,312]}]""")
        assertEquals(0, leftIn(r.out, 270, 260, 520, 320, 0xEDEDED))
        assertEquals(0, outsideChanged(src, r.out, 260, 255, 530, 320), "框外一个像素都不能动")
        val d = PhotoEdit.diff(src, r.out)!!
        assertTrue(d.changed in 1..12000, "只动字：${d.changed}")
    }

    @Test
    fun boxCuttingTextStillErasesWholeGlyphAndSparesNeighbours() {
        val src = chat()
        // 框切到字的上半，也切到了右边：字还是整个擦掉
        val r = run(src, """[{"op":"erase","box":[290,285,480,312]}]""")
        assertTrue(leftIn(r.out, 300, 270, 460, 305, 0xEDEDED) < 40, "被切到的字要整个擦掉，还剩 ${leftIn(r.out, 300, 270, 460, 305, 0xEDEDED)}")
        // 框比较大，碰到了下面的白气泡一条边：气泡（大部分在框外）不能被啃掉
        val s2 = chat()
        val r2 = run(s2, """[{"op":"erase","box":[260,255,540,415]}]""")
        for (x in 300..500 step 20) assertEquals(0xFFFFFF, r2.out.argb[405 * 800 + x] and 0xFFFFFF, "气泡被啃了 x=$x")
        assertEquals(0, leftIn(r2.out, 270, 260, 520, 320, 0xEDEDED))
        assertTrue(r2.notes.any { "没动" in it }, "要告诉 AI 框碰到了别的东西：${r2.notes}")
    }

    @Test
    fun eraseBoxWithNothingSaysSo() {
        val src = chat()
        val r = run(src, """[{"op":"erase","box":[100,200,200,260]}]""")
        assertEquals(0, PhotoEdit.diff(src, r.out)!!.changed)
        assertTrue(r.notes.any { "没有和背景不一样" in it }, r.notes.toString())
    }

    @Test
    fun eraseTextInsideBubbleKeepsBubble() {
        val src = chat()
        val r = run(src, """[{"op":"erase","box":[90,430,540,490]}]""")
        assertEquals(0, leftIn(r.out, 95, 435, 535, 485, 0xFFFFFF), "气泡里的字擦成白的")
        assertEquals(0, outsideChanged(src, r.out, 85, 425, 545, 495))
    }

    /** 真出过的坑（11-02 试验）：只按「偏红」找笔迹，把旁边橙黄色的表情也擦了；红圈周围压缩留下的淡影也要擦干净。 */
    @Test
    fun eraseRedMarkSparesOrangeEmojiAndHalo() {
        val clean = chat()
        val marked = draw(clean) {
            color = Color(244, 53, 28); stroke = java.awt.BasicStroke(9f); drawOval(240, 240, 330, 100)
            // 再压一遍 JPEG，产生色晕
        }
        val jpg = encodePixels(marked, 85)
        val src = decodePixels(jpg)!!
        val r = run(src, """[{"op":"erase","box":[225,225,585,355],"color":"红"}]""")
        var redLeft = 0
        for (y in 225 until 355) for (x in 225 until 585) {
            val c = r.out.argb[y * 800 + x]
            if ((c shr 16 and 255) - (c shr 8 and 255) > 25) redLeft++
        }
        assertEquals(0, redLeft, "红圈和色晕都要没有")
        // 灰字还在（这一步只擦红的）
        assertTrue(leftIn(r.out, 280, 270, 500, 305, 0xEDEDED) > 300, "灰字不该被擦")
        // 范围包住表情也不擦
        val r2 = run(src, """[{"op":"erase","box":[225,50,720,355],"color":"红"}]""")
        val emoji = r2.out.argb[100 * 800 + 670]
        assertTrue((emoji shr 8 and 255) > 150 && (emoji and 255) < 110, "表情被擦了：${Integer.toHexString(emoji)}")
    }

    /** 背景不是纯色：从周围抄，补完不能留一块明显不一样的颜色；框外不动。 */
    @Test
    fun eraseOnTextureSynthesizes() {
        val rnd = java.util.Random(3)
        val src = Pixels(400, 300, IntArray(400 * 300) { (0xFF shl 24) or ((60 + rnd.nextInt(40)) shl 16) or ((130 + rnd.nextInt(50)) shl 8) or (40 + rnd.nextInt(30)) })
        val marked = draw(src) { color = Color.WHITE; font = Font("Microsoft YaHei", Font.BOLD, 30); drawString("水印", 150, 160) }
        val r = run(marked, """[{"op":"erase","box":[145,125,225,170]}]""")
        for (y in 125 until 170) for (x in 145 until 225) {
            val c = r.out.argb[y * 400 + x]
            assertTrue((c shr 16 and 255) < 110 && (c and 255) < 90, "白字没擦掉 ($x,$y)")
        }
        assertEquals(0, outsideChanged(marked, r.out, 145, 125, 225, 170))
        assertTrue(r.notes.any { "看一眼" in it })
    }

    @Test
    fun otherOperations() {
        val src = chat()
        // 打码：范围里变了，范围外不变，字读不出来（只剩一格一格的）
        val m = run(src, """[{"op":"mosaic","box":[270,265,520,312]}]""")
        assertEquals(0, outsideChanged(src, m.out, 270, 265, 520, 312))
        assertTrue(PhotoEdit.diff(src, m.out)!!.changed > 1000)
        // 裁剪：内容一点不变
        val c = run(src, """[{"op":"crop","box":[400,20,780,200]}]""")
        assertEquals(380, c.out.w); assertEquals(180, c.out.h)
        assertEquals(src.argb[20 * 800 + 400], c.out.argb[0])
        assertEquals(src.argb[199 * 800 + 779], c.out.argb[c.out.argb.size - 1])
        // 转一圈回来一模一样
        val once = run(src, """[{"op":"rotate","angle":90}]""")
        assertEquals(600, once.out.w); assertEquals(800, once.out.h)
        assertEquals(src.argb[0], once.out.argb[599]) // 左上角转到右上角
        val back = run(src, """[{"op":"rotate","angle":90},{"op":"rotate","angle":270}]""")
        assertEquals(0, PhotoEdit.diff(src, back.out)!!.changed)
        val half = run(src, """[{"op":"rotate","angle":180},{"op":"rotate","angle":180}]""")
        assertEquals(0, PhotoEdit.diff(src, half.out)!!.changed)
        // 翻转两次回来
        val fl = run(src, """[{"op":"flip","direction":"horizontal"},{"op":"flip"}]""")
        assertEquals(0, PhotoEdit.diff(src, fl.out)!!.changed)
        val fv = run(src, """[{"op":"flip","direction":"vertical"}]""")
        assertEquals(src.argb[0], fv.out.argb[(src.h - 1) * src.w])
        // 缩放
        val rs = run(src, """[{"op":"resize","width":400}]""")
        assertEquals(400, rs.out.w); assertEquals(300, rs.out.h)
        // 盖色块、调亮度（只动范围内）
        val f = run(src, """[{"op":"fill","box":[10,10,50,50],"color":"#112233"}]""")
        assertEquals(0x112233, f.out.argb[20 * 800 + 20] and 0xFFFFFF)
        val a = run(src, """[{"op":"adjust","box":[10,10,50,50],"brightness":20}]""")
        assertEquals(0, outsideChanged(src, a.out, 10, 10, 50, 50))
        assertNotEquals(src.argb[20 * 800 + 20], a.out.argb[20 * 800 + 20])
        // 画框
        val k = run(src, """[{"op":"mark","box":[100,100,200,200],"shape":"rect","color":"红"}]""")
        assertEquals(0, outsideChanged(src, k.out, 90, 90, 210, 210))
        assertTrue(PhotoEdit.diff(src, k.out)!!.changed > 100)
        // 贴图
        val other = flat(40, 40, 0x00FF00)
        val pasted = PhotoEdit.run(src, steps("""[{"op":"paste","from":"b.png","box":[0,0,40,40]}]""")) { n -> other.takeIf { n == "b.png" } }
        assertEquals(0x00FF00, pasted.out.argb[20 * 800 + 20] and 0xFFFFFF)
        // 带透明的图：alpha 留着
        val trans = Pixels(4, 4, IntArray(16) { 0x80102030.toInt() })
        val tr = decodePixels(encodePixels(trans))!!
        assertEquals(0x80, tr.argb[5] ushr 24)
    }

    @Test
    fun replaceText() {
        val src = chat()
        // 改字：先擦再写，位置、颜色、大小照擦掉的那段
        val r = run(src, """[{"op":"erase","box":[270,265,520,312]},{"op":"text","text":"今天 上午9:00"}]""")
        assertEquals(0, outsideChanged(src, r.out, 260, 250, 530, 330))
        var ink = 0; var x1 = 9999; var x2 = 0
        for (y in 250 until 330) for (x in 260 until 530) {
            val c = r.out.argb[y * 800 + x]
            if (c and 0xFFFFFF != 0xEDEDED) { ink++; x1 = minOf(x1, x); x2 = maxOf(x2, x) }
        }
        assertTrue(ink > 300, "写上去了：$ink")
        assertTrue(x1 in 262..300 && x2 in 460..528, "位置和原来差不多：$x1～$x2")
    }

    @Test
    fun badInputTellsTheModel() {
        val src = chat()
        assertTrue(runCatching { run(src, """[{"op":"erase"}]""") }.exceptionOrNull() is PhotoEdit.Problem)
        assertTrue(runCatching { run(src, """[{"op":"erase","box":[900,900,950,950]}]""") }.exceptionOrNull()?.message!!.contains("800×600"))
        assertTrue(runCatching { run(src, """[{"op":"swirl","box":[1,1,5,5]}]""") }.exceptionOrNull()?.message!!.contains("erase"))
        assertTrue(runCatching { run(src, """[{"op":"rotate","angle":45}]""") }.exceptionOrNull() is PhotoEdit.Problem)
        // steps 被模型写成字符串也认；box 写成字符串也认
        val r = PhotoEdit.run(src, PhotoEdit.steps(JsonPrimitive("""[{"op":"erase","box":"270,265,520,312"}]"""))) { null }
        assertEquals(0, leftIn(r.out, 270, 260, 520, 320, 0xEDEDED))
    }

    @Test
    fun bigPictureCoordinatesAreInWhatTheModelSees() {
        // 原图 3200×2400，模型看到的是 1600×1200：坐标按看到的写
        val src = draw(flat(3200, 2400, 0xEDEDED)) { color = Color.BLACK; fillRect(1000, 1000, 400, 200) }
        val r = run(src, """[{"op":"erase","box":[490,490,710,610]}]""")
        assertEquals(0, leftIn(r.out, 900, 900, 1500, 1300, 0xEDEDED))
    }

    @Test
    fun readsSizeAndExif() {
        val src = chat()
        assertEquals(800 to 600, PhotoEdit.sizeOf(encodePixels(src)))
        val jpg = encodePixels(src, 90)
        assertEquals(800 to 600, PhotoEdit.sizeOf(jpg))
        assertEquals(1, PhotoEdit.orientationOf(jpg))
        assertEquals(1600 to 1200, PhotoEdit.viewSize(3200, 2400))
        // 手机拍的：EXIF 说要顺时针转 90 度
        val turned = withOrientation(jpg, 6)
        assertEquals(6, PhotoEdit.orientationOf(turned))
        assertEquals(600 to 800, PhotoEdit.sizeOf(turned))
        val up = PhotoEdit.load(turned)!!
        assertEquals(600, up.w); assertEquals(800, up.h)
        assertNotNull(PhotoEdit.uprightJpeg(turned))
        assertEquals(null, PhotoEdit.uprightJpeg(jpg))
    }

    /** 给 JPEG 塞一段 EXIF（只有方向这一项）。 */
    private fun withOrientation(jpg: ByteArray, o: Int): ByteArray {
        val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1, 1, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = body.size + 2
        val out = ByteArrayOutputStream()
        out.write(jpg, 0, 2)
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()))
        out.write(body)
        out.write(jpg, 2, jpg.size - 2)
        return out.toByteArray()
    }

    // —— 接进对话 ——

    private fun contentText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        is JsonArray -> el.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("\n")
        else -> ""
    }

    private fun imagesIn(m: JsonObject) = (m["content"] as? JsonArray)?.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image_url" } ?: 0

    private fun msgs(r: JsonObject) = r["messages"]!!.jsonArray.map { it.jsonObject }

    private suspend fun world(vision: Boolean = true, paint: Boolean = true): Engine {
        val st = Storage(dir)
        val e = Engine(st, isPhone = true)
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "k", listOf(ModelInfo("fake-chat", vision = vision)))))
        if (paint) {
            e.call(Command.SaveProvider(ProviderConfig("pimg", "custom", "假画图", "http://127.0.0.1:$port/v1", "k", listOf(ModelInfo("fake-image", tools = false, imageGen = true)))))
            e.call(Command.SaveSettings(e.state.settings.copy(imageGen = ImageGenSettings("pimg", "fake-image", "1024x1024"))))
        }
        e.call(Command.SaveMember(Member("m1", "阿测", providerId = "p1", modelId = "fake-chat")))
        val s = e.state.settings
        // 记录员的后台活也走这个假模型，会抢 script 里的话，关掉
        e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(enabled = false, autoExtract = false, stances = false, aiVerify = false), relay = s.relay.copy(enabled = false))))
        return e
    }

    private suspend fun Engine.newChat(): String {
        call(Command.CreateConversation(listOf("m1"), "测试"))
        return state.conversations.first().id
    }

    private suspend fun Engine.upload(name: String, bytes: ByteArray): String {
        val a = Attachment("up${name.hashCode().toString(16)}", name, "image/png", bytes.size.toLong(), AttachmentKind.IMAGE)
        Storage(dir).putFile(a, bytes, null)
        return a.id
    }

    private suspend fun Engine.answer(convId: String): Message = withTimeout(20_000) {
        while (true) {
            delay(50)
            val m = store.messages.value[convId].orEmpty().lastOrNull()
            if (m != null && m.role == Role.AI && m.status != MsgStatus.STREAMING && m.status != MsgStatus.PENDING) return@withTimeout m
        }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    @Test
    fun chatEditsThePictureAndShowsTheResult() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val src = encodePixels(chat())
        val id = e.upload("微信图片_1.png", src)
        script += "edit_image" to """{"source":"微信图片_1.png","steps":[{"op":"erase","box":[270,265,520,312]}]}"""
        script += "" to "去掉了。"
        e.call(Command.SendMessage(conv, "把这行时间去掉", listOf(id)))
        val a = e.answer(conv)
        assertEquals(MsgStatus.DONE, a.status)
        assertEquals(listOf("photo"), a.tools.map { it.kind })
        val att = a.attachments.single()
        assertEquals(AttachmentKind.GENERATED_IMAGE, att.kind)
        assertTrue(att.name.startsWith("P图_"), att.name)
        val out = decodePixels(Storage(dir).fileBytes(att.id)!!)!!
        assertEquals(0, leftIn(out, 270, 260, 520, 320, 0xEDEDED))
        assertEquals(0, painted.get(), "P 图不该调画图")
        // 第一次请求：图旁边写了文件名和尺寸，工具里有 edit_image
        val first = requests[0]
        assertTrue("edit_image" in first["tools"].toString())
        val userText = msgs(first).filter { it["role"]?.jsonPrimitive?.content == "user" }.joinToString { contentText(it["content"]) }
        assertTrue("微信图片_1.png" in userText && "800×600" in userText, userText)
        // 第二次请求：工具结果里有程序量到的，结果图带给模型看
        val second = msgs(requests[1])
        val tool = second.first { it["role"]?.jsonPrimitive?.content == "tool" }["content"]!!.jsonPrimitive.content
        assertTrue("程序比对" in tool && "范围外一个像素都没变" in tool, tool)
        val shown = second.last()
        assertEquals("user", shown["role"]?.jsonPrimitive?.content)
        assertEquals(1, imagesIn(shown))
        assertTrue("不是用户发的" in contentText(shown["content"]), contentText(shown["content"]))
    }

    @Test
    fun retryKeepsOnlyTheLastAttempt() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val id = e.upload("a.png", encodePixels(chat()))
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"erase","box":[270,265,400,312]}]}"""
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"erase","box":[270,265,520,312]}]}"""
        script += "" to "好了。"
        e.call(Command.SendMessage(conv, "去掉时间", listOf(id)))
        val a = e.answer(conv)
        assertEquals(2, a.tools.count { it.kind == "photo" })
        assertEquals(1, a.attachments.size, "重做的只留最后一张，试错的不发给用户")
        val out = decodePixels(Storage(dir).fileBytes(a.attachments.single().id)!!)!!
        assertEquals(0, leftIn(out, 270, 260, 520, 320, 0xEDEDED))
    }

    @Test
    fun emptyStepsSendsItAgainWithoutRepainting() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val bytes = encodePixels(chat())
        val id = e.upload("a.png", bytes)
        script += "edit_image" to """{"source":"a.png","resend":true}"""
        script += "" to "再发一次。"
        e.call(Command.SendMessage(conv, "我没收到", listOf(id)))
        val a = e.answer(conv)
        assertEquals(1, a.attachments.size)
        assertTrue(Storage(dir).fileBytes(a.attachments[0].id)!!.contentEquals(bytes), "原样")
        assertEquals(0, painted.get())
    }

    /** 11-02 训练：阿麦拿色块一笔一笔拼海边，调了 12 次还没完。一轮最多 P 6 次，一次最多 20 步，到了让它照实说。 */
    @Test
    fun runawayIsStopped() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val id = e.upload("a.png", encodePixels(chat()))
        repeat(8) { script += "edit_image" to """{"source":"a.png","steps":[{"op":"fill","box":[0,0,10,10],"color":"红"}]}""" }
        script += "" to "拼不出来。"
        e.call(Command.SendMessage(conv, "把背景换成海边", listOf(id)))
        val a = e.answer(conv)
        assertEquals(6, a.tools.count { it.kind == "photo" }, "最多真做 6 次")
        assertEquals(1, a.attachments.size)
        val tools = msgs(requests.last()).filter { it["role"]?.jsonPrimitive?.content == "tool" }.map { it["content"]!!.jsonPrimitive.content }
        assertTrue("不能再试了" in tools.last(), tools.last())
        val many = (1..21).joinToString(",") { """{"op":"fill","box":[0,0,1,1],"color":"红"}""" }
        assertTrue(runCatching { run(chat(), "[$many]") }.exceptionOrNull()?.message!!.contains("最多 20 步"))
    }

    /** 11-02 训练：参数没写对（steps 是空的）不能当成做了；一步的内容直接写在外面也认。 */
    @Test
    fun emptyStepsIsNotDone() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val id = e.upload("a.png", encodePixels(chat()))
        script += "edit_image" to """{"source":"a.png"}"""
        script += "edit_image" to """{"source":"a.png","op":"crop","box":[0,0,100,100]}"""
        script += "" to "裁好了。"
        e.call(Command.SendMessage(conv, "裁一下", listOf(id)))
        val a = e.answer(conv)
        val tool = msgs(requests[1]).last { it["role"]?.jsonPrimitive?.content == "tool" }["content"]!!.jsonPrimitive.content
        assertTrue("什么也没改" in tool, tool)
        assertEquals(listOf(false, true), a.tools.map { it.ok })
        assertEquals(100, decodePixels(Storage(dir).fileBytes(a.attachments.single().id)!!)!!.w)
    }

    /** 11-02 训练：阿米学着记录写一行假的「你发给用户的图」，其实一个工具都没调。程序退回去让它重说，字里的假记录也去掉。 */
    @Test
    fun claimWithoutToolIsSentBack() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val id = e.upload("a.png", encodePixels(chat()))
        script += "" to "这次改好了，只擦掉了时间。\n[你发给用户的图：图片_1.png，已经发到微信]"
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"erase","box":[270,265,520,312]}]}"""
        script += "" to "去掉了。\n〔11月3日 01:47〕别的没动。"
        e.call(Command.SendMessage(conv, "把字去掉", listOf(id)))
        val a = e.answer(conv)
        assertEquals("去掉了。\n\n别的没动。", a.content)
        assertEquals(1, a.attachments.size)
        assertEquals(3, requests.size)
        val nudge = msgs(requests[1]).last()
        assertTrue("程序检查" in contentText(nudge["content"]), nudge.toString())
        // 只退回一次：第二次还这么说就算了，假记录照样去掉
        requests.clear()
        script += "" to "改好了。"
        script += "" to "真的改好了。\n[你发给用户的图：图片_2.png]"
        e.call(Command.SendMessage(conv, "再改一下"))
        delay(100)
        val b = withTimeout(20_000) { var m: Message; do { delay(50); m = e.store.messages.value[conv]!!.last() } while (m.role != Role.AI || m.status == MsgStatus.STREAMING || m.id == a.id); m }
        assertEquals("真的改好了。", b.content)
        assertEquals(2, requests.size)
        // 普通聊天不查
        requests.clear()
        script += "" to "饭做好了就趁热吃。"
        e.call(Command.SendMessage(conv, "晚上吃啥好"))
        delay(100)
        withTimeout(20_000) { var m: Message; do { delay(50); m = e.store.messages.value[conv]!!.last() } while (m.role != Role.AI || m.status == MsgStatus.STREAMING || m.id == b.id); m }
        assertEquals(1, requests.size)
    }

    @Test
    fun problemsGoBackToTheModel() = runBlocking {
        val e = world()
        val conv = e.newChat()
        val id = e.upload("a.png", encodePixels(chat()))
        script += "edit_image" to """{"source":"不存在.png","steps":[{"op":"crop","box":[0,0,10,10]}]}"""
        script += "edit_image" to """{"source":"a.png","steps":[{"op":"erase","box":[9000,9000,9100,9100]}]}"""
        script += "" to "没做成。"
        e.call(Command.SendMessage(conv, "裁一下", listOf(id)))
        val a = e.answer(conv)
        assertEquals(0, a.attachments.size)
        assertTrue(a.tools.all { it.kind == "photo" && !it.ok })
        val tools = msgs(requests.last()).filter { it["role"]?.jsonPrimitive?.content == "tool" }.map { it["content"]!!.jsonPrimitive.content }
        assertTrue("找不到" in tools[0] && "a.png" in tools[0], tools[0])
        assertTrue("800×600" in tools[1], tools[1])
    }

    /** 画完给它看一眼；提示词里说清楚画图改不了图。 */
    @Test
    fun drawnPictureIsShownAndPromptTellsTheDifference() = runBlocking {
        val e = world()
        val conv = e.newChat()
        script += "generate_image" to """{"prompt":"一只猫"}"""
        script += "" to "画好了。"
        e.call(Command.SendMessage(conv, "画一只猫"))
        val a = e.answer(conv)
        assertEquals(1, a.attachments.size)
        assertEquals(1, imagesIn(msgs(requests[1]).last()), "画完的图给模型看")
        val sys = contentText(msgs(requests[0]).first()["content"])
        // 2026-11-04 起 generate_image 能带参考图重画，但设定和说明照样写明「是重画、不是在原图上改」
        assertTrue("edit_image" in sys && "不能当成在原图上改" in sys, "设定里分清楚 P 图和画图")
        assertFalse("要改图时调用 generate_image" in sys)
        val toolDefs = requests[0]["tools"].toString()
        assertTrue("不是在原图上改" in toolDefs)
    }

    /** 看不了图的模型：不给 P 图工具，设定里写明。 */
    @Test
    fun noVisionNoPhotoTool() = runBlocking {
        val e = world(vision = false)
        val conv = e.newChat()
        e.call(Command.SendMessage(conv, "你好"))
        e.answer(conv)
        assertFalse("edit_image" in requests[0]["tools"].toString())
        assertTrue("没法改" in contentText(msgs(requests[0]).first()["content"]))
    }

    /** 手机拍的照片带 EXIF 方向：发给模型的是转正的，写的尺寸也是转正后的；P 图的坐标按转正后的算。 */
    @Test
    fun tiltedPhotoIsUpright() = runBlocking {
        val e = world()
        val conv = e.newChat()
        // 原图 800×600，EXIF 说要顺时针转 90 度，看起来是 600×800
        val jpg = withOrientation(encodePixels(chat(), 90), 6)
        val id = e.upload("手机照片.jpg", jpg)
        script += "edit_image" to """{"source":"手机照片.jpg","steps":[{"op":"crop","box":[0,0,600,400]}]}"""
        script += "" to "裁好了。"
        e.call(Command.SendMessage(conv, "只要上半部分", listOf(id)))
        val a = e.answer(conv)
        val first = msgs(requests[0]).first { imagesIn(it) > 0 }
        assertTrue("600×800" in contentText(first["content"]), contentText(first["content"]))
        val url = (first["content"] as JsonArray).mapNotNull { (it as? JsonObject)?.get("image_url")?.jsonObject?.get("url")?.jsonPrimitive?.content }.single()
        val sent = decodePixels(Base64.getDecoder().decode(url.substringAfter(",")))!!
        assertEquals(600 to 800, sent.w to sent.h, "发给模型的是转正的")
        val out = decodePixels(Storage(dir).fileBytes(a.attachments.single().id)!!)!!
        assertEquals(600 to 400, out.w to out.h)
    }

    /** 自己发过的图：记录里写清做了什么、发出去没有，最近的图也带给它看（但不放在 assistant 消息里）。这样用户问「图呢」时它不会以为没做。 */
    @Test
    fun ownPicturesAreTruthfulInHistory() = runBlocking {
        val st = Storage(dir)
        val e = world()
        val conv = e.newChat()
        val png = encodePixels(flat(64, 64, 0x3366CC))
        val a1 = Attachment("g1", "图片_1.png", "image/png", png.size.toLong(), AttachmentKind.GENERATED_IMAGE, note = "一只白猫坐在草坪上")
        st.putFile(a1, png, null)
        val a2 = Attachment("g2", "P图_2.png", "image/png", png.size.toLong(), AttachmentKind.GENERATED_IMAGE, note = "P 图（原图《x.jpg》）：擦掉 [1,2,3,4]")
        st.putFile(a2, png, null)
        val t = System.currentTimeMillis()
        st.saveMessages(conv, listOf(
            Message("u1", conv, Role.USER, USER_ID, "画一只白猫", createdAt = t - 90_000),
            Message("a1", conv, Role.AI, "m1", "做好了。", createdAt = t - 80_000, attachments = listOf(a1), channelParts = listOf(ChannelPart("w1", attachmentId = "g1"))),
            Message("u2", conv, Role.USER, USER_ID, "再 P 一下", createdAt = t - 40_000),
            Message("a2", conv, Role.AI, "m1", "P 好了。", createdAt = t - 30_000, attachments = listOf(a2), channelParts = listOf(ChannelPart("w2", text = "P 好了。"))),
        ))
        // 新开一个引擎读盘（上面那个已经把这个对话的空记录放在内存里了）
        st.saveState(e.state)
        val e2 = Engine(st, isPhone = true)
        e2.call(Command.LoadMessages(conv))
        e2.call(Command.SendMessage(conv, "图呢"))
        e2.answer(conv)
        val all = msgs(requests.last())
        val texts = all.joinToString("\n") { contentText(it["content"]) }
        assertTrue("你上面这条回答发给用户的图：图片_1.png" in texts && "已经发到微信" in texts && "画的是：一只白猫" in texts, texts.takeLast(700))
        // 这些写在 user 里，不写进 AI 自己的话（它会学着写）
        all.filter { it["role"]?.jsonPrimitive?.content == "assistant" }.forEach { assertFalse("发给用户的图" in contentText(it["content"]), it.toString()) }
        assertTrue("发到微信没成功" in texts, "这张的编号没记上，就是没发成：" + texts.takeLast(700))
        // 图只在 user 消息里（assistant 带图接口不认）
        all.filter { it["role"]?.jsonPrimitive?.content == "assistant" }.forEach { assertEquals(0, imagesIn(it)) }
        assertTrue(all.filter { it["role"]?.jsonPrimitive?.content == "user" }.sumOf { imagesIn(it) } == 2, "最近两张自己的图给它看")
        assertFalse(all.first { it["role"]?.jsonPrimitive?.content == "user" }.toString().isEmpty())
    }
}
