package com.amapilot.navigation

import com.amap.api.navi.enums.IconType
import com.amap.api.navi.enums.LaneAction

/** 一条车道允许的方向集合（`backgroundLane` 可能是复合码，也可能是多个单码）。 */
internal class LaneDirs {
    var straight = false
    var left = false
    var right = false
    var uturnLeft = false
    var uturnRight = false

    /** 是否包含 [dir] 方向。 */
    fun has(dir: LaneDir): Boolean = when (dir) {
        LaneDir.STRAIGHT -> straight
        LaneDir.LEFT -> left
        LaneDir.RIGHT -> right
        LaneDir.UTURN_LEFT -> uturnLeft
        LaneDir.UTURN_RIGHT -> uturnRight
    }
}

/** 把 AMap 的 LaneAction（含复合码）展开成方向集合。 */
internal fun laneDirsOf(actions: IntArray): LaneDirs {
    val d = LaneDirs()
    actions.forEach { a ->
        when (a) {
            LaneAction.LANE_ACTION_AHEAD -> d.straight = true
            LaneAction.LANE_ACTION_LEFT_IN_AHEAD -> {
                // LEFT_IN_AHEAD = 向左分叉道路，等同“直行+左转”（Material fork_left）
                d.straight = true
                d.left = true
            }

            LaneAction.LANE_ACTION_LEFT -> d.left = true
            LaneAction.LANE_ACTION_RIGHT -> d.right = true

            LaneAction.LANE_ACTION_AHEAD_LEFT -> {
                d.straight = true; d.left = true
            }
            LaneAction.LANE_ACTION_AHEAD_RIGHT -> {
                d.straight = true; d.right = true
            }
            LaneAction.LANE_ACTION_LEFT_RIGHT -> {
                d.left = true; d.right = true
            }
            LaneAction.LANE_ACTION_AHEAD_LEFT_RIGHT -> {
                d.straight = true; d.left = true; d.right = true
            }

            LaneAction.LANE_ACTION_LU_TURN -> d.uturnLeft = true
            LaneAction.LANE_ACTION_RU_TURN -> d.uturnRight = true
            LaneAction.LANE_ACTION_AHEAD_LU_TURN -> {
                d.straight = true; d.uturnLeft = true
            }
            LaneAction.LANE_ACTION_AHEAD_RU_TURN -> {
                d.straight = true; d.uturnRight = true
            }

            // 同侧“转弯 + 掉头”：合并字形（转弯肘部 + 下挂小 U 环）
            LaneAction.LANE_ACTION_LEFT_LU_TURN,
            LaneAction.LANE_ACTION_LEFT_IN_LEFT_LU_TURN,
            LaneAction.LANE_ACTION_LEFT_LU_TURN_EX -> {
                d.left = true; d.uturnLeft = true
            }
            LaneAction.LANE_ACTION_RIGHT_RU_TURN,
            LaneAction.LANE_ACTION_RIGHT_RU_TURN_EX -> {
                d.right = true; d.uturnRight = true
            }

            LaneAction.LANE_ACTION_AHEAD_LEFT_LU_TURN -> {
                // 按“直行+左转”理解（掉头方向并入左转）
                d.straight = true; d.left = true
            }
            LaneAction.LANE_ACTION_LEFT_RU_TURN -> {
                // 按“左转+右转”理解
                d.left = true; d.right = true
            }
            LaneAction.LANE_ACTION_AHEAD_RIGHT_RU_TURN -> {
                // 按“直行+右转”理解（掉头方向并入右转）
                d.straight = true; d.right = true
            }
        }
    }
    return d
}

/** 方向集合 → 车道图标描述；没有对应组合时回退到单方向。
 *  [slight] = 当前 maneuver 是左前方/右前方时，fork 分支改用 45° 变体。 */
internal fun laneSpecOf(d: LaneDirs, slight: Boolean = false): LaneIconSpec = when {
    d.straight && d.left && d.right -> ManeuverIcons.StraightLeftRight
    d.straight && d.left ->
        if (slight) ManeuverIcons.ForkLeftSlight else ManeuverIcons.ForkLeft
    d.straight && d.right ->
        if (slight) ManeuverIcons.ForkRightSlight else ManeuverIcons.ForkRight
    d.left && d.right -> ManeuverIcons.LeftRight
    d.straight && d.uturnLeft -> ManeuverIcons.StraightUTurn
    d.straight && d.uturnRight -> ManeuverIcons.StraightUTurnRight
    d.left && d.uturnLeft -> ManeuverIcons.LeftUTurn
    d.right && d.uturnRight -> ManeuverIcons.RightUTurn
    d.straight -> ManeuverIcons.StraightLane
    d.left -> if (slight) ManeuverIcons.SlightLeftLane else ManeuverIcons.TurnLeftLane
    d.right -> if (slight) ManeuverIcons.SlightRightLane else ManeuverIcons.TurnRightLane
    d.uturnLeft -> ManeuverIcons.UTurnLeftLane
    d.uturnRight -> ManeuverIcons.UTurnRightLane
    else -> ManeuverIcons.StraightLane
}

/** maneuver 是否为左前方/右前方（45°），用于选择 fork 的 45° 分支变体。 */
internal fun isSlightManeuver(iconType: Int): Boolean =
    iconType == IconType.LEFT_FRONT || iconType == IconType.RIGHT_FRONT

/** 当前 maneuver 需要走的方向（用于车道内逐方向高亮）。 */
internal fun activeLaneDir(iconType: Int): LaneDir = when (iconType) {
    IconType.LEFT,
    IconType.LEFT_FRONT,
    IconType.LEFT_BACK -> LaneDir.LEFT

    IconType.RIGHT,
    IconType.RIGHT_FRONT,
    IconType.RIGHT_BACK -> LaneDir.RIGHT

    IconType.LEFT_TURN_AROUND,
    IconType.ENTRY_RING_UTURN -> LaneDir.UTURN_LEFT   // 含环岛掉头
    IconType.U_TURN_RIGHT -> LaneDir.UTURN_RIGHT
    // MERGE_LEFT/RIGHT 是并线进入主路/匝道，车道箭头仍是直行，按直行高亮
    else -> LaneDir.STRAIGHT
}

/**
 * 车道条路口归属容差（米，按机动类型分档）：机动"完成点"（step 末端）总比决策
 * 路口远出一截（路口内部段或掉头环弧长）。掉头容差偏大只会提前引导靠左，无害。
 */
internal const val LANE_TURN_TOLERANCE_M = 70
internal const val LANE_UTURN_TOLERANCE_M = 140

internal fun laneJunctionToleranceM(active: LaneDir): Int =
    if (active == LaneDir.UTURN_LEFT || active == LaneDir.UTURN_RIGHT) {
        LANE_UTURN_TOLERANCE_M
    } else {
        LANE_TURN_TOLERANCE_M
    }

/**
 * 机动点路口但**没有任何车道能执行**机动方向（掉头可借同侧转弯车道）= 窄路
 * 借道形态：转向卡已承载指引，隐藏矛盾信息更安全。途中路口不受影响。
 */
internal fun laneBarMisleadsManeuver(
    active: LaneDir,
    lanes: List<List<Int>>,
    linksToManeuver: Int = 1,
    stepRemainMeters: Int = Int.MAX_VALUE,
    linkRemainMeters: Int = -1,
): Boolean {
    if (lanes.isEmpty()) return false
    val maneuverJunction = if (linkRemainMeters >= 0) {
        stepRemainMeters <= linkRemainMeters + laneJunctionToleranceM(active)
    } else {
        linksToManeuver <= 1
    }
    if (!maneuverJunction) return false
    return when (active) {
        LaneDir.UTURN_LEFT -> lanes.none {
            val d = laneDirsOf(it.toIntArray()); d.has(LaneDir.LEFT) || d.has(LaneDir.UTURN_LEFT)
        }
        LaneDir.UTURN_RIGHT -> lanes.none {
            val d = laneDirsOf(it.toIntArray()); d.has(LaneDir.RIGHT) || d.has(LaneDir.UTURN_RIGHT)
        }
        else -> lanes.none { laneDirsOf(it.toIntArray()).has(active) }
    }
}

/**
 * 实际用于高亮的方向。主信号 = 引擎逐车道推荐（[recommended]，路口作用域、不受
 * 机动远近干扰）→ 整箭头高亮；引擎零推荐时按方向推导兜底：车道数据属最近路口
 * （`curLinkRetainDistance`），差 ≤ [laneJunctionToleranceM] 即机动点路口 → 按
 * maneuver 方向；差值大则本路口应直行。取不到 link 距离时退回 link 计数（≥2 = 途中）。
 */
internal fun resolveLaneHighlight(
    lanes: List<List<Int>>,
    active: LaneDir,
    recommended: List<Boolean> = emptyList(),
    linksToManeuver: Int = 1,
    stepRemainMeters: Int = Int.MAX_VALUE,
    linkRemainMeters: Int = -1,
): LaneHighlight {
    // 矛盾场景（窄路借道）全灰：车道组照常显示但不高亮，是否借道由用户判断。
    // 仅在有距离判定时生效（linkRemain 缺失的老路径保持直行回退语义）。
    if (linkRemainMeters >= 0 && laneBarMisleadsManeuver(
            active, lanes, linksToManeuver, stepRemainMeters, linkRemainMeters
        )
    ) {
        return LaneHighlight(lanes.map { emptySet() })
    }
    // 主信号：推荐车道高亮；机动点路口且该车道含机动方向时仅高亮机动分支
    // （45° 分叉变体同理），否则（途中路口 / 不含机动方向 / 掉头借道）整箭头同色。
    if (recommended.size == lanes.size && recommended.any { it }) {
        val maneuverJunction = if (linkRemainMeters >= 0) {
            stepRemainMeters <= linkRemainMeters + laneJunctionToleranceM(active)
        } else {
            linksToManeuver <= 1
        }
        return LaneHighlight(
            lanes.mapIndexed { i, lane ->
                if (!recommended[i]) return@mapIndexed emptySet()
                val dirs = laneDirsOf(lane.toIntArray())
                if (maneuverJunction && dirs.has(active)) {
                    setOf(active)
                } else {
                    buildSet {
                        if (dirs.straight) add(LaneDir.STRAIGHT)
                        if (dirs.left) add(LaneDir.LEFT)
                        if (dirs.right) add(LaneDir.RIGHT)
                        if (dirs.uturnLeft) add(LaneDir.UTURN_LEFT)
                        if (dirs.uturnRight) add(LaneDir.UTURN_RIGHT)
                    }
                }
            }
        )
    }
    val laneJunctionIsManeuver = if (linkRemainMeters >= 0) {
        stepRemainMeters <= linkRemainMeters + laneJunctionToleranceM(active)
    } else {
        linksToManeuver <= 1
    }
    val enRoute = !laneJunctionIsManeuver &&
        lanes.any { laneDirsOf(it.toIntArray()).straight }
    val effActive = if (enRoute) LaneDir.STRAIGHT else active
    if (lanes.isEmpty()) return LaneHighlight(emptyList())
    if (lanes.any { laneDirsOf(it.toIntArray()).has(effActive) }) {
        return LaneHighlight(
            lanes.map { lane ->
                if (laneDirsOf(lane.toIntArray()).has(effActive)) setOf(effActive) else emptySet()
            }
        )
    }
    // 掉头无专用车道 → 用同侧转弯车道：左掉头取**最左**左转车道，右掉头取**最右**
    // 右转车道（仅该车道高亮，其余同向车道不着色）
    val turnFallback = when (effActive) {
        LaneDir.UTURN_LEFT -> LaneDir.LEFT
        LaneDir.UTURN_RIGHT -> LaneDir.RIGHT
        else -> null
    }
    if (turnFallback != null) {
        val idxs = lanes.indices.filter {
            laneDirsOf(lanes[it].toIntArray()).has(turnFallback)
        }
        if (idxs.isNotEmpty()) {
            val pick = if (effActive == LaneDir.UTURN_LEFT) idxs.first() else idxs.last()
            return lanes.indices.map { i ->
                if (i == pick) setOf(turnFallback) else emptySet()
            }.let(::LaneHighlight)
        }
    }
    val anyStraight = lanes.any { laneDirsOf(it.toIntArray()).straight }
    val fallbackDir = if (anyStraight) LaneDir.STRAIGHT else effActive
    return LaneHighlight(
        lanes.map { lane ->
            if (laneDirsOf(lane.toIntArray()).has(fallbackDir)) setOf(fallbackDir) else emptySet()
        }
    )
}

/** 车道高亮结果：**逐车道**要高亮的方向集合（空集 = 该车道全灰）。 */

internal data class LaneHighlight(val laneDirs: List<Set<LaneDir>>)
