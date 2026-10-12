package com.guixing.jixunying.engine.code

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 电脑上的 CodeRunner（docs/Code栏设计说明.md 第 4 节第 2、3 条，安全核心，写代码的 AI 只调用不改）。
 * - 工作目录固定在项目文件夹；外壳 powershell（默认）/ cmd / 自己指定的程序；输出按 UTF-8 读。
 * - 超时、或者协程被取消（用户点「停止」）：先杀子孙进程再杀它自己。
 * - 环境变量只给 CodeRunnerRules.allowedEnv 白名单里的。
 * 放在 desktopMain：ProcessHandle 安卓 8.0 上没有，手机也不在本机跑命令。
 * @param envSource 测试用：换掉「本机的环境变量」
 */
class CodeShell(private val envSource: () -> Map<String, String> = System::getenv) : CodeRunner {

    override suspend fun run(command: String, dir: File, shell: String, timeoutSec: Int, maxChars: Int): CodeRunResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(commandLine(shell, command)).directory(dir).redirectErrorStream(true)
            val env = pb.environment()
            val source = envSource()
            env.clear()
            source.forEach { (k, v) -> if (CodeRunnerRules.allowedEnv(k)) env[k] = v }
            val t0 = System.currentTimeMillis()
            val p = pb.start()
            runCatching { p.outputStream.close() }
            val buf = ByteArrayOutputStream()
            var dropped = 0L
            val reader = Thread {
                runCatching {
                    val b = ByteArray(8192)
                    p.inputStream.use { input ->
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            // 最多收 4MB 原始字节，再多的只计数（省得撑爆内存）
                            val room = MAX_BYTES - buf.size()
                            if (room > 0) buf.write(b, 0, minOf(n, room))
                            if (n > room) dropped += (n - maxOf(room, 0))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val finished = runInterruptible { p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS) }
                val killed = if (finished) emptyList() else killTree(p)
                reader.join(3_000)
                val (text, cut) = CodeRunnerRules.clip(buf.toString(charsetOf(shell)), maxChars, dropped)
                CodeRunResult(if (finished) p.exitValue() else null, text, timedOut = !finished, truncated = cut, killed = killed,
                    millis = System.currentTimeMillis() - t0)
            } catch (e: CancellationException) {
                killTree(p)
                throw e
            }
        }

    private fun commandLine(shell: String, command: String): List<String> = when (shell.trim().lowercase()) {
        // Windows 自带的 PowerShell 5.1 读没有 BOM 的文件默认按系统编码（中文 Windows 是 GBK），
        // 我们写的 UTF-8 文件用 Get-Content 一看全是乱码（10-12 真模型测试里 MiniMax、小米都踩了，以为文件坏了）。
        // 只把「读」的几个命令默认改成 UTF-8；写文件的不改（5.1 的 -Encoding UTF8 会加 BOM）。
        "", "powershell" -> listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
            "[Console]::OutputEncoding = [Text.UTF8Encoding]::new(\$false); \$OutputEncoding = [Text.UTF8Encoding]::new(\$false); " +
                "\$PSDefaultParameterValues['Get-Content:Encoding'] = 'UTF8'; \$PSDefaultParameterValues['Select-String:Encoding'] = 'UTF8'; " +
                "\$PSDefaultParameterValues['Import-Csv:Encoding'] = 'UTF8'; $command",
        )
        // cmd：没有控制台窗口时 chcp 65001 不生效（10-12 测试里中文成了乱码），输出按系统本地编码（中文 Windows 是 GBK）读
        "cmd" -> listOf("cmd.exe", "/d", "/s", "/c", command)
        else -> listOf(shell.trim(), "-lc", command)
    }

    private fun charsetOf(shell: String): java.nio.charset.Charset =
        if (shell.trim().lowercase() == "cmd") {
            runCatching { java.nio.charset.Charset.forName(System.getProperty("native.encoding") ?: System.getProperty("sun.jnu.encoding")) }
                .getOrDefault(Charsets.UTF_8)
        } else Charsets.UTF_8

    /** 先杀子孙再杀自己；返回杀掉的进程号。 */
    private fun killTree(p: Process): List<Long> {
        val h = p.toHandle()
        val all = runCatching { h.descendants().toList() }.getOrDefault(emptyList()) + h
        all.forEach { runCatching { it.destroyForcibly() } }
        runCatching { p.waitFor(5, TimeUnit.SECONDS) }
        return all.map { it.pid() }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
