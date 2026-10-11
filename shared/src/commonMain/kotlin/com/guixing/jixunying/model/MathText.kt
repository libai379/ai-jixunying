package com.guixing.jixunying.model

/**
 * 回答里的 LaTeX 公式转成普通符号（1.5.0）：聊天界面、微信、导出的 Word 都显示不了 LaTeX，
 * 模型写 \( \lceil 40/12 \rceil \) 就成了一串反斜杠。只转公式定界符（\( \)、\[ \]、$$ $$、带命令的 $ $）里面的内容，
 * 代码块不碰；认不出的命令去掉反斜杠留名字，至少能看。
 */
object MathText {
    private val symbols = linkedMapOf(
        "\\times" to "×", "\\div" to "÷", "\\cdots" to "⋯", "\\ldots" to "…", "\\cdot" to "·", "\\pm" to "±", "\\mp" to "∓",
        "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\le" to "≤", "\\ge" to "≥", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\infty" to "∞", "\\lceil" to "⌈", "\\rceil" to "⌉", "\\lfloor" to "⌊", "\\rfloor" to "⌋",
        "\\rightarrow" to "→", "\\Rightarrow" to "⇒", "\\leftarrow" to "←", "\\Leftarrow" to "⇐", "\\to" to "→", "\\iff" to "⇔",
        "\\in" to "∈", "\\notin" to "∉", "\\subseteq" to "⊆", "\\subset" to "⊂", "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅",
        "\\forall" to "∀", "\\exists" to "∃", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\partial" to "∂", "\\nabla" to "∇",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\Delta" to "Δ", "\\epsilon" to "ε", "\\theta" to "θ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\pi" to "π", "\\sigma" to "σ", "\\Sigma" to "Σ", "\\phi" to "φ", "\\omega" to "ω", "\\Omega" to "Ω",
        "\\circ" to "°", "\\%" to "%", "\\quad" to "  ", "\\qquad" to "    ", "\\," to " ", "\\;" to " ", "\\!" to "", "\\ " to " ",
        "\\left" to "", "\\right" to "", "\\{" to "{", "\\}" to "}",
    )
    private val sup = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', 'n' to 'ⁿ', '+' to '⁺', '-' to '⁻')
    private val sub = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉')

    /** 一段公式（不带定界符）→ 普通文字。 */
    fun formula(src: String): String {
        var s = src.trim()
        // 先换符号（长的命令先换：\leq 在 \le 前面），这样分数里的 \pi 先变成 π，不会被当成「复杂」加括号
        for ((k, v) in symbols.entries.sortedByDescending { it.key.length }) s = s.replace(k, v)
        // \frac{a}{b} → a/b（a、b 复杂就加括号）；\sqrt{x} → √x；\text{…} \mathrm{…} → …
        repeat(4) {
            s = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""").replace(s) { m ->
                fun wrap(x: String) = if (x.length <= 2 || Regex("""^[\w.]+$""").matches(x)) x else "($x)"
                wrap(m.groupValues[1]) + "/" + wrap(m.groupValues[2])
            }
            s = Regex("""\\sqrt\{([^{}]*)\}""").replace(s) { m -> "√" + (if (m.groupValues[1].length <= 2) m.groupValues[1] else "(${m.groupValues[1]})") }
            s = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname|textbf)\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
        }
        // 上标、下标：^2 ^{10} _1
        s = Regex("""\^\{([0-9n+\-]{1,4})\}|\^([0-9n])""").replace(s) { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).map { sup[it] ?: it }.joinToString("") }
        s = Regex("""_\{([0-9]{1,3})\}|_([0-9])""").replace(s) { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).map { sub[it] ?: it }.joinToString("") }
        // 认不出的命令：去掉反斜杠留名字；剩下的花括号去掉
        s = Regex("""\\([a-zA-Z]+)""").replace(s) { it.groupValues[1] }
        s = s.replace("{", "").replace("}", "")
        // LaTeX 里命令后面的空格不算：括号里侧的空格去掉（⌈ 40/12 ⌉ → ⌈40/12⌉）
        s = s.replace(Regex("""([⌈⌊(])\s+"""), "$1").replace(Regex("""\s+([⌉⌋)])"""), "$1")
        return s.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    private val display = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]""")
    private val inline = Regex("""\\\(([\s\S]+?)\\\)|(?<![\\$\w])\$([^$\n]*\\[a-zA-Z{^_][^$\n]*)\$(?!\w)""")

    /** 整段回答：代码块外面的公式转成普通文字。 */
    fun plain(md: String): String {
        if ('\\' !in md && "$$" !in md) return md
        val parts = md.split("```")
        return parts.mapIndexed { i, part ->
            if (i % 2 == 1) part   // 代码块里面不动
            else {
                var t = display.replace(part) { m -> "\n" + formula(m.groupValues[1].ifEmpty { m.groupValues[2] }) + "\n" }
                t = inline.replace(t) { m -> formula(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
                t
            }
        }.joinToString("```")
    }
}
