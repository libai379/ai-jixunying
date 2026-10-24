package com.guixing.jixunying.ui

import com.kashif_e.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.CodeEntry
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeTimeline
import com.guixing.jixunying.model.CodeModels
import com.guixing.jixunying.model.CodePhone
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.FileChange
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.TeamSeat
import com.guixing.jixunying.model.Thinking
import com.guixing.jixunying.model.ToolStep
import kotlinx.coroutines.launch

/** 一步工具展开以后最多显示多少行（长的读写和命令输出在这里只给个开头，改动看差异查看器）。 */
private const val STEP_LINES = 80

/**
 * Code 栏回答区和输入框最宽多少（2026-10-22 用户：「注意现在的框，两边空太大」——以前写死 860，2560 宽的屏上一半是空的）。
 * 看代码、差异、日志、表格要宽一点；窗口窄的时候照旧撑满。
 */
private val CODE_MAX_WIDTH = 1400.dp

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

    // 2026-10-17：电脑上把文件拖进 Code 会话，放进输入框（和聊天一样；能动手时才收）
    val platform = LocalPlatform.current
    var dragging by remember { mutableStateOf(false) }
    Box(platform.fileDropTarget(Modifier.fillMaxSize(), onHover = { dragging = it && access.canAct }) { files ->
        if (!access.canAct) return@fileDropTarget
        if (files.isEmpty()) ctl.toast("没认出拖进来的是什么（不是文件、图片，也不是路径）")
        else ctl.composerInbox = convId to Incoming(files = files)
    }) {
        // 窗口矮（横屏、键盘弹出、分屏）时和对话栏一样：输入框收紧，特别矮时顶栏先收起来（2026-10-22）
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            val short = h < 420.dp
            Column(Modifier.fillMaxSize()) {
                if (h >= 240.dp) CodeTopBar(ctl, state, conv, spec, wide, openDrawer, running, access)
                if (spec.prUrl.isNotBlank() && platform.isDesktop && !ctl.remoteMode) CiBar(ctl, conv, spec)
                // 和对话页一样（10-24）：开着液态玻璃、没开终端时，输入框浮在步骤列表上，列表一直排到底，输入框的玻璃透出下面的字
                val env = com.guixing.jixunying.ui.glass.LocalGlass.current
                val terminal = ctl.codeTerminalOpen && access.canAct && platform.isDesktop && !ctl.remoteMode
                val floating = env.on && env.backdrop != null && !terminal
                val contentLayer = com.kashif_e.backdrop.backdrops.rememberLayerBackdrop()
                var bottomH by remember { mutableStateOf(0.dp) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                val bottom: @Composable () -> Unit = {
                    BackgroundBar(ctl, conv)
                    if (access.canAct) CodeComposer(ctl, conv, running, short = short, maxText = (h * 0.3f).coerceIn(48.dp, 200.dp)) else CodeBottomNote(access)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CodeMessageList(ctl, state, conv, messages, access.canAct,
                        modifier = if (floating) Modifier.layerBackdrop(contentLayer) else Modifier,
                        bottomPad = if (floating) bottomH else 0.dp) { diffMessage = it }
                    if (floating) {
                        val both = com.kashif_e.backdrop.backdrops.rememberCombinedBackdrop(env.backdrop!!, contentLayer)
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .onSizeChanged { bottomH = with(density) { it.height.toDp() } }) {
                            CompositionLocalProvider(com.guixing.jixunying.ui.glass.LocalGlass provides
                                com.guixing.jixunying.ui.glass.GlassEnv(both, env.on, env.dark, env.tuning, env.paint)) { bottom() }
                        }
                    }
                }
                if (terminal) TerminalPanel(ctl, conv, spec)
                if (!floating) bottom()
            }
        }
        if (dragging) Box(
            Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("松开鼠标，把文件放进输入框", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
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
    // 谁在干活（2026-10-23）：单独一个按钮，点开就能换（以前只写在副标题里，换要进「更多 → 工作区」）
    var showBrain by remember(conv.id) { mutableStateOf(ctl.debugDialog == "brain") }
    val subtitle = if (wide) spec.folder else folderName(spec.folder)
    val brain = (if (spec.isAgent) CodeSpec.agentLabel(spec.providerId, spec.modelId) else (provider?.takeIf { wide }?.let { "${it.name} · " } ?: "") + spec.modelId) +
        (if (spec.isClaudeCode && spec.effort.isNotEmpty()) " · ${spec.effort}" else "") +
        (if (spec.team.isNotEmpty()) " · 带团队" else "")
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    // 文件夹限个宽度，剩下的都给「谁在干活」（两边都 weight 会对半分，短文件夹名旁边的按钮也被截断）
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 420.dp))
                } else {
                    // 手机上放不下整条路径：显示最后一节，前面加个文件夹图标、点一下看全路径
                    Row(
                        Modifier.widthIn(max = 110.dp).clip(RoundedCornerShape(6.dp)).clickable { showPath = true }.padding(vertical = 2.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Row(
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50))
                        .clickable { showBrain = true }.padding(start = 6.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SmartToy, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(brain, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.ExpandMore, "换谁来干活", Modifier.size(14.dp), tint = Ext.c.subtle)
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
        // 2026-10-23 用户：「整个 code 页面逻辑我没看懂」——顶上只留停止、权限档和「更多」；
        // 工作区、终端、提交收进「更多」，另加「这一页怎么用」
        var showGit by remember(conv.id) { mutableStateOf(ctl.debugDialog == "git") }
        var showHelp by remember(conv.id) { mutableStateOf(ctl.debugDialog == "codehelp") }
        val onPc = LocalPlatform.current.isDesktop && !ctl.remoteMode
        CodeMoreMenu(ctl, spec, onPc, onWorkspace = { showPath = true }, onGit = { showGit = true }, onHelp = { showHelp = true })
        Spacer(Modifier.width(8.dp))
        if (showGit) GitDialog(ctl, conv, spec) { showGit = false }
        if (showHelp) CodeHelpDialog { showHelp = false }
        // 2026-10-17「自动」「全部放开」：电脑上才有（手机上打不开，PhoneGate 也会拒）。顺序照 Claude 的菜单：自动、手动、接受编辑、全部放开、计划
        val canOpen = onPc
        val base = CodeUi.modesFor(access, spec).map { ModeOpt(it) }
        val options = buildList {
            if (canOpen || spec.autoMode) add(ModeOpt(CodeMode.ACCEPT_EDITS, auto = true))
            base.filter { it.mode == CodeMode.MANUAL }.forEach { add(it) }
            base.filter { it.mode == CodeMode.ACCEPT_EDITS }.forEach { add(it) }
            if (canOpen || spec.openAll) add(ModeOpt(CodeMode.ACCEPT_EDITS, open = true))
            base.filter { it.mode == CodeMode.PLAN }.forEach { add(it) }
        }
        val current = ModeOpt(spec.mode, open = spec.openAll && spec.mode != CodeMode.PLAN, auto = spec.autoMode && spec.mode != CodeMode.PLAN && !spec.openAll)
        // 开发截图用：-Djxy.start=code:会话编号#modes 把这个菜单打开
        CodeModePicker(current, options, enabled = access.canAct, debugOpen = ctl.debugDialog == "modes") { o ->
            ctl.run(Command.CodeSetMode(conv.id, o.mode, openAll = o.open, auto = o.auto), okText = "权限档换成「${o.label}」")
        }
    }
    HorizontalDivider(color = Ext.c.border)
    if (showPath) WorkspaceDialog(ctl, conv, spec) { showPath = false }
    if (showBrain) BrainDialog(ctl, conv, spec) { showBrain = false }
}

/**
 * PR 的检查状态条（2026-10-18，开发补齐第 9 样；Claude 桌面 App 的 CI status bar）。
 * 每分钟用 gh 查一次；失败了「让 AI 修」（把失败日志交给 AI），「全过了自动合并」。
 */
@Composable
private fun CiBar(ctl: AppController, conv: Conversation, spec: CodeSpec) {
    val platform = LocalPlatform.current
    var state by remember(conv.id) { mutableStateOf("") }
    var checks by remember(conv.id) { mutableStateOf(emptyList<Pair<String, String>>()) }
    var tick by remember(conv.id) { mutableStateOf(0) }
    LaunchedEffect(conv.id, tick) {
        while (true) {
            val r = ctl.backend.call(Command.CodeGit(conv.id, "checks"))
            runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(r.data) as kotlinx.serialization.json.JsonObject
                state = (o["state"] as kotlinx.serialization.json.JsonPrimitive).content
                checks = (o["checks"] as kotlinx.serialization.json.JsonArray).map { a ->
                    val l = a as kotlinx.serialization.json.JsonArray
                    (l[0] as kotlinx.serialization.json.JsonPrimitive).content to (l[1] as kotlinx.serialization.json.JsonPrimitive).content
                }
            }
            kotlinx.coroutines.delay(60_000)
        }
    }
    val failed = checks.count { it.second in setOf("FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED") }
    val passed = checks.count { it.second in setOf("SUCCESS", "NEUTRAL", "SKIPPED") }
    val pending = checks.size - failed - passed
    Row(
        Modifier.fillMaxWidth().background(if (failed > 0) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else Ext.c.sidebarSelected)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PR " + when (state) { "MERGED" -> "已合并"; "CLOSED" -> "已关闭"; "OPEN" -> "开着"; "" -> "…"; else -> state } +
            (if (checks.isEmpty()) "" else " · 检查 ${checks.size} 项：过了 $passed" + (if (failed > 0) "，失败 $failed" else "") + (if (pending > 0) "，在跑 $pending" else "")),
            style = MaterialTheme.typography.labelMedium, color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (failed > 0) TextButton({ ctl.run(Command.CodeGit(conv.id, "ci_fix"), okText = "把失败日志交给 AI 了") }) { Text("让 AI 修") }
        if (state == "OPEN") TextButton({ ctl.run(Command.CodeGit(conv.id, "automerge")) }) { Text("全过了自动合并") }
        TextButton({ tick++ }) { Text("刷新") }
        TextButton({ platform.openUrl(spec.prUrl) }) { Text("打开") }
    }
}

/** 终端里的一行记录：敲的命令（带当时的目录）和输出。 */
private class TermLine(val cwd: String, val command: String, var output: String, var code: String)

/** 每个会话的终端记录（切走再回来还在；程序重开清空）。 */
private val termLogs = mutableMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<TermLine>>()
private val termCwds = mutableMapOf<String, String>()

/**
 * 内置终端（2026-10-18，开发补齐第 2 样；Claude 桌面 App 会话里的 Terminal）：在项目文件夹里自己敲命令，
 * 和 AI 用同一个执行器、同一个外壳。不是真的交互式控制台：每条命令单独跑完拿输出；cd 由程序记住；末尾带 & 放后台。
 */
@Composable
private fun TerminalPanel(ctl: AppController, conv: Conversation, spec: CodeSpec) {
    val log = remember(conv.id) { termLogs.getOrPut(conv.id) { androidx.compose.runtime.mutableStateListOf() } }
    var cwd by remember(conv.id) { mutableStateOf(termCwds[conv.id] ?: spec.folder) }
    var input by remember(conv.id) { mutableStateOf(TextFieldValue("")) }
    var busy by remember(conv.id) { mutableStateOf(false) }
    var histIdx by remember(conv.id) { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    LaunchedEffect(log.size, log.lastOrNull()?.output?.length) { scroll.animateScrollTo(scroll.maxValue) }
    fun run() {
        val cmd = input.text.trim()
        if (cmd.isEmpty() || busy) return
        if (cmd == "clear" || cmd == "cls") { log.clear(); input = TextFieldValue(""); return }
        val line = TermLine(cwd, cmd, "…", "")
        log.add(line)
        input = TextFieldValue("")
        histIdx = -1
        busy = true
        ctl.run(Command.TermRun(conv.id, cmd, cwd), quiet = true) { r ->
            busy = false
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(r.data) as kotlinx.serialization.json.JsonObject }.getOrNull()
            val out = o?.get("output")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: r.message
            val idx = log.indexOf(line)
            if (idx >= 0) log[idx] = TermLine(line.cwd, line.command, out, o?.get("code")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty())
            o?.get("cwd")?.let { cwd = (it as kotlinx.serialization.json.JsonPrimitive).content; termCwds[conv.id] = cwd }
        }
    }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16181D)).padding(10.dp),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
            if (log.isEmpty()) Text("自己敲命令：在项目文件夹里跑 PowerShell，和 AI 无关（cd 换目录，末尾加 & 放后台，clear 清屏）。", style = mono, color = Color(0xFF8A90A0))
            log.forEach { l ->
                SelectionContainer {
                    Column {
                        Text("${folderName(l.cwd)}> ${l.command}", style = mono, color = Color(0xFF7DD3FC))
                        if (l.output.isNotBlank()) Text(l.output.trimEnd().takeLast(20_000), style = mono,
                            color = if (l.code.isNotEmpty() && l.code != "0") Color(0xFFFCA5A5) else Color(0xFFE5E7EB))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("${folderName(cwd)}> ", style = mono, color = Color(0xFF7DD3FC))
            BasicTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                textStyle = mono.copy(color = Color(0xFFE5E7EB)), cursorBrush = SolidColor(Color(0xFFE5E7EB)),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val cmds = log.map { it.command }
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { run(); true }
                        Key.DirectionUp -> {
                            if (cmds.isNotEmpty()) {
                                histIdx = if (histIdx < 0) cmds.lastIndex else (histIdx - 1).coerceAtLeast(0)
                                input = TextFieldValue(cmds[histIdx], TextRange(cmds[histIdx].length))
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (histIdx >= 0) {
                                histIdx++
                                input = if (histIdx > cmds.lastIndex) { histIdx = -1; TextFieldValue("") } else TextFieldValue(cmds[histIdx], TextRange(cmds[histIdx].length))
                            }
                            true
                        }
                        else -> false
                    }
                },
            )
            if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color(0xFF7DD3FC))
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * 提交和上传（2026-10-18，用户：「能不能连接 github，开发完帮忙上传」；engine/code/GitHub）。
 * 是仓库、有远端：提交并推送 / 推送并开 PR / 只提交。没有远端：先给「只在本地提交」，传 GitHub 是下面的次要选项。
 * 不是仓库：「在本地建仓库并提交」，或者在 GitHub 上建仓库并上传（默认私有）。
 * 2026-10-22 试用记账本时改的：以前没远端只能去 GitHub 建仓库；默认仓库名是独立副本的编号（5a5f0291）；
 * 默认提交说明是被截断的会话标题。现在说明默认空着，另给「让 AI 写说明并提交」（它看改动写，试用时写得很好）。
 * 结果原样显示在下面（出错能看到 git / gh 说了什么）。
 */
@Composable
private fun GitDialog(ctl: AppController, conv: Conversation, spec: CodeSpec, onClose: () -> Unit) {
    var info by remember { mutableStateOf<Map<String, String>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    // GitHub 仓库名只能用英文、数字、- _ .：独立副本用主仓库的文件夹名，中文名过滤完是空的就让用户自己填
    var name by remember { mutableStateOf(folderName(spec.worktreeOf.ifBlank { spec.folder }).replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.')) }
    var private by remember { mutableStateOf(true) }
    LaunchedEffect(reload) {
        val r = ctl.backend.call(Command.CodeGit(conv.id, "repo"))
        info = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(r.data).let { e ->
            (e as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content } } }.getOrElse { mapOf("repo" to "false") }
    }
    fun go(op: String) {
        busy = true
        output = "正在做…"
        ctl.run(Command.CodeGit(conv.id, op, message.ifBlank { "更新" }, name, private), okText = null, quiet = true) { r ->
            busy = false
            output = (if (r.ok) "✓ " else "✗ ") + r.message + if (r.data.isNotBlank()) "\n\n" + r.data else ""
            reload++
        }
    }
    /** 交给这个会话的 AI：它看改动写说明、只在本地提交（和自己敲 git 一样会显示在对话里）。 */
    fun aiCommit() {
        ctl.run(Command.CodeSend(conv.id, "把现在没提交的改动提交到当前分支：只在本地提交，不推送、不建远端仓库；提交说明按改动内容写（中文，第一行一句话概括）。"),
            okText = null, quiet = true) { onClose() }
    }
    val i = info
    val isRepo = i?.get("repo") == "true"
    val remote = i?.get("remote").orEmpty()
    AppDialog(if (isRepo && remote.isNotBlank()) "提交和上传" else "提交", onClose, width = 600.dp, actions = { Button(onClose) { Text("好了") } }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when {
                i == null -> Text("正在看项目的 Git 状态…", style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle)
                isRepo -> {
                    Text("分支：${i["branch"].orEmpty().ifBlank { "（还没有）" }}" + (if (remote.isNotBlank()) "　远端：$remote" else "　还没连到 GitHub"),
                        style = MaterialTheme.typography.bodyMedium)
                    val changes = i["changes"].orEmpty().lines().filter { it.isNotBlank() }
                    Text(if (changes.isEmpty()) "没有没提交的改动" + (i["ahead"].orEmpty().takeIf { it.isNotBlank() && it != "0" }?.let { "（有 $it 个提交还没推上去）" } ?: "")
                        else "有 ${changes.size} 个文件改过、还没提交：", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
                    if (changes.isNotEmpty()) MonoBox(changes.take(30).joinToString("\n") + if (changes.size > 30) "\n…还有 ${changes.size - 30} 个" else "")
                }
                else -> Text("这个文件夹还不是 Git 仓库：可以只在本地建一个仓库把现在的文件提交进去，也可以在 GitHub 上建仓库传上去。",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            FieldLabel("这次提交写什么", "空着就写「更新」；不想自己写就让 AI 写")
            AppTextField(message, { message = it }, placeholder = "比如：登录页加上记住密码")
            Spacer(Modifier.height(10.dp))
            if (i != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !isRepo -> Button({ go("init") }, enabled = !busy) { Text("在本地建仓库并提交") }
                    remote.isBlank() -> Button({ go("commit") }, enabled = !busy) { Text("只在本地提交") }
                    else -> {
                        Button({ go("commit_push") }, enabled = !busy) { Text("提交并推送") }
                        OutlinedButton({ go("pr") }, enabled = !busy) { Text("推送并开 PR") }
                        TextButton({ go("commit") }, enabled = !busy) { Text("只提交") }
                    }
                }
                if (isRepo) OutlinedButton({ aiCommit() }, enabled = !busy) { Text("让 AI 写说明并提交") }
            }
            if (i != null && (!isRepo || remote.isBlank())) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Ext.c.border)
                Spacer(Modifier.height(10.dp))
                Text("要传到 GitHub（会把代码传上去，默认私有）：", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                FieldLabel("GitHub 上的仓库名", "只能用英文、数字、- _ .")
                AppTextField(name, { name = it }, placeholder = "比如：my-app")
                Row(Modifier.clickable { private = !private }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(private, { private = it })
                    Text("私有仓库（只有你能看）", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton({ go("create") }, enabled = !busy && name.isNotBlank()) { Text("在 GitHub 上建仓库并上传") }
            }
            if (output.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                MonoBox(output.take(4000))
            }
            Spacer(Modifier.height(6.dp))
            Text("要先在 设置 → Code 栏 → GitHub 里登录。推不上去时看上面 git 说了什么，也可以直接让 AI 帮你查。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
    }
}

/**
 * 工作区（2026-10-17，照 Claude Code 的 /add-dir 和「不再问」）：
 * 项目文件夹 + 加进来的文件夹（AI 读不用问，改按权限档），以及这个会话里「同样开头不再问」的命令。
 * 只能在电脑上改（手机上只看，PhoneGate 也会拒）。
 */
@Composable
private fun WorkspaceDialog(ctl: AppController, conv: Conversation, spec: CodeSpec, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val canEdit = platform.isDesktop && !ctl.remoteMode
    fun save(paths: List<String>, cmds: List<String>) = ctl.run(Command.CodeSetAccess(conv.id, paths, cmds), quiet = true) { r ->
        if (!r.ok) ctl.toast(r.message.ifBlank { "没改成" })
    }
    AppDialog("工作区", onClose, width = 560.dp, actions = {
        TextButton({ clipboard.setText(AnnotatedString(spec.folder)); ctl.toast("已复制路径") }) { Text("复制项目路径") }
        Button(onClose) { Text("好了") }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("电脑上的文件 AI 都能读，不用问。工作区里的改动看权限档；工作区外面的（比如 G 盘别的地方）改之前会先问你，" +
                "选「全部放开」就都不问；批准时可以顺便把那个文件夹加进来。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(10.dp))
            if (spec.worktreeOf.isNotBlank()) {
                Text("这是独立副本：从 ${spec.worktreeOf} 复制出来，在分支 ${spec.worktreeBranch} 上改，不影响原来的文件夹。删掉这个会话会清掉副本（分支留着）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            FieldLabel("项目文件夹", "命令在这里跑")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { Text(spec.folder, style = MaterialTheme.typography.bodyMedium) }
                if (canEdit) TextButton({
                    scope.launch {
                        val f = platform.pickFolder("换一个项目文件夹") ?: return@launch
                        ctl.run(Command.CodeSetProject(conv.id, folder = f), okText = "项目文件夹换成 ${folderName(f)}")
                    }
                }) { Text("换一个") }
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("加进来的文件夹", if (spec.extraPaths.isEmpty()) "还没有" else null)
            spec.extraPaths.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(15.dp), tint = Ext.c.subtle)
                    Spacer(Modifier.width(6.dp))
                    Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (canEdit) TextButton({ save(spec.extraPaths - p, spec.allowedCommands) }) { Text("移出", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (canEdit) TextButton({
                scope.launch {
                    val f = platform.pickFolder("把一个文件夹加进工作区") ?: return@launch
                    if (f.trimEnd('\\', '/').equals(spec.folder.trimEnd('\\', '/'), ignoreCase = true) ||
                        spec.extraPaths.any { it.equals(f, ignoreCase = true) }) { ctl.toast("已经在工作区里了"); return@launch }
                    save(spec.extraPaths + f, spec.allowedCommands)
                }
            }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("加一个文件夹")
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("不再问的命令和网站", if (spec.allowedCommands.isEmpty()) "还没有；批准命令时选「…开头的不再问」就会记在这里" else "以这些开头的命令直接跑；拼起来的和危险的命令照样每次都问")
            spec.allowedCommands.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (c.startsWith("web:")) "网站 " + c.removePrefix("web:") else c,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                    if (canEdit) TextButton({ save(spec.extraPaths, spec.allowedCommands - c) }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (canEdit) {
                Spacer(Modifier.height(12.dp))
                FieldLabel("独立副本", "几个会话同时改同一个项目时，各用一份互不干扰（要是 Git 仓库）")
                TextButton({
                    // 照原会话的档开（2026-10-22：以前写死「接受编辑」，「自动」丢了）；「全部放开」不跟过去
                    ctl.run(Command.CodeCreate(spec.worktreeOf.ifBlank { spec.folder }, spec.providerId, spec.modelId,
                        if (spec.mode == CodeMode.MANUAL || spec.mode == CodeMode.PLAN) spec.mode else CodeMode.ACCEPT_EDITS,
                        worktree = true, auto = spec.autoMode), quiet = true) { r ->
                        if (r.ok) { onClose(); ctl.openConversation(r.data) } else ctl.toast(r.message.ifBlank { "开不了独立副本" })
                    }
                }) { Text("用独立副本开一个新会话") }
            }
            if (!canEdit) {
                Spacer(Modifier.height(8.dp))
                Text("工作区和「不再问」只能在电脑上改。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
    }
}

/**
 * 谁来干活（2026-10-23 用户：「code 界面是不是我选终端自己链接 Claudecode」——选模型藏在「更多 → 工作区」里找不到，
 * 「终端」又像是接 Claude Code 的）：顶栏标题下面那个按钮点开就是这里——本机的编程命令行、自己加的模型、思考强度、团队。
 * 只能在电脑上改（手机上只看，PhoneGate 也会拒）。
 */
@Composable
private fun BrainDialog(ctl: AppController, conv: Conversation, spec: CodeSpec, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    val canEdit = platform.isDesktop && !ctl.remoteMode
    AppDialog("谁来干活", onClose, width = 560.dp, actions = { Button(onClose) { Text("好了") } }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("选 Claude Code、Codex、Gemini CLI，就是把任务交给你电脑上装的那个程序，用你自己的订阅或账号；" +
                "选「自己加的模型」，就是 AI集训营 自己的助手调那个模型（用 模型服务 里的 Key）。只列能调用工具的模型。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(8.dp))
            // 本机的编程命令行：Claude Code（2026-10-18，走你的 Claude 订阅）、Codex / Gemini CLI（2026-10-20，各用你自己的账号）
            Text("本机的编程命令行（用你自己的账号；先在 设置 → Code 栏 检查装好、登录了）", style = MaterialTheme.typography.labelMedium,
                color = Ext.c.subtle, modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
            CodeSpec.AGENTS.forEach { (agent, _) ->
                CodeSpec.agentModels(agent).forEach { (id, _) ->
                    val on = spec.providerId == agent && spec.modelId == id
                    val label = CodeSpec.agentLabel(agent, id)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Ext.c.sidebarSelected else Color.Transparent)
                            .clickable(enabled = canEdit && !on) { ctl.run(Command.CodeSetProject(conv.id, providerId = agent, modelId = id), okText = "换成 $label") }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(on, null, enabled = canEdit)
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // 思考强度（2026-10-22）：照官方 --effort 的档，外加 ultracode；空 = 它自己的默认
            if (spec.isClaudeCode) {
                var effortOpen by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("思考强度", style = MaterialTheme.typography.bodyMedium)
                        Text("越往下想得越深、越慢、越费额度；团队里的 Claude Code 也用这一档", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    }
                    Box {
                        TextButton({ effortOpen = true }, enabled = canEdit) { Text(CodeSpec.effortLabel(spec.effort), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        androidx.compose.material3.DropdownMenu(effortOpen, { effortOpen = false }) {
                            CodeSpec.EFFORTS.forEach { (id, label) ->
                                androidx.compose.material3.DropdownMenuItem({ Text(label) }, {
                                    effortOpen = false
                                    ctl.run(Command.CodeSetEffort(conv.id, id), okText = "思考强度：$label")
                                })
                            }
                        }
                    }
                }
            }
            if (spec.isCliAgent) Text("${CodeSpec.agentName(spec.providerId)} 不能一步步问你：「手动」档交出去之前问一次，「计划」档只读；" +
                "做完拿交出去之前的快照比对，改了的文件照样能看差异、撤销。手机发来的任务不交给它。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            Text("自己加的模型", style = MaterialTheme.typography.labelMedium,
                color = Ext.c.subtle, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp))
            val st by ctl.backend.store.state.collectAsState()
            val options = st.providers.filter { it.enabled }.flatMap { p -> p.models.filter { it.tools }.map { p to it } }
            options.forEach { (p, m) ->
                val on = p.id == spec.providerId && m.id == spec.modelId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Ext.c.sidebarSelected else Color.Transparent)
                        .clickable(enabled = canEdit && !on) { ctl.run(Command.CodeSetProject(conv.id, providerId = p.id, modelId = m.id), okText = "模型换成 ${m.id}") }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(on, null, enabled = canEdit)
                    Text("${p.name} · ${m.id}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            TeamSection(ctl, conv, spec, canEdit, options)
            if (!canEdit) {
                Spacer(Modifier.height(8.dp))
                Text("谁来干活只能在电脑上改。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
    }
}

/**
 * 团队（2026-10-19，多角色派活，设计说明第 16 节）：上面选的模型当主控，开发 / 审查 / 测试各选一个脑子（或者不用）。
 * 2026-10-22 起主控是 Claude Code 也能组队：它是总指挥，派活工具经本机 MCP 给它（第 18 节）；Codex / Gemini 当主控还不能组队。
 */
@Composable
private fun TeamSection(ctl: AppController, conv: Conversation, spec: CodeSpec, canEdit: Boolean,
                        options: List<Pair<ProviderConfig, ModelInfo>>) {
    FieldLabel("团队", when {
        spec.team.isEmpty() -> "不组队就是一个 AI 从头做到尾"
        spec.isClaudeCode -> "Claude Code 当总指挥，拆好活派给下面的角色（只在电脑上发的任务里）"
        else -> "上面选的模型当主控，拆好活派给下面的角色"
    })
    if (spec.isAgent && !spec.isClaudeCode) {
        Text("主控是 ${CodeSpec.agentName(spec.providerId)} 时不能派活给团队（它用的是自己的工具）。要组队，主控换成 Claude Code 或者自己加的模型。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        return
    }
    TeamSeat.ROLES.forEach { (role, label, what) ->
        val seat = spec.seat(role)
        var open by remember { mutableStateOf(false) }
        fun pick(providerId: String, modelId: String, ok: String) {
            open = false
            ctl.run(Command.CodeSetTeam(conv.id, role, providerId, modelId), okText = ok)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(what, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                val current = when {
                    seat == null -> "不用"
                    seat.isAgent -> CodeSpec.agentLabel(seat.providerId, seat.modelId)
                    else -> options.firstOrNull { it.first.id == seat.providerId && it.second.id == seat.modelId }
                        ?.let { "${it.first.name} · ${it.second.id}" } ?: "${seat.modelId}（服务商不在了）"
                }
                TextButton({ open = true }, enabled = canEdit) { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                androidx.compose.material3.DropdownMenu(open, { open = false }) {
                    androidx.compose.material3.DropdownMenuItem({ Text("不用") }, { pick("", "", "$label：不用了") })
                    CodeSpec.AGENTS.forEach { (agent, _) ->
                        CodeSpec.agentModels(agent).forEach { (id, _) ->
                            val l = CodeSpec.agentLabel(agent, id)
                            androidx.compose.material3.DropdownMenuItem({ Text(l) }, { pick(agent, id, "$label 交给 $l") })
                        }
                    }
                    options.forEach { (p, m) ->
                        androidx.compose.material3.DropdownMenuItem({ Text("${p.name} · ${m.id}") }, { pick(p.id, m.id, "$label 用 ${m.id}") })
                    }
                }
            }
        }
    }
    // 一条龙（2026-10-22，设计说明第 19 节）：程序按「开发 → 审查 / 测试 → 没过就改 → 提交」转
    val ready = spec.seat("dev") != null && (spec.seat("review") != null || spec.seat("test") != null)
    val on = spec.flow == CodeSpec.FLOW_DEV
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("一条龙", style = MaterialTheme.typography.bodyMedium)
            Text(when {
                !ready -> "先选好开发，再选审查或测试，就能让程序自己转：开发 → 审查 / 测试 → 没过就改（最多 ${CodeSpec.FLOW_ROUNDS} 轮）→ 提交"
                on -> "开着：每条任务由程序转，上面的模型不参与；都过了在独立副本里本地提交"
                else -> "开发 → 审查 / 测试 → 没过就改（最多 ${CodeSpec.FLOW_ROUNDS} 轮）→ 提交，程序自己转"
            }, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        AppSwitch(on && ready, { v ->
            ctl.run(Command.CodeSetFlow(conv.id, if (v) CodeSpec.FLOW_DEV else ""), okText = if (v) "一条龙开了" else "一条龙关了")
        }, enabled = canEdit && ready)
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
/** 权限档的一个选项：「自动」「全部放开」是「接受编辑 + 开关」（不做成 CodeMode 新值，旧手机读不坏）。 */
private data class ModeOpt(val mode: CodeMode, val open: Boolean = false, val auto: Boolean = false) {
    val label: String get() = if (auto) "自动" else if (open) "全部放开" else mode.label
}

@Composable
private fun CodeModePicker(mode: ModeOpt, modes: List<ModeOpt>, enabled: Boolean, debugOpen: Boolean = false, onPick: (ModeOpt) -> Unit) {
    // 2026-10-23：以前五档一字排开，看不出各是什么意思；改成一个按钮显示现在这一档，点开每档带一句说明
    var open by remember { mutableStateOf(debugOpen) }
    val color = if (mode.open) Ext.c.warning else MaterialTheme.colorScheme.primary
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50))
                .background(color.copy(alpha = 0.10f))
                .clickable(enabled = enabled) { open = true }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Security, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(mode.label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
            if (enabled) Icon(Icons.Rounded.ExpandMore, "权限档", Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            Text("权限档：AI 改文件、跑命令前要不要先问你", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            modes.forEach { m ->
                androidx.compose.material3.DropdownMenuItem(
                    {
                        Column(Modifier.widthIn(max = 320.dp)) {
                            Text(m.label, style = MaterialTheme.typography.bodyMedium, color = if (m.open) Ext.c.warning else MaterialTheme.colorScheme.onSurface)
                            Text(modeOptDesc(m), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                        }
                    },
                    trailingIcon = { if (m == mode) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) },
                    onClick = { open = false; if (m != mode) onPick(m) },
                )
            }
        }
    }
}

private fun modeOptDesc(m: ModeOpt) = when {
    m.auto -> "读、改项目里的文件直接做；别的先让检查员（裁判模型）看一眼，它拦下的再问你"
    m.open -> "改文件、跑命令都不问；删整个文件夹、格式化这类危险命令照样问"
    else -> modeDesc(m.mode)
}

/** 顶上的「更多」：工作区、终端、提交（电脑上才有后两样）、这一页怎么用。 */
@Composable
private fun CodeMoreMenu(ctl: AppController, spec: CodeSpec, onPc: Boolean, onWorkspace: () -> Unit, onGit: () -> Unit, onHelp: () -> Unit) {
    var open by remember { mutableStateOf(ctl.debugDialog == "more") }
    val extra = spec.extraPaths.size + spec.allowedCommands.size
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreHoriz, "更多") }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            @Composable
            fun item(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) = androidx.compose.material3.DropdownMenuItem(
                {
                    Column(Modifier.widthIn(max = 320.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                },
                leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                onClick = { open = false; onClick() },
            )
            item(Icons.Rounded.FolderOpen, if (extra > 0) "工作区（另加了 $extra 项）" else "工作区",
                "项目文件夹、另外放进来的文件夹、「不再问」的命令", onWorkspace)
            if (onPc) {
                // 终端（2026-10-18，开发补齐第 2 样）：开关在会话之间共用
                // 2026-10-23 改叫「自己敲命令」：叫「终端」时用户以为是接 Claude Code 的
                item(Icons.Rounded.Terminal, if (ctl.codeTerminalOpen) "收起「自己敲命令」" else "自己敲命令",
                    "你自己在项目文件夹里敲命令（PowerShell），和 AI 无关；换谁来干活点标题下面那个按钮", { ctl.codeTerminalOpen = !ctl.codeTerminalOpen })
                // 2026-10-22 叫「提交」：没远端时主要是在本地提交，有远端弹窗里照样能推送、开 PR
                item(Icons.Rounded.CloudUpload, "提交", "把改动存成一个版本（git 提交），有 GitHub 的话还能推上去", onGit)
            }
            item(Icons.Rounded.Info, "这一页怎么用", "谁在干活、权限档、改动怎么撤销", onHelp)
        }
    }
}

/** 「这一页怎么用」（2026-10-23 用户：「整个 code 页面逻辑我没看懂」）。 */
@Composable
private fun CodeHelpDialog(onClose: () -> Unit) {
    AppDialog("Code 这一页怎么用", onClose, width = 560.dp, actions = { Button(onClose) { Text("知道了") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            @Composable
            fun para(title: String, body: String) = Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            para("一个会话 = 一个项目文件夹", "左边每一行是一个会话，建的时候选好文件夹和谁来干活。之后在下面说一句要做什么，它就在这个文件夹里读代码、改文件、跑命令。")
            para("谁在干活", "顶上标题下面那个带小机器人的按钮，点开就能换。选「Claude Code」「Codex」「Gemini CLI」就是直接用你电脑上装的那个程序（用你自己的订阅或账号）；" +
                "选自己加的模型就是 AI集训营 自己带的助手调那个模型。团队（开发 / 审查 / 测试各派给谁）也在那里。")
            para("中间一行一行是什么", "AI 说的话和它做的每一步（读了哪个文件、改了哪个文件、跑了什么命令）按先后排。点某一步能展开看内容；要你批准的那一步会自己展开，带「允许」「拒绝」。")
            para("权限档（顶上那个盾牌按钮）", "决定它改文件、跑命令前要不要先问你。不放心就用「手动」，什么都先问；只想让它出方案就用「计划」。")
            para("改错了怎么办", "回答下面的「改了 N 个文件」点进去，能一个文件一个文件地拒绝，文件马上恢复成改之前的样子。你发过的任务下面有「回到这里」，那之后的改动全部还原。")
            para("「更多」里的东西", "工作区：除了项目文件夹，还允许它碰哪些地方。自己敲命令：你自己用的 PowerShell 小窗口，和 AI 无关，不是接 Claude Code 的。提交：改满意了，存成一个 git 版本。")
        }
    }
}

@Composable
private fun CodeMessageList(ctl: AppController, state: AppState, conv: Conversation, messages: List<Message>, canAct: Boolean,
                            modifier: Modifier = Modifier, bottomPad: androidx.compose.ui.unit.Dp = 0.dp, onDiff: (String) -> Unit) {
    // 和聊天一样从底下往上排：打开会话停在最新一行的最后（教训 60），每个会话各记各的位置
    val listState = remember(conv.id) { LazyListState() }
    val last = messages.lastOrNull()
    // 只在刚发出任务时拽到最底下（2026-10-23）：以前停在最新那条里也算「在底下」，任务在跑时每来一步就拽一次，
    // 往上翻着点某一步根本点不准。现在一步一行，停在最底下时新的一行自己顶上来；往上翻着时列表按行认位置，不动
    LaunchedEffect(messages.size) {
        if (last?.role == Role.USER) listState.animateScrollToItem(0)
    }
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(bottom = bottomPad)) { CodeStartHint(conv) }
        return
    }
    val rows = codeRows(ctl, state, messages, canAct, onDiff).asReversed()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        reverseLayout = true,
        // 输入框浮在上面时底部留出它的高度：用内边距不用一行空白（空白那行跟着输入框变高会把整个列表推走，10-24 codeListHoldsWhileRunning）
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPad),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        items(rows, key = { it.key }) { r ->
            Box(Modifier.widthIn(max = CODE_MAX_WIDTH).fillMaxWidth().padding(horizontal = 16.dp)) { r.content() }
        }
        item(key = "top") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                Text("项目文件夹：${conv.code?.folder.orEmpty()}（电脑上的文件都能读；改工作区外面的会先问你）",
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
        if (m.attachments.isNotEmpty()) {
            AttachmentStrip(ctl, m.attachments, alignEnd = true)
            Spacer(Modifier.height(6.dp))
        }
        if (m.content.isNotBlank()) Surface(shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp), color = Ext.c.userBubble, modifier = Modifier.widthIn(max = 620.dp)) {
            SelectionContainer { MarkdownText(m.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = Ext.c.onUserBubble) }
        }
        var confirm by remember(m.id) { mutableStateOf(false) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallCodeAction(Icons.Rounded.ContentCopy, "复制") { clipboard.setText(AnnotatedString(m.content)); ctl.toast("已复制") }
            // 回到这里（2026-10-18，开发补齐第 8 样）：电脑上才有
            if (LocalPlatform.current.isDesktop && !ctl.remoteMode) SmallCodeAction(Icons.Rounded.History, "回到这里：这条任务之后改过的文件全部还原，后面的对话删掉，任务放回输入框") { confirm = true }
        }
        if (confirm) AppDialog("回到这条任务之前？", { confirm = false }, width = 460.dp, actions = {
            TextButton({ confirm = false }) { Text("取消") }
            Button({
                confirm = false
                ctl.run(Command.CodeRewind(m.convId, m.id), quiet = true) { r ->
                    ctl.toast(r.message.ifBlank { if (r.ok) "回去了" else "没成功" })
                    if (r.ok) ctl.composerDraft = m.convId to r.data
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("回到这里") }
        }) {
            Text("从这条任务开始，AI 改过的文件都会恢复成改之前的样子，之后的对话全部删掉；这条任务的文字放回输入框，你可以改一改再发。",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 列表里的一行。一条 AI 回答拆成好几行（2026-10-23）：任务在跑时新的一步是新的一行，往上翻着看的那一行不会被挤走。 */
private class CodeRow(val key: String, val content: @Composable () -> Unit)

/** 头像和名字之后，回答的内容往右缩进到名字底下。 */
private val CODE_INDENT = 46.dp

private fun codeRows(ctl: AppController, state: AppState, messages: List<Message>, canAct: Boolean, onDiff: (String) -> Unit): List<CodeRow> {
    val rows = mutableListOf<CodeRow>()
    val used = HashSet<String>()
    fun add(key: String, indent: Boolean = true, content: @Composable () -> Unit) {
        // 编号万一重了（旧数据里步骤没编号），LazyColumn 会崩：加个序号
        var k = key
        var n = 1
        while (!used.add(k)) k = "$key#${n++}"
        rows += CodeRow(k) { Box(Modifier.fillMaxWidth().padding(start = if (indent) CODE_INDENT else 0.dp)) { content() } }
    }
    for (m in messages) {
        if (m.role == Role.USER) {
            add(m.id, indent = false) { Box(Modifier.padding(vertical = 8.dp)) { CodeUserMessage(ctl, m) } }
            continue
        }
        val (body, thinkInline) = splitThink(m.content)
        val reasoning = (m.reasoning + if (thinkInline.isNotEmpty()) "\n$thinkInline" else "").trim()
        val entries = CodeTimeline.build(m.content, m.tools)
        val streaming = m.status == MsgStatus.STREAMING
        add("${m.id}:head", indent = false) { CodeAiHead(m) }
        // 待办清单放最上面（2026-10-18）
        m.tools.lastOrNull { it.kind == "todo" }?.let { t -> add("${m.id}:todo") { TodoCard(t.output) } }
        // 一步工具一行，点开看输入和输出；边做边说的话按时间插在步骤之间
        entries.forEach { e ->
            when (e) {
                is CodeEntry.Step -> add("${m.id}:s:${e.step.id}") { CodeToolStep(ctl, m, e.step, canAct) }
                is CodeEntry.Said -> {
                    val text = splitThink(e.text).first
                    if (text.isNotBlank()) add("${m.id}:t:${e.key}") { SelectionContainer { MarkdownText(text, Modifier.padding(bottom = 6.dp)) } }
                }
            }
        }
        if (reasoning.isNotEmpty()) add("${m.id}:reason") { CodeReasoning(reasoning, streaming = streaming && body.isEmpty()) }
        // 还在跑、又没在等批准：最下面一直有一行在跳（2026-10-22：以前有了步骤就不显示，两步之间想一分多钟，看不出是在想还是卡住了）
        if (streaming && m.tools.none { it.approval == "waiting" })
            add("${m.id}:busy") { CodeThinking(m.createdAt, started = m.tools.isNotEmpty() || body.isNotEmpty() || reasoning.isNotEmpty()) }
        add("${m.id}:foot") { CodeAiFoot(ctl, m, body, onDiff) }
    }
    return rows
}

@Composable
private fun CodeAiHead(m: Message) {
    Row(Modifier.padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(null, 34.dp, emoji = "🛠️", color = 0xFF4F5BEB)
        Spacer(Modifier.width(12.dp))
        Text("Code 助手", style = MaterialTheme.typography.titleSmall)
        if (m.modelLabel.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Pill(m.modelLabel, Ext.c.subtle)
        }
    }
}

/** 一条回答的收尾：改动、出错、停止、复制。 */
@Composable
private fun CodeAiFoot(ctl: AppController, m: Message, body: String, onDiff: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val changes = remember(m.tools) { m.tools.mapNotNull { it.change } }
    Column(Modifier.padding(bottom = 8.dp)) {
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

/** 转圈 + 用了多久（每秒跳一下）：还没动手写「正在想…」，做了几步以后写「还在做…」。 */
@Composable
private fun CodeThinking(since: Long, started: Boolean) {
    var now by remember { mutableStateOf(nowMillis()) }
    LaunchedEffect(since) { while (true) { now = nowMillis(); kotlinx.coroutines.delay(1_000) } }
    val sec = ((now - since) / 1000).coerceAtLeast(0)
    val took = if (sec < 60) "$sec 秒" else "${sec / 60} 分 ${(sec % 60).toString().padStart(2, '0')} 秒"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text((if (started) "还在做…" else "正在想…") + "（已经 $took）", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    }
}

/** 一步工具：一行摘要，点开看输入和输出；等批准的那一步默认展开，带「允许」「拒绝」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeToolStep(ctl: AppController, msg: Message, t: ToolStep, canAct: Boolean) {
    // 这台设备上动不了（手机本机、或者电脑上没开「允许手机用 Code 栏」）时只能看
    val readonly = !canAct
    val waiting = t.approval == "waiting"
    // AI 看过的图默认展开（它会说「上面这几张截图」，用户得一眼看到）
    var open by remember(t.id) { mutableStateOf(t.image.isNotBlank()) }
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
                    // 工作区外面的读 / 列目录 / 查找：等批准时写清是哪个路径
                    waiting && (t.kind == "read" || t.kind == "list" || t.kind == "grep" || t.kind == "find") -> {
                        Text(if (readonly) "读工作区外面的东西，在等你批准：" else "${t.note.ifBlank { "读工作区外面的东西" }}：",
                            style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
                        MonoBox(t.input)
                    }
                    // 要打开的网页：等批准时写清网址（2026-10-17）
                    waiting && t.kind == "fetch" -> {
                        Text(if (readonly) "打开网页，在等你批准：" else "要打开这个网页：", style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
                        MonoBox(t.input)
                    }
                    // 要跑的命令：等批准的时候也要看得见
                    t.kind == "run" -> {
                        if (waiting) Text(if (readonly) "这条命令在等你批准：" else "要跑这条命令：", style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
                        MonoBox(t.input, wrap = true)
                        if (!waiting && t.output.isNotBlank()) MonoBox(t.output)
                    }
                    // 改文件：看差异
                    t.kind == "write" || t.kind == "edit" -> {
                        if (waiting) Text(if (readonly) "这处改动在等你批准：" else "要改这个文件：", style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
                        if (t.output.isNotBlank()) ScrollPane { DiffLines(t.output) }
                    }
                    // AI 看过的图（2026-10-22）：缩略图，点开看大图
                    t.image.isNotBlank() -> StepImage(ctl, t)
                    // 读文件 / 列目录 / 查找：看结果
                    else -> if (t.output.isNotBlank()) MonoBox(t.output)
                }
                when {
                    waiting -> if (readonly) {
                        Text("要批准就打开电脑上的这个会话。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    } else {
                        if ("自动检查" in t.note) Text(t.note.substringAfter("（").removeSuffix("）").ifBlank { t.note },
                            style = MaterialTheme.typography.bodySmall, color = Ext.c.warning)
                        // 「顺便放宽」的两个选项只在电脑上给（手机上点了也会被当成只允许这一次，见 PhoneGate）
                        val canWiden = LocalPlatform.current.isDesktop && !ctl.remoteMode
                        if (t.kind == "write" || t.kind == "edit") if (t.scopeDir.isNotBlank()) Text("这个文件在工作区外面。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { ctl.run(Command.CodeDecide(msg.convId, t.id, true)) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            ) { Text(if (canWiden && (t.scopeDir.isNotBlank() || t.prefix.isNotBlank())) "只允许这一次" else "允许") }
                            if (canWiden && t.scopeDir.isNotBlank()) OutlinedButton(
                                onClick = { ctl.run(Command.CodeDecide(msg.convId, t.id, true, "dir")) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) { Text("允许，并把 ${folderName(t.scopeDir)} 加进工作区", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            if (canWiden && t.prefix.isNotBlank()) OutlinedButton(
                                onClick = { ctl.run(Command.CodeDecide(msg.convId, t.id, true, "command")) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(if (t.prefix.startsWith("web:")) "允许，${t.prefix.removePrefix("web:")} 这个会话里不再问"
                                    else "允许，「${t.prefix.take(30)}」开头的这个会话里不再问", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
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

/** AI 看过的那张图：存的缩小副本（步骤上的 image），手机遥控时也取得到；点一下看大图。 */
@Composable
private fun StepImage(ctl: AppController, t: ToolStep) {
    val img = rememberImage(ctl, t.image)
    var big by remember(t.image) { mutableStateOf(false) }
    if (img == null) Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        // 按原图比例撑满 420 见方的框：横图宽 420，竖图（手机截图）高 420；太小的图也放大到看得见
        val ratio = img.width.coerceAtLeast(1).toFloat() / img.height.coerceAtLeast(1)
        androidx.compose.foundation.Image(img, t.input,
            Modifier.widthIn(max = 420.dp).heightIn(max = 420.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                .clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).clickable { big = true },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    }
    if (big) ImagePreview(ctl, Attachment(t.image, t.input.substringAfterLast('\\').substringAfterLast('/'), "image/jpeg", 0, AttachmentKind.IMAGE,
        note = "AI 看过的图：${t.input}")) { big = false }
}

/** 一行摘要：做了什么 + 这条的结果。 */
private fun codeStepLine(t: ToolStep): Pair<ImageVector, String> {
    val where = t.input.ifBlank { "." }
    // 还在等批准的：还没做，写「要…」（2026-10-17 截图看出「读了 X · 等你批准」自相矛盾）
    if (t.approval == "waiting") {
        val todo = when (t.kind) {
            "read" -> "要读 ${t.input}"
            "list" -> "要列 ${t.input}"
            "grep" -> "要在 ${t.input} 里查找"
            "find" -> "要在 ${t.input} 里找文件"
            "write" -> "要写 ${t.input}"
            "edit" -> "要改 ${t.input}"
            "run" -> "要跑「${t.input.take(80)}」"
            "fetch" -> "要打开 ${t.input.take(80)}"
            else -> "${t.kind} ${t.input}"
        }
        return Icons.Rounded.HourglassTop to "$todo · 等你批准"
    }
    val head = when (t.kind) {
        "read" -> "读了 ${t.input}"
        "list" -> if (where == ".") "列了项目根目录" else "列了 ${t.input}"
        "grep" -> "查找「${t.input}」"
        "write" -> "写了 ${t.input}"
        "edit" -> "改了 ${t.input}"
        "run" -> "跑了「${t.input.take(80)}」"
        "find" -> "找文件「${t.input}」"
        "agent" -> "子代理「${t.input}」"
        "search" -> "搜了「${t.input}」"
        "fetch" -> "打开了 ${t.input.take(80)}"
        "plan" -> "写好了计划（点开看）"
        else -> "${t.kind} ${t.input}"
    }
    val tail = when {
        t.approval == "waiting" -> " · 等你批准"
        t.approval == "denied" -> " · 没有做"
        t.kind == "run" && t.note.isNotEmpty() -> " · ${t.note}"
        t.kind == "write" || t.kind == "edit" -> t.change?.let { "（+${it.added} −${it.removed}）" } ?: ""
        (t.kind == "grep" || t.kind == "find") && !t.ok -> " · 没找到"
        t.kind == "search" && t.ok -> " · ${t.sources.size} 条"
        t.kind == "agent" -> " · ${t.note}"
        t.kind == "read" && !t.ok -> " · 读不了"
        !t.ok -> " · 没成功"
        else -> ""
    }
    return when (t.kind) {
        "read" -> Icons.Rounded.Description to (head + tail)
        "list" -> Icons.Rounded.FolderOpen to (head + tail)
        "grep", "find", "search" -> Icons.Rounded.Search to (head + tail)
        "write" -> Icons.Rounded.Add to (head + tail)
        "edit" -> Icons.Rounded.Edit to (head + tail)
        "plan" -> Icons.Rounded.Description to (head + tail)
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
    // 同一个文件这条回答里可能改了好几次（试用记账本时 AutoCapture.kt 改了 7 次，列表只算了第一次「+6 −1」，git 是 +18 −8）：
    // 行数从这个文件的完整差异里数（下面每个文件都要一份），没拿到之前先把几次的加起来顶着
    val sums = remember(msg.tools) { msg.tools.mapNotNull { it.change }.groupBy { it.path }.mapValues { (_, l) -> l.sumOf { it.added } to l.sumOf { it.removed } } }
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
    // 逐行评论（2026-10-18，开发补齐第 7 样）：点差异里的一行写评论，攒几条后「按评论再改」
    val comments = remember(messageId) { androidx.compose.runtime.mutableStateListOf<DiffComment>() }
    // 看每个文件改了什么（拒绝 / 撤销之后文件变了，重新取一遍）：左边的行数也从这里数
    LaunchedEffect(messageId, reload) {
        changes.forEach { c ->
            ctl.run(Command.CodeDiff(conv.id, c.id), quiet = true) { r ->
                if (r.ok) { diffs[c.id] = r.data; diffErr.remove(c.id) }
                else diffErr[c.id] = r.message.ifBlank { "看不了这处改动" }
            }
        }
    }
    fun stat(c: FileChange): Pair<Int, Int> = diffs[c.id]?.let { CodeDiffText.count(it) } ?: sums[c.path] ?: (c.added to c.removed)
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
        // 宽屏、高屏上跟着窗口变大（2026-10-22：以前固定 1000 宽、430 高，长代码行被截掉）
        val paneHeight = (maxHeight * 0.6f).coerceIn(430.dp, 900.dp)
        AppDialog("这次改了 ${changes.size} 个文件", onClose, width = if (side) (maxWidth * 0.86f).coerceIn(1000.dp, 1600.dp) else 560.dp, actions = {
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
                if (comments.isNotEmpty() && access.canAct) Button({
                    ctl.run(Command.CodeSend(conv.id, DiffComment.task(comments)))
                    comments.clear()
                    onClose()
                }) { Text("按 ${comments.size} 条评论再改") }
                else Button(onClick = onClose) { Text("知道了") }
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
                    Row(Modifier.fillMaxWidth().height(paneHeight)) {
                        Column(Modifier.width(260.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
                            changes.forEach { c ->
                                ChangeFileRow(c, stat(c), picked == c.id, readonly) { picked = c.id; confirmReject = "" }
                                if (picked == c.id) {
                                    ChangeRowActions(c, readonly, onReject = { confirmReject = c.id }) { review(c.id, true) }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxSize().background(Ext.c.border))
                        Box(Modifier.weight(1f).fillMaxSize().padding(start = 12.dp)) {
                            DiffPane(diffs[picked], diffErr[picked], current, if (access.canAct) comments else null)
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
                                    Text(stat(c).let { (a, r) -> "+$a −$r" } + "  " + reviewLabel(c),
                                        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (current != null && !readonly) ChangeRowActions(current, readonly, onReject = { confirmReject = current.id }) { review(current.id, true) }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(320.dp)) { DiffPane(diffs[picked], diffErr[picked], current, if (access.canAct) comments else null) }
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
private fun ChangeFileRow(c: FileChange, stat: Pair<Int, Int>, selected: Boolean, readonly: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) Ext.c.sidebarSelected else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.path, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("+${stat.first} −${stat.second}" + if (c.created) " · 新文件" else "",
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

/** 差异里一行的评论（2026-10-18）：哪个文件、改后第几行、那一行的字、评论。 */
internal class DiffComment(val path: String, val line: Int, val code: String, val note: String) {
    companion object {
        /** 攒下的评论变成一条任务。 */
        fun task(list: List<DiffComment>) = buildString {
            appendLine("按下面这些评论再改一版（评论写在你刚才改过的地方）：")
            list.forEach { c ->
                append("- ").append(c.path).append(if (c.line > 0) " 第 ${c.line} 行" else "")
                append("「").append(c.code.trim().take(120)).append("」：").appendLine(c.note.trim())
            }
        }

        /** 统一差异里第 idx 行在改后文件里是第几行（按 @@ 行数着算；删掉的行算它前面那一行）。 */
        fun newLineOf(lines: List<String>, idx: Int): Int {
            var n = 0
            for (i in 0..idx.coerceAtMost(lines.lastIndex)) {
                val l = lines[i]
                if (l.startsWith("@@")) { n = Regex("\\+(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: n; continue }
                if (l.startsWith("+++") || l.startsWith("---")) continue
                if (!l.startsWith("-")) n++
            }
            return n
        }
    }
}

@Composable
private fun DiffPane(text: String?, error: String?, c: FileChange?, comments: androidx.compose.runtime.snapshots.SnapshotStateList<DiffComment>? = null) {
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
        var target by remember(text) { mutableStateOf(-1) }
        var note by remember(text) { mutableStateOf("") }
        val lines = remember(text) { text.lines() }
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(10.dp)).background(Ext.c.codeBg).padding(8.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                DiffLines(text, onLine = if (comments != null) ({ i -> target = i; note = "" }) else null,
                    marked = comments?.filter { it.path == c.path }?.map { it.code }?.toSet().orEmpty(), selected = target)
            }
        }
        if (comments != null) {
            if (target >= 0) {
                val line = lines.getOrElse(target) { "" }
                Spacer(Modifier.height(6.dp))
                Text("给这一行写评论：${line.take(80)}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { AppTextField(note, { note = it }, placeholder = "比如：这里要判断空值") }
                    TextButton({
                        if (note.isNotBlank()) comments += DiffComment(c.path, DiffComment.newLineOf(lines, target), line.drop(1), note)
                        target = -1; note = ""
                    }) { Text("加评论") }
                }
            } else Text(if (comments.isEmpty()) "点差异里的一行可以写评论，攒几条后点「按评论再改」。" else "已写 ${comments.size} 条评论。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 从统一差异的文字里数加了几行、删了几行（文件头 +++ / --- 不算）。 */
internal object CodeDiffText {
    fun count(diff: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        for (line in diff.lineSequence()) when {
            line.startsWith("+++") || line.startsWith("---") -> {}
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
        return added to removed
    }
}

/** 统一差异：+ 绿、− 红、@@ 是位置行，可以选中复制。 */
@Composable
internal fun DiffLines(text: String, onLine: ((Int) -> Unit)? = null, marked: Set<String> = emptySet(), selected: Int = -1) {
    val lines = remember(text) { text.lines() }
    // 能写评论时每行可以点（点了就不好再拖选复制，所以只在差异查看器里开）
    val body: @Composable () -> Unit = {
        Column {
            lines.forEachIndexed { idx, line ->
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
                val commented = line.length > 1 && line.drop(1) in marked
                Text((if (commented) "💬 " else "") + line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = fg,
                    softWrap = false, modifier = Modifier.fillMaxWidth()
                        .background(if (idx == selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else bg)
                        .let { m -> if (onLine != null && !line.startsWith("@@") && !line.startsWith("+++") && !line.startsWith("---")) m.clickable { onLine(idx) } else m }
                        .padding(horizontal = 4.dp))
            }
        }
    }
    if (onLine == null) SelectionContainer { body() } else body()
}

/** 等宽显示的一块内容（命令、命令输出、读到的文件、列目录、查找结果），太长的只给开头。 */
@Composable
private fun MonoBox(text: String, wrap: Boolean = false) {
    val lines = remember(text) { text.lines() }
    val shown = lines.take(STEP_LINES)
    // wrap：要跑的命令自动换行、整条看得见（2026-10-22 试用时批准卡片上的命令截在「-ErrorAction S」，关键参数看不到就要做决定）
    if (wrap) SelectionContainer {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ext.c.codeBg).padding(8.dp)) {
            shown.forEach { Text(it.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } else ScrollPane(Modifier.fillMaxWidth()) {
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
internal fun ScrollPane(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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

/**
 * 下面的输入框：发一条任务。上一条还在做的时候照样能发，会排在后面。
 * 2026-10-17：和聊天一样能带附件——回形针、图片按钮、拖进来、Ctrl+V 粘贴截图或复制的文件。
 * 图片发给能看图的模型；文档抽出文字一起发；电脑上的文件还把原路径告诉模型（并加进工作区，读不用再问）。
 */
@Composable
private fun CodeComposer(ctl: AppController, conv: Conversation, running: Boolean, short: Boolean = false, maxText: androidx.compose.ui.unit.Dp = 200.dp) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var text by remember(conv.id) { mutableStateOf(TextFieldValue("")) }
    val pending = remember(conv.id) { mutableStateListOf<PendingFile>() }
    var uploading by remember(conv.id) { mutableStateOf(0) }
    val slashAll = rememberSlashCommands(ctl, conv.id)

    fun add(files: List<PickedFile>) {
        // 2026-10-17 用户：「把所有限制全部删掉，随意加任何文件」。照 Claude Code：电脑上的文件、文件夹只把路径交给 AI，
        // 不上传、不限大小（AI 用 read_file 去读，已经加进工作区，不用再问）；只有图片还要传上去给能看图的模型看。
        // 手机遥控电脑时手机上的路径电脑上没有，照旧上传。
        val local = platform.isDesktop && !ctl.remoteMode
        files.forEach { f ->
            val isImage = f.mime.startsWith("image/") || f.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
            if (local && f.path.isNotBlank() && (!isImage || f.pathOnly)) {
                if (pending.none { it.local && it.path.equals(f.path, ignoreCase = true) }) pending.add(PendingFile(f.name, path = f.path, local = true))
                return@forEach
            }
            if (f.pathOnly) { ctl.toast("「${f.name}」读不了内容，也没有路径，放不进来"); return@forEach }
            val p = PendingFile(f.name, path = f.path)
            pending.add(p)
            uploading++
            scope.launch {
                val att = ctl.backend.upload(f.name, f.mime, f.bytes)
                val idx = pending.indexOf(p)
                if (idx >= 0) pending[idx] = PendingFile(f.name, att, att == null, f.path)
                uploading--
                if (att == null) ctl.toast("「${f.name}」上传失败")
            }
        }
    }
    fun pick(imagesOnly: Boolean) { scope.launch { add(platform.pickFiles(imagesOnly)) } }
    // 「回到这里」放回来的任务文字
    val draft = ctl.composerDraft
    LaunchedEffect(draft, conv.id) {
        val (cid, t) = draft ?: return@LaunchedEffect
        if (cid != conv.id) return@LaunchedEffect
        ctl.composerDraft = null
        text = TextFieldValue(t, TextRange(t.length))
    }
    // 拖进来、粘贴进来的（CodeScreen 收到后放进 composerInbox）
    val inbox = ctl.composerInbox
    LaunchedEffect(inbox, conv.id) {
        val (cid, inc) = inbox ?: return@LaunchedEffect
        if (cid != conv.id) return@LaunchedEffect
        ctl.composerInbox = null
        inc.attachments.forEach { pending.add(PendingFile(it.name, it)) }
        add(inc.files)
    }

    fun send() {
        if (uploading > 0) return
        val ids = pending.mapNotNull { it.att?.id }
        // 原路径只在电脑上自己用时才有意义（手机遥控电脑时手机上的路径电脑上没有）
        val paths = if (platform.isDesktop && !ctl.remoteMode) pending.filter { it.att != null || it.local }.mapNotNull { it.path.ifBlank { null } }.distinct() else emptyList()
        if (text.text.isBlank() && ids.isEmpty() && paths.isEmpty()) return
        // 「/名字 后面的字」没从列表里选、直接发的：也照命令展开
        Regex("^/(\\S+)\\s*([\\s\\S]*)$").find(text.text.trim())?.let { m ->
            slashAll.firstOrNull { it.name.equals(m.groupValues[1], ignoreCase = true) }?.let { text = TextFieldValue(expandSlash(it, m.groupValues[2])) }
        }
        // 手机上：等批准、做完时要弹通知，趁现在（应用在前台）问一下通知权限
        if (!platform.isDesktop) platform.askNotifyPermission()
        ctl.run(Command.CodeSend(conv.id, text.text, ids, paths))
        text = TextFieldValue("")
        pending.clear()
    }
    // 打「@」补全文件名（2026-10-18）
    val fileQuery = atQuery(text)
    val fileHits = rememberFileSuggestions(ctl, conv.id, fileQuery)
    // 开头打「/」补全斜杠命令（2026-10-18）：选中就展开成命令的话，可以改了再发（清单在上面 send 前面取）
    val slashQ = slashQuery(text)
    val slashHits = if (slashQ == null) emptyList() else slashAll.filter { it.name.startsWith(slashQ, ignoreCase = true) || slashQ in it.name }
    fun pickSlash(item: SlashItem) { val t = expandSlash(item, ""); text = TextFieldValue(t, TextRange(t.length)) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = if (short) 6.dp else 14.dp, top = if (short) 2.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = CODE_MAX_WIDTH).fillMaxWidth()) {
            // 候选列表窗口矮时让位：只占输入框排完剩下的高度，放不下就滑（2026-10-22，和对话栏一样）
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (fileQuery != null) FileSuggestList(fileHits) { text = insertAt(text, it) }
                if (slashQ != null) SlashList(slashHits) { pickSlash(it) }
            }
            GlassSurface(shape = RoundedCornerShape(if (com.guixing.jixunying.ui.glass.LocalGlass.current.on) com.guixing.jixunying.ui.glass.glassRadius(com.guixing.jixunying.model.GlassTarget.BAR) else 22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Ext.c.border), shadowElevation = 3.dp) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
                    PendingStrip(ctl, pending)
                    // 写字的地方最后量：下面那一排（发送键）先排，窗口矮时它让位、里面能滑
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(min = 26.dp, max = maxText)) {
                        if (text.text.isEmpty()) Text(
                            if (platform.isDesktop) "说一句要它做什么，可以拖文件、Ctrl+V 贴截图…  Enter 发送，Shift+Enter 换行" else "说一句要它做什么…",
                            style = MaterialTheme.typography.bodyLarge, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).onPreviewKeyEvent { e ->
                                if (platform.isDesktop && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed) {
                                    // 正在补全文件名：回车选第一个
                                    if (slashQ != null && slashHits.isNotEmpty()) pickSlash(slashHits.first())
                                    else if (fileQuery != null && fileHits.isNotEmpty()) text = insertAt(text, fileHits.first()) else send()
                                    true
                                } else if (platform.isDesktop && e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed) && platform.clipboardHasFiles()) {
                                    // 剪贴板里是截图或复制的文件：当附件放进来；是文字就照常粘贴
                                    scope.launch {
                                        val files = platform.clipboardFiles()
                                        if (files.isEmpty()) ctl.toast("剪贴板里的东西读不出来") else add(files)
                                    }
                                    true
                                } else false
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 左边一组只占剩下的宽度，发送键先排、永远是完整大小（2026-10-22，和对话栏一样；以前「上一条还在做」那行字在窄屏、大字体下会把发送键挤没）
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            ComposerIcon(Icons.Rounded.AttachFile, if (platform.isDesktop) "附件（代码、图片、PDF、Word、Excel…；也可以把文件拖进来，或者 Ctrl+V 粘贴截图）" else "附件") { pick(false) }
                            ComposerIcon(Icons.Rounded.Image, "图片") { pick(true) }
                            Spacer(Modifier.width(6.dp))
                            if (running) {
                                Text("上一条还在做，这条会排在后面", style = MaterialTheme.typography.labelSmall, color = Ext.c.warning, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val canSend = (text.text.isNotBlank() || pending.any { it.att != null || it.local }) && uploading == 0
                        Box(
                            Modifier, contentAlignment = Alignment.Center,
                        ) {
                            // 液态发送键（2026-10-22），和对话页同一个
                            GlassRoundButton(Icons.Rounded.ArrowUpward, "发送", enabled = canSend, color = MaterialTheme.colorScheme.primary,
                                onColor = MaterialTheme.colorScheme.onPrimary, onClick = { send() })
                        }
                    }
                }
            }
            if (!short) Text(
                "电脑上的文件都能读；改工作区外面的会先问你（全部放开就不问），改过的都能撤销。" + when {
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
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    Text("选一个项目文件夹，它就能读代码、改文件、跑命令，也能读写别的地方的文件（先问你）；它做的每一步都写在对话里给你看，" +
                        "改文件、跑命令要不要先问你，由权限档说了算，随时能换。每个会话一个文件夹，互不影响。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    val isDesktopHere = LocalPlatform.current.isDesktop
                    Button(onClick = { ctl.newCode(isDesktopHere) }, shape = RoundedCornerShape(12.dp)) {
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
            FieldLabel("项目文件夹", "命令在这里跑；别的文件夹之后能加进工作区")
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
