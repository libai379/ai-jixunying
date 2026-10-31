package com.guixing.jixunying

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/**
 * 同一份数据只许一个 AI集训营 开着（2026-10-27 用户：「电脑能开多个」）。
 * 数据目录里的 app.lock 用文件锁占住（进程退出、崩了系统都会放开，不会留死锁）；
 * 占住的那个在 127.0.0.1 上听一个空闲端口，端口号写进 app.port。第二个打开时锁不到，就连那个端口说一声「show」再退出。
 * 说「quit」是真退出（点叉现在只藏到托盘，装新版前用它正常关掉：停中转、微信、后台命令再退）。
 */
object SingleInstance {
    /** 拿到了返回锁（要一直拿着，别让它被回收）；已经有一个开着返回 null。 */
    fun claim(root: File, onQuit: () -> Unit = {}, onShow: () -> Unit): FileLock? {
        root.mkdirs()
        val raf = RandomAccessFile(File(root, "app.lock"), "rw")
        // 拿锁出错（不是「被别的占着」）要记下来：以前一律当成已经开着一个、悄悄退出，看不出真正的原因（2026-10-30 复查时补）
        val lock = try { raf.channel.tryLock() } catch (e: Exception) { lockError = e.toString(); null }
        val portFile = File(root, "app.port")
        if (lock == null) {
            raf.close()
            runCatching {
                val port = portFile.readText().trim().toInt()
                Socket(InetAddress.getLoopbackAddress(), port).use { s -> s.soTimeout = 2000; s.getOutputStream().write("show\n".toByteArray()) }
            }
            return null
        }
        runCatching {
            val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            Thread({
                while (true) {
                    val c = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        c.use { s ->
                            s.soTimeout = 2000
                            when (s.getInputStream().bufferedReader().readLine()?.trim()) {
                                "show" -> onShow()
                                "quit" -> onQuit()
                            }
                        }
                    }
                }
            }, "jxy-single").apply { isDaemon = true }.start()
        }
        held = lock
        return lock
    }

    @Volatile private var held: FileLock? = null

    /** 上一次 claim 拿锁时出的错（null = 没出错，拿不到就是真有一个开着）。 */
    @Volatile var lockError: String? = null
        private set
}
