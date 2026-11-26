package com.amapilot.navigation

import kotlin.math.abs
import kotlin.math.log

/**
 * 导航跟随态自适应 zoom（手机 TBT / 车机 CarMapDisplay 单一来源）：
 * - **基础项（速度）**：`MAX − 1.2·log₂(max(v,8)/10)`，夹在 [MIN, MAX]；
 *   低速放大看细节、高速缩小看前路。
 * - **机动项（纯距离，不含速度）**：距下一机动 ≤500m 起线性拉近、≤100m 到
 *   满格 [BOOST]，上限 [MANEUVER_MAX]。判据是"离多远"而非"多久到"。
 * - 巡航/无机动传 `Float.MAX_VALUE` → 纯速度曲线。
 */
object AutoZoom {
    /** 高速下限（再快不再缩）。 */
    const val MIN = 13f

    /** 常规上限（低速/静止的目标档）。 */
    const val MAX = 18.5f

    /** 机动细节档上限（临近机动最多拉到此级，恒高于 [MAX] 半级余量）。 */
    const val MANEUVER_MAX = 19.1f

    /** 机动拉近起始距离（米）：距机动低于此值开始拉近。 */
    const val MANEUVER_START_M = 500f

    /** 机动拉近满格距离（米）：距机动低于此值拉满 [BOOST]。 */
    const val MANEUVER_FULL_M = 100f

    /** 机动项最大拉近量（zoom 级）。 */
    const val BOOST = 1.6f

    /** 每 tick 向目标的逼近步长（防跳变）。 */
    const val STEP = 0.2f

    /** 目标与当前差超此值直接吸附（远目标不慢慢爬）。 */
    const val SNAP_DIFF = 3f

    /** 死区：差值小于此不动（防微抖）。 */
    const val DEAD_ZONE = 0.05f

    /** 目标 zoom：速度基础项 + 机动距离分带（[MANEUVER_FULL_M]~[MANEUVER_START_M]）。 */
    fun target(speedKmh: Float, maneuverRemainMeters: Float): Float {
        val v = speedKmh.coerceAtLeast(8f)
        // 基础项先夹 [MIN, MAX]：v<10 的对数外插会超出 MAX，机动项叠加后才能到 MANEUVER_MAX
        val base = (MAX - 1.2f * log(v / 10f, 2f)).coerceIn(MIN, MAX)
        val inWindow = maneuverRemainMeters.isFinite() &&
            maneuverRemainMeters < MANEUVER_START_M
        val boost = if (inWindow) {
            val ramp = ((MANEUVER_START_M - maneuverRemainMeters) /
                (MANEUVER_START_M - MANEUVER_FULL_M)).coerceIn(0f, 1f)
            ramp * BOOST
        } else {
            0f
        }
        return (base + boost).coerceIn(MIN, MANEUVER_MAX)
    }

    /** 平滑逼近：一步 [STEP]，超 [SNAP_DIFF] 吸附，进 [DEAD_ZONE] 不动。
     *  [step] 供非 1Hz 调用方做时间归一（车机 250ms 帧 = [STEP]/4）。 */
    fun approach(current: Float, target: Float, step: Float = STEP): Float {
        val diff = target - current
        return when {
            abs(diff) > SNAP_DIFF -> target
            abs(diff) < DEAD_ZONE -> current
            else -> current + diff.coerceIn(-step, step)
        }
    }
}
