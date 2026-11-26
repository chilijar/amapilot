package com.amapilot.auto

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.amapilot.navigation.CruiseSession

/**
 * Android Auto 服务入口：声明导航类应用，由车机宿主拉起会话。
 *
 * Debug 包允许任意宿主连接便于开发调试，Release 包仅允许签名校验通过的宿主。
 */
class NavigationCarAppService : CarAppService() {

    override fun onCreateSession(): Session = NavigationSession()

    override fun onCreate() {
        super.onCreate()
        // 隐私合规重注入（与 MainActivity 同进程但车连可独立冷启动）；
        // setApiKey 会重置隐私静态 → 注入前后各一次
        val agreed = getSharedPreferences("amapilot_prefs", Context.MODE_PRIVATE)
            .getBoolean("privacy_agreed", false)
        if (agreed) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
        com.amapilot.settings.AmapKey.apply(this)
        if (agreed) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
        android.util.Log.i(TAG, "onCreate")
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        android.util.Log.i(TAG, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        android.util.Log.i(TAG, "onDestroy -> reset car state")
        resetCarState()
        super.onDestroy()
    }

    /** 车机断开收尾：回收本会话自动开启的巡航（用户手机端手动开启的不受影响）
     *  + 复位会话级连接标记（手机辅助态退出）。 */
    private fun resetCarState() {
        if (NavigationSession.autoCruiseStarted && CruiseSession.isActive) {
            CruiseSession.stop()
        }
        NavigationSession.autoCruiseStarted = false
        CarMapControl.carConnected.value = false
    }

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // 必须带库自带宿主白名单：空白名单下唯一放行通道是宿主持
            // android.car.permission.TEMPLATE_RENDERER，而 AA 18（18.0.664264）已删除
            // 该权限声明 → 四道闸门全灭 → "Unrecognized host" 拒绑 → 车机屏
            // "unexpected error"。hosts_allowlist_sample
            // 覆盖 gearhead / AEOS 宿主的证书谱系（含当前 AA 18 谱系首证书）
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    companion object {
        private const val TAG = "CarAppService"
    }
}
