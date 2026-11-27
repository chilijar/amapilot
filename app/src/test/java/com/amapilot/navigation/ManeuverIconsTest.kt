package com.amapilot.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 复合车道图标：路径必须能被 Compose PathParser 解析并构建成 24×24 ImageVector。 */
class ManeuverIconsTest {

    @Test
    fun laneCompositeIconsBuild() {
        val specs = mapOf(
            "ForkLeft" to ManeuverIcons.ForkLeft,
            "ForkRight" to ManeuverIcons.ForkRight,
            "ForkLeftSlight" to ManeuverIcons.ForkLeftSlight,
            "ForkRightSlight" to ManeuverIcons.ForkRightSlight,
            "SlightLeftLane" to ManeuverIcons.SlightLeftLane,
            "SlightRightLane" to ManeuverIcons.SlightRightLane,
            "LeftRight" to ManeuverIcons.LeftRight,
            "StraightUTurn" to ManeuverIcons.StraightUTurn,
            "StraightUTurnRight" to ManeuverIcons.StraightUTurnRight,
            "LeftUTurn" to ManeuverIcons.LeftUTurn,
            "RightUTurn" to ManeuverIcons.RightUTurn,
            "StraightLeftRight" to ManeuverIcons.StraightLeftRight
        )
        specs.forEach { (name, spec) ->
            LaneDir.entries.forEach { active ->
                val icon = spec.build(setOf(active), Color.Black, Color.Gray)
                assertEquals(name, 24f, icon.viewportWidth)
                assertEquals(name, 24f, icon.viewportHeight)
                assertTrue("$name 应有绘制内容", icon.root.size > 0)
            }
        }
    }

    /** 当前方向高亮：所有子路径中必须同时出现高亮色与灰色，且高亮部件最后绘制（覆盖共享主干）。 */
    @Test
    fun laneIconHighlightsOnlyActiveDirection() {
        val accent = Color(0xFF1A73E8)
        val inactive = Color(0xFF9AA0A6)
        val cases = listOf(
            ManeuverIcons.ForkLeft to setOf(LaneDir.STRAIGHT, LaneDir.LEFT),
            ManeuverIcons.LeftRight to setOf(LaneDir.LEFT, LaneDir.RIGHT),
            ManeuverIcons.StraightLeftRight to setOf(LaneDir.STRAIGHT, LaneDir.LEFT, LaneDir.RIGHT),
            ManeuverIcons.StraightUTurn to setOf(LaneDir.STRAIGHT, LaneDir.UTURN_LEFT)
        )
        cases.forEach { (spec, dirs) ->
            dirs.forEach { active ->
                val icon = spec.build(setOf(active), accent, inactive)
                val colors = collectColors(icon.root)
                assertTrue("${spec.name}/$active 应含高亮色", colors.contains(accent))
                assertTrue("${spec.name}/$active 应含灰色部件", colors.contains(inactive))
                assertEquals("${spec.name}/$active 最后绘制的部件应高亮", accent, colors.last())
            }
        }
    }

    /** 共享底杆：直行/分叉两个方向高亮时都要点亮，无关方向则整块灰。 */
    @Test
    fun sharedTrunkHighlightsWithEitherDirection() {
        val accent = Color(0xFF1A73E8)
        val inactive = Color(0xFF9AA0A6)
        fun accents(spec: LaneIconSpec, dir: LaneDir) =
            collectColors(spec.build(setOf(dir), accent, inactive).root).count { it == accent }

        // fork：直行路径 + 底杆；下移头 + 弧线臂 + 底杆
        assertEquals(2, accents(ManeuverIcons.ForkLeft, LaneDir.STRAIGHT))
        assertEquals(3, accents(ManeuverIcons.ForkLeft, LaneDir.LEFT))
        assertEquals(0, accents(ManeuverIcons.ForkLeft, LaneDir.RIGHT))
        assertEquals(2, accents(ManeuverIcons.ForkRight, LaneDir.STRAIGHT))
        assertEquals(3, accents(ManeuverIcons.ForkRight, LaneDir.RIGHT))
        // 45° 变体：直行路径 + 底杆；斜分支 + 箭头 + 底杆
        assertEquals(2, accents(ManeuverIcons.ForkRightSlight, LaneDir.STRAIGHT))
        assertEquals(3, accents(ManeuverIcons.ForkRightSlight, LaneDir.RIGHT))
        assertEquals(3, accents(ManeuverIcons.ForkLeftSlight, LaneDir.LEFT))
        // 纯左前方/右前方单车道：只有这一个方向，整条点亮
        assertEquals(3, accents(ManeuverIcons.SlightRightLane, LaneDir.RIGHT))
        assertEquals(0, accents(ManeuverIcons.SlightRightLane, LaneDir.STRAIGHT))
        assertEquals(3, accents(ManeuverIcons.SlightLeftLane, LaneDir.LEFT))

        // 直行+掉头：直行 + 底杆；U 环 + 箭头 + 底杆
        assertEquals(2, accents(ManeuverIcons.StraightUTurn, LaneDir.STRAIGHT))
        assertEquals(3, accents(ManeuverIcons.StraightUTurn, LaneDir.UTURN_LEFT))

        // 左+左掉头：顶部左转 + 入口主干；U 环 + 入口主干
        assertEquals(2, accents(ManeuverIcons.LeftUTurn, LaneDir.LEFT))
        assertEquals(2, accents(ManeuverIcons.LeftUTurn, LaneDir.UTURN_LEFT))
        assertEquals(0, accents(ManeuverIcons.LeftUTurn, LaneDir.RIGHT))
    }

    /** 右前方/左前方映射到 TurnSlightRight/Left（官方 Material 图标），且能正常构建。 */
    @Test
    fun slightTurnIconsMapAndBuild() {
        val right = ManeuverIcons.maneuverIcon(com.amap.api.navi.enums.IconType.RIGHT_FRONT)
        val left = ManeuverIcons.maneuverIcon(com.amap.api.navi.enums.IconType.LEFT_FRONT)
        assertSame(ManeuverIcons.TurnSlightRight, right)
        assertSame(ManeuverIcons.TurnSlightLeft, left)
        assertEquals(24f, right.viewportWidth)
        assertEquals(24f, left.viewportWidth)
        assertTrue(right.root.size > 0)
        assertTrue(left.root.size > 0)
    }

    /** 左后方/右后方（急转）按普通左/右转渲染（用户定稿 2026-11：急转图标
     *  与实际路口形态不符；车道语义本就归 LEFT/RIGHT）。 */
    @Test
    fun sharpTurnIconsMapToRegularTurns() {
        assertSame(
            ManeuverIcons.TurnLeft,
            ManeuverIcons.maneuverIcon(com.amap.api.navi.enums.IconType.LEFT_BACK)
        )
        assertSame(
            ManeuverIcons.TurnRight,
            ManeuverIcons.maneuverIcon(com.amap.api.navi.enums.IconType.RIGHT_BACK)
        )
    }

    private fun collectColors(group: VectorGroup): List<Color> {
        val out = mutableListOf<Color>()
        for (i in 0 until group.size) appendColor(group[i], out)
        return out
    }

    private fun appendColor(node: VectorNode, out: MutableList<Color>) {
        when (node) {
            is VectorGroup -> for (i in 0 until node.size) appendColor(node[i], out)
            is VectorPath -> {
                (node.fill as? SolidColor)?.let { out.add(it.value) }
                (node.stroke as? SolidColor)?.let { out.add(it.value) }
            }
        }
    }
}
