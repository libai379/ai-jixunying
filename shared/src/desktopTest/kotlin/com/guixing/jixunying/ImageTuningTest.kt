package com.guixing.jixunying

import com.guixing.jixunying.engine.ImageGen
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.ImageTuning
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 画图按模型细调（2026-11-04 用户在微信里把各家画图模型挨个测过：「每个模型风格不一样，得需要写程序细调」）。
 * 去留、审美补丁、反向提示词、改写开关、分辨率，都按模型来。
 */
class ImageTuningTest {

    /** 用户 11-04 16:15 那一轮阿德写的原话（五个模型同一段）。 */
    private val userTest = "写实棚拍人像照片，3:4竖幅，完整全身坐姿。原创成年东亚女性，25岁左右，柔和自然五官，淡妆，深棕色侧分长直发自然垂在肩前，嘴唇自然闭合，神情平静。" +
        "穿黑色细吊带连衣裙，赤足。姿态：坐在地面上，屈膝，双腿交叠斜放在身前，双手自然搭在脚踝和小腿附近，身体略侧向镜头。"

    @Test
    fun userVerdictsDecideWhoIsKept() {
        // 「可灵放弃，太抽搐了，硬性条件没有」「还是毛病 审美挺好 模型太抽搐」（cogview-4）、「有两个脚抽搐的」是 GLM-Image；
        // MiniMax：同一段描述测三张，两张手脚出毛病（用户：「第一张手抽搐了」），照「调不过来就不要调了」放弃
        // Vidu：用户交给我定（「好用就留 不好用就删」），人像常被审核拦、能出图的脸普通又贵，放弃
        for (m in listOf("kling/kling-v3-image-generation", "kling/kling-v3-omni-image-generation", "cogview-4", "cogview-4-250304", "glm-image", "image-01", "image-01-live",
                "vidu/vidu-image-lite_reference2image", "vidu/viduq3-fast_reference2image"))
            assertEquals(ImageTuning.Verdict.DROP, ImageTuning.profile(m).verdict, m)
        // 千问「不会抽搐……审美可以调」；万相 2.7「细节做的都挺好 就是审美不太行 可以调」「能用，行」
        // z-image-turbo：11-04 晚选模型三张都好（用户：「选出几个能用的模型来就行」）
        for (m in listOf("qwen-image-3.0-pro", "qwen-image-3.0", "qwen-image-2.0-pro", "wan2.7-image-pro", "wan2.7-image", "z-image-turbo"))
            assertEquals(ImageTuning.Verdict.KEEP, ImageTuning.profile(m).verdict, m)
        for (m in listOf("cogview-3-flash", "wanx2.1-t2i-turbo", "gpt-image-2.5-sunburst"))
            assertEquals(ImageTuning.Verdict.UNTESTED, ImageTuning.profile(m).verdict, m)
    }

    @Test
    fun onlyAdultWomenPhotosGetTheFacePatch() {
        assertTrue(ImageTuning.youngWomanPhoto(userTest))
        assertTrue(ImageTuning.youngWomanPhoto("一个女孩子站在樱花树下，写实摄影"), "「女孩子」里的「孩子」不算小孩")
        assertTrue(ImageTuning.youngWomanPhoto("portrait photo of a young woman in a red dress"))
        assertTrue(ImageTuning.youngWomanPhoto("成年少女，校园风写真"))
        assertFalse(ImageTuning.youngWomanPhoto("可爱的10岁中国女孩，红裙子，商业摄影"), "未成年不加颜值和妆容的词")
        assertFalse(ImageTuning.youngWomanPhoto("少女在海边奔跑"), "少女没说成年，按未成年算")
        assertFalse(ImageTuning.youngWomanPhoto("一位60岁的老奶奶在院子里择菜"))
        assertFalse(ImageTuning.youngWomanPhoto("中年女性，职业装"))
        assertFalse(ImageTuning.youngWomanPhoto("动漫风格的女孩，二次元"), "不是照片不补摄影的词")
        assertFalse(ImageTuning.youngWomanPhoto("一只橘猫趴在窗台上晒太阳"))
        assertFalse(ImageTuning.youngWomanPhoto("一个男人在跑步"))
        // 写明不要的不算（11-04 实测：「禁止……动漫风格」让 MiniMax 那张没补上）
        assertTrue(ImageTuning.youngWomanPhoto("写实棚拍人像，原创25岁成年东亚女性，坐在地上。禁止额外肢体、手脚融合、夸张大眼、动漫风格、水印、字幕。"))
        assertTrue(ImageTuning.youngWomanPhoto("写实人像，年轻女性，皮肤细腻，不要显老、皱纹，避免卡通感。"))
        assertTrue(ImageTuning.youngWomanPhoto("人像照片，一位女生。禁止：动漫、插画、儿童画风。"))
        assertFalse(ImageTuning.youngWomanPhoto("不要写实，画成动漫风格的女孩"), "要的是动漫")
    }

    @Test
    fun eachModelGetsItsOwnPatch() {
        val qwen = ImageTuning.tail("qwen-image-3.0-pro", userTest)!!
        assertTrue("时尚人像摄影" in qwen && "比实际年龄年轻几岁" in qwen, qwen)   // 写了 25 岁：不硬改成二十出头
        val wan = ImageTuning.tail("wan2.7-image-pro", userTest)!!
        assertTrue("下颌线柔和" in wan && "鼻子小巧" in wan, wan)              // 偏男相
        // MiniMax 补「光线明亮」试过：同一个种子光线没亮、还多画了一只脚，不补，只关它自带的改写
        assertNull(ImageTuning.tail("image-01", userTest))
        assertTrue("二十出头" in ImageTuning.tail("qwen-image-3.0", "一位女生在咖啡馆看书，写实摄影")!!)
        assertNull(ImageTuning.tail("z-image-turbo", userTest), "没测出毛病的不补")
        assertNull(ImageTuning.tail("kling/kling-v3-omni-image-generation", userTest))
        assertNull(ImageTuning.tail("qwen-image-3.0", "一只橘猫趴在窗台上晒太阳"))

        val plan = ImageTuning.plan("qwen-image-3.0-pro", userTest)
        assertTrue(plan.prompt.startsWith(userTest) && plan.prompt.endsWith(plan.added) && plan.added.isNotEmpty())
        // qwen-image-2.0 提示词上限 1300：补上去会超的就不补，别挤掉用户的描述
        val long = userTest + "细节".repeat(560)
        assertEquals(long, ImageTuning.plan("qwen-image-2.0", long).prompt)
        assertTrue(ImageTuning.plan("qwen-image-3.0", long).added.isNotEmpty(), "3.0 上限 4500，照补")
    }

    /** 参考图：只有官方写明收图的模型才收（千问 3.0 三张、2.1 十张、万相 2.7 九张）；带了参考图不补审美、不加「显老」那些反向提示词。 */
    @Test
    fun referencesOnlyWhereTheDocsSaySo() {
        assertEquals(3, ImageTuning.maxReferences("qwen-image-3.0-pro"))
        assertEquals(10, ImageTuning.maxReferences("qwen-image-2.1-turbo"))
        assertEquals(9, ImageTuning.maxReferences("wan2.7-image"))
        for (m in listOf("z-image-turbo", "image-01", "vidu/vidu-image-lite_reference2image", "kling/kling-v3-omni-image-generation", "gpt-image-2.5-flare", "wan2.6-t2i"))
            assertEquals(0, ImageTuning.maxReferences(m), m)
        val withRef = ImageTuning.plan("qwen-image-3.0", userTest, refs = 1)
        assertEquals(userTest, withRef.prompt)
        assertFalse("显老" in withRef.negative!!)
        assertTrue(ImageTuning.plan("qwen-image-3.0", userTest).added.isNotEmpty())
    }

    /** MiniMax 有一次回了张灰底带零星彩点的图、状态码照样成功（11-04 实测）：认出来当没画成；正常的暗调图、黑底图不误伤。 */
    @Test
    fun grayPlaceholderIsNotAPicture() {
        val gen = ImageGen { null }
        val rnd = java.util.Random(1)
        fun img(w: Int, h: Int, f: (Int, Int) -> Int) = com.guixing.jixunying.engine.encodePixels(
            com.guixing.jixunying.engine.Pixels(w, h, IntArray(w * h) { i -> (0xFF shl 24) or f(i % w, i / w) }), 90)
        val gray = img(432, 576) { _, _ -> if (rnd.nextInt(200) == 0) rnd.nextInt(0xFFFFFF) else 0x807E81 }
        assertTrue(gen.looksBlank(gray))
        // 黑底上坐着个人（占三成）
        val portrait = img(432, 576) { x, y -> if (x in 130..300 && y in 100..560) 0xC89A80 else 0x050706 }
        assertFalse(gen.looksBlank(portrait))
        // 黑底星空：几乎全黑也不算
        val night = img(432, 576) { _, _ -> if (rnd.nextInt(300) == 0) 0xFFFFFF else 0x020208 }
        assertFalse(gen.looksBlank(night))
    }

    @Test
    fun parametersFollowTheOfficialDocs() {
        // 反向提示词：「qwen-image-2.1 系列、wan2.7-image-pro 和 wan2.7-image 不支持此参数，其余模型均支持」
        assertNotNull(ImageTuning.plan("qwen-image-3.0", "一只猫").negative)
        assertNotNull(ImageTuning.plan("wan2.6-t2i", "一只猫").negative)
        assertNotNull(ImageTuning.plan("wanx2.1-t2i-turbo", "一只猫").negative)
        for (m in listOf("qwen-image-2.1-pro", "wan2.7-image-pro", "z-image-turbo", "image-01", "kling/kling-v3-image-generation"))
            assertNull(ImageTuning.plan(m, "一只猫").negative, m)
        val catNeg = ImageTuning.plan("qwen-image-3.0", "一只猫").negative!!
        val womanNeg = ImageTuning.plan("qwen-image-3.0", userTest).negative!!
        assertFalse("显老" in catNeg)
        assertTrue("显老" in womanNeg && womanNeg.length <= 500)
        // 改写：z-image 关（开着算「思考」档贵一倍）；万相 2.7 不发；MiniMax 长描述关、短的开；千问开
        assertEquals(false, ImageTuning.rewrite("z-image-turbo", userTest))
        assertNull(ImageTuning.rewrite("wan2.7-image", userTest))
        assertEquals(false, ImageTuning.rewrite("image-01", userTest))
        assertEquals(true, ImageTuning.rewrite("image-01", "一只猫"))
        assertEquals(true, ImageTuning.rewrite("qwen-image-3.0-pro", userTest))
    }

    @Test
    fun drawAsLargeAsTheSameMoneyAllows() {
        fun sized(model: String, w: Int, h: Int): Pair<Int, Int> {
            val t = ImageTuning.targetPixels(model)
            val (sw, sh) = if (t == null) w to h else ImageGen.scaleToPixels(w, h, t)
            return ImageGen.dashScopeSize(model.lowercase(), sw, sh)
        }
        // qwen-image-3.0：1K、2K 同价 → 按 2K 画；3:4 照官方推荐那样长边过 2048
        val (qw, qh) = sized("qwen-image-3.0", 1024, 1365)
        assertTrue(qw.toLong() * qh in 3_900_000..4_194_304 && qh > 2048 && qw % 16 == 0 && qh % 16 == 0, "${qw}x$qh")
        // qwen-image-3.0-pro：超过 225 万像素算 2K 档贵一倍 → 压在 1K 档里画到最大
        val (pw, ph) = sized("qwen-image-3.0-pro", 1024, 1365)
        assertTrue(pw.toLong() * ph in 2_000_000..2_250_000, "${pw}x$ph")
        val (bw, bh) = sized("qwen-image-3.0-pro", 2048, 2048)
        assertTrue(bw.toLong() * bh <= 2_250_000, "AI 要了 2K 也压回 1K 档：${bw}x$bh")
        // 万相 2.7：默认就是 2K
        val (ww, wh) = sized("wan2.7-image-pro", 1024, 1024)
        assertEquals(2048 to 2048, ww to wh)
        // 别家不动
        assertEquals(null, ImageTuning.targetPixels("z-image-turbo"))
        assertEquals(null, ImageTuning.targetPixels("image-01"))
    }

    @Test
    fun droppedModelsAreNotPickedAutomatically() {
        fun prov(id: String, preset: String, vararg models: String) =
            ProviderConfig(id, preset, id, "https://example.com/v1", "k", models = models.map { ModelInfo(it, imageGen = true) })
        val providers = listOf(
            prov("q", "qianwen", "kling/kling-v3-omni-image-generation", "qwen-image-3.0", "wan2.7-image-pro"),
            prov("z", "zhipu", "cogview-4", "cogview-3-flash"),
            prov("m", "minimax", "image-01"),
        )
        val all = ImagePick.candidates(providers).map { it.modelId }
        assertTrue(all.indexOf("kling/kling-v3-omni-image-generation") > all.indexOf("cogview-3-flash"), "放弃的垫底：$all")
        assertTrue(all.indexOf("image-01") > all.indexOf("cogview-3-flash"), "放弃的垫底：$all")
        assertTrue(all.indexOf("wan2.7-image-pro") < all.indexOf("cogview-3-flash"), "实测能用的往前：$all")
        val auto = ImagePick.resolve(AppState(providers = providers)).map { it.modelId }
        assertFalse(auto.any { ImageTuning.dropped(it) }, "自动挑不用放弃的：$auto")
        assertEquals("qwen-image-3.0", auto.first())
        // 只剩放弃的：照用（总比画不了强）。用自定义服务商：千问AI平台会按预设把它能画的全带上
        val onlyKling = ImagePick.resolve(AppState(providers = listOf(prov("q", "custom", "kling/kling-v3-image-generation")))).map { it.modelId }
        assertTrue("kling/kling-v3-image-generation" in onlyKling, onlyKling.toString())
    }

    /**
     * 2026-11-05 用户看阿雯一轮：「皮肤带塑料感」「像油画」，要做的是抖音实拍那种。描述里要真实质感时，
     * 补的审美只管脸年轻好看，不带「理想化、商业摄影、妆容精致、透亮有光泽」；反向提示词不加「皱纹、皮肤粗糙」。没写的照旧（防显老还在）。
     */
    @Test
    fun realSkinIsNotPolishedAway() {
        val real = "实拍感人像，一位二十出头的年轻女性站在落地镜前，皮肤保留毛孔和细小绒毛，不磨皮、不美颜，手机随手拍的质感"
        assertTrue(ImageTuning.wantsRealSkin(real))
        val t = assertNotNull(ImageTuning.tail("qwen-image-3.0-pro", real))
        assertTrue("年轻好看" in t, t)
        assertFalse("理想化" in t || "透亮有光泽" in t || "商业摄影" in t || "妆容干净精致" in t, t)
        val plan = ImageTuning.plan("qwen-image-3.0-pro", real)
        val neg = assertNotNull(plan.negative)
        assertTrue("显老" in neg && "皮肤过度光滑" in neg, neg)
        assertFalse("皮肤暗沉粗糙" in neg || "皱纹" in neg, neg)
        val w = assertNotNull(ImageTuning.tail("wan2.7-image-pro", real))
        assertFalse("理想化" in w || "透亮" in w, w)

        // 没要真实质感的照旧补理想化审美（11-04 治「画老、素人脸」那一套）
        val plain = "时尚人像，一位二十五岁的年轻女性，白色连衣裙，站在花园里，微笑看镜头"
        assertFalse(ImageTuning.wantsRealSkin(plain))
        assertTrue("理想化" in assertNotNull(ImageTuning.tail("qwen-image-3.0-pro", plain)))
        assertTrue("皮肤暗沉粗糙" in assertNotNull(ImageTuning.plan("qwen-image-3.0-pro", plain).negative))
    }

    /** 放弃的模型点名才用（2026-11-05 阿雯没被点名自己挑了可灵）：认编号、去掉前缀的编号和各家叫法。 */
    @Test
    fun droppedModelOnlyWhenNamed() {
        val kling = "kling/kling-v3-omni-image-generation"
        assertTrue(ImageTuning.dropped(kling))
        assertFalse(ImageTuning.namedIn(kling, "很丑，再画一张"))
        assertTrue(ImageTuning.namedIn(kling, "用可灵画一张试试"))
        assertTrue(ImageTuning.namedIn(kling, "试试 Kling 那个模型"))
        assertTrue(ImageTuning.namedIn("cogview-4", "智谱的画一张"))
        assertTrue(ImageTuning.namedIn("image-01", "海螺画图试一下"))
        assertFalse(ImageTuning.namedIn("vidu/viduq2-fast_reference2image", "换个模型"))
        assertFalse(ImageTuning.dropped("qwen-image-3.0-pro"))
    }
}
