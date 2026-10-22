package com.guixing.jixunying.engine

import com.guixing.jixunying.model.FileChange
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一张团队任务单上改了哪些文件（docs/Code栏设计说明.md 第 18 节第 9 条第 2 样，2026-10-22）。
 * 放在角色干活的协程上下文里：写文件、改文件、Claude Code 改完、Codex / Gemini 比对出来的改动都往这里记，
 * 角色交回结果时程序把摘要接在后面——主控 / 总指挥不用信角色自己说改了什么。
 * 几张单子同时跑时各记各的（上下文跟着协程走）；Codex / Gemini 是整个工作区比对，同时有别的角色在改时会把别人的也算进来（第 17 节第 7 条）。
 */
class RoleChanges : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RoleChanges>

    private class Entry(var added: Int, var removed: Int, val created: Boolean, var deleted: Boolean, var big: Boolean)

    private val files = LinkedHashMap<String, Entry>()

    @Synchronized fun add(change: FileChange, deleted: Boolean = false) {
        val e = files[change.path]
        if (e == null) files[change.path] = Entry(change.added, change.removed, change.created, deleted, false)
        else { e.added += change.added; e.removed += change.removed; e.deleted = deleted }
    }

    /** 超过快照上限的大文件：只知道改了，没有行数。 */
    @Synchronized fun addBig(path: String) {
        files.getOrPut(path) { Entry(0, 0, false, false, true) }.big = true
    }

    /** 接在角色交回的话后面的一段；没改文件也写明（审查、测试一般就是这样）。 */
    @Synchronized fun summary(): String {
        if (files.isEmpty()) return "\n\n【程序记的改动】这张单子没有改文件。"
        val added = files.values.sumOf { it.added }
        val removed = files.values.sumOf { it.removed }
        return buildString {
            append("\n\n【程序记的改动】改了 ").append(files.size).append(" 个文件，+").append(added).append(" −").append(removed).append("：")
            for ((path, e) in files) {
                append("\n- ").append(path)
                when {
                    e.big -> append("（大文件，没有行数）")
                    e.deleted -> append("（删掉了）")
                    e.created -> append("（新建）+").append(e.added)
                    else -> append(" +").append(e.added).append(" −").append(e.removed)
                }
            }
            append("\n用户在界面上能逐个看差异、拒绝；要看具体改了什么就读这些文件或者跑 git diff。")
        }
    }
}
