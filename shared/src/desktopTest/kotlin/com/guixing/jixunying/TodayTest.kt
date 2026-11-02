package com.guixing.jixunying

import com.guixing.jixunying.engine.DateMentions
import com.guixing.jixunying.engine.Today
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 今天是什么日子、一句话里写到的日期离今天多远（2026-11-02 用户：「我生日是农历九月二十四」，AI 没反应过来就是今天）。 */
class TodayTest {
    private val today = LocalDate.of(2026, 11, 2)

    @Test
    fun todayFacts() {
        assertEquals("九月二十四", Today.lunarText(today))
        val s = Today.describe(today)
        assertTrue(s.startsWith("2026年11月2日 星期一（工作日），农历丙午（马）年九月二十四"), s)
        assertTrue("10月23日霜降已过" in s && "下一个是11月7日立冬" in s, s)
        // 节气当天、节日、放假、调休
        val winter = Today.describe(LocalDate.of(2026, 11, 7))
        assertTrue("今天是立冬" in winter && "已过" !in winter, winter)
        val national = Today.describe(LocalDate.of(2026, 10, 1))
        assertTrue("放假（国庆节）" in national && "今天是国庆节" in national, national)
        assertTrue("调休上班（国庆节）" in Today.describe(LocalDate.of(2026, 10, 10)))
        assertEquals("周末", Today.workNote(LocalDate.of(2026, 11, 7)))
        // 库里还没有 2027 年的放假安排（国家还没公布）：平日不说是不是工作日，别编
        assertNull(Today.workNote(LocalDate.of(2027, 3, 3)))
        // 闰月
        assertEquals("闰六月初一", Today.lunarText(LocalDate.of(2025, 7, 25)))
        // 节气名字都是中文（库内部有 DA_XUE 这种写法，跨年时别漏出来）
        var d = LocalDate.of(2026, 1, 1)
        repeat(800) {
            val t = Today.describe(d)
            assertFalse(t.any { it in 'A'..'Z' }, "$d：$t")
            d = d.plusDays(1)
        }
    }

    @Test
    fun aheadCalendar() {
        val cal = Today.upcoming(today)
        assertTrue(cal.startsWith("明天 11月3日 周二 九月二十五；后天 11月4日 周三 九月二十六；11月5日 周四 九月二十七"), cal)
        assertTrue("11月7日 周六 九月二十九 立冬" in cal, cal)
        assertTrue("11月9日 下周一 十月初一" in cal, cal)
        assertTrue("11月11日 下周三 十月初三" in cal, cal)
        assertTrue("11月16日 下下周一" in cal, cal)
        val big = Today.bigDaysAhead(today)
        assertTrue(big.startsWith("元旦 2027年1月1日（60 天后）"), big)
        assertTrue("春节 2027年2月6日（96 天后）" in big || "除夕" in big, big)
    }

    @Test
    fun lunarConversion() {
        assertEquals(LocalDate.of(2027, 10, 23), Today.fromLunar(2027, 9, 24))
        assertEquals(LocalDate.of(2027, 2, 6), Today.fromLunar(2027, 1, 1))
        assertEquals(LocalDate.of(2025, 7, 25), Today.fromLunar(2025, 6, 1, leap = true))
        assertNull(Today.fromLunar(2026, 6, 1, leap = true), "2026 年没有闰六月")
        assertNull(Today.fromLunar(2026, 9, 31), "没有三十一")
        val look = Today.lookup(LocalDate.of(2027, 2, 6), today)
        assertTrue("星期六" in look && "正月初一" in look && "春节" in look && "96 天后" in look, look)
        assertTrue("就是今天" in Today.lookup(today, today))
    }

    @Test
    fun mentionsInText() {
        fun one(text: String) = DateMentions.notes(text, today).single()
        // 用户那一句
        assertEquals("农历九月二十四 就是今天（11月2日 周一）", one("我生日是农历九月二十四"))
        assertEquals("阴历九月廿四 就是今天（11月2日 周一）", one("阴历九月廿四是我妈生日"))
        assertEquals("农历9月24 就是今天（11月2日 周一）", one("农历9月24日子不错".replace("日子不错", "")))
        // 公历：明天、几天后、刚过的（今年的已经过了也说）
        assertEquals("11月3日 下一次是 11月3日 周二，农历九月二十五（明天）", one("我11月3日要交标书"))
        assertEquals("11月3号 下一次是 11月3日 周二，农历九月二十五（明天）", one("11月3号开会"))
        assertTrue("11月2日 就是今天" in one("我生日是11月2日"))
        val past = one("我妈生日是10月28日")
        assertTrue(past.startsWith("10月28日 下一次是 2027年10月28日"), past)
        assertTrue("今年的 10月28日 周三，农历九月十九 已经过了（5 天前）" in past, past)
        // 农历：几天后
        assertEquals("农历九月二十八 下一次是 11月6日 周五（4 天后）", one("我儿子生日是农历九月二十八"))
        // 一看就是农历的
        assertTrue("腊月二十三" in one("腊月二十三小年"), one("腊月二十三小年"))
        assertTrue("九月初九" in one("九月初九去爬山"))
        // 带年份
        val y = one("我是1995年11月2日生的")
        assertTrue(y.startsWith("1995年11月2日 是 1995年11月2日 周四，农历九月初十（"), y)
        assertTrue("农历1995年九月二十四 是 公历" in one("我是农历1995年九月二十四生的"), one("我是农历1995年九月二十四生的"))
        // 分不清的不认
        assertTrue(DateMentions.notes("我生日九月二十四", today).isEmpty(), "没写农历也没写日 / 号")
        assertTrue(DateMentions.notes("今天天气不错，五月的时候来过", today).isEmpty())
        assertTrue(DateMentions.notes("2月30日", today).isEmpty(), "没有这一天")
        // 农历没有这一天（小月没有三十）的年份：往后找有的那年，不报错
        assertNotNull(DateMentions.notes("农历六月三十", today).firstOrNull())
    }

    @Test
    fun relativeWords() {
        fun n(t: String) = DateMentions.notes(t, today)
        assertEquals(listOf("下周三 是 11月11日 周三，农历十月初三"), n("下周三是几号"))
        assertEquals(listOf("明天 是 11月3日 周二，农历九月二十五"), n("我明天要去杭州"))
        assertEquals(listOf("这周五 是 11月6日 周五，农历九月二十八"), n("这周五开会"))
        assertEquals(listOf("下星期天 是 11月15日 周日，农历十月初七"), n("下星期天有空吗"))
        assertEquals(listOf("上周三 是 10月28日 周三，农历九月十九"), n("上周三去的"))
        assertEquals(listOf("大后天 是 11月5日 周四，农历九月二十七"), n("大后天走"))
        assertEquals(listOf("下下周一 是 11月16日 周一，农历十月初八"), n("下下周一交"))
        assertTrue(n("周三开会").isEmpty(), "分不清这周还是下周")
        assertTrue(n("今天吃什么").isEmpty())
    }

    @Test
    fun memoryDatesDueSoon() {
        assertEquals("今天", DateMentions.soon("用户的生日是农历九月二十四。", today))
        assertEquals("明天（11月3日 周二）", DateMentions.soon("女儿生日是11月3日", today))
        assertEquals("4 天后（11月6日 周五）", DateMentions.soon("儿子生日是农历九月二十八", today))
        assertNull(DateMentions.soon("妈妈生日是10月28日", today), "刚过的不算")
        assertNull(DateMentions.soon("没有日期的一条记忆", today))
        // 带年份：出生年份按每年算；将来的一次性安排只看那一年
        assertEquals("今天", DateMentions.soon("1995年11月2日出生", today))
        assertNull(DateMentions.soon("2027年11月3日搬家", today))
        assertEquals("明天（11月3日 周二）", DateMentions.soon("2026年11月3日搬家", today))
        assertNull(DateMentions.soon("2025年11月2日交的房", today), "去年的一次性的事")
        // 农历生日后面顺手写的公历，明年不算
        assertNull(DateMentions.soon("生日是农历九月二十四，2026年对应公历11月2日", LocalDate.of(2027, 11, 2)))
        assertEquals("今天", DateMentions.soon("生日是农历九月二十四，2026年对应公历11月2日", LocalDate.of(2027, 10, 23)))
    }
}
