package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Code 栏上传到 GitHub（2026-10-18，engine/code/GitHub）：不连网，用本机一个空的「远端」仓库代替 GitHub，
 * 走一遍：看状态 → 提交并推送 → 远端真收到了。建仓库（gh repo create）要连 GitHub、要登录，这里不测。
 */
class GitHubTest {
    private val tmp = kotlin.io.path.createTempDirectory("jxy-git").toFile()

    @AfterTest
    fun cleanup() { tmp.deleteRecursively() }

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    @Test
    fun commitAndPushToRemote() = runBlocking {
        val remote = File(tmp, "remote.git").apply { mkdirs() }
        git(remote, "init", "--bare", "-b", "main")
        val proj = File(tmp, "proj").apply { mkdirs() }
        git(proj, "init", "-b", "main")
        git(proj, "config", "user.email", "t@t")
        git(proj, "config", "user.name", "t")
        git(proj, "remote", "add", "origin", remote.absolutePath)
        File(proj, "a.txt").writeText("hello\n")

        val e = Engine(Storage(File(tmp, "data")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假", "http://127.0.0.1:9/v1", "sk", listOf(ModelInfo("m")))))
        // 测试里不走代理
        e.call(Command.SaveSettings(e.state.settings.copy(proxy = "")))
        val conv = e.call(Command.CodeCreate(proj.absolutePath, "p1", "m", CodeMode.MANUAL)).data

        val st = e.call(Command.CodeGit(conv, "repo")).data
        assertTrue("\"repo\":\"true\"" in st && "a.txt" in st, st)
        val r = e.call(Command.CodeGit(conv, "commit_push", "第一次上传"))
        assertTrue(r.ok, r.message + "\n" + r.data)
        assertTrue("第一次上传" in git(remote, "log", "--oneline", "main"), "远端收到了提交")
        // 再点一次：没有改动也不报错
        assertTrue(e.call(Command.CodeGit(conv, "commit", "")).ok)
        // 手机上只能看状态
        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        assertNotNull(com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeGit(conv, "push"), on).refusal)
        assertEquals(null, com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeGit(conv, "repo"), on).refusal)
    }

    /**
     * 2026-10-22（试用记账本时发现以前没远端只能去 GitHub 建仓库）：还不是仓库的能在本地建仓库并提交；
     * 有仓库、没远端的能只在本地提交；都不碰 GitHub、不加远端。手机上不许。
     */
    @Test
    fun commitLocallyWithoutRemote() = runBlocking<Unit> {
        val proj = File(tmp, "local").apply { mkdirs() }
        File(proj, "a.txt").writeText("hello\n")
        val e = Engine(Storage(File(tmp, "data2")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假", "http://127.0.0.1:9/v1", "sk", listOf(ModelInfo("m")))))
        e.call(Command.SaveSettings(e.state.settings.copy(proxy = "")))
        val conv = e.call(Command.CodeCreate(proj.absolutePath, "p1", "m", CodeMode.MANUAL)).data

        assertTrue("\"repo\":\"false\"" in e.call(Command.CodeGit(conv, "repo")).data)
        val r = e.call(Command.CodeGit(conv, "init", "第一次提交"))
        assertTrue(r.ok, r.message + "\n" + r.data)
        assertTrue("第一次提交" in git(proj, "log", "--oneline"), git(proj, "log", "--oneline"))
        assertEquals("", git(proj, "remote").trim(), "没加远端")

        File(proj, "b.txt").writeText("x\n")
        val st = e.call(Command.CodeGit(conv, "repo")).data
        assertTrue("\"remote\":\"\"" in st && "b.txt" in st, st)
        assertTrue(e.call(Command.CodeGit(conv, "commit", "第二次")).ok)
        assertTrue("第二次" in git(proj, "log", "--oneline"))

        val on = e.state.copy(settings = e.state.settings.copy(code = e.state.settings.code.copy(allowPhone = true)))
        assertNotNull(com.guixing.jixunying.engine.code.PhoneGate.check(Command.CodeGit(conv, "init"), on).refusal)
    }

    /** 2026-10-18 独立副本：git worktree 到 .claude/worktrees/，新分支；主仓库 status 干净；删会话清掉副本、分支留着。 */
    @Test
    fun worktreeSessionIsIsolated() = runBlocking<Unit> {
        val proj = File(tmp, "wt").apply { mkdirs() }
        git(proj, "init", "-b", "main")
        git(proj, "config", "user.email", "t@t")
        git(proj, "config", "user.name", "t")
        File(proj, "a.txt").writeText("v1\n")
        git(proj, "add", "-A"); git(proj, "commit", "-m", "init")
        val e = Engine(Storage(File(tmp, "data2")))
        e.codeRunner = com.guixing.jixunying.engine.code.CodeShell()
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假", "http://127.0.0.1:9/v1", "sk", listOf(ModelInfo("m")))))
        val r = e.call(Command.CodeCreate(proj.absolutePath, "p1", "m", CodeMode.MANUAL, worktree = true))
        assertTrue(r.ok, r.message)
        val spec = e.state.conversation(r.data)!!.code!!
        val wt = File(spec.folder)
        assertTrue(wt.absolutePath.replace('\\', '/').contains(".claude/worktrees/"), wt.absolutePath)
        assertEquals("v1", File(wt, "a.txt").readText().trim(), "副本里是仓库的内容（这台电脑 git 会把换行转成 CRLF）")
        assertTrue(spec.worktreeBranch.startsWith("jxy/"))
        File(wt, "a.txt").writeText("v2\n")
        assertEquals("v1", File(proj, "a.txt").readText().trim(), "改副本不影响原来的")
        assertEquals("", git(proj, "status", "--porcelain").trim(), "主仓库看不到副本")
        e.call(Command.DeleteConversation(r.data))
        assertTrue(!wt.exists(), "删会话清掉副本")
        assertTrue(spec.worktreeBranch in git(proj, "branch"), "分支留着")
        // 2026-10-22：原会话是「自动」档，独立副本也开成自动（以前写死成接受编辑）；手机发来的不开自动
        val auto = e.call(Command.CodeCreate(proj.absolutePath, "p1", "m", CodeMode.ACCEPT_EDITS, worktree = true, auto = true))
        assertTrue(auto.ok, auto.message)
        assertTrue(e.state.conversation(auto.data)!!.code!!.autoMode, "自动档跟过来了")
        e.call(Command.DeleteConversation(auto.data))
    }
}
