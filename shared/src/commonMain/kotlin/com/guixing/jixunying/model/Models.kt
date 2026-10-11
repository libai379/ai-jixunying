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
    /** 模型所在平台有官方内置搜索（智谱、千问）就用内置的；Kimi 的 web_search 走 Kimi 官方搜索接口；其余模型调用下面选的搜索引擎。 */
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
)

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
    /**
     * 发设置的这一端认得哪一版设置。旧版手机不认识这个字段，发来的就是 0；
     * 电脑看到 0 就保留旧版不认识的那几项（不然手机一改设置就把电脑上的冲回默认值）。
     */
    val schema: Int = 0,
) {
    companion object {
        /**
         * 1 = 1.4.0 加了裁判和 AI 核实；2 = 1.5.0 加了 office（Word / Excel）。
         * 以后再加旧版不认识的设置项，就加一，并在 SaveSettings 里保留。
         */
        const val SCHEMA = 2
    }
}

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
) {
    val isGroup: Boolean get() = memberIds.size > 1
}

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

/** AI 回答过程中用到的工具：联网搜索、读网页、画图。 */
@Serializable
data class ToolStep(
    val kind: String, // search / fetch / image
    val input: String,
    val sources: List<SearchSource> = emptyList(),
    val ok: Boolean = true,
    val note: String = "",
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
)

/** 和这台电脑配对过的手机。key 是两边共享的加密密钥（Base64），只存在电脑和那台手机上。 */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val key: String,
    val pairedAt: Long,
    val lastSeen: Long = 0,
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
    /** 本机文档索引的情况（界面显示用）。 */
    val docs: DocIndexInfo = DocIndexInfo(),
    /** 微信助理的情况（界面显示用；手机上没有，一直是空的）。 */
    val weixin: WeixinInfo = WeixinInfo(),
    /** 这台设备能不能接微信助理（电脑端能）。 */
    val weixinCapable: Boolean = false,
    /** 记录员后台活出的错（成功一次就清掉），见 Background.kt。 */
    val bgProblems: List<BgProblem> = emptyList(),
) {
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun provider(id: String) = providers.firstOrNull { it.id == id }
    fun conversation(id: String) = conversations.firstOrNull { it.id == id }
}
