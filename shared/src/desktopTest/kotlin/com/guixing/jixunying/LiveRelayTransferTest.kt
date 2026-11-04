package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * 经真的公共中转从「电脑」取一个大文件，看要多久、会不会卡（2026-11-04 用户：「连华为手机更新，电脑传输环节卡住了怎么回事」）。
 * 只在设了 JXY_LIVE_RELAY=工作文件夹 时跑（不放 C 盘）；JXY_RELAY_MB 是文件多大（默认 26，和 1.17.1 安卓安装包差不多）。
 * 电脑和手机两头都是这台电脑上新建的（新的 hostId，和用户的电脑、手机不相干），只用公共中转，不花钱。
 */
class LiveRelayTransferTest {
    @Test
    fun bigFileOverPublicRelay() = runBlocking {
        val root = File(System.getenv("JXY_LIVE_RELAY") ?: return@runBlocking).apply { mkdirs() }
        val mb = System.getenv("JXY_RELAY_MB")?.toIntOrNull() ?: 26
        val pcDir = File(root, "pc-" + System.currentTimeMillis())
        val pc = Engine(Storage(pcDir), isPhone = false)
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, RelaySettings.DEFAULT_BROKERS, "测试电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        val t00 = System.currentTimeMillis()
        try {
            withTimeout(90_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(200) }
            println("电脑连上中转：${pc.state.relayStatus}（${(System.currentTimeMillis() - t00) / 1000} 秒）")
            val code = PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)
            val phone = Engine(Storage(File(root, "phone-" + System.currentTimeMillis())), isPhone = true)
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            hub.pair(code).getOrThrow()
            val remote = hub.remote.value!!
            withTimeout(90_000) { while (remote.conn.value !is ConnState.Connected) delay(200) }
            println("手机连上电脑（${(System.currentTimeMillis() - t00) / 1000} 秒）")
            val bytes = Random(1).nextBytes(mb * 1024 * 1024)   // 随机数据压不小，和安装包一样
            Storage(pcDir).putFile(Attachment("big1", "big.bin", "application/octet-stream", bytes.size.toLong(), AttachmentKind.DOCUMENT), bytes, null)
            val t0 = System.currentTimeMillis()
            var lastPct = -1
            val watch = launch {
                remote.downloads.collect { m ->
                    m["big1"]?.let { p -> val pct = (p * 100).toInt(); if (pct / 5 != lastPct / 5) { lastPct = pct; println("  取了 $pct%，${(System.currentTimeMillis() - t0) / 1000} 秒") } }
                }
            }
            val got = remote.fileBytes("big1")
            watch.cancel()
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            println("取完：${got?.size ?: "没取到"} 字节，$secs 秒，" + (got?.let { "内容一致=${it.contentEquals(bytes)}，平均 ${"%.0f".format(it.size / 1024.0 / secs)} KB/秒" } ?: ""))
        } finally {
            relay.stop()
            pcDir.parentFile.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
