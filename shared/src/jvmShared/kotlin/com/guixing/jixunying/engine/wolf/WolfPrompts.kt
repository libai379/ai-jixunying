package com.guixing.jixunying.engine.wolf

import com.guixing.jixunying.model.WolfEvent
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfText
import com.guixing.jixunying.model.WolfRole
import com.guixing.jixunying.model.WolfSeat
import com.guixing.jixunying.model.WolfSeatMetric

/**
 * 给 AI 座位看的东西（设计说明第 3 节）：system = 规则 + 你是谁；user = 你看得到的记录 + 这一步要做什么。
 * 别的玩家只用座位号，不说背后是哪个模型、是不是真人（防看名字偏袒）。私下想法、私下判断一律不进。
 */
object WolfPrompts {

    fun system(r: WolfGameRecord, me: WolfSeat): String = buildString {
        val roles = r.roleList.toSet()
        appendLine("你在玩一局狼人杀，你是 ${me.no}号。法官是程序。")
        appendLine()
        appendLine("【这局的规则】")
        appendLine("- ${r.seats.size} 个人，座位 1～${r.seats.size} 号。身份：${WolfText.roles(r.roleList)}。")
        appendLine("- 胜负：狼人全部出局，好人赢；神职全部出局或平民全部出局，狼人赢（屠边）；活着的狼人数不少于好人数，狼人也赢。")
        appendLine("- 夜里：有技能的人按顺序行动，天亮一起结算，只公布谁死了、不公布怎么死的。")
        appendLine("- 白天：公布昨晚死讯（只有第一夜死的有遗言）→ 活着的人按顺序各发言一次（每天换起点）→ 所有人同时投票，投完一起公布 → " +
            "票最多的出局并留遗言；平票的几个人各 PK 发言一次，其他人在他们里面再投一次，还平就当天没人出局。")
        appendLine()
        appendLine("【这局有的身份】")
        for (role in WolfRole.entries) if (role in roles) appendLine("- ${role.label}：${role.desc}")
        appendLine()
        appendLine("【你的身份】${me.role.label}" + (me.origRole?.let { "（你本来是${it.label}，第一夜复制成了${me.role.label}）" } ?: ""))
        appendLine(me.role.desc)
        if (me.role.wolf) {
            val mates = r.seats.filter { it.role.wolf && it.no != me.no }
            appendLine("你的狼队友：${mates.joinToString("、") { "${it.no}号" + if (!it.alive) "（已出局）" else "" }.ifEmpty { "没有" }}。你们的目标是屠边，白天要藏好身份。")
        }
        if (!me.alive) appendLine("你已经出局了。")
        appendLine()
        append("别的玩家可能是 AI，也可能是真人，你只知道他们的座位号。按你的身份尽力让自己的阵营赢。")
    }

    private fun line(e: WolfEvent, god: Boolean): String {
        val time = if (e.day == 0) "" else "第${e.day}${if (e.night) "夜" else "天"} "
        val who = if (e.actor > 0) "${e.actor}号" else ""
        return when (e.kind) {
            "speech" -> "$time$who 发言：${e.text}"
            "pk" -> "$time$who PK 发言：${e.text}"
            "lastWords" -> "$time$who 遗言：${e.text}"
            "vote" -> "$time${e.text}"
            "wolfTalk" -> "$time（狼队）${e.text}"
            "seerResult" -> "$time（查验结果）${e.text}"
            "witchInfo" -> "$time（法官告诉你）${e.text}"
            "act" -> "$time" + (if (god || 0 in e.visibleTo) "（法官记录）" else "（只有你知道）") + e.text
            "host" -> "${time}主持人说：${e.text}"
            "review" -> "复盘 $who：${e.text}"
            else -> "$time${e.text}"
        }
    }

    fun user(r: WolfGameRecord, me: WolfSeat, day: Int, night: Boolean, ask: String): String = buildString {
        appendLine("【到现在你看得到的记录】")
        val seen = r.events.filter { it.visibleTo.isEmpty() || me.no in it.visibleTo }
        if (seen.isEmpty()) appendLine("（还没有）")
        for (e in seen) appendLine(line(e, false))
        appendLine()
        val revealed = r.seats.filter { it.revealed }
        appendLine("【现在】第$day${if (night) "夜" else "天"}。活着的：${r.seats.filter { it.alive }.joinToString("、") { "${it.no}号" }}。" +
            (if (revealed.isNotEmpty()) "翻过牌的白痴（不能投票）：${revealed.joinToString("、") { "${it.no}号" }}。" else "") +
            (if (!me.alive) "你已经出局。" else ""))
        appendLine()
        append(ask)
    }

    /** 整局记录，夜里谁做了什么也在（复盘用）。 */
    fun fullLog(r: WolfGameRecord): String = r.events.filter { it.kind != "review" }.joinToString("\n") { line(it, true) }
}

/** 局后指标（设计说明第 4 节）。只看记录，不调模型。 */
object WolfMetrics {

    fun of(r: WolfGameRecord): List<WolfSeatMetric> {
        if (r.winner.isBlank()) return emptyList()
        val roleOf = r.seats.associate { it.no to it.role }
        val votesByDay = r.events.filter { it.kind == "vote" }.groupBy { it.day }
        val topByDay = votesByDay.mapValues { (_, vs) ->
            val counts = vs.filter { it.target != 0 }.groupingBy { it.target }.eachCount()
            val top = counts.maxOfOrNull { it.value } ?: 0
            counts.filter { it.value == top }.keys to counts
        }
        return r.seats.map { s ->
            val good = !s.role.wolf
            val judges = r.notes.filter { it.seat == s.no && it.kind == "judge" && it.most != 0 }
            val myVotes = r.events.filter { it.kind == "vote" && it.actor == s.no && it.target != 0 }
            var misled = 0
            var minority = 0
            if (good) for (v in myVotes) {
                val (leaders, counts) = topByDay[v.day] ?: continue
                val pre = judges.lastOrNull { it.day == v.day && it.stage == "beforeVote" }
                val targetGood = roleOf[v.target]?.wolf == false
                if (pre != null && roleOf[pre.most]?.wolf == true && targetGood && v.target in leaders) misled++
                if (roleOf[v.target]?.wolf == true && (counts[v.target] ?: 0) < (counts.values.maxOrNull() ?: 0)) minority++
            }
            WolfSeatMetric(
                seat = s.no,
                won = (r.winner == "wolf") == s.role.wolf,
                judgeHits = if (good) judges.count { roleOf[it.most]?.wolf == true } else 0,
                judgeCount = if (good) judges.size else 0,
                misled = misled,
                votesOnGood = if (good) myVotes.count { roleOf[it.target]?.wolf == false } else 0,
                votesCast = myVotes.size,
                minorityRight = minority,
                abstains = r.notes.count { it.seat == s.no && it.kind == "abstain" },
            )
        }
    }

    fun firstDivergence(r: WolfGameRecord): Int =
        r.notes.filter { it.day == 1 && it.kind == "judge" && it.stage == "beforeSpeech" && it.most != 0 }.map { it.most }.distinct().size
}
