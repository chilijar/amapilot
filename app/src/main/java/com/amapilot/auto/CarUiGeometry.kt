package com.amapilot.auto

/** 车机搜索弹出卡片的行数据（收藏/中继结果共用；lat/lng 皆 0 表示不可点）。 */
data class CarSearchRow(
    val title: String,
    val subtitle: String,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
)

/**
 * 车机表面尺寸 + 宿主可见区内缩（多窗口/副窗口切换时会变）。
 *
 * VD 密度跟随宿主 dpi（异常兜底 160），Compose 层经 [scaledBy] ×(160/D) 归到 dp
 * 空间；"表面像素 == dp" 仅在 160 基线成立。
 *
 * 可见区按边分策略应用（见 [of]）：左右完整应用（实体黑边/媒体副窗口），顶部
 * 夹到 [TOP_INSET_APPLY_MAX]，底部忽略（表面内没有实体 chrome）。
 */
data class CarScreenMetrics(
    val width: Int = REF_WIDTH,
    val height: Int = REF_HEIGHT,
    /** 已按逐边策略夹取过的内缩量（不是宿主原始值）。 */
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
    /** 软左缘内缩：不计整高的宿主复合卡列，顶部/中部仍用全宽。 */
    val insetLeftSoft: Int = 0,

    /** 宿主复合卡列在场（px 空间判定，密度无关——dp 归一会串单位）。 */
    val hostCardColumn: Boolean = false,

    /** 右竖排避让宽度（chip + 边距，px）；默认 [CarUiGeometry.SELF_RAIL_ZONE_DEFAULT]，
     *  换车由 [CarUiGeometry.learnedRailZone] 自学习（只增不减）。 */
    val railZone: Int = CarUiGeometry.SELF_RAIL_ZONE_DEFAULT,
) {
    /** 内容区左缘（可见区，且保证至少留 [MIN_WIDTH] 给 UI）。 */
    val left: Int
        get() = insetLeft.coerceAtLeast(0).coerceAtMost((width - MIN_WIDTH).coerceAtLeast(0))

    /** 软左缘：转向卡/取景中心用（宿主卡列排除不计）。 */
    val leftSoft: Int
        get() = insetLeftSoft.coerceAtLeast(0).coerceAtMost((width - MIN_WIDTH).coerceAtLeast(0))

    /** 内容区上缘。 */
    val top: Int
        get() = insetTop.coerceAtLeast(0).coerceAtMost((height - MIN_HEIGHT).coerceAtLeast(0))

    /** 内容区右缘（开区间）。 */
    val right: Int
        get() = (width - insetRight)
            .coerceAtMost(width)
            .coerceAtLeast(minOf(left + MIN_WIDTH, width).coerceAtLeast(left + 1))

    /** 内容区下缘（开区间）。 */
    val bottom: Int
        get() = (height - insetBottom)
            .coerceAtMost(height)
            .coerceAtLeast(minOf(top + MIN_HEIGHT, height).coerceAtLeast(top + 1))

    val contentWidth: Int get() = right - left
    val contentHeight: Int get() = bottom - top

    /** 全几何按 [factor] 缩放：px ↔ dp 换算（内缩策略已在 [of] 用原始 px 落地）。 */
    fun scaledBy(factor: Float): CarScreenMetrics = CarScreenMetrics(
        width = (width * factor).toInt(),
        height = (height * factor).toInt(),
        insetLeft = (insetLeft * factor).toInt(),
        insetTop = (insetTop * factor).toInt(),
        insetRight = (insetRight * factor).toInt(),
        insetBottom = (insetBottom * factor).toInt(),
        insetLeftSoft = (insetLeftSoft * factor).toInt(),
        // 模式标志不缩放：px 空间的判定原样传递（密度无关语义）
        hostCardColumn = hostCardColumn,
        railZone = (railZone * factor).toInt(),
    )

    /** 地图取景中心（`AMap.setPointToCenter`）：可见区中心而非表面中心（副窗口
     *  盖住右侧时箭头不能藏在面板下）。 */
    val centerX: Int get() = (leftSoft + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    companion object {
        /** 参考尺寸（DHU 800x480 窗口的应用表面）；文档坐标均以此为准。 */
        const val REF_WIDTH = 800
        const val REF_HEIGHT = 400

        /** 最小可用宽度：四钮 4*48 + 最小间距 3*4 + 左右边距 = 244。 */
        const val MIN_WIDTH = 244

        /** 最小可用高度：转向卡 + 按钮行。 */
        const val MIN_HEIGHT = 120

        /** 宿主可见区的标称 padding：常态报 12 但该圈无实体 chrome，只让开超出部分。 */
        const val HOST_BASELINE_INSET = 12

        /** 顶部内缩判定阈值：≤此值 = 实体 chrome 按超出基线让开；>此值 = 宿主自动
         *  隐藏的图标条（卡片区透明且反复跳变），一律忽略。 */
        const val TOP_INSET_APPLY_MAX = 48

        /** 底部内缩一律忽略：表面内 y<400 无实体 chrome（dock 在表面之外）。 */
        const val BOTTOM_INSET_CAP = 0

        /** 宿主复合行程卡列判定阈值：宿主把左下卡柱报成整高左内缩（~332px），
         *  真副窗口黑边仅 ~10-22px。超过 = 卡列：微档按钮行让开，转向卡/取景不让。 */
        const val HOST_CARD_LEFT_MIN = 120

        /** 右内缩生效阈值：≥此值 = 真侧面板；右竖排 rail 的 ~76px 振荡忽略。 */
        const val RIGHT_INSET_APPLY_MIN = 150

        /** 由宿主原始可见区矩形构造（逐边策略在此落地）。 */
        fun of(
            width: Int,
            height: Int,
            visibleLeft: Int,
            visibleTop: Int,
            visibleRight: Int,
            visibleBottom: Int,
            railZone: Int = CarUiGeometry.SELF_RAIL_ZONE_DEFAULT,
        ): CarScreenMetrics {
            val rawLeft = (visibleLeft - HOST_BASELINE_INSET).coerceAtLeast(0)
            return CarScreenMetrics(
                width = width,
                height = height,
                // 左右：只吃超出宿主标称 padding 的部分（实体黑边 / 副窗口面板）
                insetLeft = rawLeft,
                // 软左缘：宿主卡列（>阈值）不计，顶部转向卡与水平取景中心仍用全宽
                insetLeftSoft = if (rawLeft > HOST_CARD_LEFT_MIN) 0 else rawLeft,
                // 卡列模式在 px 空间一次定死（dp 归一后阈值比较会串单位）
                hostCardColumn = rawLeft > HOST_CARD_LEFT_MIN,
                // 右：只认真侧面板；右竖排 rail 的 76px 随 chrome hide/show 振荡，
                // 计入会让取景中心周期横摆 = 可见抖动
                insetRight = (((width - visibleRight) - HOST_BASELINE_INSET)
                    .coerceAtLeast(0))
                    .let { if (it >= RIGHT_INSET_APPLY_MIN) it else 0 },
                // 顶：小内缩=实体 chrome 要让开；大内缩=透明图标条，忽略（见上）
                insetTop = if (visibleTop in 0..TOP_INSET_APPLY_MAX) {
                    (visibleTop - HOST_BASELINE_INSET).coerceAtLeast(0)
                } else {
                    0
                },
                insetBottom = (height - visibleBottom).coerceIn(0, BOTTOM_INSET_CAP),
                railZone = railZone,
            )
        }
    }
}

/**
 * 车机表面 UI 几何（**绘制与命中测试的单一来源**）。
 *
 * 所有位置都是 [CarScreenMetrics] 的函数，按边锚定（左：卡片/按钮行/面板；右：
 * 竖排；下：按钮行/胶囊；居中：全览胶囊）。尺寸常量（按钮 48 等）不随窗口缩放
 * ——AA 最小触摸目标 48dp，窗口变小时改为重排而非缩小。
 */
object CarUiGeometry {

    // ── 尺寸常量（不缩放）──

    /** 表面尺寸变化的过渡时长（ms）：叠加层布局与相机重解共用，视觉同步落位。 */
    const val RESIZE_ANIM_MS = 280

    /** 转向卡/路程卡的设计宽度（窄窗时按内容区收窄）。 */
    const val NAV_CARD_WIDTH = 240

    /** 转向卡高度（不含车道抽屉）：用于判断路程卡是否还放得下。 */
    const val TURN_CARD_H = 96

    /** 卡片/面板与边缘的间距（**四边统一**：左/上/下都用它，视觉一致）。 */
    const val EDGE_MARGIN = 8

    // 导航动作钮（Google 式四钮：取消/其他路线/搜索/完整路线；嵌在复合行程卡内）
    const val NAV_BTN_ROW = 48
    const val NAV_BTN_ROW_BOTTOM = 8
    const val NAV_BTN_ROW_COUNT = 4

    // 右竖排模型（宿主 mapActionStrip 渲染，此模型只做避让/锚定计算）：
    // 宿主 chip 尺寸无法探测 → 保守取 64px，宁可多留不可重叠
    const val SELF_RAIL_BTN = 64
    const val SELF_RAIL_PITCH_MAX = 80
    const val SELF_RAIL_PITCH_MIN = 56
    const val SELF_RAIL_MARGIN = 12
    const val SELF_RAIL_BOTTOM = 16

    /** 右竖排避让宽度默认值（chip 无法探测时的保守估计 [SELF_RAIL_MARGIN] +
     *  [SELF_RAIL_BTN]；换车由 [learnedRailZone] 自学习）。 */
    const val SELF_RAIL_ZONE_DEFAULT = SELF_RAIL_MARGIN + SELF_RAIL_BTN

    /** 右竖排避让宽度自学习：宿主 rail 显示时把自身宽度报进右内缩（~76px），
     *  超出基线的部分落在 [默认值, RIGHT_INSET_APPLY_MIN) 且 ≥ 当前值 → 采纳
     *  （只增不减；≥150 是媒体侧面板）。宿主不报则保持保守默认。 */
    fun learnedRailZone(current: Int, rawRightInset: Int): Int {
        val c = rawRightInset - CarScreenMetrics.HOST_BASELINE_INSET
        val cap = CarScreenMetrics.RIGHT_INSET_APPLY_MIN
        return if (c in SELF_RAIL_ZONE_DEFAULT until cap && c >= current) c else current
    }

    // 自绘搜索胶囊（空闲/巡航态左上）
    const val SEARCH_PILL_X = 12
    const val SEARCH_PILL_Y = 12
    const val SEARCH_PILL_W = 112
    const val SEARCH_PILL_H = 44

    // 选路浮动面板（导航态点"其他路线"展开）
    const val ROUTE_PANEL_Y = 84
    const val ROUTE_PANEL_W = 240
    const val ROUTE_PANEL_HEADER_H = 44
    const val ROUTE_PANEL_ROW_H = 52
    const val ROUTE_PANEL_MAX_ROWS = 4

    /** 灰线直选的命中阈值（屏幕像素距离）。 */
    const val ROUTE_LINE_HIT_PX = 40f

    /** 取景中心与自绘覆盖物列的最小净空；窄屏时中心右移出卡片列，保证箭头不被遮挡。 */
    const val MAP_CENTER_CLEARANCE = 24

    /** 窄屏判定阈值：内容区宽度低于此值时，全览/选路的边界拟合也让开自绘覆盖物列。 */
    const val NARROW_CONTENT = 600

    /**
     * 地图取景中心 X（`AMap.setPointToCenter`）：默认可见区中心，但不得落进左侧
     * 覆盖物列或右侧竖排；落进卡片列时放到剩余可用空间的 1/3 处（给行进方向留
     * 路面视野），区间为空则退回几何中心。
     *
     * @param overlayColumnWidth 左侧常驻覆盖物列宽（含边距），由调用方按 UI 态给出
     */
    fun mapCenterX(m: CarScreenMetrics, overlayColumnWidth: Int): Int {
        val plain = (m.left + m.right) / 2
        val minX = m.left + overlayColumnWidth + MAP_CENTER_CLEARANCE
        val maxX = selfRailX(m) - MAP_CENTER_CLEARANCE
        if (minX >= maxX) return plain.coerceIn(m.left, m.right)
        return when {
            plain < minX -> minX + (maxX - minX) / 3
            plain > maxX -> maxX
            else -> plain
        }
    }

    /** 地图取景中心 Y：默认可见区中心，但保证车头（中心下方 [carAheadPx]）不落到
     *  按钮行底下（矮窗下几何中心 + 48 正好压在按钮行上）。 */
    fun mapCenterY(m: CarScreenMetrics, carAheadPx: Int, hasBottomCards: Boolean): Int {
        val plain = (m.top + m.bottom) / 2
        if (!hasBottomCards) return plain
        // 障碍线 = 卡内按钮行顶缘（卡体不横跨中心 x，用卡顶会白白抬升取景）
        val maxCarY = navCardBtnY(m) - MAP_CENTER_CLEARANCE
        val overflow = (plain + carAheadPx) - maxCarY
        return if (overflow > 0) (plain - overflow).coerceAtLeast(m.top) else plain
    }

    // 全览态「返回导航视角」胶囊（底部居中）
    const val OVERVIEW_PILL_W = 170
    const val OVERVIEW_PILL_H = 48

    /** 面板顶部避让高度不足时贴内容区上缘（矮窗）。 */
    private const val PANEL_TOP_MIN = 8

    // ── 左锚：搜索胶囊 / 卡片 ──

    fun searchPillX(m: CarScreenMetrics): Int = m.left + SEARCH_PILL_X
    fun searchPillY(m: CarScreenMetrics): Int = m.top + SEARCH_PILL_Y

    fun navCardX(m: CarScreenMetrics): Int = m.left + EDGE_MARGIN

    /** 转向卡左缘 = 软左缘（宿主复合卡列不计，保持左上对齐）。 */
    fun turnCardX(m: CarScreenMetrics): Int = m.leftSoft + EDGE_MARGIN

    /** 转向卡宽：宿主复合卡模式 = 与宿主卡等宽（随其上报跟宽）；否则同 [navCardWidth]。 */
    fun turnCardWidth(m: CarScreenMetrics): Int =
        if (m.hostCardColumn) {
            (m.insetLeft - 10)
                .coerceAtMost(m.contentWidth - 2 * EDGE_MARGIN)
                .coerceAtLeast(CarScreenMetrics.MIN_WIDTH)
        } else {
            navCardWidth(m)
        }

    /** 卡片宽度：设计 240，窄窗按内容区收窄；下限 224 = 卡内嵌 4 钮×48 + 内边距 + 间距。 */
    fun navCardWidth(m: CarScreenMetrics): Int {
        val avail = m.contentWidth - 2 * EDGE_MARGIN
        return NAV_CARD_WIDTH.coerceAtMost(avail).coerceAtLeast(minOf(224, avail))
    }

    /** 转向卡顶缘：与左缘同为 [EDGE_MARGIN]（四边统一）。 */
    fun turnCardTop(m: CarScreenMetrics): Int = m.top + EDGE_MARGIN

    // ── 下锚：复合行程卡（Google 式：时间/距离·ETA + 卡内嵌按钮行） ──

    /** 复合卡内边距与卡内按钮行几何（按钮嵌卡内，无独立按钮行）。 */
    const val NAV_CARD_PAD = 10
    const val NAV_CARD_H_FULL = 132
    const val NAV_CARD_H_MIN = 104
    const val NAV_CARD_H_MICRO = 68
    const val NAV_CARD_BTN_GAP_MIN = 4
    const val NAV_CARD_BTN_GAP_MAX = 12

    /** 卡高三档：全档（时间+距离·ETA+按钮行）/ 简档（时间+按钮行）/ 微档（仅按钮行，
     *  矮窗退化）——按钮在卡内，任何窗高都保留取消/选路入口。 */
    fun navCardHeight(m: CarScreenMetrics): Int {
        val minY = turnCardTop(m) + TURN_CARD_H + EDGE_MARGIN
        val avail = m.bottom - NAV_BTN_ROW_BOTTOM
        return when {
            avail - NAV_CARD_H_FULL >= minY -> NAV_CARD_H_FULL
            avail - NAV_CARD_H_MIN >= minY -> NAV_CARD_H_MIN
            else -> NAV_CARD_H_MICRO
        }
    }

    /** 复合卡顶缘（下锚）。 */
    fun navCardTop(m: CarScreenMetrics): Int =
        (m.bottom - NAV_BTN_ROW_BOTTOM - navCardHeight(m)).coerceAtLeast(m.top)

    /** 宿主复合卡模式：自绘仅按钮行（微档）下锚顶缘。 */
    fun navBtnRowTop(m: CarScreenMetrics): Int =
        (m.bottom - NAV_BTN_ROW_BOTTOM - NAV_CARD_H_MICRO).coerceAtLeast(m.top)

    /** 卡内按钮间距：按卡宽容纳 4 钮×48 + 内边距。 */
    fun navCardBtnGap(m: CarScreenMetrics): Int {
        val avail = navCardWidth(m) - 2 * NAV_CARD_PAD - NAV_BTN_ROW_COUNT * NAV_BTN_ROW
        val gap = if (NAV_BTN_ROW_COUNT > 1) avail / (NAV_BTN_ROW_COUNT - 1) else 0
        return gap.coerceIn(NAV_CARD_BTN_GAP_MIN, NAV_CARD_BTN_GAP_MAX)
    }

    /** 卡内按钮行第 index 钮左缘 x（绘制与命中共用）。 */
    fun navCardBtnX(m: CarScreenMetrics, index: Int): Int =
        navCardX(m) + NAV_CARD_PAD + index * (NAV_BTN_ROW + navCardBtnGap(m))

    /** 卡内按钮行顶缘 y（卡内下锚）。 */
    fun navCardBtnY(m: CarScreenMetrics): Int =
        navCardTop(m) + navCardHeight(m) - NAV_CARD_PAD - NAV_BTN_ROW

    // ── 右锚 + 下锚：自绘右竖排 ──

    /** 竖排左缘 = 右缘 − 避让宽度 → 车速环/取景右界随宿主 chip 尺寸自适应。 */
    fun selfRailX(m: CarScreenMetrics): Int =
        (m.right - m.railZone).coerceAtLeast(m.left)

    /** 竖排间距：矮窗时压缩（按钮仍 48，间距不低于 MIN−BTN）。 */
    fun selfRailPitch(m: CarScreenMetrics): Int {
        val avail = m.contentHeight - 2 * SELF_RAIL_BOTTOM - SELF_RAIL_BTN
        val pitch = if (avail > 0) avail / 2 else 0
        // 矮窗封顶：保证 y0 下锚不越顶（三钮留在内容区内）
        val fit = (m.contentHeight - SELF_RAIL_BOTTOM - SELF_RAIL_BTN) / 2
        return pitch.coerceIn(SELF_RAIL_PITCH_MIN, SELF_RAIL_PITCH_MAX)
            .coerceAtMost(fit.coerceAtLeast(0))
    }

    /** 竖排首钮顶缘：贴底留 [SELF_RAIL_BOTTOM]，且不越过内容区上缘（矮窗防裁掉）。 */
    fun selfRailY0(m: CarScreenMetrics): Int =
        (m.bottom - SELF_RAIL_BOTTOM - (2 * selfRailPitch(m) + SELF_RAIL_BTN))
            .coerceAtLeast(m.top)

    fun selfRailBtnTop(m: CarScreenMetrics, index: Int): Int =
        selfRailY0(m) + index * selfRailPitch(m)

    // ── 右下：车速环（导航+巡航统一） ──
    // 导航态左下被路程卡占据 → 移到右竖排左侧、与底钮同底锚。锚定竖排自身几何
    // 而非独立定位：副窗口收缩时随竖排一起左滑，构造上永不重叠；极窄窗隐藏。

    /** 车速环直径（与 SpeedRing size 同源，勿在 CarMapUi 硬编码）。 */
    const val SPEED_BADGE_SIZE = 56

    /** 车速环与右竖排的水平间距（也是与左列卡片的最低留隙，见 [speedBadgeFits]）。 */
    const val SPEED_BADGE_RAIL_GAP = 8

    /** 车速环左缘 = 竖排左缘 − 间距 − 尺寸（同源锚点，随竖排滑动）。 */
    fun speedBadgeX(m: CarScreenMetrics): Int =
        (selfRailX(m) - SPEED_BADGE_RAIL_GAP - SPEED_BADGE_SIZE).coerceAtLeast(m.left)

    /** 车速环顶缘：与竖排底钮同底锚（SELF_RAIL_BOTTOM 基线，视觉同排）。 */
    fun speedBadgeY(m: CarScreenMetrics): Int =
        (m.bottom - SELF_RAIL_BOTTOM - SPEED_BADGE_SIZE).coerceAtLeast(m.top)

    /** 极窄窗守卫：环与左列卡片仍需留隙；放不下时隐藏（车速是次级信息）。 */
    fun speedBadgeFits(m: CarScreenMetrics): Boolean =
        speedBadgeX(m) - (turnCardX(m) + turnCardWidth(m)) >= SPEED_BADGE_RAIL_GAP

    // ── 底部居中：全览胶囊 ──

    fun overviewPillX(m: CarScreenMetrics): Int =
        m.left + ((m.contentWidth - OVERVIEW_PILL_W) / 2).coerceAtLeast(EDGE_MARGIN)

    fun overviewPillTop(m: CarScreenMetrics): Int =
        (m.bottom - NAV_BTN_ROW_BOTTOM - OVERVIEW_PILL_H).coerceAtLeast(m.top)

    /** 右下高德 logo 保护带（合规保留）。 */
    const val LOGO_BAND = 24

    // ── 选路浮动面板 ──

    fun routePanelX(m: CarScreenMetrics): Int = m.left + EDGE_MARGIN

    fun routePanelW(m: CarScreenMetrics): Int =
        ROUTE_PANEL_W.coerceAtMost(m.contentWidth - 2 * EDGE_MARGIN).coerceAtLeast(160)

    fun routePanelY(m: CarScreenMetrics): Int {
        val needed = ROUTE_PANEL_HEADER_H + ROUTE_PANEL_ROW_H + PANEL_TOP_MIN
        val y = m.top + ROUTE_PANEL_Y
        return if (y + needed <= m.bottom) y else m.top + PANEL_TOP_MIN
    }

    /** 当前高度下能放几行（至少 1 行）。 */
    fun routePanelMaxRows(m: CarScreenMetrics): Int {
        val avail = m.bottom - routePanelY(m) - ROUTE_PANEL_HEADER_H
        return (avail / ROUTE_PANEL_ROW_H).coerceIn(1, ROUTE_PANEL_MAX_ROWS)
    }

    fun routePanelRowTop(m: CarScreenMetrics, index: Int): Int =
        routePanelY(m) + ROUTE_PANEL_HEADER_H + index * ROUTE_PANEL_ROW_H

    fun routePanelCloseLeft(m: CarScreenMetrics): Int =
        routePanelX(m) + routePanelW(m) - NAV_BTN_ROW
}
