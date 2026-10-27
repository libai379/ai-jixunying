package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.WolfBoard
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfLive
import com.guixing.jixunying.model.WolfPresets
import com.guixing.jixunying.model.WolfRole
import com.guixing.jixunying.model.WolfSeatPick
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 集训场界面开的局（2026-10-27）：真人座位挂出「这一步要做什么」，界面交了法官接着往下走；主持人模式（暂停、一步一步、插话）；
 * AI 调不通时问重试还是跳过；打完复盘、经验下局带上。AI 用 WolfFakeLlm（不联网）。
 */
class WolfLiveTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "jxy-wolflive-" + System.nanoTime())
    private val engines = mutableListOf<Engine>()
    private var fake: WolfFakeLlm? = null

    @AfterTest
    fun cleanup() { engines.forEach { it.close() }; fake?.stop(); dir.deleteRecursively() }

    private suspend fun live(e: Engine): WolfLive? =
        e.call(Command.WolfCurrent).data.takeIf { it.isNotBlank() }?.let { AppJson.decodeFromString(WolfLive.serializer(), it) }

    private suspend fun games(e: Engine) = AppJson.decodeFromString(ListSerializer(WolfGameRecord.serializer()), e.call(Command.WolfList).data)

    private fun aiEngine(names: List<String>, broken: Set<String> = emptySet()): Engine {
        val f = fake ?: WolfFakeLlm().also { fake = it }
        WolfFakeLlm.seed(dir, f.base, names, broken)
        return Engine(Storage(dir)).also { engines += it }
    }

    /** 真人按简单的打法交：狼刀第一个好人，好人投第一只狼，别的选第一个。 */
    private suspend fun playHumans(e: Engine, kinds: MutableSet<String> = mutableSetOf(), checkTwice: Boolean = false) {
        withTimeout(60_000) {
            while (true) {
                val l = live(e) ?: error("没有正在打的局")
                if (l.over && !l.reviewing) break
                val a = l.asks.firstOrNull { it.seat != 0 }
                if (a == null) { delay(5); continue }
                kinds += a.kind + ":" + a.what
                val seats = l.record.seats
                val wolves = seats.filter { it.alive && it.role.wolf }.map { it.no }
                val good = seats.filter { it.alive && !it.role.wolf }.map { it.no }
                val me = seats.first { it.no == a.seat }
                val cmd = when (a.kind) {
                    "speech", "lastWords" -> Command.WolfAnswer(a.id, text = "${a.seat}号说话")
                    "witch" -> Command.WolfAnswer(a.id)
                    "merchant" -> Command.WolfAnswer(a.id, target = 0)
                    else -> when (a.what) {
                        "刀人" -> Command.WolfAnswer(a.id, target = good.first { it in a.allowed })
                        "投票" -> Command.WolfAnswer(a.id, target = if (me.role.wolf) 0 else wolves.filter { it in a.allowed }.minOrNull() ?: 0)
                        else -> Command.WolfAnswer(a.id, target = a.allowed.firstOrNull() ?: 0)
                    }
                }
                assertTrue(e.call(cmd).ok)
                // 同一步交两次：第二次说「已经过了」
                if (checkTwice) assertFalse(e.call(cmd).ok)
            }
        }
    }

    @Test
    fun sixHumansPlayToTheEnd() = runBlocking<Unit> {
        val e = Engine(Storage(dir)).also { engines += it }
        val pushed = java.util.concurrent.atomic.AtomicInteger()
        e.addListener { if (it is Event.Wolf) pushed.incrementAndGet() }
        val start = e.call(Command.WolfStart(WolfBoard.SIX.roles, (1..6).map { WolfSeatPick(humanName = "人$it") }))
        assertTrue(start.ok, start.message)
        // 开着一局时不许再开
        assertFalse(e.call(Command.WolfStart(WolfBoard.SIX.roles, (1..6).map { WolfSeatPick(humanName = "人$it") })).ok)
        val kinds = mutableSetOf<String>()
        playHumans(e, kinds, checkTwice = true)
        val l = live(e)!!
        assertTrue(l.over)
        assertTrue(l.record.winner in setOf("good", "wolf"), l.record.endReason + l.record.error)
        assertTrue(l.record.notes.isEmpty(), "推给界面的不带私下想法")
        assertTrue("speech:发言" in kinds && "target:投票" in kinds && "target:刀人" in kinds && "witch:用药" in kinds, kinds.toString())
        assertTrue(pushed.get() > 10)
        // 存盘的那份：真人不交私下判断；全是真人不复盘
        val saved = games(e)
        assertEquals(1, saved.size)
        assertTrue(saved[0].notes.none { it.kind == "judge" })
        assertTrue(saved[0].events.any { it.kind == "speech" && it.text.endsWith("号说话") })
        assertTrue(saved[0].events.none { it.kind == "review" })
    }

    @Test
    fun stopMidGame() = runBlocking<Unit> {
        val e = Engine(Storage(dir)).also { engines += it }
        assertTrue(e.call(Command.WolfStart(WolfBoard.SIX.roles, (1..6).map { WolfSeatPick(humanName = "人$it") })).ok)
        withTimeout(5_000) { while (live(e)?.asks.isNullOrEmpty()) delay(5) }
        assertTrue(e.call(Command.WolfStop).ok)
        val l = live(e)!!
        assertTrue(l.over)
        assertEquals("中途停了", l.record.error)
        // 停了以后能再开；指定身份按指定
        assertTrue(e.call(Command.WolfStart(WolfBoard.EIGHT.roles, (1..8).map { WolfSeatPick(humanName = "人$it", role = if (it == 3) WolfRole.GUARD else null) })).ok)
        assertEquals(8, live(e)!!.record.seats.size)
        assertEquals(WolfRole.GUARD, live(e)!!.record.seats.first { it.no == 3 }.role)
        // 指定得比身份表多：不开
        e.call(Command.WolfStop)
        assertFalse(e.call(Command.WolfStart(WolfBoard.SIX.roles, (1..6).map { WolfSeatPick(humanName = "人$it", role = WolfRole.SEER) })).ok)
    }

    /** 主持人模式：一步一步点「下一步」，中途改自动、暂停、插话；打完每个 AI 说复盘感想，经验存下来，下一局带上。 */
    @Test
    fun hostModeWithReviewAndExperience() = runBlocking<Unit> {
        val names = listOf("阿德", "阿麦", "阿智", "阿米", "阿千", "阿珈", "阿苗")
        val e = aiEngine(names)
        val typed = java.util.concurrent.atomic.AtomicInteger()
        e.addListener { if (it is Event.WolfTyping && it.text.isNotEmpty()) typed.incrementAndGet() }
        val roles = WolfPresets.all.first { it.name == "经典标配" }.list.let { it.take(7) }
        assertTrue(e.call(Command.WolfStart(roles, names.indices.map { WolfSeatPick(memberId = "m$it") }, "经典标配", stepMode = true)).ok)
        // 一步一步：先点三下「下一步」
        var steps = 0
        withTimeout(20_000) {
            while (steps < 3) {
                val a = live(e)!!.asks.firstOrNull { it.kind == "next" }
                if (a == null) { delay(5); continue }
                assertEquals(0, a.seat)
                assertTrue(e.call(Command.WolfAnswer(a.id)).ok); steps++
            }
        }
        // 暂停：不再往下走（停在下一步之前）
        assertTrue(e.call(Command.WolfControl("pause")).ok)
        assertTrue(e.call(Command.WolfControl("auto")).ok)
        delay(300)
        val calls = live(e)!!.record.calls
        delay(500)
        assertEquals(calls, live(e)!!.record.calls, "暂停了还在调模型")
        assertTrue(live(e)!!.paused)
        // 主持人插话：记录里有，AI 之后看得到
        assertTrue(e.call(Command.WolfSay("3号你昨晚为什么不说话？")).ok)
        assertTrue(e.call(Command.WolfControl("resume")).ok)
        withTimeout(60_000) { while (live(e)?.let { it.over && !it.reviewing } != true) delay(10) }
        val r = games(e).first()
        assertEquals("", r.error)
        assertTrue(r.winner in setOf("good", "wolf"), r.endReason)
        assertTrue(r.events.any { it.kind == "host" && "3号你昨晚" in it.text })
        assertTrue(fake!!.systems.isNotEmpty())
        // 复盘：每个 AI 一句；经验存进 wolf/exp.json
        assertEquals(names.size, r.events.count { it.kind == "review" }, r.events.filter { it.kind == "review" }.toString())
        assertTrue(File(dir, "wolf/exp.json").readText().contains("先听完再表态"))
        assertTrue(r.notes.any { it.kind == "judge" }, "AI 交了私下判断")
        assertTrue(r.events.any { it.kind == "act" && it.visibleTo == listOf(0) }, "法官记录（只有主持人看得到）")
        assertTrue(typed.get() > 0, "发言边出边推")
        // 下一局：带上经验
        fake!!.systems.clear()
        assertTrue(e.call(Command.WolfStart(roles, names.indices.map { WolfSeatPick(memberId = "m$it") })).ok)
        withTimeout(60_000) { while (live(e)?.let { it.over && !it.reviewing } != true) delay(10) }
        assertTrue(fake!!.systems.any { "以前打狼人杀自己总结的经验" in it && "先听完再表态" in it })
        // 有真人的局不能以主持人身份插话
        e.call(Command.WolfStart(WolfBoard.SIX.roles, (1..6).map { WolfSeatPick(humanName = "人$it") }))
        assertFalse(e.call(Command.WolfSay("偷看")).ok)
        e.call(Command.WolfStop)
    }

    /** 1 个真人 + 5 个 AI 打完一局；某个 AI 调不通：问重试还是跳过，跳过就记弃权，局接着打。 */
    @Test
    fun humanWithAiAndBrokenAi() = runBlocking<Unit> {
        val names = listOf("阿德", "阿麦", "坏的", "阿米", "阿千")
        val e = aiEngine(names, broken = setOf("坏的"))
        val picks = listOf(WolfSeatPick(humanName = "我")) + names.indices.map { WolfSeatPick(memberId = "m$it") }
        assertTrue(e.call(Command.WolfStart(WolfBoard.SIX.roles, picks, judge = false)).ok)
        var errors = 0
        var retried = false
        withTimeout(60_000) {
            while (true) {
                val l = live(e)!!
                if (l.over && !l.reviewing) break
                val err = l.asks.firstOrNull { it.kind == "error" }
                if (err != null) {
                    errors++
                    assertTrue("坏的" in err.what, err.what)
                    // 第一次点重试（还是坏的，再问一次），之后都跳过
                    assertTrue(e.call(Command.WolfAnswer(err.id, text = if (!retried) "retry" else "skip")).ok)
                    retried = true
                    continue
                }
                val a = l.asks.firstOrNull { it.seat == 1 }
                if (a == null) { delay(5); continue }
                val cmd = when (a.kind) {
                    "speech", "lastWords" -> Command.WolfAnswer(a.id, text = "我是 1 号")
                    "witch" -> Command.WolfAnswer(a.id)
                    else -> Command.WolfAnswer(a.id, target = a.allowed.firstOrNull() ?: 0)
                }
                e.call(cmd)
            }
        }
        val r = games(e).first()
        assertEquals("", r.error)
        assertTrue(r.winner.isNotBlank(), r.endReason)
        assertTrue(errors >= 2, "出错问了主持人：$errors 次")
        // 身份随机：真人可能第一夜就被刀（只有遗言），发言、遗言都算
        assertTrue(r.events.any { (it.kind == "speech" || it.kind == "lastWords") && it.actor == 1 && it.text == "我是 1 号" }, r.events.filter { it.actor == 1 }.toString())
        assertTrue(r.events.any { it.kind == "review" }, "有 AI 就复盘")
        assertTrue(r.events.none { it.kind == "review" && it.actor == 1 }, "真人不复盘")
    }

    /** 14 种身份都上一遍（两局：功能多的自定义板子），AI 全打完、不出错。 */
    @Test
    fun allRolesPlayThrough() = runBlocking<Unit> {
        val names = (1..12).map { "AI$it" }
        val e = aiEngine(names)
        val boards = listOf(
            listOf(WolfRole.WOLF, WolfRole.WOLF_KING, WolfRole.WHITE_WOLF, WolfRole.SEER, WolfRole.WITCH, WolfRole.HUNTER, WolfRole.IDIOT, WolfRole.GUARD,
                WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.VILLAGER),
            listOf(WolfRole.WOLF, WolfRole.BEAUTY_WOLF, WolfRole.WOLF, WolfRole.SEER, WolfRole.KNIGHT, WolfRole.DREAMER, WolfRole.MERCHANT, WolfRole.THIEF,
                WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.VILLAGER, WolfRole.HUNTER),
        )
        for (roles in boards) {
            assertTrue(e.call(Command.WolfStart(roles, names.indices.map { WolfSeatPick(memberId = "m$it") }, judge = false, useExp = false)).ok)
            withTimeout(90_000) { while (live(e)?.let { it.over && !it.reviewing } != true) delay(10) }
            val r = games(e).first()
            assertEquals("", r.error, r.events.takeLast(5).toString())
            assertTrue(r.winner.isNotBlank(), r.endReason)
        }
        val all = games(e)
        // 盗贼复制过；商人送过护盾
        assertTrue(all.any { g -> g.seats.any { it.origRole == WolfRole.THIEF } })
        assertTrue(all.any { g -> g.events.any { it.kind == "act" && "奇迹商人" in it.text && "护盾" in it.text } })
    }
}
