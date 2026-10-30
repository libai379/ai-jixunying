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
    /**
     * 2026-10-30 用户：手机上 MiniMax 做的视频（6.8～17MB）「打不开，也下载不了」，千问的（3MB 左右）能打开。
     * 真走公共中转对比：电脑上放一个 JXY_LIVE_BIG_MB（默认 10）MB 的文件，手机先按旧办法整个取（GetFile），再按新办法分段取。
     */
    @Test
    fun bigFileOverPublicBrokers() = runBlocking {
        if (System.getenv("JXY_LIVE") != "1") return@runBlocking
        val mb = System.getenv("JXY_LIVE_BIG_MB")?.toIntOrNull() ?: 10
        val dir = kotlin.io.path.createTempDirectory("jxy-live-big").toFile()
        val pc = Engine(Storage(File(dir, "pc")))
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, RelaySettings.DEFAULT_BROKERS, "实测电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(40_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(200) }
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "实测手机")
            assertTrue(hub.pair(pc.pairingCode()).isSuccess)
            val remote = hub.remote.value!!
            withTimeout(40_000) { while (remote.conn.value !is ConnState.Connected) delay(100) }
            val data = ByteArray(mb * 1024 * 1024) { (it * 31 % 251).toByte() }
            val att = com.guixing.jixunying.model.AppJson.decodeFromString(com.guixing.jixunying.model.Attachment.serializer(),
                pc.call(Command.UploadFile("实测视频.mp4", "video/mp4", java.util.Base64.getEncoder().encodeToString(data))).data)
            val t0 = System.currentTimeMillis()
            val old = remote.call(Command.GetFile(att.id))
            val oldOk = old.ok && runCatching { java.util.Base64.getDecoder().decode(old.data).contentEquals(data) }.getOrDefault(false)
            println("旧办法（整个取）${mb}MB：${if (oldOk) "成功" else "失败：" + old.message.take(80)}，用时 ${System.currentTimeMillis() - t0} ms")
            val t1 = System.currentTimeMillis()
            val got = remote.fileBytes(att.id)
            println("新办法（分段取）${mb}MB：${if (got != null && got.contentEquals(data)) "成功" else "失败"}，用时 ${System.currentTimeMillis() - t1} ms")
            assertContentEquals(data, got)
            hub.unpair()
        } finally {
            relay.stop()
            dir.deleteRecursively()
        }
    }

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
