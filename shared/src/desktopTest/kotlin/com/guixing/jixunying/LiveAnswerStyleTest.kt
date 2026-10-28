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
    private class Case(val name: String, val turns: List<String>, val search: Boolean)

    private val cases = listOf(
        Case("直播话术（截图那道，开联网）", listOf("我在抖音直播卖一款西马花珠沉香，6mm 108颗，帮我写一段开场介绍的话术", "达拉干沉香地位和历史底蕴"), search = true),
        Case("闲聊", listOf("最近直播完嗓子都哑了，有啥办法"), search = false),
        Case("知识", listOf("沉香和檀香有什么区别？"), search = false),
        Case("排查", listOf("RTSS 里「在OSD上显示」那一栏是灰的，勾不上，怎么回事？"), search = false),
        Case("拿主意", listOf("我想把直播从抖音搬到视频号，你觉得行不行？"), search = false),
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
            e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(autoExtract = false, stances = false, aiVerify = false))))
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
                for (c in cases) {
                    // 本应用：单聊，按题开关联网
                    val conv = e.call(Command.CreateConversation(listOf(m.id), c.name)).data
                    e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(webSearch = c.search)))
                    var app = ""
                    for (t in c.turns) app = ask(conv, t)
                    say("\n—— ${c.name} · 本应用：${shape(app.substringAfter("〕\n"))}")
                    say(app)
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
