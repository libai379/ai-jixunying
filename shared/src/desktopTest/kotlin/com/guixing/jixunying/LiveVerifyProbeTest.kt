package com.guixing.jixunying

import com.guixing.jixunying.engine.LlmClient
import com.guixing.jixunying.engine.StanceJudge
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.WebSearch
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.JudgePick
import com.guixing.jixunying.model.StanceEntry
import com.guixing.jixunying.model.StanceOption
import com.guixing.jixunying.model.StanceTopic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test

/**
 * 看 AI 核实每一环：裁判搜了什么词、搜到什么、最后怎么判。只调裁判（便宜），不跑成员。
 * 只在设了 JXY_LIVE_VERIFY=数据文件夹 时跑：复制那份数据的 state.json 到临时文件夹再用，不打印 Key。
 */
class LiveVerifyProbeTest {
    @Test
    fun probe() = runBlocking {
        val src = System.getenv("JXY_LIVE_VERIFY") ?: return@runBlocking
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-verify").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val raw = Storage(tmp).loadState() ?: error("读不了 state.json")
            // JXY_VERIFY_ENGINE=ZHIPU：用服务商里智谱的 Key 走智谱搜索（每次 1 分钱）
            val state = if (System.getenv("JXY_VERIFY_ENGINE") == "ZHIPU") {
                val k = raw.providers.first { it.presetId == "zhipu" && it.apiKey.isNotBlank() }.apiKey
                raw.copy(settings = raw.settings.copy(search = raw.settings.search.copy(engine = com.guixing.jixunying.model.SearchEngine.ZHIPU, apiKeys = mapOf("ZHIPU" to k))))
            } else raw
            val (p, model) = JudgePick.pick(state) ?: error("没有裁判模型")
            println("裁判：$model（${p.name}）；搜索引擎：${state.settings.search.engine}")
            val members = state.members.take(2)
            val llm = LlmClient { null }
            val ws = WebSearch { state.settings.proxy.trim().ifEmpty { null } }
            val cases = listOf(
                Triple("珠穆朗玛峰最新的官方测量高度是多少", "8848.86 米", "8844.43 米"),
                Triple("Python 3 里 round(2.5) 的结果是多少", "2", "3"),
                Triple("《红楼梦》前八十回的作者是谁", "曹雪芹", "高鹗"),
            )
            for ((q, a, b) in cases) {
                val topic = StanceTopic(
                    id = "t", convId = "c", question = q,
                    options = listOf(StanceOption("A", a), StanceOption("B", b)),
                    entries = listOf(StanceEntry(members[0].id, "m1", option = "A", first = true), StanceEntry(members[1].id, "m2", option = "B", first = true)),
                )
                val pr = StanceJudge.verifyPrompt(topic, members, true)
                val tools = AppJson.parseToJsonElement("""[{"type":"function","function":{"name":"web_search","description":"Search the web for current information","parameters":{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}}}]""").jsonArray
                val working = mutableListOf<JsonObject>(buildJsonObject { put("role", "user"); put("content", pr.prompt) })
                println("\n=== 题目：$q（A $a / B $b）")
                val all = mutableListOf<com.guixing.jixunying.model.SearchSource>()
                var r = llm.chat(p, model, working, null, tools) { _, _ -> }
                println("第一次回答：工具调用 ${r.toolCalls.size} 个；正文 ${r.content.take(200)}")
                if (r.toolCalls.isNotEmpty()) {
                    working += buildJsonObject {
                        put("role", "assistant"); put("content", r.content)
                        if (r.reasoning.isNotEmpty()) put("reasoning_content", r.reasoning)
                        putJsonArray("tool_calls") { r.toolCalls.forEach { tc -> add(buildJsonObject { put("id", tc.id); put("type", tc.type); putJsonObject("function") { put("name", tc.name); put("arguments", tc.arguments.ifBlank { "{}" }) } }) } }
                    }
                    for (tc in r.toolCalls) {
                        val query = runCatching { AppJson.parseToJsonElement(tc.arguments).jsonObject["query"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        println("搜索词：$query")
                        val sr = runCatching { ws.search(query ?: "", state.settings.search) }.getOrElse { println("搜索出错：$it"); emptyList() }
                        val from = all.size
                        all += sr
                        sr.forEachIndexed { i, s -> println("  [${from + i + 1}] ${s.title.take(50)} | ${s.url.take(90)} | ${s.snippet.take(60)}") }
                        working += buildJsonObject { put("role", "tool"); put("tool_call_id", tc.id); put("content", sr.withIndex().joinToString("\n\n") { (i, s) -> "[${from + i + 1}] ${s.title}\n${s.url}\n${s.snippet}" }.ifEmpty { "搜索失败" }) }
                    }
                    r = llm.chat(p, model, working, null, null) { _, _ -> }
                }
                println("最后回答：${r.content.take(400)}")
                val v = StanceJudge.parseVerify(r.content, listOf("A", "B"), pr.anon)
                println("会存的出处：" + v?.cited.orEmpty().mapNotNull { all.getOrNull(it - 1)?.url }.ifEmpty { listOf("（无）") })
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
