package com.guixing.jixunying.engine

import com.guixing.jixunying.model.Usage
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenAI 的 Responses 接口（2026-10-23，给「用 ChatGPT 登录」用；docs/ChatGPT订阅设计说明.md）。
 * 引擎里的消息、工具都是 chat/completions 的格式，这里来回转：
 * - system → instructions（官方：这条路不收 role=system 的消息条目）；对话中间的 system → developer 消息
 * - 用户的文字、图片 → input_text / input_image；AI 说过的话 → output_text
 * - AI 调过的工具 → function_call，工具结果 → function_call_output
 * - 函数工具放进一个命名空间（官方：函数工具要放进 namespace 或 additional_tools）；平台内置的搜索之类不传
 * - 每次都 store=false、stream=true（官方要求）；不传 temperature 等官方列了不支持的字段
 */
internal class ResponsesApi(private val proxyOf: () -> String?, private val baseUrl: String) {

    /** 模型调工具时回来的 function_call 带不带 namespace，回放历史时照原样带回去（按 call_id 记）。 */
    private val namespaces = ConcurrentHashMap<String, String>()

    /** 上一次回来的 function_call 原样（真登录测试 LiveChatGptTest 看格式用）。 */
    @Volatile var lastRawCalls: List<String> = emptyList()
        private set

    /** 某个字段报过「不支持」（subscription_sharing_unsupported_capability 带 param），以后不再传。 */
    private val dropped = ConcurrentHashMap.newKeySet<String>()

    fun body(model: String, messages: List<JsonObject>, tools: JsonArray?): JsonObject {
        val instructions = StringBuilder()
        val input = buildJsonArray {
            var seenNonSystem = false
            for (m in messages) {
                when (m.str("role")) {
                    "system" -> {
                        val text = textOf(m["content"])
                        if (!seenNonSystem) { if (instructions.isNotEmpty()) instructions.append("\n\n"); instructions.append(text) }
                        else add(message("developer", listOf(part("input_text", text))))
                    }
                    "user" -> { seenNonSystem = true; add(message("user", userParts(m["content"]))) }
                    "assistant" -> {
                        seenNonSystem = true
                        val text = textOf(m["content"])
                        if (text.isNotBlank()) add(message("assistant", listOf(part("output_text", text))))
                        (m["tool_calls"] as? JsonArray)?.forEach { el ->
                            val tc = el as? JsonObject ?: return@forEach
                            val f = tc["function"] as? JsonObject ?: return@forEach
                            val id = tc.str("id").orEmpty()
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", id)
                                put("name", f.str("name").orEmpty())
                                put("arguments", f.str("arguments") ?: (f["arguments"]?.toString() ?: "{}"))
                                namespaces[id]?.let { put("namespace", it) }
                            })
                        }
                    }
                    "tool" -> {
                        seenNonSystem = true
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", m.str("tool_call_id").orEmpty())
                            put("output", textOf(m["content"]))
                        })
                    }
                }
            }
        }
        val fns = tools?.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "function") return@mapNotNull null
            val f = o["function"] as? JsonObject ?: return@mapNotNull null
            buildJsonObject {
                put("type", "function")
                put("name", f.str("name").orEmpty())
                f.str("description")?.let { put("description", it) }
                f["parameters"]?.let { put("parameters", it) }
            }
        }.orEmpty()
        return buildJsonObject {
            put("model", model)
            if (instructions.isNotEmpty()) put("instructions", instructions.toString())
            put("input", input)
            put("store", false)
            put("stream", true)
            if (fns.isNotEmpty() && "tools" !in dropped) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "namespace")
                    put("name", NAMESPACE)
                    put("description", "AI集训营 给你的工具")
                    put("tools", JsonArray(fns))
                })
            })
            if ("reasoning" !in dropped) put("reasoning", buildJsonObject { put("summary", "auto") })
        }
    }

    /** 发一次；服务端说某个字段不支持就去掉重试（最多 3 次）。401 先刷新令牌再试一次。 */
    suspend fun chat(
        model: String,
        messages: List<JsonObject>,
        tools: JsonArray?,
        token: suspend (force: Boolean) -> String,
        onAccepted: () -> Unit,
        onDelta: (String, String) -> Unit,
    ): ChatResult {
        var tries = 0
        var forced = false
        lastRawCalls = emptyList()
        while (true) {
            try {
                return stream(body(model, messages, tools), token(forced), onAccepted, onDelta)
            } catch (e: ApiException) {
                tries++
                if (e.status == 401 && !forced) { forced = true; continue }
                val param = unsupportedParam(e)
                if (param != null && param !in dropped && tries <= 3) { dropped.add(param); continue }
                throw e
            }
        }
    }

    private fun unsupportedParam(e: ApiException): String? {
        if (e.status != 400 || "unsupported_capability" !in e.body) return null
        val p = runCatching { (Json.parse(e.body).jsonObject["error"] as? JsonObject)?.str("param") }.getOrNull().orEmpty()
        return when {
            p.startsWith("tools") -> "tools"
            p.startsWith("reasoning") -> "reasoning"
            else -> null
        }
    }

    private suspend fun stream(body: JsonObject, token: String, onAccepted: () -> Unit, onDelta: (String, String) -> Unit): ChatResult {
        val started = System.currentTimeMillis()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var usage = Usage()
        var finish: String? = null
        var completed = false
        Http.client(proxyOf()).preparePost(baseUrl.trimEnd('/') + "/responses") {
            header("Authorization", "Bearer $token")
            header("Accept", "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { resp ->
            if (resp.status.value !in 200..299) throw ApiException(resp.status.value, resp.bodyAsText())
            val ch = resp.bodyAsChannel()
            var accepted = false
            while (true) {
                val line = ch.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val ev = runCatching { Json.parse(data).jsonObject }.getOrNull() ?: continue
                if (!accepted) { accepted = true; onAccepted() }
                when (ev.str("type")) {
                    "response.output_text.delta" -> ev.str("delta")?.let { content.append(it); onDelta(it, "") }
                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                        ev.str("delta")?.let { reasoning.append(it); onDelta("", it) }
                    "response.reasoning_summary_part.done" -> if (reasoning.isNotEmpty()) { reasoning.append("\n\n"); onDelta("", "\n\n") }
                    "response.output_item.done" -> {
                        val item = ev["item"] as? JsonObject
                        if (item?.str("type") == "function_call") {
                            val id = item.str("call_id") ?: item.str("id").orEmpty()
                            item.str("namespace")?.let { namespaces[id] = it }
                            lastRawCalls = lastRawCalls + item.toString()
                            calls += ToolCall(id, item.str("name").orEmpty(), item.str("arguments") ?: "{}")
                        }
                    }
                    "response.completed", "response.incomplete" -> {
                        completed = ev.str("type") == "response.completed"
                        val r = ev["response"] as? JsonObject
                        usage = parseUsage(r?.get("usage")) ?: usage
                        finish = if (completed) (if (calls.isNotEmpty()) "tool_calls" else "stop")
                            else (r?.get("incomplete_details") as? JsonObject)?.str("reason") ?: "incomplete"
                    }
                    "response.failed", "error" -> {
                        val err = ((ev["response"] as? JsonObject)?.get("error") ?: ev["error"] ?: ev) as? JsonObject
                        val code = err?.str("code").orEmpty()
                        throw ApiException(statusOf(code), buildJsonObject { put("error", err ?: ev) }.toString())
                    }
                }
            }
        }
        if (!completed && finish == null) throw ApiException(502, "ChatGPT 的回答流中途断了（没收到 response.completed）")
        return ChatResult(content.toString(), reasoning.toString(), calls, usage.copy(millis = System.currentTimeMillis() - started), finish)
    }

    companion object {
        const val NAMESPACE = "jxy"

        private fun message(role: String, parts: List<JsonObject>) = buildJsonObject {
            put("type", "message"); put("role", role); put("content", JsonArray(parts))
        }

        private fun part(type: String, text: String) = buildJsonObject { put("type", type); put("text", text) }

        private fun userParts(c: JsonElement?): List<JsonObject> = when (c) {
            is JsonPrimitive -> listOf(part("input_text", c.content))
            is JsonArray -> c.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                when (o.str("type")) {
                    "text" -> part("input_text", o.str("text").orEmpty())
                    "image_url" -> {
                        val url = (o["image_url"] as? JsonObject)?.str("url") ?: o.str("image_url")
                        url?.let { buildJsonObject { put("type", "input_image"); put("image_url", it) } }
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }

        private fun textOf(c: JsonElement?): String = when (c) {
            is JsonPrimitive -> c.content
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
            else -> ""
        }

        private fun parseUsage(el: JsonElement?): Usage? {
            val u = el as? JsonObject ?: return null
            val p = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val c = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            val cached = (u["input_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
            return Usage(p, c, cached)
        }

        /** 官方「Structured Responses errors」里各个错误码的状态，流里报的错也照这个归类。 */
        private fun statusOf(code: String) = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> 429
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> 503
            "subscription_sharing_invalid_user" -> 401
            "subscription_sharing_unsupported_capability" -> 400
            "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported" -> 403
            else -> 500
        }

        /** 给用户看的话（报错里带了这些码时）。 */
        fun friendly(body: String): String? = when {
            "usage_limit_exceeded" in body -> "ChatGPT 订阅这段时间的额度用完了（可以在 ChatGPT 网页 设置 → 用量 里看）。等恢复了再用，或者先换别的成员。"
            "user_not_eligible" in body -> "这个 ChatGPT 账号不能把订阅额度给别的程序用（免费号或团队、企业版可能不行）。"
            "usage_unavailable" in body || "user_unavailable" in body -> "ChatGPT 那边暂时不可用，过一会儿再试。"
            "invalid_user" in body -> "ChatGPT 登录失效了：设置 → 模型服务 → ChatGPT 订阅 里重新登录。"
            else -> null
        }
    }
}
