package com.guixing.jixunying.ui

import com.guixing.jixunying.ui.glass.glass

import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Insights
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
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
fun BrandMark(size: Int = 30) {
    // 2026-10-12 起用用户给的图标（和启动图标、电脑窗口图标同一张）
    LocalPlatform.current.brandIcon?.let { icon ->
        androidx.compose.foundation.Image(icon, "AI集训营", Modifier.size(size.dp))
        return
    }
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
    val isDesktopHere = LocalPlatform.current.isDesktop
    val state by ctl.backend.store.state.collectAsState()
    var query by remember { mutableStateOf("") }

    // 2026-10-14 按用户给的改版图排（「侧边栏改版 · 改前 vs 改后」）：
    // 顶上 名字 → 「对话 | Code」标签和设备下拉一行 → 搜索框和「+」一行 → 我的文档 / 立场档案 / AI 成员三张小卡片 →
    // 列表 → 最底下一行「花费」「设置」。原来在底下的「连接电脑 / 手机联机」收进了设备下拉。
    val code = ctl.sidebarTab == SidebarTab.CODE
    val codeAccess = CodeUi.access(LocalPlatform.current.isDesktop, ctl.remoteMode, state.settings.code.allowPhone)
    // 液态玻璃（2026-10-22）：浮在壁纸上的一块圆角玻璃（电脑上留出边距；手机抽屉贴边，只右边圆角）
    val glassOn = com.guixing.jixunying.ui.glass.LocalGlass.current.on
    val panelShape = if (isDesktopHere) RoundedCornerShape(22.dp) else RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    Column(modifier
        .then(if (glassOn && isDesktopHere) Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp) else Modifier)
        .glass(if (glassOn) panelShape else androidx.compose.ui.graphics.RectangleShape, com.guixing.jixunying.ui.glass.GlassStyle.Panel, fallback = Ext.c.sidebar)) {
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // 接焦点用的空点（什么都不画）：手机上抽屉一拉开，系统把焦点交给侧栏里第一个能拿焦点的东西，
            // 落在标签、下拉、「+」上都会显出一块灰色的焦点底色；让它落在这里
            Box(Modifier.size(0.dp).focusable())
            Row(Modifier.padding(start = 6.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(36)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("AI集训营", style = MaterialTheme.typography.titleMedium)
                    Text("多模型助手 · 数据只在本机", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // 手机本机跑不了 Code 栏，不给这个标签（2026-10-23），切到电脑才有
                val tabs = if (codeAccess == CodeAccess.NO_LOCAL) listOf(SidebarTab.CHAT) else SidebarTab.entries
                SidebarTabs(tabs, ctl.sidebarTab) { if (it != ctl.sidebarTab) { ctl.sidebarTab = it; query = "" } }
                Spacer(Modifier.weight(1f))
                DeviceMenu(ctl, state, onNavigate)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TapToSearchField(query, { query = it }, if (code) "搜索 Code 会话" else "搜索对话", Modifier.weight(1f))
                // 新对话 / 新建 Code 会话（Code 栏在这台设备上动不了时不给，见 CodeUi）
                val canNew = !code || codeAccess.canAct
                if (canNew) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary)
                            .clickable { if (code) ctl.newCode(isDesktopHere) else ctl.showNewChat = true; onNavigate() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, if (code) "新建 Code 会话" else "新对话", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickCard(Icons.Rounded.FolderOpen, "我的文档", when {
                    !state.settings.docs.enabled -> "已关闭"
                    state.docs.needPermission -> "要授权"
                    state.docs.scanning -> "扫描中…"
                    else -> "${state.docs.count} 个"
                }, dot = if (state.docs.needPermission) Ext.c.warning else null) { ctl.openPage(MainPage.DOCS); onNavigate() }
                QuickCard(Icons.Rounded.Flag, "立场档案", state.conversations.sumOf { it.stanceTopics }.let { if (it == 0) "还没有" else "$it 题" }) {
                    ctl.openPage(MainPage.STANCES); onNavigate()
                }
                QuickCard(Icons.Rounded.Groups, "AI 成员", "${state.members.size} 位") { ctl.openSettings(SettingsTab.MEMBERS); onNavigate() }
            }
            Spacer(Modifier.height(4.dp))
            if (code) CodeSessionSidebar(ctl, state, query, codeAccess, onNavigate)
            else ChatSessionSidebar(ctl, state, query, onNavigate)
        }
        HorizontalDivider(color = Ext.c.border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BottomItem(Icons.Rounded.Payments, "花费", null) { ctl.openPage(MainPage.COSTS); onNavigate() }
            Spacer(Modifier.width(6.dp))
            // 记录员后台出了错：设置旁边亮红点。点进去照样是设置首页（以前直接跳「记忆」，
            // 出错提示没消之前每次点设置都进记忆，用户 2026-10-12 报的），首页里「记忆」那一项标红
            val bgBad = state.bgProblems.isNotEmpty()
            BottomItem(Icons.Rounded.Tune, "设置", if (bgBad) MaterialTheme.colorScheme.error else null) { ctl.openSettings(); onNavigate() }
        }
    }
}

private fun memberNames(state: AppState, c: Conversation) = c.memberIds.mapNotNull { state.member(it)?.name }.joinToString("、")

/** 「对话 | Code」两个文字标签，选中的那个变主色、底下一道短横线。 */
@Composable
private fun SidebarTabs(tabs: List<SidebarTab>, tab: SidebarTab, onPick: (SidebarTab) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        tabs.forEach { t ->
            val on = t == tab
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(t) }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.label, style = MaterialTheme.typography.titleSmall,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(22.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

/** 搜索框下面那三张小卡片之一：图标、名字、一行状态（原来侧栏底下的「我的文档」「立场档案」「AI 成员」）。 */
@Composable
private fun RowScope.QuickCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, dot: Color? = null, onClick: () -> Unit) {
    // 按下弹簧缩一下（2026-10-22）；玻璃上底色半透明
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier.weight(1f).springPress(press).clip(RoundedCornerShape(14.dp)).background(cardFill())
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dot != null) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 最底下那一行的「花费」「设置」。 */
@Composable
private fun BottomItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, dot: Color?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (dot != null) {
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        }
    }
}

/** 「对话」那一栏：按时间分组的对话列表（Code 会话不在里面）。新对话按钮和搜索框在 Sidebar 顶上。 */
@Composable
private fun ColumnScope.ChatSessionSidebar(ctl: AppController, state: AppState, query: String, onNavigate: () -> Unit) {
        // Code 会话只在「Code」那一栏列，这里不显示
        val list = state.conversations.filter { !it.isCode }
            .filter { query.isBlank() || it.title.contains(query, true) || memberNames(state, it).contains(query, true) }
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
}

/**
 * 「Code」那一栏（docs/Code栏设计说明.md 第 1、11 节，2026-10-12）：新建按钮 + Code 会话列表
 * （标题、文件夹名、现在什么状态）。状态要看最后一条回答，所以打开这一栏时把这几个会话的记录取回来。
 */
@Composable
private fun ColumnScope.CodeSessionSidebar(ctl: AppController, state: AppState, query: String, access: CodeAccess, onNavigate: () -> Unit) {
    // 「+」在 Sidebar 顶上（这台设备上动不了时不给，见 CodeUi）
    val allMessages by ctl.backend.store.messages.collectAsState()
    val all = state.conversations.filter { it.isCode }.sortedByDescending { it.updatedAt }
    // 状态靠最后一条回答判断；没取过记录的会话取一次（不弹提示）
    androidx.compose.runtime.LaunchedEffect(all.map { it.id }, ctl.backend) {
        all.forEach { c -> if (!ctl.backend.store.hasMessages(c.id)) ctl.run(Command.LoadMessages(c.id), quiet = true) }
    }
    val convs = all.filter { query.isBlank() || it.title.contains(query, true) || it.code?.folder.orEmpty().contains(query, true) }
    if (all.isNotEmpty() && convs.isEmpty()) {
        Text("没找到", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(12.dp))
        Spacer(Modifier.weight(1f))
        return
    }
    if (convs.isEmpty()) {
        Text(
            when {
                access.canAct -> "还没有 Code 会话。新建一个，选好项目文件夹和模型就能让它动手。"
                access == CodeAccess.LOCKED -> "电脑上还没有 Code 会话。要新建得在电脑上做。"
                else -> "还没有 Code 会话。"
            },
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(12.dp))
        Spacer(Modifier.weight(1f))
        return
    }
    LazyColumn(Modifier.weight(1f)) {
        items(convs, key = { it.id }) { c ->
            CodeSessionRow(ctl, state, c, allMessages[c.id].orEmpty(),
                selected = c.id == ctl.currentConvId && ctl.settingsTab == null && ctl.page == null) {
                ctl.openConversation(c.id); onNavigate()
            }
        }
    }
}

@Composable
private fun CodeSessionRow(ctl: AppController, state: AppState, c: Conversation, messages: List<Message>, selected: Boolean, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val status = codeStatus(messages)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(10.dp))
            .background(animatedSelection(selected))
            .clickable(onClick = onClick).padding(start = 8.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(null, 30.dp, emoji = "🛠️", color = 0xFF4F5BEB)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 手机发起的会话：电脑上要一眼看得出来（设计说明第 6 节，第二期）
                if (c.code?.fromPhone == true) {
                    Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(3.dp))
                    Text("手机发起", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(folderName(c.code?.folder.orEmpty()), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Pill(status.label, when (status) {
            CodeStatus.RUNNING -> MaterialTheme.colorScheme.primary
            CodeStatus.WAITING -> Ext.c.warning
            CodeStatus.ERROR -> MaterialTheme.colorScheme.error
            CodeStatus.DONE -> Ext.c.success
            CodeStatus.STOPPED -> Ext.c.subtle
            CodeStatus.NEW -> Ext.c.subtle
        })
        Box {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, "更多", Modifier.size(16.dp), tint = Ext.c.subtle)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) },
                    onClick = { menu = false; renaming = true })
                DropdownMenuItem({ Text("删除", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true })
            }
        }
    }
    if (renaming) {
        var title by remember { mutableStateOf(c.title) }
        AppDialog("重命名会话", { renaming = false }, width = 420.dp, actions = {
            TextButton({ renaming = false }) { Text("取消") }
            Button({ renaming = false; ctl.run(Command.UpdateConversation(c.copy(title = title.trim().ifEmpty { c.title }))) }) { Text("保存") }
        }) { AppTextField(title, { title = it }) }
    }
    if (confirmDelete) {
        AppDialog("删除 Code 会话", { confirmDelete = false }, width = 440.dp, actions = {
            TextButton({ confirmDelete = false }) { Text("取消") }
            Button({
                confirmDelete = false
                if (ctl.currentConvId == c.id) ctl.currentConvId = state.conversations.firstOrNull { it.id != c.id && !it.isCode }?.id
                    ?: state.conversations.firstOrNull { it.id != c.id }?.id
                ctl.run(Command.DeleteConversation(c.id))
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }) {
            Text("「${c.title}」的对话记录，连同它改文件前存的备份一起删掉，不能恢复。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 项目文件夹的最后一节（界面上不显示一长串路径）。 */
internal fun folderName(path: String): String =
    path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { path }

/**
 * 这个 Code 会话现在什么状态（docs/Code栏设计说明.md 第 1 节）：看最后一条回答——
 * 还在等批准、正在跑、出错了、用户按了停止、还是做完了。
 */
enum class CodeStatus(val label: String) {
    NEW("还没有任务"), RUNNING("在跑"), WAITING("等你批准"), STOPPED("停下了"), DONE("做完"), ERROR("出错"),
}

internal fun codeStatus(messages: List<Message>): CodeStatus {
    val last = messages.lastOrNull { it.role == Role.AI } ?: return CodeStatus.NEW
    return when {
        last.status == MsgStatus.STREAMING && last.tools.any { it.approval == "waiting" } -> CodeStatus.WAITING
        last.status == MsgStatus.STREAMING -> CodeStatus.RUNNING
        last.status == MsgStatus.ERROR -> CodeStatus.ERROR
        // 用户自己按了停止：这不算「做完」，侧栏照实说停下了（灰的，不标绿）
        last.status == MsgStatus.STOPPED -> CodeStatus.STOPPED
        else -> CodeStatus.DONE
    }
}

/**
 * 群聊的头像（2026-10-14 改版图）：最多四位成员，每位一个小圆、成员颜色打底、名字最后一个字（阿德 → 德），2×2 排；
 * 两位时上下居中并排，三位时上面两位、下面一位居中；超过四位只画前四位。
 */
@Composable
private fun GroupAvatar(members: List<com.guixing.jixunying.model.Member>, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val cell = size / 2
    val shown = members.take(4)
    val slots = when (shown.size) {
        2 -> listOf(0f to 0.5f, 1f to 0.5f)
        3 -> listOf(0f to 0f, 1f to 0f, 0.5f to 1f)
        else -> listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
    }
    val fontSize = (cell.value * 0.5f).sp
    Box(Modifier.size(size)) {
        shown.forEachIndexed { i, m ->
            val (x, y) = slots[i]
            Box(
                Modifier.offset(cell * x, cell * y).size(cell).padding(0.5.dp).clip(CircleShape).background(Color(m.color)),
                contentAlignment = Alignment.Center,
            ) {
                // 行高等于字号、去掉字体自带的上下留白：不然小圆里的字会被往下挤、下半截被裁掉
                Text(m.name.trim().takeLast(1).ifBlank { "?" }, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = fontSize, lineHeight = fontSize,
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                        ),
                    ))
            }
        }
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
            .background(animatedSelection(selected))
            .clickable(onClick = onClick).padding(start = 8.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            members.isEmpty() -> MemberAvatar(null, 36.dp, emoji = "💬")
            members.size == 1 -> MemberAvatar(members[0], 36.dp)
            else -> GroupAvatar(members)
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
