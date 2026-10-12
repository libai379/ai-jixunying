package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 设置里的「按意思搜」（docs/向量和语音设计说明.md 第 2、3 节，2026-10-12）。
 * 服务商和 Key 都用用户已经加好的，这里只决定「聊天记录 / 我的文档」各自要不要按意思搜、用哪一家的哪个向量模型。
 * 以后往这里加旧版不认识的新项，记得 Settings.SCHEMA 加一，并在 Engine 的 SaveSettings 里保留电脑上的那几项。
 */
@Serializable
data class SemanticSettings(
    /** 聊天记录按意思搜（默认关）。 */
    val historyOn: Boolean = false,
    /** 我的文档按意思搜（默认关）。 */
    val docsOn: Boolean = false,
    /** 用哪个服务商算向量；留空 = 自动挑（智谱 → 千问 → 自定义，见 EmbedPick）。 */
    val providerId: String = "",
    /** 用哪个模型；留空 = 用这家的默认（智谱 embedding-3 / 千问 text-embedding-v4）。「自定义」必须用户自己填。 */
    val model: String = "",
)

/** 自动挑出来的一个向量服务商（模型 + 哪一家）。 */
data class EmbedChoice(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    /** 哪家平台（Thinking.platformOf），记账和查价格按它。 */
    val platform: String,
    /** 给人看的说明：谁家的、要不要钱。 */
    val note: String,
)

/**
 * 向量模型（2026-10-12 查的官方文档，原话摘在 docs/参考资料.md「向量和语音」一节）。
 * 模型名照官方原文写：智谱 embedding-3、千问 text-embedding-v4；用户在用的另外几家（DeepSeek、MiniMax、小米）
 * 官方没有向量接口，所以「自定义」留给硅基流动、本机 Ollama 这类 OpenAI 兼容的地址，模型名用户自己填。
 */
object EmbedModels {
    const val ZHIPU = "embedding-3"
    const val QWEN = "text-embedding-v4"

    /** 智谱、千问都显式要 1024 维（设计说明第 2 节：存得小、够用；智谱默认是 2048）。 */
    const val DIM = 1024

    /**
     * 估算用的换算：每个字大约几个 token。官方没给，10-12 用智谱 embedding-3 真 Key 量了三句（LiveSemanticTest）：
     * 纯中文 0.72、中英数字混排 0.75、英文 0.20（按字符算）。估算按 0.75（偏多一点，宁可多估）；千问没量过。
     */
    const val TOKENS_PER_CHAR = 0.75

    const val TOKEN_NOTE = "token 数是粗估：官方没有给换算，按智谱实测的每个汉字约 0.75 个 token 算的（只量过几句，千问没量过）。"

    /**
     * 按意思搜的门槛：余弦相似度低于它的不算「意思相近」，不放进结果（不然库里随便什么都会被排进来）。
     * 10-12 用智谱 embedding-3 真 Key 量了两种（LiveSemanticTest）：
     * - 两句短句子之间：意思相近 0.707～0.776，不相关 0.476～0.596；
     * - 短问句对库里 500 字一段的长回答（库里大多是这种）：相关的 0.39～0.60，不相关的 0.23～0.32。
     * 两种范围重叠，一个数顾不全；先按第二种取中间的 0.36（当初按第一种定 0.62，模拟器上实测按意思一条都搜不到）。
     * 代价：短消息之间不相关的（0.48～0.60）也会放进来，由成员自己看片段判断。样本少，未确认，用得多了再调。
     * 别的模型没量过，先不设门槛（0 = 不挡）。
     */
    fun minScore(model: String): Double = if (model == ZHIPU) 0.36 else 0.0

    private val defaults = mapOf("zhipu" to ZHIPU, "qwen" to QWEN)

    fun default(platform: String): String? = defaults[platform]

    fun defaultOf(p: ProviderConfig): String? = default(Thinking.platformOf(p))

    /** 这位服务商能不能算向量：智谱、千问有官方接口；「自定义」按 OpenAI 兼容写，模型名用户填。 */
    fun supports(p: ProviderConfig): Boolean = defaultOf(p) != null || p.presetId == "custom"

    /** 一次最多发几条（设计说明第 2 节：智谱 64 条、千问 10 条、自定义按 10 条）。 */
    fun batchOf(p: ProviderConfig): Int =
        if (p.presetId == "custom") 10 else if (Thinking.platformOf(p) == "zhipu") 64 else 10

    /**
     * 智谱、千问显式传 dimensions（我们知道它们认这个字段）；
     * 「自定义」不传，用它自己默认的维度（第一次算回来的长度就是它的维度，见 engine/VectorStore.kt）。
     */
    fun sendsDimensions(p: ProviderConfig): Boolean = p.presetId != "custom"
}

/** 一份向量（聊天记录或我的文档）的补算进度，给界面看（AppState.semanticProgress）。 */
@Serializable
data class SemanticPart(
    val done: Int = 0,
    val total: Int = 0,
    /** 正在算。 */
    val running: Boolean = false,
    /** 最后一次出错的原因（成功了就清掉）。 */
    val error: String = "",
    /** 这一份的向量是哪个平台 / 模型 / 维度算的（还没算过就是空的）。 */
    val platform: String = "",
    val model: String = "",
    val dim: Int = 0,
    val builtAt: Long = 0,
    /** 存的向量不是现在这个模型算出来的（换了模型，要「重新算一遍」）。 */
    val stale: Boolean = false,
) {
    /** 给人看的一句。 */
    val text: String
        get() = when {
            error.isNotBlank() -> "出错：$error"
            stale -> "以前是用 $model 算的，和现在选的不一样：点「重新算一遍」才能按意思搜（会再花一次钱），现在先按字面搜"
            running -> "正在算 $done / $total 段"
            done == 0 && total == 0 -> "还没算过"
            done >= total -> "算好了（$done 段）"
            else -> "算了 $done / $total 段"
        }
}

/** 按意思搜的进度（聊天记录和文档各一份）。 */
@Serializable
data class SemanticProgress(
    val history: SemanticPart = SemanticPart(),
    val docs: SemanticPart = SemanticPart(),
) {
    fun of(section: String) = if (section == "history") history else docs
}

/** 估算里的一类（聊天记录或我的文档）：多少字、几段、粗估多少 token、按价格表估多少钱（没价格就是 null）。 */
@Serializable
data class SemanticEstimatePart(
    val chars: Int = 0,
    val chunks: Int = 0,
    val tokens: Long = 0,
    val cost: Double? = null,
)

/**
 * 「算一遍要多少钱」的估算（Command.SemanticEstimate 的结果）。不联网：按本机的聊天记录和文档的字数算。
 * token 是粗估（EmbedModels.TOKEN_NOTE），钱按价格表算；价格表里没有这个模型时 cost 是 null。
 */
@Serializable
data class SemanticEstimateResult(
    val providerId: String = "",
    val providerName: String = "",
    val model: String = "",
    /** 给人看，比如「embedding-3（智谱 家）」。 */
    val label: String = "",
    /** 价格表里这个模型怎么算钱（没有就是「价格表里没有…」）。 */
    val priceText: String = "",
    val currency: String = "CNY",
    val history: SemanticEstimatePart = SemanticEstimatePart(),
    val docs: SemanticEstimatePart = SemanticEstimatePart(),
    val tokenNote: String = EmbedModels.TOKEN_NOTE,
)
