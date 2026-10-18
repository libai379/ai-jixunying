package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.CodeSettings
import com.guixing.jixunying.model.Command

private const val POWERSHELL = "powershell"

/** 备份超过这个大小就在这一页顶上提醒（docs/Code栏设计说明.md 第 10 节）。 */
private const val BACKUP_WARN_BYTES = 1024L * 1024 * 1024

/**
 * 设置 → Code 栏（docs/Code栏设计说明.md 第 10、11 节，1.6.0）：命令用哪个外壳、跑一条命令最多多久、
 * 一次任务最多走几步、手机能不能用、改前备份占了多少。
 * 只在电脑上能改；手机上（本机，或者遥控电脑）这一页是只读的，写一句「在电脑上改」。
 */
@Composable
fun CodePage(ctl: AppController, state: AppState) {
    val platform = LocalPlatform.current
    val c = state.settings.code
    fun set(f: (CodeSettings) -> CodeSettings) = ctl.updateSettings { it.copy(code = f(it.code)) }
    // 只在电脑上、而且看的就是本机那台时才改得动（遥控电脑时改的是电脑上的设置，得坐到电脑前改）
    val editable = platform.isDesktop && ctl.backend === ctl.hub.local
    // 备份在数据目录里：电脑上直接算；手机遥控电脑时问电脑要（Command.CodeBackupSize）；手机本机不跑 Code 栏，只写「在电脑上看」
    val askBackup = editable || ctl.remoteMode

    PageHeader("Code 栏", "侧栏切到「Code」，让 AI 对着一个项目文件夹写代码：读代码、改文件、跑命令，" +
        "每一步都写在会话里给你看，改过的文件能拒绝、能恢复原样。这一页是它怎么跑、一次能走多远；改了马上生效。")

    if (!editable) ReadOnlyNote()

    // 备份攒多了会明显占盘，删会话又连备份一起删，用户得先知道
    val size by produceState<Long?>(null, ctl.backend, state.conversations.size) {
        value = if (!askBackup) null else {
            val r = ctl.backend.call(Command.CodeBackupSize)
            if (r.ok) r.data.toLongOrNull() else null
        }
    }
    if ((size ?: 0L) > BACKUP_WARN_BYTES) {
        WarnBar("Code 栏的改前备份已经超过 1 GB，删掉不用的 Code 会话会连备份一起删。")
        Spacer(Modifier.height(12.dp))
    }

    SectionCard {
        FieldLabel("命令用哪个外壳", "命令都在项目文件夹里跑，只给它最少的环境变量：各家服务商的 Key、代理设置都不给子进程。")
        if (editable) {
            // 「自己指定」点开前是一行选项，点开后才出现路径输入框（没填之前不往设置里写，空路径等于没指定）
            var custom by remember { mutableStateOf(isCustomShell(c.shell)) }
            RadioRow(!custom && shellLabel(c.shell) == "PowerShell", "PowerShell（推荐）", "Windows 10 自带，不用另装东西") {
                custom = false
                set { it.copy(shell = POWERSHELL) }
            }
            RadioRow(!custom && shellLabel(c.shell) == "cmd", "cmd", "老式命令提示符；中文输出按系统本地编码读") {
                custom = false
                set { it.copy(shell = "cmd") }
            }
            RadioRow(custom, "自己指定", "装了 Git Bash 之类的就填它的完整路径，比如 F:\\Git\\bin\\bash.exe") { custom = true }
            if (custom) {
                var path by remember { mutableStateOf(customShellOf(c.shell)) }
                Spacer(Modifier.height(4.dp))
                FieldLabel("外壳程序的完整路径")
                AppTextField(path, { path = it }, placeholder = "F:\\Git\\bin\\bash.exe")
                // 打字的时候不截、不校验（教训 27），停下来自动保存；保存下来的路径没有文件才标红
                AutoSave(path, customShellOf(c.shell)) { v -> if (v.trim().isNotEmpty()) set { it.copy(shell = v.trim()) } }
                val saved = customShellOf(c.shell)
                if (path.trim().isEmpty()) Text("还没填路径，现在还是用 PowerShell 跑命令。",
                    style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
                else if (saved.isNotEmpty() && !platform.fileExists(saved)) Text("这个路径上没有文件，命令跑不起来；不想用了就选回 PowerShell。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            ReadOnlyRow("现在用", shellLabel(c.shell))
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("一次任务最多跑多久、走多远", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (editable) {
            NumberField(c.commandTimeoutSec, 10, 3600, "一条命令最多跑多久（秒）",
                "默认 300；超时会把命令连同它开出的子进程一起杀掉", "秒") { v -> set { it.copy(commandTimeoutSec = v) } }
            NumberField(c.maxSteps, 1, 200, "一次任务最多走几步",
                "默认 40；到了上限会停下来告诉你，不会自己一直转", "步") { v -> set { it.copy(maxSteps = v) } }
            AutoSaveHint()
        } else {
            ReadOnlyRow("一条命令最多跑多久", "${c.commandTimeoutSec} 秒")
            ReadOnlyRow("一次任务最多走几步", "${c.maxSteps} 步")
        }
    }
    Spacer(Modifier.height(12.dp))
    // 2026-10-18 外部 Claude Code：用户自己装、自己登录的官方 Claude Code，走用户自己的订阅（engine/code/ClaudeCli）
    if (editable) {
        SectionCard {
            Text("Claude Code（用你的 Claude 订阅）", style = MaterialTheme.typography.titleSmall)
            Text("在 Code 会话的「工作区 → 模型」里选 Claude Code，这个会话就交给你电脑上的 Claude Code 去做：它读文件、改文件、跑命令，" +
                "每一步照样显示在会话里，要改文件、跑命令时照这个会话的权限档问你，改过的照样能看差异、撤销。" +
                "登录在 Claude Code 自己的窗口里完成，本程序不碰你的账号和凭据。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            var ccStatus by remember { mutableStateOf<String?>(null) }
            var ccOk by remember { mutableStateOf(false) }
            var ccCheck by remember { mutableStateOf(0) }
            LaunchedEffect(ccCheck, c.claudePath) {
                ccStatus = null
                val r = ctl.backend.call(Command.ClaudeStatus)
                ccOk = r.ok
                ccStatus = r.data.ifBlank { r.message }
            }
            Text(ccStatus ?: "正在检查…", style = MaterialTheme.typography.bodyMedium,
                color = if (ccStatus != null && !ccOk) Ext.c.warning else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ ccCheck++ }) { Text("重新检查") }
                if (!ccOk) TextButton({ ctl.run(Command.ClaudeLogin) }) { Text("打开 Claude Code 登录") }
                TextButton({ platform.openUrl("https://code.claude.com/docs") }) { Text("怎么装") }
            }
            var cp by remember { mutableStateOf(c.claudePath) }
            FieldLabel("claude.exe 的位置", "空着就自己找（PATH 里、官方安装器默认装的 用户文件夹\\.local\\bin）")
            AppTextField(cp, { cp = it }, placeholder = "比如 C:\\Users\\你\\.local\\bin\\claude.exe")
            AutoSave(cp, c.claudePath) { v -> set { it.copy(claudePath = v.trim()) } }
        }
        Spacer(Modifier.height(12.dp))
    }
    // 2026-10-18 连 GitHub：开发完在会话上面点「上传」，提交、推送、开 PR、建仓库（engine/code/GitHub）
    if (editable) {
        SectionCard {
            Text("GitHub", style = MaterialTheme.typography.titleSmall)
            Text("开发完在 Code 会话上面点「上传」：提交、推到 GitHub、开 PR；还不是仓库的能直接在 GitHub 上建一个并上传。" +
                "用的是 Git 和 GitHub 官方命令行 gh（和 Claude 桌面 App 一样），连 GitHub 走设置里的代理。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            var ghStatus by remember { mutableStateOf<String?>(null) }
            var ghOk by remember { mutableStateOf(false) }
            var checking by remember { mutableStateOf(0) }
            LaunchedEffect(checking) {
                ghStatus = null
                val r = ctl.backend.call(Command.GitHubStatus)
                ghOk = r.ok
                ghStatus = r.data.ifBlank { r.message }
            }
            Text(ghStatus ?: "正在检查…", style = MaterialTheme.typography.bodyMedium,
                color = if (ghStatus != null && !ghOk) Ext.c.warning else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ checking++ }) { Text("重新检查") }
                if (!ghOk) TextButton({ ctl.run(Command.GitHubLogin) }) { Text("登录 GitHub") }
                TextButton({ platform.openUrl("https://cli.github.com/") }) { Text("下载 gh") }
            }
            var gh by remember { mutableStateOf(c.ghPath) }
            FieldLabel("gh.exe 的位置", "空着就自己找（PATH 里有就行）；找不到时填完整路径")
            AppTextField(gh, { gh = it }, placeholder = "比如 C:\\Program Files\\GitHub CLI\\gh.exe")
            AutoSave(gh, c.ghPath) { v -> set { it.copy(ghPath = v.trim()) } }
        }
        Spacer(Modifier.height(12.dp))
    }
    // 钩子（2026-10-18，开发补齐第 12 样）
    if (editable) {
        SectionCard {
            Text("钩子", style = MaterialTheme.typography.titleSmall)
            Text("自己写的命令，在固定的时候自动跑，输出给 AI 看（报错了它会接着改）。不用批准：是你自己写的。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
            var he by remember { mutableStateOf(c.hookAfterEdit) }
            var ht by remember { mutableStateOf(c.hookAfterTask) }
            FieldLabel("改完文件后跑", "\$FILE 换成刚改的那个文件，比如 npx prettier --write \$FILE")
            AppTextField(he, { he = it }, placeholder = "空着就不跑")
            AutoSave(he, c.hookAfterEdit) { v -> set { it.copy(hookAfterEdit = v.trim()) } }
            FieldLabel("任务做完后跑", "在项目文件夹里跑，比如 npm test；没过的话 AI 会再改一轮")
            AppTextField(ht, { ht = it }, placeholder = "空着就不跑")
            AutoSave(ht, c.hookAfterTask) { v -> set { it.copy(hookAfterTask = v.trim()) } }
        }
        Spacer(Modifier.height(12.dp))
    }
    // MCP 外部工具（2026-10-18，开发补齐第 12 样）
    if (editable) {
        SectionCard {
            Text("外部工具（MCP）", style = MaterialTheme.typography.titleSmall)
            Text("接上 MCP 服务器，它带的工具两栏的 AI 都能用（比如读写某个数据库、操作浏览器、查公司内部系统）。" +
                "用起来和跑命令一样：手动档先问你，自动档给检查员看，全部放开不问。手机、微信发起的不能用。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            c.mcpServers.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyMedium)
                        Text(m.command, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1)
                    }
                    androidx.compose.material3.Switch(m.enabled, { v -> set { s -> s.copy(mcpServers = s.mcpServers.map { if (it == m) it.copy(enabled = v) else it }) } })
                    TextButton({ set { s -> s.copy(mcpServers = s.mcpServers - m) } }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
                }
            }
            var mName by remember { mutableStateOf("") }
            var mCmd by remember { mutableStateOf("") }
            var testMsg by remember { mutableStateOf("") }
            Spacer(Modifier.height(6.dp))
            AppTextField(mName, { mName = it }, placeholder = "名字，比如 文件系统")
            Spacer(Modifier.height(4.dp))
            AppTextField(mCmd, { mCmd = it }, placeholder = "启动命令，比如 npx -y @modelcontextprotocol/server-filesystem G:\\资料")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    testMsg = "正在连…"
                    ctl.run(Command.McpTest(mCmd.trim()), quiet = true) { r -> testMsg = (if (r.ok) "✓ " else "✗ ") + r.message + if (r.data.isNotBlank()) "\n" + r.data else "" }
                }, enabled = mCmd.isNotBlank()) { Text("测试连接") }
                TextButton({
                    set { s -> s.copy(mcpServers = s.mcpServers.filter { it.name != mName.trim() } + com.guixing.jixunying.model.McpServerConfig(mName.trim(), mCmd.trim())) }
                    mName = ""; mCmd = ""; testMsg = ""
                }, enabled = mName.isNotBlank() && mCmd.isNotBlank()) { Text("加上") }
            }
            if (testMsg.isNotBlank()) Text(testMsg.take(2000), style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        Spacer(Modifier.height(12.dp))
    }
    SectionCard {
        Text("手机", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        if (editable) {
            SwitchRow("允许手机用 Code 栏",
                "关着时：手机经加密线路发来的 Code 指令（新建、发任务、批准、停止）电脑一律拒绝，只有看过程和差异放行。" +
                    "手机用 Code 栏是第二期才做的；现在打开也只是让这些指令不被电脑拒绝，手机上的界面还是只读。",
                c.allowPhone) { v -> set { it.copy(allowPhone = v) } }
        } else {
            ReadOnlyRow("允许手机用 Code 栏", if (c.allowPhone) "已打开" else "已关闭")
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("改前备份", style = MaterialTheme.typography.titleSmall)
        Text("AI 每次改文件之前，先把原来的内容存一份（在数据目录的 code 文件夹里）：会话里点「拒绝」、" +
            "或者「撤销这次任务的全部改动」，都靠它把文件还原成改之前的样子。删掉 Code 会话会连它的备份一起删。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        ReadOnlyRow("现在占了", if (!askBackup) "在电脑上看" else size?.let { sizeText(it) } ?: "正在算…")
    }
}

/** 填的是外壳程序自己的路径（不是 powershell / cmd 这两个内置的）。 */
internal fun isCustomShell(shell: String): Boolean =
    shell.isNotBlank() && shell.trim().lowercase() != POWERSHELL && shell.trim().lowercase() != "cmd"

/** 自定义外壳的路径；用的是内置的（或者没填）就留空。 */
internal fun customShellOf(shell: String) = if (isCustomShell(shell)) shell.trim() else ""

/** 外壳的显示名（和 engine/CodeAgent.kt 里告诉模型的说法一致）。 */
internal fun shellLabel(shell: String) = when (shell.trim().lowercase()) {
    "", POWERSHELL -> "PowerShell"
    "cmd" -> "cmd"
    else -> shell.trim()
}

/** 备份大小的说法：上 GB 就说 GB（1 GB 的线要提醒，写一长串 MB 用户不好数）。 */
internal fun sizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${(bytes / 1024.0 / 1024.0 / 1024.0 * 100).toInt() / 100.0} GB"
    bytes >= 1024 * 1024 -> "${(bytes / 1024.0 / 1024.0 * 10).toInt() / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** 手机上看这一页：只在电脑上能改。 */
@Composable
private fun ReadOnlyNote() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(10.dp))
            .background(Ext.c.sidebarSelected).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Computer, null, Modifier.size(15.dp), tint = Ext.c.subtle)
        Spacer(Modifier.width(8.dp))
        Text("Code 栏只能在电脑上跑，这些设置也要在电脑上改；下面是电脑上现在的样子。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 只读的一行：左边是什么，右边是电脑上的值。 */
@Composable
private fun ReadOnlyRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** 黄色的提醒条（备份超 1 GB 这类要先跟用户说的）。 */
@Composable
internal fun WarnBar(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ext.c.warning.copy(alpha = 0.14f)).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(15.dp), tint = Ext.c.warning)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 填数字的设置（教训 27）：打字的时候不截、不校验，停下来自动保存时才看数对不对；
 * 不对（不是数字、出了范围）就提示一句、不保存，电脑上还是原来的值。
 */
@Composable
private fun NumberField(saved: Int, min: Int, max: Int, title: String, hint: String, unit: String, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(saved.toString()) }
    val n = text.trim().toIntOrNull()
    AutoSave(text, saved.toString()) { v -> v.trim().toIntOrNull()?.let { if (it in min..max) onSave(it) } }
    FieldLabel(title, hint)
    AppTextField(text, { text = it }, placeholder = saved.toString(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    if (n == null || n < min || n > max) Text("要填 $min 到 $max 之间的整数（$unit），现在没保存，还是用原来的 $saved",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(8.dp))
}
