package com.guixing.jixunying.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 界面发给引擎的指令。本机直接调用；手机遥控电脑时经加密中转发给电脑。 */
@Serializable
sealed interface Command {
    @Serializable data class SaveProvider(val provider: ProviderConfig) : Command
    @Serializable data class DeleteProvider(val id: String) : Command
    /** 拉取服务商的模型列表（GET /models），结果写回该服务商。 */
    @Serializable data class FetchModels(val providerId: String) : Command
    /** 用一句话测试连通性。 */
    @Serializable data class TestProvider(val providerId: String, val modelId: String) : Command
    /** 试画一张（服务商留空 = 按自动规则选）。结果 data 是生成图片的 Attachment JSON。 */
    @Serializable data class TestImage(val providerId: String = "", val modelId: String = "", val prompt: String = "") : Command

    @Serializable data class SaveMember(val member: Member) : Command
    @Serializable data class DeleteMember(val id: String) : Command
    @Serializable data class SaveProfile(val profile: UserProfile) : Command
    @Serializable data class SaveSettings(val settings: Settings) : Command

    @Serializable data class CreateConversation(val memberIds: List<String>, val title: String = "") : Command
    @Serializable data class UpdateConversation(val conversation: Conversation) : Command
    @Serializable data class DeleteConversation(val id: String) : Command
    @Serializable data class LoadMessages(val convId: String) : Command

    @Serializable data class SendMessage(
        val convId: String,
        val text: String,
        val attachmentIds: List<String> = emptyList(),
        /** true 时不走聊天模型，直接把文字当画图提示词。 */
        val drawImage: Boolean = false,
    ) : Command
    @Serializable data class Stop(val convId: String) : Command
    @Serializable data class DeleteMessage(val convId: String, val messageId: String) : Command
    @Serializable data class Regenerate(val convId: String, val messageId: String) : Command

    /** 远程上传附件：内容 Base64。结果 data 是 Attachment 的 JSON。 */
    @Serializable data class UploadFile(val name: String, val mime: String, val base64: String) : Command
    /** 远程取文件：结果 data 是 Base64。 */
    @Serializable data class GetFile(val id: String) : Command
    /** 手机从电脑导入服务商（含 Key）、成员、个人资料、搜索和画图设置。结果 data 是 ConfigBundle 的 JSON。 */
    @Serializable data object ExportConfig : Command
    /** 手机把电脑导出的配置并入本机。 */
    @Serializable data class ImportConfig(val bundleJson: String) : Command

    /** 换一个新的配对二维码（旧的立即失效）。 */
    @Serializable data object NewPairingCode : Command
    @Serializable data class RemoveDevice(val id: String) : Command
}

@Serializable
data class CommandResult(val ok: Boolean = true, val message: String = "", val data: String = "")

/** 从电脑导到手机的配置包。 */
@Serializable
data class ConfigBundle(
    val providers: List<ProviderConfig>,
    val members: List<Member>,
    val profile: UserProfile,
    val search: SearchSettings,
    val imageGen: ImageGenSettings,
    val proxy: String,
)

/** 引擎推给界面的事件。 */
@Serializable
sealed interface Event {
    @Serializable data class State(val state: AppState) : Event
    @Serializable data class Messages(val convId: String, val messages: List<Message>) : Event
    @Serializable data class MessageUpsert(val message: Message) : Event
    /** 流式输出的增量，避免每个字都把整条消息推一遍。 */
    @Serializable data class MessageDelta(
        val convId: String,
        val messageId: String,
        val content: String = "",
        val reasoning: String = "",
    ) : Event
    @Serializable data class MessageRemoved(val convId: String, val messageId: String) : Event
    @Serializable data class ConversationRemoved(val convId: String) : Event
    @Serializable data class Notice(val text: String, val error: Boolean = false) : Event
}

/** 手机和电脑之间的一帧（加密前的明文）。 */
@Serializable
sealed interface WireFrame {
    @Serializable data class Req(val reqId: Long, val command: Command) : WireFrame
    @Serializable data class Res(val reqId: Long, val result: CommandResult) : WireFrame
    @Serializable data class Evt(val event: Event) : WireFrame
    /** 手机上线 / 心跳。电脑收到后把最新状态推给它。 */
    @Serializable data class Hello(val deviceName: String, val wantState: Boolean = true) : WireFrame
    /** 电脑告诉手机：你已被取消配对。 */
    @Serializable data object Revoked : WireFrame
}

/** 配对请求：手机扫码后，用二维码里的一次性密钥加密发给电脑。 */
@Serializable
data class PairRequest(val deviceId: String, val deviceName: String, val deviceKey: String, val ts: Long)

@Serializable
data class PairReply(val ok: Boolean, val hostName: String = "", val message: String = "")

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "@t"
}

const val USER_ID = "user"

/** 给手机看的 Key：只保留首尾，电脑收到原样的打码 Key 时保留原值。 */
fun maskKey(key: String): String =
    if (key.length <= 8) (if (key.isEmpty()) "" else "••••") else key.take(4) + "••••" + key.takeLast(4)

fun isMaskedKey(key: String) = key.contains("••••")
