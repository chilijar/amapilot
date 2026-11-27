package com.amapilot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.amap.api.navi.enums.LaneAction
import com.amapilot.navigation.LaneDir
import com.amapilot.navigation.NaviSession
import com.amapilot.navigation.laneDirsOf
import com.amapilot.navigation.laneSpecOf
import com.amapilot.navigation.resolveLaneHighlight
import com.amapilot.ui.theme.GmIcons

/**
 * 共用车道行（手机 TBT / 车机 CarTurnCard 单一实现）：满宽 SpaceEvenly 居中分布 +
 * 车道间竖分隔线 + 单元格。判定逻辑全部来自
 * [com.amapilot.navigation.LaneDirections]，改一次两侧同步。
 * 两侧只差色板与尺寸（手机 30dp/54dp + 顶分割线，车机 26dp/46dp 紧凑无分割线）；
 * 箭头粗细随 iconSize 等比（fill 型 Material 剪影杆宽 2/24 viewport）。
 */
@Composable
fun LaneRow(
    lanes: List<NaviSession.LaneCell>,
    active: LaneDir,
    slight: Boolean,
    linksToManeuver: Int,
    stepRemainMeters: Int,
    linkRemainMeters: Int,
    iconSize: Dp,
    separatorHeight: Dp,
    accent: Color,
    inactive: Color,
    separatorColor: Color,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    topDividerColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    val highlight = resolveLaneHighlight(
        lanes.map { it.actions }, active, lanes.map { it.recommended },
        linksToManeuver, stepRemainMeters, linkRemainMeters
    )
    if (topDividerColor != null) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .height(1.dp)
                .background(topDividerColor)
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        lanes.forEachIndexed { index, lane ->
            if (index > 0) LaneRowSeparator(separatorColor, separatorHeight)
            LaneRowCell(
                lane, highlight.laneDirs.getOrElse(index) { emptySet() },
                slight, iconSize, accent, inactive
            )
        }
    }
}

/** 车道分隔线（竖向车道线）；[dashed] 支持虚/实（SDK 未暴露每分隔线样式，默认实线）。 */
@Composable
private fun LaneRowSeparator(color: Color, height: Dp, dashed: Boolean = false) {
    Canvas(modifier = Modifier.size(width = 2.dp, height = height)) {
        val pathEffect = if (dashed) {
            PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
        } else {
            null
        }
        drawLine(
            color = color,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = size.width,
            pathEffect = pathEffect
        )
    }
}

/** 单车道单元格：特殊车道（公交/潮汐/可变）用 Material 字形（非推荐 = [inactive]）；
 *  空车道占位；普通车道复合字形按 [active] 逐方向着色。 */
@Composable
private fun LaneRowCell(
    cell: NaviSession.LaneCell,
    active: Set<LaneDir>,
    slight: Boolean,
    iconSize: Dp,
    accent: Color,
    inactive: Color,
) {
    val actions = cell.actions.toIntArray()
    val iconMod = Modifier.size(iconSize)
    val specialTint = if (cell.recommended) accent else inactive
    when {
        actions.contains(LaneAction.LANE_ACTION_BUS) ->
            Icon(imageVector = GmIcons.Bus, contentDescription = null, modifier = iconMod, tint = specialTint)
        actions.contains(LaneAction.LANE_ACTION_TIDAL) ->
            Icon(imageVector = GmIcons.SwapVert, contentDescription = null, modifier = iconMod, tint = specialTint)
        actions.contains(LaneAction.LANE_ACTION_VARIABLE) ->
            Icon(imageVector = GmIcons.AutoRenew, contentDescription = null, modifier = iconMod, tint = specialTint)
        actions.contains(LaneAction.LANE_ACTION_EMPTY) ||
            actions.contains(LaneAction.LANE_ACTION_NULL) ->
            Spacer(iconMod)
        else -> {
            val spec = laneSpecOf(laneDirsOf(actions), slight)
            val icon = remember(spec, active, accent, inactive) {
                spec.build(active, accent, inactive)
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = iconMod,
                tint = Color.Unspecified
            )
        }
    }
}
