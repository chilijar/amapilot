package com.amapilot.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航跟随态自适应 zoom（手机 TBT / 车机单一来源 [AutoZoom]）：
 * 速度对数曲线（基础项）+ 机动**纯距离**分带拉近（用户定稿 2026-11：
 * 机动项是距离信号，速度只在基础项，勿重复计入）。
 *
 * 曲线参考值（log₂ 斜率 1.2，夹 [13,18.5]）：
 * v≤10→18.5；20→17.3；40→16.1；60→15.4；80→14.9；240+→13。
 * 机动分带：≥500m 无；500→100m 线性 0→+1.6；≤100m 满格。
 */
class AutoZoomTest {

    @Test
    fun speedCurveMatchesCalibration() {
        // 纯速度曲线（无机动）
        assertEquals(18.5f, AutoZoom.target(0f, Float.MAX_VALUE), 1e-3f)
        assertEquals(18.5f, AutoZoom.target(10f, Float.MAX_VALUE), 1e-3f)
        assertEquals(17.3f, AutoZoom.target(20f, Float.MAX_VALUE), 0.05f)
        assertEquals(16.1f, AutoZoom.target(40f, Float.MAX_VALUE), 0.05f)
        assertEquals(15.4f, AutoZoom.target(60f, Float.MAX_VALUE), 0.05f)
        // 高速封底 13
        assertEquals(13f, AutoZoom.target(240f, Float.MAX_VALUE), 1e-3f)
    }

    @Test
    fun maneuverProximityIsPureDistance() {
        // 距离分带：≥500m 无机动项；500→100m 线性；≤100m 满格 +1.6
        val far = AutoZoom.target(60f, 1_000f)
        val atStart = AutoZoom.target(60f, 500f)
        val mid = AutoZoom.target(60f, 300f)
        val near = AutoZoom.target(60f, 100f)
        val atManeuver = AutoZoom.target(60f, 5f)
        assertEquals("500m 处应无机动项", far, atStart, 1e-3f)
        assertTrue("越近越拉近", atStart < mid && mid < near)
        assertEquals("100m 内满格", near, atManeuver, 1e-3f)
        // 60km/h 基线 15.4 + 1.6 = 17.0
        assertEquals(17.0f, atManeuver, 0.05f)
        // 低速 + 贴近：夹在细节档上限 19.1（18.5 + 1.6 = 20.1 超限）
        assertEquals(19.1f, AutoZoom.target(15f, 5f), 0.05f)
    }

    @Test
    fun maneuverTermIsSpeedInvariant() {
        // 机动项与车速无关：任意速度下 100m 与 1000m 的目标差恒 = BOOST。
        // （仅限 base+BOOST ≤ MANEUVER_MAX 的速度档；低速贴近被细节档上限
        //  夹断属设计行为，见 maneuverProximityIsPureDistance / stopped 用例）
        for (v in listOf(20f, 30f, 60f, 90f, 120f)) {
            val delta = AutoZoom.target(v, 100f) - AutoZoom.target(v, 1_000f)
            assertEquals("v=$v 机动项应等幅", AutoZoom.BOOST, delta, 0.05f)
        }
    }

    @Test
    fun stoppedNearManeuverStillZoomsIn() {
        // 静止（v=0）：纯距离判定天然覆盖——500m 外无、100m 内满格
        assertEquals(18.5f, AutoZoom.target(0f, 600f), 0.05f)
        assertTrue("停在机动跟前应拉近", AutoZoom.target(0f, 50f) > 18.5f)
        assertEquals(19.1f, AutoZoom.target(0f, 50f), 0.05f) // 夹在细节档上限
    }

    @Test
    fun approachSmoothsAndSnaps() {
        // 步长 0.2、死区 0.05、差>3 吸附
        assertEquals(15.0f, AutoZoom.approach(15.2f, 15.0f), 1e-3f)   // 步进
        assertEquals(15.2f, AutoZoom.approach(15.2f, 15.22f), 1e-3f) // 死区不动
        assertEquals(15.0f, AutoZoom.approach(15.2f, 13.5f), 1e-3f)  // 差 1.7 = 步进 −0.2
        assertEquals(15.0f, AutoZoom.approach(15.2f, 12.5f), 1e-3f)  // 差 2.7 仍步进
        assertEquals(16.6f, AutoZoom.approach(13.0f, 16.6f), 1e-3f)  // 差 3.6 吸附直达目标
    }
}
