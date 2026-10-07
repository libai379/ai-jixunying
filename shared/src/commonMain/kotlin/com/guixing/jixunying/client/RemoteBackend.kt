package com.guixing.jixunying.client

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.WireFrame
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val ok: Boolean, val token: String = "", val message: String = "", val hostName: String = "")

/** 手机端：所有指令经局域网转给桌面，桌面推回事件。 */
class RemoteBackend(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Backend {
    override val store = ClientStore()
    override val isHost = false
    private val _conn = MutableStateFlow<ConnState>(ConnState.NotPaired)
    override val conn: StateFlow<ConnState> = _conn

    private val http = HttpClient {
        install(WebSockets) { pingIntervalMillis = 15_000 }
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 120_000 }
    }

    private var host = ""
    private var token = ""
    private var session: DefaultWebSocketSession? = null
    private var loopJob: Job? = null
    private var nextReq = 1L
    private val pending = mutableMapOf<Long, CompletableDeferred<CommandResult>>()
    private val lock = Mutex()
    private val fileCache = LinkedHashMap<String, ByteArray>()

    val hostAddress get() = host

    private fun base() = "http://$host"

    suspend fun pair(hostPort: String, code: String, deviceName: String): PairResponse = runCatching {
        val resp = http.post("http://$hostPort/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), deviceName)))
        }
        AppJson.decodeFromString(PairResponse.serializer(), resp.bodyAsText())
    }.getOrElse { PairResponse(false, message = "连不上 $hostPort：${it.message ?: it::class.simpleName}") }

    fun connect(hostPort: String, token: String) {
        this.host = hostPort
        this.token = token
        loopJob?.cancel()
        loopJob = scope.launch { runLoop() }
    }

    fun disconnect() {
        loopJob?.cancel()
        loopJob = null
        _conn.value = ConnState.NotPaired
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (scope.isActive) {
            _conn.value = ConnState.Connecting
            try {
                val s = http.webSocketSession("ws://$host/api/ws") { parameter("token", token) }
                session = s
                _conn.value = ConnState.Connected(host)
                backoff = 1_000L
                // 重连后把已经打开过的会话重新拉一遍，补上断线期间的消息
                store.messages.value.keys.forEach { cid -> scope.launch { call(Command.LoadMessages(cid)) } }
                for (frame in s.incoming) {
                    if (frame !is Frame.Text) continue
                    when (val f = AppJson.decodeFromString(WireFrame.serializer(), frame.readText())) {
                        is WireFrame.Evt -> store.apply(f.event)
                        is WireFrame.Res -> lock.withLock { pending.remove(f.reqId) }?.complete(f.result)
                        is WireFrame.Req -> Unit
                    }
                }
                val reason = s.closeReason.await()
                if (reason?.code == 4401.toShort()) {
                    _conn.value = ConnState.Failed("电脑端已取消这台手机的配对，请重新配对")
                    return
                }
                _conn.value = ConnState.Failed("和电脑的连接断开了，正在重连…")
            } catch (e: Throwable) {
                if (!scope.isActive) return
                _conn.value = ConnState.Failed("连不上电脑（$host）：${e.message ?: e::class.simpleName}")
            } finally {
                session = null
                lock.withLock {
                    pending.values.forEach { it.complete(CommandResult(false, "连接断开")) }
                    pending.clear()
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    override suspend fun call(command: Command): CommandResult {
        val s = session ?: return CommandResult(false, "还没连上电脑")
        val deferred = CompletableDeferred<CommandResult>()
        val id = lock.withLock { val id = nextReq++; pending[id] = deferred; id }
        return try {
            s.send(AppJson.encodeToString(WireFrame.serializer(), WireFrame.Req(id, command)))
            withTimeoutOrNull(180_000) { deferred.await() } ?: CommandResult(false, "电脑端没有响应")
        } catch (e: Throwable) {
            lock.withLock { pending.remove(id) }
            CommandResult(false, e.message ?: "发送失败")
        }
    }

    override suspend fun upload(name: String, mime: String, bytes: ByteArray): Attachment? = runCatching {
        val resp = http.post("${base()}/api/files") {
            header("Authorization", "Bearer $token")
            parameter("name", name)
            parameter("mime", mime)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        if (!resp.status.isSuccess()) return null
        AppJson.decodeFromString(Attachment.serializer(), resp.bodyAsText())
    }.getOrNull()

    override suspend fun fileBytes(id: String): ByteArray? {
        fileCache[id]?.let { return it }
        return runCatching {
            val resp = http.get("${base()}/api/files/$id") { header("Authorization", "Bearer $token") }
            if (!resp.status.isSuccess()) null else resp.bodyAsBytes()
        }.getOrNull()?.also {
            fileCache[id] = it
            while (fileCache.size > 40) fileCache.remove(fileCache.keys.first())
        }
    }
}
