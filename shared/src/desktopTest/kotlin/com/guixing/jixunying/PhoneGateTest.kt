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

    @Test
    fun phoneCreatesOnlyInKnownFolders() = runBlocking {
        val e = engine(allowPhone = true)
        // 电脑上还没建过任何 Code 会话：手机一个文件夹都用不了
        val none = e.fromPhone(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
        assertFalse(none.ok)
        assertTrue("已经建过" in none.message, none.message)
        assertTrue(e.state.conversations.none { it.code != null })

        val pcId = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        assertFalse(e.state.conversation(pcId)!!.code!!.fromPhone)

        // 同一个文件夹（换个写法：大小写、结尾斜杠、绕一圈的 ..）能建，标成手机发起；手机自己填 fromPhone = false 不算
        val ok = e.fromPhone(Command.CodeCreate(project.absolutePath.uppercase() + "\\", "p1", "code-model", fromPhone = false))
        assertTrue(ok.ok, ok.message)
        val spec = e.state.conversation(ok.data)!!.code!!
        assertTrue(spec.fromPhone)
        assertEquals(project.absolutePath, spec.folder, "存电脑上原来那个写法")
        assertNull(spec.pcMode)
        assertTrue(e.fromPhone(Command.CodeCreate(File(other, "..\\proj").path, "p1", "code-model")).ok)

        // 别的文件夹、项目里面的子文件夹、上一级：都不行
        for (p in listOf(other.absolutePath, File(project, "sub").apply { mkdirs() }.absolutePath, dir.absolutePath, "", "C:\\")) {
            val r = e.fromPhone(Command.CodeCreate(p, "p1", "code-model"))
            assertFalse(r.ok, "「$p」不该能建")
        }
        // 「接受编辑」不能在手机上新建
        val loose = e.fromPhone(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.ACCEPT_EDITS))
        assertFalse(loose.ok)
        assertTrue("接受编辑" in loose.message, loose.message)
        // 计划档可以
        assertTrue(e.fromPhone(Command.CodeCreate(project.absolutePath, "p1", "code-model", CodeMode.PLAN)).ok)
    }

    @Test
    fun phoneCannotLoosenMode() = runBlocking {
        val e = engine(allowPhone = true)
        val pc = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        assertEquals(CodeMode.MANUAL, e.state.conversation(pc)!!.code!!.pcMode)

        // 电脑上选的是手动：手机切到计划、再切回手动都行，切到接受编辑不行
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.PLAN)).ok)
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.MANUAL)).ok)
        assertFalse(e.fromPhone(Command.CodeSetMode(pc, CodeMode.ACCEPT_EDITS)).ok)
        assertEquals(CodeMode.MANUAL, e.state.conversation(pc)!!.code!!.mode)
        // 手机换档不改 pcMode（手机自己填 fromPhone = false 也不算）
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.PLAN, fromPhone = false)).ok)
        assertEquals(CodeMode.MANUAL, e.state.conversation(pc)!!.code!!.pcMode)

        // 电脑上给它选了接受编辑：手机也能切过去
        assertTrue(e.call(Command.CodeSetMode(pc, CodeMode.ACCEPT_EDITS)).ok)
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.MANUAL)).ok)
        assertTrue(e.fromPhone(Command.CodeSetMode(pc, CodeMode.ACCEPT_EDITS)).ok)
        assertEquals(CodeMode.ACCEPT_EDITS, e.state.conversation(pc)!!.code!!.mode)

        // 手机建的会话：电脑上没选过，切不到接受编辑；电脑上切过以后就行
        val ph = e.fromPhone(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        assertFalse(e.fromPhone(Command.CodeSetMode(ph, CodeMode.ACCEPT_EDITS)).ok)
        assertTrue(e.call(Command.CodeSetMode(ph, CodeMode.ACCEPT_EDITS)).ok)
        assertTrue(e.fromPhone(Command.CodeSetMode(ph, CodeMode.ACCEPT_EDITS)).ok)
    }

    @Test
    fun oldSessionsWithoutPcMode() {
        // 1.7.0 建的会话没有 pcMode：电脑上建的按它现在的档算，手机建的不算
        val pcSpec = com.guixing.jixunying.model.CodeSpec("G:\\p", "p1", "m", CodeMode.ACCEPT_EDITS)
        assertTrue(PhoneGate.pcAllowsAcceptEdits(pcSpec))
        assertFalse(PhoneGate.pcAllowsAcceptEdits(pcSpec.copy(mode = CodeMode.MANUAL)))
        assertFalse(PhoneGate.pcAllowsAcceptEdits(pcSpec.copy(fromPhone = true)))
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
        assertEquals(2, PhoneGate.folders(e.state).size)
    }
}
