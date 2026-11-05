package com.guixing.jixunying.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.JudgePick
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.MemorySettings
import com.guixing.jixunying.model.RecorderPick
import com.guixing.jixunying.model.TidyProgress
import kotlinx.coroutines.delay

private val memoryKinds = listOf("关于我", "偏好", "要求", "事实")

/** 看过结果的那次整理（结束时间）：看过就不再在记忆页上提示。放在页面外面，切到别的页再回来也记得。 */
private var tidySeenAt by mutableStateOf(0L)

/** 设置 → 记忆：开关、记录员、压缩时机，以及记住的每一条（能加、能改、能删、能置顶）。 */
@Composable
fun MemoryPage(ctl: AppController, state: AppState) {
    val ms = state.settings.memory
    fun saveMs(f: (MemorySettings) -> MemorySettings) = ctl.updateSettings { it.copy(memory = f(it.memory)) }
    var newText by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(memoryKinds.first()) }
    var newTopic by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var tidyStarting by remember { mutableStateOf(false) }
    // 截图、尺寸测试用（ShotsTest）：settings:MEMORY#tidy 开着进度窗口（正在做第 2 批、25 秒没动静），#tidystrip 是窗口关了以后页上那一行
    val sample = remember {
        if (!ctl.debug("tidy") && !ctl.debug("tidystrip")) null
        else nowMillis().let { t ->
            TidyProgress(running = true, startedAt = t - 95_000, total = 168, done = 46, batch = 2, batches = 5, merged = 6, dropped = 1,
                written = 312, lastActivityAt = t - 25_000, model = "mimo-v2.6-flash")
        }
    }
    val tidy = sample ?: state.tidy
    var tidyOpen by remember { mutableStateOf(ctl.debug("tidy")) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    PageHeader("记忆", "AI 会记得关于你的事，所有对话、所有成员都能用。对话聊得太长时，记录员会把前面的部分压缩成摘要（大家共用一份）；AI 也能翻以前的聊天记录。")
    // 记录员在后台出过错（压缩、挑记忆、点名、立场档案）：说清楚原因和怎么修
    BgProblemsCard(ctl, state.bgProblems, onMemoryPage = true)

    SectionCard {
        SwitchRow("长期记忆", "把下面记住的事告诉每位成员；AI 也能主动记、能翻以前的聊天", ms.enabled) { v -> saveMs { it.copy(enabled = v) } }
        SwitchRow("聊完自动记住要点", "记录员从聊天里挑出关于你的长期信息。不记密码、Key、证件号这类敏感信息", ms.enabled && ms.autoExtract) {
            saveMs { m -> m.copy(autoExtract = it, enabled = m.enabled || it) }
        }
        HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 8.dp))
        FieldLabel("记录员", "压缩聊天、挑记忆用的模型，便宜的就够")
        val auto = RecorderPick.isAuto(state)
        val now = RecorderPick.pick(state)
        SelectBox(
            when {
                now == null -> "还没有能用的模型（先在 模型服务 里配一个）"
                auto -> "自动：${now.second}（${now.first.name}）"
                else -> "${now.second}（${now.first.name}）"
            },
        ) { close ->
            androidx.compose.material3.DropdownMenuItem({
                Column {
                    Text("自动（推荐）")
                    Text("优先用 mimo-v2.6-flash 这类便宜的模型", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
            }, onClick = { saveMs { it.copy(recorderProviderId = "", recorderModelId = "") }; close() })
            state.providers.filter(ImagePick::usable).forEach { p ->
                p.models.filter { !it.imageGen }.forEach { m ->
                    androidx.compose.material3.DropdownMenuItem({
                        Column {
                            Text(m.id)
                            Text(p.name, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                        }
                    }, onClick = { saveMs { it.copy(recorderProviderId = p.id, recorderModelId = m.id) }; close() })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FieldLabel("聊多长开始压缩", "越短越省钱，越长 AI 看到的原话越多")
        FlowRowCompat {
            listOf(12_000 to "1.2 万字", 24_000 to "2.4 万字（默认）", 48_000 to "4.8 万字").forEach { (n, label) ->
                Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
                    ToggleChip(label, Icons.Rounded.Psychology, ms.compressAt == n) { saveMs { it.copy(compressAt = n) } }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    JudgeSection(ctl, state, ms, ::saveMs)
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记住的事（${state.memories.size} 条）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !tidy.running && !tidyStarting && state.memories.size >= 2, onClick = {
                tidyStarting = true; result = null
                ctl.run(Command.TidyMemories, quiet = true) { r ->
                    tidyStarting = false
                    // 在后台一批批做（2026-11-05）：开始了就开进度窗口；不用整理、没有模型这些直接说
                    if (r.ok && r.data == "started") tidyOpen = true else result = r.ok to r.message
                }
            }) { Text(when { tidy.running -> "正在整理…"; tidyStarting -> "正在开始…"; else -> "整理" }) }
        }
        Text("「关于你」的每个对话都带上；带话题的只在属于那个话题的对话里带上（对话设置里能改话题），别的对话里 AI 要用时自己查。" +
            "置顶的优先，其余按新旧（总长约 2500 字）。点「来自」能跳回记下这条的那句话。「整理」让记录员合并重复和矛盾的条目，置顶的不动。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        result?.let { (ok, text) -> TestResultBox(ok, text) { result = null } }
        // 整理在后台做，窗口关了也接着做：这里留一行进度，点开看详情；做完了还没看过的，说一声结果
        if (tidy.running && !tidyOpen) TidyStrip(tidy) { tidyOpen = true }
        else if (!tidy.running && tidy.finishedAt > tidySeenAt && !tidyOpen) TestResultBox(!tidy.error, tidy.note) { tidySeenAt = tidy.finishedAt }
        if (tidyOpen) TidyDialog(ctl, tidy) { tidyOpen = false; if (!tidy.running) tidySeenAt = tidy.finishedAt }
        Spacer(Modifier.height(4.dp))
        AppTextField(newText, { newText = it }, placeholder = "手动添加一条，例如：我在北京做产品经理，回答请先给结论", singleLine = false, minLines = 1)
        Spacer(Modifier.height(6.dp))
        // 类别一行（排不下自己换行），「添加」在下一行靠右：窄屏、大字体下不再挤没（10-24 phoneSettingsFit）
        Text("类别", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
        KindPicker(newKind) { newKind = it }
        Spacer(Modifier.height(6.dp))
        Text("话题", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
        TopicPicker(state, newTopic) { newTopic = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(enabled = newText.isNotBlank(), onClick = {
                val item = MemoryItem("mem" + nowMillis().toString(36), newText.trim(), newKind, "手动添加", topic = newTopic.trim())
                ctl.run(Command.SaveMemory(item)) { r -> if (r.ok) newText = "" }
            }) { Text("添加") }
        }
        Spacer(Modifier.height(10.dp))
        if (state.memories.isEmpty()) {
            Text("还没有。聊天时跟 AI 说「记住……」，或者在上面手动添加；打开「聊完自动记住要点」后，记录员也会自己挑。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        // 按话题分组（记忆二期）：先「关于你」，再各个话题（最近用过的在前）
        val order = compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt }
        val groups = state.memories.groupBy { it.topic }.entries
            .sortedWith(compareBy<Map.Entry<String, List<MemoryItem>>> { it.key.isNotBlank() }.thenByDescending { e -> e.value.maxOf { it.updatedAt } })
        // 每一组能收起（2026-10-27 用户：「记忆下面这一串要有一个收起的栏」）：默认都收着，只显示组名和条数，点一下展开
        var open by remember { mutableStateOf(setOf<String>()) }
        if (groups.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val allOpen = groups.all { it.key in open }
            TextButton({ open = if (allOpen) emptySet() else groups.map { it.key }.toSet() }) { Text(if (allOpen) "全部收起" else "全部展开", style = MaterialTheme.typography.labelMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          groups.forEach { (topic, list) ->
            val expanded = topic in open
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = if (expanded) open - topic else open + topic }
                .padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起" else "展开", Modifier.size(18.dp), tint = Ext.c.subtle)
                Spacer(Modifier.width(6.dp))
                Text(if (topic.isBlank()) "关于你（所有对话都带上）· ${list.size} 条" else "话题「$topic」（聊这个话题时带上）· ${list.size} 条",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            if (expanded) list.sortedWith(order).forEach { m ->
                @Composable
                fun actions() {
                    IconButton(onClick = { ctl.run(Command.SaveMemory(m.copy(pinned = !m.pinned)), quiet = true) }) {
                        Icon(Icons.Rounded.PushPin, if (m.pinned) "取消置顶" else "置顶", Modifier.size(17.dp),
                            tint = if (m.pinned) MaterialTheme.colorScheme.primary else Ext.c.subtle)
                    }
                    IconButton(onClick = { editing = m }) { Icon(Icons.Rounded.Edit, "修改", Modifier.size(17.dp), tint = Ext.c.subtle) }
                    IconButton(onClick = { ctl.run(Command.DeleteMemory(m.id), "已删除") }) { Icon(Icons.Rounded.Delete, "删除", Modifier.size(17.dp), tint = Ext.c.subtle) }
                }
                @Composable
                fun body(mod: Modifier) = Column(mod) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(m.kind)
                        if (m.pinned) { Spacer(Modifier.width(4.dp)); Pill("置顶", Ext.c.warning) }
                    }
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
                    MemorySource(ctl, state, m)
                }
                val card = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp)
                // 分屏 220dp 这种窄宽度：三个按钮占掉大半，文字和出处会被挤成 0 宽（phoneSettingsFit），按钮挪到下面一行
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 300.dp) Column(card) {
                        body(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                    } else Row(card, verticalAlignment = Alignment.CenterVertically) {
                        body(Modifier.weight(1f))
                        actions()
                    }
                }
            }
          }
        }
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        var kind by remember(m.id) { mutableStateOf(m.kind) }
        var topic by remember(m.id) { mutableStateOf(m.topic) }
        AppDialog("修改记忆", { editing = null }, width = 520.dp, actions = {
            TextButton({ editing = null }) { Text("取消") }
            Button(enabled = text.isNotBlank(), onClick = { editing = null; ctl.run(Command.SaveMemory(m.copy(text = text.trim(), kind = kind, topic = topic.trim())), "已保存") }) { Text("保存") }
        }) {
            AppTextField(text, { text = it }, singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("类别：", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                KindPicker(kind) { kind = it }
            }
            Spacer(Modifier.height(8.dp))
            Text("话题", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
            TopicPicker(state, topic) { topic = it }
        }
    }
}

/** 话题：留空 = 关于你本人（所有对话都带）；点已有的话题名直接填上。 */
@Composable
internal fun TopicPicker(state: AppState, topic: String, onChange: (String) -> Unit) {
    AppTextField(topic, onChange, placeholder = "留空 = 关于你本人，所有对话都带")
    val known = state.memories.map { it.topic }.filter { it.isNotBlank() }.distinct()
    if (known.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            known.forEach { k ->
                Box(Modifier.clip(RoundedCornerShape(50)).clickable { onChange(k) }) {
                    Pill(k, if (k == topic.trim()) MaterialTheme.colorScheme.primary else Ext.c.subtle)
                }
            }
        }
    }
}

/** 出处：哪个对话、哪天，有原话就带上；点一下跳回那个对话的那一条（对话删了就只显示名字）。 */
@Composable
private fun MemorySource(ctl: AppController, state: AppState, m: MemoryItem) {
    val conv = m.convId.takeIf { it.isNotBlank() }?.let { state.conversation(it) }
    if (m.source.isBlank() && conv == null) return
    val label = "来自：" + (conv?.title ?: m.source) + (if (conv == null && m.convId.isNotBlank()) "（对话已删除）" else "")
    Column(if (conv != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable {
        ctl.focusMessageId = m.messageId.ifBlank { null }
        ctl.openConversation(conv.id)
    } else Modifier) {
        Text(label + if (conv != null) " ›" else "", style = MaterialTheme.typography.labelSmall,
            color = if (conv != null) MaterialTheme.colorScheme.primary else Ext.c.subtle)
        if (m.quote.isNotBlank()) Text("原话：「${m.quote.take(80)}${if (m.quote.length > 80) "…" else ""}」",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

@Composable
private fun KindPicker(kind: String, onPick: (String) -> Unit) {
    // 排不下就换行（窄屏、大字体，10-24 phoneSettingsFit）
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        memoryKinds.forEach { k ->
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { onPick(k) }) {
                Pill(k, if (k == kind) MaterialTheme.colorScheme.primary else Ext.c.subtle)
            }
        }
    }
}

/**
 * 立场档案的裁判：用哪个模型判、要不要隐去名字、要不要 AI 核实。
 * 默认 deepseek-flash（阿德也是这个模型，所以默认隐去名字，只防「看名字偏袒」）。
 */
@Composable
private fun JudgeSection(ctl: AppController, state: AppState, ms: MemorySettings, saveMs: ((MemorySettings) -> MemorySettings) -> Unit) {
    val recorder = RecorderPick.pick(state)?.second
    val judgeAuto = JudgePick.isAuto(state)
    val judgeNow = JudgePick.pick(state)
    SectionCard {
        FieldLabel("立场档案的裁判", "判断谁改了口、谁被带偏的模型。默认用 deepseek-flash（DeepSeek-V4.1-Flash）")
        SelectBox(
            when {
                judgeNow == null -> "还没有能用的模型（先在 模型服务 里配一个）"
                judgeAuto -> "自动：${judgeNow.second}（${judgeNow.first.name}）"
                else -> "${judgeNow.second}（${judgeNow.first.name}）"
            },
        ) { close ->
            androidx.compose.material3.DropdownMenuItem({
                Column {
                    Text("自动（推荐）")
                    Text("优先用 deepseek-flash，没有就退回记录员" + (recorder?.let { "（$it）" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
            }, onClick = { saveMs { it.copy(judgeProviderId = "", judgeModelId = "") }; close() })
            state.providers.filter(ImagePick::usable).forEach { p ->
                p.models.filter { !it.imageGen }.forEach { m ->
                    androidx.compose.material3.DropdownMenuItem({
                        Column {
                            Text(m.id)
                            Text(p.name, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                        }
                    }, onClick = { saveMs { it.copy(judgeProviderId = p.id, judgeModelId = m.id) }; close() })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow("裁判看不到成员名字", "把名字换成「成员甲乙丙丁」再交给裁判，防它看名字偏袒。回答风格本身藏不住，只能防这一层",
            ms.stances && ms.judgeAnonymous) { v -> saveMs { it.copy(judgeAnonymous = v) } }
        SwitchRow("开新议题后 AI 核实", "裁判自己判一次谁对（能查就联网查），结论和出处显示在议题上。你标的永远优先",
            ms.stances && ms.aiVerify) { v -> saveMs { it.copy(aiVerify = v) } }
        SwitchRow("统计时按 AI 核实算", "你没标对错的题，用 AI 核实的结论算「首答准确率」。关掉就只算你亲手标的题",
            ms.stances && ms.aiVerify && ms.countAiVerdict) { v -> saveMs { it.copy(countAiVerdict = v) } }
    }
}

/**
 * 整理记忆的进度窗口（2026-11-05 用户：「你得做一个小窗口，显示它的进度。说显示他做多少信息，还剩多少信息等等，
 * 让我知道他在干活，而不是干等」）。整理不限时，所以要看得见它还活着：这一批在等 / 在想 / 在写、写了多少字、多久没动静。
 */
@Composable
private fun TidyDialog(ctl: AppController, t: TidyProgress, onClose: () -> Unit) {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(t.running) { while (t.running) { now = nowMillis(); delay(1_000) } }
    // 「多久没动静」「用了多久」：本机整理的直接用它记的时间；手机看电脑上的整理时两台机器的钟可能差几十秒，
    // 改成每有动静（lastActivityAt 变了）就记下手机这时的钟，从这儿往后数
    val remote = ctl.remoteMode
    var seenAt by remember { mutableStateOf(if (remote) nowMillis() else t.lastActivityAt) }
    var seenActivity by remember { mutableStateOf(t.lastActivityAt) }
    LaunchedEffect(t.lastActivityAt) {
        if (t.lastActivityAt != seenActivity) {
            seenActivity = t.lastActivityAt
            seenAt = if (remote) nowMillis() else t.lastActivityAt
            now = nowMillis()
        }
    }
    var stopping by remember { mutableStateOf(false) }
    val small = MaterialTheme.typography.bodySmall
    AppDialog("整理记忆", onClose, width = 460.dp, actions = {
        if (t.running) OutlinedButton(enabled = !stopping, onClick = { stopping = true; ctl.run(Command.TidyStop, quiet = true) }) {
            Text(if (stopping) "正在停…" else "停下")
        }
        else Button(onClick = onClose) { Text("好") }
    }) {
        // 窄屏、大字体下装不下时能往下滑（2026-11-05 量 320dp + 字体 200% 时，底下「用了多久」那几行被裁掉、也划不到）
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("看完 ${t.done} / ${t.total} 条，还剩 ${(t.total - t.done).coerceAtLeast(0)} 条", style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { t.fraction() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text("分 ${t.batches} 批${if (t.running && t.batch > 0) "，正在做第 ${t.batch} 批" else ""} · 合并了 ${t.merged} 处 · 删了 ${t.dropped} 条",
                style = small, color = Ext.c.subtle)
            Spacer(Modifier.height(12.dp))
            if (t.running) {
                Text(when {
                    stopping -> "正在停下…"
                    t.written == 0 -> "这一批交给记录员了，在等它回话"
                    t.thinking -> "记录员在想（已经想了 ${t.written} 字）"
                    else -> "记录员在写这一批要改的（已经写了 ${t.written} 字）"
                }, style = MaterialTheme.typography.bodyMedium)
                // 一直在动的条：程序没卡住
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                val quiet = ((now - seenAt) / 1000).coerceAtLeast(0)
                if (quiet >= 20) Text("已经 $quiet 秒没有新动静了。整理不限时，会一直等；不想等就点「停下」，做完的那几批已经改好。", style = small, color = Ext.c.subtle)
                Text("关掉这个窗口也会接着整理，记忆页上能再点开。", style = small, color = Ext.c.subtle)
            } else {
                Text(t.note, style = MaterialTheme.typography.bodyMedium,
                    color = if (t.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            val used = if (t.running) (t.lastActivityAt - t.startedAt) + (now - seenAt) else t.finishedAt - t.startedAt
            Text("记录员：${t.model} · 用了 ${tidyDuration(used)}",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
    }
}

/** 记忆页上的一行：整理正在后台做（窗口关了），进度条 + 「看进度」。 */
@Composable
private fun TidyStrip(t: TidyProgress, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("正在整理：看完 ${t.done} / ${t.total} 条（第 ${t.batch} / ${t.batches} 批）", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { t.fraction() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        TextButton(onClick = onOpen) { Text("看进度") }
    }
}

private fun TidyProgress.fraction() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

private fun tidyDuration(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return if (sec < 60) "$sec 秒" else "${sec / 60} 分 ${sec % 60} 秒"
}
