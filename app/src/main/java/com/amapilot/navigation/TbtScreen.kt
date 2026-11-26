package com.amapilot.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.PolylineOptions
import com.amapilot.map.animateCameraTo
import com.amapilot.map.systemGpsSource
import com.amapilot.ui.components.SpeedRing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 自研 TBT 导航屏：地图引擎（3D 车头朝上）+ 圆角卡片 UI。
 * 顶部转向卡 + 底部信息栏（剩余距离·时间·红绿灯 + 退出），数据来自 [NaviSession]。
 *
 * @param emulator 模拟器用模拟导航
 * @param destination 终点坐标（途经点/策略重算用）
 * @param initialPathId 地图页选中的路线 ID（导航开始前选中）
 * @param waypoints 途经点（策略重算沿用）
 * @param onExit 手动退出（调用方保留路线会话）
 * @param onArrived 到达终点自动退出（调用方清空路线会话）
 */
@Composable
fun TbtScreen(
    emulator: Boolean,
    destination: LatLng? = null,
    initialPathId: Int? = null,
    waypoints: List<NaviWaypoint> = emptyList(),
    isNight: Boolean = false,
    onExit: () -> Unit,
    onArrived: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navi by NaviSession.state.collectAsState()
    val lanes by NaviSession.lanes.collectAsState()
    val mapView = remember { com.amapilot.map.MapHolder.acquire(context) }
    var panelExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var chromeShown by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { chromeShown = true }

    DisposableEffect(Unit) {
        // 续导接手：导航已在进行（车机断开回手）时不重启（重启=重算+进度归零）
        if (!NaviSession.isActive) {
            NaviSession.start(context, emulator, destination, initialPathId, waypoints)
        }
        onDispose {
            // 移交车机：车机接管时仅让出显示，导航继续（同进程共享 NaviSession）。
            // 守卫必须同时看会话级 carConnected 与 Surface 级 carSessionActive
            // （Surface/VD 晚数秒才创建，只查后者会误停导航）
            if (!com.amapilot.auto.CarMapControl.carConnected.value &&
                !com.amapilot.auto.CarMapControl.carSessionActive.value
            ) {
                NaviSession.stop()
            }
        }
    }

    // 到达终点：展示到达态 2s 后自动退出，调用方清空整个路线会话（行程已完成）
    LaunchedEffect(navi.arrived) {
        if (navi.arrived) {
            kotlinx.coroutines.delay(2000)
            onArrived()
        }
    }

    LaunchedEffect(isNight) {
        mapView.map.setMapType(
            if (isNight) com.amap.api.maps.AMap.MAP_TYPE_NIGHT
            else com.amap.api.maps.AMap.MAP_TYPE_NORMAL
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                // 共享 MapView：车机会话持有渲染时手机退后台不得暂停——AA 投影
                // 抢前台必触发 ON_PAUSE，暂停会把车机地图一起冻死
                if (!com.amapilot.auto.CarMapControl.carConnected.value &&
                    !com.amapilot.auto.CarMapControl.carSessionActive.value
                ) mapView.onPause()
            }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 共享 MapView：离屏只摘视图，不销毁引擎（销毁后重建需 40~90s）
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 相机三态（与车机 toggle 同语义）：FOLLOW=3D 车头朝上 / NORTH=2D 正北 /
        // OVERVIEW=全览；拖动 → followPaused，FOLLOW 态再点 = 回正
        var cameraMode by remember { androidx.compose.runtime.mutableStateOf(TbtCameraMode.FOLLOW) }
        var followPaused by remember { androidx.compose.runtime.mutableStateOf(false) }
        var lastCamera by remember {
            androidx.compose.runtime.mutableStateOf<com.amap.api.maps.model.CameraPosition?>(null)
        }
        var lastTickLat by remember { androidx.compose.runtime.mutableStateOf(0.0) }
        var lastTickLng by remember { androidx.compose.runtime.mutableStateOf(0.0) }
        var lastTickBearing by remember { androidx.compose.runtime.mutableStateOf(0f) }
        // 跟随态自适应 zoom（与车机单一来源 [AutoZoom]；初值 = 常规上限档）
        var followZoom by remember {
            androidx.compose.runtime.mutableStateOf(com.amapilot.navigation.AutoZoom.MAX)
        }
        // 路线步骤预览模式：previewIndex 非空=预览该步（地图跳路口、顶部步骤卡、底部预览栏）
        var previewIndex by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        val steps by NaviSession.routeSteps.collectAsState()
        val previewStep = previewIndex?.let { steps.getOrNull(it) }
        val previewActive by androidx.compose.runtime.rememberUpdatedState(previewStep != null)
        var showSteps by remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(previewIndex) {
            val i = previewIndex ?: return@LaunchedEffect
            val s = steps.getOrNull(i) ?: return@LaunchedEffect
            followPaused = true
            mapView.map.animateCameraTo(
                com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
                    com.amap.api.maps.model.LatLng(s.lat, s.lng), 17f
                ),
                400
            )
        }
        AndroidView(
            factory = {
                com.amapilot.map.MapHolder.reset(mapView).map.apply {
                    uiSettings.isZoomControlsEnabled = false
                    isTrafficEnabled = true
                    // 拖动地图：暂停当前态的相机跟随（箭头仍随定位更新）
                    setOnMapTouchListener { event ->
                        if (event.action == android.view.MotionEvent.ACTION_MOVE) followPaused = true
                    }
                    // 定位源 = 导航引擎位置（含 EMULATOR 模拟位置）
                    setLocationSource(naviLocationSource())
                    // SDK 图标/精度圈透明化，箭头与相机自控
                    myLocationStyle = MyLocationStyle()
                        .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
                        .myLocationIcon(transparentDescriptor(context))
                        .radiusFillColor(android.graphics.Color.TRANSPARENT)
                        .strokeColor(android.graphics.Color.TRANSPARENT)
                        .strokeWidth(0f)
                    isMyLocationEnabled = true
                }
                mapView
            },
            modifier = Modifier.fillMaxSize()
        )
        var arrowMarker by remember { androidx.compose.runtime.mutableStateOf<com.amap.api.maps.model.Marker?>(null) }
        val arrowIcon = remember { arrowDescriptor(context) }
        var stepMarker by remember { androidx.compose.runtime.mutableStateOf<com.amap.api.maps.model.Marker?>(null) }
        val stepDotIcon = remember { com.amapilot.navigation.stepDotDescriptor() }
        // 各态相机按最近一拍定位即时构建（切态/回正不等下一拍 GPS）
        fun followCamera(): com.amap.api.maps.model.CameraPosition? {
            if (lastTickLat == 0.0) return lastCamera
            val rad = Math.toRadians(lastTickBearing.toDouble())
            val aheadLat = lastTickLat + TBT_OFFSET_LAT * kotlin.math.cos(rad)
            val aheadLng = lastTickLng +
                TBT_OFFSET_LAT / kotlin.math.cos(Math.toRadians(lastTickLat)) *
                kotlin.math.sin(rad)
            return com.amap.api.maps.model.CameraPosition(
                com.amap.api.maps.model.LatLng(aheadLat, aheadLng), 16f, 55f, lastTickBearing
            )
        }

        fun northCamera(): com.amap.api.maps.model.CameraPosition? =
            if (lastTickLat == 0.0) null
            else com.amap.api.maps.model.CameraPosition(
                com.amap.api.maps.model.LatLng(lastTickLat, lastTickLng), 16f, 0f, 0f
            )

        fun fitOverviewCamera() {
            val pts = NaviSession.routePoints.value ?: return
            if (pts.size < 2) return
            // 2D 北向上：newLatLngBounds 保持当前 tilt/bearing——先显式归零
            mapView.map.moveCamera(com.amap.api.maps.CameraUpdateFactory.changeTilt(0f))
            mapView.map.moveCamera(com.amap.api.maps.CameraUpdateFactory.changeBearing(0f))
            val b = com.amap.api.maps.model.LatLngBounds.builder()
            pts.forEach { b.include(it) }
            mapView.map.animateCameraTo(
                com.amap.api.maps.CameraUpdateFactory.newLatLngBounds(
                    b.build(), TBT_OVERVIEW_PAD
                ),
                OVERVIEW_ANIM_MS
            )
        }

        LaunchedEffect(mapView) {
            NaviSession.location.collect { loc ->
                val l = loc ?: return@collect
                val lat = l.coord?.latitude ?: return@collect
                val lng = l.coord?.longitude ?: return@collect
                val bearing = when {
                    l.roadBearing in 0.1f..359.9f -> l.roadBearing
                    l.bearing in 0.1f..359.9f -> l.bearing
                    else -> 0f
                }
                // 自适应缩放（与车机同源 AutoZoom）：速度曲线 + 临近机动按接近时间拉近
                val st = NaviSession.state.value
                followZoom = com.amapilot.navigation.AutoZoom.approach(
                    followZoom,
                    com.amapilot.navigation.AutoZoom.target(
                        st.speedKmh.toFloat(), st.stepRemainMeters.toFloat()
                    )
                )
                lastTickLat = lat
                lastTickLng = lng
                lastTickBearing = bearing
                val position = com.amap.api.maps.model.LatLng(lat, lng)
                // FOLLOW = 3D 前推；其余 = 2D 正北居中（OVERVIEW 取景由切态动作发起）
                val cameraPosition = when (cameraMode) {
                    TbtCameraMode.FOLLOW -> {
                        val rad = Math.toRadians(bearing.toDouble())
                        val aheadLat = lat + TBT_OFFSET_LAT * kotlin.math.cos(rad)
                        val aheadLng = lng +
                            TBT_OFFSET_LAT / kotlin.math.cos(Math.toRadians(lat)) *
                            kotlin.math.sin(rad)
                        com.amap.api.maps.model.CameraPosition(
                            com.amap.api.maps.model.LatLng(aheadLat, aheadLng),
                            followZoom, 55f, bearing
                        )
                    }
                    else -> com.amap.api.maps.model.CameraPosition(position, 16f, 0f, 0f)
                }
                lastCamera = cameraPosition
                val m = arrowMarker
                if (m == null) {
                    arrowMarker = mapView.map.addMarker(
                        com.amap.api.maps.model.MarkerOptions()
                            .position(position)
                            .icon(arrowIcon)
                            .anchor(0.5f, 0.5f)
                            .rotateAngle(0f)
                            .visible(!previewActive)
                    )
                    mapView.map.animateCameraTo(
                        com.amap.api.maps.CameraUpdateFactory.newCameraPosition(cameraPosition),
                        NAV_ENTRY_ANIM_MS
                    )
                } else {
                    m.position = position
                    m.isVisible = !previewActive
                    // FOLLOW：箭头恒指屏上；其余态屏幕恒正北 → 箭头按真实航向旋转。
                    // rotateAngle 对非 flat marker 是逆时针角，与顺时针 bearing 反号 → 取负
                    m.rotateAngle = if (cameraMode == TbtCameraMode.FOLLOW) {
                        0f
                    } else {
                        (360f - bearing % 360f) % 360f
                    }
                    if (cameraMode != TbtCameraMode.OVERVIEW && !followPaused && !previewActive) {
                        mapView.map.moveCamera(
                            com.amap.api.maps.CameraUpdateFactory.newCameraPosition(cameraPosition)
                        )
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                arrowMarker?.remove()
                stepMarker?.remove()
            }
        }
        androidx.compose.runtime.LaunchedEffect(previewStep) {
            arrowMarker?.isVisible = previewStep == null
        }
        androidx.compose.runtime.LaunchedEffect(previewStep) {
            stepMarker?.remove()
            stepMarker = previewStep?.let { s ->
                mapView.map.addMarker(
                    com.amap.api.maps.model.MarkerOptions()
                        .position(com.amap.api.maps.model.LatLng(s.lat, s.lng))
                        .icon(stepDotIcon)
                        .anchor(0.5f, 0.5f)
                )
            }
        }
        var routePolyline by remember {
            androidx.compose.runtime.mutableStateOf<com.amap.api.maps.model.Polyline?>(null)
        }
        LaunchedEffect(Unit) {
            NaviSession.routePoints.collect { pts ->
                routePolyline?.remove()
                routePolyline = null
                val points = pts.orEmpty()
                if (points.isNotEmpty()) {
                    routePolyline = mapView.map.addPolyline(
                        PolylineOptions().addAll(points).width(18f).color(ROUTE_COLOR)
                    )
                }
            }
        }
        DisposableEffect(Unit) { onDispose { routePolyline?.remove() } }

        // 终点/途经点标记：导航中保持可见；途经点观察 NaviSession 实时上图
        val naviWaypoints by NaviSession.waypoints.collectAsState()
        var destMarker by remember {
            androidx.compose.runtime.mutableStateOf<com.amap.api.maps.model.Marker?>(null)
        }
        var waypointMarkers by remember {
            androidx.compose.runtime.mutableStateOf<List<com.amap.api.maps.model.Marker>>(emptyList())
        }
        val destPinIcon = remember { pinDescriptor(context) }
        LaunchedEffect(destination, naviWaypoints) {
            destMarker?.remove()
            destMarker = destination?.let { dest ->
                mapView.map.addMarker(
                    com.amap.api.maps.model.MarkerOptions().position(dest).icon(destPinIcon)
                )
            }
            waypointMarkers.forEach { it.remove() }
            waypointMarkers = naviWaypoints.mapIndexed { index, wp ->
                mapView.map.addMarker(
                    com.amap.api.maps.model.MarkerOptions()
                        .position(LatLng(wp.lat, wp.lng))
                        .icon(waypointDescriptor(context, index + 1))
                        .anchor(0.5f, 0.5f)
                        .zIndex(1500f)
                )
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                destMarker?.remove()
                waypointMarkers.forEach { it.remove() }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = chromeShown,
            enter = androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(
                    com.amapilot.ui.theme.GmMotion.DurationLong,
                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                )
            ) { -it } + androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (previewStep != null) StepPreviewCard(step = previewStep)
            else TurnCard(navi = navi, lanes = lanes)
        }
        // 左下角车速圆环：蓝环正常，红环超速；面板展开时淡出收起
        androidx.compose.animation.AnimatedVisibility(
            visible = chromeShown && !panelExpanded && previewStep == null,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
            ) + androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(
                    com.amapilot.ui.theme.GmMotion.DurationLong,
                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                )
            ) { it / 2 },
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
            ) + androidx.compose.animation.slideOutVertically(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
            ) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 92.dp)
        ) {
            SpeedRing(
                speedKmh = navi.speedKmh,
                limitKmh = navi.limitKmh
            )
        }
        // 预览态：地图区左右滑动切换上/下一步（置于底部栏前，避免盖住预览栏按钮）
        if (previewStep != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var accum = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { accum = 0f },
                            onHorizontalDrag = { change, d ->
                                change.consume()
                                accum += d
                            },
                            onDragEnd = {
                                if (accum < -120) {
                                    previewIndex = ((previewIndex ?: 0) + 1)
                                        .coerceAtMost(steps.lastIndex)
                                } else if (accum > 120) {
                                    previewIndex = ((previewIndex ?: 0) - 1).coerceAtLeast(0)
                                }
                            }
                        )
                    }
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = chromeShown,
            enter = androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(
                    com.amapilot.ui.theme.GmMotion.DurationLong,
                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                )
            ) { it } + androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            if (previewStep != null) {
                PreviewBar(
                    navi = navi,
                    index = previewIndex ?: 0,
                    total = steps.size,
                    step = previewStep,
                    onPrev = { previewIndex = ((previewIndex ?: 0) - 1).coerceAtLeast(0) },
                    onNext = {
                        previewIndex = ((previewIndex ?: 0) + 1).coerceAtMost(steps.lastIndex)
                    },
                    onExit = {
                        previewIndex = null
                        // 按当前相机态恢复取景（FOLLOW/NORTH 回跟随位，OVERVIEW 重全览）
                        followPaused = false
                        when (cameraMode) {
                            TbtCameraMode.OVERVIEW -> fitOverviewCamera()
                            TbtCameraMode.NORTH ->
                                northCamera()?.let {
                                    mapView.map.animateCameraTo(
                                        com.amap.api.maps.CameraUpdateFactory.newCameraPosition(it),
                                        RECENTER_ANIM_MS
                                    )
                                }
                            TbtCameraMode.FOLLOW ->
                                followCamera()?.let {
                                    mapView.map.animateCameraTo(
                                        com.amap.api.maps.CameraUpdateFactory.newCameraPosition(it),
                                        RECENTER_ANIM_MS
                                    )
                                }
                        }
                    }
                )
            } else {
                BottomBar(
                    navi = navi,
                    onExit = onExit,
                    expanded = panelExpanded,
                    onExpandedChange = { panelExpanded = it },
                    onOpenSteps = {
                        panelExpanded = false
                        showSteps = true
                    }
                )
            }
        }
        // 路线步骤半屏 sheet：时间轴列表占下半屏，上半地图可见；点步骤收 sheet 进预览
        if (showSteps) {
            StepsSheet(
                modifier = Modifier.align(Alignment.BottomCenter),
                onBack = { showSteps = false },
                onStepTap = { step ->
                    showSteps = false
                    previewIndex = steps.indexOf(step)
                }
            )
        }
        // 相机三态 toggle（常驻右下，预览态隐藏）：跟随/北向上/全览循环；
        // FOLLOW 被拖偏时再点 = 回正（与车机相机钮同语义）
        androidx.compose.animation.AnimatedVisibility(
            visible = previewStep == null,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
            ) + androidx.compose.animation.scaleIn(
                com.amapilot.ui.theme.GmMotion.quickSpring()
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
            ) + androidx.compose.animation.scaleOut(
                com.amapilot.ui.theme.GmMotion.quickSpring()
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 92.dp)
        ) {
            val camIcon = when (cameraMode) {
                TbtCameraMode.FOLLOW -> com.amapilot.ui.theme.GmIcons.CameraFollow
                TbtCameraMode.NORTH -> com.amapilot.ui.theme.GmIcons.CameraNorth
                TbtCameraMode.OVERVIEW -> com.amapilot.ui.theme.GmIcons.CameraOverview
            }
            com.amapilot.ui.components.GmFab(
                icon = camIcon,
                contentDescription = "视角切换（跟随/北向上/全览）",
                onClick = {
                    when {
                        cameraMode == TbtCameraMode.FOLLOW && followPaused -> {
                            followPaused = false
                            followCamera()?.let {
                                mapView.map.animateCameraTo(
                                    com.amap.api.maps.CameraUpdateFactory.newCameraPosition(it),
                                    RECENTER_ANIM_MS
                                )
                            }
                        }
                        cameraMode == TbtCameraMode.FOLLOW -> {
                            cameraMode = TbtCameraMode.NORTH
                            northCamera()?.let {
                                mapView.map.animateCameraTo(
                                    com.amap.api.maps.CameraUpdateFactory.newCameraPosition(it),
                                    RECENTER_ANIM_MS
                                )
                            }
                        }
                        cameraMode == TbtCameraMode.NORTH -> {
                            cameraMode = TbtCameraMode.OVERVIEW
                            fitOverviewCamera()
                        }
                        else -> {
                            cameraMode = TbtCameraMode.FOLLOW
                            followPaused = false
                            followCamera()?.let {
                                mapView.map.animateCameraTo(
                                    com.amap.api.maps.CameraUpdateFactory.newCameraPosition(it),
                                    RECENTER_ANIM_MS
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

/** 顶部转向卡：转向图标 + 大字距离 + 道路名；下方内嵌车道抽屉（有车道信息时下展）。 */
@Composable
private fun TurnCard(
    navi: NaviState,
    lanes: List<NaviSession.LaneCell>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = ManeuverIcons.maneuverIcon(navi.iconType),
                    contentDescription = null,
                    tint = ACCENT,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = naviDistanceText(navi.stepRemainMeters),
                            style = MaterialTheme.typography.displaySmall,
                            color = ACCENT
                        )
                        Spacer(Modifier.width(8.dp))
                        navi.maneuverLine()?.let { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    navi.alongLine()?.let { along ->
                        Text(
                            text = along,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 车道抽屉：距 maneuver 过远不展示；机动点路口但车道组无人能执行机动
            // 方向（窄路借道）时照常显示但全灰（[laneBarMisleadsManeuver]）
            androidx.compose.animation.AnimatedVisibility(
                visible = lanes.isNotEmpty() &&
                    navi.stepRemainMeters <= NaviSession.LANE_PANEL_MAX_METERS,
                enter = androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                    ),
                exit = androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                    )
            ) {
                // 共用车道行（与车机同实现）：主题色 24dp/44dp + 顶部分割线
                com.amapilot.ui.components.LaneRow(
                    lanes = lanes,
                    active = activeLaneDir(navi.iconType),
                    slight = isSlightManeuver(navi.iconType),
                    linksToManeuver = navi.linksToManeuver,
                    stepRemainMeters = navi.stepRemainMeters,
                    linkRemainMeters = navi.linkRemainMeters,
                    iconSize = 24.dp,
                    separatorHeight = 44.dp,
                    accent = ACCENT,
                    inactive = MaterialTheme.colorScheme.onSurfaceVariant,
                    separatorColor = MaterialTheme.colorScheme.outlineVariant,
                    topDividerColor = MaterialTheme.colorScheme.outlineVariant,
                    horizontalPadding = 20.dp,
                    verticalPadding = 10.dp
                )
            }
        }
    }
}

private enum class PanelTab { NONE, SEARCH, WAYPOINT, SETTINGS }

private val ALONG_CATEGORIES = listOf("加油站", "充电桩", "洗手间")

/**
 * 底部信息栏：单张卡片。收起态 = 退出 / 剩余信息 / 切换路线；展开态 = 横滑
 * 路线卡片 + 功能列表（添加途经点 / 管理途经点[有途经点时] / 路线步骤 / 设置）。
 */
@Composable
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun BottomBar(
    navi: NaviState,
    onExit: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenSteps: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember { androidx.compose.runtime.mutableStateOf(PanelTab.NONE) }
    val panelWaypoints by NaviSession.waypoints.collectAsState()
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        // 系统导航栏 inset 放在 Surface 内部：背景延伸到屏幕底边（与展开态 sheet 一致）
        Column(modifier = Modifier.navigationBarsPadding()) {
            val density = LocalDensity.current
            val swipeThreshold = with(density) { 24.dp.toPx() }
            Box(
                modifier = Modifier.pointerInput(expanded) {
                    var accum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accum = 0f }
                    ) { _, dragAmount ->
                        accum += dragAmount
                        if (!expanded && accum <= -swipeThreshold) {
                            onExpandedChange(true)
                        } else if (expanded && accum >= swipeThreshold) {
                            onExpandedChange(false)
                            tab = PanelTab.NONE
                        }
                    }
                }
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.CenterHorizontally)
                            .padding(top = 6.dp, bottom = 2.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .clickable {
                                onExpandedChange(!expanded)
                                if (!expanded) tab = PanelTab.NONE
                            }
                    )
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!expanded) {
                            IconButton(onClick = onExit) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "退出导航",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                text = naviDurationText(navi.routeRemainSeconds),
                                style = MaterialTheme.typography.headlineMedium,
                                color = ACCENT
                            )
                            Text(
                                text = buildString {
                                    val arrival = java.time.LocalTime.now()
                                        .plusSeconds(navi.routeRemainSeconds.toLong())
                                    append(String.format("%02d:%02d", arrival.hour, arrival.minute))
                                    append(" 到达 · ")
                                    append(naviDistanceText(navi.routeRemainMeters))
                                    if (navi.lightCount > 0) append(" · 红绿灯 ${navi.lightCount}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!expanded) {
                            IconButton(onClick = { onExpandedChange(true) }) {
                                Icon(
                                    com.amapilot.ui.theme.GmIcons.AltRoute,
                                    contentDescription = "切换路线",
                                    tint = ACCENT
                                )
                            }
                        }
                    }
                }
            }
            if (expanded) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = {
                        onExpandedChange(false)
                        tab = PanelTab.NONE
                    },
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    // 仅顶部圆角：贴屏幕底的全宽底部面板（底部圆角会透出遮罩）
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        RouteCardsRow()
                        ListEntry(
                            // 添加途经点 = 走廊搜索（即添加入口）
                            icon = { Icon(Icons.Default.Add, contentDescription = null, tint = ACCENT) },
                            label = "添加途经点",
                            active = tab == PanelTab.SEARCH,
                            onClick = { tab = if (tab == PanelTab.SEARCH) PanelTab.NONE else PanelTab.SEARCH }
                        )
                        if (tab == PanelTab.SEARCH) AlongSearchPanel()
                        if (panelWaypoints.isNotEmpty()) {
                            ListEntry(
                                icon = { Icon(Icons.Default.Place, contentDescription = null, tint = ACCENT) },
                                label = "管理途经点",
                                active = tab == PanelTab.WAYPOINT,
                                onClick = { tab = if (tab == PanelTab.WAYPOINT) PanelTab.NONE else PanelTab.WAYPOINT }
                            )
                            if (tab == PanelTab.WAYPOINT) WaypointPanel()
                        }
                        ListEntry(
                            icon = { Icon(com.amapilot.ui.theme.GmIcons.ListIcon, contentDescription = null, tint = ACCENT) },
                            label = "路线步骤",
                            active = false,
                            onClick = onOpenSteps
                        )
                        ListEntry(
                            icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = ACCENT) },
                            label = "路线设置",
                            active = tab == PanelTab.SETTINGS,
                            onClick = { tab = if (tab == PanelTab.SETTINGS) PanelTab.NONE else PanelTab.SETTINGS }
                        )
                        if (tab == PanelTab.SETTINGS) SettingsPanel()
                    }
                }
            }
        }
    }
}

/** 横滑路线卡片：切换备选路线，居中吸附；选中路线排首位并自动滚到选中页。 */
@Composable
private fun RouteCardsRow() {
    val options by NaviSession.routeOptions.collectAsState()
    val selectedId by NaviSession.selectedPathId.collectAsState()
    if (options.size < 2) return
    // 仅随 options 变化重排（运行中重映射页索引会误切换）
    val ordered = remember(options) { options.sortedByDescending { it.pathId == selectedId } }
    val selectedIndex = ordered.indexOfFirst { it.pathId == selectedId }.takeIf { it >= 0 } ?: 0
    val pagerState = rememberPagerState(initialPage = selectedIndex) { ordered.size }
    // 勿按 settledPage collect 换路（userScrollEnabled=false 后会自我回声，
    // 一次点击触发两次 selectRoute）；换路唯一入口 = 卡片 onClick
    LaunchedEffect(selectedId, ordered) {
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }
    // 固定卡片宽度 + contentPadding：吸附位恒左对齐；userScrollEnabled=false
    // 避开 sheet 拖拽拦截
    Box(modifier = Modifier.padding(bottom = 4.dp)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            pageSize = PageSize.Fixed(TBT_ROUTE_CARD_WIDTH),
            pageSpacing = 10.dp,
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) { page ->
            val option = ordered[page]
            val selected = option.pathId == selectedId
            Surface(
                onClick = { NaviSession.selectRoute(option.pathId) },
                modifier = Modifier.width(TBT_ROUTE_CARD_WIDTH),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = if (selected) 0.dp else 1.dp,
                border = if (selected) androidx.compose.foundation.BorderStroke(
                    2.dp,
                    MaterialTheme.colorScheme.primary
                ) else null
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = naviDurationText(option.durationSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${String.format("%.1f", option.lengthMeters / 1000.0)} 公里 · 红绿灯 ${option.trafficLights}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** TBT 面板路线卡片宽度（横滑吸附页宽）。 */
private val TBT_ROUTE_CARD_WIDTH = 180.dp

@Composable
private fun ListEntry(
    icon: @Composable () -> Unit,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            color = if (active) ACCENT else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
        Text("›", color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * 走廊搜索：剩余路线取 4 个采样点并行搜索、按路线序合并去重
 * （单点搜索 = 周边语义，覆盖不到前方沿途）。
 *
 * @param searcher 搜索封装
 * @param keyword 类别或自定义关键词
 */
private suspend fun alongRouteSearch(
    context: android.content.Context,
    searcher: com.amapilot.map.PoiSearcher,
    keyword: String
): List<com.amapilot.map.SearchedPoi> {
    val route = NaviSession.routePoints.value.orEmpty()
    // 位置链：引擎位置 → 巡航 → 系统 GPS last-known（避免退化为无位置全国搜）
    val cur = (
        NaviSession.location.value?.coord
            ?: com.amapilot.navigation.CruiseSession.location.value?.coord
        )?.let { com.amap.api.maps.model.LatLng(it.latitude, it.longitude) }
        ?: com.amapilot.map.SystemLocations.lastKnownGcj(context)
    // 剩余路线：从距车最近坐标截断（已走过部分不参与采样）
    val remaining = if (cur != null && route.size > 8) {
        var bi = 0
        var bd = Double.MAX_VALUE
        route.forEachIndexed { i, p ->
            val dlat = p.latitude - cur.latitude
            val dlng = p.longitude - cur.longitude
            val d = dlat * dlat + dlng * dlng
            if (d < bd) {
                bd = d
                bi = i
            }
        }
        route.subList(bi, route.size)
    } else {
        route
    }
    val samples = when {
        remaining.size >= 4 -> listOf(
            remaining[remaining.size / 4],
            remaining[remaining.size / 2],
            remaining[remaining.size * 3 / 4],
            remaining.last()
        )
        remaining.isNotEmpty() -> listOf(remaining.last())
        cur != null -> listOf(cur)
        else -> emptyList()
    }
    if (samples.isEmpty()) return emptyList()
    val per = coroutineScope {
        samples.map { s ->
            async {
                runCatching {
                    searcher.searchPoi(
                        keyword,
                        location = com.amap.api.maps.model.LatLng(s.latitude, s.longitude)
                    )
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
    }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<com.amapilot.map.SearchedPoi>()
    per.forEach { list ->
        list.forEach { p ->
            val k = p.id.ifBlank { "${p.lat}_${p.lng}" }
            if (seen.add(k)) out += p
        }
    }
    return out
}

/**
 * 沿途搜索面板：预设类别 chips + 自定义关键词（未选未输入不发起搜索）；
 * 结果按走廊序排列，点击加入途经点并重算（添加途经点的唯一入口）。
 */
@Composable
private fun AlongSearchPanel() {
    val context = LocalContext.current
    val searcher = remember { com.amapilot.map.PoiSearcher(context) }
    var category by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var customQuery by remember { androidx.compose.runtime.mutableStateOf("") }
    var results by remember { androidx.compose.runtime.mutableStateOf<List<com.amapilot.map.SearchedPoi>>(emptyList()) }
    var loading by remember { androidx.compose.runtime.mutableStateOf(false) }
    val naviLocation by NaviSession.location.collectAsState()

    val keyword = if (customQuery.isNotBlank()) customQuery else category ?: ""

    LaunchedEffect(keyword) {
        if (keyword.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        loading = true
        kotlinx.coroutines.delay(300)
        val res = runCatching { alongRouteSearch(context, searcher, keyword) }
            .getOrDefault(emptyList())
        results = res
        loading = false
    }

    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ALONG_CATEGORIES.forEach { c ->
                androidx.compose.material3.FilterChip(
                    selected = category == c && customQuery.isBlank(),
                    onClick = {
                        customQuery = ""
                        category = c
                    },
                    label = { Text(c) }
                )
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = customQuery,
            onValueChange = { customQuery = it },
            placeholder = { Text("自定义搜索") },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        if (loading) {
            Text(
                "搜索中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            val loc = naviLocation?.coord
            results.take(5).forEach { poi ->
                val dist = loc?.let {
                    val dx = (it.longitude - poi.lng) * 85.0
                    val dy = (it.latitude - poi.lat) * 111.0
                    kotlin.math.sqrt(dx * dx + dy * dy) * 1000
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            NaviSession.addWaypoint(
                                poi.name,
                                com.amap.api.maps.model.LatLng(poi.lat, poi.lng)
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(poi.name, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        text = dist?.let { String.format("%.1f km", it / 1000) } ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text("  +", color = ACCENT, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 途经点管理面板：途经点 chips（点 × 删除并即刻重算）；有途经点时才展开。
 */
@Composable
private fun WaypointPanel() {
    val waypoints by NaviSession.waypoints.collectAsState()

    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            waypoints.forEach { wp ->
                androidx.compose.material3.AssistChip(
                    onClick = { NaviSession.removeWaypoint(wp) },
                    label = { Text(wp.name, maxLines = 1) },
                    trailingIcon = { Text("×", color = MaterialTheme.colorScheme.outline) }
                )
            }
        }
    }
}

/** 路线设置面板：策略切换后重算路线并持久化为默认偏好（与设置页同源）。 */
@Composable
private fun SettingsPanel() {
    val context = LocalContext.current
    val current by NaviSession.strategy.collectAsState()
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        DriveStrategy.entries.forEach { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        com.amapilot.settings.Prefs.setDefaultStrategy(context, s.label)
                        NaviSession.recalculate(strategy = s.naviStrategy)
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    s.label,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    color = if (s.naviStrategy == current) ACCENT else MaterialTheme.colorScheme.onSurface
                )
                if (s.naviStrategy == current) {
                    Text("当前", fontSize = 12.sp, color = ACCENT)
                }
            }
        }
    }
}

/** 路线步骤二级页：全屏时间轴列表（竖连接线+节点箭头+路名+距离）；点步骤回主图并跳该路口。 */
@Composable
private fun StepsSheet(
    onBack: () -> Unit,
    onStepTap: (NaviSession.RouteStep) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps by NaviSession.routeSteps.collectAsState()
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val sheetHeight = (config.screenHeightDp * 0.55f).dp
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(sheetHeight),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "路线步骤",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (steps.isEmpty()) {
                Text(
                    text = "暂无路线步骤",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    steps.forEachIndexed { index, s ->
                        StepTimelineRow(
                            step = s,
                            isFirst = index == 0,
                            isLast = index == steps.lastIndex,
                            onClick = { onStepTap(s) }
                        )
                    }
                }
            }
        }
    }
}

/** 时间轴步骤行：左竖连接线+节点转向箭头，右路名+距离。 */
@Composable
private fun StepTimelineRow(
    step: NaviSession.RouteStep,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(
                        if (isFirst) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ManeuverIcons.maneuverIcon(step.iconType),
                    contentDescription = null,
                    tint = ACCENT,
                    modifier = Modifier.size(16.dp)
                )
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(24.dp)
                    .background(
                        if (isLast) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = step.roadName.ifBlank { "路口" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = naviDistanceText(step.lengthMeters),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 主色：主题令牌（浅色=Google Blue 与地图页一致，夜间=Blue300 保证暗底对比度）。 */
private val ACCENT: Color
    @Composable get() = MaterialTheme.colorScheme.primary

/** 相机前推量（度）：箭头显示在屏幕约 60% 高度处（避开底部时间/路线栏）。 */
private const val TBT_OFFSET_LAT = 0.003

/** 相机动画时长（毫秒）：进入导航视角飞行 / 回中 / 全览取景。 */
private const val NAV_ENTRY_ANIM_MS = 600L
private const val RECENTER_ANIM_MS = 300L
private const val OVERVIEW_ANIM_MS = 600L

/** 全览取景留白（像素）：与 MapScreen 路线预览同源（200px）。 */
private const val TBT_OVERVIEW_PAD = 200

/** TBT 相机三态：跟随/北向上/全览（与车机右竖排 toggle 同语义）。 */
private enum class TbtCameraMode { FOLLOW, NORTH, OVERVIEW }



/** 路线线色。 */
private val ROUTE_COLOR = android.graphics.Color.rgb(0x1A, 0x73, 0xE8)

/** 导航位置源：把 [NaviSession.location]（含模拟位置）转发给地图定位层。 */
private fun naviLocationSource(): com.amap.api.maps.LocationSource =
    object : com.amap.api.maps.LocationSource {
        private var listener: com.amap.api.maps.LocationSource.OnLocationChangedListener? = null
        private var scope: kotlinx.coroutines.CoroutineScope? = null

        override fun activate(onLocationChangedListener: com.amap.api.maps.LocationSource.OnLocationChangedListener) {
            listener = onLocationChangedListener
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).also {
                it.launch {
                    NaviSession.location.collect { loc ->
                        loc?.let { l ->
                            listener?.onLocationChanged(
                                com.amap.api.location.AMapLocation("navi").apply {
                                    l.coord?.let { c ->
                                        latitude = c.latitude
                                        longitude = c.longitude
                                    }
                                    speed = l.speed
                                    // 箭头方向：道路方向 > GPS 方向角
                                    bearing = when {
                                        l.roadBearing in 0.1f..359.9f -> l.roadBearing
                                        l.bearing in 0.1f..359.9f -> l.bearing
                                        else -> 0f
                                    }
                                    locationType = com.amap.api.location.AMapLocation.LOCATION_TYPE_GPS
                                }
                            )
                        }
                    }
                }
            }
        }

        override fun deactivate() {
            scope?.cancel()
            scope = null
            listener = null
        }
    }

/** 步骤预览顶卡：当前预览步骤的转向箭头+路名+距离（同导航转向卡风格）。 */
@Composable
private fun StepPreviewCard(step: NaviSession.RouteStep) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ManeuverIcons.maneuverIcon(step.iconType),
                contentDescription = null,
                tint = ACCENT,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.roadName.ifBlank { "路口" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = naviDistanceText(step.lengthMeters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 步骤预览底栏：上排=左/右靠边切换+中间大字总时间/距离+小字步骤；底部退出预览单独居中。 */
@Composable
private fun PreviewBar(
    navi: NaviState,
    index: Int,
    total: Int,
    step: NaviSession.RouteStep,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = index > 0) {
                    Icon(
                        com.amapilot.ui.theme.GmIcons.ArrowBack,
                        contentDescription = "上一步",
                        tint = ACCENT
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = buildString {
                            append(naviDurationText(navi.routeRemainSeconds))
                            append(" · ")
                            append(naviDistanceText(navi.routeRemainMeters))
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = ACCENT
                    )
                    Text(
                        text = "步骤 ${index + 1}/$total · ${step.roadName.ifBlank { "路口" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onNext, enabled = index < total - 1) {
                    Icon(
                        com.amapilot.ui.theme.GmIcons.ArrowForward,
                        contentDescription = "下一步",
                        tint = ACCENT
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                androidx.compose.material3.TextButton(onClick = onExit) {
                    Text(
                        text = "退出预览",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
