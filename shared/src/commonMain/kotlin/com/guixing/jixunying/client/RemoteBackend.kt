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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** 手机切到后台省电：断开中转；resume 时再连上。 */
    fun pause() {}
    fun resume() {}
    fun close()
}

/** 手机遥控电脑：指令经加密线路发给电脑，电脑推回事件。电脑关着时这里显示离线，手机本机照常能用。 */
/** 从电脑取文件时一段多大：经公共中转加密后约 6 片（每片 96KB）。 */
private const val FILE_PART = 512 * 1024

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
    /** callStreaming 的请求：电脑先回的一截交给谁。 */
    private val partSinks = mutableMapOf<Long, (String, String) -> Unit>()
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

    /** 手机切到后台、又没在接微信：断开中转、不发心跳，省电。回前台 resume。 */
    @kotlin.concurrent.Volatile var paused = false
        private set

    fun pause() {
        if (paused) return
        paused = true
        link.pause()
        _conn.value = ConnState.Connecting
    }

    fun resume() {
        if (!paused) return
        paused = false
        onlineSince = null
        lastHostFrame = null
        link.resume()
    }

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
                    is WireFrame.Part -> lock.withLock { partSinks[f.reqId] }?.invoke(f.content, f.reasoning)
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
                if (paused) { wasOnline = false; delay(2_000); continue }
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
        if (paused) return
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

    override suspend fun call(command: Command): CommandResult = callStreaming(command, null)

    /**
     * 和 call 一样，电脑没答完时先回的一截（WireFrame.Part）交给 onPart。
     * 借电脑本机模型（HostChat）：大模型在电脑上慢慢想，从最后一次收到东西算起等 10 分钟。
     * 借电脑听视频（HostListen）也等 10 分钟（解码再一段段听写）；借电脑读平台链接（HostPage）等 4 分钟
     * （10-29 实测电脑上开一条抖音要 46 秒，前面还可能排着别的页面）。
     */
    suspend fun callStreaming(command: Command, onPart: ((String, String) -> Unit)?): CommandResult {
        if (revoked.value) return CommandResult(false, "已取消配对")
        val deferred = CompletableDeferred<CommandResult>()
        var lastPart = clock.markNow()
        val sink: ((String, String) -> Unit)? = onPart?.let { f -> { c, r -> lastPart = clock.markNow(); f(c, r) } }
        val id = lock.withLock { val id = nextReq++; pending[id] = deferred; sink?.let { partSinks[id] = it }; id }
        val limit = when (command) {
            is Command.HostChat, is Command.HostListen -> 600.seconds
            is Command.HostPage -> 240.seconds
            else -> 120.seconds
        }
        return try {
            link.send(WireFrame.Req(id, command))
            var r: CommandResult? = null
            while (r == null) {
                val left = limit - lastPart.elapsedNow()
                if (!left.isPositive()) break
                r = withTimeoutOrNull(left) { deferred.await() }
            }
            r ?: CommandResult(false, "电脑没有响应（不在线或网络太慢）")
        } catch (e: Throwable) {
            CommandResult(false, e.message ?: "发送失败")
        } finally {
            lock.withLock { pending.remove(id); partSinks.remove(id) }
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

    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val downloads: StateFlow<Map<String, Float>> = _downloads

    /**
     * 从电脑取文件：一段 512KB 地取，三段一起，哪段没到就单独重取（2026-10-30 用户：手机上 MiniMax 做的视频「打不开，也下载不了」——
     * 以前整个文件一次传，17MB 经公共中转切成两百多片一起发，丢一片就拼不起来、等 120 秒算失败；千问的 3MB 四十来片过得去）。
     * 电脑是旧版、不认分段：第一段 20 秒没回音就按旧办法整个取。
     */
    override suspend fun fileBytes(id: String): ByteArray? {
        fileCache[id]?.let { return it }
        val got = try { fileInParts(id) } finally { _downloads.value = _downloads.value - id }
        val bytes = when (got) {
            is PartsResult.Done -> got.bytes
            // 电脑不认分段（旧版）：按旧办法整个取
            PartsResult.NotSupported -> call(Command.GetFile(id)).takeIf { it.ok }?.let { r -> runCatching { Base64.decode(r.data) }.getOrNull() }
            PartsResult.Failed -> null
        }
        // 只把小文件（图片这类）留在内存里；十几 MB 的视频留几个手机内存就紧了
        return bytes?.also {
            if (it.size <= 4 * 1024 * 1024) {
                fileCache[id] = it
                while (fileCache.size > 30) fileCache.remove(fileCache.keys.first())
            }
        }
    }

    private sealed interface PartsResult {
        class Done(val bytes: ByteArray) : PartsResult
        data object NotSupported : PartsResult
        data object Failed : PartsResult
    }

    private suspend fun fileInParts(id: String): PartsResult {
        // 第一段 20 秒没回音：多半是旧版电脑不认这条（它会直接丢掉认不出的请求，不回话）
        val first = withTimeoutOrNull(20.seconds) { part(id, 0, tries = 1) } ?: return PartsResult.NotSupported
        val total = first.first
        if (total > Int.MAX_VALUE || total < 0) return PartsResult.Failed
        val out = ByteArray(total.toInt())
        first.second.copyInto(out, 0)
        var done = first.second.size.toLong()
        val offsets = (first.second.size.toLong() until total step FILE_PART.toLong()).toList()
        _downloads.value = _downloads.value + (id to (done.toFloat() / total.coerceAtLeast(1)))
        for (window in offsets.chunked(3)) {
            val got = coroutineScope {
                window.map { off -> async { off to part(id, off) } }.map { it.await() }
            }
            for ((off, p) in got) {
                val bytes = p?.second ?: return PartsResult.Failed
                bytes.copyInto(out, off.toInt())
                done += bytes.size
            }
            _downloads.value = _downloads.value + (id to (done.toFloat() / total))
        }
        return if (done == total) PartsResult.Done(out) else PartsResult.Failed
    }

    /** 取一段：(整个文件多少字节, 这一段)；最多试 tries 次，文件不在就不再试。 */
    private suspend fun part(id: String, offset: Long, tries: Int = 3): Pair<Long, ByteArray>? {
        repeat(tries) {
            val r = call(Command.GetFilePart(id, offset, FILE_PART))
            if (r.ok) {
                val total = r.message.toLongOrNull()
                val bytes = runCatching { Base64.decode(r.data) }.getOrNull()
                if (total != null && bytes != null) return total to bytes
            } else if (r.message == "文件不存在") return null
        }
        return null
    }
}
