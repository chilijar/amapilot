package com.amapilot.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.MapView
import com.amap.api.maps.model.MyLocationStyle
import com.amapilot.map.animateCameraTo
import com.amapilot.map.systemGpsSource
import com.amapilot.ui.components.SpeedRing
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 巡航屏：无终点导航（电子眼播报/拥堵提示/统计）。顶部卡 = 电子眼提醒，
 * 底部栏 = 里程/时长 + 退出，左下角车速环；数据来自 [CruiseSession]（与车机共用）。
 * @param emulator 模拟器用模拟推进（算路后 EMULATOR 沿线行进）
 */
@Composable
fun CruiseScreen(
    emulator: Boolean = false,
    isNight: Boolean = false,
    onSearchClick: () -> Unit = {},
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cruise by CruiseSession.state.collectAsState()
    val mapView = remember { com.amapilot.map.MapHolder.acquire(context) }
    // chrome 入场动画：进入巡航时搜索栏自上、底栏/车速环自下滑入（地图连续不闪）
    var chromeShown by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) { chromeShown = true }

    DisposableEffect(Unit) {
        // 续接守卫：巡航已在进行时进入不重启（重启=统计归零），冷进入才发起
        if (!CruiseSession.isActive) CruiseSession.start(context, emulator)
        onDispose {
            // 移交车机：车机会话活跃时手机仅让出显示（同进程共享会话），退出才停止
            if (!com.amapilot.auto.CarMapControl.carSessionActive.value) {
                CruiseSession.stop()
            }
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

    Box(modifier = Modifier.fillMaxSize()) {
        // 用户拖动地图后暂停相机跟随，回中按钮恢复
        var following by remember { androidx.compose.runtime.mutableStateOf(true) }
        var lastCamera by remember {
            androidx.compose.runtime.mutableStateOf<CameraPosition?>(null)
        }
        AndroidView(
            factory = {
                com.amapilot.map.MapHolder.reset(mapView).map.apply {
                    uiSettings.isZoomControlsEnabled = false
                    isTrafficEnabled = true
                    // 拖动地图：暂停跟随（箭头仍随定位更新）
                    setOnMapTouchListener { event ->
                        if (event.action == android.view.MotionEvent.ACTION_MOVE) following = false
                    }
                    // 预设巡航视野：远距离瞬移防闪跳；近距离保留当前相机，
                    // 由首个定位的 animateCameraTo 做入场飞行
                    com.amapilot.map.SystemLocations.lastKnownGcj(context)?.let { seed ->
                        val cur = mapView.map.cameraPosition?.target
                        if (cur == null || com.amapilot.map.farApart(cur, seed)) {
                            moveCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition(seed, CRUISE_ZOOM, CRUISE_TILT, 0f)
                                )
                            )
                        }
                    }
                    setLocationSource(cruiseLocationSource())
                    // SDK 层仅收位置（SHOW 不控相机）：图标/精度圈透明化，箭头与相机自绘
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
        LaunchedEffect(mapView) {
            var lastLat: Double? = null
            var lastLng: Double? = null
            var lastDiffBearing: Float? = null
            CruiseSession.location.collect { loc ->
                val l = loc ?: return@collect
                val lat = l.coord?.latitude ?: return@collect
                val lng = l.coord?.longitude ?: return@collect
                // 位移推算航向：要求移动 ≥8m（单点噪声不跳方向），未达沿用上次值
                if (lastLat != null && lastLng != null) {
                    val dx = (lng - lastLng!!) * kotlin.math.cos(Math.toRadians(lat))
                    val dy = lat - lastLat!!
                    if (dx * dx + dy * dy > 5e-9) {
                        lastDiffBearing =
                            Math.toDegrees(kotlin.math.atan2(dx, dy)).toFloat()
                    }
                }
                lastLat = lat
                lastLng = lng
                val bearing = when {
                    l.roadBearing in 0.1f..359.9f -> l.roadBearing
                    l.bearing in 0.1f..359.9f -> l.bearing
                    else -> lastDiffBearing ?: 0f
                }
                val position = com.amap.api.maps.model.LatLng(lat, lng)
                // 相机目标点 = 位置沿行进方向前推（箭头落在屏幕偏下 ~68%）
                val rad = Math.toRadians(bearing.toDouble())
                val aheadLat = lat + CENTER_OFFSET_LAT * kotlin.math.cos(rad)
                val aheadLng = lng + CENTER_OFFSET_LAT / kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.sin(rad)
                val cameraTarget = com.amap.api.maps.model.LatLng(aheadLat, aheadLng)
                val cameraPosition = CameraPosition(cameraTarget, CRUISE_ZOOM, CRUISE_TILT, bearing)
                lastCamera = cameraPosition
                val m = arrowMarker
                if (m == null) {
                    // 箭头固定朝北（rotateAngle 0），地图旋转实现车头朝上
                    arrowMarker = mapView.map.addMarker(
                        com.amap.api.maps.model.MarkerOptions()
                            .position(position)
                            .icon(arrowIcon)
                            .anchor(0.5f, 0.5f)
                            .rotateAngle(0f)
                    )
                    // 进入巡航：从浏览视野平滑飞到巡航视角（后续逐帧跟随用 moveCamera）
                    mapView.map.animateCameraTo(
                        CameraUpdateFactory.newCameraPosition(cameraPosition),
                        CRUISE_ENTRY_ANIM_MS
                    )
                } else {
                    m.position = position
                    // 车头朝上：地图随行进方向旋转（无动画直接跟随，避免动画与更新频率竞争）
                    if (following) {
                        mapView.map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(cameraPosition)
                        )
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose { arrowMarker?.remove() }
        }
        // 顶部搜索栏：巡航中搜索并选定目的地即转入导航（入场自顶部滑下）
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
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Surface(
                onClick = onSearchClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 4.dp
            ) {
                Row {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
                    )
                    Text(
                        text = "搜索目的地",
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 16.dp)
                            .weight(1f)
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = chromeShown,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
            ) + androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(
                    com.amapilot.ui.theme.GmMotion.DurationLong,
                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                )
            ) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 20.dp)
        ) {
            SpeedRing(
                speedKmh = cruise.speedKmh,
                limitKmh = cruise.limitKmh
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = chromeShown,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
            ) + androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(
                    com.amapilot.ui.theme.GmMotion.DurationLong,
                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                )
            ) { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            CruiseBottomBar(
                cruise = cruise,
                onExit = onExit
            )
        }
        // 脱离跟随时显示回中按钮（右下角、底部栏上方，弹簧缩放淡入）
        androidx.compose.animation.AnimatedVisibility(
            visible = !following,
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
                .padding(end = 16.dp, bottom = 104.dp)
        ) {
            com.amapilot.ui.components.GmFab(
                icon = com.amapilot.ui.theme.GmIcons.MyLocation,
                contentDescription = "回到当前位置",
                onClick = {
                    following = true
                    lastCamera?.let {
                        mapView.map.animateCameraTo(
                            CameraUpdateFactory.newCameraPosition(it),
                            RECENTER_ANIM_MS
                        )
                    }
                }
            )
        }
    }
}

/** 底部栏：巡航统计 + 退出。 */
@Composable
private fun CruiseBottomBar(cruise: CruiseState, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = naviDistanceText(cruise.distanceMeters),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = naviDurationText(cruise.durationSeconds),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onExit) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "退出巡航",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private const val CRUISE_ZOOM = 15.5f

/** 相机动画时长（毫秒）：进入巡航视角飞行 / 回中。 */
private const val CRUISE_ENTRY_ANIM_MS = 600L
private const val RECENTER_ANIM_MS = 300L

private const val CRUISE_TILT = 55f

/** 相机前推量（度）：箭头显示在屏幕约 68% 高度处。 */
private const val CENTER_OFFSET_LAT = 0.0078

/** 巡航位置源：CruiseSession 位置（系统 GPS 兜底）转发地图定位层。 */
private fun cruiseLocationSource(): com.amap.api.maps.LocationSource =
    object : com.amap.api.maps.LocationSource {
        private var listener: com.amap.api.maps.LocationSource.OnLocationChangedListener? = null
        private var scope: kotlinx.coroutines.CoroutineScope? = null
        private var lastLat: Double? = null
        private var lastLng: Double? = null

        override fun activate(onLocationChangedListener: com.amap.api.maps.LocationSource.OnLocationChangedListener) {
            listener = onLocationChangedListener
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).also {
                it.launch {
                    CruiseSession.location.collect { loc ->
                        loc?.let { l ->
                            val lat = l.coord?.latitude ?: return@let
                            val lng = l.coord?.longitude ?: return@let
                            // 位移差分计算行进方向（geo fix 无 bearing 时兜底）
                            val diffBearing = if (lastLat != null && lastLng != null) {
                                val dx = (lng - lastLng!!) * kotlin.math.cos(Math.toRadians(lat))
                                val dy = lat - lastLat!!
                                if (dx * dx + dy * dy > 1e-12) {
                                    Math.toDegrees(kotlin.math.atan2(dx, dy)).toFloat()
                                } else null
                            } else null
                            lastLat = lat
                            lastLng = lng
                            // 箭头方向：道路方向 > GPS 方向角 > 位移差分
                            val bearing = when {
                                l.roadBearing in 0.1f..359.9f -> l.roadBearing
                                l.bearing in 0.1f..359.9f -> l.bearing
                                else -> diffBearing ?: 0f
                            }
                            listener?.onLocationChanged(
                                com.amap.api.location.AMapLocation("cruise").apply {
                                    latitude = lat
                                    longitude = lng
                                    speed = l.speed
                                    this.bearing = bearing
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

