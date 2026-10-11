package com.guixing.jixunying

import com.guixing.jixunying.engine.Council
import com.guixing.jixunying.model.MathText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 回答里的 LaTeX 公式转成普通符号；会审的代号换回真名。 */
class MathTextTest {
    @Test
    fun latexBecomesPlain() {
        // 真模型（会审主席）写的样子
        assertEquals("⌈40/12⌉=4 正确", MathText.plain("\\(\\lceil 40/12\\rceil=4\\) 正确").replace("  ", " "))
        assertEquals("12×3=36", MathText.formula("12\\times3=36"))
        assertEquals("4×4+8×3=40", MathText.formula("4\\times4+8\\times3=40"))
        assertEquals("40÷12=3⋯4", MathText.formula("40\\div12=3\\cdots4"))
        assertEquals("a/b + (x+1)/2", MathText.formula("\\frac{a}{b} + \\frac{x+1}{2}"))
        assertEquals("√2 ≤ x² ≠ y₁", MathText.formula("\\sqrt{2} \\le x^2 \\ne y_1"))
        assertEquals("⌈3.33⋯⌉ = 4", MathText.formula("\\left\\lceil 3.33\\cdots \\right\\rceil = 4"))
        // 独立一行的公式
        val block = MathText.plain("合计：\n\\[\n12\\times3=36\n\\]\n完")
        assertTrue("12×3=36" in block && "\\[" !in block, block)
        assertTrue("⌈m/n⌉" in MathText.plain("至少 $$\\left\\lceil \\frac{m}{n} \\right\\rceil$$ 个"))
        // 单个 $ 只在里面有命令时才当公式；价格不动；代码块里不动
        assertEquals("价格 $5 和 $6", MathText.plain("价格 $5 和 $6"))
        assertEquals("比例 π/2", MathText.plain("比例 \$\\frac{\\pi}{2}\$"))
        val code = "```\npath = \"C:\\\\Users\\\\x\"\nprint(\\(1\\))\n```"
        assertEquals(code, MathText.plain(code))
        // 没有反斜杠的原样返回
        assertEquals("普通文字 **加粗**", MathText.plain("普通文字 **加粗**"))
    }

    @Test
    fun councilRevealsNames() {
        val names = mapOf("甲" to "阿麦", "乙" to "阿米")
        assertEquals("阿麦（回答甲）对，【阿米（回答乙）】也对；乙、丙一致",
            Council.reveal("回答甲对，【乙】也对；乙、丙一致", names))
        assertEquals(listOf(2, 3, 0, 1), Council.parseRanking("说了很多\n**名次：丙 > 丁 > 甲 > 乙**", 4))
        assertEquals(null, Council.parseRanking("没写名次", 4))
        assertEquals(mapOf(2 to 1.0, 3 to 2.0, 0 to 3.0, 1 to 4.0), Council.averageRanks(listOf(listOf(2, 3, 0, 1), listOf(2, 3, 0, 1)), 4))
    }
}
