package com.guixing.jixunying.engine

import com.guixing.jixunying.model.OfficeSettings
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 做 Word / Excel 文件（1.5.0，第 6 件）。手写 OOXML，不用 Apache POI：电脑和手机都能跑，安装包也不变大
 * （读文档的 DocExtract 也是手写的）。元素顺序照 ECMA-376 的 schema 排，Word / WPS / Excel 都认。
 *
 * - Word：Markdown 正文 → .docx。标题（第一个一级标题当文档标题）、多级列表、表格（表头重复、数字列右对齐）、
 *   加粗 / 斜体 / 删除线 / 行内代码 / 链接、代码块、引用、分割线、分页（单独一行 [分页]）、目录、页码、页眉、横向。
 *   三套样式：通用（微软雅黑）、正式（宋体正文黑体标题）、公文（仿宋三号、固定行距 28 磅，接近 GB/T 9704）。
 * - Excel：Markdown 表格 → .xlsx。每个标题下的表格是一个工作表（标题当工作表名），表格前一行字当表名行，表格后的字当备注；
 *   单元格里的数字、千分位、百分比、金额（¥ $）、日期、TRUE/FALSE 自动认成对应类型，「=」开头的是公式；
 *   编号、手机号、身份证号这种（0 开头或 11 位以上）保持文字。表头样式、冻结、筛选、斑马纹、自动列宽、合计行。
 */
object OfficeWriter {

    class Made(val bytes: ByteArray, val text: String, val summary: String)

    // ———————————————— Markdown ————————————————

    sealed interface Md {
        data class Heading(val level: Int, val text: String) : Md
        data class Para(val text: String) : Md
        data class Code(val code: String) : Md
        data class Quote(val text: String) : Md
        data class Item(val ordered: Boolean, val level: Int, val text: String, val number: Int) : Md
        data class Table(val header: List<String>, val aligns: List<Char>, val rows: List<List<String>>) : Md
        data object Rule : Md
        data object PageBreak : Md
    }

    private val pageBreaks = setOf("[分页]", "【分页】", "<!-- pagebreak -->", "<pagebreak>", "<pagebreak/>", "\\pagebreak", "\\newpage", "---分页---")
    private val bulletRe = Regex("""^(\s*)[-*+•]\s+(?:\[[ xX]]\s+)?(.*)$""")
    private val orderedRe = Regex("""^(\s*)(\d{1,4})[.)、]\s*(.*)$""")
    private val sepRe = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

    fun parse(src: String): List<Md> {
        val lines = src.replace("\r\n", "\n").replace('\t', ' ').split('\n')
        val out = mutableListOf<Md>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += Md.Para(para.toString().trim('\n'))
            para.clear()
        }
        fun cells(s: String): List<String> {
            var t = s.trim()
            if (t.startsWith("|")) t = t.drop(1)
            if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
            return t.split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
                    out += Md.Code(code.toString().trimEnd('\n'))
                }
                t.isEmpty() -> flush()
                t.lowercase() in pageBreaks -> { flush(); out += Md.PageBreak }
                Regex("""^#{1,6}\s""").containsMatchIn(t) -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length
                    out += Md.Heading(level, t.drop(level).trim().trimEnd('#').trim())
                }
                Regex("""^(-{3,}|\*{3,}|_{3,})$""").matches(t) -> { flush(); out += Md.Rule }
                t.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().removePrefix(">").trim()).append('\n'); i++ }
                    out += Md.Quote(q.toString().trim())
                    continue
                }
                t.contains('|') && i + 1 < lines.size && sepRe.matches(lines[i + 1].trim()) -> {
                    flush()
                    val header = cells(t)
                    val aligns = cells(lines[i + 1]).map { c ->
                        val l = c.startsWith(":"); val r = c.endsWith(":")
                        if (l && r) 'c' else if (r) 'r' else if (l) 'l' else ' '
                    }
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().let { it.contains('|') && it.isNotEmpty() }) {
                        val r = cells(lines[i])
                        rows += List(header.size) { r.getOrElse(it) { "" } }
                        i++
                    }
                    out += Md.Table(header, List(header.size) { aligns.getOrElse(it) { ' ' } }, rows)
                    continue
                }
                bulletRe.matches(line) -> {
                    flush()
                    val m = bulletRe.find(line)!!
                    out += Md.Item(false, (m.groupValues[1].length / 2).coerceAtMost(3), m.groupValues[2], 0)
                }
                orderedRe.matches(line) && !Regex("""^\s*\d{1,4}[.)]\d""").containsMatchIn(line) -> {
                    flush()
                    val m = orderedRe.find(line)!!
                    out += Md.Item(true, (m.groupValues[1].length / 2).coerceAtMost(3), m.groupValues[3], m.groupValues[2].toInt())
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line.trim())
                }
            }
            i++
        }
        flush()
        return out
    }

    /** 一段字里的样式：加粗、斜体、删除线、行内代码、链接。 */
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false,
                   val code: Boolean = false, val link: String? = null)

    private val inlineRe = Regex(
        """(\*\*\*[^*\n]+?\*\*\*)|(\*\*[^*\n]+?\*\*)|(__[^_\n]+?__)|(`[^`\n]+?`)|(\[[^\]\n]+?]\((https?://[^)\s]+)\))|(~~[^~\n]+?~~)|((?<![*\w])\*[^*\n]+?\*(?!\*))|(https?://[^\s)）\]>，。、]+)"""
    )

    fun runs(text: String, bold: Boolean = false): List<Run> {
        val src = text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        val out = mutableListOf<Run>()
        var last = 0
        for (m in inlineRe.findAll(src)) {
            if (m.range.first < last) continue
            if (m.range.first > last) out += Run(src.substring(last, m.range.first), bold = bold)
            val v = m.value
            out += when {
                m.groups[1] != null -> Run(v.drop(3).dropLast(3), bold = true, italic = true)
                m.groups[2] != null || m.groups[3] != null -> Run(v.drop(2).dropLast(2), bold = true)
                m.groups[4] != null -> Run(v.drop(1).dropLast(1), code = true, bold = bold)
                m.groups[5] != null -> Run(v.substring(1, v.indexOf("](")), link = m.groupValues[6], bold = bold)
                m.groups[7] != null -> Run(v.drop(2).dropLast(2), strike = true, bold = bold)
                m.groups[8] != null -> Run(v.drop(1).dropLast(1), italic = true, bold = bold)
                else -> Run(v, link = v, bold = bold)
            }
            last = m.range.last + 1
        }
        if (last < src.length) out += Run(src.substring(last), bold = bold)
        return out
    }

    /** 去掉 Markdown 记号的纯文字（Excel 单元格、目录、文件名用）。 */
    fun plain(text: String) = runs(text).joinToString("") { it.text }

    // ———————————————— 共用 ————————————————

    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun zip(parts: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, xml) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(xml.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    internal const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    internal const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    internal const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    internal fun coreXml(title: String): String {
        val now = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_INSTANT)
        return XML + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            "<dc:title>${esc(title)}</dc:title><dc:creator>AI集训营</dc:creator><cp:lastModifiedBy>AI集训营</cp:lastModifiedBy>" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }

    internal fun appXml() = XML + """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>AI集训营</Application></Properties>"""

    internal fun rootRels(main: String) = XML + """<Relationships xmlns="$RELS_NS">""" +
        """<Relationship Id="rId1" Type="$R_NS/officeDocument" Target="$main"/>""" +
        """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """<Relationship Id="rId3" Type="$R_NS/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    // ———————————————— Word ————————————————

    /** AI 这次另外指定的（没指定就按设置）。 */
    data class WordOptions(
        val title: String = "",
        val style: String = "",
        val header: String = "",
        val landscape: Boolean = false,
        val toc: Boolean? = null,
        val firstLineIndent: Boolean? = null,
    )

    /** 一套 Word 样式。字号是半磅（21 = 10.5 磅 = 五号）。 */
    private class Look(
        val latin: String, val body: String, val size: Int,
        val headLatin: String, val heads: List<String>, val headSizes: List<Int>, val headBold: List<Boolean>,
        val titleFont: String, val titleSize: Int,
        /** 行距：auto 时是 240 的倍数，exact 时是缇（1/20 磅）。 */
        val line: Int, val exact: Boolean, val after: Int,
        val margins: List<Int>, val indentDefault: Boolean, val tableSize: Int, val pageFormat: String,
    )

    private fun look(style: String) = when (style) {
        // 正式：宋体小四正文、黑体标题、1.5 倍行距、首行缩进
        "formal" -> Look("Times New Roman", "宋体", 24, "Times New Roman", listOf("黑体", "黑体", "黑体", "黑体"), listOf(32, 30, 28, 24),
            listOf(false, false, false, false), "黑体", 36, 360, false, 0, listOf(1440, 1800, 1440, 1800), true, 21, "page")
        // 公文（接近 GB/T 9704-2012）：仿宋三号正文、固定行距 28 磅、一级黑体二级楷体三级仿宋加粗、标题小标宋二号、页边距 3.7/2.6/3.5/2.8 厘米
        "official" -> Look("Times New Roman", "仿宋", 32, "Times New Roman", listOf("黑体", "楷体", "仿宋", "仿宋"), listOf(32, 32, 32, 32),
            listOf(false, false, true, false), "方正小标宋简体", 44, 560, true, 0, listOf(2098, 1474, 1984, 1587), true, 24, "dash")
        // 通用：微软雅黑五号、1.5 倍行距、段后 6 磅、不缩进
        else -> Look("Microsoft YaHei", "微软雅黑", 21, "Microsoft YaHei", listOf("微软雅黑", "微软雅黑", "微软雅黑", "微软雅黑"), listOf(32, 28, 24, 22),
            listOf(true, true, true, true), "微软雅黑", 40, 360, false, 120, listOf(1440, 1800, 1440, 1800), false, 20, "page")
    }

    private fun fonts(latin: String, ea: String) = """<w:rFonts w:ascii="${esc(latin)}" w:hAnsi="${esc(latin)}" w:eastAsia="${esc(ea)}" w:cs="${esc(latin)}"/>"""

    fun word(markdown: String, s: OfficeSettings, o: WordOptions = WordOptions()): Made {
        val styleId = o.style.ifBlank { s.wordStyle }
        val lk = look(styleId)
        // AI 这次指定了别的样式（比如设置是通用、用户说「用公文格式」）：缩进跟那套样式走
        val indent = o.firstLineIndent ?: if (o.style.isNotBlank() && o.style != s.wordStyle) lk.indentDefault else s.firstLineIndent
        var blocks = parse(markdown)
        // 标题：AI 给了就用；没给、而且只有开头一个一级标题，就把它当文档标题
        var title = plain(o.title).trim()
        val h1s = blocks.filterIsInstance<Md.Heading>().filter { it.level == 1 }
        if (title.isEmpty() && h1s.size == 1 && blocks.firstOrNull() == h1s[0]) {
            title = plain(h1s[0].text).trim()
            blocks = blocks.drop(1)
        } else if (title.isNotEmpty() && (blocks.firstOrNull() as? Md.Heading)?.let { plain(it.text).trim() == title } == true) {
            blocks = blocks.drop(1)
        }
        // 标题级别往上提：AI 常常不用一级标题，直接从 ## 开始
        val minLevel = blocks.filterIsInstance<Md.Heading>().minOfOrNull { it.level } ?: 1
        val shift = (minLevel - 1).coerceAtLeast(0)
        fun lvl(h: Md.Heading) = (h.level - shift).coerceIn(1, 4)
        val headings = blocks.filterIsInstance<Md.Heading>()
        // 自动目录：长文档（3000 字以上）、标题 4 个以上才加，短的加了反而多一页
        val toc = o.toc ?: (s.autoToc && headings.size >= 4 && markdown.length >= 3000)

        val links = mutableListOf<String>()
        val nums = mutableListOf<Int>()   // 每个有序列表一个编号实例（起始号）
        val body = StringBuilder()

        fun rPr(r: Run, base: String = "") = buildString {
            val inner = buildString {
                if (r.code) append(fonts("Consolas", lk.body))
                if (r.bold) append("<w:b/><w:bCs/>")
                if (r.italic) append("<w:i/><w:iCs/>")
                if (r.strike) append("<w:strike/>")
                if (r.link != null) append("""<w:color w:val="0563C1"/>""")
                append(base)
                if (r.link != null) append("""<w:u w:val="single"/>""")
                if (r.code) append("""<w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/>""")
            }
            if (inner.isNotEmpty()) append("<w:rPr>").append(inner).append("</w:rPr>")
        }

        fun textRuns(text: String, base: String = "", forceBold: Boolean = false): String = buildString {
            for (r in runs(text, forceBold)) {
                val pieces = r.text.split('\n')
                val runXml = buildString {
                    append("<w:r>").append(rPr(r, base))
                    pieces.forEachIndexed { k, p ->
                        if (k > 0) append("<w:br/>")
                        if (p.isNotEmpty()) append("""<w:t xml:space="preserve">""").append(esc(p)).append("</w:t>")
                    }
                    append("</w:r>")
                }
                if (r.link != null) {
                    links += r.link
                    append("""<w:hyperlink r:id="rIdL${links.size}" w:history="1">""").append(runXml).append("</w:hyperlink>")
                } else append(runXml)
            }
        }

        val noIndent = """<w:ind w:firstLineChars="0" w:firstLine="0"/>"""
        fun para(text: String, ppr: String = "", base: String = "") {
            body.append("<w:p>")
            if (ppr.isNotEmpty()) body.append("<w:pPr>").append(ppr).append("</w:pPr>")
            body.append(textRuns(text, base)).append("</w:p>")
        }

        // 文档标题
        if (title.isNotEmpty()) body.append("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr>""").append(textRuns(title)).append("</w:p>")
        // 目录：先把标题列出来（WPS 也能看到），Word 打开时更新出页码
        if (toc && headings.isNotEmpty()) {
            body.append("""<w:p><w:pPr><w:pStyle w:val="TOCHeading"/></w:pPr><w:r><w:t>目录</w:t></w:r></w:p>""")
            body.append("""<w:p><w:pPr><w:pStyle w:val="TOC1"/></w:pPr><w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r>""" +
                """<w:r><w:instrText xml:space="preserve"> TOC \o "1-3" \h \z \u </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>""")
            headings.filter { lvl(it) <= 3 }.forEachIndexed { k, h ->
                if (k > 0) body.append("""<w:p><w:pPr><w:pStyle w:val="TOC${lvl(h)}"/></w:pPr>""")
                body.append("<w:r><w:t xml:space=\"preserve\">").append(esc(plain(h.text))).append("</w:t></w:r>")
                body.append("</w:p>")
            }
            body.append("""<w:p><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""")
            body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
        }

        var orderedOpen = false
        for (b in blocks) {
            if (b !is Md.Item) orderedOpen = false
            when (b) {
                is Md.Heading -> body.append("""<w:p><w:pPr><w:pStyle w:val="Heading${lvl(b)}"/></w:pPr>""").append(textRuns(b.text)).append("</w:p>")
                is Md.Para -> para(b.text)
                is Md.Quote -> para(b.text,
                    """<w:pStyle w:val="Quote"/>""")
                is Md.Code -> b.code.split('\n').forEach { line ->
                    body.append("""<w:p><w:pPr><w:pStyle w:val="CodeBlock"/></w:pPr><w:r><w:t xml:space="preserve">""").append(esc(line)).append("</w:t></w:r></w:p>")
                }
                is Md.Rule -> body.append("""<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="BFBFBF"/></w:pBdr>$noIndent</w:pPr></w:p>""")
                is Md.PageBreak -> body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
                is Md.Item -> {
                    // 每个数字列表各自从头编（从第一项写的号开始）；中间夹着圆点子项不算断开
                    val numId = if (b.ordered) {
                        if (!orderedOpen) { nums += b.number.coerceAtLeast(1); orderedOpen = true }
                        1 + nums.size
                    } else 1
                    val left = 420 * (b.level + 1)
                    para(b.text, """<w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="${b.level}"/><w:numId w:val="$numId"/></w:numPr>""" +
                        """<w:ind w:left="$left" w:hanging="420" w:firstLineChars="0"/>""")
                }
                is Md.Table -> body.append(wordTable(b, lk, ::textRuns)).append("""<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>$noIndent</w:pPr></w:p>""")
            }
        }

        val hasHeader = o.header.isNotBlank() || (s.headerTitle && title.isNotEmpty())
        val headerText = o.header.ifBlank { title }
        val pg = if (o.landscape) """<w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>""" else """<w:pgSz w:w="11906" w:h="16838"/>"""
        val (mt, mr, mb, ml) = lk.margins   // 上、右、下、左
        val sect = buildString {
            append("<w:sectPr>")
            if (hasHeader) append("""<w:headerReference w:type="default" r:id="rIdH"/>""")
            if (s.pageNumbers) append("""<w:footerReference w:type="default" r:id="rIdF"/>""")
            append(pg)
            append("""<w:pgMar w:top="$mt" w:right="$mr" w:bottom="$mb" w:left="$ml" w:header="851" w:footer="992" w:gutter="0"/>""")
            append("</w:sectPr>")
        }
        val doc = XML + """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="$R_NS"><w:body>""" +
            body + sect + "</w:body></w:document>"

        val rels = buildString {
            append(XML).append("""<Relationships xmlns="$RELS_NS">""")
            append("""<Relationship Id="rIdS" Type="$R_NS/styles" Target="styles.xml"/>""")
            append("""<Relationship Id="rIdT" Type="$R_NS/settings" Target="settings.xml"/>""")
            append("""<Relationship Id="rIdN" Type="$R_NS/numbering" Target="numbering.xml"/>""")
            if (hasHeader) append("""<Relationship Id="rIdH" Type="$R_NS/header" Target="header1.xml"/>""")
            if (s.pageNumbers) append("""<Relationship Id="rIdF" Type="$R_NS/footer" Target="footer1.xml"/>""")
            links.forEachIndexed { k, u -> append("""<Relationship Id="rIdL${k + 1}" Type="$R_NS/hyperlink" Target="${esc(u)}" TargetMode="External"/>""") }
            append("</Relationships>")
        }
        val ct = buildString {
            append(XML).append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            val wml = "application/vnd.openxmlformats-officedocument.wordprocessingml"
            append("""<Override PartName="/word/document.xml" ContentType="$wml.document.main+xml"/>""")
            append("""<Override PartName="/word/styles.xml" ContentType="$wml.styles+xml"/>""")
            append("""<Override PartName="/word/settings.xml" ContentType="$wml.settings+xml"/>""")
            append("""<Override PartName="/word/numbering.xml" ContentType="$wml.numbering+xml"/>""")
            if (hasHeader) append("""<Override PartName="/word/header1.xml" ContentType="$wml.header+xml"/>""")
            if (s.pageNumbers) append("""<Override PartName="/word/footer1.xml" ContentType="$wml.footer+xml"/>""")
            append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
            append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
            append("</Types>")
        }
        val parts = mutableListOf(
            "[Content_Types].xml" to ct,
            "_rels/.rels" to rootRels("word/document.xml"),
            "word/document.xml" to doc,
            "word/_rels/document.xml.rels" to rels,
            "word/styles.xml" to wordStyles(lk, indent),
            "word/settings.xml" to wordSettings(toc),
            "word/numbering.xml" to numbering(lk, nums),
            "docProps/core.xml" to coreXml(title),
            "docProps/app.xml" to appXml(),
        )
        val W = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="$R_NS""""
        if (hasHeader) parts += "word/header1.xml" to XML + """<w:hdr $W><w:p><w:pPr><w:pStyle w:val="Header"/></w:pPr><w:r><w:t xml:space="preserve">${esc(plain(headerText))}</w:t></w:r></w:p></w:hdr>"""
        if (s.pageNumbers) {
            fun fld(code: String) = """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> $code </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>"""
            fun t(s: String) = """<w:r><w:t xml:space="preserve">$s</w:t></w:r>"""
            val inner = if (lk.pageFormat == "dash") t("— ") + fld("PAGE") + t(" —") else t("第 ") + fld("PAGE") + t(" 页，共 ") + fld("NUMPAGES") + t(" 页")
            parts += "word/footer1.xml" to XML + """<w:ftr $W><w:p><w:pPr><w:pStyle w:val="Footer"/></w:pPr>$inner</w:p></w:ftr>"""
        }
        val headCount = headings.size
        val tables = blocks.count { it is Md.Table }
        val summary = buildList {
            add(when (styleId) { "formal" -> "正式样式"; "official" -> "公文样式"; else -> "通用样式" })
            if (headCount > 0) add("$headCount 个标题")
            if (tables > 0) add("$tables 张表")
            if (toc && headings.isNotEmpty()) add("带目录")
            if (o.landscape) add("横向")
        }.joinToString("，")
        val text = (if (title.isNotEmpty()) "$title\n\n" else "") + markdown
        return Made(zip(parts), text, summary)
    }

    private fun wordTable(t: Md.Table, lk: Look, textRuns: (String, String, Boolean) -> String): String {
        val cols = t.header.size.coerceAtLeast(1)
        // 全是数字的列靠右（Markdown 里写了对齐的照写的）
        val numeric = (0 until cols).map { c ->
            val vals = t.rows.map { it.getOrElse(c) { "" }.trim() }.filter { it.isNotEmpty() && it != "-" && it != "—" }
            vals.isNotEmpty() && vals.all { cellValue(plain(it)) is CellVal.Num }
        }
        // 日期列居中
        val dates = (0 until cols).map { c ->
            val vals = t.rows.map { it.getOrElse(c) { "" }.trim() }.filter { it.isNotEmpty() }
            vals.isNotEmpty() && vals.all { (cellValue(plain(it)) as? CellVal.Num)?.fmt == "yyyy-mm-dd" }
        }
        fun jc(c: Int) = when (t.aligns.getOrElse(c) { ' ' }) { 'c' -> "center"; 'r' -> "right"; 'l' -> "left"; else -> if (dates[c]) "center" else if (numeric[c]) "right" else "left" }
        val sz = """<w:sz w:val="${lk.tableSize}"/><w:szCs w:val="${lk.tableSize}"/>"""
        val cellPpr = """<w:spacing w:before="0" w:after="0" w:line="276" w:lineRule="auto"/><w:ind w:firstLineChars="0" w:firstLine="0"/>"""
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr>")
        sb.append("""<w:tblW w:w="5000" w:type="pct"/><w:jc w:val="center"/>""")
        sb.append("<w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { sb.append("""<w:$it w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>""") }
        sb.append("</w:tblBorders>")
        sb.append("""<w:tblLayout w:type="autofit"/><w:tblCellMar><w:top w:w="57" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="57" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar>""")
        sb.append("</w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("""<w:gridCol w:w="${9000 / cols}"/>""") }
        sb.append("</w:tblGrid>")
        // 表头：加粗、浅底色、跨页时每页重复
        sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
        t.header.forEachIndexed { c, h ->
            sb.append("""<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/><w:shd w:val="clear" w:color="auto" w:fill="DEE6F2"/><w:vAlign w:val="center"/></w:tcPr>""")
            sb.append("""<w:p><w:pPr>$cellPpr<w:jc w:val="${if (numeric[c]) jc(c) else "center"}"/></w:pPr>""").append(textRuns(h, sz, true)).append("</w:p></w:tc>")
        }
        sb.append("</w:tr>")
        for (row in t.rows) {
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                sb.append("""<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/><w:vAlign w:val="center"/></w:tcPr>""")
                sb.append("""<w:p><w:pPr>$cellPpr<w:jc w:val="${jc(c)}"/></w:pPr>""").append(textRuns(row.getOrElse(c) { "" }, sz, false)).append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun wordStyles(lk: Look, indent: Boolean): String {
        val ind = if (indent) """<w:ind w:firstLineChars="200" w:firstLine="${lk.size * 20}"/>""" else ""
        val spacing = """<w:spacing w:before="0" w:after="${lk.after}" w:line="${lk.line}" w:lineRule="${if (lk.exact) "exact" else "auto"}"/>"""
        val sb = StringBuilder(XML)
        sb.append("""<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        sb.append("<w:docDefaults><w:rPrDefault><w:rPr>").append(fonts(lk.latin, lk.body))
        sb.append("""<w:kern w:val="2"/><w:sz w:val="${lk.size}"/><w:szCs w:val="${lk.size}"/><w:lang w:val="en-US" w:eastAsia="zh-CN" w:bidi="ar-SA"/>""")
        sb.append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>").append(spacing).append("</w:pPr></w:pPrDefault></w:docDefaults>")
        fun style(id: String, name: String, ppr: String, rpr: String, type: String = "paragraph", extra: String = "") {
            sb.append("""<w:style w:type="$type" w:styleId="$id"${if (id == "Normal") " w:default=\"1\"" else ""}><w:name w:val="$name"/>""")
            if (id != "Normal" && type == "paragraph") sb.append("""<w:basedOn w:val="Normal"/>""")
            sb.append(extra)
            sb.append("<w:qFormat/>")
            if (ppr.isNotEmpty()) sb.append("<w:pPr>").append(ppr).append("</w:pPr>")
            if (rpr.isNotEmpty()) sb.append("<w:rPr>").append(rpr).append("</w:rPr>")
            sb.append("</w:style>")
        }
        style("Normal", "Normal", """<w:widowControl/>$spacing$ind<w:jc w:val="both"/>""", "")
        val titleSpacing = """<w:spacing w:before="240" w:after="360" w:line="${if (lk.exact) lk.line + 120 else 240}" w:lineRule="${if (lk.exact) "exact" else "auto"}"/>"""
        style("Title", "Title", """<w:keepNext/>$titleSpacing<w:ind w:firstLineChars="0" w:firstLine="0"/><w:jc w:val="center"/><w:outlineLvl w:val="0"/>""",
            fonts(lk.headLatin, lk.titleFont) + """<w:b/><w:bCs/><w:sz w:val="${lk.titleSize}"/><w:szCs w:val="${lk.titleSize}"/>""", extra = """<w:next w:val="Normal"/>""")
        for (k in 1..4) {
            val before = if (lk.exact) 0 else listOf(360, 240, 200, 160)[k - 1]
            val after = if (lk.exact) 0 else listOf(200, 160, 120, 80)[k - 1]
            val line = if (lk.exact) """w:line="${lk.line}" w:lineRule="exact"""" else """w:line="${if (k == 1) 300 else 276}" w:lineRule="auto""""
            // 公文的标题跟正文一样首行缩进两个字（一、（一）这种）；其他样式的标题顶格
            val hInd = if (lk.exact) """<w:ind w:firstLineChars="200" w:firstLine="${lk.size * 20}"/>""" else """<w:ind w:firstLineChars="0" w:firstLine="0"/>"""
            style("Heading$k", "heading $k", """<w:keepNext/><w:keepLines/><w:spacing w:before="$before" w:after="$after" $line/>$hInd<w:jc w:val="left"/><w:outlineLvl w:val="${k - 1}"/>""",
                fonts(lk.headLatin, lk.heads[k - 1]) + (if (lk.headBold[k - 1]) "<w:b/><w:bCs/>" else "") +
                    (if (!lk.exact) """<w:color w:val="1F2937"/>""" else "") + """<w:sz w:val="${lk.headSizes[k - 1]}"/><w:szCs w:val="${lk.headSizes[k - 1]}"/>""",
                extra = """<w:next w:val="Normal"/>""")
        }
        style("ListParagraph", "List Paragraph", """<w:spacing w:before="0" w:after="${lk.after / 2}"/><w:ind w:left="420" w:firstLineChars="0" w:firstLine="0"/>""", "")
        style("Quote", "Quote", """<w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="BFBFBF"/></w:pBdr><w:ind w:left="420" w:right="420" w:firstLineChars="0" w:firstLine="0"/>""",
            """<w:color w:val="595959"/>""")
        style("CodeBlock", "Code Block", """<w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/><w:spacing w:before="0" w:after="0" w:line="260" w:lineRule="auto"/><w:ind w:left="210" w:firstLineChars="0" w:firstLine="0"/><w:jc w:val="left"/>""",
            fonts("Consolas", lk.body) + """<w:sz w:val="18"/><w:szCs w:val="18"/>""")
        style("TOCHeading", "TOC Heading", """<w:spacing w:before="240" w:after="240"/><w:ind w:firstLineChars="0" w:firstLine="0"/><w:jc w:val="center"/>""",
            fonts(lk.headLatin, lk.heads[0]) + """<w:b/><w:sz w:val="${lk.headSizes[0]}"/><w:szCs w:val="${lk.headSizes[0]}"/>""")
        for (k in 1..3) style("TOC$k", "toc $k", """<w:tabs><w:tab w:val="right" w:leader="dot" w:pos="9000"/></w:tabs><w:spacing w:before="0" w:after="60"/><w:ind w:left="${(k - 1) * 420}" w:firstLineChars="0" w:firstLine="0"/>""", "")
        style("Header", "header", """<w:pBdr><w:bottom w:val="single" w:sz="4" w:space="1" w:color="BFBFBF"/></w:pBdr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:ind w:firstLineChars="0" w:firstLine="0"/><w:jc w:val="center"/>""",
            """<w:color w:val="7F7F7F"/><w:sz w:val="18"/><w:szCs w:val="18"/>""")
        style("Footer", "footer", """<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:ind w:firstLineChars="0" w:firstLine="0"/><w:jc w:val="center"/>""",
            if (lk.pageFormat == "dash") fonts("Times New Roman", "宋体") + """<w:sz w:val="28"/><w:szCs w:val="28"/>""" else """<w:color w:val="7F7F7F"/><w:sz w:val="18"/><w:szCs w:val="18"/>""")
        sb.append("</w:styles>")
        return sb.toString()
    }

    private fun wordSettings(toc: Boolean) = XML + """<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
        """<w:zoom w:percent="100"/><w:defaultTabStop w:val="420"/><w:characterSpacingControl w:val="compressPunctuation"/>""" +
        (if (toc) """<w:updateFields w:val="true"/>""" else "") +
        """<w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>"""

    /** 编号：0 号是圆点列表，1 号是数字列表（每个数字列表一个实例，各自从头编）。 */
    private fun numbering(lk: Look, nums: List<Int>): String {
        val sb = StringBuilder(XML)
        sb.append("""<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        val bullets = listOf("•", "◦", "▪", "•")
        sb.append("""<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""")
        bullets.forEachIndexed { l, b ->
            sb.append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="$b"/><w:lvlJc w:val="left"/>""")
            sb.append("""<w:pPr><w:ind w:left="${420 * (l + 1)}" w:hanging="420"/></w:pPr><w:rPr>${fonts(lk.latin, lk.body)}</w:rPr></w:lvl>""")
        }
        sb.append("</w:abstractNum>")
        val fmts = listOf("decimal" to "%1.", "decimal" to "(%2)", "lowerLetter" to "%3.", "lowerRoman" to "%4.")
        sb.append("""<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""")
        fmts.forEachIndexed { l, (f, t) ->
            sb.append("""<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="$f"/><w:lvlText w:val="$t"/><w:lvlJc w:val="left"/>""")
            sb.append("""<w:pPr><w:ind w:left="${420 * (l + 1)}" w:hanging="420"/></w:pPr></w:lvl>""")
        }
        sb.append("</w:abstractNum>")
        sb.append("""<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>""")
        nums.forEachIndexed { k, start ->
            sb.append("""<w:num w:numId="${k + 2}"><w:abstractNumId w:val="1"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="$start"/></w:lvlOverride></w:num>""")
        }
        sb.append("</w:numbering>")
        return sb.toString()
    }

    // ———————————————— Excel ————————————————

    sealed interface CellVal {
        data class Text(val s: String) : CellVal
        data class Num(val v: Double, val fmt: String) : CellVal
        data class Formula(val f: String) : CellVal
        data class Bool(val b: Boolean) : CellVal
        data object Empty : CellVal
    }

    private val numRe = Regex("""^[+-]?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?$""")
    private val pctRe = Regex("""^[+-]?\d+(\.\d+)?%$""")
    private val moneyRe = Regex("""^([¥￥$])\s?([+-]?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?)$""")
    private val dateRe = Regex("""^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?$""")

    /** 单元格里的字是什么：数字（带格式）、公式、日期、布尔，还是就是文字。 */
    fun cellValue(raw: String, thousands: Boolean = true): CellVal {
        val s = raw.trim()
        if (s.isEmpty()) return CellVal.Empty
        if (s.startsWith("=") && s.length > 1) return CellVal.Formula(s.drop(1))
        if (s.equals("TRUE", true) || s.equals("FALSE", true)) return CellVal.Bool(s.equals("TRUE", true))
        pctRe.find(s)?.let {
            val d = s.dropLast(1).substringAfter('.', "").length
            return CellVal.Num(s.dropLast(1).toDouble() / 100, if (d == 0) "0%" else "0." + "0".repeat(d.coerceAtMost(4)) + "%")
        }
        moneyRe.find(s)?.let { m ->
            val v = m.groupValues[2].replace(",", "").toDouble()
            val sym = if (m.groupValues[1] == "$") "\"$\"" else "\"¥\""
            return CellVal.Num(v, "$sym#,##0.00")
        }
        dateRe.find(s)?.let { m ->
            val d = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
            if (d != null) return CellVal.Num(ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d).toDouble(), "yyyy-mm-dd")
        }
        if (numRe.matches(s)) {
            val digits = s.trimStart('+', '-').substringBefore('.').replace(",", "")
            // 编号、手机号、身份证号：0 开头或 11 位以上的整数照文字存（不然 0 没了、后几位变 0）
            if ((digits.length > 1 && digits.startsWith("0") && !s.contains(',')) || digits.length >= 11) return CellVal.Text(s)
            val v = s.replace(",", "").toDouble()
            val dec = s.substringAfter('.', "").length
            val grouped = s.contains(',') || (thousands && kotlin.math.abs(v) >= 1000)
            val fmt = when {
                grouped -> if (dec == 0) "#,##0" else "#,##0." + "0".repeat(dec.coerceAtMost(4))
                dec > 0 -> "0." + "0".repeat(dec.coerceAtMost(6))
                else -> "General"
            }
            return CellVal.Num(v, fmt)
        }
        return CellVal.Text(s)
    }

    class Sheet(val name: String, val title: String, val header: List<String>, val rows: List<List<String>>, val notes: List<String>)

    /** 把 Markdown 拆成工作表：每个标题下的每张表一个；表前一段字当表名行，表后的字当备注。 */
    fun sheets(markdown: String): List<Sheet> {
        val out = mutableListOf<Sheet>()
        var heading = ""
        val before = mutableListOf<String>()
        var lastTable: Triple<String, String, Md.Table>? = null
        val afterNotes = mutableListOf<String>()
        fun close() {
            lastTable?.let { (name, title, t) -> out += Sheet(name, title, t.header.map(::plain), t.rows.map { r -> r }, afterNotes.toList()) }
            lastTable = null
            afterNotes.clear()
        }
        for (b in parse(markdown)) {
            when (b) {
                is Md.Heading -> { close(); heading = plain(b.text).trim(); before.clear() }
                is Md.Table -> {
                    close()
                    lastTable = Triple(heading, before.joinToString(" ").trim(), b)
                    before.clear()
                }
                is Md.Para, is Md.Quote, is Md.Item -> {
                    val text = plain(when (b) { is Md.Para -> b.text; is Md.Quote -> b.text; is Md.Item -> b.text; else -> "" }).trim()
                    if (lastTable != null) afterNotes += text else before += text
                }
                else -> Unit
            }
        }
        close()
        // 工作表名：最多 31 个字，不能有 []:*?/\，不能重名
        val used = mutableSetOf<String>()
        return out.mapIndexed { k, sh ->
            var base = sh.name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().take(31).ifEmpty { "表${k + 1}" }
            var name = base
            var n = 2
            while (!used.add(name.lowercase())) { name = base.take(28) + "($n)"; n++ }
            Sheet(name, sh.title, sh.header, sh.rows, sh.notes)
        }
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** 显示宽度：中文算两个。 */
    internal fun width(s: String) = s.sumOf { if (it.code > 0x2E80) 2.0 else 1.0 }

    fun excel(markdown: String, s: OfficeSettings, totalRow: Boolean = false): Made {
        val sheets = sheets(markdown)
        require(sheets.isNotEmpty()) { "没找到表格：Excel 的内容要写成 Markdown 表格（| 列 | 列 |，第二行 |---|---|）" }

        // 样式表：字体 0 正文 1 加粗 2 表名（加粗 14） 3 备注（灰色）；填充 0 1 固定，2 表头 3 斑马纹 4 合计行；边框 0 无 1 细线
        val numFmts = linkedMapOf<String, Int>()
        val builtin = mapOf("General" to 0, "0" to 1, "0.00" to 2, "#,##0" to 3, "#,##0.00" to 4, "0%" to 9, "0.00%" to 10)
        fun fmtId(f: String) = builtin[f] ?: numFmts.getOrPut(f) { 164 + numFmts.size }
        data class Xf(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val h: String, val wrap: Boolean)
        val xfs = mutableListOf(Xf(0, 0, 0, 0, "", false))
        fun xf(x: Xf): Int { val i = xfs.indexOf(x); if (i >= 0) return i; xfs += x; return xfs.size - 1 }

        val sheetXml = mutableListOf<String>()
        val filters = mutableListOf<String>()
        val textOut = StringBuilder()
        for ((si, sh) in sheets.withIndex()) {
            val cols = sh.header.size.coerceAtLeast(1)
            val rowsXml = StringBuilder()
            val merges = mutableListOf<String>()
            val widths = DoubleArray(cols) { 6.0 }
            var r = 0
            if (sh.title.isNotEmpty()) {
                r++
                rowsXml.append("""<row r="$r" ht="26" customHeight="1"><c r="A$r" s="${xf(Xf(0, 2, 0, 0, "center", false))}" t="inlineStr"><is><t xml:space="preserve">${esc(sh.title)}</t></is></c></row>""")
                if (cols > 1) merges += "A$r:${colName(cols - 1)}$r"
            }
            r++
            val headerRow = r
            val hStyle = if (s.excelHeaderStyle) xf(Xf(0, 1, 2, 1, "center", true)) else xf(Xf(0, 0, 0, 1, "", true))
            rowsXml.append("""<row r="$r">""")
            sh.header.forEachIndexed { c, h ->
                widths[c] = maxOf(widths[c], width(h) + 2)
                rowsXml.append("""<c r="${colName(c)}$r" s="$hStyle" t="inlineStr"><is><t xml:space="preserve">${esc(h)}</t></is></c>""")
            }
            rowsXml.append("</row>")
            // 每列第一个数字的格式：这一列的公式、合计跟着用
            val colFmt = arrayOfNulls<String>(cols)
            for (row in sh.rows) for (c in 0 until cols) {
                if (colFmt[c] == null) (cellValue(plain(row.getOrElse(c) { "" }), s.excelThousands) as? CellVal.Num)?.let { colFmt[c] = it.fmt }
            }
            fun fmtOf(c: Int) = colFmt[c]?.takeIf { it != "yyyy-mm-dd" } ?: if (s.excelThousands) "#,##0" else "General"
            // 能合计的列：有数字（日期、百分比不算，百分比加起来没意义）或者有公式
            val summable = BooleanArray(cols) { c ->
                sh.rows.any { row ->
                    when (val v = cellValue(plain(row.getOrElse(c) { "" }), s.excelThousands)) {
                        is CellVal.Num -> v.fmt != "yyyy-mm-dd" && !v.fmt.endsWith("%")
                        is CellVal.Formula -> colFmt[c]?.let { it != "yyyy-mm-dd" && !it.endsWith("%") } ?: true
                        else -> false
                    }
                }
            }
            for ((ri, row) in sh.rows.withIndex()) {
                r++
                val zebra = s.excelZebra && ri % 2 == 1
                rowsXml.append("""<row r="$r">""")
                for (c in 0 until cols) {
                    val rawCell = row.getOrElse(c) { "" }
                    val bold = Regex("""^\s*(\*\*|__).+(\*\*|__)\s*$""").matches(rawCell)
                    val text = plain(rawCell).trim()
                    val v = cellValue(text, s.excelThousands)
                    val ref = "${colName(c)}$r"
                    val font = if (bold) 1 else 0
                    val fill = if (zebra) 3 else 0
                    when (v) {
                        is CellVal.Num -> {
                            widths[c] = maxOf(widths[c], width(text) + 3)
                            rowsXml.append("""<c r="$ref" s="${xf(Xf(fmtId(v.fmt), font, fill, 1, "right", false))}"><v>${num(v.v)}</v></c>""")
                        }
                        is CellVal.Formula -> {
                            widths[c] = maxOf(widths[c], 12.0)
                            rowsXml.append("""<c r="$ref" s="${xf(Xf(fmtId(fmtOf(c)), font, fill, 1, "right", false))}"><f>${esc(shiftRows(v.f, if (sh.title.isNotEmpty()) 1 else 0))}</f></c>""")
                        }
                        is CellVal.Bool -> rowsXml.append("""<c r="$ref" s="${xf(Xf(0, font, fill, 1, "center", false))}" t="b"><v>${if (v.b) 1 else 0}</v></c>""")
                        is CellVal.Text -> {
                            val long = width(v.s) > 50
                            widths[c] = maxOf(widths[c], minOf(width(v.s) + 2, 52.0))
                            rowsXml.append("""<c r="$ref" s="${xf(Xf(0, font, fill, 1, "", long))}" t="inlineStr"><is><t xml:space="preserve">${esc(v.s)}</t></is></c>""")
                        }
                        CellVal.Empty -> rowsXml.append("""<c r="$ref" s="${xf(Xf(0, font, fill, 1, "", false))}"/>""")
                    }
                }
                rowsXml.append("</row>")
            }
            val lastData = r
            if (totalRow && sh.rows.isNotEmpty() && summable.any { it }) {
                r++
                rowsXml.append("""<row r="$r">""")
                for (c in 0 until cols) {
                    val ref = "${colName(c)}$r"
                    when {
                        c == 0 && !summable[0] -> rowsXml.append("""<c r="$ref" s="${xf(Xf(0, 1, 4, 1, "center", false))}" t="inlineStr"><is><t>合计</t></is></c>""")
                        summable[c] -> rowsXml.append("""<c r="$ref" s="${xf(Xf(fmtId(fmtOf(c)), 1, 4, 1, "right", false))}"><f>SUM(${colName(c)}${headerRow + 1}:${colName(c)}$lastData)</f></c>""")

                        else -> rowsXml.append("""<c r="$ref" s="${xf(Xf(0, 1, 4, 1, "", false))}"/>""")
                    }
                }
                rowsXml.append("</row>")
            }
            if (sh.notes.isNotEmpty()) {
                r++
                for (n in sh.notes) {
                    r++
                    rowsXml.append("""<row r="$r"><c r="A$r" s="${xf(Xf(0, 3, 0, 0, "", false))}" t="inlineStr"><is><t xml:space="preserve">${esc(n)}</t></is></c></row>""")
                }
            }
            val last = "${colName(cols - 1)}${maxOf(lastData, headerRow)}"
            val sb = StringBuilder(XML)
            sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$R_NS">""")
            sb.append("""<dimension ref="A1:${colName(cols - 1)}${maxOf(r, 1)}"/>""")
            sb.append("""<sheetViews><sheetView workbookViewId="0"${if (si == 0) " tabSelected=\"1\"" else ""}>""")
            if (s.excelFreeze && sh.rows.isNotEmpty()) sb.append("""<pane ySplit="$headerRow" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A${headerRow + 1}" sqref="A${headerRow + 1}"/>""")
            sb.append("</sheetView></sheetViews>")
            sb.append("""<sheetFormatPr defaultRowHeight="18"/>""")
            sb.append("<cols>")
            for (c in 0 until cols) sb.append("""<col min="${c + 1}" max="${c + 1}" width="${"%.1f".format(java.util.Locale.ROOT, widths[c].coerceIn(6.0, 60.0))}" customWidth="1"/>""")
            sb.append("</cols>")
            sb.append("<sheetData>").append(rowsXml).append("</sheetData>")
            if (s.excelFilter && sh.rows.isNotEmpty()) {
                sb.append("""<autoFilter ref="A$headerRow:$last"/>""")
                filters += """<definedName name="_xlnm._FilterDatabase" localSheetId="$si" hidden="1">'${esc(sh.name.replace("'", "''"))}'!${'$'}A${'$'}$headerRow:${'$'}${colName(cols - 1)}${'$'}${maxOf(lastData, headerRow)}</definedName>"""
            }
            if (merges.isNotEmpty()) sb.append("""<mergeCells count="${merges.size}">""").append(merges.joinToString("") { """<mergeCell ref="$it"/>""" }).append("</mergeCells>")
            sb.append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
            sb.append("""<pageSetup paperSize="9" orientation="${if (cols > 6) "landscape" else "portrait"}"/>""")
            sb.append("</worksheet>")
            sheetXml += sb.toString()
            textOut.append("## ").append(sh.name).append('\n')
            if (sh.title.isNotEmpty()) textOut.append(sh.title).append('\n')
            textOut.append("| ").append(sh.header.joinToString(" | ")).append(" |\n|").append(" --- |".repeat(cols)).append('\n')
            sh.rows.forEach { row -> textOut.append("| ").append(row.joinToString(" | ") { plain(it) }).append(" |\n") }
            sh.notes.forEach { textOut.append(it).append('\n') }
            textOut.append('\n')
        }

        val styles = buildString {
            append(XML).append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            if (numFmts.isNotEmpty()) {
                append("""<numFmts count="${numFmts.size}">""")
                numFmts.forEach { (f, id) -> append("""<numFmt numFmtId="$id" formatCode="${esc(f)}"/>""") }
                append("</numFmts>")
            }
            val font = "微软雅黑"
            append("""<fonts count="4">""")
            append("""<font><sz val="11"/><name val="$font"/><family val="2"/><charset val="134"/></font>""")
            append("""<font><b/><sz val="11"/><name val="$font"/><family val="2"/><charset val="134"/></font>""")
            append("""<font><b/><sz val="14"/><name val="$font"/><family val="2"/><charset val="134"/></font>""")
            append("""<font><i/><sz val="10"/><color rgb="FF7F7F7F"/><name val="$font"/><family val="2"/><charset val="134"/></font>""")
            append("</fonts>")
            append("""<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FFDEE6F2"/><bgColor indexed="64"/></patternFill></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FFF5F7FB"/><bgColor indexed="64"/></patternFill></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FFF2F2F2"/><bgColor indexed="64"/></patternFill></fill></fills>""")
            append("""<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>""")
            val side = """style="thin"><color rgb="FFBFBFBF"/>"""
            append("""<border><left $side</left><right $side</right><top $side</top><bottom $side</bottom><diagonal/></border></borders>""")
            append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
            append("""<cellXfs count="${xfs.size}">""")
            for (x in xfs) {
                append("""<xf numFmtId="${x.numFmt}" fontId="${x.font}" fillId="${x.fill}" borderId="${x.border}" xfId="0"""")
                if (x.numFmt != 0) append(""" applyNumberFormat="1"""")
                if (x.font != 0) append(""" applyFont="1"""")
                if (x.fill != 0) append(""" applyFill="1"""")
                if (x.border != 0) append(""" applyBorder="1"""")
                append(""" applyAlignment="1"><alignment vertical="center"""")
                if (x.h.isNotEmpty()) append(""" horizontal="${x.h}"""")
                if (x.wrap) append(""" wrapText="1"""")
                append("/></xf>")
            }
            append("</cellXfs>")
            append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
            append("</styleSheet>")
        }
        val workbook = buildString {
            append(XML).append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$R_NS">""")
            append("""<bookViews><workbookView/></bookViews><sheets>""")
            sheets.forEachIndexed { k, sh -> append("""<sheet name="${esc(sh.name)}" sheetId="${k + 1}" r:id="rId${k + 1}"/>""") }
            append("</sheets>")
            if (filters.isNotEmpty()) append("<definedNames>").append(filters.joinToString("")).append("</definedNames>")
            // 没存公式的计算结果：打开时让 Excel / WPS 全部重算
            append("""<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
        }
        val wbRels = buildString {
            append(XML).append("""<Relationships xmlns="$RELS_NS">""")
            sheets.indices.forEach { k -> append("""<Relationship Id="rId${k + 1}" Type="$R_NS/worksheet" Target="worksheets/sheet${k + 1}.xml"/>""") }
            append("""<Relationship Id="rIdS" Type="$R_NS/styles" Target="styles.xml"/></Relationships>""")
        }
        val ct = buildString {
            append(XML).append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            sheets.indices.forEach { k -> append("""<Override PartName="/xl/worksheets/sheet${k + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
            append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
            append("</Types>")
        }
        val parts = mutableListOf(
            "[Content_Types].xml" to ct,
            "_rels/.rels" to rootRels("xl/workbook.xml"),
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to wbRels,
            "xl/styles.xml" to styles,
            "docProps/core.xml" to coreXml(sheets.first().title.ifEmpty { sheets.first().name }),
            "docProps/app.xml" to appXml(),
        )
        sheetXml.forEachIndexed { k, x -> parts += "xl/worksheets/sheet${k + 1}.xml" to x }
        val summary = sheets.joinToString("，") { "「${it.name}」${it.rows.size} 行 × ${it.header.size} 列" } + (if (totalRow) "，能合计的表加了合计行" else "")
        return Made(zip(parts), textOut.toString().trim(), summary)
    }

    private val refRe = Regex("""(?<![A-Za-z0-9_.'!$"])(\$?)([A-Z]{1,3})(\$?)(\d{1,7})(?![\d(A-Za-z_!])""")

    /** 公式里的行号往下挪 by 行（AI 按「表头第 1 行」写，程序在上面加了表名行）。引号里的字、别的工作表的引用不动。 */
    fun shiftRows(f: String, by: Int): String {
        if (by == 0) return f
        return f.split('"').mapIndexed { k, part ->
            if (k % 2 == 1) part else refRe.replace(part) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + (m.groupValues[4].toInt() + by) }
        }.joinToString("\"")
    }

    private fun num(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
