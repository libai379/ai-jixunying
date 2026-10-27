package com.guixing.jixunying

import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ProviderConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 狼人杀用的假模型（不联网、不花钱）：按法官问的是哪一步回一段像样的话，分几块流式发（界面上能看到边出字）。
 * 打法很简单：谁都投「可选」里第一个（狼不投队友），所以一局几天就能打完。
 * 模型名 fake-broken 一律回 500（测「调不通 → 问主持人重试还是跳过」）。
 */
class WolfFakeLlm {
    private val port = java.net.ServerSocket(0).use { it.localPort }
    val base = "http://127.0.0.1:$port/v1"
    /** 收到的每次请求的 system 原文（看经验有没有带上）。 */
    val systems = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var delayMs = 0L

    private val srv: EmbeddedServer<*, *> = embeddedServer(CIO, port = port) {
        routing {
            post("/v1/chat/completions") {
                val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                if (req["model"]!!.jsonPrimitive.content == "fake-broken") {
                    call.respondText("""{"error":{"message":"服务器开小差了"}}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    return@post
                }
                val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                val system = msgs.first()["content"]!!.jsonPrimitive.content
                val user = msgs.last()["content"].let { c -> (c as? JsonPrimitive)?.content ?: c!!.jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() } }
                systems += system
                val text = answer(system, user)
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                call.respondTextWriter(ContentType.Text.EventStream) {
                    for (part in text.chunked(12)) {
                        write("data: " + Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.buildJsonObject {
                            put("choices", kotlinx.serialization.json.buildJsonArray {
                                add(kotlinx.serialization.json.buildJsonObject {
                                    put("index", JsonPrimitive(0))
                                    put("delta", kotlinx.serialization.json.buildJsonObject { put("content", JsonPrimitive(part)) })
                                })
                            })
                        }) + "\n\n")
                        flush()
                    }
                    write("data: [DONE]\n\n"); flush()
                }
            }
        }
    }.also { it.start(wait = false) }

    fun stop() = srv.stop(100, 200)

    private fun answer(system: String, user: String): String {
        val me = Regex("你是 (\\d+)号").find(system)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mates = Regex("你的狼队友：([^。\\n]*)").find(system)?.groupValues?.get(1)?.let { s -> Regex("(\\d+)号").findAll(s).map { it.groupValues[1].toInt() }.toSet() }.orEmpty()
        val ask = user.substringAfterLast("【现在】", user)
        val choices = Regex("可选：([^。]*)。").findAll(ask).lastOrNull()?.groupValues?.get(1)?.let { s -> Regex("(\\d+)号").findAll(s).map { it.groupValues[1].toInt() }.toList() }.orEmpty()
        val pick = choices.firstOrNull { it !in mates } ?: choices.firstOrNull() ?: 0
        fun target(n: Int) = "<想法>${me}号想了想，选 ${n}号。</想法>{\"target\": $n, \"reason\": \"他发言有点怪\"}"
        return when {
            "复盘" in system -> "{\"感想\": \"${me}号：这局我太早暴露了。\", \"经验\": \"${me}号的经验：先听完再表态。\"}"
            "你手里" in ask -> if ("不能用解药" in ask) "{\"save\": false, \"poison\": 0}" else "<想法>先救人</想法>{\"save\": true, \"poison\": 0}"
            "奇迹商人" in ask && "gift" in ask -> "{\"target\": ${choices.firstOrNull() ?: 0}, \"gift\": \"shield\"}"
            "说说你现在的判断" in ask -> "{\"probs\": {}, \"most\": ${choices.firstOrNull() ?: Regex("(\\d+)号").find(ask.substringAfter("要判断的座位："))?.groupValues?.get(1) ?: 0}, \"confidence\": 60, \"why\": \"感觉\"}"
            "PK 发言" in ask -> "<想法>得辩护</想法><发言>我是好人，${me}号真的不是狼，别投我。</发言>"
            "轮到你发言" in ask -> "<想法>我是${me}号，先稳住。</想法><发言>我是${me}号。昨晚的情况大家都看到了，我觉得前面几位的发言都偏保守，我先听后面的，投票时再定。</发言>"
            "留遗言" in ask -> "<发言>我是${me}号，我是好人，大家好好找狼。</发言>"
            else -> target(pick)
        }
    }

    companion object {
        /** 一份只有假模型的数据：names 里每个名字一位 AI 成员（broken 里的名字用会出错的模型）。 */
        fun seed(root: File, base: String, names: List<String>, broken: Set<String> = emptySet()) {
            root.mkdirs()
            Storage(root).saveState(AppState(
                providers = listOf(ProviderConfig("pf", "custom", "假模型", base, "sk-fake")),
                members = names.mapIndexed { i, n -> Member("m$i", n, listOf("🧠", "🤖", "📊", "🦊", "🐼", "🐯", "🦁", "🐸", "🐙", "🦉", "🐳", "🦄")[i % 12],
                    providerId = "pf", modelId = if (n in broken) "fake-broken" else "fake-wolf") },
            ))
        }
    }
}
