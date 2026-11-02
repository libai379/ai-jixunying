package com.guixing.jixunying.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 一段话里写到的日期，程序算好离今天多远（2026-11-02，用户说「我生日是农历九月二十四」时 AI 没反应过来就是今天）。
 * 只对号入座、不做判断：模型自己换算农历常错，算出来的事实交给它，要不要提、怎么说由它定。
 * 认这几种：农历 / 阴历 + 月日、「九月初九」「腊月二十三」这种一看就是农历的、带「日」「号」的公历月日（可以带年份）。
 * 「九月二十四」不带农历也不带日、号的不认：分不清是公历还是农历，留给模型问。
 */
object DateMentions {
    private const val CN_MONTH = "正|腊|冬|十一|十二|十|[一二三四五六七八九]|\\d{1,2}"
    private const val CN_DAY = "初[一二三四五六七八九十]|十[一二三四五六七八九]|二十[一二三四五六七八九]?|廿[一二三四五六七八九]|三十|卅|\\d{1,2}"

    /** 农历 / 阴历 / 旧历 开头。 */
    private val LUNAR = Regex("(?:农历|阴历|旧历)\\s*(?:(\\d{4})年)?\\s*(闰)?($CN_MONTH)月\\s*($CN_DAY)[日号]?")
    /** 没写农历但一看就是：X月初Y、正月 / 腊月 / 冬月开头。 */
    private val BARE_LUNAR = Regex("(闰)?(十一|十二|十|[一二三四五六七八九])月(初[一二三四五六七八九十])|(正|腊|冬)月(初[一二三四五六七八九十]|十[一二三四五六七八九]?|二十[一二三四五六七八九]?|廿[一二三四五六七八九]|三十)")
    /** 公历：要带「日」或「号」。 */
    private val SOLAR = Regex("(?:(\\d{4})年)?(十一|十二|十|[一二三四五六七八九]|\\d{1,2})月(三十一|三十|二十[一二三四五六七八九]?|十[一二三四五六七八九]?|[一二三四五六七八九]|\\d{1,2})[日号]")

    /** 写到的一个日期：lunar 为真时 month / day 是农历；year 空 = 没写年份（每年都有的那种）。 */
    class Mention(val raw: String, val lunar: Boolean, val year: Int?, val month: Int, val day: Int, val leap: Boolean, val start: Int)

    private fun cnNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        val t = s.removePrefix("初")
        return when {
            t == "十" -> 10
            t == "卅" -> 30
            t == "三十" -> 30
            t.startsWith("三十") -> 30 + (digits.indexOf(t.last()) + 1)
            t.startsWith("二十") -> 20 + (digits.indexOf(t.last()).takeIf { t.length > 2 }?.plus(1) ?: 0)
            t.startsWith("廿") -> 20 + (digits.indexOf(t.last()) + 1)
            t.startsWith("十") -> 10 + (digits.indexOf(t.last()) + 1)
            t.length == 1 && t[0] in digits -> digits.indexOf(t[0]) + 1
            else -> null
        }
    }

    private fun lunarMonth(s: String): Int? = when (s) {
        "正" -> 1
        "冬" -> 11
        "腊" -> 12
        else -> cnNumber(s)
    }

    /** 文字里写到的日期，按出现先后；重叠的只算一次（农历优先）。 */
    fun scan(text: String): List<Mention> {
        val out = mutableListOf<Mention>()
        fun free(r: IntRange) = out.none { it.start in r || r.first in it.start until it.start + it.raw.length }
        for (m in LUNAR.findAll(text)) {
            val month = lunarMonth(m.groupValues[3]) ?: continue
            val day = cnNumber(m.groupValues[4]) ?: continue
            if (month in 1..12 && day in 1..30) out += Mention(m.value, true, m.groupValues[1].toIntOrNull(), month, day, m.groupValues[2].isNotEmpty(), m.range.first)
        }
        for (m in BARE_LUNAR.findAll(text)) {
            if (!free(m.range)) continue
            val g = m.groupValues
            val month = if (g[2].isNotEmpty()) lunarMonth(g[2]) else lunarMonth(g[4])
            val day = cnNumber(if (g[3].isNotEmpty()) g[3] else g[5])
            if (month != null && day != null && month in 1..12 && day in 1..30) out += Mention(m.value, true, null, month, day, g[1].isNotEmpty(), m.range.first)
        }
        for (m in SOLAR.findAll(text)) {
            if (!free(m.range)) continue
            val month = cnNumber(m.groupValues[2]) ?: continue
            val day = cnNumber(m.groupValues[3]) ?: continue
            if (month in 1..12 && day in 1..31) out += Mention(m.value, false, m.groupValues[1].toIntOrNull(), month, day, false, m.range.first)
        }
        return out.sortedBy { it.start }
    }

    private fun label(d: LocalDate, today: LocalDate, lunarToo: Boolean): String = buildString {
        if (d.year != today.year) append(d.year).append("年")
        append(Today.md(d)).append(" ").append(Today.weekName(d))
        if (lunarToo) append("，农历").append(Today.lunarText(d))
    }

    /** 这个日期最近的两次（没到的那次、刚过的那次）。 */
    private fun nearby(m: Mention, today: LocalDate): Pair<LocalDate?, LocalDate?> {
        // 往后多看几年：农历六月三十这种小月没有的日子，隔几年才有一回
        val years = if (m.lunar) Today.lunarOf(today).year.let { (it - 1)..(it + 5) } else (today.year - 1)..(today.year + 5)
        val all = years.mapNotNull { y ->
            if (m.lunar) Today.fromLunar(y, m.month, m.day, m.leap)
            else runCatching { LocalDate.of(y, m.month, m.day) }.getOrNull()
        }.sorted()
        return all.firstOrNull { !it.isBefore(today) } to all.lastOrNull { it.isBefore(today) }
    }

    /** 给一个日期写一句：「农历九月二十四 = 今天（11月2日 周一）」。算不出来（这一年没有这一天）返回 null。 */
    fun note(m: Mention, today: LocalDate): String? {
        val name = m.raw.trim()
        if (m.year != null) {
            val d = if (m.lunar) Today.fromLunar(m.year, m.month, m.day, m.leap) else runCatching { LocalDate.of(m.year, m.month, m.day) }.getOrNull()
            d ?: return null
            val days = ChronoUnit.DAYS.between(today, d)
            val far = if (kotlin.math.abs(days) > 400) (if (days > 0) "${days / 365} 年多以后" else "${-days / 365} 年多以前") else Today.relative(days)
            return "$name 是 " + (if (m.lunar) "公历 " else "") + label(d, today, !m.lunar) + "（$far）"
        }
        val (next, prev) = nearby(m, today)
        if (next == null) return null
        val nextDays = ChronoUnit.DAYS.between(today, next)
        if (nextDays == 0L) return "$name 就是今天（" + label(next, today, !m.lunar) + "）"
        val ahead = "$name 下一次是 " + label(next, today, !m.lunar) + "（" + Today.relative(nextDays) + "）"
        val pastDays = prev?.let { ChronoUnit.DAYS.between(it, today) }
        return if (prev != null && pastDays != null && pastDays <= 60) "$ahead；今年的 " + label(prev, today, !m.lunar) + " 已经过了（" + Today.relative(-pastDays) + "）" else ahead
    }

    private val DAY_WORDS = mapOf("大后天" to 3L, "后天" to 2L, "明天" to 1L, "昨天" to -1L, "前天" to -2L, "大前天" to -3L)
    private val RELATIVE_DAY = Regex("大后天|大前天|后天|明天|前天|昨天")
    private val RELATIVE_WEEK = Regex("(下下|下|上|这|本)个?(周|星期|礼拜)([一二三四五六日天])")

    /**
     * 「明天」「下周三」这种：程序直接说是几号。模型自己数常错（11-02 真模型：今天周一，有的把下周三答成 11月4日、11月5日；基线里还有把这周末算成 8、9 号的）。
     * 「周三」不带这、下的不认：分不清是这周还是下周。
     */
    fun relativeNotes(text: String, today: LocalDate): List<String> {
        val out = mutableListOf<String>()
        fun add(name: String, d: LocalDate) { out += "$name 是 " + Today.md(d) + " " + Today.weekName(d) + "，农历" + Today.lunarText(d) }
        RELATIVE_DAY.findAll(text).forEach { m -> DAY_WORDS[m.value]?.let { add(m.value, today.plusDays(it)) } }
        RELATIVE_WEEK.findAll(text).forEach { m ->
            val weeks = when (m.groupValues[1]) { "下下" -> 2L; "下" -> 1L; "上" -> -1L; else -> 0L }
            val day = "一二三四五六日".indexOf(m.groupValues[3].replace("天", "日")) + 1
            add(m.value, today.with(java.time.DayOfWeek.MONDAY).plusWeeks(weeks).plusDays(day - 1L))
        }
        return out.distinct()
    }

    /** 这段话里写到的日期都算一遍（写明的 + 明天、下周三这种）；最多四个，没有返回空。 */
    fun notes(text: String, today: LocalDate): List<String> =
        (scan(text).mapNotNull { note(it, today) } + relativeNotes(text, today)).distinct().take(4)

    private val EVERY_YEAR = Regex("生日|出生|生于|纪念|结婚|忌日|周年")

    /**
     * 一条记忆里写到的日期，这几天（今天到 days 天后）有没有对上：对上了返回说法「今天」「4 天后（11月6日 周五）」。
     * 写了年份的是那一年的事（下周的出差），早于今年的不算；生日、纪念日这种带年份的每年都算。
     * 写了农历的只看农历：模型常在后面顺手写一句「2026 年对应公历 11月2日」，那个公历日子明年就不对了。
     */
    fun soon(text: String, today: LocalDate, days: Long = 7): String? {
        val mentions = scan(text)
        val hasLunar = mentions.any { it.lunar }
        for (m in mentions) {
            if (hasLunar && !m.lunar) continue
            val everyYear = m.year == null || EVERY_YEAR.containsMatchIn(text)
            val d = if (everyYear) nearby(m, today).first
            else if (m.year != null && m.year >= today.year) {
                (if (m.lunar) Today.fromLunar(m.year, m.month, m.day, m.leap) else runCatching { LocalDate.of(m.year, m.month, m.day) }.getOrNull())
                    ?.takeIf { !it.isBefore(today) }
            } else null
            d ?: continue
            val n = ChronoUnit.DAYS.between(today, d)
            if (n in 0..days) return if (n == 0L) "今天" else Today.relative(n) + "（" + Today.md(d) + " " + Today.weekName(d) + "）"
        }
        return null
    }
}
