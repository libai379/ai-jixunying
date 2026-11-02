package com.guixing.jixunying.engine

import com.nlf.calendar.Lunar
import com.nlf.calendar.Solar
import com.nlf.calendar.util.HolidayUtil
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「今天」是什么日子（2026-11-02 用户：说「我生日是农历九月二十四」，AI 只回「记住了」，没反应过来就是今天）。
 * 时钟以前只有公历和星期，模型换算不了农历，也算不准几天以后是星期几。这里把算好的事实交给它：
 * 农历、节气、节日、放假调休用 lunar-java（cn.6tail:lunar），和香港天文台对照表逐日核对过（docs/参考资料.md）。
 * 一律北京时间（Prompts.clock 也是）。
 */
object Today {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun date(ms: Long = System.currentTimeMillis()): LocalDate = Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate()

    private val WEEK_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val LUNAR_DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "二十一", "二十二", "二十三", "二十四", "二十五", "二十六", "二十七", "二十八", "二十九", "三十",
    )
    private val LUNAR_MONTHS = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")

    /** 够大的节：放进「再往后」那一句，别的（母亲节、万圣节……）只在两周日历里顺带写。 */
    private val BIG_DAYS = setOf("元旦节", "除夕", "春节", "元宵节", "清明节", "劳动节", "端午节", "七夕节", "中秋节", "国庆节", "重阳节", "腊八节")

    fun lunarOf(d: LocalDate): Lunar = Solar.fromYmd(d.year, d.monthValue, d.dayOfMonth).lunar

    fun weekName(d: LocalDate): String = WEEK_NAMES[d.dayOfWeek.value - 1]

    fun lunarDayName(day: Int): String = LUNAR_DAYS[day - 1]

    /** month 是 lunar-java 的写法：闰月是负的。 */
    fun lunarMonthName(month: Int): String = (if (month < 0) "闰" else "") + LUNAR_MONTHS[kotlin.math.abs(month) - 1]

    /** 「九月二十四」「闰六月初三」。 */
    fun lunarText(d: LocalDate): String = lunarOf(d).let { lunarMonthName(it.month) + lunarDayName(it.day) }

    fun md(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

    /** 这一天的节日和节气（「中秋节」「立冬」）。 */
    fun festivalsOf(d: LocalDate): List<String> {
        val solar = Solar.fromYmd(d.year, d.monthValue, d.dayOfMonth)
        val lunar = solar.lunar
        return (lunar.festivals + solar.festivals + listOfNotNull(lunar.jieQi.takeIf { it.isNotBlank() })).distinct()
    }

    /** 放假安排：「放假（国庆节）」「调休上班（国庆节）」；库里有这一年的安排时平常日子是「工作日 / 周末」；没有这一年的（还没公布）周末照写、平日返回 null。 */
    fun workNote(d: LocalDate): String? {
        val h = runCatching { HolidayUtil.getHoliday(d.year, d.monthValue, d.dayOfMonth) }.getOrNull()
        if (h != null) return (if (h.isWork) "调休上班（" else "放假（") + h.name + "）"
        val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        if (weekend) return "周末"
        return if (HolidayUtil.getHolidays(d.year).isNotEmpty()) "工作日" else null
    }

    /** 今天那一句：一天之内不变（分钟不放这里，见 Prompts「今天」的说明）。 */
    fun describe(d: LocalDate): String = buildString {
        val lunar = lunarOf(d)
        append(d.year).append("年").append(md(d)).append(" 星期").append("一二三四五六日"[d.dayOfWeek.value - 1])
        workNote(d)?.let { append("（").append(it).append("）") }
        append("，农历").append(lunar.yearInGanZhi).append("（").append(lunar.yearShengXiao).append("）年").append(lunarText(d))
        val fest = festivalsOf(d)
        if (fest.isNotEmpty()) append("，今天是").append(fest.joinToString("、"))
        append("。节气：")
        val term = lunar.jieQi
        if (term.isBlank()) lunar.prevJieQi.let { append(md(toLocal(it.solar))).append(it.name).append("已过，") }
        lunar.nextJieQi.let { append("下一个是").append(md(toLocal(it.solar))).append(it.name) }
        append("。")
    }

    private fun toLocal(s: Solar): LocalDate = LocalDate.of(s.year, s.month, s.day)

    /** 接下来 days 天：相对叫法（明天、下周三）、公历、农历，有节日、放假的写上。模型自己算星期和农历会错，一眼能查。 */
    fun upcoming(d: LocalDate, days: Int = 14): String {
        val thisMonday = d.with(DayOfWeek.MONDAY)
        return (1..days).joinToString("；") { i ->
            val x = d.plusDays(i.toLong())
            val weeks = ChronoUnit.WEEKS.between(thisMonday, x.with(DayOfWeek.MONDAY))
            val week = when (weeks) {
                0L -> ""
                1L -> "下"
                else -> "下下"
            } + weekName(x)
            val rel = when (i) {
                1 -> "明天 "
                2 -> "后天 "
                else -> ""
            }
            val extra = festivalsOf(x) + listOfNotNull(workNote(x)?.takeIf { it.startsWith("放假") || it.startsWith("调休") })
            rel + md(x) + " " + week + " " + lunarText(x) + if (extra.isEmpty()) "" else " " + extra.joinToString(" ")
        }
    }

    /** 两周日历以外最近的几个大节（「元旦 1月1日（60 天后）」）。 */
    fun bigDaysAhead(d: LocalDate, skipDays: Int = 14, count: Int = 4): String {
        val out = mutableListOf<String>()
        var i = skipDays + 1L
        while (out.size < count && i <= 400) {
            val x = d.plusDays(i)
            festivalsOf(x).firstOrNull { it in BIG_DAYS }?.let { name ->
                out += name.removeSuffix("节").let { if (it.length < 2) name else it } + " " + (if (x.year != d.year) "${x.year}年" else "") + md(x) + "（$i 天后）"
            }
            i++
        }
        return out.joinToString("、")
    }

    private val aheadMemo = java.util.concurrent.ConcurrentHashMap<LocalDate, Pair<String, String>>()

    /** 两周日历和再往后的大节，一天算一次（每次回答都要用，算一遍要翻几十上百天）。 */
    fun ahead(d: LocalDate): Pair<String, String> = aheadMemo[d] ?: (upcoming(d) to bigDaysAhead(d)).also {
        if (aheadMemo.size > 3) aheadMemo.clear()
        aheadMemo[d] = it
    }

    /** 公历这一天的全部说明（查日历工具用）：相对今天几天、星期、农历、节日、放假。 */
    fun lookup(x: LocalDate, today: LocalDate = date()): String = buildString {
        val days = ChronoUnit.DAYS.between(today, x)
        val lunar = lunarOf(x)
        append("公历 ").append(x.year).append("年").append(md(x)).append(" 星期").append("一二三四五六日"[x.dayOfWeek.value - 1])
        append("；农历").append(lunar.yearInGanZhi).append("（").append(lunar.yearShengXiao).append("）年").append(lunarText(x))
        val fest = festivalsOf(x)
        if (fest.isNotEmpty()) append("；").append(fest.joinToString("、"))
        workNote(x)?.let { append("；").append(it) }
        append("；").append(relative(days))
    }

    fun relative(days: Long): String = when {
        days == 0L -> "就是今天"
        days == 1L -> "明天"
        days == 2L -> "后天"
        days == -1L -> "昨天"
        days == -2L -> "前天"
        days > 0 -> "$days 天后"
        else -> "${-days} 天前"
    }

    /** 农历某年某月某日换成公历；这一年没有这一天（小月没有三十、没有这个闰月）返回 null。 */
    fun fromLunar(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate? = runCatching {
        val s = Lunar.fromYmd(year, if (leap) -month else month, day).solar
        LocalDate.of(s.year, s.month, s.day)
    }.getOrNull()
}
