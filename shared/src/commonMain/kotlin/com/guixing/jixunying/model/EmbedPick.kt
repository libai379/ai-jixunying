package com.guixing.jixunying.model

/**
 * 按意思搜用哪一家算向量（docs/向量和语音设计说明.md 第 2 节，2026-10-12）。
 * 设置里指定了就用指定的；没指定（自动）按这个顺序挑，只挑已启用、填了 Key 的服务商：
 * 智谱 embedding-3 → 千问 text-embedding-v4 → 自定义（presetId 是 custom 的服务商，模型名必须用户自己填，
 * 没填就挑不出来——不知道填哪个模型就不知道发给谁）。用户在用的另外几家（DeepSeek、MiniMax、小米）官方没有向量接口。
 * 挑不出来返回 null：设置页里这个开关要灰掉，搜索照旧只按字面。
 */
object EmbedPick {

    private val order = listOf("zhipu", "qwen")

    /** 这次用哪个服务商、哪个模型；挑不出来返回 null。 */
    fun pick(state: AppState): EmbedChoice? {
        val sem = state.settings.semantic
        // 设置里指定了哪家就用哪家（模型名留空就用官方默认；自定义服务商没有默认，留空就挑不出来，往下走自动挑）
        val only = state.provider(sem.providerId)
        if (sem.providerId.isNotBlank() && only != null && ImagePick.usable(only)) choice(only, sem.model)?.let { return it }
        for (platform in order) {
            // 同一家平台用户可能加了好几个服务商（比如国内版 + 国际版），按加进来的顺序看第一个能用的
            state.providers.firstOrNull { Thinking.platformOf(it) == platform && ImagePick.usable(it) }?.let { return choice(it, "") }
        }
        if (sem.model.isNotBlank()) {
            state.providers.firstOrNull { it.presetId == "custom" && ImagePick.usable(it) }?.let { return choice(it, sem.model) }
        }
        return null
    }

    private fun choice(p: ProviderConfig, modelId: String): EmbedChoice? {
        val model = modelId.trim().ifBlank { EmbedModels.defaultOf(p).orEmpty() }
        if (model.isBlank()) return null
        return EmbedChoice(p.id, p.name, model, Thinking.platformOf(p), noteOf(p, model))
    }

    /** 给人看的一句：谁家的 · 要不要钱（设置页里列服务商时也用它，所以是公开的）。 */
    fun noteOf(p: ProviderConfig, model: String): String {
        val maker = when (Thinking.platformOf(p)) {
            "zhipu" -> "智谱官方"
            "qwen" -> "阿里官方"
            else -> p.name
        }
        val price = Prices.of(Thinking.platformOf(p), model, baseUrl = p.baseUrl)
        val how = when {
            price == null -> "价格没查到"
            price.isFree -> "限时免费"
            else -> Prices.describe(price)
        }
        return "$maker · $how"
    }

    /** 设置里指定了但用不了（服务商删了、停用了、没填 Key）：自动挑。 */
    fun isAuto(state: AppState): Boolean {
        val p = state.provider(state.settings.semantic.providerId)
        return state.settings.semantic.providerId.isBlank() || p == null || !ImagePick.usable(p)
    }
}
