package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.DeviceSyncer
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Ledger
import com.guixing.jixunying.engine.NetLoad
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.USER_ID
import com.guixing.jixunying.model.UsageRecord
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 手机和电脑互相同步账本、微信对话（2026-10-27，engine/DeviceSync.kt）。 */
class DeviceSyncTest {
    private lateinit var dir: File
    private val engines = mutableListOf<Engine>()

    @BeforeTest fun setUp() { dir = kotlin.io.path.createTempDirectory("jxy-devsync").toFile() }
    @AfterTest fun tearDown() { engines.forEach { runCatching { it.close() } }; dir.deleteRecursively() }

    private val ch = "weixin:u1"

    /** 一台设备的数据：同一个 DeepSeek 账号（两边编号不同）、一位阿德、一个微信对话。 */
    private fun seed(root: File, pid: String, mid: String, convId: String, msgs: List<Pair<String, Long>>, usage: List<Long>) {
        root.mkdirs()
        val st = Storage(root)
        st.saveState(AppState(
            providers = listOf(ProviderConfig(pid, name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1", apiKey = "sk-same")),
            members = listOf(Member(mid, "阿德", providerId = pid, modelId = "deepseek-v4.1-flash")),
            conversations = listOf(Conversation(convId, "微信 小明", listOf(mid), createdAt = 1, updatedAt = 1, channel = ch)),
        ))
        st.saveMessages(convId, msgs.map { (id, at) ->
            Message(id, convId, if (id.startsWith("u")) Role.USER else Role.AI, if (id.startsWith("u")) USER_ID else mid, "内容 $id", createdAt = at)
        })
        val led = Ledger(File(root, "usage"))
        usage.forEach { at -> led.add(UsageRecord(at, "chat", pid, "DeepSeek", "deepseek", "deepseek-v4.1-flash", prompt = 100, completion = (at % 1000).toInt(), memberId = mid)) }
    }

    @Test
    fun ledgerAndWeixinBothWays() = runBlocking<Unit> {
        val mqttPort = ServerSocket(0).use { it.localPort }
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(Properties().apply {
            setProperty("port", mqttPort.toString()); setProperty("host", "127.0.0.1"); setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true"); setProperty("persistence_enabled", "false")
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val t = 1_800_000_000_000L
        seed(File(dir, "pc"), "p-pc", "m-pc", "c-pc", listOf("u1" to t + 1, "a1" to t + 2), listOf(t + 10, t + 11, t + 12))
        // 手机：电脑关着时接了微信，答了一轮；也花了钱（50 条，逼它分几批）
        seed(File(dir, "phone"), "p-ph", "m-ph", "c-ph", listOf("u2" to t + 100, "a2" to t + 101), (0 until 50).map { t + 200 + it })
        val pc = Engine(Storage(File(dir, "pc"))).also { engines += it }
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true).also { engines += it }
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            assertTrue(hub.pair(PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)).isSuccess)
            withTimeout(20_000) { while (hub.remote.value?.conn?.value !is ConnState.Connected) delay(100) }
            withTimeout(10_000) { while (hub.remote.value!!.store.state.value.conversations.none { it.channel == ch }) delay(100) }
            // 第一遍按流量计费的网：批次压到最小（20 条），50 条要分三批
            var metered = true
            val syncer = DeviceSyncer(hub, phone, "手机 测试", File(dir, "phone"), load = { NetLoad(-1, metered) }, pauseMs = 10)
            assertTrue(syncer.runOnce(hub.remote.value!!), hub.sync.value.error)

            val pcLed = Ledger(File(dir, "pc/usage")).since(0)
            val phLed = Ledger(File(dir, "phone/usage")).since(0)
            assertEquals(53, pcLed.size, "电脑上：自己 3 条 + 手机 50 条")
            assertEquals(53, phLed.size, "手机上：自己 50 条 + 电脑 3 条")
            assertTrue(pcLed.filter { it.origin == "手机 测试" }.all { it.providerId == "p-pc" && it.memberId == "m-pc" }, "同一个地址同一个 Key 对到电脑那家、成员按名字对")
            assertTrue(phLed.filter { it.origin.startsWith("电脑") }.all { it.providerId == "p-ph" }, "手机上记成手机那家")
            assertEquals(2, pc.call(Command.GetCosts("all")).let { com.guixing.jixunying.model.AppJson.decodeFromString(com.guixing.jixunying.model.CostReport.serializer(), it.data) }.byDevice.size,
                "花费页能按设备分开看")

            fun saved(who: String, conv: String) = Storage(File(dir, who)).loadMessages(conv)
            fun msgs(e: Engine, conv: String) = saved(if (e === pc) "pc" else "phone", conv).map { it.id }
            assertEquals(listOf("u1", "a1", "u2", "a2"), msgs(pc, "c-pc"), "电脑的微信对话里有了手机答的那轮，按时间排")
            assertEquals(listOf("u1", "a1", "u2", "a2"), msgs(phone, "c-ph"))
            assertEquals("m-pc", saved("pc", "c-pc").first { it.id == "a2" }.senderId, "手机的阿德对到电脑的阿德")

            // 再来一遍（模拟重连、正常网）：不多一条
            metered = false
            assertTrue(syncer.runOnce(hub.remote.value!!))
            assertEquals(53, Ledger(File(dir, "pc/usage")).since(0).size)
            assertEquals(53, Ledger(File(dir, "phone/usage")).since(0).size)
            assertEquals(4, msgs(pc, "c-pc").size)
            assertEquals("两边已经一样", hub.sync.value.summary)
        } finally {
            relay.stop(); broker.stopServer()
        }
    }
}
