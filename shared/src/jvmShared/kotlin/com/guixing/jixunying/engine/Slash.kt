package com.guixing.jixunying.engine

import com.guixing.jixunying.model.SlashCommand
import java.io.File

/**
 * 斜杠命令（2026-10-18，开发补齐第 12、13 样；Claude Code 的自定义命令放在项目 .claude/commands 底下的 .md，文件名就是命令名，
 * 文件里写 $ARGUMENTS 会换成命令后面跟的字）。我们的来源：内置的 + 设置 → 个性化 里自己写的 + Code 会话项目里的。
 */
object Slash {
    /** 内置：代码审查（第 13 样；Claude Code 的 /code-review）。只看不改。 */
    val REVIEW = SlashCommand("审查", "审查这次还没提交的改动：只看不改，按严重程度列问题",
        "审查这次还没提交的改动（先跑 git status 和 git diff 看改了什么；不是 Git 仓库就看这个会话里改过的文件）。只看不改，不要动任何文件。\n" +
            "按严重程度从高到低列出问题，每条写：文件和行号、问题是什么、会导致什么后果（具体的输入或操作会出什么错）、怎么改。" +
            "先找会出错的（逻辑错误、边界情况、空值、并发、安全），再说可以简化的。没发现问题就直说没问题。\$ARGUMENTS")

    val BUILTIN = listOf(REVIEW, SlashCommand("review", "同 /审查", REVIEW.text))

    /** 项目里的：.claude/commands/ 底下的 .md（子文件夹里的名字用「文件夹:名字」）。文件开头 --- 包起来的 description: 当说明。 */
    fun fromProject(folder: File): List<SlashCommand> {
        val dir = File(folder, ".claude/commands")
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension.equals("md", true) }.take(200).mapNotNull { f ->
            val raw = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val (front, body) = if (raw.startsWith("---")) {
                val end = raw.indexOf("\n---", 3)
                if (end > 0) raw.substring(3, end) to raw.substring(end + 4).trimStart() else "" to raw
            } else "" to raw
            val desc = Regex("(?m)^description:\\s*(.+)$").find(front)?.groupValues?.get(1)?.trim()?.trim('"') ?: body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60)
            val rel = f.relativeTo(dir).path.replace('\\', '/').removeSuffix(".md").removeSuffix(".MD")
            SlashCommand(rel.replace('/', ':'), desc, body)
        }.toList()
    }

    /** 展开：「/名字 后面的字」→ 命令的话（$ARGUMENTS 换成后面的字；没写 $ARGUMENTS 的接在最后）。 */
    fun expand(cmd: SlashCommand, args: String): String =
        if ("\$ARGUMENTS" in cmd.text) cmd.text.replace("\$ARGUMENTS", args.trim()).trim()
        else (cmd.text.trim() + if (args.isBlank()) "" else "\n\n" + args.trim())
}

/**
 * 技能（2026-10-18，开发补齐第 12 样；Claude Code 的 skills：项目 .claude/skills/<名字>/SKILL.md，开头 --- 包起来的 name、description）。
 * 会话开始时只把「名字 + 一句说明 + 说明书在哪」列给 AI，要用时它自己读说明书（省 token，和 Claude Code 一样按需加载）。
 */
object Skills {
    class Skill(val name: String, val description: String, val path: String)

    fun fromProject(folder: File): List<Skill> {
        val dir = File(folder, ".claude/skills")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { d ->
            val f = File(d, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
            val raw = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val front = if (raw.startsWith("---")) raw.substring(3, raw.indexOf("\n---", 3).takeIf { it > 0 } ?: 3) else ""
            fun field(k: String) = Regex("(?m)^$k:\\s*(.+)$").find(front)?.groupValues?.get(1)?.trim()?.trim('"')
            Skill(field("name") ?: d.name, (field("description") ?: raw.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("---") }.orEmpty()).take(300), f.absolutePath)
        }.take(50)
    }

    /** 系统说明里的那一段；没有技能返回空字符串。 */
    fun prompt(list: List<Skill>): String = if (list.isEmpty()) "" else buildString {
        append("\n\n【项目里的技能】（碰到用得上的，先用 read_file 读它的说明书，再照着做）\n")
        list.forEach { append("- ").append(it.name).append("：").append(it.description).append("（说明书：").append(it.path).append("）\n") }
    }
}
