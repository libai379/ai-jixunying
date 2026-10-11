package com.guixing.jixunying.engine

/**
 * 会审模式（1.5.0，docs/构想.md 第二节，参考 Karpathy 的 llm-council 和「讨论越久越容易被带偏」那几篇研究）：
 * ① 各自独立回答（互相看不到）→ ② 匿名互评：每位成员看打乱顺序、隐去名字的全部回答（里面可能有自己的），逐个挑错、排名次
 * → ③ 主席汇总：中立的裁判模型写最终答案，必须写出首轮分歧、各方依据、少数派意见、还不确定的地方。
 * 只讨论一轮，不接力；互评的名次由程序算成平均名次附在汇总后面（不靠模型自己算）。
 * 这里只放提示词和解析，调度在 Engine.runCouncil。
 */
object Council {
    val letters = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")

    /** 互评：给每位成员看的那段话。answers 是（代号, 回答原文），已经打乱。 */
    fun reviewPrompt(question: String, answers: List<Pair<String, String>>): String = buildString {
        append("用户的问题：\n").append(question.trim()).append("\n\n")
        append("下面是几位 AI 各自独立给出的回答。名字隐去了、顺序打乱了，里面可能有你自己的。\n\n")
        answers.forEach { (k, text) -> append("【回答").append(k).append("】\n").append(text.trim().take(6000)).append("\n\n") }
        append("请做互评：\n")
        append("1. 逐个看每个回答：哪里对，哪里错（事实、计算、逻辑），漏了什么。说具体，不客套；全对就说全对。\n")
        append("2. 最后单独一行写名次，从好到差，格式：名次：").append(answers.joinToString(" > ") { it.first }).append("\n")
        append("只评回答本身，不猜是谁写的。")
    }

    /** 从互评里找「名次：甲 > 丙 > 乙」，返回回答下标的顺序；找不到或少于两个返回 null。 */
    fun parseRanking(text: String, n: Int): List<Int>? {
        val line = text.lines().lastOrNull { Regex("""名次\s*[:：]""").containsMatchIn(it) } ?: return null
        val order = mutableListOf<Int>()
        for (ch in line.substringAfter('：').substringAfter(':')) {
            val i = letters.indexOf(ch.toString())
            if (i in 0 until n && i !in order) order += i
        }
        return order.takeIf { it.size >= 2 }
    }

    /** 平均名次（越小越好）；没人给它排名的不算。 */
    fun averageRanks(rankings: List<List<Int>>, n: Int): Map<Int, Double> =
        (0 until n).mapNotNull { i ->
            val places = rankings.mapNotNull { r -> r.indexOf(i).takeIf { it >= 0 }?.plus(1) }
            if (places.isEmpty()) null else i to places.average()
        }.toMap()

    /** 主席汇总：问题、全部回答（代号）、全部互评（只标第几份）、程序算的平均名次。 */
    fun chairPrompt(question: String, answers: List<Pair<String, String>>, reviews: List<String>, avg: Map<Int, Double>): String = buildString {
        append("你是这次会审的主席，要给用户写最终答案。\n\n用户的问题：\n").append(question.trim()).append("\n\n")
        append("几位 AI 各自独立的回答（隐去了名字）：\n\n")
        answers.forEach { (k, text) -> append("【回答").append(k).append("】\n").append(text.trim().take(6000)).append("\n\n") }
        if (reviews.isNotEmpty()) {
            append("它们互相评审的意见：\n\n")
            reviews.forEachIndexed { i, r -> append("【评审 ").append(i + 1).append("】\n").append(r.trim().take(4000)).append("\n\n") }
        }
        if (avg.isNotEmpty()) {
            append("互评的平均名次（越小越好）：")
            append(avg.entries.sortedBy { it.value }.joinToString("，") { "回答${letters[it.key]} ${"%.1f".format(it.value)}" }).append("\n\n")
        }
        append("请写最终答案：\n")
        append("- 先直接给结论。\n")
        append("- 首轮分歧：一开始有几种说法，各是什么，哪几个回答一致。\n")
        append("- 依据：结论靠什么站得住；互评里指出的错误要说清楚是对是错。\n")
        append("- 少数派意见：和结论不一样的说法是什么、有没有道理，不能略掉；少数派说对了就照少数派的来。\n")
        append("- 还不确定的地方。\n")
        append("提到某个回答时用「回答甲」这样的代号。名次只是参考，结论按道理定，不按人多。")
    }

    /**
     * 回答写完、给人看之前：「回答甲」「【甲】」换回真名（一次换完，免得名字里刚好有代号的字被再换一遍）。
     * 「乙、丙都答 4」这种光写一个字的不换（单字太容易误伤）。
     */
    fun reveal(text: String, names: Map<String, String>): String {
        if (names.isEmpty()) return text
        val keys = names.keys.joinToString("|") { Regex.escape(it) }
        val re = Regex("回答($keys)|【($keys)】")
        return re.replace(text) { m ->
            val k = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val n = names[k] ?: return@replace m.value
            if (m.groupValues[1].isNotEmpty()) "$n（回答$k）" else "【$n（回答$k）】"
        }
    }
}
