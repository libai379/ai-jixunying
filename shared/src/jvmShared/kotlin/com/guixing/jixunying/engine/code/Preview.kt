package com.guixing.jixunying.engine.code

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 预览（2026-10-18，开发补齐第 3 样；Claude 桌面 App 的「Preview your app」：起开发服务器、在浏览器里看、改完自己截图验证）。
 * 我们没有内置浏览器窗格：用电脑自带的 Edge（或 Chrome）无界面模式给网址截一张图，交给能看图的模型看。
 * 找不到浏览器、截图失败都返回 null，调用的地方改成读网页文字。
 */
object Preview {
    /** 找浏览器：Edge 优先（Windows 10 自带），再找 Chrome。 */
    fun browser(): File? = listOfNotNull(
        System.getenv("ProgramFiles(x86)")?.let { File(it, "Microsoft/Edge/Application/msedge.exe") },
        System.getenv("ProgramFiles")?.let { File(it, "Microsoft/Edge/Application/msedge.exe") },
        System.getenv("ProgramFiles")?.let { File(it, "Google/Chrome/Application/chrome.exe") },
        System.getenv("ProgramFiles(x86)")?.let { File(it, "Google/Chrome/Application/chrome.exe") },
        System.getenv("LOCALAPPDATA")?.let { File(it, "Google/Chrome/Application/chrome.exe") },
    ).firstOrNull { it.isFile }

    /**
     * 给网址截图，存成 png 返回字节。width × height 是浏览器窗口大小。用一份单独的临时浏览器资料，不碰用户自己的浏览器。
     * 最多等 timeoutSec 秒。
     */
    fun screenshot(url: String, width: Int = 1280, height: Int = 800, timeoutSec: Long = 40): ByteArray? {
        val exe = browser() ?: return null
        val dir = kotlin.io.path.createTempDirectory("jxy-preview").toFile()
        return try {
            val out = File(dir, "shot.png")
            val p = ProcessBuilder(
                exe.absolutePath, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-first-run", "--no-default-browser-check",
                "--user-data-dir=${File(dir, "profile").absolutePath}", "--window-size=$width,$height",
                "--virtual-time-budget=5000", "--screenshot=${out.absolutePath}", url,
            ).redirectErrorStream(true).start()
            runCatching { p.inputStream.close() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
            out.takeIf { it.isFile && it.length() > 0 }?.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            dir.deleteRecursively()
        }
    }
}
