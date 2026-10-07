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
 * 画图用哪个模型。用户在 设置 → 画图 指定了就用指定的；没指定（自动）就从已经配好 Key 的服务商里挑：
 * 免费的排最前，其次是用户自己列出来的画图模型，最后是按预设推断的（比如 MiniMax 的 Key 也能调 image-01）。
 */
object ImagePick {

    private val freeModels = setOf("cogview-3-flash")

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
            (listed + preset.filter { it !in listed }).distinct().forEach { id ->
                val free = id.lowercase() in freeModels
                val inferred = id !in listed
                val rank = when {
                    free -> 0
                    !inferred -> 1_000 + pi
                    else -> 2_000 + pi
                }
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
            if (p != null && usable(p)) {
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
        return ig.providerId.isBlank() || ig.modelId.isBlank() || p == null || !usable(p)
    }
}
