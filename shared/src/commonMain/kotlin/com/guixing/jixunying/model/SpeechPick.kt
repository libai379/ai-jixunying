package com.guixing.jixunying.model

/**
 * 读出来 / 听写用哪一家（docs/向量和语音设计说明.md 第 2 节，2026-10-12）。
 * 设置 → 语音 里指定了就用指定的；没指定（自动）按这个顺序挑，只挑已启用、填了 Key 的服务商：
 * 读出来 小米 → MiniMax（turbo）→ 智谱 → 千问；听写 小米 → MiniMax → 智谱。
 * 小米排第一是因为它最便宜（读出来限时免费），千问排最后是因为余额少、价格也没查（CLAUDE.md 红线）。
 */
object SpeechPick {

    private val ttsOrder = listOf("mimo", "minimax", "zhipu", "qwen")
    private val asrOrder = listOf("mimo", "minimax", "zhipu")

    /** 指定了服务商就用指定那位；没指定按顺序自动挑；都没有能用的返回 null。 */
    fun forTts(state: AppState): SpeechChoice? {
        val sp = state.settings.speech
        return pick(state, sp.ttsProviderId, sp.ttsModel, ttsOrder, tts = true)
    }

    fun forAsr(state: AppState): SpeechChoice? {
        val sp = state.settings.speech
        return pick(state, sp.asrProviderId, sp.asrModel, asrOrder, tts = false)
    }

    private fun pick(state: AppState, providerId: String, modelId: String, order: List<String>, tts: Boolean): SpeechChoice? {
        val only = state.provider(providerId)
        if (providerId.isNotBlank() && only != null && ImagePick.usable(only)) return choice(only, modelId, tts)
        for (platform in order) {
            // 同一家平台用户可能加了好几个服务商（比如国内版 + 国际版），按加进来的顺序看第一个能用的
            state.providers.firstOrNull { Thinking.platformOf(it) == platform && ImagePick.usable(it) }?.let { return choice(it, "", tts) }
        }
        return null
    }

    private fun choice(p: ProviderConfig, modelId: String, tts: Boolean): SpeechChoice? {
        val default = (if (tts) SpeechModels.ttsDefaultOf(p) else SpeechModels.asrDefaultOf(p)) ?: return null
        // 没指定就用官方文档里的那个默认模型。不从用户拉的模型列表里按「名字带 tts」去挑：
        // 小米列表里还有 mimo-v2.5-tts-voiceclone / -voicedesign，要额外传音频样本或音色描述，挑中了就读不出来（主会话审查 10-12）
        val model = modelId.ifBlank { default }
        return SpeechChoice(p.id, p.name, model, noteOf(p, model))
    }

    private fun noteOf(p: ProviderConfig, model: String): String {
        val maker = when (Thinking.platformOf(p)) {
            "mimo" -> "小米官方"
            "minimax" -> "MiniMax 官方"
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
    fun isAuto(state: AppState, tts: Boolean): Boolean {
        val id = if (tts) state.settings.speech.ttsProviderId else state.settings.speech.asrProviderId
        val p = state.provider(id)
        return id.isBlank() || p == null || !ImagePick.usable(p)
    }
}
