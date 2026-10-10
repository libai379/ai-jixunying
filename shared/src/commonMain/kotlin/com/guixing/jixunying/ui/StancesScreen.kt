package com.guixing.jixunying.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.RecorderPick
import com.guixing.jixunying.model.StanceCard
import com.guixing.jixunying.model.StanceEntry
import com.guixing.jixunying.model.StanceTopic
import com.guixing.jixunying.model.Stances
import kotlinx.serialization.builtins.ListSerializer

/** 取议题：convId 留空 = 全部对话的。rev 变了（对话上的 stanceAt）就重新取。 */
@Composable
fun rememberStanceTopics(ctl: AppController, convId: String, rev: Any): List<StanceTopic> {
    val topics by produceState(emptyList<StanceTopic>(), convId, rev, ctl.backend) {
        val r = ctl.backend.call(Command.StanceList(convId))
        if (r.ok) value = runCatching { AppJson.decodeFromString(ListSerializer(StanceTopic.serializer()), r.data) }.getOrDefault(emptyList())
    }
    return topics
}

/** 立场档案页：每位成员一张卡，下面是全部议题（能标对错）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StancesScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val topics = rememberStanceTopics(ctl, "", state.conversations.map { it.id to it.stanceAt })
    var filter by remember { mutableStateOf("全部") }
    val recorder = RecorderPick.pick(state)?.second

    Column(Modifier.fillMaxSize()) {
        if (!wide) Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
            Text("立场档案", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 20.dp)) {
            Column(Modifier.widthIn(max = 860.dp)) {
                PageHeader("立场档案", "群聊里大家独立作答时，记录员记下每位成员一开始怎么说；之后谁改了口、为什么改（被说服、跟风、迎合你、自己查证）也记下来。" +
                    "你在议题上标一下谁说对了，就能看出谁首答准、谁容易被带偏。")
                BgProblemsCard(ctl, state.bgProblems.filter { it.job == com.guixing.jixunying.model.BgJob.STANCES })
                SectionCard {
                    SwitchRow("记立场档案", "群聊每轮答完由记录员" + (recorder?.let { "（$it）" } ?: "") + "看一遍，一次只花很少的 token；单聊不记", state.settings.memory.stances) { on ->
                        ctl.updateSettings { it.copy(memory = it.memory.copy(stances = on)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (topics.isEmpty()) {
                    SectionCard {
                        Text("还没有记录", style = MaterialTheme.typography.titleSmall)
                        Text("建一个有几位成员的群聊（独立作答），问一个有明确答案或结论的问题，比如「一根绳子对折再对折，从中间剪一刀，变成几段？」。" +
                            "大家答完，这里就会出现这道题，以及每位成员的立场。闲聊、画图、写文章这类没有立场可比的不记。",
                            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
                    }
                    return@Column
                }
                val ids = state.members.map { it.id }.filter { id -> topics.any { t -> t.entries.any { it.memberId == id } } }
                val countAi = state.settings.memory.aiVerify && state.settings.memory.countAiVerdict
                val cards = Stances.cards(topics, ids, countAi)
                val mine = topics.count { it.verdict.isNotEmpty() }
                val verified = topics.count { it.verdict.isEmpty() && it.aiVerdict.isNotEmpty() && it.aiVerdict != StanceTopic.UNCLEAR }
                val aiNote = if (verified == 0) "" else "，AI 核实 $verified 题" + if (countAi) "（一起算进下面的数字）" else "（没算进下面的数字）"
                FieldLabel("成员", "你标了 $mine 题$aiNote。标了对错的题越多，数字越有意义")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.forEach { StanceCardView(state, it, if (wide) Modifier.width(262.dp) else Modifier.fillMaxWidth()) }
                }
                Spacer(Modifier.height(18.dp))
                val unmarked = topics.count { it.verdict.isEmpty() }
                FieldLabel("议题", "${topics.size} 个" + if (unmarked > 0) "，$unmarked 个还没标对错" else "")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部", "有分歧", "有人改口", "没标对错").forEach { f ->
                        Pill(f, if (filter == f) MaterialTheme.colorScheme.primary else Ext.c.subtle) { filter = f }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val shown = topics.sortedByDescending { it.createdAt }.filter { t ->
                    when (filter) {
                        "有分歧" -> t.entries.filter { it.first }.map { it.option }.filter { it != StanceTopic.UNCLEAR }.distinct().size > 1
                        "有人改口" -> t.entries.any { it.why.isNotEmpty() && it.why != Stances.HOLD }
                        "没标对错" -> t.verdict.isEmpty()
                        else -> true
                    }
                }
                if (shown.isEmpty()) Text("没有符合条件的议题", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                shown.forEach { t ->
                    val conv = state.conversation(t.convId)
                    StanceTopicCard(ctl, state, t, conv) {
                        ctl.focusMessageId = t.askId.ifEmpty { null }
                        ctl.openConversation(t.convId)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun StanceCardView(state: AppState, c: StanceCard, modifier: Modifier) {
    val m = state.member(c.memberId)
    SectionCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(m, 30.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m?.name ?: "已移除的成员", style = MaterialTheme.typography.titleSmall)
                Text("${m?.modelId.orEmpty()} · 参与 ${c.topics} 题", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        StatLine("首答准确率", Stances.percent(c.firstRight, c.judged), if (c.judged == 0) "还没标对错" else "${c.judged} 题对 ${c.firstRight}")
        StatLine("坚持度", Stances.percent(c.held, c.challenged), if (c.challenged == 0) "没被质疑过" else "质疑 ${c.challenged} 次坚持 ${c.held}")
        Spacer(Modifier.height(6.dp))
        Text("改口：跟风 ${c.follow} · 迎合你 ${c.pleaseUser} · 被说服 ${c.persuaded} · 自己查证 ${c.selfCheck}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("被带偏 ${c.misled} · 被纠正 ${c.corrected} · 说服别人 ${c.convinced}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatLine(label: String, value: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
        Text(hint, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 一个议题：问题、各个立场谁首答选了它、现在谁站在这边、谁改了口，以及标对错。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StanceTopicCard(ctl: AppController, state: AppState, t: StanceTopic, conv: Conversation?, onOpenConv: (() -> Unit)? = null) {
    fun name(id: String) = state.member(id)?.name ?: "已移除的成员"
    val members = t.entries.map { it.memberId }.distinct()
    val firstOf = members.associateWith { id -> t.entries.firstOrNull { it.memberId == id && it.first } ?: t.entries.first { it.memberId == id } }
    val nowOf = members.associateWith { id -> t.latestOf(id)!! }
    var confirmDelete by remember { mutableStateOf(false) }
    var verifying by remember(t.id) { mutableStateOf(false) }
    SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(t.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(formatTime(t.createdAt), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
        if (onOpenConv != null && conv != null) {
            Text("来自「${conv.title}」 · 看原话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenConv).padding(vertical = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        val keys = t.options.map { it.key } + (if (nowOf.values.any { it.option == StanceTopic.UNCLEAR } || firstOf.values.any { it.option == StanceTopic.UNCLEAR }) listOf(StanceTopic.UNCLEAR) else emptyList())
        keys.forEach { k ->
            val text = t.option(k)?.text ?: "没给出明确结论"
            val first = members.filter { firstOf[it]?.option == k }
            val now = members.filter { nowOf[it]?.option == k }
            val right = t.verdict == k
            val wrong = t.verdict.isNotEmpty() && t.verdict != StanceTopic.OPEN && k != StanceTopic.UNCLEAR && !right
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Pill(if (k == StanceTopic.UNCLEAR) "?" else k, when {
                    right -> Ext.c.success
                    wrong -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        if (right) Icon(Icons.Rounded.Check, "对", Modifier.padding(start = 4.dp).size(16.dp), tint = Ext.c.success)
                        if (wrong) Icon(Icons.Rounded.Close, "错", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                    val line = buildList {
                        add("首答：" + first.joinToString("、", transform = ::name).ifEmpty { "没人" })
                        if (now != first) add("现在：" + now.joinToString("、", transform = ::name).ifEmpty { "没人" })
                    }.joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
            }
        }
        // 改口记录（谁、从哪到哪、为什么）
        val changes = t.entries.sortedBy { it.time }.filter { it.why.isNotEmpty() && it.why != Stances.HOLD }
        val held = t.entries.filter { it.why == Stances.HOLD }.map { it.memberId }.distinct()
        if (changes.isNotEmpty() || held.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            changes.forEach { e ->
                val prev = t.entries.filter { it.memberId == e.memberId && it.time < e.time }.maxByOrNull { it.time }
                val by = if (e.by.isNotEmpty()) "，因为${name(e.by)}" else ""
                Text("${name(e.memberId)} 改口 ${prev?.option ?: "?"}→${e.option}：${e.why}$by" + (if (e.reason.isNotBlank()) "。${e.reason}" else ""),
                    style = MaterialTheme.typography.labelSmall, color = whyColor(e.why))
            }
            if (held.isNotEmpty()) Text("被追问后坚持：" + held.joinToString("、", transform = ::name), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
        Spacer(Modifier.height(8.dp))
        // AI 核实：裁判自己判了一次谁对（能查就联网查了）；用户没标的时候显示，用户标过就收起
        if (t.aiVerdict.isNotEmpty() || t.aiReason.isNotEmpty()) {
            AiVerifyRow(ctl, state, t, ::name)
            Spacer(Modifier.height(8.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("谁说对了？", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
            fun mark(v: String) = ctl.run(Command.StanceMark(t.id, if (t.verdict == v) "" else v), quiet = true)
            t.options.forEach { o -> ToggleChip("${o.key} 对", Icons.Rounded.Check, t.verdict == o.key) { mark(o.key) } }
            ToggleChip("都不对", Icons.Rounded.Close, t.verdict == StanceTopic.NONE) { mark(StanceTopic.NONE) }
            ToggleChip("没有对错", Icons.Rounded.RemoveCircleOutline, t.verdict == StanceTopic.OPEN) { mark(StanceTopic.OPEN) }
            if (state.settings.memory.aiVerify) {
                TextButton(enabled = !verifying, onClick = {
                    verifying = true
                    ctl.run(Command.VerifyStance(t.id), quiet = true) { verifying = false }
                }) { Text(if (verifying) "正在核实…" else "重新核实", style = MaterialTheme.typography.labelMedium) }
            }
            TextButton(onClick = { confirmDelete = true }) { Text("记错了，删掉", style = MaterialTheme.typography.labelMedium) }
        }
    }
    if (confirmDelete) AppDialog("删掉这个议题？", { confirmDelete = false }, width = 420.dp, actions = {
        TextButton({ confirmDelete = false }) { Text("取消") }
        TextButton({ confirmDelete = false; ctl.run(Command.StanceDelete(t.id), "删掉了") }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
    }) {
        Text("「${t.question}」的记录会删掉，聊天记录不受影响。记录员把闲聊当成议题、或者立场归错了，就删掉它。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun whyColor(why: String) = when (why) {
    Stances.FOLLOW, Stances.PLEASE_USER -> Ext.c.warning
    Stances.PERSUADED, Stances.SELF_CHECK -> MaterialTheme.colorScheme.primary
    else -> Ext.c.subtle
}

/** AI 核实的结论：判的是哪个立场、理由、出处，以及「采纳」。 */
@Composable
private fun AiVerifyRow(ctl: AppController, state: AppState, t: StanceTopic, name: (String) -> String) {
    val platform = LocalPlatform.current
    val taken = t.verdict.isNotEmpty()
    val mine = t.verdict == t.aiVerdict && taken
    val text = when (t.aiVerdict) {
        StanceTopic.NONE -> "都不对"
        StanceTopic.OPEN -> "没有对错"
        StanceTopic.UNCLEAR -> "判断不了"
        "" -> ""
        else -> "${t.aiVerdict} 对：" + (t.option(t.aiVerdict)?.text ?: "")
    }
    val color = when (t.aiVerdict) {
        StanceTopic.UNCLEAR, "" -> Ext.c.subtle
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text("AI 核实", style = MaterialTheme.typography.labelMedium, color = color)
            if (text.isNotBlank()) { Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            if (mine) { Spacer(Modifier.width(6.dp)); Text("（已采纳）", style = MaterialTheme.typography.labelSmall, color = Ext.c.success) }
        }
        if (t.aiReason.isNotBlank()) Text(t.aiReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (t.aiSources.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            t.aiSources.take(4).forEach { s ->
                Text("· " + s.title.ifBlank { s.url }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { platform.openUrl(s.url) }.padding(vertical = 1.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.aiVerdict.isNotBlank() && t.aiVerdict != StanceTopic.UNCLEAR && !mine) {
                TextButton(onClick = { ctl.run(Command.StanceMark(t.id, t.aiVerdict), quiet = true) }) {
                    Text("采纳", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (taken && !mine) Text("（你已经标了，你标的优先）", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
        Text("裁判只看答案对不对，防不了「回答风格被认出来」。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

/** 聊天里 AI 回答下面的小标签：这条回答在某个议题上的立场。 */
@Composable
fun StanceChip(t: StanceTopic, e: StanceEntry, onClick: () -> Unit) {
    val opt = t.option(e.option)?.text ?: "没给出明确结论"
    val prev = t.entries.filter { it.memberId == e.memberId && it.time < e.time }.maxByOrNull { it.time }
    val mark = when {
        t.verdict.isEmpty() || t.verdict == StanceTopic.OPEN -> ""
        t.verdict == e.option -> " ✓"
        else -> " ✗"
    }
    val text = when {
        e.first || prev == null -> "立场 ${e.option}：$opt"
        e.why == Stances.HOLD -> "坚持 ${e.option}：$opt"
        else -> "改口 ${prev.option}→${e.option}（${e.why}）：$opt"
    } + mark
    val color = when {
        mark == " ✓" -> Ext.c.success
        mark == " ✗" -> MaterialTheme.colorScheme.error
        else -> whyColor(e.why).takeIf { e.why.isNotEmpty() && e.why != Stances.HOLD } ?: MaterialTheme.colorScheme.primary
    }
    Pill(text, color, Modifier.widthIn(max = 420.dp), onClick = onClick)
}

/** 某个对话的全部议题（聊天顶部「立场」点开的）。 */
@Composable
fun ConversationStancesDialog(ctl: AppController, state: AppState, conv: Conversation, topics: List<StanceTopic>, focusId: String?, onClose: () -> Unit) {
    AppDialog("立场档案 · ${conv.title}", onClose, width = 680.dp, actions = {
        TextButton(onClick = { onClose(); ctl.openPage(MainPage.STANCES) }) { Text("看全部成员的卡片") }
    }) {
        val ordered = topics.sortedByDescending { it.createdAt }.let { all -> all.filter { it.id == focusId } + all.filter { it.id != focusId } }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (ordered.isEmpty()) Text("这个对话还没有记录", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            ordered.forEach { t ->
                StanceTopicCard(ctl, state, t, conv, null)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
