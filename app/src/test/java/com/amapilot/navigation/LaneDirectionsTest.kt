package com.amapilot.navigation

import com.amap.api.navi.enums.IconType
import com.amap.api.navi.enums.LaneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 车道方向解析、图标选择与高亮方向回退规则。 */
class LaneDirectionsTest {

    private fun dirs(vararg actions: Int) = laneDirsOf(actions)

    @Test
    fun compositeActionsExpandToDirections() {
        with(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT)) {
            assertEquals(true, straight); assertEquals(true, left); assertEquals(false, right)
        }
        with(dirs(LaneAction.LANE_ACTION_AHEAD_RIGHT)) {
            assertEquals(true, straight); assertEquals(true, right); assertEquals(false, left)
        }
        with(dirs(LaneAction.LANE_ACTION_LEFT_RIGHT)) {
            assertEquals(true, left); assertEquals(true, right); assertEquals(false, straight)
        }
        with(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT_RIGHT)) {
            assertEquals(true, straight); assertEquals(true, left); assertEquals(true, right)
        }
        with(dirs(LaneAction.LANE_ACTION_LU_TURN)) {
            assertEquals(true, uturnLeft); assertEquals(false, straight)
        }
        with(dirs(LaneAction.LANE_ACTION_AHEAD_LU_TURN)) {
            assertEquals(true, straight); assertEquals(true, uturnLeft)
        }
        with(dirs(LaneAction.LANE_ACTION_LEFT_LU_TURN)) {
            assertEquals(true, left); assertEquals(true, uturnLeft); assertEquals(false, straight)
        }
        with(dirs(LaneAction.LANE_ACTION_RIGHT_RU_TURN)) {
            assertEquals(true, right); assertEquals(true, uturnRight)
        }
        // AHEAD_LEFT_LU_TURN / LEFT_RU_TURN / AHEAD_RIGHT_RU_TURN 按两向理解
        with(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT_LU_TURN)) {
            assertEquals(true, straight); assertEquals(true, left); assertEquals(false, uturnLeft)
        }
        with(dirs(LaneAction.LANE_ACTION_LEFT_RU_TURN)) {
            assertEquals(true, left); assertEquals(true, right); assertEquals(false, uturnRight)
        }
        with(dirs(LaneAction.LANE_ACTION_AHEAD_RIGHT_RU_TURN)) {
            assertEquals(true, straight); assertEquals(true, right)
        }
    }

    @Test
    fun laneSpecMatchesDirectionSet() {
        assertSame(ManeuverIcons.ForkLeft, laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT)))
        assertSame(ManeuverIcons.ForkRight, laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_RIGHT)))
        assertSame(ManeuverIcons.LeftRight, laneSpecOf(dirs(LaneAction.LANE_ACTION_LEFT_RIGHT)))
        assertSame(
            ManeuverIcons.StraightLeftRight,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT_RIGHT))
        )
        assertSame(ManeuverIcons.LeftUTurn, laneSpecOf(dirs(LaneAction.LANE_ACTION_LEFT_LU_TURN)))
        assertSame(ManeuverIcons.RightUTurn, laneSpecOf(dirs(LaneAction.LANE_ACTION_RIGHT_RU_TURN)))
        assertSame(ManeuverIcons.StraightUTurn, laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_LU_TURN)))
        assertSame(ManeuverIcons.UTurnLeftLane, laneSpecOf(dirs(LaneAction.LANE_ACTION_LU_TURN)))
        assertSame(ManeuverIcons.StraightLane, laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD)))
    }

    @Test
    fun slightManeuversUseFortyFiveDegreeForks() {
        assertEquals(true, isSlightManeuver(IconType.RIGHT_FRONT))
        assertEquals(true, isSlightManeuver(IconType.LEFT_FRONT))
        assertEquals(false, isSlightManeuver(IconType.RIGHT))
        assertSame(
            ManeuverIcons.ForkRightSlight,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_RIGHT), slight = true)
        )
        assertSame(
            ManeuverIcons.ForkLeftSlight,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT), slight = true)
        )
        assertSame(
            ManeuverIcons.SlightRightLane,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_RIGHT), slight = true)
        )
        assertSame(
            ManeuverIcons.SlightLeftLane,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_LEFT), slight = true)
        )
        assertSame(
            ManeuverIcons.TurnRightLane,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_RIGHT))
        )
        assertSame(
            ManeuverIcons.ForkRight,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_RIGHT))
        )
        assertSame(
            ManeuverIcons.ForkLeft,
            laneSpecOf(dirs(LaneAction.LANE_ACTION_AHEAD_LEFT))
        )
    }

    @Test
    fun maneuverDirections() {
        assertEquals(LaneDir.LEFT, activeLaneDir(IconType.LEFT_FRONT))
        assertEquals(LaneDir.LEFT, activeLaneDir(IconType.LEFT_BACK))
        assertEquals(LaneDir.RIGHT, activeLaneDir(IconType.RIGHT_FRONT))
        assertEquals(LaneDir.RIGHT, activeLaneDir(IconType.RIGHT_BACK))
        assertEquals(LaneDir.UTURN_LEFT, activeLaneDir(IconType.LEFT_TURN_AROUND))
        assertEquals(LaneDir.UTURN_RIGHT, activeLaneDir(IconType.U_TURN_RIGHT))
        // 并线进入主路按直行
        assertEquals(LaneDir.STRAIGHT, activeLaneDir(IconType.MERGE_LEFT))
        assertEquals(LaneDir.STRAIGHT, activeLaneDir(IconType.MERGE_RIGHT))
        assertEquals(LaneDir.STRAIGHT, activeLaneDir(IconType.STRAIGHT))
    }

    @Test
    fun highlightResolutionCoversFallbacksAndUTurnOnlyIndex() {
        val a = LaneAction.LANE_ACTION_AHEAD_LEFT
        val s = LaneAction.LANE_ACTION_AHEAD
        val l = LaneAction.LANE_ACTION_LEFT
        val r = LaneAction.LANE_ACTION_RIGHT
        val u = LaneAction.LANE_ACTION_LU_TURN
        val ur = LaneAction.LANE_ACTION_RU_TURN

        // 车道数据属于更近的直行路口（maneuver 在更远处右转）→ 回退直行
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.STRAIGHT), setOf(LaneDir.STRAIGHT))),
            resolveLaneHighlight(listOf(listOf(a), listOf(s)), LaneDir.RIGHT)
        )
        // 有车道支持 maneuver 方向时保持原方向（只给含该方向的车道着色）
        assertEquals(
            LaneHighlight(listOf(emptySet(), emptySet(), setOf(LaneDir.RIGHT))),
            resolveLaneHighlight(listOf(listOf(a), listOf(s), listOf(r)), LaneDir.RIGHT)
        )
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.UTURN_LEFT), emptySet())),
            resolveLaneHighlight(listOf(listOf(u), listOf(s)), LaneDir.UTURN_LEFT)
        )
        // 掉头无专用掉头车道 → 同侧转弯车道兜底，且只高亮**最左**那条左转道
        assertEquals(
            LaneHighlight(listOf(emptySet(), setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(listOf(listOf(s), listOf(l), listOf(l)), LaneDir.UTURN_LEFT)
        )
        // 右掉头 → 只高亮**最右**那条右转道
        assertEquals(
            LaneHighlight(listOf(emptySet(), setOf(LaneDir.RIGHT), emptySet())),
            resolveLaneHighlight(listOf(listOf(r), listOf(r), listOf(s)), LaneDir.UTURN_RIGHT)
        )
        // 有专用掉头车道时优先它，不走转弯兜底
        assertEquals(
            LaneHighlight(listOf(emptySet(), setOf(LaneDir.UTURN_RIGHT))),
            resolveLaneHighlight(listOf(listOf(l), listOf(ur)), LaneDir.UTURN_RIGHT)
        )
        // 没有直行车道且无车道含 maneuver 方向 → 整条车道条无可着色（全灰，
        // 与旧"方向着色但无分支命中"像素等价）
        assertEquals(
            LaneHighlight(listOf(emptySet(), emptySet())),
            resolveLaneHighlight(listOf(listOf(l), listOf(r)), LaneDir.STRAIGHT)
        )
        assertEquals(
            LaneHighlight(listOf(emptySet(), emptySet())),
            resolveLaneHighlight(listOf(listOf(r), listOf(r)), LaneDir.LEFT)
        )
        // 空车道条直接返回
        assertEquals(
            LaneHighlight(emptyList()),
            resolveLaneHighlight(emptyList(), LaneDir.LEFT)
        )
    }

    @Test
    fun enRouteHighlightsStraightWhenIntersectionsRemain() {
        val s = LaneAction.LANE_ACTION_AHEAD
        val l = LaneAction.LANE_ACTION_LEFT
        // 途中还有路口（linksToManeuver>=2）：车道数据属最近路口，本路口应直行
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.STRAIGHT) }),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT, linksToManeuver = 2
            )
        )
        // 下个路口即机动点：按 maneuver 方向
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT, linksToManeuver = 1
            )
        )
        // 途中但无直行车道：保持 maneuver 方向（无合理直行可高亮）
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.LEFT) }),
            resolveLaneHighlight(
                listOf(listOf(l), listOf(l)), LaneDir.LEFT, linksToManeuver = 3
            )
        )
    }

    @Test
    fun engineRecommendedLanesWinOverInference() {
        val al = LaneAction.LANE_ACTION_AHEAD_LEFT
        val s = LaneAction.LANE_ACTION_AHEAD
        val ar = LaneAction.LANE_ACTION_AHEAD_RIGHT
        val l = LaneAction.LANE_ACTION_LEFT

        // 岔口起点实测（2026-11 探测）：[直+左][直][直][直+右]，引擎仅推第 4 道
        // → 该道**整箭头**（直+右两分支）高亮，其余全灰；机动距离/计数信号被忽略
        assertEquals(
            LaneHighlight(
                listOf(emptySet(), emptySet(), emptySet(), setOf(LaneDir.STRAIGHT, LaneDir.RIGHT))
            ),
            resolveLaneHighlight(
                listOf(listOf(al), listOf(s), listOf(s), listOf(ar)), LaneDir.RIGHT,
                recommended = listOf(false, false, false, true),
                linksToManeuver = 2, stepRemainMeters = 358, linkRemainMeters = 47
            )
        )
        // 途中直行路口实测：全部车道推荐 → 全整箭头高亮（[直][直][直][直+右]）
        assertEquals(
            LaneHighlight(
                listOf(
                    setOf(LaneDir.STRAIGHT), setOf(LaneDir.STRAIGHT),
                    setOf(LaneDir.STRAIGHT), setOf(LaneDir.STRAIGHT, LaneDir.RIGHT)
                )
            ),
            resolveLaneHighlight(
                listOf(listOf(s), listOf(s), listOf(s), listOf(ar)), LaneDir.RIGHT,
                recommended = listOf(true, true, true, true),
                linksToManeuver = 2, stepRemainMeters = 1274, linkRemainMeters = 43
            )
        )
        // 掉头口（东大桥路实测形态）：引擎若推左道 → 整箭头左转高亮（掉头借道），
        // 不再受掉头环长度导致的归属误判影响
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet(), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l), listOf(s), listOf(s)), LaneDir.UTURN_LEFT,
                recommended = listOf(true, false, false),
                stepRemainMeters = 176, linkRemainMeters = 103
            )
        )
        // 分支精修（保留复合箭头两色定制）：机动点路口 + 推荐车道含机动方向 →
        // 仅机动分支高亮（[直+左] 推荐道在左转机动点 = 左分支蓝、直行分支灰）
        val al2 = LaneAction.LANE_ACTION_AHEAD_LEFT
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(al2), listOf(s)), LaneDir.LEFT,
                recommended = listOf(true, false),
                stepRemainMeters = 120, linkRemainMeters = 110
            )
        )
        // 同推荐车道在途中路口（机动更远）→ 整箭头同色（本路口动作非机动方向）
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.STRAIGHT, LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(al2), listOf(s)), LaneDir.LEFT,
                recommended = listOf(true, false),
                stepRemainMeters = 480, linkRemainMeters = 110
            )
        )
        // 引擎零推荐（全 false）或推荐列表与车道数不符 → 回退方向推导路径
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.STRAIGHT) }),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                recommended = listOf(false, false), linksToManeuver = 2
            )
        )
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                recommended = listOf(true), linksToManeuver = 1
            )
        )
    }

    @Test
    fun laneBarSuppressedWhenNoLaneServesManeuver() {
        val s = LaneAction.LANE_ACTION_AHEAD
        val ar = LaneAction.LANE_ACTION_AHEAD_RIGHT
        val l = LaneAction.LANE_ACTION_LEFT

        // 建华路实测形态：机动点路口、单条[直+右]车道组配左转机动 → 隐藏
        assertEquals(
            true,
            laneBarMisleadsManeuver(
                LaneDir.LEFT, listOf(listOf(ar)),
                stepRemainMeters = 132, linkRemainMeters = 105
            )
        )
        // 同车道组在途中路口（机动更远）→ 正常展示
        assertEquals(
            false,
            laneBarMisleadsManeuver(
                LaneDir.LEFT, listOf(listOf(ar)),
                stepRemainMeters = 480, linkRemainMeters = 105
            )
        )
        // 车道组含机动方向 → 展示（含引擎推荐/兜底高亮照常）
        assertEquals(
            false,
            laneBarMisleadsManeuver(
                LaneDir.LEFT, listOf(listOf(s), listOf(l)),
                stepRemainMeters = 132, linkRemainMeters = 105
            )
        )
        // 掉头机动：[左转][直行][直+右] 可借左转道完成掉头 → 展示（东大桥路形态）
        assertEquals(
            false,
            laneBarMisleadsManeuver(
                LaneDir.UTURN_LEFT, listOf(listOf(l), listOf(s), listOf(ar)),
                stepRemainMeters = 176, linkRemainMeters = 103
            )
        )
        // 掉头机动且无左/掉头车道 → 隐藏
        assertEquals(
            true,
            laneBarMisleadsManeuver(
                LaneDir.UTURN_LEFT, listOf(listOf(s), listOf(ar)),
                stepRemainMeters = 176, linkRemainMeters = 103
            )
        )
        // 空车道条不算误导（本来就不渲染）
        assertEquals(
            false,
            laneBarMisleadsManeuver(LaneDir.LEFT, emptyList(), 1, 100, 90)
        )
    }

    @Test
    fun misleadJunctionRendersAllGrayRegardlessOfSource() {
        val ar = LaneAction.LANE_ACTION_AHEAD_RIGHT
        val s = LaneAction.LANE_ACTION_AHEAD

        // 引擎推荐路径：[直+右] rec=true 配左转机动（建华路实测形态）→ 全灰，
        // 不显示推荐蓝（用户定稿：显示路面车道、由用户自行判断是否借道）
        assertEquals(
            LaneHighlight(listOf(emptySet())),
            resolveLaneHighlight(
                listOf(listOf(ar)), LaneDir.LEFT,
                recommended = listOf(true),
                stepRemainMeters = 132, linkRemainMeters = 105
            )
        )
        // 兜底路径同样全灰（不回退直行高亮——蓝色直行分支与左转卡同样矛盾）
        assertEquals(
            LaneHighlight(listOf(emptySet(), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(ar), listOf(s)), LaneDir.LEFT,
                stepRemainMeters = 132, linkRemainMeters = 105
            )
        )
        // 距离判定缺失（-1）时不触发全灰，保持老回退语义（直行）
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.STRAIGHT), setOf(LaneDir.STRAIGHT))),
            resolveLaneHighlight(
                listOf(listOf(ar), listOf(s)), LaneDir.LEFT,
                stepRemainMeters = 132, linkRemainMeters = -1
            )
        )
    }

    @Test
    fun distanceVerdictWinsOverLinkCount() {
        val s = LaneAction.LANE_ACTION_AHEAD
        val l = LaneAction.LANE_ACTION_LEFT

        // 用户事故场景（2026-11 实车）：机动点路口，link 计数误判 ≥2（step 尾部
        // 路口内部 link / curLink 偏移），距离判定车道条路口=机动点 → 必须左转
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                linksToManeuver = 2, stepRemainMeters = 120, linkRemainMeters = 110
            )
        )
        // 容差内（step 末端内部 link 一小截）仍算同一路口
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                linksToManeuver = 2, stepRemainMeters = 138, linkRemainMeters = 100
            )
        )
        // 容差按机动类型分档：掉头环完成弧长（东大桥路实测 73m）不再穿透——
        // 掉头借最左左转道高亮，不误判"途中"高亮直行
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet(), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s), listOf(s)), LaneDir.UTURN_LEFT,
                linksToManeuver = 2, stepRemainMeters = 176, linkRemainMeters = 103
            )
        )
        // 同样的 73m 差值对普通转向仍是途中（路口间距 ≥ 百米级）
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.STRAIGHT) }),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                linksToManeuver = 2, stepRemainMeters = 176, linkRemainMeters = 103
            )
        )
        // 掉头差值超过掉头容差（140m）仍判途中
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.STRAIGHT) }),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.UTURN_LEFT,
                linksToManeuver = 2, stepRemainMeters = 300, linkRemainMeters = 100
            )
        )
        // 途中路口：机动点远在车道条路口之后 → 直行（即使计数说是 1）
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.STRAIGHT) }),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                linksToManeuver = 1, stepRemainMeters = 600, linkRemainMeters = 150
            )
        )
        // 途中但无直行车道：保持 maneuver 方向
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.LEFT) }),
            resolveLaneHighlight(
                listOf(listOf(l), listOf(l)), LaneDir.LEFT,
                stepRemainMeters = 600, linkRemainMeters = 150
            )
        )
        // 引擎未上报 link 剩余距离（-1）→ 退回 link 计数老行为
        assertEquals(
            LaneHighlight(List(2) { setOf(LaneDir.STRAIGHT) }),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                linksToManeuver = 2, stepRemainMeters = 120, linkRemainMeters = -1
            )
        )
        assertEquals(
            LaneHighlight(listOf(setOf(LaneDir.LEFT), emptySet())),
            resolveLaneHighlight(
                listOf(listOf(l, s), listOf(s)), LaneDir.LEFT,
                linksToManeuver = 1, stepRemainMeters = 120, linkRemainMeters = -1
            )
        )
    }
}
