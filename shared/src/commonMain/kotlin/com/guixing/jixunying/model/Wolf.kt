package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 狼人杀（集训场，docs/狼人杀设计说明.md）的数据：身份、预设、座位、事件（带谁看得到）、私下想法、结果和指标。
 * 一局存一个文件 <数据目录>/wolf/<局编号>.json，界面和测评报告都从这里读。
 * 10-27 照第一代（G:\ai-discussion-app\src\wolf\roles.js）补齐 14 种身份和 8 套预设；旧记录里的五种身份名字没变，读得回来。
 */
@Serializable
enum class WolfRole(
    val label: String,
    val icon: String,
    /** wolf 狼人阵营 / good 好人阵营 / third 第三方（盗贼复制之前）。 */
    val faction: String,
    /** 神职（屠边算神）。 */
    val god: Boolean = false,
    val desc: String,
) {
    WOLF("普通狼", "🐺", "wolf", desc = "每晚和狼队一起刀一个人；白天藏好身份，发言时可以自爆（自己出局，直接进夜晚）。"),
    WOLF_KING("狼王", "👑", "wolf", desc = "狼人。出局时可以开枪带走一个人（被毒死、自爆不能开枪）。"),
    WHITE_WOLF("白狼王", "🤍", "wolf", desc = "狼人。白天发言时可以自爆并带走一个人，直接进夜晚。"),
    BEAUTY_WOLF("狼美人", "💋", "wolf", desc = "狼人。第一夜魅惑一个好人，狼美人出局时被魅惑的人一起出局。"),
    SEER("预言家", "🔮", "good", god = true, desc = "每晚查验一个人，知道他是好人还是狼人。"),
    WITCH("女巫", "🧪", "good", god = true, desc = "解药、毒药各一瓶，一晚最多用一瓶；知道当晚谁被刀；不能救自己。"),
    HUNTER("猎人", "🏹", "good", god = true, desc = "被狼刀死或被投票出局时可以开枪带走一个人（被毒死不能开枪）。"),
    IDIOT("白痴", "🤡", "good", god = true, desc = "被投票出局时翻牌免死，留在场上但以后不能投票；夜里被刀照样出局。"),
    GUARD("守卫", "🛡️", "good", god = true, desc = "每晚守护一个人不被狼刀，不能连着两晚守同一个人；同守同救也会死。"),
    KNIGHT("骑士", "⚔️", "good", god = true, desc = "白天发言时可以和一个人决斗（整局一次）：对方是狼就狼出局并进夜晚，是好人就骑士自己出局。"),
    DREAMER("摄梦人", "💤", "good", god = true, desc = "每晚让一个人梦游，他当晚不会被狼刀死；连着两晚选同一个人他会死；摄梦人夜里死了，梦游的人一起死。"),
    MERCHANT("奇迹商人", "🏪", "good", god = true, desc = "整局一次：夜里给一个人查验、护盾或毒药里的一样，他当晚就能用。"),
    THIEF("盗贼", "🃏", "third", desc = "第一夜盲选一个人，复制他的身份和阵营（看不到别人身份）；不选就当平民。"),
    VILLAGER("平民", "👤", "good", desc = "没有技能，靠发言和投票找狼。");

    val wolf: Boolean get() = faction == "wolf"
    /** 狼人那几种都能自爆（白狼王自爆还能带走一个）。 */
    val canExplode: Boolean get() = wolf
    /** 出局时能开枪的。 */
    val gunner: Boolean get() = this == HUNTER || this == WOLF_KING
}

/** 旧版的两套板子（10-26、10-27 早上的记录里存的是它），读旧记录用。 */
@Serializable
enum class WolfBoard(val label: String, val roles: List<WolfRole>) {
    SIX("6 人局：2 狼、预言家、女巫、2 平民", listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.VILLAGER, WolfRole.VILLAGER)),
    EIGHT("8 人局：2 狼、预言家、女巫、守卫、3 平民", listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.GUARD,
        WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.VILLAGER)),
}

/** 开局预设（照第一代的 8 套）。 */
data class WolfPreset(val name: String, val roles: Map<WolfRole, Int>) {
    val players: Int get() = roles.values.sum()
    val list: List<WolfRole> get() = WolfRole.entries.flatMap { r -> List(roles[r] ?: 0) { r } }
}

object WolfPresets {
    val all = listOf(
        WolfPreset("新手入门", mapOf(WolfRole.WOLF to 1, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.VILLAGER to 3)),
        WolfPreset("标准小局", mapOf(WolfRole.WOLF to 2, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.VILLAGER to 3)),
        WolfPreset("经典标配", mapOf(WolfRole.WOLF to 2, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.HUNTER to 1, WolfRole.VILLAGER to 3)),
        WolfPreset("均衡进阶", mapOf(WolfRole.WOLF to 2, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.HUNTER to 1, WolfRole.GUARD to 1, WolfRole.VILLAGER to 3)),
        WolfPreset("功能狼", mapOf(WolfRole.WOLF to 3, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.HUNTER to 1, WolfRole.GUARD to 1, WolfRole.VILLAGER to 3)),
        WolfPreset("竞技顶配", mapOf(WolfRole.WOLF to 4, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.HUNTER to 1, WolfRole.IDIOT to 1, WolfRole.VILLAGER to 4)),
        WolfPreset("狼王高配", mapOf(WolfRole.WOLF to 3, WolfRole.WOLF_KING to 1, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.HUNTER to 1, WolfRole.GUARD to 1, WolfRole.VILLAGER to 4)),
        WolfPreset("大桌经典", mapOf(WolfRole.WOLF to 4, WolfRole.WOLF_KING to 1, WolfRole.SEER to 1, WolfRole.WITCH to 1, WolfRole.HUNTER to 1, WolfRole.GUARD to 1,
            WolfRole.IDIOT to 1, WolfRole.VILLAGER to 5)),
    )

    /** 开局前的提醒（照第一代 checkBalance）：空 = 没问题。 */
    fun warnings(roles: List<WolfRole>): List<String> {
        val wolves = roles.count { it.wolf }
        val good = roles.count { it.faction == "good" }
        return buildList {
            if (roles.size < 5) add("至少要 5 个人才能开局")
            if (wolves == 0) add("没有狼人")
            else if (wolves >= good) add("狼人不少于好人，一开局狼就赢了")
        }
    }
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
    /** 还能不能投票（白痴翻牌后不能）。 */
    val canVote: Boolean = true,
    /** 白痴翻过牌（大家都知道他是白痴）。 */
    val revealed: Boolean = false,
    /** 盗贼复制前的身份（复制了才有）。 */
    val origRole: WolfRole? = null,
    val deathReason: String = "",
    val deathDay: Int = 0,
)

/**
 * 发生的一件事。visibleTo 为空 = 所有人看得到（公开）；不为空 = 只有这几个座位看得到（狼队商量、验人结果……）；
 * 里面只有 0 = 只有法官 / 主持人看得到（谁守了谁、谁被毒……打完复盘才给大家看）。
 * kind：death 死讯 / speech 发言 / pk PK 发言 / lastWords 遗言 / vote 投票 / exile 放逐 / peace 平安夜或平票 /
 *       wolfTalk 狼队提刀 / seerResult 验人 / witchInfo 女巫知道刀口 / act 夜里谁做了什么 / skill 白天的技能（自爆、决斗、开枪、翻牌）/
 *       host 主持人说的 / review 复盘感想 / note 法官说明 / end 结束
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
 * kind：think 每一步的私下想法 / judge 私下判断（stage = beforeSpeech / beforeVote）/ retry 输出不合法重问 / abstain 重问后还不行记弃权 / exp 局后总结的经验
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
    /** 旧记录用的板子；新的局看 roles。 */
    val board: WolfBoard? = null,
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
    /** 这局的身份（开局发的，按 WolfRole 顺序）。 */
    val roles: List<WolfRole> = emptyList(),
    /** 预设名字（自己配的是「自定义」）。 */
    val setupName: String = "",
    /** 现在在哪一步（给界面顶上那行看，比如「第 2 天 · 投票」）。 */
    val phase: String = "",
) {
    val roleList: List<WolfRole> get() = roles.ifEmpty { board?.roles ?: seats.map { it.origRole ?: it.role } }
    /** 「标准小局 · 7 人：2 狼、预言家、女巫、3 平民」。 */
    val label: String get() = (setupName.ifBlank { "${seats.size} 人局" }) + " · " + WolfText.roles(roleList)
    val hasHuman: Boolean get() = seats.any { it.human }
}

object WolfText {
    /** 「2 普通狼、预言家、女巫、3 平民」。 */
    fun roles(list: List<WolfRole>): String =
        WolfRole.entries.mapNotNull { r -> list.count { it == r }.takeIf { it > 0 }?.let { n -> (if (n > 1) "$n " else "") + r.label } }.joinToString("、")
}

/**
 * 法官要真人（或主持人）做的一步（集训场界面上填，2026-10-27）。
 * kind：speech 发言（pk = PK 发言；options 里是白天能顺带做的：explode 自爆 / whiteExplode 白狼王自爆带人 / duel 骑士决斗）/ lastWords 遗言 /
 *       target 选一个座位（what 是守护 / 刀人 / 查验 / 投票 / 开枪 / 魅惑 / 梦游 / 复制……）/ witch 用药 / merchant 奇迹商人送技能 /
 *       next 主持人一步一步时「下一步」/ error 某个 AI 调不通，问重试还是跳过。
 * seat = 0 是给主持人（不是哪个座位）。真人不交私下判断。
 */
@Serializable
data class WolfAsk(
    val id: String = "",
    val seat: Int = 0,
    val kind: String = "",
    val what: String = "",
    /** 给人看的一句：要做什么。 */
    val text: String = "",
    val allowed: List<Int> = emptyList(),
    /** 可以不选（守卫不守、弃票、不开枪）。 */
    val allowZero: Boolean = false,
    /** 女巫：今晚能不能用解药、能毒谁。 */
    val canSave: Boolean = false,
    val poisonable: List<Int> = emptyList(),
    /** 发言时能顺带做的技能（见上）。 */
    val options: List<String> = emptyList(),
)

/** 开局时一个座位坐谁：AI 成员（memberId）或真人（humanName）；role 不为空 = 手动指定身份。 */
@Serializable
data class WolfSeatPick(val memberId: String = "", val humanName: String = "", val role: WolfRole? = null)

/** 正在打的那一局（Event.Wolf 推给界面）：记录、等人做的事、哪几个 AI 正在想、主持人的节奏。 */
@Serializable
data class WolfLive(
    val record: WolfGameRecord,
    val asks: List<WolfAsk> = emptyList(),
    val thinking: List<Int> = emptyList(),
    /** 打完了或者被停了。 */
    val over: Boolean = false,
    /** 主持人点了暂停（当前这一步做完就停）。 */
    val paused: Boolean = false,
    /** 一步一步：每一步之前等主持人点「下一步」。 */
    val stepMode: Boolean = false,
    /** 打完以后 AI 正在说复盘感想。 */
    val reviewing: Boolean = false,
)
