package com.guixing.jixunying

import com.guixing.jixunying.engine.EdgeBrowser
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.WeixinBridge
import com.guixing.jixunying.model.Command
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * 假微信（iLink + CDN）+ 真模型 + 真 Edge + 真听写：引用文字、发视频、引用视频，把回答打印出来人看（2026-11-01）。
 * JXY_LIVE_WXQUOTE=数据文件夹（只复制 state.json，不动原数据，也不碰真微信）；JXY_LIVE_WXQUOTE_VIDEO=视频文件；
 * JXY_LIVE_WXQUOTE_MEMBER=成员名（默认阿米，要能看图）。花一点模型和听写的钱。不要选 ChatGPT 订阅的成员：副本刷新令牌会把用户的登录顶掉，也没复制凭据。
 */
class LiveWeixinQuoteTest {
    @Test
    fun quoteAndVideoWithRealModel() = runBlocking<Unit> {
        val src = System.getenv("JXY_LIVE_WXQUOTE") ?: return@runBlocking
        val video = File(System.getenv("JXY_LIVE_WXQUOTE_VIDEO") ?: return@runBlocking).readBytes()
        val who = System.getenv("JXY_LIVE_WXQUOTE_MEMBER") ?: "阿米"
        val inbox = ConcurrentLinkedQueue<String>()
        val sent = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val ids = AtomicInteger(0)
        val key = ByteArray(16) { (it * 7 + 3).toByte() }
        val keyB64 = Base64.getEncoder().encodeToString(key)
        val port = ServerSocket(0).use { it.localPort }
        val fake = embeddedServer(CIO, port = port) {
            routing {
                post("/ilink/bot/get_bot_qrcode") { call.respondText("""{"qrcode":"qr1","qrcode_img_content":"https://x/q"}""", ContentType.Application.Json) }
                get("/ilink/bot/get_qrcode_status") {
                    call.respondText("""{"status":"confirmed","bot_token":"tok","ilink_bot_id":"bot1@im.bot","ilink_user_id":"me@im.wechat","baseurl":"http://127.0.0.1:$port"}""", ContentType.Application.Json)
                }
                post("/ilink/bot/msg/notifystart") { call.respondText("{}", ContentType.Application.Json) }
                post("/ilink/bot/msg/notifystop") { call.respondText("{}", ContentType.Application.Json) }
                post("/ilink/bot/getupdates") {
                    call.receiveText()
                    val m = inbox.poll()
                    if (m == null) { delay(200); call.respondText("""{"ret":0,"msgs":[],"get_updates_buf":"b0"}""", ContentType.Application.Json) }
                    else call.respondText("""{"ret":0,"msgs":[$m],"get_updates_buf":"b1"}""", ContentType.Application.Json)
                }
                post("/ilink/bot/getconfig") { call.receiveText(); call.respondText("""{"ret":0,"typing_ticket":"tt"}""", ContentType.Application.Json) }
                post("/ilink/bot/sendtyping") { call.receiveText(); call.respondText("""{"ret":0}""", ContentType.Application.Json) }
                post("/ilink/bot/sendmessage") {
                    val id = "1844674407370955${1000 + ids.incrementAndGet()}"
                    val m = Json.parseToJsonElement(call.receiveText()).jsonObject["msg"]!!.jsonObject
                    sent += id to m["item_list"]!!.jsonArray[0].jsonObject["text_item"]!!.jsonObject["text"]!!.jsonPrimitive.content
                    call.respondText("""{"ret":0,"message_id":$id}""", ContentType.Application.Json)
                }
                get("/c2c/download") { call.respondBytes(WeixinBridge.aesEcb(video, key, encrypt = true), ContentType.Application.OctetStream) }
            }
        }.also { it.start() }
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-wxquote").toFile()
        val edgeProfile = kotlin.io.path.createTempDirectory("jxy-live-wxquote-edge").toFile()
        val edge = EdgeBrowser(EdgeBrowser.findBrowser() ?: error("找不到 Edge"), edgeProfile)
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp), browserFactory = { edge })
            val m = e.state.members.first { it.name == who }
            val s = e.state.settings
            e.call(Command.SaveSettings(s.copy(
                memory = s.memory.copy(enabled = false, autoExtract = false, stances = false, aiVerify = false),
                relay = s.relay.copy(enabled = false),
                weixin = s.weixin.copy(enabled = true, memberIds = listOf(m.id), webSearch = false),
            )))
            val bridge = WeixinBridge(e, File(tmp, "weixin"), loginBase = "http://127.0.0.1:$port", cdnBase = "http://127.0.0.1:$port/c2c")
            e.weixin = bridge
            bridge.start()
            e.call(Command.WeixinLogin)
            withTimeout(15_000) { while (!e.state.weixin.status.startsWith("已连接")) delay(100) }

            fun msg(id: Int, item: String) = """{"message_id":$id,"from_user_id":"me@im.wechat","to_user_id":"bot1@im.bot","message_type":1,"context_token":"c$id","item_list":[$item]}"""
            suspend fun say(id: Int, item: String): List<Pair<String, String>> {
                sent.clear()
                println("\n>>> $item".take(300))
                inbox += msg(id, item)
                withTimeout(300_000) { while (sent.isEmpty()) delay(200) }
                delay(1_500)
                val got = sent.toList()
                got.forEach { println("<<< ${it.second}") }
                return got
            }
            val first = say(1, """{"type":1,"text_item":{"text":"用一句话说说，人为什么会觉得 AI 说话假？"}}""")
            say(2, """{"type":1,"text_item":{"text":"这句最假，你自己说哪儿假"},"ref_msg":{"svr_id":${first.first().first}}}""")
            say(3, """{"type":5,"video_item":{"media":{"encrypt_query_param":"v1","aes_key":"$keyB64","encrypt_type":1},"video_size":${video.size}}}""")
            say(4, """{"type":1,"text_item":{"text":"先不说这个，今天星期几？"}}""")
            say(5, """{"type":1,"text_item":{"text":"这段视频里她手里拿的是什么？她说了什么？"},"ref_msg":{"svr_id":3}}""")
            bridge.stop()
            val conv = e.state.conversations.first { it.channel == "weixin:me@im.wechat" }
            e.call(Command.LoadMessages(conv.id))
            e.store.messages.value[conv.id]!!.filter { it.role == com.guixing.jixunying.model.Role.USER }.forEach { u ->
                println("[用户消息] " + u.content.take(200).replace("\n", " ⏎ ") + " | 附件 " + u.attachments.map { it.name + "/" + it.seconds + "秒/" + it.frames.size + "画面/听到" + it.textChars + "字/" + it.note } + " | 引用附件 " + u.quoted.map { it.name })
            }
        } finally {
            edge.close()
            fake.stop(0, 0)
            Thread.sleep(800)
            tmp.deleteRecursively()
            edgeProfile.deleteRecursively()
        }
    }
}
