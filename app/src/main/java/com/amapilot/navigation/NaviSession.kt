package com.amapilot.navigation

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.CameraType
import com.amap.api.navi.enums.CarEnterCameraStatus
import com.amap.api.navi.enums.IconType
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * 自研导航 UI 状态（手机 TBT 屏与 Android Auto 车机共用）。
 *
 * 距离/时间单位 = 米/秒；speedKmh/limitKmh = km/h（limitKmh 0=未知，来自电子眼）。
 * iconType/actionText/nextRoad = 当前转向；currentRoad = 当前道路；arrived = 已到达。
 *
 * @property remainPathId [routeRemainMeters] 所属路线 pathId；切换窗口内引擎仍上报
 *   旧路线里程，消费方须按此归属过滤
 */
data class NaviState(
    val iconType: Int = IconType.NONE,
    val actionText: String = "",
    val nextRoad: String = "",
    val stepRemainMeters: Int = 0,
    val routeRemainMeters: Int = 0,
    val remainPathId: Long = 0L,
    val routeRemainSeconds: Int = 0,
    val lightCount: Int = 0,
    /** 当前 step 内剩余 link 数（link 边界≈路口）：[linkRemainMeters] 缺失时的
     *  车道条兜底判据（含路口内部 link 时不稳，不作主判据）。 */
    val linksToManeuver: Int = 1,
    /** 当前 link 剩余米数（NaviInfo.curLinkRetainDistance，-1=未上报）；与
     *  [stepRemainMeters] 之差是车道高亮方向的主判据。 */
    val linkRemainMeters: Int = -1,
    /** 当前 step 索引（[GuidanceController] 检测机动完成 = 索引递增）。 */
    val stepIndex: Int = 0,
    /** 当前转向剩余秒数（引擎按车速估算；0=未知，回退米数阈值）；[GuidanceController] 接近播报用。 */
    val stepRemainSeconds: Int = 0,
    val currentRoad: String = "",
    val speedKmh: Int = 0,
    val limitKmh: Int = 0,
    /** 区间测速态（null=非区间；仅语音消费——车速环视觉不分区间，见 [SpeedZone]）。 */
    val speedZone: SpeedZone? = null,
    val arrived: Boolean = false
)

/**
 * 区间测速状态（导航态引擎 `updateIntervalCameraInfo` 专用回调驱动；巡航态无此数据，
 * 为 SDK 硬边界）。车速环视觉**不区分**区间（用户定稿：视觉只保留车速/限速），
 * 区间语义仅供语音播报与 [NaviState.limitKmh] 路由消费。
 *
 * 边界语义（引擎 javadoc + 用户定稿）：
 * - **中途汇入只扫到出口**（startCamera=null）：无起点基准，区间均值无意义 →
 *   不建区间态，出口限速降级为普通限速生效；
 * - **中途驶出**（LEAVE 未走完）：均值/剩余仅覆盖部分路程，保留会误导 → 清除；
 * - **完整进入**（先 UNENTER 后 ENTER）：均值可信。
 *
 * @property limitKmh 区间限速（0=未采集）
 * @property averageKmh 区间内实时平均速度（-1=未计算或接近态未进入）
 * @property totalMeters 区间总长（-1=未上报）
 * @property remainMeters 区间剩余长度（-1=未上报；位置相对终点，客观可信）
 * @property inside true=已在区间内（ENTER）；false=接近中（UNENTER）
 * @property fullEntry true=先接近后进入的完整区间（[averageKmh] 可播）；
 *   中途汇入直接 ENTER 时 SDK 均值跟踪基线偏晚，不可播
 */
data class SpeedZone(
    val limitKmh: Int = 0,
    val averageKmh: Int = -1,
    val totalMeters: Int = -1,
    val remainMeters: Int = -1,
    val inside: Boolean = false,
    val fullEntry: Boolean = false,
)

/** 途经点（含展示名，按加入顺序）。 */
data class NaviWaypoint(
    val name: String,
    val lat: Double,
    val lng: Double
)

/** 备选路线（切换路线用）。 */
data class NaviRouteOption(
    val pathId: Int,
    val lengthMeters: Int,
    val durationSeconds: Int,
    val trafficLights: Int
)

/**
 * 导航会话：聚合 [AMapNavi] 数据回调为 [StateFlow]，
 * 供手机 TBT 屏与 Android Auto NavigationTemplate 共用；
 * 途经点按加入顺序参与路线重算。
 */
/** 到达后展示到达态并自动返回巡航的延迟。 */
private const val ARRIVED_CRUISE_DELAY_MS = 2000L

object NaviSession {

    /** 车道面板显示门槛（米）：距 maneuver 过远不展示（引擎提前数公里推匝道车道易误导）。 */
    const val LANE_PANEL_MAX_METERS = 2000

    private val _state = MutableStateFlow(NaviState())
    val state: StateFlow<NaviState> = _state

    /** 单点测速限速池（棘轮：无新值保留；区间态期间被区间限速覆盖发布）。 */
    private var pointLimitKmh = 0

    /** 当前区间测速态（null=非区间；与 [NaviState.speedZone] 同源发布）。 */
    private var zone: SpeedZone? = null

    /** 本区间经过过接近态（UNENTER）：区分完整进入与中途汇入（均值可信性）。 */
    private var zoneSawApproach = false

    val isActive: Boolean
        get() = navi != null

    /** 会话激活态（可观察）：state 流同值不发射，卡片层须靠它即时重组。 */
    private val _active = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> = _active

    /** 导航引擎位置（含 EMULATOR 模拟位置），驱动 TBT 地图定位层。 */
    private val _location = MutableStateFlow<AMapNaviLocation?>(null)
    val location: StateFlow<AMapNaviLocation?> = _location

    private val _waypoints = MutableStateFlow<List<NaviWaypoint>>(emptyList())
    val waypoints: StateFlow<List<NaviWaypoint>> = _waypoints

    private val _routeOptions = MutableStateFlow<List<NaviRouteOption>>(emptyList())
    val routeOptions: StateFlow<List<NaviRouteOption>> = _routeOptions

    private val _selectedPathId = MutableStateFlow<Int?>(null)
    val selectedPathId: StateFlow<Int?> = _selectedPathId

    /** 单条车道引导（两个 `showLaneInfo` 回调的 `backgroundLane` 语义不同，已归一化）。 */
    data class LaneCell(val actions: List<Int>, val recommended: Boolean)

    /** 单条回调带来的整组背景方向（供随后到达的数组回调按车道拆分）。 */
    private var pendingLaneBg: IntArray? = null

    /** 现场诊断去重：link 计数×距离判定矛盾只打一次日志。 */
    private var laneCountAnomalyLogged = false

    /** 前方路口车道引导（showLaneInfo 推送、hideLaneInfo 清空）；空=不展示车道条。 */
    private val _lanes = MutableStateFlow<List<LaneCell>>(emptyList())
    val lanes: StateFlow<List<LaneCell>> = _lanes

    /** 全程路线步骤（分步列表）：方向 + 路口路名 + 距离 + 路口坐标。 */
    data class RouteStep(
        val iconType: Int,
        val roadName: String,
        val lengthMeters: Int,
        val lat: Double,
        val lng: Double
    )

    private val _routeSteps = MutableStateFlow<List<RouteStep>>(emptyList())
    val routeSteps: StateFlow<List<RouteStep>> = _routeSteps

    private fun refreshSteps() {
        val n = navi ?: return
        val path = _selectedPathId.value?.let { n.naviPaths[it] }
            ?: n.naviPaths.values.firstOrNull()
        _routeSteps.value = path?.steps?.map { s ->
            val end = s.coords?.lastOrNull()
            RouteStep(
                iconType = s.iconType,
                roadName = s.links?.firstOrNull()?.roadName.orEmpty(),
                lengthMeters = s.length,
                lat = end?.latitude ?: 0.0,
                lng = end?.longitude ?: 0.0
            )
        }.orEmpty()
    }

    private var listener: SimpleNaviListener? = null
    private var navi: AMapNavi? = null

    var destination: LatLng? = null
        private set

    /** 导航类型（EMULATOR / GPS）。 */
    private var emulatorNavi = false
    private var arrivalJob: kotlinx.coroutines.Job? = null
    private val scope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Main.immediate
        )
    private var appContext: Context? = null
    /** 选中路线的引擎内部 path id（与 NaviInfo.pathId 同号段；0=未知/不过滤）。 */
    private var selectedInternalId: Long = 0L

    /** startNavi 是否实际生效（引擎无路线会拒绝）；幽灵导航自愈判据。 */
    private var naviRunning: Boolean = false
    val selectedInternalPathId: Long get() = selectedInternalId

    /** 换路请求进行中（忽略新请求，防连续点击竞态）。 */
    private val switching = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _strategy = MutableStateFlow(PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT)
    val strategy: StateFlow<Int> = _strategy

    /** 当前导航路线（地图画线用），随选中路线切换更新；null 表示未在导航。 */
    private val _routePoints = MutableStateFlow<List<LatLng>?>(null)
    val routePoints: StateFlow<List<LatLng>?> = _routePoints

    /** 当前选中路线分段路况（linkIndex = link 索引起点，status 0-4）：车机路况配色用。 */
    private val _routeTraffic = MutableStateFlow<List<com.amap.api.navi.model.AMapTrafficStatus>>(emptyList())
    val routeTraffic: StateFlow<List<com.amap.api.navi.model.AMapTrafficStatus>> = _routeTraffic

    /** 选中路线各 link 的起始坐标下标表（linkIndex → 坐标点；直接当坐标索引用会整体错位）。 */
    private val _routeLinkStarts = MutableStateFlow(IntArray(0))
    val routeLinkStarts: StateFlow<IntArray> = _routeLinkStarts

    /** 全部备选路线坐标（pathId → 路线点）：车机选路模式画灰线/命中用。 */
    private val _routePaths = MutableStateFlow<Map<Int, List<LatLng>>>(emptyMap())
    val routePaths: StateFlow<Map<Int, List<LatLng>>> = _routePaths

    /** 刷新画线为当前选中路线（无选中时取第一条）。 */
    private fun refreshRoutePoints() {
        val paths = navi?.naviPaths ?: return
        val id = _selectedPathId.value ?: paths.keys.firstOrNull()
        _routePoints.value = paths[id]?.coordList
            ?.map { LatLng(it.latitude, it.longitude) }
        // NaviInfo.pathId 与 naviPaths key 不同号段（引擎内部 long id），记录供归属过滤
        selectedInternalId = runCatching { paths[id]?.pathid ?: 0L }.getOrDefault(0L)
        // link → 坐标偏移表：step 边界可能有 ±1 差，按总长比例映射消偏
        _routeLinkStarts.value = runCatching {
            val raw = ArrayList<Int>()
            var c = 0
            paths[id]?.steps?.forEach { st ->
                st.links?.forEach { lk ->
                    raw.add(c)
                    c += lk.coords?.size ?: 0
                }
            }
            val n = paths[id]?.coordList?.size ?: 0
            if (raw.isEmpty() || c <= 0 || n <= 0) {
                IntArray(0)
            } else {
                val f = n.toDouble() / c
                IntArray(raw.size + 1) { i ->
                    if (i < raw.size) kotlin.math.round(raw[i] * f).toInt() else n
                }
            }
        }.getOrDefault(IntArray(0))
        _routeTraffic.value = runCatching {
            paths[id]?.trafficStatuses.orEmpty()
        }.getOrDefault(emptyList())
        // 真机调试：导出路线坐标供 adb 位置注入沿路线模拟行驶（到达/巡航回归）
        runCatching {
            val pts = _routePoints.value.orEmpty()
            pts.chunked(80).forEachIndexed { i, c ->
                android.util.Log.d(
                    "NaviSession",
                    "routepts[$i]=" + c.joinToString(";") { "${it.latitude},${it.longitude}" }
                )
            }
        }
        refreshSteps()
    }

    /**
     * 沿已规划路线开始导航。
     *
     * @param emulator true 用模拟导航（模拟器验证）
     * @param destination 终点坐标（重算使用）
     * @param initialPathId 指定初始路线（`selectRouteId` 仅导航开始前生效）
     * @param waypoints 途经点（按加入顺序，策略重算沿用）
     */
    fun start(
        context: Context,
        emulator: Boolean,
        destination: LatLng? = null,
        initialPathId: Int? = null,
        waypoints: List<NaviWaypoint> = emptyList()
    ) {
        // 重入守卫：导航中再次 start（换目的地）先收旧会话，否则引擎忽略 startNavi
        if (this.navi != null) stop()
        val navi = AMapNavi.getInstance(context.applicationContext)
        this.navi = navi
        _active.value = true
        this.destination = destination
        this.emulatorNavi = emulator
        appContext = context.applicationContext
        this._waypoints.value = waypoints
        _strategy.value = com.amapilot.settings.Prefs.routeStrategyInt(context)
        attach(navi)
        // 导入地图页已规划的多路线（未触发新算路回调时）
        if (_routeOptions.value.isEmpty()) {
            importRouteOptions(navi)
        }
        // 导航开始前选中指定路线（引擎约束：startNavi 后 selectRouteId 无效）
        initialPathId?.let { id ->
            if (id != _selectedPathId.value && navi.selectRouteId(id)) {
                _selectedPathId.value = id
            }
            refreshRoutePoints()
        }
        VoiceSpeaker.start(context)
        appContext = context.applicationContext
        GuidanceController.reset()
        navi.setMultipleRouteNaviMode(true)
        val started = navi.startNavi(if (emulator) NaviType.EMULATOR else NaviType.GPS)
        naviRunning = started
        // v11 引擎 GPS 不随 startNavi 隐式启动（EMULATOR 走引擎模拟不启 GPS）
        if (!emulator) {
            val gps = runCatching { navi.startGPS() }.getOrDefault(false)
            android.util.Log.i(
                "NaviSession",
                "startGPS returned=$gps ready=${runCatching { navi.isGpsReady }.getOrDefault(false)}"
            )
        }
        android.util.Log.i(
            "NaviSession",
            "startNavi(${"EMULATOR".takeIf { emulator } ?: "GPS"}) returned=$started " +
                "selected=${_selectedPathId.value} internal=$selectedInternalId " +
                "paths=${navi.naviPaths.keys} " +
                "internalIds=${navi.naviPaths.values.map { it.pathid }}"
        )
    }

    /** 从引擎重读备选路线（多路径模式下随行驶更新）；不改变当前选中路线。 */
    fun refreshRouteData() {
        val navi = this.navi ?: return
        importRouteOptions(navi)
    }

    private fun importRouteOptions(navi: AMapNavi) {
        _routePaths.value = navi.naviPaths.entries.associate { (id, path) ->
            id to (path.coordList?.map { LatLng(it.latitude, it.longitude) } ?: emptyList())
        }
        _routeOptions.value = navi.naviPaths.entries
            .sortedBy { it.key }
            .map { (id, path) ->
                NaviRouteOption(
                    pathId = id,
                    lengthMeters = path.allLength,
                    durationSeconds = path.allTime,
                    trafficLights = path.trafficLightCount
                )
            }
        // 当前选中路线仍存在则保留，否则取引擎默认（多路线重算后 ids[0]）
        if (_selectedPathId.value == null ||
            _routeOptions.value.none { it.pathId == _selectedPathId.value }
        ) {
            _selectedPathId.value = _routeOptions.value.firstOrNull()?.pathId
        }
        refreshRoutePoints()
    }

    /** 加入途经点（追加到队尾）并重算路线。 */
    fun addWaypoint(name: String, latLng: LatLng) {
        _waypoints.value = _waypoints.value + NaviWaypoint(name, latLng.latitude, latLng.longitude)
        recalculate()
    }

    fun removeWaypoint(waypoint: NaviWaypoint) {
        _waypoints.value = _waypoints.value - waypoint
        recalculate()
    }

    /**
     * 切换到指定路线。
     *
     * 导航中换路 = 从当前位 `recalculate`（唯一存活通道：`selectRouteId` 返回 true
     * 但引导流不换，`selectMainPathID` 只发一拍数据即停死）。面板行刷新为车位锚定
     * 的新选项，[pathId] 仅表示换路意图；结果几乎相同（len<5% 且 time<10%）或选项
     * <2 时轮换一档策略再算一次。
     *
     * 导航前维持 `selectRouteId` 语义（正常流程走 [start] 的 initialPathId，此分支兜底）。
     */
    fun selectRoute(pathId: Int) {
        val navi = this.navi ?: return
        if (!switching.compareAndSet(false, true)) return
        if (!_active.value || pathId == _selectedPathId.value) {
            if (!_active.value) {
                val ok = runCatching { navi.selectRouteId(pathId) }.getOrDefault(false)
                android.util.Log.d(
                    "NaviSession",
                    "selectRoute(pre-nav) id=$pathId engineAccepted=$ok"
                )
            }
            switching.set(false)
            return
        }
        android.util.Log.i(
            "NaviSession",
            "route switch: recalculate from current position (intent=$pathId)"
        )
        dumpRouteState("reroute-refresh")
        rerouteBaseline = _state.value.routeRemainMeters to
            _state.value.routeRemainSeconds
        reroutePending = true
        recalculate()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            switching.set(false)
        }, 500L)
    }

    private var reroutePending = false

    private var rerouteBaseline: Pair<Int, Int>? = null

    /** 换路收尾：同路线（len<5% 且 time<10%）或选项 <2 时轮换策略重算一次。 */
    private fun settleReroute(navi: AMapNavi) {
        if (!reroutePending) return
        reroutePending = false
        val base = rerouteBaseline
        rerouteBaseline = null
        val newTop = _routeOptions.value.firstOrNull()
        val sameish = base != null && newTop != null && base.first > 0 &&
            kotlin.math.abs(newTop.lengthMeters - base.first) < base.first * 0.05 &&
            base.second > 0 &&
            kotlin.math.abs(newTop.durationSeconds - base.second) < base.second * 0.10
        val tooFew = _routeOptions.value.size < 2
        if (!sameish && !tooFew) {
            dumpRouteState("reroute-settled")
            return
        }
        val rotated = runCatching {
            // 轮换一档：当前为避堵则换不走高速，否则换避堵
            if (_strategy.value == navi.strategyConvert(true, false, false, false, false)) {
                navi.strategyConvert(false, true, false, false, false)
            } else {
                navi.strategyConvert(true, false, false, false, false)
            }
        }.getOrNull()
        android.util.Log.i(
            "NaviSession",
            "reroute: " + when {
                tooFew -> "fresh set has ${_routeOptions.value.size} option"
                else -> "same route (len=${newTop?.lengthMeters} vs ${base?.first})"
            } + (if (rotated != null && rotated != _strategy.value)
                " -> rotate strategy to $rotated and retry once" else "")
        )
        if (rotated != null && rotated != _strategy.value) {
            recalculate(rotated)
        } else {
            dumpRouteState("reroute-settled")
        }
    }

    /** 实验探针对外入口（车机选路打开时 dump；见 [dumpRouteState]）。 */
    fun dumpRouteStatePublic(tag: String) = dumpRouteState(tag)

    /** 实验探针：备选路径锚点/总量 + 引擎实时剩余（导航中换路重锚实验用）。 */
    private fun dumpRouteState(tag: String) {
        val navi = this.navi ?: return
        val s = _state.value
        val paths = navi.naviPaths.entries.joinToString { (k, p) ->
            val c = p.coordList
            "[$k len=${p.allLength}m t=${p.allTime}s pts=${c?.size ?: 0} " +
                "first=(${c?.firstOrNull()?.latitude},${c?.firstOrNull()?.longitude}) " +
                "last=(${c?.lastOrNull()?.latitude},${c?.lastOrNull()?.longitude}) " +
                "internal=${p.pathid}]"
        }
        android.util.Log.i(
            "NaviSession",
            "routeState $tag: remain=${s.routeRemainMeters}m/${s.routeRemainSeconds}s " +
                "step=${s.stepRemainMeters}m paths{$paths}"
        )
    }

    /**
     * 到达目的地 → 展示 2s 到达态后收导航并拉起巡航（[CruiseSession.start] 幂等）；
     * 手机/车机共享本单例，单一所有者避免双份调度。仅 arrived 触发。
     */
    private fun scheduleArrivalCruise() {
        arrivalJob?.cancel()
        val ctx = appContext ?: return
        arrivalJob = scope.launch {
            kotlinx.coroutines.delay(ARRIVED_CRUISE_DELAY_MS)
            if (!_state.value.arrived) return@launch
            val emu = emulatorNavi
            android.util.Log.i("NaviSession", "arrived -> stop navi + auto cruise")
            stop()
            runCatching { CruiseSession.start(ctx, emulator = emu) }
                .onFailure {
                    android.util.Log.e("NaviSession", "auto cruise after arrival failed", it)
                }
        }
    }

    /** 途经点/策略变更后重算路线（途经点按加入顺序）；strategy null 保持当前。 */
    fun recalculate(strategy: Int? = null) {
        val navi = this.navi ?: return
        val dest = destination ?: run {
            android.util.Log.w("NaviSession", "recalculate bail: destination null")
            return
        }
        // 位置回退系统 GPS（引擎位置流可能冻死/未达），否则加途经点静默无响应
        val cur = location.value?.coord?.let { LatLng(it.latitude, it.longitude) }
            ?: com.amapilot.map.SystemLocations.lastKnownGcj(appContext ?: return)
        val from = cur?.let { NaviLatLng(it.latitude, it.longitude) } ?: run {
            android.util.Log.w("NaviSession", "recalculate bail: no location")
            return
        }
        strategy?.let { _strategy.value = it }
        android.util.Log.i(
            "NaviSession",
            "recalculate from=${cur.latitude},${cur.longitude} dest=${dest.latitude}," +
                "${dest.longitude} waypoints=${waypoints.value.size} strategy=${_strategy.value}"
        )
        // 4 参重载实际语义 (from, to, wayPoints, strategy)
        navi.calculateDriveRoute(
            listOf(from),
            listOf(NaviLatLng(dest.latitude, dest.longitude)),
            waypoints.value.map { NaviLatLng(it.lat, it.lng) },
            _strategy.value
        )
    }

    /** 停止导航并复位状态。 */
    fun stop() {
        arrivalJob?.cancel()
        arrivalJob = null
        listener?.let { navi?.removeAMapNaviListener(it) }
        listener = null
        navi?.let { n ->
            runCatching { n.stopNavi() }
            // v11 引擎 GPS 不随 stopNavi 自收 → 对称 stopGPS（到达巡航/下次导航
            // 各自 startGPS，交接安全）
            runCatching { n.stopGPS() }
        }
        navi = null
        naviRunning = false
        _active.value = false
        pointLimitKmh = 0
        zone = null
        zoneSawApproach = false
        _state.value = NaviState()
        _waypoints.value = emptyList()
        // routePoints 置 null 驱动车机 Presentation 重绘，清除常驻地图上的路线残留
        _routePoints.value = null
        _routeTraffic.value = emptyList()
        _routeLinkStarts.value = IntArray(0)
        _routePaths.value = emptyMap()
        _routeOptions.value = emptyList()
        _selectedPathId.value = null
        selectedInternalId = 0L
        destination = null
        VoiceSpeaker.shutdown()
        GuidanceController.reset()
        appContext = null
    }

    private fun attach(navi: AMapNavi) {
        listener?.let { navi.removeAMapNaviListener(it) }
        val l = object : SimpleNaviListener() {
            override fun onInitNaviSuccess() {
                android.util.Log.i("NaviSession", "engine onInitNaviSuccess")
            }

            override fun onInitNaviFailure() {
                android.util.Log.e("NaviSession", "engine onInitNaviFailure")
            }

            override fun onStartNavi(type: Int) {
                android.util.Log.i("NaviSession", "engine onStartNavi type=$type")
            }

            override fun onEndEmulatorNavi() {
                android.util.Log.i("NaviSession", "engine onEndEmulatorNavi")
            }

            override fun onCalculateRouteSuccess(ids: IntArray) {
                android.util.Log.i(
                    "NaviSession",
                    "calculateRouteSuccess ids=${ids.toList()} selected=${_selectedPathId.value}"
                )
                if (ids.isNotEmpty() && ids.first() != _selectedPathId.value) {
                    _selectedPathId.value = ids.first()
                }
                importRouteOptions(navi)
                // 换路收尾（同路线/选项不足时轮换策略重试一次）；非换路重算为空操作
                settleReroute(navi)
                // 幽灵导航自愈：startNavi 曾被拒（引擎无路线竞态）时在此补启，
                // 否则 isActive 悬空零值
                if (_active.value && !naviRunning) {
                    val retry = runCatching {
                        navi.startNavi(
                            if (emulatorNavi) NaviType.EMULATOR else NaviType.GPS
                        )
                    }.getOrDefault(false)
                    naviRunning = retry
                    android.util.Log.i("NaviSession", "late startNavi returned=$retry")
                }
            }

            override fun onCalculateRouteFailure(error: Int) {
                android.util.Log.w("NaviSession", "calculateRouteFailure error=$error")
                // 悬空态（active 但引擎未起）算路失败 → 收会话退出幽灵导航；
                // 导航中重算失败 running=true 不受影响
                if (_active.value && !naviRunning) {
                    runCatching { stop() }.onFailure {
                        android.util.Log.w("NaviSession", "stop after calc failure failed", it)
                    }
                }
            }

            override fun onNaviInfoUpdate(info: NaviInfo) {
                // 多路线串扰防护：只消费属于当前选中路线的 info（NaviInfo.pathId
                // 是引擎内部 long id，须经 [selectedInternalPathId] 映射；pathId=0 放行）
                val internal = selectedInternalId
                if (info.pathId != 0L && internal != 0L && info.pathId != internal) {
                    android.util.Log.w(
                        "NaviSession",
                        "naviInfo DROPPED pathId=${info.pathId} != selectedInternal=$internal"
                    )
                    return
                }
                val linksAhead = linksAheadInStep(info)
                val linkRemain = info.curLinkRetainDistance
                // 诊断（一次性）：计数判途中但距离说明车道条路口即机动点
                if (!laneCountAnomalyLogged && linksAhead >= 2 && linkRemain >= 0 &&
                    info.curStepRetainDistance <= linkRemain +
                    laneJunctionToleranceM(activeLaneDir(info.iconType))
                ) {
                    laneCountAnomalyLogged = true
                    android.util.Log.i(
                        "NaviSession",
                        "lane junction==maneuver by distance (stepRemain=" +
                            "${info.curStepRetainDistance} linkRemain=$linkRemain) " +
                            "but linksAhead=$linksAhead — count heuristic would misguide"
                    )
                }
                _state.value = _state.value.copy(
                    iconType = info.iconType,
                    actionText = actionText(info.iconType),
                    nextRoad = info.nextRoadName.orEmpty(),
                    stepRemainMeters = info.curStepRetainDistance,
                    routeRemainMeters = info.pathRetainDistance,
                    remainPathId = info.pathId,
                    routeRemainSeconds = info.pathRetainTime,
                    lightCount = info.routeRemainLightCount,
                    linksToManeuver = linksAhead,
                    linkRemainMeters = linkRemain,
                    stepIndex = info.curStep,
                    stepRemainSeconds = info.curStepRetainTime,
                    currentRoad = info.currentRoadName.orEmpty(),
                    speedKmh = info.currentSpeed // NaviInfo.currentSpeed 已是 km/h
                )
                // 简洁引导合成（详细模式内部静默；安全/终点类引擎文本不受影响）
                GuidanceController.onState(
                    _state.value,
                    appContext?.let { com.amapilot.settings.Prefs.isVoiceConcise(it) } ?: false
                ).forEach { VoiceSpeaker.speakForce(it) }
            }

            // 引擎广播文本（自研 TBT 的语音播报源）
            override fun onGetNavigationText(text: String?) {
                VoiceSpeaker.speak(text)
            }

            override fun onGetNavigationText(type: Int, text: String?) {
                VoiceSpeaker.speak(text)
            }

            override fun updateCameraInfo(cameras: Array<com.amap.api.navi.model.AMapNaviCameraInfo>?) {
                updateLimit(cameras)
            }

            override fun updateIntervalCameraInfo(
                startCamera: com.amap.api.navi.model.AMapNaviCameraInfo?,
                endCamera: com.amap.api.navi.model.AMapNaviCameraInfo?,
                status: Int
            ) {
                updateIntervalZone(startCamera, endCamera, status)
            }

            override fun onLocationChange(location: AMapNaviLocation?) {
                location?.let { _location.value = it }
            }

            override fun onArriveDestination() {
                _state.value = _state.value.copy(arrived = true)
                scheduleArrivalCruise()
            }

            // 前方路口车道引导：推送各车道箭头+推荐车道；离开路口清空
            override fun showLaneInfo(
                laneInfo: Array<AMapLaneInfo>?,
                b: ByteArray?,
                b1: ByteArray?
            ) {
                val entries = laneInfo?.toList().orEmpty()
                logLanes(laneInfo)
                logLaneContext("show-array")
                if (entries.isEmpty()) return
                val groupBg = pendingLaneBg
                _lanes.value = entries.mapIndexed { i, l ->
                    // 优先用每车道 backgroundLane；否则用单条回调带来的整组方向按车道拆分
                    val actions = l.backgroundLane?.toList()
                        ?: groupBg?.getOrNull(i)?.let { listOf(it) }
                        ?: emptyList()
                    LaneCell(actions, l.isRecommended)
                }
            }

            override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
                if (laneInfo == null) return
                logLanes(arrayOf(laneInfo))
                val bg = laneInfo.backgroundLane ?: return
                pendingLaneBg = bg
                // 单条回调的 backgroundLane 是整组车道方向，按车道拆成多格
                _lanes.value = bg.map { LaneCell(listOf(it), laneInfo.isRecommended) }
            }

            // 引擎路线通知（备选路线刷新/路况重算提示）：重导入保持选路数据实时
            override fun onNaviRouteNotify(
                data: com.amap.api.navi.model.AMapNaviRouteNotifyData?
            ) {
                android.util.Log.i(
                    "NaviSession",
                    "route notify: type=${data?.notifyType} success=${data?.isSuccess}"
                )
                navi?.let { importRouteOptions(it) }
            }

            // 车道上下文日志（去重）：距离判定现场（机动点/路口 + 计数兜底 + 裁决）
            private var laneCtxLast: String = ""
            private fun logLaneContext(where: String) {
                val s = _state.value
                val tol = laneJunctionToleranceM(activeLaneDir(s.iconType))
                val verdict = if (s.linkRemainMeters >= 0) {
                    if (s.stepRemainMeters <= s.linkRemainMeters + tol
                    ) "maneuver" else "intermediate"
                } else "fallback(count=${s.linksToManeuver})"
                val key = "$where|$verdict|${s.linksToManeuver}|${s.stepRemainMeters / 50}"
                if (key == laneCtxLast) return
                laneCtxLast = key
                android.util.Log.d(
                    "NaviSession",
                    "laneCtx $where verdict=$verdict step=${s.stepRemainMeters}m " +
                        "link=${s.linkRemainMeters}m count=${s.linksToManeuver} " +
                        "tol=${tol}m icon=${s.actionText}"
                )
            }

            override fun hideLaneInfo() {
                logLaneContext("hide")
                pendingLaneBg = null
                _lanes.value = emptyList()
            }
        }
        listener = l
        navi.addAMapNaviListener(l)
    }

    /** 当前 step 内剩余 link 数（link 边界≈路口）；取不到时 1。 */
    private fun linksAheadInStep(info: NaviInfo): Int = runCatching {
        val steps = navi?.naviPath?.steps ?: return@runCatching 1
        val startLink = steps.take(info.curStep).sumOf { it.links?.size ?: 0 }
        val inStep = steps.getOrNull(info.curStep)?.links?.size ?: 0
        ((startLink + inStep) - info.curLink).coerceAtLeast(1)
    }.getOrDefault(1)

    /** 打印每车道 backgroundLane/frontLane/laneTypeId/推荐（调试潮汐/可变车道编码）。 */
    private fun logLanes(lanes: Array<AMapLaneInfo>?) {        lanes?.forEachIndexed { i, l ->
            android.util.Log.d(
                "NaviSession",
                "lane[$i] bg=${l.backgroundLane?.toList()} front=${l.frontLane?.toList()} " +
                    "typeId=${l.getLaneTypeIdArray()?.concatToString()} rec=${l.isRecommended}"
            )
        }
    }

    /**
     * 从电子眼数据提取单点限速（km/h）。仅单点测速类型进池（区间类型 8/9 走
     * [updateIntervalZone] 专用回调管理；无区间态时的终点类型 = 中途汇入扫到的
     * 出口，其限速仍应生效）。`getCameraSpeed()` 为官方推荐取值——已废弃的
     * `getSpeed()` 数组仅在区间类型才有值，旧实现单点限速实际取不到。
     */
    private fun updateLimit(cameras: Array<com.amap.api.navi.model.AMapNaviCameraInfo>?) {
        val limit = cameras
            ?.firstOrNull { c ->
                (c.cameraType == CameraType.SPEED ||
                    (c.cameraType == CameraType.INTERVALVELOCITYEND && zone == null)) &&
                    c.cameraSpeed > 0
            }
            ?.cameraSpeed ?: return
        pointLimitKmh = limit
        publishLimit()
    }

    /**
     * 区间测速专用回调（引擎 javadoc：第三参为 [CarEnterCameraStatus] 而非距离）。
     * 边界：中途汇入（startCamera=null）不建区间语义、出口限速降级普通池；
     * 驶出（LEAVE，含中途驶出）一律清除——部分路程的均值/剩余会误导。
     */
    private fun updateIntervalZone(
        startCamera: com.amap.api.navi.model.AMapNaviCameraInfo?,
        endCamera: com.amap.api.navi.model.AMapNaviCameraInfo?,
        status: Int,
    ) {
        when {
            startCamera == null -> {
                // 中途汇入只扫到出口：无起点基准，区间均值无意义 → 不建区间态
                clearZone(publish = false)
                endCamera?.cameraSpeed?.takeIf { it > 0 }?.let { pointLimitKmh = it }
                publishLimit()
            }
            status == CarEnterCameraStatus.LEAVE -> clearZone()
            status == CarEnterCameraStatus.ENTER || status == CarEnterCameraStatus.UNENTER -> {
                if (status == CarEnterCameraStatus.UNENTER) zoneSawApproach = true
                zone = SpeedZone(
                    limitKmh = startCamera.cameraSpeed.takeIf { it > 0 } ?: 0,
                    // 均值仅 ENTER 态可能有效；UNENTER 期间引擎未开始跟踪
                    averageKmh = if (status == CarEnterCameraStatus.ENTER) {
                        startCamera.averageSpeed.takeIf { it > 0 } ?: -1
                    } else -1,
                    totalMeters = startCamera.distance.takeIf { it > 0 } ?: -1,
                    remainMeters = startCamera.intervalRemainDistance.takeIf { it > 0 } ?: -1,
                    inside = status == CarEnterCameraStatus.ENTER,
                    fullEntry = zoneSawApproach,
                )
                publishLimit()
            }
            else -> clearZone() // UNKNOWN(0)
        }
    }

    /** 清除区间态（限速回落单点池）。 */
    private fun clearZone(publish: Boolean = true) {
        zone = null
        zoneSawApproach = false
        if (publish) publishLimit()
    }

    /** 生效限速路由：区间激活时用区间限速（车速环超速判定单一值，视觉不区分来源），
     *  清除后回落单点池；speedZone 随之同源发布。 */
    private fun publishLimit() {
        val effective = zone?.limitKmh?.takeIf { it > 0 } ?: pointLimitKmh
        _state.value = _state.value.copy(limitKmh = effective, speedZone = zone)
    }

    /** [IconType] 到中文动作文本。 */
    internal fun actionText(iconType: Int): String = when (iconType) {
        IconType.LEFT -> "左转"
        IconType.RIGHT -> "右转"
        IconType.LEFT_FRONT -> "左前方"
        IconType.RIGHT_FRONT -> "右前方"
        // 左后方/右后方按左转/右转播报与显示（与图标侧一致）
        IconType.LEFT_BACK -> "左转"
        IconType.RIGHT_BACK -> "右转"
        IconType.LEFT_TURN_AROUND -> "掉头"
        IconType.STRAIGHT -> "直行"
        IconType.ENTER_ROUNDABOUT -> "进入环岛"
        IconType.OUT_ROUNDABOUT -> "驶出环岛"
        IconType.ARRIVED_DESTINATION -> "到达终点"
        IconType.ARRIVED_WAYPOINT -> "到达途经点"
        else -> ""
    }
}
