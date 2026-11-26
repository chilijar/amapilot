package com.amapilot.auto

import com.amap.api.maps.model.LatLng

/**
 * 车机跟随相机的**沿路线外推**几何：把当前位置投影到路线弧长，沿弧长前推
 * `v×age` 得到帧目标，弯道不切角、不冲出路面（直线外推的固有缺陷）。
 *
 * 供 [NavigationScreen] ticker 消费；灰线侧自持同构几何，互不依赖。
 * 投影复用 [projectOnRoute]（窗口化避免掉头段吸到对向段）。
 */
internal object RouteFollowGeom {

    @Volatile
    private var lats: DoubleArray = DoubleArray(0)

    @Volatile
    private var lngs: DoubleArray = DoubleArray(0)

    /** 顶点 i 到终点的弧长（米）；递减：[0]=总长、末位=0。与 [lats] 同长。 */
    @Volatile
    private var cumFromEnd: DoubleArray = DoubleArray(0)

    /** 沿路线外推结果：帧目标坐标 + 新锚点弧长（下拍窗口化投影用）。 */
    data class Advance(val lat: Double, val lng: Double, val cum: Double)

    /** 灌入路线（导航开始/重算/换线时调用；点数 <2 = 清空回退直线外推）。 */
    fun update(points: List<LatLng>) {
        if (points.size < 2) {
            clear()
            return
        }
        synchronized(this) {
            lats = DoubleArray(points.size) { points[it].latitude }
            lngs = DoubleArray(points.size) { points[it].longitude }
            cumFromEnd = buildCumFromEnd(points)
        }
    }

    fun clear() {
        synchronized(this) {
            lats = DoubleArray(0)
            lngs = DoubleArray(0)
            cumFromEnd = DoubleArray(0)
        }
    }

    /**
     * 投影 (lat,lng) 到路线 → 沿弧长前推 [aheadMeters] → 插值坐标。
     * @return null = 无路线/投影失败/偏线（> [GRAY_PROJ_MAX_OFFSET_M]），调用方回退直线外推
     */
    fun advance(lat: Double, lng: Double, aheadMeters: Double, lastCum: Double): Advance? {
        val l = lats
        val g = lngs
        val c = cumFromEnd
        if (l.size < 2) return null
        val proj = projectOnRoute(l, g, c, lat, lng, lastCum) ?: return null
        if (proj.second > GRAY_PROJ_MAX_OFFSET_M) return null
        // 前推 = 到终点弧长减小；夹到 [0, 总长]（到点即停在终点）
        val targetCum = (proj.first - aheadMeters).coerceIn(0.0, c[0])
        var idx = 0
        while (idx < l.size && c[idx] >= targetCum) idx++
        idx = idx.coerceIn(1, l.size - 1)
        val cHi = c[idx - 1]
        val cLo = c[idx]
        val t = if (cHi > cLo) ((cHi - targetCum) / (cHi - cLo)).coerceIn(0.0, 1.0) else 1.0
        return Advance(
            l[idx - 1] + (l[idx] - l[idx - 1]) * t,
            g[idx - 1] + (g[idx] - g[idx - 1]) * t,
            targetCum
        )
    }

    /** 顶点到终点弧长表（与 CarMapPresentation.buildCumFromEnd 同算法）。 */
    private fun buildCumFromEnd(pts: List<LatLng>): DoubleArray {
        val n = pts.size
        val arr = DoubleArray(n)
        var acc = 0.0
        for (i in n - 2 downTo 0) {
            val dLat = (pts[i + 1].latitude - pts[i].latitude) * 111_320.0
            val dLng = (pts[i + 1].longitude - pts[i].longitude) * 111_320.0 *
                kotlin.math.cos(Math.toRadians((pts[i].latitude + pts[i + 1].latitude) / 2))
            acc += kotlin.math.hypot(dLat, dLng)
            arr[i] = acc
        }
        return arr
    }
}
