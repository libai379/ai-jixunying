package com.guixing.jixunying.engine

import com.guixing.jixunying.model.BalanceInfo
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Thinking
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 查余额：用聊天用的同一个 Key 调各家的余额接口（2026-10-08 查的，出处见 docs/参考资料.md「各家价格和查余额」）。
 * 能查的：DeepSeek、Kimi、硅基流动、阶跃（官方文档写了）；MiniMax（没写进公开文档的接口，官方 CLI 在用）；OpenRouter（只看这个 Key 的额度）。
 * 查不了的（小米、千问、豆包、智谱……）告诉用户去控制台看。智谱只有控制台登录后用的接口，认不认 API Key 没确认，
 * 拿去试会把好好的 Key 报成「不对」，所以不查。
 */
class Balances(private val proxyOf: (ProviderConfig) -> String?) {

    private fun root(p: ProviderConfig) = p.baseUrl.trim().trimEnd('/')
    private fun noV1(p: ProviderConfig) = root(p).removeSuffix("/v1")

    /** 控制台地址：取预设里申请 Key 的那个网站。 */
    private fun console(p: ProviderConfig): String = Presets.byId(p.presetId).keyUrl.ifBlank { "" }

    suspend fun query(p: ProviderConfig, now: Long): BalanceInfo {
        val base = BalanceInfo(p.id, p.name, supported = false, consoleUrl = console(p), at = now)
        val platform = Thinking.platformOf(p)
        val u = p.baseUrl.lowercase()
        val plan: Pair<String, (JsonObject) -> BalanceInfo>? = when {
            platform == "deepseek" -> "${noV1(p)}/user/balance" to { o -> deepSeek(o, base) }
            platform == "kimi" -> "${root(p)}/users/me/balance" to { o -> kimi(o, base, if ("moonshot.ai" in u) "USD" else "CNY") }
            platform == "siliconflow" -> "${root(p)}/user/info" to { o -> siliconFlow(o, base, if ("siliconflow.com" in u) "USD" else "CNY") }
            platform == "stepfun" -> "${root(p)}/accounts" to { o -> stepFun(o, base) }
            platform == "minimax" -> "${noV1(p)}/account/query_balance" to { o -> miniMax(o, base, if ("minimax.io" in u) "USD" else "CNY") }
            platform == "openrouter" -> "${root(p)}/key" to { o -> openRouter(o, base) }
            else -> null
        }
        if (plan == null) return base.copy(text = "这家没有用 API Key 查余额的接口，到控制台看")
        // 非公开接口：出错时也要标出来，401 / 403 说「不认 API Key」而不是「Key 不对」
        val unofficial = platform == "minimax"
        val b = base.copy(supported = true, unofficial = unofficial)
        val key = p.apiKey.trim()
        if (key.isEmpty()) return b.copy(text = "还没填 API Key")
        // Key 里混进了换行之类的字符：网络库的报错会把整个 Key 带出来，提前拦下
        if (key.any { it < ' ' }) return b.copy(text = "Key 里有换行或看不见的字符，到 设置 → 模型服务 重新粘贴一遍")
        val (url, parse) = plan
        return try {
            withTimeout(15_000) {
                val resp = Http.client(proxyOf(p)).get(url) {
                    header("Authorization", "Bearer $key")
                    header("Accept", "application/json")
                }
                val text = resp.bodyAsText()
                if (resp.status.value !in 200..299) {
                    val why = when (resp.status.value) {
                        401, 403 -> if (unofficial) "这个接口不认这个 Key（订阅套餐的 Key 查不了），到控制台看" else "Key 不对、失效，或者这个 Key 没有查余额的权限"
                        404 -> "接口不存在（可能改了），到控制台看"
                        else -> "查询失败"
                    }
                    return@withTimeout b.copy(text = "$why（HTTP ${resp.status.value}）")
                }
                val o = runCatching { Json.parse(text).jsonObject }.getOrNull()
                    ?: return@withTimeout b.copy(text = "返回的内容看不懂")
                parse(o).let { if (unofficial) it.copy(unofficial = true) else it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) b.copy(text = "查询超时") else throw e
        } catch (e: Throwable) {
            // 报错原文里不能带出 Key（会显示在页面上、传到手机）
            val msg = (e.message ?: e::class.simpleName.orEmpty()).replace(key, com.guixing.jixunying.model.maskKey(key)).take(80)
            b.copy(text = "网络连不上：$msg")
        }
    }

    // —— 各家返回的格式 ——

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun money(v: Double, currency: String) = (if (currency == "USD") "$" else "¥") + "%.2f".format(v)

    private fun ok(base: BalanceInfo, amount: Double, currency: String, extra: String = "", unofficial: Boolean = false) =
        base.copy(supported = true, ok = true, amount = amount, currency = currency, unofficial = unofficial,
            text = "可用 ${money(amount, currency)}" + (if (extra.isNotBlank()) "（$extra）" else ""))

    /** {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]} */
    private fun deepSeek(o: JsonObject, base: BalanceInfo): BalanceInfo {
        val infos = runCatching { o["balance_infos"]!!.jsonArray.mapNotNull { it.obj() } }.getOrNull().orEmpty()
        val info = infos.firstOrNull { it.str("currency") == "CNY" } ?: infos.firstOrNull() ?: return base.copy(supported = true, text = "返回里没有余额")
        val cur = info.str("currency") ?: "CNY"
        val total = info.num("total_balance") ?: return base.copy(supported = true, text = "返回里没有余额")
        val gift = info.num("granted_balance") ?: 0.0
        return ok(base, total, cur, if (gift > 0) "其中赠送 ${money(gift, cur)}" else "")
    }

    /** {"code":0,"data":{"available_balance":49.58,"voucher_balance":46.58,"cash_balance":3.0}} */
    private fun kimi(o: JsonObject, base: BalanceInfo, cur: String): BalanceInfo {
        val d = o["data"].obj() ?: return base.copy(supported = true, text = "返回里没有余额")
        val avail = d.num("available_balance") ?: return base.copy(supported = true, text = "返回里没有余额")
        val voucher = d.num("voucher_balance") ?: 0.0
        val cash = d.num("cash_balance") ?: 0.0
        return ok(base, avail, cur, listOfNotNull(if (voucher > 0) "代金券 ${money(voucher, cur)}" else null, if (cash < 0) "欠费 ${money(-cash, cur)}" else null).joinToString("，"))
    }

    /** {"data":{"totalBalance":"88.88","chargeBalance":"88.00","balance":"0.88"}}（balance 像是赠送部分，官方没写） */
    private fun siliconFlow(o: JsonObject, base: BalanceInfo, cur: String): BalanceInfo {
        val d = o["data"].obj() ?: return base.copy(supported = true, text = "返回里没有余额")
        val total = d.num("totalBalance") ?: return base.copy(supported = true, text = "返回里没有余额")
        return ok(base, total, cur)
    }

    /** {"object":"account","balance":12.3,"total_cash_balance":20,"total_voucher_balance":5,"type":"prepaid"} */
    private fun stepFun(o: JsonObject, base: BalanceInfo): BalanceInfo {
        val b = o.num("balance") ?: return base.copy(supported = true, text = "返回里没有余额")
        return ok(base, b, "CNY")
    }

    /** {"available_amount":"12.30","cash_balance":"10.00","voucher_balance":"2.30","owed_amount":"0","base_resp":{"status_code":0}} */
    private fun miniMax(o: JsonObject, base: BalanceInfo, cur: String): BalanceInfo {
        val code = o["base_resp"].obj()?.get("status_code")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        if (code != 0) return base.copy(supported = true, unofficial = true, text = "查不了（MiniMax 返回 $code；订阅套餐的 Key 查不了余额）")
        val avail = o.num("available_amount") ?: return base.copy(supported = true, unofficial = true, text = "返回里没有余额")
        val voucher = o.num("voucher_balance") ?: 0.0
        val owed = o.num("owed_amount") ?: 0.0
        return ok(base, avail, cur, listOfNotNull(if (voucher > 0) "代金券 ${money(voucher, cur)}" else null, if (owed > 0) "欠费 ${money(owed, cur)}" else null).joinToString("，"), unofficial = true)
    }

    /** {"data":{"limit":10,"limit_remaining":7.5,"usage":2.5}}：只是这个 Key 的额度，不是账户余额 */
    private fun openRouter(o: JsonObject, base: BalanceInfo): BalanceInfo {
        val d = o["data"].obj() ?: return base.copy(supported = true, text = "返回里没有额度")
        val remaining = d.num("limit_remaining")
        val used = d.num("usage") ?: 0.0
        return if (remaining != null) ok(base, remaining, "USD", "这个 Key 的剩余额度")
        else base.copy(supported = true, ok = true, currency = "USD", text = "这个 Key 没设上限，已用 ${money(used, "USD")}（账户余额要用管理 Key 查）")
    }
}
