package com.guixing.jixunying.engine.code

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Code 栏的路径判断（安全核心，写代码的 AI 只调用不改）。
 *
 * 2026-10-17 用户改了规矩（「电脑 code 限制那么死，我怎么用来做开发」）：照 Claude Code 的做法，
 * 不再把读写死锁在项目文件夹里，而是分「工作区里」和「工作区外」：
 * - 工作区 = 项目文件夹 + 这个会话加进来的文件夹 / 文件（CodeSpec.extraPaths，相当于 Claude Code 的 /add-dir）；
 * - 工作区里：读不问，改按权限档；工作区外：读和改都先问用户（问不问由 CodeAgent 定，这里只回答「在不在」）。
 * 出处：https://code.claude.com/docs/en/permissions —「Read-only ... No, within the working directory and additional directories」，
 * 「Files in additional directories follow the same permission rules as the original working directory」。
 *
 * resolve 把模型给的路径（相对项目文件夹或绝对路径）规范化成真实路径：
 * - 「..」、短文件名（PROGRA~1）、大小写、符号链接 / 目录联接：一律按 toRealPath 换成真名再判断在不在工作区；
 * - 备用数据流（a.txt:stream）、设备名（CON、NUL、COM1…）、空字符：直接拒绝（null）；
 * - 网络路径（\\服务器\共享、//服务器）：直接拒绝。碰一下就可能让系统拿当前用户的凭据去连那台机器
 *   （Claude Code 也不许把网络路径当工作目录：「looking one up can contact the host it names」，同一页）。
 * 还不存在的文件（要新建的）：找到最近一级存在的上级目录取真实路径，再接上后面几级。
 */
class CodeGuard(root: File, extra: List<String> = emptyList()) {
    val root: Path = root.toPath().toAbsolutePath().normalize().toRealPath()

    /** 工作区的各个根（真实路径）：项目文件夹在最前，后面是加进来的；已经不存在的跳过。 */
    val roots: List<Path> = listOf(this.root) + extra.mapNotNull { p -> runCatching { realOf(p.trim()) }.getOrNull()?.takeIf { Files.exists(it) } }

    /** 把路径换成真实路径的文件；不合法（数据流、设备名、网络路径、空字符）返回 null。不管在不在工作区。 */
    fun resolve(path: String): File? = realOf(path)?.toFile()

    /** 这个文件 / 目录在不在工作区里（按真实路径比）。 */
    fun inScope(file: File): Boolean {
        val real = realOf(file.absolutePath) ?: return false
        return roots.any { real.startsWith(it) }
    }

    /** 项目文件夹里的（旧接口，测试和老代码用）：在项目文件夹里就返回，不在或不合法返回 null。 */
    fun resolveInProject(path: String): File? = realOf(path)?.takeIf { it.startsWith(root) }?.toFile()

    /** 给人和模型看的路径：项目文件夹里的写相对路径（用 /），外面的写完整路径。 */
    fun relative(file: File): String {
        val p = file.toPath().toAbsolutePath().normalize()
        return if (p.startsWith(root)) root.relativize(p).toString().replace('\\', '/') else p.toString()
    }

    /** 这个路径要加进工作区的话加哪一级：目录就是它自己，文件就是它所在的目录。 */
    fun folderToAdd(file: File): String = (if (file.isDirectory) file else file.absoluteFile.parentFile ?: file).absolutePath

    private fun realOf(path: String): Path? {
        val raw = path.trim().removeSurrounding("\"").trim()
        if (raw.isEmpty() || raw.any { it == '\u0000' }) return null
        if (raw.startsWith("\\\\") || raw.startsWith("//")) return null
        // Paths.get 而不是 Path.of：这层代码手机上也编译，安卓 8.0 没有 Path.of
        val asked = runCatching { Paths.get(raw) }.getOrNull() ?: return null
        val full = (if (asked.isAbsolute) asked else root.resolve(asked)).toAbsolutePath().normalize()
        if (full.toString().startsWith("\\\\")) return null
        // 盘符后面再出现冒号 = 备用数据流；每一级都不能是设备名
        if (full.toString().drop(2).contains(':')) return null
        if (full.any { it.toString().substringBefore('.').uppercase() in DEVICE_NAMES }) return null
        var existing: Path = full
        val tail = ArrayDeque<String>()
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            tail.addFirst(existing.fileName?.toString() ?: return null)
            existing = existing.parent ?: return null
        }
        val real = runCatching { existing.toRealPath() }.getOrNull() ?: return null
        return tail.fold(real) { acc, name -> acc.resolve(name) }
    }

    companion object {
        private val DEVICE_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    }
}
