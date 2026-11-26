package com.amapilot.navigation

/**
 * 方向卡文案层（手机 TBT / 车机 CarTurnCard / AmapNaviPage 备用入口共用）：
 * 单位统一为中文（`x.x 公里 / x 米`、`x 小时 x 分 / x 分钟 / 1 分钟内`），
 * 三处共用一份实现，勿再复制。
 */

/**
 * 距离报告取整（Google Maps 粒度对齐，向下取整）：`<10m` 保留精确 /
 * `<100m` 10 米级 / `<1km` 50 米级 / `<10km` 100 米级 / `≥10km` 整公里。
 * floor = 倒计时语义：显示值恒 ≤ 实际剩余，不虚报更远的机动距离；显示
 * （转向卡/路程卡）与简洁播报（`GuidanceController.fmtDist`）共用本函数。
 */
fun naviRoundMeters(meters: Int): Int = when {
    meters < 10 -> meters
    meters < 100 -> meters / 10 * 10
    meters < 1000 -> meters / 50 * 50
    meters < 10000 -> meters / 100 * 100
    else -> meters / 1000 * 1000
}

/** 距离文本：`x.x 公里` / `x 公里`（≥10km）/ `x 米`（[naviRoundMeters] 取整后）。 */
fun naviDistanceText(meters: Int): String {
    val m = naviRoundMeters(meters)
    return when {
        m >= 10000 -> "${m / 1000} 公里"
        m >= 1000 -> String.format("%.1f 公里", m / 1000.0)
        else -> "$m 米"
    }
}

/** 时长文本：`x 小时 x 分` / `x 分钟` / `1 分钟内`（<60s，原车机 `<1 min` 语义）。 */
fun naviDurationText(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        seconds < 60 -> "1 分钟内"
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
        else -> "$minutes 分钟"
    }
}

/** 机动行：`动作+下一路名`（两者皆空 → null = 整行隐藏，车机既有语义）。 */
fun NaviState.maneuverLine(): String? = buildString {
    append(actionText)
    if (nextRoad.isNotBlank()) append(nextRoad)
}.takeIf { it.isNotBlank() }

/** 沿路行：`沿 当前路名`（空 → null）。 */
fun NaviState.alongLine(): String? =
    currentRoad.takeIf { it.isNotBlank() }?.let { "沿 $it" }
