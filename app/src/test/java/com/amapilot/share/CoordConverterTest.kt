package com.amapilot.share

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WGS84 → GCJ-02 转换参考值测试。
 *
 * 参考值由标准国测局偏移算法生成；重点守住：
 * 境内偏移量级与方向（华南纬度向南偏——未转换时点会向北漂移数百米）、
 * 境外原样返回。
 */
class CoordConverterTest {

    @Test
    fun `beijing shifts north-east`() {
        val p = CoordConverter.wgs84ToGcj02(39.992, 116.338)
        assertEquals(39.993334, p.lat, 1e-4)
        assertEquals(116.344167, p.lng, 1e-4)
    }

    @Test
    fun `shanghai shifts south-east`() {
        val p = CoordConverter.wgs84ToGcj02(31.2304, 121.4737)
        assertEquals(31.228458, p.lat, 1e-4)
        assertEquals(121.478223, p.lng, 1e-4)
    }

    @Test
    fun `shenzhen shifts south-east`() {
        val p = CoordConverter.wgs84ToGcj02(22.5431, 114.0579)
        assertEquals(22.540383, p.lat, 1e-4)
        assertEquals(114.063014, p.lng, 1e-4)
    }

    @Test
    fun `outside china passes through unchanged`() {
        val p = CoordConverter.wgs84ToGcj02(35.6895, 139.6917)
        assertEquals(35.6895, p.lat, 0.0)
        assertEquals(139.6917, p.lng, 0.0)
    }

    @Test
    fun `inland offsets stay within expected magnitude`() {
        // 国内偏移应在数百米量级（< 0.01deg ≈ 1km）
        val cases = listOf(
            39.992 to 116.338,
            31.2304 to 121.4737,
            22.5431 to 114.0579,
            30.5728 to 104.0668
        )
        cases.forEach { (lat, lng) ->
            val p = CoordConverter.wgs84ToGcj02(lat, lng)
            assert(kotlin.math.abs(p.lat - lat) < 0.01) { "lat offset ${(p.lat - lat)} too large" }
            assert(kotlin.math.abs(p.lng - lng) < 0.01) { "lng offset ${(p.lng - lng)} too large" }
        }
    }
}
