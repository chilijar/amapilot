package com.amapilot.map

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.amap.api.location.AMapLocation
import com.amap.api.maps.LocationSource

/**
 * 系统 LocationManager 聚合助手：注册全部可用 provider（GPS/网络/FUSED），
 * 室内网络兜底、室外 GPS 精准；激活时回放 lastKnownLocation 快速出首个位置。
 */
object SystemLocations {

    /** 定位权限自检（FINE 或 COARSE 任一即可），兜底权限被撤销的竞态。 */
    private fun hasLocationPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 向全部启用中的 provider 注册位置更新（排除 passive）。
     * 入口经 [hasLocationPermission] 自检，lint 不识别自定义守卫函数，故 SuppressLint。
     * @param seed 是否回放 lastKnownLocation（蓝点秒出）；做速度/里程统计应传 false——
     * 种子位置时间戳旧，与首个实时 fix 的位移差会被误算成瞬时速度
     * @return 是否至少注册成功一个 provider
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun request(context: Context, listener: LocationListener, seed: Boolean = true): Boolean {
        if (!hasLocationPermission(context)) return false
        val lm =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .filter { it != LocationManager.PASSIVE_PROVIDER }
            .ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
        var registered = false
        providers.forEach { provider ->
            // 延迟回放缓存位置：activate() 同步期 AMap 图层未就绪会丢弃种子
            if (seed) {
                runCatching { lm.getLastKnownLocation(provider) }
                    .getOrNull()?.let { cached ->
                        android.os.Handler(context.mainLooper).postDelayed({
                            runCatching { listener.onLocationChanged(cached) }
                        }, 600L)
                    }
            }
            runCatching {
                lm.requestLocationUpdates(provider, 1000L, 1f, listener, context.mainLooper)
                registered = true
            }
        }
        return registered
    }

    /** 取最新 lastKnownLocation 并转 GCJ-02（切屏相机预设用，避免先跳引擎默认
     *  北京再跳回的闪变）。权限守卫同 [request]。 */
    @android.annotation.SuppressLint("MissingPermission")
    fun lastKnownGcj(context: Context): com.amap.api.maps.model.LatLng? {
        if (!hasLocationPermission(context)) return null
        val lm =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .filter { it != LocationManager.PASSIVE_PROVIDER }
        val best = providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time } ?: return null
        val gcj = com.amapilot.share.CoordConverter.wgs84ToGcj02(
            best.latitude, best.longitude
        )
        return com.amap.api.maps.model.LatLng(gcj.lat, gcj.lng)
    }

    /** 注销位置更新（任意 context 拿到的 LocationManager 为同一实例）。 */
    fun cancel(context: Context, listener: LocationListener) {
        val lm =
            context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(listener) }
    }

    /** 系统 [Location] 转高德 [AMapLocation]：WGS84 → GCJ-02 必须转换，
     *  否则国内有数百米系统性偏移。 */
    fun toAMapLocation(location: Location): AMapLocation {
        val gcj = com.amapilot.share.CoordConverter.wgs84ToGcj02(
            location.latitude, location.longitude
        )
        return AMapLocation("sys").apply {
            latitude = gcj.lat
            longitude = gcj.lng
            accuracy = location.accuracy
            speed = location.speed
            bearing = location.bearing
            time = location.time
            elapsedRealtimeNanos = location.elapsedRealtimeNanos
            locationType = AMapLocation.LOCATION_TYPE_GPS
        }
    }
}

/**
 * 地图定位源：系统多 provider 聚合经 [LocationSource] 驱动地图定位图层，
 * 绕开 AMap 定位 SDK 的网络定位与鉴权。
 */
fun systemGpsSource(context: Context): LocationSource {
    val appContext = context.applicationContext
    return object : LocationSource {
        private var listener: LocationSource.OnLocationChangedListener? = null
        private val sysListener = LocationListener { location ->
            listener?.onLocationChanged(SystemLocations.toAMapLocation(location))
        }

        override fun activate(onLocationChangedListener: LocationSource.OnLocationChangedListener) {
            listener = onLocationChangedListener
            SystemLocations.request(appContext, sysListener)
        }

        override fun deactivate() {
            SystemLocations.cancel(appContext, sysListener)
            listener = null
        }
    }
}
