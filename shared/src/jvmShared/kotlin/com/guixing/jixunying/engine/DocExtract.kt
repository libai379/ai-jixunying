package com.guixing.jixunying.engine

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** 把各种文档转成文字给模型看。图片不在这里处理（直接按图片发给能看图的模型）。 */
object DocExtract {

    private val textExt = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "log", "conf", "properties",
        "html", "htm", "css", "js", "ts", "jsx", "tsx", "vue", "kt", "kts", "java", "py", "go", "rs", "c", "h", "cpp", "hpp",
        "cs", "swift", "rb", "php", "sh", "bat", "ps1", "sql", "gradle", "srt", "tex", "rtf",
    )

    fun isImage(name: String, mime: String) =
        mime.startsWith("image/") || ext(name) in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun imageMime(name: String, mime: String) = when {
        mime.startsWith("image/") -> mime
        ext(name) == "jpg" || ext(name) == "jpeg" -> "image/jpeg"
        ext(name) == "webp" -> "image/webp"
        ext(name) == "gif" -> "image/gif"
        ext(name) == "bmp" -> "image/bmp"
        else -> "image/png"
    }

    /** 返回 (文字, 说明)。文字为 null 表示读不出来。 */
    fun extract(name: String, bytes: ByteArray): Pair<String?, String> = try {
        when (val e = ext(name)) {
            "pdf" -> extractPdfText(bytes)
            "docx" -> ooxml(bytes, Regex("""^word/(document|footnotes|endnotes)\d*\.xml$""")) to ""
            "pptx" -> ooxml(bytes, Regex("""^ppt/slides/slide\d+\.xml$"""), slides = true) to ""
            "xlsx" -> xlsx(bytes) to ""
            "doc", "ppt", "xls" -> null to "老版 Office 格式（.$e）读不了，请另存为 .${e}x 再发"
            in textExt -> decodeText(bytes) to ""
            else -> {
                val t = decodeText(bytes)
                if (looksLikeText(t)) t to "" else null to "这种文件（.$e）读不出文字"
            }
        }
    } catch (t: Throwable) {
        null to "读取失败：${t.message?.take(80)}"
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8.removePrefix("﻿")
        return runCatching { String(bytes, charset("GB18030")) }.getOrDefault(utf8)
    }

    private fun looksLikeText(s: String): Boolean {
        if (s.isEmpty()) return false
        val sample = s.take(4000)
        val bad = sample.count { it == '�' || (it < ' ' && it !in "\n\r\t") }
        return bad < sample.length / 50
    }

    private fun zipEntries(bytes: ByteArray, accept: (String) -> Boolean): Map<String, String> {
        val out = sortedMapOf<String, String>(compareBy({ it.filter(Char::isLetter) }, { it.filter(Char::isDigit).toIntOrNull() ?: 0 }, { it }))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && accept(e.name)) out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun ooxml(bytes: ByteArray, pattern: Regex, slides: Boolean = false): String {
        val parts = zipEntries(bytes) { pattern.matches(it) }
        return parts.entries.mapIndexed { i, (_, xml) ->
            val text = xml
                .replace(Regex("""</w:p>|</a:p>"""), "\n")
                .replace(Regex("""<w:tab/>"""), "\t")
                .replace(Regex("""<w:br/>"""), "\n")
                .replace(Regex("""</w:tc>"""), " | ")
                .replace(Regex("""<[^>]+>"""), "")
            val clean = Html.unescape(text).replace(Regex("""\n{3,}"""), "\n\n").trim()
            if (slides) "【第 ${i + 1} 页】\n$clean" else clean
        }.joinToString("\n\n")
    }

    private fun xlsx(bytes: ByteArray): String {
        val parts = zipEntries(bytes) { it == "xl/sharedStrings.xml" || it.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) || it == "xl/workbook.xml" }
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            Regex("""<si>([\s\S]*?)</si>""").findAll(xml).map { si ->
                Regex("""<t[^>]*>([\s\S]*?)</t>""").findAll(si.groupValues[1]).joinToString("") { Html.unescape(it.groupValues[1]) }
            }.toList()
        }.orEmpty()
        val names = parts["xl/workbook.xml"]?.let { xml -> Regex("""<sheet [^>]*name="([^"]+)"""").findAll(xml).map { Html.unescape(it.groupValues[1]) }.toList() }.orEmpty()
        val sb = StringBuilder()
        parts.filterKeys { it.startsWith("xl/worksheets/") }.entries.forEachIndexed { i, (_, xml) ->
            sb.append("【工作表 ").append(names.getOrNull(i) ?: (i + 1).toString()).append("】\n")
            var rows = 0
            for (row in Regex("""<row[^>]*>([\s\S]*?)</row>""").findAll(xml)) {
                val cells = Regex("""<c ([^>]*?)(/>|>([\s\S]*?)</c>)""").findAll(row.groupValues[1]).map { c ->
                    val attrs = c.groupValues[1]
                    val inner = c.groupValues[3]
                    val v = Regex("""<v>([\s\S]*?)</v>""").find(inner)?.groupValues?.get(1)
                    when {
                        attrs.contains("t=\"s\"") -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        attrs.contains("t=\"inlineStr\"") -> Regex("""<t[^>]*>([\s\S]*?)</t>""").find(inner)?.groupValues?.get(1)?.let(Html::unescape).orEmpty()
                        else -> v?.let(Html::unescape).orEmpty()
                    }
                }.toList()
                if (cells.any { it.isNotBlank() }) {
                    sb.append(cells.joinToString(" | ")).append('\n')
                    if (++rows >= 2000) { sb.append("…（行数太多，后面省略）\n"); break }
                }
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
