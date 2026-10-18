package com.guixing.jixunying.model

import kotlinx.serialization.Serializable

/**
 * Code 栏的权限档（docs/Code栏设计说明.md 第 1 节，2026-10-12）。
 * 不做「完全不问」这一档：想要的话以后用户明确提出再说。
 */
@Serializable
enum class CodeMode {
    /** 每次改文件、每次跑命令之前都停下来问，显示要改的差异或要跑的命令。默认这一档。 */
    MANUAL,
    /** 改文件不问（但事后能在差异里拒绝），跑命令仍然问。 */
    ACCEPT_EDITS,
    /** 只许读文件和做查找，不许改、不许跑会改东西的命令；AI 先给出计划，用户同意后再切到别的档去做。 */
    PLAN;

    val label: String get() = when (this) {
        MANUAL -> "手动"
        ACCEPT_EDITS -> "接受编辑"
        PLAN -> "计划"
    }
}

/**
 * 一个 Code 会话要什么（docs/Code栏设计说明.md 第 11 节，2026-10-12）。
 * Code 会话复用「对话 + 消息」那套存储和推送：Conversation.code 不为空就是 Code 会话，
 * 它的 memberIds 为空、channel 为空。模型从用户已经加好的服务商和模型里选，Code 栏自己不单独收 Key。
 */
@Serializable
data class CodeSpec(
    /** 项目文件夹的绝对路径；这个会话的所有文件读写都限制在它里面。 */
    val folder: String,
    val providerId: String,
    val modelId: String,
    val mode: CodeMode = CodeMode.MANUAL,
    /** 这个会话是从手机发起的（电脑端要明显标出来）。 */
    val fromPhone: Boolean = false,
    /**
     * 电脑上最后给这个会话选的权限档（2026-10-13，第二期）。手机不能比电脑放得更宽：
     * 「接受编辑」只有电脑上选过，手机才能切过去（engine/code/PhoneGate）。null = 旧版建的会话或手机建的、电脑还没选过。
     */
    val pcMode: CodeMode? = null,
    /**
     * 加进工作区的文件夹或文件（2026-10-17，相当于 Claude Code 的 /add-dir）：和项目文件夹一样，读不问、改按权限档。
     * 批准一步时选「允许，并把这个文件夹加进来」，或者在会话上面「工作区」里加。手机上加不了（engine/code/PhoneGate）。
     */
    val extraPaths: List<String> = emptyList(),
    /** 这个会话里「同样开头不再问」的命令开头（2026-10-17，engine/code/CodeCommands 判断）。手机上加不了。 */
    val allowedCommands: List<String> = emptyList(),
    /**
     * 全部放开（2026-10-17 用户：「比 Claude 更开放」）：改文件、跑命令都不问，只有危险命令照样问；改之前照样备份、能撤销。
     * 不做成 CodeMode 的新值：旧版手机解不开新的枚举值，整个状态会读坏（和会审开关同一个道理）。界面上是「接受编辑 + 这个开关」。
     * 只能在电脑上打开（PhoneGate）。
     */
    val openAll: Boolean = false,
    /**
     * 自动（2026-10-17，照 Claude Code 的 auto mode）：读、改工作区里的直接做，其余的先让检查员（裁判模型，engine/AutoJudge）看，
     * 检查员拦下的再问用户。和 openAll 一样不做成 CodeMode 新值（旧手机读不坏）；界面上是「接受编辑 + 这个开关」。只能在电脑上打开。
     */
    val autoMode: Boolean = false,
    /** 这个会话开过的 PR 网址（2026-10-18）：不为空时会话顶上显示检查状态。 */
    val prUrl: String = "",
    /**
     * 独立副本（2026-10-18，开发补齐第 10 样；Claude 的 worktree）：不为空 = 这个会话的 folder 是从这个仓库 git worktree 出来的副本，
     * 在自己的分支（worktreeBranch）上改；删会话时把副本清掉（分支留着）。
     */
    val worktreeOf: String = "",
    val worktreeBranch: String = "",
)

/**
 * 一次工具调用改了一个文件（docs/Code栏设计说明.md 第 1、11 节，2026-10-12）。
 * 改之前的内容由 engine/code/CodeBackup 按 id 存着：「拒绝」和「撤销这次任务的全部改动」都靠它还原。
 * 第一期按文件审阅：一个文件一份备份，够还原就行。
 */
@Serializable
data class FileChange(
    /** 这一步的唯一编号，也是备份文件夹里那份备份的文件名。 */
    val id: String,
    /** 相对项目文件夹的路径（用 /）。 */
    val path: String,
    /** 加了几行、删了几行（显示「改了 N 个文件 +X −Y」用）。 */
    val added: Int = 0,
    val removed: Int = 0,
    /** 原来没有这个文件（拒绝的时候要把它删掉）。 */
    val created: Boolean = false,
    /** "" 没处理 / "accepted" 接受了 / "rejected" 拒绝了。 */
    val review: String = "",
)

/**
 * Code 栏能选哪些模型（docs/Code栏设计说明.md 第 10 节第 4 条，2026-10-12）。
 * 服务商里所有支持调用工具的模型都能选，只有真跑通过一遍的才标「已实测」，其余标「未实测」（能选，只是没试过）。
 */
object CodeModels {
    /**
     * 已经用真 Key 跑通过编码循环的模型。键是「平台|模型名小写」，平台取 Thinking.platformOf（按预设编号或接口地址认）。
     * 主会话用真 Key 跑通后再加进来（LiveCodeTest，JXY_LIVE_CODE=数据文件夹）。
     * 2026-10-12 05:0x 都是同一个任务（把 greet.txt 第一行的 hello 改成 你好、跑命令确认），都改对、都跑了命令：
     * - deepseek|deepseek-flash：5 秒，输入 4907（命中缓存 3328）/ 输出 328 token
     * - minimax|minimax-m3：13 秒；思考写在正文的 <think> 里（界面拆成推理过程显示）
     * - zhipu|glm-5.3-flash：29 秒
     * - mimo|mimo-v2.6-flash：16 秒
     * 当时 PowerShell 读无 BOM 的 UTF-8 文件会乱码，MiniMax、小米多绕了一步；之后 CodeShell 已改成读的命令默认 UTF-8。
     */
    val TESTED: Set<String> = setOf("deepseek|deepseek-flash", "minimax|minimax-m3", "zhipu|glm-5.3-flash", "mimo|mimo-v2.6-flash")

    fun keyOf(platform: String, modelId: String) = platform.trim().lowercase() + "|" + modelId.trim().lowercase()

    fun isTested(platform: String, modelId: String) = keyOf(platform, modelId) in TESTED
}

/**
 * 设置 → Code 栏（docs/Code栏设计说明.md 第 10 节，2026-10-12）。
 * 以后往这里加旧版不认识的新项，记得 Settings.SCHEMA 加一，并在 Engine 的 SaveSettings 里保留电脑上的那几项。
 */
@Serializable
data class CodeSettings(
    /** 命令用哪个外壳：powershell（Windows 10 自带，不用另装东西）/ cmd / 自己指定一个外壳程序的完整路径。 */
    val shell: String = "powershell",
    /** 单条命令跑多久算超时（秒）；超时要真的把进程连同它的子进程杀掉。 */
    val commandTimeoutSec: Int = 300,
    /** 一次任务最多走几步工具调用，防止无限转；到了上限停下来告诉用户，不是悄悄结束。 */
    val maxSteps: Int = 40,
    /** 允许手机使用 Code 栏（第二期；默认关，手机上只能选电脑上已经建过的项目文件夹）。 */
    val allowPhone: Boolean = false,
    /** GitHub 命令行 gh.exe 的位置（2026-10-18）；空 = 靠 PATH 找。 */
    val ghPath: String = "",
    /** MCP 服务器（2026-10-18，开发补齐第 12 样）：名字 + 启动命令（stdio）。两栏的 AI 都能用它们的工具。只在电脑上配（PhoneGate 保留电脑上的 code 设置）。 */
    val mcpServers: List<McpServerConfig> = emptyList(),
)

@Serializable
data class McpServerConfig(val name: String, val command: String, val enabled: Boolean = true)

/**
 * 手机遥控电脑时 Code 栏能做什么（docs/Code栏设计说明.md 第 6 节，2026-10-13）。
 * 真正把关的是电脑上的 engine/code/PhoneGate；这里是它和手机界面共用的那几条判断（界面据此决定显示哪些按钮）。
 */
object CodePhone {
    /** 电脑上给这个会话选过「接受编辑」，手机才能切过去。旧会话没记 pcMode：电脑上建的按它现在的档算。 */
    fun mayAcceptEdits(spec: CodeSpec): Boolean =
        (spec.pcMode ?: if (!spec.fromPhone) spec.mode else null) == CodeMode.ACCEPT_EDITS

    /** 手机上新建能选的档（不含「接受编辑」）。 */
    val createModes: List<CodeMode> = listOf(CodeMode.MANUAL, CodeMode.PLAN)

    /** 电脑上已有 Code 会话的项目文件夹（去重：不分大小写、不管结尾斜杠）。手机新建对话框只能从这里选。 */
    fun folders(s: AppState): List<String> =
        s.conversations.mapNotNull { it.code?.folder }.distinctBy { it.trimEnd('\\', '/').lowercase() }
}
