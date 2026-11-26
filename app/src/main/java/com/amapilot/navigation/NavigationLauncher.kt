package com.amapilot.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Poi
import com.amap.api.navi.AmapNaviPage
import com.amap.api.navi.AmapNaviParams
import com.amap.api.navi.AmapNaviTheme
import com.amap.api.navi.AmapNaviType
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.INaviInfoCallback
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amapilot.map.SearchedPoi

/** 导航启动入口：SDK 无可嵌入导航 View，官方入口为 [AmapNaviPage.showRouteActivity]。
 *  路线已由应用内选好，用 [AmapPageType.NAVI] 跳过 SDK 预览页直达 TBT。 */
object NavigationLauncher {

    /** 直接进入 SDK 导航界面（[from] 为 null 表示当前位置）。 */
    fun start(context: Context, from: LatLng?, to: SearchedPoi) {
        val start = from?.let { Poi("我的位置", it, null) }
        val end = Poi(to.name, LatLng(to.lat, to.lng), to.id.ifBlank { null })
        show(context, start, end)
    }

    /** 交换模式：目的地作为起点、当前位置作为终点。 */
    fun startFromPoi(context: Context, fromPoi: SearchedPoi, to: LatLng?) {
        val start = Poi(fromPoi.name, LatLng(fromPoi.lat, fromPoi.lng), fromPoi.id.ifBlank { null })
        val end = to?.let { Poi("我的位置", it, null) }
        show(context, start, end)
    }

    private fun show(context: Context, start: Poi?, end: Poi?) {
        val appContext = context.applicationContext
        val params = AmapNaviParams(start, null, end, AmapNaviType.DRIVER, AmapPageType.NAVI).apply {
            setNeedCalculateRouteWhenPresent(false)
            theme = AmapNaviTheme.BLACK
        }
        AmapNaviPage.getInstance().showRouteActivity(appContext, params, NaviUiCallback(appContext))
    }
}

/** 主色调：与地图路线一致的蓝色。 */
private const val ACCENT = 0xFF1E88E5.toInt()

/** 次要文字色。 */
private const val SUBTLE = 0xFF757575.toInt()

/**
 * 导航回调 + 自定义 TBT UI：顶部转向卡、底部信息栏（数据由 [onNaviInfoUpdate] 驱动；
 * 3D 地图/语音/算路仍由 SDK 处理）。
 */
private class NaviUiCallback(private val context: Context) : INaviInfoCallback {

    private var turnRoad: TextView? = null
    private var turnDistance: TextView? = null
    private var remainInfo: TextView? = null
    private var roadInfo: TextView? = null

    override fun onInitNaviFailure() = Unit
    override fun onGetNavigationText(p1: String?) = Unit
    override fun onLocationChange(p1: AMapNaviLocation?) = Unit
    override fun onArriveDestination(p1: Boolean) = Unit
    override fun onCalculateRouteSuccess(p1: IntArray?) = Unit
    override fun onCalculateRouteFailure(p1: Int) = Unit
    override fun onStopSpeaking() = Unit
    override fun onReCalculateRoute(p1: Int) = Unit
    override fun onExitPage(p1: Int) {
        runCatching {
            com.amap.api.navi.AMapNavi.getInstance(context).removeAMapNaviListener(naviInfoListener)
        }
        AmapNaviPage.destroy()
    }

    override fun onStrategyChanged(p1: Int) = Unit
    override fun onArrivedWayPoint(p1: Int) = Unit
    override fun onMapTypeChanged(p1: Int) = Unit
    override fun onNaviDirectionChanged(p1: Int) = Unit
    override fun onDayAndNightModeChanged(p1: Int) = Unit
    override fun onBroadcastModeChanged(p1: Int) = Unit
    override fun onScaleAutoChanged(p1: Boolean) = Unit

    private val naviInfoListener = object : com.amap.api.navi.SimpleNaviListener() {
        override fun onNaviInfoUpdate(info: NaviInfo) {
            turnRoad?.text = info.nextRoadName.orEmpty().ifBlank { info.currentRoadName }
            turnDistance?.text = naviDistanceText(info.curStepRetainDistance)
            remainInfo?.text = buildString {
                append(naviDistanceText(info.pathRetainDistance))
                append("  ·  ")
                append(naviDurationText(info.pathRetainTime))
                if (info.routeRemainLightCount > 0) append("  ·  红绿灯 ${info.routeRemainLightCount}")
            }
            roadInfo?.text = buildString {
                append(info.currentRoadName.orEmpty())
                info.nextRoadName?.takeIf { it.isNotBlank() }?.let {
                    append("  →  ")
                    append(it)
                }
            }
        }
    }

    override fun onStartNavi(p1: Int) {
        runCatching {
            com.amap.api.navi.AMapNavi.getInstance(context).addAMapNaviListener(naviInfoListener)
        }
    }

    override fun getCustomMiddleView(): View? = null

    /** 顶部转向卡：大字距离 + 道路名。 */
    @SuppressLint("SetTextI18n")
    override fun getCustomNaviView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundRounded(24f, Color.WHITE)
        setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        turnDistance = textView(context, "", 26f, ACCENT, Typeface.DEFAULT_BOLD).also { addView(it) }
        turnRoad = textView(context, "", 18f, Color.BLACK, Typeface.DEFAULT_BOLD).also { addView(it) }
    }

    /** 底部信息栏：剩余信息 + 当前道路 + 退出按钮。 */
    override fun getCustomNaviBottomView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundRounded(24f, Color.WHITE)
        setPadding(dp(20f), dp(14f), dp(12f), dp(14f))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                remainInfo = textView(context, "", 18f, ACCENT, Typeface.DEFAULT_BOLD).also { addView(it) }
                roadInfo = textView(context, "", 13f, SUBTLE, Typeface.DEFAULT).also { addView(it) }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            Button(context).apply {
                text = "退出"
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    color = android.content.res.ColorStateList.valueOf(ACCENT)
                    cornerRadius = dp(20f).toFloat()
                }
                setOnClickListener { AmapNaviPage.getInstance().exitRouteActivity() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }
}

/** TextView 便捷构造。 */
private fun textView(
    context: Context,
    text: String,
    sizeSp: Float,
    color: Int,
    typeface: Typeface
): TextView = TextView(context).apply {
    this.text = text
    setTextColor(color)
    this.typeface = typeface
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

/** 圆角白色卡片背景。 */
private fun View.setBackgroundRounded(radiusDp: Float, color: Int) {
    background = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}

private fun dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, contextRes().displayMetrics).toInt()

private fun contextRes() = android.content.res.Resources.getSystem()
