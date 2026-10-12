package com.guixing.jixunying.engine

import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SpeechModels
import com.guixing.jixunying.model.SpeechUnits
import com.guixing.jixunying.model.Thinking
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
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

/** 声音合成好了（服务商已经扣钱），只是音频下载失败：照样记账。 */
class SpeechDownloadFailed(status: Int, body: String) : ApiException(status, body)

/**
 * 语音（docs/向量和语音设计说明.md 第 2 节，2026-10-12）。两件事：
 * - 读出来（synthesize）：文字 → wav（16 位 PCM）。长文字先清理 Markdown、按模型上限切段，一段一次请求。
 * - 听写（transcribe）：wav → 文字。智谱一段最多 30 秒，超过的按 25 秒切开分开识别再按顺序拼。
 *
 * 各家协议按平台分（Thinking.platformOf）：
 * - 小米 mimo：读出来、听写都走 {接口地址}/chat/completions（读出来 audio.format=wav、音频在 choices[0].message.audio.data；
 *   听写把音频当 data URL 放进 user 消息的 input_audio）
 * - MiniMax：读出来 POST {去掉结尾 /v1}/v1/t2a_v2（data.audio 是十六进制），听写 POST {同上}/v1/speech_to_text（multipart）
 * - 智谱：读出来 POST {接口地址}/audio/speech（二进制），听写 POST {接口地址}/audio/transcriptions（multipart）
 * - 千问：读出来 POST https://{接口地址的域名}/api/v1/services/aigc/multimodal-generation/generation，返回地址再下载；听写第一期不接
 * 其他平台明确报「这家没有接」，不去硬试 OpenAI 的 /audio 接口。各家字段和限制的出处见 docs/参考资料.md「向量和语音」。
 */
class Speech(private val proxyOf: (ProviderConfig) -> String?) {

    /** 服务商接下了这次请求（回了 2xx）就记一条账：读出来按字符、听写按秒（教训 53：失败但已经扣钱的也要记）。 */
    @Volatile var onBilled: ((ProviderConfig, String, Double, String) -> Unit)? = null

    /** 读出来的一段：文字是清理切段以后的正文，bytes 是 wav。 */
    class Spoken(val text: String, val bytes: ByteArray)

    /**
     * 读出来。返回一段或多段（长文字按模型上限切开，界面一段一段放、点停止就不再往下合成）。
     * 这里不拼成一个文件：各家采样率不一定一样（智谱建议 24000、别家不一定），拼起来要么重采样要么声音变形；
     * 而且一段一段合成时用户点了「停止」就不再往下花钱。
     */
    suspend fun synthesize(provider: ProviderConfig, model: String, voice: String, speed: Double, text: String, prepared: Boolean = false): List<Spoken> {
        if (!SpeechModels.supportsTts(provider)) throw IllegalStateException(NO_TTS)
        val platform = Thinking.platformOf(provider)
        val chunks = plan(provider, text, prepared)
        if (chunks.isEmpty()) throw IllegalStateException("这段文字里没有能读的内容（都是代码块或 Markdown 记号）")
        val v = voice.ifBlank { SpeechModels.defaultVoice(model) }
        val client = Http.client(proxyOf(provider))
        return chunks.map { Spoken(it, one(client, provider, platform, model, v, speed, it)) }
    }

    /**
     * 读之前切段（不联网）：清理 Markdown、跳过代码块，按这家的上限切开。
     * prepared = true 是已经切好的一段（SpeakPlan 给的），不再清理，只防一手超长。
     */
    fun plan(provider: ProviderConfig, text: String, prepared: Boolean = false): List<String> =
        SpeechText.split(if (prepared) text.trim() else SpeechText.clean(text), SpeechText.limitOf(Thinking.platformOf(provider)))

    /** 听写（wav，16 位 PCM）。智谱超过 25 秒的切成几段分开识别，再按顺序拼起来。 */
    suspend fun transcribe(provider: ProviderConfig, model: String, wav: ByteArray): String {
        val platform = Thinking.platformOf(provider)
        val client = Http.client(proxyOf(provider))
        return when (platform) {
            "mimo" -> mimoAsr(client, provider, model, wav)
            "minimax" -> {
                val sec = SpeechText.secondsOf(wav)
                if (sec != null && sec > 500) throw IllegalStateException("这段录音 ${sec.toInt()} 秒，MiniMax 一段最长 500 秒，请分两次听")
                miniMaxAsr(client, provider, model, wav, sec ?: 0.0)
            }
            "zhipu" -> {
                // 官方一段最长 30 秒：切成 ≤25 秒的几段分开识别，按顺序拼起来（每段各算各的账）
                val parts = SpeechText.splitWav(wav, MAX_ZHIPU_SEC)
                val sb = StringBuilder()
                for (part in parts) sb.append(zhipuAsr(client, provider, model, part.bytes, part.seconds))
                sb.toString()
            }
            "qwen" -> throw IllegalStateException("千问还没有接听写：官方写着「HTTP（OpenAI 兼容）」，但 API 参考页打不开，第一期不接")
            else -> throw IllegalStateException(NO_ASR)
        }
    }

    /** 一段文字（已经切好）读成 wav。 */
    private suspend fun one(
        client: HttpClient, p: ProviderConfig, platform: String, model: String, voice: String, speed: Double, text: String,
    ): ByteArray = when (platform) {
        "mimo" -> mimoTts(client, p, model, voice, text)
        "minimax" -> miniMaxTts(client, p, model, voice, speed, text)
        "zhipu" -> zhipuTts(client, p, model, voice, speed, text)
        "qwen" -> qwenTts(client, p, model, voice, text)
        else -> throw IllegalStateException(NO_TTS)
    }

    private fun bill(p: ProviderConfig, model: String, units: Double) {
        runCatching { onBilled?.invoke(p, model, units, SpeechUnits.SECOND) }
    }

    private fun billChars(p: ProviderConfig, model: String, text: String) {
        runCatching { onBilled?.invoke(p, model, SpeechUnits.ttsChars(Thinking.platformOf(p), text), SpeechUnits.CHAR) }
    }

    // ———————————————— 地址 ————————————————

    private fun base(p: ProviderConfig) = p.baseUrl.trimEnd('/')

    /** MiniMax 的语音接口在 /v1 底下：地址是「接口地址去掉结尾的 /v1」再加 /v1/...。 */
    private fun miniMaxRoot(p: ProviderConfig) = base(p).removeSuffix("/v1")

    /** 千问的语音接口不在兼容层地址底下，用域名拼（和画图一样）。 */
    private fun root(p: ProviderConfig): String {
        val u = java.net.URI(p.baseUrl.trim())
        return u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    }

    private suspend fun HttpResponse.jsonOrThrow(what: String): JsonObject {
        val text = bodyAsText()
        if (status.value !in 200..299) throw ApiException(status.value, text)
        return runCatching { Json.parse(text).jsonObject }.getOrNull() ?: throw ApiException(status.value, "$what 返回的不是 JSON：" + text.take(300))
    }

    /** MiniMax 在 HTTP 200 里用 base_resp.status_code 报错：不是 0 就把 status_msg 报出来。 */
    private fun checkBaseResp(o: JsonObject, who: String) {
        val br = o["base_resp"] as? JsonObject ?: return
        val code = br["status_code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) throw ApiException(if (code == 1004) 401 else 400, "$who：${br.str("status_msg").orEmpty()}（$code）")
    }

    /** 十六进制字符串 → 字节（MiniMax 的音频是这么给的）；不是有效的十六进制返回 null。 */
    private fun hexToBytes(s: String): ByteArray? {
        val t = s.trim()
        if (t.isEmpty() || t.length % 2 != 0) return null
        val out = ByteArray(t.length / 2)
        for (i in out.indices) {
            val h = Character.digit(t[i * 2], 16)
            val l = Character.digit(t[i * 2 + 1], 16)
            if (h < 0 || l < 0) return null
            out[i] = ((h shl 4) or l).toByte()
        }
        return out
    }

    // ———————————————— 读出来 ————————————————

    /** 小米：走 chat/completions，assistant 消息放要读的字，audio.format = wav。 */
    private suspend fun mimoTts(client: HttpClient, p: ProviderConfig, model: String, voice: String, text: String): ByteArray {
        val o = client.post(base(p) + "/chat/completions") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    // user 消息放说话风格；留空按官方文档「可以空着」
                    add(buildJsonObject { put("role", "user"); put("content", "") })
                    add(buildJsonObject { put("role", "assistant"); put("content", text) })
                }
                putJsonObject("audio") {
                    put("voice", voice.ifBlank { "mimo_default" })
                    put("format", "wav")
                }
                // 不传 speed：小米官方文档里没有这个字段（主会话审查 10-12 去掉；文档没写的参数不传，免得被拒）
            }.toString())
        }.jsonOrThrow("小米读出来")
        // 收下了就记一条（后面解析失败也已经扣钱，教训 53）
        billChars(p, model, text)
        val audio = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["message"] as? JsonObject }?.let { it["audio"] as? JsonObject }
        val data = audio?.str("data")
            ?: throw ApiException(200, "小米读出来没返回音频（choices[0].message.audio.data 取不到）：" + o.toString().take(300))
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
        if (bytes == null) throw ApiException(200, "小米返回的音频不是 base64：" + data.take(120))
        if (bytes.isEmpty()) throw ApiException(200, "小米返回的音频是空的（base64 解出来 0 字节）")
        return bytes
    }

    /** MiniMax：POST {去掉结尾 /v1}/v1/t2a_v2，返回 data.audio 是十六进制。 */
    private suspend fun miniMaxTts(client: HttpClient, p: ProviderConfig, model: String, voice: String, speed: Double, text: String): ByteArray {
        val o = client.post(miniMaxRoot(p) + "/v1/t2a_v2") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("text", text)
                putJsonObject("voice_setting") {
                    put("voice_id", voice.ifBlank { "male-qn-qingse" })
                    put("speed", speed)
                }
                putJsonObject("audio_setting") { put("format", "wav") }
            }.toString())
        }.jsonOrThrow("MiniMax 读出来")
        checkBaseResp(o, "MiniMax")
        // 它认下了这单就记一条（后面解析失败也已经扣钱，教训 53）
        billChars(p, model, text)
        val hex = (o["data"] as? JsonObject)?.str("audio")
            ?: throw ApiException(200, "MiniMax 读出来没返回音频（data.audio 取不到）：" + o.toString().take(300))
        val bytes = hexToBytes(hex) ?: throw ApiException(200, "MiniMax 返回的音频不是十六进制：" + hex.take(120))
        if (bytes.isEmpty()) throw ApiException(200, "MiniMax 返回的音频是空的（十六进制解出来 0 字节）")
        return bytes
    }

    /** 智谱：POST {接口地址}/audio/speech，response_format 显式给 wav（不给默认是 pcm），返回二进制。 */
    private suspend fun zhipuTts(client: HttpClient, p: ProviderConfig, model: String, voice: String, speed: Double, text: String): ByteArray {
        val resp = client.post(base(p) + "/audio/speech") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("input", text)
                put("voice", voice.ifBlank { "tongtong" })
                put("response_format", "wav")
                put("speed", speed)
            }.toString())
        }
        val bytes = resp.bodyAsBytes()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, String(bytes, Charsets.UTF_8))
        if (bytes.isEmpty()) throw ApiException(200, "智谱读出来返回了空内容")
        // 出错时它回的是 JSON（不是音频），别把一段报错文字当 wav 存下来
        if (bytes[0] == '{'.code.toByte()) throw ApiException(200, "智谱读出来失败了：" + String(bytes, Charsets.UTF_8).take(300))
        billChars(p, model, text)
        return bytes
    }

    /** 千问：不在兼容层底下，返回 output.audio.url（24 小时有效），再下载那个 wav。 */
    private suspend fun qwenTts(client: HttpClient, p: ProviderConfig, model: String, voice: String, text: String): ByteArray {
        val o = client.post(root(p) + "/api/v1/services/aigc/multimodal-generation/generation") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                putJsonObject("input") {
                    put("text", text)
                    put("voice", voice.ifBlank { "Cherry" })
                }
            }.toString())
        }.jsonOrThrow("千问读出来")
        o.str("code")?.let { code ->
            // 出错时 HTTP 200 里带 code / message
            if (code.isNotBlank() && code != "0") throw ApiException(400, "千问读出来失败了：" + o.str("message").orEmpty() + "（$code）")
        }
        // 它把音频合成好了（已经扣钱），后面取地址、下载失败也要记一条
        billChars(p, model, text)
        val url = ((o["output"] as? JsonObject)?.get("audio") as? JsonObject)?.str("url")
            ?: throw ApiException(200, "千问读出来没返回音频地址（output.audio.url 取不到）：" + o.toString().take(300))
        val got = try {
            client.get(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SpeechDownloadFailed(502, "音频下载失败：${e.message ?: e::class.simpleName}")
        }
        if (got.status.value !in 200..299) throw SpeechDownloadFailed(got.status.value, "音频下载失败")
        val bytes = got.bodyAsBytes()
        if (bytes.isEmpty()) throw SpeechDownloadFailed(200, "音频下载下来是空的")
        return bytes
    }

    // ———————————————— 听写 ————————————————

    /** 小米：音频 base64 当 data URL 放进 user 消息的 input_audio，字在 choices[0].message.content。 */
    private suspend fun mimoAsr(client: HttpClient, p: ProviderConfig, model: String, wav: ByteArray): String {
        val dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)
        val o = client.post(base(p) + "/chat/completions") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "input_audio")
                                putJsonObject("input_audio") { put("data", dataUrl) }
                            })
                        }
                    })
                }
            }.toString())
        }.jsonOrThrow("小米听写")
        bill(p, model, SpeechText.secondsOf(wav) ?: 0.0) // 收下了就记一条（后面解析失败也已经扣钱）
        val text = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["message"] as? JsonObject }?.str("content")
        if (text.isNullOrBlank()) throw ApiException(200, "小米听写没返回文字（choices[0].message.content 是空的）：" + o.toString().take(300))
        return text
    }

    /** MiniMax：multipart/form-data，file + model。 */
    private suspend fun miniMaxAsr(client: HttpClient, p: ProviderConfig, model: String, wav: ByteArray, seconds: Double): String {
        val resp = client.post(miniMaxRoot(p) + "/v1/speech_to_text") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            setBody(audioForm(model, wav))
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        val o = runCatching { Json.parse(text).jsonObject }.getOrNull()
            ?: throw ApiException(200, "MiniMax 听写返回的不是 JSON：" + text.take(300))
        checkBaseResp(o, "MiniMax")
        bill(p, model, seconds) // 收下了就记一条
        // 官方文档没写返回里文字叫什么（docs/参考资料.md 里记了「没写」），先按 OpenAI 的 text 取；取不到把整段返回报出来
        val got = o.str("text") ?: (o["data"] as? JsonObject)?.str("text")
        if (got.isNullOrBlank()) throw ApiException(200, "MiniMax 听写返回里没找到文字（现在按 text 取，整个返回是：" + text.take(300) + "）")
        return got
    }

    /** 智谱：multipart（file + model），返回 JSON 的 text。 */
    private suspend fun zhipuAsr(client: HttpClient, p: ProviderConfig, model: String, wav: ByteArray, seconds: Double): String {
        val resp = client.post(base(p) + "/audio/transcriptions") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            setBody(audioForm(model, wav))
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        val o = runCatching { Json.parse(text).jsonObject }.getOrNull()
            ?: throw ApiException(200, "智谱听写返回的不是 JSON：" + text.take(300))
        // 它出错时也回 HTTP 200，内容是 {"error":{...}}（10-12 真 Key 实测）：这种没识别，不记账
        (o["error"] as? JsonObject)?.let { err ->
            throw ApiException(400, "智谱听写失败了：" + err.str("message").orEmpty().take(300) + (err.str("code")?.let { "（$it）" } ?: ""))
        }
        bill(p, model, seconds) // 收下了就记一条（按这一段的秒数）
        return o.str("text")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(200, "智谱听写没返回文字：" + text.take(300))
    }

    /**
     * 只给 filename：Ktor 的 formData 自己会先加「form-data; name="file"」，再把这里的值用「; 」接在后面
     * （ktor 源码 formDsl.kt、FormDataContent.kt）。以前这里又写了一遍 form-data，拼出两个 form-data，
     * MiniMax 报「missing required form field: file」、智谱报「Invalid content disposition format」（10-12 真 Key 实测）。
     */
    private fun audioForm(model: String, wav: ByteArray, name: String = "audio.wav") = MultiPartFormDataContent(formData {
        append("model", model)
        append("file", wav, Headers.build {
            append(HttpHeaders.ContentType, "audio/wav")
            append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
        })
    })

    companion object {
        /** 智谱一段最长 30 秒，切成 25 秒一段（官方限制，见 docs/参考资料.md）。 */
        const val MAX_ZHIPU_SEC = 25.0

        const val NO_TTS = "这家还没有接读出来（现在支持：小米 mimo、MiniMax、智谱、千问）"
        const val NO_ASR = "这家还没有接听写（现在支持：小米 mimo、MiniMax、智谱）"
    }
}

/**
 * 读出来之前的文字处理：去掉 Markdown 记号、跳过代码块，再按句号之类切段。
 * 不靠提示词让模型别写 Markdown（CLAUDE.md 红线），读之前由程序清理。
 */
object SpeechText {

    /** 一段最多多少字（各家官方上限，出处 docs/参考资料.md「读出来」）。 */
    fun limitOf(platform: String): Int = when (platform) {
        "zhipu" -> 1024 // input maxLength 1024
        "qwen" -> 600 // 一次 512 token / 600 字以内
        "minimax" -> 3000 // 官方：大于 3000 字符推荐用流式；我们不是流式，先按 3000 切
        else -> 1000 // 小米官方没写上限，先按 1000
    }

    /** 去掉 Markdown 记号；代码块整段跳过，只读一句「这里有一段代码」。 */
    fun clean(text: String): String {
        var t = text
        // 思考过程不读（有的模型把 <think> 写在正文里）
        t = t.replace(Regex("(?s)<think>.*?</think>"), " ")
        t = t.replace(Regex("(?s)<think>.*"), " ")
        // 代码块整段跳过
        t = t.replace(Regex("(?s)```.*?```"), " 这里有一段代码。 ")
        t = t.replace(Regex("(?m)^\\s*```.*$"), " 这里有一段代码。 ")
        // 图片和链接只留文字
        t = t.replace(Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1")
        t = t.replace(Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1")
        // 加粗、斜体、删除线只留文字（成对出现才去掉，免得动了 2*3 里的星号）
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        t = t.replace(Regex("__(.+?)__"), "$1")
        t = t.replace(Regex("~~(.+?)~~"), "$1")
        t = t.replace(Regex("(?s)\\*([^*\\n]+)\\*"), "$1")
        // 行内代码去掉反引号，只读里面的字
        t = t.replace("`", "")
        // 标题、引用、列表符号、分隔线
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        t = t.replace(Regex("(?m)^\\s{0,3}[-*+]\\s+"), "")
        // 有序列表「1. 」「2) 」「3、」：点和括号后面要有空格才算，免得把「3.5 元」开头的 3. 削掉
        t = t.replace(Regex("(?m)^\\s{0,3}\\d+[.)]\\s+"), "")
        t = t.replace(Regex("(?m)^\\s{0,3}\\d+、\\s*"), "")
        t = t.replace(Regex("(?m)^[\\s|:_*\\-]+$"), "")
        // 空行压一压，别读一长串停顿
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }

    /** 按句号、问号、叹号、分号、换行切句（标点跟着上一句）。 */
    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) out += s
            sb.clear()
        }
        for (c in text) {
            sb.append(c)
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';') flush()
        }
        flush()
        return out
    }

    /** 切成每段不超过 limit 字的几段（单句就超长的硬切）。 */
    fun split(text: String, limit: Int): List<String> {
        val max = limit.coerceAtLeast(50)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) out += cur.toString().trim()
            cur.clear()
        }
        for (sentence in sentences(text)) {
            var rest = sentence
            while (rest.isNotEmpty()) {
                val room = max - cur.length
                if (rest.length <= room) {
                    cur.append(rest); break
                }
                if (room <= 0) {
                    flush(); continue
                }
                val cut = safeCut(rest, room)
                cur.append(rest.take(cut))
                flush()
                rest = rest.drop(cut).trimStart()
            }
        }
        flush()
        return out
    }

    /** 别从代理对中间切开（不然会切出乱码）。 */
    private fun safeCut(s: String, at: Int): Int =
        if (at > 0 && at < s.length && s[at - 1].isHighSurrogate()) at - 1 else at

    // ———————————————— wav（16 位 PCM） ————————————————

    /** 一个 wav 的格式和数据（数据只有 data 块里的音频字节，不含头部）。 */
    class Wav(val sampleRate: Int, val channels: Int, val bits: Int, val data: ByteArray) {
        val bytesPerSecond: Int get() = sampleRate * channels * (bits / 8)
        val seconds: Double get() = if (bytesPerSecond <= 0) 0.0 else data.size.toDouble() / bytesPerSecond
    }

    class WavPart(val bytes: ByteArray, val seconds: Double)

    /** 认一段 wav 的头部（认不出返回 null）。 */
    fun parseWav(bytes: ByteArray): Wav? {
        if (bytes.size < 44) return null
        fun ascii(off: Int, len: Int) = String(bytes, off, len, Charsets.US_ASCII)
        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE") return null
        var i = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        while (i + 8 <= bytes.size) {
            val id = ascii(i, 4)
            val size = leInt(bytes, i + 4)
            val body = i + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                }
                "data" -> {
                    if (sampleRate <= 0 || channels <= 0 || bits <= 0) return null
                    val n = minOf(size, bytes.size - body).coerceAtLeast(0)
                    return Wav(sampleRate, channels, bits, bytes.copyOfRange(body, body + n))
                }
            }
            if (size <= 0) break
            i = body + size + (size and 1)
        }
        return null
    }

    /** 这段话有多少秒（不是能认出来的 wav 返回 null）。 */
    fun secondsOf(bytes: ByteArray): Double? = parseWav(bytes)?.seconds

    /** 按采样率、声道、位深拼一个 wav（16 位 PCM，44 字节的头部）。 */
    fun wavOf(w: Wav): ByteArray = wavOf(w.sampleRate, w.channels, w.bits, w.data)

    fun wavOf(sampleRate: Int, channels: Int, bits: Int, data: ByteArray): ByteArray {
        val blockAlign = channels * (bits / 8)
        val out = ByteArray(44 + data.size)
        fun ascii(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(out, off) }
        fun int(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
            out[off + 2] = ((v shr 16) and 0xFF).toByte()
            out[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun short(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF"); int(4, 36 + data.size); ascii(8, "WAVE")
        ascii(12, "fmt "); int(16, 16); short(20, 1); short(22, channels)
        int(24, sampleRate); int(28, sampleRate * blockAlign); short(32, blockAlign); short(34, bits)
        ascii(36, "data"); int(40, data.size)
        data.copyInto(out, 44)
        return out
    }

    /**
     * 把一段 wav 按 maxSeconds 切成几段（智谱一段最长 30 秒，超过的要分开识别）。
     * 切点按整帧对齐（不会把一帧劈成两半）。认不出格式的直接报错：切不了就别发，免得服务商报更难懂的错。
     */
    fun splitWav(bytes: ByteArray, maxSeconds: Double): List<WavPart> {
        val w = parseWav(bytes) ?: throw IllegalStateException("这段录音不是能认出来的 wav（16 位 PCM），发不了")
        val frame = w.channels * (w.bits / 8)
        if (w.bytesPerSecond <= 0 || frame <= 0) throw IllegalStateException("这段录音的格式认不出来（采样率 / 声道 / 位深不全），发不了")
        val per = ((w.bytesPerSecond * maxSeconds).toInt() / frame) * frame
        if (per < frame || w.data.size <= per) return listOf(WavPart(bytes, w.seconds))
        val out = mutableListOf<WavPart>()
        var off = 0
        while (off < w.data.size) {
            val end = minOf(off + per, w.data.size)
            val part = w.data.copyOfRange(off, end)
            out += WavPart(wavOf(w.sampleRate, w.channels, w.bits, part), part.size.toDouble() / w.bytesPerSecond)
            off = end
        }
        return out
    }

    private fun leInt(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
