package com.amapilot.navigation

/**
 * 简洁引导合成器：简洁模式下引擎转向类文本静音，由本器按 [NaviState] 合成三类
 * （句式 "{距离}后{动作}[, 进入{路名}]"，无名道路省略）：机动完成/导航开始，
 * 与接近机动点（`stepRemainSeconds` ≤ [CLOSE_SECONDS]，每 step 一次）。
 * 区间测速（[SpeedZone] 驱动）：接近/进入各一次 + 剩余梯级 + 均值超限（仅完整
 * 进入）；文案含"区间测速"关键词 → [VoiceSpeaker] 归限速提醒开关管控
 * （视觉零区分：车速环只保留车速/限速）。
 * 详细模式不合成；到达/重算/偏航等引擎文本由 VoiceSpeaker 放行。
 */
internal object GuidanceController {

    /** 接近触发：距机动点剩余秒数阈值（速度自适应距离）。 */
    const val CLOSE_SECONDS = 25

    /** 秒数未知（引擎未给）时的回退米数阈值。 */
    const val CLOSE_FALLBACK_METERS = 300

    private const val UNNAMED_ROAD = "无名道路"

    /** 区间剩余播报梯级（米）：递减跨越时各播一次。 */
    private val ZONE_REMAIN_STEPS_M = intArrayOf(1000, 500)

    private var lastStep = -1
    private var closeDone = false

    // ── 区间测速播报锁（每区间一次；区间消失即复位）──
    /** "前方区间测速"已播。 */
    private var zoneApproachDone = false

    /** "进入区间测速"已播。 */
    private var zoneEnterDone = false

    /** 剩余梯级各档已播。 */
    private var zoneRemainDone = BooleanArray(ZONE_REMAIN_STEPS_M.size)

    /** 均值超限翻转锁（跨越阈值播一次，回落复位）。 */
    private var zoneAvgOver = false

    /** 导航开始/结束/重算外重置（start/stop 调用）。 */
    fun reset() {
        lastStep = -1
        closeDone = false
        resetZoneLocks()
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
        out += zoneTexts(s)
        return out
    }

    /**
     * 区间测速合成：接近/进入各一次 + 剩余梯级（1km/500m）+ 均值超限提醒。
     * 均值仅完整进入（先接近后进入）播——中途汇入 SDK 均值跟踪基线偏晚；
     * 区间消失（驶出/中途离开/切换）复位全部锁，下个区间重新播。
     */
    private fun zoneTexts(s: NaviState): List<String> {
        val z = s.speedZone
        if (z == null) {
            resetZoneLocks()
            return emptyList()
        }
        val out = mutableListOf<String>()
        if (!z.inside && !zoneApproachDone) {
            zoneApproachDone = true
            out += if (z.limitKmh > 0) "前方区间测速，限速${z.limitKmh}" else "前方区间测速"
        }
        if (z.inside && !zoneEnterDone) {
            zoneEnterDone = true
            out += buildString {
                append("进入区间测速")
                if (z.limitKmh > 0) append("，限速").append(z.limitKmh)
                if (z.totalMeters > 0) append("，全长").append(fmtZoneLength(z.totalMeters))
            }
        }
        if (z.inside) {
            ZONE_REMAIN_STEPS_M.forEachIndexed { i, stepM ->
                if (!zoneRemainDone[i] && z.remainMeters in 1..stepM) {
                    zoneRemainDone[i] = true
                    out += "区间测速剩余${fmtDist(z.remainMeters)}"
                }
            }
            if (z.fullEntry && z.averageKmh > 0 && z.limitKmh > 0) {
                val over = z.averageKmh > z.limitKmh
                if (over && !zoneAvgOver) out += "区间平均速度已超限速，请减速"
                zoneAvgOver = over
            }
        }
        return out
    }

    private fun resetZoneLocks() {
        zoneApproachDone = false
        zoneEnterDone = false
        zoneRemainDone = BooleanArray(ZONE_REMAIN_STEPS_M.size)
        zoneAvgOver = false
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

    /** 区间全长口语化：整公里不带小数（"3公里"比显示梯级的"3.0公里"上口）。 */
    internal fun fmtZoneLength(m: Int): String =
        if (m >= 1000) "${m / 1000}公里" else fmtDist(m)
}
