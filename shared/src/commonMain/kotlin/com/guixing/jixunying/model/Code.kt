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
    /** 这个会话是从手机发起的（第二期用，先留着；电脑端要能明显标出来）。 */
    val fromPhone: Boolean = false,
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
)
