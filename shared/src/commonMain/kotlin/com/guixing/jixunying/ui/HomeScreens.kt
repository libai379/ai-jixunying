package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.Command
import kotlinx.coroutines.launch

@Composable
private fun MobileMenu(wide: Boolean, openDrawer: () -> Unit) {
    if (!wide) Row(Modifier.fillMaxWidth().padding(4.dp)) { IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") } }
}

@Composable
fun WelcomeScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        MobileMenu(wide, openDrawer)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (wide) 60.dp else 10.dp))
            BrandMark(64)
            Spacer(Modifier.height(16.dp))
            Text("欢迎使用 AI集训营", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp))
            Spacer(Modifier.height(6.dp))
            Text("把国内外的大模型拉进一个应用：单聊像豆包，群聊能互相 @、互相纠错。\n联网搜索、看图读文档、画图都有。数据只存在你自己的设备上。",
                style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StepCard(1, "添加模型服务", "选 DeepSeek、智谱、Kimi、通义、豆包、OpenAI、Claude… 填上 API Key", state.providers.isNotEmpty()) {
                    // 还没有服务商时直接弹出「添加服务商」，不用再找按钮
                    if (state.providers.isEmpty()) ctl.debugDialog = "add"
                    ctl.openSettings(SettingsTab.PROVIDERS)
                }
                StepCard(2, "创建 AI 成员", "给模型起个名字、定个位，比如「老钱：数据分析」「杠精：专门挑错」", state.members.isNotEmpty()) {
                    ctl.openSettings(SettingsTab.MEMBERS)
                }
                StepCard(3, "开始聊天", "单聊或拉群都行", false) {
                    if (state.members.isNotEmpty()) ctl.showNewChat = true else ctl.openSettings(SettingsTab.MEMBERS)
                }
                val platform = LocalPlatform.current
                if (platform.isDesktop) {
                    StepCard(4, "（可选）手机联机", "手机扫一下二维码，在哪儿都能用手机遥控这台电脑", state.devices.isNotEmpty()) {
                        ctl.openSettings(SettingsTab.DEVICES)
                    }
                    StepCard(5, "（可选）绑定微信", "在手机微信里给助理发消息，这台电脑上的 AI 回答", state.weixin.bound) {
                        ctl.openSettings(SettingsTab.WEIXIN)
                    }
                } else if (!ctl.remoteMode) StepCard(0, "已经在电脑上配好了？", "扫电脑上的二维码配对，再一键把电脑上的模型服务和成员同步到手机", ctl.hub.remote.value != null) {
                    ctl.openSettings(SettingsTab.DEVICES)
                }
            }
        }
    }
}

@Composable
private fun StepCard(n: Int, title: String, desc: String, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Ext.c.border, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (done) Ext.c.success.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.CheckCircle, null, tint = Ext.c.success)
            else if (n == 0) Icon(Icons.Rounded.Computer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            else Text("$n", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        Text("›", fontSize = 22.sp, color = Ext.c.subtle)
    }
}

/**
 * 新对话首页（2026-10-28 用户：「每次打开这个软件，它是一个新对话、新页面，不要直接弹出旧页面。除非一直在后台运行……
 * 中间一个新对话，然后中间有一个 logo，一个很暖心的语言」）：新开软件不再自动打开最近的对话（App.kt），先到这里。
 * 中间 logo + 跟着时间走的一句问候，底下输入框直接打字；发出去才建对话（不会每打开一次留一个空对话）。
 * 跟谁聊默认是最近聊过的那个对话的成员，上面一排能换；要几个人一起聊点「自己挑成员」。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmptyChatScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val recent = remember(state.conversations, state.members) {
        state.conversations.filter { !it.isCode && it.channel.isBlank() && it.memberIds.isNotEmpty() }.maxByOrNull { it.updatedAt }
            ?.memberIds.orEmpty().filter { id -> state.member(id) != null }
    }
    var picked by remember(recent) { mutableStateOf(recent.ifEmpty { state.members.take(1).map { it.id } }) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val greeting = remember { HomeGreeting.now(state.profile.name) }
    fun send() {
        val t = text.trim()
        if (t.isEmpty() || picked.isEmpty() || busy) return
        busy = true
        ctl.run(Command.CreateConversation(picked)) { r ->
            if (!r.ok) { busy = false; ctl.toast(r.message.ifBlank { "开不了新对话" }); return@run }
            text = ""
            ctl.openConversation(r.data)
            ctl.run(Command.SendMessage(r.data, t)) { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        MobileMenu(wide, openDrawer)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(if (wide) 40.dp else 12.dp))
            BrandMark(64)
            Spacer(Modifier.height(16.dp))
            Text(greeting.first, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(greeting.second, style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp))
            Spacer(Modifier.height(22.dp))
            // 跟谁聊：点一个就是单聊它；「全员」拉所有成员进群
            androidx.compose.foundation.layout.FlowRow(Modifier.widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.members.take(8).forEach { m ->
                    val on = picked == listOf(m.id)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (on) MaterialTheme.colorScheme.primary else Ext.c.border, RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
                            .clickable { picked = listOf(m.id) }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemberAvatar(m, 24.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(m.name, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (state.members.size > 1) {
                    val all = state.members.map { it.id }
                    val on = picked.size > 1
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (on) MaterialTheme.colorScheme.primary else Ext.c.border, RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
                            .clickable { picked = all }.padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text(if (on && picked.size != all.size) "${picked.size} 个人一起" else "全员一起聊", style = MaterialTheme.typography.labelLarge) }
                }
            }
            TextButton(onClick = { ctl.showNewChat = true }) { Text("自己挑成员…") }
        }
        // 输入框：打字回车（电脑）或点发送就开一个新对话；电脑上宽窗口里居中、限宽
        val platform = LocalPlatform.current
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), contentAlignment = Alignment.Center) { Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                text, { text = it }, Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (platform.isDesktop && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        (e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter) && !e.isShiftPressed) { send(); true } else false
                },
                placeholder = {
                    val who = picked.mapNotNull { state.member(it)?.name }
                    Text(if (who.size == 1) "给${who[0]}发消息…" else "说点什么，${who.size} 个人一起听…", color = Ext.c.subtle, maxLines = 1)
                },
                shape = RoundedCornerShape(22.dp), maxLines = 6,
            )
            Spacer(Modifier.width(8.dp))
            GlassRoundButton(Icons.Rounded.ArrowUpward, "发送", enabled = text.isNotBlank() && picked.isNotEmpty() && !busy,
                color = MaterialTheme.colorScheme.primary, onColor = MaterialTheme.colorScheme.onPrimary, onClick = { send() })
        } }
    }
}

/** 首页那句问候：北京时间几点就说什么（公共代码里没有时区库，按 UTC+8 自己算），第二句按日子轮换。 */
object HomeGreeting {
    fun now(name: String, millis: Long = nowMillis()): Pair<String, String> {
        val hour = (((millis + 8 * 3_600_000L) / 3_600_000L) % 24).toInt()
        val day = ((millis + 8 * 3_600_000L) / 86_400_000L).toInt()
        // 没改过名字的默认叫「我」（UserProfile），问候成「夜深了，我」不像话，叫「你」
        val who = name.trim().takeUnless { it.isBlank() || it == "我" } ?: "你"
        val first = when (hour) {
            in 5..10 -> "早上好，$who"
            in 11..12 -> "中午好，$who"
            in 13..17 -> "下午好，$who"
            in 18..22 -> "晚上好，$who"
            else -> "夜深了，$who"
        }
        val lines = when (hour) {
            in 5..10 -> listOf("新的一天，慢慢来，有我们在。", "今天想先做点什么？我们陪你一起。", "吃早饭了吗？有事随时叫我们。")
            in 11..12 -> listOf("忙了一上午，先喝口水再说。", "中午了，别忘了吃饭。有什么想问的？", "上午辛苦了，下午的事我们帮你分担。")
            in 13..17 -> listOf("下午容易犯困，有什么我们帮你理一理。", "今天进展怎么样？有卡住的地方说一声。", "累了就歇会儿，剩下的交给我们。")
            in 18..22 -> listOf("忙完一天了，想聊点什么都行。", "今天辛苦了。有什么想说的，我们都在。", "晚上好，放松一下，有事慢慢说。")
            else -> listOf("这么晚还没睡，有什么心事可以说说。", "夜里也有我们陪着，不过别熬太晚。", "早点休息，明天的事明天再说也来得及。")
        }
        return first to lines[((day % lines.size) + lines.size) % lines.size]
    }
}
