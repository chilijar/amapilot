package com.amapilot

import android.app.Application

/**
 * 进程级 bootstrap：key 注入与隐私合规覆盖**所有**冷启动入口。挂在各 Activity /
 * CarAppService 的 onCreate 会漏掉其他入口（如分享直达），Application.onCreate
 * 先于任何组件执行，是唯一覆盖全入口的收敛点。
 */
class AmapilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 顺序固定：隐私 → setApiKey → 重注入隐私（setApiKey 会重置隐私静态，
        // 注入前调任何 SDK 接口会抛 AMapException = 启动崩溃循环）
        val agreed = getSharedPreferences("amapilot_prefs", MODE_PRIVATE)
            .getBoolean("privacy_agreed", false)
        if (agreed) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
        com.amapilot.settings.AmapKey.apply(this)
        if (agreed) {
            com.amapilot.settings.AmapPrivacy.assert(this)
        }
    }
}
