package com.guixing.jixunying.relay

import com.guixing.jixunying.client.FrameLink
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.PairReply
import com.guixing.jixunying.model.PairRequest
import com.guixing.jixunying.model.PairedHost
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64

/** 手机端的加密线路：订阅「电脑→我」，往「我→电脑」发。 */
class RelayLink(private val host: PairedHost) : FrameLink {
    private val key = Base64.getDecoder().decode(host.key)
    private val assembler = Envelope.Assembler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<WireFrame>(extraBufferCapacity = 1024)
    override val incoming: SharedFlow<WireFrame> = _incoming

    private fun newMqtt() = MqttMulti(host.brokers, listOf(Topics.down(host.hostId, host.deviceId)), probeTopic = Topics.probe(host.hostId, host.deviceId)) { _, payload ->
        val whole = assembler.accept(payload) ?: return@MqttMulti
        Envelope.openFrames(key, whole)?.forEach { _incoming.tryEmit(it) }
    }

    // 暂停（手机切到后台省电）会把中转全断开，继续时换一套新连接；online 跟着当前这套走
    @Volatile private var mqtt: MqttMulti? = newMqtt()
    private val _online = MutableStateFlow(false)
    override val online: StateFlow<Boolean> = _online
    private var watch: Job? = null

    private fun watchOnline(m: MqttMulti) {
        watch?.cancel()
        watch = scope.launch { m.readyCount.collect { _online.value = it > 0 } }
    }

    init { mqtt?.let { watchOnline(it); it.start() } }

    override suspend fun send(frame: WireFrame) = withContext(Dispatchers.IO) {
        mqtt?.publish(Topics.up(host.hostId, host.deviceId), Envelope.sealFrames(key, listOf(frame))) ?: Unit
    }

    override fun reconnect() { mqtt?.forceReconnect() }

    @Synchronized override fun pause() {
        val m = mqtt ?: return
        mqtt = null
        watch?.cancel()
        _online.value = false
        scope.launch { m.stop() }
    }

    @Synchronized override fun resume() {
        if (mqtt != null) return
        val m = newMqtt()
        mqtt = m
        watchOnline(m)
        m.start()
    }

    override fun close() {
        mqtt?.stop()
        mqtt = null
        scope.cancel()
    }
}

/**
 * 手机扫码后配对：生成自己的编号和长期密钥，用二维码里的一次性密钥加密发给电脑，等电脑回话。
 */
suspend fun pairWithHost(code: PairingCode, deviceName: String): Result<PairedHost> = withContext(Dispatchers.IO) {
    val secret = runCatching { Base64.getDecoder().decode(code.p) }.getOrNull()
        ?: return@withContext Result.failure(IllegalArgumentException("配对码不对"))
    val deviceId = Crypto.randomHex(6)
    val deviceKey = Crypto.randomBytes(32)
    val reply = CompletableDeferred<PairReply>()
    val assembler = Envelope.Assembler()
    val mqtt = MqttMulti(code.b, listOf(Topics.pairReply(code.h, deviceId))) { _, payload ->
        val whole = assembler.accept(payload) ?: return@MqttMulti
        val json = Envelope.openJson(secret, whole) ?: return@MqttMulti
        runCatching { AppJson.decodeFromString(PairReply.serializer(), json) }.getOrNull()?.let { reply.complete(it) }
    }
    mqtt.start()
    try {
        withTimeoutOrNull(20_000) { mqtt.readyCount.first { it > 0 } }
            ?: return@withContext Result.failure(IllegalStateException("连不上中转服务器，检查手机网络"))
        val req = AppJson.encodeToString(
            PairRequest.serializer(),
            PairRequest(deviceId, deviceName.ifBlank { "手机" }, Base64.getEncoder().encodeToString(deviceKey), System.currentTimeMillis()),
        )
        // 订阅可能还没在所有中转上生效，隔几秒补发一次（电脑那边按设备编号去重）
        val sender = CoroutineScope(Dispatchers.IO).launch {
            repeat(6) {
                mqtt.publish(Topics.pair(code.h), Envelope.sealJson(secret, req))
                delay(3_000)
            }
        }
        val r = withTimeoutOrNull(25_000) { reply.await() }
        sender.cancel()
        when {
            r == null -> Result.failure(IllegalStateException("电脑没回应：确认电脑上 AI集训营 开着，二维码是最新的（每个二维码只能用一次）"))
            !r.ok -> Result.failure(IllegalStateException(r.message.ifBlank { "电脑拒绝了配对" }))
            else -> Result.success(PairedHost(code.h, r.hostName.ifBlank { code.n }, deviceId, Base64.getEncoder().encodeToString(deviceKey), code.b))
        }
    } finally {
        mqtt.stop()
    }
}
