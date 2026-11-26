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
    ) = NaviState(
        stepIndex = step,
        stepRemainMeters = remain,
        stepRemainSeconds = seconds,
        actionText = action,
        nextRoad = road,
        arrived = arrived,
    )

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
}
