package com.guixing.jixunying

import com.guixing.jixunying.engine.LlmClient
import com.guixing.jixunying.engine.Recorder
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.RecorderPick
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用真模型试记录员（压缩摘要、挑长期记忆）的输出能不能用。只在设了 JXY_LIVE_RECORDER=数据文件夹 时跑，
 * 用那个文件夹里配好的服务商（自动挑 mimo-v2.6-flash 这类便宜的）。不打印 Key。
 */
class LiveRecorderTest {
    @Test
    fun recorderWithRealModel() = runBlocking {
        val dir = System.getenv("JXY_LIVE_RECORDER") ?: return@runBlocking
        val state = Storage(java.io.File(dir)).loadState() ?: return@runBlocking
        val (p, model) = RecorderPick.pick(state) ?: return@runBlocking
        println("记录员：$model（${p.name}）")
        val llm = LlmClient { null }
        fun user(t: String) = buildJsonObject { put("role", "user"); put("content", t) }

        val transcript = """
            【我】我在北京做产品经理，最近在找房子，预算每月 5000 以内，最好离国贸近一点。
            【阿德】国贸附近 5000 以内比较紧，可以看看双井、劲松这一带的老小区一居室，通勤 10 分钟左右。
            【阿麦】补充一下：签合同前一定拍照留存房屋现状，押金退还条款要写清楚起算日。
            【我】好的。以后回答我请先给结论，别绕弯子。另外帮我记一下：我对猫毛过敏，找房时别推荐养过猫的。
            【阿德】明白。结论：优先看劲松、双井的老小区一居室，签约前查清是否养过宠物。
        """.trimIndent()

        val summary = llm.chat(p, model, listOf(user(Recorder.summarizePrompt("", transcript, "我"))), null, null) { _, _ -> }.content
        println("—— 摘要 ——\n$summary")
        assertTrue(summary.length > 30)
        assertTrue("先给结论" in summary || "结论" in summary, "用户的要求要保留")

        val existing = listOf(MemoryItem("m1", "在北京工作", "关于我"), MemoryItem("m2", "成员用 DeepSeek、MiniMax、智谱、小米四家", "事实", topic = "AI集训营"))
        val raw = llm.chat(p, model, listOf(user(Recorder.extractPrompt(existing, transcript, "我", Recorder.topics(existing), "", false))), null, null) { _, _ -> }.content
        println("—— 挑记忆原始输出 ——\n$raw")
        val out = Recorder.parseExtraction(raw)
        assertNotNull(out, "输出要能解析")
        println("  对话话题：${out.conversationTopic}")
        val lines = transcript.lines().filter { it.startsWith("【我】") }.mapIndexed { i, l -> "u$i" to l.removePrefix("【我】") }
        val items = out.items
        items.forEach { println("  [${it.kind}][${it.topic}] ${it.text} quote=${it.quote} → ${Recorder.locateQuote(it.quote, lines)?.first} replaces=${it.replaces}") }
        assertTrue(items.size in 1..3)
        assertTrue(items.any { "过敏" in it.text || "先给结论" in it.text || "产品经理" in it.text })

        // 记忆二期：聊的是一个长期项目，看它认不认得出话题、项目的事记成话题记忆
        val project = """
            【我】AI集训营 那个项目，成员固定四家这条禁令撤掉吧，以后可以加别的模型。
            【阿德】好的，那加新成员时模型名和价格从官方原文核对。
            【我】还有，发版只在我说发的时候发，GitHub 上每一版都留着。
            【阿德】明白，旧版的 Release 不删。
        """.trimIndent()
        val raw2 = llm.chat(p, model, listOf(user(Recorder.extractPrompt(existing, project, "我", Recorder.topics(existing), "", false))), null, null) { _, _ -> }.content
        println("—— 项目聊天挑记忆 ——\n$raw2")
        val out2 = Recorder.parseExtraction(raw2)
        assertNotNull(out2)
        val lines2 = project.lines().filter { it.startsWith("【我】") }.mapIndexed { i, l -> "u$i" to l.removePrefix("【我】") }
        println("  对话话题：${out2.conversationTopic}")
        out2.items.forEach { println("  [${it.kind}][${it.topic}] ${it.text} quote=${it.quote} → ${Recorder.locateQuote(it.quote, lines2)?.first} replaces=${it.replaces}") }
    }
}
