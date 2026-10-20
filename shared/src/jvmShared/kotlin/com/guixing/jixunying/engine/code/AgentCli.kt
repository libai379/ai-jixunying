package com.guixing.jixunying.engine.code

import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 本机的 Codex、Gemini CLI（2026-10-20，用户：「按甲做」；docs/Code栏设计说明.md 第 17 节）。
 * 和 Claude Code 一样不碰凭据：启动用户自己装、自己登录的官方命令行，只跟它的标准输入输出说话。
 *
 * 用的是无界面模式（本机实测 codex-cli 0.153.2、Gemini CLI 0.52.0，docs/参考资料.md「Codex / Gemini 命令行无界面模式」）：
 * - codex exec --json -s <沙箱> --skip-git-repo-check -   （「-」= 任务从标准输入读）
 * - gemini -p . --output-format stream-json --approval-mode <档> --skip-trust   （-p 的字接在标准输入后面）
 * 任务都走标准输入（经 cmd.exe 转一手，参数里的引号、中文会被弄坏），写完就关掉输入。
 * 两家都不能一步步来问权限、也不会在改文件之前通知：改了什么由 CodeAgent 事前快照、事后比对得出（engine/code/Snapshot）。
 */
object AgentCli {

    sealed interface Event
    data class Session(val id: String) : Event
    /** delta = 一段段流出来的（Gemini）；false = 一整段话（Codex 的 agent_message）。 */
    data class Text(val text: String, val delta: Boolean) : Event
    data class Thinking(val text: String) : Event
    /** 开始用一个工具（done = false）或用完（done = true，带输出）。kind 是我们步骤的种类（read / run / edit……）。 */
    data class Tool(val id: String, val kind: String, val summary: String, val done: Boolean, val ok: Boolean = true, val output: String = "") : Event
    /** 不致命的提示（Codex 的 error 条目，比如「没有这个模型的资料」）。 */
    data class Note(val text: String) : Event
    data class Done(val error: String?, val usage: Usage) : Event

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(agent: String, line: String): List<Event> {
        val o = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return emptyList()
        return if (agent == CodeSpec.CODEX) codex(o) else gemini(o)
    }

    // ———————— Codex：thread.started → turn.started → item.started / item.completed → turn.completed / turn.failed ————————

    private fun codex(o: JsonObject): List<Event> = when (o.s("type")) {
        "thread.started" -> listOf(Session(o.s("thread_id")))
        "item.started", "item.completed" -> {
            val done = o.s("type") == "item.completed"
            val it = o["item"] as? JsonObject
            if (it == null) emptyList() else when (it.s("type")) {
                "agent_message" -> if (done) listOfNotNull(it.s("text").takeIf { t -> t.isNotBlank() }?.let { t -> Text(t, false) }) else emptyList()
                "reasoning" -> if (done) listOfNotNull(it.s("text").takeIf { t -> t.isNotBlank() }?.let { t -> Thinking(t) }) else emptyList()
                "command_execution" -> {
                    val code = (it["exit_code"] as? JsonPrimitive)?.intOrNull
                    listOf(Tool(it.s("id"), "run", shortCommand(it.s("command")), done,
                        ok = !done || (code == 0 && it.s("status") != "failed"),
                        output = if (done) it.s("aggregated_output").trim() + (if (code != null && code != 0) "\n（退出码 $code）" else "") else ""))
                }
                "file_change" -> {
                    val paths = (it["changes"] as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonObject)?.s("path")?.takeIf { p -> p.isNotBlank() } }
                    listOf(Tool(it.s("id"), "edit", paths.joinToString("、").ifBlank { "改文件" }, done, ok = it.s("status") != "failed"))
                }
                "web_search" -> listOf(Tool(it.s("id"), "search", it.s("query").ifBlank { "联网搜索" }, done))
                "mcp_tool_call" -> listOf(Tool(it.s("id"), "run", "${it.s("server")} ${it.s("tool")}".trim(), done, ok = it.s("status") != "failed"))
                "todo_list" -> emptyList()
                "error" -> if (done) listOf(Note(it.s("message"))) else emptyList()
                else -> emptyList()
            }
        }
        "turn.completed" -> {
            val u = o["usage"] as? JsonObject
            fun n(k: String) = (u?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
            listOf(Done(null, Usage(n("input_tokens"), n("output_tokens") + n("reasoning_output_tokens"), n("cached_input_tokens"))))
        }
        "turn.failed" -> listOf(Done(errorText((o["error"] as? JsonObject)?.s("message").orEmpty()), Usage()))
        // 顶层 error 后面一般跟着 turn.failed（10-20 实测：不存在的模型），这里只当提示
        "error" -> listOf(Note(errorText(o.s("message"))))
        else -> emptyList()
    }

    /**
     * Codex 跑的命令是「"…\powershell.exe" -Command '真正的命令'」或者「-Command "真正的命令"」（里面的双引号写成 \"，10-20 实测两种都有）：
     * 只留里面那条，好看一点。
     */
    fun shortCommand(c: String): String {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val head = """^"?[^"]*?powershell(?:\.exe)?"?\s+-Command\s+"""
        Regex("$head'(.*)'$", opts).find(c.trim())?.let { return it.groupValues[1].replace("''", "'") }
        Regex("$head\"(.*)\"$", opts).find(c.trim())?.let { return it.groupValues[1].replace("\\\"", "\"") }
        return c
    }

    /** Gemini 自己记思路用的工具，不读不写，不显示。 */
    private val GEMINI_HIDDEN = setOf("update_topic")

    /** 报错常是一整段 JSON 字符串（{"type":"error","error":{"message":…}}）：取出里面的 message。 */
    private fun errorText(raw: String): String {
        val inner = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
        return (inner["error"] as? JsonObject)?.s("message")?.takeIf { it.isNotBlank() } ?: inner.s("message").ifBlank { raw }
    }

    // ———————— Gemini：init → message → tool_use / tool_result → result ————————

    private fun gemini(o: JsonObject): List<Event> = when (o.s("type")) {
        "init" -> listOf(Session(o.s("session_id")))
        "message" -> if (o.s("role") != "assistant") emptyList()
            else listOfNotNull(o.s("content").takeIf { it.isNotEmpty() }?.let { Text(it, (o["delta"] as? JsonPrimitive)?.contentOrNull == "true") })
        "tool_use" -> {
            val p = o["parameters"] as? JsonObject ?: JsonObject(emptyMap())
            val name = o.s("tool_name")
            if (name in GEMINI_HIDDEN) return emptyList()
            val summary = p.s("file_path").ifBlank { p.s("command") }.ifBlank { p.s("pattern") }.ifBlank { p.s("dir_path") }
                .ifBlank { p.s("query") }.ifBlank { p.s("url") }.ifBlank { "$name ${p.toString().take(200)}" }
            listOf(Tool(o.s("tool_id"), geminiKind(name), summary, false))
        }
        "tool_result" -> {
            val ok = o.s("status") == "success"
            val out = o.s("output").ifBlank { (o["error"] as? JsonObject)?.s("message").orEmpty() }
            listOf(Tool(o.s("tool_id"), "", "", true, ok, out))
        }
        "result" -> {
            val st = o["stats"] as? JsonObject
            fun n(k: String) = (st?.get(k) as? JsonPrimitive)?.intOrNull ?: 0
            val usage = Usage(n("input_tokens"), n("output_tokens"), n("cached"), (st?.get("duration_ms") as? JsonPrimitive)?.longOrNull ?: 0)
            val err = if (o.s("status") == "success") null else (o["error"] as? JsonObject)?.s("message")?.ifBlank { null } ?: "Gemini CLI 没做完（${o.s("status")}）"
            listOf(Done(err, usage))
        }
        "error" -> listOf(Note(o.s("message")))
        else -> emptyList()
    }

    private fun geminiKind(name: String) = when (name) {
        "read_file", "read_many_files" -> "read"
        "write_file" -> "write"
        "replace", "edit" -> "edit"
        "list_directory" -> "list"
        "glob" -> "find"
        "search_file_content", "grep" -> "grep"
        "google_web_search", "web_search" -> "search"
        "web_fetch" -> "fetch"
        else -> "run"
    }

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    // ———————————————— 怎么启动 ————————————————

    /** 找 codex / gemini：设置里填了就用它；否则在 PATH 里找，再看 npm 装的位置（%APPDATA%\npm）。 */
    fun findExe(agent: String, configured: String, env: Map<String, String> = System.getenv()): File? {
        configured.trim().trim('"').takeIf { it.isNotEmpty() }?.let { return File(it).takeIf { f -> f.isFile } }
        val base = if (agent == CodeSpec.CODEX) "codex" else "gemini"
        val names = listOf("$base.exe", "$base.cmd", base)
        val path = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        for (dir in path.split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
            for (n in names) File(dir.trim('"'), n).takeIf { it.isFile }?.let { return it }
        }
        return env.entries.firstOrNull { it.key.equals("APPDATA", ignoreCase = true) }?.value
            ?.let { File(it, "npm/$base.cmd") }?.takeIf { it.isFile }
    }

    /**
     * 启动参数。readOnly = 计划档或审查角色：Codex 只读沙箱、Gemini 的 plan；
     * full = 「全部放开」：Codex danger-full-access、Gemini yolo；其余 Codex workspace-write、Gemini auto_edit（不弹就跳过要批准的工具）。
     */
    fun args(agent: String, model: String, readOnly: Boolean, full: Boolean, addDirs: List<String>): List<String> = buildList {
        if (agent == CodeSpec.CODEX) {
            addAll(listOf("exec", "--json", "--skip-git-repo-check", "-s", if (readOnly) "read-only" else if (full) "danger-full-access" else "workspace-write"))
            if (model.isNotBlank()) addAll(listOf("-m", model.trim()))
            if (!readOnly) for (d in addDirs) addAll(listOf("--add-dir", d))
            add("-")
        } else {
            addAll(listOf("-p", ".", "--output-format", "stream-json", "--skip-trust",
                "--approval-mode", if (readOnly) "plan" else if (full) "yolo" else "auto_edit"))
            if (model.isNotBlank()) addAll(listOf("-m", model.trim()))
            for (d in addDirs) addAll(listOf("--include-directories", d))
        }
    }
}
