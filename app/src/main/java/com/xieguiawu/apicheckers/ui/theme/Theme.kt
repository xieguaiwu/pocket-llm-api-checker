package com.xieguiawu.apicheckers.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── 全局约束 3 色板：UI 中只允许使用以下 9 种颜色 ──────────────

val Bg = Color(0xFF0E1116) // 页面背景
val Card = Color(0xFF161B22) // 卡片背景
val TextMain = Color(0xFFE6E8EB) // 主文字
val TextSub = Color(0xFF8B949E) // 次文字
val Accent = Color(0xFF58A6FF) // 强调（蓝）
val Warn = Color(0xFFD29922) // 警告（黄）
val Danger = Color(0xFFF85149) // 危险（红）
val Ok = Color(0xFF3FB950) // 成功（绿）
val Divider = Color(0xFF21262D) // 分隔线

// 深色纯色主题：所有 ColorScheme 槽位都映射到色板内，
// 防止 Material3 组件默认色泄漏出其他颜色。
private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    primaryContainer = Accent,
    onPrimaryContainer = Bg,
    inversePrimary = Accent,
    secondary = TextSub,
    onSecondary = Bg,
    secondaryContainer = Card,
    onSecondaryContainer = TextMain,
    tertiary = Accent,
    onTertiary = Bg,
    tertiaryContainer = Card,
    onTertiaryContainer = TextMain,
    background = Bg,
    onBackground = TextMain,
    surface = Card,
    onSurface = TextMain,
    surfaceVariant = Card,
    onSurfaceVariant = TextSub,
    surfaceTint = Accent,
    inverseSurface = TextMain,
    inverseOnSurface = Bg,
    error = Danger,
    onError = Bg,
    errorContainer = Danger,
    onErrorContainer = Bg,
    outline = Divider,
    outlineVariant = Divider,
    scrim = Bg,
    surfaceBright = Card,
    surfaceDim = Bg,
    surfaceContainer = Card,
    surfaceContainerHigh = Card,
    surfaceContainerHighest = Card,
    surfaceContainerLow = Card,
    surfaceContainerLowest = Bg,
)

@Composable
fun ApiCheckersTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
