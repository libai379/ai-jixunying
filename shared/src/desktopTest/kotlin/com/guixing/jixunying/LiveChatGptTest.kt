package com.guixing.jixunying

import com.guixing.jixunying.engine.ChatGptAuth
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.ResponsesApi
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ProviderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真的用 ChatGPT 登录（docs/ChatGPT订阅设计说明.md 第 6 节「真登录没试」）。平时跳过；
 * JXY_LIVE_CHATGPT=数据目录 才跑：登录地址写到 数据目录/login-url.txt，在浏览器里打开、同意，跳回本机后
 * 拉模型、问一句、再走一次工具来回，把真回来的样子打印出来（namespace 带不带、报什么错）。代理用 JXY_LIVE_PROXY（默认 127.0.0.1:10809）。
 */
class LiveChatGptTest {
    @Test
    fun realSignInAndChat() = runBlocking<Unit> {
        val path = System.getenv("JXY_LIVE_CHATGPT") ?: return@runBlocking
        val dir = File(path).apply { mkdirs() }
        val proxy = System.getenv("JXY_LIVE_PROXY") ?: "http://127.0.0.1:10809"
        val e = Engine(Storage(dir))
        e.call(Command.SaveSettings(e.state.settings.copy(proxy = proxy)))
        if (e.state.provider("pg") == null) e.call(Command.SaveProvider(ProviderConfig("pg", "chatgpt", "ChatGPT 订阅", "https://api.openai.com/v1", useProxy = true)))
        if (!e.state.chatgpt.signedIn) {
            val r = e.call(Command.ChatGptSignIn)
            assertTrue(r.ok, r.message)
            File(dir, "login-url.txt").writeText(r.data)
            println("LOGIN_URL_WRITTEN")
            withTimeout(5 * 60_000) { while (e.state.chatgpt.signingIn) delay(200) }
        }
        println("CHATGPT_INFO ${e.state.chatgpt}")
        assertTrue(e.state.chatgpt.signedIn, e.state.chatgpt.error)
        withTimeout(60_000) { while (e.state.provider("pg")?.models.isNullOrEmpty()) delay(200) }
        val models = e.state.provider("pg")!!.models.map { it.id }
        println("MODELS $models")
        val model = System.getenv("JXY_LIVE_CHATGPT_MODEL") ?: models.first()
        val t = e.call(Command.TestProvider("pg", model))
        println("TEST ${t.ok} ${t.message}")

        // 工具来回：看真回来的 function_call 长什么样
        val auth = ChatGptAuth(File(dir, "chatgpt"), proxy = { proxy })
        val api = ResponsesApi({ proxy }, "https://api.openai.com/v1")
        val tools = buildJsonArray {
            add(buildJsonObject { put("type", "function"); put("function", buildJsonObject {
                put("name", "get_time"); put("description", "查现在几点"); put("parameters", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) })
            }) })
        }
        val user = buildJsonObject { put("role", "user"); put("content", "现在几点？一定要先调用 get_time 工具。") }
        val r1 = api.chat(model, listOf(user), tools, { auth.accessToken(it) }, {}) { _, _ -> }
        println("TOOLCALLS ${r1.toolCalls.map { "${it.id} ${it.name} ${it.arguments}" }} TEXT ${r1.content.take(200)}")
        println("RAWCALLS ${api.lastRawCalls}")
    }
}
