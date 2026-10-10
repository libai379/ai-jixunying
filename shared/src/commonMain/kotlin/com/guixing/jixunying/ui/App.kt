package com.guixing.jixunying.ui

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 全局提示条。弹窗打开时，弹窗里也放一个，免得提示被弹窗挡住。 */
val LocalSnackbar = androidx.compose.runtime.staticCompositionLocalOf { SnackbarHostState() }

enum class SettingsTab(val title: String) {
    PROVIDERS("模型服务"), MEMBERS("AI 成员"), PROFILE("我的资料"), MEMORY("记忆"), SEARCH("联网搜索"), IMAGE("画图"),
    WEIXIN("微信"), DEVICES("联机"), APPEARANCE("外观"), ABOUT("关于"),
}

/** 主区域除了对话和设置以外的页面。 */
enum class MainPage { DOCS, STANCES, COSTS }

/** 要放进某个对话输入框的文件：别的 App 发来的（还没上传），或者文档库里转成的附件。 */
class Incoming(val files: List<PickedFile> = emptyList(), val attachments: List<com.guixing.jixunying.model.Attachment> = emptyList()) {
    val names: List<String> get() = files.map { it.name } + attachments.map { it.name }
}

/** 界面级状态：当前操作哪台设备、打开哪个对话、是否在设置页。 */
class AppController(val hub: Hub, val backend: Backend, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
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
    AppTheme(state.settings.darkMode) {
        val snackbar = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalPlatform provides platform, LocalSnackbar provides snackbar) {
            val scope = rememberCoroutineScope()
            // 切换本机 / 电脑时整个界面换一套状态
            key(backend) {
                val ctl = remember(backend) {
                    AppController(hub, backend, scope, snackbar).also { c ->
                        // 开发截图用：-Djxy.start=settings:MEMBERS 之类
                        debugStart?.let { s ->
                            if (s.startsWith("settings")) c.settingsTab = SettingsTab.entries.firstOrNull { it.name == s.substringAfter(':', "").substringBefore('#') } ?: SettingsTab.PROVIDERS
                            if (s == "newchat") c.showNewChat = true
                            if (s == "docs") c.page = MainPage.DOCS
                            if (s == "stances") c.page = MainPage.STANCES
                            if (s == "costs") c.page = MainPage.COSTS
                            if (s == "settingshome") c.openSettings()
                            if (s.startsWith("conv:")) c.currentConvId = s.removePrefix("conv:").substringBefore('#')
                            c.debugDialog = s.substringAfter('#', "")
                        }
                    }
                }
                LaunchedEffect(backend) {
                    backend.store.notices.collect { snackbar.showSnackbar(it.text, duration = if (it.error) SnackbarDuration.Long else SnackbarDuration.Short) }
                }
                // 别的 App 发来的文件（安卓：微信里「用其他应用打开」）：问发到哪个对话
                val incomingFiles by platform.incomingFiles.collectAsState()
                LaunchedEffect(incomingFiles) {
                    if (incomingFiles.isNotEmpty()) {
                        ctl.incoming = Incoming(files = incomingFiles)
                        platform.clearIncoming()
                    }
                }
                // 默认打开最近的对话
                LaunchedEffect(backend, state.conversations.isNotEmpty()) {
                    if (ctl.currentConvId == null) state.conversations.firstOrNull()?.let {
                        ctl.currentConvId = it.id
                        if (!backend.store.hasMessages(it.id)) ctl.run(Command.LoadMessages(it.id))
                    }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    MainLayout(ctl)
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 80.dp))
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
            Row(Modifier.fillMaxSize()) {
                Sidebar(ctl, Modifier.width(272.dp).fillMaxHeight(), onNavigate = {})
                VerticalDivider(color = Ext.c.border)
                Box(Modifier.weight(1f).fillMaxHeight()) { MainContent(ctl, wide = true, openDrawer = {}) }
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
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
                    ModalDrawerSheet(Modifier.width(300.dp), drawerContainerColor = Ext.c.sidebar) {
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
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        if (ctl.remoteMode) RemoteBanner(ctl)
        Box(Modifier.weight(1f)) {
            when {
                tab != null -> SettingsScreen(ctl, tab, wide, openDrawer)
                ctl.page == MainPage.DOCS -> DocsScreen(ctl, wide, openDrawer)
                ctl.page == MainPage.STANCES -> StancesScreen(ctl, wide, openDrawer)
                ctl.page == MainPage.COSTS -> CostsScreen(ctl, wide, openDrawer)
                state.providers.isEmpty() || state.members.isEmpty() -> WelcomeScreen(ctl, wide, openDrawer)
                ctl.currentConvId != null && state.conversation(ctl.currentConvId!!) != null -> ChatScreen(ctl, ctl.currentConvId!!, wide, openDrawer)
                else -> EmptyChatScreen(ctl, wide, openDrawer)
            }
        }
    }
    if (ctl.showNewChat) NewChatDialog(ctl)
    ctl.incoming?.let { SendToDialog(ctl, it) }
}
