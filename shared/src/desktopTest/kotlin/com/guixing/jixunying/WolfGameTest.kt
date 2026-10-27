package com.guixing.jixunying

import com.guixing.jixunying.engine.wolf.WolfBrain
import com.guixing.jixunying.engine.wolf.WolfGame
import com.guixing.jixunying.engine.wolf.WolfParse
import com.guixing.jixunying.engine.wolf.WolfRules
import com.guixing.jixunying.model.WolfBoard
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfRole
import com.guixing.jixunying.model.WolfSeat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 狼人杀法官（docs/狼人杀设计说明.md）：用脚本当玩家，不联网。 */
class WolfGameTest {

    /** 这一步问的是什么（按法官的问法认）。 */
    private fun stepOf(user: String): String {
        val ask = user.substringAfterLast("【现在】")
        return when {
            "要刀谁" in ask || "狼队意见不一样" in ask -> "knife"
            "要守护谁" in ask -> "guard"
            "要查验谁" in ask -> "check"
            "你手里" in ask -> "witch"
            "轮到你发言" in ask -> "speak"
            "留遗言" in ask -> "lastWords"
            "说说你现在的判断" in ask -> "judge"
            "现在投票" in ask -> "vote"
            else -> error("认不出这一步：$ask")
        }
    }

    private fun record(board: WolfBoard, roles: List<WolfRole>) =
        WolfGameRecord("t", board, roles.mapIndexed { i, r -> WolfSeat(i + 1, r, "m${i + 1}", name = "玩家${i + 1}") })

    private fun target(n: Int) = "<想法>想好了</想法>{\"target\": $n, \"reason\": \"测试\"}"

    /**
     * 6 人局完整打一局：1、2 号狼，3 预言家，4 女巫，5、6 平民。
     * 狼每晚刀最小号的好人；女巫第一晚救、不毒；好人都投 1 号，狼投 5 号。
     * 第 1 夜刀 3 被救 → 第 1 天 1 号出局（4 票对 2 票）→ 第 2 夜刀 3 → 第 2 天好人投 2 → 好人赢。
     */
    @Test
    fun fullGameAndVisibility() = runBlocking {
        val prompts = java.util.Collections.synchronizedList(mutableListOf<Triple<Int, String, String>>())
        var r = record(WolfBoard.SIX, listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.VILLAGER, WolfRole.VILLAGER))
        val brain = WolfBrain { seat, system, user ->
            val step = stepOf(user)
            prompts += Triple(seat.no, step, system + "\n" + user)
            val aliveGood = r.seats.filter { it.alive && !it.role.wolf }.map { it.no }
            val aliveWolves = r.seats.filter { it.alive && it.role.wolf }.map { it.no }
            when (step) {
                "knife" -> target(aliveGood.min())
                "check" -> target(1)
                "witch" -> if ("1夜" in user.substringAfterLast("【现在】") || "第1夜" in user.substringAfterLast("【现在】")) "{\"save\": true, \"poison\": 0}" else "{\"save\": false, \"poison\": 0}"
                "speak" -> "<想法>我是${seat.role.label}</想法><发言>${seat.no}号发言：我觉得 ${aliveWolves.minOrNull()} 号可疑。</发言>"
                "lastWords" -> "<发言>我走了</发言>"
                "judge" -> "{\"probs\": {\"${aliveWolves.min()}\": 80}, \"most\": ${if (seat.role.wolf) aliveGood.min() else aliveWolves.min()}, \"confidence\": 70, \"why\": \"直觉\"}"
                "vote" -> target(if (seat.role.wolf) 5 else aliveWolves.min())
                else -> error(step)
            }
        }
        val game = WolfGame(r, { brain }, onUpdate = { r = it })
        val out = game.run()
        assertEquals("", out.error)
        assertEquals("good", out.winner, out.endReason)
        assertEquals(listOf(1, 2), out.events.filter { it.kind == "exile" }.map { it.target })
        assertFalse(out.seats.first { it.no == 3 }.alive, "第 2 夜 3 号被刀（解药第 1 夜用了）")
        // 第 1 夜救了：第 1 天是平安夜
        assertTrue(out.events.any { it.kind == "peace" && it.day == 1 && "平安夜" in it.text })

        // 看得到的：平民的记录里没有狼队商量、验人结果、女巫刀口
        for ((no, _, text) in prompts.filter { it.first in listOf(5, 6) }) {
            assertFalse("（狼队）" in text || "（你验人）" in text || "（法官告诉你）" in text, "$no 号看到了私密")
        }
        // 预言家看得到自己的验人；狼看得到队友的提刀
        assertTrue(prompts.any { it.first == 3 && "（你验人）1号是狼人" in it.third })
        assertTrue(prompts.any { it.first == 2 && "（狼队）1号想刀" in it.third })
        // 私下想法、私下判断不进任何人的记录
        assertFalse(prompts.any { "想好了" in it.third.substringAfter("【到现在你看得到的记录】") || "直觉" in it.third })
        // 同时投票：投票时看不到当天别人的票
        for ((_, step, text) in prompts.filter { it.second == "vote" }) {
            val day = Regex("【现在】第(\\d+)天").find(text)!!.groupValues[1]
            assertFalse(Regex("第${day}天 \\d+号投").containsMatchIn(text), "投票时看到了别人的票")
            assertEquals("vote", step)
        }
        // 指标：好人判断全对、没被带偏；首轮分歧度 = 最怀疑的不同答案数（好人都说 1 号，狼都说 3 号）
        val m5 = out.metrics.first { it.seat == 5 }
        assertTrue(m5.won && m5.judgeCount > 0 && m5.judgeHits == m5.judgeCount && m5.misled == 0, m5.toString())
        assertFalse(out.metrics.first { it.seat == 1 }.won)
        assertEquals(2, out.firstDivergence)
        assertTrue(out.calls > 20)
    }

    /** 乱答：先重问一次，还不行记弃权（票是 0），绝不随机代投；大家都弃票就平安。 */
    @Test
    fun invalidOutputIsRetriedThenAbstains() = runBlocking {
        var r = record(WolfBoard.SIX, listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.VILLAGER, WolfRole.VILLAGER))
        val brain = WolfBrain { seat, _, user ->
            when (stepOf(user)) {
                "knife" -> target(6)
                "check" -> target(1)
                "witch" -> "{\"save\": false, \"poison\": 0}"
                "speak" -> "就说两句"            // 没标签也算发言
                "lastWords" -> "再见"
                "judge" -> "我不知道"           // 看不懂：重问、弃权
                "vote" -> { if (seat.no == 5) "{\"target\": 9}" else target(0) } // 5 号投不存在的人
                else -> "?"
            }
        }
        val out = WolfGame(r, { brain }, onUpdate = { r = it }, maxDays = 1).run()
        val day1Votes = out.events.filter { it.kind == "vote" && it.day == 1 }
        assertEquals(0, day1Votes.first { it.actor == 5 }.target, "投不存在的人：重问后弃权")
        assertTrue(out.notes.any { it.seat == 5 && it.kind == "retry" && it.stage == "投票" })
        assertTrue(out.notes.any { it.seat == 5 && it.kind == "abstain" && it.stage == "投票" })
        assertTrue(out.events.any { it.kind == "peace" && "没人投票" in it.text })
        assertTrue(out.events.any { it.kind == "speech" && it.text == "就说两句" })
        assertTrue(out.notes.count { it.kind == "abstain" && it.stage == "判断" } >= 5)
        assertEquals("打满 1 天没分出胜负", out.endReason)
    }

    /** 8 人局：同守同救算死（奶穿）；平票没人出局。 */
    @Test
    fun guardWitchAndTie() = runBlocking {
        val roles = listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.GUARD, WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.VILLAGER)
        var r = record(WolfBoard.EIGHT, roles)
        val brain = WolfBrain { seat, _, user ->
            when (stepOf(user)) {
                "guard" -> target(6)
                "knife" -> target(6)
                "check" -> target(1)
                "witch" -> "{\"save\": true, \"poison\": 0}"
                "speak", "lastWords" -> "<发言>嗯</发言>"
                "judge" -> "{\"most\": 1, \"confidence\": 50}"
                // 第 1 天活 7 人：1、2、3 投 7，4、5、7 投 8，8 弃票 → 3 比 3 平票
                "vote" -> target(when (seat.no) { 1, 2, 3 -> 7; 4, 5, 7 -> 8; else -> 0 })
                else -> "?"
            }
        }
        val out = WolfGame(r, { brain }, onUpdate = { r = it }, maxDays = 1).run()
        assertTrue(out.events.any { it.kind == "death" && it.day == 1 && "6号" in it.text }, "同守同救：6 号还是死了")
        assertTrue(out.events.any { it.kind == "peace" && it.day == 1 && "平票" in it.text }, out.events.filter { it.day == 1 }.toString())
        assertTrue(out.seats.filter { it.no in listOf(7, 8) }.all { it.alive })
    }

    /** 女巫被刀：解药还在也不能救自己（乱答要救 → 重问后弃权），女巫死。 */
    @Test
    fun witchCannotSaveSelf() = runBlocking {
        var r = record(WolfBoard.SIX, listOf(WolfRole.WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.VILLAGER, WolfRole.VILLAGER))
        var witchSaw = ""
        val brain = WolfBrain { _, _, user ->
            when (stepOf(user)) {
                "knife" -> target(4)
                "check" -> target(1)
                "witch" -> { witchSaw = user.substringAfterLast("【现在】"); "{\"save\": true, \"poison\": 0}" }
                "speak", "lastWords" -> "<发言>嗯</发言>"
                // 10-27 真局写过带引号的「"2号"」
                "judge" -> "{\"most\": \"1号\"}"
                "vote" -> target(0)
                else -> "?"
            }
        }
        val out = WolfGame(r, { brain }, onUpdate = { r = it }, maxDays = 1).run()
        assertTrue(out.notes.any { it.kind == "judge" && it.most == 1 }, "\"1号\" 当成 1 号")
        assertTrue("不能用解药" in witchSaw && "解药 1 瓶" in witchSaw, witchSaw)
        assertFalse(out.seats.first { it.no == 4 }.alive)
        assertTrue(out.notes.any { it.seat == 4 && it.kind == "abstain" && it.stage == "用药" })
    }

    @Test
    fun parsingAndRules() {
        val (think, obj) = WolfParse.split("<think>xx</think><想法>先想想 {不是 JSON}</想法>好的：```json\n{\"target\": \"3号\", \"reason\": \"a\"}\n```")
        assertEquals("先想想 {不是 JSON}", think)
        assertNotNull(obj)
        assertEquals("\"3号\"", obj["target"].toString())
        // 真模型写过的不合法 JSON：值是「5号」
        val loose = WolfParse.split("{\"probs\": {\"1号\": 100, \"3号\": 30}, \"most\": 5号, \"confidence\": 20, \"why\": \"1号是队友\"}").second
        assertEquals("5", loose!!["most"].toString())
        assertEquals("\"1号是队友\"", loose["why"].toString())
        val seats = (1..6).map { WolfSeat(it, if (it <= 2) WolfRole.WOLF else if (it == 3) WolfRole.SEER else if (it == 4) WolfRole.WITCH else WolfRole.VILLAGER) }
        assertEquals(null, WolfRules.winner(seats))
        assertEquals("wolf", WolfRules.winner(seats.map { if (it.no in listOf(3, 4)) it.copy(alive = false) else it })!!.first, "神职死光")
        assertEquals("wolf", WolfRules.winner(seats.map { if (it.no in listOf(5, 6)) it.copy(alive = false) else it })!!.first, "平民死光")
        assertEquals("good", WolfRules.winner(seats.map { if (it.no <= 2) it.copy(alive = false) else it })!!.first)
    }
}
