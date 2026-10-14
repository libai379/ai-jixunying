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

    /**
     * Code 栏：新建一个 Code 会话（docs/Code栏设计说明.md 第 1、11 节，2026-10-12）。
     * 结果 data 是新对话的 id；文件夹必须存在，服务商和模型必须已经加过，模型还要支持调用工具。
     */
    @Serializable data class CodeCreate(
        val folder: String,
        val providerId: String,
        val modelId: String,
        val mode: CodeMode = CodeMode.MANUAL,
        /** 手机发来的（电脑收到手机的指令时由 PhoneGate 填 true，手机自己填的不算）。 */
        val fromPhone: Boolean = false,
    ) : Command
    /** Code 栏：换权限档（只影响之后的步骤，已经跑完的不变）。fromPhone 同上。 */
    @Serializable data class CodeSetMode(val convId: String, val mode: CodeMode, val fromPhone: Boolean = false) : Command
    /** Code 栏：发一条任务，开始跑编码循环（同一个会话已经在跑就排队，前一个跑完接着跑）。 */
    @Serializable data class CodeSend(val convId: String, val text: String) : Command
    /** Code 栏：停下这个会话正在跑的循环（模型请求和正在跑的命令都停），消息状态变 STOPPED。 */
    @Serializable data class CodeStop(val convId: String) : Command
    /** Code 栏：批准或拒绝一步（写文件 / 改文件 / 跑命令）。针对具体某一步，没有超时自动放行。 */
    @Serializable data class CodeDecide(val convId: String, val stepId: String, val allow: Boolean) : Command
    /** Code 栏：某一处改动的统一差异（unified diff）。结果 data 是差异文本。 */
    @Serializable data class CodeDiff(val convId: String, val changeId: String) : Command
    /** Code 栏：接受或拒绝一处改动；拒绝 = 把文件恢复成改之前的样子（原来没有的文件就删掉）。 */
    @Serializable data class CodeReview(val convId: String, val changeId: String, val accept: Boolean) : Command
    /** Code 栏：撤销这一条回答里的全部改动（从后往前还原）。 */
    @Serializable data class CodeRevertTask(val convId: String, val messageId: String) : Command
    /**
     * Code 栏：改前的备份一共占了多少字节（<数据目录>/code/ 底下所有文件加起来）。结果 data 是字节数。
     * 只读，手机发来也放行；手机遥控电脑时拿到的是电脑上的数（设置 → Code 栏 显示）。
     */
    @Serializable data object CodeBackupSize : Command

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

    /** 长期记忆：新增或修改一条。 */
    @Serializable data class SaveMemory(val item: MemoryItem) : Command
    @Serializable data class DeleteMemory(val id: String) : Command
    /** 让记录员合并重复、矛盾的记忆。 */
    @Serializable data object TidyMemories : Command
    /** 花费：period = today / week / month / all。结果 data 是 CostReport 的 JSON。 */
    @Serializable data class GetCosts(val period: String = "month") : Command
    /** 花费设置（汇率、自填单价）单独保存：旧版手机发来的 SaveSettings 里没有这块，不能让它冲掉。 */
    @Serializable data class SaveCosts(val costs: CostSettings) : Command
    /** 现在就联网取一次最新的美元汇率（不管上次是什么时候取的），并把汇率改回自动更新。 */
    @Serializable data object RefreshUsdRate : Command
    /** 查各家余额（能查的直接查，查不了的给控制台链接）。结果 data 是 List<BalanceInfo> 的 JSON。 */
    @Serializable data object GetBalances : Command
    /** 手动填 / 改 / 清除某个服务商的余额（balance = null 是清除）。单独一条指令：旧版手机发的 SaveCosts 不认识这块，不能让它冲掉。 */
    @Serializable data class SaveManualBalance(val providerId: String, val balance: ManualBalance?) : Command
    /** 后台出错的提示「知道了」：先不显示（再出错还会提示）。 */
    @Serializable data class DismissBgProblem(val job: BgJob) : Command
    /** 搜所有对话的聊天内容。结果 data 是 List<HistoryHit> 的 JSON。 */
    @Serializable data class SearchHistory(val query: String, val limit: Int = 30) : Command

    /** 搜本机文档；关键词留空 = 最近修改的。结果 data 是 List<DocHit> 的 JSON。 */
    @Serializable data class DocSearch(val query: String, val limit: Int = 50) : Command
    /** 马上重新扫一遍文档。 */
    @Serializable data object DocRescan : Command
    /** 把本机的一个文档变成附件（「问 AI」用）。结果 data 是 Attachment 的 JSON。 */
    @Serializable data class DocAttach(val path: String) : Command

    /** 微信助理：开始扫码绑定（结果 data 是绑定链接）。 */
    @Serializable data object WeixinLogin : Command
    /** 微信助理：手机上提示输入数字时，把数字交给电脑。 */
    @Serializable data class WeixinVerify(val code: String) : Command
    /** 微信助理：解除绑定。 */
    @Serializable data object WeixinLogout : Command
    /**
     * 微信助理：手机经加密线路向电脑要绑定（一个微信只能绑一台设备，手机再扫码会把电脑的顶掉，所以两边用同一个绑定）。
     * 结果 data 是绑定（含凭证）的 JSON，只给已配对的手机；电脑关机时手机拿它接着回答。
     */
    @Serializable data object WeixinShare : Command
    /** 微信助理：手机把 WeixinShare 拿到的绑定存到本机。 */
    @Serializable data class WeixinImport(val shareJson: String) : Command
    /** 微信助理：手机告诉电脑，微信消息它答到了这个时间（消息的 create_time_ms），电脑别再答一遍。 */
    @Serializable data class WeixinReport(val answeredUntil: Long) : Command

    /** 把一条回答存成 Word（format = "docx"）或把里面的表格存成 Excel（"xlsx"）。结果 data 是 ExportedFile 的 JSON。 */
    @Serializable data class ExportMessage(val convId: String, val messageId: String, val format: String) : Command

    /**
     * 语音：把一段文字读出来（docs/向量和语音设计说明.md，2026-10-12）。
     * 长文字由引擎先清理 Markdown、按所选模型的上限切段，再一段一次请求；结果 data 是 SpeechAudio 的 JSON（一段或多段音频）。
     * 三家都是本地引擎自己跑（手机遥控电脑时用手机自己的 Key，不经中转传音频），所以不查「允许手机用」。
     * providerId / modelId / voice 留空 = 用设置里选的（设置也留空就自动挑）。
     * prepared = true：text 是 SpeakPlan 切好的一段，不再清理 Markdown（清理两遍会把「3.5 元」这类开头再削一次）。
     */
    @Serializable data class Speak(
        val text: String,
        val providerId: String = "",
        val modelId: String = "",
        val voice: String = "",
        val prepared: Boolean = false,
    ) : Command
    /**
     * 语音：读长回答之前先问引擎「用哪家、切成哪几段」，不花钱、不联网。结果 data 是 SpeechPlan 的 JSON。
     * 界面拿到以后一段一段发 Speak(prepared = true)、一段一段放：点「停止」就不再往下合成（不再花钱）。
     */
    @Serializable data class SpeakPlan(val text: String, val providerId: String = "", val modelId: String = "") : Command
    /**
     * 语音：把一段录音听写成文字。audioBase64 是 wav（16 位 PCM）；结果 data 就是识别出来的文字。
     * 智谱一段最多 30 秒，引擎会按 25 秒切开分开识别再按顺序拼起来。
     */
    @Serializable data class Transcribe(
        val audioBase64: String,
        val mime: String = "audio/wav",
        val providerId: String = "",
        val modelId: String = "",
    ) : Command

    /**
     * 按意思搜：先估算「把现有的聊天记录和我的文档都算成向量」有多少字、多少段、大约多少钱。
     * 不联网、不花钱、也不算向量（只按本机已有的东西数一遍），结果 data 是 SemanticEstimateResult 的 JSON。
     */
    @Serializable data object SemanticEstimate : Command
    /** 按意思搜：整份作废重算。scope 是 history / docs / all（留空按 all）。换模型、维度时必须重算。 */
    @Serializable data class SemanticRebuild(val scope: String = "") : Command
    /** 按意思搜：查补算进度（和 AppState.semanticProgress 一样，手机遥控电脑时用它）。结果 data 是 SemanticProgress 的 JSON。 */
    @Serializable data object SemanticStatus : Command

    /** 立场档案：取议题（convId 留空 = 全部对话的）。结果 data 是 List<StanceTopic> 的 JSON。 */
    @Serializable data class StanceList(val convId: String = "") : Command
    /** 立场档案：标对错。verdict 是立场的 key，或 StanceTopic.NONE / OPEN，空 = 取消标记。 */
    @Serializable data class StanceMark(val topicId: String, val verdict: String) : Command
    /** 立场档案：让裁判重新核实一次这个议题（上次失败了，或者想再试一次）。 */
    @Serializable data class VerifyStance(val topicId: String) : Command
    /** 立场档案：删掉一个议题（记录员认错了的）。 */
    @Serializable data class StanceDelete(val topicId: String) : Command

    /** 换一个新的配对二维码（旧的立即失效）。 */
    @Serializable data object NewPairingCode : Command
    @Serializable data class RemoveDevice(val id: String) : Command
}

@Serializable
data class CommandResult(val ok: Boolean = true, val message: String = "", val data: String = "")

/**
 * 读出来的结果（Command.Speak 的 data）：一段或多段 wav（16 位 PCM）的 base64。
 * 长的文字会按所选模型的上限切成几段，界面一段一段放，点「停止」就不再往下合成。
 */
@Serializable
data class SpeechAudio(
    val mime: String = "audio/wav",
    val segments: List<String> = emptyList(),
    /** 用了哪个模型，给人看（比如「mimo-v2.5-tts（小米 MiMo）· 3 段 · 128 字」）。 */
    val label: String = "",
    val chars: Int = 0,
)

/**
 * 读出来之前切好的段（Command.SpeakPlan 的 data）：已经去掉 Markdown、跳过代码块，每段不超过所选模型的上限。
 * providerId / modelId 是这次挑中的，界面发 Speak 时原样带上，免得中途改了设置一段一家。
 */
@Serializable
data class SpeechPlan(
    val providerId: String = "",
    val modelId: String = "",
    /** 给人看，比如「mimo-v2.5-tts（小米 MiMo）」。 */
    val label: String = "",
    val chunks: List<String> = emptyList(),
)

/** 聊天记录搜索的一条结果。 */
@Serializable
data class HistoryHit(
    val convId: String,
    val convTitle: String,
    val messageId: String,
    val sender: String,
    val snippet: String,
    val time: Long,
)

/** 从电脑导到手机的配置包。 */
@Serializable
data class ConfigBundle(
    val providers: List<ProviderConfig>,
    val members: List<Member>,
    val profile: UserProfile,
    val search: SearchSettings,
    val imageGen: ImageGenSettings,
    val proxy: String,
    val memories: List<MemoryItem> = emptyList(),
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
