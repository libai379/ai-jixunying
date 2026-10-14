package com.guixing.jixunying

import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.ui.CodeAccess
import com.guixing.jixunying.ui.CodeNotice
import com.guixing.jixunying.ui.CodeNotify
import com.guixing.jixunying.ui.CodeUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Code 栏第二期（手机下令）界面里那两条纯判断（docs/Code栏设计说明.md 第 6 节，2026-10-13）：
 * - CodeUi：这台设备上该显示哪些按钮、写哪句说明（真正把关的在电脑上的 engine/code/PhoneGate）；
 * - CodeNotify：某一步开始等批准、或者一条回答做完了，这次该不该弹通知、弹什么。
 */
class CodeUiNotifyTest {

    // ———————————————— CodeUi.access ————————————————

    @Test
    fun accessOnPcIsFull() {
        // 电脑上：不管设置里那个开关（那是给手机看的），照旧全都能动
        assertEquals(CodeAccess.FULL, CodeUi.access(isDesktop = true, remoteMode = false, allowPhone = false))
        assertEquals(CodeAccess.FULL, CodeUi.access(isDesktop = true, remoteMode = false, allowPhone = true))
    }

    @Test
    fun accessOnPhoneLocalIsNone() {
        // 手机在「本机」：这台设备上跑不了 Code 栏，电脑上开没开开关都不算
        assertEquals(CodeAccess.NO_LOCAL, CodeUi.access(isDesktop = false, remoteMode = false, allowPhone = false))
        assertEquals(CodeAccess.NO_LOCAL, CodeUi.access(isDesktop = false, remoteMode = false, allowPhone = true))
        assertFalse(CodeUi.access(isDesktop = false, remoteMode = false, allowPhone = true).canAct)
    }

    @Test
    fun accessOnPhoneRemoteFollowsHostSwitch() {
        // 手机遥控电脑：看电脑上的开关（手机拿到的是电脑发过来的状态）
        assertEquals(CodeAccess.PHONE, CodeUi.access(isDesktop = false, remoteMode = true, allowPhone = true))
        assertEquals(CodeAccess.LOCKED, CodeUi.access(isDesktop = false, remoteMode = true, allowPhone = false))
        assertTrue(CodeAccess.PHONE.canAct)
        assertFalse(CodeAccess.LOCKED.canAct)
    }

    // ———————————————— CodeUi.modesFor / createModesFor ————————————————

    private fun spec(mode: CodeMode, fromPhone: Boolean = false, pcMode: CodeMode? = null) =
        CodeSpec("G:\\proj", "p1", "m1", mode = mode, fromPhone = fromPhone, pcMode = pcMode)

    @Test
    fun pcListsAllThreeModes() {
        // 电脑上三档全列（新建对话框和会话页都一样）
        assertEquals(CodeMode.entries.toList(), CodeUi.createModesFor(CodeAccess.FULL))
        assertEquals(CodeMode.entries.toList(), CodeUi.modesFor(CodeAccess.FULL, spec(CodeMode.MANUAL)))
    }

    @Test
    fun phoneListsOnlyManualAndPlan() {
        // 手机上只列「手动」「计划」——和电脑上 PhoneGate 允许手机新建的那两档一致
        assertEquals(listOf(CodeMode.MANUAL, CodeMode.PLAN), CodeUi.createModesFor(CodeAccess.PHONE))
        assertEquals(listOf(CodeMode.MANUAL, CodeMode.PLAN), CodeUi.modesFor(CodeAccess.PHONE, spec(CodeMode.MANUAL)))
        // 只读（开关没开 / 手机本机）时列的也是这两档，只是点不动
        assertEquals(listOf(CodeMode.MANUAL, CodeMode.PLAN), CodeUi.modesFor(CodeAccess.LOCKED, spec(CodeMode.MANUAL)))
    }

    @Test
    fun acceptEditsShowsOnlyWhenPcAllowedIt() {
        // 电脑上给这个会话选过「接受编辑」：手机上也能看见、能切回去
        assertTrue(CodeMode.ACCEPT_EDITS in CodeUi.modesFor(CodeAccess.PHONE, spec(CodeMode.MANUAL, pcMode = CodeMode.ACCEPT_EDITS)))
        // 会话现在是「接受编辑」、电脑上建的（旧会话没 pcMode）：按它现在的档算，能看见
        assertTrue(CodeMode.ACCEPT_EDITS in CodeUi.modesFor(CodeAccess.PHONE, spec(CodeMode.ACCEPT_EDITS, fromPhone = false)))
        // 手机建的、电脑上没选过：不列（切过去电脑也会拒）
        assertFalse(CodeMode.ACCEPT_EDITS in CodeUi.modesFor(CodeAccess.PHONE, spec(CodeMode.MANUAL, fromPhone = true)))
        assertFalse(CodeMode.ACCEPT_EDITS in CodeUi.modesFor(CodeAccess.PHONE, spec(CodeMode.ACCEPT_EDITS, fromPhone = true)))
    }

    // ———————————————— CodeNotify.plan ————————————————

    private fun step(id: String, kind: String = "run", input: String = "gradlew test", approval: String = "") =
        ToolStep(kind = kind, input = input, id = id, approval = approval)

    private fun ai(id: String, status: MsgStatus = MsgStatus.STREAMING, tools: List<ToolStep> = emptyList(), error: String = "") =
        Message(id = id, convId = "c1", role = Role.AI, senderId = "code", tools = tools, status = status, error = error)

    private fun plan(before: List<Message>, after: List<Message>, open: String? = null, foreground: Boolean = true) =
        CodeNotify.plan(before, after, convId = "c1", title = "改登录", openConvId = open, foreground = foreground)

    @Test
    fun waitingStepNotifies() {
        val before = listOf(ai("m1", tools = listOf(step("s1"))))
        val after = listOf(ai("m1", tools = listOf(step("s1", approval = "waiting"))))
        val n = plan(before, after)
        assertEquals(1, n.size)
        assertEquals("等你批准", n[0].title)
        assertTrue("改登录" in n[0].text, n[0].text)
        assertTrue("gradlew test" in n[0].text, n[0].text)
        assertEquals("c1", n[0].convId)
    }

    @Test
    fun sameStepWaitingAgainDoesNotNotify() {
        // 同一张快照再来一次（重新拉消息、界面重画）：还是那一步在等，不再弹
        val both = listOf(ai("m1", tools = listOf(step("s1", approval = "waiting"))))
        assertTrue(plan(both, both).isEmpty())
        // 已经批准过的那一步（状态变了）也不再弹
        val allowed = listOf(ai("m1", tools = listOf(step("s1", approval = "allowed"))))
        assertTrue(plan(both, allowed).isEmpty())
    }

    @Test
    fun finishedAnswerNotifies() {
        val before = listOf(ai("m1", MsgStatus.STREAMING, listOf(step("s1", approval = "allowed"))))
        val done = plan(before, listOf(ai("m1", MsgStatus.DONE, listOf(step("s1", approval = "allowed")))))
        assertEquals(1, done.size)
        assertTrue("做完了" in done[0].text, done[0].text)

        val err = plan(before, listOf(ai("m1", MsgStatus.ERROR, error = "模型返回 401")))
        assertEquals(1, err.size)
        assertEquals("Code 栏出错", err[0].title)
        assertTrue("401" in err[0].text, err[0].text)

        val stopped = plan(before, listOf(ai("m1", MsgStatus.STOPPED)))
        assertEquals(1, stopped.size)
        assertTrue("停下了" in stopped[0].text, stopped[0].text)
    }

    @Test
    fun finishedAnswerThatWasAlreadyFinishedDoesNotNotify() {
        // 早就做完的回答（打开旧会话、重新拉一遍消息）：不当成「刚做完」
        val done = listOf(ai("m1", MsgStatus.DONE))
        assertTrue(plan(done, done).isEmpty())
        // 第一次看到这条回答（上一次里没有它）也不算「刚做完」——只有等批准那件事照弹
        assertTrue(plan(emptyList(), done).isEmpty())
    }

    @Test
    fun userMessageNeverNotifies() {
        val u = Message(id = "u1", convId = "c1", role = Role.USER, senderId = "user", content = "改一下")
        assertTrue(plan(emptyList(), listOf(u)).isEmpty())
    }

    @Test
    fun quietWhenWatchingThatConversation() {
        val before = listOf(ai("m1", MsgStatus.STREAMING))
        val after = listOf(ai("m1", MsgStatus.DONE, tools = listOf(step("s1", approval = "waiting"))))
        // 人正看着这个会话、应用也在前台：不打扰
        assertTrue(plan(before, after, open = "c1", foreground = true).isEmpty())
        // 在前台但看的是别的会话：照样弹
        assertEquals(2, plan(before, after, open = "c2", foreground = true).size)
        // 不在前台（揣兜里、在别的应用里）：就算「打开着」也弹
        assertEquals(2, plan(before, after, open = "c1", foreground = false).size)
    }

    @Test
    fun keyIsPerStepAndPerAnswer() {
        // 同一个会话里两件事：key 不一样，各弹各的（同一件事重放 key 也一样，界面靠它去重）
        val before = listOf(ai("m1", MsgStatus.STREAMING), ai("m2", MsgStatus.STREAMING))
        val after = listOf(
            ai("m1", MsgStatus.DONE),
            ai("m2", MsgStatus.DONE, tools = listOf(step("s9", approval = "waiting"))),
        )
        // m1 做完一条、m2 既做完又新出现一步等批准：三件事，三个 key
        val n: List<CodeNotice> = plan(before, after)
        assertEquals(3, n.size)
        assertEquals(3, n.map { it.key }.distinct().size)
        assertTrue(n.all { it.convId == "c1" })
        // 同一条回答里「等批准」和「做完了」是两件事，key 不能撞（撞了后一件会被当成重放吞掉）
        assertTrue(n.any { it.title == "等你批准" } && n.any { it.title == "Code 栏" })
    }
}
