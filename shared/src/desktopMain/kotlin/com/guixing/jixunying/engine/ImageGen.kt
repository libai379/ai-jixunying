package com.guixing.jixunying.engine

import com.guixing.jixunying.model.ProviderConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * 画图：OpenAI 风格的 /images/generations。
 * 兼容 OpenAI gpt-image、智谱 CogView、火山方舟 Seedream、硅基流动 Kolors/FLUX（它的参数名和返回格式不一样，单独处理）。
 */
class ImageGen(private val proxyOf: (ProviderConfig) -> String?) {

    class Result(val bytes: ByteArray, val mime: String, val revisedPrompt: String?)

    suspend fun generate(provider: ProviderConfig, model: String, prompt: String, size: String): Result {
        val silicon = provider.baseUrl.contains("siliconflow")
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            if (silicon) put("image_size", size) else put("size", size)
            if (!silicon) put("n", 1)
            if (provider.baseUrl.contains("volces.com")) {
                put("response_format", "url"); put("watermark", false)
            }
        }
        val client = Http.client(proxyOf(provider))
        val resp = client.post(provider.baseUrl.trimEnd('/') + "/images/generations") {
            header("Authorization", "Bearer ${provider.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        val o = Json.parse(text).jsonObject
        val first = ((o["data"] ?: o["images"]) as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ApiException(resp.status.value, "返回里没有图片：" + text.take(300))
        val revised = first.str("revised_prompt")
        first.str("b64_json")?.let { return Result(Base64.getDecoder().decode(it), sniffMime(it), revised) }
        val url = first.str("url") ?: throw ApiException(resp.status.value, "返回里没有图片地址：" + text.take(300))
        val img = client.get(url)
        if (img.status.value !in 200..299) throw ApiException(img.status.value, "图片下载失败")
        val bytes = img.bodyAsBytes()
        return Result(bytes, img.headers["Content-Type"]?.substringBefore(';') ?: "image/png", revised)
    }

    private fun sniffMime(b64: String) = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
    }
}
