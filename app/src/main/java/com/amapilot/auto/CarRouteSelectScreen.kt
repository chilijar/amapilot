package com.amapilot.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.MapController

/**
 * 选路**pushed 屏**（预导航多路线决策 / 导航中备选切换共用）。
 *
 * 必须压在栈顶而非根屏态切换：宿主只把 back 映射为 pop，根屏态 back 会退出会话。
 * 栈语义：预导航 = Navigation → Search → RouteSelect（back 回地点选择页）；
 * 导航中 = Navigation → RouteSelect（back 回地图）。
 *
 * 单击定稿：行用逐行 `setOnClickListener`（radio 语义下点已选中行不产生变化）；
 * 不挂 header BACK 钮，否则旋钮初始焦点落在「返回」而非路线首行。提交/直选经
 * [CarMapControl.exitRouteSelect] 与 [CarMapControl.onRouteSelectClosed] 关屏，
 * back 退出由 onDestroy 收掉选路图形态。
 */
class CarRouteSelectScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // 提交/直选收尾后关闭本屏（钩子本屏独占，destroy 时复位）
        CarMapControl.onRouteSelectClosed = {
            runCatching {
                if (screenManager.top === this) screenManager.pop()
            }
        }
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                // back 退出：收掉选路图形态（预导航目标点一并清除）；
                // exitRouteSelect 的关屏钩子有 top===this 守卫，不会重复 pop
                if (CarMapControl.routeSelect.value != null) {
                    CarMapControl.exitRouteSelect(null)
                }
                CarMapControl.onRouteSelectClosed = null
            }
        })
    }

    override fun onGetTemplate(): Template = runCatching { panel() }
        .onFailure { android.util.Log.e("CarMapDisplay", "routeSelect onGetTemplate failed", it) }
        .getOrElse { ListTemplate.Builder().setLoading(true).build() }

    private fun panel(): Template {
        val opts = CarMapControl.routeSelect.value.orEmpty()
        // 选路态不挂顶栏动作与右竖排（旋钮通道 = 侧栏列表行）；MWCT 库不校验
        // ActionStrip，NavigationTemplate 的「≥1 动作」限制不适用
        return MapWithContentTemplate.Builder()
            .setMapController(MapController.Builder().build())
            .setContentTemplate(
                ListTemplate.Builder()
                    // 标题头不带 BACK 动作：BACK 钮会抢走旋钮初始焦点（见类 KDoc）
                    .setHeader(Header.Builder().setTitle("选择路线").build())
                    .setSingleList(
                        ItemList.Builder().apply {
                            opts.forEach { o ->
                                addItem(
                                    Row.Builder()
                                        .setTitle(
                                            (if (o.current) "✓ " else "") +
                                                o.label + " · " + o.timeText +
                                                if (o.deltaText.isNotBlank()) "  " + o.deltaText else ""
                                        )
                                        .addText(
                                            listOf(o.distText, o.lightsText)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" · ")
                                        )
                                        .setOnClickListener {
                                            android.util.Log.i(
                                                "CarMapDisplay",
                                                "route select row clicked: ${o.pathId}"
                                            )
                                            CarMapControl.exitRouteSelect(o.pathId)
                                        }
                                        .build()
                                )
                            }
                            // 无「取消」行：宿主返回通道（箭头/返回键/旋钮 back）
                            // 完整覆盖取消语义；pop 触发 onDestroy 收选路图形态
                        }.build()
                    )
                    .build()
            )
            .build()
    }

}
