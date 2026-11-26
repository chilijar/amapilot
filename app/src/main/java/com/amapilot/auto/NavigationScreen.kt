package com.amapilot.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import com.amapilot.R
import com.amapilot.navigation.CruiseSession
import com.amapilot.navigation.NaviSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

// ── 车机跟随平滑/过冲防护参数（见 ticker 内注释）──
/** 外推锚龄帽（ms）：引擎 fix 断流时最多前推 1.5s，超龄即回跳（防过冲）。 */
private const val DR_AGE_CAP_MS = 1_500L

/** 平滑残差直接吸附阈值（米）：重定位/换源/断流恢复级的大跳不走恒速吸收。 */
private const val DR_SMOOTH_SNAP_M = 60.0

/** 平滑吸收基础速率（m/s，另加当前车速×1.0）：系数必须 ≥1.0，否则显示位
 *  滞后累积到 snap 阈值 = 周期性顿挫。 */
private const val DR_SMOOTH_BASE_RATE_MS = 4f

/**
 * 车机单屏：按会话状态渲染空闲/导航/巡航三态模板。
 *
 * 地图为自绘管线（VirtualDisplay + CarMapPresentation）：宿主在地图模板下派发
 * Surface，高德直接渲染。ticker 每 250ms 驱动相机；模板仅在会话状态切换时经
 * [Screen.invalidate] 重发（高频重发会让内容卡入场动画反复重播）。
 */
class NavigationScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = android.os.Handler(carContext.mainLooper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 高德自绘地图管线（VirtualDisplay：宿主 Surface 为虚拟屏合成目标）。 */
    private val carMap = CarMapDisplay(carContext)

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            android.util.Log.i("CarMapDisplay", "onSurfaceAvailable ${surfaceContainer.width}x${surfaceContainer.height} dpi=${surfaceContainer.dpi} hasSurface=${surfaceContainer.surface != null}")
            val carSurface = surfaceContainer.surface ?: run {
                // 空 Surface 不算收到：保持 surfaceReceived=false 逼宿主销毁重建
                // （宿主派发空 Surface 后不会主动重派 = 僵尸黑屏）
                android.util.Log.w("CarMapDisplay", "surface 为 null，保持重注册重试")
                return
            }
            surfaceReceived = true
            carMap.start(carSurface, surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            android.util.Log.i("CarMapDisplay", "onSurfaceDestroyed")
            surfaceReceived = false // 恢复重注册重试（宿主 ANR 循环后会重新派发）
            carMap.stop()
        }

        /** 宿主可见区变化（分屏/宿主 chrome 遮挡）：自绘锚点改用可见区计算。 */
        override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
            android.util.Log.i(
                "CarMapDisplay",
                "onVisibleAreaChanged $visibleArea (surface " +
                    "${CarMapControl.metrics.value.width}x${CarMapControl.metrics.value.height})"
            )
            CarMapControl.setVisibleArea(
                visibleArea.left, visibleArea.top,
                visibleArea.right, visibleArea.bottom
            )
        }

        override fun onScroll(dx: Float, dy: Float) {
            android.util.Log.i("CarMapDisplay", "surface onScroll ($dx, $dy)")
            CarMapControl.scrollBy(dx, dy)
        }

        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
            CarMapControl.zoomBy(scaleFactor)
        }

        override fun onClick(x: Float, y: Float) {
            // 自绘 UI 的点按命中（Compose 点击回调不接收车机触摸）
            android.util.Log.i("CarMapDisplay", "surface onClick ($x, $y)")
            CarMapControl.handleClick(x.toInt(), y.toInt())
        }
    }

    private val ticker = object : Runnable {
        private var tick = 0
        private var cardTick = 0
        private var lastNavi = NaviSession.isActive
        private var lastCruise = CruiseSession.isActive
        private var lastOverview = false
        private var lastCamMode = CarMapControl.cameraMode.value

        // 外推锚点（dead reckoning）：fix 间按速度×航向前推，相机每 250ms 跟随外推位
        private var anchorLat = 0.0
        private var anchorLng = 0.0
        private var anchorElapsedMs = 0L

        // ── 过冲防护：fix 间位移速度 ──
        // 两拍 fix 间距 ÷ 时距；引擎 currentSpeed 有节拍滞后，急刹时位移速度先归零。
        // EMA(0.5) 压单拍 GPS 噪声。
        private var lastFixLat = 0.0
        private var lastFixLng = 0.0
        private var lastFixSeenMs = 0L
        private var fixSpeedEmaMs = 0f

        // ── 显示位平滑器 ──
        // 新 fix 含离散修正，直接消费 = 1Hz 速度阶跃顿挫。残差按**恒速**吸收
        // （指数衰减会在大误差首帧爆速度尖峰）；稳态残差≈0。
        private var smoothLat = 0.0
        private var smoothLng = 0.0
        private var smoothValid = false
        private var lastFrameMs = 0L

        // 沿路线外推状态：路线引用变化时重建几何；偏线/无路线回退直线外推
        private var lastRoutePts: List<com.amap.api.maps.model.LatLng>? = null
        private var routeLastCum = -1.0

        override fun run() {
            // 模板只在会话状态切换时重发（每次重发都会重播内容卡入场动画）
            val navi = NaviSession.isActive
            val cruise = CruiseSession.isActive
            val overview = CarMapControl.overviewActive.value
            val camMode = CarMapControl.cameraMode.value
            if (navi != lastNavi || cruise != lastCruise ||
                overview != lastOverview || camMode != lastCamMode
            ) {
                val prevNavi = lastNavi
                lastNavi = navi
                lastCruise = cruise
                lastOverview = overview
                lastCamMode = camMode
                invalidate()
                // 导航所有权同步：started 后宿主 ✕ 才过库闸门；停止须 ended（幂等）
                if (navi != prevNavi) {
                    runCatching {
                        val nm = carContext.getCarService(
                            androidx.car.app.navigation.NavigationManager::class.java
                        )
                        if (navi) nm.navigationStarted() else nm.navigationEnded()
                        android.util.Log.i("CarMapDisplay", "navManager sync: navi=$navi")
                    }.onFailure {
                        android.util.Log.w("CarMapDisplay", "navManager sync failed", it)
                    }
                }
            }
            val active = navi || cruise
            // 宿主行程卡 = 模板快照：导航中每 5s 低频重发让快照跟随 state
            // （更密会反复重播宿主入场动画）
            if (navi && ++cardTick % 20 == 0) invalidate()
            // 相机跟随（3D）：导航看 NaviSession，巡航看 CruiseSession；
            val loc = if (NaviSession.isActive) NaviSession.location.value
                else CruiseSession.location.value
            val coord = loc?.coord
            if (coord != null) {
                // 速度取归一化状态流（km/h）；AMapNaviLocation.speed 单位混杂不可用
                val speedKmh = when {
                    NaviSession.isActive -> NaviSession.state.value.speedKmh.toFloat()
                    CruiseSession.isActive -> CruiseSession.state.value.speedKmh.toFloat()
                    else -> 0f
                }
                // 航向：引擎路匹配航向优先；GPS 航向仅行驶中可信（静止时漂移/归 0），
                // 传 0 = Presentation 层 sticky 保持最近解算航向
                val bearing = when {
                    loc.roadBearing in 0.1f..359.9f -> loc.roadBearing
                    speedKmh > 5f && loc.bearing in 0.1f..359.9f -> loc.bearing
                    else -> 0f
                }
                // 距下一机动（导航态才有；巡航/空闲省略 = 纯速度曲线）
                val maneuverRemain = if (NaviSession.isActive) {
                    NaviSession.state.value.stepRemainMeters.toFloat()
                } else {
                    Float.MAX_VALUE
                }
                // 帧间外推 + 显示位平滑（三层）：
                // ① 新 fix 重置锚点并推算位移速度；② 外推速度 = min(状态速度,
                // 位移速度×1.25+1)；③ 残差恒速吸收进显示位，速度连续。
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (coord.latitude != anchorLat || coord.longitude != anchorLng ||
                    anchorElapsedMs == 0L
                ) {
                    if (anchorElapsedMs > 0L && lastFixSeenMs > 0L) {
                        val dtFix = ((nowMs - lastFixSeenMs) / 1000f).coerceIn(0.2f, 5f)
                        val dLat = (coord.latitude - lastFixLat) * 111_320.0
                        val dLng = (coord.longitude - lastFixLng) * 111_320.0 *
                            kotlin.math.cos(Math.toRadians(coord.latitude))
                        val implied =
                            (kotlin.math.hypot(dLat, dLng) / dtFix).toFloat()
                        fixSpeedEmaMs =
                            if (fixSpeedEmaMs <= 0f) implied
                            else fixSpeedEmaMs + (implied - fixSpeedEmaMs) * 0.5f
                    }
                    lastFixLat = coord.latitude
                    lastFixLng = coord.longitude
                    lastFixSeenMs = nowMs
                    anchorLat = coord.latitude
                    anchorLng = coord.longitude
                    anchorElapsedMs = nowMs
                }
                val ageMs = (nowMs - anchorElapsedMs).coerceIn(0L, DR_AGE_CAP_MS)
                val stateMs = speedKmh / 3.6f
                // 位移速度 2s 内有效：fix 断流期回退状态速度（锚龄帽已限外推时长）
                val fixCapMs = if (fixSpeedEmaMs > 0f && nowMs - lastFixSeenMs < 2_000L) {
                    fixSpeedEmaMs * 1.25f + 1f
                } else {
                    Float.MAX_VALUE
                }
                val vMs = kotlin.math.min(stateMs, fixCapMs)
                // 路线几何灌入（沿路线外推用）：路线流引用变化 = 新路线/重算
                val ptsNow = if (NaviSession.isActive) NaviSession.routePoints.value else null
                if (ptsNow !== lastRoutePts) {
                    lastRoutePts = ptsNow
                    RouteFollowGeom.update(ptsNow.orEmpty())
                    routeLastCum = -1.0
                }
                var frameLat = coord.latitude
                var frameLng = coord.longitude
                var frameSrc = "hold"
                if (bearing in 0.1f..359.9f && vMs > 1f) {
                    val aheadM = vMs * (ageMs / 1000f)
                    // 沿路线外推：投影 → 弧长前推 → 插值坐标（弯道不切角；
                    // 无路线/偏线回退直线外推）
                    val adv = RouteFollowGeom.advance(
                        coord.latitude, coord.longitude, aheadM.toDouble(), routeLastCum
                    )
                    if (adv != null) {
                        routeLastCum = adv.cum
                        frameLat = adv.lat
                        frameLng = adv.lng
                        frameSrc = "route"
                    } else {
                        routeLastCum = -1.0
                        val rad = Math.toRadians(bearing.toDouble())
                        frameLat = coord.latitude +
                            aheadM * kotlin.math.cos(rad) / 111_320.0
                        frameLng = coord.longitude +
                            aheadM * kotlin.math.sin(rad) /
                            (111_320.0 * kotlin.math.cos(Math.toRadians(coord.latitude)))
                        frameSrc = "line"
                    }
                }
                // 残差恒速吸收（dt 按真实帧间隔）；>60m（重定位/断流恢复）直接吸附
                val dtFrame = if (lastFrameMs > 0L) {
                    ((nowMs - lastFrameMs) / 1000f).coerceIn(0.05f, 1f)
                } else {
                    0.25f
                }
                lastFrameMs = nowMs
                var smoothErrM = 0.0
                if (!smoothValid) {
                    smoothValid = true
                    smoothLat = frameLat
                    smoothLng = frameLng
                } else {
                    val eLat = (frameLat - smoothLat) * 111_320.0
                    val eLng = (frameLng - smoothLng) * 111_320.0 *
                        kotlin.math.cos(Math.toRadians(smoothLat))
                    val errM = kotlin.math.hypot(eLat, eLng)
                    smoothErrM = errM
                    when {
                        errM > DR_SMOOTH_SNAP_M -> {
                            smoothLat = frameLat
                            smoothLng = frameLng
                        }
                        errM > 0.1 -> {
                            // 速率 = 基础 + 车速×1.0：稳态与目标同速锁定，修正按富余速率吸收
                            val rate = DR_SMOOTH_BASE_RATE_MS + stateMs
                            val f = kotlin.math.min(errM, (rate * dtFrame).toDouble()) / errM
                            smoothLat += (frameLat - smoothLat) * f
                            smoothLng += (frameLng - smoothLng) * f
                        }
                    }
                }
                // 诊断（D 级，每帧——尖峰在帧内发生，节流会丢失）：
                // src=route 沿路线 / line 直线 / hold 静止；err=目标与显示位残差
                android.util.Log.d(
                    "CarMapDisplay",
                    "follow: src=$frameSrc vMs=${vMs.toInt()} cap=${fixCapMs.toInt()} " +
                        "age=${ageMs}ms err=${"%.1f".format(smoothErrM)}m"
                )
                CarMapControl.moveCamera(
                    smoothLat, smoothLng, bearing, speedKmh, maneuverRemain
                )
            }
            // 重注册（幂等）：**仅在尚未收到 Surface 时**每 10s 重试——注册会触发
            // 宿主销毁重建 Surface，拿到后必须停手
            if (!surfaceReceived && ++tick % 40 == 0) {
                runCatching {
                    carContext.getCarService(androidx.car.app.AppManager::class.java)
                        .setSurfaceCallback(surfaceCallback)
                }
            }
            // 相机跟随帧率：active 250ms（外推帧，见上）/ 空闲 2s（箭头由 SDK 定位层驱动）
            handler.postDelayed(this, if (active) 250L else 2000L)
        }
    }

    /** 已收到宿主 Surface（此后停止重注册，避免触发宿主循环重建）。 */
    @Volatile
    private var surfaceReceived = false

    init {
        carContext.getCarService(androidx.car.app.AppManager::class.java)
            .setSurfaceCallback(surfaceCallback)
        // 冷启动双 bind 死通道自愈：备份健康 IAppHost binder，延时后台探测（HostDispatchHealer）
        HostDispatchHealer.capture(carContext)
        handler.postDelayed(
            { Thread { HostDispatchHealer.probeAndHeal { invalidate() } }.start() },
            8000L
        )
        handler.postDelayed(
            { Thread { HostDispatchHealer.probeAndHeal { invalidate() } }.start() },
            20000L
        )
        // 注入车机自绘 UI 的动作回调（Presentation 内的按钮 → 宿主模板屏）
        CarMapControl.onSearch = {
            // SDK 原生搜索屏（宿主搜索栏+车机键盘+结果列表）；重复按不叠栈
            if (screenManager.top !is CarSearchScreen) {
                android.util.Log.i("CarMapDisplay", "search pressed -> push CarSearchScreen")
                screenManager.push(CarSearchScreen(carContext))
            }
        }
        // 手机中继：有输入且搜索屏未开 = 自动 push 首页（顶置搜索栏接词切结果态）
        scope.launch {
            CarSearchRelay.query.collect { q ->
                if (q.isNotBlank() && screenManager.top !is CarSearchScreen) {
                    android.util.Log.i("CarMapDisplay", "relay query -> push CarSearchScreen")
                    screenManager.push(CarSearchScreen(carContext))
                }
            }
        }
        CarMapControl.favoritesLoader = {
            com.amapilot.data.FavoriteRepository(carContext).load()
        }
        CarMapControl.onSearchSelect = { row, asWaypoint ->
            if (asWaypoint && NaviSession.isActive) {
                NaviSession.addWaypoint(
                    row.title,
                    com.amap.api.maps.model.LatLng(row.lat, row.lng)
                )
                CarSearchRelay.reset()
            } else {
                // 发起导航不立即关屏：「算路中…」pill 作反馈；不 reset 中继（保留结果复用）
                CarRouteStarter.start(
                    carContext,
                    com.amap.api.maps.model.LatLng(row.lat, row.lng)
                )
            }
        }
        // 选路入口（pushed 屏：宿主 back = pop 回栈下屏）
        CarMapControl.onOpenRouteSelect = {
            if (screenManager.top !is CarRouteSelectScreen) {
                screenManager.push(CarRouteSelectScreen(carContext))
            }
        }
        CarMapControl.onAlternates = {
            android.util.Log.i("CarMapDisplay", "alternates pressed -> toggle route select")
            CarMapControl.toggleRouteSelect()
        }
        CarMapControl.onCancelNavi = {
            android.util.Log.i("CarMapDisplay", "cancel navi (card button row)")
            NaviSession.stop()
        }
        CarMapControl.onOverview = {
            android.util.Log.i("CarMapDisplay", "overview toggle (host action strip)")
            CarMapControl.showOverview()
        }
        // 宿主 ✕ / 终止导航回调：app 须先 navigationStarted() 宿主才放行；
        // 不注册则 ✕ 只消宿主卡、引擎导航继续跑
        carContext.getCarService(androidx.car.app.navigation.NavigationManager::class.java)
            .setNavigationManagerCallback(
                { handler.post(it) },
                object : androidx.car.app.navigation.NavigationManagerCallback {
                    override fun onStopNavigation() {
                        android.util.Log.i(
                            "CarMapDisplay",
                            "host onStopNavigation -> NaviSession.stop"
                        )
                        NaviSession.stop()
                    }
                }
            )
        // 到达 → 巡航调度已收敛到 NaviSession.scheduleArrivalCruise（单一所有者）
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                handler.removeCallbacks(ticker)
                carMap.stop()
                scope.cancel()
                HostDispatchHealer.reset()
                CarMapControl.onOpenRouteSelect = null
                CarMapControl.onSearch = null
                CarMapControl.onAlternates = null
                CarMapControl.onCancelNavi = null
                CarMapControl.onOverview = null
                CarMapControl.favoritesLoader = null
                CarMapControl.onSearchSelect = null
            }
        })
        handler.postDelayed(ticker, 1500L)
    }

    override fun onGetTemplate(): Template = runCatching { buildTemplate() }
        .onFailure { android.util.Log.e("CarMapDisplay", "onGetTemplate failed", it) }
        .getOrThrow()

    /**
     * 三态统一用裸 NavigationTemplate：宿主仅对它派发 Surface 手势
     * （onClick/onScroll），MapWithContentTemplate 下拖拽/自绘点击全部失效。
     * 代价：宿主内容卡不可用 → 搜索入口改为自绘胶囊 + 顶栏宿主图标。
     */
    private fun buildTemplate(): Template = navStyleTemplate()



    /**
     * 统一地图模板（三态共用）：自绘 Surface + 状态化顶栏 [topStrip] +
     * 宿主 mapActionStrip 竖排；导航态挂宿主复合行程卡（转向卡仍自绘）。
     */
    private fun navStyleTemplate(): Template {
        // 复合行程卡 = 宿主 RoutingInfo（hostTripCard 开关），自绘整卡仅关闭时兜底
        val b = androidx.car.app.navigation.model.NavigationTemplate.Builder()
            // 顶栏状态化（[topStrip]）：导航 = 四功能钮，空闲/巡航 = 搜索钮
            .setActionStrip(topStrip())
        // 右竖排三态统一 = [缩放+][缩放−][相机 toggle]（[carMapStrip]）
        b.setMapActionStrip(carMapStrip())
        // 宿主复合行程卡：RoutingInfo 机动步 + TravelEstimate（自带 ✕，旋钮可焦）
        if (com.amapilot.navigation.NaviSession.isActive && CarMapControl.hostTripCard) {
            runCatching {
                b.setNavigationInfo(hostRoutingInfo())
                b.setDestinationTravelEstimate(hostTravelEstimate())
            }.onFailure {
                android.util.Log.w("CarMapDisplay", "host trip card build failed", it)
            }
        }
        return b.build()
    }

    /** 宿主行程卡机动步（cue = 动作文字、road = 下条路；正文宿主不渲染）。 */
    private fun hostRoutingInfo(): androidx.car.app.navigation.model.RoutingInfo {
        val s = com.amapilot.navigation.NaviSession.state.value
        val step = androidx.car.app.navigation.model.Step.Builder()
            .apply {
                if (s.actionText.isNotBlank()) setCue(s.actionText)
                if (s.nextRoad.isNotBlank()) setRoad(s.nextRoad)
            }
            .build()
        return androidx.car.app.navigation.model.RoutingInfo.Builder()
            .setCurrentStep(step, hostDistance(s.stepRemainMeters))
            .build()
    }

    /** 宿主复合行程卡数据：剩余距离 + ETA + 剩余秒 + tripText（当前道路）。 */
    private fun hostTravelEstimate(): androidx.car.app.navigation.model.TravelEstimate {
        val s = com.amapilot.navigation.NaviSession.state.value
        val eta = System.currentTimeMillis() + s.routeRemainSeconds * 1000L
        return androidx.car.app.navigation.model.TravelEstimate.Builder(
            hostDistance(s.routeRemainMeters),
            androidx.car.app.model.DateTimeWithZone.create(
                eta, java.util.TimeZone.getDefault()
            )
        )
            .setRemainingTimeSeconds(s.routeRemainSeconds.toLong())
            .setTripText(androidx.car.app.model.CarText.create(s.currentRoad))
            .build()
    }

    /** 宿主 Distance：≥1km 一位小数公里档，否则米档。 */
    private fun hostDistance(m: Int): androidx.car.app.model.Distance =
        if (m >= 1000) {
            androidx.car.app.model.Distance.create(
                Math.round(m / 100.0) / 10.0,
                androidx.car.app.model.Distance.UNIT_KILOMETERS_P1
            )
        } else {
            androidx.car.app.model.Distance.create(m.toDouble(), androidx.car.app.model.Distance.UNIT_METERS)
        }

    /** 顶栏：导航态 = 按功能独立图标钮 [选路][途经点][改目的地][设置]（旋钮焦点环
     *  直达，宿主 4 动作硬上限；取消走宿主卡 ✕）；空闲/巡航 = 搜索钮
     *  （NavigationTemplate 强制 ActionStrip，省略抛 IllegalStateException）。 */
    private fun topStrip(): ActionStrip {
        val b = ActionStrip.Builder()
        if (com.amapilot.navigation.NaviSession.isActive) {
            // 最左 = 选路
            b.addAction(stripIconAction(R.drawable.ic_car_routes) {
                CarMapControl.flashAction("alternates")
                CarMapControl.onAlternates?.invoke()
            })
            b.addAction(stripIconAction(R.drawable.ic_car_waypoint) {
                CarMapControl.waypointMode.value = true
                CarMapControl.flashAction("waypoint")
                CarMapControl.onSearch?.invoke()
            })
            b.addAction(stripIconAction(R.drawable.ic_car_flag) {
                CarMapControl.waypointMode.value = false
                CarMapControl.flashAction("search")
                CarMapControl.onSearch?.invoke()
            })
            b.addAction(stripIconAction(R.drawable.ic_car_settings) {
                android.util.Log.i("CarMapDisplay", "settings pressed -> push CarSettingsScreen")
                if (screenManager.top !is CarSettingsScreen) {
                    screenManager.push(CarSettingsScreen(carContext))
                }
            })
            return b.build()
        }
        // 空闲/巡航顶栏 = 搜索钮（空 strip 抛 IllegalStateException = 宿主错误屏）
        return b
            .addAction(stripIconAction(R.drawable.ic_car_search) {
                CarMapControl.flashAction("search")
                CarMapControl.onSearch?.invoke()
            })
            .build()
    }

    /**
     * 宿主 mapActionStrip 右竖排 = 三态统一的 [缩放+][缩放−][相机 toggle]
     * （**宿主硬上限 4 动作**，超限抛异常）。**导航态勿撤竖排**：车速环几何锚定
     * 本竖排左缘（[CarUiGeometry.speedBadgeX]），撤掉会悬空；真要撤须同步改
     * speedBadgeX 直锚 m.right。
     */
    private fun carMapStrip(): ActionStrip {
        val b = ActionStrip.Builder()
        // 相机三态 toggle（跟随/北向上/全览；FOLLOW 拖偏再点 = 回正），纵列最下
        val camIcon = when (CarMapControl.cameraMode.value) {
            CarMapControl.CameraMode.FOLLOW -> R.drawable.ic_car_follow
            CarMapControl.CameraMode.NORTH -> R.drawable.ic_car_north
            CarMapControl.CameraMode.OVERVIEW -> R.drawable.ic_car_overview
        }
        b.addAction(stripIconAction(R.drawable.ic_car_zoom_in) { CarMapControl.zoomBy(1.5f) })
        b.addAction(stripIconAction(R.drawable.ic_car_zoom_out) { CarMapControl.zoomBy(0.5f) })
        b.addAction(stripIconAction(camIcon) { CarMapControl.cycleCameraMode() })
        return b.build()
    }

    /** 竖排图标动作构造。 */
    private fun stripIconAction(res: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(
                CarIcon.Builder(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(
                        carContext, res
                    )
                ).build()
            )
            .setOnClickListener { onClick() }
            .build()
}
