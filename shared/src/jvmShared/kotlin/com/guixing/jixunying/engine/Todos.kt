package com.guixing.jixunying.engine

/**
 * 待办清单（2026-10-18，开发补齐第 4 样；Claude Code 的 TodoWrite / Task 工具）：多步任务列出来，做到哪一步看得见。
 * 模型给一段文字，一行一项，开头标状态：[x] 做完、[>] 正在做、[ ] 没做（也认 - [x] 这种 Markdown 写法）。
 * 存成 ToolStep(kind = "todo")，output 是规整后的几行；一条回答里只留最新的一张（界面上原地刷新）。
 */
object Todos {
    /** 工具说明（两栏同一句）。 */
    const val DESC = "把这次任务拆成几步列成待办清单，用户能看到你做到哪一步。一行一项，开头写状态：[x] 做完 / [>] 正在做 / [ ] 没做。" +
        "开始做多步任务时先列一次，每做完一步再调一次更新（整张清单重写）。只有一两步的小事不用列。"

    /** 规整成「[x] / [>] / [ ] + 文字」的几行；一项都没有返回空字符串。 */
    fun normalize(raw: String): String = raw.lines().mapNotNull { line ->
        val t = line.trim().removePrefix("-").removePrefix("*").trim()
        if (t.isEmpty()) return@mapNotNull null
        val m = Regex("^\\[(.?)\\]\\s*(.*)$").find(t)
        val (mark, text) = if (m == null) " " to t else m.groupValues[1] to m.groupValues[2]
        val st = when (mark.lowercase()) { "x", "√", "✓" -> "x"; ">", "~", "-", "*" -> ">"; else -> " " }
        if (text.isBlank()) null else "[$st] ${text.trim()}"
    }.take(30).joinToString("\n")

    /** 给模型的回话。 */
    fun reply(lines: String): String {
        val all = lines.lines().filter { it.isNotBlank() }
        val done = all.count { it.startsWith("[x]") }
        return "待办清单更新了：${all.size} 项，做完 $done 项。"
    }
}
