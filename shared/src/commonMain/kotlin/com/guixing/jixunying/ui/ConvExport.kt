package com.guixing.jixunying.ui

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.launch

/**
 * 整段对话导出成 Markdown 文件（2026-10-24，照 Cherry Studio「导出话题」）：标题、成员、每条消息（谁、什么时候、正文），
 * 附件只写文件名；推理过程不导出。记录没加载过的先取一次。
 */
fun exportConversationMarkdown(ctl: AppController, state: AppState, c: Conversation) {
    ctl.scope.launch {
        if (!ctl.backend.store.hasMessages(c.id)) ctl.backend.call(Command.LoadMessages(c.id))
        val msgs = ctl.backend.store.messages.value[c.id].orEmpty()
        if (msgs.isEmpty()) { ctl.toast("这个对话还没有消息"); return@launch }
        val md = conversationMarkdown(state, c, msgs)
        val name = c.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40).ifBlank { "对话" } + ".md"
        if (ctl.platform.saveFile(name, md.toByteArray())) ctl.toast("已导出《$name》")
    }
}

fun conversationMarkdown(state: AppState, c: Conversation, msgs: List<Message>): String = buildString {
    append("# ").append(c.title).append("\n\n")
    val names = c.memberIds.mapNotNull { state.member(it)?.name }
    if (names.isNotEmpty()) append("成员：").append(names.joinToString("、")).append("\n\n")
    for (m in msgs) {
        val who = if (m.role == Role.USER) state.profile.name.ifBlank { "我" } else state.member(m.senderId)?.name ?: "AI"
        append("## ").append(who).append(" · ").append(formatTime(m.createdAt)).append("\n\n")
        append(splitThink(m.content).first.trim()).append("\n\n")
        m.attachments.forEach { a -> append("- 附件：").append(a.name).append("\n") }
        if (m.attachments.isNotEmpty()) append("\n")
    }
}
