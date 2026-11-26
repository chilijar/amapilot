package com.amapilot.auto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 已走灰线棘轮（[advanceGrayRemain]）行为：防「灰线超前当前位置」与「灰线回跳」。
 *
 * 背景：灰线边界由引擎剩余里程沿路线弧长切分；棘轮只减不增会把一次性
 * 错小值（选路窗口旧路线 remain / 引擎毛刺）永久锁存 → 灰线盖过当前位置
 * 且不恢复。速度帽拒绝单 tick 超物理上限的骤降。
 */
class CarMapDisplayTest {

    @Test
    fun `unlatched ratchet adopts first remain`() {
        assertEquals(5000, advanceGrayRemain(0, 5000, 1.0))
        assertEquals(5000, advanceGrayRemain(-1, 5000, 1.0))
    }

    @Test
    fun `normal driving decrease is adopted`() {
        // 1s tick、降 30m（≈108km/h）在速度帽内
        assertEquals(4970, advanceGrayRemain(5000, 4970, 1.0))
    }

    @Test
    fun `remain increase never rolls gray back`() {
        // 引擎抖动让 remain 回增：棘轮保持旧锁存（灰线不回跳）
        assertEquals(4900, advanceGrayRemain(4900, 5200, 1.0))
    }

    @Test
    fun `single-tick plunge beyond speed cap is rejected`() {
        // dt=1s → maxDrop = 40*1+50 = 90m；骤降 1200m（选路窗口旧路线值/毛刺）拒绝
        assertEquals(5000, advanceGrayRemain(5000, 3800, 1.0))
    }

    @Test
    fun `drop exactly at cap is accepted`() {
        assertEquals(4910, advanceGrayRemain(5000, 4910, 1.0))
    }

    @Test
    fun `cap scales with tick interval`() {
        // dt=10s → maxDrop = 450m；降 400m 接受
        assertEquals(4600, advanceGrayRemain(5000, 4600, 10.0))
        // 降 460m 拒绝
        assertEquals(5000, advanceGrayRemain(5000, 4540, 10.0))
    }

    @Test
    fun `negative dt treated as zero`() {
        // maxDrop = 50m 容差；降 80m 拒绝
        assertEquals(5000, advanceGrayRemain(5000, 4920, -1.0))
        // 降 40m 接受
        assertEquals(4960, advanceGrayRemain(5000, 4960, -1.0))
    }

    @Test
    fun `traveled boundary starts at route origin despite engine offset`() {
        // 真机实测：t=0 引擎 remain0=21075 < 几何总长 21377（系统偏差 302m）
        val total0 = 21377.0
        val remain0 = grayBaselineRemain0(total0, 21075)
        assertEquals(21075, remain0)
        // 出发时 traveled=0 → 边界 = 总长（路线起点，灰线不超前箭头）
        assertEquals(total0, grayTraveledBoundary(total0, remain0, 21075), 0.001)
        // 走 1000m 后 → 边界距终点 20377（= 距起点 1000m）
        assertEquals(20377.0, grayTraveledBoundary(total0, remain0, 20075), 0.001)
        // 引擎 remain 归零时 traveled=remain0 → 边界停在距终点 δ=302m 处
        // （尾部缺口 = 系统偏差；到达流程随即清线，视觉不可见）
        assertEquals(302.0, grayTraveledBoundary(total0, remain0, 0), 0.001)
    }

    @Test
    fun `mid-route rebuild falls back to raw remain`() {
        // 中途 Presentation 重建：remain=10000 与 total 差 11377 > 1500 阈值
        // → remain0 = total（δ=0），边界退回直接用 remain（旧行为）
        val total0 = 21377.0
        val remain0 = grayBaselineRemain0(total0, 10000)
        assertEquals(total0.toInt(), remain0)
        assertEquals(10000.0, grayTraveledBoundary(total0, remain0, 10000), 1.0)
        assertEquals(9000.0, grayTraveledBoundary(total0, remain0, 9000), 1.0)
    }

    @Test
    fun `boundary clamped to total when first remain overshoots`() {
        // 引擎首帧 remain > 几何总长（毛刺）：边界夹在 total，不越界
        val total0 = 5000.0
        val remain0 = grayBaselineRemain0(total0, 5300)
        assertEquals(5300, remain0)
        assertEquals(total0, grayTraveledBoundary(total0, remain0, 5300), 0.001)
    }

    // ── GPS 投影主通道（projectOnRoute）──
    // 测试路线：赤道子午线向北 3 段，每段 0.001° lat ≈ 111.32m，总长 ≈ 333.96m
    private val lats = doubleArrayOf(0.0, 0.001, 0.002, 0.003)
    private val lngs = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private val cum = doubleArrayOf(333.96, 222.64, 111.32, 0.0)

    @Test
    fun `projection on segment midpoint is continuous`() {
        // 第 1 段中点（lat 0.0005）：边界 = 段起点弧长与段终点的插值中点
        val r = projectOnRoute(lats, lngs, cum, 0.0005, 0.0)
        requireNotNull(r)
        assertEquals(278.3, r.first, 0.5)
        assertEquals(0.0, r.second, 1.0)
    }

    @Test
    fun `lateral offset reported in meters`() {
        // 经度偏 0.0001°（赤道 ≈ 11.13m）：垂距量得、弧长仍按投影点
        val r = projectOnRoute(lats, lngs, cum, 0.0015, 0.0001)
        requireNotNull(r)
        assertEquals(11.1, r.second, 1.0)
        assertEquals(166.98, r.first, 1.0)
    }

    @Test
    fun `projection clamps at route end`() {
        // 终点外 0.001°：夹在末顶点，弧长 = 0
        val r = projectOnRoute(lats, lngs, cum, 0.004, 0.0)
        requireNotNull(r)
        assertEquals(0.0, r.first, 0.001)
    }

    @Test
    fun `window excludes far u-turn mirror segment`() {
        // 掉头歧义回归：窗口锁定路线尾段（lastCum=30, ±25m）时，
        // 车实际在第 1 段中点（镜像弧长差 ~250m+）——全局最近点会被窗口
        // 排除，返回的垂距巨大（>60m 阈值）→ 调用方退回引擎 fallback，
        // 而非把边界误吸到 278m 处提前盖灰
        val r = projectOnRoute(lats, lngs, cum, 0.0005, 0.0, lastCum = 30.0, windowMeters = 25.0)
        requireNotNull(r)
        assert(r.second > GRAY_PROJ_MAX_OFFSET_M) {
            "windowed projection should report unreliable offset, got ${r.second}"
        }
    }

    @Test
    fun `window centered on true position keeps projection`() {
        // 正常行驶：窗口中心在车附近 → 投影照常精确
        val r = projectOnRoute(lats, lngs, cum, 0.0015, 0.0, lastCum = 166.98)
        requireNotNull(r)
        assertEquals(166.98, r.first, 1.0)
        assertEquals(0.0, r.second, 1.0)
    }

    // ── 统一边界棘轮（ratchetBoundary）──

    @Test
    fun `unlatched boundary adopts first projection`() {
        assertEquals(500.0, ratchetBoundary(-1.0, 500.0, 1.0), 0.001)
    }

    @Test
    fun `boundary never rolls back`() {
        // GPS 噪声让投影回跳：保持旧边界（灰线不回缩）
        assertEquals(400.0, ratchetBoundary(400.0, 450.0, 1.0), 0.001)
        // 锁存到 0（路线末端）同样有效，不被当作未锁存
        assertEquals(0.0, ratchetBoundary(0.0, 50.0, 1.0), 0.001)
    }

    @Test
    fun `forward jump beyond speed cap is clamped`() {
        // dt=1s → 单拍最多推进 40*1+50=90m（误吸/毛刺不一次盖掉大段）
        assertEquals(910.0, ratchetBoundary(1000.0, 500.0, 1.0), 0.001)
        // 帽内正常推进
        assertEquals(970.0, ratchetBoundary(1000.0, 970.0, 1.0), 0.001)
    }
}
