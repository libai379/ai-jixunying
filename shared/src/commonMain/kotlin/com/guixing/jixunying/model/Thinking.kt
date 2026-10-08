package com.guixing.jixunying.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 成员怎么想。 */
@Serializable
enum class ThinkingMode {
    /** 不传参数，按模型自己的习惯（有的默认先想再答，有的直接答）。 */
    AUTO,
    /** 关掉思考直接答；关不掉的就尽量少想。快、省钱，难题可能差一点。 */
    FAST,
    /** 先想透再答（有档位的开到最高）。慢、多花钱，适合难题。 */
    DEEP,
}

/**
 * 一个模型怎么切换思考。
 * @param fast 「快速」时放进请求体的字段；null = 关不掉，选了也按默认
 * @param deep 「深度」时放进请求体的字段；空对象 = 默认就会深想，不用另外传；null = 这个模型不会深想
 * @param thinksByDefault 不传参数时会不会先想再答；null = 不清楚
 * @param note 给人看的补充说明（比如「关不掉，只能少想一点」）
 * @param known 查到过这家怎么切换；false 时界面上快速 / 深度都不能选
 */
data class ThinkingRule(
    val fast: JsonObject?,
    val deep: JsonObject?,
    val thinksByDefault: Boolean?,
    val note: String = "",
    val known: Boolean = true,
)

object Thinking {
    private val EMPTY = JsonObject(emptyMap())

    fun label(mode: ThinkingMode) = when (mode) {
        ThinkingMode.AUTO -> "默认"
        ThinkingMode.FAST -> "快速"
        ThinkingMode.DEEP -> "深度"
    }

    /** 这种方式在这个模型上能不能用（能用才会真的传参数）。 */
    fun available(rule: ThinkingRule, mode: ThinkingMode) = when (mode) {
        ThinkingMode.AUTO -> true
        ThinkingMode.FAST -> rule.fast != null
        ThinkingMode.DEEP -> rule.deep != null
    }

    /** 要放进请求体的字段；null = 什么都不传。 */
    fun params(rule: ThinkingRule, mode: ThinkingMode): JsonObject? = when (mode) {
        ThinkingMode.AUTO -> null
        ThinkingMode.FAST -> rule.fast
        ThinkingMode.DEEP -> rule.deep
    }?.takeIf { it.isNotEmpty() }

    /** 设置里选中某种方式时，下面那行说明。 */
    fun explain(rule: ThinkingRule, mode: ThinkingMode): String {
        val base = when (mode) {
            ThinkingMode.AUTO -> when (rule.thinksByDefault) {
                true -> "按模型自己的习惯：这个模型默认会先想再答，难题答得好，但慢一些。"
                false -> "按模型自己的习惯：这个模型默认直接回答，不先想。"
                null -> "按模型自己的习惯，不额外传参数。"
            }
            ThinkingMode.FAST -> when {
                !rule.known -> "没查到这家怎么切换思考，选了也按默认回答。"
                rule.fast == null -> "这个模型关不掉思考，选了也按默认回答。"
                else -> "不先想，直接回答：快、省钱；算数、推理这类难题可能差一点。"
            }
            ThinkingMode.DEEP -> when {
                !rule.known -> "没查到这家怎么切换思考，选了也按默认回答。"
                rule.deep == null -> "这个模型不会深度思考，选了也按默认回答。"
                rule.deep.isEmpty() -> "这个模型默认就会想透再答，和「默认」一样。"
                else -> "先想透再答：慢一些、多花一些，适合难题、方案、要推理的问题。"
            }
        }
        return if (rule.note.isBlank()) base else "$base${rule.note}"
    }

    /** 某个服务商上的某个模型怎么切换思考。各家参数查证见 docs/参考资料.md「切换思考的参数」。 */
    fun rule(p: ProviderConfig?, model: String): ThinkingRule {
        if (p == null || model.isBlank()) return UNKNOWN
        return Rules.of(platformOf(p), model.trim().lowercase())
    }

    /** 看预设编号，再看接口地址（自定义服务商填的官方地址也能认出来）。 */
    internal fun platformOf(p: ProviderConfig): String {
        val u = p.baseUrl.lowercase()
        return when {
            p.presetId == "deepseek" || "api.deepseek.com" in u -> "deepseek"
            p.presetId.startsWith("minimax") || "minimaxi.com" in u || "minimax.io" in u -> "minimax"
            p.presetId == "zhipu" || p.presetId == "zai" || "bigmodel.cn" in u || "api.z.ai" in u -> "zhipu"
            p.presetId == "mimo" || "xiaomimimo.com" in u -> "mimo"
            p.presetId == "qianwen" || p.presetId.startsWith("dashscope") || "dashscope" in u || "qianwenaiapi" in u -> "qwen"
            p.presetId.startsWith("moonshot") || "moonshot" in u || "api.kimi" in u -> "kimi"
            p.presetId == "ark" || "volces.com" in u -> "ark"
            p.presetId == "siliconflow" || "siliconflow" in u -> "siliconflow"
            p.presetId == "openai" || "api.openai.com" in u -> "openai"
            p.presetId == "gemini" || "generativelanguage.googleapis.com" in u -> "gemini"
            p.presetId == "anthropic" || "api.anthropic.com" in u -> "anthropic"
            p.presetId == "openrouter" || "openrouter.ai" in u -> "openrouter"
            p.presetId == "xai" || "api.x.ai" in u -> "xai"
            else -> p.presetId
        }
    }

    internal val UNKNOWN = ThinkingRule(null, null, null, known = false)
    internal val NONE = EMPTY
}

/** 各家的切换方式（按平台 + 模型名）。 */
private object Rules {
    fun of(platform: String, model: String): ThinkingRule = Thinking.UNKNOWN
}
