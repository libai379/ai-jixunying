package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.WolfAsk
import com.guixing.jixunying.model.WolfBoard
import com.guixing.jixunying.model.WolfEvent
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfLive
import com.guixing.jixunying.model.WolfSeat
import com.guixing.jixunying.model.WolfSeatPick
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * 集训场（侧栏第三栏，docs/狼人杀设计说明.md 第 5 节第二期，2026-10-27）：开一局狼人杀，座位可以是真人也可以是 AI 成员。
 * 真人在这台设备上轮流操作：只有一个真人时一直显示他那份；几个真人时，轮到谁先挡住屏幕，那个人点「我是 X号」才显示他的身份和要做的事。
 * 全是 AI 的局是上帝视角。几台手机各自进一个房间是第三期，还没做。
 */
@Composable
fun WolfScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val live by ctl.backend.store.wolf.collectAsState()
    // 刚打开（或者手机刚连上电脑）：问一下有没有正在打的局
    LaunchedEffect(ctl.backend) {
        val r = ctl.backend.call(Command.WolfCurrent)
        if (r.ok && r.data.isNotBlank()) runCatching { AppJson.decodeFromString(WolfLive.serializer(), r.data) }.getOrNull()?.let { ctl.backend.store.wolf.value = it }
    }
    Column(Modifier.fillMaxSize()) {
        if (!wide) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
            Text("集训场", style = MaterialTheme.typography.titleMedium)
        }
        val l = live
        when {
            l != null && !l.over -> WolfTable(ctl, l, wide)
            ctl.wolfView.isNotBlank() -> WolfReplay(ctl, ctl.wolfView, wide)
            l != null && ctl.wolfShowLast -> WolfTable(ctl, l, wide)
            else -> WolfLobby(ctl, state, wide)
        }
    }
}

/** 打过的局（侧栏里列出来，点开看复盘）。rev 变了就重新取。 */
@Composable
fun rememberWolfGames(ctl: AppController, rev: Any?): List<WolfGameRecord> {
    val games by produceState(emptyList<WolfGameRecord>(), rev, ctl.backend) {
        val r = ctl.backend.call(Command.WolfList)
        if (r.ok) value = runCatching { AppJson.decodeFromString(ListSerializer(WolfGameRecord.serializer()), r.data) }.getOrDefault(emptyList())
    }
    return games
}

/** 侧栏「集训场」一栏：正在打的那局 + 打过的局。 */
@Composable
fun WolfSidebar(ctl: AppController, modifier: Modifier, onNavigate: () -> Unit) {
    val live by ctl.backend.store.wolf.collectAsState()
    val games = rememberWolfGames(ctl, live?.over to live?.record?.id)
    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(6.dp))
        SideRow("开一局", "狼人杀：真人和 AI 一起玩", ctl.wolfView.isBlank() && live?.over != false) {
            ctl.wolfView = ""; ctl.wolfShowLast = false; onNavigate()
        }
        live?.takeIf { !it.over }?.let { l ->
            SideRow("正在打", "${l.record.board.roles.size} 人局 · 第 ${l.record.day.coerceAtLeast(1)} 天", ctl.wolfView.isBlank()) { ctl.wolfView = ""; onNavigate() }
        }
        if (games.isNotEmpty()) {
            Text("打过的局", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
            for (g in games) if (g.id != live?.takeIf { !it.over }?.record?.id) {
                SideRow(gameTitle(g), gameSub(g), ctl.wolfView == g.id) { ctl.wolfView = g.id; onNavigate() }
            }
        }
    }
}

@Composable
private fun SideRow(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun gameTitle(g: WolfGameRecord) = g.seats.joinToString("、") { it.name }
private fun gameSub(g: WolfGameRecord) = "${g.seats.size} 人局 · " + when {
    g.winner == "good" -> "好人赢"
    g.winner == "wolf" -> "狼人赢"
    g.error.isNotBlank() -> g.error.take(20)
    else -> "没打完"
} + " · " + WolfText.time(g.startedAt)

// ———————————————— 开局 ————————————————

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WolfLobby(ctl: AppController, state: AppState, wide: Boolean) {
    var board by remember { mutableStateOf(WolfBoard.SIX) }
    val members = state.members
    // 默认：1 号是你，其余按 AI 成员轮着坐
    val picks = remember { mutableStateListOf<WolfSeatPick>() }
    LaunchedEffect(board, members.size) {
        val n = board.roles.size
        while (picks.size > n) picks.removeAt(picks.lastIndex)
        while (picks.size < n) {
            val i = picks.size
            picks += if (i == 0 || members.isEmpty()) WolfSeatPick(humanName = if (i == 0) "我" else "玩家${i + 1}")
            else WolfSeatPick(memberId = members[(i - 1) % members.size].id)
        }
    }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            PageHeader("狼人杀", "几个真人和几个 AI 一起玩。法官是程序，AI 每一步自己想、自己说；真人在这里发言、投票。" +
                "几个真人用同一台设备轮流操作：轮到谁，屏幕先挡住，那个人点「我是几号」才看到自己的身份。")
            SectionCard {
                FieldLabel("板子")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in WolfBoard.entries) ChoiceChip(b.label, b == board) { board = b }
                }
                Spacer(Modifier.height(12.dp))
                FieldLabel("座位", "身份开局时随机发")
                picks.forEachIndexed { i, p ->
                    SeatPickRow(i + 1, p, state) { picks[i] = it }
                }
                val humans = picks.count { it.memberId.isBlank() }
                Text(when (humans) {
                    0 -> "全是 AI：你在旁边看（上帝视角，所有身份都看得到）。"
                    1 -> "1 个真人、${picks.size - 1} 个 AI。"
                    else -> "$humans 个真人、${picks.size - humans} 个 AI：轮到谁就把设备交给谁。"
                }, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 8.dp))
                Text("AI 每一步都调一次它的模型，一局大约几十次（上次 6 个 AI 一局 36 次，约 0.38 元）；本地模型不花钱。",
                    style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Button(enabled = !busy, onClick = {
                    busy = true; error = ""
                    scope.launch {
                        val r = ctl.backend.call(Command.WolfStart(board, picks.toList()))
                        busy = false
                        if (!r.ok) error = r.message.ifBlank { "开不了" } else { ctl.wolfView = ""; ctl.wolfShowLast = true }
                    }
                }) { Text(if (busy) "正在开…" else "开始") }
            }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, on: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.clip(RoundedCornerShape(50))
        .background(if (on) primary.copy(alpha = 0.12f) else Color.Transparent)
        .border(1.dp, if (on) primary.copy(alpha = 0.5f) else Ext.c.border, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 一个座位：点左边选「真人」或哪位 AI 成员；真人的话右边填昵称。 */
@Composable
private fun SeatPickRow(no: Int, p: WolfSeatPick, state: AppState, onChange: (WolfSeatPick) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${no}号", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
        Box {
            val label = if (p.memberId.isBlank()) "真人" else state.member(p.memberId)?.name ?: "（成员不在了）"
            OutlinedButton(onClick = { open = true }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("真人") }, onClick = { open = false; onChange(WolfSeatPick(humanName = p.humanName.ifBlank { "玩家$no" })) })
                for (m in state.members) DropdownMenuItem(text = { Text(m.name) }, onClick = { open = false; onChange(WolfSeatPick(memberId = m.id)) })
            }
        }
        Spacer(Modifier.width(8.dp))
        if (p.memberId.isBlank()) AppTextField(p.humanName, { onChange(p.copy(humanName = it.take(12))) }, Modifier.weight(1f), placeholder = "昵称")
        else Text(state.member(p.memberId)?.modelId.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// ———————————————— 对局 ————————————————

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WolfTable(ctl: AppController, live: WolfLive, wide: Boolean) {
    val r = live.record
    val humans = r.seats.filter { it.human }
    // 谁在看：一个真人 → 一直是他；几个真人 → 0 = 挡着，点「我是几号」才是他；没有真人 → 上帝视角
    var viewer by remember(r.id) { mutableStateOf(if (humans.size == 1) humans[0].no else 0) }
    val god = humans.isEmpty() || live.over
    val me = r.seats.firstOrNull { it.no == viewer }
    val myAsk = live.asks.firstOrNull { it.seat == viewer }
    val waiting = live.asks.filter { a -> r.seats.firstOrNull { it.no == a.seat }?.human == true }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(r.events.size, live.asks.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 16.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.board.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!live.over) TextButton(onClick = { scope.launch { ctl.backend.call(Command.WolfStop) } }) { Text("停掉这局") }
                else TextButton(onClick = { ctl.wolfShowLast = false; ctl.backend.store.wolf.value = null }) { Text("再开一局") }
            }
            // 座位
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in r.seats) SeatChip(s, roleVisible(s, me, god, r), live.thinking.contains(s.no), s.no == viewer && humans.size > 1)
            }
            Spacer(Modifier.height(12.dp))
            // 记录（只显示这个人看得到的）
            for (e in r.events) if (eventVisible(e, me, god)) EventLine(e, r, god)
            if (live.thinking.isNotEmpty()) Text("正在想：" + live.thinking.joinToString("、") { "${it}号" }, style = MaterialTheme.typography.bodySmall,
                color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
            if (live.over) {
                Spacer(Modifier.height(10.dp))
                SectionCard {
                    Text(when (r.winner) { "good" -> "好人赢了"; "wolf" -> "狼人赢了"; else -> r.error.ifBlank { "没分出胜负" } },
                        style = MaterialTheme.typography.titleMedium)
                    Text(r.seats.joinToString("，") { "${it.no}号${it.name}：${it.role.label}" }, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    TextButton(onClick = { ctl.wolfView = r.id }) { Text("看复盘（每个 AI 心里怎么想的）") }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 要真人做的事
            if (!live.over) when {
                humans.size > 1 && viewer == 0 -> {
                    if (waiting.isEmpty()) Text("等 AI……", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    for (a in waiting) {
                        val s = r.seats.first { it.no == a.seat }
                        SectionCard(Modifier.padding(bottom = 8.dp)) {
                            Text("轮到 ${s.no}号（${s.name}）：${a.what}", style = MaterialTheme.typography.titleSmall)
                            Text("把设备交给 ${s.name}，别人别看。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                            Button(onClick = { viewer = s.no }, modifier = Modifier.padding(top = 6.dp)) { Text("我是 ${s.no}号，打开") }
                        }
                    }
                }
                me != null -> {
                    MyRoleCard(me, r)
                    Spacer(Modifier.height(8.dp))
                    if (myAsk != null) AskPanel(myAsk, r) { cmd ->
                        scope.launch { ctl.backend.call(cmd) }
                        if (humans.size > 1) viewer = 0
                    } else Text(if (!me.alive) "你已经出局了，可以接着看。" else "等别人……", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    if (humans.size > 1 && myAsk == null) TextButton(onClick = { viewer = 0 }) { Text("挡住屏幕") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun roleVisible(s: WolfSeat, me: WolfSeat?, god: Boolean, r: WolfGameRecord): Boolean =
    god || (me != null && (s.no == me.no || (me.role.wolf && s.role.wolf)))

private fun eventVisible(e: WolfEvent, me: WolfSeat?, god: Boolean): Boolean =
    god || e.visibleTo.isEmpty() || (me != null && me.no in e.visibleTo)

@Composable
private fun SeatChip(s: WolfSeat, showRole: Boolean, thinking: Boolean, isMe: Boolean) {
    val c = if (!s.alive) Ext.c.subtle else if (showRole && s.role.wolf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = if (s.alive) 0.10f else 0.05f))
        .border(if (isMe || thinking) 1.5.dp else 0.dp, if (isMe || thinking) c else Color.Transparent, RoundedCornerShape(10.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("${s.no}号 ${s.name}" + if (!s.alive) "（出局）" else "", style = MaterialTheme.typography.labelLarge, color = c)
        Text((if (s.human) "真人" else "AI") + if (showRole) " · ${s.role.label}" else "", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

@Composable
private fun EventLine(e: WolfEvent, r: WolfGameRecord, god: Boolean = true) {
    val name = r.seats.firstOrNull { it.no == e.actor }?.let { "${it.no}号 ${it.name}" }.orEmpty()
    val private = e.visibleTo.isNotEmpty()
    when (e.kind) {
        "speech", "lastWords" -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp)) {
            Text(name + if (e.kind == "lastWords") " · 遗言" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(e.text, style = MaterialTheme.typography.bodyMedium)
        }
        "vote" -> Text(e.text, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        else -> Text((if (!private) "" else if (god) "（私下）" else "（只有你看得到）") + e.text, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (e.kind == "death" || e.kind == "exile" || e.kind == "end" || e.kind == "peace") FontWeight.SemiBold else FontWeight.Normal,
            color = if (private) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 3.dp))
    }
}

@Composable
private fun MyRoleCard(me: WolfSeat, r: WolfGameRecord) {
    SectionCard {
        Text("你是 ${me.no}号 ${me.name}，身份：${me.role.label}", style = MaterialTheme.typography.titleSmall,
            color = if (me.role.wolf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (me.role.wolf) Text("狼队友：" + r.seats.filter { it.role.wolf && it.no != me.no }.joinToString("、") { "${it.no}号${it.name}" }.ifEmpty { "没有" },
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    }
}

/** 真人这一步怎么交：发言打字；选人点号码；女巫点救不救、毒谁。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskPanel(a: WolfAsk, r: WolfGameRecord, send: (Command.WolfAnswer) -> Unit) {
    SectionCard {
        Text(a.what, style = MaterialTheme.typography.titleSmall)
        Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        fun seatName(n: Int) = r.seats.firstOrNull { it.no == n }?.let { "${it.no}号 ${it.name}" } ?: "${n}号"
        when (a.kind) {
            "speech", "lastWords" -> {
                var text by remember(a.id) { mutableStateOf("") }
                AppTextField(text, { text = it }, singleLine = false, minLines = 3, placeholder = if (a.kind == "speech") "说你的判断和理由" else "遗言")
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { send(Command.WolfAnswer(a.id, text = text)) }) { Text("说完了") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { send(Command.WolfAnswer(a.id, text = "过。")) }) { Text("过") }
                }
            }
            "witch" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.canSave) Button(onClick = { send(Command.WolfAnswer(a.id, save = true)) }) { Text("用解药救") }
                    for (n in a.poisonable) OutlinedButton(onClick = { send(Command.WolfAnswer(a.id, poison = n)) }) { Text("毒 " + seatName(n)) }
                    TextButton(onClick = { send(Command.WolfAnswer(a.id)) }) { Text("今晚都不用") }
                }
            }
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in a.allowed) OutlinedButton(onClick = { send(Command.WolfAnswer(a.id, target = n)) }) { Text(seatName(n)) }
                if (a.allowZero) TextButton(onClick = { send(Command.WolfAnswer(a.id, target = 0)) }) { Text(if (a.what == "投票") "弃票" else "不选") }
            }
        }
    }
}

// ———————————————— 复盘 ————————————————

@Composable
private fun WolfReplay(ctl: AppController, id: String, wide: Boolean) {
    val live by ctl.backend.store.wolf.collectAsState()
    val games = rememberWolfGames(ctl, id to live?.over)
    val r = games.firstOrNull { it.id == id }
    var thoughts by remember(id) { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 16.dp)) {
        Column(Modifier.widthIn(max = 760.dp)) {
            if (r == null) { Text("读取中…", color = Ext.c.subtle); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("复盘 · " + WolfText.time(r.startedAt), style = MaterialTheme.typography.titleMedium)
                    Text(r.board.label + " · " + when (r.winner) { "good" -> "好人赢"; "wolf" -> "狼人赢"; else -> r.error.ifBlank { "没打完" } } +
                        (if (r.calls > 0) " · 调了 ${r.calls} 次模型" else ""), style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                }
                TextButton(onClick = { thoughts = !thoughts }) { Text(if (thoughts) "收起心里话" else "显示心里话") }
            }
            Spacer(Modifier.height(8.dp))
            for (s in r.seats) Text("${s.no}号 ${s.name}：${s.role.label}" + (if (s.human) "（真人）" else if (s.modelLabel.isNotBlank()) "（${s.modelLabel}）" else ""),
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            // 事件和私下想法按时间排在一起
            val items = r.events.map { it.at to (it as Any) } + (if (thoughts) r.notes.filter { it.kind == "think" || it.kind == "judge" }.map { it.at to (it as Any) } else emptyList())
            for ((_, x) in items.sortedBy { it.first }) when (x) {
                is WolfEvent -> EventLine(x, r)
                is com.guixing.jixunying.model.WolfNote -> {
                    val s = r.seats.firstOrNull { it.no == x.seat }
                    Text("心里话 · ${x.seat}号${s?.name.orEmpty()}" + (if (x.kind == "judge") "（最怀疑 ${x.most}号，把握 ${x.confidence}%）" else "（${x.stage}）") + "：" + x.text,
                        style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

object WolfText {
    /** 「3 小时前」「10月27日」这种（公共代码里没有时区库，用 Devices.kt 的 formatTime）。 */
    fun time(ms: Long): String = if (ms <= 0) "" else formatTime(ms)
}
