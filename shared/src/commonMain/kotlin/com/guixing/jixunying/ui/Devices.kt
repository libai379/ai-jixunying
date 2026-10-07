package com.guixing.jixunying.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.RelaySettings
import kotlinx.coroutines.launch

private fun connText(c: ConnState) = when (c) {
    is ConnState.Connected -> "已连接"
    ConnState.Connecting -> "正在连接…"
    is ConnState.Failed -> c.reason
    ConnState.NotPaired -> "未配对"
    ConnState.Local -> "本机"
}

/** 手机在遥控电脑时，顶上一条提示，一键切回本机。 */
@Composable
fun RemoteBanner(ctl: AppController) {
    val conn by ctl.backend.conn.collectAsState()
    val ok = conn is ConnState.Connected
    Row(
        Modifier.fillMaxWidth().background(if (ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Computer, null, Modifier.size(16.dp), tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        val name = ctl.hub.remote.value?.host?.hostName ?: "电脑"
        Text(if (ok) "正在操作电脑「$name」" else "电脑「$name」：${connText(conn)}", style = MaterialTheme.typography.labelMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { ctl.hub.useRemote.value = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            Text("切回本机", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 侧栏顶上的设备切换：本机 / 配对的电脑。只在手机配对过电脑后出现。 */
@Composable
fun DeviceSwitcher(ctl: AppController) {
    val remote by ctl.hub.remote.collectAsState()
    val useRemote by ctl.hub.useRemote.collectAsState()
    val r = remote ?: return
    val conn by r.conn.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Ext.c.border, RoundedCornerShape(12.dp)).padding(3.dp),
    ) {
        @Composable
        fun seg(selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, dot: Color?, onClick: () -> Unit) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(onClick = onClick).padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(15.dp), tint = if (selected) MaterialTheme.colorScheme.primary else Ext.c.subtle)
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (dot != null) {
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                }
            }
        }
        seg(!useRemote, Icons.Rounded.PhoneAndroid, "本机", null) { ctl.hub.useRemote.value = false }
        seg(useRemote, Icons.Rounded.Computer, r.host.hostName.ifBlank { "电脑" },
            if (conn is ConnState.Connected) Ext.c.success else MaterialTheme.colorScheme.error) { ctl.hub.useRemote.value = true }
    }
}

@Composable
fun QrCode(text: String, sizeDp: Int = 220) {
    val platform = LocalPlatform.current
    val matrix = remember(text) { platform.qrMatrix(text) }
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp)) {
        if (matrix == null) Text("（这台设备不能显示二维码）", style = MaterialTheme.typography.bodySmall)
        else Canvas(Modifier.fillMaxWidth().height((sizeDp - 24).dp)) {
            val n = matrix.size
            val cell = size.minDimension / n
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[y][x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** 电脑端「联机」页：二维码 + 可复制的配对码 + 已配对手机 + 中转设置。 */
@Composable
fun HostDevicesPage(ctl: AppController, state: AppState) {
    val clipboard = LocalClipboardManager.current
    val platform = LocalPlatform.current
    val code = remember(state.hostId, state.pairingSecret, state.settings.relay) {
        PairingCode(state.hostId, state.pairingSecret, state.settings.relay.deviceName.ifBlank { platform.deviceName }, state.settings.relay.brokers).encode()
    }
    var relay by remember(state.settings.relay) { mutableStateOf(state.settings.relay) }
    var brokersText by remember(state.settings.relay) { mutableStateOf(state.settings.relay.brokers.joinToString("\n")) }

    Text("手机联机", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(2.dp))
    Text("手机在哪儿都能连这台电脑：家里、公司、出差、国外都行，不用同一个 Wi-Fi，也不用自己搭服务器。",
        style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    Spacer(Modifier.height(16.dp))
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            if (state.pairingSecret.isNotBlank()) QrCode(code)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("用手机扫码配对", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. 手机装上 AI集训营，打开 设置 → 连接电脑 → 扫码配对\n" +
                        "2. 扫左边的二维码，几秒钟就好\n" +
                        "3. 以后手机侧栏顶上能在「本机」和这台电脑之间切换\n\n" +
                        "扫不了码？点下面「复制配对码」，用微信发到手机上，在手机的联机页粘贴。\n" +
                        "每个二维码只能用一次，配对成功后自动换新的。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)); ctl.toast("配对码已复制") }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("复制配对码")
                    }
                    TextButton(onClick = { ctl.run(Command.NewPairingCode) }) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("换一个")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ok = state.relayStatus.startsWith("已连上")
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Ext.c.success else Ext.c.warning))
                    Spacer(Modifier.width(6.dp))
                    Text(state.relayStatus.ifBlank { "正在启动…" }, style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("已配对的手机", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (state.devices.isEmpty()) Text("还没有", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        state.devices.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp), tint = Ext.c.subtle)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium)
                    if (d.lastSeen > 0) Text("最近在线：" + formatTime(d.lastSeen), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
                TextButton(onClick = { ctl.run(Command.RemoveDevice(d.id)) }) { Text("取消配对", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("它是怎么连上的", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "电脑和手机都主动连到公共中转服务器（默认同时连 EMQX 和 HiveMQ 两家公司的免费公共服务器，哪个通用哪个，一家出问题另一家顶上），" +
                "所以不需要公网 IP、不用设路由器。\n" +
                "聊天内容和 Key 在发出前用配对时交换的密钥加密（AES-256-GCM），中转服务器上只能看到乱码，也改不了。\n" +
                "电脑要开着并运行 AI集训营，手机才能遥控它；电脑关着时手机切回「本机」照样能用。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        SwitchRow("允许手机联机", "关掉后不再连中转，手机也就连不上这台电脑", state.settings.relay.enabled) { v ->
            ctl.updateSettings { it.copy(relay = it.relay.copy(enabled = v)) }
        }
        var deviceName by remember { mutableStateOf(state.settings.relay.deviceName) }
        AutoSave(deviceName, state.settings.relay.deviceName) { v -> ctl.updateSettings { it.copy(relay = it.relay.copy(deviceName = v.trim().take(20))) } }
        FieldLabel("电脑名称", "手机上显示的名字，改了自动保存")
        AppTextField(deviceName, { deviceName = it }, placeholder = platform.deviceName)
        Spacer(Modifier.height(8.dp))
        // 中转服务器是高级设置：改了要重连、可能要重新配对，所以留一个明确的「应用」按钮，不边打边生效
        FieldLabel("中转服务器（高级）", "一行一个；可以换成自己的 MQTT 服务器。改了以后手机要重新配对")
        AppTextField(brokersText, { brokersText = it }, singleLine = false, minLines = 2)
        Spacer(Modifier.height(10.dp))
        val list = brokersText.lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { RelaySettings.DEFAULT_BROKERS }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = list != state.settings.relay.brokers, onClick = {
                ctl.updateSettings { it.copy(relay = it.relay.copy(brokers = list)) }
                ctl.toast("中转服务器已更换，正在重新连接")
            }) { Text("应用中转服务器") }
            TextButton(onClick = { brokersText = RelaySettings.DEFAULT_BROKERS.joinToString("\n") }) { Text("恢复默认") }
        }
    }
}

/** 手机端「联机」页：扫码 / 粘贴配对码；已配对就显示状态、导入配置、取消配对。 */
@Composable
fun PhoneDevicesPage(ctl: AppController) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val remote by ctl.hub.remote.collectAsState()
    var codeText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(text: String) {
        val code = PairingCode.decode(text)
        if (code == null) { error = "这不是 AI集训营 的配对码（应该以 ${PairingCode.PREFIX} 开头）"; return }
        busy = true; error = null
        scope.launch {
            val r = ctl.hub.pair(code)
            busy = false
            r.onSuccess { ctl.toast("已配对「${it.hostName}」") }.onFailure { error = it.message }
        }
    }

    Text("连接电脑", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(2.dp))
    Text("手机本身就能单独用（模型直接从手机调用）。配对电脑后，还能在任何地方遥控电脑上的 AI集训营：看电脑上的对话、让电脑干活。",
        style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    Spacer(Modifier.height(16.dp))
    val r = remote
    if (r == null) {
        SectionCard {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(46.dp), onClick = {
                scope.launch { platform.scanQr()?.let { pair(it) } }
            }) {
                Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "正在配对…" else "扫码配对")
            }
            Spacer(Modifier.height(12.dp))
            Text("或者粘贴电脑上复制的配对码：", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(6.dp))
            AppTextField(codeText, { codeText = it }, placeholder = "${PairingCode.PREFIX}……", singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = !busy && codeText.isNotBlank(), onClick = { pair(codeText) }) { Text("用配对码配对") }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Text("电脑上：打开 AI集训营 → 设置 → 手机联机，就能看到二维码。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
    } else {
        val conn by r.conn.collectAsState()
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.host.hostName.ifBlank { "电脑" }, style = MaterialTheme.typography.titleSmall)
                    Text(connText(conn), style = MaterialTheme.typography.labelMedium,
                        color = if (conn is ConnState.Connected) Ext.c.success else MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(enabled = conn is ConnState.Connected && !busy, onClick = {
                busy = true
                scope.launch {
                    val exp = r.call(Command.ExportConfig)
                    val res = if (exp.ok) ctl.hub.local.call(Command.ImportConfig(exp.data)) else exp
                    busy = false
                    ctl.toast(if (res.ok) res.message.ifBlank { "已导入" } else res.message)
                }
            }) {
                Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(if (busy) "正在同步…" else "同步电脑上的模型服务和成员")
            }
            Text("同步后手机不连电脑也能直接用这些模型（Key 加密传过来，只存在这台手机上）。手机上已有的不会重复添加，也不会被覆盖，只补电脑上多出来的。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctl.hub.useRemote.value = true }) { Text("切到电脑") }
                TextButton(onClick = { ctl.hub.unpair() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("取消配对") }
            }
        }
    }
}

fun formatTime(millis: Long): String {
    val diff = nowMillis() - millis
    return when {
        millis <= 0 -> "还没有"
        diff < 120_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 30 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> dateText(millis)
    }
}

/** 北京时间的日期，例如「2025年10月3日」（公共代码里没有时区库，按 UTC+8 自己算）。 */
fun dateText(millis: Long): String {
    fun floorDiv(a: Long, b: Long) = if (a >= 0) a / b else -((-a + b - 1) / b)
    val days = floorDiv(millis + 8 * 3_600_000L, 86_400_000L)
    // Howard Hinnant 的 civil_from_days
    val z = days + 719_468
    val era = floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${y}年${m}月${d}日"
}

