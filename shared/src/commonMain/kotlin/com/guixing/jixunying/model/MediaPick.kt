package com.guixing.jixunying.model

/**
 * AI 自己挑模型（2026-11-03 用户：「让 AI 也能调用模型，包括画图修图视频语音等等，只有成员他自己是换不了的，
 * 这样的话，如果一张图修不好，让他多试几个，随便调用各种模型和工具」）。
 * 画图、补画（P 图里交给模型的那一步）、做视频、配音的工具多一个 model 参数：只能从用户配好 Key 的服务商里挑，
 * 没写就照设置（自动）。成员自己聊天用的模型不在这里，换不了。再试花的钱由 ImageGenSettings.retryBudget 管（Engine 记账）。
 */
object MediaPick {

    /** 「qwen-image-3.0（千问AI平台）」「qwen-image-3.0@千问」「 wan2.7-image 」都认成模型名。 */
    fun clean(model: String?): String =
        model.orEmpty().trim().substringBefore('（').substringBefore('(').substringBefore('@').substringBefore('|').trim()

    // —— 画图 ——

    /** 按 AI 写的模型名找画图模型（不分大小写）；没写或者找不到返回 null。 */
    fun image(state: AppState, model: String?): ImageChoice? {
        val want = clean(model)
        if (want.isEmpty()) return null
        return ImagePick.candidates(state.providers).firstOrNull { it.modelId.equals(want, ignoreCase = true) }
    }

    // —— 改图（补画）——

    /**
     * 能把原图传进去改的模型。出处：千问AI平台「图像生成模型」页（qwen-image-2.0 以后、wan2.7-image 都是「编辑 ✓」）、
     * 「万相2.1 通用图像编辑」页（wanx2.1-imageedit 能给遮罩），见 docs/参考资料.md「用户充值的千问AI平台能怎么改图」。
     */
    fun canEdit(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.startsWith("qwen-image-2") || m.startsWith("qwen-image-3") || m.startsWith("wan2.7-image") || m == "wanx2.1-imageedit"
    }

    /** 没指定时的顺序：qwen-image-3.0（用户配的）→ wan2.7-image（能框出范围）→ wanx2.1-imageedit（能给遮罩，最便宜）。 */
    private val editOrder = listOf("qwen-image-3.0", "wan2.7-image", "wanx2.1-imageedit")

    /**
     * 改图的候选：只接了千问AI平台 / 阿里百炼（同一个 Key 通用，列表里没写的也能调）。
     * 设置里选的画图模型能改图就排第一，其余按 editOrder，再是服务商列表里别的能改图的。
     */
    fun editCandidates(state: AppState): List<ImageChoice> {
        val ig = state.settings.imageGen
        val out = mutableListOf<Pair<Int, ImageChoice>>()
        state.providers.filter { ImagePick.usable(it) && VideoPick.vendorOf(it) == VideoPick.DASHSCOPE }.forEachIndexed { pi, p ->
            val listed = p.models.filter { it.imageGen && canEdit(it.id) }.map { it.id }
            (listed + editOrder.filter { id -> listed.none { it.equals(id, ignoreCase = true) } }).distinct().forEach { id ->
                val order = editOrder.indexOfFirst { it.equals(id, ignoreCase = true) }.let { if (it < 0) editOrder.size else it }
                val rank = (if (p.id == ig.providerId && id.equals(ig.modelId, ignoreCase = true)) 0 else 1 + order) * 100 + pi
                out += rank to ImageChoice(p.id, p.name, id, free = false, inferred = id !in listed, note = noteOf(state, p, id))
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** model 空 = 排第一的；写了就按名字找，候选里没有、但名字认得而且有千问的 Key，也让用（同一个 Key 通用）。 */
    fun edit(state: AppState, model: String?): ImageChoice? {
        val all = editCandidates(state)
        val want = clean(model)
        if (want.isEmpty()) return all.firstOrNull()
        all.firstOrNull { it.modelId.equals(want, ignoreCase = true) }?.let { return it }
        if (!canEdit(want)) return null
        val p = state.providers.firstOrNull { ImagePick.usable(it) && VideoPick.vendorOf(it) == VideoPick.DASHSCOPE } ?: return null
        return ImageChoice(p.id, p.name, want, free = false, inferred = true, note = noteOf(state, p, want))
    }

    private fun noteOf(state: AppState, p: ProviderConfig, model: String): String {
        val price = price(state, p, model)
        return p.name + " · " + when {
            price == null -> "价格没查到"
            price.isFree -> "免费"
            price.perImage != null -> "约 ${yuan(toCny(price, price.perImage))} 元一张"
            else -> Prices.describe(price)
        }
    }

    // —— 做视频 ——

    /**
     * 做视频按名字挑：要有这家的 Key，素材它也收得了。返回（选择, 不行的原因）；model 空 = 照设置（VideoPick.resolve）。
     */
    fun video(state: AppState, model: String?, need: Set<String>): Pair<VideoChoice?, String?> {
        val want = clean(model)
        if (want.isEmpty()) return VideoPick.resolve(state, need) to null
        val names = VideoPick.modelsFor(state.providers).joinToString("、") { it.id }
        val m = VideoPick.models.firstOrNull { it.id.equals(want, ignoreCase = true) }
            ?: return null to "没有「$want」这个做视频的模型。能用的：$names。"
        val p = VideoPick.candidates(state.providers).firstOrNull { VideoPick.vendorOf(it) == m.vendor }
            ?: return null to "${m.id} 要${if (m.vendor == VideoPick.MINIMAX) " MiniMax 中国版" else "千问AI平台或阿里百炼"}的 Key，用户没配。能用的：$names。"
        val miss = need.filter { it !in m.media }
        if (miss.isNotEmpty()) return null to "${m.id} 收不了${miss.joinToString("、") { VideoMediaUse.label(it) }}。能用的：$names。"
        return VideoChoice(p.id, p.name, m) to null
    }

    // —— 配音 ——

    /** 能配音的模型（每家的读出来模型），按设置里那家排前面。 */
    fun speechCandidates(state: AppState): List<SpeechChoice> {
        val first = SpeechPick.forTts(state)
        val all = state.providers.filter { ImagePick.usable(it) && SpeechModels.supportsTts(it) }
            .flatMap { p -> SpeechModels.ttsListOf(p).map { m -> SpeechChoice(p.id, p.name, m, SpeechPick.noteOf(p, m)) } }
        return (listOfNotNull(first) + all.filter { it.providerId != first?.providerId || it.modelId != first.modelId })
    }

    /** model 空 = 设置里的；写了按名字找。 */
    fun speech(state: AppState, model: String?): SpeechChoice? {
        val want = clean(model)
        if (want.isEmpty()) return SpeechPick.forTts(state)
        return speechCandidates(state).firstOrNull { it.modelId.equals(want, ignoreCase = true) }
    }

    // —— 价钱估算（再试时管上限用；查不到价的按偏贵的算，宁可少试一次）——

    private fun price(state: AppState, p: ProviderConfig, model: String): ModelPrice? =
        Prices.of(Thinking.platformOf(p), model, state.settings.costs.overrides, p.baseUrl)

    /** 美元按 7.2 折成元，只用来估上限。 */
    private fun toCny(price: ModelPrice, v: Double) = if (price.currency == "USD") v * 7.2 else v

    /** 画一张多少钱，写给 AI 看：「免费」「约 0.18 元一张」；价格表里没有的照实写「价钱没查到」（不拿估的上限冒充）。 */
    fun imagePriceText(state: AppState, providerId: String, model: String): String {
        val p = state.provider(providerId) ?: return "价钱没查到"
        val price = price(state, p, model) ?: return "价钱没查到"
        if (price.isFree) return "免费"
        return price.perImage?.let { "约 ${yuan(toCny(price, it))} 元一张" } ?: "价钱没查到"
    }

    /** 画一张 / 改一张大概多少钱（元）。 */
    fun imageCost(state: AppState, providerId: String, model: String): Double {
        val p = state.provider(providerId) ?: return UNKNOWN_IMAGE
        val price = price(state, p, model) ?: return UNKNOWN_IMAGE
        if (price.isFree) return 0.0
        return price.perImage?.let { toCny(price, it) } ?: UNKNOWN_IMAGE
    }

    /** 做一段视频大概多少钱（元）：每秒价 × 秒数；billingModel 带档位（VideoPick.billingModel）。 */
    fun videoCost(state: AppState, providerId: String, billingModel: String, seconds: Int): Double {
        val p = state.provider(providerId) ?: return UNKNOWN_VIDEO_SECOND * seconds
        val price = price(state, p, billingModel)
        val perSec = price?.perSecond?.let { toCny(price, it) } ?: UNKNOWN_VIDEO_SECOND
        return perSec * seconds.coerceAtLeast(1)
    }

    /** 配一段音大概多少钱（元）：每万字符的价 × 字数。 */
    fun speechCost(state: AppState, providerId: String, model: String, chars: Int): Double {
        val p = state.provider(providerId) ?: return 0.05
        val price = price(state, p, model) ?: return 0.05
        if (price.isFree) return 0.0
        return price.perChar?.let { toCny(price, it) * chars / 10_000.0 } ?: 0.05
    }

    const val UNKNOWN_IMAGE = 0.5
    const val UNKNOWN_VIDEO_SECOND = 1.0

    /** 0.18 → "0.18"，2.0 → "2"。 */
    fun yuan(v: Double): String {
        val r = kotlin.math.round(v * 100) / 100.0
        return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
    }
}
