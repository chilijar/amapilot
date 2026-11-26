package com.amapilot.navigation

/**
 * 简洁引导合成器：简洁模式下引擎转向类文本静音，由本器按 [NaviState] 合成两类
 * （句式 "{距离}后{动作}[, 进入{路名}]"，无名道路省略）：机动完成/导航开始，
 * 与接近机动点（`stepRemainSeconds` ≤ [CLOSE_SECONDS]，每 step 一次）。
 * 详细模式不合成；到达/重算/偏航等引擎文本由 VoiceSpeaker 放行。
 */
internal object GuidanceController {

    /** 接近触发：距机动点剩余秒数阈值（速度自适应距离）。 */
    const val CLOSE_SECONDS = 25

    /** 秒数未知（引擎未给）时的回退米数阈值。 */
    const val CLOSE_FALLBACK_METERS = 300

    private const val UNNAMED_ROAD = "无名道路"

    private var lastStep = -1
    private var closeDone = false

    /** 导航开始/结束/重算外重置（start/stop 调用）。 */
    fun reset() {
        lastStep = -1
        closeDone = false
    }

    /** 每帧导航状态入口。[concise] 关时静默；返回本帧合成文本（经 VoiceSpeaker 播出）。 */
    fun onState(s: NaviState, concise: Boolean): List<String> {
        if (!concise || s.arrived) return emptyList()
        val out = mutableListOf<String>()
        if (s.stepIndex != lastStep) {
            lastStep = s.stepIndex
            // 短 step（密集机动）：next 播报本身即接近提醒，不再补第二条
            closeDone = isClose(s)
            if (s.stepRemainMeters > 0 && s.actionText.isNotBlank()) {
                out += guideText(s)
            }
        } else if (!closeDone && isClose(s) && s.actionText.isNotBlank()) {
            closeDone = true
            out += guideText(s)
        }
        return out
    }

    /** 动态接近判定：剩余秒数优先（速度自适应），秒数未知回退米数。 */
    internal fun isClose(s: NaviState): Boolean =
        if (s.stepRemainSeconds > 0) {
            s.stepRemainSeconds <= CLOSE_SECONDS
        } else {
            s.stepRemainMeters in 1..CLOSE_FALLBACK_METERS
        }

    /** "{距离}后{动作}[, 进入{路名}]"；无名道路/空路名省略子句。 */
    internal fun guideText(s: NaviState): String = buildString {
        append(fmtDist(s.stepRemainMeters)).append("后").append(s.actionText)
        if (isNamedRoad(s.nextRoad)) append("，进入").append(s.nextRoad)
    }

    internal fun isNamedRoad(road: String): Boolean =
        road.isNotBlank() && road != UNNAMED_ROAD

    /** 距离口语化 = 转向卡文本去空格（[naviDistanceText] 单一来源，梯级与显示一致）。 */
    internal fun fmtDist(m: Int): String = naviDistanceText(m).replace(" ", "")
}
