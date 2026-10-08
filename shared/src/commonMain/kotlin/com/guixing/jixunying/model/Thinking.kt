package com.guixing.jixunying.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * @param fast 「快速」时放进请求体的字段；null = 关不掉，选了也按默认；空对象 = 本来就不先想
 * @param deep 「深度」时放进请求体的字段；null = 这个模型不会思考；空对象 = 默认就想透，不用另外传
 * @param thinksByDefault 不传参数时会不会先想再答；null = 模型自己看情况 / 不清楚
 * @param fastOnlyLess 关不掉思考，「快速」只是让它少想（调低思考强度）
 * @param known 查到过这家怎么切换；false 时界面上快速 / 深度都不能选
 */
data class ThinkingRule(
    val fast: JsonObject?,
    val deep: JsonObject?,
    val thinksByDefault: Boolean?,
    val fastOnlyLess: Boolean = false,
    val known: Boolean = true,
)

object Thinking {
    fun label(mode: ThinkingMode) = when (mode) {
        ThinkingMode.AUTO -> "默认"
        ThinkingMode.FAST -> "快速"
        ThinkingMode.DEEP -> "深度"
    }

    /** 这种方式在这个模型上能不能选。 */
    fun available(rule: ThinkingRule, mode: ThinkingMode) = when (mode) {
        ThinkingMode.AUTO -> true
        ThinkingMode.FAST -> rule.fast != null
        ThinkingMode.DEEP -> rule.deep != null
    }

    /** 要放进请求体的字段；null = 什么都不传（和默认一样）。 */
    fun params(rule: ThinkingRule, mode: ThinkingMode): JsonObject? = when (mode) {
        ThinkingMode.AUTO -> null
        ThinkingMode.FAST -> rule.fast
        ThinkingMode.DEEP -> rule.deep
    }?.takeIf { it.isNotEmpty() }

    /** 设置里选中某种方式时，下面那行说明。 */
    fun explain(rule: ThinkingRule, mode: ThinkingMode): String = when (mode) {
        ThinkingMode.AUTO -> when (rule.thinksByDefault) {
            true -> "按模型自己的习惯：这个模型默认会先想再答，难题答得好，但慢一些。"
            false -> "按模型自己的习惯：这个模型默认直接回答，不先想。"
            null -> if (rule.known) "按模型自己的习惯：这个模型自己判断要不要先想。" else "按模型自己的习惯，不额外传参数。"
        }
        ThinkingMode.FAST -> when {
            !rule.known -> "没查到这家怎么切换思考，选了也按默认回答。"
            rule.fast == null -> "这个模型关不掉思考，选了也按默认回答。"
            rule.fast.isEmpty() -> "这个模型本来就不先想，和「默认」一样。"
            rule.fastOnlyLess -> "这个模型关不掉思考，「快速」是让它少想一点：快一些、省一些。"
            else -> "不先想，直接回答：快、省钱；算数、推理这类难题可能差一点。"
        }
        ThinkingMode.DEEP -> when {
            !rule.known -> "没查到这家怎么切换思考，选了也按默认回答。"
            rule.deep == null -> "这个模型不会先想再答，选了也按默认回答。"
            rule.deep.isEmpty() -> "这个模型默认就会想透再答，和「默认」一样。"
            else -> "先想透再答：慢一些、多花一些，适合难题、方案、要推理的问题。"
        }
    }

    /** 某个服务商上的某个模型怎么切换思考。各家参数的出处见 docs/参考资料.md「切换思考的参数」。 */
    fun rule(p: ProviderConfig?, model: String): ThinkingRule {
        if (p == null || model.isBlank()) return UNKNOWN
        return Rules.of(platformOf(p), model.trim().lowercase())
    }

    /** 看预设编号，再看接口地址（自定义服务商填的官方地址也能认出来）。 */
    fun platformOf(p: ProviderConfig): String {
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
            p.presetId.startsWith("tokenhub") || "tencentmaas" in u -> "tokenhub"
            p.presetId == "qianfan" || "qianfan.baidubce" in u -> "qianfan"
            p.presetId == "stepfun" || "api.stepfun.com" in u -> "stepfun"
            p.presetId == "longcat" || "longcat.chat" in u -> "longcat"
            p.presetId == "ollama" || ":11434" in u -> "ollama"
            else -> p.presetId
        }
    }

    val UNKNOWN = ThinkingRule(null, null, null, known = false)
}

/**
 * 各家的切换方式（按平台 + 模型名，模型名已转小写）。2026-10-08 查的官方文档：
 * DeepSeek 思考模式、智谱「深度思考 / 思考模式」、小米 MiMo「深度思考」、千问AI平台「思考模式」、Kimi「Chat API」。
 */
private object Rules {
    private val EMPTY = JsonObject(emptyMap())
    private fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(mapOf(*kv))
    private fun type(t: String) = "thinking" to obj("type" to JsonPrimitive(t))
    private fun effort(e: String) = "reasoning_effort" to JsonPrimitive(e)
    private fun flag(on: Boolean) = "enable_thinking" to JsonPrimitive(on)

    /** 能开能关：thinking.type = enabled / disabled（DeepSeek、智谱、MiMo、Kimi K2.x 都是这个写法）。 */
    private fun typeSwitch(byDefault: Boolean?, deepExtra: Pair<String, JsonElement>? = null) = ThinkingRule(
        fast = obj(type("disabled")),
        deep = if (deepExtra == null) obj(type("enabled")) else obj(type("enabled"), deepExtra),
        thinksByDefault = byDefault,
    )

    /** 千问的写法：enable_thinking = true / false。 */
    private fun flagSwitch(byDefault: Boolean?) = ThinkingRule(obj(flag(false)), obj(flag(true)), byDefault)

    /** 一直会想、关不掉，也没有强度可调。 */
    private val ALWAYS = ThinkingRule(fast = null, deep = EMPTY, thinksByDefault = true)

    /** 不会思考的普通模型。 */
    private val NEVER = ThinkingRule(fast = EMPTY, deep = null, thinksByDefault = false)

    fun of(platform: String, m: String): ThinkingRule = when (platform) {
        // 默认开思考、强度 high；关：thinking.type=disabled；最深：reasoning_effort=max
        "deepseek" -> when {
            "reasoner" in m || "-r1" in m -> ALWAYS
            m.startsWith("deepseek") -> typeSwitch(true, effort("max"))
            else -> Thinking.UNKNOWN
        }
        "minimax" -> when {
            // M3.1-Flash-Preview 一直思考（传 disabled 报 400），强度 low…max（默认 max）
            m.startsWith("minimax-m3.1") -> ThinkingRule(fast = obj(effort("low")), deep = EMPTY, thinksByDefault = true, fastOnlyLess = true)
            // M3：thinking.type 只认 adaptive（默认，等于开）/ disabled，传 enabled 报 400
            m.startsWith("minimax-m3") -> ThinkingRule(fast = obj(type("disabled")), deep = EMPTY, thinksByDefault = true)
            // M2.x、M1 关不掉（传 disabled 不报错但照样想）
            m.startsWith("minimax-m2") || m.startsWith("minimax-m1") -> ALWAYS
            else -> Thinking.UNKNOWN
        }
        "zhipu" -> when {
            // GLM-5.3 系列强制思考（传 disabled 会报错），强度 max（默认）/ high / low
            m.startsWith("glm-5.3") -> ThinkingRule(fast = obj(type("enabled"), effort("low")), deep = EMPTY, thinksByDefault = true, fastOnlyLess = true)
            // GLM-5.2、5.1、5、4.7 默认开思考，可以关
            m.startsWith("glm-5") || m.startsWith("glm-4.7") -> typeSwitch(true)
            // GLM-4.6、4.5 系列是「混合思考」，模型自己判断要不要想
            m.startsWith("glm-4.6") || m.startsWith("glm-4.5") -> typeSwitch(null)
            m.startsWith("glm-z1") -> ALWAYS
            m.startsWith("glm-4") || m.startsWith("cogview") || m.startsWith("charglm") -> NEVER
            else -> Thinking.UNKNOWN
        }
        "mimo" -> when {
            // v2.6 / v2.5 默认开思考，thinking.type 开关；没有强度档位
            m.startsWith("mimo-v2.6") || m.startsWith("mimo-v2.5") -> typeSwitch(true)
            m.startsWith("mimo") -> typeSwitch(null)
            else -> Thinking.UNKNOWN
        }
        "qwen" -> when {
            "thinking" in m || m.startsWith("qwq") || "-r1" in m -> ALWAYS
            m.startsWith("kimi-k3") || m.startsWith("glm-5.3") -> ALWAYS
            // Qwen3.5 及以后默认开思考；qwen3-max、qwen-plus、qwen-flash、qwen-turbo 默认关
            Regex("^qwen3\\.[5-9]").containsMatchIn(m) -> flagSwitch(true)
            m.startsWith("qwen3-max") || m.startsWith("qwen-plus") || m.startsWith("qwen-flash") || m.startsWith("qwen-turbo") -> flagSwitch(false)
            Regex("^qwen3-\\d").containsMatchIn(m) -> flagSwitch(true)
            m.startsWith("deepseek-v4") -> flagSwitch(true)
            m.startsWith("deepseek-v3") -> flagSwitch(false)
            m.startsWith("qwen") -> flagSwitch(null)
            else -> Thinking.UNKNOWN
        }
        "kimi" -> when {
            // K3 一直思考，强度 low / high / max（默认 max）
            m.startsWith("kimi-k3") -> ThinkingRule(fast = obj(effort("low")), deep = EMPTY, thinksByDefault = true, fastOnlyLess = true)
            m.startsWith("kimi-k2.7-code") || "thinking" in m -> ALWAYS
            m.startsWith("kimi-k2.6") || m.startsWith("kimi-k2.5") -> typeSwitch(true)
            m.startsWith("moonshot-v1") -> NEVER
            else -> Thinking.UNKNOWN
        }
        // 以下几家是助手按官方文档查的（2026-10-08），把握稍差一点；不认的话会自动去掉参数重试
        "openai" -> when {
            // gpt-5 / gpt-5-mini 关不掉，最低 minimal、最高 high（none 是 gpt-5.1 以后才有）
            m == "gpt-5" || m.startsWith("gpt-5-") -> ThinkingRule(obj(effort("minimal")), obj(effort("high")), true, fastOnlyLess = true)
            Regex("^o\\d").containsMatchIn(m) -> ThinkingRule(obj(effort("low")), obj(effort("high")), true, fastOnlyLess = true)
            m.startsWith("gpt-4") -> NEVER
            else -> Thinking.UNKNOWN
        }
        "gemini" -> when {
            // 2.5 Pro 关不掉（none 报 400），最低 low；2.5 Flash 能关
            m.startsWith("gemini-2.5-pro") -> ThinkingRule(obj(effort("low")), obj(effort("high")), true, fastOnlyLess = true)
            m.startsWith("gemini-2.5-flash") && "lite" !in m -> ThinkingRule(obj(effort("none")), obj(effort("high")), true)
            else -> Thinking.UNKNOWN
        }
        "xai" -> when {
            "non-reasoning" in m -> NEVER
            // 最早的 grok-4 不接受 reasoning_effort；4.5 以后 low…xhigh（4.5 把 xhigh 当 high）
            m == "grok-4" || m.startsWith("grok-4-0709") || "-reasoning" in m -> ALWAYS
            Regex("^grok-4\\.\\d").containsMatchIn(m) -> ThinkingRule(obj(effort("low")), obj(effort("xhigh")), true, fastOnlyLess = true)
            else -> Thinking.UNKNOWN
        }
        // OpenRouter 统一的 reasoning 字段；一定要想的模型会拒绝 none（自动去掉重试）
        "openrouter" -> ThinkingRule(obj("reasoning" to obj("effort" to JsonPrimitive("none"))), obj("reasoning" to obj("effort" to JsonPrimitive("high"))), null)
        // 硅基流动：多数推理模型认 enable_thinking
        "siliconflow" -> flagSwitch(null)
        // Ollama 本机：none 一定安全；不会思考的模型要求思考会报 400（自动去掉）
        "ollama" -> ThinkingRule(obj(effort("none")), obj(effort("high")), null)
        "tokenhub" -> when {
            m.startsWith("hy3") || m.startsWith("hy4") -> typeSwitch(true)
            m.startsWith("deepseek-v4") || m.startsWith("deepseek/deepseek") -> typeSwitch(true, effort("max"))
            m.startsWith("glm-5.3") -> ThinkingRule(fast = obj(type("enabled"), effort("low")), deep = EMPTY, thinksByDefault = true, fastOnlyLess = true)
            m.startsWith("kimi-k3") -> ThinkingRule(fast = obj(effort("low")), deep = EMPTY, thinksByDefault = true, fastOnlyLess = true)
            else -> Thinking.UNKNOWN
        }
        "qianfan" -> when {
            m.startsWith("ernie-x1") -> ALWAYS
            "thinking" in m -> flagSwitch(true)
            else -> Thinking.UNKNOWN
        }
        "stepfun" -> when {
            m == "step-3.5-flash" -> ALWAYS
            m.startsWith("step-3.5-flash-") || m.startsWith("step-3.7") || m.startsWith("step-5") ->
                ThinkingRule(obj(effort("low")), obj(effort("high")), true, fastOnlyLess = true)
            else -> Thinking.UNKNOWN
        }
        "longcat" -> when {
            m.startsWith("longcat-2") -> typeSwitch(true)
            else -> Thinking.UNKNOWN
        }
        // 火山方舟豆包 Seed 2.x：默认开思考（强度 high，这个模型的最高有效档），thinking.type 能关；
        // 关的时候不能再带 reasoning_effort（只许 minimal），所以快速只发 disabled。注意 doubao-seedream 是画图模型
        "ark" -> when {
            m.startsWith("doubao-seed-") -> ThinkingRule(fast = obj(type("disabled")), deep = EMPTY, thinksByDefault = true)
            else -> Thinking.UNKNOWN
        }
        else -> Thinking.UNKNOWN
    }
}
