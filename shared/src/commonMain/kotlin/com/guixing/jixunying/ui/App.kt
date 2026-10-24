package com.guixing.jixunying.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import com.guixing.jixunying.ui.glass.drawBackground
import com.guixing.jixunying.ui.glass.drawWallpaper
import androidx.compose.ui.graphics.luminance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 全局提示条。弹窗打开时，弹窗里也放一个，免得提示被弹窗挡住。 */
val LocalSnackbar = androidx.compose.runtime.staticCompositionLocalOf { SnackbarHostState() }

enum class SettingsTab(val title: String) {
    // 账号放第一项（10-24 用户：「全世界哪个 APP 账号放在里边？」——以前塞在「个性化」里找不到）
    ACCOUNT("账号"), PROVIDERS("模型服务"), MEMBERS("AI 成员"), PROFILE("个性化"), MEMORY("记忆"), SEMANTIC("按意思搜"), SEARCH("联网搜索"), IMAGE("画图和视频"), SPEECH("语音"),
    OFFICE("Word / Excel / PPT"),
    CODE("Code 栏"),
    WEIXIN("微信"), DEVICES("联机"), APPEARANCE("外观"), ABOUT("关于"),
}

/** 主区域除了对话和设置以外的页面。 */
enum class MainPage { DOCS, STANCES, COSTS }

/**
 * 侧栏顶上的两栏（docs/Code栏设计说明.md 第 1、11 节，2026-10-12）：
 * 「对话」是现在的聊天，「Code」是对着一个项目文件夹写代码的会话。两边各列各的，互不掺和。
 */
enum class SidebarTab(val label: String) { CHAT("对话"), CODE("Code") }

/** 要放进某个对话输入框的文件：别的 App 发来的（还没上传），或者文档库里转成的附件。 */
class Incoming(val files: List<PickedFile> = emptyList(), val attachments: List<com.guixing.jixunying.model.Attachment> = emptyList()) {
    val names: List<String> get() = files.map { it.name } + attachments.map { it.name }
}

/** 界面级状态：当前操作哪台设备、打开哪个对话、是否在设置页。 */
class AppController(
    val hub: Hub,
    val backend: Backend,
    val scope: CoroutineScope,
    val snackbar: SnackbarHostState,
    /** 这台设备的能力（放音、录音……），读出来和听写要用。 */
    val platform: Platform,
) {
    /** 读出来（「读出来」按钮、回答完自动读）。 */
    val speech = SpeechPlayer(this)

    /** Code 栏做完 / 等你批准时的系统通知（第二期，2026-10-13）。 */
    val codeNotice = CodeNotifier(this)

    var currentConvId by mutableStateOf<String?>(null)
    var page by mutableStateOf<MainPage?>(null)
    /** 等用户选「发到哪个对话」的文件。 */
    var incoming by mutableStateOf<Incoming?>(null)
    /** 已经选好对话、等那个对话的输入框收下的文件。 */
    var composerInbox by mutableStateOf<Pair<String, Incoming>?>(null)
    /** 要填进某个对话输入框的文字（空对话里点了建议问题）。 */
    var composerDraft by mutableStateOf<Pair<String, String>?>(null)

    fun deliverIncoming(convId: String) {
        val inc = incoming ?: return
        incoming = null
        composerInbox = convId to inc
        openConversation(convId)
    }
    /** 打开对话后要滚到的消息（从搜索结果点进来时）。 */
    var focusMessageId by mutableStateOf<String?>(null)
    var settingsTab by mutableStateOf<SettingsTab?>(null)
    var showNewChat by mutableStateOf(false)
    var debugDialog = ""
    /** 截图、尺寸测试用：#后面能用 + 叠几个开关（比如 conv:c14#longdraft+draw）。 */
    fun debug(flag: String) = flag in debugDialog.split('+')

    /** 手机正在遥控电脑。 */
    val remoteMode: Boolean get() = backend !== hub.local

    /**
     * 执行指令。回调先跑（按钮马上恢复），提示条另外显示；quiet 时不弹提示（调用方自己在界面上显示结果）。
     */
    fun run(cmd: Command, okText: String? = null, quiet: Boolean = false, then: (CommandResult) -> Unit = {}) {
        scope.launch {
            val r = backend.call(cmd)
            then(r)
            if (quiet) return@launch
            if (!r.ok) snackbar.showSnackbar(r.message.ifBlank { "操作失败" }, duration = SnackbarDuration.Long)
            else if (okText != null || r.message.isNotBlank()) snackbar.showSnackbar(okText ?: r.message)
        }
    }

    fun toast(text: String) { scope.launch { snackbar.showSnackbar(text) } }

    private val settingsLock = kotlinx.coroutines.sync.Mutex()

    /**
     * 改设置：在「最新」的设置上改一处、马上保存（设置页都不用再点「保存」）。
     * 排队执行，每次都拿最新状态，手机遥控电脑时连着改两处也不会互相覆盖。
     */
    fun updateSettings(transform: (com.guixing.jixunying.model.Settings) -> com.guixing.jixunying.model.Settings) {
        scope.launch {
            settingsLock.lock()
            try {
                val r = backend.call(Command.SaveSettings(transform(backend.store.state.value.settings).copy(schema = com.guixing.jixunying.model.Settings.SCHEMA)))
                if (!r.ok) snackbar.showSnackbar(r.message.ifBlank { "保存失败" }, duration = SnackbarDuration.Long)
            } finally {
                settingsLock.unlock()
            }
        }
    }

    /**
     * 改「这台设备自己的」设置（现在只有 设置 → 语音：读出来和听写用这台设备自己的服务商和 Key，
     * 手机遥控电脑时也是用手机自己的，不走中转，见 docs/向量和语音设计说明.md 第 5 节）。
     * 电脑上本机就是引擎本身，和 updateSettings 一个效果。
     */
    fun updateLocalSettings(transform: (com.guixing.jixunying.model.Settings) -> com.guixing.jixunying.model.Settings) {
        scope.launch {
            settingsLock.lock()
            try {
                val local = hub.local
                val r = local.call(Command.SaveSettings(transform(local.store.state.value.settings).copy(schema = com.guixing.jixunying.model.Settings.SCHEMA)))
                if (!r.ok) snackbar.showSnackbar(r.message.ifBlank { "保存失败" }, duration = SnackbarDuration.Long)
            } finally {
                settingsLock.unlock()
            }
        }
    }

    /**
     * 改设置、改完接着做一件事（按意思搜换了服务商 / 模型要「重新算一遍」）。
     * 设置得先落地再重算：不然重算的时候引擎还按旧的那家挑，又拿旧那家跑一遍、白花一次钱（1.7.0）。
     */
    fun updateSettingsThen(
        transform: (com.guixing.jixunying.model.Settings) -> com.guixing.jixunying.model.Settings,
        then: suspend () -> Unit,
    ) {
        scope.launch {
            settingsLock.lock()
            try {
                val r = backend.call(Command.SaveSettings(transform(backend.store.state.value.settings).copy(schema = com.guixing.jixunying.model.Settings.SCHEMA)))
                if (!r.ok) snackbar.showSnackbar(r.message.ifBlank { "保存失败" }, duration = SnackbarDuration.Long) else then()
            } finally {
                settingsLock.unlock()
            }
        }
    }

    fun updateProfile(transform: (com.guixing.jixunying.model.UserProfile) -> com.guixing.jixunying.model.UserProfile) {
        scope.launch {
            settingsLock.lock()
            try {
                val r = backend.call(Command.SaveProfile(transform(backend.store.state.value.profile)))
                if (!r.ok) snackbar.showSnackbar(r.message.ifBlank { "保存失败" }, duration = SnackbarDuration.Long)
            } finally {
                settingsLock.unlock()
            }
        }
    }

    /** 手机上：设置首页（列表）还是某一页。电脑上左边一直有列表，用不到。 */
    var settingsHome by mutableStateOf(false)

    fun openConversation(id: String) {
        currentConvId = id
        settingsTab = null
        page = null
        if (!backend.store.hasMessages(id)) run(Command.LoadMessages(id))
    }

    /** 侧栏现在看的是「对话」还是「Code」。 */
    var sidebarTab by mutableStateOf(SidebarTab.CHAT)
    /** 新建 Code 会话的对话框开着。 */
    var showNewCode by mutableStateOf(false)

    /** Code 会话的终端面板开着没有（2026-10-18；会话之间共用这个开关）。 */
    var codeTerminalOpen by mutableStateOf(false)

    /**
     * 新建 Code 会话（2026-10-17：电脑上不弹窗，直接开一个——文件夹、模型用上一次的，开起来以后在标题下面的「谁来干活」、「更多 → 工作区」里随时换）。
     * 手机遥控电脑时照旧弹选择框（只能从电脑上已有的文件夹里挑）。开不了（比如还没有能调工具的模型）也弹选择框，里面会写原因。
     */
    fun newCode(isDesktop: Boolean) {
        if (!isDesktop || remoteMode) { showNewCode = true; return }
        run(Command.CodeCreate("", "", ""), quiet = true) { r ->
            if (r.ok) openConversation(r.data) else { toast(r.message.ifBlank { "开不了新会话" }); showNewCode = true }
        }
    }

    /** 不指定哪一页：手机上先进设置首页（列表），电脑上默认第一页。 */
    fun openSettings(tab: SettingsTab? = null) {
        settingsTab = tab ?: SettingsTab.PROVIDERS
        settingsHome = tab == null
        page = null
    }

    fun openPage(p: MainPage) { page = p; settingsTab = null }
}

@Composable
fun App(hub: Hub, platform: Platform, debugStart: String? = null) {
    val useRemote by hub.useRemote.collectAsState()
    val remote by hub.remote.collectAsState()
    val backend: Backend = remote?.takeIf { useRemote } ?: hub.local
    val state by backend.store.state.collectAsState()
    // 点了 Code 栏的通知（安卓）或者托盘图标（电脑）要打开哪个会话。放在 key(backend) 外面：
    // 要打开的是电脑上的会话而现在看的是本机时，切过去以后这个还得留着（第二期 2026-10-13）
    var pendingConv by remember { mutableStateOf<String?>(null) }
    AppTheme(state.settings.darkMode) {
        val snackbar = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalPlatform provides platform, LocalSnackbar provides snackbar) {
            val scope = rememberCoroutineScope()
            // 切换本机 / 电脑时整个界面换一套状态
            key(backend) {
                val ctl = remember(backend) {
                    AppController(hub, backend, scope, snackbar, platform).also { c ->
                        // 开发截图用：-Djxy.start=settings:MEMBERS 之类
                        debugStart?.let { s ->
                            if (s.startsWith("settings")) c.settingsTab = SettingsTab.entries.firstOrNull { it.name == s.substringAfter(':', "").substringBefore('#') } ?: SettingsTab.PROVIDERS
                            if (s == "newchat") c.showNewChat = true
                            if (s == "docs") c.page = MainPage.DOCS
                            if (s == "stances") c.page = MainPage.STANCES
                            if (s == "costs") c.page = MainPage.COSTS
                            if (s == "settingshome") c.openSettings()
                            if (s == "newcode") { c.sidebarTab = SidebarTab.CODE; c.showNewCode = true }
                            // code / code:会话编号 / code:会话编号#diff = 侧栏切到 Code，必要时打开这个会话（#diff 再打开改动查看器）
                            if (s == "code" || s.startsWith("code:")) {
                                c.sidebarTab = SidebarTab.CODE
                                s.removePrefix("code:").substringBefore('#').takeIf { it.isNotBlank() }?.let { c.openConversation(it) }
                            }
                            if (s.startsWith("conv:")) {
                                // conv:对话编号@消息编号 = 像从搜索结果点进来一样，跳到那条消息
                                c.currentConvId = s.removePrefix("conv:").substringBefore('#').substringBefore('@')
                                c.focusMessageId = s.substringBefore('#').substringAfter('@', "").ifEmpty { null }
                            }
                            c.debugDialog = s.substringAfter('#', "")
                        }
                    }
                }
                LaunchedEffect(backend) {
                    backend.store.notices.collect { snackbar.showSnackbar(it.text, duration = if (it.error) SnackbarDuration.Long else SnackbarDuration.Short) }
                }
                // 切「本机 / 电脑」时引擎换了：正在读的那条停下来，别再往下放
                DisposableEffect(backend) { onDispose { ctl.speech.stop() } }
                // 开发截图用：conv:编号#speak = 打开会话后立刻读最后一条回答（看「停止」「合成中…」的样子）
                LaunchedEffect(backend, ctl.debugDialog) {
                    if (ctl.debugDialog != "speak") return@LaunchedEffect
                    ctl.debugDialog = ""
                    val id = ctl.currentConvId ?: return@LaunchedEffect
                    val msgs = backend.store.messages
                        .first { (it[id]?.any { m -> m.role == Role.AI && m.content.isNotBlank() }) == true }[id].orEmpty()
                    val m = msgs.lastOrNull { it.role == Role.AI && it.content.isNotBlank() && it.status == MsgStatus.DONE } ?: return@LaunchedEffect
                    ctl.speech.read(m.id, splitThink(m.content).first)
                }
                // Code 栏的通知（第二期）：某一步开始等批准、或者一条回答做完了，应用不在前台（或没打开那个会话）时弹一条
                LaunchedEffect(backend) {
                    backend.store.messages.collect { ctl.codeNotice.onMessages(it) }
                }
                // 点了通知（安卓）或者托盘图标（电脑）：切到那个 Code 会话
                val convRequest by platform.openConvRequest.collectAsState()
                LaunchedEffect(convRequest) {
                    convRequest?.let { pendingConv = it; platform.clearOpenConvRequest() }
                }
                LaunchedEffect(pendingConv, state.conversations.isEmpty(), backend) {
                    val id = pendingConv ?: return@LaunchedEffect
                    if (state.conversation(id) != null) {
                        pendingConv = null
                        ctl.sidebarTab = SidebarTab.CODE
                        ctl.openConversation(id)
                    } else if (state.conversations.isEmpty()) {
                        // 状态还没来（刚打开应用、刚连上电脑）：等它一下，别当没有
                    } else if (ctl.hub.remote.value != null && !ctl.remoteMode) {
                        // 本机没有这个会话：通知说的是电脑上的 Code 会话，切到电脑再看
                        ctl.hub.useRemote.value = true
                    } else {
                        pendingConv = null
                    }
                }
                // 别的 App 发来的文件（安卓：微信里「用其他应用打开」）：问发到哪个对话
                val incomingFiles by platform.incomingFiles.collectAsState()
                LaunchedEffect(incomingFiles) {
                    if (incomingFiles.isNotEmpty()) {
                        ctl.incoming = Incoming(files = incomingFiles)
                        platform.clearIncoming()
                    }
                }
                // 默认打开最近的对话。跳过 Code 会话（主会话审查 10-12 补）：新建的 Code 会话排在最前面，
                // 启动时侧栏在「对话」那一栏，主区域却打开 Code 会话就对不上了
                LaunchedEffect(backend, state.conversations.isNotEmpty()) {
                    if (ctl.currentConvId == null) state.conversations.firstOrNull { !it.isCode }?.let {
                        ctl.currentConvId = it.id
                        if (!backend.store.hasMessages(it.id)) ctl.run(Command.LoadMessages(it.id))
                    }
                }
                // 液态玻璃（2026-10-22，docs/界面换装说明.md）：壁纸在最底下、和界面是兄弟（不能是界面的上层，安卓上会原生崩溃），
                // 界面里的玻璃面经 LocalGlass 采样它；设置里关掉就是原来的纯色
                val glassOn = state.settings.glass
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val wallpaper = com.kashif_e.backdrop.backdrops.rememberLayerBackdrop()
                // 界面背景（2026-10-24）：默认 = 开着玻璃是那几团柔光、关着是主题底色；纯色 / 渐变 / 自己的图片照 设置 → 外观 → 背景
                val bg = state.settings.background
                val bgImage = if (bg.mode == com.guixing.jixunying.model.BgMode.IMAGE && bg.imageId.isNotBlank()) rememberImage(ctl, bg.imageId) else null
                val baseColor = MaterialTheme.colorScheme.background
                val paint: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = remember(bg, bgImage, dark, glassOn, baseColor) {
                    { drawBackground(bg, bgImage) { if (glassOn) drawWallpaper(dark) else drawRect(baseColor) } }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (glassOn || bg.mode != com.guixing.jixunying.model.BgMode.DEFAULT)
                        com.guixing.jixunying.ui.glass.AppWallpaper(if (glassOn) wallpaper else null, bg, bgImage,
                            { if (glassOn) drawWallpaper(dark) else drawRect(baseColor) }, Modifier.matchParentSize())
                    CompositionLocalProvider(com.guixing.jixunying.ui.glass.LocalGlass provides
                        com.guixing.jixunying.ui.glass.GlassEnv(if (glassOn) wallpaper else null, glassOn, dark, state.settings.glassTuning, paint)) {
                        MainLayout(ctl)
                    }
                    // 开发截图用：-Djxy.start=…#toast 打开就弹一条提示，看它挡不挡东西
                    LaunchedEffect(Unit) { if (ctl.debugDialog == "toast") snackbar.showSnackbar("改文件先问我", duration = SnackbarDuration.Indefinite) }
                    // 提示条（2026-10-17 用户截图：「很挡视野」）：以前在底下、又宽又大，正好压住输入框；
                    // 改成顶上居中的小胶囊，窄、字小，几秒自己消失；带按钮的（比如「去设置」）照样能点
                    // 电脑上顶栏中间是空的，放那儿；手机顶栏放满了标题和按钮，放到顶栏下面
                    val toastTop = if (LocalPlatform.current.isDesktop) 10.dp else 66.dp
                    SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = toastTop, start = 16.dp, end = 16.dp)) { data ->
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                            shadowElevation = 4.dp,
                            modifier = Modifier.widthIn(max = 460.dp),
                        ) {
                            androidx.compose.foundation.layout.Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Text(data.visuals.message, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                data.visuals.actionLabel?.let { label ->
                                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                                    androidx.compose.material3.Text(label, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.inversePrimary,
                                        modifier = Modifier.clickable { data.performAction() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainLayout(ctl: AppController) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 860.dp
        if (wide) {
            // 侧栏能拖宽拖窄、能收起（10-24 用户：「可以变宽，变窄，可以收起那种」）：右边缘拖动，200～420dp，记在本机；
            // 收起后只剩一条窄边，上面一个展开键
            val platform = LocalPlatform.current
            var sideW by remember { mutableStateOf((platform.getPref("sidebar.width")?.toFloatOrNull() ?: 272f).coerceIn(200f, 420f)) }
            var collapsed by remember { mutableStateOf(platform.getPref("sidebar.collapsed") == "1") }
            val density = androidx.compose.ui.platform.LocalDensity.current
            Row(Modifier.fillMaxSize()) {
                if (collapsed) {
                    Column(Modifier.width(44.dp).fillMaxHeight().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { collapsed = false; platform.setPref("sidebar.collapsed", null) }) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "展开侧栏")
                        }
                    }
                } else {
                    Box(Modifier.width(sideW.dp).fillMaxHeight()) {
                        Sidebar(ctl, Modifier.fillMaxSize(), onNavigate = {})
                        // 收起键：侧栏右上角
                        IconButton(onClick = { collapsed = true; platform.setPref("sidebar.collapsed", "1") },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 2.dp).size(28.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "收起侧栏", Modifier.size(18.dp), tint = Ext.c.subtle)
                        }
                    }
                    // 拖动条：鼠标放上去变成左右箭头，按住拖
                    Box(Modifier.width(6.dp).fillMaxHeight()
                        .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                        .draggable(rememberDraggableState { d -> sideW = (sideW + with(density) { d.toDp().value }).coerceIn(200f, 420f) },
                            Orientation.Horizontal, onDragStopped = { platform.setPref("sidebar.width", sideW.toString()) }))
                }
                // 液态玻璃时侧栏是浮着的一块，不要分隔线
                if (!com.guixing.jixunying.ui.glass.LocalGlass.current.on) VerticalDivider(color = Ext.c.border)
                Box(Modifier.weight(1f).fillMaxHeight()) { MainContent(ctl, wide = true, openDrawer = {}) }
            }
        } else {
            // 开发截图用：起始参数带 #drawer 就让手机上的侧栏一打开就展开（看侧栏改版的样子）
            val drawer = rememberDrawerState(if (ctl.debugDialog == "drawer") DrawerValue.Open else DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            // 返回键：先关侧栏；在设置的某一页就回设置首页；再关设置 / 文档页；最后才交给系统（退出）
            LocalPlatform.current.BackHandler(drawer.isOpen || ctl.settingsTab != null || ctl.page != null) {
                when {
                    drawer.isOpen -> scope.launch { drawer.close() }
                    ctl.settingsTab != null && !ctl.settingsHome -> ctl.settingsHome = true
                    else -> { ctl.settingsTab = null; ctl.page = null }
                }
            }
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    // 最多占窗口的 88%：分屏最窄 220dp 时写死 300dp 会整个出界（10-24 phoneSettingsFit 量出来的）
                    ModalDrawerSheet(Modifier.width(minOf(300.dp, maxWidth * 0.88f)), drawerContainerColor = if (com.guixing.jixunying.ui.glass.LocalGlass.current.on) androidx.compose.ui.graphics.Color.Transparent else Ext.c.sidebar) {
                        Sidebar(ctl, Modifier.fillMaxSize(), onNavigate = { scope.launch { drawer.close() } })
                    }
                },
            ) {
                MainContent(ctl, wide = false, openDrawer = { scope.launch { drawer.open() } })
            }
        }
    }
}

@Composable
private fun MainContent(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val tab = ctl.settingsTab
    val state by ctl.backend.store.state.collectAsState()
    // 手机在「本机」时没有 Code 栏（2026-10-23 用户：「手机为什么要留 code 页面，似乎没有用」）：本机跑不了，
    // 侧栏只剩「对话」；从电脑切回本机时正停在 Code 栏的，回到对话
    val noLocalCode = !LocalPlatform.current.isDesktop && !ctl.remoteMode
    LaunchedEffect(noLocalCode) { if (noLocalCode && ctl.sidebarTab == SidebarTab.CODE) ctl.sidebarTab = SidebarTab.CHAT }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        if (ctl.remoteMode) RemoteBanner(ctl)
        Box(Modifier.weight(1f)) {
            when {
                tab != null -> SettingsScreen(ctl, tab, wide, openDrawer)
                ctl.page == MainPage.DOCS -> DocsScreen(ctl, wide, openDrawer)
                ctl.page == MainPage.STANCES -> StancesScreen(ctl, wide, openDrawer)
                ctl.page == MainPage.COSTS -> CostsScreen(ctl, wide, openDrawer)
                state.providers.isEmpty() || (state.members.isEmpty() && ctl.sidebarTab != SidebarTab.CODE) -> WelcomeScreen(ctl, wide, openDrawer)
                // Code 会话打开时主区域是 CodeScreen（不是聊天），不管侧栏在哪一栏
                ctl.currentConvId?.let { state.conversation(it) }?.isCode == true -> CodeScreen(ctl, ctl.currentConvId!!, wide, openDrawer)
                ctl.currentConvId != null && state.conversation(ctl.currentConvId!!) != null ->
                    if (ctl.sidebarTab == SidebarTab.CODE) CodeEmptyScreen(ctl, wide, openDrawer) else ChatScreen(ctl, ctl.currentConvId!!, wide, openDrawer)
                ctl.sidebarTab == SidebarTab.CODE -> CodeEmptyScreen(ctl, wide, openDrawer)
                else -> EmptyChatScreen(ctl, wide, openDrawer)
            }
        }
    }
    if (ctl.showNewChat) NewChatDialog(ctl)
    if (ctl.showNewCode) NewCodeDialog(ctl)
    ctl.incoming?.let { SendToDialog(ctl, it) }
}
