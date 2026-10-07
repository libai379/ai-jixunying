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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.model.SearchSource

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Quote(val text: String) : Block
    data class ListItem(val ordered: Boolean, val marker: String, val indent: Int, val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

private fun parseBlocks(src: String): List<Block> {
    val lines = src.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n'); i++
                }
                out += Block.Code(lang, code.toString().trimEnd('\n'))
            }
            t.isEmpty() -> flush()
            Regex("^#{1,6}\\s").containsMatchIn(t) -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                out += Block.Heading(level, t.drop(level).trim())
            }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += Block.Rule }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q.append(lines[i].trim().removePrefix(">").trim()).append('\n'); i++
                }
                out += Block.Quote(q.toString().trim())
                continue
            }
            t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1].trim()) -> {
                flush()
                fun cells(s: String) = s.trim().trim('|').split('|').map { it.trim() }
                val header = cells(t)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += cells(lines[i]); i++
                }
                out += Block.Table(header, rows)
                continue
            }
            Regex("^\\s*([-*+])\\s+").containsMatchIn(line) -> {
                flush()
                val indent = line.takeWhile { it == ' ' }.length / 2
                out += Block.ListItem(false, "•", indent, line.trim().drop(1).trim())
            }
            Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> {
                flush()
                val indent = line.takeWhile { it == ' ' }.length / 2
                val m = Regex("^(\\d+)[.)]\\s+(.*)$").find(line.trim())!!
                out += Block.ListItem(true, m.groupValues[1] + ".", indent, m.groupValues[2])
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line.trimEnd())
            }
        }
        i++
    }
    flush()
    return out
}

@Composable
private fun inline(text: String, sources: List<SearchSource>, base: SpanStyle = SpanStyle()): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = Ext.c.codeBg
    return remember(text, sources, primary) {
        buildAnnotatedString {
            val linkStyle = TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.None))
            val pattern = Regex(
                """(\*\*[^*\n]+?\*\*)|(__[^_\n]+?__)|(`[^`\n]+?`)|(\[[^\]\n]+?\]\((https?://[^)\s]+)\))|(\[\d{1,2}\])|(https?://[^\s)）\]>，。]+)|(@[\p{L}\p{N}_\-]{1,20})|((?<![*\w])\*[^*\n]+?\*(?!\*))|(~~[^~\n]+?~~)"""
            )
            var last = 0
            for (m in pattern.findAll(text)) {
                if (m.range.first < last) continue
                append(text.substring(last, m.range.first))
                val v = m.value
                when {
                    v.startsWith("**") || v.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(v.drop(2).dropLast(2)) }
                    v.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(v.drop(2).dropLast(2)) }
                    v.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(" " + v.drop(1).dropLast(1) + " ") }
                    m.groups[4] != null -> {
                        val label = v.substring(1, v.indexOf("]("))
                        withLink(LinkAnnotation.Url(m.groupValues[5], linkStyle)) { append(label) }
                    }
                    m.groups[6] != null -> {
                        val n = v.trim('[', ']').toInt()
                        val src = sources.getOrNull(n - 1)
                        if (src != null) withLink(LinkAnnotation.Url(src.url, linkStyle)) {
                            withStyle(SpanStyle(fontSize = 11.sp, baselineShift = androidx.compose.ui.text.style.BaselineShift(0.3f), fontWeight = FontWeight.SemiBold)) { append("[$n]") }
                        } else append(v)
                    }
                    m.groups[7] != null -> withLink(LinkAnnotation.Url(v, linkStyle)) { append(v) }
                    m.groups[8] != null -> withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Medium)) { append(v) }
                    m.groups[9] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(v.drop(1).dropLast(1)) }
                    else -> append(v)
                }
                last = m.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, sources: List<SearchSource> = emptyList(), color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { parseBlocks(text) }
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> Text(
                    inline(b.text, sources), style = body.copy(
                        fontSize = when (b.level) { 1 -> 21.sp; 2 -> 18.sp; 3 -> 16.sp; else -> 15.sp },
                        fontWeight = FontWeight.SemiBold, lineHeight = 28.sp,
                    ), modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Para -> Text(inline(b.text, sources), style = body)
                is Block.Code -> CodeBlock(b.lang, b.code)
                is Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).height(22.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(inline(b.text, sources), style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                is Block.ListItem -> Row(Modifier.padding(start = (b.indent * 18).dp)) {
                    Text(b.marker, style = body.copy(color = if (b.ordered) MaterialTheme.colorScheme.primary else Ext.c.subtle, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.widthIn(min = 18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(inline(b.text, sources), style = body)
                }
                is Block.Table -> TableBlock(b, sources, body)
                Block.Rule -> HorizontalDivider(color = Ext.c.border)
            }
        }
    }
}

@Composable
private fun TableBlock(t: Block.Table, sources: List<SearchSource>, body: TextStyle) {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, Ext.c.border, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                for (c in 0 until cols) Text(inline(t.header.getOrElse(c) { "" }, sources), style = body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                    modifier = Modifier.width(160.dp).padding(horizontal = 12.dp, vertical = 8.dp))
            }
            t.rows.forEach { r ->
                HorizontalDivider(color = Ext.c.border)
                Row {
                    for (c in 0 until cols) Text(inline(r.getOrElse(c) { "" }, sources), style = body.copy(fontSize = 14.sp),
                        modifier = Modifier.width(160.dp).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ext.c.codeBg).border(1.dp, Ext.c.border, RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "代码" }, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { clipboard.setText(AnnotatedString(code)) }.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(13.dp), tint = Ext.c.subtle)
                Spacer(Modifier.width(3.dp))
                Text("复制", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 2.dp)) {
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface, softWrap = false)
        }
    }
}

/** 把 <think>…</think> 拆出来当推理过程显示。 */
fun splitThink(content: String): Pair<String, String> {
    val sb = StringBuilder()
    var rest = content
    val thinks = StringBuilder()
    while (true) {
        val s = rest.indexOf("<think>")
        if (s < 0) { sb.append(rest); break }
        sb.append(rest.substring(0, s))
        val e = rest.indexOf("</think>", s)
        if (e < 0) { thinks.append(rest.substring(s + 7)); break }
        thinks.append(rest.substring(s + 7, e))
        rest = rest.substring(e + 8)
    }
    return sb.toString().trim() to thinks.toString().trim()
}
