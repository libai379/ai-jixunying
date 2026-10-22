package com.guixing.jixunying.engine.code

import com.guixing.jixunying.model.Usage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 外部 Claude Code（2026-10-18，用户：「code 能不能接入终端，比如 Claude Code 终端……claude 可以用订阅了」）。
 *
 * 不碰订阅凭据：启动的是用户自己装、自己登录的官方 claude（未改动），我们只跟它的标准输入输出说话
 * （Anthropic 官方页面允许「end user signing in to the unmodified Claude Code binary with their own Claude subscription」，
 * 原文摘录在 D:\AI\docs\anthropic-claude-code-legal-20261012.md）。
 *
 * 用的是它的「无界面」模式（2.1.302 本机实测，docs/Code栏设计说明.md 第 15 节）：
 * claude -p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio
 * - 标准输入一行一个 JSON：先发 control_request initialize，再发 {"type":"user","message":{...}}；做完了关掉输入它就退出。
 * - 标准输出一行一个 JSON：system/init（会话编号）、assistant（文字 / 思考 / tool_use）、user（tool_result）、result（做完）。
 * - 要批准的一步它发 control_request（subtype can_use_tool，带 tool_name、input、tool_use_id），
 *   我们回 control_response：{"behavior":"allow","updatedInput":...} 或 {"behavior":"deny","message":...}。
 */
object ClaudeCli {

    sealed interface Event
    data class Init(val sessionId: String, val model: String) : Event
    data class Text(val text: String, val sub: Boolean) : Event
    data class Thinking(val text: String) : Event
    /** sub = 子代理（Task）里的一步。 */
    data class ToolUse(val id: String, val name: String, val input: JsonObject, val sub: Boolean) : Event
    data class ToolResult(val id: String, val text: String, val isError: Boolean) : Event
    data class Permission(val requestId: String, val toolName: String, val input: JsonObject, val toolUseId: String) : Event
    data class Done(val text: String, val isError: Boolean, val sessionId: String, val usage: Usage) : Event
    /** 订阅额度（status 不是 allowed 就是到上限了）。 */
    data class Limit(val status: String, val resetsAt: Long) : Event

    private val json = Json { ignoreUnknownKeys = true }

    /** 一行输出拆成事件；不认识的、坏的行返回空。 */
    fun parse(line: String): List<Event> {
        val o = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return emptyList()
        return when (o.s("type")) {
            "system" -> if (o.s("subtype") == "init") listOf(Init(o.s("session_id"), o.s("model"))) else emptyList()
            "assistant" -> {
                val sub = o["parent_tool_use_id"]?.let { it is JsonPrimitive && it.contentOrNull != null } == true
                val content = (o["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
                content.mapNotNull { b ->
                    val bo = b as? JsonObject ?: return@mapNotNull null
                    when (bo.s("type")) {
                        "text" -> bo.s("text").takeIf { it.isNotEmpty() }?.let { Text(it, sub) }
                        "thinking" -> bo.s("thinking").takeIf { it.isNotEmpty() }?.let { Thinking(it) }
                        "tool_use" -> ToolUse(bo.s("id"), bo.s("name"), bo["input"] as? JsonObject ?: JsonObject(emptyMap()), sub)
                        else -> null
                    }
                }
            }
            "user" -> {
                val content = (o["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
                content.mapNotNull { b ->
                    val bo = b as? JsonObject ?: return@mapNotNull null
                    if (bo.s("type") != "tool_result") return@mapNotNull null
                    ToolResult(bo.s("tool_use_id"), textOf(bo["content"]), (bo["is_error"] as? JsonPrimitive)?.contentOrNull == "true")
                }
            }
            "control_request" -> {
                val r = o["request"] as? JsonObject ?: return emptyList()
                if (r.s("subtype") != "can_use_tool") return emptyList()
                listOf(Permission(o.s("request_id"), r.s("tool_name"), r["input"] as? JsonObject ?: JsonObject(emptyMap()), r.s("tool_use_id")))
            }
            "result" -> {
                val u = o["usage"] as? JsonObject
                fun n(k: String) = (u?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
                val cached = n("cache_read_input_tokens")
                val usage = Usage(n("input_tokens") + n("cache_creation_input_tokens") + cached, n("output_tokens"), cached,
                    (o["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0)
                val isError = (o["is_error"] as? JsonPrimitive)?.contentOrNull == "true" || o.s("subtype").startsWith("error")
                // 出错时说明可能在 result，也可能在 errors 数组里
                val errors = (o["errors"] as? JsonArray)?.joinToString("\n") { textOf(it) }.orEmpty()
                listOf(Done(o.s("result").ifBlank { errors }, isError, o.s("session_id"), usage))
            }
            "rate_limit_event" -> {
                val info = o["rate_limit_info"] as? JsonObject ?: return emptyList()
                listOf(Limit(info.s("status"), (info["resetsAt"] as? JsonPrimitive)?.longOrNull ?: 0))
            }
            else -> emptyList()
        }
    }

    /** tool_result 的 content 可能是字符串，也可能是 [{type:text,text}] 数组。 */
    private fun textOf(e: kotlinx.serialization.json.JsonElement?): String = when (e) {
        null -> ""
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonArray -> e.joinToString("\n") { x -> (x as? JsonObject)?.let { if (it.s("type") == "text") it.s("text") else "" } ?: textOf(x) }.trim()
        is JsonObject -> e.s("text")
    }

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    // ———————————————— 发给它的 ————————————————

    fun initRequest(): String = buildJsonObject {
        put("type", "control_request"); put("request_id", "init"); putJsonObject("request") { put("subtype", "initialize") }
    }.toString()

    /** 一条用户消息；images 是 data URL（data:image/jpeg;base64,...），转成它认的 image 块。 */
    fun userMessage(text: String, images: List<String> = emptyList()): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                for (url in images) {
                    val m = Regex("^data:([^;]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL).find(url) ?: continue
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") { put("type", "base64"); put("media_type", m.groupValues[1]); put("data", m.groupValues[2]) }
                    }
                }
                addJsonObject { put("type", "text"); put("text", text.ifBlank { "（见图）" }) }
            }
        }
    }.toString()

    fun allow(requestId: String, input: JsonObject): String = response(requestId, buildJsonObject {
        put("behavior", "allow"); put("updatedInput", input)
    })

    fun deny(requestId: String, message: String): String = response(requestId, buildJsonObject {
        put("behavior", "deny"); put("message", message)
    })

    private fun response(requestId: String, body: JsonObject): String = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") { put("subtype", "success"); put("request_id", requestId); put("response", body) }
    }.toString()

    // ———————————————— 怎么启动 ————————————————

    /**
     * 找 claude：设置里填了就用它；否则在 PATH 里找 claude.exe / claude.cmd，再看官方安装器的默认位置
     * （%USERPROFILE%\.local\bin\claude.exe）和 npm 装的位置（%APPDATA%\npm\claude.cmd）。
     */
    fun findExe(configured: String, env: Map<String, String> = System.getenv()): File? {
        configured.trim().trim('"').takeIf { it.isNotEmpty() }?.let { return File(it).takeIf { f -> f.isFile } }
        val names = listOf("claude.exe", "claude.cmd", "claude")
        val path = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        for (dir in path.split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
            for (n in names) File(dir.trim('"'), n).takeIf { it.isFile }?.let { return it }
        }
        val home = System.getProperty("user.home")
        listOfNotNull(
            File(home, ".local/bin/claude.exe"),
            env["APPDATA"]?.let { File(it, "npm/claude.cmd") },
        ).firstOrNull { it.isFile }?.let { return it }
        return null
    }

    /** .cmd / .bat 要经 cmd.exe 起（npm 装的是 claude.cmd，测试里的假 claude 也是）。 */
    fun launcher(exe: File): List<String> =
        if (exe.extension.lowercase() in setOf("cmd", "bat")) listOf("cmd.exe", "/c", exe.absolutePath) else listOf(exe.absolutePath)

    /**
     * 启动参数。权限档：计划档交给它自己的 plan（它不会改文件）；其余一律 manual——要改文件、跑不只读的命令都来问我们，
     * 由我们按这个会话的档（手动 / 接受编辑 / 自动 / 全部放开）决定，改之前照样备份，能在回答里撤销。
     * 用户的话、自定义指令都不放在参数里（经 cmd.exe 转一手会被引号、特殊字符弄坏）：话走标准输入，指令写进文件。
     */
    fun args(model: String, plan: Boolean, resume: String, addDirs: List<String>, instructionsFile: File?, mcpConfig: File? = null,
             effort: String = ""): List<String> = buildList {
        addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--permission-prompt-tool", "stdio"))
        // 它的选择题弹窗在这里接不住：让它直接用文字问
        addAll(listOf("--disallowedTools", "AskUserQuestion"))
        addAll(listOf("--permission-mode", if (plan) "plan" else "manual"))
        if (model.isNotBlank()) addAll(listOf("--model", model.trim()))
        // 思考强度（2026-10-22）：空 = 不传，用它自己的默认
        if (effort.isNotBlank()) addAll(listOf("--effort", effort.trim()))
        if (resume.isNotBlank()) addAll(listOf("--resume", resume.trim()))
        for (d in addDirs) addAll(listOf("--add-dir", d))
        if (instructionsFile != null) addAll(listOf("--append-system-prompt-file", instructionsFile.absolutePath))
        // 当总指挥（第 18 节）：本机 MCP 服务的配置（用户自己配的 MCP 照样加载，不用 --strict-mcp-config）
        if (mcpConfig != null) addAll(listOf("--mcp-config", mcpConfig.absolutePath))
    }

    /**
     * 给它的环境变量：去掉 CLAUDECODE、CLAUDE_CODE_SESSION_ID 这类（从 Claude Code 里开的本程序会带着，漏进去它会以为自己是子会话；
     * CCB 踩过，见 D:\AI\ccb\项目说明.md「红线」），留 CLAUDE_CODE_GIT_BASH_PATH、CLAUDE_CONFIG_DIR、CLAUDE_CODE_OAUTH_TOKEN 这几个用户自己设的；
     * 没设代理而设置里填了代理，就带上（它要连 Anthropic）。
     */
    fun env(base: Map<String, String>, proxy: String): Map<String, String> {
        val keep = setOf("CLAUDE_CODE_GIT_BASH_PATH", "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_OAUTH_TOKEN")
        val out = base.filterKeys { k -> val u = k.uppercase(); !u.startsWith("CLAUDE") || u in keep }.toMutableMap()
        val px = proxy.trim()
        if (px.isNotEmpty() && out.keys.none { it.equals("HTTPS_PROXY", ignoreCase = true) }) {
            val url = if (px.startsWith("http")) px else "http://$px"
            out["HTTPS_PROXY"] = url
            out["HTTP_PROXY"] = url
            if (out.keys.none { it.equals("NO_PROXY", ignoreCase = true) }) out["NO_PROXY"] = "localhost,127.0.0.1"
        }
        return out
    }

    /** Claude Code 的 TodoWrite（{todos:[{content,status}]}）换成我们待办卡片的几行。 */
    fun todos(input: JsonObject): String = (input["todos"] as? JsonArray).orEmpty().mapNotNull { t ->
        val o = t as? JsonObject ?: return@mapNotNull null
        val mark = when (o.s("status")) { "completed" -> "x"; "in_progress" -> ">"; else -> " " }
        o.s("content").takeIf { it.isNotBlank() }?.let { "[$mark] $it" }
    }.joinToString("\n")
}

/**
 * 一个正在跑的 claude 进程。输出由一个线程一行一行读进通道（读管道会卡住、不能取消；取消协程时 close() 杀进程，线程就退出了）。
 */
class ClaudeProcess(command: List<String>, dir: File, env: Map<String, String>) : AutoCloseable {
    private val process: Process = ProcessBuilder(command).directory(dir).also { pb ->
        pb.environment().clear(); pb.environment().putAll(env)
    }.start()
    val lines = Channel<String>(Channel.UNLIMITED)
    private val err = StringBuilder()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

    init {
        Thread {
            runCatching { process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { lines.trySendBlocking(it) } }
            lines.close()
        }.apply { isDaemon = true; name = "claude-out" }.start()
        Thread {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { l -> synchronized(err) { if (err.length < 8000) err.append(l).append('\n') } }
            }
        }.apply { isDaemon = true; name = "claude-err" }.start()
    }

    @Synchronized
    fun send(line: String) {
        runCatching { writer.write(line); writer.write("\n"); writer.flush() }
    }

    /** 关掉它的输入：这一轮做完它就自己退出。 */
    @Synchronized
    fun closeInput() { runCatching { writer.close() } }

    fun stderrTail(): String = synchronized(err) { err.toString().trim().takeLast(1500) }

    /** 关了输入以后等它自己退出（让它把会话记录写完）。 */
    fun waitExit(ms: Long) { runCatching { process.waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS) } }

    fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    /** 连同它开的子进程（它跑的命令）一起杀掉。 */
    override fun close() {
        closeInput()
        // ProcessHandle 是 Java 9 的，安卓编译不认：反射去调（只在电脑上真跑）
        runCatching {
            val ph = Class.forName("java.lang.ProcessHandle")
            val handle = Process::class.java.getMethod("toHandle").invoke(process)
            @Suppress("UNCHECKED_CAST")
            val kids = ph.getMethod("descendants").invoke(handle) as java.util.stream.Stream<Any>
            val kill = ph.getMethod("destroyForcibly")
            kids.toArray().forEach { k -> runCatching { kill.invoke(k) } }
        }
        process.destroyForcibly()
    }
}
