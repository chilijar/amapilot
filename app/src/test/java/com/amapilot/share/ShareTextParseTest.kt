package com.amapilot.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTextParseTest {

    @Test
    fun `baidu link with latlng converts bd09`() {
        val p = parseCoordsFromText("我在这里 https://map.baidu.com/?latlng=39.915,116.404")
        assertEquals(39.908657, p!!.lat, 1e-5)
        assertEquals(116.397627, p.lng, 1e-5)
    }

    @Test
    fun `bare coordinates parsed as gcj02`() {
        val p = parseCoordsFromText("meet at 39.909,116.397 ok")
        assertEquals(39.909, p!!.lat, 1e-9)
        assertEquals(116.397, p.lng, 1e-9)
    }

    @Test
    fun `location parameter parsed`() {
        val p = parseCoordsFromText("baidumap://map?location=39.915,116.404")
        assertEquals(39.908657, p!!.lat, 1e-5)
    }

    @Test
    fun `non-baidu url keeps raw coords`() {
        val p = parseCoordsFromText("https://amap.com/?latlng=39.9,116.4")
        assertEquals(39.9, p!!.lat, 1e-9)
    }

    @Test
    fun `no coordinates returns null`() {
        assertNull(parseCoordsFromText("hello world"))
    }

    @Test
    fun `short numbers ignored`() {
        assertNull(parseCoordsFromText("v1.2 update"))
    }
}
