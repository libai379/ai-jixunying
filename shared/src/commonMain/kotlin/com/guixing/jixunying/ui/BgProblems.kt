package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.BgProblem
import com.guixing.jixunying.model.Command

/**
 * 记录员后台活出错的提示：哪样活、在哪个对话、什么时候、为什么、会怎样；能直接去改，也能「知道了」先收起。
 * @param onMemoryPage 已经在 设置 → 记忆 了，就不再给「去记忆设置」的按钮
 */
@Composable
fun BgProblemsCard(ctl: AppController, problems: List<BgProblem>, onMemoryPage: Boolean = false) {
    if (problems.isEmpty()) return
    val err = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(shape).background(err.copy(alpha = 0.08f))
            .border(1.dp, err.copy(alpha = 0.35f), shape).padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        problems.sortedByDescending { it.at }.forEach { p ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.padding(top = 2.dp).size(16.dp), tint = err)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("记录员「${p.job.label}」出错" + (if (p.times > 1) "（连续 ${p.times} 次）" else ""),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(formatTime(p.at) + (if (p.convTitle.isNotBlank()) " · 对话「${p.convTitle}」" else ""),
                        style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    SelectionContainer { Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                    Text(p.job.consequence + "。成功一次这条就会自己消失。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pad = PaddingValues(horizontal = 0.dp)
                        val toProviders = listOf("Key", "余额", "额度", "模型服务", "模型名", "接口地址").any { it in p.reason }
                        if (toProviders) TextButton({ ctl.openSettings(SettingsTab.PROVIDERS) }, contentPadding = pad) { Text("去模型服务 ›", style = MaterialTheme.typography.labelMedium) }
                        if (toProviders && !onMemoryPage) Spacer(Modifier.width(16.dp))
                        if (!onMemoryPage) TextButton({ ctl.openSettings(SettingsTab.MEMORY) }, contentPadding = pad) { Text("换记录员 ›", style = MaterialTheme.typography.labelMedium) }
                        Spacer(Modifier.weight(1f))
                        TextButton({ ctl.run(Command.DismissBgProblem(p.job), quiet = true) }) { Text("知道了", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}
