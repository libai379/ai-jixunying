package com.guixing.jixunying.engine

import com.guixing.jixunying.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 画图。各家接口不一样，按服务商地址自动选协议：
 * - OpenAI 风格 /images/generations（同步）：OpenAI、智谱 CogView、火山方舟 Seedream、阶跃、百度千帆 iRAG、腾讯 TokenHub 混元生图、xAI
 * - 硅基流动：同上，但参数叫 image_size、返回 images[]
 * - 阿里百炼 / 千问AI平台：千问图像 qwen-image、通义万相 wan（异步任务）
 * - MiniMax：/image_generation（image-01）
 * - 可灵 Kling：异步任务，Key 可以是 API Key，也可以是「AccessKey:SecretKey」（自动签 JWT）
 * - 魔搭 ModelScope：异步任务（每天有免费额度）
 */
class ImageGen(private val proxyOf: (ProviderConfig) -> String?) {

    class Result(val bytes: ByteArray, val mime: String, val revisedPrompt: String?)

    enum class Protocol { OPENAI, SILICONFLOW, DASHSCOPE, MINIMAX, KLING, MODELSCOPE }

    companion object {
        fun protocolOf(p: ProviderConfig): Protocol {
            val u = p.baseUrl.lowercase()
            val id = p.presetId
            return when {
                id == "siliconflow" -> Protocol.SILICONFLOW
                id.startsWith("dashscope") || id == "qianwen" -> Protocol.DASHSCOPE
                id.startsWith("minimax") -> Protocol.MINIMAX
                id.startsWith("kling") -> Protocol.KLING
                id == "modelscope" -> Protocol.MODELSCOPE
                "siliconflow" in u -> Protocol.SILICONFLOW
                "dashscope" in u || "qianwenaiapi" in u || "maas.aliyuncs" in u -> Protocol.DASHSCOPE
                "minimax" in u -> Protocol.MINIMAX
                "klingai" in u -> Protocol.KLING
                "modelscope" in u -> Protocol.MODELSCOPE
                else -> Protocol.OPENAI
            }
        }

        /** "1024x1024" → 宽高比 "1:1"（MiniMax、可灵要比例不要像素）。 */
        fun aspectOf(size: String): String {
            val (w, h) = parseSize(size) ?: return "1:1"
            val r = w.toDouble() / h
            val options = listOf("1:1" to 1.0, "16:9" to 16 / 9.0, "9:16" to 9 / 16.0, "4:3" to 4 / 3.0, "3:4" to 3 / 4.0, "3:2" to 1.5, "2:3" to 2 / 3.0, "21:9" to 21 / 9.0)
            return options.minBy { kotlin.math.abs(it.second - r) }.first
        }

        fun parseSize(size: String): Pair<Int, Int>? {
            val parts = size.lowercase().split('x', '*', ':')
            val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
            return w to h
        }
    }

    suspend fun generate(provider: ProviderConfig, model: String, prompt: String, size: String): Result {
        val client = Http.client(proxyOf(provider))
        return when (protocolOf(provider)) {
            Protocol.OPENAI, Protocol.SILICONFLOW -> openAi(client, provider, model, prompt, size)
            Protocol.DASHSCOPE -> dashScope(client, provider, model, prompt, size)
            Protocol.MINIMAX -> miniMax(client, provider, model, prompt, size)
            Protocol.KLING -> kling(client, provider, model, prompt, size)
            Protocol.MODELSCOPE -> modelScope(client, provider, model, prompt, size)
        }
    }

    private fun base(p: ProviderConfig) = p.baseUrl.trimEnd('/')

    private fun root(p: ProviderConfig): String {
        val u = java.net.URI(p.baseUrl.trim())
        return u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    }

    private suspend fun HttpResponse.jsonOrThrow(): JsonObject {
        val text = bodyAsText()
        if (status.value !in 200..299) throw ApiException(status.value, text)
        return runCatching { Json.parse(text).jsonObject }.getOrNull() ?: throw ApiException(status.value, "返回不是 JSON：" + text.take(200))
    }

    private suspend fun download(client: HttpClient, url: String): Pair<ByteArray, String> {
        val img = client.get(url)
        if (img.status.value !in 200..299) throw ApiException(img.status.value, "图片下载失败")
        return img.bodyAsBytes() to (img.headers["Content-Type"]?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/png")
    }

    private fun sniffMime(b64: String) = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
    }

    // —— OpenAI 风格 ——
    private suspend fun openAi(client: HttpClient, p: ProviderConfig, model: String, prompt: String, size: String): Result {
        val silicon = protocolOf(p) == Protocol.SILICONFLOW
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            if (silicon) put("image_size", size) else put("size", size)
            if (!silicon) put("n", 1)
            if (p.baseUrl.contains("volces.com")) { put("response_format", "url"); put("watermark", false) }
        }
        val o = client.post(base(p) + "/images/generations") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.jsonOrThrow()
        (o["error"] as? JsonObject)?.let { throw ApiException(400, it.toString()) }
        val first = ((o["data"] ?: o["images"]) as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ApiException(200, "返回里没有图片：" + o.toString().take(300))
        val revised = first.str("revised_prompt")
        first.str("b64_json")?.let { return Result(Base64.getDecoder().decode(it), sniffMime(it), revised) }
        val url = first.str("url") ?: throw ApiException(200, "返回里没有图片地址：" + o.toString().take(300))
        val (bytes, mime) = download(client, url)
        return Result(bytes, mime, revised)
    }

    // —— 阿里百炼 / 千问AI平台：千问图像、通义万相 ——
    private suspend fun dashScope(client: HttpClient, p: ProviderConfig, model: String, prompt: String, size: String): Result {
        val m = model.lowercase()
        val qwenStyle = m.startsWith("qwen-image") || m.startsWith("z-image")
        val newWan = m.startsWith("wan2.6") || m.startsWith("wan2.7")
        val path = when {
            qwenStyle -> "/api/v1/services/aigc/multimodal-generation/generation"
            newWan -> "/api/v1/services/aigc/image-generation/generation"
            else -> "/api/v1/services/aigc/text2image/image-synthesis"
        }
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                if (qwenStyle || newWan) putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") { add(buildJsonObject { put("text", prompt) }) }
                    })
                } else put("prompt", prompt)
            }
            putJsonObject("parameters") {
                put("size", size.replace('x', '*')); put("n", 1); put("watermark", false); put("prompt_extend", true)
            }
        }
        suspend fun submit(async: Boolean) = client.post(root(p) + path) {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            if (async) header("X-DashScope-Async", "enable")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        var resp = submit(async = true)
        if (resp.status.value == 400) {
            val text = resp.bodyAsText()
            // 个别模型只支持同步
            resp = if (text.contains("async", ignoreCase = true) || text.contains("异步")) submit(async = false) else throw ApiException(400, text)
        }
        var o = resp.jsonOrThrow()
        val taskId = (o["output"] as? JsonObject)?.str("task_id")
        if (taskId != null) {
            o = poll(180) {
                val t = client.get(root(p) + "/api/v1/tasks/$taskId") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.jsonOrThrow()
                val out = t["output"] as? JsonObject
                when (out?.str("task_status")) {
                    "SUCCEEDED" -> t
                    "FAILED", "CANCELED", "UNKNOWN" -> throw ApiException(200, "生成失败：" + (out.str("message") ?: out.str("code")).orEmpty())
                    else -> null
                }
            }
        }
        val out = o["output"] as? JsonObject ?: throw ApiException(200, "返回里没有结果：" + o.toString().take(300))
        val url = ((out["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { (it["message"] as? JsonObject)?.get("content") as? JsonArray }
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.str("image") }
            ?: ((out["results"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("url")
            ?: throw ApiException(200, "返回里没有图片地址：" + out.toString().take(300))
        val (bytes, mime) = download(client, url)
        return Result(bytes, mime, null)
    }

    // —— MiniMax image-01 ——
    private suspend fun miniMax(client: HttpClient, p: ProviderConfig, model: String, prompt: String, size: String): Result {
        val o = client.post(base(p) + "/image_generation") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model); put("prompt", prompt); put("aspect_ratio", aspectOf(size))
                put("response_format", "base64"); put("n", 1); put("prompt_optimizer", true)
            }.toString())
        }.jsonOrThrow()
        val br = o["base_resp"] as? JsonObject
        val code = br?.get("status_code")?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) throw ApiException(400, "MiniMax：" + br?.str("status_msg").orEmpty() + "（$code）")
        val data = o["data"] as? JsonObject ?: throw ApiException(200, "返回里没有图片")
        (data["image_base64"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content?.let {
            return Result(Base64.getDecoder().decode(it), sniffMime(it), null)
        }
        val url = (data["image_urls"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: throw ApiException(200, "返回里没有图片")
        val (bytes, mime) = download(client, url)
        return Result(bytes, mime, null)
    }

    // —— 可灵 Kling ——
    private fun klingAuth(key: String): String {
        val k = key.trim()
        if (!k.contains(':')) return "Bearer $k"
        val (ak, sk) = k.split(':', limit = 2)
        val enc = Base64.getUrlEncoder().withoutPadding()
        val now = System.currentTimeMillis() / 1000
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString("""{"iss":"$ak","exp":${now + 1800},"nbf":${now - 5}}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sk.toByteArray(), "HmacSHA256")) }
        val sig = enc.encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        return "Bearer $header.$payload.$sig"
    }

    private suspend fun kling(client: HttpClient, p: ProviderConfig, model: String, prompt: String, size: String): Result {
        val b = base(p).removeSuffix("/v1")
        val o = client.post("$b/v1/images/generations") {
            header("Authorization", klingAuth(p.apiKey))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("model_name", model); put("prompt", prompt); put("n", 1); put("aspect_ratio", aspectOf(size)) }.toString())
        }.jsonOrThrow()
        if ((o["code"]?.jsonPrimitive?.intOrNull ?: 0) != 0) throw ApiException(400, "可灵：" + o.str("message").orEmpty())
        val taskId = (o["data"] as? JsonObject)?.str("task_id") ?: throw ApiException(200, "可灵没有返回任务编号")
        val done = poll(240) {
            val t = client.get("$b/v1/images/generations/$taskId") { header("Authorization", klingAuth(p.apiKey)) }.jsonOrThrow()
            val d = t["data"] as? JsonObject
            when (d?.str("task_status")) {
                "succeed" -> d
                "failed" -> throw ApiException(200, "可灵生成失败：" + d.str("task_status_msg").orEmpty())
                else -> null
            }
        }
        val url = (((done["task_result"] as? JsonObject)?.get("images") as? JsonArray)?.firstOrNull() as? JsonObject)?.str("url")
            ?: throw ApiException(200, "可灵返回里没有图片")
        val (bytes, mime) = download(client, url)
        return Result(bytes, mime, null)
    }

    // —— 魔搭 ModelScope ——
    private suspend fun modelScope(client: HttpClient, p: ProviderConfig, model: String, prompt: String, size: String): Result {
        val o = client.post(base(p) + "/images/generations") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            header("X-ModelScope-Async-Mode", "true")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("model", model); put("prompt", prompt); put("size", size) }.toString())
        }.jsonOrThrow()
        val taskId = o.str("task_id") ?: throw ApiException(200, "魔搭没有返回任务编号：" + o.toString().take(200))
        val done = poll(240) {
            val t = client.get(base(p) + "/tasks/$taskId") {
                header("Authorization", "Bearer ${p.apiKey.trim()}")
                header("X-ModelScope-Task-Type", "image_generation")
            }.jsonOrThrow()
            when (t.str("task_status")) {
                "SUCCEED" -> t
                "FAILED" -> throw ApiException(200, "魔搭生成失败：" + t.toString().take(200))
                else -> null
            }
        }
        val url = (done["output_images"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: throw ApiException(200, "魔搭返回里没有图片")
        val (bytes, mime) = download(client, url)
        return Result(bytes, mime, null)
    }

    /** 每 2 秒查一次，直到 check 返回非空；超时报错。 */
    private suspend fun <T : Any> poll(timeoutSec: Int, check: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            check()?.let { return it }
            delay(2_000)
        }
        throw ApiException(408, "画图超时（${timeoutSec} 秒还没画完）")
    }
}
