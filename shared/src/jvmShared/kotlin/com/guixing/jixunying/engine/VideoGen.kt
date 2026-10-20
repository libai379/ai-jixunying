package com.guixing.jixunying.engine

import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.VideoModel
import com.guixing.jixunying.model.VideoPick
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 做视频：千问AI平台 / 阿里百炼的 DashScope 视频接口（只有异步）。
 * 提交 POST {域名}/api/v1/services/aigc/video-generation/video-synthesis（请求头 X-DashScope-Async: enable），
 * 拿 output.task_id，再 GET {域名}/api/v1/tasks/{task_id} 查到 SUCCEEDED，下载 output.video_url（24 小时有效）。
 * 出处：千问AI平台文档「文生视频」「wan3.0-video 视频生成」（2026-10-20 原文，记在 docs/参考资料.md）。
 * 按成功生成的秒数收费，失败不收。
 */
class VideoGen(private val proxyOf: (ProviderConfig) -> String?, private val pollMs: Long = 10_000) {

    class Result(val bytes: ByteArray, val seconds: Double, val resolution: String, val duration: Int)

    /** 已经做好（扣了钱）只是下载失败。 */
    class DownloadFailed(val seconds: Double, val resolution: String, message: String) : Exception(message)

    companion object {
        /** 请求体（单独拿出来方便测试）。 */
        fun body(m: VideoModel, prompt: String, resolution: String, duration: Int, ratio: String, audio: Boolean): JsonObject = buildJsonObject {
            put("model", m.id)
            putJsonObject("input") { put("prompt", prompt) }
            putJsonObject("parameters") {
                if (m.bySize) put("size", m.sizes[resolution] ?: m.sizes.values.first())
                else { put("resolution", resolution); put("ratio", ratio) }
                put("duration", duration)
                put("prompt_extend", true)
                put("watermark", false)
                if (m.audioSwitch) put("audio", audio)
            }
        }
    }

    /**
     * 做一段视频。onTask 收到任务编号（停止时用它取消），onStatus 收到给人看的进度（「排队中」「生成中」「下载中」）。
     * 超过 timeoutMin 分钟没做完就报错（任务在服务端可能还会做完并扣钱）。
     */
    suspend fun generate(p: ProviderConfig, modelId: String, prompt: String, resolution: String, duration: Int, ratio: String, audio: Boolean,
                         timeoutMin: Int = 15, onTask: (String) -> Unit = {}, onStatus: (String) -> Unit = {}): Result {
        val m = VideoPick.model(modelId)
        val res = VideoPick.resolutionFor(m, resolution)
        val secs = VideoPick.durationFor(m, duration)
        val sub = Http.client(proxyOf(p)).post("${root(p)}/api/v1/services/aigc/video-generation/video-synthesis") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            header("X-DashScope-Async", "enable")
            contentType(ContentType.Application.Json)
            setBody(body(m, prompt, res, secs, VideoPick.ratioFor(m, ratio), audio).toString())
        }.jsonOrThrow()
        val taskId = (sub["output"] as? JsonObject)?.str("task_id") ?: throw ApiException(200, "没有返回任务编号：" + sub.toString().take(300))
        onTask(taskId)
        onStatus("排队中")
        return await(p, taskId, res, secs, timeoutMin, onStatus)
    }

    /** 等任务做完并下载。停止时取消不掉（已经在生成）的任务也用它在后台接着等。 */
    suspend fun await(p: ProviderConfig, taskId: String, resolution: String, duration: Int, timeoutMin: Int = 15, onStatus: (String) -> Unit = {}): Result {
        val client = Http.client(proxyOf(p))
        val deadline = System.currentTimeMillis() + timeoutMin * 60_000L
        var done: JsonObject? = null
        while (done == null) {
            if (System.currentTimeMillis() > deadline) throw ApiException(408, "做视频超时（$timeoutMin 分钟还没做完，任务编号 $taskId）")
            delay(pollMs)
            val t = query(client, p, taskId)
            val out = t["output"] as? JsonObject
            when (out?.str("task_status")) {
                "SUCCEEDED" -> done = t
                "FAILED", "CANCELED", "UNKNOWN" -> throw ApiException(200, "生成失败：" + listOfNotNull(out.str("task_status"), out.str("code"), out.str("message")).joinToString(" "))
                "RUNNING" -> onStatus("生成中")
                else -> onStatus("排队中")
            }
        }
        val out = done["output"] as JsonObject
        val usage = done["usage"] as? JsonObject
        val seconds = usage?.num("output_video_duration") ?: usage?.num("duration") ?: duration.toDouble()
        val url = out.str("video_url") ?: throw DownloadFailed(seconds, resolution, "做好了但返回里没有视频地址：" + out.toString().take(300))
        onStatus("下载中")
        val bytes = try {
            val r = client.get(url)
            if (r.status.value !in 200..299) throw DownloadFailed(seconds, resolution, "视频下载失败（${r.status.value}）")
            r.bodyAsBytes()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DownloadFailed) {
            throw e
        } catch (e: Throwable) {
            throw DownloadFailed(seconds, resolution, "视频下载失败：${e.message ?: e::class.simpleName}")
        }
        return Result(bytes, seconds, resolution, duration)
    }

    /**
     * 取消任务：POST {域名}/api/v1/tasks/{task_id}/cancel。官方原文「只能取消 PENDING 状态的任务。任务进入 RUNNING 状态后将继续执行直至完成」。
     * 取消完再查一次，返回任务现在的状态（CANCELED = 取消掉了，不收费）；查不到返回 null。
     */
    suspend fun cancel(p: ProviderConfig, taskId: String): String? {
        val client = Http.client(proxyOf(p))
        runCatching { client.post("${root(p)}/api/v1/tasks/$taskId/cancel") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.bodyAsText() }
        return runCatching { (query(client, p, taskId)["output"] as? JsonObject)?.str("task_status") }.getOrNull()
    }

    private suspend fun query(client: HttpClient, p: ProviderConfig, taskId: String) =
        client.get("${root(p)}/api/v1/tasks/$taskId") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.jsonOrThrow()

    private fun root(p: ProviderConfig): String {
        val u = java.net.URI(p.baseUrl.trim())
        return u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    }

    private suspend fun HttpResponse.jsonOrThrow(): JsonObject {
        val text = bodyAsText()
        if (status.value !in 200..299) throw ApiException(status.value, text)
        return runCatching { Json.parse(text).jsonObject }.getOrNull() ?: throw ApiException(status.value, "返回不是 JSON：" + text.take(200))
    }

    private fun JsonObject.num(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
}
