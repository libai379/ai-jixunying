package com.guixing.jixunying.engine

import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.VideoMediaUse
import com.guixing.jixunying.model.VideoModel
import com.guixing.jixunying.model.VideoPick
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * 做视频，两家都只有异步任务：
 * - 千问AI平台 / 阿里百炼（DashScope）：提交 POST {域名}/api/v1/services/aigc/video-generation/video-synthesis（请求头 X-DashScope-Async: enable），
 *   拿 output.task_id，再 GET {域名}/api/v1/tasks/{task_id} 查到 SUCCEEDED，下载 output.video_url（24 小时有效）。
 *   带素材时（wan3.0 的 input.media）先把本地文件传到官方免费临时存储（GET /api/v1/uploads?action=getPolicy&model=…，再按返回的表单传 OSS），
 *   得到 oss:// 地址，提交时请求头加 X-DashScope-OssResourceResolve: enable。
 * - MiniMax（V2，海螺 H3）：POST {域名}/v2/video_generation（content 数组），拿 task_id，GET {域名}/v2/query/video_generation/{task_id}
 *   查到 succeeded，下载 task.content.url；素材用 data: URI（Base64）直接放进请求，请求体最多 64MB。
 * 出处：docs/参考资料.md「视频生成」（2026-10-20 / 21 原文）。按成功生成的秒数收费，失败不收。
 */
class VideoGen(private val proxyOf: (ProviderConfig) -> String?, private val pollMs: Long = 10_000) {

    /**
     * 用量：output = 输出秒数，input = 输入视频秒数，images = 输入的图片张数（MiniMax 超出免费张数要钱）。
     * 万相按「输入 + 输出」一起算（同一个单价，total）。
     */
    class Usage(val output: Double, val input: Double = 0.0, val images: Int = 0) {
        val total get() = output + input
    }

    class Result(val bytes: ByteArray, val usage: Usage, val resolution: String) {
        val seconds get() = usage.output
    }

    /** 已经做好（扣了钱）只是下载失败。 */
    class DownloadFailed(val usage: Usage, val resolution: String, message: String) : Exception(message) {
        val seconds get() = usage.output
    }

    /** 素材传到服务端之后的样子：用途 + 网址（oss://、https://、data:）。 */
    class Media(val use: String, val url: String)

    companion object {
        /** 千问 / 百炼的请求体（单独拿出来方便测试）。duration < 0 = 智能时长（只在带素材的 wan3.0 上传）。 */
        fun body(m: VideoModel, prompt: String, resolution: String, duration: Int, ratio: String, audio: Boolean, media: List<Media> = emptyList()): JsonObject = buildJsonObject {
            put("model", m.id)
            putJsonObject("input") {
                put("prompt", prompt)
                if (media.isNotEmpty()) putJsonArray("media") { media.forEach { x -> addJsonObject { put("type", x.use); put("url", x.url) } } }
            }
            putJsonObject("parameters") {
                if (m.bySize) put("size", m.sizes[resolution] ?: m.sizes.values.first())
                else { put("resolution", resolution); put("ratio", ratio) }
                put("duration", duration)
                put("prompt_extend", true)
                put("watermark", false)
                if (m.audioSwitch) put("audio", audio)
            }
        }

        /** MiniMax V2 的请求体。官方：文生视频 ratio 必填且不能是 adaptive；首尾帧时恒为 adaptive；参考类可选。 */
        fun minimaxBody(m: VideoModel, prompt: String, resolution: String, duration: Int, ratio: String, media: List<Media> = emptyList()): JsonObject = buildJsonObject {
            put("model", m.id)
            putJsonArray("content") {
                addJsonObject { put("type", "text"); put("text", prompt) }
                media.forEach { x ->
                    val kind = when (x.use) {
                        VideoMediaUse.VIDEO -> "video_url"
                        VideoMediaUse.AUDIO -> "audio_url"
                        else -> "image_url"
                    }
                    addJsonObject {
                        put("type", kind)
                        putJsonObject(kind) { put("url", x.url) }
                        put("role", x.use)
                    }
                }
            }
            put("resolution", resolution)
            put("duration", duration)
            put("ratio", when {
                media.any { it.use in VideoMediaUse.frames } -> "adaptive"
                media.isEmpty() && ratio !in m.ratios -> "16:9"
                ratio == "adaptive" || ratio in m.ratios -> ratio
                else -> "adaptive"
            })
            put("aigc_watermark", false)
        }
    }

    /**
     * 做一段视频。onTask 收到任务编号（停止时用它取消），onStatus 收到给人看的进度（「传素材」「排队中」「生成中」「下载中」）。
     * inputs = 素材（千问的本地文件先上传，MiniMax 的转成 Base64）；ratio 可以是 adaptive（带素材时按素材自适应）。
     * 超过 timeoutMin 分钟没做完就报错（任务在服务端可能还会做完并扣钱）。
     */
    suspend fun generate(p: ProviderConfig, modelId: String, prompt: String, resolution: String, duration: Int, ratio: String, audio: Boolean,
                         inputs: List<VideoInput> = emptyList(), timeoutMin: Int = 15,
                         onTask: (String) -> Unit = {}, onStatus: (String) -> Unit = {}): Result {
        val m = VideoPick.model(modelId)
        val res = VideoPick.resolutionFor(m, resolution)
        // 智能时长（-1）只有带素材的 wan3.0 能用；其余照模型的范围夹住
        val secs = if (duration < 0 && m.vendor == VideoPick.DASHSCOPE && inputs.isNotEmpty() && m.media.isNotEmpty()) -1 else VideoPick.durationFor(m, duration)
        val images = inputs.count { it.use in setOf(VideoMediaUse.FIRST, VideoMediaUse.LAST, VideoMediaUse.IMAGE) }
        val client = Http.client(proxyOf(p))
        val taskId = if (m.vendor == VideoPick.MINIMAX) {
            val media = inputs.map { i -> Media(i.use, i.url ?: ("data:${i.mime};base64," + Base64.getEncoder().encodeToString(i.bytes!!))) }
            onStatus("提交中")
            val sub = client.post("${root(p)}/v2/video_generation") {
                header("Authorization", "Bearer ${p.apiKey.trim()}")
                contentType(ContentType.Application.Json)
                setBody(minimaxBody(m, prompt, res, secs, ratio, media).toString())
            }.minimaxJson()
            sub.str("task_id") ?: throw ApiException(200, "没有返回任务编号：" + sub.toString().take(300))
        } else {
            val media = inputs.mapIndexed { idx, i ->
                if (i.url != null) Media(i.use, i.url)
                else { onStatus("传素材 ${idx + 1}/${inputs.size}"); Media(i.use, upload(client, p, m.id, i, idx)) }
            }
            val r = if (ratio == "adaptive" && m.media.isNotEmpty()) "adaptive" else VideoPick.ratioFor(m, ratio)
            onStatus("提交中")
            val sub = client.post("${root(p)}/api/v1/services/aigc/video-generation/video-synthesis") {
                header("Authorization", "Bearer ${p.apiKey.trim()}")
                header("X-DashScope-Async", "enable")
                if (media.any { it.url.startsWith("oss://") }) header("X-DashScope-OssResourceResolve", "enable")
                contentType(ContentType.Application.Json)
                setBody(body(m, prompt, res, secs, r, audio, media).toString())
            }.jsonOrThrow()
            (sub["output"] as? JsonObject)?.str("task_id") ?: throw ApiException(200, "没有返回任务编号：" + sub.toString().take(300))
        }
        onTask(taskId)
        onStatus("排队中")
        return await(p, m.id, taskId, res, secs, images, timeoutMin, onStatus)
    }

    /**
     * 把一份本地素材传到千问 / 百炼的免费临时存储，返回 oss:// 地址（48 小时有效，只有同一个账号、同一个模型能用）。
     * 官方「上传文件获取临时 URL」：GET /api/v1/uploads?action=getPolicy&model=…，返回 data 里的 upload_host、upload_dir、oss_access_key_id、
     * signature、policy、x_oss_object_acl、x_oss_forbid_overwrite；再 POST 表单到 upload_host（字段照官方示例的顺序，文件放最后）。
     */
    private suspend fun upload(client: HttpClient, p: ProviderConfig, model: String, i: VideoInput, idx: Int): String {
        val pol = client.get("${root(p)}/api/v1/uploads") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            parameter("action", "getPolicy")
            parameter("model", model)
        }.jsonOrThrow()
        val d = pol["data"] as? JsonObject ?: throw ApiException(200, "取上传凭证没返回 data：" + pol.toString().take(300))
        fun need(k: String) = d.str(k) ?: throw ApiException(200, "上传凭证里没有 $k")
        // 文件名只用英文字母数字（key 会出现在网址里），扩展名照原文件
        val ext = i.name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        val fileName = "jxy_${System.currentTimeMillis()}_$idx.$ext"
        val key = need("upload_dir") + "/" + fileName
        val r = client.post(need("upload_host")) {
            setBody(MultiPartFormDataContent(formData {
                append("OSSAccessKeyId", need("oss_access_key_id"))
                append("Signature", need("signature"))
                append("policy", need("policy"))
                append("x-oss-object-acl", need("x_oss_object_acl"))
                append("x-oss-forbid-overwrite", need("x_oss_forbid_overwrite"))
                append("key", key)
                append("success_action_status", "200")
                // 只给 filename：formData 自己会加「form-data; name="file"」（教训 69）
                append("file", i.bytes!!, Headers.build {
                    append(HttpHeaders.ContentType, i.mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }))
        }
        if (r.status.value != 200) throw ApiException(r.status.value, "传素材《${i.name}》失败：" + r.bodyAsText().take(300))
        return "oss://$key"
    }

    /** 等任务做完并下载。停止时取消不掉（已经在生成）的任务也用它在后台接着等。 */
    suspend fun await(p: ProviderConfig, modelId: String, taskId: String, resolution: String, duration: Int, images: Int = 0,
                      timeoutMin: Int = 15, onStatus: (String) -> Unit = {}): Result {
        val m = VideoPick.model(modelId)
        val client = Http.client(proxyOf(p))
        val deadline = System.currentTimeMillis() + timeoutMin * 60_000L
        var done: Task? = null
        while (done == null) {
            if (System.currentTimeMillis() > deadline) throw ApiException(408, "做视频超时（$timeoutMin 分钟还没做完，任务编号 $taskId）")
            delay(pollMs)
            val t = query(client, p, m, taskId)
            when (t.status) {
                "SUCCEEDED" -> done = t
                "FAILED", "CANCELED", "UNKNOWN" -> throw ApiException(200, "生成失败：" + listOfNotNull(t.status, t.error).joinToString(" "))
                "RUNNING" -> onStatus("生成中")
                else -> onStatus("排队中")
            }
        }
        val usage = done.usage?.let { if (it.images == 0 && images > 0) Usage(it.output, it.input, images) else it }
            ?: Usage(duration.toDouble().coerceAtLeast(0.0), images = images)
        val url = done.url ?: throw DownloadFailed(usage, resolution, "做好了但返回里没有视频地址")
        onStatus("下载中")
        val bytes = try {
            val r = client.get(url)
            if (r.status.value !in 200..299) throw DownloadFailed(usage, resolution, "视频下载失败（${r.status.value}）")
            r.bodyAsBytes()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DownloadFailed) {
            throw e
        } catch (e: Throwable) {
            throw DownloadFailed(usage, resolution, "视频下载失败：${e.message ?: e::class.simpleName}")
        }
        return Result(bytes, usage, resolution)
    }

    /** 查一次任务的结果。status 统一成千问的写法（PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED / UNKNOWN）。 */
    private class Task(val status: String?, val url: String?, val usage: Usage?, val error: String?)

    private suspend fun query(client: HttpClient, p: ProviderConfig, m: VideoModel, taskId: String): Task {
        if (m.vendor == VideoPick.MINIMAX) {
            val j = client.get("${root(p)}/v2/query/video_generation/$taskId") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.minimaxJson()
            val t = j["task"] as? JsonObject ?: throw ApiException(200, "查任务没返回 task：" + j.toString().take(300))
            val status = when (t.str("status")) {
                "queued" -> "PENDING"
                "running" -> "RUNNING"
                "succeeded" -> "SUCCEEDED"
                "failed" -> "FAILED"
                "cancelled" -> "CANCELED"
                else -> t.str("status")?.uppercase()
            }
            val u = t["usage"] as? JsonObject
            val usage = u?.let { Usage(it.num("output_seconds") ?: t.num("duration") ?: 0.0, it.num("input_seconds") ?: 0.0, it.num("input_image_count")?.toInt() ?: 0) }
            val err = (t["error"] as? JsonObject)?.let { e -> listOfNotNull(e.str("code"), e.str("message")).joinToString(" ") }
            return Task(status, (t["content"] as? JsonObject)?.str("url"), usage, err)
        }
        val j = client.get("${root(p)}/api/v1/tasks/$taskId") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.jsonOrThrow()
        val out = j["output"] as? JsonObject
        val u = j["usage"] as? JsonObject
        // 万相 3.0：usage.duration =「输入视频 + 输出视频」，两样同一个单价；老模型只有 output_video_duration 或 duration
        val usage = u?.let {
            val output = it.num("output_video_duration") ?: it.num("duration") ?: 0.0
            val input = it.num("input_video_duration") ?: ((it.num("duration") ?: output) - output).coerceAtLeast(0.0)
            Usage(output, input)
        }
        return Task(out?.str("task_status"), out?.str("video_url"), usage,
            listOfNotNull(out?.str("code"), out?.str("message")).joinToString(" ").ifBlank { null })
    }

    /**
     * 取消任务。千问：POST /api/v1/tasks/{task_id}/cancel，「只能取消 PENDING 状态的任务」；
     * MiniMax：DELETE /v2/video_generation/{task_id}，排队中的取消（「无扣费」），运行中的「处理中无法取消」。
     * 取消完再查一次，返回任务现在的状态（CANCELED = 取消掉了，不收费）；查不到返回 null。
     */
    suspend fun cancel(p: ProviderConfig, modelId: String, taskId: String): String? {
        val m = VideoPick.model(modelId)
        val client = Http.client(proxyOf(p))
        runCatching {
            if (m.vendor == VideoPick.MINIMAX) client.delete("${root(p)}/v2/video_generation/$taskId") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.bodyAsText()
            else client.post("${root(p)}/api/v1/tasks/$taskId/cancel") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.bodyAsText()
        }
        return runCatching { query(client, p, m, taskId).status }.getOrNull()
    }

    private fun root(p: ProviderConfig): String {
        val u = java.net.URI(p.baseUrl.trim())
        return u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    }

    private suspend fun HttpResponse.jsonOrThrow(): JsonObject {
        val text = bodyAsText()
        if (status.value !in 200..299) throw ApiException(status.value, text)
        return runCatching { Json.parse(text).jsonObject }.getOrNull() ?: throw ApiException(status.value, "返回不是 JSON：" + text.take(200))
    }

    /** MiniMax：出错时 HTTP 状态码是真的，返回 {"type":"error","error":{…}}；也防着 200 + base_resp.status_code 不为 0 的老写法。 */
    private suspend fun HttpResponse.minimaxJson(): JsonObject {
        val j = jsonOrThrow()
        val base = j["base_resp"] as? JsonObject
        val code = (base?.get("status_code") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        if (code != 0) throw ApiException(status.value, "${base?.str("status_msg")}（$code）")
        (j["error"] as? JsonObject)?.let { throw ApiException(status.value, it.str("message") ?: it.toString()) }
        return j
    }

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
}
