package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.PageBrowser
import com.guixing.jixunying.engine.SocialLinks
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
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
    private lateinit var dir: File

    /** 假浏览器：按网址回事先存好的页面（照 10-23 真读到的样子删减）。 */
    private class FakeBrowser : PageBrowser {
        val opened = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun snapshot(url: String, script: String, settleMs: Long): JsonObject {
            opened += url
            assertEquals(SocialLinks.EXTRACT_JS, script)
            val json = when {
                "douyin.com" in url -> """{"url":"$url","title":"","desc":"","text":"精选\n推荐\n第83集 | 医者医人，亦可被人间治愈。#小城良方\n3095\n162\n发布时间：2026-10-16 07:22\n全部评论\n这个是真想看，21号我们一起追","videos":["https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/uuu_265.mp4"],"images":[]}"""
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
                                val fn = JsonObject(mapOf("name" to JsonPrimitive("fetch_url"), "arguments" to JsonPrimitive(JsonObject(mapOf("url" to JsonPrimitive(url))).toString())))
                                val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("f1"), "type" to JsonPrimitive("function"), "function" to fn))
                                chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                                write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                            }
                            else -> chunk(JsonObject(mapOf("content" to JsonPrimitive("看过了。"))).toString())
                        }
                    }
                }
            }
        }
        fake.start(wait = false)
    }

    @AfterTest
    fun cleanup() { fake.stop(500, 1_000); dir.deleteRecursively() }

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
        assertEquals("login:https://www.douyin.com/", browser.opened.last())
    }
}
