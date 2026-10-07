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
    PROVIDERS("模型服务"), MEMBERS("AI 成员"), PROFILE("我的资料"), SEARCH("联网搜索"), IMAGE("画图"),
    DEVICES("联机"), APPEARANCE("外观"), ABOUT("关于"),
}

/** 界面级状态：当前操作哪台设备、打开哪个对话、是否在设置页。 */
class AppController(val hub: Hub, val backend: Backend, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    var currentConvId by mutableStateOf<String?>(null)
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

    fun openConversation(id: String) {
        currentConvId = id
        settingsTab = null
        if (!backend.store.hasMessages(id)) run(Command.LoadMessages(id))
    }

    fun openSettings(tab: SettingsTab = SettingsTab.PROVIDERS) { settingsTab = tab }
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
                            c.debugDialog = s.substringAfter('#', "")
                        }
                    }
                }
                LaunchedEffect(backend) {
                    backend.store.notices.collect { snackbar.showSnackbar(it.text, duration = if (it.error) SnackbarDuration.Long else SnackbarDuration.Short) }
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
                state.providers.isEmpty() || state.members.isEmpty() -> WelcomeScreen(ctl, wide, openDrawer)
                ctl.currentConvId != null && state.conversation(ctl.currentConvId!!) != null -> ChatScreen(ctl, ctl.currentConvId!!, wide, openDrawer)
                else -> EmptyChatScreen(ctl, wide, openDrawer)
            }
        }
    }
    if (ctl.showNewChat) NewChatDialog(ctl)
}
