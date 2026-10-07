package com.guixing.jixunying.relay

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 同时连好几个公共 MQTT 中转，哪个通用哪个。发的时候每个都发一份，收的时候按消息编号去重。
 *
 * 公共中转会换机器（DNS 指向变了，老连接还挂在旧机器上，表面「已连接」其实收不到别人的消息）。
 * 所以每个连接每 45 秒往探测主题发一条小消息，自己订阅着；连续 150 秒一条探测都没收到，就拆掉重连（重新解析域名）。
 */
class MqttMulti(
    private val brokers: List<String>,
    private val topics: List<String>,
    private val probeTopic: String? = null,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, MqttAsyncClient>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val lastProbe = ConcurrentHashMap<String, Long>()
    private val forced = ConcurrentHashMap.newKeySet<String>()
    private val _readyCount = MutableStateFlow(0)
    /** 已连上并订阅好的中转数量。 */
    val readyCount: StateFlow<Int> = _readyCount
    @Volatile private var stopped = false

    private val allTopics = if (probeTopic != null) topics + probeTopic else topics

    fun start() {
        brokers.distinct().forEach { uri -> scope.launch { supervise(uri) } }
    }

    /** 全部拆掉重连（比如手机很久没收到电脑的回音）。 */
    fun forceReconnect() {
        forced.addAll(clients.keys)
    }

    private fun update() { _readyCount.value = ready.size }

    private suspend fun supervise(uri: String) {
        while (!stopped) {
            val client = try {
                MqttAsyncClient(uri, "jxy-" + Crypto.randomHex(8), MemoryPersistence())
            } catch (_: Throwable) {
                return
            }
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) = subscribe(client, uri)
                override fun connectionLost(cause: Throwable?) {
                    ready.remove(uri); update()
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (topic == probeTopic) { lastProbe[uri] = System.currentTimeMillis(); return }
                    runCatching { onMessage(topic, message.payload) }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 30
                connectionTimeout = 15
                maxInflight = 500
                maxReconnectDelay = 30_000
            }
            // Paho 的自动重连只在第一次连上之后才生效，第一次要自己重试
            var backoff = 2_000L
            while (!stopped && !client.isConnected) {
                val ok = runCatching { client.connect(opts).waitForCompletion(20_000); true }.getOrDefault(false)
                if (ok) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
            if (stopped) { runCatching { client.close(true) }; return }
            clients[uri] = client
            lastProbe[uri] = System.currentTimeMillis()
            forced.remove(uri)
            // 健康检查
            while (!stopped && uri !in forced) {
                if (probeTopic != null && client.isConnected && uri in ready) {
                    runCatching { client.publish(probeTopic, MqttMessage(byteArrayOf(1)).apply { qos = 0 }) }
                }
                var waited = 0L
                while (!stopped && uri !in forced && waited < 45_000) { delay(1_000); waited += 1_000 }
                if (probeTopic != null && System.currentTimeMillis() - (lastProbe[uri] ?: 0) > 150_000) break
            }
            // 拆掉，重来
            clients.remove(uri, client)
            ready.remove(uri); update()
            runCatching { if (client.isConnected) client.disconnect(500).waitForCompletion(1_000) }
            runCatching { client.close(true) }
            if (!stopped) delay(1_000)
        }
    }

    private fun subscribe(client: MqttAsyncClient, uri: String) {
        if (allTopics.isEmpty()) { ready.add(uri); update(); return }
        runCatching {
            client.subscribe(allTopics.toTypedArray(), IntArray(allTopics.size) { 1 }, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    ready.add(uri); update()
                    lastProbe[uri] = System.currentTimeMillis()
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {}
            })
        }
    }

    fun publish(topic: String, payloads: List<ByteArray>, qos: Int = 1) {
        for (c in clients.values) {
            if (!c.isConnected) continue
            for (p in payloads) runCatching { c.publish(topic, MqttMessage(p).apply { this.qos = qos }) }
        }
    }

    fun stop() {
        stopped = true
        clients.values.forEach { c ->
            runCatching { if (c.isConnected) c.disconnect(500).waitForCompletion(1_000) }
            runCatching { c.close(true) }
        }
        clients.clear()
        ready.clear(); update()
        scope.cancel()
    }
}

/** 加密前的明文：一个包里可以装好几帧（流式输出时攒一下再发，少发很多条）。 */
@Serializable
data class SealedFrames(val ts: Long, val frames: List<WireFrame>)

/**
 * 信封：密文太大就切片，每片带 [J1][消息编号 8 字节][第几片 2 字节][共几片 2 字节]。
 * 公共中转单条最大 1MB（EMQX），这里切成 96KB 一片。
 */
object Envelope {
    private const val CHUNK = 96 * 1024
    private const val HEADER = 14

    fun pack(sealed: ByteArray): List<ByteArray> {
        val id = Random.nextLong()
        val count = ((sealed.size + CHUNK - 1) / CHUNK).coerceAtLeast(1)
        return (0 until count).map { i ->
            val from = i * CHUNK
            val to = minOf(sealed.size, from + CHUNK)
            ByteBuffer.allocate(HEADER + (to - from)).apply {
                put('J'.code.toByte()); put('1'.code.toByte())
                putLong(id); putShort(i.toShort()); putShort(count.toShort())
                put(sealed, from, to - from)
            }.array()
        }
    }

    /** 收片、拼包、去重（同一条消息从两个中转各来一份）。 */
    class Assembler {
        private class Partial(val parts: Array<ByteArray?>, val created: Long)
        private val partials = HashMap<Long, Partial>()
        private val seen = object : LinkedHashMap<Long, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 4000
        }

        @Synchronized
        fun accept(payload: ByteArray): ByteArray? {
            if (payload.size < HEADER || payload[0] != 'J'.code.toByte() || payload[1] != '1'.code.toByte()) return null
            val bb = ByteBuffer.wrap(payload)
            bb.position(2)
            val id = bb.long
            val idx = bb.short.toInt()
            val count = bb.short.toInt()
            if (count <= 0 || idx !in 0 until count || seen.containsKey(id)) return null
            val now = System.currentTimeMillis()
            partials.entries.removeAll { now - it.value.created > 180_000 }
            val p = partials.getOrPut(id) { Partial(arrayOfNulls(count), now) }
            if (p.parts.size != count) return null
            p.parts[idx] = payload.copyOfRange(HEADER, payload.size)
            if (p.parts.any { it == null }) return null
            partials.remove(id)
            seen[id] = true
            val total = p.parts.sumOf { it!!.size }
            val out = ByteArray(total)
            var pos = 0
            p.parts.forEach { part -> part!!.copyInto(out, pos); pos += part.size }
            return out
        }
    }

    private val framesSer = SealedFrames.serializer()

    fun sealFrames(key: ByteArray, frames: List<WireFrame>): List<ByteArray> {
        val json = AppJson.encodeToString(framesSer, SealedFrames(System.currentTimeMillis(), frames))
        return pack(Crypto.seal(key, Crypto.gzip(json.encodeToByteArray())))
    }

    /** 解开一包；密钥不对、被篡改或者太旧（超过 30 分钟，防重放）都返回 null。 */
    fun openFrames(key: ByteArray, whole: ByteArray): List<WireFrame>? {
        val plain = Crypto.open(key, whole) ?: return null
        val sealed = runCatching { AppJson.decodeFromString(framesSer, Crypto.gunzip(plain).decodeToString()) }.getOrNull() ?: return null
        if (kotlin.math.abs(System.currentTimeMillis() - sealed.ts) > 30 * 60_000) return null
        return sealed.frames
    }

    fun sealJson(key: ByteArray, json: String): List<ByteArray> = pack(Crypto.seal(key, Crypto.gzip(json.encodeToByteArray())))

    fun openJson(key: ByteArray, whole: ByteArray): String? =
        Crypto.open(key, whole)?.let { runCatching { Crypto.gunzip(it).decodeToString() }.getOrNull() }

    @Suppress("unused")
    private val listSer = ListSerializer(WireFrame.serializer())
}

object Topics {
    fun pair(hostId: String) = "jxy1/$hostId/pair"
    fun pairReply(hostId: String, deviceId: String) = "jxy1/$hostId/pair/$deviceId"
    fun up(hostId: String, deviceId: String) = "jxy1/$hostId/$deviceId/up"
    fun down(hostId: String, deviceId: String) = "jxy1/$hostId/$deviceId/dn"
    fun allUp(hostId: String) = "jxy1/$hostId/+/up"
    fun probe(hostId: String) = "jxy1/$hostId/probe"
    fun probe(hostId: String, deviceId: String) = "jxy1/$hostId/$deviceId/probe"
}
