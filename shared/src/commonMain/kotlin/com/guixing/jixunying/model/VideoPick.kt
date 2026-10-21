package com.guixing.jixunying.model

/**
 * 一个做视频的模型能接受什么。出处：千问AI平台文档「视频生成模型」「wan3.0-video 视频生成」和各模型的 API 参考（2026-10-20 / 21 下载原文核对），
 * MiniMax 开放平台「视频生成」「创建视频生成任务」（V2 接口，2026-10-21），都记在 docs/参考资料.md「视频生成」。
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
    /** 哪家的接口：VideoPick.DASHSCOPE（千问AI平台 / 百炼）或 VideoPick.MINIMAX。 */
    val vendor: String = VideoPick.DASHSCOPE,
    /** 能收的素材用途（VideoMediaUse 里的名字）；空 = 只能文生视频。 */
    val media: Set<String> = emptySet(),
)

/** 素材用途：名字照 wan3.0 的 media.type（MiniMax 的 role 名字一样，只是没有 file / link）。 */
object VideoMediaUse {
    const val FIRST = "first_frame"
    const val LAST = "last_frame"
    const val IMAGE = "reference_image"
    const val VIDEO = "reference_video"
    const val AUDIO = "reference_audio"
    const val FILE = "file"
    const val LINK = "link"
    val all = listOf(FIRST, LAST, IMAGE, VIDEO, AUDIO, FILE, LINK)
    val frames = setOf(FIRST, LAST)

    /** 给人看的名字。 */
    fun label(use: String) = when (use) {
        FIRST -> "首帧"
        LAST -> "尾帧"
        IMAGE -> "参考图"
        VIDEO -> "参考视频"
        AUDIO -> "参考音频"
        FILE -> "文档"
        LINK -> "网页"
        else -> use
    }
}

/** 一个能用的做视频的服务商。switched = 设置里选的模型收不了这些素材，换成了这个（给 AI 和用户的说明）。 */
data class VideoChoice(val providerId: String, val providerName: String, val model: VideoModel, val switched: String = "")

/** 做视频用哪家、哪个模型、什么档位。 */
object VideoPick {
    const val DEFAULT_MODEL = "wan3.0-video"
    const val DASHSCOPE = "dashscope"
    const val MINIMAX = "minimax"

    private val WAN30_MEDIA = VideoMediaUse.all.toSet()
    private val H3_MEDIA = setOf(VideoMediaUse.FIRST, VideoMediaUse.LAST, VideoMediaUse.IMAGE, VideoMediaUse.VIDEO, VideoMediaUse.AUDIO)
    private val WIDE = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")

    val models: List<VideoModel> = listOf(
        VideoModel("wan3.0-video", "万相 3.0（推荐，最便宜的 480P 也能用，可开关声音；能用图、视频、音频、文档、网页当素材）", listOf("480P", "720P", "1080P"), 2, 30,
            audioSwitch = true, ratios = WIDE, media = WAN30_MEDIA),
        VideoModel("wan3.0-video-prime", "万相 3.0 高速版（更快，更贵；素材同万相 3.0）", listOf("480P", "720P", "1080P"), 2, 30,
            audioSwitch = true, ratios = WIDE, media = WAN30_MEDIA),
        VideoModel("happyhorse-1.1-t2v", "HappyHorse 1.1（有声，只能文生视频）", listOf("480P", "720P", "1080P"), 3, 15, watermarkDefault = true,
            ratios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "4:5", "5:4", "9:21", "21:9")),
        VideoModel("wan2.7-t2v", "万相 2.7（有声，多镜头，只能文生视频）", listOf("720P", "1080P"), 2, 15),
        VideoModel("wan2.6-t2v", "万相 2.6（有声，只能文生视频）", listOf("720P", "1080P"), 2, 15, bySize = true,
            sizes = mapOf("720P" to "1280*720", "1080P" to "1920*1080")),
        VideoModel("wan2.2-t2v-plus", "万相 2.2（无声，固定 5 秒，只能文生视频）", listOf("480P", "1080P"), 5, 5, bySize = true,
            sizes = mapOf("480P" to "832*480", "1080P" to "1920*1080")),
        VideoModel("wanx2.1-t2v-turbo", "万相 2.1 极速（无声，固定 5 秒，只能文生视频）", listOf("480P", "720P"), 5, 5, bySize = true,
            sizes = mapOf("480P" to "832*480", "720P" to "1280*720")),
        // MiniMax V2 接口（海螺）：时长只能整数 4~15（H3-Max 5~15），文生视频的比例不能是 adaptive
        VideoModel("MiniMax-H3-Max", "MiniMax H3 极速版（海螺，480P / 768P，5～15 秒；能用图、视频、音频当素材）", listOf("480P", "768P"), 5, 15,
            ratios = WIDE, vendor = MINIMAX, media = H3_MEDIA),
        VideoModel("MiniMax-H3", "MiniMax H3（海螺，768P / 2K，4～15 秒；能用图、视频、音频当素材）", listOf("768P", "2K"), 4, 15,
            ratios = WIDE, vendor = MINIMAX, media = H3_MEDIA),
    )

    /** 每家默认用哪个（最便宜、素材最全的）。 */
    fun defaultFor(vendor: String) = if (vendor == MINIMAX) model("MiniMax-H3-Max") else model(DEFAULT_MODEL)

    fun model(id: String): VideoModel = models.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
        ?: VideoModel(id.trim(), id.trim(), listOf("480P", "720P", "1080P"), 2, 15)

    /** 这个服务商是哪家的视频接口；都不是返回 null。MiniMax 只认中国版（国际版的视频接口没核对过）。 */
    fun vendorOf(p: ProviderConfig): String? {
        val u = p.baseUrl.lowercase()
        return when {
            p.presetId == "qianwen" || p.presetId.startsWith("dashscope") || "dashscope" in u || "qianwenaiapi" in u || "maas.aliyuncs" in u -> DASHSCOPE
            p.presetId == "minimax" || "api.minimaxi.com" in u || "api.minimax.cn" in u -> MINIMAX
            else -> null
        }
    }

    fun supports(p: ProviderConfig): Boolean = vendorOf(p) != null

    /** 能用的服务商：千问AI平台排前面（用户充的是它），其次百炼，MiniMax 最后。 */
    fun candidates(providers: List<ProviderConfig>): List<ProviderConfig> =
        providers.filter { ImagePick.usable(it) && supports(it) }
            .sortedBy { if (it.presetId == "qianwen") 0 else if (vendorOf(it) == DASHSCOPE) 1 else 2 }

    /** 设置页的模型清单：只列有 Key 的那几家的模型。 */
    fun modelsFor(providers: List<ProviderConfig>): List<VideoModel> {
        val vendors = candidates(providers).mapNotNull { vendorOf(it) }.toSet()
        return models.filter { it.vendor in vendors }
    }

    /**
     * 现在做视频用哪家；没有能用的返回 null。设置关了也返回 null（AI 就看不到工具）。
     * need = 这次带的素材用途。设置里选的模型收不了时，自动换成能收的（先同一家，再别家），并在 switched 里说明；
     * 谁都收不了就返回设置里那个（由调用的地方报错）。
     */
    fun resolve(state: AppState, need: Set<String> = emptySet()): VideoChoice? {
        val v = state.settings.videoGen
        if (!v.enabled) return null
        val all = candidates(state.providers)
        if (all.isEmpty()) return null
        val wanted = model(v.modelId)
        // 选了哪家：设置里指定的；没指定按模型找同一家；都没有用排第一的
        val p = all.firstOrNull { it.id == v.providerId } ?: all.firstOrNull { vendorOf(it) == wanted.vendor } ?: all.first()
        val pv = vendorOf(p)!!
        val base = if (wanted.vendor == pv) wanted else defaultFor(pv)
        fun ok(m: VideoModel) = need.all { it in m.media }
        if (ok(base)) return VideoChoice(p.id, p.name, base)
        val what = need.filter { it !in base.media }.joinToString("、") { VideoMediaUse.label(it) }
        // 同一家换能收的
        defaultFor(pv).takeIf { ok(it) }?.let { return VideoChoice(p.id, p.name, it, "设置里选的 ${base.id} 收不了$what，这次改用 ${it.id}") }
        // 别家
        for (other in all) {
            val m = defaultFor(vendorOf(other)!!)
            if (ok(m)) return VideoChoice(other.id, other.name, m, "${p.name} 的 ${base.id} 收不了$what，这次改用 ${other.name} 的 ${m.id}")
        }
        return VideoChoice(p.id, p.name, base)
    }

    /** 档位不支持就换最接近的（480P 不支持时用 720P）。 */
    fun resolutionFor(m: VideoModel, wanted: String): String {
        val w = wanted.trim().uppercase()
        if (w in m.resolutions) return w
        val order = listOf("480P", "720P", "768P", "1080P", "2K")
        val i = order.indexOf(w).coerceAtLeast(0)
        return order.drop(i).firstOrNull { it in m.resolutions } ?: m.resolutions.first()
    }

    fun durationFor(m: VideoModel, wanted: Int): Int = wanted.coerceIn(m.minSec, m.maxSec)

    fun ratioFor(m: VideoModel, wanted: String): String = wanted.trim().takeIf { it in m.ratios } ?: "16:9"

    /** 花费页上的模型名：带档位（价格按档位不同）。MiniMax 的输入视频、多出来的图另记一行（extra = 「输入视频」「图片」）。 */
    fun billingModel(modelId: String, resolution: String, extra: String = "") = when (extra) {
        "" -> "$modelId $resolution"
        "图片" -> "$modelId 图片"
        else -> "$modelId $resolution $extra"
    }

    /** MiniMax 每次免费的图片张数（价格页：H3 5 张以内免费，H3-Max 2 张以内免费）。 */
    fun freeImages(modelId: String) = if (modelId.equals("MiniMax-H3-Max", ignoreCase = true)) 2 else 5

    /** 价格表用哪个平台名（Prices.of 的第一个参数）。 */
    fun pricePlatform(m: VideoModel) = if (m.vendor == MINIMAX) "minimax" else "qwen"
}
