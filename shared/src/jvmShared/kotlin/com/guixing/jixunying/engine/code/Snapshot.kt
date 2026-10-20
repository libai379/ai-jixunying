package com.guixing.jixunying.engine.code

import java.io.File

/**
 * 工作区快照（2026-10-20，接 Codex / Gemini 的方案甲，docs/Code栏设计说明.md 第 17 节，安全核心）。
 * 它们改文件之前不会通知我们，没法像自己的工具、Claude Code 那样「改前备份」；所以交出去之前把工作区里的文件原样记一份，
 * 跑完再扫一遍比对：改了的、新建的、删掉的都列出来，原来的内容交给 CodeBackup，照样能看差异、拒绝、撤销。
 *
 * 跳过 .git、node_modules、build 这类目录（SKIP_DIRS）；单个文件超过 MAX_FILE 的只记大小和修改时间——改了能发现，但留不了原样；
 * 文件数、总字节到上限（MAX_FILES、MAX_TOTAL）就不再往下扫，complete = false，后面没扫到的改动发现不了（界面上照实说）。
 */
class Snapshot private constructor(
    private val entries: Map<String, Entry>,
    /** 扫完了全部（没碰到上限）。 */
    val complete: Boolean,
) {
    /** bytes = null：文件太大，没存原样。 */
    private class Entry(val length: Long, val modified: Long, val bytes: ByteArray?)

    /** 一处改动。before = 改之前的内容（null = 原来没有这个文件，或者太大没存）；after 为空表示被删掉了。 */
    class Change(val file: File, val before: ByteArray?, val existed: Boolean, val deleted: Boolean) {
        /** 原来就有、但太大没存原样：撤销不了。 */
        val tooBig: Boolean get() = existed && before == null
    }

    val size: Int get() = entries.size

    /** 跑完以后再扫一遍，和这份比：改了的、新建的、删掉的。 */
    fun changes(roots: List<File>): List<Change> {
        val now = scan(roots, keepBytes = false)
        val out = mutableListOf<Change>()
        for ((path, old) in entries) {
            val cur = now.entries[path]
            val f = File(path)
            if (cur == null) {
                // 扫的时候不在了：真删了，或者后来那次扫描撞了上限没扫到它（那就再看一眼）
                if (!f.isFile) out += Change(f, old.bytes, existed = true, deleted = true)
                continue
            }
            if (cur.length == old.length && cur.modified == old.modified) continue
            val same = old.bytes != null && runCatching { f.readBytes().contentEquals(old.bytes) }.getOrDefault(false)
            if (!same) out += Change(f, old.bytes, existed = true, deleted = false)
        }
        // 这份没扫完时，后来扫到的「新文件」可能只是当时没扫到的老文件：不报新建（宁可漏报，不把老文件当新建——拒绝新建会删文件）
        if (complete) for (path in now.entries.keys) if (path !in entries) out += Change(File(path), null, existed = false, deleted = false)
        return out.sortedBy { it.file.path.lowercase() }
    }

    companion object {
        val SKIP_DIRS = setOf(".git", ".hg", ".svn", "node_modules", "build", ".gradle", ".idea", "out", "target", "dist",
            "__pycache__", ".venv", "venv", ".next", ".kotlin", ".cache")
        const val MAX_FILE = 1L * 1024 * 1024
        const val MAX_FILES = 20_000
        const val MAX_TOTAL = 64L * 1024 * 1024

        /** 扫工作区（项目文件夹 + 加进来的文件夹）。读不了的文件当没有。 */
        fun take(roots: List<File>): Snapshot = scan(roots, keepBytes = true)

        private fun scan(roots: List<File>, keepBytes: Boolean): Snapshot {
            val map = LinkedHashMap<String, Entry>()
            var total = 0L
            var complete = true
            val seen = HashSet<String>()
            fun walk(dir: File) {
                val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
                for (f in kids) {
                    if (!complete) return
                    if (f.isDirectory) {
                        if (f.name.lowercase() in SKIP_DIRS || java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                        walk(f)
                        continue
                    }
                    if (!f.isFile) continue
                    val key = f.absolutePath
                    if (!seen.add(key.lowercase())) continue
                    if (map.size >= MAX_FILES) { complete = false; return }
                    val len = f.length()
                    val bytes = if (keepBytes && len <= MAX_FILE) {
                        if (total + len > MAX_TOTAL) { complete = false; return }
                        runCatching { f.readBytes() }.getOrNull() ?: continue
                    } else null
                    total += bytes?.size ?: 0
                    map[key] = Entry(len, f.lastModified(), bytes)
                }
            }
            roots.filter { it.isDirectory }.forEach { walk(it.absoluteFile) }
            return Snapshot(map, complete)
        }
    }
}
