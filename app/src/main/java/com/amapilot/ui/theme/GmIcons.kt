package com.amapilot.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Google Maps 风格自定义图标：未引入 material-icons-extended（避免 APK 膨胀），
 *  按需内联核心图标的 Material 矢量路径。 */
object GmIcons {

    /** 定位/回中（官方 Material my_location），Google Maps 右下角按钮。 */
    val MyLocation: ImageVector by lazy {
        ImageVector.Builder(
            name = "MyLocation", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 8f)
                curveToRelative(-2.21f, 0f, -4f, 1.79f, -4f, 4f)
                reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
                reflectiveCurveToRelative(4f, -1.79f, 4f, -4f)
                reflectiveCurveToRelative(-1.79f, -4f, -4f, -4f)
                close()
                moveToRelative(8.94f, 3f)
                curveToRelative(-0.46f, -4.17f, -3.77f, -7.48f, -7.94f, -7.94f)
                verticalLineTo(1f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2.06f)
                curveTo(6.83f, 3.52f, 3.52f, 6.83f, 3.06f, 11f)
                horizontalLineTo(1f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2.06f)
                curveToRelative(0.46f, 4.17f, 3.77f, 7.48f, 7.94f, 7.94f)
                verticalLineTo(23f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2.06f)
                curveToRelative(4.17f, -0.46f, 7.48f, -3.77f, 7.94f, -7.94f)
                horizontalLineTo(23f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-2.06f)
                close()
                moveTo(12f, 19f)
                curveToRelative(-3.87f, 0f, -7f, -3.13f, -7f, -7f)
                reflectiveCurveToRelative(3.13f, -7f, 7f, -7f)
                reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
                close()
            }
        }.build()
    }

    /** 导航箭头（纸飞机），Google Maps 方向/巡航按钮。 */
    val Navigation: ImageVector by lazy {
        ImageVector.Builder(
            name = "Navigation", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(4.5f, 20.29f)
                lineToRelative(0.71f, 0.71f)
                lineTo(12f, 18f)
                lineToRelative(6.79f, 3f)
                lineToRelative(0.71f, -0.71f)
                close()
            }
        }.build()
    }

    /** 图层（地图类型切换）。 */
    val Layers: ImageVector by lazy {
        ImageVector.Builder(
            name = "Layers", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.99f, 18.54f)
                lineToRelative(-7.37f, -5.73f)
                lineTo(3f, 14.07f)
                lineToRelative(9f, 7f)
                lineToRelative(9f, -7f)
                lineToRelative(-1.63f, -1.27f)
                close()
                moveTo(12f, 16f)
                lineToRelative(7.36f, -5.73f)
                lineTo(21f, 9f)
                lineToRelative(-9f, -7f)
                lineToRelative(-9f, 7f)
                lineToRelative(1.63f, 1.27f)
                close()
            }
        }.build()
    }

    /** 交换起终点（官方 Material swap_vert）。 */
    val SwapVert: ImageVector by lazy {
        ImageVector.Builder(
            name = "SwapVert", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 17.01f)
                verticalLineTo(10f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(7.01f)
                horizontalLineToRelative(-3f)
                lineTo(15f, 21f)
                lineToRelative(4f, -3.99f)
                horizontalLineToRelative(-3f)
                close()
                moveTo(9f, 3f)
                lineTo(5f, 6.99f)
                horizontalLineToRelative(3f)
                verticalLineTo(14f)
                horizontalLineToRelative(2f)
                verticalLineTo(6.99f)
                horizontalLineToRelative(3f)
                lineTo(9f, 3f)
                close()
            }
        }.build()
    }

    /** 拖动把手（官方 Material drag_handle，两横杠）：停靠点重排手柄。 */
    val DragHandle: ImageVector by lazy {
        ImageVector.Builder(
            name = "DragHandle", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 9f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(16f)
                verticalLineTo(9f)
                close()
                moveTo(4f, 15f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(-2f)
                horizontalLineTo(4f)
                verticalLineTo(15f)
                close()
            }
        }.build()
    }

    /** 公交车道（官方 Material directions_bus）。 */
    val Bus: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bus", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 16f)
                curveToRelative(0f, 0.88f, 0.39f, 1.67f, 1f, 2.22f)
                verticalLineTo(20f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(1f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(1f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(1f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-1.78f)
                curveToRelative(0.61f, -0.55f, 1f, -1.34f, 1f, -2.22f)
                verticalLineTo(6f)
                curveToRelative(0f, -3.5f, -3.58f, -4f, -8f, -4f)
                reflectiveCurveToRelative(-8f, 0.5f, -8f, 4f)
                verticalLineToRelative(10f)
                close()
                moveToRelative(3.5f, 1f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveTo(6.67f, 14f, 7.5f, 14f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(8.33f, 17f, 7.5f, 17f)
                close()
                moveToRelative(9f, 0f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                close()
                moveToRelative(1.5f, -6f)
                horizontalLineTo(6f)
                verticalLineTo(6f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(5f)
                close()
            }
        }.build()
    }

    /** 可变车道（官方 Material autorenew）。 */
    val AutoRenew: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoRenew", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 6f)
                verticalLineToRelative(3f)
                lineToRelative(4f, -4f)
                lineToRelative(-4f, -4f)
                verticalLineToRelative(3f)
                curveToRelative(-4.42f, 0f, -8f, 3.58f, -8f, 8f)
                curveToRelative(0f, 1.57f, 0.46f, 3.03f, 1.24f, 4.26f)
                lineTo(6.7f, 14.8f)
                curveToRelative(-0.45f, -0.83f, -0.7f, -1.79f, -0.7f, -2.8f)
                curveToRelative(0f, -3.31f, 2.69f, -6f, 6f, -6f)
                close()
                moveToRelative(6.76f, 1.74f)
                lineTo(17.3f, 9.2f)
                curveToRelative(0.44f, 0.84f, 0.7f, 1.79f, 0.7f, 2.8f)
                curveToRelative(0f, 3.31f, -2.69f, 6f, -6f, 6f)
                verticalLineToRelative(-3f)
                lineToRelative(-4f, 4f)
                lineToRelative(4f, 4f)
                verticalLineToRelative(-3f)
                curveToRelative(4.42f, 0f, 8f, -3.58f, 8f, -8f)
                curveToRelative(0f, -1.57f, -0.46f, -3.03f, -1.24f, -4.26f)
                close()
            }
        }.build()
    }



    /** 前进箭头（官方 Material arrow_forward，下一步用）。 */
    val ArrowForward: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowForward", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(16.17f, 11f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(12.17f)
                lineToRelative(-5.58f, 5.59f)
                lineTo(12f, 20f)
                lineToRelative(8f, -8f)
                close()
            }
        }.build()
    }

    /** 返回箭头（官方 Material arrow_back）。 */
    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowBack", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineToRelative(5.59f, -5.59f)
                lineTo(12f, 4f)
                lineToRelative(-8f, 8f)
                lineToRelative(8f, 8f)
                lineToRelative(1.41f, -1.41f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    /** 列表（三横线，路线步骤 tab 用）。 */
    val ListIcon: ImageVector by lazy {
        ImageVector.Builder(
            name = "ListIcon", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 6f)
                horizontalLineTo(20f)
                verticalLineToRelative(2f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 11f)
                horizontalLineTo(20f)
                verticalLineToRelative(2f)
                horizontalLineTo(4f)
                close()
                moveTo(4f, 16f)
                horizontalLineTo(20f)
                verticalLineToRelative(2f)
                horizontalLineTo(4f)
                close()
            }
        }.build()
    }

    /** 备选路线（官方 Material alt_route，Google Maps 切换路线同款）。 */
    val AltRoute: ImageVector by lazy {
        ImageVector.Builder(
            name = "AltRoute", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9.78f, 11.16f)
                lineToRelative(-1.42f, 1.42f)
                curveToRelative(-0.68f, -0.69f, -1.34f, -1.58f, -1.79f, -2.94f)
                lineToRelative(1.94f, -0.49f)
                curveTo(8.83f, 10.04f, 9.28f, 10.65f, 9.78f, 11.16f)
                close()
                moveTo(11f, 6f)
                lineTo(7f, 2f)
                lineTo(3f, 6f)
                horizontalLineToRelative(3.02f)
                curveTo(6.04f, 6.81f, 6.1f, 7.54f, 6.21f, 8.17f)
                lineToRelative(1.94f, -0.49f)
                curveTo(8.08f, 7.2f, 8.03f, 6.63f, 8.02f, 6f)
                horizontalLineTo(11f)
                close()
                moveTo(21f, 6f)
                lineToRelative(-4f, -4f)
                lineToRelative(-4f, 4f)
                horizontalLineToRelative(2.99f)
                curveToRelative(-0.1f, 3.68f, -1.28f, 4.75f, -2.54f, 5.88f)
                curveToRelative(-0.5f, 0.44f, -1.01f, 0.92f, -1.45f, 1.55f)
                curveToRelative(-0.34f, -0.49f, -0.73f, -0.88f, -1.13f, -1.24f)
                lineTo(9.46f, 13.6f)
                curveTo(10.39f, 14.45f, 11f, 15.14f, 11f, 17f)
                curveToRelative(0f, 0f, 0f, 0f, 0f, 0f)
                horizontalLineToRelative(0f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-5f)
                curveToRelative(0f, 0f, 0f, 0f, 0f, 0f)
                curveToRelative(0f, -2.02f, 0.71f, -2.66f, 1.79f, -3.63f)
                curveToRelative(1.38f, -1.24f, 3.08f, -2.78f, 3.2f, -7.37f)
                horizontalLineTo(21f)
                close()
            }
        }.build()
    }

    /** 公司（官方 Material work 公文包）。 */
    val Work: ImageVector by lazy {
        ImageVector.Builder(
            name = "Work", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 6f)
                horizontalLineToRelative(-4f)
                verticalLineTo(4f)
                curveToRelative(0f, -1.11f, -0.89f, -2f, -2f, -2f)
                horizontalLineToRelative(-4f)
                curveToRelative(-1.11f, 0f, -2f, 0.89f, -2f, 2f)
                verticalLineToRelative(2f)
                horizontalLineTo(4f)
                curveToRelative(-1.11f, 0f, -1.99f, 0.89f, -1.99f, 2f)
                lineTo(2f, 19f)
                curveToRelative(0f, 1.11f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.11f, 0f, 2f, -0.89f, 2f, -2f)
                verticalLineTo(8f)
                curveToRelative(0f, -1.11f, -0.89f, -2f, -2f, -2f)
                close()
                moveToRelative(-6f, 0f)
                horizontalLineToRelative(-4f)
                verticalLineTo(4f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    /** 搜索历史（官方 Material history 时钟）。 */
    val History: ImageVector by lazy {
        ImageVector.Builder(
            name = "History", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 3f)
                curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
                horizontalLineTo(1f)
                lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f)
                lineTo(9f, 12f)
                horizontalLineTo(6f)
                curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
                reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
                curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
                curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
                reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f)
                close()
                moveToRelative(-1f, 5f)
                verticalLineToRelative(5f)
                lineToRelative(4.28f, 2.54f)
                lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f)
                verticalLineTo(8f)
                horizontalLineTo(12f)
                close()
            }
        }.build()
    }
    /** 地点图钉（官方 Material place），搜索结果/到达图标。 */
    val Place: ImageVector by lazy {
        ImageVector.Builder(
            name = "Place", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(8.13f, 2f, 5f, 5.13f, 5f, 9f)
                curveToRelative(0f, 5.25f, 7f, 13f, 7f, 13f)
                reflectiveCurveToRelative(7f, -7.75f, 7f, -13f)
                curveToRelative(0f, -3.87f, -3.13f, -7f, -7f, -7f)
                close()
                moveToRelative(0f, 9.5f)
                curveToRelative(-1.38f, 0f, -2.5f, -1.12f, -2.5f, -2.5f)
                reflectiveCurveToRelative(1.12f, -2.5f, 2.5f, -2.5f)
                reflectiveCurveToRelative(2.5f, 1.12f, 2.5f, 2.5f)
                reflectiveCurveToRelative(-1.12f, 2.5f, -2.5f, 2.5f)
                close()
            }
        }.build()
    }


    val CameraFollow: ImageVector by lazy {
        ImageVector.Builder(
            name = "CameraFollow", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(19f, 21f)
                lineTo(12f, 16.8f)
                lineTo(5f, 21f)
                close()
            }
        }.build()
    }

    val CameraNorth: ImageVector by lazy {
        ImageVector.Builder(
            name = "CameraNorth", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(17.5f, 15f)
                lineTo(12f, 11.8f)
                lineTo(6.5f, 15f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 16.5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val CameraOverview: ImageVector by lazy {
        ImageVector.Builder(
            name = "CameraOverview", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round
            ) {
                moveTo(3f, 9f)
                lineTo(3f, 3f)
                lineTo(9f, 3f)
                moveTo(15f, 3f)
                lineTo(21f, 3f)
                lineTo(21f, 9f)
                moveTo(21f, 15f)
                lineTo(21f, 21f)
                lineTo(15f, 21f)
                moveTo(9f, 21f)
                lineTo(3f, 21f)
                lineTo(3f, 15f)
            }
        }.build()
    }
}
