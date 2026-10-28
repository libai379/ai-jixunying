package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/** 一个模型服务商（按 OpenAI 兼容协议调用）。 */
@Serializable
data class ProviderConfig(
    val id: String,
    val presetId: String = "custom",
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<ModelInfo> = emptyList(),
    val useProxy: Boolean = false,
    val enabled: Boolean = true,
    /**
     * 手机上：这是电脑本机的模型（Ollama 这类，地址是电脑的 127.0.0.1），手机自己连不上，
     * 每次调用经加密线路交给配对的电脑去问（HostModels，2026-10-24）。电脑开着、手机连上电脑才能用。
     */
    val viaHost: Boolean = false,
    /** 最后改的时间（两端同步谁新听谁的，2026-10-27）；0 = 以前建的、没记。 */
    val updatedAt: Long = 0,
)

@Serializable
data class ModelInfo(
    val id: String,
    val vision: Boolean = false,
    val tools: Boolean = true,
    val imageGen: Boolean = false,
)

/** 群里的一个 AI 成员。 */
@Serializable
data class Member(
    val id: String,
    val name: String,
    val avatar: String = "🤖",
    val color: Long = 0xFF5B6CFF,
    val providerId: String = "",
    val modelId: String = "",
    /** 人设 / 擅长什么，会写进它自己的系统提示，也会告诉群里其他成员。 */
    val bio: String = "",
    /** 留空表示不传，用模型默认值（有的模型只接受固定 temperature）。 */
    val temperature: Double? = null,
    /** 快速（关掉思考）/ 深度（想透再答）/ 默认（不传参数，按模型自己的习惯）。各家怎么切换见 Thinking.kt。 */
    val thinking: ThinkingMode = ThinkingMode.AUTO,
    /** 最后改的时间（两端同步谁新听谁的，2026-10-27）。 */
    val updatedAt: Long = 0,
)

/** 用户自己（主持人）。 */
@Serializable
data class UserProfile(
    val name: String = "我",
    val avatar: String = "🙂",
    val about: String = "",
)

@Serializable
enum class SearchEngine { BING_FREE, BOCHA, TAVILY, ZHIPU, BRAVE }

@Serializable
enum class SearchMode {
    /**
     * 模型所在平台有官方内置搜索（智谱、千问）就用内置的；Kimi、DeepSeek 的 web_search 走自家官方搜索接口；
     * 其余模型调用下面选的搜索引擎，选的是免费必应时借用户已有的 DeepSeek / Kimi 官方搜索（2026-10-28）。
     */
    AUTO,
    /** 所有模型都用下面选的搜索引擎（出处显示最统一）。 */
    ENGINE_ONLY,
}

@Serializable
data class SearchSettings(
    val mode: SearchMode = SearchMode.AUTO,
    val engine: SearchEngine = SearchEngine.BING_FREE,
    val apiKeys: Map<String, String> = emptyMap(),
    val useProxy: Boolean = false,
    val maxResults: Int = 6,
)

@Serializable
data class ImageGenSettings(
    val providerId: String = "",
    val modelId: String = "",
    val size: String = "1024x1024",
)

/**
 * 做视频（2026-10-20，用户充了千问之后）：千问AI平台 / 阿里百炼的视频生成（异步任务，按秒收费）。
 * 默认最便宜能用的一档：wan3.0-video、480P、5 秒。模型、档位出处见 docs/参考资料.md「视频生成」。
 */
@Serializable
data class VideoGenSettings(
    /** 关了 AI 就看不到做视频的工具。 */
    val enabled: Boolean = true,
    /** 空 = 自动（千问AI平台优先，其次阿里百炼）。 */
    val providerId: String = "",
    val modelId: String = VideoPick.DEFAULT_MODEL,
    val resolution: String = "480P",
    val duration: Int = 5,
    val ratio: String = "16:9",
    /** 有声（wan3.0 能关；别的模型按它自己的默认）。 */
    val audio: Boolean = true,
)

/**
 * 联机：手机和电脑隔着半个地球也能连。两边都主动连到公共中转服务器（MQTT），
 * 内容用配对时交换的密钥端到端加密，中转服务器只看得到乱码。不需要自己的服务器。
 */
@Serializable
data class RelaySettings(
    val enabled: Boolean = true,
    /** 按顺序同时连接，任何一个通都能用。 */
    val brokers: List<String> = DEFAULT_BROKERS,
    val deviceName: String = "",
) {
    companion object {
        /** EMQX 和 HiveMQ 两家公司的免费公共服务器（2026-10-07 实测可用），同时连。 */
        val DEFAULT_BROKERS = listOf("ssl://broker-cn.emqx.io:8883", "ssl://broker.emqx.io:8883", "ssl://broker.hivemq.com:8883")
    }
}

/**
 * 记忆。三层（参考 Claude Code / MiMo Code / WorkBuddy，见 docs/构想.md 第四节）：
 * 1. 对话太长时，记录员把前面的聊天压缩成摘要，群里大家共用一份；
 * 2. 关于用户的长期记忆（身份、偏好、要求），所有对话、所有成员都能用，可以看、可以改；
 * 3. AI 能搜以前的聊天记录。
 */
@Serializable
data class MemorySettings(
    /** 长期记忆写进每位成员的设定，并允许 AI 用 remember 工具记东西。 */
    val enabled: Boolean = true,
    /** 聊完后记录员自动挑出值得长期记住的要点。 */
    val autoExtract: Boolean = true,
    /** 记录员用哪个模型；留空自动挑便宜的（mimo-v2.6-flash 优先）。 */
    val recorderProviderId: String = "",
    val recorderModelId: String = "",
    /** 一个对话没压缩的部分超过多少字就压缩。 */
    val compressAt: Int = 24_000,
    /** 立场档案：群聊每轮答完，记录员记下各位成员的立场和改口（见 Stances.kt）。 */
    val stances: Boolean = true,
    /** 立场档案的裁判用哪个模型；留空自动挑（deepseek-flash 优先）。 */
    val judgeProviderId: String = "",
    val judgeModelId: String = "",
    /** 裁判看到的回答隐去成员名字（换成甲乙丙丁），防止看名字偏袒。 */
    val judgeAnonymous: Boolean = true,
    /** AI 核实：开新议题后，裁判单独判一次谁对（能查就用 web_search）。 */
    val aiVerify: Boolean = true,
    /** 统计「准确率」时，用户没标的题按 AI 核实的结论算（用户标的永远优先）。 */
    val countAiVerdict: Boolean = true,
)

@Serializable
data class MemoryItem(
    val id: String,
    val text: String,
    /** 关于我 / 偏好 / 要求 / 事实 */
    val kind: String = "关于我",
    /** 从哪来：对话标题、「手动添加」。 */
    val source: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val pinned: Boolean = false,
    /**
     * 话题记忆（记忆二期，2026-10-26 用户定）：空 = 关于用户本人，所有对话都带；
     * 有名字（例如「AI集训营」「星阙」）= 只在属于这个话题的对话里带上，别的对话里 AI 用 recall_memory 查。
     */
    val topic: String = "",
    /** 出处：从哪个对话的哪条消息记下来的（点开能跳回去）；手动添加的为空。 */
    val convId: String = "",
    val messageId: String = "",
    /** 那条消息里的原话（记录员抄的，程序核对过确实在原文里）。 */
    val quote: String = "",
)

@Serializable
data class MemoryTombstone(val id: String, val deletedAt: Long)

/** 记忆同步时两边交换的东西（Command.SyncMemories 的参数和结果）。 */
@Serializable
data class MemoryBundle(val items: List<MemoryItem> = emptyList(), val deleted: List<MemoryTombstone> = emptyList())

/** 本机文档（AI 能搜、能读）。 */
@Serializable
data class DocSettings(
    val enabled: Boolean = true,
    /** 要收录的文件夹；空 = 默认（电脑：文档、桌面、下载；手机：整个存储）。 */
    val folders: List<String> = emptyList(),
    /** 电脑上：把微信收到的文件（Documents\WeChat Files、xwechat_files 里的 file 文件夹）也收进来。 */
    val includeWeixin: Boolean = false,
)

/** 文档索引的情况（界面显示用）。 */
@Serializable
data class DocIndexInfo(
    val count: Int = 0,
    val scanning: Boolean = false,
    /** 正在扫的文件夹 / 进度说明。 */
    val progress: String = "",
    val scannedAt: Long = 0,
    /** 实际收录的文件夹。 */
    val roots: List<String> = emptyList(),
    /** 这台电脑上找到的微信文件夹（不管开没开）。 */
    val weixinRoots: List<String> = emptyList(),
    /** 安卓：还没给「所有文件访问」权限。 */
    val needPermission: Boolean = false,
)

/** 文档搜索的一条结果。 */
@Serializable
data class DocHit(
    val path: String,
    val name: String,
    val folder: String,
    val size: Long,
    val mtime: Long,
    /** 抽出的文字有多少字；0 = 读不出文字（只能按文件名找）。 */
    val chars: Int,
    val snippet: String = "",
    val note: String = "",
)

/**
 * 微信助理（腾讯官方 ClawBot / iLink 协议，和 WorkBuddy 的「微信助理」一样）：
 * 电脑上扫码绑定后，用户在微信里给它发消息，电脑上的 AI 成员回答，回复发回微信。
 */
@Serializable
data class WeixinSettings(
    val enabled: Boolean = true,
    /** 谁来回答微信消息（一位 = 单聊；多位 = 群聊，每人的回答各发一条）。空 = 第一位成员。 */
    val memberIds: List<String> = emptyList(),
    val webSearch: Boolean = true,
)

/** 微信助理的状态（界面显示用，不含凭证）。 */
@Serializable
data class WeixinInfo(
    val bound: Boolean = false,
    /** 给人看的状态：未绑定 / 等待扫码 / 已扫码，请在手机上确认 / 已连接 / 会话过期，请重新绑定…… */
    val status: String = "",
    /** 绑定用的链接：编成二维码给手机微信扫，或者发到微信里点开。 */
    val loginLink: String = "",
    /** 要在手机上输入的数字（微信提示「输入手机微信显示的数字」时）。 */
    val needVerifyCode: Boolean = false,
    val boundAt: Long = 0,
    val lastMessageAt: Long = 0,
    /** 这台设备正在收微信消息（绑定了、开关开着、没在待命、连得上）。手机看电脑的这一项决定自己接不接。 */
    val answering: Boolean = false,
    /** 绑定的助理编号（ilink_bot_id，不是凭证）：手机拿它和电脑的比，看是不是同一个绑定。 */
    val botId: String = "",
    /** 这台手机用的是从电脑同步来的绑定。 */
    val shared: Boolean = false,
)

@Serializable
data class Settings(
    val search: SearchSettings = SearchSettings(),
    val imageGen: ImageGenSettings = ImageGenSettings(),
    val videoGen: VideoGenSettings = VideoGenSettings(),
    val proxy: String = "127.0.0.1:10809",
    val relay: RelaySettings = RelaySettings(),
    val darkMode: Int = 0, // 0 跟随系统 1 浅色 2 深色
    /** 一次用户发言最多引发几轮 AI 之间的 @ 接力，防止无限互聊。 */
    val maxMentionChain: Int = 3,
    val memory: MemorySettings = MemorySettings(),
    val docs: DocSettings = DocSettings(),
    val weixin: WeixinSettings = WeixinSettings(),
    val costs: CostSettings = CostSettings(),
    /** AI 做 Word / Excel 文件（1.5.0）。 */
    val office: OfficeSettings = OfficeSettings(),
    /** Code 栏（1.6.0）：命令外壳、超时、最大步数、允许手机用（见 model/Code.kt）。 */
    val code: CodeSettings = CodeSettings(),
    /** 语音（1.7.0）：读出来、听写用哪一家、什么音色（见 model/Speech.kt）。 */
    val speech: SpeechSettings = SpeechSettings(),
    /** 按意思搜（1.7.0）：聊天记录、我的文档要不要算向量，用哪一家的向量模型（见 model/Semantic.kt）。 */
    val semantic: SemanticSettings = SemanticSettings(),
    /** 个性化（2026-10-17，学 WorkBuddy 的 设置 → 个性化 → 自定义指令）：用户自己写的、每次都要照着做的指令。 */
    val personal: PersonalSettings = PersonalSettings(),
    /** 液态玻璃界面（2026-10-22，docs/界面换装说明.md）：关掉就是原来的纯色界面。 */
    val glass: Boolean = true,
    /** 液态玻璃调参（2026-10-24，model/GlassTuning.kt）：每个位置的参数和用户存的预设。 */
    val glassTuning: GlassTuning = GlassTuning(),
    /** 界面背景（2026-10-24，model/GlassTuning.kt 的 AppBackground）：默认 / 纯色 / 渐变 / 自己的图片。 */
    val background: AppBackground = AppBackground(),
    /**
     * 发设置的这一端认得哪一版设置。旧版手机不认识这个字段，发来的就是 0；
     * 电脑看到 0 就保留旧版不认识的那几项（不然手机一改设置就把电脑上的冲回默认值）。
     */
    val schema: Int = 0,
) {
    companion object {
        /**
         * 1 = 1.4.0 加了裁判和 AI 核实；2 = 1.5.0 加了 office（Word / Excel）；3 = 1.6.0 加了 code（Code 栏）；
         * 4 = 1.7.0 加了 speech（语音）；5 = 1.7.0 加了 semantic（按意思搜）；6 = 2026-10-17 加了 personal（自定义指令）；
         * 7 = 2026-10-22 加了 glass（液态玻璃界面）；8 = 2026-10-24 加了 glassTuning（液态玻璃调参）；9 = 同一天加了 background（界面背景）。
         * 以后再加旧版不认识的设置项，就加一，并在 SaveSettings 里保留。
         */
        const val SCHEMA = 9
    }
}

/**
 * 设置 → 个性化 → 自定义指令（2026-10-17，用户：「也可以学 WorkBuddy，在个性化里面自定义指令」）。
 * WorkBuddy 的做法（腾讯云开发者社区教程，官方文档没写这一页，未确认）：设置中心 → 个性化 → 自定义指令，写下的规则每次对话、每个任务都生效。
 * 我们分两格：instructions 聊天和 Code 栏都用；codeInstructions 只给 Code 栏（相当于 Claude Code 的个人 CLAUDE.md，管所有项目）。
 */
@Serializable
data class PersonalSettings(
    val instructions: String = "",
    val codeInstructions: String = "",
    /** 自己写的斜杠命令（2026-10-18，开发补齐第 12 样）：输入框打「/名字」展开成这段话，里面的 $ARGUMENTS 换成命令后面跟的字。两栏都能用。 */
    val commands: List<SlashCommand> = emptyList(),
)

/** 一个斜杠命令：名字（不带 /）、一句说明、展开成的话。 */
@Serializable
data class SlashCommand(val name: String, val description: String = "", val text: String = "")

/**
 * 设置 → Word / Excel：AI 做文件时怎么排版（用户 10-08：「做精细，人性化智能化，后台多做开关我可以主动调试」）。
 * AI 这次另外指定的（比如「用公文格式」「横向」）优先。
 */
@Serializable
data class OfficeSettings(
    /** 成员能做 Word / Excel 文件（多两个工具）。 */
    val enabled: Boolean = true,
    /** Word 样式：general 通用（微软雅黑）/ formal 正式（宋体正文、黑体标题、首行缩进）/ official 公文（仿宋三号、固定行距 28 磅）。 */
    val wordStyle: String = "general",
    /** 正文首行缩进两个字（选样式时跟着样式变，也能单独改）。 */
    val firstLineIndent: Boolean = false,
    /** 页脚加页码。 */
    val pageNumbers: Boolean = true,
    /** 页眉写文档标题。 */
    val headerTitle: Boolean = false,
    /** 标题多（4 个以上）时开头自动加目录。 */
    val autoToc: Boolean = true,
    /** Excel 表头加粗、加底色。 */
    val excelHeaderStyle: Boolean = true,
    /** Excel 冻结表头（往下翻表头不动）。 */
    val excelFreeze: Boolean = true,
    /** Excel 表头加筛选按钮。 */
    val excelFilter: Boolean = true,
    /** Excel 隔行浅色底纹。 */
    val excelZebra: Boolean = false,
    /** 大于一千的数字加千分位逗号。 */
    val excelThousands: Boolean = true,
    /** 文件名后面加日期（周报-20261010.docx）。 */
    val dateInName: Boolean = false,
    /** 电脑上：做好后另存一份到文件夹。 */
    val autoSave: Boolean = true,
    /** 另存到哪个文件夹（空 = 文档\AI集训营）。 */
    val saveFolder: String = "",
    /** 每条回答下面显示「存成 Word」，有表格的再显示「表格存成 Excel」。 */
    val exportButtons: Boolean = true,
    /** PPT 配色：blue 商务蓝 / green 清新绿 / orange 活力橙 / dark 科技深色 / mono 简约黑白。 */
    val pptTheme: String = "blue",
    /** PPT 宽屏 16:9（关掉是 4:3）。 */
    val pptWide: Boolean = true,
    /** 每页右下角页码。 */
    val pptPageNumbers: Boolean = true,
    /** 内容页 4 页以上时，封面后面自动加一页目录。 */
    val pptAgenda: Boolean = true,
    /** 讲稿（AI 写的「备注：」）放进演讲者备注。 */
    val pptNotes: Boolean = true,
    /** 一页字太多时自动拆成两页（第二页标题加「（续）」）；关掉就缩小字号硬塞。 */
    val pptSplit: Boolean = true,
)

@Serializable
enum class ReplyMode {
    /** 每个成员各自独立回答，互相看不到本轮其他人的答案（防附和）。 */
    INDEPENDENT,
    /** 依次回答，后面的人能看到前面的回答，可以补充或反驳。 */
    RELAY,
    /** 只有被 @ 的成员回答，没人被 @ 时由第一位成员回答。 */
    MENTION_ONLY,
}

@Serializable
data class Conversation(
    val id: String,
    val title: String = "新对话",
    val memberIds: List<String> = emptyList(),
    val replyMode: ReplyMode = ReplyMode.INDEPENDENT,
    val webSearch: Boolean = true,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val pinned: Boolean = false,
    /** 前面有多少条消息已经压缩成摘要（界面上提示用）。 */
    val summarized: Int = 0,
    /** 来自哪里：空 = 本应用里聊的；"weixin:<微信用户编号>" = 微信助理的对话。 */
    val channel: String = "",
    /** 这个对话记了几个议题（立场档案），界面据此显示入口。 */
    val stanceTopics: Int = 0,
    /** 立场档案最近一次变化的时间，界面看到它变了就重新取。 */
    val stanceAt: Long = 0,
    /** 归档（2026-10-24）：不在侧栏的时间分组里列，放到最底下「已归档」里；搜索照样能搜到。 */
    val archived: Boolean = false,
    /**
     * 会审模式（1.5.0，构想.md 第二节）：对大家提的问题，各自独立回答 → 匿名互评 → 主席汇总（写出分歧和少数派）。
     * 不做成 ReplyMode 的新值：旧版手机解不开新的枚举值，整个状态都会读坏；新加的字段旧版直接忽略。
     */
    val council: Boolean = false,
    /**
     * Code 栏会话（2026-10-12，docs/Code栏设计说明.md 第 11 节）：不为空就是 Code 会话，复用「对话 + 消息」的存储和推送。
     * 放在最后加：旧版手机不认识这个字段直接忽略（AppJson.ignoreUnknownKeys），不会读坏整个状态。
     */
    val code: CodeSpec? = null,
    /**
     * 对话页 AI 不用再问就能读的文件 / 文件夹（2026-10-17 用户定「和 Code 一样放开」）：
     * 拖进这个对话的、批准时选了「以后这个文件夹不再问」的。「我的文档」收录的本来就不用问。
     */
    val grantedPaths: List<String> = emptyList(),
    /**
     * 对话页改文件「全部放开」（2026-10-17，用户：「比 Claude 更开放」）：AI 新建、修改文件都不问；改之前照样备份、能撤销。
     * 关着时：拖进来的 / 选了「不再问」的文件夹里直接改，别处的先问。只能在电脑上打开（PhoneGate）。
     */
    val fileOpen: Boolean = false,
    /** 对话页「自动」（2026-10-17）：动手（改别处的文件、删、跑命令）先让检查员看，拦下的再问用户。和 fileOpen 二选一。 */
    val fileAuto: Boolean = false,
    /** 这个对话 / 会话开着的后台命令（2026-10-18，npm run dev 这种）。程序重开时清空（进程已经没了）。 */
    val background: List<BackgroundProc> = emptyList(),
    /** 这个对话属于哪个记忆话题（记忆二期）：记录员自动认；用户在对话设置里改过就以用户的为准（memoryTopicByUser）。 */
    val memoryTopic: String = "",
    val memoryTopicByUser: Boolean = false,
    /** 对话本身（标题、成员、置顶、归档……）最后改的时间（两端同步用，2026-10-27）；updatedAt 是最后一条消息的时间。 */
    val editedAt: Long = 0,
) {
    val isGroup: Boolean get() = memberIds.size > 1

    /** Code 栏会话（侧栏「Code」那一边列的）。 */
    val isCode: Boolean get() = code != null
}

/** 一个后台命令（2026-10-18）：编号、命令、在哪儿跑、还在不在跑、输出里出现的网址（开发服务器的地址，预览用）。 */
@Serializable
data class BackgroundProc(
    val id: String,
    val command: String,
    val cwd: String,
    val startedAt: Long,
    val running: Boolean = true,
    val url: String = "",
)

@Serializable
enum class Role { USER, AI, SYSTEM }

@Serializable
enum class MsgStatus { PENDING, STREAMING, DONE, ERROR, STOPPED }

@Serializable
enum class AttachmentKind { IMAGE, DOCUMENT, GENERATED_IMAGE }

@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: AttachmentKind,
    /** 文档抽出来的文字有多少字（0 表示没抽出来）。 */
    val textChars: Int = 0,
    val note: String = "",
    /** AI 做的文件（Word / Excel）：在回答下面显示成能打开的文件，微信里也发回去。 */
    val generated: Boolean = false,
    /** 做文件的那台设备上另存的副本（带真名字，点开就用 Word / WPS 打开）；别的设备用不了这个路径。 */
    val savedPath: String = "",
)

/** 导出的文件（「存成 Word」）：文件名 + base64 内容，界面拿去另存。 */
@Serializable
data class ExportedFile(val name: String, val base64: String)

@Serializable
data class SearchSource(val title: String, val url: String, val snippet: String = "")

/** AI 回答过程中用到的工具：联网搜索、读网页、画图，以及 Code 栏读文件、改文件、跑命令。 */
@Serializable
data class ToolStep(
    /** search / fetch / image / file / memory / history / doc，以及 Code 栏的 read / list / grep / write / edit / run。 */
    val kind: String,
    val input: String,
    val sources: List<SearchSource> = emptyList(),
    val ok: Boolean = true,
    val note: String = "",
    /**
     * 下面四个是 Code 栏用的（2026-10-12，docs/Code栏设计说明.md 第 11 节）；加在最后，聊天里的工具步骤不受影响。
     */
    /** 这一步的唯一编号（批准 / 拒绝、看差异都按它找）。 */
    val id: String = "",
    /** 工具的输出（截断后的，长的话里面写明被截断了）。 */
    val output: String = "",
    /** "" 不用批准 / "waiting" 等用户 / "allowed" 允许了 / "denied" 拒绝了。 */
    val approval: String = "",
    /** 这一步改了文件才有；没改文件是 null。 */
    val change: FileChange? = null,
    /**
     * 下面两个是 2026-10-17 加的（等批准的那一步，界面据此多给一个选项）：
     * scopeDir = 这一步碰的是工作区外面，允许时可以顺便把这个文件夹加进工作区；
     * prefix = 这条命令能记成「同样开头这个会话里不再问」，记的就是这个开头（拼起来的、危险的命令是空的）。
     */
    val scopeDir: String = "",
    val prefix: String = "",
    /**
     * 2026-10-22：AI 看过的那张图存的一份缩小副本（附件编号，和聊天附件同一个文件夹）。试用时 Claude Code 截了四张图说「上面四张截图」，
     * 界面上读图那一步却是空的；原图常在临时目录里会被清掉，所以存一份。空 = 不是图。
     */
    val image: String = "",
    /**
     * 2026-10-23：这一步开始时 AI 已经说了多少字（正文去掉开头空白后的长度），界面按它把边做边说的话插回步骤之间。
     * -1 = 旧消息没记，话都排在步骤后面（以前的样子）。
     */
    val textAt: Int = -1,
)

@Serializable
data class Usage(val prompt: Int = 0, val completion: Int = 0, val cached: Int = 0, val millis: Long = 0)

@Serializable
data class Message(
    val id: String,
    val convId: String,
    val role: Role,
    /** 成员 id；用户发言是 "user"。 */
    val senderId: String,
    val content: String = "",
    val reasoning: String = "",
    val attachments: List<Attachment> = emptyList(),
    val tools: List<ToolStep> = emptyList(),
    val status: MsgStatus = MsgStatus.DONE,
    val error: String = "",
    val createdAt: Long = 0,
    /** 发言时用的模型，留档（成员以后换模型也能看出当时是谁答的）。 */
    val modelLabel: String = "",
    val usage: Usage? = null,
    /** 会审模式里这是哪一步："" 普通回答，"review" 匿名互评，"chair" 主席汇总。 */
    val stage: String = "",
    /** 从别的设备同步来的（那台的名字），空 = 本机的；同步时只发本机的（2026-10-27，engine/DeviceSync）。 */
    val syncedFrom: String = "",
)

/** 和这台电脑配对过的手机。key 是两边共享的加密密钥（Base64），只存在电脑和那台手机上。 */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val key: String,
    val pairedAt: Long,
    val lastSeen: Long = 0,
    /** 这台手机最近一次和电脑同步完的时间、结果（2026-10-27）。 */
    val lastSyncAt: Long = 0,
    val lastSyncNote: String = "",
)

/** 除聊天记录以外的全部状态，体积小，变化时整体推送。 */
@Serializable
data class AppState(
    val providers: List<ProviderConfig> = emptyList(),
    val members: List<Member> = emptyList(),
    val profile: UserProfile = UserProfile(),
    val settings: Settings = Settings(),
    val conversations: List<Conversation> = emptyList(),
    val devices: List<PairedDevice> = emptyList(),
    /** 这台电脑在中转上的编号（随机，首次启动生成）。 */
    val hostId: String = "",
    /** 当前一次性配对密钥（Base64）；配对成功一次就换新的。只在电脑上有意义，不发给手机。 */
    val pairingSecret: String = "",
    /** 中转服务器连接情况，给界面显示用，不存盘也行。 */
    val relayStatus: String = "",
    /** 关于用户的长期记忆。 */
    val memories: List<MemoryItem> = emptyList(),
    /** 删掉的记忆（编号 + 删的时间），手机和电脑互相同步时让删除也跟过去（engine/MemorySync.kt）；留 90 天。 */
    val memoryDeleted: List<MemoryTombstone> = emptyList(),
    /** 本机文档索引的情况（界面显示用）。 */
    val docs: DocIndexInfo = DocIndexInfo(),
    /** 微信助理的情况（界面显示用；手机上没有，一直是空的）。 */
    val weixin: WeixinInfo = WeixinInfo(),
    /** 这台设备能不能接微信助理（电脑端能）。 */
    val weixinCapable: Boolean = false,
    /**
     * 电脑关机时由哪台手机接微信（配对设备的编号；空 = 没定，谁拿了绑定谁接，和以前一样）。2026-10-24 一台电脑连多台手机：
     * 不定的话电脑一关，几台开着微信助理的手机会一起回。默认是最后一台从电脑拿微信绑定的；电脑 设置 → 微信 里能换。"none" = 都不接。
     */
    val weixinBackup: String = "",
    /** 电脑上点「让手机现在同步」的时间（2026-10-27）：手机看到它变大就同步一遍。 */
    val syncRequestAt: Long = 0,
    /** 删掉的服务商、成员（编号 + 删的时间），两端同步时另一边跟着删，留 90 天（2026-10-27）。 */
    val configDeleted: List<MemoryTombstone> = emptyList(),
    /** 删掉的对话，同上。 */
    val convDeleted: List<MemoryTombstone> = emptyList(),
    /** 记录员后台活出的错（成功一次就清掉），见 Background.kt。 */
    val bgProblems: List<BgProblem> = emptyList(),
    /** 按意思搜的补算进度（聊天记录、我的文档各一份），见 model/Semantic.kt。 */
    val semanticProgress: SemanticProgress = SemanticProgress(),
    /** 这台设备上「用 ChatGPT 登录」的情况（2026-10-23）。凭据本身不在这里（只存在本机 chatgpt/auth.json）。 */
    val chatgpt: ChatGptInfo = ChatGptInfo(),
    /** 这台设备的账号（2026-10-24）：本机密钥算出来的编号 + 绑的 GitHub。私钥不在这里（只在 account/identity.json）。 */
    val account: AccountInfo = AccountInfo(),
) {
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun provider(id: String) = providers.firstOrNull { it.id == id }
    fun conversation(id: String) = conversations.firstOrNull { it.id == id }
}

/**
 * 「用 ChatGPT 登录」的状态（2026-10-23，docs/ChatGPT订阅设计说明.md）。
 * signingIn：正在等用户在浏览器里点同意；planOk：给了「用订阅额度」的权限（chatgpt.tokens.use.direct）。
 */
@Serializable
data class ChatGptInfo(
    val signedIn: Boolean = false,
    val email: String = "",
    val planOk: Boolean = false,
    val signingIn: Boolean = false,
    val error: String = "",
)

/**
 * 账号（2026-10-24，docs/账号设计说明.md）：id 是本机密钥算出来的编号（jxy-xxxx-…）；github* 是绑的 GitHub。
 * githubSigningIn：正在等用户在 GitHub 页面上点同意；githubError：上一次登录的错。
 */
@Serializable
data class AccountInfo(
    val id: String = "",
    val createdAt: Long = 0,
    val githubLogin: String = "",
    val githubName: String = "",
    val githubAvatar: String = "",
    val githubSigningIn: Boolean = false,
    val githubError: String = "",
)
