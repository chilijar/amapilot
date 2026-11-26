package com.amapilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amapilot.ui.theme.GmColors

/** Google Maps 风格浮动圆钮：白色圆形 + 阴影 + 灰色图标（定位回中/图层切换）。 */
@Composable
fun GmFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // onSurfaceVariant：浅色=Gray700，夜间自动变亮保证暗底可见
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Int = 48
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size((size * 0.5f).dp)
            )
        }
    }
}

/** Google Maps 风格浮动搜索栏：白色胶囊 + 阴影，左图标右操作位。 */
@Composable
fun GmSearchBar(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                // 夜间自动切换保证暗底可见
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 38.dp)
            )
            if (trailing != null) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
            }
        }
    }
}

/** 底部面板抓手条：居中灰色小胶囊，提示可拖拽。 */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                // outline：浅色=Gray300，夜间自动变暗
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
        )
    }
}

/**
 * Google Maps 风格信息芯片（如"25 分钟 · 推荐"）。
 */
@Composable
fun GmChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val bg = if (selected) GmColors.Blue50 else GmColors.Gray100
    val fg = if (selected) GmColors.Blue700 else GmColors.Gray700
    val content: @Composable () -> Unit = {
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = bg, modifier = modifier) { content() }
    } else {
        Surface(shape = MaterialTheme.shapes.large, color = bg, modifier = modifier) { content() }
    }
}
