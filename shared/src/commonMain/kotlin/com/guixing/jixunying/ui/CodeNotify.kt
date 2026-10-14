package com.guixing.jixunying.ui

import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep

/**
 * 一条要弹给用户的 Code 栏通知（docs/Code栏设计说明.md 第 1 节「通知」，第二期 2026-10-13 补上）。
 * key 相同 = 同一件事，不重复弹；点通知打开应用并切到 convId 这个会话。
 */
data class CodeNotice(val convId: String, val key: String, val title: String, val text: String)

/**
 * 「这次该不该弹、弹什么」——纯判断，不碰界面也不碰系统（单元测试在 CodeNotifyTest）。
 *
 * 什么时候弹（设计说明第 1 节）：
 * - 某个 Code 会话里新出现一步 approval = "waiting"（等你批准）；
 * - 一条 Code 回答从 STREAMING 变成 DONE / ERROR / STOPPED（做完了）。
 * 只在应用不在前台、或者在前台但没打开那个会话时弹；同一步 / 同一条回答不重复弹。
 *
 * 比较的是「上一次看到的消息」和「这一次的消息」：不在上一次里、或者状态没变的都不算新事，
 * 所以打开一个旧会话、重新拉一遍消息不会平白弹一堆通知。
 */
object CodeNotify {

    /**
     * @param before     上一次看到的这个会话的消息（第一次看到这个会话时调用方直接跳过，不调这里）
     * @param after      这一次的消息
     * @param convId     哪个 Code 会话
     * @param title      会话标题（通知里写「哪个会话」用）
     * @param openConvId 现在界面上打开的是哪个会话（null = 没打开任何会话）
     * @param foreground 应用在不在前台
     */
    fun plan(
        before: List<Message>,
        after: List<Message>,
        convId: String,
        title: String,
        openConvId: String?,
        foreground: Boolean,
    ): List<CodeNotice> {
        // 人正看着这个会话就不用打扰（不在前台时照样弹：可能在别的应用里，也可能手机揣兜里）
        if (foreground && openConvId == convId) return emptyList()
        val lastTime = before.associateBy { it.id }
        val out = mutableListOf<CodeNotice>()
        val who = title.ifBlank { "Code 会话" }

        for (m in after) {
            if (m.role != Role.AI) continue
            val prev = lastTime[m.id]
            // 等你批准：这一步上一次不是「waiting」（没见过的也算新出现的）
            val waitingBefore = prev?.tools.orEmpty().filter { it.approval == "waiting" }.map { it.id }.toSet()
            m.tools.filter { it.approval == "waiting" && it.id !in waitingBefore }.forEach { t ->
                out += CodeNotice(convId, key(convId, m.id, t.id, "wait"), "等你批准", "「$who」${stepText(t)}")
            }
            // 做完了：上一次还是「正在答」（PENDING / STREAMING），这一次有结果了
            if (prev == null || (prev.status != MsgStatus.STREAMING && prev.status != MsgStatus.PENDING)) continue
            val text = when (m.status) {
                MsgStatus.DONE -> "「$who」做完了"
                MsgStatus.ERROR -> "「$who」出错了：${m.error.ifBlank { "没说是什么错，打开看看" }}"
                MsgStatus.STOPPED -> "「$who」停下了"
                else -> continue
            }
            out += CodeNotice(convId, key(convId, m.id, "", "done"),
                if (m.status == MsgStatus.ERROR) "Code 栏出错" else "Code 栏", text)
        }
        return out
    }

    private fun key(convId: String, messageId: String, stepId: String, what: String) =
        "code:$convId:$messageId:$stepId:$what"

    /** 等批准的那一步在说哪件事（通知里的一句话）。 */
    private fun stepText(t: ToolStep): String = when (t.kind) {
        "run" -> "要跑一条命令：${t.input.take(60)}"
        "write" -> "要写文件 ${t.input}"
        "edit" -> "要改文件 ${t.input}"
        else -> "有一步在等你批准：${t.input}"
    }
}

/**
 * 盯着消息变化，该弹的时候弹一条系统通知。当前操作的是哪台设备就看哪台：
 * 手机遥控电脑时看的是电脑推过来的消息（设计说明第 6 节），电脑上看的是电脑自己的会话。
 */
class CodeNotifier(private val ctl: AppController) {

    /** 每个 Code 会话上一次看到的消息。 */
    private val seen = HashMap<String, List<Message>>()

    /** 已经弹过的（同一件事只弹一次，重新拉消息也不会再弹）。 */
    private val fired = HashSet<String>()

    /** 消息变了就叫一下（App 里盯着当前后端的所有消息，不只打开的那一个会话）。 */
    fun onMessages(all: Map<String, List<Message>>) {
        val state = ctl.backend.store.state.value
        val titles = state.conversations.filter { it.isCode }.associate { it.id to it.title }
        if (titles.isEmpty()) { seen.keys.retainAll(all.keys); return }
        for ((convId, msgs) in all) {
            val title = titles[convId] ?: continue // 不是 Code 会话（聊天那些不弹）
            val before = seen[convId]
            seen[convId] = msgs
            if (before == null) continue // 第一次看到这个会话：只记下来
            val notices = CodeNotify.plan(before, msgs, convId, title, ctl.currentConvId, ctl.platform.inForeground)
            notices.filter { fired.add(it.key) }.forEach { ctl.platform.notify(it.key, it.title, it.text, it.convId) }
        }
        seen.keys.retainAll(all.keys)
    }
}
