package com.amapilot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amapilot.ui.theme.GmColors

/**
 * 车速圆环（手机 TBT/巡航 与车机巡航共用）：圆底 + 彩色环描边（正常 [ringColor]，
 * 超速按超出量分档 <10 黄 / 10~20 橙 / >20 红）+ 限速内环细刻度 + 中心大字车速 + km/h。
 * 两侧只差色板尺寸与限速形态：手机 72dp 主题色（限速仅内环刻度）；车机 52dp 自配色
 * 并加 [limitLabel] 文字行。
 */
@Composable
fun SpeedRing(
    speedKmh: Int,
    limitKmh: Int,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    overspeedSlightColor: Color = GmColors.Yellow700,
    overspeedMidColor: Color = GmColors.Orange600,
    overspeedSevereColor: Color = GmColors.Red600,
    bgColor: Color = MaterialTheme.colorScheme.surface,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    subTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    speedFontSize: TextUnit = 22.sp,
    unitFontSize: TextUnit = 10.sp,
    limitLabel: (@Composable () -> Unit)? = null,
) {
    // 超速分档（超出限速 km/h）：1~9 黄 / 10~20 橙 / 21+ 红；内环随档同色
    val over = speedKmh - limitKmh
    val strokeColor = when {
        limitKmh <= 0 || over < 1 -> ringColor
        over < 10 -> overspeedSlightColor
        over <= 20 -> overspeedMidColor
        else -> overspeedSevereColor
    }
    Box(
        modifier = modifier
            .size(size)
            .background(bgColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = this.size.minDimension * 0.09f
            drawCircle(
                color = strokeColor,
                radius = (this.size.minDimension - strokeW) / 2f,
                style = Stroke(width = strokeW)
            )
            if (limitKmh > 0) {
                // 内环细刻度 = 限速值参考
                drawCircle(
                    color = strokeColor.copy(alpha = 0.35f),
                    radius = (this.size.minDimension - strokeW * 4.2f) / 2f,
                    style = Stroke(width = strokeW * 0.45f)
                )
            }
        }
        if (limitLabel != null) {
            // 车机形态：速度/单位/限速 居中堆叠
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$speedKmh",
                    fontSize = speedFontSize,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Text(text = "km/h", fontSize = unitFontSize, color = subTextColor)
                limitLabel()
            }
        } else {
            Text(
                text = "$speedKmh",
                fontSize = speedFontSize,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = "km/h",
                fontSize = unitFontSize,
                color = subTextColor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = size * 0.14f)
            )
        }
    }
}
