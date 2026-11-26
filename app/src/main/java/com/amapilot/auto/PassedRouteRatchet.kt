package com.amapilot.auto

/**
 * 已走灰线（进度）边界推进规则：GPS 投影（主）+ 引擎剩余里程棘轮（兜底）。
 * 无 Android 依赖，纯 JVM 单测可直接初始化。
 */

/** 灰线棘轮速度上限（m/s，≈144km/h）：单 tick 剩余里程降幅超过即视为引擎毛刺。 */
internal const val GRAY_MAX_DROP_MPS = 40.0

/** 灰线棘轮容差（m）：覆盖 tick 间隔量化与弧长插值误差。 */
internal const val GRAY_DROP_MARGIN_M = 50.0

/**
 * 剩余里程棘轮：只减不增（防引擎抖动回跳），并拒绝单 tick 超速度上限的骤降。
 * @param lastRemain 上一 tick 锁存值；<=0 = 未锁定（直接采当前值）
 */
internal fun advanceGrayRemain(lastRemain: Int, remain: Int, dtSeconds: Double): Int {
    if (lastRemain <= 0) return remain
    val maxDrop = GRAY_MAX_DROP_MPS * dtSeconds.coerceAtLeast(0.0) + GRAY_DROP_MARGIN_M
    if (remain < lastRemain - maxDrop) return lastRemain
    return minOf(lastRemain, remain)
}

/**
 * 已走里程 → 灰线边界弧长：boundary = total0 − (remain0 − eff)，夹在 [0, total0]。
 * 引擎首帧 remain 与几何弧长有系统性偏差，直接用会让灰线开局超前箭头，故只用已走里程。
 * @param total0 几何总弧长；@param remain0 首帧剩余里程；@param eff 当前棘轮剩余里程
 */
internal fun grayTraveledBoundary(total0: Double, remain0: Int, eff: Int): Double =
    (total0 - (remain0 - eff)).coerceIn(0.0, total0)

/** 出发判定阈值（m）：首帧 total − remain 超过即视为「中途重建」，不锁存偏差基准。 */
internal const val GRAY_BASELINE_MAX_GAP_M = 1500.0

/**
 * 首帧锁存 remain0：近出发（total − firstEff ≤ 阈值）取 firstEff 以吸收系统偏差；
 * 中途重建（Presentation 重建）取 total，退回直接用 remain 作边界。
 */
internal fun grayBaselineRemain0(total0: Double, firstEff: Int): Int =
    if (total0 - firstEff <= GRAY_BASELINE_MAX_GAP_M) firstEff else total0.toInt()

// ── GPS 投影边界（主通道） ──
//
// 引擎 remain 是离散台阶式刷新，边界只能跳到台阶位置 = 灰线一节节推进。
// 改用 GPS 位置向路线投影：每拍连续推进，且与箭头同源。

/** 投影垂距上限（m）：超过 = 偏离路线（重算窗口/GPS 漂移），投影不可信，退回引擎 remain。 */
internal const val GRAY_PROJ_MAX_OFFSET_M = 60.0

/**
 * 投影搜索窗口（m，以上一拍边界为中心 ±窗）：掉头段去程/回程仅隔一个车道宽，
 * 全局最近点会吸到对向段（灰线提前盖住未走回程），窗口化即消除。
 */
internal const val GRAY_PROJ_WINDOW_M = 250.0

/**
 * GPS 位置向路线折线窗口化投影（等距圆柱近似，米级误差可忽略）。
 * @param cumFromEnd 各顶点「到终点弧长」（递减）；@param lastCum 上一拍边界（<0 = 全量扫描）
 * @return [到终点弧长, 垂距(m)]；点数不足/窗口内无段返回 null
 */
internal fun projectOnRoute(
    lats: DoubleArray,
    lngs: DoubleArray,
    cumFromEnd: DoubleArray,
    lat: Double,
    lng: Double,
    lastCum: Double = -1.0,
    windowMeters: Double = GRAY_PROJ_WINDOW_M,
): Pair<Double, Double>? {
    if (lats.size < 2 || lats.size != lngs.size || lats.size != cumFromEnd.size) return null
    val mPerDegLng = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
    var bestCum = 0.0
    var bestOff = Double.MAX_VALUE
    for (i in 1 until lats.size) {
        // 窗口过滤：段两端都落在 [lastCum±window] 外则跳过（cumFromEnd 递减）
        if (lastCum >= 0.0) {
            val cHi = cumFromEnd[i - 1]
            val cLo = cumFromEnd[i]
            if (cLo > lastCum + windowMeters || cHi < lastCum - windowMeters) continue
        }
        // 以 P 为原点的局部米坐标：A=顶点 i-1，B=顶点 i
        val ax = (lats[i - 1] - lat) * 111_320.0
        val ay = (lngs[i - 1] - lng) * mPerDegLng
        val dx = (lats[i] - lats[i - 1]) * 111_320.0
        val dy = (lngs[i] - lngs[i - 1]) * mPerDegLng
        val len2 = dx * dx + dy * dy
        // t = ((P-A)·(B-A))/|B-A|²，P-A = -A
        val t = if (len2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val fx = ax + dx * t
        val fy = ay + dy * t
        val off = kotlin.math.hypot(fx, fy)
        if (off < bestOff) {
            bestOff = off
            bestCum = cumFromEnd[i - 1] + (cumFromEnd[i] - cumFromEnd[i - 1]) * t
        }
    }
    if (bestOff == Double.MAX_VALUE) return null
    return Pair(bestCum, bestOff)
}

/**
 * 边界统一棘轮（随前进递减）：只进不退 + 单拍速度帽，覆盖 GPS 回跳与两源切换跳变。
 * @param lastPos 上一拍边界；<0 = 未锁存
 */
internal fun ratchetBoundary(lastPos: Double, pos: Double, dtSeconds: Double): Double {
    if (lastPos < 0.0) return pos
    if (pos > lastPos) return lastPos
    val maxAdvance = GRAY_MAX_DROP_MPS * dtSeconds.coerceAtLeast(0.0) + GRAY_DROP_MARGIN_M
    return if (lastPos - pos > maxAdvance) lastPos - maxAdvance else pos
}
