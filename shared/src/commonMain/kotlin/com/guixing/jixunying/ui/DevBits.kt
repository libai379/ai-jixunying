package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.BackgroundProc
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation

/**
 * 开发一套的小部件（2026-10-18）：两栏共用。
 * BackgroundBar：输入框上面一条，列出这个对话 / 会话开着的后台命令——绿点在跑、灰点结束了；
 * 有网址的点「打开」用浏览器看（预览），「输出」看最后一截，「停」停掉。
 */
@Composable
fun BackgroundBar(ctl: AppController, conv: Conversation) {
    val procs = conv.background
    if (procs.isEmpty()) return
    val platform = LocalPlatform.current
    var showOut by remember(conv.id) { mutableStateOf<BackgroundProc?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        procs.takeLast(6).forEach { p ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).border(1.dp, Ext.c.border, RoundedCornerShape(50)).padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (p.running) Ext.c.success else Ext.c.subtle))
                Spacer(Modifier.width(6.dp))
                Text(p.command.take(36), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 260.dp))
                if (p.url.isNotBlank()) TextButton({ platform.openUrl(p.url) }) { Text("打开", style = MaterialTheme.typography.labelMedium) }
                TextButton({ showOut = p }) { Text("输出", style = MaterialTheme.typography.labelMedium) }
                if (p.running) TextButton({ ctl.run(Command.BgStop(conv.id, p.id)) }) {
                    Text("停", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    showOut?.let { p -> BackgroundOutputDialog(ctl, conv, p) { showOut = null } }
}

@Composable
private fun BackgroundOutputDialog(ctl: AppController, conv: Conversation, p: BackgroundProc, onClose: () -> Unit) {
    var text by remember { mutableStateOf("正在取…") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        val r = ctl.backend.call(Command.BgOutput(conv.id, p.id))
        text = if (r.ok) r.data.ifBlank { "（还没有输出）" } else r.message
    }
    AppDialog("后台命令的输出", onClose, width = 720.dp, actions = {
        TextButton({ tick++ }) { Text("刷新") }
        Button(onClose) { Text("好了") }
    }) {
        Column(Modifier.fillMaxWidth()) {
            Text(p.command + "\n在 " + p.cwd + if (p.url.isNotBlank()) "\n网址 " + p.url else "",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState()).padding(10.dp)) {
                SelectionContainer { Text(text.takeLast(20_000), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            }
        }
    }
}

/** 待办清单卡片（2026-10-18，开发补齐第 4 样）：[x] 做完打勾、[>] 正在做高亮、[ ] 没做；两栏共用。 */
@Composable
fun TodoCard(lines: String) {
    val items = lines.lines().filter { it.length > 3 }
    if (items.isEmpty()) return
    val done = items.count { it.startsWith("[x]") }
    Column(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(1.dp, Ext.c.border, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("待办 · $done / ${items.size}", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
        items.forEach { l ->
            val st = l.substring(1, 2)
            val text = l.substring(3).trim()
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (st) { "x" -> "✓"; ">" -> "▶"; else -> "○" }, style = MaterialTheme.typography.bodySmall,
                    color = when (st) { "x" -> Ext.c.success; ">" -> MaterialTheme.colorScheme.primary; else -> Ext.c.subtle },
                    modifier = Modifier.width(18.dp))
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = when (st) { "x" -> Ext.c.subtle; ">" -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    textDecoration = if (st == "x") androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            }
        }
    }
}
