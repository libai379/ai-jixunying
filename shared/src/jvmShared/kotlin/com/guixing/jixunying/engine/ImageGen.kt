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
import kotlinx.serialization.json.buildJsonArray
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

    /**
     * note：要告诉用户的一句（比如智谱没签免责声明、这张还带水印）；空 = 没有。
     * added：程序按模型补在提示词后面的那句（ImageTuning，2026-11-04）；空 = 没补。
     */
    class Result(val bytes: ByteArray, val mime: String, val revisedPrompt: String?, val note: String = "", val added: String = "")

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

        /** 智谱画的图还带水印时告诉用户的话（没在智谱后台签去水印的免责声明，带 watermark_enabled = false 会被拒）。 */
        const val ZHIPU_WATERMARK_NOTE = "智谱画的图还带着「AI生成」水印：要去掉，先到智谱开放平台 个人中心 → 安全管理 → 去水印管理 签一下免责声明，签完以后画的就不带了。"

        /** "1024x1024" → 宽高比 "1:1"（MiniMax、可灵要比例不要像素）。 */
        fun aspectOf(size: String): String {
            val (w, h) = parseSize(size) ?: return "1:1"
            val r = w.toDouble() / h
            val options = listOf("1:1" to 1.0, "16:9" to 16 / 9.0, "9:16" to 9 / 16.0, "4:3" to 4 / 3.0, "3:4" to 3 / 4.0, "3:2" to 1.5, "2:3" to 2 / 3.0, "21:9" to 21 / 9.0)
            return options.minBy { kotlin.math.abs(it.second - r) }.first
        }

        /** 可灵（经千问AI平台）只认 16:9、9:16、1:1（API 参考 kling-image-generation/create-task 的 aspect_ratio enum），挑最接近的。 */
        fun klingAspect(w: Int, h: Int): String {
            val r = w.toDouble() / h.coerceAtLeast(1)
            return listOf("1:1" to 1.0, "16:9" to 16 / 9.0, "9:16" to 9 / 16.0).minBy { kotlin.math.abs(kotlin.math.ln(it.second / r)) }.first
        }

        /**
         * Vidu（经千问AI平台）的尺寸只能从固定几种里挑（API 参考 vidu-image-generation/create-task「可用尺寸列表」1K 档），按宽高比挑最近的。
         * vidu-image / pro / lite 一套，viduq3-fast、viduq2-pro / fast 一套。
         */
        fun viduSize(model: String, w: Int, h: Int): String {
            val list = if ("viduq" in model.lowercase())
                listOf(1024 to 1024, 768 to 1376, 848 to 1264, 896 to 1200, 928 to 1152, 1152 to 928, 1200 to 896, 1264 to 848, 1376 to 768, 1584 to 672)
            else listOf(1024 to 1024, 720 to 1440, 1440 to 720, 1024 to 768, 768 to 1024, 1920 to 1088, 1088 to 1920, 1536 to 1024, 1024 to 1536, 1920 to 816, 816 to 1920)
            val r = w.toDouble() / h.coerceAtLeast(1)
            val (bw, bh) = list.minBy { (a, b) -> kotlin.math.abs(kotlin.math.ln(a.toDouble() / b / r)) }
            return "$bw*$bh"
        }

        /**
         * 千问AI平台各画图模型能收的尺寸（2026-11-04 故意传 1*1 探出来的报错原文）：
         * qwen-image「between 262144 (512x512) and 6553600 (2560x2560)」（表里写最大 2048×2048，按 2048 来）；z-image「262144 and 4194304」；
         * wan2.7「589824 and 16777216」（wan2.7-image 表里最大 2048×2048）；wan2.6-t2i、wan2.5-t2i「589824 and 2073600」；
         * wan2.2、wanx2.1「Either width or height should be between 512 and 1440」；wanx2.0、wanx-v1「divided by 8」。保持宽高比缩放，取 16 的倍数。
         * 千问图像、万相 2.7 只限总像素和宽高比（1:8～8:1），单边可以过 2048：官方 2K 推荐尺寸 3:4 是 1728*2368（文生图指南「各像素范围下的推荐分辨率」）。
         * qwen-image-3.0-pro 压在 1K 档（ImageTuning.QWEN_PRO_1K_MAX）：超过算 2K、贵一倍。
         */
        fun dashScopeSize(model: String, w: Int, h: Int): Pair<Int, Int> {
            val m = model.lowercase()
            fun sq(n: Int) = n.toLong() * n
            return when {
                m.startsWith("qwen-image-3.0-pro") -> fitSize(w, h, sq(512), com.guixing.jixunying.model.ImageTuning.QWEN_PRO_1K_MAX, 256, 4096)
                m.startsWith("qwen-image") -> fitSize(w, h, sq(512), sq(2048), 256, 4096)
                m.startsWith("z-image") -> fitSize(w, h, sq(512), sq(2048), 256, 2048)
                m.startsWith("wan2.7") -> fitSize(w, h, sq(768), sq(2048), 384, 4096)
                m.startsWith("wan2.6") || m.startsWith("wan2.5") -> fitSize(w, h, sq(768), sq(1440), 384, 2048)
                else -> fitSize(w, h, sq(512), sq(1440), 512, 1440)
            }
        }

        /** 保持宽高比，把尺寸缩放到总像素 [minPixels, maxPixels]、每边 [minSide, maxSide] 里，取 step 的倍数。 */
        fun fitSize(w0: Int, h0: Int, minPixels: Long, maxPixels: Long, minSide: Int, maxSide: Int, step: Int = 16): Pair<Int, Int> {
            var w = w0.coerceAtLeast(1).toDouble()
            var h = h0.coerceAtLeast(1).toDouble()
            fun scale(s: Double) { w *= s; h *= s }
            if (w * h > maxPixels) scale(kotlin.math.sqrt(maxPixels / (w * h)))
            if (w * h < minPixels) scale(kotlin.math.sqrt(minPixels / (w * h)))
            if (maxOf(w, h) > maxSide) scale(maxSide / maxOf(w, h))
            if (minOf(w, h) < minSide) scale(minSide / minOf(w, h))
            val lo = ((minSide + step - 1) / step) * step
            val hi = (maxSide / step) * step
            var fw = (Math.round(w / step) * step).toInt().coerceIn(lo, hi)
            var fh = (Math.round(h / step) * step).toInt().coerceIn(lo, hi)
            // 取整以后总像素出了范围：一格一格挪回来
            while (fw.toLong() * fh > maxPixels && (fw > lo || fh > lo)) { if (fw >= fh && fw > lo) fw -= step else fh -= step }
            while (fw.toLong() * fh < minPixels && (fw < hi || fh < hi)) { if (fw <= fh && fw < hi) fw += step else fh += step }
            return fw to fh
        }

        /** 保持宽高比，把尺寸放大或缩小到总像素约等于 target（再交给各家的范围去取整）。 */
        fun scaleToPixels(w: Int, h: Int, target: Long): Pair<Int, Int> {
            val s = kotlin.math.sqrt(target.toDouble() / (w.toLong() * h).coerceAtLeast(1))
            return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
        }

        fun parseSize(size: String): Pair<Int, Int>? {
            val parts = size.lowercase().split('x', '*', ':')
            val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
            return w to h
        }
    }

    /**
     * 画一张。plan 是按模型细调好的东西（ImageTuning：补审美的一句、反向提示词、改写开关、分辨率），不传就按模型算；
     * 对比测试时可以传自己的（固定 seed、改开关）。
     */
    suspend fun generate(provider: ProviderConfig, model: String, prompt: String, size: String,
                         plan: com.guixing.jixunying.model.ImageTuning.Plan = com.guixing.jixunying.model.ImageTuning.plan(model, prompt)): Result {
        val client = Http.client(proxyOf(provider))
        val r = when (protocolOf(provider)) {
            Protocol.OPENAI, Protocol.SILICONFLOW -> openAi(client, provider, model, plan.prompt, size)
            Protocol.DASHSCOPE -> dashScope(client, provider, model, plan, size)
            Protocol.MINIMAX -> miniMax(client, provider, model, plan, size)
            Protocol.KLING -> kling(client, provider, model, plan.prompt, size)
            Protocol.MODELSCOPE -> modelScope(client, provider, model, plan.prompt, size)
        }
        return if (plan.added.isEmpty()) r else Result(r.bytes, r.mime, r.revisedPrompt, r.note, plan.added)
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

    /** 服务商已经画好、给了地址（已经扣钱）才会走到这里；下载失败也要让上面知道这张算过钱。 */
    private suspend fun download(client: HttpClient, url: String): Pair<ByteArray, String> = try {
        val img = client.get(url)
        if (img.status.value !in 200..299) throw ImageDownloadFailed(img.status.value, "图片下载失败")
        img.bodyAsBytes() to (img.headers["Content-Type"]?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/png")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ImageDownloadFailed) {
        throw e
    } catch (e: Throwable) {
        throw ImageDownloadFailed(502, "图片下载失败：${e.message ?: e::class.simpleName}")
    }

    private fun sniffMime(b64: String) = when {
        b64.startsWith("/9j/") -> "image/jpeg"
        b64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
    }

    // —— OpenAI 风格 ——
    private suspend fun openAi(client: HttpClient, p: ProviderConfig, model: String, prompt: String, size: String): Result {
        val silicon = protocolOf(p) == Protocol.SILICONFLOW
        val zhipu = p.presetId == "zhipu" || p.baseUrl.contains("bigmodel.cn")
        // 智谱的尺寸（2026-11-04 故意传 1x1 探出来的报错原文）：glm-image「长宽均需满足512px-2880px之间,且为32整数倍,并保证最大像素数不超过2^22px」，
        // cogview「…且为16整数倍,并保证最大像素数不超过2^21px」。1080x1920 这种直接发会被拒，缩放取整
        val sendSize = if (!zhipu) size else parseSize(size)?.let { (w, h) ->
            val glm = model.lowercase().startsWith("glm-image")
            val (fw, fh) = fitSize(w, h, 512L * 512, if (glm) 1L shl 22 else 1L shl 21, 512, 2048, if (glm) 32 else 16)
            "${fw}x$fh"
        } ?: size
        fun body(noMark: Boolean) = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            if (silicon) put("image_size", size) else put("size", sendSize)
            if (!silicon) put("n", 1)
            if (p.baseUrl.contains("volces.com")) { put("response_format", "url"); put("watermark", false) }
            // 智谱（2026-10-28 用户：「生图都带 AI 生成，改」）：watermark_enabled = false 关掉显式和隐式水印。官方文档
            // （docs.bigmodel.cn 图像生成）：「仅允许已签署免责声明的客户使用，签署路径：个人中心-安全管理-去水印管理」
            if (zhipu && noMark) put("watermark_enabled", false)
        }
        suspend fun send(noMark: Boolean) = client.post(base(p) + "/images/generations") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(body(noMark).toString())
        }
        var note = ""
        var resp = send(noMark = true)
        // 没签免责声明被拒：照常带水印画，告诉用户去哪儿签
        if (zhipu && resp.status.value !in 200..299) {
            val text = resp.bodyAsText()
            if (!Regex("(?i)watermark|水印|免责").containsMatchIn(text)) throw ApiException(resp.status.value, text)
            resp = send(noMark = false)
            note = ZHIPU_WATERMARK_NOTE
        }
        val o = resp.jsonOrThrow()
        (o["error"] as? JsonObject)?.let { throw ApiException(400, it.toString()) }
        val first = ((o["data"] ?: o["images"]) as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ApiException(200, "返回里没有图片：" + o.toString().take(300))
        val revised = first.str("revised_prompt")
        first.str("b64_json")?.let { return Result(Base64.getDecoder().decode(it), sniffMime(it), revised, note) }
        val url = first.str("url") ?: throw ApiException(200, "返回里没有图片地址：" + o.toString().take(300))
        val (bytes, mime) = download(client, url)
        return Result(bytes, mime, revised, note)
    }

    /**
     * 改图（2026-11-03，P 图里「补画」那一步用）：把一张图和一句话交给能改图的模型，返回整张新图；框外由 PhotoEdit 贴回原图，
     * 所以模型动没动框外都不要紧。只接了千问AI平台 / 阿里百炼，出处 docs/参考资料.md「用户充值的千问AI平台能怎么改图」：
     * - qwen-image-2.x / 3.x：multimodal-generation，content 里 image + text；size「宽*高」，总像素 512×512～2048×2048
     * - wan2.7-image(-pro)：同一个接口；parameters.bbox_list 框出要改的地方（「原始图片的绝对像素坐标」，一张图最多 2 个框），
     *   size 总像素 768×768～2048×2048
     * - wanx2.1-imageedit：image2image/image-synthesis，只能异步；function = description_edit_with_mask，遮罩白色是要改的、
     *   「蒙版图像的尺寸应与原始图像一致」，原图宽高各 512～4096
     * image 统一传 JPEG（wan2.7 写明「PNG（不支持 Alpha 通道）」）。尺寸由调用的地方按上面的范围准备好。
     */
    suspend fun edit(provider: ProviderConfig, model: String, image: ByteArray, prompt: String, size: String, box: IntArray?, mask: ByteArray?): Result {
        if (protocolOf(provider) != Protocol.DASHSCOPE) throw IllegalStateException("${provider.name} 的改图还没接：现在只能用千问AI平台或阿里百炼的模型改图")
        val client = Http.client(proxyOf(provider))
        val m = model.lowercase()
        val img = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image)
        if (m.startsWith("wanx2.1-imageedit")) {
            val body = buildJsonObject {
                put("model", model)
                putJsonObject("input") {
                    put("function", if (mask != null) "description_edit_with_mask" else "description_edit")
                    put("prompt", prompt)
                    put("base_image_url", img)
                    if (mask != null) put("mask_image_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(mask))
                }
                putJsonObject("parameters") { put("n", 1) }
            }
            return dashScopeRun(client, provider, "/api/v1/services/aigc/image2image/image-synthesis", body, preferAsync = true)
        }
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("image", img) })
                            add(buildJsonObject { put("text", prompt) })
                        }
                    })
                }
            }
            putJsonObject("parameters") {
                put("n", 1); put("watermark", false); put("size", size)
                if (box != null && m.startsWith("wan2.7")) putJsonArray("bbox_list") {
                    add(buildJsonArray { add(buildJsonArray { box.forEach { add(it) } }) })
                }
            }
        }
        return dashScopeRun(client, provider, "/api/v1/services/aigc/multimodal-generation/generation", body, preferAsync = false)
    }

    // —— 阿里百炼 / 千问AI平台：千问图像、通义万相、Z-Image，平台上的可灵、Vidu ——
    /**
     * 哪个模型走哪个接口（2026-11-04 照千问AI平台 API 参考核对，又用用户的 Key 故意传错尺寸逐个探过，见 docs/参考资料.md「千问AI平台全部画图模型」）：
     * - 同步 multimodal-generation（input.messages）：qwen-image、z-image-turbo、wan2.7-image(-pro)、wan2.6-t2i。
     *   以前 wan2.6 / 2.7 发的是 image-generation 同步，平台回 403「does not support synchronous calls」，靠换异步再试才画成
     * - 异步 image-generation/generation（input.messages，要 X-DashScope-Async）：kling/…（aspect_ratio 只有 16:9、9:16、1:1，resolution 1k / 2k）、
     *   vidu/…（size 只能从固定的几种里挑，n 只能 1）
     * - 异步 text2image/image-synthesis（input.prompt）：wan2.5-t2i-preview、wan2.2-t2i-*、wanx2.1-t2i-*、wanx2.0-t2i-turbo、wanx-v1
     * 按模型细调的参数（2026-11-04，ImageTuning）：分辨率按「同样的钱画到最大」放大；反向提示词新接口放 parameters、老万相放 input
     * （API 参考 wan-text-to-image-v2 原文：input.negative_prompt）；prompt_extend 照 ImageTuning.rewrite（z-image 关、万相 2.7 不发改发 thinking_mode）。
     */
    private suspend fun dashScope(client: HttpClient, p: ProviderConfig, model: String, plan: com.guixing.jixunying.model.ImageTuning.Plan, size: String): Result {
        val m = model.lowercase()
        val kling = m.startsWith("kling/")
        val vidu = m.startsWith("vidu/")
        val sync = m.startsWith("qwen-image") || m.startsWith("z-image") || m.startsWith("wan2.7") || m.startsWith("wan2.6")
        val path = when {
            sync -> "/api/v1/services/aigc/multimodal-generation/generation"
            kling || vidu -> "/api/v1/services/aigc/image-generation/generation"
            else -> "/api/v1/services/aigc/text2image/image-synthesis"
        }
        val (w0, h0) = parseSize(size) ?: (1024 to 1024)
        val (w, h) = plan.pixels?.let { scaleToPixels(w0, h0, it) } ?: (w0 to h0)
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                if (sync || kling || vidu) putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") { add(buildJsonObject { put("text", plan.prompt) }) }
                    })
                } else {
                    put("prompt", plan.prompt)
                    plan.negative?.let { put("negative_prompt", it) }
                }
            }
            putJsonObject("parameters") {
                put("n", 1); put("watermark", false)
                when {
                    kling -> { put("aspect_ratio", klingAspect(w, h)); put("resolution", if (maxOf(w, h) >= 1536) "2k" else "1k") }
                    vidu -> put("size", viduSize(m, w, h))
                    else -> {
                        val (fw, fh) = dashScopeSize(m, w, h)
                        put("size", "$fw*$fh")
                        if (sync) plan.negative?.let { put("negative_prompt", it) }
                        if (m.startsWith("wan2.7")) put("thinking_mode", true) else plan.rewrite?.let { put("prompt_extend", it) }
                        plan.seed?.let { put("seed", it) }
                    }
                }
            }
        }
        return dashScopeRun(client, p, path, body, preferAsync = !sync)
    }

    /** 千问AI平台 / 百炼：提交（同步或异步，被拒而且说的是同步 / 异步的问题就换另一种再试一次）、等任务做完、取图。 */
    private suspend fun dashScopeRun(client: HttpClient, p: ProviderConfig, path: String, body: JsonObject, preferAsync: Boolean): Result {
        suspend fun submit(async: Boolean) = client.post(root(p) + path) {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            if (async) header("X-DashScope-Async", "enable")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        // 先按文档的方式来，被拒而且报错说的是同步 / 异步的问题，就换另一种再试一次。
        // 千问AI平台对不支持异步的接口回 403「current user api does not support asynchronous calls」（2026-10-08 实测），不只是 400。
        var resp = submit(async = preferAsync)
        if (resp.status.value == 400 || resp.status.value == 403) {
            val text = resp.bodyAsText()
            val aboutMode = text.contains("async", ignoreCase = true) || text.contains("synchronous", ignoreCase = true) || text.contains("异步") || text.contains("同步")
            resp = if (aboutMode) submit(async = !preferAsync) else throw ApiException(resp.status.value, text)
        }
        var o = resp.jsonOrThrow()
        val taskId = (o["output"] as? JsonObject)?.str("task_id")
        if (taskId != null) {
            o = poll(180) {
                val t = client.get(root(p) + "/api/v1/tasks/$taskId") { header("Authorization", "Bearer ${p.apiKey.trim()}") }.jsonOrThrow()
                val out = t["output"] as? JsonObject
                when (out?.str("task_status")) {
                    "SUCCEEDED" -> t
                    "FAILED", "CANCELED", "UNKNOWN" -> {
                        val why = (out.str("message") ?: out.str("code")).orEmpty()
                        // 平台上的第三方（可灵、Vidu）账号没开通时任务照样建、然后失败（2026-11-04 用户的 Key 实测）
                        throw ApiException(200, if ("not activated" in why.lowercase())
                            "这个模型在千问AI平台上还没开通，要先到千问AI平台控制台开通（平台原话：$why）" else "生成失败：$why")
                    }
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
    private suspend fun miniMax(client: HttpClient, p: ProviderConfig, model: String, plan: com.guixing.jixunying.model.ImageTuning.Plan, size: String): Result {
        val o = client.post(base(p) + "/image_generation") {
            header("Authorization", "Bearer ${p.apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                // image-01-live 不收 21:9（官方文档「21:9 (1344x576) (仅适用于 image-01)」）
                val ar = aspectOf(size).let { if (it == "21:9" && model.lowercase() == "image-01-live") "16:9" else it }
                put("model", model); put("prompt", plan.prompt); put("aspect_ratio", ar)
                // prompt_optimizer：官方默认 false；以前一直开着，长描述被它改（ImageTuning.rewrite）
                put("response_format", "base64"); put("n", 1); put("prompt_optimizer", plan.rewrite ?: false)
                plan.seed?.let { put("seed", it) }
                // 官方文档写默认就是 false（platform.minimax.cn image-generation-t2i），写明白免得哪天改了默认
                put("aigc_watermark", false)
            }.toString())
        }.jsonOrThrow()
        val br = o["base_resp"] as? JsonObject
        val code = br?.get("status_code")?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) throw ApiException(400, "MiniMax：" + br?.str("status_msg").orEmpty() + "（$code）")
        // 被它的内容安全检查拦下的张数（API 参考原文 failed_count「因内容安全检查失败而未返回的图片数量」；示例里是字符串 "0"）
        val blocked = (o["metadata"] as? JsonObject)?.str("failed_count")?.toIntOrNull() ?: 0
        if (blocked > 0) throw ApiException(200, "MiniMax：这张被它的内容安全检查拦下了，没出图。换个说法或换个模型再画")
        val data = o["data"] as? JsonObject ?: throw ApiException(200, "返回里没有图片")
        val (bytes, mime) = (data["image_base64"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) to sniffMime(it) }
            ?: download(client, (data["image_urls"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content ?: throw ApiException(200, "返回里没有图片"))
        if (looksBlank(bytes)) throw ApiException(200, "MiniMax 回来的是一张灰底空图（多半是被它的内容安全检查换掉了），没画成。换个说法或换个模型再画")
        return Result(bytes, mime, null)
    }

    /**
     * 几乎一整张中灰（2026-11-04 实测：MiniMax 有一次回了张灰底带零星彩点的图当结果，状态码照样是成功；
     * 抽样 99.6% 的点和中间色每个通道差不到 12，中间色约 (128,126,129)。正常的暗调人像同样的算法是 84%）。
     * 只认中灰：黑底、白底的正常图（星空、白底图标）不误伤。
     */
    internal fun looksBlank(bytes: ByteArray): Boolean {
        val px = runCatching { decodePixels(bytes) }.getOrNull() ?: return false
        val step = maxOf(1, px.argb.size / 40_000)
        val sample = (px.argb.indices step step).map { px.argb[it] }
        if (sample.isEmpty()) return false
        fun ch(c: Int, s: Int) = (c shr s) and 255
        val med = sample.sortedBy { ch(it, 16) + ch(it, 8) + ch(it, 0) }[sample.size / 2]
        if (listOf(16, 8, 0).any { ch(med, it) !in 80..176 }) return false
        val near = sample.count { c -> listOf(16, 8, 0).all { s -> kotlin.math.abs(ch(c, s) - ch(med, s)) <= 12 } }
        return near >= sample.size * 0.98
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
