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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.DocHit
import com.guixing.jixunying.model.DocSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * 我的文档：AI 能搜、能读这台设备上的文档。这里能看收录了哪些文件夹、加减文件夹、搜文件，
 * 找到的文件点「问 AI」就带着它去对话（选好对话后文件放进输入框，再写要问什么）。
 */
@Composable
fun DocsScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val info = state.docs
    val ds = state.settings.docs
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<DocHit>?>(null) }
    var asking by remember { mutableStateOf<String?>(null) }
    val device = if (ctl.remoteMode) "电脑「${ctl.hub.remote.value?.host?.hostName ?: ""}」" else if (platform.isDesktop) "这台电脑" else "这台手机"
    fun save(next: DocSettings) = ctl.run(Command.SaveSettings(state.settings.copy(docs = next)), quiet = true)

    LaunchedEffect(query, info.count, ctl.backend) {
        if (query.isNotBlank()) delay(300)
        val r = ctl.backend.call(Command.DocSearch(query.trim(), 60))
        hits = if (r.ok) runCatching { AppJson.decodeFromString(ListSerializer(DocHit.serializer()), r.data) }.getOrDefault(emptyList()) else emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        if (!wide) Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
            Text("我的文档", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
            Column(Modifier.widthIn(max = 860.dp)) {
                PageHeader("我的文档", "AI 能搜、能读${device}上的文档（PDF、Word、Excel、PPT、文本等）。在聊天里直接问「我那份合同里付款日期是哪天」就行；也可以在这里找到文件，点「问 AI」。")
                SectionCard {
                    if (info.needPermission && !ctl.remoteMode) {
                        Text("还没允许读取手机里的文件", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text("安卓要你在系统设置里给 AI集训营 打开「所有文件访问权限」，AI 才能读到别的 App（包括微信「保存到手机」的）文件。只读不改，也不会上传。",
                            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(vertical = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { platform.requestFileAccess() }) { Text("去允许") }
                            OutlinedButton(onClick = { ctl.run(Command.DocRescan) }) { Text("我已经允许了") }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    !ds.enabled -> "已关闭：AI 不查你的文档"
                                    info.scanning -> "正在扫描…已收录 ${info.count} 个"
                                    else -> "已收录 ${info.count} 个文档 · ${formatTime(info.scannedAt)}扫描"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (info.scanning && info.progress.isNotBlank()) Text(info.progress, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (info.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else TextButton(enabled = ds.enabled, onClick = { ctl.run(Command.DocRescan) }) { Text("重新扫描") }
                    }
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("收录的文件夹", if (ds.folders.isEmpty()) "现在用默认的；文件改了会自动更新（每半小时看一次）" else "自己选的")
                    if (info.roots.isEmpty()) Text("（没有可用的文件夹）", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    info.roots.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp), tint = Ext.c.subtle)
                            Spacer(Modifier.width(6.dp))
                            Text(r, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (r in ds.folders) IconButton(onClick = { save(ds.copy(folders = ds.folders - r)) }, Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, "不收录这个文件夹", Modifier.size(14.dp), tint = Ext.c.subtle)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!ctl.remoteMode) OutlinedButton(onClick = {
                            scope.launch {
                                val f = platform.pickFolder() ?: return@launch
                                // 第一次自己加文件夹时，把原来默认的那些也带上，免得一加反而少了
                                val base = ds.folders.ifEmpty { info.roots.filter { it !in info.weixinRoots } }
                                save(ds.copy(folders = (base + f).distinct()))
                            }
                        }) { Text("添加文件夹") }
                        if (ds.folders.isNotEmpty()) TextButton(onClick = { save(ds.copy(folders = emptyList())) }) { Text("恢复默认") }
                    }
                    if (platform.isDesktop || ctl.remoteMode) {
                        HorizontalLine()
                        if (info.weixinRoots.isNotEmpty()) SwitchRow("包括微信收到的文件",
                            "电脑微信把收到的文件存在「${info.weixinRoots.first()}」这类文件夹里（找到 ${info.weixinRoots.size} 个）。打开后 AI 也能搜到这些文件。聊天记录不在这里读。",
                            ds.includeWeixin) { save(ds.copy(includeWeixin = it)) }
                        else Text("没找到电脑微信存文件的文件夹（一般在「文档\\WeChat Files」或「文档\\xwechat_files」）。",
                            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    }
                    HorizontalLine()
                    SwitchRow("让 AI 查文档", "关掉后 AI 不再搜你的文档，也不再扫描", ds.enabled) { save(ds.copy(enabled = it)) }
                }
                Spacer(Modifier.height(16.dp))
                AppTextField(query, { query = it }, placeholder = "搜文件名或内容，比如：合同 付款日期",
                    trailing = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = Ext.c.subtle) })
                Spacer(Modifier.height(10.dp))
                val list = hits
                Text(
                    when {
                        list == null -> "正在加载…"
                        query.isBlank() -> if (list.isEmpty()) "还没有收录到文档" else "最近改过的文档"
                        else -> "找到 ${list.size} 个"
                    },
                    style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle, modifier = Modifier.padding(bottom = 6.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.orEmpty().forEach { h ->
                        DocRow(h, canOpen = !ctl.remoteMode, busy = asking == h.path, onOpen = {
                            if (!platform.openFile(h.path)) ctl.toast("打不开这个文件")
                        }) {
                            asking = h.path
                            ctl.run(Command.DocAttach(h.path), quiet = true) { r ->
                                asking = null
                                if (!r.ok) { ctl.toast(r.message); return@run }
                                val att = runCatching { AppJson.decodeFromString(Attachment.serializer(), r.data) }.getOrNull() ?: return@run
                                ctl.incoming = Incoming(attachments = listOf(att))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalLine() {
    androidx.compose.material3.HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun DocRow(h: DocHit, canOpen: Boolean, busy: Boolean, onOpen: () -> Unit, onAsk: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Ext.c.border, RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ext = h.name.substringAfterLast('.', "").uppercase().take(4)
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(docColor(ext)), contentAlignment = Alignment.Center) {
            Text(ext.ifEmpty { "文件" }, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.folder, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(h.size) + " · " + formatTime(h.mtime) + " · " + (if (h.chars > 0) "${h.chars} 字" else h.note.ifBlank { "读不出文字" }),
                style = MaterialTheme.typography.labelSmall, color = if (h.chars > 0) Ext.c.subtle else Ext.c.warning, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.snippet.isNotBlank()) Text(h.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(enabled = !busy, onClick = onAsk) { Text(if (busy) "读取中…" else "问 AI") }
            if (canOpen) TextButton(onClick = onOpen) { Text("打开") }
        }
    }
}

/** 文件（别的 App 发来的，或文档库里的）要发到哪个对话：选一个，文件就放进那个对话的输入框。 */
@Composable
fun SendToDialog(ctl: AppController, inc: Incoming) {
    val state by ctl.backend.store.state.collectAsState()
    val names = inc.names
    AppDialog("发到哪个对话？", { ctl.incoming = null }, width = 520.dp) {
        Text("「${names.firstOrNull().orEmpty()}」" + (if (names.size > 1) "等 ${names.size} 个文件" else "") + "会放进输入框，你再写要问什么。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val first = state.members.firstOrNull()
            if (first != null) PickRow("开一个新对话", "和 ${first.name} 单聊（以后可以在对话设置里加人）", "＋") {
                ctl.run(Command.CreateConversation(listOf(first.id))) { r -> if (r.ok) ctl.deliverIncoming(r.data) }
            }
            state.conversations.sortedByDescending { it.updatedAt }.take(30).forEach { c ->
                val members = c.memberIds.mapNotNull { state.member(it)?.name }
                PickRow(c.title, if (members.size > 1) "群聊 · " + members.joinToString("、") else members.firstOrNull() ?: "未选成员", null) {
                    ctl.deliverIncoming(c.id)
                }
            }
            if (state.members.isEmpty()) Text("还没有 AI 成员，先到 设置 → AI 成员 添加。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PickRow(title: String, sub: String, badge: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Ext.c.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge != null) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Text(badge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
