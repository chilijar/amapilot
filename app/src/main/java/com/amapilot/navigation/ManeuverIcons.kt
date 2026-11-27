package com.amapilot.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// ---- 车道图标路径（24×24，3px 杆，Material 45° 尾翼） ----

/** 车道方向（组合图标内逐方向着色：需走的方向高亮，其余灰色）。 */
enum class LaneDir { STRAIGHT, LEFT, RIGHT, UTURN_LEFT, UTURN_RIGHT }

/** 组合图标里的一条子路径及其所属方向；[dir] 为 null 表示**共享主干**（任一方向高亮时一起高亮）。 */
class LanePart(
    val dir: LaneDir?,
    val d: String,
    val fill: Boolean = true,
    val stroke: Boolean = false,
    val dx: Float = 0f,
    val mirror: Boolean = false
)

/**
 * 车道图标描述：若干条带方向标签的路径。[build] 按方向集上色（集合内=accent，
 * 其余=inactive，共享主干随任一高亮方向；集合含全部分支 = 整箭头高亮）。
 */
class LaneIconSpec(val name: String, val parts: List<LanePart>) {
    fun build(active: Set<LaneDir>, accent: Color, inactive: Color): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 命中方向最后绘制；共享主干再压在最上层，保证两个方向都“踩”到的底端主杆高亮
            val used = active.isNotEmpty() && parts.any { it.dir != null && it.dir in active }
            parts.sortedBy { p ->
                when {
                    p.dir == null -> 2
                    p.dir != null && p.dir in active -> 1
                    else -> 0
                }
            }.forEach { p ->
                val color = when {
                    p.dir == null -> if (used) accent else inactive
                    p.dir != null && p.dir in active -> accent
                    else -> inactive
                }
                group(
                    name = p.d.take(12),
                    scaleX = if (p.mirror) -1f else 1f,
                    translationX = if (p.mirror) 24f - p.dx else p.dx
                ) {
                    addPath(
                        pathData = PathParser().parsePathString(p.d).toNodes(),
                        fill = if (p.fill) SolidColor(color) else null,
                        stroke = if (p.stroke) SolidColor(color) else null,
                        // stroke 部件杆宽：与 fill 剪影的 2/24 杆视觉等重（3/24 ≈ 剪影+抗锯齿占位）
                        strokeLineWidth = if (p.stroke) 3f else 0f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Round
                    )
                }
            }
        }.build()
}

/** 单方向车道路径（官方 Material straight / turn_* / u_turn_*）。 */
private const val STRAIGHT_D =
    "M11,6.83 L9.41,8.41 L8,7 L12,3 L16,7 L14.59,8.41 L13,6.83 L13,21 L11,21 Z"

private const val TURN_LEFT_D =
    "M6.83,11 l1.59,1.59 L7,14 l-4,-4 l4,-4 l1.41,1.41 L6.83,9 L15,9 " +
        "c1.1,0 2,0.9 2,2 v9 h-2 v-9 L6.83,11 Z"

private const val TURN_RIGHT_D =
    "M17.17,11 l-1.59,1.59 L17,14 l4,-4 l-4,-4 l-1.41,1.41 L17.17,9 L9,9 " +
        "c-1.1,0 -2,0.9 -2,2 v9 h2 v-9 L17.17,11 Z"

private const val UTURN_LEFT_D =
    "M18,9 v12 h-2 V9 c0,-2.21 -1.79,-4 -4,-4 s-4,1.79 -4,4 v4.17 " +
        "l1.59,-1.59 L11,13 l-4,4 l-4,-4 l1.41,-1.41 L6,13.17 V9 " +
        "c0,-3.31 2.69,-6 6,-6 s6,2.69 6,6 z"

private const val UTURN_RIGHT_D =
    "M6,9 v12 h2 V9 c0,-2.21 1.79,-4 4,-4 s4,1.79 4,4 v4.17 " +
        "l-1.59,-1.59 L13,13 l4,4 l4,-4 l-1.41,-1.41 L18,13.17 V9 " +
        "c0,-3.31 -2.69,-6 -6,-6 s-6,2.69 -6,6 z"

/** 官方 fork_left 的左分支（与右移 2 的 straight 拼出官方 fork_left）。 */
private const val FORK_LEFT_BRANCH_D =
    "M9.41,15.59 L8,17 L4,13 L8,9 L9.41,10.41 L7.83,12 " +
        "C9.34,11.67 11.56,12.08 13,13.36 L13,17 " +
        "C12.27,14.42 9.93,13.53 7.83,14 Z"

/** 官方 fork_right 的右分支（与左移 2 的 straight 拼出官方 fork_right）。 */
private const val FORK_RIGHT_BRANCH_D =
    "M14.59,15.59 L16,17 L20,13 L16,9 L14.59,10.41 L16.17,12 " +
        "C14.66,11.67 12.44,12.08 11,13.36 L11,17 " +
        "C11.73,14.42 14.07,13.53 16.17,14 Z"

/** fork 车道的共享底杆（[LanePart.dir] = null）：任一方向高亮时一起点亮。 */
private const val FORK_LEFT_TRUNK_D = "M13,13.36 L13,21 L15,21 L15,13.36 Z"

/** fork_right 的共享底杆（直行杆 x=9..11）。 */
private const val FORK_RIGHT_TRUNK_D = "M9,13.36 L9,21 L11,21 L11,13.36 Z"

/** 右前方 45° 分岔（maneuver = RIGHT_FRONT 时替换 fork_right 分支）。 */
private const val SLIGHT_BRANCH_R_D = "M10,15 L16.3,9.5"
private const val SLIGHT_HEAD_R_D =
    "M18.23,7.57 L18.23,13.23 L15.54,10.26 L12.57,7.57 Z"

/** 左前方（45° 分岔，[SLIGHT_BRANCH_R_D] 镜像）。 */
private const val SLIGHT_BRANCH_L_D = "M14,15 L7.7,9.5"
private const val SLIGHT_HEAD_L_D =
    "M5.77,7.57 L5.77,13.23 L8.46,10.26 L11.43,7.57 Z"

private const val LR_HEAD_L_D =
    "M6.83,11 L8.42,12.59 L7,14 L3,10 L7,6 L8.41,7.41 L6.83,9 Z"

private const val LR_HEAD_R_D =
    "M17.17,11 L15.58,12.59 L17,14 L21,10 L17,6 L15.59,7.41 L17.17,9 Z"

/** 左转+右转的左臂（stroke 部件，平滑收进主杆）。 */
private const val LR_ARM_L_D = "M6.83,10 L7,10 C10,10 12,12 12,15 L12,21"

private const val LR_ARM_R_D = "M17.17,10 L17,10 C14,10 12,12 12,15 L12,21"

private const val U_LOOP_BIG_D =
    "M13,15 V14 A4,4 0 0 0 5,14 V15.5 H7 V14 A2,2 0 0 1 11,14 V15 Z"

private const val U_HEAD_DOWN_D =
    "M5,15 L3.41,13.41 L2,14.83 L6,18.83 L10,14.83 L8.41,13.41 L7,15 Z"

/** 直行+掉头：U 环汇入直行杆后的共享底杆（y=15..21）。 */
private const val SUT_LEFT_TRUNK_D = "M13,15 L13,21 L15,21 L15,15 Z"
private const val SUT_RIGHT_TRUNK_D = "M9,15 L9,21 L11,21 L11,15 Z"

// ---- 直行+左转+右转：直行箭头 + 左右平滑 Y 臂（臂高 y=13） ----

private const val SLR_HEAD_L_D =
    "M6.83,14 L8.42,15.59 L7,17 L3,13 L7,9 L8.41,10.41 L6.83,12 Z"

private const val SLR_HEAD_R_D =
    "M17.17,14 L15.58,15.59 L17,17 L21,13 L17,9 L15.59,10.41 L17.17,12 Z"

private const val SLR_ARM_L_D = "M6.83,13 L7,13 C10,13 12,15 12,18 L12,21"

private const val SLR_ARM_R_D = "M17.17,13 L17,13 C14,13 12,15 12,18 L12,21"

// ---- 左转+左掉头：参考路标结构，Material 曲线 ----

/** 顶部左转（Material turn_left，横杆 y5..7，右杆 x16..18）。 */
private const val LUL_TURN_LEFT_D =
    "M7.83,7 l1.59,1.59 L8,10 l-4,-4 l4,-4 l1.41,1.41 L7.83,5 L16,5 " +
        "c1.1,0 2,0.9 2,2 v9 h-2 v-9 L7.83,7 Z"

/** 下方 U 形回环 + 向下箭头（Material u_turn_left 下移，掉头端缩短到与右杆底对齐）。 */
private const val LUL_UTURN_D =
    "M18,15 v6 h-2 V15 c0,-2.21 -1.79,-4 -4,-4 s-4,1.79 -4,4 v2.17 " +
        "l1.59,-1.59 L11,17 l-4,4 l-4,-4 l1.41,-1.41 L6,17.17 V15 " +
        "c0,-3.31 2.69,-6 6,-6 s6,2.69 6,6 z"

/** 左转+左掉头：下方 U 环的右腿即入口主干，左转方向也要走这一段。 */
private const val LUT_LEFT_TRUNK_D = "M16,15 L16,21 L18,21 L18,15 Z"
private const val LUT_RIGHT_TRUNK_D = "M6,15 L6,21 L8,21 L8,15 Z"


/**
 * 导航转向图标（官方 Material 机动点图标，Google Maps 导航同款）。
 * 路径取自 google/material-design-icons maps 分类，脚本转 Compose ImageVector。
 */
object ManeuverIcons {
    val TurnLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "TurnLeft", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.83f, 11f)
                lineToRelative(1.59f, 1.59f)
                lineTo(7f, 14f)
                lineToRelative(-4f, -4f)
                lineToRelative(4f, -4f)
                lineToRelative(1.41f, 1.41f)
                lineTo(6.83f, 9f)
                lineTo(15f, 9f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                verticalLineToRelative(9f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-9f)
                lineTo(6.83f, 11f)
                close()
            }
        }.build()
    }

    val TurnRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "TurnRight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.17f, 11f)
                lineToRelative(-1.59f, 1.59f)
                lineTo(17f, 14f)
                lineToRelative(4f, -4f)
                lineToRelative(-4f, -4f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(17.17f, 9f)
                lineTo(9f, 9f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(9f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-9f)
                lineTo(17.17f, 11f)
                close()
            }
        }.build()
    }

    val TurnSlightLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "TurnSlightLeft", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.66f, 6f)
                verticalLineTo(4f)
                horizontalLineTo(6f)
                verticalLineToRelative(5.66f)
                horizontalLineToRelative(2f)
                verticalLineTo(7.41f)
                lineToRelative(5f, 5f)
                verticalLineTo(20f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-7.58f)
                curveToRelative(0f, -0.53f, -0.21f, -1.04f, -0.59f, -1.41f)
                lineToRelative(-5f, -5f)
                horizontalLineTo(11.66f)
                close()
            }
        }.build()
    }

    val TurnSlightRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "TurnSlightRight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.34f, 6f)
                verticalLineTo(4f)
                horizontalLineTo(18f)
                verticalLineToRelative(5.66f)
                horizontalLineToRelative(-2f)
                verticalLineTo(7.41f)
                lineToRelative(-5f, 5f)
                verticalLineTo(20f)
                horizontalLineTo(9f)
                verticalLineToRelative(-7.58f)
                curveToRelative(0f, -0.53f, 0.21f, -1.04f, 0.59f, -1.41f)
                lineToRelative(5f, -5f)
                horizontalLineTo(12.34f)
                close()
            }
        }.build()
    }

    val TurnSharpLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "TurnSharpLeft", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 6.83f)
                lineTo(4.41f, 8.41f)
                lineTo(3f, 7f)
                lineToRelative(4f, -4f)
                lineToRelative(4f, 4f)
                lineTo(9.59f, 8.41f)
                lineTo(8f, 6.83f)
                verticalLineTo(13f)
                horizontalLineToRelative(8f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                horizontalLineTo(8f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                verticalLineTo(6.83f)
                close()
            }
        }.build()
    }

    val TurnSharpRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "TurnSharpRight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 6.83f)
                lineToRelative(1.59f, 1.59f)
                lineTo(21f, 7f)
                lineToRelative(-4f, -4f)
                lineToRelative(-4f, 4f)
                lineToRelative(1.41f, 1.41f)
                lineTo(16f, 6.83f)
                verticalLineTo(13f)
                horizontalLineTo(8f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-6f)
                horizontalLineToRelative(8f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(6.83f)
                close()
            }
        }.build()
    }

    val UTurnLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "UTurnLeft", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 9f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(-2f)
                verticalLineTo(9f)
                curveToRelative(0f, -2.21f, -1.79f, -4f, -4f, -4f)
                reflectiveCurveTo(8f, 6.79f, 8f, 9f)
                verticalLineToRelative(4.17f)
                lineToRelative(1.59f, -1.59f)
                lineTo(11f, 13f)
                lineToRelative(-4f, 4f)
                lineToRelative(-4f, -4f)
                lineToRelative(1.41f, -1.41f)
                lineTo(6f, 13.17f)
                verticalLineTo(9f)
                curveToRelative(0f, -3.31f, 2.69f, -6f, 6f, -6f)
                reflectiveCurveTo(18f, 5.69f, 18f, 9f)
                close()
            }
        }.build()
    }

    val UTurnRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "UTurnRight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 9f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(2f)
                verticalLineTo(9f)
                curveToRelative(0f, -2.21f, 1.79f, -4f, 4f, -4f)
                reflectiveCurveToRelative(4f, 1.79f, 4f, 4f)
                verticalLineToRelative(4.17f)
                lineToRelative(-1.59f, -1.59f)
                lineTo(13f, 13f)
                lineToRelative(4f, 4f)
                lineToRelative(4f, -4f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(18f, 13.17f)
                verticalLineTo(9f)
                curveToRelative(0f, -3.31f, -2.69f, -6f, -6f, -6f)
                reflectiveCurveTo(6f, 5.69f, 6f, 9f)
                close()
            }
        }.build()
    }

    val Straight: ImageVector by lazy {
        ImageVector.Builder(
            name = "Straight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 6.83f)
                lineTo(9.41f, 8.41f)
                lineTo(8f, 7f)
                lineTo(12f, 3f)
                lineTo(16f, 7f)
                lineTo(14.59f, 8.41f)
                lineTo(13f, 6.83f)
                lineTo(13f, 21f)
                lineTo(11f, 21f)
                close()
            }
        }.build()
    }

    /** 环岛左转出口（官方 Material roundabout_left）。 */
    val RoundaboutLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "RoundaboutLeft", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.08f, 8f)
                curveToRelative(0.48f, -2.84f, 2.94f, -5f, 5.92f, -5f)
                curveToRelative(3.31f, 0f, 6f, 2.69f, 6f, 6f)
                curveToRelative(0f, 2.97f, -2.16f, 5.44f, -5f, 5.92f)
                lineTo(17f, 21f)
                horizontalLineToRelative(-2f)
                lineToRelative(0f, -6.09f)
                curveToRelative(0f, -0.98f, 0.71f, -1.8f, 1.67f, -1.97f)
                curveTo(18.56f, 12.63f, 20f, 10.98f, 20f, 9f)
                curveToRelative(0f, -2.21f, -1.79f, -4f, -4f, -4f)
                curveToRelative(-1.98f, 0f, -3.63f, 1.44f, -3.94f, 3.33f)
                curveTo(11.89f, 9.29f, 11.07f, 10f, 10.09f, 10f)
                lineToRelative(-4.26f, 0f)
                lineToRelative(1.59f, 1.59f)
                lineTo(6f, 13f)
                lineTo(2f, 9f)
                lineToRelative(4f, -4f)
                lineToRelative(1.41f, 1.41f)
                lineTo(5.83f, 8f)
                lineTo(10.08f, 8f)
                close()
            }
        }.build()
    }

    /** 环岛右转出口（官方 Material roundabout_right）。 */
    val RoundaboutRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "RoundaboutRight", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(13.92f, 8f)
                curveTo(13.44f, 5.16f, 10.97f, 3f, 8f, 3f)
                curveTo(4.69f, 3f, 2f, 5.69f, 2f, 9f)
                curveToRelative(0f, 2.97f, 2.16f, 5.44f, 5f, 5.92f)
                lineTo(7f, 21f)
                horizontalLineToRelative(2f)
                lineToRelative(0f, -6.09f)
                curveToRelative(0f, -0.98f, -0.71f, -1.8f, -1.67f, -1.97f)
                curveTo(5.44f, 12.63f, 4f, 10.98f, 4f, 9f)
                curveToRelative(0f, -2.21f, 1.79f, -4f, 4f, -4f)
                curveToRelative(1.98f, 0f, 3.63f, 1.44f, 3.94f, 3.33f)
                curveTo(12.11f, 9.29f, 12.93f, 10f, 13.91f, 10f)
                lineToRelative(4.26f, 0f)
                lineToRelative(-1.59f, 1.59f)
                lineTo(18f, 13f)
                lineToRelative(4f, -4f)
                lineToRelative(-4f, -4f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(18.17f, 8f)
                lineTo(13.92f, 8f)
                close()
            }
        }.build()
    }

    /** 汇入（官方 Material merge，左右汇入共用）。 */
    val Merge: ImageVector by lazy {
        ImageVector.Builder(
            name = "Merge", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.41f, 21f)
                lineTo(5f, 19.59f)
                lineToRelative(4.83f, -4.83f)
                curveToRelative(0.75f, -0.75f, 1.17f, -1.77f, 1.17f, -2.83f)
                verticalLineToRelative(-5.1f)
                lineTo(9.41f, 8.41f)
                lineTo(8f, 7f)
                lineToRelative(4f, -4f)
                lineToRelative(4f, 4f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(13f, 6.83f)
                verticalLineToRelative(5.1f)
                curveToRelative(0f, 1.06f, 0.42f, 2.08f, 1.17f, 2.83f)
                lineTo(19f, 19.59f)
                lineTo(17.59f, 21f)
                lineTo(12f, 15.41f)
                lineTo(6.41f, 21f)
                close()
            }
        }.build()
    }

    /** 服务区（官方 Material local_gas_station）。 */
    val LocalGasStation: ImageVector by lazy {
        ImageVector.Builder(
            name = "LocalGasStation", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveToRelative(19.77f, 7.23f)
                lineToRelative(0.01f, -0.01f)
                lineToRelative(-3.72f, -3.72f)
                lineTo(15f, 4.56f)
                lineToRelative(2.11f, 2.11f)
                curveToRelative(-0.94f, 0.36f, -1.61f, 1.26f, -1.61f, 2.33f)
                curveToRelative(0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
                curveToRelative(0.36f, 0f, 0.69f, -0.08f, 1f, -0.21f)
                verticalLineToRelative(7.21f)
                curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
                reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f)
                verticalLineTo(14f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(-1f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineTo(6f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(16f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(-7.5f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(5f)
                curveToRelative(0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
                reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
                verticalLineTo(9f)
                curveToRelative(0f, -0.69f, -0.28f, -1.32f, -0.73f, -1.77f)
                close()
                moveTo(12f, 10f)
                horizontalLineTo(6f)
                verticalLineTo(5f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(5f)
                close()
                moveToRelative(6f, 0f)
                curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                reflectiveCurveToRelative(0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                close()
            }
        }.build()
    }

    // ---------------- 车道图标描述（逐方向着色） ----------------

    val StraightLane = LaneIconSpec("StraightLane", listOf(LanePart(LaneDir.STRAIGHT, STRAIGHT_D)))
    val TurnLeftLane = LaneIconSpec("TurnLeftLane", listOf(LanePart(LaneDir.LEFT, TURN_LEFT_D)))
    val TurnRightLane = LaneIconSpec("TurnRightLane", listOf(LanePart(LaneDir.RIGHT, TURN_RIGHT_D)))
    val UTurnLeftLane =
        LaneIconSpec("UTurnLeftLane", listOf(LanePart(LaneDir.UTURN_LEFT, UTURN_LEFT_D)))
    val UTurnRightLane =
        LaneIconSpec("UTurnRightLane", listOf(LanePart(LaneDir.UTURN_RIGHT, UTURN_RIGHT_D)))

    /** 直行 + 左转（官方 fork_left 拆成两条路径，便于分别着色）。 */
    val ForkLeft = LaneIconSpec(
        "ForkLeft",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D, dx = 2f),
            LanePart(LaneDir.LEFT, FORK_LEFT_BRANCH_D),
            LanePart(null, FORK_LEFT_TRUNK_D)
        )
    )

    /** 直行 + 右转（官方 fork_right 拆分）。 */
    val ForkRight = LaneIconSpec(
        "ForkRight",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D, dx = -2f),
            LanePart(LaneDir.RIGHT, FORK_RIGHT_BRANCH_D),
            LanePart(null, FORK_RIGHT_TRUNK_D)
        )
    )

    /** 直行 + 右前方（45° 分岔）：仅用于 maneuver = RIGHT_FRONT。 */
    val ForkRightSlight = LaneIconSpec(
        "ForkRightSlight",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D, dx = -2f),
            LanePart(LaneDir.RIGHT, SLIGHT_BRANCH_R_D, fill = false, stroke = true),
            LanePart(LaneDir.RIGHT, SLIGHT_HEAD_R_D),
            LanePart(null, FORK_RIGHT_TRUNK_D)
        )
    )

    /** 直行 + 左前方（45° 分岔）：仅用于 maneuver = LEFT_FRONT。 */
    val ForkLeftSlight = LaneIconSpec(
        "ForkLeftSlight",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D, dx = 2f),
            LanePart(LaneDir.LEFT, SLIGHT_BRANCH_L_D, fill = false, stroke = true),
            LanePart(LaneDir.LEFT, SLIGHT_HEAD_L_D),
            LanePart(null, FORK_LEFT_TRUNK_D)
        )
    )

    /** 纯右前方车道（45°）：仅用于 maneuver = RIGHT_FRONT。 */
    val SlightRightLane = LaneIconSpec(
        "SlightRightLane",
        listOf(
            LanePart(LaneDir.RIGHT, FORK_RIGHT_TRUNK_D),
            LanePart(LaneDir.RIGHT, SLIGHT_BRANCH_R_D, fill = false, stroke = true),
            LanePart(LaneDir.RIGHT, SLIGHT_HEAD_R_D)
        )
    )

    /** 纯左前方车道（45°）：仅用于 maneuver = LEFT_FRONT。 */
    val SlightLeftLane = LaneIconSpec(
        "SlightLeftLane",
        listOf(
            LanePart(LaneDir.LEFT, FORK_LEFT_TRUNK_D),
            LanePart(LaneDir.LEFT, SLIGHT_BRANCH_L_D, fill = false, stroke = true),
            LanePart(LaneDir.LEFT, SLIGHT_HEAD_L_D)
        )
    )

    /** 左转 + 右转：平滑 Y 形。 */
    val LeftRight = LaneIconSpec(
        "LeftRight",
        listOf(
            LanePart(LaneDir.LEFT, LR_HEAD_L_D),
            LanePart(LaneDir.RIGHT, LR_HEAD_R_D),
            LanePart(LaneDir.LEFT, LR_ARM_L_D, fill = false, stroke = true),
            LanePart(LaneDir.RIGHT, LR_ARM_R_D, fill = false, stroke = true)
        )
    )

    /** 直行 + 掉头：直行箭头 + 大 U 环。 */
    val StraightUTurn = LaneIconSpec(
        "StraightUTurn",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D, dx = 2f),
            LanePart(LaneDir.UTURN_LEFT, U_LOOP_BIG_D, dx = 2f),
            LanePart(LaneDir.UTURN_LEFT, U_HEAD_DOWN_D, dx = 2f),
            LanePart(null, SUT_LEFT_TRUNK_D)
        )
    )

    /** 直行 + 右掉头（[StraightUTurn] 镜像）。 */
    val StraightUTurnRight = LaneIconSpec(
        "StraightUTurnRight",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D, dx = 2f, mirror = true),
            LanePart(LaneDir.UTURN_RIGHT, U_LOOP_BIG_D, dx = 2f, mirror = true),
            LanePart(LaneDir.UTURN_RIGHT, U_HEAD_DOWN_D, dx = 2f, mirror = true),
            LanePart(null, SUT_RIGHT_TRUNK_D)
        )
    )

    /** 左转 + 左掉头：参考路标结构（顶部左转 + 下方 U 环）。 */
    val LeftUTurn = LaneIconSpec(
        "LeftUTurn",
        listOf(
            LanePart(LaneDir.LEFT, LUL_TURN_LEFT_D),
            LanePart(LaneDir.UTURN_LEFT, LUL_UTURN_D),
            LanePart(null, LUT_LEFT_TRUNK_D)
        )
    )

    /** 右转 + 右掉头（[LeftUTurn] 镜像）。 */
    val RightUTurn = LaneIconSpec(
        "RightUTurn",
        listOf(
            LanePart(LaneDir.RIGHT, LUL_TURN_LEFT_D, mirror = true),
            LanePart(LaneDir.UTURN_RIGHT, LUL_UTURN_D, mirror = true),
            LanePart(null, LUT_RIGHT_TRUNK_D)
        )
    )

    /** 直行 + 左转 + 右转：直行箭头 + 左右平滑 Y 臂（y=13）。 */
    val StraightLeftRight = LaneIconSpec(
        "StraightLeftRight",
        listOf(
            LanePart(LaneDir.STRAIGHT, STRAIGHT_D),
            LanePart(LaneDir.LEFT, SLR_HEAD_L_D),
            LanePart(LaneDir.RIGHT, SLR_HEAD_R_D),
            LanePart(LaneDir.LEFT, SLR_ARM_L_D, fill = false, stroke = true),
            LanePart(LaneDir.RIGHT, SLR_ARM_R_D, fill = false, stroke = true)
        )
    )

    /**
     * AMap [com.amap.api.navi.enums.IconType] → 官方 Material 转向图标。
     * 驾车类型全覆盖；非驾车图标与语义中性类型回退直行。
     */
    fun maneuverIcon(iconType: Int): ImageVector = when (iconType) {
        com.amap.api.navi.enums.IconType.LEFT -> TurnLeft
        com.amap.api.navi.enums.IconType.RIGHT -> TurnRight
        com.amap.api.navi.enums.IconType.LEFT_FRONT -> TurnSlightLeft
        com.amap.api.navi.enums.IconType.RIGHT_FRONT -> TurnSlightRight
        // 左后方/右后方按普通左/右转渲染（急转图标与实际路口形态不符；车道语义本就归 LEFT/RIGHT）
        com.amap.api.navi.enums.IconType.LEFT_BACK -> TurnLeft
        com.amap.api.navi.enums.IconType.RIGHT_BACK -> TurnRight
        com.amap.api.navi.enums.IconType.LEFT_TURN_AROUND -> UTurnLeft
        com.amap.api.navi.enums.IconType.U_TURN_RIGHT -> UTurnRight
        com.amap.api.navi.enums.IconType.MERGE_LEFT,
        com.amap.api.navi.enums.IconType.MERGE_RIGHT -> Merge
        com.amap.api.navi.enums.IconType.ENTER_ROUNDABOUT,
        com.amap.api.navi.enums.IconType.ENTRY_LEFT_RING,
        com.amap.api.navi.enums.IconType.ENTRY_RING_LEFT,
        com.amap.api.navi.enums.IconType.ENTRY_LEFT_RING_LEFT,
        com.amap.api.navi.enums.IconType.ENTRY_RING_CONTINUE,
        com.amap.api.navi.enums.IconType.ENTRY_LEFT_RING_CONTINUE,
        com.amap.api.navi.enums.IconType.ENTRY_LEFTRINGU_TURN -> RoundaboutLeft
        com.amap.api.navi.enums.IconType.OUT_ROUNDABOUT,
        com.amap.api.navi.enums.IconType.LEAVE_LEFT_RING,
        com.amap.api.navi.enums.IconType.ENTRY_RING_RIGHT,
        com.amap.api.navi.enums.IconType.ENTRY_LEFT_RING_RIGHT -> RoundaboutRight
        com.amap.api.navi.enums.IconType.ENTRY_RING_UTURN -> UTurnLeft
        com.amap.api.navi.enums.IconType.ARRIVED_SERVICE_AREA -> LocalGasStation
        com.amap.api.navi.enums.IconType.ARRIVED_DESTINATION,
        com.amap.api.navi.enums.IconType.ARRIVED_WAYPOINT,
        com.amap.api.navi.enums.IconType.ARRIVED_TOLLGATE ->
            com.amapilot.ui.theme.GmIcons.Place
        else -> Straight
    }
}
