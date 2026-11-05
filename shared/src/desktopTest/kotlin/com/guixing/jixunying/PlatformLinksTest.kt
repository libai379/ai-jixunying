package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.HostModels
import com.guixing.jixunying.engine.PageBrowser
import com.guixing.jixunying.engine.SocialLinks
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 读平台链接（2026-10-23，docs/平台链接设计说明.md）：认平台、认「被拦了」、整理给 AI 的文字；
 * 引擎里 AI 调 fetch_url 读抖音 / 小红书链接走本机浏览器（这里换成假的），没开「联网」时贴了网址也给工具。
 */
class PlatformLinksTest {
    private val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    @Volatile private var asrCalls = 0
    /** 假抖音图床收到的下载（Referer）。 */
    private val imgFetches = Collections.synchronizedList(mutableListOf<String>())
    /** 假抖音视频地址收到的下载（Referer）。 */
    private val vidFetches = Collections.synchronizedList(mutableListOf<String>())
    /** 3 秒的小视频（每秒 10 帧）：前一半红、后一半蓝，看取出来的画面是不是按时间先后。 */
    private val clip: ByteArray by lazy {
        val f = File.createTempFile("jxy-clip", ".mp4")
        try {
            val r = FFmpegFrameRecorder(f, 320, 240)
            r.format = "mp4"; r.videoCodec = avcodec.AV_CODEC_ID_MPEG4; r.frameRate = 10.0; r.videoBitrate = 400_000
            r.start()
            val conv = Java2DFrameConverter()
            repeat(30) { i ->
                val img = java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
                val g = img.createGraphics(); g.color = if (i < 15) java.awt.Color(220, 30, 30) else java.awt.Color(30, 30, 220); g.fillRect(0, 0, 320, 240); g.dispose()
                r.record(conv.convert(img))
            }
            r.stop(); r.release()
            f.readBytes()
        } finally { f.delete() }
    }
    private lateinit var dir: File

    /** 假浏览器：按网址回事先存好的页面（照 10-23 真读到的样子删减）。 */
    private class FakeBrowser(val imgBase: String = "") : PageBrowser {
        val opened = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun snapshot(url: String, script: String, settleMs: Long): JsonObject {
            opened += url
            assertEquals(SocialLinks.EXTRACT_JS, script)
            val json = when {
                // 11-04 真读到的抖音图文（删减）：两张图都在页面上，地址带 biz_tag=aweme_images；顶上「1/2」
                "/note/" in url -> """{"url":"$url","title":"","desc":"","text":"精选\n推荐\n登录\n1499\n434\n1/2\n个人观点，仅供参考\nAllenの朋友圈\n与 Claude Code 的神级对话。#ai吐槽大会 #Claude 神级对话\n发布时间：2026-11-02 23:28:38\n评论(434)","videos":[],"images":[],
                    "postImages":["$imgBase/img/1.webp?biz_tag=aweme_images&x-signature=a","$imgBase/img/2.webp?biz_tag=aweme_images&x-signature=b"]}"""
                // 11-05 用户在微信发的那条（删减）：视频地址指到假服务器；/video/404 那条的视频地址下不动
                "douyin.com/video/7704346864333753610" in url || "douyin.com/video/404" in url ->
                    """{"url":"$url","title":"","desc":"","text":"精选\n推荐\n原来网页设计还能这么玩！ #世界渲染大赛 #3D\nAOV视觉科学\n发布时间：2026-11-05 12:01","videos":["https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/uuu_265.mp4","$imgBase/${if ("404" in url) "gone" else "vid"}/1.mp4?__vid=1"],"images":[]}"""
                "douyin.com" in url -> """{"url":"$url","title":"","desc":"","text":"精选\n推荐\n第83集 | 医者医人，亦可被人间治愈。#小城良方\n3095\n162\n发布时间：2026-10-16 07:22\n全部评论\n这个是真想看，21号我们一起追","videos":["https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/uuu_265.mp4","https://v5-dy-ov.zjcdn.com/video/abc.mp4"],"images":[]}"""
                "xiaohongshu.com" in url -> """{"url":"$url","title":"小红书","text":"登录后推荐更懂你的笔记\n扫码","videos":[],"images":[],
                    "note":{"title":"","desc":"#阿凡达3讨论团[话题]#","type":"video","user":"电梯爱看","images":["http://sns-webpic-qc.xhscdn.com/a.webp"],"likes":"700","tags":["阿凡达3讨论团","电影阿凡达3"],"video":"http://sns-video-zl.xhscdn.com/stream/1.mp4"}}"""
                else -> """{"url":"$url","title":"出错啦! - bilibili.com","text":"错误号: 412\n由于触发哔哩哔哩安全风控策略，该次访问请求被拒绝。","videos":[],"images":[]}"""
            }
            return Json.parseToJsonElement(json).jsonObject
        }
        override suspend fun cookieNames(domain: String) = if (domain == "douyin.com") setOf("ttwid", "sessionid") else setOf("buvid3")
        override suspend fun showForLogin(url: String) { opened += "login:$url" }
        override suspend fun hide() {}
        override fun running() = true
        val audioAsked = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun audioPcm(mediaUrl: String, maxSeconds: Int): Pair<ByteArray, Double> {
            audioAsked += mediaUrl
            // 3 秒「有声音」的假 PCM（正弦波），整段说是 200 秒
            val n = 16000 * 3
            val b = ByteArray(n * 2)
            for (i in 0 until n) { val v = (Math.sin(i / 8.0) * 8000).toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
            return b to 200.0
        }
        override fun close() {}
    }

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-platform").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val hasTools = req["tools"] != null
                    if (hasTools) requests += req
                    val task = msgs.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val results = msgs.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                    val url = Regex("https?://\\S+").find(task)?.value
                    val canFetch = req["tools"]?.toString()?.contains("fetch_url") == true
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        when {
                            !hasTools -> chunk(JsonObject(mapOf("content" to JsonPrimitive("[]"))).toString())
                            url != null && canFetch && results.isEmpty() -> {
                                val tool = if ("听一下" in task) "listen_video" else "fetch_url"
                                val fn = JsonObject(mapOf("name" to JsonPrimitive(tool), "arguments" to JsonPrimitive(JsonObject(mapOf("url" to JsonPrimitive(url))).toString())))
                                val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("f1"), "type" to JsonPrimitive("function"), "function" to fn))
                                chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                                write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            }
                            else -> chunk(JsonObject(mapOf("content" to JsonPrimitive("看过了。"))).toString())
                        }
                    }
                }
                // 假抖音图床：回一张 2000×1200 的 WEBP（电脑上 ImageIO 解不了 WEBP，要走 Skia）
                get("/img/{n}") {
                    imgFetches += call.request.headers["Referer"].orEmpty()
                    call.respondBytes(webp(2000, 1200), ContentType("image", "webp"))
                }
                // 假抖音视频地址：回那段 3 秒的小视频
                get("/vid/{n}") {
                    vidFetches += call.request.headers["Referer"].orEmpty()
                    call.respondBytes(clip, ContentType("video", "mp4"))
                }
                // 假的智谱听写：每段回一句
                post("/v1/audio/transcriptions") {
                    call.receiveText()
                    asrCalls++
                    call.respondText("""{"text":"第${asrCalls}段台词。"}""", ContentType.Application.Json)
                }
            }
        }
        fake.start(wait = false)
    }

    @AfterTest
    fun cleanup() { fake.stop(500, 1_000); dir.deleteRecursively() }

    private fun webp(w: Int, h: Int): ByteArray {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFF3366CC.toInt())
        surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(100f, 100f, w / 2f, h / 2f), org.jetbrains.skia.Paint().apply { color = 0xFFFFFFFF.toInt() })
        return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, 80)!!.bytes
    }

    /**
     * 真开 Edge 读真的抖音图文、真下载作品的图，假模型只负责调 fetch_url（不花钱）：看 AI 实际收到的图。
     * JXY_LIVE_POSTIMG=作品链接，JXY_POSTIMG_OUT=把发给模型的图存到哪个文件夹。
     */
    @Test
    fun livePostImages() = runBlocking<Unit> {
        val link = System.getenv("JXY_LIVE_POSTIMG") ?: return@runBlocking
        val out = File(System.getenv("JXY_POSTIMG_OUT") ?: error("要设 JXY_POSTIMG_OUT")).also { it.mkdirs() }
        val profile = kotlin.io.path.createTempDirectory("jxy-browser").toFile()
        val browser = com.guixing.jixunying.engine.EdgeBrowser(com.guixing.jixunying.engine.EdgeBrowser.findBrowser() ?: error("找不到 Edge"), profile)
        try {
            val e = Engine(Storage(File(dir, "live")), browserFactory = { browser })
            e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m", vision = true)))))
            e.call(Command.SaveMember(Member("a1", "阿看", providerId = "p1", modelId = "m")))
            val conv = e.call(Command.CreateConversation(listOf("a1"))).data
            e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
            val t0 = System.currentTimeMillis()
            e.call(Command.SendMessage(conv, "看看这个抖音链接 $link"))
            withTimeout(180_000) { while (e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }?.status != MsgStatus.DONE) delay(100) }
            val last = requests.last()
            val tool = last["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
            println("用了 ${System.currentTimeMillis() - t0} ms\n工具结果：\n" + tool.take(1500))
            val pics = last["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" && it["content"] is JsonArray }
                .flatMap { it["content"]!!.jsonArray.map { p -> p.jsonObject } }
            pics.filter { it["type"]?.jsonPrimitive?.content == "text" }.forEach { println("附图说明：" + it["text"]!!.jsonPrimitive.content) }
            pics.filter { it["type"]?.jsonPrimitive?.content == "image_url" }.forEachIndexed { i, p ->
                val bytes = java.util.Base64.getDecoder().decode(p["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.substringAfter(","))
                val img = javax.imageio.ImageIO.read(bytes.inputStream())
                val f = File(out, "post-${i + 1}.jpg").also { it.writeBytes(bytes) }
                println("图 ${i + 1}：${img.width}×${img.height}，${bytes.size / 1024} KB → $f")
            }
        } finally {
            browser.close()
            // Edge 关了还占着文件一会儿，一次删不干净（11-04 留下 9 MB）：多试几次
            for (i in 0 until 10) { if (profile.deleteRecursively()) break; Thread.sleep(500) }
        }
    }

    /**
     * 2026-11-04 用户在微信发抖音图文「与 Claude Code 的神级对话」，对话全在两张图里，AI 只读到标题和作者（「图没到我眼前」）。
     * 现在：作品的图下载下来（带 Referer）、WEBP 转成 JPEG（长边 1600），附在工具结果后面给能看图的模型看；看不了图的照实说。
     */
    @Test
    fun douyinNoteImagesShownToModel() = runBlocking<Unit> {
        assertTrue(webp(10, 10).copyOfRange(8, 12).decodeToString() == "WEBP")
        val browser = FakeBrowser("http://127.0.0.1:$port")
        val e = Engine(Storage(File(dir, "data")), browserFactory = { browser })
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m", vision = true), ModelInfo("t", vision = false)))))
        e.call(Command.SaveMember(Member("a1", "阿看", providerId = "p1", modelId = "m")))
        e.call(Command.SaveMember(Member("a2", "阿盲", providerId = "p1", modelId = "t")))
        val link = "https://www.douyin.com/note/7703797444894883451"
        suspend fun ask(member: String): Pair<String, JsonObject> {
            val conv = e.call(Command.CreateConversation(listOf(member))).data
            e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
            requests.clear()
            e.call(Command.SendMessage(conv, "0.28 复制打开抖音，看看【Allenの朋友圈的图文作品】与 Claude Code 的神级对话 $link"))
            withTimeout(30_000) { while (e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }?.status != MsgStatus.DONE) delay(30) }
            val last = requests.last()
            val tool = last["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
            return tool to last
        }

        val (seenText, req) = ask("a1")
        assertTrue("与 Claude Code 的神级对话" in seenText && "2 张图都附在后面" in seenText, seenText)
        assertFalse("x-signature" in seenText, "不贴一长串图片地址")
        val pics = req["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" && it["content"] is JsonArray }
            .flatMap { it["content"]!!.jsonArray.map { p -> p.jsonObject } }.filter { it["type"]?.jsonPrimitive?.content == "image_url" }
        assertEquals(2, pics.size, "两张图都给模型看")
        val first = pics[0]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(first.startsWith("data:image/jpeg;base64,"))
        val img = javax.imageio.ImageIO.read(java.util.Base64.getDecoder().decode(first.substringAfter(",")).inputStream())
        assertEquals(1600 to 960, img.width to img.height, "WEBP 解开、缩到长边 1600")
        assertEquals(listOf("https://www.douyin.com/", "https://www.douyin.com/"), imgFetches.toList(), "下载带 Referer")
        val step = e.store.messages.value.values.flatten().last { it.role == Role.AI && it.senderId == "a1" }.tools.last { it.kind == "fetch" }
        assertTrue(step.ok && step.note == "图文 2 张", step.note)

        // 看不了图的模型：不下载，照实说看不了
        imgFetches.clear()
        val (blindText, _) = ask("a2")
        assertTrue("看不了图" in blindText && "一共 2 张图" in blindText, blindText)
        assertTrue(imgFetches.isEmpty())
    }

    /**
     * 抖音视频作品的画面（2026-11-05 用户在微信发「原来网页设计还能这么玩！」，AI 只读到字、听到一声「啊」，答「没拿到视频画面」；
     * 用户：「抖音链接还是打不开」）。现在：视频下载下来（带 Referer），从头到尾均匀取 6 张画面附在后面给能看图的模型看；
     * 看不了图的不下载、照实说；视频下不动的照实说画面没打开。
     */
    @Test
    fun douyinVideoFramesShownToModel() = runBlocking<Unit> {
        val browser = FakeBrowser("http://127.0.0.1:$port")
        val e = Engine(Storage(File(dir, "data")), browserFactory = { browser })
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m", vision = true), ModelInfo("t", vision = false)))))
        e.call(Command.SaveMember(Member("a1", "阿看", providerId = "p1", modelId = "m")))
        e.call(Command.SaveMember(Member("a2", "阿盲", providerId = "p1", modelId = "t")))
        suspend fun ask(member: String, link: String): Pair<String, JsonObject> {
            val conv = e.call(Command.CreateConversation(listOf(member))).data
            e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
            requests.clear()
            e.call(Command.SendMessage(conv, "8.99 复制打开抖音，看看【AOV视觉科学的作品】原来网页设计还能这么玩！ $link"))
            withTimeout(30_000) { while (e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }?.status != MsgStatus.DONE) delay(30) }
            val last = requests.last()
            val tool = last["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
            return tool to last
        }
        fun step(member: String) = e.store.messages.value.values.flatten().last { it.role == Role.AI && it.senderId == member }.tools.last { it.kind == "fetch" }
        val link = "https://www.douyin.com/video/7704346864333753610"

        val (seenText, req) = ask("a1", link)
        assertTrue("原来网页设计还能这么玩" in seenText && "这是视频作品（3 秒），从头到尾均匀取了 6 张画面附在后面" in seenText, seenText)
        val parts = req["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" && it["content"] is JsonArray }
            .flatMap { it["content"]!!.jsonArray.map { p -> p.jsonObject } }
        val label = parts.filter { it["type"]?.jsonPrimitive?.content == "text" }.map { it["text"]!!.jsonPrimitive.content }.firstOrNull { "视频的画面" in it }
        // 3 秒的视频取在第 0.25、0.75……2.75 秒附近（时长 FFmpeg 量出来可能差一点，小数只看个大概）
        // （程序给 AI 看的图前面都带「工具给你看的图，不是用户发的：」）
        assertTrue(label != null && "抖音视频的画面（视频 3 秒，从头到尾均匀取了 6 张，大约在第 0." in label && " 秒，按时间先后）" in label &&
            label.substringAfter("大约在第 ").substringBefore(" 秒").split("、").size == 6, label)
        val frames = parts.filter { it["type"]?.jsonPrimitive?.content == "image_url" }.map {
            javax.imageio.ImageIO.read(java.util.Base64.getDecoder().decode(it["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.substringAfter(",")).inputStream())
        }
        assertEquals(6, frames.size, "6 张画面都给模型看")
        fun red(i: java.awt.image.BufferedImage) = java.awt.Color(i.getRGB(i.width / 2, i.height / 2)).let { it.red > it.blue + 80 }
        assertEquals(listOf(true, true, true, false, false, false), frames.map(::red), "按时间先后：前三张红、后三张蓝")
        assertEquals(listOf("https://www.douyin.com/"), vidFetches.toList(), "下载带 Referer、只下一次")
        assertEquals("视频 3 秒 · 看了 6 张画面", step("a1").note)

        // 看不了图的模型：不下载，照实说看不了画面
        vidFetches.clear()
        val (blindText, _) = ask("a2", link)
        assertTrue("看不了画面" in blindText && "listen_video" in blindText, blindText)
        assertTrue(vidFetches.isEmpty())

        // 视频下不动：照实说画面没打开，不把签过名的长地址写给 AI
        val (goneText, _) = ask("a1", "https://www.douyin.com/video/404")
        assertTrue("画面没取到（下载视频 HTTP 404）" in goneText, goneText)
        assertEquals("视频画面没取到", step("a1").note)
    }

    /**
     * 真开 Edge 读真的抖音视频作品、真下载视频取画面，假模型只负责调 fetch_url（不花钱）：看 AI 实际收到的画面。
     * JXY_LIVE_POSTVIDEO=作品链接，JXY_POSTVIDEO_OUT=把发给模型的画面存到哪个文件夹。
     */
    @Test
    fun livePostVideo() = runBlocking<Unit> {
        val link = System.getenv("JXY_LIVE_POSTVIDEO") ?: return@runBlocking
        val out = File(System.getenv("JXY_POSTVIDEO_OUT") ?: error("要设 JXY_POSTVIDEO_OUT")).also { it.mkdirs() }
        val profile = kotlin.io.path.createTempDirectory("jxy-browser").toFile()
        val browser = com.guixing.jixunying.engine.EdgeBrowser(com.guixing.jixunying.engine.EdgeBrowser.findBrowser() ?: error("找不到 Edge"), profile)
        try {
            val e = Engine(Storage(File(dir, "live")), browserFactory = { browser })
            e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m", vision = true)))))
            e.call(Command.SaveMember(Member("a1", "阿看", providerId = "p1", modelId = "m")))
            val conv = e.call(Command.CreateConversation(listOf("a1"))).data
            e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
            val t0 = System.currentTimeMillis()
            e.call(Command.SendMessage(conv, "看看这个抖音链接 $link"))
            withTimeout(240_000) { while (e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }?.status != MsgStatus.DONE) delay(100) }
            val last = requests.last()
            val tool = last["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
            println("用了 ${System.currentTimeMillis() - t0} ms\n工具结果：\n" + tool.take(1500))
            println("界面上那一步：" + e.store.messages.value[conv].orEmpty().last { it.role == Role.AI }.tools.last { it.kind == "fetch" }.note)
            val parts = last["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" && it["content"] is JsonArray }
                .flatMap { it["content"]!!.jsonArray.map { p -> p.jsonObject } }
            parts.filter { it["type"]?.jsonPrimitive?.content == "text" }.forEach { println("附图说明：" + it["text"]!!.jsonPrimitive.content) }
            parts.filter { it["type"]?.jsonPrimitive?.content == "image_url" }.forEachIndexed { i, p ->
                val bytes = java.util.Base64.getDecoder().decode(p["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.substringAfter(","))
                val img = javax.imageio.ImageIO.read(bytes.inputStream())
                val f = File(out, "frame-${i + 1}.jpg").also { it.writeBytes(bytes) }
                println("画面 ${i + 1}：${img.width}×${img.height}，${bytes.size / 1024} KB → $f")
            }
        } finally {
            browser.close()
            for (i in 0 until 10) { if (profile.deleteRecursively()) break; Thread.sleep(500) }
        }
    }

    @Test
    fun recognizeBlockAndFormat() {
        assertEquals("douyin", SocialLinks.platformOf("https://v.douyin.com/iAbCdEf/")?.id)
        assertEquals("xhs", SocialLinks.platformOf("http://xhslink.com/a/xyz")?.id)
        assertEquals("bilibili", SocialLinks.platformOf("https://b23.tv/BV1x")?.id)
        assertEquals("weibo", SocialLinks.platformOf("https://m.weibo.cn/status/123")?.id)
        assertEquals("kuaishou", SocialLinks.platformOf("https://v.kuaishou.com/abc")?.id)
        assertNull(SocialLinks.platformOf("https://notdouyin.com/x"))
        assertNull(SocialLinks.platformOf("https://www.baidu.com/"))
        fun page(s: String) = Json.parseToJsonElement(s).jsonObject
        assertTrue(SocialLinks.blocked(page("""{"title":"验证码中间页","url":"https://www.douyin.com/","text":""}""")))
        assertTrue(SocialLinks.blocked(page("""{"title":"出错啦! - bilibili.com","url":"https://www.bilibili.com/video/BV1","text":"错误号: 412 安全风控"}""")))
        assertTrue(SocialLinks.blocked(page("""{"title":"小红书","url":"https://www.xiaohongshu.com/login?redirectPath=x","text":"扫码登录"}""")))
        assertFalse(SocialLinks.blocked(page("""{"title":"","url":"https://www.douyin.com/video/1","text":"${"作品文案 ".repeat(100)}登录"}""")))
        // 10-29 电脑上真读的一条抖音图文：整页 340 来字、顶上有「登录」按钮，作品文案和发布时间都读到了，以前被当成登录页
        assertFalse(SocialLinks.blocked(page("""{"title":"","url":"https://www.douyin.com/note/7701206475788048740","text":"精选\n推荐\n搜索\n投稿\n登录\n3.4万\n我说，这是我给小肥鱼做的脑子。#deepseek #鲸鱼娘\n发布时间：2026-10-27 00:54:21"}""")))
    }

    /**
     * 手机上读、听抖音链接（2026-10-29 用户：「抖音链接还是看不了对吧」）：手机没有浏览器，连着电脑时交给电脑上的浏览器读、交给电脑听；
     * 没连上电脑时 AI 拿到的是「要电脑开着」，不给听视频的工具。
     */
    @Test
    fun phoneReadsPlatformLinksThroughPc() = runBlocking<Unit> {
        val mqttPort = ServerSocket(0).use { it.localPort }
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(java.util.Properties().apply {
            setProperty("port", mqttPort.toString()); setProperty("host", "127.0.0.1"); setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true"); setProperty("persistence_enabled", "false")
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val browser = FakeBrowser()
        val pc = Engine(Storage(File(dir, "pc")), browserFactory = { browser })
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        // 听写只在电脑上有（手机上没加能听写的服务商，听视频照样能用：电脑听）
        pc.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "http://127.0.0.1:$port/v1", "sk-z")))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            phone.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m")))))
            phone.call(Command.SaveMember(Member("a1", "阿测", providerId = "p1", modelId = "m")))
            val conv = phone.call(Command.CreateConversation(listOf("a1"))).data
            phone.call(Command.UpdateConversation(phone.state.conversation(conv)!!.copy(webSearch = false)))
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            HostModels(hub, phone).install()
            fun ai() = phone.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }
            suspend fun ask(text: String): String {
                val before = phone.store.messages.value[conv].orEmpty().size
                phone.call(Command.SendMessage(conv, text))
                withTimeout(30_000) { while (!(phone.store.messages.value[conv].orEmpty().size > before + 1 && ai()?.status == MsgStatus.DONE)) delay(30) }
                return requests.last()["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
            }
            val link = "https://www.douyin.com/video/7697224638232825098"

            // 没连上电脑：说清楚要电脑开着，不去瞎读；也不给听视频
            val off = ask("帮我看看 $link")
            assertTrue(HostModels.PAGE_OFFLINE in off, off)
            assertFalse(requests.last()["tools"].toString().contains("listen_video"))
            assertFalse(ai()!!.tools.last { it.kind == "fetch" }.ok)
            assertTrue(browser.opened.isEmpty())

            assertTrue(hub.pair(PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)).isSuccess)
            withTimeout(20_000) { while (hub.remote.value?.conn?.value !is ConnState.Connected) delay(100) }

            val dy = ask("再看看 $link")
            assertTrue("【抖音】" in dy && "医者医人" in dy && "这个是真想看" in dy, dy)
            assertEquals(listOf(link), browser.opened.toList(), "是电脑上的浏览器开的")
            assertTrue(ai()!!.tools.last { it.kind == "fetch" }.ok)

            // 听视频：手机把读页面时拿到的视频地址交给电脑，电脑解码、用电脑上的智谱听写
            val heard = ask("听一下 $link")
            assertTrue(requests.last()["tools"].toString().contains("listen_video"))
            assertTrue("第1段台词" in heard && "整段 200 秒" in heard, heard)
            assertEquals(listOf(link), browser.opened.toList(), "读过的链接电脑不用再开页面")
            assertEquals(listOf("https://v5-dy-ov.zjcdn.com/video/abc.mp4"), browser.audioAsked.toList())
            val step = ai()!!.tools.last { it.kind == "listen" }
            assertTrue(step.ok && "3 秒" in step.note, step.note)
        } finally {
            relay.stop(); broker.stopServer()
        }
    }

    @Test
    fun aiReadsPlatformLinksThroughBrowser() = runBlocking<Unit> {
        val browser = FakeBrowser()
        val e = Engine(Storage(File(dir, "data")), browserFactory = { browser })
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m")))))
        e.call(Command.SaveMember(Member("a1", "阿测", providerId = "p1", modelId = "m")))
        val conv = e.call(Command.CreateConversation(listOf("a1"))).data
        // 关掉「联网」：贴了网址照样给读网页的工具
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = false)))
        fun ai() = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }
        suspend fun ask(text: String): String {
            val before = e.store.messages.value[conv].orEmpty().size
            e.call(Command.SendMessage(conv, text))
            withTimeout(30_000) { while (!(e.store.messages.value[conv].orEmpty().size > before + 1 && ai()?.status == MsgStatus.DONE)) delay(30) }
            return requests.last()["messages"]!!.jsonArray.last { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
        }

        val dy = ask("帮我看看这个 7.92 复制打开抖音 https://www.douyin.com/video/7697224638232825098 看看【小城良方】")
        assertTrue("【抖音】" in dy && "医者医人" in dy && "这个是真想看" in dy, dy)
        assertFalse("uuu_265" in dy, "抖音的占位视频不能当成作品视频")
        assertTrue(ai()!!.tools.any { it.kind == "fetch" && it.ok })

        val xhs = ask("这篇呢 https://www.xiaohongshu.com/explore/6ab399f2000000001303e711?xsec_token=x")
        assertTrue("作者：电梯爱看" in xhs && "点赞：700" in xhs && "话题：阿凡达3讨论团、电影阿凡达3" in xhs && "视频地址：http://sns-video-zl" in xhs, xhs)
        assertFalse("登录后推荐" in xhs, "读到笔记数据就不附页面杂字")

        val bili = ask("还有 https://www.bilibili.com/video/BV1AvnH6HET1")
        assertTrue("没让看" in bili && "去登录B站" in bili, bili)
        assertFalse(ai()!!.tools.last { it.kind == "fetch" }.ok)
        assertEquals(3, browser.opened.size)

        // 登录状态：抖音有 sessionid = 已登录；小红书看不出来；B站没登录
        val st = Json.parseToJsonElement(e.call(Command.PlatformStatus()).data).jsonObject
        val p = st["platforms"]!!.jsonObject
        assertEquals("in", p["douyin"]!!.jsonPrimitive.content)
        assertEquals("unknown", p["xhs"]!!.jsonPrimitive.content)
        assertEquals("out", p["bilibili"]!!.jsonPrimitive.content)
        assertTrue(e.call(Command.PlatformLogin("douyin")).ok)

        // 听视频：没有能听写的服务商时不给这个工具
        assertFalse(requests.last()["tools"].toString().contains("listen_video"))
        e.call(Command.SaveProvider(ProviderConfig("pz", "zhipu", "智谱", "http://127.0.0.1:$port/v1", "sk-z")))
        // 读过的抖音链接：直接用记下的视频地址（不再开页面）；假 PCM 3 秒 → 智谱按 25 秒一段，一段
        val opened = browser.opened.size
        val heard = ask("听一下 https://www.douyin.com/video/7697224638232825098")
        assertTrue(requests.last()["tools"].toString().contains("listen_video"))
        assertTrue("第1段台词" in heard && "整段 200 秒" in heard, heard)
        assertEquals(opened, browser.opened.size, "读过的链接不用再开页面")
        assertEquals(listOf("https://v5-dy-ov.zjcdn.com/video/abc.mp4"), browser.audioAsked.toList())
        val step = ai()!!.tools.last { it.kind == "listen" }
        assertTrue(step.ok && "3 秒" in step.note, step.note)
        assertEquals("login:https://www.douyin.com/", browser.opened.last())
    }
}
