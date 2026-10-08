package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 记账：每调用一次模型、每画一张图记一条（engine/Ledger.kt 存在 usage\年-月.jsonl）。花费页按这个和价格表估算花了多少钱。
 * @param kind chat = 成员回答；compact / memory / addressing / stances = 记录员的后台活；image = 画图；test = 测试连通；other
 * @param platform 哪家平台（Thinking.platformOf，价格表按它查）
 * @param prompt 输入 token（含命中缓存的部分）
 * @param cached 其中命中缓存的
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
)

object UsageKinds {
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val TEST = "test"
    const val OTHER = "other"

    fun label(kind: String) = when (kind) {
        CHAT -> "成员回答"
        "compact" -> "压缩聊天"
        "memory" -> "挑长期记忆"
        "addressing" -> "点名判断"
        "stances" -> "立场档案"
        IMAGE -> "画图"
        TEST -> "测试连通"
        else -> "其他"
    }
}

/** 花费设置：美元折人民币的汇率；用户改过的单价（键见 Prices.key）。 */
@Serializable
data class CostSettings(
    val usdRate: Double = 7.1,
    val overrides: Map<String, ModelPrice> = emptyMap(),
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
)

/**
 * 一家服务商的余额（Command.GetBalances 的结果之一）。
 * @param supported 这家有能用 API Key 查余额的接口
 * @param amount 可用余额（查到才有）
 * @param text 给人看的：「可用 ¥12.34（其中赠送 ¥2.00）」或者查不了的原因
 * @param consoleUrl 查不了时去哪看
 * @param unofficial 用的是没写进公开文档的接口（可能哪天就不能用了）
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
)
