package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 狼人杀（集训场，docs/狼人杀设计说明.md）的数据：板子、座位、事件（带谁看得到）、私下想法、结果和指标。
 * 一局存一个文件 <数据目录>/wolf/<局编号>.json，界面和测评报告都从这里读。
 */
@Serializable
enum class WolfRole(val label: String, val wolf: Boolean = false, val god: Boolean = false) {
    WOLF("狼人", wolf = true),
    SEER("预言家", god = true),
    WITCH("女巫", god = true),
    GUARD("守卫", god = true),
    VILLAGER("平民"),
}

/** 板子（第一期两套，设计说明第 2 节）。 */
@Serializable
enum class WolfBoard(val label: String, val roles: List<WolfRole>) {
    SIX("6 人局：2 狼、预言家、女巫、2 平民", listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.VILLAGER, WolfRole.VILLAGER)),
    EIGHT("8 人局：2 狼、预言家、女巫、守卫、3 平民", listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.GUARD,
        WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.VILLAGER)),
}

/**
 * 一个座位。no 从 1 开始（桌上叫「3号」）。AI 座位 memberId 是 AI 成员编号；真人座位 human = true、name 是昵称。
 * modelLabel 只给界面和报告用，不告诉别的玩家（防看模型偏袒）。
 */
@Serializable
data class WolfSeat(
    val no: Int,
    val role: WolfRole,
    val memberId: String = "",
    val human: Boolean = false,
    val name: String = "",
    val modelLabel: String = "",
    val alive: Boolean = true,
    /** 这一局用到的模型编号（测评按它分）。 */
    val modelId: String = "",
)

/**
 * 发生的一件事。visibleTo 为空 = 所有人看得到（公开）；不为空 = 只有这几个座位看得到（狼队商量、验人结果……）。
 * kind：death 死讯 / speech 发言 / lastWords 遗言 / vote 投票 / exile 放逐 / peace 平安夜或平票 /
 *       wolfTalk 狼队提刀 / seerResult 验人 / witchInfo 女巫知道刀口 / note 法官说明 / end 结束
 */
@Serializable
data class WolfEvent(
    val day: Int,
    val night: Boolean,
    val kind: String,
    val actor: Int = 0,
    val target: Int = 0,
    val text: String = "",
    val visibleTo: List<Int> = emptyList(),
    val at: Long = 0,
)

/**
 * 私下的东西：只记录、不进任何玩家的记录（设计说明第 3 节）。
 * kind：think 每一步的私下想法 / judge 私下判断（stage = beforeSpeech / beforeVote）/ retry 输出不合法重问 / abstain 重问后还不行记弃权
 */
@Serializable
data class WolfNote(
    val day: Int,
    val seat: Int,
    val kind: String,
    val stage: String = "",
    val text: String = "",
    /** 私下判断：最怀疑几号（0 = 没说）、几成把握、每个人是狼的把握（座位号 → 0～100）。 */
    val most: Int = 0,
    val confidence: Int = 0,
    val probs: Map<Int, Int> = emptyMap(),
    val at: Long = 0,
)

/** 每个座位一局的指标（设计说明第 4 节）。主分是 won；其余是副分，只做分析。 */
@Serializable
data class WolfSeatMetric(
    val seat: Int,
    val won: Boolean,
    /** 好人：私下判断最怀疑的是真狼的次数 / 判断次数。 */
    val judgeHits: Int = 0,
    val judgeCount: Int = 0,
    /** 好人：投票前判断最怀疑的是狼，最后却投了好人、而且投的是多数票那个。 */
    val misled: Int = 0,
    /** 好人：投给好人的票数 / 投出的票数。 */
    val votesOnGood: Int = 0,
    val votesCast: Int = 0,
    /** 投了少数票而且投的是狼。 */
    val minorityRight: Int = 0,
    val abstains: Int = 0,
)

@Serializable
data class WolfGameRecord(
    val id: String,
    val board: WolfBoard,
    val seats: List<WolfSeat>,
    val events: List<WolfEvent> = emptyList(),
    val notes: List<WolfNote> = emptyList(),
    /** "good" / "wolf" / ""（没打完）。 */
    val winner: String = "",
    val endReason: String = "",
    val metrics: List<WolfSeatMetric> = emptyList(),
    /** 第一天发言前，私下判断里「最怀疑谁」有几种不同答案。 */
    val firstDivergence: Int = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    /** 出错没打完的原因（整局不算分）。 */
    val error: String = "",
    val calls: Int = 0,
    val day: Int = 0,
)
