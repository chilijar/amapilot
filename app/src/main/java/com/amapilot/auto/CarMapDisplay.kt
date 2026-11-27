package com.amapilot.auto

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.amap.api.maps.CameraUpdate
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amapilot.map.systemGpsSource
import com.amapilot.navigation.NaviSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 临近机动上抬的低速门（km/h）：低于此且进入机动坡道带时俯仰回抬。 */
private const val TILT_LIFT_MAX_KMH = 30f

/** 临近机动的上抬目标俯仰角（度）。 */
private const val TILT_NEAR_MANEUVER = 55f

/** 车机地图 3D 跟随俯仰角（度；0=正上俯视）。低速临近转向点时经
 *  [maneuverLiftTilt] 上抬；实机可 amapilot_car_tilt（30..70）实时调，10s 缓存。 */
private const val CAR_TILT = 70f

/** 相机前推偏移（**屏幕像素恒定**）：车位置固定在屏高 ~62% 处；必须按缩放级别
 *  换算成米，否则低速放大时会把箭头推出屏幕底边。 */
private const val CAR_AHEAD_SCREEN_PX = 48.0

/** 手动拖拽累计超过此屏幕像素数 → 平滑切 2D 正上视角（Google Maps 行为）。 */
private const val PAN_FLATTEN_PX = 60f

/** 全览/选路做边界拟合时，在可见区内缩之外再加的留白（px）。 */
private const val OVERVIEW_PADDING = 80
private const val ROUTE_SEL_PADDING = 90

/** 选路路径按车位截断时向前多留的顶点数（视觉衔接，线头略压在车位后方）。 */
private const val TRIM_BACK_POINTS = 2

/** 路线颜色（Google 蓝，与手机端 TbtScreen 一致）。 */
private val ROUTE_COLOR = android.graphics.Color.rgb(0x1A, 0x73, 0xE8)

// ── 速度自适应缩放（低速放大看细节、高速缩小看前路；平滑逼近防跳变） ──
// 目标曲线与平滑参数单一来源 = [com.amapilot.navigation.AutoZoom]
// 每帧航向逼近步长（度；时间归一 = 1s 最多 32°）：低于城市弯道角速度会弯中落后。
private const val BEARING_STEP = 8f
private const val MANUAL_ZOOM_HOLD_MS = 15_000L  // 手动缩放后保持时长
// 稳态跟随相机 glide 时长 = **帧周期**（250ms）：每拍动画走完即被下一拍接续；
// 长于帧周期会在减速曲线中途重启 = 速度脉动。可用 amapilot_follow_anim_ms 覆盖。
private const val FOLLOW_ANIM_MS = 250L

// 箭头 marker 引擎侧线性插值时长 = 帧周期（TranslateAnimation + Linear，
// 消 250ms 步进台阶；帧 N 目标 = 帧 N+1 起点）。
private const val ARROW_ANIM_MS = 250L

// ── 路线线宽体系（主信息=蓝线，路况=细彩线辅助）──
private const val ROUTE_WIDTH = 13f      // 蓝色主线（底）
private const val TRAFFIC_WIDTH = 6f     // 路况彩线（叠主线中央）
// 已走灰线（盖住主线+路况）：宽度必须与主线一致（覆盖要求），常量引用保证同步
private const val PASSED_WIDTH = ROUTE_WIDTH
private const val ALT_ROUTE_WIDTH = 11f  // 选路备选线
private const val ALT_HL_WIDTH = 19f     // 选路白描边（两侧各露 4px）

/** 车机 MapView 几何缩放：地名无公开字号 API → 按 1/SCALE 布局后 scaleX/Y=SCALE
 *  （pivot 0,0）；线宽/徽标/像素类相机 API 一律经 [glw]·[/SCALE] 补偿。 */
private const val MAP_VIEW_SCALE = 0.6f

/** 运行时缩放：GL 表面 ≤2048（SurfaceView buffer 上限，超出右侧裁切）→
 *  取 max(0.6, W/2048, H/2048)；像素换算一律用本值。 */
internal var mapViewScale: Float = MAP_VIEW_SCALE
    private set

/** 按表面尺寸计算安全缩放（GL 表面 ≤2048，车机 buffer 上限实测档）。 */
private fun safeMapScale(w: Int, h: Int): Float =
    maxOf(MAP_VIEW_SCALE, w / 2048f, h / 2048f)

/** 屏上设计 dp → GL 表面线宽（密度 × MapView 缩放双补偿）。 */
private fun glw(screenWidth: Float): Float = screenWidth * densityFactor / mapViewScale

/** 灰线边界插值退化阈值：t 小于此值时零长尾段会让转折点 join 退化（蓝线转角从灰线下露出）。 */
private const val BOUNDARY_SNAP_EPS = 0.02

/** 灰线重绘的最小边界推进（米）：小于此且 1s 内画过则跳过（setPoints 是整表 JNI 重建）。 */
private const val GRAY_REDRAW_ADVANCE_M = 1.0

/** 路况分段配色（AMap status：1畅通 2缓行 3拥堵 4严重拥堵；未知=与主线同蓝，视觉隐形）。 */
private fun trafficColorFor(status: Int): Int = when (status) {
    1 -> 0xFF00A850.toInt()
    2 -> 0xFFFFB300.toInt()
    3 -> 0xFFE53935.toInt()
    4 -> 0xFFB71C1C.toInt()
    else -> ROUTE_COLOR
}

/** 已走过路段的灰色覆盖线（盖在路况彩线上方，随定位推进延伸）。 */
private val PASSED_ROUTE_COLOR = android.graphics.Color.rgb(0x6B, 0x72, 0x80)

/** 备选路线灰线（选路模式，半透明银灰适配夜间底图）。 */
private val ALT_ROUTE_COLOR = android.graphics.Color.argb(0xCC, 0x9A, 0xA3, 0xB2)

/** 搜索行（车机 SearchTemplate 结果行/收藏行共用模型；lat/lng 有效 = 可点行）。 */
data class SearchPanelRow(
    val title: String,
    val subtitle: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 原 POI id（历史/收藏同 id 语义对齐用）。 */
    val id: String = "",
)

/** 选路浮动面板一行（overlay 渲染与 handleClick 命中共用；pathId 用于切换）。 */
data class RouteSelOption(
    val pathId: Int,
    val label: String,
    val timeText: String,
    val distText: String,
    val lightsText: String,
    val deltaText: String,
    val current: Boolean,
)

/**
 * 车机地图自绘（VirtualDisplay 方案）：宿主 [Surface] 作为 VD 合成目标，其上跑高德
 * [MapView]（不经 SurfaceTexture，避免消费端所有权冲突）；相机/手势经本单例中转。
 */
object CarMapControl {
    @Volatile var presentation: CarMapPresentation? = null

    /** 车机会话是否活跃（手机端据此切换为「请使用车机端」辅助态）。 */
    val carSessionActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 车机表面尺寸与可见区（**宿主多窗口/分屏切换时会变**）。Compose State：叠加层
     *  读取即订阅、自动重排，[handleClick] 读同一份值 → 绘制与命中永远一致。 */
    val metrics = androidx.compose.runtime.mutableStateOf(CarScreenMetrics())

    /** 右竖排避让宽度：[setVisibleArea] 经 [CarUiGeometry.learnedRailZone] 学习（只增不减）。 */
    @Volatile
    private var railZone: Int = CarUiGeometry.SELF_RAIL_ZONE_DEFAULT

    /** 宿主表面尺寸变化（`onSurfaceAvailable` 重派或 VD resize 后）；可见区未给过时按整屏处理。
     *
     *  @return 尺寸是否真的变了（调用方据此决定是否重解相机） */
    fun setSurfaceSize(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val cur = metrics.value
        if (cur.width == width && cur.height == height) return false
        // 保留宿主上次给的可见区内缩（按新尺寸重新夹取），别一 resize 就丢掉副窗口让位
        metrics.value = hostVisibleArea?.let { r ->
            CarScreenMetrics.of(width, height, r.left, r.top, r.right, r.bottom, railZone)
        } ?: CarScreenMetrics(width, height, railZone = railZone)
        Log.i(TAG, "car surface ${cur.width}x${cur.height} -> ${width}x${height}")
        return true
    }

    /** 宿主可见区（`onVisibleAreaChanged`）→ 内容区内缩。**必须参与布局**：忽略它会让
     *  卡片左缘插进 chrome、右竖排压在媒体副窗口下（逐边策略见 [CarScreenMetrics.of]）。 */
    @Volatile
    var hostVisibleArea: android.graphics.Rect? = null
        private set

    fun setVisibleArea(left: Int, top: Int, right: Int, bottom: Int) {
        val r = android.graphics.Rect(left, top, right, bottom)
        val prev = hostVisibleArea
        hostVisibleArea = r
        if (prev == r) return
        val cur = metrics.value
        if (right - left < 40 || bottom - top < 40) {
            Log.w(TAG, "ignore degenerate visible area $r")
            return
        }
        // rail 宽度自学习：右内缩（宿主原始值）落在 rail 区间则采纳较大者
        val prevZone = railZone
        railZone = CarUiGeometry.learnedRailZone(railZone, cur.width - right)
        if (railZone != prevZone) {
            Log.i(TAG, "rail zone learned: $prevZone -> $railZone (right inset ${cur.width - right})")
        }
        val next = CarScreenMetrics.of(cur.width, cur.height, left, top, right, bottom, railZone)
        Log.i(
            TAG, "host visible area $r -> insets L${next.insetLeft} T${next.insetTop} " +
                "R${next.insetRight} B${next.insetBottom} " +
                "(content ${next.contentWidth}x${next.contentHeight} center ${next.centerX},${next.centerY})"
        )
        if (next == cur) return
        metrics.value = next
        // 取景中心/边界拟合都要跟着可见区走（箭头不能被副窗口盖住）
        presentation?.onViewportChanged()
    }

    /** 由 NavigationScreen 注入：搜索按钮推入宿主 SearchTemplate 屏。 */
    @Volatile var onSearch: (() -> Unit)? = null

    /** 由 NavigationScreen 注入：备选路线（宿主）入口。 */
    @Volatile var onAlternates: (() -> Unit)? = null

    /** 由 NavigationScreen 注入：取消导航（卡片按钮行潜在通道用）。 */
    @Volatile var onCancelNavi: (() -> Unit)? = null

    /** 由 NavigationScreen 注入：全览/回导航视角切换（宿主顶栏文字动作通道）。 */
    @Volatile var onOverview: (() -> Unit)? = null

    /** 由 NavigationScreen 注入：打开选路 pushed 屏（back = pop 回栈下屏）。 */
    @Volatile var onOpenRouteSelect: (() -> Unit)? = null

    /** 由 [CarRouteSelectScreen] 注入：选路收尾（提交/直选/back 退出）后关屏。 */
    @Volatile var onRouteSelectClosed: (() -> Unit)? = null

    /** 车辆昼夜态（宿主经 Configuration uiMode 派发；null = 未连接/未派发）。 */
    val carDarkMode = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    /** 昼夜设置变更 tick（设置页切换后全局重应用底图样式）。 */
    val dayNightTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * [resolveNight] 的解析结果流（null = 尚未解析）：底图样式与叠加层卡片配色
     * 的**单一昼夜源**——[CarMapDisplay.applyMapDayNight] 每次重算时落点于此，
     * 保证卡片与底图永不同步漂移。
     */
    val resolvedNight = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    /** 昼夜解析优先级：设置覆盖（白天/夜间）> 车连时车辆态 > 手机系统 uiMode。 */
    fun resolveNight(context: android.content.Context): Boolean =
        when (com.amapilot.settings.Prefs.dayNightMode(context)) {
            com.amapilot.settings.Prefs.DAY_NIGHT_NIGHT -> true
            com.amapilot.settings.Prefs.DAY_NIGHT_DAY -> false
            else -> carDarkMode.value ?: (
                (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                )
        }

    /** 选路模式状态：null=关闭；列表=浮动面板行（overlay 渲染与命中测试共用）。 */
    val routeSelect = kotlinx.coroutines.flow.MutableStateFlow<List<RouteSelOption>?>(null)

    /** 选路图形缓存：新 Presentation 在 mapLoaded 后据此重绘备选线/徽标（Surface 重建会清覆盖物）。 */
    @Volatile var selPaths: Map<Int, List<LatLng>> = emptyMap()
    @Volatile var selSelectedId: Int? = null

    /** 车机 Surface 附着态（地图/叠加层存活）。 */
    val carConnected = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 选路行数据（自绘层徽标序号/当前标记用）。 */
    @Volatile var selOpts: List<RouteSelOption> = emptyList()

    /** 选路数据版本（自绘层重组触发之一）。 */
    val selVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** 相机变化计数（自绘选路层逐帧重投影触发）。 */
    val cameraTick = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** LatLng → 车机屏 px（含 MapView 几何缩放换算）；选路自绘层投影用。 */
    fun projectToScreen(p: LatLng): android.graphics.Point? = runCatching {
        val pt = presentation?.aMapForProject()?.projection?.toScreenLocation(p)
            ?: return null
        android.graphics.Point(
            (pt.x * mapViewScale).toInt(),
            (pt.y * mapViewScale).toInt()
        )
    }.getOrNull()

    /** 旋钮/宿主 chip 选中同步高亮：key=自绘按钮标识，对应按钮点亮 600ms。 */
    val actionFlash = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val flashHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 点亮自绘按钮 [key] 600ms（宿主 chip 点击与自绘触摸共用）。 */
    fun flashAction(key: String) {
        android.util.Log.i("CarMapDisplay", "action flash: $key")
        actionFlash.value = key
        flashHandler.removeCallbacksAndMessages(null)
        flashHandler.postDelayed({ actionFlash.value = null }, 600)
    }

    /** 导航前选路模式：routeSelect 行来自算路结果（非导航中引擎备选），行点击=用该路线发起导航。 */
    @Volatile var preNavSelectMode: Boolean = false

    /** 预导航选路的目标点（commitPreNav 发起导航用）。 */
    @Volatile var preNavDest: com.amap.api.maps.model.LatLng? = null

    /** 导航前选路：复用选路侧栏渲染/命中；行点击以对应路线 start（selectRouteId 仅导航前生效）。 */
    fun openPreNavSelect(
        rows: List<RouteSelOption>,
        dest: com.amap.api.maps.model.LatLng,
        paths: Map<Int, List<LatLng>> = emptyMap(),
    ) {
        preNavDest = dest
        preNavSelectMode = true
        routeSelect.value = rows
        // 备选线 + 序号徽标上图。select 早于地图就绪时 enterRouteSelect 会早退，
        // 先落 selPaths 缓存供 mapLoaded 重入
        if (paths.isNotEmpty()) {
            if (selPaths.isEmpty()) selPaths = paths
            presentation?.enterRouteSelect(
                paths, rows.firstOrNull { it.current }?.pathId, rows
            )
        }
        onOpenRouteSelect?.invoke()
    }

    /** 搜索状态反馈（算路中…/搜索中…）：overlay 顶部 pill；替代旧搜索卡 header。 */
    val searchStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** 设置/清除搜索状态 pill 文本。 */
    fun setSearchStatus(text: String?) {
        searchStatus.value = text
    }

    /** 由 NavigationScreen 注入：收藏列表加载。 */
    @Volatile
    var favoritesLoader: (() -> List<com.amapilot.data.FavoritePlace>)? = null

    /** 由 NavigationScreen 注入：搜索行选中（发起导航或加途经点）。 */
    @Volatile
    var onSearchSelect: ((row: SearchPanelRow, asWaypoint: Boolean) -> Unit)? = null

    /** 收藏原始数据（车机搜索屏默认态快捷行/收藏展开用）。 */
    fun favoritePlaces(): List<com.amapilot.data.FavoritePlace> =
        favoritesLoader?.invoke().orEmpty()

    /** 收藏 → 搜索行（车机 SearchTemplate 屏初始行；宿主键盘输入前的快捷入口）。 */
    fun favoriteRows(): List<SearchPanelRow> =
        favoritesLoader?.invoke().orEmpty().take(8).map { f ->
            SearchPanelRow(
                when (f.kind) {
                    com.amapilot.data.FavoritePlace.Kind.HOME -> "家 · ${f.name}"
                    com.amapilot.data.FavoritePlace.Kind.WORK -> "公司 · ${f.name}"
                    else -> f.name
                },
                f.address, f.lat, f.lng
            )
        }

    /** 完整路线全览中（overlay 显示"返回导航视角"胶囊；空点地图/⌖/再按⛶均返回）。 */
    val overviewActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 相机三态（顶栏/右竖排 toggle 循环：跟随 → 北向上 → 全览）。 */
    enum class CameraMode { FOLLOW, NORTH, OVERVIEW }

    val cameraMode = kotlinx.coroutines.flow.MutableStateFlow(CameraMode.FOLLOW)

    /** 跟随被拖偏/全览暂停（FOLLOW 态再点 toggle = 回正而非切态）。 */
    val followPaused = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 相机 toggle：FOLLOW 且被拖偏 = 回正；否则循环 跟随→北向上→全览。 */
    fun cycleCameraMode() {
        if (cameraMode.value == CameraMode.FOLLOW && followPaused.value) {
            android.util.Log.i("CarMapDisplay", "camera toggle -> recenter (panned)")
            recenter()
            return
        }
        val next = when (cameraMode.value) {
            CameraMode.FOLLOW -> CameraMode.NORTH
            CameraMode.NORTH -> CameraMode.OVERVIEW
            CameraMode.OVERVIEW -> CameraMode.FOLLOW
        }
        android.util.Log.i("CarMapDisplay", "camera mode -> $next")
        when (next) {
            CameraMode.OVERVIEW -> showOverview()
            CameraMode.FOLLOW -> recenter()
            CameraMode.NORTH -> if (overviewActive.value) recenter()
        }
        cameraMode.value = next
    }

    /** 宿主复合行程卡开关。**启用**：数据/✕ 由宿主渲染（旋钮可焦），自绘卡退为按钮行
     *  微档与宿主卡共存；false = 自绘整卡四钮兜底（真车形态不可控，仅保底）。 */
    const val hostTripCard = true

    /** 途经点模式（卡内 📍 钮 / 搜索屏开关行共享；ON 时结果行点击 = 加途经点）。 */
    val waypointMode = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 卡内 📍 钮：途经点模式切换（与搜索屏开关行同状态）。 */
    fun toggleWaypoint() {
        waypointMode.value = !waypointMode.value
        flashAction("waypoint")
    }

    /** 选路模式开关（"其他路线"钮/顶栏图标）：展开 = 备选线 + 2D 北向上全程视野 +
     *  差异面板；再点收起。 */
    fun toggleRouteSelect() {
        if (routeSelect.value != null) {
            exitRouteSelect(null)
            return
        }
        // 打开即从引擎重导入：naviPaths 随行驶刷新，面板基于当前剩余行程
        com.amapilot.navigation.NaviSession.refreshRouteData()
        com.amapilot.navigation.NaviSession.dumpRouteStatePublic("select-opened")
        val rows = buildRouteSelOptions()
        if (rows == null) {
            Log.i(TAG, "route select: no alternatives")
            return
        }
        routeSelect.value = rows
        presentation?.enterRouteSelect(
            com.amapilot.navigation.NaviSession.routePaths.value,
            com.amapilot.navigation.NaviSession.selectedPathId.value,
            rows
        )
        Log.i(TAG, "route select entered: ${rows.size} options")
        onOpenRouteSelect?.invoke()
    }

    /** 引擎备选数据更新 → 面板行与灰线同步刷新（选路模式开启时）。 */
    fun refreshRouteSelectView() {
        if (routeSelect.value == null) return
        val rows = buildRouteSelOptions() ?: return
        routeSelect.value = rows
        presentation?.refreshRouteSelect(
            com.amapilot.navigation.NaviSession.routePaths.value,
            com.amapilot.navigation.NaviSession.selectedPathId.value,
            rows
        )
    }

    /** 面板行构建（无备选返回 null；数字=引擎当前 naviPaths 剩余量）。 */
    fun buildRouteSelOptions(): List<RouteSelOption>? {
        val opts = com.amapilot.navigation.NaviSession.routeOptions.value
        if (opts.size < 2) return null
        val sel = com.amapilot.navigation.NaviSession.selectedPathId.value
        val minDur = opts.minOf { it.durationSeconds }
        return opts.mapIndexed { i, o ->
            RouteSelOption(
                pathId = o.pathId,
                label = "路线 ${i + 1}",
                timeText = fmtDuration(o.durationSeconds),
                distText = fmtDistance(o.lengthMeters),
                lightsText = if (o.trafficLights > 0) "${o.trafficLights} 红绿灯" else "",
                deltaText = when {
                    o.pathId == sel -> "当前"
                    o.durationSeconds <= minDur -> "最快"
                    else -> "+${(o.durationSeconds - minDur + 59) / 60} 分"
                },
                current = o.pathId == sel,
            )
        }
    }

    /** 退出选路模式；pathId 非空则切换路线。回到 3D 车头向上由 ticker 恢复。 */
    fun exitRouteSelect(pathId: Int?) {
        val wasOpen = routeSelect.value != null
        if (!wasOpen && pathId == null) return
        val preNav = preNavSelectMode
        if (preNav && pathId != null) {
            // 预导航行点击：以该路线发起导航（宿主侧栏行是唯一提交通道）
            CarRouteStarter.commitPreNav(pathId)
            presentation?.exitRouteSelect()
            onRouteSelectClosed?.invoke()
            return
        }
        preNavSelectMode = false
        preNavDest = null
        routeSelect.value = null
        selPaths = emptyMap()
        selSelectedId = null
        if (wasOpen) presentation?.exitRouteSelect()
        if (pathId != null) {
            Log.i(TAG, "route selected: pathId=$pathId")
            com.amapilot.navigation.NaviSession.selectRoute(pathId)
        } else if (wasOpen) {
            Log.i(TAG, "route select exited (no change)")
        }
        onRouteSelectClosed?.invoke()
    }

    private fun fmtDuration(seconds: Int): String = when {
        seconds >= 3600 -> String.format("%.1f 小时", seconds / 3600.0)
        else -> "${maxOf(1, seconds / 60)} 分钟"
    }

    private fun fmtDistance(meters: Int): String = when {
        meters >= 1000 -> String.format("%.1f km", meters / 1000.0)
        else -> "$meters m"
    }

    /** 手机端是否前台（Surface 重挂时据此决定是否恢复车机渲染）。 */
    @Volatile
    var phoneForeground = false
        private set

    /** 手机端前台时暂停车机地图渲染（双引擎串行，避免全局渲染锁争抢）。 */
    fun pauseCarRendering() {
        phoneForeground = true
        presentation?.pauseRendering()
    }

    /** 手机端退到后台后恢复车机地图渲染。 */
    fun resumeCarRendering() {
        phoneForeground = false
        presentation?.resumeRendering()
    }

    /** 车机表面点击命中：宿主经 `onClick` 派发，坐标是**表面像素**；叠加层几何在
     *  **dp 空间**（[CarScreenMetrics.scaledBy]）→ 事件 ×(160/D) 归一后比较。
     *  例外：[hitRouteLine] 走引擎投影，原值直用。 */
    fun handleClick(x: Int, y: Int) {
        val f = 160f / vdDensityDpi
        val m = metrics.value.scaledBy(f)
        val xd = (x * f).toInt()
        val yd = (y * f).toInt()
        val g = CarUiGeometry
        // 选路模式：面板关闭 × / 路线行 / 地图灰线直选；其余点击吞掉（按钮行此时隐藏）
        val sel = routeSelect.value
        if (sel != null) {
            val rpx = g.routePanelX(m)
            val rpy = g.routePanelY(m)
            val rpw = g.routePanelW(m)
            if (xd in g.routePanelCloseLeft(m)..(rpx + rpw) &&
                yd in rpy..(rpy + g.ROUTE_PANEL_HEADER_H)
            ) {
                Log.i(TAG, "route panel close hit")
                exitRouteSelect(null)
                return
            }
            sel.take(g.routePanelMaxRows(m)).forEachIndexed { i, opt ->
                val top = g.routePanelRowTop(m, i)
                if (yd in top..(top + g.ROUTE_PANEL_ROW_H) &&
                    xd in rpx..(rpx + rpw)
                ) {
                    Log.i(TAG, "route panel row hit: $i pathId=${opt.pathId}")
                    if (CarMapControl.preNavSelectMode) CarRouteStarter.commitPreNav(opt.pathId)
                    else exitRouteSelect(opt.pathId)
                    return
                }
            }
            val lineHit = presentation?.hitRouteLine(x, y)
            if (lineHit != null) {
                Log.i(TAG, "route line hit: pathId=$lineHit")
                exitRouteSelect(lineHit)
                return
            }
            Log.i(TAG, "route select mode: tap swallowed ($x,$y)")
            return
        }
        // 复合行程卡内嵌按钮行（命中矩形与卡内绘制锚点同源；宿主卡模式 = 微档
        // [选路/途经点/搜索/全览]，自绘卡模式 = Maps 序 [取消/选路/途经点/搜索]）
        if (com.amapilot.navigation.NaviSession.isActive) {
            // 自绘整卡按钮行命中（仅宿主卡关闭的兜底态；Maps 序 取消/选路/途经点/搜索）
            if (!CarMapControl.hostTripCard) {
                val btnY = g.navCardBtnY(m)
                if (yd in btnY..(btnY + g.NAV_BTN_ROW)) {
                    for (i in 0 until g.NAV_BTN_ROW_COUNT) {
                        val bx = g.navCardBtnX(m, i)
                        if (xd in bx..(bx + g.NAV_BTN_ROW)) {
                            Log.i(TAG, "nav card button hit: $i")
                            when (i) {
                                0 -> { flashAction("cancel"); onCancelNavi?.invoke() }
                                1 -> { flashAction("routes"); onAlternates?.invoke() }
                                2 -> toggleWaypoint()
                                3 -> { flashAction("search"); onSearch?.invoke() }
                            }
                            return
                        }
                    }
                }
            }
            // 全览态：胶囊命中 或 地图空点 = 返回导航视角
            if (overviewActive.value) {
                val pillX = g.overviewPillX(m)
                val pillTop = g.overviewPillTop(m)
                if (xd in pillX..(pillX + g.OVERVIEW_PILL_W) &&
                    yd in pillTop..(pillTop + g.OVERVIEW_PILL_H)
                ) {
                    Log.i(TAG, "overview pill hit -> back to navi view")
                    recenter()
                    return
                }
                Log.i(TAG, "overview blank tap -> back to navi view")
                recenter()
                return
            }
        }
        // 空闲/巡航：自绘搜索胶囊已删（入口 = 宿主 rail 搜索 chip），无自绘命中
    }

    /** 完整路线视图（切换式）：全览中再按 = 返回导航视角。 */
    fun showOverview() {
        if (overviewActive.value) {
            Log.i(TAG, "CarMapControl.showOverview -> toggle back to navi view")
            recenter()
            return
        }
        Log.i(TAG, "CarMapControl.showOverview")
        presentation?.showOverview()
        overviewActive.value = true
        followPaused.value = true
        cameraMode.value = CameraMode.OVERVIEW
    }

    /** 定位回正：恢复跟随并回到最近位置（ActionStrip 图标动作调用）。 */
    fun recenter() {
        Log.i(TAG, "CarMapControl.recenter presentation=${presentation != null}")
        if (routeSelect.value != null) exitRouteSelect(null)
        overviewActive.value = false
        followPaused.value = false
        cameraMode.value = CameraMode.FOLLOW
        presentation?.recenterToLast()
    }

    /** 相机跟随（由屏幕 ticker 250ms 外推帧驱动：3D 航向 + 速度/机动自适应缩放）。 */
    fun moveCamera(
        latitude: Double,
        longitude: Double,
        bearing: Float = 0f,
        speedKmh: Float = 0f,
        /** 距下一机动米数（导航态；巡航省略 = 纯速度曲线）。 */
        maneuverRemainMeters: Float = Float.MAX_VALUE,
    ) {
        presentation?.moveCamera(latitude, longitude, bearing, speedKmh, maneuverRemainMeters)
    }

    /** 宿主手势：拖动（拖偏 = 跟随暂停，FOLLOW 态 toggle 变回正语义）。 */
    fun scrollBy(dx: Float, dy: Float) {
        followPaused.value = true
        presentation?.scrollBy(dx, dy)
    }

    /** 宿主手势：缩放（factor>1 放大）。 */
    fun zoomBy(factor: Float) {
        Log.i(TAG, "CarMapControl.zoomBy($factor) presentation=${presentation != null}")
        presentation?.zoomBy(factor)
    }
}

/**
 * 车机地图宿主（**持久化单例**，对齐手机端 MapHolder 模式）：Surface 到来 →
 * [attachSurface] 热换（状态保留）；销毁 → 仅摘除目标 + 暂停渲染，**绝不
 * onDestroy 引擎**（冷重建 4~6s、状态全丢）。真正销毁只经 [destroy]。
 */
class CarMapDisplay(private val context: Context) {

    companion object {
        private var sharedVirtualDisplay: android.hardware.display.VirtualDisplay? = null
        private var sharedPresentation: CarMapPresentation? = null
        private var sharedW = 0
        private var sharedH = 0

        /** 负载门阈值（CPU 利用率 %）与最长等待。 */
        private const val LOAD_GATE_THRESHOLD = 70
        private const val LOAD_GATE_MAX_MS = 20_000L
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSurface: Surface? = null
    private var pendingW = 0
    private var pendingH = 0

    /** 宿主最近上报的 dpi（[start] 传入；VD 创建时经 [sanitizeDensity] 落地）。 */
    @Volatile
    private var pendingDpi: Int = 160

    @Volatile
    var running = false
        private set

    /** 宿主 Surface 就绪（binder 线程调用，必须**毫秒级返回**）：已持久 → 立即热换；
     *  冷启动 → 延迟 8s 建 VD（引擎初始化不得占主线程，否则回调 ANR）。 */
    fun start(carSurface: Surface, width: Int, height: Int, hostDpi: Int) {
        Log.i(
            TAG, "host surface ready ${width}x${height} dpi=$hostDpi " +
                "valid=${carSurface.isValid} warm=${sharedVirtualDisplay != null}"
        )
        pendingSurface = carSurface
        pendingW = width
        pendingH = height
        pendingDpi = hostDpi
        handler.removeCallbacksAndMessages(null)
        if (sharedVirtualDisplay != null && sharedPresentation != null) {
            handler.post { attachSurface(carSurface, width, height) }
        } else {
            handler.postDelayed({
                executor.execute { startInternal() }
            }, START_DELAY_MS)
        }
    }

    /** 热换 Surface：VD 重定向 + 恢复渲染 + 刷新一帧相机/箭头（毫秒级）。 */
    private fun attachSurface(surface: Surface, width: Int, height: Int) {
        val vd = sharedVirtualDisplay ?: return
        if (!surface.isValid) {
            Log.w(TAG, "attach: surface already invalid"); return
        }
        // 宿主多窗口/分屏切换会以新尺寸重新派发 Surface：VD 必须同步 resize，
        // 否则旧的 800x400 内容被合成器拉伸到新表面 = 地图宽度/比例失真
        val sizeChanged = width != sharedW || height != sharedH
        runCatching {
            if (sizeChanged) {
                vd.resize(width, height, vdDensityDpi)
                sharedW = width
                sharedH = height
            }
            vd.setSurface(surface)
        }.onFailure { Log.e(TAG, "surface re-attach failed", it) }
        running = true
        CarMapControl.carSessionActive.value = true
        val metricsChanged = CarMapControl.setSurfaceSize(width, height)
        Log.i(
            TAG, "surface re-attached (persistent map kept" +
                (if (sizeChanged) ", resized to ${width}x${height}" else "") + ")"
        )
        sharedPresentation?.onSurfaceReattached()
        // 尺寸变了（宿主多窗口/分屏切换）：重解相机，箭头重新居中
        if (sizeChanged || metricsChanged) sharedPresentation?.onSurfaceResized()
    }

    /** 负载门：/proc/stat 双采样利用率 < [LOAD_GATE_THRESHOLD] 放行，否则 2s 一拍，
     *  最多 [LOAD_GATE_MAX_MS]（超时仍挂载）。/proc/loadavg 被 SELinux 挡故用利用率。 */
    private fun waitForLoadOrTimeout() {
        val deadline = android.os.SystemClock.elapsedRealtime() + LOAD_GATE_MAX_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val util = readCpuUtilPercent()
            if (util < LOAD_GATE_THRESHOLD) {
                Log.i(TAG, "load gate pass: cpu=${util}%")
                return
            }
            Log.i(TAG, "load gate wait: cpu=${util}%")
            Thread.sleep(2000)
        }
        Log.w(TAG, "load gate timeout (cpu still busy), proceeding")
    }

    /** 双采样 /proc/stat 首行求 200ms 窗口 CPU 利用率（0-100）。 */
    private fun readCpuUtilPercent(): Int = runCatching {
        fun snap(): LongArray =
            java.io.File("/proc/stat").readLines().first()
                .split(Regex("\\s+")).drop(1).take(7).map { it.toLong() }.toLongArray()
        val a = snap()
        Thread.sleep(200)
        val b = snap()
        var total = 0L
        var idle = 0L
        for (i in a.indices) {
            total += b[i] - a[i]
            if (i == 3 || i == 4) idle += b[i] - a[i] // idle + iowait
        }
        if (total <= 0) 0 else ((total - idle) * 100 / total).toInt()
    }.getOrDefault(0)

    private fun startInternal() {
        val carSurface = pendingSurface ?: return
        val width = pendingW
        val height = pendingH
        if (!carSurface.isValid) {
            Log.w(TAG, "host surface gone before start"); return
        }
        // 负载门：冷会话挂 Presentation（引擎初始化占主线程）须避开手机高负载窗口，
        // 否则宿主回调排在初始化后超 5s 时限 = ANR。后台线程等待，不占宿主回调。
        waitForLoadOrTimeout()
        vdDensityDpi = sanitizeDensity(pendingDpi)
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val vd = dm.createVirtualDisplay(
            "amapilot-car", width, height, vdDensityDpi, carSurface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            null, null
        ) ?: run {
            Log.e(TAG, "createVirtualDisplay failed"); return
        }
        sharedVirtualDisplay = vd
        sharedW = width
        sharedH = height
        running = true
        CarMapControl.setSurfaceSize(width, height)
        val display = vd.display
        Log.i(TAG, "virtual display ${display.displayId} created (${width}x${height})")
        // 渲染尺寸取证：VD 密度对齐宿主后引擎地名 = dp 语义随密度缩放；
        // scale 被 2048 GL 上限顶高时缩小地名 trick 失效
        val probeScale = safeMapScale(width, height)
        Log.i(
            TAG, "render probe: vdDensity=$vdDensityDpi hostDpi=$pendingDpi " +
                "scale=$probeScale layout=${(width / probeScale).toInt()}x${(height / probeScale).toInt()} " +
                "routePx=${(ROUTE_WIDTH * densityFactor).toInt()}"
        )
        // Presentation 是外部显示的官方通道（Activity 启动需签名权限，走不通）
        val appContext = context.applicationContext
        handler.post {
            sharedPresentation = CarMapPresentation(appContext, display).also { p ->
                runCatching { p.show() }
                    .onFailure { Log.e(TAG, "presentation show failed", it) }
            }
        }
    }

    /** 宿主 Surface 销毁（binder 线程）：摘除 VD 目标 + 暂停渲染，地图常驻。 */
    fun stop() {
        pendingSurface = null
        handler.removeCallbacksAndMessages(null)
        running = false
        // 会话视觉结束：手机端退出"已连接车机"辅助态（地图本体保留）
        CarMapControl.carSessionActive.value = false
        handler.post {
            runCatching { sharedVirtualDisplay?.setSurface(null) }
            sharedPresentation?.onSurfaceDetached()
            Log.i(TAG, "surface detached (map kept alive)")
        }
    }

    /** 进程级收尾（真正销毁引擎；日常 Surface/会话切换绝不走到这里）。 */
    fun destroy() {
        pendingSurface = null
        handler.removeCallbacksAndMessages(null)
        running = false
        handler.post {
            sharedPresentation?.dismiss()
            sharedPresentation = null
            CarMapControl.presentation = null
            sharedVirtualDisplay?.let { runCatching { it.release() } }
            sharedVirtualDisplay = null
        }
    }
}

private const val TAG = "CarMapDisplay"

/**
 * VirtualDisplay 密度：**跟随宿主上报的 [androidx.car.app.SurfaceContainer.getDpi]**
 * （宿主 chrome 按宿主 dpi 渲染，引擎地名/Compose dp·sp 按 VD 密度渲染，错位 =
 * 全部偏大）；异常值兜底 160。
 * px↔dp 三处边界：① 叠加层 metrics 经 [CarScreenMetrics.scaledBy] ×(160/D) 归一到
 * dp 空间；② 点击命中（[CarMapControl.handleClick]）表面 px ×(160/D) 转 dp 比较
 * （hitRouteLine 例外）；③ 引擎像素类 API（相机/取景/投影）恒表面 px。
 * 线宽/徽标/箭头位图 = dp 语义（设计值 ×[densityFactor] 再 ÷mapViewScale）。
 */
internal var vdDensityDpi: Int = 160
    private set

/** 宿主 dpi → VD 密度（越界/未上报兜底 160 = 旧基准，行为与历史版本一致）。 */
internal fun sanitizeDensity(dpi: Int): Int = if (dpi in 80..640) dpi else 160

/** VD 密度因子：dp → 表面像素（px = dp × D/160；dp = px × 160/D）。 */
internal val densityFactor: Float get() = vdDensityDpi / 160f

/** Surface 就绪 → VD/Activity 启动延迟：让宿主先跑完回调风暴。 */
private const val START_DELAY_MS = 8_000L

/**
 * 车机地图 Presentation：显示在以宿主 Surface 为目标的 VirtualDisplay 上；高德
 * [MapView] + 系统 GPS 定位源，相机跟随/手势经 [CarMapControl] 中转。
 */
/** 冷会话 MapView 延后挂载（错开宿主回调风暴，防主线程排队 ANR）。 */
private const val MAPVIEW_DELAY_MS = 3000L

class CarMapPresentation(outerContext: Context, display: android.view.Display) :
    android.app.Presentation(outerContext, display),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner {

    /** Presentation 视图树无生命周期宿主——Compose 需要手动注入。 */
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController =
        androidx.savedstate.SavedStateRegistryController.create(this)

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var mapView: MapView? = null
    private var aMap: com.amap.api.maps.AMap? = null
    private var composeView: androidx.compose.ui.platform.ComposeView? = null

    /** onMapLoaded 前静默相机/手势（初始化期锁竞争会卡车机模板线程）。 */
    @Volatile
    private var mapLoaded = false

    /** 搜索气泡是否展开（气泡栏；Compose State 驱动重组）。 */
    val searchExpandedState = androidx.compose.runtime.mutableStateOf(false)

    val searchExpanded: Boolean
        get() = searchExpandedState.value

    fun toggleSearch() {
        searchExpandedState.value = !searchExpandedState.value
        if (!searchExpandedState.value) CarSearchRelay.reset()
    }

    /** 底图昼夜样式跟随（车辆态/系统/设置，见 [CarMapControl.resolveNight]）。
     *  解析结果同时落 [CarMapControl.resolvedNight]，叠加层卡片配色同源消费。 */
    private fun applyMapDayNight() {
        val night = CarMapControl.resolveNight(context)
        CarMapControl.resolvedNight.value = night
        runCatching {
            val map = aMap ?: return
            map.mapType = if (night) {
                com.amap.api.maps.AMap.MAP_TYPE_NIGHT
            } else {
                com.amap.api.maps.AMap.MAP_TYPE_NORMAL
            }
        }.onFailure { Log.w(TAG, "applyMapDayNight failed", it) }
    }

    /** 气泡栏行数据：收藏（家/公司/普通）+ 手机输入的结果。 */
    fun searchRows(): List<CarSearchRow> {
        val rows = mutableListOf<CarSearchRow>()
        CarSearchRelay.results.value.take(4).forEach {
            rows.add(CarSearchRow(it.name, it.address, it.lat, it.lng))
        }
        val favs = com.amapilot.data.FavoriteRepository(context).load()
        favs.take(4).forEach { f ->
            val label = when (f.kind) {
                com.amapilot.data.FavoritePlace.Kind.HOME -> "家 · ${f.name}"
                com.amapilot.data.FavoritePlace.Kind.WORK -> "公司 · ${f.name}"
                else -> f.name
            }
            rows.add(CarSearchRow(label, f.address, f.lat, f.lng))
        }
        return rows
    }

    /** 从气泡栏行发起导航（巡航中先退出巡航）。 */
    fun startNavigationFromRow(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        CarRouteStarter.start(context, com.amap.api.maps.model.LatLng(lat, lng))
        searchExpandedState.value = false
        CarSearchRelay.reset()
    }

    /** 相机跟随开关：用户拖动后关闭，点定位按钮恢复。 */
    @Volatile
    private var follow = true

    @Volatile
    private var lastLat = 0.0
    @Volatile
    private var lastLng = 0.0

    /** 按键事件探针：真车旋钮可能以 KeyEvent 进入自有窗口（DHU dpad 走宿主路径，
     *  不代表真旋钮）；收到即日志，供取证后决定是否实现自绘焦点遍历。 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        android.util.Log.i(
            "CarMapDisplay",
            "KEY probe: code=${event.keyCode} action=${event.action} " +
                "src=${event.source} repeat=${event.repeatCount}"
        )
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        // 注入视图树生命周期宿主（Presentation 自身不提供，Compose 必需）
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            // 尺寸安全网：宿主未重派 onSurfaceAvailable 就改尺寸时从视图树布局感知
            decor.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
                val w = r - l
                val h = b - t
                if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
                if (w == orr - ol && h == ob - ot) return@addOnLayoutChangeListener
                Log.i(TAG, "presentation layout ${orr - ol}x${ob - ot} -> ${w}x${h}")
                if (CarMapControl.setSurfaceSize(w, h)) onSurfaceResized()
            }
        }
        // 先挂空容器：MapView 初始化（引擎 load）是主线程重活，过载时会把宿主回调
        // 排到 5s 时限外 = ANR。延后 MAPVIEW_DELAY_MS 让回调风暴先过
        val root = android.widget.FrameLayout(context)
        setContentView(root)
        CarMapControl.presentation = this
        CarMapControl.carSessionActive.value = true
        Log.i(
            TAG, "CarMapPresentation created @${System.identityHashCode(this)} " +
                "(car virtual display, night style; mapview deferred)"
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!deferredMapDead) initMapView(savedInstanceState, root)
        }, MAPVIEW_DELAY_MS)
    }

    /** Presentation dismiss 后延迟任务不得再挂 MapView。 */
    private var deferredMapDead = false

    /** 延后挂载 AMap MapView（主线程重活，错开宿主回调风暴）。 */
    private fun initMapView(
        savedInstanceState: Bundle?,
        root: android.widget.FrameLayout,
    ) {
        val view = MapView(
            context,
            com.amap.api.maps.AMapOptions()
                // 高德 logo 为合规水印，无 API 隐藏，仅可换位：移出左下卡片区
                .logoPosition(com.amap.api.maps.AMapOptions.LOGO_POSITION_BOTTOM_RIGHT)
        )
        mapView = view
        // 必须在 Presentation 窗口 attach 后再 onCreate：MapHolder.acquire 的首建在
        // show() 之前（无 window），真机 VD 上引擎不会 load；二次 onCreate 有意保留
        view.onCreate(savedInstanceState)
        // 高德 GLSurfaceView 可能置顶盖住叠加层：遍历内部 SurfaceView 强制置底
        forceSurfaceBelow(view)
        // 模拟器（VD + 软件 GL）：dirty-render 信号丢失会首帧后冻帧 → 仅模拟器切连续渲染
        if (CarRouteStarter.isEmulatorDevice()) forceContinuousRender(view)

        val map = view.map
        aMap = map
        // 同步渲染暂停态：共享 MapView 可能被上一 Presentation/手机前台暂停留在
        // onPause；新实例 rendering 初值 true 会让 resumeRendering 空跳 = 永久黑屏
        rendering = false
        if (CarMapControl.phoneForeground) pauseRendering() else resumeRendering()
        map.setOnCameraChangeListener(object : com.amap.api.maps.AMap.OnCameraChangeListener {
            override fun onCameraChange(p: com.amap.api.maps.model.CameraPosition?) {
                CarMapControl.cameraTick.value++
            }

            override fun onCameraChangeFinish(p: com.amap.api.maps.model.CameraPosition?) {
                CarMapControl.cameraTick.value++
            }
        })
        map.setOnMapLoadedListener {
            mapLoaded = true
            Log.i(TAG, "AMap engine loaded (car display)")
            // 取景中心对齐可见区（副窗口态下不是表面中心）
            applyViewportCenter(CarMapControl.metrics.value)
            // 首帧定位：会话位置/系统 lastKnown(GCJ) 兜底，消灭"先北京默认中心再跳回"
            val seed = bestKnownPosition()
            if (seed != null) {
                moveCamera(seed.latitude, seed.longitude, 0f)
            }
            redrawRoute()
            // Surface 重建后覆盖物被清：选路仍开着则从控制层缓存重绘备选线/徽标
            CarMapControl.routeSelect.value?.let { rows ->
                // select 先于地图加载到达时 enterRouteSelect 早退、selPaths 未填充：
                // 空缓存回退会话层路线数据（预导航=规划结果；导航中=引擎 naviPaths）
                val paths = CarMapControl.selPaths.ifEmpty {
                    com.amapilot.navigation.NaviSession.routePaths.value.orEmpty()
                }
                if (paths.isNotEmpty()) {
                    enterRouteSelect(
                        paths,
                        CarMapControl.selSelectedId
                            ?: rows.firstOrNull { it.current }?.pathId,
                        rows
                    )
                }
            }
            // 引擎就绪 2s 后再挂 UI 叠加层（错峰主线程重活，防宿主回调 ANR）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ attachOverlay() }, 2000)
        }
        // 路线/途经点数据流 → 重绘（发起导航/切路线/加途经点/取消 全由 flow 驱动）；
        // 收集器防猝死：裸抛会永久终止 collect（逐次兜底 + 日志）
        scope.launch {
            NaviSession.routePoints.collect {
                runCatching { redrawRoute() }
                    .onFailure { Log.e(TAG, "redrawRoute failed", it) }
            }
        }
        scope.launch {
            NaviSession.waypoints.collect {
                runCatching { redrawEndpoints() }
                    .onFailure { Log.e(TAG, "redrawEndpoints failed", it) }
            }
        }
        // 引擎备选路线数据更新（onNaviRouteNotify/重算）→ 选路面板与灰线实时刷新
        scope.launch {
            NaviSession.routeOptions.collect { CarMapControl.refreshRouteSelectView() }
        }
        // 底图昼夜：车连跟车辆态，未连回退手机系统；设置页可覆盖（[CarMapControl.resolveNight]）
        applyMapDayNight()
        scope.launch { CarMapControl.carDarkMode.collect { applyMapDayNight() } }
        scope.launch { CarMapControl.dayNightTick.collect { applyMapDayNight() } }
        // 实时路况层（与手机端 MapScreen/TbtScreen/CruiseScreen 一致）
        runCatching { map.isTrafficEnabled = true }
        // 关闭高德 SDK 自带 UI 控件（缩放/定位/指南针/比例尺），logo 保留（合规）
        runCatching {
            map.uiSettings.isZoomControlsEnabled = false
            map.uiSettings.isMyLocationButtonEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isScaleControlsEnabled = false
        }
        runCatching { map.setLocationSource(systemGpsSource(context)) }
        // SDK 图标/精度圈透明化：车机与手机同款自绘蓝箭头（车头朝上由相机 bearing 旋转）
        map.myLocationStyle = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
            .myLocationIcon(com.amapilot.navigation.transparentDescriptor(context))
            .radiusFillColor(android.graphics.Color.TRANSPARENT)
            .strokeColor(android.graphics.Color.TRANSPARENT)
            .strokeWidth(0f)
        map.isMyLocationEnabled = true
        // 空闲态箭头由 SDK 定位层驱动，导航/巡航由 ticker 接管。跳变过滤：陈旧缓存/
        // 网络定位与实时 GPS 交错会 km 级回跳；>300m 且 <10s 丢弃，连丢 3 次放行
        map.setOnMyLocationChangeListener { loc ->
            if (loc != null &&
                !NaviSession.isActive &&
                !com.amapilot.navigation.CruiseSession.isActive &&
                // 预导航选路态：空闲 GPS 锚定会每 10s 把全览取景拽回车位街道级
                // （打断 refit 动画 = 选路屏无全览/无路线图形）
                CarMapControl.routeSelect.value == null
            ) {
                val prev = lastIdleFix
                if (prev != null) {
                    val dt = loc.time - prev.time
                    val dist = prev.distanceTo(loc).toDouble()
                    if (dt in 0..10_000L && dist > 300.0 && idleSkipStreak < 3) {
                        idleSkipStreak++
                        Log.i(
                            TAG, "idle fix skipped: jump ${dist.toInt()}m " +
                                "dt=${dt}ms (stale/network provider)"
                        )
                        return@setOnMyLocationChangeListener
                    }
                }
                idleSkipStreak = 0
                lastIdleFix = loc
                Log.i(
                    TAG, "idle fix: (${loc.latitude}, ${loc.longitude}) " +
                        "bearing=${loc.bearing} speed=${loc.speed}"
                )
                moveCamera(loc.latitude, loc.longitude, loc.bearing)
            }
        }
        map.moveCamera(CameraUpdateFactory.zoomTo(14.5f))

        // MapView 几何缩放（[mapViewScale]）：1/SCALE 尺寸布局 + pivot 0,0 缩放，
        // 底图地名字体等比缩小到接近卡片字号；GL 表面填充开销 1/SCALE²
        val dm = context.resources.displayMetrics
        val scaled = dm.widthPixels > 0 && dm.heightPixels > 0
        if (scaled) {
            mapViewScale = safeMapScale(dm.widthPixels, dm.heightPixels)
            view.pivotX = 0f
            view.pivotY = 0f
            view.scaleX = mapViewScale
            view.scaleY = mapViewScale
        }
        root.apply {
            addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    if (scaled) (dm.widthPixels / mapViewScale).toInt() else -1,
                    if (scaled) (dm.heightPixels / mapViewScale).toInt() else -1
                )
            )
        }
        // 叠加层与引擎加载解耦：真机 VD 上 onMapLoaded 可能分钟级才到，门控其上会让
        // 整屏黑到引擎就绪；创建后 1.5s 先挂 overlay，onMapLoaded 路径作兜底（幂等）
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ attachOverlay() }, 1500)
    }

    /** onMapLoaded 后 2s 挂载 Compose 叠加层（错峰主线程重活，防宿主回调 ANR）。 */
    private fun attachOverlay() {
        if (composeView != null) return
        val compose = androidx.compose.ui.platform.ComposeView(context).apply {
            setContent {
                val navi by com.amapilot.navigation.NaviSession.state.collectAsState()
                val cruise by com.amapilot.navigation.CruiseSession.state.collectAsState()
                val lanes by com.amapilot.navigation.NaviSession.lanes.collectAsState()
                // 激活态用专用流：state 流同值合流会让卡片迟到（start/stop 即时发射）
                val naviActive by com.amapilot.navigation.NaviSession.activeFlow.collectAsState()
                val cruiseActive by com.amapilot.navigation.CruiseSession.activeFlow.collectAsState()
                MaterialTheme {
                    CarMapOverlay(
                        navi = navi.takeIf { naviActive },
                        cruise = cruise.takeIf { cruiseActive },
                        lanes = lanes,
                        // Compose State 读取即订阅：宿主改窗口尺寸自动重排。px→dp
                        // 归一（VD 密度对齐）：几何函数输出 dp 空间，与宿主 chrome 同基准
                        metrics = CarMapControl.metrics.value.scaledBy(160f / vdDensityDpi),
                    )
                }
            }
        }
        composeView = compose
        (compose.parent as? android.view.ViewGroup)?.removeView(compose)
        (mapView?.parent as? android.view.ViewGroup)?.addView(
            compose, android.widget.FrameLayout.LayoutParams(-1, -1)
        )
        Log.i(TAG, "overlay attached")
    }

    /** 投影通道暴露给选路自绘层（CarMapControl.projectToScreen）。 */
    fun aMapForProject(): com.amap.api.maps.AMap? = aMap

    /** 遍历视图树，把内部 SurfaceView 的 z-order 全部压回窗口之下。 */
    private fun forceSurfaceBelow(v: android.view.View) {
        if (v is android.view.SurfaceView) {
            runCatching {
                v.setZOrderOnTop(false)
                v.setZOrderMediaOverlay(false)
            }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) forceSurfaceBelow(v.getChildAt(i))
        }
    }

    /** 递归找高德内部 GLSurfaceView 切连续渲染（模拟器 VD 冻帧对策）。 */
    private fun forceContinuousRender(v: android.view.View) {
        if (v is android.opengl.GLSurfaceView) {
            runCatching {
                v.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY)
            }.onSuccess {
                Log.i(TAG, "GLSurfaceView -> RENDERMODE_CONTINUOUSLY (emulator VD workaround)")
            }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) forceContinuousRender(v.getChildAt(i))
        }
    }

    override fun onStop() {
        // Surface 失效/显示切换时 Presentation 自动走 dismiss 流程
        super.onStop()
    }

    override fun onDetachedFromWindow() {
        if (CarMapControl.presentation === this) CarMapControl.presentation = null
        super.onDetachedFromWindow()
    }

    override fun dismiss() {
        deferredMapDead = true
        if (CarMapControl.presentation === this) {
            CarMapControl.presentation = null
            CarMapControl.carSessionActive.value = false
        }
        runCatching {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
            savedStateRegistryController.performSave(android.os.Bundle())
        }
        composeView = null
        scope.cancel()
        routePolyline = null
        passedPolyline = null
        trafficPolyline = null
        routePts = emptyList()
        cumFromEnd = DoubleArray(0)
        routeLats = DoubleArray(0)
        routeLngs = DoubleArray(0)
        lastEffPosCum = -1.0
        destMarker = null
        waypointMarkers = emptyList()
        arrowMarker = null
        altLines.clear()
        altLinePoints = emptyMap()
        hlLine = null
        routeBubbles.clear()
        selPaths = emptyMap()
        // 共享 MapHolder 单例：离屏绝不可 onDestroy（引擎死后下一个 Presentation
        // 拿到死 MapView = 黑屏）——仅暂停渲染，覆盖物由 MapHolder.reset 清
        runCatching { mapView?.onPause() }
        mapView = null
        aMap = null
        Log.i(TAG, "CarMapPresentation dismissed @${System.identityHashCode(this)}")
        super.dismiss()
    }

    // ── 路线绘制 + 3D 跟随相机（对齐手机端 TbtScreen 实现） ──

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var routePolyline: Polyline? = null
    private var destMarker: Marker? = null
    private var waypointMarkers: List<Marker> = emptyList()

    /** 路线线 + 终点/途经点标记（flow 驱动；取消导航后 routePoints 置 null 自动清除）。 */
    private fun redrawRoute() {
        val a = aMap ?: return
        routePolyline?.remove()
        routePolyline = null
        val points = if (NaviSession.isActive) NaviSession.routePoints.value.orEmpty() else emptyList()
        routePts = points
        lastGrayRemain = 0
        lastGrayTickMs = 0L
        lastGrayDrawCum = -1.0
        lastGrayDrawMs = 0L
        grayRemain0 = 0
        grayTotal0 = 0.0
        cumFromEnd = buildCumFromEnd(points)
        routeLats = DoubleArray(points.size) { points[it].latitude }
        routeLngs = DoubleArray(points.size) { points[it].longitude }
        lastEffPosCum = -1.0
        runCatching { passedPolyline?.remove() }
        passedPolyline = null
        runCatching { trafficPolyline?.remove() }
        trafficPolyline = null
        if (points.isNotEmpty()) {
            // 蓝色主线（底，z10）：路线是主信息；加兜底防单次异常杀死收集器
            routePolyline = runCatching {
                a.addPolyline(
                    PolylineOptions().addAll(points).width(glw(ROUTE_WIDTH))
                        .color(ROUTE_COLOR).zIndex(10f)
                        // 圆角连接/端帽：与灰线完全一致（同宽同几何同 join → 转角处灰线精确覆盖蓝线）
                        .lineJoinType(PolylineOptions.LineJoinType.LineJoinRound)
                        .lineCapType(PolylineOptions.LineCapType.LineCapRound)
                )
            }.onFailure { Log.e(TAG, "route polyline add failed", it) }.getOrNull()
            // 路况细彩线（主线中央，z11；辅助信息，未知段同蓝隐形）
            buildTrafficColors(points)?.let { colors ->
                trafficPolyline = runCatching {
                    a.addPolyline(
                        PolylineOptions().addAll(points).width(glw(TRAFFIC_WIDTH))
                            .zIndex(11f).colorValues(colors)
                            // 圆角连接/端帽：转角不伸出灰线覆盖范围（灰线更宽且同 join）
                            .lineJoinType(PolylineOptions.LineJoinType.LineJoinRound)
                            .lineCapType(PolylineOptions.LineCapType.LineCapRound)
                    )
                }.getOrNull()
            }
        }
        Log.i(TAG, "route redrawn: ${points.size} points, active=${NaviSession.isActive}")
        if (!NaviSession.isActive && !CarMapControl.preNavSelectMode) {
            // 导航结束：选路/全览/灰线一并清除。preNavSelectMode 必须豁免——预导航
            // 窗口期 isActive 同样为 false，地图晚加载的 redrawRoute 会清掉刚打开的选路
            clearAltLines()
            if (CarMapControl.routeSelect.value != null) CarMapControl.routeSelect.value = null
            CarMapControl.overviewActive.value = false
        }
        redrawEndpoints()
    }

    private fun redrawEndpoints() {
        val a = aMap ?: return
        destMarker?.remove()
        destMarker = null
        waypointMarkers.forEach { it.remove() }
        waypointMarkers = emptyList()
        if (!NaviSession.isActive) return
        destMarker = NaviSession.destination?.let {
            a.addMarker(
                MarkerOptions().position(it)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    // 抬到路线线（z10~12）上方，pin 底座不被线盖住
                    .zIndex(30f)
            )
        }
        waypointMarkers = NaviSession.waypoints.value.map { wp ->
            a.addMarker(
                MarkerOptions().position(LatLng(wp.lat, wp.lng))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .zIndex(31f)
            )
        }
    }

    /** 自绘蓝箭头（与手机 TBT/巡航同款 NavigationIcons.arrowDescriptor）。 */
    private var arrowMarker: Marker? = null
    private var passedPolyline: Polyline? = null
    private var routePts: List<LatLng> = emptyList()
    private var lastGrayRemain = 0
    private var lastGrayTickMs = 0L
    // 灰线重绘节流账本（见 GRAY_REDRAW_ADVANCE_M）：-1 = 尚未画过（必画）
    private var lastGrayDrawCum = -1.0
    private var lastGrayDrawMs = 0L
    // traveled-based 边界基准：首帧锁存（引擎 t=0 remain 比几何总长短 ~300m）
    private var grayRemain0 = 0
    private var grayTotal0 = 0.0
    private var cumFromEnd: DoubleArray = DoubleArray(0)
    // GPS 投影主通道（灰线随进度连续推进；引擎 remain 离散台阶 = "一段一段跳"根因）
    private var routeLats: DoubleArray = DoubleArray(0)
    private var routeLngs: DoubleArray = DoubleArray(0)
    private var lastEffPosCum = -1.0
    private val bearingHist = ArrayDeque<Float>()
    private var lastLoggedBearing = -999f
    private var lastIdleFix: android.location.Location? = null
    private var idleSkipStreak = 0
    private val arrowIcon by lazy {
        // 车机 MapView 几何缩放（mapViewScale=0.6）补偿：位图放大 1/SCALE 保持屏上尺寸
        com.amapilot.navigation.arrowDescriptor(context, densityFactor / mapViewScale)
    }

    private var lastPosTimeMs = 0L
    private var posSkipStreak = 0
    private var lastSpeedKmh = 0f
    private var lastManeuverRemain = Float.MAX_VALUE

    /** 最近解算航向（中值滤波+平滑后）。Surface 重挂后要用它重解相机——传 0 会把
     *  视角甩回正北，箭头朝向跟着错。 */
    private var lastHeading = 0f

    /** 最近一拍已下发相机的目标（稳态短路：全部未变则不重启动画/不动箭头）。 */
    private var issuedTgtLat = 0.0
    private var issuedTgtLng = 0.0
    private var issuedZoom = -99f
    private var issuedBearing = -999f
    private var issuedNorthUp = false
    private var issuedTilt = -1f

    /** 最佳已知位置：会话位置（导航/巡航）优先，系统 lastKnown(GCJ) 兜底。 */
    private fun bestKnownPosition(): LatLng? {
        val nav = NaviSession.location.value?.coord
        if (nav != null) return LatLng(nav.latitude, nav.longitude)
        val cruise = com.amapilot.navigation.CruiseSession.location.value?.coord
        if (cruise != null) return LatLng(cruise.latitude, cruise.longitude)
        return runCatching {
            com.amapilot.map.SystemLocations.lastKnownGcj(context)
        }.getOrNull()
    }
    private var lastLoggedZoom = -99f
    private var lastLoggedTilt = -1f
    private var manualZoomUntilMs = 0L

    /**
     * 更新蓝箭头（flat 贴地，随俯仰透视压缩）。AMap flat Marker 的屏幕角 =
     * rotateAngle **+** cameraBearing（加法语义，与文档相反；**勿改回减法**，否则
     * 箭头指向反转）。期望屏幕角 = heading − cameraBearing，故 rotateAngle =
     * heading − 2 × cameraBearing。
     */
    private fun updateArrow(
        a: com.amap.api.maps.AMap,
        latitude: Double,
        longitude: Double,
        heading: Float,
        cameraBearing: Float,
    ) {
        val markerAngle =
            ((heading - 2f * cameraBearing) % 360f + 360f) % 360f
        val pos = LatLng(latitude, longitude)
        val m = arrowMarker
        if (m == null) {
            arrowMarker = a.addMarker(
                MarkerOptions().position(pos).icon(arrowIcon)
                    .anchor(0.5f, 0.5f)
                    .setFlat(true)
                    .rotateAngle(markerAngle)
                    // v11 按 zIndex 排序：路线/路况/灰线 10~12，marker 默认更低会被压住
                    .zIndex(999f)
            )
            return
        }
        m.rotateAngle = markerAngle
        // 位置：引擎侧 **Linear** 插值（marker 可选插值器，animateCamera 不可）。
        // 链式衔接：帧 N 目标即帧 N+1 起点，消 250ms 台阶；异常回退直设。
        runCatching {
            val anim = com.amap.api.maps.model.animation.TranslateAnimation(pos)
            anim.setDuration(ARROW_ANIM_MS)
            anim.setInterpolator(android.view.animation.LinearInterpolator())
            m.setAnimation(anim)
            m.startAnimation()
        }.onFailure {
            m.position = pos
        }
    }

    /** 速度 + 机动距离 → 目标缩放：单一来源 [com.amapilot.navigation.AutoZoom.target]。 */
    private fun autoZoomFor(speedKmh: Float, maneuverRemainMeters: Float): Float =
        com.amapilot.navigation.AutoZoom.target(speedKmh, maneuverRemainMeters)

    /** 相机跟随（3D 车头向上；缩放 = 速度/机动自适应，手动缩放后保持 15s）。 */
    fun moveCamera(
        latitude: Double,
        longitude: Double,
        bearing: Float = 0f,
        speedKmh: Float = 0f,
        /** 距下一机动米数（导航态；巡航/无机动省略 = 纯速度曲线）。 */
        maneuverRemainMeters: Float = Float.MAX_VALUE,
        /** >0 = 用动画过渡到目标取景（尺寸变化时用；常规跟随为 0 = 瞬移）。 */
        smoothMs: Long = 0L,
    ) {
        // 跳变过滤（全来源）：多 provider 与缓存/网络定位交错会造成 km 级回跳
        // （箭头瞬移 + 航向翻转）；10s 内 >300m 丢弃，连丢 3 次放行真实重定位
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastLat != 0.0 && lastPosTimeMs != 0L) {
            val dt = now - lastPosTimeMs
            val mLat = (latitude - lastLat) * 111_320.0
            val mLng = (longitude - lastLng) * 111_320.0 *
                kotlin.math.cos(Math.toRadians(latitude))
            val dist = kotlin.math.hypot(mLat, mLng)
            if (dt in 0..10_000L && dist > 300.0 && posSkipStreak < 3) {
                posSkipStreak++
                Log.i(TAG, "pos fix skipped: jump ${dist.toInt()}m dt=${dt}ms")
                return
            }
        }
        posSkipStreak = 0
        lastPosTimeMs = now
        lastSpeedKmh = speedKmh
        lastManeuverRemain = maneuverRemainMeters
        // 上一次位置（航向兜底用位移推算，须在覆盖前读取）
        val prevLat = lastLat
        val prevLng = lastLng
        lastLat = latitude
        lastLng = longitude
        if (!mapLoaded) return
        val a = aMap ?: return
        // 取景中心跟随 UI 态：窄屏箭头要避开左侧卡片列（内部按 lastCenter 去重）
        applyViewportCenter(CarMapControl.metrics.value)
        val cam = a.cameraPosition
        // 航向目标：bearing（2s 中值滤波去尖峰）→ 位移推算（>15m）→ **保持最近解算
        // 航向**（sticky）。兜底勿用 cam.bearing：北向上/全览态为 0，停车后箭头会甩向正北。
        bearingHist.addLast(bearing)
        // 窗口 8 样本 = 2s：更长窗口在持续弯道滞后（出弯甩尾）；仍需 4+ 并发尖峰才被击穿
        while (bearingHist.size > 8) bearingHist.removeFirst()
        val valid = bearingHist.filter { it in 0.1f..359.9f }
        val bearingF = if (valid.isNotEmpty()) {
            valid.sorted()[valid.size / 2]
        } else {
            null
        }
        val wantB = if (bearingF != null) {
            bearingF
        } else {
            val mLat = (latitude - prevLat) * 111_320.0
            val mLng = (longitude - prevLng) * 111_320.0 *
                kotlin.math.cos(Math.toRadians(latitude))
            if (prevLat != 0.0 && kotlin.math.hypot(mLat, mLng) > 15.0) {
                val brg = Math.toDegrees(kotlin.math.atan2(mLng, mLat)).toFloat()
                ((brg % 360f) + 360f) % 360f
            } else {
                lastHeading
            }
        }
        lastHeading = wantB
        updatePassedRoute(latitude, longitude)
        // 北向上模式：bearing 恒 0、tilt 0（2D 正北），位置仍跟随
        val northUp = CarMapControl.cameraMode.value == CarMapControl.CameraMode.NORTH
        if (!follow) {
            // 北向上视角（全览/选路/手拖压平）：相机 bearing 即当前值
            updateArrow(a, latitude, longitude, wantB, cam.bearing)
            return
        }
        // 缩放：手动保持期用当前值，否则向目标平滑逼近（单一来源 AutoZoom，步长 STEP/4）
        val zoom = if (android.os.SystemClock.elapsedRealtime() < manualZoomUntilMs) {
            cam.zoom
        } else {
            com.amapilot.navigation.AutoZoom.approach(
                cam.zoom, autoZoomFor(speedKmh, maneuverRemainMeters),
                com.amapilot.navigation.AutoZoom.STEP / 4f
            )
        }
        // 航向平滑逼近（最短角路径 ≤5°/帧 = 1s 最多 20°）；差 >60° 直接吸附；北向上恒 0
        var db = (wantB - cam.bearing) % 360f
        if (db > 180f) db -= 360f
        if (db < -180f) db += 360f
        val b = if (northUp) {
            0f
        } else if (kotlin.math.abs(db) > 60f) {
            wantB
        } else {
            (cam.bearing + db.coerceIn(-BEARING_STEP, BEARING_STEP) + 360f) % 360f
        }
        // 俯仰：amapilot_car_tilt 基准 + 低速临近机动的坡道上抬（见 maneuverLiftTilt）
        val tilt = if (northUp) 0f else maneuverLiftTilt(speedKmh, maneuverRemainMeters)
        if (kotlin.math.abs(b - lastLoggedBearing) > 8f ||
            kotlin.math.abs(zoom - lastLoggedZoom) > 0.4f ||
            kotlin.math.abs(tilt - lastLoggedTilt) > 1f
        ) {
            lastLoggedBearing = b
            lastLoggedZoom = zoom
            lastLoggedTilt = tilt
            Log.i(TAG, "camera: v=${speedKmh.toInt()}km/h zoom=$zoom tilt=$tilt heading=$b")
        }
        val rad = Math.toRadians(b.toDouble())
        // 前推距离 = 恒定屏幕像素 × 当前级别的米/像素（Web Mercator @160dpi）
        val mPerPx = 156543.03392 *
            kotlin.math.cos(Math.toRadians(latitude)) /
            Math.pow(2.0, zoom.toDouble())
        val offsetDeg = CAR_AHEAD_SCREEN_PX * mPerPx / 111_320.0
        val aheadLat = latitude + offsetDeg * kotlin.math.cos(rad)
        val aheadLng = longitude +
            offsetDeg / kotlin.math.cos(Math.toRadians(latitude)) * kotlin.math.sin(rad)
        // 稳态短路：目标位/zoom/bearing/朝向/俯仰全未变 = 重复 animateCamera 只空转重启
        val settled = android.os.SystemClock.elapsedRealtime() >= viewportSettleUntilMs
        if (arrowMarker != null && settled && smoothMs <= 0L &&
            kotlin.math.abs(aheadLat - issuedTgtLat) < 1e-7 &&
            kotlin.math.abs(aheadLng - issuedTgtLng) < 1e-7 &&
            kotlin.math.abs(zoom - issuedZoom) < 0.01f &&
            kotlin.math.abs(b - issuedBearing) < 0.05f &&
            northUp == issuedNorthUp &&
            kotlin.math.abs(tilt - issuedTilt) < 0.01f
        ) {
            return
        }
        issuedTgtLat = aheadLat
        issuedTgtLng = aheadLng
        issuedZoom = zoom
        issuedBearing = b
        issuedNorthUp = northUp
        issuedTilt = tilt
        // 跟随态：用平滑后的相机 bearing 解算箭头角
        updateArrow(a, latitude, longitude, wantB, b)
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition(LatLng(aheadLat, aheadLng), zoom, tilt, b)
        )
        // smoothMs>0：尺寸变化后平滑过渡；窗口内的常规跟随帧也走动画（否则下一帧会
        // 打断）。稳态走 FOLLOW_ANIM_MS(=帧周期)，amapilot_follow_anim_ms 可覆盖
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val effectiveSmooth = when {
            smoothMs > 0L -> smoothMs
            nowMs < viewportSettleUntilMs -> CarUiGeometry.RESIZE_ANIM_MS.toLong()
            else -> followAnimMs()
        }
        a.animateCamera(update, effectiveSmooth, null)
    }

    private var lastTrafficHash = 0
    private var trafficPolyline: Polyline? = null

    /** 逐段路况色表（未知段=主线蓝，视觉隐形）；无路况数据返回 null。 */
    private fun buildTrafficColors(points: List<LatLng>): List<Int>? {
        val traffic = NaviSession.routeTraffic.value
        val starts = NaviSession.routeLinkStarts.value
        if (traffic.isEmpty() || points.size < 2 || starts.size < 2) return null
        // 每 link 的路况：traffic 段按 linkIndex 升序给出（"自该 link 起 status"）
        val linkStatus = IntArray(starts.size - 1)
        var ti = 0
        var cur = 0
        for (li in linkStatus.indices) {
            while (ti < traffic.size && traffic[ti].linkIndex <= li) {
                cur = traffic[ti].status
                ti++
            }
            linkStatus[li] = cur
        }
        // 坐标点 → link（starts 为缩放后坐标起点，末元素=points.size 哨兵）
        val colors = ArrayList<Int>(points.size - 1)
        var li = 0
        for (i in 0 until points.size - 1) {
            while (li + 1 < linkStatus.size && starts[li + 1] <= i) li++
            colors.add(trafficColorFor(linkStatus[li]))
        }
        lastTrafficHash = colors.hashCode()
        return colors
    }

    /** 路况变化检测（1Hz 轻扫；仅哈希变化才重建**细彩线**，主线不动）。 */
    private fun refreshTrafficColorsIfNeeded() {
        if (routePts.size < 2 || !NaviSession.isActive) return
        val colors = buildTrafficColors(routePts) ?: return
        if (colors.hashCode() == lastTrafficHash && trafficPolyline != null) return
        val a = aMap ?: return
        Log.i(TAG, "traffic colors refreshed")
        runCatching { trafficPolyline?.remove() }
        trafficPolyline = runCatching {
            a.addPolyline(
                PolylineOptions().addAll(routePts).width(glw(TRAFFIC_WIDTH))
                    .zIndex(11f).colorValues(colors)
            )
        }.getOrNull()
    }

    /** 路线各点到终点的弧长（米），与 routePts 同长；弧长切分用。 */
    private fun buildCumFromEnd(pts: List<LatLng>): DoubleArray {
        val n = pts.size
        val arr = DoubleArray(n)
        var acc = 0.0
        for (i in n - 2 downTo 0) {
            val dLat = (pts[i + 1].latitude - pts[i].latitude) * 111_320.0
            val dLng = (pts[i + 1].longitude - pts[i].longitude) * 111_320.0 *
                kotlin.math.cos(Math.toRadians((pts[i].latitude + pts[i + 1].latitude) / 2))
            acc += kotlin.math.hypot(dLat, dLng)
            arr[i] = acc
        }
        return arr
    }

    /**
     * 已走过路段置灰：**GPS 位置窗口化投影 → 弧长边界**（主）+ 引擎剩余里程
     * traveled-based 换算（fallback），统一单调棘轮收尾。
     *
     * 投影沿上一拍边界 ±[GRAY_PROJ_WINDOW_M] 弧长窗口搜索——全局最近点在掉头处
     * 有歧义（会吸到对向段、灰线提前盖住回程）；偏离路线退回引擎 remain。
     */
    private fun updatePassedRoute(latitude: Double, longitude: Double) {
        if (routePts.size < 2 || !NaviSession.isActive) return
        val a = aMap ?: return
        refreshTrafficColorsIfNeeded()
        if (cumFromEnd.size != routePts.size || cumFromEnd[0] <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (lastGrayTickMs > 0) (now - lastGrayTickMs) / 1000.0 else 0.0
        lastGrayTickMs = now
        val proj = projectOnRoute(
            routeLats, routeLngs, cumFromEnd, latitude, longitude,
            lastCum = lastEffPosCum
        )
        val raw: Double
        val src: String
        if (proj != null && proj.second <= GRAY_PROJ_MAX_OFFSET_M) {
            raw = proj.first
            src = "proj(off=${proj.second.toInt()}m)"
        } else {
            raw = engineGrayBoundary(dt) ?: return
            src = "engine(remain=${NaviSession.state.value.routeRemainMeters}m)"
        }
        val effPos = ratchetBoundary(lastEffPosCum, raw, dt)
        lastEffPosCum = effPos
        // 节流：棘轮状态已更新，几何重绘推迟到推进 ≥1m 或距上次 ≥1s
        if (lastGrayDrawCum >= 0.0 &&
            lastGrayDrawCum - effPos < GRAY_REDRAW_ADVANCE_M &&
            now - lastGrayDrawMs < 1_000L
        ) return
        lastGrayDrawCum = effPos
        lastGrayDrawMs = now
        // 诊断（实车灰线取证）：边界/来源/总长
        Log.d(
            TAG, "gray effPos=${effPos.toInt()}m src=$src " +
                "total=${cumFromEnd[0].toInt()}m remain0=$grayRemain0"
        )
        // 边界 = 弧长插值点（跨过 effPos 的两顶点间线性插值），随剩余里程连续移动
        var idx = 0
        while (idx < routePts.size && cumFromEnd[idx] >= effPos) idx++
        idx = idx.coerceIn(1, routePts.size - 1)
        val cHi = cumFromEnd[idx - 1]
        val cLo = cumFromEnd[idx]
        val t = if (cHi > cLo) ((cHi - effPos) / (cHi - cLo)).coerceIn(0.0, 1.0) else 1.0
        val p0 = routePts[idx - 1]
        val p1 = routePts[idx]
        val boundary = LatLng(
            p0.latitude + (p1.latitude - p0.latitude) * t,
            p0.longitude + (p1.longitude - p0.longitude) * t
        )
        val grayPts = ArrayList<LatLng>(idx + 1)
        grayPts.addAll(routePts.subList(0, idx))
        // 退化边界：插值点距顶点过近会产生零长尾段，join 退化使蓝线转角从灰线下露出
        if (t >= BOUNDARY_SNAP_EPS) grayPts.add(boundary)
        val pl = passedPolyline
        if (pl == null) {
            passedPolyline = runCatching {
                a.addPolyline(
                    PolylineOptions().addAll(grayPts).width(glw(PASSED_WIDTH))
                        .color(PASSED_ROUTE_COLOR).zIndex(12f)
                        // 圆角连接/端帽：与主线完全一致（同宽同几何同 join → 精确覆盖）
                        .lineJoinType(PolylineOptions.LineJoinType.LineJoinRound)
                        .lineCapType(PolylineOptions.LineCapType.LineCapRound)
                )
            }.getOrNull()
        } else {
            // 原地换点（1Hz 连续推进，无 remove+add 闪烁）
            runCatching { pl.setPoints(grayPts) }
        }
    }

    /**
     * 引擎剩余里程 → traveled 边界（投影不可用/偏离路线时的 fallback）。
     *
     * 含路线归属过滤：选路/重算后 1~2s 内引擎仍上报旧路线 remain，消费它会让只减不增
     * 的棘轮把一次性窗口错误永久放大。返回 null = 本拍不可用，调用方跳过。
     */
    private fun engineGrayBoundary(dtSeconds: Double): Double? {
        val st = NaviSession.state.value
        val remain = st.routeRemainMeters
        if (remain <= 0) return null
        val selInternal = NaviSession.selectedInternalPathId
        if (selInternal != 0L && st.remainPathId != 0L && st.remainPathId != selInternal) {
            return null
        }
        if (remain.toDouble() > cumFromEnd[0] + 50.0) {
            Log.w(
                TAG, "gray INCONSISTENT: remain=${remain}m > routeTotal=" +
                    "${cumFromEnd[0].toInt()}m (引擎路线与坐标快照不一致)"
            )
        }
        // 单调棘轮 + 速度帽：remain 只减不增；超物理上限的骤降（毛刺/重算）不锁存
        lastGrayRemain = advanceGrayRemain(lastGrayRemain, remain, dtSeconds)
        val eff = lastGrayRemain
        // traveled-based：首帧锁存 remain0/total0，边界只随已走里程推进——引擎 t=0
        // remain 比几何总长短 ~300m（仅近出发锁存，见 grayBaselineRemain0）
        if (grayRemain0 <= 0) {
            grayRemain0 = grayBaselineRemain0(cumFromEnd[0], eff)
            grayTotal0 = cumFromEnd[0]
        }
        return grayTraveledBoundary(grayTotal0, grayRemain0, eff)
    }

    private var panAccumPx = 0f
    private var panFlattened = false

    fun scrollBy(dx: Float, dy: Float) {
        if (!mapLoaded) return
        val a = aMap ?: return
        follow = false // 用户拖动：暂停跟随，定位按钮可回正
        a.moveCamera(CameraUpdateFactory.scrollBy(dx, dy))
        // 累计拖拽超阈值：400ms 平滑切 2D 正上并点亮「返回导航视角」胶囊；
        // panFlattened 每轮拖拽只触发一次（recenter 复位）
        panAccumPx += kotlin.math.abs(dx) + kotlin.math.abs(dy)
        if (!panFlattened && panAccumPx > PAN_FLATTEN_PX) {
            panFlattened = true
            val cam = a.cameraPosition
            Log.i(
                TAG, "pan flatten: tilt ${cam.tilt}->0 bearing ${cam.bearing}->0 " +
                    "accum=${panAccumPx.toInt()}px"
            )
            a.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition(cam.target, cam.zoom, 0f, 0f)
                ),
                400L, null
            )
            CarMapControl.overviewActive.value = true
        }
    }

    fun zoomBy(factor: Float) {
        Log.i(TAG, "presentation.zoomBy($factor) mapLoaded=$mapLoaded")
        if (!mapLoaded) return
        // 手动缩放：15s 内自动缩放不接管
        manualZoomUntilMs =
            android.os.SystemClock.elapsedRealtime() + MANUAL_ZOOM_HOLD_MS
        if (factor > 1f) aMap?.animateCamera(CameraUpdateFactory.zoomIn())
        else if (factor < 1f) aMap?.animateCamera(CameraUpdateFactory.zoomOut())
    }

    // ── 选路模式：备选路线灰线 + 2D 北向上全程视野 ──

    private val altLines = mutableListOf<Polyline>()
    private var altLinePoints: Map<Int, List<LatLng>> = emptyMap()
    private var hlLine: Polyline? = null
    private val routeBubbles = mutableListOf<Marker>()
    private var selPaths: Map<Int, List<LatLng>> = emptyMap()

    /** 进入选路：**全部路线蓝线** + 中点气泡标识（当前=蓝框），选中路线加**白色描边**；
     *  相机 2D 北向上容纳全部路线。 */
    fun enterRouteSelect(
        paths: Map<Int, List<LatLng>>,
        selectedId: Int?,
        opts: List<RouteSelOption>,
    ) {
        if (!mapLoaded) return
        val a = aMap ?: return
        // 预导航态引擎无路线：refit 回调带空 paths 会清掉缓存（自绘层消失根因）
        if (paths.isEmpty()) return
        // 导航中切路：coordList 是原始起点起的全程几何，按车位截断到前方剩余段
        val ahead = trimPathsAhead(paths, lastLat, lastLng)
        follow = false
        drawSelectGraphics(ahead, selectedId, opts)
        // 取景：统一手动可见区拟合（[fitPathsToVisible]，与全览共用；无底部卡）
        val m = CarMapControl.metrics.value
        val cx = CarUiGeometry.mapCenterX(m, overlayColumnWidth(m))
        val cy = CarUiGeometry.mapCenterY(
            m, CAR_AHEAD_SCREEN_PX.toInt(), hasBottomCards = false
        )
        fitPathsToVisible(a, ahead.values, ROUTE_SEL_PADDING, cx, cy, fallbackZoom = 12.5f)
        Log.i(TAG, "route select view: ${ahead.size} paths (compose-drawn)")
    }

    /** 选路数据刷新：重绘线/气泡/白描边，不动相机（引擎实时更新时用）。 */
    fun refreshRouteSelect(
        paths: Map<Int, List<LatLng>>,
        selectedId: Int?,
        opts: List<RouteSelOption>,
    ) {
        if (!mapLoaded || CarMapControl.routeSelect.value == null) return
        if (paths.isEmpty()) return
        // 与进入时同口径：截断到车位前方剩余段（面板开着时车在走，图形跟着前移）
        val ahead = trimPathsAhead(paths, lastLat, lastLng)
        CarMapControl.selPaths = ahead
        CarMapControl.selSelectedId = selectedId
        drawSelectGraphics(ahead, selectedId, opts)
    }

    private fun drawSelectGraphics(
        paths: Map<Int, List<LatLng>>,
        selectedId: Int?,
        opts: List<RouteSelOption>,
    ) {
        // 选路图形 = Compose Canvas 自绘（见 CarMapUi.SelectGraphicsLayer）：MWCT 态
        // 引擎覆盖物新增不进渲染层，故引擎层只留数据缓存供自绘/命中
        selPaths = paths
        altLinePoints = paths
        CarMapControl.selPaths = paths
        CarMapControl.selSelectedId = selectedId
        CarMapControl.selOpts = opts
        CarMapControl.selVersion.value++
    }

    /** 白描边高亮（引擎层已弃用：Compose 自绘层按 current 画 halo）。 */
    fun setRouteFocus(pathId: Int?) {
        // no-op：AA18 MWCT 引擎覆盖物不可靠，高亮由自绘层承担
    }

    /** 退出选路：灰线清除，回跟随并飞回车位（ticker ≤1s 内恢复 3D 车头向上）。 */
    fun exitRouteSelect() {
        clearAltLines()
        follow = true
        manualZoomUntilMs = 0L
        if (mapLoaded && lastLat != 0.0) {
            aMap?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lastLat, lastLng)))
            // 缩放由下一 tick 速度自适应吸附恢复
        }
    }

    private fun clearAltLines() {
        altLines.forEach { runCatching { it.remove() } }
        altLines.clear()
        altLinePoints = emptyMap()
        runCatching { hlLine?.remove() }
        hlLine = null
        routeBubbles.forEach { runCatching { it.remove() } }
        routeBubbles.clear()
        selPaths = emptyMap()
    }

    /** 选路图形/取景路径截断：引擎 coordList 是原始起点起的全程几何，按车位最近顶点
     *  截断只保留前方剩余段（多留 [TRIM_BACK_POINTS] 个顶点衔接）。车位未知时原样返回。 */
    private fun trimPathsAhead(
        paths: Map<Int, List<LatLng>>,
        lat: Double,
        lng: Double,
    ): Map<Int, List<LatLng>> {
        if (lat == 0.0 || lng == 0.0 || paths.isEmpty()) return paths
        return paths.mapValues { (_, pts) ->
            if (pts.size <= TRIM_BACK_POINTS + 2) return@mapValues pts
            var best = 0
            var bestD = Double.MAX_VALUE
            for (i in pts.indices) {
                val d = sqr(pts[i].latitude - lat) + sqr(pts[i].longitude - lng)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            pts.subList((best - TRIM_BACK_POINTS).coerceAtLeast(0), pts.size)
        }
    }

    private fun sqr(x: Double) = x * x

    /** 两经纬度点大圆距离（米，haversine）。 */
    private fun meterBetween(
        p0: com.amap.api.maps.model.LatLng,
        p1: com.amap.api.maps.model.LatLng,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(p1.latitude - p0.latitude)
        val dLng = Math.toRadians(p1.longitude - p0.longitude)
        val s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(p0.latitude)) *
            Math.cos(Math.toRadians(p1.latitude)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(s))
    }

    /** 路线点集 bbox 的取景要素：中心 + 米跨度（等距圆柱，经度按 cos(midLat) 修正）。 */
    private class FrameSpan(val center: LatLng, val latM: Double, val lngM: Double)

    /** 多路径点集合并 bbox（空/单点退化为 null——单点无拟合意义）。 */
    private fun frameSpanOf(paths: Collection<List<LatLng>>): FrameSpan? {
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        paths.forEach { pts ->
            pts.forEach { p ->
                if (p.latitude < minLat) minLat = p.latitude
                if (p.latitude > maxLat) maxLat = p.latitude
                if (p.longitude < minLng) minLng = p.longitude
                if (p.longitude > maxLng) maxLng = p.longitude
            }
        }
        if (minLat >= maxLat && minLng >= maxLng) return null
        val midLat = (minLat + maxLat) / 2.0
        return FrameSpan(
            LatLng(midLat, (minLng + maxLng) / 2.0),
            (maxLat - minLat) * 111_320.0,
            (maxLng - minLng) * 111_320.0 * Math.cos(Math.toRadians(midLat)),
        )
    }

    /**
     * 统一取景原语（**全览 + 选路共用**）：按**可见区**宽高拟合 + bbox 中心锚到屏像素
     * [anchorX]/[anchorY]。不走引擎 `newLatLngBounds`（MWCT 态不可靠）；zoom 用引擎
     * 投影实测米/像素按 ∝ 2^-zoom 推导。锚点须在 animateCamera **之后**设置。
     *
     * @param padPx 屏像素四周留白
     * @param fallbackZoom 投影实测失败时的兜底 zoom；null = 不动相机并返回 false
     * @return 是否成功发起取景（失败时不触碰锚点账本）
     */
    private fun fitPathsToVisible(
        a: com.amap.api.maps.AMap,
        paths: Collection<List<LatLng>>,
        padPx: Int,
        anchorX: Int,
        anchorY: Int,
        fallbackZoom: Float? = null,
    ): Boolean {
        val span = frameSpanOf(paths) ?: return false
        val m = CarMapControl.metrics.value
        val padView = padPx / mapViewScale
        val availW = (m.contentWidth / mapViewScale - 2 * padView).coerceAtLeast(60f)
        val availH = (m.contentHeight / mapViewScale - 2 * padView).coerceAtLeast(60f)
        val mPerPxNeed = maxOf(span.latM / availH, span.lngM / availW).coerceAtLeast(0.01)
        val zoom = runCatching {
            val proj = a.projection
            val midY = (m.contentHeight / mapViewScale / 2).toInt()
            val p0 = proj.fromScreenLocation(android.graphics.Point(0, midY))
            val p1 = proj.fromScreenLocation(android.graphics.Point(100, midY))
            val measured = meterBetween(p0, p1) / 100.0
            (a.cameraPosition.zoom + Math.log(measured / mPerPxNeed) / Math.log(2.0))
                .toFloat()
        }.getOrNull() ?: fallbackZoom
        if (zoom == null) return false
        val ok = runCatching {
            a.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition(span.center, zoom, 0f, 0f)
                )
            )
        }.isSuccess
        if (!ok) {
            Log.e(TAG, "fit visible camera failed: zoom=$zoom")
            return false
        }
        Log.i(
            TAG, "fit visible: zoom=$zoom span=${span.latM.toInt()}x${span.lngM.toInt()}m " +
                "avail=${availW.toInt()}x${availH.toInt()}vp anchor=($anchorX,$anchorY)"
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "fit visible result: cam=${a.cameraPosition.zoom} @${a.cameraPosition.target}")
        }, 800)
        setCenterPoint(a, anchorX, anchorY)
        lastCenterX = anchorX
        lastCenterY = anchorY
        return true
    }

    /** 序号徽标弧长位置比例（按路线序号错开，避免并行几何贴脸）。 */
    private val BADGE_FRACS = listOf(0.50, 0.25, 0.80, 0.15, 0.90)

    /** 路线序号徽标位图：小圆牌（当前=蓝底白字，其余深底灰框白字）；详情在选路面板行。 */
    private fun routeBadge(
        num: Int,
        current: Boolean,
    ): com.amap.api.maps.model.BitmapDescriptor {
        // 位图按设计 dp × 密度 ÷SCALE 放大：MapView 几何缩放后屏上保持 44dp 语义
        val s = densityFactor / mapViewScale
        val d = (44f * s).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(
            d, d, android.graphics.Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(bmp)
        val rect = android.graphics.RectF(2f * s, 2f * s, d - 2f * s, d - 2f * s)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        bg.color = if (current) 0xFF4EA0FF.toInt() else 0xF2111114.toInt()
        c.drawOval(rect, bg)
        if (!current) {
            val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            border.color = 0xFF8A93A6.toInt()
            border.style = android.graphics.Paint.Style.STROKE
            border.strokeWidth = 2.5f * s
            c.drawOval(rect, border)
        }
        val text = "$num"
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 22f * s
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.color = 0xFFFFFFFF.toInt()
        val tw = paint.measureText(text)
        c.drawText(text, d / 2f - tw / 2f, d / 2f + 8f * s, paint)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 灰线命中：采样点屏幕像素距离最小者 < 阈值 → 返回 pathId。 */
    fun hitRouteLine(x: Int, y: Int): Int? {
        val proj = aMap?.projection ?: return null
        val th = CarUiGeometry.ROUTE_LINE_HIT_PX
        for ((id, pts) in altLinePoints) {
            val step = maxOf(1, pts.size / 80)
            var i = 0
            while (i < pts.size) {
                val sp = runCatching { proj.toScreenLocation(pts[i]) }.getOrNull()
                if (sp != null) {
                    // toScreenLocation 返回 GL 表面像素，换算回屏坐标再比阈值
                    val dx = sp.x * mapViewScale - x.toFloat()
                    val dy = sp.y * mapViewScale - y.toFloat()
                    if (dx * dx + dy * dy < th * th) return id
                }
                i += step
            }
        }
        return null
    }

    /** 完整路线：2D 俯视缩放到路线边界（退出跟随；回正钮恢复 3D 跟随）。取景与选路共用
     *  [fitPathsToVisible]；投影失败兜底 plain `newLatLngBounds`（先归零成 2D）。 */
    fun showOverview() {
        if (!mapLoaded) return
        val pts = NaviSession.routePoints.value ?: return
        if (pts.size < 2) return
        follow = false
        val a = aMap ?: return
        a.moveCamera(CameraUpdateFactory.changeTilt(0f))
        a.moveCamera(CameraUpdateFactory.changeBearing(0f))
        // 锚点与 applyViewportCenter 同参数（hasBottomCards=isActive）→ 账本一致
        val m = CarMapControl.metrics.value
        val cx = CarUiGeometry.mapCenterX(m, overlayColumnWidth(m))
        val cy = CarUiGeometry.mapCenterY(
            m, CAR_AHEAD_SCREEN_PX.toInt(),
            hasBottomCards = com.amapilot.navigation.NaviSession.isActive
        )
        if (fitPathsToVisible(a, listOf(pts), OVERVIEW_PADDING, cx, cy)) return
        val builder = LatLngBounds.builder()
        pts.forEach { builder.include(it) }
        runCatching {
            a.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    builder.build(), (OVERVIEW_PADDING / mapViewScale).toInt()
                )
            )
        }
        Log.i(TAG, "overview fit fallback: plain newLatLngBounds")
    }

    /** 回正到最近位置并恢复跟随（新 presentation 未收过定位时兜底取已知位置）。 */
    fun recenterToLast() {
        follow = true
        manualZoomUntilMs = 0L
        panAccumPx = 0f
        panFlattened = false
        if (lastLat == 0.0 && lastLng == 0.0) {
            // Surface 重建后的新实例还没有定位历史：取会话/系统 lastKnown
            val fb = bestKnownPosition()
            Log.i(TAG, "recenterToLast fallback=$fb")
            if (fb != null) {
                lastLat = fb.latitude
                lastLng = fb.longitude
            }
        } else {
            Log.i(TAG, "recenterToLast ($lastLat,$lastLng)")
        }
        if (lastLat != 0.0 || lastLng != 0.0) {
            // 直接回到自适应缩放（手动/全览留下的级别不再保留）
            aMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(lastLat, lastLng),
                    autoZoomFor(lastSpeedKmh, lastManeuverRemain)
                )
            )
        }
    }

    /** 跟随开关：关闭后锁定视角，重新开启时回正到最近位置。 */
    fun toggleFollow() {
        follow = !follow
        if (follow && (lastLat != 0.0 || lastLng != 0.0)) {
            aMap?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lastLat, lastLng)))
        }
    }

    /** Surface 摘除：暂停渲染（引擎/状态全保留）。 */
    fun onSurfaceDetached() {
        pauseRendering()
    }

    /** Surface 重挂：恢复渲染并刷新一帧相机/箭头（状态即拿即用，无冷启动）。 */
    fun onSurfaceReattached() {
        if (!CarMapControl.phoneForeground) resumeRendering()
        if (mapLoaded && (lastLat != 0.0 || lastLng != 0.0)) {
            // 用最近解算航向（传 0 会把视角甩回正北，箭头朝向跟着错）
            moveCamera(lastLat, lastLng, lastHeading, lastSpeedKmh)
        }
        Log.i(TAG, "presentation reattached, rendering=${!CarMapControl.phoneForeground}")
    }

    /** **宿主表面尺寸变化**（多窗口/分屏切换）后的重新取景：VD 已 resize，但相机取景
     *  仍是旧宽高比下解算的结果。跟随态重跑 [moveCamera]（前推量与锚点随新尺寸重算）；
     *  全览/选路态重新拟合边界；手拖压平态保持北向上，仅重贴箭头。 */
    /** 可见区变化（宿主展开/收起副窗口）：与尺寸变化同一套处理。 */
    fun onViewportChanged() {
        Log.i(TAG, "viewport changed -> re-anchor map center + camera")
        onSurfaceResized()
    }

    fun onSurfaceResized() {
        // 超宽屏/宿主多窗口：MapView 布局尺寸在 onCreate 定死，resize 后不重布局则露窗底灰边
        runCatching {
            val dm = context.resources.displayMetrics
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                mapViewScale = safeMapScale(dm.widthPixels, dm.heightPixels)
                mapView?.scaleX = mapViewScale
                mapView?.scaleY = mapViewScale
                mapView?.layoutParams = android.widget.FrameLayout.LayoutParams(
                    (dm.widthPixels / mapViewScale).toInt(),
                    (dm.heightPixels / mapViewScale).toInt()
                )
            }
        }
        if (!mapLoaded) {
            Log.i(TAG, "surface resized before map load (camera seeds on load)")
            return
        }
        val m = CarMapControl.metrics.value
        // 取景中心挪到可见区中央：副窗口盖住右侧时，车头箭头不会被压在面板下
        applyViewportCenter(m)
        when {
            CarMapControl.routeSelect.value != null -> {
                Log.i(
                    TAG, "viewport ${m.contentWidth}x${m.contentHeight}: " +
                        "refit route-select"
                )
                // 先刷新行数据（引擎实时剩余量），再按新宽高比取景（几何取 selPaths 缓存）
                CarMapControl.refreshRouteSelectView()
                enterRouteSelect(
                    CarMapControl.selPaths,
                    CarMapControl.selSelectedId,
                    CarMapControl.routeSelect.value.orEmpty()
                )
            }

            !follow -> {
                Log.i(TAG, "viewport ${m.contentWidth}x${m.contentHeight}: refit overview")
                val a = aMap ?: return
                showOverview()
                if (lastLat != 0.0 || lastLng != 0.0) {
                    updateArrow(a, lastLat, lastLng, lastHeading, a.cameraPosition.bearing)
                }
            }

            else -> {
                Log.i(
                    TAG, "viewport ${m.contentWidth}x${m.contentHeight}: " +
                        "recenter follow camera (arrow re-anchored, ${CarUiGeometry.RESIZE_ANIM_MS}ms)"
                )
                if (lastLat != 0.0 || lastLng != 0.0) {
                    // 与叠加层布局动画同长：地图取景与卡片同时落位
                    moveCamera(
                        lastLat, lastLng, lastHeading, lastSpeedKmh,
                        smoothMs = CarUiGeometry.RESIZE_ANIM_MS.toLong()
                    )
                } else {
                    recenterToLast()
                }
            }
        }
    }

    /** 把相机目标点映射到**可见区里没有被自绘覆盖物占用的中心**：常态 = (400,200)；
     *  副窗口压窄时右移让开卡片列，矮窗时上移防车头落到按钮行下（[CarUiGeometry]）。 */
    private var lastCenterX = -1
    private var lastCenterY = -1
    private var appliedCenterX = -1
    private var appliedCenterY = -1
    private var centerAnim: android.animation.ValueAnimator? = null

    /** 视角过渡窗口截止时刻：期间常规跟随也用动画，避免瞬移打断 resize 过渡。 */
    private var viewportSettleUntilMs = 0L

    private fun applyViewportCenter(m: CarScreenMetrics) {
        val a = aMap ?: return
        val g = CarUiGeometry
        val cx = g.mapCenterX(m, overlayColumnWidth(m))
        val cy = g.mapCenterY(
            m, CAR_AHEAD_SCREEN_PX.toInt(),
            hasBottomCards = com.amapilot.navigation.NaviSession.isActive
        )
        if (cx == lastCenterX && cy == lastCenterY) return
        val fromX = if (appliedCenterX < 0) cx else appliedCenterX
        val fromY = if (appliedCenterY < 0) cy else appliedCenterY
        lastCenterX = cx
        lastCenterY = cy
        viewportSettleUntilMs = android.os.SystemClock.elapsedRealtime() +
            CarUiGeometry.RESIZE_ANIM_MS + 120L
        Log.i(
            TAG, "map center ($fromX,$fromY) -> ($cx,$cy) for content " +
                "${m.contentWidth}x${m.contentHeight} " +
                "(overlay column ${overlayColumnWidth(m)})"
        )
        centerAnim?.cancel()
        if (fromX == cx && fromY == cy) {
            setCenterPoint(a, cx, cy)
            return
        }
        // setPointToCenter 瞬时生效：直接换会让地图横向闪一下 → 逐帧插值（同 RESIZE_ANIM_MS）
        centerAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CarUiGeometry.RESIZE_ANIM_MS.toLong()
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                setCenterPoint(
                    a,
                    (fromX + (cx - fromX) * f).toInt(),
                    (fromY + (cy - fromY) * f).toInt()
                )
            }
            start()
        }
    }

    private fun setCenterPoint(a: com.amap.api.maps.AMap, x: Int, y: Int) {
        if (x == appliedCenterX && y == appliedCenterY) return
        appliedCenterX = x
        appliedCenterY = y
        // setPointToCenter 收 MapView 像素（GL 表面 = 屏 / mapViewScale）
        runCatching {
            a.setPointToCenter(
                (x / mapViewScale).toInt(),
                (y / mapViewScale).toInt()
            )
        }
            .onFailure { Log.w(TAG, "setPointToCenter failed", it) }
    }

    // ── 跟随动画时长调试覆盖（仿 amapilot_force_gps_navi 先例）──
    // `amapilot_follow_anim_ms` <50..5000>；缺省/越界 = FOLLOW_ANIM_MS，10s 缓存。
    private var animOverrideCacheMs = 0L
    private var animOverrideCheckedAt = 0L

    /** 当前生效的跟随相机动画时长（调试覆盖 > FOLLOW_ANIM_MS 默认）。 */
    private fun followAnimMs(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        if (animOverrideCacheMs == 0L || now - animOverrideCheckedAt > 10_000L) {
            animOverrideCheckedAt = now
            val prev = animOverrideCacheMs
            animOverrideCacheMs = runCatching {
                val v = android.provider.Settings.Global.getInt(
                    context.contentResolver, "amapilot_follow_anim_ms", 0
                )
                if (v in 50..5_000) v.toLong() else FOLLOW_ANIM_MS
            }.getOrDefault(FOLLOW_ANIM_MS)
            if (animOverrideCacheMs != prev) {
                Log.i(TAG, "follow anim duration -> ${animOverrideCacheMs}ms (A/B override)")
            }
        }
        return animOverrideCacheMs
    }

    // ── 跟随俯仰角调试覆盖（与 followAnimMs 同款；用户实调视角用）──
    private var tiltOverrideCache = -1f
    private var tiltCheckedAt = 0L

    /** 当前生效的 3D 俯仰角（amapilot_car_tilt 覆盖 > CAR_TILT 默认）。 */
    private fun carTilt(): Float {
        val now = android.os.SystemClock.elapsedRealtime()
        if (tiltOverrideCache < 0f || now - tiltCheckedAt > 10_000L) {
            tiltCheckedAt = now
            tiltOverrideCache = runCatching {
                val v = android.provider.Settings.Global.getInt(
                    context.contentResolver, "amapilot_car_tilt", 0
                )
                if (v in 30..70) v.toFloat() else CAR_TILT
            }.getOrDefault(CAR_TILT)
        }
        return tiltOverrideCache
    }

    /** 临近机动的俯仰上抬：低速接近转向点上抬看路口。坡道与 AutoZoom 机动拉近**同带**
     *  （500m 起坡、100m 满格）；速度门 [TILT_LIFT_MAX_KMH] 之上保持贴地。 */
    private fun maneuverLiftTilt(speedKmh: Float, maneuverRemainMeters: Float): Float {
        val base = carTilt()
        if (speedKmh >= TILT_LIFT_MAX_KMH ||
            !maneuverRemainMeters.isFinite() ||
            maneuverRemainMeters >= com.amapilot.navigation.AutoZoom.MANEUVER_START_M
        ) {
            return base
        }
        val ramp = (
            (com.amapilot.navigation.AutoZoom.MANEUVER_START_M - maneuverRemainMeters) /
                (com.amapilot.navigation.AutoZoom.MANEUVER_START_M -
                    com.amapilot.navigation.AutoZoom.MANEUVER_FULL_M)
            ).coerceIn(0f, 1f)
        val target = kotlin.math.min(base, TILT_NEAR_MANEUVER)
        return base - (base - target) * ramp
    }

    /** 当前 UI 态下左侧**常驻**覆盖物列的宽度（含边距）：导航态 = 转向卡列；巡航车速环
     *  在右下、空闲态入口在宿主 rail → 其余态仅边距。弹出面板**不计入**（瞬态浮层本就
     *  盖住地图，挤走取景中心会让箭头在开合时突跳）。 */
    private fun overlayColumnWidth(m: CarScreenMetrics): Int {
        val g = CarUiGeometry
        return when {
            com.amapilot.navigation.NaviSession.isActive ->
                g.navCardWidth(m) + g.EDGE_MARGIN
            else -> g.EDGE_MARGIN
        }
    }

    /** 暂停/恢复车机地图渲染（手机前台时暂停，避免同进程双引擎抢全局渲染锁）。 */
    @Volatile
    private var rendering = true

    fun pauseRendering() {
        if (!rendering) return
        rendering = false
        runCatching { mapView?.onPause() }
        Log.i(TAG, "car map rendering paused")
    }

    fun resumeRendering() {
        if (rendering) return
        rendering = true
        runCatching { mapView?.onResume() }
        Log.i(TAG, "car map rendering resumed")
    }
}
