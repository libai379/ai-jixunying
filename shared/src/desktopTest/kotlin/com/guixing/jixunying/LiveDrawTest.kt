package com.guixing.jixunying

import com.guixing.jixunying.engine.ImageGen
import com.guixing.jixunying.engine.Storage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 真画几张图对比画图模型：同样的描述（成员自己写的「要写字」「不要字」两种），用不同画图模型各画一张，存到 JXY_DRAW_OUT。
 * 只在设了 JXY_LIVE_DRAW_REAL=数据文件夹 时跑，JXY_DRAW_MODELS 写要比的模型（逗号隔开，例如 qwen-image-3.0,cogview-3-flash）。
 * 会花钱（千问 qwen-image-3.0 每张 0.18 元），跑之前算好预算。不打印 Key。
 */
class LiveDrawTest {
    @Test
    fun drawWithRealModels() = runBlocking {
        val dir = System.getenv("JXY_LIVE_DRAW_REAL") ?: return@runBlocking
        val out = File(System.getenv("JXY_DRAW_OUT") ?: return@runBlocking).apply { mkdirs() }
        val wanted = System.getenv("JXY_DRAW_MODELS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@runBlocking
        val state = Storage(File(dir)).loadState() ?: return@runBlocking
        val gen = ImageGen { null }
        // 两段描述是阿德（deepseek-flash）在 App 里真实设定下自己写的（见 LiveDrawPromptTest 的输出）
        val prompts = listOf(
            "text" to "中秋节主题海报，竖版构图。深蓝色渐变夜空，画面右上方一轮巨大的金黄色满月，月面上有淡淡的玉兔与桂树剪影。前景左侧是中式古典庭院的飞檐一角与盛开的桂花枝，空中漂浮两三盏暖黄色灯笼和几缕流云。国潮水墨插画风格，细节精致，暖金色与深蓝配色，宁静温暖的节日氛围。画面正中偏下位置用金色毛笔书法体清晰写出五个大字「月圆人团圆」，字迹工整易读，横向排列，无其他多余文字。",
            "notext" to "中秋夜景插画：一轮又大又圆的满月高悬在深蓝紫色的夜空中央，月光皎洁，周围淡淡云絮。画面下方是中式传统庭院与飞檐屋顶的剪影，屋檐下挂着几盏暖橙色灯笼，散发出柔和光晕。庭院里一棵盛开的桂花树，枝头点缀细小金色花簇，树下木桌上摆着茶壶、茶杯和几块月饼。远处是层叠的山影与一条映着月光的河，水面有月亮的倒影。整体风格：中国风唯美插画，工笔与写意结合，构图层次分明，冷色调夜空与暖色调灯火形成对比，安静、团圆的氛围。画面中不出现任何文字、汉字、字母、数字、招牌或印章字样，纯净无文字的纯图像。",
        )
        for (model in wanted) {
            val p = state.providers.firstOrNull { pr -> pr.models.any { it.id == model } } ?: run { println("没有服务商有 $model"); continue }
            for ((tag, prompt) in prompts) {
                val t0 = System.currentTimeMillis()
                runCatching { gen.generate(p, model, prompt, "1024x1024") }
                    .onSuccess { r ->
                        val f = File(out, "$model-$tag.${r.mime.substringAfter('/').replace("jpeg", "jpg")}")
                        f.writeBytes(r.bytes)
                        println("$model [$tag] 画好了 ${(System.currentTimeMillis() - t0) / 1000.0} 秒 → ${f.name}（${r.bytes.size / 1024} KB）")
                    }
                    .onFailure { println("$model [$tag] 失败：${it.message?.take(200)}") }
            }
        }
    }
}
