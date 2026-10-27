package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.WolfBoard
import com.guixing.jixunying.model.WolfLive
import com.guixing.jixunying.model.WolfSeatPick
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 集训场界面开的局（2026-10-27）：真人座位挂出「这一步要做什么」，界面交了法官接着往下走。不联网（全是真人座位）。 */
class WolfLiveTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "jxy-wolflive-" + System.nanoTime())
    private var engine: Engine? = null

    @AfterTest
    fun cleanup() { engine?.close(); dir.deleteRecursively() }

    private suspend fun live(e: Engine): WolfLive? =
        e.call(Command.WolfCurrent).data.takeIf { it.isNotBlank() }?.let { AppJson.decodeFromString(WolfLive.serializer(), it) }

    @Test
    fun sixHumansPlayToTheEnd() = runBlocking<Unit> {
        val e = Engine(Storage(dir)).also { engine = it }
        val pushed = java.util.concurrent.atomic.AtomicInteger()
        e.addListener { if (it is Event.Wolf) pushed.incrementAndGet() }
        val start = e.call(Command.WolfStart(WolfBoard.SIX, (1..6).map { WolfSeatPick(humanName = "人$it") }))
        assertTrue(start.ok, start.message)
        // 开着一局时不许再开
        assertFalse(e.call(Command.WolfStart(WolfBoard.SIX, (1..6).map { WolfSeatPick(humanName = "人$it") })).ok)
        val kinds = mutableSetOf<String>()
        withTimeout(30_000) {
            while (true) {
                val l = live(e) ?: error("没有正在打的局")
                if (l.over) break
                val a = l.asks.firstOrNull()
                if (a == null) { delay(5); continue }
                kinds += a.kind + ":" + a.what
                val seats = l.record.seats
                val wolves = seats.filter { it.alive && it.role.wolf }.map { it.no }
                val good = seats.filter { it.alive && !it.role.wolf }.map { it.no }
                val me = seats.first { it.no == a.seat }
                val cmd = when (a.kind) {
                    "speech", "lastWords" -> Command.WolfAnswer(a.id, text = "${a.seat}号说话")
                    "witch" -> Command.WolfAnswer(a.id)
                    else -> when (a.what) {
                        "刀人" -> Command.WolfAnswer(a.id, target = good.min())
                        "投票" -> Command.WolfAnswer(a.id, target = if (me.role.wolf) 0 else wolves.filter { it in a.allowed }.minOrNull() ?: 0)
                        else -> Command.WolfAnswer(a.id, target = a.allowed.first())
                    }
                }
                assertTrue(e.call(cmd).ok)
                // 同一步交两次：第二次说「已经过了」
                assertFalse(e.call(cmd).ok)
            }
        }
        val l = live(e)!!
        assertTrue(l.over)
        assertTrue(l.record.winner in setOf("good", "wolf"), l.record.endReason + l.record.error)
        assertTrue(l.record.notes.isEmpty(), "推给界面的不带私下想法")
        assertTrue("speech:发言" in kinds && "target:投票" in kinds && "target:刀人" in kinds && "witch:用药" in kinds, kinds.toString())
        assertTrue(pushed.get() > 10)
        // 存盘的那份：真人不交私下判断
        val saved = AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.WolfGameRecord.serializer()),
            e.call(Command.WolfList).data)
        assertEquals(1, saved.size)
        assertTrue(saved[0].notes.none { it.kind == "judge" })
        assertTrue(saved[0].events.any { it.kind == "speech" && it.text.endsWith("号说话") })
    }

    @Test
    fun stopMidGame() = runBlocking<Unit> {
        val e = Engine(Storage(dir)).also { engine = it }
        assertTrue(e.call(Command.WolfStart(WolfBoard.SIX, (1..6).map { WolfSeatPick(humanName = "人$it") })).ok)
        withTimeout(5_000) { while (live(e)?.asks.isNullOrEmpty()) delay(5) }
        assertTrue(e.call(Command.WolfStop).ok)
        val l = live(e)!!
        assertTrue(l.over)
        assertEquals("中途停了", l.record.error)
        // 停了以后能再开
        assertTrue(e.call(Command.WolfStart(WolfBoard.EIGHT, (1..8).map { WolfSeatPick(humanName = "人$it") })).ok)
        assertEquals(8, live(e)!!.record.seats.size)
        e.call(Command.WolfStop)
    }
}
