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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SettingsTab(val title: String) {
    PROVIDERS("模型服务"), MEMBERS("AI 成员"), PROFILE("我的资料"), SEARCH("联网搜索"), IMAGE("画图"),
    DEVICES("手机连接"), APPEARANCE("外观"), ABOUT("关于"),
}

/** 界面级状态：当前打开哪个对话、是否在设置页。 */
class AppController(val backend: Backend, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    var currentConvId by mutableStateOf<String?>(null)
    var settingsTab by mutableStateOf<SettingsTab?>(null)
    var showNewChat by mutableStateOf(false)
    var debugDialog = ""

    fun run(cmd: Command, okText: String? = null, then: (CommandResult) -> Unit = {}) {
        scope.launch {
            val r = backend.call(cmd)
            if (!r.ok) snackbar.showSnackbar(r.message.ifBlank { "操作失败" }, duration = SnackbarDuration.Long)
            else if (okText != null || r.message.isNotBlank()) snackbar.showSnackbar(okText ?: r.message)
            then(r)
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
fun App(backend: Backend, platform: Platform, debugStart: String? = null) {
    val state by backend.store.state.collectAsState()
    AppTheme(state.settings.darkMode) {
        CompositionLocalProvider(LocalPlatform provides platform) {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val ctl = remember(backend) {
                AppController(backend, scope, snackbar).also { c ->
                    // 开发截图用：-Djxy.start=settings:MEMBERS 之类
                    debugStart?.let { s ->
                        if (s.startsWith("settings")) c.settingsTab = SettingsTab.entries.firstOrNull { it.name == s.substringAfter(':', "") } ?: SettingsTab.PROVIDERS
                        if (s == "newchat") c.showNewChat = true
                        c.debugDialog = s.substringAfter('#', "")
                    }
                }
            }
            val conn by backend.conn.collectAsState()

            LaunchedEffect(backend) {
                backend.store.notices.collect { snackbar.showSnackbar(it.text, duration = if (it.error) SnackbarDuration.Long else SnackbarDuration.Short) }
            }
            // 默认打开最近的对话
            LaunchedEffect(state.conversations.isNotEmpty()) {
                if (ctl.currentConvId == null) state.conversations.firstOrNull()?.let {
                    ctl.currentConvId = it.id
                    if (!backend.store.hasMessages(it.id)) ctl.run(Command.LoadMessages(it.id))
                }
            }

            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val remote = backend as? RemoteBackend
                if (remote != null && (conn is ConnState.NotPaired || (conn is ConnState.Failed && state.providers.isEmpty() && state.members.isEmpty()))) {
                    ConnectScreen(remote, ctl)
                } else {
                    MainLayout(ctl)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 80.dp))
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
    when {
        tab != null -> SettingsScreen(ctl, tab, wide, openDrawer)
        state.providers.isEmpty() || state.members.isEmpty() -> WelcomeScreen(ctl, wide, openDrawer)
        ctl.currentConvId != null && state.conversation(ctl.currentConvId!!) != null -> ChatScreen(ctl, ctl.currentConvId!!, wide, openDrawer)
        else -> EmptyChatScreen(ctl, wide, openDrawer)
    }
    if (ctl.showNewChat) NewChatDialog(ctl)
}
