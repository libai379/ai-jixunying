package com.guixing.jixunying.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.WeixinSettings
import kotlinx.coroutines.launch

/**
 * 设置 → 微信。扫码绑定微信助理、选谁来回答、开关。
 * 手机上看本机时（1.5.0 起手机也能接）：配对过电脑就用电脑的同一个绑定（一个微信只能绑一台设备），电脑在接时手机待命；
 * 没配对电脑的手机自己绑。
 */
@Composable
fun WeixinPage(ctl: AppController, state: AppState) {
    val clipboard = LocalClipboardManager.current
    val platform = LocalPlatform.current
    val wx = state.weixin
    val ws = state.settings.weixin
    var busy by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var confirmUnbind by remember { mutableStateOf(false) }
    fun save(f: (WeixinSettings) -> WeixinSettings) = ctl.updateSettings { it.copy(weixin = f(it.weixin)) }
    // 手机上看的是本机（不是遥控电脑），并且配对过电脑
    val phoneLocal = !platform.isDesktop && ctl.backend === ctl.hub.local
    val remote by ctl.hub.remote.collectAsState()
    val paired = remote.takeIf { phoneLocal }
    val host = paired?.let { it.store.state.collectAsState().value }
    val hostOnline = paired?.let { it.conn.collectAsState().value is com.guixing.jixunying.client.ConnState.Connected } == true

    PageHeader("微信", "和 WorkBuddy 的「微信助理」一样：扫码绑定以后，在手机微信里给助理发消息（文字、图片、文件、语音都行），AI 成员回答，回复发回微信。" +
        "电脑和手机都能接：电脑开着就电脑答，电脑关了由手机接着答。用的是腾讯官方的 ClawBot 接口。")

    if (!state.weixinCapable) {
        SectionCard {
            Text("这台设备没有接微信助理", style = MaterialTheme.typography.titleSmall)
            Text("电脑上的 AI集训营：设置 → 微信，扫码绑定。如果这台手机已经配对电脑，可以在侧栏顶上切到电脑，在这里直接管理。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        WeixinFilesTips(isPhone = !platform.isDesktop)
        return
    }

    if (paired != null && (wx.shared || !wx.bound)) {
        // 配对过电脑的手机：用电脑的绑定，不在手机上另外扫码（会把电脑的顶掉）
        SectionCard {
            Text(if (wx.bound) "用的是电脑上的微信绑定" else "还没接微信", style = MaterialTheme.typography.titleSmall)
            if (wx.bound && wx.status.isNotBlank()) Text(wx.status, style = MaterialTheme.typography.bodySmall,
                color = if (wx.status.startsWith("已连接")) Ext.c.success else Ext.c.subtle)
            if (wx.bound && wx.lastMessageAt > 0) Text("这台手机收到的最近一条：${formatTime(wx.lastMessageAt)}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            Text(
                when {
                    wx.bound -> "回答用的是这台手机上的 AI 成员。手机上的 AI集训营 要开着（在后台也行）才能接；电脑在接时手机只待命，不收消息。"
                    host?.weixin?.bound == true -> "电脑上已经绑定了微信。开着下面「这台手机接微信」的开关，手机会自动用同一个绑定，不用再扫码。"
                    else -> "先在电脑上 设置 → 微信 扫码绑定，这台手机会自动用同一个绑定。一个微信只能绑一台设备，在手机上另外扫码会把电脑的顶掉。"
                },
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
            if (!wx.bound && host?.weixin?.bound == true) {
                Spacer(Modifier.height(8.dp))
                Button(enabled = hostOnline && !busy, onClick = {
                    busy = true
                    ctl.scope.launch {
                        val r = paired.call(Command.WeixinShare)
                        if (r.ok) ctl.run(Command.WeixinImport(r.data)) { busy = false } else { busy = false; ctl.toast(r.message) }
                    }
                }) { Text(if (busy) "正在同步…" else "现在同步") }
                if (!hostOnline) Text("电脑现在不在线，等电脑开着再同步。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    } else SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (wx.bound) "已绑定微信" else "还没绑定微信", style = MaterialTheme.typography.titleSmall)
                // 状态和标题说的是一回事时就不重复了
                if (wx.status.isNotBlank() && wx.status != "未绑定") Text(wx.status, style = MaterialTheme.typography.bodySmall,
                    color = if (wx.status.startsWith("已连接")) Ext.c.success else Ext.c.subtle)
                if (wx.bound && wx.lastMessageAt > 0) Text("最近一条微信消息：${formatTime(wx.lastMessageAt)}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            if (wx.bound) TextButton(onClick = { confirmUnbind = true }) { Text("解除绑定", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(10.dp))
        if (wx.loginLink.isNotBlank()) {
            Row(verticalAlignment = Alignment.Top) {
                val canShowQr = platform.qrMatrix(wx.loginLink) != null
                if (canShowQr) {
                    QrCode(wx.loginLink, 200)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    // 手机上（遥控电脑时）显示不了二维码，自己的屏幕也没法用自己的微信扫：改成发链接
                    Text(
                        if (canShowQr) "1. 打开手机微信，点右上角「＋」→「扫一扫」\n2. 扫左边的二维码，在手机上点「确认」\n3. 绑定好以后，微信里会多一个助理对话，给它发消息就行"
                        else "1. 点下面「复制链接」\n2. 发到自己的微信里（比如「文件传输助手」），点开链接，按提示确认\n3. 绑定好以后，微信里会多一个助理对话，给它发消息就行",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(wx.loginLink)); ctl.toast("绑定链接已复制") }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(if (canShowQr) "扫不了码？复制链接" else "复制链接")
                    }
                    if (canShowQr) Text("把链接发到自己微信（比如「文件传输助手」）里点开，也能绑定。",
                        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
                    if (wx.needVerifyCode) {
                        Spacer(Modifier.height(10.dp))
                        FieldLabel("手机微信上显示的数字")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppTextField(code, { code = it.filter(Char::isDigit).take(8) }, Modifier.weight(1f), placeholder = "输入数字",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = code.isNotBlank(), onClick = { ctl.run(Command.WeixinVerify(code), quiet = true); code = "" }) { Text("提交") }
                        }
                    }
                }
            }
        } else if (!wx.bound) {
            Button(enabled = !busy, onClick = {
                busy = true
                ctl.run(Command.WeixinLogin, quiet = true) { r -> busy = false; if (!r.ok) ctl.toast(r.message) }
            }) { Text(if (busy) "正在生成二维码…" else "绑定微信") }
            Text("需要这台设备能上网（连的是腾讯的服务器，国内直连，不走代理）。一个微信只能绑一台设备：手机配对过电脑的话，在电脑上绑，手机会自动用同一个绑定。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
        }
        // 手机自己扫码绑了、电脑也绑了：后绑的会把先绑的顶掉
        if (wx.bound && !wx.shared && host?.weixin?.bound == true) {
            Text("这台手机和电脑各绑了一次微信。一个微信只能绑一台设备，后绑的会把先绑的顶掉。建议在手机上「解除绑定」，手机会自动改用电脑的绑定。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        if (platform.isDesktop || !phoneLocal) SwitchRow("开启微信助理", "关掉后这台电脑不回微信消息（绑定还在）；配对的手机开着微信助理的话，由手机接着答", ws.enabled) { v -> save { it.copy(enabled = v) } }
        else {
            SwitchRow("这台手机接微信", "电脑关机或关掉微信助理时由手机接着答。关掉省电：手机不收微信消息，电脑开着照常回答", ws.enabled) { v -> save { it.copy(enabled = v) } }
            if (ws.enabled && wx.bound) {
                // 手机接微信时通知栏常驻一条（前台服务）；有的手机还会在后台省电时关掉应用，要把电池设成「不限制」
                Text("开着时通知栏会常驻一条「AI集训营 在后台接微信」，系统才不会把应用关掉；点通知上的「关掉」等于关这个开关。" +
                    "三星等手机还要在 设置 → 应用 → AI集训营 → 电池 里选「不限制」，不然锁屏久了还是会被关掉。",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(bottom = 4.dp))
                if (platform.canOpenAppSettings) TextButton(onClick = { platform.openAppSettings() }) { Text("打开这个应用的系统设置") }
            }
        }
        SwitchRow("联网搜索", "微信里问时效性的问题会先搜再答，回复末尾附上来源", ws.webSearch) { v -> save { it.copy(webSearch = v) } }
        Spacer(Modifier.height(6.dp))
        FieldLabel("谁来回答微信消息", "选一位就是单聊；选多位就是群聊，每位的回答各发一条")
        val picked = ws.memberIds.filter { state.member(it) != null }.ifEmpty { state.members.take(1).map { it.id } }.toSet()
        if (state.members.isEmpty()) Text("还没有 AI 成员，先到「AI 成员」添加。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        MemberPickList(state, picked) { id ->
            val next = if (id in picked) picked - id else picked + id
            if (next.isNotEmpty()) save { it.copy(memberIds = state.members.map { m -> m.id }.filter { id2 -> id2 in next }) }
        }
        Text("换了以后，已经有的「微信对话」也一起换成这几位。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("在微信里怎么用", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "· 直接发文字、图片、文件（PDF、Word、Excel、PPT 等），AI 会读；语音用微信转好的文字。\n" +
                "· 说「画一张……」，画好的图会发回微信。\n" +
                "· 发「/新对话」开始一个新话题，发「/帮助」看说明。\n" +
                "· 每个微信联系人在接的那台设备上有一个「微信对话」，在那里也能看、能接着聊。\n" +
                "· 电脑开着 AI集训营 就由电脑答；电脑关了，开着微信助理的手机接着答（手机上的 AI集训营 要开着，在后台也行）。两边都关着，微信里就没有回复。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    WeixinFilesTips(isPhone = false)

    if (confirmUnbind) {
        AppDialog("解除绑定微信", { confirmUnbind = false }, width = 440.dp, actions = {
            TextButton({ confirmUnbind = false }) { Text("取消") }
            Button({ confirmUnbind = false; ctl.run(Command.WeixinLogout) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("解除绑定") }
        }) { Text("解除后微信里的助理不再回复，电脑上的「微信对话」聊天记录保留。以后可以重新扫码绑定。", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** 怎么让 AI 读微信里的文件和聊天：只走官方渠道。 */
@Composable
private fun WeixinFilesTips(isPhone: Boolean) {
    SectionCard {
        Text("让 AI 看微信里的文件和聊天", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            (if (isPhone) "· 在这台手机的微信里点开文件，右上角「…」→「用其他应用打开」→ AI集训营，选个对话就能问。\n" else "") +
                "· 转发给微信助理：文件、图片、文字消息都可以直接转给它。\n" +
                "· 电脑上：「我的文档」里打开「包括微信收到的文件」，AI 就能搜到微信收过的文件。\n" +
                "· 聊天记录：把要 AI 看的消息逐条转发或截图发给助理。微信「合并转发的聊天记录」目前不会转给助理；AI集训营也不读微信本地加密的聊天数据库。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
