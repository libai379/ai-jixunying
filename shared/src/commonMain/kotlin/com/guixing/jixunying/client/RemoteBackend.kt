package com.guixing.jixunying.client

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.PairedHost
import com.guixing.jixunying.model.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 手机和电脑之间的一条加密线路（具体实现走公共中转，见 jvmShared/relay）。 */
interface FrameLink {
    /** 至少连上了一个中转服务器。 */
    val online: StateFlow<Boolean>
    val incoming: SharedFlow<WireFrame>
    suspend fun send(frame: WireFrame)
    /** 线路自己觉得连着、却很久收不到对方消息时，拆掉重连。 */
    fun reconnect() {}
    fun close()
}

/** 手机遥控电脑：指令经加密线路发给电脑，电脑推回事件。电脑关着时这里显示离线，手机本机照常能用。 */
class RemoteBackend(
    val host: PairedHost,
    private val link: FrameLink,
    private val deviceName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Backend {
    override val store = ClientStore()
    override val isHost = false
    private val _conn = MutableStateFlow<ConnState>(ConnState.Connecting)
    override val conn: StateFlow<ConnState> = _conn

    private var nextReq = 1L
    private val pending = mutableMapOf<Long, CompletableDeferred<CommandResult>>()
    private val lock = Mutex()
    private val fileCache = LinkedHashMap<String, ByteArray>()
    private val clock = TimeSource.Monotonic
    private var lastHostFrame: TimeSource.Monotonic.ValueTimeMark? = null
    private var onlineSince: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastReconnect: TimeSource.Monotonic.ValueTimeMark? = null
    private var reconnectGap = 90.seconds
    private var jobs = mutableListOf<Job>()

    /** 电脑把我取消配对了。 */
    val revoked = MutableStateFlow(false)

    /** 最后一次收到电脑消息的时间（毫秒时间戳）。手机接微信时拿它划界：这以后发来的电脑可能没答。 */
    @kotlin.concurrent.Volatile var lastHeardAt: Long = 0L
        private set

    fun start() {
        jobs += scope.launch {
            link.incoming.collect { f ->
                lastHostFrame = clock.markNow()
                lastHeardAt = com.guixing.jixunying.ui.nowMillis()
                when (f) {
                    is WireFrame.Evt -> store.apply(f.event)
                    is WireFrame.Res -> lock.withLock { pending.remove(f.reqId) }?.complete(f.result)
                    is WireFrame.Hello, is WireFrame.Req -> Unit
                    is WireFrame.Revoked -> revoked.value = true
                }
                refreshState()
            }
        }
        // 心跳：上线时和之后每 25 秒发一次 Hello，电脑会回一声；70 秒没回音就算电脑离线
        jobs += scope.launch {
            var lastHello: TimeSource.Monotonic.ValueTimeMark? = null
            var wasOnline = false
            while (isActive) {
                val online = link.online.value
                if (online && !wasOnline) {
                    onlineSince = clock.markNow()
                    lastHello = null
                }
                wasOnline = online
                if (online && (lastHello == null || lastHello.elapsedNow() > 25.seconds)) {
                    val needState = _conn.value !is ConnState.Connected
                    runCatching { link.send(WireFrame.Hello(deviceName, wantState = needState)) }
                    lastHello = clock.markNow()
                }
                refreshState()
                // 中转连着但电脑一直没回音：可能中转换了机器、老连接成了「僵尸」，拆掉重连一次（最多 90 秒一次）
                val silent = lastHostFrame?.let { it.elapsedNow() > 60.seconds } ?: (onlineSince?.elapsedNow()?.let { it > 20.seconds } == true)
                if (!silent) reconnectGap = 90.seconds
                if (online && silent && (lastReconnect == null || lastReconnect!!.elapsedNow() > reconnectGap)) {
                    lastReconnect = clock.markNow()
                    // 电脑一直不回（多半是关机了）就越等越久再试，最长 10 分钟
                    reconnectGap = (reconnectGap * 2).coerceAtMost(600.seconds)
                    link.reconnect()
                }
                delay(2_000)
            }
        }
    }

    private fun refreshState() {
        val before = _conn.value
        val online = link.online.value
        val since = onlineSince
        val last = lastHostFrame
        _conn.value = when {
            revoked.value -> ConnState.Failed("电脑上已取消这台手机的配对，请重新扫码")
            !online -> ConnState.Failed("连不上中转服务器，检查手机网络")
            last != null && since != null && last > since && last.elapsedNow() < 70.seconds -> ConnState.Connected(host.hostName.ifBlank { "电脑" })
            since != null && since.elapsedNow() > 12.seconds -> ConnState.Failed("电脑不在线（没开机，或者 AI集训营 没打开）")
            else -> ConnState.Connecting
        }
        if (_conn.value is ConnState.Connected && before !is ConnState.Connected) {
            // 重新连上后把已打开的会话再拉一遍，补上断线期间的消息
            store.messages.value.keys.forEach { cid -> scope.launch { call(Command.LoadMessages(cid)) } }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        link.close()
    }

    override suspend fun call(command: Command): CommandResult {
        if (revoked.value) return CommandResult(false, "已取消配对")
        val deferred = CompletableDeferred<CommandResult>()
        val id = lock.withLock { val id = nextReq++; pending[id] = deferred; id }
        return try {
            link.send(WireFrame.Req(id, command))
            withTimeoutOrNull(120_000) { deferred.await() } ?: CommandResult(false, "电脑没有响应（不在线或网络太慢）")
        } catch (e: Throwable) {
            CommandResult(false, e.message ?: "发送失败")
        } finally {
            lock.withLock { pending.remove(id) }
        }
    }

    override suspend fun upload(name: String, mime: String, bytes: ByteArray): Attachment? {
        val r = call(Command.UploadFile(name, mime, Base64.encode(bytes)))
        if (!r.ok) {
            store.apply(com.guixing.jixunying.model.Event.Notice(r.message.ifBlank { "上传失败" }, error = true))
            return null
        }
        return runCatching { AppJson.decodeFromString(Attachment.serializer(), r.data) }.getOrNull()
    }

    override suspend fun fileBytes(id: String): ByteArray? {
        fileCache[id]?.let { return it }
        val r = call(Command.GetFile(id))
        if (!r.ok) return null
        return runCatching { Base64.decode(r.data) }.getOrNull()?.also {
            fileCache[id] = it
            while (fileCache.size > 30) fileCache.remove(fileCache.keys.first())
        }
    }
}
