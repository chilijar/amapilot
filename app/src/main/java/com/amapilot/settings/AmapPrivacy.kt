package com.amapilot.settings

import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings

/**
 * 高德隐私合规统一注入。各模块持有**各自独立**的进程内隐私静态：maps 只覆盖
 * 地图+导航，搜索须经 [ServiceSettings]、定位须经 [AMapLocationClient]——缺失时
 * 搜索直接抛 AMapException（搜索/算路全失败）。
 *
 * 隐私静态是内存值（进程重启即失效），故每个进程入口与同意回调都必须重注入，
 * 且先于任何 SDK 接口。
 */
object AmapPrivacy {

    /** 全模块隐私合规重注入（maps/navi + search + location；幂等，可多次调用）。 */
    fun assert(context: Context) {
        runCatching { MapsInitializer.updatePrivacyShow(context, true, true) }
            .onFailure { android.util.Log.w("AmapPrivacy", "maps show failed", it) }
        runCatching { MapsInitializer.updatePrivacyAgree(context, true) }
            .onFailure { android.util.Log.w("AmapPrivacy", "maps agree failed", it) }
        runCatching { ServiceSettings.updatePrivacyShow(context, true, true) }
            .onFailure { android.util.Log.w("AmapPrivacy", "search show failed", it) }
        runCatching { ServiceSettings.updatePrivacyAgree(context, true) }
            .onFailure { android.util.Log.w("AmapPrivacy", "search agree failed", it) }
        // 定位模块隐私静态独立：未注入时导航位置流首 fix 后冻死
        runCatching {
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(context, true, true)
        }.onFailure { android.util.Log.w("AmapPrivacy", "location show failed", it) }
        runCatching {
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(context, true)
        }.onFailure { android.util.Log.w("AmapPrivacy", "location agree failed", it) }
    }
}
