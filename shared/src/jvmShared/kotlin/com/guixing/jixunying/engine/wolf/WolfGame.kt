package com.guixing.jixunying.engine.wolf

import com.guixing.jixunying.engine.Json
import com.guixing.jixunying.model.WolfAsk
import com.guixing.jixunying.model.WolfEvent
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfNote
import com.guixing.jixunying.model.WolfRole
import com.guixing.jixunying.model.WolfSeat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 一个座位背后是谁在想：AI（问模型）或真人（界面填）。
 * system 是这个座位的身份和规则，user 是它看得到的记录 + 这一步要它做什么；回原文，程序自己解析。
 */
fun interface WolfBrain {
    suspend fun ask(seat: WolfSeat, system: String, user: String): String

    /** 法官实际调的这个：多带一份「这一步要做什么」（真人座位按它出界面，AI 座位不用）。 */
    suspend fun act(seat: WolfSeat, system: String, user: String, step: WolfAsk): String = ask(seat, system, user)
}

/** 主持人的节奏：法官每走一步之前叫一下（一步一步时等「下一步」，暂停时等继续）。label 是这一步叫什么。 */
fun interface WolfGate {
    suspend fun before(label: String)
}

/**
 * 狼人杀的法官（程序，不是 AI）：按 docs/狼人杀设计说明.md 和第一代（G:\ai-discussion-app\src\wolf）的规则一步一步问各个座位，
 * 记事件和私下想法，打完算指标。每一步都把记录交给 onUpdate（存盘、推界面）。
 *
 * 夜里顺序：（第一夜）盗贼复制 → 狼美人魅惑 → 守卫 → 摄梦人 → 奇迹商人 → 狼人 → 预言家 → 女巫 → 商人送的查验 / 毒药；天亮一起结算。
 * 白天：死讯 →（第一夜死的）遗言 → 开枪 → 按顺序发言（发言时可以自爆、白狼王带人、骑士决斗）→ 同时投票 → 平票的 PK 发言再投一次 → 放逐、遗言、开枪。
 */
class WolfGame(
    start: WolfGameRecord,
    private val brainOf: (WolfSeat) -> WolfBrain,
    private val now: () -> Long = System::currentTimeMillis,
    private val onUpdate: (WolfGameRecord) -> Unit = {},
    private val maxDays: Int = 20,
    private val gate: WolfGate = WolfGate {},
    /** AI 每天发言前、投票前交私下判断（测评用）。 */
    private val judge: Boolean = true,
    /**
     * 一个 AI 交私下判断最多等多久（毫秒），超时这次判断跳过、记弃权。私下判断只给测评用、不影响对局，
     * 不能让整桌等最慢的那个（2026-10-29 实测：智谱 GLM-5.3-Flash 一次判断 268 秒，别人一分钟内都交了）。
     */
    private val judgeTimeoutMs: Long = 60_000,
    /** 给某个座位的规则后面再加的话（以前总结的经验）。 */
    private val systemExtra: (WolfSeat) -> String = { "" },
) {
    @Volatile var record = start
        private set

    private val antidote = HashMap<Int, Boolean>()
    private val poison = HashMap<Int, Boolean>()
    private val lastGuard = HashMap<Int, Int>()
    private val lastDream = HashMap<Int, Int>()
    private val charm = HashMap<Int, Int>()
    private val knightUsed = HashSet<Int>()
    private val merchantUsed = HashSet<Int>()

    private val seats get() = record.seats
    private fun seat(no: Int) = seats.first { it.no == no }
    private fun alive() = seats.filter { it.alive }
    private fun aliveNos() = alive().map { it.no }
    private fun aliveWith(role: WolfRole) = alive().filter { it.role == role }
    private fun name(no: Int) = "${no}号"

    // 私下判断、投票、狼队提刀是几个座位同时问的（互相看不到），改记录要排队
    private val lock = Any()
    private fun change(f: (WolfGameRecord) -> WolfGameRecord) = synchronized(lock) { record = f(record); onUpdate(record) }
    private fun emit(e: WolfEvent) = change { it.copy(events = it.events + e.copy(at = now())) }
    private fun note(n: WolfNote) = change { it.copy(notes = it.notes + n.copy(at = now())) }
    /** 只有法官 / 主持人看得到的（谁守了谁、谁毒了谁），打完复盘大家才看到。 */
    private fun god(day: Int, night: Boolean, text: String, actor: Int = 0, target: Int = 0) =
        emit(WolfEvent(day, night, "act", actor, target, text, visibleTo = listOf(0)))
    /** 只告诉这一个座位的（你守了谁、商人送了你什么）。 */
    private fun tell(no: Int, day: Int, night: Boolean, text: String, target: Int = 0) =
        emit(WolfEvent(day, night, "act", no, target, text, visibleTo = listOf(no)))

    /** 走到哪一步了：写进记录（界面顶上那行），再让主持人的节奏把关。 */
    private suspend fun step(label: String) {
        change { it.copy(phase = label) }
        gate.before(label)
    }

    suspend fun run(): WolfGameRecord {
        record = record.copy(startedAt = record.startedAt.takeIf { it > 0 } ?: now())
        for (s in seats) { antidote[s.no] = true; poison[s.no] = true }
        emit(WolfEvent(0, false, "note", text = "游戏开始：${record.label}。座位 1～${seats.size} 号。"))
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
        record = record.copy(metrics = WolfMetrics.of(record), firstDivergence = WolfMetrics.firstDivergence(record), phase = "结束")
        onUpdate(record)
        return record
    }

    // ———————————————— 夜里 ————————————————

    private var nightDeaths: List<Pair<Int, String>> = emptyList()

    private suspend fun night(day: Int) {
        step("第 $day 夜 · 天黑")
        emit(WolfEvent(day, true, "note", text = "第 $day 夜，天黑请闭眼。"))
        if (day == 1) {
            for (t in aliveWith(WolfRole.THIEF)) { step("第 1 夜 · 盗贼"); thief(t) }
            for (b in aliveWith(WolfRole.BEAUTY_WOLF)) {
                step("第 1 夜 · 狼美人")
                val c = pickTarget(b, day, true, "魅惑", "第一夜选一个不是狼的人魅惑：你出局时他会跟着出局。",
                    "魅惑谁？你出局时他会跟着出局。", alive().filter { !it.role.wolf }.map { it.no }, allowZero = false)
                if (c != 0) { charm[b.no] = c; tell(b.no, day, true, "你魅惑了 ${name(c)}。", c); god(day, true, "${name(b.no)}（狼美人）魅惑了 ${name(c)}。", b.no, c) }
            }
        }
        // 守卫
        val guarded = HashSet<Int>()
        for (g in aliveWith(WolfRole.GUARD)) {
            step("第 $day 夜 · 守卫")
            val last = lastGuard[g.no] ?: 0
            val t = pickTarget(g, day, true, "守护",
                "今晚你要守护谁？被守护的人今晚不会被狼刀死。不能连续两晚守同一个人（昨晚守的是 ${if (last == 0) "没有" else name(last)}），可以守自己。也可以不守（target 写 0）。",
                "今晚守护谁？不能连着两晚守同一个人" + (if (last == 0) "。" else "（昨晚守的是 ${name(last)}）。"),
                aliveNos().filter { it != last }, allowZero = true)
            lastGuard[g.no] = t
            if (t != 0) { guarded += t; tell(g.no, day, true, "你今晚守护了 ${name(t)}。", t); god(day, true, "${name(g.no)}（守卫）守护 ${name(t)}。", g.no, t) }
            else god(day, true, "${name(g.no)}（守卫）今晚没守人。", g.no)
        }
        // 摄梦人
        val dreams = HashMap<Int, Int>()
        for (d in aliveWith(WolfRole.DREAMER)) {
            step("第 $day 夜 · 摄梦人")
            val last = lastDream[d.no] ?: 0
            val t = pickTarget(d, day, true, "梦游",
                "今晚让谁梦游？梦游的人今晚不会被狼刀死；连着两晚选同一个人他会死（昨晚选的是 ${if (last == 0) "没有" else name(last)}）；你夜里死了他也会跟着死。",
                "今晚让谁梦游？连着两晚选同一个人他会死" + (if (last == 0) "。" else "（昨晚选的是 ${name(last)}）。"),
                aliveNos().filter { it != d.no }, allowZero = false)
            if (t != 0) { dreams[d.no] = t; tell(d.no, day, true, "你今晚让 ${name(t)} 梦游。", t); god(day, true, "${name(d.no)}（摄梦人）让 ${name(t)} 梦游。", d.no, t) }
        }
        // 奇迹商人：送出去的查验 / 毒药，收的人在女巫之后用；护盾当晚挡狼刀
        val shielded = HashSet<Int>()
        val gifts = mutableListOf<Pair<Int, String>>()
        for (m in aliveWith(WolfRole.MERCHANT).filter { it.no !in merchantUsed }) {
            step("第 $day 夜 · 奇迹商人")
            val (t, gift) = merchant(m, day)
            if (t == 0) continue
            merchantUsed += m.no
            val giftName = when (gift) { "check" -> "查验"; "shield" -> "护盾"; else -> "毒药" }
            god(day, true, "${name(m.no)}（奇迹商人）送给 ${name(t)} 一次$giftName。", m.no, t)
            tell(m.no, day, true, "你把$giftName 送给了 ${name(t)}。", t)
            if (gift == "shield") shielded += t else gifts += t to gift
            tell(t, day, true, "奇迹商人送了你一次$giftName" + if (gift == "shield") "：你今晚不会被狼刀死。" else "，今晚就用。")
        }
        // 狼人：各自提，不一样再商量一次，还不一样按第一只活狼的
        val wolves = alive().filter { it.role.wolf }
        val wolfNos = wolves.map { it.no }
        val targets = aliveNos().filter { it !in wolfNos }
        var knife = 0
        if (wolves.isNotEmpty() && targets.isNotEmpty()) {
            step("第 $day 夜 · 狼人")
            val first = coroutineScope {
                wolves.map { w ->
                    async {
                        w.no to pickTarget(w, day, true, "刀人",
                            "今晚狼队要刀谁？你的狼队友是：${mates(w)}。在 reason 里简单说理由，队友能看到。",
                            "今晚刀谁？狼队友：${mates(w)}。", targets, allowZero = false, wolfTalk = true)
                    }
                }.awaitAll()
            }
            val picks = first.map { it.second }.filter { it != 0 }
            knife = if (picks.distinct().size <= 1) picks.firstOrNull() ?: 0 else {
                val again = "狼队意见不一样：" + first.joinToString("；") { (n, t) -> "${n}号想刀 ${if (t == 0) "没说" else name(t)}" } + "。再定一次，尽量统一。"
                val second = coroutineScope {
                    wolves.map { w -> async { pickTarget(w, day, true, "刀人", again, again, targets, allowZero = false, wolfTalk = true) } }.awaitAll()
                }.filter { it != 0 }
                if (second.distinct().size == 1) second.first() else second.firstOrNull() ?: picks.first()
            }
            emit(WolfEvent(day, true, "note", text = if (knife == 0) "狼队今晚没有刀人。" else "狼队今晚刀 ${name(knife)}。", visibleTo = wolfNos))
        }
        // 预言家
        for (s in aliveWith(WolfRole.SEER)) {
            step("第 $day 夜 · 预言家")
            check(s, day, "今晚你要查验谁？会告诉你他是好人还是狼人。")
        }
        // 女巫：知道刀口；一晚最多用一瓶；不能自救
        var saved = false
        val poisoned = mutableListOf<Int>()
        for (w in aliveWith(WolfRole.WITCH)) {
            if (antidote[w.no] != true && poison[w.no] != true) continue
            step("第 $day 夜 · 女巫")
            emit(WolfEvent(day, true, "witchInfo", 0, knife, if (knife == 0) "今晚没人被刀。" else "今晚 ${name(knife)} 被刀了。", visibleTo = listOf(w.no)))
            val (save, p) = witch(w, day, knife)
            // 自己用了什么药也告诉她自己（2026-10-29 查出来：以前只写进法官记录，第二天女巫自己看不到昨晚救没救、毒了谁）
            if (save) { saved = true; antidote[w.no] = false; god(day, true, "${name(w.no)}（女巫）用解药救了 ${name(knife)}。", w.no, knife); tell(w.no, day, true, "你用解药救了 ${name(knife)}。", knife) }
            if (p != 0) { poisoned += p; poison[w.no] = false; god(day, true, "${name(w.no)}（女巫）毒了 ${name(p)}。", w.no, p); tell(w.no, day, true, "你用毒药毒了 ${name(p)}。", p) }
            if (!save && p == 0) { god(day, true, "${name(w.no)}（女巫）今晚没用药。", w.no); tell(w.no, day, true, "你今晚没用药。") }
        }
        // 商人送的查验 / 毒药
        for ((no, gift) in gifts) {
            val s = seat(no)
            if (!s.alive) continue
            if (gift == "check") check(s, day, "奇迹商人送了你一次查验：今晚查验谁？会告诉你他是好人还是狼人。")
            else {
                val p = pickTarget(s, day, true, "下毒", "奇迹商人送了你一瓶毒药：今晚毒谁？也可以不用（target 写 0）。", "奇迹商人送了你一瓶毒药：今晚毒谁？",
                    aliveNos().filter { it != no }, allowZero = true)
                if (p != 0) { poisoned += p; god(day, true, "${name(no)} 用商人的毒药毒了 ${name(p)}。", no, p); tell(no, day, true, "你用商人的毒药毒了 ${name(p)}。", p) }
                else tell(no, day, true, "商人送的毒药你今晚没用。")
            }
        }
        // 天亮一起结算：同守同救算死（奶穿）；梦游、护盾挡狼刀
        val dead = LinkedHashMap<Int, String>()
        if (knife != 0) {
            val g = knife in guarded
            val safe = if (g && saved) false else g || saved || knife in dreams.values || knife in shielded
            if (!safe) dead[knife] = "被狼刀"
            else god(day, true, "${name(knife)} 被刀，" + when {
                g -> "守卫守住了"; saved -> "女巫救了"; knife in shielded -> "商人的护盾挡住了"; else -> "在梦游，躲过了"
            } + "。", target = knife)
        }
        for (p in poisoned) if (p !in dead) dead[p] = "被毒"
        for ((d, t) in dreams) if (t == lastDream[d] && t !in dead) dead[t] = "连着两晚被梦游"
        for (d in aliveWith(WolfRole.DREAMER)) lastDream[d.no] = dreams[d.no] ?: 0
        val died = mutableListOf<Int>()
        for ((no, why) in dead) died += kill(no, why, day, night = true)
        // 摄梦人夜里死了：梦游的人一起死
        for ((d, t) in dreams) if (d in died && seat(t).alive) { god(day, true, "${name(d)}（摄梦人）死了，梦游的 ${name(t)} 跟着死。", d, t); died += kill(t, "摄梦人殉情", day, night = true) }
        nightDeaths = died.distinct().map { it to seat(it).deathReason }
        for ((no, why) in nightDeaths) god(day, true, "${name(no)} 死了（$why）。", target = no)
    }

    private fun mates(w: WolfSeat) = alive().filter { it.role.wolf && it.no != w.no }.joinToString("、") { name(it.no) }.ifEmpty { "（只剩你自己）" }

    /** 盗贼：第一夜盲选一个人复制身份和阵营；不选就当平民。 */
    private suspend fun thief(t: WolfSeat) {
        val c = pickTarget(t, 1, true, "复制", "你是盗贼：选一个人，复制他的身份和阵营（你看不到别人的身份，是盲选）。不选（target 写 0）就当平民。",
            "选一个人复制他的身份和阵营（盲选）；不选就当平民。", aliveNos().filter { it != t.no }, allowZero = true)
        val got = if (c == 0) WolfRole.VILLAGER else seat(c).role.let { if (it == WolfRole.THIEF) WolfRole.VILLAGER else it }
        change { r -> r.copy(seats = r.seats.map { if (it.no == t.no) it.copy(role = got, origRole = WolfRole.THIEF) else it }) }
        tell(t.no, 1, true, if (c == 0) "你没复制，当平民。" else "你复制了 ${name(c)} 的身份：${got.label}。", c)
        god(1, true, "${name(t.no)}（盗贼）" + (if (c == 0) "没复制，当平民。" else "复制了 ${name(c)}：${got.label}。"), t.no, c)
        if (got.wolf) {
            val wolves = alive().filter { it.role.wolf }.map { it.no }
            emit(WolfEvent(1, true, "note", t.no, text = "${name(t.no)}（盗贼）复制成了狼人，和你们一队。", visibleTo = wolves))
        }
    }

    private suspend fun check(s: WolfSeat, day: Int, ask: String) {
        val c = pickTarget(s, day, true, "查验", ask, "今晚查验谁？会告诉你他是好人还是狼人。", aliveNos().filter { it != s.no }, allowZero = false)
        if (c == 0) return
        val bad = seat(c).role.wolf
        emit(WolfEvent(day, true, "seerResult", s.no, c, "${name(c)}是${if (bad) "狼人" else "好人"}。", visibleTo = listOf(s.no)))
        god(day, true, "${name(s.no)} 查验 ${name(c)}：${if (bad) "狼人" else "好人"}。", s.no, c)
    }

    /** 女巫：(用了解药, 毒了谁)。 */
    private suspend fun witch(w: WolfSeat, day: Int, knife: Int): Pair<Boolean, Int> {
        val canSave = antidote[w.no] == true && knife != 0 && knife != w.no
        val poisonable = if (poison[w.no] == true) aliveNos().filter { it != w.no } else emptyList()
        if (!canSave && poisonable.isEmpty()) return false to 0
        val ask = buildString {
            append(if (knife == 0) "今晚没人被刀。" else "今晚 ${name(knife)} 被刀了。")
            append("你手里：").append(if (antidote[w.no] == true) "解药 1 瓶" else "解药已用").append("，").append(if (poison[w.no] == true) "毒药 1 瓶" else "毒药已用").append("。")
            append("一晚最多用一瓶；不能救自己。")
            append("输出 JSON：{\"save\": true/false, \"poison\": 座位号或 0}。")
            if (!canSave) append("（今晚不能用解药。）")
        }
        repeat(2) { attempt ->
            val raw = brainOf(w).act(w, systemFor(w), userFor(w, day, true, ask),
                WolfAsk(seat = w.no, kind = "witch", what = "用药", text = if (knife == 0) "今晚没人被刀。" else "今晚 ${name(knife)} 被刀了。",
                    canSave = canSave, poisonable = poisonable))
            countCall()
            val (think, obj) = WolfParse.split(raw)
            if (think.isNotBlank()) note(WolfNote(day, w.no, "think", "用药", think))
            if (obj != null) {
                val save = (obj["save"] as? JsonPrimitive)?.contentOrNull == "true"
                val p = (obj["poison"] as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                val ok = (!save || canSave) && (p == 0 || p in poisonable) && !(save && p != 0)
                if (ok) return save to p
            }
            note(WolfNote(day, w.no, if (attempt == 0) "retry" else "abstain", "用药", raw.take(300)))
        }
        return false to 0
    }

    /** 奇迹商人：(送给谁, 送什么)；今晚不送返回 (0, "")。 */
    private suspend fun merchant(m: WolfSeat, day: Int): Pair<Int, String> {
        val allowed = aliveNos().filter { it != m.no }
        val ask = "你是奇迹商人，整局只能送一次：选一个人，送他查验（check）、护盾（shield，今晚不会被狼刀死）或毒药（poison）里的一样，他今晚就能用。" +
            "今晚不送就 target 写 0。可选：${allowed.joinToString("、") { name(it) }}。格式：先写 <想法>私下想法</想法>，再输出 JSON：{\"target\": 座位号, \"gift\": \"check/shield/poison\"}。"
        repeat(2) { attempt ->
            val raw = brainOf(m).act(m, systemFor(m), userFor(m, day, true, ask),
                WolfAsk(seat = m.no, kind = "merchant", what = "送技能", text = "整局一次：送一个人查验、护盾或毒药；今晚也可以先不送。", allowed = allowed, allowZero = true))
            countCall()
            val (think, obj) = WolfParse.split(raw)
            if (think.isNotBlank()) note(WolfNote(day, m.no, "think", "送技能", think))
            val t = (obj?.get("target") as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.toIntOrNull()
            val g = (obj?.get("gift") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (t == 0) return 0 to ""
            if (t != null && t in allowed && g in setOf("check", "shield", "poison")) return t to g
            note(WolfNote(day, m.no, if (attempt == 0) "retry" else "abstain", "送技能", raw.take(300)))
        }
        return 0 to ""
    }

    // ———————————————— 白天 ————————————————

    private suspend fun daytime(day: Int) {
        step("第 $day 天 · 天亮")
        val dead = nightDeaths.map { it.first }
        emit(WolfEvent(day, false, if (dead.isEmpty()) "peace" else "death",
            text = if (dead.isEmpty()) "第 $day 天，天亮了。昨晚是平安夜。" else "第 $day 天，天亮了。昨晚死的是：${dead.sorted().joinToString("、") { name(it) }}。"))
        if (finished(day)) return
        if (day == 1) for (no in dead) lastWords(seat(no), day)
        // 被狼刀死的猎人、狼王天亮后开枪（被毒、殉情、梦游死的不能开）
        for ((no, why) in nightDeaths) if (why == "被狼刀" && seat(no).role.gunner) { gun(seat(no), day); if (finished(day)) return }
        // 顺序每天轮换起点（Kaggle 的做法，设计说明第 1 节）
        val order = aliveNos().let { list -> val k = (day - 1) % list.size; list.drop(k) + list.take(k) }
        if (judge) judgeAll(day, "beforeSpeech")
        step("第 $day 天 · 发言")
        emit(WolfEvent(day, false, "note", text = "开始发言，顺序：${order.joinToString("、") { name(it) }}。"))
        for (no in order) {
            val s = seat(no)
            if (!s.alive) continue
            val ended = speakAndSkill(s, day)
            if (finished(day)) return
            if (ended) { emit(WolfEvent(day, false, "note", text = "白天到此结束，直接进入黑夜。")); return }
        }
        if (judge) judgeAll(day, "beforeVote")
        step("第 $day 天 · 投票")
        val ballots = vote(day, alive().filter { it.canVote }, aliveNos(), "现在投票放逐一个人。所有人同时投，投完一起公布。可以弃票（target 写 0）。",
            "投票放逐谁？大家同时投，投完一起公布。")
        var out = leaderOf(ballots)
        if (out.second.size > 1) {
            val tied = out.second.sorted()
            emit(WolfEvent(day, false, "note", text = "平票：${tied.joinToString("、") { name(it) }} 各 ${out.first} 票。平票的人各说一次，其他人在他们里面再投一次。"))
            step("第 $day 天 · PK")
            for (no in tied) speak(seat(no), day, "pk")
            val pkVoters = alive().filter { it.canVote && it.no !in tied }
            if (pkVoters.isEmpty()) { emit(WolfEvent(day, false, "peace", text = "没人能投了，今天没人出局。")); return }
            val again = vote(day, pkVoters, tied, "PK 后重新投票：只能在 ${tied.joinToString("、") { name(it) }} 里选。可以弃票（target 写 0）。",
                "PK 后重新投：在 ${tied.joinToString("、") { name(it) }} 里选一个。")
            out = leaderOf(again)
            if (out.second.size != 1) {
                emit(WolfEvent(day, false, "peace", text = if (out.first == 0) "没人投票，今天没人出局。" else "还是平票，今天没人出局。"))
                return
            }
        } else if (out.first == 0) {
            emit(WolfEvent(day, false, "peace", text = "没人投票，今天没人出局。"))
            return
        }
        step("第 $day 天 · 放逐")
        exile(seat(out.second.first()), out.first, day)
    }

    /** 票最多的：(票数, 那几个人)。 */
    private fun leaderOf(ballots: List<Pair<Int, Int>>): Pair<Int, Set<Int>> {
        val counts = ballots.filter { it.second != 0 }.groupingBy { it.second }.eachCount()
        val top = counts.maxOfOrNull { it.value } ?: 0
        return top to counts.filter { it.value == top }.keys
    }

    /** 同时投票：每人先交，全交完一起公布（交的时候谁也看不到别人的票）。 */
    private suspend fun vote(day: Int, voters: List<WolfSeat>, candidates: List<Int>, ask: String, human: String): List<Pair<Int, Int>> {
        val ballots = coroutineScope {
            voters.map { v ->
                async { v.no to pickTarget(v, day, false, "投票", ask, human, candidates.filter { it != v.no }, allowZero = true) }
            }.awaitAll()
        }
        for ((v, t) in ballots) emit(WolfEvent(day, false, "vote", v, t, if (t == 0) "${v}号弃票" else "${v}号投 ${t}号"))
        return ballots
    }

    private suspend fun exile(s: WolfSeat, votes: Int, day: Int) {
        if (s.role == WolfRole.IDIOT && !s.revealed) {
            change { r -> r.copy(seats = r.seats.map { if (it.no == s.no) it.copy(revealed = true, canVote = false) else it }) }
            emit(WolfEvent(day, false, "skill", s.no, text = "${name(s.no)}得 $votes 票，翻牌：是白痴，免死，以后不能投票。"))
            return
        }
        emit(WolfEvent(day, false, "exile", 0, s.no, "${name(s.no)}得 $votes 票，被放逐。"))
        kill(s.no, "被投票出局", day)
        if (finished(day)) return
        lastWords(seat(s.no), day)
        if (s.role.gunner) gun(seat(s.no), day)
    }

    /** 开枪：猎人、狼王出局时带走一个人（可以不开）。被带走的有遗言；带走的又是猎人、狼王就接着开。 */
    private suspend fun gun(shooter: WolfSeat, day: Int) {
        if (finished(day)) return
        val t = pickTarget(shooter, day, false, "开枪",
            "你（${shooter.role.label}）出局了，可以开枪带走一个活着的人，也可以不开（target 写 0）。",
            "你出局了，可以开枪带走一个人，也可以不开。", aliveNos(), allowZero = true)
        if (t == 0) { emit(WolfEvent(day, false, "skill", shooter.no, text = "${name(shooter.no)}（${shooter.role.label}）没有开枪。")); return }
        emit(WolfEvent(day, false, "skill", shooter.no, t, "${name(shooter.no)}亮出${shooter.role.label}，开枪带走了 ${name(t)}。"))
        kill(t, "被开枪带走", day)
        if (finished(day)) return
        lastWords(seat(t), day)
        if (seat(t).role.gunner) gun(seat(t), day)
    }

    /** 发言，顺带的技能（自爆、白狼王带人、骑士决斗）在说完以后结算。返回 true = 白天到此结束。 */
    private suspend fun speakAndSkill(s: WolfSeat, day: Int): Boolean {
        val (skill, target) = speak(s, day, "speech")
        when (skill) {
            "explode" -> {
                emit(WolfEvent(day, false, "skill", s.no, text = "${name(s.no)}自爆了：是狼人。"))
                kill(s.no, "自爆", day)
                return true
            }
            "whiteExplode" -> {
                emit(WolfEvent(day, false, "skill", s.no, target, "${name(s.no)}自爆：是白狼王，带走了 ${name(target)}。"))
                kill(s.no, "自爆", day)
                kill(target, "被白狼王带走", day)
                return true
            }
            "duel" -> {
                knightUsed += s.no
                if (seat(target).role.wolf) {
                    emit(WolfEvent(day, false, "skill", s.no, target, "${name(s.no)}亮出骑士，和 ${name(target)} 决斗：${name(target)}是狼人，出局。"))
                    kill(target, "被骑士决斗", day)
                    return true
                }
                emit(WolfEvent(day, false, "skill", s.no, target, "${name(s.no)}亮出骑士，和 ${name(target)} 决斗：${name(target)}是好人，骑士出局。"))
                kill(s.no, "决斗输了", day)
            }
        }
        return false
    }

    /** 发言（kind = speech / pk）。返回顺带用的技能和对象（没用就是 ""）。 */
    private suspend fun speak(s: WolfSeat, day: Int, kind: String): Pair<String, Int> {
        val options = if (kind != "speech") emptyList() else buildList {
            if (s.role.canExplode) add("explode")
            if (s.role == WolfRole.WHITE_WOLF) add("whiteExplode")
            if (s.role == WolfRole.KNIGHT && s.no !in knightUsed) add("duel")
        }
        val others = aliveNos().filter { it != s.no }
        val skillHelp = buildString {
            if ("explode" in options) append("你可以在发言后自爆（自己出局，白天直接结束进夜晚）：在最后写 <技能>自爆</技能>。")
            if ("whiteExplode" in options) append("你也可以自爆并带走一个人：写 <技能>带走 座位号</技能>。")
            if ("duel" in options) append("你可以在发言后和一个人决斗（整局一次）：写 <技能>决斗 座位号</技能>；对方是狼就狼出局并进夜晚，是好人你自己出局。")
            if (isNotEmpty()) append("不用就别写。")
        }
        val ask = (if (kind == "pk") "你和别人平票了，现在是 PK 发言：为自己辩护，说清楚为什么不该投你。" else "轮到你发言了。像真人在桌上一样说，说你的判断和理由。") +
            "格式：先写 <想法>你的私下想法（别人看不到）</想法>，再写 <发言>你要公开说的话</发言>。" + skillHelp
        repeat(2) { attempt ->
            val raw = brainOf(s).act(s, systemFor(s), userFor(s, day, false, ask),
                WolfAsk(seat = s.no, kind = "speech", what = if (kind == "pk") "PK 发言" else "发言",
                    text = if (kind == "pk") "你平票了：为自己辩护。" else "轮到你发言了。", allowed = others, options = options))
            countCall()
            val (think, speech) = WolfParse.speech(raw)
            if (think.isNotBlank()) note(WolfNote(day, s.no, "think", "发言", think))
            if (speech.isNotBlank()) {
                emit(WolfEvent(day, false, kind, s.no, text = speech.take(2000)))
                val (sk, t) = WolfParse.skill(raw)
                return when {
                    sk == "explode" && "explode" in options -> sk to 0
                    (sk == "whiteExplode" || sk == "duel") && sk in options && t in others -> sk to t
                    else -> "" to 0
                }
            }
            note(WolfNote(day, s.no, if (attempt == 0) "retry" else "abstain", "发言", raw.take(300)))
        }
        emit(WolfEvent(day, false, kind, s.no, text = "（${s.no}号没有发言）"))
        return "" to 0
    }

    private suspend fun lastWords(s: WolfSeat, day: Int) {
        val raw = brainOf(s).act(s, systemFor(s), userFor(s, day, false,
            "你出局了，请留遗言。格式：<想法>私下想法</想法><发言>遗言</发言>。"),
            WolfAsk(seat = s.no, kind = "lastWords", what = "遗言", text = "你出局了，留几句遗言。"))
        countCall()
        val (think, speech) = WolfParse.speech(raw)
        if (think.isNotBlank()) note(WolfNote(day, s.no, "think", "遗言", think))
        emit(WolfEvent(day, false, "lastWords", s.no, text = speech.ifBlank { "（没有遗言）" }.take(2000)))
    }

    /** 私下判断：每个活着的 AI 对每个别人是狼的把握。只记录，不进任何人的记录。真人不交。 */
    private suspend fun judgeAll(day: Int, stage: String) = coroutineScope {
        alive().filter { !it.human }.map { s -> async { judgeOne(s, day, stage) } }.awaitAll()
    }

    private suspend fun judgeOne(s: WolfSeat, day: Int, stage: String) {
        if (withTimeoutOrNull(judgeTimeoutMs) { judgeOneNow(s, day, stage) } == null)
            note(WolfNote(day, s.no, "abstain", "判断", "想了 ${judgeTimeoutMs / 1000} 秒还没交，这次判断跳过（只是测评用的记录，不影响对局）"))
    }

    private suspend fun judgeOneNow(s: WolfSeat, day: Int, stage: String) {
        val others = aliveNos().filter { it != s.no }
        val ask = "（这一步只记录，不会给任何人看。）说说你现在的判断：每个别的活着的玩家是狼人的把握（0～100），" +
            "以及你最怀疑谁。输出 JSON：{\"probs\": {\"座位号\": 把握, …}, \"most\": 座位号, \"confidence\": 0～100, \"why\": \"一两句理由\"}。" +
            "要判断的座位：${others.joinToString("、") { name(it) }}。"
        for (attempt in 0..1) {
            val raw = brainOf(s).act(s, systemFor(s), userFor(s, day, false, ask), WolfAsk(seat = s.no, kind = "judge", what = "判断"))
            countCall()
            val (_, obj) = WolfParse.split(raw)
            // 10-27 真局：阿德写成 "most": "2号"（带引号的字符串），和 target 一样只取数字
            val most = (obj?.get("most") as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (obj != null && most in others) {
                val probs = (obj["probs"] as? JsonObject)?.mapNotNull { (k, v) ->
                    val n = k.filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
                    val p = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                    if (n in others) n to p.coerceIn(0, 100) else null
                }?.toMap().orEmpty()
                note(WolfNote(day, s.no, "judge", stage, (obj["why"] as? JsonPrimitive)?.contentOrNull.orEmpty(), most,
                    ((obj["confidence"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0).coerceIn(0, 100), probs))
                return
            }
            note(WolfNote(day, s.no, if (attempt == 0) "retry" else "abstain", "判断", raw.take(300)))
        }
    }

    /**
     * 问一个要选座位号的动作（守、刀、验、投、开枪……）。JSON：{"target": 座位号, "reason": "…"}。
     * 不合法重问一次，还不行记弃权、返回 0（绝不随机替它选，设计说明第 3 节）。
     */
    private suspend fun pickTarget(s: WolfSeat, day: Int, night: Boolean, what: String, ask: String, human: String, allowed: List<Int>,
                                   allowZero: Boolean, wolfTalk: Boolean = false): Int {
        if (allowed.isEmpty() && !allowZero) return 0
        val full = ask + "可选：${allowed.joinToString("、") { name(it) }}。格式：先写 <想法>私下想法</想法>，再输出 JSON：{\"target\": 座位号, \"reason\": \"一句话\"}。"
        for (attempt in 0..1) {
            val raw = brainOf(s).act(s, systemFor(s), userFor(s, day, night, full),
                WolfAsk(seat = s.no, kind = "target", what = what, text = human, allowed = allowed, allowZero = allowZero))
            countCall()
            val (think, obj) = WolfParse.split(raw)
            if (think.isNotBlank()) note(WolfNote(day, s.no, "think", what, think))
            val t = (obj?.get("target") as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.toIntOrNull()
            if (t != null && (t in allowed || (allowZero && t == 0))) {
                if (wolfTalk) emit(WolfEvent(day, true, "wolfTalk", s.no, t,
                    "${s.no}号想刀 ${if (t == 0) "没人" else name(t)}" + ((obj["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()).take(300).let { if (it.isBlank()) "。" else "：$it" },
                    visibleTo = alive().filter { it.role.wolf }.map { it.no }))
                return t
            }
            note(WolfNote(day, s.no, if (attempt == 0) "retry" else "abstain", what, raw.take(300)))
        }
        return 0
    }

    private fun countCall() = synchronized(lock) { record = record.copy(calls = record.calls + 1) }

    /** 出局：记原因和哪天；狼美人出局，被魅惑的人跟着出局（夜里的只有法官知道，天亮跟死讯一起公布）。返回这次出局的所有人。 */
    private fun kill(no: Int, reason: String, day: Int, night: Boolean = false): List<Int> {
        if (!seat(no).alive) return emptyList()
        change { r -> r.copy(seats = r.seats.map { if (it.no == no) it.copy(alive = false, deathReason = reason, deathDay = day) else it }) }
        val out = mutableListOf(no)
        val c = charm[no] ?: 0
        if (seat(no).role == WolfRole.BEAUTY_WOLF && c != 0 && seat(c).alive) {
            val text = "${name(c)}被狼美人魅惑过，跟着出局。"
            if (night) god(day, true, text, no, c) else emit(WolfEvent(day, false, "skill", no, c, text))
            out += kill(c, "狼美人殉情", day, night)
        }
        return out
    }

    /** 主持人在桌上说一句（全是 AI 的局才有主持人）：大家都看得到，AI 之后的每一步都会看到。 */
    fun hostSay(text: String) = emit(WolfEvent(record.day, record.phase.contains("夜"), "host", text = text.take(500)))

    // ———————————————— 打完以后 ————————————————

    /**
     * 复盘（照第一代）：身份全公开，每个 AI 说一句感想，再总结一句经验（下局带上）。一个 AI 问一次。
     * 返回 座位号 → 经验。
     */
    suspend fun review(): Map<Int, String> {
        if (record.winner.isBlank()) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        val all = WolfPrompts.fullLog(record)
        val roles = seats.joinToString("，") { name(it.no) + (it.origRole?.let { o -> "${o.label}→" } ?: "") + it.role.label }
        for (s in seats.filter { !it.human }) {
            val ask = "游戏结束了：${record.endReason}。所有人的身份：$roles。\n\n【整局记录（含夜里谁做了什么）】\n$all\n\n" +
                "你是 ${name(s.no)}（${s.role.label}），${if (s.alive) "活到了最后" else "第 ${s.deathDay} 天出局（${s.deathReason}）"}。" +
                "身份都公开了，不用再装。输出 JSON：{\"感想\": \"一两句复盘感想：你这局的打法、遗憾或惊喜，像真人桌边聊天\", \"经验\": \"一句话总结这局学到的、下局能用的经验\"}。"
            val raw = try {
                brainOf(s).act(s, "你刚打完一局狼人杀，现在是局后复盘。", ask, WolfAsk(seat = s.no, kind = "review", what = "复盘"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            countCall()
            val (_, obj) = WolfParse.split(raw)
            val say = (obj?.get("感想") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { WolfParse.clean(raw).trim().take(300) }
            val exp = (obj?.get("经验") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (say.isNotBlank()) emit(WolfEvent(record.day, false, "review", s.no, text = say.take(600)))
            if (exp.isNotBlank()) { note(WolfNote(record.day, s.no, "exp", text = exp.take(200))); out[s.no] = exp.take(200) }
        }
        return out
    }

    // ———————————————— 胜负 ————————————————

    private fun finished(day: Int): Boolean {
        if (record.winner.isNotBlank()) return true
        val w = WolfRules.winner(seats) ?: return false
        end(day, w.first, w.second)
        return true
    }

    private fun end(day: Int, winner: String, reason: String) {
        record = record.copy(winner = winner, endReason = reason, endedAt = now())
        emit(WolfEvent(day, false, "end", text = reason + "。身份：" + seats.joinToString("，") { "${it.no}号${it.role.label}" }))
    }

    // ———————————————— 给 AI 看的 ————————————————

    private fun systemFor(s: WolfSeat): String = WolfPrompts.system(record, seat(s.no), knownFor(seat(s.no))) + systemExtra(s).let { if (it.isBlank()) "" else "\n\n$it" }

    /**
     * 这个座位手里的东西：药还在不在、技能用没用、上一晚守 / 梦游的是谁、魅惑了谁（2026-10-29 用户：「每个人知不知道自己是什么职业，应该做什么，
     * 能够看到什么，看不到什么？」）。这些在法官这里，记录里不一定有，所以单独写进它的规则；验人结果这类记录里已经有的不重复。
     */
    private fun knownFor(s: WolfSeat): List<String> = buildList {
        when (s.role) {
            WolfRole.WITCH -> add("你的药：解药" + (if (antidote[s.no] == true) "还在" else "已经用了") + "，毒药" + (if (poison[s.no] == true) "还在" else "已经用了") + "。")
            WolfRole.GUARD -> lastGuard[s.no]?.takeIf { it != 0 }?.let { add("你上一晚守的是 ${name(it)}，下一晚不能再守他。") }
            WolfRole.DREAMER -> lastDream[s.no]?.takeIf { it != 0 }?.let { add("你上一晚让 ${name(it)} 梦游；再连着选他，他会死。") }
            WolfRole.KNIGHT -> add(if (s.no in knightUsed) "你已经决斗过了（整局一次）。" else "你的决斗还没用（整局一次）。")
            WolfRole.MERCHANT -> add(if (s.no in merchantUsed) "你的礼物已经送出去了（整局一次）。" else "你的礼物还没送（整局一次）。")
            WolfRole.BEAUTY_WOLF -> charm[s.no]?.let { add("你魅惑的是 ${name(it)}（他自己不知道）：你出局时他跟着出局。") }
            WolfRole.IDIOT -> if (s.revealed) add("你已经翻过牌了：还在场上、能发言，但不能再投票。")
            else -> {}
        }
    }

    private fun userFor(s: WolfSeat, day: Int, night: Boolean, ask: String): String = WolfPrompts.user(record, seat(s.no), day, night, ask)
}

/** 胜负（屠边，设计说明第 2 节）：返回（"good"/"wolf"，原因），没分出来返回 null。 */
object WolfRules {
    fun winner(seats: List<WolfSeat>): Pair<String, String>? {
        val alive = seats.filter { it.alive }
        val wolves = alive.count { it.role.wolf }
        val good = alive.count { it.role.faction == "good" }
        val gods = alive.count { it.role.god }
        val villagers = alive.count { it.role == WolfRole.VILLAGER }
        val hadGods = seats.any { it.role.god }
        val hadVillagers = seats.any { it.role == WolfRole.VILLAGER }
        return when {
            wolves == 0 -> "good" to "狼人全部出局，好人赢"
            hadGods && gods == 0 -> "wolf" to "神职全部出局，狼人赢（屠边）"
            hadVillagers && villagers == 0 -> "wolf" to "平民全部出局，狼人赢（屠边）"
            wolves >= good -> "wolf" to "狼人数不少于好人，狼人赢"
            else -> null
        }
    }

    /** 按身份表随机发（座位 1..n）；fixed 里指定了的座位按指定，剩下的身份打乱发给其余座位。 */
    fun deal(roles: List<WolfRole>, rng: Random, fixed: Map<Int, WolfRole> = emptyMap()): List<WolfRole> {
        val pool = roles.toMutableList()
        for (r in fixed.values) pool.remove(r)
        pool.shuffle(rng)
        val it = pool.iterator()
        return roles.indices.map { i -> fixed[i + 1] ?: it.next() }
    }
}

/** 解析 AI 的输出：<想法>…</想法> + JSON 或 <发言>…</发言>（+ <技能>…</技能>），容忍代码块、前后多余的字。 */
object WolfParse {
    private val thinkTag = Regex("<想法>([\\s\\S]*?)</想法>")
    private val speechTag = Regex("<发言>([\\s\\S]*?)(</发言>|$)")
    private val skillTag = Regex("<技能>([\\s\\S]*?)</技能>")

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
        val t = skillTag.replace(clean(raw), "")
        val think = thinkTag.find(t)?.groupValues?.get(1)?.trim().orEmpty()
        // 发言没套标签：去掉想法剩下的就是发言（模型常这样，算合法）
        val speech = speechTag.find(t)?.groupValues?.get(1)?.trim() ?: thinkTag.replace(t, "").trim()
        return think to speech
    }

    /** 发言后面带的技能：「自爆」→ explode；「带走 3」→ whiteExplode；「决斗 3」→ duel。没写返回 ("", 0)。 */
    fun skill(raw: String): Pair<String, Int> {
        val s = skillTag.find(clean(raw))?.groupValues?.get(1)?.trim() ?: return "" to 0
        val n = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: 0
        return when {
            "决斗" in s -> "duel" to n
            "带走" in s -> "whiteExplode" to n
            "自爆" in s -> "explode" to 0
            else -> "" to 0
        }
    }
}
