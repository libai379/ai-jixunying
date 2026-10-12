package com.guixing.jixunying

import com.guixing.jixunying.engine.code.CodeBackup
import com.guixing.jixunying.engine.code.CodeGuard
import com.guixing.jixunying.engine.code.CodeRunnerRules
import com.guixing.jixunying.engine.code.CodeShell
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Code 栏的安全核心（docs/Code栏设计说明.md 第 4 节、第 9 节第 1、4、5、9 条）。 */
class CodeSafetyTest {
    private val tmp = kotlin.io.path.createTempDirectory("jxy-code-safety").toFile()
    private val root = File(tmp, "proj").apply { mkdirs() }
    private val outside = File(tmp, "outside").apply { mkdirs(); File(this, "secret.txt").writeText("别人的文件") }

    @AfterTest
    fun cleanup() {
        // 先拆目录联接，免得 deleteRecursively 顺着联接删到外面
        File(root, "link").let { if (it.exists()) runCatching { java.nio.file.Files.delete(it.toPath()) } }
        tmp.deleteRecursively()
    }

    @Test
    fun guardKeepsPathsInsideProject() {
        File(root, "src").mkdirs()
        File(root, "src/a.kt").writeText("fun a() {}")
        val g = CodeGuard(root)
        assertEquals(File(root, "src/a.kt").canonicalPath, g.resolve("src/a.kt")!!.canonicalPath)
        assertEquals("src/a.kt", g.relative(g.resolve("src\\a.kt")!!))
        assertNotNull(g.resolve("new/dir/b.kt"), "还不存在的文件（要新建的）可以")
        assertNotNull(g.resolve(File(root, "src/a.kt").absolutePath), "项目里的绝对路径可以")
        assertNotNull(g.resolve("src/../src/a.kt"), "绕一圈还在里面的可以")

        assertNull(g.resolve("../outside/secret.txt"), "..")
        assertNull(g.resolve("src/../../outside/secret.txt"), "绕出去")
        assertNull(g.resolve(File(outside, "secret.txt").absolutePath), "外面的绝对路径")
        assertNull(g.resolve("C:\\Windows\\System32\\drivers\\etc\\hosts"), "别的盘 / 系统文件")
        assertNull(g.resolve("\\\\server\\share\\x.txt"), "网络路径")
        assertNull(g.resolve("src/a.kt:evil"), "备用数据流")
        assertNull(g.resolve("CON"), "设备名")
        assertNull(g.resolve("src/NUL.txt"), "设备名加扩展名")
        assertNull(g.resolve(""), "空")
        assertNull(g.resolve("a\u0000b"), "空字符")
        assertNull(g.resolve("a<b>.txt"), "非法字符")
    }

    @Test
    fun guardRejectsJunctionPointingOutside() {
        // 目录联接（mklink /J）不用管理员权限，最像「项目里放个链接指到外面」
        val p = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", File(root, "link").absolutePath, outside.absolutePath).redirectErrorStream(true).start()
        p.waitFor()
        if (!File(root, "link").exists()) { println("建不了目录联接，跳过：" + p.inputStream.readBytes().toString(Charsets.UTF_8)); return }
        val g = CodeGuard(root)
        assertNull(g.resolve("link/secret.txt"), "联接指到外面的已有文件")
        assertNull(g.resolve("link/new.txt"), "联接指到外面的新文件")
    }

    @Test
    fun shellRunsInProjectWithUtf8AndExitCode() = runBlocking {
        val sh = CodeShell()
        val r = sh.run("Write-Output '你好，世界'; (Get-Location).Path", root, timeoutSec = 60)
        assertEquals(0, r.exitCode, r.output)
        assertTrue("你好，世界" in r.output, r.output)
        assertTrue(root.canonicalPath.lowercase() in r.output.lowercase(), "工作目录是项目文件夹：" + r.output)
        assertEquals(3, sh.run("exit 3", root, timeoutSec = 60).exitCode)
        val c = sh.run("echo 中文 cmd", root, shell = "cmd", timeoutSec = 60)
        assertTrue("中文 cmd" in c.output, c.output)
        // 没有 BOM 的 UTF-8 文件：PowerShell 5.1 默认按 GBK 读成乱码（10-12 真模型测试踩到的），现在读的命令默认 UTF-8
        File(root, "u8.txt").writeText("你好 world\n", Charsets.UTF_8)
        val g = sh.run("Get-Content u8.txt; Select-String -Path u8.txt -Pattern 'world' | ForEach-Object { \$_.Line }", root, timeoutSec = 60)
        assertTrue(g.output.lines().count { "你好 world" in it } >= 2, g.output)
    }

    @Test
    fun shellHidesKeysAndProxies() = runBlocking {
        // 真实情况：本机全部环境变量经白名单过滤；把假的 Key、代理混进去
        val sh = CodeShell {
            System.getenv().filterKeys { !it.equals("JAVA_HOME", true) } + mapOf(
                "DEEPSEEK_API_KEY" to "sk-secret-123", "HTTPS_PROXY" to "http://127.0.0.1:9", "GITHUB_TOKEN" to "ghp_x",
                "JAVA_HOME" to "C:\\jdk")
        }
        val r = sh.run("Write-Output \"[\$env:DEEPSEEK_API_KEY][\$env:HTTPS_PROXY][\$env:GITHUB_TOKEN][\$env:JAVA_HOME]\"", root, timeoutSec = 60)
        assertTrue("[][][][C:\\jdk]" in r.output, r.output)
        assertFalse(CodeRunnerRules.allowedEnv("ANTHROPIC_API_KEY"))
        assertFalse(CodeRunnerRules.allowedEnv("HTTP_PROXY"))
        assertTrue(CodeRunnerRules.allowedEnv("Path"))
    }

    @Test
    fun timeoutKillsWholeTree() = runBlocking {
        val r = CodeShell().run("ping -n 30 127.0.0.1", root, timeoutSec = 2)
        assertTrue(r.timedOut)
        assertNull(r.exitCode)
        assertTrue(r.millis < 15_000, "超时就停：${r.millis} 毫秒")
        assertTrue(r.killed.size >= 2, "powershell 和 ping 都杀了：${r.killed}")
        r.killed.forEach { pid -> assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "进程 $pid 还活着") }
    }

    @Test
    fun stopKillsCommand() = runBlocking {
        val marker = File(root, "after.txt")
        val job = async { CodeShell().run("Start-Sleep 3; Set-Content after.txt done", root, timeoutSec = 60) }
        delay(1_000)
        job.cancel()
        delay(4_500)
        assertFalse(marker.exists(), "点了停止，命令后面那半没再跑")
    }

    @Test
    fun longOutputClipped() = runBlocking {
        val r = CodeShell().run("1..3000 | ForEach-Object { 'line ' + \$_ + ' ' + ('x' * 30) }", root, timeoutSec = 60, maxChars = 2_000)
        assertTrue(r.truncated)
        assertTrue("中间省略" in r.output, r.output.take(200))
        assertTrue(r.output.startsWith("line 1 "), r.output.take(50))
        assertTrue("line 3000" in r.output, "结尾留着")
        val (s, cut) = CodeRunnerRules.clip("短", 10)
        assertEquals("短", s); assertFalse(cut)
    }

    @Test
    fun backupRestores() {
        val b = CodeBackup(File(tmp, "backup"))
        val f = File(root, "a.txt").apply { writeText("原来的") }
        assertFalse(b.save("c1", f))
        f.writeText("改过的")
        assertFalse(b.save("c1", f), "同一个编号不覆盖")
        assertTrue(b.restore("c1", f))
        assertEquals("原来的", f.readText())

        val n = File(root, "new/b.txt")
        assertTrue(b.save("c2", n), "原来没有的文件")
        n.parentFile.mkdirs(); n.writeText("新建的")
        assertTrue(b.restore("c2", n))
        assertFalse(n.exists(), "还原 = 删掉")
        assertNull(b.original("c2"))
        assertFalse(b.restore("没有这个", f))
        assertFailsWith<IllegalArgumentException> { b.save("../x", f) }
    }
}
