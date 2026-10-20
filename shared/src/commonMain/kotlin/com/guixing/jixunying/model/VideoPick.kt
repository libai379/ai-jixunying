package com.guixing.jixunying.model

/**
 * 一个文生视频模型能接受什么。出处：千问AI平台文档「视频生成模型」和各模型的 API 参考（2026-10-20 下载原文核对，
 * 记在 docs/参考资料.md「视频生成」）。
 * @param bySize true = 老接口用 size（像素「宽*高」）；false = 用 resolution（档位）+ ratio
 * @param sizes bySize 时每个档位对应的像素
 */
data class VideoModel(
    val id: String,
    val label: String,
    val resolutions: List<String>,
    val minSec: Int,
    val maxSec: Int,
    val bySize: Boolean = false,
    val sizes: Map<String, String> = emptyMap(),
    /** 能用 audio 开关（只有 wan3.0）。 */
    val audioSwitch: Boolean = false,
    val ratios: List<String> = listOf("16:9", "9:16", "1:1", "4:3", "3:4"),
    /** 不传 watermark 时默认加水印（happyhorse），要显式传 false。 */
    val watermarkDefault: Boolean = false,
)

/** 一个能用的做视频的服务商。 */
data class VideoChoice(val providerId: String, val providerName: String, val model: VideoModel)

/** 做视频用哪家、哪个模型、什么档位。 */
object VideoPick {
    const val DEFAULT_MODEL = "wan3.0-video"

    val models: List<VideoModel> = listOf(
        VideoModel("wan3.0-video", "万相 3.0（推荐，最便宜的 480P 也能用，可开关声音）", listOf("480P", "720P", "1080P"), 2, 30, audioSwitch = true,
            ratios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")),
        VideoModel("wan3.0-video-prime", "万相 3.0 高速版（更快，更贵）", listOf("480P", "720P", "1080P"), 2, 30, audioSwitch = true,
            ratios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")),
        VideoModel("happyhorse-1.1-t2v", "HappyHorse 1.1（有声）", listOf("480P", "720P", "1080P"), 3, 15, watermarkDefault = true,
            ratios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "4:5", "5:4", "9:21", "21:9")),
        VideoModel("wan2.7-t2v", "万相 2.7（有声，多镜头）", listOf("720P", "1080P"), 2, 15),
        VideoModel("wan2.6-t2v", "万相 2.6（有声）", listOf("720P", "1080P"), 2, 15, bySize = true,
            sizes = mapOf("720P" to "1280*720", "1080P" to "1920*1080")),
        VideoModel("wan2.2-t2v-plus", "万相 2.2（无声，固定 5 秒）", listOf("480P", "1080P"), 5, 5, bySize = true,
            sizes = mapOf("480P" to "832*480", "1080P" to "1920*1080")),
        VideoModel("wanx2.1-t2v-turbo", "万相 2.1 极速（无声，固定 5 秒）", listOf("480P", "720P"), 5, 5, bySize = true,
            sizes = mapOf("480P" to "832*480", "720P" to "1280*720")),
    )

    fun model(id: String): VideoModel = models.firstOrNull { it.id == id.trim() }
        ?: VideoModel(id.trim(), id.trim(), listOf("480P", "720P", "1080P"), 2, 15)

    /** 千问AI平台、阿里百炼（同一套 DashScope 视频接口）。 */
    fun supports(p: ProviderConfig): Boolean {
        val u = p.baseUrl.lowercase()
        return p.presetId == "qianwen" || p.presetId.startsWith("dashscope") ||
            "dashscope" in u || "qianwenaiapi" in u || "maas.aliyuncs" in u
    }

    /** 能用的服务商：千问AI平台排前面（用户充的是它）。 */
    fun candidates(providers: List<ProviderConfig>): List<ProviderConfig> =
        providers.filter { ImagePick.usable(it) && supports(it) }.sortedBy { if (it.presetId == "qianwen") 0 else 1 }

    /** 现在做视频用哪家；没有能用的返回 null。设置关了也返回 null（AI 就看不到工具）。 */
    fun resolve(state: AppState): VideoChoice? {
        val v = state.settings.videoGen
        if (!v.enabled) return null
        val all = candidates(state.providers)
        val p = all.firstOrNull { it.id == v.providerId } ?: all.firstOrNull() ?: return null
        return VideoChoice(p.id, p.name, model(v.modelId))
    }

    /** 档位不支持就换最接近的（480P 不支持时用 720P）。 */
    fun resolutionFor(m: VideoModel, wanted: String): String {
        val w = wanted.trim().uppercase()
        if (w in m.resolutions) return w
        val order = listOf("480P", "720P", "1080P")
        val i = order.indexOf(w).coerceAtLeast(0)
        return order.drop(i).firstOrNull { it in m.resolutions } ?: m.resolutions.first()
    }

    fun durationFor(m: VideoModel, wanted: Int): Int = wanted.coerceIn(m.minSec, m.maxSec)

    fun ratioFor(m: VideoModel, wanted: String): String = wanted.trim().takeIf { it in m.ratios } ?: "16:9"

    /** 花费页上的模型名：带档位（价格按档位不同）。 */
    fun billingModel(modelId: String, resolution: String) = "$modelId $resolution"
}
