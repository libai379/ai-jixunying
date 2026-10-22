package com.guixing.jixunying

import com.guixing.jixunying.ui.DiffComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 差异逐行评论（2026-10-18，开发补齐第 7 样）：评论落在改后文件的第几行；攒下的评论变成一条任务。 */
class DiffCommentTest {
    @Test
    fun lineNumbersAndTask() {
        val diff = listOf("--- a/x.kt", "+++ b/x.kt", "@@ -10,3 +10,4 @@", " a", "-b", "+c", "+d", " e")
        assertEquals(10, DiffComment.newLineOf(diff, 3), "第一行上下文")
        assertEquals(11, DiffComment.newLineOf(diff, 5), "+c 是改后第 11 行")
        assertEquals(12, DiffComment.newLineOf(diff, 6))
        val t = DiffComment.task(listOf(DiffComment("x.kt", 11, "c", "要判断空值")))
        assertTrue("x.kt 第 11 行「c」：要判断空值" in t, t)
    }

    /** 2026-10-22：差异窗口左边的行数从完整差异里数，文件头 +++ / --- 不算。 */
    @Test
    fun countsAddedAndRemovedLines() {
        val diff = listOf("--- a/x.kt", "+++ b/x.kt", "@@ -10,3 +10,4 @@", " a", "-b", "+c", "+d", " e", "@@ -40,2 +41,1 @@", "-f", "-g", "+h").joinToString("\n")
        assertEquals(3 to 3, com.guixing.jixunying.ui.CodeDiffText.count(diff))
        assertEquals(0 to 0, com.guixing.jixunying.ui.CodeDiffText.count(""))
    }
}
