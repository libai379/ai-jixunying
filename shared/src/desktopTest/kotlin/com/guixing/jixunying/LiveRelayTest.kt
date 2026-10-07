package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 真走公共中转（EMQX 上海 + 美国）的联机检查，平时跳过；设环境变量 JXY_LIVE=1 才跑。 */
class LiveRelayTest {
    @Test
    fun pairAndTalkOverPublicBrokers() = runBlocking {
        if (System.getenv("JXY_LIVE") != "1") return@runBlocking
        val dir = kotlin.io.path.createTempDirectory("jxy-live").toFile()
        val pc = Engine(Storage(File(dir, "pc")))
        val brokers = System.getenv("JXY_BROKERS")?.split(",")?.map { it.trim() } ?: RelaySettings.DEFAULT_BROKERS
        println("中转：$brokers")
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, brokers, "实测电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            val t0 = System.currentTimeMillis()
            withTimeout(40_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(200) }
            println("电脑连上中转：${pc.state.relayStatus}，用时 ${System.currentTimeMillis() - t0} ms")
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "实测手机")
            val t1 = System.currentTimeMillis()
            val r = hub.pair(pc.pairingCode())
            assertTrue(r.isSuccess, r.exceptionOrNull()?.message)
            println("配对用时 ${System.currentTimeMillis() - t1} ms")
            val remote = hub.remote.value!!
            val t2 = System.currentTimeMillis()
            withTimeout(40_000) { while (remote.conn.value !is ConnState.Connected) delay(100) }
            println("手机连上电脑用时 ${System.currentTimeMillis() - t2} ms")
            val t3 = System.currentTimeMillis()
            val conv = remote.call(Command.CreateConversation(emptyList(), "实测"))
            assertTrue(conv.ok, conv.message)
            println("一次指令往返 ${System.currentTimeMillis() - t3} ms")
            val data = ByteArray(400_000) { (it % 97).toByte() }
            val t4 = System.currentTimeMillis()
            val att = remote.upload("实测.bin", "application/octet-stream", data)
            assertNotNull(att)
            assertContentEquals(data, remote.fileBytes(att.id))
            println("400KB 上传再下载 ${System.currentTimeMillis() - t4} ms")
            hub.unpair()
        } finally {
            relay.stop()
            dir.deleteRecursively()
        }
    }
}
