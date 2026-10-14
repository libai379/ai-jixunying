package com.guixing.jixunying.engine

import com.guixing.jixunying.engine.code.CodeBackup
import com.guixing.jixunying.engine.code.CodeGuard
import com.guixing.jixunying.engine.code.CodeRunner
import com.guixing.jixunying.engine.code.CodeRunnerRules
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.FileChange
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.model.Usage
import com.guixing.jixunying.model.UsageKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 编码循环（docs/Code栏设计说明.md 第 3、4 节，2026-10-12）：用户发一条任务进来，
 * 组装会话历史 + 工具说明 → 模型带工具回答 → 结果回给模型 → 重复，直到模型不再调工具、用户点停止、或者到了最大步数。
 *
 * 安全都在程序里卡（不靠提示词）：
 * - 路径一律先过 CodeGuard.resolve，越界就不碰文件，把「在项目文件夹外面」当工具结果回给模型；
 * - 改文件之前先 CodeBackup.save 存原样（备份目录 <数据目录>/code/<对话 id>/）；
 * - 权限档：手动 = 改文件、跑命令都先等批准；接受编辑 = 只有跑命令等；计划 = 工具里根本不给写文件的工具，跑命令要批准；
 * - 等批准时消息状态保持 STREAMING、这一步 ToolStep.approval = "waiting"，界面显示要写的内容 / 差异 / 命令；
 *   拒绝 = 不执行，把「用户拒绝了」当工具结果回给模型；点停止时等批准的按拒绝处理（绝不超时自动放行）。
 *
 * 一个会话同一时间只跑一个循环：在跑的时候又发任务就排队，前一个跑完接着跑。
 */
class CodeAgent(private val host: CodeHost) {

    /** 一个会话一份：正在跑的（job）、排队等着的。 */
    private class Run(val convId: String) {
        val queued = ArrayDeque<Message>()
        var job: Job? = null
        var running = false
    }

    /** 一步正在等用户批准。 */
    private class Waiting(val convId: String, val done: CompletableDeferred<Boolean>)

    private val runs = ConcurrentHashMap<String, Run>()
    private val waitings = ConcurrentHashMap<String, Waiting>()

    /** 用户发了一条任务：没人跑就直接跑，有人在跑就排队（一个会话同时只跑一个循环）。 */
    fun submit(convId: String, task: Message) {
        val run = runs.getOrPut(convId) { Run(convId) }
        synchronized(run) {
            if (run.running) {
                run.queued.add(task)
                return
            }
            run.running = true
        }
        start(run, task)
    }

    /**
     * 停下这个会话正在跑的循环：等批准的按拒绝处理（先把等的都拒掉，再取消协程；
     * 模型请求和正在跑的命令由 CodeRunner 在协程被取消时杀干净），排队的也一起丢掉。
     */
    fun stop(convId: String) {
        waitings.entries.filter { it.value.convId == convId }.forEach { it.value.done.complete(false) }
        val run = runs[convId] ?: return
        synchronized(run) { run.queued.clear() }
        host.cancel(convId)
    }

    /** 用户点了允许 / 拒绝某一步。找不到（已经不在等了）返回 false。 */
    fun decide(convId: String, stepId: String, allow: Boolean): Boolean {
        val w = waitings[stepId] ?: return false
        if (w.convId != convId) return false
        w.done.complete(allow)
        return true
    }

    private fun start(run: Run, task: Message) {
        run.job = host.launch(run.convId) {
            try {
                loop(run.convId, task)
            } finally {
                val next = synchronized(run) { run.queued.removeFirstOrNull() }
                if (next == null) synchronized(run) { run.running = false } else start(run, next)
            }
        }
    }

    // ———————————————— 编码循环 ————————————————

    private suspend fun loop(convId: String, task: Message) {
        val spec = host.state.conversation(convId)?.code ?: return
        val provider = host.state.provider(spec.providerId)
        // 回复也用一条普通消息：和聊天一样流式推给界面（工具步骤挂在 tools 上）
        val msg = Message(
            host.newId(), convId, Role.AI, CODE_ID, status = MsgStatus.STREAMING, createdAt = host.now(),
            modelLabel = if (provider == null) spec.modelId else "${spec.modelId}（${provider.name}）",
        )
        host.addMessage(msg)
        val bad = providerProblem(provider)
        if (bad != null) {
            host.updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = bad) }
            return
        }
        val p = provider!!
        val guard = CodeGuard(File(spec.folder))
        val backup = host.backupOf(convId)
        var usage = Usage()
        try {
            val working = mutableListOf<JsonObject>()
            working += systemMsg(spec, host.state.settings.code)
            working += history(convId, task, msg.id)
            var steps = 0
            var reachedMax = false
            while (true) {
                val mode = host.state.conversation(convId)?.code?.mode ?: spec.mode
                val settings = host.state.settings.code
                val r = withContext(UsageTag(UsageKinds.CODE, convId = convId)) {
                    host.llmClient.chat(p, spec.modelId, working, null, toolsFor(mode), null, {}, null) { c, rs ->
                        host.appendDelta(convId, msg.id, c, rs)
                    }
                }
                usage = Usage(usage.prompt + r.usage.prompt, usage.completion + r.usage.completion,
                    usage.cached + r.usage.cached, usage.millis + r.usage.millis)
                if (r.toolCalls.isEmpty()) break
                working += assistantMsg(r, p)
                var full = false
                for (tc in r.toolCalls) {
                    if (steps >= settings.maxSteps) { full = true; break }
                    steps++
                    working += toolMsg(tc.id, step(convId, msg.id, tc, mode, spec, guard, backup))
                }
                if (full) { reachedMax = true; break }
                trimWorking(working)
            }
            val tail = if (reachedMax) "\n\n（到了最多 ${host.state.settings.code.maxSteps} 步，停下了）" else ""
            host.updateMessage(convId, msg.id) { it.copy(content = (it.content + tail).trim(), status = MsgStatus.DONE, usage = usage) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { host.updateMessage(convId, msg.id) { it.copy(status = MsgStatus.STOPPED) } }
            throw e
        } catch (e: Throwable) {
            host.updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = host.friendlyError(e, p)) }
        }
    }

    /** 给模型的系统说明：只讲环境（项目文件夹、外壳、有哪些工具），不加限制词。 */
    private fun systemMsg(spec: CodeSpec, code: CodeSettings): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", buildString {
            append("你在帮用户改一个项目里的代码。\n")
            append("项目文件夹：").append(spec.folder).append("（工具里的 path 都写相对它的路径，比如 src/main.kt）\n")
            append("当前外壳：").append(shellLabel(code.shell)).append("；命令的工作目录就是项目文件夹；单条命令最多跑 ")
            append(code.commandTimeoutSec).append(" 秒，超时会把进程连同它的子进程杀掉。\n")
            append("系统是 Windows，文件按 UTF-8 读写。\n")
            append("可以用的工具：read_file（读文件，可指定行范围）、list_dir（列目录）、grep（按内容查找）")
            if (spec.mode != CodeMode.PLAN) append("、write_file（新建或整个重写）、edit_file（局部替换）")
            append("、run_command（跑一条命令，返回退出码和输出）。")
        })
    }

    private fun shellLabel(shell: String) = when (shell.trim().lowercase()) {
        "", "powershell" -> "PowerShell"
        "cmd" -> "cmd"
        else -> shell.trim()
    }

    /** 会话历史：用户的话当 user，自己的话当 assistant；太长就从最早的开始丢，并在开头说明。 */
    private fun history(convId: String, task: Message, excludeId: String): List<JsonObject> {
        val all = host.messagesOf(convId)
        val end = all.indexOfFirst { it.id == task.id }.let { if (it < 0) all.size else it + 1 }
        val usable = all.subList(0, end).filter {
            it.id != excludeId && it.role != Role.SYSTEM && it.status != MsgStatus.STREAMING &&
                it.status != MsgStatus.ERROR && it.content.isNotBlank()
        }
        val picked = ArrayDeque<Message>()
        var used = 0
        for (m in usable.asReversed()) {
            val cost = m.content.length + 200
            if (used + cost > HISTORY_BUDGET && picked.isNotEmpty()) break
            used += cost
            picked.addFirst(m)
        }
        val parts = mutableListOf<JsonObject>()
        if (picked.size < usable.size) parts += buildJsonObject { put("role", "user"); put("content", "（更早的记录太长，已省略）") }
        for (m in picked) {
            val role = if (m.role == Role.USER) "user" else "assistant"
            // 以前回答里的 <think>…</think>（MiniMax 写在正文里）不再发回去，白占 token（10-12 真模型测试看到的；聊天那边也这么做）
            val text = if (role == "assistant") m.content.replace(THINK, "").trim() else m.content
            if (text.isBlank()) continue
            // 有的接口不允许连着两条同一个角色，合并一下
            val last = parts.lastOrNull()?.str("role")
            if (last == role && parts.isNotEmpty()) {
                val prev = parts.removeAt(parts.size - 1)
                parts += buildJsonObject { put("role", role); put("content", prev.str("content").orEmpty() + "\n\n" + text) }
            } else parts += buildJsonObject { put("role", role); put("content", text) }
        }
        while (parts.firstOrNull()?.str("role") == "assistant") parts.removeAt(0)
        return parts
    }

    private fun assistantMsg(r: ChatResult, p: ProviderConfig): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", r.content)
        // 思考模式的思考内容要带回去（DeepSeek 等要求；和成员的工具循环一样）
        if (r.reasoning.isNotEmpty() && p.presetId !in setOf("openai", "anthropic", "gemini", "openrouter")) put("reasoning_content", r.reasoning)
        putJsonArray("tool_calls") {
            r.toolCalls.forEach { tc ->
                add(buildJsonObject {
                    put("id", tc.id); put("type", tc.type)
                    putJsonObject("function") { put("name", tc.name); put("arguments", tc.arguments.ifBlank { "{}" }) }
                })
            }
        }
    }

    private fun toolMsg(callId: String, result: String): JsonObject = buildJsonObject {
        put("role", "tool"); put("tool_call_id", callId); put("content", result)
    }

    /**
     * 攒得太长时，把早先的工具输出换成一句「已省略」（最近几步原样留着）；
     * 只动 role = tool 的正文，assistant 的 tool_calls 和配对关系不变，接口才认。
     */
    private fun trimWorking(working: MutableList<JsonObject>) {
        var total = working.sumOf { it.toString().length }
        if (total <= WORK_BUDGET) return
        val last = maxOf(0, working.size - KEEP_TAIL)
        for (i in 0 until last) {
            if (total <= WORK_BUDGET) break
            val o = working[i]
            if (o.str("role") != "tool") continue
            val cur = o.str("content").orEmpty()
            if (cur.length <= 200) continue
            working[i] = buildJsonObject {
                put("role", "tool"); put("tool_call_id", o.str("tool_call_id").orEmpty())
                put("content", "（这一步的输出太长，放不下了，已省略；要的话重读一次）")
            }
            total -= cur.length - 40
        }
    }

    // ———————————————— 工具 ————————————————

    private fun toolsFor(mode: CodeMode): JsonArray = buildJsonArray {
        fun prop(type: String, desc: String) = buildJsonObject { put("type", type); put("description", desc) }
        fun text(desc: String) = prop("string", desc)
        fun fn(name: String, desc: String, props: JsonObject, required: List<String>) = add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name); put("description", desc)
                putJsonObject("parameters") {
                    put("type", "object"); put("properties", props)
                    putJsonArray("required") { required.forEach { add(it) } }
                }
            }
        })
        val path = text("相对项目文件夹的路径，比如 src/main.kt")
        fn("read_file", "读一个文件的文字内容，可以只读一段。",
            buildJsonObject {
                put("path", path)
                put("start_line", prop("integer", "可选，从第几行开始读（从 1 数）"))
                put("end_line", prop("integer", "可选，读到第几行（含）"))
            }, listOf("path"))
        fn("list_dir", "列一个目录里有哪些文件和子目录。", buildJsonObject { put("path", path) }, emptyList())
        fn("grep", "按内容在项目里查找，返回命中的文件、行号和那一行。",
            buildJsonObject {
                put("pattern", text("要找的内容，正则表达式或者普通文字"))
                put("path", text("可选，只在哪个目录里找；不写就是整个项目文件夹"))
            }, listOf("pattern"))
        // 计划档：写文件和改文件这两个工具根本不给模型
        if (mode != CodeMode.PLAN) {
            fn("write_file", "新建一个文件，或者整个重写一个已有文件（原来的内容会被换掉）。",
                buildJsonObject {
                    put("path", path)
                    put("content", text("文件的全部内容"))
                }, listOf("path", "content"))
            fn("edit_file", "把已有文件里的一段文字换成另一段。old_text 在文件里必须正好出现一次，否则报错、文件不动。",
                buildJsonObject {
                    put("path", path)
                    put("old_text", text("要换掉的原文（要唯一，写长一点；包括缩进和换行）"))
                    put("new_text", text("换成什么（删掉这一段就填空字符串）"))
                }, listOf("path", "old_text", "new_text"))
        }
        fn("run_command", "在项目文件夹里跑一条命令（工作目录就是项目文件夹），返回退出码和输出。",
            buildJsonObject { put("command", text("要跑的命令")) }, listOf("command"))
    }

    /** 走一步工具：先按权限档看要不要等批准，再执行，把这一步记到消息上，返回给模型的工具结果。 */
    private suspend fun step(convId: String, msgId: String, tc: ToolCall, mode: CodeMode, spec: CodeSpec,
                             guard: CodeGuard, backup: CodeBackup): String {
        val args = runCatching { Json.parseToJsonElement(tc.arguments.ifBlank { "{}" }).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return when (tc.name) {
            "read_file" -> readFile(convId, msgId, args, guard)
            "list_dir" -> listDir(convId, msgId, args, guard)
            "grep" -> grep(convId, msgId, args, guard)
            // 计划档不给这两个工具；模型硬要调，也在这里拒掉，文件不动
            "write_file" -> if (mode == CodeMode.PLAN) planRefusal(convId, msgId, "write", args.str("path").orEmpty())
                else writeFile(convId, msgId, args, mode, guard, backup)
            "edit_file" -> if (mode == CodeMode.PLAN) planRefusal(convId, msgId, "edit", args.str("path").orEmpty())
                else editFile(convId, msgId, args, mode, guard, backup)
            "run_command" -> runCommand(convId, msgId, args, spec)
            else -> "没有这个工具：${tc.name}"
        }
    }

    private suspend fun readFile(convId: String, msgId: String, args: JsonObject, guard: CodeGuard): String {
        val raw = args.str("path").orEmpty()
        if (raw.isBlank()) return "参数错误：缺少 path"
        val file = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(file)
        if (!file.isFile) return "没有这个文件：$rel（要看目录用 list_dir）"
        if (file.length() > MAX_FILE_BYTES) return "$rel 太大（${file.length()} 字节），读不下；可以用 grep 找位置。"
        val strict = runCatching { readUtf8(file) }.getOrNull()
        val text = strict ?: runCatching { file.readText() }.getOrNull() ?: return "读不了 $rel（可能没有权限，或者不是文本）"
        val encodingNote = if (strict == null) "（注意：这个文件不是 UTF-8 编码，可能是 GBK 之类，下面的中文可能是乱码；edit_file 改不了它）\n" else ""
        val lines = text.split('\n')
        val from = (args.str("start_line")?.trim()?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val to = (args.str("end_line")?.trim()?.toIntOrNull() ?: lines.size).coerceIn(from, maxOf(from, lines.size))
        val body = lines.subList(from - 1, to.coerceAtMost(lines.size)).joinToString("\n")
        val (shown, cut) = CodeRunnerRules.clip(body, RESULT_LIMIT)
        val out = encodingNote + "$rel 第 $from～$to 行（共 ${lines.size} 行）：\n$shown" +
            if (cut) "\n（上面被截断了；可以指定 start_line / end_line 只读一段）" else ""
        addStep(convId, msgId, ToolStep("read", rel, id = host.newId(), output = shown, ok = true))
        return out
    }

    private suspend fun listDir(convId: String, msgId: String, args: JsonObject, guard: CodeGuard): String {
        val raw = args.str("path").orEmpty().ifBlank { "." }
        val dir = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(dir).ifBlank { "." }
        if (!dir.isDirectory) return "不是目录：$rel"
        val all = runCatching { dir.listFiles()?.sortedBy { it.name } }.getOrNull() ?: return "读不了这个目录：$rel"
        // 目录联接 / 符号链接指到项目外面的：照列名字，但标出来（读不了，CodeGuard 按真实路径判断）
        val shown = all.take(LIST_MAX).joinToString("\n") {
            (if (it.isDirectory) it.name + "/" else it.name) + if (guard.resolve(it.absolutePath) == null) "（指到项目外面，读不了）" else ""
        }
        val note = if (all.size > LIST_MAX) "（还有 ${all.size - LIST_MAX} 个没列出来）" else ""
        addStep(convId, msgId, ToolStep("list", rel, id = host.newId(), output = shown, ok = true))
        return "$rel 里 ${all.size} 项：\n" + shown.ifBlank { "（空目录）" } + (if (note.isNotEmpty()) "\n$note" else "")
    }

    private suspend fun grep(convId: String, msgId: String, args: JsonObject, guard: CodeGuard): String {
        val pattern = args.str("pattern").orEmpty()
        if (pattern.isBlank()) return "参数错误：缺少 pattern"
        val raw = args.str("path").orEmpty().ifBlank { "." }
        val start = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(start).ifBlank { "." }
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        // 每行最多看 2000 字：写坏了的正则在超长行上会卡很久（Kotlin 的正则停不下来）
        fun hit(line: String) = line.take(2000).let { l -> re?.containsMatchIn(l) ?: l.contains(pattern, ignoreCase = true) }
        val hits = mutableListOf<String>()
        val queue = ArrayDeque<File>().apply { add(start) }
        var scanned = 0
        while (queue.isNotEmpty() && hits.size < GREP_MAX && scanned < GREP_FILES) {
            val f = queue.removeFirst()
            // 主会话审查时补的（10-12）：遍历到的每个目录、文件都要过 CodeGuard（按真实路径判断），
            // 不然项目里一个指到外面的目录联接，就能让 grep 读到项目外的文件
            if (guard.resolve(f.absolutePath) == null) continue
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.filter { it.name != ".git" && it.name != "node_modules" }?.forEach { queue.add(it) }
            } else if (f.isFile && f.length() <= GREP_FILE_BYTES && f.extension.lowercase() !in GREP_SKIP_EXT) {
                scanned++
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                var i = 0
                for (line in text.lineSequence()) {
                    i++
                    if (hits.size >= GREP_MAX) break
                    if (hit(line)) hits += "${guard.relative(f)}:$i: ${line.trim().take(300)}"
                }
            }
        }
        val output = hits.joinToString("\n")
        addStep(convId, msgId, ToolStep("grep", "$pattern · $rel", id = host.newId(), output = output, ok = hits.isNotEmpty()))
        return if (hits.isEmpty()) "在 $rel 里没找到「$pattern」（扫了 $scanned 个文件）。"
        else "$rel 里找到 ${hits.size} 处（扫了 $scanned 个文件）：\n$output" +
            if (hits.size >= GREP_MAX) "\n（只列了前 $GREP_MAX 条，写具体一点再找）" else ""
    }

    /** 计划档：写文件的工具没有提供，模型硬调也不执行。 */
    private suspend fun planRefusal(convId: String, msgId: String, kind: String, path: String): String {
        addStep(convId, msgId, ToolStep(kind, path, id = host.newId(), ok = false, approval = "denied", note = "现在是计划档，没有提供这个工具，没有执行"))
        return "现在是计划档，这一步没有执行，文件没动。要改文件先让用户把权限档切到「手动」或「接受编辑」。"
    }

    private suspend fun writeFile(convId: String, msgId: String, args: JsonObject, mode: CodeMode,
                                  guard: CodeGuard, backup: CodeBackup): String {
        val raw = args.str("path").orEmpty()
        if (raw.isBlank()) return "参数错误：缺少 path"
        val content = args.str("content") ?: return "参数错误：缺少 content"
        val file = guard.resolve(raw) ?: return outside(raw)
        if (file.isDirectory) return "「${guard.relative(file)}」是目录，不能当文件写"
        val rel = guard.relative(file)
        val existed = file.isFile
        val before = if (existed) runCatching { file.readText() }.getOrNull() ?: return "读不了原来的 $rel（可能没有权限）" else ""
        val diff = CodeDiff.make(rel, before, content)
        val stepId = host.newId()
        val step = ToolStep("write", rel, id = stepId, output = uiText(diff.text), ok = true,
            note = if (existed) "重写这个文件" else "新建这个文件")
        // 不用批准（接受编辑档）也要先把这一步记到消息上，不然改了文件却没有改动记录，没法审阅、撤销（主会话审查 10-12 补）
        if (needsApproval(mode, "write")) { if (!waitApproval(convId, msgId, step)) return refused("写 $rel") } else addStep(convId, msgId, step)
        val created = backup.save(stepId, file)
        val err = runCatching { file.parentFile?.mkdirs(); file.writeText(content, Charsets.UTF_8) }.exceptionOrNull()
        if (err != null) {
            setStep(convId, msgId, stepId) { it.copy(ok = false, note = "没写成：${err.message}") }
            return "写不了 $rel：${err.message}"
        }
        val change = FileChange(stepId, rel, diff.added, diff.removed, created = created)
        setStep(convId, msgId, stepId) { it.copy(ok = true, output = uiText(diff.text), change = change) }
        return "写好了：$rel（${if (created) "新建" else "重写"}，+${diff.added} −${diff.removed}）"
    }

    private suspend fun editFile(convId: String, msgId: String, args: JsonObject, mode: CodeMode,
                                 guard: CodeGuard, backup: CodeBackup): String {
        val raw = args.str("path").orEmpty()
        if (raw.isBlank()) return "参数错误：缺少 path"
        val oldText = args.str("old_text") ?: return "参数错误：缺少 old_text"
        val newText = args.str("new_text") ?: return "参数错误：缺少 new_text"
        if (oldText.isEmpty()) return "参数错误：old_text 不能是空的"
        val file = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(file)
        if (!file.isFile) return "$rel 不存在，或者不是文件（要新建就用 write_file）"
        if (file.length() > MAX_FILE_BYTES) return "$rel 太大（${file.length()} 字节），改不了"
        // 严格按 UTF-8 解码：GBK 之类的老文件读成乱码再写回去会把中文弄坏，宁可不改
        val before = runCatching { readUtf8(file) }.getOrElse { return "读不了 $rel（可能没有权限）" }
            ?: return "$rel 不是 UTF-8 编码（可能是 GBK 之类），为了不把里面的中文弄坏，没有改。可以先用 run_command 把它转成 UTF-8，或者用 write_file 整个重写。"
        val count = occurrences(before, oldText)
        if (count == 0) return "文件里没有这段文字，没有改动。先用 read_file 看一下现在的文件，再写要替换的那一段。"
        if (count > 1) return "这段文字在文件里出现了 $count 次，分不清改哪一处，没有改动。把 old_text 写长一点、只匹配一处。"
        val after = before.replaceFirst(oldText, newText)
        val diff = CodeDiff.make(rel, before, after)
        val stepId = host.newId()
        val step = ToolStep("edit", rel, id = stepId, output = uiText(diff.text), ok = true, note = "替换 $rel 里的一段")
        // 同 writeFile：不用批准也要先记上这一步
        if (needsApproval(mode, "edit")) { if (!waitApproval(convId, msgId, step)) return refused("改 $rel") } else addStep(convId, msgId, step)
        val created = backup.save(stepId, file)
        val err = runCatching { file.writeText(after, Charsets.UTF_8) }.exceptionOrNull()
        if (err != null) {
            setStep(convId, msgId, stepId) { it.copy(ok = false, note = "没改成：${err.message}") }
            return "改不了 $rel：${err.message}"
        }
        val change = FileChange(stepId, rel, diff.added, diff.removed, created = created)
        setStep(convId, msgId, stepId) { it.copy(ok = true, output = uiText(diff.text), change = change) }
        return "改好了：$rel（+${diff.added} −${diff.removed}）"
    }

    private suspend fun runCommand(convId: String, msgId: String, args: JsonObject, spec: CodeSpec): String {
        val command = args.str("command").orEmpty()
        if (command.isBlank()) return "参数错误：缺少 command"
        val runner: CodeRunner = host.runner ?: return "这台设备不能跑命令。"
        val code = host.state.settings.code
        val stepId = host.newId()
        val step = ToolStep("run", command, id = stepId, ok = true)
        if (needsApproval(host.state.conversation(convId)?.code?.mode ?: spec.mode, "run")) {
            if (!waitApproval(convId, msgId, step)) return refused("跑这条命令")
        } else addStep(convId, msgId, step)
        val r = runner.run(command, File(spec.folder), code.shell, code.commandTimeoutSec, RESULT_LIMIT)
        val (shown, cut) = CodeRunnerRules.clip(r.output, RESULT_LIMIT)
        val state = when {
            r.timedOut -> "超时了（${code.commandTimeoutSec} 秒），进程连同它的子进程都杀掉了"
            r.exitCode == 0 -> "跑完了（退出码 0）"
            else -> "退出码 ${r.exitCode}"
        }
        setStep(convId, msgId, stepId) { it.copy(ok = r.exitCode == 0, output = shown, note = state) }
        return buildString {
            append("命令：").append(command).append('\n').append(state)
            if (r.truncated || cut) append("；输出太长，下面是被截断后的")
            append('\n').append(shown)
        }
    }

    // ———————————————— 批准 ————————————————

    private fun needsApproval(mode: CodeMode, kind: String): Boolean = when (kind) {
        "write", "edit" -> mode == CodeMode.MANUAL
        "run" -> true
        else -> false
    }

    /**
     * 把这一步推出去等用户批准（approval = waiting，消息状态仍是 STREAMING），返回用户允不允许。
     * 每一步只等它自己那一次 CodeDecide，不超时、不自动放行；点停止时按拒绝处理。
     */
    private suspend fun waitApproval(convId: String, msgId: String, step: ToolStep): Boolean {
        val done = CompletableDeferred<Boolean>()
        waitings[step.id] = Waiting(convId, done)
        addStep(convId, msgId, step.copy(approval = "waiting"))
        val allow = try {
            done.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                setStep(convId, msgId, step.id) { it.copy(approval = "denied", ok = false, note = "停下来时这一步没有做") }
            }
            throw e
        } finally {
            waitings.remove(step.id)
        }
        setStep(convId, msgId, step.id) {
            it.copy(approval = if (allow) "allowed" else "denied", ok = allow, note = if (allow) it.note else "用户拒绝了这一步")
        }
        return allow
    }

    private fun refused(what: String) = "用户拒绝了这一步（$what），没有做。不要原样重来，可以换个做法，或者问用户怎么改。"

    // ———————————————— 改动：差异 / 接受 / 撤销 ————————————————

    /** 看某一处改动改了什么：拿备份里的原样和现在的文件算差异。 */
    suspend fun diff(convId: String, changeId: String): CommandResult {
        val found = findChange(convId, changeId) ?: return CommandResult(false, "找不到这处改动")
        val guard = guardOf(convId) ?: return CommandResult(false, "这不是 Code 会话")
        val file = guard.resolve(found.second.path) ?: return CommandResult(false, "这个文件已经不在项目文件夹里了")
        val before = withContext(Dispatchers.IO) { host.backupOf(convId).original(changeId)?.toString(Charsets.UTF_8) }
        val now = withContext(Dispatchers.IO) { runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("") }
        val r = CodeDiff.make(found.second.path, before ?: "", now)
        return CommandResult(data = r.text)
    }

    /**
     * 接受或拒绝一处改动。拒绝 = 把文件恢复成改之前的样子（原来没有的文件就删掉）。
     * 主会话审查时补的（10-12）：同一个文件在这处之后又被改过的，恢复这处的备份会把后面那几次也一起撤掉，
     * 所以后面那几处一起标成「拒绝」，免得界面上还显示没处理、实际文件里已经没有了。已经是这个结果的再点一次不做事。
     */
    suspend fun review(convId: String, changeId: String, accept: Boolean): CommandResult {
        val found = findChange(convId, changeId) ?: return CommandResult(false, "找不到这处改动")
        val want = if (accept) "accepted" else "rejected"
        if (found.second.review == want) return CommandResult(message = if (accept) "已经接受过了" else "已经拒绝过了")
        val guard = guardOf(convId) ?: return CommandResult(false, "这不是 Code 会话")
        if (accept) {
            if (found.second.review == "rejected") return CommandResult(false, "这处改动已经撤销了，文件里已经没有它了")
            markReview(convId, changeId, "accepted")
            return CommandResult(message = "接受了这处改动")
        }
        val file = guard.resolve(found.second.path) ?: return CommandResult(false, "这个文件已经不在项目文件夹里了")
        val ok = withContext(Dispatchers.IO) { host.backupOf(convId).restore(changeId, file) }
        if (!ok) return CommandResult(false, "这处改动的备份不在了，没法恢复")
        val later = laterChanges(convId, setOf(changeId), found.second.path)
        markReview(convId, changeId, "rejected")
        later.forEach { markReview(convId, it.id, "rejected") }
        return CommandResult(message = "拒绝了这处改动，文件已经恢复成改之前的样子" +
            if (later.isNotEmpty()) "（这个文件后面的 ${later.size} 处改动也一起撤掉了）" else "")
    }

    /** 撤销这一条回答里的全部改动：从后往前一个一个恢复，都记成拒绝了；同一文件在这之后的改动也一起标成拒绝。 */
    suspend fun revertTask(convId: String, messageId: String): CommandResult {
        val guard = guardOf(convId) ?: return CommandResult(false, "这不是 Code 会话")
        val msg = host.messagesOf(convId).firstOrNull { it.id == messageId } ?: return CommandResult(false, "找不到这条回答")
        val changes = msg.tools.mapNotNull { it.change }.filter { it.review != "rejected" }
        if (changes.isEmpty()) return CommandResult(false, "这条回答没有要撤销的改动")
        val backup = host.backupOf(convId)
        var done = 0
        // 从后往前：同一个文件改了好几次时，反着恢复才能回到最早的样子
        for (c in changes.asReversed()) {
            val file = guard.resolve(c.path) ?: continue
            if (withContext(Dispatchers.IO) { backup.restore(c.id, file) }) done++
        }
        val ids = changes.map { it.id }.toSet()
        val later = changes.map { it.path }.distinct().flatMap { laterChanges(convId, ids, it) }.distinctBy { it.id }
        changes.forEach { markReview(convId, it.id, "rejected") }
        later.forEach { markReview(convId, it.id, "rejected") }
        return CommandResult(message = "撤销了这条回答里的 $done 处改动，文件回到了改之前的样子" +
            if (later.isNotEmpty()) "（这些文件在后面回答里的 ${later.size} 处改动也一起撤掉了）" else "")
    }

    /** 会话里按时间顺序排在 ids 里最早那一处之后、改的是同一个文件、还没拒绝的改动。 */
    private fun laterChanges(convId: String, ids: Set<String>, path: String): List<FileChange> {
        val all = host.messagesOf(convId).flatMap { m -> m.tools.mapNotNull { it.change } }
        val first = all.indexOfFirst { it.id in ids }
        if (first < 0) return emptyList()
        return all.drop(first + 1).filter { it.id !in ids && it.path == path && it.review != "rejected" }
    }

    /** 改一处改动的审阅结果（挂在这一步的 ToolStep.change 上）。 */
    private fun markReview(convId: String, changeId: String, review: String) {
        val list = host.messagesOf(convId)
        for (m in list) {
            if (m.tools.none { it.change?.id == changeId }) continue
            host.updateMessage(convId, m.id) { msg ->
                msg.copy(tools = msg.tools.map { if (it.change?.id == changeId) it.copy(change = it.change.copy(review = review)) else it })
            }
            return
        }
    }

    /** 在会话的历史里按改动编号找一处改动。 */
    private fun findChange(convId: String, changeId: String): Pair<Message, FileChange>? =
        host.messagesOf(convId).asReversed().firstNotNullOfOrNull { m ->
            m.tools.firstNotNullOfOrNull { t -> t.change?.takeIf { it.id == changeId } }?.let { m to it }
        }

    private fun guardOf(convId: String): CodeGuard? {
        val spec = host.state.conversation(convId)?.code ?: return null
        return runCatching { CodeGuard(File(spec.folder)) }.getOrNull()
    }

    // ———————————————— 零碎 ————————————————

    private suspend fun addStep(convId: String, msgId: String, step: ToolStep) {
        host.updateMessage(convId, msgId) { it.copy(tools = it.tools + step) }
    }

    private fun setStep(convId: String, msgId: String, stepId: String, transform: (ToolStep) -> ToolStep) {
        host.updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) transform(it) else it }) }
    }

    /** 界面里给这一步看的内容（要写的内容 / 差异），太长了截一下。 */
    private fun uiText(text: String) = CodeRunnerRules.clip(text, UI_TEXT_LIMIT).first

    private fun outside(raw: String) = "路径在项目文件夹外面或不合法：$raw。工具里的 path 要写这个项目里的路径。"

    /** 严格按 UTF-8 解码；不是合法的 UTF-8（GBK 之类的老文件）返回 null。读不了（没权限）抛异常。 */
    private fun readUtf8(file: File): String? {
        val bytes = file.readBytes()
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    private fun occurrences(text: String, needle: String): Int {
        var n = 0
        var i = text.indexOf(needle)
        while (i >= 0) {
            n++
            i = text.indexOf(needle, i + needle.length)
        }
        return n
    }

    private fun providerProblem(p: ProviderConfig?): String? = when {
        p == null -> "服务商已经不在了：到 设置 → 模型服务 看一下"
        !p.enabled -> "服务商「${p.name}」已停用：到 设置 → 模型服务 把它的开关打开"
        p.apiKey.isBlank() && !p.baseUrl.contains("127.0.0.1") && !p.baseUrl.contains("localhost") -> "服务商「${p.name}」还没填 API Key：到 设置 → 模型服务 填写"
        else -> null
    }

    companion object {
        private val THINK = Regex("<think>[\\s\\S]*?</think>")
        /** 给模型的工具结果最长多少字。 */
        private const val RESULT_LIMIT = 12_000
        /** 界面上一步显示的内容最长多少字。 */
        private const val UI_TEXT_LIMIT = 20_000
        /** 单个文件多大就不读了。 */
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val LIST_MAX = 200
        private const val GREP_MAX = 60
        private const val GREP_FILES = 2000
        private const val GREP_FILE_BYTES = 1L * 1024 * 1024
        /** 这些扩展名按内容找没意义（二进制）。 */
        private val GREP_SKIP_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "zip", "jar", "class", "exe", "dll", "so", "pdf", "docx", "xlsx", "pptx", "mp3", "mp4", "ttf", "woff", "woff2")
        /** 发给模型的会话历史最多多少字。 */
        private const val HISTORY_BUDGET = 48_000
        /** 一轮里攒的工具结果最多多少字，超了把早先的输出省略掉。 */
        private const val WORK_BUDGET = 150_000
        /** 省略时最近几步原样留着。 */
        private const val KEEP_TAIL = 10
        // 手机发来的指令怎么拦：见 engine/code/PhoneGate（安全核心）
    }
}

/**
 * 编码循环要用到的引擎能力（由 Engine 实现）。单独抽出来是为了让循环只碰这几样，
 * 不直接去翻引擎内部：消息怎么存、怎么推给界面、文件改前的备份放哪，都由引擎管。
 */
interface CodeHost {
    val state: AppState
    /** 跑命令的执行器；手机上（本机）是 null。 */
    val runner: CodeRunner?
    val llmClient: LlmClient
    fun backupOf(convId: String): CodeBackup
    fun messagesOf(convId: String): List<Message>
    fun newId(): String
    fun now(): Long
    fun addMessage(m: Message)
    fun updateMessage(convId: String, id: String, persist: Boolean = true, transform: (Message) -> Message): Message?
    fun appendDelta(convId: String, id: String, content: String, reasoning: String)
    /** 起一个协程跑循环，并登记成这个会话的活（点停止、删会话时一起取消）。 */
    fun launch(convId: String, block: suspend () -> Unit): Job
    fun cancel(convId: String)
    fun friendlyError(e: Throwable, p: ProviderConfig? = null): String
}
