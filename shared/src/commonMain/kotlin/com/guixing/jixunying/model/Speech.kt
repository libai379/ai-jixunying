package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 设置 → 语音（docs/向量和语音设计说明.md 第 3 节，2026-10-12）。
 * 服务商和 Key 都用用户已经加好的，这里只选「读出来 / 听写」用哪一家、哪个模型、什么音色。
 * 以后往这里加旧版不认识的新项，记得 Settings.SCHEMA 加一，并在 Engine 的 SaveSettings 里保留电脑上的那几项。
 */
@Serializable
data class SpeechSettings(
    /** 读出来用哪个服务商；留空 = 自动挑（小米 → MiniMax → 智谱 → 千问，见 SpeechPick）。 */
    val ttsProviderId: String = "",
    /** 留空 = 用这家的默认模型（SpeechModels.ttsDefault）。 */
    val ttsModel: String = "",
    /** 音色；留空 = 这个模型的默认音色（见 SpeechModels.defaultVoice）。MiniMax 的音色编号可以手填。 */
    val ttsVoice: String = "",
    /** 语速，1.0 = 原速。各家能接受的范围不一样（MiniMax 是 0.5～2），超出由服务商自己报错。 */
    val ttsSpeed: Double = 1.0,
    /** 每条回答答完自动读出来（默认关）。 */
    val autoRead: Boolean = false,
    /** 听写用哪个服务商；留空 = 自动挑（小米 → MiniMax → 智谱）。 */
    val asrProviderId: String = "",
    val asrModel: String = "",
)

/** 自动挑出来的一个语音模型（读出来或听写）。 */
data class SpeechChoice(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    /** 给人看的说明：谁家的、要不要钱。 */
    val note: String,
)

/**
 * 各家的语音模型和音色（2026-10-12 查的官方文档，原话摘在 docs/参考资料.md「向量和语音」一节）。
 * 模型名照官方原文写：小米 mimo-v2.5-tts / mimo-v2.5-asr、MiniMax speech-2.8-turbo / speech-2.8-hd / asr-1.0、
 * 智谱 glm-tts / glm-asr-2512、千问 qwen3-tts-flash（千问的听写第一期不接：官方 API 参考页 404）。
 */
object SpeechModels {
    /** 平台（Thinking.platformOf 的返回值）→ 默认的读出来模型。 */
    private val ttsDefaults = mapOf(
        "mimo" to "mimo-v2.5-tts",
        "minimax" to "speech-2.8-turbo",
        "zhipu" to "glm-tts",
        "qwen" to "qwen3-tts-flash",
    )

    /** 平台 → 默认的听写模型。千问不在里面：官方写「OpenAI 兼容」但 API 参考页 404，第一期不接。 */
    private val asrDefaults = mapOf(
        "mimo" to "mimo-v2.5-asr",
        "minimax" to "asr-1.0",
        "zhipu" to "glm-asr-2512",
    )

    /** 平台 → 这家能选的读出来模型（设置页照着列；第一个是默认）。 */
    private val ttsLists = mapOf(
        "mimo" to listOf("mimo-v2.5-tts"),
        "minimax" to listOf("speech-2.8-turbo", "speech-2.8-hd"),
        "zhipu" to listOf("glm-tts"),
        "qwen" to listOf("qwen3-tts-flash"),
    )

    /** 这家的语音模型有哪些（设置 → 语音 里列出来给用户挑）。 */
    fun ttsListOf(p: ProviderConfig): List<String> = ttsLists[Thinking.platformOf(p)].orEmpty()

    fun asrModelOf(p: ProviderConfig): String? = asrDefaultOf(p)

    /**
     * 这家的读出来接口认不认「语速」：MiniMax（voice_setting.speed）和智谱（speed）认；
     * 小米走 chat/completions、千问的接口里都没有这个字段，所以不传、设置页里把滑块灰掉
     * （出处 docs/参考资料.md「向量和语音」；engine/Speech.kt 也是只给这两家传 speed）。
     */
    fun supportsSpeed(platform: String) = platform == "minimax" || platform == "zhipu"

    /** 这位服务商有没有接读出来 / 听写。 */
    fun supportsTts(p: ProviderConfig) = Thinking.platformOf(p) in ttsDefaults

    fun supportsAsr(p: ProviderConfig) = Thinking.platformOf(p) in asrDefaults

    fun ttsDefault(platform: String) = ttsDefaults[platform]

    fun asrDefault(platform: String) = asrDefaults[platform]

    fun ttsDefaultOf(p: ProviderConfig) = ttsDefault(Thinking.platformOf(p))

    fun asrDefaultOf(p: ProviderConfig) = asrDefault(Thinking.platformOf(p))

    /**
     * 读出来的可选音色（设置页照着列；不在表里的也能手填）。
     * 小米九个、智谱七个都是官方文档里逐个列出来的；MiniMax 官方只给了 male-qn-qingse 这一个例子，
     * 千问官方只给了 Cherry，所以这两家不列全，让用户按控制台里的编号手填。
     */
    fun voices(model: String): List<Pair<String, String>> {
        val m = model.trim().lowercase()
        return when {
            m.startsWith("mimo") -> listOf(
                "mimo_default" to "默认", "冰糖" to "冰糖", "茉莉" to "茉莉", "苏打" to "苏打", "白桦" to "白桦",
                "Mia" to "Mia", "Chloe" to "Chloe", "Milo" to "Milo", "Dean" to "Dean",
            )
            m.startsWith("glm-tts") -> listOf(
                "tongtong" to "tongtong（默认）", "chuichui" to "chuichui", "xiaochen" to "xiaochen", "jam" to "jam",
                "kazi" to "kazi", "douji" to "douji", "luodo" to "luodo",
            )
            m.startsWith("speech-") -> listOf("male-qn-qingse" to "male-qn-qingse（官方例子，其他编号手填）")
            m.startsWith("qwen3-tts") -> listOf("Cherry" to "Cherry（官方例子，其他编号手填）")
            else -> emptyList()
        }
    }

    /** 没填音色时用什么。MiniMax、千问官方只给了例子，先按例子填（用户能在设置页改）。 */
    fun defaultVoice(model: String): String {
        val m = model.trim().lowercase()
        return when {
            m.startsWith("mimo") -> "mimo_default"
            m.startsWith("glm-tts") -> "tongtong"
            m.startsWith("speech-") -> "male-qn-qingse"
            m.startsWith("qwen3-tts") -> "Cherry"
            else -> ""
        }
    }
}

/**
 * 记账用的单位（UsageRecord.unit）：读出来按字符、听写按秒，价格表据此算钱
 * （ModelPrice.perChar / perHour / perSecond）。
 */
object SpeechUnits {
    const val CHAR = "char"
    const val SECOND = "second"

    /**
     * 读出来这次算多少「字符」。MiniMax 的算法和别家不一样：1 个汉字算 2 个字符，
     * 英文字母、希腊字母、标点符号、特殊符号、空格、回车等各算 1 个（出处 docs/参考资料.md「读出来」MiniMax 那条）；
     * 其他家按字数（一个字符算一个，代理对不拆成两个）。
     */
    fun ttsChars(platform: String, text: String): Double {
        if (platform != "minimax") return count(text).toDouble()
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            n += if (isHan(c)) 2 else 1
            i += if (c.isHighSurrogate()) 2 else 1
        }
        return n.toDouble()
    }

    private fun count(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) { i += if (text[i].isHighSurrogate()) 2 else 1; n++ }
        return n
    }

    /** 汉字（含扩展 A 区和兼容区）。够 MiniMax 的算法用；日文假名等其他文字按 1 个字符算。 */
    private fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿'
}
