package com.amapilot.auto

import android.content.Context
import android.util.Log
import com.amap.api.maps.model.LatLng
import com.amapilot.navigation.CruiseSession
import com.amapilot.navigation.NaviSession
import com.amapilot.navigation.RoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 车机端发起导航（与手机端同语义）：先 RoutePlanner 算路 → NaviSession.start。
 * 直接 start 时引擎无路线数据（导航卡 0 m / 0 min）；算路失败仍继续 start 由引擎自算。
 */
object CarRouteStarter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null

    fun start(context: Context, dest: LatLng) {
        appContext = context.applicationContext
        if (CruiseSession.isActive) CruiseSession.stop()
        // 重导航必须先整体收掉旧会话：引擎导航中会吞掉 calculateDriveRoute 回调
        // （协程挂死），且 startNavi 在导航中会被忽略——旧路线/路程/方向全部残留
        if (NaviSession.isActive) NaviSession.stop()
        // 保留搜索卡显示算路中（先关卡再异步算 = 期间无反馈）
        CarMapControl.setSearchStatus("算路中…")
        scope.launch {
            // 算路超时兜底：即使回调丢失也继续 start（引擎按当前位置自算）
            val routes = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                runCatching {
                    val origin = currentLocation(context) ?: return@runCatching null
                    RoutePlanner(context.applicationContext)
                        .planDriveRoutes(origin, dest, emptyList())
                }.getOrNull()
            }
            CarMapControl.setSearchStatus(null)
            if (routes != null && routes.size > 1) {
                // 多路线先选后启（selectRouteId 仅导航前生效，先 start 再切会被拒）
                val rows = routes.mapIndexed { i, r ->
                    RouteSelOption(
                        pathId = r.pathId,
                        label = "路线${i + 1}",
                        timeText = "${kotlin.math.round(r.durationSeconds / 60.0)} 分钟",
                        distText = formatDist(r.lengthMeters),
                        lightsText = "红绿灯 ${r.trafficLights}",
                        deltaText = if (i == 0) "推荐" else "",
                        current = i == 0,
                    )
                }
                if (CarMapControl.onOpenRouteSelect != null) {
                    CarMapControl.openPreNavSelect(
                        rows, dest, routes.associate { it.pathId to it.points }
                    )
                    Log.i("CarRouteStarter", "pre-nav select: ${rows.size} routes")
                } else {
                    // 无选路屏钩子（手机独立）：直接采纳推荐路线，避免静默挂起
                    Log.i(
                        "CarRouteStarter",
                        "phone fallback: auto-commit recommended route ${routes[0].pathId}"
                    )
                    NaviSession.start(
                        context, naviEmulatorMode(context), dest, initialPathId = routes[0].pathId
                    )
                }
            } else {
                NaviSession.start(
                    context, naviEmulatorMode(context), dest,
                    initialPathId = routes?.firstOrNull()?.pathId
                )
            }
        }
    }

    /** 导航前选路行点击：以对应路线发起导航（引擎导航前 selectRouteId 生效）。 */
    fun commitPreNav(pathId: Int) {
        val ctx = appContext ?: return
        val dest = CarMapControl.preNavDest ?: return
        CarMapControl.preNavSelectMode = false
        CarMapControl.preNavDest = null
        CarMapControl.routeSelect.value = null
        Log.i("CarRouteStarter", "commitPreNav pathId=$pathId")
        // 单击定稿：行点击 = start + 关屏回跟随/转向卡
        NaviSession.start(ctx, naviEmulatorMode(ctx), dest, initialPathId = pathId)
    }

    private fun formatDist(m: Int): String =
        if (m >= 1000) String.format("%.1f 公里", m / 1000.0) else "$m 米"

    /** 当前位置：巡航/导航会话位置优先，退回系统 GPS 最近定位，再退回系统融合
     *  最近定位（室内无 GPS fix 时 lastKnown 为 null）。 */
    private fun currentLocation(context: Context): LatLng? {
        (CruiseSession.location.value ?: NaviSession.location.value)?.coord?.let {
            return LatLng(it.latitude, it.longitude)
        }
        // 显式权限检查（lint MissingPermission 不识别 runCatching 兜底）
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching {
                val lm = context.getSystemService(android.location.LocationManager::class.java)
                lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)?.let {
                    return LatLng(it.latitude, it.longitude)
                }
                lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)?.let {
                    return LatLng(it.latitude, it.longitude)
                }
            }
        }
        return com.amapilot.map.SystemLocations.lastKnownGcj(context)
    }

    internal fun isEmulatorDevice(): Boolean =
        android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.PRODUCT.contains("sdk") ||
            android.os.Build.HARDWARE.contains("ranchu")

    /**
     * 导航采用 EMULATOR 模拟模式还是 GPS 实驱。真机恒 GPS；模拟器默认 EMULATOR，
     * `amapilot_force_gps_navi` 置 1 强制 GPS（配合 `adb emu geo fix` 注入）。
     */
    internal fun naviEmulatorMode(context: android.content.Context): Boolean {
        val forced = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver, "amapilot_force_gps_navi", 0
            )
        }.getOrDefault(0) == 1
        return !forced && isEmulatorDevice()
    }
}
