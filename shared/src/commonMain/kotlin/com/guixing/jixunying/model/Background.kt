package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/** 记录员在后台干的四样活。 */
@Serializable
enum class BgJob {
    /** 聊太长时把前面的聊天压成摘要。 */
    COMPACT,
    /** 从聊天里挑出要长期记住的事。 */
    MEMORY,
    /** 群里没 @、直接喊名字时，判断是在叫谁。 */
    ADDRESSING,
    /** 群聊每轮答完，记下各位的立场和改口。 */
    STANCES;

    val label: String get() = when (this) {
        COMPACT -> "压缩聊天"
        MEMORY -> "挑长期记忆"
        ADDRESSING -> "点名判断"
        STANCES -> "立场档案"
    }

    /** 出错了会怎样（让人知道要不要管它）。 */
    val consequence: String get() = when (this) {
        COMPACT -> "摘要没更新，这个对话发给模型的上下文会越来越长、越来越贵"
        MEMORY -> "这次没挑出要记的事，下次答完会再试"
        ADDRESSING -> "没认出你在叫谁，这次按对大家说处理了"
        STANCES -> "这一轮各位的立场没记上"
    }
}

/**
 * 后台活出错的记录：同一样活只留最近一次，成功一次就清掉。
 * 界面在 设置 → 记忆、立场档案页、侧栏「设置」上提示；弹提示半小时内同一样活只弹一次。
 */
@Serializable
data class BgProblem(
    val job: BgJob,
    /** 出错原因（给人看的，可能很长）。 */
    val reason: String,
    val at: Long,
    /** 哪个对话里出的错。 */
    val convTitle: String = "",
    /** 连续出错几次。 */
    val times: Int = 1,
) {
    /** 弹提示用的短原因：「API Key 不对或已失效（HTTP 401）」「网络连不上」。 */
    val shortReason: String get() = reason.substringBefore("：").take(60)

    fun noticeText(): String = buildString {
        append("记录员「").append(job.label).append("」出错")
        if (convTitle.isNotBlank()) append("（「").append(convTitle.take(16)).append("」）")
        append("：").append(shortReason).append("。").append(job.consequence).append("。")
        append("到 设置 → 记忆 可以看详情、换记录员。")
    }
}
