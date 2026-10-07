package com.guixing.jixunying.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ProviderPreset

@Composable
fun MemberAvatar(member: Member?, size: Dp = 32.dp, emoji: String? = null, color: Long? = null) {
    val c = Color(color ?: member?.color ?: 0xFF94A3B8)
    Box(
        Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.16f)).border(1.dp, c.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji ?: member?.avatar ?: "?", fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
fun PresetBadge(p: ProviderPreset, size: Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color(p.badgeColor)),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.badge, color = Color.White, fontSize = (size.value * (if (p.badge.length > 1) 0.38f else 0.5f)).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ToggleChip(label: String, icon: ImageVector, checked: Boolean, onToggle: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (checked) primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (checked) primary.copy(alpha = 0.45f) else Ext.c.border, RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (checked) primary else Ext.c.subtle)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (checked) primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Ext.c.border),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun FieldLabel(text: String, hint: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 6.dp, top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    password: Boolean = false,
    minLines: Int = 1,
    trailing: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = Ext.c.subtle, style = MaterialTheme.typography.bodyMedium) },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Ext.c.border,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        }
        Switch(checked, onChange)
    }
}

/** 统一风格的弹窗：标题 + 关闭按钮 + 内容 + 底部按钮。 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 560.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      Column(Modifier.widthIn(max = width).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "关闭", Modifier.size(18.dp), tint = Ext.c.subtle)
                    }
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.heightIn(max = 620.dp)) { content() }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        // 弹窗打开时提示条显示在弹窗下方（同一层），不会被挡住
        androidx.compose.material3.SnackbarHost(LocalSnackbar.current)
      }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${(bytes / 1024.0 / 1024.0 * 10).toInt() / 10.0} MB"
}

val MemberColors = listOf(0xFF5B6CFF, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEC4899, 0xFF8B5CF6, 0xFFEF4444, 0xFF14B8A6, 0xFF64748B, 0xFFF97316)
val MemberEmojis = listOf("🤖", "🧠", "📊", "💻", "🧐", "✍️", "🦉", "🐱", "🦊", "🐼", "🌟", "🔬", "🎨", "📚", "⚖️", "🩺", "🚀", "🍵", "🎯", "🧭")
