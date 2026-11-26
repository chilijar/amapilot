package com.amapilot.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Google Maps 风格配色令牌（Material 3 色板）：仅覆盖 UI 层，底图颜色由高德 SDK 控制。
 * Google Blue 主色 + Google Gray 文字 + 语义色（通行绿/拥堵红/警示黄）。
 */
object GmColors {
    // ── 品牌主色（Google Blue）──
    val Blue600 = Color(0xFF1A73E8) // 主色/导航蓝
    val Blue700 = Color(0xFF1967D2) // 按下态
    val Blue50 = Color(0xFFE8F0FE)  // 浅蓝底（选中/高亮背景）
    val Blue300 = Color(0xFF8AB4F8) // 深色模式主色
    val DarkSelectedSurface = Color(0xFF2A3240) // 深色模式选中面（较 DarkElevated 仅微蓝调，选中态主靠勾边）

    // ── 中性色（Google Gray）──
    val Gray900 = Color(0xFF202124) // 主文字（浅色模式）
    val Gray700 = Color(0xFF5F6368) // 次文字/图标
    val Gray500 = Color(0xFF9AA0A6) // 占位/禁用
    val Gray300 = Color(0xFFDADCE0) // 描边/分隔线
    val Gray100 = Color(0xFFF1F3F4) // 浅灰底（输入框/芯片）
    val Gray50 = Color(0xFFF8F9FA)  // 极浅灰底
    val White = Color(0xFFFFFFFF)

    // 深色模式中性色
    val DarkSurface = Color(0xFF1F1F1F)      // 卡片/面板表面
    val DarkBackground = Color(0xFF121316)   // 页面背景
    val DarkElevated = Color(0xFF2A2B2E)     // 抬升表面
    val DarkOnSurface = Color(0xFFE3E3E3)    // 主文字
    val DarkOnSurfaceVar = Color(0xFFBDC1C6) // 次文字
    val DarkOutline = Color(0xFF5F6368)

    // ── 语义色 ──
    val Green700 = Color(0xFF188038) // 通行/顺畅/到达
    val Green50 = Color(0xFFE6F4EA)
    val Red600 = Color(0xFFD93025)   // 拥堵/错误/超速
    val Red50 = Color(0xFFFCE8E6)
    val Yellow700 = Color(0xFFF9AB00) // 警示/缓行
    val Yellow50 = Color(0xFFFEF7E0)
    val Orange600 = Color(0xFFE8710A) // 严重拥堵

    // ── 地图要素（与高德底图协调，用于自绘覆盖物）──
    val RouteBlue = Color(0xFF1A73E8)      // 选中路线
    val RouteAltGray = Color(0xFFB0B5BC)   // 备选路线
    val LocationDot = Color(0xFF1A73E8)    // 定位点
    val AccuracyFill = Color(0x1A1A73E8)   // 精度圈填充（~10%）
    val AccuracyStroke = Color(0x661A73E8) // 精度圈描边（~40%）
}
