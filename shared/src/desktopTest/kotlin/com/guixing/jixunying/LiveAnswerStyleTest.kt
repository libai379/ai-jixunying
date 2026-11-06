package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.LlmClient
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test

/**
 * 用户 2026-10-26：「模型对话回答找不到重点，又冗杂，上下文又太长，絮絮叨叨……不如官方原生 APP 好用」；
 * 10-28 拿截图对比：同样问「达拉干沉香地位和历史底蕴」，官方 App 直接给能在直播间念的话术，我们的先念四行「搜索失败了」、写成百科报告、还教他做事。
 * 同一位成员、同一组问题跑两种：「裸调」（只发聊天记录，不带我们的设定，接近官方接口）和「本应用」（走 Engine，带设定、记忆）。
 * 改设定前后各跑一次（JXY_LIVE_STYLE_LABEL 写 old / new），全文写进 JXY_LIVE_STYLE_OUT，人来读。
 * 只在设了 JXY_LIVE_STYLE=数据文件夹 时跑：只复制 state.json（不动原数据），记忆照常带上，关掉记录员、立场档案、AI 核对（省调用）。
 * JXY_LIVE_STYLE_ONLY=阿德,阿米 只测这几位（默认阿德）；JXY_LIVE_STYLE_BARE=0 不跑裸调。
 */
class LiveAnswerStyleTest {
    /** all = 每一轮的回答都打出来（被骂这几道要看它每句怎么接）。 */
    private class Case(val name: String, val turns: List<String>, val search: Boolean, val all: Boolean = false)

    private val cases = listOf(
        Case("直播话术（截图那道，开联网）", listOf("我在抖音直播卖一款西马花珠沉香，6mm 108颗，帮我写一段开场介绍的话术", "达拉干沉香地位和历史底蕴"), search = true),
        // 2026-11-06 华为上那段：第三问 DeepSeek 单列了「三个坑，务必避开」（用户：「第三段的坑完全没有必要」）
        Case("直播话术·文莱（华为那段，开联网）", listOf("我在抖音直播卖一款西马花珠沉香，6mm，从产地优势、地位、稀缺性、纹路油脂、味道、文玩价值、市场价值写一份话术",
            "达拉干沉香地位和历史底蕴", "文莱沉香地位，和历史底蕴"), search = true),
        // 2026-11-06 华为第二段：新对话一句就问，DeepSeek 把宋元明几本书一条条排（用户：「不够总结不够权威，没有夸的成分」）
        Case("西马历史（华为第二段，开联网）", listOf("西马花珠沉香手串地位，和历史底蕴"), search = true),
        Case("闲聊", listOf("最近直播完嗓子都哑了，有啥办法"), search = false),
        Case("知识", listOf("沉香和檀香有什么区别？"), search = false),
        Case("排查", listOf("RTSS 里「在OSD上显示」那一栏是灰的，勾不上，怎么回事？"), search = false),
        Case("拿主意", listOf("我想把直播从抖音搬到视频号，你觉得行不行？"), search = false),
        // 被骂（2026-11-04 用户在微信里拿阿苗测：「对方骂你，你话太多就输了」「学会狗一点」「还是太 AI 了」，
        // 认可的回法是「嫌我蠢还找我，咱俩谁的问题？[旺柴]」「你就说了个圆啊[旺柴]」「那你画，我瞅瞅[旺柴]」）
        Case("被骂（空骂）", listOf("蠢货玩意", "干啥啥不行"), search = false, all = true),
        Case("被骂（嫌写得差，抖音截图那段）", listOf("帮我写一句直播卖沉香手串的开场白，一句就行", "写的真垃圾，还不如我自己写", "我自己写我还要你有什么用"), search = false, all = true),
        Case("被骂（骂里有具体毛病）", listOf("帮我写一句直播卖沉香手串的开场白，一句就行", "蠢货，太长了，直播哪有工夫念这么多"), search = false, all = true),
        Case("被骂（叫它滚）", listOf("你这水平别干了，滚吧"), search = false, all = true),
        Case("被骂（拿主意时被怼）", listOf("我想把直播从抖音搬到视频号，你觉得行不行？", "你懂个屁，就是要搬"), search = false, all = true),
    )

    @Test
    fun compareAnswerStyle() = runBlocking {
        val src = System.getenv("JXY_LIVE_STYLE") ?: return@runBlocking
        val label = System.getenv("JXY_LIVE_STYLE_LABEL") ?: "run"
        val out = File(System.getenv("JXY_LIVE_STYLE_OUT") ?: File(File(src).parentFile, "live-style-$label.txt").path)
        out.writeText("", Charsets.UTF_8)
        fun say(line: String) { println(line); out.appendText(line + "\n", Charsets.UTF_8) }
        val only = (System.getenv("JXY_LIVE_STYLE_ONLY") ?: "阿德").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val bare = System.getenv("JXY_LIVE_STYLE_BARE") != "0"
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-style").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            val s = e.state.settings
            // JXY_LIVE_STYLE_NOMEM=1：不带长期记忆（看设定本身管不管用；用户的记忆里可能已经写着要怎么答）
            val noMem = System.getenv("JXY_LIVE_STYLE_NOMEM") == "1"
            e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(enabled = !noMem && s.memory.enabled, autoExtract = false, stances = false, aiVerify = false))))
            val llm = LlmClient { p -> if (p.useProxy) e.state.settings.proxy.trim().ifEmpty { null } else null }

            suspend fun ask(conv: String, text: String): String {
                e.call(Command.SendMessage(conv, text))
                withTimeoutOrNull(300_000) {
                    while (true) {
                        delay(1_000)
                        val ai = e.store.messages.value[conv].orEmpty().lastOrNull()
                        if (ai != null && ai.role == Role.AI && ai.status != MsgStatus.STREAMING) break
                    }
                }
                val ai = e.store.messages.value[conv].orEmpty().last()
                if (ai.error.isNotBlank()) return "出错：" + ai.error.take(300)
                val steps = ai.tools.joinToString("；") { t ->
                    "${t.kind}「${t.input.take(30)}」${if (t.ok) "${t.sources.size}条：" + t.sources.take(2).joinToString("、") { it.title.take(16) } else "失败"}${if (t.note.isNotBlank()) "（${t.note.take(60)}）" else ""}"
                }
                return (if (steps.isNotEmpty()) "〔工具：$steps〕\n" else "") + ai.content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            }

            fun shape(t: String): String {
                val lines = t.lines()
                val heads = lines.count { Regex("""^\s*#{1,6}\s""").containsMatchIn(it) }
                val bullets = lines.count { Regex("""^\s*([-*+•]|\d+[.、)])\s""").containsMatchIn(it) }
                val bold = Regex("""\*\*[^*]+\*\*""").findAll(t).count()
                val table = lines.count { it.trim().startsWith("|") }
                return "${t.length} 字，标题 $heads，列表行 $bullets，加粗 $bold，表格行 $table"
            }

            for (m in e.state.members.filter { it.name in only }) {
                val p = e.state.provider(m.providerId) ?: continue
                say("\n==================== ${m.name}（${m.modelId}）· $label ====================")
                // JXY_LIVE_STYLE_CASES=被骂：只跑名字里带这几个字的题
                val pick = System.getenv("JXY_LIVE_STYLE_CASES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                for (c in cases.filter { c -> pick == null || pick.any { it in c.name } }) {
                    // 本应用：单聊，按题开关联网
                    val conv = e.call(Command.CreateConversation(listOf(m.id), c.name)).data
                    // JXY_LIVE_STYLE_MEMORY=recall / full：这一轮用哪种记忆（2026-11-06 两种记忆）
                    val memMode = System.getenv("JXY_LIVE_STYLE_MEMORY").orEmpty()
                    e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = c.search, memoryMode = memMode)))
                    if (memMode == "recall") {
                        val r = com.guixing.jixunying.engine.Recorder.recall(e.state.memories, e.state.memoryUse, "", c.turns.last(), System.currentTimeMillis())
                        say("〔联想记忆挑了 ${r.items.size} 条（其中按眼下挑的 ${r.activated.size} 条，${r.items.sumOf { it.text.length }} 字）：" +
                            r.items.filter { it.id in r.activated }.joinToString("；") { it.text.take(40) } + "〕")
                    } else if (memMode == "full") {
                        val f = com.guixing.jixunying.engine.Recorder.forPrompt(e.state.memories, "")
                        say("〔完整记忆带了 ${f.size} 条，${f.sumOf { it.text.length }} 字〕")
                    }
                    var app = ""
                    if (c.all) say("\n—— ${c.name} · 本应用")
                    for (t in c.turns) {
                        app = ask(conv, t)
                        if (c.all) say("用户：$t\n${m.name}（${app.length} 字）：$app")
                    }
                    if (!c.all) {
                        say("\n—— ${c.name} · 本应用：${shape(app.substringAfter("〕\n"))}")
                        say(app)
                    }
                    if (!bare) continue
                    // 裸调：不带任何设定，只发聊天记录（多轮的前几轮用它自己的回答）
                    val msgs = mutableListOf<JsonObject>()
                    var raw = ""
                    for (t in c.turns) {
                        msgs += buildJsonObject { put("role", "user"); put("content", t) }
                        raw = runCatching { llm.chat(p, m.modelId, msgs, null, null) { _, _ -> }.content }
                            .getOrElse { "出错：" + it.message.orEmpty().take(300) }
                            .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                        msgs += buildJsonObject { put("role", "assistant"); put("content", raw) }
                    }
                    say("\n—— ${c.name} · 裸调：${shape(raw)}")
                    say(raw)
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
