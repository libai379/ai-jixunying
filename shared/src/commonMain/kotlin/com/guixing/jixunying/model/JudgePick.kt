package com.guixing.jixunying.model

/**
 * 立场档案的裁判（判断立场、AI 核实）用哪个模型。设置里指定了就用指定的；否则自动找 deepseek-flash；
 * 找不到就退回记录员。裁判要求：能用工具（AI 核实需要 web_search）、不能太贵、理解能力要好。
 */
object JudgePick {
    private const val PREFERRED_JUDGE = "deepseek-flash"

    fun pick(state: AppState): Pair<ProviderConfig, String>? {
        val ms = state.settings.memory
        // 设置里指定了就用
        if (ms.judgeProviderId.isNotBlank() && ms.judgeModelId.isNotBlank()) {
            val p = state.provider(ms.judgeProviderId)
            if (p != null && ImagePick.usable(p)) return p to ms.judgeModelId
        }
        return auto(state)
    }

    fun auto(state: AppState): Pair<ProviderConfig, String>? {
        // 自动找：deepseek-flash 优先
        val chat = state.providers.filter(ImagePick::usable).flatMap { p -> p.models.filter { !it.imageGen }.map { p to it.id } }
        chat.firstOrNull { it.second.lowercase() == PREFERRED_JUDGE }?.let { return it }
        // 别的平台上的同一个模型（比如 deepseek-ai/DeepSeek-V4.1-Flash）
        chat.firstOrNull { it.second.lowercase().let { id -> "deepseek" in id && "flash" in id } }?.let { return it }
        // 找不到就用记录员
        return RecorderPick.pick(state)
    }

    fun isAuto(state: AppState): Boolean {
        val ms = state.settings.memory
        val p = state.provider(ms.judgeProviderId)
        return ms.judgeProviderId.isBlank() || ms.judgeModelId.isBlank() || p == null || !ImagePick.usable(p)
    }
}
