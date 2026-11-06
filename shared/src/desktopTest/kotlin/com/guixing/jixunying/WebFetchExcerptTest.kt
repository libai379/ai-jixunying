package com.guixing.jixunying

import com.guixing.jixunying.engine.WebSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * fetch_url 的 find（2026-11-06）：《诸蕃志》卷上 5 万多字，渤泥国条在 1.5 万字以后，只给前 12000 字时模型看不到，
 * 把占城条的「身丁香」安到了渤泥头上。
 */
class WebFetchExcerptTest {
    private val filler = "閒話不表。".repeat(3000)   // 15000 字
    private val book = "==占城國== 官監民入山斫香輸官，謂之身丁香。" + filler +
        "==渤泥國== 土地所出，梅花腦、速腦、金脚腦、米腦、黃蠟、降眞香、瑇瑁。" + filler

    @Test
    fun longPageSaysHowToSeeTheRest() {
        val s = WebSearch.excerpt(book, null)
        assertFalse("渤泥國" in s)
        assertTrue("全文 ${book.length} 字" in s && "find" in s, s.takeLast(200))
        assertEquals("短网页原样", WebSearch.excerpt("短网页原样", null))
    }

    @Test
    fun findCutsOutTheEntry() {
        val s = WebSearch.excerpt(book, "渤泥國")
        assertTrue("降眞香" in s, s)
        assertFalse("身丁香" in s, "离得远的别的条目不该混进来")
        assertTrue(s.length < 3000, "只给那一处前后：${s.length}")
        // 几个词、挨着的并成一段
        val two = WebSearch.excerpt(book, "梅花腦 降眞香")
        assertEquals(1, Regex("……\\n").findAll(two).count(), two)
        // 没找到：说一声，给开头
        val none = WebSearch.excerpt(book, "麻逸國")
        assertTrue(none.startsWith("（全文") && "没找到" in none)
    }
}
