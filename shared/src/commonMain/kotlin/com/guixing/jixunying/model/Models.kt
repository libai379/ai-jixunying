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
    /** 模型所在平台有官方内置搜索（Kimi、智谱、千问）就用内置的，其余模型调用下面选的搜索引擎。 */
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
)

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
) {
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun provider(id: String) = providers.firstOrNull { it.id == id }
    fun conversation(id: String) = conversations.firstOrNull { it.id == id }
}
