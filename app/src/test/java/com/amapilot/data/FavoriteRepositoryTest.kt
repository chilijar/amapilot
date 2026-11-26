package com.amapilot.data

import com.amapilot.map.SearchedPoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteRepositoryTest {

    private val poi = SearchedPoi("id1", "星巴克", "北京市朝阳区", 39.9, 116.4)

    @Test
    fun `upsert appends new place`() {
        val result = FavoriteRepository.upsert(emptyList(), FavoriteRepository.fromPoi(poi))
        assertEquals(1, result.size)
        assertEquals("星巴克", result[0].name)
    }

    @Test
    fun `upsert same id replaces`() {
        val first = FavoriteRepository.fromPoi(poi)
        val renamed = first.copy(name = "星巴克(改名)")
        val result = FavoriteRepository.upsert(listOf(first), renamed)
        assertEquals(1, result.size)
        assertEquals("星巴克(改名)", result[0].name)
    }

    @Test
    fun `home kind is unique`() {
        val home1 = FavoritePlace("a", "家1", "", 39.0, 116.0, FavoritePlace.Kind.HOME)
        val home2 = FavoritePlace("b", "家2", "", 39.1, 116.1, FavoritePlace.Kind.HOME)
        val result = FavoriteRepository.upsert(listOf(home1), home2)
        assertEquals(listOf(home2), result)
    }

    @Test
    fun `normal kind allows duplicates of kind`() {
        val n1 = FavoritePlace("a", "地点1", "", 39.0, 116.0)
        val n2 = FavoritePlace("b", "地点2", "", 39.1, 116.1)
        assertEquals(2, FavoriteRepository.upsert(listOf(n1), n2).size)
    }

    @Test
    fun `capacity truncates oldest`() {
        val existing = (1..FavoriteRepository.MAX_FAVORITES).map {
            FavoritePlace("id$it", "地点$it", "", 39.0 + it * 0.001, 116.0)
        }
        val result = FavoriteRepository.upsert(existing, FavoritePlace("new", "新地点", "", 39.5, 116.5))
        assertEquals(FavoriteRepository.MAX_FAVORITES, result.size)
        assertEquals("新地点", result.last().name)
        assertTrue(result.none { it.id == "id1" })
    }

    @Test
    fun `fromPoi generates coordinate id when blank`() {
        val noId = poi.copy(id = "")
        val fav = FavoriteRepository.fromPoi(noId)
        assertTrue(fav.id.isNotBlank())
        assertTrue(fav.id.startsWith("39.9000"))
    }
}
