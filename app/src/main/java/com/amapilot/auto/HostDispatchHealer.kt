package com.amapilot.auto

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext

/**
 * 宿主 dispatch 死通道的自愈。
 *
 * 冷启动时宿主会连发两次 onAppCreate，第二次 `resetHosts()` 清掉第一次缓存的
 * IAppHost binder（`getHost("app")` 恒抛异常 → 搜索/设置/选路等模板屏全哑）。
 * 修复：init 时备份健康 binder，会话建立后探测并按需回写 `mAppHost`。
 *
 * 反射只触碰本包捆绑的 androidx.car.app 私有成员，全程 runCatching，失败降级不修复。
 */
internal object HostDispatchHealer {
    private const val TAG = "CarAppHeal"

    private var dispatcher: Any? = null
    private var appHostField: java.lang.reflect.Field? = null
    private var getHostMethod: java.lang.reflect.Method? = null

    /** 会话 init 时备份的健康 IAppHost binder（会话级对象，宿主侧 AppHost 服务本体）。 */
    @Volatile
    private var savedAppHost: Any? = null

    @Volatile
    private var healed = false

    /**
     * 备份当前健康的 mAppHost；须在 [NavigationScreen] init 内、setSurfaceCallback 之后调用。
     */
    fun capture(carContext: CarContext) {
        runCatching {
            val df = CarContext::class.java.getDeclaredField("mHostDispatcher")
            df.isAccessible = true
            val d = df.get(carContext) ?: return
            val hf = d.javaClass.getDeclaredField("mAppHost")
            hf.isAccessible = true
            val gm = d.javaClass.getDeclaredMethod("getHost", String::class.java)
            gm.isAccessible = true
            dispatcher = d
            appHostField = hf
            getHostMethod = gm
            hf.get(d)?.let { savedAppHost = it }
            Log.i(TAG, "capture: dispatcher=$d savedAppHost=${savedAppHost != null}")
        }.onFailure { Log.w(TAG, "capture failed (reflection unavailable)", it) }
    }

    /**
     * 探测一次 IAppHost 通道；死且有备份则回主线程回写修复。须在后台线程调用
     * （getHost 是同步 binder 调用）。
     *
     * @return 是否仍处于死通道且未修复（onHealed 在主线程回调）
     */
    fun probeAndHeal(onHealed: () -> Unit): Boolean {
        if (healed) return false
        val d = dispatcher ?: return false
        val gm = getHostMethod ?: return false
        val alive = runCatching { gm.invoke(d, "app") != null }.getOrDefault(false)
        if (alive) return false
        // 补备份：capture 时为 null 的话，此刻 mAppHost 可能已被首次 dispatch 缓存
        runCatching { appHostField?.get(d) }.getOrNull()?.let { savedAppHost = it }
        val spare = savedAppHost ?: run {
            Log.w(TAG, "dispatch dead but no spare IAppHost binder")
            return true
        }
        Log.w(TAG, "dispatch dead (getHost(app)=null) -> restore spare IAppHost")
        Handler(Looper.getMainLooper()).post {
            runCatching {
                appHostField?.set(d, spare)
                healed = true
                Log.i(TAG, "healed: mAppHost restored on main thread")
                onHealed()
            }.onFailure { Log.w(TAG, "heal failed", it) }
        }
        return true
    }

    /** 会话销毁复位（[NavigationScreen] onDestroy），下一会话重新 capture。 */
    fun reset() {
        dispatcher = null
        appHostField = null
        getHostMethod = null
        savedAppHost = null
        healed = false
    }
}
