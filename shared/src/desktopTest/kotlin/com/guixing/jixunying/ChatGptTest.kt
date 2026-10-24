package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.ResponsesApi
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ProviderConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 用 ChatGPT 登录（2026-10-23，docs/ChatGPT订阅设计说明.md）：假的 OpenAI 登录服务 + 假的 Responses 接口，
 * 照官方文档的格式走一遍：登录（第一次注册拿正式 client_id、PKCE、校验 ID 令牌签名）→ 拉模型 → 调模型 → 刷新令牌 → 额度用完 → 退出。
 */
class ChatGptTest {
    private var port = 0
    private lateinit var fake: EmbeddedServer<*, *>
    private lateinit var dir: File
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val tokenForms = CopyOnWriteArrayList<Map<String, String>>()
    private val responsesReqs = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var nonce = ""
    @Volatile private var challenge = ""
    @Volatile private var refreshes = 0

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private val base get() = "http://127.0.0.1:$port"

    private fun idToken(aud: String): String {
        val h = b64("""{"alg":"RS256","kid":"k1","typ":"JWT"}""".toByteArray())
        val c = b64("""{"iss":"$base","aud":"$aud","sub":"user-1","email":"u@example.com","nonce":"$nonce","exp":${System.currentTimeMillis() / 1000 + 3600}}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update("$h.$c".toByteArray()); sign() }
        return "$h.$c.${b64(sig)}"
    }

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-chatgpt").toFile()
        System.setProperty("jxy.chatgpt.auth", "http://127.0.0.1:$port")
        fake = embeddedServer(CIO, port = port) {
            routing {
                get("/.well-known/openid-configuration") {
                    call.respondText("""{"issuer":"$base","jwks_uri":"$base/jwks"}""", ContentType.Application.Json)
                }
                get("/jwks") {
                    val pub = keys.public as RSAPublicKey
                    fun u(b: java.math.BigInteger) = b64(b.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
                    call.respondText("""{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","n":"${u(pub.modulus)}","e":"${u(pub.publicExponent)}"}]}""", ContentType.Application.Json)
                }
                post("/api/accounts/oauth/token") {
                    val p = call.receiveParameters()
                    val form = p.names().associateWith { p[it].orEmpty() }
                    tokenForms += form
                    when (form["grant_type"]) {
                        "authorization_code" -> {
                            val ok = form["code"] == "c1" && form["client_id"] == "oaiapp_test" &&
                                b64(MessageDigest.getInstance("SHA-256").digest(form["code_verifier"].orEmpty().toByteArray())) == challenge
                            if (!ok) { call.respondText("""{"error":"invalid_grant"}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@post }
                            // 访问令牌只给 60 秒：下一次用就要刷新（测刷新这条路）
                            call.respondText("""{"access_token":"at-1","refresh_token":"rt-1","id_token":"${idToken("oaiapp_test")}","token_type":"Bearer","expires_in":60,
                                "scope":"openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"}""", ContentType.Application.Json)
                        }
                        "refresh_token" -> {
                            refreshes++
                            if (form["refresh_token"] != "rt-$refreshes") { call.respondText("""{"error":"refresh_token_reused"}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@post }
                            call.respondText("""{"access_token":"at-${refreshes + 1}","refresh_token":"rt-${refreshes + 1}","token_type":"Bearer","expires_in":3600}""", ContentType.Application.Json)
                        }
                        else -> call.respondText("{}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    }
                }
                get("/v1/models") {
                    call.respondText("""{"models":[{"slug":"gpt-x","display_name":"GPT X","visibility":"list"},{"slug":"gpt-hidden","visibility":"hide"}]}""", ContentType.Application.Json)
                }
                post("/v1/responses") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    responsesReqs += req
                    val auth = call.request.headers["Authorization"].orEmpty()
                    if (!auth.startsWith("Bearer at-")) { call.respondText("""{"error":{"code":"subscription_sharing_invalid_user"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post }
                    val model = req["model"]!!.jsonPrimitive.content
                    val input = req["input"]!!.jsonArray
                    val body = when {
                        model == "gpt-limit" -> sse("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}""")
                        model == "gpt-tools" && input.none { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" } -> sse(
                            """{"type":"response.reasoning_summary_text.delta","delta":"想一下"}""",
                            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_9","name":"get_time","namespace":"jxy","arguments":"{}"}}""",
                            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":3}}}""",
                        )
                        else -> sse(
                            """{"type":"response.output_text.delta","delta":"你好，"}""",
                            """{"type":"response.output_text.delta","delta":"我是 GPT。"}""",
                            """{"type":"response.completed","response":{"usage":{"input_tokens":12,"output_tokens":5,"input_tokens_details":{"cached_tokens":2}}}}""",
                        )
                    }
                    call.respondText(body, ContentType.Text.EventStream)
                }
            }
        }.start(wait = false)
    }

    @AfterTest
    fun tearDown() {
        fake.stop(100, 500)
        System.clearProperty("jxy.chatgpt.auth")
        dir.deleteRecursively()
    }

    private suspend fun until(what: String, ok: () -> Boolean) = withTimeout(10_000) { while (!ok()) delay(50) }.also { assertTrue(ok(), what) }

    @Test
    fun signInUseRefreshAndSignOut() = runBlocking<Unit> {
        val e = Engine(Storage(dir))
        e.call(Command.SaveSettings(e.state.settings.copy(proxy = "")))
        e.call(Command.SaveProvider(ProviderConfig("pg", "chatgpt", "ChatGPT 订阅", "$base/v1", useProxy = true)))
        assertFalse(e.state.chatgpt.signedIn)
        // 没登录时调模型：说清楚要先登录
        val before = e.call(Command.TestProvider("pg", "gpt-x"))
        assertFalse(before.ok); assertTrue("登录" in before.message, before.message)

        val r = e.call(Command.ChatGptSignIn)
        assertTrue(r.ok, r.message)
        assertTrue(e.state.chatgpt.signingIn)
        val uri = URI(r.data)
        assertEquals("/api/accounts/authorize", uri.path)
        val q = uri.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("AI集训营", q["agent_name_hint"])
        assertTrue(q["ext_agent_host_id"]!!.startsWith("urn:uuid:"))
        assertTrue(Regex("""http://127\.0\.0\.1:\d+/auth/callback""").matches(q["redirect_uri"]!!), q["redirect_uri"])
        assertTrue("chatgpt.tokens.use.direct" in q["scope"]!! && "offline_access" in q["scope"]!!)
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("code", q["response_type"])
        nonce = q["nonce"]!!; challenge = q["code_challenge"]!!

        // 浏览器跳回本机（OpenAI 在回调里给正式的 client_id）
        val cb = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(URI(q["redirect_uri"] + "?code=c1&state=${q["state"]}&client_id=oaiapp_test")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())
        assertEquals(200, cb.statusCode()); assertTrue("登录好了" in cb.body())

        until("登录好了") { e.state.chatgpt.signedIn && !e.state.chatgpt.signingIn }
        assertTrue(e.state.chatgpt.planOk)
        assertEquals("u@example.com", e.state.chatgpt.email)
        until("拉到模型") { e.state.provider("pg")?.models?.map { it.id } == listOf("gpt-x") }
        val code = tokenForms.first { it["grant_type"] == "authorization_code" }
        assertEquals("oaiapp_test", code["client_id"]); assertEquals(q["redirect_uri"], code["redirect_uri"]); assertEquals("https://api.openai.com/v1", code["resource"])
        assertFalse("client_secret" in code)
        // 凭据只在 chatgpt/auth.json，不进 state.json
        val authFile = File(dir, "chatgpt/auth.json")
        assertTrue(authFile.exists())
        assertFalse("rt-" in File(dir, "state.json").readText())

        // 访问令牌只剩 60 秒：调之前先刷新（拉模型时已经刷过一次 → rt-2）
        val t = e.call(Command.TestProvider("pg", "gpt-x"))
        assertTrue(t.ok, t.message); assertTrue("我是 GPT" in t.message, t.message)
        assertTrue(refreshes >= 1)
        assertTrue("rt-${refreshes + 1}" in authFile.readText())
        val req = responsesReqs.last()
        assertEquals(false, req["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, req["stream"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("temperature" in req)
        assertEquals("input_text", req["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)

        // 额度用完：给一句人话
        e.call(Command.SaveProvider(e.state.provider("pg")!!.let { it.copy(models = it.models + com.guixing.jixunying.model.ModelInfo("gpt-limit")) }))
        val lim = e.call(Command.TestProvider("pg", "gpt-limit"))
        assertFalse(lim.ok); assertTrue("额度用完" in lim.message, lim.message)

        // 退出
        e.call(Command.ChatGptSignOut)
        assertFalse(e.state.chatgpt.signedIn)
        val after = e.call(Command.TestProvider("pg", "gpt-x"))
        assertFalse(after.ok); assertTrue("登录" in after.message, after.message)
        // 再登录用存下的 client_id（官方：以后都用正式的）
        val again = e.call(Command.ChatGptSignIn)
        assertTrue("client_id=oaiapp_test" in again.data, again.data)
        e.call(Command.ChatGptSignOut)
    }

    @Test
    fun responsesConversionAndToolRoundTrip() = runBlocking<Unit> {
        val api = ResponsesApi({ null }, "$base/v1")
        val tools = buildJsonArray {
            add(buildJsonObject { put("type", "function"); put("function", buildJsonObject { put("name", "get_time"); put("description", "现在几点"); put("parameters", buildJsonObject { put("type", "object") }) }) })
            add(buildJsonObject { put("type", "web_search") })   // 平台内置的，不传
        }
        val user = buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "几点了？看图") })
                add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/png;base64,AAAA") }) })
            })
        }
        val sys = buildJsonObject { put("role", "system"); put("content", "你是阿G") }
        val deltas = StringBuilder()
        val r1 = api.chat("gpt-tools", listOf(sys, user), tools, { "at-1" }, {}) { c, rs -> deltas.append(c).append(rs) }
        assertEquals(listOf("get_time"), r1.toolCalls.map { it.name })
        assertEquals("call_9", r1.toolCalls[0].id)
        assertEquals("想一下", r1.reasoning)
        val req1 = responsesReqs.last()
        assertEquals("你是阿G", req1["instructions"]!!.jsonPrimitive.content)
        val ns = req1["tools"]!!.jsonArray.single().jsonObject
        assertEquals("namespace", ns["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("get_time"), ns["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val parts = req1["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("input_text", "input_image"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(req1["input"]!!.jsonArray.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })

        // 引擎把工具调用和结果接回去（chat/completions 的格式）→ 第二次请求里是 function_call（带回 namespace）+ function_call_output
        val asst = buildJsonObject {
            put("role", "assistant"); put("content", "")
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("id", "call_9"); put("type", "function")
                put("function", buildJsonObject { put("name", "get_time"); put("arguments", "{}") })
            }) })
        }
        val tool = buildJsonObject { put("role", "tool"); put("tool_call_id", "call_9"); put("content", "下午三点") }
        val later = buildJsonObject { put("role", "system"); put("content", "提醒：用中文") }
        val r2 = api.chat("gpt-tools", listOf(sys, user, asst, tool, later), tools, { "at-1" }, {}) { _, _ -> }
        assertEquals("你好，我是 GPT。", r2.content)
        assertEquals(12, r2.usage.prompt); assertEquals(5, r2.usage.completion); assertEquals(2, r2.usage.cached)
        val input2 = responsesReqs.last()["input"]!!.jsonArray.map { it.jsonObject }
        val fc = input2.single { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("jxy", fc["namespace"]!!.jsonPrimitive.content)
        assertEquals("call_9", fc["call_id"]!!.jsonPrimitive.content)
        val out = input2.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("下午三点", out["output"]!!.jsonPrimitive.content)
        // 对话中间的 system 变成 developer 消息
        assertEquals("developer", input2.last()["role"]!!.jsonPrimitive.content)
        assertTrue(input2.none { (it["content"] as? JsonArray)?.any { p -> p.jsonObject["type"]?.jsonPrimitive?.content == "output_text" && p.jsonObject["text"]?.jsonPrimitive?.content.isNullOrEmpty() } == true })
    }
}
