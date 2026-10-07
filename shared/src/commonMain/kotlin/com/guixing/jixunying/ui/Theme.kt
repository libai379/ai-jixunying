package com.guixing.jixunying.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 界面里 Material 色板之外还要用到的几种颜色。 */
@Immutable
data class ExtraColors(
    val sidebar: Color,
    val sidebarSelected: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val border: Color,
    val subtle: Color,
    val codeBg: Color,
    val success: Color,
    val warning: Color,
    val canvas: Color,
)

val LocalExtra = staticCompositionLocalOf {
    ExtraColors(Color.White, Color.White, Color.White, Color.Black, Color.Gray, Color.Gray, Color.White, Color.Green, Color.Yellow, Color.White)
}

private val Brand = Color(0xFF4F5BEB)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8FF),
    onPrimaryContainer = Color(0xFF1B2280),
    secondary = Color(0xFF0EA5A0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F5F3),
    onSecondaryContainer = Color(0xFF064E4B),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFF1F2F6),
    onSurfaceVariant = Color(0xFF5E6472),
    surfaceContainer = Color(0xFFF3F4F8),
    surfaceContainerHigh = Color(0xFFEDEEF3),
    surfaceContainerHighest = Color(0xFFE6E8EE),
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFFD5D8E0),
    outlineVariant = Color(0xFFE6E8EE),
    error = Color(0xFFD93A3A),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8A1C1C),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8C95FF),
    onPrimary = Color(0xFF111433),
    primaryContainer = Color(0xFF2B3070),
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFF4FD6CF),
    onSecondary = Color(0xFF00302D),
    secondaryContainer = Color(0xFF0C3F3C),
    onSecondaryContainer = Color(0xFFC8F4F1),
    background = Color(0xFF111216),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF17181D),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF22242B),
    onSurfaceVariant = Color(0xFFA3A8B4),
    surfaceContainer = Color(0xFF1C1D23),
    surfaceContainerHigh = Color(0xFF24262D),
    surfaceContainerHighest = Color(0xFF2C2E36),
    surfaceContainerLow = Color(0xFF18191E),
    surfaceContainerLowest = Color(0xFF0E0F12),
    outline = Color(0xFF3A3D47),
    outlineVariant = Color(0xFF2C2E36),
    error = Color(0xFFFF7A7A),
    errorContainer = Color(0xFF3D1A1A),
    onErrorContainer = Color(0xFFFFD6D6),
)

private val LightExtra = ExtraColors(
    sidebar = Color(0xFFF0F1F6),
    sidebarSelected = Color(0xFFE2E4F3),
    userBubble = Color(0xFFE9EBFF),
    onUserBubble = Color(0xFF1A1D3A),
    border = Color(0xFFE3E5EC),
    subtle = Color(0xFF8A90A0),
    codeBg = Color(0xFFF4F5F8),
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    canvas = Color(0xFFFFFFFF),
)

private val DarkExtra = ExtraColors(
    sidebar = Color(0xFF15161B),
    sidebarSelected = Color(0xFF252733),
    userBubble = Color(0xFF2A2F5C),
    onUserBubble = Color(0xFFE8EAFF),
    border = Color(0xFF2A2C34),
    subtle = Color(0xFF7D8290),
    codeBg = Color(0xFF1F2128),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    canvas = Color(0xFF17181D),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(darkMode: Int, content: @Composable () -> Unit) {
    val dark = when (darkMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme: ColorScheme = if (dark) DarkScheme else LightScheme
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
            // 没写颜色的文字和图标默认跟着主题走：以前默认是黑色，深色模式下侧栏、标题、成员名都看不见
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

object Ext {
    val c: ExtraColors @Composable get() = LocalExtra.current
}
