package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.WolfAsk
import com.guixing.jixunying.model.WolfEvent
import com.guixing.jixunying.model.WolfGameRecord
import com.guixing.jixunying.model.WolfLive
import com.guixing.jixunying.model.WolfNote
import com.guixing.jixunying.model.WolfPresets
import com.guixing.jixunying.model.WolfRole
import com.guixing.jixunying.model.WolfSeat
import com.guixing.jixunying.model.WolfSeatPick
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * 集训场（侧栏第三栏，docs/狼人杀设计说明.md，2026-10-27 照第一代 G:\ai-discussion-app\src\wolf 重做）：
 * 开局页选「我当主持人」（全 AI，上帝视角，能暂停、一步一步、插话）或「我上桌玩」（真人座位，同一台设备轮流）；
 * 预设 8 套 + 自己加减 14 种身份，身份随机或手动指定，角色图鉴。
 * 对局页像群聊：顶上一排座位头像，中间 AI 带头像一句一句说（边出字边显示），法官的话夹在中间，底下是轮到你做的事。
 * 打完 AI 各说一句复盘感想；复盘页能看每个 AI 的心里话。
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
            Text("狼人杀", style = MaterialTheme.typography.titleMedium)
        }
        val l = live
        when {
            l != null && !l.over && ctl.wolfView.isBlank() -> WolfTable(ctl, state, l, wide)
            ctl.wolfView.isNotBlank() -> WolfReplay(ctl, state, ctl.wolfView, wide)
            l != null && ctl.wolfShowLast -> WolfTable(ctl, state, l, wide)
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

/** 侧栏「集训场」一栏：开一局、正在打的那局、打过的局。 */
@Composable
fun WolfSidebar(ctl: AppController, modifier: Modifier, onNavigate: () -> Unit) {
    val live by ctl.backend.store.wolf.collectAsState()
    val games = rememberWolfGames(ctl, live?.over to live?.record?.id)
    val playing = live?.takeIf { !it.over }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(6.dp))
        if (playing != null) {
            SideRow("🐺 正在打", playing.record.phase.ifBlank { "开局" } + " · " + playing.record.seats.size + " 人", ctl.wolfView.isBlank()) { ctl.wolfView = ""; onNavigate() }
        } else {
            SideRow("🐺 开一局狼人杀", "当主持人看 AI 打，或者上桌一起玩", ctl.wolfView.isBlank()) { ctl.wolfView = ""; ctl.wolfShowLast = false; onNavigate() }
        }
        val past = games.filter { it.id != playing?.record?.id }
        if (past.isNotEmpty()) {
            Text("打过的局", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
            for (g in past) SideRow(g.setupName.ifBlank { "${g.seats.size} 人局" } + " · " + resultText(g), WolfUiText.time(g.startedAt) + " · " + g.seats.joinToString("、") { it.name },
                ctl.wolfView == g.id) { ctl.wolfView = g.id; onNavigate() }
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

private fun resultText(g: WolfGameRecord) = when {
    g.winner == "good" -> "好人赢"
    g.winner == "wolf" -> "狼人赢"
    g.error.isNotBlank() -> g.error.take(12)
    else -> "没打完"
}

object WolfUiText {
    /** 「3 小时前」「10月27日」这种（公共代码里没有时区库，用 Devices.kt 的 formatTime）。 */
    fun time(ms: Long): String = if (ms <= 0) "" else formatTime(ms)
}

// ———————————————— 开局 ————————————————

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WolfLobby(ctl: AppController, state: AppState, wide: Boolean) {
    val members = state.members
    var host by remember { mutableStateOf(true) }
    var presetName by remember { mutableStateOf(WolfPresets.all[1].name) }
    val counts = remember { mutableStateOf(WolfPresets.all[1].roles) }
    val roles = WolfRole.entries.flatMap { r -> List(counts.value[r] ?: 0) { r } }
    val picks = remember { mutableStateListOf<WolfSeatPick>() }
    // 座位数跟着身份数走；主持人模式全是 AI；上桌模式默认 1 号是你
    LaunchedEffect(roles.size, host, members.size) {
        while (picks.size > roles.size) picks.removeAt(picks.lastIndex)
        while (picks.size < roles.size) {
            val i = picks.size
            picks += if (!host && i == 0 || members.isEmpty()) WolfSeatPick(humanName = if (i == 0) "我" else "玩家${i + 1}")
            else WolfSeatPick(memberId = members[(if (host) i else i - 1).mod(members.size)].id)
        }
        if (host) for (i in picks.indices) if (picks[i].memberId.isBlank() && members.isNotEmpty()) picks[i] = WolfSeatPick(memberId = members[i % members.size].id, role = picks[i].role)
        if (!host && picks.none { it.memberId.isBlank() } && picks.isNotEmpty()) picks[0] = WolfSeatPick(humanName = "我", role = picks[0].role)
        // 指定的身份这局没有了：改回随机
        for (i in picks.indices) picks[i].role?.let { r -> if (picks.count { it.role == r } > roles.count { it == r }) picks[i] = picks[i].copy(role = null) }
    }
    var step by remember { mutableStateOf(false) }
    // 私下判断默认关（2026-10-29 用户：「狼人杀跑的这么慢……这一局那不得跑过几个小时啊？」——实测第 1 天 15 分钟里它占了 7 分钟，
    // 只给测评用，桌上谁也看不到）；做测评时再打开
    var judge by remember { mutableStateOf(false) }
    var useExp by remember { mutableStateOf(true) }
    var guide by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val warns = WolfPresets.warnings(roles)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 820.dp)) {
            PageHeader("狼人杀", "法官是程序，AI 各用自己的模型，一步一步自己想、自己说。")
            // 两种玩法
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard("🎙️ 我当主持人", "全是 AI 打。你看得到所有身份和夜里谁做了什么，可以暂停、一步一步来，白天能插话。", host) { host = true }
                ModeCard("🙋 我上桌玩", "你（和朋友）坐几个座位，跟 AI 一起打。几个人就同一台设备轮流，轮到谁先挡住屏幕。", !host) { host = false }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard {
                FieldLabel("板子", "点一套预设，或者自己加减身份")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in WolfPresets.all) ChoiceChip("${p.name} ${p.players}人", presetName == p.name) { presetName = p.name; counts.value = p.roles }
                    ChoiceChip("自定义", presetName == "自定义") { presetName = "自定义" }
                }
                Text("一共 ${roles.size} 人：" + com.guixing.jixunying.model.WolfText.roles(roles).ifBlank { "还没选" }, style = MaterialTheme.typography.bodySmall,
                    color = Ext.c.subtle, modifier = Modifier.padding(top = 8.dp))
                for (w in warns) Text("⚠️ $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                // 2026-10-29 用户：「职业介绍的地方在哪儿？你不会说跟我开局之后再看吧？」——就在开局页这里，开局之前就能看，单独标一个小标题
                Text("身份介绍", style = MaterialTheme.typography.titleSmall)
                Text("每个身份能干什么、属于哪边；右边加减这局的人数。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle,
                    modifier = Modifier.padding(bottom = 4.dp))
                // 每个身份一行：名字、阵营、能干什么直接写在下面，旁边加减人数（2026-10-28 用户：「每个角色的说明在哪儿？」——以前藏在「角色图鉴」按钮里）。
                // 这局用到的排前面；没用到的收在「其余身份」里，点开也是一样带说明
                val used = WolfRole.entries.filter { (counts.value[it] ?: 0) > 0 }
                val unused = WolfRole.entries.filter { (counts.value[it] ?: 0) == 0 }
                for (r in used) RoleCounter(r, counts.value[r] ?: 0) { n ->
                    counts.value = (counts.value + (r to n)).filterValues { it > 0 }
                    presetName = WolfPresets.all.firstOrNull { it.roles == counts.value }?.name ?: "自定义"
                }
                TextButton(onClick = { guide = !guide }) { Text(if (guide) "收起其余身份" else "其余 ${unused.size} 种身份（点开看说明、往局里加）") }
                if (guide) for (r in unused) RoleCounter(r, 0) { n ->
                    counts.value = (counts.value + (r to n)).filterValues { it > 0 }
                    presetName = WolfPresets.all.firstOrNull { it.roles == counts.value }?.name ?: "自定义"
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard {
                FieldLabel("座位", if (host) "每个座位坐哪位 AI；身份默认随机发，也可以点右边指定" else "点左边选真人还是哪位 AI；身份默认随机发")
                picks.forEachIndexed { i, p -> SeatPickRow(i + 1, p, state, host, roles, picks) { picks[i] = it } }
                if (members.isEmpty()) Text("还没有 AI 成员：先到 设置 → AI 成员 加几位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            SectionCard {
                if (host) SwitchRow("一步一步来", "每一步（天黑、狼人行动、投票……）等你点「下一步」再走；不开就自动打，随时能暂停", step) { step = it }
                SwitchRow("记录 AI 的私下判断", "测评用，桌上谁也看不到：每天发言前、投票前让每个 AI 说它怀疑谁、几成把握。打开以后每天多两轮，一局慢将近一倍（谁超过 1 分钟没交就跳过）", judge) { judge = it }
                SwitchRow("带上以前的经验", "每局打完 AI 各总结一句经验，下局开打前给它看（照第一代）", useExp) { useExp = it }
                val ai = picks.count { it.memberId.isNotBlank() }
                Text("这局 $ai 个 AI。6 个 AI 实测一局调 36 次模型、约 0.38 元；开着私下判断大约翻倍，打完复盘每个 AI 再多一次；本地模型不花钱。",
                    style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Button(enabled = !busy && warns.none { "至少" in it || "没有狼" in it } && picks.size == roles.size && members.isNotEmpty(), onClick = {
                    busy = true; error = ""
                    scope.launch {
                        val r = ctl.backend.call(Command.WolfStart(roles, picks.toList(), presetName, stepMode = host && step, judge = judge, useExp = useExp))
                        busy = false
                        if (!r.ok) error = r.message.ifBlank { "开不了" } else { ctl.wolfView = ""; ctl.wolfShowLast = true }
                    }
                }) { Text(if (busy) "正在开…" else "开始") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeCard(title: String, desc: String, on: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.widthIn(max = 380.dp).clip(RoundedCornerShape(14.dp))
        .background(if (on) primary.copy(alpha = 0.10f) else cardFill())
        .border(if (on) 1.5.dp else 1.dp, if (on) primary else Ext.c.border, RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (on) primary else MaterialTheme.colorScheme.onSurface)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
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

/** 一种身份一行：「🐺 普通狼 · 狼人阵营」，下面一句能干什么，右边「− 2 ＋」。 */
@Composable
private fun RoleCounter(r: WolfRole, n: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
        .background(if (n > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
        .border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${r.icon} ${r.label} · " + when (r.faction) { "wolf" -> "狼人阵营"; "good" -> if (r.god) "好人·神职" else "好人"; else -> "第三方" },
                style = MaterialTheme.typography.labelLarge)
            Text(r.desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        TextButton(onClick = { if (n > 0) onChange(n - 1) }, enabled = n > 0) { Text("−") }
        Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { if (n < 8) onChange(n + 1) }) { Text("＋") }
    }
}

/** 一个座位：点左边选「真人」或哪位 AI 成员（主持人模式只有 AI）；真人填昵称；右边身份「随机」或指定。 */
@Composable
private fun SeatPickRow(no: Int, p: WolfSeatPick, state: AppState, host: Boolean, roles: List<WolfRole>, all: List<WolfSeatPick>, onChange: (WolfSeatPick) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var roleOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${no}号", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
        Box {
            val m = state.member(p.memberId)
            OutlinedButton(onClick = { open = true }) {
                Text(if (p.memberId.isBlank()) "🙋 真人" else "${m?.avatar ?: "🤖"} ${m?.name ?: "（不在了）"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(open, { open = false }) {
                if (!host) DropdownMenuItem(text = { Text("🙋 真人") }, onClick = { open = false; onChange(WolfSeatPick(humanName = p.humanName.ifBlank { "玩家$no" }, role = p.role)) })
                for (mm in state.members) DropdownMenuItem(text = { Text("${mm.avatar} ${mm.name}  ·  ${mm.modelId}") },
                    onClick = { open = false; onChange(WolfSeatPick(memberId = mm.id, role = p.role)) })
            }
        }
        Spacer(Modifier.width(8.dp))
        if (p.memberId.isBlank()) AppTextField(p.humanName, { onChange(p.copy(humanName = it.take(12))) }, Modifier.weight(1f), placeholder = "昵称")
        else Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Box {
            TextButton(onClick = { roleOpen = true }) { Text(p.role?.let { "${it.icon} ${it.label}" } ?: "随机", maxLines = 1) }
            DropdownMenu(roleOpen, { roleOpen = false }) {
                DropdownMenuItem(text = { Text("随机") }, onClick = { roleOpen = false; onChange(p.copy(role = null)) })
                for (r in roles.distinct()) {
                    val left = roles.count { it == r } - all.count { it.role == r } + (if (p.role == r) 1 else 0)
                    if (left > 0) DropdownMenuItem(text = { Text("${r.icon} ${r.label}") }, onClick = { roleOpen = false; onChange(p.copy(role = r)) })
                }
            }
        }
    }
}

// ———————————————— 对局 ————————————————

/** 聊天流里的一行。 */
private sealed interface WolfLine {
    data class Sys(val e: WolfEvent) : WolfLine
    data class Talk(val e: WolfEvent) : WolfLine
    data class Votes(val list: List<WolfEvent>) : WolfLine
    data class Thought(val n: WolfNote) : WolfLine
    data class Typing(val seat: Int, val text: String) : WolfLine
}

private fun linesOf(r: WolfGameRecord, visible: (WolfEvent) -> Boolean, notes: List<WolfNote>): List<WolfLine> {
    val out = mutableListOf<WolfLine>()
    val items = r.events.filter(visible).map { it.at to (it as Any) } + notes.map { it.at to (it as Any) }
    for ((_, x) in items.sortedBy { it.first }) {
        when (x) {
            is WolfNote -> out += WolfLine.Thought(x)
            is WolfEvent -> when (x.kind) {
                "vote" -> {
                    val last = out.lastOrNull()
                    if (last is WolfLine.Votes && last.list.first().day == x.day) out[out.lastIndex] = WolfLine.Votes(last.list + x) else out += WolfLine.Votes(listOf(x))
                }
                "speech", "pk", "lastWords", "review", "host" -> out += WolfLine.Talk(x)
                else -> out += WolfLine.Sys(x)
            }
        }
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WolfTable(ctl: AppController, state: AppState, live: WolfLive, wide: Boolean) {
    val r = live.record
    val humans = r.seats.filter { it.human }
    val hostMode = humans.isEmpty()
    // 谁在看：一个真人 → 一直是他；几个真人 → 0 = 挡着，点「我是几号」才是他；没有真人 → 主持人（上帝视角）
    var viewer by remember(r.id) { mutableStateOf(if (humans.size == 1) humans[0].no else 0) }
    // 这一夜已经看过一眼的真人（天亮清空）
    val seen = remember(r.id, r.day, "夜" in r.phase) { mutableStateListOf<Int>() }
    val god = hostMode || live.over
    val me = r.seats.firstOrNull { it.no == viewer }
    val typing by ctl.backend.store.wolfTyping.collectAsState()
    val scope = rememberCoroutineScope()
    fun send(c: Command) = scope.launch { val res = ctl.backend.call(c); if (!res.ok && res.message.isNotBlank()) ctl.toast(res.message) }

    // 夜里谁在想就说明谁有技能：上桌玩时夜里只显示自己和狼队友在想（主持人和打完以后都看得到）
    val night = "夜" in r.phase
    val thinking = if (god || !night) live.thinking else live.thinking.filter { n ->
        n == me?.no || (me != null && me.role.wolf && r.seats.firstOrNull { it.no == n }?.role?.wolf == true)
    }
    // 谁想了多久（2026-10-29 用户：「狼人杀跑的这么慢，半天了，还卡在思考」——看得出是谁在拖）：从这台设备看到它开始想算起
    val since = remember(r.id) { mutableStateMapOf<Int, Long>() }
    var clock by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(thinking) {
        val t = nowMillis()
        for (n in thinking) if (n !in since) since[n] = t
        for (n in since.keys.toList()) if (n !in thinking) since.remove(n)
    }
    LaunchedEffect(Unit) { while (true) { clock = nowMillis(); kotlinx.coroutines.delay(1000) } }
    val took: (Int) -> String = { n -> since[n]?.let { val sec = ((clock - it) / 1000).coerceAtLeast(0); "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}" }.orEmpty() }
    val lines = remember(r, viewer, god, typing, thinking) {
        linesOf(r, { e -> god || e.visibleTo.isEmpty() || (me != null && me.no in e.visibleTo) }, emptyList()) +
            thinking.map { WolfLine.Typing(it, typing[it].orEmpty()) }
    }
    val list = rememberLazyListState()
    val nearBottom by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 3 } }
    // 打开时翻到最新的地方；之后人在底下才跟着往下走（往上翻着看的时候不打扰）
    LaunchedEffect(r.id) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    LaunchedEffect(lines.size, typing) { if (nearBottom && lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        // 顶上：现在第几天哪一步 + 主持人的按钮
        Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 24.dp else 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (live.over) (if (live.reviewing) "复盘中…" else "结束 · " + resultText(r)) else r.phase.ifBlank { "开局" },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.label + if (live.paused && !live.over) " · 已暂停" else "", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!live.over) {
                if (hostMode) {
                    TextButton(onClick = { send(Command.WolfControl(if (live.paused) "resume" else "pause")) }) { Text(if (live.paused) "继续" else "暂停") }
                    TextButton(onClick = { send(Command.WolfControl(if (live.stepMode) "auto" else "step")) }) { Text(if (live.stepMode) "改成自动" else "一步一步") }
                }
                TextButton(onClick = { send(Command.WolfStop) }) { Text("停掉", color = MaterialTheme.colorScheme.error) }
            } else if (!live.reviewing) {
                TextButton(onClick = { ctl.wolfView = r.id }) { Text("看心里话") }
                TextButton(onClick = { ctl.wolfShowLast = false; ctl.backend.store.wolf.value = null }) { Text("再开一局") }
            }
        }
        SeatStrip(r, state, { s -> god || (me != null && (s.no == me.no || (me.role.wolf && s.role.wolf))) || s.revealed }, thinking, viewer, wide)
        HorizontalDivider(color = Ext.c.border)
        // 中间：像群聊一样
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (wide) 24.dp else 12.dp, vertical = 10.dp)) {
            items(lines.size) { i -> Box(Modifier.widthIn(max = 820.dp)) { LineView(lines[i], r, state, god, took) } }
        }
        HorizontalDivider(color = Ext.c.border)
        // 底下：轮到谁做什么
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = if (wide) 24.dp else 12.dp, vertical = 10.dp)) {
            Column(Modifier.widthIn(max = 820.dp)) {
                val hostAsks = live.asks.filter { it.seat == 0 }
                for (a in hostAsks) HostAskCard(a) { send(it) }
                when {
                    live.over -> Text(if (live.reviewing) "AI 们在说复盘感想……" else r.endReason.ifBlank { r.error.ifBlank { "这局结束了" } },
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    hostMode -> HostSay(ctl) { send(Command.WolfSay(it)) }
                    humans.size > 1 && viewer == 0 -> {
                        // 几个真人轮流：白天轮到谁叫谁；夜里不说谁有事（不然等于亮身份），活着的真人每人都看一眼
                        val waiting = live.asks.filter { it.seat != 0 }
                        val callOut = if (!night) waiting.mapNotNull { a -> r.seats.firstOrNull { it.no == a.seat } }
                            else if (waiting.isEmpty()) emptyList()
                            else humans.filter { it.alive && (it.no !in seen || waiting.any { a -> a.seat == it.no }) }
                        if (night && waiting.isNotEmpty()) Text("天黑了：活着的真人轮流看一眼（没事的人看完直接挡上），别让别人看到。",
                            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                        if (callOut.isEmpty()) Text(waitText(live.copy(thinking = thinking), r, took), style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                        for (s in callOut.distinctBy { it.no }) {
                            val a = waiting.firstOrNull { it.seat == s.no }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (night) "${s.no}号 ${s.name}" else "轮到 ${s.no}号 ${s.name}：${a?.what.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                                    Text("把设备交给 ${s.name}，别人别看。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                                }
                                Button(onClick = { viewer = s.no; seen += s.no }) { Text("我是 ${s.no}号") }
                            }
                        }
                    }
                    me != null -> {
                        MyRoleBar(me, r)
                        val mine = live.asks.firstOrNull { it.seat == me.no }
                        if (mine != null) AskPanel(ctl, mine, r) { c -> send(c); if (humans.size > 1) viewer = 0 }
                        else {
                            Text(if (!me.alive) "你已经出局了，可以接着看。" else if (night && humans.size > 1) "今晚你没有要做的事。" else waitText(live.copy(thinking = thinking), r, took), style = MaterialTheme.typography.bodySmall,
                                color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
                            if (humans.size > 1) TextButton(onClick = { viewer = 0 }) { Text("看完了，挡住屏幕") }
                        }
                    }
                }
            }
        }
    }
}

private fun waitText(live: WolfLive, r: WolfGameRecord, took: (Int) -> String = { "" }): String = when {
    live.paused -> "已暂停"
    live.thinking.isNotEmpty() -> "等 " + live.thinking.joinToString("、") { n ->
        (r.seats.firstOrNull { it.no == n }?.let { "${it.no}号 ${it.name}" } ?: "${n}号") + took(n).let { if (it.isBlank()) "" else "（想了 $it）" }
    } + "……"
    live.asks.isNotEmpty() -> "等 " + live.asks.joinToString("、") { a -> if (a.seat == 0) "主持人" else "${a.seat}号" } + "……"
    else -> "……"
}

/** 顶上一排座位：头像、几号、名字、看得到的身份；出局变灰，正在想的有圈。 */
@Composable
private fun SeatStrip(r: WolfGameRecord, state: AppState, showRole: (WolfSeat) -> Boolean, thinking: List<Int>, viewer: Int, wide: Boolean) {
    LazyRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (wide) 24.dp else 12.dp)) {
        items(r.seats) { s ->
            val c = if (showRole(s) && s.role.wolf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val ring = s.no in thinking || s.no == viewer
            Column(Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
                .background(if (s.alive) c.copy(alpha = 0.06f) else Color.Transparent)
                .border(if (ring) 1.5.dp else 0.dp, if (ring) c else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    SeatAvatar(s, state, 34)
                    if (!s.alive) Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Text("${s.no}号", style = MaterialTheme.typography.labelSmall, color = if (s.alive) c else Ext.c.subtle, fontWeight = FontWeight.SemiBold)
                Text(s.name, style = MaterialTheme.typography.labelSmall, color = if (s.alive) MaterialTheme.colorScheme.onSurface else Ext.c.subtle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (showRole(s)) "${s.role.icon}${s.role.label}" else if (s.no in thinking) "思考中" else " ",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeatAvatar(s: WolfSeat?, state: AppState, size: Int) {
    val m = s?.memberId?.takeIf { it.isNotBlank() }?.let { state.member(it) }
    Box(Modifier.alpha(if (s?.alive == false) 0.4f else 1f)) {
        MemberAvatar(m, size.dp, emoji = if (m == null) (if (s?.human == true) "🙋" else "🎙️") else null, color = if (m == null) 0xFFF59E0B else null)
    }
}

/** 聊天流里的一行怎么画。 */
@Composable
private fun LineView(l: WolfLine, r: WolfGameRecord, state: AppState, god: Boolean, took: (Int) -> String = { "" }) {
    fun seat(n: Int) = r.seats.firstOrNull { it.no == n }
    when (l) {
        is WolfLine.Sys -> {
            val e = l.e
            val private = e.visibleTo.isNotEmpty()
            val icon = when (e.kind) {
                "death" -> "💀"; "exile" -> "🗳️"; "peace" -> if (e.night || "平安夜" in e.text) "🌤️" else "⚖️"; "end" -> "🏆"
                "skill" -> "⚡"; "seerResult" -> "🔮"; "witchInfo" -> "🧪"; "wolfTalk" -> "🐺"
                else -> if (e.night) "🌙" else if ("天亮" in e.text) "☀️" else "📢"
            }
            val label = when {
                !private -> ""
                0 in e.visibleTo -> "法官记录 · "
                e.kind == "wolfTalk" || (e.visibleTo.size > 1) -> "狼队 · "
                god -> "${e.visibleTo.joinToString("、") { "${it}号" }}知道 · "
                else -> "只有你知道 · "
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text("$icon $label${e.text}", textAlign = TextAlign.Center,
                    style = if (e.kind in setOf("death", "exile", "end", "skill")) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    fontWeight = if (e.kind in setOf("death", "exile", "end", "skill", "peace")) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (private) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (private) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        is WolfLine.Votes -> {
            val by = l.list.filter { it.target != 0 }.groupBy { it.target }.entries.sortedByDescending { it.value.size }
            val none = l.list.filter { it.target == 0 }.map { it.actor }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)).padding(10.dp)) {
                Text("🗳️ 第 ${l.list.first().day} 天投票", style = MaterialTheme.typography.labelLarge)
                for ((t, vs) in by) Text("${t}号 ${seat(t)?.name.orEmpty()}  ←  ${vs.joinToString("、") { "${it.actor}号" }}（${vs.size} 票）",
                    style = MaterialTheme.typography.bodySmall)
                if (none.isNotEmpty()) Text("弃票：${none.joinToString("、") { "${it}号" }}", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
        is WolfLine.Talk -> {
            val e = l.e
            if (e.kind == "host") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    Column(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)).padding(10.dp)) {
                        Text("主持人", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(e.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return
            }
            val s = seat(e.actor)
            Bubble(s, state, (when (e.kind) { "pk" -> "PK"; "lastWords" -> "遗言"; "review" -> "复盘"; else -> "" }), e.text, god)
        }
        is WolfLine.Typing -> Bubble(seat(l.seat), state, took(l.seat).let { if (it.isBlank()) "" else "想了 $it" }, l.text.ifBlank { "正在想…" }, god, typing = true)
        is WolfLine.Thought -> {
            val n = l.n
            Text("💭 ${n.seat}号 " + (if (n.kind == "judge") "判断（最怀疑 ${n.most}号，把握 ${n.confidence}%）" else if (n.kind == "exp") "总结的经验" else n.stage) + "：" + n.text,
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 46.dp, top = 1.dp, bottom = 3.dp))
        }
    }
}

@Composable
private fun Bubble(s: WolfSeat?, state: AppState, tag: String, text: String, god: Boolean, typing: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        SeatAvatar(s, state, 34)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(listOfNotNull(s?.let { "${it.no}号 ${it.name}" }, tag.takeIf { it.isNotBlank() }, s?.takeIf { god }?.role?.let { "${it.icon}${it.label}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (typing) Ext.c.subtle else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun MyRoleBar(me: WolfSeat, r: WolfGameRecord) {
    // 技能说明默认就摊开（2026-10-28 用户找不到每个身份的说明），点一下收起
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background((if (me.role.wolf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary).copy(alpha = 0.08f))
        .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("你是 ${me.no}号 ${me.name} · ${me.role.icon} ${me.role.label}" + (me.origRole?.let { "（本来是${it.label}）" } ?: "") +
            (if (me.role.wolf) " · 狼队友：" + r.seats.filter { it.role.wolf && it.no != me.no }.joinToString("、") { "${it.no}号${it.name}" }.ifEmpty { "没有" } else "") +
            (if (open) "" else "  （点开看技能说明）"),
            style = MaterialTheme.typography.labelLarge, color = if (me.role.wolf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (open) {
            Text(me.role.desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
            Text(com.guixing.jixunying.model.WolfText.goal(me.role), style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
    }
}

/** 主持人能做的：一步一步时点「下一步」；某个 AI 调不通时选重试还是跳过。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostAskCard(a: WolfAsk, send: (Command) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
        .background(if (a.kind == "error") MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
        .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (a.kind == "error") "⚠️ ${a.what}" else a.text, style = MaterialTheme.typography.titleSmall)
            if (a.kind == "error") Text(a.text, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (a.kind == "error") {
            Button(onClick = { send(Command.WolfAnswer(a.id, text = "retry")) }) { Text("重试") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { send(Command.WolfAnswer(a.id, text = "skip")) }) { Text("跳过") }
        } else Button(onClick = { send(Command.WolfAnswer(a.id)) }) { Text("下一步") }
    }
}

/**
 * 主持人插话（全 AI 的局）：大家都看得到，AI 之后每一步都会看到。
 * 能打字也能点话筒说（2026-10-29 用户：「狼人杀依旧没有语音输入」——以前话筒只在上桌发言那里，主持人这一栏没有）。
 */
@Composable
private fun HostSay(ctl: AppController, say: (String) -> Unit) {
    val platform = LocalPlatform.current
    var text by remember { mutableStateOf("") }
    val dictation = rememberDictation(ctl, "wolf-host") { heard -> text = if (text.isBlank()) heard else "$text $heard" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 主持人是法官：只宣布规则和结果，不问玩家问题（2026-10-29 用户：「主持人不应该说三号你为什么改票，主持人是一个规则执行者」）
        AppTextField(text, { text = it }, Modifier.weight(1f), placeholder = "以主持人身份宣布一句（比如「本局女巫不能自救」「现在进入投票」），AI 都看得到")
        if (platform.canRecord) {
            Spacer(Modifier.width(6.dp))
            DictationButton(dictation, "说一句话，变成文字填进输入框（再点一下停下）")
        }
        Spacer(Modifier.width(8.dp))
        Button(enabled = text.isNotBlank(), onClick = { say(text.trim()); text = "" }) { Text("说") }
    }
}

/** 真人这一步怎么交：发言打字或说话（下面可以顺带自爆、决斗）；选人点头像；女巫点救不救、毒谁；商人选人和送什么。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskPanel(ctl: AppController, a: WolfAsk, r: WolfGameRecord, send: (Command.WolfAnswer) -> Unit) {
    fun seatName(n: Int) = r.seats.firstOrNull { it.no == n }?.let { "${it.no}号 ${it.name}" } ?: "${n}号"
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text("轮到你：${a.what}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (a.text.isNotBlank()) Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        when (a.kind) {
            "speech", "lastWords" -> {
                var text by remember(a.id) { mutableStateOf("") }
                var skill by remember(a.id) { mutableStateOf("") }
                var target by remember(a.id) { mutableStateOf(0) }
                val dictation = rememberDictation(ctl, a.id) { heard -> text = if (text.isBlank()) heard else "$text $heard" }
                Row(verticalAlignment = Alignment.Bottom) {
                    AppTextField(text, { text = it }, Modifier.weight(1f), singleLine = false, minLines = 2,
                        placeholder = if (a.kind == "lastWords") "遗言" else "说你的判断和理由")
                    if (LocalPlatform.current.canRecord) {
                        Spacer(Modifier.width(6.dp))
                        DictationButton(dictation, "说一段话，变成文字填进输入框（再点一下停下）")
                    }
                }
                if (a.options.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip("不用技能", skill == "") { skill = ""; target = 0 }
                        if ("explode" in a.options) ChoiceChip("💥 说完自爆", skill == "explode") { skill = "explode"; target = 0 }
                        if ("whiteExplode" in a.options) ChoiceChip("🤍 自爆并带走一个人", skill == "whiteExplode") { skill = "whiteExplode" }
                        if ("duel" in a.options) ChoiceChip("⚔️ 和一个人决斗", skill == "duel") { skill = "duel" }
                    }
                    if (skill == "whiteExplode" || skill == "duel") FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in a.allowed) ChoiceChip(seatName(n), target == n) { target = n }
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    Button(enabled = (skill != "whiteExplode" && skill != "duel") || target != 0,
                        onClick = { send(Command.WolfAnswer(a.id, text = text, skill = skill, target = target)) }) { Text("说完了") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { send(Command.WolfAnswer(a.id, text = "过。")) }) { Text("过") }
                }
            }
            "witch" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.canSave) Button(onClick = { send(Command.WolfAnswer(a.id, save = true)) }) { Text("🧪 用解药救") }
                for (n in a.poisonable) OutlinedButton(onClick = { send(Command.WolfAnswer(a.id, poison = n)) }) { Text("☠️ 毒 " + seatName(n)) }
                TextButton(onClick = { send(Command.WolfAnswer(a.id)) }) { Text("今晚都不用") }
            }
            "merchant" -> {
                var gift by remember(a.id) { mutableStateOf("check") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("🔮 查验", gift == "check") { gift = "check" }
                    ChoiceChip("🛡️ 护盾", gift == "shield") { gift = "shield" }
                    ChoiceChip("☠️ 毒药", gift == "poison") { gift = "poison" }
                }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in a.allowed) OutlinedButton(onClick = { send(Command.WolfAnswer(a.id, target = n, gift = gift)) }) { Text("送给 " + seatName(n)) }
                    TextButton(onClick = { send(Command.WolfAnswer(a.id, target = 0)) }) { Text("今晚先不送") }
                }
            }
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in a.allowed) OutlinedButton(onClick = { send(Command.WolfAnswer(a.id, target = n)) }) { Text(seatName(n)) }
                if (a.allowZero) TextButton(onClick = { send(Command.WolfAnswer(a.id, target = 0)) }) {
                    Text(when (a.what) { "投票" -> "弃票"; "开枪" -> "不开枪"; "守护" -> "今晚不守"; "下毒" -> "不用"; "复制" -> "不复制，当平民"; else -> "不选" })
                }
            }
        }
    }
}

// ———————————————— 复盘 ————————————————

@Composable
private fun WolfReplay(ctl: AppController, state: AppState, id: String, wide: Boolean) {
    val live by ctl.backend.store.wolf.collectAsState()
    val games = rememberWolfGames(ctl, id to live?.over)
    val r = games.firstOrNull { it.id == id }
    var thoughts by remember(id) { mutableStateOf(true) }
    if (r == null) { Text("读取中…", color = Ext.c.subtle, modifier = Modifier.padding(24.dp)); return }
    val lines = remember(r, thoughts) { linesOf(r, { true }, if (thoughts) r.notes.filter { it.kind == "think" || it.kind == "judge" || it.kind == "exp" } else emptyList()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 24.dp else 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("复盘 · " + resultText(r), style = MaterialTheme.typography.titleMedium)
                Text(r.label + " · " + WolfUiText.time(r.startedAt) + (if (r.calls > 0) " · 调了 ${r.calls} 次模型" else ""),
                    style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { thoughts = !thoughts }) { Text(if (thoughts) "收起心里话" else "显示心里话") }
            TextButton(onClick = { ctl.wolfView = ""; ctl.wolfShowLast = false }) { Text("开一局") }
        }
        SeatStrip(r, state, { true }, emptyList(), 0, wide)
        HorizontalDivider(color = Ext.c.border)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (wide) 24.dp else 12.dp, vertical = 10.dp)) {
            items(lines.size) { i -> Box(Modifier.widthIn(max = 820.dp)) { LineView(lines[i], r, state, true) } }
        }
    }
}
