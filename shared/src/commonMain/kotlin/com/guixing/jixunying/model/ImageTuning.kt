package com.guixing.jixunying.model

/**
 * 画图按模型细调（2026-11-04）。
 *
 * 起因：用户在微信里拿同一类提示词（25 岁女生、黑吊带裙、赤足坐在黑背景地上）把各家画图模型挨个测了一遍
 * （微信对话 11-04 15:54—16:31），说「每个模型风格不一样，得需要写程序细调」。用户定的原则（16:25 原话）：
 * 「MiniMax 和千问他们的模型不会抽搐，虽然他们的审美不太行，但是审美可以调，可灵等等那些模型虽然审美行，
 * 但是抽搐模型上限不太行，所以直接放弃」；万相 2.7 pro「偏男性的脸，细节做的都挺好 就是审美不太行 可以调」；
 * 「不能只靠一个模型」。也就是：手脚画畸形是硬条件，不行的直接放弃；审美由程序按模型补。
 *
 * 这里管三件事，ImageGen 照着发：
 * 1. 去留：放弃的不自动用（ImagePick 排到最后、自动挑时跳过），AI 的模型清单里标出来，用户点名才用
 * 2. 审美补丁：用户测出来的各家画人的毛病，画年轻女性写实人像时在提示词后面补一句、补反向提示词
 * 3. 接口参数：分辨率（同样的钱画到最大）、反向提示词、平台改写开关——都照官方文档（出处写在各函数上）
 */
object ImageTuning {

    enum class Verdict { KEEP, DROP, UNTESTED }

    /** family：给人看的名字；note：用户实测出来的脾气（写进 AI 的模型清单）。 */
    class Profile(val family: String, val verdict: Verdict, val note: String)

    /** 「kling/kling-v3-image-generation」这种平台上的第三方，去掉前缀再认。 */
    private fun bare(model: String) = model.lowercase().substringAfterLast('/')

    private fun isMiniMax(m: String) = m == "image-01" || m == "image-01-live"

    fun profile(model: String): Profile {
        val m = bare(model)
        return when {
            m.startsWith("kling") -> Profile("可灵", Verdict.DROP, "手脚画畸形，用户实测后放弃")
            m.startsWith("cogview-4") -> Profile("智谱 CogView-4", Verdict.DROP, "脸好看但手脚畸形，用户实测后放弃")
            m.startsWith("glm-image") -> Profile("智谱 GLM-Image", Verdict.DROP, "脚画畸形，用户实测后放弃")
            m.startsWith("qwen-image") -> Profile("千问", Verdict.KEEP, "结构最稳，手脚基本画对；人像容易画老、脸偏平常（程序自动补审美）")
            m.startsWith("wan2.7") -> Profile("万相 2.7", Verdict.KEEP, "皮肤和细节好；人像偏老、偏男相（程序自动补审美）")
            // 用户 16:25 说过「MiniMax 和千问他们的模型不会抽搐」；我 11-04 同一段描述测三张，一张手乱了（用户：「第一张手抽搐了」）、一张多画一只脚
            isMiniMax(m) -> Profile("MiniMax", Verdict.DROP, "便宜、脸年轻，但手脚时常画畸形，用户实测后放弃")
            m.startsWith("z-image") -> Profile("Z-Image", Verdict.UNTESTED, "测过一张：皮肤偏滑、姿势跑偏，用户没表态")
            m.startsWith("vidu") -> Profile("Vidu", Verdict.UNTESTED, "本来是参考图生图，只给文字也能画；测过，用户没表态")
            else -> Profile("", Verdict.UNTESTED, "")
        }
    }

    fun dropped(model: String) = profile(model).verdict == Verdict.DROP

    // —— 画的是不是年轻女性写实人像（用户测的就是这种，补丁只对它有根据）——

    private val FEMALE = Regex(
        "女孩|女生|女性|女子|女人|姑娘|美女|少女|女模|女演员|女明星|女主|新娘|小姐姐|她|\\bwom[ae]n\\b|\\bgirls?\\b|\\blady\\b|\\bfemale\\b|\\bbride\\b",
        RegexOption.IGNORE_CASE,
    )
    // 「女孩子」里的「孩子」不算小孩
    private val MINOR = Regex(
        "小孩|儿童|(?<![女男])孩子|女童|男童|宝宝|婴儿|幼儿|小朋友|小学生|初中生|中学生|未成年|萝莉|\\bchild|\\bkids?\\b|\\bbaby|\\btoddler|\\bteen",
        RegexOption.IGNORE_CASE,
    )
    private val OLDER = Regex("中年|老年|老人|老奶奶|老太|奶奶|外婆|姥姥|大妈|阿姨|妇人|老妇|皱纹|白发|middle-aged|elderly|old woman|grandma", RegexOption.IGNORE_CASE)
    private val NOT_PHOTO = Regex(
        "动漫|二次元|卡通|插画|漫画|水墨|国画|工笔|油画|水彩|素描|像素|黏土|手办|Q版|剪纸|版画|anime|cartoon|illustration|painting|sketch|pixel art|clay|chibi",
        RegexOption.IGNORE_CASE,
    )
    private val AGE = Regex("(\\d{1,2})\\s*(?:岁|周岁|years? old)", RegexOption.IGNORE_CASE)

    private fun age(prompt: String) = AGE.find(prompt)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 写明不要的东西：「禁止：A、B、C。」这种清单删到句号；「不要 X」「避免 X」删到下一个逗号。
     * 2026-11-04 实测：AI 写「禁止额外肢体……动漫风格、水印」，「动漫」被当成要画动漫，补丁没加上；「不要显老、皱纹」同理会被当成画老人。
     */
    private val LIST_NEG = Regex("(?:禁止|避免|negative prompt|avoid)\\s*[：:][^。\\n]*", RegexOption.IGNORE_CASE)
    private val INLINE_NEG = Regex("(?:禁止|不要|避免|不得|切勿|不能出现|不出现|无任何|\\bno\\b)[^，,。；;\\n]*", RegexOption.IGNORE_CASE)

    /** 提示词里真要画的部分（去掉写明不要的）。 */
    private fun wanted(prompt: String) = INLINE_NEG.replace(LIST_NEG.replace(prompt, ""), "")

    /**
     * 成年女性的写实人像、没写成中老年。写了未成年的（小孩、18 岁以下、「少女」又没说成年）一律不补：不往孩子身上加妆容、颜值的词。
     * 动漫、插画、水墨这些不是照片的也不补（补的是摄影的词）。只看真要画的部分（「禁止动漫风格」不算动漫）。
     */
    fun youngWomanPhoto(prompt: String): Boolean {
        val p = wanted(prompt)
        if (!FEMALE.containsMatchIn(p) || NOT_PHOTO.containsMatchIn(p) || OLDER.containsMatchIn(p) || MINOR.containsMatchIn(p)) return false
        val a = age(p)
        if (a != null && (a < 18 || a >= 35)) return false
        if ("少女" in p && "成年" !in p && a == null) return false
        return true
    }

    /**
     * 程序补在提示词后面的一句（按模型）；不是年轻女性写实人像、或者这家没测出毛病，就是 null。
     * 毛病都是用户 11-04 那几张测出来的（存在 docs/画图细调.md）：
     * - 千问 qwen-image-3.0-pro：要的 25 岁画成三十好几的素人脸，脸偏平常（AI 自己的提示词里堆了很多「真实皮肤纹理、色差」）
     * - 万相 wan2.7-image-pro：脸显老、偏男相（用户：「最丑的一个」「偏男性的脸，细节做的都挺好 就是审美不太行 可以调」）
     * - MiniMax image-01：脸年轻，但光线压得很暗、手脚藏在暗处；自己加了额头的装饰点、红唇浓妆，发型没照写的。
     *   补「光线明亮、照描述画」试过（同一个种子）：光线没亮，还多画了一只脚，看不出好处，不补；只关掉它自带的改写（见 rewrite）
     * 审美的词照官方「图像生成微调」的人像示例（「商业摄影」「时尚人像摄影」「理想化」「精致淡妆」）。
     * 改完对比（同一段描述）：千问 3.0 Pro、千问 3.0 的脸从三十几岁的素人脸变成二十出头的模特脸，手脚照样稳；万相 2.7 Pro 柔和、年轻了一些。
     */
    fun tail(model: String, prompt: String): String? {
        if (!youngWomanPhoto(prompt)) return null
        val m = bare(model)
        val a = age(prompt)
        val young = if (a != null && a >= 25) "看起来比实际年龄年轻几岁" else "看起来二十出头"
        val face = "人物$young，面容年轻好看：五官精致、轮廓清晰，眼睛明亮有神，妆容干净精致，皮肤透亮有光泽"
        return when {
            m.startsWith("qwen-image") -> "画面审美：时尚人像摄影、商业摄影的理想化审美；$face。"
            m.startsWith("wan2.7") -> "画面审美：时尚人像摄影的理想化审美；$face；五官柔美秀气，鼻子小巧，下颌线柔和，头发蓬松有层次、不贴在脸上。"
            else -> null
        }
    }

    // —— 接口参数 ——

    /** 千问AI平台官方给的反向提示词示例（万相 2.6 图像编辑 API 参考 negative_prompt 的示例原文；千问文生图指南里是同一句的英文版）。 */
    const val NEGATIVE = "低分辨率、低质量、肢体变形、手指变形、颜色过度饱和、蜡像感、面部细节缺失、皮肤过度光滑、AI 痕迹明显、构图混乱、文字模糊或扭曲"

    /** 年轻女性人像再加的（对着千问、万相把人画老、画平常的毛病）。 */
    const val NEGATIVE_FACE = "显老、皱纹、法令纹、眼袋、皮肤暗沉粗糙、五官平淡、男性化的五官"

    /**
     * 能收反向提示词的。千问AI平台文生图指南原文：「qwen-image-2.1 系列、wan2.7-image-pro 和 wan2.7-image 不支持此参数，其余模型均支持」；
     * z-image、可灵、Vidu 的 API 参考里没有这个参数，不发。老万相（wan2.5 及以前、wanx）放在 input 里，新的放在 parameters 里（ImageGen 管）。
     */
    fun takesNegative(model: String): Boolean {
        val m = bare(model)
        return when {
            m.startsWith("qwen-image-2.1") || m.startsWith("wan2.7") -> false
            m.startsWith("qwen-image") || m.startsWith("wan") -> true
            else -> false
        }
    }

    /** qwen-image-3.0-pro 的 1K 档上限：API 参考原文「面积不大于 2,250,000 为 qima_output_1k」（1K 档 0.25 元，2K 档 0.5 元）。 */
    const val QWEN_PRO_1K_MAX = 2_250_000L

    /**
     * 同样的钱能画多大就画多大：返回目标总像素（按要的宽高比放大或缩小到这么多）；null = 照要的尺寸。
     * 出处：千问AI平台文生图指南「设置输出图像分辨率」和各模型页「模型价格」（2026-11-04 下载）：
     * - qwen-image-3.0 / 2.1 / 2.0 系列都能出 2K（2048*2048），除 3.0-pro 外 1K、2K 一个价（qwen-image-3.0 两档都是 0.18 元）→ 2K
     * - qwen-image-3.0-pro：超过 225 万像素算 2K 档、贵一倍 → 压在 1K 档里画到最大
     * - wan2.7-image(-pro)：默认就是「"2K" (2048*2048)」，一个价 → 2K（以前按 1K 左右画，比它自己默认的还小）
     * - wan2.6-t2i、wan2.5-t2i-preview：总像素 1280*1280～1440*1440 → 1440*1440
     * 其余（wan2.2 及更早、z-image、MiniMax、智谱……）不动：没查到放大不加钱，或者官方默认就是 1K。
     */
    fun targetPixels(model: String): Long? {
        val m = bare(model)
        return when {
            m.startsWith("qwen-image-3.0-pro") -> QWEN_PRO_1K_MAX
            m.startsWith("qwen-image") || m.startsWith("wan2.7") -> 2048L * 2048
            m.startsWith("wan2.6") || m.startsWith("wan2.5") -> 1440L * 1440
            else -> null
        }
    }

    /**
     * 要不要让平台先改写提示词（千问AI平台 prompt_extend、MiniMax prompt_optimizer）；null = 这家没这个开关、不发。
     * - 千问图像、万相 2.6 及以前：开（官方默认开；「图像生成微调」原文「建议保持默认值以获得最佳效果」；qwen-image-3.0 的思考模式要开着改写才生效）
     * - 万相 2.7：不支持 prompt_extend（文生图指南原文「请改用 thinking_mode」），ImageGen 发 thinking_mode
     * - z-image：关（API 参考原文默认 false，「true：……会增加响应时间和费用」——开着算「思考」档 0.2 元一张，标准档 0.1 元；以前一直开着、账上按 0.1 记）
     * - MiniMax：见 MINIMAX_REWRITE_BELOW
     */
    fun rewrite(model: String, prompt: String): Boolean? {
        val m = bare(model)
        return when {
            m.startsWith("wan2.7") -> null
            m.startsWith("z-image") -> false
            isMiniMax(m) -> prompt.length < MINIMAX_REWRITE_BELOW
            m.startsWith("qwen-image") || m.startsWith("wan") -> true
            else -> null
        }
    }

    /**
     * MiniMax 的 prompt_optimizer 只给短描述开（官方 API 参考原文「是否开启 prompt 自动优化，默认为 false」）。
     * AI 写的描述动辄五六百字，用不着它再改；11-04 同一段描述、同一个种子对比：开着画出来的脸偏老、不像描述，关掉以后年轻好看。
     */
    const val MINIMAX_REWRITE_BELOW = 80

    /** 各家提示词的长度上限（字，按官方 API 参考）：补上去会超的就不补，免得把用户的描述挤掉。 */
    private fun maxChars(model: String): Int {
        val m = bare(model)
        return when {
            isMiniMax(m) -> 1500                      // 「最长 1500 字符」
            m.startsWith("qwen-image-2.0") -> 1300    // 「上限为 1300 Token」，按字算偏保守
            m.startsWith("qwen-image") -> 4500        // 3.0 / 2.1「推荐不超过 4500 Token」
            m.startsWith("wan2.7") -> 5000            // 「长度不超过 5000 个字符」
            else -> 800
        }
    }

    /**
     * 这次画图按模型调好的东西，ImageGen 照着发。
     * prompt：补过的提示词；added：补了哪句（空 = 没补，告诉 AI 用）；negative：反向提示词（null = 不发）；
     * rewrite：平台改写开关（null = 不发）；pixels：目标总像素（null = 照要的尺寸）；seed：只给对比测试用。
     */
    data class Plan(val prompt: String, val added: String, val negative: String?, val rewrite: Boolean?, val pixels: Long?, val seed: Long? = null)

    fun plan(model: String, prompt: String): Plan {
        val t = tail(model, prompt)?.takeIf { prompt.length + it.length + 1 <= maxChars(model) }
        val full = if (t == null) prompt else prompt.trimEnd() + "\n" + t
        val negative = if (!takesNegative(model)) null
            else (NEGATIVE + if (youngWomanPhoto(prompt)) "、$NEGATIVE_FACE" else "").take(500)
        return Plan(full, t.orEmpty(), negative, rewrite(model, prompt), targetPixels(model))
    }
}
