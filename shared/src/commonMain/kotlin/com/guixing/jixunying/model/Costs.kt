package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 记账：每调用一次模型、每画一张图记一条（engine/Ledger.kt 存在 usage\年-月.jsonl）。花费页按这个和价格表估算花了多少钱。
 * @param kind chat = 成员回答；compact / memory / addressing / stances = 记录员的后台活；image = 画图；test = 测试连通；
 *   tts = 读出来、asr = 听写（语音，见 model/Speech.kt）；other
 * @param platform 哪家平台（Thinking.platformOf，价格表按它查）
 * @param prompt 输入 token（含命中缓存的部分）
 * @param cached 其中命中缓存的
 * @param units 语音按这个数量记：读出来是字符数、听写是秒数（价格表按 万字符 / 小时 / 秒 算）
 * @param unit 单位：SpeechUnits.CHAR 或 SECOND；不是语音时是空的
 * @param backfill 从以前的聊天记录补记的（那时没记后台活，也没记具体时段）
 */
@Serializable
data class UsageRecord(
    val at: Long,
    val kind: String,
    val providerId: String,
    val provider: String,
    val platform: String,
    val model: String,
    val prompt: Int = 0,
    val cached: Int = 0,
    val completion: Int = 0,
    val images: Int = 0,
    val memberId: String = "",
    val convId: String = "",
    val backfill: Boolean = false,
    val units: Double = 0.0,
    val unit: String = "",
    /** 在哪台设备上花的：空 = 本机；同步来的写对方的名字（「手机 samsung SM-S9480」「电脑 …」），2026-10-27。 */
    val origin: String = "",
)

object UsageKinds {
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val TEST = "test"
    const val OTHER = "other"
    /** Code 栏的编码循环（docs/Code栏设计说明.md 第 11 节）。 */
    const val CODE = "code"
    /** 读出来（TTS，1.7.0）。 */
    const val TTS = "tts"
    /** 听写（ASR，1.7.0）。 */
    const val ASR = "asr"
    /** 按意思搜（向量，1.7.0）。 */
    const val EMBED = "embed"
    /** 做视频（按秒，2026-10-20）。 */
    const val VIDEO = "video"

    fun label(kind: String) = when (kind) {
        CHAT -> "成员回答"
        CODE -> "Code 栏"
        "compact" -> "压缩聊天"
        "memory" -> "挑长期记忆"
        "addressing" -> "点名判断"
        "stances" -> "立场档案"
        "council" -> "会审（互评、汇总）"
        "wolf" -> "狼人杀"
        IMAGE -> "画图"
        VIDEO -> "做视频"
        TTS -> "读出来"
        ASR -> "听写"
        EMBED -> "按意思搜"
        TEST -> "测试连通"
        else -> "其他"
    }
}

/**
 * 花费设置：美元折人民币的汇率；用户改过的单价（键见 Prices.key）；手动填的余额（键是服务商 id）。
 * manual 只走 Command.SaveManualBalance 改：SaveCosts、SaveSettings 都保留原值（旧版手机不认识这块，发来的是空的）。
 */
@Serializable
data class CostSettings(
    /** 1 美元折多少人民币。usdAuto 开着时由引擎联网自动更新（engine/UsdRate，最多 12 小时一次）；7.1 只是还没取到时的初始值。 */
    val usdRate: Double = 7.1,
    /** 汇率自动更新（2026-10-14 用户要的，默认开）；用户手动填了汇率就关掉。 */
    val usdAuto: Boolean = true,
    /** 最近一次自动取到汇率的时间、从哪取的（界面显示「几点从哪取的」）。0 = 还没取到过。 */
    val usdRateAt: Long = 0,
    val usdRateFrom: String = "",
    /**
     * 1 美元换多少某种货币（键是 ISO 代码：CNY、HKD、SGD、GBP、EUR、JPY），和 usdRate 同一次取到（2026-10-24 用户要的换算：
     * 「不仅是美元，新币港币英镑也有汇率……互相之间也可以换算」）。空 = 还没取到。
     */
    val fx: Map<String, Double> = emptyMap(),
    val overrides: Map<String, ModelPrice> = emptyMap(),
    val manual: Map<String, ManualBalance> = emptyMap(),
)

/**
 * 手动填的余额（2026-10-12 用户要的：查不了余额的平台自己填，「手动输入也要计算」）。
 * 填的时候记下时间；之后按账本里这家的调用和价格表估算花了多少：先扣资源包 / 免费额度的 token，再扣钱（各家顺序见 BillingRules）。
 * @param amount 填的时候控制台上的可用余额
 * @param packTokens 资源包 / 免费额度还剩多少 token（会先扣这个；0 = 没有）
 */
@Serializable
data class ManualBalance(
    val amount: Double,
    val currency: String = "CNY",
    val at: Long,
    val packTokens: Long = 0,
)

/**
 * 花费页的一行（一位成员 / 一个模型 / 一种用途 / 一天）。人民币和美元分开记，显示时按汇率合计。
 * @param unpriced 价格表里没有这个模型的调用次数（没算进钱里）
 * @param noUsage 服务商没返回用量的调用次数（没算进钱里）
 */
@Serializable
data class CostLine(
    val key: String,
    val label: String,
    val sub: String = "",
    val cny: Double = 0.0,
    val usd: Double = 0.0,
    val calls: Int = 0,
    val images: Int = 0,
    val prompt: Long = 0,
    val cached: Long = 0,
    val completion: Long = 0,
    val unpriced: Int = 0,
    val noUsage: Int = 0,
    val priceText: String = "",
    /** 按模型分时：改单价用的键（Prices.key）；用户改过价时 overridden = true。 */
    val priceKey: String = "",
    val overridden: Boolean = false,
    /** 按模型分时：现在算钱用的单价（改单价的对话框拿它预填，币种也照它）。 */
    val price: ModelPrice? = null,
    /** 价格表里有官方价（没有的话「恢复官方价」要写成「清除自填单价」）。 */
    val hasOfficial: Boolean = false,
    /** 语音累计：读出来多少字、听写多少秒（同一行里记录的单位一样时相加；不是语音的行是 0 / 空）。 */
    val units: Double = 0.0,
    /** SpeechUnits.CHAR 或 SECOND；不是语音的行是空串。 */
    val unit: String = "",
) {
    fun total(usdRate: Double) = cny + usd * usdRate
}

/** 一段时间的花费（Command.CostReport 的结果）。 */
@Serializable
data class CostReport(
    /** today / week / month / all */
    val period: String,
    val from: Long,
    val total: CostLine,
    val byMember: List<CostLine> = emptyList(),
    val byModel: List<CostLine> = emptyList(),
    val byKind: List<CostLine> = emptyList(),
    /** 按天（北京时间），最近的在后面，最多 31 天。 */
    val daily: List<CostLine> = emptyList(),
    val usdRate: Double = 7.1,
    /** 账本里最早一条的时间；补记的老记录最晚到哪天（那之前没有记录员后台活的账）。 */
    val firstAt: Long = 0,
    val backfillUntil: Long = 0,
    /** 按设备（本机 / 同步来的别的设备），只有一台时是空的（2026-10-27）。 */
    val byDevice: List<CostLine> = emptyList(),
)

/**
 * 一家服务商的余额（Command.GetBalances 的结果之一）。
 * @param supported 这家有能用 API Key 查余额的接口
 * @param amount 可用余额（查到才有）
 * @param text 给人看的：「可用 ¥12.34（其中赠送 ¥2.00）」或者查不了的原因
 * @param consoleUrl 查不了时去哪看
 * @param unofficial 用的是没写进公开文档的接口（可能哪天就不能用了）
 * @param manual 这家手动填过余额（接口查不了时 amount / text 是按它估算的，estimated = true）
 */
@Serializable
data class BalanceInfo(
    val providerId: String,
    val provider: String,
    val supported: Boolean,
    val ok: Boolean = false,
    val amount: Double? = null,
    val currency: String = "CNY",
    val text: String = "",
    val consoleUrl: String = "",
    val unofficial: Boolean = false,
    val at: Long = 0,
    val manual: ManualBalance? = null,
    val estimated: Boolean = false,
)
