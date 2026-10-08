package com.guixing.jixunying.engine

import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SearchSource
import com.guixing.jixunying.model.Usage
import io.ktor.client.request.get
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext

class ToolCall(val id: String, val name: String, val arguments: String, val type: String = "function")

class ChatResult(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage,
    val finishReason: String?,
)

class ApiException(val status: Int, val body: String) : Exception("HTTP $status：${body.take(400)}")

/** 切换思考可能用到的请求字段（各家不一样，见 model/Thinking.kt）。 */
private val THINKING_KEYS = listOf("thinking", "enable_thinking", "thinking_budget", "reasoning_effort", "reasoning", "reasoning_split", "chat_template_kwargs")

/** OpenAI 兼容的 /chat/completions，流式。国内外各家都走这一套。 */
class LlmClient(private val proxyOf: (ProviderConfig) -> String?) {

    /** 每次调用成功后记账（Engine 设置；UsageTag 说明算谁的账）。 */
    @Volatile var onUsage: ((ProviderConfig, String, Usage, UsageTag?) -> Unit)? = null

    /** 某个模型不接受的参数，出过一次 400 就记住，以后不再传。见教训库第 1、2 条。 */
    private val droppedParams = ConcurrentHashMap<String, MutableSet<String>>()

    private fun url(p: ProviderConfig, path: String) = p.baseUrl.trimEnd('/') + path

    /**
     * @param tools 函数工具 + 平台内置工具（Kimi 的 builtin_function、智谱的 web_search）
     * @param extra 额外放进请求体的字段（千问的 enable_search 等）
     * @param onSources 平台内置搜索返回的出处（智谱 web_search、千问 search_info）
     * @param thinking 切换思考的字段（见 model/Thinking.kt）；模型不认就去掉重试，并记住
     */
    suspend fun chat(
        provider: ProviderConfig,
        model: String,
        messages: List<JsonObject>,
        temperature: Double?,
        tools: JsonArray?,
        extra: JsonObject? = null,
        onSources: (List<SearchSource>) -> Unit = {},
        thinking: JsonObject? = null,
        onDelta: (content: String, reasoning: String) -> Unit,
    ): ChatResult {
        val key = provider.id + "|" + model
        var attempt = 0
        while (true) {
            val dropped = droppedParams[key] ?: emptySet()
            val usableTools = tools?.filter { t ->
                val type = (t as? JsonObject)?.str("type")
                type == "function" || "native_search" !in dropped
            }
            val body = buildJsonObject {
                put("model", model)
                put("messages", JsonArray(messages))
                put("stream", true)
                if ("stream_options" !in dropped) put("stream_options", buildJsonObject { put("include_usage", true) })
                if (temperature != null && "temperature" !in dropped) put("temperature", temperature)
                if (!usableTools.isNullOrEmpty() && "tools" !in dropped) put("tools", JsonArray(usableTools))
                if (extra != null && "native_search" !in dropped) extra.forEach { (k, v) -> put(k, v) }
                if (thinking != null && "thinking" !in dropped) thinking.forEach { (k, v) -> put(k, v) }
            }
            try {
                val r = stream(provider, body, onSources, onDelta)
                // 没返回用量的也记一条（花费页会说「有几次没返回用量，没算进去」）
                val tag = currentCoroutineContext()[UsageTag]
                runCatching { onUsage?.invoke(provider, model, r.usage, tag) }
                return r
            } catch (e: ApiException) {
                attempt++
                val bad = guessBadParam(e, body)
                if (bad != null && attempt <= 4) {
                    droppedParams.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(bad)
                    continue
                }
                throw e
            }
        }
    }

    fun toolsDropped(provider: ProviderConfig, model: String) =
        droppedParams[provider.id + "|" + model]?.contains("tools") == true

    fun nativeSearchDropped(provider: ProviderConfig, model: String) =
        droppedParams[provider.id + "|" + model]?.contains("native_search") == true

    /** 这个模型不认切换思考的参数（报过 400），之后按它自己的默认方式回答。 */
    fun thinkingDropped(provider: ProviderConfig, model: String) =
        droppedParams[provider.id + "|" + model]?.contains("thinking") == true

    private fun guessBadParam(e: ApiException, body: JsonObject): String? {
        if (e.status !in listOf(400, 422)) return null
        val t = e.body.lowercase()
        val hasNative = "enable_search" in body ||
            (body["tools"] as? JsonArray)?.any { (it as? JsonObject)?.str("type") != "function" } == true
        // 报错里点名了我们发的思考字段（「unknown parameter: thinking」「enable_thinking is not supported」之类）；
        // 智谱的报错是中文、不带字段名：「该模型始终思考，不支持关闭思考；请使用 low、high 或 max。」
        val sentThinking = THINKING_KEYS.filter { it in body }
        val aboutThinking = sentThinking.any { it in t || it.replace('_', ' ') in t } || "思考" in t
        return when {
            "temperature" in t && "temperature" in body -> "temperature"
            "stream_options" in t || "include_usage" in t -> "stream_options"
            sentThinking.isNotEmpty() && "reasoning_content" !in t && aboutThinking -> "thinking"
            hasNative && ("enable_search" in t || "search_options" in t || "web_search" in t || "builtin" in t || "search" in t) -> "native_search"
            ("tool" in t || "function" in t) && "tools" in body -> "tools"
            else -> null
        }
    }

    private suspend fun stream(
        provider: ProviderConfig,
        body: JsonObject,
        onSources: (List<SearchSource>) -> Unit,
        onDelta: (String, String) -> Unit,
    ): ChatResult {
        val started = System.currentTimeMillis()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, ToolSlot>()
        var usage = Usage()
        var finish: String? = null
        var sourcesSent = false
        fun checkSources(obj: JsonObject) {
            if (sourcesSent) return
            val found = parseSources(obj) ?: obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { c ->
                parseSources(c) ?: (c["delta"] as? JsonObject)?.let(::parseSources) ?: (c["message"] as? JsonObject)?.let(::parseSources)
            }
            if (!found.isNullOrEmpty()) { sourcesSent = true; onSources(found) }
        }

        Http.client(proxyOf(provider)).preparePost(url(provider, "/chat/completions")) {
            header("Authorization", "Bearer ${provider.apiKey.trim()}")
            header("Accept", "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.execute { resp ->
            if (resp.status.value !in 200..299) throw ApiException(resp.status.value, resp.bodyAsText())
            val ct = resp.headers["Content-Type"].orEmpty()
            if (!ct.contains("event-stream")) {
                // 有的服务商忽略 stream，直接回整段 JSON
                val text = resp.bodyAsText()
                val obj = runCatching { Json.parse(text).jsonObject }.getOrNull()
                    ?: throw ApiException(resp.status.value, text)
                obj["error"]?.let { throw ApiException(resp.status.value, it.toString()) }
                checkBaseResp(obj)
                checkSources(obj)
                val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                val c = msg?.str("content").orEmpty()
                val r = msg?.str("reasoning_content") ?: msg?.str("reasoning") ?: ""
                content.append(c); reasoning.append(r); onDelta(c, r)
                msg?.get("tool_calls")?.let { arr -> collectToolCalls(arr, calls) }
                usage = parseUsage(obj["usage"]) ?: usage
                return@execute
            }
            val ch = resp.bodyAsChannel()
            while (true) {
                val line = ch.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val obj = runCatching { Json.parse(data).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw ApiException(200, it.toString()) }
                checkBaseResp(obj)
                parseUsage(obj["usage"])?.let { usage = it }
                checkSources(obj)
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                choice.str("finish_reason")?.let { finish = it }
                val delta = choice["delta"]?.jsonObject ?: continue
                val c = delta.str("content").orEmpty()
                val r = delta.str("reasoning_content") ?: delta.str("reasoning") ?: ""
                if (c.isNotEmpty() || r.isNotEmpty()) {
                    content.append(c); reasoning.append(r); onDelta(c, r)
                }
                delta["tool_calls"]?.let { collectToolCalls(it, calls) }
            }
        }
        usage = usage.copy(millis = System.currentTimeMillis() - started)
        val toolCalls = calls.values.mapIndexedNotNull { i, s ->
            if (s.name.isEmpty()) null else ToolCall(s.id.toString().ifEmpty { "call_$i" }, s.name.toString(), s.args.toString(), s.type.ifEmpty { "function" })
        }
        return ChatResult(content.toString(), reasoning.toString(), toolCalls, usage, finish)
    }

    /** MiniMax 等会在 HTTP 200 里用 base_resp.status_code 报错（比如 1004 登录失败、1008 余额不足）。 */
    private fun checkBaseResp(o: JsonObject) {
        val br = o["base_resp"] as? JsonObject ?: return
        val code = br["status_code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) throw ApiException(if (code == 1004) 401 else 400, br.toString())
    }

    private class ToolSlot(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder(), var type: String = "")

    private fun collectToolCalls(arr: JsonElement, calls: MutableMap<Int, ToolSlot>) {
        (arr as? JsonArray)?.forEachIndexed { i, el ->
            val o = el.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull ?: i
            val slot = calls.getOrPut(idx) { ToolSlot() }
            o.str("id")?.let { if (slot.id.isEmpty()) slot.id.append(it) }
            o.str("type")?.let { if (slot.type.isEmpty()) slot.type = it }
            o["function"]?.jsonObject?.let { f ->
                f.str("name")?.let { if (slot.name.isEmpty()) slot.name.append(it) }
                f.str("arguments")?.let { slot.args.append(it) }
                (f["arguments"] as? JsonObject)?.let { slot.args.append(it.toString()) }
            }
        }
    }

    /** 智谱：web_search[{title, link, content}]；千问：search_info.search_results[{index, title, url}]。 */
    private fun parseSources(o: JsonObject): List<SearchSource>? {
        (o["web_search"] as? JsonArray)?.let { arr ->
            return arr.mapNotNull { it as? JsonObject }.mapNotNull { s ->
                val url = s.str("link") ?: s.str("url") ?: return@mapNotNull null
                SearchSource(s.str("title").orEmpty().ifBlank { url }, url, s.str("content").orEmpty().take(300))
            }
        }
        (o["search_info"] as? JsonObject)?.let { info ->
            val arr = info["search_results"] as? JsonArray ?: return null
            return arr.mapNotNull { it as? JsonObject }
                .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
                .mapNotNull { s ->
                    val url = s.str("url") ?: return@mapNotNull null
                    SearchSource(s.str("title").orEmpty().ifBlank { s.str("site_name").orEmpty() }, url, s.str("site_name").orEmpty())
                }
        }
        return null
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        val p = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val c = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cached = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
            ?: (u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
        return Usage(p, c, cached)
    }

    /** GET /models，返回模型 id 列表。 */
    suspend fun listModels(provider: ProviderConfig): List<String> {
        val resp = Http.client(proxyOf(provider)).get(url(provider, "/models")) {
            header("Authorization", "Bearer ${provider.apiKey.trim()}")
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        val root = Json.parse(text)
        val arr = (root as? JsonObject)?.let { it["data"] ?: it["models"] } ?: root
        return (arr as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { it.str("id") ?: it.str("name")?.removePrefix("models/") }
                ?: (el as? JsonPrimitive)?.contentOrNull
        }.orEmpty().distinct().sorted()
    }
}

object Json {
    fun parse(text: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text)
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
