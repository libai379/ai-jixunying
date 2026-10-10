package com.guixing.jixunying.model

/** 一个能用的画图模型。 */
data class ImageChoice(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    /** 免费（智谱 cogview-3-flash）。 */
    val free: Boolean,
    /** 服务商的模型列表里没有，是按预设推断这家平台能画（同一个 Key 通用）。 */
    val inferred: Boolean,
    /** 给人看的说明：谁家的、要不要钱。 */
    val note: String,
)

/**
 * 画图用哪个模型。用户在 设置 → 画图 指定了就用指定的；没指定（自动）就从已经配好 Key 的服务商里挑，画质优先：
 * 千问图像排第一（用户定的默认：画里的中文字写得准，免费的 cogview-3-flash 会把字画成鬼画符），
 * 其次是 Seedream、gpt-image、混元 3.0、万相、可灵这些一线模型，再是其他收费的，免费的垫底兜底。
 * 同一档里，用户自己列出来的画图模型排在按预设推断的前面（比如 MiniMax 的 Key 也能调 image-01）。
 */
object ImagePick {

    private val freeModels = setOf("cogview-3-flash")

    /**
     * 已经下线的画图模型：阶跃的文生图、图生图、图片编辑接口 2026-10-10 整个停了（step-2x-large、step-1x 等，出处见 docs/参考资料.md）。
     * 自动挑时跳过；以前在 设置 → 画图 指定了的也不再用，按自动挑。
     */
    fun retired(model: String): Boolean {
        val id = model.lowercase()
        return id.startsWith("step-1x") || id.startsWith("step-2x") || id.startsWith("step-image")
    }

    private val topModels = listOf("seedream", "gpt-image", "hy-image", "wan2", "kling")

    /** 画质档次，越小越靠前。 */
    private fun tier(model: String): Int {
        val id = model.lowercase()
        return when {
            "qwen-image" in id -> 0
            id in freeModels -> 3
            topModels.any { it in id } -> 1
            else -> 2
        }
    }

    fun usable(p: ProviderConfig) = p.enabled && (p.apiKey.isNotBlank() || isLocalUrl(p.baseUrl))

    fun isLocalUrl(url: String) = "127.0.0.1" in url || "localhost" in url

    private fun noteOf(p: ProviderConfig, model: String, free: Boolean): String {
        val maker = when (p.presetId) {
            "zhipu", "zai" -> "智谱官方"
            "minimax", "minimax-global" -> "MiniMax 官方"
            "ark" -> "字节火山方舟官方（豆包）"
            "dashscope", "dashscope-intl", "qianwen" -> "阿里官方"
            "tokenhub" -> "腾讯官方（混元）"
            "qianfan" -> "百度官方"
            "stepfun" -> "阶跃官方"
            "kling", "kling-global" -> "可灵官方（快手）"
            "openai" -> "OpenAI 官方"
            "xai" -> "xAI 官方"
            "siliconflow" -> "硅基流动（开源模型）"
            "modelscope" -> "魔搭（开源模型）"
            else -> p.name
        }
        val price = when {
            free -> "免费"
            p.presetId == "modelscope" -> "每天有免费额度"
            else -> "按张收费"
        }
        return "$maker · $price"
    }

    /** 自动模式下的全部候选，按优先顺序。 */
    fun candidates(providers: List<ProviderConfig>): List<ImageChoice> {
        val out = mutableListOf<Pair<Int, ImageChoice>>()
        providers.filter(::usable).forEachIndexed { pi, p ->
            val listed = p.models.filter { it.imageGen }.map { it.id }
            val preset = if (p.presetId == "custom") emptyList() else Presets.byId(p.presetId).models.filter { it.imageGen }.map { it.id }
            (listed + preset.filter { it !in listed }).distinct().filterNot(::retired).forEach { id ->
                val free = id.lowercase() in freeModels
                val inferred = id !in listed
                val rank = tier(id) * 10_000 + (if (inferred) 1_000 else 0) + pi
                out += rank to ImageChoice(p.id, p.name, id, free, inferred, noteOf(p, id, free))
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** 实际要用的：指定了且还能用就只用它，否则走自动候选。 */
    fun resolve(state: AppState): List<ImageChoice> {
        val ig = state.settings.imageGen
        if (ig.providerId.isNotBlank() && ig.modelId.isNotBlank()) {
            val p = state.provider(ig.providerId)
            if (p != null && usable(p) && !retired(ig.modelId)) {
                val free = ig.modelId.lowercase() in freeModels
                return listOf(ImageChoice(p.id, p.name, ig.modelId, free, inferred = false, note = noteOf(p, ig.modelId, free)))
            }
        }
        return candidates(state.providers)
    }

    /** 自动模式（没有有效的指定）。 */
    fun isAuto(state: AppState): Boolean {
        val ig = state.settings.imageGen
        val p = state.provider(ig.providerId)
        return ig.providerId.isBlank() || ig.modelId.isBlank() || p == null || !usable(p) || retired(ig.modelId)
    }
}
