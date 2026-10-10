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

    /**
     * 隐去名字：给裁判看的文字里，成员名字换成「成员甲 / 乙 / 丙 / 丁」，成员回答里说出的模型名换成「模型甲」……
     * - 代号按传进来的成员顺序给（引擎传对话里的成员顺序），每一轮都一样；
     * - 一次换完，换进去的代号不会再被别的名字换一遍；
     * - 用户的话和议题只换成员名字；question 里本来就提到的模型名（问的就是它）回答里也不换，换了题目就变了。
     * 裁判写回来的话用 back 换回真名。
     */
    class Anon(members: List<Member>, question: String = "") {
        private val codeOfId = members.mapIndexed { i, m -> m.id to "成员" + (CODES.getOrNull(i) ?: "${i + 1}") }.toMap()
        private val nameWords = members.filter { it.name.isNotBlank() }.associate { it.name.lowercase() to codeOfId.getValue(it.id) }
        private val modelWords = members.mapNotNull { m ->
            val w = modelWord(m.modelId)?.takeUnless { question.contains(it, ignoreCase = true) } ?: return@mapNotNull null
            w to codeOfId.getValue(m.id).replace("成员", "模型")
        }.distinctBy { it.first }.toMap()
        private val backWords = members.associate { codeOfId.getValue(it.id) to it.name } + modelWords.entries.associate { (w, code) -> code to w }

        fun code(memberId: String): String? = codeOfId[memberId]
        fun codeOfName(name: String): String? = nameWords[name.trim().removePrefix("@").lowercase()]
        /** 只换成员名字（用户的话、议题、工具说明）。 */
        fun names(text: String) = swap(text, nameWords)
        /** 成员名字和模型名都换（成员的回答）。 */
        fun all(text: String) = swap(text, nameWords + modelWords)
        /** 裁判写的「成员甲」（也认「甲」）→ 真名；认不出返回 null。 */
        fun nameOf(code: String): String? {
            val c = code.trim().removePrefix("@").trim()
            return backWords[c]?.takeIf { c.startsWith("成员") } ?: backWords["成员$c"]
        }
        /** 裁判写的一段话：代号换回真名，模型代号换回模型名。 */
        fun back(text: String): String = codeRegex.replace(text) { backWords[it.value] ?: it.value }

        private fun swap(text: String, words: Map<String, String>): String {
            if (words.isEmpty() || text.isEmpty()) return text
            val re = Regex(words.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
            return re.replace(text) { words[it.value.lowercase()] ?: it.value }
        }

        private companion object {
            val codeRegex = Regex("(成员|模型)(" + CODES.joinToString("|") + "|\\d+)")
            /** deepseek-flash → deepseek，MiniMax-M3 → minimax；太短的（glm）容易误伤，不换。 */
            fun modelWord(modelId: String): String? =
                modelId.lowercase().substringAfterLast('/').split('-', '_', '.', ' ').firstOrNull()?.takeIf { it.length >= 4 && it.any(Char::isLetter) }
        }
    }

    data class PromptParts(
        val open: List<StanceTopic>,
        val said: List<Said>,
        val ask: String,
        val userName: String,
    )

    fun prompt(open: List<StanceTopic>, said: List<Said>, ask: String, userName: String, nameOf: (String) -> String, canOpen: Boolean,
               anonymous: Boolean = false, members: List<Member> = emptyList()): PromptResult {
        val anon = if (anonymous && members.isNotEmpty()) Anon(members, ask + "\n" + open.joinToString("\n") { it.question }) else null
        val parts = if (anon != null) {
            val anonOpen = open.map { t ->
                // 议题里记的表态也换成代号（已经不在的成员没有代号）
                t.copy(question = anon.names(t.question), options = t.options.map { StanceOption(it.key, anon.names(it.text)) },
                    entries = t.entries.map { e -> e.copy(memberId = anon.code(e.memberId) ?: "已移除的成员") })
            }
            val anonSaid = said.map { s ->
                // calledBy 是 @ 他的那位成员的名字
                Said(s.message.copy(content = anon.all(s.message.content)), anon.code(s.message.senderId) ?: s.name, s.independent,
                    s.calledBy?.let { anon.codeOfName(it) ?: "另一位成员" }, anon.names(s.tools))
            }
            PromptParts(anonOpen, anonSaid, anon.names(ask), "用户")
        } else {
            PromptParts(open, said, ask, userName)
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
                        val who = if (anon != null) id else nameOf(id)
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

        return PromptResult(prompt, anon)
    }

    /** anon：隐名时用来把裁判写回来的代号换回真名；没隐名是 null。 */
    class PromptResult(val prompt: String, val anon: Anon?)

    /** AI 核实：开新议题后，裁判判一次谁对（能查就用 web_search）。members 按对话里的成员顺序（代号跟记立场时一样）。 */
    fun verifyPrompt(topic: StanceTopic, members: List<Member>, anonymous: Boolean): PromptResult {
        val anon = if (anonymous && members.isNotEmpty()) Anon(members, topic.question) else null
        fun who(id: String) = anon?.code(id) ?: members.firstOrNull { it.id == id }?.name ?: "已移除的成员"
        val finalQ = anon?.names(topic.question) ?: topic.question
        val finalOpts = topic.options.map { StanceOption(it.key, anon?.names(it.text) ?: it.text) }
        // 每个立场的持有者
        val finalStances = topic.entries.groupBy { it.option }.mapValues { (_, es) -> es.map { who(it.memberId) }.distinct() }

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

        return PromptResult(prompt, anon)
    }

    data class VerifyResult(val verdict: String, val reason: String)

    /** 读裁判的结论。verdict 只认这道题的立场 key（大小写都行）、none、open、unclear；别的（「A对」「成员甲」）当看不懂，返回 null。 */
    fun parseVerify(raw: String, keys: Collection<String>, anon: Anon? = null): VerifyResult? {
        val o = jsonObjectIn(raw) ?: return null
        val verdict = (o["verdict"] as? JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("立场")?.trim() ?: return null
        val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(160) ?: ""
        val finalVerdict = when (verdict.lowercase()) {
            "none" -> StanceTopic.NONE
            "open" -> StanceTopic.OPEN
            "unclear", "?" -> StanceTopic.UNCLEAR
            else -> keys.firstOrNull { it.equals(verdict, ignoreCase = true) } ?: return null
        }
        return VerifyResult(finalVerdict, anon?.back(reason) ?: reason)
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

    /** anon：隐名时把裁判写的代号换回真名（名字字段和说明文字都换）。 */
    fun parse(raw: String, anon: Anon? = null): Verdict? {
        val o = jsonObjectIn(raw) ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun who(name: String): String = anon?.nameOf(name) ?: name.removePrefix("@").removePrefix("成员")
        fun text(s: String): String = anon?.back(s) ?: s
        val updates = (o["updates"] as? JsonArray).orEmpty().mapNotNull { el ->
            val u = el as? JsonObject ?: return@mapNotNull null
            val topic = (u["topic"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.filter(Char::isDigit)?.toIntOrNull() } ?: return@mapNotNull null
            val member = who(u.s("member"))
            val stance = u.s("stance").ifBlank { StanceTopic.UNCLEAR }
            if (member.isBlank()) return@mapNotNull null
            val why = when (val w = u.s("why")) {
                "没变", "坚持", "" -> Stances.HOLD
                else -> Stances.reasons.firstOrNull { it in w } ?: Stances.HOLD
            }
            Update(topic, member, stance.take(3), text(u.s("newText")).take(40), why, who(u.s("by")), text(u.s("reason")).take(80))
        }
        val nt = o["newTopic"] as? JsonObject
        val newTopic = nt?.let { t ->
            val q = text(t.s("question"))
            val options = (t["options"] as? JsonArray).orEmpty().mapNotNull { el ->
                val x = el as? JsonObject ?: return@mapNotNull null
                val k = x.s("key").take(3)
                if (k.isBlank()) null else k to text(x.s("text")).take(40)
            }
            val stances = (t["stances"] as? JsonArray).orEmpty().mapNotNull { el ->
                val x = el as? JsonObject ?: return@mapNotNull null
                val n = who(x.s("member"))
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
