package com.guixing.jixunying.engine.code

/**
 * Code 栏「这类命令以后不再问」怎么判断（2026-10-17，安全核心，写代码的 AI 只调用不改）。
 *
 * 照 Claude Code 的做法：批准一条命令时可以选「同样开头的命令这个会话里不再问」，记下的是命令开头（前缀），
 * 以后完全以这个开头的命令直接跑。出处：https://code.claude.com/docs/en/permissions 的权限表，
 * Bash 一行「"Yes, and don't ask again" behavior: Permanently per repository and command」；
 * 我们记在会话上（CodeSpec.allowedCommands），删会话就没了，比它保守一点。
 *
 * 三条一律不自动放行、也不给「不再问」（docs/Code栏设计说明.md 第 4 节第 2 条那份「永远要问」的清单）：
 * - 拼起来的命令：带 ; & | ` $( > < 换行 的，后半截可能是别的命令，前缀管不住；
 * - 危险命令：递归删除、格式化、改注册表、改系统环境变量、下载后直接执行、强推 / 硬重置 / 清理 Git 等；
 * - 空命令。
 */
object CodeCommands {
    /** 这几个程序后面跟的子命令才是真正做的事（git status 和 git push 差别很大），前缀取两节。 */
    private val TWO_PART = setOf(
        "git", "npm", "npx", "pnpm", "yarn", "bun", "gradlew", "gradle", "mvn", "mvnw", "dotnet", "cargo", "go",
        "python", "python3", "py", "pip", "pip3", "uv", "node", "deno", "flutter", "dart", "adb", "docker", "kubectl",
        "gh", "composer", "php", "ruby", "bundle", "rake", "java", "kotlin", "kotlinc", "swift", "make", "cmake",
    )

    private val COMPOUND = Regex("[;&|`<>\\n\\r]|\\$\\(")

    private val DANGER = listOf(
        Regex("\\brm\\b[^\\n]*\\s-[a-z]*[rf]", RegexOption.IGNORE_CASE),
        Regex("\\bremove-item\\b[^\\n]*-r(ecurse)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(rd|rmdir)\\b[^\\n]*/s", RegexOption.IGNORE_CASE),
        Regex("\\b(del|erase)\\b[^\\n]*/[sq]", RegexOption.IGNORE_CASE),
        Regex("\\bformat(-volume)?\\b\\s+[a-z]:?", RegexOption.IGNORE_CASE),
        Regex("\\b(diskpart|bcdedit|shutdown|cipher\\s+/w)\\b", RegexOption.IGNORE_CASE),
        Regex("\\breg(\\.exe)?\\s+(add|delete|import|load|restore)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(set|new|remove)-itemproperty\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsetx\\b", RegexOption.IGNORE_CASE),
        Regex("setenvironmentvariable", RegexOption.IGNORE_CASE),
        Regex("\\b(iex|invoke-expression)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bgit\\s+push\\b[^\\n]*(--force|\\s-f\\b|--mirror|--delete)", RegexOption.IGNORE_CASE),
        Regex("\\bgit\\s+(reset\\s+--hard|clean\\b|checkout\\s+--\\s|filter-branch|update-ref\\s+-d)", RegexOption.IGNORE_CASE),
        Regex("\\b(takeown|icacls)\\b", RegexOption.IGNORE_CASE),
    )

    /** 危险命令：每次都问，不许「不再问」。 */
    fun dangerous(cmd: String): Boolean = DANGER.any { it.containsMatchIn(cmd) }

    /** 拼起来的命令（前缀管不住后半截）。 */
    fun compound(cmd: String): Boolean = COMPOUND.containsMatchIn(cmd)

    /** 这条命令能记成「同样开头不再问」的话，记哪个开头；不能记返回 null。 */
    fun prefixOf(cmd: String): String? {
        val t = cmd.trim()
        if (t.isEmpty() || compound(t) || dangerous(t)) return null
        val parts = t.split(Regex("\\s+"))
        val first = program(parts[0])
        // 再开一层外壳、直接执行一段代码的：记了开头等于什么都放行
        if (first in SHELLS) return null
        val second = parts.getOrNull(1)?.lowercase()
        if (first in INTERPRETERS && second in setOf("-c", "-e", "--eval", "-command", "-encodedcommand")) return null
        val n = when {
            // python -m pytest：记到模块名
            first in INTERPRETERS && second == "-m" && parts.size > 2 -> 3
            first in TWO_PART && second != null && !second.startsWith("-") -> 2
            else -> 1
        }
        return parts.take(n).joinToString(" ")
    }

    /** 再开一层外壳的程序：不给「不再问」。 */
    private val SHELLS = setOf("powershell", "pwsh", "cmd", "bash", "sh", "zsh", "wsl", "start-process", "invoke-command")
    /** 能直接执行一段代码的解释器：-c / -e 不给「不再问」，-m 记到模块名。 */
    private val INTERPRETERS = setOf("python", "python3", "py", "node", "deno", "ruby", "perl", "php")

    /** 这条命令被记下的哪个开头放行了；都没有返回 null。拼起来的、危险的永远不放行。 */
    fun allowedBy(cmd: String, prefixes: List<String>): String? {
        val t = cmd.trim()
        if (t.isEmpty() || compound(t) || dangerous(t)) return null
        val parts = t.split(Regex("\\s+"))
        // 记过「python」开头（比如批准过 python --version），也不能顺带放行 python -c 一段代码、再开一层外壳
        val first = program(parts[0])
        val second = parts.getOrNull(1)?.lowercase()
        if (first in SHELLS || (first in INTERPRETERS && second in setOf("-c", "-e", "--eval", "-command", "-encodedcommand"))) return null
        return prefixes.firstOrNull { p ->
            val pp = p.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            pp.isNotEmpty() && pp.size <= parts.size && pp.indices.all { i ->
                if (i == 0) program(pp[0]) == program(parts[0]) else pp[i].equals(parts[i], ignoreCase = true)
            }
        }
    }

    /** 程序名统一一下：去掉 .\ ./ 前缀和 .exe / .cmd / .bat 后缀，小写。 */
    private fun program(token: String): String = token.lowercase()
        .removePrefix(".\\").removePrefix("./")
        .removeSuffix(".exe").removeSuffix(".cmd").removeSuffix(".bat")
}
