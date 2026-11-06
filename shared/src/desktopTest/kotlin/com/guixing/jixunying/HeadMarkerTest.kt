package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 边写边显示时扣住开头学来的「〔17:53〕」（2026-11-06 华为：停在半截的回答开头留着「〔17:53〕行，把历史底蕴这段压成总结……」）。
 */
class HeadMarkerTest {
    private val e = Engine(Storage(Files.createTempDirectory("jxy-head").toFile()))

    private fun stream(id: String, vararg parts: String): String {
        var content = ""
        for (p in parts) content += e.holdHead(id, content, p)
        return content
    }

    @Test
    fun markerSplitAcrossDeltasIsDropped() {
        assertEquals("行，把历史底蕴", stream("a", "〔17", ":53〕", "行，把", "历史底蕴"))
        assertEquals("行", stream("b", "〔11月6日 17:53〕\n行"))
    }

    @Test
    fun normalTextUntouched() {
        assertEquals("西马沉香〔注〕很好", stream("c", "西马", "沉香〔注〕很好"))
        // 开头是「〔」但不像时间标记（太长、换行）：原样
        val long = "〔" + "很长的一段话".repeat(10) + "〕后面"
        assertEquals(long, stream("d", long))
        assertEquals("〔第一\n行", stream("e", "〔第一", "\n行"))
    }
}
