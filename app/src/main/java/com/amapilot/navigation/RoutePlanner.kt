package com.amapilot.navigation

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.NaviLatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 规划完成的驾车路线。 */
data class PlannedRoute(
    /** 引擎路线 ID（naviPaths key，12/13/14…），导航开始前选中该路线用。 */
    val pathId: Int,
    val points: List<LatLng>,
    val lengthMeters: Int,
    val durationSeconds: Int,
    /** SDK 路线差异标签（如"时间最短、红绿灯少"）。 */
    val labels: String = "",
    val trafficLights: Int = 0
)

/** 路线规划失败（高德错误码）。 */
class RouteException(val code: Int, message: String) : Exception(message)

/** 驾车路线策略（多路线变体：保证返回 naviPaths 备选卡片）。 */
enum class DriveStrategy(val label: String, val naviStrategy: Int) {
    DEFAULT("默认", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT),
    AVOID_CONGESTION("躲避拥堵", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_CONGESTION),
    AVOID_HIGHWAY("不走高速", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED),
    PREFER_HIGHWAY("高速优先", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_PRIORITY_HIGHSPEED)
}

/**
 * 驾车路线规划：多路径管理与途经点设置。基于 [AMapNavi] 单例，
 * 回调经 [suspendCancellableCoroutine] 转挂起；须在隐私同意后使用。
 */
class RoutePlanner(context: Context) {

    private val appContext = context.applicationContext
    private val navi: AMapNavi = AMapNavi.getInstance(appContext)

    /**
     * 规划驾车路线（多路径，最多 3 条备选；策略取设置页默认偏好）。
     * @param wayPoints 途经点（按列表顺序生效）
     * @return 备选路线列表，首条为推荐路线
     */
    suspend fun planDriveRoutes(
        from: LatLng,
        to: LatLng,
        wayPoints: List<LatLng> = emptyList()
    ): List<PlannedRoute> = suspendCancellableCoroutine { cont ->
        val listener = object : SimpleNaviListener() {
            override fun onCalculateRouteSuccess(ids: IntArray) {
                navi.removeAMapNaviListener(this)
                if (!cont.isActive) return
                val routes = navi.naviPaths.entries
                    .sortedBy { it.key }
                    .map { (id, path) ->
                        PlannedRoute(
                            pathId = id,
                            points = path.coordList.map { LatLng(it.latitude, it.longitude) },
                            lengthMeters = path.allLength,
                            durationSeconds = path.allTime,
                            labels = path.labels.orEmpty(),
                            trafficLights = path.trafficLightCount
                        )
                    }
                if (routes.isNotEmpty()) {
                    cont.resume(routes)
                } else {
                    cont.resumeWith(Result.failure(RouteException(-1, "no route returned")))
                }
            }

            override fun onCalculateRouteFailure(error: Int) {
                navi.removeAMapNaviListener(this)
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(RouteException(error, "route calc failed: $error")))
                }
            }
        }
        navi.addAMapNaviListener(listener)
        android.util.Log.d(
            "AmapilotPoi",
            "planRoute from=(${from.latitude},${from.longitude}) to=(${to.latitude},${to.longitude})"
        )
        // 注意：4 参重载实际语义为 (from, to, wayPoints, strategy)（实际语义验证）；
        // 3 参版本是 (to, wayPoints)，起点取引擎当前位置，勿误用
        val accepted = navi.calculateDriveRoute(
            listOf(NaviLatLng(from.latitude, from.longitude)),
            listOf(NaviLatLng(to.latitude, to.longitude)),
            wayPoints.map { NaviLatLng(it.latitude, it.longitude) },
            com.amapilot.settings.Prefs.routeStrategyInt(appContext)
        )
        if (!accepted) {
            navi.removeAMapNaviListener(listener)
            if (cont.isActive) {
                cont.resumeWith(Result.failure(RouteException(-2, "calculateDriveRoute rejected")))
            }
        }
        cont.invokeOnCancellation { navi.removeAMapNaviListener(listener) }
    }
}
