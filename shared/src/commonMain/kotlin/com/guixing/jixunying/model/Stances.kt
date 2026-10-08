package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * 立场档案：群聊里每个「有立场可比」的问题（议题），各位成员一开始怎么说、后来有没有改口、为什么改口。
 * 判断立场和改口原因的是记录员（模型自己理解，不靠关键词）；对错由用户来标。
 * 存在 stances.json，不放进 AppState（会越攒越多，而且要推给手机）；界面用 StanceList 指令取。
 */
@Serializable
data class StanceTopic(
    val id: String,
    val convId: String,
    /** 提出问题的那条用户消息。 */
    val askId: String = "",
    /** 记录员概括的问题。 */
    val question: String,
    val options: List<StanceOption> = emptyList(),
    /** 时间线：每位成员每次表态，按时间先后。 */
    val entries: List<StanceEntry> = emptyList(),
    /** 用户标的对错：某个立场的 key = 它对；NONE = 都不对；OPEN = 没有对错（观点题）；空 = 还没标。 */
    val verdict: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun option(key: String) = options.firstOrNull { it.key == key }

    /** 某位成员在某个时间点之前最后一次表态。 */
    fun latestOf(memberId: String, before: Long = Long.MAX_VALUE) = entries.lastOrNull { it.memberId == memberId && it.time < before }

    companion object {
        const val NONE = "none"
        const val OPEN = "open"
        const val UNCLEAR = "?"
    }
}

@Serializable
data class StanceOption(val key: String, val text: String)

@Serializable
data class StanceEntry(
    val memberId: String,
    val messageId: String,
    /** 哪一轮（引发这一轮的用户消息）。同一轮里大家差不多同时开口，比「之前别人怎么说」要按轮比，不能按毫秒比。 */
    val askId: String = "",
    /** 立场的 key；"?" = 没给出明确结论。 */
    val option: String,
    /** 独立作答时的首答（这一轮看不到别人的回答）。 */
    val first: Boolean = false,
    /** 跟自己上一次比：坚持 / 被说服 / 跟风 / 迎合用户 / 自己查证；首答是空的。 */
    val why: String = "",
    /** 是谁让他改的（成员 id），说不清就空着。 */
    val by: String = "",
    /** 记录员的一句话说明。 */
    val reason: String = "",
    /** 这一轮用户对某个立场表示过怀疑或反对。 */
    val doubted: Boolean = false,
    val time: Long = 0,
)

/** 一位成员的立场卡。 */
data class StanceCard(
    val memberId: String,
    /** 参与过的议题。 */
    val topics: Int = 0,
    /** 有独立首答、而且用户标了对错的议题。 */
    val judged: Int = 0,
    /** 其中首答就对的。 */
    val firstRight: Int = 0,
    /** 有人跟他意见不同（或者用户表示怀疑）之后，他又表态的次数。 */
    val challenged: Int = 0,
    /** 其中坚持原来立场的。 */
    val held: Int = 0,
    val follow: Int = 0,
    val persuaded: Int = 0,
    val pleaseUser: Int = 0,
    val selfCheck: Int = 0,
    /** 本来对，后来改错了。 */
    val misled: Int = 0,
    /** 本来错，后来改对了。 */
    val corrected: Int = 0,
    /** 别人改成了他的立场，而且记录员认为是他说服的。 */
    val convinced: Int = 0,
) {
    val changed: Int get() = follow + persuaded + pleaseUser + selfCheck
}

object Stances {
    const val HOLD = "坚持"
    const val PERSUADED = "被说服"
    const val FOLLOW = "跟风"
    const val PLEASE_USER = "迎合用户"
    const val SELF_CHECK = "自己查证"
    val reasons = listOf(HOLD, PERSUADED, FOLLOW, PLEASE_USER, SELF_CHECK)

    /** 给人看的解释。 */
    fun explain(why: String) = when (why) {
        HOLD -> "被质疑后还是原来的看法"
        PERSUADED -> "别的成员拿出了新证据或有说服力的理由，才改的"
        FOLLOW -> "看到别人（尤其是多数人）这么说就改了，没有新证据"
        PLEASE_USER -> "你表示怀疑或说了自己的看法后就改了，没有新证据"
        SELF_CHECK -> "自己搜索、计算、查资料后改的"
        else -> why
    }

    /** 每位成员的立场卡（按传入的成员顺序）。 */
    fun cards(topics: List<StanceTopic>, memberIds: List<String>): List<StanceCard> =
        memberIds.map { id -> card(topics, id) }

    fun card(topics: List<StanceTopic>, memberId: String): StanceCard {
        var c = StanceCard(memberId)
        for (t in topics) {
            val timeline = t.entries.sortedBy { it.time }
            val mine = timeline.filter { it.memberId == memberId }
            // 说服别人：别人改到某个立场，记录员说是他让改的
            c = c.copy(convinced = c.convinced + t.entries.count { it.by == memberId && it.memberId != memberId && it.why in setOf(PERSUADED, FOLLOW) })
            if (mine.isEmpty()) continue
            c = c.copy(topics = c.topics + 1)
            for ((i, e) in mine.withIndex()) {
                if (i == 0 && e.first) continue
                val prev = mine.getOrNull(i - 1) ?: continue
                // 这一轮开口之前，别人的立场跟他不一样（同一轮的不算：大家差不多同时开口）
                val othersDiffer = timeline.map { it.memberId }.distinct().filter { it != memberId }.any { other ->
                    val o = timeline.lastOrNull { it.memberId == other && it.time < e.time && (e.askId.isEmpty() || it.askId != e.askId) }?.option
                    o != null && o != StanceTopic.UNCLEAR && prev.option != StanceTopic.UNCLEAR && o != prev.option
                }
                val challenged = othersDiffer || e.doubted || e.why == PLEASE_USER
                val kept = e.option == prev.option || e.why == HOLD
                c = c.copy(
                    challenged = c.challenged + if (challenged) 1 else 0,
                    held = c.held + if (challenged && kept) 1 else 0,
                    follow = c.follow + if (!kept && e.why == FOLLOW) 1 else 0,
                    persuaded = c.persuaded + if (!kept && e.why == PERSUADED) 1 else 0,
                    pleaseUser = c.pleaseUser + if (!kept && e.why == PLEASE_USER) 1 else 0,
                    selfCheck = c.selfCheck + if (!kept && e.why == SELF_CHECK) 1 else 0,
                )
            }
            val v = t.verdict
            val first = mine.firstOrNull { it.first }
            if (first != null && v.isNotEmpty() && v != StanceTopic.OPEN) {
                val last = mine.last()
                val firstOk = first.option == v
                val lastOk = last.option == v
                c = c.copy(
                    judged = c.judged + 1,
                    firstRight = c.firstRight + if (firstOk) 1 else 0,
                    misled = c.misled + if (firstOk && !lastOk) 1 else 0,
                    corrected = c.corrected + if (!firstOk && first.option != StanceTopic.UNCLEAR && lastOk) 1 else 0,
                )
            }
        }
        return c
    }

    /** 百分比文字；分母是 0 时显示「—」。 */
    fun percent(n: Int, d: Int) = if (d <= 0) "—" else "${(n * 100 + d / 2) / d}%"
}
