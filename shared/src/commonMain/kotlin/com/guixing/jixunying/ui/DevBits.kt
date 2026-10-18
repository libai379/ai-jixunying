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

/** 光标前面正在打的「@xxx」（没有空白），返回 xxx；不在打就返回 null。 */
fun atQuery(v: androidx.compose.ui.text.input.TextFieldValue): String? {
    val before = v.text.substring(0, v.selection.start.coerceIn(0, v.text.length))
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    val q = before.substring(at + 1)
    return if (q.any { it.isWhitespace() } || q.length > 60) null else q
}

/** 把光标前的「@xxx」换成「@路径 」。路径里有空格的加引号。 */
fun insertAt(v: androidx.compose.ui.text.input.TextFieldValue, path: String): androidx.compose.ui.text.input.TextFieldValue {
    val cursor = v.selection.start.coerceIn(0, v.text.length)
    val at = v.text.substring(0, cursor).lastIndexOf('@')
    val token = if (path.any { it.isWhitespace() }) "\"$path\"" else path
    val nt = v.text.substring(0, at) + "@" + token + " " + v.text.substring(cursor)
    return androidx.compose.ui.text.input.TextFieldValue(nt, androidx.compose.ui.text.TextRange(at + token.length + 2))
}

/** 按「@xxx」去电脑上找文件（停 200 毫秒再找，免得每打一个字找一次）。 */
@Composable
fun rememberFileSuggestions(ctl: AppController, convId: String, query: String?): List<String> {
    var list by remember(convId) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(convId, query) {
        if (query == null) { list = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(200)
        val r = ctl.backend.call(Command.FileSuggest(convId, query))
        list = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(r.data) as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content } }.getOrDefault(emptyList())
    }
    return list
}

/** 输入框上方弹出的文件建议列表。 */
@Composable
fun FileSuggestList(files: List<String>, onPick: (String) -> Unit) {
    if (files.isEmpty()) return
    androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Ext.c.border),
        shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 520.dp)) {
        Column(Modifier.padding(4.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(f) }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(f.substringAfterLast('/').substringAfterLast('\\'), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(f, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 一条斜杠命令（界面用）。 */
class SlashItem(val name: String, val description: String, val text: String, val source: String)

/** 输入框开头正在打的「/xxx」（还没打空格），返回 xxx；不是就 null。 */
fun slashQuery(v: androidx.compose.ui.text.input.TextFieldValue): String? {
    val t = v.text
    if (!t.startsWith("/") || t.contains('\n')) return null
    val word = t.drop(1).substringBefore(' ')
    return if (t.length == word.length + 1) word else null
}

/** 取这个对话能用的斜杠命令（打开输入框时取一次）。 */
@Composable
fun rememberSlashCommands(ctl: AppController, convId: String): List<SlashItem> {
    var list by remember(convId) { mutableStateOf(emptyList<SlashItem>()) }
    LaunchedEffect(convId) {
        val r = ctl.backend.call(Command.SlashList(convId))
        list = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(r.data) as kotlinx.serialization.json.JsonArray).map { e ->
                val o = e as kotlinx.serialization.json.JsonObject
                fun f(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                SlashItem(f("name"), f("description"), f("text"), f("source"))
            }
        }.getOrDefault(emptyList())
    }
    return list
}

/** 展开一条斜杠命令：「/名字 后面的字」→ 命令的话（$ARGUMENTS 换成后面的字）。 */
fun expandSlash(item: SlashItem, args: String): String =
    if ("\$ARGUMENTS" in item.text) item.text.replace("\$ARGUMENTS", args.trim()).trim()
    else item.text.trim() + if (args.isBlank()) "" else "\n\n" + args.trim()

/** 「/」补全列表。 */
@Composable
fun SlashList(items: List<SlashItem>, onPick: (SlashItem) -> Unit) {
    if (items.isEmpty()) return
    androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Ext.c.border),
        shadowElevation = 6.dp, modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 560.dp)) {
        Column(Modifier.padding(4.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            items.forEach { c ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(c) }.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("/" + c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(c.description, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text(c.source, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
            }
        }
    }
}
