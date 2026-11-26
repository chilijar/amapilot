package com.amapilot.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Google Maps 风格动效令牌（Material 3 Motion）：底部面板/抽屉用弹簧（可拖拽跟手），
 * 卡片/页面切换用 emphasized easing（快出慢入），数值/状态变化用短促 standard 过渡。
 */
object GmMotion {

    // ── Material 3 标准缓动曲线 ──
    /** 强调曲线：用于大范围/进入动画（快起慢收）。 */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** 强调减速：元素进入屏幕。 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** 强调加速：元素离开屏幕。 */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** 标准曲线：小范围/局部动画。 */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    // ── 时长 ──
    const val DurationShort = 150    // 状态/颜色微变
    const val DurationMedium = 250   // 卡片展开/淡入
    const val DurationLong = 400     // 页面/面板大过渡
    const val DurationSheet = 500    // 底部面板弹簧

    // ── 弹簧（底部面板/可拖拽元素）──
    /** 面板弹簧：中等刚度 + 轻微回弹，跟手且自然。 */
    fun <T> sheetSpring() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** 快速弹簧：FAB/小元素弹出。 */
    fun <T> quickSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
}
