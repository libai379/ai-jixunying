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
