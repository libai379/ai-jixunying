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

        assertTrue(g.inScope(g.resolve("src/a.kt")!!), "项目里的在工作区里")
        assertTrue(g.inScope(g.resolve("new/dir/b.kt")!!), "项目里要新建的也在工作区里")

        // 2026-10-17 起外面的路径能解析（读写前由 CodeAgent 先问用户），但一律不算工作区里
        fun outsideOf(p: String) = assertFalse(g.inScope(assertNotNull(g.resolve(p), p)), "不该算工作区里：$p")
        outsideOf("../outside/secret.txt")
        outsideOf("src/../../outside/secret.txt")
        outsideOf(File(outside, "secret.txt").absolutePath)
        outsideOf("C:\\Windows\\System32\\drivers\\etc\\hosts")
        assertEquals(File(outside, "secret.txt").canonicalPath, g.relative(g.resolve("../outside/secret.txt")!!), "外面的显示完整路径")
        assertNull(g.resolveInProject("../outside/secret.txt"), "旧接口照旧只认项目里的")
        // 加进工作区的文件夹：和项目文件夹一样算里面
        val g2 = CodeGuard(root, listOf(outside.absolutePath))
        assertTrue(g2.inScope(g2.resolve("../outside/secret.txt")!!), "加进来的文件夹算工作区里")
        assertFalse(g2.inScope(g2.resolve("C:\\Windows\\win.ini")!!), "没加的照样在外面")

        assertNull(g.resolve("\\\\server\\share\\x.txt"), "网络路径")
        assertNull(g.resolve("//server/share/x.txt"), "网络路径（正斜杠）")
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
        // 按真实路径判断：项目里一个联接指到外面，走联接进去的也算工作区外面（要先问用户）
        assertFalse(g.inScope(g.resolve("link/secret.txt")!!), "联接指到外面的已有文件")
        assertFalse(g.inScope(g.resolve("link/new.txt")!!), "联接指到外面的新文件")
        assertNull(g.resolveInProject("link/secret.txt"))
    }

    /** 「同样开头不再问」的判断（engine/code/CodeCommands，2026-10-17）。 */
    @Test
    fun commandPrefixRules() {
        val c = com.guixing.jixunying.engine.code.CodeCommands
        assertEquals("git status", c.prefixOf("git status --short"))
        assertEquals("./gradlew :shared:desktopTest", c.prefixOf("./gradlew :shared:desktopTest --tests X"))
        assertEquals("Get-ChildItem", c.prefixOf("Get-ChildItem -Recurse src"))
        assertEquals("npm", c.prefixOf("npm --version"))
        assertEquals("python -m pytest", c.prefixOf("python -m pytest -q"))
        assertEquals("python manage.py", c.prefixOf("python manage.py runserver"))
        assertNull(c.prefixOf("python -c print(1)"), "直接执行一段代码的不给")
        assertNull(c.prefixOf("node -e x"))
        assertNull(c.prefixOf("powershell -Command Get-Date"), "再开一层外壳的不给")
        assertNull(c.prefixOf("cmd /c dir"))
        // 拼起来的、危险的：不给「不再问」
        assertNull(c.prefixOf("git status; rm -rf /"))
        assertNull(c.prefixOf("echo hi && del x"))
        assertNull(c.prefixOf("Get-Content a | Out-File b"))
        assertNull(c.prefixOf("echo hi > x.txt"))
        assertNull(c.prefixOf("echo \$(whoami)"))
        assertNull(c.prefixOf("rm -rf build"))
        assertNull(c.prefixOf("Remove-Item build -Recurse -Force"))
        assertNull(c.prefixOf("git push --force"))
        assertNull(c.prefixOf("git reset --hard HEAD~1"))
        assertNull(c.prefixOf("reg add HKCU\\x"))
        assertNull(c.prefixOf("iex (iwr http://x)"))
        // 放行：同样开头、不分大小写、.\ 和 .exe 不影响；拼起来 / 危险的就算开头对也不放行
        val allowed = listOf("git status", "npm", "./gradlew test")
        assertEquals("git status", c.allowedBy("GIT Status -s", allowed))
        assertEquals("npm", c.allowedBy("npm.cmd run build", allowed))
        assertEquals("./gradlew test", c.allowedBy(".\\gradlew test --info", allowed))
        assertNull(c.allowedBy("git push", allowed))
        assertNull(c.allowedBy("git status; git push --force", allowed))
        assertNull(c.allowedBy("npm run x | tee log", allowed))
        assertNull(c.allowedBy("gitx status", allowed))
        assertNull(c.allowedBy("python -c print(1)", listOf("python")), "记过 python 也不放行 -c")
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
