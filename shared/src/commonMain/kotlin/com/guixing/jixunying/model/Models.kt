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
data class SearchSettings(
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

@Serializable
data class ServerSettings(
    val enabled: Boolean = true,
    val port: Int = 18765,
    val deviceName: String = "我的电脑",
)

@Serializable
data class Settings(
    val search: SearchSettings = SearchSettings(),
    val imageGen: ImageGenSettings = ImageGenSettings(),
    val proxy: String = "127.0.0.1:10809",
    val server: ServerSettings = ServerSettings(),
    val darkMode: Int = 0, // 0 跟随系统 1 浅色 2 深色
    /** 一次用户发言最多引发几轮 AI 之间的 @ 接力，防止无限互聊。 */
    val maxMentionChain: Int = 3,
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

@Serializable
data class PairedDevice(val name: String, val token: String, val pairedAt: Long, val lastSeen: Long = 0)

/** 除聊天记录以外的全部状态，体积小，变化时整体推送。 */
@Serializable
data class AppState(
    val providers: List<ProviderConfig> = emptyList(),
    val members: List<Member> = emptyList(),
    val profile: UserProfile = UserProfile(),
    val settings: Settings = Settings(),
    val conversations: List<Conversation> = emptyList(),
    val devices: List<PairedDevice> = emptyList(),
    /** 当前配对码（只在桌面端有意义）。 */
    val pairingCode: String = "",
    val serverAddresses: List<String> = emptyList(),
) {
    fun member(id: String) = members.firstOrNull { it.id == id }
    fun provider(id: String) = providers.firstOrNull { it.id == id }
    fun conversation(id: String) = conversations.firstOrNull { it.id == id }
}
