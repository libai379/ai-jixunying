package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/*
 * 液态玻璃调参（2026-10-24，docs/界面换装说明.md 第 6 节）。用户：「业态这一块儿，你只把它做进去了，那全套没有做进去。我怎么调整呢？
 * 而且你这个效果很垃圾，需要我自己去调整才行」→ 照癸星科技的调参台（F:\guixingkeji\app\src\main\kotlin\com\guixing\glasstuning\
 * GlassTuningModel.kt、GlassTuningActivity.kt）搬：每个位置一套参数（形状、模糊 / 折射、高光、外阴影、内阴影、染色），
 * 实时预览，恢复默认，预设（保存 / 应用 / 改名 / 删除），导出 JSON。
 * 我们多一项「底色」：玻璃上铺一层跟着深浅色走的底，保证上面的字看得清（癸星科技那边是 tint 一个颜色，深浅色切换时不跟着变）。
 */

/** 界面里哪一类玻璃各调各的。 */
enum class GlassTarget(val label: String) {
    SIDEBAR("侧栏"),
    BAR("顶栏和输入框"),
    CARD("设置卡片"),
    CONTROL("按钮和开关"),
    DIALOG("弹窗"),
}

/** 和库里 HighlightStyle 的三种对应（Ambient / Default / Plain）。电脑端只有 Plain 能画（另外两种的着色器和新版 skiko 不兼容）。 */
enum class HighlightStyleKind { Ambient, Default, Plain }

@Serializable
data class GlassHighlightParams(
    val enabled: Boolean = true,
    val style: HighlightStyleKind = HighlightStyleKind.Plain,
    val widthDp: Float = 0.8f,
    val blurRadiusDp: Float = 0f,
    val alpha: Float = 0.5f,
    val colorArgb: Long = 0xFFFFFFFFL,
    val angleDeg: Float = 45f,
    val falloff: Float = 1f,
    val ambientIntensity: Float = 0.38f,
)

@Serializable
data class GlassShadowParams(
    val enabled: Boolean = true,
    val radiusDp: Float = 6.273656f,
    val offsetXDp: Float = -0.41332245f,
    val offsetYDp: Float = 4f,
    val colorArgb: Long = 0x4D000000L,
    val alpha: Float = 0.64483225f,
)

@Serializable
data class GlassInnerShadowParams(
    val enabled: Boolean = false,
    val radiusDp: Float = 24f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 24f,
    val colorArgb: Long = 0x26000000L,
    val alpha: Float = 1f,
)

@Serializable
data class GlassTintParams(
    val enabled: Boolean = false,
    val colorArgb: Long = 0xFF0088FFL,
    val alpha: Float = 0.75f,
)

@Serializable
data class GlassParams(
    /** 圆角。按钮、开关是圆的 / 胶囊的，不用这一项。 */
    val cornerRadiusDp: Float = 16f,
    val blurRadiusDp: Float = 0f,
    val refractionHeightDp: Float = 48f,
    val refractionAmountDp: Float = 64f,
    val depthEffect: Boolean = true,
    /** 色散：库的着色器采样坐标没夹住，背后有字时花屏（癸星科技 PITFALLS.md），默认关，开了自己看。 */
    val chromaticAberration: Boolean = false,
    val vibrancy: Boolean = true,
    /** 底色：浅色 / 深色时铺多厚（0～1），颜色跟着主题的卡片色走。 */
    val surfaceLight: Float = 0.38f,
    val surfaceDark: Float = 0.24f,
    val highlight: GlassHighlightParams = GlassHighlightParams(),
    val shadow: GlassShadowParams = GlassShadowParams(),
    val innerShadow: GlassInnerShadowParams = GlassInnerShadowParams(),
    val tint: GlassTintParams = GlassTintParams(),
)

@Serializable
data class GlassPreset(val id: String, val name: String, val params: Map<String, GlassParams> = emptyMap())

/** 存在 Settings.glassTuning：每个位置改过的参数（没改过的用默认），和用户存的预设。 */
@Serializable
data class GlassTuning(
    val byTarget: Map<String, GlassParams> = emptyMap(),
    val presets: List<GlassPreset> = emptyList(),
) {
    fun paramsFor(t: GlassTarget): GlassParams = byTarget[t.name] ?: GlassDefaults.of(t)
    fun with(t: GlassTarget, p: GlassParams) = copy(byTarget = byTarget + (t.name to p))
    fun reset(t: GlassTarget) = copy(byTarget = byTarget - t.name)
    /** 现在每个位置生效的参数（存预设用）。 */
    fun snapshot(): Map<String, GlassParams> = GlassTarget.entries.associate { it.name to paramsFor(it) }
    fun apply(p: GlassPreset) = copy(byTarget = byTarget + p.params.filterKeys { k -> GlassTarget.entries.any { it.name == k } })
}

object GlassDefaults {
    /** 每个位置的默认：就是 10-24 用户看到的样子（折射 48 / 64、不模糊，底色按位置不同），改过以后点「恢复默认」回到这里。 */
    fun of(t: GlassTarget): GlassParams = when (t) {
        GlassTarget.SIDEBAR -> GlassParams(cornerRadiusDp = 22f, surfaceLight = 0.38f, surfaceDark = 0.24f)
        GlassTarget.BAR -> GlassParams(cornerRadiusDp = 22f, surfaceLight = 0.30f, surfaceDark = 0.18f)
        GlassTarget.CARD -> GlassParams(cornerRadiusDp = 12f, surfaceLight = 0.38f, surfaceDark = 0.24f)
        GlassTarget.CONTROL -> GlassParams(cornerRadiusDp = 999f, surfaceLight = 0.10f, surfaceDark = 0.06f)
        GlassTarget.DIALOG -> GlassParams(cornerRadiusDp = 22f, surfaceLight = 0.38f, surfaceDark = 0.24f)
    }

    /**
     * 用户 08-08 在癸星科技真机调参台里调出来的那套（TunedGlassParams，GlassTuningModel.kt:101）：圆角 36.2、不模糊、折射 48 / 64、
     * depthEffect、vibrancy、阴影 6.27dp 偏移 (-0.41, 4) 颜色 0x4D000000 透明度 0.645、高光关、染色关。色散那边是开的，这里关
     * （我们背后有字，开了花屏）。底色铺 0：完全照搬就是全透明，字可能看不清，留给用户自己加。
     */
    val tuned = GlassParams(
        cornerRadiusDp = 36.200985f, blurRadiusDp = 0f, refractionHeightDp = 48f, refractionAmountDp = 64f,
        depthEffect = true, chromaticAberration = false, vibrancy = true, surfaceLight = 0f, surfaceDark = 0f,
        highlight = GlassHighlightParams(enabled = false, style = HighlightStyleKind.Default, widthDp = 0.47776306f, blurRadiusDp = 4.526637f,
            alpha = 1f, angleDeg = 185.30353f, falloff = 0.34610263f),
        shadow = GlassShadowParams(),
        tint = GlassTintParams(enabled = false, colorArgb = 0xFF0088FFL, alpha = 0.75f),
    )

    const val BUILT_IN_DEFAULT = "builtin-default"
    const val BUILT_IN_TUNED = "builtin-tuned"

    /** 两个自带的预设：「AI集训营默认」「癸星科技调好的」（按钮、开关保持圆的）。 */
    val builtIns: List<GlassPreset> = listOf(
        GlassPreset(BUILT_IN_DEFAULT, "AI集训营默认", GlassTarget.entries.associate { it.name to of(it) }),
        GlassPreset(BUILT_IN_TUNED, "癸星科技调好的", GlassTarget.entries.associate {
            it.name to if (it == GlassTarget.CONTROL) tuned.copy(cornerRadiusDp = 999f) else tuned
        }),
    )
}

/**
 * 界面背景（2026-10-24 用户：「界面壁纸我添加不了自己的图片，色彩渐变纯色我自己改不了颜色」）。
 * 照癸星科技的 GlassBackgroundConfig（app\src\main\kotlin\com\guixing\glassui\GlassBackground.kt）：默认 / 纯色 / 渐变 / 图片，
 * 亮度、对比度、饱和度三种都能调（纯色、渐变是把同一个色彩变换算进颜色本身），模糊给渐变和图片，透明度只给图片
 * （纯色、渐变一律不透明：背景是最底层，半透明只能和随便定的底色混）。图片是上传到本机数据里的文件（imageId）。
 */
enum class BgMode(val label: String) { DEFAULT("默认"), SOLID("纯色"), GRADIENT("渐变"), IMAGE("图片") }

@Serializable
data class AppBackground(
    val mode: BgMode = BgMode.DEFAULT,
    val solidArgb: Long = 0xFF15151AL,
    val gradientArgb: List<Long> = listOf(0xFF8B7CFFL, 0xFF3CE8D8L),
    val gradientAngle: Float = 45f,
    val imageId: String = "",
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val blurDp: Float = 0f,
    val alpha: Float = 1f,
)
