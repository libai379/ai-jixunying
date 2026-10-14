package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.UsdRate
import com.guixing.jixunying.model.Command
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 美元汇率自动更新（engine/UsdRate，2026-10-14）。用本地假服务器装成两家汇率接口：
 * 第一家坏了换第二家、数离谱不用、两家都不行时不改原来的汇率；手动模式下不自动取。
 */
class UsdRateTest {
    private val dir = kotlin.io.path.createTempDirectory("jxy-usdrate").toFile()
    private var port = 0
    private lateinit var fake: EmbeddedServer<*, *>
    private val calls = mutableListOf<String>()

    @BeforeTest
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        fake = embeddedServer(CIO, port = port) {
            routing {
                // 照真接口的样子（2026-10-14 实测的返回）
                get("/er/ok") { calls += "er/ok"; call.respondText("""{"result":"success","base_code":"USD","rates":{"USD":1,"CNY":6.705025}}""", ContentType.Application.Json) }
                get("/er/error") { calls += "er/error"; call.respondText("""{"result":"error","error-type":"unknown-code"}""", ContentType.Application.Json) }
                get("/fr/ok") { calls += "fr/ok"; call.respondText("""{"amount":1.0,"base":"USD","date":"2026-10-13","rates":{"CNY":6.6938}}""", ContentType.Application.Json) }
                get("/fr/crazy") { calls += "fr/crazy"; call.respondText("""{"rates":{"CNY":670.38}}""", ContentType.Application.Json) }
                get("/html") { calls += "html"; call.respondText("<html>维护中</html>", ContentType.Text.Html) }
            }
        }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        fake.stop(100, 500)
        dir.deleteRecursively()
    }

    private fun src(name: String, path: String, er: Boolean) = UsdRate.Source(name, "http://127.0.0.1:$port$path") { o ->
        if (er && o["result"]?.toString()?.trim('"') != "success") null else UsdRate.cny(o)
    }

    private fun rates(vararg s: UsdRate.Source) = UsdRate({ null }, s.toList())

    @Test
    fun firstSourceWins() = runBlocking {
        val r = rates(src("甲", "/er/ok", true), src("乙", "/fr/ok", false)).fetch()
        assertEquals(6.705025, r.rate)
        assertEquals("甲", r.source)
        assertEquals(listOf("er/ok"), calls, "第一家通了就不去问第二家")
    }

    @Test
    fun fallsBackWhenFirstIsBroken() = runBlocking {
        // 第一家回 error、回网页都算不行，换第二家
        assertEquals(6.6938, rates(src("甲", "/er/error", true), src("乙", "/fr/ok", false)).fetch().rate)
        assertEquals(6.6938, rates(src("甲", "/html", false), src("乙", "/fr/ok", false)).fetch().rate)
    }

    @Test
    fun rejectsCrazyNumbers() = runBlocking {
        // 670 这种（单位搞错、接口出错）不拿来用：只剩它一家时报错
        val e = runCatching { rates(src("乙", "/fr/crazy", false)).fetch() }.exceptionOrNull()
        assertTrue(e != null && "看着不对" in e.message.orEmpty(), e?.message)
    }

    @Test
    fun engineUpdatesAndKeepsOldRateOnFailure() = runBlocking {
        val e = Engine(Storage(File(dir, "data")))
        assertEquals(7.1, e.state.settings.costs.usdRate)
        assertTrue(e.state.settings.costs.usdAuto, "默认自动更新")

        // 都不行：失败，原来的汇率不动
        e.usdRates = rates(src("甲", "/er/error", true), src("乙", "/fr/crazy", false))
        val bad = e.call(Command.RefreshUsdRate)
        assertFalse(bad.ok)
        assertTrue("取不到最新汇率" in bad.message, bad.message)
        assertEquals(7.1, e.state.settings.costs.usdRate)
        assertEquals(0L, e.state.settings.costs.usdRateAt)

        // 通了：汇率、时间、来源都记下（保留四位小数）
        e.usdRates = rates(src("ExchangeRate-API", "/er/ok", true))
        val ok = e.call(Command.RefreshUsdRate)
        assertTrue(ok.ok, ok.message)
        val c = e.state.settings.costs
        assertEquals(6.705, c.usdRate)
        assertEquals("ExchangeRate-API", c.usdRateFrom)
        assertTrue(c.usdRateAt > 0)

        // 刚取过：不强制的话 12 小时内不再取
        calls.clear()
        assertTrue(e.refreshUsdRate(force = false).ok)
        assertTrue(calls.isEmpty(), "12 小时内不再联网")

        // 用户手动填了：改成手动；不强制时不自动取，点「改回自动」（RefreshUsdRate）又变回自动
        assertTrue(e.call(Command.SaveCosts(e.state.settings.costs.copy(usdRate = 7.0, usdAuto = false, usdRateAt = 0))).ok)
        assertTrue(e.refreshUsdRate(force = false).ok)
        assertTrue(calls.isEmpty(), "手动模式下不自动取")
        assertEquals(7.0, e.state.settings.costs.usdRate)
        assertTrue(e.call(Command.RefreshUsdRate).ok)
        assertTrue(e.state.settings.costs.usdAuto)
        assertEquals(6.705, e.state.settings.costs.usdRate)
    }
}
