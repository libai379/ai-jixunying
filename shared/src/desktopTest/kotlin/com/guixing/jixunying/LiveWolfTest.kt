package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.WolfBoard
import com.guixing.jixunying.model.WolfGameRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 真模型打一局狼人杀（docs/狼人杀设计说明.md）。只在设了 JXY_LIVE_WOLF=数据文件夹 时跑：复制那份数据的 state.json 用里面的服务商和成员。
 * JXY_LIVE_WOLF_SEATS=阿德,阿米,阿智,阿德,阿米,阿智（按座位，成员名字；默认就是这个）；JXY_LIVE_WOLF_OUT=写整局复盘的文件夹。
 * 花用户的钱：一局 6 人大约一百次调用，用便宜的模型。
 */
class LiveWolfTest {
    @Test
    fun oneGame() = runBlocking {
        val src = System.getenv("JXY_LIVE_WOLF") ?: return@runBlocking
        val outDir = File(System.getenv("JXY_LIVE_WOLF_OUT") ?: File(src).parent).apply { mkdirs() }
        val work = File(outDir, "engine").apply { deleteRecursively(); mkdirs() }
        File(src, "state.json").copyTo(File(work, "state.json"))
        val e = Engine(Storage(work))
        try {
            val names = (System.getenv("JXY_LIVE_WOLF_SEATS") ?: "阿德,阿米,阿智,阿德,阿米,阿智").split(",").map { it.trim() }
            val ids = names.map { n -> e.state.members.first { it.name == n }.id }
            val board = if (ids.size == 8) WolfBoard.EIGHT else WolfBoard.SIX
            val r = e.playWolf(board, ids, seed = System.getenv("JXY_LIVE_WOLF_SEED")?.toLongOrNull() ?: 7L) { g ->
                g.events.lastOrNull()?.let { ev -> if (ev.at > 0 && ev.kind != "note") println("[${ev.day}${if (ev.night) "夜" else "天"}] ${ev.kind} ${ev.text.take(120)}") }
            }
            File(outDir, "wolf-game.txt").writeText(replay(r), Charsets.UTF_8)
            println("结果：${r.winner} ${r.endReason}；调用 ${r.calls} 次；出错：${r.error}")
            assertEquals("", r.error)
        } finally {
            e.close()
        }
    }

    /** 整局复盘：座位、事件（带谁看得到）、私下想法和判断、指标。 */
    private fun replay(r: WolfGameRecord): String = buildString {
        appendLine("${r.board.label}  结果：${r.winner}（${r.endReason}）  调用 ${r.calls} 次  首轮分歧 ${r.firstDivergence}")
        r.seats.forEach { appendLine("${it.no}号 ${it.role.label} ${it.name} ${it.modelLabel} ${if (it.alive) "活" else "死"}") }
        appendLine()
        val all = (r.events.map { it.at to "事件 [${it.day}${if (it.night) "夜" else "天"}] ${it.kind} ${if (it.visibleTo.isEmpty()) "" else "（只给 ${it.visibleTo}）"}${it.text}" } +
            r.notes.map { it.at to "私下 [${it.day}] ${it.seat}号 ${it.kind}/${it.stage} most=${it.most} conf=${it.confidence} ${it.probs} ${it.text}" })
            .sortedBy { it.first }
        all.forEach { appendLine(it.second) }
        appendLine()
        r.metrics.forEach { appendLine(it.toString()) }
    }
}
