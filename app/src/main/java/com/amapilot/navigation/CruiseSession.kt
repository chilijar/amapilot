package com.amapilot.navigation

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AimlessModeListener
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 前方电子眼提醒。 */
data class CameraAlert(
    val type: Int,
    val distanceMeters: Int,
    val limitKmh: Int
)

/** 前方拥堵信息。 */
data class CongestionAlert(
    val roadName: String,
    val lengthMeters: Int,
    val status: Int
)

/**
 * 巡航状态（手机巡航屏与 Android Auto 共用）。
 * @property limitKmh 最近电子眼限速（0 未知）；里程/时长均为本次巡航累计。
 */
data class CruiseState(
    val speedKmh: Int = 0,
    val limitKmh: Int = 0,
    val roadName: String = "",
    val cameras: List<CameraAlert> = emptyList(),
    val congestion: CongestionAlert? = null,
    val distanceMeters: Int = 0,
    val durationSeconds: Int = 0
)

/** 巡航会话：无起终点导航（[AMapNavi.startAimlessMode]），聚合电子眼/拥堵/统计
 *  回调为 [StateFlow]，供巡航屏与 Android Auto 共用。 */
object CruiseSession {

    private val _state = MutableStateFlow(CruiseState())
    val state: StateFlow<CruiseState> = _state

    val isActive: Boolean
        get() = navi != null

    /** 会话激活态（可观察）：同 NaviSession.activeFlow，避免状态流同值合流。 */
    private val _active = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> = _active

    /** 巡航引擎位置（驱动地图定位层）。 */
    private val _location = MutableStateFlow<AMapNaviLocation?>(null)
    val location: StateFlow<AMapNaviLocation?> = _location

    private var navi: AMapNavi? = null
    private var aimlessListener: AimlessModeListener? = null
    private var naviListener: SimpleNaviListener? = null
    private var locationManager: android.location.LocationManager? = null
    private var gpsInjector: android.location.LocationListener? = null
    private var statsScope: kotlinx.coroutines.CoroutineScope? = null
    private var pendingEmulator = false
    private var startedAt: Long = 0

    /** 真机路径：速度独家取系统 GPS（m/s→km/h 单位确定）；EMULATOR 取 NaviInfo。 */
    private var speedFromGps = false

    /** 引擎位置流最近一拍时间（0=从未）：活跃时系统 GPS 让位，防双写交替跳。 */
    private var lastEngineLocAt = 0L

    /** 最近一次精定位（gps/fused）时间：活跃期内忽略 network 粗定位 fix。 */
    private var lastFineLocAt = 0L

    /**
     * 开始巡航（幂等：已进行中则沿用，浏览页 → 全屏移交不重启引擎）。
     * 真机 `startNavi(CRUISE)` 电子眼模式；模拟器被海外权益拦截 → 先算路再 `EMULATOR`。
     */
    fun start(context: Context, emulator: Boolean = false) {
        if (navi != null) return
        val appContext = context.applicationContext
        val navi = AMapNavi.getInstance(appContext)
        this.navi = navi
        _active.value = true
        attach(navi)
        startedAt = System.currentTimeMillis()

        if (emulator) {
            pendingEmulator = true
            val from = com.amap.api.navi.model.NaviLatLng(39.90923, 116.397428)
            val to = com.amap.api.navi.model.NaviLatLng(39.9075, 116.47)
            navi.calculateDriveRoute(
                listOf(from),
                listOf(to),
                emptyList(),
                PathPlanningStrategy.DRIVING_DEFAULT
            )
        } else {
            // 真机：引擎 CRUISE 模式（电子眼回调）+ 系统 GPS 驱动位置/箭头/注入引擎。
            // startNavi 异常不得阻断 GPS 注册（否则箭头/统计全无）
            runCatching { navi.startNavi(com.amap.api.navi.enums.NaviType.CRUISE) }
                .onFailure { android.util.Log.w("CruiseSession", "startNavi(CRUISE) failed", it) }
            // v11 引擎 GPS 不随 startNavi 隐式启动：不显式 startGPS 则 CRUISE 态
            // 引擎位置流/NaviInfo 全哑（真机车速恒 0、地图只剩 1Hz GPS 跳步）
            val gpsStarted = runCatching { navi.startGPS() }.getOrDefault(false)
            android.util.Log.i("CruiseSession", "startNavi(CRUISE) startGPS returned=$gpsStarted")
            speedFromGps = true
            registerGpsStats(appContext, navi)
        }

        statsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).also {
            it.launch {
                var lastAt = 0L
                var accDist = 0
                var prev: Pair<Double, Double>? = null
                location.collect { loc ->
                    val c = loc?.coord ?: return@collect
                    val now = System.currentTimeMillis()
                    val dt = if (lastAt > 0) (now - lastAt) / 1000.0 else 0.0
                    prev?.let { (pLat, pLng) ->
                        val step = haversineMeters(pLat, pLng, c.latitude, c.longitude)
                        // 常规间隔回调才累计里程（毫秒级连发的 lastKnown 种子跳变被过滤）
                        if (dt >= 0.1 && step < 5000.0) accDist += step.toInt()
                    }
                    prev = c.latitude to c.longitude
                    // 速度不在此估算（真机 = GPS 监听器，EMULATOR = NaviInfo）；仅统计里程/时长
                    lastAt = now
                    _state.value = _state.value.copy(
                        distanceMeters = accDist,
                        durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    )
                }
            }
        }
    }

    /**
     * 真机路径：系统多 provider（GPS/网络/FUSED）发布位置并注入引擎。仲裁规则：
     * - network 粗定位仅在精定位停更 [COARSE_LOC_BACKOFF_MS] 后兜底（交错写入会大跳）；
     * - 引擎位置流活跃时系统 GPS 只更新速度、位置让位；引擎停更 [ENGINE_LOC_STALE_MS]
     *   才兜底 `_location`（防双写交替跳变）；
     * - 速度独家取系统 GPS（m/s→km/h），不混引擎 NaviInfo。
     */
    private fun registerGpsStats(appContext: Context, navi: AMapNavi) {
        locationManager =
            appContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val listener = android.location.LocationListener { location ->
            val now = System.currentTimeMillis()
            val fine = location.provider == android.location.LocationManager.GPS_PROVIDER ||
                location.provider == FUSED_PROVIDER
            if (fine) {
                lastFineLocAt = now
            } else if (lastFineLocAt != 0L && now - lastFineLocAt < COARSE_LOC_BACKOFF_MS) {
                // 精定位活跃：忽略粗定位 fix（大跳变来源）
                return@LocationListener
            }
            // 速度（真机独家来源）：系统 GPS speed 单位确定 m/s → km/h；CRUISE 态
            // 引擎 NaviInfo.currentSpeed 不可靠（恒 0）。seed 回放的旧 fix 须过滤
            val fresh = android.os.SystemClock.elapsedRealtimeNanos() -
                location.elapsedRealtimeNanos < SPEED_FRESH_NS
            val speedKmh = if (fresh) (location.speed * 3.6f).toInt() else null
            // 引擎位置流活跃：仅更新速度，位置让位（防双写交替跳）
            if (now - lastEngineLocAt < ENGINE_LOC_STALE_MS) {
                speedKmh?.let { v -> _state.value = _state.value.copy(speedKmh = v) }
                return@LocationListener
            }
            // 系统 GPS 为 WGS84，地图/引擎均按 GCJ-02 消费：统一转换
            val gcj = com.amapilot.share.CoordConverter.wgs84ToGcj02(
                location.latitude, location.longitude
            )
            _location.value = com.amap.api.navi.model.AMapNaviLocation().apply {
                coord = com.amap.api.navi.model.NaviLatLng(gcj.lat, gcj.lng)
                this.speed = location.speed
                // 携带 GPS 航向，否则两路位置交替时箭头方向跳变。**仅行驶中同步**
                // （>1.4m/s）：静止时接收机 bearing 漂移不定，保持 0 由消费端兜底
                if (location.bearing in 0.1f..359.9f && location.speed > 1.4f) {
                    this.bearing = location.bearing
                    this.roadBearing = location.bearing
                }
            }
            _state.value = speedKmh?.let { v -> _state.value.copy(speedKmh = v) } ?: _state.value
            val injected = android.location.Location(location).apply {
                latitude = gcj.lat
                longitude = gcj.lng
            }
            try {
                navi.setExtraGPSData(0, injected)
            } catch (_: Exception) {
            }
        }
        // seed=true：回放 lastKnown 让箭头/相机秒出（旧 fix 的过期速度已被过滤）
        if (com.amapilot.map.SystemLocations.request(appContext, listener, seed = true)) {
            gpsInjector = listener
        }
    }

    /** 结束巡航并复位状态。 */
    fun stop() {
        aimlessListener?.let { navi?.removeAimlessModeListener(it) }
        naviListener?.let { navi?.removeAMapNaviListener(it) }
        gpsInjector?.let { listener ->
            try {
                locationManager?.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }
        statsScope?.cancel()
        statsScope = null
        gpsInjector = null
        locationManager = null
        pendingEmulator = false
        speedFromGps = false
        lastEngineLocAt = 0L
        lastFineLocAt = 0L
        aimlessListener = null
        naviListener = null
        navi?.let { n ->
            runCatching { n.stopNavi() }
            // v11 引擎 GPS 不随 stopNavi 自收，须对称 stopGPS 防空转
            runCatching { n.stopGPS() }
        }
        navi = null
        _active.value = false
        _state.value = CruiseState()
    }

    /** 两点球面距离（米）。 */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun attach(navi: AMapNavi) {
        val aimless = object : AimlessModeListener {
            override fun onUpdateTrafficFacility(facilities: Array<AMapNaviTrafficFacilityInfo>?) {
                updateCameras(facilities)
            }

            override fun onUpdateAimlessModeElecCameraInfo(cameras: Array<AMapNaviTrafficFacilityInfo>?) {
                updateCameras(cameras)
            }

            override fun updateAimlessModeStatistics(stat: AimLessModeStat?) {
                stat ?: return
                _state.value = _state.value.copy(
                    distanceMeters = stat.aimlessModeDistance,
                    durationSeconds = stat.aimlessModeTime
                )
            }

            override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) {
                info ?: return
                _state.value = _state.value.copy(
                    congestion = CongestionAlert(
                        roadName = info.roadName.orEmpty(),
                        lengthMeters = info.length,
                        status = info.congestionStatus
                    )
                )
            }
        }
        val simple = object : SimpleNaviListener() {
            override fun onCalculateRouteSuccess(ids: IntArray) {
                if (pendingEmulator) {
                    pendingEmulator = false
                    navi.startNavi(com.amap.api.navi.enums.NaviType.EMULATOR)
                }
            }

            override fun onLocationChange(location: AMapNaviLocation?) {
                location ?: return
                // 引擎平滑（路匹配）位置流活跃标记：系统 GPS 据此让位 _location
                lastEngineLocAt = System.currentTimeMillis()
                _location.value = location
            }

            override fun onNaviInfoUpdate(info: NaviInfo) {
                // NaviInfo.currentSpeed 已是 km/h，勿再乘 3.6。真机 CRUISE 态该值不可靠
                // （恒 0）→ 速度取系统 GPS（见 [registerGpsStats]）；EMULATOR 用引擎值
                _state.value = if (speedFromGps) {
                    _state.value.copy(roadName = info.currentRoadName.orEmpty())
                } else {
                    _state.value.copy(
                        speedKmh = info.currentSpeed,
                        roadName = info.currentRoadName.orEmpty()
                    )
                }
            }
        }
        aimlessListener = aimless
        naviListener = simple
        navi.addAimlessModeListener(aimless)
        navi.addAMapNaviListener(simple)
    }

    private fun updateCameras(cameras: Array<AMapNaviTrafficFacilityInfo>?) {
        val alerts = cameras.orEmpty().map {
            CameraAlert(type = it.type, distanceMeters = it.distance, limitKmh = it.limitSpeed)
        }
        _state.value = _state.value.copy(
            cameras = alerts,
            limitKmh = alerts.firstOrNull { c -> c.limitKmh > 0 }?.limitKmh ?: 0
        )
    }

    /** 引擎位置流停更判定（毫秒）：超过即由系统 GPS 兜底驱动 `_location`。 */
    private const val ENGINE_LOC_STALE_MS = 3000L

    /** 粗定位（network）让位窗口（毫秒）：精定位停更超过此时长才采纳。 */
    private const val COARSE_LOC_BACKOFF_MS = 15000L

    /** fix 新鲜度判定（纳秒）：超过此龄的 fix（seed 回放的 lastKnown）不参与车速。 */
    private const val SPEED_FRESH_NS = 3_000_000_000L

    /** 系统 FUSED provider 名（`LocationManager.FUSED_PROVIDER` 为 API 31+ 常量，字面量全版本安全）。 */
    private const val FUSED_PROVIDER = "fused"
}
