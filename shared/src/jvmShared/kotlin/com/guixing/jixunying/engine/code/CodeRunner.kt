package com.guixing.jixunying.engine.code

import java.io.File

/**
 * Code 栏跑命令的接口（安全核心，写代码的 AI 只调用不改）。
 * 电脑上是 CodeShell（desktopMain，Main.kt 里设给 Engine.codeRunner）；手机上没有（手机不在本机跑 Code 栏）。
 */
interface CodeRunner {
    /**
     * 在 dir 里跑一条命令。超时或协程被取消（用户点「停止」）时杀掉整棵进程树。
     * @param shell "powershell"、"cmd"，或者外壳程序的完整路径（当成 bash 那样用「-lc 命令」调）
     * @param maxChars 输出最多留多少字（超了留开头三分之二、结尾三分之一）
     */
    suspend fun run(command: String, dir: File, shell: String = "powershell", timeoutSec: Int = 300, maxChars: Int = 30_000): CodeRunResult
}

class CodeRunResult(
    /** 进程退出码；超时或被停掉时是 null。 */
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    /** 超时 / 停止时杀掉的进程号（含子孙），测试看杀干净没有。 */
    val killed: List<Long> = emptyList(),
    val millis: Long = 0,
)

object CodeRunnerRules {
    /** 子进程能拿到的环境变量（大小写不敏感）。服务商的 Key、代理设置一律不给。 */
    private val ALLOWED = setOf(
        "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATH", "PATHEXT", "TEMP", "TMP",
        "USERPROFILE", "USERNAME", "USERDOMAIN", "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA",
        "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432", "COMMONPROGRAMFILES", "COMMONPROGRAMFILES(X86)",
        "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS", "COMPUTERNAME", "PSMODULEPATH", "LANG",
        "JAVA_HOME", "GRADLE_USER_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GOPATH", "CARGO_HOME", "RUSTUP_HOME",
        "NODE_PATH", "NVM_HOME", "NVM_SYMLINK", "PYTHONHOME", "VIRTUAL_ENV", "HOME",
    )
    /** 名字里带这些的，就算在白名单里也去掉。 */
    private val SECRET = Regex("KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL", RegexOption.IGNORE_CASE)

    fun allowedEnv(name: String): Boolean = name.uppercase() in ALLOWED && !SECRET.containsMatchIn(name)

    /** 留开头三分之二、结尾三分之一，写明省略了多少。返回（文字，截没截）。 */
    fun clip(s: String, max: Int, droppedBytes: Long = 0): Pair<String, Boolean> {
        if (s.length <= max && droppedBytes == 0L) return s to false
        if (s.length <= max) return (s + "\n……（后面还有约 $droppedBytes 字节没收）") to true
        val head = max * 2 / 3
        val tail = max - head
        val omitted = s.length - head - tail
        return (s.take(head) + "\n……（中间省略 $omitted 字" + (if (droppedBytes > 0) "，另有约 $droppedBytes 字节没收" else "") + "）……\n" + s.takeLast(tail)) to true
    }
}
