package com.guixing.jixunying.engine.code

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 客户端（2026-10-18，开发补齐第 12 样；Claude Code 的「Connect external tools」）。
 * 只做 stdio：把服务器当子进程开起来，一行一个 JSON-RPC 消息。
 * 照官方规范 https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle ：
 * initialize（protocolVersion、capabilities、clientInfo）→ notifications/initialized → tools/list / tools/call。
 * 关掉：先关它的输入，再杀进程。
 */
class McpClient(val name: String, private val commandLine: String, private val dir: File) {

    class Tool(val name: String, val description: String, val inputSchema: JsonObject)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val stderr = StringBuilder()
    @Volatile var tools: List<Tool> = emptyList(); private set

    val alive: Boolean get() = process?.isAlive == true

    /** 开起来、握手、取工具清单。失败抛异常（带上服务器 stderr 的最后一截）。 */
    suspend fun start(timeoutMs: Long = 30_000) {
        withContext(Dispatchers.IO) {
            // Windows 上 npx、uvx 这些是 .cmd：交给 cmd.exe 去找
            val pb = ProcessBuilder("cmd.exe", "/d", "/s", "/c", commandLine).directory(dir)
            val env = pb.environment()
            // 服务器自己要联网、要找 node：环境照给，但名字里带 KEY / TOKEN / SECRET 的不给（和跑命令同一条规矩的精神）
            env.keys.filter { Regex("KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL", RegexOption.IGNORE_CASE).containsMatchIn(it) }.forEach { env.remove(it) }
            val p = pb.start()
            process = p
            writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
            Thread {
                runCatching {
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        val o = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
                        val id = (o["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@forEachLine
                        pending.remove(id)?.complete(o)
                    }
                }
                pending.values.forEach { it.completeExceptionally(IllegalStateException("MCP 服务器「$name」退出了：" + stderrTail())) }
            }.apply { isDaemon = true; start() }
            Thread {
                runCatching { p.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it); if (stderr.length > 8000) stderr.delete(0, stderr.length - 8000) } } }
            }.apply { isDaemon = true; start() }
        }
        withTimeout(timeoutMs) {
            request("initialize", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "ai-jixunying"); put("version", "1") }
            })
            notify("notifications/initialized")
            val r = request("tools/list", JsonObject(emptyMap()))
            tools = (r["tools"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Tool((o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                    (o["description"] as? JsonPrimitive)?.content.orEmpty(),
                    (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") })
            }
        }
    }

    /** 调一个工具，返回文字结果（content 里的 text 拼起来；isError 的前面标出来）。 */
    suspend fun call(tool: String, arguments: JsonObject, timeoutMs: Long = 120_000): String = withTimeout(timeoutMs) {
        val r = request("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) })
        val text = (r["content"] as? JsonArray).orEmpty().joinToString("\n") { e ->
            val o = e as? JsonObject
            when ((o?.get("type") as? JsonPrimitive)?.content) {
                "text" -> (o?.get("text") as? JsonPrimitive)?.content.orEmpty()
                null -> ""
                else -> "[${(o["type"] as JsonPrimitive).content}]"
            }
        }
        if ((r["isError"] as? JsonPrimitive)?.content == "true") "（工具报错）$text" else text
    }

    private suspend fun request(method: String, params: JsonElement): JsonObject {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        val o = d.await()
        (o["error"] as? JsonObject)?.let { throw IllegalStateException("MCP「$name」$method 出错：" + ((it["message"] as? JsonPrimitive)?.content ?: it.toString())) }
        return (o["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    private suspend fun notify(method: String) = send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) })

    private suspend fun send(o: JsonObject) = withContext(Dispatchers.IO) {
        val w = writer ?: throw IllegalStateException("MCP「$name」没开起来")
        synchronized(w) { w.write(o.toString()); w.write("\n"); w.flush() }
    }

    fun stderrTail(): String = synchronized(stderr) { stderr.toString().takeLast(600) }

    fun close() {
        runCatching { writer?.close() }
        val p = process ?: return
        runCatching { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
    }
}
