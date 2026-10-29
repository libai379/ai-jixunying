package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Usage
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 手机借电脑本机的模型（2026-10-24 用户：「我本地部署了两个模型……手机端加进去这个模型只有电脑端那边开着时候才能用」）。
 *
 * 电脑上地址是本机（127.0.0.1 / localhost）的服务商——Ollama 这类——手机自己连不上。手机连着电脑时：
 * - 把这些服务商抄一份到手机上，标 viaHost、不带 Key；第一次抄过来时，电脑上用它的成员也抄一份（以后手机上删了就不再加回来）。
 * - 手机上问这些模型时，LlmClient 把请求原样经加密线路交给电脑（Command.HostChat），电脑边问边把增量发回来
 *   （WireFrame.Part，10-25 起一个字一个字出），答完再发整段结果（工具调用、用量都在里面）。
 * 电脑关了、没连上：服务商和成员还在，问的时候直接说「要电脑开着」。
 */

/** 电脑上：这次 HostChat 的增量交给谁（RelayHost 放进协程上下文，Engine 处理 HostChat 时取出来）。 */
class HostChatParts(val onPart: (content: String, reasoning: String) -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(HostChatParts) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<HostChatParts>
}

object HostChatCodec {
    fun isLoopback(baseUrl: String): Boolean {
        val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || Regex("""127(\.\d{1,3}){3}""").matches(host)
    }

    fun request(messages: List<JsonObject>, temperature: Double?, tools: JsonArray?, extra: JsonObject?, thinking: JsonObject?): String =
        buildJsonObject {
            put("messages", JsonArray(messages))
            if (temperature != null) put("temperature", temperature)
            if (tools != null) put("tools", tools)
            if (extra != null) put("extra", extra)
            if (thinking != null) put("thinking", thinking)
        }.toString()

    class Request(val messages: List<JsonObject>, val temperature: Double?, val tools: JsonArray?, val extra: JsonObject?, val thinking: JsonObject?)

    fun parseRequest(s: String): Request {
        val o = AppJson.parseToJsonElement(s).jsonObject
        return Request(
            o["messages"]?.jsonArray?.map { it.jsonObject }.orEmpty(),
            (o["temperature"] as? JsonPrimitive)?.doubleOrNull,
            o["tools"] as? JsonArray,
            o["extra"] as? JsonObject,
            o["thinking"] as? JsonObject,
        )
    }

    fun result(r: ChatResult): String = buildJsonObject {
        put("content", r.content)
        put("reasoning", r.reasoning)
        put("toolCalls", buildJsonArray {
            r.toolCalls.forEach { t -> add(buildJsonObject { put("id", t.id); put("name", t.name); put("arguments", t.arguments); put("type", t.type) }) }
        })
        put("usage", buildJsonObject { put("prompt", r.usage.prompt); put("completion", r.usage.completion); put("cached", r.usage.cached); put("millis", r.usage.millis) })
        r.finishReason?.let { put("finishReason", it) }
    }.toString()

    fun parseResult(s: String): ChatResult {
        val o = AppJson.parseToJsonElement(s).jsonObject
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val u = o["usage"] as? JsonObject
        return ChatResult(
            content = o.s("content").orEmpty(),
            reasoning = o.s("reasoning").orEmpty(),
            toolCalls = (o["toolCalls"] as? JsonArray)?.map { it.jsonObject }?.map {
                ToolCall(it.s("id").orEmpty(), it.s("name").orEmpty(), it.s("arguments").orEmpty(), it.s("type") ?: "function")
            }.orEmpty(),
            usage = Usage(
                u?.get("prompt")?.jsonPrimitive?.intOrNull ?: 0, u?.get("completion")?.jsonPrimitive?.intOrNull ?: 0,
                u?.get("cached")?.jsonPrimitive?.intOrNull ?: 0, u?.get("millis")?.jsonPrimitive?.longOrNull ?: 0,
            ),
            finishReason = o.s("finishReason"),
        )
    }
}

/**
 * 手机上：抖音、小红书这些平台链接交给配对的电脑读、听（2026-10-29 用户：「抖音链接还是看不了对吧」）。
 * 手机上没有浏览器（这些平台不登录、不用真浏览器只给登录页），电脑上的 Edge 能读（docs/平台链接设计说明.md 第 5 节）。
 * online = 现在连着电脑；没连上时 page、listen 抛异常，说清楚要电脑开着。
 */
interface HostBrowser {
    val online: Boolean
    suspend fun page(url: String): JsonObject
    suspend fun listen(url: String, media: String): com.guixing.jixunying.model.CommandResult
}

/** 手机上跑：抄电脑本机的服务商、装上「经电脑问」「经电脑读平台链接」。 */
class HostModels(
    private val hub: Hub,
    private val engine: Engine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    fun start() {
        install()
        scope.launch {
            while (isActive) {
                runCatching { syncFromHost() }
                delay(3_000)
            }
        }
    }

    fun install() {
        engine.hostChat = { p, model, request, onDelta ->
            val remote = hub.remote.value
            if (remote == null || remote.conn.value !is ConnState.Connected)
                throw IllegalStateException("「${p.name}」是电脑本机的模型，要电脑开着、手机连上电脑才能用")
            val r = remote.callStreaming(Command.HostChat(p.id, model, request), onDelta)
            if (!r.ok) throw IllegalStateException(r.message)
            HostChatCodec.parseResult(r.data)
        }
        engine.hostBrowser = object : HostBrowser {
            override val online get() = hub.remote.value?.conn?.value is ConnState.Connected
            private fun remote() = hub.remote.value?.takeIf { it.conn.value is ConnState.Connected }
                ?: throw IllegalStateException(PAGE_OFFLINE)
            override suspend fun page(url: String): JsonObject {
                val r = remote().call(Command.HostPage(url))
                if (!r.ok) throw IllegalStateException(r.message.ifBlank { "电脑没读成" })
                return AppJson.parseToJsonElement(r.data).jsonObject
            }
            override suspend fun listen(url: String, media: String) = remote().call(Command.HostListen(url, media))
        }
    }

    companion object {
        const val PAGE_OFFLINE = "手机上没有浏览器，这种平台链接要交给电脑读：要电脑开着、手机连上电脑"
    }

    /** 返回 true = 改了手机上的服务商或成员。 */
    internal suspend fun syncFromHost(): Boolean {
        val remote = hub.remote.value ?: return false
        if (remote.conn.value !is ConnState.Connected) return false
        val host = remote.store.state.value
        val mine = engine.state
        var changed = false
        for (hp in host.providers) {
            if (hp.viaHost || !HostChatCodec.isLoopback(hp.baseUrl)) continue
            val old = mine.provider(hp.id)
            if (old != null && !old.viaHost) continue   // 手机上自己建过同号的，不碰
            val copy = ProviderConfig(id = hp.id, presetId = hp.presetId, name = hp.name.removeSuffix("（电脑）") + "（电脑）",
                baseUrl = hp.baseUrl, models = hp.models, enabled = hp.enabled, viaHost = true)
            if (old?.copy(updatedAt = 0) == copy) continue   // 改的时间（两端同步用）不算变
            engine.call(Command.SaveProvider(copy))
            changed = true
            if (old == null) {
                // 第一次抄过来：电脑上用它的成员也抄（同号）；以后手机上删了就不再加回来
                host.members.filter { it.providerId == hp.id && mine.member(it.id) == null }
                    .forEach { engine.call(Command.SaveMember(it)) }
            }
        }
        return changed
    }
}
