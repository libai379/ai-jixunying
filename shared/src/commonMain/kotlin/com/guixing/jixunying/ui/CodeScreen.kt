package com.guixing.jixunying.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeModels
import com.guixing.jixunying.model.CodePhone
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.FileChange
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.Thinking
import com.guixing.jixunying.model.ToolStep
import kotlinx.coroutines.launch

/** 一步工具展开以后最多显示多少行（长的读写和命令输出在这里只给个开头，改动看差异查看器）。 */
private const val STEP_LINES = 80

/**
 * Code 会话的主区域（docs/Code栏设计说明.md 第 1、6、11 节，2026-10-12 / 第二期 2026-10-13）。
 * 上面是会话名、文件夹、权限档和「停止」，中间按顺序显示说过的话和每一步工具，下面是输入框。
 * 第二期起手机上也能动手：手机遥控电脑、且电脑上打开了「允许手机用 Code 栏」时，发任务、停止、
 * 批准某一步、逐个文件接受 / 拒绝都能用；「接受编辑」档和「全部撤销」只能在电脑上做（CodeUi / CodePhone）。
 */
@Composable
fun CodeScreen(ctl: AppController, convId: String, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val allMessages by ctl.backend.store.messages.collectAsState()
    val conv = state.conversation(convId) ?: return
    val spec = conv.code ?: return
    val messages = allMessages[convId].orEmpty()
    // 能不能动手：电脑上照旧；手机上要连着电脑、电脑上还开着「允许手机用 Code 栏」
    val access = CodeUi.access(LocalPlatform.current.isDesktop, ctl.remoteMode, state.settings.code.allowPhone)
    // 差异查看器打开在哪个回答上（点「改了 N 个文件」进来）
    var diffMessage by remember(convId) { mutableStateOf("") }
    LaunchedEffect(convId, ctl.backend) {
        if (!ctl.backend.store.hasMessages(convId)) ctl.run(Command.LoadMessages(convId), quiet = true)
    }
    // 开发截图用：-Djxy.start=code:会话编号#diff 直接把差异查看器打开（只开一次，关掉不再弹）
    var debugOpened by remember(convId) { mutableStateOf(false) }
    LaunchedEffect(convId, messages.size) {
        if (!debugOpened && ctl.debugDialog == "diff" && messages.isNotEmpty()) {
            debugOpened = true
            diffMessage = messages.lastOrNull { m -> m.tools.any { it.change != null } }?.id.orEmpty()
        }
    }
    val running = messages.lastOrNull()?.status == MsgStatus.STREAMING

    Column(Modifier.fillMaxSize()) {
        CodeTopBar(ctl, state, conv, spec, wide, openDrawer, running, access)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CodeMessageList(ctl, state, conv, messages, access.canAct) { diffMessage = it }
        }
        if (access.canAct) CodeComposer(ctl, conv, running) else CodeBottomNote(access)
    }
    if (diffMessage.isNotBlank()) ChangesDialog(ctl, conv, diffMessage, access) { diffMessage = "" }
}

/**
 * 手机上动不了时的说明（原来放输入框的位置）：开关没开写清去哪儿打开，本机写清要先连上电脑。
 */
@Composable
private fun CodeBottomNote(access: CodeAccess) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(Ext.c.sidebarSelected).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Computer, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        Spacer(Modifier.width(8.dp))
        Text(
            if (access == CodeAccess.LOCKED) CodeUi.LOCKED_NOTE else CodeUi.NO_LOCAL_NOTE,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodeTopBar(ctl: AppController, state: AppState, conv: Conversation, spec: CodeSpec,
                       wide: Boolean, openDrawer: () -> Unit, running: Boolean, access: CodeAccess) {
    val provider = state.provider(spec.providerId)
    // 窄屏那一行只放得下文件夹的最后一节，点一下弹完整的路径（下面弹窗）
    var showPath by remember(conv.id) { mutableStateOf(false) }
    // 开发截图用：-Djxy.start=code:会话编号#path 直接把这个弹窗打开
    LaunchedEffect(Unit) { if (ctl.debugDialog == "path") showPath = true }
    val subtitle = (if (wide) spec.folder else folderName(spec.folder)) + (provider?.let { " · ${it.name}" } ?: "") + " · ${spec.modelId}"
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!wide) IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conv.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                // 手机发起的会话：电脑上要一眼看得出来（设计说明第 6 节）
                if (spec.fromPhone) {
                    Spacer(Modifier.width(8.dp))
                    PhoneMadeMark()
                }
            }
            if (wide) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                // 手机上放不下整条路径：显示最后一节，前面加个文件夹图标、点一下看全路径
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { showPath = true }.padding(vertical = 2.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        if (running && access.canAct) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50))
                    .clickable { ctl.run(Command.CodeStop(conv.id)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Stop, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.width(10.dp))
        }
        CodeModePicker(spec.mode, CodeUi.modesFor(access, spec), enabled = access.canAct) { m ->
            ctl.run(Command.CodeSetMode(conv.id, m), okText = "权限档换成「${m.label}」")
        }
    }
    HorizontalDivider(color = Ext.c.border)
    if (showPath) {
        val clipboard = LocalClipboardManager.current
        AppDialog("项目文件夹", { showPath = false }, width = 520.dp, actions = {
            TextButton({ clipboard.setText(AnnotatedString(spec.folder)); ctl.toast("已复制路径") }) { Text("复制路径") }
            Button({ showPath = false }) { Text("知道了") }
        }) {
            Text("AI 只在这个文件夹里读代码、改文件、跑命令，别的地方碰不到。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(8.dp))
            SelectionContainer { Text(spec.folder, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * 「手机发起」小标记（设计说明第 6 节）：手机开出来的 Code 会话，电脑上要一眼看得出来。
 */
@Composable
private fun PhoneMadeMark() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Text("手机发起", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 权限档切换：点一下就生效，不用再去设置里改。
 * modes 是这台设备上能选的档（手机上只有手动、计划，见 CodeUi.modesFor）。
 */
@Composable
private fun CodeModePicker(mode: CodeMode, modes: List<CodeMode>, enabled: Boolean, onPick: (CodeMode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50)).padding(2.dp),
    ) {
        modes.forEach { m ->
            val on = m == mode
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable(enabled = enabled) { if (!on) onPick(m) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(m.label, style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.primary else Ext.c.subtle)
            }
        }
    }
}

@Composable
private fun CodeMessageList(ctl: AppController, state: AppState, conv: Conversation, messages: List<Message>, canAct: Boolean, onDiff: (String) -> Unit) {
    // 和聊天一样从底下往上排：打开会话停在最新一行的最后（教训 60），每个会话各记各的位置
    val listState = remember(conv.id) { LazyListState() }
    val newestFirst = remember(messages) { messages.asReversed() }
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.content?.length, last?.tools?.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val justSent = last?.role == Role.USER
        if (justSent || listState.firstVisibleItemIndex in 1..2) listState.animateScrollToItem(0)
    }
    if (messages.isEmpty()) {
        CodeStartHint(conv)
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        items(newestFirst, key = { it.id }) { m ->
            Box(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (m.role == Role.USER) CodeUserMessage(ctl, m) else CodeAiMessage(ctl, state, m, canAct, onDiff)
            }
        }
        item(key = "top") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                Text("这个会话只在这个项目文件夹里读写：${conv.code?.folder.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 刚建好的会话：说一句要它做什么就行。 */
@Composable
private fun CodeStartHint(conv: Conversation) {
    val folder = conv.code?.folder.orEmpty()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 620.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Computer, null, Modifier.size(40.dp), tint = Ext.c.subtle)
            Spacer(Modifier.height(14.dp))
            Text(conv.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(folder, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(14.dp))
            // 说法不绑定某一档（主会话审查 10-12 改）：「接受编辑」档改文件是不问的
            Text("在下面说一句要它做什么：读代码、改文件、跑命令都行。改文件、跑命令前要不要先问你，看右上角的权限档；" +
                "改过的文件都能在差异里拒绝，文件会恢复成改之前的样子。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CodeUserMessage(ctl: AppController, m: Message) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp), color = Ext.c.userBubble, modifier = Modifier.widthIn(max = 620.dp)) {
            SelectionContainer { MarkdownText(m.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ext.c.onUserBubble) }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallCodeAction(Icons.Rounded.ContentCopy, "复制") { clipboard.setText(AnnotatedString(m.content)); ctl.toast("已复制") }
        }
    }
}

@Composable
private fun CodeAiMessage(ctl: AppController, state: AppState, m: Message, canAct: Boolean, onDiff: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val (body, thinkInline) = remember(m.content) { splitThink(m.content) }
    val reasoning = (m.reasoning + if (thinkInline.isNotEmpty()) "\n$thinkInline" else "").trim()
    val changes = remember(m.tools) { m.tools.mapNotNull { it.change } }
    Row(Modifier.fillMaxWidth()) {
        MemberAvatar(null, 34.dp, emoji = "🛠️", color = 0xFF4F5BEB)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Code 助手", style = MaterialTheme.typography.titleSmall)
                if (m.modelLabel.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Pill(m.modelLabel, Ext.c.subtle)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 每一步工具一行，点开看输入和输出；等批准的那一步自己展开
            m.tools.forEach { CodeToolStep(ctl, m, it, canAct) }
            if (reasoning.isNotEmpty()) CodeReasoning(reasoning, streaming = m.status == MsgStatus.STREAMING && body.isEmpty())
            if (body.isNotBlank()) SelectionContainer { MarkdownText(body) }
            if (m.status == MsgStatus.STREAMING && body.isEmpty() && reasoning.isEmpty() && m.tools.isEmpty()) CodeThinking()
            if (changes.isNotEmpty()) ChangesBar(changes) { onDiff(m.id) }
            if (m.status == MsgStatus.ERROR) CodeErrorBox(ctl, m.error)
            if (m.status == MsgStatus.STOPPED) Text("（已停止）", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
            if (m.status != MsgStatus.STREAMING && body.isNotBlank()) {
                Row(Modifier.padding(top = 4.dp)) {
                    SmallCodeAction(Icons.Rounded.ContentCopy, "复制") { clipboard.setText(AnnotatedString(body)); ctl.toast("已复制") }
                }
            }
        }
    }
}

@Composable
private fun CodeThinking() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("正在想…", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    }
}

/** 一步工具：一行摘要，点开看输入和输出；等批准的那一步默认展开，带「允许」「拒绝」。 */
@Composable
private fun CodeToolStep(ctl: AppController, msg: Message, t: ToolStep, canAct: Boolean) {
    // 这台设备上动不了（手机本机、或者电脑上没开「允许手机用 Code 栏」）时只能看
    val readonly = !canAct
    val waiting = t.approval == "waiting"
    var open by remember(t.id) { mutableStateOf(false) }
    val expanded = open || waiting
    val (icon, label) = codeStepLine(t)
    val tint = when {
        waiting -> Ext.c.warning
        t.approval == "denied" || !t.ok -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    // 要跑的命令：等批准的时候也要看得见
                    t.kind == "run" -> {
                        if (waiting) Text(if (readonly) "这条命令在等你批准：" else "要跑这条命令：", style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
                        MonoBox(t.input)
                        if (!waiting && t.output.isNotBlank()) MonoBox(t.output)
                    }
                    // 改文件：看差异
                    t.kind == "write" || t.kind == "edit" -> {
                        if (waiting) Text(if (readonly) "这处改动在等你批准：" else "要改这个文件：", style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
                        if (t.output.isNotBlank()) ScrollPane { DiffLines(t.output) }
                    }
                    // 读文件 / 列目录 / 查找：看结果
                    else -> if (t.output.isNotBlank()) MonoBox(t.output)
                }
                when {
                    waiting -> if (readonly) {
                        Text("要批准就打开电脑上的这个会话。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { ctl.run(Command.CodeDecide(msg.convId, t.id, true)) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            ) { Text("允许") }
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(50))
                                    .clickable { ctl.run(Command.CodeDecide(msg.convId, t.id, false)) }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) { Text("拒绝", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    t.approval == "denied" -> Text(deniedText(t), style = MaterialTheme.typography.bodySmall, color = Ext.c.warning)
                    t.note.isNotEmpty() -> Text(t.note, style = MaterialTheme.typography.bodySmall,
                        color = if (t.ok) Ext.c.subtle else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 一行摘要：做了什么 + 这条的结果。 */
private fun codeStepLine(t: ToolStep): Pair<ImageVector, String> {
    val where = t.input.ifBlank { "." }
    val head = when (t.kind) {
        "read" -> "读了 ${t.input}"
        "list" -> if (where == ".") "列了项目根目录" else "列了 ${t.input}"
        "grep" -> "查找「${t.input}」"
        "write" -> "写了 ${t.input}"
        "edit" -> "改了 ${t.input}"
        "run" -> "跑了「${t.input.take(80)}」"
        else -> "${t.kind} ${t.input}"
    }
    val tail = when {
        t.approval == "waiting" -> " · 等你批准"
        t.approval == "denied" -> " · 没有做"
        t.kind == "run" && t.note.isNotEmpty() -> " · ${t.note}"
        t.kind == "write" || t.kind == "edit" -> t.change?.let { "（+${it.added} −${it.removed}）" } ?: ""
        t.kind == "grep" && !t.ok -> " · 没找到"
        t.kind == "read" && !t.ok -> " · 读不了"
        !t.ok -> " · 没成功"
        else -> ""
    }
    return when (t.kind) {
        "read" -> Icons.Rounded.Description to (head + tail)
        "list" -> Icons.Rounded.FolderOpen to (head + tail)
        "grep" -> Icons.Rounded.Search to (head + tail)
        "write" -> Icons.Rounded.Add to (head + tail)
        "edit" -> Icons.Rounded.Edit to (head + tail)
        else -> Icons.Rounded.Computer to (head + tail)
    }
}

/** 拒绝这一步到底是为什么：用户点的，还是计划档根本不给工具。 */
private fun deniedText(t: ToolStep) = when {
    t.note.contains("计划档") -> "计划档不许改文件，这一步没有执行。想做就在上面把权限档换成「手动」或「接受编辑」。"
    t.note.contains("停下来") -> "停下来的时候这一步没做。"
    else -> "你拒绝了这一步，没有做。"
}

@Composable
private fun CodeReasoning(text: String, streaming: Boolean) {
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
                SelectionContainer { Text(if (streaming) text.takeLast(600) else text, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle) }
            }
        }
    }
}

@Composable
private fun CodeErrorBox(ctl: AppController, error: String) {
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            SelectionContainer { Text(error.ifBlank { "出错了" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            // 模型和 Key 的问题最多：直接给个按钮过去
            val fix = when {
                "模型" in error || "Key" in error || "余额" in error || "工具" in error -> SettingsTab.PROVIDERS to "去模型服务"
                else -> null
            }
            if (fix != null) TextButton(onClick = { ctl.openSettings(fix.first) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                Text(fix.second + " ›", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 「改了 N 个文件 +X −Y」：点开是差异查看器。 */
@Composable
private fun ChangesBar(changes: List<FileChange>, onClick: () -> Unit) {
    val files = changes.map { it.path }.distinct().size
    val added = changes.sumOf { it.added }
    val removed = changes.sumOf { it.removed }
    val accepted = changes.count { it.review == "accepted" }
    val rejected = changes.count { it.review == "rejected" }
    Row(
        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Ext.c.sidebarSelected).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Edit, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("改了 $files 个文件", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text("+$added", style = MaterialTheme.typography.labelMedium, color = Ext.c.success)
        Spacer(Modifier.width(6.dp))
        Text("−$removed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        if (accepted > 0 || rejected > 0) {
            Spacer(Modifier.width(10.dp))
            Text(buildString {
                if (accepted > 0) append("已接受 $accepted")
                if (accepted > 0 && rejected > 0) append(" · ")
                if (rejected > 0) append("已拒绝 $rejected")
            }, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
        Spacer(Modifier.width(10.dp))
        Text("看改动 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

// ———————————————— 差异查看器 ————————————————

/**
 * 差异查看器（docs/Code栏设计说明.md 第 1 节）：左边文件列表，右边这个文件改了什么（窄屏上下排）。
 * 每个文件能「接受」「拒绝」，拒绝前先问一句；底下还能「全部撤销」这条回答里的改动。
 */
@Composable
private fun ChangesDialog(ctl: AppController, conv: Conversation, messageId: String, access: CodeAccess, onClose: () -> Unit) {
    // 手机上能动手时，逐个文件接受 / 拒绝和电脑上一样能用；只有「全部撤销」得回电脑上做
    val readonly = !access.canAct
    val all by ctl.backend.store.messages.collectAsState()
    val msg = all[conv.id].orEmpty().firstOrNull { it.id == messageId } ?: return
    val changes = msg.tools.mapNotNull { it.change }.distinctBy { it.path }
    // 还没处理的改动，它那一步的 output 里本来就存着同一段差异（写文件时算出来的），先拿来当内容：
    // 打开就能看见，不用先对着转圈；下面再问引擎要一次（看文件现在的样子），拿回来会盖掉。
    val diffs = remember(messageId) {
        mutableStateMapOf<String, String>().apply {
            changes.filter { it.review.isEmpty() }.forEach { c ->
                msg.tools.firstOrNull { it.change?.id == c.id }?.output?.takeIf { it.isNotBlank() }?.let { put(c.id, it) }
            }
        }
    }
    val diffErr = remember(messageId) { mutableStateMapOf<String, String>() }
    var picked by remember(messageId) { mutableStateOf(changes.firstOrNull()?.id.orEmpty()) }
    var reload by remember(messageId) { mutableStateOf(0) }
    var confirmReject by remember(messageId) { mutableStateOf("") }
    var confirmRevert by remember(messageId) { mutableStateOf(false) }
    // 看选中那个文件改了什么（拒绝 / 撤销之后文件变了，重新取一遍）
    LaunchedEffect(picked, reload) {
        if (picked.isBlank()) return@LaunchedEffect
        ctl.run(Command.CodeDiff(conv.id, picked), quiet = true) { r ->
            if (r.ok) { diffs[picked] = r.data; diffErr.remove(picked) }
            else diffErr[picked] = r.message.ifBlank { "看不了这处改动" }
        }
    }
    fun review(id: String, accept: Boolean) {
        ctl.run(Command.CodeReview(conv.id, id, accept), quiet = true) { r ->
            if (!r.ok) ctl.toast(if (r.message.isBlank()) "没成功" else r.message)
            else if (r.message.isNotBlank()) ctl.toast(r.message)
            diffs.remove(id) // 文件变了，重新拿一遍
            reload++
        }
    }
    val current = changes.firstOrNull { it.id == picked }
    BoxWithConstraints {
        val side = maxWidth >= 640.dp
        AppDialog("这次改了 ${changes.size} 个文件", onClose, width = if (side) 1000.dp else 560.dp, actions = {
            if (confirmRevert) {
                TextButton({ confirmRevert = false }) { Text("取消") }
                Button({
                    confirmRevert = false
                    ctl.run(Command.CodeRevertTask(conv.id, messageId), quiet = true) { r ->
                        if (!r.ok) ctl.toast(r.message.ifBlank { "没成功" })
                        // 都恢复成改之前的样子了，差异就是空的
                        else changes.forEach { diffs[it.id] = "" }
                        reload++
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确定撤销") }
            } else if (confirmReject.isNotEmpty()) {
                TextButton({ confirmReject = "" }) { Text("取消") }
                Button({ val id = confirmReject; confirmReject = ""; review(id, false); picked = id },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确定拒绝") }
            } else {
                // 「全部撤销」只能在电脑上做（PhoneGate 会拒），手机上换成一行小字
                if (access == CodeAccess.FULL && changes.any { it.review != "rejected" }) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50))
                            .clickable { confirmRevert = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("全部撤销", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
                }
                Button(onClick = onClose) { Text("知道了") }
            }
        }) {
            Column(Modifier.fillMaxWidth()) {
                if (confirmRevert) {
                    Warn("撤销以后，这条回答改过的文件都会恢复成改之前的样子，包括已经点过「接受」的。")
                } else if (confirmReject.isNotEmpty()) {
                    Warn("拒绝「${changes.firstOrNull { it.id == confirmReject }?.path.orEmpty()}」会把这个文件恢复成改之前的样子" +
                        "（这个文件后面的改动也会一起撤掉）。")
                }
                if (access == CodeAccess.PHONE) {
                    Text(CodeUi.REVERT_ONLY_ON_PC, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                if (side) {
                    Row(Modifier.fillMaxWidth().height(430.dp)) {
                        Column(Modifier.width(260.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
                            changes.forEach { c ->
                                ChangeFileRow(c, picked == c.id, readonly) { picked = c.id; confirmReject = "" }
                                if (picked == c.id) {
                                    ChangeRowActions(c, readonly, onReject = { confirmReject = c.id }) { review(c.id, true) }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxSize().background(Ext.c.border))
                        Box(Modifier.weight(1f).fillMaxSize().padding(start = 12.dp)) {
                            DiffPane(diffs[picked], diffErr[picked], current)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            changes.forEach { c ->
                                val on = picked == c.id
                                Column(Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (on) Ext.c.sidebarSelected else Color.Transparent)
                                    .clickable { picked = c.id; confirmReject = "" }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                    Text(folderName(c.path), style = MaterialTheme.typography.labelMedium,
                                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text("+${c.added} −${c.removed}" + "  " + reviewLabel(c),
                                        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (current != null && !readonly) ChangeRowActions(current, readonly, onReject = { confirmReject = current.id }) { review(current.id, true) }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(320.dp)) { DiffPane(diffs[picked], diffErr[picked], current) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Warn(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(Ext.c.warning.copy(alpha = 0.14f)).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(15.dp), tint = Ext.c.warning)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChangeFileRow(c: FileChange, selected: Boolean, readonly: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) Ext.c.sidebarSelected else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.path, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("+${c.added} −${c.removed}" + if (c.created) " · 新文件" else "",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
        reviewPill(c)
    }
}

/** 选中文件下面的「接受」「拒绝」（手机上只显示已经处理成什么样）。 */
@Composable
private fun ChangeRowActions(c: FileChange, readonly: Boolean, onReject: () -> Unit, onAccept: () -> Unit) {
    if (readonly) return
    Row(Modifier.padding(start = 8.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (c.review == "rejected") {
            Text("已经恢复成改之前的样子了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        } else {
            if (c.review != "accepted") {
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(Ext.c.success.copy(alpha = 0.14f))
                        .clickable(onClick = onAccept).padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("接受", style = MaterialTheme.typography.labelMedium, color = Ext.c.success) }
            }
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                    .clickable(onClick = onReject).padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("拒绝", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun reviewPill(c: FileChange) {
    when (c.review) {
        "accepted" -> Pill("已接受", Ext.c.success)
        "rejected" -> Pill("已拒绝", MaterialTheme.colorScheme.error)
        else -> Pill("还没处理", Ext.c.subtle)
    }
}

private fun reviewLabel(c: FileChange) = when (c.review) {
    "accepted" -> "已接受"
    "rejected" -> "已拒绝"
    else -> ""
}

@Composable
private fun DiffPane(text: String?, error: String?, c: FileChange?) {
    if (c == null) { Text("没有改动了", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle); return }
    if (error != null) { Warn(error); return }
    if (text == null) {
        Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("正在读差异…", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle) }
        return
    }
    if (text.isBlank()) {
        Text("这个文件现在和改之前一样，没有差异。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(c.path + (if (c.created) "（新文件）" else ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Ext.c.codeBg).padding(8.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                DiffLines(text)
            }
        }
    }
}

/** 统一差异：+ 绿、− 红、@@ 是位置行，可以选中复制。 */
@Composable
private fun DiffLines(text: String) {
    val lines = remember(text) { text.lines() }
    SelectionContainer {
        Column {
            lines.forEach { line ->
                val bg = when {
                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    line.startsWith("+++") || line.startsWith("---") -> Color.Transparent
                    line.startsWith("+") -> Ext.c.success.copy(alpha = 0.16f)
                    line.startsWith("-") -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
                val fg = when {
                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                    line.startsWith("+++") || line.startsWith("---") -> Ext.c.subtle
                    line.startsWith("+") -> Ext.c.success
                    line.startsWith("-") -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = fg,
                    softWrap = false, modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp))
            }
        }
    }
}

/** 等宽显示的一块内容（命令、命令输出、读到的文件、列目录、查找结果），太长的只给开头。 */
@Composable
private fun MonoBox(text: String) {
    val lines = remember(text) { text.lines() }
    val shown = lines.take(STEP_LINES)
    ScrollPane(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column {
                shown.forEach { Text(it.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = false) }
            }
        }
    }
    if (lines.size > shown.size) Text("（下面还有 ${lines.size - shown.size} 行没显示）",
        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
}

/** 等宽 / 差异内容外面那层：横向能滑，长行在手机上也不会被截掉。 */
@Composable
private fun ScrollPane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Ext.c.codeBg)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) { content() }
    }
}

@Composable
private fun SmallCodeAction(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, desc, Modifier.size(14.dp), tint = Ext.c.subtle)
        Spacer(Modifier.width(4.dp))
        Text(desc, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

// ———————————————— 输入框 ————————————————

/** 下面的输入框：发一条任务。上一条还在做的时候照样能发，会排在后面。 */
@Composable
private fun CodeComposer(ctl: AppController, conv: Conversation, running: Boolean) {
    val platform = LocalPlatform.current
    var text by remember(conv.id) { mutableStateOf(TextFieldValue("")) }
    fun send() {
        if (text.text.isBlank()) return
        // 手机上：等批准、做完时要弹通知，趁现在（应用在前台）问一下通知权限
        if (!platform.isDesktop) platform.askNotifyPermission()
        ctl.run(Command.CodeSend(conv.id, text.text))
        text = TextFieldValue("")
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Ext.c.border), shadowElevation = 3.dp) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 26.dp, max = 200.dp)) {
                        if (text.text.isEmpty()) Text(
                            if (platform.isDesktop) "说一句要它做什么…  Enter 发送，Shift+Enter 换行" else "说一句要它做什么…",
                            style = MaterialTheme.typography.bodyLarge, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).onPreviewKeyEvent { e ->
                                if (platform.isDesktop && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                                    send(); true
                                } else false
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            Text("上一条还在做，这条会排在后面", style = MaterialTheme.typography.labelSmall, color = Ext.c.warning)
                        }
                        Spacer(Modifier.weight(1f))
                        val canSend = text.text.isNotBlank()
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable(enabled = canSend) { send() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, "发送", Modifier.size(19.dp),
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else Ext.c.subtle)
                        }
                    }
                }
            }
            Text(
                "所有文件读写都在这个项目文件夹里，改过的都能撤销。" + when {
                    ctl.remoteMode -> "这个会话在电脑上跑。"
                    platform.isDesktop -> "命令在这台电脑上跑。"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            )
        }
    }
}

// ———————————————— Code 栏没开会话时的主区域 ————————————————

/** 侧栏选着「Code」但没打开任何会话：说清楚这一栏是干什么的，给个新建按钮。 */
@Composable
fun CodeEmptyScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val access = CodeUi.access(LocalPlatform.current.isDesktop, ctl.remoteMode, state.settings.code.allowPhone)
    val codeConvs = state.conversations.filter { it.isCode }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!wide) IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
            Text("Code", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
        }
        HorizontalDivider(color = Ext.c.border)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 620.dp).padding(24.dp)) {
                // 这台设备上动不了就只说清楚为什么、去哪儿打开；能动手（电脑上、或者手机遥控电脑且电脑开着开关）才给新建
                if (!access.canAct) {
                    Text(if (access == CodeAccess.LOCKED) CodeUi.LOCKED_TITLE else CodeUi.NO_LOCAL_TITLE,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(if (access == CodeAccess.LOCKED) CodeUi.LOCKED_NOTE else CodeUi.NO_LOCAL_NOTE,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("让 AI 对着一个项目文件夹写代码", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("选一个项目文件夹，它就能读代码、改文件、跑命令；它做的每一步都写在对话里给你看，" +
                        "改文件、跑命令要不要先问你，由权限档说了算，随时能换。每个会话一个文件夹，互不影响。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { ctl.showNewCode = true }, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("新建 Code 会话")
                    }
                }
                // 动不了也一样能打开看（手机上遥控电脑、电脑上没开开关时就是这样）
                if (codeConvs.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("已经有的会话", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                    Spacer(Modifier.height(6.dp))
                    codeConvs.take(6).forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ctl.openConversation(c.id) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(folderName(c.code?.folder.orEmpty()), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                        }
                    }
                }
            }
        }
    }
}

// ———————————————— 新建 Code 会话 ————————————————

/**
 * 新建 Code 会话（docs/Code栏设计说明.md 第 1、10 节）：选项目文件夹、选模型、选权限档。
 * 列表里只列支持调用工具的模型；没实测跑过的标「未实测」（能选，只是没试过）。
 */
@Composable
fun NewCodeDialog(ctl: AppController) {
    val state by ctl.backend.store.state.collectAsState()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val access = CodeUi.access(platform.isDesktop, ctl.remoteMode, state.settings.code.allowPhone)
    // 手机上不能弹系统选择器（选出来的路径是手机上的，电脑上没有）：只能从电脑上已有会话的文件夹里挑
    val pcFolders = remember(state.conversations) { CodePhone.folders(state) }
    var folder by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(CodeMode.MANUAL) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val options = state.providers.filter { it.enabled }.flatMap { p -> p.models.filter { it.tools }.map { p to it } }
    val chosen = options.firstOrNull { "${it.first.id}|${it.second.id}" == picked }

    AppDialog("新建 Code 会话", { ctl.showNewCode = false }, width = 560.dp, actions = {
        TextButton({ ctl.showNewCode = false }) { Text("取消") }
        if (access.canAct) {
            Button(
                enabled = !busy && folder.isNotBlank() && chosen != null,
                onClick = {
                    val (p, m) = chosen ?: return@Button
                    busy = true
                    error = ""
                    if (!platform.isDesktop) platform.askNotifyPermission()
                    ctl.run(Command.CodeCreate(folder, p.id, m.id, mode), quiet = true) { r ->
                        busy = false
                        if (r.ok) { ctl.showNewCode = false; ctl.openConversation(r.data) }
                        else error = r.message.ifBlank { "建不了这个会话" }
                    }
                },
            ) { Text(if (busy) "正在建…" else "建好") }
        }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (!access.canAct) {
                Text(if (access == CodeAccess.LOCKED) CodeUi.LOCKED_TITLE else CodeUi.NO_LOCAL_TITLE, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(if (access == CodeAccess.LOCKED) CodeUi.LOCKED_NOTE else CodeUi.NO_LOCAL_NOTE,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            FieldLabel("项目文件夹", "这个会话只在这个文件夹里读写")
            if (platform.isDesktop) {
                // 电脑上照旧：弹系统的文件夹选择器
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.ifBlank { "还没选" }, style = MaterialTheme.typography.bodyMedium,
                        color = if (folder.isBlank()) Ext.c.subtle else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton({ scope.launch { platform.pickFolder("选择项目文件夹")?.let { folder = it } } }) {
                        Text(if (folder.isBlank()) "选择文件夹" else "换一个")
                    }
                }
            } else if (pcFolders.isEmpty()) {
                Text(NO_PC_FOLDER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                // 手机上不弹系统选择器（手机上选的路径电脑上没有）：只从电脑上已有 Code 会话的文件夹里挑一个
                Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    pcFolders.forEach { f ->
                        val on = f == folder
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (on) Ext.c.sidebarSelected else Color.Transparent)
                                .clickable { folder = f }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(on, { folder = f })
                            Column(Modifier.weight(1f)) {
                                Text(folderName(f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("用哪个模型", "只列支持调用工具的模型")
            if (options.isEmpty()) {
                Text("还没有能用的模型。先到 设置 → 模型服务 加一个支持调用工具的模型。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { (p, m) ->
                        val key = "${p.id}|${m.id}"
                        val on = key == picked
                        val tested = CodeModels.isTested(Thinking.platformOf(p), m.id)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (on) Ext.c.sidebarSelected else Color.Transparent)
                                .clickable { picked = key }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(on, { picked = key })
                            Text("${p.name} · ${m.id}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Pill(if (tested) "已实测" else "未实测", if (tested) Ext.c.success else Ext.c.subtle)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("权限档", "之后随时能在会话上面换")
            CodeUi.createModesFor(access).forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { mode = m }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(m == mode, { mode = m })
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium)
                        Text(modeDesc(m), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                }
            }
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** 手机新建会话时，电脑上一个 Code 会话都没有：没法选文件夹，先说清楚怎么办。 */
private const val NO_PC_FOLDER = "电脑上还没有 Code 会话。先在电脑上建一个，手机上才能选它的文件夹。"

private fun modeDesc(m: CodeMode) = when (m) {
    CodeMode.MANUAL -> "改文件、跑命令之前都先问你（默认）"
    CodeMode.ACCEPT_EDITS -> "改文件不问，跑命令仍然问"
    CodeMode.PLAN -> "只看不改，先给你方案"
}
