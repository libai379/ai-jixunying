package com.guixing.jixunying

import com.guixing.jixunying.engine.LlmClient
import com.guixing.jixunying.engine.Prompts
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.ModelInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * 用真模型看「理解」这一半：每位成员按 App 里真实的设定，收到「要写字 / 不要字 / 没说」三种画图要求，
 * 看它们写给画图模型的描述对不对。只看描述，不真的画。
 * 只在设了 JXY_LIVE_DRAW=数据文件夹 时跑（用那份数据里配好的服务商和成员）。不打印 Key。
 */
class LiveDrawPromptTest {
    @Test
    fun membersWriteDrawPrompts() = runBlocking {
        val dir = System.getenv("JXY_LIVE_DRAW") ?: return@runBlocking
        val state = Storage(java.io.File(dir)).loadState() ?: return@runBlocking
        val llm = LlmClient { null }
        val tool = JsonObject(mapOf(
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive("generate_image"),
                "description" to JsonPrimitive("根据文字描述画一张图，画好后会直接显示给用户。"),
                "parameters" to JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "prompt" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("画面描述：主体、风格、构图、光线、色彩，越具体越好"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("prompt"))),
                )),
            )),
        ))
        val asks = listOf(
            "要写字" to "画一张中秋节海报，上面写「月圆人团圆」",
            "不要字" to "画一张中秋夜景，画面里不要有任何文字",
            "没说" to "画一张中秋夜景",
        )
        for (m in state.members) {
            val p = state.provider(m.providerId) ?: continue
            val conv = Conversation("t", "测试", listOf(m.id))
            val model = p.models.firstOrNull { it.id == m.modelId } ?: ModelInfo(m.modelId)
            val sys = Prompts.system(state, conv, m, canSearch = false, canDraw = true, canSeeImages = model.vision,
                independentRound = false, calledBy = null)
            println("════ ${m.name}（${m.modelId}）")
            for ((label, ask) in asks) {
                val msgs = listOf(
                    buildJsonObject { put("role", "system"); put("content", sys) },
                    buildJsonObject { put("role", "user"); put("content", ask) },
                )
                val r = runCatching { llm.chat(p, m.modelId, msgs, m.temperature, JsonArray(listOf(tool))) { _, _ -> } }
                    .getOrElse { println("  [$label] 出错：${it.message?.take(120)}"); continue }
                val call = r.toolCalls.firstOrNull { it.name == "generate_image" }
                val prompt = call?.let { runCatching { com.guixing.jixunying.engine.Json.parse(it.arguments).jsonObject["prompt"]?.jsonPrimitive?.content }.getOrNull() }
                if (prompt == null) println("  [$label] 没调画图，直接说：${r.content.take(100)}")
                else println("  [$label] 描述：$prompt")
            }
        }
    }
}
