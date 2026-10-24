package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.MemorySyncer
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.RelaySettings
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 一台电脑同时连好几台手机（2026-10-24 用户：「一个桌面端可以连接多个移动端」）。走本地 MQTT 模拟的中转。 */
class MultiPhoneTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = kotlin.io.path.createTempDirectory("jxy-multiphone").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun onePcTwoPhones() = runBlocking<Unit> {
        val mqttPort = ServerSocket(0).use { it.localPort }
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(Properties().apply {
            setProperty("port", mqttPort.toString()); setProperty("host", "127.0.0.1"); setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true"); setProperty("persistence_enabled", "false")
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val pc = Engine(Storage(File(dir, "pc")))
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            fun code() = PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)

            val phoneA = Engine(Storage(File(dir, "a")), isPhone = true)
            val hubA = Hub(phoneA, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "手机甲")
            val first = code()
            assertTrue(hubA.pair(first).isSuccess)
            val second = code()
            assertNotEquals(first.p, second.p, "配对一台后二维码换新的")
            val phoneB = Engine(Storage(File(dir, "b")), isPhone = true)
            val hubB = Hub(phoneB, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "手机乙")
            assertTrue(pairWithHost(first, "拿旧码的").isFailure, "旧二维码不能再配第二台")
            assertTrue(hubB.pair(second).isSuccess)
            assertEquals(setOf("手机甲", "手机乙"), pc.state.devices.map { it.name }.toSet())

            val a = hubA.remote.value!!
            val b = hubB.remote.value!!
            withTimeout(20_000) { while (a.conn.value !is ConnState.Connected || b.conn.value !is ConnState.Connected) delay(100) }
            // 两台同时连着：都看得到电脑的状态，都能让电脑干活
            withTimeout(10_000) { while (a.store.state.value.hostId.isEmpty() || b.store.state.value.hostId.isEmpty()) delay(50) }
            assertTrue(a.call(Command.SaveMemory(MemoryItem("ma", "手机甲记的"))).ok)
            assertTrue(b.call(Command.SaveMemory(MemoryItem("mb", "手机乙记的"))).ok)
            withTimeout(10_000) { while (pc.state.memories.size < 2) delay(50) }
            // 电脑上的变化两台都收到
            withTimeout(10_000) { while (a.store.state.value.memories.size < 2 || b.store.state.value.memories.size < 2) delay(50) }
            // 联机页：两台都显示在线
            val now = System.currentTimeMillis()
            assertTrue(pc.state.devices.all { now - it.lastSeen < 90_000 }, "${pc.state.devices}")

            // 记忆同步：两台手机经电脑互相同步
            MemorySyncer(hubA, phoneA).start()
            MemorySyncer(hubB, phoneB).start()
            phoneA.call(Command.SaveMemory(MemoryItem("la", "只在手机甲本机记的")))
            withTimeout(30_000) { while (phoneB.state.memories.none { it.id == "la" }) delay(100) }

            // 一台手机不能踢掉、改名另一台，也不能换二维码
            val idB = pc.state.devices.first { it.name == "手机乙" }.id
            val kick = a.call(Command.RemoveDevice(idB))
            assertFalse(kick.ok, kick.message)
            assertFalse(a.call(Command.RenameDevice(idB, "被改了")).ok)
            assertFalse(a.call(Command.NewPairingCode).ok)
            assertEquals(2, pc.state.devices.size)
            // 电脑上：改名、取消一台，另一台不受影响
            assertTrue(pc.call(Command.RenameDevice(idB, "妈妈的手机")).ok)
            assertEquals("妈妈的手机", pc.state.devices.first { it.id == idB }.name)
            pc.call(Command.RemoveDevice(idB))
            assertEquals(listOf("手机甲"), pc.state.devices.map { it.name })
            assertTrue(a.call(Command.SaveMemory(MemoryItem("ma2", "取消别人以后照样能用"))).ok)
        } finally {
            relay.stop(); broker.stopServer()
        }
    }
}
