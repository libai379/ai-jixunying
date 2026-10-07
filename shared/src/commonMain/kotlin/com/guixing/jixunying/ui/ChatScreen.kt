package com.guixing.jixunying.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep
import kotlinx.coroutines.launch

private const val PAINTER = "tool:image"

@Composable
fun ChatScreen(ctl: AppController, convId: String, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val allMessages by ctl.backend.store.messages.collectAsState()
    val conv = state.conversation(convId) ?: return
    val messages = allMessages[convId].orEmpty()
    var showConvSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(state, conv, wide, openDrawer) { showConvSettings = true }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MessageList(ctl, state, conv, messages)
        }
        Composer(ctl, state, conv, running = messages.any { it.status == MsgStatus.STREAMING })
    }
    if (showConvSettings) ConversationSettingsDialog(ctl, state, conv) { showConvSettings = false }
}

@Composable
private fun ChatTopBar(state: AppState, conv: Conversation, wide: Boolean, openDrawer: () -> Unit, onSettings: () -> Unit) {
    val members = conv.memberIds.mapNotNull { state.member(it) }
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!wide) IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(conv.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (members.size > 1) "群聊 · ${members.size} 位成员 · " + when (conv.replyMode) {
                    ReplyMode.INDEPENDENT -> "独立作答"
                    ReplyMode.RELAY -> "接力讨论"
                    ReplyMode.MENTION_ONLY -> "只回答被 @ 的"
                } else members.firstOrNull()?.let { "${it.name} · ${it.modelId}" } ?: "还没有成员",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50)).clickable(onClick = onSettings)
                .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            members.take(4).forEach { MemberAvatar(it, 24.dp); Spacer(Modifier.width(2.dp)) }
            if (members.size > 4) Text("+${members.size - 4}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Tune, "对话设置", Modifier.size(16.dp), tint = Ext.c.subtle)
        }
    }
    androidx.compose.material3.HorizontalDivider(color = Ext.c.border)
}

@Composable
private fun MessageList(ctl: AppController, state: AppState, conv: Conversation, messages: List<Message>) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()
    // 从侧栏搜索结果点进来：滚到那条消息
    LaunchedEffect(ctl.focusMessageId, messages.size) {
        val target = ctl.focusMessageId ?: return@LaunchedEffect
        val i = messages.indexOfFirst { it.id == target }
        if (i >= 0) {
            listState.scrollToItem(i + 1)
            ctl.focusMessageId = null
        }
    }
    // 新消息或流式输出时，如果本来就在底部附近就跟着滚到底
    LaunchedEffect(messages.size, last?.content?.length, last?.attachments?.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        // 自己刚发的消息、或者本来就在底部附近，都滚到底
        val justSent = last?.role == Role.USER
        if (ctl.focusMessageId != null) return@LaunchedEffect
        if (justSent || lastVisible >= info.totalItemsCount - 3 || messages.size <= 2) listState.animateScrollToItem(messages.size)
    }
    if (messages.isEmpty()) {
        ChatEmptyHint(state, conv)
        return
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 点聊天区域收起键盘（和微信一样）
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            if (conv.summarized > 0) {
                Text("前面 ${conv.summarized} 条已由记录员压缩成摘要，AI 记得要点；原话还在这里，AI 需要时也能搜到",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 640.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp))
                Spacer(Modifier.height(8.dp))
            }
        }
        items(messages, key = { it.id }) { m ->
            Box(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (m.role == Role.USER) UserMessage(ctl, state, m) else AiMessage(ctl, state, m)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ChatEmptyHint(state: AppState, conv: Conversation) {
    val members = conv.memberIds.mapNotNull { state.member(it) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { members.take(5).forEach { MemberAvatar(it, 48.dp) } }
        Spacer(Modifier.height(16.dp))
        Text(if (members.size > 1) "群里有 ${members.joinToString("、") { it.name }}" else "和 ${members.firstOrNull()?.name ?: "AI"} 聊聊",
            style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (members.size > 1) "直接提问，大家各自独立回答；用 @名字 点名某位回答，@所有人 让全员回答。\nAI 之间也会互相 @、互相纠错。"
            else "可以发图片、PDF、Word、Excel 等文件；打开「联网」它会先搜再答；想要图直接说「画一张……」。",
            style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle,
        )
        Spacer(Modifier.height(20.dp))
        val tips = listOf("今天有什么值得关注的科技新闻？", "帮我把这份文档总结成 5 条要点", "画一只在月球上喝茶的橘猫", "比较一下这几个方案的优缺点")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tips.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun UserMessage(ctl: AppController, state: AppState, m: Message) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (m.attachments.isNotEmpty()) {
            AttachmentStrip(ctl, m.attachments, alignEnd = true)
            Spacer(Modifier.height(6.dp))
        }
        if (m.content.isNotBlank()) {
            Surface(shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp), color = Ext.c.userBubble, modifier = Modifier.widthIn(max = 620.dp)) {
                SelectionContainer {
                    MarkdownText(m.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ext.c.onUserBubble)
                }
            }
        }
        MessageActions(ctl, m, alignEnd = true, canRegenerate = false)
    }
}

@Composable
private fun AiMessage(ctl: AppController, state: AppState, m: Message) {
    val member = state.member(m.senderId)
    val painter = m.senderId == PAINTER
    val (body, thinkInline) = remember(m.content) { splitThink(m.content) }
    val reasoning = (m.reasoning + if (thinkInline.isNotEmpty()) "\n$thinkInline" else "").trim()
    val sources = remember(m.tools) { m.tools.filter { it.kind == "search" }.flatMap { it.sources } }

    Row(Modifier.fillMaxWidth()) {
        if (painter) MemberAvatar(null, 34.dp, emoji = "🎨", color = 0xFFEC4899) else MemberAvatar(member, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (painter) "画图助手" else member?.name ?: "已移除的成员", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                if (m.modelLabel.isNotBlank()) Pill(m.modelLabel, Ext.c.subtle)
            }
            Spacer(Modifier.height(6.dp))
            ToolStepsView(m.tools, streaming = m.status == MsgStatus.STREAMING)
            if (reasoning.isNotEmpty()) ReasoningView(reasoning, streaming = m.status == MsgStatus.STREAMING && body.isEmpty())
            if (body.isNotBlank()) SelectionContainer { MarkdownText(body, sources = sources) }
            if (m.status == MsgStatus.STREAMING && body.isEmpty() && reasoning.isEmpty()) TypingDots()
            if (m.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AttachmentStrip(ctl, m.attachments, alignEnd = false, large = true)
            }
            if (m.status == MsgStatus.ERROR) ErrorBox(m.error)
            if (m.status == MsgStatus.STOPPED) Text("（已停止）", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
            if (sources.isNotEmpty() && m.status != MsgStatus.STREAMING) SourcesRow(sources)
            if (m.status != MsgStatus.STREAMING) MessageActions(ctl, m, alignEnd = false, canRegenerate = true)
        }
    }
}

@Composable
private fun TypingDots() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("正在思考…", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    }
}

/** 工具步骤：两步以内照常列出；多了就折叠成一行摘要，点开再看明细，免得刷屏。 */
@Composable
private fun ToolStepsView(tools: List<ToolStep>, streaming: Boolean) {
    if (tools.size <= 2) { tools.forEach { ToolStepView(it) }; return }
    var open by remember { mutableStateOf(false) }
    val searches = tools.count { it.kind == "search" }
    val pages = tools.count { it.kind == "fetch" }
    val images = tools.count { it.kind == "image" }
    val docs = tools.count { it.kind == "doc" }
    val history = tools.count { it.kind == "history" }
    val memos = tools.count { it.kind == "memory" }
    val summary = buildList {
        if (searches > 0) add("联网搜了 $searches 次")
        if (pages > 0) add("读了 $pages 个网页")
        if (docs > 0) add("查了 $docs 次文档")
        if (history > 0) add("翻了 $history 次聊天记录")
        if (memos > 0) add("记了 $memos 条")
        if (images > 0) add("画了 $images 张图")
    }.joinToString(" · ")
    val latest = tools.last()
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Language, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                if (streaming) "$summary · 正在${if (latest.kind == "fetch") "读网页" else if (latest.kind == "image") "画图" else "搜「${latest.input.take(16)}」"}…" else summary,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp),
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 8.dp, top = 6.dp)) { tools.forEach { ToolStepView(it) } }
        }
    }
}

@Composable
private fun ToolStepView(t: ToolStep) {
    var open by remember { mutableStateOf(false) }
    val (icon, label) = when (t.kind) {
        "search" -> Icons.Rounded.Language to (if (t.ok) "搜索「${t.input}」· ${t.sources.size} 条结果" else "搜索「${t.input}」失败")
        "fetch" -> Icons.Rounded.Description to (if (t.ok) "阅读网页 ${t.input.take(60)}" else "网页打不开 ${t.input.take(60)}")
        "memory" -> Icons.Rounded.Psychology to (if (t.ok) "记住了：${t.input.take(40)}" else "没记：${t.input.take(40)}")
        "history" -> Icons.Rounded.History to (if (t.ok) "翻了以前的聊天「${t.input.take(24)}」" else "以前的聊天里没找到「${t.input.take(24)}」")
        "doc" -> Icons.Rounded.FolderOpen to (if (t.ok) t.input.take(60) else "${t.input.take(50)}（没找到）")
        else -> Icons.Rounded.Brush to (if (t.ok) "画图：${t.input.take(40)}" else "画图失败")
    }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = t.sources.isNotEmpty() || t.note.isNotEmpty()) { open = !open }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp))
            if (t.sources.isNotEmpty() || t.note.isNotEmpty()) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (t.note.isNotEmpty()) Text(t.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                val platform = LocalPlatform.current
                t.sources.forEachIndexed { i, s ->
                    Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable { platform.openUrl(s.url) }.padding(4.dp)) {
                        Text("${i + 1}. ${s.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.snippet.isNotBlank()) Text(s.snippet, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningView(text: String, streaming: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(15.dp), tint = Ext.c.subtle)
            Spacer(Modifier.width(4.dp))
            Text(if (streaming) "正在推理…" else "推理过程（${text.length} 字）", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        AnimatedVisibility(open || streaming) {
            Row(Modifier.padding(top = 4.dp)) {
                Box(Modifier.width(2.dp).heightIn(min = 20.dp).background(Ext.c.border))
                Spacer(Modifier.width(10.dp))
                Text(if (streaming) text.takeLast(600) else text, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
    }
}

@Composable
private fun SourcesRow(sources: List<com.guixing.jixunying.model.SearchSource>) {
    val platform = LocalPlatform.current
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.take(12).forEachIndexed { i, s ->
            val host = s.url.substringAfter("://").substringBefore('/').removePrefix("www.")
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(8.dp)).clickable { platform.openUrl(s.url) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Text(host.take(28), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorBox(error: String) {
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        SelectionContainer { Text(error.ifBlank { "出错了" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
    }
}

@Composable
private fun MessageActions(ctl: AppController, m: Message, alignEnd: Boolean, canRegenerate: Boolean) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.content.isNotBlank()) SmallAction(Icons.Rounded.ContentCopy, "复制") { clipboard.setText(AnnotatedString(splitThink(m.content).first)); ctl.toast("已复制") }
        if (canRegenerate) SmallAction(Icons.Rounded.Refresh, "重新回答") { ctl.run(Command.Regenerate(m.convId, m.id)) }
        SmallAction(Icons.Rounded.Delete, "删除") { ctl.run(Command.DeleteMessage(m.convId, m.id)) }
        m.usage?.let { u ->
            if (u.prompt + u.completion > 0 || u.millis > 0) {
                Spacer(Modifier.width(6.dp))
                Text(buildString {
                    if (u.millis > 0) append("${(u.millis / 100) / 10.0} 秒")
                    if (u.prompt + u.completion > 0) append(" · 输入 ${u.prompt}" + (if (u.cached > 0) "（缓存 ${u.cached}）" else "") + " / 输出 ${u.completion} tokens")
                }, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(15.dp), tint = Ext.c.subtle)
    }
}

// ———————————————— 附件和图片 ————————————————

@Composable
fun rememberImage(ctl: AppController, id: String): ImageBitmap? {
    val platform = LocalPlatform.current
    val img by produceState<ImageBitmap?>(null, id) {
        value = ImageCache.get(id) ?: ctl.backend.fileBytes(id)?.let { platform.decodeImage(it) }?.also { ImageCache.put(id, it) }
    }
    return img
}

object ImageCache {
    private val map = LinkedHashMap<String, ImageBitmap>()
    fun get(id: String) = map[id]
    fun put(id: String, img: ImageBitmap) {
        map[id] = img
        while (map.size > 60) map.remove(map.keys.first())
    }
}

@Composable
private fun AttachmentStrip(ctl: AppController, atts: List<Attachment>, alignEnd: Boolean, large: Boolean = false) {
    var preview by remember { mutableStateOf<Attachment?>(null) }
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val images = atts.filter { it.kind != AttachmentKind.DOCUMENT }
        val docs = atts.filter { it.kind == AttachmentKind.DOCUMENT }
        if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            images.forEach { a ->
                val img = rememberImage(ctl, a.id)
                val side = if (large) 300.dp else 120.dp
                Box(
                    Modifier.size(side).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, Ext.c.border, RoundedCornerShape(12.dp)).clickable { preview = a },
                    contentAlignment = Alignment.Center,
                ) {
                    if (img != null) androidx.compose.foundation.Image(img, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        docs.forEach { a -> DocChip(a) }
    }
    preview?.let { ImagePreview(ctl, it) { preview = null } }
}

@Composable
private fun DocChip(a: Attachment, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp))
            .padding(start = 8.dp, end = if (onRemove != null) 2.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ext = a.name.substringAfterLast('.', "").uppercase().take(4)
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(docColor(ext)), contentAlignment = Alignment.Center) {
            Text(ext.ifEmpty { "文件" }, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatSize(a.size) + if (a.textChars > 0) " · ${a.textChars} 字" else if (a.note.isNotBlank()) " · ${a.note}" else "",
                style = MaterialTheme.typography.labelSmall, color = if (a.textChars == 0 && a.note.isNotBlank()) MaterialTheme.colorScheme.error else Ext.c.subtle,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) IconButton(onClick = onRemove, Modifier.size(26.dp)) { Icon(Icons.Rounded.Close, "移除", Modifier.size(14.dp)) }
    }
}

private fun docColor(ext: String) = when (ext) {
    "PDF" -> Color(0xFFE5484D)
    "DOCX", "DOC" -> Color(0xFF2B6CEB)
    "XLSX", "XLS", "CSV" -> Color(0xFF16A34A)
    "PPTX", "PPT" -> Color(0xFFEA7A1E)
    "MD", "TXT" -> Color(0xFF64748B)
    else -> Color(0xFF7C3AED)
}

@Composable
private fun ImagePreview(ctl: AppController, a: Attachment, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val img = rememberImage(ctl, a.id)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).clickable(onClick = onClose).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val bytes = ctl.backend.fileBytes(a.id) ?: return@launch
                        if (platform.saveFile(a.name, bytes)) ctl.toast("已保存")
                    }
                }) {
                    Icon(Icons.Rounded.Download, null, tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("保存", color = Color.White)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (img != null) androidx.compose.foundation.Image(img, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else CircularProgressIndicator(color = Color.White)
            }
            if (a.note.isNotBlank()) Text(a.note, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ———————————————— 输入框 ————————————————

private class PendingFile(val name: String, var att: Attachment? = null, var failed: Boolean = false)

@Composable
private fun Composer(ctl: AppController, state: AppState, conv: Conversation, running: Boolean) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var text by remember(conv.id) { mutableStateOf(TextFieldValue("")) }
    var drawMode by remember(conv.id) { mutableStateOf(false) }
    val pending = remember(conv.id) { mutableStateListOf<PendingFile>() }
    var uploading by remember { mutableStateOf(0) }
    val members = conv.memberIds.mapNotNull { state.member(it) }

    // 输入「@」后提示成员
    val mentionQuery = remember(text) {
        val before = text.text.substring(0, text.selection.start.coerceIn(0, text.text.length))
        val at = before.lastIndexOf('@')
        if (at >= 0 && before.substring(at + 1).none { it.isWhitespace() } && before.length - at <= 12) before.substring(at + 1) else null
    }
    // 候选：对话里的成员在前；不在对话里的也列出来，@ 了会被拉进对话
    val outsiders = state.members.filter { it.id !in conv.memberIds }
    val suggestions = if (mentionQuery == null) emptyList() else
        (members.map { it.name to it } + (if (members.size > 1) listOf("所有人" to null) else emptyList()) + outsiders.map { it.name to it })
            .filter { it.first.contains(mentionQuery, true) }

    fun insertMention(name: String) {
        val cursor = text.selection.start
        val before = text.text.substring(0, cursor)
        val at = before.lastIndexOf('@')
        val newText = text.text.substring(0, at) + "@$name " + text.text.substring(cursor)
        val pos = at + name.length + 2
        text = TextFieldValue(newText, TextRange(pos))
    }

    fun pick(imagesOnly: Boolean) {
        scope.launch {
            val files = platform.pickFiles(imagesOnly)
            files.forEach { f ->
                val p = PendingFile(f.name)
                pending.add(p)
                uploading++
                scope.launch {
                    val att = ctl.backend.upload(f.name, f.mime, f.bytes)
                    val idx = pending.indexOf(p)
                    if (idx >= 0) pending[idx] = PendingFile(f.name, att, att == null)
                    uploading--
                    if (att == null) ctl.toast("「${f.name}」上传失败")
                }
            }
        }
    }

    // AI 还在回答或画图时也能接着发：新消息另起一轮，不用等
    fun send() {
        if (uploading > 0) return
        val ids = pending.mapNotNull { it.att?.id }
        if (text.text.isBlank() && ids.isEmpty()) return
        ctl.run(Command.SendMessage(conv.id, text.text, ids, drawImage = drawMode))
        text = TextFieldValue("")
        pending.clear()
        // 「直接画图」只管这一条，发完回到正常聊天
        drawMode = false
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
            if (suggestions.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Ext.c.border), shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp)) {
                    Column(Modifier.padding(4.dp)) {
                        suggestions.take(8).forEach { (name, m) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { insertMention(name) }.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (m != null) MemberAvatar(m, 24.dp) else MemberAvatar(null, 24.dp, emoji = "👥")
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                if (m != null) {
                                    Spacer(Modifier.width(6.dp))
                                    val outside = m.id !in conv.memberIds
                                    Text(if (outside) "不在这个对话里，@ 了会拉进来" else m.modelId, style = MaterialTheme.typography.labelSmall,
                                        color = if (outside) Ext.c.warning else Ext.c.subtle, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (drawMode) MaterialTheme.colorScheme.secondary else Ext.c.border),
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
                    if (pending.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pending.forEach { p ->
                                val att = p.att
                                if (att == null) {
                                    Row(
                                        Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (p.failed) Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                        else CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                        if (p.failed) IconButton(onClick = { pending.remove(p) }, Modifier.size(22.dp)) { Icon(Icons.Rounded.Close, null, Modifier.size(12.dp)) }
                                    }
                                } else if (att.kind == AttachmentKind.IMAGE) {
                                    val img = rememberImage(ctl, att.id)
                                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        if (img != null) androidx.compose.foundation.Image(img, att.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { pending.remove(p) },
                                            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "移除", Modifier.size(11.dp), tint = Color.White) }
                                    }
                                } else DocChip(att) { pending.remove(p) }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 26.dp, max = 200.dp)) {
                        if (text.text.isEmpty()) Text(
                            when {
                                drawMode -> "直接画图：描述画面，比如「水墨风格的江南小镇，清晨薄雾」（只画这一张，发完回到聊天）"
                                members.size > 1 -> if (platform.isDesktop) "发消息…  @名字 点名回答，Enter 发送，Shift+Enter 换行" else "发消息…  @名字 点名回答"
                                else -> if (platform.isDesktop) "发消息，可以附图片和文档…  Enter 发送，Shift+Enter 换行" else "发消息，可以附图片和文档"
                            },
                            style = MaterialTheme.typography.bodyLarge, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).onPreviewKeyEvent { e ->
                                if (platform.isDesktop && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                                    if (suggestions.isNotEmpty()) insertMention(suggestions.first().first) else send()
                                    true
                                } else false
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ComposerIcon(Icons.Rounded.AttachFile, "附件（图片、PDF、Word、Excel、PPT、代码…）") { pick(false) }
                        ComposerIcon(Icons.Rounded.Image, "图片") { pick(true) }
                        if (state.members.size > 1) ComposerIcon(Icons.Rounded.AlternateEmail, "@ 成员（也能 @ 不在这个对话里的成员，会把他拉进来）") {
                            text = TextFieldValue(text.text + (if (text.text.isEmpty() || text.text.endsWith(" ")) "@" else " @"), TextRange(Int.MAX_VALUE))
                        }
                        Spacer(Modifier.width(6.dp))
                        ToggleChip("联网", Icons.Rounded.Language, conv.webSearch) {
                            ctl.run(Command.UpdateConversation(conv.copy(webSearch = !conv.webSearch)))
                        }
                        Spacer(Modifier.width(6.dp))
                        ToggleChip("直接画图", Icons.Rounded.Brush, drawMode) { drawMode = !drawMode }
                        Spacer(Modifier.weight(1f))
                        // 有回答 / 画图在进行时，停止按钮单独放在发送键旁边，不挡着发新消息
                        if (running) {
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50))
                                    .clickable { ctl.run(Command.Stop(conv.id)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Stop, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(3.dp))
                                Text("停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        val canSend = (text.text.isNotBlank() || pending.any { it.att != null }) && uploading == 0
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (canSend) (if (drawMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(enabled = canSend) { send() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (drawMode) Icons.Rounded.Brush else Icons.Rounded.ArrowUpward,
                                if (drawMode) "画图" else "发送", Modifier.size(19.dp),
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else Ext.c.subtle,
                            )
                        }
                    }
                }
            }
            Text(
                "AI 也会出错，重要信息请核实。" + when {
                    ctl.remoteMode -> "这个对话在电脑上运行和保存。"
                    platform.isDesktop -> "聊天记录只存在这台电脑上。"
                    else -> "聊天记录只存在这台手机上。"
                },
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ComposerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ———————————————— 对话设置 ————————————————

@Composable
private fun ConversationSettingsDialog(ctl: AppController, state: AppState, conv: Conversation, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(conv.memberIds.toSet()) }
    var mode by remember { mutableStateOf(conv.replyMode) }
    var search by remember { mutableStateOf(conv.webSearch) }
    var title by remember { mutableStateOf(conv.title) }
    AppDialog("对话设置", onClose, width = 560.dp, actions = {
        TextButton(onClose) { Text("取消") }
        Button(enabled = picked.isNotEmpty(), onClick = {
            onClose()
            ctl.run(Command.UpdateConversation(conv.copy(
                title = title.trim().ifEmpty { conv.title },
                memberIds = state.members.map { it.id }.filter { it in picked } + conv.memberIds.filter { it in picked && state.member(it) == null },
                replyMode = mode, webSearch = search,
            )))
        }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            FieldLabel("标题")
            AppTextField(title, { title = it })
            Spacer(Modifier.height(12.dp))
            FieldLabel("成员", "选多位就是群聊")
            MemberPickList(state, picked) { id -> picked = if (id in picked) picked - id else picked + id }
            if (picked.size > 1) {
                Spacer(Modifier.height(12.dp))
                FieldLabel("没人被 @ 时怎么回答")
                listOf(
                    ReplyMode.INDEPENDENT to ("独立作答（推荐）" to "每人各答各的，互相看不到，避免跟风附和；答完可以互相 @ 纠错"),
                    ReplyMode.RELAY to ("接力讨论" to "依次发言，后面的人看得到前面的回答，适合头脑风暴"),
                    ReplyMode.MENTION_ONLY to ("只回答被 @ 的" to "没 @ 人时由第一位成员回答，省 token"),
                ).forEach { (m, t) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { mode = m }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == m, { mode = m })
                        Column {
                            Text(t.first, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(t.second, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("联网搜索", "需要时先搜索再回答，回答里标出处", search) { search = it }
        }
    }
}
