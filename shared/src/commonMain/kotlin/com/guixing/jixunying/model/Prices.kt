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
 * @param perChar 读出来每万字符的价格（MiniMax 的算法是 1 个汉字算 2 个字符，见 SpeechUnits.ttsChars）
 * @param perHour 听写每小时的价格
 * @param perSecond 听写每秒的价格（智谱官方按 token 计、给的是每秒的参考价）；填了它就不看 perHour
 */
@Serializable
data class ModelPrice(
    val currency: String = "CNY",
    val tiers: List<PriceTier> = emptyList(),
    val perImage: Double? = null,
    val perChar: Double? = null,
    val perHour: Double? = null,
    val perSecond: Double? = null,
    val offPeakHalf: Boolean = false,
    val note: String = "",
) {
    val isFree: Boolean
        get() = (perImage ?: 0.0) == 0.0 && (perChar ?: 0.0) == 0.0 && (perHour ?: 0.0) == 0.0 && (perSecond ?: 0.0) == 0.0 &&
            tiers.all { it.input == 0.0 && it.output == 0.0 }
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
    /** 读出来：每万字符的价格。 */
    private fun chars(price: Double, note: String = "") = ModelPrice("CNY", perChar = price, note = note)
    /** 听写：每小时的价格。 */
    private fun hourly(price: Double, note: String = "") = ModelPrice("CNY", perHour = price, note = note)
    /** 听写：每秒的价格（官方只给了每秒的估算价时用这个）。 */
    private fun perSec(price: Double, note: String = "") = ModelPrice("CNY", perSecond = price, note = note)
    /** 做视频：每秒的价格，按模型名后面的档位（「wan3.0-video 480P」）挑；没写档位按最低一档。 */
    private fun videoPrice(model: String, byRes: Map<String, Double>, note: String = ""): ModelPrice {
        val res = model.split(' ').getOrNull(1).orEmpty().uppercase()
        return ModelPrice("CNY", perSecond = byRes[res] ?: byRes.values.first(),
            note = listOf(note, "按阿里云百炼官方价估，实际以千问AI平台账单为准").filter { it.isNotBlank() }.joinToString("；"))
    }
    /** MiniMax 做视频：「MiniMax-H3-Max 480P」输出、「… 480P 输入视频」、「MiniMax-H3-Max 图片」超出免费张数的图（m 已经是小写）。 */
    private fun minimaxVideo(m: String): ModelPrice {
        val max = m.startsWith("minimax-h3-max")
        if (m.endsWith(" 图片")) return img(if (max) 0.5 else 0.2, note = if (max) "超出 2 张免费的部分" else "超出 5 张免费的部分")
        val res = m.split(' ').getOrNull(1).orEmpty().uppercase()
        val input = m.endsWith(" 输入视频")
        val price = when {
            max && input -> if (res == "768P") 0.97 else 0.37
            max -> if (res == "768P") 0.5 else 0.33
            else -> if (res == "2K") 0.8 else 0.5
        }
        return perSec(price, if (input) "输入的参考视频按秒" else "")
    }
    private val FREE = ModelPrice("CNY", listOf(t(0.0, 0.0, 0.0)), perImage = 0.0, note = "免费")
    private val FREE_IMAGE = ModelPrice("CNY", perImage = 0.0, note = "免费")

    fun key(platform: String, model: String) = platform + "|" + model.trim().lowercase()

    /** 先看用户改过的，再查表。provider 用来分国内站 / 国际站（同一家两个币种）。 */
    fun of(platform: String, model: String, overrides: Map<String, ModelPrice> = emptyMap(), baseUrl: String = ""): ModelPrice? =
        overrides[key(platform, model)] ?: table(platform, model.trim().lowercase(), baseUrl.lowercase())

    private fun table(platform: String, m: String, url: String): ModelPrice? = when (platform) {
        // 用 ChatGPT 登录：花的是订阅额度，不按 token 另外收钱（2026-10-23）
        Presets.CHATGPT -> cny(0.0, 0.0, 0.0, note = "ChatGPT 订阅额度，不另外收钱")
        // 本机跑的模型（Ollama、LM Studio）：电费以外不花钱（2026-10-24）
        "ollama", "lmstudio" -> cny(0.0, 0.0, 0.0, note = "本机模型，不花钱")
        "deepseek" -> when {
            m.startsWith("deepseek-v4-pro") -> ModelPrice("CNY", listOf(t(9.0, 0.30, 27.0)), offPeakHalf = true)
            m.startsWith("deepseek-flash") || m.startsWith("deepseek-v4-flash") -> ModelPrice("CNY", listOf(t(2.0, 0.04, 8.0)), offPeakHalf = true)
            else -> null
        }
        "mimo" -> when {
            // 语音：读出来官方写「Free for a limited time」；听写国内 0.5 元 / 小时，按秒记
            m.startsWith("mimo-v2.5-tts") -> chars(0.0, "限时免费")
            m.startsWith("mimo-v2.5-asr") -> hourly(0.5)
            m.startsWith("mimo-v2.6-pro-ultraspeed") -> cny(30.0, 0.25, 60.0)
            m.startsWith("mimo-v2.6-pro") || m.startsWith("mimo-v2.5-pro") -> cny(3.0, 0.025, 6.0)
            m.startsWith("mimo-v2.6-flash") || m == "mimo-v2.5" -> cny(1.0, 0.02, 2.0)
            else -> null
        }
        "minimax" -> {
            val global = "minimax.io" in url
            when {
                m == "image-01" || m == "image-01-live" -> if (global) img(0.0035, "USD") else img(0.025)
                // 语音：价格出自 platform.minimax.cn 的价格页（国际站的价格没查）
                m.startsWith("speech-2.8-hd") -> if (global) null else chars(3.5, "1 个汉字算 2 个字符")
                m.startsWith("speech-2.8-turbo") -> if (global) null else chars(2.0, "1 个汉字算 2 个字符")
                m.startsWith("asr-1.0") -> if (global) null else hourly(2.5)
                // 做视频（2026-10-21 platform.minimaxi.com 价格页「视频」原文）：输出按秒；输入视频按秒（价按输出档位）；
                // 图片 H3 5 张以内免费、超出 0.20 元/张，H3-Max 2 张以内免费、超出 0.50 元/张；音频免费。记账时三样分开记（VideoPick.billingModel）
                m.startsWith("minimax-h3") -> if (global) null else minimaxVideo(m)
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
            // 语音：GLM-TTS 2 元 / 万字符；glm-asr-2512 官方按 token 计、给的是「约 0.0002 元/秒」的参考价
            // 向量：embedding-3 0.5 元 / 百万 token（只有输入，没有输出）
            m.startsWith("embedding-3") -> cny(0.5, null, 0.0)
            m.startsWith("glm-tts") -> chars(2.0)
            m.startsWith("glm-asr") -> perSec(0.0002, "官方按 token 计，这里按约每秒 0.0002 元估")
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
            // 读出来 qwen3-tts-flash：官方价格页是动态加载的，没读到数字（未确认），所以不给价（花费页会说没算进去）
            m.startsWith("qwen3.8-max") -> cny(12.0, 1.5, 36.0)
            // qwen3-max、qwen-plus 按整次请求的输入长度分档
            m.startsWith("qwen3-max") -> ModelPrice("CNY", listOf(t(2.5, 0.5, 10.0, 32_000), t(4.0, 0.8, 16.0, 128_000), t(7.0, 1.4, 28.0)))
            m.startsWith("qwen-plus") -> ModelPrice("CNY", listOf(t(0.8, 0.16, 2.0, 128_000), t(2.4, 0.48, 20.0, 256_000), t(4.8, 0.96, 48.0)),
                note = "按不思考的输出价算；开了思考输出贵很多（≤128K 档 8 元）")
            m.startsWith("qwen-image-3.0-pro") -> img(0.25)
            m.startsWith("qwen-image-3.0") -> img(0.18)
            m.startsWith("wan2.7-image-pro") -> img(0.5)
            m.startsWith("wan2.7-image") || m.startsWith("wan2.6-image") || m.startsWith("wan2.6-t2i") -> img(0.2)
            // 做视频按秒，模型名后面带档位（VideoPick.billingModel）。阿里云百炼官方价格页华北 2（北京）2026-10-20 原文；
            // 千问AI平台自己的价格页是动态加载的读不到，按百炼的算（画图两边一样）。wan3.0-video 写的是原价（页面写「限时 7 折」）
            m.startsWith("wan3.0-video-prime") -> videoPrice(m, mapOf("480P" to 0.45, "720P" to 0.9, "1080P" to 1.8))
            m.startsWith("wan3.0-video") -> videoPrice(m, mapOf("480P" to 0.3, "720P" to 0.6, "1080P" to 1.2), "原价；官方写限时 7 折，实际可能更便宜")
            m.startsWith("happyhorse-1.1-t2v") -> videoPrice(m, mapOf("480P" to 0.45, "720P" to 0.9, "1080P" to 1.2))
            m.startsWith("wan2.7-t2v") || m.startsWith("wan2.6-t2v") -> videoPrice(m, mapOf("720P" to 0.6, "1080P" to 1.0))
            m.startsWith("wan2.2-t2v-plus") -> videoPrice(m, mapOf("480P" to 0.14, "1080P" to 0.7))
            m.startsWith("wanx2.1-t2v-turbo") -> videoPrice(m, mapOf("480P" to 0.24, "720P" to 0.24))
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
        // 语音：读出来按字符、听写按秒（见 SpeechUnits）
        when (r.unit) {
            SpeechUnits.CHAR -> return price.perChar?.let { it * r.units / 10_000.0 }
            SpeechUnits.SECOND -> return price.perSecond?.let { it * r.units } ?: price.perHour?.let { it * r.units / 3600.0 }
        }
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
        val tail = if (p.note.isNotBlank()) "（${p.note}）" else ""
        if (p.perImage != null) return if (p.perImage == 0.0) "免费" else "${fmt(p.perImage)} $unit/张" + tail
        if (p.perChar != null) return "${fmt(p.perChar)} $unit/万字符" + tail
        if (p.perSecond != null) return "${fmt(p.perSecond)} $unit/秒" + tail
        if (p.perHour != null) return "${fmt(p.perHour)} $unit/小时" + tail
        if (p.isFree) return "免费"
        // 只收输入的（向量模型）：不写「输出 0」，读着绕
        p.tiers.singleOrNull()?.takeIf { it.output == 0.0 && it.cached == null && it.input > 0.0 }?.let {
            return "${fmt(it.input)} $unit/百万 token（只按输入算）" + tail
        }
        val parts = p.tiers.mapIndexed { i, tier ->
            val head = if (p.tiers.size > 1) (if (tier.upTo == Int.MAX_VALUE) "更长：" else "输入≤${tier.upTo / 1000}K：") else ""
            head + "输入 ${fmt(tier.input)}" + (tier.cached?.let { " · 命中 ${fmt(it)}" } ?: "") + " · 输出 ${fmt(tier.output)}"
        }
        return parts.joinToString("；") + " $unit/百万" + (if (p.offPeakHalf) "（高峰价，空闲时段半价）" else "") + (if (p.note.isNotBlank()) "（${p.note}）" else "")
    }

    /** 最多 4 位小数。不用 Double.toString：0.0002 会变成「2.0E-4」（智谱听写的单价，10-12 截图里看到的）。 */
    internal fun fmt(d: Double): String {
        val scaled = kotlin.math.round(d * 10000).toLong()
        val a = kotlin.math.abs(scaled)
        val frac = (a % 10000).toString().padStart(4, '0').trimEnd('0')
        return (if (scaled < 0) "-" else "") + (a / 10000) + (if (frac.isNotEmpty()) ".$frac" else "")
    }
}
