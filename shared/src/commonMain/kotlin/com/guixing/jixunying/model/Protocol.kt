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
    /** 用 ChatGPT 登录（2026-10-23）：回执 data 是要在浏览器里打开的地址；用户在网页上点完以后状态里的 chatgpt 跟着变。只能在登录的那台设备上用。 */
    @Serializable data object ChatGptSignIn : Command
    /** 退出 ChatGPT 登录（清掉本机令牌）。 */
    @Serializable data object ChatGptSignOut : Command
    /** 读平台链接（2026-10-23）：各平台登没登录。回执 data 是 JSON {"available":true,"platforms":{"douyin":"in"|"out"|"unknown"}}。只在电脑上有浏览器。 */
    @Serializable data class PlatformStatus(val launch: Boolean = false) : Command
    /** 把读平台链接用的浏览器挪到屏幕上、打开这个平台让用户扫码登录。 */
    @Serializable data class PlatformLogin(val platform: String) : Command
    /** 登好了：浏览器挪回屏幕外面。 */
    @Serializable data object PlatformHide : Command
    /** 账号（2026-10-24）：用 GitHub 设备码登录。回执 data 是 JSON {"code":给用户输的码,"uri":网址}；用户在网页上同意后状态里的 account 跟着变。 */
    @Serializable data object AccountGitHubSignIn : Command
    /** 解绑 GitHub（本机密钥账号不变）。 */
    @Serializable data object AccountGitHubUnbind : Command
    /** 导出账号备份码（用口令加密）。回执 data 是备份码。只能在这台设备上导（不经过联机线路）。 */
    @Serializable data class AccountExport(val passphrase: String) : Command
    /** 导入账号备份码：这台设备换成那个账号。 */
    @Serializable data class AccountImport(val code: String, val passphrase: String) : Command
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
        /** 2026-10-18：用这个仓库的独立副本（git worktree）开会话。 */
        val worktree: Boolean = false,
        /** 2026-10-22：开「自动」档（独立副本照原会话的档开）。2026-10-28 起手机发来的也算。 */
        val auto: Boolean = false,
        /** 2026-10-28：开「全部放开」（新建时就能选，以前要建好再切；手机上也能选）。 */
        val openAll: Boolean = false,
    ) : Command
    /**
     * Code 栏：换这个会话的项目文件夹 / 模型（2026-10-17，新建不再先选，开起来以后随时换）。空字符串 = 这一样不换。
     * 换文件夹时，以前改动记录里的相对路径换成完整路径，照样能撤销。2026-10-28 起手机上也能换。
     */
    @Serializable data class CodeSetProject(val convId: String, val folder: String = "", val providerId: String = "", val modelId: String = "") : Command
    /**
     * Code 栏：给团队的一个角色选脑子（2026-10-19，多角色派活）。providerId 空 = 这个角色不要了；
     * CodeSpec.CLAUDE_CODE = 交给 Claude Code（modelId 是 --model 别名）；CodeSpec.CODEX / GEMINI = 交给 Codex / Gemini CLI（2026-10-20）。
     * 2026-10-28 起手机上也能改。
     */
    @Serializable data class CodeSetTeam(val convId: String, val role: String, val providerId: String = "", val modelId: String = "") : Command
    /** 流程模板（2026-10-22，第 19 节）：flow 填 CodeSpec.FLOW_DEV 打开一条龙，空 = 关掉。2026-10-28 起手机上也能改。 */
    @Serializable data class CodeSetFlow(val convId: String, val flow: String = "") : Command
    /** Claude Code 的思考强度（2026-10-22）：effort 是 CodeSpec.EFFORTS 里的一个，空 = 它自己的默认。 */
    @Serializable data class CodeSetEffort(val convId: String, val effort: String = "") : Command
    /**
     * Code 栏连 GitHub（2026-10-18，engine/code/GitHub）：op =
     * "repo" 看项目的 Git 状态（data 是 JSON：repo / branch / changes / remote / ahead）；"commit" 提交全部改动；
     * "push" 推上去；"commit_push" 提交并推；"pr" 推上去并开 PR；"create" 还不是仓库的，建仓库并上传（name、private）；
     * 2026-10-18：「checks」PR 的检查状态（data 是 JSON：url / state / checks:[[名字, 结果]]）；「ci_fix」把失败日志交给 AI 修；「automerge」全过了自动合并。
     * 结果 data 是命令的输出。只有 "repo" 手机上能用（PhoneGate）。
     */
    @Serializable data class CodeGit(val convId: String, val op: String, val message: String = "", val name: String = "", val private: Boolean = true) : Command
    /**
     * Code 会话的终端（2026-10-18，开发补齐第 2 样）：用户自己敲的一条命令，在 cwd 里跑（空 = 项目文件夹）。
     * data 是 JSON：{"cwd": 跑完以后的目录, "output": 输出, "code": 退出码}。「cd 目录」只换目录不跑；末尾带 & 的放后台。
     * 只能在电脑上用（PhoneGate）。
     */
    @Serializable data class TermRun(val convId: String, val command: String, val cwd: String = "") : Command
    /**
     * 输入框打「@」补全文件名（2026-10-18，开发补齐第 5 样）：Code 会话在项目文件夹里找，对话在拖进来的文件夹里找。
     * data 是 JSON 数组（最多 20 个）：Code 会话给相对路径，对话给完整路径。
     */
    @Serializable data class FileSuggest(val convId: String, val query: String) : Command
    /**
     * 斜杠命令清单（2026-10-18，开发补齐第 12、13 样）：内置的（/审查）+ 设置里自己写的 + Code 会话项目里 .claude/commands 底下的 .md。
     * data 是 JSON 数组：[{"name","description","text","source"}]。
     */
    @Serializable data class SlashList(val convId: String) : Command
    /** 试着连一个 MCP 服务器（2026-10-18）：data 是它的工具名，一行一个；连不上 ok = false。只能在电脑上。 */
    @Serializable data class McpTest(val command: String) : Command
    /** 后台命令（2026-10-18）：看输出（data 是最后一截输出）。 */
    @Serializable data class BgOutput(val convId: String, val id: String) : Command
    /** 后台命令：停掉。手机发来的拒绝（PhoneGate 不拦也行，停掉只会更安全——放行）。 */
    @Serializable data class BgStop(val convId: String, val id: String) : Command
    /** GitHub 连没连上：git、gh 装没装、登没登录。data 是给人看的几行字；ok = 都好了。 */
    @Serializable data object GitHubStatus : Command
    /** 开一个窗口登录 GitHub（gh auth login --web），用户在浏览器里点同意。只能在电脑上。 */
    @Serializable data object GitHubLogin : Command
    /** 外部 Claude Code（2026-10-18）：装没装、什么版本、登没登录（只读它自己 claude auth status 的结果，不碰凭据）。 */
    @Serializable data object ClaudeStatus : Command
    /** 开一个终端窗口运行 claude，让用户在它自己的界面里登录（/login）。只能在电脑上。 */
    @Serializable data object ClaudeLogin : Command
    /** Codex / Gemini CLI（2026-10-20）：装没装、什么版本（Codex 还查登没登录）。agent 是 CodeSpec.CODEX / GEMINI。 */
    @Serializable data class AgentStatus(val agent: String) : Command
    /** Code 栏：换权限档（只影响之后的步骤，已经跑完的不变）。fromPhone 同上。 */
    @Serializable data class CodeSetMode(val convId: String, val mode: CodeMode, val fromPhone: Boolean = false,
        /** 2026-10-17：「全部放开」（CodeSpec.openAll）。2026-10-28 起手机上也能开。 */
        val openAll: Boolean = false,
        /** 2026-10-17：「自动」（CodeSpec.autoMode）。2026-10-28 起手机上也能开。 */
        val auto: Boolean = false) : Command
    /** Code 栏：发一条任务，开始跑编码循环（同一个会话已经在跑就排队，前一个跑完接着跑）。 */
    @Serializable data class CodeSend(
        val convId: String,
        val text: String,
        /** 2026-10-17：附件（先 UploadFile 传上来的）。图片发给能看图的模型，文档抽出文字一起发。 */
        val attachmentIds: List<String> = emptyList(),
        /**
         * 2026-10-17：电脑上拖进来 / 选的文件在电脑上的原路径。写进这条任务里给模型看，并加进工作区（读不用再问）。
         * 手机发来的一律清空（手机上的路径电脑上没有，也不许手机放大工作区，PhoneGate）。
         */
        val paths: List<String> = emptyList(),
        /** 手机发来的（PhoneGate 填 true）：只用来标「手机发起」，2026-10-28 起和电脑上发的一样做。 */
        val fromPhone: Boolean = false,
    ) : Command
    /** Code 栏：停下这个会话正在跑的循环（模型请求和正在跑的命令都停），消息状态变 STOPPED。 */
    @Serializable data class CodeStop(val convId: String) : Command
    /**
     * Code 栏：批准或拒绝一步（读写工作区外的文件 / 写文件 / 改文件 / 跑命令）。针对具体某一步，没有超时自动放行。
     * remember（2026-10-17）："" 只这一次；"dir" 允许并把这一步的文件夹（ToolStep.scopeDir）加进工作区；
     * "command" 允许并且同样开头（ToolStep.prefix）的命令这个会话里不再问。2026-10-28 起手机发来的也照办。
     */
    @Serializable data class CodeDecide(val convId: String, val stepId: String, val allow: Boolean, val remember: String = "") : Command
    /**
     * Code 栏：改这个会话的工作区（加进来的文件夹 / 文件）和「不再问」的命令开头（2026-10-17）。整份替换。
     * 2026-10-28 起手机上也能改（电脑上打开「允许手机用 Code 栏」时）。
     */
    @Serializable data class CodeSetAccess(val convId: String, val extraPaths: List<String>, val allowedCommands: List<String>) : Command
    /** Code 栏：某一处改动的统一差异（unified diff）。结果 data 是差异文本。 */
    @Serializable data class CodeDiff(val convId: String, val changeId: String) : Command
    /** Code 栏：接受或拒绝一处改动；拒绝 = 把文件恢复成改之前的样子（原来没有的文件就删掉）。 */
    @Serializable data class CodeReview(val convId: String, val changeId: String, val accept: Boolean) : Command
    /**
     * Code 栏「回到这里」（2026-10-18，开发补齐第 8 样）：从这条用户任务开始（含）往后的改动全部还原、消息全部删掉。
     * data 是这条任务的原文（界面放回输入框）。正在跑的先停。手机上不许（一次动一大片，PhoneGate）。
     */
    @Serializable data class CodeRewind(val convId: String, val messageId: String) : Command
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
        /** 2026-10-17：电脑上拖进来 / 选的文件和文件夹的路径：写进消息、这个对话的 AI 读它不用再问。手机发来的清空（PhoneGate）。 */
        val paths: List<String> = emptyList(),
        /** 手机发来的（PhoneGate 填 true，手机自己填的不算）：电脑上没打开「允许手机用 Code 栏」时，这一轮 AI 不许读收录范围外的文件、不许改文件和跑命令。 */
        val fromPhone: Boolean = false,
        /** true 时不走聊天模型，直接把文字当画图提示词。 */
        val drawImage: Boolean = false,
        /** true 时不走聊天模型，直接把文字当视频描述，附上的图 / 视频 / 音频 / 文档当素材（2026-10-21）。 */
        val makeVideo: Boolean = false,
    ) : Command
    @Serializable data class Stop(val convId: String) : Command
    @Serializable data class DeleteMessage(val convId: String, val messageId: String) : Command
    @Serializable data class Regenerate(val convId: String, val messageId: String, val fromPhone: Boolean = false) : Command
    /**
     * 对话页：AI 要读收录范围外的文件，用户批准或拒绝（2026-10-17）。remember = "dir"：允许并且这个文件夹在这个对话里以后不再问。
     * 2026-10-28 起手机发来的 remember 也照办（电脑上打开「允许手机用 Code 栏」时，PhoneGate）。
     */
    @Serializable data class ChatDecide(val convId: String, val stepId: String, val allow: Boolean, val remember: String = "") : Command
    /** 对话页：撤销 AI 改的一处文件（恢复成改之前的样子，原来没有的文件删掉）。2026-10-17。 */
    @Serializable data class ChatRevert(val convId: String, val changeId: String) : Command
    /** 对话页：改这个对话「不用再问」的路径（整份替换）。只能在电脑上改。 */
    @Serializable data class ChatSetGranted(val convId: String, val paths: List<String>) : Command

    /** 远程上传附件：内容 Base64。结果 data 是 Attachment 的 JSON。 */
    @Serializable data class UploadFile(val name: String, val mime: String, val base64: String) : Command
    /** 远程取文件：结果 data 是 Base64。 */
    @Serializable data class GetFile(val id: String) : Command
    /** 手机从电脑导入服务商（含 Key）、成员、个人资料、搜索和画图设置。结果 data 是 ConfigBundle 的 JSON。 */
    @Serializable data object ExportConfig : Command
    /** 手机把电脑导出的配置并入本机。 */
    /**
     * fromPeer（2026-10-27 双向同步）：是配对的另一台发来的——本机代理不动、本机地址（127.0.0.1 这类）的服务商不要、记忆不在这里合（MemorySync 管）。
     */
    @Serializable data class ImportConfig(val bundleJson: String, val fromPeer: Boolean = false) : Command

    /** 长期记忆：新增或修改一条。 */
    @Serializable data class SaveMemory(val item: MemoryItem) : Command
    @Serializable data class DeleteMemory(val id: String) : Command
    /** 对话属于哪个记忆话题（对话设置里改）：topic = null 交回记录员自动认，"" = 不属于任何话题。 */
    @Serializable data class SetConvTopic(val convId: String, val topic: String?) : Command
    /** 让记录员合并重复、矛盾的记忆。 */
    @Serializable data object TidyMemories : Command
    /** 手机和电脑互相同步记忆（2026-10-24）：发来对方的记忆和删除记录，合进本机，data 回合好的 MemoryBundle JSON。 */
    @Serializable data class SyncMemories(val bundle: MemoryBundle) : Command
    /**
     * 手机和电脑互相同步账本（2026-10-27，model/DeviceSync.kt）：合进对方发来的 push（记成 origin 那台的），
     * data 回本机自己 pullSince（含）以后的一批 LedgerBatch JSON，最多 limit 条。
     */
    @Serializable data class SyncLedger(val push: LedgerBatch, val origin: String, val pullSince: Long, val limit: Int = 200) : Command
    /** 同步一个微信对话（渠道）的记录，规则同 SyncLedger；data 回 ChannelBatch JSON。 */
    /** 手机同步完一遍告诉电脑（电脑联机页显示每台手机什么时候同步过）；RelayHost 按是哪台手机记。 */
    @Serializable data class SyncDone(val summary: String) : Command
    /** 两端同步配置（PeerConfig）：合进本机（打平时听发来的），data 回合好的 PeerConfig JSON。发空的就是只取对方的。 */
    @Serializable data class SyncConfig(val bundle: PeerConfig) : Command
    /** 电脑上点「让手机现在同步」：记下时间，在线的手机看到状态里的 syncRequestAt 变了就同步一遍。 */
    @Serializable data object RequestPhoneSync : Command
    @Serializable data class SyncChannel(val push: ChannelBatch, val origin: String, val pullSince: Long, val limit: Int = 40) : Command
    /**
     * 手机的 GitHub 登录换令牌交给电脑做（2026-10-24 方案一）：手机拿到授权码后经加密线路发来，电脑去 GitHub 换令牌、查用户资料，
     * data 回 GitHub 用户资料 JSON（login、id、name、avatar），令牌电脑上也不留、不绑到电脑自己的账号上。
     */
    /** 手机借电脑本机的模型问一次（request = messages / temperature / tools / extra / thinking 的 JSON）；只认电脑上地址是本机的服务商。 */
    @Serializable data class HostChat(val providerId: String, val model: String, val request: String) : Command
    @Serializable data class GitHubExchange(val code: String, val redirectUri: String, val verifier: String) : Command
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
    /** 电脑关机时由哪台手机接微信（设备编号；"none" = 都不接；空 = 不指定）。只能在电脑上改。 */
    @Serializable data class WeixinSetBackup(val deviceId: String) : Command

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

    /**
     * 集训场：开一局狼人杀（同一时间只打一局）。roles 是这局的身份（个数 = 座位数），座位按给的顺序；
     * 座位上 role 指定了的按指定，其余随机发。stepMode = 主持人一步一步点；judge = AI 每天交私下判断（测评用）；useExp = AI 带上以前总结的经验。
     * 结果 data 是局编号。
     */
    @Serializable data class WolfStart(
        val roles: List<WolfRole>,
        val seats: List<WolfSeatPick>,
        val setupName: String = "",
        val stepMode: Boolean = false,
        val judge: Boolean = true,
        val useExp: Boolean = true,
    ) : Command
    /**
     * 集训场：真人座位（或主持人）交这一步（askId 见 WolfAsk）。发言、遗言填 text（skill 是发言时顺带用的技能：explode / whiteExplode / duel，
     * 带人、决斗的对象填 target）；选座位填 target（0 = 不选）；女巫填 save / poison；奇迹商人填 target + gift（check / shield / poison）；
     * next 随便交；error 填 text = retry / skip。
     */
    @Serializable data class WolfAnswer(
        val askId: String, val text: String = "", val target: Int = 0, val save: Boolean = false, val poison: Int = 0,
        val skill: String = "", val gift: String = "",
    ) : Command
    /** 集训场：主持人控制节奏。action：pause 暂停 / resume 继续 / step 改成一步一步 / auto 改成自动。 */
    @Serializable data class WolfControl(val action: String) : Command
    /** 集训场：主持人在桌上说一句（大家都看得到，AI 也会看到）。 */
    @Serializable data class WolfSay(val text: String) : Command
    /** 集训场：停掉正在打的那一局。 */
    @Serializable data object WolfStop : Command
    /** 集训场：正在打的那一局。结果 data 是 WolfLive 的 JSON，没有就是空。 */
    @Serializable data object WolfCurrent : Command
    /** 集训场：打过的局（新的在前）。结果 data 是 List<WolfGameRecord> 的 JSON。 */
    @Serializable data object WolfList : Command

    /** 换一个新的配对二维码（旧的立即失效）。 */
    @Serializable data object NewPairingCode : Command
    @Serializable data class RemoveDevice(val id: String) : Command
    /** 给配对过的手机改个名字（一台电脑连好几台手机时分得清，10-24）。 */
    @Serializable data class RenameDevice(val id: String, val name: String) : Command
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
    /** 集训场正在打的那一局变了（每一步都推）。 */
    @Serializable data class Wolf(val live: WolfLive) : Event
    /** 集训场：某个 AI 正在说的话（边出边推，说完由 Wolf 里的记录接上）。text 空 = 说完了。 */
    @Serializable data class WolfTyping(val seat: Int, val text: String) : Event
}

/** 手机和电脑之间的一帧（加密前的明文）。 */
@Serializable
sealed interface WireFrame {
    @Serializable data class Req(val reqId: Long, val command: Command) : WireFrame
    @Serializable data class Res(val reqId: Long, val result: CommandResult) : WireFrame
    /** 一次请求还没答完时先回的一截（借电脑本机模型 HostChat 一个字一个字出，10-25）。只发给发请求的那台手机，旧版手机不会收到。 */
    @Serializable data class Part(val reqId: Long, val content: String = "", val reasoning: String = "") : WireFrame
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
