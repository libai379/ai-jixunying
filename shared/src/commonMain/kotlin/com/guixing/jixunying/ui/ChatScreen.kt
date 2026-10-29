package com.guixing.jixunying.ui

import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import com.guixing.jixunying.ui.glass.glass
import androidx.compose.ui.layout.onSizeChanged
import com.kashif_e.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
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

/** 对话栏回答区和输入框最宽多少（2026-10-22 从 860 放宽；读文字为主，比 Code 栏的 1400 窄一些，一行不至于太长）。 */
private val CHAT_MAX_WIDTH = 1000.dp

private const val PAINTER = "tool:image"
private const val CHAIR = "tool:chair"
private const val VIDEOMAKER = "tool:video"

@Composable
fun ChatScreen(ctl: AppController, convId: String, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val allMessages by ctl.backend.store.messages.collectAsState()
    val conv = state.conversation(convId) ?: return
    val messages = allMessages[convId].orEmpty()
    // 回答完自动读（设置 → 语音 的开关，存的是本机的设置）：这个对话里刚答完的回答念出来，
    // 打开对话时已有的旧回答不念（SpeechPlayer.onMessages 里分辨）
    val localSettings by ctl.hub.local.store.state.collectAsState()
    LaunchedEffect(convId, messages, localSettings.settings.speech.autoRead) {
        ctl.speech.onMessages(convId, messages, localSettings.settings.speech.autoRead)
    }
    var showConvSettings by remember { mutableStateOf(ctl.debugDialog == "convsettings") }
    // 立场档案：这个对话记过议题才去取；点了某条回答下的立场标签就打开它
    val topics = if (conv.stanceTopics > 0) rememberStanceTopics(ctl, convId, conv.stanceAt) else emptyList()
    var stanceDialog by remember { mutableStateOf<String?>(if (ctl.debugDialog == "stances") "" else null) }
    // 不管从哪条路打开的对话，聊天记录没加载就补上（以前有的入口只切了对话、没加载，显示成空的）
    LaunchedEffect(convId, ctl.backend) {
        if (!ctl.backend.store.hasMessages(convId)) ctl.run(Command.LoadMessages(convId), quiet = true)
    }

    // 电脑上把文件拖进聊天区：放进输入框（和点回形针选的一样，先上传，等你写问题）
    val platform = LocalPlatform.current
    var dragging by remember { mutableStateOf(false) }
    Box(platform.fileDropTarget(Modifier.fillMaxSize(), onHover = { dragging = it }) { files ->
        if (files.isEmpty()) ctl.toast("没认出拖进来的是什么（不是文件、图片，也不是路径）")
        else ctl.composerInbox = convId to Incoming(files = files)
    }) {
        // 横屏、键盘弹出、分屏时窗口很矮（2026-10-22 用户：「需要兼容大多数手机的屏幕比例才行」）：
        // 矮（不到 420dp）时输入框收紧、不显示底下那行提示；特别矮（不到 240dp，横屏又开着键盘）时顶栏先收起来，保住输入框和发送键
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight
            val short = h < 420.dp
            // 液态玻璃开着：输入框浮在聊天记录上面，聊天记录一直排到最底下（底部留出输入框的高度），
            // 输入框的玻璃同时采样壁纸和聊天记录，滚动的字从玻璃下面透出来、被折射（10-24 用户：「它是透明的，能透出下面的文字」）。
            // 聊天记录这一层和输入框是兄弟（采样源不能是玻璃的上级，癸星科技踩过：安卓上原生崩溃）
            val env = com.guixing.jixunying.ui.glass.LocalGlass.current
            val floating = env.on && env.backdrop != null
            val contentLayer = com.kashif_e.backdrop.backdrops.rememberLayerBackdrop()
            var composerH by remember { mutableStateOf(0.dp) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val composer: @Composable () -> Unit = {
                BackgroundBar(ctl, conv)
                Composer(ctl, state, conv, running = messages.any { it.status == MsgStatus.STREAMING }, short = short,
                    maxText = (h * 0.3f).coerceIn(48.dp, 200.dp))
            }
            var topH by remember { mutableStateOf(0.dp) }
            // 在这个对话里找（2026-10-24）：Ctrl+F 或顶栏放大镜；命中的消息一条条跳，当前这条框出来
            var findQuery by remember(convId) { mutableStateOf("") }
            var findIndex by remember(convId) { mutableStateOf(0) }
            val findHits = remember(messages, findQuery) {
                val q = findQuery.trim()
                if (q.isEmpty()) emptyList() else messages.filter { it.content.contains(q, ignoreCase = true) }.map { it.id }
            }
            val currentFind = if (ctl.findOpen) findHits.getOrNull(findIndex.coerceIn(0, (findHits.size - 1).coerceAtLeast(0))) else null
            LaunchedEffect(currentFind) { if (currentFind != null) ctl.focusMessageId = currentFind }
            val topBar: @Composable () -> Unit = {
                if (h >= 240.dp) ChatTopBar(state, conv, wide, openDrawer, onStances = if (conv.stanceTopics > 0) ({ stanceDialog = "" }) else null,
                    onFind = { ctl.findOpen = true }, divider = !floating) { showConvSettings = true }
                if (ctl.findOpen) FindBar(findQuery, { findQuery = it; findIndex = (findHits.size - 1).coerceAtLeast(0) }, findHits.size, findIndex,
                    onPrev = { if (findHits.isNotEmpty()) findIndex = (findIndex - 1 + findHits.size) % findHits.size },
                    onNext = { if (findHits.isNotEmpty()) findIndex = (findIndex + 1) % findHits.size },
                    onClose = { ctl.findOpen = false; findQuery = "" })
            }
            Column(Modifier.fillMaxSize()) {
                if (!floating) topBar()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    MessageList(ctl, state, conv, messages, topics,
                        modifier = if (floating) Modifier.layerBackdrop(contentLayer) else Modifier,
                        bottomPad = if (floating) composerH else 0.dp, topPad = if (floating) topH else 0.dp, highlightId = currentFind) { stanceDialog = it }
                    if (floating) {
                        val both = com.kashif_e.backdrop.backdrops.rememberCombinedBackdrop(env.backdrop!!, contentLayer)
                        // 顶栏也浮在聊天记录上面（10-24：「所有的框都是」透出下面的字）：一条贴顶的玻璃，往上翻的字从它下面过
                        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topH = with(density) { it.height.toDp() } }) {
                            CompositionLocalProvider(com.guixing.jixunying.ui.glass.LocalGlass provides
                                com.guixing.jixunying.ui.glass.GlassEnv(both, env.on, env.dark, env.tuning, env.paint)) {
                                // 和输入框一样：两边留 16dp、圆角，浮着的一块（10-24 用户：「上面这一栏……跟输入框一样」）
                                GlassSurface(RoundedCornerShape(com.guixing.jixunying.ui.glass.glassRadius(com.guixing.jixunying.model.GlassTarget.BAR)),
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                                    Column { topBar() }
                                }
                            }
                        }
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .onSizeChanged { composerH = with(density) { it.height.toDp() } }) {
                            CompositionLocalProvider(com.guixing.jixunying.ui.glass.LocalGlass provides
                                com.guixing.jixunying.ui.glass.GlassEnv(both, env.on, env.dark, env.tuning, env.paint)) { composer() }
                        }
                    }
                }
                if (!floating) composer()
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
    if (showConvSettings) ConversationSettingsDialog(ctl, state, conv) { showConvSettings = false }
    stanceDialog?.let { focus -> ConversationStancesDialog(ctl, state, conv, topics, focus.ifEmpty { null }) { stanceDialog = null } }
}

@Composable
private fun ChatTopBar(state: AppState, conv: Conversation, wide: Boolean, openDrawer: () -> Unit, onStances: (() -> Unit)?, onFind: () -> Unit = {},
                       divider: Boolean = true, onSettings: () -> Unit) {
    val members = conv.memberIds.mapNotNull { state.member(it) }
    // 至少 60 高，系统字体调大时跟着长高（2026-10-22：以前写死 60，200% 字体时副标题掉出顶栏、压到消息上）
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!wide) IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(conv.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (members.size > 1) "群聊 · ${members.size} 位成员 · " + if (conv.council) "会审" else when (conv.replyMode) {
                    ReplyMode.INDEPENDENT -> "独立作答"
                    ReplyMode.RELAY -> "接力讨论"
                    ReplyMode.MENTION_ONLY -> "只回答被 @ 的"
                } else members.firstOrNull()?.let { "${it.name} · ${it.modelId}" } ?: "还没有成员",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onStances != null) {
            Pill("立场 ${conv.stanceTopics}", MaterialTheme.colorScheme.primary, onClick = onStances)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onFind, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Search, "在对话里找", Modifier.size(19.dp), tint = Ext.c.subtle) }
        Spacer(Modifier.width(4.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).chipOutline(RoundedCornerShape(50)).clickable(onClick = onSettings)
                .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            members.take(4).forEach { MemberAvatar(it, 24.dp); Spacer(Modifier.width(2.dp)) }
            if (members.size > 4) Text("+${members.size - 4}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Tune, "对话设置", Modifier.size(16.dp), tint = Ext.c.subtle)
        }
    }
    // 浮着的玻璃顶栏里不画分隔线（2026-10-28 用户：「顶栏下面有白边」——画在玻璃里就是底边上一条白线）
    if (divider) androidx.compose.material3.HorizontalDivider(color = Ext.c.border)
}

@Composable
private fun MessageList(ctl: AppController, state: AppState, conv: Conversation, messages: List<Message>,
                        topics: List<com.guixing.jixunying.model.StanceTopic>, modifier: Modifier = Modifier, bottomPad: Dp = 0.dp, topPad: Dp = 0.dp,
                        highlightId: String? = null, onStance: (String) -> Unit) {
    // 和微信一样从底下往上排：打开对话就停在最新一条的最后一行，图片后加载、AI 正在输出也不会把最新一行挤出去。
    // 每个对话各记各的位置（以前所有对话共用一份：在一个对话里往上翻过，之后打开别的对话都停在上面）
    val listState = remember(conv.id) { LazyListState() }
    val stanceOf = remember(topics) { topics.flatMap { t -> t.entries.map { e -> e.messageId to (t to e) } }.groupBy({ it.first }, { it.second }) }
    val last = messages.lastOrNull()
    // 倒着排：0 是底部留白，1 是最新一条，最上面是开头那行提示
    val newestFirst = remember(messages) { messages.asReversed() }
    // 从侧栏搜索结果、立场档案点进来：滚到那条消息，让它的开头露在最上面
    LaunchedEffect(ctl.focusMessageId, messages.size) {
        val target = ctl.focusMessageId ?: return@LaunchedEffect
        val i = messages.indexOfFirst { it.id == target }
        if (i < 0) return@LaunchedEffect
        val index = messages.size - i
        listState.scrollToItem(index)   // 倒着排时这一条贴在最下面
        val info = listState.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.index == index }?.let { item ->
            listState.scrollBy((item.size - (info.viewportEndOffset - info.viewportStartOffset)).toFloat())
        }
        ctl.focusMessageId = null
    }
    // 新消息或流式输出：自己刚发的，或者本来就在底部附近，贴到底（停在底部时倒着排本来就不会动）
    LaunchedEffect(messages.size, last?.content?.length, last?.attachments?.size) {
        if (messages.isEmpty() || ctl.focusMessageId != null) return@LaunchedEffect
        val justSent = last?.role == Role.USER
        if (justSent || listState.firstVisibleItemIndex in 1..2) listState.animateScrollToItem(0)
    }
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad)) { ChatEmptyHint(state, conv) { t -> ctl.composerDraft = conv.id to t } }
        return
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 点聊天区域收起键盘（和微信一样）
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        reverseLayout = true,
        // 输入框浮在上面时底部留出它的高度：用内边距不用一行空白（空白那行跟着输入框变高会把整个列表推走，10-24 codeListHoldsWhileRunning）
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topPad, bottom = bottomPad),
        // 消息少、一屏放得下时照样从上往下排，不沉在底下
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 输入框浮在上面时，底部多留出它的高度，最后一条不被挡住（往上一点就从玻璃下面透出来）
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        items(newestFirst, key = { it.id }) { m ->
            Box(Modifier.widthIn(max = CHAT_MAX_WIDTH).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (m.id == highlightId) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(6.dp) else Modifier)) {
                if (m.role == Role.USER) UserMessage(ctl, state, m) else AiMessage(ctl, state, m, stanceOf[m.id].orEmpty(), onStance)
            }
        }
        item(key = "top") {
            // 倒着排时一项里的几块也会倒过来，所以包一层 Column
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                if (conv.summarized > 0) {
                    Text("前面 ${conv.summarized} 条已由记录员压缩成摘要，AI 记得要点；原话还在这里，AI 需要时也能搜到",
                        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 640.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyHint(state: AppState, conv: Conversation, onTip: (String) -> Unit) {
    val members = conv.memberIds.mapNotNull { state.member(it) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { members.take(5).forEach { MemberAvatar(it, 48.dp) } }
        Spacer(Modifier.height(16.dp))
        Text(if (members.size > 1) "群里有 ${members.joinToString("、") { it.name }}" else "和 ${members.firstOrNull()?.name ?: "AI"} 聊聊",
            style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (members.size > 1) "直接提问，大家各自独立回答；想让某位回答就喊名字（「${members.first().name}，……」）或 @${members.first().name}，@所有人 让全员回答。\n" +
                "AI 之间也会互相 @、互相纠错；有答案的问题，谁一开始怎么说、后来谁改了口，记在「立场档案」里。"
            else "可以发图片、PDF、Word、Excel 等文件；打开「联网」它会先搜再答；想要图直接说「画一张……」。",
            style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle,
        )
        Spacer(Modifier.height(20.dp))
        // 点一下就填进输入框（以前看着能点，其实点了没反应）
        val tips = listOf("今天有什么值得关注的科技新闻？", "帮我把这份文档总结成 5 条要点", "画一只在月球上喝茶的橘猫", "我电脑里最近改过哪些文档？")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            tips.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t -> Pill(t, MaterialTheme.colorScheme.primary) { onTip(t) } }
                }
            }
        }
    }
}

@Composable
private fun UserMessage(ctl: AppController, state: AppState, m: Message) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        // 几点说的（2026-10-29 用户要每句话都带时间）
        Text(clockText(m.createdAt), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(bottom = 3.dp))
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
private fun AiMessage(ctl: AppController, state: AppState, m: Message,
                      stances: List<Pair<com.guixing.jixunying.model.StanceTopic, com.guixing.jixunying.model.StanceEntry>> = emptyList(),
                      onStance: (String) -> Unit = {}) {
    val member = state.member(m.senderId)
    val painter = m.senderId == PAINTER
    val videoMaker = m.senderId == VIDEOMAKER
    val chair = m.senderId == CHAIR
    val review = m.stage == "review"
    // 会审的互评默认折叠（四份互评很长），点一下展开
    var folded by remember(m.id) { mutableStateOf(review) }
    val (body, thinkInline) = remember(m.content) { splitThink(m.content) }
    val reasoning = (m.reasoning + if (thinkInline.isNotEmpty()) "\n$thinkInline" else "").trim()
    val sources = remember(m.tools) { m.tools.filter { it.kind == "search" }.flatMap { it.sources } }

    Row(Modifier.fillMaxWidth()) {
        if (painter) MemberAvatar(null, 34.dp, emoji = "🎨", color = 0xFFEC4899)
        else if (videoMaker) MemberAvatar(null, 34.dp, emoji = "🎬", color = 0xFF7C3AED)
        else if (chair) MemberAvatar(null, 34.dp, emoji = "⚖️", color = 0xFF0F766E)
        else MemberAvatar(member, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (painter) "画图助手" else if (videoMaker) "视频助手" else if (chair) "会审主席" else member?.name ?: "已移除的成员", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
                Text(clockText(m.createdAt), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                Spacer(Modifier.width(8.dp))
                if (review) { Pill(if (folded) "互评 · 点开看" else "互评", MaterialTheme.colorScheme.secondary) { folded = !folded }; Spacer(Modifier.width(6.dp)) }
                if (chair) { Pill("会审汇总", Ext.c.success); Spacer(Modifier.width(6.dp)) }
                if (m.modelLabel.isNotBlank()) Pill(m.modelLabel, Ext.c.subtle)
            }
            // 立场档案：这条回答在哪个议题上是什么立场、是不是改口（点开看这道题大家怎么说）
            stances.forEach { (t, e) ->
                Spacer(Modifier.height(4.dp))
                StanceChip(t, e) { onStance(t.id) }
            }
            Spacer(Modifier.height(6.dp))
            m.tools.lastOrNull { it.kind == "todo" }?.let { TodoCard(it.output) }
            m.tools.filter { it.approval == "waiting" }.forEach { ReadApprovalCard(ctl, m, it, member?.name ?: "AI") }
            ToolStepsView(m.tools.filter { it.approval != "waiting" && it.change == null && it.kind !in setOf("write", "edit", "delete", "run", "todo") }, streaming = m.status == MsgStatus.STREAMING)
            // 跑过的命令：一行，点开看输出
            m.tools.filter { it.kind == "run" && it.approval != "waiting" }.forEach { RunStepView(it) }
            // 改过的文件单独列出来（能看差异、能撤销）；拒绝了 / 没改成的写一句
            m.tools.filter { it.change != null }.forEach { ChangedFileView(ctl, m, it) }
            m.tools.filter { (it.kind == "write" || it.kind == "edit" || it.kind == "delete") && it.change == null && it.approval != "waiting" && it.note.isNotBlank() }.forEach {
                Text("${it.input.substringAfterLast('\\').substringAfterLast('/')}：${it.note}", style = MaterialTheme.typography.bodySmall,
                    color = Ext.c.warning, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (reasoning.isNotEmpty()) ReasoningView(reasoning, streaming = m.status == MsgStatus.STREAMING && body.isEmpty())
            if (body.isNotBlank() && folded) Text(body.lineSequence().firstOrNull { it.isNotBlank() }?.take(80).orEmpty() + " …",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.clickable { folded = false })
            else if (body.isNotBlank()) SelectionContainer { MarkdownText(body, sources = sources) }
            if (m.status == MsgStatus.STREAMING && body.isEmpty() && reasoning.isEmpty()) TypingDots()
            if (m.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AttachmentStrip(ctl, m.attachments, alignEnd = false, large = true)
            }
            if (m.status == MsgStatus.ERROR) ErrorBox(m.error, ctl)
            if (m.status == MsgStatus.STOPPED) Text("（已停止）", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
            if (sources.isNotEmpty() && m.status != MsgStatus.STREAMING) SourcesRow(sources)
            if (m.status != MsgStatus.STREAMING && !folded) MessageActions(ctl, m, alignEnd = false, canRegenerate = !review && !chair,
                export = state.settings.office.exportButtons && !painter && !videoMaker && body.isNotBlank() && m.status == MsgStatus.DONE, body = body)
        }
    }
}

/**
 * AI 要新建 / 修改电脑上的文件，停下来问用户（2026-10-17，对话栏 = Chat + Cowork）：卡片上带差异。
 * 读文件不问（用户：「比 Claude 更开放」）；拖进来的文件夹、选了「不再问」的、开了「全部放开」的也不问。
 * 「以后这个文件夹不再问」只在电脑上给（手机上点了也只算这一次，见 PhoneGate）。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReadApprovalCard(ctl: AppController, m: Message, t: ToolStep, who: String) {
    val canWiden = LocalPlatform.current.isDesktop && !ctl.remoteMode
    Column(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, Ext.c.warning.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("$who 要${t.note.ifBlank { "改这个文件" }}：", style = MaterialTheme.typography.labelMedium, color = Ext.c.warning)
        SelectionContainer { Text(t.input, style = if (t.kind == "run") MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else MaterialTheme.typography.bodyMedium) }
        if (t.output.isNotBlank()) ScrollPane { DiffLines(t.output) }
        // 允许、拒绝两个在第一行挨着；「以后不再问」另起一行（和 Code 栏一样）
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            DecideButton(if (canWiden) "只允许这一次" else "允许", DecideKind.ALLOW) { ctl.run(Command.ChatDecide(m.convId, t.id, true)) }
            DecideButton("拒绝", DecideKind.DENY) { ctl.run(Command.ChatDecide(m.convId, t.id, false)) }
        }
        if (canWiden && t.scopeDir.isNotBlank()) DecideButton(
            "允许，${t.scopeDir.substringAfterLast('\\').substringAfterLast('/')} 这个对话里以后不再问", DecideKind.WIDEN,
        ) { ctl.run(Command.ChatDecide(m.convId, t.id, true, "dir")) }
    }
}

/**
 * 这个对话里 AI 不用问就能改的文件夹（2026-10-17，和 Code 栏的「工作区」对应）：拖进来的、批准时选了「不再问」的。
 * 读文件本来就不问；这里管的是改。只能在电脑上改（PhoneGate）。
 */
@Composable
private fun ChatFoldersDialog(ctl: AppController, conv: Conversation, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    fun save(paths: List<String>) = ctl.run(Command.ChatSetGranted(conv.id, paths), quiet = true) { r -> if (!r.ok) ctl.toast(r.message.ifBlank { "没改成" }) }
    AppDialog("这个对话的文件夹", onClose, width = 560.dp, actions = { Button(onClose) { Text("好了") } }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("电脑上的文件 AI 都能读，不用问。下面这些文件夹里，AI 新建、修改文件也不问；别处的改之前会先问你（打开「改文件全部放开」就都不问）。" +
                "改过的文件都在回答里能一键撤销。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(10.dp))
            if (conv.grantedPaths.isEmpty()) Text("还没有。把文件夹拖进输入框，或者点下面加一个。", style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle)
            conv.grantedPaths.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(15.dp), tint = Ext.c.subtle)
                    Spacer(Modifier.width(6.dp))
                    Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton({ save(conv.grantedPaths - p) }) { Text("移出", color = MaterialTheme.colorScheme.error) }
                }
            }
            TextButton({
                scope.launch {
                    val f = platform.pickFolder("加一个文件夹") ?: return@launch
                    if (conv.grantedPaths.any { it.equals(f, ignoreCase = true) }) { ctl.toast("已经加过了"); return@launch }
                    save(conv.grantedPaths + f)
                }
            }) { Text("加一个文件夹") }
        }
    }
}

/** AI 在对话里跑过的一条命令：一行摘要（退出码），点开看输出。 */
@Composable
private fun RunStepView(t: ToolStep) {
    var open by remember(t.id) { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Edit, null, Modifier.size(14.dp), tint = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
            Text("跑了「${t.input.take(60)}」" + if (t.approval == "denied") " · 没有做" else if (t.note.isNotBlank()) " · ${t.note.take(40)}" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        // 点开先看完整的命令（一行摘要只有前 60 个字，2026-10-22），再看输出
        AnimatedVisibility(open) {
            SelectionContainer {
                Column(Modifier.padding(start = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.input, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                    if (t.output.isNotBlank()) Text(t.output.take(6000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                }
            }
        }
    }
}

/** AI 在对话里改过的一个文件：一行摘要，点开看差异，能「撤销」（恢复成改之前的样子）。 */
@Composable
private fun ChangedFileView(ctl: AppController, m: Message, t: ToolStep) {
    val ch = t.change ?: return
    var open by remember(t.id) { mutableStateOf(false) }
    val undone = ch.review == "rejected"
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Description, null, Modifier.size(14.dp), tint = if (undone) Ext.c.subtle else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text((if (t.kind == "delete") "删掉了 " else if (ch.created) "新建了 " else "改了 ") + ch.path.substringAfterLast('\\').substringAfterLast('/') +
                (if (t.kind == "delete") (if (ch.removed > 1) "（${ch.removed} 个文件）" else "") else "（+${ch.added} −${ch.removed}）") + if (undone) " · 已恢复" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = Ext.c.subtle)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(ch.path, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle) }
                if (t.output.isNotBlank()) ScrollPane { DiffLines(t.output) }
                if (!undone && LocalPlatform.current.isDesktop && !ctl.remoteMode) TextButton({ ctl.run(Command.ChatRevert(m.convId, ch.id)) }) {
                    Text(if (t.kind == "delete") "恢复（放回去）" else if (ch.created) "撤销（删掉这个新建的文件）" else "撤销（恢复成改之前的样子）", color = MaterialTheme.colorScheme.error)
                }
            }
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
    val videos = tools.count { it.kind == "video" }
    val docs = tools.count { it.kind == "doc" }
    val history = tools.count { it.kind == "history" }
    val memos = tools.count { it.kind == "memory" }
    val files = tools.count { it.kind == "file" }
    val summary = buildList {
        if (files > 0) add("做了 $files 个文件")
        if (searches > 0) add("联网搜了 $searches 次")
        if (pages > 0) add("读了 $pages 个网页")
        if (docs > 0) add("查了 $docs 次文档")
        if (history > 0) add("翻了 $history 次聊天记录")
        if (memos > 0) add("记了 $memos 条")
        if (images > 0) add("画了 $images 张图")
        if (videos > 0) add("做了 $videos 段视频")
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
                if (streaming) "$summary · 正在${if (latest.kind == "fetch") "读网页" else if (latest.kind == "image") "画图" else if (latest.kind == "video") "做视频" else "搜「${latest.input.take(16)}」"}…" else summary,
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
        "file" -> Icons.Rounded.Description to (if (t.ok) "做了文件《${t.input.take(50)}》" else "文件没做成：${t.input.take(40)}")
        "run" -> Icons.Rounded.Edit to ("跑了「${t.input.take(50)}」" + if (t.note.isNotBlank()) " · ${t.note.take(30)}" else "")
        // 听平台视频里说的话（2026-10-23）
        "listen" -> Icons.Rounded.Mic to ((if (t.ok) "听了视频" else "视频没听成") + if (t.note.isNotBlank()) " · ${t.note.take(40)}" else "")
        "video" -> Icons.Rounded.Movie to ((if (t.ok) "做视频：" else "视频没做成：") + t.input.take(30) + if (t.note.isNotBlank()) " · ${t.note.take(30)}" else "")
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
                // 说明不一定是出错（比如「这次按意思和字面合起来搜」）：真出错才用红色
                if (t.note.isNotEmpty()) Text(t.note, style = MaterialTheme.typography.bodySmall,
                    color = if (!t.ok || "出错" in t.note) MaterialTheme.colorScheme.error else Ext.c.subtle)
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
private fun ErrorBox(error: String, ctl: AppController) {
    // 错误里说了「到 设置→某页」的，直接给个按钮过去，不用自己找
    val fix = when {
        "模型服务" in error || "API Key" in error || "Key 不对" in error || "余额" in error -> SettingsTab.PROVIDERS to "去模型服务"
        "AI 成员" in error -> SettingsTab.MEMBERS to "去 AI 成员"
        "画图" in error && "设置" in error -> SettingsTab.IMAGE to "去画图设置"
        "代理" in error -> SettingsTab.APPEARANCE to "去改代理"
        else -> null
    }
    Row(
        Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            SelectionContainer { Text(error.ifBlank { "出错了" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
            if (fix != null) TextButton(onClick = { ctl.openSettings(fix.first) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                Text(fix.second + " ›", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MessageActions(ctl: AppController, m: Message, alignEnd: Boolean, canRegenerate: Boolean, export: Boolean = false, body: String = "") {
    val clipboard = LocalClipboardManager.current
    val platform = LocalPlatform.current
    // 像豆包的导出：这条回答存成 Word；里面有表格的话，表格存成 Excel
    fun export(fmt: String) = ctl.run(Command.ExportMessage(m.convId, m.id, fmt), quiet = true) { r ->
        if (!r.ok) { ctl.toast(r.message); return@run }
        val f = runCatching { com.guixing.jixunying.model.AppJson.decodeFromString(com.guixing.jixunying.model.ExportedFile.serializer(), r.data) }.getOrNull() ?: return@run
        ctl.scope.launch { if (platform.saveFile(f.name, kotlin.io.encoding.Base64.decode(f.base64))) ctl.toast("已保存《${f.name}》") }
    }
    val hasTable = export && remember(body) { Regex("""(?m)^\s*\|?\s*:?-{3,}:?\s*\|""").containsMatchIn(body) }
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.content.isNotBlank()) SmallAction(Icons.Rounded.ContentCopy, "复制") { clipboard.setText(AnnotatedString(splitThink(m.content).first)); ctl.toast("已复制") }
        // 读出来：只给答完了的 AI 回答（念的是正文，不含思考过程；Markdown 记号引擎会去掉）
        if (platform.canPlayAudio && m.role != Role.USER && body.isNotBlank() && m.status == MsgStatus.DONE) {
            val speaking = ctl.speech.isReading(m.id)
            if (speaking && ctl.speech.preparing) {
                Text("合成中…", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                Spacer(Modifier.width(4.dp))
            }
            SmallAction(if (speaking) Icons.Rounded.Stop else Icons.Rounded.VolumeUp, if (speaking) "停止" else "读出来") { ctl.speech.toggle(m.id, body) }
        }
        if (export) SmallAction(Icons.Rounded.Description, "存成 Word") { export("docx") }
        if (hasTable) SmallAction(Icons.Rounded.TableChart, "表格存成 Excel") { export("xlsx") }
        // 自己说的话：放回输入框改一改再发（和豆包、ChatGPT 一样）
        if (m.role == Role.USER && m.content.isNotBlank()) SmallAction(Icons.Rounded.Edit, "改了重发") { ctl.composerDraft = m.convId to m.content }
        // 引用回复（2026-10-24，照微信、Slack）：这条的开头几行用「> 」引起来放进输入框；引的是 AI 的话就顺带 @ 它，让它来接
        if (m.role != Role.USER && body.isNotBlank()) SmallAction(Icons.Rounded.FormatQuote, "引用") {
            val who = ctl.backend.store.state.value.member(m.senderId)?.name
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val head = lines.take(3).joinToString("\n") { "> " + it.take(80) } + if (lines.size > 3) "\n> …" else ""
            ctl.composerDraft = m.convId to (if (who != null) "@$who " else "") + "\n" + head + "\n\n"
        }
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

/** 这台设备自己存的图（界面背景，2026-10-28 起外观各台设备自己管）：手机遥控电脑时也从手机本机读，不走中转。 */
@Composable
fun rememberLocalImage(ctl: AppController, id: String): ImageBitmap? {
    val platform = LocalPlatform.current
    val img by produceState<ImageBitmap?>(null, id) {
        value = ImageCache.get(id) ?: ctl.hub.local.fileBytes(id)?.let { platform.decodeImage(it) }?.also { ImageCache.put(id, it) }
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
internal fun AttachmentStrip(ctl: AppController, atts: List<Attachment>, alignEnd: Boolean, large: Boolean = false) {
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
        docs.forEach { a -> if (a.generated) MadeFileChip(ctl, a) else DocChip(a) }
    }
    preview?.let { ImagePreview(ctl, it) { preview = null } }
}

/** AI 做的 Word / Excel：点一下用 Word / WPS 打开，右边「另存为」。 */
@Composable
private fun MadeFileChip(ctl: AppController, a: Attachment) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun open() {
        if (busy) return
        busy = true
        scope.launch {
            // 文件做在这台设备上：直接打开存好的副本；遥控电脑时先把文件取过来
            val here = ctl.backend === ctl.hub.local && a.savedPath.isNotEmpty() && platform.openFile(a.savedPath)
            val ok = here || (ctl.backend.fileBytes(a.id)?.let { platform.openBytes(a.name, it) } == true)
            if (!ok) {
                val bytes = ctl.backend.fileBytes(a.id)
                if (bytes == null) ctl.toast("文件取不到") else if (platform.saveFile(a.name, bytes)) ctl.toast("已保存《${a.name}》")
            }
            busy = false
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Ext.c.border, RoundedCornerShape(12.dp))
            .clickable { open() }.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ext = a.name.substringAfterLast('.', "").uppercase().take(4)
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(docColor(ext)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            else Text(when (ext) { "XLSX" -> "表"; "DOCX" -> "文"; "PPTX" -> "演"; "MP4" -> "视"; else -> ext }, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        // weight(fill = false)：窄屏上文字让位，「另存为」不被挤没（10-24 phoneSettingsFit）
        Column(Modifier.weight(1f, fill = false).widthIn(max = 280.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(formatSize(a.size), a.note).filter { it.isNotBlank() }.joinToString(" · ") + " · 点一下打开",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = {
            scope.launch {
                val bytes = ctl.backend.fileBytes(a.id)
                if (bytes == null) ctl.toast("文件取不到") else if (platform.saveFile(a.name, bytes)) ctl.toast("已保存《${a.name}》")
            }
        }, Modifier.size(34.dp)) { Icon(Icons.Rounded.Download, "另存为", Modifier.size(18.dp), tint = Ext.c.subtle) }
    }
}

@Composable
internal fun DocChip(a: Attachment, onRemove: (() -> Unit)? = null) {
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

fun docColor(ext: String) = when (ext) {
    "PDF" -> Color(0xFFE5484D)
    "DOCX", "DOC" -> Color(0xFF2B6CEB)
    "XLSX", "XLS", "CSV" -> Color(0xFF16A34A)
    "PPTX", "PPT" -> Color(0xFFEA7A1E)
    "MD", "TXT" -> Color(0xFF64748B)
    else -> Color(0xFF7C3AED)
}

@Composable
internal fun ImagePreview(ctl: AppController, a: Attachment, onClose: () -> Unit) {
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

/** 输入框里待发的附件（上传中 / 传好了 / 失败了）。path 是电脑上的原路径（Code 栏用，2026-10-17）。 */
internal class PendingFile(val name: String, var att: Attachment? = null, var failed: Boolean = false, val path: String = "",
    /** 只给路径、不上传（Code 栏里电脑上的文件和文件夹，2026-10-17）。 */
    val local: Boolean = false)

/** 输入框上面那一排待发的附件：图片显示缩略图，文档显示名字，都能点叉去掉（聊天和 Code 栏共用）。 */
@Composable
internal fun PendingStrip(ctl: AppController, pending: androidx.compose.runtime.snapshots.SnapshotStateList<PendingFile>) {
    if (pending.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pending.forEach { p ->
                val att = p.att
                if (p.local) {
                    // 只给路径的（Code 栏）：名字 + 完整路径，能去掉
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AttachFile, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.widthIn(max = 260.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.path, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { pending.remove(p) }, Modifier.size(22.dp)) { Icon(Icons.Rounded.Close, "移除", Modifier.size(12.dp)) }
                    }
                } else if (att == null) {
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
}

/** short：窗口矮（横屏、键盘弹出、分屏），边距收紧、不显示底下的提示；maxText：输入框最多长多高。 */
@Composable
private fun Composer(ctl: AppController, state: AppState, conv: Conversation, running: Boolean, short: Boolean = false, maxText: androidx.compose.ui.unit.Dp = 200.dp) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    // 尺寸测试：#longdraft 先放一段很长的草稿（看矮窗口里发送键还在不在）
    var text by remember(conv.id) { mutableStateOf(TextFieldValue(if (ctl.debug("longdraft")) (1..12).joinToString("\n") { "第 $it 行草稿，写得很长的时候输入框会长高" } else "")) }
    var drawMode by remember(conv.id) { mutableStateOf(ctl.debug("draw")) }
    // 「直接做视频」：不经过 AI，直接把写的话当视频描述，附上的图 / 视频 / 文件当素材（2026-10-21）
    var videoMode by remember(conv.id) { mutableStateOf(false) }
    var showFolders by remember(conv.id) { mutableStateOf(false) }
    if (showFolders) ChatFoldersDialog(ctl, conv) { showFolders = false }
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

    // 空对话里点了建议问题：填进输入框，用户可以改了再发
    val draft = ctl.composerDraft
    LaunchedEffect(draft, conv.id) {
        val (cid, t) = draft ?: return@LaunchedEffect
        if (cid != conv.id) return@LaunchedEffect
        ctl.composerDraft = null
        text = TextFieldValue(t, TextRange(t.length))
    }

    // 从「我的文档」或别的 App（微信「用其他应用打开」）送来的文件，放进输入框，等用户写问题
    val inbox = ctl.composerInbox
    LaunchedEffect(inbox, conv.id) {
        val (cid, inc) = inbox ?: return@LaunchedEffect
        if (cid != conv.id) return@LaunchedEffect
        ctl.composerInbox = null
        inc.attachments.forEach { pending.add(PendingFile(it.name, it)) }
        // 文件夹、太大的、读不了的只有路径（2026-10-17 用户定「和 Code 一样放开」）：电脑上把路径交给 AI，它用 list_folder / read_document 去读；
        // 手机遥控电脑时手机上的路径电脑上没有，放不进来
        val (pathOnly, readable) = inc.files.partition { it.pathOnly }
        if (pathOnly.isNotEmpty()) {
            if (platform.isDesktop && !ctl.remoteMode) pathOnly.filter { it.path.isNotBlank() }.forEach { f ->
                if (pending.none { it.local && it.path.equals(f.path, ignoreCase = true) }) pending.add(PendingFile(f.name, path = f.path, local = true))
            } else ctl.toast("「${pathOnly.joinToString("、") { it.name }}」读不了内容，手机上放不进来")
        }
        readable.forEach { f ->
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

    // ———— 听写：点话筒录音，停下就发给听写模型，认出来的字填进输入框（不直接发，用户看一眼再发；和 Code 栏共用 Dictation.kt） ————
    val dictation = rememberDictation(ctl, conv.id) { t -> text = text.withSpoken(t) }
    // 开发截图用：conv:编号#record = 打开对话就是「正在录音」的样子
    LaunchedEffect(conv.id, ctl.debugDialog) {
        if (ctl.debugDialog != "record") return@LaunchedEffect
        ctl.debugDialog = ""
        dictation.start()
    }

    // AI 还在回答或画图时也能接着发：新消息另起一轮，不用等
    fun send() {
        if (uploading > 0) return
        val ids = pending.mapNotNull { it.att?.id }
        val paths = pending.filter { it.local }.map { it.path }
        if (text.text.isBlank() && ids.isEmpty() && paths.isEmpty()) return
        ctl.run(Command.SendMessage(conv.id, text.text, ids, paths = paths, drawImage = drawMode && !videoMode, makeVideo = videoMode))
        text = TextFieldValue("")
        pending.clear()
        // 「直接画图」「直接做视频」只管这一条，发完回到正常聊天
        drawMode = false
        videoMode = false
    }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = if (short) 6.dp else 14.dp, top = if (short) 2.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = CHAT_MAX_WIDTH).fillMaxWidth()) {
            // 开头打「/」补全斜杠命令（2026-10-18，和 Code 栏一样）
            val slashAll = rememberSlashCommands(ctl, conv.id)
            val slashQ = slashQuery(text)
            val slashHits = if (slashQ == null) emptyList() else slashAll.filter { it.name.startsWith(slashQ, ignoreCase = true) || slashQ in it.name }
            // 打「@」时拖进来的文件夹里的文件也列出来（2026-10-18，和 Code 栏一样）
            val fileQuery = if (conv.grantedPaths.isNotEmpty()) atQuery(text) else null
            val fileHits = rememberFileSuggestions(ctl, conv.id, fileQuery)
            // 候选列表（/ 命令、@ 文件、@ 成员）在输入框上面；窗口矮时让位：只占输入框排完剩下的高度，放不下就滑（2026-10-22）
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (slashQ != null) SlashList(slashHits) { val t = expandSlash(it, ""); text = TextFieldValue(t, TextRange(t.length)) }
                if (fileQuery != null && fileHits.isNotEmpty() && (suggestions.isEmpty() || fileQuery.length >= 2)) FileSuggestList(fileHits) { text = insertAt(text, it) }
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
            }
            GlassSurface(
                shape = RoundedCornerShape(if (com.guixing.jixunying.ui.glass.LocalGlass.current.on) com.guixing.jixunying.ui.glass.glassRadius(com.guixing.jixunying.model.GlassTarget.BAR) else 22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (drawMode || videoMode) MaterialTheme.colorScheme.secondary else Ext.c.border),
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
                    PendingStrip(ctl, pending)
                    // 写字的地方最后量：下面那一排（发送键）先排，窗口矮时它让位、里面能滑
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(min = 26.dp, max = maxText)) {
                        if (text.text.isEmpty()) Text(
                            when {
                                videoMode -> "直接做视频：描述画面和动作，比如「一只橘猫在窗台上伸懒腰，阳光洒进来」；附上的图片当首帧（两张是首尾帧），附上的视频按描述改或接长，附上的文档按内容做（按秒收费，发完回到聊天）"
                                drawMode -> "直接画图：描述画面，比如「水墨风格的江南小镇，清晨薄雾」（只画这一张，发完回到聊天）"
                                members.size > 1 -> if (platform.isDesktop) "发消息…  喊名字或 @名字 点名回答，Enter 发送，Shift+Enter 换行" else "发消息…  喊名字或 @ 点名"
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
                                    if (slashQ != null && slashHits.isNotEmpty()) { val t = expandSlash(slashHits.first(), ""); text = TextFieldValue(t, TextRange(t.length)) }
                                    else if (fileQuery != null && fileHits.isNotEmpty() && suggestions.isEmpty()) text = insertAt(text, fileHits.first())
                                    else if (suggestions.isNotEmpty()) insertMention(suggestions.first().first) else send()
                                    true
                                } else if (platform.isDesktop && e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed) && platform.clipboardHasFiles()) {
                                    // 剪贴板里是截图或复制的文件：当附件放进来；是文字就照常粘贴
                                    scope.launch {
                                        val files = platform.clipboardFiles()
                                        if (files.isEmpty()) ctl.toast("剪贴板里的东西读不出来") else ctl.composerInbox = conv.id to Incoming(files = files)
                                    }
                                    true
                                } else if (platform.isDesktop && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && text.text.isEmpty()) {
                                    // 输入框空着按 ↑：调出自己上一条发过的话（2026-10-24，照终端和 Slack 的习惯），改改再发
                                    val lastMine = ctl.backend.store.messages.value[conv.id].orEmpty().lastOrNull { it.role == Role.USER }?.content.orEmpty()
                                    if (lastMine.isNotBlank()) { text = TextFieldValue(lastMine, TextRange(lastMine.length)); true } else false
                                } else false
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 2026-10-22 用户发手机截图（发送键被挤到最右边没位置）：「必须得这么多按钮吗？能不能整理一下，合并一下」。
                        // 不常点的（图片、文件、@、直接画图、直接做视频、文件夹）收进「+」；外面只留联网、话筒、电脑上的「动手」权限，和正开着的模式标签
                        val videoOn = state.settings.videoGen.enabled && com.guixing.jixunying.model.VideoPick.candidates(state.providers).isNotEmpty()
                        // 左边这一组只占剩下的宽度，放不下就横着滑；右边的停止和发送先排、永远是完整大小。
                        // 10-22 用户：「需要兼容大多数手机的屏幕比例才行」——手机最窄 320dp、分屏窗口最窄 220dp、系统字体最大 200%（安卓规范，见 docs/参考资料.md「手机屏幕尺寸」）
                        val tools = rememberScrollState()
                        Row(Modifier.weight(1f).fadeEnd(tools).horizontalScroll(tools), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                var plus by remember { mutableStateOf(ctl.debug("plus")) }
                                ComposerIcon(Icons.Rounded.Add, "图片、文件、@ 成员、直接画图、直接做视频") { plus = true }
                                androidx.compose.material3.DropdownMenu(plus, { plus = false }) {
                                    PlusItem(Icons.Rounded.Image, "图片", "给 AI 看图") { plus = false; pick(true) }
                                    PlusItem(Icons.Rounded.AttachFile, "文件",
                                        if (platform.isDesktop) "PDF、Word、Excel、代码…；也能拖进来" else "PDF、Word、Excel、PPT、代码…") { plus = false; pick(false) }
                                    if (state.members.size > 1) PlusItem(Icons.Rounded.AlternateEmail, "@ 成员", "点名谁来答；不在这个对话里的会被拉进来") {
                                        plus = false
                                        text = TextFieldValue(text.text + (if (text.text.isEmpty() || text.text.endsWith(" ")) "@" else " @"), TextRange(Int.MAX_VALUE))
                                    }
                                    PlusItem(Icons.Rounded.Brush, "直接画图", "不经过 AI，写的话当画面描述，只画这一张", checked = drawMode) {
                                        plus = false; drawMode = !drawMode; if (drawMode) videoMode = false
                                    }
                                    if (videoOn) PlusItem(Icons.Rounded.Movie, "直接做视频", "写的话当视频描述；附图当首帧，按秒收费", checked = videoMode) {
                                        plus = false; videoMode = !videoMode; if (videoMode) drawMode = false
                                    }
                                    // 这个对话里 AI 直接改、不用问的文件夹（拖进来的、选了「不再问」的），和 Code 栏的「工作区」一样能加能移出
                                    if (platform.isDesktop && !ctl.remoteMode) PlusItem(Icons.Rounded.FolderOpen,
                                        if (conv.grantedPaths.isEmpty()) "文件夹" else "文件夹（${conv.grantedPaths.size} 个）", "AI 直接改、不用再问的文件夹") { plus = false; showFolders = true }
                                }
                            }
                            // 联网：电脑上带字；手机上窄，只留图标（开着时高亮）
                            if (platform.isDesktop) {
                                Spacer(Modifier.width(4.dp))
                                ToggleChip("联网", Icons.Rounded.Language, conv.webSearch) {
                                    ctl.run(Command.UpdateConversation(conv.copy(webSearch = !conv.webSearch)))
                                }
                                Spacer(Modifier.width(4.dp))
                            } else {
                                // 手机上三个圆按钮都有底色了，挨着不留缝会粘成一条
                                Spacer(Modifier.width(6.dp))
                                ToggleIcon(Icons.Rounded.Language, if (conv.webSearch) "联网：开着（点一下关）" else "联网：关着（点一下开）", conv.webSearch) {
                                    ctl.run(Command.UpdateConversation(conv.copy(webSearch = !conv.webSearch)), okText = if (conv.webSearch) "联网关了" else "联网开了")
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            // 听写：点一下开始录，再点一下停下，认出来的字填进输入框（不直接发）
                            if (platform.canRecord) DictationButton(dictation, "说一段话，变成文字填进输入框（最多 5 分钟，再点一下停下）")
                            // 2026-10-17 对话栏 = Chat + Cowork：AI 改文件要不要先问（电脑上才有；手机上改不了，PhoneGate 也会保留电脑上的）
                            if (platform.isDesktop && !ctl.remoteMode) {
                                Spacer(Modifier.width(4.dp))
                                // 动手（改别处的文件、删、跑命令）问不问：照 Claude 的权限菜单做成一个小菜单
                                Box {
                                    var menu by remember { mutableStateOf(false) }
                                    val label = when { conv.fileOpen -> "全部放开"; conv.fileAuto -> "自动"; else -> "动手先问我" }
                                    ToggleChip(label, Icons.Rounded.Edit, conv.fileOpen || conv.fileAuto) { menu = true }
                                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                        listOf(
                                            Triple("自动", "检查员看过没问题的直接做，拿不准的再问你", conv.copy(fileAuto = true, fileOpen = false)),
                                            Triple("动手先问我", "改别处的文件、删东西、跑命令之前都问你（拖进来的文件夹里照样直接改）", conv.copy(fileAuto = false, fileOpen = false)),
                                            Triple("全部放开", "都不问；只有危险命令问一次，改之前照样备份能撤销", conv.copy(fileAuto = false, fileOpen = true)),
                                        ).forEach { (name, desc, next) ->
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(name + if (name == label) "  ✓" else "", style = MaterialTheme.typography.bodyMedium)
                                                        Text(desc, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                                                    }
                                                },
                                                onClick = { menu = false; if (name != label) ctl.run(Command.UpdateConversation(next), okText = name) },
                                            )
                                        }
                                    }
                                }
                            }
                            // 正开着「直接画图 / 直接做视频」：外面挂个小标签，点一下退出（以前只有输入框边框变色，不显眼）
                            if (drawMode) { Spacer(Modifier.width(6.dp)); ModeChip(if (platform.isDesktop) "直接画图" else "画图", Icons.Rounded.Brush) { drawMode = false } }
                            if (videoMode) { Spacer(Modifier.width(6.dp)); ModeChip(if (platform.isDesktop) "直接做视频" else "视频", Icons.Rounded.Movie) { videoMode = false } }
                        }
                        // 有回答 / 画图在进行时，停止按钮单独放在发送键旁边，不挡着发新消息
                        if (running) {
                            // 手机上只留图标（圆的，和发送键一样大），把位置让给左边那组；电脑上照旧带字（2026-10-22）
                            if (platform.isDesktop) Row(
                                Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50))
                                    .clickable { ctl.run(Command.Stop(conv.id)) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Stop, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(3.dp))
                                Text("停止", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            } else Box(
                                Modifier.size(36.dp).clip(CircleShape).border(1.dp, Ext.c.border, CircleShape).clickable { ctl.run(Command.Stop(conv.id)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Stop, "停止", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        val canSend = (text.text.isNotBlank() || pending.any { it.att != null || it.local }) && uploading == 0
                        // 液态发送键（2026-10-22）：按下跟手形变、弹回；关了玻璃就是原来的实心圆
                        GlassRoundButton(
                            if (videoMode) Icons.Rounded.Movie else if (drawMode) Icons.Rounded.Brush else Icons.Rounded.ArrowUpward,
                            if (videoMode) "做视频" else if (drawMode) "画图" else "发送",
                            enabled = canSend,
                            color = if (drawMode || videoMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            onColor = MaterialTheme.colorScheme.onPrimary,
                            onClick = { send() },
                        )
                    }
                }
            }
            if (!short) Text(
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

/**
 * 输入框那一排的圆按钮（对话栏的「+」、话筒，Code 栏的附件、图片）。
 * 手机上大一圈、带一层圆底（2026-10-29 用户发手机截图：「这三个按钮再大 25%，语音和加号都要跟联网这个按钮一样，做一个背景图层」）：
 * 32 → 40dp、图标 19 → 24dp，底色不分开关；联网开着时另用主色（ToggleIcon）。电脑上照旧光图标（那一排是带字的胶囊，鼠标点得准）。
 */
@Composable
internal fun ComposerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val phone = !LocalPlatform.current.isDesktop
    Box(
        Modifier.size(if (phone) PhoneButton else 32.dp).clip(CircleShape)
            .then(if (phone) Modifier.background(composerFill()) else Modifier).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, Modifier.size(if (phone) PhoneGlyph else 19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val PhoneButton = 40.dp
private val PhoneGlyph = 24.dp

/** 手机上输入框圆按钮底下那层淡底（深色、浅色、开不开玻璃都看得出是个按钮，又不抢发送键）。 */
@Composable
private fun composerFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

/** 横着能滑、右边还有东西没露出来时，右边缘淡出一截，看得出后面还有（2026-10-22，输入框那一排放不下时）。 */
private fun Modifier.fadeEnd(scroll: androidx.compose.foundation.ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scroll.canScrollForward) {
            val w = 24.dp.toPx()
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - w, endX = size.width),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
        }
    }

/** 「+」菜单里的一项：图标、名字、一行说明；checked = 这个模式正开着（打个勾）。 */
@Composable
private fun PlusItem(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, desc: String, checked: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Column {
                Text(name + if (checked) "  ✓" else "", style = MaterialTheme.typography.bodyMedium)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
        },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

/** 只有图标的开关（手机上的联网）：和 ComposerIcon 一样大、一样有底；开着时底色和图标用主色。 */
@Composable
internal fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, checked: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(PhoneButton).clip(CircleShape).background(if (checked) primary.copy(alpha = 0.14f) else composerFill()).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, Modifier.size(PhoneGlyph), tint = if (checked) primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 正开着的模式（直接画图 / 直接做视频）：带叉的小标签，点一下退出。 */
@Composable
private fun ModeChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme.secondary
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClose).padding(start = 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = c)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.Close, "退出$label", Modifier.size(14.dp), tint = c)
    }
}

// ———————————————— 对话设置 ————————————————

@Composable
private fun ConversationSettingsDialog(ctl: AppController, state: AppState, conv: Conversation, onClose: () -> Unit) {
    var picked by remember { mutableStateOf(conv.memberIds.toSet()) }
    var mode by remember { mutableStateOf(conv.replyMode) }
    var search by remember { mutableStateOf(conv.webSearch) }
    var council by remember { mutableStateOf(conv.council) }
    var title by remember { mutableStateOf(conv.title) }
    // 记忆话题（记忆二期）：autoTopic = 交给记录员认
    var topic by remember { mutableStateOf(conv.memoryTopic) }
    var autoTopic by remember { mutableStateOf(!conv.memoryTopicByUser) }
    AppDialog("对话设置", onClose, width = 560.dp, actions = {
        TextButton(onClose) { Text("取消") }
        Button(enabled = picked.isNotEmpty(), onClick = {
            onClose()
            ctl.run(Command.UpdateConversation(conv.copy(
                title = title.trim().ifEmpty { conv.title },
                memberIds = state.members.map { it.id }.filter { it in picked } + conv.memberIds.filter { it in picked && state.member(it) == null },
                replyMode = mode, webSearch = search, council = council && picked.size > 1,
            )))
            val nextTopic = if (autoTopic) null else topic.trim()
            if (autoTopic != !conv.memoryTopicByUser || (!autoTopic && nextTopic != conv.memoryTopic))
                ctl.run(Command.SetConvTopic(conv.id, nextTopic), quiet = true)
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
                Spacer(Modifier.height(8.dp))
                SwitchRow("会审模式", "对大家提的问题：先各自独立回答，再匿名互评、排名次，最后由中立的主席（立场档案用的裁判模型）写最终答案，" +
                    "写清首轮分歧和少数派意见。每个问题要多调「成员数 + 1」次模型，适合重要、有争议的问题；只叫某几位时照常回答。", council) { council = it }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("联网搜索", "需要时先搜索再回答，回答里标出处", search) { search = it }
            if (conv.code == null && state.settings.memory.enabled) {
                Spacer(Modifier.height(8.dp))
                SwitchRow("记忆话题自动认", "记录员看聊天内容认出这个对话属于哪个话题，AI 回答时带上那个话题的记忆。" +
                    "现在：" + conv.memoryTopic.ifBlank { "不属于任何话题" }, autoTopic) { autoTopic = it; if (it) topic = conv.memoryTopic }
                if (!autoTopic) TopicPicker(state, topic) { topic = it }
            }
        }
    }
}

/** 对话里的查找栏（2026-10-24）：输入框 + 第几条 / 共几条 + 上一个 / 下一个 + 关掉；Enter 下一个，Esc 关掉。 */
@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, count: Int, index: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, Modifier.size(16.dp), tint = Ext.c.subtle)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("在这个对话里找", style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle)
                BasicTextField(query, onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else when (e.key) {
                            Key.Enter -> { if (e.isShiftPressed) onPrev() else onNext(); true }
                            Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    })
            }
            if (query.isNotBlank()) Text(if (count == 0) "没有" else "${index + 1} / $count", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
        }
        IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "上一个") }
        IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowDown, "下一个") }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关掉查找") }
    }
}
