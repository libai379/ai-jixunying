package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 等太久没回不说成「网络连不上」（2026-11-05 晚 qwen-image-3.0-pro 等满 180 秒，界面和 AI 都说网络连不上，AI 还跟用户说「没扣钱」）。
 */
class TimeoutWordingTest {
    @Test
    fun slowReplyIsNotCalledNetworkDown() {
        val e = Engine(Storage(Files.createTempDirectory("jxy-timeout").toFile()))
        val socket = java.net.SocketTimeoutException(
            "Socket timeout has expired [url=https://maas.qianwenaiapi.com/api/v1/services/aigc/multimodal-generation/generation, socket_timeout=180000] ms")
        val s = e.friendlyError(socket)
        assertTrue("等了 3 分钟" in s && "一个字都没回" in s, s)
        assertFalse("网络连不上" in s, s)
        // paintAuto 试了几个都没成时包一层，原来那句照样认得
        val wrapped = e.friendlyError(IllegalStateException("试了 a、b，都没画成。第一个的错误：${e.friendlyError(socket)}", socket))
        assertFalse("网络连不上" in wrapped, wrapped)
        // 真连不上的照旧
        assertTrue("网络连不上" in e.friendlyError(java.net.ConnectException("Connection refused")))
    }
}
