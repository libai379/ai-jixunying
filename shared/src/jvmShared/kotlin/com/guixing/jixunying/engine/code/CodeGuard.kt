package com.guixing.jixunying.engine.code

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Code 栏的路径限制（docs/Code栏设计说明.md 第 4 节第 1 条，安全核心，写代码的 AI 只调用不改）：
 * 模型给的路径（相对项目文件夹或绝对路径）先规范化，再确认落在项目文件夹里，越界一律 null。
 * - 「..」、绝对路径、另一个盘、\\服务器\共享：规范化后不在项目文件夹里 → 拒绝
 * - 符号链接 / 目录联接指到外面：按真实路径（toRealPath）判断 → 拒绝
 * - Windows 短文件名（PROGRA~1）、大小写：toRealPath 会换成真名再比
 * - 备用数据流（a.txt:stream）、设备名（CON、NUL、COM1…）、空字符：直接拒绝
 * 还不存在的文件（要新建的）：找到最近一级存在的上级目录取真实路径，再接上后面几级。
 */
class CodeGuard(root: File) {
    val root: Path = root.toPath().toAbsolutePath().normalize().toRealPath()

    /** 项目文件夹里的文件；越界或不合法返回 null。 */
    fun resolve(path: String): File? {
        val raw = path.trim().removeSurrounding("\"").trim()
        if (raw.isEmpty() || raw.any { it == '\u0000' }) return null
        // Paths.get 而不是 Path.of：这层代码手机上也编译，安卓 8.0 没有 Path.of
        val asked = runCatching { Paths.get(raw) }.getOrNull() ?: return null
        val full = (if (asked.isAbsolute) asked else root.resolve(asked)).toAbsolutePath().normalize()
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
        val target = tail.fold(real) { acc, name -> acc.resolve(name) }
        return if (target.startsWith(root)) target.toFile() else null
    }

    /** 给人和模型看的相对路径（用 /）。 */
    fun relative(file: File): String = root.relativize(file.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/')

    companion object {
        private val DEVICE_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
    }
}
