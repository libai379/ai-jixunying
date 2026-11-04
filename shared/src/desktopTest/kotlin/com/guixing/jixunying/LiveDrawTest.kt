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
    /**
     * 每个模型真画一张（2026-11-04 用户：「你测试」——核对补全进来的画图模型都画得出、接口和尺寸都对）。
     * JXY_LIVE_DRAW_EACH=数据文件夹（复制一份 state.json 再用），JXY_DRAW_OUT=存图的文件夹，JXY_DRAW_EACH=模型@尺寸,…（尺寸不写就 1024x1024）。
     * 服务商按 ImagePick.candidates 找（预设推断的也算，和 AI 调的时候一样）。花钱，跑之前算好；不打印 Key；不记进用户的账本。
     */
    @Test
    fun drawEachOnce() = runBlocking {
        val dir = System.getenv("JXY_LIVE_DRAW_EACH") ?: return@runBlocking
        val out = File(System.getenv("JXY_DRAW_OUT") ?: return@runBlocking).apply { mkdirs() }
        val wanted = System.getenv("JXY_DRAW_EACH")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@runBlocking
        val state = Storage(File(dir)).loadState() ?: return@runBlocking
        val cands = com.guixing.jixunying.model.ImagePick.candidates(state.providers)
        val gen = ImageGen { null }
        // JXY_DRAW_PROMPT_FILE：换成自己的描述（2026-11-04 拿用户在微信里测的那段人像描述对比细调前后）；JXY_DRAW_TAG 加在文件名后面
        val prompt = System.getenv("JXY_DRAW_PROMPT_FILE")?.let { File(it).readText().trim() }
            ?: "一只橘猫趴在木头窗台上晒太阳，窗外是蓝天和几朵白云，写实摄影风格，柔和的自然光"
        val tag = System.getenv("JXY_DRAW_TAG")?.let { "-$it" }.orEmpty()
        // JXY_DRAW_REFS：参考图文件（逗号隔开），和程序里一样缩成长边 1600 的 JPEG 再发（2026-11-04 参考图画图）
        val refs = System.getenv("JXY_DRAW_REFS")?.split(',')?.filter { it.isNotBlank() }?.map { path ->
            val px = com.guixing.jixunying.engine.decodePixels(File(path.trim()).readBytes())!!
            val (vw, vh) = com.guixing.jixunying.engine.PhotoEdit.viewSize(px.w, px.h)
            com.guixing.jixunying.engine.encodePixels(if (vw == px.w && vh == px.h) px else com.guixing.jixunying.engine.PhotoEdit.resize(px, vw, vh), 88)
        }.orEmpty()
        for (w in wanted) {
            // 模型@尺寸!开关：「!raw」= 不细调（照旧发：不补、不放大、没有反向提示词）；「!rewrite」「!norewrite」= 只改平台改写开关；「!seed=123」固定种子
            val model = w.substringBefore('@').substringBefore('!')
            val size = w.substringAfter('@', "1024x1024").substringBefore('!')
            val flags = w.split('!').drop(1)
            val c = cands.firstOrNull { it.modelId.equals(model, ignoreCase = true) } ?: run { println("$model：没有能调它的服务商"); continue }
            val p = state.provider(c.providerId)!!
            var plan = com.guixing.jixunying.model.ImageTuning.plan(model, prompt, refs.size)
            for (f in flags) plan = when {
                f == "raw" -> plan.copy(prompt = prompt, added = "", negative = null, pixels = null)
                f == "rewrite" -> plan.copy(rewrite = true)
                f == "norewrite" -> plan.copy(rewrite = false)
                f.startsWith("seed=") -> plan.copy(seed = f.substringAfter('=').toLong())
                else -> plan
            }
            val t0 = System.currentTimeMillis()
            runCatching { gen.generate(p, model, prompt, size, refs.take(maxOf(1, com.guixing.jixunying.model.ImageTuning.maxReferences(model))).takeIf { refs.isNotEmpty() }.orEmpty(), plan = plan) }
                .onSuccess { r ->
                    val (iw, ih) = com.guixing.jixunying.engine.PhotoEdit.sizeOf(r.bytes) ?: (0 to 0)
                    val f = File(out, model.replace('/', '_') + flags.joinToString("") { "-" + it.replace('=', '_') } + tag + "." + r.mime.substringAfter('/').replace("jpeg", "jpg"))
                    f.writeBytes(r.bytes)
                    println("$model（${c.providerName}，要 $size，${flags.joinToString(",").ifEmpty { "细调" }}）画好了 ${(System.currentTimeMillis() - t0) / 1000.0} 秒，${iw}x$ih，${r.bytes.size / 1024} KB → ${f.name}" +
                        (if (r.note.isNotBlank()) "；提醒：${r.note.take(60)}" else "") + (if (r.added.isNotEmpty()) "；补了：${r.added}" else "；没补") +
                        "；反向提示词：${if (plan.negative == null) "无" else "有"}；改写：${plan.rewrite}")
                }
                .onFailure { println("$model（${c.providerName}，要 $size）失败 ${(System.currentTimeMillis() - t0) / 1000.0} 秒：${it.message?.take(300)}") }
        }
    }

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
