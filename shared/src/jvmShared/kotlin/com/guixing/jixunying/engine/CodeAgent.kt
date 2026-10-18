package com.guixing.jixunying.engine

import com.guixing.jixunying.engine.code.CodeBackup
import com.guixing.jixunying.engine.code.CodeCommands
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
import com.guixing.jixunying.model.PersonalSettings
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.model.Usage
import com.guixing.jixunying.model.UsageKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
 * - 路径一律先过 CodeGuard.resolve（不合法的直接拒）；2026-10-17 起照 Claude Code 分工作区里外：
 *   工作区（项目文件夹 + 加进来的）里读不问，外面的读、写每次都先问（哪一档都问，「接受编辑」也只放行工作区里的改动）；
 * - 改文件之前先 CodeBackup.save 存原样（备份目录 <数据目录>/code/<对话 id>/）；
 * - 权限档：手动 = 改文件、跑命令都先等批准；接受编辑 = 只有跑命令等；计划 = 工具里根本不给写文件的工具，跑命令要批准；
 * - 命令：批准时可以选「同样开头这个会话里不再问」（engine/code/CodeCommands：拼起来的、危险的永远要问）；
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

    /** 一步正在等用户批准。done 的结果：允不允许 + remember（"" / "dir" / "command"，见 Command.CodeDecide）。 */
    private class Waiting(val convId: String, val done: CompletableDeferred<Pair<Boolean, String>>)

    private val runs = ConcurrentHashMap<String, Run>()
    private val waitings = ConcurrentHashMap<String, Waiting>()

    /** 手机发起的任务（消息编号）：这些任务里读工作区外的文件照旧要问（2026-10-17，电脑上发起的读不问）。 */
    private val phoneTasks = ConcurrentHashMap.newKeySet<String>()

    /** 每个会话正在做的那条任务的文字（「自动」档给检查员看用户这次要什么）。 */
    private val currentTask = ConcurrentHashMap<String, String>()

    /** 这一轮是手机发起的（放在协程上下文里，往下的工具都看得到）。 */
    private class PhoneTask : kotlin.coroutines.AbstractCoroutineContextElement(PhoneTask) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<PhoneTask>
    }

    /** 用户发了一条任务：没人跑就直接跑，有人在跑就排队（一个会话同时只跑一个循环）。 */
    fun submit(convId: String, task: Message, fromPhone: Boolean = false) {
        if (fromPhone) phoneTasks += task.id
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
        waitings.entries.filter { it.value.convId == convId }.forEach { it.value.done.complete(false to "") }
        val run = runs[convId] ?: return
        synchronized(run) { run.queued.clear() }
        host.cancel(convId)
    }

    /** 用户点了允许 / 拒绝某一步。找不到（已经不在等了）返回 false。 */
    fun decide(convId: String, stepId: String, allow: Boolean, remember: String = ""): Boolean {
        val w = waitings[stepId] ?: return false
        if (w.convId != convId) return false
        w.done.complete(allow to (if (allow) remember else ""))
        return true
    }

    private fun start(run: Run, task: Message) {
        run.job = host.launch(run.convId) {
            try {
                withContext(if (phoneTasks.remove(task.id)) PhoneTask() else kotlin.coroutines.EmptyCoroutineContext) { loop(run.convId, task) }
                host.compactLater(run.convId)
            } finally {
                val next = synchronized(run) { run.queued.removeFirstOrNull() }
                if (next == null) synchronized(run) { run.running = false } else start(run, next)
            }
        }
    }

    // ———————————————— 编码循环 ————————————————

    private suspend fun loop(convId: String, task: Message) {
        val spec = host.state.conversation(convId)?.code ?: return
        currentTask[convId] = task.content
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
        val backup = host.backupOf(convId)
        var usage = Usage()
        try {
            val working = mutableListOf<JsonObject>()
            // 模型能不能看图（设置 → 模型服务 里的「能看图」）：能看就把用户发的图片、read_file 读到的图片发过去
            val vision = p.models.firstOrNull { it.id == spec.modelId }?.vision == true
            val notes = withContext(Dispatchers.IO) { projectNotes(File(spec.folder)) }
            working += systemMsg(spec, host.state.settings.code, host.state.settings.personal, notes)
            working += history(convId, task, msg.id, vision)
            // 读了哪些项目说明，记一步给用户看（和 Claude Code 开会话时读 CLAUDE.md 一样，每次任务开头读）
            if (notes.isNotEmpty()) addStep(convId, msg.id, ToolStep("read", notes.joinToString("、") { it.first }, id = host.newId(),
                output = notes.joinToString("\n\n") { "—— ${it.first} ——\n${it.second}" }.take(UI_TEXT_LIMIT), ok = true, note = "项目说明，每次开始自动读"))
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
                // 这一轮 read_file 读到的图片：工具结果里放不了图片（多数接口只认文字），等这一轮的工具结果都回完，
                // 再补一条 user 消息把图片带上
                val seen = mutableListOf<Pair<String, String>>()
                // 子代理（2026-10-18）：这一轮派的几个一起跑，结果按顺序接回去
                val subs = r.toolCalls.filter { it.name == "spawn_agent" }
                val subResults = if (subs.isEmpty()) emptyMap() else kotlinx.coroutines.coroutineScope {
                    subs.associate { tc -> tc.id to async { subAgent(convId, msg.id, tc, p, spec, backup, vision) } }
                        .mapValues { it.value.await() }
                }
                for (tc in r.toolCalls) {
                    if (steps >= settings.maxSteps) { full = true; break }
                    steps++
                    subResults[tc.id]?.let { working += toolMsg(tc.id, it); continue }
                    // 工作区每一步重新取：上一步刚批准「把这个文件夹加进来」，这一步就算工作区里了
                    val cur = host.state.conversation(convId)?.code ?: spec
                    val guard = CodeGuard(File(cur.folder), cur.extraPaths)
                    working += toolMsg(tc.id, step(convId, msg.id, tc, mode, cur, guard, backup, if (vision) seen else null))
                }
                if (seen.isNotEmpty()) {
                    working += contentMsg("user", "（这是刚才 read_file 读到的图片：" + seen.joinToString("、") { it.first } + "）", seen.map { it.second })
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

    /**
     * 子代理（2026-10-18，开发补齐第 11 样；Claude Code 的 Agent 工具）：一个新的上下文，同样的工具（不能再派子代理）和权限，
     * 步子都记在同一条回答上（界面上看得见它在干什么、要批准的照样弹）；最多走 maxSteps 的一半；返回它最后说的话。
     */
    private suspend fun subAgent(convId: String, msgId: String, tc: ToolCall, p: ProviderConfig, spec: CodeSpec, backup: CodeBackup, vision: Boolean): String {
        val args = runCatching { Json.parseToJsonElement(tc.arguments.ifBlank { "{}" }).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val task = args.str("task").orEmpty().ifBlank { return "参数错误：缺少 task" }
        val name = args.str("name").orEmpty().ifBlank { task.take(12) }
        val stepId = host.newId()
        addStep(convId, msgId, ToolStep("agent", name, id = stepId, ok = true, note = "子代理开始干活", output = task))
        val working = mutableListOf<JsonObject>()
        working += systemMsg(spec, host.state.settings.code, host.state.settings.personal, emptyList())
        working += buildJsonObject { put("role", "user"); put("content", "你是主 AI 派出来的帮手，专心做下面这件事，做完用几句话交回结果（发现了什么、改了什么、还有什么没做）：\n\n$task") }
        val limit = maxOf(5, host.state.settings.code.maxSteps / 2)
        var steps = 0
        var last = ""
        try {
            while (true) {
                val mode = host.state.conversation(convId)?.code?.mode ?: spec.mode
                val r = withContext(UsageTag(UsageKinds.CODE, convId = convId)) {
                    host.llmClient.chat(p, spec.modelId, working, null, toolsFor(mode, sub = true), null, {}, null) { _, _ -> }
                }
                last = r.content.replace(THINK, "").trim()
                if (r.toolCalls.isEmpty()) break
                working += assistantMsg(r, p)
                val seen = mutableListOf<Pair<String, String>>()
                for (t in r.toolCalls) {
                    if (steps >= limit) break
                    steps++
                    val cur = host.state.conversation(convId)?.code ?: spec
                    working += toolMsg(t.id, step(convId, msgId, t, mode, cur, CodeGuard(File(cur.folder), cur.extraPaths), backup, if (vision) seen else null))
                }
                if (seen.isNotEmpty()) working += contentMsg("user", "（这是刚才读到的图片）", seen.map { it.second })
                if (steps >= limit) { last = (last + "\n（子代理走到了 $limit 步上限，停下了）").trim(); break }
                trimWorking(working)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            last = "子代理出错了：${host.friendlyError(e, p)}"
        }
        setStep(convId, msgId, stepId) { it.copy(note = "子代理做完了（$steps 步）", output = "任务：$task\n\n结果：\n${last.ifBlank { "（没说什么）" }}".take(UI_TEXT_LIMIT)) }
        return "子代理「$name」做完了（$steps 步）。它交回的结果：\n${last.ifBlank { "（没说什么）" }}"
    }

    /**
     * 项目里的说明文件（2026-10-17，照 Claude Code：https://code.claude.com/docs/en/memory 的表——
     * 项目的 ./CLAUDE.md 或 ./.claude/CLAUDE.md、本人的 ./CLAUDE.local.md；没有 CLAUDE.md 时读 AGENTS.md）。
     * 只读项目文件夹这一层（Claude Code 还会往上级目录找，我们先不做）。每个最多 MAX_NOTE 字。
     */
    private fun projectNotes(folder: File): List<Pair<String, String>> {
        fun read(name: String): Pair<String, String>? {
            val f = File(folder, name)
            if (!f.isFile || f.length() > 2L * 1024 * 1024) return null
            val t = runCatching { f.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return name to (if (t.length > MAX_NOTE) t.take(MAX_NOTE) + "\n…（后面还有 ${t.length - MAX_NOTE} 字没放进来，要的话用 read_file 读）" else t)
        }
        val claude = listOfNotNull(read("CLAUDE.md"), read(".claude/CLAUDE.md"))
        val local = listOfNotNull(read("CLAUDE.local.md"))
        val agents = if (claude.isEmpty() && local.isEmpty()) listOfNotNull(read("AGENTS.md")) else emptyList()
        return claude + local + agents
    }

    /** 给模型的系统说明：讲环境（项目文件夹、外壳、有哪些工具），再接上用户自己的指令和项目说明；不加限制词。 */
    private fun systemMsg(spec: CodeSpec, code: CodeSettings, personal: PersonalSettings = PersonalSettings(),
                          notes: List<Pair<String, String>> = emptyList()): JsonObject = buildJsonObject {
        put("role", "system")
        put("content", buildString {
            append("你在帮用户改一个项目里的代码。\n")
            append("项目文件夹：").append(spec.folder).append("（工具里的 path 可以写相对它的路径，比如 src/main.kt，也可以写完整路径，比如 G:\\资料\\说明.md）\n")
            if (spec.extraPaths.isNotEmpty()) append("另外加进工作区的：").append(spec.extraPaths.joinToString("；")).append('\n')
            append("电脑上任何地方的文件都能读；改工作区（项目文件夹和上面加进来的）外面的文件，程序会先问用户一声（用户开了「全部放开」就不问）。\n")
            append("当前外壳：").append(shellLabel(code.shell)).append("；命令的工作目录就是项目文件夹；单条命令最多跑 ")
            append(code.commandTimeoutSec).append(" 秒，超时会把进程连同它的子进程杀掉。\n")
            append("系统是 Windows，文件按 UTF-8 读写。\n")
            append("用户消息里「@路径」是点名给你的文件，先读它。\n")
            append("可以用的工具：read_file（读文件，可指定行范围；图片、PDF、Word、Excel 也能读）、list_dir（列目录）、grep（按内容查找）、find_files（按文件名找）")
            if (spec.mode != CodeMode.PLAN) append("、write_file（新建或整个重写）、edit_file（局部替换）")
            append("、run_command（跑一条命令，返回退出码和输出，能放后台）、web_search（联网搜索）、fetch_url（读网页）、preview（给网页截图看）、update_todos（待办清单）、spawn_agent（派子代理）。")
            // 用户自己写的（设置 → 个性化）和项目里的说明文件：照着做
            val custom = listOf(personal.instructions.trim(), personal.codeInstructions.trim()).filter { it.isNotEmpty() }
            if (custom.isNotEmpty()) {
                append("\n\n【用户的自定义指令】（用户在设置里写的，每次都照着做）\n").append(custom.joinToString("\n\n"))
            }
            notes.forEach { (name, text) ->
                append("\n\n【项目说明：").append(name).append("】（项目文件夹里的说明文件，照着做）\n").append(text)
            }
            // 技能（2026-10-18）：只列名字和说明，要用时自己读
            append(Skills.prompt(runCatching { Skills.fromProject(File(spec.folder)) }.getOrDefault(emptyList())))
        })
    }

    private fun shellLabel(shell: String) = when (shell.trim().lowercase()) {
        "", "powershell" -> "PowerShell"
        "cmd" -> "cmd"
        else -> shell.trim()
    }

    /**
     * 会话历史：用户的话当 user，自己的话当 assistant；太长就从最早的开始丢，并在开头说明。
     * 2026-10-17：用户消息带的附件也发过去（文档的文字、最新一条任务里的图片，见 CodeHost.attachmentParts）。
     */
    private fun history(convId: String, task: Message, excludeId: String, vision: Boolean): List<JsonObject> {
        val all = host.messagesOf(convId)
        val end = all.indexOfFirst { it.id == task.id }.let { if (it < 0) all.size else it + 1 }
        // 记录员压过的部分换成摘要放在最前面，只发摘要之后的原文（2026-10-18）
        val (summary, upTo) = host.summaryOf(convId)
        val usable = all.subList(0, end).filter {
            it.id != excludeId && it.role != Role.SYSTEM && it.status != MsgStatus.STREAMING &&
                it.status != MsgStatus.ERROR && (it.content.isNotBlank() || it.attachments.isNotEmpty()) &&
                (summary.isBlank() || it.createdAt > upTo || it.id == task.id)
        }
        val picked = ArrayDeque<Message>()
        var used = 0
        for (m in usable.asReversed()) {
            val cost = m.content.length + m.attachments.sumOf { minOf(it.textChars, 4000) } + 200
            if (used + cost > HISTORY_BUDGET && picked.isNotEmpty()) break
            used += cost
            picked.addFirst(m)
        }
        data class Part(val role: String, val text: String, val images: List<String>)
        val parts = mutableListOf<Part>()
        if (summary.isNotBlank()) parts += Part("user", "【前情摘要】这个会话更早的部分已经压成下面的摘要：\n\n$summary", emptyList())
        if (picked.size < usable.size) parts += Part("user", "（更早的记录太长，已省略）", emptyList())
        for (m in picked) {
            val role = if (m.role == Role.USER) "user" else "assistant"
            // 以前回答里的 <think>…</think>（MiniMax 写在正文里）不再发回去，白占 token（10-12 真模型测试看到的；聊天那边也这么做）
            var text = if (role == "assistant") m.content.replace(THINK, "").trim() else m.content
            var images = emptyList<String>()
            if (role == "user" && m.attachments.isNotEmpty()) {
                val (more, imgs) = host.attachmentParts(m, latest = m.id == task.id, vision = vision)
                text = (text + more).trim()
                images = imgs
            }
            if (text.isBlank() && images.isEmpty()) continue
            // 有的接口不允许连着两条同一个角色，合并一下
            val last = parts.lastOrNull()
            if (last != null && last.role == role) parts[parts.size - 1] = Part(role, last.text + "\n\n" + text, last.images + images)
            else parts += Part(role, text, images)
        }
        while (parts.firstOrNull()?.role == "assistant") parts.removeAt(0)
        return parts.map { p -> contentMsg(p.role, p.text.ifBlank { "（看图）" }, p.images) }
    }

    /** 一条 user / assistant 消息；带图片时 content 是「文字 + 图片」的数组（OpenAI 风格，聊天那边也这么发）。 */
    private fun contentMsg(role: String, text: String, images: List<String>): JsonObject = buildJsonObject {
        put("role", role)
        if (images.isEmpty()) put("content", text)
        else putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", text) })
            images.forEach { url -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } }) }
        }
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

    private fun toolsFor(mode: CodeMode, sub: Boolean = false): JsonArray = buildJsonArray {
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
        val path = text("相对项目文件夹的路径（比如 src/main.kt），或者完整路径")
        fn("read_file", "读一个文件，可以只读一段。图片（png、jpg…）会直接给你看；PDF、Word、Excel、PPT 会抽出文字。",
            buildJsonObject {
                put("path", path)
                put("start_line", prop("integer", "可选，从第几行开始读（从 1 数）"))
                put("end_line", prop("integer", "可选，读到第几行（含）"))
            }, listOf("path"))
        fn("list_dir", "列一个目录里有哪些文件和子目录。", buildJsonObject { put("path", path) }, emptyList())
        // 2026-10-17 照 Claude Code 补的：按文件名找（Glob）、联网搜索（WebSearch）、读网页（WebFetch）
        fn("find_files", "按文件名找文件，比如 **/*.kt、src/**/Test*.java、*.md；返回匹配的路径（新改的在前）。",
            buildJsonObject {
                put("pattern", text("文件名的通配写法：* 匹配一段名字，** 匹配任意几层目录"))
                put("path", text("可选，在哪个目录底下找；不写就是项目文件夹"))
            }, listOf("pattern"))
        fn("web_search", "联网搜索，查文档、报错、库的最新版本这类本机上没有的信息。返回标题、网址和摘要。",
            buildJsonObject { put("query", text("要搜的内容")) }, listOf("query"))
        fn("fetch_url", "打开一个网页，读它的正文文字（比如官方文档、发布说明、报错讨论）。",
            buildJsonObject { put("url", text("网址，http 或 https 开头")) }, listOf("url"))
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
        fn("run_command", "在项目文件夹里跑一条命令（工作目录就是项目文件夹），返回退出码和输出。" +
            "开发服务器、监听文件这种开起来不会结束的命令，把 background 设成 true：开起来马上返回编号，之后用 read_output 看输出、stop_process 停掉。",
            buildJsonObject {
                put("command", text("要跑的命令"))
                put("background", prop("boolean", "可选，true = 在后台跑（不等它结束）"))
            }, listOf("command"))
        fn("read_output", "看一个后台命令到现在的输出（最后一截），以及它还在不在跑。",
            buildJsonObject { put("id", text("run_command 后台跑时给的编号")) }, listOf("id"))
        // 2026-10-18 开发补齐第 11 样：子代理（子代理里不再派）
        if (!sub) fn("spawn_agent", "把一块独立的活交给一个新的 AI 去做（它有自己的一份上下文，用同样的工具、同样的权限），做完把结果交回给你。" +
            "适合：查清楚某件事（哪些地方用到了 X）、各自独立的几块改动。一轮里同时派几个会并行跑。写清楚要它做什么、做完交回什么。",
            buildJsonObject {
                put("task", text("交给它的活，写清楚目标和要交回什么"))
                put("name", text("可选，给它起个短名字，比如「查登录」"))
            }, listOf("task"))
        // 2026-10-18 开发补齐第 4 样：待办清单
        fn("update_todos", Todos.DESC, buildJsonObject { put("items", text("整张清单，一行一项，开头 [x] / [>] / [ ]")) }, listOf("items"))
        // 2026-10-18 开发补齐第 3 样：改完自己看页面
        fn("preview", "打开一个网页（比如后台开的开发服务器 http://localhost:5173）截一张图给你看，用来检查改完的页面对不对。看不了图的话给你网页上的文字。",
            buildJsonObject {
                put("url", text("网址"))
                put("width", prop("integer", "可选，浏览器宽度，默认 1280（看手机版填 390）"))
            }, listOf("url"))
        fn("stop_process", "停掉一个后台命令（连同它开出的子进程）。",
            buildJsonObject { put("id", text("后台命令的编号")) }, listOf("id"))
    }

    /** 走一步工具：先按权限档看要不要等批准，再执行，把这一步记到消息上，返回给模型的工具结果。 */
    private suspend fun step(convId: String, msgId: String, tc: ToolCall, mode: CodeMode, spec: CodeSpec,
                             guard: CodeGuard, backup: CodeBackup, seen: MutableList<Pair<String, String>>?): String {
        val args = runCatching { Json.parseToJsonElement(tc.arguments.ifBlank { "{}" }).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return when (tc.name) {
            "read_file" -> readFile(convId, msgId, args, guard, seen)
            "list_dir" -> listDir(convId, msgId, args, guard)
            "grep" -> grep(convId, msgId, args, guard)
            "find_files" -> findFiles(convId, msgId, args, guard)
            "web_search" -> webSearch(convId, msgId, args)
            "fetch_url" -> fetchUrl(convId, msgId, args, spec)
            "preview" -> previewTool(convId, msgId, args, seen)
            "update_todos" -> {
                val lines = Todos.normalize(args.str("items").orEmpty())
                if (lines.isEmpty()) return "清单是空的：一行一项，开头写 [x] / [>] / [ ]"
                // 一条回答里只留最新的一张
                host.updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.filter { it.kind != "todo" } + ToolStep("todo", "待办", id = host.newId(), output = lines, ok = true)) }
                Todos.reply(lines)
            }
            "read_output" -> {
                val id = args.str("id").orEmpty().trim()
                val out = host.backgroundOutput(id) ?: return "没有这个后台命令：$id"
                val alive = host.state.conversation(convId)?.background?.firstOrNull { it.id == id }?.running == true
                addStep(convId, msgId, ToolStep("read", "后台命令 $id 的输出", id = host.newId(), output = out.takeLast(UI_TEXT_LIMIT), ok = true))
                (if (alive) "（还在跑）\n" else "（已经结束了）\n") + out.takeLast(RESULT_LIMIT)
            }
            "stop_process" -> {
                val id = args.str("id").orEmpty().trim()
                host.stopBackground(convId, id)
                addStep(convId, msgId, ToolStep("run", "停掉后台命令 $id", id = host.newId(), ok = true))
                "停掉了 $id"
            }
            // 计划档不给这两个工具；模型硬要调，也在这里拒掉，文件不动
            "write_file" -> if (mode == CodeMode.PLAN) planRefusal(convId, msgId, "write", args.str("path").orEmpty())
                else writeFile(convId, msgId, args, mode, guard, backup)
            "edit_file" -> if (mode == CodeMode.PLAN) planRefusal(convId, msgId, "edit", args.str("path").orEmpty())
                else editFile(convId, msgId, args, mode, guard, backup)
            "run_command" -> runCommand(convId, msgId, args, spec)
            else -> "没有这个工具：${tc.name}"
        }
    }

    /**
     * 读的东西在工作区外面：先停下来问用户（2026-10-17，照 Claude Code：工作区外的读要批准）。
     * 返回 null = 在工作区里、不用问；返回 ToolStep = 问过、允许了（这一步已经记在消息上，读完用 setStep 填结果）；
     * 拒绝了抛 Refused，调用的地方把它的说明回给模型。
     */
    private suspend fun askOutside(convId: String, msgId: String, kind: String, file: File, guard: CodeGuard, note: String): ToolStep? {
        if (guard.inScope(file)) return null
        // 2026-10-17 用户：「比 Claude 更开放」——电脑上发起的，工作区外面的也直接读，不问；只有手机发起的任务才停下来问
        if (kotlin.coroutines.coroutineContext[PhoneTask] == null) return null
        val step = ToolStep(kind, guard.relative(file), id = host.newId(), ok = true, note = note, scopeDir = guard.folderToAdd(file))
        if (!waitApproval(convId, msgId, step)) throw Refused(refused("${note}：${guard.relative(file)}"))
        return step
    }

    private class Refused(val text: String) : Exception(text)

    /** 读类的一步做完了：问过的那一步填上结果，没问过的新记一步。 */
    private suspend fun record(convId: String, msgId: String, asked: ToolStep?, step: ToolStep) {
        if (asked == null) addStep(convId, msgId, step)
        else setStep(convId, msgId, asked.id) { it.copy(input = step.input, output = step.output, ok = step.ok) }
    }

    /**
     * 读文件。2026-10-17 照 Claude Code 的 Read：图片给能看图的模型看（seen 不为 null 就是能看），
     * PDF、Word、Excel、PPT 抽出文字（DocExtract，和聊天里的附件同一套）。
     */
    private suspend fun readFile(convId: String, msgId: String, args: JsonObject, guard: CodeGuard, seen: MutableList<Pair<String, String>>? = null): String {
        val raw = args.str("path").orEmpty()
        if (raw.isBlank()) return "参数错误：缺少 path"
        val file = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(file)
        if (!file.isFile) return "没有这个文件：$rel（要看目录用 list_dir）"
        val asked = try { askOutside(convId, msgId, "read", file, guard, "读工作区外面的文件") } catch (r: Refused) { return r.text }
        val ext = file.extension.lowercase()
        if (ext in IMAGE_EXT) {
            if (file.length() > MAX_IMAGE_BYTES) return "$rel 是图片，但太大了（${file.length()} 字节）"
            if (seen == null) {
                record(convId, msgId, asked, ToolStep("read", rel, id = host.newId(), output = "（图片，这个模型看不了）", ok = false))
                return "$rel 是图片（${file.length()} 字节）。这个模型看不了图片；要看图得在 设置 → 模型服务 里换一个能看图的模型。"
            }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val shrunk = runCatching { shrinkImageToJpeg(bytes, 1600, 1_500_000) }.getOrNull()
            val url = if (shrunk != null) "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(shrunk)
                else "data:${DocExtract.imageMime(file.name, "")};base64," + java.util.Base64.getEncoder().encodeToString(bytes)
            seen += rel to url
            record(convId, msgId, asked, ToolStep("read", rel, id = host.newId(), output = "（图片，已发给模型看）", ok = true))
            return "$rel 是图片，已经附在下一条消息里给你看。"
        }
        if (ext in DOC_EXT) {
            if (file.length() > MAX_DOC_BYTES) return "$rel 太大（${file.length()} 字节），读不下"
            val (text, note) = withContext(Dispatchers.IO) { DocExtract.extract(file.name, file.readBytes()) }
            if (text == null) {
                record(convId, msgId, asked, ToolStep("read", rel, id = host.newId(), output = note, ok = false))
                return "$rel 读不出文字：$note"
            }
            val (shown, cut) = CodeRunnerRules.clip(text, RESULT_LIMIT * 2)
            record(convId, msgId, asked, ToolStep("read", rel, id = host.newId(), output = shown.take(UI_TEXT_LIMIT), ok = true))
            return "$rel 抽出的文字（共 ${text.length} 字）：\n$shown" + if (cut) "\n（中间被截断了）" else ""
        }
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
        record(convId, msgId, asked, ToolStep("read", rel, id = host.newId(), output = shown, ok = true))
        return out
    }

    private suspend fun listDir(convId: String, msgId: String, args: JsonObject, guard: CodeGuard): String {
        val raw = args.str("path").orEmpty().ifBlank { "." }
        val dir = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(dir).ifBlank { "." }
        if (!dir.isDirectory) return "不是目录：$rel"
        val asked = try { askOutside(convId, msgId, "list", dir, guard, "列工作区外面的目录") } catch (r: Refused) { return r.text }
        val all = runCatching { dir.listFiles()?.sortedBy { it.name } }.getOrNull() ?: return "读不了这个目录：$rel"
        // 在工作区里列的时候，目录联接 / 符号链接指到工作区外面的：照列名字，但标出来（要读会先问用户，CodeGuard 按真实路径判断）
        val inside = guard.inScope(dir)
        val shown = all.take(LIST_MAX).joinToString("\n") {
            (if (it.isDirectory) it.name + "/" else it.name) + if (inside && !guard.inScope(it)) "（指到工作区外面，读的时候会先问用户）" else ""
        }
        val note = if (all.size > LIST_MAX) "（还有 ${all.size - LIST_MAX} 个没列出来）" else ""
        record(convId, msgId, asked, ToolStep("list", rel, id = host.newId(), output = shown, ok = true))
        return "$rel 里 ${all.size} 项：\n" + shown.ifBlank { "（空目录）" } + (if (note.isNotEmpty()) "\n$note" else "")
    }

    private suspend fun grep(convId: String, msgId: String, args: JsonObject, guard: CodeGuard): String {
        val pattern = args.str("pattern").orEmpty()
        if (pattern.isBlank()) return "参数错误：缺少 pattern"
        val raw = args.str("path").orEmpty().ifBlank { "." }
        val start = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(start).ifBlank { "." }
        if (!start.exists()) return "没有这个路径：$rel"
        val asked = try { askOutside(convId, msgId, "grep", start, guard, "在工作区外面查找") } catch (r: Refused) { return r.text }
        // 找的范围：在工作区里就是整个工作区（指到外面的联接跳过）；问过用户的外面目录，只在那个目录自己底下找
        val inside = guard.inScope(start)
        val fence = if (inside) guard else CodeGuard(if (start.isDirectory) start else start.parentFile ?: start)
        fun allowed(f: File) = if (inside) fence.inScope(f) else fence.resolveInProject(f.absolutePath) != null
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        // 每行最多看 2000 字：写坏了的正则在超长行上会卡很久（Kotlin 的正则停不下来）
        fun hit(line: String) = line.take(2000).let { l -> re?.containsMatchIn(l) ?: l.contains(pattern, ignoreCase = true) }
        val hits = mutableListOf<String>()
        val queue = ArrayDeque<File>().apply { add(start) }
        var scanned = 0
        while (queue.isNotEmpty() && hits.size < GREP_MAX && scanned < GREP_FILES) {
            val f = queue.removeFirst()
            // 主会话审查时补的（10-12）：遍历到的每个目录、文件都要过 CodeGuard（按真实路径判断），
            // 不然一个指到外面的目录联接，就能让 grep 不问用户就读到工作区外的文件
            if (!allowed(f)) continue
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
        record(convId, msgId, asked, ToolStep("grep", "$pattern · $rel", id = host.newId(), output = output, ok = hits.isNotEmpty()))
        return if (hits.isEmpty()) "在 $rel 里没找到「$pattern」（扫了 $scanned 个文件）。"
        else "$rel 里找到 ${hits.size} 处（扫了 $scanned 个文件）：\n$output" +
            if (hits.size >= GREP_MAX) "\n（只列了前 $GREP_MAX 条，写具体一点再找）" else ""
    }

    /**
     * 按文件名找（2026-10-17，照 Claude Code 的 Glob）。规矩和 grep 一样：工作区里不问，指到外面的联接跳过；
     * 问过用户的外面目录，只在那个目录自己底下找。新改的排在前面（Claude Code 的 Glob 也这么排）。
     */
    private suspend fun findFiles(convId: String, msgId: String, args: JsonObject, guard: CodeGuard): String {
        val pattern = args.str("pattern").orEmpty().trim().replace('\\', '/')
        if (pattern.isBlank()) return "参数错误：缺少 pattern"
        val raw = args.str("path").orEmpty().ifBlank { "." }
        val start = guard.resolve(raw) ?: return outside(raw)
        val rel = guard.relative(start).ifBlank { "." }
        if (!start.isDirectory) return "不是目录：$rel"
        val asked = try { askOutside(convId, msgId, "find", start, guard, "在工作区外面找文件") } catch (r: Refused) { return r.text }
        val inside = guard.inScope(start)
        val fence = if (inside) guard else CodeGuard(start)
        fun allowed(f: File) = if (inside) fence.inScope(f) else fence.resolveInProject(f.absolutePath) != null
        // 没写目录的（*.kt）按「任意一层」找，和大家的习惯一样
        val glob = if ('/' in pattern) pattern else "**/$pattern"
        val matcher = runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrElse { return "通配写法不对：$pattern" }
        val rootMatcher = if ('/' in pattern) null else runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrNull()
        val found = mutableListOf<File>()
        var scanned = 0
        withContext(Dispatchers.IO) {
            val queue = ArrayDeque<File>().apply { add(start) }
            while (queue.isNotEmpty() && scanned < FIND_SCAN_MAX) {
                val f = queue.removeFirst()
                if (!allowed(f)) continue
                if (f.isDirectory) {
                    f.listFiles()?.filter { it.name != ".git" && it.name != "node_modules" }?.forEach { queue.add(it) }
                } else {
                    scanned++
                    val r = start.toPath().relativize(f.toPath())
                    if (matcher.matches(r) || rootMatcher?.matches(r) == true) found += f
                }
            }
        }
        val sorted = found.sortedByDescending { it.lastModified() }
        val output = sorted.take(FIND_MAX).joinToString("\n") { guard.relative(it) }
        record(convId, msgId, asked, ToolStep("find", "$pattern · $rel", id = host.newId(), output = output, ok = found.isNotEmpty()))
        return if (found.isEmpty()) "在 $rel 里没找到「$pattern」（看了 $scanned 个文件）。"
        else "$rel 里找到 ${found.size} 个：\n$output" + if (found.size > FIND_MAX) "\n（只列了最新的 $FIND_MAX 个，写具体一点再找）" else ""
    }

    /** 联网搜索（2026-10-17）：不改本机任何东西，不用问；结果记一步给用户看。 */
    private suspend fun webSearch(convId: String, msgId: String, args: JsonObject): String {
        val q = args.str("query").orEmpty().trim()
        if (q.isBlank()) return "参数错误：缺少 query"
        val (step, res) = host.webSearch(q)
        addStep(convId, msgId, step.copy(id = host.newId(), output = res.joinToString("\n") { "${it.title}\n${it.url}" }))
        return when {
            !step.ok -> "搜索失败：${step.note}"
            res.isEmpty() -> "没有搜到结果。"
            else -> res.mapIndexed { i, r -> "[${i + 1}] ${r.title}\n${r.url}\n${r.snippet}" }.joinToString("\n\n")
        }
    }

    /**
     * 读网页（2026-10-17）：照 Claude Code 的 WebFetch 先问用户——读过本机文件的 AI 能把内容拼进网址发出去，
     * 所以每个网站第一次都问；批准时可选「这个网站这个会话里不再问」（记成 web:网站 放在 allowedCommands 里）。
     */
    private suspend fun fetchUrl(convId: String, msgId: String, args: JsonObject, spec: CodeSpec): String {
        val url = args.str("url").orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "参数错误：url 要以 http:// 或 https:// 开头"
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return "网址不对：$url"
        val key = "web:$host"
        val stepId = this.host.newId()
        // 2026-10-17 用户：「比 Claude 更开放」，和对话栏统一——读网页不改本机任何东西，不问
        val allowed = true
        val step = ToolStep("fetch", url, id = stepId, ok = true, prefix = if (allowed) "" else key,
            note = if (allowed) "$host 这个会话里不再问" else "")
        if (!allowed) { if (!waitApproval(convId, msgId, step)) return refused("打开 $url") } else addStep(convId, msgId, step)
        return try {
            val text = this.host.fetchUrl(url)
            val (shown, cut) = CodeRunnerRules.clip(text, RESULT_LIMIT)
            setStep(convId, msgId, stepId) { it.copy(output = shown.take(UI_TEXT_LIMIT), ok = true) }
            "$url 的正文：\n$shown" + if (cut) "\n（太长，中间省略了）" else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val why = this.host.friendlyError(e)
            setStep(convId, msgId, stepId) { it.copy(ok = false, note = "打不开：$why") }
            "网页打不开：$why"
        }
    }

    /** 预览（2026-10-18）：截图给能看图的模型；看不了图或截不了的，给网页文字。 */
    private suspend fun previewTool(convId: String, msgId: String, args: JsonObject, seen: MutableList<Pair<String, String>>?): String {
        val url = args.str("url").orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "url 要以 http:// 或 https:// 开头"
        val width = args.str("width")?.trim()?.toIntOrNull()?.coerceIn(320, 2560) ?: 1280
        if (seen != null) {
            val png = withContext(Dispatchers.IO) { com.guixing.jixunying.engine.code.Preview.screenshot(url, width, if (width < 600) 844 else 800) }
            if (png != null) {
                val small = runCatching { shrinkImageToJpeg(png, 1600, 1_500_000) }.getOrNull()
                seen += url to (if (small != null) "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(small) else "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png))
                addStep(convId, msgId, ToolStep("fetch", "预览 $url", id = host.newId(), ok = true, note = "截了图给 AI 看"))
                return "$url 的截图已经附在下一条消息里给你看。"
            }
        }
        val text = runCatching { host.fetchUrl(url) }.getOrElse { return "打不开 $url：${host.friendlyError(it)}（开发服务器开了没有？可以用 read_output 看后台命令的输出）" }
        addStep(convId, msgId, ToolStep("fetch", "预览 $url", id = host.newId(), ok = true, note = if (seen == null) "这个模型看不了图，读了网页文字" else "截不了图，读了网页文字"))
        return "$url 的网页文字（${if (seen == null) "你看不了图" else "截图没成功"}）：\n" + CodeRunnerRules.clip(text, RESULT_LIMIT).first
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
        val out = !guard.inScope(file)
        val step = ToolStep("write", rel, id = stepId, output = uiText(diff.text), ok = true,
            note = (if (existed) "重写这个文件" else "新建这个文件") + if (out) "（在工作区外面）" else "",
            scopeDir = if (out) guard.folderToAdd(file) else "")
        // 不用批准（接受编辑档）也要先把这一步记到消息上，不然改了文件却没有改动记录，没法审阅、撤销（主会话审查 10-12 补）
        if (shouldAsk(convId, out, needsApproval(mode, "write"), step, "写文件 $rel（${if (existed) "重写" else "新建"}）\n${diff.text.take(3000)}")) { if (!waitApproval(convId, msgId, step)) return refused("写 $rel") } else addStep(convId, msgId, step)
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
        val out = !guard.inScope(file)
        val step = ToolStep("edit", rel, id = stepId, output = uiText(diff.text), ok = true,
            note = "替换 $rel 里的一段" + if (out) "（在工作区外面）" else "", scopeDir = if (out) guard.folderToAdd(file) else "")
        // 同 writeFile：不用批准也要先记上这一步；工作区外面的改动哪一档都要问
        if (shouldAsk(convId, out, needsApproval(mode, "edit"), step, "改文件 $rel\n${diff.text.take(3000)}")) { if (!waitApproval(convId, msgId, step)) return refused("改 $rel") } else addStep(convId, msgId, step)
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
        // 这个会话里批准过「同样开头不再问」的，直接跑（拼起来的、危险的 CodeCommands 永远不放行）
        val by = CodeCommands.allowedBy(command, spec.allowedCommands)
        val step = ToolStep("run", command, id = stepId, ok = true,
            note = if (by != null) "「$by」开头的命令这个会话里不再问" else "",
            prefix = if (by == null) CodeCommands.prefixOf(command).orEmpty() else "")
        // 全部放开：不问；只有危险命令（递归删除、格式化、改注册表……）照样问一次
        val ask = when {
            CodeCommands.dangerous(command) && by == null -> true
            openAll(convId) -> false
            by != null -> false
            autoOn(convId) -> host.autoCheck(convId, currentTask[convId].orEmpty(), "跑命令：$command").also { why ->
                if (why != null) autoNote[stepId] = "自动检查拦下：$why"
            } != null
            else -> needsApproval(host.state.conversation(convId)?.code?.mode ?: spec.mode, "run")
        }
        if (ask) {
            if (!waitApproval(convId, msgId, step)) return refused("跑这条命令")
        } else addStep(convId, msgId, step)
        // 后台跑（2026-10-18）：开起来就返回编号
        if (args.str("background")?.trim()?.lowercase() == "true") {
            val id = host.startBackground(convId, command, File(spec.folder))
            setStep(convId, msgId, stepId) { it.copy(ok = id != null, note = if (id != null) "在后台跑，编号 $id" else "后台开不起来") }
            return if (id == null) "后台开不起来。" else "已经在后台开起来了，编号 $id。用 read_output 看输出（开发服务器的网址一般在里面），用 stop_process 停掉。"
        }
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

    /** 「自动」档：这个会话开着自动、不是计划档。 */
    private fun autoOn(convId: String): Boolean = host.state.conversation(convId)?.code?.let { it.autoMode && it.mode != CodeMode.PLAN } == true

    /** 检查员拦下的理由，挂在等批准的那一步上给用户看（步骤编号 → 说明）。 */
    private val autoNote = ConcurrentHashMap<String, String>()

    /**
     * 写 / 改文件要不要先问（2026-10-17）：全部放开不问；自动档——工作区里的直接做，外面的先给检查员看，拦下的问；
     * 其余照旧（外面的问、手动档问）。
     */
    private suspend fun shouldAsk(convId: String, outside: Boolean, modeAsks: Boolean, step: ToolStep, action: String): Boolean = when {
        openAll(convId) -> false
        autoOn(convId) -> if (!outside) false else {
            val why = host.autoCheck(convId, currentTask[convId].orEmpty(), action)
            if (why != null) autoNote[step.id] = "自动检查拦下：$why"
            why != null
        }
        else -> outside || modeAsks
    }

    /** 这个会话开了「全部放开」（2026-10-17）：改文件、跑命令都不问（危险命令除外），改之前照样备份。计划档不算。 */
    private fun openAll(convId: String): Boolean = host.state.conversation(convId)?.code?.let { it.openAll && it.mode != CodeMode.PLAN } == true

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
        val done = CompletableDeferred<Pair<Boolean, String>>()
        waitings[step.id] = Waiting(convId, done)
        // 自动档里检查员拦下的：把理由接在说明后面给用户看
        val why = autoNote.remove(step.id)
        val note = when {
            why == null -> step.note
            step.note.isBlank() -> why
            else -> step.note + "（$why）"
        }
        addStep(convId, msgId, step.copy(approval = "waiting", note = note))
        val (allow, remember) = try {
            done.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                setStep(convId, msgId, step.id) { it.copy(approval = "denied", ok = false, note = "停下来时这一步没有做") }
            }
            throw e
        } finally {
            waitings.remove(step.id)
        }
        // 允许时顺便放宽（只对这一步给出的那一项）：把文件夹加进工作区 / 同样开头的命令不再问
        val widened = when {
            !allow -> ""
            remember == "dir" && step.scopeDir.isNotBlank() -> {
                host.updateCode(convId) { s -> if (s.extraPaths.any { it.equals(step.scopeDir, ignoreCase = true) }) s else s.copy(extraPaths = s.extraPaths + step.scopeDir) }
                "（已把 ${step.scopeDir} 加进工作区）"
            }
            remember == "command" && step.prefix.isNotBlank() -> {
                host.updateCode(convId) { s -> if (step.prefix in s.allowedCommands) s else s.copy(allowedCommands = s.allowedCommands + step.prefix) }
                if (step.prefix.startsWith("web:")) "（网站 ${step.prefix.removePrefix("web:")} 这个会话里不再问）"
                else "（「${step.prefix}」开头的命令这个会话里不再问）"
            }
            else -> ""
        }
        setStep(convId, msgId, step.id) {
            it.copy(approval = if (allow) "allowed" else "denied", ok = allow, note = if (allow) it.note + widened else "用户拒绝了这一步")
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

    /**
     * 回到这里（2026-10-18）：这条用户任务（含）往后，所有没撤销的改动从后往前还原，消息全删；返回任务原文。
     * 先停掉正在跑的（不然一边还原一边又在改）。
     */
    suspend fun rewind(convId: String, messageId: String): CommandResult {
        val guard = guardOf(convId) ?: return CommandResult(false, "这不是 Code 会话")
        val all = host.messagesOf(convId)
        val idx = all.indexOfFirst { it.id == messageId }
        if (idx < 0 || all[idx].role != Role.USER) return CommandResult(false, "找不到这条任务")
        stop(convId)
        val later = all.subList(idx, all.size)
        val changes = later.flatMap { m -> m.tools.mapNotNull { it.change } }.filter { it.review != "rejected" }
        val backup = host.backupOf(convId)
        var done = 0
        for (c in changes.asReversed()) {
            val file = guard.resolve(c.path) ?: continue
            if (withContext(Dispatchers.IO) { backup.restore(c.id, file) }) done++
        }
        host.removeMessages(convId, later.map { it.id })
        return CommandResult(message = "回到了这条任务之前：还原了 $done 处改动，后面的对话删掉了", data = all[idx].content)
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
        return runCatching { CodeGuard(File(spec.folder), spec.extraPaths) }.getOrNull()
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

    private fun outside(raw: String) = "路径不合法：$raw（网络路径 \\\\服务器\\共享、设备名 CON / NUL、带「:数据流」的都不行）。"

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
        /** 每个项目说明文件（CLAUDE.md 等）最多放多少字进系统说明。 */
        private const val MAX_NOTE = 30_000
        /** 单个文件多大就不读了。 */
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        /** read_file 读图片、文档的上限（图片会先压小再发）。 */
        private const val MAX_IMAGE_BYTES = 30L * 1024 * 1024
        private const val MAX_DOC_BYTES = 50L * 1024 * 1024
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val DOC_EXT = setOf("pdf", "docx", "xlsx", "pptx", "doc", "xls", "ppt")
        private const val LIST_MAX = 200
        private const val FIND_MAX = 200
        private const val FIND_SCAN_MAX = 50_000
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
    /** 改这个会话的 CodeSpec（批准时顺便把文件夹加进工作区、记下不再问的命令开头）。 */
    fun updateCode(convId: String, transform: (CodeSpec) -> CodeSpec)
    /**
     * 一条用户消息的附件怎么发给模型（2026-10-17）：返回（接在正文后面的文字，图片 data URL）。
     * 文档抽出的文字接在后面（最新一条给得多、以前的只给开头）；图片只有最新一条、模型能看图时才发。
     */
    fun attachmentParts(m: Message, latest: Boolean, vision: Boolean): Pair<String, List<String>> = "" to emptyList()
    /** 联网搜索（和聊天成员同一套，设置 → 联网搜索 选的引擎）：返回（工具步骤，结果）。 */
    suspend fun webSearch(query: String): Pair<ToolStep, List<com.guixing.jixunying.model.SearchSource>> =
        ToolStep("search", query, ok = false, note = "这台设备不能联网搜索") to emptyList()
    /** 打开一个网页，返回正文文字；打不开抛异常。 */
    suspend fun fetchUrl(url: String): String = throw IllegalStateException("这台设备不能读网页")
    /** 删掉这些消息（「回到这里」用）。 */
    fun removeMessages(convId: String, ids: List<String>) {}
    /** 长会话压缩（2026-10-18，开发补齐第 6 样）：记录员写的前情摘要和它管到哪个时间；没有就是（""，0）。 */
    fun summaryOf(convId: String): Pair<String, Long> = "" to 0L
    /** 一次任务做完以后，在后台看要不要压缩（和对话栏同一套 compactIfLong）。 */
    fun compactLater(convId: String) {}
    /** 后台命令（2026-10-18）：开起来返回编号（开不了 null）；看输出；停掉。 */
    fun startBackground(convId: String, command: String, dir: File): String? = null
    fun backgroundOutput(id: String): String? = null
    fun stopBackground(convId: String, id: String) {}
    /** 「自动」档的检查（engine/AutoJudge）：返回 null = 放行，文字 = 拦下的理由（要问用户）。 */
    suspend fun autoCheck(convId: String, request: String, action: String): String? = "这台设备没有检查员"
}
