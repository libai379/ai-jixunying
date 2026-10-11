package com.guixing.jixunying.engine

import com.guixing.jixunying.engine.OfficeWriter.Md
import com.guixing.jixunying.engine.OfficeWriter.R_NS
import com.guixing.jixunying.engine.OfficeWriter.RELS_NS
import com.guixing.jixunying.engine.OfficeWriter.XML
import com.guixing.jixunying.engine.OfficeWriter.esc
import com.guixing.jixunying.model.OfficeSettings
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max

/**
 * 做 PPT（1.5.0，第 7 件）。和 OfficeWriter 一样手写 OOXML；不用占位符，标题、正文、表格、装饰条都直接画成形状，
 * 只留一个空白版式——版面完全由这里决定，Office / WPS / LibreOffice 打开都一样。
 *
 * 内容用 Markdown 写：开头「# 标题」是封面（下面一两行字是副标题），每个「## 」是一页；页里写要点、编号、表格、引用、代码，
 * 「### 」是页里的小标题；「备注：」后面的字放进演讲者备注（讲稿）。只有标题没内容的一页当章节页，「谢谢」「Q&A」这种当结尾页。
 * 程序负责设计：配色、版式、按字数选字号（28 → 18 磅），一页放不下自动拆成「（续）」，表格超过 12 行也拆；内容页多时封面后加目录页。
 */
object PptWriter {

    data class PptOptions(val title: String = "", val subtitle: String = "", val theme: String = "")

    private class Theme(
        val id: String, val primary: String, val accent: String, val light: String, val text: String, val subtle: String,
        val bg: String, val dark: Boolean, val head: String, val band: String, val border: String, val onPrimary: String,
    )

    private fun theme(id: String) = when (id) {
        "green" -> Theme("green", "1E6B52", "2E9E74", "E3F2EC", "262626", "7F7F7F", "FFFFFF", false, "1E6B52", "F1F8F5", "D9D9D9", "FFFFFF")
        "orange" -> Theme("orange", "B4490F", "ED7D31", "FCE9DC", "262626", "7F7F7F", "FFFFFF", false, "C55A11", "FDF3EC", "D9D9D9", "FFFFFF")
        "dark" -> Theme("dark", "38BDF8", "22D3EE", "1E293B", "E2E8F0", "94A3B8", "0F172A", true, "0E7490", "162033", "334155", "0F172A")
        "mono" -> Theme("mono", "262626", "595959", "F2F2F2", "262626", "8C8C8C", "FFFFFF", false, "404040", "F5F5F5", "D9D9D9", "FFFFFF")
        else -> Theme("blue", "1F4E79", "2E75B6", "DEEBF7", "262626", "7F7F7F", "FFFFFF", false, "1F4E79", "F2F7FC", "D9D9D9", "FFFFFF")
    }

    /** 主题中文名（给 AI 和界面用）对应编号。 */
    fun themeId(name: String): String = when (name.trim().lowercase()) {
        "商务蓝", "蓝", "blue" -> "blue"
        "清新绿", "绿", "green" -> "green"
        "活力橙", "橙", "orange" -> "orange"
        "科技深色", "深色", "黑色", "dark" -> "dark"
        "简约黑白", "黑白", "mono" -> "mono"
        else -> ""
    }

    // ———————————————— 内容 ————————————————

    private sealed interface Item
    private class Bullet(val level: Int, val text: String, val ordered: Boolean, val number: Int) : Item
    private class Para(val text: String, val sub: Boolean = false) : Item
    private class Quote(val text: String) : Item
    private class Code(val text: String) : Item
    private class Tbl(val t: Md.Table) : Item

    private enum class Kind { COVER, AGENDA, SECTION, CONTENT, END }
    private class Slide(val kind: Kind, val title: String, val items: List<Item> = emptyList(), val notes: String = "", val sub: String = "", val number: Int = 0)

    private val notesRe = Regex("""^(备注|讲稿|演讲稿|演讲备注|Notes?)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    private val endRe = Regex("""^(谢谢|感谢|谢谢观看|谢谢聆听|感谢聆听|感谢观看|Thanks?( You)?|Q\s*&\s*A|提问|答疑|问答)[!！。.]*$""", RegexOption.IGNORE_CASE)

    private fun build(markdown: String, o: PptOptions): Pair<Slide?, List<Slide>> {
        val blocks = OfficeWriter.parse(markdown)
        var title = OfficeWriter.plain(o.title).trim()
        var sub = OfficeWriter.plain(o.subtitle).trim()
        var i = 0
        // 开头的一级标题当封面；它下面、第一个二级标题前的字当副标题
        if (blocks.firstOrNull() is Md.Heading && (blocks[0] as Md.Heading).level == 1) {
            if (title.isEmpty()) title = OfficeWriter.plain((blocks[0] as Md.Heading).text).trim()
            i = 1
            val lines = mutableListOf<String>()
            while (i < blocks.size && blocks[i] !is Md.Heading) {
                (blocks[i] as? Md.Para)?.let { lines += OfficeWriter.plain(it.text).trim() }
                i++
            }
            if (sub.isEmpty()) sub = lines.joinToString("\n")
        }
        val slides = mutableListOf<Slide>()
        var curTitle: String? = null
        var items = mutableListOf<Item>()
        val notes = StringBuilder()
        var inNotes = false
        fun close() {
            val t = curTitle ?: run { if (items.isEmpty() && notes.isEmpty()) return; "" }
            // 编号列表在这里就编好号（从第一项写的号开始往下数）：拆页后接着编，AI 每项都写「1.」也对；
            // 每条直接写自己的号，PowerPoint、WPS、LibreOffice 显示都一样
            var no = 0
            var inList = false
            val numbered = items.map { it ->
                if (it is Bullet && it.ordered && it.level == 0) {
                    no = if (inList) no + 1 else it.number.coerceAtLeast(1)
                    inList = true
                    Bullet(0, it.text, true, no)
                } else {
                    if (!(it is Bullet && it.level > 0)) inList = false
                    it
                }
            }
            slides += Slide(Kind.CONTENT, t, numbered, notes.toString().trim())
            curTitle = null; items = mutableListOf(); notes.clear(); inNotes = false
        }
        while (i < blocks.size) {
            val b = blocks[i]
            when {
                b is Md.Heading && b.level <= 2 -> { close(); curTitle = OfficeWriter.plain(b.text).trim() }
                inNotes -> notes.append(blockText(b)).append('\n')
                b is Md.Para && notesRe.containsMatchIn(b.text.trim()) -> { inNotes = true; notes.append(b.text.trim().replace(notesRe, "")).append('\n') }
                b is Md.Quote && notesRe.containsMatchIn(b.text.trim()) -> notes.append(b.text.trim().replace(notesRe, "")).append('\n')
                b is Md.Heading -> items += Para(b.text, sub = true)
                b is Md.Para -> items += Para(b.text)
                b is Md.Item -> items += Bullet(b.level, b.text, b.ordered, b.number)
                b is Md.Quote -> items += Quote(b.text)
                b is Md.Code -> items += Code(b.code)
                b is Md.Table -> items += Tbl(b)
                else -> Unit
            }
            i++
        }
        close()
        // 只有标题的页：「谢谢」这种是结尾页，其他是章节页
        var section = 0
        val typed = slides.map { s ->
            when {
                s.items.isEmpty() && endRe.matches(s.title) -> Slide(Kind.END, s.title, notes = s.notes)
                s.items.isEmpty() && s.title.isNotEmpty() -> Slide(Kind.SECTION, s.title, notes = s.notes, number = ++section)
                else -> s
            }
        }
        val cover = if (title.isNotEmpty()) Slide(Kind.COVER, title, sub = sub) else null
        return cover to typed
    }

    private fun blockText(b: Md): String = when (b) {
        is Md.Para -> OfficeWriter.plain(b.text)
        is Md.Item -> "• " + OfficeWriter.plain(b.text)
        is Md.Quote -> OfficeWriter.plain(b.text)
        is Md.Heading -> OfficeWriter.plain(b.text)
        is Md.Code -> b.code
        is Md.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString(" | ") { OfficeWriter.plain(it) } }
        else -> ""
    }

    // ———————————————— 排版：按字数选字号、放不下就拆 ————————————————

    private const val EMU_IN = 914400L
    private const val EMU_PT = 12700L

    private fun em(s: String) = s.sumOf { if (it.code > 0x2E80) 1.0 else 0.55 }
    private fun lines(text: String, fs: Double, widthPt: Double) =
        text.split('\n').sumOf { l -> max(1, ceil(em(l) * fs / widthPt).toInt()) }

    private fun levelSize(fs: Double, level: Int) = (fs - 2 * level).coerceAtLeast(12.0)
    /** 表格字号：按一页能放多高来定（12–16 磅），行少字就大。 */
    private fun tableFont(rows: Int) = (378.0 / ((rows + 1) * 2.0)).toInt().toDouble().coerceIn(12.0, 16.0)
    private fun tableRowPt(tfs: Double) = tfs * 2.0

    /** 一段内容在 fs 字号下大概多高（磅）。 */
    private fun heightOf(it: Item, fs: Double, wPt: Double): Double = when (it) {
        is Bullet -> { val f = levelSize(fs, it.level); lines(OfficeWriter.plain(it.text), f, wPt - 24.0 * (it.level + 1)) * f * 1.25 + f * 0.5 }
        is Para -> { val f = if (it.sub) fs + 2 else fs; lines(OfficeWriter.plain(it.text), f, wPt) * f * 1.25 + f * 0.5 }
        is Quote -> lines(OfficeWriter.plain(it.text), fs, wPt - 24) * fs * 1.25 + fs * 0.8
        is Code -> { val f = (fs * 0.7).coerceAtLeast(11.0); it.text.split('\n').size * f * 1.2 + fs * 0.6 }
        is Tbl -> (it.t.rows.size + 1) * tableRowPt(tableFont(it.t.rows.size)) + fs * 0.6
    }

    private val sizes = listOf(28.0, 26.0, 24.0, 22.0, 20.0, 18.0)

    /** 内容页：挑最大的放得下的字号；放不下（并且允许拆）就拆成几页，表格超过 12 行也拆（表头每页都有）。 */
    private fun fit(s: Slide, wPt: Double, hPt: Double, split: Boolean): List<Pair<Slide, Double>> {
        // 先把长表格切开
        val items = s.items.flatMap { it ->
            if (it is Tbl && split && it.t.rows.size > 12) it.t.rows.chunked(12).map { rows -> Tbl(Md.Table(it.t.header, it.t.aligns, rows)) } else listOf(it)
        }
        fun total(list: List<Item>, fs: Double) = list.sumOf { heightOf(it, fs, wPt) }
        sizes.firstOrNull { total(items, it) <= hPt }?.let { return listOf(Slide(s.kind, s.title, items, s.notes) to it) }
        if (!split || items.size <= 1) {
            // 不拆：缩到放得下（最小 12 磅），PowerPoint 打开时还会再按 normAutofit 缩
            val fs = (12..18).reversed().map { it.toDouble() }.firstOrNull { total(items, it) <= hPt } ?: 12.0
            return listOf(Slide(s.kind, s.title, items, s.notes) to fs)
        }
        // 拆：先按 20 磅算要几页，再把内容平均分到这几页（不会一页挤一页空），几页用同一个字号；讲稿放在第一页
        val heights = items.map { heightOf(it, 20.0, wPt) }
        var n = 1
        run { var used = 0.0; for (h in heights) { if (used + h > hPt && used > 0) { n++; used = 0.0 }; used += h } }
        val target = heights.sum() / n
        val pages = mutableListOf<MutableList<Item>>(mutableListOf())
        var used = 0.0
        items.forEachIndexed { k, it ->
            val h = heights[k]
            if (pages.last().isNotEmpty() && pages.size < n && (used + h > target * 1.08 || used + h > hPt)) { pages += mutableListOf<Item>(); used = 0.0 }
            pages.last() += it
            used += h
        }
        val fs = pages.minOf { list -> sizes.firstOrNull { total(list, it) <= hPt } ?: 18.0 }
        return pages.mapIndexed { k, list ->
            Slide(s.kind, if (k == 0) s.title else s.title + "（续）", list, if (k == 0) s.notes else "") to fs
        }
    }

    // ———————————————— 形状 ————————————————

    private class Shapes {
        val sb = StringBuilder()
        var id = 2
        fun next() = id++
    }

    private fun xfrm(x: Long, y: Long, w: Long, h: Long) = """<a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm>"""

    private fun Shapes.rect(x: Long, y: Long, w: Long, h: Long, fill: String, name: String = "装饰", ellipse: Boolean = false) {
        sb.append("""<p:sp><p:nvSpPr><p:cNvPr id="${next()}" name="$name"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>""")
        sb.append(xfrm(x, y, w, h)).append("""<a:prstGeom prst="${if (ellipse) "ellipse" else "rect"}"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$fill"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>""")
    }

    private fun rPr(sz: Double, color: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, font: String = "微软雅黑") =
        """<a:rPr lang="zh-CN" altLang="en-US" sz="${(sz * 100).toInt()}"${if (bold) " b=\"1\"" else ""}${if (italic) " i=\"1\"" else ""}${if (strike) " strike=\"sngStrike\"" else ""} dirty="0">""" +
            """<a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:latin typeface="$font"/><a:ea typeface="$font"/><a:cs typeface="$font"/></a:rPr>"""

    /** Markdown 行内（加粗、斜体、删除线、代码）→ 一串 a:r。 */
    private fun runs(text: String, sz: Double, color: String, bold: Boolean = false, accent: String = color, italic: Boolean = false): String = buildString {
        for (r in OfficeWriter.runs(text, bold)) {
            val pieces = r.text.split('\n')
            pieces.forEachIndexed { k, p ->
                if (k > 0) append("<a:br>").append(rPr(sz, color)).append("</a:br>")
                if (p.isEmpty()) return@forEachIndexed
                append("<a:r>")
                append(rPr(sz, if (r.link != null || r.code) accent else color, r.bold, r.italic || italic, r.strike, if (r.code) "Consolas" else "微软雅黑"))
                append("<a:t>").append(esc(p)).append("</a:t></a:r>")
            }
        }
    }

    /** 一个文字框：paras 是已经拼好的 a:p。 */
    private fun Shapes.textBox(x: Long, y: Long, w: Long, h: Long, paras: String, anchor: String = "t", name: String = "文字", autofit: Boolean = false) {
        sb.append("""<p:sp><p:nvSpPr><p:cNvPr id="${next()}" name="$name"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>""")
        sb.append(xfrm(x, y, w, h)).append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>""")
        sb.append("""<p:txBody><a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" anchor="$anchor">${if (autofit) "<a:normAutofit/>" else ""}</a:bodyPr><a:lstStyle/>""")
        sb.append(paras.ifEmpty { "<a:p><a:endParaRPr lang=\"zh-CN\"/></a:p>" }).append("</p:txBody></p:sp>")
    }

    private fun para(inner: String, algn: String = "l", spcBefPt: Double = 0.0, lnPct: Int = 100, extra: String = "", marL: Long = 0, indent: Long = 0) =
        """<a:p><a:pPr marL="$marL" indent="$indent" algn="$algn"><a:lnSpc><a:spcPct val="${lnPct * 1000}"/></a:lnSpc><a:spcBef><a:spcPts val="${(spcBefPt * 100).toInt()}"/></a:spcBef>$extra</a:pPr>$inner</a:p>"""

    private fun noBullet() = "<a:buNone/>"

    private fun Shapes.table(x: Long, y: Long, w: Long, t: Md.Table, th: Theme) {
        val cols = t.header.size.coerceAtLeast(1)
        val tfs = tableFont(t.rows.size)
        val rowH = (tableRowPt(tfs) * EMU_PT).toLong()
        // 列宽按内容长短分（最窄 2 个字，最宽 20 个字）
        val weights = (0 until cols).map { c ->
            (listOf(t.header.getOrElse(c) { "" }) + t.rows.map { it.getOrElse(c) { "" } }).maxOf { em(OfficeWriter.plain(it)) }.coerceIn(2.0, 20.0)
        }
        val sum = weights.sum()
        val widths = weights.map { (w * it / sum).toLong() }
        val numeric = (0 until cols).map { c ->
            val vals = t.rows.map { OfficeWriter.plain(it.getOrElse(c) { "" }).trim() }.filter { it.isNotEmpty() }
            vals.isNotEmpty() && vals.all { OfficeWriter.cellValue(it) is OfficeWriter.CellVal.Num }
        }
        fun ln(tag: String) = """<a:$tag w="9525" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:srgbClr val="${th.border}"/></a:solidFill><a:prstDash val="solid"/></a:$tag>"""
        val lines = ln("lnL") + ln("lnR") + ln("lnT") + ln("lnB")
        fun cell(text: String, head: Boolean, c: Int, fill: String): String {
            val algn = when (t.aligns.getOrElse(c) { ' ' }) { 'c' -> "ctr"; 'r' -> "r"; 'l' -> "l"; else -> if (head) "ctr" else if (numeric[c]) "r" else "l" }
            val color = if (head) "FFFFFF" else th.text
            return """<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn="$algn"/>""" + runs(text, tfs, color, head, th.accent) +
                """<a:endParaRPr lang="zh-CN" sz="${(tfs * 100).toInt()}"/></a:p></a:txBody>""" +
                """<a:tcPr marL="91440" marR="91440" marT="45720" marB="45720" anchor="ctr">$lines<a:solidFill><a:srgbClr val="$fill"/></a:solidFill></a:tcPr></a:tc>"""
        }
        sb.append("""<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="${next()}" name="表格"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>""")
        sb.append("""<p:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="${rowH * (t.rows.size + 1)}"/></p:xfrm>""")
        sb.append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblPr firstRow="1" bandRow="1"/><a:tblGrid>""")
        widths.forEach { sb.append("""<a:gridCol w="$it"/>""") }
        sb.append("</a:tblGrid>")
        sb.append("""<a:tr h="$rowH">""")
        t.header.forEachIndexed { c, h -> sb.append(cell(h, true, c, th.head)) }
        sb.append("</a:tr>")
        t.rows.forEachIndexed { r, row ->
            sb.append("""<a:tr h="$rowH">""")
            for (c in 0 until cols) sb.append(cell(row.getOrElse(c) { "" }, false, c, if (r % 2 == 1) th.band else th.bg))
            sb.append("</a:tr>")
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
    }

    // ———————————————— 一页一页画 ————————————————

    private class Canvas(val w: Long, val h: Long)

    private fun slideXml(shapes: Shapes, bg: String?) = XML +
        """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$R_NS" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld>""" +
        (if (bg != null) """<p:bg><p:bgPr><a:solidFill><a:srgbClr val="$bg"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>""" else "") +
        """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr>""" +
        """<a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
        shapes.sb + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

    private fun inch(v: Double) = (v * EMU_IN).toLong()

    private fun cover(s: Slide, c: Canvas, th: Theme, end: Boolean = false): String {
        val sh = Shapes()
        val bg = if (th.dark) th.bg else th.primary
        sh.rect(0, 0, c.w, c.h, bg, "背景")
        sh.rect(0, 0, inch(0.22), c.h, th.accent, "左边条")
        // 右下角两个淡淡的圆，压一点设计感
        sh.rect(c.w - inch(3.2), c.h - inch(2.6), inch(4.2), inch(4.2), if (th.dark) "16243A" else blend(bg, "FFFFFF", 0.10), "圆", ellipse = true)
        sh.rect(c.w - inch(1.6), inch(-0.9), inch(2.4), inch(2.4), if (th.dark) "13203A" else blend(bg, "FFFFFF", 0.06), "圆", ellipse = true)
        val titleColor = "FFFFFF"
        val subColor = if (th.dark) th.subtle else blend(bg, "FFFFFF", 0.78)
        val left = inch(0.9)
        val width = c.w - inch(1.8)
        if (end) {
            sh.textBox(left, (c.h * 0.36).toLong(), width, inch(1.4), para(runs(s.title, 48.0, titleColor, true), "ctr"), "ctr", "结尾")
            sh.rect((c.w - inch(1.2)) / 2, (c.h * 0.36).toLong() + inch(1.55), inch(1.2), inch(0.06), th.accent, "线")
        } else {
            val tfs = if (em(s.title) > 22) 34.0 else 40.0
            sh.textBox(left, (c.h * 0.24).toLong(), width, inch(1.9), para(runs(s.title, tfs, titleColor, true), lnPct = 110), "b", "标题")
            sh.rect(left, (c.h * 0.24).toLong() + inch(2.05), inch(1.2), inch(0.06), if (th.dark) th.primary else "FFFFFF", "线")
            if (s.sub.isNotBlank()) {
                val paras = s.sub.split('\n').filter { it.isNotBlank() }.take(3).joinToString("") { para(runs(it, 20.0, subColor), spcBefPt = 4.0) }
                sh.textBox(left, (c.h * 0.24).toLong() + inch(2.3), width, inch(1.3), paras, name = "副标题")
            }
            val d = LocalDate.now()
            sh.textBox(left, c.h - inch(1.0), inch(5.0), inch(0.4), para(runs("${d.year} 年 ${d.monthValue} 月", 14.0, subColor)), name = "日期")
        }
        return slideXml(sh, null)
    }

    private fun section(s: Slide, c: Canvas, th: Theme): String {
        val sh = Shapes()
        val blockW = (c.w * 0.36).toLong()
        sh.rect(0, 0, blockW, c.h, th.primary, "色块")
        sh.textBox(0, (c.h * 0.32).toLong(), blockW, inch(1.6), para(runs("%02d".format(s.number), 72.0, if (th.dark) th.bg else "FFFFFF", true), "ctr"), "ctr", "编号")
        val x = blockW + inch(0.7)
        sh.textBox(x, (c.h * 0.36).toLong(), c.w - x - inch(0.7), inch(1.4), para(runs(s.title, 36.0, th.text, true), lnPct = 110), "ctr", "章节")
        sh.rect(x, (c.h * 0.36).toLong() + inch(1.5), inch(1.0), inch(0.06), th.accent, "线")
        return slideXml(sh, if (th.dark) th.bg else null)
    }

    private fun Shapes.chrome(title: String, c: Canvas, th: Theme, deck: String, page: Int, pageNumbers: Boolean) {
        rect(inch(0.42), inch(0.47), inch(0.08), inch(0.6), th.accent, "标题条")
        val tfs = if (em(title) > 26) 24.0 else 28.0
        textBox(inch(0.62), inch(0.35), c.w - inch(1.24), inch(0.85), para(runs(title, tfs, if (th.dark) th.primary else th.primary, true)), "ctr", "标题")
        rect(inch(0.62), inch(1.25), c.w - inch(1.24), inch(0.015), if (th.dark) "334155" else th.light, "分隔线")
        if (deck.isNotBlank()) textBox(inch(0.62), c.h - inch(0.5), inch(6.0), inch(0.3), para(runs(deck, 10.0, th.subtle)), name = "页脚")
        if (pageNumbers) textBox(c.w - inch(1.62), c.h - inch(0.5), inch(1.0), inch(0.3), para(runs("$page", 10.0, th.subtle), "r"), name = "页码")
    }

    private fun content(s: Slide, fs: Double, c: Canvas, th: Theme, deck: String, page: Int, pageNumbers: Boolean): String {
        val sh = Shapes()
        sh.chrome(s.title, c, th, deck, page, pageNumbers)
        val x = inch(0.75)
        val w = c.w - inch(1.5)
        val wPt = w.toDouble() / EMU_PT
        var y = inch(1.5)
        val bottom = c.h - inch(0.75)
        // 连着的文字合成一个文字框，表格单独画
        val groups = mutableListOf<MutableList<Item>>()
        for (it in s.items) {
            if (it is Tbl || groups.isEmpty() || groups.last().firstOrNull() is Tbl) groups += mutableListOf(it) else groups.last() += it
        }
        for (g in groups) {
            val first = g.first()
            if (first is Tbl) {
                sh.table(x, y, w, first.t, th)
                y += ((first.t.rows.size + 1) * tableRowPt(tableFont(first.t.rows.size)) * EMU_PT).toLong() + inch(0.15)
                continue
            }
            val h = g.sumOf { heightOf(it, fs, wPt) }
            val paras = buildString {
                for (it in g) {
                    when (it) {
                        is Bullet -> {
                            val f = levelSize(fs, it.level)
                            val marL = inch(0.32 + 0.38 * it.level)
                            val bu = if (it.ordered) {
                                val start = it.number.coerceAtLeast(1)
                                """<a:buClr><a:srgbClr val="${th.accent}"/></a:buClr><a:buFont typeface="+mj-lt"/><a:buAutoNum type="arabicPeriod" startAt="$start"/>"""
                            } else """<a:buClr><a:srgbClr val="${th.accent}"/></a:buClr><a:buFont typeface="Arial"/><a:buChar char="${if (it.level == 0) "•" else "–"}"/>"""
                            append(para(runs(it.text, f, th.text, accent = th.accent), spcBefPt = f * 0.5, lnPct = 110, extra = bu, marL = marL, indent = -inch(0.3)))
                        }
                        is Para -> append(para(runs(it.text, if (it.sub) fs + 2 else fs, if (it.sub) th.primary else th.text, it.sub, th.accent), spcBefPt = fs * 0.5, lnPct = 110, extra = noBullet()))
                        is Quote -> append(para(runs(it.text, fs, th.subtle, accent = th.accent, italic = true), spcBefPt = fs * 0.6, lnPct = 110,
                            extra = noBullet(), marL = inch(0.3)))
                        is Code -> {
                            val f = (fs * 0.7).coerceAtLeast(11.0)
                            it.text.split('\n').forEachIndexed { k, line ->
                                append(para(if (line.isEmpty()) "" else "<a:r>${rPr(f, th.text, font = "Consolas")}<a:t>${esc(line)}</a:t></a:r>", spcBefPt = if (k == 0) fs * 0.5 else 0.0,
                                    extra = noBullet(), marL = inch(0.2)))
                            }
                        }
                        is Tbl -> Unit
                    }
                }
            }
            val boxH = (h * EMU_PT).toLong().coerceAtMost(bottom - y).coerceAtLeast(inch(0.4))
            sh.textBox(x, y, w, boxH, paras, name = "正文", autofit = true)
            y += boxH + inch(0.1)
        }
        return slideXml(sh, if (th.dark) th.bg else null)
    }

    private fun agenda(titles: List<String>, c: Canvas, th: Theme, deck: String, page: Int, pageNumbers: Boolean): String {
        val sh = Shapes()
        sh.chrome("目录", c, th, deck, page, pageNumbers)
        val twoCols = titles.size > 6
        val per = if (twoCols) (titles.size + 1) / 2 else titles.size
        val colW = if (twoCols) (c.w - inch(1.5)) / 2 else c.w - inch(1.5)
        val fs = when { titles.size <= 4 -> 28.0; titles.size <= 6 -> 24.0; titles.size <= 10 -> 22.0; else -> 18.0 }
        titles.chunked(per).forEachIndexed { col, list ->
            val paras = list.mapIndexed { k, t ->
                val n = col * per + k + 1
                para(runs("%02d".format(n), fs + 4, th.accent, true) + runs("   $t", fs, th.text), spcBefPt = fs * if (titles.size <= 4) 1.4 else 0.9)
            }.joinToString("")
            sh.textBox(inch(0.9) + col * colW, inch(1.6), colW - inch(0.3), c.h - inch(2.5), paras, name = "目录", autofit = true)
        }
        return slideXml(sh, if (th.dark) th.bg else null)
    }

    /** 两个颜色按比例混（封面上的淡圆、副标题颜色）。 */
    private fun blend(a: String, b: String, t: Double): String {
        fun ch(s: String, i: Int) = s.substring(i, i + 2).toInt(16)
        return (0..2).joinToString("") { k ->
            val v = (ch(a, k * 2) * (1 - t) + ch(b, k * 2) * t).toInt().coerceIn(0, 255)
            "%02X".format(v)
        }
    }

    // ———————————————— 打包 ————————————————

    fun ppt(markdown: String, s: OfficeSettings, o: PptOptions = PptOptions()): OfficeWriter.Made {
        val th = theme(o.theme.ifBlank { s.pptTheme })
        val canvas = if (s.pptWide) Canvas(12192000, 6858000) else Canvas(9144000, 6858000)
        val (cover, raw) = build(markdown, o)
        require(cover != null || raw.isNotEmpty()) { "没找到内容：PPT 的内容用 Markdown 写，# 标题是封面，每个 ## 是一页" }
        val wPt = (canvas.w - inch(1.5)).toDouble() / EMU_PT
        val hPt = (canvas.h - inch(1.5) - inch(0.75)).toDouble() / EMU_PT
        // 内容页按字数排好（可能拆成几页）
        val laid = raw.flatMap { if (it.kind == Kind.CONTENT) fit(it, wPt, hPt, s.pptSplit) else listOf(it to 0.0) }
        val deck = cover?.title.orEmpty()
        val contentTitles = raw.filter { it.kind == Kind.CONTENT && it.title.isNotBlank() }.map { it.title }.distinct()
        val sections = raw.filter { it.kind == Kind.SECTION }.map { it.title }
        val agendaTitles = if (sections.size >= 2) sections else contentTitles
        // 目录页：有两个以上章节就列章节；没章节时内容页 4 页以上列内容页（太多就不列了）
        val withAgenda = s.pptAgenda && cover != null && (sections.size >= 2 || contentTitles.size in 4..16)

        val slides = mutableListOf<Pair<String, String>>()   // (幻灯片 XML, 备注)
        if (cover != null) slides += cover(cover, canvas, th) to ""
        if (withAgenda) slides += agenda(agendaTitles, canvas, th, deck, slides.size + 1, s.pptPageNumbers) to ""
        for ((sl, fs) in laid) {
            val page = slides.size + 1
            slides += when (sl.kind) {
                Kind.SECTION -> section(sl, canvas, th)
                Kind.END -> cover(sl, canvas, th, end = true)
                else -> content(sl, fs, canvas, th, deck, page, s.pptPageNumbers)
            } to (if (s.pptNotes) sl.notes else "")
        }
        val hasNotes = slides.any { it.second.isNotBlank() }

        val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val ns = """xmlns:a="$A" xmlns:r="$R_NS" xmlns:p="$P""""
        val group = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""
        val clrMap = """bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink""""
        val font = """<a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/>"""

        val parts = mutableListOf<Pair<String, String>>()
        val presRels = StringBuilder(XML).append("""<Relationships xmlns="$RELS_NS">""")
        presRels.append("""<Relationship Id="rIdM" Type="$R_NS/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
        presRels.append("""<Relationship Id="rIdT" Type="$R_NS/theme" Target="theme/theme1.xml"/>""")
        presRels.append("""<Relationship Id="rIdP" Type="$R_NS/presProps" Target="presProps.xml"/>""")
        presRels.append("""<Relationship Id="rIdV" Type="$R_NS/viewProps" Target="viewProps.xml"/>""")
        presRels.append("""<Relationship Id="rIdS" Type="$R_NS/tableStyles" Target="tableStyles.xml"/>""")
        if (hasNotes) presRels.append("""<Relationship Id="rIdN" Type="$R_NS/notesMaster" Target="notesMasters/notesMaster1.xml"/>""")
        slides.indices.forEach { k -> presRels.append("""<Relationship Id="rId${k + 1}" Type="$R_NS/slide" Target="slides/slide${k + 1}.xml"/>""") }
        presRels.append("</Relationships>")

        val pres = buildString {
            append(XML).append("""<p:presentation $ns saveSubsetFonts="1">""")
            append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rIdM"/></p:sldMasterIdLst>""")
            if (hasNotes) append("""<p:notesMasterIdLst><p:notesMasterId r:id="rIdN"/></p:notesMasterIdLst>""")
            append("<p:sldIdLst>")
            slides.indices.forEach { k -> append("""<p:sldId id="${256 + k}" r:id="rId${k + 1}"/>""") }
            append("</p:sldIdLst>")
            append("""<p:sldSz cx="${canvas.w}" cy="${canvas.h}"${if (s.pptWide) "" else " type=\"screen4x3\""}/><p:notesSz cx="6858000" cy="9144000"/>""")
            append("""<p:defaultTextStyle><a:defPPr><a:defRPr lang="zh-CN"/></a:defPPr><a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1800" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill>$font</a:defRPr></a:lvl1pPr></p:defaultTextStyle>""")
            append("</p:presentation>")
        }
        val master = XML + """<p:sldMaster $ns><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$group</p:spTree></p:cSld>""" +
            """<p:clrMap $clrMap/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
            """<p:txStyles><p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="2800" b="1"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/><a:ea typeface="+mj-ea"/><a:cs typeface="+mj-cs"/></a:defRPr></a:lvl1pPr></p:titleStyle>""" +
            """<p:bodyStyle><a:lvl1pPr marL="0" indent="0" algn="l"><a:defRPr sz="2000"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill>$font</a:defRPr></a:lvl1pPr></p:bodyStyle>""" +
            """<p:otherStyle><a:lvl1pPr marL="0" algn="l"><a:defRPr sz="1800"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill>$font</a:defRPr></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""
        val masterRels = XML + """<Relationships xmlns="$RELS_NS"><Relationship Id="rId1" Type="$R_NS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$R_NS/theme" Target="../theme/theme1.xml"/></Relationships>"""
        val layout = XML + """<p:sldLayout $ns type="blank" preserve="1"><p:cSld name="空白"><p:spTree>$group</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""
        val layoutRels = XML + """<Relationships xmlns="$RELS_NS"><Relationship Id="rId1" Type="$R_NS/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

        parts += "_rels/.rels" to OfficeWriter.rootRels("ppt/presentation.xml")
        parts += "ppt/presentation.xml" to pres
        parts += "ppt/_rels/presentation.xml.rels" to presRels.toString()
        parts += "ppt/slideMasters/slideMaster1.xml" to master
        parts += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to masterRels
        parts += "ppt/slideLayouts/slideLayout1.xml" to layout
        parts += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to layoutRels
        parts += "ppt/theme/theme1.xml" to themeXml(th)
        parts += "ppt/presProps.xml" to XML + """<p:presentationPr $ns/>"""
        parts += "ppt/viewProps.xml" to XML + """<p:viewPr $ns><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr><p:gridSpacing cx="76200" cy="76200"/></p:viewPr>"""
        parts += "ppt/tableStyles.xml" to XML + """<a:tblStyleLst xmlns:a="$A" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>"""
        slides.forEachIndexed { k, (xml, notes) ->
            val n = k + 1
            parts += "ppt/slides/slide$n.xml" to xml
            val rels = StringBuilder(XML).append("""<Relationships xmlns="$RELS_NS"><Relationship Id="rId1" Type="$R_NS/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""")
            if (notes.isNotBlank()) {
                rels.append("""<Relationship Id="rId2" Type="$R_NS/notesSlide" Target="../notesSlides/notesSlide$n.xml"/>""")
                parts += "ppt/notesSlides/notesSlide$n.xml" to notesSlide(ns, group, notes)
                parts += "ppt/notesSlides/_rels/notesSlide$n.xml.rels" to XML + """<Relationships xmlns="$RELS_NS"><Relationship Id="rId1" Type="$R_NS/notesMaster" Target="../notesMasters/notesMaster1.xml"/><Relationship Id="rId2" Type="$R_NS/slide" Target="../slides/slide$n.xml"/></Relationships>"""
            }
            rels.append("</Relationships>")
            parts += "ppt/slides/_rels/slide$n.xml.rels" to rels.toString()
        }
        if (hasNotes) {
            parts += "ppt/notesMasters/notesMaster1.xml" to notesMaster(ns, group, clrMap, font)
            parts += "ppt/notesMasters/_rels/notesMaster1.xml.rels" to XML + """<Relationships xmlns="$RELS_NS"><Relationship Id="rId1" Type="$R_NS/theme" Target="../theme/theme2.xml"/></Relationships>"""
            parts += "ppt/theme/theme2.xml" to themeXml(th)
        }
        parts += "docProps/core.xml" to OfficeWriter.coreXml(deck)
        parts += "docProps/app.xml" to OfficeWriter.appXml()
        val pml = "application/vnd.openxmlformats-officedocument.presentationml"
        val ct = buildString {
            append(XML).append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/ppt/presentation.xml" ContentType="$pml.presentation.main+xml"/>""")
            append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="$pml.slideMaster+xml"/>""")
            append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="$pml.slideLayout+xml"/>""")
            append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
            append("""<Override PartName="/ppt/presProps.xml" ContentType="$pml.presProps+xml"/>""")
            append("""<Override PartName="/ppt/viewProps.xml" ContentType="$pml.viewProps+xml"/>""")
            append("""<Override PartName="/ppt/tableStyles.xml" ContentType="$pml.tableStyles+xml"/>""")
            slides.forEachIndexed { k, (_, notes) ->
                append("""<Override PartName="/ppt/slides/slide${k + 1}.xml" ContentType="$pml.slide+xml"/>""")
                if (notes.isNotBlank()) append("""<Override PartName="/ppt/notesSlides/notesSlide${k + 1}.xml" ContentType="$pml.notesSlide+xml"/>""")
            }
            if (hasNotes) {
                append("""<Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="$pml.notesMaster+xml"/>""")
                append("""<Override PartName="/ppt/theme/theme2.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
            }
            append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
            append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
            append("</Types>")
        }
        parts.add(0, "[Content_Types].xml" to ct)

        val contentCount = laid.count { it.first.kind == Kind.CONTENT }
        val summary = buildList {
            add("${slides.size} 页")
            add(when (th.id) { "green" -> "清新绿"; "orange" -> "活力橙"; "dark" -> "科技深色"; "mono" -> "简约黑白"; else -> "商务蓝" })
            if (withAgenda) add("带目录")
            if (hasNotes) add("有讲稿")
            if (laid.size > raw.size) add("${laid.size - raw.size} 页内容太多拆开了")
            if (contentCount == 0) add("只有封面")
        }.joinToString("，")
        val text = buildString {
            if (cover != null) append("【封面】").append(cover.title).append('\n').append(cover.sub).append("\n\n")
            for ((sl, _) in laid) {
                append("【").append(sl.title).append("】\n")
                sl.items.forEach { append(blockTextItem(it)).append('\n') }
                if (sl.notes.isNotBlank()) append("（讲稿）").append(sl.notes).append('\n')
                append('\n')
            }
        }.trim()
        return OfficeWriter.Made(OfficeWriter.zip(parts), text, summary)
    }

    private fun blockTextItem(it: Item): String = when (it) {
        is Bullet -> "  ".repeat(it.level) + "• " + OfficeWriter.plain(it.text)
        is Para -> OfficeWriter.plain(it.text)
        is Quote -> OfficeWriter.plain(it.text)
        is Code -> it.text
        is Tbl -> (listOf(it.t.header) + it.t.rows).joinToString("\n") { r -> r.joinToString(" | ") { c -> OfficeWriter.plain(c) } }
    }

    private fun notesSlide(ns: String, group: String, notes: String): String = XML +
        """<p:notes $ns><p:cSld><p:spTree>$group""" +
        """<p:sp><p:nvSpPr><p:cNvPr id="2" name="幻灯片图像"/><p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr><p:nvPr><p:ph type="sldImg"/></p:nvPr></p:nvSpPr><p:spPr/></p:sp>""" +
        """<p:sp><p:nvSpPr><p:cNvPr id="3" name="备注"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>""" +
        notes.trim().split('\n').joinToString("") { line -> if (line.isBlank()) "<a:p><a:endParaRPr lang=\"zh-CN\"/></a:p>" else """<a:p><a:r><a:rPr lang="zh-CN" altLang="en-US" dirty="0"/><a:t>${esc(line.trim())}</a:t></a:r></a:p>""" } +
        """</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"""

    private fun notesMaster(ns: String, group: String, clrMap: String, font: String): String = XML +
        """<p:notesMaster $ns><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$group""" +
        """<p:sp><p:nvSpPr><p:cNvPr id="2" name="幻灯片图像占位符"/><p:cNvSpPr><a:spLocks noGrp="1" noRot="1" noChangeAspect="1"/></p:cNvSpPr><p:nvPr><p:ph type="sldImg" idx="2"/></p:nvPr></p:nvSpPr>""" +
        """<p:spPr><a:xfrm><a:off x="381000" y="685800"/><a:ext cx="6096000" cy="3429000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln w="12700"><a:solidFill><a:prstClr val="black"/></a:solidFill></a:ln></p:spPr></p:sp>""" +
        """<p:sp><p:nvSpPr><p:cNvPr id="3" name="备注占位符"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" sz="quarter" idx="3"/></p:nvPr></p:nvSpPr>""" +
        """<p:spPr><a:xfrm><a:off x="685800" y="4343400"/><a:ext cx="5486400" cy="4114800"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""" +
        """<p:txBody><a:bodyPr vert="horz" lIns="91440" tIns="45720" rIns="91440" bIns="45720" rtlCol="0"/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>""" +
        """</p:spTree></p:cSld><p:clrMap $clrMap/>""" +
        """<p:notesStyle><a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1200" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill>$font</a:defRPr></a:lvl1pPr></p:notesStyle></p:notesMaster>"""

    /** 主题：配色 + 字体（中文微软雅黑）+ 一套最简的格式方案（PowerPoint 要求三种填充、线条、效果、背景各有三个）。 */
    private fun themeXml(th: Theme): String {
        val fill = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
        val ln = { w: Int -> """<a:ln w="$w" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>""" }
        val fonts = """<a:latin typeface="微软雅黑"/><a:ea typeface="微软雅黑"/><a:cs typeface=""/>"""
        return XML + """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="AI集训营"><a:themeElements>""" +
            """<a:clrScheme name="AI集训营"><a:dk1><a:srgbClr val="${if (th.dark) "0F172A" else "262626"}"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="${if (th.dark) "1E293B" else th.primary}"/></a:dk2><a:lt2><a:srgbClr val="${th.light}"/></a:lt2>""" +
            """<a:accent1><a:srgbClr val="${th.primary}"/></a:accent1><a:accent2><a:srgbClr val="${th.accent}"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>""" +
            """<a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>""" +
            """<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>""" +
            """<a:fontScheme name="AI集训营"><a:majorFont>$fonts</a:majorFont><a:minorFont>$fonts</a:minorFont></a:fontScheme>""" +
            """<a:fmtScheme name="AI集训营"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst><a:lnStyleLst>${ln(6350)}${ln(12700)}${ln(19050)}</a:lnStyleLst>""" +
            """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
            """<a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""
    }
}
