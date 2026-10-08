package com.guixing.jixunying

import com.guixing.jixunying.engine.CostReporter
import com.guixing.jixunying.engine.Ledger
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.CostSettings
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelPrice
import com.guixing.jixunying.model.PriceTier
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.UsageRecord
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 花费：价格表、时段、分档、缓存、画图张数、改价；汇总按成员 / 模型 / 用途，查不到价和没返回用量的单独数。 */
class CostsTest {
    private fun bj(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, Ledger.BEIJING).toInstant().toEpochMilli()
    private fun near(expected: Double, actual: Double?) = assertTrue(actual != null && abs(expected - actual) < 1e-9, "期望 $expected，实际 $actual")
    private fun rec(at: Long, platform: String, model: String, prompt: Int = 0, cached: Int = 0, completion: Int = 0, images: Int = 0,
                    kind: String = "chat", member: String = "", provider: String = "p") =
        UsageRecord(at, kind, provider, "服务商", platform, model, prompt, cached, completion, images, member)

    @Test
    fun peakHours() {
        // 2026-10-07 是星期三
        assertTrue(Prices.isPeak(bj(2026, 10, 7, 9, 0)))
        assertTrue(Prices.isPeak(bj(2026, 10, 7, 11, 59)))
        assertFalse(Prices.isPeak(bj(2026, 10, 7, 12, 0)))
        assertTrue(Prices.isPeak(bj(2026, 10, 7, 17, 59)))
        assertFalse(Prices.isPeak(bj(2026, 10, 7, 18, 0)))
        assertFalse(Prices.isPeak(bj(2026, 10, 10, 10, 0)), "星期六全天空闲")
        assertFalse(Prices.isPeak(bj(2026, 10, 11, 15, 0)), "星期日全天空闲")
    }

    @Test
    fun priceTable() {
        val ds = Prices.of("deepseek", "deepseek-flash")
        // 一百万输入（没命中）+ 一百万输出：高峰 2 + 8 = 10 元，空闲半价
        near(10.0, Prices.cost(rec(bj(2026, 10, 7, 10), "deepseek", "deepseek-flash", 1_000_000, 0, 1_000_000), ds))
        near(5.0, Prices.cost(rec(bj(2026, 10, 7, 20), "deepseek", "deepseek-flash", 1_000_000, 0, 1_000_000), ds))
        // 命中缓存的部分按命中价：90 万命中 ×0.04 + 10 万没命中 ×2，高峰
        near(0.036 + 0.2, Prices.cost(rec(bj(2026, 10, 7, 10), "deepseek", "deepseek-flash", 1_000_000, 900_000, 0), ds))
        // MiniMax-M3：输入超过 512k 整次按长上下文价；国际站按美元
        near(0.6 * 4.20, Prices.cost(rec(0, "minimax", "MiniMax-M3", 600_000), Prices.of("minimax", "MiniMax-M3")))
        near(0.1 * 2.10, Prices.cost(rec(0, "minimax", "MiniMax-M3", 100_000), Prices.of("minimax", "MiniMax-M3")))
        assertEquals("USD", Prices.of("minimax", "MiniMax-M3", baseUrl = "https://api.minimax.io/v1")!!.currency)
        // 智谱 GLM-5.3-Flash 不免费
        near(2.8, Prices.cost(rec(0, "zhipu", "GLM-5.3-Flash", completion = 1_000_000), Prices.of("zhipu", "GLM-5.3-Flash")))
        assertTrue(Prices.of("zhipu", "cogview-3-flash")!!.isFree)
        // 千问图像 0.18 元一张
        near(0.36, Prices.cost(rec(0, "qwen", "qwen-image-3.0", images = 2, kind = "image"), Prices.of("qwen", "qwen-image-3.0")))
        // 小米 v2.6-pro-ultraspeed 不能被当成 pro
        near(60.0, Prices.cost(rec(0, "mimo", "mimo-v2.6-pro-ultraspeed", completion = 1_000_000), Prices.of("mimo", "mimo-v2.6-pro-ultraspeed")))
        // 查不到的返回 null
        assertNull(Prices.of("custom", "my-model"))
        // 用户改过的单价优先
        val mine = mapOf(Prices.key("custom", "My-Model") to ModelPrice("CNY", listOf(PriceTier(input = 1.0, output = 1.0))))
        near(2.0, Prices.cost(rec(0, "custom", "my-model", 1_000_000, 0, 1_000_000), Prices.of("custom", "my-model", mine)))
        assertTrue("空闲时段半价" in Prices.describe(ds!!))
    }

    @Test
    fun report() {
        val dir = kotlin.io.path.createTempDirectory("jxy-cost").toFile()
        try {
            val ledger = Ledger(dir)
            val state = AppState(
                providers = listOf(ProviderConfig("pd", "deepseek", "DeepSeek", "https://api.deepseek.com"), ProviderConfig("pq", "qianwen", "千问", "https://maas.qianwenaiapi.com/compatible-mode/v1")),
                members = listOf(Member("ma", "阿德", providerId = "pd", modelId = "deepseek-flash")),
                settings = Settings(costs = CostSettings(usdRate = 7.0)),
            )
            val now = bj(2026, 10, 8, 20)
            ledger.addAll(listOf(
                rec(bj(2026, 9, 30, 10), "deepseek", "deepseek-flash", 1_000_000, 0, 0, member = "ma", provider = "pd"),       // 上个月，不算进本月
                rec(bj(2026, 10, 7, 10), "deepseek", "deepseek-flash", 1_000_000, 0, 1_000_000, member = "ma", provider = "pd"), // 10 元
                rec(bj(2026, 10, 8, 19), "deepseek", "deepseek-flash", 0, 0, 0, kind = "stances", provider = "pd"),              // 没返回用量
                rec(bj(2026, 10, 8, 19), "qwen", "qwen-image-3.0", images = 1, kind = "image", provider = "pq"),                 // 0.18 元
                rec(bj(2026, 10, 8, 19), "custom", "my-model", 1000, 0, 1000, member = "ma", provider = "px"),                  // 查不到价
            ))
            val r = CostReporter(ledger, { state }, { now }).report("month")
            near(10.18, r.total.cny)
            assertEquals(4, r.total.calls)
            assertEquals(1, r.total.noUsage)
            assertEquals(1, r.total.unpriced)
            assertEquals(listOf("阿德", "画图助手", "记录员（后台）"), r.byMember.map { it.label })
            assertEquals(2, r.byMember.first().calls)
            val m = r.byModel.first { it.label == "my-model" }
            assertTrue("价格表里没有" in m.priceText)
            assertEquals(listOf("2026-10-07", "2026-10-08"), r.daily.map { it.key })
            // 全部：上个月那条也算上；补记标记没有
            near(10.18 + 2.0, CostReporter(ledger, { state }, { now }).report("all").total.cny)
            assertEquals(bj(2026, 10, 8, 0), CostReporter(ledger, { state }, { now }).periodStart("today"))
            assertEquals(bj(2026, 10, 5, 0), CostReporter(ledger, { state }, { now }).periodStart("week"), "本周从星期一算")
        } finally {
            dir.deleteRecursively()
        }
    }
}
