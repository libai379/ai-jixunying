package com.guixing.jixunying.engine

import com.guixing.jixunying.model.EmbedModels
import com.guixing.jixunying.model.ProviderConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 向量（docs/向量和语音设计说明.md 第 2 节，2026-10-12）：POST {接口地址}/embeddings，OpenAI 兼容写法，
 * 智谱、千问、「自定义」都一样，所以这里只有一套。
 * - 请求：model、input（字符串数组）、dimensions（智谱、千问显式给 1024；「自定义」不发，用它自己的默认维度）。
 * - 返回：data[].index 是这条对应输入里的第几条，data[].embedding 是向量；按 index 对回顺序（不假设返回顺序和输入一样）。
 * - 出错：有的家出错也回 HTTP 200，内容是 {"error":{...}}（参考资料里智谱那一条），所以看到 error 对象也算失败。
 * - 一次最多几条（智谱 64、千问 10、自定义 10）由调用方按 EmbedModels.batchOf 切好再发。
 */
class Embeddings(private val proxyOf: (ProviderConfig) -> String?) {

    /**
     * 服务商收下了（2xx 而且返回的 data 齐全）就记一条账：kind = embed，prompt 是这次用掉的 token
     * （教训 53：接下了就算花了钱，后面我们自己解析失败也照样记）。
     */
    @Volatile var onBilled: ((ProviderConfig, String, Int) -> Unit)? = null

    /** 一次调用的结果：和 texts 一一对应的向量 + 这次用了多少 token。 */
    class Embedded(val vectors: List<FloatArray>, val promptTokens: Int)

    suspend fun embed(p: ProviderConfig, model: String, texts: List<String>): Embedded {
        if (texts.isEmpty()) return Embedded(emptyList(), 0)
        val resp = Http.client(proxyOf(p)).post(p.baseUrl.trimEnd('/') + "/embeddings") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                putJsonArray("input") { texts.forEach { add(it) } }
                if (EmbedModels.sendsDimensions(p)) put("dimensions", EmbedModels.DIM)
            }.toString())
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        val o = runCatching { Json.parse(text).jsonObject }.getOrNull()
            ?: throw ApiException(resp.status.value, "向量接口返回的不是 JSON：" + text.take(300))
        (o["error"] as? JsonObject)?.let { err ->
            val code = err.str("code").orEmpty()
            throw ApiException(400, "向量接口报错：" + (err.str("message") ?: err.toString().take(300)) + (if (code.isNotEmpty()) "（$code）" else ""))
        }
        val arr = o["data"] as? JsonArray ?: throw ApiException(resp.status.value, "向量接口没返回 data：" + text.take(300))
        val out = arrayOfNulls<FloatArray>(texts.size)
        for (el in arr) {
            val item = el as? JsonObject ?: continue
            val i = item["index"]?.jsonPrimitive?.intOrNull ?: continue
            if (i !in texts.indices || out[i] != null) continue
            val vec = (item["embedding"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull } ?: emptyList()
            if (vec.isEmpty()) continue
            out[i] = vec.toFloatArray()
        }
        val miss = out.indexOfFirst { it == null }
        if (miss >= 0) throw ApiException(resp.status.value, "向量接口少回了第 ${miss + 1} 条（一次发了 ${texts.size} 条）：" + text.take(300))
        val vectors = out.map { it!! }
        val dim = vectors.first().size
        if (vectors.any { it.size != dim }) throw ApiException(resp.status.value, "向量接口返回的维度不一致：" + vectors.map { it.size }.distinct().joinToString("、"))
        val tokens = (o["usage"] as? JsonObject)?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        runCatching { onBilled?.invoke(p, model, tokens) }
        return Embedded(vectors, tokens)
    }
}
