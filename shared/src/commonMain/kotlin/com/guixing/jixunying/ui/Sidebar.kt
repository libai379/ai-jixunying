package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.HistoryHit
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
fun BrandMark(size: Int = 30) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6A5CFF), Color(0xFF2EC5CE)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("集", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.5).sp)
    }
}

@Composable
fun Sidebar(ctl: AppController, modifier: Modifier, onNavigate: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(modifier.background(Ext.c.sidebar).padding(horizontal = 12.dp)) {
        Row(Modifier.padding(start = 6.dp, top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(10.dp))
            Column {
                Text("AI集训营", style = MaterialTheme.typography.titleMedium)
                Text("多模型助手 · 数据只在本机", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
        }
        DeviceSwitcher(ctl)
        Button(
            onClick = { ctl.showNewChat = true; onNavigate() },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新对话")
        }
        Spacer(Modifier.height(10.dp))
        TapToSearchField(query, { query = it }, "搜索对话")
        Spacer(Modifier.height(8.dp))

        val list = state.conversations.filter { query.isBlank() || it.title.contains(query, true) || memberNames(state, it).contains(query, true) }
        // 也搜聊天内容（两个字以上，停顿一下再搜，免得每打一个字都搜一遍）
        var hits by remember { mutableStateOf<List<HistoryHit>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(query, ctl.backend) {
            hits = emptyList()
            val q = query.trim()
            if (q.length < 2) return@LaunchedEffect
            kotlinx.coroutines.delay(350)
            val r = ctl.backend.call(Command.SearchHistory(q, 20))
            if (r.ok) hits = runCatching { AppJson.decodeFromString(ListSerializer(HistoryHit.serializer()), r.data) }.getOrDefault(emptyList())
        }
        val now = nowMillis()
        val dayMs = 86_400_000L
        val groups = listOf(
            "置顶" to list.filter { it.pinned },
            "今天" to list.filter { !it.pinned && now - it.updatedAt < dayMs },
            "最近 7 天" to list.filter { !it.pinned && now - it.updatedAt in dayMs until 7 * dayMs },
            "更早" to list.filter { !it.pinned && now - it.updatedAt >= 7 * dayMs },
        ).filter { it.second.isNotEmpty() }

        LazyColumn(Modifier.weight(1f)) {
            if (list.isEmpty() && hits.isEmpty()) item {
                Text(if (query.isBlank()) "还没有对话" else "没找到", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(12.dp))
            }
            if (hits.isNotEmpty()) {
                item(key = "h_hits") {
                    Text("聊天内容里找到", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp))
                }
                items(hits, key = { "hit_" + it.convId + it.messageId }) { h ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { ctl.focusMessageId = h.messageId; ctl.openConversation(h.convId); onNavigate() }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text("${h.convTitle} · ${h.sender}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.snippet, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            groups.forEach { (label, convs) ->
                item(key = "h_$label") {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp))
                }
                items(convs, key = { it.id }) { c ->
                    ConversationRow(ctl, state, c, selected = c.id == ctl.currentConvId && ctl.settingsTab == null && ctl.page == null) {
                        ctl.openConversation(c.id); onNavigate()
                    }
                }
            }
        }

        Column(Modifier.padding(vertical = 10.dp)) {
            NavRow(Icons.Rounded.FolderOpen, "我的文档", when {
                !state.settings.docs.enabled -> "已关闭"
                state.docs.needPermission -> "要授权"
                state.docs.scanning -> "扫描中…"
                else -> "${state.docs.count} 个"
            }, dot = if (state.docs.needPermission) Ext.c.warning else null) { ctl.openPage(MainPage.DOCS); onNavigate() }
            NavRow(Icons.Rounded.Groups, "AI 成员", "${state.members.size} 位") { ctl.openSettings(SettingsTab.MEMBERS); onNavigate() }
            val platform = LocalPlatform.current
            val remote by ctl.hub.remote.collectAsState()
            val remoteConn = remote?.conn?.collectAsState()?.value
            if (platform.isDesktop) {
                NavRow(Icons.Rounded.PhoneAndroid, "手机联机", if (state.devices.isEmpty()) "未配对" else "${state.devices.size} 台",
                    dot = if (state.relayStatus.startsWith("已连上")) Ext.c.success else null) {
                    ctl.openSettings(SettingsTab.DEVICES); onNavigate()
                }
            } else {
                NavRow(Icons.Rounded.Computer, "连接电脑", when (remoteConn) {
                    null -> "未配对"
                    is ConnState.Connected -> "已连接"
                    ConnState.Connecting -> "连接中…"
                    else -> "离线"
                }, dot = when (remoteConn) { is ConnState.Connected -> Ext.c.success; null -> null; else -> MaterialTheme.colorScheme.error }) {
                    ctl.openSettings(SettingsTab.DEVICES); onNavigate()
                }
            }
            NavRow(Icons.Rounded.Settings, "设置", null) { ctl.openSettings(); onNavigate() }
        }
    }
}

private fun memberNames(state: AppState, c: Conversation) = c.memberIds.mapNotNull { state.member(it)?.name }.joinToString("、")

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, trailing: String?, dot: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

@Composable
private fun ConversationRow(ctl: AppController, state: AppState, c: Conversation, selected: Boolean, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val members = c.memberIds.mapNotNull { state.member(it) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Ext.c.sidebarSelected else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 8.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp)) {
            when {
                members.isEmpty() -> MemberAvatar(null, 30.dp, emoji = "💬")
                members.size == 1 -> MemberAvatar(members[0], 30.dp)
                else -> {
                    MemberAvatar(members[0], 21.dp)
                    Box(Modifier.offset(9.dp, 9.dp)) { MemberAvatar(members[1], 21.dp) }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (c.channel.startsWith("weixin:")) "微信 · " else "") +
                (if (members.size > 1) "群聊 · " + members.joinToString("、") { it.name } else members.firstOrNull()?.name ?: "未选成员"),
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, "更多", Modifier.size(16.dp), tint = Ext.c.subtle)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (c.pinned) "取消置顶" else "置顶") }, leadingIcon = { Icon(Icons.Rounded.PushPin, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; ctl.run(Command.UpdateConversation(c.copy(pinned = !c.pinned))) })
                DropdownMenuItem({ Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; renaming = true })
                DropdownMenuItem({ Text("删除", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true })
            }
        }
    }
    if (renaming) {
        var title by remember { mutableStateOf(c.title) }
        AppDialog("重命名对话", { renaming = false }, width = 420.dp, actions = {
            TextButton({ renaming = false }) { Text("取消") }
            Button({ renaming = false; ctl.run(Command.UpdateConversation(c.copy(title = title.trim().ifEmpty { c.title }))) }) { Text("保存") }
        }) { AppTextField(title, { title = it }) }
    }
    if (confirmDelete) {
        AppDialog("删除对话", { confirmDelete = false }, width = 420.dp, actions = {
            TextButton({ confirmDelete = false }) { Text("取消") }
            Button({
                confirmDelete = false
                if (ctl.currentConvId == c.id) ctl.currentConvId = state.conversations.firstOrNull { it.id != c.id }?.id
                ctl.run(Command.DeleteConversation(c.id))
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }) {
            Text("「${c.title}」的聊天记录和附件会从电脑上删除，不能恢复。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MemberPickList(state: AppState, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.members.forEach { m ->
            val on = m.id in selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else Ext.c.border, RoundedCornerShape(12.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent)
                    .clickable { onToggle(m.id) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberAvatar(m, 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(listOf(m.modelId.ifBlank { "未选模型" }, m.bio).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                androidx.compose.material3.Checkbox(on, { onToggle(m.id) })
            }
        }
    }
}

@Composable
fun NewChatDialog(ctl: AppController) {
    val state by ctl.backend.store.state.collectAsState()
    var picked by remember { mutableStateOf(state.members.take(1).map { it.id }.toSet()) }
    AppDialog("新对话", { ctl.showNewChat = false }, width = 520.dp, actions = {
        Text(if (picked.size > 1) "群聊 · ${picked.size} 位成员" else if (picked.size == 1) "单聊" else "至少选一位",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.weight(1f))
        TextButton({ ctl.showNewChat = false }) { Text("取消") }
        Button(enabled = picked.isNotEmpty(), onClick = {
            ctl.showNewChat = false
            ctl.run(Command.CreateConversation(state.members.map { it.id }.filter { it in picked })) { r -> if (r.ok) ctl.openConversation(r.data) }
        }) { Text("开始") }
    }) {
        if (state.members.isEmpty()) {
            Text("还没有 AI 成员。先到 设置→AI 成员 添加。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选一位就是单聊，选多位就是群聊（可以互相 @、互相纠错）。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle,
                    modifier = Modifier.weight(1f))
                if (state.members.size > 1) TextButton(onClick = {
                    picked = if (picked.size == state.members.size) state.members.take(1).map { it.id }.toSet() else state.members.map { it.id }.toSet()
                }) { Text(if (picked.size == state.members.size) "只选一位" else "全选") }
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                MemberPickList(state, picked) { id -> picked = if (id in picked) picked - id else picked + id }
            }
        }
    }
}
