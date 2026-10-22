package com.guixing.jixunying.engine

import com.guixing.jixunying.engine.code.Commander
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * 总指挥的本机 MCP 服务（docs/Code栏设计说明.md 第 18 节，2026-10-22）：MCP 的 Streamable HTTP，只做 Claude Code 要用的那部分。
 * 规范原文（modelcontextprotocol.io 2025-06-18 transports）要求的都照做：只监听 127.0.0.1；校验 Origin（防网页借浏览器打本机端口）；
 * 要认证（每轮一个随机口令，Authorization: Bearer）。POST 收 JSON-RPC：通知回 202 不带内容，请求回 application/json；
 * GET 回 405（不开推送流）；DELETE 回 200。用 JDK 自带的 com.sun.net.httpserver（打包的运行时要带 jdk.httpserver 模块）。
 */
class DesktopMcpServer(private val commander: Commander) : AutoCloseable {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "jxy-mcp").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = pool
        createContext("/mcp") { ex -> runCatching { handle(ex) }.onFailure { runCatching { reply(ex, 500, "") } } }
        start()
    }

    val port: Int get() = server.address.port

    private fun handle(ex: HttpExchange) {
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin != null && !Regex("^https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?$").matches(origin.trim())) return reply(ex, 403, "")
        val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer")?.trim().orEmpty()
        if (token.isEmpty() || !commander.knows(token)) return reply(ex, 401, "")
        when (ex.requestMethod.uppercase()) {
            "POST" -> {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                // 派活要等角色干完的（wait_task）会在这里阻塞几分钟：线程池是 cached，不耽误别的请求
                val out = runBlocking { commander.handle(token, body) }
                if (out == null) reply(ex, 202, "") else reply(ex, 200, out, "application/json")
            }
            "DELETE" -> reply(ex, 200, "")
            else -> reply(ex, 405, "")
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: String, type: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (type != null) ex.responseHeaders.set("Content-Type", "$type; charset=utf-8")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
