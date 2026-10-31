package com.guixing.jixunying.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.APP_VERSION
import com.guixing.jixunying.model.Command
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置 → 关于 → 检查更新（2026-10-30 用户：「如果安装了这个应用，我从关于里边去更新，需不需要服务器？」——不要，查 GitHub 发行版）。
 * 管的是这台设备自己：手机遥控电脑时这里也是手机自己的版本（读本机 hub.local 的状态、对本机下命令）。
 * 查 → 有新版就显示说明 → 下载（核对 SHA-256）→ 电脑「更新并重开」/ 手机「安装」。引擎每天也自动查一次，有新版「关于」上标红点。
 */
@Composable
fun UpdateCard(ctl: AppController) {
    val platform = LocalPlatform.current
    val local = ctl.hub.local
    val st by local.store.state.collectAsState()
    val up = st.update
    val info = up.latest
    var busy by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    // 手机遥控电脑时顺便看看电脑上是哪一版（老版本电脑没有这一项）
    val remote by ctl.hub.remote.collectAsState()
    val hostVersion = remote?.store?.state?.collectAsState()?.value?.appVersion.orEmpty()

    // 开发演练用（-Djxy.start=settings:ABOUT#autoupdate）：自己把「检查 → 下载 → 更新」走一遍，真机演练原地换新版不用动鼠标
    LaunchedEffect(Unit) {
        if (!ctl.debug("autoupdate")) return@LaunchedEffect
        ctl.debugDialog = ""
        if (!local.call(Command.UpdateCheck).ok || !local.store.state.value.update.newer) return@LaunchedEffect
        local.call(Command.UpdateDownload)
        val s = local.store.state.first { it.update.downloaded.isNotEmpty() || it.update.error }.update
        val i = s.latest ?: return@LaunchedEffect
        if (s.downloaded.isNotEmpty()) platform.installUpdate(s.downloaded, i.kind, i.version)?.let { ctl.toast(it) }
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("检查更新", style = MaterialTheme.typography.titleSmall)
                Text("这台${if (platform.isDesktop) "电脑" else "手机"}装的是 $APP_VERSION" +
                    (if (up.checkedAt > 0) "，${formatTime(up.checkedAt)}查过" else "") +
                    (if (!platform.isDesktop && hostVersion.isNotEmpty()) "；电脑上是 $hostVersion" else ""),
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            TextButton(enabled = !up.checking && up.progress < 0f, onClick = { ctl.scope.launch { local.call(Command.UpdateCheck) } }) {
                Text(if (up.checking) "正在查…" else "检查更新")
            }
        }
        if (up.status.isNotBlank()) Text(up.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            color = when {
                up.error -> MaterialTheme.colorScheme.error
                up.newer -> MaterialTheme.colorScheme.primary
                else -> Ext.c.subtle
            })
        if (info != null && up.newer) {
            Spacer(Modifier.height(8.dp))
            Text("${info.name.ifBlank { "AI集训营 ${info.version}" }}" + (info.publishedAt.take(10).takeIf { it.isNotBlank() }?.let { " · $it 发布" } ?: ""),
                style = MaterialTheme.typography.bodyMedium)
            if (info.notes.isNotBlank()) {
                // 发行说明：默认只露开头一截，点「看全部」展开
                Column(if (showNotes) Modifier.padding(top = 4.dp) else Modifier.padding(top = 4.dp).heightIn(max = 120.dp)) {
                    MarkdownText(info.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "收起说明" else "看全部说明") }
            }
            if (up.progress >= 0f) {
                LinearProgressIndicator(progress = { up.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("已下 ${(up.progress * 100).toInt()}%" + (if (info.assetSize > 0) "（共 ${info.assetSize / 1024 / 1024} MB）" else ""),
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    // 这一版没有这台设备能自动装的文件：去网页
                    info.kind.isEmpty() -> OutlinedButton(onClick = { platform.openUrl(info.pageUrl) }) { Text("打开发行版网页") }
                    up.downloaded.isNotBlank() -> Button(enabled = !busy, onClick = {
                        busy = true
                        ctl.scope.launch {
                            val why = platform.installUpdate(up.downloaded, info.kind, info.version)
                            busy = false
                            if (why != null) ctl.toast(why)
                        }
                    }) { Text(when (info.kind) { "zip" -> "更新并重开"; "exe" -> "打开安装程序"; else -> "安装" }) }
                    else -> Button(enabled = up.progress < 0f, onClick = {
                        ctl.scope.launch { local.call(Command.UpdateDownload).let { r -> if (!r.ok) ctl.toast(r.message) } }
                    }) { Text(if (up.progress >= 0f) "正在下载…" else "下载新版" + (if (info.assetSize > 0) "（${info.assetSize / 1024 / 1024} MB）" else "")) }
                }
                if (info.pageUrl.isNotBlank() && info.kind.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { platform.openUrl(info.pageUrl) }) { Text("发行版网页") }
                }
            }
            Text(
                when (info.kind) {
                    "zip" -> "点「更新并重开」：程序先退出，在原来的文件夹里换成新版，再自动打开（几秒到十几秒）。聊天记录、设置都在数据文件夹里，不受影响；桌面图标、开机自启照旧。"
                    "exe" -> "这台电脑的程序文件夹写不进去，只能用安装程序：点了会打开安装程序，程序先退出；装好以后从桌面图标打开。"
                    "apk" -> "点「安装」会打开系统的安装界面，按「更新」就行，装完点「打开」。第一次要在系统里允许 AI集训营「安装未知应用」。" +
                        "手机直连 GitHub 下不动时，电脑开着会自动请电脑代下再传过来。"
                    else -> "这一版没有能自动装的文件，去发行版网页手动下载。"
                },
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
        } else if (info == null && !up.checking && up.status.isBlank()) {
            Text("新版发在 GitHub（libai379/ai-jixunying）上，不需要别的服务器。每天也会自动查一次，有新版这里和「关于」上会标出来。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
