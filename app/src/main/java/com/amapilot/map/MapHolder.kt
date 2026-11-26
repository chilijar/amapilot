package com.amapilot.map

import android.content.Context
import android.view.ViewGroup
import com.amap.api.maps.MapView

/**
 * 进程级共享 MapView：高德引擎在 [MapView.onDestroy] 后重建极慢（模拟器 40~90s，
 * 期间只有灰底）。各屏经 [acquire] 复用同一实例，离屏绝不 onDestroy，仅
 * MainActivity 销毁时 [destroy]；挂载前用 [reset] 摘旧父容器并清空上一屏覆盖物/监听。
 */
object MapHolder {

    @Volatile
    private var instance: MapView? = null

    /** 获取共享 [MapView]（首次创建，applicationContext 避免持有 Activity）。 */
    fun acquire(context: Context): MapView =
        instance ?: synchronized(this) {
            instance ?: MapView(context.applicationContext).apply { onCreate(null) }
                .also { instance = it }
        }

    /** AndroidView factory 中调用：清空上一屏覆盖物与全局监听后返回实例。 */
    fun reset(mapView: MapView): MapView {
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        // 车机 Presentation 的几何缩放（MapView scale 0.6）不复用到手机屏
        mapView.scaleX = 1f
        mapView.scaleY = 1f
        mapView.map.apply {
            clear()
            setOnMapTouchListener(null)
            setOnMyLocationChangeListener(null)
        }
        return mapView
    }

    /** MainActivity 销毁时回收引擎；其余场景一律不调用。 */
    fun destroy() {
        synchronized(this) {
            instance?.onDestroy()
            instance = null
        }
    }

    /** 暂停渲染（Activity 不可见时调用）：消除后台 GL 空转与全局渲染锁争抢。 */
    @Volatile
    private var paused = false

    fun pause() {
        paused = true
        runCatching { instance?.onPause() }
    }

    fun resume() {
        if (!paused) return
        paused = false
        runCatching { instance?.onResume() }
    }

    /** 当前是否处于暂停态（车机地图引擎可据此避免同进程双引擎同时渲染）。 */
    fun isPaused(): Boolean = paused
}

/** 带时长的相机动画（用户触发切换用；逐帧跟随仍用 moveCamera）。
 *  no-op 回调规避 SDK 各版本对 null callback 的差异。 */
fun com.amap.api.maps.AMap.animateCameraTo(
    update: com.amap.api.maps.CameraUpdate,
    durationMs: Long
) {
    animateCamera(update, durationMs, object : com.amap.api.maps.AMap.CancelableCallback {
        override fun onFinish() {}
        override fun onCancel() {}
    })
}

/** 两坐标是否相距较远（经纬度差和 > 0.5° ≈ 50km）：远 → 瞬移防跨省飞行，
 *  近 → 交给 animateCameraTo 做视角过渡。 */
fun farApart(
    a: com.amap.api.maps.model.LatLng,
    b: com.amap.api.maps.model.LatLng
): Boolean =
    kotlin.math.abs(a.latitude - b.latitude) +
        kotlin.math.abs(a.longitude - b.longitude) > 0.5
