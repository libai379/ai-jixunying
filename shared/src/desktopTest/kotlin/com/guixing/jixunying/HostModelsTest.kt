package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.HostChatCodec
import com.guixing.jixunying.engine.HostModels
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 手机借电脑本机的模型（2026-10-24，engine/HostModels.kt）。 */
class HostModelsTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() { dir = kotlin.io.path.createTempDirectory("jxy-hostmodels").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    @Test
    fun loopback() {
        assertTrue(HostChatCodec.isLoopback("http://127.0.0.1:11434/v1"))
        assertTrue(HostChatCodec.isLoopback("http://localhost:11434/v1"))
        assertFalse(HostChatCodec.isLoopback("https://api.deepseek.com/v1"))
        assertFalse(HostChatCodec.isLoopback("http://127.0.0.1.evil.com/v1"))
    }

    @Test
    fun phoneUsesPcLocalModel() = runBlocking<Unit> {
        // 假的 Ollama：只在电脑这边的 127.0.0.1 上
        val ollamaPort = ServerSocket(0).use { it.localPort }
        val asked = AtomicInteger()
        val ollama = embeddedServer(CIO, port = ollamaPort, host = "127.0.0.1") {
            routing {
                post("/v1/chat/completions") {
                    call.receiveText()
                    asked.incrementAndGet()
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        // 两截中间停一下：手机应该先收到第一截（一个字一个字出），不是等整段
                        write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"我是电脑上的\"}}]}\n\n")
                        flush()
                        Thread.sleep(600)
                        write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"千问\"}}]}\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }.start(wait = false)
        val mqttPort = ServerSocket(0).use { it.localPort }
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(Properties().apply {
            setProperty("port", mqttPort.toString()); setProperty("host", "127.0.0.1"); setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true"); setProperty("persistence_enabled", "false")
            setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val pc = Engine(Storage(File(dir, "pc")))
        pc.call(Command.SaveSettings(pc.state.settings.copy(relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        pc.call(Command.SaveProvider(ProviderConfig("ollama1", "ollama", "Ollama（本机）", "http://127.0.0.1:$ollamaPort/v1",
            models = listOf(ModelInfo("qwen3.6:35b-a3b-coding")))))
        pc.call(Command.SaveProvider(ProviderConfig("cloud1", "custom", "云端", "https://api.example.com/v1", apiKey = "sk-pc")))
        pc.call(Command.SaveMember(Member("mq", "阿千", providerId = "ollama1", modelId = "qwen3.6:35b-a3b-coding")))
        pc.call(Command.SaveMember(Member("mc", "阿云", providerId = "cloud1", modelId = "x")))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            val hm = HostModels(hub, phone)
            hm.install()
            // 没连上电脑：说清楚要电脑开着
            phone.call(Command.SaveProvider(ProviderConfig("ollama1", "ollama", "Ollama（本机）（电脑）", "http://127.0.0.1:$ollamaPort/v1", viaHost = true)))
            val off = phone.call(Command.TestProvider("ollama1", "qwen3.6:35b-a3b-coding"))
            assertFalse(off.ok); assertTrue("电脑开着" in off.message, off.message)
            assertEquals(0, asked.get())
            phone.call(Command.DeleteProvider("ollama1"))

            assertTrue(hub.pair(PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)).isSuccess)
            withTimeout(20_000) { while (hub.remote.value?.conn?.value !is ConnState.Connected || hub.remote.value?.store?.state?.value?.providers.isNullOrEmpty()) delay(100) }
            assertTrue(hm.syncFromHost())
            val p = phone.state.provider("ollama1")!!
            assertTrue(p.viaHost && p.apiKey.isEmpty() && p.name == "Ollama（本机）（电脑）")
            assertEquals(null, phone.state.provider("cloud1"), "云端服务商不抄（手机不经电脑花电脑的 Key）")
            assertEquals("阿千", phone.state.member("mq")?.name, "第一次抄过来时成员也抄")
            assertEquals(null, phone.state.member("mc"))
            assertFalse(hm.syncFromHost(), "没变就不再写")
            // 手机上删了成员，不再加回来
            phone.call(Command.DeleteMember("mq"))
            hm.syncFromHost()
            assertEquals(null, phone.state.member("mq"))

            val r = phone.call(Command.TestProvider("ollama1", "qwen3.6:35b-a3b-coding"))
            assertTrue(r.ok, r.message); assertTrue("我是电脑上的千问" in r.message, r.message)
            assertEquals(1, asked.get(), "是电脑去问的本机模型")
            // 增量先到：第一截在整段结果回来之前就到了手机
            val parts = java.util.Collections.synchronizedList(mutableListOf<String>())
            val firstPartAt = java.util.concurrent.atomic.AtomicLong(0)
            val ask = HostChatCodec.request(listOf(kotlinx.serialization.json.buildJsonObject {
                put("role", kotlinx.serialization.json.JsonPrimitive("user")); put("content", kotlinx.serialization.json.JsonPrimitive("你是谁"))
            }), null, null, null, null)
            val streamed = hub.remote.value!!.callStreaming(Command.HostChat("ollama1", "qwen3.6:35b-a3b-coding", ask)) { c, _ ->
                firstPartAt.compareAndSet(0, System.currentTimeMillis()); parts += c
            }
            val doneAt = System.currentTimeMillis()
            assertTrue(streamed.ok, streamed.message)
            assertEquals("我是电脑上的千问", HostChatCodec.parseResult(streamed.data).content)
            assertEquals("我是电脑上的千问", parts.joinToString(""), "几截拼起来就是整段")
            assertTrue(firstPartAt.get() in 1..(doneAt - 300), "第一截比整段早到（差 ${doneAt - firstPartAt.get()} 毫秒）")
            // 手机不能借这条路用电脑上的云端服务商
            val cloud = hub.remote.value!!.call(Command.HostChat("cloud1", "x", HostChatCodec.request(emptyList(), null, null, null, null)))
            assertFalse(cloud.ok)
        } finally {
            relay.stop(); broker.stopServer(); ollama.stop(100, 500)
        }
    }
}
