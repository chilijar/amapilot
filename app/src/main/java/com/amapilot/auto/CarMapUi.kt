package com.amapilot.auto

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amapilot.R
import com.amapilot.navigation.CruiseState
import com.amapilot.navigation.ManeuverIcons
import com.amapilot.navigation.NaviSession
import com.amapilot.navigation.NaviState
import com.amapilot.navigation.alongLine
import com.amapilot.navigation.maneuverLine
import com.amapilot.navigation.naviDistanceText
import com.amapilot.navigation.naviDurationText

/**
 * 车机 UI 叠加层（VirtualDisplay Presentation 内，地图之上）：转向卡 / 复合行程卡 /
 * 车速环。**只做展示**：交互走宿主图标，[CarMapControl.handleClick] 为潜在表面
 * 点击通道保留（当前宿主不触发）。
 */
/** 车机叠加层配色（昼夜双 palette）：accent/路线色两态通用，卡片底与文字随昼夜。
 *  夜间 = 历史观感（深底浅字）；日间 = 白底深字（对齐底图日间样式）。 */
private data class CarPalette(
    /** 卡片/胶囊/pill 底色 */
    val bg: Color,
    /** 主文字（动作/路名/车速） */
    val textPrimary: Color,
    /** 副文字（沿 X / 距离·ETA） */
    val textSecondary: Color,
    /** 车道未高亮箭头/分隔条 */
    val laneGray: Color,
    /** 按钮行未选中底（深底=白透、白底=黑透） */
    val btnIdle: Color,
    /** 选路备选序号徽标底（Canvas 用 ARGB int） */
    val badgeBg: Int,
    /** 徽标数字色（Canvas 用 ARGB int） */
    val badgeText: Int,
)

private val CarAccent = Color(0xFF4EA0FF)

private val NightPalette = CarPalette(
    bg = Color(0xF2111114),
    textPrimary = Color(0xFFF4F6FB),
    textSecondary = Color(0xFF9AA3B2),
    laneGray = Color(0xFF6B7280),
    btnIdle = Color.White.copy(alpha = 0.14f),
    badgeBg = 0xF2111114.toInt(),
    badgeText = 0xFFFFFFFF.toInt(),
)

private val DayPalette = CarPalette(
    bg = Color(0xF2FFFFFF),
    textPrimary = Color(0xFF1F232B),
    textSecondary = Color(0xFF5B6572),
    laneGray = Color(0xFF9AA3B2),
    btnIdle = Color.Black.copy(alpha = 0.08f),
    badgeBg = 0xF2FFFFFF.toInt(),
    badgeText = 0xFF1F232B.toInt(),
)

/** 叠加层配色解析：与底图同源（[CarMapControl.resolvedNight]）；未派发时维持
 *  夜间观感（车机连接前不闪白）。 */
@Composable
private fun carPalette(): CarPalette {
    val night by CarMapControl.resolvedNight.collectAsState()
    return if (night ?: true) NightPalette else DayPalette
}

@Composable
fun CarMapOverlay(
    navi: NaviState?,
    cruise: CruiseState?,
    lanes: List<NaviSession.LaneCell>,
    metrics: CarScreenMetrics,
) {
    // 选路模式面板（CarMapControl 状态流驱动；展开时隐藏路程卡/按钮行）
    val routeSel by CarMapControl.routeSelect.collectAsState()
    // 搜索状态 pill（算路中/搜索中反馈）
    val searchStatus by CarMapControl.searchStatus.collectAsState()
    // 退场动画快照：exit 期间 navi/cruise 已 null，!! 会 NPE；留住最后非空值
    var lastNavi by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(navi)
    }
    if (navi != null) lastNavi = navi
    var lastCruise by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(cruise)
    }
    if (cruise != null) lastCruise = cruise
    // 车速环数据源（导航/巡航两态统一右下）；同样需要退场快照
    var lastSpeed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<Int, Int>?>(null)
    }
    val speedNow: Pair<Int, Int>? = navi?.let { it.speedKmh to it.limitKmh }
        ?: cruise?.let { it.speedKmh to it.limitKmh }
    if (speedNow != null) lastSpeed = speedNow
    val naviActive = navi != null
    // 全览态：显示「返回导航视角」胶囊
    val overview by CarMapControl.overviewActive.collectAsState()
    val g = CarUiGeometry
    // ── 尺寸/可见区变化平滑过渡：动画化 metrics 本身，派生位置协同滑动 ──
    val spec = tween<Int>(CarUiGeometry.RESIZE_ANIM_MS, easing = FastOutSlowInEasing)
    val animW by animateIntAsState(metrics.width, spec, label = "carSurfaceW")
    val animH by animateIntAsState(metrics.height, spec, label = "carSurfaceH")
    val animL by animateIntAsState(metrics.insetLeft, spec, label = "carInsetL")
    val animT by animateIntAsState(metrics.insetTop, spec, label = "carInsetT")
    val animR by animateIntAsState(metrics.insetRight, spec, label = "carInsetR")
    val animB by animateIntAsState(metrics.insetBottom, spec, label = "carInsetB")
    val m = if (animW == metrics.width && animH == metrics.height &&
        animL == metrics.insetLeft && animT == metrics.insetTop &&
        animR == metrics.insetRight && animB == metrics.insetBottom
    ) {
        metrics
    } else {
        metrics.copy(
            width = animW, height = animH,
            insetLeft = animL, insetTop = animT,
            insetRight = animR, insetBottom = animB
        )
    }
    val cardW = g.navCardWidth(m).dp

    Box(Modifier.fillMaxSize()) {
        // 叠加层配色（与底图昼夜同源）
        val c = carPalette()

        // ── 搜索入口 = 宿主 rail 搜索 chip（旋钮/触摸共通道）；此处只留状态 pill ──

        // ── 搜索状态 pill（算路中…/搜索中… 反馈；顶部水平居中） ──
        if (searchStatus != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = c.bg,
                modifier = Modifier
                    .offset(
                        x = ((m.left + m.right) / 2 - 90).dp,
                        y = g.searchPillY(m).dp
                    )
                    .width(180.dp)
                    .height(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        searchStatus!!,
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // ── 选路自绘层（路线线/序号徽标：投影 Canvas 跟相机） ──
        if (routeSel != null) {
            SelectGraphicsLayer()
        }

        // ── 车速环（展示；导航+巡航统一右下·竖排左侧。选路态隐藏，与卡片一致） ──
        AnimatedVisibility(
            visible = speedNow != null && routeSel == null && g.speedBadgeFits(m),
            enter = fadeIn(androidx.compose.animation.core.tween(220)) + slideInVertically(androidx.compose.animation.core.tween(220)) { it / 4 },
            exit = fadeOut(androidx.compose.animation.core.tween(160)) + slideOutVertically(androidx.compose.animation.core.tween(160)) { it / 4 }
        ) {
            lastSpeed?.let { (speed, limit) ->
                CarSpeedBadge(
                    speed, limit,
                    modifier = Modifier.offset(
                        x = g.speedBadgeX(m).dp,
                        y = g.speedBadgeY(m).dp
                    )
                )
            }
        }

        // ── 转向卡（导航态，左上；车道条同自绘。选路态隐藏：路线信息优先） ──
        AnimatedVisibility(
            visible = navi != null && routeSel == null,
            enter = fadeIn(androidx.compose.animation.core.tween(220)) + slideInVertically(androidx.compose.animation.core.tween(220)) { it / 4 },
            exit = fadeOut(androidx.compose.animation.core.tween(160)) + slideOutVertically(androidx.compose.animation.core.tween(160)) { it / 4 }
        ) {
            lastNavi?.let { nv ->
            CarTurnCard(
                nv, lanes,
                cardWidth = g.turnCardWidth(m).dp,
                modifier = Modifier.offset(
                    x = g.turnCardX(m).dp,
                    y = g.turnCardTop(m).dp
                )
            )
            }
        }

        // ── 复合行程卡：宿主渲染（自带 ✕ 且旋钮可焦）；自绘整卡仅兜底 ──
        AnimatedVisibility(
            visible = navi != null && routeSel == null && !CarMapControl.hostTripCard,
            enter = fadeIn(androidx.compose.animation.core.tween(220)) + slideInVertically(androidx.compose.animation.core.tween(220)) { it / 4 },
            exit = fadeOut(androidx.compose.animation.core.tween(160)) + slideOutVertically(androidx.compose.animation.core.tween(160)) { it / 4 }
        ) {
            lastNavi?.let { nv ->
            val cardH = g.navCardHeight(m)
            CarNaviCard(
                nv,
                cardWidth = cardW,
                cardHeight = cardH.dp,
                showSub = cardH == CarUiGeometry.NAV_CARD_H_FULL,
                showTime = cardH != CarUiGeometry.NAV_CARD_H_MICRO,
                btnGap = g.navCardBtnGap(m).dp,
                modifier = Modifier.offset(
                    x = g.navCardX(m).dp,
                    y = g.navCardTop(m).dp
                )
            )
            }
        }

        // ── 全览返回胶囊（底部水平居中；点击/空点地图/⌖/再按⛶ 均返回导航视角） ──
        AnimatedVisibility(
            visible = overview && navi != null && routeSel == null,
            enter = fadeIn(androidx.compose.animation.core.tween(220)) + slideInVertically(androidx.compose.animation.core.tween(220)) { it / 4 },
            exit = fadeOut(androidx.compose.animation.core.tween(160)) + slideOutVertically(androidx.compose.animation.core.tween(160)) { it / 4 }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = c.bg,
                modifier = Modifier
                    .offset(
                        x = g.overviewPillX(m).dp,
                        y = g.overviewPillTop(m).dp
                    )
                    .width(g.OVERVIEW_PILL_W.dp)
                    .height(g.OVERVIEW_PILL_H.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_car_recenter),
                        contentDescription = null,
                        tint = CarAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("返回导航视角", color = c.textPrimary, fontSize = 13.sp)
                }
            }
        }

        // ── 搜索弹出卡片：状态行 + 模式行(导航态) + 网格；窄窗降单列 ──
        // 选路面板已迁宿主 MWCT 侧栏 ListTemplate（旋钮可焦、触摸可点；自绘表面
        // 进不了宿主焦点系统）
    }
}

/**
 * 选路路线/序号徽标自绘层：投影换算的 Compose Canvas（相机变化逐帧重投影）。
 * MWCT 态引擎覆盖物新增不进渲染层，故改叠加层自绘保同屏可见。
 */
@Composable
private fun SelectGraphicsLayer() {
    // 相机 tick / 选路版本：读取即订阅 → 变化触发重组重投影
    val camTick by CarMapControl.cameraTick.collectAsState()
    val selVer by CarMapControl.selVersion.collectAsState()
    // 徽标底/文字随昼夜（与卡片同源；Canvas lambda 里不能调 composable，先捕获）
    val c = carPalette()
    Canvas(Modifier.fillMaxSize()) {
        val tick = camTick + selVer // 订阅锚点（值本身不参与绘制）
        if (tick < Long.MIN_VALUE / 2) return@Canvas
        val paths = CarMapControl.selPaths
        val selectedId = CarMapControl.selSelectedId
        val opts = CarMapControl.selOpts
        val native = drawContext.canvas.nativeCanvas

        fun stroke(pts: List<com.amap.api.maps.model.LatLng>, px: Float, color: Int) {
            if (pts.size < 2) return
            val path = android.graphics.Path()
            val step = maxOf(1, pts.size / 80)
            var first = true
            var i = 0
            while (i < pts.size) {
                val sp = CarMapControl.projectToScreen(pts[i])
                if (sp != null) {
                    if (first) {
                        path.moveTo(sp.x.toFloat(), sp.y.toFloat())
                        first = false
                    } else {
                        path.lineTo(sp.x.toFloat(), sp.y.toFloat())
                    }
                }
                i += step
            }
            // 末点补全（采样步长跳过终点会让线短一截）
            CarMapControl.projectToScreen(pts.last())?.let {
                if (!first) path.lineTo(it.x.toFloat(), it.y.toFloat())
            }
            native.drawPath(
                path,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = px
                    this.color = color
                    strokeJoin = android.graphics.Paint.Join.ROUND
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }
            )
        }

        // 备选线（底）→ 当前路线白描边 + 蓝主线（顶）
        paths.forEach { (id, pts) ->
            if (id != selectedId) stroke(pts, 11f * density, 0xFF4EA0FF.toInt())
        }
        paths[selectedId]?.let { pts ->
            stroke(pts, 19f * density, 0xFFFFFFFF.toInt())
            stroke(pts, 13f * density, 0xFF4EA0FF.toInt())
        }

        // 序号徽标：弧长比例错开防重叠；详情在宿主侧栏行
        val numById = opts.withIndex().associate { it.value.pathId to (it.index + 1) }
        val fracs = listOf(0.50f, 0.25f, 0.80f, 0.15f, 0.90f)
        opts.forEach { o ->
            val pts = paths[o.pathId] ?: return@forEach
            val num = numById[o.pathId] ?: return@forEach
            val frac = fracs.getOrElse(num - 1) { 0.5f }
            val pos = pts[(pts.size * frac).toInt().coerceIn(0, pts.size - 1)]
            val sp = CarMapControl.projectToScreen(pos) ?: return@forEach
            val d = 44f * density
            val rect = android.graphics.RectF(
                sp.x - d / 2, sp.y - d / 2, sp.x + d / 2, sp.y + d / 2
            )
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.color = if (o.current) 0xFF4EA0FF.toInt() else c.badgeBg
            native.drawOval(rect, paint)
            if (!o.current) {
                paint.color = 0xFF8A93A6.toInt()
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 2.5f * density
                native.drawOval(rect, paint)
            }
            paint.style = android.graphics.Paint.Style.FILL
            // 选中徽标 = 蓝底白字（两态恒定）；备选徽标随昼夜（白底深字/深底白字）
            paint.color = if (o.current) 0xFFFFFFFF.toInt() else c.badgeText
            paint.textSize = 22f * density
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val t = "$num"
            val tw = paint.measureText(t)
            native.drawText(t, sp.x - tw / 2, sp.y + 8f * density, paint)
        }
    }
}

/** 导航按钮行单钮（圆形底 + accent 图标，与卡片同风格、随昼夜）。 */
@Composable
private fun NavActionButton(
    painter: androidx.compose.ui.graphics.painter.Painter,
    key: String,
    active: Boolean = false,
) {
    val c = carPalette()
    // 旋钮/宿主 chip 选中同步高亮（按下点亮 600ms）；[active] = 模式态常亮
    val flash by CarMapControl.actionFlash.collectAsState()
    val lit = flash == key || active
    // 半透明圆底；选中/焦点 = accent 圆环 + 浅染底（实心填充会吞掉圆形轮廓）
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (lit) CarAccent.copy(alpha = 0.22f)
        else c.btnIdle,
        border = androidx.compose.foundation.BorderStroke(
            if (lit) 2.dp else 0.dp,
            if (lit) CarAccent else androidx.compose.ui.graphics.Color.Transparent
        ),
        modifier = Modifier.size(CarUiGeometry.NAV_BTN_ROW.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter,
                contentDescription = null,
                tint = if (lit) CarAccent else c.textPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 转向卡（方向信息）。[cardWidth] 由 [CarUiGeometry.navCardWidth] 按窗口宽度给出。 */
@Composable
private fun CarTurnCard(
    navi: NaviState,
    lanes: List<NaviSession.LaneCell>,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val c = carPalette()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = c.bg,
        // 与路程卡同宽同左缘（Google 式上下对齐）
        modifier = modifier.width(cardWidth)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ManeuverIcons.maneuverIcon(navi.iconType),
                    contentDescription = null,
                    tint = CarAccent,
                    modifier = Modifier.size(42.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = naviDistanceText(navi.stepRemainMeters),
                        color = CarAccent,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // 动作+下一路名；两者皆空时整行隐藏（卡片本身已表达导航态）
                    navi.maneuverLine()?.let { line ->
                        Text(
                            text = line,
                            color = c.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    navi.alongLine()?.let { along ->
                        Text(
                            text = along,
                            color = c.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            // 车道行：距 maneuver 过远不展示（提前推送的匝道车道与路面标线不符）；
            // 车道组无人能执行机动方向（窄路借道）照常显示但全灰。判定同源 LaneDirections
            if (lanes.isNotEmpty() &&
                navi.stepRemainMeters <= NaviSession.LANE_PANEL_MAX_METERS
            ) {
                com.amapilot.ui.components.LaneRow(
                    lanes = lanes,
                    active = com.amapilot.navigation.activeLaneDir(navi.iconType),
                    slight = com.amapilot.navigation.isSlightManeuver(navi.iconType),
                    linksToManeuver = navi.linksToManeuver,
                    stepRemainMeters = navi.stepRemainMeters,
                    linkRemainMeters = navi.linkRemainMeters,
                    iconSize = 26.dp,
                    separatorHeight = 46.dp,
                    accent = CarAccent,
                    inactive = c.laneGray,
                    separatorColor = c.laneGray,
                    horizontalPadding = 0.dp,
                    verticalPadding = 6.dp
                )
            }
        }
    }
}

/**
 * 复合行程卡：剩余时间大字 + 距离·ETA 行 + 卡内嵌按钮行（矮窗简档隐藏距离·ETA 行
 * 但保留按钮行）。
 */
@Composable
private fun CarNaviCard(
    navi: NaviState,
    cardWidth: Dp,
    cardHeight: Dp,
    showSub: Boolean,
    showTime: Boolean,
    btnGap: Dp,
    modifier: Modifier = Modifier,
) {
    val c = carPalette()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = c.bg,
        modifier = modifier.size(cardWidth, cardHeight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 12.dp,
                    vertical = CarUiGeometry.NAV_CARD_PAD.dp
                )
        ) {
            if (showTime) {
                // 时间大字 = 卡片主视觉（accent 色）
                Text(
                    text = naviDurationText(navi.routeRemainSeconds),
                    color = CarAccent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (showSub) {
                Text(
                    text = "${naviDistanceText(navi.routeRemainMeters)} · " +
                        formatCarEta(navi.routeRemainSeconds),
                    color = c.textSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.weight(1f))
            // 卡内四圆钮：✕ 取消 / ⑂ 选路 / 📍 途经点 / 🔍 搜索（仅兜底态用整卡四钮）
            val wp by CarMapControl.waypointMode.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(btnGap)) {
                NavActionButton(androidx.compose.ui.res.painterResource(R.drawable.ic_car_close), "cancel")
                NavActionButton(androidx.compose.ui.res.painterResource(R.drawable.ic_car_routes), "routes")
                NavActionButton(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_car_waypoint),
                    "waypoint",
                    active = wp
                )
                NavActionButton(androidx.compose.ui.res.painterResource(R.drawable.ic_car_search), "search")
            }
        }
    }
}

/** 预计到达时刻（HH:MM，本地时区）：复合卡距离·ETA 行用。 */
private fun formatCarEta(remainSeconds: Int): String {
    val cal = java.util.Calendar.getInstance()
        .apply { add(java.util.Calendar.SECOND, remainSeconds) }
    return String.format(
        "%02d:%02d",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE)
    )
}

/** 车机车速环 = 共用 [com.amapilot.ui.components.SpeedRing] 的车机配色封装：
 *  尺寸同源 [CarUiGeometry.SPEED_BADGE_SIZE]；底/文字随昼夜（与卡片同源），
 *  超限速文字行 accent 色，超速分档变色。 */
@Composable
private fun CarSpeedBadge(speedKmh: Int, limitKmh: Int, modifier: Modifier = Modifier) {
    val c = carPalette()
    com.amapilot.ui.components.SpeedRing(
        speedKmh = speedKmh,
        limitKmh = limitKmh,
        modifier = modifier,
        size = CarUiGeometry.SPEED_BADGE_SIZE.dp,
        ringColor = CarAccent,
        bgColor = c.bg,
        textColor = c.textPrimary,
        subTextColor = c.textSecondary,
        speedFontSize = 20.sp,
        unitFontSize = 8.sp,
        limitLabel = if (limitKmh > 0) {
            { Text("限 $limitKmh", color = CarAccent, fontSize = 9.sp) }
        } else null
    )
}
