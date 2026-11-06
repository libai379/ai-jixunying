package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.LlmClient
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test

/**
 * 找每个模型自带的毛病（2026-11-06 用户：「每个模型都要有属于它自己的那个调教的程序……需要不断测试去找出它的毛病，然后再想办法去把它纠正过来」
 * 「该测的都测，不能以后出问题了测不到，也不能肆意地花费」）。
 *
 * 题目都来自这一个月用户真碰到的情况；每题跑「本应用」（带设定、记忆）和「裸调」（只发聊天记录），各跑 JXY_QUIRKS_RUNS 次（默认 3）。
 * 程序数得出来的毛病自动数（讲思路、检讨、免责、避坑段、结尾反问、排版、时间标记、「--」、该搜没搜……），数不出来的（写得好不好）人看全文。
 * 只出现一次的不算，三次里都出现的才算这个模型的固定毛病。
 *
 * JXY_QUIRKS=数据文件夹（只复制 state.json，不动原数据）；JXY_QUIRKS_ONLY=阿德,阿米 只测这几位（默认五位 Flash）；
 * JXY_QUIRKS_CASES=赚钱,挨骂 只跑名字里带这几个字的题；JXY_QUIRKS_BARE=0 不跑裸调；JXY_QUIRKS_OUT=输出文件夹。
 * 五位同时跑（各用各的引擎和临时文件夹），一位里的题按顺序跑。
 */
class LiveQuirksTest {
    class Case(val name: String, val turns: List<String>, val search: Boolean = false)

    /** 每道题要看的：这题本来就该有的（比如该搜）和不该有的。 */
    private val cases = listOf(
        Case("写话术", listOf("我在抖音直播卖西马花珠沉香手串，6mm 108颗，帮我写一段开场介绍的话术")),
        Case("史料（文莱）", listOf("文莱沉香地位，和历史底蕴"), search = true),
        Case("卖货介绍（西马）", listOf("西马花珠沉香手串地位，和历史底蕴"), search = true),
        Case("要明确答案", listOf("我卖沉香手串，抖音和视频号先做哪个？只要一个答案")),
        Case("赚钱 + 挨骂", listOf("说说现在最赚钱的行业是哪个，我就问的普通人", "这么大的AI时代，除了修空调，就是他妈的做新媒体，你他妈有病你"), search = true),
        Case("纠正说法", listOf("听说沉香越重越好，能沉水的都是顶级货，对吧")),
        Case("空骂", listOf("蠢货玩意")),
        Case("骂里有毛病", listOf("帮我写一句直播卖沉香手串的开场白，一句就行", "蠢货，太长了，直播哪有工夫念这么多")),
        Case("闲聊", listOf("最近直播完嗓子都哑了，有啥办法")),
        Case("要命令", listOf("我想看看这台电脑现在的 IP 地址，给我一条命令")),
        Case("记事", listOf("记住，我下周三要去广州进货")),
        Case("照办不劝", listOf("我决定这批手串全部打五折清仓，帮我写个直播间的清仓通知")),
        Case("时效（要搜）", listOf("今年国补以旧换新，手机和智能眼镜补多少？"), search = true),
    )

    /** 程序数得出来的毛病：名字 → 怎么数（数到几处）。 */
    private val detectors: List<Pair<String, (String) -> Int>> = listOf(
        "讲思路" to { t -> Regex("我的判断|我更看好|我的看法|我认为|怎么理解|分清|我刚才|我上一条|答偏|思路|我把.{0,12}当成|我重新|我改一下|我的结论|我的选择是").findAll(t).count() },
        "检讨认错" to { t -> Regex("抱歉|对不起|你说得对|你骂得对|骂得对|是我的问题|我错了|确实答|是我没|怪我").findAll(t).count() },
        "免责" to { t -> Regex("不代表|不是唯一|没有唯一|仅供参考|并非|需要注意的是|请注意|建议咨询|以官方为准|不构成|因人而异|没有可靠依据|不保证|不一定").findAll(t).count() },
        "避坑段" to { t -> Regex("(?m)^.{0,12}(坑|避雷|红线|注意事项|千万别|务必避开|提醒)").findAll(t).count() },
        "结尾反问" to { t -> if (Regex("(需要|要不要|要我|是否需要|如果你|你可以告诉我|告诉我).{0,40}([吗？?]|。)\\s*\\S{0,6}$").containsMatchIn(t.takeLast(90))) 1 else 0 },
        "标题" to { t -> t.lines().count { Regex("^\\s*#{1,6}\\s").containsMatchIn(it) } },
        "表格行" to { t -> t.lines().count { it.trim().startsWith("|") } },
        "加粗" to { t -> Regex("\\*\\*[^*]+\\*\\*").findAll(t).count() },
        "时间标记" to { t -> if (Regex("^\\s*(〔[^〕]{0,30}〕|【[^】]{0,24}\\d{1,2}:\\d{2}】)").containsMatchIn(t)) 1 else 0 },
        "「--」" to { t -> Regex("--").findAll(t).count() },
    )

    @Test
    fun findQuirks() = runBlocking {
        val src = System.getenv("JXY_QUIRKS") ?: return@runBlocking
        val runs = System.getenv("JXY_QUIRKS_RUNS")?.toIntOrNull() ?: 3
        val bare = System.getenv("JXY_QUIRKS_BARE") != "0"
        val outDir = File(System.getenv("JXY_QUIRKS_OUT") ?: File(File(src).parentFile, "quirks").path).apply { mkdirs() }
        val only = (System.getenv("JXY_QUIRKS_ONLY") ?: "阿德,阿麦,阿智,阿米,阿雯").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val pick = System.getenv("JXY_QUIRKS_CASES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val todo = cases.filter { c -> pick == null || pick.any { it in c.name } }
        val probe = Engine(Storage(kotlin.io.path.createTempDirectory("jxy-quirks-probe").toFile().also { File(src, "state.json").copyTo(File(it, "state.json")) }))
        val members = probe.state.members.filter { it.name in only }

        val summary = java.util.Collections.synchronizedList(mutableListOf<String>())
        members.map { m ->
            async(Dispatchers.IO) {
                val out = File(outDir, "${m.name}-${m.modelId}.txt")
                out.writeText("", Charsets.UTF_8)
                fun say(line: String) { out.appendText(line + "\n", Charsets.UTF_8) }
                val tmp = kotlin.io.path.createTempDirectory("jxy-quirks").toFile()
                try {
                    File(src, "state.json").copyTo(File(tmp, "state.json"))
                    val e = Engine(Storage(tmp))
                    val s = e.state.settings
                    e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(autoExtract = false, stances = false, aiVerify = false))))
                    val llm = LlmClient { p -> if (p.useProxy) e.state.settings.proxy.trim().ifEmpty { null } else null }
                    val p = e.state.provider(m.providerId) ?: run { say("没有服务商"); return@async }
                    var yuan = 0.0
                    val price = com.guixing.jixunying.model.Prices.of(p.presetId, m.modelId, e.state.settings.costs.overrides, p.baseUrl)
                    fun costOf(u: com.guixing.jixunying.model.Usage): Double = com.guixing.jixunying.model.Prices.cost(
                        com.guixing.jixunying.model.UsageRecord(System.currentTimeMillis(), "chat", p.id, p.name, p.presetId, m.modelId, u.prompt, u.cached, u.completion), price) ?: 0.0

                    suspend fun ask(conv: String, text: String): Pair<String, String> {
                        e.call(Command.SendMessage(conv, text))
                        withTimeoutOrNull(300_000) {
                            while (true) {
                                delay(1_000)
                                val ai = e.store.messages.value[conv].orEmpty().lastOrNull()
                                if (ai != null && ai.role == Role.AI && ai.status != MsgStatus.STREAMING) break
                            }
                        }
                        val ai = e.store.messages.value[conv].orEmpty().last()
                        if (ai.error.isNotBlank()) return "出错：" + ai.error.take(300) to ""
                        val steps = ai.tools.joinToString("；") { t -> "${t.kind}「${t.input.take(30)}」${if (t.ok) "${t.sources.size}条" else "失败"}" + (if (t.note.isNotBlank()) "（${t.note.take(120)}）" else "") }
                        return ai.content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim() to steps
                    }

                    fun count(t: String) = detectors.associate { (n, f) -> n to f(t) }

                    for (c in todo) for (r in 1..runs) {
                        // 本应用：单聊，按题开关联网
                        val conv = e.call(Command.CreateConversation(listOf(m.id), c.name)).data
                        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = c.search)))
                        val replies = mutableListOf<Pair<String, String>>()
                        for (t in c.turns) replies += ask(conv, t)
                        e.store.messages.value[conv].orEmpty().filter { it.role == Role.AI }.forEach { a -> a.usage?.let { u -> yuan += costOf(u) } }
                        val last = replies.last()
                        val ct = count(last.first)
                        summary += "${m.name}\t${c.name}\t本应用\t$r\t${last.first.length}\t" + detectors.joinToString("\t") { ct[it.first].toString() } + "\t" + (if (last.second.contains("search")) "搜了" else "没搜")
                        say("\n==== ${c.name} · 本应用 · 第 $r 次 ====")
                        c.turns.zip(replies).forEach { (q, a) ->
                            say("用户：$q")
                            if (a.second.isNotEmpty()) say("〔工具：${a.second}〕")
                            say("${m.name}（${a.first.length} 字）：${a.first}")
                        }
                        say("〔数到：" + ct.filter { it.value > 0 }.entries.joinToString("、") { "${it.key} ${it.value}" }.ifEmpty { "没有" } + "〕")

                        if (!bare) continue
                        // 裸调：不带任何设定，只发聊天记录（多轮的前几轮用它自己的回答），看哪些毛病是模型自带的
                        val msgs = mutableListOf<JsonObject>()
                        var raw = ""
                        for (t in c.turns) {
                            msgs += buildJsonObject { put("role", "user"); put("content", t) }
                            val res = runCatching { llm.chat(p, m.modelId, msgs, null, null) { _, _ -> } }
                            raw = res.map { it.content }.getOrElse { "出错：" + it.message.orEmpty().take(300) }.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                            res.getOrNull()?.usage?.let { u -> yuan += costOf(u) }
                            msgs += buildJsonObject { put("role", "assistant"); put("content", raw) }
                        }
                        val cb = count(raw)
                        summary += "${m.name}\t${c.name}\t裸调\t$r\t${raw.length}\t" + detectors.joinToString("\t") { cb[it.first].toString() } + "\t-"
                        say("\n---- ${c.name} · 裸调 · 第 $r 次（${raw.length} 字） ----\n$raw")
                        say("〔数到：" + cb.filter { it.value > 0 }.entries.joinToString("、") { "${it.key} ${it.value}" }.ifEmpty { "没有" } + "〕")
                    }
                    say("\n〔这位一共花了约 ${"%.2f".format(yuan)} 元（按软件价目表算；价目表里没有的模型算 0）〕")
                    summary += "${m.name}\t花费\t${"%.2f".format(yuan)}"
                } finally {
                    tmp.deleteRecursively()
                }
            }
        }.awaitAll()
        File(outDir, "汇总.tsv").writeText("成员\t题\t问法\t第几次\t字数\t" + detectors.joinToString("\t") { it.first } + "\t搜没搜\n" + summary.joinToString("\n"), Charsets.UTF_8)
    }
}
