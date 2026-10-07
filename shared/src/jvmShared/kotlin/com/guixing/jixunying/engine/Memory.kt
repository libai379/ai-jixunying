package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 一个对话的「记录员笔记」：前面压缩掉的聊天的摘要，存在 convs\<id>.memo.json，不放进 AppState（太大，而且要推给手机）。
 * 用时间分界而不是消息编号：压缩过的消息被删了也不会乱。
 */
@Serializable
data class ConvMemo(
    val summary: String = "",
    /** 摘要覆盖到这个时间（含）为止的消息。 */
    val upToTime: Long = 0,
    val summarizedCount: Int = 0,
    /** 长期记忆扫到这个时间为止。 */
    val scannedUpToTime: Long = 0,
    val updatedAt: Long = 0,
)

/** 记录员：压缩聊天、挑出值得长期记住的要点、整理记忆。全群共用一个，不是每个成员各压一遍。 */
object Recorder {

    /** 自动挑记录员：便宜、指令遵循好的优先（规则在 model/RecorderPick.kt，界面也要用）。 */
    fun pick(state: AppState): Pair<ProviderConfig, String>? = com.guixing.jixunying.model.RecorderPick.pick(state)

    fun summarizePrompt(oldSummary: String, transcript: String, userName: String): String = buildString {
        appendLine("你是聊天记录员。把下面这段群聊记录压缩成摘要，之后大家会只看摘要而不看原文，所以要保留所有后面可能用到的信息。")
        appendLine()
        appendLine("要求：")
        appendLine("- 按这几节写（没有内容的节省略）：")
        appendLine("  ## 话题")
        appendLine("  ## ${userName}的要求和偏好（尽量照原话，只增不删）")
        appendLine("  ## 已经定下的结论")
        appendLine("  ## 分歧和各成员的立场")
        appendLine("  ## 重要的事实、数据、链接、文件名")
        appendLine("  ## 还没解决的问题和待办")
        appendLine("- 写清楚谁说了什么（用名字）。数字、名称、代码接口照抄，不要改写。")
        appendLine("- 不要编造，不要评价，不要加原文里没有的东西。")
        appendLine("- 尽量精炼，一般不超过 1500 字。直接输出摘要正文，不要开场白。")
        if (oldSummary.isNotBlank()) {
            appendLine()
            appendLine("这是更早的聊天已经压缩好的摘要，请把它和新的记录合并成一份新的摘要（旧摘要里的要求和结论除非被推翻，否则保留）：")
            appendLine("<旧摘要>")
            appendLine(oldSummary.trim())
            appendLine("</旧摘要>")
        }
        appendLine()
        appendLine("<新的聊天记录>")
        appendLine(transcript.trim())
        append("</新的聊天记录>")
    }

    fun extractPrompt(existing: List<MemoryItem>, transcript: String, userName: String): String = buildString {
        appendLine("你是记录员。下面是用户「$userName」和 AI 的一段聊天。找出值得长期记住的、关于用户本人的信息，以后的对话都会用到。")
        appendLine()
        appendLine("只记这些：用户的身份、职业、所在城市、长期关心的事、正在做的长期项目、对回答方式的要求和偏好、明确说「记住」的事。")
        appendLine("不要记：一次性的问题、闲聊内容、AI 说的话、密码 / Key / 身份证号 / 银行卡等敏感信息、只跟这次对话有关的细节。")
        appendLine("每条写成一句完整的话，以用户为主语（例如「在北京做产品经理」「希望回答先给结论」）。一次最多 3 条。")
        appendLine()
        if (existing.isNotEmpty()) {
            appendLine("已经记住的（不要重复；如果新信息推翻了某条，用 replaces 写它的编号）：")
            existing.forEachIndexed { i, m -> appendLine("${i + 1}. [${m.kind}] ${m.text}") }
            appendLine()
        }
        appendLine("输出 JSON 数组，不要别的文字：[{\"text\": \"…\", \"kind\": \"关于我|偏好|要求|事实\", \"replaces\": 编号或 null}]。没有值得记的就输出 []。")
        appendLine()
        appendLine("<聊天记录>")
        appendLine(transcript.trim())
        append("</聊天记录>")
    }

    fun tidyPrompt(items: List<MemoryItem>): String = buildString {
        appendLine("下面是关于一位用户的长期记忆，可能有重复、互相矛盾或过时的条目。请整理：合并重复的，矛盾的保留更新的（编号大的更新），删掉没用的。")
        appendLine("不要新增原来没有的信息，措辞尽量保留原样。")
        appendLine()
        items.forEachIndexed { i, m -> appendLine("${i + 1}. [${m.kind}] ${m.text}") }
        appendLine()
        append("输出 JSON 数组，不要别的文字：[{\"text\": \"…\", \"kind\": \"关于我|偏好|要求|事实\", \"from\": [原来的编号…]}]")
    }

    class Extracted(val text: String, val kind: String, val replaces: Int?, val from: List<Int>)

    /** 宽松地解析模型输出的 JSON 数组（容忍前后多余的文字、代码块）。 */
    fun parseItems(raw: String): List<Extracted>? {
        val t = raw.substringAfter("</think>", raw)
        val start = t.indexOf('[')
        val end = t.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val arr = runCatching { Json.parse(t.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val text = o.str("text")?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val kind = o.str("kind")?.trim()?.takeIf { it in kinds } ?: "关于我"
            val replaces = (o["replaces"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val from = (o["from"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
            Extracted(text.take(200), kind, replaces, from)
        }
    }

    val kinds = listOf("关于我", "偏好", "要求", "事实")

    /** 粗略判断两条记忆是不是一回事（字的二元组重合度）。 */
    fun similar(a: String, b: String): Boolean {
        fun grams(s: String): Set<String> {
            val t = s.filter { it.isLetterOrDigit() }.lowercase()
            return if (t.length < 2) setOf(t) else t.windowed(2).toSet()
        }
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return false
        val inter = ga.intersect(gb).size.toDouble()
        return inter / minOf(ga.size, gb.size) >= 0.8
    }

    /** 给成员的系统提示用：置顶的在前，然后按更新时间，总长有上限。 */
    fun forPrompt(items: List<MemoryItem>, limitChars: Int = 2500): List<MemoryItem> {
        val sorted = items.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        val out = mutableListOf<MemoryItem>()
        var used = 0
        for (m in sorted) {
            if (used + m.text.length > limitChars) break
            used += m.text.length + 10
            out += m
        }
        return out
    }

    /** 搜聊天记录：把问题拆成词，中文再拆成两字一组，按命中多少排序。 */
    fun terms(query: String): List<String> {
        val words = query.split(Regex("[\\s,，。.!！?？、;；:：\"'“”‘’()（）]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val out = LinkedHashSet<String>()
        for (w in words) {
            out += w
            val cjk = w.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            if (cjk >= 3 && w.length >= 3) w.windowed(2).forEach { out += it }
        }
        return out.toList()
    }

    fun score(text: String, terms: List<String>): Int {
        val t = text.lowercase()
        var s = 0
        for (term in terms) if (term in t) s += if (term.length >= 3) 3 else 1
        return s
    }

    fun snippet(text: String, terms: List<String>, width: Int = 120): String {
        val t = text.replace(Regex("\\s+"), " ")
        val lower = t.lowercase()
        val at = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - width / 3).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(t.length)
        return (if (start > 0) "…" else "") + t.substring(start, end) + (if (end < t.length) "…" else "")
    }
}
