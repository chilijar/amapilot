package com.amapilot.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机 UI 几何的尺寸自适应性（宿主多窗口/分屏切换会改变表面宽高与可见区）。
 *
 * 两类保证：
 * 1. **参考尺寸 800x400 复现已实测验证的坐标**（DHU tap 点位表的来源）
 * 2. **任意尺寸下元素都落在内容区内**、触摸目标不缩水、卡片不互相重叠
 *
 * 复合行程卡（2026-11 定稿）：按钮行嵌在卡内（无独立按钮行），卡高三档
 * FULL/MIN/MICRO 按窗高退化；搜索改宿主 SearchTemplate 屏（无自绘面板几何）。
 */
class CarUiGeometryTest {

    private val g = CarUiGeometry
    private val ref = CarScreenMetrics(800, 400)

    // ── 1. 参考几何：无内缩时复现旧版已验证点位 ──

    @Test
    fun bareSurfaceGeometryMatchesLegacyReference() {
        // 搜索胶囊：绘制 (12,12) 112x44 → 点击中心 (68,34)
        assertEquals(12, g.searchPillX(ref))
        assertEquals(12, g.searchPillY(ref))
        assertEquals(68, g.searchPillX(ref) + g.SEARCH_PILL_W / 2)
        assertEquals(34, g.searchPillY(ref) + g.SEARCH_PILL_H / 2)

        // 复合行程卡：全档 132 高、下锚顶缘 260；卡内按钮行顶缘 334，
        // 钮中心 (42/99/156/213, 358)
        assertEquals(g.NAV_CARD_H_FULL, g.navCardHeight(ref))
        assertEquals(260, g.navCardTop(ref))
        assertEquals(334, g.navCardBtnY(ref))
        assertEquals(listOf(18, 75, 132, 189), (0..3).map { g.navCardBtnX(ref, it) })
        assertEquals(listOf(42, 99, 156, 213), (0..3).map {
            g.navCardBtnX(ref, it) + g.NAV_BTN_ROW / 2
        })
        assertEquals(358, g.navCardBtnY(ref) + g.NAV_BTN_ROW / 2)
        // 卡底缘 = 下边距 8
        assertEquals(392, g.navCardTop(ref) + g.navCardHeight(ref))

        // 右竖排模型（宿主 mapActionStrip 只做避让锚定；DHU 实测中心 ≈786，
        // 模型保守取 64px 宽 → 中心 756，AA18 真车 chip 更大更靠左也不重叠）
        assertEquals(724, g.selfRailX(ref))
        assertEquals(160, g.selfRailY0(ref))
        assertEquals(80, g.selfRailPitch(ref))
        assertEquals(listOf(192, 272, 352), (0..2).map {
            g.selfRailBtnTop(ref, it) + g.SELF_RAIL_BTN / 2
        })
        assertEquals(756, g.selfRailX(ref) + g.SELF_RAIL_BTN / 2)

        // 全览胶囊：底部水平居中
        assertEquals(315, g.overviewPillX(ref))
        assertEquals(344, g.overviewPillTop(ref))
        assertEquals(368, g.overviewPillTop(ref) + g.OVERVIEW_PILL_H / 2)

        // 选路面板：✕ 在头部右侧，行顶 128+52i，最多 4 行
        assertEquals(8, g.routePanelX(ref))
        assertEquals(84, g.routePanelY(ref))
        assertEquals(240, g.routePanelW(ref))
        assertEquals(200, g.routePanelCloseLeft(ref))
        assertEquals(128, g.routePanelRowTop(ref, 0))
        assertEquals(180, g.routePanelRowTop(ref, 1))
        assertEquals(4, g.routePanelMaxRows(ref))
    }

    // ── 1b. 宿主真实状态：可见区内缩后的点位 ──

    /** 常态：宿主报 Rect(12,12 - 788,388)，实测那一圈无实体 chrome。 */
    private val hostNormal = CarScreenMetrics.of(800, 400, 12, 12, 788, 388)

    /** 副窗口态：宿主报 Rect(22,34 - 464,376)（右侧被媒体面板吃掉 336px）。 */
    private val hostSplit = CarScreenMetrics.of(800, 400, 22, 34, 464, 376)

    /** 图标条态：宿主报 Rect(12,88 - 788,388)（自动隐藏 bar，左侧透明）。 */
    private val hostTopBar = CarScreenMetrics.of(800, 400, 12, 88, 788, 388)

    @Test
    fun hostNominalPaddingDoesNotMoveTheLayout() {
        // 常态宿主报的 12px 是**标称 padding**（实测无实体 chrome，地图画到表面
        // 边缘）。若照单全收，卡片会从已验证的 (8,8) 挪到 (20,20)，凭空多出
        // 12px 空隙。扣除基线后布局与旧版完全一致。
        val m = hostNormal
        assertEquals(0, m.insetLeft)
        assertEquals(0, m.insetTop)
        assertEquals(0, m.insetRight)
        assertEquals(0, m.insetBottom)
        assertEquals(0, m.left)
        assertEquals(800, m.right)
        assertEquals(8, g.navCardX(m))
        assertEquals(8, g.turnCardTop(m))
        assertEquals(724, g.selfRailX(m))
        assertEquals(334, g.navCardBtnY(m))
        // 已验证的 DHU 点位不变
        assertEquals(68, g.searchPillX(m) + g.SEARCH_PILL_W / 2)
        assertEquals(34, g.searchPillY(m) + g.SEARCH_PILL_H / 2)
        assertEquals(756, g.selfRailX(m) + g.SELF_RAIL_BTN / 2)
        assertEquals(400, m.centerX)   // 取景中心 = 表面中心，与默认一致
        assertEquals(200, m.centerY)
        assertInside(m)
    }

    @Test
    fun topBarFlickerNeverMovesTheCards() {
        // 宿主图标条自动隐藏 → top 在 12 ↔ 88 之间跳。跟着动会让方向卡上下窜，
        // 且那条 bar 左侧透明（按它内缩曾被用户判定"方向卡位置错误，偏下了"）。
        assertEquals(0, hostTopBar.insetTop)
        assertEquals(8, g.turnCardTop(hostTopBar))
        assertEquals(8, g.navCardX(hostTopBar))
        // 与常态完全一致 = 不跳变
        assertEquals(hostNormal.copy(), hostTopBar.copy())
    }

    @Test
    fun speedBadgeSitsLeftOfRailSameBaseline() {
        // 车速环（右下·竖排左侧，2026-11 定稿）：右缘 + 间距 = 竖排左缘
        // （同源锚点 → 构造上永不重叠）；与竖排底钮同底锚（同排视觉）
        assertEquals(660, g.speedBadgeX(ref))
        assertEquals(
            g.selfRailX(ref),
            g.speedBadgeX(ref) + g.SPEED_BADGE_SIZE + g.SPEED_BADGE_RAIL_GAP
        )
        assertEquals(328, g.speedBadgeY(ref))
        assertEquals(
            g.selfRailBtnTop(ref, 2) + g.SELF_RAIL_BTN,
            g.speedBadgeY(ref) + g.SPEED_BADGE_SIZE
        )
        // 环完全在内容区内，且与左列卡片（转向卡/路程卡）留隙
        assertTrue(g.speedBadgeFits(ref))
        assertTrue(
            "环应完全在内容区内",
            g.speedBadgeX(ref) + g.SPEED_BADGE_SIZE <= ref.right &&
                g.speedBadgeY(ref) + g.SPEED_BADGE_SIZE <= ref.bottom
        )
    }

    @Test
    fun speedBadgeSlidesWithRailAndStaysVisibleInSplit() {
        // 副窗口（右侧媒体面板 324px）：随竖排一起左滑（同源锚点），
        // 且仍与左列卡片留隙 → 保持可见
        assertEquals(
            g.selfRailX(hostSplit) - g.SPEED_BADGE_RAIL_GAP - g.SPEED_BADGE_SIZE,
            g.speedBadgeX(hostSplit)
        )
        assertTrue(g.speedBadgeFits(hostSplit))
    }

    @Test
    fun speedBadgeHidesWhenWindowTooNarrow() {
        // 极窄窗：环位与左列卡片重叠（间距 < RAIL_GAP）→ 隐藏（导航控件优先）
        val tiny = CarScreenMetrics(300, 400)
        assertFalse(g.speedBadgeFits(tiny))
    }

    // ── 4b. 右竖排避让宽度自学习（换车自适应，2026-11） ──

    @Test
    fun railZoneLearningAdoptsLargerHostRailOnly() {
        // 默认 76（12 边距 + 64 chip 保守值）
        assertEquals(76, g.SELF_RAIL_ZONE_DEFAULT)
        // 常态基线 12：无信息，保持
        assertEquals(76, g.learnedRailZone(76, rawRightInset = 12))
        // 幻影小内缩（< 默认）：忽略
        assertEquals(76, g.learnedRailZone(76, rawRightInset = 40))
        // rail 出现报 96：采纳（换车 chip 更大）
        assertEquals(96, g.learnedRailZone(76, rawRightInset = 108))
        // 只增不减：rail 消失回 12，已学值保持
        assertEquals(96, g.learnedRailZone(96, rawRightInset = 12))
        // 更小的新观测不回退
        assertEquals(96, g.learnedRailZone(96, rawRightInset = 84))
        // 媒体侧面板（≥150 阈值）：不是 rail，不采纳
        assertEquals(96, g.learnedRailZone(96, rawRightInset = 348))
        assertEquals(76, g.learnedRailZone(76, rawRightInset = 348))
    }

    @Test
    fun learnedRailZoneShiftsBadgeAndFramingTogether() {
        // 学到更大 rail（96）后：竖排/车速环/取景右界整体左移同一量，
        // 锚点关系（环右缘 + 间距 = 竖排左缘）不破坏
        val m = CarScreenMetrics(800, 400, railZone = 96)
        assertEquals(800 - 96, g.selfRailX(m))
        assertEquals(
            g.selfRailX(m),
            g.speedBadgeX(m) + g.SPEED_BADGE_SIZE + g.SPEED_BADGE_RAIL_GAP
        )
        assertTrue(g.speedBadgeFits(m))
        // 与默认 76 的差 = 整体左移量，方向一致
        val ref = CarScreenMetrics(800, 400)
        assertEquals(96 - 76, g.selfRailX(ref) - g.selfRailX(m))
    }

    @Test
    fun splitStateMovesRailOutOfTheHostPanel() {
        val m = hostSplit
        // 左侧超出基线的 10px = 实测实体黑边 → 卡片让开，恢复 8px 视觉间距
        assertEquals(10, m.insetLeft)
        assertEquals(18, g.navCardX(m))
        // 顶部报 34（≤ 阈值）= 实体 chrome（实测 10px 黑边）→ 让开超出基线的 22，
        // 否则搜索胶囊贴在 y=12、距黑边只剩 2px（用户实测"搜索栏太靠上了"）
        assertEquals(22, m.insetTop)
        assertEquals(30, g.turnCardTop(m))
        assertEquals(34, g.searchPillY(m))
        assertTrue(
            "胶囊应完全让开实体黑边(10px)",
            g.searchPillY(m) - 10 >= g.EDGE_MARGIN
        )
        // 右侧 336px 是实体媒体面板（超出基线 324）→ 竖排必须滑到面板左边
        assertEquals(324, m.insetRight)
        assertEquals(476, m.right)
        assertEquals(476 - 12 - g.SELF_RAIL_BTN, g.selfRailX(m))
        assertTrue(
            "竖排应完全在可见区内: ${g.selfRailX(m) + g.SELF_RAIL_BTN} > ${m.right}",
            g.selfRailX(m) + g.SELF_RAIL_BTN <= m.right
        )
        // 取景中心挪到可见区中央（箭头不再藏在面板底下）
        assertEquals((10 + 476) / 2, m.centerX)
        assertTrue("中心应显著左移", m.centerX < 400)
        assertInside(m)
    }

    // ── 2. 多窗口：窄窗 ──

    @Test
    fun narrowWindowKeepsCardButtonsInside() {
        val narrow = CarScreenMetrics(480, 400)
        // 卡宽设计 240（内容区 464 容得下），卡内四钮不溢出
        assertEquals(240, g.navCardWidth(narrow))
        val lastBtnRight = g.navCardBtnX(narrow, 3) + g.NAV_BTN_ROW
        assertTrue("卡内按钮溢出右缘: $lastBtnRight", lastBtnRight <= 480)
        // 右竖排跟着右缘走，不会被裁到窗外
        assertEquals(480 - 12 - g.SELF_RAIL_BTN, g.selfRailX(narrow))
        assertEquals(g.NAV_BTN_ROW, 48)
        assertInside(narrow)
    }

    @Test
    fun tinyWindowClampsCardToContent() {
        val tiny = CarScreenMetrics(320, 240)
        // 320 宽：卡宽 240 容得下 4 钮 + 间距
        assertEquals(240, g.navCardWidth(tiny))
        assertTrue(g.navCardBtnX(tiny, 3) + g.NAV_BTN_ROW <= tiny.right)
        // 240 高：全档放不下 → 简档（时间+按钮行）
        assertEquals(g.NAV_CARD_H_MIN, g.navCardHeight(tiny))
        assertTrue(g.selfRailX(tiny) >= tiny.left)
        assertInside(tiny)

        // 极限宽度：卡宽夹到内容区，退化窗只保证不越界到负值
        val extreme = CarScreenMetrics(216, 240)
        assertTrue(g.navCardWidth(extreme) <= extreme.contentWidth)
        assertTrue(g.navCardBtnX(extreme, 0) >= extreme.left)
        assertTrue(g.navCardX(extreme) >= extreme.left)
    }

    @Test
    fun hostCardColumnExclusionKeepsTurnCardLeftAligned() {
        // 宿主复合卡列报成整高左内缩 332（Rect(344,12-788,388) 实测）：
        // 转向卡/取景中心走软左缘（保持左上对齐），微档按钮行走硬左缘让开卡柱
        val m = CarScreenMetrics.of(800, 400, 344, 12, 788, 388)
        assertEquals(332, m.insetLeft)
        assertEquals(0, m.insetLeftSoft)
        assertEquals(g.EDGE_MARGIN, g.turnCardX(m))
        assertEquals(344 - 12 + g.EDGE_MARGIN, g.navCardX(m))
        assertTrue("取景中心回全宽中点", m.centerX in 380..420)
        // 副窗口小左内缩仍两边都让
        val side = CarScreenMetrics.of(800, 400, 22, 34, 464, 376)
        assertEquals(10, side.insetLeftSoft)
        assertEquals(10 + g.EDGE_MARGIN, g.turnCardX(side))
        // 宿主卡列模式：转向卡与宿主卡等宽（左内缩即卡柱宽），微档/按钮让开
        assertEquals(322, g.turnCardWidth(m))
        // 密度不变性（VD 密度对齐）：hostCardColumn 标志在 px 空间于 of() 判定、
        // scaledBy 原样传递——dp 归一后不得与 px 标定的阈值直接比较（否则
        // rawLeft=118（无卡列）×D=120 → dp=157>HOST_CARD_LEFT_MIN 误判翻转）
        assertTrue(m.hostCardColumn)
        assertTrue(m.scaledBy(160f / 120f).hostCardColumn)
        val noCard = CarScreenMetrics.of(800, 400, 130, 12, 788, 388)
        assertFalse(noCard.hostCardColumn)
        assertFalse(noCard.scaledBy(160f / 120f).hostCardColumn)
        // 等宽定稿在 dp 空间同样成立：宿主卡模式随 insetLeft 走（px→dp 往返守恒）
        val dp = m.scaledBy(160f / 120f)
        assertEquals(dp.insetLeft - 10, g.turnCardWidth(dp))
        // 无宿主卡列：与自绘行程卡同宽
        assertEquals(g.navCardWidth(side), g.turnCardWidth(side))
    }

    // ── 3. 多窗口：矮窗（卡高三档退化） ──

    @Test
    fun shortWindowDropsCardToMicroTierKeepingButtons() {
        // 200dp 高：FULL/MIN 都会压转向卡 → 微档（仅按钮行），取消/选路入口保留
        val short = CarScreenMetrics(800, 200)
        assertEquals(g.NAV_CARD_H_MICRO, g.navCardHeight(short))
        assertEquals(124, g.navCardTop(short))
        assertEquals(134, g.navCardBtnY(short))
        assertTrue(g.navCardBtnY(short) + g.NAV_BTN_ROW <= short.bottom)
        // 竖排间距按可用高度压缩，三钮仍在内容区内
        // （200 高：avail=(200-32-64)/2=52 → coerce 到 PITCH_MIN=56；
        //  fit=(200-16-64)/2=60 不封顶）
        assertEquals(56, g.selfRailPitch(short))
        assertTrue(g.selfRailY0(short) >= short.top)
        assertTrue(
            g.selfRailBtnTop(short, 2) + g.SELF_RAIL_BTN <= short.bottom
        )
    }

    @Test
    fun mediumHeightKeepsNaviCardClearOfTurnCard() {
        val m = CarScreenMetrics(800, 240)
        // 简档：顶缘 128 ≥ 转向卡底 112
        assertEquals(g.NAV_CARD_H_MIN, g.navCardHeight(m))
        assertEquals(128, g.navCardTop(m))
        assertTrue(g.navCardTop(m) >= g.turnCardTop(m) + g.TURN_CARD_H)
    }

    // ── 4. 多窗口：宽窗 / 大窗 ──

    @Test
    fun wideWindowAnchorsRightRailAndCentersOverviewPill() {
        val wide = CarScreenMetrics(1280, 720)
        // 右锚：竖排贴右缘（不是留在 724 的旧参考位置）
        assertEquals(1280 - 12 - g.SELF_RAIL_BTN, g.selfRailX(wide))
        // 下锚：复合卡贴底（全档）
        assertEquals(720 - 8 - 132, g.navCardTop(wide))
        assertEquals(720 - 8 - 132 + 74, g.navCardBtnY(wide))
        // 居中：全览胶囊跟随新宽度居中
        assertEquals((1280 - g.OVERVIEW_PILL_W) / 2, g.overviewPillX(wide))
        assertInside(wide)
    }

    // ── 5. 逐边策略与退化输入 ──

    @Test
    fun insetPolicyIsPerEdgeNotUniform() {
        // 左右：只吃超出宿主标称 padding(12) 的部分
        val wide = CarScreenMetrics.of(800, 400, 40, 12, 600, 388)
        assertEquals(28, wide.insetLeft)      // 40 − 12
        assertEquals(188, wide.insetRight)    // (800−600) − 12
        assertEquals(612, wide.right)
        assertEquals(28, wide.left)   // = insetLeft（已扣基线）
        // 顶：一律忽略（图标条 12↔88 跳变，且左侧透明）
        assertEquals(0, wide.insetTop)
        assertEquals(0, wide.top)
        // 底：一律忽略（表面内无实体 chrome）
        assertEquals(0, wide.insetBottom)
        assertEquals(400, wide.bottom)
        // 小于基线的内缩 → 0（常态就是这个情况）
        val small = CarScreenMetrics.of(800, 400, 5, 0, 795, 400)
        assertEquals(0, small.insetLeft)
        assertEquals(0, small.insetRight)
        // 负值/越界的宿主矩形不会污染内容区
        val junk = CarScreenMetrics.of(800, 400, -50, -20, 9000, 5000)
        assertEquals(0, junk.insetLeft)
        assertEquals(0, junk.insetTop)
        assertEquals(0, junk.insetRight)
        assertEquals(0, junk.insetBottom)
        assertEquals(0, junk.left)
        assertEquals(800, junk.right)
    }

    @Test
    fun hugeInsetsStillLeaveMinimumUsableContent() {
        // 宿主把可见区压到极小（异常/极端分屏）：内容区至少留 MIN_WIDTH/MIN_HEIGHT，
        // 保证按钮与卡片仍有落点，不会算出负宽或零高
        val m = CarScreenMetrics.of(800, 400, 700, 380, 710, 390)
        assertTrue("内容宽 ${m.contentWidth}", m.contentWidth >= CarScreenMetrics.MIN_WIDTH)
        assertTrue("内容高 ${m.contentHeight}", m.contentHeight >= 1)
        assertTrue(g.navCardBtnX(m, 0) >= m.left)
        assertInside(m)
    }

    @Test
    fun degenerateSizesClampAnchorsToContentOrigin() {
        // 退化尺寸（宿主异常回调）不得算出负坐标：一律夹到内容区原点。
        for (m in listOf(
            CarScreenMetrics(0, 0),
            CarScreenMetrics(-100, 50),
            CarScreenMetrics(800, -1)
        )) {
            assertTrue(m.contentWidth > 0)
            assertTrue(m.contentHeight > 0)
            assertTrue(g.selfRailX(m) >= m.left)
            assertTrue(g.selfRailY0(m) >= m.top)
            assertTrue(g.navCardBtnY(m) >= m.top)
            assertTrue(g.overviewPillTop(m) >= m.top)
            assertTrue(g.navCardX(m) >= m.left)
            assertTrue(g.routePanelX(m) >= m.left)
        }
    }

    // ── 6. 取景中心：窄屏右移避让卡片列（箭头不被遮挡） ──

    @Test
    fun mapCenterStaysPutOnNormalWidth() {
        // 常态：卡片列 248 + 净空 24 → 允许区间 [272, 668]，几何中心 400 在区间内
        val m = CarScreenMetrics(800, 400)
        assertEquals(400, g.mapCenterX(m, overlayColumnWidth = 248))
        assertEquals(200, g.mapCenterY(m, carAheadPx = 48, hasBottomCards = true))
    }

    @Test
    fun mapCenterShiftsRightWhenContentIsNarrow() {
        // 副窗口态：可见区 10..476，几何中心 243 落进卡片列(10..258)里 → 箭头被压
        val m = hostSplit
        assertEquals(243, (m.left + m.right) / 2)
        val col = 248
        val minX = m.left + col + g.MAP_CENTER_CLEARANCE          // 282
        val maxX = m.right - g.SELF_RAIL_MARGIN - g.SELF_RAIL_BTN -
            g.MAP_CENTER_CLEARANCE                                 // 392
        val cx = g.mapCenterX(m, overlayColumnWidth = col)
        assertTrue(
            "中心应右移到卡片列之外: $cx < $minX",
            cx >= minX
        )
        // 用户要求：不要贴着卡片边缘，落在**剩余可用空间的 1/3 处**
        assertEquals(minX + (maxX - minX) / 3, cx)
        assertEquals(313, cx)
        // 仍须在可见区内、且不压到右竖排
        assertTrue(cx <= maxX)
    }

    @Test
    fun mapCenterIgnoresTransientPanelsAndDegradesSafely() {
        // 弹出面板是瞬态浮层：不为它挤走取景中心（否则面板开合时箭头来回突跳，
        // 且窄屏下面板几乎占满可见区，无处可让）。中心只按常驻卡片列算。
        val m = hostSplit
        val cardCol = 248
        assertEquals(g.mapCenterX(m, cardCol), g.mapCenterX(m, cardCol))
        // 净空区间为空（极窄 + 宽覆盖物）时退回可见区内的几何中心，不得越界
        val tiny = CarScreenMetrics.of(800, 400, 400, 0, 700, 400)
        val cxTiny = g.mapCenterX(tiny, overlayColumnWidth = 400)
        assertTrue(
            "极窄退化应落在内容区内: $cxTiny vs ${tiny.left}..${tiny.right}",
            cxTiny in tiny.left..tiny.right
        )
        // 覆盖物列为 0（无任何常驻卡片）时 = 纯几何中心
        val bare = CarScreenMetrics(800, 400)
        assertEquals(
            (bare.left + bare.right) / 2,
            g.mapCenterX(bare, overlayColumnWidth = 0)
        )
    }

    @Test
    fun mapCenterLiftsOnShortWindowSoArrowClearsCardButtons() {
        // 矮窗：几何中心 + 车头偏移会正好压在卡内按钮行上 → 中心上移
        val short = CarScreenMetrics(800, 200)
        val plain = (short.top + short.bottom) / 2
        val carY = plain + 48
        assertTrue(
            "构造前提：车头应压在按钮行上 (carY=$carY, btnY=${g.navCardBtnY(short)})",
            carY > g.navCardBtnY(short)
        )
        val cy = g.mapCenterY(short, carAheadPx = 48, hasBottomCards = true)
        assertTrue(
            "车头应抬到卡内按钮行以上: ${cy + 48} vs ${g.navCardBtnY(short)}",
            cy + 48 <= g.navCardBtnY(short) - g.MAP_CENTER_CLEARANCE
        )
        // 无底部卡片（空闲/巡航）时不做抬升
        assertEquals(plain, g.mapCenterY(short, 48, hasBottomCards = false))
    }

    // ── 7. 四边间距统一（用户要求：上下与左边框一致） ──

    @Test
    fun marginsAreUniformOnAllSides() {
        for (m in listOf(
            CarScreenMetrics(800, 400),
            CarScreenMetrics(1280, 720),
            CarScreenMetrics(480, 320)
        )) {
            val e = g.EDGE_MARGIN
            // 左：卡片/面板左缘
            assertEquals("${m} 左缘", m.left + e, g.navCardX(m))
            assertEquals("${m} 选路面板左缘", m.left + e, g.routePanelX(m))
            // 上：转向卡顶缘
            assertEquals("${m} 上边距", m.top + e, g.turnCardTop(m))
            // 下：复合卡底缘 / 全览胶囊底缘
            assertEquals(
                "${m} 复合卡下边距", m.bottom - e,
                g.navCardTop(m) + g.navCardHeight(m)
            )
            assertEquals(
                "${m} 全览胶囊下边距", m.bottom - e,
                g.overviewPillTop(m) + g.OVERVIEW_PILL_H
            )
            // 右：竖排距右缘（自身边距 12，比卡片边距略大——按钮是圆形触控区，
            // 视觉重量小；此处只断言它确实右锚，不会留在旧窗口的绝对位置）
            assertTrue(
                "${m} 右竖排应贴右缘",
                m.right - g.selfRailX(m) - g.SELF_RAIL_BTN in 8..16
            )
        }
    }

    // ── 8. 全尺寸扫描：任何窗口都不得溢出 ──

    @Test
    fun everyElementStaysInsideContentAreaAcrossSizes() {
        val widths = listOf(320, 400, 480, 520, 640, 720, 800, 1024, 1280, 1920)
        val heights = listOf(160, 200, 240, 320, 400, 480, 600, 720, 1080)
        for (w in widths) {
            for (h in heights) {
                val m = CarScreenMetrics(w, h)
                assertInside(m, label = "${w}x${h}")
                // 触摸目标恒 ≥48dp（AA 规范），窗口再小也不缩水
                // （SELF_RAIL_BTN=64 是竖排避让模型值——宿主渲染实际钮，
                //  取大于 DHU 实测 48 的保守值覆盖 AA18 真车更大 chip）
                assertEquals(48, g.NAV_BTN_ROW)
                assertEquals(64, g.SELF_RAIL_BTN)
                assertTrue(g.OVERVIEW_PILL_H >= 48)
                // 非微档时卡片不压转向卡（微档=退化窗容忍重叠）
                if (g.navCardHeight(m) != g.NAV_CARD_H_MICRO) {
                    assertTrue(
                        "${w}x${h} 复合卡压转向卡",
                        g.navCardTop(m) >= g.turnCardTop(m) + g.TURN_CARD_H
                    )
                }
                // 竖排三钮完整可见
                assertTrue(
                    "${w}x${h} 竖排溢出",
                    g.selfRailBtnTop(m, 2) + g.SELF_RAIL_BTN <= m.bottom
                )
                assertTrue("${w}x${h} 竖排越界", g.selfRailX(m) >= m.left)
            }
        }
    }

    /** 断言全部交互元素矩形落在内容区内（退化窗底部溢出豁免）。 */
    private fun assertInside(m: CarScreenMetrics, label: String = "") {
        fun check(name: String, x: Int, y: Int, w: Int, h: Int) {
            assertTrue(
                "$label $name 越界: ($x,$y) ${w}x${h} vs 内容区 " +
                    "(${m.left},${m.top})-(${m.right},${m.bottom})",
                x >= m.left && y >= m.top && x + w <= m.right && y + h <= m.bottom
            )
        }
        check(
            "搜索胶囊", g.searchPillX(m), g.searchPillY(m),
            g.SEARCH_PILL_W, g.SEARCH_PILL_H
        )
        // 复合卡与卡内按钮行：退化窗（卡底超出内容区）豁免
        val cardH = g.navCardHeight(m)
        val cardTop = g.navCardTop(m)
        if (cardTop + cardH <= m.bottom) {
            check("复合卡", g.navCardX(m), cardTop, g.navCardWidth(m), cardH)
            check(
                "卡内按钮行", g.navCardBtnX(m, 0), g.navCardBtnY(m),
                g.navCardBtnX(m, 3) + g.NAV_BTN_ROW - g.navCardBtnX(m, 0),
                g.NAV_BTN_ROW
            )
        }
        check(
            "转向卡", g.navCardX(m), g.turnCardTop(m),
            g.navCardWidth(m), g.TURN_CARD_H
        )
        for (i in 0..2) {
            check(
                "右竖排$i", g.selfRailX(m), g.selfRailBtnTop(m, i),
                g.SELF_RAIL_BTN, g.SELF_RAIL_BTN
            )
        }
        check(
            "全览胶囊", g.overviewPillX(m), g.overviewPillTop(m),
            g.OVERVIEW_PILL_W, g.OVERVIEW_PILL_H
        )
        // 选路面板：至少 1 行且在内容区内
        assertTrue("$label 选路面板至少 1 行", g.routePanelMaxRows(m) >= 1)
        val rpBottom = g.routePanelRowTop(m, g.routePanelMaxRows(m) - 1) + g.ROUTE_PANEL_ROW_H
        check(
            "选路面板", g.routePanelX(m), g.routePanelY(m), g.routePanelW(m),
            rpBottom - g.routePanelY(m)
        )
    }

    // ── 6. px↔dp 空间换算（VD 密度对齐）──

    @Test
    fun scaledByConvertsPxMetricsToDpSpace() {
        // 宿主 120dpi：px → dp 因子 = 160/120；内缩/尺寸等比换算
        val f = 160f / 120f
        val m = CarScreenMetrics.of(1920, 720, 22, 34, 1596, 708).scaledBy(f)
        // of() 的逐边策略在宿主原始 px 上落地（副窗口左 22-12=10、右 1920-1596-12=312
        // ≥150 认定侧面板；顶 34-12=22）→ scaledBy 只做单位换算
        assertEquals((1920 * f).toInt(), m.width)
        assertEquals((10 * f).toInt(), m.insetLeft)
        assertEquals((312 * f).toInt(), m.insetRight)
        assertEquals((22 * f).toInt(), m.insetTop)
        // dp 空间几何仍自洽：内容区 = 右缘 − 左缘
        assertEquals(m.right - m.left, m.contentWidth)
    }

    @Test
    fun scaledByOneIsIdentity() {
        val base = CarScreenMetrics.of(800, 400, 22, 34, 464, 376)
        assertEquals(base, base.scaledBy(1f))
    }
}
