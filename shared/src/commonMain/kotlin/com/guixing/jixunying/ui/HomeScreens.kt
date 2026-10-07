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
            Text("把国内外的大模型拉进一个群：单聊像豆包，群聊能互相 @、互相纠错。\n联网搜索、看图读文档、画图都有。数据全在你自己的电脑上。",
                style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StepCard(1, "添加模型服务", "选 DeepSeek、智谱、Kimi、通义、豆包、OpenAI、Claude… 填上 API Key", state.providers.isNotEmpty()) {
                    ctl.openSettings(SettingsTab.PROVIDERS)
                }
                StepCard(2, "创建 AI 成员", "给模型起个名字、定个位，比如「老钱：数据分析」「杠精：专门挑错」", state.members.isNotEmpty()) {
                    ctl.openSettings(SettingsTab.MEMBERS)
                }
                StepCard(3, "开始聊天", "单聊或拉群都行", false) {
                    if (state.members.isNotEmpty()) ctl.showNewChat = true else ctl.openSettings(SettingsTab.MEMBERS)
                }
                if (ctl.backend.isHost) StepCard(4, "（可选）手机连电脑", "手机装上安卓版，同一个 Wi-Fi 下输入配对码即可", state.devices.isNotEmpty()) {
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

@Composable
fun EmptyChatScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        MobileMenu(wide, openDrawer)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BrandMark(56)
            Spacer(Modifier.height(14.dp))
            Text("今天想聊点什么？", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.members.take(4).forEach { m ->
                    Column(
                        Modifier.width(110.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(16.dp))
                            .clickable { ctl.run(Command.CreateConversation(listOf(m.id))) { r -> if (r.ok) ctl.openConversation(r.data) } }
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MemberAvatar(m, 40.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(m.name, style = MaterialTheme.typography.bodyMedium)
                        Text("单聊", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (state.members.size > 1) Button(onClick = {
                ctl.run(Command.CreateConversation(state.members.map { it.id }, "全员群聊")) { r -> if (r.ok) ctl.openConversation(r.data) }
            }) { Text("拉全员进群聊") }
            TextButton(onClick = { ctl.showNewChat = true }) { Text("自己挑成员…") }
        }
    }
}

/** 手机端首次使用：找到电脑并配对。 */
@Composable
fun ConnectScreen(remote: RemoteBackend, ctl: AppController) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val conn by remote.conn.collectAsState()
    var hosts by remember { mutableStateOf<List<FoundHost>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(platform.getPref("host").orEmpty()) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun discover() {
        scope.launch {
            searching = true
            hosts = platform.discoverHosts()
            if (address.isBlank()) hosts.firstOrNull()?.let { address = it.address }
            searching = false
        }
    }
    LaunchedEffect(Unit) { discover() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(30.dp))
        BrandMark(60)
        Spacer(Modifier.height(14.dp))
        Text("连接你的电脑", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("电脑上打开 AI集训营 → 设置 → 手机连接，手机和电脑连同一个 Wi-Fi。", style = MaterialTheme.typography.bodySmall,
            color = Ext.c.subtle, textAlign = TextAlign.Center)
        if (conn is ConnState.Failed) {
            Spacer(Modifier.height(8.dp))
            Text((conn as ConnState.Failed).reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("找到的电脑", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { discover() }) { Icon(Icons.Rounded.Refresh, "重新查找", Modifier.size(18.dp)) }
            }
            if (hosts.isEmpty() && !searching) Text("没找到。确认电脑端开着、同一个 Wi-Fi，或者在下面手动填地址。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            hosts.forEach { h ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, if (address == h.address) MaterialTheme.colorScheme.primary else Ext.c.border, RoundedCornerShape(12.dp))
                        .clickable { address = h.address }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(h.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(h.address, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            FieldLabel("电脑地址")
            AppTextField(address, { address = it.trim() }, placeholder = "例如 192.168.1.5:18765", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Spacer(Modifier.height(10.dp))
            FieldLabel("配对码", "电脑上显示的 6 位数字")
            AppTextField(code, { code = it.filter(Char::isDigit).take(6) }, placeholder = "000000", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = !busy && address.isNotBlank() && code.length == 6,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val host = if (address.contains(':')) address else "$address:18765"
                        val r = remote.pair(host, code, platform.deviceName)
                        busy = false
                        if (r.ok) {
                            platform.setPref("host", host); platform.setPref("token", r.token)
                            remote.connect(host, r.token)
                        } else error = r.message
                    }
                },
            ) { Text(if (busy) "正在配对…" else "配对并连接") }
            val savedToken = platform.getPref("token")
            if (savedToken != null && platform.getPref("host") != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { remote.connect(platform.getPref("host")!!, savedToken) }, modifier = Modifier.fillMaxWidth()) { Text("用上次的配对重新连接") }
            }
        }
    }
}
