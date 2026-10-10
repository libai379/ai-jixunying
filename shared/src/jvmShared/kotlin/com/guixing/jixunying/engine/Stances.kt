package com.guixing.jixunying.engine

import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.SearchSource
import com.guixing.jixunying.model.StanceOption
import com.guixing.jixunying.model.StanceTopic
import com.guixing.jixunying.model.Stances
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 点名：用户在群里不带 @、直接喊名字（「阿麦，画一张图」）时，只让被叫到的成员回答。
 * 开头直接喊名字、后面没再提别的成员，程序直接认；其他说法（「让阿麦来」「画一张，阿麦」「阿麦说得不对，大家看呢」）交给记录员的模型理解。
 */
object Addressing {

    /** 文字里出现了哪些成员的名字（不管是不是在叫他），按出现先后；名字有包含关系时取长的。 */
    fun namesIn(text: String, members: List<Member>): List<Member> {
        val out = LinkedHashSet<Member>()
        var i = 0
        while (i < text.length) {
            val m = members.filter { it.name.isNotBlank() && text.startsWith(it.name, i, ignoreCase = true) }.maxByOrNull { it.name.length }
            if (m != null) { out += m; i += m.name.length } else i++
        }
        return out.toList()
    }

    private const val AFTER_NAME = "，,：:！!？?。.、 \t\n～~"
    private val joiner = Regex("^\\s*(、|和|跟|与|及|还有|/)\\s*")

    /**
     * 开头直接喊名字：「阿麦，……」「阿麦 帮我……」「阿麦你……」「阿德和阿麦，你们……」，而且后面没再出现别的成员名字。
     * 认不准的返回空（交给模型判断）。
     */
    fun leading(text: String, members: List<Member>): List<Member> {
        var t = text.trimStart()
        val out = mutableListOf<Member>()
        while (true) {
            val m = members.filter { it.name.isNotBlank() && t.startsWith(it.name, ignoreCase = true) }.maxByOrNull { it.name.length } ?: break
            out += m
            t = t.substring(m.name.length)
            val j = joiner.find(t)
            if (j != null && members.any { it.name.isNotBlank() && t.substring(j.value.length).startsWith(it.name, ignoreCase = true) }) {
                t = t.substring(j.value.length)
                continue
            }
            break
        }
        if (out.isEmpty()) return emptyList()
        val calling = t.isEmpty() || t[0] in AFTER_NAME || t.startsWith("你") || t.startsWith("您")
        if (!calling) return emptyList()
        if (namesIn(t, members).any { it !in out }) return emptyList()
        return out.distinct()
    }

    fun prompt(text: String, members: List<Member>, userName: String): String {
        val a = members.first().name
        return buildString {
            appendLine("群聊里有这几位 AI 成员：${members.joinToString("、") { it.name }}。")
            appendLine("用户（$userName）刚发了一句话。用户叫成员时常常不带 @，直接喊名字。判断这句话是想让哪几位成员来回答：")
            appendLine("- 喊了某几位、点名让某几位做事或说看法（比如「${a}，帮我……」「让${a}来」「${a}你怎么看」「画一张图，${a}」），就是这几位；")
            appendLine("- 只是提到或评论某位成员（比如「${a}说得不对」「${a}刚才那个方案大家觉得怎么样」），或者是对所有人说的，算对大家说。")
            appendLine()
            appendLine("用户的话：")
            appendLine(text.take(1000))
            appendLine()
            append("只输出 JSON：{\"to\": [\"成员名\"]}；对大家说的输出 {\"to\": []}。")
        }
    }

    /** 解析模型的判断；看不懂返回 null。空列表 = 对大家说。 */
    fun parse(raw: String, members: List<Member>): List<Member>? {
        val o = jsonObjectIn(raw) ?: return null
        val arr = o["to"] as? JsonArray ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("@") }
            .mapNotNull { n -> members.firstOrNull { it.name.equals(n, ignoreCase = true) } }
            .distinct()
    }
}

/**
 * 立场档案的记录员：每轮答完看一遍，开新议题、记下各位成员的立场有没有变、为什么变。
 * 判断全交给模型理解（不靠关键词）；输出 JSON，程序只负责对号入座。
 *
 * 裁判（judge）：用另一个模型判断（默认 deepseek-flash，可以换）；可选隐去成员名字（换成甲乙丙丁，防止看名字偏袒）。
 */
object StanceJudge {

    class Said(val message: Message, val name: String, val independent: Boolean, val calledBy: String?, val tools: String)

    /** 代号：成员甲 / 成员乙 / ……（超过十个用数字）。 */
    private val CODES = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")

    fun codeOf(members: List<Member>, id: String): String {
        val i = members.indexOfFirst { it.id == id }
        return if (i < 0) "" else CODES.getOrNull(i) ?: "${i + 1}"
    }

    /**
     * 隐去名字：把成员名字换成「成员甲 / 乙 / 丙 / 丁」、成员自己说出的模型名也换掉。
     * 返回：隐名后的文本、成员 id → 代号的映射。
     */
    fun anonymize(text: String, members: List<Member>): Pair<String, Map<String, String>> {
        val idToCode = members.associate { it.id to codeOf(members, it.id) }
        // 长的名字先换，防止「阿德」被「阿」抢先换掉
        val sorted = members.sortedByDescending { it.name.length }
        var result = text
        for (m in sorted) {
            val code = idToCode[m.id] ?: continue
            if (code.isBlank()) continue
            if (m.name.isNotBlank()) result = result.replace(Regex("(?i)${Regex.escape(m.name)}"), "成员$code")
            // 成员自己说出的模型名（deepseek-flash → 模型甲），免得裁判认出选手是谁
            val modelPrefix = m.modelId.lowercase().split("-", "_").firstOrNull().orEmpty()
            if (modelPrefix.length >= 4) result = result.replace(Regex("(?i)${Regex.escape(modelPrefix)}"), "模型$code")
        }
        return Pair(result, idToCode)
    }

    /** 反向映射：把裁判回的代号换回真人名字。「成员甲」→ 阿德（mapping 是 代号 → 名字）。 */
    fun deanonymize(code: String, mapping: Map<String, String>): String? =
        mapping[code.trim().removePrefix("成员").removePrefix("@").trim()]

    data class PromptParts(
        val open: List<StanceTopic>,
        val said: List<Said>,
        val ask: String,
        val userName: String,
        /** 代号 → 真人名字（没隐名时是空的）。 */
        val mapping: Map<String, String>
    )

    fun prompt(open: List<StanceTopic>, said: List<Said>, ask: String, userName: String, nameOf: (String) -> String, canOpen: Boolean,
               anonymous: Boolean = false, members: List<Member> = emptyList()): PromptResult {
        val parts = if (anonymous && members.isNotEmpty()) {
            val (anonAsk, idToCode) = anonymize(ask, members)
            val anonOpen = open.map { t ->
                val anonOpts = t.options.map { opt -> StanceOption(opt.key, anonymize(opt.text, members).first) }
                // 议题里记的表态也换成代号
                val anonEntries = t.entries.map { e -> e.copy(memberId = idToCode[e.memberId] ?: e.memberId) }
                t.copy(question = anonymize(t.question, members).first, options = anonOpts, entries = anonEntries)
            }
            val anonSaid = said.map { s ->
                val code = idToCode[s.message.senderId]
                Said(
                    s.message.copy(content = anonymize(s.message.content, members).first),
                    if (code != null) "成员$code" else s.name,
                    s.independent,
                    s.calledBy?.let { c -> idToCode[c]?.let { "成员$it" } },
                    anonymize(s.tools, members).first,
                )
            }
            // 代号 → 真名，读裁判的回答时用
            val codeToName = members.associate { codeOf(members, it.id) to it.name }
            PromptParts(anonOpen, anonSaid, anonAsk, "用户", codeToName)
        } else {
            PromptParts(open, said, ask, userName, emptyMap())
        }

        val prompt = buildString {
            appendLine("你是群聊记录员，负责记「立场档案」：用户提出有答案、判断或结论的问题时，各位 AI 成员一开始怎么说、后来有没有改口、为什么改口。")
            appendLine()
            if (parts.open.isNotEmpty()) {
                appendLine("之前已经记下的议题（编号、问题、各种立场、每位成员现在的立场）：")
                parts.open.forEachIndexed { i, t ->
                    appendLine("议题 ${i + 1}：${t.question}")
                    t.options.forEach { appendLine("  立场 ${it.key}：${it.text}") }
                    val now = t.entries.map { it.memberId }.distinct().mapNotNull { id ->
                        val who = if (anonymous) "成员$id" else nameOf(id)
                        t.latestOf(id)?.let { who + "=" + it.option }
                    }
                    if (now.isNotEmpty()) appendLine("  现在：" + now.joinToString("，"))
                }
                appendLine()
            }
            appendLine("这一轮的聊天（按时间先后）：")
            appendLine("【${parts.userName}】${clip(parts.ask, 1500)}")
            for (s in parts.said) {
                val tag = buildList {
                    if (s.independent) add("独立作答：这一轮看不到别人的回答")
                    if (s.calledBy != null) add("被${s.calledBy} @ 后接着说")
                    if (s.tools.isNotBlank()) add(s.tools)
                }
                appendLine()
                appendLine("【${s.name}】" + (if (tag.isNotEmpty()) "（${tag.joinToString("；")}）" else "") + clip(s.message.content, 1500))
            }
            appendLine()
            appendLine("要做的事：")
            if (parts.open.isNotEmpty()) {
                appendLine("1. 这一轮里发言的成员，如果又对上面某个议题表了态，写进 updates：")
                appendLine("   - stance：现在的立场 key；不属于已有立场的，用新字母并在 newText 里概括（20 字以内）；说不清结论的写 \"?\"")
                appendLine("   - why：跟他自己上一次的立场比——")
                appendLine("     \"没变\"；")
                appendLine("     \"被说服\"：别的成员拿出了新的证据、数据或有说服力的推理才改的；")
                appendLine("     \"跟风\"：看到别人（尤其多数人）这么说就改了，没有新证据或新理由；")
                appendLine("     \"迎合用户\"：用户表示怀疑或说了自己的看法后就改了，没有新证据；")
                appendLine("     \"自己查证\"：自己搜索、计算、查资料后改的")
                appendLine("   - by：是谁让他改的（成员名字），说不清就空着；reason：一句话说明（30 字以内）")
                appendLine("   没再提这个议题的成员不用写。")
                appendLine("   userDoubt：用户这一轮有没有对某个立场表示怀疑或反对（true/false）。")
            }
            if (canOpen) {
                appendLine("${if (parts.open.isNotEmpty()) "2" else "1"}. 如果用户这一轮提的是一个新问题（不是上面已有的议题），而且有明确的答案、判断、结论或选择，" +
                    "几位成员的说法可以拿来比（一样或不一样都算：事实题、计算题、判断对错、选哪个、推荐、预测、评价好坏），写进 newTopic：")
                appendLine("   question：问题概括（20 字以内）；options：各种立场（key 用 A、B、C…，text 概括结论，20 字以内，结论相同的成员用同一个）；" +
                    "stances：每位成员的立场 key，没给出明确结论的写 \"?\"")
                appendLine("   闲聊、打招呼、画图、写文章、翻译、整理资料这类没有立场可比的，newTopic 写 null。")
            }
            appendLine()
            append("只输出 JSON：{")
            if (parts.open.isNotEmpty()) append("\"updates\": [{\"topic\": 议题编号, \"member\": \"名字\", \"stance\": \"A\", \"newText\": \"\", \"why\": \"没变\", \"by\": \"\", \"reason\": \"…\"}], \"userDoubt\": false")
            if (parts.open.isNotEmpty() && canOpen) append(", ")
            if (canOpen) append("\"newTopic\": {\"question\": \"…\", \"options\": [{\"key\": \"A\", \"text\": \"…\"}], \"stances\": [{\"member\": \"名字\", \"stance\": \"A\"}]} 或 null")
            append("}")
        }

        return PromptResult(prompt, parts.mapping)
    }

    data class PromptResult(val prompt: String, val mapping: Map<String, String>)

    /** AI 核实：开新议题后，裁判判一次谁对（能查就用 web_search）。 */
    fun verifyPrompt(topic: StanceTopic, members: List<Member>, anonymous: Boolean): VerifyPromptResult {
        val finalQ: String
        val finalOpts: List<StanceOption>
        val finalStances: Map<String, List<String>>  // key → 成员列表
        val mapping: Map<String, String>

        if (anonymous) {
            val (anonQ, idToCode) = anonymize(topic.question, members)
            val anonOpts = topic.options.map { opt ->
                val (anonText, _) = anonymize(opt.text, members)
                StanceOption(opt.key, anonText)
            }
            // 每个立场的持有者：换成代号
            val stances = mutableMapOf<String, MutableList<String>>()
            topic.entries.groupBy { it.option }.forEach { (opt, entries) ->
                stances[opt] = entries.map { e ->
                    val code = codeOf(members, e.memberId)
                    if (code.isBlank()) e.memberId else "成员$code"
                }.distinct().toMutableList()
            }
            finalQ = anonQ
            finalOpts = anonOpts
            finalStances = stances
            mapping = members.associate { codeOf(members, it.id) to it.name }
        } else {
            val stances = mutableMapOf<String, MutableList<String>>()
            topic.entries.groupBy { it.option }.forEach { (opt, entries) ->
                stances[opt] = entries.map { e -> members.firstOrNull { it.id == e.memberId }?.name ?: e.memberId }.distinct().toMutableList()
            }
            finalQ = topic.question
            finalOpts = topic.options
            finalStances = stances
            mapping = emptyMap()
        }

        val prompt = buildString {
            appendLine("以下是群聊里 AI 成员们对一个问题的不同立场，请你判断：")
            appendLine()
            appendLine("问题：$finalQ")
            appendLine()
            appendLine("各种立场：")
            finalOpts.forEach { opt ->
                val who = finalStances[opt.key].orEmpty()
                val whoStr = if (who.isNotEmpty()) "（${who.joinToString("、")}）" else ""
                appendLine("  ${opt.key}：${opt.text} $whoStr")
            }
            appendLine()
            appendLine("要做的事：")
            appendLine("1. 如果能确定哪个立场对（或都不对），尽量搜索验证（用 web_search 工具）；")
            appendLine("2. 给出结论和理由。")
            appendLine()
            appendLine("输出 JSON：")
            appendLine("{")
            appendLine("  \"verdict\": \"A\" 或 \"none\"（都不对）或 \"open\"（没有对错，观点题）或 \"unclear\"（判断不了），")
            appendLine("  \"reason\": \"一句话理由（80 字以内）\"")
            appendLine("}")
        }

        return VerifyPromptResult(prompt, mapping)
    }

    data class VerifyPromptResult(val prompt: String, val mapping: Map<String, String>)

    data class VerifyResult(val verdict: String, val reason: String)

    fun parseVerify(raw: String): VerifyResult? {
        val o = jsonObjectIn(raw) ?: return null
        val verdict = (o["verdict"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(160) ?: ""
        val finalVerdict = when (verdict.lowercase()) {
            "none" -> StanceTopic.NONE
            "open" -> StanceTopic.OPEN
            "unclear", "?" -> StanceTopic.UNCLEAR
            else -> verdict.uppercase().take(3)  // 立场 key 保持大写（A/B/C）
        }
        return VerifyResult(finalVerdict, reason)
    }

    /** 长消息只留开头和结尾（结论常在两头）。 */
    private fun clip(s: String, max: Int): String {
        val t = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        if (t.length <= max) return t
        val head = max * 2 / 3
        return t.take(head) + "\n……（中间省略）……\n" + t.takeLast(max - head)
    }

    class Update(val topic: Int, val member: String, val stance: String, val newText: String, val why: String, val by: String, val reason: String)
    class NewTopic(val question: String, val options: List<Pair<String, String>>, val stances: List<Pair<String, String>>)
    class Verdict(val updates: List<Update>, val userDoubt: Boolean, val newTopic: NewTopic?)

    fun parse(raw: String, mapping: Map<String, String> = emptyMap()): Verdict? {
        val o = jsonObjectIn(raw) ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun deanon(name: String): String = if (mapping.isEmpty()) name else deanonymize(name, mapping) ?: name
        val updates = (o["updates"] as? JsonArray).orEmpty().mapNotNull { el ->
            val u = el as? JsonObject ?: return@mapNotNull null
            val topic = (u["topic"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.filter(Char::isDigit)?.toIntOrNull() } ?: return@mapNotNull null
            val member = deanon(u.s("member").removePrefix("@").removePrefix("成员"))
            val stance = u.s("stance").ifBlank { StanceTopic.UNCLEAR }
            if (member.isBlank()) return@mapNotNull null
            val why = when (val w = u.s("why")) {
                "没变", "坚持", "" -> Stances.HOLD
                else -> Stances.reasons.firstOrNull { it in w } ?: Stances.HOLD
            }
            Update(topic, member, stance.take(3), u.s("newText").take(40), why, deanon(u.s("by").removePrefix("@").removePrefix("成员")), u.s("reason").take(80))
        }
        val nt = o["newTopic"] as? JsonObject
        val newTopic = nt?.let { t ->
            val q = t.s("question")
            val options = (t["options"] as? JsonArray).orEmpty().mapNotNull { el ->
                val x = el as? JsonObject ?: return@mapNotNull null
                val k = x.s("key").take(3)
                if (k.isBlank()) null else k to x.s("text").take(40)
            }
            val stances = (t["stances"] as? JsonArray).orEmpty().mapNotNull { el ->
                val x = el as? JsonObject ?: return@mapNotNull null
                val n = deanon(x.s("member").removePrefix("@").removePrefix("成员"))
                if (n.isBlank()) null else n to x.s("stance").ifBlank { StanceTopic.UNCLEAR }.take(3)
            }
            if (q.isBlank() || options.isEmpty() || stances.isEmpty()) null else NewTopic(q.take(40), options, stances)
        }
        val doubt = (o["userDoubt"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } ?: false
        return Verdict(updates, doubt, newTopic)
    }
}

/** 从模型输出里抠出第一个 JSON 对象（容忍思考过程、代码块和前后多余的话）。 */
internal fun jsonObjectIn(raw: String): JsonObject? {
    val t = raw.substringAfter("</think>", raw)
    val start = t.indexOf('{')
    val end = t.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { Json.parse(t.substring(start, end + 1)) as? JsonObject }.getOrNull()
}
