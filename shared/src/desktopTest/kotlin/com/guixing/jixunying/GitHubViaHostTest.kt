package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.GitHubViaHost
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
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
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手机的 GitHub 登录靠电脑（2026-10-24）：电脑上登录了，手机连上就带过来；手机自己登录时换令牌交给电脑做。
 * 手机的代理故意填成连不上的地址：手机自己去换一定失败，能登上就说明是电脑换的。
 */
class GitHubViaHostTest {
    private var port = 0
    private lateinit var fake: EmbeddedServer<*, *>
    private lateinit var dir: File
    private val exchanges = AtomicInteger()
    @Volatile private var challenge = ""

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-ghhost").toFile()
        System.setProperty("jxy.github.web", "http://127.0.0.1:$port")
        System.setProperty("jxy.github.api", "http://127.0.0.1:$port/api")
        System.setProperty("jxy.github.clientId", "Iv-test")
        System.setProperty("jxy.github.secret", "sec-test")
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/login/oauth/access_token") {
                    val p = call.receiveParameters()
                    val ok = p["client_secret"] == "sec-test" && Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(p["code_verifier"].orEmpty().toByteArray())) == challenge
                    exchanges.incrementAndGet()
                    call.respondText(if (ok) """{"access_token":"gho_x"}""" else """{"error":"bad_verification_code"}""", ContentType.Application.Json)
                }
                get("/api/user") {
                    call.respondText("""{"login":"libai379","id":7,"name":"李白","avatar_url":"https://a/u/7"}""", ContentType.Application.Json)
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

    private fun query(url: String) = URI(url).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    private fun browserBack(url: String) {
        val q = query(url)
        challenge = q["code_challenge"]!!
        java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI(q["redirect_uri"] + "?code=c1&state=" + q["state"])).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun phoneUsesPcForGitHub() = runBlocking<Unit> {
        val mqttPort = ServerSocket(0).use { it.localPort }
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(Properties().apply {
            setProperty("port", mqttPort.toString()); setProperty("host", "127.0.0.1"); setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true"); setProperty("persistence_enabled", "false")
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val pc = Engine(Storage(File(dir, "pc")))
        pc.call(Command.SaveSettings(pc.state.settings.copy(proxy = "", relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            // 手机自己连不上 GitHub（模拟国内直连不通）
            phone.call(Command.SaveSettings(phone.state.settings.copy(proxy = "http://127.0.0.1:1")))
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            assertTrue(hub.pair(PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)).isSuccess)
            withTimeout(20_000) { while (hub.remote.value?.conn?.value !is ConnState.Connected) delay(100) }
            val via = GitHubViaHost(hub, phone)
            via.install()

            // 1. 手机自己点登录：跳回手机，换令牌交给电脑
            val r = phone.call(Command.AccountGitHubSignIn)
            assertTrue(r.ok, r.message)
            browserBack(r.data)
            withTimeout(20_000) { while (phone.state.account.githubSigningIn) delay(50) }
            assertEquals("libai379", phone.state.account.githubLogin, phone.state.account.githubError)
            assertEquals("", pc.state.account.githubLogin, "电脑只帮忙换，不绑到电脑自己的账号上")
            assertTrue(exchanges.get() >= 1)

            // 2. 电脑上登录了，手机连着就带过来（手机先解绑）
            phone.call(Command.AccountGitHubUnbind)
            assertEquals("", phone.state.account.githubLogin)
            val pr = pc.call(Command.AccountGitHubSignIn)
            browserBack(pr.data)
            withTimeout(20_000) { while (pc.state.account.githubLogin.isEmpty()) delay(50) }
            withTimeout(10_000) { while (hub.remote.value!!.store.state.value.account.githubLogin.isEmpty()) delay(50) }
            assertTrue(via.syncFromHost())
            assertEquals("libai379", phone.state.account.githubLogin)
            assertEquals("李白", phone.state.account.githubName)
            assertTrue(!via.syncFromHost(), "一样了就不再换")
            assertTrue(phone.state.account.id != pc.state.account.id, "账号编号还是各一个")

            // 3. 没连着电脑：手机自己换，连不上就说清楚该怎么办
            hub.unpair()
            phone.call(Command.AccountGitHubUnbind)
            val r3 = phone.call(Command.AccountGitHubSignIn)
            browserBack(r3.data)
            withTimeout(30_000) { while (phone.state.account.githubSigningIn) delay(50) }
            assertEquals("", phone.state.account.githubLogin)
            assertTrue("连上电脑" in phone.state.account.githubError, phone.state.account.githubError)
        } finally {
            relay.stop(); broker.stopServer()
        }
    }
}
