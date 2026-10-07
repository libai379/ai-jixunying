package com.guixing.jixunying.relay

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.PairReply
import com.guixing.jixunying.model.PairRequest
import com.guixing.jixunying.model.PairedDevice
import com.guixing.jixunying.model.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 电脑端联机服务：连上公共中转，等手机来配对、来发指令；把引擎的事件加密推给在线的手机。
 * 不需要公网 IP、不需要端口映射，也不需要自己的服务器：电脑和手机都是主动往外连中转。
 */
class RelayHost(private val engine: Engine) {
    private var mqtt: MqttMulti? = null
    private var scope: CoroutineScope? = null
    private var unsubscribe: (() -> Unit)? = null
    private val assemblers = ConcurrentHashMap<String, Envelope.Assembler>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val queues = ConcurrentHashMap<String, Channel<WireFrame>>()
    /** 设备编号 → 密钥的快照，用来在取消配对时还能给它发最后一句「已取消」。 */
    private val knownKeys = ConcurrentHashMap<String, ByteArray>()

    val isRunning get() = mqtt != null

    @Synchronized
    fun restart() {
        stop()
        val s = engine.state
        if (!s.settings.relay.enabled) {
            engine.setRelayStatus("联机已关闭")
            return
        }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        val hostId = s.hostId
        s.devices.forEach { d -> keyOf(d)?.let { knownKeys[d.id] = it } }
        val m = MqttMulti(s.settings.relay.brokers, listOf(Topics.pair(hostId), Topics.allUp(hostId)), probeTopic = Topics.probe(hostId)) { topic, payload -> onMessage(hostId, topic, payload) }
        mqtt = m
        m.start()
        unsubscribe = engine.addListener { e -> forward(e) }
        sc.launch {
            while (isActive) {
                val n = m.readyCount.value
                val total = s.settings.relay.brokers.size
                engine.setRelayStatus(if (n > 0) "已连上中转（$n/$total）" else "正在连接中转服务器…")
                delay(3_000)
            }
        }
    }

    @Synchronized
    fun stop() {
        unsubscribe?.invoke(); unsubscribe = null
        mqtt?.stop(); mqtt = null
        scope?.cancel(); scope = null
        queues.values.forEach { it.close() }
        queues.clear()
        lastSeen.clear()
    }

    fun isOnline(deviceId: String) = (lastSeen[deviceId] ?: 0) > System.currentTimeMillis() - 90_000

    private fun keyOf(d: PairedDevice) = runCatching { Base64.getDecoder().decode(d.key) }.getOrNull()

    private fun onMessage(hostId: String, topic: String, payload: ByteArray) {
        val parts = topic.split('/')
        if (parts.size == 3 && parts[2] == "pair") return onPair(hostId, payload)
        if (parts.size != 4 || parts[3] != "up") return
        val deviceId = parts[2]
        val whole = assemblers.getOrPut(deviceId) { Envelope.Assembler() }.accept(payload) ?: return
        val device = engine.state.devices.firstOrNull { it.id == deviceId }
        if (device == null) {
            // 已被取消配对：能解开就告诉它一声
            knownKeys[deviceId]?.let { k ->
                if (Envelope.openFrames(k, whole) != null) mqtt?.publish(Topics.down(hostId, deviceId), Envelope.sealFrames(k, listOf(WireFrame.Revoked)))
            }
            return
        }
        val key = keyOf(device) ?: return
        val frames = Envelope.openFrames(key, whole) ?: return
        val first = !isOnline(deviceId)
        lastSeen[deviceId] = System.currentTimeMillis()
        if (first) engine.touchDevice(deviceId)
        for (f in frames) {
            when (f) {
                is WireFrame.Hello -> {
                    enqueue(deviceId, WireFrame.Hello(engine.hostName()))
                    if (f.wantState || first) enqueue(deviceId, WireFrame.Evt(Event.State(engine.remoteView(engine.state))))
                }
                is WireFrame.Req -> scope?.launch {
                    val r = engine.call(f.command)
                    enqueue(deviceId, WireFrame.Res(f.reqId, r))
                }
                else -> Unit
            }
        }
    }

    private fun onPair(hostId: String, payload: ByteArray) {
        val whole = assemblers.getOrPut("pair") { Envelope.Assembler() }.accept(payload) ?: return
        val secrets = engine.pairingSecrets()
        secrets.forEachIndexed { i, secret ->
            val json = Envelope.openJson(secret, whole) ?: return@forEachIndexed
            val req = runCatching { AppJson.decodeFromString(PairRequest.serializer(), json) }.getOrNull() ?: return
            val keyOk = runCatching { Base64.getDecoder().decode(req.deviceKey).size == 32 }.getOrDefault(false)
            val fresh = kotlin.math.abs(System.currentTimeMillis() - req.ts) < 10 * 60_000
            val existing = engine.state.devices.firstOrNull { it.id == req.deviceId }
            val reply = when {
                !keyOk || !fresh -> PairReply(false, message = "配对请求无效（手机时间不对或数据损坏）")
                // 用刚换掉的旧二维码：只认刚用它配对成功的那台手机（它补发的请求），别的手机一律拒绝
                i > 0 && (existing == null || existing.key != req.deviceKey) -> PairReply(false, message = "这个二维码已经用过了，请在电脑上看最新的二维码")
                else -> {
                    engine.registerDevice(PairedDevice(req.deviceId, req.deviceName.take(30), req.deviceKey, System.currentTimeMillis()))
                    knownKeys[req.deviceId] = Base64.getDecoder().decode(req.deviceKey)
                    PairReply(true, engine.hostName())
                }
            }
            mqtt?.publish(Topics.pairReply(hostId, req.deviceId), Envelope.sealJson(secret, AppJson.encodeToString(PairReply.serializer(), reply)))
            return
        }
    }

    /** 引擎事件推给在线的手机（状态里的 Key 打码）。 */
    private fun forward(e: Event) {
        val state = engine.state
        // 设备被移除：发「已取消」并忘掉
        knownKeys.keys.filter { id -> state.devices.none { it.id == id } }.forEach { id ->
            if (isOnline(id)) enqueue(id, WireFrame.Revoked, keyOverride = knownKeys[id])
            lastSeen.remove(id)
        }
        val frame = WireFrame.Evt(if (e is Event.State) Event.State(engine.remoteView(e.state)) else e)
        state.devices.forEach { d -> if (isOnline(d.id)) enqueue(d.id, frame) }
    }

    private fun enqueue(deviceId: String, frame: WireFrame, keyOverride: ByteArray? = null) {
        val sc = scope ?: return
        val ch = queues.getOrPut(deviceId) {
            Channel<WireFrame>(Channel.UNLIMITED).also { c -> sc.launch { drain(deviceId, c) } }
        }
        if (keyOverride != null) {
            // 取消配对的最后一句，直接发
            mqtt?.publish(Topics.down(engine.state.hostId, deviceId), Envelope.sealFrames(keyOverride, listOf(frame)))
            return
        }
        ch.trySend(frame)
    }

    /** 每台手机一个发送队列：攒 80 毫秒，把同一条消息的流式增量合并，再打成一包加密发出。 */
    private suspend fun drain(deviceId: String, ch: Channel<WireFrame>) {
        for (first in ch) {
            val batch = mutableListOf(first)
            withTimeoutOrNull(80) { while (batch.size < 200) batch += ch.receive() }
            while (batch.size < 400) batch += ch.tryReceive().getOrNull() ?: break
            val merged = mergeDeltas(batch)
            val device = engine.state.devices.firstOrNull { it.id == deviceId } ?: continue
            val key = keyOf(device) ?: continue
            mqtt?.publish(Topics.down(engine.state.hostId, deviceId), Envelope.sealFrames(key, merged))
        }
    }

    private fun mergeDeltas(frames: List<WireFrame>): List<WireFrame> {
        val out = ArrayList<WireFrame>(frames.size)
        for (f in frames) {
            val last = out.lastOrNull()
            val d = (f as? WireFrame.Evt)?.event as? Event.MessageDelta
            val ld = (last as? WireFrame.Evt)?.event as? Event.MessageDelta
            if (d != null && ld != null && d.messageId == ld.messageId && d.convId == ld.convId) {
                out[out.size - 1] = WireFrame.Evt(ld.copy(content = ld.content + d.content, reasoning = ld.reasoning + d.reasoning))
            } else out += f
        }
        return out
    }
}
