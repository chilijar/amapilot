package com.amapilot.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高德分享链接解析测试（纯 URL 解析路径，不含网络展开）。
 *
 * 关键点：uri.amap.com 的 position 参数**经度在前**；
 * coordinate 参数决定坐标系（gaode 默认 GCJ-02，wgs84/baidu 需转换）。
 */
class AmapShareResolverTest {

    @Test
    fun `detects amap share links`() {
        assertTrue(AmapShareResolver.isAmapShare("麦当劳 https://surl.amap.com/c0pkNl0c1vt"))
        assertTrue(
            AmapShareResolver.isAmapShare(
                "https://uri.amap.com/marker?position=121.47,31.23&name=x&coordinate=gaode"
            )
        )
        assertFalse(AmapShareResolver.isAmapShare("https://example.com/121.47,31.23"))
        assertFalse(AmapShareResolver.isAmapShare("我在麦当劳"))
    }

    @Test
    fun `parses direct uri with lng first and gaode coordinate`() {
        val p = AmapShareResolver.resolve(
            "https://uri.amap.com/marker?position=121.484124,31.236434&name=%E9%BA%A6%E5%BD%93%E5%8A%B3&coordinate=gaode&callnative=0"
        )
        assertEquals(31.236434, p?.lat ?: 0.0, 1e-9)
        assertEquals(121.484124, p?.lng ?: 0.0, 1e-9)
    }

    @Test
    fun `parses direct uri defaulting to gcj02 without coordinate param`() {
        val p = AmapShareResolver.resolve(
            "https://uri.amap.com/marker?position=116.397428,39.90923&name=here"
        )
        assertEquals(39.90923, p?.lat ?: 0.0, 1e-9)
        assertEquals(116.397428, p?.lng ?: 0.0, 1e-9)
    }

    @Test
    fun `converts wgs84 coordinate to gcj02`() {
        val p = AmapShareResolver.resolve(
            "https://uri.amap.com/marker?position=116.338,39.992&coordinate=wgs84"
        )
        // 北京附近偏移 ~+0.0065E/+0.0013N（wgs84ToGcj02 参考值）
        assertEquals(39.993334, p?.lat ?: 0.0, 1e-4)
        assertEquals(116.344167, p?.lng ?: 0.0, 1e-4)
    }

    @Test
    fun `rejects malformed position`() {
        assertNull(
            AmapShareResolver.resolve("https://uri.amap.com/marker?name=x")
        )
        assertNull(
            AmapShareResolver.resolve("https://uri.amap.com/marker?position=abc,def")
        )
    }

    @Test
    fun `parses tencent qq map marker uri (lat first, title as name)`() {
        // 微信位置分享载荷形态：apis.map.qq.com/uri/v1/marker?marker=coord=lat,lng;title=..;addr=..
        val url = "https://apis.map.qq.com/uri/v1/marker?marker=coord=31.089698,120.981409;" +
            "title=%E6%8A%A5%E5%9B%BD%E5%AF%BA;addr=%E9%9D%92%E6%B5%A6%E5%8C%BA;tel=;" +
            "category=&referer=weixin"
        val p = AmapShareResolver.parseAmapUrl(url)
        assertEquals(31.089698, p?.lat ?: 0.0, 1e-9)
        assertEquals(120.981409, p?.lng ?: 0.0, 1e-9)
        assertEquals("报国寺", AmapShareResolver.nameFromUrl(url))
        val r = AmapShareResolver.resolveNamed("微信位置 $url")
        assertEquals("报国寺", r?.name)
    }

    @Test
    fun `parses wb amap landing p param (lat first, name 4th segment)`() {
        // 2026-11 真分享抓证：surl 短链首跳落地 wb.amap.com，p=poiid,lat,lng,name,addr
        val url = "https://wb.amap.com/?commonBizInfo=x" +
            "&p=B001508146,31.08969896623761,120.98140910267827,%E6%8A%A5%E5%9B%BD%E5%AF%BA," +
            "%E6%9C%B1%E5%AE%B6%E8%A7%92%E9%95%87&src=app_C3090"
        val p = AmapShareResolver.parseAmapUrl(url)
        assertEquals(31.08969896623761, p?.lat ?: 0.0, 1e-9)
        assertEquals(120.98140910267827, p?.lng ?: 0.0, 1e-9)
        assertEquals("报国寺", AmapShareResolver.nameFromUrl(url))
        // 无网络路径：resolveNamed 对含该链的文本直解
        val r = AmapShareResolver.resolveNamed("报国寺 $url")
        assertEquals("报国寺", r?.name)
    }

    @Test
    fun `parses callAPP android embedded p param`() {
        // m.amap.com/callAPP：android= 内嵌 androidamap 深链（编码一层）
        val url = "https://m.amap.com/callAPP?ios=x&android=androidamap%3Faction%3Dshorturl" +
            "%26p%3DB001508146%2C31.08969896623761%2C120.98140910267827%2C%25E6%258A%25A5%25E5" +
            "%259B%25BD%25E5%25AF%25BA"
        val p = AmapShareResolver.parseAmapUrl(url)
        assertEquals(31.08969896623761, p?.lat ?: 0.0, 1e-9)
        assertEquals(120.98140910267827, p?.lng ?: 0.0, 1e-9)
        assertEquals("报国寺", AmapShareResolver.nameFromUrl(url))
    }
}
