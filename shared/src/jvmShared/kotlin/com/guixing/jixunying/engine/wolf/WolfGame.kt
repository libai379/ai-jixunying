package com.guixing.jixunying.engine.wolf

import com.guixing.jixunying.engine.Json
import com.guixing.jixunying.model.WolfEvent
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfNote
import com.guixing.jixunying.model.WolfRole
import com.guixing.jixunying.model.WolfSeat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * 一个座位背后是谁在想：AI（LlmBrain，问模型）或真人（界面填，第二期）。
 * system 是这个座位的身份和规则，user 是它看得到的记录 + 这一步要它做什么；回原文，程序自己解析。
 */
fun interface WolfBrain {
    suspend fun ask(seat: WolfSeat, system: String, user: String): String
}

/**
 * 狼人杀的法官（程序，不是 AI）：按 docs/狼人杀设计说明.md 第 2、3 节的规则一步一步问各个座位，记事件和私下想法，打完算指标。
 * 每一步都把记录交给 onUpdate（存盘、推界面）。
 */
class WolfGame(
    start: WolfGameRecord,
    private val brainOf: (WolfSeat) -> WolfBrain,
    private val now: () -> Long = System::currentTimeMillis,
    private val onUpdate: (WolfGameRecord) -> Unit = {},
    private val maxDays: Int = 12,
) {
    @Volatile var record = start
        private set

    private var lastGuard = 0
    private var antidote = true
    private var poison = true

    private val seats get() = record.seats
    private fun seat(no: Int) = seats.first { it.no == no }
    private fun alive() = seats.filter { it.alive }
    private fun aliveNos() = alive().map { it.no }

    // 私下判断、投票是几个座位同时问的（互相看不到，设计说明第 3 节），改记录要排队
    private val lock = Any()
    private fun change(f: (WolfGameRecord) -> WolfGameRecord) = synchronized(lock) { record = f(record); onUpdate(record) }
    private fun emit(e: WolfEvent) = change { it.copy(events = it.events + e.copy(at = now())) }
    private fun note(n: WolfNote) = change { it.copy(notes = it.notes + n.copy(at = now())) }

    suspend fun run(): WolfGameRecord {
        record = record.copy(startedAt = record.startedAt.takeIf { it > 0 } ?: now())
        emit(WolfEvent(0, false, "note", text = "游戏开始：${record.board.label}。座位 1～${seats.size} 号。"))
        try {
            for (day in 1..maxDays) {
                record = record.copy(day = day)
                night(day)
                if (finished(day)) break
                daytime(day)
                if (finished(day)) break
                if (day == maxDays) end(day, "", "打满 $maxDays 天没分出胜负")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            record = record.copy(error = e.message ?: e.toString(), endedAt = now())
            onUpdate(record)
            return record
        }
        record = record.copy(metrics = WolfMetrics.of(record), firstDivergence = WolfMetrics.firstDivergence(record))
        onUpdate(record)
        return record
    }

    // ———————————————— 夜里 ————————————————

    private suspend fun night(day: Int) {
        emit(WolfEvent(day, true, "note", text = "第 $day 夜，天黑请闭眼。"))
        // 守卫
        var guarded = 0
        alive().firstOrNull { it.role == WolfRole.GUARD }?.let { g ->
            val allowed = aliveNos().filter { it != lastGuard }
            guarded = pickTarget(g, day, true, "守护",
                "今晚你要守护谁？被守护的人今晚不会被狼刀死。不能连续两晚守同一个人（昨晚守的是 ${if (lastGuard == 0) "没有" else "${lastGuard}号"}），可以守自己。" +
                    "也可以不守（target 写 0）。", allowed, allowZero = true)
            lastGuard = guarded
        }
        // 狼人：各自提，不一样再商量一次，还不一样按第一只活狼的
        val wolves = alive().filter { it.role.wolf }
        val wolfNos = wolves.map { it.no }
        val targets = aliveNos().filter { it !in wolfNos }
        var knife = 0
        if (wolves.isNotEmpty() && targets.isNotEmpty()) {
            val first = wolves.map { w ->
                w.no to pickTarget(w, day, true, "刀人",
                    "今晚狼队要刀谁？你的狼队友是：${wolfNos.filter { it != w.no }.joinToString("、") { "${it}号" }.ifEmpty { "（只剩你自己）" }}。" +
                        "在 reason 里简单说理由，队友能看到。", targets, allowZero = false, wolfTalk = true)
            }
            val picks = first.map { it.second }.filter { it != 0 }
            knife = if (picks.distinct().size <= 1) picks.firstOrNull() ?: 0 else {
                val second = wolves.map { w ->
                    pickTarget(w, day, true, "刀人",
                        "狼队意见不一样：" + first.joinToString("；") { (n, t) -> "${n}号想刀 ${if (t == 0) "没说" else "${t}号"}" } +
                            "。再定一次，尽量统一。", targets, allowZero = false, wolfTalk = true)
                }.filter { it != 0 }
                if (second.distinct().size == 1) second.first() else second.firstOrNull() ?: picks.first()
            }
            emit(WolfEvent(day, true, "note", text = if (knife == 0) "狼队今晚没有刀人。" else "狼队今晚刀 ${knife}号。", visibleTo = wolfNos))
        }
        // 预言家
        alive().firstOrNull { it.role == WolfRole.SEER }?.let { s ->
            val checked = pickTarget(s, day, true, "查验", "今晚你要查验谁？会告诉你他是好人还是狼人。", aliveNos().filter { it != s.no }, allowZero = false)
            if (checked != 0) emit(WolfEvent(day, true, "seerResult", s.no, checked,
                "${checked}号是${if (seat(checked).role.wolf) "狼人" else "好人"}。", visibleTo = listOf(s.no)))
        }
        // 女巫：知道刀口；一晚最多用一瓶；不能自救
        var saved = false
        var poisoned = 0
        alive().firstOrNull { it.role == WolfRole.WITCH }?.let { w ->
            if (antidote || poison) {
                emit(WolfEvent(day, true, "witchInfo", 0, knife, if (knife == 0) "今晚没人被刀。" else "今晚 ${knife}号被刀了。", visibleTo = listOf(w.no)))
                val r = witch(w, day, knife)
                if (r.first) { saved = true; antidote = false }
                if (r.second != 0) { poisoned = r.second; poison = false }
            }
        }
        // 天亮结算：同守同救算死（奶穿）
        val dead = mutableListOf<Pair<Int, String>>()
        if (knife != 0 && (guarded == knife) == saved) dead += knife to "被狼刀"
        if (poisoned != 0 && dead.none { it.first == poisoned }) dead += poisoned to "被毒"
        nightDeaths = dead.map { it.first }
        for ((no, _) in dead) kill(no)
    }

    private var nightDeaths: List<Int> = emptyList()

    /** 女巫：(用了解药, 毒了谁)。 */
    private suspend fun witch(w: WolfSeat, day: Int, knife: Int): Pair<Boolean, Int> {
        val canSave = antidote && knife != 0 && knife != w.no
        val poisonable = if (poison) aliveNos().filter { it != w.no } else emptyList()
        if (!canSave && poisonable.isEmpty()) return false to 0
        val ask = buildString {
            append(if (knife == 0) "今晚没人被刀。" else "今晚 ${knife}号被刀了。")
            append("你手里：").append(if (antidote) "解药 1 瓶" else "解药已用").append("，").append(if (poison) "毒药 1 瓶" else "毒药已用").append("。")
            append("一晚最多用一瓶；不能救自己。")
            append("输出 JSON：{\"save\": true/false, \"poison\": 座位号或 0}。")
            if (!canSave) append("（今晚不能用解药。）")
        }
        repeat(2) { attempt ->
            val raw = brainOf(w).ask(w, systemFor(w), userFor(w, day, true, ask))
            countCall()
            val (think, obj) = WolfParse.split(raw)
            note(WolfNote(day, w.no, "think", "用药", think))
            if (obj != null) {
                val save = (obj["save"] as? JsonPrimitive)?.contentOrNull == "true"
                val p = (obj["poison"] as? JsonPrimitive)?.intOrNull ?: 0
                val ok = (!save || canSave) && (p == 0 || p in poisonable) && !(save && p != 0)
                if (ok) return save to p
            }
            note(WolfNote(day, w.no, if (attempt == 0) "retry" else "abstain", "用药", raw.take(300)))
        }
        return false to 0
    }

    // ———————————————— 白天 ————————————————

    private suspend fun daytime(day: Int) {
        val dead = nightDeaths
        emit(WolfEvent(day, false, if (dead.isEmpty()) "peace" else "death",
            text = if (dead.isEmpty()) "第 $day 天，天亮了。昨晚是平安夜。" else "第 $day 天，天亮了。昨晚死的是：${dead.sorted().joinToString("、") { "${it}号" }}。"))
        if (day == 1) for (no in dead) lastWords(seat(no), day)
        if (finished(day)) return
        // 顺序每天轮换起点（Kaggle 的做法，设计说明第 1 节）
        val order = aliveNos().let { list -> val k = (day - 1) % list.size; list.drop(k) + list.take(k) }
        judgeAll(day, "beforeSpeech")
        emit(WolfEvent(day, false, "note", text = "开始发言，顺序：${order.joinToString("、") { "${it}号" }}。"))
        for (no in order) speak(seat(no), day)
        judgeAll(day, "beforeVote")
        // 同时投票：每人先交，全交完一起公布（交的时候谁也看不到别人的票）
        val voters = alive()
        val ballots = coroutineScope {
            voters.map { v ->
                async {
                    v.no to pickTarget(v, day, false, "投票", "现在投票放逐一个人。所有人同时投，投完一起公布。可以弃票（target 写 0）。",
                        aliveNos().filter { it != v.no }, allowZero = true)
                }
            }.awaitAll()
        }
        for ((v, t) in ballots) emit(WolfEvent(day, false, "vote", v, t, if (t == 0) "${v}号弃票" else "${v}号投 ${t}号"))
        val counts = ballots.filter { it.second != 0 }.groupingBy { it.second }.eachCount()
        val top = counts.maxOfOrNull { it.value } ?: 0
        val leaders = counts.filter { it.value == top }.keys
        if (top == 0 || leaders.size > 1) {
            emit(WolfEvent(day, false, "peace", text = if (top == 0) "没人投票，今天没人出局。" else "平票（${leaders.sorted().joinToString("、") { "${it}号" }} 各 $top 票），今天没人出局。"))
            return
        }
        val out = leaders.first()
        emit(WolfEvent(day, false, "exile", 0, out, "${out}号得 $top 票，被放逐。"))
        kill(out)
        lastWords(seat(out), day)
    }

    private suspend fun speak(s: WolfSeat, day: Int) {
        repeat(2) { attempt ->
            val raw = brainOf(s).ask(s, systemFor(s), userFor(s, day, false,
                "轮到你发言了。像真人在桌上一样说，说你的判断和理由。格式：先写 <想法>你的私下想法（别人看不到）</想法>，再写 <发言>你要公开说的话</发言>。"))
            countCall()
            val (think, speech) = WolfParse.speech(raw)
            if (think.isNotBlank()) note(WolfNote(day, s.no, "think", "发言", think))
            if (speech.isNotBlank()) { emit(WolfEvent(day, false, "speech", s.no, text = speech.take(2000))); return }
            note(WolfNote(day, s.no, if (attempt == 0) "retry" else "abstain", "发言", raw.take(300)))
        }
        emit(WolfEvent(day, false, "speech", s.no, text = "（${s.no}号没有发言）"))
    }

    private suspend fun lastWords(s: WolfSeat, day: Int) {
        val raw = brainOf(s).ask(s, systemFor(s), userFor(s, day, false,
            "你出局了，请留遗言。格式：<想法>私下想法</想法><发言>遗言</发言>。"))
        countCall()
        val (think, speech) = WolfParse.speech(raw)
        if (think.isNotBlank()) note(WolfNote(day, s.no, "think", "遗言", think))
        emit(WolfEvent(day, false, "lastWords", s.no, text = speech.ifBlank { "（没有遗言）" }.take(2000)))
    }

    /** 私下判断：每个活着的人对每个别人是狼的把握。只记录，不进任何人的记录。 */
    private suspend fun judgeAll(day: Int, stage: String) = coroutineScope {
        alive().map { s -> async { judgeOne(s, day, stage) } }.awaitAll()
    }

    private suspend fun judgeOne(s: WolfSeat, day: Int, stage: String) {
        run {
            val others = aliveNos().filter { it != s.no }
            val ask = "（这一步只记录，不会给任何人看。）说说你现在的判断：每个别的活着的玩家是狼人的把握（0～100），" +
                "以及你最怀疑谁。输出 JSON：{\"probs\": {\"座位号\": 把握, …}, \"most\": 座位号, \"confidence\": 0～100, \"why\": \"一两句理由\"}。" +
                "要判断的座位：${others.joinToString("、") { "${it}号" }}。"
            for (attempt in 0..1) {
                val raw = brainOf(s).ask(s, systemFor(s), userFor(s, day, false, ask))
                countCall()
                val (_, obj) = WolfParse.split(raw)
                val most = (obj?.get("most") as? JsonPrimitive)?.intOrNull ?: 0
                if (obj != null && most in others) {
                    val probs = (obj["probs"] as? JsonObject)?.mapNotNull { (k, v) ->
                        val n = k.filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
                        val p = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                        if (n in others) n to p.coerceIn(0, 100) else null
                    }?.toMap().orEmpty()
                    note(WolfNote(day, s.no, "judge", stage, (obj["why"] as? JsonPrimitive)?.contentOrNull.orEmpty(), most,
                        ((obj["confidence"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0).coerceIn(0, 100), probs))
                    break
                }
                note(WolfNote(day, s.no, if (attempt == 0) "retry" else "abstain", "判断", raw.take(300)))
            }
        }
    }

    /**
     * 问一个要选座位号的动作（守、刀、验、投）。JSON：{"target": 座位号, "reason": "…"}。
     * 不合法重问一次，还不行记弃权、返回 0（绝不随机替它选，设计说明第 3 节）。
     */
    private suspend fun pickTarget(s: WolfSeat, day: Int, night: Boolean, what: String, ask: String, allowed: List<Int>,
                                   allowZero: Boolean, wolfTalk: Boolean = false): Int {
        val full = ask + "可选：${allowed.joinToString("、") { "${it}号" }}。格式：先写 <想法>私下想法</想法>，再输出 JSON：{\"target\": 座位号, \"reason\": \"一句话\"}。"
        for (attempt in 0..1) {
            val raw = brainOf(s).ask(s, systemFor(s), userFor(s, day, night, full))
            countCall()
            val (think, obj) = WolfParse.split(raw)
            if (think.isNotBlank()) note(WolfNote(day, s.no, "think", what, think))
            val t = (obj?.get("target") as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.toIntOrNull()
            if (t != null && (t in allowed || (allowZero && t == 0))) {
                if (wolfTalk) emit(WolfEvent(day, true, "wolfTalk", s.no, t,
                    "${s.no}号想刀 ${if (t == 0) "没人" else "${t}号"}：" + ((obj["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()).take(300),
                    visibleTo = alive().filter { it.role.wolf }.map { it.no }))
                return t
            }
            note(WolfNote(day, s.no, if (attempt == 0) "retry" else "abstain", what, raw.take(300)))
        }
        return 0
    }

    private fun countCall() = synchronized(lock) { record = record.copy(calls = record.calls + 1) }

    private fun kill(no: Int) = change { r -> r.copy(seats = r.seats.map { if (it.no == no) it.copy(alive = false) else it }) }

    // ———————————————— 胜负 ————————————————

    private fun finished(day: Int): Boolean {
        val w = WolfRules.winner(seats) ?: return false
        end(day, w.first, w.second)
        return true
    }

    private fun end(day: Int, winner: String, reason: String) {
        record = record.copy(winner = winner, endReason = reason, endedAt = now())
        emit(WolfEvent(day, false, "end", text = reason + "。身份：" + seats.joinToString("，") { "${it.no}号${it.role.label}" }))
    }

    // ———————————————— 给 AI 看的 ————————————————

    private fun systemFor(s: WolfSeat): String = WolfPrompts.system(record, s)

    private fun userFor(s: WolfSeat, day: Int, night: Boolean, ask: String): String = WolfPrompts.user(record, s, day, night, ask)
}

/** 胜负（屠边，设计说明第 2 节）：返回（"good"/"wolf"，原因），没分出来返回 null。 */
object WolfRules {
    fun winner(seats: List<WolfSeat>): Pair<String, String>? {
        val alive = seats.filter { it.alive }
        val wolves = alive.count { it.role.wolf }
        val good = alive.size - wolves
        val gods = alive.count { it.role.god }
        val villagers = alive.count { it.role == WolfRole.VILLAGER }
        val hadGods = seats.any { it.role.god }
        return when {
            wolves == 0 -> "good" to "狼人全部出局，好人赢"
            hadGods && gods == 0 -> "wolf" to "神职全部出局，狼人赢（屠边）"
            villagers == 0 -> "wolf" to "平民全部出局，狼人赢（屠边）"
            wolves >= good -> "wolf" to "狼人数不少于好人，狼人赢"
            else -> null
        }
    }

    /** 按板子随机发身份（座位 1..n），seat 的 memberId / 名字由调用方给。 */
    fun deal(roles: List<WolfRole>, rng: Random): List<WolfRole> = roles.shuffled(rng)
}

/** 解析 AI 的输出：<想法>…</想法> + JSON 或 <发言>…</发言>，容忍代码块、前后多余的字。 */
object WolfParse {
    private val thinkTag = Regex("<想法>([\\s\\S]*?)</想法>")
    private val speechTag = Regex("<发言>([\\s\\S]*?)(</发言>|$)")

    fun clean(raw: String) = raw.substringAfter("</think>", raw)

    /** 值里写成「"most": 5号」（真模型 mimo-v2.6-flash 10-26 这样写过，JSON 不合法）：把冒号后面的「数字号」改成数字。 */
    private val numberWithHao = Regex("(:\\s*)(\\d+)\\s*号(?=\\s*[,}])")

    // 解析器是宽松的：「5号」不报错、当成一段文字，所以先规整再解析（不能等解析失败再改）
    fun split(raw: String): Pair<String, JsonObject?> = splitStrict(numberWithHao.replace(raw, "$1$2"))

    private fun splitStrict(raw: String): Pair<String, JsonObject?> {
        val t = clean(raw)
        val think = thinkTag.find(t)?.groupValues?.get(1)?.trim().orEmpty()
        val rest = thinkTag.replace(t, " ")
        val start = rest.lastIndexOf('{').let { last ->
            // 取最后一个完整的 JSON 对象：从第一个能配上最后一个 } 的 { 开始找
            var i = rest.indexOf('{')
            var found = -1
            while (i in 0..last) {
                val end = rest.lastIndexOf('}')
                if (end > i && runCatching { Json.parse(rest.substring(i, end + 1)) as? JsonObject }.getOrNull() != null) { found = i; break }
                i = rest.indexOf('{', i + 1)
            }
            found
        }
        val obj = if (start >= 0) runCatching { Json.parse(rest.substring(start, rest.lastIndexOf('}') + 1)) as? JsonObject }.getOrNull() else null
        return think to obj
    }

    fun speech(raw: String): Pair<String, String> {
        val t = clean(raw)
        val think = thinkTag.find(t)?.groupValues?.get(1)?.trim().orEmpty()
        // 发言没套标签：去掉想法剩下的就是发言（模型常这样，算合法）
        val speech = speechTag.find(t)?.groupValues?.get(1)?.trim() ?: thinkTag.replace(t, "").trim()
        return think to speech
    }
}
