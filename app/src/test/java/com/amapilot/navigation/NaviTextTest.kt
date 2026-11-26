package com.amapilot.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 方向卡共用文案层（手机 TBT / 车机 / AmapNaviPage 备用入口单一来源）。 */
class NaviTextTest {

    @Test
    fun distanceFormats() {
        assertEquals("0 米", naviDistanceText(0))
        assertEquals("9 米", naviDistanceText(9))      // <10m 保留精确（floor 会归 0）
        assertEquals("40 米", naviDistanceText(49))     // <100m 10 米级
        assertEquals("60 米", naviDistanceText(64))
        assertEquals("90 米", naviDistanceText(99))
        assertEquals("200 米", naviDistanceText(243))   // <1km 50 米级（Google 梯级）
        assertEquals("950 米", naviDistanceText(999))
        assertEquals("1.0 公里", naviDistanceText(1000))
        assertEquals("2.4 公里", naviDistanceText(2430)) // <10km 100 米级一位小数
        assertEquals("12 公里", naviDistanceText(12340)) // ≥10km 整公里
    }

    @Test
    fun durationFormats() {
        assertEquals("1 分钟内", naviDurationText(30))   // 原车机 "<1 min" 语义
        assertEquals("1 分钟", naviDurationText(60))
        assertEquals("59 分钟", naviDurationText(3599))
        assertEquals("1 小时 0 分", naviDurationText(3600))
        assertEquals("2 小时 5 分", naviDurationText(7500))
    }

    @Test
    fun maneuverLineJoinsActionAndRoad() {
        assertEquals("左转进入南京西路", NaviState(actionText = "左转进入", nextRoad = "南京西路").maneuverLine())
        assertEquals("直行", NaviState(actionText = "直行").maneuverLine())
        assertNull(NaviState(actionText = "", nextRoad = "").maneuverLine())
    }

    /** 左后方/右后方（急转）按左转/右转播报（用户定稿 2026-11，与图标侧同步）。 */
    @Test
    fun sharpTurnVerbsCollapseToRegularTurns() {
        assertEquals("左转", NaviSession.actionText(com.amap.api.navi.enums.IconType.LEFT_BACK))
        assertEquals("右转", NaviSession.actionText(com.amap.api.navi.enums.IconType.RIGHT_BACK))
        // 相邻档位不受影响
        assertEquals("左前方", NaviSession.actionText(com.amap.api.navi.enums.IconType.LEFT_FRONT))
        assertEquals("掉头", NaviSession.actionText(com.amap.api.navi.enums.IconType.LEFT_TURN_AROUND))
    }

    @Test
    fun alongLineHidesWhenBlank() {
        assertEquals("沿 建国路", NaviState(currentRoad = "建国路").alongLine())
        assertNull(NaviState(currentRoad = "").alongLine())
    }
}
