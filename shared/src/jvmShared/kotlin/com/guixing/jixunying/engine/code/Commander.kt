package com.guixing.jixunying.engine.code

import com.guixing.jixunying.model.TeamSeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude Code 当总指挥（docs/Code栏设计说明.md 第 18 节，2026-10-22）：本机 MCP 服务的 JSON-RPC 这一层。
 * HTTP 那层在电脑端（DesktopMcpServer，只监听 127.0.0.1、校验口令和 Origin）；引擎在两端共用，这里不碰网络。
 * 每把任务交给组了队的 Claude Code，开一个 Session（随机口令）；它调 assign_task 派活、wait_task 收结果、list_tasks 看状态。
 * 角色真正怎么干活由 run 决定（CodeAgent.assignRole，和第 16 节的 assign_task 同一套）。
 */
class Commander {
    /** 派出去的一张单子。 */
    class Task(val id: String, val role: String, val name: String, val startedAt: Long, val job: Deferred<String>)

    /**
     * 一轮总指挥。scope 是总指挥这一轮的协程（用户点停止、这一轮结束，角色跟着停）；
     * run 真正派活（返回角色交回的结果）；roles 是团队里的角色（role 和给它看的说明）。
     */
    class Session(
        val token: String,
        val scope: CoroutineScope,
        val run: suspend (role: String, task: String, name: String) -> String,
        val roles: List<Pair<String, String>>,
    ) {
        val tasks = java.util.Collections.synchronizedMap(LinkedHashMap<String, Task>())
        private var n = 0
        @Synchronized fun nextId() = "T${++n}"
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun open(scope: CoroutineScope, roles: List<Pair<String, String>>, run: suspend (String, String, String) -> String): Session {
        val token = ByteArray(24).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return Session(token, scope, run, roles).also { sessions[token] = it }
    }

    /** 这一轮结束：口令作废；还没做完的单子停掉，返回它们（回答里写明）。 */
    fun close(token: String): List<Task> {
        val s = sessions.remove(token) ?: return emptyList()
        val left = synchronized(s.tasks) { s.tasks.values.filter { !it.job.isCompleted } }
        left.forEach { it.job.cancel() }
        return left
    }

    fun knows(token: String) = sessions.containsKey(token)

    /**
     * 一个 POST 的内容 → 回的 JSON；null = 收到的是通知（HTTP 那层回 202 不带内容）。
     * 口令不认识也回一个 JSON-RPC 错误（正常情况 HTTP 那层已经先挡了）。
     */
    suspend fun handle(token: String, body: String, now: () -> Long = System::currentTimeMillis): String? {
        val msg = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return error(JsonNull, -32700, "Parse error")
        val id = msg["id"]
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull
        if (id == null || id is JsonNull) return null // 通知（notifications/initialized、cancelled……）或者对我们的回应
        val s = sessions[token] ?: return error(id, -32001, "这一轮已经结束，口令作废了")
        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                // 照它要的版本回（我们只用工具，几个版本都一样）
                put("protocolVersion", (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: "2025-06-18")
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                putJsonObject("serverInfo") { put("name", "jxy"); put("title", "AI集训营"); put("version", "1") }
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", tools(s)) })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val (text, bad) = when (name) {
                    "assign_task" -> assign(s, args, now())
                    "wait_task" -> wait(s, args, now)
                    "list_tasks" -> list(s, now()) to false
                    else -> "没有这个工具：$name" to true
                }
                result(id, buildJsonObject {
                    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
                    put("isError", bad)
                })
            }
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun tools(s: Session): JsonArray = buildJsonArray {
        val roles = s.roles.joinToString("；") { (r, d) -> "$r = $d" }
        add(tool("assign_task", "把一张任务单派给团队里的一个角色，马上返回编号（T1、T2…），角色在后台干；可以连着派几张同时干。现在的团队：$roles。") {
            putJsonObject("role") { put("type", "string"); putJsonArray("enum") { s.roles.forEach { add(it.first) } }; put("description", "派给哪个角色") }
            putJsonObject("task") { put("type", "string"); put("description", "任务单：要做什么、改哪些文件、做完交回什么") }
            putJsonObject("name") { put("type", "string"); put("description", "给这张单子起个短名字（可以不填）") }
        })
        add(tool("wait_task", "等派出去的单子做完，拿回结果。不写 ids 就等全部；一次最多等 $MAX_WAIT 秒，没做完的会写还在做，可以接着等。") {
            putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "要等的编号，比如 [\"T1\"]") }
            putJsonObject("seconds") { put("type", "integer"); put("description", "最多等几秒（默认 $MAX_WAIT）") }
        })
        add(tool("list_tasks", "列出这一轮派过的单子和状态。") {})
    }

    private fun tool(name: String, description: String, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties", props)
            if (name == "assign_task") putJsonArray("required") { add("role"); add("task") }
        }
    }

    private fun assign(s: Session, args: JsonObject, now: Long): Pair<String, Boolean> {
        val role = (args["role"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val task = (args["task"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        if (task.isEmpty()) return "参数错误：缺少 task" to true
        if (s.roles.none { it.first == role }) return "团队里没有「$role」。现在有：" + s.roles.joinToString("、") { it.first } to true
        val name = (args["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().ifBlank { task.lineSequence().first().take(16) }
        val id = s.nextId()
        val job = s.scope.async { s.run(role, task, name) }
        s.tasks[id] = Task(id, role, name, now, job)
        return "派出去了：$id（${TeamSeat.label(role)}：$name）。用 wait_task 等它做完拿结果；需要的话可以先派别的。" to false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun wait(s: Session, args: JsonObject, now: () -> Long): Pair<String, Boolean> {
        val want = when (val v = args["ids"]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            is JsonPrimitive -> v.contentOrNull.orEmpty().split(',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        val all = synchronized(s.tasks) { s.tasks.values.toList() }
        if (all.isEmpty()) return "还没派过单子。" to true
        val picked = if (want.isEmpty()) all else all.filter { it.id in want }
        val unknown = want.filter { w -> all.none { it.id == w } }
        if (picked.isEmpty()) return "没有这些编号：${unknown.joinToString("、")}。派过的：${all.joinToString("、") { it.id }}" to true
        val seconds = ((args["seconds"] as? JsonPrimitive)?.intOrNull ?: MAX_WAIT).coerceIn(1, MAX_WAIT)
        withTimeoutOrNull(seconds * 1000L) { picked.forEach { it.job.join() } }
        val text = buildString {
            if (unknown.isNotEmpty()) appendLine("（没有这些编号：${unknown.joinToString("、")}）")
            for (t in picked) {
                val head = "${t.id}（${TeamSeat.label(t.role)}：${t.name}）"
                when {
                    !t.job.isCompleted -> appendLine("$head 还在做（已经 ${(now() - t.startedAt) / 1000} 秒），可以接着等。")
                    t.job.isCancelled -> appendLine("$head 停下了，没做完。")
                    else -> appendLine("$head 做完了：\n" + t.job.getCompleted().take(RESULT_LIMIT)).also { appendLine() }
                }
            }
        }
        return text.trim() to false
    }

    private fun list(s: Session, now: Long): String {
        val all = synchronized(s.tasks) { s.tasks.values.toList() }
        if (all.isEmpty()) return "还没派过单子。"
        return all.joinToString("\n") { t ->
            "${t.id} ${TeamSeat.label(t.role)}：${t.name} — " + when {
                !t.job.isCompleted -> "还在做（已经 ${(now - t.startedAt) / 1000} 秒）"
                t.job.isCancelled -> "停下了"
                else -> "做完了"
            }
        }
    }

    private fun result(id: JsonElement, r: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", r) }.toString()
    private fun error(id: JsonElement, code: Int, message: String) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) } }.toString()

    companion object {
        /** wait_task 一次最多等多久（秒）。配置里 timeout 给 30 分钟，Claude Code 判空闲不会早于它（官方原文，第 18 节第 3 条）。 */
        const val MAX_WAIT = 600
        /** MCP 服务配置里给这个服务的 timeout（毫秒）。 */
        const val TOOL_TIMEOUT_MS = 1_800_000
        /** 一张单子交回的结果最多给它多少字（Claude Code 对超过 1 万 token 的工具输出会警告）。 */
        const val RESULT_LIMIT = 8000

        /** 写给 claude 的 --mcp-config 内容。 */
        fun config(port: Int, token: String): String = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject("jxy") {
                    put("type", "http")
                    put("url", "http://127.0.0.1:$port/mcp")
                    putJsonObject("headers") { put("Authorization", "Bearer $token") }
                    put("timeout", TOOL_TIMEOUT_MS)
                }
            }
        }.toString()
    }
}
