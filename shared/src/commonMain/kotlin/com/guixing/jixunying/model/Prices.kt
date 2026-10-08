package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/** 一档价格：整次请求的输入 token（含命中缓存）不超过 upTo 时用这一档。单位：每百万 token。 */
@Serializable
data class PriceTier(
    val upTo: Int = Int.MAX_VALUE,
    val input: Double,
    /** 命中缓存的输入；null = 按普通输入价算（这家没有缓存价）。 */
    val cached: Double? = null,
    val output: Double,
)

/**
 * 一个模型的价格。币种 CNY（元）或 USD（美元）。
 * @param offPeakHalf DeepSeek 那样分时段：表里是高峰价，北京时间工作日 9–12 点、14–18 点以外半价
 * @param perImage 画图模型每张的价格
 */
@Serializable
data class ModelPrice(
    val currency: String = "CNY",
    val tiers: List<PriceTier> = emptyList(),
    val perImage: Double? = null,
    val offPeakHalf: Boolean = false,
    val note: String = "",
) {
    val isFree: Boolean get() = (perImage ?: 0.0) == 0.0 && tiers.all { it.input == 0.0 && it.output == 0.0 }
}

/**
 * 官方价格（2026-10-08 查各家官方价格页，另一个助手逐条核对过；出处见 docs/参考资料.md「各家价格」）。
 * 价格会变：用户可以在花费页改某个模型的单价（Settings.costs.overrides，键是「平台|模型名小写」）。
 * 只算 token 和画图张数；联网搜索、工具调用之类按次另收的钱不在里面。
 */
object Prices {
    private fun t(input: Double, cached: Double?, output: Double, upTo: Int = Int.MAX_VALUE) = PriceTier(upTo, input, cached, output)
    private fun cny(input: Double, cached: Double?, output: Double, note: String = "") = ModelPrice("CNY", listOf(t(input, cached, output)), note = note)
    private fun usd(input: Double, cached: Double?, output: Double, note: String = "") = ModelPrice("USD", listOf(t(input, cached, output)), note = note)
    private fun img(price: Double, currency: String = "CNY", note: String = "") = ModelPrice(currency, perImage = price, note = note)
    private val FREE = ModelPrice("CNY", listOf(t(0.0, 0.0, 0.0)), perImage = 0.0, note = "免费")
    private val FREE_IMAGE = ModelPrice("CNY", perImage = 0.0, note = "免费")

    fun key(platform: String, model: String) = platform + "|" + model.trim().lowercase()

    /** 先看用户改过的，再查表。provider 用来分国内站 / 国际站（同一家两个币种）。 */
    fun of(platform: String, model: String, overrides: Map<String, ModelPrice> = emptyMap(), baseUrl: String = ""): ModelPrice? =
        overrides[key(platform, model)] ?: table(platform, model.trim().lowercase(), baseUrl.lowercase())

    private fun table(platform: String, m: String, url: String): ModelPrice? = when (platform) {
        "deepseek" -> when {
            m.startsWith("deepseek-v4-pro") -> ModelPrice("CNY", listOf(t(9.0, 0.30, 27.0)), offPeakHalf = true)
            m.startsWith("deepseek-flash") || m.startsWith("deepseek-v4-flash") -> ModelPrice("CNY", listOf(t(2.0, 0.04, 8.0)), offPeakHalf = true)
            else -> null
        }
        "mimo" -> when {
            m.startsWith("mimo-v2.6-pro-ultraspeed") -> cny(30.0, 0.25, 60.0)
            m.startsWith("mimo-v2.6-pro") || m.startsWith("mimo-v2.5-pro") -> cny(3.0, 0.025, 6.0)
            m.startsWith("mimo-v2.6-flash") || m == "mimo-v2.5" -> cny(1.0, 0.02, 2.0)
            else -> null
        }
        "minimax" -> {
            val global = "minimax.io" in url
            when {
                m == "image-01" || m == "image-01-live" -> if (global) img(0.0035, "USD") else img(0.025)
                m.startsWith("minimax-m3.1") -> null
                // M3 输入超过 512k 整次按长上下文价；国内价已含官方「永久五折」
                m.startsWith("minimax-m3") -> if (global) ModelPrice("USD", listOf(t(0.30, 0.06, 1.20, 512_000), t(0.60, 0.12, 2.40)))
                    else ModelPrice("CNY", listOf(t(2.10, 0.42, 8.40, 512_000), t(4.20, 0.84, 16.80)))
                m.startsWith("minimax-m2.7-highspeed") -> if (global) null else cny(4.2, 0.42, 16.8)
                m.startsWith("minimax-m2.7") -> if (global) null else cny(2.1, 0.42, 8.4)
                else -> null
            }
        }
        "zhipu" -> when {
            // Z.ai 国际版按美元另有价格，没查
            "api.z.ai" in url -> null
            m in setOf("glm-4.7-flash", "glm-4-flash-250414", "glm-4-flash", "glm-z1-flash", "glm-4.6v-flash", "glm-4v-flash", "glm-4.1v-thinking-flash") -> FREE
            m.startsWith("glm-5.3-flashx") -> cny(2.0, 0.57, 7.0)
            m.startsWith("glm-5.3-flash") -> cny(0.8, 0.23, 2.8, "不是免费模型")
            m.startsWith("glm-5.3") -> cny(8.0, 2.0, 28.0)
            m == "glm-4.6v" -> ModelPrice("CNY", listOf(t(1.0, 0.2, 3.0, 32_000), t(2.0, 0.4, 6.0)))
            m == "cogview-3-flash" -> FREE_IMAGE
            m.startsWith("cogview-4") -> img(0.06)
            m == "glm-image" -> img(0.1)
            else -> null
        }
        "qwen" -> when {
            // 百炼国际版按美元另有价格，没查
            "dashscope-intl" in url || "alibabacloud" in url -> null
            m.startsWith("qwen3.8-max") -> cny(12.0, 1.5, 36.0)
            // qwen3-max、qwen-plus 按整次请求的输入长度分档
            m.startsWith("qwen3-max") -> ModelPrice("CNY", listOf(t(2.5, 0.5, 10.0, 32_000), t(4.0, 0.8, 16.0, 128_000), t(7.0, 1.4, 28.0)))
            m.startsWith("qwen-plus") -> ModelPrice("CNY", listOf(t(0.8, 0.16, 2.0, 128_000), t(2.4, 0.48, 20.0, 256_000), t(4.8, 0.96, 48.0)),
                note = "按不思考的输出价算；开了思考输出贵很多（≤128K 档 8 元）")
            m.startsWith("qwen-image-3.0-pro") -> img(0.25)
            m.startsWith("qwen-image-3.0") -> img(0.18)
            m.startsWith("wan2.7-image-pro") -> img(0.5)
            m.startsWith("wan2.7-image") || m.startsWith("wan2.6-image") || m.startsWith("wan2.6-t2i") -> img(0.2)
            else -> null
        }
        "kimi" -> when {
            // 国际版（moonshot.ai）按美元，没查
            "moonshot.ai" in url -> null
            m.startsWith("kimi-k3") -> cny(20.0, 2.0, 100.0)
            m.startsWith("kimi-k2.7-code-highspeed") -> cny(13.0, 2.6, 54.0)
            m.startsWith("kimi-k2.7-code") -> cny(6.5, 1.3, 27.0)
            m.startsWith("kimi-k2.6") -> cny(6.5, 1.1, 27.0)
            else -> null
        }
        "ark" -> when {
            m.startsWith("doubao-seed-2-1-pro") -> cny(6.0, 1.2, 30.0)
            m.startsWith("deepseek-v4-1-flash") -> ModelPrice("CNY", listOf(t(2.0, 0.04, 8.0)), offPeakHalf = true)
            m.startsWith("doubao-seedream-5-0-pro") -> img(0.30, note = "1.5K 及以下；更大的图 0.60 元")
            m.startsWith("doubao-seedream-5-0-flash") -> img(0.12)
            m.startsWith("doubao-seedream-5-0") -> img(0.22, note = "按 5.0 lite 算")
            m.startsWith("doubao-seedream-4-5") -> img(0.25)
            m.startsWith("doubao-seedream-4-0") -> img(0.20)
            else -> null
        }
        "stepfun" -> when {
            m.startsWith("step-3.5-flash") -> cny(0.7, 0.14, 2.1)
            m.startsWith("step-3.7-flash") -> cny(1.35, 0.27, 8.1)
            m.startsWith("step-5-preview") -> cny(7.0, 0.35, 20.0)
            m.startsWith("step-1o-turbo-vision") -> cny(2.5, 0.5, 8.0)
            m.startsWith("step-2x-large") -> img(0.1)
            else -> null
        }
        "siliconflow" -> when {
            m.endsWith("deepseek-ai/deepseek-v3.2") -> cny(4.0, 0.4, 6.0)
            m == "deepseek-ai/deepseek-v4-flash" -> cny(3.0, 0.3, 9.0, "按全天价算；官方 2–8 点有半价时段")
            m == "qwen/qwen3-vl-32b-instruct" -> cny(1.0, null, 4.0)
            m == "kwai-kolors/kolors" -> FREE_IMAGE
            m == "qwen/qwen-image" || m.startsWith("qwen/qwen-image-edit") -> img(0.30)
            m == "tongyi-mai/z-image-turbo" -> img(0.10)
            else -> null
        }
        "modelscope" -> if (m.contains("image")) FREE_IMAGE else FREE
        "openai" -> when {
            m == "gpt-5" -> usd(1.25, 0.125, 10.0)
            m == "gpt-5-mini" -> usd(0.25, 0.025, 2.0)
            m == "gpt-image-1" -> img(0.042, "USD", "按中等质量 1024×1024 算")
            else -> null
        }
        "anthropic" -> when {
            // 走 OpenAI 兼容接口没有缓存价，输入全按原价
            m.startsWith("claude-opus-5-5") || m.startsWith("claude-opus-5.5") -> usd(4.0, null, 20.0)
            m.startsWith("claude-sonnet-5-5") || m.startsWith("claude-sonnet-5.5") -> usd(2.0, null, 10.0)
            m.startsWith("claude-haiku-4-5") || m.startsWith("claude-haiku-4.5") -> usd(1.0, null, 5.0)
            else -> null
        }
        "gemini" -> when {
            m.startsWith("gemini-2.5-pro") -> ModelPrice("USD", listOf(t(1.25, 0.125, 10.0, 200_000), t(2.5, 0.25, 15.0)))
            m.startsWith("gemini-2.5-flash") && "lite" !in m -> usd(0.30, 0.03, 2.5)
            else -> null
        }
        "kling", "kling-global" -> if ("singapore" in url) img(0.028, "USD") else img(0.2, note = "按资源包折算")
        "tokenhub" -> if (m.startsWith("hy-image-v3")) img(0.2) else null
        "qianfan" -> if (m == "irag-1.0") img(0.14, note = "2025 年的官方公告价") else null
        else -> null
    }

    /** 北京时间工作日 9–12 点、14–18 点（DeepSeek 的高峰时段；法定节假日没算，按工作日处理）。 */
    fun isPeak(millis: Long): Boolean {
        val t = millis + 8 * 3_600_000L
        val day = t.floorDiv(86_400_000L)
        // 1970-01-01 是星期四：(day + 3) % 7 → 0 = 星期一 … 6 = 星期日
        val dow = (day + 3).mod(7L).toInt()
        val hour = (t.mod(86_400_000L) / 3_600_000L).toInt()
        return dow < 5 && (hour in 9..11 || hour in 14..17)
    }

    /** 一条记录花了多少（原币种）。没有价格返回 null。 */
    fun cost(r: UsageRecord, price: ModelPrice?): Double? {
        price ?: return null
        if (r.images > 0) return price.perImage?.let { it * r.images }
        // 补记的老记录把一次回答里几轮工具调用的输入加在一起了，按它挑档会偏贵：老记录一律按第一档
        val tier = (if (r.backfill) price.tiers.firstOrNull() else price.tiers.firstOrNull { r.prompt <= it.upTo })
            ?: price.tiers.lastOrNull() ?: return null
        val cached = r.cached.coerceIn(0, r.prompt)
        val miss = r.prompt - cached
        var yuan = (miss * tier.input + cached * (tier.cached ?: tier.input) + r.completion * tier.output) / 1_000_000.0
        // 补记的老记录没有可靠的时段，按高峰价算（宁可多估）
        if (price.offPeakHalf && !r.backfill && !isPeak(r.at)) yuan /= 2
        return yuan
    }

    /** 给人看的单价，比如「输入 2 · 命中 0.04 · 输出 8 元/百万（空闲半价）」。 */
    fun describe(p: ModelPrice): String {
        val unit = if (p.currency == "USD") "美元" else "元"
        if (p.perImage != null) return if (p.perImage == 0.0) "免费" else "${fmt(p.perImage)} $unit/张" + (if (p.note.isNotBlank()) "（${p.note}）" else "")
        if (p.isFree) return "免费"
        val parts = p.tiers.mapIndexed { i, tier ->
            val head = if (p.tiers.size > 1) (if (tier.upTo == Int.MAX_VALUE) "更长：" else "输入≤${tier.upTo / 1000}K：") else ""
            head + "输入 ${fmt(tier.input)}" + (tier.cached?.let { " · 命中 ${fmt(it)}" } ?: "") + " · 输出 ${fmt(tier.output)}"
        }
        return parts.joinToString("；") + " $unit/百万" + (if (p.offPeakHalf) "（高峰价，空闲时段半价）" else "") + (if (p.note.isNotBlank()) "（${p.note}）" else "")
    }

    private fun fmt(d: Double): String {
        val s = ((d * 10000).let { kotlin.math.round(it) } / 10000).toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
