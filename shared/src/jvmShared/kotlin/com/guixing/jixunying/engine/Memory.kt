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

    /**
     * 挑长期记忆（记忆二期，2026-10-26）：除了关于用户本人的，也记某个话题 / 项目的长期信息（只在这个话题的对话里带上）；
     * 每条抄一句原话当出处（程序核对原话真在记录里，对不上就不挂）；顺带认出这段聊天属于哪个话题。
     */
    fun extractPrompt(existing: List<MemoryItem>, transcript: String, userName: String, topics: List<String>, convTopic: String, topicFixed: Boolean): String = buildString {
        appendLine("你是记录员。下面是用户「$userName」和 AI 的一段聊天。找出值得长期记住的信息，以后的对话会用到。")
        appendLine()
        appendLine("两种记忆：")
        appendLine("- 关于用户本人的（topic 留空）：身份、职业、所在城市、长期关心的事、对回答方式的要求和偏好、明确说「记住」的事。所有对话都会带上。")
        appendLine("- 某个话题 / 项目的（topic 写话题名）：用户长期在做的项目、长期关心的话题里定下的事、背景、要求，例如「AI集训营 的成员不再限定四家」。只在聊这个话题时带上。")
        appendLine("不要记：一次性的问题、闲聊内容、AI 说的话、密码 / Key / 身份证号 / 银行卡等敏感信息、只跟这次对话有关的细节。")
        appendLine("每条写成一句完整的话（关于用户本人的以用户为主语，例如「在北京做产品经理」「希望回答先给结论」）。一次最多 3 条。")
        appendLine("每条附上 quote：聊天记录里用户说的、能作为依据的那句原话，一字不改地抄（抄不出就留空）。")
        appendLine()
        if (topics.isNotEmpty()) {
            appendLine("已有的话题（能归进去就用原名，别另起近义的名字）：${topics.joinToString("、")}")
            appendLine()
        }
        if (existing.isNotEmpty()) {
            appendLine("已经记住的（不要重复；如果新信息推翻了某条，用 replaces 写它的编号）：")
            existing.forEachIndexed { i, m -> appendLine("${i + 1}. [${m.kind}]" + (if (m.topic.isNotBlank()) "[话题：${m.topic}]" else "") + " ${m.text}") }
            appendLine()
        }
        if (topicFixed) appendLine("这段聊天的话题用户已经定了：${convTopic.ifBlank { "（不属于任何话题）" }}。conversationTopic 照抄。")
        else appendLine("conversationTopic：这段聊天主要在聊哪个长期话题 / 项目（用已有的名字或起一个简短的新名字）；只是零散的问答、不属于任何长期话题就留空。")
        appendLine()
        appendLine("输出 JSON，不要别的文字：{\"conversationTopic\": \"…\", \"items\": [{\"text\": \"…\", \"kind\": \"关于我|偏好|要求|事实\", \"topic\": \"\", \"quote\": \"…\", \"replaces\": 编号或 null}]}。没有值得记的 items 就写 []。")
        appendLine()
        appendLine("<聊天记录>")
        appendLine(transcript.trim())
        append("</聊天记录>")
    }

    fun tidyPrompt(items: List<MemoryItem>): String = buildString {
        appendLine("下面是关于一位用户的长期记忆，可能有重复、互相矛盾或过时的条目。请整理：合并重复的，矛盾的保留更新的（编号大的更新），删掉没用的。")
        appendLine("不要新增原来没有的信息，措辞尽量保留原样。")
        appendLine()
        appendLine("带「话题」的是只在聊那个话题时才用的记忆；只合并同一话题里的，话题名照抄（没有话题的 topic 留空）。")
        appendLine()
        items.forEachIndexed { i, m -> appendLine("${i + 1}. [${m.kind}]" + (if (m.topic.isNotBlank()) "[话题：${m.topic}]" else "") + " ${m.text}") }
        appendLine()
        append("输出 JSON 数组，不要别的文字：[{\"text\": \"…\", \"kind\": \"关于我|偏好|要求|事实\", \"topic\": \"\", \"from\": [原来的编号…]}]")
    }

    class Extracted(val text: String, val kind: String, val replaces: Int?, val from: List<Int>, val topic: String = "", val quote: String = "")

    /** 挑记忆的输出：这段聊天的话题（null = 没写）和条目。也认旧格式（只有数组）。 */
    class Extraction(val conversationTopic: String?, val items: List<Extracted>)

    fun parseExtraction(raw: String): Extraction? {
        val t = raw.substringAfter("</think>", raw)
        val o = t.indexOf('{')
        val a = t.indexOf('[')
        if (o >= 0 && (a < 0 || o < a)) {
            val end = t.lastIndexOf('}')
            val obj = if (end > o) runCatching { Json.parse(t.substring(o, end + 1)) as? JsonObject }.getOrNull() else null
            if (obj != null) {
                val items = (obj["items"] as? JsonArray)?.let { parseItems(it.toString()) } ?: emptyList()
                return Extraction(obj.str("conversationTopic")?.let(::cleanTopic), items)
            }
        }
        return parseItems(t)?.let { Extraction(null, it) }
    }

    /** 话题名：去掉引号书名号和首尾空白，最长 20 字。 */
    fun cleanTopic(raw: String): String = raw.trim().trim('「', '」', '《', '》', '"', '\'', '“', '”').trim().take(20)

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
            Extracted(text.take(200), kind, replaces, from, o.str("topic")?.let(::cleanTopic).orEmpty(), o.str("quote")?.trim().orEmpty().take(200))
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
    fun forPrompt(items: List<MemoryItem>, topic: String = "", limitChars: Int = 2500): List<MemoryItem> {
        val sorted = items.filter { it.topic.isBlank() || (topic.isNotBlank() && it.topic == topic) }.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        val out = mutableListOf<MemoryItem>()
        var used = 0
        for (m in sorted) {
            if (used + m.text.length > limitChars) break
            used += m.text.length + 10
            out += m
        }
        return out
    }

    /** 所有话题名，最近用过的在前。 */
    fun topics(items: List<MemoryItem>): List<String> =
        items.filter { it.topic.isNotBlank() }.groupBy { it.topic }.entries.sortedByDescending { e -> e.value.maxOf { it.updatedAt } }.map { it.key }

    /**
     * 原话核对：记录员抄的原话真在某条消息里才挂出处（去掉空白再比）；抄得不全的，按字的二元组重合最多的那条算，太低就不挂。
     * 返回（消息编号，原话）；对不上返回 null。
     */
    fun locateQuote(quote: String, messages: List<Pair<String, String>>): Pair<String, String>? {
        val q = quote.filterNot { it.isWhitespace() }
        if (q.length < 2) return null
        messages.lastOrNull { (_, text) -> q in text.filterNot { it.isWhitespace() } }?.let { return it.first to quote.trim() }
        fun grams(s: String) = s.filterNot { it.isWhitespace() }.lowercase().windowed(2).toSet()
        val gq = grams(q)
        if (gq.isEmpty()) return null
        val best = messages.maxByOrNull { (_, text) -> grams(text).intersect(gq).size } ?: return null
        val share = grams(best.second).intersect(gq).size.toDouble() / gq.size
        return if (share >= 0.8) best.first to quote.trim() else null
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
