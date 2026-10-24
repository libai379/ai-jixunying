package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.MemorySync
import com.guixing.jixunying.engine.MemorySyncer
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MemoryBundle
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.MemoryTombstone
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
import kotlin.test.assertTrue

/** 手机和电脑的长期记忆互相同步（2026-10-24，engine/MemorySync.kt）。 */
class MemorySyncTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = kotlin.io.path.createTempDirectory("jxy-memsync").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun m(id: String, text: String, at: Long) = MemoryItem(id, text, createdAt = at, updatedAt = at)

    @Test
    fun mergeRules() {
        val now = 1_000_000L
        val pc = MemoryBundle(listOf(m("a", "喜欢喝茶", 10), m("b", "住北京", 10), m("d", "旧的", 10)), listOf(MemoryTombstone("x", 50)))
        val phone = MemoryBundle(listOf(m("a", "喜欢喝咖啡", 20), m("c", "养了一只猫", 15), m("x", "被电脑删掉的", 40), m("d2", "旧的", 30)),
            listOf(MemoryTombstone("b", 30)))
        val one = MemorySync.merge(pc, phone, now)
        val two = MemorySync.merge(phone, pc, now)
        assertTrue(MemorySync.same(one, two), "谁先谁后结果一样")
        val byId = one.items.associateBy { it.id }
        assertEquals("喜欢喝咖啡", byId["a"]?.text, "同一条以后改的为准")
        assertTrue("b" !in byId, "手机删了，电脑那条也没了")
        assertTrue("x" !in byId, "电脑删得比手机最后一次改晚：删")
        assertEquals("养了一只猫", byId["c"]?.text)
        assertTrue("d" !in byId && byId["d2"] != null, "一模一样的两条留改得晚的")
        assertTrue(one.deleted.any { it.id == "d" })
        // 删了以后另一边又改过：留着改过的
        val revived = MemorySync.merge(MemoryBundle(deleted = listOf(MemoryTombstone("c", 10))), MemoryBundle(listOf(m("c", "又改了", 20))), now)
        assertEquals(listOf("c"), revived.items.map { it.id })
        // 删除记录 90 天后清掉
        assertTrue(MemorySync.merge(MemoryBundle(deleted = listOf(MemoryTombstone("z", 0))), MemoryBundle(), MemorySync.KEEP_DELETED_MS + 1).deleted.isEmpty())
    }

    @Test
    fun phoneAndPcSyncThroughRelay() = runBlocking<Unit> {
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
            pc.call(Command.SaveMemory(MemoryItem("p1", "电脑上记的：喜欢喝茶")))
            pc.call(Command.SaveMemory(MemoryItem("p2", "电脑上记的：要删的这条")))
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            phone.call(Command.SaveMemory(MemoryItem("m1", "手机上记的：养了一只猫")))
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            assertTrue(hub.pair(PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)).isSuccess)
            withTimeout(20_000) { while (hub.remote.value?.conn?.value !is ConnState.Connected) delay(100) }
            val syncer = MemorySyncer(hub, phone)
            syncer.start()
            suspend fun converge(what: String, ok: () -> Boolean) = withTimeout(30_000) { while (!ok()) delay(100) }.also { assertTrue(ok(), what) }
            fun ids(e: Engine) = e.state.memories.map { it.id }.toSet()

            converge("第一次连上两边都有三条") { ids(pc) == setOf("p1", "p2", "m1") && ids(phone) == setOf("p1", "p2", "m1") }
            // 电脑上删、手机上改：都跟过去
            pc.call(Command.DeleteMemory("p2"))
            delay(5)
            phone.call(Command.SaveMemory(phone.state.memories.first { it.id == "p1" }.copy(text = "手机上改的：喜欢喝咖啡")))
            converge("删和改都同步") {
                ids(pc) == setOf("p1", "m1") && ids(phone) == setOf("p1", "m1") &&
                    pc.state.memories.first { it.id == "p1" }.text == "手机上改的：喜欢喝咖啡"
            }
            // 手机上删：电脑也删
            phone.call(Command.DeleteMemory("m1"))
            converge("手机删的电脑也删") { ids(pc) == setOf("p1") && ids(phone) == setOf("p1") }
            assertEquals("", syncer.lastError)
        } finally {
            relay.stop(); broker.stopServer()
        }
    }
}
