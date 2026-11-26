package com.amapilot.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import com.amapilot.settings.Prefs

/**
 * 车机设置**半屏面板**（MapWithContentTemplate + 内容 ListTemplate，地图保持可见）。
 * 项：路线配置四 Toggle（见 [Prefs.routeStrategyInt]）/ 音频播报三档 + 内容开关；
 * 入口行 push 子屏，选中即存即关，无 Apply 行。
 */
class CarSettingsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e(TAG, "onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template =
        MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle("设置")
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(
                        ItemList.Builder()
                            // 路线配置/音频播报下沉二级半屏屏（首屏只留入口行）
                            .addItem(
                                Row.Builder()
                                    .setTitle("路线配置 · " + routePrefSummary(carContext))
                                    .setBrowsable(true)
                                    .setOnClickListener {
                                        screenManager.push(CarRoutePrefsScreen(carContext))
                                    }
                                    .build()
                            )
                            .addItem(
                                Row.Builder()
                                    .setTitle("音频播报 · " + audioLabel(carContext))
                                    .setBrowsable(true)
                                    .setOnClickListener {
                                        screenManager.push(CarAudioPrefsScreen(carContext))
                                    }
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    /** 路线配置摘要（首屏入口行回显）。 */
    internal fun routePrefSummary(ctx: CarContext): String {
        val on = listOf(
            "避免拥堵" to Prefs.isRouteAvoidCongestion(ctx),
            "避免收费" to Prefs.isRouteAvoidToll(ctx),
            "避免高速" to Prefs.isRouteAvoidHighway(ctx),
            "高速优先" to Prefs.isRoutePreferHighway(ctx),
        ).filter { it.second }.map { it.first }
        return if (on.isEmpty()) "默认" else on.joinToString("、")
    }

    companion object {
        private const val TAG = "CarSettings"

        /** 音频主档三档标签（对应 unmute / alarm only / mute）。 */
        internal val MODE_LABELS = listOf("播报", "警报", "静音")

        /** 当前主档：播报=转向开；警报=转向关但电子眼/限速任一开；静音=全关。 */
        internal fun audioMode(ctx: CarContext): Int = when {
            Prefs.isVoiceEnabled(ctx) -> 0
            Prefs.isVoiceCameraEnabled(ctx) || Prefs.isVoiceSpeedLimitEnabled(ctx) -> 1
            else -> 2
        }

        internal fun audioLabel(ctx: CarContext): String = MODE_LABELS[audioMode(ctx)]

        /** 主档 → 三开关组合（警报档强制电子眼/限速开；细调 toggle 可再单独改）。 */
        internal fun applyAudioMode(ctx: CarContext, mode: Int) {
            when (mode) {
                0 -> Prefs.setVoiceEnabled(ctx, true)
                1 -> {
                    Prefs.setVoiceEnabled(ctx, false)
                    Prefs.setVoiceCameraEnabled(ctx, true)
                    Prefs.setVoiceSpeedLimitEnabled(ctx, true)
                }
                else -> {
                    Prefs.setVoiceEnabled(ctx, false)
                    Prefs.setVoiceCameraEnabled(ctx, false)
                    Prefs.setVoiceSpeedLimitEnabled(ctx, false)
                }
            }
        }
    }
}


/**
 * 音频播报**二级半屏屏**：两个入口行——播报模式 ›（三档 radio 三级屏）/
 * 播报内容 ›（三 checkbox 三级屏），主档与开关不混屏。
 */
class CarAudioPrefsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e("CarSettings", "audio prefs onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template =
        MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle("音频播报")
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(
                        ItemList.Builder()
                            .addItem(
                                Row.Builder()
                                    .setTitle(
                                        "播报模式 · " + CarSettingsScreen.audioLabel(carContext)
                                    )
                                    .setBrowsable(true)
                                    .setOnClickListener {
                                        screenManager.push(CarAudioModeScreen(carContext))
                                    }
                                    .build()
                            )
                            .addItem(
                                Row.Builder()
                                    .setTitle("播报内容 · " + contentSummary(carContext))
                                    .setBrowsable(true)
                                    .setOnClickListener {
                                        screenManager.push(CarAudioContentScreen(carContext))
                                    }
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    companion object {
        /** 播报内容摘要（入口行回显）。 */
        internal fun contentSummary(ctx: CarContext): String {
            val on = listOf(
                "转向" to Prefs.isVoiceEnabled(ctx),
                "电子眼" to Prefs.isVoiceCameraEnabled(ctx),
                "限速" to Prefs.isVoiceSpeedLimitEnabled(ctx),
            ).filter { it.second }.map { it.first }
            return if (on.isEmpty()) "全关" else on.joinToString("/")
        }
    }
}

/** 播报模式**三级半屏屏**：三行 ✓ radio，点选即应用并回二级。 */
class CarAudioModeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e("CarSettings", "audio mode onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template {
        val cur = CarSettingsScreen.audioMode(carContext)
        return MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle("播报模式")
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(
                        ItemList.Builder().apply {
                            CarSettingsScreen.MODE_LABELS.forEachIndexed { i, label ->
                                addItem(
                                    Row.Builder()
                                        .setTitle((if (i == cur) "✓ " else "") + label)
                                        .setBrowsable(true)
                                        .setOnClickListener {
                                            CarSettingsScreen.applyAudioMode(carContext, i)
                                            android.util.Log.i(
                                                "CarSettings", "audio mode -> $label"
                                            )
                                            screenManager.pop()
                                        }
                                        .build()
                                )
                            }
                        }.build()
                    )
                    .build()
            )
            .build()
    }
}

/** 播报内容**三级半屏屏**：三 checkbox 行，点选即存，与手机端同源 Prefs。 */
class CarAudioContentScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e("CarSettings", "audio content onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template =
        MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle("播报内容")
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(
                        ItemList.Builder()
                            .addItem(checkRow("转向播报", Prefs::isVoiceEnabled, Prefs::setVoiceEnabled))
                            .addItem(checkRow("电子眼播报", Prefs::isVoiceCameraEnabled, Prefs::setVoiceCameraEnabled))
                            .addItem(checkRow("限速提醒", Prefs::isVoiceSpeedLimitEnabled, Prefs::setVoiceSpeedLimitEnabled))
                            .addItem(
                                Row.Builder()
                                    .setTitle(
                                        "引导细节 · " + if (Prefs.isVoiceConcise(carContext)) {
                                            Prefs.VOICE_DETAIL_CONCISE
                                        } else {
                                            Prefs.VOICE_DETAIL_DETAILED
                                        }
                                    )
                                    .setBrowsable(true)
                                    .setOnClickListener {
                                        screenManager.push(CarAudioDetailScreen(carContext))
                                    }
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    /** checkbox 行：✓ 前缀表达选中态，点击切换并原地刷新。 */
    private fun checkRow(
        title: String,
        get: (android.content.Context) -> Boolean,
        set: (android.content.Context, Boolean) -> Unit,
    ): Row =
        Row.Builder()
            .setTitle((if (get(carContext)) "✓ " else "") + title)
            .setBrowsable(true)
            .setOnClickListener {
                val next = !get(carContext)
                set(carContext, next)
                android.util.Log.i("CarSettings", "checkbox $title -> $next")
                invalidate()
            }
            .build()
}

/** 路线配置**二级半屏屏**：四 Toggle 行切换即存（见 [Prefs.routeStrategyInt]），互斥由 Prefs 保证。 */
class CarRoutePrefsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e("CarSettings", "route prefs onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template =
        MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle("路线配置")
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(
                        ItemList.Builder()
                            .addItem(toggleRow("避免拥堵", Prefs::isRouteAvoidCongestion, Prefs::setRouteAvoidCongestion))
                            .addItem(toggleRow("避免收费", Prefs::isRouteAvoidToll, Prefs::setRouteAvoidToll))
                            .addItem(toggleRow("避免高速", Prefs::isRouteAvoidHighway, Prefs::setRouteAvoidHighway))
                            .addItem(toggleRow("高速优先", Prefs::isRoutePreferHighway, Prefs::setRoutePreferHighway))
                            .build()
                    )
                    .build()
            )
            .build()

    private fun toggleRow(
        title: String,
        get: (android.content.Context) -> Boolean,
        set: (android.content.Context, Boolean) -> Unit,
    ): Row =
        Row.Builder()
            .setTitle(title)
            .setToggle(
                androidx.car.app.model.Toggle.Builder { v ->
                    set(carContext, v)
                    android.util.Log.i("CarSettings", "toggle $title -> $v")
                    invalidate()
                }.setChecked(get(carContext)).build()
            )
            .build()
}

/** 路线引导细节**三级半屏屏**：简洁/详细两行 radio 点选即存即关；简洁 = 转向文本静音。 */
class CarAudioDetailScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e("CarSettings", "audio detail onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template {
        val concise = Prefs.isVoiceConcise(carContext)
        val opts = listOf(
            Prefs.VOICE_DETAIL_CONCISE to concise,
            Prefs.VOICE_DETAIL_DETAILED to !concise,
        )
        return MapWithContentTemplate.Builder()
            .setContentTemplate(
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle("引导细节")
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(
                        ItemList.Builder().apply {
                            opts.forEach { (label, cur) ->
                                addItem(
                                    Row.Builder()
                                        .setTitle((if (cur) "✓ " else "") + label)
                                        .setBrowsable(true)
                                        .setOnClickListener {
                                            Prefs.setVoiceDetail(carContext, label)
                                            android.util.Log.i(
                                                "CarSettings", "audio detail -> $label"
                                            )
                                            screenManager.pop()
                                        }
                                        .build()
                                )
                            }
                        }.build()
                    )
                    .build()
            )
            .build()
    }
}
