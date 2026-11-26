package com.amapilot.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.location.AMapLocation
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.LocationSource
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.GroundOverlay
import com.amap.api.maps.model.GroundOverlayOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amapilot.navigation.PlannedRoute
import com.amapilot.navigation.pinDescriptor
import com.amapilot.navigation.waypointDescriptor
import com.amapilot.navigation.RoutePlanner
import com.amapilot.navigation.transparentDescriptor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 路线停靠点。一条路线即 `List<RouteStop>`（≥2）：首=起点、尾=终点、中间=途经点。 */
sealed interface RouteStop {
    /** 「我的位置」：解析到当前 GPS 坐标。 */
    object MyLocation : RouteStop

    data class Poi(val poi: SearchedPoi) : RouteStop
}

fun RouteStop.displayName(): String = when (this) {
    RouteStop.MyLocation -> "我的位置"
    is RouteStop.Poi -> poi.name
}

/** 把停靠点解析为坐标；[MyLocation] 需要 [gps]（无定位时返回 null）。 */
fun RouteStop.resolve(gps: LatLng?): LatLng? = when (this) {
    RouteStop.MyLocation -> gps
    is RouteStop.Poi -> LatLng(poi.lat, poi.lng)
}

/**
 * 主地图界面：全屏高德 [MapView] 底座 + Compose 覆盖层（搜索栏/路线卡/地点卡/抽屉），
 * 生命周期经 [LifecycleEventObserver] 转发给 MapView。选点后地图绘制多备选路线
 * （选中蓝、其余灰），底部路线卡确认后启动导航。
 */
@Composable
fun MapScreen(
    mode: MapMode = MapMode.BROWSE,
    hasLocationPermission: Boolean,
    isNight: Boolean = false,
    emulator: Boolean = false,
    stops: List<RouteStop> = emptyList(),
    onStopsChange: (List<RouteStop>) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onLocationChanged: (LatLng) -> Unit = {},
    onStartNavigation: (LatLng?, Int?, List<com.amapilot.navigation.NaviWaypoint>) -> Unit = { _, _, _ -> },
    onClearSelection: () -> Unit = {},
    onAutoCruise: () -> Unit = {},
    showZoomControls: Boolean = false,
    focusPoint: SearchedPoi? = null,
    onFocusPointChange: (SearchedPoi?) -> Unit = {},
    onExploreSearch: (place: SearchedPoi, keyword: String?) -> Unit = { _, _ -> },
    onRouteFromPlace: (SearchedPoi) -> Unit = {},
    favorites: List<com.amapilot.data.FavoritePlace> = emptyList(),
    onPickFavorite: (com.amapilot.data.FavoritePlace) -> Unit = {},
    onDeleteFavorite: (com.amapilot.data.FavoritePlace) -> Unit = {},
    onRequestSetKind: (com.amapilot.data.FavoritePlace.Kind) -> Unit = {},
    onDemoteFavorite: (com.amapilot.data.FavoritePlace) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { com.amapilot.map.MapHolder.acquire(context) }
    mapView.map.uiSettings.isZoomControlsEnabled = showZoomControls
    mapView.map.isTrafficEnabled = true
    val browseDotIcon = remember { locationDotDescriptor(context) }
    val resultPinIcon = remember { pinDescriptor(context) }
    val routePlanner = remember { RoutePlanner(context) }
    var resultMarker by remember { mutableStateOf<Marker?>(null) }
    var originMarker by remember { mutableStateOf<Marker?>(null) }
    var myLocation by remember { mutableStateOf<LatLng?>(null) }
    var routePolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var routes by remember { mutableStateOf<List<PlannedRoute>>(emptyList()) }
    var selectedRoute by remember { mutableIntStateOf(0) }
    var planning by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    // 路线编辑态：停靠点列表 ≥2（起点+终点）；重排后以列表顺序为准（无 swapped）
    val routeMode = stops.size >= 2
    // 退场快照：路线态镜像 stops，退出时冻结供 AnimatedContent 退场渲染（防闪空）
    var lastStops by remember { mutableStateOf(stops) }
    androidx.compose.runtime.SideEffect { if (routeMode) lastStops = stops }
    val renderStops = if (routeMode) stops else lastStops
    // 定位回调读最新 stops（判断终点是否 MyLocation 以跟随 GPS）
    val currentStops by rememberUpdatedState(stops)

    // 自动巡航检测状态（非 Compose 状态：定位回调高频写，避免重组）
    var drivingSinceMs by remember { mutableStateOf(0L) }
    val autoCruiseEnabled = remember { com.amapilot.settings.Prefs.isAutoCruiseEnabled(context) }

    var cruiseCardVisible by remember { mutableStateOf(false) }
    var parkedSinceMs by remember { mutableStateOf(0L) }
    var cruiseHandoff by remember { mutableStateOf(false) }
    val cruiseState by com.amapilot.navigation.CruiseSession.state.collectAsState()

    var drawerExpanded by remember { mutableStateOf(false) }
    var waypointMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }

    // 地图选点（POI 点击/长按）→ 焦点地点卡：pin 标记 + 后台逆地理
    var focusMarker by remember { mutableStateOf<Marker?>(null) }
    val mapScope = rememberCoroutineScope()
    val poiSearcher = remember { PoiSearcher(context) }

    // 定位三件套（自绘，浏览态）：精度圈 + 方向扇形 + 圆点
    var accuracyCircle by remember { mutableStateOf<Circle?>(null) }
    var beamOverlay by remember { mutableStateOf<GroundOverlay?>(null) }
    var dotMarker by remember { mutableStateOf<Marker?>(null) }
    var lastFix by remember { mutableStateOf<LatLng?>(null) }
    val beamIcon = remember { beamDescriptor(context) }

    // 传感器航向（旋转矢量）：静止时也有方向；GPS course 优先，传感器次之
    val headingSensor = remember { HeadingSensor(context) }
    DisposableEffect(Unit) {
        headingSensor.start()
        onDispose { headingSensor.stop() }
    }

    /** 每个 GPS fix 更新自绘定位视觉：精度圈、方向扇形、圆点。 */
    fun updateLocationOverlays(latLng: LatLng, accuracy: Float, bearing: Float) {
        headingSensor.location = latLng.latitude to latLng.longitude
        // 精度圈与方向扇形共用同一半径（GPS 精度过高时取下限，保证可见）
        val radius = maxOf(if (accuracy > 0f) accuracy.toDouble() else 0.0, 25.0)
        val circle = accuracyCircle
        if (circle == null) {
            accuracyCircle = mapView.map.addCircle(
                CircleOptions()
                    .center(latLng)
                    .radius(radius)
                    .fillColor(android.graphics.Color.argb(50, 0x1A, 0x73, 0xE8))
                    .strokeColor(android.graphics.Color.argb(120, 0x1A, 0x73, 0xE8))
                    .strokeWidth(2.5f)
                    .zIndex(900f)
            )
        } else {
            circle.center = latLng
            circle.radius = radius
        }
        val sensorHeading = headingSensor.heading.value
        val last = lastFix
        val heading: Float? = when {
            bearing in 0.1f..359.9f -> bearing
            sensorHeading != null -> sensorHeading
            last != null -> {
                val dLat = latLng.latitude - last.latitude
                val dLng = latLng.longitude - last.longitude
                if (dLat * dLat + dLng * dLng > 1e-8) {
                    Math.toDegrees(
                        kotlin.math.atan2(
                            dLng * kotlin.math.cos(Math.toRadians(latLng.latitude)),
                            dLat
                        )
                    ).toFloat()
                } else null
            }
            else -> null
        }
        lastFix = latLng
        val radiusF = radius.toFloat()
        val beam = beamOverlay
        if (beam == null && heading != null) {
            beamOverlay = mapView.map.addGroundOverlay(
                GroundOverlayOptions()
                    .position(latLng, radiusF * 2, radiusF * 2)
                    .image(beamIcon)
                    .zIndex(1000f)
            )
        }
        beamOverlay?.let { b ->
            b.position = latLng
            b.setDimensions(radiusF * 2, radiusF * 2)
            if (heading != null) b.bearing = heading
        }
        val dot = dotMarker
        if (dot == null) {
            dotMarker = mapView.map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .icon(browseDotIcon)
                    .anchor(0.5f, 0.5f)
                    .zIndex(2000f)
            )
        } else {
            dot.position = latLng
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            accuracyCircle?.remove()
            beamOverlay?.remove()
            dotMarker?.remove()
            focusMarker?.remove()
        }
    }

    // 离开浏览页时结束卡片巡航会话；点卡片进巡航屏属会话移交，不停止
    DisposableEffect(Unit) {
        onDispose {
            if (cruiseCardVisible && !cruiseHandoff) {
                com.amapilot.navigation.CruiseSession.stop()
            }
        }
    }

    LaunchedEffect(isNight) {
        mapView.map.setMapType(
            if (isNight) com.amap.api.maps.AMap.MAP_TYPE_NIGHT
            else com.amap.api.maps.AMap.MAP_TYPE_NORMAL
        )
    }

    LaunchedEffect(hasLocationPermission, mode) {
        if (hasLocationPermission) {
            val style = MyLocationStyle().myLocationType(
                when (mode) {
                    MapMode.BROWSE -> MyLocationStyle.LOCATION_TYPE_FOLLOW
                    MapMode.CRUISE, MapMode.NAVIGATION -> MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE
                }
            )
            if (mode == MapMode.BROWSE) {
                // SDK 圆点/精度圈透明化：视觉由自绘三件套（精度圈/扇形/圆点）接管
                style.myLocationIcon(transparentDescriptor(context))
                    .radiusFillColor(android.graphics.Color.TRANSPARENT)
                    .strokeColor(android.graphics.Color.TRANSPARENT)
                    .strokeWidth(0f)
            }
            mapView.map.apply {
                // 预设街区级视野（lastKnown）避免闪跳默认北京；远距离瞬移，近距离动画过渡
                SystemLocations.lastKnownGcj(context)?.let { seed ->
                    val update = CameraUpdateFactory.newLatLngZoom(seed, BROWSE_ZOOM)
                    val cur = cameraPosition?.target
                    if (cur == null || farApart(cur, seed)) moveCamera(update)
                    else animateCameraTo(update, RETURN_BROWSE_ANIM_MS)
                }
                // 自定义定位源：系统 GPS 直读，绕开 AMap 定位 SDK（模拟器海外权益拦截）
                setLocationSource(systemGpsSource(context))
                myLocationStyle = style
                isMyLocationEnabled = true
                setOnMyLocationChangeListener { location ->
                    location?.let {
                        val latLng = LatLng(it.latitude, it.longitude)
                        myLocation = latLng
                        onLocationChanged(latLng)
                        updateLocationOverlays(latLng, it.accuracy, it.bearing)
                        // 终点=我的位置（被拖到末位）：终点 Pin 跟随定位移动
                        if (currentStops.lastOrNull() == RouteStop.MyLocation) {
                            resultMarker?.position = latLng
                        }
                        // 巡航卡片：持续行驶 → 显示卡片并后台起会话（不切屏）；停车超时收起
                        if (autoCruiseEnabled && mode == MapMode.BROWSE && !routeMode) {
                            val speedKmh = it.speed * 3.6f
                            if (speedKmh >= AUTO_CRUISE_SPEED_KMH) {
                                parkedSinceMs = 0L
                                if (!cruiseCardVisible && AutoCruiseGuard.armed) {
                                    val since = drivingSinceMs
                                    if (since == 0L) {
                                        drivingSinceMs = System.currentTimeMillis()
                                    } else if (System.currentTimeMillis() - since >= AUTO_CRUISE_SUSTAIN_MS) {
                                        drivingSinceMs = 0L
                                        cruiseCardVisible = true
                                        com.amapilot.navigation.CruiseSession.start(context, emulator)
                                    }
                                }
                            } else {
                                drivingSinceMs = 0L
                                // 车速降到阈值以下（停过车）→ 重新武装自动巡航
                                AutoCruiseGuard.armed = true
                                if (cruiseCardVisible) {
                                    val since = parkedSinceMs
                                    if (since == 0L) {
                                        parkedSinceMs = System.currentTimeMillis()
                                    } else if (System.currentTimeMillis() - since >= CRUISE_CARD_HIDE_MS) {
                                        parkedSinceMs = 0L
                                        cruiseCardVisible = false
                                        com.amapilot.navigation.CruiseSession.stop()
                                    }
                                }
                            }
                        }
                    }
                }
                // 用户拖动地图：暂停相机跟随（保留蓝点），由定位按钮重置回中
                setOnMapTouchListener { event ->
                    if (event.action == android.view.MotionEvent.ACTION_MOVE &&
                        mode == MapMode.BROWSE && !routeMode
                    ) {
                        myLocationStyle = transparentBrowseStyle(context)
                    }
                }
                // 底图 POI 点击 → 焦点地点卡（开关显式打开；路线编辑态忽略）
                isTouchPoiEnable = true
                setOnPOIClickListener { poi ->
                    if (mode == MapMode.BROWSE && currentStops.size < 2) {
                        val ll = poi.coordinate
                        onFocusPointChange(
                            SearchedPoi(
                                id = poi.poiId.orEmpty()
                                    .ifBlank { "map_${ll.latitude}_${ll.longitude}" },
                                name = poi.name.orEmpty().ifBlank { "地图位置" },
                                address = "",
                                lat = ll.latitude,
                                lng = ll.longitude
                            )
                        )
                    }
                }
                // 长按任意处选点：立即落卡（坐标降级名），后台逆地理回填真名
                setOnMapLongClickListener { point ->
                    if (mode == MapMode.BROWSE && currentStops.size < 2) {
                        onFocusPointChange(
                            SearchedPoi(
                                id = "map_${point.latitude}_${point.longitude}",
                                name = "地图位置",
                                address = String.format("%.5f, %.5f", point.latitude, point.longitude),
                                lat = point.latitude,
                                lng = point.longitude
                            )
                        )
                        mapScope.launch {
                            val resolved = runCatching {
                                withTimeoutOrNull(REGEO_TIMEOUT_MS) {
                                    poiSearcher.regeoPoint(point)
                                }
                            }.getOrNull()
                            // 回填守卫：坐标一致才回填（用户已换焦点/收卡则放弃）
                            if (resolved != null &&
                                kotlin.math.abs(resolved.lat - point.latitude) < 1e-7 &&
                                kotlin.math.abs(resolved.lng - point.longitude) < 1e-7
                            ) {
                                onFocusPointChange(resolved)
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(routeMode) {
        if (!routeMode) return@LaunchedEffect
        // 进入路线编辑：收地点卡 + 收起巡航卡片并停会话（引擎让位给算路）
        if (focusPoint != null) onFocusPointChange(null)
        if (cruiseCardVisible) {
            cruiseCardVisible = false
            parkedSinceMs = 0L
            com.amapilot.navigation.CruiseSession.stop()
        }
        if (hasLocationPermission) {
            // 查看选中地点：暂停相机跟随，蓝点保留
            mapView.map.myLocationStyle = transparentBrowseStyle(context)
        }
        // 平滑飞向终点（原瞬移生硬）；终点为「我的位置」时跳过（已在跟随）
        val destStop = stops.lastOrNull()
        if (destStop is RouteStop.Poi) {
            mapView.map.animateCameraTo(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(destStop.poi.lat, destStop.poi.lng), 15f
                ),
                POI_FLY_ANIM_MS
            )
        }
    }

    // 焦点地点（POI 点击/长按/周边换焦点）：pin 上图 + 相机飞行 + 收抽屉
    LaunchedEffect(focusPoint) {
        focusMarker?.remove()
        focusMarker = null
        val fp = focusPoint ?: return@LaunchedEffect
        if (drawerExpanded) drawerExpanded = false
        focusMarker = mapView.map.addMarker(
            MarkerOptions()
                .position(LatLng(fp.lat, fp.lng))
                .icon(resultPinIcon)
                .title(fp.name)
                .snippet(fp.address.takeIf { it.isNotBlank() })
                .zIndex(1500f)
        )
        // 相机飞向焦点（保底街区级视野；已更近时不拉远）
        val zoom = mapView.map.cameraPosition?.zoom ?: BROWSE_ZOOM
        mapView.map.animateCameraTo(
            CameraUpdateFactory.newLatLngZoom(LatLng(fp.lat, fp.lng), maxOf(zoom, BROWSE_ZOOM)),
            POI_FLY_ANIM_MS
        )
    }

    LaunchedEffect(stops) {
        // 每轮先清上一轮覆盖物（含清空选点时——否则路线/图钉残留在浏览图上）
        routePolylines.forEach { it.remove() }
        routePolylines = emptyList()
        waypointMarkers.forEach { it.remove() }
        waypointMarkers = emptyList()
        originMarker?.remove()
        originMarker = null
        resultMarker?.remove()
        resultMarker = null
        routes = emptyList()
        selectedRoute = 0
        routeError = null
        if (stops.size < 2) {
            planning = false
            return@LaunchedEffect
        }
        planning = true
        // 含「我的位置」时等待首个定位 fix 以解析 GPS；纯 POI 路线无需等待
        val gps = if (stops.any { it == RouteStop.MyLocation }) {
            snapshotFlow { myLocation }.filterNotNull().first()
        } else {
            myLocation
        }
        val points = stops.map { it.resolve(gps) }
        if (points.any { it == null }) {
            planning = false
            return@LaunchedEffect
        }
        val resolved = points.filterNotNull()
        val origin = resolved.first()
        val dest = resolved.last()
        val viaPoints = resolved.drop(1).dropLast(1)
        // 起点标记：起点为 POI 才画蓝点（「我的位置」起点由定位层蓝点充当，不画）
        if (stops.first() is RouteStop.Poi) {
            originMarker = mapView.map.addMarker(
                MarkerOptions().position(origin).icon(browseDotIcon)
            )
        }
        // 终点标记：红 Pin（终点为「我的位置」时位置随 GPS 移动，见定位回调）
        val destStop = stops.last()
        val destOpts = MarkerOptions().position(dest).icon(resultPinIcon)
        if (destStop is RouteStop.Poi) {
            destOpts.title(destStop.poi.name).snippet(destStop.poi.address)
        }
        resultMarker = mapView.map.addMarker(destOpts)
        // 途经点序号标记（与 RouteBar 序号对应）
        waypointMarkers = stops.drop(1).dropLast(1).mapIndexed { index, stop ->
            mapView.map.addMarker(
                MarkerOptions()
                    .position(stop.resolve(gps)!!)
                    .icon(waypointDescriptor(context, index + 1))
                    .anchor(0.5f, 0.5f)
                    .zIndex(1500f)
            )
        }
        try {
            val result = routePlanner.planDriveRoutes(origin, dest, viaPoints)
            routes = result
            drawRoutes(mapView, result, 0, origin, dest, viaPoints) { routePolylines = it }
        } catch (e: Exception) {
            // 并发算路冲突（上一次请求仍在引擎内进行）会报错：稍候自动重试一次
            kotlinx.coroutines.delay(3000)
            try {
                val result = routePlanner.planDriveRoutes(origin, dest, viaPoints)
                routes = result
                drawRoutes(mapView, result, 0, origin, dest, viaPoints) { routePolylines = it }
            } catch (e2: Exception) {
                routeError = e2.message
            }
        } finally {
            planning = false
        }
    }

    // 切换选中路线：重画线路颜色
    LaunchedEffect(selectedRoute, routes) {
        if (routes.isEmpty() || stops.size < 2) return@LaunchedEffect
        val gps = myLocation ?: return@LaunchedEffect
        val resolved = stops.mapNotNull { it.resolve(gps) }
        if (resolved.size < 2) return@LaunchedEffect
        routePolylines.forEach { it.remove() }
        drawRoutes(
            mapView, routes, selectedRoute,
            resolved.first(), resolved.last(), resolved.drop(1).dropLast(1)
        ) { routePolylines = it }
    }

    // 回中 FAB 底距随底部组件切换平滑过渡
    val fabBottom by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (cruiseCardVisible) 110.dp else 92.dp,
        animationSpec = androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium),
        label = "fabBottom"
    )

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { com.amapilot.map.MapHolder.reset(mapView) }, modifier = Modifier.fillMaxSize())
        // chrome 层：浏览态 ↔ 路线态交叉淡化；方向性动效由元素级入场承担
        androidx.compose.animation.AnimatedContent(
            targetState = routeMode,
            transitionSpec = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                ) togetherWith androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                )
            },
            label = "mapChrome"
        ) { isRoute ->
            // 一次性入场：分支组合后元素按方位滑入（顶栏自上、底栏/抽屉/卡片自下）
            var chromeShown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { chromeShown = true }
            if (!isRoute) {
                Box(Modifier.fillMaxSize()) {
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
                        com.amapilot.ui.components.GmSearchBar(
                            placeholder = "搜索地点",
                            onClick = onSearchClick
                        )
                    }
                    // 巡航卡片（驾驶检测触发）与收藏抽屉/地点卡互斥：各自滑入滑出
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chromeShown && cruiseCardVisible && focusPoint == null,
                        enter = androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.tween(
                                com.amapilot.ui.theme.GmMotion.DurationLong,
                                easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                            )
                        ) { it } + androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                        ),
                        exit = androidx.compose.animation.slideOutVertically(
                            androidx.compose.animation.core.tween(
                                com.amapilot.ui.theme.GmMotion.DurationMedium,
                                easing = com.amapilot.ui.theme.GmMotion.EmphasizedAccelerate
                            )
                        ) { it } + androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                        ),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        CruiseCard(
                            cruise = cruiseState,
                            onTap = {
                                cruiseHandoff = true
                                onAutoCruise()
                            },
                            onDismiss = {
                                cruiseCardVisible = false
                                parkedSinceMs = 0L
                                com.amapilot.navigation.CruiseSession.stop()
                                // 驾驶中手动收起：停车前不再重新弹出
                                AutoCruiseGuard.armed = false
                            }
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chromeShown && cruiseCardVisible && focusPoint == null,
                        enter = androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                        ) + androidx.compose.animation.scaleIn(
                            com.amapilot.ui.theme.GmMotion.quickSpring()
                        ),
                        exit = androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                        ) + androidx.compose.animation.scaleOut(
                            com.amapilot.ui.theme.GmMotion.quickSpring()
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, bottom = 100.dp)
                    ) {
                        com.amapilot.ui.components.SpeedRing(
                            speedKmh = cruiseState.speedKmh,
                            limitKmh = cruiseState.limitKmh
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chromeShown && !cruiseCardVisible && focusPoint == null,
                        enter = androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.tween(
                                com.amapilot.ui.theme.GmMotion.DurationLong,
                                easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                            )
                        ) { it } + androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                        ),
                        exit = androidx.compose.animation.slideOutVertically(
                            androidx.compose.animation.core.tween(
                                com.amapilot.ui.theme.GmMotion.DurationMedium,
                                easing = com.amapilot.ui.theme.GmMotion.EmphasizedAccelerate
                            )
                        ) { it } + androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                        ),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        FavoritesDrawer(
                            favorites = favorites,
                            expanded = drawerExpanded,
                            onExpandedChange = { drawerExpanded = it },
                            onPick = onPickFavorite,
                            onDelete = onDeleteFavorite,
                            onRequestSetKind = onRequestSetKind,
                            onDemote = onDemoteFavorite,
                            onOpenPreferences = onSettingsClick
                        )
                    }
                    // 焦点地点卡：与巡航卡/抽屉互斥占底部位；chip 以该点为中心探索
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chromeShown && focusPoint != null,
                        enter = androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.tween(
                                com.amapilot.ui.theme.GmMotion.DurationLong,
                                easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                            )
                        ) { it } + androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                        ),
                        exit = androidx.compose.animation.slideOutVertically(
                            androidx.compose.animation.core.tween(
                                com.amapilot.ui.theme.GmMotion.DurationMedium,
                                easing = com.amapilot.ui.theme.GmMotion.EmphasizedAccelerate
                            )
                        ) { it } + androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                        ),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        focusPoint?.let { fp ->
                            PlaceCard(
                                place = fp,
                                distanceM = myLocation?.let { loc ->
                                    haversineM(loc.latitude, loc.longitude, fp.lat, fp.lng)
                                },
                                onNavigate = { onRouteFromPlace(fp) },
                                onExplore = { onExploreSearch(fp, null) },
                                onCategory = { cat -> onExploreSearch(fp, cat) },
                                onDismiss = { onFocusPointChange(null) }
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    // 路线栏自顶部滑入、路线面板自底部滑入（各按方位）
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
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        RouteBar(
                            stops = renderStops,
                            location = myLocation,
                            onEdit = onSearchClick,
                            onStopsChange = onStopsChange
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
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        RoutePanel(
                            routes = routes,
                            selectedIndex = selectedRoute,
                            planning = planning,
                            error = routeError,
                            // 起点非当前位置（被拖成 POI）时引擎无法从该点导航，仅预览
                            canNavigate = stops.firstOrNull() == RouteStop.MyLocation,
                            onSelect = { selectedRoute = it },
                            onStart = {
                                // 终点=末位，途经点=中间停靠点（按顺序）；起点由规划路线决定
                                val gps = myLocation
                                val resolved = stops.mapNotNull { it.resolve(gps) }
                                if (resolved.size >= 2) {
                                    val wps = stops.drop(1).dropLast(1).mapNotNull { stop ->
                                        stop.resolve(gps)?.let {
                                            com.amapilot.navigation.NaviWaypoint(
                                                stop.displayName(), it.latitude, it.longitude
                                            )
                                        }
                                    }
                                    onStartNavigation(
                                        resolved.last(),
                                        routes.getOrNull(selectedRoute)?.pathId,
                                        wps
                                    )
                                }
                            },
                            onDismiss = onClearSelection
                        )
                    }
                }
            }
        }
        // 右下角定位按钮（仅浏览态且抽屉未展开；路线编辑时隐藏——回中会破坏起终点全览视野）
        androidx.compose.animation.AnimatedVisibility(
            visible = hasLocationPermission && !routeMode && !drawerExpanded,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
            ) + androidx.compose.animation.scaleIn(com.amapilot.ui.theme.GmMotion.quickSpring()),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
            ) + androidx.compose.animation.scaleOut(com.amapilot.ui.theme.GmMotion.quickSpring()),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp)
                // 悬浮于巡航卡片/收藏抽屉（收起态）上方
                .padding(bottom = fabBottom)
        ) {
            com.amapilot.ui.components.GmFab(
                icon = com.amapilot.ui.theme.GmIcons.MyLocation,
                contentDescription = "回到当前位置",
                onClick = {
                    myLocation?.let { loc ->
                        mapView.map.animateCameraTo(
                            CameraUpdateFactory.newLatLngZoom(loc, BROWSE_ZOOM),
                            RECENTER_ANIM_MS
                        )
                        if (mode == MapMode.BROWSE && !routeMode) {
                            mapView.map.myLocationStyle = MyLocationStyle()
                                .myLocationType(MyLocationStyle.LOCATION_TYPE_FOLLOW)
                                .myLocationIcon(transparentDescriptor(context))
                                .radiusFillColor(android.graphics.Color.TRANSPARENT)
                                .strokeColor(android.graphics.Color.TRANSPARENT)
                                .strokeWidth(0f)
                        }
                    }
                }
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                // 共享 MapView：车机会话持有渲染时手机退后台不得暂停，否则车机地图冻死
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
}

/** 浏览态底部抽屉：收起态为家/公司/收藏 chips；展开态为「常用地点」+「我的收藏」
 *  + 设置入口。抓手点击或上下拖动切换。 */
@Composable
private fun FavoritesDrawer(
    favorites: List<com.amapilot.data.FavoritePlace>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (com.amapilot.data.FavoritePlace) -> Unit,
    onDelete: (com.amapilot.data.FavoritePlace) -> Unit,
    onRequestSetKind: (com.amapilot.data.FavoritePlace.Kind) -> Unit,
    onDemote: (com.amapilot.data.FavoritePlace) -> Unit,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier
) {
    val home = favorites.firstOrNull { it.kind == com.amapilot.data.FavoritePlace.Kind.HOME }
    val work = favorites.firstOrNull { it.kind == com.amapilot.data.FavoritePlace.Kind.WORK }
    val normalCount = favorites.count { it.kind == com.amapilot.data.FavoritePlace.Kind.NORMAL }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        // 展开/收起：高度弹簧动画（内容切换被高度过渡掩盖）
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .animateContentSize(com.amapilot.ui.theme.GmMotion.sheetSpring())
        ) {
            // 抓手：点击切换 + 上下拖动切换（与 TBT 底栏同手势）
            val density = LocalDensity.current
            val swipeThreshold = with(density) { 24.dp.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(expanded) {
                        var accum = 0f
                        detectVerticalDragGestures(onDragStart = { accum = 0f }) { _, dragAmount ->
                            accum += dragAmount
                            if (!expanded && accum <= -swipeThreshold) onExpandedChange(true)
                            else if (expanded && accum >= swipeThreshold) onExpandedChange(false)
                        }
                    }
                    .clickable { onExpandedChange(!expanded) }
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                )
            }
            if (!expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    home?.let { fav ->
                        DrawerChip(Icons.Default.Home, "家") { onPick(fav) }
                    }
                    work?.let { fav ->
                        DrawerChip(com.amapilot.ui.theme.GmIcons.Work, "公司") { onPick(fav) }
                    }
                    DrawerChip(
                        icon = Icons.Default.Favorite,
                        label = if (normalCount > 0) "收藏 $normalCount" else "收藏地点"
                    ) { onExpandedChange(true) }
                }
            } else {
                val normals = favorites.filter {
                    it.kind == com.amapilot.data.FavoritePlace.Kind.NORMAL
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp)
                ) {
                    item { DrawerSectionHeader("常用地点") }
                    item {
                        HomeWorkRow(
                            kind = com.amapilot.data.FavoritePlace.Kind.HOME,
                            fav = home,
                            onPick = onPick,
                            onSet = {
                                onRequestSetKind(com.amapilot.data.FavoritePlace.Kind.HOME)
                            },
                            onDemote = onDemote
                        )
                    }
                    item {
                        HomeWorkRow(
                            kind = com.amapilot.data.FavoritePlace.Kind.WORK,
                            fav = work,
                            onPick = onPick,
                            onSet = {
                                onRequestSetKind(com.amapilot.data.FavoritePlace.Kind.WORK)
                            },
                            onDemote = onDemote
                        )
                    }
                    item { DrawerSectionHeader("我的收藏") }
                    if (normals.isEmpty()) {
                        item {
                            Text(
                                text = "搜索地点后点 ♡ 即可收藏",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                            )
                        }
                    } else {
                        items(normals, key = { it.id }) { fav ->
                            FavoriteManageRow(fav, onPick, onDelete)
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPreferences() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "设置",
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text("›", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)
    )
}

/** 常用地点行：未设置 → 进选点模式；已设置 → 点击导航，⋮ 更换/取消（降级为普通收藏）。 */
@Composable
private fun HomeWorkRow(
    kind: com.amapilot.data.FavoritePlace.Kind,
    fav: com.amapilot.data.FavoritePlace?,
    onPick: (com.amapilot.data.FavoritePlace) -> Unit,
    onSet: () -> Unit,
    onDemote: (com.amapilot.data.FavoritePlace) -> Unit
) {
    val isHome = kind == com.amapilot.data.FavoritePlace.Kind.HOME
    val icon = if (isHome) Icons.Default.Home else com.amapilot.ui.theme.GmIcons.Work
    val label = if (isHome) "家" else "公司"
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (fav != null) onPick(fav) else onSet() }
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (fav == null) MaterialTheme.colorScheme.outline else ACCENT
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (fav == null) {
                Text(
                    text = "设置$label",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(fav.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (fav.address.isNotBlank()) {
                    Text(
                        fav.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        if (fav == null) {
            Text(
                "›",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 12.dp)
            )
        } else {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("更换$label") },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onSet()
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("取消设为$label") },
                        leadingIcon = {
                            Icon(Icons.Default.Close, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onDemote(fav)
                        }
                    )
                }
            }
        }
    }
}

/** 焦点地点卡：名称/地址/距离 + 周边分类 chip + 搜周边 + 〔路线〕。三个入口共用：
 *  底图 POI 点击、长按选点、周边结果换焦点。 */
@Composable
private fun PlaceCard(
    place: SearchedPoi,
    distanceM: Double?,
    onNavigate: () -> Unit,
    onExplore: () -> Unit,
    onCategory: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            // 手势导航条避让：不加会把〔路线〕钮压进系统手势区（点按被吞）
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        place.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    val sub = listOfNotNull(
                        place.address.takeIf { it.isNotBlank() },
                        distanceM?.let { formatDistanceM(it) }
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭地点卡")
                }
            }
            // 周边分类 chip（共用基集 + 餐厅）+ 关键词入口；横向滚动容纳
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                (PoiSearcher.NEARBY_CATEGORIES + "餐厅").forEach { cat ->
                    AssistChip(
                        onClick = { onCategory(cat) },
                        label = { Text(cat) }
                    )
                }
                AssistChip(
                    onClick = onExplore,
                    label = { Text("搜周边") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            // 主行动钮：〔开始导航〕进多路线选择（与搜索/收藏选点同管线）
            Button(
                onClick = onNavigate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("开始导航", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun formatDistanceM(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else String.format("%.1f km", m / 1000)

/** 两点球面距离（米，haversine）。 */
private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
    return 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a))
}

/** 普通收藏管理行：心形图标 + 名称/地址 + ⋮ 菜单（仅删除），点击导航。 */
@Composable
private fun FavoriteManageRow(
    fav: com.amapilot.data.FavoritePlace,
    onPick: (com.amapilot.data.FavoritePlace) -> Unit,
    onDelete: (com.amapilot.data.FavoritePlace) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(fav) }
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Favorite,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(fav.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (fav.address.isNotBlank()) {
                Text(
                    fav.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete(fav)
                    }
                )
            }
        }
    }
}

/** 绘制多路线：选中蓝色，其余灰色，并适配起终点（含途经点）视野。 */
private fun drawRoutes(
    mapView: MapView,
    routes: List<PlannedRoute>,
    selected: Int,
    from: LatLng,
    to: LatLng,
    via: List<LatLng> = emptyList(),
    onUpdate: (List<Polyline>) -> Unit
) {
    val map = mapView.map
    val polylines = mutableListOf<Polyline>()
    // 选中路线最后添加，保证叠在备选路线上层
    routes.indices.sortedBy { it == selected }.forEach { index ->
        val route = routes[index]
        val color = if (index == selected) ROUTE_SELECTED_COLOR else ROUTE_ALT_COLOR
        val width = if (index == selected) 18f else 12f
        polylines += map.addPolyline(
            PolylineOptions().addAll(route.points).width(width).color(color)
        )
    }
    onUpdate(polylines)
    val bounds = LatLngBounds.Builder().include(from).include(to)
    via.forEach { bounds.include(it) }
    map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(bounds.build(), 200)
    )
}

/**
 * 起点/途经点/终点搜索栏卡片：停靠点逐行全宽白色圆角行，右侧编辑（铅笔/完成）与交换
 * 起终点。**编辑模式**下展开途经点内联搜索、行尾 × 删除与全部行长按拖动排序。
 * @param location 当前定位（途经点联想附近优先）
 */
@Composable
private fun RouteBar(
    stops: List<RouteStop>,
    location: LatLng?,
    onEdit: () -> Unit,
    onStopsChange: (List<RouteStop>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val searcher = remember { PoiSearcher(context) }
    // 编辑模式：铅笔切换，删除/拖动排序/添加入口仅在编辑中展开
    var editing by remember { mutableStateOf(false) }
    var addingStop by remember { mutableStateOf(false) }
    var stopQuery by remember { mutableStateOf("") }
    var tips by remember { mutableStateOf<List<com.amap.api.services.help.Tip>>(emptyList()) }
    val stopFieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // 拖动排序：越过相邻行中点即换位（含起终点）；交换经 stops 触发重算
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0f) }
    val spacingPx = with(LocalDensity.current) { 1.dp.toPx() }
    val currentStops by rememberUpdatedState(stops)
    val handleDrag: (Float) -> Unit = { amountY ->
        dragOffsetY += amountY
        val idx = draggingIndex
        val slot = rowHeightPx + spacingPx
        if (idx != null && slot > 0f) {
            if (dragOffsetY > slot / 2f && idx < currentStops.lastIndex) {
                onStopsChange(currentStops.toMutableList().apply { add(idx + 1, removeAt(idx)) })
                draggingIndex = idx + 1
                dragOffsetY -= slot
            } else if (dragOffsetY < -slot / 2f && idx > 0) {
                onStopsChange(currentStops.toMutableList().apply { add(idx - 1, removeAt(idx)) })
                draggingIndex = idx - 1
                dragOffsetY += slot
            }
        }
    }
    val handleDragEnd: () -> Unit = {
        draggingIndex = null
        dragOffsetY = 0f
    }

    // 途经点搜索联想（防抖 300ms，附近优先）
    LaunchedEffect(stopQuery) {
        if (stopQuery.isBlank()) {
            tips = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        runCatching { searcher.inputTips(stopQuery, location = location) }
            .onSuccess { tips = it.filter { t -> t.poiID != null && t.point != null } }
    }

    Surface(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                // 途经点行增删/内联搜索展开：高度弹簧过渡（不突变）
                modifier = Modifier
                    .weight(1f)
                    .animateContentSize(com.amapilot.ui.theme.GmMotion.sheetSpring()),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 首=起点(蓝点)、尾=终点(红点)、中间=途经点(序号)；编辑态可拖动/删除
                stops.forEachIndexed { index, stop ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    val dragging = draggingIndex == index
                    val currentIndex by rememberUpdatedState(index)
                    StopRow(
                        stop = stop,
                        index = index,
                        count = stops.size,
                        editable = editing,
                        dragging = dragging,
                        onClick = if (index == 0 || index == stops.lastIndex) onEdit else null,
                        onDelete = {
                            val remaining = stops.filterIndexed { i, _ -> i != index }
                            onStopsChange(if (remaining.size < 2) emptyList() else remaining)
                        },
                        onDragStart = {
                            draggingIndex = currentIndex
                            dragOffsetY = 0f
                        },
                        onDrag = handleDrag,
                        onDragEnd = handleDragEnd,
                        onHeight = { rowHeightPx = it },
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .offset { IntOffset(0, if (dragging) dragOffsetY.roundToInt() else 0) }
                    )
                }
                // 编辑态才展开途经点配置：内联搜索（自动聚焦，可连续添加）
                if (editing) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    if (addingStop) {
                        StopSearchField(
                            query = stopQuery,
                            onQueryChange = { stopQuery = it },
                            tips = tips,
                            onPick = { tip ->
                                // 插入到终点前（倒数第二位）：新停靠点成为最后一个途经点
                                val insertAt = (stops.size - 1).coerceAtLeast(0)
                                onStopsChange(
                                    stops.toMutableList().apply {
                                        add(insertAt, RouteStop.Poi(tip.toSearchedPoi()))
                                    }
                                )
                                stopQuery = ""
                                tips = emptyList()
                                addingStop = false
                            },
                            onCollapse = {
                                addingStop = false
                                stopQuery = ""
                                tips = emptyList()
                            },
                            focusRequester = stopFieldFocus,
                            showMyLocation = stops.none { it == RouteStop.MyLocation },
                            onPickMyLocation = {
                                // 加回当前位置：插入到终点前（与添加 POI 同位）
                                val insertAt = (stops.size - 1).coerceAtLeast(0)
                                onStopsChange(
                                    stops.toMutableList().apply { add(insertAt, RouteStop.MyLocation) }
                                )
                                stopQuery = ""
                                tips = emptyList()
                                addingStop = false
                            }
                        )
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            runCatching { stopFieldFocus.requestFocus() }
                        }
                    } else {
                        AddStopRow(onClick = { addingStop = true })
                    }
                    // Done 按钮：列表末（最后途经点/添加行之后）右对齐（GM 式），取代右上对勾
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        // 与上方停靠点行同高（bodyLarge 行高 + 垂直 6dp = 36dp），右对齐可点
                        Text(
                            text = "完成",
                            color = ACCENT,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .clickable {
                                    addingStop = false
                                    stopQuery = ""
                                    tips = emptyList()
                                    editing = false
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            // 非编辑态：右侧铅笔(进编辑)+交换；编辑态隐藏（完成按钮在列表末）
            if (!editing) {
                Column {
                    IconButton(onClick = { editing = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑路线",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onStopsChange(stops.reversed()) }) {
                        Icon(
                            com.amapilot.ui.theme.GmIcons.SwapVert,
                            contentDescription = "交换起终点",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 停靠点行：前导图标按位置（起点蓝点/终点红点/途经点序号），「我的位置」文字置灰。
 *  [onClick] 仅起点/终点行（进搜索），途经点行传 null 不可点。 */
@Composable
private fun StopRow(
    stop: RouteStop,
    index: Int,
    count: Int,
    editable: Boolean,
    dragging: Boolean,
    onClick: (() -> Unit)?,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onHeight: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOrigin = index == 0
    val isDest = index == count - 1
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(0.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        modifier = modifier
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .pointerInput(editable) {
                if (!editable) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                )
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(12.dp))
            when {
                // 起点：蓝点；终点：红点（与旧 BarRow 同风格）
                isOrigin -> Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                isDest -> Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
                // 途经点：序号圆点
                else -> Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(ACCENT, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$index",
                        color = androidx.compose.ui.graphics.Color.White,
                        // 去字体 padding + 行高收紧：数字在圆点内严格居中
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                includeFontPadding = false
                            )
                        )
                    )
                }
            }
            Text(
                text = stop.displayName(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                // 「我的位置」为次要灰、POI 正常色
                color = if (stop is RouteStop.MyLocation) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            // 拖动把手在右、删除 × 在最右（全部可删；删到 <2 退出路线态）
            if (editable) {
                Icon(
                    com.amapilot.ui.theme.GmIcons.DragHandle,
                    contentDescription = "拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp, end = 2.dp)
                        .size(20.dp)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除停靠点",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun AddStopRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = ACCENT,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "添加途经点",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = ACCENT,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun StopSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    tips: List<com.amap.api.services.help.Tip>,
    onPick: (com.amap.api.services.help.Tip) -> Unit,
    onCollapse: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    showMyLocation: Boolean = false,
    onPickMyLocation: () -> Unit = {}
) {
    Column {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索途经点") },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                IconButton(onClick = onCollapse) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        // 「我的位置」选项：列表已删除当前位置时可加回为停靠点
        if (showMyLocation) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickMyLocation() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    com.amapilot.ui.theme.GmIcons.MyLocation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "我的位置",
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
        tips.take(5).forEach { tip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(tip) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = tip.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
    }
}

private fun com.amap.api.services.help.Tip.toSearchedPoi(): SearchedPoi = SearchedPoi(
    id = poiID.orEmpty(),
    name = name.orEmpty(),
    address = (district ?: "") + (address ?: ""),
    lat = point?.latitude ?: 0.0,
    lng = point?.longitude ?: 0.0
)

/** 底部路线选择面板：横滑卡片（时长/里程+标签/红绿灯数），带开始导航。 */
@Composable
private fun RoutePanel(
    routes: List<PlannedRoute>,
    selectedIndex: Int,
    planning: Boolean,
    error: String?,
    canNavigate: Boolean = true,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 规划中/失败：非模态锚定卡片（不打断地图查看）
    if (planning) {
        Surface(
            modifier = modifier.padding(12.dp),
            shape = MaterialTheme.shapes.large,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("规划中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (error != null) {
        Surface(
            modifier = modifier.padding(12.dp),
            shape = MaterialTheme.shapes.large,
            shadowElevation = 4.dp
        ) {
            Text(
                text = "规划失败：$error",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }
    if (routes.isEmpty()) return

    // 非模态锚定面板：不用 ModalBottomSheet，避免遮罩变暗无法对比地图路线
    val pageCount = routes.size
    val pagerState = rememberPagerState(
        initialPage = selectedIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    ) { pageCount }
    LaunchedEffect(pagerState, routes) {
        snapshotFlow { pagerState.settledPage }
            .collect { page -> if (page != selectedIndex && page < routes.size) onSelect(page) }
    }
    LaunchedEffect(selectedIndex, routes) {
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex.coerceIn(0, routes.lastIndex))
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
        ),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                com.amapilot.ui.components.SheetHandle()
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSize = PageSize.Fixed(ROUTE_CARD_WIDTH),
                pageSpacing = 10.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { page ->
                RouteCard(
                    route = routes[page],
                    selected = page == selectedIndex,
                    onSelect = { onSelect(page) }
                )
            }
            // 起点非当前位置时仅预览：隐藏开始导航按钮（引擎无法从 POI 起点导航）
            if (canNavigate) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = MaterialTheme.shapes.extraLarge
                    // 默认 primary/onPrimary：浅色=Blue600/白（原样），夜间=Blue300/深字
                ) {
                    Text("开始导航", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** 单条路线卡片（Google Maps 风格：选中态用主题令牌，夜间自动切深蓝底）。 */
@Composable
private fun RouteCard(route: PlannedRoute, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.width(ROUTE_CARD_WIDTH),
        shape = MaterialTheme.shapes.large,
        // 选中态靠 primaryContainer 底色 + 2dp primary 勾边表达
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (selected) 0.dp else 1.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatDuration(route.durationSeconds),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // Google Maps：时长恒为深色，选中态由浅蓝底+蓝边框表达（不用蓝字）
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(formatLength(route.lengthMeters))
                    parseLabels(route.labels).takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "红绿灯 ${route.trafficLights} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
        else -> "$minutes 分钟"
    }
}

private fun formatLength(meters: Int): String {
    val km = meters / 1000.0
    return if (km >= 10) "${km.toInt()} 公里" else String.format("%.1f 公里", km)
}

/** 解析 SDK 路线标签：JSON 数组取 text 字段，非 JSON 原样返回。 */
private fun parseLabels(labels: String): String {
    if (labels.isBlank()) return ""
    return try {
        val arr = org.json.JSONArray(labels)
        (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
            .joinToString(" · ")
    } catch (_: Exception) {
        labels
    }
}

private val ROUTE_SELECTED_COLOR = Color.rgb(0x1A, 0x73, 0xE8)

/** 路线卡片宽度（横滑吸附页宽）。 */
private val ROUTE_CARD_WIDTH = 300.dp

/** 主色：主题令牌（浅色=Google Blue，夜间=Blue300 保证暗底对比度）。 */
private val ACCENT: androidx.compose.ui.graphics.Color
    @Composable get() = MaterialTheme.colorScheme.primary

/** 浏览态默认视野（约 1km 比例尺）。 */
private const val BROWSE_ZOOM = 15.5f

/** 相机动画时长（毫秒）：选点飞行 / 回中 / 导航巡航返回浏览。 */
private const val POI_FLY_ANIM_MS = 400L
private const val RECENTER_ANIM_MS = 300L
private const val RETURN_BROWSE_ANIM_MS = 400L
/** 长按选点逆地理超时：超时即保持坐标降级名（不阻塞地点卡交互）。 */
private const val REGEO_TIMEOUT_MS = 2500L

/** 自动巡航触发速度阈值（km/h）：低于该速度视为未在驾车。 */
private const val AUTO_CRUISE_SPEED_KMH = 20f

/** 自动巡航再触发保护：手动退出后置为未武装，需停车（车速低于阈值）一次才
 *  重新武装，避免驾驶中被反复拉回。 */
object AutoCruiseGuard {
    var armed: Boolean = true
}

/** 自动巡航持续时长（毫秒）：连续超速阈值该时长后触发。 */
private const val AUTO_CRUISE_SUSTAIN_MS = 15_000L

/** 巡航卡片停车收起时长（毫秒）：车速低于阈值持续该时长后收起卡片。 */
private const val CRUISE_CARD_HIDE_MS = 60_000L

/** 巡航卡片（与 TBT 底栏同构）：左 × 收起、中大字道路 + 限速/电子眼、右进入巡航；
 *  点击任意处进全屏巡航。 */
@Composable
private fun CruiseCard(
    cruise: com.amapilot.navigation.CruiseState,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onTap,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            com.amapilot.ui.components.SheetHandle()
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左：收起（与 TBT 退出导航同位同样式）
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起巡航卡片",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                // 中：大字道路名 + 小字限速/电子眼（车速在左下车速环，与 TBT 同构）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text(
                        text = cruise.roadName.ifBlank { "巡航中" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = buildString {
                            if (cruise.limitKmh > 0) append("限速 ${cruise.limitKmh} km/h")
                            cruise.cameras.firstOrNull()?.let { cam ->
                                if (isNotEmpty()) append(" · ")
                                append("前方 ${cam.distanceMeters} 米电子眼")
                            }
                        }.ifBlank { "点击进入巡航" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                // 右：进入巡航（与 TBT 切换路线按钮同位）
                IconButton(onClick = onTap) {
                    Icon(
                        com.amapilot.ui.theme.GmIcons.Navigation,
                        contentDescription = "进入巡航",
                        tint = ACCENT
                    )
                }
            }
        }
    }
}

private val LOCATE_BLUE = androidx.compose.ui.graphics.Color(0xFF1A73E8)

private val ROUTE_ALT_COLOR = Color.rgb(0xB0, 0xB5, 0xBC)

/** 浏览态 SDK 定位层样式：SHOW（不控相机）+ 全透明图标/精度圈（视觉由自绘三件套接管）。 */
private fun transparentBrowseStyle(context: Context): MyLocationStyle = MyLocationStyle()
    .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
    .myLocationIcon(transparentDescriptor(context))
    .radiusFillColor(android.graphics.Color.TRANSPARENT)
    .strokeColor(android.graphics.Color.TRANSPARENT)
    .strokeWidth(0f)

/** 浏览态定位圆点：蓝色圆心 + 细白环。 */
private fun locationDotDescriptor(context: Context): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (32 * density).toInt().coerceAtLeast(32)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1A, 0x73, 0xE8) }
    canvas.drawCircle(center, center, center * 0.62f, whitePaint)
    canvas.drawCircle(center, center, center * 0.5f, bluePaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** 粗略方向扇形（正北朝上 ±45°，半径向透明渐变），以 GroundOverlay 铺在精度圈上，
 *  尺寸随精度圈半径缩放。 */
private fun beamDescriptor(context: Context): BitmapDescriptor {
    val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(
            c, c, c,
            intArrayOf(
                Color.argb(170, 0x1A, 0x73, 0xE8),
                Color.argb(140, 0x1A, 0x73, 0xE8),
                Color.argb(0, 0x1A, 0x73, 0xE8)
            ),
            floatArrayOf(0f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
    }
    val rect = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
    val path = Path().apply {
        moveTo(c, c)
        arcTo(rect, -135f, 90f, false)
        close()
    }
    canvas.drawPath(path, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
