package com.amapilot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏有效性核查的纯函数部分：名称匹配与距离计算。
 * （周边回搜走 PoiSearcher 设备侧，不入单测；防误报原则 = 搜索失败不报。）
 */
class FavoriteValidatorTest {

    @Test
    fun `exact and whitespace variants match`() {
        assertTrue(FavoriteValidator.nameMatches("东方明珠", "东方明珠"))
        assertTrue(FavoriteValidator.nameMatches("东方 明珠", "东方明珠"))
    }

    @Test
    fun `containment matches both directions`() {
        // 收藏存全称、回搜回简称（或反之：分店名包含主名）
        assertTrue(FavoriteValidator.nameMatches("东方明珠广播电视塔", "东方明珠"))
        assertTrue(FavoriteValidator.nameMatches("东方明珠", "东方明珠广播电视塔"))
        assertTrue(FavoriteValidator.nameMatches("星巴克", "星巴克咖啡(陆家嘴店)"))
    }

    @Test
    fun `different places do not match`() {
        assertFalse(FavoriteValidator.nameMatches("东方明珠", "明珠花园小区"))
        assertFalse(FavoriteValidator.nameMatches("", "东方明珠"))
        assertFalse(FavoriteValidator.nameMatches("东方明珠", ""))
    }

    @Test
    fun `distance approximates meters`() {
        // 纬度 0.001° ≈ 111.32m
        assertEquals(
            111.32,
            FavoriteValidator.distanceMeters(31.0, 121.0, 31.001, 121.0),
            1.0
        )
        // 经度方向按 cos(lat) 收缩：赤道 0.001° ≈ 111.32m，60° 纬度 ≈ 55.66m
        assertEquals(
            55.66,
            FavoriteValidator.distanceMeters(60.0, 121.0, 60.0, 121.001),
            1.0
        )
        assertEquals(0.0, FavoriteValidator.distanceMeters(31.0, 121.0, 31.0, 121.0), 0.001)
    }
}
