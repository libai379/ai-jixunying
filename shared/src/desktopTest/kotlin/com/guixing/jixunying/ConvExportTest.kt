package com.guixing.jixunying

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.UserProfile
import com.guixing.jixunying.ui.conversationMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 整段对话导出成 Markdown（2026-10-24）、归档字段（旧数据没有这一项也能读）。 */
class ConvExportTest {
    @Test
    fun markdown() {
        val m = Member(id = "m1", name = "阿德", providerId = "p", modelId = "x")
        val state = AppState(members = listOf(m), profile = UserProfile(name = "小白"))
        val c = Conversation(id = "c1", title = "租房", memberIds = listOf("m1"))
        val msgs = listOf(
            Message(id = "1", convId = "c1", role = Role.USER, senderId = "user", content = "押金多少", createdAt = 1,
                attachments = listOf(Attachment(id = "a", name = "合同.docx", mime = "x", size = 1, kind = com.guixing.jixunying.model.AttachmentKind.entries.last()))),
            Message(id = "2", convId = "c1", role = Role.AI, senderId = "m1", content = "<think>想一下</think>两个月", createdAt = 2),
        )
        val md = conversationMarkdown(state, c, msgs)
        assertTrue(md.startsWith("# 租房\n\n成员：阿德"), md)
        assertTrue("## 小白 · " in md && "## 阿德 · " in md, md)
        assertTrue("- 附件：合同.docx" in md)
        assertTrue("两个月" in md)
        assertFalse("想一下" in md, "推理过程不导出")
    }

    @Test
    fun archivedDefaultsFalse() {
        val old = """{"id":"c1","title":"旧对话"}"""
        assertEquals(false, AppJson.decodeFromString(Conversation.serializer(), old).archived)
    }
}
