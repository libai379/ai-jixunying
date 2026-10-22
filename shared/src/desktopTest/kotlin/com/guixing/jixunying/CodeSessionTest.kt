package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.FileChange
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.ToolStep
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
 * Code 栏的数据和建会话 / 换档 / 删会话（docs/Code栏设计说明.md 第 1、5、11 节，2026-10-12）。
 * 这里只测「会话本身」——建、换权限档、删（连改动备份一起删）、设置版本、序列化；
 * 编码循环、工具、审批、差异在后面的单子（engine/CodeAgent.kt）里测。
 */
class CodeSessionTest {
    private val dir = kotlin.io.path.createTempDirectory("jxy-code-session").toFile()

    /** 用户选的那个项目文件夹（文件夹名就是会话标题）。 */
    private val project = File(dir, "proj").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** 装好一个服务商的引擎（模型支不支持调用工具由调用方定），并把 Code 设置打开。 */
    private suspend fun engine(tools: Boolean = true): Engine {
        val e = Engine(Storage(dir))
        // 电脑上跑命令的执行器（桌面端才装得上：安卓没有底层进程接口）。不设的话建不了 Code 会话。
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:1/v1", "sk-test", listOf(ModelInfo("code-model", tools = tools)))))
        val s = e.state.settings
        // 界面发回来的设置带着它认得的版本号（schema = 3），所以这里能改 Code 设置
        e.call(Command.SaveSettings(s.copy(code = CodeSettings(shell = "powershell", commandTimeoutSec = 120, maxSteps = 10, allowPhone = true))))
        return e
    }

    @Test
    fun createCodeSession() = runBlocking {
        val e = engine()
        val r = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
        assertTrue(r.ok, r.message)
        val conv = assertNotNull(e.state.conversation(r.data), "新建的会话要能按 id 找回来")
        val code = assertNotNull(conv.code, "Code 会话的 code 不为空")
        assertEquals(project.absolutePath, code.folder)
        assertEquals("p1", code.providerId)
        assertEquals("code-model", code.modelId)
        assertEquals(CodeMode.MANUAL, code.mode, "没说就是默认的手动档")
        assertFalse(code.fromPhone)
        assertTrue(conv.isCode)
        // 2026-10-17：标题先叫「新对话」，第一条任务发出去换成任务开头（文件夹在侧栏、顶栏另外显示）
        assertEquals("新对话", conv.title)
        assertTrue(conv.memberIds.isEmpty(), "Code 会话没有成员")
        assertTrue(conv.createdAt > 0 && conv.updatedAt > 0)
        // 存储和推送跟普通对话一样：排在最前面，消息列表推一个空的
        assertEquals(r.data, e.state.conversations.first().id)
        assertEquals(emptyList(), e.store.messages.value[r.data])
        // 关掉重开：会话和它的 Code 设置在
        val e2 = Engine(Storage(dir))
        assertEquals(code, e2.state.conversation(r.data)?.code)
    }

    @Test
    fun createFailsWithClearReasons() = runBlocking {
        val e = engine(tools = false)
        val missing = e.call(Command.CodeCreate(File(dir, "没有这个文件夹").absolutePath, "p1", "code-model"))
        assertFalse(missing.ok)
        assertTrue("文件夹不存在" in missing.message, missing.message)

        val file = File(dir, "a.txt").apply { writeText("x") }
        val notFolder = e.call(Command.CodeCreate(file.absolutePath, "p1", "code-model"))
        assertFalse(notFolder.ok)
        assertTrue("不是文件夹" in notFolder.message, notFolder.message)

        val noProvider = e.call(Command.CodeCreate(project.absolutePath, "px", "code-model"))
        assertFalse(noProvider.ok)
        assertTrue("服务商不存在" in noProvider.message, noProvider.message)

        val noModel = e.call(Command.CodeCreate(project.absolutePath, "p1", "没有这个模型"))
        assertFalse(noModel.ok)
        assertTrue("没有模型" in noModel.message, noModel.message)

        // 这个服务商的 code-model 标着不能调工具（ModelInfo.tools = false）
        val noTools = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
        assertFalse(noTools.ok)
        assertTrue("不能调用工具" in noTools.message, noTools.message)

        assertTrue(e.state.conversations.isEmpty(), "一条都没建成，不能留下半个会话")
    }

    @Test
    fun createFailsWithoutRunner() = runBlocking {
        // 手机本机没有跑命令的执行器（codeRunner = null）：第一期只在电脑上用，建不了 Code 会话
        val e = Engine(Storage(dir))
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:1/v1", "sk-test", listOf(ModelInfo("code-model", tools = true)))))
        assertNull(e.codeRunner, "这台（手机）没有跑命令的执行器")
        val r = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model"))
        assertFalse(r.ok)
        assertTrue("这台设备不能跑 Code 栏" in r.message, r.message)
        assertTrue(e.state.conversations.isEmpty())
    }

    @Test
    fun setModeOnlyOnCodeSessions() = runBlocking {
        val e = engine()
        val id = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        assertTrue(e.call(Command.CodeSetMode(id, CodeMode.PLAN)).ok)
        assertEquals(CodeMode.PLAN, e.state.conversation(id)?.code?.mode)
        assertTrue(e.call(Command.CodeSetMode(id, CodeMode.ACCEPT_EDITS)).ok)
        assertEquals(CodeMode.ACCEPT_EDITS, e.state.conversation(id)?.code?.mode)
        // 同一档再点一次不算错
        assertTrue(e.call(Command.CodeSetMode(id, CodeMode.ACCEPT_EDITS)).ok)

        val plain = e.call(Command.CreateConversation(emptyList(), "聊天")).data
        val r = e.call(Command.CodeSetMode(plain, CodeMode.PLAN))
        assertFalse(r.ok)
        assertTrue("不是 Code 会话" in r.message, r.message)
        assertNull(e.state.conversation(plain)?.code)

        assertFalse(e.call(Command.CodeSetMode("没有这个会话", CodeMode.PLAN)).ok)
    }

    @Test
    fun deleteCodeSessionRemovesBackups() = runBlocking {
        val e = engine()
        val id = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        // 假装 AI 改过文件：备份文件夹里放一份改之前的内容（CodeBackup 存的就是这个）
        val backup = File(File(dir, "code"), id).apply { mkdirs() }
        File(backup, "ch1.orig").writeText("改之前的样子")
        val plain = e.call(Command.CreateConversation(emptyList(), "聊天")).data

        assertTrue(e.call(Command.DeleteConversation(id)).ok)
        assertFalse(backup.exists(), "删 Code 会话要连改动备份一起删")
        assertNull(e.state.conversation(id))
        assertNull(e.store.messages.value[id])

        // 普通对话（没有备份文件夹）照样能删，不会去碰 <数据目录>/code/
        assertTrue(e.call(Command.DeleteConversation(plain)).ok)
        assertNull(e.state.conversation(plain))
    }

    @Test
    fun backupSizeCountsEveryBackupFile() = runBlocking {
        val e = engine()
        // 一个会话都还没有、<数据目录>/code/ 都还没建：算 0，不是报错（设置页要能照常显示）
        assertTrue(e.call(Command.CodeBackupSize).ok)
        assertEquals("0", e.call(Command.CodeBackupSize).data)

        val id = e.call(Command.CodeCreate(project.absolutePath, "p1", "code-model")).data
        val backup = File(File(dir, "code"), id).apply { mkdirs() }
        File(backup, "ch1.orig").writeText("12345")            // 5 字节
        File(backup, "ch2.orig").writeText("1234567890")       // 10 字节
        File(File(backup, "cache"), "ch3.diff").apply { parentFile.mkdirs(); writeText("123") } // 子文件夹里的也算：3 字节
        assertEquals("18", e.call(Command.CodeBackupSize).data)

        // 数据目录里别的东西不算（只有 code 文件夹里的才算备份）
        File(dir, "history.jsonl").writeText("y".repeat(200))
        File(File(dir, "code"), "notes.txt").writeText("zz")   // code 文件夹里的散文件也算（万一有残留）
        assertEquals("20", e.call(Command.CodeBackupSize).data)

        // 删会话：备份跟着删，数掉回去（Code 设置页的「1 GB 提醒」也就消了）
        assertTrue(e.call(Command.DeleteConversation(id)).ok)
        assertEquals("2", e.call(Command.CodeBackupSize).data)
        File(File(dir, "code"), "notes.txt").delete()
        assertEquals("0", e.call(Command.CodeBackupSize).data)
    }

    @Test
    fun oldPhoneKeepsCodeSettings() = runBlocking {
        val e = engine()
        val s = e.state.settings
        assertTrue(e.call(Command.SaveSettings(s.copy(code = CodeSettings(shell = "cmd", commandTimeoutSec = 60, maxSteps = 5, allowPhone = true)))).ok)
        assertEquals("cmd", e.state.settings.code.shell)

        // 1.5.0 的手机（schema = 2）不认识 code 这块，发来的是默认值：电脑上改过的要保留
        assertTrue(e.call(Command.SaveSettings(s.copy(schema = 2, code = CodeSettings()))).ok)
        assertEquals(CodeSettings(shell = "cmd", commandTimeoutSec = 60, maxSteps = 5, allowPhone = true), e.state.settings.code)

        // 这一版的界面（schema = 3）能改回来
        assertTrue(e.call(Command.SaveSettings(s.copy(schema = 3, code = CodeSettings(shell = "powershell")))).ok)
        assertEquals("powershell", e.state.settings.code.shell)
        // 10-22 的液态玻璃开关：电脑上关了，10-17 的手机（schema = 6）不认识它、发来默认的「开」，电脑上保留「关」；这一版能改
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(schema = Settings.SCHEMA, glass = false))).ok)
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(schema = 6, glass = true))).ok)
        assertEquals(false, e.state.settings.glass, "旧版手机冲不掉电脑上的液态玻璃开关")
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(schema = Settings.SCHEMA, glass = true))).ok)
        assertEquals(true, e.state.settings.glass)
        assertEquals(7, Settings.SCHEMA, "1.7.0 加了语音和按意思搜（5），10-17 加了个性化（6），10-22 加了液态玻璃开关（7）")
    }

    @Test
    fun serialization() {
        // 带 code 的对话、带新字段的工具步骤：编码再解码一样
        val conv = Conversation(
            id = "c1", title = "proj", createdAt = 1, updatedAt = 2,
            code = CodeSpec("G:\\proj", "p1", "m1", CodeMode.PLAN, fromPhone = true),
        )
        val convText = AppJson.encodeToString(Conversation.serializer(), conv)
        assertTrue("\"code\"" in convText, convText)
        assertEquals(conv, AppJson.decodeFromString(Conversation.serializer(), convText))

        val step = ToolStep(
            kind = "edit", input = "src/a.kt", id = "s1", output = "旧片段换成了新片段", approval = "waiting",
            change = FileChange("ch1", "src/a.kt", added = 3, removed = 1, review = "accepted"),
        )
        val stepText = AppJson.encodeToString(ToolStep.serializer(), step)
        assertEquals(step, AppJson.decodeFromString(ToolStep.serializer(), stepText))

        // 旧版（没有这些字段）的 JSON 也要能读出来，新字段是默认值
        val oldConv = AppJson.decodeFromString(Conversation.serializer(), """{"id":"c9","title":"老对话"}""")
        assertNull(oldConv.code)
        assertFalse(oldConv.isCode)
        assertEquals("老对话", oldConv.title)

        val oldStep = AppJson.decodeFromString(ToolStep.serializer(), """{"kind":"search","input":"天气"}""")
        assertEquals("", oldStep.id)
        assertEquals("", oldStep.output)
        assertEquals("", oldStep.approval)
        assertNull(oldStep.change)

        // 旧版存下来的设置里没有 code：读出来是默认值（以前的设置不会被读坏）
        val oldSettings = AppJson.decodeFromString(Settings.serializer(), """{"proxy":"127.0.0.1:10809"}""")
        assertEquals(CodeSettings(), oldSettings.code)
        assertEquals("powershell", oldSettings.code.shell)

        // 枚举按名字传（联机协议里也是这个名字），前后版本对得上
        val fromJson = AppJson.decodeFromString(
            Conversation.serializer(),
            """{"id":"c2","code":{"folder":"/x","providerId":"p","modelId":"m","mode":"PLAN"}}""",
        )
        assertEquals(CodeMode.PLAN, fromJson.code?.mode)
        assertEquals(CodeMode.MANUAL, CodeSpec("/x", "p", "m").mode)
        assertEquals("手动", CodeMode.MANUAL.label)
        assertEquals("接受编辑", CodeMode.ACCEPT_EDITS.label)
        assertEquals("计划", CodeMode.PLAN.label)
    }
}
