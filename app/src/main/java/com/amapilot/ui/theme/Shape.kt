package com.amapilot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 形状令牌：圆角一律引用 `MaterialTheme.shapes.*`，勿硬编码。
 * extraLarge(28dp)=底部面板/搜索栏，large(16dp)=卡片，medium(12dp)=小卡/输入框。
 */
val GmShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
