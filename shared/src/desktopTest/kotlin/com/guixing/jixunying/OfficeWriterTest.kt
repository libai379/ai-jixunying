package com.guixing.jixunying

import com.guixing.jixunying.engine.DocExtract
import com.guixing.jixunying.engine.OfficeWriter
import com.guixing.jixunying.engine.OfficeWriter.CellVal
import com.guixing.jixunying.model.OfficeSettings
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Word / Excel 生成（OfficeWriter）。每个 XML 都要是合法的、DocExtract 能读回来、单元格类型认得对。
 * 设了 JXY_OFFICE_OUT=文件夹 就把生成的文件存下来，拿 python-docx / openpyxl 或者 Word / WPS 再打开看。
 */
class OfficeWriterTest {
    private val out = System.getenv("JXY_OFFICE_OUT")?.let { File(it).apply { mkdirs() } }

    private val report = """
        # 2026 年第三季度项目验收报告

        本报告总结 **第三季度** 的交付情况，详见 [项目主页](https://example.com/p)。

        ## 一、项目概况

        项目于 7 月启动，9 月底完成验收。主要成果：

        - 完成登录页改版
          - 支持扫码登录
          - 支持短信验证码
        - 上线数据看板
        - ~~旧版报表~~ 已下线

        ## 二、进度与费用

        | 阶段 | 计划完成 | 实际完成 | 费用（元） |
        |---|---|---|---:|
        | 需求 | 2026-07-15 | 2026-07-14 | 35,000 |
        | 开发 | 2026-08-31 | 2026-09-05 | 128,500.50 |
        | 测试 | 2026-09-20 | 2026-09-25 | 22,000 |

        ## 三、问题与改进

        1. 接口文档更新不及时
        2. 测试环境不稳定
        3. 需求变更 3 次

        > 说明：费用含税，*不含* 人力成本。

        [分页]

        ## 四、附录

        ```
        版本号 v2.3.1
        构建时间 2026-09-30 18:00
        ```

        ### 4.1 联系人

        负责人：张三 `13800138000`
    """.trimIndent()

    private fun parts(bytes: ByteArray): Map<String, String> {
        val m = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return m
    }

    private fun wellFormed(name: String, xml: String) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))) }
            .onFailure { throw AssertionError("$name 不是合法的 XML：${it.message}") }
    }

    @Test
    fun wordInThreeStyles() {
        for (style in listOf("general", "formal", "official")) {
            val made = OfficeWriter.word(report, OfficeSettings(wordStyle = style, headerTitle = true), OfficeWriter.WordOptions(toc = true))
            val p = parts(made.bytes)
            p.forEach { (n, x) -> if (n.endsWith(".xml") || n.endsWith(".rels")) wellFormed(n, x) }
            val doc = p["word/document.xml"]!!
            // 第一个一级标题当文档标题；## 提成一级标题；有目录、分页、表头重复、超链接
            assertTrue("""<w:pStyle w:val="Title"/>""" in doc)
            assertTrue("""<w:pStyle w:val="Heading1"/>""" in doc && """<w:pStyle w:val="Heading2"/>""" in doc)
            assertTrue("TOC \\o" in doc, "要目录就有目录")
            assertTrue("""<w:br w:type="page"/>""" in doc)
            assertTrue("<w:tblHeader/>" in doc)
            assertTrue("<w:hyperlink" in doc && "https://example.com/p" in p["word/_rels/document.xml.rels"]!!)
            assertTrue("NUMPAGES" in p["word/footer1.xml"]!! || "— " in p["word/footer1.xml"]!!)
            assertTrue("2026 年第三季度项目验收报告" in p["word/header1.xml"]!!)
            // 数字列表单独编号，圆点列表有两级
            assertTrue("""<w:numId w:val="2"/>""" in doc && """<w:ilvl w:val="1"/>""" in doc)
            val back = DocExtract.extract("报告.docx", made.bytes).first!!
            listOf("项目概况", "128,500.50", "接口文档更新不及时", "版本号 v2.3.1", "13800138000").forEach { assertTrue(it in back, "读回来要有「$it」") }
            if (style == "official") assertTrue("仿宋" in p["word/styles.xml"]!! && """w:lineRule="exact"""" in p["word/styles.xml"]!!)
            out?.let { File(it, "报告-$style.docx").writeBytes(made.bytes) }
        }
        // 短文档（不到 3000 字）不自动加目录；设置是通用、AI 指定公文：正文跟公文首行缩进
        val short = OfficeWriter.word(report, OfficeSettings(), OfficeWriter.WordOptions(style = "official"))
        val sp = parts(short.bytes)
        assertTrue("TOC \\o" !in sp["word/document.xml"]!!)
        assertTrue("w:firstLineChars=\"200\"" in sp["word/styles.xml"]!!.substringAfter("w:styleId=\"Normal\"").substringBefore("</w:style>"))
        // 横向、指定页眉、不要目录
        val wide = OfficeWriter.word("## 一\n正文\n## 二\n## 三\n## 四", OfficeSettings(), OfficeWriter.WordOptions(title = "横向测试", header = "内部资料", landscape = true, toc = false))
        val d = parts(wide.bytes)["word/document.xml"]!!
        assertTrue("""w:orient="landscape"""" in d && "TOC \\o" !in d)
        out?.let { File(it, "横向.docx").writeBytes(wide.bytes) }
    }

    private val deck = """
        # 2026 年第三季度工作汇报
        产品研发部 · 张三

        ## 第一部分：成果

        ## 本季度亮点
        - 完成 **登录页改版**，转化率提升 12.5%
          - 扫码登录上线
          - 短信验证码改造
        - 上线数据看板，日活 3,200
        - ~~旧版报表~~ 已下线

        备注：开场先讲转化率，强调扫码登录是用户呼声最高的功能。
        时间控制在两分钟。

        ## 进度与费用
        | 阶段 | 计划完成 | 实际完成 | 费用（元） |
        |---|---|---|---:|
        | 需求 | 2026-07-15 | 2026-07-14 | 35,000 |
        | 开发 | 2026-08-31 | 2026-09-05 | 128,500 |
        | 测试 | 2026-09-20 | 2026-09-25 | 22,000 |

        ## 第二部分：问题

        ## 遇到的问题
        1. 接口文档更新不及时，前后端对不上的情况一个月出现了好几次，返工浪费了大约两周时间
        2. 测试环境不稳定，经常因为数据库连接池耗尽导致自动化测试大面积失败
        3. 需求变更 3 次，其中两次发生在开发后期，导致排期整体延后一周
        4. 第三方短信服务商在 8 月中旬出现过一次长达四小时的故障，影响了验证码登录
        5. 新同事上手慢，缺少系统的入职文档和代码规范说明
        6. 跨部门沟通链路长，一个小改动需要经过三层审批
        7. 线上监控告警太多，真正重要的告警经常被淹没
        8. 代码评审不及时，合并请求平均要等两天才有人看
        9. 发布流程全靠手工，每次上线都要两个人盯一晚上，出错了也很难回滚
        10. 数据看板的口径和财务报表对不上，每个月对账都要花一天时间
        11. 移动端适配问题多，安卓低版本机型上偶尔白屏，排查周期长
        12. 埋点设计不统一，同一个指标在不同页面的统计方式不一样
        13. 外包团队交付质量参差不齐，验收标准没写清楚
        14. 技术债越来越多，老模块没人敢动，改一处牵一发而动全身

        > 这些问题下季度逐一跟进。

        ## 全部任务清单
        | 序号 | 任务 | 负责人 | 状态 |
        |---|---|---|---|
        | 1 | 登录页改版 | 张三 | 完成 |
        | 2 | 扫码登录 | 李四 | 完成 |
        | 3 | 短信验证码 | 王五 | 完成 |
        | 4 | 数据看板 | 赵六 | 完成 |
        | 5 | 报表下线 | 张三 | 完成 |
        | 6 | 接口文档 | 李四 | 进行中 |
        | 7 | 测试环境 | 王五 | 进行中 |
        | 8 | 监控告警 | 赵六 | 进行中 |
        | 9 | 代码评审 | 张三 | 未开始 |
        | 10 | 入职文档 | 李四 | 未开始 |
        | 11 | 审批流程 | 王五 | 未开始 |
        | 12 | 性能优化 | 赵六 | 未开始 |
        | 13 | 安全加固 | 张三 | 未开始 |
        | 14 | 灰度发布 | 李四 | 未开始 |
        | 15 | 复盘会 | 王五 | 未开始 |

        ## 谢谢
    """.trimIndent()

    @Test
    fun pptDecks() {
        for (theme in listOf("blue", "green", "orange", "dark", "mono")) {
            val made = com.guixing.jixunying.engine.PptWriter.ppt(deck, OfficeSettings(pptTheme = theme))
            val p = parts(made.bytes)
            p.forEach { (n, x) -> if (n.endsWith(".xml") || n.endsWith(".rels")) wellFormed(n, x) }
            val slides = p.keys.filter { Regex("""ppt/slides/slide\d+\.xml""").matches(it) }
            // 封面、目录、章节、亮点、进度、章节、问题（拆成两页）、清单（15 行拆成两页）、谢谢
            assertEquals(11, slides.size, "页数：${made.summary}")
            assertTrue("目录" in p["ppt/slides/slide2.xml"]!!, "内容页 4 页以上加目录")
            assertTrue("（续）" in p.filterKeys { it in slides }.values.joinToString(""), "放不下的页拆开")
            assertTrue(p.keys.any { it.startsWith("ppt/notesSlides/") } && "开场先讲转化率" in p.filterKeys { it.startsWith("ppt/notesSlides/") }.values.joinToString(""))
            assertTrue("""<p:sldSz cx="12192000" cy="6858000"/>""" in p["ppt/presentation.xml"]!!)
            val back = DocExtract.extract("汇报.pptx", made.bytes).first!!
            listOf("2026 年第三季度工作汇报", "登录页改版", "128,500", "复盘会", "谢谢").forEach { assertTrue(it in back, "读回来要有「$it」") }
            out?.let { File(it, "汇报-$theme.pptx").writeBytes(made.bytes) }
        }
        // 4:3、不拆页、没讲稿
        val narrow = com.guixing.jixunying.engine.PptWriter.ppt(deck, OfficeSettings(pptWide = false, pptSplit = false, pptNotes = false, pptAgenda = false))
        val np = parts(narrow.bytes)
        assertTrue("""type="screen4x3"""" in np["ppt/presentation.xml"]!!)
        assertTrue(np.keys.none { it.startsWith("ppt/notesSlides/") || it.startsWith("ppt/notesMasters/") })
        assertTrue("（续）" !in np.values.joinToString(""))
        out?.let { File(it, "汇报-4比3.pptx").writeBytes(narrow.bytes) }
    }

    @Test
    fun cellValues() {
        assertEquals(CellVal.Num(12000.0, "#,##0"), OfficeWriter.cellValue("12,000"))
        assertEquals(CellVal.Num(128500.5, "#,##0.00"), OfficeWriter.cellValue("128,500.50"))
        assertEquals(CellVal.Num(0.125, "0.0%"), OfficeWriter.cellValue("12.5%"))
        assertEquals(CellVal.Num(1200.0, "\"¥\"#,##0.00"), OfficeWriter.cellValue("¥1,200"))
        assertEquals(CellVal.Num(46305.0, "yyyy-mm-dd"), OfficeWriter.cellValue("2026-10-10"))
        assertEquals(CellVal.Formula("SUM(B2:B4)"), OfficeWriter.cellValue("=SUM(B2:B4)"))
        assertEquals(CellVal.Text("13800138000"), OfficeWriter.cellValue("13800138000"), "手机号照文字")
        assertEquals(CellVal.Text("007"), OfficeWriter.cellValue("007"), "0 开头的编号照文字")
        assertEquals(CellVal.Num(42.0, "General"), OfficeWriter.cellValue("42"))
        assertEquals(CellVal.Num(3.14, "0.00"), OfficeWriter.cellValue("3.14"))
        assertEquals(CellVal.Text("12万"), OfficeWriter.cellValue("12万"))
        assertEquals(CellVal.Num(1500.0, "General"), OfficeWriter.cellValue("1500", thousands = false))
        assertEquals("SUM(B3:B5)/\$C\$3+LOG10(A3)+Sheet2!A2&\"B2\"", OfficeWriter.shiftRows("SUM(B2:B4)/\$C\$2+LOG10(A2)+Sheet2!A2&\"B2\"", 1))
    }

    @Test
    fun excelSheets() {
        val md = """
            ## 销售汇总
            2026 年 1–3 月各区域销售额

            | 区域 | 1月 | 2月 | 3月 | 季度合计 | 增长 |
            |---|---|---|---|---|---|
            | 华东 | 12,000 | 13,500 | 15,200 | =SUM(B2:D2) | 12.5% |
            | 华北 | 9,800 | 10,200 | 9,900 | =SUM(B3:D3) | -3.2% |
            | **华南** | 15,000 | 16,800 | 18,100 | =SUM(B4:D4) | 20.7% |

            数据来源：销售系统导出，单位：元。

            ## 联系人
            | 姓名 | 手机 | 入职日期 | 工号 | 在职 |
            |---|---|---|---|---|
            | 张三 | 13800138000 | 2024-03-01 | 007 | TRUE |
            | 李四 | 13900139000 | 2025-07-15 | 012 | FALSE |
        """.trimIndent()
        val made = OfficeWriter.excel(md, OfficeSettings(excelZebra = true), totalRow = true)
        val p = parts(made.bytes)
        p.forEach { (n, x) -> if (n.endsWith(".xml") || n.endsWith(".rels")) wellFormed(n, x) }
        val wb = p["xl/workbook.xml"]!!
        assertTrue("""name="销售汇总"""" in wb && """name="联系人"""" in wb)
        assertTrue("fullCalcOnLoad" in wb && "_xlnm._FilterDatabase" in wb)
        val s1 = p["xl/worksheets/sheet1.xml"]!!
        assertTrue("<f>SUM(B3:D3)</f>" in s1 && "<f>SUM(B5:D5)</f>" in s1, "AI 按表头第 1 行写的公式，加了表名行后行号往下挪一行")
        assertTrue("<f>SUM(B3:B5)</f>" in s1 && "<f>SUM(E3:E5)</f>" in s1, "合计行：数字列和公式列求和")
        assertTrue("SUM(F3:F5)" !in s1, "百分比列不求和")
        assertTrue("""<pane ySplit="2"""" in s1, "冻结到表头（表名行 + 表头）")
        assertTrue("""<mergeCell ref="A1:F1"/>""" in s1, "表名行合并")
        assertTrue("数据来源" in s1, "表后的字当备注")
        val s2 = p["xl/worksheets/sheet2.xml"]!!
        assertTrue("13800138000" in s2 && """t="inlineStr"><is><t xml:space="preserve">13800138000""" in s2, "手机号是文字")
        assertTrue("<v>45352</v>" in s2, "日期存成日期序号（2024-03-01）")
        assertTrue("""t="b"><v>1</v>""" in s2)
        assertTrue("合计" !in s2, "没有能合计的列就不加合计行")
        assertTrue("¥" !in p["xl/styles.xml"]!! && "0.0%" in p["xl/styles.xml"]!!)
        val back = DocExtract.extract("表.xlsx", made.bytes).first!!
        listOf("华东", "联系人", "张三").forEach { assertTrue(it in back, "读回来要有「$it」：$back") }
        out?.let { File(it, "销售.xlsx").writeBytes(made.bytes) }
    }
}
