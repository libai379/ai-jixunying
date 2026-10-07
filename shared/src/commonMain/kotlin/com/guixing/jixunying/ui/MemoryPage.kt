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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.MemorySettings
import com.guixing.jixunying.model.RecorderPick

private val memoryKinds = listOf("关于我", "偏好", "要求", "事实")

/** 设置 → 记忆：开关、记录员、压缩时机，以及记住的每一条（能加、能改、能删、能置顶）。 */
@Composable
fun MemoryPage(ctl: AppController, state: AppState) {
    val ms = state.settings.memory
    fun saveMs(f: (MemorySettings) -> MemorySettings) = ctl.updateSettings { it.copy(memory = f(it.memory)) }
    var newText by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(memoryKinds.first()) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var tidying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    PageHeader("记忆", "AI 会记得关于你的事，所有对话、所有成员都能用。对话聊得太长时，记录员会把前面的部分压缩成摘要（大家共用一份）；AI 也能翻以前的聊天记录。")

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
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("记住的事（${state.memories.size} 条）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !tidying && state.memories.size >= 2, onClick = {
                tidying = true; result = null
                ctl.run(Command.TidyMemories, quiet = true) { r -> tidying = false; result = r.ok to r.message }
            }) { Text(if (tidying) "正在整理…" else "整理") }
        }
        Text("置顶的每次都会带上，其余按新旧带上（总长约 2500 字）。「整理」让记录员合并重复和矛盾的条目，置顶的不动。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        result?.let { (ok, text) -> TestResultBox(ok, text) { result = null } }
        Spacer(Modifier.height(4.dp))
        AppTextField(newText, { newText = it }, placeholder = "手动添加一条，例如：我在北京做产品经理，回答请先给结论", singleLine = false, minLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("类别：", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
            KindPicker(newKind) { newKind = it }
            Spacer(Modifier.weight(1f))
            Button(enabled = newText.isNotBlank(), onClick = {
                val item = MemoryItem("mem" + nowMillis().toString(36), newText.trim(), newKind, "手动添加")
                ctl.run(Command.SaveMemory(item)) { r -> if (r.ok) newText = "" }
            }) { Text("添加") }
        }
        Spacer(Modifier.height(10.dp))
        if (state.memories.isEmpty()) {
            Text("还没有。聊天时跟 AI 说「记住……」，或者在上面手动添加；打开「聊完自动记住要点」后，记录员也会自己挑。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        val sorted = state.memories.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sorted.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp))
                        .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(m.kind)
                            if (m.pinned) { Spacer(Modifier.width(4.dp)); Pill("置顶", Ext.c.warning) }
                        }
                        Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
                        if (m.source.isNotBlank()) Text("来自：${m.source}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                    IconButton(onClick = { ctl.run(Command.SaveMemory(m.copy(pinned = !m.pinned)), quiet = true) }) {
                        Icon(Icons.Rounded.PushPin, if (m.pinned) "取消置顶" else "置顶", Modifier.size(17.dp),
                            tint = if (m.pinned) MaterialTheme.colorScheme.primary else Ext.c.subtle)
                    }
                    IconButton(onClick = { editing = m }) { Icon(Icons.Rounded.Edit, "修改", Modifier.size(17.dp), tint = Ext.c.subtle) }
                    IconButton(onClick = { ctl.run(Command.DeleteMemory(m.id), "已删除") }) { Icon(Icons.Rounded.Delete, "删除", Modifier.size(17.dp), tint = Ext.c.subtle) }
                }
            }
        }
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        var kind by remember(m.id) { mutableStateOf(m.kind) }
        AppDialog("修改记忆", { editing = null }, width = 520.dp, actions = {
            TextButton({ editing = null }) { Text("取消") }
            Button(enabled = text.isNotBlank(), onClick = { editing = null; ctl.run(Command.SaveMemory(m.copy(text = text.trim(), kind = kind)), "已保存") }) { Text("保存") }
        }) {
            AppTextField(text, { text = it }, singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("类别：", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                KindPicker(kind) { kind = it }
            }
        }
    }
}

@Composable
private fun KindPicker(kind: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        memoryKinds.forEach { k ->
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { onPick(k) }) {
                Pill(k, if (k == kind) MaterialTheme.colorScheme.primary else Ext.c.subtle)
            }
        }
    }
}
