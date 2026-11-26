package com.amapilot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amapilot.data.FavoritePlace
import com.amapilot.data.FavoriteRepository
import com.amapilot.map.MapMode
import com.amapilot.map.MapScreen
import com.amapilot.map.PoiSearcher
import com.amapilot.map.RouteStop
import com.amapilot.map.SearchedPoi
import com.amapilot.map.SearchScreen
import com.amapilot.data.HistoryStore
import com.amapilot.map.SearchViewModel
import com.amapilot.navigation.CruiseScreen
import com.amapilot.navigation.TbtScreen
import com.amapilot.settings.Prefs
import com.amapilot.settings.SettingsScreen
import com.amapilot.ui.components.PrivacyPolicyDialog
import com.amapilot.ui.theme.AmapilotTheme

/**
 * 应用主入口：首次启动强制展示隐私合规弹窗，同意后经 [com.amapilot.settings.AmapPrivacy]
 * 全模块注入并初始化地图；随后请求定位权限。顶层以状态切换地图/搜索/TBT 等页面。
 */
class MainActivity : ComponentActivity() {

    /** 可见性 → 共享地图引擎渲染开关：后台不渲染，避免与车机引擎争抢全局渲染锁。 */
    override fun onResume() {
        super.onResume()
        com.amapilot.map.MapHolder.resume()
        // 手机前台：暂停车机渲染（双引擎串行，防渲染锁争抢）。车连辅助态例外——
        // CarConnectedScreen 不占渲染锁，暂停车机会因 AA 半遮不触发 onPause 而冻死
        if (com.amapilot.auto.CarMapControl.carConnected.value) {
            com.amapilot.auto.CarMapControl.resumeCarRendering()
        } else {
            com.amapilot.auto.CarMapControl.pauseCarRendering()
        }
    }

    override fun onPause() {
        com.amapilot.map.MapHolder.pause()
        // 手机退后台：恢复车机地图渲染（此时手机引擎已停）
        com.amapilot.auto.CarMapControl.resumeCarRendering()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appContext = applicationContext
        // 隐私标志是进程内静态（setApiKey 会重置）：每次进程启动都须重注入，且先于
        // 任何 SDK 接口，否则抛 AMapException = 启动崩溃循环
        if (prefs.getBoolean(KEY_PRIVACY_AGREED, false)) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
        // 崩溃循环自救：上次启动未活过 8s 且距本次 <30s = 启动崩溃；**连续两次**
        // 才清覆盖 + 重置引导标记 → 下次进 KeySetupScreen（不建 MapView、不再崩）。
        // 单次失败可能只是 force-stop/系统回收，不触发。
        val bootNow = System.currentTimeMillis()
        val lastBoot = prefs.getLong(KEY_BOOT_TS, 0L)
        val lastOk = prefs.getLong(KEY_BOOT_OK, 0L)
        val failedBoot = lastBoot > 0 && lastOk < lastBoot &&
            bootNow - lastBoot < 30_000
        val bootFails = if (failedBoot) prefs.getInt(KEY_BOOT_FAILS, 0) + 1 else 0
        prefs.edit().putInt(KEY_BOOT_FAILS, bootFails).apply()
        if (com.amapilot.settings.Prefs.amapApiKey(appContext).isNotBlank() &&
            bootFails >= 2
        ) {
            com.amapilot.settings.Prefs.setAmapApiKey(appContext, "")
            com.amapilot.settings.Prefs.setKeySetupDismissed(appContext, false)
            android.os.Handler(mainLooper).post {
                android.widget.Toast.makeText(
                    this,
                    if (com.amapilot.settings.AmapKey.bundled(appContext).isNotBlank()) {
                        "检测到连续启动崩溃，已恢复内置高德 key"
                    } else {
                        "检测到连续启动崩溃，已清除自定义 key，重启后重新配置"
                    },
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        prefs.edit().putLong(KEY_BOOT_TS, bootNow).apply()
        // 用户自持高德 key 覆盖注入（须早于任何 MapView/引擎初始化）
        com.amapilot.settings.AmapKey.apply(appContext)
        // setApiKey 会重置 SDK 隐私静态 → 注入后重注入
        if (prefs.getBoolean(KEY_PRIVACY_AGREED, false)) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
        setContent {
            // Activity 销毁时回收共享地图引擎（屏间切换不销毁，见 MapHolder）
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { com.amapilot.map.MapHolder.destroy() }
            }
            // 昼夜解析：设置覆盖（白天/夜间）或跟随系统
            var dayNightPref by remember { mutableStateOf(Prefs.dayNightMode(this)) }
            val isNight = when (dayNightPref) {
                Prefs.DAY_NIGHT_NIGHT -> true
                Prefs.DAY_NIGHT_DAY -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            AmapilotTheme(darkTheme = isNight) {
                var agreed by remember { mutableStateOf(prefs.getBoolean(KEY_PRIVACY_AGREED, false)) }
                // 首启 key 引导：Release 零内置 key，隐私同意后强推一次；可跳过落盘
                var keySetupDone by remember {
                    mutableStateOf(
                        com.amapilot.settings.Prefs.keySetupDismissed(this@MainActivity) ||
                            com.amapilot.settings.AmapKey.isConfigured(this@MainActivity)
                    )
                }
                // 导入备份后的收藏有效性核查（导入链路唯一打扰点）：仅失效项才弹窗。
                // 冷启动 tick=0 也消费一次 pending 标志（覆盖导入后进程被杀的窗口）
                val favCheckTick by com.amapilot.settings.ConfigBackup.favCheckTick
                    .collectAsState()
                var invalidFavs by remember { mutableStateOf<List<String>?>(null) }
                androidx.compose.runtime.LaunchedEffect(favCheckTick) {
                    val ctx = this@MainActivity
                    if (!com.amapilot.settings.ConfigBackup.favCheckPending(ctx)) {
                        return@LaunchedEffect
                    }
                    com.amapilot.settings.ConfigBackup.clearFavCheckPending(ctx)
                    if (!com.amapilot.settings.AmapKey.isConfigured(ctx)) return@LaunchedEffect
                    // 幂等重注入：tick 可能先于 AmapKey.apply 到达，否则搜索带空 key 失败
                    com.amapilot.settings.AmapKey.apply(ctx)
                    val invalid = runCatching {
                        com.amapilot.data.FavoriteValidator.findInvalidNames(
                            ctx, FavoriteRepository(ctx).load()
                        )
                    }.getOrDefault(emptyList())
                    if (invalid.isNotEmpty()) invalidFavs = invalid
                }
                invalidFavs?.let { names ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { invalidFavs = null },
                        title = {
                            androidx.compose.material3.Text("以下收藏地点已失效")
                        },
                        // 按行只列名字
                        text = {
                            androidx.compose.material3.Text(names.joinToString("\n"))
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { invalidFavs = null }
                            ) { androidx.compose.material3.Text("知道了") }
                        }
                    )
                }
                var hasLocationPermission by remember { mutableStateOf(false) }
                var showSearch by remember { mutableStateOf(false) }
                // 焦点地点（POI 点击/长按/探索换焦点共用）；null = 无卡浏览态
                var focusPoint by remember { mutableStateOf<SearchedPoi?>(null) }
                // 卡来源=搜索选点 → back 回搜索列表；其他来源 back 只收卡
                var searchResultOpen by remember { mutableStateOf(false) }
                // 探索态：中心非 null = 结果换焦点（不进路线不记历史）；keyword = chip 预填
                var exploreCenter by remember { mutableStateOf<SearchedPoi?>(null) }
                var exploreKeyword by remember { mutableStateOf<String?>(null) }
                // 停靠点列表（提升到 Activity 以防导航往返丢失）：首=起点、尾=终点、
                // 中间=途经点。空=浏览态，≥2=路线编辑态。
                var routeStops by remember { mutableStateOf<List<RouteStop>>(emptyList()) }
                // 选点模式：非 null 时搜索选定的地点存为家/公司（不发起导航）
                var pickingKind by remember {
                    mutableStateOf<FavoritePlace.Kind?>(null)
                }
                val favRepo = remember { FavoriteRepository(this) }
                var favorites by remember { mutableStateOf(favRepo.load()) }
                var navigating by remember { mutableStateOf(false) }
                var navigateDest by remember { mutableStateOf<com.amap.api.maps.model.LatLng?>(null) }
                var navigatePathId by remember { mutableStateOf<Int?>(null) }
                var navigateWaypoints by remember {
                    mutableStateOf<List<com.amapilot.navigation.NaviWaypoint>>(emptyList())
                }
                var cruising by remember { mutableStateOf(false) }
                // 巡航屏跟随会话态：会话升沿自动进巡航屏，手动退出不触发
                val cruiseActive by
                    com.amapilot.navigation.CruiseSession.activeFlow.collectAsState()
                androidx.compose.runtime.LaunchedEffect(cruiseActive) {
                    if (cruiseActive && !navigating) cruising = true
                    if (!cruiseActive) cruising = false
                }
                // 外部发起导航（分享三选/车机侧）同步手机 UI 进 TBT；会话结束同步骤退
                val naviActive by
                    com.amapilot.navigation.NaviSession.activeFlow.collectAsState()
                androidx.compose.runtime.LaunchedEffect(naviActive) {
                    if (naviActive && !navigating) {
                        navigateDest = com.amapilot.navigation.NaviSession.destination
                        navigateWaypoints = com.amapilot.navigation.NaviSession.waypoints.value
                        navigating = true
                    }
                    if (!naviActive) navigating = false
                }
                var showSettings by remember { mutableStateOf(false) }
                // key 启动鉴权探针：错 key/配额超限显式告知（否则底图静默空白）
                var keyProbe by remember {
                    mutableStateOf<com.amapilot.settings.AmapKey.ProbeResult>(
                        com.amapilot.settings.AmapKey.lastProbe
                    )
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    keyProbe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.amapilot.settings.AmapKey.probe(appContext)
                    }
                }
                // 启动存活标记：8s 后落盘，供下次判别是否崩溃
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(8000)
                    prefs.edit().putLong(KEY_BOOT_OK, System.currentTimeMillis()).apply()
                }
                val keyInvalid =
                    keyProbe as? com.amapilot.settings.AmapKey.ProbeResult.KeyInvalid
                val keyQuota = keyProbe == com.amapilot.settings.AmapKey.ProbeResult.Quota
                val hasKeyOverride =
                    com.amapilot.settings.Prefs.amapApiKey(appContext).isNotBlank()
                if (keyInvalid != null && hasKeyOverride) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            keyProbe = com.amapilot.settings.AmapKey.ProbeResult.Unknown
                        },
                        title = { androidx.compose.material3.Text("高德 Key 校验失败") },
                        text = {
                            androidx.compose.material3.Text(
                                "错误码 ${keyInvalid.code}：key 与本机签名 SHA1 或平台" +
                                    "（Android）不匹配。" +
                                    if (com.amapilot.settings.AmapKey.bundled(appContext)
                                        .isNotBlank()
                                    ) {
                                        "可回退内置 key 恢复可用。"
                                    } else {
                                        "可清除后重启重新配置。"
                                    }
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                com.amapilot.settings.Prefs.setAmapApiKey(appContext, "")
                                com.amapilot.settings.Prefs.setKeySetupDismissed(appContext, false)
                                keyProbe = com.amapilot.settings.AmapKey.ProbeResult.Unknown
                                android.widget.Toast.makeText(
                                    this,
                                    if (com.amapilot.settings.AmapKey.bundled(appContext)
                                        .isNotBlank()
                                    ) {
                                        "已清除自定义 key，重启后恢复内置"
                                    } else {
                                        "已清除自定义 key，重启后进入配置引导"
                                    },
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }) {
                                androidx.compose.material3.Text(
                                    if (com.amapilot.settings.AmapKey.bundled(appContext)
                                        .isNotBlank()
                                    ) {
                                        "回退内置"
                                    } else {
                                        "清除并重新配置"
                                    }
                                )
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                keyProbe = com.amapilot.settings.AmapKey.ProbeResult.Unknown
                            }) { androidx.compose.material3.Text("保留当前") }
                        }
                    )
                } else if (keyQuota && !hasKeyOverride) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            keyProbe = com.amapilot.settings.AmapKey.ProbeResult.Unknown
                        },
                        title = { androidx.compose.material3.Text("高德配额超限") },
                        text = {
                            androidx.compose.material3.Text(
                                "内置 key 今日配额已用完（错误码 1004）。到设置→高德 Key" +
                                    " 配置自己的 key 可恢复。"
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                keyProbe = com.amapilot.settings.AmapKey.ProbeResult.Unknown
                                showSettings = true
                            }) { androidx.compose.material3.Text("去设置") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                keyProbe = com.amapilot.settings.AmapKey.ProbeResult.Unknown
                            }) { androidx.compose.material3.Text("知道了") }
                        }
                    )
                }
                var myLocation by remember { mutableStateOf<com.amap.api.maps.model.LatLng?>(null) }
                // 搜索基准快照 = 打开搜索瞬间的视野中心（拖图后围绕显示区域搜索；
                // 引擎未就绪回退 myLocation）
                var searchAnchor by remember {
                    mutableStateOf<com.amap.api.maps.model.LatLng?>(null)
                }
                val snapshotSearchAnchor = {
                    searchAnchor = runCatching {
                        com.amapilot.map.MapHolder.acquire(this)
                            .map.cameraPosition?.target
                    }.getOrNull()
                }
                // 返回键优先退子页面（覆盖层 → 导航 → 巡航 → 地点卡 → 路线编辑），
                // 避免直接退出应用。地点卡返回只收卡，地图相机不动。
                androidx.activity.compose.BackHandler(
                    enabled = navigating || cruising || showSearch || showSettings ||
                        routeStops.size >= 2 || focusPoint != null
                ) {
                    when {
                        showSearch -> {
                            showSearch = false
                            pickingKind = null
                            exploreCenter = null
                            exploreKeyword = null
                        }
                        showSettings -> showSettings = false
                        navigating -> navigating = false
                        cruising -> {
                            cruising = false
                            // 手动退出：停车前不再自动重入巡航
                            com.amapilot.map.AutoCruiseGuard.armed = false
                        }
                        focusPoint != null -> {
                            if (searchResultOpen) {
                                // 搜索选点的卡：返回回搜索列表换选（草稿与结果保留）
                                searchResultOpen = false
                                focusPoint = null
                                showSearch = true
                            } else {
                                focusPoint = null
                            }
                        }
                        routeStops.size >= 2 -> {
                            routeStops = emptyList()
                        }
                    }
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> hasLocationPermission = granted }
                if (!agreed) {
                    PrivacyPolicyDialog(
                        onAgree = {
                            com.amapilot.settings.AmapPrivacy.assert(this)
                            prefs.edit().putBoolean(KEY_PRIVACY_AGREED, true).apply()
                            agreed = true
                        },
                        onDisagree = { finish() }
                    )
                } else if (!keySetupDone) {
                    com.amapilot.settings.KeySetupScreen(onDone = { keySetupDone = true })
                } else {
                    // 车机会话活跃：手机退居辅助位（输入/分享），导航在车机端。
                    // 判定只用会话级 carConnected（Surface 级会陈旧滞留，勿用）
                    val carConnected by com.amapilot.auto.CarMapControl.carConnected
                        .collectAsState()
                    if (carConnected) {
                        CarConnectedScreen()
                    } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize()
                    ) {
                        // 基础层：地图/巡航/导航互斥直切（共享 MapView 不可重叠），
                        // 过渡感由 chrome 入场与相机动画承担
                        when {
                            cruising -> CruiseScreen(
                                emulator = com.amapilot.auto.CarRouteStarter.naviEmulatorMode(appContext),
                                isNight = isNight,
                                onSearchClick = {
                                    exploreCenter = null
                                    exploreKeyword = null
                                    snapshotSearchAnchor()
                                    showSearch = true
                                },
                                onExit = {
                                    cruising = false
                                    // 手动退出：停车前不再自动重入巡航
                                    com.amapilot.map.AutoCruiseGuard.armed = false
                                }
                            )
                            navigating -> TbtScreen(
                                emulator = com.amapilot.auto.CarRouteStarter.naviEmulatorMode(appContext),
                                destination = navigateDest,
                                initialPathId = navigatePathId,
                                waypoints = navigateWaypoints,
                                isNight = isNight,
                                onExit = { navigating = false },
                                onArrived = {
                                    navigating = false
                                    // 到达=行程完成：清空路线（手动中途退出保留停靠点）
                                    routeStops = emptyList()
                                }
                            )
                            else -> {
                                LaunchedEffect(Unit) {
                                    hasLocationPermission =
                                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                                            PackageManager.PERMISSION_GRANTED
                                    if (!hasLocationPermission) {
                                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                }
                                MapScreen(
                                    mode = MapMode.BROWSE,
                                    hasLocationPermission = hasLocationPermission,
                                    isNight = isNight,
                                    emulator = com.amapilot.auto.CarRouteStarter.naviEmulatorMode(appContext),
                                    stops = routeStops,
                                    onStopsChange = { routeStops = it },
                                    focusPoint = focusPoint,
                                    onFocusPointChange = { fp ->
                                        focusPoint = fp
                                        // 非搜索来源：清除搜索回链标记（back 不误回搜索）
                                        searchResultOpen = false
                                    },
                                    onExploreSearch = { place, keyword ->
                                        exploreCenter = place
                                        exploreKeyword = keyword
                                        showSearch = true
                                    },
                                    onRouteFromPlace = { poi ->
                                        // 〔开始导航〕：记地点历史（选定语义）并进多路线选择
                                        HistoryStore(appContext).addPlace(
                                            poi.name, poi.address, poi.lat, poi.lng, poi.id
                                        )
                                        focusPoint = null
                                        searchResultOpen = false
                                        routeStops = listOf(
                                            RouteStop.MyLocation,
                                            RouteStop.Poi(poi)
                                        )
                                    },
                                    onSearchClick = {
                                        exploreCenter = null
                                        exploreKeyword = null
                                        snapshotSearchAnchor()
                                        showSearch = true
                                    },
                                    onLocationChanged = { myLocation = it },
                                    onStartNavigation = { dest, pathId, wps ->
                                        navigateDest = dest
                                        navigatePathId = pathId
                                        navigateWaypoints = wps
                                        navigating = true
                                    },
                                    onClearSelection = {
                                        routeStops = emptyList()
                                    },
                                    onAutoCruise = { cruising = true },
                                    onSettingsClick = { showSettings = true },
                                    favorites = favorites,
                                    onPickFavorite = { fav ->
                                        // 新目的地：起点默认「我的位置」，清空上一路线
                                        routeStops = listOf(
                                            RouteStop.MyLocation,
                                            RouteStop.Poi(
                                                SearchedPoi(
                                                    id = fav.id,
                                                    name = fav.name,
                                                    address = fav.address,
                                                    lat = fav.lat,
                                                    lng = fav.lng
                                                )
                                            )
                                        )
                                    },
                                    onDeleteFavorite = { fav ->
                                        favRepo.remove(fav.id)
                                        favorites = favRepo.load()
                                    },
                                    onRequestSetKind = { kind ->
                                        // 进入选点模式：搜索选定后存为家/公司
                                        pickingKind = kind
                                        showSearch = true
                                    },
                                    onDemoteFavorite = { fav ->
                                        // 取消设为家/公司：降级为普通收藏（保留地点）
                                        favRepo.add(fav.copy(kind = FavoritePlace.Kind.NORMAL))
                                        favorites = favRepo.load()
                                    }
                                )
                            }
                        }
                        // 搜索覆盖层：自顶部滑入（与入口搜索栏一致），地图保留在下方
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showSearch,
                            enter = androidx.compose.animation.slideInVertically(
                                androidx.compose.animation.core.tween(
                                    com.amapilot.ui.theme.GmMotion.DurationLong,
                                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedDecelerate
                                )
                            ) { -it } + androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationMedium)
                            ),
                            exit = androidx.compose.animation.slideOutVertically(
                                androidx.compose.animation.core.tween(
                                    com.amapilot.ui.theme.GmMotion.DurationMedium,
                                    easing = com.amapilot.ui.theme.GmMotion.EmphasizedAccelerate
                                )
                            ) { -it } + androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(com.amapilot.ui.theme.GmMotion.DurationShort)
                            )
                        ) {
                            val searchViewModel = viewModel {
                                SearchViewModel(
                                    searcher = PoiSearcher(appContext),
                                    historyStore = HistoryStore(appContext),
                                    // 检索基准：探索中心 > 视野快照 > GPS
                                    locationProvider = { searchAnchor ?: myLocation },
                                    centerProvider = { exploreCenter }
                                )
                            }
                            SearchScreen(
                                onBack = {
                                    showSearch = false
                                    pickingKind = null
                                    exploreCenter = null
                                    exploreKeyword = null
                                },
                                onPoiSelected = { poi ->
                                    val kind = pickingKind
                                    val center = exploreCenter
                                    when {
                                        kind != null -> {
                                            // 选点模式：存为家/公司（upsert 保证类别唯一），不发起导航
                                            favRepo.add(FavoriteRepository.fromPoi(poi).copy(kind = kind))
                                            favorites = favRepo.load()
                                            pickingKind = null
                                        }
                                        center != null -> {
                                            // 探索结果换焦点回地点卡（浏览意图，不记历史）
                                            focusPoint = poi
                                            searchResultOpen = false
                                            exploreCenter = null
                                        }
                                        else -> {
                                            // 选定地点入共享历史（与车机同源；联想直选同经此回调）
                                            HistoryStore(appContext).addPlace(
                                                poi.name, poi.address, poi.lat, poi.lng, poi.id
                                            )
                                            if (cruising) {
                                                // 巡航中选定目的地：直接转入导航
                                                navigateDest = com.amap.api.maps.model.LatLng(poi.lat, poi.lng)
                                                cruising = false
                                                navigating = true
                                            } else {
                                                // 搜索选点 → 地点卡（先看地点再导航），
                                                // 与 POI 点击/长按同一条链；back 回搜索列表
                                                focusPoint = poi
                                                searchResultOpen = true
                                            }
                                        }
                                    }
                                    showSearch = false
                                },
                                onFavorite = { poi ->
                                    // 心形=普通收藏（再点移除）；家/公司走抽屉「常用地点」，避免误降级
                                    val fp = FavoriteRepository.fromPoi(poi)
                                    val existing = favorites.firstOrNull { it.id == fp.id }
                                    when {
                                        existing == null -> favRepo.add(fp)
                                        existing.kind == FavoritePlace.Kind.NORMAL ->
                                            favRepo.remove(fp.id)
                                    }
                                    favorites = favRepo.load()
                                },
                                favorites = favorites,
                                // 拖图后搜索静默以显示区域为中心，不做偏移提示
                                banner = pickingKind?.let {
                                    if (it == FavoritePlace.Kind.HOME) "正在设置家：搜索并选择位置"
                                    else "正在设置公司：搜索并选择位置"
                                } ?: exploreCenter?.let { "在 · ${it.name} 附近" },
                                // 探索入口：复位上一轮 + 预填分类词自动检索（不抢键盘焦点）
                                resetOnEnter = exploreCenter != null,
                                initialQuery = exploreKeyword,
                                viewModel = searchViewModel
                            )
                        }
                        // 设置覆盖层：自底部滑入（入口在底部抽屉「路线喜好」，转场来源与入口一致）
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showSettings,
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
                            )
                        ) {
                            SettingsScreen(
                                onBack = { showSettings = false },
                                dayNight = dayNightPref,
                                onDayNightChange = { dayNightPref = it }
                            )
                        }
                    }
                }
                    }
            }
        }
    }

    /** 车机会话活跃时的手机辅助态：提示使用车机屏（手机仅作输入/分享接收）。 */
    @androidx.compose.runtime.Composable
    private fun CarConnectedScreen() {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Place,
                contentDescription = null,
                tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = androidx.compose.ui.Modifier.size(56.dp)
            )
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(16.dp)
            )
            androidx.compose.material3.Text(
                "已连接车机",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
            )
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(8.dp)
            )
            androidx.compose.material3.Text(
                "导航与路线选择请在车机屏操作",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(4.dp)
            )
            androidx.compose.material3.Text(
                "手机可继续接收外部分享的地址",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(24.dp)
            )
            // 搜索输入：手机负责打字，结果在车机屏选择
            var queryText by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf("")
            }
            androidx.compose.material3.OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                singleLine = true,
                placeholder = { androidx.compose.material3.Text("在车机屏点搜索，这里输入关键词") },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
            )
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(12.dp)
            )
            androidx.compose.material3.Button(
                onClick = {
                    com.amapilot.auto.CarSearchRelay.submit(
                        applicationContext, queryText
                    )
                },
                enabled = queryText.isNotBlank()
            ) {
                androidx.compose.material3.Text("搜索（结果显示在车机端）")
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "amapilot_prefs"
        private const val KEY_BOOT_TS = "key_boot_ts"
        private const val KEY_BOOT_OK = "key_boot_ok"
        private const val KEY_BOOT_FAILS = "key_boot_fails"
        private const val KEY_PRIVACY_AGREED = "privacy_agreed"

        /** 模拟器检测：无真实位移时 TBT 用模拟导航。 */
        fun isEmulatorDevice(): Boolean =
            android.os.Build.FINGERPRINT.contains("generic") ||
                android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.PRODUCT.contains("sdk") ||
                android.os.Build.HARDWARE.contains("ranchu")
    }
}
