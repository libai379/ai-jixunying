package com.guixing.jixunying.engine.code

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodePhone
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import java.io.File

/**
 * 手机经加密中转发来的指令，进引擎之前先过这一道（docs/Code栏设计说明.md 第 6 节，安全核心，写代码的 AI 只调用不改）。
 * 只在 RelayHost 收到手机的指令时调；电脑上自己发的不经过这里。
 *
 * 不管开关开没开：
 * - 保存设置：Code 栏那几项（外壳、超时、最大步数、允许手机用）一律保留电脑上的。手机界面上这几项本来就只读，
 *   但不能指望界面：配对过的手机发一条保存设置，就能自己打开「允许手机用」、把外壳换成任意程序（2026-10-13 审出）。
 *
 * 电脑上没打开「允许手机用 Code 栏」：Code 栏的写操作一律拒绝（看差异、看备份大小这种只读的放行）。
 *
 * 打开了：
 * - 新建会话：只能用电脑上已经建过的 Code 会话的项目文件夹（不能让手机随便指一个路径），权限档只能是手动或计划；
 *   标成「手机发起」。
 * - 换权限档：手动、计划随便换；「接受编辑」只有电脑上给这个会话选过才行（手机不能比电脑放得更宽）。
 * - 撤销整条回答的全部改动：不许（一次动一大片，留给电脑上做）。一个文件一个文件地接受 / 拒绝可以。
 * - 发任务、停止、批准或拒绝某一步：放行。批准只针对具体一步（CodeDecide 带 stepId），等批准没有超时，
 *   手机掉线就一直等，绝不自动放行（CodeAgent 里本来就是这样）。
 *   2026-10-17：批准时的「顺便把文件夹加进工作区」「同样开头不再问」手机上一律当成只允许这一次；
 *   直接改工作区和「不再问」清单（CodeSetAccess）不许。
 * - 指令里的 fromPhone 一律改成 true，手机自己填的不算。
 */
object PhoneGate {

    /** 过了这一道以后的结果：refusal 不为空就直接回给手机；否则把 command（可能改写过）交给引擎。 */
    class Verdict(val command: Command, val refusal: CommandResult? = null)

    const val OFF = "电脑上没打开「允许手机用 Code 栏」"

    fun check(c: Command, s: AppState): Verdict {
        if (c is Command.SaveSettings) {
            return Verdict(c.copy(settings = c.settings.copy(code = s.settings.code)))
        }
        // 2026-10-17 对话页 AI 能读电脑上任意文件（收录范围外的先问用户）：手机发起的这一轮一律不许读收录范围外的；
        // 手机不能带电脑上的路径进来、不能放宽「不再问」的路径
        when (c) {
            is Command.SendMessage -> return Verdict(c.copy(paths = emptyList(), fromPhone = true))
            is Command.Regenerate -> return Verdict(c.copy(fromPhone = true))
            is Command.ChatDecide -> return Verdict(c.copy(remember = ""))
            is Command.ChatSetGranted -> return refuse(c, "对话里不用再问的文件夹只能在电脑上改")
            else -> Unit
        }
        if (!isCodeWrite(c)) return Verdict(c)
        if (!s.settings.code.allowPhone) return refuse(c, OFF)
        return when (c) {
            is Command.CodeCreate -> {
                if (c.mode !in CodePhone.createModes) return refuse(c, "手机上新建只能选「手动」或「计划」；要「接受编辑」请在电脑上切")
                val folder = knownFolder(c.folder, s) ?: return refuse(c, "手机上只能用电脑上已经建过 Code 会话的项目文件夹")
                Verdict(c.copy(folder = folder, fromPhone = true))
            }
            is Command.CodeSetMode -> {
                val spec = s.conversation(c.convId)?.code ?: return Verdict(c.copy(fromPhone = true)) // 不存在的会话交给引擎报错
                if (c.mode == CodeMode.ACCEPT_EDITS && !CodePhone.mayAcceptEdits(spec)) {
                    return refuse(c, "「接受编辑」要在电脑上给这个会话打开，手机上只能换「手动」或「计划」")
                }
                Verdict(c.copy(fromPhone = true))
            }
            is Command.CodeRevertTask -> refuse(c, "「撤销这次的全部改动」只能在电脑上做；手机上可以一个文件一个文件地拒绝")
            // 2026-10-17：手机不能把工作区放大、不能让命令「不再问」——这两样都比「批准这一步」宽
            is Command.CodeSetAccess -> refuse(c, "工作区和「不再问」的命令只能在电脑上改")
            is Command.CodeDecide -> Verdict(c.copy(remember = ""))
            is Command.CodeSend -> Verdict(c.copy(paths = emptyList()))
            else -> Verdict(c)
        }
    }

    /** 手机给的文件夹对上电脑上已有 Code 会话的哪个（规范化后比，Windows 不分大小写）；对不上返回 null。 */
    fun knownFolder(asked: String, s: AppState): String? {
        val want = norm(asked) ?: return null
        return s.conversations.mapNotNull { it.code?.folder }.firstOrNull { norm(it).equals(want, ignoreCase = true) }
    }

    private fun norm(p: String): String? {
        val t = p.trim().removeSurrounding("\"").trim()
        if (t.isEmpty() || t.any { it == '\u0000' }) return null
        return runCatching { File(t).absoluteFile.normalize().path.trimEnd('\\', '/') }.getOrNull()
    }

    private fun isCodeWrite(c: Command) = c is Command.CodeCreate || c is Command.CodeSend || c is Command.CodeStop ||
        c is Command.CodeDecide || c is Command.CodeSetMode || c is Command.CodeReview || c is Command.CodeRevertTask ||
        c is Command.CodeSetAccess

    private fun refuse(c: Command, why: String) = Verdict(c, CommandResult(false, why))
}
