package com.guixing.jixunying.engine

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 美元兑人民币汇率自动校准（用户 2026-10-14 要的：「联网自己去搜索最新的」）。
 *
 * 两个不要 Key 的公开接口，第一个不行换第二个（2026-10-14 在这台电脑上直连、走代理都试过能通）：
 * - ExchangeRate-API 的免费接口 https://open.er-api.com/v6/latest/USD → rates.CNY（每天更新一次）
 * - Frankfurter（数据是欧洲央行的参考汇率）https://api.frankfurter.dev/v1/latest?base=USD&symbols=CNY → rates.CNY（工作日更新）
 * 当时两家分别是 6.705025（10-14 00:02 UTC）、6.6938（10-13），对得上；原来默认写死的 7.1 高了约 6%。
 * 两家的使用条款没读到原文（条款页当时打不开），按保守的用法：最多每 12 小时取一次，结果存起来。
 */
class UsdRate(
    private val proxyOf: () -> String?,
    /** 去哪取（名字、地址、怎么从返回里取出 CNY）。测试里换成本地假服务器。 */
    private val sources: List<Source> = SOURCES,
) {

    class Source(val name: String, val url: String, val parse: (JsonObject) -> Double?)

    /** perUsd：1 美元换多少别的货币（取得到的都放进来，至少有 CNY）。 */
    class Result(val rate: Double, val source: String, val perUsd: Map<String, Double> = emptyMap())

    /** 取一次。先直连，连不上再走设置里的代理；两个接口都不行就抛出最后一个错。 */
    suspend fun fetch(): Result {
        var last: Throwable? = null
        for (src in sources) {
            val name = src.name
            for (proxy in listOf(null, proxyOf()).distinct()) {
                try {
                    val o = withTimeout(12_000) {
                        val text = Http.client(proxy).get(src.url) { header("Accept", "application/json") }.bodyAsText()
                        Json.parse(text).jsonObject
                    }
                    val r = src.parse(o)
                    if (r == null || r !in SANE) throw IllegalStateException("$name 返回的汇率看着不对：$r")
                    return Result(r, name, perUsd(o))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    last = e
                }
            }
        }
        throw last ?: IllegalStateException("取不到汇率")
    }

    companion object {
        /** 自动更新的间隔：取到以后 12 小时内不再取。 */
        const val EVERY_MS = 12 * 60 * 60_000L

        /** 1 美元折人民币的合理范围：超出就当接口出错了，不拿来用。 */
        val SANE = 4.0..12.0

        /** 两家都是 {"rates":{"CNY":数}}；ExchangeRate-API 另有 "result":"success"。 */
        fun cny(o: JsonObject): Double? = (o["rates"] as? JsonObject)?.get("CNY")?.jsonPrimitive?.doubleOrNull

        /** 换算要的几种（2026-10-24）：人民币、港币、新加坡元、英镑、欧元、日元；美元自己是 1。 */
        val CURRENCIES = listOf("CNY", "HKD", "SGD", "GBP", "EUR", "JPY")

        fun perUsd(o: JsonObject): Map<String, Double> {
            val rates = o["rates"] as? JsonObject ?: return emptyMap()
            return CURRENCIES.mapNotNull { c -> rates[c]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }?.let { c to it } }.toMap() + ("USD" to 1.0)
        }

        val SOURCES: List<Source> = listOf(
            Source("ExchangeRate-API", "https://open.er-api.com/v6/latest/USD") { o ->
                if (o["result"]?.jsonPrimitive?.content == "success") cny(o) else null
            },
            Source("Frankfurter（欧洲央行）", "https://api.frankfurter.dev/v1/latest?base=USD&symbols=CNY,HKD,SGD,GBP,EUR,JPY") { o -> cny(o) },
        )
    }
}
