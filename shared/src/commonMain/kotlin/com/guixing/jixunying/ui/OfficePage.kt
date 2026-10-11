package com.guixing.jixunying.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.OfficeSettings
import kotlinx.coroutines.launch

/**
 * 设置 → Word / Excel（1.5.0）：AI 做文件时的默认排版和开关。
 * 用户 10-08：「做精细，人性化智能化，后台多做开关我可以主动调试」。聊天里另外说的（「用公文格式」「横向」）优先。
 */
@Composable
fun OfficePage(ctl: AppController, state: AppState) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val o = state.settings.office
    fun set(f: (OfficeSettings) -> OfficeSettings) = ctl.updateSettings { it.copy(office = f(it.office)) }
    val desktopHere = platform.isDesktop && ctl.backend === ctl.hub.local

    PageHeader("Word / Excel", "成员能把回答做成 Word 文档、Excel 表格：说「做成 Word」「整理成表格发我」就行，做好的文件显示在回答下面，点一下用 Word / WPS 打开；" +
        "微信里要的也直接发回微信。这里是默认的排版，聊天里另外说的（比如「用公文格式」「横向」「加个合计」）优先。改了马上生效。")

    SectionCard {
        SwitchRow("AI 能做 Word / Excel", "关掉后成员不再做文件，已经做好的还在", o.enabled) { v -> set { it.copy(enabled = v) } }
        SwitchRow("回答下面显示「存成 Word」", "有表格的回答再显示「表格存成 Excel」（像豆包的导出），用过的搜索来源附在 Word 最后", o.exportButtons) { v -> set { it.copy(exportButtons = v) } }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("Word 排版", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        FieldLabel("样式")
        RadioRow(o.wordStyle == "general", "通用", "微软雅黑五号、1.5 倍行距、段后空一点、不缩进；适合方案、总结、说明、清单") {
            set { it.copy(wordStyle = "general", firstLineIndent = false) }
        }
        RadioRow(o.wordStyle == "formal", "正式", "宋体小四正文、黑体标题、1.5 倍行距、首行缩进两字；适合报告、论文、申请、合同") {
            set { it.copy(wordStyle = "formal", firstLineIndent = true) }
        }
        RadioRow(o.wordStyle == "official", "公文", "接近国标 GB/T 9704：仿宋三号正文、固定行距 28 磅、一级标题黑体二级楷体、页码「— 1 —」、标题方正小标宋（电脑没装这个字体会换成别的）") {
            set { it.copy(wordStyle = "official", firstLineIndent = true) }
        }
        Spacer(Modifier.height(6.dp))
        SwitchRow("正文首行缩进两个字", "选样式时会跟着变，也能单独改", o.firstLineIndent) { v -> set { it.copy(firstLineIndent = v) } }
        SwitchRow("页脚加页码", "通用和正式是「第 1 页，共 3 页」，公文是「— 1 —」", o.pageNumbers) { v -> set { it.copy(pageNumbers = v) } }
        SwitchRow("页眉写文档标题", null, o.headerTitle) { v -> set { it.copy(headerTitle = v) } }
        SwitchRow("标题多时自动加目录", "4 个以上标题时开头加目录。Word 打开时会问要不要更新域，点「是」就有页码；WPS 在目录上右键「更新目录」", o.autoToc) { v -> set { it.copy(autoToc = v) } }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("Excel", style = MaterialTheme.typography.titleSmall)
        Text("数字、百分比、金额、日期自动变成能计算的格式；手机号、身份证号、0 开头的编号保持原样。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        SwitchRow("表头加粗、加底色", null, o.excelHeaderStyle) { v -> set { it.copy(excelHeaderStyle = v) } }
        SwitchRow("冻结表头", "往下翻时表头一直在上面", o.excelFreeze) { v -> set { it.copy(excelFreeze = v) } }
        SwitchRow("表头加筛选按钮", null, o.excelFilter) { v -> set { it.copy(excelFilter = v) } }
        SwitchRow("隔行浅色底纹", "行多的时候看着不串行", o.excelZebra) { v -> set { it.copy(excelZebra = v) } }
        SwitchRow("大于一千的数字加千分位", "12000 显示成 12,000，只是显示，值不变", o.excelThousands) { v -> set { it.copy(excelThousands = v) } }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Text("文件", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        SwitchRow("文件名后面加日期", "比如「周报-20261010.docx」", o.dateInName) { v -> set { it.copy(dateInName = v) } }
        if (desktopHere) {
            SwitchRow("做好后另存一份到文件夹", "在资源管理器里也能找到；关掉的话文件存在 AI集训营 自己的数据里，照样能点开", o.autoSave) { v -> set { it.copy(autoSave = v) } }
            if (o.autoSave) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("存到")
                        Text(o.saveFolder.ifBlank { "文档\\AI集训营（默认）" }, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { scope.launch { platform.pickFolder()?.let { p -> set { it.copy(saveFolder = p) } } } }) { Text("换文件夹") }
                    if (o.saveFolder.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { set { it.copy(saveFolder = "") } }) { Text("恢复默认") }
                    }
                }
            }
        } else {
            Text(if (platform.isDesktop) "做好的文件存在做它的那台设备上；点一下取过来打开，或点右边的下载图标另存。"
                else "手机上做好的文件点一下就用 WPS / Office 打开，或点右边的下载图标另存到手机里；遥控电脑时，文件存在电脑上，点开时先传到手机。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
    }
}
