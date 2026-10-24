package com.guixing.jixunying

import com.guixing.jixunying.engine.Account
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.code.PhoneGate
import com.guixing.jixunying.model.Command
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 账号（2026-10-24，docs/账号设计说明.md）：本机密钥、备份码、GitHub 网页登录跳回本机（假 GitHub，照官方 web flow + PKCE 的格式）、手机不能改。 */
class AccountTest {
    private var port = 0
    private lateinit var fake: EmbeddedServer<*, *>
    private lateinit var dir: File
    private val forms = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var polls = 0
    @Volatile private var userAuth = ""
    @Volatile private var challenge = ""

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-account").toFile()
        System.setProperty("jxy.github.web", "http://127.0.0.1:$port")
        System.setProperty("jxy.github.api", "http://127.0.0.1:$port/api")
        System.setProperty("jxy.github.clientId", "Iv-test")
        System.setProperty("jxy.github.secret", "sec-test")
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/login/oauth/access_token") {
                    val p = call.receiveParameters(); val form = p.names().associateWith { p[it].orEmpty() }; forms += form
                    polls++
                    val pkceOk = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(form["code_verifier"].orEmpty().toByteArray())) == challenge
                    call.respondText(
                        if (form["code"] == "c1" && form["client_secret"] == "sec-test" && pkceOk) """{"access_token":"gho_test","token_type":"bearer","scope":""}"""
                        else """{"error":"bad_verification_code"}""", ContentType.Application.Json)
                }
                get("/api/user") {
                    userAuth = call.request.headers["Authorization"].orEmpty()
                    call.respondText("""{"login":"libai379","id":12345,"name":"李白","avatar_url":"https://avatars.githubusercontent.com/u/12345"}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    @AfterTest
    fun tearDown() {
        fake.stop(100, 500)
        listOf("jxy.github.web", "jxy.github.api", "jxy.github.clientId", "jxy.github.secret").forEach { System.clearProperty(it) }
        dir.deleteRecursively()
    }

    @Test
    fun localKeyBackupAndRestore() {
        val a = Account(File(dir, "a"), { null })
        val id = a.accountId
        assertTrue(Regex("jxy-[0-9a-f]{4}(-[0-9a-f]{4}){4}").matches(id), id)
        assertEquals(id, Account(File(dir, "a"), { null }).accountId, "再打开还是同一个账号")
        val sig = a.sign("hello".toByteArray())
        assertTrue(Account.verify(a.identity.publicKey, "hello".toByteArray(), sig))
        assertFalse(Account.verify(a.identity.publicKey, "hell0".toByteArray(), sig))

        val code = a.export("口令123456")
        assertTrue(code.startsWith("JXYID1:"))
        assertFalse(a.identity.privateKey in code, "备份码里不能有明文私钥")
        val b = Account(File(dir, "b"), { null })
        assertNotEquals(id, b.accountId)
        assertTrue(runCatching { b.import(code, "错的口令") }.exceptionOrNull()?.message.orEmpty().contains("口令不对"))
        assertTrue(runCatching { b.import("随便一段字", "x") }.exceptionOrNull()?.message.orEmpty().contains("不是"))
        assertTrue(runCatching { a.export("123") }.isFailure, "口令太短")
        b.import(code, "口令123456")
        assertEquals(id, b.accountId, "导入后就是同一个账号")
        assertEquals(id, Account(File(dir, "b"), { null }).accountId, "导入的存下来了")
    }

    @Test
    fun githubDeviceFlowAndPhoneGate() = runBlocking<Unit> {
        val e = Engine(Storage(File(dir, "data")))
        e.call(Command.SaveSettings(e.state.settings.copy(proxy = "")))
        val id = e.state.account.id
        assertTrue(id.startsWith("jxy-"))
        // 私钥不进 state.json
        val priv = Json.parseToJsonElement(File(dir, "data/account/identity.json").readText()).jsonObject["privateKey"]!!.jsonPrimitive.content
        assertFalse(priv in File(dir, "data/state.json").readText())

        val r = e.call(Command.AccountGitHubSignIn)
        assertTrue(r.ok, r.message)
        // 网页登录：打开的是 GitHub 授权页，跳回本机 127.0.0.1（端口随意，路径和应用里登记的一样）
        val uri = URI(r.data)
        assertEquals("/login/oauth/authorize", uri.path)
        val q = uri.rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Iv-test", q["client_id"])
        assertTrue(Regex("""http://127\.0\.0\.1:\d+/auth/github""").matches(q["redirect_uri"]!!), q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertFalse("scope" in q, "不要权限 = 只读公开资料")
        challenge = q["code_challenge"]!!
        assertTrue(e.state.account.githubSigningIn)
        // 浏览器跳回来：先来一个要图标的请求（不理），再来真回调
        val http = java.net.http.HttpClient.newHttpClient()
        fun get(u: String) = http.send(java.net.http.HttpRequest.newBuilder(URI(u)).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        // 浏览器的预连接：连上不发东西（真登录时就是它让登录报了 Read timed out）
        val idle = java.net.Socket("127.0.0.1", URI(q["redirect_uri"]!!).port)
        assertEquals(404, get(q["redirect_uri"]!!.replace("/auth/github", "/favicon.ico")).statusCode())
        idle.close()
        val cb = get(q["redirect_uri"] + "?code=c1&state=" + q["state"])
        assertTrue("GitHub 登录好了" in cb.body(), cb.body())
        withTimeout(15_000) { while (e.state.account.githubLogin.isEmpty()) delay(50) }
        assertEquals("libai379", e.state.account.githubLogin)
        assertEquals("李白", e.state.account.githubName)
        assertFalse(e.state.account.githubSigningIn)
        assertEquals(id, e.state.account.id, "绑 GitHub 不换账号编号")
        assertEquals("Bearer gho_test", userAuth)
        val tok = forms.last()
        assertEquals("Iv-test", tok["client_id"]); assertEquals(q["redirect_uri"], tok["redirect_uri"])

        // 校验码不对（别人伪造的跳转）：不绑
        e.call(Command.AccountGitHubUnbind)
        val r2 = e.call(Command.AccountGitHubSignIn)
        val q2 = URI(r2.data).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        get(q2["redirect_uri"] + "?code=c1&state=wrong")
        withTimeout(15_000) { while (e.state.account.githubSigningIn) delay(50) }
        assertEquals("", e.state.account.githubLogin)
        assertTrue("校验码" in e.state.account.githubError, e.state.account.githubError)
        // 再正常登一次，后面导出要带着 GitHub
        val r3 = e.call(Command.AccountGitHubSignIn)
        val q3 = URI(r3.data).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        challenge = q3["code_challenge"]!!
        get(q3["redirect_uri"] + "?code=c1&state=" + q3["state"])
        withTimeout(15_000) { while (e.state.account.githubLogin.isEmpty()) delay(50) }
        assertFalse("gho_test" in File(dir, "data/account/identity.json").readText(), "令牌不存")
        assertFalse("gho_test" in File(dir, "data/state.json").readText())

        // 手机遥控时：账号、各种登录都只能在电脑上改
        val s = e.state
        listOf(Command.AccountGitHubSignIn, Command.AccountGitHubUnbind, Command.AccountExport("123456"), Command.AccountImport("x", "y"),
            Command.ChatGptSignIn, Command.PlatformLogin("douyin")).forEach { c ->
            assertNotNull(PhoneGate.check(c, s).refusal, "$c 手机不能做")
        }

        // 导出导入走一遍、解绑
        val code = e.call(Command.AccountExport("abcdef")).data
        assertTrue(code.startsWith("JXYID1:"))
        assertTrue(e.call(Command.AccountGitHubUnbind).ok)
        assertEquals("", e.state.account.githubLogin)
        val e2 = Engine(Storage(File(dir, "data2")))
        assertNotEquals(id, e2.state.account.id)
        assertFalse(e2.call(Command.AccountImport(code, "错的")).ok)
        assertTrue(e2.call(Command.AccountImport(code, "abcdef")).ok)
        assertEquals(id, e2.state.account.id)
        assertEquals("libai379", e2.state.account.githubLogin, "备份码带着导出时绑的 GitHub")
    }
}
