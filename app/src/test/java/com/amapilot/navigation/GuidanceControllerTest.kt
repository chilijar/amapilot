package com.amapilot.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** 简洁引导合成：机动完成播"{距离}后{动作}[, 进入{路名}]"、接近机动点（剩余秒数
 *  动态触发）同句式一次；无名道路省略路名子句。 */
class GuidanceControllerTest {

    private fun state(
        step: Int = 0,
        remain: Int = 800,
        seconds: Int = 60,
        action: String = "左转",
        road: String = "X路",
        arrived: Boolean = false,
        speedZone: SpeedZone? = null,
    ) = NaviState(
        stepIndex = step,
        stepRemainMeters = remain,
        stepRemainSeconds = seconds,
        actionText = action,
        nextRoad = road,
        arrived = arrived,
        speedZone = speedZone,
    )

    /** 吸收机动首播（后续同 step 帧只出区间文本，不与机动播报混合）。 */
    private fun absorbManeuver() {
        GuidanceController.reset()
        GuidanceController.onState(state(), concise = true)
    }

    @Test
    fun nextAnnouncementUsesDistanceFirstFormat() {
        GuidanceController.reset()
        assertEquals(
            listOf("800米后左转，进入X路"),
            GuidanceController.onState(state(), concise = true)
        )
    }

    @Test
    fun unnamedRoadOmitsRoadClause() {
        GuidanceController.reset()
        assertEquals(
            listOf("800米后左转"),
            GuidanceController.onState(state(road = "无名道路"), concise = true)
        )
        GuidanceController.reset()
        assertEquals(
            listOf("800米后左转"),
            GuidanceController.onState(state(road = ""), concise = true)
        )
    }

    @Test
    fun closeReminderTriggeredByRemainingSeconds() {
        GuidanceController.reset()
        GuidanceController.onState(state(), concise = true)
        // 40s > 25s：静默
        assertEquals(emptyList<String>(), GuidanceController.onState(state(remain = 500, seconds = 40), true))
        // 20s ≤ 25s：接近播报（每 step 一次），句式同 next
        assertEquals(
            listOf("500米后左转，进入X路"),
            GuidanceController.onState(state(remain = 500, seconds = 20), true)
        )
        assertEquals(emptyList<String>(), GuidanceController.onState(state(remain = 300, seconds = 12), true))
    }

    @Test
    fun closeReminderFallsBackToMetersWhenSecondsUnknown() {
        GuidanceController.reset()
        GuidanceController.onState(state(), concise = true)
        assertEquals(emptyList<String>(), GuidanceController.onState(state(remain = 500, seconds = 0), true))
        assertEquals(
            listOf("300米后左转，进入X路"),
            GuidanceController.onState(state(remain = 300, seconds = 0), true)
        )
    }

    @Test
    fun stepChangeAnnouncesNextAndKilometerFormat() {
        GuidanceController.reset()
        GuidanceController.onState(state(), concise = true)
        assertEquals(
            listOf("1.2公里后直行"),
            GuidanceController.onState(
                state(step = 1, remain = 1250, action = "直行", road = "无名道路"), true
            )
        )
    }

    @Test
    fun denseManeuversGetSingleAnnouncementPerStep() {
        GuidanceController.reset()
        // 短 step：step 切换时已在接近区 → 只播 next 一条，不补接近提醒
        assertEquals(
            listOf("100米后右转，进入X路"),
            GuidanceController.onState(state(remain = 100, seconds = 8, action = "右转"), true)
        )
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(remain = 80, seconds = 6, action = "右转"), true)
        )
        // 连续密集机动：每个 step 各一条（下限，不可再省）
        assertEquals(
            listOf("60米后左转，进入Y路"),
            GuidanceController.onState(
                state(step = 1, remain = 60, seconds = 5, action = "左转", road = "Y路"), true
            )
        )
    }

    @Test
    fun detailedModeStaysSilent() {
        GuidanceController.reset()
        assertEquals(emptyList<String>(), GuidanceController.onState(state(), concise = false))
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(remain = 100, seconds = 5), concise = false)
        )
    }

    @Test
    fun arrivedProducesNothing() {
        GuidanceController.reset()
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(arrived = true), concise = true)
        )
    }

    @Test
    fun distanceFormatting() {
        // 与转向卡同梯级取整（naviRoundMeters），播报/显示一致
        assertEquals("950米", GuidanceController.fmtDist(999))
        assertEquals("200米", GuidanceController.fmtDist(243))
        assertEquals("60米", GuidanceController.fmtDist(64))
        assertEquals("1.0公里", GuidanceController.fmtDist(1000))
        assertEquals("1.2公里", GuidanceController.fmtDist(1250))
        assertEquals("2.0公里", GuidanceController.fmtDist(2049))
        assertEquals("12公里", GuidanceController.fmtDist(12340))
    }

    // ── 区间测速播报（speedZone 驱动；每区间一次，区间消失复位锁）──

    @Test
    fun speedZoneApproachAndEnterEachAnnounceOnce() {
        absorbManeuver()
        val approach = SpeedZone(
            limitKmh = 80, totalMeters = 3000, remainMeters = 2800,
            inside = false, fullEntry = true
        )
        assertEquals(
            listOf("前方区间测速，限速80"),
            GuidanceController.onState(state(speedZone = approach), concise = true)
        )
        // 同态重复：静默
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(speedZone = approach), concise = true)
        )
        val inside = approach.copy(inside = true, averageKmh = -1, remainMeters = 2400)
        assertEquals(
            listOf("进入区间测速，限速80，全长3公里"),
            GuidanceController.onState(state(speedZone = inside), concise = true)
        )
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(speedZone = inside), concise = true)
        )
    }

    @Test
    fun speedZoneRemainLadderAnnouncesAtSteps() {
        absorbManeuver()
        var z = SpeedZone(
            limitKmh = 80, totalMeters = 2000, remainMeters = 1500,
            inside = true, fullEntry = true
        )
        GuidanceController.onState(state(speedZone = z), concise = true) // 进入播报吸收
        // 剩余 1500m：两档（1km/500m）均未到，静默
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(speedZone = z), concise = true)
        )
        // 跌破 1km：第一档播一次
        z = z.copy(remainMeters = 900, averageKmh = 75)
        assertEquals(
            listOf("区间测速剩余${GuidanceController.fmtDist(900)}"),
            GuidanceController.onState(state(speedZone = z), concise = true)
        )
        // 同档重复：静默
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(speedZone = z), concise = true)
        )
        // 跌破 500m：第二档播一次
        z = z.copy(remainMeters = 420)
        assertEquals(
            listOf("区间测速剩余${GuidanceController.fmtDist(420)}"),
            GuidanceController.onState(state(speedZone = z), concise = true)
        )
    }

    @Test
    fun speedZoneAverageOverLimitFlipsOnceForFullEntry() {
        absorbManeuver()
        var z = SpeedZone(
            limitKmh = 80, totalMeters = 3000, remainMeters = 2000,
            inside = true, fullEntry = true, averageKmh = 75
        )
        GuidanceController.onState(state(speedZone = z), concise = true) // 进入播报吸收
        // 均值升至超限：播一次（翻转锁）
        z = z.copy(averageKmh = 92, remainMeters = 1500)
        assertEquals(
            listOf("区间平均速度已超限速，请减速"),
            GuidanceController.onState(state(speedZone = z), concise = true)
        )
        // 持续超限：静默（不轰炸）
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(speedZone = z.copy(averageKmh = 95)), concise = true)
        )
        // 回落再超限：重新触发
        GuidanceController.onState(state(speedZone = z.copy(averageKmh = 70)), concise = true)
        assertEquals(
            listOf("区间平均速度已超限速，请减速"),
            GuidanceController.onState(state(speedZone = z.copy(averageKmh = 90)), concise = true)
        )
    }

    /** 中途汇入（直接 ENTER 无接近态）：进入照播、剩余照播，均值不可信不播。 */
    @Test
    fun midJoinZoneSkipsAverageAnnouncement() {
        absorbManeuver()
        val z = SpeedZone(
            limitKmh = 100, totalMeters = 5000, remainMeters = 3000,
            inside = true, fullEntry = false, averageKmh = 130
        )
        assertEquals(
            listOf("进入区间测速，限速100，全长5公里"),
            GuidanceController.onState(state(speedZone = z), concise = true)
        )
        // 均值"超限"但 fullEntry=false（SDK 跟踪基线偏晚）：不播均值提醒
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(speedZone = z.copy(remainMeters = 2900)), concise = true)
        )
    }

    /** 中途驶出/区间消失：锁复位，下个区间重新播（部分路程数据不残留）。 */
    @Test
    fun zoneLeaveResetsLocksForNextZone() {
        absorbManeuver()
        val z = SpeedZone(
            limitKmh = 60, totalMeters = 2000, remainMeters = 1000,
            inside = true, fullEntry = true
        )
        GuidanceController.onState(state(speedZone = z), concise = true) // 进入播报
        // 驶出（LEAVE → zone 清除，NaviSession 不再发布区间态）：静默 + 复位
        assertEquals(
            emptyList<String>(),
            GuidanceController.onState(state(), concise = true)
        )
        // 下个区间：接近播报重新出现（锁已复位）
        val next = SpeedZone(
            limitKmh = 80, totalMeters = 3000, remainMeters = 2800,
            inside = false, fullEntry = true
        )
        assertEquals(
            listOf("前方区间测速，限速80"),
            GuidanceController.onState(state(speedZone = next), concise = true)
        )
    }
}
