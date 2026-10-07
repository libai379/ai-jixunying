package com.guixing.jixunying.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 界面发给引擎的指令。桌面端直接调用，手机端经局域网 WebSocket 发给桌面。 */
@Serializable
sealed interface Command {
    @Serializable data class SaveProvider(val provider: ProviderConfig) : Command
    @Serializable data class DeleteProvider(val id: String) : Command
    /** 拉取服务商的模型列表（GET /models），结果写回该服务商。 */
    @Serializable data class FetchModels(val providerId: String) : Command
    /** 用一句话测试连通性。 */
    @Serializable data class TestProvider(val providerId: String, val modelId: String) : Command

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

    @Serializable data object NewPairingCode : Command
    @Serializable data class RemoveDevice(val token: String) : Command
}

@Serializable
data class CommandResult(val ok: Boolean = true, val message: String = "", val data: String = "")

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

/** 局域网线路上的一帧。 */
@Serializable
sealed interface WireFrame {
    @Serializable data class Req(val reqId: Long, val command: Command) : WireFrame
    @Serializable data class Res(val reqId: Long, val result: CommandResult) : WireFrame
    @Serializable data class Evt(val event: Event) : WireFrame
}

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "@t"
}

const val DISCOVERY_PORT = 18766
const val DISCOVERY_PING = "AIJXY_DISCOVER_V1"
const val DISCOVERY_PONG = "AIJXY_HERE_V1"
const val USER_ID = "user"

/** 给手机端看的 Key：只保留首尾，桌面收到原样的打码 Key 时保留原值。 */
fun maskKey(key: String): String =
    if (key.length <= 8) (if (key.isEmpty()) "" else "••••") else key.take(4) + "••••" + key.takeLast(4)

fun isMaskedKey(key: String) = key.contains("••••")
