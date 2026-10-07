package com.guixing.jixunying.engine

import com.guixing.jixunying.client.PairRequest
import com.guixing.jixunying.client.PairResponse
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.DISCOVERY_PING
import com.guixing.jixunying.model.DISCOVERY_PONG
import com.guixing.jixunying.model.DISCOVERY_PORT
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.WireFrame
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

/**
 * 电脑就是手机的服务器：同一个局域网（同一个 Wi-Fi）里，手机连电脑的这个端口。
 * - UDP 18766：手机广播「找电脑」，电脑回一声
 * - HTTP /api/pair：用电脑上显示的 6 位配对码换一个长期令牌
 * - WebSocket /api/ws：指令和事件
 * - HTTP /api/files：上传附件、取图片
 */
class LanServer(private val eng: Engine) {
    private var server: EmbeddedServer<*, *>? = null
    private var udp: DatagramSocket? = null
    private var scope: CoroutineScope? = null
    var lastError: String? = null
        private set

    fun restart() {
        stop()
        val cfg = eng.state.settings.server
        if (!cfg.enabled) {
            eng.setServerAddresses(emptyList())
            return
        }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        try {
            server = embeddedServer(CIO, port = cfg.port, host = "0.0.0.0") { module() }.also { it.start(wait = false) }
            lastError = null
            eng.setServerAddresses(localAddresses().map { "$it:${cfg.port}" })
        } catch (e: Throwable) {
            lastError = "端口 ${cfg.port} 启动失败：${e.message}"
            eng.setServerAddresses(emptyList())
        }
        sc.launch { discoveryLoop(cfg.port) }
    }

    fun stop() {
        runCatching { server?.stop(500, 1000) }
        server = null
        runCatching { udp?.close() }
        udp = null
        scope?.cancel()
        scope = null
    }

    private fun discoveryLoop(port: Int) {
        try {
            val sock = DatagramSocket(DISCOVERY_PORT).also { it.broadcast = true }
            udp = sock
            val buf = ByteArray(512)
            while (!sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                if (text.startsWith(DISCOVERY_PING)) {
                    val reply = "$DISCOVERY_PONG|${eng.state.settings.server.deviceName}|$port".toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun tokenOf(call: ApplicationCall): String? {
        val h = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        return h ?: call.request.queryParameters["token"]
    }

    private fun io.ktor.server.application.Application.module() {
        install(WebSockets) { pingPeriod = 20.seconds; maxFrameSize = 64L * 1024 * 1024 }
        routing {
            get("/api/ping") {
                call.respondText("""{"app":"ai-jixunying","name":"${eng.state.settings.server.deviceName.replace("\"", "")}"}""", ContentType.Application.Json)
            }
            post("/api/pair") {
                val req = runCatching { AppJson.decodeFromString(PairRequest.serializer(), call.receiveText()) }.getOrNull()
                val token = req?.let { eng.pair(it.code, it.deviceName) }
                val resp = if (token != null) PairResponse(true, token, hostName = eng.state.settings.server.deviceName)
                else PairResponse(false, message = "配对码不对。到电脑上 设置→手机连接 看最新的 6 位配对码")
                call.respondText(AppJson.encodeToString(PairResponse.serializer(), resp), ContentType.Application.Json)
            }
            post("/api/files") {
                if (eng.deviceForToken(tokenOf(call).orEmpty()) == null) return@post call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                val name = call.request.queryParameters["name"] ?: "file"
                val mime = call.request.queryParameters["mime"] ?: "application/octet-stream"
                val bytes = call.receive<ByteArray>()
                val att = eng.upload(name, mime, bytes)
                    ?: return@post call.respondText("too large", status = HttpStatusCode.PayloadTooLarge)
                call.respondText(AppJson.encodeToString(com.guixing.jixunying.model.Attachment.serializer(), att), ContentType.Application.Json)
            }
            get("/api/files/{id}") {
                if (eng.deviceForToken(tokenOf(call).orEmpty()) == null) return@get call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                val id = call.parameters["id"].orEmpty()
                val bytes = eng.fileBytes(id) ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
                val mime = eng.fileMeta(id)?.mime ?: "application/octet-stream"
                call.respondBytes(bytes, ContentType.parse(mime))
            }
            webSocket("/api/ws") {
                val token = call.request.queryParameters["token"].orEmpty()
                if (eng.deviceForToken(token) == null) {
                    close(CloseReason(4401, "unauthorized"))
                    return@webSocket
                }
                val out = Channel<String>(Channel.UNLIMITED)
                val sender = launch {
                    for (t in out) {
                        if (t === KICK) { close(CloseReason(4401, "unpaired")); break }
                        send(t)
                    }
                }
                fun enc(e: Event): String {
                    val ev = if (e is Event.State) Event.State(eng.remoteView(e.state)) else e
                    return AppJson.encodeToString(WireFrame.serializer(), WireFrame.Evt(ev))
                }
                out.trySend(enc(Event.State(eng.state)))
                val unsubscribe = eng.addListener { e ->
                    // 被取消配对的设备立刻断开
                    if (e is Event.State && eng.deviceForToken(token) == null) {
                        out.trySend(KICK)
                    } else out.trySend(enc(e))
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val f = runCatching { AppJson.decodeFromString(WireFrame.serializer(), frame.readText()) }.getOrNull() ?: continue
                        if (f is WireFrame.Req) launch {
                            val r = eng.call(f.command)
                            out.trySend(AppJson.encodeToString(WireFrame.serializer(), WireFrame.Res(f.reqId, r)))
                        }
                    }
                } finally {
                    unsubscribe(); sender.cancel(); out.close()
                }
            }
        }
    }

    companion object {
        private const val KICK = "__jxy_kick__"

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filterNot { n -> listOf("vmware", "virtualbox", "hyper-v", "vethernet", "docker", "wsl", "tap", "tun", "clash", "v2ray").any { n.displayName.lowercase().contains(it) } }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress }
                .sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
        }.getOrDefault(emptyList())
    }
}
