package com.amapilot.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.amapilot.navigation.CruiseSession
import com.amapilot.navigation.NaviSession

/**
 * 车机会话：提供首屏，负责手机端与车机端的状态同步。无导航且无巡航时自动
 * 开始巡航（车机默认展示车速/电子眼），车机断开时由
 * [NavigationCarAppService.onDestroy] 回收。
 */
class NavigationSession : Session() {

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        // 车辆昼夜切换（真车 = 大灯/车机设置；DHU = day/night 命令）→ 底图跟随
        val dark = (newConfiguration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        android.util.Log.i("CarAppService", "car configuration changed -> dark=$dark")
        CarMapControl.carDarkMode.value = dark
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // 连接态只由生命周期 ON_START/ON_STOP 维护：宿主 push SearchTemplate 会回收
        // 地图 Surface，辅助态挂 Surface 会断手机输入通道；传输断开后会话仍被宿主
        // 保留（STOPPED）并在 rebind 时重走 onCreateScreen，无条件置 true = 误留辅助态
        android.util.Log.i("CarAppService", "session onCreateScreen (await ON_START)")
        // 车辆昼夜初值（DHU 可用 day/night 命令模拟）
        CarMapControl.carDarkMode.value = carContext.isDarkMode
        android.util.Log.i(
            "CarAppService",
            "session car dark mode initial = ${CarMapControl.carDarkMode.value}"
        )
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            // 车机断开时宿主只派 ON_PAUSE/ON_STOP 并保留会话等重连，ON_DESTROY 与
            // Service.onDestroy 均不达；辅助态必须挂 ON_STOP/ON_START，否则断开后卡死
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                android.util.Log.i("CarAppService", "session lifecycle ON_START -> carConnected=true")
                CarMapControl.carConnected.value = true
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                android.util.Log.i("CarAppService", "session lifecycle ON_STOP -> carConnected=false")
                CarMapControl.carConnected.value = false
            }

            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                android.util.Log.i("CarAppService", "session lifecycle ON_DESTROY")
                CarMapControl.carConnected.value = false
                CarMapControl.carDarkMode.value = null
            }
        })
        if (!NaviSession.isActive && !CruiseSession.isActive && !autoCruiseStarted) {
            autoCruiseStarted = true
            // 延后启动：让宿主 bind/首屏渲染先完成（引擎初始化争抢 CPU 会致 bind ANR）
            android.os.Handler(carContext.mainLooper).postDelayed({
                Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    // 恒用真实 CRUISE 模式（无起终点/不算路/无网络）；EMULATOR 依赖高德云算路
                    runCatching { CruiseSession.start(carContext, emulator = false) }
                }.start()
            }, 4000L)
        }
        return NavigationScreen(carContext)
    }

    companion object {
        /** 本次连接是否已自动开启过巡航（由 Service.onDestroy 复位）。 */
        internal var autoCruiseStarted = false
    }
}
