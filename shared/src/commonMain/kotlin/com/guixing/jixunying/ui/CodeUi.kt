package com.guixing.jixunying.ui

import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodePhone
import com.guixing.jixunying.model.CodeSpec

/**
 * Code 栏在这台设备上能干什么（docs/Code栏设计说明.md 第 6 节，第二期 2026-10-13）。
 *
 * 真正把关的是电脑上的 engine/code/PhoneGate：手机发去的每一条指令都在那儿过一遍，越权的一律拒。
 * 这里只是给它和界面共用的那一条判断——该显示哪些按钮、写哪句说明。界面里不重复实现一套把关
 * （不显示做不了的按钮，但如果还是点到了，电脑那边照样会拒，并把一句人话回过来显示给用户）。
 */
enum class CodeAccess {
    /** 电脑上（本身就是跑 Code 栏的那台）：照旧，全都能动。 */
    FULL,

    /** 手机遥控电脑、电脑上打开了「允许手机用 Code 栏」：能动手，但有几样只能在电脑上做。 */
    PHONE,

    /** 手机遥控电脑、电脑上没打开开关：只能看。 */
    LOCKED,

    /** 手机本机：这台设备上跑不了 Code 栏（没有跑命令的执行器）。 */
    NO_LOCAL;

    val canAct: Boolean get() = this == FULL || this == PHONE
}

object CodeUi {

    /**
     * 能不能动手，看三样：这台设备是不是电脑、现在操作的是本机还是电脑、正在操作的那台的开关。
     * allowPhone 取的是「现在操作的那台设备」的设置——手机遥控电脑时拿到的是电脑上的设置（电脑发过来的状态）。
     */
    fun access(isDesktop: Boolean, remoteMode: Boolean, allowPhone: Boolean): CodeAccess = when {
        !remoteMode -> if (isDesktop) CodeAccess.FULL else CodeAccess.NO_LOCAL
        allowPhone -> CodeAccess.PHONE
        else -> CodeAccess.LOCKED
    }

    /** 新建会话时能选的档：电脑上三档全列，手机上只有「手动」「计划」（CodePhone.createModes）。 */
    fun createModesFor(access: CodeAccess): List<CodeMode> =
        if (access == CodeAccess.FULL) CodeMode.entries.toList() else CodePhone.createModes.toList()

    /**
     * 会话页上权限档那几个按钮列哪几档：电脑上三档全列；手机上只列「手动」「计划」，
     * 「接受编辑」只在电脑上给这个会话选过（CodePhone.mayAcceptEdits）时才出现——
     * 电脑上没选过的话手机切过去也会被 PhoneGate 拒，界面就不给这个按钮。
     */
    fun modesFor(access: CodeAccess, spec: CodeSpec): List<CodeMode> {
        val list = createModesFor(access).toMutableList()
        if (access != CodeAccess.FULL && CodePhone.mayAcceptEdits(spec)) list += CodeMode.ACCEPT_EDITS
        return list
    }

    /** 手机上遥控电脑、电脑上没打开开关时，会话页底下那一句（要写清去哪儿打开）。 */
    const val LOCKED_NOTE = "电脑上没打开「允许手机用 Code 栏」（在电脑上 设置 → Code 栏 打开），现在只能看。"

    /** 手机遥控电脑、电脑上没打开开关时，Code 栏空白页上那一句。 */
    const val LOCKED_TITLE = "现在只能看电脑上的 Code 会话"

    /** 手机本机（没连电脑，或者侧栏顶上选的是「本机」）时，会话页底下那一句。 */
    const val NO_LOCAL_NOTE = "Code 栏要对着一个项目文件夹读文件、改文件、跑命令，只能在电脑上跑。" +
        "手机连上电脑以后（侧栏顶上切到「电脑」，电脑上 设置 → Code 栏 打开「允许手机用 Code 栏」），就能在这里遥控电脑上的 Code 栏。"

    /** 手机本机时，Code 栏空白页上那一句。 */
    const val NO_LOCAL_TITLE = "手机本机跑不了 Code 栏"

    /** 手机上不显示「全部撤销」，换成这一句。 */
    const val REVERT_ONLY_ON_PC = "撤销全部改动要在电脑上做"
}
