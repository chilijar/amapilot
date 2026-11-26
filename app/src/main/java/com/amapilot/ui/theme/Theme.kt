package com.amapilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** Google Maps 风格浅色配色。 */
private val LightColors = lightColorScheme(
    primary = GmColors.Blue600,
    onPrimary = GmColors.White,
    primaryContainer = GmColors.Blue50,
    onPrimaryContainer = GmColors.Blue700,
    secondary = GmColors.Green700,
    onSecondary = GmColors.White,
    secondaryContainer = GmColors.Green50,
    tertiary = GmColors.Yellow700,
    background = GmColors.Gray50,
    onBackground = GmColors.Gray900,
    surface = GmColors.White,
    onSurface = GmColors.Gray900,
    surfaceVariant = GmColors.Gray100,
    onSurfaceVariant = GmColors.Gray700,
    outline = GmColors.Gray300,
    outlineVariant = GmColors.Gray100,
    error = GmColors.Red600,
    onError = GmColors.White,
    errorContainer = GmColors.Red50,
)

/** Google Maps 风格深色配色（夜间/车机）。 */
private val DarkColors = darkColorScheme(
    primary = GmColors.Blue300,
    onPrimary = GmColors.Gray900,
    // 选中面较 DarkElevated 仅微弱蓝调（选中态主靠 primary 勾边）
    primaryContainer = GmColors.DarkSelectedSurface,
    onPrimaryContainer = GmColors.Blue50,
    secondary = GmColors.Green50,
    onSecondary = GmColors.Gray900,
    tertiary = GmColors.Yellow700,
    background = GmColors.DarkBackground,
    onBackground = GmColors.DarkOnSurface,
    surface = GmColors.DarkSurface,
    onSurface = GmColors.DarkOnSurface,
    surfaceVariant = GmColors.DarkElevated,
    onSurfaceVariant = GmColors.DarkOnSurfaceVar,
    outline = GmColors.DarkOutline,
    error = GmColors.Red600,
    onError = GmColors.White,
)

/**
 * 应用主题：Google Maps 风格配色，默认跟随系统昼夜模式。
 */
@Composable
fun AmapilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = GmTypography,
        shapes = GmShapes,
        content = content
    )
}
