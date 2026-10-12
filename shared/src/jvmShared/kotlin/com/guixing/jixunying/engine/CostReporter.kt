package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.CostLine
import com.guixing.jixunying.model.CostReport
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.UsageKinds
import com.guixing.jixunying.model.UsageRecord
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 花费：按账本和价格表估算（时间都按北京时间算）。
 * 人民币和美元分开累计，页面按汇率合计；价格表里没有的模型、没返回用量的调用单独计数，不瞎估。
 */
class CostReporter(private val ledger: Ledger, private val stateOf: () -> AppState, private val clock: () -> Long = System::currentTimeMillis) {

    fun periodStart(period: String, now: Long = clock()): Long {
        val today = Instant.ofEpochMilli(now).atZone(Ledger.BEIJING).toLocalDate()
        val day: LocalDate? = when (period) {
            "today" -> today
            "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "month" -> today.withDayOfMonth(1)
            else -> null
        }
        return day?.atStartOfDay(Ledger.BEIJING)?.toInstant()?.toEpochMilli() ?: 0L
    }

    fun report(period: String): CostReport {
        val s = stateOf()
        val from = periodStart(period)
        val recs = ledger.since(from)
        val overrides = s.settings.costs.overrides

        /** 一组记录加起来。 */
        class Acc(val key: String, val label: String, val sub: String = "") {
            var cny = 0.0; var usd = 0.0; var calls = 0; var images = 0
            var prompt = 0L; var cached = 0L; var completion = 0L; var unpriced = 0; var noUsage = 0
            var priceText = ""; var priceKey = ""; var overridden = false; var price: com.guixing.jixunying.model.ModelPrice? = null; var hasOfficial = false
            fun line() = CostLine(key, label, sub, cny, usd, calls, images, prompt, cached, completion, unpriced, noUsage, priceText, priceKey, overridden, price, hasOfficial)
        }
        val total = Acc("total", "合计")
        val members = LinkedHashMap<String, Acc>()
        val models = LinkedHashMap<String, Acc>()
        val kinds = LinkedHashMap<String, Acc>()
        val days = LinkedHashMap<String, Acc>()

        for (r in recs) {
            val p = s.provider(r.providerId)
            val price = Prices.of(r.platform, r.model, overrides, p?.baseUrl.orEmpty())
            val money = Prices.cost(r, price)
            val missing = r.images == 0 && r.prompt == 0 && r.completion == 0 && r.units <= 0.0
            fun add(a: Acc) {
                a.calls++; a.images += r.images
                a.prompt += r.prompt; a.cached += r.cached; a.completion += r.completion
                when {
                    missing -> a.noUsage++
                    money == null -> a.unpriced++
                    price!!.currency == "USD" -> a.usd += money
                    else -> a.cny += money
                }
            }
            add(total)
            val (mk, ml) = when {
                r.kind == UsageKinds.CHAT || (r.kind == UsageKinds.IMAGE && r.memberId.isNotBlank()) ->
                    r.memberId to (s.member(r.memberId)?.name ?: "已删除的成员")
                r.kind == UsageKinds.IMAGE -> "painter" to "画图助手"
                r.kind == UsageKinds.TTS || r.kind == UsageKinds.ASR -> "speech" to "语音"
                r.kind == UsageKinds.TEST -> "test" to "测试连通"
                r.kind == UsageKinds.OTHER -> "other" to "其他"
                else -> "recorder" to "记录员（后台）"
            }
            add(members.getOrPut(mk) { Acc(mk, ml) })
            val modelKey = Prices.key(r.platform, r.model)
            add(models.getOrPut(modelKey) {
                Acc(modelKey, r.model, p?.name ?: r.provider).also { a ->
                    a.priceKey = modelKey
                    a.overridden = modelKey in overrides
                    a.price = price
                    a.hasOfficial = Prices.of(r.platform, r.model, emptyMap(), p?.baseUrl.orEmpty()) != null
                    a.priceText = price?.let(Prices::describe) ?: "价格表里没有这个模型（可以自己填单价）"
                }
            })
            add(kinds.getOrPut(r.kind) { Acc(r.kind, UsageKinds.label(r.kind)) })
            val d = Instant.ofEpochMilli(r.at).atZone(Ledger.BEIJING).toLocalDate().toString()
            add(days.getOrPut(d) { Acc(d, d) })
        }
        val rate = s.settings.costs.usdRate
        fun List<CostLine>.byMoney() = sortedWith(compareByDescending<CostLine> { it.total(rate) }.thenByDescending { it.calls })
        val all = if (from == 0L) recs else ledger.since(0)
        return CostReport(
            period = period, from = from, total = total.line(),
            byMember = members.values.map { it.line() }.byMoney(),
            byModel = models.values.map { it.line() }.byMoney(),
            byKind = kinds.values.map { it.line() }.byMoney(),
            daily = calendar(from, days) { Acc(it, it) }.map { it.line() },
            usdRate = rate,
            firstAt = all.firstOrNull()?.at ?: 0L,
            backfillUntil = all.lastOrNull { it.backfill }?.at ?: 0L,
        )
    }

    /**
     * 按天的柱子要按日历排：没用的日子也占一格（不然 10 月 1 日和 8 日看着像挨着的两天）。
     * 最多最近 31 天；「全部」也只看最近 31 天。
     */
    private fun <A> calendar(from: Long, days: Map<String, A>, make: (String) -> A): List<A> {
        if (days.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(clock()).atZone(Ledger.BEIJING).toLocalDate()
        val first = days.keys.minOf { LocalDate.parse(it) }
        var d = maxOf(first, today.minusDays(30), if (from > 0) Instant.ofEpochMilli(from).atZone(Ledger.BEIJING).toLocalDate() else first)
        val out = mutableListOf<A>()
        while (!d.isAfter(today)) { out += days[d.toString()] ?: make(d.toString()); d = d.plusDays(1) }
        return out
    }

    /**
     * 手填余额以后这家服务商花了多少（换成手填的币种）。按时间顺序：每条调用的 token 先从资源包 / 免费额度里扣
     * （BillingRules：智谱、千问是先扣这个的），扣不完的部分按价格表算钱；画图按张算钱，不扣 token。
     * @return spent 花了多少；packLeft 资源包还剩多少 token；unpriced 价格表里没有、没算进去的调用次数
     */
    fun manualEstimate(providerId: String, m: com.guixing.jixunying.model.ManualBalance): ManualEstimate {
        val s = stateOf()
        val rate = s.settings.costs.usdRate
        var pack = m.packTokens
        var spent = 0.0
        var unpriced = 0
        for (r in ledger.since(m.at).filter { it.providerId == providerId && it.at > m.at }) {
            val tokens = (r.prompt + r.completion).toLong()
            var share = 1.0
            if (pack > 0 && r.images == 0 && tokens > 0) {
                val use = minOf(pack, tokens)
                pack -= use
                share = (tokens - use).toDouble() / tokens
            }
            if (share <= 0.0) continue
            val price = Prices.of(r.platform, r.model, s.settings.costs.overrides, s.provider(r.providerId)?.baseUrl.orEmpty())
            val c = Prices.cost(r, price)
            if (c == null || price == null) { unpriced++; continue }
            val inCurrency = when {
                price.currency == m.currency -> c
                price.currency == "USD" -> c * rate
                else -> c / rate
            }
            spent += inCurrency * share
        }
        return ManualEstimate(spent, pack, unpriced)
    }

    class ManualEstimate(val spent: Double, val packLeft: Long, val unpriced: Int)

    /** 测试和界面用：单条记录的钱（原币种）。 */
    fun costOf(r: UsageRecord): Double? {
        val s = stateOf()
        return Prices.cost(r, Prices.of(r.platform, r.model, s.settings.costs.overrides, s.provider(r.providerId)?.baseUrl.orEmpty()))
    }
}
