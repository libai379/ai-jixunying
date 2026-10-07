package com.guixing.jixunying.model

/**
 * 记录员（压缩聊天、挑长期记忆）用哪个模型。设置里指定了就用指定的；否则自动挑便宜、指令遵循好的：
 * mimo-v2.6-flash 优先（理由见 docs/构想.md 第四节），没有就找名字带 flash / mini / lite 的，再不行用第一位成员的模型。
 */
object RecorderPick {
    private val preferred = listOf("mimo-v2.6-flash", "glm-5.3-flash", "deepseek-flash", "qwen-plus", "minimax-m3")

    fun pick(state: AppState): Pair<ProviderConfig, String>? {
        val ms = state.settings.memory
        if (ms.recorderProviderId.isNotBlank() && ms.recorderModelId.isNotBlank()) {
            val p = state.provider(ms.recorderProviderId)
            if (p != null && ImagePick.usable(p)) return p to ms.recorderModelId
        }
        return auto(state)
    }

    fun auto(state: AppState): Pair<ProviderConfig, String>? {
        val chat = state.providers.filter(ImagePick::usable).flatMap { p -> p.models.filter { !it.imageGen }.map { p to it.id } }
        for (want in preferred) chat.firstOrNull { it.second.lowercase() == want }?.let { return it }
        chat.firstOrNull { (_, m) -> listOf("flash", "mini", "lite", "turbo", "air").any { it in m.lowercase() } }?.let { return it }
        state.members.firstNotNullOfOrNull { mem ->
            state.provider(mem.providerId)?.takeIf(ImagePick::usable)?.takeIf { mem.modelId.isNotBlank() }?.let { it to mem.modelId }
        }?.let { return it }
        return chat.firstOrNull()
    }

    fun isAuto(state: AppState): Boolean {
        val ms = state.settings.memory
        val p = state.provider(ms.recorderProviderId)
        return ms.recorderProviderId.isBlank() || ms.recorderModelId.isBlank() || p == null || !ImagePick.usable(p)
    }
}
