package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.code.PhoneGate
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机发来的指令过的那一道（engine/code/PhoneGate，docs/Code栏设计说明.md 第 6 节，2026-10-13 第二期）。
 * 走中转的全流程在 CodeAgentTest.phoneThroughRelay 里测；这里直接喂 PhoneGate 再交给引擎，把每条规则过一遍。
 */
class PhoneGateTest {
    private val dir = kotlin.io.path.createTempDirectory("jxy-phonegate").toFile()
    private val project = File(dir, "proj").apply { mkdirs() }
    private val other = File(dir, "other").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private suspend fun engine(allowPhone: Boolean): Engine {
        val e = Engine(Storage(File(dir, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:1/v1", "sk-test", listOf(ModelInfo("code-model", tools = true)))))
        e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(allowPhone = allowPhone))))
        return e
    }

    /** 当成手机发来的：先过 PhoneGate，再交给引擎（和 RelayHost 一样）。 */
    private suspend fun Engine.fromPhone(c: Command) = PhoneGate.check(c, state).let { v -> v.refusal ?: call(v.command) }

    @Test
    fun phoneCannotChangeCodeSettings() = runBlocking {
        val e = engine(allowPhone = false)
        // 改过的手机发一条保存设置，想自己打开开关、把外壳换成别的程序：Code 栏那几项保留电脑上的，别的设置照存
        val forged = e.state.settings.copy(code = CodeSettings(shell = "C:\\evil.exe", allowPhone = true, maxSteps = 999))
        assertTrue(e.fromPhone(Command.SaveSettings(forged.copy(darkMode = 2))).ok)
        assertEquals(CodeSettings(allowPhone = false), e.state.settings.code)
        assertEquals(2, e.state.settings.darkMode)
        // 电脑上自己存照样能改
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(shell = "cmd", allowPhone = true)))).ok)
        assertEquals("cmd", e.state.settings.code.shell)
    }

    @Test
    fun switchOffBlocksWritesButNotReads() = runBlocking {
        val s = engine(allowPhone = false).state
        for (c in listOf(
            Command.CodeCreate(project.absolutePath, "p1", "code-model"), Command.CodeSend("c1", "改一下"), Command.CodeStop("c1"),
            Command.CodeDecide("c1", "s1", true), Command.CodeSetMode("c1", CodeMode.PLAN), Command.CodeReview("c1", "ch1", false),
            Command.CodeRevertTask("c1", "m1"),
        )) {
            val r = assertNotNull(PhoneGate.check(c, s).refusal, "$c 应该被拦")
            assertEquals(PhoneGate.OFF, r.message)
        }
        assertNull(PhoneGate.check(Command.CodeDiff("c1", "ch1"), s).refusal)
        assertNull(PhoneGate.check(Command.CodeBackupSize, s).refusal)
    }

    /**
     * 2026-10-28 用户在手机上遥控修服务器：只能挂在电脑上建过的文件夹、只能选手动 / 计划，点了 51 次允许。
     * 改成手机和电脑一样：任意存在的文件夹、五档都能选，标成手机发起。
     */
    @Test
    fun phoneCreatesAnywhereWithAnyMode() = runBlocking {
        val e = engine(allowPhone = true)
        // 电脑上一个会话都没建过：手机照样能用电脑上任意文件夹；手机自己填 fromPhone = false 不算
        val first = e.fromPhone(Command.CodeCreate(other.absolutePath, "p1", "code-model", fromPhone = false))
        assertTrue(first.ok, first.message)
        assertTrue(e.state.conversation(first.data)!!.code!!.fromPhone)

        // 电脑上建过的文件夹换个写法（大小写、结尾斜杠）：存电脑上原来那个写法
        e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
        val same = e.fromPhone(Command.CodeCreate(project.absolutePath.uppercase() + "\\", "p1", "code-model"))
        assertEquals(project.absolutePath, e.state.conversation(same.data)!!.code!!.folder)

        // 不存在的文件夹由引擎报错
        assertFalse(e.fromPhone(Command.CodeCreate(File(dir, "没有这个").absolutePath, "p1", "code-model")).ok)

        // 新建就能选「全部放开」「自动」「接受编辑」；计划档带着全部放开也还是计划
        suspend fun create(c: Command.CodeCreate): com.guixing.jixunying.model.CodeSpec {
            val r = e.fromPhone(c)
            assertTrue(r.ok, r.message)
            return e.state.conversation(r.data)!!.code!!
        }
        val open = create(Command.CodeCreate(project.absolutePath, "p1", "code-model", openAll = true))
        assertTrue(open.openAll); assertFalse(open.autoMode); assertEquals(CodeMode.ACCEPT_EDITS, open.mode)
        val auto = create(Command.CodeCreate(project.absolutePath, "p1", "code-model", auto = true))
        assertTrue(auto.autoMode); assertFalse(auto.openAll); assertEquals(CodeMode.ACCEPT_EDITS, auto.mode)
        assertEquals(CodeMode.ACCEPT_EDITS, create(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.ACCEPT_EDITS)).mode)
        val plan = create(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.PLAN, openAll = true))
        assertEquals(CodeMode.PLAN, plan.mode); assertFalse(plan.openAll)
    }

    @Test
    fun phoneChangesModeAndWorkspaceLikePc() = runBlocking {
        val e = engine(allowPhone = true)
        val pc = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        fun spec() = e.state.conversation(pc)!!.code!!
        // 自动、全部放开、接受编辑、手动：手机上都能切（以前切走自动就回不来）
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.ACCEPT_EDITS, auto = true)).ok)
        assertTrue(spec().autoMode)
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.ACCEPT_EDITS, openAll = true)).ok)
        assertTrue(spec().openAll)
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.MANUAL)).ok)
        assertEquals(CodeMode.MANUAL, spec().mode); assertFalse(spec().openAll); assertFalse(spec().autoMode)
        // 加工作区、「不再问」的命令
        assertTrue(e.fromPhone(Command.CodeSetAccess(pc, listOf(other.absolutePath), listOf("git status"))).ok)
        assertEquals(listOf(other.absolutePath), spec().extraPaths)
        // 批准时「顺便加进工作区 / 不再问」照原样交给引擎（以前手机上一律当成只允许这一次）
        val d = PhoneGate.check(Command.CodeDecide(pc, "s1", true, remember = "dir"), e.state)
        assertNull(d.refusal)
        assertEquals("dir", (d.command as Command.CodeDecide).remember)
        // 换项目文件夹和模型、团队、一条龙：放行
        for (c in listOf(Command.CodeSetProject(pc, folder = other.absolutePath), Command.CodeSetTeam(pc, "review", "p1", "code-model"),
            Command.CodeSetFlow(pc, ""))) assertNull(PhoneGate.check(c, e.state).refusal, "$c 应该放行")
        // 开关关了：照样都拦
        e.call(Command.SaveSettings(e.state.settings.copy(code = CodeSettings(allowPhone = false))))
        assertEquals(PhoneGate.OFF, PhoneGate.check(Command.CodeSetMode(pc, CodeMode.ACCEPT_EDITS, openAll = true), e.state).refusal?.message)
    }

    @Test
    fun revertAllOnlyOnPc() = runBlocking {
        val s = engine(allowPhone = true).state
        val r = assertNotNull(PhoneGate.check(Command.CodeRevertTask("c1", "m1"), s).refusal)
        assertTrue("只能在电脑上" in r.message, r.message)
        // 一个文件一个文件地接受 / 拒绝、批准某一步、停止、发任务：放行
        for (c in listOf(Command.CodeReview("c1", "ch1", false), Command.CodeDecide("c1", "s1", true), Command.CodeStop("c1"), Command.CodeSend("c1", "接着改"))) {
            assertNull(PhoneGate.check(c, s).refusal, "$c 应该放行")
        }
    }

    @Test
    fun foldersListIsDistinct() = runBlocking {
        val e = engine(allowPhone = true)
        e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
        e.call(Command.CodeCreate(project.absolutePath + "\\", "p1", "code-model"))
        e.call(Command.CodeCreate(other.absolutePath, "p1", "code-model"))
        assertEquals(2, com.guixing.jixunying.model.CodePhone.folders(e.state).size)
    }
}
