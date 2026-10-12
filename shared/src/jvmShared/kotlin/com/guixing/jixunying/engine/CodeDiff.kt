package com.guixing.jixunying.engine

import com.guixing.jixunying.engine.code.CodeRunnerRules
import kotlin.math.max
import kotlin.math.min

/**
 * Code 栏的差异（docs/Code栏设计说明.md 第 1、11 节，2026-10-12）：按行比较两段文字，
 * 产出统一差异（unified diff：--- / +++ 头、@@ 段头、上下文 3 行）和增删行数。自己写的，不加依赖库。
 *
 * 两件事分开算：
 * - 增删行数用 Myers 差分的「最短编辑步数」算，跟给不给差异文本无关，大文件里的小改动也是准的；
 * - 差异文本用最长公共子序列排（先掐掉相同的开头结尾，再排中间），改动大到排不动就不排了，
 *   只给行数和一句说明（第 11 节：文件太大时可以只给增删行数和一句说明）。
 */
object CodeDiff {
    /** 一边超过这么多行就不给差异文本了，只给行数。 */
    const val MAX_LINES = 5000
    /** 差异里每条改动前后各留几行上下文。 */
    const val CONTEXT = 3
    /** 排差异用的表格最多多少格（几千行整段重写会占太多内存，超了就不排）。 */
    private const val CELL_CAP = 3_000_000
    /** Myers 最多找多少步，超了行数按整段替换估。 */
    private const val STEP_CAP = 2000
    /** 差异文本最长多少字（一本正经的长文件也能看）。 */
    private const val TEXT_CAP = 60_000

    /**
     * @param text 能给差异就是差异文本；太大了就是一句说明（「只给增删行数」那句）。
     * @param added 加了几行，@param removed 删了几行（0 和 0 = 一模一样）。
     */
    class Result(val text: String, val added: Int, val removed: Int) {
        val same: Boolean get() = added == 0 && removed == 0
    }

    /** path 是相对项目文件夹的路径，只写进 --- / +++ 头里给人看。 */
    fun make(path: String, old: String, new: String): Result {
        val a = linesOf(old)
        val b = linesOf(new)
        val (removed, added) = counts(a, b)
        if (removed == 0 && added == 0) return Result("", 0, 0)
        if (a.size > MAX_LINES || b.size > MAX_LINES) {
            return Result("这个文件太大（原来 ${a.size} 行，现在 ${b.size} 行），这里只给增删行数：+$added −$removed", added, removed)
        }
        val ops = opsOf(a, b)
            ?: return Result("这段改动太大（原来 ${a.size} 行，现在 ${b.size} 行），这里只给增删行数：+$added −$removed", added, removed)
        return Result(render(path, a, b, ops), added, removed)
    }

    /** 只要增删行数（改完文件记 FileChange 用）：(删了几行, 加了几行)。 */
    fun counts(old: String, new: String): Pair<Int, Int> = counts(linesOf(old), linesOf(new))

    private fun linesOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val raw = text.split('\n')
        // 末尾换行不算多一行空的
        val cut = if (raw.isNotEmpty() && raw.last().isEmpty()) raw.dropLast(1) else raw
        // 换行的 \r 不算进内容，免得整行看起来都变了（Windows 上改过的文件）
        return cut.map { it.removeSuffix("\r") }
    }

    // ———————————————— 增删行数 ————————————————

    /** (删了几行, 加了几行)。Myers 的最短编辑步数 D 配上两边行数差就能倒推出来：加 − 删 = 现在行数 − 原来行数。 */
    private fun counts(a: List<String>, b: List<String>): Pair<Int, Int> {
        var lo = 0
        while (lo < a.size && lo < b.size && a[lo] == b[lo]) lo++
        var ea = a.size
        var eb = b.size
        while (ea > lo && eb > lo && a[ea - 1] == b[eb - 1]) { ea--; eb-- }
        val n = ea - lo
        val m = eb - lo
        if (n == 0 && m == 0) return 0 to 0
        if (n == 0) return 0 to m
        if (m == 0) return n to 0
        val steps = editSteps(a.subList(lo, ea), b.subList(lo, eb)) ?: return n to m
        return (steps - (m - n)) / 2 to (steps + (m - n)) / 2
    }

    /** 最短编辑步数（加上几次、删掉几次，合起来）；找太多步就不找了，返回 null。 */
    private fun editSteps(a: List<String>, b: List<String>): Int? {
        val n = a.size
        val m = b.size
        val max = n + m
        val v = IntArray(2 * max + 2)
        for (d in 0..max) {
            if (d > STEP_CAP) return null
            var k = -d
            while (k <= d) {
                val i = k + max + 1
                var x = if (k == -d || (k != d && v[i - 1] < v[i + 1])) v[i + 1] else v[i - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[i] = x
                if (x >= n && y >= m) return d
                k += 2
            }
        }
        return null
    }

    // ———————————————— 逐行对照 ————————————————

    /** 整份文件的逐行对照：' ' 没变 / '-' 删掉 / '+' 加上。排不动（太大）返回 null。 */
    private fun opsOf(a: List<String>, b: List<String>): List<Char>? {
        var lo = 0
        while (lo < a.size && lo < b.size && a[lo] == b[lo]) lo++
        var ea = a.size
        var eb = b.size
        while (ea > lo && eb > lo && a[ea - 1] == b[eb - 1]) { ea--; eb-- }
        val n = ea - lo
        val m = eb - lo
        val ops = ArrayList<Char>(a.size + b.size)
        repeat(lo) { ops.add(' ') }
        if (n > 0 || m > 0) {
            if ((n + 1).toLong() * (m + 1) > CELL_CAP) return null
            ops.addAll(lcsOps(a.subList(lo, ea), b.subList(lo, eb)) ?: return null)
        }
        repeat(a.size - ea) { ops.add(' ') }
        return ops
    }

    /** 最长公共子序列：中间那段怎么改最省。 */
    private fun lcsOps(a: List<String>, b: List<String>): List<Char>? {
        val n = a.size
        val m = b.size
        val w = m + 1
        val dp = ShortArray((n + 1) * w)
        fun at(i: Int, j: Int) = dp[i * w + j].toInt()
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i * w + j] = (if (a[i] == b[j]) at(i + 1, j + 1) + 1 else max(at(i + 1, j), at(i, j + 1))).toShort()
            }
        }
        val ops = ArrayList<Char>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops.add(' '); i++; j++ }
                at(i + 1, j) >= at(i, j + 1) -> { ops.add('-'); i++ }
                else -> { ops.add('+'); j++ }
            }
        }
        while (i < n) { ops.add('-'); i++ }
        while (j < m) { ops.add('+'); j++ }
        return ops
    }

    // ———————————————— 排版 ————————————————

    private fun render(path: String, a: List<String>, b: List<String>, ops: List<Char>): String {
        // 逐行配上文字，再按改动的位置分段（@@ 段头）
        val tagged = ArrayList<Pair<Char, String>>(ops.size)
        var oi = 0
        var ni = 0
        for (op in ops) {
            when (op) {
                ' ' -> { tagged.add(' ' to a[oi]); oi++; ni++ }
                '-' -> { tagged.add('-' to a[oi]); oi++ }
                else -> { tagged.add('+' to b[ni]); ni++ }
            }
        }
        val oldBefore = IntArray(tagged.size + 1)
        val newBefore = IntArray(tagged.size + 1)
        for (t in tagged.indices) {
            oldBefore[t + 1] = oldBefore[t] + if (tagged[t].first == '+') 0 else 1
            newBefore[t + 1] = newBefore[t] + if (tagged[t].first == '-') 0 else 1
        }
        val changed = tagged.indices.filter { tagged[it].first != ' ' }
        if (changed.isEmpty()) return ""
        val sb = StringBuilder()
        val name = path.trim().ifBlank { "文件" }
        sb.append("--- ").append(if (path.isBlank()) name else "a/$name").append('\n')
        sb.append("+++ ").append(if (path.isBlank()) name else "b/$name").append('\n')
        var i = 0
        while (i < changed.size) {
            val start = max(0, changed[i] - CONTEXT)
            var j = i
            // 两处改动挨得近（中间只隔几行）就并在一段里
            while (j + 1 < changed.size && changed[j + 1] - changed[j] <= 2 * CONTEXT) j++
            val end = min(tagged.size - 1, changed[j] + CONTEXT)
            val oldCount = oldBefore[end + 1] - oldBefore[start]
            val newCount = newBefore[end + 1] - newBefore[start]
            // 行号从 1 数；这一段一行没删/没加时，起点按 Git 的写法写成前一行
            val oldStart = if (oldCount > 0) oldBefore[start] + 1 else oldBefore[start]
            val newStart = if (newCount > 0) newBefore[start] + 1 else newBefore[start]
            sb.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
            for (t in start..end) sb.append(tagged[t].first).append(tagged[t].second).append('\n')
            i = j + 1
        }
        // 特长的差异也截一下（截了会在文本里写明省略了多少）
        return CodeRunnerRules.clip(sb.toString(), TEXT_CAP).first
    }
}
