package com.guixing.jixunying

import com.guixing.jixunying.engine.BackgroundWatch
import com.guixing.jixunying.engine.ChatResult
import com.guixing.jixunying.engine.ToolCall
import com.guixing.jixunying.model.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 「看不懂」时记下的模型原话（BgProblem.raw）：用户要能看出是格式问题还是别的。 */
class BackgroundRawTest {
    private fun r(content: String, reasoning: String = "", tools: List<ToolCall> = emptyList(), finish: String? = null) =
        ChatResult(content, reasoning, tools, Usage(), finish)

    @Test
    fun shortReplyKeptAsIsWithSpacesCollapsed() {
        assertEquals("裁判说 A对， 理由是 对折两次", BackgroundWatch.rawOf(r("裁判说  A对，\n理由是 对折两次")))
    }

    @Test
    fun longReplyCutAt300WithTotalLength() {
        val raw = BackgroundWatch.rawOf(r("字".repeat(500)))
        assertTrue(raw.startsWith("字".repeat(300) + "……"), raw)
        assertTrue("共 500 字" in raw, raw)
    }

    @Test
    fun emptyReplySaysWhy() {
        val raw = BackgroundWatch.rawOf(r("", reasoning = "想".repeat(1234), tools = listOf(ToolCall("t1", "web_search", "{}")), finish = "tool_calls"))
        assertEquals("（正文是空的：结束原因 tool_calls，还想调用工具 web_search，推理 1234 字）", raw)
        assertEquals("（正文是空的）", BackgroundWatch.rawOf(r("   ")))
    }
}
