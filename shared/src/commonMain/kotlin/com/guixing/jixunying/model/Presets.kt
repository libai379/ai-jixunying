package com.guixing.jixunying.model

/**
 * 服务商预设。地址核实于 2026-10-07（来源见 docs/参考资料.md「API 地址」一节）。
 * 九模型测评用过的模型名，照用户 WorkBuddy 里实际跑通的写法（大小写也照抄）。
 * 其余模型名是建议值，填好 Key 后点「拉取模型列表」以服务商返回的为准。
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    val group: String,
    val baseUrl: String,
    val keyUrl: String,
    val models: List<ModelInfo>,
    val badge: String,
    val badgeColor: Long,
    val useProxy: Boolean = false,
    val note: String = "",
)

private fun m(id: String, vision: Boolean = false, tools: Boolean = true) = ModelInfo(id, vision = vision, tools = tools)
private fun img(id: String) = ModelInfo(id, tools = false, imageGen = true)

/** 九模型编程测评的成绩（D:\AI\projects\国产九模型编程测评-完整资料包），在选模型时显示。 */
data class EvalInfo(val rank: Int, val score: String, val cost: String, val reportName: String)

object Evals {
    private val all = mapOf(
        "minimax-m3" to EvalInfo(1, "4.81", "¥2.56", "MiniMax-M3"),
        "deepseek-flash" to EvalInfo(2, "4.68", "¥0.85", "DeepSeek-V4.1-Flash"),
        "mimo-v2.6-pro" to EvalInfo(3, "4.67", "¥0.38", "MiMo-V2.6-Pro"),
        "mimo-v2.6-flash" to EvalInfo(4, "4.64", "¥0.14", "mimo-v2.6-flash"),
        "glm-5.3" to EvalInfo(5, "4.64", "¥11.75", "GLM-5.3"),
        "qwen3.8-max" to EvalInfo(6, "4.61", "¥14.90", "Qwen3-Max"),
        "glm-5.3-flash" to EvalInfo(7, "4.58", "实付¥0", "GLM-5.3-Flash"),
        "deepseek-v4-pro" to EvalInfo(8, "4.48", "¥3.58", "DeepSeek-V4 Pro"),
        "kimi-k3" to EvalInfo(9, "4.39", "¥12.22", "Kimi K3"),
    )

    fun of(modelId: String): EvalInfo? = all[modelId.lowercase()]

    fun label(modelId: String): String? = of(modelId)?.let { "测评第${it.rank} · ${it.score}分 · ${it.cost}" }
}

object Presets {
    const val GROUP_CN = "国内"
    const val GROUP_IMAGE = "国内 · 专门画图"
    const val GROUP_GLOBAL = "海外"
    const val GROUP_HUB = "聚合平台与本地"
    const val GROUP_OTHER = "其他"

    val all: List<ProviderPreset> = listOf(
        // —— 国内（九模型测评的五家排前面）——
        ProviderPreset("deepseek", "深度求索 DeepSeek", GROUP_CN, "https://api.deepseek.com",
            "https://platform.deepseek.com/api_keys",
            listOf(m("deepseek-flash"), m("deepseek-v4-pro")), "D", 0xFF4D6BFE,
            note = "deepseek-flash 即测评里的 DeepSeek-V4.1-Flash（测评第 2）"),
        ProviderPreset("minimax", "MiniMax 中国版", GROUP_CN, "https://api.minimaxi.com/v1",
            "https://platform.minimaxi.com/user-center/basic-information/interface-key",
            listOf(m("MiniMax-M3", vision = true), img("image-01")), "M", 0xFFE5484D,
            note = "MiniMax-M3 测评第 1；画图用 image-01"),
        ProviderPreset("mimo", "小米 MiMo", GROUP_CN, "https://api.xiaomimimo.com/v1",
            "https://platform.xiaomimimo.com/",
            listOf(m("mimo-v2.6-pro"), m("mimo-v2.6-flash")), "米", 0xFFFF6900,
            note = "测评第 3、4，最便宜"),
        ProviderPreset("zhipu", "智谱开放平台", GROUP_CN, "https://open.bigmodel.cn/api/paas/v4",
            "https://open.bigmodel.cn/usercenter/apikeys",
            listOf(m("glm-5.3"), m("GLM-5.3-Flash"), m("glm-4.6v", vision = true), img("cogview-4-250304"), img("cogview-3-flash")),
            "Z", 0xFF1F2A44, note = "带官方联网搜索；画图 cogview-3-flash 免费"),
        ProviderPreset("qianwen", "千问AI平台（阿里）", GROUP_CN, "https://maas.qianwenaiapi.com/compatible-mode/v1",
            "https://platform.qianwenai.com/docs/api-reference/preparation/api-key",
            listOf(m("qwen3.8-max"), img("qwen-image-3.0"), img("wan2.7-image")), "千", 0xFF6236FF,
            note = "qwen3.8-max 即测评里的 Qwen3-Max；带官方联网搜索；画图千问图像 / 万相"),
        ProviderPreset("moonshot", "Kimi 中国版", GROUP_CN, "https://api.moonshot.cn/v1",
            "https://platform.moonshot.cn/console/api-keys",
            listOf(m("kimi-k3", vision = true)), "K", 0xFF111111,
            note = "带官方联网搜索；kimi-k3 只接受 temperature=1，成员里温度留空即可"),
        ProviderPreset("dashscope", "阿里云百炼（通义千问 · 万相）", GROUP_CN, "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://bailian.console.aliyun.com/?tab=model#/api-key",
            listOf(m("qwen3-max"), m("qwen-plus"), m("qwen3-vl-plus", vision = true),
                img("qwen-image-3.0"), img("qwen-image-plus"), img("wan2.7-image"), img("wan2.6-t2i")),
            "通", 0xFF6236FF, note = "带官方联网搜索；画图有千问图像和通义万相"),
        ProviderPreset("ark", "火山方舟（豆包 · Seedream）", GROUP_CN, "https://ark.cn-beijing.volces.com/api/v3",
            "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey",
            listOf(m("doubao-seed-2-1-pro-260628", vision = true),
                img("doubao-seedream-5-0-260128"), img("doubao-seedream-4-5-251128"), img("doubao-seedream-4-0-250828")),
            "豆", 0xFF3370FF, note = "模型名填方舟控制台里的 Model ID；Seedream 就是即梦的同款画图模型"),
        ProviderPreset("tokenhub", "腾讯云 TokenHub", GROUP_CN, "https://tokenhub.tencentmaas.com/v1",
            "https://console.cloud.tencent.com/tokenhub",
            listOf(m("deepseek-v4-pro"), m("glm-5.3"), m("kimi-k3"), img("HY-Image-V3.0")), "T", 0xFF00A3FF,
            note = "按量付费，聚合多家模型；画图是混元生图 3.0"),
        ProviderPreset("tokenplan", "腾讯云 Token Plan（个人版）", GROUP_CN, "https://api.lkeap.cloud.tencent.com/plan/v3",
            "https://console.cloud.tencent.com/tokenhub",
            listOf(m("deepseek-v4-pro"), m("glm-5.3")), "T", 0xFF00A3FF, note = "订阅套餐，个人版只能生成一个 Key"),
        ProviderPreset("tokenhub-sg", "腾讯云 TokenHub（新加坡）", GROUP_CN, "https://tokenhub-intl.tencentcloudmaas.com/v1",
            "https://console.tencentcloud.com/tokenhub",
            listOf(m("deepseek-v4-pro"), m("glm-5.3")), "T", 0xFF00A3FF),
        ProviderPreset("hunyuan", "腾讯混元", GROUP_CN, "https://api.hunyuan.cloud.tencent.com/v1",
            "https://console.cloud.tencent.com/hunyuan/start",
            listOf(m("hunyuan-turbos-latest"), m("hunyuan-vision", vision = true)), "混", 0xFF00A3FF),
        ProviderPreset("qianfan", "百度千帆（文心 · iRAG）", GROUP_CN, "https://qianfan.baidubce.com/v2",
            "https://console.bce.baidu.com/iam/#/iam/apikey/list",
            listOf(m("ernie-5.0"), m("ernie-4.5-turbo-vl", vision = true), img("irag-1.0")), "文", 0xFF2932E1,
            note = "画图用 iRAG（irag-1.0）"),
        ProviderPreset("stepfun", "阶跃星辰", GROUP_CN, "https://api.stepfun.com/v1",
            "https://platform.stepfun.com/interface-key",
            listOf(m("step-3.5-flash"), m("step-1o-turbo-vision", vision = true), img("step-2x-large"), img("step-1x-medium")),
            "阶", 0xFF0F172A),
        ProviderPreset("longcat", "美团 LongCat", GROUP_CN, "https://api.longcat.chat/openai/v1",
            "https://longcat.chat/platform/api_keys",
            listOf(m("LongCat-Flash-Chat"), m("LongCat-Flash-Thinking")), "龙", 0xFFFFC300, note = "每天有免费额度"),
        ProviderPreset("siliconflow", "硅基流动", GROUP_CN, "https://api.siliconflow.cn/v1",
            "https://cloud.siliconflow.cn/account/ak",
            listOf(m("deepseek-ai/DeepSeek-V3.2"), m("Qwen/Qwen3-VL-32B-Instruct", vision = true),
                img("Kwai-Kolors/Kolors"), img("Qwen/Qwen-Image")),
            "硅", 0xFF7C3AED, note = "开源模型大全；画图有可图 Kolors、Qwen-Image"),
        ProviderPreset("modelscope", "魔搭 ModelScope", GROUP_CN, "https://api-inference.modelscope.cn/v1",
            "https://modelscope.cn/my/myaccesstoken",
            listOf(m("Qwen/Qwen3-235B-A22B-Instruct-2507"), img("Qwen/Qwen-Image")), "魔", 0xFF624AFF,
            note = "每天有免费额度（含画图）"),
        ProviderPreset("lingyi", "零一万物", GROUP_CN, "https://api.lingyiwanwu.com/v1",
            "https://platform.lingyiwanwu.com/apikeys", listOf(m("yi-lightning")), "零", 0xFF003425),
        ProviderPreset("spark", "讯飞星火", GROUP_CN, "https://spark-api-open.xf-yun.com/v1",
            "https://console.xfyun.cn/services/cbm", listOf(m("4.0Ultra"), m("lite")), "讯", 0xFF1A6DFF,
            note = "Key 填控制台里的 APIPassword"),

        // —— 国内专门画图的 ——
        ProviderPreset("kling", "可灵 Kling（快手）", GROUP_IMAGE, "https://api-beijing.klingai.com",
            "https://app.klingai.com/cn/dev/console/application",
            listOf(img("kling-v3"), img("kling-v2-1")), "灵", 0xFF0B0B0F,
            note = "Key 填 API Key；老账号也可以填「AccessKey:SecretKey」"),

        // —— 海外 ——
        ProviderPreset("openai", "OpenAI", GROUP_GLOBAL, "https://api.openai.com/v1",
            "https://platform.openai.com/api-keys",
            listOf(m("gpt-5", vision = true), m("gpt-5-mini", vision = true), img("gpt-image-1")),
            "AI", 0xFF10A37F, useProxy = true),
        ProviderPreset("anthropic", "Anthropic Claude", GROUP_GLOBAL, "https://api.anthropic.com/v1",
            "https://console.anthropic.com/settings/keys",
            listOf(m("claude-opus-5-5", vision = true), m("claude-sonnet-5-5", vision = true), m("claude-haiku-4-5", vision = true)),
            "A", 0xFFD97757, useProxy = true, note = "走 Anthropic 官方的 OpenAI 兼容接口"),
        ProviderPreset("gemini", "Google Gemini", GROUP_GLOBAL, "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://aistudio.google.com/apikey",
            listOf(m("gemini-2.5-pro", vision = true), m("gemini-2.5-flash", vision = true)), "G", 0xFF4285F4, useProxy = true),
        ProviderPreset("xai", "xAI Grok", GROUP_GLOBAL, "https://api.x.ai/v1",
            "https://console.x.ai/", listOf(m("grok-4", vision = true), img("grok-2-image")), "X", 0xFF000000, useProxy = true),
        ProviderPreset("mistral", "Mistral", GROUP_GLOBAL, "https://api.mistral.ai/v1",
            "https://console.mistral.ai/api-keys", listOf(m("mistral-large-latest"), m("pixtral-large-latest", vision = true)),
            "Mi", 0xFFFA520F, useProxy = true),
        ProviderPreset("groq", "Groq", GROUP_GLOBAL, "https://api.groq.com/openai/v1",
            "https://console.groq.com/keys", listOf(m("llama-3.3-70b-versatile")), "Gq", 0xFFF55036, useProxy = true),
        ProviderPreset("moonshot-global", "Kimi 国际版", GROUP_GLOBAL, "https://api.moonshot.ai/v1",
            "https://platform.moonshot.ai/console/api-keys", listOf(m("kimi-k3", vision = true)), "K", 0xFF111111),
        ProviderPreset("minimax-global", "MiniMax 国际版", GROUP_GLOBAL, "https://api.minimax.io/v1",
            "https://www.minimax.io/platform/user-center/basic-information/interface-key",
            listOf(m("MiniMax-M3", vision = true), img("image-01")), "M", 0xFFE5484D),
        ProviderPreset("zai", "Z.ai（智谱国际版）", GROUP_GLOBAL, "https://api.z.ai/api/paas/v4",
            "https://z.ai/manage-apikey/apikey-list", listOf(m("glm-5.3")), "Z", 0xFF1F2A44),
        ProviderPreset("dashscope-intl", "阿里云百炼国际版", GROUP_GLOBAL, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "https://modelstudio.console.alibabacloud.com/", listOf(m("qwen3-max")), "通", 0xFF6236FF),
        ProviderPreset("kling-global", "可灵 Kling 国际版", GROUP_GLOBAL, "https://api-singapore.klingai.com",
            "https://klingai.com/dev", listOf(img("kling-v3")), "灵", 0xFF0B0B0F),

        // —— 聚合与本地 ——
        ProviderPreset("openrouter", "OpenRouter", GROUP_HUB, "https://openrouter.ai/api/v1",
            "https://openrouter.ai/keys", listOf(m("openai/gpt-5", vision = true), m("anthropic/claude-sonnet-5-5", vision = true)),
            "OR", 0xFF6467F2, useProxy = true, note = "一个 Key 用遍海外模型"),
        ProviderPreset("ollama", "Ollama（本机）", GROUP_HUB, "http://127.0.0.1:11434/v1",
            "https://ollama.com/download", listOf(m("qwen3:8b")), "O", 0xFF222222, note = "本机模型，Key 随便填"),
        ProviderPreset("lmstudio", "LM Studio（本机）", GROUP_HUB, "http://127.0.0.1:1234/v1",
            "https://lmstudio.ai/", emptyList(), "LM", 0xFF4F46E5, note = "本机模型，Key 随便填"),

        ProviderPreset("custom", "自定义", GROUP_OTHER, "", "", emptyList(), "✦", 0xFF64748B,
            note = "任何兼容 OpenAI 协议的接口"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.last()

    /** 模型名里能看出是否支持看图。拉取模型列表时用来给默认值，用户可以手动改。 */
    fun guessVision(model: String): Boolean {
        val s = model.lowercase()
        return listOf("vl", "vision", "4o", "gpt-4.1", "gpt-5", "claude", "gemini", "kimi-k2.6", "kimi-k3", "pixtral",
            "grok-4", "omni", "glm-4.5v", "glm-4.6v", "doubao-seed", "qvq", "step-1o", "-v-", "minimax-m3").any { it in s }
    }

    fun guessImageGen(model: String): Boolean {
        val s = model.lowercase()
        return listOf("cogview", "seedream", "kolors", "flux", "gpt-image", "dall-e", "wanx", "wan2.", "stable-diffusion", "qwen-image",
            "imagen", "image-01", "hy-image", "irag", "kling", "step-1x", "step-2x", "grok-2-image", "z-image").any { it in s }
    }
}

/** 给新用户的默认成员模板（选好服务商后一键加入）。 */
object MemberTemplates {
    data class Template(val name: String, val avatar: String, val color: Long, val bio: String)

    val all = listOf(
        Template("小智", "🧠", 0xFF5B6CFF, "全能助手，回答准确、条理清楚，遇到事实问题先查证再说。"),
        Template("老钱", "📊", 0xFF0EA5E9, "数据和商业分析，说话直接，喜欢用数字说明问题，会主动指出风险。"),
        Template("阿码", "💻", 0xFF10B981, "资深程序员，擅长写代码和排查问题，回答带可运行的代码。"),
        Template("杠精", "🧐", 0xFFF59E0B, "专职挑错的审稿人：别人说得对就认，说得不对就指出哪里不对、为什么，不为反对而反对。"),
        Template("文青", "✍️", 0xFFEC4899, "文案和写作，擅长润色、起标题、写故事，风格灵活。"),
    )
}
