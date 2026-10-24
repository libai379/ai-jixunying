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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 账号（2026-10-24，docs/账号设计说明.md）：本机密钥、备份码、GitHub 设备码登录（假 GitHub，照官方 device flow 的格式）、手机不能改。 */
class AccountTest {
    private var port = 0
    private lateinit var fake: EmbeddedServer<*, *>
    private lateinit var dir: File
    private val forms = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var polls = 0
    @Volatile private var userAuth = ""

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-account").toFile()
        System.setProperty("jxy.github.web", "http://127.0.0.1:$port")
        System.setProperty("jxy.github.api", "http://127.0.0.1:$port/api")
        System.setProperty("jxy.github.clientId", "Iv-test")
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/login/device/code") {
                    val p = call.receiveParameters(); forms += p.names().associateWith { p[it].orEmpty() }
                    call.respondText("""{"device_code":"dev-1","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":1}""", ContentType.Application.Json)
                }
                post("/login/oauth/access_token") {
                    val p = call.receiveParameters(); forms += p.names().associateWith { p[it].orEmpty() }
                    polls++
                    // 第一次：还没同意；第二次：慢一点；第三次给令牌
                    call.respondText(when (polls) {
                        1 -> """{"error":"authorization_pending"}"""
                        2 -> """{"error":"slow_down","interval":1}"""
                        else -> """{"access_token":"gho_test","token_type":"bearer","scope":""}"""
                    }, ContentType.Application.Json)
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
        listOf("jxy.github.web", "jxy.github.api", "jxy.github.clientId").forEach { System.clearProperty(it) }
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
        val d = Json.parseToJsonElement(r.data).jsonObject
        assertEquals("ABCD-1234", d["code"]!!.jsonPrimitive.content)
        assertEquals("ABCD-1234", e.state.account.githubCode)
        withTimeout(15_000) { while (e.state.account.githubLogin.isEmpty()) delay(50) }
        assertEquals("libai379", e.state.account.githubLogin)
        assertEquals("李白", e.state.account.githubName)
        assertEquals("", e.state.account.githubCode)
        assertEquals(id, e.state.account.id, "绑 GitHub 不换账号编号")
        assertEquals("Bearer gho_test", userAuth)
        val start = forms.first()
        assertEquals("Iv-test", start["client_id"])
        assertFalse("client_secret" in start)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", forms.last()["grant_type"])
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
