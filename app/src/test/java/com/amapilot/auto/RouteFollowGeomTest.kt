package com.amapilot.auto

import com.amap.api.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 沿路线外推（[RouteFollowGeom]）行为：投影 → 弧长前推 → 插值坐标。
 *
 * 车机跟随相机的帧目标几何（2026-11 滴滴/高德官方平滑移动同思路——展示位置
 * 一律"投影到线再插值"）。关键性质：弯道沿折线不切角、终点夹取不越过、
 * 偏线 >[GRAY_PROJ_MAX_OFFSET_M] 返回 null（调用方回退直线外推）、
 * 窗口化投影防掉头段吸对向车道。
 *
 * 夹具取赤道（cos=1）：1° lng = 1° lat = 111320m，弧长/坐标换算无三角修正。
 */
class RouteFollowGeomTest {

    /** 东西向 200m 段 × 50 = 10km 直线。 */
    private val straight = List(51) { i ->
        LatLng(0.0, 116.0 + i * 200.0 / 111_320.0)
    }

    @Test
    fun `advance projects and interpolates along segment`() {
        RouteFollowGeom.update(straight)
        // 第 10 顶点（cum=8000）前推 300m：跨 1 段落在 11-12 顶点之间
        val a = RouteFollowGeom.advance(straight[10].latitude, straight[10].longitude, 300.0, -1.0)
        assertEquals(7700.0, a!!.cum, 1.0)
        // 300m = 200m 整段 + 100m → 第 12 顶点回退 100m 处（即 11-12 段中点）
        val expectLng = straight[11].longitude + 0.5 * (straight[12].longitude - straight[11].longitude)
        assertEquals(expectLng, a.lng, 1e-7)
        assertEquals(0.0, a.lat, 1e-9)
    }

    @Test
    fun `ahead beyond destination clamps to end`() {
        RouteFollowGeom.update(straight)
        val a = RouteFollowGeom.advance(straight[0].latitude, straight[0].longitude, 99_999.0, -1.0)
        // 到终点弧长夹到 0；坐标 = 折线末顶点
        assertEquals(0.0, a!!.cum, 0.001)
        assertEquals(straight.last().longitude, a.lng, 1e-9)
    }

    @Test
    fun `off-route beyond threshold returns null for straight fallback`() {
        RouteFollowGeom.update(straight)
        // 垂距 ~70m > 60m 阈值：null（重算窗口/偏线 → 调用方回退 bearing 直线外推）
        assertNull(
            RouteFollowGeom.advance(
                straight[10].latitude + 70.0 / 111_320.0, straight[10].longitude, 10.0, -1.0
            )
        )
    }

    @Test
    fun `small offset inside threshold still advances along route`() {
        RouteFollowGeom.update(straight)
        // 垂距 ~20m：正常投影前推（cum 8000 → 7900）
        val a = RouteFollowGeom.advance(
            straight[10].latitude + 20.0 / 111_320.0, straight[10].longitude, 100.0, -1.0
        )
        assertEquals(7900.0, a!!.cum, 1.0)
    }

    @Test
    fun `L-shaped route advances around the bend not diagonally`() {
        // 东行 400m 后北行 400m（L 形）：前推 500m = 400m 到拐点 + 100m 北行段
        val l = buildList {
            add(LatLng(0.0, 116.0))
            add(LatLng(0.0, 116.0 + 400.0 / 111_320.0))
            add(LatLng(400.0 / 111_320.0, 116.0 + 400.0 / 111_320.0))
        }
        RouteFollowGeom.update(l)
        val a = RouteFollowGeom.advance(l[0].latitude, l[0].longitude, 500.0, -1.0)
        // 直线外推会切对角（Δ北=Δ东）；沿路外推 = 先吃完东行段再走北行段
        assertEquals(l[1].longitude, a!!.lng, 1e-9)          // 经度停在拐点
        assertEquals(l[1].latitude + 100.0 / 111_320.0, a.lat, 1e-9) // 纬度前进 100m
        assertEquals(300.0, a.cum, 1.0)                       // 800 - 500
    }

    @Test
    fun `windowed projection skips far return leg of u-turn`() {
        // 去程 y=0 东行 2km，折返 y=+3m（隔车道）西行 2km——全局最近点会吸回程段
        val there = List(21) { i -> LatLng(0.0, 116.0 + i * 100.0 / 111_320.0) }
        val back = List(21) { i -> LatLng(3.0 / 111_320.0, 116.0 + (2000 - i * 100.0) / 111_320.0) }
        RouteFollowGeom.update(there + back)
        // 去程 500m 处（cum=3503，含 3m 折返接缝段）窗口化前推：回程镜像点
        // cum≈503，距锚 3000m ≫ 250m 窗口 → 被窗口排除，投影仍落在去程段
        val cur = there[5]
        val a = RouteFollowGeom.advance(cur.latitude, cur.longitude, 100.0, lastCum = 3503.0)
        assertEquals(3403.0, a!!.cum, 1.0)
    }

    @Test
    fun `empty or short route returns null`() {
        RouteFollowGeom.update(emptyList())
        assertNull(RouteFollowGeom.advance(0.0, 116.0, 10.0, -1.0))
        RouteFollowGeom.update(listOf(LatLng(0.0, 116.0)))
        assertNull(RouteFollowGeom.advance(0.0, 116.0, 10.0, -1.0))
    }
}
